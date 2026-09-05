package manualcontrol.custommap;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class BreakableIceEngine {

    static final int OVERLOAD_OCCUPANTS = 5;
    static final int CRACK_INTERVAL_TICKS = 60;
    static final int FINAL_OVERLOAD_TICKS = 90;
    static final int BROKEN_TICKS = 90;
    static final int REBUILD_TICKS = 30;

    enum SupportKind { MAIN, FLOATING }

    enum Phase {
        INTACT,
        CRACK_1,
        CRACK_2,
        CRACK_3,
        BROKEN,
        REBUILDING
    }

    static final class SupportKey {
        private final SupportKind kind;
        private final String platformId;
        private final int tileX;

        private SupportKey(SupportKind kind, String platformId, int tileX) {
            if (tileX < 0)
                throw new IllegalArgumentException("Ice support tile X must be non-negative");
            if (kind == SupportKind.FLOATING
                    && (platformId == null || platformId.trim().isEmpty()))
                throw new IllegalArgumentException("Floating ice support requires a platform ID");
            this.kind = kind;
            this.platformId = platformId;
            this.tileX = tileX;
        }

        static SupportKey main(int tileX) {
            return new SupportKey(SupportKind.MAIN, null, tileX);
        }

        static SupportKey floating(String platformId, int localTileX) {
            return new SupportKey(SupportKind.FLOATING, platformId, localTileX);
        }

        SupportKind kind() { return kind; }
        String platformId() { return platformId; }
        int tileX() { return tileX; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SupportKey)) return false;
            SupportKey key = (SupportKey) other;
            if (kind != key.kind || tileX != key.tileX) return false;
            return platformId == null
                    ? key.platformId == null
                    : platformId.equals(key.platformId);
        }

        @Override
        public int hashCode() {
            int result = 31 * kind.hashCode() + tileX;
            return 31 * result + (platformId == null ? 0 : platformId.hashCode());
        }

        @Override
        public String toString() {
            return kind == SupportKind.MAIN
                    ? "MAIN/" + tileX
                    : "FLOATING/" + platformId + "/" + tileX;
        }
    }

    static final class VisualState {
        private final SupportKey key;
        private final Phase phase;
        private final int crackLevel;
        private final int progressTicks;
        private final int phaseDurationTicks;
        private final int lastOccupantCount;

        private VisualState(SupportKey key, Phase phase, int crackLevel,
                            int progressTicks, int phaseDurationTicks,
                            int lastOccupantCount) {
            this.key = key;
            this.phase = phase;
            this.crackLevel = crackLevel;
            this.progressTicks = progressTicks;
            this.phaseDurationTicks = phaseDurationTicks;
            this.lastOccupantCount = lastOccupantCount;
        }

        SupportKey key() { return key; }
        Phase phase() { return phase; }
        int crackLevel() { return crackLevel; }
        int progressTicks() { return progressTicks; }
        int phaseDurationTicks() { return phaseDurationTicks; }
        int lastOccupantCount() { return lastOccupantCount; }

        boolean overloaded() {
            return lastOccupantCount > OVERLOAD_OCCUPANTS;
        }

        boolean collisionPresent() {
            return phase != Phase.BROKEN && phase != Phase.REBUILDING;
        }

        float progress() {
            if (phaseDurationTicks <= 0) return 0f;
            return Math.max(0f, Math.min(1f,
                    progressTicks / (float) phaseDurationTicks));
        }
    }

    private static final class MutableState {
        int crackLevel;
        int crackProgressTicks;
        int finalOverloadTicks;
        int phaseTicks;
        int lastOccupantCount;
        Phase phase = Phase.INTACT;
    }

    private final Map<SupportKey, MutableState> states =
            new LinkedHashMap<SupportKey, MutableState>();
    private final Map<SupportKey, Set<Object>> reportedOccupants =
            new LinkedHashMap<SupportKey, Set<Object>>();
    private long gameplayTick;

    synchronized void reportOccupant(SupportKey key, Object actorIdentity) {
        if (key == null || actorIdentity == null) return;
        Set<Object> actors = reportedOccupants.get(key);
        if (actors == null) {
            actors = Collections.newSetFromMap(
                    new IdentityHashMap<Object, Boolean>());
            reportedOccupants.put(key, actors);
        }
        actors.add(actorIdentity);
    }

    synchronized void advanceTick() {
        gameplayTick++;

        for (Map.Entry<SupportKey, MutableState> entry
                : new LinkedHashMap<SupportKey, MutableState>(states).entrySet()) {
            SupportKey key = entry.getKey();
            MutableState state = entry.getValue();
            Set<Object> actors = reportedOccupants.get(key);
            state.lastOccupantCount = actors == null ? 0 : actors.size();
            advanceExisting(key, state);
        }

        for (Map.Entry<SupportKey, Set<Object>> report : reportedOccupants.entrySet()) {
            if (states.containsKey(report.getKey())
                    || report.getValue().size() <= OVERLOAD_OCCUPANTS)
                continue;
            MutableState state = new MutableState();
            state.lastOccupantCount = report.getValue().size();
            states.put(report.getKey(), state);
            advanceLoaded(state);
        }
        reportedOccupants.clear();
    }

    private void advanceExisting(SupportKey key, MutableState state) {
        if (state.phase == Phase.BROKEN) {
            if (++state.phaseTicks >= BROKEN_TICKS) {
                state.phase = Phase.REBUILDING;
                state.phaseTicks = 0;
            }
            return;
        }
        if (state.phase == Phase.REBUILDING) {
            if (++state.phaseTicks >= REBUILD_TICKS)
                states.remove(key);
            return;
        }

        if (state.lastOccupantCount > OVERLOAD_OCCUPANTS)
            advanceLoaded(state);
        else if (state.crackLevel >= 3)
            state.finalOverloadTicks = 0;
    }

    private static void advanceLoaded(MutableState state) {
        if (state.crackLevel < 3) {
            if (++state.crackProgressTicks >= CRACK_INTERVAL_TICKS) {
                state.crackLevel++;
                state.crackProgressTicks = 0;
                state.phase = crackPhase(state.crackLevel);
            }
            return;
        }
        if (++state.finalOverloadTicks >= FINAL_OVERLOAD_TICKS) {
            state.phase = Phase.BROKEN;
            state.phaseTicks = 0;
            state.finalOverloadTicks = 0;
        }
    }

    synchronized VisualState state(SupportKey key) {
        MutableState state = states.get(key);
        return state == null ? intact(key) : snapshotOf(key, state);
    }

    synchronized boolean collisionPresent(SupportKey key) {
        return state(key).collisionPresent();
    }

    synchronized Map<SupportKey, VisualState> snapshot() {
        Map<SupportKey, VisualState> copy =
                new LinkedHashMap<SupportKey, VisualState>();
        for (Map.Entry<SupportKey, MutableState> entry : states.entrySet())
            copy.put(entry.getKey(), snapshotOf(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }

    synchronized long gameplayTick() { return gameplayTick; }
    synchronized int trackedTileCount() { return states.size(); }

    synchronized void clear() {
        states.clear();
        reportedOccupants.clear();
        gameplayTick = 0L;
    }

    private static VisualState snapshotOf(SupportKey key, MutableState state) {
        int progress;
        int duration;
        if (state.phase == Phase.BROKEN) {
            progress = state.phaseTicks;
            duration = BROKEN_TICKS;
        } else if (state.phase == Phase.REBUILDING) {
            progress = state.phaseTicks;
            duration = REBUILD_TICKS;
        } else if (state.crackLevel >= 3) {
            progress = state.finalOverloadTicks;
            duration = FINAL_OVERLOAD_TICKS;
        } else {
            progress = state.crackProgressTicks;
            duration = CRACK_INTERVAL_TICKS;
        }
        return new VisualState(key, state.phase, state.crackLevel,
                progress, duration, state.lastOccupantCount);
    }

    private static VisualState intact(SupportKey key) {
        return new VisualState(key, Phase.INTACT, 0, 0,
                CRACK_INTERVAL_TICKS, 0);
    }

    private static Phase crackPhase(int crackLevel) {
        if (crackLevel == 1) return Phase.CRACK_1;
        if (crackLevel == 2) return Phase.CRACK_2;
        return Phase.CRACK_3;
    }
}
