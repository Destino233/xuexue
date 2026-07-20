#!/usr/bin/env bash
set -euo pipefail

report="${XUEXUE_PDF_BENCHMARK_REPORT:-tmp/pdfs/long-document-benchmark.json}"
pages="${XUEXUE_PDF_BENCHMARK_PAGES:-1001}"

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}" \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :desktopTest \
    --tests dev.xuexue.platform.PdfLongDocumentBenchmarkTest \
    -Dxuexue.pdf.benchmark.pages="$pages" \
    -Dxuexue.pdf.benchmark.report="$report"
