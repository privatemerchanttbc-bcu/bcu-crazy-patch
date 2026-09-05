package manualcontrol;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

public final class SniperManualState {

    public static final int DEFAULT_COOLDOWN_FRAMES = 300;
    public static final int MAX_SNIPER_COUNT = 10;
    private static final int MAX_RAPID_SHOTS = 240;
    private static final WeakHashMap<Object, Config> BY_SNIPER = new WeakHashMap<>();

    private static int lastCooldownFrames = DEFAULT_COOLDOWN_FRAMES;
    private static int lastDamage = -1;
    private static int lastSniperCount = 1;

    private SniperManualState() {}

    public static synchronized Config register(Object sniper, int cooldownFrames, int damage, int sniperCount) {
        Config cfg = BY_SNIPER.get(sniper);
        if (cfg == null) {
            cfg = new Config();
            BY_SNIPER.put(sniper, cfg);
        }
        cfg.enabled = true;
        cfg.cooldownFrames = clamp(cooldownFrames, 0, 99999);
        cfg.damage = Math.max(1, damage);
        cfg.sniperCount = clamp(sniperCount, 1, MAX_SNIPER_COUNT);
        cfg.cooldownRemaining = 0;
        cfg.clearTargets();
        cfg.resetReticle();
        lastCooldownFrames = cfg.cooldownFrames;
        lastDamage = cfg.damage;
        lastSniperCount = cfg.sniperCount;
        return cfg;
    }

    public static synchronized void unregister(Object sniper) {
        BY_SNIPER.remove(sniper);
    }

    public static synchronized Config get(Object sniper) {
        return BY_SNIPER.get(sniper);
    }

    public static synchronized boolean isActive(Object sniper) {
        Config cfg = BY_SNIPER.get(sniper);
        return cfg != null && cfg.enabled;
    }

    public static synchronized int rememberedCooldown() {
        return lastCooldownFrames;
    }

    public static synchronized int rememberedDamageOr(int fallback) {
        return lastDamage > 0 ? lastDamage : Math.max(1, fallback);
    }

