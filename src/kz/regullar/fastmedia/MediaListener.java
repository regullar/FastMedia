package kz.regullar.fastmedia;
public interface MediaListener {
    default void onTrackChanged(NowPlaying nowPlaying) {
    }
    default void onPositionChanged(PlaybackPosition position) {
    }
    default void onPlaybackStopped() {
    }
}
