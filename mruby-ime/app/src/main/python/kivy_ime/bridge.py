# Copyright 2026 copy0401
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Localhost JSON bridge between the Kivy keyboard UI and the Kotlin IME.

The Kotlin ``MrubyimeIME`` runs a ``KivyBridge`` TCP server on 0.0.0.0:19876.
Protocol (one JSON object per line):

  Kotlin -> Kivy
    {"type": "candidates", "buffer": "abc", "candidates": ["你", "妳"]}

  Kivy -> Kotlin
    {"type": "key", "char": "a"}
    {"type": "backspace"}
    {"type": "space"}
    {"type": "enter"}
    {"type": "toggle_mode"}
    {"type": "hide"}

Selection of a candidate is done by sending its ``%selkey`` digit as a
``key`` message (the engine treats it as a selection while a composition is
alive), so no extra command is needed.

The bridge is intentionally dependency-free (stdlib only) and thread-safe:
``send`` may be called from the Kivy main thread while a daemon thread reads
incoming messages.
"""

import json
import socket
import threading

PORT = 19876


class ImeBridge:
    def __init__(self, host="127.0.0.1", port=PORT, on_message=None):
        self.host = host
        self.port = port
        self.on_message = on_message
        self.connected = False
        self._sock = None
        self._writer = None
        self._reader_thread = None

    # -- connection -----------------------------------------------------
    def connect(self, timeout=3.0):
        """Try once to connect. Returns True on success."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            sock.connect((self.host, self.port))
            sock.settimeout(None)
        except OSError as exc:
            print("ImeBridge: connect failed: %s" % (exc,), flush=True)
            return False

        self._sock = sock
        self._writer = sock.makefile("w", encoding="utf-8", newline="\n")
        self.connected = True
        self._reader_thread = threading.Thread(
            target=self._read_loop, args=(sock,), daemon=True
        )
        self._reader_thread.start()
        print("ImeBridge: connected to %s:%d" % (self.host, self.port), flush=True)
        return True

    def close(self):
        self.connected = False
        try:
            if self._writer:
                self._writer.close()
        except OSError:
            pass
        try:
            if self._sock:
                self._sock.close()
        except OSError:
            pass
        self._writer = None
        self._sock = None

    # -- reader ---------------------------------------------------------
    def _read_loop(self, sock):
        try:
            reader = sock.makefile("r", encoding="utf-8")
            while True:
                line = reader.readline()
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    message = json.loads(line)
                except ValueError:
                    print("ImeBridge: bad json: %r" % (line,), flush=True)
                    continue
                if self.on_message:
                    # The callback is responsible for hopping to the main
                    # thread (Clock.schedule_once) before touching widgets.
                    self.on_message(message)
        except OSError as exc:
            print("ImeBridge: read error: %s" % (exc,), flush=True)
        finally:
            self.connected = False

    # -- writer ---------------------------------------------------------
    def send(self, **payload):
        if not self._writer:
            return False
        # Drop keys whose value is None. A JSON null does NOT read back as null
        # on the Kotlin side: JSONObject.optString(name, null) returns the STRING
        # "null" (JSONObject.NULL is a non-null object, so JSON.toString() does
        # String.valueOf(NULL)). That string then reaches SimpleDateFormat and
        # throws "Illegal pattern character 'n'", which is why DT / W silently
        # did nothing. Omitting the key lets optString() return its fallback.
        payload = {k: v for k, v in payload.items() if v is not None}
        try:
            self._writer.write(json.dumps(payload) + "\n")
            self._writer.flush()
            return True
        except (OSError, ValueError) as exc:
            print("ImeBridge: send failed: %s" % (exc,), flush=True)
            self.connected = False
            return False

    # -- commands -------------------------------------------------------
    def key(self, char):
        return self.send(type="key", char=char)

    def backspace(self):
        return self.send(type="backspace")

    def space(self):
        return self.send(type="space")

    def enter(self):
        return self.send(type="enter")

    def toggle_mode(self):
        return self.send(type="toggle_mode")

    def hide(self):
        return self.send(type="hide")
