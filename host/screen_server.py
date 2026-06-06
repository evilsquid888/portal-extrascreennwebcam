#!/usr/bin/env python3
"""Capture a Mac display (ideally a BetterDisplay virtual display) and serve it as an
MJPEG stream the Portal app pulls over the adb-reverse tunnel.

  python3 screen_server.py --list            # see monitor indices
  python3 screen_server.py --monitor 2       # serve that display on :8081
"""
import argparse
import ctypes
import io
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import mss
from PIL import Image, ImageDraw

# CoreGraphics doesn't composite the cursor into captures, so we draw it ourselves.
if sys.platform == "darwin":
    class _CGPoint(ctypes.Structure):
        _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]

    _cg = ctypes.CDLL("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
    _cf = ctypes.CDLL("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    _cg.CGEventCreate.restype = ctypes.c_void_p
    _cg.CGEventCreate.argtypes = [ctypes.c_void_p]
    _cg.CGEventGetLocation.restype = _CGPoint
    _cg.CGEventGetLocation.argtypes = [ctypes.c_void_p]
    _cf.CFRelease.argtypes = [ctypes.c_void_p]

    def mouse_pos():
        ev = _cg.CGEventCreate(None)
        loc = _cg.CGEventGetLocation(ev)
        _cf.CFRelease(ev)
        return loc.x, loc.y
elif sys.platform == "win32":
    class _WinPoint(ctypes.Structure):
        _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

    def mouse_pos():
        pt = _WinPoint()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt))
        return pt.x, pt.y
else:
    def mouse_pos():
        return None

# Arrow polygon, hotspot at (0,0), ~21px tall at scale 1 (black body, white outline like macOS).
_CURSOR = [(0, 0), (0, 16), (4, 13), (7, 20), (10, 18.5), (7, 12), (12, 12)]


def draw_cursor(img, x, y, scale):
    pts = [(x + px * scale, y + py * scale) for px, py in _CURSOR]
    ImageDraw.Draw(img).polygon(pts, fill="black", outline="white")


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
                    pos = mouse_pos()
                    if pos is not None:
                        mx, my = pos[0] - mon["left"], pos[1] - mon["top"]
                        if 0 <= mx < mon["width"] and 0 <= my < mon["height"]:
                            sx = shot.size[0] / mon["width"]  # retina scale
                            draw_cursor(img, mx * sx, my * (shot.size[1] / mon["height"]), sx)
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
