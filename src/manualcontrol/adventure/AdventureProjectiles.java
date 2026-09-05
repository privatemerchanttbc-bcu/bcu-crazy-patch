package manualcontrol.adventure;

import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import manualcontrol.reflect.BBPainterAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

final class AdventureProjectiles {

    private static final float WAVE_SPEED = 88f;
    private static final float SURGE_DMG_PCT = 0.4f;
    private static final int SURGE_HIT_EVERY = 10;

    private static final class Wave {
        float x;
        float dir;
        float remaining;
        int lv;
        int layer;
        int age;
        boolean hostile;
        long dmg;
        boolean hitPlayer;
        final Set<EEnemy> hit = Collections.newSetFromMap(new WeakHashMap<EEnemy, Boolean>());
    }

    private static final class Surge {
        float x0, x1;
        int ticksLeft;
        int lv;
        int layer;
        int age;
        boolean hostile;
        long dmg;
    }

    private final AdventureBattle battle;
    private final Random rnd = new Random();
    private final List<Wave> waves = new ArrayList<Wave>();
    private final List<Surge> surges = new ArrayList<Surge>();

    AdventureProjectiles(AdventureBattle battle) {
        this.battle = battle;
    }

    void tryProc(EUnit player, EEnemy struck, long strikeDamage) {
        AdventureCoreState cores = AdventureRuntime.cores();
        long dmg = Math.max(1L, strikeDamage);
        if (cores.waveChance() > 0f && rnd.nextFloat() < cores.waveChance()) {
            int lv = 1 + rnd.nextInt(Math.max(1, cores.waveMaxLv()));
            launchPlayerWave(player, lv, dmg);
            battle.hud.showMessage("WAVE LV" + lv + "!", 140, 215, 255);
        }
        if (struck != null && cores.surgeChance() > 0f && rnd.nextFloat() < cores.surgeChance()) {
            int lv = 1 + rnd.nextInt(Math.max(1, cores.surgeMaxLv()));
            float cx = struck.pos + (rnd.nextFloat() * 2f - 1f) * (100f + rnd.nextFloat() * 150f);
            spawnSurgeAt(cx, struck.currentLayer, lv, false,
                    Math.round(dmg * SURGE_DMG_PCT));
            battle.hud.showMessage("SURGE LV" + lv + "!", 255, 150, 74);
        }
    }

    void launchPlayerWave(EUnit player, int lv, long dmg) {
        if (player == null || waves.size() >= 8) return;
        Wave w = new Wave();
        w.dir = AdventureBridge.isAdventureFlipped(player) ? 1f : -1f;
        w.x = playerFaceX(player, w.dir);
        w.remaining = 2f * unitRange(player);
        w.lv = Math.max(1, lv);
        w.layer = player.currentLayer;
        w.hostile = false;
        w.dmg = Math.max(1L, dmg);
        waves.add(w);
    }

    private static float playerFaceX(EUnit player, float dir) {
        try {
            manualcontrol.crazy.collision.SpriteBounds.WorldBox box =
                    manualcontrol.crazy.collision.SpriteBounds.of(player);
            if (box != null) return dir > 0f ? box.x1 + 18f : box.x0 - 18f;
        } catch (Throwable ignored) {}
        return player.pos + dir * 120f;
    }

    private static float unitRange(EUnit player) {
        try {
            Object data = manualcontrol.reflect.BCUFields.get(player, "data");
            Object r = manualcontrol.reflect.BCUFields.invoke(data, "getRange");
            if (r instanceof Number) return Math.max(300f, ((Number) r).floatValue());
        } catch (Throwable ignored) {}
        return 450f;
    }

    void launchHostileWave(float x, float dir, int lv, long dmg, int layer) {
        if (waves.size() >= 8) return;
        Wave w = new Wave();
        w.x = x;
        w.dir = dir;
        w.remaining = 300f + Math.max(1, lv) * 300f;
        w.lv = Math.max(1, lv);
        w.layer = layer;
        w.hostile = true;
        w.dmg = Math.max(1, dmg);
        waves.add(w);
    }

    void spawnSurgeAt(float cx, int layer, int lv, boolean hostile, long dmg) {
        if (surges.size() >= 8) return;
        float half = (160f + Math.max(1, lv) * 40f) / 2f;
        Surge s = new Surge();
        s.x0 = cx - half;
        s.x1 = cx + half;
        s.ticksLeft = 30 + Math.max(1, lv) * 20;
        s.lv = Math.max(1, lv);
        s.layer = layer;
        s.hostile = hostile;
        s.dmg = Math.max(1, dmg);
        surges.add(s);
    }

