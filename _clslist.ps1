$co = "F:\SublimationProject\sublimation for android\sdk-lite\work\appclasses"
Get-ChildItem -Recurse $co | ForEach-Object { Write-Host ("F " + $_.FullName.Substring($co.Length + 1)) }