package manualcontrol;

import java.util.ArrayList;
import java.util.List;

public final class FallingRegistry {

    public static final class Job {
        public final Object entity;
        public final Object battleInfoPage;
        public final int origLayer;
        public final int origStatus44;
        public final int origKbTime;
        public final boolean savedInvincibility;
        public final boolean savedKbTime;
        public final boolean toControlOnLand;

        public float screenX;
        public float screenY;
        public float vx;
        public float vy;
        public int bounceCount;
        public int tickCount;
        public float maxFallHeightPx;
        public boolean landedWaitingForControl;

        public Job(Object entity, Object battleInfoPage, int releaseX, int releaseY,
                   float vx, float vy, boolean toControlOnLand,
                   int origLayer, int origStatus44, int origKbTime,
                   boolean savedInvincibility, boolean savedKbTime) {
            this.entity = entity;
            this.battleInfoPage = battleInfoPage;
            this.screenX = releaseX;
            this.screenY = releaseY;
            this.vx = vx;
            this.vy = vy;
            this.toControlOnLand = toControlOnLand;
            this.origLayer = origLayer;
            this.origStatus44 = origStatus44;
            this.origKbTime = origKbTime;
            this.savedInvincibility = savedInvincibility;
            this.savedKbTime = savedKbTime;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    private FallingRegistry() {}

    public static synchronized void add(Job job) {
        if (job != null && job.entity != null) JOBS.add(job);
    }

    public static synchronized void remove(Job job) {
        JOBS.remove(job);
    }

    public static synchronized List<Job> snapshot() {
        return new ArrayList<>(JOBS);
    }

    public static synchronized boolean isManaged(Object entity) {
        if (entity == null) return false;
        for (Job job : JOBS) {
            if (job.entity == entity) return true;
        }
        return false;
    }
}
