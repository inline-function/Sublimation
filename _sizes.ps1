Remove-Item "F:/SublimationProject/sublimation for android/sdk-lite/work" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "F:/SublimationProject/sublimation for android/sdk-lite/out" -Recurse -Force -ErrorAction SilentlyContinue
$root = "F:/SublimationProject/sublimation for android/sdk-lite"
function SizeOf($d) { $s = (Get-ChildItem $d -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Sum Length).Sum; return [math]::Round($s/1MB, 1) }
foreach ($d in @('bin', 'lib', 'libs', 'keystore', 'build')) { Write-Host ($d + ' ' + (SizeOf (Join-Path $root $d)) + 'MB') }
$total = (Get-ChildItem $root -Recurse -File | Measure-Object -Sum Length).Sum
Write-Host ('TOTAL=' + [math]::Round($total/1MB, 1) + 'MB')