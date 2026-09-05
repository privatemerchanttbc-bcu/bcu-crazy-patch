package manualcontrol.adventure;

import common.battle.attack.AttackAb;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import manualcontrol.crazy.collision.SpriteBounds;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.EntityAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

final class AdventureArchitect {

    static final int WALL = 0;
    static final int PLATFORM = 1;
    static final int GATE = 2;
    static final int LAUNCH_PAD = 3;
    static final int ANCHOR = 4;
    static final int TYPE_COUNT = 5;

    private static final int INSTALL_TICKS = 24;
    private static final int LIFE_TICKS = 900;
    private static final int DESTROY_TICKS = 14;
    private static final int REMOVAL_DESTROYED = 1;
    private static final int REMOVAL_EXPIRED = 2;
    private static final float BUILD_DISTANCE = 260f;
    private static final float MIN_X = -525f;
    private static final float TARGET_RANGE = 1200f;

    private static final int[] COOLDOWN = {360, 300, 450, 240, 600};
    private static final float[] HP_MULT = {3f, 1.8f, 2.5f, 1.5f, 2.2f};
    private static final float[] WIDTH = {180f, 320f, 170f, 220f, 130f};
    static final float VISUAL_SCALE = 4.5f;
    private static final String[] NAMES = {
            "BARRIER", "PLATFORM", "BLOCKER GATE", "LAUNCH PAD", "ANCHOR PYLON"
    };

    static final class Construct {
        final int type;
        final float x;
        final int layer;
        final long maxHp;
        long hp;
        int install = INSTALL_TICKS;
        int life = LIFE_TICKS;
        int destroy;
        int removalReason;
        boolean complete;
        float healAcc;

        Construct(int type, float x, int layer, long maxHp) {
            this.type = type;
            this.x = x;
            this.layer = layer;
            this.maxHp = maxHp;
            this.hp = maxHp;
        }

        float halfWidth() { return WIDTH[type] * 0.5f; }
        boolean active() { return complete && destroy <= 0 && hp > 0L; }
    }

    private static final class EnemyLaunch {
        float layer;
        float ground;
        float velocity;
    }

    private final AdventureBattle battle;
    private final List<Construct> constructs = new ArrayList<Construct>();
    private final int[] cooldown = new int[TYPE_COUNT];
    private final WeakHashMap<EEnemy, Construct> enemyTargets =
            new WeakHashMap<EEnemy, Construct>();
    private final Set<Object> resolvedAttacks = Collections.newSetFromMap(
            new WeakHashMap<Object, Boolean>());
    private final WeakHashMap<Object, Integer> padRearm =
            new WeakHashMap<Object, Integer>();
    private final WeakHashMap<EEnemy, EnemyLaunch> enemyLaunches =
            new WeakHashMap<EEnemy, EnemyLaunch>();

    private int selected;
    private float previewX;
    private int previewLayer;
    private boolean previewValid;

    AdventureArchitect(AdventureBattle battle) {
        this.battle = battle;
    }

    static float visualWidth(int type, float siz) {
        int i = Math.max(0, Math.min(TYPE_COUNT - 1, type));
        return WIDTH[i] * 0.32f * siz * VISUAL_SCALE;
    }

    void onLevelStart() {
        constructs.clear();
        java.util.Arrays.fill(cooldown, 0);
        enemyTargets.clear();
        resolvedAttacks.clear();
        padRearm.clear();
        enemyLaunches.clear();
        selected = 0;
        previewValid = false;
    }

    void tick(EUnit player, AdventureController controller) {
        int rank = AdventureRuntime.cores().architectRank();
        if (rank <= 0) {
            AdventureInput.consumeArchitectCycle();
            AdventureInput.consumeArchitectBuild();
            previewValid = false;
            return;
        }
        if (selected >= rank) selected = rank - 1;
        int cycle = AdventureInput.consumeArchitectCycle();
        if (cycle != 0) {
            selected = (selected + (cycle > 0 ? 1 : rank - 1)) % rank;
            AdventureSfx.play(AdventureSfx.SELECT_MOVE);
        }

        for (int i = 0; i < cooldown.length; i++) if (cooldown[i] > 0) cooldown[i]--;
        tickConstructs();
        tickPadRearm();
        tickLaunchPads(player, controller);

        updatePreview(player, controller);
        if (AdventureInput.consumeArchitectBuild() && previewValid && cooldown[selected] <= 0) {
            long maxHp = Math.max(1L, Math.round(player.maxH * HP_MULT[selected]));
            constructs.add(new Construct(selected, previewX, previewLayer, maxHp));
            cooldown[selected] = COOLDOWN[selected];
            battle.coreVfx.spawnArchitectInstall(previewX, previewLayer, selected);
            AdventureSfx.play(AdventureSfx.SELECT_MOVE);
        }
    }

