param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Department'),
    [string]$ShortcutDirectory = [Environment]::GetFolderPath('Desktop')
)
$ErrorActionPreference = 'Stop'
$controlDirectory = Join-Path $InstallRoot 'control'
New-Item -ItemType Directory -Path $controlDirectory -Force | Out-Null
foreach ($name in @('control-panel.ps1','start-local.ps1','stop-local.ps1','restart-local.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $controlDirectory $name) -Force
}
$shellPath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$shortcutPath = Join-Path $ShortcutDirectory '服务部系统控制台.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $shellPath
$shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $controlDirectory 'control-panel.ps1')`" -InstallRoot `"$InstallRoot`""
$shortcut.WorkingDirectory = $controlDirectory
$shortcut.Description = '启动、关闭和查看服务部系统状态'
$shortcut.IconLocation = "$env:SystemRoot\System32\imageres.dll,11"
$shortcut.Save()
Write-Host "控制台已安装：$shortcutPath"
