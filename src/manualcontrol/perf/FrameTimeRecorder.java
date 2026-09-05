package manualcontrol.perf;

import java.util.Arrays;

public final class FrameTimeRecorder {
    public static final int CADENCE = 0, DRAW = 1, UPDATE = 2;
    public static final String[] METRICS = {"draw_start_interval", "draw_cpu", "battle_update_cpu"};
    private final long[][] samples, startOffsets;
    private final int[] counts = new int[3];
    private final long[] dropped = new long[3], failures = new long[3];
    private final long started, startedEpochMs;
    private long firstDrawOffsetNs = -1;
    private Object battle;
    private long previousDraw;
    private boolean hasDraw, closed;
    private int warmup;
    private long otherBattleDraws;
    private long firstOtherDrawOffsetNs = -1;

    public FrameTimeRecorder(int capacity, int warmup, long started) {
        this(capacity, warmup, started, -1);
    }

    public FrameTimeRecorder(int capacity, int warmup, long started, long startedEpochMs) {
        if (capacity < 1 || capacity > 120000 || warmup < 0 || warmup > 36000)
            throw new IllegalArgumentException("invalid recording bounds");
        samples = new long[3][capacity];
        startOffsets = new long[3][capacity];
        this.warmup = warmup;
        this.started = started;
        this.startedEpochMs = startedEpochMs;
    }

    public synchronized long beginDraw(Object owner, long now) {
        if (closed || owner == null || now - started < 0) return 0;
        if (battle == null) battle = owner;
        if (owner != battle) {
            otherBattleDraws++;
            if (firstOtherDrawOffsetNs < 0) firstOtherDrawOffsetNs = now - started;
            return 0;
        }
        if (warmup > 0) { warmup--; return 0; }
        if (hasDraw) add(CADENCE, previousDraw, now);
        else firstDrawOffsetNs = now - started;
        previousDraw = now;
        hasDraw = true;
        return now;
    }

    public synchronized long beginUpdate(Object owner, long now) {
        return !closed && owner != null && owner == battle && hasDraw && now - started >= 0 ? now : 0;
    }

    public synchronized boolean hasForeignBattleDraws() { return otherBattleDraws > 0; }

    public synchronized void end(Object owner, int metric, long start, long now, boolean failed) {
        if (closed || start == 0 || owner != battle || start - started < 0) return;
        if (metric != DRAW && metric != UPDATE) return;
        add(metric, start, now);
        if (failed) failures[metric]++;
    }

    private void add(int metric, long start, long end) {
        long duration = end - start;
        if (duration < 0) return;
        if (counts[metric] == samples[metric].length) { dropped[metric]++; return; }
        int index = counts[metric]++;
        samples[metric][index] = duration;
        startOffsets[metric][index] = start - started;
    }

    public synchronized Snapshot close(long now) {
        closed = true;
        battle = null;
        long[][] copy = new long[3][];
        long[][] offsets = new long[3][];
        for (int i = 0; i < 3; i++) {
            copy[i] = Arrays.copyOf(samples[i], counts[i]);
            offsets[i] = Arrays.copyOf(startOffsets[i], counts[i]);
        }
        return new Snapshot(copy, offsets, dropped.clone(), failures.clone(), now - started,
                warmup, otherBattleDraws, startedEpochMs, firstDrawOffsetNs, firstOtherDrawOffsetNs);
    }

    public static final class Snapshot {
        public final long[][] samples, startOffsetsNs;
        public final long[] dropped, failures;
        public final long elapsedNs, otherBattleDraws;
        public final long startedEpochMs, firstDrawOffsetNs;
        public final long firstOtherDrawOffsetNs;
        public final int warmupRemaining;
        private Snapshot(long[][] samples, long[][] offsets, long[] dropped, long[] failures, long elapsed,
                         int warmup, long other, long epoch, long firstDraw, long firstOther) {
            this.samples = samples; this.dropped = dropped; this.failures = failures;
            startOffsetsNs = offsets; startedEpochMs = epoch; firstDrawOffsetNs = firstDraw;
            firstOtherDrawOffsetNs = firstOther;
            elapsedNs = elapsed; warmupRemaining = warmup; otherBattleDraws = other;
        }
    }

    public static final class Stats {
        public final int count, overBudget, over2xBudget, over50ms;
        public final double averageMs, p95Ms, p99Ms, maxMs;
        public Stats(long[] input, double budgetMs) {
            long[] sorted = input.clone();
            Arrays.sort(sorted);
            count = sorted.length;
            double sum = 0;
            int over = 0, over2 = 0, over50 = 0;
            for (long ns : sorted) {
                double ms = ns / 1000000.0;
                sum += ms;
                if (ms > budgetMs) over++;
                if (ms > budgetMs * 2) over2++;
                if (ms > 50) over50++;
            }
            overBudget = over; over2xBudget = over2; over50ms = over50;
            averageMs = count == 0 ? Double.NaN : sum / count;
            p95Ms = percentile(sorted, .95); p99Ms = percentile(sorted, .99);
            maxMs = count == 0 ? Double.NaN : sorted[count - 1] / 1000000.0;
        }
        private static double percentile(long[] sorted, double fraction) {
            return sorted.length == 0 ? Double.NaN
                    : sorted[(int) Math.ceil(fraction * sorted.length) - 1] / 1000000.0;
        }
    }
}