    private void tickConstructs() {
        Iterator<Construct> it = constructs.iterator();
        while (it.hasNext()) {
            Construct c = it.next();
            if (c.destroy > 0) {
                if (--c.destroy <= 0) it.remove();
                continue;
            }
            if (!c.complete) {
                if (--c.install <= 0) lock(c);
                continue;
            }
            if (c.hp <= 0L) beginRemoval(c, REMOVAL_DESTROYED);
            else if (--c.life <= 0) beginRemoval(c, REMOVAL_EXPIRED);
        }
        healFromAnchors();
    }

    private void beginRemoval(Construct c, int reason) {
        if (c == null || c.destroy > 0) return;
        c.destroy = DESTROY_TICKS;
        c.removalReason = reason;
        enemyTargets.values().removeAll(Collections.singleton(c));
        if (reason == REMOVAL_DESTROYED) {
            battle.camera.shake(7, 4);
            battle.coreVfx.spawnArchitectDestroyed(c.x, c.layer, c.type);
        } else {
            battle.coreVfx.spawnArchitectExpired(c.x, c.layer, c.type);
        }
    }

    private void lock(Construct built) {
        built.complete = true;
        built.install = 0;
        for (Construct old : constructs) {
            if (old != built && old.type == built.type && old.active()) {
                beginRemoval(old, REMOVAL_EXPIRED);
            }
        }
        battle.camera.shake(5, 2);
        battle.coreVfx.spawnArchitectLock(built.x, built.layer, built.type);
    }

    private void healFromAnchors() {
        for (Construct anchor : constructs) {
            if (!anchor.active() || anchor.type != ANCHOR) continue;
            for (Construct c : constructs) {
                if (!c.active() || c.hp >= c.maxHp || Math.abs(c.x - anchor.x) > 650f) continue;
                c.healAcc += c.maxHp * 0.01f / 30f;
                long whole = (long) c.healAcc;
                if (whole > 0L) {
                    c.healAcc -= whole;
                    c.hp = Math.min(c.maxHp, c.hp + whole);
                }
            }
        }
    }

    private void updatePreview(EUnit player, AdventureController controller) {
        previewValid = false;
        if (player == null || !AdventureController.canDrive(player)
                || controller.isAirborne() || AdventureController.isAttackActive(player)) return;
        float maxX = battle.sb.st.len + 525f;
        boolean facingRight = controller.isFacingRight();
        float front = player.pos;
        SpriteBounds.WorldBox playerBox = SpriteBounds.of(player);
        if (playerBox != null) front = facingRight ? playerBox.x1 : playerBox.x0;
        previewX = Math.max(MIN_X, Math.min(maxX,
                front + (facingRight ? BUILD_DISTANCE : -BUILD_DISTANCE)));
        previewLayer = player.currentLayer;
        previewValid = cooldown[selected] <= 0 && !overlapsInvalid(previewX, selected);
    }

    private boolean overlapsInvalid(float x, int type) {
        float half = WIDTH[type] * 0.5f;
        for (Construct c : constructs) {
            if (c.destroy <= 0 && Math.abs(c.x - x) < c.halfWidth() + half + 16f) return true;
        }
        for (Entity e : battle.sb.le) {
            if (e == null || e == battle.player() || e.dead || e.health <= 0L) continue;
            SpriteBounds.WorldBox box = SpriteBounds.of(e);
            if (box != null ? box.x1 >= x - half && box.x0 <= x + half
                    : Math.abs(e.pos - x) < half + 80f) return true;
        }
        if (battle.player() != null && Math.abs(battle.player().pos - x) < half + 70f) return true;
        return battle.showDoor() && Math.abs(battle.door().worldX() - x) < half + 130f;
    }

    Construct targetFor(EEnemy enemy) {
        if (enemy == null) return null;
        Construct best = null;
        float distance = TARGET_RANGE + 1f;
        for (Construct c : constructs) {
            if (!c.active()) continue;
            float d = Math.abs(c.x - enemy.pos);
            if (d <= TARGET_RANGE && d < distance) {
                best = c;
                distance = d;
            }
        }
        if (best == null) enemyTargets.remove(enemy); else enemyTargets.put(enemy, best);
        return best;
    }

    boolean enemyTargetsConstruct(Object enemy) {
        Construct c = enemyTargets.get(enemy);
        return c != null && c.active();
    }

    float targetX(EEnemy enemy, EUnit player) {
        Construct c = targetFor(enemy);
        return c == null ? player.pos : c.x;
    }

    boolean canEngageConstruct(EEnemy enemy, float range) {
        Construct c = enemyTargets.get(enemy);
        return c != null && c.active()
                && Math.abs(enemy.pos - c.x) <= range + c.halfWidth();
    }

