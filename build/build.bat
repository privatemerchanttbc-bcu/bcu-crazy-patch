@echo off
REM ============================================================
REM  Builds manual-control-patch.jar, the BCU Crazy Patch agent.
REM
REM  Usage:   build.bat  [path\to\BCU-x-x-x-x.jar]
REM
REM  You need a JDK 17 or newer and a copy of the BCU jar. The BCU
REM  jar is a compile reference only - nothing from it is copied
REM  into the output.
REM ============================================================

setlocal enabledelayedexpansion

set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"
set "ROOT=%HERE%\.."

set "SRC=%ROOT%\src"
set "OUT=%ROOT%\out"
set "LIB=%ROOT%\lib"
set "MANIFEST=%HERE%\MANIFEST.MF"
set "OUTPUT=%ROOT%\manual-control-patch.jar"

REM --- locate a JDK -------------------------------------------
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
    set "JAVA=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVAC (
    where javac >nul 2>&1
    if not errorlevel 1 (
        set "JAVAC=javac"
        set "JAR=jar"
        set "JAVA=java"
    )
)
if not defined JAVAC for /d %%d in ("%ROOT%\jdk*") do (
    if exist "%%~fd\bin\javac.exe" (
        set "JAVAC=%%~fd\bin\javac.exe"
        set "JAR=%%~fd\bin\jar.exe"
        set "JAVA=%%~fd\bin\java.exe"
    )
)
if not defined JAVAC (
    echo [build] ERROR: no JDK found.
    echo [build] Install a JDK 17 or newer, set JAVA_HOME to point at one,
    echo [build] or unpack a JDK into a "jdk" folder inside this repo.
    echo [build] A plain JRE is not enough - the compiler is required.
    exit /b 1
)

REM --- locate the BCU jar -------------------------------------
set "BCU_JAR=%~1"
if not defined BCU_JAR if defined BCU_JAR_ENV set "BCU_JAR=%BCU_JAR_ENV%"
if not defined BCU_JAR for %%f in ("%ROOT%\bcu\BCU*.jar") do set "BCU_JAR=%%~ff"
if not defined BCU_JAR for %%f in ("%ROOT%\..\BCU-Bugfix*.jar") do set "BCU_JAR=%%~ff"
if not defined BCU_JAR for %%f in ("%ROOT%\..\BCU-0*.jar") do set "BCU_JAR=%%~ff"
if not defined BCU_JAR (
    echo [build] ERROR: BCU jar not found.
    echo [build] Pass it as an argument:
    echo [build]     build\build.bat "C:\path\to\BCU-0-5-8-8.jar"
    echo [build] or drop a copy into a "bcu" folder next to this repo.
    echo [build] It is only a compile reference and is never redistributed.
    exit /b 1
)
echo [build] BCU reference: !BCU_JAR!

set "CP=!BCU_JAR!"
for %%d in ("!BCU_JAR!") do set "BCU_HOME=%%~dpd"
if exist "!BCU_HOME!BCU_lib" (
    for %%f in ("!BCU_HOME!BCU_lib\*.jar") do set "CP=!CP!;%%~ff"
) else (
    echo [build] NOTE: no BCU_lib folder next to the BCU jar.
    echo [build] BCU ships its libraries there, and some sources compile against them.
    echo [build] Point this script at the jar inside your real BCU folder if the build fails.
)
for %%f in ("%LIB%\*.jar") do set "CP=!CP!;%%~ff"

echo [build] Checking source charset...
"%JAVA%" "%HERE%\CharsetGate.java" "%SRC%"
if errorlevel 1 (
    echo [build] ERROR: charset gate failed
    exit /b 1
)

echo [build] Cleaning...
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo [build] Listing sources...
set "SRCLIST=%OUT%\sources.txt"
for /r "%SRC%" %%f in (*.java) do (
    set "P=%%~ff"
    echo "!P:\=/!">>"%SRCLIST%"
)
for /f %%c in ('type "%SRCLIST%" ^| find /c /v ""') do echo [build] Source files: %%c

echo [build] Compiling for Java 8...
"%JAVAC%" --release 8 -encoding UTF-8 -cp "%CP%" -d "%OUT%" "@%SRCLIST%"
if errorlevel 1 (
    echo [build] ERROR: compilation failed
    exit /b 1
)

if exist "%ROOT%\resources" (
    echo [build] Copying resources...
    xcopy "%ROOT%\resources\*" "%OUT%\" /e /i /y >nul
)

echo [build] Bundling ASM...
pushd "%OUT%"
for %%f in ("%LIB%\*.jar") do "%JAR%" xf "%%~ff"
if exist "META-INF" rmdir /s /q "META-INF"
if exist "module-info.class" del /f /q "module-info.class"
if exist "sources.txt" del /f /q "sources.txt"
popd

echo [build] Packaging...
"%JAR%" cfm "%OUTPUT%" "%MANIFEST%" -C "%OUT%" .
if errorlevel 1 (
    echo [build] ERROR: packaging failed
    exit /b 1
)

echo.
echo [build] SUCCESS: %OUTPUT%
for %%i in ("%OUTPUT%") do echo [build] Size: %%~zi bytes
echo.
endlocal
