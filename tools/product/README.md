# LaterText Product Atlas

The Product Definition gives durable IDs to six screens, their content regions,
scheduling actions, execution behavior and user-visible states. The Kotlin IDs
are compiled into the app and used as static Compose tags. Declared bindings
identify implementation locations; they do not claim runtime capture coverage.

Use the Python environment that contains the installed Shipyard CLI:

```sh
python scripts/generate_product_ids.py --write
python scripts/generate_product_ids.py --check
python scripts/product_binding_check.py
```

The native capture adapter renders the real Upcoming, Composer, and Schedule
Editor feature composables with fixed synthetic state and inert callbacks.
It cannot save schedules or send messages. It requires a fresh disposable
`latertext_atlas_api35` AVD (API 35, 1080×2400, 420 dpi, English), refuses existing
app installations, runs normal and 200% font profiles, and restores font scale
and removes only the APKs it installed. Hierarchies contain product IDs and
measured bounds, never accessibility text or recipient data.

```sh
python tools/product/capture_android.py --serial emulator-5588 \
  --expected-avd latertext_atlas_api35 --output /tmp/latertext-atlas-capture
```

The output directory must be new and outside the repository. The adapter builds
the current clean commit locally and validates APK hashes, definition digest,
profile coordinates and all six capture rows. It never publishes evidence.
These fixtures qualify individual feature surfaces, not device SMS delivery,
navigation wiring, or live permission dialogs.

After committing and pushing, update the registered server checkout and run:

```sh
SHIPYARD_URL=http://2016macbook.tail8cc030.ts.net:7070 \
  shipyard product onboard --project P-10 --repo .
```

Follow the returned plan. Acceptance of an imported revision is human-only.
