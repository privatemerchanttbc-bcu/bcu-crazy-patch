package manualcontrol.crazy.collision;

import common.system.P;
import common.system.fake.FakeGraphics;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Method;

public final class SpriteBounds {

    public static volatile float RAT = 0.32f;

    public static final class WorldBox {
        public final float x0, y0, x1, y1;
        public WorldBox(float x0, float y0, float x1, float y1) {
            this.x0 = Math.min(x0, x1);
            this.y0 = Math.min(y0, y1);
            this.x1 = Math.max(x0, x1);
            this.y1 = Math.max(y0, y1);
        }
        public boolean overlaps(WorldBox o) {
            return o != null && this.x0 <= o.x1 && o.x0 <= this.x1
                    && this.y0 <= o.y1 && o.y0 <= this.y1;
        }
        public boolean overlapsX(WorldBox o) {
            return o != null && this.x0 <= o.x1 && o.x0 <= this.x1;
        }
        @Override public String toString() {
            return "WorldBox[x " + Math.round(x0) + ".." + Math.round(x1)
                    + ", y " + Math.round(y0) + ".." + Math.round(y1) + "]";
        }
    }

    public static final class ShadowMetrics {
        public final float centerX;
        public final float centerY;
        public final float width;
        public final int partIndex;

        ShadowMetrics(float centerX, float centerY, float width, int partIndex) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.partIndex = partIndex;
        }
    }

    private SpriteBounds() {}

    private static volatile Method drawMethod;

    static final MeasuringGraphics.AlphaRectProvider ALPHA_PROVIDER =
            new MeasuringGraphics.AlphaRectProvider() {
                @Override public float[] alphaRect(common.system.fake.FakeImage image) {
                    return AlphaBounds.normalized(image);
                }
                @Override public boolean exclude(common.system.fake.FakeImage image) {
                    return AlphaBounds.EXCLUDE_SHADOWS && AlphaBounds.isShadow(image);
                }
            };

    private static final MeasuringGraphics.AlphaRectProvider SHADOW_ALPHA_PROVIDER =
            new MeasuringGraphics.AlphaRectProvider() {
                @Override public float[] alphaRect(common.system.fake.FakeImage image) {
                    return AlphaBounds.normalized(image);
                }
            };

    public static final class Silhouette {
        public final WorldBox box;
        public final java.util.List<MeasuringGraphics.PartQuad> quads;
        public Silhouette(WorldBox box, java.util.List<MeasuringGraphics.PartQuad> quads) {
            this.box = box;
            this.quads = quads;
        }
    }

    public static java.util.List<Silhouette> localComponentsOfAnimation(
            Object anim, boolean mirror, float mirrorPivot) {
        if (anim == null) return java.util.Collections.emptyList();
        try {
            Object raw = BCUFields.get(anim, "order");
            if (!(raw instanceof Object[])) return java.util.Collections.emptyList();
            Object[] order = (Object[]) raw;
            java.util.Map<Integer, MeasuringGraphics> groups =
                    new java.util.LinkedHashMap<Integer, MeasuringGraphics>();
            for (int i = 0; i < order.length; i++) {
                if (!(order[i] instanceof common.util.anim.EPart)) continue;
                common.util.anim.EPart part = (common.util.anim.EPart) order[i];
                common.util.anim.EPart root = part;
                for (int guard = 0; guard < order.length; guard++) {
                    common.util.anim.EPart parent = root.getFa();
                    if (parent == null || parent == root) break;
                    common.util.anim.EPart grand = parent.getFa();
                    if (grand == null || grand == parent) break;
                    root = parent;
                }
                Integer key = Integer.valueOf(root.getInd());
                MeasuringGraphics mg = groups.get(key);
                if (mg == null) {
                    mg = new MeasuringGraphics(ALPHA_PROVIDER, true);
                    groups.put(key, mg);
                }
                try { part.drawPart(mg, new P(1f, 1f)); }
                catch (Throwable ignored) {}
            }

            java.util.List<Silhouette> out = new java.util.ArrayList<Silhouette>();
            for (MeasuringGraphics mg : groups.values()) {
                if (mg == null || mg.quads().isEmpty()) continue;
                Silhouette component = localFromMeasurement(mg, mirror, mirrorPivot);
                if (component != null && component.box != null) out.add(component);
            }
            return out;
        } catch (Throwable ignored) {
            return java.util.Collections.emptyList();
        }
    }

    private static Silhouette localFromMeasurement(MeasuringGraphics mg,
                                                   boolean mirror,
                                                   float mirrorPivot) {
        if (mg == null || mg.quads().isEmpty()) return null;
        float pivot = mirror && finite(mirrorPivot) ? mirrorPivot : 0f;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        java.util.List<MeasuringGraphics.PartQuad> quads =
                new java.util.ArrayList<MeasuringGraphics.PartQuad>(mg.quads().size());
        for (MeasuringGraphics.PartQuad quad : mg.quads()) {
            if (quad == null || quad.pts == null || quad.pts.length < 8) continue;
            float[] points = new float[8];
            for (int i = 0; i < 4; i++) {
                float x = quad.pts[i * 2];
                if (mirror) x = 2f * pivot - x;
                float y = quad.pts[i * 2 + 1];
                points[i * 2] = x;
                points[i * 2 + 1] = y;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            quads.add(new MeasuringGraphics.PartQuad(points, quad.image));
        }
        if (quads.isEmpty() || !finite(minX) || !finite(minY)
                || !finite(maxX) || !finite(maxY)) return null;
        return new Silhouette(new WorldBox(minX, minY, maxX, maxY), quads);
    }

    public static WorldBox of(Object entity) {
        Silhouette s = silhouetteOf(entity, false);
        return s == null ? null : s.box;
    }

    public static Silhouette silhouetteOf(Object entity) {
        return silhouetteOf(entity, true);
    }

    public static Silhouette silhouetteOfAnimation(Object anim, float pos,
                                                   float layer,
                                                   float spriteScale,
                                                   boolean mirror) {
        return silhouetteOfAnimation(anim, pos, layer, spriteScale, mirror, Float.NaN);
    }

    public static Silhouette silhouetteOfAnimation(Object anim, float pos,
                                                   float layer,
                                                   float spriteScale,
                                                   boolean mirror,
                                                   float mirrorPivot) {
        if (anim == null || Float.isNaN(pos) || Float.isNaN(layer)) return null;
        try {
            MeasuringGraphics mg = new MeasuringGraphics(ALPHA_PROVIDER, true);
            Method method = drawMethod;
            if (method == null || method.getDeclaringClass() != anim.getClass()) {
                method = BCUFields.method(anim.getClass(), "draw",
                        FakeGraphics.class, P.class, float.class);
                drawMethod = method;
            }
            method.invoke(anim, mg, new P(0f, 0f), 1f);
            if (!mg.hasBox()) return null;
            float inv = Math.max(.01f, spriteScale) / RAT;
            float pivot = mirror
                    ? (finite(mirrorPivot) ? mirrorPivot : footCenterX(mg)) : 0f;
            float wx0 = pos + (mirror ? 2f * pivot - mg.maxX() : mg.minX()) * inv;
            float wx1 = pos + (mirror ? 2f * pivot - mg.minX() : mg.maxX()) * inv;
            float layerY = layer * 4f / RAT;
            WorldBox box = new WorldBox(wx0, mg.minY() * inv + layerY,
                    wx1, mg.maxY() * inv + layerY);
            java.util.List<MeasuringGraphics.PartQuad> quads =
                    new java.util.ArrayList<MeasuringGraphics.PartQuad>();
            for (MeasuringGraphics.PartQuad quad : mg.quads()) {
                float[] world = new float[8];
                for (int i = 0; i < 4; i++) {
                    float localX = quad.pts[i * 2];
                    world[i * 2] = pos + (mirror
                            ? 2f * pivot - localX : localX) * inv;
                    world[i * 2 + 1] = quad.pts[i * 2 + 1] * inv + layerY;
                }
                quads.add(new MeasuringGraphics.PartQuad(world, quad.image));
            }
            return new Silhouette(box, quads);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Silhouette localOfAnimation(Object anim, boolean mirror) {
        return silhouetteOfAnimation(anim, 0f, 0f, RAT, mirror);
    }

    public static Silhouette localOfAnimation(Object anim, boolean mirror,
                                              float mirrorPivot) {
        return silhouetteOfAnimation(anim, 0f, 0f, RAT, mirror, mirrorPivot);
    }

    public static Silhouette transformed(Silhouette local, float pos, float layer, float spriteScale) {
        return transformed(local, pos, layer, spriteScale, 0f);
    }

    public static Silhouette transformed(Silhouette local, float pos, float layer,
                                         float spriteScale, float localYOffset) {
        if (local == null || local.box == null) return null;
        float k = Math.max(.01f, spriteScale) / RAT;
        float dy = layer * 4f / RAT + localYOffset * k;
        WorldBox box = new WorldBox(pos + local.box.x0 * k, local.box.y0 * k + dy,
                pos + local.box.x1 * k, local.box.y1 * k + dy);
        java.util.List<MeasuringGraphics.PartQuad> quads;
        if (local.quads == null || local.quads.isEmpty()) {
            quads = java.util.Collections.emptyList();
        } else {
            quads = new java.util.ArrayList<MeasuringGraphics.PartQuad>(local.quads.size());
            for (MeasuringGraphics.PartQuad q : local.quads) {
                float[] w = new float[8];
                for (int i = 0; i < 4; i++) {
                    w[i * 2] = pos + q.pts[i * 2] * k;
                    w[i * 2 + 1] = q.pts[i * 2 + 1] * k + dy;
                }
                quads.add(new MeasuringGraphics.PartQuad(w, q.image));
            }
        }
        return new Silhouette(box, quads);
    }

    public static Silhouette rotated(Silhouette source, float pivotX, float pivotY,
                                     float angle) {
        if (source == null || source.box == null || Math.abs(angle) < .00001f)
            return source;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        java.util.List<MeasuringGraphics.PartQuad> rotated =
                new java.util.ArrayList<MeasuringGraphics.PartQuad>();
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        if (source.quads != null) {
            for (MeasuringGraphics.PartQuad quad : source.quads) {
                if (quad == null || quad.pts == null || quad.pts.length < 8) continue;
                float[] points = new float[8];
                for (int i = 0; i < 4; i++) {
                    float dx = quad.pts[i * 2] - pivotX;
                    float dy = quad.pts[i * 2 + 1] - pivotY;
                    float x = pivotX + dx * cos - dy * sin;
                    float y = pivotY + dx * sin + dy * cos;
                    points[i * 2] = x;
                    points[i * 2 + 1] = y;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
                rotated.add(new MeasuringGraphics.PartQuad(points, quad.image));
            }
        }
        if (rotated.isEmpty()) {
            float[] corners = {source.box.x0, source.box.y0, source.box.x1, source.box.y0,
                    source.box.x1, source.box.y1, source.box.x0, source.box.y1};
            for (int i = 0; i < 4; i++) {
                float dx = corners[i * 2] - pivotX;
                float dy = corners[i * 2 + 1] - pivotY;
                float x = pivotX + dx * cos - dy * sin;
                float y = pivotY + dx * sin + dy * cos;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new Silhouette(new WorldBox(minX, minY, maxX, maxY), rotated);
    }

    public static float shadowCenterX(Object entity) {
        try {
            Object am = BCUFields.get(entity, "anim");
            if (am == null) return Float.NaN;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return Float.NaN;
            MeasuringGraphics mg = new MeasuringGraphics(ALPHA_PROVIDER, true);
            Method m = drawMethod;
            if (m == null || m.getDeclaringClass() != anim.getClass()) {
                m = BCUFields.method(anim.getClass(), "draw",
                        FakeGraphics.class, P.class, float.class);
                drawMethod = m;
            }
            m.invoke(anim, mg, new P(0f, 0f), 1f);
            if (!mg.hasBox()) return Float.NaN;
            return footCenterX(mg);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    public static int shadowPartIndex(Object entity) {
        try {
            Object am = BCUFields.get(entity, "anim");
            Object anim = am == null ? null : BCUFields.get(am, "anim");
            if (anim == null) return -1;
            Object[] order = (Object[]) BCUFields.get(anim, "order");
            if (order == null || order.length == 0) return -1;

            float[][] boxes = new float[order.length][];
            float bottom = -Float.MAX_VALUE, top = Float.MAX_VALUE;
            for (int i = 0; i < order.length; i++) {
                if (!(order[i] instanceof common.util.anim.EPart)) continue;
                MeasuringGraphics mg = new MeasuringGraphics(ALPHA_PROVIDER, true);
                ((common.util.anim.EPart) order[i]).drawPart(mg, new P(1f, 1f));
                if (!mg.hasBox()) continue;
                boxes[i] = new float[]{mg.minX(), mg.minY(), mg.maxX(), mg.maxY()};
                bottom = Math.max(bottom, mg.maxY());
                top = Math.min(top, mg.minY());
            }
            if (bottom <= top) return -1;
            float band = Math.max(8f, (bottom - top) * 0.30f);
            int best = -1;
            float bestW = -1f;
            for (int i = 0; i < boxes.length; i++) {
                float[] b = boxes[i];
                if (b == null) continue;
                float w = b[2] - b[0], h = b[3] - b[1];
                float cy = (b[1] + b[3]) * 0.5f;
                if (w < 4f) continue;
                if (cy < bottom - band) continue;
                if (h > w * 0.7f) continue;
                if (w > bestW) { bestW = w; best = i; }
            }
            return best;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static ShadowMetrics shadowMetrics(Object entity) {
        try {
            Object am = BCUFields.get(entity, "anim");
            Object anim = am == null ? null : BCUFields.get(am, "anim");
            return shadowMetricsOfAnimation(anim);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static ShadowMetrics shadowMetricsOfAnimation(Object anim) {
        try {
            if (anim == null) return null;
            Object[] order = (Object[]) BCUFields.get(anim, "order");
            if (order == null || order.length == 0) return null;

            float[][] boxes = new float[order.length][];
            boolean[] explicitShadow = new boolean[order.length];
            float bottom = -Float.MAX_VALUE;
            float top = Float.MAX_VALUE;
            for (int i = 0; i < order.length; i++) {
                if (!(order[i] instanceof common.util.anim.EPart)) continue;
                MeasuringGraphics mg = new MeasuringGraphics(SHADOW_ALPHA_PROVIDER, true);
                ((common.util.anim.EPart) order[i]).drawPart(mg, new P(1f, 1f));
                if (!mg.hasBox()) continue;
                boxes[i] = new float[]{mg.minX(), mg.minY(), mg.maxX(), mg.maxY()};
                bottom = Math.max(bottom, mg.maxY());
                top = Math.min(top, mg.minY());
                for (MeasuringGraphics.PartQuad q : mg.quads()) {
                    if (q.image != null && AlphaBounds.isShadow(q.image)) {
                        explicitShadow[i] = true;
                        break;
                    }
                }
            }
            if (bottom <= top) return null;

            float band = Math.max(8f, (bottom - top) * 0.30f);
            int best = -1;
            float bestW = -1f;
            for (int i = 0; i < boxes.length; i++) {
                float[] b = boxes[i];
                if (b == null || !explicitShadow[i]) continue;
                float w = b[2] - b[0];
                float h = b[3] - b[1];
                float cy = (b[1] + b[3]) * 0.5f;
                if (w < 4f || cy < bottom - band || h > w * 0.85f) continue;
                if (w > bestW) {
                    bestW = w;
                    best = i;
                }
            }

            if (best < 0) {
                for (int i = 0; i < boxes.length; i++) {
                    float[] b = boxes[i];
                    if (b == null) continue;
                    float w = b[2] - b[0];
                    float h = b[3] - b[1];
                    float cy = (b[1] + b[3]) * 0.5f;
                    if (w < 4f || cy < bottom - band || h > w * 0.7f) continue;
                    if (w > bestW) {
                        bestW = w;
                        best = i;
                    }
                }
            }

            if (best < 0 || bestW < 1f || Float.isNaN(bestW)
                    || Float.isInfinite(bestW)) {
                return null;
            }
            float center = (boxes[best][0] + boxes[best][2]) * 0.5f;
            float centerY = (boxes[best][1] + boxes[best][3]) * 0.5f;
            if (!finite(center) || !finite(centerY)) return null;
            return new ShadowMetrics(center, centerY, bestW, best);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float footCenterX(MeasuringGraphics mg) {
        float spriteBottom = mg.maxY();
        float spriteH = mg.maxY() - mg.minY();
        float band = Math.max(8f, spriteH * 0.30f);

        float bestW = -1f, shadowCx = Float.NaN;
        for (MeasuringGraphics.PartQuad q : mg.quads()) {
            float minx = min4(q.pts, 0), maxx = max4(q.pts, 0);
            float miny = min4(q.pts, 1), maxy = max4(q.pts, 1);
            float w = maxx - minx, h = maxy - miny;
            if (w < 4f) continue;
            if ((miny + maxy) * 0.5f < spriteBottom - band) continue;
            if (h > w * 0.7f) continue;
            if (w > bestW) { bestW = w; shadowCx = (minx + maxx) * 0.5f; }
        }
        if (shadowCx == shadowCx) return shadowCx;

        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (MeasuringGraphics.PartQuad q : mg.quads()) {
            if (max4(q.pts, 1) < spriteBottom - band) continue;
            lo = Math.min(lo, min4(q.pts, 0));
            hi = Math.max(hi, max4(q.pts, 0));
        }
        if (hi >= lo) return (lo + hi) * 0.5f;

        return (mg.minX() + mg.maxX()) * 0.5f;
    }

    public static float[] pivotAndTop(Object entity) {
        try {
            Object am = BCUFields.get(entity, "anim");
            if (am == null) return null;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return null;
            MeasuringGraphics mg = new MeasuringGraphics(ALPHA_PROVIDER, true);
            Method m = drawMethod;
            if (m == null || m.getDeclaringClass() != anim.getClass()) {
                m = BCUFields.method(anim.getClass(), "draw",
                        FakeGraphics.class, P.class, float.class);
                drawMethod = m;
            }
            m.invoke(anim, mg, new P(0f, 0f), 1f);
            if (!mg.hasBox()) return null;
            return new float[]{footCenterX(mg), mg.minY()};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float min4(float[] p, int off) {
        return Math.min(Math.min(p[off], p[off + 2]), Math.min(p[off + 4], p[off + 6]));
    }

    private static float max4(float[] p, int off) {
        return Math.max(Math.max(p[off], p[off + 2]), Math.max(p[off + 4], p[off + 6]));
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static Silhouette silhouetteOf(Object entity, boolean wantQuads) {
        if (entity == null) return null;
        try {
            Object am = BCUFields.get(entity, "anim");
            if (am == null) return null;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return null;
            return measure(entity, anim, wantQuads);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Silhouette measure(Object entity, Object anim, boolean wantQuads) throws Exception {
        MeasuringGraphics mg = new MeasuringGraphics(ALPHA_PROVIDER, wantQuads);
        Method m = drawMethod;
        if (m == null || m.getDeclaringClass() != anim.getClass()) {
            m = BCUFields.method(anim.getClass(), "draw",
                    FakeGraphics.class, P.class, float.class);
            drawMethod = m;
        }
        m.invoke(anim, mg, new P(0f, 0f), 1f);
        if (!mg.hasBox()) return null;

        float pos = BCUFields.getFloat(entity, "pos");

        boolean mirror = manualcontrol.hooks.DrawHook.shouldMirrorForCurrentFacing(entity);
        float sign = mirror ? -1f : 1f;

        float inv = SpriteScale.get(entity) / RAT;

        float advOff = 0f;
        if (mirror) {
            float c = manualcontrol.adventure.AdventureBridge.mirrorCenter(entity);
            if (c == c) advOff = 2f * c * inv;
        }

        float wxA = pos + sign * mg.minX() * inv + advOff;
        float wxB = pos + sign * mg.maxX() * inv + advOff;

        float layerY = 0f;
        try {
            layerY = BCUFields.getInt(entity, "currentLayer") * 4f / RAT;
        } catch (Throwable ignored) {}
        float wy0 = mg.minY() * inv + layerY;
        float wy1 = mg.maxY() * inv + layerY;
        WorldBox box = new WorldBox(wxA, wy0, wxB, wy1);

        java.util.List<MeasuringGraphics.PartQuad> quads;
        if (wantQuads) {
            java.util.List<MeasuringGraphics.PartQuad> local = mg.quads();
            quads = new java.util.ArrayList<MeasuringGraphics.PartQuad>(local.size());
            for (MeasuringGraphics.PartQuad q : local) {
                float[] w = new float[8];
                for (int i = 0; i < 4; i++) {
                    w[i * 2] = pos + sign * q.pts[i * 2] * inv + advOff;
                    w[i * 2 + 1] = q.pts[i * 2 + 1] * inv + layerY;
                }
                quads.add(new MeasuringGraphics.PartQuad(w, q.image));
            }
        } else {
            quads = java.util.Collections.emptyList();
        }
        return new Silhouette(box, quads);
    }
}
