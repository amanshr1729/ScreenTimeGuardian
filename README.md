# ScreenTime Guardian (v1.0 — Lightweight Native Kotlin)

A minimal, battery-friendly Android app that tracks active screen-on time and gently nudges you to take a break after your custom **X hours, Y minutes** limit.

## Included in Version 1
- **Custom timer**: Set any hours & minutes (plus 30m, 1h, 1h 30m, 2h 30m presets).
- **Persistent screen-on tracking**: Runs via a lightweight Foreground Service (`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`)—automatically pauses when your screen is off, and auto-logs a natural break if your screen stays off for 3+ minutes.
- **Toast + Smart Heads-Up Notification**: Fires a heads-up notification with one-tap **Took a break**, **Snooze 5m**, and **Snooze 15m** actions, plus an optional **Toast** pop-up.
- **Gentle escalation**: Re-reminds every 10 minutes if ignored (up to 3 nudges).
- **Discipline Streak & Daily Stats**: Tracks how consistently you respond to break reminders, your best streak, today's breaks, and today's total screen time.
- **Samsung One UI friendly**: Includes a built-in prompt to set Battery usage to **Unrestricted** so Samsung's Deep Sleeping Apps won't kill the timer, plus automatic restart after reboot.

---

## How to Build Your `.apk`

### Option 1: Fastest — Auto-Build `.apk` on GitHub (No Android Studio needed)
1. Unzip `ScreenTimeGuardian-Android.zip`.
2. Create a new (private or public) repository on [GitHub](https://github.com/new).
3. Upload all files from the `ScreenTimeGuardian` folder (making sure `.github/workflows/build-apk.yml` is included).
4. Open the **Actions** tab → click **Build ScreenTime Guardian APK** (or click **Run workflow**).
5. When the build finishes (~2 minutes), download **`ScreenTimeGuardian-debug-apk`** from the **Artifacts** section, transfer `app-debug.apk` to your Samsung phone, and tap it to install.

### Option 2: Build Locally in Android Studio
1. Open the unzipped `ScreenTimeGuardian` folder in **Android Studio** (Ladybug or newer, JDK 17).
2. Let Gradle sync finish.
3. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Grab the generated APK from `app/build/outputs/apk/debug/app-debug.apk`.

---

## Samsung Setup Tip
After installing on your Samsung phone:
1. Open **ScreenTime Guardian** and tap **Start timer** (allow Notifications when prompted).
2. Tap the amber **Keep timer reliable on Samsung** banner at the top of the app and allow **Unrestricted** battery usage.
