# HapticSync architecture

This document explains how HapticSync turns a video's soundtrack into synchronised haptics. It
covers where the audio comes from, how time is kept, how sounds are classified, how vibration
is rendered and scheduled on each kind of phone, how haptics are prepared ahead of playback,
and how files without a seek index are made seekable.

## Layers

```
app (Compose UI, view models, persistence)
 ├── platform:playback   Media3 ExoPlayer + PcmTapAudioSink,          ─┐ Android
 │                       ahead-of-playback decoder, seek indexing      │
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
  encoder-delay frames that the sink trims, and reports each buffer at the time it is actually
  heard (see [Media time and sink time](#media-time-and-sink-time)).
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
| `HapticSync-prepare` | Decodes the soundtrack ahead of playback and builds the prepared haptic track. | Background |
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

### Media time and sink time

A prepared haptic track is built on the *media timeline*: the item's own timeline with the
encoder delay (priming samples) removed. The sink's clock runs on *renderer time*. The two are
related by `sink time = media time + offset`, where

```
offset = outputStreamOffset + encoderDelay − trimmedDelay
```

Media3 trims the encoder delay only at the start of a stream (`trimmedDelay = encoderDelay`), and
after a seek it does not trim again (`trimmedDelay = 0`), because the seek position is already
on the media timeline. `PcmTapAudioSink` tracks both regimes and reports the offset through
`MediaAudioInput.onMediaTimeOffset` whenever it changes (configure, stream offset, flush).

An earlier version stamped the first buffer after the trim with the untrimmed timestamp, so for
AAC (about 2,000 priming samples) the whole stream was analysed about 45 ms later than it was
heard. The emulator test `PreparedTimingInstrumentedTest` now checks that the ahead-of-playback
decoder and the playing sink agree on when a click happens within 3 ms, before and after a seek.

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

## Prepared haptics

Live analysis must decide what a sound is within the ~250 ms the sink buffers ahead of the
speaker, and cannot know what comes next. Preparing the haptics ahead of playback removes both
limits. This is the same idea as the published descriptions of music haptics on other
platforms: a haptic track of transient and continuous intensity, computed before the sound
plays. (Apple's own algorithm is not public, and none of it is used here.)

**Decoding.** `MediaAudioDecoder` reads the file with `MediaExtractorCompat` (Media3's own
extractors, including the seek indexing below), so sample times are exactly those ExoPlayer
plays. The platform `MediaCodec` decodes them. The decoder is configured *not* to trim the
encoder delay; it is removed here, so the output is on the media timeline.

**Building.** `HapticTrackBuilder` runs the full `AudioAnalyzer` over the decoded audio, starting
1.5 s early to warm up its adaptive state. Every candidate event is held for 4 s of look-ahead
before it is kept:
- **Salience.** An event whose level is far below the programme level around it (before and
  after) is background clutter and is dropped. The margin is 8–22 dB and grows with the event
  sensitivity. General (unclassified) sounds get half the margin and need a minimum strength.
- **Masking.** An event within 120 ms of one at least 1.6× stronger is dropped.
- **Density.** Once 12 events have been kept within a second, further ones in that second are
  dropped.

Events and per-hop texture levels (one byte each, at the analysis hop of 10 ms) are stored in a
`HapticTrack` in segments, each with a *ready range*. Preparation starts at the playback
position and runs far ahead of it. After a seek outside the ready range, a new segment starts
at the seek target and stops when it reaches audio that is already prepared. The track also
records where the audio ends: anything after that (a video track that runs longer than its
audio) counts as prepared, and audio that starts the item is ready from 0 even when its first
sample comes a little later, as in many screen recordings.

**Playing.** `TrackPlayer` runs on the scheduler thread instead of the live scheduler:
1. It renders the next window of the track (0.5–1.5 s, nominally 1 s): transient curves from
   `HapticMapper` and the texture, mixed on a 5 ms grid with the current output settings
   (intensity, envelope, isolation, calibration). Texture below 6% is gated to zero, and texture
   is ducked to 40% under transients, so strong motors only play meaningful sounds.
2. The window ends at the quietest hop near its nominal length, so hand-overs fall where
   nothing is felt.
3. It issues the window as one amplitude waveform (an on/off pattern on motors without
   amplitude control) when `clock ≥ windowStart + syncOffset − motorLatency`, and the next
   window 2 ms before the current one ends.
4. Seeks, pauses and settings changes stop the current window and restart rendering at the
   current position (settings at most every 150 ms while a slider is dragged).

The motor receives about one command per second, and every transient lands where the analysis
placed it, independent of scheduler wake-up jitter.

**Player flow.** `HapticPreparationController` (app) holds playback when an item opens until
15 s are prepared. After a seek into unprepared audio it pauses until 5 s are prepared, and it
pauses in the same way if playback catches up with preparation (less than 1 s ahead). *Play now* skips the wait;
haptics then join as soon as their audio is ready. Changing detection settings (bands,
sensitivities, analysis) prepares a new track from the current position and switches to it
once 3 s are ready. If the audio cannot be decoded ahead of playback, the engine falls back to
live analysis and the player shows why.

## Seeking in unindexed files

ExoPlayer can seek only with an index. Fragmented MP4 without a `sidx` box and Matroska/WebM
without `Cues` have none, so their seek map is *unseekable* and every seek silently restarts
from the beginning. Screen recordings and files from some cameras, editors and download tools
are fragmented this way.

`SeekIndexingExtractorsFactory` wraps the default extractors:
- constant-bitrate seeking is enabled for the formats that support it (MP3, ADTS AAC and AMR
  without an index);
- for fragmented MP4 and Matroska it intercepts `ExtractorOutput.seekMap`. When the extractor
  reports an unseekable map, `FragmentedMp4Indexer` or `MatroskaIndexer` scans the file once
  (only box and element headers: `moof`/`tfdt`/`trun`, or `Cluster`/`Timecode`) and hands the
  player a `ChunkIndex` of fragment or cluster start times and byte offsets.

The scan reads only headers, so it takes milliseconds per minute of media. The offline decoder
uses the same factory, so preparation can seek in these files too. When seeking is still
impossible (e.g. a stream that cannot be read randomly), the player disables the seek controls
and explains why instead of restarting the video.

## Isolation mode

Isolation restricts the haptic output to the sounds you select. Any number of targets can be
combined, and the output is their union:
- *Category* and *group* targets allow events and texture of those categories.
- *Band* targets (and the low-frequency group) also drive haptics from the selected bands'
  energy.

Selecting a target turns isolation on; removing the last one turns it off. Settings saved by
earlier versions (a single target) are migrated on load.

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
- **`core:engine`:** prepared tracks: events land on the drums, quiet clutter is filtered,
  segments warm up, join and stop at prepared audio; the track player carries every click on
  time, compensates motor latency, stops on pause, restarts after seeks, stays silent where the
  track is not ready and drives on/off motors; the engine follows the sink clock with a track
  and returns to live analysis without one.
- **`core:engine`:** end-to-end runs through a simulated Media3 sink, covering:
  - drum timing (< 2 ms mean dispatch error, > 50 ms lead);
  - pause/resume, seeking and completion;
  - sample rates, format changes and device fallbacks;
  - isolation, live preset changes, sync offset, 2× speed, the master switch and the
    visualisation.

The instrumented tests run on a device or emulator:
- **`platform:playback`:** plays a generated WAV through the real Media3 pipeline. It checks
  that the format, monotonic timestamps, the output position, pause and seek discontinuities
  reach the engine. `PreparedTimingInstrumentedTest` encodes clicks to AAC on the device and
  checks that:
  - the offline decoder and the playing sink agree within 3 ms, with an encoder delay in the
    file and with a platform-muxed file, before and after a seek;
  - WAV decodes from zero without gaps;
  - fragmented MP4 without an index, unseekable for the default extractors, seeks to the right
    place in the player and in the decoder.

  JVM unit tests (`SeekIndexersTest`) cover the fragmented-MP4 and Matroska index builders.
- **`platform:vibration`:** checks the capability probe against the API level. It also checks
  that every effect the renderer produces is accepted by Android's `VibrationEffect`
  validation.
- **`app`:** a Compose smoke test through Home, Haptic Studio, Calibration, Presets and Demo
  mode.
