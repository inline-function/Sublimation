$apk = "F:/SublimationProject/sublimation for android/sdk-lite/out/app-debug.apk"
if (Test-Path $apk) {
  $t = (Get-Item $apk).LastWriteTime
  Write-Host ("apk=" + [math]::Round((Get-Item $apk).Length/1MB, 2) + "MB mtime=" + $t.ToString("HH:mm:ss"))
} else { Write-Host "NO-APK" }