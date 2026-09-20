# PathMind System Architecture

PathMind is an entirely on-device, sensor-driven personal spatial memory and dead-reckoning navigation system for Android. It operates with zero internet access, zero cloud telemetry, and zero remote API dependencies.

---

## High-Level Architecture Diagram

```
+-----------------------------------------------------------------------------------+
|                                 USER INTERFACE                                    |
|   MainActivity  •  RouteLearningActivity  •  MyMemoriesActivity  •  NavigationActivity  |
|                     VoiceCommandBottomSheet (Hero Mic + Voice UX)                  |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                           INTENT & INTELLIGENCE LAYER                             |
|  +--------------------------------+   +-----------------------------------------+ |
|  |   Offline Speech (Vosk)        |   |   Spatial ML Inference Engine           | |
|  |   - Local Kaldi Acoustic Model |   |   - 18 Input Movement Features          | |
|  |   - 100% Offline Recognizer    |   |   - 1,221 Parameters (~4.93 KB TFLite)  | |
|  |   - Dynamic Grammar Constraint |   |   - 0.05-0.10 ms Latency (On-Device)    | |
|  +--------------------------------+   |   - Shannon Entropy & Class Softmax     | |
|                 |                     +-----------------------------------------+ |
|                 v                                          |                      |
|  +--------------------------------+                        |                      |
|  |   Voice Intent Orchestrator    |                        |                      |
|  |   - Semantic Slot Extraction   |                        |                      |
|  |   - Memory Retrieval Engine    |                        |                      |
|  |   - Typo / Alias / Affinity    |                        |                      |
|  +--------------------------------+                        |                      |
+------------------------------------------------------------|----------------------+
                                                             |
                                                             v
+-----------------------------------------------------------------------------------+
|                        DEAD-RECKONING & SENSOR PIPELINE                           |
|  +------------------------------------------------------------------------------+ |
|  |  MovementSensorEngine (50 Hz Sampling)                                       | |
|  |  - Hardware Accelerometer & Gyroscope Streams                                | |
|  |  - Continuous Low-Pass Dynamic Gravity Alignment (Decoupled Footfall Bounce) | |
|  |  - Orthogonal Yaw-Rate Integration with Natural Arm-Sway Noise Rejection    | |
|  |  - Step Counting (Hardware Step-Detector with Fallback)                      | |
|  +------------------------------------------------------------------------------+ |
|          |                                              |                         |
|          v                                              v                         |
|  +----------------------------------+   +---------------------------------------+ |
|  |  RouteLearningEngine             |   |  NavigationEngine                     | |
|  |  - Dynamic Movement Partitioning |   |  - Target Route Slicing               | |
|  |  - START, WALK, TURN, PAUSE      |   |  - Step Tracking & Canonical Arrival  | |
|  |  - Turn Streak (15 samples)      |   |  - Live Step Countdown & Cue Cards    | |
|  |  - Candidate Onset Timestamping  |   +---------------------------------------+ |
|  |  - Min Turn Duration (800 ms)    |                       |                     |
|  +----------------------------------+                       v                     |
|                                         +---------------------------------------+ |
|                                         |  DeviationDetector                    | |
|                                         |  - Step Overrun Ratio Tracking        | |
|                                         |  - Sustained Drift Heading Evaluation | |
|                                         |  - POSSIBLE -> CONFIRMED -> RECOVER   | |
|                                         +---------------------------------------+ |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                           LOCAL PERSISTENCE LAYER                                 |
|  - RouteRepository (saved_routes.json in private internal app storage)            |
|  - MemoryRepository (saved_memories.json in private internal app storage)         |
|  - LandmarkImageManager (internal files/landmarks/ with orphan image cleanup)    |
|  - Zero Cloud Endpoints • Zero Remote Telemetry • Zero Trackers                   |
+-----------------------------------------------------------------------------------+
```

---

## Core Subsystems

