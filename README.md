# XueXue v0.1 alpha

XueXue is a local-first Compose Multiplatform desktop workbench for reading,
notes, search, backlinks, and model-assisted work.

## Run

Requires JDK 26. The first PDF build also needs a C/C++ toolchain, GNU Make,
and `curl`. Gradle downloads the pinned MuPDF source archive, verifies its
checksum, and builds the project-owned JNI bridge with bundled MuPDF
dependencies. No MuPDF Java wrapper or system FreeType/OpenSSL/zlib development
package is required.

```bash
./gradlew run
```

The first screen lets you create or open a directory. Files remain the source
of truth. Layout state is stored in `.xuexue/workbench.properties`; Agent
sessions and PDF annotations are also stored under `.xuexue` and should be
backed up when they matter.

## Windows

Windows builds use MSYS2 for the POSIX shell and the MinGW GNU toolchain used
to build the MuPDF JNI library. You do not need Windows Developer Mode: the
build skips source-archive symlinks that cannot be created by a standard
MSYS2 installation.

Install the prerequisites from PowerShell:

```powershell
winget install --id EclipseAdoptium.Temurin.26.JDK --exact --source winget
winget install --id MSYS2.MSYS2 --exact --source winget
```

Open the **MSYS2 MINGW64** terminal and update its packages. If `pacman -Syu`
asks you to close the terminal, reopen **MSYS2 MINGW64** before running the
second command:

```bash
pacman -Syu
pacman -Su
pacman -S --needed base-devel mingw-w64-x86_64-toolchain curl
```

Then open PowerShell in the repository. The following keeps Gradle, MuPDF,
temporary files, and build caches on D:; adjust the root only if your data
drive uses another path:

```powershell
New-Item -ItemType Directory -Force -Path `
  'D:\Dev\gradle-cache', 'D:\Dev\cache', 'D:\Dev\tmp' | Out-Null
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-26.0.2.10-hotspot'
$env:GRADLE_USER_HOME = 'D:\Dev\gradle-cache'
$env:XDG_CACHE_HOME = '/d/Dev/cache'
$env:TEMP = 'D:\Dev\tmp'
$env:TMP = $env:TEMP
$env:TMPDIR = '/d/Dev/tmp'
.\gradlew.bat run
```

Gradle automatically detects MSYS2 at `C:\msys64` or `C:\msys2`. Set
`MSYS2_HOME` in PowerShell when it is installed elsewhere. `pacman` is only
used inside the MSYS2 terminal; Gradle itself is launched from PowerShell.

## Build a distributable

Compose Multiplatform packaging is platform-native: each target must be built
on its own operating system (Windows cannot produce macOS or Linux packages).
The target formats are declared in `build.gradle.kts` under
`compose.desktop.application.nativeDistributions` (Windows MSI, Debian deb,
macOS dmg).

Windows — verified in this repository (use `gradlew.bat` from PowerShell):

```powershell
# Unpacked portable app, no installer needed:
#   build\compose\binaries\main\app\XueXue\XueXue.exe
.\gradlew.bat createDistributable

# MSI installer (standard Compose target; not exercised in this repository):
.\gradlew.bat packageMsi
```

Linux and macOS — same flow, not verified in this repository; run the
corresponding command on the target machine:

```bash
./gradlew createDistributable   # build/compose/binaries/main/app/XueXue/
./gradlew packageDeb            # Debian/Ubuntu package (Linux)
./gradlew packageDmg            # macOS disk image
```

The bundled `xuexue_mupdf` JNI library is rebuilt automatically whenever
`tools/native/mupdf_bridge.c` or `tools/bootstrap-mupdf-native.sh` changes; a
prebuilt copy ships under `.mupdf/<version>/<platform>/`. The launcher bundles
a runtime, so the output runs without a system JDK.

## Alpha scope

- Desktop workbench with activity rail, resizable tool panel, document tabs,
  contextual toolbar, agent panel, and status bar.
- Real directory tree, preview/pinned tabs, create Markdown file, refresh, and
  atomic autosave.
- Markdown source/edit/read modes, formatting commands, wiki links, tags,
  headings, backlinks, outline, and workspace text search.
- MuPDF-backed viewport-tiled PDF viewing with continuous/single/double-page
  modes, smooth anchored zoom, rotation, night reading, native text selection,
  links, search, annotations, persisted reading position, and progressive
  HTTP Range loading.
- Image viewing.
- Streaming OpenAI-compatible agent conversation with current-document context,
  cancellation, and workspace-local session persistence.
- Restores the last workspace, open tabs, active document, tool, and panel sizes.

See [docs/V0.1_ALPHA.md](docs/V0.1_ALPHA.md) for acceptance criteria and known
architecture gaps.

## Quality checks

```bash
./gradlew ktlintCheck
./gradlew detektMainDesktop
./gradlew detektTestDesktop
./gradlew desktopTest
./gradlew koverXmlReport
```

CI runs the same checks. Dependency versions are locked in `gradle.lockfile`.
See [CONTRIBUTING.md](CONTRIBUTING.md) for commit and review guidelines.
