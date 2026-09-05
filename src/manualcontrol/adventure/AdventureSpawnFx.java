package manualcontrol.adventure;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import manualcontrol.reflect.BBPainterAccess;

import java.util.ArrayList;
import java.util.List;

final class AdventureSpawnFx {

    private static final int BURST_DURATION = 42;
    private static final int LANDING_DURATION = 50;
    private static final int RING_SEGMENTS = 26;
    private static final int FLASH_DURATION = 26;

    private final List<Burst> bursts = new ArrayList<Burst>();
    private final List<Landing> landings = new ArrayList<Landing>();
    private final List<Pillar> pillars = new ArrayList<Pillar>();
    private final List<Shock> shocks = new ArrayList<Shock>();
    private final List<SlideDust> slideDust = new ArrayList<SlideDust>();
    private int flashAge = -1;
    private boolean flashDouble;
    private int landingSequence;

    AdventureSpawnFx() {
        AdventureLandingVfxRenderer.prewarm(AdventureRuntime.landingVfx());
    }

    private static final class Burst {
        final float worldX;
        final int layer;
        final int cr, cg, cb, fr, fg, fb;
        int age;
        final float[] sparkX = new float[6];
        final float[] sparkSpeed = new float[6];
        final float[] sparkPhase = new float[6];
        Burst(float worldX, int layer, int cr, int cg, int cb, int fr, int fg, int fb) {
            this.worldX = worldX;
            this.layer = layer;
            this.cr = cr; this.cg = cg; this.cb = cb;
            this.fr = fr; this.fg = fg; this.fb = fb;
            for (int i = 0; i < sparkX.length; i++) {
                sparkX[i] = (float) (Math.random() * 2 - 1) * 55f;
                sparkSpeed[i] = 3f + (float) Math.random() * 4f;
                sparkPhase[i] = (float) Math.random();
            }
        }
    }

    private static final class Landing {
        final float worldX;
        final int layer;
        final AdventureLandingVfx style;
        final int seed;
        final AdventureLandingVfxRenderer.Visual visual;
        int age;

        Landing(float worldX, int layer, AdventureLandingVfx style, int seed,
                float shadowWidth) {
            this.worldX = worldX;
            this.layer = layer;
            this.style = style == null ? AdventureLandingVfx.CRYSTAL : style;
            this.seed = seed;
            this.visual = AdventureLandingVfxRenderer.create(
                    worldX, layer, this.style, seed, shadowWidth);
        }
    }

    private static final class Pillar {
        final float worldX;
        final int layer, r, g, b;
        int age;
        Pillar(float worldX, int layer, int r, int g, int b) {
            this.worldX = worldX; this.layer = layer;
            this.r = r; this.g = g; this.b = b;
        }
    }

    private static final class Shock {
        final int r, g, b;
        int age;
        Shock(int r, int g, int b) { this.r = r; this.g = g; this.b = b; }
    }

    private static final class SlideDust {
        final float worldX;
        final int layer;
        final int direction;
        final boolean enemy;
        int age;

        SlideDust(float worldX, int layer, int direction, boolean enemy) {
            this.worldX = worldX;
            this.layer = layer;
            this.direction = direction < 0 ? -1 : 1;
            this.enemy = enemy;
        }
    }

    void spawn(float worldX, int layer, boolean enemy) {
        if (enemy) spawnColored(worldX, layer, 230, 70, 235, 255, 120, 255);
        else spawnColored(worldX, layer, 200, 240, 255, 235, 250, 255);
    }

    void spawnColored(float worldX, int layer, int cr, int cg, int cb, int fr, int fg, int fb) {
        if (bursts.size() < 24) bursts.add(new Burst(worldX, layer, cr, cg, cb, fr, fg, fb));
    }

    void spawnLanding(float worldX, int layer, AdventureLandingVfx style,
                      float shadowWidth) {
        if (landings.size() >= 10) landings.remove(0);
        int seed = ++landingSequence * 1103515245 + Float.floatToIntBits(worldX);
        landings.add(new Landing(worldX, layer, style, seed, shadowWidth));
    }

    void slideDust(float worldX, int layer, int direction, boolean enemy) {
        if (slideDust.size() >= 40) slideDust.remove(0);
        slideDust.add(new SlideDust(worldX, layer, direction, enemy));
    }

