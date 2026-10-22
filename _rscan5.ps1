$mj = "F:\SublimationProject\sublimation for android\sdk-lite\work\classes\com_google_android_material__material-1.12.0.jar"
$enc = [Text.Encoding]::GetEncoding("ISO-8859-1")
$s = $enc.GetString([IO.File]::ReadAllBytes($mj))
$r1 = $s.IndexOf('R$style')
$r2 = $s.IndexOf('R$attr')
$r3 = $s.IndexOf('material/R')
Write-Host ('Rstyle=' + $r1 + ' Rattr=' + $r2 + ' materialR=' + $r3)