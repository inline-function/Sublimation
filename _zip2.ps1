Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead("F:/SublimationProject/sublimation for android/sdk-lite/out/app-debug.apk")
try {
  foreach ($n in @("assets/app.js", "assets/app.js.html", "classes.dex")) {
    $e = $z.GetEntry($n)
    if ($e) { Write-Host ($n + " zip-len=" + $e.Length) } else { Write-Host ($n + " MISSING") }
  }
} finally { $z.Dispose() }
foreach ($p in @("app.js", "app.js.html")) {
  $f = "F:/SublimationProject/sublimation for android/android-app/app/src/main/assets/" + $p
  Write-Host ($p + " file-len=" + (Get-Item $f).Length)
}