#!/usr/bin/env python3
"""Loopback PP-StructureV3 adapter used by the XueXue desktop client."""

from __future__ import annotations

import argparse
import base64
import binascii
import io
import ipaddress
import json
import logging
import os
import sys
import threading
from urllib.parse import parse_qs
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

try:
    import resource
except ImportError:  # resource is not part of the Windows standard library.
    resource = None  # type: ignore[assignment]


DEFAULT_VERSION = "3.x-sidecar"
DEFAULT_MAX_REQUEST_BYTES = 64 * 1024 * 1024


def json_safe(value: Any) -> Any:
    """Convert Paddle/numpy values into values accepted by json.dumps."""
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    if hasattr(value, "tolist"):
        return json_safe(value.tolist())
    if hasattr(value, "item"):
        return json_safe(value.item())
    return str(value)


def result_json(result: Any) -> Any:
    """Read both the 3.x result object and older dict-like adapters."""
    value = result
    if hasattr(value, "json"):
        value = value.json
        if callable(value):
            value = value()
    if hasattr(value, "to_json"):
        value = value.to_json
        if callable(value):
            value = value()
    if isinstance(value, str):
        return json.loads(value)
    if isinstance(value, dict):
        return json_safe(value)
    if hasattr(value, "to_dict"):
        return json_safe(value.to_dict())
    return json_safe(value)


def first_result(results: Any) -> Any:
    if isinstance(results, (list, tuple)):
        if not results:
            return {}
        return results[0]
    try:
        return next(iter(results))
    except TypeError:
        return results


