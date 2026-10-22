Set-Location "F:\KotlinProject\sublimation"
git rm --cached _commit3.ps1 2>&1 | Out-Null
git add -A 2>&1 | Out-Null
git -c user.name="inline-function" -c user.email="inline-function@users.noreply.github.com" commit -m "清理：移除误提交的临时脚本" -q
git log --oneline -1
git push origin main 2>&1 | Select-String "main ->" | ForEach-Object { "PUSH " + $_.Line.Trim() }