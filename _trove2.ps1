$root = "F:\SublimationProject\sublimation for android\sdk-lite"
$cache = "C:\Users\ZhuanZ\.gradle\caches\modules-2\files-2.1"
$t = Get-ChildItem (Join-Path $cache "org.jetbrains.intellij.deps\trove4j\1.0.20200330") -Recurse -Filter "*.jar" | Select-Object -First 1
Copy-Item $t.FullName (Join-Path $root "lib\trove4j.jar") -Force
Write-Host "trove-ok"