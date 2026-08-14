# Launches the native-image XueXue build with the runtime staging it needs.
#
# Usage:  powershell -ExecutionPolicy Bypass -File run-native.ps1
param(
    [string]$OutDir = "build"
)
$ErrorActionPreference = "Stop"

$mainApp = $PSScriptRoot
$out = Join-Path $mainApp $OutDir
$exe = Join-Path $out "XueXueNative.exe"
if (-not (Test-Path $exe)) { throw "$exe not found - run build-native.ps1 first" }
$runtimeHome = Join-Path $out "runtime-home"
$skiko = Join-Path $out "skiko"

# -Xmx1g mirrors the JVM build's DEFAULT_APPLICATION_MEMORY_LIMIT_MIB.
# -Djava.home points AWT at the jdk_library DLLs emitted by native-image.
# -Dskiko.library.path skips Skiko's resource-unpack path (broken under Substrate).
# The native AWT does not detect the display scale itself (blurry Swing dialogs
# otherwise), so read the system scale from the registry and force it.
$appliedDpi = (Get-ItemProperty -Path 'HKCU:\Control Panel\Desktop\WindowMetrics' -Name AppliedDPI -ErrorAction SilentlyContinue).AppliedDPI
if ($appliedDpi) {
    $uiScale = [math]::Round($appliedDpi / 96, 2)
    Write-Host "    display scale: $appliedDpi DPI -> sun.java2d.uiScale=$uiScale"
} else {
    $uiScale = 1
}
& $exe `
    "-Djava.home=$runtimeHome" `
    "-Dskiko.library.path=$skiko" `
    "-Xmx1g", `
    ("-Dsun.java2d.uiScale=" + $uiScale), `
    "-Dsun.java2d.dpiaware=true"