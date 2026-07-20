# XueXue PaddleOCR sidecar

This directory contains the executable loopback entry point used by the PDF
analysis engine. It binds only to `127.0.0.1` and accepts one page image per
request. It does not accept a PDF path or upload a document to a remote
service.

## Local run

Use a dedicated virtual environment. The versions below are locked to the
tested package releases; Paddle's platform-specific wheel must be available
for the target CPU or GPU backend.

```bash
cd tools/paddleocr-sidecar
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
XUEXUE_PADDLEOCR_VERSION=3.x-sidecar \
  XUEXUE_PADDLEOCR_EXECUTABLE="$PWD/bin/paddleocr-sidecar" \
  XUEXUE_PADDLEOCR_ENDPOINT=http://127.0.0.1:8765 \
  XUEXUE_PADDLEOCR_INSTALL_DIR="$HOME/.cache/xuexue/paddleocr" \
  .venv/bin/python paddleocr_sidecar.py
```

The launcher uses `python3` by default. Set `XUEXUE_PADDLEOCR_PYTHON` when a
different interpreter is required. The sidecar defaults to
`XUEXUE_PADDLEOCR_DEVICE=auto`: it selects a real Paddle CUDA/XPU/custom device
when the installed runtime reports one, and otherwise uses CPU. Set
`XUEXUE_PADDLEOCR_DEVICE=gpu:0` (or `cuda:0`) to require NVIDIA CUDA; an
unavailable explicit device makes health fail instead of silently falling back
to CPU. `GET /health` reports the requested device, selected device, backend,
compiled backends, available devices, and `device_available`.

The Kotlin client checks the reported version, readiness, and runtime device
before starting analysis. A machine GPU detected by the host is only a hardware
hint; it is not treated as Paddle acceleration until the sidecar reports it.

## Release artifact

Build one artifact per operating system, architecture, and Paddle backend.
The package contains the Python dependencies in `lib/`, so it must be built
on a machine compatible with the target wheel.

```bash
cd tools/paddleocr-sidecar
./package.sh 3.x-sidecar "$PWD/dist/paddleocr-sidecar-linux-x86_64.zip" --backend cpu
./package.sh 3.x-sidecar "$PWD/dist/paddleocr-sidecar-linux-cuda12.zip" --backend cuda126
```

The same builder also runs on Windows and macOS when invoked with their
native Python interpreter:

```text
python package.py 3.x-sidecar dist/paddleocr-sidecar-windows-x86_64.zip --backend cpu
python package.py 3.x-sidecar dist/paddleocr-sidecar-linux-cuda12.zip --backend cuda126
python package.py 3.x-sidecar dist/paddleocr-sidecar-macos-arm64.zip --backend cpu
```

It installs dependencies for the current target interpreter, writes the
version and backend into the artifact, and prints the SHA-256 checksum. The
available NVIDIA variants are `cuda118`, `cuda126`, and `cuda129`; they install
`paddlepaddle-gpu==3.3.1` from Paddle's matching wheel index. Build each ZIP on
the operating system, architecture, Python version, CUDA runtime, and Paddle
backend that will run it. Other Paddle custom devices require a matching
vendor-provided wheel and can be supplied with `--requirements`.

Intel Arc/SYCL being visible to the operating system does not make the standard
PaddleOCR PP-StructureV3 package GPU-capable. The current sidecar therefore
reports CPU fallback on such a host unless a Paddle custom-device runtime and
matching PaddleOCR support are installed; no Intel GPU acceleration is claimed
without that runtime evidence.

Configure the desktop application with:

- `XUEXUE_PADDLEOCR_ARTIFACT_URL`: HTTPS URL for the platform artifact.
- `XUEXUE_PADDLEOCR_ARTIFACT_SHA256`: lowercase SHA-256 from the build output.
- `XUEXUE_PADDLEOCR_ARTIFACT_FORMAT=ZIP`.
- `XUEXUE_PADDLEOCR_ARTIFACT_ENTRY=bin/paddleocr-sidecar` on Unix, or the
  corresponding Windows launcher for a Windows artifact.
- `XUEXUE_PADDLEOCR_VERSION`: the same version string passed to `package.sh`.

The desktop manager downloads into its cache, resumes a partial download,
verifies the checksum, rejects unsafe ZIP paths and oversized extraction, then
starts the extracted entry point. Do not distribute one artifact across
different Paddle CPU/GPU or operating-system combinations.

## Protocol

`GET /health` returns the sidecar version, readiness, runtime device selection,
and loaded engine flags.
`POST /analyze` and `POST /analyze-vl` accept the Kotlin request fields
`page_index`, `width`, `height`, `dpi`, `language`, and `image_base64`. The
response exposes `parsing_res_list`; every block has a label, text, and bbox
when the Paddle result provides one.

The normal repository test suite does not install PaddleOCR or Pillow, so its
tests validate the protocol, adapter, and lifecycle contracts rather than
model recognition quality. A real scan-book evaluation uses
`tools/run_pdf_ocr_evaluation.sh` with the locked runtime and model weights;
the current external baseline is recorded in `docs/PDF_OCR_EVALUATION_REPORT.md`.
