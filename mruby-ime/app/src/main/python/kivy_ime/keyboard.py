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

# Key press effects
RIPPLE = (0.2, 0.6, 1.0, 0.5)   # Blue cyan ripple
GLOW = (0.2, 0.8, 1.0, 0.3)     # Blue glow

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

    # Key press effects
    ripple_r = NumericProperty(0.0)
    ripple_a = NumericProperty(0.0)
    glow_r = NumericProperty(0.0)
    glow_a = NumericProperty(0.0)

    def __init__(self, label="", value=None, action="code", sub_label="",
                 gestures=None, repeat=False, long_press=None,
                 on_commit=None, font_size=None, **kwargs):
        super().__init__(**kwargs)
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
            # Key press effects
            self._ripple_color = Color(*RIPPLE[:3], 0)
            self._ripple = Ellipse()
            self._glow_color = Color(*GLOW[:3], 0)
            self._glow = Ellipse()

        self.bind(pos=self._sync, size=self._sync, press=self._sync,
                  edge_left=self._sync, edge_top=self._sync,
                  ripple_r=self._sync, ripple_a=self._sync,
                  glow_r=self._sync, glow_a=self._sync)
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

        # Key press effects
        cx, cy = self.center_x, self.center_y
        r = self.ripple_r
        self._ripple.pos = (cx - r, cy - r)
        self._ripple.size = (2 * r, 2 * r)
        self._ripple_color.rgba = RIPPLE[:3] + (self.ripple_a,)

        rg = self.glow_r
        self._glow.pos = (cx - rg, cy - rg)
        self._glow.size = (2 * rg, 2 * rg)
        self._glow_color.rgba = GLOW[:3] + (self.glow_a,)

    # -- feedback ------------------------------------------------------
    def _animate(self, target, duration):
        Animation.cancel_all(self, "press")
        Animation(press=target, d=duration).start(self)

    # Key press effects
    def _trigger_effects(self):
        # Ripple expands from touch point
        self.ripple_r = 0
        self.ripple_a = 0.6
        Animation(ripple_r=max(self.width, self.height), ripple_a=0, d=0.3).start(self)
        # Glow expands behind key
        self.glow_r = self.width * 0.3
        self.glow_a = 0.4
        Animation(glow_r=self.width * 1.2, glow_a=0, d=0.25).start(self)

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
        self._trigger_effects()
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
            self._bg_color = Color(*(CHIP_SELECTED if selected else CLEAR))
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
        self._bg_color.rgba = CHIP_SELECTED if self.selected else CLEAR
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
        
        # Bind size
        self.bind(size=self._on_size, pos=self._on_pos)
        
        # Game loop
        self._game_clock = Clock.schedule_interval(self._update, 1/60)
        # Start immediately; _on_size also resets once a real size arrives.
        Clock.schedule_once(lambda _dt: self._reset_game(), 0)
    
    def _on_size(self, *args):
        if not self.running and not self.game_over and self.width > 0 and self.height > 0:
            self._reset_game()
        self.draw()
    
    def _on_pos(self, *args):
        self.draw()
    
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
    
    def draw(self):
        """Draw the game on canvas."""
        self.canvas.clear()
        
        with self.canvas:
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
                # Would draw "GAME OVER" text here - simplified for now
            
            # Score
            Color(0.2, 0.2, 0.2, 1)
            # Score display - using a simple rectangle as placeholder
            Rectangle(pos=(self.width - dp(120), self.height - dp(50)), size=(dp(100), dp(30)))


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

# Shared bar keys: the game button (T-Rex runner) and the hide button.
GAME_KEY = {"label": "\U0001f996", "action": "game"}
HIDE_KEY = {"label": "\u25bc", "action": "hide", "long_press": "toggle_hide_rows"}

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
        self._build()

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
                            size_hint_x=8.0 / self.COLUMNS, bar_width=0)
        self._chips_box = BoxLayout(orientation="horizontal", spacing=0,
                                    size_hint_x=None)
        self._chips_box.bind(
            minimum_width=self._chips_box.setter("width"))
        scroll.add_widget(self._chips_box)
        self._bar_box.add_widget(scroll)

        # T-Rex runner (tap = jump, swipe down = duck)
        game = self._make_key(GAME_KEY, font_size=sp(14))
        game.size_hint_x = 1.0 / self.COLUMNS
        self._bar_box.add_widget(game)

        hide = self._make_key(HIDE_KEY, font_size=sp(14))
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
        
        # Switch to game widget
        self.clear_widgets()
        self._game_widget = GameWidget(on_exit=self._exit_game)
        self.add_widget(self._game_widget)
        self.height = self.bar_h + self.body_h + self.pad

    def _exit_game(self):
        if not hasattr(self, '_game_mode') or not self._game_mode:
            return
        self._game_mode = False
        GameSounds.stop_music()
        
        # Restore keyboard
        self.clear_widgets()
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
