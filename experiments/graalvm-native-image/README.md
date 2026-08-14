# GraalVM Native Image Experiment

Exploratory workspace for ahead-of-time (AOT) compiling the XueXue desktop
app with GraalVM Native Image. The goal is to measure whether the ~250-300MB
of JVM overhead (JIT compiler, code cache, class metadata) can be traded
for build-time configuration effort.

## Status

- [x] Toolchain installed and `native-image` smoke-tested (GraalVM CE 25.2.4)
- [x] Minimal non-Compose AOT build works (hello-native, 30ms startup)
- [x] Minimal Compose AOT build **works** (Compose 1.9.0 + GraalVM 25.2.4):
      window + animation + clean exit, RSS ~113MB. See `EXPERIMENT_REPORT.md`.
- [x] **Real app AOT build works** (see `main-app/`): the full XueXue app
      (MuPDF JNI, kotlinx.serialization, AI client stack) compiles with
      native-image; window, workspace chooser, and PDF reading verified on
      Windows x64, RSS ~140MB vs ~600-700MB JVM baseline.
- [x] Reflect/JNI/resource config assembled from the inventory below
- [x] RSS and startup measured against the JVM build (~113MB vs ~300-400MB)

## Expected trade-off (from the main README discussion)

| | JVM build (current) | Native Image (target) |
| --- | --- | --- |
| Startup | ~1-3s | <200ms |
| RSS steady | ~600-700MB | ~350-450MB |
| Build time | minutes | 10-30 min (AOT) |
| Dynamic features | unrestricted | closed-world, need config files |

## Key risks for this codebase

1. **Compose Multiplatform desktop support for Native Image is experimental.**
   JetBrains documents a setup, but it lags the stable release train and
   frequently breaks on upgrades (we are on Compose 1.8.2 / Kotlin 2.4.0).
2. **kotlinx.serialization** (12 `@Serializable` types in `src/`) needs
   serializer registration; with the serialization plugin this is mostly
   compile-time, but polymorphic/default implementations may need config.
3. **37 JNI `external fun`s** in `MuPdfNative` plus the `xuexue_mupdf`
   bridge: JNI must be explicitly enabled (`-H:+JNI`), the library loaded
   at runtime (System.load) works, but every signature is part of the ABI.
4. **Skiko/Compose native libraries** ship per-platform; AOT builds must
   bundle and load them exactly like the JVM build does (resources dir).
5. **AI client stack** (HTTP streaming, JSON, coroutines) is mostly
   plain code; coroutines are supported, but check `kotlinx-coroutines`
   Native Image compatibility on the chosen GraalVM version.

## Layout

```
experiments/graalvm-native-image/
  README.md            this file
  INVENTORY.md         dynamic-feature inventory (reflect/JNI/serialization)
  native-image/        draft GraalVM config files (reflect/resource/jni)
  notes/               build logs and findings
```

## Toolchain

GraalVM Community is downloaded to `D:\Dev\graalvm\` (outside the repo so
it never touches git). See `notes/toolchain.md` for the exact version and
install steps.