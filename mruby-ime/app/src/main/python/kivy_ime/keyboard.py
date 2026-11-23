"""Kivy keyboard front-end for mruby-ime.

The layout mirrors ``app/src/main/assets/keyboard_layout.rb`` exactly:

* 12-column grid, ``gap_h``/``gap_v`` 2 dp, ``outer`` 3 dp
* ``bar`` 48 dp + ``body`` 240 dp, padding 3 dp left/right/bottom
* HHKB-style light palette (key #E0E0E0, pressed #CCCCCC, seam #999999,
  bar #F5F5F5, ink #000000, muted #666666, chip selected #CCCCCC)
* every key may carry a ``sub_label`` (the swipe-up symbol, printed top-right)
  and ``gestures`` (``up``/``down``/``left``/``right`` → action)

Feedback uses ``kivy.animation`` (the plate sinks and darkens) and
``kivy.core.audio`` (the system key click), per the Kivy docs.
"""

import os
import threading
import time

from kivy.animation import Animation
from kivy.app import App
from kivy.clock import Clock
from kivy.core.audio import SoundLoader
from kivy.core.text import Label as CoreLabel
from kivy.core.text import LabelBase
from kivy.core.window import Window
from kivy.graphics import Color, Line, Rectangle
from kivy.metrics import dp, sp
from kivy.properties import BooleanProperty, NumericProperty
from kivy.uix.anchorlayout import AnchorLayout
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.layout import Layout
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

from kivy_ime.bridge import ImeBridge

# --------------------------------------------------------------------------
# Palette (theme :light in keyboard_layout.rb)
# --------------------------------------------------------------------------
INK = (0.0, 0.0, 0.0, 1.0)
MUTED = (0.4, 0.4, 0.4, 1.0)
BAR_BG = (245 / 255.0, 245 / 255.0, 245 / 255.0, 1.0)
KEY_BG = (224 / 255.0, 224 / 255.0, 224 / 255.0, 1.0)
KEY_PRESSED = (204 / 255.0, 204 / 255.0, 204 / 255.0, 1.0)
SEAM = (153 / 255.0, 153 / 255.0, 153 / 255.0, 1.0)
CHIP_SELECTED = (204 / 255.0, 204 / 255.0, 204 / 255.0, 1.0)
CLEAR = (1.0, 1.0, 1.0, 0.0)

# --------------------------------------------------------------------------
# Fonts: Roboto has no CJK glyphs, so bind a system CJK face.
# --------------------------------------------------------------------------
_CJK_CANDIDATES = (
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/NotoSansCJKsc-Regular.otf",
    "/system/fonts/NotoSansSC-Regular.otf",
    "/system/fonts/DroidSansFallback.ttf",
    "/system/fonts/DroidSansFallbackFull.ttf",
    # desktop (host smoke tests)
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Light.ttc",
)


def _register_cjk_font():
    for path in _CJK_CANDIDATES:
        if not os.path.exists(path):
            continue
        try:
            LabelBase.register(name="IME", fn_regular=path)
            print("kivy-ime: CJK font %s" % path, flush=True)
            return "IME"
        except Exception as exc:  # noqa: BLE001 - font probing must not crash
            print("kivy-ime: font %s failed: %s" % (path, exc), flush=True)
    print("kivy-ime: no CJK font found, using default", flush=True)
    return "Roboto"


FONT_NAME = _register_cjk_font()


# --------------------------------------------------------------------------
# Click sound
# --------------------------------------------------------------------------
class SoundPlayer:
    """Lazily loaded system key click, silently disabled when unavailable."""

    _sound = None
    _tried = False

    PATHS = (
        "/system/media/audio/ui/KeypressStandard.ogg",
        "/system/media/audio/ui/KeypressSpacebar.ogg",
        "/system/media/audio/ui/Effect_Tick.ogg",
    )

    @classmethod
    def _load(cls):
        cls._tried = True
        for path in cls.PATHS:
            if not os.path.exists(path):
                continue
            sound = SoundLoader.load(path)
            if sound:
                sound.volume = 0.6
                cls._sound = sound
                print("kivy-ime: click sound %s" % path, flush=True)
                return
        print("kivy-ime: no click sound available", flush=True)

    @classmethod
    def click(cls):
        if not cls._tried:
            cls._load()
        if cls._sound:
            try:
                cls._sound.play()
            except Exception:  # noqa: BLE001 - audio must never break typing
                pass


