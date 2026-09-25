# LaterText

LaterText is a developer-sideloadable Android app for dependable one-to-one message scheduling. It supports automatic SMS and single-image MMS, recurring schedules, randomized timing, share-sheet and keyboard media intake, and honest send and delivery status.

## What works

- Schedule a text SMS once, daily, weekly, or monthly, with optional bounded random jitter.
- Send text SMS automatically with multipart status tracking, durable retries for safe failures, and separate carrier-send and delivery outcomes.
- Send one JPEG, PNG, or carrier-sized GIF as MMS through Android's subscription-scoped MMS service. Static photos are resized and compressed to the active carrier's live limits; oversized GIFs fall back to assisted sharing so animation is never silently destroyed.
- Select one phone number with Android's privacy-preserving contact picker; no broad contacts permission is required.
- Receive text, links, and one image or GIF from the Android share sheet, `PROCESS_TEXT`, clipboard, drag-and-drop, or a compatible keyboard such as Gboard.
- Preview the complete image or meme without cropping in the composer, upcoming list, schedule detail, and history; animated GIF and WebP previews play while visible.
- Hand unsupported or over-limit media and RCS-like content to the default messaging app with the recipient and content prepared. Android does not expose a public API for unattended RCS, so assisted sends remain user-action-required and unverified.
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

The debug application ID is `com.patjackson.latertext.debug`. On first launch, complete the in-app setup for SMS, notifications, and exact alarms. A device with telephony messaging service is required for automatic SMS/MMS; assisted messaging remains available without it. MMS uses Android's active-subscription APN/MMSC configuration—LaterText does not ship carrier URLs or credentials.

Run the local verification suite with:

```bash
./gradlew :app:assembleDebug jvmUnitTest testDebugUnitTest lintDebug --configuration-cache
```

With an API 37 emulator or device connected, run Room and persistence integration tests with:

```bash
./gradlew :data:impl:connectedDebugAndroidTest
```

## SloopWorks integration

Shipyard project **P-10** tracks this repository as LaterText (workspace name
Chatty). `.shipyard.yaml` pins the verification command. Product Atlas vocabulary,
generated Kotlin IDs, implementation associations and synthetic Android capture
profiles are under [`.product`](.product/product-definition.json); see the
[capture workflow](tools/product/README.md).

Debug and development builds include the shared DebugDrawer and SWIP event
inspector. Open the floating drawer, choose **Diagnostics**, and enable recording
for the current session. Screen names and bounded scheduling outcomes stay in
memory. Stopping recording clears them; recipient details, message text, media
and identifiers are never recorded. Release builds use a no-op implementation
and contain neither SDK. Remote analytics is not configured.

Private Maven dependencies require `gpr.user`/`gpr.token` in the user's Gradle
properties or `GITHUB_ACTOR`/`SLOOPWORKS_PACKAGES_TOKEN` in the build environment.
No credential belongs in the repository. SWIP schemas and generated event sources
are pinned in [`.swip/upstream.json`](.swip/upstream.json). Verify them with
`python3 scripts/check_swip_generated.py`; changes originate in the SWIP registry.

Build an APK with Shipyard Deploy provenance using:

```sh
python3 scripts/build_development.py --deploy-repo ../shipyard-deploy
shipyard-deploy inspect app/build/outputs/apk/development/app-development.apk --json
shipyard-deploy publish app/build/outputs/apk/development/app-development.apk --dry-run --json
```

The builder compiles an exact pinned upstream plugin revision in a cache and
builds `com.patjackson.latertext.dev`, separate from the ordinary debug package.
It does not publish. `.shipyard-deploy.yaml` selects notification-only channels.
The developer APK uses this machine's existing Android debug signer, whose
fingerprint must be registered before publishing. It is for personal development;
another machine needs the same authorized signer or a separately approved slot.

## Design specification

The high-fidelity Material 3 design specification is stored at [`designs/LaterText Design Spec.dc.html`](designs/LaterText%20Design%20Spec.dc.html). It was exported from the shared [Claude Design project](https://claude.ai/design/p/3e614187-e015-4aa2-9701-7b215c4a5865?file=LaterText+Design+Spec.dc.html&via=share).

Open the file in a browser to review the complete visual system, screen states, interaction rules, accessibility requirements, copy, and Jetpack Compose handoff guidance.

## Implementation plan

The reviewed zero-to-one build plan is stored at [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md). It defines the sideloadable v1 scope, Android and Gradle architecture, durable schemas, keyboard GIF/sticker intake, transport semantics, subagent ownership, development waves, test gates, and APK definition of done.
