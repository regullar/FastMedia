# FastMedia

A small Windows media library: read the current track from the System Media
Transport Controls, control playback, get a **real audio waveform** from the
system audio mix, and fetch **lyrics** — all behind one class, `MediaSession`.

The HTTP/JSON for lyrics and the PCM audio capture live entirely in the native
DLL (`FastMedia.dll`); the Java side is a thin, allocation-light facade over JNA.

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

    // Real audio-reactive waveform (WASAPI loopback, starts on first use)
    float[] wave = new float[256];
    int n = session.sampleWaveform(wave);            // normalised peaks 0..1
    float level = session.audioLevel();              // smoothed loudness 0..1

    // Lyrics (cached, deduplicated; never blocks the caller)
    session.requestLyrics("Rick Astley", "Never Gonna Give You Up")
           .thenAccept(lyrics -> {
               if (lyrics.kind() == Lyrics.Kind.SYNCED) {
                   int active = lyrics.activeLineIndex(session.position().positionMs());
               }
           });
}
```

That is the whole API surface. The session polls the system on its own daemon
threads with adaptive intervals (faster while playing, slower when idle) and
pushes changes to your `MediaListener` — you never write polling loops.

## Layout

- `kz.regullar.fastmedia` — public API: `MediaSession`, `NowPlaying`,
  `PlaybackPosition`, `MediaListener`, `Lyrics`, `LyricLine`.
- `kz.regullar.fastmedia.internal` — JNA bindings, struct mappings, LRC parser.
- `assets/FastMedia.dll` — native backend (must be on the classpath).

## Requirements

- Windows 10/11, 64-bit JVM.
- JNA on the classpath (`net.java.dev.jna:jna`).

## Building the native DLL

```
cmake -S cpp -B cpp/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build cpp/build
```

Then copy `FastMedia.dll` to `java/resource/assets/FastMedia.dll`.
