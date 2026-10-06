# BLE_2 microphone flow — questions before any Android code change

This file is **questions only**. No app code was changed.

Target: `android_v1` talking to `ble_2` (nRF54L15, advertise name `Tag_PDM`, Nordic UART Service).

---

## Already determined (do not re-answer these)

These are facts from `android_v1` and `ble_2` source. Questions below skip them.

### Android app (`C:\nordic\v\android_v1`)

- XML View Binding (not Compose). Main screens: `MainActivity` → scan `ScannerActivity` → pet pick `PetAssignHelper` → camera `DeviceActivity`.
- Home CTA when connected is **Start recording** (`R.string.home_cta_record`). That currently opens `DeviceActivity` (Camera2 + `MediaRecorder` MP4 + BLE sensor capture).
- Record control is the red `recordBtnContainer` on `activity_device.xml` (same visual language you want on the mic page).
- BLE is Nordic `BleManager` (`TagBleManager.kt`): requires GATT service `TAG_STREAM` `7f5e0a10-…`, notify `SENSOR_DATA` `7f5e0a11-…`, write `COMMAND` `7f5e0a12-…`.
- Commands today are **binary**: START `0x01` + int64 LE unix ms, STOP `0x02` (`TagCommand.kt`). Incoming notify is parsed as **sensor** packets (`SensorPacketParser`, start byte `0xA1`).
- Scan shows names `Tag` / `Tag_*` (`looksLikeTagName`). `Tag_PDM` **would appear** in the list.
- Connect **will fail** on `ble_2`: `isRequiredServiceSupported()` returns false without `TAG_STREAM` (“Not a Tag device (TAG_STREAM missing)”).
- MTU 247 is already requested (matches `ble_2` `CONFIG_BT_L2CAP_TX_MTU=247`).
- Sessions save under `files/sessions/SESSION-yyyyMMdd-HHmmss-xxxx/` as `.xlsx` + `.mp4` + `.log` + `manifest.json`. History + cloud upload (`mp4`/`xlsx`/`log`/`json`/`csv`/`txt`). Gallery publish is **video** (`Movies/Tag`).
- There is **no** NUS client, **no** WAV writer, **no** waveform view, **no** gain UI.
- Mid-recording BLE drop: no reconnect; `DeviceActivity` auto-saves `SESSION_LOSS`. Idle drops attempt reconnect (6 tries).
- `StorageGate` blocks start if free space &lt; ~200 MB (written for video).
- `UiFeatureConfig.kt` can hide camera/scan/history/etc. without deleting screens.
- Login + at least one pet profile are required before Home.

### Tag firmware (`C:\nordic\audio\ble_2`) — contract as implemented today

- NUS UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (RX write `…0002`, TX notify `…0003`). Advertises NUS in scan response.
- Commands are **ASCII** on NUS RX (`START`, `STOP`, `GAIN_SET 2`, …). Aliases `0`/`1` = START/STOP. Replies are ASCII lines on the **same** NUS TX used for audio (`OK START\n`, `GAIN 2 MIN -20 MAX 20 DEF 2 Q8 322\n`, `ERR …\n`, `PONG\n`).
- Gain on Tag is **integer dB only** (`parse_db` rejects `19.5`). Absolute limits **−20…+20**. Runtime rule `GAIN_MIN ≤ GAIN_SET ≤ GAIN_MAX`. Defaults: MIN −20, MAX 20, current/default **+2**. Not stored in flash (lost on Tag reset/power cycle). `Q8` is internal PCM multiply, not a UI unit.
- **Logical audio chunk:** 80 samples, 16-bit signed LE mono, 160 PCM bytes, 10 ms at 8 kHz.
- **Every NUS audio notify** (`include/audio/audio_packet.h`):

```text
offset 0  uint32 LE  packet_id
offset 4  uint32 LE  sample_id      // first sample of the 80-sample chunk
offset 8  uint8      sample_offset  // 0..79 in this notify
offset 9  uint8      sample_count   // 1..80 in this notify
offset 10 int16 LE   pcm[sample_count]
length = 10 + 2*sample_count
```

  If ATT payload ≥ 170: one notify (`offset=0`, `count=80`). If payload is 20 (default MTU 23): 5 samples/notify, 16 notifies per chunk, same `packet_id`/`sample_id`.
- Packet ID and Sample ID reset to **0** on `START`. Sample ID steps by **80** per logical chunk. `uint32` wrap.
- `STOP` may flush complete 80-sample chunks; partial &lt;80 samples are discarded.
- `AUDIO_CONFIG_GET` reports `PCM_HZ`, `BITS`, `CH`, `FRAME_MS`, `CHUNK_SAMPLES`, `CHUNK_PCM`, `HDR`, gain fields.

