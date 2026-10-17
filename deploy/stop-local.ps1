param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Department'),
    [switch]$StopDatabase
)

$ErrorActionPreference = 'Stop'
$pidFile = Join-Path $InstallRoot 'backend.pid'
if (Test-Path -LiteralPath $pidFile) {
    $processId = [int](Get-Content -LiteralPath $pidFile -Raw)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq 'java') { Stop-Process -Id $processId }
    Remove-Item -LiteralPath $pidFile -Force
}

if ($StopDatabase) {
    $mysqlPidFile = Join-Path $InstallRoot 'mysql.pid'
    if (Test-Path -LiteralPath $mysqlPidFile) {
        $mysqlProcessId = [int](Get-Content -LiteralPath $mysqlPidFile -Raw)
        $process = Get-Process -Id $mysqlProcessId -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -eq 'mysqld') { Stop-Process -Id $mysqlProcessId }
    }
}

Write-Host '服务部系统已停止。'
