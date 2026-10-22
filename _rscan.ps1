$cls = "F:\SublimationProject\sublimation for android\sdk-lite\work\classes"
$files = @(Get-ChildItem $cls -Filter "*.jar")
Write-Host ("jars=" + $files.Count)
$pat = '([a-zA-Z0-9._/]+?)/R\$([a-zA-Z]+)'
$rex = New-Object Text.RegularExpressions.Regex($pat)
$found = @{}
foreach ($f in $files) {
  $b = [IO.File]::ReadAllBytes($f.FullName)
  $s = [Text.Encoding]::Latin1.GetString($b)
  foreach ($m in $rex.Matches($s)) { $found[$m.Groups[1].Value + "|" + $m.Groups[2].Value] = $true }
}
foreach ($k in ($found.Keys | Sort-Object)) { Write-Host ("RREF " + $k) }