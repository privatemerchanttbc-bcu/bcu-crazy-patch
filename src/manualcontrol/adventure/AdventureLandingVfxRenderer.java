package manualcontrol.adventure;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import manualcontrol.Logger;
import manualcontrol.reflect.BBPainterAccess;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

final class AdventureLandingVfxRenderer {

    static final int DURATION = 54;
    static final float RADIUS_WORLD = 320f;
    static final float DEFAULT_SHADOW_WIDTH = 89f;

    private static final int IMPACT_FRAMES = 6;
    private static final int RING_FRAMES = 28;
    private static final int DEBRIS_FRAMES = 46;
    private static final float SHADOW_TO_RADIUS = 1.15f;
    private static final float MIN_SHADOW_WIDTH = 28f;
    private static final float MAX_SHADOW_WIDTH = 300f;
    private static final float MAX_DRAW_DIMENSION = 4096f;
    private static final float MAX_VISUAL_RADIUS = 1300f;
    private static final Map<AdventureLandingVfx, Assets> CACHE =
            new EnumMap<AdventureLandingVfx, Assets>(AdventureLandingVfx.class);

    private AdventureLandingVfxRenderer() {}

    static final class Visual {
        final float worldX;
        final int layer;
        final AdventureLandingVfx style;
        final int seed;
        final float shadowWidth;
        final Particle[] particles;
        final Piece[] crown;
        int age;

        Visual(float worldX, int layer, AdventureLandingVfx style, int seed,
               float shadowWidth) {
            this.worldX = worldX;
            this.layer = layer;
            this.style = style == null ? AdventureLandingVfx.CRYSTAL : style;
            this.seed = seed;
            this.shadowWidth = finite(shadowWidth) && shadowWidth > 0f
                    ? clamp(shadowWidth, MIN_SHADOW_WIDTH, MAX_SHADOW_WIDTH)
                    : DEFAULT_SHADOW_WIDTH;
            this.particles = createParticles(seed);
            this.crown = createPieces(seed, this.style);
        }

        boolean tick() {
            age++;
            return age > DURATION;
        }
    }

    private static final class Particle {
        final float angle;
        final float speed;
        final float lift;
        final float size;
        final float rotation;
        final float spin;
        final int delay;
        final boolean spark;
        final boolean front;

        Particle(float angle, float speed, float lift, float size, float rotation,
                 float spin, int delay, boolean spark, boolean front) {
            this.angle = angle;
            this.speed = speed;
            this.lift = lift;
            this.size = size;
            this.rotation = rotation;
            this.spin = spin;
            this.delay = delay;
            this.spark = spark;
            this.front = front;
        }
    }

    private static final class Piece {
        final float offset;
        final float size;
        final float rotation;
        final int delay;
        final boolean front;

        Piece(float offset, float size, float rotation, int delay, boolean front) {
            this.offset = offset;
            this.size = size;
            this.rotation = rotation;
            this.delay = delay;
            this.front = front;
        }
    }

    private static final class Assets {
        final AdventureLandingVfx style;
        final Color primary;
        final Color secondary;
        final Color hot;
        final Color dark;

        final BufferedImage ring;
        final BufferedImage glow;
        final BufferedImage flash;
        final BufferedImage crack;
        final BufferedImage smoke;
        final BufferedImage rock;
        final BufferedImage shard;
        final BufferedImage spark;
        final BufferedImage beam;
        final BufferedImage dome;
        final BufferedImage crescent;
        final BufferedImage bolt;

        FakeImage ringFx;
        FakeImage glowFx;
        FakeImage flashFx;
        FakeImage crackFx;
        FakeImage smokeFx;
        FakeImage rockFx;
        FakeImage shardFx;
        FakeImage sparkFx;
        FakeImage beamFx;
        FakeImage domeFx;
        FakeImage crescentFx;
        FakeImage boltFx;
        boolean uploadFailed;

        Assets(AdventureLandingVfx style) {
            this.style = style;
            switch (style) {
                case SOLAR:
                    primary = new Color(255, 112, 24);
                    secondary = new Color(255, 205, 72);
                    hot = new Color(255, 252, 218);
                    dark = new Color(70, 24, 8);
                    break;
                case VOID:
                    primary = new Color(164, 54, 255);
                    secondary = new Color(52, 226, 255);
                    hot = new Color(246, 235, 255);
                    dark = new Color(20, 5, 42);
                    break;
                default:
                    primary = new Color(35, 180, 255);
                    secondary = new Color(105, 236, 255);
                    hot = new Color(242, 255, 255);
                    dark = new Color(8, 45, 82);
                    break;
            }
            ring = sanitizeTexture(bakeRing(this));
            glow = sanitizeTexture(bakeGlow(this));
            flash = sanitizeTexture(bakeFlash(this));
            crack = sanitizeTexture(bakeCrack(this));
            smoke = sanitizeTexture(bakeSmoke(this));
            rock = sanitizeTexture(bakeRock(this));
            shard = sanitizeTexture(bakeShard(this));
            spark = sanitizeTexture(bakeSpark(this));
            beam = sanitizeTexture(bakeBeam(this));
            dome = sanitizeTexture(bakeDome(this));
            crescent = sanitizeTexture(bakeCrescent(this));
            bolt = sanitizeTexture(bakeBolt(this));
        }

        synchronized void upload() {
            if (ringFx != null || uploadFailed || ImageBuilder.builder == null) return;
            try {
                ringFx = toFake(ring);
                glowFx = toFake(glow);
                flashFx = toFake(flash);
                crackFx = toFake(crack);
                smokeFx = toFake(smoke);
                rockFx = toFake(rock);
                shardFx = toFake(shard);
                sparkFx = toFake(spark);
                beamFx = toFake(beam);
                domeFx = toFake(dome);
                crescentFx = toFake(crescent);
                boltFx = toFake(bolt);
                if (ringFx == null || glowFx == null || shardFx == null) {
                    uploadFailed = true;
                }
            } catch (Throwable t) {
                uploadFailed = true;
                Logger.err("Adventure landing VFX texture upload failed for " + style.id, t);
            }
        }

