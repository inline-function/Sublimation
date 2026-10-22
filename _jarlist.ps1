Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead("F:\SublimationProject\sublimation for android\sdk-lite\work\appclasses.jar")
try {
  $names = @($z.Entries | ForEach-Object { $_.FullName })
  Write-Host ('total=' + $names.Count)
  $names | ForEach-Object { Write-Host ('E ' + $_) }
} finally { $z.Dispose() }