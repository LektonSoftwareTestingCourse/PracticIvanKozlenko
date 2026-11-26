param(
    [switch]$Full,
    [switch]$KeepStack
)

$ErrorActionPreference = 'Stop'
$compose = @('compose', '--env-file', '.env.example', '-f', 'compose.practice5.yaml')
$runId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$reports = Join-Path $PSScriptRoot "services/api-tests/target/practice-5/windows-$runId"
$stackStarted = $false
$exitCode = 0

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE. Arguments: $Arguments"
    }
}

function Invoke-Tests {
    param([string]$Phase, [string[]]$Options = @())

    Write-Host "`n=== $Phase ==="
    $arguments = @('-B') + $mavenOptions + @(
        '-f', 'services/pom.xml', '-Papi-tests', '-pl', 'api-tests',
        "-Dapi.reports=$(Join-Path $reports $Phase)"
    ) + $Options + @('test')
    Invoke-Checked $maven $arguments
}

Push-Location $PSScriptRoot
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker is not on PATH. Install and start Docker Desktop with Linux containers.'
    }

    $localTools = Join-Path (Split-Path $PSScriptRoot -Parent) '.tools'
    $localJdk = Join-Path $localTools 'jdk-21.0.13+9'
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath $localJdk)) {
        $env:JAVA_HOME = $localJdk
    }

    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $mavenOptions = @()
    if ($mavenCommand) {
        $maven = $mavenCommand.Source
    } else {
        $maven = Join-Path $localTools 'apache-maven-3.9.9/bin/mvn.cmd'
        if (-not (Test-Path -LiteralPath $maven)) {
            throw 'Maven is not on PATH. Install Maven and Java 21.'
        }
        $localRepository = Join-Path $localTools 'm2'
        if (Test-Path -LiteralPath $localRepository) {
            $mavenOptions += "-Dmaven.repo.local=$localRepository"
        }
    }

    Invoke-Checked $maven @('-version')
    Invoke-Checked docker @('info', '--format', '{{.ServerVersion}}')
    Invoke-Checked docker ($compose + @('--profile', 'rate-limit', 'config', '--quiet'))
    New-Item -ItemType Directory -Path $reports -Force | Out-Null

    Write-Host 'Resetting only the practice5 test stack and its data.'
    $stackStarted = $true
    Invoke-Checked docker ($compose + @('--profile', 'rate-limit', 'down', '--volumes', '--remove-orphans'))
    Invoke-Checked docker ($compose + @('up', '-d', '--wait', '--wait-timeout', '240', 'postgres', 'rabbitmq'))
    Invoke-Checked docker ($compose + @('up', '--build', '-d', '--wait', '--wait-timeout', '480'))

    $runs = if ($Full) { 3 } else { 1 }
    for ($run = 1; $run -le $runs; $run++) {
        Invoke-Tests "baseline-$run"
    }

    if ($Full) {
        Invoke-Checked docker ($compose + @('--profile', 'rate-limit', 'up', '-d', '--no-deps', '--wait', '--wait-timeout', '480', 'limited-gateway'))
        Invoke-Tests 'rate-limit' @('-Dtest=RateLimitTest', '-Dlimited.url=http://localhost:18090')

        $phases = @(
            @('rabbitmq', 'BrokerRollbackTest', 'broker.offline', 'rollback'),
            @('switch', 'DownstreamFailureTest', 'switch.offline', 'switch-unavailable'),
            @('card-management', 'CardServiceFailureTest', 'cards.offline', 'cards-unavailable')
        )
        foreach ($phase in $phases) {
            Invoke-Checked docker ($compose + @('stop', $phase[0]))
            try {
                Invoke-Tests $phase[3] @("-Dtest=$($phase[1])", "-D$($phase[2])=true")
            } finally {
                Invoke-Checked docker ($compose + @('up', '-d', '--wait', '--wait-timeout', '480', $phase[0]))
            }
        }
    }
} catch {
    $exitCode = 1
    Write-Host "`nFAILED: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    if ($stackStarted) {
        $ErrorActionPreference = 'Continue'
        & docker @compose ps --all 2>&1 | Out-File (Join-Path $reports 'compose-state.log') -Encoding utf8
        & docker @compose logs --no-color --tail 200 2>&1 | Out-File (Join-Path $reports 'compose.log') -Encoding utf8
        if (-not $KeepStack) {
            & docker @compose --profile rate-limit down --volumes --remove-orphans
            if ($LASTEXITCODE -ne 0) { $exitCode = 1 }
        }
        Write-Host "Reports: $reports"
    }
    Pop-Location
}

if ($exitCode -eq 0) { Write-Host 'SUCCESS' -ForegroundColor Green }
exit $exitCode
