# Focused PDF-open verification for the native build.
# Signals: window title (workspace accepted -> 'test - XueXue'), RSS jump
# (PDF loaded), stderr errors (MuPDF JNI failures would print here).
param(
    [string]$OutDir = "build"
)
$ErrorActionPreference = 'Continue'
$mainApp = 'D:\Project\xuexue-main\experiments\graalvm-native-image\main-app'
$out = Join-Path $mainApp $OutDir
$exe = Join-Path $out "XueXueNative.exe"
$rh = Join-Path $out "runtime-home"
$sk = Join-Path $out "skiko"
$outLog = Join-Path $mainApp 'build\pdfverify-out.txt'
$errLog = Join-Path $mainApp 'build\pdfverify-err.txt'

$proc = Start-Process -FilePath $exe -ArgumentList ('-Djava.home=' + $rh, '-Dskiko.library.path=' + $sk, '-Xmx1g') -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Write-Output ('PID=' + $proc.Id)

function Get-Title {
  $p = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
  if ($p) { return $p.MainWindowTitle } else { return '<exited>' }
}

Start-Sleep -Seconds 15
Write-Output ('T0 title=[' + (Get-Title) + ']')

try {
  Add-Type -AssemblyName System.Windows.Forms
  $wshell = New-Object -ComObject WScript.Shell
  [void]$wshell.AppActivate($proc.Id)
  Start-Sleep -Milliseconds 600
  $wshell.SendKeys('D:\\test{ENTER}')
  Start-Sleep -Seconds 8
  Write-Output ('T1 title=[' + (Get-Title) + ']')
  [void]$wshell.AppActivate($proc.Id)
  $wshell.SendKeys('^o')
  Start-Sleep -Seconds 3
  $wshell.SendKeys('D:\\test\\VWC3-H-Brochure.pdf{ENTER}')
  Start-Sleep -Seconds 20
  Write-Output ('T2 title=[' + (Get-Title) + ']')
} catch {
  Write-Output ('AUTOMATION_WARN: ' + $_.Exception.Message)
}

$p3 = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if ($p3) {
  Write-Output ('FINAL RSS_MB=' + [math]::Round($p3.WorkingSet64/1MB, 0))
  # check whether a workspace/pdf window is up via child window titles
  try {
    [void]$wshell.AppActivate($proc.Id)
    $wshell.SendKeys('%{F4}')
    Start-Sleep -Seconds 2
    $wshell.SendKeys('%{F4}')
  } catch {}
  if (-not $proc.WaitForExit(15000)) { Stop-Process -Id $proc.Id -Force; Write-Output 'FORCE_KILLED' } else { Write-Output 'GRACEFUL_EXIT' }
} else {
  Write-Output 'EXITED_DURING_VERIFY'
}
