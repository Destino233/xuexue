# One-shot rebuild after source changes: re-capture tracing-agent config
# from the test suite, rebuild the native image, re-stage. Run from the
# repo root with the same JAVA_HOME/GRADLE_USER_HOME as normal builds.
$ErrorActionPreference = 'Stop'
$mainApp = 'D:\Project\xuexue-main\experiments\graalvm-native-image\main-app'
$repo = 'D:\Project\xuexue-main'
$graalvm = 'D:\Dev\graalvm\graalvm-community-25.2.4+7.1'
$agentDll = Join-Path $graalvm 'bin\native-image-agent.dll'

Write-Host '==> [1/3] re-capturing tracing-agent config from desktopTest (JDK 25)'
Push-Location $repo
try {
    & '.\gradlew.bat' desktopTest '-Pxuexue.nativeJvmTarget=24' ('-Dxuexue.nativeAgent=' + $agentDll) ('-Porg.gradle.java.installations.paths=' + $graalvm) --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'test capture failed' }
} finally {
    Pop-Location
}

Write-Host '==> [2/3] rebuilding native image'
$env:XUEXUE_EXTRA_CONFIG_DIRS = 'build/agent-tests,build/agent-swing'
powershell -ExecutionPolicy Bypass -File (Join-Path $mainApp 'build-native.ps1')
if ($LASTEXITCODE -ne 0) { throw 'native build failed' }

Write-Host '==> [3/3] done'
Write-Host ('Launch with:  ' + (Join-Path $mainApp 'run-native.cmd'))
