$adb = "F:/AndroidSDK/platform-tools/adb.exe"
& $adb shell am force-stop functor.sublimation.android
& $adb logcat -b all -c
& $adb shell am start --user 0 -n functor.sublimation.android/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 7
$log = & $adb logcat -d -b all 2>&1 | Out-String
foreach ($l in ($log -split [char]10)) {
  if ($l -match "Sublimation|AndroidRuntime|FATAL|functor.sublimation") { Write-Host ("L " + $l.Substring(0, 150)) }
}