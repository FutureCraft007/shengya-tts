"""Native visual language; all icons scale with the monitor DPI."""
from pathlib import Path
import sys
from PySide6.QtCore import Qt, QRectF
from PySide6.QtGui import QColor, QIcon, QPainter, QPainterPath, QPen, QPixmap


def icon(kind, color="#8DA3CB", size=24):
    pixmap = QPixmap(size * 2, size * 2); pixmap.fill(Qt.GlobalColor.transparent)
    p = QPainter(pixmap); p.setRenderHint(QPainter.RenderHint.Antialiasing); p.scale(size / 12, size / 12)
    p.setPen(QPen(QColor(color), 1.8, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin))
    if kind == "brand":
        p.setPen(Qt.PenStyle.NoPen); p.setBrush(QColor("#3563F4")); p.drawRoundedRect(QRectF(0, 0, 24, 24), 7, 7)
        p.setPen(QPen(QColor("white"), 1.8, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        for x, a, b in [(6, 10, 14), (10, 6, 18), (14, 9, 15), (18, 11, 13)]: p.drawLine(x, a, x, b)
        p.setPen(Qt.PenStyle.NoPen); p.setBrush(QColor("#93E5C0")); p.drawEllipse(QRectF(16, 4, 5, 4))
    elif kind in ("wave", "studio"):
        for x, a, b in [(4, 10, 14), (8, 6, 18), (12, 3, 21), (16, 8, 16), (20, 10, 14)]: p.drawLine(x, a, x, b)
    elif kind == "voices":
        p.drawRoundedRect(QRectF(8, 3, 8, 12), 4, 4)
        path = QPainterPath(); path.moveTo(5, 11); path.cubicTo(5, 22, 19, 22, 19, 11); p.drawPath(path); p.drawLine(12, 19, 12, 23)
    elif kind == "history":
        p.drawEllipse(QRectF(4, 4, 17, 17)); p.drawLine(12, 7, 12, 13); p.drawLine(12, 13, 16, 15)
    elif kind == "cloud":
        path = QPainterPath(); path.moveTo(6, 18); path.cubicTo(-1, 17, 1, 9, 7, 9); path.cubicTo(7, 1, 19, 1, 19, 10); path.cubicTo(26, 10, 25, 19, 18, 18); p.drawPath(path); p.drawLine(12, 13, 12, 23)
    elif kind == "settings":
        for y, x in [(6, 8), (12, 16), (18, 10)]:
            p.drawLine(3, y, 21, y); p.setBrush(QColor("white")); p.drawEllipse(QRectF(x - 2, y - 2, 4, 4))
    elif kind == "play":
        p.setBrush(QColor(color)); path = QPainterPath(); path.moveTo(8, 4); path.lineTo(20, 12); path.lineTo(8, 20); path.closeSubpath(); p.drawPath(path)
    elif kind == "stop": p.setBrush(QColor(color)); p.drawRoundedRect(QRectF(6, 6, 12, 12), 2, 2)
    elif kind in ("download", "export"):
        p.drawLine(12, 3, 12, 16); p.drawLine(7, 11, 12, 16); p.drawLine(17, 11, 12, 16); p.drawLine(4, 18, 4, 21); p.drawLine(4, 21, 20, 21); p.drawLine(20, 21, 20, 18)
    elif kind == "folder":
        path = QPainterPath(); path.moveTo(3, 8); path.lineTo(3, 4); path.lineTo(10, 4); path.lineTo(13, 7); path.lineTo(21, 7); path.lineTo(21, 20); path.lineTo(3, 20); path.closeSubpath(); p.drawPath(path)
    elif kind == "plus": p.drawLine(12, 5, 12, 19); p.drawLine(5, 12, 19, 12)
    elif kind == "search": p.drawEllipse(QRectF(3, 3, 12, 12)); p.drawLine(14, 14, 21, 21)
    else: p.drawEllipse(QRectF(4, 4, 16, 16)); p.drawLine(12, 10, 12, 17); p.drawPoint(12, 7)
    p.end(); pixmap.setDevicePixelRatio(2); return QIcon(pixmap)


STYLE = """
QWidget { font-family: 'Microsoft YaHei UI', 'Segoe UI'; font-size:14px; color:#233048; }
QMainWindow, QWidget#root { background:#F5F7FB; }
QFrame#sidebar { background:#172237; border:none; }
QFrame#sidebar QLabel { color:#92A5C6; background:transparent; }
QFrame#sidebar QLabel#brandName { color:white; font-size:21px; font-weight:700; }
QFrame#sidebar QLabel#brandSub { color:#8294B5; font-size:10px; letter-spacing:2px; }
QLabel#navSection { color:#7185A6; font-size:11px; }
QPushButton[nav="true"] { text-align:left; color:#BCCBE1; background:transparent; border:none; border-radius:10px; padding:14px 16px; font-size:14px; }
QPushButton[nav="true"]:hover { color:white; background:#23334F; }
QPushButton[nav="true"]:checked { color:white; background:#2B4170; }
QFrame#sideFoot { border:1px solid #344560; background:#1E2D45; border-radius:12px; }
QLabel#sideFootTitle { color:#DDE6F7; font-size:12px; }
QLabel#pageTitle { font-size:25px; font-weight:700; color:#1C2940; }
QLabel#pageSubtitle, QLabel#muted { font-size:12px; color:#8592A9; }
QLabel#sectionTitle { font-size:16px; font-weight:600; }
QLabel#fieldLabel { font-size:12px; color:#63718A; }
QLabel#badge { color:#3563F4; background:#EAF0FF; border-radius:8px; padding:7px 12px; font-size:11px; }
QFrame[card="true"] { background:white; border:1px solid #E7ECF4; border-radius:16px; }
QFrame#notice { background:#FFF5ED; border:1px solid #FFDEC3; border-radius:10px; }
QFrame#notice QLabel { color:#A76530; font-size:12px; }
QFrame#separator { background:#ECF0F6; border:none; min-height:1px; max-height:1px; }
QLineEdit, QComboBox, QSpinBox { min-height:28px; padding:8px 12px; border:1px solid #DFE5F0; border-radius:9px; background:white; color:#34435F; selection-background-color:#DCE7FF; }
QLineEdit:focus, QComboBox:focus, QSpinBox:focus { border-color:#7192FA; }
QComboBox::drop-down { border:none; width:28px; }
QComboBox::down-arrow { image:url('__CHEVRON__'); width:12px; height:12px; }
QSpinBox::up-button { subcontrol-origin:border; subcontrol-position:top right; width:24px; border:none; }
QSpinBox::down-button { subcontrol-origin:border; subcontrol-position:bottom right; width:24px; border:none; }
QComboBox QAbstractItemView { background:white; border:1px solid #DFE5F0; selection-background-color:#EBF1FF; selection-color:#3563F4; padding:6px; }
QComboBox#uiLanguage { min-height:22px; padding:5px 10px; font-size:12px; }
QPlainTextEdit { background:white; border:1px solid #E3E9F2; border-radius:10px; padding:12px; font-size:14px; selection-background-color:#DCE7FF; }
QPlainTextEdit#scriptEditor { border:none; padding:2px; font-size:17px; color:#354159; }
QPushButton { min-height:25px; padding:8px 16px; border-radius:9px; border:1px solid #DFE5F0; color:#586780; background:white; }
QPushButton:hover { background:#F5F8FE; border-color:#BCCBF1; }
QPushButton:pressed { background:#EAF0FF; }
QPushButton:disabled { color:#ADB8CC; background:#F5F6F9; border-color:#EBEEF4; }
QPushButton[primary="true"] { color:white; background:#3563F4; border:1px solid #3563F4; font-weight:600; }
QPushButton[primary="true"]:hover { background:#2552E0; }
QPushButton[primary="true"]:disabled { background:#A8B9F5; border-color:#A8B9F5; color:white; }
QPushButton[link="true"] { background:transparent; border:none; color:#3563F4; padding:3px 0; text-align:left; min-height:20px; font-size:12px; }
QPushButton#generateButton { min-height:36px; padding:10px 22px; font-size:15px; }
QPushButton#playButton { background:#EBF1FF; color:#3563F4; border:none; min-width:24px; }
QListWidget, QTableWidget { background:white; border:none; outline:none; selection-background-color:#EDF3FF; selection-color:#3563F4; }
QListWidget::item { padding:12px; border-bottom:1px solid #F0F3F8; min-height:43px; }
QListWidget::item:hover { background:#F7F9FF; }
QListWidget::item:selected { background:#EDF3FF; color:#3563F4; border-radius:8px; }
QHeaderView::section { background:#F8FAFD; color:#8190A6; border:none; padding:12px; font-size:12px; }
QTableWidget::item { padding:10px; border-bottom:1px solid #F0F3F8; }
QScrollArea { border:none; background:transparent; }
QScrollBar:vertical { background:transparent; width:7px; margin:2px; }
QScrollBar::handle:vertical { background:#D4DCEB; border-radius:3px; min-height:32px; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height:0; }
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical { background:transparent; }
QProgressBar { border:none; background:#E8EEFB; border-radius:3px; min-height:4px; max-height:4px; }
QProgressBar::chunk { background:#3563F4; border-radius:3px; }
QToolTip { background:#202D47; color:white; border:none; padding:6px 10px; }
"""
_assets = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parents[2]))/'windows/assets'
STYLE = STYLE.replace('__CHEVRON__', (_assets/'chevron.svg').as_posix())
