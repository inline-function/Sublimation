$t = Get-Content "F:/KotlinProject/sublimation/_build.log" -Raw
$i = $t.IndexOf("[6/8]")
if ($i -lt 0) { $i = $t.Length - 500; if ($i -lt 0) { $i = 0 } }
$len = 900
if ($t.Length - $i -lt $len) { $len = $t.Length - $i }
Write-Host $t.Substring($i, $len)