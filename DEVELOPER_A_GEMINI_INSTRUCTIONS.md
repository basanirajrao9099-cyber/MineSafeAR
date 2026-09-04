# System Instructions for Laptop 1 (Developer A - Part 1: AR Simulation & Training)

> **Instructions for the User:** Copy and paste the prompt below into Gemini on Laptop 1 whenever you start a coding session for Part 1 (AR Simulation & Fire Drills).

---

```markdown
You are assisting Developer A on Laptop 1 working on the **MineSafeAR** Android project.
Your domain is **Part 1: AR Simulation & Interactive Training Experience**.

### 1. YOUR OWNED FILES & DIRECTORIES:
You are STRICTLY responsible for and allowed to modify only the following files/packages:
- `app/src/main/java/com/minesafear/ar/` (ARSessionManager, ArScene, DepthObstacleMask, FallbackArView, etc.)
- `app/src/main/java/com/minesafear/ar/openings/` (OpeningDetector, Geometry, ExitDetectionActivity, Overlay)
- `app/src/main/java/com/minesafear/ui/modules/` (TrainingModulesScreen, FireModuleScreen, FireDrillState, FireScenario, DrillCues, FireModuleResultsScreen)
- `app/src/main/java/com/minesafear/narration/` (BriefingNarration, NarrationCatalogue)
- `app/src/main/java/com/minesafear/assessment/` (ScoringEngine, AssessmentModels)
- `app/src/main/java/com/minesafear/ui/navigation/SimulationNavGraph.kt` (Your dedicated navigation graph)
- `app/src/main/res/values/strings_simulation.xml` (Your dedicated string resources)

### 2. STRICT NO-TOUCH LIST (DO NOT EDIT THESE):
To prevent Git merge conflicts with Laptop 2 (Developer B), NEVER modify:
- `MineSafeArApp.kt` or `ManagementNavGraph.kt`
- `strings_management.xml` or `strings.xml`
- `com.minesafear.data.**` (Database, DAOs, Entities)
- `com.minesafear.certificate.**` (Certificates & QR Scanner)
- `com.minesafear.sync.**` (Sync engine)
- `com.minesafear.ui.certificates.**`, `com.minesafear.ui.home.**`, `com.minesafear.ui.settings.**`

### 3. RULES FOR ADDING NEW FEATURES:
- **New UI Screens / Routes:** Add them inside `SimulationNavGraph.kt` within `addSimulationGraph(...)`.
- **New UI Text / Labels:** Add them inside `strings_simulation.xml`.
- **Submitting Drill Results:** Use `TrainingRepository` methods to store drill scores.

Always ensure code builds cleanly with `./gradlew app:assembleDebug` and tests pass with `./gradlew app:testDebugUnitTest`.
```
