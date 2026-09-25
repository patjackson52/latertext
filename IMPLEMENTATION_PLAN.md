# LaterText Zero-to-One Implementation Plan

## 1. Objective

Build LaterText from the current design-only repository into a debug-signed,
sideloadable Android APK that runs on Android 17 / API 37 and implements the
complete v1 scope below.

The primary acceptance artifact is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The build must install on the available API 37 emulator, survive process death
and reboot, and pass the unit, component, integration, and manual acceptance
checks defined in this plan.

This plan is the implementation source of truth when it differs from the
original design artifact.

## 2. Fixed v1 decisions

These decisions prevent agents from independently resolving ambiguous product
or platform behavior.

| Area | v1 decision |
| --- | --- |
| Distribution | Developer-sideloaded APK. Google Play policy work is deferred. |
| Application ID | Use `com.patjackson.latertext` unless changed before scaffolding. |
| SDK | `compileSdk = 37`, `targetSdk = 37`, `minSdk = 28`. |
| Primary transport | Automatic one-to-one text SMS and eligible single-image MMS using `SEND_SMS`. |
| RCS | No automatic RCS. Text may be handed to the default messaging app and is always recorded as unverified. |
| MMS | Use `SmsManager.sendMultimediaMessage` with a valid `Send.req` PDU. Android owns subscription APN/MMSC/proxy selection; LaterText passes no hard-coded carrier URL or override bundle. |
| GIF discovery | No built-in GIF browser, GIPHY API, API key, trending feed, search, rating setting, or GIF network cache. |
| Keyboard media | Accept GIFs, stickers, and images from compliant third-party keyboards using Android Receive Content / Commit Content. |
| Other media intake | Support Photo Picker, clipboard/receive-content, drag-and-drop, and sharesheet media. |
| Media limit | One attachment per draft/schedule in v1. |
| Media at due time | Automatically send a supported attachment that can be prepared within live carrier limits. Otherwise post an action-required notification and use assisted review/share. |
| Assisted status | `READY_FOR_USER`, `OPENED_IN_LATER_TEXT`, `SHARED_TO_MESSAGING_APP`, `EXPIRED`; never infer sent or delivered. |
| Contacts | API 37 Contact Picker with a legacy phone picker fallback. No broad address-book mirror. |
| Recurrence | Once, daily, weekly, and monthly; explicit start, end, zone, DST, and monthly-edge semantics below. |
| Jitter | Discrete uniform whole minutes in `[-range, +range]`, sampled once per materialized occurrence and persisted. |
| Exact timing | Use user-granted `SCHEDULE_EXACT_ALARM`; otherwise describe timing as Android-delayed without a numeric guarantee. |
| Direct Boot | Deferred. No send-before-first-unlock option in v1. |
| Backup | Exclude message content, recipient data, and attachments from automatic cloud backup. Explicit export/import is deferred. |
| Link previews | Deferred. URLs remain ordinary message text. |
| Signing | Standard debug signing for the zero-to-one APK. Release keystore work is deferred. |

## 3. Scheduling semantics

### Civil time and recurrence

- Recurrence uses `java.time`, local date/time, and an IANA zone ID.
- Default zone policy is `FOLLOW_DEVICE_ZONE`.
- A DST gap shifts to the first valid local time after the gap and records the
  adjustment in History.
- A DST overlap uses the earlier offset and records that choice.
- Monthly dates that do not exist use the user's policy: `SKIP_MONTH` or
  `LAST_DAY_OF_MONTH`; default to `LAST_DAY_OF_MONTH`.
- An end count counts planned recurrence slots. Manual Send now occurrences do
  not consume it or move the next scheduled occurrence.
- Editing content creates a new immutable content revision without rerolling
  already-materialized jitter.
- Editing a timing rule creates a new rule revision and replaces only
  unclaimed future occurrences.

### Materialization and jitter

