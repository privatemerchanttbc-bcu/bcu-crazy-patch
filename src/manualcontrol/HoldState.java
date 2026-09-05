package manualcontrol;

import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.util.ArrayDeque;
import java.util.Deque;

public final class HoldState {

    public enum Phase { NONE, HELD, CONTROL, FALLING }

    private static final HoldState INSTANCE = new HoldState();

    public static final long HOLD_DURATION_MS = 5000;
    public static final int LIFT_THRESHOLD_PX = 80;

    private volatile Phase phase = Phase.NONE;
    private volatile Object heldEntity;
    private volatile Object battleInfoPage;
    private volatile int cursorX, cursorY;
    private volatile float grabOffsetScreenX = 0f;
    private volatile float grabOffsetScreenY = 0f;
    private volatile long holdStartTime = 0;
    private volatile long accumulatedMs = 0;
    private volatile boolean wasAboveThreshold = false;

    private volatile boolean keyA, keyD, keyJ, keyK;
    private volatile boolean keyJPressed, keyKPressed;

    private static final float CONTROL_JUMP_VELOCITY = -5.8f;
    private static final float CONTROL_JUMP_GRAVITY = 0.42f;
    private volatile boolean controlJumping = false;
    private volatile float controlJumpLayer = 0f;
    private volatile float controlJumpVelocity = 0f;

    private volatile float origEntityPos = 0;
    private volatile int origEntityLayer = 0;
    private volatile int origStatus44 = 0;
    private volatile int origKbTime = 0;
    private volatile boolean savedInvincibility = false;
    private volatile boolean savedKbTime = false;

    private final Deque<long[]> cursorHistory = new ArrayDeque<>();

    public static final float GRAVITY = 1.4f;
    public static final float AIR_DRAG = 0.995f;
    public static final float VEL_FACTOR = 1.2f;
    public static final float SPRING_LERP = 0.55f;
    public static final int FALL_BOUNCES = 2;
    public static final float BOUNCE_RESTITUTION = 0.48f;
    public static final float BOUNCE_MIN_VELOCITY = 7.5f;

    private volatile boolean pendingControl = false;

    private volatile float fallScreenX, fallScreenY;
    private volatile float fallVx, fallVy;
    private volatile int fallBounceCount = 0;
    private volatile float maxFallHeightPx = 0f;

    private HoldState() {}
    public static HoldState get() { return INSTANCE; }

    public synchronized void startHold(Object entity, Object infoPage, int x, int y) {
        startHold(entity, infoPage, x, y, 0f, 0f);
    }

    public synchronized void startHold(Object entity, Object infoPage, int x, int y,
                                       float grabOffsetX, float grabOffsetY) {
        if (phase != Phase.NONE) return;
        this.phase = Phase.HELD;
        this.heldEntity = entity;
        this.battleInfoPage = infoPage;
        this.cursorX = x;
        this.cursorY = y;
        this.grabOffsetScreenX = grabOffsetX;
        this.grabOffsetScreenY = grabOffsetY;
        this.accumulatedMs = 0;
        this.holdStartTime = 0;
        this.wasAboveThreshold = false;
        cursorHistory.clear();
        cursorHistory.addLast(new long[]{System.currentTimeMillis(), x, y});

        try {
            this.origEntityPos = EntityAccess.getPos(entity);
            this.origEntityLayer = EntityAccess.getLayer(entity);
        } catch (Throwable ignored) {
            this.origEntityPos = 0;
            this.origEntityLayer = 0;
        }

        try {
            Object statusArr = BCUFields.get(entity, "status");
            int[] s44 = ((int[][]) statusArr)[44];
            this.origStatus44 = s44[0];
            s44[0] = 99999;
            this.savedInvincibility = true;
        } catch (Throwable t) {
            Logger.err("Failed to set invincibility", t);
            this.savedInvincibility = false;
        }

        try {
            this.origKbTime = readKbTime(entity);
            this.savedKbTime = true;
            setKbTime(entity, 0);
        } catch (Throwable t) {
            Logger.err("Failed to save/clear kbTime", t);
            this.origKbTime = 0;
            this.savedKbTime = false;
        }
    }

    public synchronized void updateCursor(int x, int y, boolean aboveThreshold) {
        this.cursorX = x;
        this.cursorY = y;
        long now = System.currentTimeMillis();
        cursorHistory.addLast(new long[]{now, x, y});
        while (cursorHistory.size() > 10) cursorHistory.pollFirst();

        if (aboveThreshold) {
            if (!wasAboveThreshold) {
                holdStartTime = now;
                wasAboveThreshold = true;
            }
        } else {
            if (wasAboveThreshold) {
                accumulatedMs += (now - holdStartTime);
                wasAboveThreshold = false;
                holdStartTime = 0;
            }
        }
    }

