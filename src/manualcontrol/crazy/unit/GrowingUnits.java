package manualcontrol.crazy.unit;

import common.battle.attack.AttackAb;
import common.battle.entity.Entity;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.EntityAccess;

import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

public final class GrowingUnits {

    private static final float GROWTH_PER_FRAME = 0.025f / 60.0f;

    private GrowingUnits() {}

    public static final class State {
        public final WeakHashMap<Object, Integer> ages = new WeakHashMap<Object, Integer>();
    }

    @SuppressWarnings("unchecked")
    public static void tick(CrazyRuntime.StageRuntime rt) {
        java.util.List<Object> entities = (java.util.List<Object>) manualcontrol.reflect.BCUFields.get(rt.stage, "le");
        for (Object e : entities) {
            if (e == null) continue;
            if (!EntityAccess.isPlayerUnit(e)) continue;
            if (EntityAccess.isDead(e)) continue;
            Integer age = rt.growing.ages.get(e);
            rt.growing.ages.put(e, age == null ? 1 : age + 1);
        }
        Iterator<Map.Entry<Object, Integer>> it = rt.growing.ages.entrySet().iterator();
        while (it.hasNext()) {
            Object e = it.next().getKey();
            if (e == null || EntityAccess.isDead(e)) {
                it.remove();
            }
        }
    }

    public static float scaleFor(Object entity) {
        if (entity == null) return 1.0f;
        if (!(entity instanceof Entity)) return 1.0f;
        Entity e = (Entity) entity;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(e.basis);
        if (rt == null || !rt.config.growingUnits) return 1.0f;
        Integer age = rt.growing.ages.get(entity);
        if (age == null || age <= 0) return 1.0f;
        float scale = 1.0f + age * GROWTH_PER_FRAME;
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return 1.0f;
        return Math.max(1.0f, scale);
    }

    public static void applyDamageMultiplier(CrazyRuntime.StageRuntime rt, AttackAb attack) {
        if (attack == null || attack.attacker == null) return;
        if (!EntityAccess.isPlayerUnit(attack.attacker)) return;
        float scale = scaleFor(attack.attacker);
        if (scale <= 1.0001f) return;
        long next = Math.round((double) attack.rawAtk * (double) scale);
        if (next > Integer.MAX_VALUE) next = Integer.MAX_VALUE;
        if (next < Integer.MIN_VALUE) next = Integer.MIN_VALUE;
        attack.atk = (int) next;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void appendScaledHitboxes(CrazyRuntime.StageRuntime rt, List result, int touch, int dire,
                                            float d0, float d1, boolean excludeRightEdge) {
        if (rt == null || result == null || dire != -1) return;
        List<Object> entities = (List<Object>) manualcontrol.reflect.BCUFields.get(rt.stage, "le");
        float left = Math.min(d0, d1);
        float right = Math.max(d0, d1);
        for (Object obj : entities) {
            if (!(obj instanceof Entity)) continue;
            Entity e = (Entity) obj;
            if (!EntityAccess.isPlayerUnit(e) || e.dead || e.health <= 0L) continue;
            if (!touchable(e, touch)) continue;
            float scale = scaleFor(e);
            if (scale <= 1.0001f) continue;
            float extra = scaledHitboxExtra(e, scale);
            if (extra <= 0.5f) continue;
            boolean inside = excludeRightEdge
                    ? e.pos >= left - extra && e.pos < right + extra
                    : e.pos >= left - extra && e.pos <= right + extra;
            if (inside && !result.contains(e)) {
                result.add(e);
            }
        }
    }

    private static boolean touchable(Entity e, int touch) {
        try {
            return (e.touchable() & touch) > 0;
        } catch (Throwable ignored) {
            return (touch & 1) != 0;
        }
    }

    private static float scaledHitboxExtra(Entity e, float scale) {
        float width = 120f;
        try {
            width = Math.max(40f, e.data.getWidth());
        } catch (Throwable ignored) {}
        return width * 0.5f * (scale - 1.0f);
    }
}
