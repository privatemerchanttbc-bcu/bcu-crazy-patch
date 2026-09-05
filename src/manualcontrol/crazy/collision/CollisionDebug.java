package manualcontrol.crazy.collision;

import common.system.P;
import common.system.fake.FakeGraphics;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Method;

public final class CollisionDebug {

    public static volatile boolean OVERLAY = false;

    private CollisionDebug() {}

    private static volatile Method drawMethod;

    private static final long ARROW_MS = 900L;
    private static final int MAX_ARROWS = 16;

    public static volatile float ARROW_TICKS = 4f;

    private static final class ImpactArrow {
        final float worldX, worldY;
        final float vx, vzUp;
        final int layer;
        final long ms;
        ImpactArrow(float worldX, float worldY, float vx, float vzUp, int layer) {
            this.worldX = worldX;
            this.worldY = worldY;
            this.vx = vx;
            this.vzUp = vzUp;
            this.layer = layer;
            this.ms = System.currentTimeMillis();
        }
    }

    private static final java.util.ArrayDeque<ImpactArrow> ARROWS =
            new java.util.ArrayDeque<ImpactArrow>();

    public static void recordImpact(float worldX, float worldY, float vx, float vzUp, int layer) {
        if (!OVERLAY) return;
        synchronized (ARROWS) {
            ARROWS.addLast(new ImpactArrow(worldX, worldY, vx, vzUp, layer));
            while (ARROWS.size() > MAX_ARROWS) ARROWS.removeFirst();
        }
    }

