package manualcontrol.adventure;

import common.battle.entity.EEnemy;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import manualcontrol.reflect.BBPainterAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class AdventureAfterimage {

    private static final int MAIN_LIFE = 30;
    private static final int EXTRA_LIFE = 90;
    private static final int STRIKE_TICK = 5;
    private static final float STRIKE_PAD = 60f;
    private static final float TAUNT_RADIUS = 700f;
    private static final int MAX_GHOSTS = 12;

    private static final class Ghost {
        float x0, x1;
        float top;
        int layer;
        int age;
        int life;
        boolean taunt;
        boolean struck;
    }

    private final AdventureBattle battle;
    private final List<Ghost> ghosts = new ArrayList<Ghost>();
    private final Random rnd = new Random();

    AdventureAfterimage(AdventureBattle battle) {
        this.battle = battle;
    }

    void onPlayerHitLanded() {
        AdventureCoreState cores = AdventureRuntime.cores();
        if (!cores.hasUnique("P8")) return;
        common.battle.entity.EUnit player = battle.player();
        if (player == null) return;

        spawnAt(playerBoxX0(player), playerBoxX1(player), player.currentLayer, MAIN_LIFE, false);

        if (cores.hasUnique("L6") && rnd.nextFloat() < 0.30f) {
            List<EEnemy> live = battle.liveEnemies();
            List<EEnemy> alive = new ArrayList<EEnemy>();
            for (EEnemy e : live) {
                if (e != null && !e.dead && e.health > 0L) alive.add(e);
            }
            if (!alive.isEmpty()) {
                EEnemy target = alive.get(rnd.nextInt(alive.size()));
                for (int i = 0; i < 2; i++) {
                    float off = (60f + rnd.nextFloat() * 120f) * (i == 0 ? -1f : 1f);
                    float cx = target.pos + off;
                    spawnAt(cx - 80f, cx + 80f, target.currentLayer, EXTRA_LIFE, true);
                }
                battle.hud.showMessage("AFTERIMAGE SWARM!", 90, 180, 255);
            }
        }
    }

    private void spawnAt(float x0, float x1, int layer, int life, boolean taunt) {
        if (ghosts.size() >= MAX_GHOSTS) ghosts.remove(0);
        Ghost g = new Ghost();
        g.x0 = Math.min(x0, x1);
        g.x1 = Math.max(x0, x1);
        g.top = playerTop();
        g.layer = layer;
        g.life = life;
        g.taunt = taunt;
        ghosts.add(g);
    }

    private float playerBoxX0(common.battle.entity.EUnit player) {
        manualcontrol.crazy.collision.SpriteBounds.WorldBox b =
                manualcontrol.crazy.collision.SpriteBounds.of(player);
        return b != null ? b.x0 : player.pos - 120f;
    }

    private float playerBoxX1(common.battle.entity.EUnit player) {
        manualcontrol.crazy.collision.SpriteBounds.WorldBox b =
                manualcontrol.crazy.collision.SpriteBounds.of(player);
        return b != null ? b.x1 : player.pos + 120f;
    }

    private float playerTop() {
        float t = AdventureBridge.playerSpriteTop();
        return t == t ? t : -140f;
    }

    void tick() {
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            Ghost g = ghosts.get(i);
            g.age++;
            if (!g.struck && g.age >= STRIKE_TICK) {
                g.struck = true;
                strike(g);
            }
            if (g.age > g.life) ghosts.remove(i);
        }
    }

    private void strike(Ghost g) {
        long dmg = AdventureCombat.currentPlayerDamage(battle.player());
        try {
            boolean hitAny = false;
            for (EEnemy e : battle.liveEnemies()) {
                if (e == null || e.dead || e.health <= 0L) continue;
                if (AdventureController.isRevivingCorpse(e)) continue;
                if (AdventureCombat.overlapsHorizontal(
                        e, g.x0 - STRIKE_PAD, g.x1 + STRIKE_PAD)
                        && AdventureCombat.queueEffectDamage(e, dmg)) {
                    hitAny = true;
                }
            }
            if (hitAny) {
                battle.spawnFx.spawnColored((g.x0 + g.x1) * 0.5f, g.layer,
                        90, 180, 255, 200, 235, 255);
            }
        } catch (Throwable ignored) {}
    }

    float tauntXFor(EEnemy enemy) {
        float best = Float.NaN, bestD = TAUNT_RADIUS;
        for (int i = 0; i < ghosts.size(); i++) {
            Ghost g = ghosts.get(i);
            if (!g.taunt) continue;
            float cx = (g.x0 + g.x1) * 0.5f;
            float d = Math.abs(cx - enemy.pos);
            if (d < bestD) {
                bestD = d;
                best = cx;
            }
        }
        return best;
    }

    void draw(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || ghosts.isEmpty()) return;
        FakeTransform old = AdventureHud.pushIdentity(g);
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            if (siz <= 0.0001f) return;
            for (int i = 0; i < ghosts.size(); i++) {
                drawGhost(g, ghosts.get(i), siz, sbPos, midh);
            }
        } catch (Throwable ignored) {
        } finally {
            AdventureHud.popIdentity(g, old);
        }
    }

    private void drawGhost(FakeGraphics g, Ghost gh, float siz, int sbPos, int midh) {
        float fade = 1f - gh.age / (float) gh.life;
        if (fade <= 0f) return;
        float sx0 = (gh.x0 * 0.32f + 200f) * siz + sbPos;
        float sx1 = (gh.x1 * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - gh.layer * 4) * siz;
        float h = Math.max(30f, -gh.top * siz);
        float y = ground - h;
        float w = Math.max(8f, sx1 - sx0);

        int bodyA = Math.round(60f * fade);
        int frameA = Math.round(150f * fade);
        g.colRect(sx0, y, w, h, 90, 180, 255, bodyA);

        g.colRect(sx0, y, w, 2, 140, 215, 255, frameA);
        g.colRect(sx0, y + h - 2, w, 2, 140, 215, 255, frameA);
        g.colRect(sx0, y, 2, h, 140, 215, 255, frameA);
        g.colRect(sx1 - 2, y, 2, h, 140, 215, 255, frameA);

        int lines = 4;
        for (int i = 0; i < lines; i++) {
            float ph = ((gh.age * 1.5f + i * (h / lines)) % h);
            g.colRect(sx0 + 2, y + h - ph, w - 4, 1.5f, 200, 240, 255, Math.round(90f * fade));
        }

        if (gh.taunt) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(gh.age * 0.25f);
            try {
                g.setComposite(FakeGraphics.BLEND, Math.round(120 * fade * pulse) , 1);
                g.colRect(sx0 - 6, ground - 5, w + 12, 5, 90, 180, 255, 255);
                g.setComposite(FakeGraphics.DEF, 0, 0);
            } catch (Throwable ignored) {}
        }
    }
}
