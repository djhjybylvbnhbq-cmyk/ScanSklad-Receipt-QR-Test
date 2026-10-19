package com.labelscanner.offline;

/** Pure Java two-frame validation. Every confirmation must come from distinct camera frames.
 * Mixed ML and Tesseract observations are allowed, but the longer window applies.
 */
public final class ScanConsensus {
    public static final long FAST_WINDOW_MS = 1800L;
    public static final long FALLBACK_WINDOW_MS = 3200L;

    private String candidate;
    private String source;
    private long timeMs;
    private long frameId = -1;
    private int hits;
    private int resets;

    public boolean offer(String value, String engine, long incomingFrame, long nowMs) {
        if (value == null || value.isEmpty() || incomingFrame < 0) return false;
        long window = "Tesseract".equals(engine) || "Tesseract".equals(source)
                ? FALLBACK_WINDOW_MS : FAST_WINDOW_MS;
        if (value.equals(candidate) && incomingFrame == frameId) return false;
        if (value.equals(candidate) && nowMs >= timeMs && nowMs - timeMs <= window) {
            hits++;
        } else {
            if (candidate != null) resets++;
            candidate = value;
            hits = 1;
        }
        source = engine;
        timeMs = nowMs;
        frameId = incomingFrame;
        return hits >= 2;
    }

    public int hits() { return hits; }
    public int resets() { return resets; }
    public String candidate() { return candidate; }
    public void reset() {
        candidate = null;
        source = null;
        timeMs = 0;
        frameId = -1;
        hits = 0;
        resets = 0;
    }
}