    public static void drawImpactVectors(Object bbpainter, FakeGraphics g) {
        if (!OVERLAY || g == null || bbpainter == null) return;
        java.util.List<ImpactArrow> arrows;
        long now = System.currentTimeMillis();
        synchronized (ARROWS) {
            if (ARROWS.isEmpty()) return;
            while (!ARROWS.isEmpty() && now - ARROWS.peekFirst().ms > ARROW_MS) {
                ARROWS.removeFirst();
            }
            if (ARROWS.isEmpty()) return;
            arrows = new java.util.ArrayList<ImpactArrow>(ARROWS);
        }
        common.system.fake.FakeTransform old = CollisionHud.pushIdentityTransform(g);
        try {
            float siz = manualcontrol.reflect.BBPainterAccess.getSiz(bbpainter);
            int stagePos = manualcontrol.reflect.BBPainterAccess.getStagePos(bbpainter);
            int midh = manualcontrol.reflect.BBPainterAccess.getMidh(bbpainter);
            if (Float.isNaN(siz) || Float.isInfinite(siz) || siz <= 0.01f) return;
            for (ImpactArrow a : arrows) {

                float sx = (a.worldX * 0.32f + 200f) * siz + stagePos;
                float groundY = midh - (156f - a.layer * 4f) * siz;
                float sy = groundY + a.worldY * 0.32f * siz;
                if (Float.isNaN(sx) || Float.isNaN(sy)
                        || Math.abs(sx) > 16000f || Math.abs(sy) > 16000f) continue;

                float ex = sx + a.vx * ARROW_TICKS * 0.32f * siz;
                float ey = sy - a.vzUp * ARROW_TICKS * 0.32f * siz;

                g.setColor(255, 230, 60);
                g.drawLine(sx, sy, ex, ey);

                float dx = ex - sx, dy = ey - sy;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) {
                    float ux = dx / len, uy = dy / len;
                    float h = Math.min(12f, len * 0.35f);
                    g.drawLine(ex, ey, ex - h * (ux * 0.866f - uy * 0.5f), ey - h * (uy * 0.866f + ux * 0.5f));
                    g.drawLine(ex, ey, ex - h * (ux * 0.866f + uy * 0.5f), ey - h * (uy * 0.866f - ux * 0.5f));
                }

                g.colRect(sx - 3f, sy - 3f, 6f, 6f, 255, 90, 40, 230);
            }
        } catch (Throwable ignored) {

        } finally {
            CollisionHud.popTransform(g, old);
        }
    }

    public static float[] spriteCenterOffsetPx(Object entity, float siz) {
        if (entity == null) return null;
        try {
            Object am = BCUFields.get(entity, "anim");
            if (am == null) return null;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return null;
            MeasuringGraphics mg = new MeasuringGraphics(SpriteBounds.ALPHA_PROVIDER, true);
            Method m = drawMethod;
            if (m == null || m.getDeclaringClass() != anim.getClass()) {
                m = BCUFields.method(anim.getClass(), "draw",
                        FakeGraphics.class, P.class, float.class);
                drawMethod = m;
            }
            m.invoke(anim, mg, new P(0f, 0f), siz);
            if (!mg.hasBox()) return null;

            float sw = 0f, sx = 0f, sy = 0f;
            for (MeasuringGraphics.PartQuad q : mg.quads()) {
                float[] p = q.pts;
                float area = Math.abs((p[2] - p[0]) * (p[7] - p[1])
                        - (p[6] - p[0]) * (p[3] - p[1]));
                float wgt = area * AlphaBounds.fillRatio(q.image);
                if (wgt <= 0f) continue;
                float cx = (p[0] + p[2] + p[4] + p[6]) * 0.25f;
                float cy = (p[1] + p[3] + p[5] + p[7]) * 0.25f;
                sw += wgt;
                sx += wgt * cx;
                sy += wgt * cy;
            }
            if (sw > 1e-3f) return new float[]{sx / sw, sy / sw};

            return new float[]{
                    (mg.minX() + mg.maxX()) * 0.5f,
                    (mg.minY() + mg.maxY()) * 0.5f};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static float spriteTopOffsetPx(Object entity, float siz) {
        if (entity == null) return Float.NaN;
        try {
            Object am = BCUFields.get(entity, "anim");
            if (am == null) return Float.NaN;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return Float.NaN;
            MeasuringGraphics mg = new MeasuringGraphics(SpriteBounds.ALPHA_PROVIDER, true);
            Method m = drawMethod;
            if (m == null || m.getDeclaringClass() != anim.getClass()) {
                m = BCUFields.method(anim.getClass(), "draw",
                        FakeGraphics.class, P.class, float.class);
                drawMethod = m;
            }
            m.invoke(anim, mg, new P(0f, 0f), siz);
            if (!mg.hasBox()) return Float.NaN;
            return mg.minY();
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    public static void drawEntityBox(Object entity, FakeGraphics g, P p, float siz) {
        if (!OVERLAY || entity == null || g == null || p == null) return;
        try {
            Object am = BCUFields.get(entity, "anim");
            if (am == null) return;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return;

            MeasuringGraphics mg = new MeasuringGraphics(SpriteBounds.ALPHA_PROVIDER, true);
            Method m = drawMethod;
            if (m == null || m.getDeclaringClass() != anim.getClass()) {
                m = BCUFields.method(anim.getClass(), "draw",
                        FakeGraphics.class, P.class, float.class);
                drawMethod = m;
            }
            m.invoke(anim, mg, new P(0f, 0f), siz);
            if (!mg.hasBox()) return;

            int dire = BCUFields.getInt(entity, "dire");

            boolean mirror = manualcontrol.hooks.DrawHook.shouldMirrorForCurrentFacing(entity);
            float s = mirror ? -1f : 1f;

            float advOff = 0f;
            if (mirror) {
                float c = manualcontrol.adventure.AdventureBridge.mirrorCenter(entity);
                if (c == c) advOff = 2f * c * siz;
            }

            float ax = p.x + s * mg.minX() + advOff;
            float bx = p.x + s * mg.maxX() + advOff;
            float left = Math.min(ax, bx);
            float right = Math.max(ax, bx);
            float top = p.y + mg.minY();
            float bottom = p.y + mg.maxY();

            g.setColor(dire > 0 ? FakeGraphics.RED : FakeGraphics.CYAN);
            g.drawRect(left, top, right - left, bottom - top);

            for (MeasuringGraphics.PartQuad pq : mg.quads()) {
                float[] q = pq.pts;
                for (int i = 0; i < 4; i++) {
                    float x1 = p.x + s * q[i * 2] + advOff;
                    float y1 = p.y + q[i * 2 + 1];
                    float x2 = p.x + s * q[(i * 2 + 2) % 8] + advOff;
                    float y2 = p.y + q[(i * 2 + 3) % 8];
                    g.drawLine(x1, y1, x2, y2);
                }
            }
        } catch (Throwable ignored) {

        }
    }
}
