package kz.regullar.fastmedia.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.nio.charset.StandardCharsets;
@Structure.FieldOrder({"title", "artist", "sourceApp", "albumArtPng", "albumArtSize",
        "isPlaying", "durationMs", "positionMs", "trackChanged"})
public final class MediaInfoStruct extends Structure {
    public byte[] title = new byte[512];
    public byte[] artist = new byte[512];
    public byte[] sourceApp = new byte[256];
    public Pointer albumArtPng;
    public int albumArtSize;
    public byte isPlaying;
    public long durationMs;
    public long positionMs;
    public byte trackChanged;

    public MediaInfoStruct() {
        super(ALIGN_NONE);
    }

    public String title() {
        return utf8(title);
    }

    public String artist() {
        return utf8(artist);
    }

    public String source() {
        return utf8(sourceApp);
    }

    public boolean playing() {
        return isPlaying != 0;
    }

    public boolean trackChanged() {
        return trackChanged != 0;
    }

    public byte[] albumArt() {
        if (albumArtPng == null || albumArtSize <= 0) {
            return null;
        }
        return albumArtPng.getByteArray(0L, albumArtSize);
    }

    private static String utf8(byte[] bytes) {
        int len = 0;
        while (len < bytes.length && bytes[len] != 0) {
            len++;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
