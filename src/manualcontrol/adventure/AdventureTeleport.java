package manualcontrol.adventure;

import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import manualcontrol.crazy.collision.SpriteBounds;
import manualcontrol.crazy.collision.MeasuringGraphics;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.EntityAccess;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class AdventureTeleport {

    private static final float[] DISTANCE = {350f, 450f, 600f, 800f, 1000f};
    private static final int[] RECHARGE_TICKS = {360, 300, 240, 180, 120};
    private static final int[] MAX_CHARGES = {1, 1, 1, 2, 2};
    private static final float[] DAMAGE_MULT = {0.30f, 0.45f, 0.60f, 0.80f, 1.00f};

    private static final float SAMPLE_SPACING = 140f;
    private static final int MIN_SAMPLES = 3;
    private static final int MAX_SAMPLES = 8;
    private static final int MIN_GHOST_LIFE = 5;
    private static final int MAX_GHOST_LIFE = 12;
    private static final int MAX_GHOSTS = 32;
    private static final int SNAPSHOT_PADDING = 8;
    private static final int MAX_SNAPSHOT_SIDE = 2048;

    private static final class Snapshot {
        FakeImage image;
        float captureScale;
        float anchorX;
        float anchorY;
        int age;
    }

    private static final class Ghost {
        float rootX;
        float drawScale;
        float maxAlpha;
        int layer;
        int age;
        int life;
        boolean facingRight;
        Snapshot snapshot;
    }

    private final AdventureBattle battle;
    private final List<Ghost> ghosts = new ArrayList<Ghost>();
    private final List<Snapshot> snapshots = new ArrayList<Snapshot>();
    private static volatile Method animDrawMethod;
    private static volatile Class<?> animDrawClass;

    private int rank;
    private int charges;
    private int maxCharges;
    private int rechargeTicks;

    AdventureTeleport(AdventureBattle battle) {
        this.battle = battle;
    }

    void onLevelStart() {
        rank = clampRank(AdventureRuntime.cores().teleportRank());
        maxCharges = rank > 0 ? MAX_CHARGES[rank - 1] : 0;
        charges = maxCharges;
        rechargeTicks = 0;
        ghosts.clear();
        releaseSnapshots();
    }

    void tick() {
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            Ghost ghost = ghosts.get(i);
            ghost.age++;
            if (ghost.age >= ghost.life) ghosts.remove(i);
        }
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            Snapshot snapshot = snapshots.get(i);
            snapshot.age++;
            if (snapshot.age >= MAX_GHOST_LIFE) {
                unload(snapshot.image);
                snapshots.remove(i);
            }
        }

        if (rank <= 0 || charges >= maxCharges) {
            rechargeTicks = 0;
            return;
        }
        if (rechargeTicks <= 0) rechargeTicks = rechargeDurationTicks();
        if (--rechargeTicks <= 0) {
            charges++;
            rechargeTicks = charges < maxCharges ? rechargeDurationTicks() : 0;
        }
    }

    boolean tryActivate(EUnit player, boolean facingRight, float minPos, float maxPos) {
        if (player == null || rank <= 0 || charges <= 0) return false;

        float start = EntityAccess.getPos(player);
        float direction = facingRight ? 1f : -1f;
        float rawDestination = start + direction * DISTANCE[rank - 1];
        float destination = Math.max(minPos, Math.min(maxPos, rawDestination));
        float delta = destination - start;
        if (Math.abs(delta) < 1f) return false;

        SpriteBounds.WorldBox sourceBox = SpriteBounds.of(player);
        int layer = player.currentLayer;
        long damage = Math.max(1L,
                Math.round(AdventureCombat.currentPlayerDamage(player) * DAMAGE_MULT[rank - 1]));

        int hitCount = damageSweep(sourceBox, start, destination, damage);
        EntityAccess.setPos(player, destination);
        AdventureController.syncLastPosition(player);
        spawnAfterimages(player, start, destination, layer, facingRight);
        battle.spawnFx.spawnColored(start, layer, 70, 210, 255, 205, 245, 255);
        battle.spawnFx.spawnColored(destination, layer, 105, 235, 255, 235, 255, 255);
        if (hitCount > 0) {
            manualcontrol.Logger.log("Adventure: Blink hit " + hitCount
                    + " enemy(s) for " + damage);
        }

        charges--;
        if (charges < maxCharges && rechargeTicks <= 0) {
            rechargeTicks = rechargeDurationTicks();
        }
        return true;
    }

    private int damageSweep(SpriteBounds.WorldBox sourceBox, float start, float destination,
                            long damage) {
        SpriteBounds.WorldBox sweep = null;
        if (sourceBox != null) {
            float delta = destination - start;
            sweep = new SpriteBounds.WorldBox(
                    Math.min(sourceBox.x0, sourceBox.x0 + delta),
                    sourceBox.y0,
                    Math.max(sourceBox.x1, sourceBox.x1 + delta),
                    sourceBox.y1);
        }
        float pathMin = Math.min(start, destination) - 80f;
        float pathMax = Math.max(start, destination) + 80f;
        int hits = 0;

        try {
            for (EEnemy enemy : battle.liveEnemies()) {
                if (enemy == null || enemy.dead || enemy.health <= 0L) continue;
                if (AdventureController.isRevivingCorpse(enemy)) continue;

                SpriteBounds.WorldBox enemyBox = SpriteBounds.of(enemy);
                float halfWidth = enemyHalfWidth(enemy);
                boolean rootCorridorHit = enemy.pos + halfWidth >= pathMin
                        && enemy.pos - halfWidth <= pathMax;
                boolean hitX = rootCorridorHit;
                if (sweep != null && enemyBox != null) {

                    hitX = sweep.overlapsX(enemyBox) || rootCorridorHit;
                }

                boolean hitY = true;
                if (sourceBox != null && enemyBox != null) {

                    hitY = sourceBox.y0 <= enemyBox.y1 && enemyBox.y0 <= sourceBox.y1;
                }
                if (hitX && hitY && AdventureCombat.queueEffectDamage(enemy, damage)) {
                    hits++;
                    battle.spawnFx.spawnColored(enemy.pos, enemy.currentLayer,
                            55, 195, 255, 225, 250, 255);
                }
            }
        } catch (Throwable ignored) {}
        return hits;
    }

    private static float enemyHalfWidth(EEnemy enemy) {
        try {
            Object data = manualcontrol.reflect.BCUFields.get(enemy, "data");
            Object width = manualcontrol.reflect.BCUFields.invoke(data, "getWidth");
            if (width instanceof Number) {
                return Math.max(60f, ((Number) width).floatValue() * 0.5f);
            }
        } catch (Throwable ignored) {}
        return 100f;
    }

    private void spawnAfterimages(EUnit player, float start, float destination,
                                  int layer, boolean facingRight) {
        Snapshot snapshot = captureSnapshot(player);
        if (snapshot == null) return;
        snapshots.add(snapshot);

        float drawScale = AdventureBridge.drawScaleFor(player);
        float distance = Math.abs(destination - start);
        int samples = (int) Math.ceil(distance / SAMPLE_SPACING) + 1;
        samples = Math.max(MIN_SAMPLES, Math.min(MAX_SAMPLES, samples));

        for (int i = 0; i < samples; i++) {
            float order = samples == 1 ? 1f : i / (float) (samples - 1);

            float positionT = i / (float) samples;
            if (ghosts.size() >= MAX_GHOSTS) ghosts.remove(0);
            Ghost ghost = new Ghost();
            ghost.rootX = start + (destination - start) * positionT;
            ghost.drawScale = drawScale;
            ghost.maxAlpha = 55f + order * 65f;
            ghost.layer = layer;
            ghost.life = MIN_GHOST_LIFE
                    + Math.round(order * (MAX_GHOST_LIFE - MIN_GHOST_LIFE));
            ghost.facingRight = facingRight;
            ghost.snapshot = snapshot;
            ghosts.add(ghost);
        }
    }

    void draw(Object bbpainter, FakeGraphics graphics) {
        if (graphics == null || bbpainter == null || ghosts.isEmpty()) return;
        FakeTransform old = AdventureHud.pushIdentity(graphics);
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            if (siz <= 0.0001f) return;
            for (Ghost ghost : ghosts) drawGhost(graphics, ghost, siz, sbPos, midh);
        } catch (Throwable ignored) {
        } finally {
            AdventureHud.popIdentity(graphics, old);
        }
    }

    private void drawGhost(FakeGraphics graphics, Ghost ghost,
                           float siz, int sbPos, int midh) {
        float fade = 1f - ghost.age / (float) ghost.life;
        if (fade <= 0f) return;
        float rootX = (ghost.rootX * 0.32f + 200f) * siz + sbPos;
        float ground = midh - (156 - ghost.layer * 4) * siz;
        float easedFade = (float) Math.pow(fade, 1.35);
        drawSnapshotGhost(graphics, ghost, rootX, ground, siz,
                Math.round(ghost.maxAlpha * easedFade));
    }

    private void drawSnapshotGhost(FakeGraphics graphics, Ghost ghost,
                                   float rootX, float ground, float siz, int alpha) {
        Snapshot snapshot = ghost.snapshot;
        if (snapshot == null || snapshot.image == null || alpha <= 0) return;
        FakeTransform saved = null;
        try {
            saved = graphics.getTransform();
            float animSiz = siz * 0.8f * ghost.drawScale;
            float imageScale = animSiz / snapshot.captureScale;
            float x = rootX - snapshot.anchorX * imageScale;
            float y = ground - snapshot.anchorY * imageScale;
            float w = snapshot.image.getWidth() * imageScale;
            float h = snapshot.image.getHeight() * imageScale;
            if (ghost.facingRight) {
                float pivotX = rootX;
                float center = AdventureBridge.mirrorCenter(battle.player());
                if (!Float.isNaN(center)) pivotX += center * animSiz;
                graphics.translate(pivotX, 0f);
                graphics.scale(-1f, 1f);
                graphics.translate(-pivotX, 0f);
            }

            float halo = Math.max(1f, 1.5f * siz);
            graphics.setComposite(FakeGraphics.BLEND,
                    Math.max(1, Math.min(255, alpha / 4)), 1);
            graphics.drawImage(snapshot.image, x - halo, y - halo,
                    w + halo * 2f, h + halo * 2f);
            graphics.setComposite(FakeGraphics.TRANS, Math.max(0, Math.min(255, alpha)), 0);
            graphics.drawImage(snapshot.image, x, y, w, h);
        } catch (Throwable ignored) {
        } finally {
            try { graphics.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
            if (saved != null) {
                try { graphics.setTransform(saved); } catch (Throwable ignored) {}
                try { graphics.delete(saved); } catch (Throwable ignored) {}
            }
        }
    }

    private Snapshot captureSnapshot(EUnit player) {
        if (player == null || player.anim == null || ImageBuilder.builder == null) return null;
        FakeImage image = null;
        try {
            Method draw = resolveAnimDraw(player.anim);
            if (draw == null) return null;

            MeasuringGraphics measure = new MeasuringGraphics();
            draw.invoke(player.anim, measure, new P(0f, 0f), 1f);
            if (!measure.hasBox()) return null;
            float minX = measure.minX();
            float minY = measure.minY();
            float modelW = Math.max(1f, measure.maxX() - minX);
            float modelH = Math.max(1f, measure.maxY() - minY);
            float captureScale = Math.min(1f,
                    Math.min((MAX_SNAPSHOT_SIDE - SNAPSHOT_PADDING * 2f) / modelW,
                            (MAX_SNAPSHOT_SIDE - SNAPSHOT_PADDING * 2f) / modelH));
            if (captureScale <= 0.0001f) return null;

            int width = Math.max(1, Math.min(MAX_SNAPSHOT_SIDE,
                    (int) Math.ceil(modelW * captureScale) + SNAPSHOT_PADDING * 2));
            int height = Math.max(1, Math.min(MAX_SNAPSHOT_SIDE,
                    (int) Math.ceil(modelH * captureScale) + SNAPSHOT_PADDING * 2));
            image = ImageBuilder.builder.build(width, height);
            if (image == null) return null;
            Object raw = image.bimg();
            if (!(raw instanceof BufferedImage)) {
                unload(image);
                return null;
            }

            BufferedImage buffered = (BufferedImage) raw;
            Graphics2D clear = buffered.createGraphics();
            clear.setComposite(AlphaComposite.Clear);
            clear.fillRect(0, 0, width, height);
            clear.dispose();

            float anchorX = SNAPSHOT_PADDING - minX * captureScale;
            float anchorY = SNAPSHOT_PADDING - minY * captureScale;
            draw.invoke(player.anim, image.getGraphics(),
                    new P(anchorX, anchorY), captureScale);
            tintCyan(buffered);

            Snapshot snapshot = new Snapshot();
            snapshot.image = image;
            snapshot.captureScale = captureScale;
            snapshot.anchorX = anchorX;
            snapshot.anchorY = anchorY;
            return snapshot;
        } catch (Throwable ignored) {
            unload(image);
            return null;
        }
    }

    private static void tintCyan(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int alpha = argb >>> 24;
                if (alpha == 0) continue;
                int red = (argb >>> 16) & 0xff;
                int green = (argb >>> 8) & 0xff;
                int blue = argb & 0xff;
                int luminance = (red * 54 + green * 183 + blue * 19) >>> 8;
                int cyanR = 15 + luminance * 71 / 255;
                int cyanG = 120 + luminance * 115 / 255;
                int cyanB = 190 + luminance * 65 / 255;
                image.setRGB(x, y, (alpha << 24) | (cyanR << 16) | (cyanG << 8) | cyanB);
            }
        }
    }

    private void releaseSnapshots() {
        for (Snapshot snapshot : snapshots) unload(snapshot.image);
        snapshots.clear();
    }

    private static void unload(FakeImage image) {
        if (image == null) return;
        try { image.unload(); } catch (Throwable ignored) {}
    }

    private static Method resolveAnimDraw(Object anim) {
        Class<?> type = anim.getClass();
        Method cached = animDrawMethod;
        if (cached != null && animDrawClass == type) return cached;
        try {
            cached = type.getMethod("draw", FakeGraphics.class, P.class, float.class);
        } catch (Throwable first) {
            try {
                cached = type.getDeclaredMethod("draw", FakeGraphics.class, P.class, float.class);
                cached.setAccessible(true);
            } catch (Throwable ignored) {
                return null;
            }
        }
        animDrawClass = type;
        animDrawMethod = cached;
        return cached;
    }

    int rank() { return rank; }
    int charges() { return charges; }
    int maxCharges() { return maxCharges; }
    int rechargeTicks() { return rechargeTicks; }
    int rechargeDurationTicks() {
        return rank > 0 ? RECHARGE_TICKS[rank - 1] : 0;
    }
    float rechargeProgress() {
        if (charges >= maxCharges || maxCharges <= 0) return 0f;
        int duration = rechargeDurationTicks();
        if (duration <= 0) return 0f;
        return Math.max(0f, Math.min(1f, 1f - rechargeTicks / (float) duration));
    }

    private static int clampRank(int value) {
        return Math.max(0, Math.min(5, value));
    }
}
