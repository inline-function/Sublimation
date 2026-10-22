$g = Get-Item "F:/SublimationProject/AndroidSDK for CandyIDE.zip"
Write-Host ('exists=' + ($g -ne $null))
$f = "F:/SublimationProject/sublimation for android/android doc/Termux构建评估.md"
if (Test-Path $f) { Remove-Item $f -Force; Write-Host "old-eval-doc-removed" }