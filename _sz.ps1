$mj = "F:\SublimationProject\sublimation for android\sdk-lite\work\classes\com_google_android_material__material-1.12.0.jar"
Write-Host ("size=" + (Get-Item $mj).Length)
$b = [IO.File]::ReadAllBytes($mj)
Write-Host ("bytes=" + $b.Length)