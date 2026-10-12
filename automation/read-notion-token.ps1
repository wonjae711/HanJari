# Private adapter: stdout is captured by Python. Do not run interactively.
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '.runtime-delivery\notion-token.dpapi'
$secret = ConvertTo-SecureString ([IO.File]::ReadAllText($path))
$pointer = [IntPtr]::Zero
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    [Console]::Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer))
} finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $secret.Dispose()
}
