# Polar H10 release notes and device checks

## User-facing release notes

- Polar H10 adds heart rate, R-R intervals, ECG preview, torso movement and optional five-minute post-workout recovery tracking.
- Workout averages and movement maxima describe the recorded set. Recovery measurements are stored separately on its session, only after the 60-second or 300-second observation window completes. Canceling early leaves unfinished measurements unavailable.
- These measurements describe a workout and are not a clinical risk assessment.
- Polar summaries and traces stay on this device and in exported file backups. They are not synchronized to Phoenix Portal. Reinstalling and pulling from the portal does not restore Polar data; restore a file backup to recover it.
- Raw ECG storage is off by default. Enable **Save raw ECG** in Polar settings to retain traces. Capture is limited to five minutes (39,000 samples) per recording, stored traces expire after 30 days, and only the newest 36,000 segments are retained. Older traces can disappear before their sessions; summaries remain available. Retention is applied when the telemetry repository opens and when it saves telemetry.
- File backups preserve summaries regardless of the raw telemetry option. Beat samples, ECG and synchronized metric samples require **Include raw telemetry** when exporting. Backup files are not changed by local trace expiry.
- Streaming and automatic reconnection start with recording. Opening the workout screen alone does not start a scan or ECG/accelerometer stream. An explicit Disconnect remains effective until the user reconnects.

## Required device evidence before release

Local automated tests do not satisfy these checks. Record app commit, OS/device, strap firmware, trainer model and observed results for each run.

1. On both Android and iOS, pair an H10 alongside a V-Form and a Trainer+. Verify the first set, multiple sets, manual stop, automatic completion and routine completion. Check that the completion UI remains visible if the trainer is switched off while recovery continues.
2. On iOS, run a trainer reconnect scan concurrently with a remembered-H10 targeted scan and a manual H10 scan. Let each scan succeed, time out and be canceled in both orders. Confirm ending either scanner does not prevent the other device reconnecting. Kable shares a CoreBluetooth central manager, so this interaction remains an explicit release gate.
3. Take the H10 out of range during connection and PMD setup. Reconnect without restarting the app. Repeat with a failed PMD subscription; standard heart-rate notifications and connection-state changes must remain observable.
4. Leave the workout screen for routine completion during recovery. Check ECG/ACC continuity while recording owns the streams, and teardown after recovery ends. Verify Disconnect in Settings prevents automatic reconnection on subsequent screen entry or set start until explicitly reconnected.
5. Cancel recovery at 20 seconds and after 60 seconds. Confirm the first stores no HRR window and the second preserves HRR60 only. Remove the strap briefly and for more than three seconds; incomplete windows must remain absent, while previously captured rows survive. Repeat with HR notifications interrupted while PMD traffic continues.
6. Verify raw ECG default-off, opt-in, five-minute capture cap, expiry and bounded storage. Export and restore with raw telemetry both enabled and disabled, and verify historical recovery values with no strap connected.
7. On physical iOS, verify telemetry transactions with the production SQLite driver while a new set starts and an earlier set finishes saving. Reopen the app and confirm parent ownership, summaries and retained raw rows.

The release remains gated on macOS/iOS compilation, hosted CI for the published patch, and these physical-device checks.
