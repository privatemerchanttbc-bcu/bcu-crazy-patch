package manualcontrol.adventure;

import common.battle.entity.EUnit;
import manualcontrol.custommap.CustomMapRuntime;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;

final class AdventureCamera {

    private static final float LERP = 0.18f;

    private int shakeTicks;
    private int shakeAmplitude;
    private final java.util.Random rnd = new java.util.Random();

    void shake(int ticks) {
        shake(ticks, 8);
    }

    void shake(int ticks, int amplitude) {
        if (ticks >= shakeTicks) shakeAmplitude = Math.max(0, amplitude);
        else shakeAmplitude = Math.max(shakeAmplitude, Math.max(0, amplitude));
        shakeTicks = Math.max(shakeTicks, ticks);
    }

    void apply(Object bbpainter, AdventureBattle battle) {

        if (CustomMapRuntime.activeDocument() != null) return;
        EUnit player = battle.player();
        if (player == null) return;
        try {

            int width = BBPainterAccess.getWidth(bbpainter);
            int maxW = BBPainterAccess.getMaxW(bbpainter);
            float siz = BBPainterAccess.getSiz(bbpainter);
            if (siz <= 0.0001f || width <= 0) return;
            int minPos = Math.round(width - maxW * siz);
            int target = Math.round(width * 0.5f - (player.pos * 0.32f + 200f) * siz);
            if (minPos <= 0) target = clamp(target, minPos, 0);

            else if (CustomMapRuntime.activeDocument() == null) target = 0;
            int cur = BBPainterAccess.getStagePos(bbpainter);
            int next = Math.round(cur + (target - cur) * LERP);
            if (shakeTicks > 0) {
                shakeTicks--;
                if (shakeAmplitude > 0) {
                    next += rnd.nextInt(shakeAmplitude * 2 + 1) - shakeAmplitude;
                }
            }
            if (minPos <= 0) next = clamp(next, minPos, 0);
            else if (CustomMapRuntime.activeDocument() == null) next = 0;
            BCUFields.setInt(battle.sb, "pos", next);
        } catch (Throwable ignored) {}
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
