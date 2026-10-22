$base = "C:\Users\ZhuanZ\.gradle\caches\modules-2\files-2.1"
foreach ($g in @("org.jetbrains.intellij.deps", "net.java.dev.jna", "org.jetbrains.kotlin")) {
  $p = Join-Path $base $g
  if (Test-Path $p) { Get-ChildItem $p -Directory | ForEach-Object { $a = $_.Name; Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ($g + " : " + $a + " : " + $_.Name) } } }
}