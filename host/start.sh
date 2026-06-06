#!/usr/bin/env bash
# Sets up the USB tunnels, grants camera, and launches the Portal Bridge app.
# Run this on the Mac with the Portal connected over USB (adb devices shows it).
set -e

PKG=com.portalbridge

echo "==> adb devices"
adb devices

echo "==> Tunnels over USB"
adb forward tcp:8080 tcp:8080     # Mac localhost:8080  ->  Portal camera server
adb reverse tcp:8081 tcp:8081     # Portal localhost:8081 ->  Mac screen server

echo "==> Granting CAMERA permission"
adb shell pm grant "$PKG" android.permission.CAMERA || true

echo "==> Launching app on the Portal (it won't show on the home screen; that's expected)"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

cat <<'EOF'

Next steps:
  1) Create a virtual display in BetterDisplay (Extend, ~1280x720).
  2) python3 screen_server.py --list           # find its index (usually 2)
  3) python3 screen_server.py --monitor 2      # start streaming it to the Portal
  4) OBS: add Browser source -> Local file webcam_page.html (1280x720),
     then click "Start Virtual Camera".
  5) Zoom: Video -> Camera -> "OBS Virtual Camera"; Audio -> your Mac mic.
  6) Drag the Zoom window onto the Portal display. Look into the lens.
EOF
