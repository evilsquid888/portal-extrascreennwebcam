#!/usr/bin/env python3
"""Capture a Mac display (ideally a BetterDisplay virtual display) and serve it as an
MJPEG stream the Portal app pulls over the adb-reverse tunnel.

  python3 screen_server.py --list            # see monitor indices
  python3 screen_server.py --monitor 2       # serve that display on :8081
"""
import argparse
import io
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import mss
from PIL import Image


def make_handler(monitor_index, fps, quality):
    interval = 1.0 / fps

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # quiet

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type",
                             "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Pragma", "no-cache")
            self.end_headers()
            with mss.mss() as sct:
                if monitor_index >= len(sct.monitors):
                    print(f"monitor {monitor_index} not found; run --list", file=sys.stderr)
                    return
                mon = sct.monitors[monitor_index]
                while True:
                    start = time.time()
                    shot = sct.grab(mon)
                    img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
                    buf = io.BytesIO()
                    img.save(buf, format="JPEG", quality=quality)
                    data = buf.getvalue()
                    try:
                        self.wfile.write(b"--frame\r\n")
                        self.wfile.write(b"Content-Type: image/jpeg\r\n")
                        self.wfile.write(f"Content-Length: {len(data)}\r\n\r\n".encode())
                        self.wfile.write(data)
                        self.wfile.write(b"\r\n")
                    except (BrokenPipeError, ConnectionResetError):
                        break
                    sleep = interval - (time.time() - start)
                    if sleep > 0:
                        time.sleep(sleep)

    return Handler


def list_monitors():
    with mss.mss() as sct:
        for i, m in enumerate(sct.monitors):
            label = "all" if i == 0 else ("primary" if i == 1 else f"display {i}")
            print(f"[{i}] {label}: {m['width']}x{m['height']} at ({m['left']},{m['top']})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--monitor", type=int, default=2,
                    help="monitor index from --list (virtual display is usually 2)")
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--fps", type=int, default=20)
    ap.add_argument("--quality", type=int, default=70)
    ap.add_argument("--list", action="store_true", help="list monitors and exit")
    args = ap.parse_args()

    if args.list:
        list_monitors()
        return

    server = ThreadingHTTPServer(("127.0.0.1", args.port),
                                 make_handler(args.monitor, args.fps, args.quality))
    print(f"Serving display {args.monitor} as MJPEG on http://127.0.0.1:{args.port}  "
          f"({args.fps} fps, q{args.quality})")
    print("The Portal app reads this via 'adb reverse tcp:8081 tcp:8081'.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
