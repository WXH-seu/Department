param(
    [Parameter(Mandatory)][string]$MySqlBin,
    [Parameter(Mandatory)][string]$CredentialsFile,
    [Parameter(Mandatory)][string]$Uploads,
    [Parameter(Mandatory)][string]$BackupRoot,
    [string]$Database = 'department',
    [ValidateRange(1, 3650)][int]$RetentionDays = 30,
    [switch]$ApplicationStopped
)
$ErrorActionPreference = 'Stop'
if (!$ApplicationStopped) { throw 'Stop the backend first, then pass -ApplicationStopped for a consistent database/files snapshot.' }
if ($Database -notmatch '^[A-Za-z0-9_]+$') { throw 'Invalid database name' }
$credentials = (Resolve-Path -LiteralPath $CredentialsFile).Path
$uploadPath = (Resolve-Path -LiteralPath $Uploads).Path
$root = [IO.Path]::GetFullPath($BackupRoot)
$rootPath = [IO.Path]::GetPathRoot($root)
if ($root.TrimEnd('\') -eq $rootPath.TrimEnd('\')) { throw 'Backup root cannot be a drive root' }
if ($root.StartsWith($uploadPath.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or $root -eq $uploadPath) { throw 'Backups must be outside uploads' }
$destination = Join-Path $root (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
New-Item -ItemType Directory -Path $destination | Out-Null
$dump = Join-Path $destination 'database.sql'
& (Join-Path $MySqlBin 'mysqldump.exe') "--defaults-extra-file=$credentials" --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$dump" $Database
if ($LASTEXITCODE -ne 0) { throw 'Database export failed; this backup is incomplete' }
Copy-Item -LiteralPath $uploadPath -Destination (Join-Path $destination 'uploads') -Recurse
$files = Get-ChildItem -LiteralPath $destination -File -Recurse | ForEach-Object {
    [pscustomobject]@{ path = [IO.Path]::GetRelativePath($destination, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
}
$files | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
Set-Content -LiteralPath (Join-Path $destination 'COMPLETE') -Value 'Database and uploads copied successfully.' -Encoding utf8
Get-ChildItem -LiteralPath $root -Directory | Where-Object {
    $_.FullName -ne $destination -and $_.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddDays(-$RetentionDays) -and
    (Test-Path -LiteralPath (Join-Path $_.FullName 'COMPLETE'))
} | Remove-Item -Recurse -Force
Write-Output $destination
