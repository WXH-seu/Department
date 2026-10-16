param(
    [Parameter(Mandatory)][string]$MySqlBin,
    [Parameter(Mandatory)][string]$CredentialsFile,
    [Parameter(Mandatory)][string]$BackupDirectory,
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][string]$Uploads,
    [switch]$ApplicationStopped
)
$ErrorActionPreference = 'Stop'
if (!$ApplicationStopped) { throw 'Stop the backend before restoring.' }
if ($Database -notmatch '^[A-Za-z0-9_]+$') { throw 'Invalid database name' }
$backup = (Resolve-Path -LiteralPath $BackupDirectory).Path
$credentials = (Resolve-Path -LiteralPath $CredentialsFile).Path
if (!(Test-Path -LiteralPath (Join-Path $backup 'COMPLETE'))) { throw 'Backup is incomplete' }
$manifest = Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw | ConvertFrom-Json
foreach ($entry in $manifest) {
    $file = [IO.Path]::GetFullPath((Join-Path $backup $entry.path))
    if (!$file.StartsWith($backup.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Manifest path escapes backup' }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Backup checksum failed: $($entry.path)" }
}
if (Test-Path -LiteralPath $Uploads) { throw 'Restore uploads into a new directory; existing directories are never overwritten.' }
$client = Join-Path $MySqlBin 'mysql.exe'
$count = & $client "--defaults-extra-file=$credentials" --batch --skip-column-names "--execute=SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database'"
if ($LASTEXITCODE -ne 0 -or [int]$count -ne 0) { throw 'Target database must be accessible and empty' }
$OutputEncoding = [Text.UTF8Encoding]::new($false)
Get-Content -LiteralPath (Join-Path $backup 'database.sql') -Raw -Encoding utf8 | & $client "--defaults-extra-file=$credentials" --default-character-set=utf8mb4 $Database
if ($LASTEXITCODE -ne 0) { throw 'Database restore failed; keep backend stopped and inspect the target database.' }
Copy-Item -LiteralPath (Join-Path $backup 'uploads') -Destination $Uploads -Recurse
Write-Output "Restored $Database and uploads. Verify before restarting the backend."
