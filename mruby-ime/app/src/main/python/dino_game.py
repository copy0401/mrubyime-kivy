"""Standalone T-Rex runner — desktop only.

Reuses the exact widget the IME game mode runs, so the phone and the PC show
the same dinosaur. Adds hardware-keyboard controls, which the Android build
does not need.

    cd mruby-ime
    ../kivy-venv/bin/python app/src/main/python/dino_game.py

Controls
    Space / ↑   jump   (tap = jump, swipe down = duck on touch)
    ↓           duck (hold)
    R / →        restart after GAME OVER
    Esc          quit
"""

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from kivy.app import App
from kivy.core.window import Window

from kivy_ime.keyboard import GameWidget

KEY_ESCAPE = 27
KEY_SPACE = 32
KEY_UP = 273
KEY_DOWN = 274
KEY_RIGHT = 275
KEY_R = 114


class DinoRunnerApp(App):
    title = "mruby-ime · T-Rex runner"

    def build(self):
        Window.clearcolor = (1, 1, 1, 1)
        Window.bind(on_key_down=self._on_key_down,
                    on_key_up=self._on_key_up)
        self.game = GameWidget(size_hint=(1, 1))
        return self.game

    # -- jump / duck ---------------------------------------------------
    def _jump(self):
        game = self.game
        if game.game_over:
            game._reset_game()
            return
        if game.is_jumping or game.is_ducking:
            return
        game.is_jumping = True
        game.is_ducking = False
        game.dino_vel_y = game.JUMP_VELOCITY
        from kivy_ime.keyboard import GameSounds
        GameSounds.play_jump()

    def _set_duck(self, ducking):
        game = self.game
        if game.is_jumping:
            return
        game.is_ducking = ducking

    # -- keys -----------------------------------------------------------
    def _on_key_down(self, _window, key, _scancode, _codepoint, _modifiers):
        if key == KEY_ESCAPE:
            self.stop()
        elif key in (KEY_SPACE, KEY_UP):
            self._jump()
        elif key == KEY_DOWN:
            self._set_duck(True)
        elif key in (KEY_R, KEY_RIGHT):
            if self.game.game_over:
                self.game._reset_game()

    # NOTE: Window.on_key_up has a shorter signature than on_key_down
    # (no codepoint / modifiers).
    def _on_key_up(self, _window, key, _scancode):
        if key == KEY_DOWN:
            self._set_duck(False)


if __name__ == "__main__":
    DinoRunnerApp().run()