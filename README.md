# FastMedia

Java library for Windows media sessions: current track, playback control, a real audio waveform from the player's stream, and synced lyrics. Native part is a small C++ DLL (SMTC + WASAPI), the API is one class — `MediaSession`.

## Features

- Track info and cover art from System Media Transport Controls (Spotify, browsers, any SMTC player)
- Play / pause / next / previous / seek
- Push events instead of polling, with locally interpolated position
- Waveform and loudness via WASAPI loopback, captured per-process (only the active player)
- Lyrics from [lrclib.net](https://lrclib.net), plain and synced (LRC), cached

## Requirements

- Windows 10/11, 64-bit JVM
- [JNA](https://github.com/java-native-access/jna) 5.x (bundled in `libs/`)
- Per-process audio capture needs Windows 10 20H1+, otherwise falls back to the system mix

## Usage

```java
try (MediaSession session = MediaSession.open()) {
    session.addListener(new MediaListener() {
        @Override public void onTrackChanged(NowPlaying np) {
            System.out.println(np.title() + " — " + np.artist());
        }
        @Override public void onPositionChanged(PlaybackPosition pos) {
            float progress = pos.progress();
        }
        @Override public void onPlaybackStopped() { }
    });

    session.togglePlayPause();
    session.skipNext();
    session.seek(Duration.ofSeconds(30));

    float[] wave = new float[256];
    int n = session.sampleWaveform(wave);
    float level = session.audioLevel();

    session.requestLyrics("Rick Astley", "Never Gonna Give You Up")
           .thenAccept(lyrics -> {
               if (lyrics.kind() == Lyrics.Kind.SYNCED) {
                   int line = lyrics.activeLineIndex(session.position().positionMs());
               }
           });
}
```

`nowPlaying()` and `position()` never block and return the last known state (`null` if nothing is playing). Listener callbacks run on a single ordered thread.

### Waveform source

By default the capture follows the app that owns the current media session. To pick a process manually:

```java
for (AudioSource src : session.audioSources()) {
    System.out.println(src.pid() + " " + src.name() + " nowPlaying=" + src.nowPlaying());
}
session.setWaveformSource(pid);
session.setWaveformSourceAuto();
session.waveformSourcePid(); // 0 = system mix fallback
```

## Building

Native DLL (MSVC with C++20, run from a VS x64 developer prompt):

```
cmake -S cpp -B cpp/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build cpp/build
```

The DLL is copied to `resource/FastMedia.dll`, which must be on the classpath root.

Java:

```
javac -cp libs/jna-5.15.0.jar -d out $(find src -name "*.java")
java --enable-native-access=ALL-UNNAMED -cp "libs/jna-5.15.0.jar;out;resource" kz.regullar.fastmedia.Start
```

## Layout

```
src/kz/regullar/fastmedia/           public API
src/kz/regullar/fastmedia/internal/  JNA bindings, lyrics client and parsers
cpp/FastMedia.cpp                    native backend
resource/FastMedia.dll               prebuilt DLL
example/                             overlay renderer example
```
