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

This repository publishes development builds of `com.patjackson.latertext.dev` (Gradle variant `development`)
to the Shipyard Deploy project `latertext`. Configuration lives in `.shipyard-deploy.yaml`;
read it rather than hard-coding a project or a channel name.

Which channel a build goes to is decided by the current git branch:

- branch `main` → `main`
- any other branch → `dev-{branch_slug}`

### The three commands

```sh
shipyard-deploy status --json                 # what is published, where, from which branch
shipyard-deploy publish <apk> --dry-run       # resolve the plan without uploading
shipyard-deploy publish <apk>                 # publish; the channel is created if missing
```

Build the APK first with `python3 scripts/build_development.py`. This passes the pinned
plugin repository so the APK includes Shipyard identity and provenance. Plain
`assembleDevelopment` without that repository is not a publishable build.
Every command takes `--json` and returns a single object; exit code 0 means it worked.

### Do not

- Do not publish a build you did not just build from the current working tree.
- Do not pass `--project`, `--channel` or `--server` to work around a failure;
  they override the config file, and the failure is usually the honest answer.
- Do not publish from a dirty working tree without saying so in the release notes;
  provenance records `dirty: true` and reviewers will see it.
- Do not touch the release variant. Shipyard refuses it by design: it distributes
  development builds only.
- Do not add or edit credentials. `shipyard-deploy doctor` says whether the
  environment is usable; if it is not, stop and report that.
<!-- shipyard-deploy:end -->
