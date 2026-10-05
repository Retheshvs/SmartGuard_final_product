<p align="center">
  <img src="branding/smartguard-icon-192.png" width="96" alt="SmartGuard icon">
</p>

<h1 align="center">SmartGuard</h1>

<p align="center">
  <b>The phone knows who is holding it.</b><br>
  On-device face recognition that switches an Android phone between Parent, Child and Guest mode, fully offline.
</p>

---

Every parental control checks a **credential**: a PIN, a password, a pattern. A PIN only proves someone *knew* it. SmartGuard checks the **person**. When the phone is unlocked or picked up, it looks at whoever is holding it, recognises them on the device, and applies that person's rules across the whole phone: restricted apps, daily screen time and curfews. Nothing leaves the phone, and the app has no network permission at all.

Built for the **iQOO City Battles Hyderabad** hackathon (Smart Living track, iQOO x Reskilll, 26–27 Sep 2026) and tested on an iQOO phone running vivo's Android skin.

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Privacy and security](#privacy-and-security)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Setting up the phone](#setting-up-the-phone)
- [Optional: Device Owner mode](#optional-device-owner-mode)
- [Optional: on-device Policy Assistant (LLM)](#optional-on-device-policy-assistant-llm)
- [Testing and debug tools](#testing-and-debug-tools)
- [Project structure](#project-structure)
- [Known limitations](#known-limitations)

## Features

**Identity**
- Automatic face check on every unlock and on every pick-up/hand-over (accelerometer), without polling the camera.
- Guided, hands-free enrollment: five poses (straight, left, right, chin up, chin down) captured automatically.
- Passive anti-spoofing on every recognition frame, so a photo or a face on another screen is rejected without asking anyone to blink.
- Profiles that grow with the child: near-certain, live matches can be kept as extra samples, with strict guard rails against drift (see [`FaceProfileLearner`](app/src/main/java/com/smartguard/recognition/adaptive/FaceProfileLearner.kt)).

**Modes**
| Mode | Who | What they get |
|---|---|---|
| Parent | An enrolled adult | Full access, no time limit, all settings |
| Child / Teen | An enrolled child or teen | Only the apps their parent allows, a daily screen-time budget, curfews |
| Guest | A face that isn't enrolled | Child-level rules plus social media and browsers blocked, 30 minutes |
| Restricted | Nobody verified yet, a spoof, or an error | Every app closed except emergency apps |

**Enforcement**
- Per-profile blocked apps, app categories (games, social, video, browser, education, utilities) and time-window curfews (e.g. "no games after 9 pm on school nights").
- Daily screen-time budget, persisted per profile so re-verifying never refills it; resets at midnight.
- Settings, app stores, package installers and vivo/iQOO system tools are always blocked for children, so protection can't be switched off from inside.
- **Emergency apps are always available**: the phone's own dialer, SMS app and camera, WhatsApp and installed SOS/safety apps, detected per device.
- Kid home screen with big tiles for exactly the apps allowed right now, and an optional per-child kiosk mode.
- **Hand over**: a parent picks a few apps and lends the phone pinned to them until a parent's face ends the session.

**Parent controls**
- Every settings change must be confirmed by a parent's face, every time.
- A parent override PIN (stored as a salted SHA-256 hash).
- Natural-language rules ("one hour of games on weekdays, no YouTube after 8") translated by an on-device LLM into a structured policy that the app validates and enforces.

## How it works

```
 Unlock / pick-up detected
          │
          ▼
 ┌─────────────────┐   ┌──────────────┐   ┌───────────────┐   ┌──────────────┐   ┌───────────────┐
 │ ML Kit face     │──▶│ Quality gate │──▶│ Anti-spoof    │──▶│ Align + embed│──▶│ Cosine match  │
 │ detection       │   │ size/pose/eye│   │ MiniFASNetV2  │   │ MobileFaceNet│   │ + margin rule │
 └─────────────────┘   └──────────────┘   └───────────────┘   └──────────────┘   └───────┬───────┘
                                                                                          ▼
                                                     ┌──────────────────────┐   ┌──────────────────┐
                                                     │ SessionController    │◀──│ Confirmation gate│
                                                     │ Parent/Child/Guest/  │   │ 2 frames in a row│
                                                     │ Restricted           │   └──────────────────┘
                                                     └──────────┬───────────┘
                                                                ▼
                                  AccessibilityService + (optional) Device Owner app suspension
```

**Matching rules** (see [`FaceMatcher`](app/src/main/java/com/smartguard/recognition/matcher/FaceMatcher.kt) and [`FaceRecognitionPipeline`](app/src/main/java/com/smartguard/recognition/pipeline/FaceRecognitionPipeline.kt)):
- Embeddings are 192-d, L2-normalised MobileFaceNet vectors.
- A match needs cosine similarity ≥ **0.60** *and* a lead of ≥ **0.05** over the next family member, which is what separates look-alike siblings.
- Accept fast, reject slow: **2** consecutive matching frames confirm a person; **4** good-quality non-matching frames are needed before someone is treated as a stranger. Blurry, tilted or eyes-closed frames are skipped rather than counted against anyone.
- **5** consecutive spoof-looking frames reject the attempt as a spoof.
- **Fail-safe:** anything other than a confirmed match lands in a restricted mode. A stranger never gets parent access.

**How the check is launched.** Android 14+ stops background apps from opening the camera or a screen, so [`VerificationLauncher`](app/src/main/java/com/smartguard/handover/VerificationLauncher.kt) picks the first path that works:
1. Device Owner → an invisible camera foreground service.
2. SmartGuard's Accessibility service is on → the visible [`FaceCheckActivity`](app/src/main/java/com/smartguard/ui/FaceCheckActivity.kt).
3. Neither → a high-priority "tap to verify" notification.

Unlocks are detected by the Accessibility service itself, because vivo/iQOO filters the `USER_PRESENT` broadcast for background apps.

## Privacy and security

- **No `INTERNET` permission.** The manifest removes `INTERNET` and `ACCESS_NETWORK_STATE` with `tools:node="remove"`, so no library can merge them back in. You can check this with airplane mode on, or in App info.
- **No images stored.** Only face embeddings are kept, encrypted with AES-GCM using a key in the hardware-backed Android Keystore ([`KeystoreCryptoManager`](app/src/main/java/com/smartguard/data/local/security/KeystoreCryptoManager.kt)).
- **No activity log** is kept of what a child did, only per-profile minutes used today.
- Backups are disabled (`allowBackup="false"`, plus data-extraction rules), so face data doesn't leave via cloud backup.
- All models run on the device: ML Kit's bundled detector, TensorFlow Lite for embedding and anti-spoofing, and MediaPipe for the optional LLM.

The design is intended to align with the principles of India's DPDP Act (data minimisation, purpose limitation, on-device processing). It has not been audited for compliance.

## Tech stack

| Area | Library / tool |
|---|---|
| Language | Kotlin 1.9.24, Java 17 target |
| Build | Android Gradle Plugin 8.5.2, Gradle 8.9, KSP |
| SDK | `minSdk 26` (Android 8.0), `compileSdk`/`targetSdk 35`, `arm64-v8a` only |
| Camera | CameraX 1.3.4 |
| Face detection | ML Kit Face Detection 16.1.7 (bundled model) |
| Embedding + anti-spoof | TensorFlow Lite 2.16.1 (`MobileFaceNet.tflite`, `antispoof_minifasnetv2se.tflite`) |
| Storage | Room 2.6.1, AndroidX Security Crypto, Android Keystore |
| On-device LLM | MediaPipe `tasks-genai` 0.10.35 |
| UI | View-based with ViewBinding, Material Components |

Model provenance, input/output shapes and tuning notes are in [`app/src/main/assets/README_MODELS.md`](app/src/main/assets/README_MODELS.md).

## Getting started

### Requirements
- Android Studio (Koala or newer) with JDK 17.
- A physical **arm64** Android phone with a front camera (Android 8.0+). The build ships only `arm64-v8a` native code, so most emulators won't run it.
- `adb` on your PATH for the setup steps below.

### Build and install

```bash
git clone https://github.com/Retheshvs/SmartGuard_final_product.git
cd SmartGuard_final_product
```

> **Before building:** `gradle.properties` contains a machine-specific line,
> `org.gradle.java.home=C:/Users/retin/.jdks/jbr-17.0.14`. Delete it, or point it at your own JDK 17.
>
> The repo includes `gradlew.bat` and the wrapper JAR but not the Unix `gradlew` script. On macOS/Linux, open the project in Android Studio (it uses the wrapper settings directly), or run `gradle wrapper` once with Gradle 8.9 installed to regenerate it.

Then open the project in Android Studio and run the `app` configuration, or from the command line:

```bash
# Windows
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# macOS / Linux (after regenerating ./gradlew)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The two face models are already in `app/src/main/assets/`, so recognition works straight after install. The LLM model is optional and not in the repo (see [below](#optional-on-device-policy-assistant-llm)).

## Setting up the phone

1. **Open SmartGuard and allow the camera.**
2. **Enroll a parent first** (*Add face*). Until a parent exists there is no face to confirm settings changes with, so the first enrollment is ungated.
3. **Enroll children/teens** and set their rules under *Family rules*: allowed apps, categories, daily minutes, curfews, kiosk mode.
4. **Change the parent PIN.** It defaults to `1234` and the app keeps reminding you until it's changed.
5. **Turn on the Accessibility service** (*App rules and unlock checks*). This is what detects unlocks and closes blocked apps.
   - If you sideloaded the APK from a file on Android 13+, Android may grey the service out. Open App info → ⋮ → **Allow restricted settings** first.
6. **Allow background running** (battery-optimisation exemption). vivo/iQOO force-stops non-exempt apps, and Android then switches their Accessibility service off.
7. **Recommended: enable self-heal.** One command lets SmartGuard turn its own Accessibility service back on if the phone switches it off:
   ```bash
   adb shell pm grant com.smartguard android.permission.WRITE_SECURE_SETTINGS
   ```

The *Protection* card on the parent home screen shows which of these are done.

## Optional: Device Owner mode

Device Owner is the strongest setup. Android itself pauses restricted apps (greyed icon, "app paused" dialog), which keeps working even if Accessibility is switched off or SmartGuard is frozen. It also enables the lockdown and kiosk modes.

The phone must have **no accounts** on it (remove Google/vivo accounts in Settings, or start from a fresh reset). Then:

```bash
adb shell dpm set-device-owner com.smartguard/.deviceowner.SmartGuardDeviceAdminReceiver
```

What it adds:
- Restricted apps are suspended by the OS for children, teens and guests; everything except emergency apps is suspended when time is up or nobody is verified.
- During child sessions: no installing/uninstalling apps, no force-stop or clear data, no date/time changes (which would defeat curfews and limits), no adding users.
- *Turn on the lockdown*: SmartGuard can't be uninstalled; factory reset and safe-mode boot are disabled; camera and notification permissions are locked on.
- Face checks can run from a background camera service with no visible screen.

**To undo:** use **Release Device Owner** on the parent screen (it requires a parent's face). It lifts every restriction and gives up Device Owner, so a loaned phone can be returned without a factory reset.

## Optional: on-device Policy Assistant (LLM)

The *Ask assistant* screen lets a parent write rules in plain English. A local LLM turns the text into a `PolicySpec` JSON. The model **never enforces anything**: [`LlmPolicyParser`](app/src/main/java/com/smartguard/policy/llm/LlmPolicyParser.kt) sanitises its output and the deterministic [`PolicyEngine`](app/src/main/java/com/smartguard/policy/PolicyEngine.kt) validates and applies it. Without a model, a rule-based translator handles the request and the screen says so.

The model file (~1.6 GB) is too large for GitHub and is git-ignored. To use it:

1. Download `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` from [litert-community/Qwen2.5-1.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct) (Apache-2.0).
2. Push it to the phone:
   ```bash
   adb shell mkdir -p /sdcard/Android/data/com.smartguard/files/models
   adb push Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task /sdcard/Android/data/com.smartguard/files/models/
   ```

The app also looks in its private `files/models/` folder and in `/data/local/tmp/llm/`. Uninstalling the app deletes the first folder, so re-push after a reinstall (`adb install -r` keeps it). A Gemma 3 1B `.task` bundle also works; the chat template is chosen from the file name.

## Testing and debug tools

**Unit tests** (JVM, no device needed) cover the face matcher, the adaptive profile learner, the active liveness challenge, the LLM output parser and the rule-based policy translator:

```bash
gradlew.bat testDebugUnitTest      # or ./gradlew testDebugUnitTest
```

**Debug-only probes** are present only in debug builds:

```bash
# Genuine vs impostor similarity and leave-one-out identification on the real enrolled faces (logs scores only)
adb shell am start -n com.smartguard/.debug.RecognitionProbeActivity

# Start a test child session with 15 s of screen time left, to see "time's up" without a child's face
adb shell am start -n com.smartguard/.debug.ScreenTimeProbeActivity --ei seconds 15
adb shell am start -n com.smartguard/.debug.ScreenTimeProbeActivity --ez reset true

# Run the LLM policy translator on prompts without applying anything
adb shell am start -n com.smartguard/.debug.LlmProbeActivity --es prompts "no games after 9|2 hours on weekends"
```

Logs use `SG-*` tags, e.g. `adb logcat -s SG-Recog SG-A11y SG-Launcher SG-DeviceRules`.

## Project structure

```
app/src/main/java/com/smartguard/
├── SmartGuardApp.kt            App entry; session state; re-enables Accessibility if it's switched off
├── recognition/
│   ├── pipeline/               FaceRecognitionPipeline: detect → quality → liveness → embed → match → gate
│   ├── detector/               ML Kit face detector wrapper
│   ├── align/                  Eye-based alignment and crop
│   ├── embedding/              MobileFaceNet TFLite embedder (with a weak fallback descriptor)
│   ├── matcher/                Cosine similarity + runner-up margin rule
│   ├── gate/                   Consecutive-frame confirmation gate
│   ├── liveness/               Passive anti-spoof model; optional blink/head-turn challenge (off)
│   └── adaptive/               Profiles that grow with the child
├── enrollment/                 Guided five-pose enrollment
├── handover/                   Monitor service, pick-up sensor, verification launcher, hand-over sessions
├── accessibility/              Unlock detection, app blocking, self-heal
├── deviceowner/                Device Owner lockdown and OS-level app suspension
├── policy/                     Roles, sessions, rules, categories, screen time, emergency apps, PIN
│   └── llm/                    On-device LLM engine, prompt, parser, rule-based fallback
├── data/                       Room database, DAOs, Keystore encryption, profile repository
└── ui/                         Main screen, face check, parent confirmation, kid home, hand-over, editor
app/src/main/assets/            MobileFaceNet + MiniFASNetV2-SE models and their notes
app/src/debug/                  Debug-only probe activities
app/src/test/                   JVM unit tests
branding/                       Icon and logo sources
```

## Known limitations

These come from SmartGuard being a third-party app rather than part of the OS:

- **Android's biometric APIs can't say *which* enrolled fingerprint or face unlocked the phone**, so SmartGuard runs its own face check after unlock instead of replacing the lock screen.
- **The phone maker's own kids space sits behind a signature-level permission**, so SmartGuard rebuilds that behaviour with Accessibility and Device Owner. As a built-in OS feature, it could identify the person at the lock screen and unlock straight into the right mode.
- **Without Device Owner, enforcement depends on the Accessibility service.** vivo/iQOO may switch it off; battery exemption and self-heal reduce this, and the app falls back to restricted mode with a notification to the parent when it happens.
- **A blocked app can appear briefly** before Accessibility closes it. Device Owner suspension removes this.
- The optional active liveness challenge exists but is off by default because it slowed unlocking; passive anti-spoofing covers photos and screens.
- Built and tuned on one iQOO phone. Other manufacturers' background managers and package names may need adjusting in `KidsPolicy` and `EmergencyApps`.
