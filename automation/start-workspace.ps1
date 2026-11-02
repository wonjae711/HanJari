param([string]$Project = 'hanjari', [switch]$Activate)
$ErrorActionPreference = 'Stop'
$runnerArgs = @((Join-Path $PSScriptRoot 'workspace.py'), 'run', '--project', $Project)
if ($Activate) { $runnerArgs += '--activate' }
& python @runnerArgs
if ($LASTEXITCODE -ne 0) { throw 'Workspace stopped. Read the reported decision or failure before retrying.' }
