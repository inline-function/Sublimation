Set-Location "F:/KotlinProject/sublimation"
git reset _cleanup2.ps1 _gitsteps.ps1 _msg_a.txt
Remove-Item "_cleanup2.ps1", "_gitsteps.ps1", "_msg_a.txt" -ErrorAction SilentlyContinue
git add -A
git commit -F "F:/KotlinProject/sublimation/_msg_m.txt" 2>&1 | Select-Object -First 3
git status --short 2>&1 | Select-Object -First 5