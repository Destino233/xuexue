# Compose Desktop + GraalVM Native Image — experiment report

Date: 2026-08-14 · GraalVM CE 25.2.4 (JDK 25.0.4) · Compose Multiplatform 1.8.2 ·
Kotlin 2.4.0 · Windows x64

## Verdict

**Not viable.** Compose Desktop depends on windowed AWT (Swing), and the
windowed AWT event loop (`sun.awt.windows.WToolkit`) does not work under
Substrate VM. The experiment got a native executable that creates a window
and runs Compose composition, but the native event thread fails to
initialize `java.awt.event.MouseEvent`, and no configuration level fixes it.

## What worked

1. AOT compilation of a minimal Compose app succeeds (`native-image -jar`,
   ~2 min, 34-39 MB executable + jdk_library DLLs).
2. With `-H:+JNI` + tracing-agent metadata (`reachability-metadata.json`) +
   a handwritten `jni-config.json` covering `java.awt.*`, the app **creates
   a window and runs the Compose composition** (7-8 s event loop, animation
   code executes).
3. Startup is ~30 ms for a plain native hello; the Compose window appears
   within ~1 s.

## What broke (in order)

| # | Symptom | Fix | Status |
| --- | --- | --- | --- |
| 1 | `NoSuchMethodError: java.awt.Toolkit.getDefaultToolkit` | tracing-agent metadata | fixed |
| 2 | `Cannot open (null)\bin\jawt.dll` | assemble `java.home/bin` from emitted jdk_library DLLs | fixed |
| 3 | `NoClassDefFoundError: java/awt/event/MouseEvent` (JNI FindClass) | `jni-config.json` with 80 AWT classes | fixed |
| 4 | `Could not initialize class java.awt.event.MouseEvent` (AWT-Windows thread) | `--initialize-at-run-time` (packages and exact classes) | **unfixable via config** |

## Root cause

Windowed AWT on Windows runs a native event loop (`WToolkit.eventLoop`) that
does JNI `FindClass`/`GetMethodID` against `sun.awt.windows` internals. The
Substrate VM JNI layer does not reproduce the full AWT-JDK contract: class
registration works, but the static initialization of AWT event classes in
the image heap is inconsistent with the runtime environment. External
evidence: [oracle/graal#6686](https://github.com/oracle/graal/issues/6686)
(“Swing still does not work”), [oracle/graal#4113](
https://github.com/oracle/graal/issues/4113) (Compose-specific AWT failure).

## Consequence for XueXue

The ~600-700 MB JVM baseline is a hard cost of the Compose Desktop stack;
Native Image cannot remove it for a windowed Compose app. Reducing memory
below that means changing the UI stack (e.g. C++/Qt), which is out of scope
for this repository. The in-app memory-limit setting (512-1024 MB) remains
the practical knob.

## Repro

```
experiments/graalvm-native-image/compose-smoke/   (minimal Compose app)
  gradlew.bat fatJar                              # build/libs/compose-smoke.jar
  native-image -H:+JNI -H:ConfigurationFileDirectories=native-image-config \
    --no-fallback -jar build/libs/compose-smoke.jar build/compose-smoke-native
  # run with -Djava.home=<dir> where <dir>/bin holds the emitted jdk_library DLLs
```
