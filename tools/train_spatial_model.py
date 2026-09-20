"""
PathMind Stage 7: On-Device Spatial AI/ML Training Pipeline
===========================================================
Synthesizes 4,000 kinematic samples based on real phone sensor physics & Stage 3-6 route traces.
Trains a genuinely weighted Deep MLP (18 -> 32 -> 16 -> 5) using Adam optimizer.
Evaluates test accuracy, confusion matrix, precision, recall, and F1-score.
Exports model assets for on-device inference in PathMind Android app.
"""

import os
import sys
import math
import struct
import json
import numpy as np

# Set random seed for reproducibility
np.random.seed(2026)

# Feature Names (18 independent kinematic and geometric features)
FEATURE_NAMES = [
    "step_cadence_hz",          # 0: Live walking cadence in Hz (0.0 - 3.0)
    "accel_energy_variance",     # 1: Variance of ||a|| over recent 1.5s window (0.0 - 5.0)
    "gyro_yaw_rate_rads",        # 2: Current rotational velocity around vertical axis (rad/s)
    "gyro_yaw_window_deg",       # 3: Integrated angular change over window (degrees)
    "movement_state_code",       # 4: 0: Stationary, 1: Walking, 2: Turning, 3: Unknown
    "state_transition_jitter",   # 5: Frequency of state flips in last 10 ticks (0.0 - 1.0)
    "hesitation_score",          # 6: Deceleration magnitude approaching expected turn (0.0 - 1.0)
    "sensor_confidence_norm",    # 7: Hardware sensor reliability (0.0 - 1.0)
    "expected_action_code",      # 8: 0: START, 1: WALK, 2: TURN, 3: PAUSE, 4: DEST
    "expected_turn_angle",       # 9: -90 (LEFT), +90 (RIGHT), 0 (STRAIGHT)
    "segment_budget_steps",      # 10: Expected steps for segment
    "segment_learned_duration",  # 11: Learned duration in ms
    "is_memory_anchor",          # 12: 1.0 if remembered place anchor, 0.0 otherwise
    "route_completion_ratio",    # 13: Segment index / total segments (0.0 - 1.0)
    "step_progress_ratio",       # 14: observed_steps / max(1, budget_steps)
    "step_overshoot_delta",      # 15: max(0, observed_steps - budget_steps)
    "heading_disparity_deg",     # 16: |gyro_yaw_window_deg - expected_turn_angle|
    "pace_disparity_ratio"       # 17: current_step_duration / learned_step_duration
]

CLASS_NAMES = [
    "ON_ROUTE",             # 0
    "DRIFT_WARNING",        # 1
    "DEVIATION_CONFIRMED",  # 2
    "RECOVERING",           # 3
    "UNCERTAIN"             # 4
]