    void onEnemyAttack(AttackAb attack) {
        if (attack == null || !(attack.attacker instanceof EEnemy)
                || !resolvedAttacks.add(attack)) return;
        Construct c = enemyTargets.get(attack.attacker);
        if (c == null || !c.active()) return;
        damage(c, Math.max(0L, attack.atk));
    }

    private void damage(Construct c, long amount) {
        if (c == null || amount <= 0L || !c.active()) return;
        if (insideAnchor(c)) amount = Math.max(1L, Math.round(amount * 0.6f));
        c.hp = Math.max(0L, c.hp - amount);
        battle.coreVfx.spawnArchitectHit(c.x, c.layer, c.type);
        if (c.hp <= 0L) beginRemoval(c, REMOVAL_DESTROYED);
    }

    private boolean insideAnchor(Construct c) {
        for (Construct anchor : constructs) {
            if (anchor != c && anchor.active() && anchor.type == ANCHOR
                    && Math.abs(c.x - anchor.x) <= 650f) return true;
        }
        return false;
    }

    boolean blocksAttack(float enemyX, float playerX) {
        float lo = Math.min(enemyX, playerX), hi = Math.max(enemyX, playerX);
        for (Construct c : constructs) {
            if (c.active() && (c.type == WALL || c.type == GATE)
                    && c.x > lo && c.x < hi) return true;
        }
        return false;
    }

    float clampPlayerMove(EUnit player, float from, float to) {
        for (Construct c : constructs) {
            if (!c.active() || c.type != WALL) continue;
            if (playerClearsTop(player, c)) continue;
            float pad = c.halfWidth() + 45f;
            if (from < c.x && to > c.x - pad) to = Math.min(to, c.x - pad);
            if (from > c.x && to < c.x + pad) to = Math.max(to, c.x + pad);
        }
        return to;
    }

    private boolean playerClearsTop(EUnit player, Construct c) {
        if (player == null) return false;
        FakeImage image = battle.coreVfx.architectTexture(c.type);
        if (image == null || image.getWidth() <= 0) return player.currentLayer < c.layer - 42;
        float renderedHeight = WIDTH[c.type] * 0.32f * VISUAL_SCALE
                * image.getHeight() / image.getWidth();
        float heightInLayers = renderedHeight / 4f;
        return c.layer - player.currentLayer > heightInLayers;
    }

    float clampEnemyMove(EEnemy enemy, float from, float to) {
        for (Construct c : constructs) {
            if (!c.active() || (c.type != WALL && c.type != GATE)) continue;
            float pad = c.halfWidth() + 55f;
            if (from < c.x && to > c.x - pad) to = Math.min(to, c.x - pad);
            if (from > c.x && to < c.x + pad) to = Math.max(to, c.x + pad);
        }
        if (!manualcontrol.custommap.CustomMapRuntime.canEnemyWalk(from, to)) return from;
        return to;
    }

    int groundLayerAt(float x, int normalGround) {
        int ground = Math.round(manualcontrol.custommap.CustomMapRuntime.surfaceLayerAt(x, normalGround));
        for (Construct c : constructs) {
            if (c.active() && c.type == PLATFORM
                    && Math.abs(c.x - x) <= c.halfWidth()) {
                ground = Math.min(ground, c.layer - 28);
            }
        }
        return ground;
    }

    boolean tickEnemyLaunch(EEnemy enemy) {
        EnemyLaunch launch = enemyLaunches.get(enemy);
        if (launch == null) return false;
        launch.velocity += 0.42f;
        launch.layer += launch.velocity;
        if (launch.layer >= launch.ground) {
            EntityAccess.setLayer(enemy, Math.round(launch.ground));
            enemyLaunches.remove(enemy);
            return false;
        }
        EntityAccess.setLayer(enemy, Math.round(launch.layer));
        AdventureController.setWalking(enemy, false);
        AdventureController.syncLastPosition(enemy);
        return true;
    }

    private void tickLaunchPads(EUnit player, AdventureController controller) {
        for (Construct c : constructs) {
            if (!c.active() || c.type != LAUNCH_PAD) continue;
            if (player != null && Math.abs(player.pos - c.x) <= c.halfWidth()
                    && readyForPad(player)) {
                controller.forceJump(player, 1.25f);
                armPad(player);
                battle.coreVfx.spawnLaunch(c.x, c.layer);
            }
            for (EEnemy e : battle.liveEnemies()) {
                if (e == null || e.dead || enemyLaunches.containsKey(e)
                        || Math.abs(e.pos - c.x) > c.halfWidth() || !readyForPad(e)) continue;
                EnemyLaunch launch = new EnemyLaunch();
                launch.layer = e.currentLayer;
                launch.ground = e.currentLayer;
                launch.velocity = -7.25f;
                enemyLaunches.put(e, launch);
                armPad(e);
                battle.coreVfx.spawnLaunch(c.x, c.layer);
            }
        }
    }

