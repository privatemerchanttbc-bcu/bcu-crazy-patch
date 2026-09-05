package manualcontrol.adventure;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import manualcontrol.reflect.BBPainterAccess;

import java.awt.Color;

final class AdventurePauseOverlay {

    static final int NONE = 0, RESUME = 1, RESTART = 2, SAVE_QUIT = 3;

    private static final String[] LABELS = {"Resume", "Restart Stage", "Save & Quit to Menu"};
    private static final int[] ACTIONS = {RESUME, RESTART, SAVE_QUIT};

    private final boolean ironman;
    private int sel;
    private int age;
    private int pendingAction = NONE;

    private FakeImage titleTex, modeTex, hintTex;
    private final FakeImage[] itemTex = new FakeImage[LABELS.length];
    private final FakeImage[] itemSelTex = new FakeImage[LABELS.length];
    private boolean baked;

    AdventurePauseOverlay(boolean ironman) { this.ironman = ironman; }

    void tick() { age++; }

    void move(int dir) {
        int n = LABELS.length;
        sel = ((sel + dir) % n + n) % n;
        AdventureSfx.play(AdventureSfx.SELECT_MOVE);
    }

    void confirm() {
        pendingAction = ACTIONS[sel];
        AdventureSfx.play(AdventureSfx.CARD_LAND);
    }

    int consumeAction() {
        int a = pendingAction;
        pendingAction = NONE;
        return a;
    }

    void draw(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null) return;
        FakeTransform old = AdventureHud.pushIdentity(g);
        try {
            int w = BBPainterAccess.getWidth(bbpainter);
            int h = BBPainterAccess.getHeight(bbpainter);
            if (w <= 0 || h <= 0) return;
            bake();

            int veil = Math.min(185, age * 24);
            g.colRect(0, 0, w, h, 0, 0, 0, veil);

            float cx = w / 2f;
            if (titleTex != null) g.drawImage(titleTex, cx - titleTex.getWidth() / 2f, h * 0.26f);
            if (modeTex != null) g.drawImage(modeTex, cx - modeTex.getWidth() / 2f, h * 0.26f + 40);

            float y0 = h * 0.46f;
            float rowH = 48;
            float pw = 360, ph = 40;
            for (int i = 0; i < LABELS.length; i++) {
                float ry = y0 + i * rowH;
                boolean isSel = i == sel;
                if (isSel) {
                    g.colRect(cx - pw / 2f, ry - ph / 2f, pw, ph, 40, 52, 66, 225);
                    int pulse = Math.round(60 + 40 * (float) Math.sin(age * 0.15f));
                    try {
                        g.setComposite(FakeGraphics.BLEND, Math.max(0, pulse), 1);
                        stroke(g, cx - pw / 2f, ry - ph / 2f, pw, ph, 2, 120, 200, 255, 255);
                        g.setComposite(FakeGraphics.DEF, 0, 0);
                    } catch (Throwable ignored) {}
                } else {
                    g.colRect(cx - pw / 2f, ry - ph / 2f, pw, ph, 18, 22, 30, 170);
                    stroke(g, cx - pw / 2f, ry - ph / 2f, pw, ph, 1, 70, 80, 92, 200);
                }
                FakeImage t = isSel ? itemSelTex[i] : itemTex[i];
                if (t != null) g.drawImage(t, cx - t.getWidth() / 2f, ry - t.getHeight() / 2f);
            }
            if (hintTex != null) {
                g.drawImage(hintTex, cx - hintTex.getWidth() / 2f, y0 + LABELS.length * rowH + 14);
            }
        } catch (Throwable ignored) {
        } finally {
            AdventureHud.popIdentity(g, old);
        }
    }

    private static void stroke(FakeGraphics g, float x, float y, float w, float h,
                               int th, int r, int gg, int b, int a) {
        if (a <= 0) return;
        g.colRect(x, y, w, th, r, gg, b, a);
        g.colRect(x, y + h - th, w, th, r, gg, b, a);
        g.colRect(x, y, th, h, r, gg, b, a);
        g.colRect(x + w - th, y, th, h, r, gg, b, a);
    }

    private void bake() {
        if (baked) return;
        titleTex = AdventureHud.bakeText("PAUSED", new Color(235, 245, 255), 30);
        modeTex = AdventureHud.bakeText(ironman ? "IRONMAN RUN" : "CASUAL RUN",
                ironman ? new Color(255, 120, 120) : new Color(150, 200, 160), 14);
        hintTex = AdventureHud.bakeText("W / S  SELECT      J  CONFIRM      P  RESUME",
                new Color(160, 168, 178), 13);
        Color dim = new Color(200, 208, 218);
        Color bright = new Color(255, 255, 255);
        for (int i = 0; i < LABELS.length; i++) {
            itemTex[i] = AdventureHud.bakeText(LABELS[i], dim, 18);
            itemSelTex[i] = AdventureHud.bakeText(LABELS[i], bright, 19);
        }
        baked = titleTex != null;
    }
}
