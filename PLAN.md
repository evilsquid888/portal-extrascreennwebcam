# Plan — Portal as Extra Screen + Webcam (both at once)

## Goal
Use a Meta Portal Go (Android-debug enabled) as a Mac peripheral that, in the **same Zoom
call**, acts as:
1. a **second monitor** you can drag the Zoom window onto, and
2. a **webcam** pointed at you,

so you get eye contact (look at the call = look at the lens). Audio comes from the Mac.

## Why a custom app (not off-the-shelf)
- Android runs **one foreground app at a time**. A screen app must own the display; a webcam app
  needs the camera and loses it when backgrounded (API 28+ background-camera block). So no
  two-app combo (e.g. Duet + Iriun) can do both simultaneously — and Duet is now defunct anyway.
- **One process** can legally hold the camera AND own the display → both at once. That's the app.

## Architecture
```
                 USB cable + adb tunnels
PORTAL app  ─ camera MJPEG  :8080 ─►(adb forward)─► Mac: OBS browser source ─► OBS Virtual Cam ─► Zoom
   (one  ◄─ screen MJPEG    :8081 ◄─(adb reverse)◄─ Mac: screen_server.py capturing a virtual display
  process)
```
- Transport: **MJPEG both directions**, tunneled over USB via `adb forward` / `adb reverse`
  (no IP discovery, low latency, trivial to debug). Wi-Fi works later by swapping `localhost`
  for the LAN IP.
- "Drag Zoom onto it" works because **BetterDisplay** creates a real *virtual* extended display;
  we capture that display and stream it to the Portal.

## Components
| Piece | Tech | Role |
|-------|------|------|
| Portal app | Kotlin, 1 Activity | Camera2(JPEG)→MJPEG server `:8080`; MJPEG client of `localhost:8081`→SurfaceView |
| `host/screen_server.py` | Python, mss+Pillow | Capture virtual display → MJPEG `:8081` (Win/macOS/Linux) |
| `host/webcam_page.html` | HTML | Shows `:8080` stream; load as OBS Browser source |
| OBS Virtual Camera | OBS | Presents Portal cam to Zoom as a normal webcam (Win/macOS) |
| Virtual display | BetterDisplay (macOS) / IddSampleDriver·usbmmidd·HDMI dummy (Windows) | Creates the virtual second display |
| `host/start.ps1` / `host/start.sh` | PowerShell / bash | adb tunnels + grant camera + launch app |

## File layout
```
portal-extrascreennwebcam/
  PLAN.md            <- this file
  README.md          <- run/build runbook
  host/              <- cross-platform (Windows / macOS / Linux)
    screen_server.py
    webcam_page.html
    start.ps1        # Windows launcher
    start.sh         # macOS / Linux launcher
    requirements.txt
  android/
    settings.gradle  build.gradle  gradle.properties
    app/
      build.gradle
      src/main/AndroidManifest.xml
      src/main/res/layout/activity_main.xml
      src/main/java/com/portalbridge/
        MainActivity.kt
        CameraMjpegServer.kt
        ScreenMjpegClient.kt
```

## Milestones
- [x] M0 — Decide architecture (combined app; MJPEG-over-USB; OBS for webcam)
- [x] M1 — Scaffold project (Android app + Mac scripts + runbook)
- [x] M2 — Build APK ✓ verified: AGP 9.0.1 / Gradle 9.2.1 / JDK 21, compileSdk 36 / build-tools 36.1.0,
        minSdk 26 / targetSdk 30 → `app-debug.apk` (3.1 MB), BUILD SUCCESSFUL. ✓ installed + launches on Portal Go.
- [ ] M3 — Prove camera half: app runs, `adb forward`, OBS browser source shows the lens,
        OBS Virtual Camera selectable in Zoom
- [x] M4 — Prove screen half ✓ verified on Portal Go: BetterDisplay virtual display (extended) +
        `screen_server.py` → Portal renders it live (~17 fps). Sharpness requires matching the panel:
        virtual display must be 16:10, 1280x800, non-HiDPI (see README). Cursor is composited
        server-side (CoreGraphics doesn't capture it).
- [ ] M5 — Both at once in a real Zoom call; tune fps/quality/latency
- [ ] M6 (optional) — Native macOS CoreMediaIO camera extension to drop the OBS dependency

## Later / ideas
- **YUV capture path**: switch the ImageReader from HAL JPEG to YUV_420_888 and encode JPEG
  in-app (YuvImage or libjpeg-turbo). Could shave the HAL JPEG stall (~20–30 ms of latency)
  at the cost of CPU/battery on the QCS605. Measure glass-to-glass before/after to decide.
- **Smart framing / tracking**: works on camera 0 even from our sideloaded app (!) — Meta's
  `com.facebook.portal.aiservice` grabs camera 1 (raw sensor) whenever we stream camera 0 and
  drives the processed feed's crop. Observed quirk: tracking engages position-dependently
  (e.g. standing vs sitting). If we ever want it controllable, expose a launch toggle via
  intent extra plumbed from start.sh.
- **TODO (validate later): disabling camera tracking doesn't seem to work.** Figure out
  whether Meta's smart framing can actually be turned off for our stream — candidates:
  `adb shell pm disable-user --user 0 com.facebook.portal.aiservice` (reversible with
  `pm enable`), or a CaptureRequest control the aiservice respects. Verify the feed goes
  static-wide and that nothing else breaks (aiservice may be a dependency for camera 0).
- **Camera 1 experiment (done, dead end):** forcing `openCamera("1")` via the new
  `--es cameraId 1` intent extra fails instantly — the framework rejects IDs outside
  `cameraIdList=[0]`. Raw sensor confirmed unreachable without root.

## Risks / unknowns (verify on real hardware)
- **Camera access** on Portal's stripped Android — does Camera2 open the front sensor at all?
  (Make-or-break; test first.)
- Camera is **raw wide-angle**, no smart-framing.
- **720p is the resolution ceiling** (verified): the Portal Go HAL exposes two front cameras —
  camera 0 (Meta's processed path, max 1280x720@30) and camera 1 (raw 12MP, 1080p/4K-capable) —
  but `cameraIdList` only shows camera 0 to third-party apps; camera 1 is framework-hidden and
  held by `com.facebook.portal.aiservice`. Root would be required to use it.
- **Mic unusable** without Meta DSP → audio from Mac (accepted).
- Portal Android version / minSdk — set to 26; adjust if it won't install.
- App is hidden from the home screen → always launched via `adb shell monkey` (start.sh).
- Second-screen latency acceptable for Zoom UI, not fast video.

## Open questions for you
- Build host/toolchain: Android Studio on the Mac, or Gradle CLI? (changes only build steps)
- Portal Go Android version (`adb shell getprop ro.build.version.release`) so I can pin minSdk.

## Status
Scaffold complete (M1). Next action: build the APK and run M3 (camera half) first — it's the
highest-risk piece. See README.md for exact commands.
