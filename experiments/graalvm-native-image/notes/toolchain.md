# Toolchain notes

## GraalVM install

GraalVM **Community 25.2.4** (JDK 25.0.4) from the official GitHub releases
(`graalvm/graalvm-ce-builds` tag `graal-25.2.4`):

```
D:\Dev\graalvm\graalvm-community-25.2.4+7.1
  bin\java.exe           (JDK 25.0.4, JVMCI 25.2-b20)
  bin\native-image.cmd   (native-image 25.0.4)
```

## JDK version mismatch (important)

The main project builds with `jvmToolchain(26)` (JDK 26 bytecode, class file
version 70). The newest GraalVM community release as of 2026-08-14 is built
on **JDK 25** (class file version 69), and **no JDK-26 GraalVM exists yet**
(checked `graalvm/graalvm-ce-builds` releases 2026-07-28).

Consequences:

- `native-image` 25.x cannot consume the project's JDK-26 class files.
- Options: (a) wait for GraalVM for JDK 26 (expected ~Q4 2026); (b) run the
  feasibility experiment on a **standalone minimal Compose project** with
  `jvmTarget = 25` — the resulting config/reflect/JNI knowledge transfers
  back to the main project unchanged.

## Smoke test (2026-08-14)

Plain `Hello` class compiled AOT on Windows:

```
native-image -cp . Hello -o hello-native   # 46.2s build, 2 warnings
hello-native.exe                            # prints, ~30ms startup+run
```

Toolchain verified. Next step is the minimal Compose experiment (jvmTarget
25) to measure how far Compose 1.8.2 + Skiko get under Native Image.
