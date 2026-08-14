#!/usr/bin/env bash
set -euo pipefail

MUPDF_VERSION="1.27.2"
SOURCE_SHA256="553867b135303dc4c25ab67c5f234d8e900a0e36e66e8484d99adc05fe1e8737"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
BRIDGE_SOURCE="${PROJECT_ROOT}/tools/native/mupdf_bridge.c"

UNAME_SYSTEM="$(uname -s)"
UNAME_MACHINE="$(uname -m)"
case "${UNAME_SYSTEM}" in
    Linux*)
        OS_ID="linux"
        JNI_INCLUDE="linux"
        NATIVE_LIBRARY_PREFIX="lib"
        NATIVE_LIBRARY_SUFFIX=".so"
        ;;
    Darwin*)
        OS_ID="macos"
        JNI_INCLUDE="darwin"
        NATIVE_LIBRARY_PREFIX="lib"
        NATIVE_LIBRARY_SUFFIX=".dylib"
        ;;
    MINGW* | MSYS* | CYGWIN*)
        OS_ID="windows"
        JNI_INCLUDE="win32"
        NATIVE_LIBRARY_PREFIX=""
        NATIVE_LIBRARY_SUFFIX=".dll"
        ;;
    *)
        echo "Unsupported MuPDF host operating system: ${UNAME_SYSTEM}" >&2
        exit 1
        ;;
esac

case "${UNAME_MACHINE}" in
    x86_64 | amd64)
        ARCH_ID="x86_64"
        ;;
    aarch64 | arm64)
        ARCH_ID="arm64"
        ;;
    *)
        echo "Unsupported MuPDF host architecture: ${UNAME_MACHINE}" >&2
        exit 1
        ;;
esac

PLATFORM="${OS_ID}-${ARCH_ID}"
NATIVE_LIBRARY="${NATIVE_LIBRARY_PREFIX}xuexue_mupdf${NATIVE_LIBRARY_SUFFIX}"
if [[ "${1:-}" == "--print-target" ]]; then
    printf '%s/%s\n' "${PLATFORM}" "${NATIVE_LIBRARY}"
    exit 0
fi

OUTPUT_DIR="${PROJECT_ROOT}/.mupdf/${MUPDF_VERSION}/${PLATFORM}"
OUTPUT_LIBRARY="${OUTPUT_DIR}/${NATIVE_LIBRARY}"
if [[ -f "${OUTPUT_LIBRARY}" && "${OUTPUT_LIBRARY}" -nt "${BRIDGE_SOURCE}" && \
    "${OUTPUT_LIBRARY}" -nt "${BASH_SOURCE[0]}" ]]; then
    exit 0
fi

if [[ -n "${XDG_CACHE_HOME:-}" ]]; then
    CACHE_ROOT="${XDG_CACHE_HOME}/xuexue/mupdf/${MUPDF_VERSION}"
elif [[ "${OS_ID}" == "windows" ]]; then
    CACHE_ROOT="${PROJECT_ROOT}/.cache/xuexue/mupdf/${MUPDF_VERSION}"
else
    CACHE_ROOT="${HOME}/.cache/xuexue/mupdf/${MUPDF_VERSION}"
fi
ARCHIVE="${CACHE_ROOT}/mupdf-${MUPDF_VERSION}-source.tar.gz"
SOURCE_DIR="${CACHE_ROOT}/source"
MUPDF_BUILD_DIR="build/xuexue/release-bundled"
mkdir -p "${CACHE_ROOT}" "${OUTPUT_DIR}"

if [[ ! -f "${ARCHIVE}" ]]; then
    curl --fail --location --output "${ARCHIVE}.part" \
        "https://mupdf.com/downloads/archive/mupdf-${MUPDF_VERSION}-source.tar.gz"
    mv "${ARCHIVE}.part" "${ARCHIVE}"
fi

if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "${SOURCE_SHA256}" "${ARCHIVE}" | sha256sum --check --status
elif command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "${SOURCE_SHA256}" "${ARCHIVE}" | shasum --algorithm 256 --check --status
else
    echo "A SHA-256 verification tool is required." >&2
    exit 1
fi

