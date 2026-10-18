@echo off
setlocal
rem ============================================================
rem  Sublimation v4 终版 - 解释器演示（脚本模式，独立目录）
rem  Self-contained: run.bat + *.subs 脚本 + core + sublimation.jar
rem  脚本模式：check + 解释器直接求值——顶层语句即程序，无需 main、不生成 JS
rem  脚本六例（彼此不共享）：bank / collections / geometry /
rem  nullable / stringmath / mathproof
rem  bank.subs 已省略全部类型/可变性/效应标注——演示全局类型推导
rem  （idea doc/全局类型推导.md：构造点 > 使用点 > 默认 Any 填充）
rem  Usage:
rem     run.bat          启动时询问是否开启全局类型推导，答 yes 开启（默认不开）
rem     run.bat bank     interpret one script only (name with or without .subs)
rem     run.bat np       same as default but no pause：非交互，默认开启推导（自动化用）
rem  Requires: java (JRE 17+).
rem ============================================================
cd /d "%~dp0"
set "JAR=%~dp0sublimation.jar"
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not exist "%JAR%" goto :nojar

rem ---- 全局类型推导闸门：交互询问；np（自动化）默认开启 ----
rem 注意：批处理括号块内 %VAR% 在解析期展开（取不到 set /p 的结果），这里用 goto 结构规避
set "GIFLAG="
set "GI="
if /i "%~1"=="np" goto :gi_auto
set /p "GI=Enable global type inference? (yes/no): "
set "GI=%GI: =%"
if /i "%GI%"=="yes" set "GIFLAG=--global-inference"
if /i "%GI%"=="y" set "GIFLAG=--global-inference"
goto :gi_done
:gi_auto
set "GIFLAG=--global-inference"
:gi_done

if "%~1"=="" goto :all
if /i "%~1"=="np" goto :all
goto :single
:all
echo.
echo [1/1] Interpreting all *.subs scripts in this dir ...
if defined GIFLAG (echo [flag] global inference: ON) else (echo [flag] global inference: OFF)
"%JAVA_CMD%" -jar "%JAR%" run-script . %GIFLAG% > run.log 2>&1
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
"%JAVA_CMD%" -jar "%JAR%" run-script "%SCRIPT%" %GIFLAG% > run.log 2>&1
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
