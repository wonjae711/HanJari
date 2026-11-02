# Store only a Windows DPAPI-encrypted token for the current Windows user.
$ErrorActionPreference = 'Stop'
$tokenDir = Join-Path $PSScriptRoot '.runtime-delivery'
$secret = Read-Host 'Notion internal integration token (hidden input)' -AsSecureString
try {
    if ($secret.Length -eq 0) { throw 'Token must not be empty.' }
    New-Item -ItemType Directory -Force -Path $tokenDir | Out-Null
    $encrypted = ConvertFrom-SecureString -SecureString $secret
    [IO.File]::WriteAllText((Join-Path $tokenDir 'notion-token.dpapi'), $encrypted, [Text.Encoding]::ASCII)
    Write-Host 'Encrypted token saved for this Windows user. Token was not printed.'
} finally {
    $secret.Dispose()
}
