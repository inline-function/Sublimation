$lib = "F:/SublimationProject/sublimation for android/sdk-lite/lib"
Get-ChildItem $lib -File | ForEach-Object { Write-Host ($_.Name + " " + [math]::Round($_.Length/1MB, 1) + "MB") }