package manualcontrol.fps;

import manualcontrol.Logger;
import manualcontrol.OptionalModes;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class FpsHooks {

    private static volatile boolean active;
    private static volatile int subFrames = 1;
    private static volatile float animStep = 1f;

    private static volatile boolean inAnimPass;
    private static volatile boolean disabled;

    private static volatile int frameCounter;

    private static Method actionsM, sbUpdateM, sbAnimM, maUpdateM;
    private static Field timeF;

    private FpsHooks() {}

    public static void apply() {
        subFrames = FpsConfig.subFrames();
        animStep = 1f / subFrames;
        active = FpsConfig.active() && !disabled;
        try {
            Class<?> timer = Class.forName("main.Timer");
            Field p = timer.getDeclaredField("p");
            p.setAccessible(true);
            p.setInt(null, FpsConfig.frameIntervalMs());
            Logger.log("FPS: target=" + FpsConfig.targetFps() + " interval=" + FpsConfig.frameIntervalMs()
                    + "ms subFrames=" + subFrames + " active=" + active);
        } catch (Throwable t) {
            Logger.err("FPS: could not set main.Timer.p (frame rate unchanged)", t);
        }
    }

    public static void init() { apply(); }

    public static float renderSubFrameFraction() {
        return renderSubFrameFraction(active, subFrames, frameCounter);
    }

    static float renderSubFrameFraction(boolean enabled, int frameCount,
                                        int currentFrame) {
        if (!enabled) return 0f;
        int n = Math.max(1, frameCount);
        return Math.floorMod(currentFrame, n) / (float) n;
    }

    public static boolean handleBattleUpdate(Object battleField) {
        if (!active || battleField == null) return false;
        try {
            Object sb = BCUFields.get(battleField, "sb");
            if (sb == null) return false;
            int n = subFrames;
            boolean logic = (++frameCounter % n == 0);
            inAnimPass = true;
            try {
                if (logic) {

                    if (timeF == null) { timeF = BCUFields.field(sb.getClass(), "time"); }
                    timeF.setInt(sb, timeF.getInt(sb) + 1);
                    actions(battleField);
                    sbUpdate(sb);
                } else {
                    sbAnim(sb);
                    OptionalModes.onSubFrame();
                }
            } finally {
                inAnimPass = false;
            }
            return true;
        } catch (Throwable t) {
            fail("battle-update", t);
            return false;
        }
    }

    public static boolean advanceAnimFrame(Object eanim, boolean rotate) {
        if (!active || !inAnimPass || eanim == null) return false;
        try {
            float f = ((java.lang.Number) BCUFields.get(eanim, "f")).floatValue();
            f += (f == -1f) ? 1f : animStep;
            BCUFields.field(eanim.getClass(), "f").setFloat(eanim, f);
            maUpdate(eanim, f, rotate);
            return true;
        } catch (Throwable t) {
            fail("anim-frame", t);
            return false;
        }
    }

    private static void actions(Object bf) throws Exception {
        if (actionsM == null) actionsM = BCUFields.method(bf.getClass(), "actions");
        actionsM.invoke(bf);
    }

    private static void sbUpdate(Object sb) throws Exception {
        if (sbUpdateM == null) sbUpdateM = BCUFields.method(sb.getClass(), "update");
        sbUpdateM.invoke(sb);
    }

    private static void sbAnim(Object sb) throws Exception {
        if (sbAnimM == null) sbAnimM = BCUFields.method(sb.getClass(), "updateAnimation");
        sbAnimM.invoke(sb);
    }

    private static void maUpdate(Object eanim, float f, boolean rotate) throws Exception {
        Object ma = BCUFields.get(eanim, "ma");
        if (ma == null) return;
        if (maUpdateM == null) {
            maUpdateM = BCUFields.method(ma.getClass(), "update",
                    float.class, Class.forName("common.util.anim.EAnimD"), boolean.class);
        }
        maUpdateM.invoke(ma, f, eanim, rotate);
    }

    private static void fail(String where, Throwable t) {
        if (!disabled) {
            disabled = true;
            active = false;
            Logger.err("FPS: disabled after error in " + where + " (reverting to native 30fps)", t);
        }
    }
}
