# Lean capture: open the real app on the JVM, render the workspace
# chooser (Swing L&F Class.forName classes), close. 90s total.
$ErrorActionPreference = 'Continue'
$mainApp = 'D:\Project\xuexue-main\experiments\graalvm-native-image\main-app'
$repo = 'D:\Project\xuexue-main'
$agentDir = Join-Path $mainApp 'build\agent-app3'
if (Test-Path $agentDir) { Remove-Item $agentDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $agentDir | Out-Null
$jar = (Get-ChildItem (Join-Path $repo 'build\libs\xuexue-desktop-*.jar') | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1).FullName
Write-Output ('JAR=' + $jar)

$env:XUEXUE_MEMORY_RESTARTED = '1'
$java = 'D:\Dev\graalvm\graalvm-community-25.2.4+7.1\bin\java.exe'
$agentArg = '-agentlib:native-image-agent=config-output-dir=' + $agentDir
$libPath = '-Djava.library.path=' + $repo + '\.mupdf\1.27.2\windows-x86_64'
$freshHome = Join-Path $mainApp 'build\fresh-home'
New-Item -ItemType Directory -Force -Path $freshHome | Out-Null
$homeArg = '-Duser.home=' + $freshHome
$allArgs = @($agentArg, '--enable-native-access=ALL-UNNAMED', '--add-opens', 'java.desktop/java.awt=ALL-UNNAMED', '--add-opens', 'java.desktop/sun.awt.windows=ALL-UNNAMED', $libPath, $homeArg, '-jar', $jar)
$proc = Start-Process -FilePath $java -ArgumentList $allArgs -PassThru -RedirectStandardOutput (Join-Path $mainApp 'build\agent-app3-out.txt') -RedirectStandardError (Join-Path $mainApp 'build\agent-app3-err.txt')
Write-Output ('PID=' + $proc.Id)

try {
  $wshell = New-Object -ComObject WScript.Shell
  Add-Type -AssemblyName System.Windows.Forms
  Start-Sleep -Seconds 12
  [void]$wshell.AppActivate($proc.Id)
  Start-Sleep -Milliseconds 500
  $wshell.SendKeys('{TAB}{TAB}{ENTER}')
  Start-Sleep -Seconds 6
  foreach ($i in @(1,2,3,4)) {
    $mx = 500 + 80 * $i; $my = 300 + 50 * $i
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($mx, $my)
    Start-Sleep -Milliseconds 600
  }
  Start-Sleep -Seconds 3
} catch {
  Write-Output ('AUTOMATION_WARN: ' + $_.Exception.Message)
}
$alive = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if ($alive) {
  Write-Output ('ALIVE=1 RSS_MB=' + [math]::Round($alive.WorkingSet64/1MB, 0))
  try {
    [void]$wshell.AppActivate($proc.Id)
    $wshell.SendKeys('%{F4}')
    Start-Sleep -Seconds 2
    $wshell.SendKeys('%{F4}')
  } catch {}
  if (-not $proc.WaitForExit(20000)) { Stop-Process -Id $proc.Id -Force; Write-Output 'FORCE_KILLED' } else { Write-Output 'GRACEFUL_EXIT' }
} else {
  Write-Output 'EXITED_DURING_CAPTURE'
}
$cfgCount = (Get-ChildItem $agentDir -ErrorAction SilentlyContinue | Measure-Object).Count
Write-Output ('AGENT_FILES=' + $cfgCount)