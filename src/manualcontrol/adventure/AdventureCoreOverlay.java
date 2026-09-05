package manualcontrol.adventure;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import manualcontrol.reflect.BBPainterAccess;

import java.awt.Color;

final class AdventureCoreOverlay {

    private static final int CARD_W = 210, CARD_H = 300, CARD_GAP = 40;
    private static final int REVEAL_STAGGER = 6, REVEAL_LEN = 10;
    private static final int CONFIRM_LEN = 22;

    private final AdventureCore[] offer;
    private final int stageCleared;
    private int age;
    private int sel = 1;
    private int selPulse;
    private boolean confirming;
    private int confirmAge;
    private boolean done;
    private AdventureCore picked;
    private final boolean[] landPlayed = new boolean[3];

    private FakeImage titleTex, subTex, hintTex, arrowTex;
    private final FakeImage[] ribbonTex = new FakeImage[3];
    private final FakeImage[] nameTex = new FakeImage[3];
    private final FakeImage[] magTex = new FakeImage[3];
    private final FakeImage[] desc1Tex = new FakeImage[3];
    private final FakeImage[] desc2Tex = new FakeImage[3];
    private final FakeImage[] glyphTex = new FakeImage[3];
    private final FakeImage[] uniqueTex = new FakeImage[3];
    private boolean baked;

    AdventureCoreOverlay(AdventureCore[] offer, int stageCleared) {
        this.offer = offer;
        this.stageCleared = stageCleared;
    }

    boolean isDone() { return done; }

    AdventureCore picked() { return picked; }

    void tick() {
        age++;
        if (selPulse > 0) selPulse--;
        if (confirming && ++confirmAge > CONFIRM_LEN) done = true;

        for (int i = 0; i < offer.length; i++) {
            int landAt = i * REVEAL_STAGGER + REVEAL_LEN;
            if (!landPlayed[i] && age >= landAt) {
                landPlayed[i] = true;
                AdventureSfx.play(offer[i].unique ? AdventureSfx.UNIQUE_STING : AdventureSfx.CARD_LAND);
            }
        }
    }

    void move(int dir) {
        if (confirming || offer.length == 0) return;
        int n = offer.length;
        sel = ((sel + dir) % n + n) % n;
        selPulse = 6;
        AdventureSfx.play(AdventureSfx.SELECT_MOVE);
    }

    void confirm() {
        if (confirming || age < REVEAL_LEN) return;
        confirming = true;
        confirmAge = 0;
        picked = offer[Math.min(sel, offer.length - 1)];
        AdventureSfx.confirm(picked.tier);
    }

