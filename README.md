# ScreenTime Guardian (v2.0 — Native Kotlin)

A lightweight, battery-friendly Android app designed for mindful phone use on Samsung One UI and Android 8–15.

## Features in v2.0
- **Daily Overall Screen-Time Threshold Alert**: Set an overall daily cap (default 4h) and repeat interval (default every 10m). Once crossed, a high-priority heads-up notification flashes every *X* minutes while the screen is on, with one-tap **60s Cooldown** or **Pause today** actions.
- **Session Break Timer + Toast & Smart Notification**: Custom *X* hours *Y* minutes countdown that pauses when the screen turns off, auto-logs natural 3+ minute breaks, and offers **Took a break**, **Snooze 5m**, and **Snooze 15m** actions plus optional Toast pop-ups and Gentle Escalation.
- **Unlock / Pick-Up Counter**: Counts daily phone unlocks automatically.
- **Bedtime Wind-Down Rule**: Switches to a shorter reminder interval during night hours (default 22:00–07:00, every 15m).
- **Focus Mode (Auto DND)**: One-tap 25m / 50m / 90m focus sprints that enable Do Not Disturb and restore your previous notification filter when finished.
- **Per-App Doomscroll Guard**: Optional Usage Access monitor for Instagram, YouTube, TikTok, X, Reddit, Facebook, and Snapchat that nudges you if you linger past your continuous app limit.
- **60-Second Cooldown Screen**: Guided breathing countdown (Inhale / Hold / Exhale) that logs a break and grows your Discipline Streak on completion.
- **7-Day History & Discipline Streak**: Lightweight local log of the past 7 days of screen time, breaks, and unlocks.

## Build Your `.apk` on GitHub Actions
1. Replace the files in your GitHub repository with the contents of `ScreenTimeGuardian`.
2. Push to `main` (or trigger **Build ScreenTime Guardian APK** in the **Actions** tab).
3. Download **`ScreenTimeGuardian-v2-debug-apk`** from the completed workflow's **Artifacts** section and install `app-debug.apk` on your Samsung phone.
