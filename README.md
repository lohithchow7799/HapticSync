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
- Full-screen and rotation without interrupting playback.
- Recently played list with thumbnails, resume position and progress.
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
- **Isolation mode** lets you feel only one component: a category (e.g. gear shifts), a band
  (e.g. sub-bass) or a group (engine, drums, sudden impacts, low-frequency sounds).

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

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the threading model, timing and
classification details.

## Project structure

| Module | Contents |
|---|---|
| `core:dsp` | FFT, windows, biquads, band filter bank, PCM conversion, pitch tracker, resampler, demo-scene synthesis, WAV writer |
| `core:model` | Bands, categories, haptic parameters, isolation, analysis settings, presets, calibration and app settings (serialisable) |
| `core:analysis` | Feature extraction, onset detection, engine/rhythm/speech/thunder/texture detectors, event classifier, semantic-classifier hook |
| `core:haptics` | Capability model, audio-to-haptic mapper, renderer strategies, timestamp scheduler, calibration patterns |
| `core:engine` | Real-time orchestration: PCM ingest, analysis and scheduler threads, playback clock, visualisation buffers |
| `platform:playback` | Media3 ExoPlayer integration and the PCM tap |
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

**If vibration feels weak or absent:** Android applies its own settings on top of the app.
Check that *media vibration* is on (Settings › Sound & vibration › Vibration & haptics). Some
phones also reduce vibration in battery-saver mode. As an alternative, try the *Touch feedback*
usage class in Calibration.

## Limitations

- **Not validated on physical phones in this build.** All Android code is compiled, linted and
  tested on CI. The analysis and haptic pipeline is verified end to end on the JVM with
  synthesised scenes:
  - gear shifts within about 40 ms;
  - drum hits with under 2 ms mean dispatch error;
  - sync clicks within ±4 ms at 16–96 kHz.

  How effects *feel* depends on each phone's motor, so expect to use Calibration and Haptic
  Studio.
- **Categories describe acoustic character, not certainty.** DSP features detect sounds that
  behave like an engine, a kick drum or an explosion. Spectral analysis alone cannot know what
  produced a sound, so similar sounds can be confused (a slammed door and a punch, for
  example). The engine exposes a `SemanticClassifier` hook through which an on-device audio
  model (e.g. a YAMNet-style AudioSet classifier) can nudge these decisions. No model is
  bundled, and the app works fully without one.
- **A vibration motor is not a speaker.** Phone actuators resonate around 150–250 Hz and cannot
  reproduce audio frequencies. HapticSync maps the energy and dynamics of each band onto
  patterns the motor can play.
- **Encoded passthrough and offload.** If audio is sent encoded to an external receiver, the app
  never sees decoded PCM. In that case it shows a notice and plays without haptics. Headphones
  and Bluetooth work normally.
- **No background playback.** By design, playback and vibration stop when you leave the app.
- **Local media only.** DRM-protected streaming is out of scope.

## Privacy

HapticSync is fully offline:
- no network permission and no analytics;
- no microphone, playback capture or accessibility services;
- media access is granted file by file through the system pickers.

Settings, presets, calibration and the recently played list are stored only on the device.