    void tick() {
        EUnit player = battle.player();

        for (int i = waves.size() - 1; i >= 0; i--) {
            Wave w = waves.get(i);
            w.age++;
            float previousX = w.x;
            w.x += w.dir * WAVE_SPEED;
            w.remaining -= WAVE_SPEED;
            manualcontrol.crazy.collision.SpriteBounds.WorldBox waveBox =
                    waveSweepBox(w, previousX);
            try {
                if (w.hostile) {
                    if (!w.hitPlayer && playerVulnerable(player)
                            && effectOverlapsEntity(player, waveBox,
                                    Math.min(previousX, w.x) - 120f,
                                    Math.max(previousX, w.x) + 120f)) {
                        w.hitPlayer = true;
                        AdventureCombat.queueEffectDamage(player, w.dmg);
                    }
                } else {
                    for (EEnemy e : battle.liveEnemies()) {
                        if (e == null || e.dead || e.health <= 0L || w.hit.contains(e)) continue;
                        if (AdventureController.isRevivingCorpse(e)) continue;
                        if (effectOverlapsEntity(e, waveBox,
                                Math.min(previousX, w.x) - 60f,
                                Math.max(previousX, w.x) + 60f)) {
                            w.hit.add(e);
                            AdventureCombat.queueEffectDamage(e, w.dmg);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (w.remaining <= 0f) waves.remove(i);
        }

        for (int i = surges.size() - 1; i >= 0; i--) {
            Surge s = surges.get(i);
            s.age++;
            if (--s.ticksLeft <= 0) {
                surges.remove(i);
                continue;
            }
            if (s.age % SURGE_HIT_EVERY == 0) {
                manualcontrol.crazy.collision.SpriteBounds.WorldBox surgeBox =
                        surgeBox(s);
                try {
                    if (s.hostile) {
                        if (playerVulnerable(player)
                                && effectOverlapsEntity(player, surgeBox, s.x0, s.x1)) {
                            AdventureCombat.queueEffectDamage(player, s.dmg);
                        }
                    } else {
                        for (EEnemy e : battle.liveEnemies()) {
                            if (e == null || e.dead || e.health <= 0L) continue;
                            if (AdventureController.isRevivingCorpse(e)) continue;
                            if (effectOverlapsEntity(e, surgeBox, s.x0, s.x1)) {
                                AdventureCombat.queueEffectDamage(e, s.dmg);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static manualcontrol.crazy.collision.SpriteBounds.WorldBox waveSweepBox(
            Wave w, float previousX) {
        float halfHeightPx = 58f + w.lv * 20f;
        float thickPx = 20f + w.lv * 4f;
        float trailing = (halfHeightPx * 0.58f + thickPx * 1.5f + 45f) / 0.32f;
        float leading = 52f / 0.32f;
        float currentLo = w.dir > 0f ? w.x - trailing : w.x - leading;
        float currentHi = w.dir > 0f ? w.x + leading : w.x + trailing;
        float previousLo = w.dir > 0f ? previousX - trailing : previousX - leading;
        float previousHi = w.dir > 0f ? previousX + leading : previousX + trailing;
        float groundY = w.layer * 4f / manualcontrol.crazy.collision.SpriteBounds.RAT;
        float topY = groundY - (halfHeightPx * 2.1f + 18f)
                / manualcontrol.crazy.collision.SpriteBounds.RAT;
        return new manualcontrol.crazy.collision.SpriteBounds.WorldBox(
                Math.min(currentLo, previousLo), topY,
                Math.max(currentHi, previousHi), groundY);
    }

    private static manualcontrol.crazy.collision.SpriteBounds.WorldBox surgeBox(Surge s) {
        float groundY = s.layer * 4f / manualcontrol.crazy.collision.SpriteBounds.RAT;
        float topY = groundY - (58f + s.lv * 20f)
                / manualcontrol.crazy.collision.SpriteBounds.RAT;
        return new manualcontrol.crazy.collision.SpriteBounds.WorldBox(
                s.x0, topY, s.x1, groundY);
    }

    private static boolean effectOverlapsEntity(
            Entity entity,
            manualcontrol.crazy.collision.SpriteBounds.WorldBox effect,
            float fallbackX0, float fallbackX1) {
        if (entity == null) return false;
        try {
            manualcontrol.crazy.collision.SpriteBounds.WorldBox body =
                    manualcontrol.crazy.collision.SpriteBounds.of(entity);
            if (effect != null && body != null) return effect.overlaps(body);
        } catch (Throwable ignored) {}
        return AdventureCombat.overlapsHorizontal(entity, fallbackX0, fallbackX1);
    }

    private static boolean playerVulnerable(EUnit player) {
        if (player == null || player.dead || player.health <= 0L) return false;
        try {
            int[][] status = (int[][]) manualcontrol.reflect.BCUFields.get(player, "status");
            if (status != null && status[44][0] > 0) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    void draw(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || (waves.isEmpty() && surges.isEmpty())) return;
        FakeTransform old = AdventureHud.pushIdentity(g);
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            if (siz <= 0.0001f) return;
            for (int i = 0; i < waves.size(); i++) drawWave(g, waves.get(i), siz, sbPos, midh);
            for (int i = 0; i < surges.size(); i++) drawSurge(g, surges.get(i), siz, sbPos, midh);
        } catch (Throwable ignored) {
        } finally {
            AdventureHud.popIdentity(g, old);
        }
    }

    private void drawWave(FakeGraphics g, Wave w, float siz, int sbPos, int midh) {
        float lead = (w.x * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - w.layer * 4) * siz;
        float hh = (58f + w.lv * 20f) * siz;
        float cy = ground - hh - 14f * siz;
        float dir = w.dir;
        float thick = (20f + w.lv * 4f) * siz;
        float shimmer = 0.85f + 0.15f * (float) Math.sin(w.age * 0.6f);

        int r = w.hostile ? 255 : 70, gg = w.hostile ? 70 : 165, bb = w.hostile ? 100 : 255;
        int hr = w.hostile ? 255 : 150, hg = w.hostile ? 160 : 220, hb = 255;

        try {
            g.setComposite(FakeGraphics.BLEND, 255, 1);

            for (int t = 3; t >= 1; t--) {
                float gx = lead - dir * t * 15f * siz;
                crescent(g, gx, cy, hh * (1f - t * 0.05f), dir, thick * 0.8f,
                        r, gg, bb, Math.round(46f / t * shimmer));
            }

            crescent(g, lead, cy, hh * 1.06f, dir, thick * 1.5f, r, gg, bb, Math.round(70f * shimmer));

            crescent(g, lead, cy, hh, dir, thick, r, gg, bb, Math.round(120f * shimmer));

            crescent(g, lead, cy, hh * 0.9f, dir, thick * 0.5f, hr, hg, hb, Math.round(175f * shimmer));

            edgeArc(g, lead, cy, hh, dir, 4);

            for (int i = 0; i < 5; i++) {
                float ph = ((w.age * 2.2f + i * 13f) % 26f) / 26f;
                float sx = lead + dir * ph * 46f * siz;
                float sy = cy + (float) Math.sin(w.age * 0.5f + i * 2.3f) * hh * 0.7f;
                float s = (4f - ph * 2.5f) * siz;
                int a = Math.round((1f - ph) * 230f);
                if (s > 0.5f) g.colRect(sx - s / 2f, sy - s / 2f, s, s, 235, 248, 255, a);
            }

            g.setComposite(FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
    }

    private void crescent(FakeGraphics g, float lead, float cy, float hh, float dir,
                          float thick, int r, int gg, int bb, int a) {
        if (a <= 1 || hh <= 1f) return;
        int rows = Math.max(10, Math.round(hh));
        for (int i = 0; i <= rows; i++) {
            float fy = -1f + 2f * i / rows;
            float bulge = 1f - fy * fy;
            if (bulge <= 0.01f) continue;
            float y = cy + fy * hh;
            float frontX = lead - dir * (1f - bulge) * hh * 0.55f;
            float wdt = thick * bulge;
            float x0 = dir > 0 ? frontX - wdt : frontX;
            int aa = Math.round(a * bulge);

            g.colRect(x0, y - 1.3f, wdt, 2.6f, r, gg, bb, aa);
        }
    }

    private void edgeArc(FakeGraphics g, float lead, float cy, float hh, float dir, int seg) {
        g.setColor(240, 250, 255);
        float px = 0, py = 0;
        boolean first = true;
        int steps = Math.max(12, Math.round(hh / 4f));
        for (int i = 0; i <= steps; i++) {
            float fy = -1f + 2f * i / steps;
            float bulge = 1f - fy * fy;
            if (bulge <= 0.02f) { first = true; continue; }
            float y = cy + fy * hh;
            float x = lead - dir * (1f - bulge) * hh * 0.55f;
            if (!first) g.drawLine(px, py, x, y);
            px = x; py = y; first = false;
        }
    }

    private void drawSurge(FakeGraphics g, Surge s, float siz, int sbPos, int midh) {
        float ground = midh - (156 - s.layer * 4) * siz;
        float sx0 = (s.x0 * 0.32f + 200f) * siz + sbPos;
        float sx1 = (s.x1 * 0.32f + 200f) * siz + sbPos;
        float w = Math.max(10f, sx1 - sx0);
        float cx = (sx0 + sx1) / 2f;
        float fade = Math.min(1f, s.ticksLeft / 20f) * Math.min(1f, s.age / 4f);

        int br = s.hostile ? 210 : 255, bg = s.hostile ? 50 : 120, bb = s.hostile ? 180 : 40;
        int mr = 255, mg = s.hostile ? 130 : 205, mb = s.hostile ? 235 : 110;

        try {
            g.setComposite(FakeGraphics.BLEND, 255, 1);

            moundGlow(g, cx, ground, w * 0.62f, (34f + s.lv * 12f) * siz, br, bg, bb, Math.round(60f * fade));
            moundGlow(g, cx, ground, w * 0.42f, (26f + s.lv * 9f) * siz, mr, mg, mb, Math.round(95f * fade));

            int pillars = 3 + s.lv;
            for (int i = 0; i < pillars; i++) {
                float fx = sx0 + (i + 0.5f) * w / pillars;
                float flick = 0.55f + 0.45f * (float) Math.sin(s.age * 0.7f + i * 2.1f)
                        * (float) Math.cos(s.age * 0.31f + i);
                float ph = (58f + s.lv * 20f) * siz * (0.55f + 0.45f * flick);
                float pw = (30f + s.lv * 3f) * siz;
                float sway = 8f * siz;
                flameTongue(g, fx, ground, ph, pw, sway, i, s.age, br, bg, bb, Math.round(150 * fade));
                flameTongue(g, fx, ground, ph * 0.8f, pw * 0.62f, sway * 0.8f, i, s.age, mr, mg, mb, Math.round(190 * fade));
                flameTongue(g, fx, ground, ph * 0.55f, pw * 0.34f, sway * 0.6f, i, s.age, 255, 245, 210, Math.round(210 * fade));
            }

            for (int i = 0; i < 6 + s.lv; i++) {
                float ph = ((s.age * 2.6f + i * 11f) % 44f) / 44f;
                float ex = sx0 + ((i * 37 % 100) / 100f) * w + (float) Math.sin(s.age * 0.2f + i) * 4f;
                float ey = ground - ph * (70f + s.lv * 16f) * siz;
                float es = (3.5f - ph * 2.5f) * siz;
                if (es > 0.4f) g.colRect(ex - es / 2f, ey - es / 2f, es, es, 255, 220, 150, Math.round((1f - ph) * 220f * fade));
            }

            g.setComposite(FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
    }

    private void moundGlow(FakeGraphics g, float cx, float ground, float rad, float hgt,
                           int r, int gg, int bb, int a) {
        if (a <= 1 || hgt <= 1f) return;
        int rows = Math.max(8, Math.round(hgt / 2.5f));
        for (int i = 0; i <= rows; i++) {
            float f = i / (float) rows;
            float ww = rad * (float) Math.sqrt(Math.max(0f, 1f - f * f));
            if (ww <= 0.5f) continue;
            float y = ground - f * hgt;
            float rowH = hgt / rows;
            int aa = Math.round(a * (1f - f * 0.5f));
            g.colRect(cx - ww, y - rowH, ww * 2f, rowH + 0.7f, r, gg, bb, aa);
        }
    }

    private void flameTongue(FakeGraphics g, float fx, float ground, float ph, float pw,
                             float sway, int seed, int age, int r, int gg, int bb, int a) {
        if (ph <= 1f || a <= 1) return;
        int rows = Math.max(8, Math.round(ph / 2.5f));
        for (int i = 0; i < rows; i++) {
            float f = i / (float) rows;
            float prof = (float) Math.pow(1f - f, 1.25) * (0.62f + 0.38f * (float) Math.sin(f * 3.1f));
            if (prof <= 0f) continue;
            float rw = pw * prof;
            if (rw <= 0.3f) continue;
            float cxOff = sway * (float) Math.sin(f * 3f + age * 0.25f + seed) * f;
            float rowH = ph / rows;
            float y = ground - f * ph;
            int aa = Math.round(a * (0.55f + 0.45f * (1f - f)));
            g.colRect(fx + cxOff - rw / 2f, y - rowH, rw, rowH + 0.7f, r, gg, bb, aa);
        }
    }
}
