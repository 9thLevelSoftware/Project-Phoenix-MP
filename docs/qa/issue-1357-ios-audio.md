# Issue #1357: capture iOS workout audio loss

Status: diagnostic instrumentation; root cause and recovery fix remain unconfirmed.
Tracking: [#1357](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/1357).

The report identifies an iPhone 13 Pro despite the issue template's Android selection.
Sounds work during the three-rep workout setup, then remain silent after switching
to Email and returning without closing Phoenix. Other reported triggers are an
incoming call and starting with the phone's sound off before turning it on. Record
which sound control was used; silent mode, media volume, and output route are
different variables.

The earlier simulator Test Sounds/Safari run reported successful native player
calls. It did not reproduce this workout failure, exercise an actual interruption,
or establish audible output. Existing foreground/interruption-ended recovery is
already present. Missing native results were a diagnostic gap, not an established
cause. This patch adds observations without changing playback/recovery policy.

## Capture on an affected iPhone

1. Build/install the instrumented revision on macOS first. Record revision, app
   version, iPhone model, actual iOS version, sound settings, output device type,
   and whether Safe Word is enabled. Do not change these between the baseline
   and failing sequence.
2. Open **Settings → Connection Logs**, ensure logging is enabled, and clear old
   entries before starting. The log is in memory, capped at 1,000 entries shared
   with BLE diagnostics, and is lost on app termination.
3. Start the workout and verify an audible cue during the three-rep setup. Note
   the time. Switch to Email, return to Phoenix, then trigger a few expected cues.
   Note what was heard, whether rep counts continued, and whether haptics worked.
4. Immediately open **Connection Logs → Export → Copy to clipboard**. Preserve
   the full export before more workout/BLE events evict the relevant entries.
   The `WORKOUT_AUDIO` search helps viewing; export includes all retained entries.
5. In separate runs, capture the incoming-call and sound-off/start/on sequences.
   Record call dismissal versus answering, and the exact sound control used.
   A normal app switch and an audio interruption are separate test cases.

Do not restart Phoenix before exporting the failure. The new audio records omit
route names/UIDs, speech, configured safe words, and error descriptions/userInfo;
the full existing connection export can still contain device identifiers.

## Reading the evidence

Order `WORKOUT_AUDIO` records by timestamp (exports are newest first). Correlate:

- `ActiveSessionEngine` rep-event snapshots: rep type/count, cue preference flags,
  timing, and subscriber count. These occur after execution-lease validation and
  before cue selection; they do not prove an emission or acoustic output.
- The iOS collector lifetime and received cue, then selected-player outcomes.
- App lifecycle, interruption type/options/suspension information, route changes,
  and media-service notifications.
- Audio-session configuration/activation results and NSError domain/code, with
  session category/mode/options, output volume, and output port types.
- Safe Word configuration/deactivation and recovery ordering, when enabled.

A missing collector receipt narrows the investigation to cue selection/delivery
or collector lifetime. A received cue with failed activation/preparation/playback
narrows it to native audio. Successful native calls with silence still require
route, volume, and audible observations; `isPlaying=true` is not acoustic proof.
No single record establishes Safe Word ownership: correlate its own transitions
instead of inferring ownership from `PlayAndRecord` alone.

Apple documents [audio interruptions](https://developer.apple.com/documentation/avfaudio/handling-audio-interruptions),
[route changes](https://developer.apple.com/documentation/avfaudio/responding-to-audio-route-changes),
and [media-service resets](https://developer.apple.com/documentation/avfaudio/avaudiosession/mediaserviceswereresetnotification)
as separate notification paths. The added route/reset observers only record them.

## Validation boundary

Host tests can check shared behavior and existing iOS source guards. They cannot
compile Apple platform interop or establish notification delivery, session state,
or audible recovery. A macOS iOS framework/app build and the failing physical-device
trace remain required before selecting a root-cause fix. After that fix, repeat
the failing sequence with audible cues before/after, including Safe Word on/off
and speaker/headphone routes where relevant.
