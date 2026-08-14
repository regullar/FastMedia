package kz.regullar.fastmedia.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.nio.charset.StandardCharsets;

/**
 * Mirrors the naturally aligned native {@code LyricsResult} struct.
 *
 * <p>The native side owns the string buffers; call
 * {@link FastMediaNative#FreeLyrics(LyricsResultStruct)} once the strings have
 * been read. Internal.
 */
@Structure.FieldOrder({"syncedLrc", "syncedLen", "plainText", "plainLen", "status"})
public final class LyricsResultStruct extends Structure {
    public Pointer syncedLrc;
    public int syncedLen;
    public Pointer plainText;
    public int plainLen;
    public int status;

    public String syncedLrc() {
        return read(syncedLrc, syncedLen);
    }

    public String plainText() {
        return read(plainText, plainLen);
    }

    private static String read(Pointer ptr, int len) {
        if (ptr == null || len <= 0) {
            return null;
        }
        return new String(ptr.getByteArray(0L, len), StandardCharsets.UTF_8);
    }
}