# --------------------------------------------------------------------------
# Gesture helpers
# --------------------------------------------------------------------------
def _gesture(spec):
    """Normalise a gesture to ``(action, value)``."""
    if spec is None:
        return None
    if isinstance(spec, str):
        return ("code", spec)
    return (spec.get("action", "code"), spec.get("value"))


# --------------------------------------------------------------------------
# Key
# --------------------------------------------------------------------------
class Key(Widget):
    """One keyboard plate: fill, per-side seams, press animation, gestures."""

    press = NumericProperty(0.0)
    edge_left = BooleanProperty(False)
    edge_top = BooleanProperty(False)

    def __init__(self, label="", value=None, action="code", sub_label="",
                 gestures=None, repeat=False, long_press=None,
                 on_commit=None, font_size=None, **kwargs):
        super().__init__(**kwargs)
        self.value = label if value is None else value
        self.action = action
        self.sub_label = sub_label
        self.gestures = {k: _gesture(v) for k, v in (gestures or {}).items()}
        self.repeat = repeat
        self.long_press = _gesture(long_press)
        self.on_commit = on_commit
        self._down = None
        self._repeat_event = None
        self._long_event = None
        self._long_fired = False

        self._label = Label(
            text=label, font_name=FONT_NAME,
            font_size=font_size or sp(20), bold=True, color=INK,
            halign="center", valign="middle", size_hint=(None, None),
        )
        self._sub = Label(
            text=sub_label, font_name=FONT_NAME, font_size=sp(10),
            color=MUTED, halign="right", valign="top", size_hint=(None, None),
        )
        self.add_widget(self._label)
        self.add_widget(self._sub)

        with self.canvas.before:
            self._bg_color = Color(*KEY_BG)
            self._bg = Rectangle()
        with self.canvas.after:
            Color(*SEAM)
            self._seam_right = Line(width=dp(1))
            self._seam_bottom = Line(width=dp(1))
            self._seam_left = Line(width=dp(1))
            self._seam_top = Line(width=dp(1))

        self.bind(pos=self._sync, size=self._sync, press=self._sync,
                  edge_left=self._sync, edge_top=self._sync)
        self._sub.bind(texture_size=self._sync)
        self._sync()

    # -- layout --------------------------------------------------------
    def _sync(self, *_args):
        inset = self.press * dp(2)
        self._bg.pos = (self.x + inset, self.y + inset)
        self._bg.size = (max(0, self.width - 2 * inset),
                         max(0, self.height - 2 * inset))
        t = self.press
        self._bg_color.rgba = tuple(
            KEY_BG[i] + (KEY_PRESSED[i] - KEY_BG[i]) * t for i in range(3)
        ) + (1.0,)

        self._label.pos = (self.x, self.y)
        self._label.size = (self.width, self.height)
        self._label.text_size = (max(1, self.width - dp(4)), self.height)

        tw, th = self._sub.texture_size
        self._sub.size = (tw, th)
        self._sub.pos = (self.right - tw - dp(4), self.top - th - dp(2))

        self._seam_right.points = [self.right, self.y, self.right, self.top]
        self._seam_bottom.points = [self.x, self.y, self.right, self.y]
        self._seam_left.points = (
            [self.x, self.y, self.x, self.top] if self.edge_left else [])
        self._seam_top.points = (
            [self.x, self.top, self.right, self.top] if self.edge_top else [])

    # -- feedback ------------------------------------------------------
    def _animate(self, target, duration):
        Animation.cancel_all(self, "press")
        Animation(press=target, d=duration).start(self)

    # -- touch ---------------------------------------------------------
    def on_touch_down(self, touch):
        if not self.collide_point(*touch.pos):
            return False
        self._down = (touch.x, touch.y)
        self._long_fired = False
        touch.grab(self)
        self._animate(1.0, 0.05)
        SoundPlayer.click()
        if self.long_press:
            self._long_event = Clock.schedule_once(self._fire_long, 0.6)
        if self.repeat:
            self._fire(("code", self.value))
            self._repeat_event = Clock.schedule_once(self._repeat_start, 0.4)
        return True

    def on_touch_move(self, touch):
        if touch.grab_current is not self:
            return False
        if self._down and (
                abs(touch.x - self._down[0]) > dp(8)
                or abs(touch.y - self._down[1]) > dp(8)):
            self._cancel_long()
            self._stop_repeat()
        return True

    def on_touch_up(self, touch):
        if touch.grab_current is not self:
            return False
        touch.ungrab(self)
        self._cancel_long()
        self._stop_repeat()
        self._animate(0.0, 0.12)
        if self._long_fired or self.repeat:
            return True
        if self._down:
            dx = touch.x - self._down[0]
            dy = touch.y - self._down[1]
            direction = self._direction(dx, dy)
            if direction and direction in self.gestures:
                self._fire(self.gestures[direction])
                return True
        self._fire(("code", self.value) if self.action == "code"
                   else (self.action, self.value))
        return True

    @staticmethod
    def _direction(dx, dy):
        threshold = dp(16)
        if abs(dy) >= abs(dx) and abs(dy) > threshold:
            return "up" if dy > 0 else "down"
        if abs(dx) > threshold:
            return "right" if dx > 0 else "left"
        return None

    # -- timers --------------------------------------------------------
    def _fire_long(self, _dt):
        self._long_event = None
        self._long_fired = True
        self._stop_repeat()
        if self.long_press:
            self._fire(self.long_press)

    def _cancel_long(self):
        if self._long_event is not None:
            self._long_event.cancel()
            self._long_event = None

    def _repeat_start(self, _dt):
        self._repeat_event = Clock.schedule_interval(self._repeat_tick, 0.08)

    def _repeat_tick(self, _dt):
        self._fire(("code", self.value))

    def _stop_repeat(self):
        if self._repeat_event is not None:
            self._repeat_event.cancel()
            self._repeat_event = None

    def _fire(self, action_value):
        if self.on_commit and action_value:
            self.on_commit(action_value[0], action_value[1])


