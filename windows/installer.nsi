Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
!include "FileFunc.nsh"
!define APPID "ShengyaTTS"
!define MARKER "AE114907-3FD7-4DF2-9426-BE82106F1D21"
!define PRODUCT "声芽配音 · Shengya TTS"
Name "${PRODUCT}"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\ShengyaTTS"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 32
ManifestDPIAware true
VIProductVersion "0.8.0.0"
VIAddVersionKey /LANG=2052 "ProductName" "${PRODUCT}"
VIAddVersionKey /LANG=2052 "FileDescription" "声芽配音 0.8.0 安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "0.8.0"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Shengya TTS contributors; dependency notices retained"
!define MUI_ICON "${ROOT}\windows\assets\shengya.ico"
!define MUI_UNICON "${ROOT}\windows\assets\shengya.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "欢迎安装声芽配音"
!define MUI_WELCOMEPAGE_TEXT "让文字拥有声音。$\r$\n$\r$\n本地配音、音频克隆和云端声音采样，汇聚在一个工作室。$\r$\n$\r$\n安装后可从桌面打开，无需安装 Python。模型在应用内单独下载。"
!define MUI_FINISHPAGE_RUN "$INSTDIR\ShengyaTTS.exe"
!define MUI_FINISHPAGE_RUN_TEXT "打开声芽配音"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${ROOT}\LICENSE"
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!define MUI_UNCONFIRMPAGE_TEXT_TOP "卸载声芽配音程序。已下载的模型、音色、凭据和历史记录会保留在用户数据目录。"
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"
Var RegistryKey
Var SkipShortcuts

!macro EnsureProgramClosed PREFIX
  IfFileExists "$INSTDIR\ShengyaTTS.exe" 0 ${PREFIX}closed
  ClearErrors
  FileOpen $0 "$INSTDIR\ShengyaTTS.exe" a
  IfErrors ${PREFIX}locked
  FileClose $0
  Goto ${PREFIX}closed
${PREFIX}locked:
  MessageBox MB_ICONEXCLAMATION "程序文件正在使用或无法写入，请关闭声芽配音后重试。" /SD IDOK
  SetErrorLevel 2
  Abort
${PREFIX}closed:
!macroend

Function .onInit
  SetShellVarContext current
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "此版本需要 64 位 Windows。" /SD IDOK
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_ICONSTOP "此版本需要 Windows 10 或更新版本。" /SD IDOK
    Abort
  ${EndIf}
  StrCpy $RegistryKey "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPID}"
  StrCpy $SkipShortcuts "0"
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/TestInstall" $1
  ${IfNot} ${Errors}
    StrCpy $RegistryKey "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPID}.InstallTest"
    StrCpy $SkipShortcuts "1"
  ${EndIf}
  ReadRegStr $1 HKCU "$RegistryKey" "InstallLocation"
  ${If} $1 != ""
    StrCpy $INSTDIR $1
  ${EndIf}
  ${If} $SkipShortcuts != "1"
    FindWindow $0 "" "${PRODUCT}"
    ${If} $0 != 0
      MessageBox MB_ICONEXCLAMATION "请先关闭正在运行的声芽配音，再继续安装。" /SD IDOK
      Abort
    ${EndIf}
  ${EndIf}
FunctionEnd

Function .onVerifyInstDir
  IfFileExists "$INSTDIR\*.*" 0 valid
  IfFileExists "$INSTDIR\.shengya-install-id" 0 invalid
  FileOpen $0 "$INSTDIR\.shengya-install-id" r
  FileRead $0 $1
  FileClose $0
  StrCmp $1 "${MARKER}" valid invalid
invalid:
  SetErrors
valid:
FunctionEnd

Section "声芽配音（必需）" Main
  SectionIn RO
  ClearErrors
  Call .onVerifyInstDir
  IfErrors 0 targetReady
  MessageBox MB_ICONSTOP "所选目录已有其他文件，请选择空目录或已有的声芽配音安装目录。" /SD IDOK
  SetErrorLevel 2
  Abort
