@echo off
rem ===========================================================================
rem  Start the service for hands-on testing.
rem
rem  Usage:  scripts\start.cmd [mode] [port]
rem     mode: mock  = no model needed, instant template questions + heuristic scoring
rem           ollama= use Ollama  (default http://127.0.0.1:11434/v1, model qwen3:1.7b)
rem           lmstudio = use LM Studio (default http://127.0.0.1:1234/v1)
rem           live  = same as ollama
rem     port: default 8090
rem
rem  Examples:
rem     scripts\start.cmd                 (mock mode on 8090)
rem     scripts\start.cmd mock            (fastest way to click through the UI)
rem     scripts\start.cmd ollama          (real model via Ollama)
rem     scripts\start.cmd lmstudio 9000   (real model via LM Studio on port 9000)
rem
rem  This runs in the FOREGROUND so you can watch the logs; press Ctrl+C to stop.
rem
rem  NOTE: keep this file ASCII-only. cmd.exe reads .cmd as ANSI/OEM, so non-ASCII
rem  text corrupts the script.
rem ===========================================================================
setlocal
set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=mock"
set "PORT=%~2"
if "%PORT%"=="" set "PORT=8090"

rem Respect a data directory chosen by the caller, otherwise default to <root>\data.
rem (TESTING.md documents INTERVIEW_DATA_DIR as overridable; setting it unconditionally
rem here would silently ignore the caller's value.)
if not defined INTERVIEW_DATA_DIR set "INTERVIEW_DATA_DIR=%PROJECT_ROOT%\data"
if not exist "%INTERVIEW_DATA_DIR%" mkdir "%INTERVIEW_DATA_DIR%"

if /i "%MODE%"=="mock" goto mock
if /i "%MODE%"=="ollama" goto ollama
if /i "%MODE%"=="live" goto ollama
if /i "%MODE%"=="lmstudio" goto lmstudio
echo [start] unknown mode "%MODE%". Use: mock ^| ollama ^| lmstudio
popd
exit /b 1

:mock
echo [start] mode=mock (no model needed: template questions + heuristic scoring)
set "INTERVIEW_LLM_PROVIDER=mock"
set "INTERVIEW_EMBEDDING_MODE=local"
goto run

:ollama
echo [start] mode=ollama  (expects Ollama on 127.0.0.1:11434 with model qwen3:1.7b)
set "INTERVIEW_LLM_BASE_URL=http://127.0.0.1:11434/v1"
set "INTERVIEW_LLM_CHAT_MODEL=qwen3:1.7b"
set "INTERVIEW_EMBEDDING_MODE=local"
goto run

:lmstudio
echo [start] mode=lmstudio (expects LM Studio server on 127.0.0.1:1234 with a loaded model)
echo [start] if unsure which model is loaded, run:  lms ps
set "INTERVIEW_LLM_BASE_URL=http://127.0.0.1:1234/v1"
set "INTERVIEW_EMBEDDING_MODE=local"
goto run

:run
echo.
echo [start] open this in your browser:  http://127.0.0.1:%PORT%/
echo [start] data directory: %INTERVIEW_DATA_DIR%
echo [start] press Ctrl+C to stop
echo.
java -Dfile.encoding=UTF-8 -Dserver.port=%PORT% -jar "%PROJECT_ROOT%\dist\app.jar"

set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
