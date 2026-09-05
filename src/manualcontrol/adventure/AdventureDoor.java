package manualcontrol.adventure;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import manualcontrol.reflect.BBPainterAccess;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

final class AdventureDoor {

    static final float DOOR_X = 900f;

    static final float ENTER_DISTANCE = 120f;

    private static final int BW = 256, BH = 448;

    private float clock;
    private float worldX = DOOR_X;
    private boolean baked, bakeFailed;
    private FakeImage ringTex, voidTex, dotLilac, dotViolet, moundTex;

    private final FakeImage[] swirlTex = new FakeImage[3];

    boolean canEnter(float playerPos) {
        return Math.abs(playerPos - worldX) < ENTER_DISTANCE;
    }

    void setWorldX(float worldX) { this.worldX = worldX; }
    float worldX() { return worldX; }

    void draw(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null) return;
        try {
            bake();
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            if (siz <= 0.0001f) return;
            clock += 0.09f;

            float ground = midh - 156 * siz;
            float cx = (worldX * 0.32f + 200f) * siz + sbPos;
            float rx = 44f * siz;
            float ry = rx * (190f / 96f);
            float cy = ground - ry - 6f * siz + (float) Math.sin(clock) * 3f * siz;
            float pulse = 0.5f + 0.5f * (float) Math.sin(clock * 1.6f);

            float texW = rx * (BW / 96f), texH = ry * (BH / 190f);

            if (bakeFailed || ringTex == null) { drawFallback(g, cx, cy, rx, ry); return; }

            add(g, moundTex, cx, ground - 10f * siz, rx * 4.2f, (34f + 10f * pulse) * siz,
                    Math.round(150 + 60 * pulse));
            add(g, dotLilac, cx - rx, ground - 2f * siz, rx * 0.5f, 5f * siz, 200);
            add(g, dotLilac, cx + rx, ground - 2f * siz, rx * 0.5f, 5f * siz, 200);

            float bp = (clock % 4.6f) / 4.6f;
            if (bp < 0.5f) {
                float t = bp * 2f;
                add(g, ringTex, cx, cy, texW * (1f + t * 0.8f), texH * (1f + t * 0.5f),
                        Math.round((1f - t) * 130f));
            }

            try {
                g.setComposite(FakeGraphics.TRANS, 245, 0);
                g.drawImage(voidTex, cx - texW * 0.44f, cy - texH * 0.46f, texW * 0.88f, texH * 0.92f);
                g.setComposite(FakeGraphics.DEF, 0, 0);
            } catch (Throwable ignored) {}
            for (int i = 0; i < swirlTex.length; i++) {
                float ox = (float) Math.sin(clock * (0.35f + i * 0.14f) + i * 2.1f) * 3.5f * siz;
                float oy = (float) Math.cos(clock * (0.27f + i * 0.11f) + i * 1.3f) * 5f * siz;
                int a = Math.round(120 + 55 * (float) Math.sin(clock * 0.8f + i * 2.0f));
                add(g, swirlTex[i], cx + ox, cy + oy, texW * 0.86f, texH * 0.9f, a);
            }

            add(g, ringTex, cx, cy, texW * 1.06f, texH * 1.05f, Math.round(70 + 50 * pulse));
            add(g, ringTex, cx, cy, texW, texH, Math.round(200 + 55 * pulse));

            comet(g, cx, cy, rx, ry, clock * 1.4f, dotLilac, siz, 1f);
            comet(g, cx, cy, rx * 1.07f, ry * 1.04f, -clock * 1.9f + 2.1f, dotViolet, siz, 0.8f);

            for (int i = 0; i < 7; i++) {
                float ph = ((clock * 11f + i * 29f) % 130f) / 130f;
                float sy = cy + ry * 0.85f - ph * ry * 2.0f;
                float fy = (sy - cy) / ry;
                float maxW = rx * (fy * fy < 0.95f
                        ? 0.7f * (float) Math.sqrt(1f - fy * fy) + 0.2f : 0.35f);
                float ox = (float) Math.sin(clock * 0.7f + i * 2.7f) * maxW;
                float s = (7f - ph * 3f) * siz;
                int a = Math.round((1f - ph) * 220f);
                add(g, dotViolet, cx + ox, sy, s, s, a);
            }

            for (int i = 0; i < 6; i++) {
                double th = clock * 0.9f + i * (Math.PI * 2 / 6);
                float mx = cx + (float) Math.cos(th) * rx * 1.3f;
                float my = cy + (float) Math.sin(th) * ry * 1.14f;
                float depth = 0.55f + 0.45f * (float) Math.sin(th + Math.PI / 2);
                float s = (10f + 8f * depth) * siz;
                add(g, dotLilac, mx, my, s, s, Math.round(70 + 160 * depth));
            }
        } catch (Throwable ignored) {}
    }

    private void comet(FakeGraphics g, float cx, float cy, float rx, float ry,
                       float head, FakeImage dot, float siz, float strength) {
        for (int k = 0; k < 8; k++) {
            double th = head - k * 0.16;
            float x = cx + (float) Math.cos(th) * rx;
            float y = cy + (float) Math.sin(th) * ry;
            float f = 1f - k / 8f;
            float s = (16f * f + 5f) * siz;
            add(g, dot, x, y, s, s, Math.round(230 * f * strength));
        }
    }

    private static void add(FakeGraphics g, FakeImage tex, float cx, float cy,
                            float w, float h, int alpha) {
        if (tex == null || alpha <= 2 || w < 1f || h < 1f) return;
        try {
            g.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(255, alpha)), 1);
            g.drawImage(tex, cx - w / 2f, cy - h / 2f, w, h);
        } catch (Throwable ignored) {
        } finally {
            try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
        }
    }

    private void drawFallback(FakeGraphics g, float cx, float cy, float rx, float ry) {
        g.colRect(cx - rx, cy - ry, rx * 2f, ry * 2f, 90, 60, 160, 200);
        g.setColor(170, 230, 255);
        g.drawRect(cx - rx, cy - ry, rx * 2f, ry * 2f);
    }

    private void bake() {
        if (baked || bakeFailed || ImageBuilder.builder == null) return;
        try {
            ringTex = bakeRing();
            voidTex = bakeVoid();
            dotLilac = bakeDot(new Color(225, 185, 255));
            dotViolet = bakeDot(new Color(185, 130, 255));
            moundTex = bakeMound();
            for (int i = 0; i < swirlTex.length; i++) {
                swirlTex[i] = bakeSwirl(1234L * (i + 1) + 77L * i);
            }
            baked = ringTex != null && voidTex != null;
            bakeFailed = !baked;
        } catch (Throwable t) {
            bakeFailed = true;
        }
    }

    private FakeImage bakeRing() {
        BufferedImage bi = canvas(BW, BH);
        Graphics2D g2 = aa(bi);
        Ellipse2D e = new Ellipse2D.Float(128 - 96, 224 - 190, 192, 380);
        stroke(g2, e, 34f, new Color(90, 40, 160, 50));
        stroke(g2, e, 24f, new Color(120, 50, 220, 95));
        stroke(g2, e, 15f, new Color(170, 90, 255, 165));
        stroke(g2, e, 9f, new Color(205, 130, 255, 225));
        stroke(g2, e, 4f, new Color(246, 232, 255, 255));
        g2.dispose();
        return toFake(bi);
    }

    private FakeImage bakeVoid() {
        BufferedImage bi = canvas(BW, BH);
        Graphics2D g2 = aa(bi);
        g2.translate(128, 224);
        g2.scale(1.0, 190.0 / 96.0);
        RadialGradientPaint p = new RadialGradientPaint(new Point2D.Float(0, 0), 96f,
                new float[]{0f, 0.5f, 0.85f, 1f},
                new Color[]{new Color(52, 12, 82, 255), new Color(38, 8, 66, 255),
                        new Color(28, 6, 52, 210), new Color(28, 6, 52, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2.setPaint(p);
        g2.fillOval(-96, -96, 192, 192);
        g2.dispose();
        return toFake(bi);
    }

    private FakeImage bakeSwirl(long seed) {
        BufferedImage bi = canvas(BW, BH);
        Graphics2D g2 = aa(bi);
        java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < 46; i++) {
            float bx = 24f + r.nextFloat() * (BW - 48);
            float by = 34f + r.nextFloat() * (BH - 68);
            float s = 20f + r.nextFloat() * 46f;
            int rr = 150 + r.nextInt(70);
            int gg = 40 + r.nextInt(50);
            int bb2 = 200 + r.nextInt(55);
            int a = 55 + r.nextInt(70);
            RadialGradientPaint bp = new RadialGradientPaint(new Point2D.Float(bx, by), s / 2f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(rr, gg, bb2, a), new Color(rr, gg, bb2, 0)},
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g2.setPaint(bp);
            g2.fillOval(Math.round(bx - s / 2f), Math.round(by - s / 2f), Math.round(s), Math.round(s));
        }

        g2.setComposite(java.awt.AlphaComposite.DstIn);
        g2.translate(128, 224);
        g2.scale(1.0, 190.0 / 96.0);
        RadialGradientPaint mask = new RadialGradientPaint(new Point2D.Float(0, 0), 92f,
                new float[]{0f, 0.78f, 1f},
                new Color[]{new Color(255, 255, 255, 255), new Color(255, 255, 255, 255),
                        new Color(255, 255, 255, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2.setPaint(mask);
        g2.fillRect(-128, -114, 256, 228);
        g2.dispose();
        return toFake(bi);
    }

    private FakeImage bakeDot(Color tint) {
        int S = 96;
        BufferedImage bi = canvas(S, S);
        Graphics2D g2 = aa(bi);
        RadialGradientPaint p = new RadialGradientPaint(new Point2D.Float(S / 2f, S / 2f), S / 2f,
                new float[]{0f, 0.3f, 1f},
                new Color[]{new Color(255, 255, 255, 255),
                        new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 190),
                        new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2.setPaint(p);
        g2.fillOval(0, 0, S, S);
        g2.dispose();
        return toFake(bi);
    }

    private FakeImage bakeMound() {
        int W = 256, H = 96;
        BufferedImage bi = canvas(W, H);
        Graphics2D g2 = aa(bi);
        g2.translate(W / 2.0, H);
        g2.scale(1.0, H / (W / 2.0));
        RadialGradientPaint p = new RadialGradientPaint(new Point2D.Float(0, 0), W / 2f,
                new float[]{0f, 0.4f, 1f},
                new Color[]{new Color(190, 160, 255, 200), new Color(150, 90, 255, 130),
                        new Color(150, 90, 255, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2.setPaint(p);
        g2.fillOval(-W / 2, -W / 2, W, W);
        g2.dispose();
        return toFake(bi);
    }

    private static BufferedImage canvas(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D aa(BufferedImage bi) {
        Graphics2D g2 = bi.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    private static void stroke(Graphics2D g2, Ellipse2D e, float w, Color c) {
        g2.setColor(c);
        g2.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(e);
    }

    private static FakeImage toFake(BufferedImage bi) {
        FakeImage img = ImageBuilder.builder.build(bi.getWidth(), bi.getHeight());
        if (img == null) return null;
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                img.setRGB(x, y, bi.getRGB(x, y));
            }
        }
        return img;
    }
}
