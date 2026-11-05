"""Collect only the platform/image plugins used by the Widgets application.

The default hook also bundles PDF viewing and the QML virtual keyboard, which
this desktop application never uses. Keep them out before DLL dependency scan.
"""
from pathlib import Path
from PyInstaller.utils.hooks.qt import add_qt6_dependencies

hiddenimports, binaries, datas = add_qt6_dependencies(__file__)
plugins = {'qwindows.dll', 'qoffscreen.dll', 'qminimal.dll', 'qsvgicon.dll',
           'qsvg.dll', 'qjpeg.dll', 'qico.dll', 'qgif.dll'}
binaries = [entry for entry in binaries if Path(entry[0]).name.lower() in plugins]
