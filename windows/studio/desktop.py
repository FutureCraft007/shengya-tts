"""Qt desktop workspace. Inference/storage protocols remain in core.py."""
from __future__ import annotations
import os
from pathlib import Path
import shutil
import sys
import threading
import time
import wave
import winsound
from PySide6.QtCore import Qt, QObject, QThread, Signal, Slot, QSize, QUrl
from PySide6.QtGui import QFont, QFontDatabase, QShortcut, QKeySequence, QDesktopServices
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QFrame, QLabel, QPushButton,
    QVBoxLayout, QHBoxLayout, QComboBox, QPlainTextEdit, QLineEdit, QSpinBox,
    QStackedWidget, QScrollArea, QListWidget, QListWidgetItem, QTableWidget, QTableWidgetItem,
    QHeaderView, QAbstractItemView, QProgressBar, QFileDialog, QMessageBox, QInputDialog)
from .core import (Store, CloudSpeech, LocalEngine, StudioError, MODELS, cloud_url,
    import_models, download_models, model_speakers, write_json)
from .i18n import translate, is_chinese
from .design import STYLE, icon
from . import light

_app = None
def application():
    global _app
    _app = QApplication.instance() or QApplication(sys.argv[:1]); _app.setStyle("Fusion")
    if os.environ.get("QT_QPA_PLATFORM") == "offscreen":
        fonts = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"
        for name in ("msyh.ttc", "msyhbd.ttc", "segoeui.ttf"):
            if (fonts/name).is_file(): QFontDatabase.addApplicationFont(str(fonts/name))
    _app.setApplicationName("Shengya TTS"); _app.setApplicationVersion("0.8.0")
    _app.setFont(QFont("Microsoft YaHei UI", 10)); _app.setWindowIcon(icon("brand", size=64))
    return _app

def label(text, name="", wrap=False):
    w = QLabel(text); w.setObjectName(name); w.setWordWrap(wrap); return w
def row(parent=None, spacing=12):
    layout = QHBoxLayout(parent); layout.setContentsMargins(0, 0, 0, 0); layout.setSpacing(spacing); return layout
def card():
    frame = QFrame(); frame.setProperty("card", True); layout = QVBoxLayout(frame)
    layout.setContentsMargins(22, 22, 22, 22); layout.setSpacing(14); return frame, layout
def combo(items, value=None):
    w = QComboBox()
    for title, data in items: w.addItem(title, data)
    if value is not None: w.setCurrentIndex(max(0, w.findData(value)))
    return w
def button(text, callback, glyph=None, primary=False, link=False):
    w = QPushButton(text); w.setCursor(Qt.CursorShape.PointingHandCursor)
    if glyph: w.setIcon(icon(glyph, "white" if primary else "#7086AB", 20)); w.setIconSize(QSize(20, 20))
    w.setProperty("primary", primary); w.setProperty("link", link); w.clicked.connect(callback); return w
def field(layout, title, widget):
    layout.addWidget(label(title, "fieldLabel")); layout.addWidget(widget); return widget

class Operation(QObject):
    progress = Signal(str); success = Signal(object); error = Signal(object); finished = Signal()
    def __init__(self, callback): super().__init__(); self.callback = callback
    @Slot()
    def run(self):
        try: self.success.emit(self.callback(self.progress.emit))
        except Exception as error: self.error.emit(error)
        finally: self.finished.emit()

