param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Department'),
    [switch]$OpenBrowser
)

$ErrorActionPreference = 'Stop'
$app = Join-Path $InstallRoot 'app\department.jar'
$configDirectory = Join-Path $InstallRoot 'config'
$config = Join-Path $configDirectory 'application-local.yml'
$mysqlRoot = Join-Path $InstallRoot 'mysql-runtime'
$mysql = Join-Path $mysqlRoot 'bin\mysqld.exe'
$data = Join-Path $InstallRoot 'mysql-data'
$logs = Join-Path $InstallRoot 'logs'
$campusIp = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'WLAN' -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '169.254.*' } | Select-Object -ExpandProperty IPAddress -First 1

foreach ($required in @($app, $config, $mysql, $data)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "本地部署文件不存在：$required" }
}
New-Item -ItemType Directory -Path $logs -Force | Out-Null

if (-not (Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath $mysql -ArgumentList @(
        "--basedir=$mysqlRoot", "--datadir=$data", '--port=3307', '--bind-address=127.0.0.1',
        "--log-error=$(Join-Path $logs 'mysql.log')", "--pid-file=$(Join-Path $InstallRoot 'mysql.pid')"
    ) -WindowStyle Hidden | Out-Null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        if (Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue) { break }
    }
    if (-not (Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue)) {
        throw "数据库启动失败，请查看 $(Join-Path $logs 'mysql.log')"
    }
}

try {
    $health = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/health' -TimeoutSec 2
    if ($health.status -eq 'ok') {
        if ($OpenBrowser) { Start-Process 'http://127.0.0.1:8080' }
        Write-Host '服务部系统已经在运行：http://127.0.0.1:8080'
        if ($campusIp) { Write-Host "校园网访问地址：http://${campusIp}:8080" }
        return
    }
} catch {}

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw '8080 端口已被其他程序占用'
}

$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$javaCandidates += 'F:\DevTools\Java\jdk-21.0.12.1+1\bin\java.exe'
$javaCandidates += (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' })
$java = $javaCandidates | Where-Object { Test-Path -LiteralPath $_ } | Where-Object {
    (& $_ -version 2>&1 | Out-String) -match 'version "21'
} | Select-Object -First 1
if (-not $java) { throw '未找到 Java 21，请先安装或设置 JAVA_HOME' }

$configUrl = 'file:' + ($configDirectory -replace '\\', '/') + '/'
$process = Start-Process -FilePath $java -ArgumentList @('-jar', $app, "--spring.config.additional-location=$configUrl") `
    -RedirectStandardOutput (Join-Path $logs 'backend.out.log') `
    -RedirectStandardError (Join-Path $logs 'backend.err.log') -WindowStyle Hidden -PassThru
[IO.File]::WriteAllText((Join-Path $InstallRoot 'backend.pid'), [string]$process.Id)

for ($attempt = 0; $attempt -lt 60; $attempt++) {
    Start-Sleep -Milliseconds 500
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/health' -TimeoutSec 2
        if ($health.status -eq 'ok') {
            if ($OpenBrowser) { Start-Process 'http://127.0.0.1:8080' }
            Write-Host '服务部系统启动成功：http://127.0.0.1:8080'
            if ($campusIp) { Write-Host "校园网访问地址：http://${campusIp}:8080" }
            return
        }
    } catch {}
}
throw "后端启动失败，请查看 $(Join-Path $logs 'backend.err.log')"
