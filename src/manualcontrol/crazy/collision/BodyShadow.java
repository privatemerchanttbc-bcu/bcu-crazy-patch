package manualcontrol.crazy.collision;

import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import common.util.anim.EPart;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class BodyShadow {

    public static volatile boolean ENABLED = true;

    private static final Map<Object, Integer> PARTS =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());

    private static volatile Field imgField;

    private BodyShadow() {}

    public static final class Hidden {
        private final EPart part;
        private final int img;

        Hidden(EPart part, int img) {
            this.part = part;
            this.img = img;
        }
    }

    public static int partIndex(Object entity) {
        if (entity == null) return -1;
        Integer cached = PARTS.get(entity);
        if (cached != null) return cached.intValue();
        int index = SpriteBounds.shadowPartIndex(entity);
        PARTS.put(entity, Integer.valueOf(index));
        return index;
    }

    public static Hidden beginPosed(Object entity, Object am, FakeGraphics g,
                                    P p, float siz, FakeTransform ground,
                                    boolean mirror, float mirrorPivotX) {
        if (!ENABLED || entity == null || am == null || g == null || p == null
                || ground == null) return null;
        try {
            if (BCUFields.getInt(am, "dead") > 0) return null;
            if (BCUFields.get(am, "corpse") != null) return null;
        } catch (Throwable ignored) {
            return null;
        }
        int index = partIndex(entity);
        if (index < 0) return null;
        try {
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return null;
            if (BCUFields.getFloat(anim, "f") < 0f) return null;
            Object[] order = (Object[]) BCUFields.get(anim, "order");
            if (order == null || index >= order.length
                    || !(order[index] instanceof EPart)) return null;
            EPart part = (EPart) order[index];

            Field field = imgField;
            if (field == null || field.getDeclaringClass() != EPart.class) {
                field = BCUFields.field(EPart.class, "img");
                imgField = field;
            }
            int img = field.getInt(part);
            if (img < 0) return null;

            try {
                Method set = BCUFields.method(anim.getClass(), "set",
                        FakeGraphics.class);
                set.invoke(null, g);
            } catch (Throwable ignored) {}

            FakeTransform posed = g.getTransform();
            try {
                g.setTransform(ground);
                if (mirror) {
                    g.translate(mirrorPivotX, 0f);
                    g.scale(-1f, 1f);
                    g.translate(-mirrorPivotX, 0f);
                }
                g.translate(p.x, p.y);
                part.drawPart(g, new P(siz, siz));
            } finally {
                g.setTransform(posed);
                try { g.delete(posed); } catch (Throwable ignored) {}
            }

            field.setInt(part, -1);
            return new Hidden(part, img);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void endPosed(Hidden hidden) {
        if (hidden == null || hidden.part == null) return;
        try {
            Field field = imgField;
            if (field != null) field.setInt(hidden.part, hidden.img);
        } catch (Throwable ignored) {}
    }
}
