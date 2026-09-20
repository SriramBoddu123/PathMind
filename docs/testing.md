# PathMind Verification & Testing Suite

This document records the comprehensive unit test suite and physical device testing history for PathMind.

---

## 1. Automated Unit Test Suite

The project includes **62 automated unit tests** covering sensor dead-reckoning, route learning segmentation, speech intent parsing, fuzzy memory ranking, and filter logic.

### Test Execution Command
```bash
./gradlew testDebugUnitTest
```

### Test Suite Breakdown

| Test Suite Class | Tests | Status | Scope / Invariants Verified |
| :--- | :---: | :---: | :--- |
| **`TurnDetectionAndLearningTest`** | 10 | **PASSED** | Gravity vector low-pass filtering; footfall bounce rejection; arm-swing noise immunity ($< 0.35\text{ rad/s}$); sustained left/right turn detection; turn candidate onset timestamping (`turnCandidateStartTime`); 800 ms min duration; rejection of short spikes; zero-step turn segment prevention. |
| **`MemoryRetrievalEngineTest`** | 21 | **PASSED** | Levenshtein typo tolerance (e.g. `"bkie"` $\to$ `"bike"`); synonym dictionary (`"bicycle"` $\to$ `"bike"`, `"automobile"` $\to$ `"car"`); punctuation/whitespace normalization; exact match prioritization; route affinity bonus weighting; ambiguous multi-candidate detection ($< 15$ pt spread). |
| **`MemoryFilterEngineTest`** | 12 | **PASSED** | Case-insensitive multi-attribute search; route chip filtering; combined search + route intersection; chronological ordering; empty state classifications (`NO_MEMORIES_SAVED`, `NO_MATCHING_SEARCH`, `NO_MEMORIES_ON_ROUTE`). |
| **`VoiceCommandOrchestratorTest`** | 11 | **PASSED** | Intent routing; single-candidate auto-resolution; ambiguous multi-candidate selection chips; cancellation intents; empty state voice responses; slot extraction for places and routes. |
| **`VoiceIntentParserTest`** | 7 | **PASSED** | Regex and semantic parsing for `"remember ..."` commands, `"where is ..."` queries, `"start route ..."` executions, and `"cancel"` directives; punctuation stripping and lowercase normalization. |
| **`ExampleUnitTest`** | 1 | **PASSED** | Basic harness integrity verification. |
| **TOTAL** | **62** | **100% PASS** | **0 Failures • 0 Errors • 0 Skipped** |

---

## 2. Physical Device Validation (realme RMX3750)

Physical hardware verification was performed across 11 development stages on a realme RMX3750 (Android 14, 50 Hz hardware sensors):

### Validated Physical Behaviors

1. **Walking Straight with Phone in Hand**:
   - Dynamic vertical footfall shocks ($\pm 1.5\text{ m/s}^2$) are absorbed by the low-pass gravity filter ($\alpha = 0.85$).
   - Natural arm sways (angular rates $< 0.35\text{ rad/s}$) do not accumulate false turns.
   - Live step count increments accurately without spurious segment fragmentation.

2. **Genuine 90-Degree Turns**:
   - Sustained angular rate and integrated heading angle crossing $\pm 25^\circ$ correctly triggers `TurnDirection.LEFT` / `TurnDirection.RIGHT`.
   - Candidate onset timing (`turnCandidateStartTime`) preserves the beginning of physical rotation, preventing premature false-turn rejection during route learning.
   - Minimum turn duration ($800\text{ ms}$) prevents quick phone tilts or glances from creating phantom turns.

3. **Offline Voice Command & Intent Recognition**:
   - Tested in complete **Airplane Mode** (no WiFi, no cellular connection).
   - Bundled Vosk model (`vosk-model-small-en-us-0.15`) loads locally into memory in $\sim 50\text{ ms}$.
   - Successfully transcribed spoken queries including `"remember where I parked my bike"`, `"where did I park my bike"`, and `"find my bike"`.

4. **Navigation Progress & Arrival Lifecycle**:
   - Starting navigation correctly initializes segment slicing up to the anchored destination.
   - Verified that tapping **START NAVIGATION** does not prematurely show `ARRIVED` before steps are taken.
   - Reaching destination step target triggers canonical `ARRIVED / DESTINATION REACHED` state.
   - Tapping **STOP NAVIGATION** / **EXIT NAVIGATION** cleanly terminates sensor loops and clears wake flags.

5. **Storage Resilience & Route Deletion Safety**:
   - Multiple routes and memories saved across process death and phone reboots.
   - Deleting an associated route cleanly handles memory references with `"Route no longer available"` fallbacks, preventing null-pointer crashes.
   - Exiting landmark camera capture without saving automatically purges uncommitted temporary photo files from internal disk.
