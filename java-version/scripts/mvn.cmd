@echo off
rem Deterministic offline Maven wrapper for this project.
rem Usage: scripts\mvn.cmd clean test
rem Notes:
rem   -o                    : offline; never touches the network
rem   -s .m2settings.xml    : project-local repository mirror (.m2repo)
rem   -Duser.language=en    : keep compiler/test output in English (readable in any console)
setlocal
set "PROJECT_ROOT=%~dp0.."
pushd "%PROJECT_ROOT%"
call mvn -o -s "%PROJECT_ROOT%\.m2settings.xml" -Duser.language=en -Duser.country=US %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
