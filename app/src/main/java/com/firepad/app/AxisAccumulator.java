package com.firepad.app;

/** Turns a stream of fractional deltas into capped integer HID steps without dropping the fraction. */
public final class AxisAccumulator {
    private float remainder;

    public void reset() {
        remainder = 0f;
    }

    public int[] consume(float delta, int limit) {
        if (limit <= 0) return new int[0];
        remainder += delta;
        int total = (int) remainder;
        if (total > 2048) total = 2048;
        if (total < -2048) total = -2048;
        remainder -= total;
        if (total == 0) return new int[0];
        int sign = total > 0 ? 1 : -1;
        int left = Math.abs(total);
        int chunks = (left + limit - 1) / limit;
        int[] out = new int[chunks];
        for (int i = 0; i < chunks; i++) {
            int step = Math.min(limit, left);
            out[i] = sign * step;
            left -= step;
        }
        return out;
    }
}
