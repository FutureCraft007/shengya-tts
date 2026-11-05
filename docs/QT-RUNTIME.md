# Qt runtime in Windows 0.6.0

The application is MIT licensed. Its dynamically linked PySide6 / Shiboken 6.11.0 and Qt 6.11.0 runtime remain under their own licenses. The LGPL v3 option applies to the Qt Core, GUI, Widgets, Network and SVG libraries shipped here; see the retained LGPL, GPL and Qt exception texts and upstream license annotations. Unused PDF and virtual-keyboard plugins are excluded before dependency collection. Retained Qt third-party notices are under licenses/qt-third-party; their exact upstream source URLs are listed in SOURCES.txt. Additional Python/PyInstaller notices are included in this bundle's licenses folder. Qt uses the operating system's native ICU; an unrelated ICU from external tools must not be bundled.

Corresponding upstream source, including license annotations and build instructions:

- Qt Base 6.11.0: https://code.qt.io/cgit/qt/qtbase.git/tree/?h=v6.11.0
- Qt Base source archive: https://download.qt.io/archive/qt/6.11/6.11.0/submodules/qtbase-everywhere-src-6.11.0.tar.xz
- Qt SVG 6.11.0: https://code.qt.io/cgit/qt/qtsvg.git/tree/?h=v6.11.0
- Qt SVG source archive: https://download.qt.io/archive/qt/6.11/6.11.0/submodules/qtsvg-everywhere-src-6.11.0.tar.xz
- PySide / Shiboken 6.11.0: https://code.qt.io/cgit/pyside/pyside-setup.git/tree/?h=v6.11.0
- PySide source archive: https://download.qt.io/official_releases/QtForPython/pyside6/PySide6-6.11.0-src/pyside-setup-everywhere-src-6.11.0.tar.xz

No Qt source modifications are applied. Build dependencies are pinned in windows/requirements-build.txt. Qt DLLs and plugins are ordinary replaceable files under _internal/PySide6. Users may replace these with compatible modified builds; reverse engineering for debugging such modifications is permitted. Rebuilding the application with a modified compatible PySide runtime is also possible using the included Python sources and scripts/build-windows.ps1. No signature or checksum enforcement prevents replacing Qt DLLs.

The installer and uninstaller preserve the separate user-data folder %LOCALAPPDATA%\ShengyaTTS. No model weights are bundled.
