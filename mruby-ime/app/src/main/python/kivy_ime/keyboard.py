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
import random
import threading
import time

from kivy.animation import Animation
from kivy.app import App
from kivy.clock import Clock
from kivy.core.audio import SoundLoader
from kivy.core.text import Label as CoreLabel
from kivy.core.text import LabelBase
from kivy.core.window import Window
from kivy.graphics import Color, Line, Rectangle, Ellipse
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
# Palette (theme :light in keyboard_layout.rb) - "frosted glass" variant
#
# A real gaussian blur of the app behind the IME window is not available to a
# Kivy surface (it cannot sample the framebuffer behind its own window), so the
# frosted look is built from stacked translucency instead:
#
#   * the SDL surface itself is translucent (see KivyImeHost / PythonActivity)
#   * one inset frost plate at FROST alpha blurs nothing but veils the app
#   * key/chip plates at PLATE alpha sit on top of it
#   * only the seams, glyphs and rules stay opaque
#
# Set FROSTED = False to go back to the flat opaque keyboard.
# --------------------------------------------------------------------------
FROSTED = True

# Palette — "flat frost": near-white plates, cool gray seams, no depth.
#
# Previous look was #E0E0E0 plates with a #999999 grid and a press that sank
# the plate by 2 dp. That read as physical keys, which is the opposite of the
# frosted look the keys now have. This palette is one lift lighter and the
# seams are pulled down so the keys stay legible over a bright app.
#
# Set FLAT_FROST = False to return to the old #E0E0E0 / #999999 palette.
FLAT_FROST = True

INK = (0.0, 0.0, 0.0, 1.0)
MUTED = (0.4, 0.4, 0.4, 1.0)
# Plate alpha: keys/chips/bar. Opaque when FROSTED is off.
PLATE_A = 0.68 if FROSTED else 1.0
# Frost veil behind everything, over the live app content.
FROST_A = 0.42 if FROSTED else 1.0
FROST_TINT = (1.0, 1.0, 1.0)      # white veil
if FLAT_FROST:
    _KEY = (1.0, 1.0, 1.0)          # near-white frost plates
    _SEAM = (0.62, 0.64, 0.67)       # cool gray, still 1 dp visible
    _PRESS = (0.86, 0.88, 0.91)      # press = a slightly gray plate
    _BAR = (1.0, 1.0, 1.0)           # the bar is not a different band
    _CHIP = (1.0, 1.0, 1.0)
else:
    _KEY = (224 / 255.0, 224 / 255.0, 224 / 255.0)
    _SEAM = (153 / 255.0, 153 / 255.0, 153 / 255.0)
    _PRESS = (204 / 255.0, 204 / 255.0, 204 / 255.0)
    _BAR = (224 / 255.0, 224 / 255.0, 224 / 255.0)
    _CHIP = (224 / 255.0, 224 / 255.0, 224 / 255.0)

BAR_BG = _BAR + (PLATE_A,)
KEY_BG = _KEY + (PLATE_A,)
KEY_PRESSED = _PRESS + (min(1.0, PLATE_A + 0.22),)
SEAM = _SEAM + (0.85,)
# Candidate chips all share one plate (selected is bold, not yellow).
CHIP_BG = _CHIP + (PLATE_A,)
CLEAR = (1.0, 1.0, 1.0, 0.0)

# --------------------------------------------------------------------------
# Fonts: Roboto has no CJK glyphs, so bind a system CJK face.
# --------------------------------------------------------------------------
# Bundled font first: Unifont-T ships in the APK (assets/unifont_t.otf in the
# private bundle) and, unlike the device CJK faces, it also carries the glyphs
# the keyboard labels need -- backspace U+232B, enter U+23CE, the arrow set and
# box-drawing characters. The system faces stay as a fallback.
def _bundled_font_paths():
    """Where Unifont-T lives at runtime: unpacked next to our sources.

    On device p4a extracts the private bundle to
    ``filesDir/app/``, so the font sits at ``filesDir/app/assets/`` and
    this module is one level deeper (``filesDir/app/kivy_ime/``).
    """
    here = os.path.dirname(os.path.abspath(__file__))
    roots = [os.getcwd(), os.path.dirname(os.getcwd()),
             here, os.path.dirname(here)]
    for root in roots:
        if not root:
            continue
        candidate = os.path.join(root, "assets", "unifont_t.otf")
        if os.path.exists(candidate):
            return candidate
    return None


