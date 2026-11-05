# HapticSync architecture

This document explains how HapticSync turns a video's soundtrack into synchronised haptics. It
covers where the audio comes from, how time is kept, how sounds are classified, and how
vibration is rendered and scheduled on each kind of phone.

## Layers

```
app (Compose UI, view models, persistence)
 ├── platform:playback   Media3 ExoPlayer + PcmTapAudioSink          ─┐ Android
 ├── platform:vibration  Vibrator backend + capability probe          ─┘
 └── core:engine         orchestration: ingest, threads, clock, visualisation  ─┐
      ├── core:haptics   mapper, renderer, scheduler, calibration patterns       │ pure Kotlin/JVM
      ├── core:analysis  features, onsets, detectors, classifier                 │ (unit-tested
      ├── core:model     settings, presets, calibration (serialisable)           │  without Android)
      └── core:dsp       FFT, filters, PCM conversion, pitch, synthesis         ─┘
```

The engine depends only on two small interfaces, both implemented in `platform`:
- `MediaAudioInput`: what the player tells the engine (PCM, format, position, play state).
- `HapticOutput`: what the engine tells the vibrator (play a rendered effect, cancel).

The JVM tests implement the same interfaces with a simulated audio sink and a recording
output. This lets complete scenes run through the real analysis, mapping and scheduling code
in milliseconds.

## Where the audio comes from

`HapticRenderersFactory` builds ExoPlayer's audio renderer with a `PcmTapAudioSink`, a
`ForwardingAudioSink` around Media3's `DefaultAudioSink`. The tap is in the sink rather than
anywhere else for these reasons:

- **Exact timestamps.** `handleBuffer` delivers each decoded buffer with its presentation
  timestamp. The renderer may offer the same buffer several times until the sink has consumed
  it, so the tap tracks the consumed range and forwards each byte exactly once. It also skips
  encoder-delay frames that the sink trims, so later timestamps are not shifted.
- **Original timeline.** The sink's speed processor (Sonic) runs after the tap. Analysis
  therefore always sees the media timeline, at any playback speed.
- **Look-ahead.** The sink's AudioTrack buffer holds roughly 250 ms of audio ahead of the
  speaker. Audio is analysed when it enters the sink, i.e. before it is heard.
- **Clock.** `getCurrentPositionUs()` is the sink's output position, corrected for output
  latency by Media3. Its time base ("renderer time") is the same as the PCM timestamps, so the
  two can be compared directly.
- **No side effects.** The audio passes through unmodified. No microphone, `AudioPlaybackCapture`
  or `Visualizer` session is involved.

Encoded passthrough or offload (audio sent compressed to a receiver) never produces PCM.
The tap reports the stream as unsupported and the UI shows a notice.

## Threads

| Thread | Work | Priority |
|---|---|---|
| ExoPlayer playback thread | Calls `MediaAudioInput`: copies PCM into `PcmIngest`, reports position and play state. Non-blocking, allocation-free. | Media3 default |
| `HapticSync-analysis` | Converts PCM to mono/side floats, runs `AudioAnalyzer` hop by hop, maps events to effects, feeds the scheduler and the visualisation buffers. | Audio |
| `HapticSync-scheduler` | Waits on the media clock and issues vibrator commands. | Audio |
| `HapticSync-semantic` (optional) | Runs an on-device model about twice per second. | Background |
| Main | UI; reads lock-free snapshots once per frame. | — |

`PcmIngest` is a single-producer/single-consumer ring of up to 131,072 frames. Alongside the
samples it records *anchors* (frame index → PTS, epoch, format id), so every analysed hop knows
its media time even across format changes and seeks.

## Keeping time

- **Media clock.** `PlaybackClock` stores the last sink position with its `System.nanoTime()`
  and extrapolates with the playback speed. Extrapolation is limited to 500 ms, and the clock
  freezes the moment playback stops.
- **Fire time.** Every effect carries the media time of the sound it represents. The scheduler
  issues it when `clock ≥ mediaTime + syncOffset − actuatorLatency`. The user's sync offset is
  negative for earlier; the actuator latency defaults to 18 ms. Waits are derived from the
  media clock, never from free-running timers.
- **Epochs.** Seeks, flushes, track changes and resets increment the epoch. Effects and
  continuous-plan samples from an older epoch are discarded, and the vibrator is cancelled.
- **Lateness.** A transient whose fire time passed more than 40 ms ago is dropped. A long
  effect that is moderately late has its beginning skipped, so the remainder stays aligned.
- **Stopping.** Pause, buffering, the end of the stream, the master switch and leaving the app
  all reach `HapticScheduler.playbackStopped()`. That call cancels the output under the same
  lock that dispatch uses, so no command can start after playback has stopped.

## Analysis

Per hop (10 ms by default, over a 32 ms Hann window):

- **Spectrum.** A real FFT gives a 48-bar log spectrum, spectral centroid, flux and roll-off.
- **Bands.** A six-band IIR filter bank (double-precision biquads; the band ranges are
  editable) gives per-band energy, peak, noise floor and fast-release envelopes.
- **Level.** RMS, peak and zero-crossing rate, plus adaptive loudness normalisation.
- **Onsets.** Band-wise rises above an adaptive threshold. Each onset's time is refined on the
  raw waveform, which keeps clicks within a few milliseconds.
- **Pitch.** A decimated harmonic-product-spectrum tracker (8 kHz, 2048-point FFT) estimates
  the engine fundamental. A semitone log spectrum, compared hop to hop by cross-correlation,
  gives pitch velocity. Glide memory, voicing gates and run tracking detect revs, acceleration,
  deceleration, gear shifts (abrupt pitch drops) and pass-bys (a loudness swell with a Doppler
  drop).