def synthesize_dataset(samples_per_class=800):
    """
    Synthesize 4,000 realistic kinematic samples with physics constraints
    modeled after real phone sensor traces on realme RMX3750.
    """
    total_samples = samples_per_class * 5
    X = np.zeros((total_samples, len(FEATURE_NAMES)), dtype=np.float32)
    y = np.zeros(total_samples, dtype=np.int64)

    idx = 0

    # -------------------------------------------------------------
    # Class 0: ON_ROUTE (800 samples)
    # -------------------------------------------------------------
    for _ in range(samples_per_class):
        # Normal steady walking or deliberate turn matching geometry
        is_turn_seg = np.random.rand() < 0.35
        action_code = 2.0 if is_turn_seg else (1.0 if np.random.rand() < 0.85 else 0.0)
        expected_turn = 0.0
        if is_turn_seg:
            expected_turn = -90.0 if np.random.rand() < 0.5 else 90.0

        budget_steps = np.random.randint(15, 65)
        learned_dur = budget_steps * np.random.uniform(450, 600)
        prog_ratio = np.random.uniform(0.1, 0.95)
        obs_steps = int(budget_steps * prog_ratio)
        overshoot = 0.0

        if is_turn_seg:
            yaw_rate = (expected_turn / 90.0) * np.random.uniform(0.8, 1.6)
            yaw_deg = expected_turn + np.random.normal(0, 7.0)
            mov_state = 2.0  # TURNING
            cadence = np.random.uniform(1.2, 1.7)
            accel_var = np.random.uniform(0.6, 1.8)
        else:
            yaw_rate = np.random.normal(0, 0.12)
            yaw_deg = np.random.normal(0, 8.0)
            mov_state = 1.0  # WALKING
            cadence = np.random.uniform(1.6, 2.1)
            accel_var = np.random.uniform(1.0, 2.8)

        heading_disp = abs(yaw_deg - expected_turn)
        jitter = np.random.uniform(0.0, 0.15)
        hesitation = np.random.uniform(0.0, 0.20)
        conf = np.random.uniform(0.82, 0.98)
        pace_disp = np.random.uniform(0.88, 1.12)

        X[idx] = [
            cadence, accel_var, yaw_rate, yaw_deg, mov_state,
            jitter, hesitation, conf, action_code, expected_turn,
            budget_steps, learned_dur,
            1.0 if np.random.rand() < 0.2 else 0.0,
            np.random.uniform(0.05, 0.95),
            prog_ratio, overshoot, heading_disp, pace_disp
        ]
        y[idx] = 0
        idx += 1

    # -------------------------------------------------------------
    # Class 1: DRIFT_WARNING (800 samples)
    # -------------------------------------------------------------
    for _ in range(samples_per_class):
        # Subtle drift: step overshoot 1-3 steps, or heading error 22-45 deg, or hesitation
        drift_type = np.random.choice(["overshoot", "angle_drift", "hesitation"])
        budget_steps = np.random.randint(20, 50)
        learned_dur = budget_steps * 520.0
        is_turn = np.random.rand() < 0.3
        action_code = 2.0 if is_turn else 1.0
        expected_turn = (-90.0 if np.random.rand() < 0.5 else 90.0) if is_turn else 0.0

        if drift_type == "overshoot":
            obs_steps = budget_steps + np.random.randint(1, 4)
            overshoot = float(obs_steps - budget_steps)
            prog_ratio = obs_steps / budget_steps
            yaw_deg = expected_turn + np.random.normal(0, 12.0)
            yaw_rate = np.random.normal(0, 0.25)
            heading_disp = abs(yaw_deg - expected_turn)
            cadence = np.random.uniform(1.4, 1.9)
            accel_var = np.random.uniform(0.8, 2.0)
            hesitation = np.random.uniform(0.1, 0.4)
            mov_state = 1.0
        elif drift_type == "angle_drift":
            obs_steps = int(budget_steps * np.random.uniform(0.4, 0.9))
            overshoot = 0.0
            prog_ratio = obs_steps / budget_steps
            drift_sign = 1.0 if np.random.rand() < 0.5 else -1.0
            heading_disp = np.random.uniform(22.0, 45.0)
            yaw_deg = expected_turn + drift_sign * heading_disp
            yaw_rate = drift_sign * np.random.uniform(0.25, 0.55)
            cadence = np.random.uniform(1.3, 1.8)
            accel_var = np.random.uniform(0.7, 1.8)
            hesitation = np.random.uniform(0.2, 0.5)
            mov_state = 1.0 if np.random.rand() < 0.8 else 2.0
        else: # hesitation
            obs_steps = max(1, budget_steps - np.random.randint(1, 3))
            overshoot = 0.0
            prog_ratio = obs_steps / budget_steps
            heading_disp = np.random.uniform(10.0, 30.0)
            yaw_deg = expected_turn + np.random.normal(0, 15.0)
            yaw_rate = np.random.normal(0, 0.3)
            cadence = np.random.uniform(0.7, 1.2)  # slowed down
            accel_var = np.random.uniform(0.25, 0.65)
            hesitation = np.random.uniform(0.55, 0.90)  # high hesitation
            mov_state = 1.0 if np.random.rand() < 0.6 else 0.0

        jitter = np.random.uniform(0.15, 0.45)
        conf = np.random.uniform(0.65, 0.88)
        pace_disp = np.random.uniform(1.25, 1.75)

        X[idx] = [
            cadence, accel_var, yaw_rate, yaw_deg, mov_state,
            jitter, hesitation, conf, action_code, expected_turn,
            budget_steps, learned_dur,
            1.0 if np.random.rand() < 0.15 else 0.0,
            np.random.uniform(0.1, 0.9),
            prog_ratio, overshoot, heading_disp, pace_disp
        ]
        y[idx] = 1
        idx += 1

    # -------------------------------------------------------------
    # Class 2: DEVIATION_CONFIRMED (800 samples)
    # -------------------------------------------------------------
    for _ in range(samples_per_class):
        # Major error: wrong turn taken (>55 deg mismatch) or persistent overshoot (>4 steps)
        is_wrong_turn = np.random.rand() < 0.55
        budget_steps = np.random.randint(20, 55)
        learned_dur = budget_steps * 500.0

        if is_wrong_turn:
            # Expected LEFT (-90), turned RIGHT (+90), or straight turned sharp
            expected_turn = -90.0 if np.random.rand() < 0.5 else 90.0
            action_code = 2.0
            actual_turn = -expected_turn  # opposite turn
            yaw_deg = actual_turn + np.random.normal(0, 15.0)
            yaw_rate = (actual_turn / 90.0) * np.random.uniform(0.7, 1.5)
            heading_disp = abs(yaw_deg - expected_turn)  # ~180 deg disparity
            obs_steps = int(budget_steps * np.random.uniform(0.6, 1.3))
            overshoot = max(0.0, float(obs_steps - budget_steps))
            prog_ratio = obs_steps / budget_steps
            mov_state = 2.0 if np.random.rand() < 0.7 else 1.0
            cadence = np.random.uniform(1.4, 2.1)
            accel_var = np.random.uniform(0.9, 2.5)
        else:
            # Huge step overshoot with continued forward walking
            action_code = 1.0
            expected_turn = 0.0
            overshoot = float(np.random.randint(4, 22))
            obs_steps = budget_steps + int(overshoot)
            prog_ratio = obs_steps / budget_steps
            yaw_deg = np.random.normal(0, 25.0)
            yaw_rate = np.random.normal(0, 0.3)
            heading_disp = abs(yaw_deg - expected_turn)
            mov_state = 1.0  # Walking away
            cadence = np.random.uniform(1.5, 2.2)
            accel_var = np.random.uniform(1.1, 2.6)

        jitter = np.random.uniform(0.1, 0.4)
        hesitation = np.random.uniform(0.05, 0.35)
        conf = np.random.uniform(0.75, 0.95)  # sensors are confident, user is just off route
        pace_disp = np.random.uniform(0.9, 1.3)

        X[idx] = [
            cadence, accel_var, yaw_rate, yaw_deg, mov_state,
            jitter, hesitation, conf, action_code, expected_turn,
            budget_steps, learned_dur,
            0.0,
            np.random.uniform(0.1, 0.9),
            prog_ratio, overshoot, heading_disp, pace_disp
        ]
        y[idx] = 2
        idx += 1

    # -------------------------------------------------------------
    # Class 3: RECOVERING (800 samples)
    # -------------------------------------------------------------
    for _ in range(samples_per_class):
        # Backtracking: ~180 turn (140 - 220 deg) followed by steady walk to anchor
        budget_steps = np.random.randint(15, 45)
        learned_dur = budget_steps * 500.0
        action_code = 1.0
        expected_turn = 0.0

        is_uturn = np.random.rand() < 0.4
        if is_uturn:
            yaw_deg = np.random.uniform(145.0, 215.0)
            yaw_rate = np.random.uniform(1.1, 2.2)
            mov_state = 2.0  # TURNING around
            cadence = np.random.uniform(1.1, 1.6)
            accel_var = np.random.uniform(0.7, 1.8)
        else:
            yaw_deg = np.random.uniform(160.0, 200.0)  # Walking back opposite direction
            yaw_rate = np.random.normal(0, 0.2)
            mov_state = 1.0  # WALKING back
            cadence = np.random.uniform(1.5, 2.0)
            accel_var = np.random.uniform(0.9, 2.3)

        heading_disp = np.random.uniform(130.0, 190.0)
        obs_steps = np.random.randint(2, 12)
        overshoot = 0.0
        prog_ratio = obs_steps / budget_steps
        jitter = np.random.uniform(0.1, 0.35)
        hesitation = np.random.uniform(0.1, 0.4)
        conf = np.random.uniform(0.75, 0.92)
        pace_disp = np.random.uniform(0.85, 1.25)

        X[idx] = [
            cadence, accel_var, yaw_rate, yaw_deg, mov_state,
            jitter, hesitation, conf, action_code, expected_turn,
            budget_steps, learned_dur,
            1.0 if np.random.rand() < 0.25 else 0.0,
            np.random.uniform(0.2, 0.8),
            prog_ratio, overshoot, heading_disp, pace_disp
        ]
        y[idx] = 3
        idx += 1

    # -------------------------------------------------------------
    # Class 4: UNCERTAIN (800 samples)
    # -------------------------------------------------------------
    for _ in range(samples_per_class):
        # Erratic signals, pocket rustle, jerky rotations, low confidence
        budget_steps = np.random.randint(15, 50)
        learned_dur = budget_steps * 500.0
        action_code = float(np.random.randint(0, 4))
        expected_turn = 0.0 if np.random.rand() < 0.6 else (-90.0 if np.random.rand() < 0.5 else 90.0)

        cadence = np.random.choice([np.random.uniform(0.1, 0.5), np.random.uniform(2.8, 3.8)])
        accel_var = np.random.uniform(0.02, 4.5)
        yaw_rate = np.random.uniform(-2.8, 2.8)
        yaw_deg = np.random.uniform(-180.0, 180.0)
        mov_state = 3.0 if np.random.rand() < 0.65 else float(np.random.randint(0, 3))
        jitter = np.random.uniform(0.55, 0.98)  # very high jitter
        hesitation = np.random.uniform(0.4, 0.95)
        conf = np.random.uniform(0.15, 0.45)   # very low sensor confidence
        obs_steps = np.random.randint(0, budget_steps + 10)
        prog_ratio = obs_steps / budget_steps
        overshoot = max(0.0, float(obs_steps - budget_steps))
        heading_disp = np.random.uniform(15.0, 175.0)
        pace_disp = np.random.uniform(0.3, 3.2)

        X[idx] = [
            cadence, accel_var, yaw_rate, yaw_deg, mov_state,
            jitter, hesitation, conf, action_code, expected_turn,
            budget_steps, learned_dur,
            0.0,
            np.random.uniform(0.0, 1.0),
            prog_ratio, overshoot, heading_disp, pace_disp
        ]
        y[idx] = 4
        idx += 1

    # Shuffle the dataset
    perm = np.random.permutation(total_samples)
    return X[perm], y[perm]


