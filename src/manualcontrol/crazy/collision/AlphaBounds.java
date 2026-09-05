package manualcontrol.crazy.collision;

import common.system.fake.FakeImage;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class AlphaBounds {

    public static volatile int ALPHA_THRESHOLD = 40;

    public static volatile int SHADOW_LUMA_MAX = 40;

    public static volatile boolean EXCLUDE_SHADOWS = true;

    private static final Map<FakeImage, float[]> CACHE =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, float[]>());

    private static final Map<FakeImage, Boolean> SHADOW =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, Boolean>());

    public static final class Mask {
        public final int gw, gh;
        private final boolean[] cells;
        Mask(int gw, int gh, boolean[] cells) {
            this.gw = gw;
            this.gh = gh;
            this.cells = cells;
        }
        public boolean opaque(float u, float v) {
            if (u < 0f || v < 0f || u >= 1f || v >= 1f) return false;
            return cells[(int) (v * gh) * gw + (int) (u * gw)];
        }
    }

    public static volatile int MASK_RES = 48;

    private static final Map<FakeImage, Mask> MASKS =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, Mask>());

    private static final Map<FakeImage, Float> FILL =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, Float>());

    private static final Map<Object, Boolean> WARMED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private static volatile Method partsMethod;
    private static volatile Class<?> partsMethodOwner;

    private AlphaBounds() {}

    public static float[] uv(FakeImage img) {
        if (img == null) return null;
        float[] c = CACHE.get(img);
        return (c != null && c.length == 4) ? c : null;
    }

    public static float[] normalized(FakeImage img) {
        float[] uv = uv(img);
        if (uv == null) return null;
        try {
            int iw = img.getWidth();
            int ih = img.getHeight();
            if (iw <= 0 || ih <= 0) return null;
            return new float[]{
                    uv[0] / iw, uv[1] / ih,
                    (uv[2] + 1f) / iw, (uv[3] + 1f) / ih
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void warmEntity(Object entity) {
        if (entity == null || WARMED.containsKey(entity)) return;
        try {
            Object am = BCUFields.get(entity, "anim");
            if (am == null) return;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return;
            warmAnimation(anim);
            WARMED.put(entity, Boolean.TRUE);
        } catch (Throwable ignored) {

        }
    }

    public static int[] warmAnimation(Object anim) {
        int[] stats = new int[2];
        if (anim == null) return stats;
        try {
            Object model = BCUFields.get(anim, "mamodel");
            int[][] parts = (int[][]) BCUFields.get(model, "parts");
            if (parts == null) return stats;

            Object animDef = BCUFields.invoke(anim, "anim");
            if (animDef == null) return stats;
            Method pm = partsMethod;
            if (pm == null || partsMethodOwner != animDef.getClass()) {
                pm = BCUFields.method(animDef.getClass(), "parts", int.class);
                partsMethod = pm;
                partsMethodOwner = animDef.getClass();
            }

            for (int[] part : parts) {
                if (part == null || part.length < 3) continue;
                int img = part[2];
                if (img < 0) continue;
                Object fi;
                try {
                    fi = pm.invoke(animDef, img);
                } catch (Throwable ignored) {
                    continue;
                }
                if (fi instanceof FakeImage) {
                    warm((FakeImage) fi);
                    stats[0]++;
                    if (uv((FakeImage) fi) != null) stats[1]++;
                }
            }
        } catch (Throwable ignored) {

        }
        return stats;
    }

    public static boolean isShadow(FakeImage img) {
        if (img == null) return false;
        Boolean b = SHADOW.get(img);
        return b != null && b.booleanValue();
    }

    public static Mask mask(FakeImage img) {
        return img == null ? null : MASKS.get(img);
    }

    public static float fillRatio(FakeImage img) {
        if (img == null) return 1f;
        Float f = FILL.get(img);
        return f == null ? 1f : f.floatValue();
    }

    public static void warm(FakeImage img) {
        if (img == null || CACHE.containsKey(img)) return;
        float[] result;
        boolean shadow = false;
        try {
            int w = Math.max(0, img.getWidth());
            int h = Math.max(0, img.getHeight());
            int minX = w, minY = h, maxX = -1, maxY = -1;
            int maxLuma = -1;
            int visible = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    int a = (rgb >>> 24) & 255;
                    if (a <= ALPHA_THRESHOLD) continue;
                    visible++;
                    int lum = Math.max((rgb >>> 16) & 255, Math.max((rgb >>> 8) & 255, rgb & 255));
                    if (lum > maxLuma) maxLuma = lum;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
            boolean any = maxX >= minX && maxY >= minY;
            result = any ? new float[]{minX, minY, maxX, maxY} : new float[0];

            shadow = any && maxLuma <= SHADOW_LUMA_MAX;
            if (any) {
                int tw = maxX - minX + 1;
                int th = maxY - minY + 1;
                FILL.put(img, Math.min(1f, visible / (float) Math.max(1, tw * th)));
            }

            if (any) {
                int tw = maxX - minX + 1;
                int th = maxY - minY + 1;
                int gw = Math.max(1, Math.min(MASK_RES, tw));
                int gh = Math.max(1, Math.min(MASK_RES, th));
                boolean[] cells = new boolean[gw * gh];
                for (int y = minY; y <= maxY; y++) {
                    int cy = (y - minY) * gh / th;
                    for (int x = minX; x <= maxX; x++) {
                        int a = (img.getRGB(x, y) >>> 24) & 255;
                        if (a <= ALPHA_THRESHOLD) continue;
                        cells[cy * gw + (x - minX) * gw / tw] = true;
                    }
                }
                MASKS.put(img, new Mask(gw, gh, cells));
            }
        } catch (Throwable ignored) {
            result = new float[0];
        }
        CACHE.put(img, result);
        SHADOW.put(img, shadow ? Boolean.TRUE : Boolean.FALSE);
    }
}
