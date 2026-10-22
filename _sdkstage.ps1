$root = "F:\SublimationProject\sublimation for android\sdk-lite"
New-Item -ItemType Directory -Force -Path "$root\bin" | Out-Null
New-Item -ItemType Directory -Force -Path "$root\lib" | Out-Null
New-Item -ItemType Directory -Force -Path "$root\keystore" | Out-Null
New-Item -ItemType Directory -Force -Path "$root\work" | Out-Null
New-Item -ItemType Directory -Force -Path "$root\out" | Out-Null
Copy-Item "F:\AndroidSDK\build-tools\35.0.0\aapt2.exe" "$root\bin\aapt2.exe" -Force
Copy-Item "F:\AndroidSDK\build-tools\35.0.0\zipalign.exe" "$root\bin\zipalign.exe" -Force
Copy-Item "F:\AndroidSDK\build-tools\35.0.0\lib\d8.jar" "$root\lib\d8.jar" -Force
Copy-Item "F:\AndroidSDK\build-tools\35.0.0\lib\apksigner.jar" "$root\lib\apksigner.jar" -Force
Copy-Item "F:\AndroidSDK\platforms\android-35\android.jar" "$root\lib\android.jar" -Force
$kc = Get-ChildItem "C:\Users\ZhuanZ\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-compiler-embeddable\1.9.24" -Recurse -Filter "*.jar" | Select-Object -First 1
Copy-Item $kc.FullName "$root\lib\kotlin-compiler.jar" -Force
$std = Get-ChildItem "$root\libs" -Filter "org_jetbrains_kotlin__kotlin-stdlib-1.9.24.jar" | Select-Object -First 1
Copy-Item $std.FullName "$root\lib\kotlin-stdlib.jar" -Force
keytool -genkey -v -keystore "$root\keystore\debug.keystore" -storepass android -keypass android -alias candydebug -dname "CN=CandyIDE Debug,O=CandyIDE,C=CN" -keyalg RSA -keysize 2048 -validity 36500 2>&1 | Out-Null
Write-Host staged-ok