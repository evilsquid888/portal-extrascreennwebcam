# Portal Bridge

Turn a Meta Portal Go (Android-debug enabled) into **both** a second monitor **and** a webcam
for your computer — at the same time — so you can drag your Zoom window onto the Portal screen
and look into its camera for natural eye contact.

**Cross-platform host:** works on **Windows and macOS** (and Linux). The Android app and the
Python/OBS host pieces are OS-agnostic; only the launcher script and the virtual-display tool
differ per OS.

```
                 USB cable + adb tunnels
PORTAL app  ─ camera MJPEG  :8080 ─►(adb forward)─► Host: OBS browser source ─► OBS Virtual Cam ─► Zoom
   (one  ◄─ screen MJPEG    :8081 ◄─(adb reverse)◄─ Host: screen_server.py capturing a virtual display
  process)
```

One Android app does both, so there's no "two foreground apps fighting over the camera"
problem: a single process legally holds the camera **and** owns the display.

Audio comes from the host (the Portal's far-field mic array needs Meta's DSP and is unusable to
a sideloaded app). The camera is the raw front sensor — wide angle, no smart-framing.

---

## What you need on the host

| Need | Windows | macOS |
|------|---------|-------|
| adb | `winget install Google.PlatformTools` (or Android SDK) | `brew install android-platform-tools` |
| Python 3 + deps | `pip install -r host/requirements.txt` | `pip3 install -r host/requirements.txt` |
| Virtual display | Indirect Display Driver (**usbmmidd_v2**, **IddSampleDriver**) or a cheap **HDMI dummy plug** | **BetterDisplay** (free) |
| Webcam bridge | **OBS Studio** + Virtual Camera | **OBS Studio** + Virtual Camera |
| Build the APK | Android Studio (Windows) or Gradle CLI | Android Studio (Mac) or Gradle CLI |

> **Virtual display = the trick that lets you "drag Zoom onto it."** It makes the OS believe a
> real extended monitor exists; we capture that monitor and stream it to the Portal. A ~$8 HDMI
> dummy plug is the zero-software option on Windows.

---

## Build the APK

Open `android/` in **Android Studio** and press Run/Build (it generates the Gradle wrapper),
**or** from the command line with the Android SDK + JDK installed:

```bash
cd android
gradle wrapper             # first time only, if a wrapper doesn't exist yet
./gradlew assembleDebug     # Windows: .\gradlew.bat assembleDebug
```

Install on the Portal (USB connected, `adb devices` shows it as `device`):

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Run a session

1. **Make a virtual display** set to **Extend** (not Mirror), sized exactly like the Portal panel
   (**1280x800 for Portal Go**, 16:10):
   - **Windows:** install an Indirect Display Driver (usbmmidd_v2 / IddSampleDriver) or plug in
     an HDMI dummy plug.
   - **macOS:** create a virtual display in BetterDisplay (`brew install --cask betterdisplay`).

   > **Sharp text = match the panel resolution 1:1.** Any mismatch means the Portal rescales
   > every frame and text smears. For a Portal Go:
   > - When creating the BetterDisplay virtual screen, choose aspect ratio **16:10**.
   > - Set its resolution to exactly **1280x800**, and pick the **native (non-HiDPI)** variant —
   >   a "1280x800 HiDPI" mode renders at 2560x1600 internally and gets downscaled again.
   > - macOS may make the new display your *main* one (menu bar moves to it). Put it back:
   >   System Settings → Displays → **Arrange…** → drag the white menu-bar strip onto your
   >   built-in display.

2. **Find its monitor index:**
   ```bash
   python host/screen_server.py --list       # macOS: python3
   ```
   The virtual display is usually index `2`. (0 = all combined, 1 = primary.)

3. **Launch the tunnels + app:**
   - **Windows:** `powershell -ExecutionPolicy Bypass -File host\start.ps1`
   - **macOS:** `bash host/start.sh`

4. **Start the screen server** for that display:
   ```bash
   python host/screen_server.py --monitor 2
   ```
   The Portal now shows whatever is on the virtual display.

5. **Bridge the camera into OBS:**
   - OBS → Sources → **+** → **Browser** → check *Local file* → pick `host/webcam_page.html`,
     set Width/Height to 1280x720. You should see the Portal's camera.
   - Click **Start Virtual Camera**.

6. **In Zoom:** Settings → Video → Camera → **OBS Virtual Camera**. Audio → your host mic.

7. **Drag the Zoom window onto the Portal monitor.** Look into the Portal's camera. Done.

---

## Tuning

- `screen_server.py --fps 20 --quality 70` — raise/lower for smoothness vs. latency.
- Camera resolution/fps in `CameraMjpegServer.kt` (default 1280x720 @ 20fps).
- **Over Wi-Fi** instead of USB: skip `adb forward/reverse`, change the app's screen URL to the
  host's LAN IP, and point OBS at `http://<portal-ip>:8080`.

## Known limits

- Raw wide-angle camera, no auto-framing (that was Meta's software).
- Portal mic unusable — use the host's mic.
- Second-screen latency is fine for Zoom UI / reference, not fast video.
- App won't appear on Portal's home screen; it's launched via `adb shell monkey` (start script).

## Layout

```
host/      screen_server.py, webcam_page.html, start.ps1, start.sh, requirements.txt
android/   Gradle project — the single combined Portal app
PLAN.md    architecture, milestones, risks
```