### 1. Movement Sensor Engine (`MovementSensorEngine.kt`)
The foundational dead-reckoning engine that runs at 50 Hz (~20 ms period):
- **Gravity Estimation**: Employs a low-pass vector filter ($\alpha = 0.85$) on the 3-axis accelerometer stream. This isolates static gravity from dynamic forward linear accelerations and footfall shocks.
- **Decoupled Yaw Integration**: Computes angular velocity around the filtered gravity vector using dot product projection: $\omega_{gravity} = \vec{\omega} \cdot \hat{g}$.
- **Rotational Deadband**: Rejects arm swing and torso sways below $0.35\text{ rad/s}$.
- **Turn Angle Thresholding**: Integrates rotational displacement. A physical turn requires both accumulated angle exceeding $\pm 25^\circ$ and sustained angular velocity.

### 2. Route Learning Engine (`RouteLearningEngine.kt`)
Captures and structures free-form human locomotion into discrete route segments:
- **Segment Actions**: `START`, `WALK`, `TURN` (`LEFT`/`RIGHT`), `PAUSE`, `DESTINATION`.
- **Onset Preservation**: Employs candidate timestamping (`turnCandidateStartTime`). When sustained turn evidence reaches the 15-sample streak threshold (~300 ms), the segment onset is backdated to the true start of the turn rather than the debounce threshold point.
- **False-Spike Reversion**: If angular motion ceases before $800\text{ ms}$ minimum turn duration, candidate turns are rejected and seamlessly collapsed back into the ongoing walk segment without splitting.

### 3. Spatial Intent ML Classifier (`SpatialMLInferenceEngine.kt`)
A lightweight, fully on-device feedforward neural network running via TensorFlow Lite:
- **Input Feature Vector (18 dimensions)**: Cadence (Hz), step interval variance, smoothed yaw rate, integrated heading displacement, step progress ratio, pitch/roll tilt, normalized entropy, and motion variance.
- **Model Size & Latency**:
  - Size: **5,052 bytes** (~4.93 KB)
  - Parameters: **1,221 weights**
  - Measured Latency: **0.05–0.10 ms** on Android hardware (realme RMX3750)
- **Output Classes (Softmax Probabilities)**:
  1. `ON_ROUTE`
  2. `SUBTLE_DRIFT`
  3. `OFF_ROUTE_DEVIATION`
  4. `RECOVERING`
  5. `UNCERTAIN_NOISE`
- **Shannon Entropy Calculation**: Computes $H = -\sum p_i \ln(p_i)$ to measure prediction certainty in real-time.

### 4. Offline Speech Recognition (`OfflineSpeechRecognizer.kt`)
- **Vosk & Kaldi Engine**: Packages `vosk-model-small-en-us-0.15` (41.2 MB compressed archive) within local assets.
- **Private Extraction**: On first launch, extracts to internal app storage (`context.filesDir`). Subsequent launches load natively in ~50 ms.
- **Dynamic Grammar Restriction**: Constrains speech recognition to domain vocabulary (action verbs, place nouns, direction commands) to maximize acoustic accuracy and eliminate false transcriptions without internet.

### 5. Memory Retrieval Engine (`MemoryRetrievalEngine.kt`)
- **Levenshtein Fuzzy Scoring**: Handles speech recognition phonetic inaccuracies and typos (e.g., `"bkie"` $\to$ `"My Bike"`).
- **Domain Synonym & Alias Dictionary**: Maps equivalent terminology (e.g., `"bicycle"` $\to$ `"bike"`, `"automobile"` $\to$ `"car"`).
- **Route Context Affinity**: Awards scoring boosts when a voice query originates along or specifies a particular route context.
- **Disambiguation System**: When multiple candidate places score within close margins ($< 15$ points), triggers a user-facing interactive disambiguation selector.

### 6. Local Storage & Privacy (`MemoryRepository.kt`, `RouteRepository.kt`)
- All user-recorded routes, spatial memories, and optional visual landmark photos are stored strictly in sandboxed application storage (`/data/user/0/com.example.pathmind/files/`).
- AndroidManifest.xml contains **NO** `INTERNET` permission.
- Route deletion cascades safely without orphaned files or database corruptions.
