package kz.regullar.fastmedia;

import java.time.Duration;

/** Immutable snapshot of playback position and state. */
public record PlaybackPosition(boolean playing, long durationMs, long positionMs) {

    public Duration duration() {
        return Duration.ofMillis(durationMs);
    }

    public Duration position() {
        return Duration.ofMillis(positionMs);
    }

    /** Progress in {@code [0, 1]}; {@code 0} when the duration is unknown. */
    public float progress() {
        if (durationMs <= 0) {
            return 0f;
        }
        float p = (float) positionMs / (float) durationMs;
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }
}
