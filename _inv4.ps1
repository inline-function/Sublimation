Write-Host ("kotlinc=" + ((Get-Command kotlinc -ErrorAction SilentlyContinue) -ne $null))
Write-Host ("keytool=" + ((Get-Command keytool -ErrorAction SilentlyContinue) -ne $null))
Get-ChildItem "F:\AndroidSDK\build-tools\35.0.0" | Where-Object { $_.Name -in @("aapt2.exe", "zipalign.exe") } | Select-Object -ExpandProperty Name | ForEach-Object { "BT " + $_ }
Get-ChildItem "F:\AndroidSDK\build-tools\35.0.0\lib" | Where-Object { $_.Name -in @("d8.jar", "apksigner.jar") } | Select-Object -ExpandProperty Name | ForEach-Object { "LIB " + $_ }
Write-Host ("android.jar=" + (Test-Path "F:\AndroidSDK\platforms\android-35\android.jar"))