package kz.regullar.fastmedia;

import com.sun.jna.Native;
import kz.regullar.fastmedia.internal.AudioSourceStruct;
import kz.regullar.fastmedia.internal.MediaInfoStruct;
import kz.regullar.fastmedia.internal.FastMediaNative;
import kz.regullar.fastmedia.internal.PositionInfoStruct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class NativeBackend {
    private static final Logger LOG = Logger.getLogger(NativeBackend.class.getName());
    private static final String DLL_RESOURCE = "/FastMedia.dll";

    private static final int TRANSIENT = -1;

    private static final ThreadLocal<MediaInfoStruct> MEDIA_BUF =
            ThreadLocal.withInitial(MediaInfoStruct::new);
    private static final ThreadLocal<PositionInfoStruct> POSITION_BUF =
            ThreadLocal.withInitial(PositionInfoStruct::new);
    private static final ThreadLocal<AudioSourceStruct> SOURCE_BUF =
            ThreadLocal.withInitial(AudioSourceStruct::new);

    private static final CopyOnWriteArrayList<IntConsumer> EVENT_SINKS = new CopyOnWriteArrayList<>();

    private static final FastMediaNative.MediaEventCallback EVENT_CALLBACK = type -> {
        for (IntConsumer sink : EVENT_SINKS) {
            try {
                sink.accept(type);
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "[FastMedia] event sink threw", e);
            }
        }
    };

    private static volatile boolean initialized = false;
    private static boolean eventsRegistered = false;
    private static int waveformUsers = 0;

    static final class TransientFailure extends RuntimeException {
        TransientFailure(String message) {
            super(message, null, false, false);
        }
    }

    private NativeBackend() {
    }

    static synchronized void load() {
        if (initialized) {
            return;
        }
        try (InputStream in = NativeBackend.class.getResourceAsStream(DLL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("FastMedia.dll not found on classpath (" + DLL_RESOURCE + ")");
            }
            byte[] dllBytes = in.readAllBytes();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(dllBytes);
            String hash = Long.toHexString(crc.getValue());
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "fastmedia_native");
            Files.createDirectories(cacheDir);
            Path target = cacheDir.resolve("FastMedia_" + hash + ".dll");
            if (!isExtracted(target, dllBytes.length)) {
                try {
                    Files.write(target, dllBytes, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException locked) {
                    target = cacheDir.resolve("FastMedia_" + hash + "_" + ProcessHandle.current().pid() + ".dll");
                    Files.write(target, dllBytes, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
            FastMediaNative.register(target.toAbsolutePath().toString());
            validateLayout();
            initialized = true;
            LOG.log(Level.INFO, "[FastMedia] native library loaded from {0} (JNA Direct Mapping)", target);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("Failed to load FastMedia.dll: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract FastMedia.dll from resources", e);
        }
    }

    private static boolean isExtracted(Path target, long expectedSize) {
        try {
            return Files.size(target) == expectedSize;
        } catch (IOException e) {
            return false;
        }
    }

    private static void ensureLoaded() {
        if (!initialized) {
            load();
        }
    }

    private static void validateLayout() {
        if (Native.POINTER_SIZE != 8) {
            throw new IllegalStateException("FastMedia requires a 64-bit JVM");
        }
        int actualMedia = new MediaInfoStruct().size();
        if (actualMedia != 1310) {
            throw new IllegalStateException(
                    "MediaInfo layout mismatch: expected 1310, got " + actualMedia);
        }
        int actualPosition = new PositionInfoStruct().size();
        if (actualPosition != 17) {
            throw new IllegalStateException(
                    "PositionInfo layout mismatch: expected 17, got " + actualPosition);
        }
        int actualSource = new AudioSourceStruct().size();
        if (actualSource != 266) {
            throw new IllegalStateException(
                    "AudioSource layout mismatch: expected 266, got " + actualSource);
        }
    }

    static synchronized boolean addEventSink(IntConsumer sink) {
        ensureLoaded();
        EVENT_SINKS.addIfAbsent(sink);
        if (!eventsRegistered) {
            eventsRegistered = FastMediaNative.RegisterMediaEventCallback(EVENT_CALLBACK) != 0;
        }
        return eventsRegistered;
    }

    static synchronized void removeEventSink(IntConsumer sink) {
        if (!EVENT_SINKS.remove(sink) || !EVENT_SINKS.isEmpty() || !initialized) {
            return;
        }
        FastMediaNative.UnregisterMediaEventCallback();
        eventsRegistered = false;
        FastMediaNative.ClearCache();
    }

    static NowPlaying queryNowPlaying() {
        ensureLoaded();
        MediaInfoStruct s = MEDIA_BUF.get();
        try {
            int result = FastMediaNative.GetCurrentMediaInfo(s);
            if (result == TRANSIENT) {
                throw new TransientFailure("media properties unavailable");
            }
            if (result == 0) {
                return null;
            }
            return new NowPlaying(s.title(), s.artist(), s.source(), s.albumArt(),
                    s.playing(), s.durationMs, s.positionMs, s.trackChanged());
        } finally {
            FastMediaNative.FreeMediaInfo(s);
        }
    }

    static PlaybackPosition queryPosition() {
        ensureLoaded();
        PositionInfoStruct s = POSITION_BUF.get();
        int result = FastMediaNative.GetCurrentPositionInfo(s);
        if (result == TRANSIENT) {
            throw new TransientFailure("playback position unavailable");
        }
        if (result == 0) {
            return null;
        }
        return new PlaybackPosition(s.playing(), s.durationMs, s.positionMs);
    }

    static boolean play() {
        ensureLoaded();
        return FastMediaNative.Play() != 0;
    }

    static boolean pause() {
        ensureLoaded();
        return FastMediaNative.Pause() != 0;
    }

    static boolean toggle() {
        ensureLoaded();
        return FastMediaNative.TogglePlayPause() != 0;
    }

    static boolean next() {
        ensureLoaded();
        return FastMediaNative.SkipNext() != 0;
    }

    static boolean previous() {
        ensureLoaded();
        return FastMediaNative.SkipPrevious() != 0;
    }

    static boolean seek(long ms) {
        ensureLoaded();
        return FastMediaNative.SeekToMs(ms) != 0;
    }

    static synchronized boolean acquireWaveform() {
        ensureLoaded();
        if (waveformUsers == 0 && FastMediaNative.StartAudioCapture() == 0) {
            return false;
        }
        waveformUsers++;
        return true;
    }

    static synchronized void releaseWaveform() {
        if (waveformUsers > 0 && --waveformUsers == 0) {
            FastMediaNative.StopAudioCapture();
        }
    }

    static int waveform(float[] out) {
        ensureLoaded();
        return FastMediaNative.GetWaveform(out, out.length);
    }

    static float audioLevel() {
        ensureLoaded();
        return FastMediaNative.GetAudioLevel();
    }

    static List<AudioSource> listAudioSources() {
        ensureLoaded();
        int count = FastMediaNative.RefreshAudioSources();
        if (count <= 0) {
            return List.of();
        }
        AudioSourceStruct s = SOURCE_BUF.get();
        List<AudioSource> sources = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (FastMediaNative.GetAudioSource(i, s) != 0) {
                sources.add(new AudioSource(s.pid, s.name(), s.peak, s.active(), s.current()));
            }
        }
        return sources;
    }

    static boolean setWaveformSource(int pid) {
        ensureLoaded();
        return FastMediaNative.SetWaveformSource(pid) != 0;
    }

    static int waveformSourcePid() {
        ensureLoaded();
        return FastMediaNative.GetWaveformSourcePid();
    }
}