Your examples (−19.5 / 19.5 dB) **do not match** this firmware. That conflict is question 5.1.

---

## 1. Existing Android architecture

1.1 After Home **Start recording**, keep `PetAssignHelper` (pick pet / “is the pet wearing the tag?”) before the mic page, or go straight to the mic page?

1.2 Should the mic live page be a **new Activity** (recommended: `MicLiveActivity` launched instead of `DeviceActivity`), or a new layout **inside** `DeviceActivity` replacing the camera preview?

1.3 If the user is already on the camera `DeviceActivity` from an old build path, should that screen remain reachable at all for `ble_2` tags?

---

## 2. Existing BLE implementation

2.1 Confirm we should **replace** TAG_STREAM GATT with Nordic **NUS** (write UTF-8 commands on RX, listen on TX notify) for `ble_2`. Dual-stack (old sensor Tag **and** `ble_2`) is not in the current `TagBleManager`. Do you want **ble_2 only**, or keep TAG_STREAM for older collars in the same APK?

2.2 Scan currently also lists phones/laptops/TWS. For this mic product, should the list be **`Tag_PDM` / `Tag_*` only**?

2.3 Commands must become ASCII writes, not `TagCommand.startPayload(unixMs)`. `ble_2` START has **no timestamp**. Confirm we **drop phone→tag time sync** for audio sessions.

2.4 Control replies and PCM share NUS TX. Confirm the app should: enable notify → send `PING`/`GAIN_GET`/`AUDIO_CONFIG_GET` **before** `START`; while RECORDING treat notifies as **binary audio fragments** and ignore/defer ASCII (or parse ASCII only if length/header is not a valid audio fragment). Is that acceptable, or must firmware be changed so control never shares the audio notify (I will not change firmware unless you say so)?

---

## 3. BLE_2 compatibility

What **must** change in the Android project (not a question — listed so you can veto):

| Area | Today | Needed for `ble_2` |
| --- | --- | --- |
| Service UUID | `7f5e0a10-…` TAG_STREAM | NUS `6e400001-…` |
| Commands | binary `0x01`/`0x02` | ASCII `START`/`STOP`/… |
| Notify payload | sensor `0xA1` packets | 10-byte audio header + PCM |
| Device screen | camera + IMU/env Excel | mic live + WAV |
| `SensorPacketParser` | IMU/BME/TMP | unused for this stream |
| History files | `.xlsx` + `.mp4` | `.wav` (per your requirement) |
| Cloud upload extensions | no `wav` | add `wav` **if** cloud stays on |

3.1 Proceed with that GATT/command/parser swap for `ble_2` tags?

3.2 Advertise name is `Tag_PDM`. Any other names we must accept?

---

## 4. START/STOP behavior

4.1 Home **Start recording** should **only navigate** to the mic page (no BLE `START` yet). BLE `START` only when the user taps the **red record button** on the mic page. Confirm.

4.2 Wait for Tag `OK START` before showing “recording”, or start the waveform as soon as the command is written?

4.3 If `ERR START BLE` / `ERR START PDM` / write fails: stay on the mic page with a toast, or close the page?

4.4 STOP: wait for `OK STOP` then build WAV, or stop UI immediately and assemble WAV from whatever PCM was already buffered?

4.5 Back / Home while RECORDING: send `STOP` and save WAV, send `STOP` and **discard**, or block Back until Stop?

4.6 Leaving the mic page while IDLE: send `RESET`, `STOP`, or nothing?

4.7 Duplicate record tap while START in flight: ignore, or allow?

---

## 5. Gain UI

Firmware = **integers only**, step **1 dB**, range **−20…+20**, values come from `GAIN_GET` after connect.

Your prompt showed **0.5 dB** examples. That is the main unresolved gain spec.

5.1 **Precision / step:** follow firmware (**1 dB** integers), or do you want **0.5 dB** in the UI (which the Tag will **reject** until firmware is changed)?

5.2 **Controls:** three values MIN / MAX / DEFAULT at the top. Also show **current gain** (`GAIN_SET` / first number in `GAIN …`)? If yes: slider, number stepper, or both?

5.3 Control widget for MIN/MAX/DEFAULT: sliders, `NumberPicker`, or editable dB fields with ± buttons?

5.4 Changing MIN/MAX on the Tag can **clamp** current and default. After each `GAIN_*_SET`, should we always `GAIN_GET` and refresh all four numbers?

