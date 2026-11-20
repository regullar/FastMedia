package kz.regullar.fastmedia;

public final class Start {

    public static void main(String[] args) throws InterruptedException {
        try (MediaSession session = MediaSession.open()) {
            session.addListener(new MediaListener() {
                @Override
                public void onTrackChanged(NowPlaying np) {
                    System.out.println("\nNow playing: " + np.title() + " — " + np.artist() + "  [" + np.source() + "]" + (np.hasAlbumArt() ? "  (cover " + np.albumArt().length + " bytes)" : ""));
                }

                @Override
                public void onPositionChanged(PlaybackPosition pos) {
                    System.out.printf("\r%s %s / %s   ", pos.playing() ? "▶" : "⏸", format(pos.positionMs()), format(pos.durationMs()));
                }

                @Override
                public void onPlaybackStopped() {
                    System.out.println("\nNo active media session.");
                }
            });

            System.out.println("Listening for media for 30s... (control playback in any app)");
            Thread.sleep(30_000L);
        }
    }

    private static String format(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