        boolean ready() {
            return !uploadFailed && ringFx != null && glowFx != null
                    && flashFx != null && crackFx != null && shardFx != null;
        }
    }

    static Visual create(float worldX, int layer, AdventureLandingVfx style, int seed,
                         float shadowWidth) {
        return new Visual(worldX, layer, style, seed, shadowWidth);
    }

    static void prewarm(AdventureLandingVfx style) {
        assets(style, true);
    }

    static float worldRadiusForShadowWidth(float shadowWidth) {
        float safeWidth = finite(shadowWidth) && shadowWidth > 0f
                ? clamp(shadowWidth, MIN_SHADOW_WIDTH, MAX_SHADOW_WIDTH)
                : DEFAULT_SHADOW_WIDTH;
        return safeWidth * SHADOW_TO_RADIUS / 0.32f;
    }

    static void drawUnder(Object bbpainter, FakeGraphics g, Visual v) {
        if (bbpainter == null || g == null || v == null) return;
        float siz = BBPainterAccess.getSiz(bbpainter);
        if (siz <= 0.0001f) return;
        float cx = screenX(v.worldX, siz, BBPainterAccess.getStagePos(bbpainter));
        float ground = groundY(v.layer, siz, BBPainterAccess.getMidh(bbpainter));
        float radius = visualRadius(v, siz);
        Assets a = assets(v.style, true);
        if (!a.ready()) {
            drawFallbackUnder(g, v, cx, ground, radius);
            return;
        }

        float life = clamp01(v.age / (float) DURATION);
        float crackFade = v.age < 38 ? 1f : clamp01((DURATION - v.age) / 16f);
        drawImage(g, a.crackFx, cx, ground - radius * 0.04f,
                radius * 2.35f, radius * 0.92f,
                FakeGraphics.TRANS, Math.round(245f * crackFade));
        drawImage(g, a.glowFx, cx, ground - radius * 0.12f,
                radius * 2.25f, radius * 0.82f,
                FakeGraphics.BLEND, Math.round(185f * clamp01((1f - life) * 1.8f)));

        drawRing(g, a, cx, ground, radius, v.age, 0, 1.00f, 235);
        drawRing(g, a, cx, ground, radius, v.age, 7, 0.82f, 205);
        drawRing(g, a, cx, ground, radius, v.age, 13, 0.64f, 175);

        switch (v.style) {
            case SOLAR:
                drawSolarBack(g, a, v, cx, ground, radius);
                break;
            case VOID:
                drawVoidBack(g, a, v, cx, ground, radius);
                break;
            default:
                drawCrystalPieces(g, a, v, cx, ground, radius, false);
                break;
        }
        drawParticles(g, a, v, cx, ground, radius, false);
    }

    static void drawWorldOverlay(Object bbpainter, FakeGraphics g, Visual v) {
        if (bbpainter == null || g == null || v == null) return;
        float siz = BBPainterAccess.getSiz(bbpainter);
        if (siz <= 0.0001f) return;
        float cx = screenX(v.worldX, siz, BBPainterAccess.getStagePos(bbpainter));
        float ground = groundY(v.layer, siz, BBPainterAccess.getMidh(bbpainter));
        float radius = visualRadius(v, siz);
        Assets a = assets(v.style, true);
        if (!a.ready()) {
            drawFallbackOverlay(g, v, cx, ground, radius);
            return;
        }

        float impactFade = clamp01((12f - v.age) / 10f);
        if (impactFade > 0f) {
            drawImage(g, a.flashFx, cx, ground - radius * 0.18f,
                    radius * 1.75f, radius * 0.85f,
                    FakeGraphics.BLEND, Math.round(230f * impactFade));
        }

        switch (v.style) {
            case SOLAR:
                drawSolarFront(g, a, v, cx, ground, radius);
                break;
            case VOID:
                drawVoidFront(g, a, v, cx, ground, radius);
                break;
            default:
                drawCrystalPieces(g, a, v, cx, ground, radius, true);
                break;
        }
        drawParticles(g, a, v, cx, ground, radius, true);
    }

    static void drawScreenWash(Object bbpainter, FakeGraphics g, Visual v) {
        if (bbpainter == null || g == null || v == null || v.age >= IMPACT_FRAMES) return;
        Assets a = assets(v.style, false);
        float p = v.age / (float) IMPACT_FRAMES;
        int alpha = Math.round((1f - p) * (v.style == AdventureLandingVfx.SOLAR ? 38f : 28f));
        try {
            g.colRect(0, 0, BBPainterAccess.getWidth(bbpainter),
                    BBPainterAccess.getHeight(bbpainter),
                    a.hot.getRed(), a.hot.getGreen(), a.hot.getBlue(), alpha);
        } catch (Throwable ignored) {}
    }

    private static void drawCrystalPieces(FakeGraphics g, Assets a, Visual v,
                                          float cx, float ground, float radius,
                                          boolean front) {
        float fade = v.age < 34 ? 1f : clamp01((DURATION - v.age) / 20f);
        for (int i = 0; i < v.crown.length; i++) {
            Piece piece = v.crown[i];
            if (piece.front != front) continue;
            int local = v.age - piece.delay;
            if (local < 0) continue;
            float grow = easeOut(clamp01(local / 7f));
            float h = radius * piece.size * grow;
            float w = h * 0.38f;
            float x = cx + piece.offset * radius;
            drawRotated(g, a.shardFx, x, ground - h * 0.45f,
                    w, h, piece.rotation, FakeGraphics.BLEND,
                    Math.round((front ? 245f : 205f) * fade));
            if (front && i % 3 == 0) {
                drawImage(g, a.sparkFx, x, ground - h * 0.83f,
                        w * 1.35f, w * 1.35f, FakeGraphics.BLEND,
                        Math.round(185f * fade));
            }
        }
    }

