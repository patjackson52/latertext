# LaterText (Chatty)

Android scheduled SMS/MMS app. Keep message bodies, recipient details, media,
and user schedule identifiers out of diagnostics and Product Atlas evidence.

- Build and verify locally: `./gradlew :app:assembleDebug jvmUnitTest testDebugUnitTest lintDebug --configuration-cache`.
- Shipyard project: `P-10`; server: `http://2016macbook.tail8cc030.ts.net:7070`.
  Export `SHIPYARD_URL` explicitly when invoking its CLI.
- Product Definition: `.product/product-definition.json`. Use the installed
  Shipyard Python environment for `scripts/generate_product_ids.py --write`
  and `--check`. Never hand-edit generated IDs or accept a revision for a human.
- SWIP contracts: `.swip/` records the exact upstream revision. Generated Kotlin
  comes from that revision, using SWIP's official generator. Run
  `scripts/check_swip_generated.py`; never hand-edit those generated files.
- Diagnostics are opt-in, memory-only and restricted to debug/development builds.
  Release must keep the no-op implementation and exclude the SWIP/drawer artifacts.

<!-- shipyard-deploy:begin -->
## Shipyard Deploy

This repository publishes only `com.patjackson.latertext.dev` under project
`latertext`. `.shipyard-deploy.yaml` defines the variant and channel policy.
Build with `python3 scripts/build_development.py`; the script uses an exact
upstream plugin revision and never publishes. Inspect and dry-run the resulting
APK before publication. Use `shipyard-deploy status --json`,
`shipyard-deploy publish <apk> --dry-run --json`, and
`shipyard-deploy publish <apk> --json` when publishing is authorized.

Use the user's existing trusted login. Do not invent a server, alter credentials,
override project/channel to bypass a failure, work around a signer refusal, or
publish a build you did not just build. Register the actual APK signer; never use
Shipyard's public sample key for this app. No automatic device rollout is enabled.
<!-- shipyard-deploy:end -->
