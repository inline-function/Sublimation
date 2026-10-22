$cls = "F:/SublimationProject/sublimation for android/sdk-lite/work/classes"
Write-Host ("exists=" + (Test-Path $cls))
if (Test-Path $cls) { Write-Host ("count=" + @(Get-ChildItem $cls -Filter "*.jar").Count) }