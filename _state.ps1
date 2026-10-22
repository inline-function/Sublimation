$adb = "F:/AndroidSDK/platform-tools/adb.exe"
& $adb shell pm list packages functor.sublimation 2>&1
& $adb shell dumpsys package functor.sublimation.android 2>&1 | Select-String "versionName|firstInstallTime|lastUpdateTime|enabled=" | Select-Object -First 4 | ForEach-Object { $_.Line.Trim() }