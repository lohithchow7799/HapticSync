# HapticSync

**Feel what you watch.** HapticSync is an Android video player that turns the soundtrack of the
video you are watching into synchronised vibration, in real time and entirely on the phone.
Engines rumble and kick at every gear change, explosions hit and roll away, kick drums thump,
and dialogue stays quiet.

It analyses the audio that the player itself decodes, so it never uses the microphone or captures
other apps' audio. The only permission it asks for is vibration.

---

## Features

**Player**
- Pick videos with the system Photo Picker, or browse any document provider with the Storage
  Access Framework. You can also use *Open with HapticSync* from a file manager. Several files
  can be picked at once and play as a playlist.
- Play/pause, seek, ±10 s, previous/next, speed 0.5–2×, volume and mute.
- Seeking also works in files whose container has no seek index: fragmented MP4 (screen
  recordings, some downloads and camera apps) and Matroska/WebM without cues. ExoPlayer
  normally restarts such files from the beginning on every seek; HapticSync builds the missing
  index when the file opens. When a file really cannot be seeked (a live stream, for example),
  the timeline is locked and says why.
- Full-screen and rotation without interrupting playback.
- Recently played list with thumbnails, resume position and progress.
- **Prepare, then play** (on by default): the soundtrack is analysed ahead of playback, and
  playback waits until the first 15 seconds are ready. On a recent phone this takes a few
  seconds at most. After a seek
  into audio that is not prepared yet, playback pauses briefly while it is prepared. A progress
  card shows what is happening, and *Play now* skips the wait.
- Haptics start and stop with playback and resynchronise after every seek. Nothing vibrates
  after pause, stop, the end of the video or leaving the app.
- Live visualisation of the analysis:
  - spectrum, six band levels and an amplitude timeline;
  - markers for detected events;
  - a transient indicator and the commanded haptic intensity;
  - a chip naming the sound currently detected and a live indicator.

**Audio analysis** (on the decoded PCM, any sample rate, mono to 7.1)
- Overlapping 16–64 ms analysis windows (32 ms / 10 ms hop by default), on a real FFT and a
  six-band IIR filter bank. Default bands:

  | Band | Range |
  |---|---|
  | Sub-bass | 20–60 Hz |
  | Bass | 60–150 Hz |
  | Low-mid | 150–400 Hz |
  | Mid | 400–2000 Hz |
  | High-mid | 2–6 kHz |
  | High | 6–12 kHz |

  Every band's range can be changed.
- Features: RMS, peak, band energies, spectral centroid, spectral flux, zero-crossing rate,
  onset strength, attack and decay, dominant band, low-frequency change, transient intensity.
  Pitch tracking (harmonic product spectrum on decimated audio) feeds engine RPM and gear-shift
  detection.
- 27 sound categories in five families:
  - **Vehicles:** engine idle, revving, acceleration, deceleration, gear shifts, pass-bys,
    engine rumble.
  - **Drums & music:** bass drums, snares, cymbals, percussion, beats, bass drops, sustained
    bass.
  - **Cinematic:** explosions, gunshots, punches, impacts, thunder, rumble.
  - **Environment:** footsteps, door slams, machinery, rain, wind.
  - **Background:** dialogue, general audio.
