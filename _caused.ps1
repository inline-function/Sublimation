$t = Get-Content "F:/KotlinProject/sublimation/_build.log" -Raw
$i = $t.IndexOf("Caused by")
if ($i -lt 0) { $i = 0 }
$len = 700
if ($t.Length - $i -lt $len) { $len = $t.Length - $i }
Write-Host $t.Substring($i, $len)