    void flash() { flashAge = 0; flashDouble = false; }

    void flashTwice() { flashAge = 0; flashDouble = true; }

    void confirmFx(AdventureCore.Tier tier, float worldX, int layer) {
        int r = tier.r, g = tier.g, b = tier.b;
        switch (tier) {
            case BRONZE:
                spawnColored(worldX, layer, r, g, b, 240, 200, 160);
                break;
            case SILVER:
                spawnColored(worldX - 30f, layer, r, g, b, 255, 255, 255);
                spawnColored(worldX + 30f, layer, r, g, b, 255, 255, 255);
                break;
            case GOLD:
                pillars.add(new Pillar(worldX, layer, r, g, b));
                spawnColored(worldX, layer, r, g, b, 255, 240, 180);
                break;
            case PLATINUM:
                shocks.add(new Shock(r, g, b));
                spawnColored(worldX, layer, r, g, b, 230, 250, 255);
                break;
            case LEGEND:
                flashTwice();
                shocks.add(new Shock(r, g, b));
                spawnColored(worldX, layer, r, g, b, 255, 150, 150);
                break;
        }
    }

    void tick() {
        for (int i = bursts.size() - 1; i >= 0; i--) {
            if (++bursts.get(i).age > BURST_DURATION) bursts.remove(i);
        }
        for (int i = landings.size() - 1; i >= 0; i--) {
            Landing landing = landings.get(i);
            if (landing.visual.tick()) {
                landings.remove(i);
            } else {
                landing.age = landing.visual.age;
            }
        }
        for (int i = pillars.size() - 1; i >= 0; i--) {
            if (++pillars.get(i).age > 30) pillars.remove(i);
        }
        for (int i = shocks.size() - 1; i >= 0; i--) {
            if (++shocks.get(i).age > 26) shocks.remove(i);
        }
        for (int i = slideDust.size() - 1; i >= 0; i--) {
            if (++slideDust.get(i).age > 16) slideDust.remove(i);
        }
        if (flashAge >= 0 && ++flashAge > (flashDouble ? 42 : FLASH_DURATION)) flashAge = -1;
    }

    boolean active() {
        return !bursts.isEmpty() || !landings.isEmpty() || !pillars.isEmpty()
                || !shocks.isEmpty() || !slideDust.isEmpty() || flashAge >= 0;
    }

    void drawBursts(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null
                || (bursts.isEmpty() && landings.isEmpty() && pillars.isEmpty()
                && slideDust.isEmpty())) return;
        FakeTransform old = pushIdentity(g);
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            if (siz > 0.0001f) {
                for (int i = 0; i < bursts.size(); i++) {
                    drawBurst(g, bursts.get(i), siz, sbPos, midh);
                }
                for (int i = 0; i < landings.size(); i++) {
                    AdventureLandingVfxRenderer.drawUnder(
                            bbpainter, g, landings.get(i).visual);
                }
                for (int i = 0; i < pillars.size(); i++) {
                    drawPillar(g, pillars.get(i), siz, sbPos, midh);
                }
                for (int i = 0; i < slideDust.size(); i++) {
                    drawSlideDust(bbpainter, g, slideDust.get(i), siz, sbPos, midh);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            popIdentity(g, old);
        }
    }

