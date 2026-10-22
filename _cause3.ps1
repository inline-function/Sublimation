$t = Get-Content "F:/KotlinProject/sublimation/_build.log" -Raw
if ($null -eq $t) { Write-Host "EMPTYLOG" } else {
  $i = $t.IndexOf("[1/8]")
  if ($i -lt 0) { $i = 0 }
  $len = 1600
  if ($t.Length - $i -lt $len) { $len = $t.Length - $i }
  Write-Host $t.Substring($i, $len)
}