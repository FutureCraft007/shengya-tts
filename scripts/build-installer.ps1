param([string]$MakeNsis = 'makensis.exe')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskBundle = Join-Path $taskRoot 'dist\windows\ShengyaTTS-0.8.0'
$taskOutput = Join-Path $taskRoot 'dist\ShengyaTTS-0.8.0-Setup-x64.exe'
$taskList = Join-Path $taskRoot 'build\uninstall-files.nsh'
if (-not (Test-Path -LiteralPath (Join-Path $taskBundle 'ShengyaTTS.exe'))) { throw 'Build the Windows app first.' }
$taskLines = [System.Collections.Generic.List[string]]::new()
foreach ($taskFile in (Get-ChildItem -LiteralPath $taskBundle -Recurse -File)) {
    $taskRelative = [System.IO.Path]::GetRelativePath($taskBundle, $taskFile.FullName)
    if ($taskRelative.Contains('$') -or $taskRelative.Contains('"')) { throw 'Unsafe bundle filename.' }
    $taskLines.Add('Delete "$INSTDIR\' + $taskRelative + '"')
}
foreach ($taskDir in (Get-ChildItem -LiteralPath $taskBundle -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending)) {
    $taskRelative = [System.IO.Path]::GetRelativePath($taskBundle, $taskDir.FullName)
    $taskLines.Add('RMDir "$INSTDIR\' + $taskRelative + '"')
}
[System.IO.File]::WriteAllLines($taskList, $taskLines, [System.Text.UTF8Encoding]::new($true))
& $MakeNsis /V2 /INPUTCHARSET UTF8 "/DROOT=$taskRoot" "/DBUNDLE=$taskBundle" "/DOUTPUT=$taskOutput" "/DUNINSTALL_LIST=$taskList" (Join-Path $taskRoot 'windows\installer.nsi')
if ($LASTEXITCODE -ne 0) { throw 'Installer build failed.' }
Get-Item -LiteralPath $taskOutput | Select-Object FullName,Length
