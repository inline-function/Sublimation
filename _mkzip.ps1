$src = "F:/SublimationProject/sublimation for android/sdk-lite"
$dst = "F:/SublimationProject/AndroidSDK for CandyIDE"
if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
Copy-Item $src $dst -Recurse
Compress-Archive -Path $dst -DestinationPath "F:/SublimationProject/AndroidSDK for CandyIDE.zip" -Force
$z = Get-Item "F:/SublimationProject/AndroidSDK for CandyIDE.zip"
Write-Host ('ZIP=' + [math]::Round($z.Length/1MB, 1) + 'MB path=' + $z.FullName)