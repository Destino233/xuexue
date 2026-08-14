# native-image draft configuration

Placeholder configuration files for the AOT build. These will be filled as
experiments progress; the `-agentlib:native-image-agent` tracing run (the
JVM build launched with the agent) is the canonical way to generate them:

```bash
# 1. Run the JVM app with the tracing agent and exercise PDF + AI features
java -agentlib:native-image-agent=config-output-dir=native-image \
     -jar build/libs/xuexue-desktop.jar

# 2. Review the generated reflect-config.json / resource-config.json / jni-config.json
# 3. Build with GraalVM:
native-image -H:+JNI -H:ConfigurationFileDirectories=native-image \
     -jar build/libs/xuexue-desktop.jar xuexue-native
```

Known hard spots to watch when reviewing agent output:

- kotlinx.serialization generated serializers (registered via the compiler
  plugin; the agent sees them as class metadata, not reflective calls).
- Skiko's JNI-heavy internals (loads `skiko-windows-x64.dll`).
- `MuPdfNative` JNI class registration (`System.loadLibrary` + externals).
- Compose resource lookup through `compose.application.resources.dir`.
