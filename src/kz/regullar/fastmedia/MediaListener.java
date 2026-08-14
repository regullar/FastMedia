package kz.regullar.fastmedia;

/**
 * Receives media updates from a {@link MediaSession}.
 *
 * <p>Callbacks fire on the session's internal monitoring threads. Implementations
 * that touch a UI/render thread should marshal work onto it. All methods are
 * {@code default} no-ops, so listeners override only what they need.
 */
public interface MediaListener {

    /** A new track started, or its metadata/cover changed. */
    default void onTrackChanged(NowPlaying nowPlaying) {
    }

    /** Frequent playback position / play-state update for the current track. */
    default void onPositionChanged(PlaybackPosition position) {
    }

    /** No active media session is available (playback ended or no player). */
    default void onPlaybackStopped() {
    }
}
