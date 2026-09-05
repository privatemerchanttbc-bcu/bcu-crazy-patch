package manualcontrol;

import common.battle.StageBasis;
import common.battle.entity.EAnimCont;
import common.battle.entity.Entity;
import common.battle.entity.WaprCont;
import common.util.anim.EAnimI;
import manualcontrol.reflect.BCUFields;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class NativeRenderLifecycleGuard {

    private static final int MIN_WARP_TIMEOUT_FRAMES = 180;
    private static final int WARP_TIMEOUT_PADDING_FRAMES = 30;

    private static final Map<Object, StageState> STAGE_STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, StageState>());
    private static final Map<Object, ContState> CONT_STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, ContState>());

    private static long lastWarpLogMs;
    private static long lastEntityLogMs;
    private static int suppressedWarpLogs;
    private static int suppressedEntityLogs;

    private NativeRenderLifecycleGuard() {}

    public static void beforeDrawFrame(Object stageBasis) {
        StageBasis sb = asStage(stageBasis);
        if (sb == null) return;
        StageState st = stateFor(sb);
        int drawTick;
        synchronized (st) {
            drawTick = ++st.drawTick;
        }
        maintain(sb, false, false, 0, true, "draw");
        if (readInt(sb, "s_stop", 0) != 0) {
            maintain(sb, true, true, -drawTick, false, "draw-stop");
        }
    }

    public static void beforeStageUpdate(Object stageBasis) {
        StageBasis sb = asStage(stageBasis);
        if (sb == null) return;
        StageState st = stateFor(sb);
        synchronized (st) {
            ++st.stageTick;
        }
        maintain(sb, false, false, 0, false, "stage-enter");
    }

    public static void onNativeStageSkipped(Object stageBasis, String phase) {
        StageBasis sb = asStage(stageBasis);
        if (sb == null) return;
        StageState st = stateFor(sb);
        int stageTick;
        synchronized (st) {
            stageTick = st.stageTick;
            if (stageTick <= 0) {
                stageTick = ++st.stageTick;
            }
        }
        maintain(sb, true, false, stageTick, false, phase == null ? "stage-skip" : phase);
    }

    public static void afterStageUpdate(Object stageBasis) {
        StageBasis sb = asStage(stageBasis);
        if (sb == null) return;
        maintain(sb, false, false, 0, false, "stage-exit");
    }

    @SuppressWarnings("unchecked")
    private static void maintain(StageBasis sb, boolean advanceWarp, boolean onlyNonTimeImmune,
                                 int advanceTick, boolean ageFrame, String reason) {
        try {
            List<EAnimCont> lea = sb.lea;
            if (lea != null) {
                synchronized (lea) {
                    Iterator<EAnimCont> it = lea.iterator();
                    while (it.hasNext()) {
                        EAnimCont content = it.next();
                        if (!(content instanceof WaprCont)) {
                            continue;
                        }
                        WaprCont warp = (WaprCont) content;
                        ContState state = contStateFor(warp);
                        if (ageFrame) {
                            ++state.ageFrames;
                        }
                        if (advanceWarp && (!onlyNonTimeImmune || !warp.timeImmune)
                                && state.lastAdvanceTick != advanceTick) {
                            state.lastAdvanceTick = advanceTick;
                            safeUpdate(warp);
                        }

                        WarpSnapshot snap = inspectWarp(warp);
                        String removeReason = warpRemoveReason(warp, state, snap);
                        if (removeReason != null) {
                            it.remove();
                            CONT_STATES.remove(warp);
                            logWarpRemoval(removeReason, reason, state, snap, sb);
                        }
                    }
                }
            }
            cleanupDeadEntities(sb, reason);
        } catch (Throwable t) {
            Logger.err("NativeRenderLifecycleGuard failed during " + reason, t);
        }
    }

    private static String warpRemoveReason(WaprCont warp, ContState state, WarpSnapshot snap) {
        try {
            if (warp.done()) {
                return "done";
            }
        } catch (Throwable ignored) {}

        if (snap.exit && snap.charaLen > 0 && snap.charaIndex >= 0.0f
                && snap.charaIndex >= (float) (snap.charaLen - 2)) {
            return "exit-index";
        }

        int nativeLen = Math.max(snap.effectLen, snap.charaLen);
        int timeout = Math.max(MIN_WARP_TIMEOUT_FRAMES, nativeLen + WARP_TIMEOUT_PADDING_FRAMES);
        if (state.ageFrames > timeout) {
            return "timeout";
        }
        return null;
    }

    private static void cleanupDeadEntities(StageBasis sb, String reason) {
        List<Entity> le = sb.le;
        if (le == null || le.isEmpty()) return;

        synchronized (le) {
            Iterator<Entity> it = le.iterator();
            while (it.hasNext()) {
                Entity entity = it.next();
                if (!shouldRemoveEntity(entity)) {
                    continue;
                }
                EntitySnapshot snap = inspectEntity(entity);
                it.remove();
                logEntityRemoval(reason, snap);
            }
        }
    }

    private static boolean shouldRemoveEntity(Entity entity) {
        if (entity == null) return false;
        try {
            if (entity.isBase()) return false;
        } catch (Throwable ignored) {}
        try {
            if (entity.summoned != null && !entity.summoned.isEmpty()) return false;
        } catch (Throwable ignored) {}

        EntitySnapshot snap = inspectEntity(entity);
        if (snap.reviveActive || snap.warpActive || snap.burrowActive) {
            return false;
        }
        if (snap.animDead == 0) {
            return true;
        }
        return snap.dead && snap.animDead <= 0;
    }

    private static WarpSnapshot inspectWarp(WaprCont warp) {
        WarpSnapshot snap = new WarpSnapshot();
        try {
            Object type = BCUFields.get(warp, "type");
            snap.typeName = type == null ? "unknown" : String.valueOf(type);
            snap.exit = type instanceof Enum && "EXIT".equals(((Enum<?>) type).name());
        } catch (Throwable ignored) {
            snap.typeName = "unknown";
        }
        try {
            Object chara = BCUFields.get(warp, "chara");
            if (chara instanceof EAnimI) {
                EAnimI anim = (EAnimI) chara;
                snap.charaIndex = anim.ind();
                snap.charaLen = anim.len();
            }
        } catch (Throwable ignored) {}
        try {
            Object animObj = BCUFields.get(warp, "anim");
            if (animObj instanceof EAnimI) {
                EAnimI anim = (EAnimI) animObj;
                snap.effectIndex = anim.ind();
                snap.effectLen = anim.len();
            }
        } catch (Throwable ignored) {}
        return snap;
    }

    private static EntitySnapshot inspectEntity(Entity entity) {
        EntitySnapshot snap = new EntitySnapshot();
        if (entity == null) return snap;
        snap.className = entity.getClass().getSimpleName();
        try { snap.health = entity.health; } catch (Throwable ignored) {}
        try { snap.dead = entity.dead; } catch (Throwable ignored) {}
        try { snap.animDead = entity.anim == null ? -999 : entity.anim.dead; } catch (Throwable ignored) {}
        snap.kbTime = readInt(entity, "kbTime", 0);
        try {
            int[][] status = entity.status;
            if (status != null) {
                if (status.length > 11 && status[11] != null && status[11].length > 2) {
                    snap.warpActive = status[11][0] > 0 || status[11][1] > 0 || status[11][2] != 0;
                }
                if (status.length > 48 && status[48] != null && status[48].length > 1) {
                    snap.reviveActive = status[48][1] > 0;
                }
            }
        } catch (Throwable ignored) {}
        snap.burrowActive = snap.kbTime == -2 || snap.kbTime == -3 || snap.kbTime == -4;
        return snap;
    }

    private static void safeUpdate(EAnimCont content) {
        try {
            content.update();
        } catch (Throwable t) {
            Logger.err("NativeRenderLifecycleGuard failed to advance WaprCont", t);
        }
    }

    private static StageBasis asStage(Object stageBasis) {
        return stageBasis instanceof StageBasis ? (StageBasis) stageBasis : null;
    }

    private static StageState stateFor(Object stage) {
        synchronized (STAGE_STATES) {
            StageState st = STAGE_STATES.get(stage);
            if (st == null) {
                st = new StageState();
                STAGE_STATES.put(stage, st);
            }
            return st;
        }
    }

    private static ContState contStateFor(Object content) {
        synchronized (CONT_STATES) {
            ContState st = CONT_STATES.get(content);
            if (st == null) {
                st = new ContState();
                CONT_STATES.put(content, st);
            }
            return st;
        }
    }

    private static int readInt(Object obj, String field, int fallback) {
        try {
            return BCUFields.getInt(obj, field);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void logWarpRemoval(String removeReason, String phase, ContState state,
                                       WarpSnapshot snap, StageBasis sb) {
        long now = System.currentTimeMillis();
        if (now - lastWarpLogMs < 1000L) {
            ++suppressedWarpLogs;
            return;
        }
        int suppressed = suppressedWarpLogs;
        suppressedWarpLogs = 0;
        lastWarpLogMs = now;
        Logger.log("NativeRenderLifecycleGuard removed WaprCont"
                + " cause=" + removeReason
                + " phase=" + phase
                + " type=" + snap.typeName
                + " age=" + state.ageFrames
                + " chara=" + snap.charaIndex + "/" + snap.charaLen
                + " effect=" + snap.effectIndex + "/" + snap.effectLen
                + " s_stop=" + readInt(sb, "s_stop", 0)
                + (suppressed > 0 ? " suppressed=" + suppressed : ""));
    }

    private static void logEntityRemoval(String phase, EntitySnapshot snap) {
        long now = System.currentTimeMillis();
        if (now - lastEntityLogMs < 1000L) {
            ++suppressedEntityLogs;
            return;
        }
        int suppressed = suppressedEntityLogs;
        suppressedEntityLogs = 0;
        lastEntityLogMs = now;
        Logger.log("NativeRenderLifecycleGuard removed dead entity"
                + " phase=" + phase
                + " class=" + snap.className
                + " health=" + snap.health
                + " dead=" + snap.dead
                + " animDead=" + snap.animDead
                + " kbTime=" + snap.kbTime
                + (suppressed > 0 ? " suppressed=" + suppressed : ""));
    }

    private static final class StageState {
        int stageTick;
        int drawTick;
    }

    private static final class ContState {
        int ageFrames;
        int lastAdvanceTick = Integer.MIN_VALUE;
    }

    private static final class WarpSnapshot {
        String typeName = "unknown";
        boolean exit;
        float charaIndex = -1.0f;
        int charaLen = -1;
        float effectIndex = -1.0f;
        int effectLen = -1;
    }

    private static final class EntitySnapshot {
        String className = "unknown";
        long health;
        boolean dead;
        int animDead = -999;
        int kbTime;
        boolean reviveActive;
        boolean warpActive;
        boolean burrowActive;
    }
}
