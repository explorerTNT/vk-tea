package com.vktea.msg;

/** Bounded recording peaks; values are emitted as a 0..31 array for voice uploads. */
final class VoiceWaveform {
    // 250 ms samples cover the recorder's two-minute limit without unbounded growth.
    private static final int MAX_SAMPLES = 480;
    private final int[] samples = new int[MAX_SAMPLES];
    private int size;
    private int maximum;

    void add(int amplitude) {
        if (amplitude < 0 || size == samples.length) return;
        int value = Math.min(amplitude, 32767);
        samples[size++] = value;
        maximum = Math.max(maximum, value);
    }

    boolean isFull() { return size == samples.length; }

    /** Null means no real measurements were available; silence remains an array of zeroes. */
    String toJson() {
        if (size == 0) return null;
        StringBuilder out = new StringBuilder(size * 3).append('[');
        for (int i = 0; i < size; i++) {
            if (i != 0) out.append(',');
            int value = maximum == 0 ? 0 : (int) (((long) samples[i] * 31 + maximum / 2) / maximum);
            out.append(value);
        }
        return out.append(']').toString();
    }

    void clear() {
        java.util.Arrays.fill(samples, 0, size, 0);
        size = 0;
        maximum = 0;
    }
}
