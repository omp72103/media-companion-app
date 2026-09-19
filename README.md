# Media Companion App

A tiny Android app that runs on the **business phone** and fully automates the
missed-call → SMS reply. No buttons, no manual steps — install it once and it
runs in the background forever.

## How it works
1. A customer calls the business number and hangs up (missed call) or you reject it.
2. The app detects it instantly from the phone's Call Log.
3. It checks your rules (triggers, business hours, cooldown) cached from the server.
4. It replies with your business info **using the phone's own SMS** — zero cost
   per message (uses the phone's existing SMS pack).
5. It reports the call + SMS to your dashboard so Call/SMS history stays live.

> Sending happens on the phone's own SIM, so there is no per-message charge and
> no paid SMS gateway required.

## One-time setup

### 1. Register a device in your app
- Open your Media app → **Auto Call SMS → Devices → Register**.
- Choose provider **native_android** and save.
- Copy the **Device ID** and **Token** shown for that device.

### 2. Build & install the companion app

**Option A — automatic (recommended, no Android Studio):**
The build workflow is included at `android-companion-app/.github/workflows/build-companion-apk.yml`.
Because the Base44 GitHub sync can't host Actions workflows, push the companion app
to its **own** GitHub repo (the `android-companion-app/` folder's contents as the repo root,
including the `.github/` folder). Then:
1. GitHub auto-builds on every push (or run it manually from the repo's **Actions** tab).
2. Open the completed **"Build Companion APK"** run → scroll to **Artifacts** →
   download `media-companion-apk`.
3. Transfer the `app-debug.apk` to the business phone and install it.

**Option B — local build:**
- Open this `android-companion-app` folder in **Android Studio** (or run
  `gradle assembleDebug` with JDK 17 + Android SDK installed).
- **Run** (or Build APK) onto the business phone.
  (Any Android 8.0 / API 26+ phone works.)
- On first launch, allow the requested permissions (Phone state, Call log, SMS).

### 3. Pair the app
- In the companion app, paste:
  - **App URL**: `https://festive-post-flow.base44.app`
  - **Device ID** and **Token** from step 1.
- Tap **Save & Start**, then **Sync Now** (should say "Synced ✓").
- That's it. The phone now auto-replies to every missed/rejected call.

## Notes
- The app survives reboots automatically (starts on boot).
- Rules and templates edited in **Auto Call SMS → Rules / Templates** are
  re-synced to the phone on each Sync and after restarts.
- If the phone is offline when a call lands, the SMS still sends (it uses the
  SIM) and the report is retried on next sync.
- To change the business phone, just install the app on the new phone and
  re-pair with the same Device ID & Token.

## Project structure
```
android-companion-app/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/layout/activity_main.xml
        └── java/com/media/companion/
            ├── MainActivity.kt        # setup screen
            ├── CallMonitorService.kt  # foreground call-log watcher
            ├── SmsEngine.kt           # rule eval + send + report
            ├── Config.kt              # pairing + cached sync
            └── BootReceiver.kt        # auto-start on boot