- Maintain a rolling horizon of at least the next five occurrences and no more
  than 32 occurrences or 90 days without replenishment.
- Persist nominal local time, selected zone/offset, jitter offset, target
  instant, deadline, and logical recurrence key.
- Never reroll a materialized occurrence after reopening, process death,
  reboot, permission change, or reconciliation.
- Reject jitter ranges that can reorder or overlap adjacent occurrences.
- If creating a schedule inside its current jitter window would produce a past
  target, use the next recurrence rather than silently changing the requested
  distribution.

### Pause and missed occurrences

- Global pause and per-schedule pause are independent states.
- An occurrence whose grace window expires while paused becomes
  `SKIPPED_PAUSED`; resume never causes a burst of overdue sends.
- Resume affects future occurrences only.
- Default missed policy is `SEND_AS_SOON_AS_POSSIBLE` within a four-hour grace
  period for automatic SMS.
- `ASK_ME` is available only when notifications and the Action Required channel
  are enabled.

## 4. Transport and result semantics

### Automatic text SMS

- Calculate parts with `SmsManager.divideMessage()`.
- Resolve and revalidate the selected subscription immediately before sending.
- Use unique immutable PendingIntent identities for every attempt and part.
- Mark the attempt sent only after all part sent callbacks succeed.
- Track delivery independently from send acceptance.
- A missing delivery receipt eventually becomes `DELIVERY_UNAVAILABLE`, not a
  failed or unknown send.
- Retry only definite pre-acceptance failures when no part was accepted.
- Partial or ambiguous multipart results are terminal and require manual review.
- Manual resend always resends the whole message and warns about duplicates.

### Automatic MMS

- Resolve a subscription-scoped `SmsManager` and re-read its carrier MMS
  configuration immediately before every attempt.
- Build an MMS `Send.req` PDU in app-private cache and call
  `sendMultimediaMessage` with `locationUrl = null` and
  `configOverrides = null`. Android selects the current subscription's APN,
  MMSC, proxy, carrier app, and HTTP parameters; LaterText never stores AT&T,
  T-Mobile, or Verizon endpoints.
- Support one JPEG, PNG, or GIF attachment. Preserve a GIF only when it already
  fits. Resize and JPEG-compress static images to the live maximum message and
  image dimensions, with final encoded-PDU size validation.
- Persist one sent-result callback. `Activity.RESULT_OK` means the MMSC/carrier
  accepted the MMS; it is not proof of handset delivery.
- The public MMS send API has no delivery `PendingIntent`, so persist delivery
  as unavailable for that send. Never display `Delivered` for automatic MMS.
- Treat a lost or ambiguous MMSC response as terminal duplicate risk. Retry only
  errors known to occur before acceptance.
- Android persists outgoing MMS into the system telephony provider for a
  non-default SMS app; LaterText remains a companion with no inbox.

### Assisted text

- Use `ACTION_SENDTO` with `smsto:` and optional body text.
- Record only that LaterText opened the messaging application.
- Never infer the selected transport, user send action, carrier acceptance, or
  delivery.

### Assisted media fallback

- Preserve the original validated GIF/image bytes in app-private storage. Use
  this path when carrier MMS is disabled, the format is unsupported, or an
  animated GIF cannot fit without destructive conversion.
- At due time, show an Action Required notification that opens a LaterText
  review surface.
- The review surface displays recipient, text, attachment preview, and a Share
  to messaging app action.
- Share through a FileProvider URI with explicit read permission and ClipData.
- Recipient preselection is best effort and must not be promised.
- History states that the content was shared to a messaging app and that the
  send outcome is unverified.

## 5. Keyboard and rich-content intake

The composer uses the state-based Compose text field and isolates experimental
Receive Content behavior inside one `RichContentTextField` wrapper.

Accepted v1 formats:

```text
image/gif
image/webp
image/png
image/jpeg
```

All rich-content inputs converge on the same importer:

