Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead("F:\SublimationProject\sublimation for android\sdk-lite\work\appclasses.jar")
try {
  $n = @($z.Entries | ForEach-Object { $_.FullName })
  Write-Host ('total=' + $n.Count)
  $n | Where-Object { $_ -like '*material*' } | ForEach-Object { Write-Host ('M ' + $_) }
} finally { $z.Dispose() }