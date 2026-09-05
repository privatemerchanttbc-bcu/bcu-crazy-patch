package manualcontrol.reflect;

public final class EntityAccess {

    public static final String CLASS_ENTITY = "common.battle.entity.Entity";
    public static final String CLASS_EUNIT = "common.battle.entity.EUnit";
    public static final String CLASS_EENEMY = "common.battle.entity.EEnemy";
    public static final String CLASS_ECASTLE = "common.battle.entity.ECastle";

    private EntityAccess() {}

    public static float getPos(Object entity) {
        return BCUFields.getFloat(entity, "pos");
    }

    public static void setPos(Object entity, float pos) {
        try {
            BCUFields.field(entity.getClass(), "pos").setFloat(entity, pos);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set pos", e);
        }
    }

    public static int getDire(Object entity) {
        return BCUFields.getInt(entity, "dire");
    }

    public static int getLayer(Object entity) {
        return BCUFields.getInt(entity, "currentLayer");
    }

    public static void setLayer(Object entity, int layer) {
        try {
            BCUFields.field(entity.getClass(), "currentLayer").setInt(entity, layer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set currentLayer", e);
        }
    }

    public static boolean isDead(Object entity) {
        return BCUFields.getBoolean(entity, "dead");
    }

    public static boolean isBase(Object entity) {
        try {
            return (boolean) BCUFields.invoke(entity, "isBase");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isBoss(Object entity) {
        if (!entity.getClass().getName().equals(CLASS_EENEMY)) return false;
        try {
            int mark = BCUFields.getInt(entity, "mark");
            return mark >= 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPlayerUnit(Object entity) {
        return getDire(entity) == -1 && !isBase(entity)
                && entity.getClass().getName().equals(CLASS_EUNIT);
    }

    public static boolean isEnemyUnit(Object entity) {
        return getDire(entity) == 1 && !isBase(entity)
                && entity.getClass().getName().equals(CLASS_EENEMY);
    }

    public static final class SpriteOverlay {
        public final int centerX;
        public final int centerY;
        public final int radius;

        private SpriteOverlay(int centerX, int centerY, int radius) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
        }
    }

    public static final class SpriteBounds {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final float centerX;
        public final float centerY;

        private SpriteBounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.centerX = (left + right) * 0.5f;
            this.centerY = (top + bottom) * 0.5f;
        }

        public boolean contains(float x, float y, float padding) {
            return x >= left - padding && x <= right + padding
                    && y >= top - padding && y <= bottom + padding;
        }
    }

    private static long lastOverlayLog = 0L;
    private static long lastAnchorOverlayLog = 0L;

    public static SpriteOverlay overlayFromAnchor(Object entity, int anchorX, int anchorY, float siz) {
        float psiz = Math.max(0.05f, siz * 0.8f);
        float localCX = 0f, localCY = 0f, visualW = 0f, visualH = 0f;
        boolean ok = false;
        try {
            float[] b = estimateVisualModelBounds(entity);
            localCX = b[0];
            localCY = b[1];
            visualW = b[2];
            visualH = b[3];
            if (visualW > 1f && visualH > 1f && visualW < 5000f && visualH < 5000f) ok = true;
        } catch (Throwable ignored) {}

        int centerX, centerY;
        float radius;
        if (ok) {

            float halfH = visualH * psiz * 0.5f;
            radius = Math.max(visualW, visualH) * psiz * 0.5f + 6f;

            centerX = anchorX;
            centerY = Math.round(anchorY - halfH);
        } else {

            float width = 0f;
            int dire = 0;
            try {
                Object data = BCUFields.get(entity, "data");
                Object w = BCUFields.method(data.getClass(), "getWidth").invoke(data);
                if (w instanceof Number) width = ((Number) w).floatValue();
            } catch (Throwable ignored) {}
            try { dire = getDire(entity); } catch (Throwable ignored) {}
            radius = width > 1f ? clamp(width * 0.32f * siz * 0.5f + 8f, 30f, 150f) : 56f;
            centerX = anchorX;
            centerY = Math.round(anchorY - radius * 0.8f);
        }
        radius = clamp(radius, 30f, 150f);

        long now = System.currentTimeMillis();
        if (now - lastAnchorOverlayLog > 1000L) {
            manualcontrol.Logger.log("AnchorOverlay: entity="
                    + (entity == null ? "null" : entity.getClass().getSimpleName())
                    + " anchor=(" + anchorX + "," + anchorY + ")"
                    + " center=(" + centerX + "," + centerY + ")"
                    + " radius=" + Math.round(radius)
                    + " psiz=" + String.format("%.3f", psiz)
                    + " modelOK=" + ok
                    + " localCenter=(" + Math.round(localCX) + "," + Math.round(localCY) + ")"
                    + " modelWH=(" + Math.round(visualW) + "x" + Math.round(visualH) + ")");
            lastAnchorOverlayLog = now;
        }
        return new SpriteOverlay(centerX, centerY, Math.round(radius));
    }

    private static long lastBoxOverlayLog = 0L;

    public static SpriteOverlay overlayFromBox(float minX, float minY, float maxX, float maxY,
                                               float bodyCX, float bodyCY) {
        float w = maxX - minX;
        float h = maxY - minY;

        int centerX = Math.round(bodyCX);
        int centerY = Math.round(bodyCY);

        float radius = Math.max(w, h) * 0.5f + 10f;
        radius = clamp(radius, 28f, 240f);

        long now = System.currentTimeMillis();
        if (now - lastBoxOverlayLog > 1000L) {
            manualcontrol.Logger.log("BoxOverlay: box=(" + Math.round(minX) + "," + Math.round(minY)
                    + ")-(" + Math.round(maxX) + "," + Math.round(maxY) + ")"
                    + " boxCenter=(" + Math.round((minX + maxX) * 0.5f) + "," + Math.round((minY + maxY) * 0.5f) + ")"
                    + " bodyCenter=(" + centerX + "," + centerY + ")"
                    + " anchor=(" + Math.round(manualcontrol.SpriteAnchor.getX()) + "," + Math.round(manualcontrol.SpriteAnchor.getY()) + ")"
                    + " size=(" + Math.round(w) + "x" + Math.round(h) + ")"
                    + " radius=" + Math.round(radius));
            lastBoxOverlayLog = now;
        }
        return new SpriteOverlay(centerX, centerY, Math.round(radius));
    }

    public static SpriteOverlay estimateSpriteOverlay(Object entity, float siz, int rootX, int rootY) {
        float radius = 0f;
        float offsetX = 0f;
        float centerLocalX = 0f;
        float visualW = 0f;
        float visualH = 0f;
        boolean usedVisualBounds = false;

        try {
            float[] bounds = estimateVisualModelBounds(entity);
            centerLocalX = bounds[0];
            visualW = bounds[2];
            visualH = bounds[3];
            if (visualW > 1f && visualH > 1f && visualW < 2500f && visualH < 2500f) {
                float drawScale = Math.max(0.05f, siz * 0.8f);
                radius = Math.max(visualW, visualH) * drawScale * 0.22f + 8f;
                offsetX = centerLocalX * drawScale;
                float maxOffset = Math.max(26f, Math.min(140f, visualW * drawScale * 0.62f));
                offsetX = clamp(offsetX, -maxOffset, maxOffset);
                usedVisualBounds = true;
            }
        } catch (Throwable ignored) {}

        if (radius <= 0f || Float.isNaN(radius) || Float.isInfinite(radius)) {
            try {
                Object data = BCUFields.get(entity, "data");
                Object width = BCUFields.method(data.getClass(), "getWidth").invoke(data);
                if (width instanceof Number) {
                    radius = ((Number) width).floatValue() * 0.32f * siz * 0.45f;
                }
            } catch (Throwable ignored) {}
        }

        if (radius <= 0f || Float.isNaN(radius) || Float.isInfinite(radius)) {
            radius = 62f;
        }

        radius = clamp(radius, 30f, 68f);
        float offsetY = radius * 0.56f;
        int centerX = Math.round(rootX + offsetX);
        int centerY = Math.round(rootY + offsetY);
        int screenRadius = Math.round(radius);

        long now = System.currentTimeMillis();
        if ((rootX != 0 || rootY != 0) && now - lastOverlayLog > 1000L) {
            manualcontrol.Logger.log("SpriteOverlay: entity="
                    + (entity == null ? "null" : entity.getClass().getSimpleName())
                    + " root=(" + rootX + "," + rootY + ")"
                    + " center=(" + centerX + "," + centerY + ")"
                    + " offsetX=" + Math.round(offsetX)
                    + " radius=" + screenRadius
                    + " visual=" + usedVisualBounds
                    + " localCenterX=" + Math.round(centerLocalX)
                    + " bounds=(" + Math.round(visualW) + "x" + Math.round(visualH) + ")");
            lastOverlayLog = now;
        }

        return new SpriteOverlay(centerX, centerY, screenRadius);
    }

    public static int estimateScreenRadius(Object entity, float siz) {
        try {
            return estimateSpriteOverlay(entity, siz, 0, 0).radius;
        } catch (Throwable ignored) {}

        return 62;
    }

    public static SpriteBounds estimateSpriteBounds(Object entity, float siz, float rootX, float rootY) {
        float drawScale = Math.max(0.05f, siz * 0.8f);
        float visualW = 0f;
        float visualH = 0f;
        boolean ok = false;

        try {
            float[] bounds = estimateVisualModelBounds(entity);
            visualW = Math.abs(bounds[2]) * drawScale;
            visualH = Math.abs(bounds[3]) * drawScale;
            if (visualW > 1f && visualH > 1f && visualW < 5000f && visualH < 5000f) {
                ok = true;
            }
        } catch (Throwable ignored) {}

        if (!ok) {
            try {
                Object data = BCUFields.get(entity, "data");
                Object width = BCUFields.method(data.getClass(), "getWidth").invoke(data);
                if (width instanceof Number) {
                    visualW = Math.max(30f, ((Number) width).floatValue() * 0.32f * siz);
                    visualH = Math.max(45f, visualW * 1.25f);
                    ok = true;
                }
            } catch (Throwable ignored) {}
        }

        if (!ok) {
            visualW = 90f * Math.max(0.5f, siz);
            visualH = 115f * Math.max(0.5f, siz);
        }

        float frontPad = Math.max(8f, visualW * 0.12f);
        float bottomPad = Math.max(6f, visualH * 0.08f);
        int dire = 1;
        try { dire = getDire(entity); } catch (Throwable ignored) {}

        float left;
        float right;
        if (dire >= 0) {
            left = rootX - visualW;
            right = rootX + frontPad;
        } else {
            left = rootX - frontPad;
            right = rootX + visualW;
        }
        float top = rootY - visualH;
        float bottom = rootY + bottomPad;
        return new SpriteBounds(left, top, right, bottom);
    }

    private static float[] estimateVisualModelBounds(Object entity) throws Exception {
        Object animManager = BCUFields.get(entity, "anim");
        Object currentAnim = BCUFields.get(animManager, "anim");
        Object model = BCUFields.get(currentAnim, "mamodel");
        int[][] parts = (int[][]) BCUFields.get(model, "parts");
        int[] ints = (int[]) BCUFields.get(model, "ints");
        float scaleBase = ints != null && ints.length > 0 && ints[0] != 0 ? Math.abs(ints[0]) : 1000f;

        Object animDef = BCUFields.method(currentAnim.getClass(), "anim").invoke(currentAnim);
        java.lang.reflect.Method partsMethod = BCUFields.method(animDef.getClass(), "parts", int.class);

        float minX = 0f;
        float maxX = 0f;
        float minY = 0f;
        float maxY = 0f;
        boolean found = false;

        for (int i = 0; i < parts.length; i++) {
            int[] part = parts[i];
            if (part == null || part.length < 10) continue;
            int img = part[2];
            if (img < 0) continue;

            Object fakeImage;
            try {
                fakeImage = partsMethod.invoke(animDef, img);
            } catch (Throwable ignored) {
                continue;
            }
            if (fakeImage == null) continue;

            float imgW = ((Number) BCUFields.invoke(fakeImage, "getWidth")).floatValue();
            float imgH = ((Number) BCUFields.invoke(fakeImage, "getHeight")).floatValue();
            if (imgW <= 0f || imgH <= 0f) continue;

            float sx = Math.abs(part[8]) / scaleBase;
            float sy = Math.abs(part[9]) / scaleBase;
            if (sx <= 0f) sx = 1f;
            if (sy <= 0f) sy = 1f;

            float px = part[4];
            float py = part[5];
            float pivotX = part.length > 6 ? part[6] : imgW * 0.5f;
            float pivotY = part.length > 7 ? part[7] : imgH * 0.5f;

            float x0 = px - pivotX * sx;
            float y0 = py - pivotY * sy;
            float x1 = x0 + imgW * sx;
            float y1 = y0 + imgH * sy;
            float left = Math.min(x0, x1);
            float right = Math.max(x0, x1);
            float top = Math.min(y0, y1);
            float bottom = Math.max(y0, y1);
            minX = found ? Math.min(minX, left) : left;
            maxX = found ? Math.max(maxX, right) : right;
            minY = found ? Math.min(minY, top) : top;
            maxY = found ? Math.max(maxY, bottom) : bottom;
            found = true;
        }

        if (!found) return new float[]{0f, 0f, 0f, 0f};
        return new float[]{(minX + maxX) * 0.5f, (minY + maxY) * 0.5f, maxX - minX, maxY - minY};
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
