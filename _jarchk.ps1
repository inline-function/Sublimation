$mj = "F:\SublimationProject\sublimation for android\sdk-lite\work\classes\com_google_android_material__material-1.12.0.jar"
$enc = [Text.Encoding]::GetEncoding("ISO-8859-1")
$s = $enc.GetString([IO.File]::ReadAllBytes($mj))
Write-Host ('len=' + $s.Length)
$r = $s.IndexOf('R$attr')
Write-Host ('Rattr=' + $r)