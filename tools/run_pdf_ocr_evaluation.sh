#!/usr/bin/env bash
set -euo pipefail

: "${XUEXUE_PDF_EVAL_PDF:?Set XUEXUE_PDF_EVAL_PDF to an external PDF}"
: "${XUEXUE_PDF_EVAL_MANIFEST:?Set XUEXUE_PDF_EVAL_MANIFEST to its JSON manifest}"
: "${XUEXUE_PDF_EVAL_REPORT:?Set XUEXUE_PDF_EVAL_REPORT to an output JSON path}"

endpoint="${XUEXUE_PDF_EVAL_ENDPOINT:-http://127.0.0.1:8765/analyze}"
health_endpoint="${endpoint%/analyze}/health"
sidecar_pid="${XUEXUE_PDF_EVAL_SIDECAR_PID:-}"
python_bin="${XUEXUE_PADDLEOCR_PYTHON:-python3}"
paddle_device="${XUEXUE_PADDLEOCR_DEVICE:-auto}"
started_sidecar=0

cleanup() {
    if [[ "$started_sidecar" == "1" && -n "$sidecar_pid" ]]; then
        kill "$sidecar_pid" 2>/dev/null || true
        wait "$sidecar_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

check_health() {
    local require_ready="$1"
    local payload
    payload="$(curl --silent --fail --max-time 2 "$health_endpoint")" || return 1
    printf '%s' "$payload" |
        "$python_bin" -c '
import importlib.util
import json
import pathlib
import sys

module_path = pathlib.Path("tools/paddleocr-sidecar/paddleocr_sidecar.py")
spec = importlib.util.spec_from_file_location("xuexue_paddleocr_sidecar", module_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)
health = json.load(sys.stdin)
if not module.runtime_matches_requested_device(health.get("runtime"), sys.argv[1]):
    raise SystemExit(2)
if sys.argv[2] == "1" and health.get("ready") is not True:
    raise SystemExit(1)
' "$paddle_device" "$require_ready" 2>/dev/null
}

health_ready() { check_health 1; }
runtime_compatible() { check_health 0; }

if ! health_ready; then
    if curl --silent --fail --max-time 2 "$health_endpoint" >/dev/null; then
        runtime_compatible || {
            echo "Existing PaddleOCR sidecar does not satisfy requested device: $paddle_device" >&2
            exit 1
        }
    else
        log_path="${XUEXUE_PDF_EVAL_SIDECAR_LOG:-${XUEXUE_PDF_EVAL_REPORT%.*}.sidecar.log}"
        mkdir -p "$(dirname "$log_path")"
        "$python_bin" tools/paddleocr-sidecar/paddleocr_sidecar.py \
            --host 127.0.0.1 \
            --port "${XUEXUE_PADDLEOCR_PORT:-8765}" \
            --version "${XUEXUE_PADDLEOCR_VERSION:-3.x-sidecar}" \
            --device "$paddle_device" \
            >"$log_path" 2>&1 &
        sidecar_pid=$!
        started_sidecar=1
    fi
    for _ in $(seq 1 180); do
        if health_ready; then
            break
        fi
        sleep 1
    done
    health_ready || {
        echo "PaddleOCR sidecar did not become ready: $health_endpoint" >&2
        exit 1
    }
fi

runtime_fingerprint="$(
    curl --silent --fail --max-time 2 "$health_endpoint" |
        "$python_bin" -c '
import json
import sys

runtime = json.load(sys.stdin).get("runtime") or {}
print(
    "requested=" + str(runtime.get("requested_device") or "") +
    ";device=" + str(runtime.get("device") or "") +
    ";backend=" + str(runtime.get("backend") or "")
)
'
)"

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}" \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :desktopTest \
    --tests dev.xuexue.platform.PdfOcrExternalEvaluationTest \
    -Dxuexue.pdf.eval.pdf="$XUEXUE_PDF_EVAL_PDF" \
    -Dxuexue.pdf.eval.manifest="$XUEXUE_PDF_EVAL_MANIFEST" \
    -Dxuexue.pdf.eval.report="$XUEXUE_PDF_EVAL_REPORT" \
    -Dxuexue.pdf.eval.endpoint="$endpoint" \
    -Dxuexue.pdf.eval.sidecarPid="${sidecar_pid:-0}" \
    -Dxuexue.pdf.eval.runtime="$runtime_fingerprint" \
    -Dxuexue.pdf.eval.dpi="${XUEXUE_PDF_EVAL_DPI:-200}"