def nested_text(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("text", "block_content", "rec_text", "content"):
            if isinstance(value.get(key), str):
                return value[key]
        for key in ("rec_texts", "texts"):
            if isinstance(value.get(key), list):
                return "\n".join(str(item) for item in value[key])
    return ""


def normalize_result(value: Any) -> dict[str, Any]:
    """Keep the engine's parsing list while tolerating Paddle result wrappers."""
    if isinstance(value, list):
        return {"parsing_res_list": [json_safe(item) for item in value]}
    if not isinstance(value, dict):
        return {"parsing_res_list": []}

    for key in ("parsing_res_list", "blocks"):
        if isinstance(value.get(key), list):
            return {
                "parsing_res_list": [
                    normalize_block(
                        item,
                        layout_boxes=value.get("layout_det_res") or value.get("layout_res"),
                        ocr_result=value.get("overall_ocr_res"),
                    )
                    for item in value[key]
                ]
            }
    for key in ("res", "result", "output"):
        nested = value.get(key)
        if isinstance(nested, dict):
            normalized = normalize_result(nested)
            if normalized["parsing_res_list"]:
                return normalized

    # Some Paddle versions expose layout detection and OCR results separately.
    layout = value.get("layout_det_res") or value.get("layout_res") or value.get("layout")
    if isinstance(layout, list):
        return {"parsing_res_list": [normalize_block(item) for item in layout]}
    return {"parsing_res_list": []}


def normalize_block(
    value: Any,
    layout_boxes: Any = None,
    ocr_result: Any = None,
) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {"block_label": "unknown", "block_content": str(value), "block_bbox": [0, 0, 1, 1]}
    block = dict(json_safe(value))
    block.setdefault("block_label", block.get("label", block.get("type", "unknown")))
    block.setdefault("block_bbox", block.get("bbox", block.get("block_polygon", [0, 0, 1, 1])))
    if "block_content" not in block:
        block["block_content"] = nested_text(block.get("block_rec_res", block.get("text", "")))
    block_box = bbox_xyxy(block.get("block_bbox"))
    if block_box is not None:
        detection = best_layout_match(block_box, layout_boxes, block.get("block_label"))
        if detection is not None:
            block.setdefault("block_detection_score", detection[1])
        recognition = best_ocr_match(block_box, ocr_result)
        if recognition is not None:
            block.setdefault("block_recognition_score", recognition)
    return block


def env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def canonical_device(value: str) -> str:
    normalized = value.strip()
    lowered = normalized.lower()
    if lowered in {"", "auto"}:
        return "auto"
    if lowered in {"gpu", "cuda"}:
        return "gpu:0"
    if lowered.startswith("cuda:"):
        return f"gpu:{normalized.split(':', 1)[1]}"
    if lowered == "xpu":
        return "xpu:0"
    return normalized


def device_backend(device: str) -> str:
    lowered = device.lower()
    if lowered == "cpu":
        return "cpu"
    if lowered == "gpu" or lowered.startswith("gpu:") or lowered == "cuda" or lowered.startswith("cuda:"):
        return "cuda"
    if lowered == "xpu" or lowered.startswith("xpu:"):
        return "xpu"
    custom = lowered.removeprefix("custom_device:").removeprefix("custom:")
    return f"custom:{custom.split(':', 1)[0]}"


def device_matches(target: str, available: str) -> bool:
    def identity(value: str) -> str:
        return (
            value.lower()
            .removeprefix("custom_device:")
            .removeprefix("custom:")
            .removesuffix(":0")
        )

    return identity(target) == identity(available)


def string_list(value: Any) -> list[str]:
    if not isinstance(value, (list, tuple)):
        return []
    return [str(item) for item in value if str(item).strip()]


def is_accelerated_device(device: str) -> bool:
    return bool(device.strip()) and device_backend(device) != "cpu"


def custom_device_type(device: str) -> str | None:
    backend = device_backend(device)
    if not backend.startswith("custom:"):
        return None
    device_type = backend.removeprefix("custom:")
    return device_type or None


def pipeline_device_name(device: str) -> str:
    """Add an explicit device id for PaddleOCR custom-device parsing."""
    device_type = custom_device_type(device)
    if device_type is None or ":" in device:
        return device
    return f"{device_type}:0"


def configure_custom_device_support(device: str) -> None:
    """Bridge PaddleX 3.7's device whitelist to Paddle custom devices.

    Paddle 3.3 exposes custom-device inference through
    ``InferenceConfig.enable_custom_device``. PaddleX 3.7 does not yet expose
    arbitrary custom device names in its parser or static runner, so the
    compatibility layer is installed only for a detected custom backend.
    """
    if custom_device_type(device) is None:
        return

    try:
        import paddlex.utils.device as paddlex_device
        from paddlex.inference.models.runners.paddle_static import runner as static_runner
        from paddlex.inference.models.runners.paddle_static.config import pp_option
    except ImportError as error:
        raise RuntimeError("PaddleX is required for Paddle custom-device inference") from error

    device_type = custom_device_type(device)
    assert device_type is not None
    if device_type not in paddlex_device.SUPPORTED_DEVICE_TYPE:
        paddlex_device.SUPPORTED_DEVICE_TYPE.append(device_type)
    if device_type not in pp_option.PaddlePredictorOption.SUPPORT_DEVICE:
        pp_option.PaddlePredictorOption.SUPPORT_DEVICE += (device_type,)

    if getattr(static_runner.PaddleStaticRunner, "_xuexue_custom_device_patch", False):
        return

    original_create = static_runner.PaddleStaticRunner._create

    def create_runner(self: Any) -> Any:
        selected_device = self._config.get("device_type", "cpu")
        selected_custom_device = custom_device_type(selected_device)
        if selected_custom_device is None:
            return original_create(self)
        if self._config.get("run_mode", "paddle").startswith("trt"):
            raise RuntimeError(
                f"TensorRT is not supported on Paddle custom device {selected_custom_device!r}"
            )

        paddle_inference = static_runner.import_paddle_module("paddle.inference")
        model_paths = static_runner.get_model_paths(self.model_dir, self.model_file_prefix)
        if "paddle" not in model_paths:
            raise RuntimeError("No valid PaddlePaddle model found")
        model_file, params_file = model_paths["paddle"]
        config = paddle_inference.Config(str(model_file), str(params_file))
        config.enable_custom_device(selected_custom_device, self._config.get("device_id", 0) or 0)
        if hasattr(config, "disable_mkldnn"):
            config.disable_mkldnn()
        if hasattr(config, "enable_new_ir"):
            config.enable_new_ir(self._config.get("enable_new_ir", True))
            if self._config.get("enable_new_ir") and self._config.get("enable_cinn"):
                config.enable_cinn()
        if hasattr(config, "enable_new_executor"):
            config.enable_new_executor()
        config.set_optimization_level(3)
        return paddle_inference.create_predictor(config)

    static_runner.PaddleStaticRunner._create = create_runner
    static_runner.PaddleStaticRunner._xuexue_custom_device_patch = True


def runtime_matches_requested_device(runtime: Any, requested_device: str) -> bool:
    """Require the health response to honor explicit and GPU-first device requests."""
    if not isinstance(runtime, dict) or runtime.get("device_available") is not True:
        return False
    selected = runtime.get("device")
    if not isinstance(selected, str) or not selected.strip():
        return False

    requested = canonical_device(requested_device)
    if requested.lower() == "auto":
        accelerator_reported = is_accelerated_device(selected) or any(
            is_accelerated_device(device) for device in string_list(runtime.get("available_devices"))
        )
        return not accelerator_reported or is_accelerated_device(selected)
    if requested.lower() == "cpu":
        return selected.lower() == "cpu"
    return device_matches(requested, selected)


def probe_paddle_runtime(requested_device: str, paddle_module: Any = None) -> dict[str, Any]:
    """Resolve a requested device against the installed Paddle runtime."""
    requested = requested_device.strip() or "auto"
    result: dict[str, Any] = {
        "requested_device": requested,
        "device": canonical_device(requested) if requested.lower() != "auto" else "cpu",
        "backend": "cpu" if requested.lower() in {"", "auto", "cpu"} else device_backend(canonical_device(requested)),
        "compiled_backends": [],
        "available_devices": [],
        "device_available": False,
        "error": None,
    }
    try:
        if paddle_module is None:
            import paddle

            paddle_module = paddle
        paddle = paddle_module
        compiled_backends = ["cpu"]
        if bool(paddle.is_compiled_with_cuda()):
            compiled_backends.append("cuda")
        if bool(paddle.is_compiled_with_xpu()):
            compiled_backends.append("xpu")
        custom_types = string_list(paddle.device.get_all_custom_device_type())
        compiled_backends.extend(
            f"custom:{custom_type.lower()}"
            for custom_type in custom_types
            if f"custom:{custom_type.lower()}" not in compiled_backends
        )
        available_devices = string_list(paddle.device.get_available_device())
        result["compiled_backends"] = compiled_backends
        result["available_devices"] = available_devices

        requested_lower = requested.lower()
        if requested_lower == "auto":
            selected = next(
                (
                    device
                    for device in available_devices
                    if device.lower().startswith("gpu:") and "cuda" in compiled_backends
                ),
                None,
            )
            if selected is None:
                selected = next(
                    (
                        device
                        for device in available_devices
                        if device.lower().startswith("xpu:") and "xpu" in compiled_backends
                    ),
                    None,
                )
            if selected is None:
                selected = next(
                    (
                        device
                        for device in available_devices
                        if device_backend(device).startswith("custom:")
                        and device_backend(device) in compiled_backends
                    ),
                    None,
                )
            selected = selected or "cpu"
        else:
            selected = canonical_device(requested)

        backend = device_backend(selected)
        result["device"] = selected
        result["backend"] = backend
        backend_compiled = backend == "cpu" or backend in compiled_backends
        listed = any(device_matches(selected, available) for available in available_devices)
        if backend != "cpu" and backend_compiled:
            try:
                paddle.device.set_device(selected)
                listed = True
            except Exception as error:
                result["error"] = f"Paddle device {selected!r} is not usable: {error}"
        device_available = backend_compiled and (backend == "cpu" or listed)
        result["device_available"] = device_available
        if not device_available and result["error"] is None:
            result["error"] = (
                f"Paddle device {selected!r} is unavailable; "
                f"compiled_backends={','.join(compiled_backends) or 'none'}, "
                f"available_devices={','.join(available_devices) or 'none'}"
            )
    except Exception as error:
        result["error"] = f"Paddle runtime probe failed: {error}"
    return result


def process_rss_bytes() -> int | None:
    """Return a comparable process RSS value on Linux and macOS."""
    try:
        status_path = "/proc/self/status"
        if os.path.isfile(status_path):
            with open(status_path, encoding="utf-8") as status:
                for line in status:
                    if line.startswith("VmRSS:"):
                        value = line.split()[1]
                        return int(value) * 1024
        if resource is None:
            return None
        usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return int(usage if sys.platform == "darwin" else usage * 1024)
    except (OSError, ValueError):
        return None


def bbox_xyxy(value: Any) -> tuple[float, float, float, float] | None:
    if isinstance(value, dict):
        value = value.get("coordinate") or value.get("bbox") or value.get("block_bbox")
    if not isinstance(value, (list, tuple)):
        return None
    values = [float(item) for item in value if isinstance(item, (int, float))]
    if len(values) < 4:
        return None
    if len(values) >= 8:
        x_values = values[0::2]
        y_values = values[1::2]
        return min(x_values), min(y_values), max(x_values), max(y_values)
    left, top, right, bottom = values[:4]
    if right <= left:
        right += left
    if bottom <= top:
        bottom += top
    return left, top, right, bottom


def intersection_over_union(
    left: tuple[float, float, float, float],
    right: tuple[float, float, float, float],
) -> float:
    x0 = max(left[0], right[0])
    y0 = max(left[1], right[1])
    x1 = min(left[2], right[2])
    y1 = min(left[3], right[3])
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    union = left_area + right_area - intersection
    return intersection / union if union > 0.0 else 0.0


def best_layout_match(
    block_box: tuple[float, float, float, float],
    layout_boxes: Any,
    label: Any,
) -> tuple[float, float] | None:
    if not isinstance(layout_boxes, dict):
        return None
    boxes = layout_boxes.get("boxes")
    if not isinstance(boxes, list):
        return None
    best: tuple[float, float] | None = None
    for item in boxes:
        if not isinstance(item, dict):
            continue
        candidate = bbox_xyxy(item.get("coordinate") or item.get("bbox"))
        score = item.get("score")
        if candidate is None or not isinstance(score, (int, float)):
            continue
        overlap = intersection_over_union(block_box, candidate)
        if best is None or overlap > best[0]:
            best = overlap, float(score)
    return best if best is not None and best[0] >= 0.1 else None


def best_ocr_match(
    block_box: tuple[float, float, float, float],
    ocr_result: Any,
) -> float | None:
    if not isinstance(ocr_result, dict):
        return None
    boxes = ocr_result.get("rec_boxes")
    scores = ocr_result.get("rec_scores")
    if not isinstance(boxes, list) or not isinstance(scores, list):
        return None
    best_overlap = 0.0
    best_score: float | None = None
    for box, score in zip(boxes, scores):
        candidate = bbox_xyxy(box)
        if candidate is None or not isinstance(score, (int, float)):
            continue
        overlap = intersection_over_union(block_box, candidate)
        if overlap > best_overlap:
            best_overlap = overlap
            best_score = float(score)
    return best_score if best_overlap >= 0.1 else None


class SidecarState:
    def __init__(self, version: str, language: str, requested_device: str | None = None) -> None:
        self.version = version
        self.language = language
        self.runtime = probe_paddle_runtime(
            requested_device or os.getenv("XUEXUE_PADDLEOCR_DEVICE", "auto"),
        )
        self.requested_device = self.runtime["requested_device"]
        self.device = self.runtime["device"]
        self.backend = self.runtime["backend"]
        self.runtime_error = self.runtime["error"]
        self.enable_mkldnn = env_bool("XUEXUE_PADDLEOCR_ENABLE_MKLDNN", False)
        self.enable_cinn = env_bool("XUEXUE_PADDLEOCR_ENABLE_CINN", False)
        self.enable_new_ir = env_bool("XUEXUE_PADDLEOCR_ENABLE_NEW_IR", False)
        self.cpu_threads = max(
            1,
            int(os.getenv("XUEXUE_PADDLEOCR_CPU_THREADS", str(max(1, (os.cpu_count() or 1) - 1)))),
        )
        self.pipeline: Any = None
        self.vl_pipeline: Any = None
        self.error: str | None = self.runtime_error
        self.vl_error: str | None = None
        self.loading = self.runtime_error is None
        self.vl_loading = False
        self.lock = threading.Lock()
        if self.loading:
            threading.Thread(target=self._load_pipeline, name="pp-structure-loader", daemon=True).start()

    def _load_pipeline(self) -> None:
        try:
            configure_custom_device_support(self.device)
            from paddleocr import PPStructureV3

            pipeline = PPStructureV3(
                lang=self.language,
                device=pipeline_device_name(self.device),
                enable_mkldnn=self.enable_mkldnn,
                enable_cinn=self.enable_cinn,
                cpu_threads=self.cpu_threads,
                engine_config={
                    "paddle_static": {
                        "run_mode": "paddle",
                        "enable_new_ir": self.enable_new_ir,
                        "enable_cinn": self.enable_cinn,
                    }
                },
            )
            with self.lock:
                self.pipeline = pipeline
                self.error = None
        except Exception as error:  # Paddle emits several distinct import/model errors.
            with self.lock:
                self.error = f"PP-StructureV3 is not ready: {error}"
            logging.exception("PP-StructureV3 initialization failed")
        finally:
            with self.lock:
                self.loading = False

    def load_vl_pipeline(self) -> Any:
        if self.runtime_error is not None:
            raise RuntimeError(self.runtime_error)
        with self.lock:
            if self.vl_pipeline is not None:
                return self.vl_pipeline
            if self.vl_loading:
                raise RuntimeError("PaddleOCR-VL is still loading")
            if self.vl_error is not None:
                raise RuntimeError(self.vl_error)
        self._start_vl_loading()
        raise RuntimeError("PaddleOCR-VL is still loading")

    def _load_vl_pipeline(self) -> None:
        try:
            configure_custom_device_support(self.device)
            from paddleocr import PaddleOCRVL

            pipeline = PaddleOCRVL(device=pipeline_device_name(self.device))
            with self.lock:
                self.vl_pipeline = pipeline
                self.vl_error = None
        except Exception as error:
            with self.lock:
                self.vl_error = f"PaddleOCR-VL is not ready: {error}"
            logging.exception("PaddleOCR-VL initialization failed")
        finally:
            with self.lock:
                self.vl_loading = False

    def _start_vl_loading(self) -> None:
        with self.lock:
            if self.vl_pipeline is not None or self.vl_loading or self.vl_error is not None:
                return
            self.vl_loading = True
        threading.Thread(target=self._load_vl_pipeline, name="paddleocr-vl-loader", daemon=True).start()

    def health(self, probe: str | None = None, load: bool = False) -> dict[str, Any]:
        if probe == "paddleocr-vl" and load:
            self._start_vl_loading()
        with self.lock:
            pipeline_ready = self.pipeline is not None
            vl_ready = self.vl_pipeline is not None
            pipeline_error = self.error
            vl_error = self.vl_error
            pipeline_loading = self.loading
            vl_loading = self.vl_loading
        if probe is None:
            requested_ready = pipeline_ready
        elif probe == "pp-structure-v3":
            requested_ready = pipeline_ready
        else:
            requested_ready = vl_ready
        return {
            "version": self.version,
            "ready": requested_ready,
            "runtime": {
                "requested_device": self.requested_device,
                "device": self.device,
                "backend": self.backend,
                "compiled_backends": self.runtime["compiled_backends"],
                "available_devices": self.runtime["available_devices"],
                "device_available": self.runtime["device_available"],
                "enable_mkldnn": self.enable_mkldnn,
                "enable_new_ir": self.enable_new_ir,
                "cpu_threads": self.cpu_threads,
            },
            "engines": {
                "pp-structure-v3": pipeline_ready,
                "paddleocr-vl": vl_ready,
            },
            "loading": pipeline_loading,
            "vl_loading": vl_loading,
            "rss_bytes": process_rss_bytes(),
            "error": pipeline_error or ("PP-StructureV3 is still loading" if pipeline_loading else None),
            "vl_error": vl_error or ("PaddleOCR-VL is still loading" if vl_loading else None),
        }

    def analyze(self, payload: dict[str, Any], vl: bool = False) -> dict[str, Any]:
        encoded = payload.get("image_base64")
        if not isinstance(encoded, str) or not encoded:
            raise ValueError("image_base64 is required")
        try:
            image_bytes = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as error:
            raise ValueError("image_base64 is invalid") from error

        try:
            from PIL import Image
            import numpy as np

            image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
            array = np.asarray(image)
        except Exception as error:
            raise RuntimeError(f"cannot decode page image: {error}") from error

        if vl:
            pipeline = self.load_vl_pipeline()
        else:
            with self.lock:
                pipeline = self.pipeline
                error = self.error
            if pipeline is None:
                raise RuntimeError(error or "PP-StructureV3 is not ready")
        with self.lock:
            result = first_result(pipeline.predict(input=array))
        response = normalize_result(result_json(result))
        response["page_index"] = payload.get("page_index")
        response["width"] = payload.get("width", image.width)
        response["height"] = payload.get("height", image.height)
        return response


class SidecarHandler(BaseHTTPRequestHandler):
    server: "SidecarServer"

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler.
        path, _, query = self.path.partition("?")
        if path != "/health":
            self.send_json(404, {"error": "not found"})
            return
        parameters = parse_qs(query, keep_blank_values=False)
        probe = parameters.get("probe", [None])[0]
        load = parameters.get("load", ["false"])[0].lower() in {"1", "true", "yes", "on"}
        response = self.server.state.health(probe, load=load)
        if probe is not None and probe not in {"pp-structure-v3", "paddleocr-vl"}:
            response["ready"] = False
            response["error"] = f"unknown engine probe: {probe}"
        self.send_json(200, response)

    def do_POST(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler.
        if self.path not in ("/analyze", "/analyze-vl"):
            self.send_json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
            if length < 0 or length > self.server.max_request_bytes:
                raise ValueError("request body is too large or has no Content-Length")
            body = self.rfile.read(length)
            payload = json.loads(body)
            if not isinstance(payload, dict):
                raise ValueError("request body must be a JSON object")
            result = self.server.state.analyze(payload, vl=self.path == "/analyze-vl")
            self.send_json(200, result)
        except Exception as error:
            logging.exception("sidecar request failed")
            self.send_json(500, {"error": str(error)})

    def send_json(self, status: int, value: dict[str, Any]) -> None:
        body = json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: Any) -> None:
        logging.info("%s - %s", self.address_string(), format_string % args)


class SidecarServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], state: SidecarState, max_request_bytes: int) -> None:
        super().__init__(address, SidecarHandler)
        self.state = state
        self.max_request_bytes = max_request_bytes
        self.daemon_threads = True


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="XueXue PP-StructureV3 loopback sidecar")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=int(os.getenv("XUEXUE_PADDLEOCR_PORT", "8765")))
    parser.add_argument("--version", default=os.getenv("XUEXUE_PADDLEOCR_VERSION", DEFAULT_VERSION))
    parser.add_argument("--lang", default=os.getenv("XUEXUE_PADDLEOCR_LANG", "ch"))
    parser.add_argument("--device", default=os.getenv("XUEXUE_PADDLEOCR_DEVICE", "auto"))
    parser.add_argument(
        "--max-request-bytes",
        type=int,
        default=int(os.getenv("XUEXUE_PADDLEOCR_MAX_REQUEST_BYTES", str(DEFAULT_MAX_REQUEST_BYTES))),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        address = ipaddress.ip_address(args.host)
        if address != ipaddress.ip_address("127.0.0.1"):
            raise ValueError("sidecar must bind to 127.0.0.1")
        if not 1 <= args.port <= 65535:
            raise ValueError("port must be between 1 and 65535")
        if args.max_request_bytes <= 0:
            raise ValueError("max request size must be positive")
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    state = SidecarState(args.version, args.lang, requested_device=args.device)
    server = SidecarServer(("127.0.0.1", args.port), state, args.max_request_bytes)
    logging.info("XueXue sidecar listening on 127.0.0.1:%s", args.port)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
