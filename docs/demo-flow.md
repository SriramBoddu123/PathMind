# PathMind Complete Demo Walkthrough

This document outlines the step-by-step physical demonstration flow for PathMind on an Android device in complete **Airplane Mode (Offline)**.

---

## Prerequisites
1. **Device**: Physical Android smartphone running Android 10+ (tested on realme RMX3750, Android 14).
2. **Network**: Enable **Airplane Mode** (WiFi OFF, Mobile Data OFF, Bluetooth OFF).
3. **Permissions**: Grant Physical Activity Recognition (for hardware step detection) and Microphone (for on-device Vosk speech recognition) when prompted.

---

## Phase 1: Record a Spatial Route
*Goal: Teach PathMind the dead-reckoning geometry of an indoor path or outdoor walkway.*

1. Open **PathMind**.
2. Tap **LEARN NEW ROUTE** on the home screen.
3. Tap **START LEARNING**.
4. Hold the phone naturally and begin walking:
   - Walk forward 10–20 steps. Notice the live step counter and `WALKING` indicator updating at 50 Hz.
   - Make an intentional 90-degree **LEFT** or **RIGHT** turn. Notice the live turn indicator reflecting rotational heading change and locking in the turn segment.
   - Walk an additional 10–15 steps straight.
5. Tap **STOP & SAVE**.
6. Enter a route name (e.g., `"Office to Parking"`) and save.
7. The route is now stored permanently in local JSON storage (`saved_routes.json`).

---

## Phase 2: Anchor a Personal Spatial Memory
*Goal: Anchor a personal object (e.g., "My Bike", "Desk", "Meeting Room") to a relative segment along the route.*

### Method A: Voice-First Anchoring (Hero Mic)
1. From any screen, tap the cyan **VOICE COMMAND** button.
2. The offline voice assistant modal opens. Speak clearly:
   > *"Remember where I parked my bike"*
3. PathMind's offline Vosk engine transcribes the command locally.
4. The intent parser extracts the action `REMEMBER_PLACE` and entity `"my bike"`.
5. PathMind automatically anchors the memory to the most recent route segment and displays:
   - **Entity**: `My Bike`
   - **Confidence**: `90%`
   - **Action**: Memory saved!
6. *(Optional)* Tap **📷 ATTACH LANDMARK PHOTO** to capture a local camera reference of the spot.
7. Tap the dismiss action or let it auto-dismiss.

### Method B: Manual Form Anchoring
1. On the home screen, tap **REMEMBER A PLACE**.
2. Enter place name: `"My Desk"`.
3. Select route from the dropdown: `"Office to Parking"`.
4. Select anchor segment and view the computed cumulative step offset.
5. Tap **SAVE REMEMBERED PLACE**.

---

## Phase 3: Offline Memory Retrieval & Disambiguation
*Goal: Query the location of a remembered object using natural voice without internet.*

1. Walk away from the target location.
2. Tap **VOICE COMMAND**.
3. Speak:
   > *"Where did I park my bike?"*  *(or deliberately use a variant: "Find my bike")*
4. PathMind runs offline fuzzy retrieval:
   - Evaluates Levenshtein distance, synonym aliases (`bike` $\to$ `bicycle`), and route context affinity.
5. **Exact / Strong Match**:
   - The card displays: `"Found: My Bike (Score: 100)"`
   - Displays a direct **NAVIGATE TO MY BIKE** button.
6. **Ambiguous Query** *(e.g., "Where is my spot?"):*
   - If multiple candidates exist, PathMind presents interactive selection chips.
   - Tap candidate chip `"My Bike"` to select destination.

---

## Phase 4: Offline Dead-Reckoning Navigation
*Goal: Guide the user step-by-step to the exact target segment using dead-reckoning sensor fusion.*

1. Tap **NAVIGATE TO MY BIKE** (or select the memory from **My Memories** $\to$ **Navigate**).
2. The **Offline Navigation** screen opens:
   - **Destination**: `"My Bike"`
   - **Route Slice**: Automatically sliced up to the anchor segment (`Segment 2`, `24 steps total`).
   - **Visual Cue**: If a landmark photo was attached, the image thumbnail appears in the cue card.
3. Tap **START NAVIGATION**.
4. The button switches to **STOP NAVIGATION** and the engine initializes:
   - **Banner**: Displays dynamic directional guidance: `"WALK FORWARD"` with an animated cyan progress indicator.
   - **Live Step Tracking**: Counts down steps remaining in the active segment.
   - **AI Model Inspector**: View live feature vectors (`Cadence`, `Yaw`, `Disp`) and softmax probabilities (`ON_ROUTE`, `DRIFT`, `DEVIATION`).
5. Execute the route:
   - Walk straight until prompted: `"PREPARE FOR LEFT TURN"`.
   - Complete the turn: The instruction transitions to `"WALK FORWARD TO DESTINATION"`.
6. **Arrival**:
   - When the step target is reached, the banner flashes **DESTINATION REACHED / ARRIVED**.
   - The bottom action changes to **EXIT NAVIGATION**.
   - Tap to cleanly stop the engines and return to the home screen.

---

## Phase 5: Deviation & Recovery (Edge Case Demonstration)
*Goal: Demonstrate how PathMind reacts if the user takes a wrong turn or overshoots steps.*

1. Start navigation on any saved route.
2. During straight walking, deliberately take a wrong turn in the opposite direction or walk 15 steps past the turn instruction:
   - `DeviationDetector` observes the heading anomaly and step count overrun.
   - **State Transition**: Navigation state shifts from `NAVIGATING` $\to$ `DEVIATION_DETECTED` (Warning Amber).
   - **Instruction Banner**: Displays `"DEVIATION: WRONG DIRECTION — TURN AROUND TO RESUME ROUTE"`.
   - **AI Model Inspector**: The `OFF_ROUTE_DEVIATION` probability bar surges to $> 85\%$.
3. Turn around and walk back towards the expected trajectory:
   - `DeviationDetector` observes heading recovery.
   - Banner transitions to `"RECOVERING: RETURNING TO EXPECTED HEADING"`.
   - Once aligned, state seamlessly returns to `NAVIGATING` (`ON_ROUTE`).
