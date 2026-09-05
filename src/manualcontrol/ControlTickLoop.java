package manualcontrol;

import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.fall.ImpactFallFeature;

public final class ControlTickLoop {

    private static final long TICK_MS = 16;
    private static final float MOVE_MULTIPLIER = 1.5f;
    private static Thread thread;

    private ControlTickLoop() {}

    public static synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(() -> {
            Logger.log("ControlTickLoop started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException ie) {
                    break;
                }
                try {
                    tick();
                } catch (Throwable t) {
                    Logger.err("Tick error", t);
                }
            }
        }, "ManualControl-Tick");
        thread.setDaemon(true);
        thread.start();
    }

    private static Object animUTypeWalk;
    private static Object animUTypeIdle;
    private static Object animUTypeKB;
    private static Object animUTypeAttack;
    private static java.lang.reflect.Method setAnimMethod;
    private static boolean animCacheInitialized = false;

    private static void initAnimCache(Object animManager) {
        if (animCacheInitialized) return;
        try {
            Class<?> uTypeCls = Class.forName("common.util.anim.AnimU$UType");
            StringBuilder names = new StringBuilder();
            for (Object e : uTypeCls.getEnumConstants()) {
                String name = ((Enum<?>) e).name();
                names.append(name).append(",");
                if (name.equals("WALK")) animUTypeWalk = e;
                if (name.equals("IDLE")) animUTypeIdle = e;
                if (name.equals("ATK")) animUTypeAttack = e;
                if (name.equals("KB") || name.equals("HB") || name.equals("HITBACK"))
                    if (animUTypeKB == null) animUTypeKB = e;
            }
            try {
                setAnimMethod = BCUFields.method(animManager.getClass(), "setAnim", uTypeCls, boolean.class);
            } catch (Throwable t) {
                setAnimMethod = null;
            }
            animCacheInitialized = true;
            Logger.log("Anim cache init: walk=" + (animUTypeWalk != null)
                    + " idle=" + (animUTypeIdle != null)
                    + " atk=" + (animUTypeAttack != null)
                    + " kb=" + (animUTypeKB != null)
                    + " setAnim=" + (setAnimMethod != null)
                    + " all-enum-names=" + names);
        } catch (Throwable t) {
            Logger.err("Failed to init anim cache", t);
        }
    }

    public enum AnimKind { WALK, IDLE, KB, ATK }

    private static void setAnim(Object entity, AnimKind kind) {
        try {
            Object animMgr = BCUFields.get(entity, "anim");
            if (animMgr == null) return;
            if (!animCacheInitialized) initAnimCache(animMgr);
            if (setAnimMethod == null) return;
            Object type = null;
            switch (kind) {
                case WALK: type = animUTypeWalk; break;
                case IDLE: type = animUTypeIdle; break;
                case KB:   type = animUTypeKB != null ? animUTypeKB : animUTypeIdle; break;
                case ATK:  type = animUTypeAttack; break;
            }
            if (type != null) setAnimMethod.invoke(animMgr, type, true);
        } catch (Throwable ignored) {}
    }

    private static void tickAnim(Object entity) {
        try {
            Object animMgr = BCUFields.get(entity, "anim");
            if (animMgr == null) return;
            BCUFields.method(animMgr.getClass(), "update").invoke(animMgr);
        } catch (Throwable ignored) {}
    }

    private static void tick() {
        if (CrazyRuntime.isAnyNativePauseActive()) return;

        tickDetachedFallings();

        HoldState state = HoldState.get();

        if (state.isHolding()) {
            tickHold(state);
            return;
        }
        if (state.isFalling()) {
            tickFalling(state);
            return;
        }
        if (state.isInControl()) {
            tickControl(state);
        }
    }

    private static void tickHold(HoldState state) {
        Object ent = state.getHeldEntity();
        Object page = state.getBattleInfoPage();
        if (ent == null || page == null) return;
        if (EntityAccess.isDead(ent)) {
            state.release();
            return;
        }
        try {
            state.clearHeldMotionState();

            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            if (siz < 0.001f) return;

            float rootScreenX = state.getDragRootScreenX();
            float rootScreenY = state.getDragRootScreenY();
            float targetX = ((rootScreenX - sbPos) / siz - 200f) / 0.32f;
            float targetLayerF = ((rootScreenY - midh) / siz + 156f) / 4f;

            float curX = EntityAccess.getPos(ent);
            int curLayer = EntityAccess.getLayer(ent);
            float newX = curX + (targetX - curX) * HoldState.SPRING_LERP;
            float newLayerF = curLayer + (targetLayerF - curLayer) * HoldState.SPRING_LERP;
            int newLayer = Math.round(newLayerF);

            EntityAccess.setPos(ent, newX);
            EntityAccess.setLayer(ent, newLayer);
            state.clearHeldMotionState();

            setAnim(ent, AnimKind.KB);
            tickAnim(ent);
        } catch (Throwable t) {

        }
    }

    private static int controlTickLogCounter = 0;
    private static void tickControl(HoldState state) {
        Object ent = state.getHeldEntity();
        if (ent == null) {
            controlTickLogCounter++;
            if (controlTickLogCounter % 30 == 1)
                Logger.log("tickControl: heldEntity is NULL but phase=CONTROL - bug?");
            return;
        }
        boolean zombieReviveActive = isZombieReviveActive(ent);
        if (EntityAccess.isDead(ent) && !zombieReviveActive) {
            Logger.log("Held entity died - releasing control");
            state.release();
            return;
        }
        if (zombieReviveActive || readHealth(ent) <= 0L) {
            state.cancelControlJump();
            state.clearQueuedActions();
            setWalking(ent, false);
            state.syncHeldLastPosition();
            controlTickLogCounter++;
            if (controlTickLogCounter % 30 == 1) {
                Logger.log("tickControl: yielding to native death/zombie revive flow");
            }
            return;
        }
        try {
            int kbTime = readKbTime(ent);
            if (kbTime != 0) {
                state.cancelControlJump();
                state.clearQueuedActions();
                setWalking(ent, false);
                controlTickLogCounter++;
                if (controlTickLogCounter % 30 == 1)
                    Logger.log("tickControl: kbTime=" + kbTime + " - input blocked");
                return;
            }

            state.clearHeldMotionState();
            tickManualAttackImmunity(ent);

            boolean attackActive = isAttackActive(ent);
            if (!attackActive) {
                int facing = inputFacing(state);
                if (facing != 0) setManualFacing(ent, facing);
            }
            if (attackActive) {
                state.cancelControlJump();
                state.clearQueuedActions();
                setWalking(ent, false);
                updateManualAttack(ent);
                tickAnim(ent);
                state.syncHeldLastPosition();
                return;
            }

            if (state.consumeAttackPressed()) {
                state.cancelControlJump();
                setWalking(ent, false);
                if (!startManualAttack(ent)) {
                    setAnim(ent, AnimKind.ATK);
                }
                tickAnim(ent);
                state.syncHeldLastPosition();
                return;
            }

            if (state.consumeJumpPressed()) {
                if (state.beginControlJump(EntityAccess.getLayer(ent))) {
                    Logger.log("Manual jump started");
                }
            }
            state.stepControlJump(ent);

            Object data = BCUFields.get(ent, "data");
            float speed = readSpeed(data);
            float move = speed * MOVE_MULTIPLIER * 0.5f;

            float curPos = EntityAccess.getPos(ent);
            float delta = 0;
            if (state.isKeyA()) delta -= move;
            if (state.isKeyD()) delta += move;

            boolean moving = delta != 0;
            setWalking(ent, moving);

            if (moving) {
                EntityAccess.setPos(ent, curPos + delta);
            }
            state.syncHeldLastPosition();

            setAnim(ent, moving ? AnimKind.WALK : AnimKind.IDLE);

            tickAnim(ent);
        } catch (Throwable t) {

        }
    }

    private static int fallTickCount = 0;
    private static void tickFalling(HoldState state) {
        Object ent = state.getHeldEntity();
        Object page = state.getBattleInfoPage();
        if (ent == null || page == null) {
            Logger.log("tickFalling: missing ent/page - finishing");
            state.finishFalling();
            return;
        }
        if (EntityAccess.isDead(ent)) {
            Logger.log("tickFalling: entity died - finishing");
            state.finishFalling();
            return;
        }
        try {
            state.clearHeldMotionState();

            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            if (siz < 0.001f) return;

            float vx = state.getFallVx();
            float vy = state.getFallVy();
            vy += HoldState.GRAVITY;
            vx *= HoldState.AIR_DRAG;
            float sx = state.getFallScreenX() + vx;
            float sy = state.getFallScreenY() + vy;

            int origLayer = state.getOrigEntityLayer();
            float groundY = midh - (156 - origLayer * 4) * siz;
            state.recordFallHeightPx(Math.max(0f, groundY - state.getFallScreenY()));
            state.recordFallHeightPx(Math.max(0f, groundY - sy));

            fallTickCount++;
            if (fallTickCount % 5 == 0) {
                Logger.log(String.format("tickFalling#%d: sy=%.1f vy=%.2f groundY=%.1f sx=%.1f vx=%.2f",
                        fallTickCount, sy, vy, groundY, sx, vx));
            }

            if (sy >= groundY) {
                float gameX = ((sx - sbPos) / siz - 200f) / 0.32f;
                EntityAccess.setPos(ent, gameX);
                EntityAccess.setLayer(ent, origLayer);
                state.clearHeldMotionState();

                if (ImpactFallFeature.isEnabled(page, ent)) {
                    boolean impacted = ImpactFallFeature.triggerLanding(page, ent, sx, groundY, Math.max(0f, vy),
                            state.getMaxFallHeightPx(), origLayer);
                    fallTickCount = 0;
                    if (impacted) {
                        state.release();
                    } else {
                        state.finishFalling();
                    }
                    return;
                }

                int bounce = state.getFallBounceCount();
                if (bounce < HoldState.FALL_BOUNCES) {
                    int nextBounce = bounce + 1;
                    float bouncePower = Math.max(Math.abs(vy) * HoldState.BOUNCE_RESTITUTION,
                            HoldState.BOUNCE_MIN_VELOCITY);
                    if (nextBounce == 2) bouncePower *= 0.62f;
                    state.setFallBounceCount(nextBounce);
                    state.setFallVx(vx * 0.78f);
                    state.setFallVy(-bouncePower);
                    state.setFallScreenX(sx);
                    state.setFallScreenY(groundY - 1f);
                    Logger.log(String.format("BOUNCE #%d at sy=%.1f groundY=%.1f vy=%.2f -> %.2f",
                            nextBounce, sy, groundY, vy, -bouncePower));
                    setAnim(ent, AnimKind.KB);
                    tickAnim(ent);
                    return;
                }

                Logger.log(String.format("LANDED after %d bounce(s), sy=%.1f >= groundY=%.1f after %d ticks",
                        bounce, sy, groundY, fallTickCount));
                fallTickCount = 0;
                state.finishFalling();
                return;
            }

            state.setFallVx(vx);
            state.setFallVy(vy);
            state.setFallScreenX(sx);
            state.setFallScreenY(sy);

            float gameX = ((sx - sbPos) / siz - 200f) / 0.32f;
            int newLayer = (int) (((sy - midh) / siz + 156) / 4f);
            if (newLayer > origLayer) newLayer = origLayer;
            EntityAccess.setPos(ent, gameX);
            EntityAccess.setLayer(ent, newLayer);
            state.clearHeldMotionState();

            setAnim(ent, AnimKind.KB);
            tickAnim(ent);
        } catch (Throwable t) {
            Logger.err("tickFalling error", t);
            state.finishFalling();
        }
    }

    private static void tickDetachedFallings() {
        for (FallingRegistry.Job job : FallingRegistry.snapshot()) {
            tickDetachedFalling(job);
        }
    }

    private static void tickDetachedFalling(FallingRegistry.Job job) {
        if (job == null || job.entity == null || job.battleInfoPage == null) {
            FallingRegistry.remove(job);
            return;
        }
        Object ent = job.entity;
        if (EntityAccess.isDead(ent)) {
            Logger.log("Detached falling entity died - removing job");
            FallingRegistry.remove(job);
            return;
        }
        if (job.landedWaitingForControl) {
            if (HoldState.get().tryControlFromFalling(job)) {
                FallingRegistry.remove(job);
            } else {
                clearJobMotionState(job);
                setAnim(ent, AnimKind.IDLE);
                tickAnim(ent);
            }
            return;
        }
        try {
            clearJobMotionState(job);

            Object bb = BCUFields.get(job.battleInfoPage, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            if (siz < 0.001f) return;

            float vx = job.vx * HoldState.AIR_DRAG;
            float vy = job.vy + HoldState.GRAVITY;
            float sx = job.screenX + vx;
            float sy = job.screenY + vy;
            float groundY = midh - (156 - job.origLayer * 4) * siz;
            job.maxFallHeightPx = Math.max(job.maxFallHeightPx, Math.max(0f, groundY - job.screenY));
            job.maxFallHeightPx = Math.max(job.maxFallHeightPx, Math.max(0f, groundY - sy));

            job.tickCount++;
            if (job.tickCount % 5 == 0) {
                Logger.log(String.format("detachedFalling#%d: sy=%.1f vy=%.2f groundY=%.1f sx=%.1f vx=%.2f",
                        job.tickCount, sy, vy, groundY, sx, vx));
            }

            if (sy >= groundY) {
                float gameX = ((sx - sbPos) / siz - 200f) / 0.32f;
                EntityAccess.setPos(ent, gameX);
                EntityAccess.setLayer(ent, job.origLayer);
                clearJobMotionState(job);

                if (ImpactFallFeature.isEnabled(job.battleInfoPage, ent)) {
                    boolean impacted = ImpactFallFeature.triggerLanding(job.battleInfoPage, ent, sx, groundY,
                            Math.max(0f, vy), job.maxFallHeightPx, job.origLayer);
                    if (impacted) {
                        restoreDetachedToAI(job);
                    } else {
                        finishDetachedFalling(job);
                    }
                    return;
                }

                if (job.bounceCount < HoldState.FALL_BOUNCES) {
                    int nextBounce = job.bounceCount + 1;
                    float bouncePower = Math.max(Math.abs(vy) * HoldState.BOUNCE_RESTITUTION,
                            HoldState.BOUNCE_MIN_VELOCITY);
                    if (nextBounce == 2) bouncePower *= 0.62f;
                    job.bounceCount = nextBounce;
                    job.vx = vx * 0.78f;
                    job.vy = -bouncePower;
                    job.screenX = sx;
                    job.screenY = groundY - 1f;
                    Logger.log(String.format("DETACHED BOUNCE #%d at sy=%.1f groundY=%.1f vy=%.2f -> %.2f",
                            nextBounce, sy, groundY, vy, -bouncePower));
                    setAnim(ent, AnimKind.KB);
                    tickAnim(ent);
                    return;
                }

                Logger.log(String.format("DETACHED LANDED after %d bounce(s), sy=%.1f >= groundY=%.1f after %d ticks",
                        job.bounceCount, sy, groundY, job.tickCount));
                finishDetachedFalling(job);
                return;
            }

            job.vx = vx;
            job.vy = vy;
            job.screenX = sx;
            job.screenY = sy;

            float gameX = ((sx - sbPos) / siz - 200f) / 0.32f;
            int newLayer = (int) (((sy - midh) / siz + 156) / 4f);
            if (newLayer > job.origLayer) newLayer = job.origLayer;
            EntityAccess.setPos(ent, gameX);
            EntityAccess.setLayer(ent, newLayer);
            clearJobMotionState(job);
            setAnim(ent, AnimKind.KB);
            tickAnim(ent);
        } catch (Throwable t) {
            Logger.err("Detached falling error", t);
            restoreDetachedToAI(job);
        }
    }

    private static void finishDetachedFalling(FallingRegistry.Job job) {
        if (job.toControlOnLand) {
            clearJobMotionState(job);
            try { EntityAccess.setLayer(job.entity, job.origLayer); } catch (Throwable ignored) {}
            if (isEnemyEntity(job.entity)) {
                try { ConvertedRegistry.mark(job.entity); } catch (Throwable ignored) {}
            }
            job.landedWaitingForControl = true;
            if (HoldState.get().tryControlFromFalling(job)) {
                FallingRegistry.remove(job);
            } else {
                Logger.log("Detached falling landed; waiting to enter CONTROL because another hold/control is active");
            }
            return;
        }
        restoreDetachedToAI(job);
    }

    private static void restoreDetachedToAI(FallingRegistry.Job job) {
        if (job == null || job.entity == null) {
            FallingRegistry.remove(job);
            return;
        }
        if (job.savedInvincibility) {
            try {
                Object statusArr = BCUFields.get(job.entity, "status");
                ((int[][]) statusArr)[44][0] = job.origStatus44;
            } catch (Throwable ignored) {}
        }
        if (job.savedKbTime) {
            try {
                BCUFields.field(job.entity.getClass(), "kbTime").setInt(job.entity, job.origKbTime);
            } catch (Throwable ignored) {}
        }
        try { EntityAccess.setLayer(job.entity, job.origLayer); } catch (Throwable ignored) {}
        FallingRegistry.remove(job);
        Logger.log("=== DETACHED FALLING COMPLETE - unit returned to AI ===");
    }

    private static void clearJobMotionState(FallingRegistry.Job job) {
        if (job == null || job.entity == null) return;
        try { BCUFields.field(job.entity.getClass(), "kbTime").setInt(job.entity, 0); } catch (Throwable ignored) {}
        try {
            float pos = EntityAccess.getPos(job.entity);
            BCUFields.field(job.entity.getClass(), "lastPosition").setFloat(job.entity, pos);
        } catch (Throwable ignored) {}
    }

    private static boolean isEnemyEntity(Object entity) {
        return entity != null && "common.battle.entity.EEnemy".equals(entity.getClass().getName());
    }

    private static int readKbTime(Object entity) {
        try {
            return BCUFields.getInt(entity, "kbTime");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long readHealth(Object entity) {
        try {
            return BCUFields.field(entity.getClass(), "health").getLong(entity);
        } catch (Throwable ignored) {
            return 1L;
        }
    }

    private static boolean isZombieReviveActive(Object entity) {
        try {
            Object statusArr = BCUFields.get(entity, "status");
            return ((int[][]) statusArr)[48][1] > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setWalking(Object entity, boolean walking) {
        try {
            BCUFields.field(entity.getClass(), "walking").setBoolean(entity, walking);
        } catch (Throwable ignored) {}
    }

    private static int inputFacing(HoldState state) {
        boolean left = state.isKeyA();
        boolean right = state.isKeyD();
        if (left == right) return 0;
        return right ? 1 : -1;
    }

    private static void setManualFacing(Object entity, int dire) {
        try {
            int old = BCUFields.getInt(entity, "dire");
            if (old == dire) return;
            BCUFields.field(entity.getClass(), "dire").setInt(entity, dire);
            Logger.log("Manual facing changed: dire=" + dire + (dire == 1 ? " (right)" : " (left)"));
        } catch (Throwable ignored) {}
    }

    private static void tickManualAttackImmunity(Object entity) {
        try {
            Object statusArr = BCUFields.get(entity, "status");
            int[] s44 = ((int[][]) statusArr)[44];
            if (s44[0] > 0) s44[0]--;
        } catch (Throwable ignored) {}
    }

    private static boolean isAttackActive(Object entity) {
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            return BCUFields.field(atkm.getClass(), "atkTime").getInt(atkm) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean startManualAttack(Object entity) {
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            if (BCUFields.field(atkm.getClass(), "atkTime").getInt(atkm) > 0) return false;
            resetAttackLoop(entity, atkm);
            try { BCUFields.field(entity.getClass(), "waitTime").setInt(entity, 0); } catch (Throwable ignored) {}
            BCUFields.method(atkm.getClass(), "startAttack").invoke(atkm);
            Logger.log("Manual attack started");
            return true;
        } catch (Throwable t) {
            Logger.err("Manual attack start failed", t);
            return false;
        }
    }

    private static void updateManualAttack(Object entity) {
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            BCUFields.method(atkm.getClass(), "updateAttack").invoke(atkm);
        } catch (Throwable ignored) {}
    }

    private static void resetAttackLoop(Object entity, Object atkm) {
        int loops = 1;
        try {
            Object data = BCUFields.get(entity, "data");
            Object value = BCUFields.invoke(data, "getAtkLoop");
            if (value instanceof Number) loops = ((Number) value).intValue();
        } catch (Throwable ignored) {}
        if (loops == 0) loops = 1;
        try { BCUFields.field(atkm.getClass(), "attacksLeft").setInt(atkm, loops); } catch (Throwable ignored) {}
    }

    private static float readSpeed(Object data) {
        try {

            Object speed = BCUFields.invoke(data, "getSpeed");
            if (speed instanceof Integer) return ((Integer) speed).floatValue();
            if (speed instanceof Float) return (Float) speed;
        } catch (Throwable ignored) {}

        try {
            return BCUFields.getFloat(data, "speed");
        } catch (Throwable ignored) {}
        return 10f;
    }
}
