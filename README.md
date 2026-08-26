# LaterText

LaterText is a developer-sideloadable Android app for dependable one-to-one message scheduling. It supports automatic text SMS, recurring schedules, randomized timing, share-sheet and keyboard media intake, and honest send and delivery status.

## What works

- Schedule a text SMS once, daily, weekly, or monthly, with optional bounded random jitter.
- Send text SMS automatically with multipart status tracking, durable retries for safe failures, and separate carrier-send and delivery outcomes.
- Select one phone number with Android's privacy-preserving contact picker; no broad contacts permission is required.
- Receive text, links, and one image or GIF from the Android share sheet, `PROCESS_TEXT`, clipboard, drag-and-drop, or a compatible keyboard such as Gboard.
- Hand images, GIFs, or RCS-like content to the default messaging app with the recipient and content prepared. Android does not expose a public API for unattended RCS/MMS sending or verification, so LaterText reports these sends as user-action-required and unverified.
- Open a recipient's conversation in the default messaging app.
- Recover schedules after reboot, package replacement, wall-clock/time-zone changes, loss of exact-alarm access, or process death.
- Keep schedules, occurrences, attempts, callback tokens, drafts, settings, and imported attachments in durable app-private storage.
- Notify for successful sends, delivery updates, failed sends, and assisted sends that require action.

The current build targets Android API 37, supports API 28 and newer, uses Jetpack Compose and Material 3, Room, DataStore, WorkManager, and Hilt, and is intended for direct developer/personal installation rather than Google Play distribution.

## Build and install

Prerequisites are JDK 17 and an Android SDK containing API 37. The repository includes a pinned Gradle wrapper.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug application ID is `com.patjackson.latertext.debug`. On first launch, complete the in-app setup for SMS, notifications, and exact alarms. A device with telephony service is required for automatic SMS; assisted messaging remains available without it.

Run the local verification suite with:

```bash
./gradlew :app:assembleDebug jvmUnitTest testDebugUnitTest lintDebug --configuration-cache
```

With an API 37 emulator or device connected, run Room and persistence integration tests with:

```bash
./gradlew :data:impl:connectedDebugAndroidTest
```

## Design specification

The high-fidelity Material 3 design specification is stored at [`designs/LaterText Design Spec.dc.html`](designs/LaterText%20Design%20Spec.dc.html). It was exported from the shared [Claude Design project](https://claude.ai/design/p/3e614187-e015-4aa2-9701-7b215c4a5865?file=LaterText+Design+Spec.dc.html&via=share).

Open the file in a browser to review the complete visual system, screen states, interaction rules, accessibility requirements, copy, and Jetpack Compose handoff guidance.

## Implementation plan

The reviewed zero-to-one build plan is stored at [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). It defines the sideloadable v1 scope, Android and Gradle architecture, durable schemas, keyboard GIF/sticker intake, transport semantics, subagent ownership, development waves, test gates, and APK definition of done.
