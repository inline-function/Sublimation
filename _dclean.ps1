Set-Location "F:/KotlinProject\sublimation"
git add -A 2>&1 | Out-Null
git -c user.name="inline-function" -c user.email="inline-function@users.noreply.github.com" commit -m "清理：移除调试/验证用临时脚本" -q 2>&1 | Out-Null
Write-Host ('MAIN ' + (git log --oneline -1))
git push origin main 2>&1 | Select-String "main ->" | ForEach-Object { "PUSH " + $_.Line.Trim() }
Set-Location "F:/SublimationProject\sublimation for android"
Write-Host ('NEW ' + (git log --oneline -1))