```text
Keyboard / clipboard / drag-and-drop / Photo Picker / sharesheet
  -> untrusted IncomingContent
  -> immediate private staging copy
  -> MIME and file-signature validation
  -> byte and dimension limits
  -> DraftAttachment READY or FAILED
  -> durable ScheduledAttachment on schedule save
```

Rules:

- Never persist a foreign content URI as the only attachment reference.
- Copy while the temporary grant is valid.
- Treat declared MIME type, filename, dimensions, and source package as
  untrusted.
- Preserve animated bytes; do not decode and re-encode a GIF as a bitmap.
- Show the complete image without cropping in composer, upcoming, detail, and
  history previews. Play GIF/animated WebP frames while visible and stop their
  drawables when the preview leaves the window; retain an animated badge for
  accessibility and clear transport expectations.
- Consume supported media and show actionable errors for invalid or oversized
  content. Return unrelated text content to the text field's default handler.
- Support process death while copying by persisting a staged draft state.
- Scheduled media is durable data and is never deleted by cache cleanup.

The UI may show a one-time hint: "Use your keyboard's GIF or sticker button to
add one here." It must not name or attempt to launch a specific keyboard.

## 6. Gradle and module topology

Baseline:

```text
AGP: latest stable 9.3.x
Gradle wrapper: 9.5.x
JDK/toolchain: 17
Kotlin: AGP built-in Kotlin for Android modules
Compose compiler/Kotlin JVM plugin: one pinned compatible version
DI/code generation: Hilt + KSP; no kapt
```

Modules:

```text
:app
:core:model                 pure JVM IDs, values, enums, projections
:core:domain                pure JVM recurrence, reducers, use cases
:core:designsystem          Material 3 theme and reusable Compose UI
:data:api                   pure JVM repository contracts
:data:impl                  Room, Proto DataStore, files, outbox
:platform:api               pure contracts for clock, alarms, transport, intents
:platform:android           alarms, receivers, notifications, contacts, shares
:transport:automatic        SmsManager SMS/MMS transport and MMS PDU/media preparation
:transport:assisted         text/media messaging-app handoffs
:feature:composer
:feature:schedules          Upcoming, editor, detail
:feature:history
:feature:settings           onboarding, readiness, settings
:testing                    fakes, fixtures, deterministic clock/random
```

Dependency rules:

- Features depend on domain/API modules and the design system only.
- Features never depend on another feature or an implementation module.
- Platform and data implementations depend on their APIs, never the reverse.
- `:app` is the composition root and owns navigation and final Hilt assembly.
- Prefer `implementation`; use `api` only for intentional contract exposure.
- Pure modules contain no Android, Room, Compose, Hilt, or KSP usage.
- Apply Compose, Room, Hilt, and KSP only to modules that need them.

Build logic lives in an included `build-logic` build with convention plugins.
The root build must avoid `allprojects`, `subprojects`, `afterEvaluate`, dynamic
dependencies, and eager task configuration.

