@echo off
setlocal
rem ============================================================
rem  Sublimation v4 终版 - 解释器演示（脚本模式，独立目录）
rem  Self-contained: run.bat + *.subs 脚本 + core\ + sublimation.jar
rem  脚本模式：check + 解释器直接求值——顶层语句即程序，无需 main、不生成 JS
rem  脚本六例（彼此不共享）：bank / collections / geometry /
rem  nullable / stringmath / mathproof
rem  Usage:
rem     run.bat          interpret all *.subs in this dir -> run.log (UTF-8)
rem     run.bat bank     interpret one script only (name with or without .subs)
rem     run.bat np       same as default but no pause (script use)
rem  Requires: java (JRE 17+).
rem ============================================================
cd /d "%~dp0"
set "JAR=%~dp0sublimation.jar"
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not exist "%JAR%" goto :nojar
if "%~1"=="" goto :all
if /i "%~1"=="np" goto :all
goto :single
:all
echo.
echo [1/1] Interpreting all *.subs scripts in this dir ...
"%JAVA_CMD%" -jar "%JAR%" run-script . > run.log 2>&1
if errorlevel 1 goto :fail
echo [1/1] interpret OK.
type run.log
echo.
echo [DONE] artifacts: run.log
if /i "%~1"=="np" exit /b 0
pause
exit /b 0
:single
set "SCRIPT=%~1"
if not exist "%SCRIPT%" set "SCRIPT=%~1.subs"
if not exist "%SCRIPT%" goto :noscript
echo.
echo [1/1] Interpreting %SCRIPT% ...
"%JAVA_CMD%" -jar "%JAR%" run-script "%SCRIPT%" > run.log 2>&1
if errorlevel 1 goto :fail
echo [1/1] interpret OK.
type run.log
echo.
echo [DONE] artifacts: run.log
pause
exit /b 0
:fail
echo.
echo [X] SCRIPT FAILED - open run.log for details
type run.log
if /i "%~1"=="np" exit /b 1
pause
exit /b 1
:noscript
echo.
echo [X] script not found: %~1 (expected %~1.subs in this dir)
pause
exit /b 1
:nojar
echo.
echo [X] jar not found next to this script: sublimation.jar
echo     rebuild it with:  gradlew fatJar  then copy build\libs\sublimation.jar here
pause
exit /b 1
