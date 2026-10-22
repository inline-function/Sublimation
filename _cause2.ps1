$t = Get-Content "F:/KotlinProject/sublimation/_build.log" -Raw
$i = $t.LastIndexOf("Caused by")
if ($i -lt 0) { $i = 0 }
$len = 600
if ($t.Length - $i -lt $len) { $len = $t.Length - $i }
Write-Host $t.Substring($i, $len)