    void draw(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || done) return;
        FakeTransform old = AdventureHud.pushIdentity(g);
        try {
            int w = BBPainterAccess.getWidth(bbpainter);
            int h = BBPainterAccess.getHeight(bbpainter);
            if (w <= 0 || h <= 0) return;
            bake();

            int veil = Math.min(150, age * 20) + (confirming ? 30 : 0);
            g.colRect(0, 0, w, h, 0, 0, 0, Math.min(200, veil));

            if (titleTex != null) g.drawImage(titleTex, w / 2f - titleTex.getWidth() / 2f, 34);
            if (subTex != null) g.drawImage(subTex, w / 2f - subTex.getWidth() / 2f, 72);
            if (hintTex != null) g.drawImage(hintTex, w / 2f - hintTex.getWidth() / 2f, h - 46);

            int n = offer.length;
            float totalW = n * CARD_W + (n - 1) * CARD_GAP;
            float x0 = w / 2f - totalW / 2f;
            float cy = h / 2f - CARD_H / 2f + 10;
            for (int i = 0; i < n; i++) {
                drawCard(g, i, x0 + i * (CARD_W + CARD_GAP), cy);
            }
        } catch (Throwable ignored) {
        } finally {
            AdventureHud.popIdentity(g, old);
        }
    }

    private void drawCard(FakeGraphics g, int i, float x, float y) {
        AdventureCore c = offer[i];
        float t = reveal(i);
        if (t <= 0f) return;
        y += (1f - t) * 60f;

        boolean isSel = i == sel;
        float dim = confirming ? (isSel ? 1f : Math.max(0f, 1f - confirmAge / 8f))
                : (isSel ? 1f : 0.62f);
        int a255 = Math.round(255 * t * dim);
        if (a255 <= 2) return;
        float af = a255 / 255f;
        if (isSel && !confirming) y -= 6f;

        int tr = c.tier.r, tg = c.tier.g, tb = c.tier.b;

        g.colRect(x, y, CARD_W, CARD_H, 15, 18, 24, Math.round(242 * af));

        int tierIdx = c.tier.ordinal();
        stroke(g, x, y, CARD_W, CARD_H, 2, tr, tg, tb, Math.round(255 * af));
        if (tierIdx >= 1) stroke(g, x + 4, y + 4, CARD_W - 8, CARD_H - 8, 1, tr, tg, tb, Math.round(140 * af));
        if (tierIdx >= 3) stroke(g, x + 8, y + 8, CARD_W - 16, CARD_H - 16, 1, tr, tg, tb, Math.round(90 * af));
        if (tierIdx >= 2) {

            int pulse = Math.round((60 + 50 * (float) Math.sin(age * 0.12f)) * af);
            try {
                g.setComposite(FakeGraphics.BLEND, Math.max(0, pulse), 1);
                stroke(g, x - 2, y - 2, CARD_W + 4, CARD_H + 4, 2, tr, tg, tb, 255);
                g.setComposite(FakeGraphics.DEF, 0, 0);
            } catch (Throwable ignored) {}
            corners(g, x, y, CARD_W, CARD_H, tr, tg, tb, Math.round(230 * af));
        }
        if (c.tier == AdventureCore.Tier.LEGEND) {
            stroke(g, x + 6, y + 6, CARD_W - 12, CARD_H - 12, 1, 255, 90, 90, Math.round(120 * af));

            try {
                g.setComposite(FakeGraphics.BLEND, Math.round(200 * af), 1);
                for (int m = 0; m < 6; m++) {
                    float ph = ((age * 0.7f + m * 17f) % 60f) / 60f;
                    float mx = x + (m % 2 == 0 ? -8f : CARD_W + 5f) + (float) Math.sin(age * 0.1f + m) * 4f;
                    float my = y + CARD_H - ph * CARD_H;
                    g.colRect(mx, my, 3, 3, 210, 140, 255, Math.round((1f - ph) * 255));
                }
                g.setComposite(FakeGraphics.DEF, 0, 0);
            } catch (Throwable ignored) {}
        }
        if (isSel && (selPulse > 0 || !confirming)) {
            int pa = Math.round((selPulse > 0 ? 255 : 170) * af);
            stroke(g, x - 4, y - 4, CARD_W + 8, CARD_H + 8, 1, 255, 255, 255, pa / 2);
        }

        g.colRect(x + 2, y + 2, CARD_W - 4, 22, tr, tg, tb, Math.round(255 * af));
        FakeImage rt = ribbonTex[i];
        if (rt != null) drawTex(g, rt, x + CARD_W / 2f, y + 13, af, 1f);

        if (c.unique && uniqueTex[i] != null) {
            g.colRect(x + CARD_W - 66, y - 8, 62, 18, 255, 90, 90, Math.round(255 * af));
            drawTex(g, uniqueTex[i], x + CARD_W - 35, y + 1, af, 1f);
        }

        float gx = x + CARD_W / 2f, gy = y + 90;
        g.colRect(gx - 24, gy - 24, 48, 48, 20, 24, 31, Math.round(230 * af));
        try {
            g.setComposite(FakeGraphics.BLEND, Math.round(38 * af), 1);
            g.colRect(gx - 26, gy - 26, 52, 52, tr, tg, tb, 255);
            g.setComposite(FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
        g.setColor(tr, tg, tb);
        diamond(g, gx, gy, 34);
        diamond(g, gx, gy, 26);
        FakeImage gt = glyphTex[i];
        if (gt != null) drawTex(g, gt, gx, gy, af, 1f);

        drawTex(g, nameTex[i], x + CARD_W / 2f, y + 168, af, 1f);
        FakeImage mt = magTex[i];
        if (mt != null) {
            drawTex(g, mt, x + CARD_W / 2f, y + 196, af, 1f);
            try {
                g.setComposite(FakeGraphics.BLEND, Math.round(120 * af), 1);
                g.drawImage(mt, x + CARD_W / 2f - mt.getWidth() / 2f, y + 196 - mt.getHeight() / 2f);
                g.setComposite(FakeGraphics.DEF, 0, 0);
            } catch (Throwable ignored) {}
        }
        drawTex(g, desc1Tex[i], x + CARD_W / 2f, y + 238, af, 1f);
        drawTex(g, desc2Tex[i], x + CARD_W / 2f, y + 256, af, 1f);

        if (isSel && !confirming && arrowTex != null && (age / 8) % 2 == 0) {
            drawTex(g, arrowTex, x + CARD_W / 2f, y + CARD_H + 16, af, 1f);
        }

        if (confirming && isSel) {
            float ct = confirmAge / (float) CONFIRM_LEN;
            int fa = Math.round((1f - ct) * 230f);
            try {
                g.setComposite(FakeGraphics.BLEND, Math.max(0, fa), 1);
                g.colRect(x, y, CARD_W, CARD_H, 255, 255, 255, 255);
                g.setComposite(FakeGraphics.DEF, 0, 0);
            } catch (Throwable ignored) {}
        }
    }

    private float reveal(int i) {
        int start = i * REVEAL_STAGGER;
        return Math.max(0f, Math.min(1f, (age - start) / (float) REVEAL_LEN));
    }

    private static void drawTex(FakeGraphics g, FakeImage tex, float cx, float cy, float alpha, float scale) {
        if (tex == null) return;
        try {
            boolean comp = alpha < 0.99f;
            if (comp) g.setComposite(FakeGraphics.TRANS, Math.round(alpha * 255), 0);
            float w = tex.getWidth() * scale, h = tex.getHeight() * scale;
            g.drawImage(tex, cx - w / 2f, cy - h / 2f, w, h);
            if (comp) g.setComposite(FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
    }

    private static void stroke(FakeGraphics g, float x, float y, float w, float h,
                               int th, int r, int gg, int b, int a) {
        if (a <= 0) return;
        g.colRect(x, y, w, th, r, gg, b, a);
        g.colRect(x, y + h - th, w, th, r, gg, b, a);
        g.colRect(x, y, th, h, r, gg, b, a);
        g.colRect(x + w - th, y, th, h, r, gg, b, a);
    }

    private static void corners(FakeGraphics g, float x, float y, float w, float h,
                                int r, int gg, int b, int a) {
        float L = 16, t = 2;
        float[][] cs = {{x - 3, y - 3, 1, 1}, {x + w + 3 - L, y - 3, -1, 1},
                {x - 3, y + h + 3 - t, 1, -1}, {x + w + 3 - L, y + h + 3 - t, -1, -1}};
        for (float[] cc : cs) {
            g.colRect(cc[0], cc[1], L, t, r, gg, b, a);
        }
        float[][] vs = {{x - 3, y - 3}, {x + w + 1, y - 3}, {x - 3, y + h + 3 - L}, {x + w + 1, y + h + 3 - L}};
        for (float[] vv : vs) {
            g.colRect(vv[0], vv[1], t, L, r, gg, b, a);
        }
    }

    private static void diamond(FakeGraphics g, float cx, float cy, float r) {
        g.drawLine(cx, cy - r, cx + r, cy);
        g.drawLine(cx + r, cy, cx, cy + r);
        g.drawLine(cx, cy + r, cx - r, cy);
        g.drawLine(cx - r, cy, cx, cy - r);
    }

    private void bake() {
        if (baked) return;
        AdventureCore.Tier tier = offer.length > 0 ? offer[0].tier : AdventureCore.Tier.BRONZE;
        Color tierC = new Color(tier.r, tier.g, tier.b);
        titleTex = AdventureHud.bakeText("POWER CORE - " + tier.label, tierC, 26);
        subTex = AdventureHud.bakeText("STAGE " + stageCleared + " CLEARED - CHOOSE ONE",
                new Color(150, 158, 168), 14);
        hintTex = AdventureHud.bakeText("A / D  SELECT      J  CONFIRM", new Color(160, 168, 178), 15);
        arrowTex = AdventureHud.bakeText("▲", new Color(235, 245, 255), 16);
        Color name = new Color(235, 244, 252);
        Color mag = new Color(0x7C, 0xFF, 0x8E);
        Color desc = new Color(158, 166, 176);
        for (int i = 0; i < offer.length; i++) {
            AdventureCore c = offer[i];
            Color tc = new Color(c.tier.r, c.tier.g, c.tier.b);
            ribbonTex[i] = AdventureHud.bakeText(c.tier.label, new Color(18, 18, 26), 12);
            nameTex[i] = AdventureHud.bakeText(c.name, name, 18);
            magTex[i] = AdventureHud.bakeText(c.magText, mag, 20);
            desc1Tex[i] = AdventureHud.bakeText(c.desc1, desc, 12);
            desc2Tex[i] = c.desc2 == null ? null : AdventureHud.bakeText(c.desc2, desc, 12);

            glyphTex[i] = AdventureHud.bakeText(c.glyph, new Color(238, 246, 255), 32);
            uniqueTex[i] = c.unique ? AdventureHud.bakeText("UNIQUE", Color.WHITE, 11) : null;
        }
        baked = titleTex != null;
    }
}
