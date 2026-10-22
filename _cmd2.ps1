Set-Location "F:\KotlinProject\sublimation"
git add -A 2>&1 | Out-Null
git -c user.name="inline-function" -c user.email="inline-function@users.noreply.github.com" commit -m "清理：移除误提交的临时脚本（补）" -q 2>&1 | Out-Null
git log --oneline -2
git push origin main 2>&1 | Select-String "main ->" | ForEach-Object { "PUSH " + $_.Line.Trim() }