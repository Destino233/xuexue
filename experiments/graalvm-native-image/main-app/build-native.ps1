# Builds a GraalVM native-image executable of the real XueXue app.
#
# Pipeline: fat jar (jvmTarget 24 via -Pxuexue.nativeJvmTarget=24) -> native-image
# -> runtime staging (jdk_library DLLs under runtime-home/bin, Skiko DLL +
# icudtl.dat under skiko/). Everything lands in ./build; nothing outside the
# experiments tree is touched.
#
# Prereqs: GraalVM CE 25.2.4 (JDK 25) native-image on PATH or GRAALVM_HOME,
# the main project's .mupdf DLL already bootstrapped (gradle does it).
#
# Usage:  powershell -ExecutionPolicy Bypass -File build-native.ps1
#         -ExtraConfigDirs build/agent-tests  (tracing-agent capture)
param(
    [string]$OutDir = "build",
    [string[]]$ExtraConfigDirs = @()
)
$ErrorActionPreference = "Stop"

$mainApp = $PSScriptRoot
$graalvmExp = Split-Path -Parent $mainApp
$experiments = Split-Path -Parent $graalvmExp
$repo = Split-Path -Parent $experiments
$gradlew = Join-Path $repo "gradlew.bat"
$graalvmHome = if ($env:GRAALVM_HOME) { $env:GRAALVM_HOME } else { "D:\Dev\graalvm\graalvm-community-25.2.4+7.1" }
$nativeImage = Join-Path $graalvmHome "bin\native-image.cmd"

if (-not (Test-Path $nativeImage)) {
    throw "native-image.cmd not found at $nativeImage (set GRAALVM_HOME)"
}

$out = Join-Path $mainApp $OutDir
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "==> [1/4] fat jar with jvmTarget 24 (-Pxuexue.nativeJvmTarget=24)"
Push-Location $repo
try {
    & $gradlew desktopJar '-Pxuexue.nativeJvmTarget=24' --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle desktopJar failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$fatJar = Get-ChildItem (Join-Path $repo "build\libs\xuexue-desktop-*.jar") |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Select-Object -First 1
if (-not $fatJar) { throw "fat jar not found under $repo\build\libs" }
Write-Host "    jar: $($fatJar.FullName)"

Write-Host "==> [2/4] bytecode version check (need major 68, Java 24)"
$javapOut = Join-Path $out "javap.txt"
& (Join-Path $graalvmHome "bin\javap.exe") -verbose -cp $fatJar.FullName dev.xuexue.platform.MuPdfNative *> $javapOut
$major = Select-String -Path $javapOut -Pattern 'major version: (\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
Write-Host "    class file major version: $major (expect 68)"
if ($major -ne "68") { throw "bytecode major version $major is not runnable on GraalVM 25 (JDK 25); expected 68" }

Write-Host "==> [3/4] native-image (this takes 1-3 min)"
# native-image rejects comma-joined config dirs on Windows (InvalidPathException),
# so each directory becomes its own -H:ConfigurationFileDirectories option.
# PowerShell -File mangles comma-separated array args, so extra dirs are read
# from $env:XUEXUE_EXTRA_CONFIG_DIRS (comma-separated) in addition to -ExtraConfigDirs.
$extraDirs = @($ExtraConfigDirs)
if ($env:XUEXUE_EXTRA_CONFIG_DIRS) {
    $extraDirs += $env:XUEXUE_EXTRA_CONFIG_DIRS.Split(',')
}
$configArgs = @("-H:ConfigurationFileDirectories=" + (Join-Path $mainApp "config"))
foreach ($extra in $extraDirs) {
    $configArgs += "-H:ConfigurationFileDirectories=" + (Join-Path $mainApp $extra.Trim())
}
Write-Host "    config dirs:"
$configArgs | ForEach-Object { Write-Host "      $_" }
$exeName = "XueXueNative"
$log = Join-Path $out "native-image.log"
# Splatting is the only reliable way to pass arrays to native commands in PS 7.
$allArgs = @(
    "-jar", $fatJar.FullName,
    "-H:Name=$exeName",
    "-H:Path=$out"
) + $configArgs + @(
    "-H:+JNI",
    "-H:+UnlockExperimentalVMOptions",
    "-H:+ReportExceptionStackTraces",
    "-H:+AddAllCharsets",
    "--no-fallback",
    "--enable-native-access=ALL-UNNAMED",
    "--enable-url-protocols=http,https",
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
    "--add-exports=java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
    "--initialize-at-run-time=androidx.compose",
    "--initialize-at-run-time=androidx.collection",
    "--initialize-at-run-time=androidx.animation",
    "--initialize-at-run-time=androidx.lifecycle",
    "--initialize-at-run-time=androidx.arch",
    "--initialize-at-run-time=javax.swing",
    "-J-Xmx6g"
)
& $nativeImage @allArgs *> $log
if ($LASTEXITCODE -ne 0) {
    Write-Host "    native-image FAILED (exit $LASTEXITCODE); log: $log"
    exit $LASTEXITCODE
}
Write-Host "    image: $out\$exeName.exe"

Write-Host "==> [4/4] runtime staging"
$runtimeHomeBin = Join-Path $out "runtime-home\bin"
New-Item -ItemType Directory -Force -Path $runtimeHomeBin | Out-Null
Get-ChildItem $out -Filter "*.dll" | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $runtimeHomeBin $_.Name) -Force
}
# AWT font machinery (FontConfiguration) reads java.home/lib/fontconfig*;
# without them Swing/JFileChooser fails with "Fontconfig head is null".
$runtimeHomeLib = Join-Path $out "runtime-home\lib"
New-Item -ItemType Directory -Force -Path $runtimeHomeLib | Out-Null
Get-ChildItem (Join-Path $graalvmHome "lib") -Filter "fontconfig*" | ForEach-Object {
    Copy-Item $_.FullName $runtimeHomeLib -Force
}
Get-ChildItem (Join-Path $graalvmHome "lib") -Filter "psfont*" | ForEach-Object {
    Copy-Item $_.FullName $runtimeHomeLib -Force
}
$skikoDir = Join-Path $out "skiko"
New-Item -ItemType Directory -Force -Path $skikoDir | Out-Null
Push-Location $skikoDir
try {
    & (Join-Path $graalvmHome "bin\jar.exe") xf $fatJar.FullName skiko-windows-x64.dll skiko-windows-x64.dll.sha256 icudtl.dat
} finally {
    Pop-Location
}
Write-Host "    runtime-home/bin: $((Get-ChildItem $runtimeHomeBin -Filter *.dll).Count) DLLs"
Write-Host "    skiko: $((Get-ChildItem $skikoDir).Count) files"

Write-Host "DONE. Run with:  powershell -ExecutionPolicy Bypass -File run-native.ps1"