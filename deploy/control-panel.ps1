param([string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Department'))

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$startScript = Join-Path $PSScriptRoot 'start-local.ps1'
$stopScript = Join-Path $PSScriptRoot 'stop-local.ps1'
$restartScript = Join-Path $PSScriptRoot 'restart-local.ps1'
$logs = Join-Path $InstallRoot 'logs'
$script:operation = $null
$script:operationName = ''

function Get-CampusIp {
    Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'WLAN' -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '169.254.*' } |
        Select-Object -ExpandProperty IPAddress -First 1
}

function Test-ListeningPort([int]$Port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Start-ControlOperation([string]$Name, [string]$Script, [string[]]$Arguments = @()) {
    if ($script:operation -and -not $script:operation.HasExited) { return }
    $shell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $shell
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $escapedScript = $Script.Replace('"','\"')
    $escapedRoot = $InstallRoot.Replace('"','\"')
    $extra = if ($Arguments.Count) { ' ' + ($Arguments -join ' ') } else { '' }
    $info.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$escapedScript`" -InstallRoot `"$escapedRoot`"$extra"
    $script:operation = [System.Diagnostics.Process]::new()
    $script:operation.StartInfo = $info
    $script:operationName = $Name
    [void]$script:operation.Start()
    $messageLabel.Text = "$Name 中，请稍候……"
    $startButton.Enabled = $false
    $stopButton.Enabled = $false
    $restartButton.Enabled = $false
}

$form = [System.Windows.Forms.Form]::new()
$form.Text = '服务部系统控制台'
$form.StartPosition = 'CenterScreen'
$form.ClientSize = [System.Drawing.Size]::new(620, 430)
$form.MinimumSize = [System.Drawing.Size]::new(636, 469)
$form.Font = [System.Drawing.Font]::new('Microsoft YaHei UI', 10)
$form.BackColor = [System.Drawing.Color]::FromArgb(245, 248, 247)

$title = [System.Windows.Forms.Label]::new()
$title.Text = '服务部工作系统'
$title.Font = [System.Drawing.Font]::new('Microsoft YaHei UI', 20, [System.Drawing.FontStyle]::Bold)
$title.ForeColor = [System.Drawing.Color]::FromArgb(24, 59, 54)
$title.Location = [System.Drawing.Point]::new(30, 24)
$title.AutoSize = $true
$form.Controls.Add($title)

$subtitle = [System.Windows.Forms.Label]::new()
$subtitle.Text = '本机服务器启动与运行状态'
$subtitle.ForeColor = [System.Drawing.Color]::FromArgb(100, 123, 117)
$subtitle.Location = [System.Drawing.Point]::new(34, 68)
$subtitle.AutoSize = $true
$form.Controls.Add($subtitle)

$statusPanel = [System.Windows.Forms.Panel]::new()
$statusPanel.Location = [System.Drawing.Point]::new(30, 105)
$statusPanel.Size = [System.Drawing.Size]::new(560, 98)
$statusPanel.BackColor = [System.Drawing.Color]::White
$statusPanel.BorderStyle = 'FixedSingle'
$form.Controls.Add($statusPanel)

$statusDot = [System.Windows.Forms.Label]::new()
$statusDot.Text = '●'
$statusDot.Font = [System.Drawing.Font]::new('Segoe UI Symbol', 18)
$statusDot.Location = [System.Drawing.Point]::new(18, 15)
$statusDot.AutoSize = $true
$statusPanel.Controls.Add($statusDot)

$statusLabel = [System.Windows.Forms.Label]::new()
$statusLabel.Font = [System.Drawing.Font]::new('Microsoft YaHei UI', 13, [System.Drawing.FontStyle]::Bold)
$statusLabel.Location = [System.Drawing.Point]::new(55, 19)
$statusLabel.AutoSize = $true
$statusPanel.Controls.Add($statusLabel)

$detailLabel = [System.Windows.Forms.Label]::new()
$detailLabel.Location = [System.Drawing.Point]::new(22, 58)
$detailLabel.AutoSize = $true
$detailLabel.ForeColor = [System.Drawing.Color]::FromArgb(80, 103, 97)
$statusPanel.Controls.Add($detailLabel)

$startButton = [System.Windows.Forms.Button]::new()
$startButton.Text = '启动服务器'
$startButton.Location = [System.Drawing.Point]::new(30, 225)
$startButton.Size = [System.Drawing.Size]::new(170, 48)
$startButton.BackColor = [System.Drawing.Color]::FromArgb(23, 108, 88)
$startButton.ForeColor = [System.Drawing.Color]::White
$startButton.FlatStyle = 'Flat'
$startButton.Add_Click({ Start-ControlOperation '启动' $startScript })
$form.Controls.Add($startButton)

$stopButton = [System.Windows.Forms.Button]::new()
$stopButton.Text = '关闭服务器'
$stopButton.Location = [System.Drawing.Point]::new(225, 225)
$stopButton.Size = [System.Drawing.Size]::new(170, 48)
$stopButton.BackColor = [System.Drawing.Color]::FromArgb(164, 65, 65)
$stopButton.ForeColor = [System.Drawing.Color]::White
$stopButton.FlatStyle = 'Flat'
$stopButton.Add_Click({ Start-ControlOperation '关闭' $stopScript @('-StopDatabase') })
$form.Controls.Add($stopButton)

$restartButton = [System.Windows.Forms.Button]::new()
$restartButton.Text = '重新启动'
$restartButton.Location = [System.Drawing.Point]::new(420, 225)
$restartButton.Size = [System.Drawing.Size]::new(170, 48)
$restartButton.BackColor = [System.Drawing.Color]::FromArgb(78, 105, 98)
$restartButton.ForeColor = [System.Drawing.Color]::White
$restartButton.FlatStyle = 'Flat'
$restartButton.Add_Click({ Start-ControlOperation '重启' $restartScript })
$form.Controls.Add($restartButton)

$localButton = [System.Windows.Forms.Button]::new()
$localButton.Text = '打开本机页面'
$localButton.Location = [System.Drawing.Point]::new(30, 296)
$localButton.Size = [System.Drawing.Size]::new(170, 38)
$localButton.Add_Click({ Start-Process 'http://127.0.0.1:8080' })
$form.Controls.Add($localButton)

$campusButton = [System.Windows.Forms.Button]::new()
$campusButton.Text = '打开校园网页面'
$campusButton.Location = [System.Drawing.Point]::new(225, 296)
$campusButton.Size = [System.Drawing.Size]::new(170, 38)
$campusButton.Add_Click({ $ip = Get-CampusIp; if ($ip) { Start-Process "http://${ip}:8080" } })
$form.Controls.Add($campusButton)

$logButton = [System.Windows.Forms.Button]::new()
$logButton.Text = '查看运行日志'
$logButton.Location = [System.Drawing.Point]::new(420, 296)
$logButton.Size = [System.Drawing.Size]::new(170, 38)
$logButton.Add_Click({ if (-not (Test-Path $logs)) { New-Item -ItemType Directory -Path $logs -Force | Out-Null }; Start-Process explorer.exe $logs })
$form.Controls.Add($logButton)

$addressLabel = [System.Windows.Forms.Label]::new()
$addressLabel.Location = [System.Drawing.Point]::new(34, 355)
$addressLabel.Size = [System.Drawing.Size]::new(550, 24)
$addressLabel.ForeColor = [System.Drawing.Color]::FromArgb(55, 91, 82)
$form.Controls.Add($addressLabel)

$messageLabel = [System.Windows.Forms.Label]::new()
$messageLabel.Location = [System.Drawing.Point]::new(34, 389)
$messageLabel.Size = [System.Drawing.Size]::new(550, 25)
$messageLabel.ForeColor = [System.Drawing.Color]::FromArgb(100, 123, 117)
$form.Controls.Add($messageLabel)

function Update-ControlStatus {
    $webRunning = Test-ListeningPort 8080
    $databaseRunning = Test-ListeningPort 3307
    $ip = Get-CampusIp
    if ($webRunning) {
        $statusDot.ForeColor = [System.Drawing.Color]::FromArgb(37, 148, 104)
        $statusLabel.Text = '服务器正在运行'
    } else {
        $statusDot.ForeColor = [System.Drawing.Color]::FromArgb(180, 75, 75)
        $statusLabel.Text = '服务器已关闭'
    }
    $detailLabel.Text = "应用：$(if($webRunning){'运行中'}else{'已停止'})    数据库：$(if($databaseRunning){'运行中'}else{'已停止'})"
    $addressLabel.Text = if ($ip) { "校园网访问地址：http://${ip}:8080" } else { '未检测到 WLAN 校园网地址' }
    $localButton.Enabled = $webRunning
    $campusButton.Enabled = $webRunning -and [bool]$ip
    if (-not $script:operation -or $script:operation.HasExited) {
        $startButton.Enabled = -not $webRunning
        $stopButton.Enabled = $webRunning -or $databaseRunning
        $restartButton.Enabled = $webRunning
    }
}

$timer = [System.Windows.Forms.Timer]::new()
$timer.Interval = 1500
$timer.Add_Tick({
    if ($script:operation -and $script:operation.HasExited) {
        $output = ($script:operation.StandardOutput.ReadToEnd() + $script:operation.StandardError.ReadToEnd()).Trim()
        if ($script:operation.ExitCode -eq 0) { $messageLabel.Text = "$script:operationName 完成。" }
        else { $messageLabel.Text = if ($output) { $output.Split([Environment]::NewLine)[-1] } else { "$script:operationName 失败，请查看运行日志。" } }
        $script:operation.Dispose()
        $script:operation = $null
    }
    Update-ControlStatus
})
$form.Add_Shown({ Update-ControlStatus; $timer.Start() })
$form.Add_FormClosed({ $timer.Stop(); if ($script:operation) { $script:operation.Dispose() } })
[void]$form.ShowDialog()
