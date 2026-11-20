# FastMedia

A small Windows media library: read the current track from the System Media
Transport Controls, control playback, get a **real audio waveform** from the
player's own audio stream, and fetch **lyrics** — all behind one class, `MediaSession`.

The Windows SMTC integration and PCM audio capture live in the native
DLL (`FastMedia.dll`); lyrics retrieval, LRC parsing, and media events live on the Java side.

## Quick start

```java
try (MediaSession session = MediaSession.open()) {
    session.addListener(new MediaListener() {
        @Override public void onTrackChanged(NowPlaying np) {
            System.out.println(np.title() + " — " + np.artist());
            byte[] cover = np.albumArt();           // PNG/JPEG bytes, or null
        }
        @Override public void onPositionChanged(PlaybackPosition pos) {
            float progress = pos.progress();         // 0..1
        }
        @Override public void onPlaybackStopped() { }
    });

    // Transport
    session.togglePlayPause();
    session.skipNext();
    session.seek(Duration.ofSeconds(30));

    // Real audio-reactive waveform (WASAPI loopback, starts on first use).
    // By default it captures only the app that owns the current media session,
    // so notifications and other players stay out of the waveform.
    float[] wave = new float[256];
    int n = session.sampleWaveform(wave);            // normalised peaks 0..1
    float level = session.audioLevel();              // smoothed loudness 0..1

    // Pick a different source explicitly, or go back to following the player
    for (AudioSource src : session.audioSources()) {
        System.out.printf("%d %s active=%s nowPlaying=%s%n",
                src.pid(), src.name(), src.active(), src.nowPlaying());
    }
    session.setWaveformSource(1234);                 // capture one process only
    session.setWaveformSourceAuto();                 // follow the player again
    int captured = session.waveformSourcePid();      // 0 = whole system mix

    // Lyrics (cached, deduplicated; never blocks the caller)
    session.requestLyrics("Rick Astley", "Never Gonna Give You Up")
           .thenAccept(lyrics -> {
               if (lyrics.kind() == Lyrics.Kind.SYNCED) {
                   int active = lyrics.activeLineIndex(session.position().positionMs());
               }
           });
}
```

That is the whole API surface. The session subscribes to SMTC push events,
interpolates the position locally, and pushes changes to your `MediaListener`
on one ordered callback thread — you never write polling loops. If Windows
refuses the event subscription it falls back to polling once a second.

- `nowPlaying()` and `position()` never block: they return the last known
  state (`null` when nothing is playing). `open()` fetches the current track
  before returning, and `addListener` replays it to the new listener.
- A transient SMTC hiccup (a player slow to answer) keeps the last state; only
  a real "no media session" produces `onPlaybackStopped`.
- Several `MediaSession`s may be open at once; they share one native backend,
  which is released when the last one closes. The waveform source is
  process-wide.

## Waveform source

`sampleWaveform` and `audioLevel` read a WASAPI loopback capture. Which stream
it taps is controlled by `setWaveformSource`:

- **Auto (default)** — the native side matches the SMTC session's
  `SourceAppUserModelId` against the processes that own an audio session, and
  re-targets whenever the media session changes. Windows 10 20H1+ exposes this
  as *process loopback*, so only that app (and its child processes) reaches the
  waveform.
- **Explicit PID** — `setWaveformSource(pid)` pins the capture to one process,
  whether or not it is the current player. `audioSources()` lists the
  candidates with their peak level and flags the one matching the media session.

Per-process capture is not always granted: protected processes, processes
running at a higher integrity level than the JVM, and Windows versions older
than 20H1 all refuse it. In those cases the capture silently falls back to the
whole system mix — the previous behaviour — and `waveformSourcePid()` returns
`0` so you can tell which one you got.

## Layout

- `kz.regullar.fastmedia` — public API: `MediaSession`, `NowPlaying`,
  `PlaybackPosition`, `AudioSource`, `MediaListener`, `Lyrics`, `LyricLine`.
- `kz.regullar.fastmedia.internal` — JNA bindings, struct mappings, LRC parser.
- `resource/FastMedia.dll` — native backend (must be on the classpath root).
- `cpp/FastMedia.cpp` — the only native source.

## Requirements

- Windows 10/11, 64-bit JVM. Per-process waveform capture needs Windows 10
  20H1 or newer; older builds fall back to the system mix.
- JNA on the classpath (`net.java.dev.jna:jna`).

## Building the native DLL

```
cmake -S cpp -B cpp/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build cpp/build
```

Needs MSVC with C++20 (C++/WinRT coroutines). The post-build step copies
`FastMedia.dll` into `resource/` and `native/`.