- Classification uses DSP evidence scores with fuzzy logic. A hook is provided for an optional
  on-device ML model that adds semantic evidence (see [Limitations](#limitations)).

**Haptic engine**
- Ten adjustable parameters:
  - master intensity
  - pulse duration
  - pulse spacing
  - repetition
  - envelope shape
  - attack
  - release
  - event sensitivity
  - minimum amplitude
  - maximum intensity

  Separate layers handle sudden sounds (pulses) and sustained sounds (texture). Fatigue
  protection and speed-aware durations are also available.
- Device-adaptive rendering. Each phone gets the best strategy it supports:
  1. Android 16 amplitude/frequency envelopes;
  2. composition primitives;
  3. amplitude waveforms;
  4. predefined effects;
  5. on/off duty-cycle patterns.

**Haptic Studio**
- Every feel parameter can be tuned live while a video plays.
- Each of the six bands has an enable switch, a draggable frequency range (log scale),
  sensitivity, intensity, threshold, pulse strength and smoothing.
- Each sound category has an enable switch, sensitivity, intensity, duration, priority and a
  *Feel it* test button.
- Analysis settings: frequency range, window, hop, decision time, loudness adaptation, noise
  gate and quality.
- **Isolation mode** lets you feel only the sounds you choose. Select any number of categories
  (e.g. gear shifts and bass drums), bands (e.g. sub-bass) and groups (engine, drums, sudden
  impacts, low-frequency sounds) in any combination.

**Calibration**
- Capability report covering:
  - vibrator presence and count;
  - amplitude control;
  - supported primitives and their durations;
  - predefined effects;
  - Android 16 envelope limits and frequency range;
  - resonant frequency and Q factor;
  - the rendering strategy in use.
- Seven test patterns: soft, medium, strong, short impact, long rumble, rapid pulses and smooth
  increase. They are rendered through the same pipeline as playback.
- Per-phone strength settings: master intensity, response curve and weakest perceptible
  vibration.
- Motor latency compensation, a sync offset (negative = earlier) and a **sync test**: a looping
  click track with a flash, so you can line up sound, picture and vibration.
- Rendering preferences and the vibration usage class (media, physical emulation or touch).
- Calibration shows the timing of both rendering paths: live dispatch error and the hand-over
  error of prepared windows.
- Calibration is saved per device model.

**Presets**
- Built-in presets with distinct processing values: Cinematic, Racing, Music, Action, Minimal
  and Custom (your latest Studio tuning).
- Save, apply, rename, duplicate and delete your own presets.

**Demo mode**
- Twelve procedurally synthesised scenes:
  - race car on-board
  - trackside pass-bys
  - drum groove
  - bass drop
  - explosions
  - firefight
  - footsteps
  - dialogue
  - thunderstorm
  - workshop
  - trailer mix
  - sync clicks
- The scenes play through the normal player. What you feel comes from live analysis of the
  scene's audio, not from a script.
- You can also play any audio file of your own.

---

## How it works

HapticSync has two ways of turning sound into vibration. **Prepared haptics** is the default;
**live analysis** is used when preparing is turned off in Settings or the audio cannot be
decoded ahead of playback.

### Prepared haptics (default)

```
                ┌─► MediaAudioDecoder (Media3 extractors + MediaCodec, ahead of playback)
 media file ────┤        │ PCM on the media timeline
                │        ▼
                │   HapticTrackBuilder: full analysis · salience filter · masking · density cap
                │        │ events + texture levels, 10 ms resolution
                │        ▼
                │   HapticTrack (prepared range grows far ahead of playback)
                │        │
                │        ▼
                │   TrackPlayer: renders the next 0.5–1.5 s as one amplitude waveform ──► Vibrator
                │        ▲ media clock (sink position ↔ media time)
                └─► ExoPlayer ──► PcmTapAudioSink ──► AudioTrack ──► speaker
```

1. **Decode ahead.** `MediaAudioDecoder` decodes the soundtrack with the player's own extractors,
   so every sample has exactly the timestamp ExoPlayer plays it at. It starts at the playback
   position and runs ahead of it.
2. **Analyse with hindsight.** The same analyser as the live path runs over the audio, but the
   builder can look 4 s into the future before it keeps an event:
   - **salience:** an event far quieter than the programme around it (8–22 dB below it,
     depending on event sensitivity) is background clutter and is dropped; unclassified
     "general" sounds must be clearly prominent;
   - **masking:** a weaker hit within 120 ms of a much stronger one is dropped;
   - **density cap:** at most 12 events in any second.

   This removes most of the "irrelevant" vibrations that live analysis has to guess about.
3. **Store.** Events and texture levels go into a `HapticTrack`. Only *detection* settings
   (bands, sensitivities, analysis) are baked in. Changing them re-prepares in the background
   from the current position and switches over once ready. Output settings (intensity, envelope,
   isolation, calibration) are applied at playback time, so they take effect at once.
4. **Play.** `TrackPlayer` renders the next 0.5–1.5 s of the track at once (transients and
   texture mixed on a 5 ms grid), calibrates it and issues it as a single amplitude waveform.
   Each window starts at `content time + sync offset − motor latency` on the media clock, and
   the next window is issued exactly when the previous one ends, at the quietest moment
   available, so the hand-over cannot be felt. The motor gets about one command per second
   instead of one per event, and each transient lands exactly where the analysis put it.
5. **Timeline.** `PcmTapAudioSink` reports how the sink's clock maps to media time
   (`sink time = media time + offset`), including the encoder delay that Media3 trims at the
   start of a stream but not after a seek. Without this, AAC haptics ran about 45 ms late.

### Live analysis

```
 ExoPlayer ──► audio decoder ──► PcmTapAudioSink ──┬──► AudioTrack ──► speaker   (audio unchanged)
                                                    │
                                                    └──► PcmIngest  (lock-free ring + PTS anchors)
                                                              │  analysis thread
                                                              ▼
                     AudioAnalyzer: FFT · band filter bank · onsets · pitch/RPM · rhythm · speech
                                                              │  SoundEvents + texture levels
                                                              ▼
                     HapticMapper ──► HapticScheduler (media clock) ──► HapticRenderer ──► Vibrator
```

1. **Tap.** `PcmTapAudioSink` wraps Media3's audio sink. It sees every decoded buffer with its
   exact presentation timestamp, before the speed-change processor, so analysis always follows
   the media timeline. The audio itself passes through untouched.
2. **Look-ahead.** The sink buffers about 250 ms of audio ahead of the speaker, so the analysis
   runs before the sound is heard. Within that margin the classifier observes each onset for up
   to the *decision time* (90 ms by default) before deciding what it is. That margin is why the
   vibration can land with the sound instead of after it.
3. **Clock.** The scheduler uses the sink's own output position as its media clock
   (latency-corrected by Media3 and extrapolated between reports). Each effect fires at its
   media timestamp + sync offset − motor latency.
4. **Cancellation.**
   - Seeks, flushes and track changes start a new epoch; queued effects from older epochs are
     discarded.
   - Pause, buffering, the end of the stream and leaving the app cancel the vibrator.
   - Transients more than 40 ms late are dropped rather than played out of sync.
5. **Rendering.** Effects are device-independent curves until the last step, where
   `HapticRenderer` encodes them for what the phone supports.

### About Apple's music haptics

Apple has not published the algorithm behind Music Haptics, so HapticSync does not copy it. It
follows the same publicly described idea: a haptic track with transient and continuous
intensity, computed from the audio before it is played and then played in sync with it.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the threading model, timing and
classification details.

## Project structure

| Module | Contents |
|---|---|
| `core:dsp` | FFT, windows, biquads, band filter bank, PCM conversion, pitch tracker, resampler, demo-scene synthesis, WAV writer |
| `core:model` | Bands, categories, haptic parameters, isolation, analysis settings, presets, calibration and app settings (serialisable) |
| `core:analysis` | Feature extraction, onset detection, engine/rhythm/speech/thunder/texture detectors, event classifier, semantic-classifier hook |
| `core:haptics` | Capability model, audio-to-haptic mapper, renderer strategies, timestamp scheduler, calibration patterns |
| `core:engine` | Real-time orchestration: PCM ingest, analysis and scheduler threads, playback clock, visualisation buffers, prepared haptic tracks (builder, store, windowed player) |
| `platform:playback` | Media3 ExoPlayer integration, the PCM tap, the ahead-of-playback decoder and track preparer, and seek indexing for unindexed containers |
| `platform:vibration` | `Vibrator`/`VibratorManager` backend and capability probe |
| `app` | Jetpack Compose UI (Material 3), view models, DataStore persistence, navigation |

The `core` modules are plain Kotlin/JVM. They have no Android dependency and are covered by
fast JVM tests, which run synthesised scenes through the full analysis and haptic pipeline
against a simulated Media3 sink.

## Building

**Requirements**
- JDK 17.
- Android SDK with platform 36. Android Studio Narwhal (2025.1) or newer, or the command-line
  tools.

The Gradle wrapper downloads Gradle 8.14.3. AGP 8.11 and the other dependencies come from
Google Maven and Maven Central.

Build and install the debug app on a connected phone:

```bash
./gradlew installDebug
```

Run the engine tests (JVM only, no device needed):

```bash
./gradlew coreTest
```

Run the Android unit tests and lint:

```bash
./gradlew testDebugUnitTest lintDebug
```

Run the instrumented tests on a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

Build the release APK and the Play bundle:

```bash
./gradlew assembleRelease bundleRelease
```

The outputs are written to:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

### Release signing

Signing keys are never committed (`*.jks`, `*.keystore` and `keystore.properties` are
git-ignored). Create a key once:

```bash
keytool -genkeypair -v -keystore hapticsync-release.jks -alias hapticsync -keyalg RSA -keysize 4096 -validity 10000
```

Then either create `keystore.properties` in the repository root:

```properties
storeFile=hapticsync-release.jks
storePassword=…
keyAlias=hapticsync
keyPassword=…
```

…or set the environment variables `HAPTICSYNC_KEYSTORE_PATH`, `HAPTICSYNC_KEYSTORE_PASSWORD`,
`HAPTICSYNC_KEY_ALIAS` and `HAPTICSYNC_KEY_PASSWORD`. Without a release key, the release build
is signed with the local debug key so that it can still be installed for testing. Such a build
must not be published.

### Continuous integration

`.github/workflows/android.yml` runs on every push:
- the JVM engine tests;
- the Android unit tests and lint;
- the debug and release builds, the AAB and the instrumented-test APK.

The APKs and the bundle are attached to each run as the **hapticsync-builds** artifact, and
test and lint reports as **hapticsync-reports**.

To have CI sign releases with your key, add these repository secrets:
- `HAPTICSYNC_KEYSTORE_BASE64` (the base64-encoded `.jks`)
- `HAPTICSYNC_KEYSTORE_PASSWORD`
- `HAPTICSYNC_KEY_ALIAS`
- `HAPTICSYNC_KEY_PASSWORD`

The emulator job (instrumented tests) runs when the workflow is started manually with
*instrumented* checked.

## Using HapticSync

1. **Calibrate once.**
   - Open *Calibration*, play *Soft*, and adjust *Weakest vibration* until you can just feel
     it.
   - Run the *synchronisation test* and move the offset until the click, the flash and the tap
     coincide.
   - Tap *Save calibration*.
2. **Pick a preset.** For example, *Racing* for motorsport, *Music* for concerts, *Cinematic*
   for films or *Minimal* for subtle feedback.
3. **Play a video** and use the haptic bar under it:
   - intensity;
   - *Isolate a sound*, to feel only the engine or only the sub-bass;
   - *Haptic Studio*. Playback continues while you tune, so you feel each change immediately.

**Phones with strong linear motors** (Galaxy S25 Ultra and similar flagships): these motors
start and stop quickly and follow amplitude closely, which suits prepared haptics well. Quiet
background texture below a small threshold is not sent to the motor, and texture is ducked
under transients, so only meaningful sounds are felt. Prepared windows are issued as amplitude
waveforms on a 5 ms grid. If clicks still feel late in the synchronisation test, increase
*Motor latency* in Calibration a few milliseconds at a time.

**If vibration feels weak or absent:** Android applies its own settings on top of the app.
Check that *media vibration* is on (Settings › Sound & vibration › Vibration & haptics). Some
phones also reduce vibration in battery-saver mode. As an alternative, try the *Touch feedback*
usage class in Calibration.

## Limitations

- **Limited testing on physical phones.** All Android code is compiled, linted and tested on CI,
  including emulator tests that check that the ahead-of-playback decoder and the playing sink
  agree on the timeline within 3 ms (before and after seeks) and that fragmented MP4 without an
  index can be seeked. The fixes in this version follow a test report from a Galaxy S25 Ultra
  but have not been re-checked on that phone. The analysis and haptic pipeline is verified end
  to end on the JVM with synthesised scenes:
  - gear shifts within about 40 ms;
  - drum hits with under 2 ms mean dispatch error;
  - sync clicks within ±4 ms at 16–96 kHz.

  How effects *feel* depends on each phone's motor, so expect to use Calibration and Haptic
  Studio.
- **Categories describe acoustic character, not certainty.** DSP features detect sounds that
  behave like an engine, a kick drum or an explosion. Spectral analysis alone cannot know what
  produced a sound, so similar sounds can be confused (a slammed door and a punch, for
  example). The engine exposes a `SemanticClassifier` hook through which an on-device audio
  model (e.g. a YAMNet-style AudioSet classifier run with LiteRT/TensorFlow Lite) can nudge
  these decisions; `SemanticLabelMap` already maps AudioSet labels to HapticSync categories.
  No ML runtime or model is bundled, and the app works fully without one. (The only native
  code in the APK is two small AndroidX libraries, from Compose and DataStore; both are 16 KB
  page-aligned, as Google Play requires.) To add one,
  implement `SemanticClassifier` and assign it to `HapticSyncEngine.semanticClassifier`; the
  engine runs it off the audio threads about twice per second.
- **A vibration motor is not a speaker.** Phone actuators resonate around 150–250 Hz and cannot
  reproduce audio frequencies. HapticSync maps the energy and dynamics of each band onto
  patterns the motor can play.
- **Encoded passthrough and offload.** If audio is sent encoded to an external receiver, the app
  never sees decoded PCM. In that case it shows a notice and plays without haptics. Headphones
  and Bluetooth work normally.
- **No background playback.** By design, playback and vibration stop when you leave the app.
- **Preparing needs a readable file.** Files from the pickers and file managers work. If the
  audio cannot be decoded ahead of playback (an unusual codec, for example), haptics fall back
  to live analysis and the player says so.
- **Seek indexing covers fragmented MP4 and Matroska/WebM.** Other files without an index are
  seeked by constant bitrate where possible. Anything else is played without seeking, and the
  player says so.
- **Local media only.** DRM-protected streaming is out of scope.

## Privacy

HapticSync is fully offline:
- no network permission and no analytics;
- no microphone, playback capture or accessibility services;
- media access is granted file by file through the system pickers.

Settings, presets, calibration and the recently played list are stored only on the device.
