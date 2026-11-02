# Run from the user's PowerShell. No password is written to a file.
param([switch]$Recheck)
$ErrorActionPreference = 'Stop'
$pilotRoot = Split-Path -Parent $PSScriptRoot
$pilotNames = @('HANJARI_TEST_DB_URL', 'HANJARI_TEST_DB_USERNAME', 'HANJARI_TEST_DB_PASSWORD', 'PYTHONIOENCODING')
$pilotPrevious = @{}
foreach ($pilotName in $pilotNames) {
    $pilotPrevious[$pilotName] = [Environment]::GetEnvironmentVariable($pilotName, 'Process')
}
$pilotExit = 1
$pilotSecret = $null
Push-Location $pilotRoot
try {
    $env:HANJARI_TEST_DB_URL = 'jdbc:mysql://localhost:3306/hanjari_test?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8'
    $env:HANJARI_TEST_DB_USERNAME = 'hanjari_test'
    $pilotSecret = Read-Host 'Password for MySQL hanjari_test (not root)' -AsSecureString
    $pilotPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($pilotSecret)
    try {
        $env:HANJARI_TEST_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pilotPointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pilotPointer)
    }
    # The integration test checks SELECT DATABASE() before migration and data cleanup.
    $env:PYTHONIOENCODING = 'utf-8'
    if ($Recheck) {
        & python .\automation\final_verify.py --activate --recheck
    } else {
        & python .\automation\final_verify.py --activate
    }
    $pilotExit = $LASTEXITCODE
    if ($pilotExit -eq 0) {
        Write-Host 'Final verification runner completed. Check the reported stage and outcome.'
    } else {
        Write-Host 'STOPPED: Check state.json and the latest attempt logs in automation/.runtime-pilot (or .runtime-recheck).'
    }
} finally {
    foreach ($pilotName in $pilotNames) {
        [Environment]::SetEnvironmentVariable($pilotName, $pilotPrevious[$pilotName], 'Process')
    }
    if ($null -ne $pilotSecret) { $pilotSecret.Dispose() }
    Pop-Location
}
exit $pilotExit
