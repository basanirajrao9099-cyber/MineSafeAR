# MineSafeAR

AR-based industrial safety training for Android. Kotlin, Jetpack Compose,
offline-first.

**Status: a working AR pipeline, one complete training module, offline certificates,
four locales, and a sync queue that drains when the phone finds signal.** The app
opens on a "Hello MineSafeAR" home screen with five bottom-nav destinations and a
sync-status line. **Modules** lists the one real module, **Fire & Explosion
Response**, which runs end to end: briefing → place the scene → pick the
extinguisher → pick the way out → score written to Room → results screen.
**Certificates** issues, signs, displays and verifies certificates offline, and
**Settings & Language** picks between English, Hindi and Santali in two scripts.
**Assessment** is still an empty placeholder. A temporary button on Home also
launches `ARTestActivity`, which proves the AR path in isolation: camera permission
→ ARCore session → horizontal plane detection → translucent plane grid → tap →
anchored model.

**The app makes no network calls of its own.** Every feature above — training
content, scoring, certificate generation, QR verification — runs with airplane mode
on. See [Offline and sync](#offline-and-sync).

**None of this has been compiled.** There is no Gradle distribution or Maven
cache on this machine and no network route to fetch either, so every version and
API in the tree was verified against published POMs and library sources instead
of a build. Expect to fix at least the odd import when you first sync.

## Before the first build

`gradle/wrapper/gradle-wrapper.jar` is **not** in this repo, so `./gradlew` does
not exist yet. Open the project in Android Studio and let it sync (it generates
the wrapper from `gradle-wrapper.properties`), or if you have a Gradle install on
`PATH`:

```bash
gradle wrapper --gradle-version 9.5.0
```

`local.properties` points `sdk.dir` at `~/Library/Android/sdk`; change it if your
SDK lives elsewhere.

## Toolchain

| | |
|---|---|
| AGP | 9.3.2 (built-in Kotlin support) |
| Gradle | 9.5.0 |
| Kotlin | 2.4.10 |
| KSP | 2.3.11 |
| JDK | 17 target (Android Studio 2026.1.3 bundles 21) |
| compileSdk / targetSdk | 37 |
| minSdk | 29 (Android 10) |
| Compose BOM | 2026.08.00 |

Two things about AGP 9 that are easy to trip over:

- **`org.jetbrains.kotlin.android` is not applied, on purpose.** AGP 9 has
  built-in Kotlin support and the old plugin is incompatible with its new DSL.
  Only `org.jetbrains.kotlin.plugin.compose` is applied explicitly (declaring it
  at 2.4.10 also raises AGP's bundled Kotlin from 2.2.10, which SceneView 4.33.0
  needs to avoid metadata mismatches).
- **Room uses KSP, not kapt** — kapt does not work with built-in Kotlin.

## Dependencies of note

- **ARCore** `com.google.ar:core:1.54.0` — declared **AR Optional** in the
  manifest, so the app installs and runs on devices without ARCore.
- **SceneView** `io.github.sceneview:arsceneview:4.33.0` — Sceneform is archived;
  SceneView is the maintained successor and the current recommendation for
  Kotlin/Compose AR.
- **Room** 2.8.4 — the whole offline store.
- **WorkManager** 2.11.2 — the sync queue: a network-constrained one-shot request per
  write, plus a 6-hour periodic backstop.
- **Retrofit** `com.squareup.retrofit2:retrofit:3.0.0` — the `SyncApiService`
  interface only. **No client is built and no converter factory is declared**, so
  nothing can accidentally reach the network; see [Offline and
  sync](#offline-and-sync).
- **ZXing** `core:3.5.4` for QR generation, `zxing-android-embedded:4.3.0` for
  scanning.

## The AR layer

Seven files in [`ar/`](app/src/main/java/com/minesafear/ar):

| | |
|---|---|
| `ARSessionManager.kt` | session state, tap-to-place, anchor lifecycle |
| `ArScene.kt` | the `ARSceneView` call — plane finding, plane grid, node rendering |
| `ArCameraPermission.kt` | runtime `CAMERA` permission as observable state |
| `ArCoreAvailability.kt` | is ARCore installed at all — asked before trying, never via the Play Store |
| `ArStatusUi.kt` | the shared overlay furniture: status banner, permission gate, tracking-failure copy |
| `ArModels.kt` | model catalogue, and where the real assets go |
| `ARTestActivity.kt` | throwaway harness |

**`ARSessionManager` is a state holder, not a session owner.** SceneView 4.x is
declarative: `ARSceneView` creates, configures, resumes, pauses and destroys the
ARCore `Session` from the Compose lifecycle, and nodes are *declared* in its
`content` lambda rather than added imperatively. So the manager owns the list of
placements that the content lambda renders, plus the derived state a training
screen needs to guide a worker. That is why lifecycle handling is only a few
lines — there is no resume/pause plumbing to get wrong, just our own references to
release on dispose.

Rotation is handled in the manifest, not in code: `ARTestActivity` declares
`android:configChanges="orientation|screenSize|..."`, so the activity is not
recreated and the session, the manager and every anchor survive. If the activity
*is* recreated (process death), placements are lost — an ARCore `Anchor` cannot be
serialised. Persisting them needs Cloud Anchors.

Two details worth knowing before you extend it:

- **Taps are hit-tested a frame late, on purpose.** `ARSceneView.onTouchEvent`
  hands you an `io.github.sceneview.collision.HitResult` — a Filament *node* pick,
  not an ARCore plane hit. So `onTouchEvent` only records the tap coordinates, and
  `onSessionUpdated` does a real `Frame.hitTest` against trackables, filtered to
  `HORIZONTAL_UPWARD_FACING` planes with `isPoseInPolygon` (which is what stops
  objects landing in mid-air past the edge of a table).
- **Picking an object is geometry, not a node pick.** Because that hit test hits
  *trackables* rather than rendered geometry, tapping an extinguisher reports the
  floor behind it, not the extinguisher. `ARSessionManager.placementNear` closes
  the gap: it measures each anchor against the segment from the camera to the
  reported floor hit and takes the nearest within 0.5 m. `isCrowded` refuses to
  place anything within 0.9 m of an existing anchor, so "nearest" is never a coin
  flip — seven objects at that spacing still fit a ~3 × 3 m room.
- **The plane indicator is SceneView's `planeRenderer`**, the translucent grid it
  draws over every tracked plane. `ARSessionManager.hasTrackedPlane` exists to
  drive a "scan the floor" hint for the second or two before it appears.

### The placeholder models

Six `.glb` files in `res/raw/`, 1.6–4.9 KB each, all generated by
[`tools/generate_placeholder_models.py`](tools/generate_placeholder_models.py):

| | |
|---|---|
| `placeholder_cube.glb` | orange cube, for `ARTestActivity` |
| `placeholder_extinguisher_co2.glb` | **black** body |
| `placeholder_extinguisher_foam.glb` | **cream** body |
| `placeholder_extinguisher_water.glb` | **red** body |
| `placeholder_exit_sign.glb` | green sign panel |
| `placeholder_exit_arrow.glb` | green floor arrow |

They are checked in so a fresh clone works, and regenerable so nobody has to trust
an unattributed binary. Each origin sits at the centre of its base, so models rest
on a plane instead of sinking through it.

**The extinguisher body colours are content, not decoration.** Identifying a
cylinder by its colour is the skill the fire module tests, so a replacement asset
must keep the same colour per type (IS 15683 / EN 3: black CO₂, cream foam, red
water). Swap in a detailed mesh, but do not recolour one.

Models live in `res/raw/` rather than `assets/` because
`placeObjectAt(hitResult, modelRes: Int)` takes a `@RawRes Int`: rename or delete
a file and the build breaks, instead of a training module silently rendering
nothing on a worker's phone.

`ArModels.kt` documents where each real asset slots in — including the two that
are not just a matter of dropping in a `.glb`: exit signage needs vertical plane
detection to hang above a door, and the gas cloud needs a particle or volumetric
effect rather than a static mesh. PPE icons are still unmodelled.

### Running the harness

Build, install, tap **Open AR pipeline test** on Home. Grant the camera, sweep the
phone slowly across the floor until the grid appears, then tap it. An orange cube
should appear and stay put when you walk around it. Twenty placements max, oldest
evicted; **Clear** detaches them all.

Needs a physical ARCore-supported device — the emulator's AR support is not worth
the trouble.

## Training modules

### Fire & Explosion Response

Seven files in
[`ui/modules/fire/`](app/src/main/java/com/minesafear/ui/modules/fire). The split
is deliberate: everything that can be reasoned about without a camera is separate
from everything that needs one.

| | |
|---|---|
| `FireScenario.kt` | the content — which extinguisher is right, and why the others are wrong |
| `FireDrillState.kt` | the state machine: step, queue, tallies, feedback |
| `FireDrillScoring.kt` | pure arithmetic, unit-tested |
| `FireModuleScreen.kt` | the AR screen, overlays, and tap dispatch |
| `FireModuleResultsScreen.kt` | score, mistakes, time, Retry / Next module |
| `FireModuleRoutes.kt` | routes and the outcome passed between them |
| `DrillCues.kt` | success/failure sound and haptics, no audio assets needed |

**The scenario is a live electrical fire** — a conveyor motor starter panel, supply
not isolated. CO₂ is correct; water and foam are wrong for *different* reasons
(water conducts; foam is mostly water and belongs on flammable liquids), which is
what makes requirement "explain why this is wrong" worth showing. Correctness lives
on `FireScenario`, not on the `ExtinguisherType` enum, so a second scenario — fuel
spill, foam correct — is new data rather than new code.

**The trainee builds the scene, then is tested on it.** They place an exit sign at
the room's real doorway, three extinguishers in shuffled order, then three escape
arrows. Placement prompts never name the type: the three cylinders must be told
apart by body colour, because that is the actual skill.

**The correct route is the arrow nearest the exit sign they placed** — evaluated
from anchor poses at pick time, not chosen at random. It is deterministic,
discoverable from the scene, and teaches the real behaviour: follow the signage. If
the sign's anchor has stopped tracking, any route is accepted; nobody should fail a
drill because ARCore lost a plane.

Two decisions, 50 points each, 10 off per wrong pick, 80 to pass. Wrong picks are
removed from the scene, so the worst reachable score is 60 — you can brute-force
your way to the end, but not to a pass. Time is recorded and shown, never graded:
rewarding speed in a fire drill trains the wrong reflex.

The result is written to `module_results` on completion. That write is wrapped in
`runCatching` and the outcome travels to the results screen as route arguments, so
a database failure costs the record but not the trainee's score.

**Not yet:** the recorded spoken briefing (see below); the "flashback" video the
brief mentioned as optional; and arrow *headings* — the arrows anchor flat and
unrotated, so they mark positions rather than pointing anywhere until a node
rotation is added.

### Spoken narration

Every briefing overlay has a play/stop control backed by
[`narration/`](app/src/main/java/com/minesafear/narration). **No recordings exist
yet** — `NarrationCatalogue` returns `null` for every slot, the control renders
disabled, and the overlay shows "not recorded in this language yet, read the text
above". The plumbing is complete; only the `.mp3` files are missing.

Recordings go in `res/raw/` with a **language suffix**, looked up explicitly:

```
res/raw/fire_briefing_en.mp3        NarrationSlot.FIRE_BRIEFING
res/raw/fire_briefing_hi.mp3
res/raw/fire_briefing_sat.mp3       both Santali rows — nobody hears a script
res/raw/gas_leak_briefing_*.mp3     NarrationSlot.GAS_LEAK_BRIEFING
```

Then return the `R.raw.*` id from the matching branch in `NarrationCatalogue`.

**Not `res/raw-hi/`, deliberately.** Resource fallback is silent: a missing
`res/raw-sat/fire_briefing.mp3` would resolve to the English recording, so a worker
who chose Santali hears a confident voice in a language they may not speak, with
nothing on screen to say so. Text can fall back safely — a supervisor reads the
English aloud. Audio cannot: it plays once, unattended. An explicit lookup that
returns `null` is the only version that can admit it has nothing.

TTS is not an option either. Android has **no reliable Santali voice** in either
script, which is why the requirement is a recorded file rather than a synthesiser.

`GAS_LEAK_BRIEFING` exists as a slot with a TODO, but is blocked on the module: the
Gas Leak & Confined Space Protocol module has not been built, so there is no briefing
text to read out yet.

## Certificates

Issued from **My Certificates**, one row per certified worker in `certificates`:
`cert_id` (UUID), `user_id`, `user_name`, `score`, `modules_completed`,
`issued_date`, `expiry_date`, `signature_hash`.

Eligibility is the best attempt at each attempted module, averaged with floored
integer division, against the same 80% pass mark the modules use. That aggregation
lives in `CertificateIssuer.snapshot` and is a **stand-in**: it belongs in the
`AssessmentEngine` that does not exist yet, and when that lands it should call
`CertificateIssuer.issue` and delete `snapshot` rather than keep a second opinion.

### The signature is a tamper check, not a signature

`signature_hash` is `SHA-256(cert_id ␟ user_id ␟ score ␟ issued_date ␟ salt)`, and
the salt is a constant compiled into the APK. Anyone who unzips the APK can mint
cards that read VALID. **A VALID verdict means "produced by MineSafeAR and
unaltered since" — never "this worker is certified."** Production wants a
server-side signed JWT, or at minimum an HMAC with a per-tenant key; the note in
`CertificateSigner` says so at the place someone would go to change it. Rotating
the salt invalidates every certificate already issued.

Fields are joined with an ASCII unit separator (`U+001F`) so a character cannot be
shifted across a field boundary — without it, `certId="ab", userId="c"` and
`certId="a", userId="bc"` hash identically.

### Validity is a fixed 365 days

Not `plusYears(1)`. Calendar arithmetic depends on the timezone and on which side
of a leap day the issue date falls, so two devices could derive different expiries
from the same instant — and since the verifier *re-derives* the expiry to detect
tampering, a disagreement would read as forgery. The cost is that a certificate
issued in a leap year lapses one day before its anniversary. `CertificatePolicy` is
the single place both the issuer and the verifier get this from.

### Verification, offline

The QR code carries `cert_id | user_id | score | issued_date | expiry_date |
signature_hash` behind an `MSAR2` version prefix. **`score` is in the payload
although the brief's field list omitted it** — the signature covers `score`, so
without it in the code there is nothing to recompute the hash from and requirement
4 cannot run. Holder name and module list are deliberately *not* in the code: they
are not signed, so carrying them would give a forger a free text field, and it
keeps PII out of something anyone can photograph.

`CertificateVerifier.verify` is the only decision path. It parses, recomputes the
hash with `MessageDigest.isEqual`, then re-derives the expiry from the signed
`issued_date` and rejects a mismatch as `EXPIRY_ALTERED` — which closes the hole
left by `expiry_date` being in the payload but outside the hash, without changing
the hash inputs the brief specified. The verdict is VALID, EXPIRED, or INVALID with
a reason, and the scanned fields are shown under a "Scanned details" heading rather
than as fact, because on an INVALID card those values are exactly what cannot be
trusted. `now` is injected, so the expiry boundary is testable and one scan cannot
be judged against two different clocks.

Expiry is exclusive: a certificate is spent the instant it reaches `expiry_date`.

### Saving the PNG

`CertificateImage` renders the QR above a caption (title, name, score, valid-until,
full certificate id) and writes it through `MediaStore` into
`Pictures/MineSafeAR`, which on API 29+ needs **no storage permission and no
`FileProvider`** — hence no `<provider>` in the manifest. The row is created
`IS_PENDING` so the gallery never shows a half-written image, and a failed write
deletes it. Share adds `FLAG_GRANT_READ_URI_PERMISSION`; without it the receiving
app cannot read the Uri.

The on-screen QR sits on a white `Surface` and is drawn with
`FilterQuality.None` — a dark-mode surface destroys the quiet zone scanners key
off, and the default bilinear filter blurs module edges when the bitmap is scaled.

**Not yet:** nothing revokes a certificate. Offline verification cannot consult a
revocation list by definition, so a card stays VALID until it lapses. That needs the
sync layer to pull as well as push.

## Offline and sync

The design assumption is a phone that spends a shift underground with no signal and
passes the surface office twice a day.

### The audit: zero network calls

Nothing in the app opens a socket. Grepping every `.kt` for `http`, `Url`, `Uri.parse`,
`OkHttp`, `Retrofit`, `Socket`, `InetAddress`, `download` and `fetch` turns up only two
test fixtures that use `https://example.com` as an *invalid* QR payload. Per flow:

| | |
|---|---|
| Training content | hardcoded catalogue + `res/raw` models — no I/O at all |
| Assessment / scoring | `ScoringEngine`, `FireDrillScoring` — pure arithmetic |
| Certificate signing | `java.security.MessageDigest`, `UUID.randomUUID()` |
| QR encode / decode | ZXing, entirely in-process |
| PNG export | `MediaStore`, local |
| Drill sounds | `DrillCues` synthesises tones; no audio files fetched |
| Fonts | `Typography()` defaults — no downloadable-font provider |

`INTERNET` and `ACCESS_NETWORK_STATE` were declared in the manifest from the first
commit and, until this layer, were unused. They are now used by exactly one thing:
WorkManager's network constraint.

**One real defect was found and fixed.** ARCore is not part of Android — it ships as
a separate "Google Play Services for AR" app, and the manifest declares it *optional*
so MineSafeAR installs without it. On a phone that lacks it, session creation used to
fail into a black rectangle, and the standard remedy
(`ArCoreApk.requestInstall()`) opens the Play Store, which is useless 300 m down.
[`ArCoreAvailability.kt`](app/src/main/java/com/minesafear/ar/ArCoreAvailability.kt)
asks `checkAvailability` instead and, when the answer is a definitive no, shows a gate
whose only affordance is **Close** and whose copy says to sort it out at the surface
office. It never calls `requestInstall`.

The `UNKNOWN_*` answers are deliberately treated as "carry on and try", because
`UNKNOWN_TIMED_OUT` is the *expected* reply on a phone with no signal — gating on it
would lock every offline worker out of AR training, which is the failure the check
exists to prevent. **Residual gap:** a phone that has never been online *and* has no
ARCore answers `UNKNOWN_TIMED_OUT` and still reaches a failed session. Closing that
needs a session-creation failure callback out of SceneView.

### Everything is in the APK

Models are six `.glb` files in `res/raw/` behind `@RawRes` constants in `ArModels`, so
a missing asset is a compile error rather than an empty scene on a worker's phone.
Strings are four `values*/strings.xml` directories. `TrainingModuleEntity.arSceneAsset`
names a bundled `res/raw` id — never a URL, never a path into a download cache — and
its KDoc says so, because that field is the obvious place a future contributor would
put one. There is no `assets/` directory and nothing needs one.

**The one unbundled thing is narration audio**, and it is unbundled because it does
not exist: no `.mp3` recordings have been made in any of the four languages. The
lookup is explicit and returns `null`, the control renders disabled, and the overlay
says so — see [Spoken narration](#spoken-narration). Nothing tries to download them.

### The queue is the database

Every drill result and certificate is written with `pending_sync = 1` in the same
transaction that creates it. There is no separate outbox, so there is no window in
which a record exists but is not queued, and no second place for it to be lost.

[`SyncWorker`](app/src/main/java/com/minesafear/sync/SyncWorker.kt) reads those rows,
posts them, and **clears the flag only for the ids the server acknowledged**. A 200
carrying a partial `acceptedIds` leaves the rest queued; an id the server echoes that
we never sent is discarded rather than allowed to clear an unrelated row. A run that
fails touches no flags at all, so no failure path can lose a record.

Two endpoints, not one `POST /sync`: a lost drill attempt is a gap in a training
history, but a lost certificate is a worker who cannot prove they are allowed
underground, so certificates are attempted even when the results batch failed.

### Two requests, because a periodic job is not "when connectivity returns"

`SyncScheduler.schedulePeriodicSync` (6-hourly, from `MineSafeArApplication`) is the
backstop. It is *not* the mechanism: WorkManager will not run a periodic request early
just because a constraint became satisfiable, so a worker who finishes a drill at the
start of a shift and walks past the office an hour later would still be waiting.

`SyncScheduler.requestSyncNow(context)` enqueues a one-shot
`NetworkType.CONNECTED` request at each write site — after a module result is saved,
and after a certificate is issued. It sits dormant offline and fires the moment
there is a network. WorkManager persists it across process death and reboot.
`ExistingWorkPolicy.APPEND_OR_REPLACE`, chosen over the other three: `KEEP` drops a
request that arrives while one is waiting, `REPLACE` cancels an in-flight POST, and
`APPEND` deadlocks behind a failed prerequisite.

It is called from the UI layer because `TrainingRepository` has no `Context` and
should not acquire one to schedule work. The trade-off — a new write site has to
remember the call — is what the periodic run covers.

### Retry policy

[`SyncOutcomes`](app/src/main/java/com/minesafear/sync/SyncOutcome.kt) is pure and
unit-tested, separately from the worker:

| | |
|---|---|
| 2xx | success |
| 5xx, 408, 429, `IOException`, unrecognised status | retry with exponential backoff |
| other 4xx | permanent failure — the same bytes will be refused again |
| non-IO `Throwable` | permanent failure (a programming error, not a transport one) |
| `CancellationException` | rethrown, never classified — it is WorkManager stopping us |

Capped at 5 attempts. Across the two endpoints, retry outranks permanent failure
outranks success, so one endpoint's 500 brings the whole run back.

### The transport is a fake, and it ships

[`FakeSyncApiService`](app/src/main/java/com/minesafear/sync/FakeSyncApiService.kt)
lives in `main`, not `test`. `SyncApi.service()` always returns it. A phone with this
build queues records, runs the worker when it finds signal, logs what it would have
sent, and marks it done — deliberately, because the point is that the *local* half is
real and observable before a server exists. Watch it:

```bash
adb logcat -s SyncApi:I SyncWorker:I
```

```
SyncWorker  I  Run 1: 2 result(s), 1 certificate(s) queued.
SyncApi     I  → POST v1/module-results  batch#1  device=… records=2
SyncApi     I    [1/2] ModuleResultDto(id=8f3…, moduleId=fire_explosion_response, …)
SyncApi     I  ← 200  accepted=2
SyncWorker  I  Uploaded 2 module results.
```

`Behaviour.PartiallyAccepting`, `Failing(code)` and `Unreachable` exercise the failure
paths without a server to break. The payload format is `data class` `toString()`, on
purpose: no serializer is wired up, and hand-rolling a JSON writer just to make the
log look like a request body would mean maintaining an escaping routine nothing
verifies.

**Pointing it at a real backend** is a change to `SyncApi` and nothing else — the
three steps are listed in its KDoc, starting with the converter factory, whose absence
is why a `Retrofit.Builder()` would throw on the first `@Body` call today. Do not add
a body-logging interceptor to a release build: these payloads carry worker names and
certificate signatures, which is also why the current `Log.i` calls are a prototype
affordance rather than a shipping one.

### The Home indicator

Three messages off two inputs — the pending count from Room, the last-synced instant
from SharedPreferences:

| | |
|---|---|
| `Pending` | "Pending sync — will upload when online" |
| `Synced` | "Synced" + the timestamp |
| `NothingToSync` | "Nothing to sync yet" — a fresh install |

`NothingToSync` is a distinct state rather than `Synced` with a blank timestamp,
because a phone that has never uploaded anything is not synced and saying so would be
the app's first lie to a worker. The count itself is not shown: "3 items pending"
invites a worker to wonder what to do about them when the answer is "nothing".

Read-only, with no "sync now" button — tapping one offline would do nothing
perceivable, and `requestSyncNow` has already queued the upload. There is no
`ConnectivityManager` observation and no transient "Syncing…" state either; both would
add a second source of truth about a queue that Room already answers for.

Last-synced is recorded when **at least one record was accepted**, independent of the
run's verdict: a run where the results landed and the certificates 500'd did reach the
server. It is *not* recorded for a run that found an empty queue — a no-op synced
nothing. It lives in SharedPreferences alongside a random device id rather than in a
`sync_state` table, because the database still runs `fallbackToDestructiveMigration`
and adding a table would wipe user data on upgrade.

The device id is a random `UUID`, deliberately **not** `Settings.Secure.ANDROID_ID`:
the server only needs to tell two phones apart, and a hardware identifier would leak
a cross-app-stable id for no benefit.

### Not built

- **No pull half.** Module catalogue and worker profiles should download so a freshly
  provisioned phone can be handed to a new hire underground. `training_modules` and
  `workers` are unseeded and `SyncApiService` has no endpoint for either.
- **`module_progress`, `assessment_results` and `workers` are not uploaded.** All three
  carry a `pending_sync` column and none has an endpoint; they are excluded explicitly
  rather than silently attempted, and are not counted by the Home indicator.
- **No conflict resolution**, because nothing edits a synced row — both record types
  are append-only.

## Package structure

```
com.minesafear
├── MainActivity.kt              edge-to-edge Compose host
├── MineSafeArApplication.kt     schedules periodic sync
├── ar/                          ARCore + SceneView: session state, tap-to-place, test harness
├── assessment/                  question models + on-device scoring (80% to pass)
├── certificate/                 issue, sign, verify, QR encode/decode, PNG export
├── data/                        Room: entities, DAOs, converters, database, repository
├── localization/                AppLanguage (en, hi, sat, sat-Deva), preference, locale wrapping
├── narration/                   per-language spoken-briefing catalogue + MediaPlayer plumbing
├── sync/                        upload queue: worker, scheduler, retry policy, fake transport
└── ui/                          Compose screens, navigation, theme
    ├── home/                    Hello screen + sync-status indicator
    ├── certificates/            My Certificates, QR display, Verify Certificate
    ├── settings/                Settings & Language picker
    └── modules/fire/            Fire & Explosion Response
```

The Room schema is worker-scoped and sync-aware: `workers`, `training_modules`,
`module_progress`, `module_results`, `assessment_results`, `certificates`.
Locally-created rows carry a `pending_sync` flag (`synced_at` on `workers`), which is
the queue `SyncWorker` drains — `module_results` and `certificates` today, the other
three when they have endpoints. `exportSchema` is off and destructive migration is on —
both must change before the first field release, and the second is why the sync
timestamp lives in SharedPreferences rather than a table.

Now at **version 3**: `certificates` was reshaped from one row per passed module to
one row per certified worker, and its foreign keys were dropped for the same
offline-first reason `module_results` has none — a worker can be handed a phone
before their profile has synced down, and an FK to an unseeded `workers` table
turns that into an insert that throws.

## Languages

Four locales, declared in `res/xml/locales_config.xml` so Android 13+ lists the app
in Settings › System › Languages, and offered in-app under **Settings & Language**.

| Directory | Tag | State |
|---|---|---|
| `values/` | `en` | 155 strings (148 translatable, 7 `translatable="false"`) |
| `values-hi/` | `hi` | complete — 148/148 |
| `values-sat/` | `sat` | **review template** — 5 filled, 143 TODO |
| `values-b+sat+Deva/` | `sat-Deva` | **review template** — 5 filled, 143 TODO |

### The two Santali directories

Santali is one language written in two scripts here. `values-sat/` is Ol Chiki
(U+1C50–U+1C7F), the script Santali is officially written in and CLDR's default for
the language. `values-b+sat+Deva/` is the transliterated Devanagari fallback, and it
exists for a specific device reason rather than a linguistic one: **Ol Chiki is not
in the font set bundled with many Android builds**, so `ᱥᱟᱱᱛᱟᱲᱤ` renders as empty
boxes on exactly the phone whose owner needs to read it. Devanagari is bundled
everywhere. Every picker row therefore also carries a Latin label, so the Ol Chiki
row stays findable when its own name is unreadable.

The real fix is bundling **Noto Sans Ol Chiki** in `res/font/` and setting it on the
typography — a TODO, not done here, because the font is a binary this environment
cannot fetch.

### The Santali files are templates, not translations

Every key not confidently translatable is present as a commented-out line holding
the English original:

```xml
<!-- TODO(santali): <string name="fire_module_wrong_water">The red water extinguisher. …</string> -->
```

A reviewer uncomments and replaces the text; no key has to be cross-referenced
against `values/`. Only five strings are filled in — `app_name`, `home_greeting`
(ᱡᱚᱦᱟᱨ / जोहार, the standard Santali greeting), and the three format-only strings
whose content is punctuation. Guessing the rest would be actively unsafe: these
strings include which extinguisher colour is safe on a live electrical fire.

An absent key falls back to `values/` at runtime, so a half-translated locale shows
English rather than breaking — which is why omission is the honest option and a
machine guess is not. Regenerate both files after any change to `values/`:

```bash
python3 tools/generate_translation_template.py
```

The generator is text-based rather than XML-based on purpose, so Android escaping
(`&amp;`, `\'`) and every `%1$s` survive verbatim into the comment and produce valid
XML the moment it is uncommented. It refuses to emit a comment containing `--`.

### Applying the locale: yes, manual wrapping is needed

`LocaleManager.setApplicationLocales` is API 33+. `AppCompatDelegate` backports it,
**but on API < 33 the backport only takes effect through
`AppCompatActivity.attachBaseContext`** — and this app has no AppCompat: activities
extend `ComponentActivity` and `Theme.MineSafeAR` parents
`android:Theme.Material.Light.NoActionBar`. Adopting it would mean three coordinated
changes (declare `androidx.appcompat`, reparent the theme to
`Theme.AppCompat.DayNight.NoActionBar`, change both activity superclasses), and
getting the theme wrong throws *"You need to use a Theme.AppCompat theme"* at
launch.

So [`AppLocaleManager`](app/src/main/java/com/minesafear/localization/AppLocaleManager.kt)
wraps the context by hand instead — no new dependency, no theme change:

- **API 33+** — hand the tag to `LocaleManager`; the system owns the choice and
  recreates the activity. SharedPreferences is only a mirror.
- **API 29–32** — `AppLocaleManager.wrap()` from `attachBaseContext` in both
  activities, plus `Locale.setDefault` so `DateFormat` follows (certificate dates
  are formatted against the default locale). SharedPreferences is authoritative.

Persistence is SharedPreferences, not DataStore, because `attachBaseContext` runs
before any coroutine scope exists and cannot suspend — DataStore would mean
`runBlocking` on the main thread at every activity launch. `commit()`, not `apply()`:
a queued write lost to process death is the exact failure the picker exists to
prevent.

Wrapping is **per-activity**. String lookups off the application context (a future
`SyncWorker` notification, say) still resolve in the system language on 29–32 — the
same limitation AppCompat has. Wrapping the `Application` is a trap: the framework
rewrites its configuration on every config change and the override silently lapses.

### Notes that must survive translation

The fire module's block: the colour named in each extinguisher string *is* the answer
the trainee is being taught, so dropping "(black)" from the CO₂ string breaks the
module, and moving a colour to a different type makes it dangerous. The certificate
block: VALID / INVALID / EXPIRED are read off a screen at arm's length during an
inspection and must stay short and mutually distinct.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

JUnit 4, ten suites, all pure JVM — no Robolectric, no resources, no mocking
framework:

- `ScoringEngineTest` — written assessments
- `FireDrillScoringTest` — the fire drill curve, including that both agree on the
  80% pass mark
- `CertificateSignerTest` — determinism, hex shape, every signed field changing the
  hash, and the field-boundary collision the separator prevents
- `CertificatePayloadTest` — round trip, and rejection of a wrong prefix (including
  `MSAR1`), wrong field count, non-numeric score or dates, malformed signature,
  blank ids
- `CertificateVerifierTest` — valid, expired, the millisecond either side of the
  expiry boundary, signature mismatch, a signature lifted from another certificate,
  a stretched expiry, and junk
- `CertificateIssuerTest` — no results, best-of-attempts, floored average, at and
  below the threshold, cross-user isolation, and that an issued certificate
  verifies
- `SyncOutcomeTest` — the retry classification table, the attempt cap boundary, the
  cancellation rethrow, and the cross-endpoint precedence
- `SyncPayloadsTest` — entity → DTO field by field, and that `pending_sync` never
  reaches the wire
- `FakeSyncApiServiceTest` — full accept, partial accept, zero accept, a clamped
  negative count, 503 vs 422, a dropped connection, and an empty batch
- `SyncStatusUiStateTest` — the Home indicator's four branches, including that a fresh
  install never reads as "Synced"

The sync suites use `runBlocking` with the fake's latency set to 0, because
`kotlinx-coroutines-test` is not a declared dependency. `testOptions { unitTests {
isReturnDefaultValues = true } }` is set so `android.util.Log` returns rather than
throwing "not mocked".

`SyncWorker` itself is not unit-tested — that needs `work-testing` and a
`TestListenableWorkerBuilder` against an in-memory Room database, which is an
instrumented-test-shaped job. Everything it decides is in `SyncOutcomes`, which is,
and the accept-then-clear logic is the part that would most repay covering next.

No instrumented tests are wired up yet. The AR path cannot be covered by them
either way: it needs a physical ARCore device and a real room. The camera QR
scanner is in the same position — `CertificateVerifier` is fully covered, but
`ScanContract` returning a real scan is not.