    private static void drawSolarBack(FakeGraphics g, Assets a, Visual v,
                                      float cx, float ground, float radius) {
        float beam = clamp01((19f - v.age) / 17f);
        if (beam > 0f) {
            float collapse = easeOut(clamp01(v.age / 14f));
            drawImage(g, a.beamFx, cx, ground - radius * 1.52f,
                    radius * (0.62f - collapse * 0.28f), radius * 3.15f,
                    FakeGraphics.BLEND, Math.round(255f * beam));
            drawImage(g, a.domeFx, cx, ground - radius * 0.45f,
                    radius * 2.10f, radius * 1.18f,
                    FakeGraphics.BLEND, Math.round(230f * beam));
        }
        float spikeFade = v.age < 28 ? 1f : clamp01((DURATION - v.age) / 26f);
        for (int i = 0; i < 13; i++) {
            float f = i / 12f;
            float angle = (float) (-Math.PI * 0.82 + f * Math.PI * 0.64);
            float h = radius * (0.42f + (i % 4) * 0.10f)
                    * easeOut(clamp01(v.age / 8f));
            float x = cx + (f * 2f - 1f) * radius * 0.88f;
            drawRotated(g, a.shardFx, x, ground - h * 0.35f,
                    h * 0.23f, h, angle * 0.20f,
                    FakeGraphics.BLEND, Math.round(190f * spikeFade));
        }
    }

    private static void drawSolarFront(FakeGraphics g, Assets a, Visual v,
                                       float cx, float ground, float radius) {
        float fade = v.age < 24 ? 1f : clamp01((DURATION - v.age) / 30f);
        for (int i = 0; i < 5; i++) {
            float side = (i & 1) == 0 ? -1f : 1f;
            float h = radius * (0.32f + i * 0.055f)
                    * easeOut(clamp01((v.age - i) / 7f));
            float x = cx + side * radius * (0.30f + i * 0.11f);
            drawRotated(g, a.shardFx, x, ground - h * 0.38f,
                    h * 0.24f, h, side * (0.18f + i * 0.035f),
                    FakeGraphics.BLEND, Math.round(220f * fade));
        }
    }

    private static void drawVoidBack(FakeGraphics g, Assets a, Visual v,
                                     float cx, float ground, float radius) {
        float dome = clamp01((25f - v.age) / 22f);
        if (dome > 0f) {
            drawImage(g, a.domeFx, cx, ground - radius * 0.53f,
                    radius * 2.18f, radius * 1.36f,
                    FakeGraphics.BLEND, Math.round(230f * dome));
        }
        for (int i = 0; i < 5; i++) {
            float side = (i & 1) == 0 ? -1f : 1f;
            float localFade = clamp01((28f - v.age + i * 2f) / 22f);
            float x = cx + side * radius * (0.18f + i * 0.16f);
            float y = ground - radius * (0.62f + (i % 3) * 0.22f);
            drawRotated(g, a.boltFx, x, y,
                    radius * 0.24f, radius * (0.85f + (i % 2) * 0.22f),
                    side * (0.18f + i * 0.07f),
                    FakeGraphics.BLEND, Math.round(225f * localFade));
        }
    }

    private static void drawVoidFront(FakeGraphics g, Assets a, Visual v,
                                      float cx, float ground, float radius) {
        float fade = v.age < 31 ? 1f : clamp01((DURATION - v.age) / 23f);
        float spread = easeOut(clamp01(v.age / 24f));
        drawImage(g, a.crescentFx, cx, ground - radius * 0.18f,
                radius * (1.20f + spread * 0.85f), radius * (0.45f + spread * 0.16f),
                FakeGraphics.BLEND, Math.round(225f * fade));
        drawRotated(g, a.crescentFx, cx, ground - radius * 0.28f,
                radius * (0.88f + spread * 0.58f), radius * (0.34f + spread * 0.11f),
                (float) Math.PI, FakeGraphics.BLEND, Math.round(185f * fade));
        drawCrystalPieces(g, a, v, cx, ground, radius * 0.82f, true);
    }

    private static void drawParticles(FakeGraphics g, Assets a, Visual v,
                                      float cx, float ground, float radius,
                                      boolean front) {
        for (int i = 0; i < v.particles.length; i++) {
            Particle p = v.particles[i];
            if (p.front != front) continue;
            int local = v.age - p.delay;
            if (local < 0 || local >= DEBRIS_FRAMES) continue;
            float t = local / (float) DEBRIS_FRAMES;
            float travel = easeOut(t);
            float x = cx + (float) Math.cos(p.angle) * radius * p.speed * travel;
            float y = ground + (float) Math.sin(p.angle) * radius * p.speed * 0.24f * travel
                    - radius * p.lift * (float) Math.sin(Math.PI * t)
                    + radius * 0.10f * t * t;
            float size = radius * p.size * (1f - t * 0.32f);
            int alpha = Math.round(240f * (float) Math.pow(1f - t, 1.15));
            FakeImage image = p.spark ? a.sparkFx
                    : (v.style == AdventureLandingVfx.CRYSTAL ? a.shardFx : a.rockFx);
            float h = p.spark ? size : size * (v.style == AdventureLandingVfx.CRYSTAL ? 1.65f : 1f);
            drawRotated(g, image, x, y, size, h,
                    p.rotation + p.spin * local,
                    p.spark || v.style == AdventureLandingVfx.CRYSTAL
                            ? FakeGraphics.BLEND : FakeGraphics.TRANS,
                    alpha);
        }

        if (v.age >= 7 && v.age < 48) {
            for (int i = 0; i < 6; i++) {
                int local = v.age - 7 - i;
                if (local < 0) continue;
                float t = clamp01(local / 41f);
                float side = (i & 1) == 0 ? -1f : 1f;
                float x = cx + side * radius * (0.15f + i * 0.13f) * easeOut(t);
                float y = ground - radius * (0.07f + 0.48f * t)
                        + (float) Math.sin(i * 1.7f + t * 5f) * radius * 0.06f;
                float s = radius * (0.19f + i * 0.018f) * (0.75f + t * 0.35f);
                drawRotated(g, a.smokeFx, x, y, s * 1.5f, s,
                        side * (0.12f + i * 0.05f),
                        FakeGraphics.TRANS, Math.round(130f * (1f - t)));
            }
        }
    }