def _desktop_font_paths():
    # Repo-relative fallbacks, so a fresh clone still previews on a desktop
    # without the font being duplicated at the repository root.
    pkg = os.path.dirname(os.path.abspath(__file__))
    return (
        os.path.join(os.path.dirname(pkg), "assets", "unifont_t.otf"),
        os.path.join(pkg, "..", "..", "..", "..", "assets", "unifont_t.otf"),
        "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    )


_CJK_CANDIDATES = (
    _bundled_font_paths(),
    *_desktop_font_paths(),
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/NotoSansCJKsc-Regular.otf",
    "/system/fonts/NotoSansSC-Regular.otf",
    "/system/fonts/DroidSansFallback.ttf",
    "/system/fonts/DroidSansFallbackFull.ttf",
)


def _register_cjk_font():
    for path in _CJK_CANDIDATES:
        if not path or not os.path.exists(path):
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
# Emoji font: Noto CJK has no 🦖 glyph (it renders as tofu), so the game
# button needs the system emoji face.
# --------------------------------------------------------------------------
_EMOJI_CANDIDATES = (
    "/system/fonts/NotoColorEmoji.ttf",
    "/system/fonts/NotoColorEmojiFlags.ttf",
    # desktop smoke tests
    "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
    "/usr/share/fonts/noto/NotoColorEmoji.ttf",
)


def _register_emoji_font():
    for path in _EMOJI_CANDIDATES:
        if not os.path.exists(path):
            continue
        try:
            LabelBase.register(name="EMOJI", fn_regular=path)
            print("kivy-ime: emoji font %s" % path, flush=True)
            return "EMOJI"
        except Exception as exc:  # noqa: BLE001 - font probing must not crash
            print("kivy-ime: emoji font %s failed: %s" % (path, exc), flush=True)
    print("kivy-ime: no emoji font found, using default", flush=True)
    return "Roboto"


EMOJI_FONT_NAME = _register_emoji_font()


# --------------------------------------------------------------------------
# Vector dinosaur glyph
#
# The Android emoji face (NotoColorEmoji.ttf) is a CBDT *bitmap* font: SDL_ttf
# cannot scale it, so a 16 sp label renders as nothing at all. Drawing the
# dino as rectangles needs no font and looks identical on device and desktop.
# --------------------------------------------------------------------------
class DinoGlyph(Widget):
    """Blocky T-rex, sized to its cell."""

    # (x, y, w, h) in units of the cell height; y=0 is the floor.
    PARTS = (
        (3.0, 2.0, 5.0, 7.0),    # body
        (8.0, 7.5, 4.5, 3.5),    # head
        (7.0, 5.5, 2.0, 2.5),    # neck
        (1.0, 6.0, 2.5, 2.0),    # tail
        (3.0, 0.0, 1.6, 2.2),    # back leg
        (6.0, 0.0, 1.6, 2.2),    # front leg
    )

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._rects = []
        with self.canvas:
            self._body_color = Color(*INK)
            for cell in self.PARTS:
                self._rects.append(Rectangle(size=(0, 0)))
            self._eye_color = Color(1, 1, 1, 1)
            self._eye = Rectangle(size=(0, 0))
        self.bind(pos=self._sync, size=self._sync)
        self._sync()

    def _sync(self, *_args):
        u = self.height / 14.0
        left = self.x + (self.width - 10.0 * u) / 2.0
        base = self.y + (self.height - 11.0 * u) / 2.0
        for rect, (cx, cy, cw, ch) in zip(self._rects, self.PARTS):
            rect.pos = (left + cx * u, base + cy * u)
            rect.size = (cw * u, ch * u)
        self._eye.pos = (left + 10.4 * u, base + 9.3 * u)
        self._eye.size = (0.9 * u, 0.9 * u)


# --------------------------------------------------------------------------
# Game sounds
# --------------------------------------------------------------------------
class GameSounds:
    """Game sound effects and background music."""

    _jump_sound = None
    _hit_sound = None
    _bg_music = None
    _tried = False

    JUMP_PATHS = (
        "/system/media/audio/ui/KeypressStandard.ogg",
        "/system/media/audio/ui/KeypressSpacebar.ogg",
    )
    HIT_PATHS = (
        "/system/media/audio/ui/KeypressDelete.ogg",
        "/system/media/audio/ui/Effect_Tick.ogg",
    )
    MUSIC_PATHS = (
        "/system/media/audio/ringtones/Luna.ogg",
        "/system/media/audio/ringtones/Orion.ogg",
    )

    @classmethod
    def _load(cls):
        cls._tried = True
        # Jump sound
        for path in cls.JUMP_PATHS:
            if os.path.exists(path):
                sound = SoundLoader.load(path)
                if sound:
                    sound.volume = 0.7
                    cls._jump_sound = sound
                    break
        # Hit sound
        for path in cls.HIT_PATHS:
            if os.path.exists(path):
                sound = SoundLoader.load(path)
                if sound:
                    sound.volume = 0.8
                    cls._hit_sound = sound
                    break
        # Background music
        for path in cls.MUSIC_PATHS:
            if os.path.exists(path):
                sound = SoundLoader.load(path)
                if sound:
                    sound.volume = 0.3
                    sound.loop = True
                    cls._bg_music = sound
                    break
        if not cls._jump_sound:
            print("kivy-ime: no jump sound available", flush=True)
        if not cls._hit_sound:
            print("kivy-ime: no hit sound available", flush=True)
        if not cls._bg_music:
            print("kivy-ime: no background music available", flush=True)

    @classmethod
    def play_jump(cls):
        if not cls._tried:
            cls._load()
        if cls._jump_sound:
            try:
                cls._jump_sound.play()
            except Exception:
                pass

    @classmethod
    def play_hit(cls):
        if not cls._tried:
            cls._load()
        if cls._hit_sound:
            try:
                cls._hit_sound.play()
            except Exception:
                pass

    @classmethod
    def start_music(cls):
        if not cls._tried:
            cls._load()
        if cls._bg_music:
            try:
                cls._bg_music.play()
            except Exception:
                pass

    @classmethod
    def stop_music(cls):
        if cls._bg_music:
            try:
                cls._bg_music.stop()
            except Exception:
                pass


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
                 on_commit=None, font_size=None, emoji=False, seams=True,
                 **kwargs):
        super().__init__(**kwargs)
        # Bar keys pass seams=False: the bar is one continuous plate, so a 1 dp
        # outline around each button would draw a grid across it.
        self.seams = seams
        # Only a code key picks up its label; backspace/space/enter
        # keep value=None so nothing can send their glyph.
        self.value = (label if value is None and action == "code" else value)
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

        # A "dino" key is drawn with shapes: the system emoji face is a bitmap
        # font that SDL_ttf renders blank at key sizes.
        self._glyph = None
        self._label = Label(
            text="" if emoji == "dino" else label,
            font_name=FONT_NAME,
            font_size=font_size or sp(20), bold=True, color=INK,
            halign="center", valign="middle", size_hint=(None, None),
        )
        self._sub = Label(
            text=sub_label, font_name=FONT_NAME, font_size=sp(10),
            color=MUTED, halign="right", valign="top", size_hint=(None, None),
        )
        self.add_widget(self._label)
        self.add_widget(self._sub)
        if emoji == "dino":
            self._glyph = DinoGlyph(size_hint=(1, 1))
            self.add_widget(self._glyph)

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
        # Flat plates: no inset (that is the "3D key press" look). The press only
        # shifts the plate colour, which reads as feedback without depth.
        inset = 0
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

        # Key is a plain Widget, so children are placed by hand.
        if self._glyph is not None:
            self._glyph.pos = self.pos
            self._glyph.size = self.size

        if self.seams:
            self._seam_right.points = [self.right, self.y, self.right, self.top]
            self._seam_bottom.points = [self.x, self.y, self.right, self.y]
            self._seam_left.points = (
                [self.x, self.y, self.x, self.top] if self.edge_left else [])
            self._seam_top.points = (
                [self.x, self.top, self.right, self.top] if self.edge_top else [])
        else:
            self._seam_right.points = []
            self._seam_bottom.points = []
            self._seam_left.points = []
            self._seam_top.points = []

    # -- feedback ------------------------------------------------------
    def _animate(self, target, duration):
        Animation.cancel_all(self, "press")
        Animation(press=target, d=duration).start(self)

    # -- action --------------------------------------------------------
    def default_action(self):
        """What a plain tap (or a repeat tick) does: the key's own action,
        never its label. Backspace must not fall back to the arrow glyph."""
        return (self.action, self.value)

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
            self._fire(self.default_action())
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
        self._fire(self.default_action())
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
    """Candidate chip: flat by default, the selected one is bold + filled."""

    selected = BooleanProperty(False)

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
        # Only the selected chip is bold (and the only one filled), so the
        # candidate row stays flat like the DSL's bar.
        self._label = Label(
            text=shown, font_name=FONT_NAME, font_size=sp(24), bold=selected,
            color=INK, halign="center", valign="middle", size_hint=(None, None),
        )
        self.add_widget(self._label)

        with self.canvas.before:
            # Every chip carries the same plate: the candidate row reads as one
            # band of keys, and only the selected chip is bold.
            self._bg_color = Color(*CHIP_BG)
            self._bg = Rectangle()

        self.bind(pos=self._sync, size=self._sync, selected=self._sync)
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
        self._bg_color.rgba = CHIP_BG
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
# T-Rex Runner Game
# --------------------------------------------------------------------------
class GameWidget(Widget):
    """T-Rex runner game widget."""

    # Game constants
    DINO_WIDTH = dp(40)
    DINO_HEIGHT = dp(44)
    CACTUS_WIDTH = dp(20)
    CACTUS_HEIGHT = dp(40)
    GROUND_HEIGHT = dp(20)
    JUMP_VELOCITY = dp(600)
    GRAVITY = dp(1500)
    GAME_SPEED = dp(300)
    SPEED_INCREASE = dp(10)

    def __init__(self, on_exit=None, **kwargs):
        super().__init__(**kwargs)
        self.on_exit = on_exit
        self.size_hint = (1, 1)

        # Game state
        self.running = False
        self.game_over = False
        self.score = 0
        self.high_score = 0
        self.speed = self.GAME_SPEED

        # Dino state
        self.dino_x = dp(50)
        self.dino_y = self.GROUND_HEIGHT
        self.dino_vel_y = 0
        self.is_jumping = False
        self.is_ducking = False

        # Obstacles
        self.obstacles = []  # list of (x, width, height, is_bird)
        self.spawn_timer = 0
        self.spawn_interval = 1.5

        # Ground
        self.ground_x = 0

        # Clouds
        self.clouds = []

        # Touch tracking
        self._touch_start = None
        self._swipe = None

        # Score read-out (top-right): "HI 00123   00045"
        self._score_label = Label(
            text="HI 00000   00000", font_name=FONT_NAME, font_size=sp(16),
            bold=True, color=INK, halign="right", valign="top",
            size_hint=(None, None),
        )
        self._over_label = Label(
            text="", font_name=FONT_NAME, font_size=sp(18), bold=True,
            color=INK, halign="center", valign="middle",
            size_hint=(None, None),
        )
        self.add_widget(self._score_label)
        self.add_widget(self._over_label)

        # Bind size
        self.bind(size=self._on_size, pos=self._on_pos)

        # Game loop
        self._game_clock = Clock.schedule_interval(self._update, 1/60)
        # Start immediately; _on_size also resets once a real size arrives.
        Clock.schedule_once(lambda _dt: self._reset_game(), 0)

    def _on_size(self, *args):
        if not self.running and not self.game_over and self.width > 0 and self.height > 0:
            self._reset_game()
        self._sync_overlays()
        self.draw()

    def _on_pos(self, *args):
        self._sync_overlays()
        self.draw()

    def _sync_overlays(self):
        """Keep the two text overlays pinned to their corners."""
        if self.parent is None:
            return
        pad = dp(8)
        sw, sh = self._score_label.texture_size
        self._score_label.size = (sw, sh)
        self._score_label.pos = (
            self.right - sw - pad,
            self.top - sh - pad,
        )
        ow, oh = self._over_label.texture_size
        self._over_label.size = (ow, oh)
        self._over_label.pos = (
            self.center_x - ow / 2.0,
            self.center_y - oh / 2.0,
        )

    def _reset_game(self):
        self.running = True
        self.game_over = False
        self.score = 0
        self.speed = self.GAME_SPEED
        self.dino_x = dp(50)
        self.dino_y = self.GROUND_HEIGHT
        self.dino_vel_y = 0
        self.is_jumping = False
        self.is_ducking = False
        self.obstacles = []
        self.spawn_timer = 0
        self.clouds = []
        self.ground_x = 0
        # Spawn initial clouds
        for i in range(3):
            self.clouds.append({
                'x': self.width + random.uniform(0, self.width),
                'y': self.height * 0.6 + random.uniform(0, self.height * 0.2),
                'speed': random.uniform(0.2, 0.5)
            })
        GameSounds.start_music()
        self._over_label.text = ""
        self._update_score_text()
        self._sync_overlays()

    def on_touch_down(self, touch):
        if not self.collide_point(*touch.pos):
            return False

        if self.game_over:
            # Restart game
            self._reset_game()
            return True

        self._touch_start = (touch.x, touch.y)
        self._swipe = None          # None | "jump" | "duck"
        touch.grab(self)
        return True

    def on_touch_move(self, touch):
        if touch.grab_current is not self:
            return False

        if self._touch_start is None:
            self._touch_start = (touch.x, touch.y)

        dy = touch.y - self._touch_start[1]
        threshold = dp(30)

        # Swipe down -> duck (live), swipe up -> arm a jump
        if dy < -threshold and not self.is_jumping:
            self._swipe = "duck"
            self.is_ducking = True
        elif dy > threshold:
            self._swipe = "jump"

        return True

    def on_touch_up(self, touch):
        if touch.grab_current is not self:
            return False
        touch.ungrab(self)
        start = self._touch_start
        swipe = self._swipe
        self._touch_start = None
        self._swipe = None

        if swipe == "duck":
            # Release the duck now that the finger lifted.
            self.is_ducking = False
            return True

        if not self.is_jumping:
            # Tap (or swipe up) -> jump.
            self.is_jumping = True
            self.dino_vel_y = self.JUMP_VELOCITY
            self.is_ducking = False
            GameSounds.play_jump()

        return True

    def _update(self, dt):
        if not self.running or self.game_over:
            return

        # Update dino physics
        if self.is_jumping:
            self.dino_vel_y -= self.GRAVITY * dt
            self.dino_y += self.dino_vel_y * dt

            if self.dino_y <= self.GROUND_HEIGHT:
                self.dino_y = self.GROUND_HEIGHT
                self.dino_vel_y = 0
                self.is_jumping = False

        # Ducking reduces height
        dino_height = self.DINO_HEIGHT * 0.6 if self.is_ducking else self.DINO_HEIGHT

        # Update ground
        self.ground_x -= self.speed * dt
        if self.ground_x <= -self.width:
            self.ground_x = 0

        # Update clouds
        for cloud in self.clouds:
            cloud['x'] -= cloud['speed'] * self.speed * dt
            if cloud['x'] < -dp(100):
                cloud['x'] = self.width + random.uniform(0, self.width)

        # Spawn obstacles
        self.spawn_timer += dt
        if self.spawn_timer >= self.spawn_interval:
            self.spawn_timer = 0
            self._spawn_obstacle()

        # Update obstacles
        for obs in self.obstacles[:]:
            obs['x'] -= self.speed * dt
            if obs['x'] < -obs['width']:
                self.obstacles.remove(obs)
                self.score += 1
                # Increase speed slightly
                self.speed += self.SPEED_INCREASE
                # Decrease spawn interval slightly
                self.spawn_interval = max(0.8, self.spawn_interval - 0.02)

        # Check collisions
        dino_rect = (self.dino_x, self.dino_y, self.DINO_WIDTH, dino_height)
        for obs in self.obstacles:
            obs_rect = (obs['x'], obs['y'], obs['width'], obs['height'])
            if self._rects_collide(dino_rect, obs_rect):
                self._game_over()
                break

        # Update score display
        self._update_score_text()
        self.draw()

    def _spawn_obstacle(self):
        # Randomly choose cactus or bird
        if random.random() < 0.7:
            # Cactus
            width = self.CACTUS_WIDTH
            height = self.CACTUS_HEIGHT
            y = self.GROUND_HEIGHT
        else:
            # Bird (flies higher)
            width = dp(30)
            height = dp(30)
            y = self.GROUND_HEIGHT + random.uniform(dp(40), dp(100))

        self.obstacles.append({
            'x': self.width,
            'y': y,
            'width': width,
            'height': height
        })

    def _rects_collide(self, rect1, rect2):
        x1, y1, w1, h1 = rect1
        x2, y2, w2, h2 = rect2
        return (x1 < x2 + w2 and x1 + w1 > x2 and
                y1 < y2 + h2 and y1 + h1 > y2)

    def _game_over(self):
        self.game_over = True
        self.running = False
        GameSounds.play_hit()
        GameSounds.stop_music()
        if self.score > self.high_score:
            self.high_score = self.score
        self._over_label.text = "GAME OVER - TAP TO RETRY"
        self._update_score_text()
        self._sync_overlays()

    def _update_score_text(self):
        """HI + current score, zero-padded like the Chrome dino."""
        self._score_label.text = "HI %05d   %05d" % (self.high_score, self.score)
        self._score_label.texture_update()
        self._sync_overlays()

    def draw(self):
        """Draw the game.

        Everything goes into ``canvas.before`` on purpose: Widget draws
        ``canvas.before`` -> children -> ``canvas`` -> ``canvas.after``, so the
        score Label and the GAME OVER text must sit above the graphics.
        """
        self.canvas.before.clear()

        with self.canvas.before:
            # Sky background
            Color(0.95, 0.95, 0.95, 1)
            Rectangle(pos=self.pos, size=self.size)

            # Clouds
            Color(0.7, 0.7, 0.7, 0.8)
            for cloud in self.clouds:
                Ellipse(pos=(cloud['x'], cloud['y']), size=(dp(60), dp(30)))
                Ellipse(pos=(cloud['x'] + dp(20), cloud['y'] - dp(5)), size=(dp(40), dp(20)))

            # Ground
            Color(0.3, 0.3, 0.3, 1)
            # Double ground for scrolling effect
            Rectangle(pos=(self.ground_x, 0), size=(self.width + self.GROUND_HEIGHT, self.GROUND_HEIGHT))
            Rectangle(pos=(self.ground_x + self.width, 0), size=(self.width + self.GROUND_HEIGHT, self.GROUND_HEIGHT))

            # Ground line
            Color(0.2, 0.2, 0.2, 1)
            Rectangle(pos=(self.ground_x, self.GROUND_HEIGHT), size=(self.width + self.GROUND_HEIGHT, dp(2)))
            Rectangle(pos=(self.ground_x + self.width, self.GROUND_HEIGHT), size=(self.width + self.GROUND_HEIGHT, dp(2)))

            # Score
            Color(0.2, 0.2, 0.2, 1)

            # Dino
            dino_h = self.DINO_HEIGHT * 0.6 if self.is_ducking else self.DINO_HEIGHT
            dino_y = self.dino_y
            if self.is_ducking:
                dino_y = self.GROUND_HEIGHT

            # Dino body
            Color(0.2, 0.2, 0.2, 1)
            Rectangle(pos=(self.dino_x, dino_y), size=(self.DINO_WIDTH, dino_h))
            # Dino eye
            Color(1, 1, 1, 1)
            Ellipse(pos=(self.dino_x + self.DINO_WIDTH - dp(8), dino_y + dino_h - dp(10)), size=(dp(6), dp(6)))

            # Obstacles
            Color(0.2, 0.2, 0.2, 1)
            for obs in self.obstacles:
                if obs['height'] > self.CACTUS_HEIGHT * 0.8:
                    # Cactus
                    Rectangle(pos=(obs['x'], obs['y']), size=(obs['width'], obs['height']))
                    # Cactus arms
                    Rectangle(pos=(obs['x'] - dp(5), obs['y'] + obs['height'] * 0.4), size=(dp(5), dp(10)))
                    Rectangle(pos=(obs['x'] + obs['width'], obs['y'] + obs['height'] * 0.6), size=(dp(5), dp(8)))
                else:
                    # Bird
                    Ellipse(pos=(obs['x'], obs['y']), size=(obs['width'], obs['height']))
                    # Wing
                    Ellipse(pos=(obs['x'] - dp(5), obs['y'] + dp(5)), size=(dp(15), dp(10)))

            # Game over overlay
            if self.game_over:
                Color(0, 0, 0, 0.7)
                Rectangle(pos=self.pos, size=self.size)
                Color(1, 1, 1, 1)

            # Score / game-over text are Label children (they need real font
            # shaping), so nothing more to draw here.


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
    {"label": "\u232b", "value": None, "action": "backspace", "repeat": True},
]

