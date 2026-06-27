# Domain - Voice Review

Task: `t_42d60ace`
Scope: voice instruction engine and safe word listener.

Reviewed assigned files under `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/`.
`VoiceInstruction.kt` and `VoiceInstructionEngine.kt` were not present at the assigned paths. Repository search found no `VoiceInstruction` or `VoiceInstructionEngine` declarations; current voice-domain files are `SafeWordListener.kt`, `SafeWordListenerFactory.kt`, and `SafeWordDetectionManager.kt`. Because the assigned engine files are stale/missing, `SafeWordDetectionManager.kt` was also inspected as the apparent current safe-word engine/manager.

## Summary

- Files assigned: 3
- Assigned files found and reviewed: 1
- Assigned files missing: 2
- Additional current voice-domain files inspected for context: 1
- Findings: 5
- Severity breakdown:
  - Critical: 0
  - High: 1
  - Medium: 4
  - Low: 0

## Findings by file

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordListener.kt`

#### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 26, 38-44
- Description: The common listener contract exposes `startListening()`, `isListening`, and `detectedWord`, but no error/status channel for unavailable recognition, denied microphone/speech permission, unsupported on-device recognition, or start failures. The platform implementations can fail by logging and leaving `isListening` false, which leaves callers unable to distinguish “not yet started”, “already stopped”, and “emergency voice stop is unavailable”. For a safety-oriented emergency stop path, silent non-operation can make the workout UI appear configured while no voice stop will ever fire.
- Suggested fix direction: Add a shared status/error flow or sealed state such as `Idle`, `Listening`, `PermissionDenied`, `RecognitionUnavailable`, and `StartFailed`, and have calibration/workout UI surface failures instead of relying only on logs and `isListening`.

#### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 41-44
- Description: The API documents that emitted detections occur when results “match the safe word”, but it does not define or enforce what a valid safe word is. The current platform implementations perform whole-token matching after splitting transcripts on whitespace, while settings/calibration paths only require a non-blank string. A user can configure a multi-word phrase or punctuation-bearing value that appears valid in common code but can never match the actual token-based listener.
- Suggested fix direction: Define the safe-word normalization/validation contract in common code. Either restrict configured safe words to one normalized token and reject/trim punctuation at save/calibration time, or update platform matching to normalize transcripts and support the same phrase rules accepted by the UI.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/VoiceInstruction.kt`

#### Finding 3
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned file does not exist at `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/VoiceInstruction.kt`, and repository search found no `VoiceInstruction` declaration. This review target cannot be inspected as specified and may indicate a removed/renamed domain type, stale task generation, or missing voice-instruction model coverage.
- Suggested fix direction: Update the review/task manifest to the current file path if the type was renamed or removed. If the voice instruction feature is still expected, restore a common domain model or adapter and add tests/usages that prevent the file from silently disappearing.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/VoiceInstructionEngine.kt`

#### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned file does not exist at `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/VoiceInstructionEngine.kt`, and repository search found no `VoiceInstructionEngine` declaration. The current common voice-domain manager appears to be `SafeWordDetectionManager.kt`, so the requested instruction-engine review target is stale or the engine abstraction has been removed without updating review manifests/documentation.
- Suggested fix direction: Update the manifest/documentation to point at the current engine/manager file, or restore the expected engine abstraction if callers/design docs still depend on `VoiceInstructionEngine`.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/voice/SafeWordDetectionManager.kt` (additional current voice-domain file inspected)

#### Finding 5
- Category: bug
- Severity: high
- Line numbers: 73
- Description: `startForWorkout()` logs the configured safe word verbatim: `Starting safe word detection for workout (word: "$safeWord")`. The platform listener implementations deliberately redact the safe word in their startup logs, so this manager reintroduces leakage of user-chosen voice credentials/PII into application logs whenever a workout starts.
- Suggested fix direction: Redact the safe word in manager logs as well. Log only non-sensitive metadata such as safe-word length or a boolean configured/calibrated state, matching the platform listener audit behavior.