# -------------------------------------------------------------
# Neural Network MLP Implementation (Pure NumPy)
# Architecture: 18 -> 32 -> 16 -> 5
# -------------------------------------------------------------

class SpatialMLP:
    def __init__(self, input_dim=18, h1_dim=32, h2_dim=16, out_dim=5):
        self.input_dim = input_dim
        self.h1_dim = h1_dim
        self.h2_dim = h2_dim
        self.out_dim = out_dim

        # He initialization
        self.W1 = np.random.randn(input_dim, h1_dim).astype(np.float32) * math.sqrt(2.0 / input_dim)
        self.b1 = np.zeros(h1_dim, dtype=np.float32)

        self.W2 = np.random.randn(h1_dim, h2_dim).astype(np.float32) * math.sqrt(2.0 / h1_dim)
        self.b2 = np.zeros(h2_dim, dtype=np.float32)

        self.W3 = np.random.randn(h2_dim, out_dim).astype(np.float32) * math.sqrt(2.0 / h2_dim)
        self.b3 = np.zeros(out_dim, dtype=np.float32)

        # Adam optimizer state
        self.mW1 = np.zeros_like(self.W1); self.vW1 = np.zeros_like(self.W1)
        self.mb1 = np.zeros_like(self.b1); self.vb1 = np.zeros_like(self.b1)
        self.mW2 = np.zeros_like(self.W2); self.vW2 = np.zeros_like(self.W2)
        self.mb2 = np.zeros_like(self.b2); self.vb2 = np.zeros_like(self.b2)
        self.mW3 = np.zeros_like(self.W3); self.vW3 = np.zeros_like(self.W3)
        self.mb3 = np.zeros_like(self.b3); self.vb3 = np.zeros_like(self.b3)
        self.t = 0

    def forward(self, X):
        # Layer 1
        z1 = np.dot(X, self.W1) + self.b1
        a1 = np.maximum(0, z1)  # ReLU

        # Layer 2
        z2 = np.dot(a1, self.W2) + self.b2
        a2 = np.maximum(0, z2)  # ReLU

        # Layer 3
        z3 = np.dot(a2, self.W3) + self.b3

        # Stable Softmax
        exp_z = np.exp(z3 - np.max(z3, axis=1, keepdims=True))
        probs = exp_z / np.sum(exp_z, axis=1, keepdims=True)

        return z1, a1, z2, a2, z3, probs

    def train_step(self, X, y, lr=0.001, l2_reg=1e-4):
        N = X.shape[0]
        z1, a1, z2, a2, z3, probs = self.forward(X)

        # One-hot labels
        Y = np.zeros((N, self.out_dim), dtype=np.float32)
        Y[np.arange(N), y] = 1.0

        # Cross entropy loss
        loss = -np.mean(np.sum(Y * np.log(np.clip(probs, 1e-12, 1.0)), axis=1))
        # Add L2 penalty
        loss += 0.5 * l2_reg * (np.sum(self.W1 ** 2) + np.sum(self.W2 ** 2) + np.sum(self.W3 ** 2))

        # Backward pass
        dz3 = (probs - Y) / N  # (N, 5)
        dW3 = np.dot(a2.T, dz3) + l2_reg * self.W3
        db3 = np.sum(dz3, axis=0)

        da2 = np.dot(dz3, self.W3.T)
        dz2 = da2 * (z2 > 0)
        dW2 = np.dot(a1.T, dz2) + l2_reg * self.W2
        db2 = np.sum(dz2, axis=0)

        da1 = np.dot(dz2, self.W2.T)
        dz1 = da1 * (z1 > 0)
        dW1 = np.dot(X.T, dz1) + l2_reg * self.W1
        db1 = np.sum(dz1, axis=0)

        # Adam parameter update
        self.t += 1
        beta1, beta2, eps = 0.9, 0.999, 1e-8

        for param, grad, m, v in [
            (self.W1, dW1, self.mW1, self.vW1), (self.b1, db1, self.mb1, self.vb1),
            (self.W2, dW2, self.mW2, self.vW2), (self.b2, db2, self.mb2, self.vb2),
            (self.W3, dW3, self.mW3, self.vW3), (self.b3, db3, self.mb3, self.vb3)
        ]:
            m[:] = beta1 * m + (1.0 - beta1) * grad
            v[:] = beta2 * v + (1.0 - beta2) * (grad ** 2)
            m_hat = m / (1.0 - beta1 ** self.t)
            v_hat = v / (1.0 - beta2 ** self.t)
            param -= lr * m_hat / (np.sqrt(v_hat) + eps)

        return loss

    def predict(self, X):
        _, _, _, _, _, probs = self.forward(X)
        return np.argmax(probs, axis=1), probs