5.5 **Persistence:** Tag gain is RAM-only. After Tag reboot, firmware returns −20 / 20 / 2. Should the **app** remember last MIN/MAX/DEFAULT/current in SharedPreferences and push them after connect, or always display whatever the Tag reports (recommended)?

5.6 App process kill / phone reboot with Tag still in RAM: reconnect → `GAIN_GET` only (no local override)?

5.7 Show the unit as **“dB”** next to every number?

---

## 6. Gain synchronization

6.1 On connect (Home, still IDLE), send `GAIN_GET` + `AUDIO_CONFIG_GET` + `DEVICE_INFO_GET` immediately, or only when the mic page opens?

6.2 If `GAIN_GET` fails or notify is not enabled yet: retry how many times / how long? Fallback UI: hide controls vs show −20/20/2 as placeholder (placeholder can disagree with Tag — confirm you do **not** want hardcoded display)?

6.3 `GAIN_*_SET` then Tag `ERR … RANGE`: revert the widget to last Tag-confirmed value and toast?

6.4 Should `STATUS_GET` be polled, or only on connect / after START/STOP?

---

## 7. 5-second waveform

Your description is a **tumbling 5 s window** (0–5, then replace with 5–10), **not** a continuously sliding strip. 8 kHz × 5 s = **40 000 samples** per window.

7.1 Confirm **tumbling 5 s replace** (not a sliding window that crawls sample-by-sample).

7.2 Waveform style: simple polyline (oscilloscope), filled bars, or mirrored “voice memo” bars?

7.3 Amplitude: **raw int16** ±32768 full scale, or auto-gain the plot (can hide true level vs Tag gain)?

7.4 Draw every chunk (10 ms), ~3–5 Hz UI refresh, or once per 5 s window only? (Recommendation: ~10–20 Hz downsample of the 5 s buffer.)

7.5 X-axis: show `0–5 s` labels that **reset**, or show session time `5–10`, `10–15`?

7.6 No packets yet / silence: flat zero line, last window frozen, or empty placeholder?

7.7 On STOP: freeze last 5 s, or clear?

7.8 Live **speaker playback** of the mic stream, or waveform only?

---

## 8. Audio packet reconstruction

From `audio_packet.h` the layout is already defined (see **Already determined**). Remaining product questions:

8.1 Treat `audio_packet.h` as the **locked Android decoder contract** (recommended)?

8.2 Incomplete fragment set (got `packet_id=10` with only 40 of 80 samples): drop the chunk (gap in WAV), or insert zeros for missing offsets?

8.3 Missing `packet_id` (10 then 13): insert **160 samples of silence per missing chunk** in the WAV so Sample ID stays aligned, or concatenate without padding (timeline shortens)?

8.4 Duplicate complete `packet_id`: keep first, keep last, or ignore?

8.5 Out-of-order fragments/chunks: reorder by `packet_id` / `sample_offset` within a timeout, or discard late data? If timeout, how long (e.g. 100 ms / 500 ms)?

8.6 `sample_id` vs `packet_id` disagree (e.g. id 11 but sample_id 1040 instead of 880): trust Sample ID, Packet ID, or drop?

8.7 `AUDIO_CONFIG_GET` `PCM_HZ`/`BITS`/`CH`/`CHUNK_SAMPLES` vs header `HDR 10`: if Tag reports something other than 8000/16/1/80, follow the reply or refuse to record?

8.8 How to tell ASCII `OK START\n` from audio if both appear on NUS TX: valid audio = length ≥ 12, `sample_count` 1..80, `offset+count≤80`, length==10+2×count. Confirm that heuristic.

---

## 9. WAV file

Existing sessions use `SESSION-…` folders, unique names, History list, share buttons, cloud, Gallery for **mp4**. None of this is defined for WAV.

9.1 Keep `files/sessions/SESSION-…/` and put `SESSION-….wav` there (replace `.xlsx`/`.mp4` as the main artifact)?

9.2 WAV filename: same `SESSION-yyyyMMdd-HHmmss-xxxx.wav`, or include pet/device (`Rex_Tag_PDM_…wav`)?

9.3 Also copy WAV to public storage / system app (Music or Movies/`Tag`)? Today only **video** goes to Gallery.

9.4 History row: **Video** button should play/share WAV; **Data** (xlsx) hide or keep unused?

9.5 After Stop: same dialog as now (open History) plus share WAV?

9.6 Max duration? (`StorageGate` 200 MB ≈ many hours of 8 kHz mono; or cap e.g. 10 / 30 / 60 minutes?)

9.7 WAV header from `AUDIO_CONFIG_GET` (rate/bits/channels). Confirm 16-bit PCM WAV (not float).

