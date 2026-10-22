$log = & "F:/AndroidSDK/platform-tools/adb.exe" logcat -d --pid=1461 2>&1 | Out-String
foreach ($l in ($log -split [char]10)) {
  if ($l -match "Sublimation|WebView|ERROR|FATAL|chromium|ERR_|exception") { Write-Host ("X " + $l.Substring(0, [Math]::Min(150, $l.Length))) }
}