# --------------------------------------------------------------------------
# Candidate chip
# --------------------------------------------------------------------------
class Chip(Widget):
    """Transparent candidate chip; the selected one gets a #CCCCCC plate."""

    def __init__(self, text, selkey=None, selected=False, index=-1,
                 on_select=None, min_width=0, **kwargs):
        super().__init__(size_hint=(None, 1), **kwargs)
        self.text = text
        self.selkey = selkey
        self.selected = selected
        self.index = index
        self.on_select = on_select
        self.min_width = min_width

        shown = ("%s%s" % (selkey, text)) if selkey else text
        self._label = Label(
            text=shown, font_name=FONT_NAME, font_size=sp(24), bold=True,
            color=INK, halign="center", valign="middle", size_hint=(None, None),
        )
        self.add_widget(self._label)

        with self.canvas.before:
            self._bg_color = Color(*(CHIP_SELECTED if selected else CLEAR))
            self._bg = Rectangle()

        self.bind(pos=self._sync, size=self._sync)
        self.width = self._text_width(shown)
        self._sync()

    def _text_width(self, shown):
        try:
            core = CoreLabel(text=shown, font_name=FONT_NAME, font_size=sp(24))
            core.refresh()
            return max(self.min_width, core.texture.size[0] + dp(16))
        except Exception:  # noqa: BLE001 - measurement is cosmetic
            return max(self.min_width, dp(48))

    def _sync(self, *_args):
        self._bg.pos = self.pos
        self._bg.size = self.size
        self._label.pos = (self.x, self.y)
        self._label.size = (self.width, self.height)
        self._label.text_size = (max(1, self.width - dp(6)), self.height)

    def on_touch_down(self, touch):
        if not self.collide_point(*touch.pos):
            return False
        if self.on_select:
            self.on_select(self.index, self.text)
        return True


