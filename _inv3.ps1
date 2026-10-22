$base = "F:/SublimationProject/sublimation for android/android-app/app/src/main"
Get-ChildItem $base -Recurse -File | Select-Object -ExpandProperty FullName | ForEach-Object { $_.Replace($base, "") }
Write-Host "---ASSETS---"
Get-ChildItem "$base\assets" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name