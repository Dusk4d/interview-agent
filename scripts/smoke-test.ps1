<#
.SYNOPSIS
  Smoke test against a RUNNING instance (e.g. after `dist\app.cmd`).

.DESCRIPTION
  Wrapper around com.dusk4d.interview.testkit.SmokeTestMain. The bean of truth for
  the assertions is that Java main (scripts\smoke-test.cmd launches the same class),
  because Windows PowerShell 5.1 cannot reliably parse non-ASCII .ps1 files.

  Checks performed: health, static frontend, resume import (pasted text),
  privacy masking, retrieval citations (evidence for questions), session creation,
  question grounding, four-dimension scoring, follow-up, question budget, finish,
  report generation, markdown download, and error-code mapping (400/404/409, no 500).

.PARAMETER BaseUrl
  Base URL of the running service, default http://127.0.0.1:8090

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1

.NOTES
  Keep this file ASCII-only.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8090'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    Write-Host '[smoke] compiling test sources ...' -ForegroundColor Cyan
    & mvn -o -s "$root\.m2settings.xml" -q "-DskipTests=true" test-compile
    if ($LASTEXITCODE -ne 0) { throw "mvn test-compile failed (exit=$LASTEXITCODE)" }

    Write-Host '[smoke] resolving classpath ...' -ForegroundColor Cyan
    $jars = & "$PSScriptRoot\resolve-classpath.ps1"
    $deps = ($jars | Where-Object { $_ -like '*\*.jar' }) -join ';'
    if (-not $deps) { throw 'dependency classpath is empty; check .m2repo' }

    Write-Host ("[smoke] running checks against {0}" -f $BaseUrl) -ForegroundColor Cyan
    # Quote the -D flags: passed bare, PowerShell splits "-Dfile.encoding=UTF-8"
    # at the dot and java receives ".encoding=UTF-8" instead.
    $javaArgs = @(
        '-Dfile.encoding=UTF-8'
        '-cp', "target\classes;target\test-classes;$deps"
        'com.dusk4d.interview.testkit.SmokeTestMain'
        "--base-url=$BaseUrl"
    )
    & java @javaArgs
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "smoke test reported failures (exit=$code)" }
    Write-Host 'smoke test passed' -ForegroundColor Green
}
finally {
    Pop-Location
}
