Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead("F:/SublimationProject/sublimation for android/sdk-lite/out/app-debug.apk")
try {
  $names = $z.Entries | ForEach-Object { $_.FullName }
  Write-Host ("total=" + $names.Count)
  $names | Where-Object { $_ -like "assets/*" } | ForEach-Object { "A " + $_ }
  $names | Where-Object { $_ -like "*.dex" } | ForEach-Object { "D " + $_ }
  $names | Where-Object { $_ -like "AndroidManifest.xml" -or $_ -like "resources.arsc" } | ForEach-Object { "C " + $_ }
} finally { $z.Dispose() }