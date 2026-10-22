$cache = "C:\Users\ZhuanZ\.gradle\caches\modules-2\files-2.1"
$dest = "F:\SublimationProject\sublimation for android\sdk-lite\libs"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$ok = 0; $miss = 0
$d = "androidx.activity:activity:1.8.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.annotation:annotation:1.8.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.annotation:annotation-experimental:1.4.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.annotation:annotation-jvm:1.8.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.appcompat:appcompat:1.7.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.appcompat:appcompat-resources:1.7.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.arch.core:core-common:2.2.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.arch.core:core-runtime:2.2.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.cardview:cardview:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.collection:collection:1.4.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.collection:collection-jvm:1.4.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.concurrent:concurrent-futures:1.1.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.constraintlayout:constraintlayout:2.0.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.constraintlayout:constraintlayout-solver:2.0.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.coordinatorlayout:coordinatorlayout:1.1.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.core:core:1.15.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.core:core-ktx:1.15.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.cursoradapter:cursoradapter:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.customview:customview:1.1.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.documentfile:documentfile:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.drawerlayout:drawerlayout:1.1.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.dynamicanimation:dynamicanimation:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.emoji2:emoji2:1.3.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.emoji2:emoji2-views-helper:1.3.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.fragment:fragment:1.5.4"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.interpolator:interpolator:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.legacy:legacy-support-core-utils:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.lifecycle:lifecycle-common:2.6.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.lifecycle:lifecycle-livedata:2.6.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.lifecycle:lifecycle-livedata-core:2.6.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.lifecycle:lifecycle-process:2.6.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.lifecycle:lifecycle-runtime:2.6.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.lifecycle:lifecycle-viewmodel:2.6.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.loader:loader:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.localbroadcastmanager:localbroadcastmanager:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.print:print:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.profileinstaller:profileinstaller:1.3.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.recyclerview:recyclerview:1.1.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.resourceinspection:resourceinspection-annotation:1.0.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.savedstate:savedstate:1.2.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.startup:startup-runtime:1.1.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.tracing:tracing:1.2.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.transition:transition:1.5.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.vectordrawable:vectordrawable:1.1.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.vectordrawable:vectordrawable-animated:1.1.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.versionedparcelable:versionedparcelable:1.1.1"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.viewpager2:viewpager2:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "androidx.viewpager:viewpager:1.0.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "com.google.android.material:material:1.12.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "com.google.errorprone:error_prone_annotations:2.15.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "com.google.guava:listenablefuture:1.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains.kotlin:kotlin-stdlib:1.9.24"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains.kotlin:kotlin-stdlib-common:1.9.24"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
$d = "org.jetbrains:annotations:13.0"
$parts = $d.Split(":")
$dir = Join-Path $cache (Join-Path $parts[0] (Join-Path $parts[1] $parts[2]))
$f = Get-ChildItem $dir -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like ($parts[1] + "-" + $parts[2] + ".*") -and ($_.Extension -eq ".aar" -or $_.Extension -eq ".jar") } | Select-Object -First 1
if ($f) { Copy-Item $f.FullName (Join-Path $dest ($parts[0].Replace(".", "_") + "__" + $f.Name)) -Force; $ok++ } else { Write-Host ("MISS " + $d); $miss++ }
Write-Host ("copied=" + $ok + " miss=" + $miss)