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

health_ready() {
    local payload
    payload="$(curl --silent --fail --max-time 2 "$health_endpoint")" || return 1
    printf '%s' "$payload" |
        "$python_bin" -c 'import json, sys; raise SystemExit(0 if json.load(sys.stdin).get("ready") is True else 1)' \
            2>/dev/null
}

if ! health_ready; then
    if ! curl --silent --fail --max-time 2 "$health_endpoint" >/dev/null; then
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

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}" \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :desktopTest \
    --tests dev.xuexue.platform.PdfOcrExternalEvaluationTest \
    -Dxuexue.pdf.eval.pdf="$XUEXUE_PDF_EVAL_PDF" \
    -Dxuexue.pdf.eval.manifest="$XUEXUE_PDF_EVAL_MANIFEST" \
    -Dxuexue.pdf.eval.report="$XUEXUE_PDF_EVAL_REPORT" \
    -Dxuexue.pdf.eval.endpoint="$endpoint" \
    -Dxuexue.pdf.eval.sidecarPid="${sidecar_pid:-0}" \
    -Dxuexue.pdf.eval.dpi="${XUEXUE_PDF_EVAL_DPI:-200}"
