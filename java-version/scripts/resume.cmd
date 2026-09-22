@echo off
rem ===========================================================================
rem  Session resume helper. Run this first when continuing development.
rem
rem  Usage: scripts\resume.cmd
rem
rem  It does four things:
rem    1) print git status and the last few commits (confirm previous fixes landed)
rem    2) recompile and run the full automated test suite (confirm the baseline is green)
rem    3) detect the local model service (Ollama 11434 / LM Studio 1234)
rem    4) print the remaining TODO list
rem
rem  Automated tests run fully offline and do NOT need a model service; only the
rem  live-model regression does.
rem
rem  NOTE: keep this file ASCII-only. cmd.exe reads .cmd/.bat as ANSI (the OEM code
rem  page) unless the file has a special encoding, so non-ASCII text corrupts the
rem  script and produces "not recognized as an internal or external command".
rem ===========================================================================
setlocal
set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%"

echo ============================================================
echo  1/4  git status
echo ============================================================
git log --oneline -4
echo.
echo Uncommitted changes (should be empty):
git status --short
echo.

echo ============================================================
echo  2/4  running automated tests
echo ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\run-tests.ps1"
set "TEST_RC=%ERRORLEVEL%"
echo.

echo ============================================================
echo  3/4  local model service detection
echo ============================================================
powershell -NoProfile -Command "$o = Test-NetConnection -ComputerName 127.0.0.1 -Port 11434 -InformationLevel Quiet -WarningAction SilentlyContinue; $l = Test-NetConnection -ComputerName 127.0.0.1 -Port 1234 -InformationLevel Quiet -WarningAction SilentlyContinue; Write-Host ('  Ollama    11434 : ' + $o); Write-Host ('  LM Studio  1234 : ' + $l); if (-not $o -and -not $l) { Write-Host '  [hint] no model service detected.'; Write-Host '         Ollama   : set OLLAMA_MODELS=D:\Ollama_models ^&^& start D:\Ollama\ollama.exe serve'; Write-Host '         LM Studio: start the app and enable the server in the Developer tab'; Write-Host '         Mock mode: dist\app.cmd --app.llm.provider=mock  (no model needed)' }"
echo.

echo ============================================================
echo  4/4  remaining TODO
echo ============================================================
echo  [ ] remove temporary probe fields from /api/diagnostics/config
echo      (keep llmBaseUrl / thinkingDisabled; drop bound.probeError, contextCount, propertySources)
echo  [ ] write the live-model measurements into docs\VERIFICATION.md, replacing "to be measured"
echo  [ ] improve scoring consistency (same answer scored repeatedly: stddev 0.3-0.7, too high)
echo  [ ] commit, then run scripts\publish-github.cmd when GitHub is reachable
echo.

if not "%TEST_RC%"=="0" (
  echo [WARN] automated tests failed with exit=%TEST_RC% -- fix before continuing.
  popd
  exit /b %TEST_RC%
)
echo Environment ready, baseline tests passed.
popd
exit /b 0
