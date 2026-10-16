param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]$identity
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw '请右键使用管理员权限运行此脚本，以设置 Windows 防火墙。'
}
$name = "Department Campus LAN $Port"
Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port `
    -RemoteAddress LocalSubnet -Profile Domain,Private,Public | Out-Null
Write-Host "已允许校园网本地子网访问 TCP $Port；数据库端口未对外开放。"