ROW_5 = [
    {"label": "中/英", "action": "toggle_mode", "span": 2},
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


def _upper_ascii(text):
    """Uppercase ASCII letters only, leaving CJK/symbols untouched."""
    return "".join(ch.upper() if "a" <= ch <= "z" else ch for ch in text)


def _uppercase_row(row):
    """Copy of a row with ASCII letters in the labels uppercased.

    Only `label` changes: `value` stays lowercase so the mruby engine keeps
    receiving the same code characters, and `sub`/`up` are already symbols.
    """
    out = []
    for spec in row:
        label = spec.get("label", "")
        upper = _upper_ascii(label)
        out.append(spec if upper == label else dict(spec, label=upper))
    return out


# Chinese mode shows capital letters (classic 形碼 look); English mode keeps
# the lowercase rows. The bottom row is identical in both modes.
CHINESE_ROWS = [_uppercase_row(row) for row in ROWS[:4]] + [ROW_5]

# Shared bar keys: the game button (T-Rex runner) and the hide button.
GAME_KEY = {"label": "", "action": "game", "emoji": "dino"}
BACK_KEY = {"label": "KB", "action": "exit_game", "font_size": 14}
HIDE_KEY = {"label": "\u25bc", "action": "hide", "long_press": "toggle_hide_rows"}

ENGLISH_BAR = [
    {"label": "TAB", "action": "key_event", "value": "tab"},
    {"label": "^A", "action": "clipboard", "value": "select_all"},
    {"label": "^C", "action": "clipboard", "value": "copy"},
    {"label": "^V", "action": "clipboard", "value": "paste"},
    {"label": "\u21e7", "action": "key_event", "value": "up"},
    {"label": "\u21e9", "action": "key_event", "value": "down"},
    {"label": "\u21e6", "action": "key_event", "value": "left"},
    {"label": "\u21e8", "action": "key_event", "value": "right"},
    {"label": "DT", "action": "datetime"},
    {"label": "W", "action": "weekday"},
    GAME_KEY,
    HIDE_KEY,
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
        self._bar_bg = None
        with self._bar_box.canvas.before:
            self._bar_bg_color = Color(*BAR_BG)
            self._bar_bg = Rectangle()
        self._bar_box.bind(pos=self._paint_bar, size=self._paint_bar)
        self.add_widget(self._bar_box)

        self._rows_box = BoxLayout(
            orientation="vertical", spacing=self.gap_v,
            padding=[self.outer, self.outer, self.outer, self.outer],
            size_hint_y=None, height=self.body_h,
        )
        self.add_widget(self._rows_box)

        self._buffer_label = None
        self._chips_box = None

        # Frosted-glass veil: one translucent plate behind the whole keyboard,
        # so whatever the IME window composites over shows through it.
        self._frost_color = None
        self._frost_rect = None
        if FROSTED:
            with self.canvas.before:
                self._frost_color = Color(*(FROST_TINT + (FROST_A,)))
                self._frost_rect = Rectangle()
            self.bind(pos=self._paint_frost, size=self._paint_frost)
            self._paint_frost()

        self._build()

    # -- frost ---------------------------------------------------------
    def _paint_frost(self, *_args):
        if self._frost_rect is None:
            return
        self._frost_rect.pos = self.pos
        self._frost_rect.size = self.size

    # -- measurement ---------------------------------------------------
    def _paint_bar(self, *_args):
        """Keep the bar's plate in sync with its box.

        The DSL gives the bar its own colour (`bar bg: :bar` -> #F5F5F5), so
        the candidate chips sit on a flat light plate and only the selected
        chip is filled. Without it the bar shows the (white) window clear
        colour and looks nothing like the key plates.
        """
        if self._bar_box is None or self._bar_bg is None:
            return
        self._bar_bg.pos = self._bar_box.pos
        self._bar_bg.size = self._bar_box.size

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
            # Hiding the keyboard always returns to typing (Chinese) mode:
            # leave the game first, otherwise the next show starts in it.
            if getattr(self, "_game_mode", False):
                self._exit_game()
            if self.bridge:
                self.bridge.hide()
            return
        if action == "game":
            self._toggle_game()
            return
        if action == "exit_game":
            # Only reachable from the game bar; a plain ▼ already exits too.
            self._exit_game()
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

    def _make_key(self, spec, font_size=None, seams=True):
        return Key(
            label=spec.get("label", ""),
            # Deliberately NOT defaulting to the label: Key.__init__ decides
            # that itself, and only for "code" keys. Defaulting here is what
            # made DT send value="DT" and W send value="W", which Kotlin feeds
            # to SimpleDateFormat -- "DT" is not a legal pattern (exception) and
            # "W" is week-in-month (a number, not the weekday name).
            value=spec.get("value"),
            action=spec.get("action", "code"),
            sub_label=spec.get("sub", ""),
            gestures=spec.get("gestures")
            or ({"up": spec["up"]} if spec.get("up") else None),
            repeat=spec.get("repeat", False),
            long_press=spec.get("long_press"),
            on_commit=self._emit,
            font_size=font_size,
            emoji=spec.get("emoji", False),
            seams=seams,
        )

    def _rebuild_bar(self):
        self._bar_box.clear_widgets()
        self._buffer_label = None
        self._chips_box = None

        if self.english:
            for spec in ENGLISH_BAR:
                key = self._make_key(spec, font_size=sp(14), seams=False)
                key.size_hint_x = 1.0 / self.COLUMNS
                self._bar_box.add_widget(key)
            return

        buffer_key = Key(
            label=self.buffer.upper(), action="commit_buffer",
            on_commit=self._emit, font_size=sp(24), seams=False,
        )
        buffer_key.size_hint_x = 2.0 / self.COLUMNS
        # One line, always. `shorten` makes Kivy clip instead of wrapping, and
        # `shorten_from="left"` keeps the *tail* -- a long code reads "…DEFGH"
        # rather than spilling onto a second line (the code area is only 2 of
        # the 12 columns, so 6 wide capitals already overflow).
        buffer_key._label.shorten = True
        buffer_key._label.shorten_from = "left"
        buffer_key.bind(size=lambda w, _s: setattr(
            w._label, "text_size", (max(1, w.width - dp(8)), w.height)))
        buffer_key._label.halign = "left"
        buffer_key._label.text_size = (
            max(1, buffer_key.width - dp(8)), buffer_key.height)
        self._buffer_label = buffer_key._label
        self._buffer_key = buffer_key
        self._bar_box.add_widget(buffer_key)

        scroll = ScrollView(do_scroll_x=True, do_scroll_y=False,
                            size_hint_x=8.0 / self.COLUMNS, bar_width=0)
        self._chips_box = BoxLayout(orientation="horizontal", spacing=0,
                                    size_hint_x=None)
        self._chips_box.bind(
            minimum_width=self._chips_box.setter("width"))
        scroll.add_widget(self._chips_box)
        self._bar_box.add_widget(scroll)

        # T-Rex runner (tap = jump, swipe down = duck)
        game = self._make_key(GAME_KEY, font_size=sp(14), seams=False)
        game.size_hint_x = 1.0 / self.COLUMNS
        self._bar_box.add_widget(game)

        hide = self._make_key(HIDE_KEY, font_size=sp(14), seams=False)
        hide.size_hint_x = 1.0 / self.COLUMNS
        self._bar_box.add_widget(hide)

        self._refresh_bar()

    def _refresh_bar(self):
        if self.english:
            return
        if self._buffer_label is not None:
            # Show only the tail that actually fits the code box, so the box
            # never wraps onto a second line and never wastes space on a "..."
            # prefix. `_buffer_char_width()` is exact for Unifont (monospace);
            # for a proportional font it uses the widest glyph, which errs on
            # the safe side. The label's `shorten` stays as a guard.
            text = self.buffer.upper()
            box = self._buffer_key.width if self._buffer_key else 0
            if box <= 1:
                # Before the first layout pass; approximate from the window.
                box = (self.width or Window.width) * 2.0 / self.COLUMNS
            fit = max(1, int(max(1, box - dp(8)) // self._buffer_char_width()))
            self._buffer_label.text = text[-fit:]
        if self._buffer_key is not None:
            self._buffer_key.value = self.buffer
        self._refresh_chips()

    def _buffer_char_width(self):
        """Advance of the widest glyph at the code-box font size, in pixels."""
        width = getattr(self, "_buffer_char_w", 0)
        if not width:
            probe = CoreLabel(text="M", font_name=FONT_NAME, font_size=sp(24))
            probe.refresh()
            width = max(1.0, probe.get_extents("M")[0])
            self._buffer_char_w = width
        return width

    def _refresh_chips(self):
        if self._chips_box is None:
            return
        self._chips_box.clear_widgets()
        if not self.candidates:
            # Empty state: a plain box, not a "selected" one.
            self._chips_box.add_widget(
                Chip("", selected=False, min_width=self._chip_min_width))
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
        # Chinese and English have their own row sets: Chinese uppercases the
        # letter labels, English keeps them lowercase.
        rows = ROWS if self.english else CHINESE_ROWS
        # rows[0] = number row (top), rows[-1] = toggle/space/enter (bottom).
        # Vertical BoxLayout: first child added = topmost.
        for row_index, row in enumerate(rows):
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

    # -- game mode -----------------------------------------------------
    def _toggle_game(self):
        if hasattr(self, '_game_mode') and self._game_mode:
            self._exit_game()
        else:
            self._enter_game()

    def _enter_game(self):
        self._game_mode = True
        # Save current state
        self._saved_english = self.english
        self._saved_buffer = self.buffer
        self._saved_candidates = self.candidates

        self.clear_widgets()

        # The game keeps a bar of its own so ▼ (hide) and "back to the keyboard"
        # are always reachable -- otherwise the game covers the only exit.
        bar = BoxLayout(orientation="horizontal", spacing=0,
                        size_hint_y=None, height=self.bar_h)
        with bar.canvas.before:
            Color(*BAR_BG)
            self._game_bar_bg = Rectangle()
        bar.bind(pos=self._paint_game_bar, size=self._paint_game_bar)

        back = self._make_key(BACK_KEY, font_size=sp(14))
        back.size_hint_x = 2.0 / self.COLUMNS
        bar.add_widget(back)

        hint = Key(
            label="tap = jump   swipe down = duck",
            value=None, action="noop", on_commit=self._emit,
            font_size=sp(13),
        )
        hint._label.halign = "left"
        hint.size_hint_x = 8.0 / self.COLUMNS
        bar.add_widget(hint)

        hide = self._make_key(HIDE_KEY, font_size=sp(14))
        hide.size_hint_x = 1.0 / self.COLUMNS
        bar.add_widget(hide)

        self.add_widget(bar)
        self._game_widget = GameWidget(on_exit=self._exit_game)
        self.add_widget(self._game_widget)
        self.height = self.bar_h + self.body_h + self.pad

    def _paint_game_bar(self, *_args):
        for child in self.children:
            if isinstance(child, BoxLayout) and getattr(self, "_game_bar_bg", None):
                self._game_bar_bg.pos = child.pos
                self._game_bar_bg.size = child.size
                break

    def _exit_game(self):
        if not hasattr(self, '_game_mode') or not self._game_mode:
            return
        self._game_mode = False
        GameSounds.stop_music()
        self._over_label = None

        # Restore keyboard. clear_widgets() drops the bar and rows *containers*
        # too, so they must go back before _build() refills them -- otherwise
        # the IME window is an empty (white) SDL surface.
        self.clear_widgets()
        self.add_widget(self._bar_box)
        self.add_widget(self._rows_box)
        self._build()

        # Restore state
        self.english = self._saved_english
        self.buffer = self._saved_buffer
        self.candidates = self._saved_candidates
        self._refresh_bar()

    # -- bridge --------------------------------------------------------
    def on_bridge_message(self, message):
        """Called on the Kivy main thread."""
        # The IME only talks to the keyboard, never to the game: any message
        # means the user is back to typing, so drop out of game mode. This
        # covers the ▼ key, the Back key and an app/IME switch alike.
        if getattr(self, "_game_mode", False):
            self._exit_game()

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
        Window.clearcolor = (1, 1, 1, 0.0 if FROSTED else 1.0)
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
