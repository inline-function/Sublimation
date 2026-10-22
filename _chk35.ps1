$t = Get-Content "F:/KotlinProject/sublimation/_build.log" -Raw
$i = $t.IndexOf("3.5/8")
if ($i -lt 0) { Write-Host "NO-35-STEP" } else {
  $len = 300
  if ($t.Length - $i -lt $len) { $len = $t.Length - $i }
  Write-Host $t.Substring($i, $len)
}