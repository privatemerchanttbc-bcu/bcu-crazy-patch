@echo off
REM ============================================================
REM  BCU Crazy Patch - INSTALLER
REM  Put this folder inside your BCU folder (next to BCU-x.jar),
REM  close BCU, then run this. Do it once, and again after each
REM  BCU update. A backup of the original jar is kept.
REM ============================================================

setlocal enabledelayedexpansion

set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"

set "INST=%HERE%\Crazy-BCU-Adventure-Installer.jar"
if not exist "%INST%" (
    echo [ERROR] Crazy-BCU-Adventure-Installer.jar is missing from this folder.
    pause
    exit /b 1
)

set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA set "JAVA=java"

echo Starting the Crazy Patch installer...
echo.
"%JAVA%" -jar "%INST%"
echo.
pause
endlocal