- **Other detectors.**
  - A rhythm tracker for beats.
  - A speech detector for syllabic modulation, which suppresses haptics during dialogue.
  - A thunder detector for slow, deep rises.
  - Texture detectors for rain, wind and machinery.

**Classification.** When an onset occurs, the classifier keeps observing it for the *decision
time* (90 ms by default), still inside the look-ahead. It then scores every category with fuzzy
evidence from these features:
- the share of each band in the onset's rise;
- attack time and decay rate;
- sustain and late peak;
- tonality, pitch dynamics and stereo movement;
- masking by louder sounds and the current rhythm phase.

The best category wins if it exceeds an acceptance threshold set by the global and
per-category sensitivities. The decision window is automatically shortened when the measured
look-ahead is smaller (with a 60 ms safety margin), so decisions are always made before the
sound is heard.

These categories describe how a sound *behaves*. Spectral features cannot prove what produced
it. The `SemanticClassifier` hook accepts an on-device model that returns per-category prior
multipliers in [0.5, 1.5]. These priors only tip close decisions, and the engine works without
them.

## From sound to vibration

`HapticMapper` turns classified events into device-independent `HapticCurve`s: amplitude in
5 ms steps plus a sharpness value. It uses transient templates per category (a kick thump, a
snare tap, a gunshot crack, an explosion with aftershocks, a gear-shift kick), shaped by the ten
user parameters and the category's settings.

Sustained sounds (engine, rumble, sustained bass, rain, machinery) become a *continuous plan*:
a texture level sampled every hop, following RPM and band energy. Fatigue protection gradually
lowers long, strong passages.

`HapticRenderer` encodes curves for the device, in this order of preference:

| Device | Encoding |
|---|---|
| Android 16 with frequency control | `WaveformEnvelopeBuilder` amplitude/frequency envelopes; sharpness maps to frequency within the motor's usable band |
| Android 11+ with composition primitives | Short transients as primitives (click, thud, tick…) with scale; everything else as amplitude waveforms |
| Amplitude control | `createWaveform(timings, amplitudes)` at 10 ms resolution |
| Predefined effects only | Click / heavy click / tick for short hits |
| On/off motors | Pulse-width (duty-cycle) patterns that approximate amplitude |

Calibration is applied last:
- the device gain;
- the response curve `out = in^γ`;
- the minimum perceptible amplitude, which raises faint effects and drops near-zero ones;
- the maximum intensity.

`AndroidHapticOutput` attributes every vibration to the selected usage class: media by
default, through `VibrationAttributes` on Android 13+ or `AudioAttributes` before that. If the
platform rejects an effect, it is retried once as a plain one-shot. If the permission is
revoked, output is disabled.

**Mixing in the scheduler:**
- Transients pre-empt the continuous texture.
- A running transient is interrupted only by a higher-priority one, or once it is half over.
- Consecutive pulses respect the user's minimum spacing.
- Simultaneous effects coalesce into the most important one.
- The continuous layer is issued in 150 ms segments and re-issued shortly before each segment
  ends.

## Isolation mode

Isolation restricts the haptic output to one category, one band or one group:
- *Category* and *group* targets filter events and texture by category.
- *Band* targets (and the low-frequency group) drive haptics from the selected bands' energy
  only.

Isolation is a listening mode. It does not modify a preset and is kept when presets are
switched.

## Persistence

All settings are `kotlinx.serialization` documents in a Preferences DataStore:
- app settings;
- the working profile;
- the Custom profile;
- user presets;
- per-device calibration profiles;
- recently played items.

`HapticSettingsController` applies every change to the engine immediately and persists it after
a short debounce, so dragging a slider is felt at once without writing to storage at 60 Hz.

## Performance

Steady-state analysis allocates nothing per hop. In *Automatic* quality, the engine measures
its own cost per hop and, if the device falls behind, reduces work in steps: spectral analysis
every second hop, then every fourth. The status read-out in the sync test shows the look-ahead
and the mean dispatch error.

## Tests

The JVM tests use procedurally synthesised scenes with ground-truth labels: engines with real
gear-shift pitch curves, drums, explosions, gunfire, speech-like signals, weather and sync
clicks. They cover:

- **`core:dsp`:** FFT accuracy, filter responses, PCM conversion for every encoding, pitch
  tracking and smoothing.
- **`core:analysis`:**
  - classification of each scene, including timing tolerances;
  - robustness to gain, noise, sample rate (16–96 kHz), mono input and DC offset.
- **`core:haptics`:**
  - mapping per category;
  - every rendering strategy;
  - scheduler timing, epochs, staleness, pause safety and mixing.
- **`core:engine`:** end-to-end runs through a simulated Media3 sink, covering:
  - drum timing (< 2 ms mean dispatch error, > 50 ms lead);
  - pause/resume, seeking and completion;
  - sample rates, format changes and device fallbacks;
  - isolation, live preset changes, sync offset, 2× speed, the master switch and the
    visualisation.

The instrumented tests run on a device or emulator:
- **`platform:playback`:** plays a generated WAV through the real Media3 pipeline. It checks
  that the format, monotonic timestamps, the output position, pause and seek discontinuities
  reach the engine.
- **`platform:vibration`:** checks the capability probe against the API level. It also checks
  that every effect the renderer produces is accepted by Android's `VibrationEffect`
  validation.
- **`app`:** a Compose smoke test through Home, Haptic Studio, Calibration, Presets and Demo
  mode.