    private boolean readyForPad(Object entity) {
        Integer ticks = padRearm.get(entity);
        return ticks == null || ticks <= 0;
    }

    private void armPad(Object entity) { padRearm.put(entity, 20); }

    private void tickPadRearm() {
        Iterator<java.util.Map.Entry<Object, Integer>> it = padRearm.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Object, Integer> en = it.next();
            if (en.getValue() == null || en.getValue() <= 1) it.remove();
            else en.setValue(en.getValue() - 1);
        }
    }

    int rank() { return AdventureRuntime.cores().architectRank(); }
    int selectedType() { return selected; }
    String selectedName() { return NAMES[Math.max(0, Math.min(TYPE_COUNT - 1, selected))]; }
    int cooldownTicks() { return cooldown[Math.max(0, Math.min(TYPE_COUNT - 1, selected))]; }
    int cooldownMaxTicks() { return COOLDOWN[Math.max(0, Math.min(TYPE_COUNT - 1, selected))]; }
    boolean previewValid() { return previewValid; }
    List<Construct> constructs() { return constructs; }

    void drawGround(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null) return;
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            for (Construct c : constructs) drawConstruct(g, c, siz, sbPos, midh);
            if (rank() > 0 && battle.player() != null && !battle.isPaused()
                    && !battle.isChoosingCore()) drawPreview(g, siz, sbPos, midh);
        } catch (Throwable ignored) {}
    }

    private void drawConstruct(FakeGraphics g, Construct c, float siz, int sbPos, int midh) {
        float sx = (c.x * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - c.layer * 4) * siz;
        FakeImage image = battle.coreVfx.architectTexture(c.type);
        if (image == null) return;
        float w = visualWidth(c.type, siz);
        float h = w * image.getHeight() / Math.max(1f, image.getWidth());
        if (c.active() && c.type == ANCHOR) {
            FakeImage field = battle.coreVfx.anchorFieldTexture();
            if (field != null) {
                float fw = 650f * 2f * 0.32f * siz;
                float fh = Math.max(18f * siz,
                        fw * field.getHeight() / Math.max(1f, field.getWidth()) * 0.18f);
                int pulse = 45 + Math.abs((battle.sb.time % 24) - 12) * 4;
                g.setComposite(FakeGraphics.TRANS, pulse, 0);
                g.drawImage(field, sx - fw / 2f, ground - fh / 2f, fw, fh);
                g.setComposite(FakeGraphics.DEF, 0, 0);
            }
        }
        int alpha = 255;
        if (!c.complete) {
            float progress = 1f - c.install / (float) INSTALL_TICKS;
            FakeImage blueprint = battle.coreVfx.architectBlueprint(c.type, true);
            if (blueprint != null) {
                int wireAlpha = Math.max(35, Math.round(215f * (1f - progress * 0.55f)));
                g.setComposite(FakeGraphics.TRANS, wireAlpha, 0);
                g.drawImage(blueprint, sx - w / 2f, ground - h, w, h);
                g.setComposite(FakeGraphics.DEF, 0, 0);
            }
            alpha = progress < 0.34f ? 0 : Math.round(255f * (progress - 0.34f) / 0.66f);
        }
        if (c.destroy > 0) {
            alpha = Math.max(0, c.destroy * 255 / DESTROY_TICKS);
            if (c.removalReason == REMOVAL_DESTROYED && (c.destroy / 2 & 1) == 0) {
                alpha = Math.round(alpha * 0.55f);
            }
        }
        if (alpha > 0) {
            g.setComposite(FakeGraphics.TRANS, alpha, 0);
            g.drawImage(image, sx - w / 2f, ground - h, w, h);
            g.setComposite(FakeGraphics.DEF, 0, 0);
        }
        if (c.active() && c.hp < c.maxHp) {
            float frac = Math.max(0f, Math.min(1f, (float) c.hp / c.maxHp));
            g.colRect(sx - w * 0.35f, ground - h - 5f, w * 0.7f, 3f, 12, 16, 20, 220);
            g.colRect(sx - w * 0.35f, ground - h - 5f, w * 0.7f * frac, 3f,
                    55, 220, 235, 245);
        }
    }

    private void drawPreview(FakeGraphics g, float siz, int sbPos, int midh) {
        float sx = (previewX * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - previewLayer * 4) * siz;
        FakeImage image = battle.coreVfx.architectBlueprint(selected, previewValid);
        if (image == null) return;
        float w = visualWidth(selected, siz);
        float h = w * image.getHeight() / Math.max(1f, image.getWidth());
        g.setComposite(FakeGraphics.TRANS, 185, 0);
        g.drawImage(image, sx - w / 2f, ground - h, w, h);
        g.setComposite(FakeGraphics.DEF, 0, 0);
    }
}
