# Native MuPDF Bridge Build

The PDF engine is a JNI bridge (`tools/native/mupdf_bridge.c`) compiled against
bundled [MuPDF](https://mupdf.com) 1.27.2. The resulting `xuexue_mupdf`
library is **not stored in git** (`.mupdf/` is ignored): every checkout must
build it before PDF features work.

## Automatic rebuild

Gradle runs `bootstrapMuPdf` as a dependency of every compile and packaging
task. The bootstrap script (`tools/bootstrap-mupdf-native.sh`):

1. Downloads the pinned MuPDF source archive (checksum-verified) into
   `${XDG_CACHE_HOME:-~/.cache}/xuexue/mupdf/1.27.2/` (or `.cache/` on Windows).
2. Compiles MuPDF's static libraries (`libmupdf.a`, `libmupdf-third.a`).
3. Compiles `mupdf_bridge.c` with the host JDK's JNI headers and links the
   bridge into `.mupdf/1.27.2/<os>-<arch>/xuexue_mupdf.dll|.so|.dylib`.

The script exits early when the output library is newer than the bridge
source and the script itself, so repeated builds are no-ops until one of
them changes.

## Windows prerequisites

Windows builds need MSYS2 with the MinGW toolchain. See the *Windows* section
of [README.md](../README.md) for the winget/pacman install commands. The
bridge script self-bootstraps its `PATH` from `C:\msys64`, `C:\msys2` or
`/msys64`, so launching Gradle from PowerShell works without extra setup;
set `MSYS2_HOME` when MSYS2 lives elsewhere.

## Manual rebuild

```bash
# From an MSYS2 MINGW64 shell on Windows, or any POSIX shell on Linux/macOS:
bash tools/bootstrap-mupdf-native.sh

# Print the target platform/library path without building:
bash tools/bootstrap-mupdf-native.sh --print-target
```

## Zero-copy raster pipeline

The bridge exposes three JNI entry points in addition to the byte-array
`renderRgba` path (see `MuPdfNative.kt` for the Kotlin side):

| Function | Purpose |
| --- | --- |
| `renderRgbaNative` | Renders a display list into a `malloc`'d RGBA buffer and returns its address. A crop rectangle (in rendered pixels) is copied out of the padded render region, so tiles keep their 2px edge padding without a second copy. |
| `freeRasterBuffer` | Releases a buffer returned by `renderRgbaNative`. |
| `nativeZeroCopyProbe` | Returns true; Kotlin probes this symbol at startup to detect a library that ships the zero-copy path. |

On the Kotlin side the returned address is wrapped with
`Data.makeWithoutCopy` + `Image.makeRaster(Data)` (Skia borrows the memory
instead of copying), and freed only after the borrowed bitmap is closed.
Only `VIEWPORT`+`CONTENT` renders use the path; analysis/thumbnail rasters
keep byte arrays, and libraries without the probe symbol automatically fall
back to the byte-array path.

## Verifying a build

```bash
# Windows: the three symbols must be exported by the DLL:
objdump -p .mupdf/1.27.2/windows-x86_64/xuexue_mupdf.dll |
    grep -E 'renderRgbaNative|freeRasterBuffer|nativeZeroCopyProbe'

# Pixel-for-pixel consistency between the zero-copy and byte-array paths:
./gradlew desktopTest --tests 'dev.xuexue.platform.PdfZeroCopyRenderTest'
# Memory probes (need XUEXUE_*_PROBE_PATH env vars):
./gradlew desktopTest --tests 'dev.xuexue.platform.PdfNativeMemoryProbeTest'
./gradlew desktopTest --tests 'dev.xuexue.platform.PdfTextureNavigationMemoryProbeTest'
```

## Notes

- The script requires GNU Make, `curl`, `tar` and a C11 compiler (`cc`) on
  `PATH` (MinGW gcc on Windows).
- MSYS2's default `PATH` handling appends Windows-style entries that bash
  cannot resolve; the script prepends a valid toolchain when it finds one.
  Do not switch the Gradle invocation to `bash -lc`: the login shell strips
  backslashes from the script path.
- Linux and macOS targets use the same script (the OS/arch is detected with
  `uname`); they have not been exercised in this repository.
