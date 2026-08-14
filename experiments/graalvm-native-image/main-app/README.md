# Main app — GraalVM Native Image build (experiment)

AOT-compiles the **real XueXue app** with GraalVM Native Image: MuPDF JNI
bridge, kotlinx.serialization, the AI client stack, workspace/file I/O —
the full desktopMain + pi-* modules.

## Status — WORKING ✅

- [x] Prerequisite: Compose Multiplatform 1.8.2 -> 1.9.0 (commit 6179c27;
      1.8.2 crashes in recomposition under Substrate)
- [x] jvmTarget 24 switch (`-Pxuexue.nativeJvmTarget=24`, inert by default)
- [x] native-image build of the real app (1-3 min, ~150 MB exe)
- [x] window shows (RSS **~140 MB** vs ~600-700 MB JVM)
- [x] workspace chooser (JFileChooser) opens and renders
- [x] PDF opens and renders in the native build (MuPDF JNI path)
- [x] graceful exit, stderr clean

## Daily workflow — after you change source code

### Case 1: ordinary code changes (UI, logic, features)

```powershell
powershell -ExecutionPolicy Bypass -File build-native.ps1
```

Recompiles the fat jar (jvmTarget 24), rebuilds the image, re-stages the
runtime. 2-3 minutes. **No config re-capture needed** unless you touched
reflection / JNI / resources / dependencies.

### Case 2: you touched the dynamic boundary

New reflection calls, new JNI usage, new resources, new serialization
polymorphism, or dependency upgrades -> re-capture the tracing-agent config
and rebuild in one shot:

```powershell
powershell -ExecutionPolicy Bypass -File rebuild-native.ps1
```

This runs desktopTest under the native-image tracing agent (JDK 25), then
rebuilds. 5-8 minutes.

### Launch

```powershell
run-native.cmd          # double-clickable launcher
```

The launcher auto-detects the Windows display scale (registry AppliedDPI)
and passes `-Dsun.java2d.uiScale=...` so Swing dialogs are not blurry on
HiDPI displays.

## Toolchain

GraalVM CE 25.2.4 (JDK 25.0.4) at
`D:\Dev\graalvm\graalvm-community-25.2.4+7.1` (no JDK-26 GraalVM
community release exists as of 2026-08-14).

## Why jvmTarget 24?

The main project builds with `jvmToolchain(26)` (class file version 70).
GraalVM 25 (JDK 25) rejects version-70 classes, so the native build emits
Java-24 bytecode (major 68). The switch is **inert unless passed**:

```bash
gradlew desktopJar -Pxuexue.nativeJvmTarget=24
```

An init-script attempt was abandoned: init-script classes live in a
different classloader than the build's Kotlin plugin, so typed extension
lookups match nothing and the setting silently no-ops. The property switch
in `build.gradle.kts` runs in the plugin's own classloader and applies to
root + all subprojects (`-Pxuexue.nativeAgent=<dll>` similarly pins test
JVMs to Java 25 for capture runs).

## Build pipeline

```powershell
powershell -ExecutionPolicy Bypass -File build-native.ps1
# optional extra config dirs (tracing-agent captures):
$env:XUEXUE_EXTRA_CONFIG_DIRS = 'build/agent-tests,build/agent-swing'
```

1. `desktopJar -Pxuexue.nativeJvmTarget=24` (fat jar incl. `xuexue_mupdf.dll`)
2. bytecode check (`javap` major version must be 68)
3. `native-image` with the config dirs + module opens +
   `--initialize-at-run-time` for androidx + javax.swing
4. staging: jdk_library DLLs -> `build/runtime-home/bin`,
   `skiko-windows-x64.dll` + `icudtl.dat` -> `build/skiko`,
   **fontconfig files** -> `build/runtime-home/lib` (see Fixes)

Note: `-H:ConfigurationFileDirectories` **cannot take comma-joined paths
on Windows** (InvalidPathException) — each directory is passed as its own
option. Extra dirs arrive via `XUEXUE_EXTRA_CONFIG_DIRS` because
`powershell -File` mangles comma-separated array arguments.

## Runtime properties (run-native.ps1)

- `-Djava.home=build/runtime-home` — AWT jdk_library DLLs + fontconfig
- `-Dskiko.library.path=build/skiko` — Skiko DLL + ICU data
- `-Dswing.defaultlaf=...MetalLookAndFeel` — pure-Java L&F (the Windows
  L&F at runtime init segfaults in this image; build-time init is fine)
- `-Dsun.java2d.uiScale=<auto>` + `-Dsun.java2d.dpiaware=true` — crisp
  Swing dialogs on HiDPI displays
- `-Xmx1g` — mirrors the JVM build's memory cap

The MuPDF DLL is embedded as a resource (`xuexue/native/xuexue_mupdf.dll`)
and extracted at runtime by `MuPdfNative.extractClasspathLibrary()`.

## Config inventory

Three sources, merged by native-image (one option per dir):

- `config/` — hand-written base:
  - `jni-config.json`: AWT/sun.awt.windows classes (FindClass from the
    native AWT event thread) + `dev.xuexue.platform.MuPdfNative` (37 native
    methods) + `PdfTryLaterException` (thrown from the bridge), all with
    `allDeclaredFields/Methods/Constructors`
  - `resource-config.json`: `xuexue/native/*` (MuPDF DLL), skiko DLL +
    sha256, `icudtl.dat`, `*.properties`
  - `reachability-metadata.json`: tracing-agent output from a live JVM run
    of the real app (window + mouse + Swing + Compose paths)
- `build/agent-tests/` — desktopTest run under the tracing agent (JDK 25):
  MuPDF JNI find-class targets, serialization, PDF codec paths
- `build/agent-swing/` — `ChooserProbe` (renders JFileChooser + JOptionPane
  in a JFrame): the L&F UI classes that Swing loads via `Class.forName`

## Fixes log (why each config exists)

1. **AWT fields/constructors** — `Window.setShape` and the native event
   loop access `sun.java2d.pipe.*` fields and `MouseEvent` constructors via
   JNI; without registration: `NoSuchFieldError` / `NoSuchMethodError`.
2. **Swing L&F classes** — `UIDefaults.getUI` resolves UI class names with
   `Class.forName`; the L&F table is empty unless the UI classes are in the
   config (`no ComponentUI class for` error). Captured by `ChooserProbe`.
3. **Fontconfig** — Swing font machinery reads `java.home/lib/fontconfig*`;
   without them: `Fontconfig head is null` (fixed by staging the files).
4. **HiDPI** — the native AWT does not detect the display scale itself;
   Swing dialogs render blurry. `run-native.ps1` reads AppliedDPI from the
   registry and forces `sun.java2d.uiScale`.
5. **Config dirs on Windows** — comma-joined `ConfigurationFileDirectories`
   throws InvalidPathException; pass one option per dir (see Build).
6. **`com.sun.java.swing.plaf.windows` runtime init** — causes a DEP
   segfault in `Win32GraphicsEnvironment.initDisplay`; keep it at
   build-time init and force Metal via `swing.defaultlaf`.

## Known limitations

- Windows x64 only so far (Linux/macOS staging unverified)
- Swing dialogs use the Metal look (not the Windows native look)
- AI providers / OCR sidecar connections are untested under the image
- Long-session texture-cache behavior differs from the JVM (no G1 idle
  shrink; the app's own texture budgets still apply)