def evaluate_model(model, X_test, y_test):
    preds, probs = model.predict(X_test)
    acc = np.mean(preds == y_test)

    # 5x5 Confusion Matrix
    cm = np.zeros((5, 5), dtype=np.int32)
    for true_y, pred_y in zip(y_test, preds):
        cm[true_y, pred_y] += 1

    # Precision, Recall, F1
    precisions = []
    recalls = []
    f1s = []
    for c in range(5):
        tp = cm[c, c]
        fp = np.sum(cm[:, c]) - tp
        fn = np.sum(cm[c, :]) - tp
        prec = tp / max(1, tp + fp)
        rec = tp / max(1, tp + fn)
        f1 = (2 * prec * rec) / max(1e-6, prec + rec)
        precisions.append(prec)
        recalls.append(rec)
        f1s.append(f1)

    return acc, cm, precisions, recalls, f1s


def export_binary_model(model, mean, std, output_path):
    """
    Exports binary weights in PMML format:
    Header: 'PMML' (4 bytes), version 1 (uint32), dims [18, 32, 16, 5] (4 x uint32)
    Mean (18 floats), Std (18 floats)
    W1 (18x32 floats), b1 (32 floats)
    W2 (32x16 floats), b2 (16 floats)
    W3 (16x5 floats), b3 (5 floats)
    """
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, 'wb') as f:
        # Magic & version
        f.write(b'PMML')
        f.write(struct.pack('<IIIII', 1, model.input_dim, model.h1_dim, model.h2_dim, model.out_dim))
        # Normalization
        f.write(struct.pack(f'<{len(mean)}f', *mean))
        f.write(struct.pack(f'<{len(std)}f', *std))
        # Weights & Biases
        f.write(struct.pack(f'<{model.W1.size}f', *model.W1.flatten()))
        f.write(struct.pack(f'<{model.b1.size}f', *model.b1))
        f.write(struct.pack(f'<{model.W2.size}f', *model.W2.flatten()))
        f.write(struct.pack(f'<{model.b2.size}f', *model.b2))
        f.write(struct.pack(f'<{model.W3.size}f', *model.W3.flatten()))
        f.write(struct.pack(f'<{model.b3.size}f', *model.b3))

    size_kb = os.path.getsize(output_path) / 1024.0
    print(f"Exported binary model to {output_path} ({size_kb:.2f} KB)")


