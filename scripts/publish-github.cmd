@echo off
rem ===========================================================================
rem  Publish this repository to GitHub under the Dusk4d account.
rem
rem  Usage:
rem     scripts\publish-github.cmd [repoName]        (default: interview-agent)
rem
rem  Why this script exists: the development machine had no outbound network
rem  access, so the repository was prepared locally (3 commits on `main`) but
rem  could not be pushed. Run this where GitHub is reachable.
rem
rem  Prerequisites (choose one):
rem     - gh auth login                 (recommended; needs GitHub CLI)
rem     - git config credential.helper  (or a PAT when prompted for password)
rem
rem  NOTE: repository visibility defaults to public. Pass --private to change.
rem ===========================================================================
setlocal
set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%"

set "REPO_NAME=%~1"
if "%REPO_NAME%"=="" set "REPO_NAME=interview-agent"

set "GH_USER=Dusk4d"

echo [1/4] checking git state ...
git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 ( echo [publish] not a git repository & popd & exit /b 1 )
git status --porcelain | findstr /r "." >nul 2>&1
if not errorlevel 1 (
  echo [publish] working tree has uncommitted changes; commit them first.
  git status --short
  popd
  exit /b 1
)

echo [2/4] ensuring branch 'main' ...
git symbolic-ref -q HEAD >nul 2>&1
if errorlevel 1 ( echo [publish] detached HEAD; checkout a branch first & popd & exit /b 1 )
git branch --show-current | findstr /b "main" >nul 2>&1
if errorlevel 1 git branch -M main

echo [3/4] creating the remote repository ...
where gh >nul 2>&1
if errorlevel 1 (
  echo [publish] GitHub CLI not found. Create the repo manually at
  echo           https://github.com/new  with the name "%REPO_NAME%", then run:
  echo           git remote add origin https://github.com/%GH_USER%/%REPO_NAME%.git
  echo           git push -u origin main
  popd
  exit /b 1
)
gh repo view %GH_USER%/%REPO_NAME% >nul 2>&1
if errorlevel 1 (
  gh repo create %GH_USER%/%REPO_NAME% --public --source=. --remote=origin --description "Resume-driven AI interview coach: RAG retrieval, agent state machine, rubric-based answer evaluation, Markdown review report" --push
  if errorlevel 1 ( echo [publish] gh repo create failed & popd & exit /b 1 )
  echo [publish] repository created and pushed.
  popd
  exit /b 0
)

echo [4/4] pushing to existing remote ...
git remote get-url origin >nul 2>&1
if errorlevel 1 git remote add origin https://github.com/%GH_USER%/%REPO_NAME%.git
git push -u origin main
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" ( echo [publish] done: https://github.com/%GH_USER%/%REPO_NAME% ) else ( echo [publish] push failed with exit %RC% )
popd
exit /b %RC%
