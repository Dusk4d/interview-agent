@echo off
rem ===========================================================================
rem  Smoke test against a RUNNING instance (e.g. dist\app.cmd already started).
rem
rem  Usage:  scripts\smoke-test.cmd [baseUrl]
rem  Default baseUrl: http://127.0.0.1:8090
rem
rem  Why a Java main instead of a PowerShell script: Windows PowerShell 5.1 reads
rem  .ps1 files as ANSI unless they carry a UTF-8 BOM, which breaks any script that
rem  contains non-ASCII text. The Java smoke runner has no such constraint and also
rem  asserts the exact JSON contract via Jackson.
rem
rem  Dependency classpath resolution walks the local repository (see
rem  scripts\resolve-classpath.ps1) instead of relying on maven-dependency-plugin,
rem  which is not available offline.
rem ===========================================================================
setlocal
set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%"

set "BASE_URL=%~1"
if "%BASE_URL%"=="" set "BASE_URL=http://127.0.0.1:8090"

echo [smoke] compiling test sources ...
call mvn -o -s "%PROJECT_ROOT%\.m2settings.xml" -q -DskipTests=true test-compile
if errorlevel 1 ( echo [smoke] test-compile failed & popd & exit /b 1)

echo [smoke] resolving classpath ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\resolve-classpath.ps1" >nul
if errorlevel 1 ( echo [smoke] classpath resolution failed & popd & exit /b 1)

set "ARGFILE=%PROJECT_ROOT%\target\test-classpath.args"
if not exist "%ARGFILE%" ( echo [smoke] %ARGFILE% missing & popd & exit /b 1)

rem Pass the classpath through a Java @argfile. Reading target\resolved-classpath.txt
rem into a variable does NOT work: "set /p" truncates the line at 1023 characters,
rem which silently drops most jars (symptom: NoClassDefFoundError for
rem com/fasterxml/jackson/databind/ObjectMapper while everything else looks fine).
rem Do not add -cp here as well: a later -cp would override the argfile's one.
echo [smoke] running checks against %BASE_URL%
java -Dfile.encoding=UTF-8 @"%ARGFILE%" com.dusk4d.interview.testkit.SmokeTestMain --base-url=%BASE_URL%
set "RC=%ERRORLEVEL%"

popd
exit /b %RC%
