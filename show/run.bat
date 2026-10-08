@echo off
setlocal
rem ============================================================
rem  Sublimation feature showcase builder (standalone jar)
rem  Self-contained: run.bat + show.subl + stdlib\ + sublimation.jar
rem  Usage:
rem    run.bat         compile this show dir -> show.js, run it,
rem                    compile+run output -> show.log (UTF-8)
rem    run.bat run     run existing show.js only
rem    run.bat np      same as default but no pause (script use)
rem  Requires: java (JRE 17+) and node.
rem ============================================================
cd /d "%~dp0"
set "JAR=%~dp0sublimation.jar"
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not exist "%JAR%" goto :nojar
if /i "%~1"=="run" goto :runonly
echo.
echo [1/3] Compiling this show dir (show.subl + stdlib) ...
if exist show.js del show.js
"%JAVA_CMD%" -jar "%JAR%" compile . -o show.js > show.log 2>&1
if errorlevel 1 goto :compilefail
echo [1/3] compile OK.
echo [2/3] Running show.js ...
call node show.js >> show.log 2>&1
echo [2/3] run OK - result appended to show.log.
echo.
echo [DONE] artifacts: show.js + show.log
if /i "%~1"=="np" exit /b 0
pause
exit /b 0
:compilefail
echo.
echo [X] COMPILE FAILED - open show.log (Notepad) for details
if /i "%~1"=="np" exit /b 1
pause
exit /b 1
:nojar
echo.
echo [X] compiler jar not found next to this script: sublimation.jar
echo     rebuild it with:  gradlew fatJar  then copy build\libs\sublimation.jar here
pause
exit /b 1
:runonly
if not exist show.js goto :nojs
echo Running existing show.js ...
call node show.js >> show.log 2>&1
echo run OK - result appended to show.log
if /i "%~1"=="np" exit /b 0
pause
exit /b 0
:nojs
echo [X] show.js not found - run without args to build first
pause
exit /b 1