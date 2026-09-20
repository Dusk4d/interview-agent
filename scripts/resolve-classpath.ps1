<#
.SYNOPSIS
  Resolve Maven dependencies offline and build the Java classpath.

.DESCRIPTION
  This machine has no internet access, and the local repository is missing several
  plugin jars (maven-dependency-plugin, exec-maven-plugin, surefire provider).
  Therefore the classpath is resolved directly from the POM dependency tree:
    1) walk compile/runtime dependencies recursively starting from the project POM;
    2) prefer the declared version; when the version is managed by a parent/BOM,
       fall back to the version actually present in the local repository
       (releases before pre-releases, newest first);
    3) keep exactly one version per artifact to avoid classpath conflicts.

  Some artifact families must stay on one line, otherwise the newest cached jar is
  pulled in and breaks binary compatibility with spring-boot 3.4.4, e.g.
  junit-jupiter 6.x requires junit-platform 6.x, and jackson-annotations 3.x /
  jackson-core 2.20 no longer match jackson-databind 2.18. PREFERRED_PINS below
  locks those families to the versions declared in pom.xml.

  Result is written to target/resolved-classpath.txt and returned to the caller.
  An equivalent Java @argfile is written to target/test-classpath.args, because
  cmd.exe cannot carry this classpath in an environment variable: "set /p" truncates
  the line at 1023 characters, so a .cmd launcher that reads the .txt file silently
  loses most of the classpath (this used to break scripts\smoke-test.cmd with
  NoClassDefFoundError: com/fasterxml/jackson/databind/ObjectMapper).

  NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 files as ANSI
  unless they carry a UTF-8 BOM, so non-ASCII text breaks parsing.
#>
[CmdletBinding()]
param(
    [string]$RepoPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $RepoPath) { $RepoPath = Join-Path $root '.m2repo' }
$RepoPath = (Resolve-Path $RepoPath).Path

$script:repo = $RepoPath
$script:pomCache = @{}
$script:jarCache = @{}
$script:chosen = [ordered]@{}
$script:visiting = New-Object 'System.Collections.Generic.HashSet[string]'

# Artifact families that must stay on one version line (group, artifact, version).
# Using an explicit list instead of a hashtable makes the intent greppable and
# avoids any key-matching surprises with dotted group ids.
$script:pinList = @(
    @('org.junit.jupiter', 'junit-jupiter-api', '5.13.4'),
    @('org.junit.jupiter', 'junit-jupiter-engine', '5.13.4'),
    @('org.junit.jupiter', 'junit-jupiter-params', '5.13.4'),
    @('org.junit.jupiter', 'junit-jupiter', '5.13.4'),
    @('org.junit.platform', 'junit-platform-launcher', '1.13.4'),
    @('org.junit.platform', 'junit-platform-engine', '1.13.4'),
    @('org.junit.platform', 'junit-platform-commons', '1.13.4'),
    @('com.fasterxml.jackson.core', 'jackson-databind', '2.18.3'),
    @('com.fasterxml.jackson.core', 'jackson-core', '2.18.3'),
    @('com.fasterxml.jackson.core', 'jackson-annotations', '2.18.3'),
    @('com.fasterxml.jackson.datatype', 'jackson-datatype-jsr310', '2.18.3'),
    @('com.fasterxml.jackson.datatype', 'jackson-datatype-jdk8', '2.18.3'),
    @('com.fasterxml.jackson.module', 'jackson-module-parameter-names', '2.18.3'),
    @('org.assertj', 'assertj-core', '3.27.7'),
    @('net.bytebuddy', 'byte-buddy', '1.17.8'),
    @('org.slf4j', 'slf4j-api', '2.0.17'),
    # logback-classic and logback-core must stay on the same version, otherwise the
    # application prints a version-mismatch warning at startup.
    @('ch.qos.logback', 'logback-classic', '1.5.18'),
    @('ch.qos.logback', 'logback-core', '1.5.18'),
    # Spring Boot 4.x / Spring Framework 7.x / JUnit 6.x are also present in the
    # local repository but are incompatible with this project's Boot 3.4.4 stack,
    # so the Spring and test-support artifacts are pinned to the 3.4.x / 6.2.x line.
    @('org.springframework.boot', 'spring-boot-test', '3.4.4'),
    @('org.springframework.boot', 'spring-boot-test-autoconfigure', '3.4.4'),
    @('org.springframework', 'spring-test', '6.2.5'),
    @('org.springframework', 'spring-core', '6.2.5'),
    @('org.springframework', 'spring-context', '6.2.5'),
    @('org.springframework', 'spring-beans', '6.2.5'),
    @('org.springframework', 'spring-aop', '6.2.5'),
    @('org.springframework', 'spring-expression', '6.2.5'),
    @('org.springframework', 'spring-web', '6.2.5'),
    @('org.springframework', 'spring-webmvc', '6.2.5'),
    @('org.springframework', 'spring-jcl', '6.2.5'),
    @('com.jayway.jsonpath', 'json-path', '2.9.0'),
    @('org.skyscreamer', 'jsonassert', '1.5.3'),
    @('org.xmlunit', 'xmlunit-core', '2.10.0'),
    @('org.awaitility', 'awaitility', '4.2.2')
)