# --------------------------------------------------------------------------
# Row layout: BoxLayout with size_hint_x proportional to span, plus gaps
# --------------------------------------------------------------------------
class KeyRow(BoxLayout):
    """Horizontal row where each key gets size_hint_x = span / 12.0.
    Gaps are handled by inserting transparent spacer widgets."""
    def __init__(self, entries, gap, columns=12, **kwargs):
        super().__init__(orientation="horizontal", spacing=0, **kwargs)
        self.gap = gap
        self.columns = columns
        self.entries = entries

        # We'll build the row with gap spacers between keys
        # BoxLayout doesn't support per-widget margins, so we add gap widgets
        for i, (key, span) in enumerate(entries):
            key.size_hint_x = span / float(columns)
            self.add_widget(key)
            # Add gap after each key except the last
            if i < len(entries) - 1:
                gap_widget = Widget(size_hint_x=None, width=gap)
                self.add_widget(gap_widget)

    def do_layout(self, *_args):
        # Let BoxLayout do its thing, then fix gap widgets to exact width
        super().do_layout(*_args)
        for child in self.children:
            if isinstance(child, Widget) and child not in [e[0] for e in self.entries]:
                child.width = self.gap
                child.size_hint_x = None


# --------------------------------------------------------------------------
# Layout data — same rows, sub_labels and gestures as keyboard_layout.rb
# --------------------------------------------------------------------------
def _text_row(keys, subs, swipes):
    row = []
    for index, key in enumerate(keys):
        row.append({
            "label": key,
            "value": key,
            "sub": subs[index].strip() if index < len(subs) else "",
            "up": swipes[index] if index < len(swipes) else None,
        })
    return row


ROW_1 = _text_row("1234567890-=", "!@#$%^&*()_+", "!@#$%^&*()_+")
ROW_2 = _text_row("qwertyuiop[]", "          {}", "QWERTYUIOP{}")
ROW_3 = _text_row("asdfghjkl;'\\", "         :\"|", "ASDFGHJKL:\"|")
ROW_4 = _text_row("zxcvbnm,./`", "       <>?~", "ZXCVBNM<>?~") + [
    {"label": "\u2b05", "value": None, "action": "backspace", "repeat": True},
]

ROW_5 = [
    {"label": "中", "action": "toggle_mode", "span": 2},
    {"label": "", "action": "space", "span": 8, "gestures": {
        "down": {"action": "toggle_mode"},
        "left": {"action": "cursor_left"},
        "right": {"action": "cursor_right"},
    }},
    {"label": "\u23ce", "action": "enter", "span": 2, "gestures": {
        "up": {"action": "combo", "value": "shift+enter"},
    }},
]

ROWS = [ROW_1, ROW_2, ROW_3, ROW_4, ROW_5]

ENGLISH_BAR = [
    {"label": "TAB", "action": "key_event", "value": "tab"},
    {"label": "", "action": "noop"},
    {"label": "^A", "action": "clipboard", "value": "select_all"},
    {"label": "^C", "action": "clipboard", "value": "copy"},
    {"label": "^V", "action": "clipboard", "value": "paste"},
    {"label": "\u21e7", "action": "key_event", "value": "up"},
    {"label": "\u21e9", "action": "key_event", "value": "down"},
    {"label": "\u21e6", "action": "key_event", "value": "left"},
    {"label": "\u21e8", "action": "key_event", "value": "right"},
    {"label": "DT", "action": "datetime"},
    {"label": "W", "action": "weekday"},
    {"label": "\u25bc", "action": "hide", "long_press": "toggle_hide_rows"},
]


