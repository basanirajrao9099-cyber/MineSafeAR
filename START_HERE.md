# Get MineSafeAR building — start here

Your repo is in better shape than the earlier summary claimed. Corrections:

- `res/raw/` is **not empty** — six placeholder `.glb` models are already there
  (cube, exit arrow, exit sign, CO₂/foam/water extinguishers).
- `gradle-wrapper.properties` exists and pins Gradle 9.5.0. Only `gradlew` and
  `gradle/wrapper/gradle-wrapper.jar` are missing.
- `local.properties` already points at `/Users/basanirajrao/Library/Android/sdk`,
  so you have the Android SDK installed.
- The build files are coherent and well-commented. This is not a mess.

There is exactly one large unknown, addressed in Step 2.

---

## Step 1 — Generate the missing wrapper (2 min)

Open Terminal:

```bash
cd ~/MineSafeAR
gradle wrapper --gradle-version 9.5.0
```

**If `gradle: command not found`**, install it once:

```bash
brew install gradle
```

**If you don't have Homebrew**, skip the CLI entirely — open the project in
Android Studio (File → Open → `~/MineSafeAR`). Android Studio detects the
missing wrapper and regenerates it during the first sync. Use this route if
Terminal gives you any trouble; it is fully equivalent.

Confirm it worked:

```bash
ls -l ~/MineSafeAR/gradlew ~/MineSafeAR/gradle/wrapper/gradle-wrapper.jar
```

---

## Step 2 — Resolve the version catalog (5–15 min, the real test)

**This is the step that matters.** Every version in `gradle/libs.versions.toml`
was written without network access and has never been resolved against a real
repository. Some may not exist.

The ones I consider highest-risk, in order:

| Pin | Risk | Why |
|---|---|---|
| `sceneview = "4.33.0"` | **High** | Third-party, fast-moving. Big jump from the 2.x line. |
| `agp = "9.3.2"` + `kotlin = "2.4.10"` + `ksp = "2.3.11"` | **High** | These three must be mutually compatible. KSP versions track Kotlin exactly. |
| `compileSdk = "37"` / `targetSdk = "37"` | Medium | Needs that SDK platform installed locally. |
| `composeBom = "2026.08.00"` | Medium | BOM dates are real but specific. |
| `arcore = "1.54.0"`, `room = "2.8.4"`, `retrofit = "3.0.0"` | Low | Stable, predictable release lines. |

Run this — it downloads and resolves everything without compiling a line:

```bash
cd ~/MineSafeAR
./gradlew --refresh-dependencies :app:dependencies 2>&1 | tail -60
```

**Send me the output.** Every failure will be a `Could not find <artifact>` line,
and each one is a one-line fix in `libs.versions.toml`. This is mechanical.

To see what versions actually exist for a failing artifact, Android Studio's
autocomplete inside `libs.versions.toml` will list them once you're online.

---

## Step 3 — Run the 10 unit tests (2 min) ⭐

This is the highest-value command in the whole project and needs **no phone**:

```bash
cd ~/MineSafeAR
./gradlew test
```

Those tests cover `CertificateSigner`, `CertificateVerifier`, `CertificatePayload`,
`CertificateIssuer`, `ScoringEngine`, `FireDrillScoring`, `SyncPayloads`,
`SyncOutcome`, `FakeSyncApiService`, `SyncStatusUiState` — pure JVM logic with no
ARCore and no Android framework. If these pass, the entire cryptographic and
scoring core of MineSafeAR is proven correct, and the phone becomes a rendering
detail rather than an unknown.

`testOptions { unitTests { isReturnDefaultValues = true } }` is already set in
`app/build.gradle.kts`, so `android.util.Log` won't throw "not mocked".

HTML report lands at:
```
app/build/reports/tests/testDebugUnitTest/index.html
```

---

## Step 4 — Install on the phone

Only after Steps 2 and 3 are green.

1. Phone: Settings → About phone → tap **Build number** 7× → back → System →
   **Developer options** → enable **USB debugging**.
2. Plug in with a **data** cable (charge-only cables are the #1 failure).
   Tap **Allow** on the USB debugging prompt.
3. Verify the Mac sees it:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb devices
   ```
   You want a line ending in `device`. `unauthorized` means you missed the prompt.
4. Build and install:
   ```bash
   cd ~/MineSafeAR
   ./gradlew installDebug
   ```
   Or press ▶ in Android Studio with the phone selected.

`ARTestActivity` is your smoke test — it taps a plane and places
`placeholder_cube.glb`, which already exists in `res/raw/`.

---

## If Step 2 goes badly

If more than a handful of versions fail to resolve, the fastest recovery is to
let Android Studio pick the toolchain rather than fight the catalog:

1. Android Studio → **Tools → AGP Upgrade Assistant**, or
2. Temporarily set `agp`, `kotlin`, and `ksp` to whatever your Android Studio
   version ships with (Help → About shows the bundled AGP), and set
   `compileSdk`/`targetSdk` to the highest platform you have installed
   (SDK Manager shows this).

Then re-run Step 3. The unit tests don't touch AR at all, so they will run even
if SceneView never resolves — worth doing before chasing the AR dependency.

---

## What I'm doing meanwhile

Reviewing the certificate logic and the 10 test files by hand for correctness
bugs a compiler wouldn't catch — signing field order, expiry boundary handling,
and whether the tests actually assert the anomaly cases the dashboard plants.
