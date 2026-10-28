"""GD Recorder: lightweight Geometry Dash capture and run library for Windows."""
from __future__ import annotations

import ctypes
import ctypes.wintypes
import datetime as dt
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk
from PIL import Image, ImageEnhance, ImageGrab, ImageTk
from tesserocr import PyTessBaseAPI, PSM, RIL

APP_NAME = "GD Recorder"
APPDATA = Path(os.environ.get("APPDATA", Path.home())) / "GD Recorder"
MEDIA_DIR = Path.home() / "Videos" / "GD Recorder"
RUNS_FILE = APPDATA / "runs.json"
ENGINE_DIR = APPDATA / "capture-engine"
ENGINE_PATH = ENGINE_DIR / "wcap-x64.exe"
SETTINGS_FILE = APPDATA / "settings.json"


def bundled_engine() -> Path:
    root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return root / "wcap-x64.exe"


class GDRecorder:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("GD Recorder")
        root.geometry("900x700")
        root.minsize(760, 600)
        root.configure(bg="#10141f")
        self.style = ttk.Style()
        self.style.theme_use("clam")
        self.style.configure("TFrame", background="#10141f")
        self.style.configure("Card.TFrame", background="#191f2e")
        self.style.configure("TLabel", background="#10141f", foreground="#f1f4fa", font=("Segoe UI", 10))
        self.style.configure("Card.TLabel", background="#191f2e", foreground="#f1f4fa", font=("Segoe UI", 10))
        self.style.configure("Title.TLabel", font=("Segoe UI Semibold", 22), foreground="#ffffff")
        self.style.configure("Sub.TLabel", foreground="#a8b1c4")
        self.style.configure("TButton", font=("Segoe UI Semibold", 10), padding=(12, 8))
        self.style.configure("Accent.TButton", background="#52d6a0", foreground="#0d1720")
        self.style.map("Accent.TButton", background=[("active", "#75e9b8")])
        self.style.configure("Treeview", background="#191f2e", fieldbackground="#191f2e", foreground="#f1f4fa", rowheight=28)
        self.style.configure("Treeview.Heading", background="#252d40", foreground="#dfe8f7")

        self.events: queue.Queue = queue.Queue()
        self.runs = self.load_runs()
        self.session_active = False
        self.armed = False
        self.engine_proc: subprocess.Popen | None = None
        self.current_attempt: dict | None = None
        self.last_percent: int | None = None
        self.reset_hits = 0
        self.session_thread: threading.Thread | None = None
        self.pending_saves = 0
        self.ocr_region = self.load_settings().get("ocr_region")
        self.pending_level = ""
        self.pending_start = 0
        self.pending_startpos = False
        self.hotkey_thread = None
        self.close_when_stopped = False

        self.level_var = tk.StringVar(value="")
        self.start_var = tk.StringVar(value="0")
        self.mark_var = tk.StringVar(value="")
        self.query_var = tk.StringVar(value="")
        self.status_var = tk.StringVar(value="Arm capture · F10 starts/stops · progress HUD is detected automatically")
        self.detect_var = tk.StringVar(value="Detected: —")
        self.startpos_var = tk.BooleanVar(value=False)
        self.fps_var = tk.StringVar(value="30")

        self.build_ui()
        self.refresh_runs()
        self.root.after(150, self.poll_events)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.start_hotkeys()
        self.prepare_engine()

    def build_ui(self):
        outer = ttk.Frame(self.root, padding=20)
        outer.pack(fill="both", expand=True)
        top = ttk.Frame(outer)
        top.pack(fill="x", pady=(0, 16))
        ttk.Label(top, text="GD Recorder", style="Title.TLabel").pack(side="left")
        ttk.Label(top, text="Runs, bests, and percentage playback", style="Sub.TLabel").pack(side="left", padx=14, pady=(9, 0))

        card = ttk.Frame(outer, style="Card.TFrame", padding=16)
        card.pack(fill="x", pady=(0, 14))
        ttk.Label(card, text="RECORD A RUN", style="Card.TLabel", font=("Segoe UI Semibold", 12)).grid(row=0, column=0, sticky="w", columnspan=6, pady=(0, 10))
        ttk.Label(card, text="Level", style="Card.TLabel").grid(row=1, column=0, sticky="w")
        self.level_entry = ttk.Entry(card, textvariable=self.level_var, width=27)
        self.level_entry.grid(row=2, column=0, sticky="ew", padx=(0, 12))
        ttk.Label(card, text="Starting %", style="Card.TLabel").grid(row=1, column=1, sticky="w")
        ttk.Entry(card, textvariable=self.start_var, width=10).grid(row=2, column=1, sticky="w", padx=(0, 14))
        ttk.Checkbutton(card, text="Startpos run", variable=self.startpos_var).grid(row=2, column=2, sticky="w", padx=(0, 14))
        ttk.Label(card, text="FPS", style="Card.TLabel").grid(row=1, column=3, sticky="w")
        ttk.Combobox(card, textvariable=self.fps_var, values=("30", "60"), width=5, state="readonly").grid(row=2, column=3, sticky="w", padx=(0, 14))
        self.record_btn = ttk.Button(card, text="●  Arm capture  ·  F10 in game", style="Accent.TButton", command=self.toggle_recording)
        self.record_btn.grid(row=2, column=4, sticky="w")
        ttk.Label(card, text="Arm capture, switch to Geometry Dash, then press F10. The app automatically finds the progress HUD, tracks it, and saves each attempt as its own MP4.", style="Card.TLabel", wraplength=740).grid(row=3, column=0, columnspan=6, sticky="w", pady=(12, 4))
        ttk.Label(card, text="Mark %", style="Card.TLabel").grid(row=4, column=0, sticky="w", pady=(5, 0))
        self.mark_entry = ttk.Entry(card, textvariable=self.mark_var, width=10)
        self.mark_entry.grid(row=5, column=0, sticky="w")
        self.mark_btn = ttk.Button(card, text="Mark progress", command=self.add_marker, state="disabled")
        self.mark_btn.grid(row=5, column=1, sticky="w", padx=(0, 8))
        ttk.Button(card, text="Manual HUD fallback", command=self.pick_ocr_region).grid(row=5, column=2, sticky="w", padx=(0, 8))
        ttk.Label(card, textvariable=self.detect_var, style="Card.TLabel").grid(row=5, column=3, sticky="w", padx=4)
        ttk.Button(card, text="Open video folder", command=self.open_media_folder).grid(row=5, column=4, sticky="e")
        card.columnconfigure(0, weight=1)
        card.columnconfigure(5, weight=1)

        search = ttk.Frame(outer, style="Card.TFrame", padding=16)
        search.pack(fill="x", pady=(0, 14))
        ttk.Label(search, text="FIND A PERCENTAGE OR PLAY A RANGE", style="Card.TLabel", font=("Segoe UI Semibold", 12)).pack(anchor="w", pady=(0, 8))
        row = ttk.Frame(search, style="Card.TFrame")
        row.pack(fill="x")
        self.query_entry = ttk.Entry(row, textvariable=self.query_var, width=22)
        self.query_entry.pack(side="left")
        self.query_entry.bind("<Return>", lambda _e: self.search_query())
        ttk.Button(row, text="Find / play", command=self.search_query).pack(side="left", padx=8)
        ttk.Label(row, text="Type 23 to find it, or 5-23 to play a startpos segment.", style="Card.TLabel").pack(side="left", padx=8)

        table_frame = ttk.Frame(outer)
        table_frame.pack(fill="both", expand=True)
        ttk.Label(table_frame, text="YOUR RUNS & BESTS", font=("Segoe UI Semibold", 12)).pack(anchor="w", pady=(0, 8))
        cols = ("level", "best", "from", "date", "markers")
        self.tree = ttk.Treeview(table_frame, columns=cols, show="headings", selectmode="browse")
        for col, label, width in [("level", "Level", 260), ("best", "Best", 90), ("from", "Run type", 100), ("date", "Recorded", 160), ("markers", "Markers", 190)]:
            self.tree.heading(col, text=label)
            self.tree.column(col, width=width, anchor="w")
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<Double-1>", lambda _e: self.open_selected())
        ttk.Label(outer, textvariable=self.status_var, style="Sub.TLabel").pack(anchor="w", pady=(10, 0))

    def load_runs(self):
        try:
            data = json.loads(RUNS_FILE.read_text(encoding="utf-8"))
            return data if isinstance(data, list) else []
        except (OSError, json.JSONDecodeError):
            return []

    def load_settings(self):
        try:
            value = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, json.JSONDecodeError):
            return {}

    def save_settings(self):
        APPDATA.mkdir(parents=True, exist_ok=True)
        SETTINGS_FILE.write_text(json.dumps({"ocr_region": self.ocr_region}), encoding="utf-8")

    def pick_ocr_region(self):
        if self.session_active or self.armed:
            messagebox.showinfo("Stop capture first", "Choose the HUD area before arming a recording session.")
            return
        self.root.withdraw()
        self.root.after(350, self._show_region_picker)

    def _show_region_picker(self):
        try:
            screenshot = ImageGrab.grab()
        except Exception as error:
            self.root.deiconify()
            messagebox.showerror("Screen capture unavailable", str(error))
            return
        width, height = screenshot.size
        overlay = tk.Toplevel(self.root)
        overlay.overrideredirect(True)
        overlay.geometry(f"{width}x{height}+0+0")
        overlay.attributes("-topmost", True)
        canvas = tk.Canvas(overlay, width=width, height=height, highlightthickness=0, cursor="crosshair")
        canvas.pack(fill="both", expand=True)
        photo = ImageTk.PhotoImage(screenshot)
        canvas.create_image(0, 0, image=photo, anchor="nw")
        canvas.image = photo
        canvas.create_rectangle(10, 10, 620, 58, fill="#10141f", outline="#52d6a0", width=2)
        canvas.create_text(24, 34, anchor="w", fill="white", font=("Segoe UI", 15, "bold"), text="Drag a box around Geometry Dash's percentage, then release. Esc cancels.")
        state = {"start": None, "rect": None}

        def on_press(event):
            state["start"] = (event.x, event.y)
            state["rect"] = canvas.create_rectangle(event.x, event.y, event.x, event.y, outline="#52d6a0", width=3)

        def on_move(event):
            if state["start"] and state["rect"]:
                x0, y0 = state["start"]
                canvas.coords(state["rect"], x0, y0, event.x, event.y)

        def on_release(event):
            if not state["start"]:
                return
            x0, y0 = state["start"]
            region = [min(x0, event.x), min(y0, event.y), max(x0, event.x), max(y0, event.y)]
            overlay.destroy()
            self.root.deiconify()
            if region[2] - region[0] < 25 or region[3] - region[1] < 18:
                messagebox.showinfo("Area too small", "Select a larger box around the percentage text.")
                return
            self.ocr_region = region
            self.save_settings()
            detected = self.preview_ocr(screenshot.crop(tuple(region)))
            self.detect_var.set(f"Preview: {detected}%" if detected is not None else "Preview: no % found")
            self.status_var.set(f"HUD area saved ({region[2] - region[0]} × {region[3] - region[1]}). Preview should match the game HUD.")

        canvas.bind("<ButtonPress-1>", on_press)
        canvas.bind("<B1-Motion>", on_move)
        canvas.bind("<ButtonRelease-1>", on_release)
        overlay.bind("<Escape>", lambda _e: (overlay.destroy(), self.root.deiconify()))
        overlay.focus_force()

    @staticmethod
    def prepare_ocr_image(image: Image.Image) -> Image.Image:
        image = image.convert("L")
        image = image.resize((max(1, image.width * 3), max(1, image.height * 3)), Image.Resampling.LANCZOS)
        return ImageEnhance.Contrast(image).enhance(1.8)

    @staticmethod
    def recognize_percent(image: Image.Image, api: PyTessBaseAPI) -> int | None:
        api.SetImage(GDRecorder.prepare_ocr_image(image))
        text = api.GetUTF8Text().strip()
        confidence = api.MeanTextConf()
        match = re.fullmatch(r"\s*(100|\d{1,2})\s*%?\s*", text)
        return int(match.group(1)) if match and confidence >= 30 else None

    def preview_ocr(self, image: Image.Image) -> int | None:
        asset_root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
        with PyTessBaseAPI(path=str(asset_root / "tessdata"), lang="eng", psm=PSM.SINGLE_LINE) as api:
            api.SetVariable("tessedit_char_whitelist", "0123456789%")
            return self.recognize_percent(image, api)

    @staticmethod
    def find_hud_percent(api: PyTessBaseAPI) -> tuple[list[int], int] | None:
        """Find a percent in Geometry Dash's upper HUD band and return its screen crop."""
        screenshot = ImageGrab.grab()
        screen_w, screen_h = screenshot.size
        # GD's progress display is along the top. Search once, then OCR only the
        # small detected crop during the rest of the session.
        left = int(screen_w * 0.10)
        right = int(screen_w * 0.90)
        bottom = int(screen_h * 0.32)
        band = screenshot.crop((left, 0, right, bottom)).convert("L")
        scale = 2
        band = band.resize((band.width * scale, band.height * scale), Image.Resampling.LANCZOS)
        band = ImageEnhance.Contrast(band).enhance(1.8)
        api.SetPageSegMode(PSM.SPARSE_TEXT)
        api.SetImage(band)
        api.Recognize()
        iterator = api.GetIterator()
        candidates = []
        if iterator:
            while True:
                try:
                    text = iterator.GetUTF8Text(RIL.TEXTLINE)
                except RuntimeError:
                    text = ""
                match = re.search(r"(?<!\d)(100|\d{1,2})\s*%", text or "")
                bounds = iterator.BoundingBox(RIL.TEXTLINE)
                confidence = iterator.Confidence(RIL.TEXTLINE)
                if match and bounds and confidence >= 20:
                    x0, y0, x1, y1 = bounds
                    x_mid = (x0 + x1) / (2 * scale) + left
                    y_mid = (y0 + y1) / (2 * scale)
                    score = confidence - abs(x_mid - screen_w / 2) * 25 / screen_w - y_mid * 15 / screen_h
                    candidates.append((score, [x0, y0, x1, y1], int(match.group(1))))
                if not iterator.Next(RIL.TEXTLINE):
                    break
        api.SetPageSegMode(PSM.SINGLE_LINE)
        if not candidates:
            return None
        _, bounds, percent = max(candidates, key=lambda item: item[0])
        x0, y0, x1, y1 = bounds
        pad_x = max(18, (x1 - x0) // (2 * scale))
        pad_y = max(10, (y1 - y0) // (2 * scale))
        region = [
            max(0, left + x0 // scale - pad_x),
            max(0, y0 // scale - pad_y),
            min(screen_w, left + (x1 + scale - 1) // scale + pad_x),
            min(screen_h, (y1 + scale - 1) // scale + pad_y),
        ]
        return region, percent

    def ocr_loop(self):
        asset_root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
        tessdata = asset_root / "tessdata"
        try:
            with PyTessBaseAPI(path=str(tessdata), lang="eng", psm=PSM.SINGLE_LINE) as api:
                api.SetVariable("tessedit_char_whitelist", "0123456789%")
                found = self.find_hud_percent(api)
                if found:
                    region, first_percent = found
                    self.events.put(("ocr_region", region))
                    self.events.put(("ocr", (first_percent, time.monotonic())))
                elif self.ocr_region:
                    region = [int(v) for v in self.ocr_region]
                    self.events.put(("status", "Auto-detect missed the HUD · using saved manual area"))
                else:
                    self.events.put(("ocr_error", "Could not find a percentage in Geometry Dash's upper HUD area. Make sure the game is visible, then stop and retry; you can set a manual HUD area with the fallback button."))
                    return
                while self.session_active:
                    tick = time.monotonic()
                    try:
                        image = ImageGrab.grab(bbox=tuple(region))
                        percent = self.recognize_percent(image, api)
                        self.events.put(("ocr", (percent, tick)))
                    except Exception as error:
                        self.events.put(("ocr_error", str(error)))
                        return
                    time.sleep(max(0.03, 0.20 - (time.monotonic() - tick)))
        except Exception as error:
            self.events.put(("ocr_error", str(error)))

    def save_runs(self):
        APPDATA.mkdir(parents=True, exist_ok=True)
        RUNS_FILE.write_text(json.dumps(self.runs, indent=2), encoding="utf-8")

    def prepare_engine(self):
        APPDATA.mkdir(parents=True, exist_ok=True)
        ENGINE_DIR.mkdir(parents=True, exist_ok=True)
        resource = bundled_engine()
        if not ENGINE_PATH.exists() and resource.exists():
            shutil.copy2(resource, ENGINE_PATH)
        if not ENGINE_PATH.exists():
            messagebox.showerror("Capture engine missing", f"The built-in Windows capture engine was not found:\n{ENGINE_PATH}")
            return
        # wcap uses a small INI next to its executable. It records an active window
        # through Windows.Graphics.Capture and Media Foundation, with H.264 and a 30 FPS cap.
        ini = ENGINE_PATH.with_suffix(".ini")
        config = (
            "[wcap]\n"
            f"OutputFolder={MEDIA_DIR}\n"
            "OpenFolder=0\nFragmentedOutput=1\n"
            "HardwareEncoder=1\nVideoCodec=H264\n"
            f"VideoMaxFramerate={self.fps_var.get()}\nVideoMaxWidth=1920\nVideoMaxHeight=1080\n"
            "CaptureAudio=1\nApplicationLocalAudio=1\n"
            "ShortcutMonitor=0\nShortcutWindow=167772204\nShortcutRect=0\n"
        )
        ini.write_text(config, encoding="utf-16")

    def ensure_engine_running(self):
        if self.engine_proc and self.engine_proc.poll() is None:
            return True
        self.prepare_engine()
        if not ENGINE_PATH.exists():
            return False
        try:
            MEDIA_DIR.mkdir(parents=True, exist_ok=True)
            self.engine_proc = subprocess.Popen(
                [str(ENGINE_PATH)], cwd=ENGINE_DIR,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            time.sleep(0.4)
            return self.engine_proc.poll() is None
        except OSError as error:
            messagebox.showerror("Could not start capture engine", str(error))
            return False

    def send_engine_hotkey(self):
        # wcap default shortcut configured above: Ctrl+Win+Print Screen (monitor).
        user32 = ctypes.windll.user32
        keyup = 0x0002
        for key, flags in ((0x11, 0), (0x5B, 0), (0x2C, 0), (0x2C, keyup), (0x5B, keyup), (0x11, keyup)):
            user32.keybd_event(key, 0, flags, 0)
        time.sleep(0.15)

    def open_media_folder(self):
        MEDIA_DIR.mkdir(parents=True, exist_ok=True)
        os.startfile(MEDIA_DIR)

    def start_hotkeys(self):
        if os.name != "nt":
            return
        self.hotkey_thread = threading.Thread(target=self._hotkey_loop, daemon=True)
        self.hotkey_thread.start()

    def _hotkey_loop(self):
        user32 = ctypes.windll.user32
        # F10 toggles the session; F7 opens a manual marker; F6 selects the HUD crop.
        if not user32.RegisterHotKey(None, 1, 0, 0x79):
            return
        user32.RegisterHotKey(None, 2, 0, 0x76)
        user32.RegisterHotKey(None, 3, 0, 0x75)
        msg = ctypes.wintypes.MSG()
        while True:
            result = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
            if result <= 0:
                break
            if msg.message == 0x0312:
                self.events.put(("hotkey", msg.wParam))
        user32.UnregisterHotKey(None, 1)
        user32.UnregisterHotKey(None, 2)
        user32.UnregisterHotKey(None, 3)

    def poll_events(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "hotkey":
                    if value == 1:
                        self.toggle_recording()
                    elif value == 2:
                        self.root.deiconify()
                        self.root.lift()
                        self.mark_var.set("")
                        self.mark_entry.focus_set()
                    elif value == 3:
                        self.pick_ocr_region()
                elif kind == "status":
                    self.status_var.set(value)
                elif kind == "error":
                    self.status_var.set(value)
                    messagebox.showerror("GD Recorder", value)
                elif kind == "stopped":
                    self.finish_attempt(value)
                elif kind == "ocr":
                    percent, tick = value
                    self.handle_ocr(percent, tick)
                elif kind == "ocr_region":
                    self.ocr_region = value
                    self.save_settings()
                    self.status_var.set("Progress HUD found automatically · tracking percentage")
                elif kind == "ocr_error":
                    self.status_var.set(f"OCR error: {value}")
                    messagebox.showerror("Percentage detection failed", value)
                elif kind == "attempt_error":
                    self.pending_saves = max(0, self.pending_saves - 1)
                    self.status_var.set(value)
                    messagebox.showerror("Attempt could not be saved", value)
                    self.finish_stopped_session()
        except queue.Empty:
            pass
        self.root.after(150, self.poll_events)

    @staticmethod
    def percentage(text: str) -> int:
        value = int(text.strip().removesuffix("%"))
        if not 0 <= value <= 100:
            raise ValueError("Percentage must be from 0 to 100.")
        return value

    def toggle_recording(self):
        if self.session_active:
            self.stop_session()
            return
        if self.armed:
            title = self.foreground_title()
            if "gd recorder" in title.lower():
                self.status_var.set("Switch to Geometry Dash, then press F10 to start recording.")
                return
            self.armed = False
            self.session_active = True
            self.last_percent = self.pending_start
            self.reset_hits = 0
            self.record_btn.configure(text="■  Stop session  ·  F10 in game")
            self.mark_btn.configure(state="normal")
            self.begin_attempt(self.pending_start, time.monotonic())
            self.session_thread = threading.Thread(target=self.ocr_loop, daemon=True)
            self.session_thread.start()
            self.status_var.set(f"Auto recording {self.pending_level} · watching for progress resets")
            return
        level = self.level_var.get().strip()
        if not level:
            messagebox.showinfo("Level name needed", "Enter the level name before starting a run.")
            self.level_entry.focus_set()
            return
        try:
            start_percent = self.percentage(self.start_var.get())
        except ValueError as e:
            messagebox.showerror("Invalid starting percentage", str(e))
            return
        self.pending_level = level
        self.pending_start = start_percent
        self.pending_startpos = bool(self.startpos_var.get()) or start_percent > 0
        if not self.ensure_engine_running():
            messagebox.showerror("Capture engine could not start", f"Could not start Windows capture engine at:\n{ENGINE_PATH}")
            return
        self.armed = True
        self.record_btn.configure(text="Armed · F10 in Geometry Dash")
        self.status_var.set("Switch to Geometry Dash and press F10. Live OCR will track percentages and split attempts.")

    @staticmethod
    def foreground_title():
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        title = ctypes.create_unicode_buffer(512)
        user32.GetWindowTextW(hwnd, title, len(title))
        return title.value

    def add_marker(self):
        if not self.session_active or not self.current_attempt:
            return
        try:
            percent = self.percentage(self.mark_var.get())
        except ValueError as e:
            messagebox.showerror("Invalid percentage", str(e))
            return
        elapsed = max(0.0, time.monotonic() - self.current_attempt["started_at"])
        if percent < self.current_attempt["markers"][-1]["percent"]:
            messagebox.showinfo("Progress has not reached that marker yet", "Manual markers must move forward within an attempt.")
            return
        self.add_attempt_marker(self.current_attempt, percent, elapsed)
        self.mark_var.set("")
        self.status_var.set(f"Manual marker: {percent}% at {elapsed:.1f}s")

    def add_attempt_marker(self, attempt: dict, percent: int, elapsed: float):
        markers = attempt["markers"]
        if not markers or markers[-1]["percent"] < percent:
            markers.append({"percent": percent, "time": round(elapsed, 3)})
        attempt["max_percent"] = max(attempt.get("max_percent", 0), percent)

    def handle_ocr(self, percent: int | None, tick: float):
        self.detect_var.set(f"Detected: {percent}%" if percent is not None else "Detected: —")
        if not self.session_active or percent is None:
            return
        attempt = self.current_attempt
        if attempt is None:
            if percent <= self.pending_start + 2:
                self.begin_attempt(self.pending_start, time.monotonic())
                self.last_percent = self.pending_start
            return

        previous = self.last_percent
        is_reset = (
            percent <= attempt["start_percent"] + 2
            and attempt["max_percent"] >= attempt["start_percent"] + 5
        )
        self.reset_hits = self.reset_hits + 1 if is_reset else 0
        if self.reset_hits >= 2:
            self.close_attempt()
            self.begin_attempt(self.pending_start, time.monotonic())
            self.last_percent = self.pending_start
            self.reset_hits = 0
            return

        if percent >= attempt["markers"][-1]["percent"] and percent != previous:
            elapsed = max(0.0, tick - attempt["started_at"])
            self.add_attempt_marker(attempt, percent, elapsed)
        self.last_percent = percent
        if percent >= 100:
            self.close_attempt()
            self.last_percent = None

    def begin_attempt(self, start_percent: int, started_at: float):
        baseline = {str(path.resolve()) for path in MEDIA_DIR.glob("*.mp4")}
        attempt = {
            "id": str(time.time_ns()),
            "level": self.pending_level,
            "date": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
            "started_at": started_at,
            "wall_started": time.time(),
            "start_percent": start_percent,
            "max_percent": start_percent,
            "startpos": self.pending_startpos,
            "markers": [{"percent": start_percent, "time": 0.0}],
            "baseline": baseline,
            "path": None,
            "fps": int(self.fps_var.get()),
        }
        self.current_attempt = attempt
        self.send_engine_hotkey()
        threading.Thread(target=self.watch_attempt_file, args=(attempt,), daemon=True).start()

    def watch_attempt_file(self, attempt: dict):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            files = [path for path in MEDIA_DIR.glob("*.mp4") if str(path.resolve()) not in attempt["baseline"]]
            if files:
                candidate = min(files, key=lambda p: abs(p.stat().st_mtime - attempt["wall_started"]))
                attempt["path"] = str(candidate)
                return
            time.sleep(0.1)

    def close_attempt(self):
        attempt = self.current_attempt
        if attempt is None:
            return
        attempt["ended_at"] = time.monotonic()
        attempt["max_percent"] = max(attempt["max_percent"], attempt["markers"][-1]["percent"])
        self.current_attempt = None
        self.send_engine_hotkey()
        self.pending_saves += 1

        def finalize():
            deadline = time.monotonic() + 25
            path = attempt.get("path")
            previous_size = -1
            stable = 0
            while time.monotonic() < deadline:
                if not path:
                    files = [p for p in MEDIA_DIR.glob("*.mp4") if str(p.resolve()) not in attempt["baseline"]]
                    if files:
                        path = str(min(files, key=lambda p: abs(p.stat().st_mtime - attempt["wall_started"])))
                        attempt["path"] = path
                if path and Path(path).exists():
                    size = Path(path).stat().st_size
                    if size > 2048 and size == previous_size:
                        stable += 1
                    else:
                        stable = 0
                    previous_size = size
                    if stable >= 3:
                        attempt["path"] = path
                        self.events.put(("stopped", attempt))
                        return
                time.sleep(0.5)
            self.events.put(("attempt_error", "Windows capture did not finish this attempt as an MP4. Check the capture engine tray icon."))
        threading.Thread(target=finalize, daemon=True).start()

    def stop_session(self):
        self.session_active = False
        self.armed = False
        self.mark_btn.configure(state="disabled")
        self.record_btn.configure(text="Finishing attempts…", state="disabled")
        if self.current_attempt:
            self.close_attempt()
        if self.pending_saves:
            self.status_var.set("Session stopped; finishing saved attempts…")
        else:
            self.record_btn.configure(text="●  Arm capture  ·  F10 in game", state="normal")
            self.status_var.set("Capture session stopped.")
            if self.close_when_stopped:
                self.shutdown()

    def finish_attempt(self, attempt: dict):
        self.pending_saves = max(0, self.pending_saves - 1)
        path = Path(attempt["path"])
        if not path.exists() or path.stat().st_size < 2048:
            self.status_var.set("An attempt video could not be found; it was skipped.")
            self.finish_stopped_session()
            return
        run = {key: attempt[key] for key in ("id", "level", "date", "startpos", "markers", "fps")}
        run["path"] = str(path)
        run["best"] = int(attempt["max_percent"])
        run["start_percent"] = int(attempt["start_percent"])
        run["duration"] = max(0.0, attempt.get("ended_at", time.monotonic()) - attempt["started_at"])
        self.runs.append(run)
        self.save_runs()
        self.refresh_runs()
        self.status_var.set(f"Saved {run['level']} attempt {run['best']}% · {path.name}")
        self.finish_stopped_session()

    def finish_stopped_session(self):
        if not self.session_active:
            if self.pending_saves:
                return
            self.record_btn.configure(text="●  Arm capture  ·  F10 in game", state="normal")
            if self.close_when_stopped:
                self.shutdown()

    def shutdown(self):
        if self.engine_proc and self.engine_proc.poll() is None:
            self.engine_proc.terminate()
        self.root.destroy()

    def refresh_runs(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        grouped: dict[tuple, list[dict]] = {}
        for run in self.runs:
            key = (run.get("level", "Unknown"), bool(run.get("startpos")), int(run.get("start_percent", 0)))
            grouped.setdefault(key, []).append(run)
        self.display_groups = {}
        for index, (key, runs) in enumerate(sorted(grouped.items(), key=lambda pair: max(r.get("date", "") for r in pair[1]), reverse=True)):
            runs.sort(key=lambda r: r.get("date", ""), reverse=True)
            level, startpos, _start = key
            best = max((int(r.get("best", max((m.get("percent", 0) for m in r.get("markers", [])), default=0))) for r in runs), default=0)
            date = runs[0].get("date", "")[:16].replace("T", " ")
            marks_text = f"{len(runs)} attempt" + ("s" if len(runs) != 1 else "")
            iid = f"group_{index}"
            self.display_groups[iid] = runs
            run_type = ("Startpos" if startpos else "Normal") + (f" ×{len(runs)}" if len(runs) > 1 else "")
            self.tree.insert("", "end", iid=iid, values=(level, f"{best}%", run_type, date, marks_text))

    def selected_group(self):
        selected = self.tree.selection()
        return self.display_groups.get(selected[0], []) if selected else []

    def choose_run(self, runs: list[dict], title: str = "Choose a run"):
        if not runs:
            return None
        if len(runs) == 1:
            return runs[0]
        choices = []
        for index, run in enumerate(runs, 1):
            best = int(run.get("best", max((m.get("percent", 0) for m in run.get("markers", [])), default=0)))
            date = run.get("date", "")[:16].replace("T", " ")
            choices.append(f"{index}. {best}% · {date}")
        number = simpledialog.askinteger(title, "Enter the run number to open:\n\n" + "\n".join(choices), parent=self.root, minvalue=1, maxvalue=len(runs))
        return runs[number - 1] if number is not None else None

    def open_selected(self):
        run = self.choose_run(self.selected_group())
        if run:
            path = Path(run.get("path", ""))
            if path.exists():
                detail = tk.Toplevel(self.root)
                detail.title(f"{run.get('level', 'Run')} · {run.get('best', 0)}%")
                detail.geometry("480x520")
                detail.configure(bg="#10141f")
                ttk.Label(detail, text=f"{run.get('level', 'Run')} · best {run.get('best', 0)}%", style="Title.TLabel").pack(anchor="w", padx=16, pady=(16, 6))
                ttk.Label(detail, text=f"{run.get('date', '')[:16].replace('T', ' ')} · {'Startpos' if run.get('startpos') else 'Normal'}", style="Sub.TLabel").pack(anchor="w", padx=16, pady=(0, 10))
                box = tk.Text(detail, height=21, bg="#191f2e", fg="#f1f4fa", insertbackground="white", relief="flat", font=("Consolas", 10))
                box.pack(fill="both", expand=True, padx=16, pady=4)
                box.insert("end", "Detected progress and video time\n\n")
                for marker in run.get("markers", []):
                    box.insert("end", f"{int(marker['percent']):>3}%     {float(marker['time']):>7.2f}s\n")
                box.configure(state="disabled")
                ttk.Button(detail, text="Play this attempt", command=lambda p=path: self.open_player(p, 0.0)).pack(anchor="e", padx=16, pady=14)
            else:
                messagebox.showerror("Video missing", f"Could not find:\n{path}")

    @staticmethod
    def estimate_time(markers: list[dict], percent: int) -> float | None:
        points = sorted((m for m in markers if isinstance(m.get("percent"), int) and isinstance(m.get("time"), (int, float))), key=lambda m: m["time"])
        if not points:
            return None
        exact = [p for p in points if p["percent"] == percent]
        if exact:
            return float(exact[0]["time"])
        # Interpolate between adjacent percentage markers. This is an estimate;
        # a marker at the requested percentage is frame-accurate.
        for left, right in zip(points, points[1:]):
            if left["percent"] < percent < right["percent"] and right["percent"] != left["percent"]:
                ratio = (percent - left["percent"]) / (right["percent"] - left["percent"])
                return float(left["time"] + ratio * (right["time"] - left["time"]))
        return None

    def search_query(self):
        query = self.query_var.get().strip()
        if re.fullmatch(r"\d{1,3}-\d{1,3}", query):
            start, end = map(int, query.split("-"))
            if not (0 <= start < end <= 100):
                messagebox.showerror("Invalid range", "Use an increasing range from 0 to 100, such as 5-23.")
                return
            self.play_range(start, end)
            return
        if not re.fullmatch(r"\d{1,3}%?", query):
            messagebox.showinfo("Enter a percentage", "Type a percentage like 23, or a dashed range like 5-23.")
            return
        percent = self.percentage(query.rstrip("%"))
        matches = []
        for run in self.runs:
            value = self.estimate_time(run.get("markers", []), percent)
            if value is not None:
                matches.append((run, value))
        if not matches:
            messagebox.showinfo("No matching percentage", f"No run has a marker or interpolated point at {percent}%. Add progress markers with F7 during a run.")
            return
        matches.sort(key=lambda pair: pair[0].get("date", ""), reverse=True)
        if len(matches) > 1:
            options = [run for run, _seek in matches]
            run = self.choose_run(options, f"Choose the {percent}% run")
            if not run:
                return
            seek = next(value for candidate, value in matches if candidate["id"] == run["id"])
        else:
            run, seek = matches[0]
        path = Path(run.get("path", ""))
        if not path.exists():
            messagebox.showerror("Video missing", str(path))
            return
        self.open_player(path, seek)
        self.status_var.set(f"Playing {percent}% in {run.get('level')} from about {seek:.1f}s")

    def play_range(self, start: int, end: int):
        candidates = []
        for run in self.runs:
            if not run.get("startpos"):
                continue
            points = run.get("markers", [])
            a, b = self.estimate_time(points, start), self.estimate_time(points, end)
            if a is not None and b is not None and b > a:
                candidates.append((run, a, b))
        if not candidates:
            messagebox.showinfo("No startpos range found", f"{start}-{end} only works for startpos runs. Record a startpos attempt and add percentage markers covering both ends of the range.")
            return
        candidates.sort(key=lambda item: item[0].get("date", ""), reverse=True)
        if len(candidates) > 1:
            run = self.choose_run([candidate[0] for candidate in candidates], f"Choose the {start}-{end}% run")
            if not run:
                return
            _run, begin, finish = next(candidate for candidate in candidates if candidate[0]["id"] == run["id"])
        else:
            run, begin, finish = candidates[0]
        source = Path(run.get("path", ""))
        if not source.exists():
            messagebox.showerror("Video missing", str(source))
            return
        self.open_player(source, begin, finish)
        self.status_var.set(f"Playing {start}-{end}% from {run.get('level')} ({begin:.1f}s–{finish:.1f}s)")

    @staticmethod
    def open_player(path: Path, start: float, end: float | None = None):
        APPDATA.mkdir(parents=True, exist_ok=True)
        video_url = json.dumps(path.resolve().as_uri())
        end_value = "null" if end is None else f"{float(end):.3f}"
        html = f'''<!doctype html><meta charset="utf-8"><title>GD Recorder · Playback</title>
<style>body{{margin:0;background:#111;color:white;font:14px Segoe UI}}video{{width:100vw;height:calc(100vh - 28px);background:#000}}p{{margin:4px 12px}}</style>
<video id="v" controls autoplay src={video_url}></video><p>Percentage playback · close this tab when finished.</p>
<script>const v=document.getElementById('v'), a={float(start):.3f}, b={end_value};
v.addEventListener('loadedmetadata',()=>{{v.currentTime=Math.min(a,v.duration);v.play();}});
v.addEventListener('timeupdate',()=>{{if(b!==null&&v.currentTime>=b){{v.pause();v.currentTime=b;}}}});</script>'''
        player = APPDATA / "playback.html"
        player.write_text(html, encoding="utf-8")
        webbrowser.open(player.as_uri())

    def on_close(self):
        if self.session_active:
            if not messagebox.askyesno("Stop recording?", "Stop the current recording and close GD Recorder?"):
                return
            self.close_when_stopped = True
            self.stop_session()
            return
        self.shutdown()


def main():
    APPDATA.mkdir(parents=True, exist_ok=True)
    root = tk.Tk()
    GDRecorder(root)
    root.mainloop()


if __name__ == "__main__":
    main()
