# Portal Bridge launcher (Windows / PowerShell).
# Run on the host PC with the Portal connected over USB (adb devices shows it as 'device').

$pkg = "com.portalbridge"

Write-Host "==> adb devices"
adb devices

Write-Host "==> Tunnels over USB"
adb forward tcp:8080 tcp:8080     # PC localhost:8080  ->  Portal camera server
adb reverse tcp:8081 tcp:8081     # Portal localhost:8081 ->  PC screen server

Write-Host "==> Granting CAMERA permission"
adb shell pm grant $pkg android.permission.CAMERA

Write-Host "==> Launching app on the Portal (it won't appear on the home screen; that's expected)"
adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null

Write-Host @"

Next steps:
  1) Create a virtual display (see README: IddSampleDriver / usbmmidd_v2 / HDMI dummy plug).
  2) python screen_server.py --list           # find its index (usually 2)
  3) python screen_server.py --monitor 2      # start streaming it to the Portal
  4) OBS: add Browser source -> Local file webcam_page.html (1280x720),
     then click "Start Virtual Camera".
  5) Zoom: Video -> Camera -> "OBS Virtual Camera"; Audio -> your PC mic.
  6) Drag the Zoom window onto the Portal display. Look into the lens.
"@
