@echo off
REM ============================================================
REM  Builds the shareable Crazy Patch installer into
REM  download\BCU Crazy\.
REM
REM  Run build\build.bat first, or pass the BCU jar as an
REM  argument and this script will build the agent itself.
REM ============================================================

setlocal enabledelayedexpansion

set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"
set "ROOT=%HERE%\.."

set "SRC=%ROOT%\src"
set "LIB=%ROOT%\lib"
set "SHIP=%ROOT%\download\BCU Crazy"
set "BUILD=%ROOT%\installer-build"
set "AGENT=%ROOT%\manual-control-patch.jar"
set "PAYLOAD=%SHIP%\installer_payload"
set "OUTPUT=%SHIP%\Crazy-BCU-Adventure-Installer.jar"
set "MAINCLASS=manualcontrol.install.CrazyPatchInstaller"
set "PACK=%ROOT%\packs\BCU Crazy - bcucrazy.pack.bcuzip"

set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
)
if not defined JAVAC (
    where javac >nul 2>&1
    if not errorlevel 1 (
        set "JAVAC=javac"
        set "JAR=jar"
    )
)
if not defined JAVAC (
    echo [installer] ERROR: no JDK found. Set JAVA_HOME to a JDK 17 or newer.
    exit /b 1
)

if not exist "%AGENT%" (
    echo [installer] Agent jar not found, building it first...
    call "%HERE%\build.bat" %*
    if errorlevel 1 exit /b 1
)

set "BCU_JAR=%~1"
if not defined BCU_JAR for %%f in ("%ROOT%\bcu\BCU*.jar") do set "BCU_JAR=%%~ff"
if not defined BCU_JAR for %%f in ("%ROOT%\..\BCU-0*.jar") do set "BCU_JAR=%%~ff"
if not defined BCU_JAR (
    echo [installer] ERROR: BCU jar not found; pass it as an argument.
    exit /b 1
)

echo [installer] Cleaning...
if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%"

echo [installer] Compiling the standalone installer...
"%JAVAC%" --release 8 -encoding UTF-8 -cp "!BCU_JAR!" -d "%BUILD%" "%SRC%\manualcontrol\install\CrazyPatchInstaller.java"
if errorlevel 1 (
    echo [installer] ERROR: compilation failed
    exit /b 1
)

echo [installer] Staging the payload...
mkdir "%BUILD%\installer_payload"
copy /y "%AGENT%" "%BUILD%\installer_payload\manual-control-patch.jar" >nul
if exist "%PACK%" (
    mkdir "%BUILD%\installer_payload\packs"
    copy /y "%PACK%" "%BUILD%\installer_payload\packs\BCU Crazy - bcucrazy.pack.bcuzip" >nul
) else (
    echo [installer] WARNING: pack not found: %PACK%
)

echo [installer] Packaging...
if not exist "%SHIP%" mkdir "%SHIP%"
"%JAR%" cfe "%OUTPUT%" "%MAINCLASS%" -C "%BUILD%" .
if errorlevel 1 (
    echo [installer] ERROR: packaging failed
    exit /b 1
)

echo [installer] Publishing the payload beside the installer...
if exist "%PAYLOAD%" rmdir /s /q "%PAYLOAD%"
mkdir "%PAYLOAD%"
copy /y "%AGENT%" "%PAYLOAD%\manual-control-patch.jar" >nul
if exist "%PACK%" (
    mkdir "%PAYLOAD%\packs"
    copy /y "%PACK%" "%PAYLOAD%\packs\BCU Crazy - bcucrazy.pack.bcuzip" >nul
)

rmdir /s /q "%BUILD%"

echo.
echo [installer] SUCCESS: %OUTPUT%
for %%i in ("%OUTPUT%") do echo [installer] Size: %%~zi bytes
echo.
endlocal
