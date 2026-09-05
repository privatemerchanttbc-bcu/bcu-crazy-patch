package manualcontrol.crazy.collision;

import manualcontrol.reflect.BCUFields;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class SpriteScale {

    private static final Map<Object, Float> SCALE =
            Collections.synchronizedMap(new WeakHashMap<Object, Float>());

    private SpriteScale() {}

    public static void record(Object entity, float renderSiz, float growth) {
        if (entity == null) return;
        try {
            Object basis = BCUFields.get(entity, "basis");
            float basisSiz = basis == null ? 0f : BCUFields.getFloat(basis, "siz");
            if (basisSiz <= 0f || Float.isNaN(renderSiz) || renderSiz <= 0f) return;
            float mult = renderSiz * (growth <= 0f ? 1f : growth) / basisSiz;
            if (mult > 0f && !Float.isNaN(mult) && !Float.isInfinite(mult)) {
                SCALE.put(entity, mult);
            }
        } catch (Throwable ignored) {

        }
    }

    public static float get(Object entity) {
        if (entity == null) return 1f;
        Float f = SCALE.get(entity);
        return (f == null || f <= 0f) ? 1f : f;
    }
}
