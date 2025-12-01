package com.konovalov.vad.example.player;

import java.util.ArrayList;
import java.util.List;

public class PcmBuffer {
    private final List<short[]> chunks = new ArrayList<>();

    public void append(short[] data) {
        chunks.add(data.clone());
    }

    public void clear() {
        chunks.clear();
    }

    public short[] getAndClear() {
        int total = 0;
        for (short[] a : chunks) total += a.length;

        short[] out = new short[total];
        int pos = 0;
        for (short[] a : chunks) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }

        chunks.clear();
        return out;
    }
}