extract_mupdf_source() {
    local -a tar_args=(
        -xzf "${ARCHIVE}"
        --strip-components=1
        -C "${SOURCE_DIR}"
    )

    if [[ "${OS_ID}" == "windows" ]]; then
        # MSYS2 without native symlink support cannot extract dangling links.
        local symlink_path
        while IFS= read -r symlink_path; do
            [[ -n "${symlink_path}" ]] || continue
            tar_args+=(--exclude="${symlink_path}")
        done < <(
            tar -tvzf "${ARCHIVE}" |
                awk '$1 ~ /^l/ {
                    sub(/^[^ ]+ [^ ]+ [^ ]+ [^ ]+ [^ ]+ /, "")
                    sub(/ -> .*/, "")
                    print
                }'
        )
    fi

    tar "${tar_args[@]}"
}

if [[ ! -f "${SOURCE_DIR}/include/mupdf/fitz.h" ]]; then
    rm -rf "${SOURCE_DIR}"
    mkdir -p "${SOURCE_DIR}"
    extract_mupdf_source
fi

MUPDF_XCFLAGS="-fPIC"
BRIDGE_CFLAGS=("-fPIC")
if [[ "${OS_ID}" == "windows" ]]; then
    # MuPDF 1.27.2 uses SSE4.1 intrinsics in its x86 deskew path.
    MUPDF_XCFLAGS="-msse4.1"
    BRIDGE_CFLAGS=()
fi
"${MAKE:-make}" -C "${SOURCE_DIR}" \
    OUT="${MUPDF_BUILD_DIR}" \
    XCFLAGS="${MUPDF_XCFLAGS}" \
    build=release \
    HAVE_LIBCRYPTO=no \
    USE_SYSTEM_FREETYPE=no \
    USE_SYSTEM_ZLIB=no \
    libs

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-26-openjdk}"
JAVA_INCLUDE_HOME="${JAVA_HOME}"
if command -v cygpath >/dev/null 2>&1; then
    JAVA_INCLUDE_HOME="$(cygpath -u "${JAVA_HOME}")"
fi
CC="${CC:-cc}"

LINK_MODE=(-shared)
PLATFORM_LINK_FLAGS=()
case "${OS_ID}" in
    linux)
        PLATFORM_LINK_FLAGS=(-Wl,-z,noexecstack -pthread)
        ;;
    macos)
        LINK_MODE=(-dynamiclib)
        PLATFORM_LINK_FLAGS=(-Wl,-install_name,"@rpath/${NATIVE_LIBRARY}" -pthread)
        ;;
    windows)
        PLATFORM_LINK_FLAGS=(
            -Wl,--no-insert-timestamp
            -static-libgcc
            -Wl,-Bstatic
            -lwinpthread
            -Wl,-Bdynamic
            -lws2_32
            -lcrypt32
            -lbcrypt
        )
        ;;
esac

"${CC}" \
    "${LINK_MODE[@]}" \
    -O2 \
    -DNDEBUG \
    -Wall \
    -Wextra \
    -Wpedantic \
    "${BRIDGE_CFLAGS[@]}" \
    -I"${SOURCE_DIR}/include" \
    -I"${JAVA_INCLUDE_HOME}/include" \
    -I"${JAVA_INCLUDE_HOME}/include/${JNI_INCLUDE}" \
    "${BRIDGE_SOURCE}" \
    "${SOURCE_DIR}/${MUPDF_BUILD_DIR}/libmupdf.a" \
    "${SOURCE_DIR}/${MUPDF_BUILD_DIR}/libmupdf-third.a" \
    "${PLATFORM_LINK_FLAGS[@]}" \
    -lm \
    -o "${OUTPUT_LIBRARY}.part"

mv "${OUTPUT_LIBRARY}.part" "${OUTPUT_LIBRARY}"
chmod 0755 "${OUTPUT_LIBRARY}"
install -m 0644 "${SOURCE_DIR}/COPYING" "${OUTPUT_DIR}/COPYING"
find "${OUTPUT_DIR}" -maxdepth 1 -type f \( \
    -name 'libxuexue_mupdf.so' -o \
    -name 'libxuexue_mupdf.dylib' -o \
    -name 'xuexue_mupdf.dll' \
    \) ! -name "${NATIVE_LIBRARY}" -delete