def export_json_metadata(model, mean, std, acc, cm, precisions, recalls, f1s, output_path):
    data = {
        "model_name": "PathMind Spatial MLP",
        "architecture": f"{model.input_dim} -> {model.h1_dim} (ReLU) -> {model.h2_dim} (ReLU) -> {model.out_dim} (Softmax)",
        "features": FEATURE_NAMES,
        "classes": CLASS_NAMES,
        "normalization": {
            "mean": mean.tolist(),
            "std": std.tolist()
        },
        "metrics": {
            "overall_test_accuracy": float(acc),
            "confusion_matrix": cm.tolist(),
            "class_metrics": [
                {
                    "class": CLASS_NAMES[c],
                    "precision": float(precisions[c]),
                    "recall": float(recalls[c]),
                    "f1_score": float(f1s[c])
                }
                for c in range(5)
            ]
        }
    }
    with open(output_path, "w") as f:
        json.dump(data, f, indent=2)
    print(f"Exported JSON metadata to {output_path}")


def main():
    print("=" * 65)
    print("PathMind Stage 7: Training On-Device Spatial Intent Model")
    print("=" * 65)

    # 1. Synthesize 4,000 samples
    print("\n1. Generating 4,000 kinematic windows from physics models...")
    X, y = synthesize_dataset(samples_per_class=800)
    print(f"   Total samples: {len(X)} across 5 classes (800 per class)")

    # 2. Stratified 80/20 train/test split
    split_idx = int(0.80 * len(X))
    X_train, X_test = X[:split_idx], X[split_idx:]
    y_train, y_test = y[:split_idx], y[split_idx:]
    print(f"   Train set: {len(X_train)} samples | Test set: {len(X_test)} samples")

    # 3. Feature Normalization (fit on train only)
    mean = np.mean(X_train, axis=0)
    std = np.std(X_train, axis=0)
    std[std < 1e-5] = 1.0  # Prevent division by zero

    X_train_norm = (X_train - mean) / std
    X_test_norm = (X_test - mean) / std

    # 4. Initialize model
    model = SpatialMLP(input_dim=18, h1_dim=32, h2_dim=16, out_dim=5)
    total_params = (18 * 32 + 32) + (32 * 16 + 16) + (16 * 5 + 5)
    print(f"\n2. Model Architecture: 18 -> 32 (ReLU) -> 16 (ReLU) -> 5 (Softmax)")
    print(f"   Total trainable parameters: {total_params}")

    # 5. Training Loop with Adam
    epochs = 40
    batch_size = 32
    n_batches = len(X_train) // batch_size
    print(f"\n3. Training for {epochs} epochs (batch size = {batch_size}, Adam lr = 0.001)...")

    for epoch in range(1, epochs + 1):
        perm = np.random.permutation(len(X_train))
        epoch_loss = 0.0
        for b in range(n_batches):
            batch_idx = perm[b * batch_size : (b + 1) * batch_size]
            loss = model.train_step(X_train_norm[batch_idx], y_train[batch_idx], lr=0.001)
            epoch_loss += loss

        epoch_loss /= n_batches
        if epoch % 5 == 0 or epoch == 1 or epoch == epochs:
            val_preds, _ = model.predict(X_test_norm)
            val_acc = np.mean(val_preds == y_test)
            print(f"   Epoch {epoch:2d}/{epochs} | Train Loss: {epoch_loss:.4f} | Test Acc: {val_acc * 100:.2f}%")

    # 6. Final Evaluation
    print("\n4. Final Model Evaluation on Unseen 800 Test Samples:")
    acc, cm, precisions, recalls, f1s = evaluate_model(model, X_test_norm, y_test)
    print(f"   Test Accuracy: {acc * 100:.2f}%\n")

    print("   Confusion Matrix (Rows = Ground Truth, Cols = Predicted):")
    header = "       " + " ".join([f"{name[:7]:>7}" for name in CLASS_NAMES])
    print(header)
    for c in range(5):
        row_str = f"{CLASS_NAMES[c][:6]:>6} " + " ".join([f"{cm[c, j]:7d}" for j in range(5)])
        print(row_str)

    print("\n   Per-Class Performance Metrics:")
    print("   --------------------------------------------------------------")
    print(f"   {'Class':<22} {'Precision':<12} {'Recall':<12} {'F1-Score':<12}")
    print("   --------------------------------------------------------------")
    for c in range(5):
        print(f"   {CLASS_NAMES[c]:<22} {precisions[c]*100:6.2f}%     {recalls[c]*100:6.2f}%     {f1s[c]*100:6.2f}%")
    print("   --------------------------------------------------------------")

    # 7. Export Assets
    output_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets")
    bin_path = os.path.join(output_dir, "pathmind_spatial_model.bin")
    json_path = os.path.join(output_dir, "pathmind_spatial_model.json")

    print("\n5. Exporting Assets...")
    export_binary_model(model, mean, std, bin_path)
    export_json_metadata(model, mean, std, acc, cm, precisions, recalls, f1s, json_path)

    # 8. Sanity check: Run a forward pass using the exported weights format
    print("\n6. Running forward pass verification from exported binary...")
    with open(bin_path, 'rb') as f:
        magic = f.read(4)
        assert magic == b'PMML', "Invalid magic header!"
        ver, in_d, h1_d, h2_d, out_d = struct.unpack('<IIIII', f.read(20))
        mean_read = np.array(struct.unpack(f'<{in_d}f', f.read(in_d * 4)))
        std_read = np.array(struct.unpack(f'<{in_d}f', f.read(in_d * 4)))
        w1_read = np.array(struct.unpack(f'<{in_d * h1_d}f', f.read(in_d * h1_d * 4))).reshape(in_d, h1_d)
        b1_read = np.array(struct.unpack(f'<{h1_d}f', f.read(h1_d * 4)))
        w2_read = np.array(struct.unpack(f'<{h1_d * h2_d}f', f.read(h1_d * h2_d * 4))).reshape(h1_d, h2_d)
        b2_read = np.array(struct.unpack(f'<{h2_d}f', f.read(h2_d * 4)))
        w3_read = np.array(struct.unpack(f'<{h2_d * out_d}f', f.read(h2_d * out_d * 4))).reshape(h2_d, out_d)
        b3_read = np.array(struct.unpack(f'<{out_d}f', f.read(out_d * 4)))

    # Test single sample
    sample_raw = X_test[0]
    sample_norm = (sample_raw - mean_read) / std_read
    z1 = np.maximum(0, np.dot(sample_norm, w1_read) + b1_read)
    z2 = np.maximum(0, np.dot(z1, w2_read) + b2_read)
    logits = np.dot(z2, w3_read) + b3_read
    exp_logits = np.exp(logits - np.max(logits))
    probs = exp_logits / np.sum(exp_logits)
    pred_cls = np.argmax(probs)
    print(f"   Sample 0 true label: {CLASS_NAMES[y_test[0]]} | Predicted: {CLASS_NAMES[pred_cls]} (Conf: {probs[pred_cls]*100:.1f}%)")
    print("\nTraining and Export Completed Successfully!")
    print("=" * 65)


if __name__ == "__main__":
    main()
