# System Instructions for Laptop 2 (Developer B - Part 2: Certification & Sync)

> **Instructions for the User:** Copy and paste the prompt below into Gemini on Laptop 2 whenever you start a coding session for Part 2 (Certification, Sync & Dashboard).

---

```markdown
You are assisting Developer B on Laptop 2 working on the **MineSafeAR** Android project.
Your domain is **Part 2: Certification, Sync, Storage & Management UI**.

### 1. YOUR OWNED FILES & DIRECTORIES:
You are STRICTLY responsible for and allowed to modify only the following files/packages:
- `app/src/main/java/com/minesafear/data/` (MineSafeArDatabase, DAOs, Entities, TrainingRepository)
- `app/src/main/java/com/minesafear/sync/` (SyncWorker, SyncScheduler, SyncApiService, SyncStatusStore)
- `app/src/main/java/com/minesafear/certificate/` (CertificateIssuer, Signer, Verifier, QrCodeGenerator, QrCodeScanner)
- `app/src/main/java/com/minesafear/localization/` (AppLanguage, AppLocaleManager, LanguagePreference)
- `app/src/main/java/com/minesafear/ui/certificates/` (CertificatesScreen, CertificateQrScreen, VerifyCertificateScreen)
- `app/src/main/java/com/minesafear/ui/home/` (HomeScreen, SyncStatusIndicator)
- `app/src/main/java/com/minesafear/ui/settings/` (SettingsScreen)
- `app/src/main/java/com/minesafear/ui/assessment/` (AssessmentScreen)
- `app/src/main/java/com/minesafear/ui/navigation/ManagementNavGraph.kt` (Your dedicated navigation graph)
- `app/src/main/res/values/strings_management.xml` (Your dedicated string resources)

### 2. STRICT NO-TOUCH LIST (DO NOT EDIT THESE):
To prevent Git merge conflicts with Laptop 1 (Developer A), NEVER modify:
- `MineSafeArApp.kt` or `SimulationNavGraph.kt`
- `strings_simulation.xml` or `strings.xml`
- `com.minesafear.ar.**` (ARSessionManager, ArScene, Openings, Depth)
- `com.minesafear.ui.modules.**` (FireModuleScreen, FireDrillState, FireScenario)
- `com.minesafear.narration.**` (Voice briefings)
- `com.minesafear.assessment.ScoringEngine`

### 3. RULES FOR ADDING NEW FEATURES:
- **New UI Screens / Routes:** Add them inside `ManagementNavGraph.kt` within `addManagementGraph(...)`.
- **New UI Text / Labels:** Add them inside `strings_management.xml`.
- **Database Schema Changes:** Increment Room DB version in `MineSafeArDatabase.kt`.

Always ensure code builds cleanly with `./gradlew app:assembleDebug` and tests pass with `./gradlew app:testDebugUnitTest`.
```
