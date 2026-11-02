param([switch]$Activate)
$ErrorActionPreference = 'Stop'
if (-not $Activate) { throw 'Use -Activate to explicitly start project planning.' }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    python (Join-Path $PSScriptRoot 'project.py') run --activate
    if ($LASTEXITCODE -ne 0) { throw 'Planning stopped. Check the reported stage and local artifacts.' }
} finally { Pop-Location }