    public static synchronized int rememberedSniperCount() {
        return lastSniperCount;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Config {
        public boolean enabled;
        public int cooldownFrames = DEFAULT_COOLDOWN_FRAMES;
        public int damage = 1;
        public int sniperCount = 1;
        public int cooldownRemaining;
        public float activePos = -1f;
        public float activeLayer;
        public int lastMouseX = Integer.MIN_VALUE;
        public int lastMouseY = Integer.MIN_VALUE;
        public long lastRejectLogMs;
        public long lastShotLogMs;
        public long reticleEpochNanos;
        public long reticleLastShotNanos = -1L;

        private ShotRequest queuedShot;
        private final List<ShotRequest> queuedRapidShots = new ArrayList<ShotRequest>();
        private final List<ManualBullet> manualBullets = new ArrayList<ManualBullet>();
        private WeakReference<Object> activeTarget = new WeakReference<Object>(null);
        private ShotRequest heldShot;
        private boolean mouseDown;

        public boolean isRapidFire() {
            return cooldownFrames <= 0;
        }

        public synchronized void rememberMouse(int x, int y) {
            lastMouseX = x;
            lastMouseY = y;
        }

        public synchronized long ensureReticleEpoch(long nowNanos) {
            if (reticleEpochNanos <= 0L) {
                reticleEpochNanos = Math.max(1L, nowNanos);
            }
            return reticleEpochNanos;
        }

        public synchronized void markReticleShot(long nowNanos) {
            ensureReticleEpoch(nowNanos);
            reticleLastShotNanos = Math.max(1L, nowNanos);
        }

        public synchronized long reticleLastShotNanos() {
            return reticleLastShotNanos;
        }

        public synchronized void resetReticle() {
            reticleEpochNanos = 0L;
            reticleLastShotNanos = -1L;
        }

        public synchronized void queueShot(Object target, float aimPos, float aimLayer, int x, int y) {
            rememberMouse(x, y);
            ShotRequest shot = new ShotRequest(target, aimPos, aimLayer);
            if (isRapidFire()) {
                if (queuedRapidShots.size() >= MAX_RAPID_SHOTS) {
                    queuedRapidShots.remove(0);
                }
                queuedRapidShots.add(shot);
            } else {
                queuedShot = shot;
            }
        }

        public synchronized void startMouseHold(Object target, float aimPos, float aimLayer, int x, int y) {
            mouseDown = true;
            updateMouseHold(target, aimPos, aimLayer, x, y);
        }

        public synchronized void updateMouseHold(Object target, float aimPos, float aimLayer, int x, int y) {
            rememberMouse(x, y);
            heldShot = new ShotRequest(target, aimPos, aimLayer);
        }

        public synchronized void stopMouseHold() {
            mouseDown = false;
            heldShot = null;
        }

        public synchronized boolean isMouseDown() {
            return mouseDown;
        }

        public synchronized ShotRequest currentHeldShot() {
            return heldShot;
        }

        public synchronized ShotRequest consumeQueuedShot() {
            ShotRequest shot = queuedShot;
            queuedShot = null;
            return shot;
        }

        public synchronized List<ShotRequest> drainQueuedRapidShots() {
            List<ShotRequest> shots = new ArrayList<ShotRequest>(queuedRapidShots);
            queuedRapidShots.clear();
            return shots;
        }

        public synchronized void addManualBullet(ManualBullet bullet) {
            manualBullets.add(bullet);
        }

        public synchronized List<ManualBullet> manualBulletsSnapshot() {
            return new ArrayList<ManualBullet>(manualBullets);
        }

        public synchronized void removeManualBullet(ManualBullet bullet) {
            manualBullets.remove(bullet);
        }

        public synchronized boolean hasManualBullets() {
            return !manualBullets.isEmpty();
        }

        public synchronized ManualBullet visualManualBullet() {
            for (int i = manualBullets.size() - 1; i >= 0; i--) {
                ManualBullet bullet = manualBullets.get(i);
                if (bullet.sniperIndex == 0 && bullet.preTime <= 0) return bullet;
            }
            for (int i = manualBullets.size() - 1; i >= 0; i--) {
                ManualBullet bullet = manualBullets.get(i);
                if (bullet.sniperIndex == 0) return bullet;
            }
            return manualBullets.isEmpty() ? null : manualBullets.get(manualBullets.size() - 1);
        }

        public synchronized ManualBullet visualManualBullet(int sniperIndex) {
            for (int i = manualBullets.size() - 1; i >= 0; i--) {
                ManualBullet bullet = manualBullets.get(i);
                if (bullet.sniperIndex == sniperIndex && bullet.preTime <= 0) return bullet;
            }
            for (int i = manualBullets.size() - 1; i >= 0; i--) {
                ManualBullet bullet = manualBullets.get(i);
                if (bullet.sniperIndex == sniperIndex) return bullet;
            }
            return null;
        }

        public synchronized void setActiveTarget(Object target, float pos, float layer) {
            activeTarget = new WeakReference<Object>(target);
            activePos = pos;
            activeLayer = layer;
        }

        public synchronized Object getActiveTarget() {
            return activeTarget.get();
        }

        public synchronized void updateActivePosition(float pos, float layer) {
            activePos = pos;
            activeLayer = layer;
        }

        public synchronized void clearTargets() {
            queuedShot = null;
            queuedRapidShots.clear();
            manualBullets.clear();
            stopMouseHold();
            clearActiveTarget();
        }

        public synchronized void clearActiveTarget() {
            activeTarget = new WeakReference<Object>(null);
            activePos = -1f;
            activeLayer = 0;
        }
    }

    public static final class ShotRequest {
        public final WeakReference<Object> target;
        public final float aimPos;
        public final float aimLayer;

        ShotRequest(Object target, float aimPos, float aimLayer) {
            this.target = new WeakReference<Object>(target);
            this.aimPos = aimPos;
            this.aimLayer = aimLayer;
        }

        public Object getTarget() {
            return target.get();
        }
    }

    public static final class ManualBullet {
        public final WeakReference<Object> initialTarget;
        public final int sniperIndex;
        public final float originOffsetX;
        public final float originOffsetY;
        public final float muzzlePos;
        public final float muzzleLayer;
        public final float renderAimPos;
        public final float renderAimLayer;
        public final float impactAimPos;
        public final float impactAimLayer;
        public final double angle;
        public int preTime;
        public float progress;
        private long flightStartNanos = -1L;
        private long flightDurationNanos = 1L;

        public ManualBullet(Object target, int sniperIndex, float originOffsetX, float originOffsetY,
                            float muzzlePos, float muzzleLayer, float renderAimPos, float renderAimLayer,
                            float impactAimPos, float impactAimLayer, double angle, int preTime) {
            this.initialTarget = new WeakReference<Object>(target);
            this.sniperIndex = sniperIndex;
            this.originOffsetX = originOffsetX;
            this.originOffsetY = originOffsetY;
            this.muzzlePos = muzzlePos;
            this.muzzleLayer = muzzleLayer;
            this.renderAimPos = renderAimPos;
            this.renderAimLayer = renderAimLayer;
            this.impactAimPos = impactAimPos;
            this.impactAimLayer = impactAimLayer;
            this.angle = angle;
            this.preTime = preTime;
            this.progress = 0.0f;
        }

        public Object getInitialTarget() {
            return initialTarget.get();
        }

        public boolean flightStarted() {
            return flightStartNanos > 0L;
        }

        public void startFlight(long nowNanos, long durationNanos) {
            if (flightStartNanos > 0L) return;
            flightStartNanos = Math.max(1L, nowNanos);
            flightDurationNanos = Math.max(1L, durationNanos);
            progress = 0.0f;
        }

        public float progressAt(long nowNanos) {
            if (flightStartNanos <= 0L) return progress;
            float elapsed = (float) (Math.max(0L, nowNanos - flightStartNanos)
                    / (double) flightDurationNanos);
            return Math.max(progress, Math.min(1.0f, elapsed));
        }

        public void syncProgress(long nowNanos) {
            progress = progressAt(nowNanos);
        }

        public float currentPos(long nowNanos) {
            float p = progressAt(nowNanos);
            return muzzlePos + (renderAimPos - muzzlePos) * p;
        }

        public float currentLayer(long nowNanos) {
            float p = progressAt(nowNanos);
            return muzzleLayer + (renderAimLayer - muzzleLayer) * p;
        }
    }
}
