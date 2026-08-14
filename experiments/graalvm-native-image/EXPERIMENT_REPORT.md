# Compose Desktop + GraalVM Native Image — experiment report

Date: 2026-08-14 · GraalVM CE 25.2.4 (JDK 25.0.4) · Compose Multiplatform 1.9.0 ·
Kotlin 2.2.21 · jvmTarget 24 · Windows x64

## Verdict (updated)

**Viable.** A minimal Compose Desktop app compiles with `native-image` and
runs **without exceptions**: window shows, composition and animation execute,
process exits cleanly.

| Metric | JVM build | Native build |
| --- | --- | --- |
| Window shown | ~1-2 s | <2.5 s |
| RSS while running | ~300-400 MB | **~113 MB** |
| Exceptions | - | none |

## What worked

1. AOT compilation succeeds (`native-image -jar`, ~1-2 min, ~35 MB exe).
2. With the configuration below the app creates a window, runs Compose
   composition with animation, and exits cleanly (auto-exit after 6 s).
3. RSS ~113 MB vs ~300-400 MB for the same app on the JVM.

## Required configuration (the 5 keys)

1. **Module opens** (`native-image.properties`):
   `--add-opens=java.desktop/sun.awt=ALL-UNNAMED` (plus `java.awt`,
   `javax.swing`, `sun.java2d` exports) — fixes AWT static-init failures.
2. **`jni-config.json`** with ~80 `java.awt.*` / `sun.awt.windows.*` classes —
   fixes JNI `FindClass` from the native AWT event thread.
3. **`-Djava.home=<dir>`** where `<dir>/bin` holds the `jdk_library` DLLs
   emitted by native-image (`awt.dll`, `jawt.dll`, `fontmanager.dll`, ...).
4. **`-Dskiko.library.path=<dir>`** pointing at an extracted
   `skiko-windows-x64.dll` — skips Skiko's resource-unpack path, which
   fails under Substrate.
5. **`icudtl.dat`** next to the Skiko DLL — Skia's Paragraph/ICU init
   (`check(fUnicode)`) fails without it.

Tracing-agent `reachability-metadata.json` (reflection + JNI) is also
bundled under `META-INF/native-image/`; `META-INF` auto-discovery of the
older config files did not fully apply, so the flags above are passed
explicitly on the command line.

## Version combination that works

- Compose Multiplatform **1.9.0** + Kotlin **2.2.21** + Skiko 0.9.22.x
- GraalVM CE **25.2.4** (JDK 25), `jvmTarget`/bytecode **24**
- A prior attempt with Compose **1.8.2** got the window up but crashed in
  Compose recomposition (`ScopeMap.set` NPE); 1.9.0 does not hit that path.

## What is NOT yet verified for XueXue

- The real app is much bigger: 37 JNI externals in `MuPdfNative` + the
  `xuexue_mupdf` bridge, kotlinx.serialization (12 types), the AI client
  stack (HTTP streaming, coroutines), workspace/file I/O.
- The main project is on Compose 1.8.2; upgrading to 1.9.0 is a prerequisite
  for a native build (or the 1.8.2 recomposition crash must be solved).

## Repro

```
experiments/graalvm-native-image/compose-smoke/
  gradlew.bat fatJar
  native-image -H:+JNI -H:IncludeResources='skiko-windows-x64\.dll(\.sha256)?' \
    --no-fallback -jar build/libs/compose-smoke.jar build/compose-smoke-native
  # assemble runtime-home/bin from emitted jdk_library DLLs
  # extract skiko-windows-x64.dll + icudtl.dat next to the exe
  compose-smoke-native.exe -Djava.home=build/runtime-home \
    -Dskiko.library.path=build
```
