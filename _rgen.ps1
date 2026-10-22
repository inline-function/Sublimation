$rNeed = @{
  'androidx.activity' = @('id')
  'androidx.appcompat.resources' = @('drawable', 'styleable')
  'androidx.appcompat' = @('attr', 'bool', 'color', 'dimen', 'drawable', 'id', 'layout', 'string', 'style', 'styleable')
  'androidx.cardview' = @('attr', 'color', 'dimen', 'style', 'styleable')
  'androidx.constraintlayout.widget' = @('id', 'styleable')
  'androidx.coordinatorlayout' = @('attr', 'style', 'styleable')
  'androidx.core' = @('attr', 'color', 'dimen', 'drawable', 'id', 'integer', 'layout', 'string', 'styleable')
  'androidx.drawerlayout' = @('attr', 'dimen', 'styleable')
  'androidx.fragment' = @('animator', 'id', 'styleable')
  'androidx.lifecycle.runtime' = @('id')
  'androidx.lifecycle.viewmodel' = @('id')
  'androidx.recyclerview' = @('attr', 'dimen', 'id', 'styleable')
  'androidx.savedstate' = @('id')
  'androidx.startup' = @('string')
  'androidx.transition' = @('id')
  'androidx.viewpager2' = @('styleable')
  'com.google.android.material' = @('anim', 'animator', 'attr', 'color', 'dimen', 'drawable', 'id', 'integer', 'layout', 'plurals', 'string', 'style', 'styleable')
}
$rgenDir = Join-Path $work "rgen"
New-Item -ItemType Directory -Force -Path $rgenDir | Out-Null
$rjava = Get-ChildItem $rgenDir -Recurse -Filter "R.java" | Select-Object -First 1
if ($null -eq $rjava) { throw "aapt2 未生成 R.java（--java 缺失）" }
$typeData = @{}
$cur = ""
foreach ($line in (Get-Content $rjava.FullName)) {
  $tm = [regex]::Match($line, 'public static final class (\w+)')
  if ($tm.Success) { $cur = $tm.Groups[1].Value; continue }
  if (-not $rNeed.Keys) { }
  $sm = [regex]::Match($line, 'public static final int (\w+) = (0x[0-9a-fA-F]+|\d+);')
  if ($sm.Success -and $cur) {
    if (-not $typeData.ContainsKey($cur)) { $typeData[$cur] = @() }
    $typeData[$cur] += [pscustomobject]@{ name = $sm.Groups[1].Value; value = $sm.Groups[2].Value; arr = $false }
    continue
  }
  $am = [regex]::Match($line, 'public static final int\[\] (\w+) = \{([^}]*)\};')
  if ($am.Success -and $cur) {
    if (-not $typeData.ContainsKey($cur)) { $typeData[$cur] = @() }
    $vals = ($am.Groups[2].Value -split ',') | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }
    $typeData[$cur] += [pscustomobject]@{ name = $am.Groups[1].Value; value = ($vals -join ','); arr = $true }
  }
}
$rsrcDir = Join-Path $work "rsrc"
New-Item -ItemType Directory -Force -Path $rsrcDir | Out-Null
$rFiles = @()
foreach ($pkg in $rNeed.Keys) {
  $parts = $pkg.Split(".")
  $pkgDir = $rsrcDir
  foreach ($p in $parts) { $pkgDir = Join-Path $pkgDir $p }
  New-Item -ItemType Directory -Force -Path $pkgDir | Out-Null
  $sb = New-Object Text.StringBuilder
  [void]$sb.AppendLine("package " + $pkg)
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("class R {")
  foreach ($tp in $rNeed[$pkg]) {
    if (-not $typeData.ContainsKey($tp)) { continue }
    [void]$sb.AppendLine("  class " + $tp + " {")
    [void]$sb.AppendLine("    companion object {")
    foreach ($e in $typeData[$tp]) {
      if ($e.arr) {
        [void]$sb.AppendLine("      @JvmField val " + $e.name + " = intArrayOf(" + $e.value + ")")
      } else {
        [void]$sb.AppendLine("      const val " + $e.name + " = " + $e.value)
      }
    }
    [void]$sb.AppendLine("    }")
    [void]$sb.AppendLine("  }")
  }
  [void]$sb.AppendLine("}")
  $rf = Join-Path $pkgDir "R.kt"
  [IO.File]::WriteAllText($rf, $sb.ToString(), (New-Object Text.UTF8Encoding $false))
  $rFiles += $rf
}
Write-Host ("R-classes=" + $rFiles.Count)