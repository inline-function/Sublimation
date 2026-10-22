$dex = "F:\SublimationProject\sublimation for android\sdk-lite\work\dex\classes.dex"
Write-Host ("dexexists=" + (Test-Path $dex))
if (Test-Path $dex) {
  $enc = [Text.Encoding]::GetEncoding("ISO-8859-1")
  $s = $enc.GetString([IO.File]::ReadAllBytes($dex))
  Write-Host ('len=' + $s.Length)
  $r1 = $s.IndexOf('com/google/android/material/R')
  Write-Host ('materialR=' + $r1)
  if ($r1 -gt 0) { Write-Host $s.Substring($r1, 160) }
}