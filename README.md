# 🌙 Prayer Times & Home Screen Countdown Widget

A native Android prayer times app featuring a live countdown counter widget for your Android home screen, an offline-first architecture powered by **UmmahAPI**, and a built-in astronomical calculation engine.

---

## ✨ Features

- **📱 Home Screen Icon**: Clean launcher icon with an Islamic crescent moon and star in gold and emerald dark theme.
- **⏱️ Live Counter Widget**: An Android App Widget with a real-time ticking `Chronometer` countdown to the next prayer, highlighting the active prayer in gold (`#F5C030`).
- **📶 100% Offline Capability**:
  - **Full Month Sync**: Automatically fetches and caches the entire 30-day timetable from UmmahAPI.
  - **Offline Astronomical Fallback**: Contains an embedded solar astronomical prayer calculator (Egyptian General Authority of Survey / MWL algorithms). Even without internet or cache, accurate prayer times are always computed on-device.
- **⚡ One-Tap Widget Pinning**: Add the widget to your home screen directly from inside the app with a single tap.
- **🎨 Premium Dark Emerald & Gold Palette**: Preserves the colors:
  - Backgrounds: `#080B09` / `#0D1512`
  - Borders: `#1E2E27` / `#263D32`
  - Accents: Gold `#F5C030` / Amber `#D4A020`
  - Text: Crisp White `#F4F7F5` / Sage `#8FA898` / Muted `#526B5C`

---

## 🚀 How to Get the APK on Your Android Phone

### Option 1: Automatic GitHub Actions (Recommended)
1. Commit and push your changes to GitHub:
   ```bash
   git add .
   git commit -m "Complete Android app with live counter widget and offline sync"
   git push origin main
   ```
2. Open your GitHub repository on your phone or computer and go to the **Actions** tab.
3. Tap the latest workflow run: **Build Android APK**.
4. Under **Artifacts**, download **`PrayerTimes-APK`**.
5. Transfer or open the `.apk` on your Android phone and tap **Install**!

### Option 2: Build Locally with Android Studio
1. Open this repository folder in **Android Studio**.
2. Connect your phone via USB or start an emulator.
3. Click **Run** (`Shift + F10`).

---

## 📌 Adding the Home Widget

1. **Directly from the App**:
   - Open the **Prayer Times** app.
   - Tap **"★ Add Widget to Home Screen"**.
2. **From Your Launcher**:
   - Long-press an empty space on your Android home screen.
   - Tap **Widgets**.
   - Scroll to **Prayer Times** and drag the counter widget to your desired position.

---

## 🔑 API Configuration

Configured in [`PrayerConfig.kt`](file:///c:/Users/sheri/my-own-prayer-app/app/src/main/java/com/prayer/widget/PrayerConfig.kt):
- **API Key**: Registered UmmahAPI key
- **Location**: Cairo, Egypt (`30.0444° N, 31.2357° E`)
- **Calculation Method**: Egyptian General Authority of Survey
- **Madhab**: Shafi
