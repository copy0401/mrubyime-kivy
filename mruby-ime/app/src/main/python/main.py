"""Entry point for the Kivy front-end (python-for-android ``source.dir``)."""

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from kivy_ime.keyboard import KivyIMEApp  # noqa: E402

if __name__ == "__main__":
    KivyIMEApp().run()
