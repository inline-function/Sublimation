$adb = "F:/AndroidSDK/platform-tools/adb.exe"
& $adb shell am force-stop functor.sublimation.android
& $adb logcat -b all -c
& $adb shell am start --user 0 -n functor.sublimation.android/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 8
$pidLine = (& $adb shell pidof functor.sublimation.android) -replace "s", ""
Write-Host ("pid=" + $pidLine)
$log = & $adb logcat -d --pid=$pidLine 2>&1 | Out-String
Write-Host ("loglen=" + $log.Length)
foreach ($l in ($log -split [char]10)) {
  if ($l.Length -gt 1) { Write-Host ("P " + $l.Substring(0, [Math]::Min(140, $l.Length))) }
}