# Fast lookup view of the pin list: "group:artifact" -> version
$script:pins = @{}
foreach ($pin in $script:pinList) {
    $script:pins[($pin[0] + ':' + $pin[1])] = $pin[2]
}

function Test-Placeholder([string]$version) {
    if (-not $version) { return $true }
    return $version.StartsWith('${') -or $version -eq 'RELEASE' -or $version -eq 'LATEST'
}

function Get-VersionRank([string]$version) {
    $clean = $version -replace '-.*$', ''
    $numbers = @(0, 0, 0, 0)
    $i = 0
    foreach ($part in ($clean -split '[.\-]')) {
        $n = 0
        if ([int]::TryParse($part, [ref]$n) -and $i -lt 4) { $numbers[$i] = $n; $i++ }
    }
    $penalty = 0
    if ($version -match '(?i)(alpha|beta|rc|snapshot|m\d|preview|incubating)') { $penalty = 1 }
    return [double]($numbers[0] * 1000000 + $numbers[1] * 10000 + $numbers[2] * 100 + $numbers[3]) - $penalty
}

function Get-Jars([string]$group, [string]$artifact) {
    $key = "$group" + ':' + "$artifact"
    if ($script:jarCache.ContainsKey($key)) { return $script:jarCache[$key] }
    $dir = Join-Path $script:repo ($group -replace '\.', '\')
    $dir = Join-Path $dir $artifact
    $result = @()
    if (Test-Path $dir) {
        foreach ($v in Get-ChildItem $dir -Directory) {
            $jar = Join-Path $v.FullName "$artifact-$($v.Name).jar"
            if (Test-Path $jar) {
                $result += [pscustomobject]@{
                    Version = $v.Name
                    Path    = $jar
                    Rank    = Get-VersionRank $v.Name
                }
            }
        }
    }
    $result = @($result | Sort-Object -Property @{Expression = 'Rank'; Descending = $true},
                                             @{Expression = 'Version'; Descending = $true})
    $script:jarCache[$key] = $result
    return $result
}

function Resolve-Version([string]$group, [string]$artifact, [string]$declared) {
    $key = "$group" + ':' + "$artifact"
    $candidates = @()
    if (-not (Test-Placeholder $declared)) { $candidates += $declared }
    if ($script:pins.ContainsKey($key)) { $candidates += $script:pins[$key] }
    foreach ($version in $candidates) {
        $path = Join-Path $script:repo ($group -replace '\.', '\')
        $path = Join-Path $path $artifact
        $path = Join-Path $path $version
        $path = Join-Path $path "$artifact-$version.jar"
        if (Test-Path $path) { return $version }
    }
    $available = Get-Jars $group $artifact
    if ($available.Count -eq 0) { return $null }
    return $available[0].Version
}

function Get-PomPath([string]$group, [string]$artifact, [string]$version) {
    $key = "$group" + ':' + "$artifact" + ':' + "$version"
    if ($script:pomCache.ContainsKey($key)) { return $script:pomCache[$key] }
    $path = Join-Path $script:repo ($group -replace '\.', '\')
    $path = Join-Path $path $artifact
    $path = Join-Path $path $version
    $path = Join-Path $path "$artifact-$version.pom"
    if (Test-Path $path) { $resolved = $path } else { $resolved = $null }
    $script:pomCache[$key] = $resolved
    return $resolved
}

function Add-Dependency([string]$group, [string]$artifact, [string]$version, [string]$scope, [bool]$optional) {
    if ($optional) { return }
    if ($scope -eq 'test' -or $scope -eq 'provided' -or $scope -eq 'system' -or $scope -eq 'import') { return }
    if ($group -eq 'org.projectlombok') { return }
    $key = "$group" + ':' + "$artifact"
    if ($script:chosen.Contains($key)) { return }

    $resolved = Resolve-Version $group $artifact $version
    if (-not $resolved) {
        Write-Verbose "missing in local repo: $key (declared $version)"
        return
    }
    $jar = Join-Path $script:repo ($group -replace '\.', '\')
    $jar = Join-Path $jar $artifact
    $jar = Join-Path $jar $resolved
    $jar = Join-Path $jar "$artifact-$resolved.jar"
    if (-not (Test-Path $jar)) { return }
    $script:chosen[$key] = $jar
    Resolve-Pom $group $artifact $resolved
}

function Resolve-Pom([string]$group, [string]$artifact, [string]$version) {
    $key = "$group" + ':' + "$artifact" + ':' + "$version"
    if ($script:visiting.Contains($key)) { return }
    [void]$script:visiting.Add($key)
    $pom = Get-PomPath $group $artifact $version
    if (-not $pom) { return }
    [xml]$xml = Get-Content $pom -Raw
    $project = $xml.project
    if (-not $project) { return }

    foreach ($dep in @($project.dependencyManagement.dependencies.dependency)) {
        if (-not $dep) { continue }
        if (Test-Placeholder ([string]$dep.version)) { continue }
        Add-Dependency ([string]$dep.groupId) ([string]$dep.artifactId) ([string]$dep.version) ([string]$dep.scope) (([string]$dep.optional) -eq 'true')
    }
    foreach ($dep in @($project.dependencies.dependency)) {
        if (-not $dep) { continue }
        Add-Dependency ([string]$dep.groupId) ([string]$dep.artifactId) ([string]$dep.version) ([string]$dep.scope) (([string]$dep.optional) -eq 'true')
    }
}

# --- main -------------------------------------------------------------------
[xml]$pom = Get-Content (Join-Path $root 'pom.xml') -Raw
Resolve-Pom ([string]$pom.project.groupId) ([string]$pom.project.artifactId) ([string]$pom.project.version)
foreach ($dep in @($pom.project.dependencies.dependency)) {
    if (-not $dep) { continue }
    Add-Dependency ([string]$dep.groupId) ([string]$dep.artifactId) ([string]$dep.version) ([string]$dep.scope) (([string]$dep.optional) -eq 'true')
}

# Test-runner extras: JUnit Platform launcher/engine + assertion lib, in case the
# dependency tree above resolved them with a managed (unavailable) version.
$extras = @(
    @('org.junit.platform', 'junit-platform-launcher'),
    @('org.junit.platform', 'junit-platform-engine'),
    @('org.junit.platform', 'junit-platform-commons'),
    @('org.junit.jupiter', 'junit-jupiter-api'),
    @('org.junit.jupiter', 'junit-jupiter-engine'),
    @('org.junit.jupiter', 'junit-jupiter-params'),
    @('org.opentest4j', 'opentest4j'),
    @('org.assertj', 'assertj-core'),
    # Spring Boot test support (TestRestTemplate / @SpringBootTest web environment)
    @('org.springframework.boot', 'spring-boot-test'),
    @('org.springframework.boot', 'spring-boot-test-autoconfigure'),
    @('org.springframework', 'spring-test'),
    @('com.jayway.jsonpath', 'json-path'),
    @('org.skyscreamer', 'jsonassert'),
    @('org.xmlunit', 'xmlunit-core'),
    @('org.awaitility', 'awaitility'),
    @('org.apache.pdfbox', 'pdfbox'),
    @('org.apache.pdfbox', 'pdfbox-io'),
    @('org.apache.pdfbox', 'fontbox'),
    @('commons-logging', 'commons-logging')
)
foreach ($pair in $extras) {
    Add-Dependency $pair[0] $pair[1] '' 'compile' $false
}

$jars = @($script:chosen.Values)

# Final pass: honour the pins unconditionally. Transitive metadata in the local
# repository can reference newer families (Boot 4 / Framework 7 / JUnit 6), so a
# pin must be able to replace an already-chosen artifact.
foreach ($pin in $script:pinList) {
    $group = $pin[0]
    $artifact = $pin[1]
    $version = $pin[2]
    $key = $group + ':' + $artifact
    $pinJar = Join-Path $script:repo ($group -replace '\.', '\')
    $pinJar = Join-Path $pinJar $artifact
    $pinJar = Join-Path $pinJar $version
    $pinJar = Join-Path $pinJar "$artifact-$version.jar"
    $exists = Test-Path $pinJar
    $has = $script:chosen.Contains($key)
    Write-Verbose ("pin {0} -> {1} | chosen={2} | jarExists={3}" -f $key, $version, $has, $exists)
    if (-not $exists) {
        Write-Warning "pin not available in local repo, keeping resolved version: $key -> $version"
        continue
    }
    if ($has) {
        $script:chosen[$key] = $pinJar
    }
}
$jars = @($script:chosen.Values)
$outDir = Join-Path $root 'target'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$classpath = $jars -join ';'
$outFile = Join-Path $outDir 'resolved-classpath.txt'
$classpath | Set-Content -Path $outFile -Encoding ascii -NoNewline

# Java @argfile: lets .cmd launchers pass the full classpath without an
# environment variable, which cmd.exe would truncate at 1023 characters.
# It must carry the COMPLETE -cp (project classes included): Java's launcher
# lets a later -cp override an earlier one, so splitting it across the command
# line and the argfile would silently drop one half.
# Paths are written with forward slashes: inside an @argfile a backslash is an
# escape character, so "target\classes" would be read as "targetclasses".
$argFile = Join-Path $outDir 'test-classpath.args'
$argClasspath = ('target/classes;target/test-classes;' + $classpath).Replace('\', '/')
('-cp "{0}"' -f $argClasspath) | Set-Content -Path $argFile -Encoding ascii -NoNewline

Write-Host ("resolved {0} dependencies -> {1}" -f $jars.Count, $outFile) -ForegroundColor DarkGray
$jars
