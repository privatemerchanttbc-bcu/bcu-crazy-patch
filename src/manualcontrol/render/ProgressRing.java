package manualcontrol.render;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Field;

public final class ProgressRing {

    private static Field graphicsField;
    private static Field transformDataField;
    private static long lastDrawLog = 0;
    private static boolean readyActive = false;
    private static long readyStartMs = 0;
    private static final long BEAM_DURATION_MS = 650L;
    private static long beamStartMs = 0;
    private static int beamBaseX = 0;
    private static int beamBaseY = 0;
    private static int beamTargetX = 0;
    private static int beamTargetY = 0;
    private static int beamRadius = 62;

    private static volatile float drawAlphaScale = 1f;

    private ProgressRing() {}

    public static void draw(FakeGraphics gra, int cx, int cy, float progress, boolean isEnemyTarget) {
        draw(gra, cx, cy, 62, progress, isEnemyTarget, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static void draw(FakeGraphics gra, int cx, int cy, int baseRadius, float progress, boolean isEnemyTarget) {
        draw(gra, cx, cy, baseRadius, progress, isEnemyTarget, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static void draw(FakeGraphics gra, int cx, int cy, int baseRadius, float progress,
                            boolean isEnemyTarget, int baseX, int baseY) {
        draw(gra, cx, cy, baseRadius, progress, isEnemyTarget, baseX, baseY, 1f);
    }

    public static void draw(FakeGraphics gra, int cx, int cy, int baseRadius, float progress,
                            boolean isEnemyTarget, int baseX, int baseY, float opacity) {
        drawAlphaScale = Math.max(0f, Math.min(1f, opacity));
        try {
            drawInternal(gra, cx, cy, baseRadius, progress, isEnemyTarget, baseX, baseY);
        } finally {
            drawAlphaScale = 1f;
        }
    }

    private static void drawInternal(FakeGraphics gra, int cx, int cy, int baseRadius, float progress,
                            boolean isEnemyTarget, int baseX, int baseY) {
        long now = System.currentTimeMillis();
        float p = clamp(progress);
        int radius = Math.max(28, Math.min(240, baseRadius));
        boolean hasBeamOrigin = baseX != Integer.MIN_VALUE && baseY != Integer.MIN_VALUE;
        if (now - lastDrawLog > 1000) {
            String klass = gra == null ? "null" : gra.getClass().getName();
            manualcontrol.Logger.log("ArcaneCircle.draw: graphics=" + klass + " cx=" + cx + " cy=" + cy
                    + " radius=" + radius
                    + " placement=under-ground"
                    + " progress=" + String.format("%.0f%%", p * 100));
            lastDrawLog = now;
        }

        boolean ready = p >= 0.999f;
        if (ready && !readyActive) {
            readyActive = true;
            readyStartMs = now;
            if (hasBeamOrigin) triggerBeam(now, baseX, baseY, cx, cy, radius);
            manualcontrol.Logger.log("ArcaneCircle CLIMAX burst active");
        } else if (!ready) {
            readyActive = false;
            readyStartMs = 0;
        }
        if (ready && hasBeamOrigin && isBeamActive(now)) {
            rememberBeam(baseX, baseY, cx, cy, radius);
        }

        Graphics2D g2 = unwrap(gra);
        if (g2 != null) {
            drawNice(g2, cx, cy, radius, p, isEnemyTarget, now, ready);
        } else {
            drawSafeFallback(gra, cx, cy, radius, p, isEnemyTarget, now, ready);
        }
    }

    public static void drawBeamTrail(FakeGraphics gra) {
        long now = System.currentTimeMillis();
        if (!isBeamActive(now) || gra == null) return;
        Graphics2D g2 = unwrap(gra);
        if (g2 != null) {
            AffineTransform oldXform = g2.getTransform();
            Object oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            Color oldColor = g2.getColor();
            Stroke oldStroke = g2.getStroke();
            try {
                g2.setTransform(new AffineTransform());
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawBeamNice(g2, now);
            } finally {
                g2.setTransform(oldXform);
                g2.setColor(oldColor);
                g2.setStroke(oldStroke);
                if (oldAA != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
            }
        } else {
            FakeTransform oldTransform = pushIdentityTransform(gra);
            try {
                drawBeamFallback(gra, now);
            } finally {
                popTransform(gra, oldTransform);
            }
        }
    }

    public static void triggerSuccessBeam(int baseX, int baseY, int targetX, int targetY, int radius) {
        triggerBeam(System.currentTimeMillis(), baseX, baseY, targetX, targetY, Math.max(30, Math.min(68, radius)));
    }

    private static void drawNice(Graphics2D g, int cx, int cy, int radius, float progress,
                                 boolean isEnemyTarget, long now, boolean ready) {
        AffineTransform oldXform = g.getTransform();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Color oldColor = g.getColor();
        Font oldFont = g.getFont();
        Stroke oldStroke = g.getStroke();

        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            long readyElapsed = readyStartMs == 0 ? 0 : now - readyStartMs;
            int r = Math.round(radius * climaxScale(ready, readyElapsed));
            float outerPhase = phaseProgress(progress, 0.00f, 0.20f);
            float glyphPhase = phaseProgress(progress, 0.40f, 0.60f);
            float orbitPhase = phaseProgress(progress, 0.60f, 0.80f);
            float overloadPhase = phaseProgress(progress, 0.80f, 1.00f);
            int stage = ready ? 5 : Math.min(4, (int) (progress * 5f));
            float speed = 0.70f + stage * 0.48f + overloadPhase * 1.35f;
            float heat = overloadPhase;

            Color cold = isEnemyTarget ? new Color(67, 111, 255) : new Color(42, 142, 255);
            Color cyan = new Color(94, 237, 255);
            Color whiteHot = new Color(255, 254, 236);
            Color hot = progress < 0.80f
                    ? mix(cold, cyan, progress / 0.80f)
                    : mix(cyan, whiteHot, overloadPhase);
            Color deep = isEnemyTarget ? new Color(82, 38, 180) : new Color(24, 46, 124);
            Color accent = mix(new Color(48, 208, 255), Color.WHITE, heat);

            drawAura(g, cx, cy, r, progress, hot, deep, now, ready);
            drawGlyphLines(g, cx, cy, r, glyphPhase, overloadPhase, hot, accent, ready);
            drawConcentricArcs(g, cx, cy, r, outerPhase, glyphPhase, orbitPhase, overloadPhase,
                    hot, accent, deep, now, speed, ready);
            drawRunicTicks(g, cx, cy, r, progress, overloadPhase, hot, accent, now, ready);
            drawOrbitingParticles(g, cx, cy, r, orbitPhase, overloadPhase, hot, accent, now, speed, ready);
            drawMilestoneMarkers(g, cx, cy, r, progress, hot, accent, now, ready);

            if (ready) {
                drawClimax(g, cx, cy, r, hot, accent, now, readyElapsed);
            } else if (overloadPhase > 0f) {
                drawPreClimax(g, cx, cy, r, overloadPhase, hot, accent, now);
            }

            drawLabel(g, cx, cy, r, progress, ready, hot);
        } finally {
            g.setTransform(oldXform);
            g.setColor(oldColor);
            g.setFont(oldFont);
            g.setStroke(oldStroke);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    public static void drawControlMarker(FakeGraphics gra, int cx, int headTopY, long now, float opacity) {
        if (gra == null || opacity <= 0.01f) return;
        drawAlphaScale = Math.max(0f, Math.min(1f, opacity));
        FakeTransform oldT = pushIdentityTransform(gra);
        try {
            Color cyan = new Color(94, 237, 255);
            Color white = new Color(224, 250, 255);
            float bob = (float) Math.sin(now * 0.006) * 4f;
            int gap = 14;
            int tipY = Math.round(headTopY - gap + bob);
            int armW = 13;
            int armH = 9;

            setAlpha(gra, 70);
            gra.setColor(cyan.getRed(), cyan.getGreen(), cyan.getBlue());
            fillDot(gra, cx, tipY - armH, Math.round(11 + Math.abs(bob)));

            for (int k = 0; k < 2; k++) {
                int yTop = tipY - k * (armH + 3);
                int yTip = yTop + armH;
                Color c = k == 0 ? white : cyan;
                setAlpha(gra, k == 0 ? 245 : 175);
                gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
                drawThickLine(gra, cx - armW, yTop, cx, yTip, 3);
                drawThickLine(gra, cx + armW, yTop, cx, yTip, 3);
            }
            resetAlpha(gra);
        } catch (Throwable ignored) {
        } finally {
            popTransform(gra, oldT);
            drawAlphaScale = 1f;
        }
    }

    private static void triggerBeam(long now, int baseX, int baseY, int targetX, int targetY, int radius) {
        beamStartMs = now;
        rememberBeam(baseX, baseY, targetX, targetY, radius);
    }

    private static void rememberBeam(int baseX, int baseY, int targetX, int targetY, int radius) {
        beamBaseX = baseX;
        beamBaseY = baseY;
        beamTargetX = targetX;
        beamTargetY = targetY;
        beamRadius = Math.max(30, Math.min(68, radius));
    }

    private static boolean isBeamActive(long now) {
        return beamStartMs > 0 && now - beamStartMs >= 0 && now - beamStartMs <= BEAM_DURATION_MS;
    }

    private static void drawBeamNice(Graphics2D g, long now) {
        if (!isBeamActive(now)) return;
        float t = clamp((now - beamStartMs) / (float) BEAM_DURATION_MS);
        float fade = 1f - t;
        float pulse = wave(now, 0.030, 0);
        float core = Math.max(2.0f, Math.min(6.0f, beamRadius * 0.052f));

        g.setStroke(new BasicStroke(core * 3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(new Color(58, 218, 255), (int) (34 * fade)));
        g.drawLine(beamBaseX, beamBaseY, beamTargetX, beamTargetY);

        g.setStroke(new BasicStroke(core * 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(new Color(132, 244, 255), (int) ((82 + pulse * 22) * fade)));
        g.drawLine(beamBaseX, beamBaseY, beamTargetX, beamTargetY);

        g.setStroke(new BasicStroke(core * 0.72f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(Color.WHITE, (int) (172 * fade)));
        g.drawLine(beamBaseX, beamBaseY, beamTargetX, beamTargetY);

        int impact = Math.round(beamRadius * (0.20f + pulse * 0.05f));
        g.setStroke(new BasicStroke(Math.max(1.2f, core * 0.55f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / 10.0 + t * 0.9;
            int inner = Math.round(impact * 0.34f);
            int outer = Math.round(impact * (0.86f + t * 0.62f));
            int x0 = beamTargetX + (int) (Math.cos(a) * inner);
            int y0 = beamTargetY + (int) (Math.sin(a) * inner);
            int x1 = beamTargetX + (int) (Math.cos(a) * outer);
            int y1 = beamTargetY + (int) (Math.sin(a) * outer);
            g.setColor(withAlpha(i % 2 == 0 ? Color.WHITE : new Color(94, 237, 255),
                    (int) (150 * fade)));
            g.drawLine(x0, y0, x1, y1);
        }

        int dot = Math.max(3, Math.round(core + pulse * 3f));
        g.setColor(withAlpha(Color.WHITE, (int) (210 * fade)));
        g.fillOval(beamTargetX - dot, beamTargetY - dot, dot * 2, dot * 2);
    }

    private static void drawBeamFallback(FakeGraphics gra, long now) {
        if (!isBeamActive(now)) return;
        float fade = clamp(1f - (now - beamStartMs) / (float) BEAM_DURATION_MS);
        int alpha = Math.max(26, Math.min(92, (int) (92 * fade)));
        setAlpha(gra, alpha);
        gra.setColor(94, 237, 255);
        drawThinBeamLine(gra, beamBaseX, beamBaseY, beamTargetX, beamTargetY, 2);
        if (fade > 0.45f) {
            setAlpha(gra, Math.max(40, Math.min(120, (int) (120 * fade))));
            gra.setColor(255, 255, 255);
            drawThinBeamLine(gra, beamBaseX, beamBaseY, beamTargetX, beamTargetY, 1);
        }
        int size = Math.max(2, Math.round(beamRadius * 0.045f * fade));
        gra.fillRect(beamTargetX - size, beamTargetY - size, size * 2, size * 2);
        resetAlpha(gra);
    }

    private static void drawAura(Graphics2D g, int cx, int cy, int r, float progress,
                                 Color hot, Color deep, long now, boolean ready) {
        float pulse = wave(now, 0.010, 0);
        boolean groundPlaced = r >= 68;
        int outer = Math.round(r * (ready ? 1.32f : 1.05f + progress * 0.12f) + pulse * 8f);
        g.setColor(withAlpha(hot, groundPlaced ? (ready ? 24 : 8 + (int) (progress * 12))
                : (ready ? 42 : 14 + (int) (progress * 20))));
        g.fillOval(cx - outer, cy - outer, outer * 2, outer * 2);

        int shadow = Math.round(r * 0.78f);
        g.setColor(withAlpha(deep, groundPlaced ? 10 + (int) (progress * 8)
                : 18 + (int) (progress * 18)));
        g.fillOval(cx - shadow, cy - shadow, shadow * 2, shadow * 2);

        int core = Math.round(8 + progress * 7 + pulse * 3);
        g.setColor(withAlpha(Color.WHITE, ready ? 240 : 115 + (int) (heat(progress) * 88)));
        g.fillOval(cx - core / 2, cy - core / 2, core, core);
    }

    private static void drawGlyphLines(Graphics2D g, int cx, int cy, int r, float glyphPhase,
                                       float overloadPhase, Color hot, Color accent, boolean ready) {
        if (!ready && glyphPhase <= 0f) return;
        int spokes = 8;
        float lineProgress = ready ? 1f : glyphPhase;
        int alpha = ready ? 230 : 46 + (int) (lineProgress * 128) + (int) (overloadPhase * 48);
        g.setStroke(new BasicStroke(ready ? 2.4f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (int i = 0; i < spokes; i++) {
            float fill = ready ? 1f : clamp(lineProgress * spokes - i);
            if (fill <= 0f) continue;
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / spokes;
            int inner = Math.round(r * 0.18f);
            int outer = Math.round((r * 0.76f) * fill);
            int x0 = cx + (int) (Math.cos(a) * inner);
            int y0 = cy + (int) (Math.sin(a) * inner);
            int x1 = cx + (int) (Math.cos(a) * outer);
            int y1 = cy + (int) (Math.sin(a) * outer);
            g.setColor(withAlpha(i % 2 == 0 ? Color.WHITE : hot, alpha));
            g.drawLine(x0, y0, x1, y1);
        }

        if (lineProgress > 0.34f || ready) {
            float starIn = ready ? 1f : clamp((lineProgress - 0.34f) / 0.66f);
            drawStarPolygon(g, cx, cy, Math.round(r * 0.52f * (0.78f + starIn * 0.22f)),
                    spokes, hot, ready ? 215 : 44 + (int) (starIn * 136) + (int) (overloadPhase * 44), 0.0);
        }
        if (lineProgress > 0.66f || ready) {
            float innerIn = ready ? 1f : clamp((lineProgress - 0.66f) / 0.34f);
            drawStarPolygon(g, cx, cy, Math.round(r * 0.33f * (0.70f + innerIn * 0.30f)),
                    6, accent, ready ? 230 : 48 + (int) (innerIn * 138) + (int) (overloadPhase * 44), Math.PI / 6.0);
        }
    }

    private static void drawConcentricArcs(Graphics2D g, int cx, int cy, int r,
                                           float outerPhase, float glyphPhase, float orbitPhase, float overloadPhase,
                                           Color hot, Color accent, Color deep,
                                           long now, float speed, boolean ready) {
        float outerRot = (now % 360000L) * 0.050f * speed;
        float middleRot = (now % 360000L) * 0.064f * speed;
        float innerRot = (now % 360000L) * 0.086f * speed;
        float outerVisible = ready ? 1f : outerPhase;
        float middleVisible = ready ? 1f : glyphPhase;
        float innerVisible = ready ? 1f : orbitPhase;

        drawArcSegments(g, cx, cy, r + 8, outerRot, outerVisible, true, true,
                hot, 3.4f + overloadPhase * 0.9f, 28, 0.64f, ready ? 244 : 92 + (int) (outerVisible * 130));
        if (middleVisible > 0f) {
            drawArcSegments(g, cx, cy, r - 7, middleRot, middleVisible, false, true,
                    mix(deep, accent, 0.74f), 2.1f + overloadPhase * 0.6f, 24, 0.48f,
                    ready ? 210 : 64 + (int) (middleVisible * 126));
        }
        if (innerVisible > 0f) {
            drawArcSegments(g, cx, cy, r - 22, innerRot, innerVisible, true, true,
                    mix(hot, Color.WHITE, overloadPhase * 0.72f), 1.8f + overloadPhase * 0.5f, 18, 0.58f,
                    ready ? 224 : 66 + (int) (innerVisible * 130));
        }

        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (middleVisible > 0f) {
            g.setColor(withAlpha(accent, ready ? 142 : 36 + (int) (middleVisible * 78) + (int) (overloadPhase * 40)));
            g.drawOval(cx - (r - 15), cy - (r - 15), (r - 15) * 2, (r - 15) * 2);
        }
        g.setColor(withAlpha(Color.WHITE, ready ? 170 : 26 + (int) (outerVisible * 52) + (int) (overloadPhase * 58)));
        g.drawOval(cx - (r + 15), cy - (r + 15), (r + 15) * 2, (r + 15) * 2);
    }

    private static void drawArcSegments(Graphics2D g, int cx, int cy, int r, float rotationDeg,
                                        float visibleProgress, boolean clockwise, boolean progressive,
                                        Color color, float strokeWidth, int segments,
                                        float segmentFill, int alpha) {
        float step = 360f / segments;
        int direction = clockwise ? -1 : 1;
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (int i = 0; i < segments; i++) {
            float lit = progressive ? clamp(visibleProgress * segments - i) : visibleProgress;
            if (lit <= 0f) {
                if (!progressive) continue;
                g.setColor(withAlpha(new Color(20, 38, 72), 42));
                int ghostStart = Math.round(direction * (rotationDeg + i * step));
                int ghostExtent = Math.round(direction * step * segmentFill * 0.74f);
                g.drawArc(cx - r, cy - r, r * 2, r * 2, ghostStart, ghostExtent);
                continue;
            }

            int segAlpha = Math.max(0, Math.min(255, Math.round(alpha * (progressive ? (0.28f + lit * 0.72f) : 1f))));
            g.setColor(withAlpha(color, segAlpha));
            int start = Math.round(direction * (rotationDeg + i * step));
            int extent = Math.max(2, Math.round(step * segmentFill * lit));
            g.drawArc(cx - r, cy - r, r * 2, r * 2, start, extent * direction);
        }
    }

    private static void drawRunicTicks(Graphics2D g, int cx, int cy, int r, float progress,
                                       float overloadPhase, Color hot, Color accent, long now, boolean ready) {
        int total = 48;
        float spin = (now % 360000L) * 0.026f * (1f + progress * 1.2f + overloadPhase * 2.1f);
        g.setStroke(new BasicStroke(ready ? 2.2f : 1.5f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));

        for (int i = 0; i < total; i++) {
            float lit = ready ? 1f : clamp(progress * total - i);
            double a = -Math.PI / 2.0 + Math.toRadians(spin) + i * Math.PI * 2.0 / total;
            int len = (i % 6 == 0) ? 13 : ((i % 3 == 0) ? 9 : 6);
            int inner = r + 2;
            int outer = r + 2 + len;
            int x0 = cx + (int) (Math.cos(a) * inner);
            int y0 = cy + (int) (Math.sin(a) * inner);
            int x1 = cx + (int) (Math.cos(a) * outer);
            int y1 = cy + (int) (Math.sin(a) * outer);

            if (lit <= 0f) {
                g.setColor(withAlpha(new Color(18, 32, 60), 82));
                g.drawLine(x0, y0, x1, y1);
                continue;
            }

            float twinkle = 0.65f + wave(now, 0.018, i * 0.55) * 0.35f;
            Color c = (i % 4 == 0) ? accent : hot;
            g.setColor(withAlpha(mix(c, Color.WHITE, lit * (0.32f + overloadPhase * 0.42f)),
                    (int) ((98 + lit * 142 + overloadPhase * 38) * twinkle)));
            g.drawLine(x0, y0, x1, y1);

            if (i % 6 == 0 && lit > 0.45f) {
                int gx = cx + (int) (Math.cos(a) * (r - 11));
                int gy = cy + (int) (Math.sin(a) * (r - 11));
                drawRuneDiamond(g, gx, gy, ready ? 7 : 5 + Math.round(lit * 2f),
                        withAlpha(Color.WHITE, ready ? 210 : 92 + (int) (lit * 100)));
            }
        }
    }

    private static void drawOrbitingParticles(Graphics2D g, int cx, int cy, int r, float orbitPhase,
                                              float overloadPhase, Color hot, Color accent,
                                              long now, float speed, boolean ready) {
        int count = 6;
        float orbitAlpha = ready ? 1f : orbitPhase;
        if (orbitAlpha <= 0f) return;
        int activeCount = ready ? count : Math.max(1, Math.min(count, (int) Math.ceil(orbitPhase * count)));

        for (int i = 0; i < activeCount; i++) {
            int rr = r + 18 + (i % 2) * 8;
            float period = 2050f - i * 135f;
            double dir = i % 2 == 0 ? 1.0 : -1.0;
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / count
                    + dir * (now % (long) period) / period * Math.PI * 2.0 * speed;
            float t = 0.45f + 0.55f * wave(now, 0.014, i);
            Color c = i % 2 == 0 ? hot : accent;

            for (int tail = 4; tail >= 1; tail--) {
                double ta = a - dir * tail * 0.105;
                int tx = cx + (int) (Math.cos(ta) * rr);
                int ty = cy + (int) (Math.sin(ta) * rr);
                int ts = Math.max(1, 4 - tail);
                g.setColor(withAlpha(c, (int) ((24 + tail * 12) * orbitAlpha)));
                g.fillOval(tx - ts, ty - ts, ts * 2, ts * 2);
            }

            int x = cx + (int) (Math.cos(a) * rr);
            int y = cy + (int) (Math.sin(a) * rr);
            int size = ready ? 6 + (i % 2) : 4 + Math.round(orbitPhase * 4f + overloadPhase * 3f + t * 2f);
            g.setColor(withAlpha(mix(c, Color.WHITE, ready ? 0.72f : t * (0.36f + overloadPhase * 0.38f)),
                    (int) ((128 + orbitPhase * 76 + overloadPhase * 66) * orbitAlpha)));
            g.fillOval(x - size, y - size, size * 2, size * 2);
        }
    }

    private static void drawMilestoneMarkers(Graphics2D g, int cx, int cy, int r, float progress,
                                             Color hot, Color accent, long now, boolean ready) {
        float[] marks = new float[]{0f, 0.20f, 0.40f, 0.60f, 0.80f, 1.00f};
        int rr = r + 31;

        for (int i = 0; i < marks.length; i++) {
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / marks.length;
            int x = cx + (int) (Math.cos(a) * rr);
            int y = cy + (int) (Math.sin(a) * rr);
            boolean reached = ready || progress >= marks[i] - 0.001f;
            boolean current = !ready && i < marks.length - 1
                    && progress >= marks[i] && progress < marks[i + 1];
            float local = i == marks.length - 1
                    ? (ready ? 1f : phaseProgress(progress, 0.80f, 1.00f))
                    : phaseProgress(progress, marks[i], marks[i + 1]);
            int size = reached ? 7 : 4;
            if (current) size += Math.round(wave(now, 0.020, i) * 4f);
            if (ready && i == marks.length - 1) size += Math.round(wave(now, 0.028, i) * 5f);

            Color c;
            int alpha;
            if (reached) {
                c = i >= 4 ? mix(hot, Color.WHITE, 0.52f + local * 0.36f) : mix(accent, hot, i / 5f);
                alpha = current || ready ? 235 : 160;
            } else {
                c = new Color(18, 34, 70);
                alpha = 92;
            }

            g.setColor(withAlpha(c, Math.min(255, alpha)));
            drawRuneDiamond(g, x, y, size, withAlpha(c, Math.min(255, alpha)));

            if (current || (ready && i == marks.length - 1)) {
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int pulse = Math.round(size + 7 + wave(now, 0.018, i) * 7f);
                g.setColor(withAlpha(Color.WHITE, ready ? 178 : 118));
                g.drawOval(x - pulse, y - pulse, pulse * 2, pulse * 2);
            }
        }
    }

    private static void drawPreClimax(Graphics2D g, int cx, int cy, int r, float overloadPhase,
                                      Color hot, Color accent, long now) {
        float power = clamp(overloadPhase);
        int total = 16;
        int rr = Math.round(r + 31 + wave(now, 0.017, 0) * 13);
        for (int i = 0; i < total; i++) {
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / total + now * 0.0007;
            int x = cx + (int) (Math.cos(a) * rr);
            int y = cy + (int) (Math.sin(a) * rr);
            int size = 2 + (i % 4);
            g.setColor(withAlpha(i % 2 == 0 ? hot : accent, (int) (52 + power * 128)));
            g.fillOval(x - size, y - size, size * 2, size * 2);
        }
    }

    private static void drawClimax(Graphics2D g, int cx, int cy, int r, Color hot,
                                   Color accent, long now, long elapsed) {
        if (elapsed < 260L) {
            float t = elapsed / 260f;
            int flashR = Math.round(r * (1.08f + t * 0.45f));
            g.setColor(withAlpha(Color.WHITE, (int) ((1f - t) * 170)));
            g.fillOval(cx - flashR, cy - flashR, flashR * 2, flashR * 2);
        }

        float burst = clamp((elapsed - 110L) / 720f);
        if (burst > 0f && burst < 1f) {
            int rays = 28;
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < rays; i++) {
                double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / rays + wave(now, 0.007, i) * 0.035;
                int inner = Math.round(r * (0.35f + burst * 0.55f));
                int outer = Math.round(r * (0.92f + burst * 1.18f));
                int x0 = cx + (int) (Math.cos(a) * inner);
                int y0 = cy + (int) (Math.sin(a) * inner);
                int x1 = cx + (int) (Math.cos(a) * outer);
                int y1 = cy + (int) (Math.sin(a) * outer);
                Color c = i % 3 == 0 ? Color.WHITE : (i % 2 == 0 ? accent : hot);
                g.setColor(withAlpha(c, (int) ((1f - burst) * 225)));
                g.drawLine(x0, y0, x1, y1);
            }
        }

        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / 8.0;
            int x = cx + (int) (Math.cos(a) * (r * 0.62f));
            int y = cy + (int) (Math.sin(a) * (r * 0.62f));
            g.setColor(withAlpha(Color.WHITE, 215));
            g.drawLine(cx, cy, x, y);
            drawRuneDiamond(g, x, y, 11, withAlpha(i % 2 == 0 ? Color.WHITE : accent, 222));
        }
    }

    private static void drawLabel(Graphics2D g, int cx, int cy, int r, float progress,
                                  boolean ready, Color hot) {
        if (progress <= 0.04f && !ready) return;
        String text = ready ? "CONTROL" : String.format("%d%%", Math.min(99, (int) (progress * 100)));
        g.setFont(g.getFont().deriveFont(Font.BOLD, ready ? 16f : 13f));
        int w = g.getFontMetrics().stringWidth(text);
        int y = cy + r + (ready ? 34 : 29);
        g.setColor(new Color(0, 0, 0, 190));
        g.drawString(text, cx - w / 2 + 2, y + 2);
        g.setColor(ready ? Color.WHITE : mix(Color.WHITE, hot, 0.28f));
        g.drawString(text, cx - w / 2, y);

        if (!ready) {
            int stage = Math.min(4, (int) (progress * 5f));
            String band = (stage * 20) + "-" + ((stage + 1) * 20);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            int bw = g.getFontMetrics().stringWidth(band);
            int by = y + 13;
            g.setColor(new Color(0, 0, 0, 170));
            g.drawString(band, cx - bw / 2 + 1, by + 1);
            g.setColor(withAlpha(hot, 210));
            g.drawString(band, cx - bw / 2, by);
        }
    }

    private static void drawSafeFallback(FakeGraphics gra, int cx, int cy, int r, float progress,
                                         boolean isEnemyTarget, long now, boolean ready) {
        if (gra == null) return;
        FakeTransform oldTransform = pushIdentityTransform(gra);
        try {
            float p = clamp(progress);
            r = Math.max(28, Math.min(240, r));
            float outerPhase = phaseProgress(p, 0.00f, 0.20f);
            float glyphPhase = phaseProgress(p, 0.40f, 0.60f);
            float orbitPhase = phaseProgress(p, 0.60f, 0.80f);
            float overloadPhase = phaseProgress(p, 0.80f, 1.00f);
            int stage = ready ? 5 : Math.min(4, (int) (p * 5f));
            float speed = 0.70f + stage * 0.48f + overloadPhase * 1.35f;

            Color cold = isEnemyTarget ? new Color(67, 111, 255) : new Color(42, 142, 255);
            Color cyan = new Color(94, 237, 255);
            Color hot = p < 0.80f ? mix(cold, cyan, p / 0.80f) : mix(cyan, Color.WHITE, overloadPhase);
            Color dim = isEnemyTarget ? new Color(38, 30, 88) : new Color(15, 36, 78);
            Color accent = mix(cyan, Color.WHITE, overloadPhase);

            if (ready) {
                hot = new Color(255, 205, 66);
                accent = new Color(255, 247, 200);
                dim = new Color(94, 64, 12);
            }

            drawFallbackArcs(gra, cx, cy, r + 8, (now % 360000L) * 0.050f * speed,
                    ready ? 1f : outerPhase, true, true, hot, dim, 28, 0.64f);
            if (ready || glyphPhase > 0f) {
                drawFallbackArcs(gra, cx, cy, r - 7, (now % 360000L) * 0.064f * speed,
                        ready ? 1f : glyphPhase, false, true, accent, dim, 24, 0.48f);
            }
            if (ready || orbitPhase > 0f) {
                drawFallbackArcs(gra, cx, cy, r - 22, (now % 360000L) * 0.086f * speed,
                        ready ? 1f : orbitPhase, true, true, mix(hot, Color.WHITE, overloadPhase * 0.72f),
                        dim, 18, 0.58f);
            }

            drawFallbackGlyphs(gra, cx, cy, r, glyphPhase, hot, accent, ready);
            drawFallbackRunes(gra, cx, cy, r, p, hot, accent, dim, now, ready);
            drawFallbackParticles(gra, cx, cy, r, orbitPhase, overloadPhase, hot, accent, now, speed, ready);
            drawFallbackMilestones(gra, cx, cy, r, p, hot, accent, ready);

            if (overloadPhase > 0f || ready) {
                int sparks = ready ? 24 : 12;
                setAlpha(gra, ready ? 92 : 48 + (int) (overloadPhase * 58));
                gra.setColor(255, 255, 255);
                for (int i = 0; i < sparks; i++) {
                    double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / sparks + now * 0.0011;
                    int inner = Math.round(r * (ready ? 0.72f : 0.86f));
                    int outer = Math.round(r * (ready ? 1.18f : 1.08f + overloadPhase * 0.20f));
                    drawThickLine(gra,
                            cx + (int) (Math.cos(a) * inner),
                            cy + (int) (Math.sin(a) * inner),
                            cx + (int) (Math.cos(a) * outer),
                            cy + (int) (Math.sin(a) * outer),
                            ready ? 2 : 1);
                }
            }

            if (ready) {
                drawReadyFallback(gra, cx, cy, r, now);
            }
            resetAlpha(gra);
        } finally {
            popTransform(gra, oldTransform);
        }
    }

    private static void drawReadyFallback(FakeGraphics gra, int cx, int cy, int r, long now) {
        Color gold = new Color(255, 205, 66);
        Color whiteHot = new Color(255, 248, 205);

        long period = 720L;
        for (int wv = 0; wv < 3; wv++) {
            float t = (((now + wv * (period / 3)) % period) / (float) period);
            float waveR = r * (0.85f + t * 1.55f);
            int alpha = (int) ((1f - t) * 150f);
            if (alpha <= 4) continue;
            setAlpha(gra, alpha);
            Color c = wv == 1 ? whiteHot : gold;
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            drawArcApprox(gra, cx, cy, Math.round(waveR), 0f, 360f, t < 0.4f ? 3 : 2);
        }

        float pulse = wave(now, 0.020, 0);
        int coreR = Math.round(7 + pulse * 7);
        setAlpha(gra, 230);
        gra.setColor(whiteHot.getRed(), whiteHot.getGreen(), whiteHot.getBlue());
        fillDot(gra, cx, cy, coreR);
        setAlpha(gra, 120);
        gra.setColor(gold.getRed(), gold.getGreen(), gold.getBlue());
        fillDot(gra, cx, cy, coreR + 5);

        int rays = 12;
        float spin = now * 0.004f;
        setAlpha(gra, 210);
        for (int i = 0; i < rays; i++) {
            double a = spin + i * Math.PI * 2.0 / rays;
            int inner = Math.round(r * 1.16f);
            int outer = Math.round(r * (1.30f + pulse * 0.10f));
            Color c = i % 2 == 0 ? whiteHot : gold;
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            drawThickLine(gra,
                    cx + (int) (Math.cos(a) * inner), cy + (int) (Math.sin(a) * inner),
                    cx + (int) (Math.cos(a) * outer), cy + (int) (Math.sin(a) * outer), 3);
        }

        setAlpha(gra, 200);
        gra.setColor(gold.getRed(), gold.getGreen(), gold.getBlue());
        drawArcApprox(gra, cx, cy, Math.round(r * 1.10f), 0f, 360f, 2);
        resetAlpha(gra);
    }

    private static void drawFallbackArcs(FakeGraphics gra, int cx, int cy, int r, float rotationDeg,
                                         float visibleProgress, boolean clockwise, boolean progressive,
                                         Color litColor, Color dimColor, int segments, float segmentFill) {
        float step = 360f / segments;
        int direction = clockwise ? -1 : 1;
        for (int i = 0; i < segments; i++) {
            float lit = progressive ? clamp(visibleProgress * segments - i) : visibleProgress;
            Color c = lit > 0f ? litColor : dimColor;
            int alpha = lit > 0f ? 96 + (int) (Math.min(1f, lit) * 130f) : 58;
            setAlpha(gra, alpha);
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            float start = direction * (rotationDeg + i * step);
            float extent = direction * step * segmentFill * (lit > 0f ? Math.max(0.18f, Math.min(1f, lit)) : 0.65f);
            drawArcApprox(gra, cx, cy, r, start, extent, lit > 0f ? 2 : 1);
        }
    }

    private static void drawFallbackGlyphs(FakeGraphics gra, int cx, int cy, int r, float glyphPhase,
                                           Color hot, Color accent, boolean ready) {
        if (!ready && glyphPhase <= 0f) return;
        int spokes = 8;
        float lineProgress = ready ? 1f : glyphPhase;
        setAlpha(gra, ready ? 230 : 90 + (int) (lineProgress * 110));
        for (int i = 0; i < spokes; i++) {
            float fill = ready ? 1f : clamp(lineProgress * spokes - i);
            if (fill <= 0f) continue;
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / spokes;
            int inner = Math.round(r * 0.18f);
            int outer = Math.round((r * 0.76f) * fill);
            Color c = i % 2 == 0 ? Color.WHITE : hot;
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            drawThickLine(gra,
                    cx + (int) (Math.cos(a) * inner),
                    cy + (int) (Math.sin(a) * inner),
                    cx + (int) (Math.cos(a) * outer),
                    cy + (int) (Math.sin(a) * outer),
                    ready ? 2 : 1);
        }

        if (ready || lineProgress > 0.34f) {
            drawFallbackStar(gra, cx, cy, Math.round(r * 0.52f), 8, hot, ready ? 210 : 92 + (int) (lineProgress * 110), 0.0);
        }
        if (ready || lineProgress > 0.66f) {
            drawFallbackStar(gra, cx, cy, Math.round(r * 0.33f), 6, accent, ready ? 225 : 90 + (int) (lineProgress * 120), Math.PI / 6.0);
        }
    }

    private static void drawFallbackRunes(FakeGraphics gra, int cx, int cy, int r, float progress,
                                          Color hot, Color accent, Color dim, long now, boolean ready) {
        int total = 48;
        float spin = (now % 360000L) * 0.026f * (1f + progress * 1.2f);
        for (int i = 0; i < total; i++) {
            float lit = ready ? 1f : clamp(progress * total - i);
            double a = -Math.PI / 2.0 + Math.toRadians(spin) + i * Math.PI * 2.0 / total;
            int len = (i % 6 == 0) ? 15 : ((i % 3 == 0) ? 10 : 7);
            int inner = r + 3;
            int outer = r + 3 + len;
            Color c = lit > 0f ? (i % 4 == 0 ? accent : hot) : dim;
            int alpha = lit > 0f ? 105 + (int) (lit * 140) : 76;
            setAlpha(gra, alpha);
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            drawThickLine(gra,
                    cx + (int) (Math.cos(a) * inner),
                    cy + (int) (Math.sin(a) * inner),
                    cx + (int) (Math.cos(a) * outer),
                    cy + (int) (Math.sin(a) * outer),
                    (i % 6 == 0 && lit > 0.3f) ? 2 : 1);
            if (i % 6 == 0 && lit > 0.45f) {
                int gx = cx + (int) (Math.cos(a) * (r - 11));
                int gy = cy + (int) (Math.sin(a) * (r - 11));
                int s = 4 + Math.round(lit * 4f);
                setAlpha(gra, 120 + (int) (lit * 110));
                gra.setColor(255, 255, 255);
                fillDot(gra, gx, gy, s / 2);
            }
        }
    }

    private static void drawFallbackParticles(FakeGraphics gra, int cx, int cy, int r, float orbitPhase,
                                              float overloadPhase, Color hot, Color accent,
                                              long now, float speed, boolean ready) {
        float active = ready ? 1f : orbitPhase;
        if (active <= 0f) return;
        int count = 6;
        int activeCount = ready ? count : Math.max(1, Math.min(count, (int) Math.ceil(active * count)));
        setAlpha(gra, ready ? 235 : 120 + (int) (active * 95));
        for (int i = 0; i < activeCount; i++) {
            int rr = r + 20 + (i % 2) * 9;
            float period = 2050f - i * 135f;
            double dir = i % 2 == 0 ? 1.0 : -1.0;
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / count
                    + dir * (now % (long) period) / period * Math.PI * 2.0 * speed;
            Color c = i % 2 == 0 ? hot : accent;
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            int size = ready ? 10 : 6 + Math.round(active * 5f + overloadPhase * 3f);
            int x = cx + (int) (Math.cos(a) * rr);
            int y = cy + (int) (Math.sin(a) * rr);
            fillDot(gra, x, y, size / 2);
            for (int tail = 1; tail <= 3; tail++) {
                double ta = a - dir * tail * 0.12;
                int ts = Math.max(3, size - tail * 2);
                setAlpha(gra, 60 + (3 - tail) * 30);
                fillDot(gra, cx + (int) (Math.cos(ta) * rr),
                        cy + (int) (Math.sin(ta) * rr), ts / 2);
            }
        }
    }

    private static void drawFallbackMilestones(FakeGraphics gra, int cx, int cy, int r, float progress,
                                               Color hot, Color accent, boolean ready) {
        float[] marks = new float[]{0f, 0.20f, 0.40f, 0.60f, 0.80f, 1.00f};
        int rr = r + 33;
        for (int i = 0; i < marks.length; i++) {
            boolean reached = ready || progress >= marks[i] - 0.001f;
            double a = -Math.PI / 2.0 + i * Math.PI * 2.0 / marks.length;
            int x = cx + (int) (Math.cos(a) * rr);
            int y = cy + (int) (Math.sin(a) * rr);
            int size = reached ? 10 : 6;
            Color c = reached ? (i >= 4 ? Color.WHITE : mix(accent, hot, i / 5f)) : new Color(20, 36, 74);
            setAlpha(gra, reached ? 220 : 95);
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            fillDot(gra, x, y, size / 2);
        }
    }

    private static void drawFallbackStar(FakeGraphics gra, int cx, int cy, int r, int points,
                                         Color color, int alpha, double offset) {
        setAlpha(gra, alpha);
        gra.setColor(color.getRed(), color.getGreen(), color.getBlue());
        int step = points == 8 ? 3 : 2;
        for (int i = 0; i < points; i++) {
            double a0 = -Math.PI / 2.0 + offset + i * Math.PI * 2.0 / points;
            double a1 = -Math.PI / 2.0 + offset + ((i + step) % points) * Math.PI * 2.0 / points;
            drawThickLine(gra,
                    cx + (int) (Math.cos(a0) * r),
                    cy + (int) (Math.sin(a0) * r),
                    cx + (int) (Math.cos(a1) * r),
                    cy + (int) (Math.sin(a1) * r),
                    1);
        }
    }

    private static void drawArcApprox(FakeGraphics gra, int cx, int cy, int r, float startDeg, float extentDeg, int thickness) {
        int steps = Math.max(3, (int) (Math.abs(extentDeg) / 8f));
        double prevA = Math.toRadians(startDeg);
        int px = cx + (int) (Math.cos(prevA) * r);
        int py = cy + (int) (Math.sin(prevA) * r);
        for (int s = 1; s <= steps; s++) {
            double a = Math.toRadians(startDeg + extentDeg * s / (float) steps);
            int x = cx + (int) (Math.cos(a) * r);
            int y = cy + (int) (Math.sin(a) * r);
            drawThickLine(gra, px, py, x, y, thickness);
            px = x;
            py = y;
        }
    }

    private static void drawThickLine(FakeGraphics gra, int x0, int y0, int x1, int y1, int thickness) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(1, (int) (Math.sqrt(dx * dx + dy * dy) / 4.0));
        int size = Math.max(2, thickness + 1);
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int x = Math.round(x0 + dx * t);
            int y = Math.round(y0 + dy * t);
            gra.fillRect(x - size / 2, y - size / 2, size, size);
        }
    }

    private static void fillDot(FakeGraphics gra, int cx, int cy, int radius) {
        if (radius < 1) {
            gra.fillRect(cx, cy, 1, 1);
            return;
        }
        for (int dy = -radius; dy <= radius; dy++) {
            double inside = (double) radius * radius - (double) dy * dy;
            if (inside < 0) continue;
            int span = (int) Math.round(Math.sqrt(inside));
            gra.fillRect(cx - span, cy + dy, span * 2 + 1, 1);
        }
    }

    private static void drawThinBeamLine(FakeGraphics gra, int x0, int y0, int x1, int y1, int size) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(1, (int) (Math.sqrt(dx * dx + dy * dy) / 3.0));
        int dot = Math.max(1, size);
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int x = Math.round(x0 + dx * t);
            int y = Math.round(y0 + dy * t);
            gra.fillRect(x - dot / 2, y - dot / 2, dot, dot);
        }
    }

    private static FakeTransform pushIdentityTransform(FakeGraphics gra) {
        try {
            FakeTransform oldTransform = gra.getTransform();
            FakeTransform identity = gra.getTransform();
            Field f = transformDataField;
            if (f == null || f.getDeclaringClass() != identity.getClass()) {
                f = identity.getClass().getDeclaredField("data");
                f.setAccessible(true);
                transformDataField = f;
            }
            f.set(identity, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            gra.setTransform(identity);
            try { gra.delete(identity); } catch (Throwable ignored) {}
            return oldTransform;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void popTransform(FakeGraphics gra, FakeTransform oldTransform) {
        if (oldTransform == null) return;
        try {
            gra.setTransform(oldTransform);
        } catch (Throwable ignored) {
        } finally {
            try { gra.delete(oldTransform); } catch (Throwable ignored) {}
        }
    }

    private static void setAlpha(FakeGraphics gra, int alpha) {
        try {
            gra.setComposite(FakeGraphics.TRANS, clamp255(Math.round(alpha * drawAlphaScale)), 0);
        } catch (Throwable ignored) {}
    }

    private static void resetAlpha(FakeGraphics gra) {
        try {
            gra.setComposite(FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
    }

    private static void drawRectLine(FakeGraphics gra, int x0, int y0, int x1, int y1) {
        int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) / 4);
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            int x = Math.round(x0 + (x1 - x0) * t);
            int y = Math.round(y0 + (y1 - y0) * t);
            gra.fillRect(x - 1, y - 1, 3, 3);
        }
    }

    private static void drawStarPolygon(Graphics2D g, int cx, int cy, int r, int points,
                                        Color color, int alpha, double offset) {
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(color, alpha));
        int step = points == 8 ? 3 : 2;
        for (int i = 0; i < points; i++) {
            double a0 = -Math.PI / 2.0 + offset + i * Math.PI * 2.0 / points;
            double a1 = -Math.PI / 2.0 + offset + ((i + step) % points) * Math.PI * 2.0 / points;
            int x0 = cx + (int) (Math.cos(a0) * r);
            int y0 = cy + (int) (Math.sin(a0) * r);
            int x1 = cx + (int) (Math.cos(a1) * r);
            int y1 = cy + (int) (Math.sin(a1) * r);
            g.drawLine(x0, y0, x1, y1);
        }
    }

    private static void drawRuneDiamond(Graphics2D g, int cx, int cy, int size, Color color) {
        int h = Math.max(2, size / 2);
        int[] xs = new int[]{cx, cx + h, cx, cx - h};
        int[] ys = new int[]{cy - h, cy, cy + h, cy};
        g.setColor(color);
        g.fillPolygon(xs, ys, 4);
    }

    private static float climaxScale(boolean ready, long elapsed) {
        if (!ready) return 1f;
        if (elapsed < 170L) return 1f - easeOut(elapsed / 170f) * 0.18f;
        if (elapsed < 380L) return 0.82f + easeOut((elapsed - 170L) / 210f) * 0.24f;
        return 1.06f + wave(System.currentTimeMillis(), 0.012, 0) * 0.035f;
    }

    private static float hotRamp(float p) {
        return smoothstep(clamp((p - 0.72f) / 0.28f));
    }

    private static float phaseProgress(float p, float start, float end) {
        if (end <= start) return p >= end ? 1f : 0f;
        return smoothstep(clamp((p - start) / (end - start)));
    }

    private static float heat(float p) {
        return smoothstep(clamp(p));
    }

    private static float wave(long now, double speed, double phase) {
        return (float) ((Math.sin(now * speed + phase) + 1.0) * 0.5);
    }

    private static float smoothstep(float t) {
        t = clamp(t);
        return t * t * (3f - 2f * t);
    }

    private static float easeOut(float t) {
        t = clamp(t);
        return 1f - (1f - t) * (1f - t);
    }

    private static Graphics2D unwrap(FakeGraphics gra) {
        if (gra == null) return null;
        try {
            if (graphicsField != null && graphicsField.getDeclaringClass().isAssignableFrom(gra.getClass())) {
                Object g = graphicsField.get(gra);
                if (g instanceof Graphics2D) return (Graphics2D) g;
            }

            Class<?> c = gra.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    if (Graphics2D.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object g = f.get(gra);
                        if (g instanceof Graphics2D) {
                            graphicsField = f;
                            return (Graphics2D) g;
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Color mix(Color a, Color b, float t) {
        t = clamp(t);
        return new Color(
                clamp255((int) (a.getRed() + (b.getRed() - a.getRed()) * t)),
                clamp255((int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
                clamp255((int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t)),
                clamp255((int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t)));
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(Math.round(alpha * drawAlphaScale)));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
