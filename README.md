good-echo
=========

Time travelling recorder for Android. Always records in the background; go back in time and save any moment you missed.

Free/libre software under the GPL v3.

A fork of [Echo](https://github.com/mafik/echo) by Marek Rogalski (mafik), with Opus encoding, compressed in-memory history, and UI refinements.

Download
---

* [F-Droid](https://f-droid.org/repository/browse/?fdid=com.goodecho.app)

Features
---

- **Always-listening** — a foreground microphone service continuously records audio, keeping a rolling history in memory
- **Go back in time** — use the scroll wheel to pick any moment within the buffered history and save it
- **Compressed memory** — audio is encoded to Opus (~60 B per 20 ms frame) before storage, giving you hours of history even on modest hardware
- **Clean save** — saved files are `.opus` Ogg containers, no re-encoding needed (frames are written directly to the file)
- **Configurable memory** — set how much RAM to reserve for audio history in Settings
- **No ads, no tracking, no Google Play** — fully free software, ready for F-Droid distribution

Architecture
---

**SaidItFragment** — the main view. Contains the scroll-wheel time picker, listen/stop/save buttons, and the memory-limit indicator. Displays the buffered duration and maximum capacity.

**SaidItService** — foreground service managing the audio pipeline. Runs a dedicated thread that reads PCM from `AudioRecord`, encodes it to Opus frames, stores them in the ring buffer, and writes them to the active save file if recording. Accessible via `Handler` (tasks sent to `audioHandler`).

**OpusRingBuffer** — pre-allocated circular array of `byte[]` frames. `allocate(capacity)` computes the number of frames from a byte budget using `AVG_FRAME_BUDGET = 92` (60 B compressed frame + 32 B overhead). `store()` adds a compressed frame, `read(skipFrames, consumer)` passes frames sequentially to an `OpusFileWriter`.

**OpusFileWriter** — combines a Concentus `OpusEncoder` with Ogg container logic (page headers, CRC32, EOS). `writeFrame()` accepts pre-compressed Opus packets and wraps them in Ogg pages. On save, frames from `OpusRingBuffer` are fed directly to `writeFrame()` — zero re-encoding.

**SettingsActivity** — memory configuration via an EditText (value in MB, percentage shown relative to available heap). No quality selector (always 48 kHz).

Differences from Echo
---

| Area | Original Echo | good-echo |
|------|---------------|-----------|
| Codec | WAV (uncompressed PCM) | Opus in Ogg container |
| Memory storage | Raw PCM `short[]` chunks | Compressed `byte[]` Opus frames (~60 B/frame) |
| Save path | Re-encodes from PCM to WAV | Writes compressed frames directly |
| Quality selector | 8/16/48 kHz radio buttons | Always 48 kHz (Opus handles quality internally) |
| Memory control | SeekBar (0–100 %) | EditText (MB input, shows percentage) |
| Time display | mm:ss | dd:hh:mm:ss (days/hours for long sessions) |
| Package | `eu.mrogalski.saidit` | `com.goodecho.app` |
| Billing | Google Play in-app purchases | Removed entirely |
| Distributions | Google Play | F-Droid |

Building
---

Requires JDK 17 and the Android SDK.

```sh
export ANDROID_HOME=/path/to/sdk
./gradlew assembleDebug
```

The APK will be at `SaidIt/build/outputs/apk/debug/SaidIt-debug.apk`.

Disclaimer
----------

Recording laws vary by jurisdiction. You are solely responsible for
ensuring that your use of this app complies with all applicable laws
in your area. The developers and contributors assume no liability for
any misuse.

License
-------

Copyright 2014 Marek Rogalski
Copyright 2026 good-echo contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
