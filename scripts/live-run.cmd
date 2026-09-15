@echo off
rem ===========================================================================
rem  Live-model smoke/regression run against a real local model service.
rem
rem  Usage:  scripts\live-run.cmd [baseUrl] [model] [port]
rem  Defaults:
rem     baseUrl = http://127.0.0.1:11434/v1   (Ollama OpenAI-compatible endpoint)
rem     model   = qwen3:1.7b
rem     port    = 8097
rem
rem  Why a wrapper script: setting environment variables from a PowerShell parent
rem  process does not reliably reach the child JVM started by Start-Process here,
rem  and Windows quotes -D system properties inconsistently. A cmd wrapper sets the
rem  documented INTERVIEW_* variables in the same process that launches java.
rem
rem  Data goes to target\live-run so it never mixes with the demo dataset.
rem ===========================================================================
setlocal
set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%"

set "BASE_URL=%~1"
if "%BASE_URL%"=="" set "BASE_URL=http://127.0.0.1:11434/v1"
set "MODEL=%~2"
if "%MODEL%"=="" set "MODEL=qwen3:1.7b"
set "PORT=%~3"
if "%PORT%"=="" set "PORT=8097"

set "INTERVIEW_LLM_BASE_URL=%BASE_URL%"
set "INTERVIEW_LLM_CHAT_MODEL=%MODEL%"
set "INTERVIEW_LLM_MAX_TOKENS=1600"
set "INTERVIEW_LLM_READ_TIMEOUT_MS=120000"
set "INTERVIEW_DATA_DIR=%PROJECT_ROOT%\target\live-run"
set "INTERVIEW_STORAGE_MODE=file"

if not exist "%INTERVIEW_DATA_DIR%" mkdir "%INTERVIEW_DATA_DIR%"

echo [live] baseUrl = %INTERVIEW_LLM_BASE_URL%
echo [live] model   = %INTERVIEW_LLM_CHAT_MODEL%
echo [live] port    = %PORT%
echo [live] dataDir = %INTERVIEW_DATA_DIR%
echo [live] starting service ...
java -Dfile.encoding=UTF-8 -Dserver.port=%PORT% -jar "%PROJECT_ROOT%\dist\app.jar" > "%INTERVIEW_DATA_DIR%\out.log" 2> "%INTERVIEW_DATA_DIR%\err.log"

set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
