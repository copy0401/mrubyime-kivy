"""
Kivy-based keyboard UI for mruby-ime.
Connects to Kotlin IME service via local socket.
"""

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.gridlayout import GridLayout
from kivy.uix.widget import Widget
from kivy.core.window import Window
from kivy.clock import Clock
from kivy.lang import Builder
import socket
import threading
import json
import sys

# KV language for styling
KV = '''
<CandidateChip@Button>:
    font_size: '20sp'
    size_hint_y: None
    height: '48dp'
    text_size: self.size
    halign: 'center'
    valign: 'middle'

<KeyButton@Button>:
    font_size: '20sp'
    size_hint_y: None
    height: '48dp'
    text_size: self.size
    halign: 'center'
    valign: 'middle'

<KeyboardWidget>:
    orientation: 'vertical'
    spacing: '2dp'
    padding: '3dp'

    BoxLayout:
        id: buffer_box
        orientation: 'horizontal'
        size_hint_y: None
        height: '48dp'
        canvas.before:
            Color:
                rgba: 0.9, 0.9, 0.9, 1
            RoundedRectangle:
                pos: self.pos
                size: self.size
                radius: [4]
        Label:
            id: buffer_label
            text: ''
            font_size: '24sp'
            bold: True
            color: 0, 0, 0, 1
            halign: 'left'
            valign: 'middle'
            text_size: self.width - 16, self.height
            padding_x: 8

    ScrollView:
        id: candidate_scroll
        size_hint_y: 0.25
        do_scroll_x: True
        do_scroll_y: False
        bar_width: 4
        GridLayout:
            id: candidate_grid
            size_hint_y: None
            height: '48dp'
            spacing: '4dp'
            padding: '4dp'
            cols: 0  # auto

    BoxLayout:
        orientation: 'vertical'
        spacing: '2dp'
        size_hint_y: 0.65

        BoxLayout:
            orientation: 'horizontal'
            spacing: '1dp'
            size_hint_y: None
            height: '44dp'
            Button:
                text: '1'
                font_size: '18sp'
                on_press: root.on_key('1')
            Button:
                text: '2'
                font_size: '18sp'
                on_press: root.on_key('2')
            Button:
                text: '3'
                font_size: '18sp'
                on_press: root.on_key('3')
            Button:
                text: '4'
                font_size: '18sp'
                on_press: root.on_key('4')
            Button:
                text: '5'
                font_size: '18sp'
                on_press: root.on_key('5')
            Button:
                text: '6'
                font_size: '18sp'
                on_press: root.on_key('6')
            Button:
                text: '7'
                font_size: '18sp'
                on_press: root.on_key('7')
            Button:
                text: '8'
                font_size: '18sp'
                on_press: root.on_key('8')
            Button:
                text: '9'
                font_size: '18sp'
                on_press: root.on_key('9')
            Button:
                text: '0'
                font_size: '18sp'
                on_press: root.on_key('0')
            Button:
                text: '-'
                font_size: '18sp'
                on_press: root.on_key('-')
            Button:
                text: '='
                font_size: '18sp'
                on_press: root.on_key('=')

        BoxLayout:
            orientation: 'horizontal'
            spacing: '1dp'
            size_hint_y: None
            height: '44dp'
            Button:
                text: 'q'
                font_size: '18sp'
                on_press: root.on_key('q')
            Button:
                text: 'w'
                font_size: '18sp'
                on_press: root.on_key('w')
            Button:
                text: 'e'
                font_size: '18sp'
                on_press: root.on_key('e')
            Button:
                text: 'r'
                font_size: '18sp'
                on_press: root.on_key('r')
            Button:
                text: 't'
                font_size: '18sp'
                on_press: root.on_key('t')
            Button:
                text: 'y'
                font_size: '18sp'
                on_press: root.on_key('y')
            Button:
                text: 'u'
                font_size: '18sp'
                on_press: root.on_key('u')
            Button:
                text: 'i'
                font_size: '18sp'
                on_press: root.on_key('i')
            Button:
                text: 'o'
                font_size: '18sp'
                on_press: root.on_key('o')
            Button:
                text: 'p'
                font_size: '18sp'
                on_press: root.on_key('p')
            Button:
                text: '['
                font_size: '18sp'
                on_press: root.on_key('[')
            Button:
                text: ']'
                font_size: '18sp'
                on_press: root.on_key(']')

        BoxLayout:
            orientation: 'horizontal'
            spacing: '1dp'
            size_hint_y: None
            height: '44dp'
            Button:
                text: 'a'
                font_size: '18sp'
                on_press: root.on_key('a')
            Button:
                text: 's'
                font_size: '18sp'
                on_press: root.on_key('s')
            Button:
                text: 'd'
                font_size: '18sp'
                on_press: root.on_key('d')
            Button:
                text: 'f'
                font_size: '18sp'
                on_press: root.on_key('f')
            Button:
                text: 'g'
                font_size: '18sp'
                on_press: root.on_key('g')
            Button:
                text: 'h'
                font_size: '18sp'
                on_press: root.on_key('h')
            Button:
                text: 'j'
                font_size: '18sp'
                on_press: root.on_key('j')
            Button:
                text: 'k'
                font_size: '18sp'
                on_press: root.on_key('k')
            Button:
                text: 'l'
                font_size: '18sp'
                on_press: root.on_key('l')
            Button:
                text: ';'
                font_size: '18sp'
                on_press: root.on_key(';')
            Button:
                text: "'"
                font_size: '18sp'
                on_press: root.on_key("'")
            Button:
                text: '\\'
                font_size: '18sp'
                on_press: root.on_key('\\')

        BoxLayout:
            orientation: 'horizontal'
            spacing: '1dp'
            size_hint_y: None
            height: '44dp'
            Button:
                text: 'z'
                font_size: '18sp'
                on_press: root.on_key('z')
            Button:
                text: 'x'
                font_size: '18sp'
                on_press: root.on_key('x')
            Button:
                text: 'c'
                font_size: '18sp'
                on_press: root.on_key('c')
            Button:
                text: 'v'
                font_size: '18sp'
                on_press: root.on_key('v')
            Button:
                text: 'b'
                font_size: '18sp'
                on_press: root.on_key('b')
            Button:
                text: 'n'
                font_size: '18sp'
                on_press: root.on_key('n')
            Button:
                text: 'm'
                font_size: '18sp'
                on_press: root.on_key('m')
            Button:
                text: ','
                font_size: '18sp'
                on_press: root.on_key(',')
            Button:
                text: '.'
                font_size: '18sp'
                on_press: root.on_key('.')
            Button:
                text: '/'
                font_size: '18sp'
                on_press: root.on_key('/')
            Button:
                text: '⌫'
                font_size: '18sp'
                on_press: root.on_backspace()

        BoxLayout:
            orientation: 'horizontal'
            spacing: '1dp'
            size_hint_y: None
            height: '44dp'
            Button:
                text: '中/英'
                font_size: '16sp'
                on_press: root.on_toggle_mode()
                size_hint_x: 0.25
            Button:
                text: ' '
                font_size: '18sp'
                on_press: root.on_space()
                size_hint_x: 0.5
            Button:
                text: '▼'
                font_size: '18sp'
                on_press: root.on_hide()
                size_hint_x: 0.25
"""


class KeyboardWidget(BoxLayout):
    """Main keyboard widget - connects to Kotlin IME service via socket."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.socket = None
        self.writer = None
        self.reader_thread = None
        self._connect_to_ime()

    def _connect_to_ime(self):
        """Connect to the Kotlin IME service via local socket."""
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.connect(('127.0.0.1', 19876))
            self.writer = self.socket.makefile('w', encoding='utf-8')
            self.reader_thread = threading.Thread(target=self._read_loop, daemon=True)
            self.reader_thread.start()
        except Exception as e:
            print(f"KivyIME: Failed to connect to IME service: {e}")

    def _read_loop(self):
        """Read messages from Kotlin IME service."""
        try:
            reader = self.socket.makefile('r', encoding='utf-8')
            while True:
                line = reader.readline()
                if not line:
                    break
                line = line.strip()
                if line:
                    Clock.schedule_once(lambda dt: self._handle_message(line), 0)
        except Exception as e:
            print(f"KivyIME: Read error: {e}")

    def _handle_message(self, line: str):
        """Handle incoming message from Kotlin IME service."""
        try:
            data = json.loads(line)
            msg_type = data.get('type')
            if msg_type == 'candidates':
                buffer_text = data.get('buffer', '')
                candidates = data.get('candidates', [])
                self._update_ui(buffer_text, candidates)
        except Exception as e:
            print(f"KivyIME: Handle error: {e}")

    def _update_ui(self, buffer_text: str, candidates: list):
        """Update the UI with new buffer text and candidates."""
        self.ids.buffer_label.text = buffer_text

        # Update candidate chips
        self.ids.candidate_grid.clear_widgets()
        for cand in candidates[:16]:
            chip = CandidateChip(text=cand)
            chip.bind(on_press=lambda inst, c=cand: self.on_candidate_selected(c))
            self.ids.candidate_grid.add_widget(chip)

    def on_key(self, ch: str):
        """Handle key press - send to Kotlin IME."""
        if self.writer:
            msg = json.dumps({'type': 'key', 'char': ch})
            self.writer.write(msg + '\n')
            self.writer.flush()

    def on_backspace(self):
        """Handle backspace key."""
        if self.writer:
            msg = json.dumps({'type': 'backspace'})
            self.writer.write(msg + '\n')
            self.writer.flush()

    def on_space(self):
        """Handle space key."""
        if self.writer:
            msg = json.dumps({'type': 'space'})
            self.writer.write(msg + '\n')
            self.writer.flush()

    def on_enter(self):
        """Handle enter key."""
        if self.writer:
            msg = json.dumps({'type': 'enter'})
            self.writer.write(msg + '\n')
            self.writer.flush()

    def on_toggle_mode(self):
        """Handle mode toggle key."""
        if self.writer:
            msg = json.dumps({'type': 'toggle_mode'})
            self.writer.write(msg + '\n')
            self.writer.flush()

    def on_hide(self):
        """Handle hide keyboard key."""
        if self.writer:
            msg = json.dumps({'type': 'hide'})
            self.writer.write(msg + '\n')
            self.writer.flush()

    def on_candidate_selected(self, candidate: str):
        """Handle candidate selection."""
        if self.writer:
            msg = json.dumps({'type': 'candidate', 'candidate': candidate})
            self.writer.write(msg + '\n')
            self.writer.flush()


class KivyIMEApp(App):
    """Kivy application for the IME keyboard."""

    def build(self):
        self.title = '小蝦米輸入法'
        return KeyboardWidget()


if __name__ == '__main__':
    KivyIMEApp().run()
