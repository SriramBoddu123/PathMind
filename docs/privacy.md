# PathMind Privacy & Data Security Specification

PathMind was architected from inception as a privacy-first, on-device spatial intelligence system. It does not require, request, or use network access of any kind.

---

## 1. Zero Network Access Guarantee
- **No Internet Permission**: The [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) does **not** declare `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`.
- **Operating System Enforcement**: Because the operating system does not grant network access to the application, PathMind cannot transmit data to remote servers or cloud endpoints, even if modified to attempt to do so.
- **Airplane Mode Ready**: The core product functions completely in Airplane Mode (with WiFi, Cellular, and Bluetooth disabled).

---

## 2. Audio & Speech Privacy
- **Local Acoustic Inference**: PathMind uses an on-device Vosk Kaldi speech recognition model (`vosk-model-small-en-us-0.15`).
- **No Cloud Voice Processing**: Spoken voice audio never leaves the physical handset. Voice data is not processed by Google Cloud Speech, Amazon Transcribe, or any other third-party cloud API.
- **Audio Stream Discard**: Raw PCM audio captured from the microphone is fed directly into the native Kaldi acoustic decoder in memory and is discarded immediately after recognition. No audio recordings are ever saved to disk.

---

## 3. Motion & Inertial Sensor Privacy
- **On-Device Dead Reckoning**: Accelerometer and gyroscope data are processed in-memory by `MovementSensorEngine` at 50 Hz.
- **No Location Fingerprinting**: Continuous raw sensor traces are not stored or transmitted. Once a segment is categorized (`WALK`, `TURN`, `PAUSE`), raw sensor buffers are cleared.
- **Activity Recognition**: Uses standard Android `ACTIVITY_RECOGNITION` strictly for hardware step-detector access.

---

## 4. Visual Landmark Photos
- **Optional Feature**: Capturing a landmark photograph is entirely optional. PathMind's dead-reckoning navigation functions normally without images.
- **Sandboxed Storage**: Landmark images captured via the system camera are stored exclusively in private internal app storage:
  ```
  /data/user/0/com.example.pathmind/files/landmarks/
  ```
- **No Gallery Pollution**: Images are **not** saved to the public Android MediaStore or shared photo gallery, preventing other applications from viewing them.
- **Secure File Sharing**: Handled via AndroidX `FileProvider` with temporary read-only URI grants.
- **Automatic Garbage Collection**: If a user takes a photo but discards the memory without saving, PathMind automatically purges the uncommitted image from disk upon activity exit.

---

## 5. Storage & Deletion
- **Local JSON Files**: All route topology (`saved_routes.json`) and personal memory anchors (`saved_memories.json`) reside strictly in internal application storage.
- **Permanent Deletion**: When a user selects **DELETE** on a route or memory, the records and associated photo files are immediately removed from storage.
- **No Background Telemetry**: Zero crash analytics, zero behavior tracking SDKs, zero third-party advertising frameworks.