    private static void drawRing(FakeGraphics g, Assets a, float cx, float ground,
                                 float radius, int age, int delay,
                                 float maxScale, int maxAlpha) {
        int local = age - delay;
        if (local < 0 || local >= RING_FRAMES) return;
        float p = local / (float) RING_FRAMES;
        float rr = radius * maxScale * (0.14f + 0.86f * easeOut(p));
        int alpha = Math.round(maxAlpha * (1f - p));
        drawImage(g, a.ringFx, cx, ground - rr * 0.04f,
                rr * 2f, rr * 0.60f, FakeGraphics.BLEND, alpha);
    }

    private static void drawImage(FakeGraphics g, FakeImage image, float cx, float cy,
                                  float w, float h, int composite, int alpha) {
        if (g == null || image == null || !finite(cx) || !finite(cy)
                || !finite(w) || !finite(h) || w <= 1f || h <= 1f || alpha <= 0) return;
        float largest = Math.max(w, h);
        if (largest > MAX_DRAW_DIMENSION) {
            float scale = MAX_DRAW_DIMENSION / largest;
            w *= scale;
            h *= scale;
        }
        try {
            g.setComposite(composite, clamp255(alpha), composite == FakeGraphics.BLEND ? 1 : 0);
            g.drawImage(image, cx - w * 0.5f, cy - h * 0.5f, w, h);
        } catch (Throwable ignored) {
        } finally {
            resetComposite(g);
        }
    }

    private static void drawRotated(FakeGraphics g, FakeImage image, float cx, float cy,
                                    float w, float h, float rotation,
                                    int composite, int alpha) {
        if (g == null || image == null || !finite(cx) || !finite(cy)
                || !finite(w) || !finite(h) || !finite(rotation)
                || w <= 1f || h <= 1f || alpha <= 0) return;
        FakeTransform old = null;
        try {
            old = g.getTransform();
            g.translate(cx, cy);
            if (Math.abs(rotation) > 0.001f) g.rotate(rotation);
            drawImage(g, image, 0f, 0f, w, h, composite, alpha);
        } catch (Throwable ignored) {
        } finally {
            if (old != null) {
                try { g.setTransform(old); } catch (Throwable ignored) {}
                try { g.delete(old); } catch (Throwable ignored) {}
            }
            resetComposite(g);
        }
    }

    private static void drawFallbackUnder(FakeGraphics g, Visual v,
                                          float cx, float ground, float radius) {
        float p = clamp01(v.age / (float) RING_FRAMES);
        int[] c = palette(v.style);
        try {
            g.setComposite(FakeGraphics.BLEND, Math.round(225f * (1f - p)), 1);
            g.setColor(c[0], c[1], c[2]);
            float rr = radius * (0.14f + 0.86f * easeOut(p));
            drawEllipseLines(g, cx, ground, rr, rr * 0.28f);
        } catch (Throwable ignored) {
        } finally {
            resetComposite(g);
        }
    }

    private static void drawFallbackOverlay(FakeGraphics g, Visual v,
                                            float cx, float ground, float radius) {
        float fade = clamp01((18f - v.age) / 16f);
        int[] c = palette(v.style);
        try {
            g.setComposite(FakeGraphics.BLEND, Math.round(210f * fade), 1);
            g.colRect(cx - radius * 0.55f, ground - radius * 0.28f,
                    radius * 1.10f, radius * 0.24f,
                    c[0], c[1], c[2], 180);
        } catch (Throwable ignored) {
        } finally {
            resetComposite(g);
        }
    }

    private static void drawEllipseLines(FakeGraphics g, float cx, float cy,
                                         float rx, float ry) {
        if (g == null || !finite(cx) || !finite(cy) || !finite(rx) || !finite(ry)
                || rx <= 0f || ry <= 0f) {
            return;
        }
        final int segments = 32;
        float px = cx + rx;
        float py = cy;
        for (int i = 1; i <= segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            float x = cx + (float) Math.cos(angle) * rx;
            float y = cy + (float) Math.sin(angle) * ry;
            g.drawLine(px, py, x, y);
            px = x;
            py = y;
        }
    }

