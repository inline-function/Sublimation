$adb = "F:/AndroidSDK/platform-tools/adb.exe"
& $adb shell am force-stop functor.sublimation.android
& $adb uninstall functor.sublimation.android 2>&1 | Out-Null
& $adb install "F:/SublimationProject/sublimation for android/sdk-lite/out/app-debug.apk" 2>&1 | ForEach-Object { "I " + $_ }
& $adb logcat -b all -c
& $adb shell am start --user 0 -n functor.sublimation.android/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 7
& $adb shell uiautomator dump /sdcard/rx12.xml 2>&1 | Out-Null
& $adb pull /sdcard/rx12.xml "F:/KotlinProject/sublimation/_rx12.xml" 2>&1 | Out-Null
$t = [System.IO.File]::ReadAllText("F:/KotlinProject/sublimation/_rx12.xml")
Write-Host ("static=" + $t.Contains("不变的视图") + " count=" + $t.Contains("count:"))
& $adb logcat -b all -c
& $adb shell input tap 630 1451 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell input tap 630 1451 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell uiautomator dump /sdcard/rx13.xml 2>&1 | Out-Null
& $adb pull /sdcard/rx13.xml "F:/KotlinProject/sublimation/_rx13.xml" 2>&1 | Out-Null
$t2 = [System.IO.File]::ReadAllText("F:/KotlinProject/sublimation/_rx13.xml")
Write-Host ("after2-static=" + $t2.Contains("不变的视图") + " count2=" + $t2.Contains("count: 2"))
$log = & $adb logcat -d -b all 2>&1 | Out-String
foreach ($l in ($log -split [char]10)) { if ($l -match "Sublimation: diff:") { Write-Host ("L: " + $l.Substring(0, [Math]::Min(110, $l.Length))) } }