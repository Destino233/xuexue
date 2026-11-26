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

The first screen lets you create or open a directory. Layout state is stored
in `.xuexue/workbench.properties`. See the [storage contract](docs/V0.1_ALPHA.md)
for Agent history and PDF annotations.

### DeepSeek Harness

Install Node.js 24.9 or later with npm, then prepare the Agent runtime.

```bash
node tools/setup-dsh-runtime.mjs
```

Launch XueXue with `DEEPSEEK_API_KEY` set for the default DeepSeek route. DSH
reads its settings and credentials from `~/.xuexue/dsh`, or from
`XUEXUE_DSH_HOME` when set. Agent conversation requires an open workspace.

To include the platform's Node.js and DSH in a Compose distribution, run the
setup script with a staging directory and pass that directory to Gradle as
`"-Pxuexue.dshRuntimeDir=/absolute/path/to/staging"`. Use a staging directory
prepared on the OS and architecture of the package. For an existing DSH
installation or compiled source checkout, set `XUEXUE_DSH_ENTRY` to the
absolute path of its `lib/bin.js` and `XUEXUE_DSH_NODE` to its Node executable.

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

## GraalVM native-image build (experimental)

An ahead-of-time compiled executable (no JVM at runtime, RSS ~140 MB vs
~600-700 MB JVM baseline, window + PDF reading verified on Windows x64) is
built from the same sources with GraalVM CE 25.2.4. This is experimental
wiring; it does not affect normal JVM development or packaging:

- The root build exposes two inert switches: `-Pxuexue.nativeJvmTarget=24`
  (bytecode for the JDK-25-based GraalVM) and `-Dxuexue.nativeAgent=<dll>`
  (tracing-agent capture for the test JVMs).
- Everything else — build/rebuild/launch scripts, tracing-agent configs, and
  the fix log — lives in `experiments/graalvm-native-image/main-app/`.
  After changing source code, run `build-native.ps1` there (ordinary
  changes) or `rebuild-native.ps1` (changes touching reflection/JNI/
  resources), then launch with `run-native.cmd`.

See the experiment README for the full workflow and known limitations:
`experiments/graalvm-native-image/main-app/README.md`.

The bundled `xuexue_mupdf` JNI library is rebuilt automatically whenever
`tools/native/mupdf_bridge.c` or `tools/bootstrap-mupdf-native.sh` changes;
it is **not stored in git** (`.mupdf/` is ignored), so fresh checkouts build it
as part of the first Gradle invocation. See
[docs/NATIVE_BUILD.md](docs/NATIVE_BUILD.md) for the build prerequisites, the
zero-copy raster API, and verification steps. The launcher bundles a runtime,
so the output runs without a system JDK.

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
- DeepSeek Harness conversation with current-document context, tool approvals,
  cancellation, and persistent sessions.
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
