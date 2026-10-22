$cache = "C:\Users\ZhuanZ\.gradle\caches\modules-2\files-2.1"
$script:seen = @{}
function Resolve($group, $name, $version) {
  $key = "${group}:${name}:${version}"
  if ($script:seen.ContainsKey($key)) { return }
  $script:seen[$key] = $true
  Write-Host ("DEP " + $key)
  $pomDir = Join-Path $cache (Join-Path $group (Join-Path $name $version))
  if (-not (Test-Path $pomDir)) { Write-Host ("  MISS " + $pomDir); return }
  $pom = Get-ChildItem $pomDir -Recurse -Filter "${name}-${version}.pom" | Select-Object -First 1
  if ($null -eq $pom) { Write-Host ("  NO-POM"); return }
  $xml = [xml](Get-Content $pom.FullName -Raw)
  $deps = $xml.project.dependencies.dependency
  if ($null -eq $deps) { return }
  foreach ($d in $deps) {
    $scope = $d.scope
    if ($null -ne $scope -and $scope -ne "compile" -and $scope -ne "runtime" -and $scope -ne "import") { continue }
    $dg = $d.groupId; $dn = $d.artifactId; $dv = $d.version
    if ($null -eq $dv -or $dv -eq "") { continue }
    if ($dn -like "kotlin-*" -or $dg -like "org.jetbrains.kotlin*") { continue }
    Resolve $dg $dn $dv
  }
}
Resolve "com.google.android.material" "material" "1.12.0"
Write-Host ("TOTAL=" + $script:seen.Count)