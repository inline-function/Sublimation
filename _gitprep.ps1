Set-Location "F:/SublimationProject/sublimation for android"
if (-not (Test-Path .gitignore)) { New-Item .gitignore -ItemType File | Out-Null }
$ig = Get-Content .gitignore -Raw
if (-not $ig.Contains('sdk-lite')) {
  Add-Content .gitignore 'sdk-lite/bin/'
  Add-Content .gitignore 'sdk-lite/lib/'
  Add-Content .gitignore 'sdk-lite/libs/'
  Add-Content .gitignore 'sdk-lite/keystore/'
  Add-Content .gitignore 'sdk-lite/work/'
  Add-Content .gitignore 'sdk-lite/out/'
  Add-Content .gitignore 'AndroidSDK for CandyIDE/'
}
git status --short | Select-Object -First 12 | ForEach-Object { "S " + $_.Trim() }