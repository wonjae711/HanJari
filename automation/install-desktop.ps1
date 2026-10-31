param([string]$PythonExe = '')
$ErrorActionPreference = 'Stop'
if (-not $PythonExe) {
    $PythonExe = (Get-Command python -ErrorAction Stop).Source
}
$runtime = (& $PythonExe -c 'import sys, tkinter; print(sys.executable)').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Python/Tk runtime is required.' }
$pythonw = Join-Path ([IO.Path]::GetDirectoryName($runtime)) 'pythonw.exe'
$entry = Join-Path $PSScriptRoot 'desktop.py'
$icon = Join-Path $PSScriptRoot 'desktop_assets/hanjari.ico'
foreach ($file in @($pythonw, $entry, $icon)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing desktop component: $file" }
}
$desktopFolder = [Environment]::GetFolderPath('Desktop')
if (-not $desktopFolder) { throw 'Windows Desktop folder could not be resolved.' }
$linkPath = Join-Path $desktopFolder '한자리 스튜디오.lnk'
$shellObject = New-Object -ComObject WScript.Shell
$link = $shellObject.CreateShortcut($linkPath)
$arguments = '"' + $entry + '"'
if ((Test-Path -LiteralPath $linkPath) -and ($link.TargetPath -ne $pythonw -or $link.Arguments -ne $arguments)) {
    throw 'A different shortcut already exists; preserved without overwriting.'
}
$link.TargetPath = $pythonw
$link.Arguments = $arguments
$link.WorkingDirectory = Split-Path -Parent $PSScriptRoot
$link.Description = '한자리 자동화 스튜디오 — 실행, 기획 승인, 코드 리뷰'
$link.IconLocation = $icon + ',0'
$link.Save()
Write-Output "Desktop shortcut ready: $linkPath"
