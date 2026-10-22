Get-ChildItem "C:\Users\ZhuanZ\.gradle\caches\modules-2\files-2.1\com.google.android.material\material" -Directory | Select-Object -ExpandProperty Name
Write-Host "---BT---"
Get-ChildItem "F:\AndroidSDK\build-tools" -Directory | Select-Object -ExpandProperty Name
Write-Host "---PLAT---"
Get-ChildItem "F:\AndroidSDK\platforms" -Directory | Select-Object -ExpandProperty Name