    static void paintPreview(Graphics2D g, AdventureLandingVfx style,
                             int width, int height, int frame) {
        if (g == null || width <= 0 || height <= 0) return;
        AdventureLandingVfx safe = style == null ? AdventureLandingVfx.CRYSTAL : style;
        Assets a = assets(safe, false);
        int age = Math.max(0, Math.min(DURATION, frame));
        float cx = width * 0.5f;
        float ground = height - 22f;
        float radius = Math.min(width * 0.43f, height * 0.55f);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(8, 11, 17));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(43, 49, 59));
        g.fillRect(0, Math.round(ground), width, 2);

        float crackFade = age < 38 ? 1f : clamp01((DURATION - age) / 16f);
        draw2D(g, a.crack, cx, ground - radius * 0.04f,
                radius * 2.35f, radius * 0.92f, 0f, 0.90f * crackFade);
        draw2D(g, a.glow, cx, ground - radius * 0.12f,
                radius * 2.25f, radius * 0.82f, 0f,
                0.72f * clamp01((1f - age / (float) DURATION) * 1.8f));
        previewRing(g, a, cx, ground, radius, age, 0, 1f, 0.92f);
        previewRing(g, a, cx, ground, radius, age, 7, 0.82f, 0.78f);
        previewRing(g, a, cx, ground, radius, age, 13, 0.64f, 0.68f);

        Visual visual = new Visual(0f, 0, safe,
                0x41564658 + safe.ordinal() * 11939, DEFAULT_SHADOW_WIDTH);
        visual.age = age;
        paintPreviewBack(g, a, visual, cx, ground, radius);
        paintPreviewPlayer(g, cx, ground);
        paintPreviewFront(g, a, visual, cx, ground, radius);
    }

    private static void paintPreviewBack(Graphics2D g, Assets a, Visual v,
                                         float cx, float ground, float radius) {
        switch (v.style) {
            case SOLAR:
                float beam = clamp01((19f - v.age) / 17f);
                if (beam > 0f) {
                    draw2D(g, a.beam, cx, ground - radius * 1.35f,
                            radius * 0.48f, radius * 2.75f, 0f, beam);
                    draw2D(g, a.dome, cx, ground - radius * 0.44f,
                            radius * 2.05f, radius * 1.12f, 0f, beam * 0.9f);
                }
                previewPieces(g, a, v, cx, ground, radius, false, true);
                break;
            case VOID:
                float dome = clamp01((25f - v.age) / 22f);
                draw2D(g, a.dome, cx, ground - radius * 0.50f,
                        radius * 2.12f, radius * 1.30f, 0f, dome * 0.92f);
                for (int i = 0; i < 4; i++) {
                    float side = (i & 1) == 0 ? -1f : 1f;
                    draw2D(g, a.bolt, cx + side * radius * (0.20f + i * 0.15f),
                            ground - radius * (0.55f + (i % 2) * 0.25f),
                            radius * 0.20f, radius * 0.82f,
                            side * (0.18f + i * 0.06f),
                            clamp01((27f - v.age) / 22f));
                }
                break;
            default:
                previewPieces(g, a, v, cx, ground, radius, false, false);
                break;
        }
        previewParticles(g, a, v, cx, ground, radius, false);
    }

    private static void paintPreviewFront(Graphics2D g, Assets a, Visual v,
                                          float cx, float ground, float radius) {
        float impact = clamp01((12f - v.age) / 10f);
        draw2D(g, a.flash, cx, ground - radius * 0.18f,
                radius * 1.7f, radius * 0.82f, 0f, impact * 0.85f);
        switch (v.style) {
            case SOLAR:
                previewPieces(g, a, v, cx, ground, radius, true, true);
                break;
            case VOID:
                float spread = easeOut(clamp01(v.age / 24f));
                float fade = v.age < 31 ? 1f : clamp01((DURATION - v.age) / 23f);
                draw2D(g, a.crescent, cx, ground - radius * 0.18f,
                        radius * (1.18f + spread * 0.80f),
                        radius * (0.43f + spread * 0.15f), 0f, fade);
                previewPieces(g, a, v, cx, ground, radius * 0.80f, true, false);
                break;
            default:
                previewPieces(g, a, v, cx, ground, radius, true, false);
                break;
        }
        previewParticles(g, a, v, cx, ground, radius, true);
    }

    private static void previewPieces(Graphics2D g, Assets a, Visual v,
                                      float cx, float ground, float radius,
                                      boolean front, boolean solar) {
        float fade = v.age < 34 ? 1f : clamp01((DURATION - v.age) / 20f);
        for (int i = 0; i < v.crown.length; i++) {
            Piece piece = v.crown[i];
            if (piece.front != front) continue;
            int local = v.age - piece.delay;
            if (local < 0) continue;
            float h = radius * piece.size * easeOut(clamp01(local / 7f));
            draw2D(g, a.shard, cx + piece.offset * radius, ground - h * 0.45f,
                    h * (solar ? 0.24f : 0.38f), h,
                    piece.rotation, fade * (front ? 0.95f : 0.76f));
        }
    }

    private static void previewParticles(Graphics2D g, Assets a, Visual v,
                                         float cx, float ground, float radius,
                                         boolean front) {
        for (int i = 0; i < v.particles.length; i++) {
            Particle p = v.particles[i];
            if (p.front != front) continue;
            int local = v.age - p.delay;
            if (local < 0 || local >= DEBRIS_FRAMES) continue;
            float t = local / (float) DEBRIS_FRAMES;
            float travel = easeOut(t);
            float x = cx + (float) Math.cos(p.angle) * radius * p.speed * travel;
            float y = ground + (float) Math.sin(p.angle) * radius * p.speed * 0.24f * travel
                    - radius * p.lift * (float) Math.sin(Math.PI * t);
            float size = radius * p.size * (1f - t * 0.32f);
            BufferedImage image = p.spark ? a.spark
                    : (v.style == AdventureLandingVfx.CRYSTAL ? a.shard : a.rock);
            draw2D(g, image, x, y, size,
                    p.spark ? size : size * (v.style == AdventureLandingVfx.CRYSTAL ? 1.65f : 1f),
                    p.rotation + p.spin * local, (1f - t) * 0.94f);
        }
    }

    private static void paintPreviewPlayer(Graphics2D g, float cx, float ground) {
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(246, 248, 252));
        g.fillOval(Math.round(cx - 7f), Math.round(ground - 45f), 14, 14);
        g.setColor(new Color(175, 184, 199));
        g.fillRoundRect(Math.round(cx - 9f), Math.round(ground - 32f), 18, 26, 5, 5);
    }

    private static void previewRing(Graphics2D g, Assets a, float cx, float ground,
                                    float radius, int age, int delay,
                                    float maxScale, float alpha) {
        int local = age - delay;
        if (local < 0 || local >= RING_FRAMES) return;
        float p = local / (float) RING_FRAMES;
        float rr = radius * maxScale * (0.14f + 0.86f * easeOut(p));
        draw2D(g, a.ring, cx, ground - rr * 0.04f,
                rr * 2f, rr * 0.60f, 0f, alpha * (1f - p));
    }

    private static void draw2D(Graphics2D g, BufferedImage image, float cx, float cy,
                               float w, float h, float rotation, float alpha) {
        if (image == null || w <= 1f || h <= 1f || alpha <= 0.005f) return;
        java.awt.geom.AffineTransform old = g.getTransform();
        java.awt.Composite oldComposite = g.getComposite();
        try {
            g.translate(cx, cy);
            if (Math.abs(rotation) > 0.001f) g.rotate(rotation);
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(alpha)));
            g.drawImage(image, Math.round(-w * 0.5f), Math.round(-h * 0.5f),
                    Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), null);
        } finally {
            g.setTransform(old);
            g.setComposite(oldComposite);
        }
    }

    private static Assets assets(AdventureLandingVfx style, boolean upload) {
        AdventureLandingVfx safe = style == null ? AdventureLandingVfx.CRYSTAL : style;
        Assets assets;
        synchronized (CACHE) {
            assets = CACHE.get(safe);
            if (assets == null) {
                assets = new Assets(safe);
                CACHE.put(safe, assets);
            }
        }
        if (upload) assets.upload();
        return assets;
    }

    private static Particle[] createParticles(int seed) {
        Particle[] out = new Particle[18];
        Random random = new Random(seed * 31L + 0xB0A7C1EL);
        for (int i = 0; i < out.length; i++) {
            float angle = (float) (Math.PI + random.nextFloat() * Math.PI);
            float speed = 0.38f + random.nextFloat() * 0.92f;
            float lift = 0.28f + random.nextFloat() * 0.72f;
            float size = 0.045f + random.nextFloat() * 0.085f;
            float rotation = random.nextFloat() * (float) Math.PI * 2f;
            float spin = -0.12f + random.nextFloat() * 0.24f;
            int delay = random.nextInt(7);
            boolean spark = i % 3 == 0 || i == 17;
            boolean front = (i & 1) == 0;
            out[i] = new Particle(angle, speed, lift, size, rotation, spin,
                    delay, spark, front);
        }
        return out;
    }

    private static Piece[] createPieces(int seed, AdventureLandingVfx style) {
        int count = style == AdventureLandingVfx.SOLAR ? 12 : 11;
        Piece[] out = new Piece[count];
        Random random = new Random(seed * 17L + style.ordinal() * 1009L + 77L);
        for (int i = 0; i < count; i++) {
            float f = count <= 1 ? 0f : i / (float) (count - 1);
            float offset = -0.88f + f * 1.76f + (random.nextFloat() - 0.5f) * 0.08f;
            float center = 1f - Math.abs(f * 2f - 1f);
            float size = (style == AdventureLandingVfx.SOLAR ? 0.55f : 0.72f)
                    + center * (style == AdventureLandingVfx.CRYSTAL ? 0.72f : 0.45f)
                    + random.nextFloat() * 0.18f;
            float rotation = offset * (style == AdventureLandingVfx.SOLAR ? 0.34f : 0.20f)
                    + (random.nextFloat() - 0.5f) * 0.10f;
            int delay = i % 4;
            boolean front = i % 3 == 0 || i == count / 2;
            out[i] = new Piece(offset, size, rotation, delay, front);
        }
        return out;
    }

    private static BufferedImage bakeRing(Assets a) {
        BufferedImage image = canvas(512, 160);
        Graphics2D g = aa(image);
        try {
            Ellipse2D ellipse = new Ellipse2D.Float(32f, 39f, 448f, 82f);
            stroke(g, ellipse, 32f, alpha(a.primary, 22));
            stroke(g, ellipse, 22f, alpha(a.primary, 46));
            stroke(g, ellipse, 13f, alpha(a.secondary, 112));
            stroke(g, ellipse, 7f, alpha(a.secondary, 210));
            stroke(g, ellipse, 2.6f, alpha(a.hot, 255));
            g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(a.hot, 230));
            g.draw(new Arc2D.Float(32f, 39f, 448f, 82f, 12f, 58f, Arc2D.OPEN));
            g.draw(new Arc2D.Float(32f, 39f, 448f, 82f, 192f, 48f, Arc2D.OPEN));
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeGlow(Assets a) {
        BufferedImage image = canvas(384, 160);
        Graphics2D g = aa(image);
        try {
            g.translate(192, 112);
            g.scale(1.0, 0.36);
            RadialGradientPaint paint = new RadialGradientPaint(
                    new Point2D.Float(0f, 0f), 180f,
                    new float[]{0f, 0.25f, 0.62f, 1f},
                    new Color[]{alpha(a.hot, 235), alpha(a.secondary, 175),
                            alpha(a.primary, 72), alpha(a.primary, 0)},
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g.setPaint(paint);
            g.fillOval(-180, -180, 360, 360);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeFlash(Assets a) {
        BufferedImage image = canvas(384, 192);
        Graphics2D g = aa(image);
        try {
            RadialGradientPaint paint = new RadialGradientPaint(
                    new Point2D.Float(192f, 116f), 170f,
                    new float[]{0f, 0.16f, 0.48f, 1f},
                    new Color[]{alpha(Color.WHITE, 255), alpha(a.hot, 235),
                            alpha(a.primary, 90), alpha(a.primary, 0)});
            g.setPaint(paint);
            g.fillOval(22, -54, 340, 340);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 22; i++) {
                double angle = Math.PI + i * Math.PI / 21.0;
                float len = 55f + (i % 5) * 19f;
                float x0 = 192f + (float) Math.cos(angle) * 18f;
                float y0 = 116f + (float) Math.sin(angle) * 7f;
                float x1 = 192f + (float) Math.cos(angle) * len;
                float y1 = 116f + (float) Math.sin(angle) * len * 0.38f;
                g.setColor(alpha(i % 4 == 0 ? a.hot : a.secondary, 175));
                g.drawLine(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeCrack(Assets a) {
        BufferedImage image = canvas(768, 320);
        Graphics2D g = aa(image);
        Random random = new Random(0xC12A6L + a.style.ordinal() * 9151L);
        try {
            Path2D.Float cracks = new Path2D.Float();
            float cx = 384f, cy = 160f;
            int branches = a.style == AdventureLandingVfx.CRYSTAL ? 16 : 13;
            for (int i = 0; i < branches; i++) {
                double angle = Math.PI * 2.0 * i / branches
                        + (random.nextFloat() - 0.5f) * 0.26;
                float max = 185f + random.nextFloat() * 145f;
                float x = cx, y = cy;
                cracks.moveTo(x, y);
                int steps = 4 + random.nextInt(4);
                for (int k = 1; k <= steps; k++) {
                    float d = max * k / steps;
                    float side = (random.nextFloat() - 0.5f) * (18f + k * 7f);
                    float nx = cx + (float) Math.cos(angle) * d
                            + (float) Math.cos(angle + Math.PI / 2) * side;
                    float ny = cy + (float) Math.sin(angle) * d * 0.31f
                            + (float) Math.sin(angle + Math.PI / 2) * side * 0.26f;
                    cracks.lineTo(nx, ny);
                    if (k >= 2 && random.nextFloat() > 0.60f) {
                        cracks.moveTo(nx, ny);
                        cracks.lineTo(nx + (float) Math.cos(angle + 0.75f)
                                        * (22f + random.nextFloat() * 34f),
                                ny + (float) Math.sin(angle + 0.75f)
                                        * (10f + random.nextFloat() * 17f));
                        cracks.moveTo(nx, ny);
                    }
                    x = nx;
                    y = ny;
                }
            }
            stroke(g, cracks, 15f, alpha(a.primary, 22));
            stroke(g, cracks, 8f, alpha(a.primary, 62));
            stroke(g, cracks, 4f, alpha(a.dark, 235));
            stroke(g, cracks, 1.5f, alpha(a.secondary, 230));
            g.setColor(alpha(a.hot, 175));
            g.fillOval(370, 150, 28, 18);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeSmoke(Assets a) {
        BufferedImage image = canvas(128, 96);
        Graphics2D g = aa(image);
        Random random = new Random(0x5A0B1EL + a.style.ordinal() * 137L);
        try {
            for (int i = 0; i < 8; i++) {
                float x = 24f + random.nextFloat() * 80f;
                float y = 34f + random.nextFloat() * 38f;
                float radius = 22f + random.nextFloat() * 26f;
                Color base = mix(new Color(72, 72, 80), a.primary,
                        a.style == AdventureLandingVfx.SOLAR ? 0.12f : 0.24f);
                RadialGradientPaint paint = new RadialGradientPaint(
                        new Point2D.Float(x, y), radius,
                        new float[]{0f, 0.58f, 1f},
                        new Color[]{alpha(mix(base, a.hot, 0.15f), 112),
                                alpha(base, 82), alpha(base, 0)});
                g.setPaint(paint);
                g.fillOval(Math.round(x - radius), Math.round(y - radius),
                        Math.round(radius * 2f), Math.round(radius * 2f));
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeRock(Assets a) {
        BufferedImage image = canvas(96, 96);
        Graphics2D g = aa(image);
        try {
            Path2D.Float rock = new Path2D.Float();
            rock.moveTo(17, 47);
            rock.lineTo(28, 19);
            rock.lineTo(58, 12);
            rock.lineTo(82, 34);
            rock.lineTo(76, 68);
            rock.lineTo(49, 84);
            rock.lineTo(22, 70);
            rock.closePath();
            g.setColor(alpha(Color.BLACK, 95));
            g.translate(4, 5);
            g.fill(rock);
            g.translate(-4, -5);
            Color body = a.style == AdventureLandingVfx.SOLAR
                    ? mix(new Color(38, 24, 18), a.primary, 0.24f)
                    : mix(new Color(29, 27, 38), a.dark, 0.55f);
            g.setPaint(new GradientPaint(22, 16, mix(body, a.hot, 0.30f),
                    74, 80, body));
            g.fill(rock);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(a.primary, 185));
            g.draw(rock);
            g.setStroke(new BasicStroke(2f));
            g.setColor(alpha(a.hot, 205));
            g.drawLine(30, 28, 57, 20);
            g.drawLine(57, 20, 70, 37);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeShard(Assets a) {
        BufferedImage image = canvas(128, 256);
        Graphics2D g = aa(image);
        try {
            Path2D.Float shard = new Path2D.Float();
            if (a.style == AdventureLandingVfx.SOLAR) {
                shard.moveTo(64, 5);
                shard.curveTo(96, 72, 102, 153, 80, 244);
                shard.lineTo(48, 244);
                shard.curveTo(25, 164, 36, 73, 64, 5);
            } else {
                shard.moveTo(64, 5);
                shard.lineTo(106, 176);
                shard.lineTo(79, 246);
                shard.lineTo(37, 236);
                shard.lineTo(20, 158);
                shard.closePath();
            }
            stroke(g, shard, 22f, alpha(a.primary, 34));
            stroke(g, shard, 12f, alpha(a.primary, 72));
            g.setPaint(new LinearGradientPaint(24, 20, 104, 238,
                    new float[]{0f, 0.32f, 0.72f, 1f},
                    new Color[]{a.hot, a.secondary, a.primary, a.dark}));
            g.fill(shard);
            stroke(g, shard, 4f, alpha(a.hot, 245));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(a.hot, 205));
            g.drawLine(64, 12, 57, 226);
            g.setColor(alpha(a.secondary, 180));
            g.drawLine(57, 226, 100, 176);
            g.drawLine(64, 12, 23, 158);
            if (a.style == AdventureLandingVfx.VOID) {
                g.setColor(alpha(a.dark, 165));
                g.fillPolygon(new int[]{63, 97, 77, 57},
                        new int[]{22, 171, 232, 220}, 4);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeSpark(Assets a) {
        BufferedImage image = canvas(96, 96);
        Graphics2D g = aa(image);
        try {
            RadialGradientPaint glow = new RadialGradientPaint(
                    new Point2D.Float(48, 48), 46f,
                    new float[]{0f, 0.18f, 0.55f, 1f},
                    new Color[]{Color.WHITE, alpha(a.hot, 240),
                            alpha(a.primary, 90), alpha(a.primary, 0)});
            g.setPaint(glow);
            g.fillOval(2, 2, 92, 92);
            Path2D.Float diamond = new Path2D.Float();
            diamond.moveTo(48, 8);
            diamond.lineTo(59, 48);
            diamond.lineTo(48, 88);
            diamond.lineTo(37, 48);
            diamond.closePath();
            g.setColor(a.hot);
            g.fill(diamond);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeBeam(Assets a) {
        BufferedImage image = canvas(192, 512);
        for (int y = 0; y < image.getHeight(); y++) {
            float fy = y / (float) (image.getHeight() - 1);
            float vertical = 0.52f + 0.48f * fy;
            float edgeFade = clamp01(Math.min(y, image.getHeight() - 1 - y) / 12f);
            for (int x = 0; x < image.getWidth(); x++) {
                float nx = Math.abs(x - 95.5f) / 95.5f;
                float outer = clamp01(1f - nx);
                float core = clamp01(1f - nx / 0.18f);
                float aura = (float) Math.pow(outer, 2.2);
                float alpha = clamp01((aura * 0.55f + core * 0.75f)
                        * vertical * edgeFade);
                Color color = mix(a.primary, a.hot, clamp01(core * 0.88f + aura * 0.18f));
                image.setRGB(x, y, argb(Math.round(alpha * 245f), color));
            }
        }
        Graphics2D g = aa(image);
        try {
            g.setColor(alpha(a.hot, 220));
            g.fillRect(89, 8, 14, 496);
            g.setColor(alpha(Color.WHITE, 245));
            g.fillRect(94, 8, 4, 496);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeDome(Assets a) {
        BufferedImage image = canvas(384, 224);
        Graphics2D g = aa(image);
        try {
            g.translate(192, 208);
            g.scale(1.0, 0.58);
            RadialGradientPaint paint = new RadialGradientPaint(
                    new Point2D.Float(0, 0), 180f,
                    new float[]{0f, 0.28f, 0.72f, 1f},
                    new Color[]{alpha(a.hot, 215), alpha(a.secondary, 145),
                            alpha(a.primary, 54), alpha(a.primary, 0)});
            g.setPaint(paint);
            g.fillOval(-180, -180, 360, 360);
            g.scale(1.0, 1.0 / 0.58);
            g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(a.primary, 85));
            g.draw(new Arc2D.Float(-172, -195, 344, 184, 10, 160, Arc2D.OPEN));
            g.setStroke(new BasicStroke(3f));
            g.setColor(alpha(a.hot, 205));
            g.draw(new Arc2D.Float(-150, -172, 300, 150, 15, 150, Arc2D.OPEN));
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeCrescent(Assets a) {
        BufferedImage image = canvas(512, 192);
        Graphics2D g = aa(image);
        try {
            Arc2D.Float arc = new Arc2D.Float(30, 34, 452, 126, 198, 144, Arc2D.OPEN);
            stroke(g, arc, 38f, alpha(a.primary, 34));
            stroke(g, arc, 25f, alpha(a.primary, 82));
            stroke(g, arc, 14f, alpha(a.secondary, 205));
            stroke(g, arc, 5f, alpha(a.hot, 255));
            for (int i = 0; i < 7; i++) {
                float x = 58f + i * 64f;
                float y = 108f - (float) Math.sin(i * 0.82f) * 28f;
                Path2D.Float blade = new Path2D.Float();
                blade.moveTo(x, y);
                blade.lineTo(x + 44f, y - 16f);
                blade.lineTo(x + 18f, y + 12f);
                blade.closePath();
                g.setColor(alpha(i % 2 == 0 ? a.secondary : a.primary, 155));
                g.fill(blade);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage bakeBolt(Assets a) {
        BufferedImage image = canvas(128, 512);
        Graphics2D g = aa(image);
        Random random = new Random(0xB017L + a.style.ordinal() * 811L);
        try {
            Path2D.Float bolt = new Path2D.Float();
            float x = 64f;
            bolt.moveTo(x, 4f);
            for (int i = 1; i <= 11; i++) {
                float y = 4f + i * 45f;
                x = 64f + (random.nextFloat() - 0.5f) * (i % 2 == 0 ? 58f : 86f);
                bolt.lineTo(x, y);
            }
            stroke(g, bolt, 28f, alpha(a.primary, 26));
            stroke(g, bolt, 17f, alpha(a.primary, 60));
            stroke(g, bolt, 9f, alpha(a.secondary, 170));
            stroke(g, bolt, 3f, alpha(a.hot, 255));
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage canvas(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage sanitizeTexture(BufferedImage image) {
        if (image == null) return null;
        int w = image.getWidth();
        int h = image.getHeight();
        int border = Math.min(5, Math.max(1, Math.min(w, h) / 12));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                int alpha = argb >>> 24;
                if (x < border || y < border || x >= w - border || y >= h - border
                        || alpha <= 1) {
                    image.setRGB(x, y, 0);
                }
            }
        }
        return image;
    }

    private static Graphics2D aa(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static void stroke(Graphics2D g, java.awt.Shape shape, float width, Color color) {
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        g.draw(shape);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FakeImage toFake(BufferedImage image) {
        if (image == null || ImageBuilder.builder == null) return null;
        try {
            return ((ImageBuilder) ImageBuilder.builder).build(image);
        } catch (Throwable ignored) {
            FakeImage out = ImageBuilder.builder.build(image.getWidth(), image.getHeight());
            if (out == null) return null;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    out.setRGB(x, y, image.getRGB(x, y));
                }
            }
            return out;
        }
    }

    private static float screenX(float worldX, float siz, int stagePos) {
        return (worldX * 0.32f + 200f) * siz + stagePos;
    }

    private static float visualRadius(Visual visual, float siz) {
        if (visual == null || !finite(siz) || siz <= 0f) return 0f;
        return Math.min(MAX_VISUAL_RADIUS,
                worldRadiusForShadowWidth(visual.shadowWidth) * 0.32f * siz);
    }

    private static float groundY(int layer, float siz, int midh) {
        return midh - (156 - layer * 4) * siz;
    }

    private static void resetComposite(FakeGraphics g) {
        try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private static int[] palette(AdventureLandingVfx style) {
        switch (style) {
            case SOLAR: return new int[]{255, 190, 60};
            case VOID: return new int[]{175, 75, 255};
            default: return new int[]{75, 220, 255};
        }
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp255(alpha));
    }

    private static Color mix(Color a, Color b, float amount) {
        float t = clamp01(amount);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static int argb(int alpha, Color color) {
        return (clamp255(alpha) << 24) | (color.getRed() << 16)
                | (color.getGreen() << 8) | color.getBlue();
    }

    private static float easeOut(float value) {
        float inv = 1f - clamp01(value);
        return 1f - inv * inv * inv;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