# --------------------------------------------------------------------------
# Keyboard
# --------------------------------------------------------------------------
class ImeKeyboard(BoxLayout):
    """Bar + five key rows; rebuilt on mode change."""

    COLUMNS = 12

    def __init__(self, bridge=None, demo=False, **kwargs):
        self.gap_h = dp(2)
        self.gap_v = dp(2)
        self.outer = dp(3)
        self.pad = dp(3)
        self.bar_h = dp(48)
        self.body_h = dp(240)
        self.row_h = (self.body_h - 2 * self.outer
                      - self.gap_v * (len(ROWS) - 1)) / len(ROWS)

        super().__init__(
            orientation="vertical",
            padding=[self.pad, 0, self.pad, self.pad],
            spacing=0,
            size_hint=(1, None),
            **kwargs,
        )
        self.height = self.bar_h + self.body_h + self.pad

        self.bridge = bridge
        self.demo = demo or bridge is None
        self.english = False
        self.buffer = ""
        self.candidates = []
        self._chip_min_width = self._measure("0微") + dp(16)

        self._bar_box = BoxLayout(orientation="horizontal", spacing=0,
                                  size_hint_y=None, height=self.bar_h)
        self.add_widget(self._bar_box)

        self._rows_box = BoxLayout(
            orientation="vertical", spacing=self.gap_v,
            padding=[self.outer, self.outer, self.outer, self.outer],
            size_hint_y=None, height=self.body_h,
        )
        self.add_widget(self._rows_box)

        self._buffer_label = None
        self._chips_box = None
        self._build()

    # -- measurement ---------------------------------------------------
    @staticmethod
    def _measure(text, size=24):
        try:
            core = CoreLabel(text=text, font_name=FONT_NAME,
                             font_size=sp(size))
            core.refresh()
            return core.texture.size[0]
        except Exception:  # noqa: BLE001
            return dp(24)

    # -- actions -------------------------------------------------------
    def _emit(self, action, value=None):
        if action == "code" and value is not None:
            self._bridge_key(value)
            if self.demo:
                self.buffer = (self.buffer + value)[-6:]
                self.candidates = []
                self._refresh_bar()
            return
        if action == "backspace":
            if self.bridge:
                self.bridge.backspace()
            if self.demo:
                self.buffer = self.buffer[:-1]
                self._refresh_bar()
            return
        if action == "space":
            if self.bridge:
                self.bridge.space()
            if self.demo:
                self.buffer = ""
                self._refresh_bar()
            return
        if action == "enter":
            if self.bridge:
                self.bridge.enter()
            if self.demo:
                self.buffer = ""
                self._refresh_bar()
            return
        if action == "toggle_mode":
            self.set_english(not self.english)
            if self.bridge:
                self.bridge.toggle_mode()
            return
        if action == "hide":
            if self.bridge:
                self.bridge.hide()
            return
        if action == "noop":
            return
        if self.bridge:
            self.bridge.send(type=action, value=value)

    def _bridge_key(self, value):
        if not self.bridge:
            return
        if value == " ":
            self.bridge.space()
        else:
            self.bridge.key(value)

    # -- build ---------------------------------------------------------
    def _build(self):
        self._rebuild_bar()
        self._rebuild_rows()

    def _make_key(self, spec, font_size=None):
        return Key(
            label=spec.get("label", ""),
            value=spec.get("value", spec.get("label", "")),
            action=spec.get("action", "code"),
            sub_label=spec.get("sub", ""),
            gestures=spec.get("gestures")
            or ({"up": spec["up"]} if spec.get("up") else None),
            repeat=spec.get("repeat", False),
            long_press=spec.get("long_press"),
            on_commit=self._emit,
            font_size=font_size,
        )

    def _rebuild_bar(self):
        self._bar_box.clear_widgets()
        self._buffer_label = None
        self._chips_box = None

        if self.english:
            for spec in ENGLISH_BAR:
                key = self._make_key(spec, font_size=sp(14))
                key.size_hint_x = 1.0 / self.COLUMNS
                self._bar_box.add_widget(key)
            return

        buffer_key = Key(
            label=self.buffer.upper(), action="commit_buffer",
            on_commit=self._emit, font_size=sp(24),
        )
        buffer_key.size_hint_x = 2.0 / self.COLUMNS
        buffer_key.bind(size=lambda w, _s: setattr(
            w._label, "text_size", (max(1, w.width - dp(8)), w.height)))
        buffer_key._label.halign = "left"
        buffer_key._label.text_size = (
            max(1, buffer_key.width - dp(8)), buffer_key.height)
        self._buffer_label = buffer_key._label
        self._buffer_key = buffer_key
        self._bar_box.add_widget(buffer_key)

        scroll = ScrollView(do_scroll_x=True, do_scroll_y=False,
                            size_hint_x=9.0 / self.COLUMNS, bar_width=0)
        self._chips_box = BoxLayout(orientation="horizontal", spacing=0,
                                    size_hint_x=None)
        self._chips_box.bind(
            minimum_width=self._chips_box.setter("width"))
        scroll.add_widget(self._chips_box)
        self._bar_box.add_widget(scroll)

        hide = self._make_key(ENGLISH_BAR[-1], font_size=sp(14))
        hide.size_hint_x = 1.0 / self.COLUMNS
        self._bar_box.add_widget(hide)

        self._refresh_chips()

    def _refresh_bar(self):
        if self.english:
            return
        if self._buffer_label is not None:
            self._buffer_label.text = self.buffer.upper()[-6:]
        if self._buffer_key is not None:
            self._buffer_key.value = self.buffer
        self._refresh_chips()

    def _refresh_chips(self):
        if self._chips_box is None:
            return
        self._chips_box.clear_widgets()
        if not self.candidates:
            self._chips_box.add_widget(
                Chip("", selected=True, min_width=self._chip_min_width))
            return
        for index, text in enumerate(self.candidates):
            selkey = str(index) if index < 10 else None
            self._chips_box.add_widget(Chip(
                text, selkey=selkey, selected=index == 0, index=index,
                on_select=self._select, min_width=self._chip_min_width))

    def _select(self, index, text):
        if self.bridge:
            self.bridge.send(type="candidate", index=index)
        if self.demo:
            self.buffer = ""
            self.candidates = []
            self._refresh_bar()

    def _rebuild_rows(self):
        self._rows_box.clear_widgets()
        # ROWS[0] = number row (top), ROWS[-1] = toggle/space/enter (bottom).
        # Vertical BoxLayout: first child added = topmost.
        for row_index, row in enumerate(ROWS):
            entries = []
            for spec in row:
                key = self._make_key(spec)
                key.edge_top = row_index == 0  # topmost row
                entries.append((key, spec.get("span", 1)))
            key_row = KeyRow(entries, self.gap_h, self.COLUMNS,
                             size_hint_y=None, height=self.row_h)
            for column_index, (key, _span) in enumerate(entries):
                key.edge_left = column_index == 0
            self._rows_box.add_widget(key_row)

    # -- mode ----------------------------------------------------------
    def set_english(self, english):
        if self.english == english:
            return
        self.english = english
        self.buffer = ""
        self.candidates = []
        self._rebuild_bar()
        self._rebuild_rows()

    # -- bridge --------------------------------------------------------
    def on_bridge_message(self, message):
        """Called on the Kivy main thread."""
        kind = message.get("type")
        if kind == "candidates":
            self.buffer = message.get("buffer") or ""
            self.candidates = message.get("candidates") or []
            english = bool(message.get("english"))
            if english != self.english:
                self.set_english(english)
            self._refresh_bar()
        elif kind == "mode":
            self.set_english(bool(message.get("english")))


# --------------------------------------------------------------------------
# App
# --------------------------------------------------------------------------
class KivyIMEApp(App):
    title = "mruby-ime"

    def build(self):
        Window.clearcolor = (1, 1, 1, 1)
        self.bridge = ImeBridge(on_message=self._on_bridge_message)
        self.keyboard = ImeKeyboard(bridge=self.bridge)
        root = AnchorLayout(anchor_x="center", anchor_y="bottom")
        root.add_widget(self.keyboard)
        threading.Thread(target=self._connect_loop, daemon=True).start()
        return root

    def _connect_loop(self):
        while not self.bridge.connected:
            if self.bridge.connect(timeout=0.5):
                return
            time.sleep(2.0)

    def _on_bridge_message(self, message):
        Clock.schedule_once(
            lambda _dt: self.keyboard.on_bridge_message(message), 0)

    def on_stop(self):
        if getattr(self, "bridge", None):
            self.bridge.close()


if __name__ == "__main__":
    KivyIMEApp().run()