    public synchronized float[] computeVelocity() {
        if (cursorHistory.size() < 2) return new float[]{0, 0};
        long[] latest = cursorHistory.peekLast();

        long[] earlier = null;
        long targetTime = latest[0] - 100;
        for (long[] s : cursorHistory) {
            earlier = s;
            if (s[0] >= targetTime) break;
        }
        if (earlier == null || earlier == latest) earlier = cursorHistory.peekFirst();
        long dt = latest[0] - earlier[0];
        if (dt <= 0) return new float[]{0, 0};
        float vx = (float) (latest[1] - earlier[1]) * 16f / dt;
        float vy = (float) (latest[2] - earlier[2]) * 16f / dt;
        return new float[]{vx * VEL_FACTOR, vy * VEL_FACTOR};
    }

    public float getProgress() {
        long total = accumulatedMs;
        if (wasAboveThreshold && holdStartTime > 0) {
            total += (System.currentTimeMillis() - holdStartTime);
        }
        float p = (float) total / (float) HOLD_DURATION_MS;
        return Math.max(0f, Math.min(1f, p));
    }

    public synchronized void enterControl() {
        if (phase != Phase.HELD) return;
        this.phase = Phase.CONTROL;
        clearControlInvincibility();
        clearControlInput();
        Logger.log("=== ENTERED CONTROL PHASE ===");
    }

    public synchronized void enterFalling(int releaseX, int releaseY, float vx, float vy, boolean toControlOnLand) {
        if (phase != Phase.HELD) return;
        this.phase = Phase.FALLING;
        this.fallScreenX = releaseX;
        this.fallScreenY = releaseY;
        this.fallVx = vx;
        this.fallVy = vy;
        this.fallBounceCount = 0;
        this.maxFallHeightPx = 0f;
        this.pendingControl = toControlOnLand;
        Logger.log(String.format("=== FALLING START === pos=(%d,%d) vel=(%.1f,%.1f) toControl=%b",
                releaseX, releaseY, vx, vy, toControlOnLand));
    }
    public boolean isPendingControl() { return pendingControl; }

    public synchronized void detachFalling(int releaseX, int releaseY, float vx, float vy, boolean toControlOnLand) {
        if (phase != Phase.HELD || heldEntity == null) return;
        FallingRegistry.add(new FallingRegistry.Job(
                heldEntity, battleInfoPage, releaseX, releaseY, vx, vy, toControlOnLand,
                origEntityLayer, origStatus44, origKbTime, savedInvincibility, savedKbTime));
        Logger.log(String.format("=== DETACHED FALLING START === entity=%s pos=(%d,%d) vel=(%.1f,%.1f) toControl=%b",
                heldEntity.getClass().getSimpleName(), releaseX, releaseY, vx, vy, toControlOnLand));

        this.phase = Phase.NONE;
        this.heldEntity = null;
        this.battleInfoPage = null;
        this.holdStartTime = 0;
        this.accumulatedMs = 0;
        this.wasAboveThreshold = false;
        this.grabOffsetScreenX = 0f;
        this.grabOffsetScreenY = 0f;
        clearControlInput();
        this.savedInvincibility = false;
        this.savedKbTime = false;
        this.pendingControl = false;
        this.maxFallHeightPx = 0f;
        cursorHistory.clear();
    }

    public synchronized boolean tryControlFromFalling(FallingRegistry.Job job) {
        if (job == null || job.entity == null || phase != Phase.NONE) return false;
        this.phase = Phase.CONTROL;
        this.heldEntity = job.entity;
        this.battleInfoPage = job.battleInfoPage;
        this.origEntityLayer = job.origLayer;
        this.origStatus44 = job.origStatus44;
        this.origKbTime = job.origKbTime;
        this.savedInvincibility = job.savedInvincibility;
        this.savedKbTime = false;
        this.pendingControl = false;
        this.fallBounceCount = 0;
        this.maxFallHeightPx = 0f;
        clearControlInvincibility();
        clearControlInput();
        try { setKbTime(heldEntity, 0); } catch (Throwable ignored) {}
        try { EntityAccess.setLayer(heldEntity, origEntityLayer); } catch (Throwable ignored) {}
        if ("common.battle.entity.EEnemy".equals(heldEntity.getClass().getName())) {
            try { ConvertedRegistry.mark(heldEntity); } catch (Throwable ignored) {}
        }
        syncHeldLastPosition();
        Logger.log("=== DETACHED FALLING -> CONTROL === entity=" + heldEntity.getClass().getSimpleName());
        return true;
    }

