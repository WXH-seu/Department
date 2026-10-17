param([string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Department'))
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'stop-local.ps1') -InstallRoot $InstallRoot -StopDatabase
& (Join-Path $PSScriptRoot 'start-local.ps1') -InstallRoot $InstallRoot