Enable from the first scaffold:

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.parallel=true
```

## 7. Dependency injection rules

- Use Hilt only at Android composition boundaries.
- Pure domain classes use ordinary constructors and are directly constructed in
  unit tests.
- Use constructor injection where possible, `@Binds` for interface mappings,
  and `@Provides` only for external/framework types.
- Apply the Hilt aggregating task option to improve incremental processing.
- `@Singleton` is limited to the database, DataStore, repository implementations,
  attachment store, transport registry, and platform gateways.
- Inject qualified clocks, dispatchers, random source, alarm driver, SMS gateway,
  notification publisher, and URI/file importer.
- Hilt scope never substitutes for durable Room state.
- Unit tests do not start Hilt. Shared integration fakes use `@TestInstallIn`.

## 8. Durable data model

Required Room tables:

```text
recipient_endpoint
schedule
content_revision
attachment_asset
content_attachment
rule_revision
occurrence
send_attempt
attempt_part
callback_token
occurrence_event
composer_draft
draft_attachment
recent_recipient
notification_record
side_effect_outbox
```

Notable v1 changes from the original design:

- No GIPHY ID, attribution, rating, search, or cache fields.
- Attachments record an intake source:
  `KEYBOARD`, `CLIPBOARD`, `PHOTO_PICKER`, `SHARE`, or `DRAG_DROP`.
- Send outcome and delivery outcome are separate.
- Assisted media has its own handoff states and never becomes carrier-sent;
  automatic MMS uses durable attempt and callback states like automatic SMS.
- The database is credential-protected only; no Direct Boot mirror exists in v1.

Use stable string enums, UUID/ULID IDs, UTC epoch milliseconds for instants,
epoch-day plus seconds-of-day for recurrence civil time, IANA zone IDs, foreign
keys, indices, WAL, exported Room schemas, and explicit migrations. Destructive
migration is prohibited in release-capable builds.

## 9. Subagent execution protocol

There are four execution slots: one root integrator and at most three subagents.

### Ownership rules

- One build owner exclusively edits the Gradle wrapper, `settings.gradle.kts`,
  version catalog, convention plugins, root build files, and CI.
- The root integrator exclusively edits `:app` wiring, root navigation, Room
  version increments, and final manifests.
- Each subagent owns disjoint modules and may not make opportunistic changes in
  another agent's module.
- Shared contract changes land before downstream agents start or are coordinated
  explicitly through the root.
- Every agent hands off tests, assumptions, known gaps, and exact verification
  commands with its implementation.

### Work packet template

Every delegated task contains:

```text
Objective
Owned files/modules
Files/modules that must not be edited
Upstream interfaces/commit to consume
Required behavior and edge cases
Required unit/component tests
Commands that must pass
Handoff format
```

### Merge gates

- No unreviewed changes to shared Gradle or schema files.
- Public APIs are minimal and documented.
- New behavior has tests at the lowest practical layer.
- Configuration Cache remains usable.
- `git diff --check`, affected unit tests, and affected lint tasks pass.
- Root runs the combined graph after each wave before starting the next.

## 10. Zero-to-one development waves

### Wave 0: Bootstrap and contracts

Concurrency: root plus one build-infrastructure agent only.

Build agent owns:

- Gradle wrapper, settings, repositories, version catalog, convention plugins
- All empty module scaffolds
- JDK 17 toolchain, compile/target/min SDK
- Compose BOM/compiler, Hilt/KSP, Room schema export
- Configuration/build cache and initial CI checks
- Debug Application and Activity skeleton

Root owns:

- ADRs implementing the fixed decisions in this plan
- Core IDs, public enums, repository/platform interfaces, clock/random contracts
- Initial navigation contracts and app composition rules
- Agent work packets for Wave 1

Gate:

```text
./gradlew help --configuration-cache
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

The empty APK installs and launches on API 37 before feature development starts.

### Wave 1: Foundations

Run three agents in parallel after Wave 0 contracts land.

#### Agent A: Domain engine

Owns `:core:model` and `:core:domain`:

- Recurrence rules and logical occurrence keys
- DST/monthly/end policies
- Rolling materialization and jitter
- Schedule/occurrence/attempt reducers
- Retry classification
- Pure Kotlin use cases and projections
- Exhaustive deterministic unit tests

#### Agent B: Persistence and assets

Owns `:data:api` and `:data:impl`:

- Room v1 schema and repository commands
- Immutable revisions, callback tokens, event ledger, outbox
- Draft and attachment storage
- Private staging/durable/cache directory separation
- Proto DataStore settings
- Retention and orphan cleanup
- Room schema export and migration harness

#### Agent C: Design system and app shell

Owns `:core:designsystem` plus approved shell surfaces:

- Material 3 theme, typography, colors, shapes, spacing
- Reusable cards, status chips, recipient and attachment components
- Edge-to-edge scaffold and adaptive navigation shell
- Accessibility semantics and screenshot fixtures
- Loading, empty, error, and readiness primitives

Integration order: domain -> data API/implementation -> design system/shell.

Wave 1 gate:

- Recurrence, jitter, DST, reducer, and retry suites pass.
- Room can create/save/reload a complete text schedule graph.
- A durable draft attachment survives repository recreation.
- Design-system previews render at compact and expanded widths.

### Wave 2: Android execution and intake

Run three agents in parallel against frozen Wave 1 interfaces.

#### Agent D: Scheduler and reconciliation

Owns scheduler portions of `:platform:android`:

- Exact/inexact alarm driver
- Earliest-occurrence arming
- Atomic claim leases and alarm generations
- WorkManager watchdog/outbox processor
- Boot, package replacement, time, zone, and exact-access reconciliation
- Pause/missed/grace behavior
- Fake alarm driver and reconciliation tests

#### Agent E: Automatic SMS/MMS and SIM

Owns `:transport:automatic` and telephony adapter surfaces:

- Permission/readiness checks
- Subscription resolution and revalidation
- `divideMessage`, per-part PendingIntents, callbacks, aggregation
- MMS PDU composition, live carrier limits, static-image resizing, and sent callback
- Delivery timeout and separate delivery state
- Safe retry classification integration
- Partial/ambiguous terminal behavior
- Fake transport plus platform contract tests

#### Agent F: Rich-content and external intents

Owns intake portions of `:platform:android` and `:transport:assisted`:

- Compose Receive Content adapter contract
- Keyboard, clipboard, drag/drop, Photo Picker, ACTION_SEND,
  ACTION_SEND_MULTIPLE, and ACTION_PROCESS_TEXT intake
- Immediate URI copying and validation
- API 37 and legacy contact picking
- Direct Share shortcuts
- Assisted text and media handoffs
- Notification actions and FileProvider grants

Wave 2 gate:

- An alarm can claim a persisted occurrence exactly once.
- Duplicate/out-of-order callbacks are harmless.
- Simulated text SMS reaches sent/failed History states.
- A generated MMS PDU round-trips through a parser, and static image preparation
  respects injected carrier size and dimension limits.
- A test GIF URI from Receive Content becomes a durable draft attachment.
- Shared media remains readable after the source grant is revoked.
- Assisted handoffs never project a sent/delivered result.

### Wave 3: Feature UI

Run three agents in parallel using fake repositories and gateways.

#### Agent G: Composer

Owns `:feature:composer`:

- Recipient picking/manual input
- Stateful rich-content text field
- Attachment preview/import/error/remove UI
- Photo Picker and share/process-text draft restoration
- SMS segment count and transport summary
- Send now and schedule actions
- IME, Back, focus, process-death, and accessibility behavior

#### Agent H: Scheduling surfaces

Owns `:feature:schedules`:

- Upcoming list and state projections
- Complete adaptive schedule editor
- Once/daily/weekly/monthly/end/jitter/missed controls
- Occurrence previews
- Detail, edit, pause, resume, duplicate, delete, and manual send
- Action Needed and assisted-media review surface

#### Agent I: History, onboarding, and settings

Owns `:feature:history` and `:feature:settings`:

- History list, filters, result detail, and event timeline
- Empty/no-results/partial/ambiguous/unverified states
- Permission and readiness onboarding
- Notification, exact timing, SIM, recurrence, retention, and privacy settings
- Diagnostics with redaction
- Remove all GIPHY and Direct Boot settings from v1

Wave 3 gate:

- Every core flow runs against fakes without Android platform services.
- Screens pass compact/expanded, dark/light, 200% font, and keyboard tests.
- Feature modules do not depend on data/platform implementations or each other.

### Wave 4: Integration and hardening

Root integrates while agents take isolated test/hardening assignments:

- Hilt production graph and shared `@TestInstallIn` graph
- Database migration/constraint/foreign-key verification
- Reboot, process death, exact-access revocation, and time-zone changes
- Duplicate alarm, retry, manual-send, pause/delete race tests
- SIM loss/change and permission revocation
- Attachment corruption, missing file, disk-full, and cleanup safety
- Keyboard content with GIF, animated WebP, PNG, JPEG, malformed MIME,
  oversized input, revoked grant, and URL-text fallback
- Sharesheet and Direct Share flows
- Notification permission/channel combinations
- Adaptive UI, TalkBack, RTL, 12/24-hour time, and IME matrix

Root then builds, installs, and performs the acceptance flow on API 37.

## 11. Test strategy

### Local unit tests on every change

- Domain recurrence, jitter, reducers, retry logic
- ViewModels instantiated directly with fakes; no Hilt
- Repository command behavior against fakes
- Rich-content validation and attachment state transitions
- Formatting and UI projection logic

### Component tests before merge

- Room repositories and outbox
- Scheduler reconciler with fake clock/alarm driver
- SMS callback aggregation with fake platform results
- Individual Compose screens with state/event fixtures
- Hilt graph tests only where wiring itself is the subject

### Instrumented/application tests

- Room migration helper and filesystem integration
- PendingIntent identity and receiver behavior
- Exact alarm and reboot reconciliation
- Contact Picker and external-intent resolution
- FileProvider grants and sharesheet handoff
- Critical app navigation smoke flows

### Manual emulator checks

- Install/upgrade/debug launch
- Grant/deny/revoke SMS, notifications, phone, and exact-alarm access
- Type, paste, select keyboard GIF/sticker, and remove attachment
- Schedule near-future SMS; kill process; confirm callback/History
- Pause/resume and reboot before a due occurrence
- Change device time zone and 12/24-hour preference
- Open assisted text and share assisted media
- Schedule a carrier-sized image MMS on a physical device and verify the sent
  callback plus appearance in the default messaging app

Real MMS handset delivery reporting, dual-SIM hardware, roaming, and OEM battery
behavior remain post-v1 physical-device validation items.

## 12. APK definition of done

The zero-to-one milestone is complete only when:

- `assembleDebug`, local unit tests, lint, and selected instrumentation tests pass.
- Configuration Cache succeeds on two consecutive supported Gradle invocations.
- The debug APK installs and launches on the API 37 emulator.
- The app schedules, automatically sends, and records a text SMS.
- Once/daily/weekly/monthly recurrence and jitter survive process death/reboot.
- Safe failures retry without duplicate claims; partial/ambiguous sends do not.
- Contact Picker/manual recipient flows work.
- Text and media shares create editable durable drafts.
- A GIF selected from a compliant keyboard becomes a durable attachment.
- Eligible scheduled media uses automatic MMS and reaches carrier-accepted
  status; ineligible media uses assisted review/share and remains unverified.
- Sent and delivery state remain separate throughout UI and storage.
- Notifications, exact-alarm degradation, permissions, pause, delete, and SIM-loss
  states are represented honestly.
- The composer and schedule editor work with the IME, compact height, landscape,
  and 200% font scale.
- No GIPHY, automatic RCS, Direct Boot, or Play-only promise leaks
  into v1 UI or settings.

## 13. Deferred backlog

Deferred work must not be silently implemented by a feature agent:

- Broader real-carrier MMS certification, MMS delivery-report ingestion, and
  multiple-media/group MMS
- Automatic or observable RCS
- Built-in GIF search/provider integration
- Direct Boot/device-protected scheduling
- Google Play distribution flavor and permission review
- Encrypted export/import and cloud backup
- Link-preview network fetching
- Multiple recipients or group messaging
- Multiple media attachments
- Cross-device sync
- Real dual-SIM, roaming, carrier, and OEM power-behavior certification
- Release signing, store assets, analytics, crash reporting, and production rollout