    void drawWorldOverlay(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || landings.isEmpty()) return;
        FakeTransform old = pushIdentity(g);
        try {
            for (int i = 0; i < landings.size(); i++) {
                AdventureLandingVfxRenderer.drawWorldOverlay(
                        bbpainter, g, landings.get(i).visual);
            }
        } catch (Throwable ignored) {
        } finally {
            popIdentity(g, old);
        }
    }

    void drawLandingScreenOverlay(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || landings.isEmpty()) return;
        FakeTransform old = pushIdentity(g);
        try {
            for (int i = 0; i < landings.size(); i++) {
                AdventureLandingVfxRenderer.drawScreenWash(
                        bbpainter, g, landings.get(i).visual);
            }
        } catch (Throwable ignored) {
        } finally {
            popIdentity(g, old);
        }
    }

    private void drawPillar(FakeGraphics g, Pillar p, float siz, int sbPos, int midh) {
        float t = p.age / 30f;
        float sx = (p.worldX * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - p.layer * 4) * siz;
        float w = (34f - t * 26f) * siz;
        float h = (90f + t * 260f) * siz;
        int a = Math.max(0, Math.round((1f - t) * 235f));
        try {
            g.setComposite(FakeGraphics.BLEND, a, 1);
            g.colRect(sx - w / 2f, ground - h, w, h, p.r, p.g, p.b, 255);
            g.colRect(sx - w / 6f, ground - h, w / 3f, h, 255, 255, 255, 220);
            g.setComposite(FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
    }

    void drawOverlay(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || (flashAge < 0 && shocks.isEmpty())) return;
        FakeTransform old = pushIdentity(g);
        try {
            for (int i = 0; i < shocks.size(); i++) {
                drawShock(g, bbpainter, shocks.get(i));
            }
            drawFlash(g, bbpainter);
        } catch (Throwable ignored) {
        } finally {
            popIdentity(g, old);
        }
    }

    private void drawShock(FakeGraphics g, Object bbpainter, Shock s) {
        try {
            int w = BBPainterAccess.getWidth(bbpainter);
            int h = BBPainterAccess.getHeight(bbpainter);
            float t = s.age / 26f;
            float radius = 40f + t * Math.max(w, h) * 0.72f;
            int a = Math.max(0, Math.round((1f - t) * 220f));
            g.setComposite(FakeGraphics.BLEND, a, 1);
            drawRing(g, w / 2f, h * 0.55f, radius, s.r, s.g, s.b, 255);
            drawRing(g, w / 2f, h * 0.55f, radius * 0.9f, 255, 255, 255, 255);
            g.setComposite(FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
    }

    private void drawBurst(FakeGraphics g, Burst b, float siz, int sbPos, int midh) {
        float sx = (b.worldX * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - b.layer * 4) * siz;
        float t = b.age / (float) BURST_DURATION;
        int fade = Math.max(0, Math.round((1f - t) * 255f));

        int cr = b.cr, cg = b.cg, cb = b.cb;
        int fr = b.fr, fg = b.fg, fb = b.fb;

        try { g.setComposite(FakeGraphics.BLEND, 255, 1); } catch (Throwable ignored) {}

        int flashA = Math.max(0, Math.round((1f - Math.min(1f, t * 2.2f)) * 190f));
        if (flashA > 0) {
            float fw = 150f * siz, fh = 26f * siz;
            g.colRect(sx - fw / 2f, ground - 40f * siz - fh / 2f, fw, fh, cr, cg, cb, flashA);
        }
        drawRing(g, sx, ground - 40f * siz, (18f + t * 145f) * siz, cr, cg, cb, fade);
        if (t > 0.15f) {
            float t2 = (t - 0.15f) / 0.85f;
            drawRing(g, sx, ground - 40f * siz, (18f + t2 * 115f) * siz,
                    cr, cg, cb, Math.round((1f - t2) * 200f));
        }
        for (int i = 0; i < b.sparkX.length; i++) {
            float life = t + b.sparkPhase[i] * 0.3f;
            if (life > 1f) continue;
            float rise = (b.age + b.sparkPhase[i] * 6f) * b.sparkSpeed[i];
            float px = sx + b.sparkX[i] * siz;
            float py = ground - rise * siz;
            int a = Math.max(0, Math.round((1f - life) * 255f));
            float s = 4f * siz;
            g.colRect(px - s / 2f, py - s / 2f, s, s, fr, fg, fb, a);
        }
        try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private void drawSlideDust(Object painter, FakeGraphics g, SlideDust dust,
                               float siz, int sbPos, int midh) {
        float sx = (dust.worldX * 0.32f + 200f) * siz + sbPos;
        float ground = manualcontrol.custommap.CustomMapRuntime.activeDocument() == null
                ? midh - (156f - dust.layer * 4f) * siz
                : manualcontrol.custommap.CustomMapRuntime.projectY(painter, dust.layer);
        float t = dust.age / 16f;
        float drift = (10f + t * 48f) * siz * -dust.direction;
        int alpha = Math.max(0, Math.round((1f - t) * 170f));
        int r = dust.enemy ? 135 : 205;
        int gg = dust.enemy ? 108 : 190;
        int b = dust.enemy ? 92 : 160;
        try { g.setComposite(FakeGraphics.TRANS, alpha, 0); } catch (Throwable ignored) {}
        for (int i = 0; i < 3; i++) {
            float px = sx + drift * (0.55f + i * 0.22f);
            float py = ground - (4f + i * 5f + t * (8f + i * 3f)) * siz;
            float size = (10f + i * 5f) * siz * (0.65f + t * 0.7f);
            g.colRect(px - size / 2f, py - size / 2f, size, size, r, gg, b, alpha);
        }
        try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private void drawLanding(FakeGraphics g, Landing landing, float siz, int sbPos, int midh) {
        float sx = (landing.worldX * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - landing.layer * 4) * siz;
        float t = clamp01(landing.age / (float) LANDING_DURATION);
        try {
            switch (landing.style) {
                case SOLAR:
                    drawSolarLanding(g, landing, sx, ground, siz, t);
                    break;
                case VOID:
                    drawVoidLanding(g, landing, sx, ground, siz, t);
                    break;
                default:
                    drawCrystalLanding(g, landing, sx, ground, siz, t);
                    break;
            }
        } finally {
            try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
        }
    }

    private void drawCrystalLanding(FakeGraphics g, Landing landing,
                                    float cx, float ground, float siz, float t) {
        float impact = clamp01(t / 0.14f);
        float fade = clamp01((1f - t) / 0.58f);
        float spread = easeOut(t);

        int flash = Math.round(215f * (1f - impact) * fade);
        if (flash > 0) {
            g.setComposite(FakeGraphics.BLEND, flash, 1);
            g.colRect(cx - 76f * siz, ground - 5f * siz,
                    152f * siz, 7f * siz, 90, 225, 255, 230);
            g.colRect(cx - 34f * siz, ground - 18f * siz,
                    68f * siz, 18f * siz, 225, 255, 255, 120);
        }

        drawLandingRing(g, cx, ground - 3f * siz,
                (16f + spread * 106f) * siz, (5f + spread * 28f) * siz,
                70, 220, 255, Math.round(235f * fade), 3);
        float second = delayed(t, 0.11f);
        drawLandingRing(g, cx, ground - 3f * siz,
                (12f + easeOut(second) * 82f) * siz,
                (4f + easeOut(second) * 20f) * siz,
                235, 255, 255, Math.round(205f * (1f - second)), 2);

        g.setComposite(FakeGraphics.BLEND, Math.round(210f * fade), 1);
        g.setColor(85, 215, 255);
        for (int i = 0; i < 9; i++) {
            float side = i < 4 ? -1f : 1f;
            float lane = i < 4 ? i : i - 5;
            float x1 = cx + side * (10f + lane * 15f) * siz;
            float x2 = cx + side * (28f + lane * 26f) * siz;
            float bend = ((landing.seed >>> (i % 13)) & 7) - 3f;
            float mx = (x1 + x2) * 0.5f + bend * siz;
            g.drawLine(x1, ground, mx, ground - (4f + (i % 3) * 3f) * siz);
            g.drawLine(mx, ground - (4f + (i % 3) * 3f) * siz,
                    x2, ground - (i % 2) * 2f * siz);
            if ((i & 1) == 0) {
                g.drawLine(mx, ground - 5f * siz,
                        mx + side * 10f * siz, ground - 13f * siz);
            }
        }

        for (int i = 0; i < 8; i++) {
            float angle = (float) (-Math.PI + (i + 0.5) * Math.PI / 8.0);
            float local = delayed(t, i * 0.012f);
            float radial = (20f + easeOut(local) * (34f + (i % 3) * 9f)) * siz;
            float rise = (float) Math.sin(clamp01(local * 1.15f) * Math.PI)
                    * (28f + (i % 4) * 7f) * siz;
            float x = cx + (float) Math.cos(angle) * radial;
            float y = ground - 12f * siz - rise;
            float shard = (5f + (i % 3) * 1.8f) * siz * (0.65f + fade * 0.35f);
            drawDiamond(g, x, y, shard, 195, 250, 255,
                    Math.round(225f * fade), true);
        }

        float crownFade = clamp01((0.7f - t) / 0.45f);
        g.setComposite(FakeGraphics.BLEND, Math.round(205f * crownFade), 1);
        g.setColor(165, 245, 255);
        for (int i = -3; i <= 3; i++) {
            float baseX = cx + i * 13f * siz;
            float h = (25f + (3 - Math.abs(i)) * 10f) * siz * impact;
            g.drawLine(baseX - 7f * siz, ground - 2f * siz,
                    baseX, ground - h);
            g.drawLine(baseX, ground - h,
                    baseX + 7f * siz, ground - 2f * siz);
        }
    }

    private void drawSolarLanding(FakeGraphics g, Landing landing,
                                  float cx, float ground, float siz, float t) {
        float beamFade = clamp01((0.48f - t) / 0.34f);
        float fade = clamp01((1f - t) / 0.48f);
        float collapse = easeOut(clamp01(t / 0.4f));

        if (beamFade > 0f) {
            g.setComposite(FakeGraphics.BLEND, Math.round(220f * beamFade), 1);
            float outerW = (46f - collapse * 31f) * siz;
            float beamH = 230f * siz;
            g.colRect(cx - outerW / 2f, ground - beamH, outerW, beamH,
                    255, 175, 45, 150);
            g.colRect(cx - 10f * siz, ground - beamH, 20f * siz, beamH,
                    255, 235, 150, 215);
            g.colRect(cx - 3f * siz, ground - beamH, 6f * siz, beamH,
                    255, 255, 245, 255);
            float flareW = (118f - collapse * 60f) * siz;
            g.colRect(cx - flareW / 2f, ground - 18f * siz,
                    flareW, 18f * siz, 255, 195, 65, 145);
        }

        for (int i = 0; i < 3; i++) {
            float local = delayed(t, 0.06f + i * 0.105f);
            float ringFade = clamp01((1f - local) * fade);
            float radius = (12f + easeOut(local) * (92f + i * 18f)) * siz;
            drawLandingRing(g, cx, ground - 3f * siz, radius,
                    (5f + easeOut(local) * (20f + i * 3f)) * siz,
                    255, i == 0 ? 245 : 185 + i * 18,
                    i == 0 ? 205 : 55, Math.round(235f * ringFade), 3 - i / 2);
        }

        float rayFade = clamp01((0.62f - t) / 0.42f);
        g.setComposite(FakeGraphics.BLEND, Math.round(230f * rayFade), 1);
        for (int i = 0; i < 13; i++) {
            double angle = Math.PI + i * Math.PI / 12.0;
            float start = 14f * siz;
            float length = (42f + easeOut(t) * (68f + (i % 4) * 13f)) * siz;
            float x0 = cx + (float) Math.cos(angle) * start;
            float y0 = ground + (float) Math.sin(angle) * start * 0.32f;
            float x1 = cx + (float) Math.cos(angle) * length;
            float y1 = ground + (float) Math.sin(angle) * length * 0.32f;
            g.setColor(255, i % 3 == 0 ? 250 : 190, i % 3 == 0 ? 205 : 65);
            g.drawLine(x0, y0, x1, y1);
            if ((i & 1) == 0) g.drawLine(x0, y0 + siz, x1, y1 + siz);
        }

        for (int i = 0; i < 12; i++) {
            float local = delayed(t, (i % 4) * 0.025f);
            double angle = Math.PI + (i + 0.4) * Math.PI / 12.0;
            float velocity = 50f + (i % 5) * 12f;
            float radial = local * velocity * siz;
            float lift = ((float) Math.sin(local * Math.PI) * (34f + (i % 3) * 15f)
                    + 5f) * siz;
            float x = cx + (float) Math.cos(angle) * radial;
            float y = ground - lift;
            float size = (2.5f + (i % 3) * 1.4f) * siz;
            int a = Math.round(235f * fade);
            g.colRect(x - size / 2f, y - size / 2f, size, size,
                    i % 3 == 0 ? 255 : 210, i % 3 == 0 ? 245 : 110,
                    i % 3 == 0 ? 180 : 35, a);
        }
    }

    private void drawVoidLanding(FakeGraphics g, Landing landing,
                                 float cx, float ground, float siz, float t) {
        float fade = clamp01((1f - t) / 0.55f);
        float spread = easeOut(t);

        g.setComposite(FakeGraphics.BLEND, Math.round(175f * fade), 1);
        g.colRect(cx - (80f + spread * 40f) * siz, ground - 8f * siz,
                (160f + spread * 80f) * siz, 9f * siz,
                80, 20, 135, 130);

        drawLandingRing(g, cx, ground - 5f * siz,
                (14f + spread * 118f) * siz, (5f + spread * 30f) * siz,
                175, 70, 255, Math.round(235f * fade), 4);
        float inner = delayed(t, 0.09f);
        drawLandingRing(g, cx, ground - 5f * siz,
                (10f + easeOut(inner) * 88f) * siz,
                (4f + easeOut(inner) * 21f) * siz,
                65, 235, 255, Math.round(220f * (1f - inner)), 2);

        int flicker = 170 + Math.round(55f
                * (float) Math.sin(landing.age * 1.7f + landing.seed * 0.001f));
        g.setComposite(FakeGraphics.BLEND, Math.round(flicker * fade), 1);
        for (int side = -1; side <= 1; side += 2) {
            for (int lane = 0; lane < 4; lane++) {
                float x0 = cx + side * (9f + lane * 7f) * siz;
                float y0 = ground - (14f + lane * 10f) * siz;
                float x3 = cx + side * (56f + lane * 24f) * siz;
                float y3 = ground - (3f + lane * 2f) * siz;
                float jitter = (((landing.seed >>> ((lane + 3) % 16)) & 7) - 3f) * siz;
                g.setColor((lane & 1) == 0 ? 85 : 195,
                        (lane & 1) == 0 ? 245 : 90, 255);
                g.drawLine(x0, y0,
                        (x0 + x3) * 0.46f - side * 8f * siz, y0 - 19f * siz + jitter);
                g.drawLine((x0 + x3) * 0.46f - side * 8f * siz,
                        y0 - 19f * siz + jitter,
                        (x0 + x3) * 0.68f + side * 6f * siz, y0 - 7f * siz);
                g.drawLine((x0 + x3) * 0.68f + side * 6f * siz,
                        y0 - 7f * siz, x3, y3);
            }
        }

        float bladeFade = clamp01((0.82f - t) / 0.62f);
        drawCrescent(g, cx, ground - 10f * siz,
                (54f + spread * 55f) * siz, (18f + spread * 10f) * siz,
                190, 85, 255, Math.round(210f * bladeFade), false);
        drawCrescent(g, cx, ground - 5f * siz,
                (38f + spread * 48f) * siz, (12f + spread * 9f) * siz,
                75, 240, 255, Math.round(205f * bladeFade), true);

        for (int i = 0; i < 10; i++) {
            float local = delayed(t, (i % 5) * 0.018f);
            float side = (i & 1) == 0 ? -1f : 1f;
            float x = cx + side * (24f + easeOut(local) * (25f + i * 4f)) * siz;
            float y = ground - (16f + (float) Math.sin(local * Math.PI)
                    * (35f + (i % 3) * 9f)) * siz;
            float size = (3.5f + (i % 3)) * siz;
            drawDiamond(g, x, y, size,
                    (i & 1) == 0 ? 180 : 80,
                    (i & 1) == 0 ? 90 : 235, 255,
                    Math.round(210f * fade), false);
        }
    }

    private static void drawLandingRing(FakeGraphics g, float cx, float cy,
                                        float rx, float ry, int r, int gg, int b,
                                        int alpha, int thickness) {
        if (alpha <= 0 || rx <= 0f || ry <= 0f) return;
        g.setComposite(FakeGraphics.BLEND, clamp255(alpha), 1);
        g.setColor(r, gg, b);
        int count = Math.max(1, thickness);
        for (int i = 0; i < count; i++) {
            float inset = i - (count - 1) * 0.5f;
            drawEllipseLines(g, cx, cy, rx + inset, ry + inset * 0.35f);
        }
    }

    private static void drawEllipseLines(FakeGraphics g, float cx, float cy,
                                         float rx, float ry) {
        if (g == null || rx <= 0f || ry <= 0f) return;
        float px = cx + rx;
        float py = cy;
        for (int i = 1; i <= RING_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * i / RING_SEGMENTS;
            float x = cx + (float) Math.cos(angle) * rx;
            float y = cy + (float) Math.sin(angle) * ry;
            g.drawLine(px, py, x, y);
            px = x;
            py = y;
        }
    }

    private static void drawDiamond(FakeGraphics g, float cx, float cy, float radius,
                                    int r, int gg, int b, int alpha, boolean cross) {
        if (alpha <= 0 || radius <= 0f) return;
        g.setComposite(FakeGraphics.BLEND, clamp255(alpha), 1);
        g.setColor(r, gg, b);
        g.drawLine(cx, cy - radius * 1.55f, cx + radius, cy);
        g.drawLine(cx + radius, cy, cx, cy + radius * 1.55f);
        g.drawLine(cx, cy + radius * 1.55f, cx - radius, cy);
        g.drawLine(cx - radius, cy, cx, cy - radius * 1.55f);
        if (cross) {
            g.drawLine(cx - radius * 0.55f, cy, cx + radius * 0.55f, cy);
            g.drawLine(cx, cy - radius, cx, cy + radius);
        }
    }

    private static void drawCrescent(FakeGraphics g, float cx, float cy,
                                     float rx, float ry, int r, int gg, int b,
                                     int alpha, boolean reverse) {
        if (alpha <= 0) return;
        g.setComposite(FakeGraphics.BLEND, clamp255(alpha), 1);
        g.setColor(r, gg, b);
        float px = 0f, py = 0f;
        boolean first = true;
        for (int i = 0; i <= 18; i++) {
            float u = i / 18f;
            double angle = reverse ? Math.PI * (1f - u) : Math.PI * u;
            float x = cx + (float) Math.cos(angle) * rx;
            float y = cy - (float) Math.sin(angle) * ry;
            if (!first) {
                g.drawLine(px, py, x, y);
                g.drawLine(px, py + 1f, x, y + 1f);
            }
            px = x;
            py = y;
            first = false;
        }
    }

    private static float delayed(float t, float delay) {
        if (t <= delay) return 0f;
        return clamp01((t - delay) / (1f - delay));
    }

    private static float easeOut(float t) {
        float inv = 1f - clamp01(t);
        return 1f - inv * inv * inv;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void drawFlash(FakeGraphics g, Object bbpainter) {
        if (flashAge < 0) return;
        try {
            int w = BBPainterAccess.getWidth(bbpainter);
            int h = BBPainterAccess.getHeight(bbpainter);
            float env;
            int cr = 235, cg = 245, cb = 255;
            if (flashDouble) {

                float t = flashAge / 42f;
                env = Math.max(pulse(t, 0.16f, 0.11f), pulse(t, 0.55f, 0.16f));
                cr = 225; cg = 195; cb = 255;
            } else {
                float t = flashAge / (float) FLASH_DURATION;
                env = t < 0.3f ? (t / 0.3f) : (1f - (t - 0.3f) / 0.7f);
            }
            int a = Math.max(0, Math.round(env * 200f));
            if (a > 0) g.colRect(0, 0, w, h, cr, cg, cb, a);
        } catch (Throwable ignored) {}
    }

    private static float pulse(float t, float center, float width) {
        float d = (t - center) / width;
        return Math.max(0f, 1f - d * d);
    }

    private static void drawRing(FakeGraphics g, float cx, float cy, float r, int rr, int gg, int bb, int a) {
        if (a <= 0 || r <= 0) return;
        g.setColor(rr, gg, bb);
        float prevX = cx + r, prevY = cy;
        for (int i = 1; i <= RING_SEGMENTS; i++) {
            double ang = i * (Math.PI * 2 / RING_SEGMENTS);
            float x = cx + (float) Math.cos(ang) * r;
            float y = cy + (float) Math.sin(ang) * r * 0.42f;
            g.drawLine(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
        }
    }

    private static java.lang.reflect.Field tf;

    private static FakeTransform pushIdentity(FakeGraphics gra) {
        try {
            FakeTransform oldT = gra.getTransform();
            FakeTransform id = gra.getTransform();
            java.lang.reflect.Field f = tf;
            if (f == null || f.getDeclaringClass() != id.getClass()) {
                f = id.getClass().getDeclaredField("data");
                f.setAccessible(true);
                tf = f;
            }
            f.set(id, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            gra.setTransform(id);
            try { gra.delete(id); } catch (Throwable ignored) {}
            return oldT;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void popIdentity(FakeGraphics gra, FakeTransform oldT) {
        if (oldT == null) return;
        try { gra.setTransform(oldT); } catch (Throwable ignored) {
        } finally { try { gra.delete(oldT); } catch (Throwable ignored) {} }
    }
}
