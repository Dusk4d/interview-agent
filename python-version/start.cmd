@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Python environment not found. Run setup.cmd first.
  exit /b 1
)
set MODE=%~1
if "%MODE%"=="" set MODE=mock
if not "%~2"=="" set INTERVIEW_PORT=%~2
if /I "%MODE%"=="mock" set INTERVIEW_LLM_PROVIDER=mock
if /I "%MODE%"=="ollama" (
  set INTERVIEW_LLM_PROVIDER=openai-compatible
  if "%INTERVIEW_LLM_BASE_URL%"=="" set INTERVIEW_LLM_BASE_URL=http://127.0.0.1:11434/v1
  if "%INTERVIEW_LLM_CHAT_MODEL%"=="" set INTERVIEW_LLM_CHAT_MODEL=qwen3:1.7b
)
if /I "%MODE%"=="lmstudio" (
  set INTERVIEW_LLM_PROVIDER=openai-compatible
  if "%INTERVIEW_LLM_BASE_URL%"=="" set INTERVIEW_LLM_BASE_URL=http://127.0.0.1:1234/v1
)
set PYTHONPATH=%CD%\python
".venv\Scripts\python.exe" -m interview_agent.main