    public synchronized void release() {
        boolean wasControl = phase == Phase.CONTROL;
        if (wasControl) restoreAllyDirectionAfterControl();
        restoreEntityState();
        this.phase = Phase.NONE;
        this.heldEntity = null;
        this.battleInfoPage = null;
        this.holdStartTime = 0;
        this.accumulatedMs = 0;
        this.wasAboveThreshold = false;
        this.grabOffsetScreenX = 0f;
        this.grabOffsetScreenY = 0f;
        clearControlInput();
        this.fallBounceCount = 0;
        this.maxFallHeightPx = 0f;
        this.pendingControl = false;
        cursorHistory.clear();
    }

    public synchronized void forceReset() {
        this.phase = Phase.NONE;
        this.heldEntity = null;
        this.battleInfoPage = null;
        this.holdStartTime = 0;
        this.accumulatedMs = 0;
        this.wasAboveThreshold = false;
        this.grabOffsetScreenX = 0f;
        this.grabOffsetScreenY = 0f;
        clearControlInput();
        this.fallBounceCount = 0;
        this.maxFallHeightPx = 0f;
        this.savedInvincibility = false;
        this.savedKbTime = false;
        this.pendingControl = false;
        cursorHistory.clear();
        ControlMarker.clear();
        Logger.log("HoldState forceReset");
    }

    private void restoreEntityState() {
        if (heldEntity == null) return;

        if (controlJumping) {
            setJumpInvincibility(heldEntity, false);
            controlJumping = false;
        }

        if (savedInvincibility) {
            try {
                Object statusArr = BCUFields.get(heldEntity, "status");
                ((int[][]) statusArr)[44][0] = origStatus44;
            } catch (Throwable ignored) {}
            savedInvincibility = false;
        }

        if (savedKbTime) {
            try {
                java.lang.reflect.Field kbField = BCUFields.field(heldEntity.getClass(), "kbTime");
                kbField.setInt(heldEntity, origKbTime);
            } catch (Throwable ignored) {}
            savedKbTime = false;
        }

        try { EntityAccess.setLayer(heldEntity, origEntityLayer); } catch (Throwable ignored) {}
    }

    private void restoreAllyDirectionAfterControl() {
        if (heldEntity == null) return;
        try {
            BCUFields.field(heldEntity.getClass(), "dire").setInt(heldEntity, -1);
            Logger.log("CONTROL release: restored ally direction dire=-1");
        } catch (Throwable ignored) {}
    }

    private void clearControlInvincibility() {
        if (heldEntity == null) return;
        try {
            Object statusArr = BCUFields.get(heldEntity, "status");
            ((int[][]) statusArr)[44][0] = 0;
            if (savedInvincibility) {
                Logger.log("CONTROL damage enabled: cleared held invincibility status[44]");
            }
        } catch (Throwable ignored) {}
        savedInvincibility = false;
    }

    public void clearHeldMotionState() {
        Object entity = heldEntity;
        if (entity == null) return;
        try { setKbTime(entity, 0); } catch (Throwable ignored) {}
        syncHeldLastPosition();
    }

    public void syncHeldLastPosition() {
        Object entity = heldEntity;
        if (entity == null) return;
        try {
            float pos = EntityAccess.getPos(entity);
            BCUFields.field(entity.getClass(), "lastPosition").setFloat(entity, pos);
        } catch (Throwable ignored) {}
    }

    private static int readKbTime(Object entity) throws Exception {
        java.lang.reflect.Field kbField = BCUFields.field(entity.getClass(), "kbTime");
        return kbField.getInt(entity);
    }

    private static void setKbTime(Object entity, int value) throws Exception {
        java.lang.reflect.Field kbField = BCUFields.field(entity.getClass(), "kbTime");
        kbField.setInt(entity, value);
    }

    public Phase getPhase() { return phase; }
    public Object getHeldEntity() { return heldEntity; }
    public Object getBattleInfoPage() { return battleInfoPage; }
    public int getCursorX() { return cursorX; }
    public int getCursorY() { return cursorY; }
    public float getGrabOffsetScreenX() { return grabOffsetScreenX; }
    public float getGrabOffsetScreenY() { return grabOffsetScreenY; }
    public float getDragRootScreenX() { return cursorX - grabOffsetScreenX; }
    public float getDragRootScreenY() { return cursorY - grabOffsetScreenY; }
    public boolean isHolding() { return phase == Phase.HELD; }

    public boolean isChargingZone() { return wasAboveThreshold; }
    public boolean isInControl() { return phase == Phase.CONTROL; }
    public boolean isFalling() { return phase == Phase.FALLING; }
    public boolean isAtFullProgress() { return getProgress() >= 1.0f; }
    public float getOrigEntityPos() { return origEntityPos; }
    public int getOrigEntityLayer() { return origEntityLayer; }

