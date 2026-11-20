package kz.regullar.fastmedia.internal;

import com.sun.jna.Structure;

import java.nio.charset.StandardCharsets;

@Structure.FieldOrder({"pid", "name", "peak", "active", "current"})
public final class AudioSourceStruct extends Structure {
    public int pid;
    public byte[] name = new byte[256];
    public float peak;
    public byte active;
    public byte current;

    public AudioSourceStruct() {
        super(ALIGN_NONE);
    }

    public String name() {
        int len = 0;
        while (len < name.length && name[len] != 0) {
            len++;
        }
        return new String(name, 0, len, StandardCharsets.UTF_8);
    }

    public boolean active() {
        return active != 0;
    }

    public boolean current() {
        return current != 0;
    }
}
