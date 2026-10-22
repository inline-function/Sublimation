$dex = "F:\SublimationProject\sublimation for android\sdk-lite\work\dex\classes.dex"
$enc = [Text.Encoding]::GetEncoding("ISO-8859-1")
$s = $enc.GetString([IO.File]::ReadAllBytes($dex))
$r1 = $s.IndexOf('Lcom/google/android/material/R$style;')
$r2 = $s.IndexOf('Lcom/google/android/material/R\$style;')
Write-Host ('style-plain=' + $r1 + ' style-esc=' + $r2)