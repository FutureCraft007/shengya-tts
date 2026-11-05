param([switch]$SkipNative, [switch]$Verify, [switch]$UpdateVersionOutput)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskPreviousPath = $env:PATH
Push-Location $taskRoot
try {
    if (-not (Get-Command cmake -ErrorAction SilentlyContinue)) {
        $taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        $taskCmake = Get-ChildItem (Join-Path $taskSdk 'cmake') -Filter cmake.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($taskCmake) { $env:PATH = "$(Split-Path -Parent $taskCmake.FullName);$env:PATH" }
    }
    $env:CMAKE_BUILD_PARALLEL_LEVEL = '2'
    # Embedded debug information avoids mspdbsrv named-pipe failures in restricted build environments.
    $taskPreviousCl = $env:_CL_
    $env:_CL_ = "$taskPreviousCl /Z7"
    if (-not $SkipNative) {
        & .\external\qwen3-tts.cpp\build.ps1 -UseNinja -Portable -Configuration Release
        if ($LASTEXITCODE -ne 0) { throw 'Native build failed.' }
    }
    $taskNative = Join-Path $taskRoot 'external\qwen3-tts.cpp\build'
    if ($SkipNative) { $taskNative = Join-Path $taskRoot 'build\native' }
    $taskExe = Join-Path $taskNative 'qwen3-tts-cli.exe'
    if (-not (Test-Path -LiteralPath $taskExe)) { throw 'Build the native engine first.' }
    # Opt the native CLI into UTF-8 Windows paths, including Chinese account names.
    $taskMt = Get-Command mt.exe -ErrorAction SilentlyContinue
    $taskMtPath = if ($taskMt) { $taskMt.Source } else {
        Get-ChildItem (Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin\*\x64\mt.exe') -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if ($taskMtPath) {
        & $taskMtPath -nologo -manifest .\windows\native.manifest "-outputresource:$taskExe;#1"
        if ($LASTEXITCODE -ne 0) { throw 'Could not embed UTF-8 manifest.' }
    } else { throw 'Windows SDK mt.exe is required to package the native engine.' }
    if ($Verify) {
        & python scripts/verify-windows.py
        if ($LASTEXITCODE -ne 0) { throw 'Windows tests failed.' }
    }
    & python scripts/make-windows-icon.py
    if ($LASTEXITCODE -ne 0) { throw 'Icon generation failed.' }
    # Qt uses Windows' native ICU. Avoid collecting a same-named ICU DLL from
    # unrelated tools (for example Poppler) ahead of the Windows system library.
    $env:PATH = "$env:SystemRoot\System32;$env:PATH"
    & python -m PyInstaller --noconfirm --clean --windowed --onedir --name ShengyaTTS `
        --distpath dist/windows/qt-0.8.0 --workpath build/pyinstaller-0.8.0 --specpath build --version-file "$taskRoot/windows/version_info.txt" `
        --icon "$taskRoot/windows/assets/shengya.ico" `
        --additional-hooks-dir "$taskRoot/windows/pyinstaller-hooks" `
        --collect-binaries sherpa_onnx --copy-metadata sherpa-onnx --copy-metadata sherpa-onnx-core `
        --add-data "$taskRoot/windows/assets;windows/assets" `
        --add-data "$taskRoot/android/src/main/assets/kokoro-model.json;android/src/main/assets" `
        --add-data "$taskRoot/android/src/main/assets/paradee-model.json;android/src/main/assets" `
        --add-data "$taskRoot/android/src/main/assets/paradee-tokens.txt;android/src/main/assets" `
        --add-data "$taskRoot/android/src/main/assets/paradee-lexicon.bin;android/src/main/assets" `
        --add-data "$taskRoot/LICENSE;licenses" --add-data "$taskRoot/THIRD_PARTY_NOTICES.md;licenses" `
        --add-data "$taskRoot/external/qwen3-tts.cpp/LICENSE;licenses/qwen3-tts.cpp" `
        --add-data "$taskRoot/external/qwen3-tts.cpp/ggml/LICENSE;licenses/ggml" `
        --add-binary "$taskExe;native" `
        --add-binary "$taskNative/ggml.dll;native" `
        --add-binary "$taskNative/ggml-base.dll;native" `
        --add-binary "$taskNative/ggml-cpu.dll;native" windows/run.py
    if ($LASTEXITCODE -ne 0) { throw 'Windows packaging failed.' }
    $taskBundle = Join-Path $taskRoot 'dist/windows/ShengyaTTS-0.8.0'
    if (Test-Path -LiteralPath $taskBundle) {
        if (-not $UpdateVersionOutput) { throw 'Versioned output exists. Use -UpdateVersionOutput to rebuild this version.' }
        Get-ChildItem -LiteralPath (Join-Path $taskRoot 'dist/windows/qt-0.8.0/ShengyaTTS') | Copy-Item -Destination $taskBundle -Force -Recurse
    } else {
        Move-Item -LiteralPath (Join-Path $taskRoot 'dist/windows/qt-0.8.0/ShengyaTTS') -Destination $taskBundle
    }
    & python .\scripts\package-runtime.py
    if ($LASTEXITCODE -ne 0) { throw 'Runtime packaging failed.' }
    Copy-Item -LiteralPath LICENSE,THIRD_PARTY_NOTICES.md,README.md,README.zh-CN.md -Destination $taskBundle
    New-Item -ItemType Directory -Force -Path (Join-Path $taskBundle 'docs') | Out-Null
    Copy-Item -LiteralPath docs/RELEASE.md,docs/QT-RUNTIME.md,docs/KOKORO.md,docs/PARADEE.md -Destination (Join-Path $taskBundle 'docs')
    Write-Host 'Windows app: dist/windows/ShengyaTTS-0.8.0/ShengyaTTS.exe'
} finally {
    $env:PATH = $taskPreviousPath
    $env:_CL_ = $taskPreviousCl
    Pop-Location
}