class Studio(QMainWindow):
    def __init__(self, store=None):
        application(); super().__init__(); self.store = store or Store()
        self.cloud, self.engine = CloudSpeech(self.store), LocalEngine(self.store)
        self.values = self.store.settings(); self.language = self.values.get("language", "system")
        self.cancel_event = threading.Event(); self.busy = False; self._thread = None; self._operation = None
        self._drafts = {"text": self.t("local_default"), "sample": self.t("sample_default"), "transcript": "", "name": "", "secret": ""}
        self.sample_result = self.preview = None; self._page = 0; self._outcome = None
        self._voice_selections = {}
        self.setWindowTitle("声芽配音 · Shengya TTS"); self.setWindowIcon(icon("brand", size=64)); self.setMinimumSize(1000, 620)
        screen = QApplication.primaryScreen().availableGeometry(); self.resize(min(1320, screen.width()-60), min(860, screen.height()-60))
        self._build(); self.shortcut = QShortcut(QKeySequence("Ctrl+Return"), self); self.shortcut.activated.connect(lambda:self.synthesize() if self._page==0 else None)
    def t(self, key): return translate(key, self.language)
    def w(self, zh, en): return zh if is_chinese(self.language) else en
    def scroll(self, widget):
        area = QScrollArea(); area.setWidgetResizable(True); area.setWidget(widget); area.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff); widget.setAutoFillBackground(False); area.viewport().setAutoFillBackground(False)
        if widget.minimumWidth() == widget.maximumWidth(): area.setFixedWidth(widget.width()+18)
        return area

    def _build(self):
        self.controls = []; root = QWidget(); root.setObjectName("root"); self.setCentralWidget(root); shell = row(root, 0)
        side = QFrame(); side.setObjectName("sidebar"); side.setFixedWidth(202); nav = QVBoxLayout(side); nav.setContentsMargins(16, 27, 16, 20); nav.setSpacing(9)
        brand = row(spacing=10); mark = QLabel(); mark.setPixmap(icon("brand", size=40).pixmap(40,40)); brand.addWidget(mark)
        names = QVBoxLayout(); names.setSpacing(2); names.addWidget(label("声芽配音", "brandName")); names.addWidget(label("SHENGYA TTS", "brandSub")); brand.addLayout(names); brand.addStretch(); nav.addLayout(brand)
        nav.addSpacing(29); nav.addWidget(label(self.w("声音工作室", "WORKSPACE"), "navSection")); nav.addSpacing(6); self.nav_buttons = []
        for index, (key, title) in enumerate([("studio", self.w("配音工作台", "Studio")), ("voices", self.t("voices")), ("cloud", self.w("云端音色", "Cloud voices")), ("history", self.t("history")), ("settings", self.t("settings"))]):
            b = button(title, lambda checked=False, i=index: self.navigate(i), key); b.setProperty("nav", True); b.setCheckable(True); b.setObjectName("nav_"+key); nav.addWidget(b); self.nav_buttons.append(b)
        nav.addStretch(); foot = QFrame(); foot.setObjectName("sideFoot"); fl = QVBoxLayout(foot); fl.setContentsMargins(13,14,13,14)
        fl.addWidget(label(self.w("在本机，安心创作", "Create on your device"), "sideFootTitle")); fl.addWidget(label(self.w("离线配音 · 音频克隆", "Speech · voice cloning"), "muted")); nav.addWidget(foot); nav.addSpacing(5); nav.addWidget(label("v0.8.0 · Windows", "navSection")); shell.addWidget(side)
        workspace = QWidget(); self.workspace = workspace; body = QVBoxLayout(workspace); body.setContentsMargins(28,24,28,14); body.setSpacing(18)
        header = row(); titles = QVBoxLayout(); titles.setSpacing(5); self.page_title = label("", "pageTitle"); self.page_subtitle = label("", "pageSubtitle"); titles.addWidget(self.page_title); titles.addWidget(self.page_subtitle); header.addLayout(titles); header.addStretch()
        header.addWidget(label(self.w("本地运行", "On-device"), "badge")); self.ui_language = combo([("简体中文", "zh-CN"), ("English", "en"), ("跟随系统", "system")], self.language); self.ui_language.setObjectName("uiLanguage"); self.ui_language.setFixedWidth(125); self.ui_language.currentIndexChanged.connect(self.change_language); header.addWidget(self.ui_language); body.addLayout(header)
        self.notice = QFrame(); self.notice.setObjectName("notice"); nr = row(self.notice); nr.setContentsMargins(13,10,13,10); self.notice_text = label("", wrap=True); nr.addWidget(self.notice_text, 1); nr.addWidget(button("×", self.notice.hide, link=True)); self.notice.hide(); body.addWidget(self.notice)
        self.pages = QStackedWidget(); body.addWidget(self.pages, 1)
        self.studio_page(); self.voices_page(); self.cloud_page(); self.history_page(); self.settings_page()
        status = row(); self.status = label(self.w("就绪 · 你的声音，保存在本机", "Ready · your audio stays on your device"), "muted"); status.addWidget(self.status, 1)
        self.progress = QProgressBar(); self.progress.setRange(0,0); self.progress.setFixedWidth(90); self.progress.hide(); status.addWidget(self.progress); self.cancel_button = button(self.t("cancel"), self.cancel_event.set); self.cancel_button.hide(); status.addWidget(self.cancel_button); body.addLayout(status)
        holder = QWidget(); center = row(holder,0); center.addStretch(); center.addWidget(workspace); center.addStretch(); shell.addWidget(holder,1)
        self.workspace.setFixedWidth(min(1700,self.width()-202))
        root.setStyleSheet(STYLE); self.refresh_records(); self.update_model_status(); self.navigate(self._page)

    def studio_page(self):
        page = QWidget(); layout = row(page,20); left = QVBoxLayout(); left.setSpacing(16)
        editor, el = card(); title = row(); title.addWidget(label(self.w("配音文案", "Your script"), "sectionTitle")); title.addStretch(); self.char_count = label("", "muted"); title.addWidget(self.char_count); el.addLayout(title); el.addWidget(label(self.w("写下想说的话，让文字拥有声音。", "Give your words a voice."), "muted"))
        self.text_editor = QPlainTextEdit(self._drafts["text"]); self.text_editor.setObjectName("scriptEditor"); self.text_editor.setPlaceholderText(self.w("在这里输入配音文案…", "Write your script here…")); self.text_editor.setMinimumHeight(180); self.text_editor.textChanged.connect(self.count_text); el.addWidget(self.text_editor, 1)
        line = QFrame(); line.setObjectName("separator"); el.addWidget(line); actions = row(); self.import_text_button = button(self.w("导入文案", "Import text"), self.import_text, "folder"); actions.addWidget(self.import_text_button); actions.addStretch(); actions.addWidget(label("Ctrl + Enter", "muted"))
        self.generate_button = button(self.w("开始生成配音", "Generate speech"), self.synthesize, "wave", True); self.generate_button.setObjectName("generateButton"); actions.addWidget(self.generate_button); el.addLayout(actions); self.controls += [self.generate_button,self.import_text_button,self.text_editor]; left.addWidget(editor, 1)
        player, pl = card(); pr = row(); wave_mark = QLabel(); wave_mark.setPixmap(icon("wave", "#7696EC",30).pixmap(30,30)); pr.addWidget(wave_mark); texts = QVBoxLayout(); texts.setSpacing(4)
        self.player_title = label(self.w("声音，即将就绪", "Your audio will appear here")); self.player_hint = label(self.w("生成后可直接试听，作品会自动保存到历史记录。", "Preview here. Audio is saved to History."), "muted", True); texts.addWidget(self.player_title); texts.addWidget(self.player_hint); pr.addLayout(texts,1)
        self.play_button = button("", self.play_preview, "play"); self.play_button.setObjectName("playButton"); self.play_button.setAccessibleName(self.t("play")); self.play_button.setToolTip(self.t("play")); self.play_button.setEnabled(bool(self.preview)); pr.addWidget(self.play_button)
        stop = button("", self.stop_playback,"stop"); stop.setToolTip(self.t("stop")); pr.addWidget(stop); self.export_button = button(self.w("导出", "Export"), self.export_preview,"export"); self.export_button.setEnabled(bool(self.preview)); pr.addWidget(self.export_button); pl.addLayout(pr); left.addWidget(player); layout.addLayout(left,1)
        right = QWidget(); right.setFixedWidth(285); rl = QVBoxLayout(right); rl.setContentsMargins(0,0,0,0); rl.setSpacing(16)
        options, ol = card(); ol.addWidget(label(self.w("声音设置", "Voice settings"), "sectionTitle")); ol.addWidget(label(self.w("选择一种声音，表达你的想法。", "Find the voice for your words."), "muted",True))
        self.mode = combo([(self.w("千问 · 声音克隆", "Qwen · Voice cloning"),"base"),(self.w("千问 · 预置音色", "Qwen · Preset voices"),"custom"),(self.w("Kokoro · 轻量中英文", "Kokoro · Chinese & English"),"kokoro"),(self.w("Paradee · 超轻量英文", "Paradee · Tiny English"),"paradee")], self.values.get("mode","base")); self.mode.setObjectName("modelMode"); field(ol,self.w("配音模型", "Speech model"), self.mode); self.mode.currentIndexChanged.connect(self.mode_changed)
        self.voice_choice = QComboBox(); self.voice_choice.setObjectName("localVoice"); field(ol,self.w("使用音色", "Voice"),self.voice_choice); self.manage_voices = button(self.w("管理我的音色  →", "Manage voices  →"),lambda:self.navigate(1),link=True); ol.addWidget(self.manage_voices)
        self.speech_language = combo([("中文","zh"),("English","en"),("自动识别","auto"),("日本語","ja"),("한국어","ko"),("Deutsch","de"),("Français","fr"),("Español","es"),("Русский","ru")], self.values.get("speech_language","zh")); self.speech_language.setObjectName("speechLanguage"); field(ol,self.t("speech_language"),self.speech_language); self.speech_language_label = ol.itemAt(ol.count()-2).widget()
        self.speed_label=label(self.w("语速", "Speed"),"fieldLabel");ol.addWidget(self.speed_label)
        self.light_speed=combo([(f'{v:g}×',v) for v in (.5,.75,1.,1.25,1.5,2.)],self.values.get("kokoro_speed",1.));ol.addWidget(self.light_speed)
        self.model_hint=label("","muted",True);ol.addWidget(self.model_hint)
        self.paradee_example=button(self.w("填入英文示例", "Use English example"),lambda:self.text_editor.setPlainText("Hello, welcome to the voice studio. This is a small English voice running on your device."),link=True);ol.addWidget(self.paradee_example)
        self.controls += [self.mode,self.voice_choice,self.speech_language,self.light_speed,self.paradee_example]; rl.addWidget(options)
        models, ml = card(); ml.addWidget(label(self.w("本地模型", "Local models"),"sectionTitle")); self.model_status = label("","muted",True); ml.addWidget(self.model_status); ml.addWidget(label(self.w("首次下载后，配音和克隆都可以离线进行。", "Download once. Generate and clone offline."),"muted",True)); self.model_manage=button(self.w("下载 / 导入模型  →", "Download / import  →"),lambda:self.navigate(4),link=True);ml.addWidget(self.model_manage)
        self.light_download=button(self.w("下载轻量模型 · 147 MB", "Download Kokoro · 147 MB"),self.download_light,"download",True);ml.addWidget(self.light_download)
        self.light_import=button(self.w("导入轻量模型包", "Import Kokoro archive"),self.import_light,"folder");ml.addWidget(self.light_import);self.controls += [self.light_download,self.light_import]; rl.addWidget(models); rl.addStretch(); layout.addWidget(self.scroll(right)); self.count_text(); self.pages.addWidget(page)

    def voices_page(self):
        page = QWidget(); layout = row(page,20); library, ll = card(); header = row(); header.addWidget(label(self.w("我的音色库", "My voices"),"sectionTitle")); header.addStretch(); self.voice_total = label("","muted"); header.addWidget(self.voice_total); ll.addLayout(header)
        self.voice_list = QListWidget(); self.voice_list.setObjectName("voiceLibrary"); ll.addWidget(self.voice_list,1); self.voice_empty = label(self.w("还没有专属音色\n导入一段清晰人声，创建你的第一个声音。", "Import a clear recording to create your first voice."),"muted",True); self.voice_empty.setAlignment(Qt.AlignmentFlag.AlignCenter); ll.addWidget(self.voice_empty)
        actions = row(); actions.addWidget(button(self.w("选用", "Use"),self.use_voice,"voices",True)); actions.addWidget(button(self.t("play_reference"),self.play_reference,"play")); actions.addWidget(button(self.t("rename"),self.rename_voice)); actions.addWidget(button(self.t("delete"),lambda:self.delete_record("voices"))); ll.addLayout(actions); layout.addWidget(library,1)
        form, fl = card(); form.setFixedWidth(330); fl.addWidget(label(self.w("创建专属音色", "Create your voice"),"sectionTitle")); fl.addWidget(label(self.t("reference_hint"),"muted",True))
        self.name = QLineEdit(self._drafts["name"]); self.name.setObjectName("voiceName"); self.name.setPlaceholderText(self.w("例如：我的日常口播", "My narration voice")); field(fl,self.t("name"),self.name)
        self.transcript_editor = QPlainTextEdit(self._drafts["transcript"]); self.transcript_editor.setObjectName("referenceTranscript"); self.transcript_editor.setMinimumHeight(160); self.transcript_editor.setPlaceholderText(self.w("填写音频中完整、准确的原话。", "Match the recording exactly.")); field(fl,self.w("参考音频逐字稿", "Reference transcript"),self.transcript_editor); fl.addWidget(label(self.w("选填。填写后使用完整克隆；留空使用音色向量模式。", "Optional. Enables full cloning when provided."),"muted",True))
        create = button(self.w("选择音频并创建", "Import and create"),self.import_voice,"plus",True); fl.addWidget(create); self.controls += [create]; layout.addWidget(self.scroll(form)); self.pages.addWidget(page)

    def cloud_page(self):
        form, layout = card()
        layout.addWidget(label(self.t("cloud"), "sectionTitle"))
        layout.addWidget(label(self.w("使用设置中的 HTTPS 语音合成接口。生成可能计费。", "Uses the HTTPS speech endpoint in Settings. Generation may incur charges."), "muted", True))
        self.sample_editor = QPlainTextEdit(self._drafts["sample"])
        self.sample_editor.setObjectName("sampleText")
        self.sample_editor.setMinimumHeight(150)
        field(layout, self.w("配音文案（最多 2000 字）", "Speech text (up to 2,000 characters)"), self.sample_editor)
        generate = button(self.w("生成音频", "Generate audio"), self.generate_sample, "wave", True)
        self.sample_status = label(self.w("已生成，可以试听。", "Ready to preview.") if self.sample_result else "", "muted", True)
        actions = row()
        actions.addWidget(generate)
        actions.addWidget(button(self.t("play"), self.play_sample, "play"))
        actions.addWidget(button(self.w("配置云端服务", "Configure speech service"), lambda: self.navigate(4), link=True))
        actions.addStretch()
        layout.addLayout(actions)
        layout.addWidget(self.sample_status)
        self.controls.append(generate)
        self.pages.addWidget(self.scroll(form))

    def history_page(self):
        page = QWidget(); layout = QVBoxLayout(page); layout.setContentsMargins(0,0,0,0); panel,pl = card(); heading = row(); heading.addWidget(label(self.w("已生成的配音", "Generated audio"),"sectionTitle")); heading.addStretch(); self.history_total = label("","muted"); heading.addWidget(self.history_total); pl.addLayout(heading)
        self.history_list = QTableWidget(0,4); self.history_list.setObjectName("historyTable"); self.history_list.setHorizontalHeaderLabels([self.w("生成时间", "Created"),self.w("音色", "Voice"),self.w("配音文案", "Script"),self.w("时长", "Duration")]); self.history_list.verticalHeader().hide(); self.history_list.setShowGrid(False); self.history_list.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows); self.history_list.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection); self.history_list.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers); self.history_list.horizontalHeader().setSectionResizeMode(2,QHeaderView.ResizeMode.Stretch)
        for col,width in [(0,145),(1,140),(3,75)]: self.history_list.setColumnWidth(col,width)
        pl.addWidget(self.history_list,1); self.history_empty = label(self.w("还没有配音记录\n回到工作台，让第一段文字拥有声音。", "Create your first recording in Studio."),"muted",True); self.history_empty.setAlignment(Qt.AlignmentFlag.AlignCenter); pl.addWidget(self.history_empty)
        actions = row(); actions.addWidget(button(self.t("play"),self.play_history,"play",True)); actions.addWidget(button(self.t("stop"),self.stop_playback,"stop")); actions.addWidget(button(self.t("export"),self.export_history,"export")); actions.addStretch(); actions.addWidget(button(self.t("delete"),lambda:self.delete_record("history"))); pl.addLayout(actions); layout.addWidget(panel,1); self.pages.addWidget(page)

    def settings_page(self):
        page = QWidget(); layout = QVBoxLayout(page); layout.setContentsMargins(0,0,0,0); layout.setSpacing(18); models,ml = card(); ml.addWidget(label(self.w("模型与运行", "Models and performance"),"sectionTitle")); ml.addWidget(label(self.t("model_hint"),"muted",True)); self.model_rows = []
        for key,title in [("tokenizer","语音编解码器 · Tokenizer"),("base","音频克隆 · Base 0.6B"),("custom","预置音色 · CustomVoice 0.6B")]:
            mr = row(); mr.addWidget(label(title)); mr.addStretch(); state = label("","muted"); mr.addWidget(state); self.model_rows.append((key,state)); ml.addLayout(mr)
        actions = row(); dl = button(self.w("下载当前模式模型", "Download selected models"),self.download,"download",True); imports = button(self.w("导入 GGUF 模型", "Import models"),self.import_model_files,"folder"); actions.addWidget(dl); actions.addWidget(imports); actions.addStretch(); ml.addLayout(actions); self.controls += [dl,imports]
        cpu = row(); cpu.addWidget(label(self.w("CPU 线程数", "CPU threads"),"fieldLabel")); self.threads = QSpinBox(); self.threads.setRange(1,64); self.threads.setValue(self.values.get("threads",min(4,os.cpu_count() or 2))); self.threads.setFixedWidth(90); cpu.addWidget(self.threads); cpu.addStretch(); cpu.addWidget(button(self.t("data"),lambda:QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.store.root))),"folder")); ml.addLayout(cpu); layout.addWidget(models)
        credentials, cl = card()
        cl.addWidget(label(self.t("credentials"), "sectionTitle"))
        cl.addWidget(label(self.w("接口需兼容 /audio/speech，使用 Bearer API Key，并直接返回 WAV。", "Use an /audio/speech-compatible endpoint with Bearer API Key authentication and a direct WAV response."), "muted", True))
        self.cloud_endpoint = QLineEdit(self.values.get("cloud_endpoint", ""))
        self.cloud_endpoint.setPlaceholderText("https://example.com/v1/audio/speech")
        self.cloud_model = QLineEdit(self.values.get("cloud_model", ""))
        self.cloud_voice = QLineEdit(self.values.get("cloud_voice", ""))
        self.secret = QLineEdit(self._drafts["secret"])
        self.secret.setObjectName("cloudSecret")
        self.secret.setEchoMode(QLineEdit.EchoMode.Password)
        self.credential_status = label("", "muted")
        field(cl, self.w("HTTPS 接口地址", "HTTPS endpoint"), self.cloud_endpoint)
        field(cl, self.w("模型", "Model"), self.cloud_model)
        field(cl, self.w("音色 ID", "Voice ID"), self.cloud_voice)
        field(cl, "API Key", self.secret)
        self.cloud_endpoint.textChanged.connect(self.cloud_endpoint_changed)
        cl.addWidget(self.credential_status)
        cl.addWidget(label(self.t("secret_hint"), "muted", True))
        actions = row(); self.save_button = button(self.t("save"),self.save_settings,"settings",True); actions.addWidget(self.save_button); actions.addWidget(button(self.t("clear_credentials"),self.clear_credentials)); actions.addStretch(); cl.addLayout(actions); layout.addWidget(credentials); layout.addWidget(label(self.t("about"),"muted",True)); layout.addStretch(); self.pages.addWidget(self.scroll(page))

    def download_light(self):
        model=self.mode.currentData()
        self.start(lambda progress:light.download(self.store,self.cancel_event,progress,model),lambda _:None,'download')

    def import_light(self):
        if self.busy:return
        model=self.mode.currentData()
        path,_=QFileDialog.getOpenFileName(self,self.w('导入当前模型','Import selected model'),'','Paradee (*.onnx)' if model=='paradee' else 'Kokoro (*.tar.bz2)')
        if path:self.start(lambda progress:light.install_archive(self.store,Path(path),self.cancel_event,progress,model),lambda _:None,'import')

    def synthesize_light(self):
        if self.busy:return
        model=self.mode.currentData();text=self.text_editor.toPlainText().strip();voice=self.voice_choice.currentData();speed=float(self.light_speed.currentData());threads=self.threads.value()
        self.start(lambda progress:light.KokoroEngine(self.store,model).synthesize(text,voice,speed,threads,self.cancel_event,progress),self.generated)

    def navigate(self,index):
        self._page = index; self.pages.setCurrentIndex(index)
        for i,b in enumerate(self.nav_buttons): b.setChecked(i==index)
        headers = [(self.w("配音工作台", "Voice studio"),self.w("从一段文字，开始你的声音创作", "Turn your ideas into something worth hearing")),(self.w("我的音色库", "My voices"),self.w("收藏熟悉的声音，创造专属的表达", "Create voices that feel like you")),(self.t("cloud"),self.w("使用自己的云端语音服务生成和试听", "Generate and preview with your own speech service")),(self.t("history"),self.w("每一次灵感，都有声音留存", "All your recordings, ready to revisit")),(self.t("settings"),self.w("把工作室，调成你习惯的样子", "Make this workspace your own"))]
        self.page_title.setText(headers[index][0]); self.page_subtitle.setText(headers[index][1])
    def count_text(self):
        count = len(self.text_editor.toPlainText()); self.char_count.setText(f"{count:,} / 2,000"); self.char_count.setStyleSheet("color:#C65E51;" if count>2000 else "")
    def cloud_endpoint_changed(self):
        self.secret.clear()
        self.update_credential_status()
    def update_credential_status(self):
        endpoint = self.cloud_endpoint.text().strip()
        self.credential_status.setText(self.t("saved_credentials" if endpoint and self.store.credential_path(endpoint).is_file() else "no_credentials"))
    def settings_snapshot(self):
        return {"language":self.language, "cloud_endpoint":self.cloud_endpoint.text().strip(),
                "cloud_model":self.cloud_model.text().strip(), "cloud_voice":self.cloud_voice.text().strip(),
                "speech_language":self.speech_language.currentData(),"threads":self.threads.value(),"mode":self.mode.currentData(),"kokoro_speed":self.light_speed.currentData(),"kokoro_voice":self.voice_choice.currentData() if self.mode.currentData()=="kokoro" else self._voice_selections.get("kokoro",self.values.get("kokoro_voice",3))}
    def change_language(self):
        if self.busy:return
        new = self.ui_language.currentData(); voice = self.voice_choice.currentData(); self._drafts.update(text=self.text_editor.toPlainText(),sample=self.sample_editor.toPlainText(),transcript=self.transcript_editor.toPlainText(),name=self.name.text(),secret=self.secret.text()); self.values = self.settings_snapshot(); self.language = new; self.values["language"] = new; self.store.save_settings(self.values); self._build(); self.voice_choice.setCurrentIndex(max(0,self.voice_choice.findData(voice)))
        if self.preview:self.player_title.setText(self.w("配音已生成", "Your audio is ready")); self.player_hint.setText(self.duration(self.preview))
    def mode_changed(self):
        if hasattr(self,"voice_list"):self.refresh_records(); self.update_model_status()
    def update_model_status(self):
        missing = []
        for key,state in self.model_rows:
            spec = MODELS[key]; file = self.store.root/"models"/spec[0]; ready = file.is_file() and file.stat().st_size==spec[1]; state.setText(self.w("已导入", "Imported") if ready else self.w("待下载", "Not downloaded"))
            if key in ("tokenizer",self.mode.currentData()) and not ready:missing.append(key)
        self.model_status.setText(self.w("当前模式模型已就绪", "Models ready for this mode") if not missing else self.w("当前模式还需要下载模型", "Models are needed for this mode")); self.update_credential_status()
        kokoro=self.mode.currentData() in ("kokoro","paradee")
        if kokoro:self.model_status.setText(self.w("轻量模型已就绪，可离线生成", "Kokoro ready for offline synthesis") if light.ready(self.store,self.mode.currentData()) else self.w(f"尚未下载 · {light.manifest(self.mode.currentData())['bytes']/1e6:.1f} MB", "Model not downloaded"))
        if kokoro:
            self.light_download.setText(self.w(f"下载模型 · {light.manifest(self.mode.currentData())['bytes']/1e6:.1f} MB","Download model"))
            self.light_import.setText(self.w("导入模型 .onnx" if self.mode.currentData()=="paradee" else "导入模型包 .tar.bz2","Import model"))
        self.voice_choice.setProperty("engine",self.mode.currentData())
        self.model_hint.setText(self.w("82M · 中英文 · 100 个中文预置音色；克隆请选择千问。", "82M · Chinese & English presets. Select Qwen for cloning.") if kokoro else self.w("本地离线配音，使用当前千问模型的音色。", "Offline speech with your selected Qwen model."))
        if self.mode.currentData()=="paradee":self.model_hint.setText(self.w("7.88M · 仅英文 · 固定女声 af_heart。数字逐位朗读，未登录词会提示修改。","7.88M · English · af_heart. Digits read individually; unknown words require correction."))
        self.paradee_example.setVisible(self.mode.currentData()=="paradee")
        self.paradee_example.setEnabled(not self.busy)
        for widget in (self.light_speed,self.speed_label,self.light_download,self.light_import):widget.setVisible(kokoro)
        for widget in (self.speech_language,self.speech_language_label,self.manage_voices,self.model_manage):widget.setVisible(not kokoro)
    def refresh_records(self):
        previous=self.voice_choice.property("engine")
        if previous:self._voice_selections[previous]=self.voice_choice.currentData()
        selected=self._voice_selections.get(self.mode.currentData(),self.values.get("kokoro_voice",3) if self.mode.currentData()=="kokoro" else self.voice_choice.currentData())
        self.voice_choice.setProperty("engine",self.mode.currentData())
        self.voices = self.store.records("voices"); self.history = self.store.records("history"); self.voice_list.clear()
        for v in self.voices:
            kind = self.w("完整克隆", "Full clone") if v.get("prompt")=="prompt.json" else self.w("音色克隆", "Voice clone"); item = QListWidgetItem(f"{v['name']}\n{kind}  ·  {v['duration']:.1f}s"); item.setIcon(icon("voices",size=28)); self.voice_list.addItem(item)
        self.voice_total.setText(self.w(f"{len(self.voices)} 个音色",f"{len(self.voices)} voices")); self.voice_empty.setVisible(not self.voices); self.voice_choice.clear()
        if self.mode.currentData() in ("kokoro","paradee"):
            for v in sorted(light.manifest(self.mode.currentData())["voices"],key=lambda v:(v["language"]!="zh",v["id"])):self.voice_choice.addItem(v["name"]+" · "+v["code"],v["id"])
        elif self.mode.currentData()=="custom":
            path = self.store.root/"models"/MODELS["custom"][0]
            try:self.presets = model_speakers(path) if path.exists() else []
            except Exception:self.presets = []
            for name in self.presets:self.voice_choice.addItem(name,"preset:"+name)
            if not self.presets:self.voice_choice.addItem(self.w("先导入预置音色模型", "Import the preset model first"),None)
        else:
            self.voice_choice.addItem(self.t("default_voice"),None)
            for v in self.voices:self.voice_choice.addItem(v["name"],v["id"])
        self.voice_choice.setCurrentIndex(max(0,self.voice_choice.findData(selected))); self.history_list.setRowCount(len(self.history)); self.history_total.setText(self.w(f"{len(self.history)} 条作品",f"{len(self.history)} recordings")); self.history_empty.setVisible(not self.history)
        for i,v in enumerate(self.history):
            for j,text in enumerate([time.strftime("%m-%d  %H:%M",time.localtime(v["created"])),v["voice"],v["text"].replace("\n"," "),self.duration(self.store.record_path("history",v["id"])/"audio.wav")]):self.history_list.setItem(i,j,QTableWidgetItem(text))
            self.history_list.setRowHeight(i,61)
    def safe(self,callback):
        try:return callback()
        except Exception as error:self.show_error(error)
    def show_error(self,error):
        text = self.t(error.key) if isinstance(error,StudioError) else self.t("unexpected")
        if isinstance(error,StudioError) and error.detail:text += "  "+error.detail
        self.notice_text.setText(text); self.notice.show(); self.status.setText(text)
    def save_settings(self):
        def save():
            endpoint = self.cloud_endpoint.text().strip()
            if endpoint or self.secret.text().strip():
                cloud_url(endpoint)
            if self.secret.text().strip():
                self.store.save_secret(endpoint, self.secret.text())
                self.secret.clear()
            self.values = self.settings_snapshot(); self.store.save_settings(self.values); self.update_model_status(); self.status.setText(self.w("设置已保存", "Settings saved"))
        return self.safe(save)
    def clear_credentials(self):
        if self.busy and self._active_kind == "cloud":return
        if QMessageBox.question(self,self.t("clear_credentials"),self.w("删除当前地址保存的凭据？", "Remove the credentials for this endpoint?"))==QMessageBox.StandardButton.Yes:self.safe(lambda:(self.store.clear_secret(self.cloud_endpoint.text().strip()),self.secret.clear(),self.update_credential_status()))
    def start(self,callback,success,kind="local"):
        if self.busy:return
        self.busy = True; self._active_kind = kind; self.cancel_event.clear(); self.stop_playback(); self.notice.hide(); self.progress.show(); self.cancel_button.show(); self.status.setText(self.t("working")); self._outcome = None; self._success = success
        for w in self.controls:w.setEnabled(False)
        self.ui_language.setEnabled(False); self.save_button.setEnabled(kind!="cloud"); self._thread = QThread(self); self._operation = Operation(callback); self._operation.moveToThread(self._thread)
        self._thread.started.connect(self._operation.run); self._operation.progress.connect(self.report_progress); self._operation.success.connect(self.receive_success); self._operation.error.connect(self.receive_error); self._operation.finished.connect(self._thread.quit); self._operation.finished.connect(self._operation.deleteLater); self._thread.finished.connect(self.complete_job); self._thread.finished.connect(self._thread.deleteLater); self._thread.start()
    @Slot(str)
    def report_progress(self,text):self.status.setText(self.t("working")+"  "+str(text))
    @Slot(object)
    def receive_success(self,result):self._outcome = (True,result)
    @Slot(object)
    def receive_error(self,error):self._outcome = (False,error)
    @Slot()
    def complete_job(self):
        # finished is emitted just before the OS thread returns. Join before releasing
        # its Python wrappers so a rapid next job cannot destroy a running QThread.
        if self._thread is not None:self._thread.wait()
        self.busy = False; self._thread = self._operation = None; self.progress.hide(); self.cancel_button.hide(); self.ui_language.setEnabled(True); self.save_button.setEnabled(True)
        for w in self.controls:w.setEnabled(True)
        if self._outcome and self._outcome[0]:self.status.setText(self.t("done")); self.safe(lambda:self._success(self._outcome[1]))
        elif self._outcome:self.show_error(self._outcome[1])
        self.update_model_status()
    def synthesize(self):
        if self.busy:return
        if self.mode.currentData() in ("kokoro","paradee"):
            self.store.save_settings(self.settings_snapshot());self.synthesize_light();return
        def prepare():
            s,text,selected = self.settings_snapshot(),self.text_editor.toPlainText(),self.voice_choice.currentData(); voice = next((v for v in self.voices if v["id"]==selected),None) if s["mode"]=="base" else None; speaker = (selected or "").removeprefix("preset:") if s["mode"]=="custom" else ""; self.store.save_settings(s)
            self.start(lambda progress:self.engine.synthesize(text,s["speech_language"],s["mode"],voice,speaker,s["threads"],self.cancel_event,progress),self.generated)
        self.safe(prepare)
    def generated(self,record):
        self.refresh_records(); self.preview = self.store.record_path("history",record["id"])/"audio.wav"; self.player_title.setText(self.w("配音已生成", "Your audio is ready")); self.player_hint.setText(f"{record['voice']}  ·  {self.duration(self.preview)}"); self.play_button.setEnabled(True); self.export_button.setEnabled(True)
    def download(self):
        if self.mode.currentData() in ("kokoro","paradee"):self.download_light();return
        mode = self.mode.currentData(); self.start(lambda p:download_models(self.store,mode,self.cancel_event,p),lambda _:self.refresh_records(),"download")
    def import_model_files(self):
        paths,_ = QFileDialog.getOpenFileNames(self,self.t("import_models"),"","GGUF (*.gguf)")
        if paths:self.start(lambda _:import_models(self.store,[Path(p) for p in paths],self.cancel_event),lambda _:self.refresh_records(),"import")
    def import_text(self):
        path,_ = QFileDialog.getOpenFileName(self,self.w("导入配音文案", "Import script"),"","Text (*.txt)")
        if path:self.safe(lambda:self.text_editor.setPlainText(Path(path).read_text(encoding="utf-8-sig")))
    def import_voice(self):
        path,_ = QFileDialog.getOpenFileName(self,self.t("import_voice"),"","PCM16 WAV (*.wav)")
        if path:
            name,transcript,threads = self.name.text().strip() or Path(path).stem,self.transcript_editor.toPlainText(),self.threads.value(); self.start(lambda _:self.engine.clone(Path(path),name,transcript,threads,self.cancel_event),self.voice_created,"clone")
    def voice_created(self,record):
        self.mode.setCurrentIndex(self.mode.findData("base")); self.refresh_records(); self.voice_choice.setCurrentIndex(max(0,self.voice_choice.findData(record["id"]))); self.navigate(1)
    def selection(self,group):
        index = self.voice_list.currentRow() if group=="voices" else self.history_list.currentRow(); records = self.voices if group=="voices" else self.history
        if not 0<=index<len(records):raise StudioError("choose_record")
        return records[index]
    def use_voice(self):
        def use():
            v = self.selection("voices"); self.mode.setCurrentIndex(self.mode.findData("base")); self.refresh_records(); self.voice_choice.setCurrentIndex(self.voice_choice.findData(v["id"])); self.navigate(0)
        self.safe(use)
    def rename_voice(self):
        def rename():
            v = self.selection("voices"); name,ok = QInputDialog.getText(self,self.t("rename"),self.t("name"),text=v["name"])
            if ok:
                if not 1<=len(name.strip())<=100:raise StudioError("voice_name")
                v["name"] = name.strip(); write_json(self.store.record_path("voices",v["id"])/"record.json",v); self.refresh_records()
        self.safe(rename)
    def delete_record(self,group):
        if self.busy:return
        def delete():
            v = self.selection(group)
            if QMessageBox.question(self,self.t("delete"),self.t("delete_confirm"),QMessageBox.StandardButton.Yes|QMessageBox.StandardButton.No,QMessageBox.StandardButton.No)==QMessageBox.StandardButton.Yes:self.stop_playback(); self.store.delete_record(group,v["id"]); self.refresh_records()
        self.safe(delete)
    def generate_sample(self):
        if self.busy:return
        def prepare():
            config = self.settings_snapshot()
            cloud_url(config["cloud_endpoint"])
            if self.secret.text().strip():
                self.store.save_secret(config["cloud_endpoint"], self.secret.text())
                self.secret.clear()
            self.store.save_settings(config)
            text = self.sample_editor.toPlainText()
            self.sample_result = None
            self.sample_status.clear()
            self.start(lambda _: self.cloud.sample(config, text, self.cancel_event), self.sample_ready, "cloud")
        self.safe(prepare)
    def sample_ready(self, path):
        self.sample_result = path
        self.sample_status.setText(self.w("已生成，可以试听。", "Ready to preview."))
    @staticmethod
    def duration(path):
        try:
            with wave.open(str(path),"rb") as a:seconds = round(a.getnframes()/a.getframerate()); return f"{seconds//60:02d}:{seconds%60:02d}"
        except (OSError,wave.Error):return "—"
    def play_file(self,path):self.safe(lambda:winsound.PlaySound(str(path),winsound.SND_FILENAME|winsound.SND_ASYNC|winsound.SND_NODEFAULT))
    def stop_playback(self):winsound.PlaySound(None,0)
    def play_preview(self):
        if self.preview:self.play_file(self.preview)
    def play_reference(self):self.safe(lambda:self.play_file(self.store.record_path("voices",self.selection("voices")["id"])/"reference.wav"))
    def play_history(self):self.safe(lambda:self.play_file(self.store.record_path("history",self.selection("history")["id"])/"audio.wav"))
    def play_sample(self):
        if self.sample_result:self.play_file(self.sample_result)
        else:self.show_error(StudioError("choose_record"))
    def export_file(self,source):
        target,_ = QFileDialog.getSaveFileName(self,self.t("export"),"Shengya-voice.wav","WAV (*.wav)")
        if target and Path(target).resolve()!=source.resolve():shutil.copyfile(source,target)
    def export_preview(self):
        if self.preview:self.safe(lambda:self.export_file(self.preview))
    def export_history(self):self.safe(lambda:self.export_file(self.store.record_path("history",self.selection("history")["id"])/"audio.wav"))
    def closeEvent(self,event):
        if self.busy:self.notice_text.setText(self.t("close_busy")); self.notice.show(); event.ignore()
        else:self.stop_playback(); event.accept()
    def resizeEvent(self,event):
        if hasattr(self,"workspace"):self.workspace.setFixedWidth(min(1700,max(700,event.size().width()-202)))
        super().resizeEvent(event)

def main():
    app = application(); window = Studio(); window.show(); return app.exec()