9.8 Cloud: upload `.wav` to collar.justkodez.com, or **local-only** for this mic build?

9.9 `manifest.json`: keep pet/user fields, drop video width/fps, add audio rate/gain/packet stats?

---

## 10. Connection / disconnection / error handling

10.1 Disconnect **during RECORDING**: same as camera path (no reconnect, save WAV of received PCM, mark `SESSION_LOSS`), or discard?

10.2 Disconnect **on mic page IDLE**: reconnect like Home, or close the page?

10.3 `START` GATT write fail vs Tag `ERR START …`: same user message or distinguish?

10.4 `STOP` write fail but PCM already buffered: still write WAV?

10.5 Notify stream stalls (no fragment for N ms while RECORDING): toast, auto-STOP+save, or wait forever? What timeout?

10.6 Tag `ERR UNKNOWN` / `ERR … ARG`: toast the exact line?

10.7 Packet gaps: show a warning on the after-stop dialog (like current `qualityLabel` / missing %)?

---

## 11. Existing app features

Do **not** assume deletion. For `ble_2` mic product, keep / hide / disable each:

| Feature | Default I’d assume | Your choice |
| --- | --- | --- |
| Login / account | keep | keep / skip |
| Pet profiles + assign before record | keep | keep / skip |
| Home scan / disconnect | keep | |
| Camera `DeviceActivity` (preview, flash, switch cam, MP4) | hide via `UiFeatureConfig` | hide / keep / remove later |
| IMU/env Excel + `CustomDataActivity` | hide | hide / keep |
| `CameraConfigActivity` | hide | |
| History list | keep, WAV instead of video/xlsx | keep / hide |
| Cloud sync banner + History Sync | off if no wav upload | on / off |
| Logs / Settings / About | keep | |
| `UiFeatureConfig` as the kill-switch | yes | |

11.1 Fill keep/hide for the table (or say “hide all camera/sensor, keep login+pets+history+logs”).

11.2 Scanner listing non-Tag devices (phones/TWS): keep or Tag-only?

---

## 12. UI design

12.1 Mic page chrome: reuse camera overlay (primary bar, red record button, duration `00:00`) with waveform instead of `TextureView`, gain row in the top overlay?

12.2 Record button: same 80 dp red circle, label Start/Stop?

12.3 Gain row disabled look: grey sliders, or hide the controls while RECORDING?

12.4 Show live **duration** and/or **packet count / missing count**?

12.5 Dark full-screen (like camera) vs light Home theme?

12.6 Orientation: portrait lock like `DeviceActivity`?

---

## 13. Additional questions / recommendations

**R1 — New `MicLiveActivity`, do not overload camera `DeviceActivity`.**  
Camera/MediaRecorder and NUS audio are different pipelines. Keep `DeviceActivity` behind `UiFeatureConfig` until you drop it.  
→ Approve new activity launched from Home **Start recording** (after optional pet assign)?

**R2 — Tumbling 5 s plot, ring buffer of 40 000 samples.**  
Matches “replace the previous 5 seconds”. A sliding window is extra work and not what you described.  
→ Approve tumbling window?

**R3 — Decoder locked to `audio_packet.h`; WAV params from `AUDIO_CONFIG_GET` with fallback 8 kHz / 16-bit / mono / 80.**  
→ Approve?

**R4 — Integer 1 dB UI** until firmware supports tenths. Showing 19.5 would be a lie.  
→ Approve 1 dB, or will firmware be updated first?

**R5 — Current gain as a fourth control** (large slider), MIN/MAX/DEFAULT as compact limits. Limits only editable when IDLE; current gain also IDLE-only per your spec.  
→ Want the current-gain slider?

**R6 — Do not mix ASCII and PCM parsing by time only; use the 10-byte header validity check.** Still send config commands only while IDLE so `OK GAIN_SET` cannot land in the WAV.  
→ Approve?

**R7 — History: one WAV per session; hide Data/Video/Sync until you decide cloud.** Publish WAV to `Music/Tag` or share-sheet only.  
→ Music/Tag, share only, or session-folder only?

**R8 — Live earpiece/speaker monitor** is extra latency and AEC risk. Waveform-only for v1.  
→ Waveform only?

**R9 — `PING` after CCC enable** as a link check before enabling Start.  
→ Yes/no?

**R10 — Phone storage gate:** 200 MB is oversized for 8 kHz WAV (~16 kB/s → ~1 MB/min). Keep 200 MB or lower (e.g. 20 MB)?

