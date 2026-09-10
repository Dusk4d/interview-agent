<#
.SYNOPSIS
  Compile and run the full automated test suite offline.

.DESCRIPTION
  Why not plain `mvn test`: the offline repository is missing surefire's provider
  dependency (junit-platform-launcher 1.11.4 jar) as well as maven-dependency-plugin
  and exec-maven-plugin, so Maven cannot run tests or compute a classpath here.
  Pipeline used instead:
    1) mvn -o test-compile                    - compile main + test sources
    2) scripts\resolve-classpath.ps1          - resolve jars from the local repo
    3) java com.dusk4d.interview.testkit.TestRunner - run tests via JUnit Platform API
  With network access you can use `scripts\mvn.cmd test -DskipTests=false` instead.

  NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads .ps1 as ANSI).

.PARAMETER Select
  Fully qualified test class names to run. Empty means "all tests".

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\run-tests.ps1
  powershell -ExecutionPolicy Bypass -File scripts\run-tests.ps1 -Select com.dusk4d.interview.parse.TextCleanerTest
#>
[CmdletBinding()]
param(
    [string[]]$Select = @()
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    Write-Host '[1/3] compiling main and test sources ...' -ForegroundColor Cyan
    & mvn -o -s "$root\.m2settings.xml" -q "-DskipTests=true" test-compile
    if ($LASTEXITCODE -ne 0) { throw "mvn test-compile failed (exit=$LASTEXITCODE)" }

    Write-Host '[2/3] resolving dependency classpath ...' -ForegroundColor Cyan
    $jars = & "$PSScriptRoot\resolve-classpath.ps1"
    $deps = ($jars | Where-Object { $_ -like '*\*.jar' }) -join ';'
    if (-not $deps) { throw 'dependency classpath is empty; check .m2repo' }
    $classpath = "target\classes;target\test-classes;$deps"

    Write-Host '[3/3] running tests ...' -ForegroundColor Cyan
    $javaArgs = @('-Dfile.encoding=UTF-8', '-Duser.language=en', '-Duser.country=US',
                  '-cp', $classpath, 'com.dusk4d.interview.testkit.TestRunner') + $Select
    $logFile = Join-Path $root 'target\test-output.txt'
    & java @javaArgs 2>&1 | Tee-Object -FilePath $logFile
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "tests failed (exit=$code); see $logFile" }
    Write-Host 'all automated tests passed' -ForegroundColor Green
}
finally {
    Pop-Location
}
