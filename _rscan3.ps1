$mj = "F:\SublimationProject\sublimation for android\sdk-lite\work\classes\com_google_android_material__material-1.12.0.jar"
Write-Host ("material-exists=" + (Test-Path $mj))
$s = [Text.Encoding]::Latin1.GetString([IO.File]::ReadAllBytes($mj))
Write-Host ('len=' + $s.Length)
$i = $s.IndexOf('R$style')
Write-Host ('Rstyle-idx=' + $i)
if ($i -gt 0) { Write-Host $s.Substring([Math]::Max(0, $i - 60), 80) }