targetReady:
  !insertmacro EnsureProgramClosed install
  SetOutPath "$INSTDIR"
  SetOverwrite on
  File /r "${BUNDLE}\*.*"
  FileOpen $0 "$INSTDIR\.shengya-install-id" w
  FileWrite $0 "${MARKER}"
  FileClose $0
  FileOpen $0 "$INSTDIR\.shengya-registry" w
  FileWrite $0 "$RegistryKey"
  FileClose $0
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "$RegistryKey" "DisplayName" "${PRODUCT}"
  WriteRegStr HKCU "$RegistryKey" "DisplayVersion" "0.8.0"
  WriteRegStr HKCU "$RegistryKey" "Publisher" "Shengya TTS contributors"
  WriteRegStr HKCU "$RegistryKey" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "$RegistryKey" "DisplayIcon" "$INSTDIR\ShengyaTTS.exe"
  WriteRegStr HKCU "$RegistryKey" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegDWORD HKCU "$RegistryKey" "NoModify" 1
  WriteRegDWORD HKCU "$RegistryKey" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  WriteRegDWORD HKCU "$RegistryKey" "EstimatedSize" $0
  ${If} $SkipShortcuts != "1"
    CreateDirectory "$SMPROGRAMS\声芽配音"
    CreateShortcut "$SMPROGRAMS\声芽配音\声芽配音.lnk" "$INSTDIR\ShengyaTTS.exe"
    CreateShortcut "$SMPROGRAMS\声芽配音\卸载声芽配音.lnk" "$INSTDIR\Uninstall.exe"
  ${EndIf}
SectionEnd

Section "创建桌面快捷方式" Desktop
  ${If} $SkipShortcuts != "1"
    CreateShortcut "$DESKTOP\声芽配音.lnk" "$INSTDIR\ShengyaTTS.exe"
  ${EndIf}
SectionEnd

Function un.onInit
  SetShellVarContext current
  FileOpen $0 "$INSTDIR\.shengya-install-id" r
  IfErrors invalid
  FileRead $0 $1
  FileClose $0
  StrCmp $1 "${MARKER}" valid invalid
invalid:
  MessageBox MB_ICONSTOP "安装目录标识不匹配，未执行卸载。" /SD IDOK
  Abort
valid:
  FileOpen $0 "$INSTDIR\.shengya-registry" r
  FileRead $0 $RegistryKey
  FileClose $0
  StrCmp $RegistryKey "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPID}" normal
  StrCmp $RegistryKey "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPID}.InstallTest" test invalid
normal:
  StrCpy $SkipShortcuts "0"
  Goto checkRunning
test:
  StrCpy $SkipShortcuts "1"
checkRunning:
  ${If} $SkipShortcuts != "1"
    FindWindow $0 "" "${PRODUCT}"
    ${If} $0 != 0
      MessageBox MB_ICONEXCLAMATION "请先关闭声芽配音，再卸载。" /SD IDOK
      Abort
    ${EndIf}
  ${EndIf}
FunctionEnd

Section "Uninstall"
  !insertmacro EnsureProgramClosed uninstall
  !include "${UNINSTALL_LIST}"
  Delete "$INSTDIR\.shengya-install-id"
  Delete "$INSTDIR\.shengya-registry"
  Delete "$INSTDIR\Uninstall.exe"
  ${If} $SkipShortcuts != "1"
    Delete "$DESKTOP\声芽配音.lnk"
    Delete "$SMPROGRAMS\声芽配音\声芽配音.lnk"
    Delete "$SMPROGRAMS\声芽配音\卸载声芽配音.lnk"
    RMDir "$SMPROGRAMS\声芽配音"
  ${EndIf}
  DeleteRegKey HKCU "$RegistryKey"
  RMDir "$INSTDIR"
SectionEnd
