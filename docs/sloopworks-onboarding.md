# SloopWorks onboarding — 2026-09-24

Repository: `patjackson52/latertext`, local workspace `chatty`. Original SMS/MMS
work was preserved and pushed in `98d5517`. Integration code was first verified
at `a6d6ff6df14a8825ba31de3e97878b7f8b23fe2d`.

## DebugDrawer and SWIP

Debug/development builds use published DebugDrawer 0.1.1, SWIP core 0.1.12 and
debug 0.1.1. Recording starts off, requires a session opt-in, stays in memory,
and exposes only generated screen/operation/outcome/recurrence/media-presence
values. SDK IDs and raw property maps are removed before entering the ring.
The release runtime dependency graph excludes SWIP and DebugDrawer.

The LaterText registry and schema change landed in
[SWIP PR 123](https://github.com/SloopWorks/swip/pull/123), with TypeScript,
Kotlin, Apple and documentation checks passing. `.swip/upstream.json` pins the
merged registry revision. The consumer includes only official generated product
types; SDK libraries resolve from the published Maven pins.

Remote analytics collection is not configured. This integration supplies local
debug analytics; a remote destination/source and consent policy would be a
separate extension.

## Shipyard

Project `P-10` is registered on `http://2016macbook.tail8cc030.ts.net:7070`, with
server checkout `/home/shipyard/workspace/chatty`. The verification command and
two planning/policy documents are committed and indexed. The server process
has a repository-specific Git ownership trust entry for this shared checkout.

The Mac's existing RUN-4 configuration now includes P-10 at
`~/workspace/runner-grok/chatty`, with a clean independent clone and local SDK
path. Existing runner processes were not restarted; the mapping is available on
their next configuration load. There are no work items yet, so dispatch-context
verification remains inapplicable until one is created.

## Shipyard Deploy

Registered project `latertext`, package `com.patjackson.latertext.dev`, and `main`
channel on the existing trusted dev control plane. Both the channel ceiling and
repository routing select `NOTIFY`; the configured TTL is 48 hours. No device is
enrolled and no build has been published.

Registered signer SHA-256:
`15a066e7bf2507cb0e4d5c24defcc97506eeff19b106cb7f76dfc0e92300876b`.
This is the existing local Android debug certificate, not the public sample key.
The pinned build helper embeds valid build identity, manifest marker, signature
and Git provenance, verified with `shipyard-deploy inspect`.

Registration used the existing owner admin credential transiently, without
creating or changing credentials. The saved file-store publisher belongs to
another project and cannot access LaterText. A LaterText-bound publisher login
is still needed for ordinary publishing. The least-privilege proposal is one
`publisher` principal named `latertext-local`, bound to `latertext`, with only
`project:read` and `package:publish`, stored in an owner-only LaterText profile
without replacing another project's login. The repository's agent instructions
forbid adding/editing credentials without an explicit owner instruction.

## Product Atlas

The validated definition contains 28 entities and 21 verified implementation
bindings. Definition digest:
`sha256:f1db52836f179b6928829c76fcfcfd57bcb72a1f620051adcd744c52d7f762b2`.
Kotlin, Python and JavaScript ID registries are generated and checked.

The native adapter targets empty Upcoming, blank Composer and Schedule Editor
at `phone_normal` and `phone_font200`. All six local synthetic captures passed
on the dedicated API 35 emulator. Their source bundle is at
`/tmp/latertext-atlas-a6d6ff6/source-bundle.json`; it records the exact commit,
APK hashes, digest, geometry and fixture identity. No capture was published.

Human acceptance remains required. Use the exact candidate revision and command
from `shipyard product onboard --project P-10 --repo . --revision <PDR-id>`.
The agent must not self-accept or call onboarding complete until the accepted
head is verified and the planner reports `ready: true`.

## Verification

- Full Gradle gate passed: APK assembly, JVM and Android host tests, lint,
  release compilation, and capture-test APK assembly. 92 unit tests across 21
  suites passed with no failures or skips.
- Product Definition validation, generated-ID checks, exact-commit bindings,
  and pinned SWIP schema/source comparison passed.
- Six capture rows passed adapter validation and received visual inspection.
- Live emulator smoke: drawer opens, recording opt-in works, navigating to
  History produces a bounded SWIP screen-view event visible in the inspector.
  The disposable app was uninstalled and emulator stopped afterward.

These checks did not send SMS/MMS or use a personal device. Delivery behavior
was outside this onboarding verification.
