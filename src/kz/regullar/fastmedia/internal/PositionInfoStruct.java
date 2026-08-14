package kz.regullar.fastmedia.internal;

import com.sun.jna.Structure;

/** Mirrors the packed native {@code PositionInfo} struct. Internal. */
@Structure.FieldOrder({"isPlaying", "durationMs", "positionMs"})
public final class PositionInfoStruct extends Structure {
    public byte isPlaying;
    public long durationMs;
    public long positionMs;

    public PositionInfoStruct() {
        super(ALIGN_NONE);
    }

    public boolean playing() {
        return isPlaying != 0;
    }
}
