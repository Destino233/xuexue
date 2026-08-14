# Full verification of the native XueXue build: window, mouse events,
# workspace chooser, best-effort PDF open, RSS, graceful exit.
param(
    [string]$OutDir = "build"
)
$ErrorActionPreference = 'Continue'
$mainApp = 'D:\Project\xuexue-main\experiments\graalvm-native-image\main-app'
$out = Join-Path $mainApp $OutDir
$exe = Join-Path $out "XueXueNative.exe"
$rh = Join-Path $out "runtime-home"
$sk = Join-Path $out "skiko"
$outLog = Join-Path $mainApp 'build\verify-out.txt'
$errLog = Join-Path $mainApp 'build\verify-err.txt'

$proc = Start-Process -FilePath $exe -ArgumentList ('-Djava.home=' + $rh, '-Dskiko.library.path=' + $sk, '-Xmx1g') -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Write-Output ('PID=' + $proc.Id)
Start-Sleep -Seconds 20
$p2 = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if (-not $p2) { Write-Output 'DIED_EARLY'; exit 1 }
Write-Output ('WINDOW=[' + $p2.MainWindowTitle + '] RSS_MB=' + [math]::Round($p2.WorkingSet64/1MB, 0))

try {
  Add-Type -AssemblyName System.Windows.Forms
  $wshell = New-Object -ComObject WScript.Shell
  # move the mouse across the window to exercise AWT mouse events
  foreach ($i in @(1,2,3,4)) {
    $mx = 600 + 60 * $i; $my = 350 + 40 * $i
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($mx, $my)
    Start-Sleep -Milliseconds 700
  }
  # select a workspace folder with PDFs (D:\test) in the startup chooser
  [void]$wshell.AppActivate($proc.Id)
  Start-Sleep -Milliseconds 600
  $wshell.SendKeys('D:\\test{ENTER}')
  Start-Sleep -Seconds 10
  # try to open the brochure PDF (best effort)
  [void]$wshell.AppActivate($proc.Id)
  $wshell.SendKeys('^o')
  Start-Sleep -Seconds 3
  $wshell.SendKeys('D:\\test\\VWC3-H-Brochure.pdf{ENTER}')
  Start-Sleep -Seconds 15
} catch {
  Write-Output ('AUTOMATION_WARN: ' + $_.Exception.Message)
}
$p3 = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if ($p3) {
  Write-Output ('FINAL RSS_MB=' + [math]::Round($p3.WorkingSet64/1MB, 0))
  # graceful exit so any agent/handler flushes
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
