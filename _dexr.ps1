$dex = "F:\SublimationProject\sublimation for android\sdk-lite\work\dex\classes.dex"
$enc = [Text.Encoding]::GetEncoding("ISO-8859-1")
$s = $enc.GetString([IO.File]::ReadAllBytes($dex))
$pat = 'L([a-zA-Z0-9._/]+?)/R\$([a-zA-Z]+);'
$rex = New-Object Text.RegularExpressions.Regex($pat)
$found = @{}
foreach ($m in $rex.Matches($s)) { $found[$m.Groups[1].Value + "|" + $m.Groups[2].Value] = $true }
foreach ($k in ($found.Keys | Sort-Object)) { Write-Host ('RREF ' + $k) }