**R11 — Anything else required on the mic page** (spectrum, gain dB readout of live RMS, keep-awake already used on camera via wake lock)?

Reply with numbered answers (1.1, 5.1, R1, …). After that, the app can be modified to match.

---

## Implementation decisions (applied in android_v1)

Unanswered questionnaire items were implemented using the R1–R11 recommendations as product decisions. Safest extra choices are listed here.

### Navigation and camera
- **1.1** Keep `PetAssignHelper` before the microphone page.
- **1.2** New `MicLiveActivity` + `activity_mic_live.xml` (camera `DeviceActivity` not rewritten).
- **1.3** Home **Start recording** launches `MicLiveActivity` only. Camera code is not deleted; it is simply not opened from Home.

### BLE
- **2.1 / R1** NUS-only required GATT (`6e400001` / RX `0002` / TX `0003`). TAG_STREAM is optional if present; ble_2 will not connect without NUS.
- **2.2** Existing scan filter (`Tag` / `Tag_*`) plus advertised NUS UUID. `Tag_PDM` already matches `Tag_*`.
- **2.3** ASCII `START` with no phone timestamp.
- **2.4 / R6** Audio vs ASCII: valid 10-byte header + `length == 10 + 2*sample_count`. Gain commands only while IDLE.

### START / STOP
- **4.1 / R9** Home navigates only. After NUS CCC: `PING`, then on the mic page `GAIN_GET` / `AUDIO_CONFIG_GET` / `DEVICE_INFO_GET`. User presses the red button to send `START`.
- **4.2** Wait for `OK START` (3 s timeout). Duplicate START while STARTING/RECORDING is ignored.
- **4.3** `ERR START`: stay on page, toast the ERR line, do not record.
- **4.4** Wait for `OK STOP` then write WAV; 3 s timeout still saves. STOP write failure still saves.
- **4.5** Back while recording: STOP + save.
- **4.6** Leave IDLE: no STOP.
- **10.1** Recording disconnect: save WAV as `SESSION_LOSS`, no reconnect (existing manager already skips reconnect while `RECEIVING`).
- **10.2** IDLE disconnect: existing reconnect, stay on page.
- **10.5** ~3 s audio stall: auto-stop + save.

### Gain
- **5.1 / R4** Integer 1 dB only, absolute −20…+20.
- **5.2 / R5** Current gain slider + MIN/MAX/DEFAULT steppers. Values shown from Tag `GAIN` replies (not hardcoded as Tag truth; UI shows “—” until `GAIN_GET` succeeds).
- **5.4** `GAIN_GET` after `OK GAIN_*`.
- **5.5 / 5.6** No SharedPreferences push of gain.
- Limits and current gain disabled while recording.

### Audio / waveform
- **R2 / 7** Tumbling 5 s window (40 000 samples at 8 kHz), polyline, raw int16 scale, ~400 draw bins, freeze on STOP (view not reset). No speaker monitor (**R8**).
- **8.1–8.6** Assembler: drop incomplete chunks (200 ms fragment timeout), no silence padding, keep-first duplicate `packet_id`, drop late IDs, drop `sample_id` mismatch, uint32 wrap via wrapping delta.
- **8.7** WAV uses Tag `AUDIO_CONFIG` when compatible (`8000/16/1/80/hdr 10`). GET failure → fallback 8 kHz/16-bit/mono/80. Incompatible Tag config: refuse START.
- Packet stats are written to the session log, not shown live on the waveform page.

### Storage
- **R7** Session folder `files/sessions/SESSION-…/SESSION-….wav` + log + `manifest.json` (`files.audio`). No Music/Tag gallery publish; share from History.
- History: hide Data/Sync when there is no xlsx; Video button shares WAV (`audio/wav`) for mic sessions.
- Mic sessions indexed `SyncStatus.LOCAL_ONLY` (WAV not uploaded). Camera 200 MB `StorageGate` unchanged; mic START uses a local 20 MB check (**R10**).
- Manifest keeps user/pet/device fields; adds audio filename when present.

### Other
- **R11** Keep-awake: `FLAG_KEEP_SCREEN_ON` + partial wake lock while recording.
- Login, pets, scanner, history, logs, settings, about unchanged aside from History WAV/share and Home navigation.

### Extra assumptions (not in the questionnaire)
- Audio notifications that arrive after `START` is sent but before `OK START` are still assembled (avoids dropping the first fragments). WAV is only kept if START succeeds or a later STOP/disconnect save runs.
- ASCII lines are split on `\n`. Firmware replies include newline.
- Gain MIN/MAX/DEFAULT steppers require a successful `GAIN` snapshot first; they are disabled until then.