    public synchronized void setKey(int keyCode, boolean down) {
        switch (keyCode) {
            case java.awt.event.KeyEvent.VK_A: keyA = down; break;
            case java.awt.event.KeyEvent.VK_D: keyD = down; break;
            case java.awt.event.KeyEvent.VK_J:
                if (down && !keyJ) keyJPressed = true;
                keyJ = down;
                break;
            case java.awt.event.KeyEvent.VK_K:
                if (down && !keyK) keyKPressed = true;
                keyK = down;
                break;
        }
    }
    public boolean isKeyA() { return keyA; }
    public boolean isKeyD() { return keyD; }
    public boolean isKeyJ() { return keyJ; }
    public boolean isKeyK() { return keyK; }

    public synchronized boolean consumeAttackPressed() {
        if (!keyJPressed) return false;
        keyJPressed = false;
        return true;
    }

    public synchronized boolean consumeJumpPressed() {
        if (!keyKPressed) return false;
        keyKPressed = false;
        return true;
    }

    public synchronized void clearQueuedActions() {
        keyJPressed = false;
        keyKPressed = false;
    }

    private synchronized void clearControlInput() {
        keyA = keyD = keyJ = keyK = false;
        keyJPressed = keyKPressed = false;
        controlJumping = false;
        controlJumpLayer = 0f;
        controlJumpVelocity = 0f;
    }

    public synchronized boolean beginControlJump(float startLayer) {
        if (controlJumping) return false;
        controlJumping = true;
        controlJumpLayer = startLayer;
        controlJumpVelocity = CONTROL_JUMP_VELOCITY;

        setJumpInvincibility(heldEntity, true);
        return true;
    }

    public synchronized boolean stepControlJump(Object entity) {
        if (!controlJumping || entity == null) return false;
        controlJumpVelocity += CONTROL_JUMP_GRAVITY;
        controlJumpLayer += controlJumpVelocity;
        boolean landed = false;
        if (controlJumpLayer >= origEntityLayer) {
            controlJumpLayer = origEntityLayer;
            controlJumping = false;
            landed = true;
        }
        try { EntityAccess.setLayer(entity, Math.round(controlJumpLayer)); } catch (Throwable ignored) {}

        setJumpInvincibility(entity, !landed);
        return controlJumping;
    }

    public synchronized void cancelControlJump() {
        if (controlJumping) setJumpInvincibility(heldEntity, false);
        controlJumping = false;
        controlJumpLayer = origEntityLayer;
        controlJumpVelocity = 0f;
    }

    private void setJumpInvincibility(Object entity, boolean on) {
        if (entity == null) return;
        try {
            Object statusArr = BCUFields.get(entity, "status");
            ((int[][]) statusArr)[44][0] = on ? 99999 : 0;
        } catch (Throwable ignored) {}
    }

    public boolean isControlJumping() { return controlJumping; }

    public float getFallScreenX() { return fallScreenX; }
    public float getFallScreenY() { return fallScreenY; }
    public float getFallVx() { return fallVx; }
    public float getFallVy() { return fallVy; }
    public int getFallBounceCount() { return fallBounceCount; }
    public float getMaxFallHeightPx() { return maxFallHeightPx; }
    public void setFallScreenX(float v) { this.fallScreenX = v; }
    public void setFallScreenY(float v) { this.fallScreenY = v; }
    public void setFallVx(float v) { this.fallVx = v; }
    public void setFallVy(float v) { this.fallVy = v; }
    public void setFallBounceCount(int v) { this.fallBounceCount = v; }
    public void recordFallHeightPx(float v) {
        if (v > maxFallHeightPx) this.maxFallHeightPx = v;
    }

    public synchronized void finishFalling() {
        if (phase != Phase.FALLING) return;
        if (pendingControl) {

            if (heldEntity != null) {
                try { setKbTime(heldEntity, 0); } catch (Throwable ignored) {}
                try { EntityAccess.setLayer(heldEntity, origEntityLayer); } catch (Throwable ignored) {}
                if ("common.battle.entity.EEnemy".equals(heldEntity.getClass().getName())) {
                    try { ConvertedRegistry.mark(heldEntity); } catch (Throwable ignored) {}
                }
                clearHeldMotionState();
            }

            this.savedKbTime = false;
            this.phase = Phase.CONTROL;
            clearControlInvincibility();
            clearControlInput();
            this.fallBounceCount = 0;
            this.maxFallHeightPx = 0f;
            this.pendingControl = false;
            Logger.log("=== FALLING -> CONTROL === entity=" + (heldEntity != null ? heldEntity.getClass().getSimpleName() : "null"));
            return;
        }
        restoreEntityState();
        this.phase = Phase.NONE;
        this.heldEntity = null;
        this.battleInfoPage = null;
        this.grabOffsetScreenX = 0f;
        this.grabOffsetScreenY = 0f;
        this.fallBounceCount = 0;
        this.maxFallHeightPx = 0f;
        this.pendingControl = false;
        Logger.log("=== FALLING COMPLETE - unit landed ===");
    }
}
