package manualcontrol.crazy.fall;

import common.battle.StageBasis;
import common.battle.attack.AttackCanon;
import common.battle.entity.AbEntity;
import common.battle.entity.Entity;
import common.pack.UserProfile;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.Data;
import common.util.unit.Trait;
import manualcontrol.FallingRegistry;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ImpactFallFeature {

    private static final int MAX_TARGETS = 20;
    private static final int RING_FRAMES = 42;
    private static final int DUST_FRAMES = 80;
    private static final int FLASH_FRAMES = 8;
    private static final int SQUASH_IN_FRAMES = 6;
    private static final int SQUASH_HOLD_FRAMES = 4;
    private static final float SQUASH_X = 1.35f;
    private static final float SQUASH_Y = 0.22f;
    private static final float CRACK_WIDTH_SCALE = 0.50f;
    private static final int TICKS_PER_SECOND = 30;
    private static final int SPRITE_SHOCKWAVE = 1;
    private static final int SPRITE_FLASH = 2;
    private static final int SPRITE_CRACK = 3;
    private static final int SPRITE_SMOKE = 4;
    private static final int SPRITE_ROCK = 5;
    private static final Map<Integer, FakeImage> SPRITE_CACHE =
            Collections.synchronizedMap(new HashMap<Integer, FakeImage>());

    private ImpactFallFeature() {}

    public static final class State {
        public final Object lock = new Object();
        public final List<ImpactVisual> visuals = new ArrayList<ImpactVisual>();
        public final List<LaunchJob> launches = new ArrayList<LaunchJob>();
        public final Map<Object, SquashJob> squashes = new WeakHashMap<Object, SquashJob>();
    }

    private static final class Target {
        final AbEntity entity;
        final float screenX;
        final float screenY;
        final float distance;
        final boolean base;
        final boolean launchImmune;

        Target(AbEntity entity, float screenX, float screenY, float distance,
               boolean base, boolean launchImmune) {
            this.entity = entity;
            this.screenX = screenX;
            this.screenY = screenY;
            this.distance = distance;
            this.base = base;
            this.launchImmune = launchImmune;
        }
    }

    private static final class ImpactVisual {
        final float pos;
        final int layer;
        final float radiusWorld;
        final int seed;
        final Color color;
        final int crackHoldFrames;
        final int crackFadeFrames;
        final SmokePuff[] smoke;
        final RockChip[] rocks;
        int age;

        ImpactVisual(float pos, int layer, float radiusWorld, Color color,
                     int crackHoldFrames, int crackFadeFrames, int seed) {
            this.pos = pos;
            this.layer = layer;
            this.radiusWorld = radiusWorld;
            this.color = color;
            this.crackHoldFrames = Math.max(0, crackHoldFrames);
            this.crackFadeFrames = Math.max(1, crackFadeFrames);
            this.seed = seed;
            this.smoke = createSmoke(seed);
            this.rocks = createRocks(seed);
        }

        boolean done() {
            return age > Math.max(DUST_FRAMES, crackHoldFrames + crackFadeFrames);
        }
    }

    private static final class SmokePuff {
        final int seed;
        final int delay;
        final float angle;
        final float distance;
        final float lift;
        final float size;
        final float scaleX;
        final float scaleY;
        final float rotation;
        final float spin;

        SmokePuff(int seed, int delay, float angle, float distance, float lift,
                  float size, float scaleX, float scaleY, float rotation, float spin) {
            this.seed = seed;
            this.delay = delay;
            this.angle = angle;
            this.distance = distance;
            this.lift = lift;
            this.size = size;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.rotation = rotation;
            this.spin = spin;
        }
    }

    private static final class RockChip {
        final int seed;
        final int delay;
        final float angle;
        final float speed;
        final float lift;
        final float size;
        final float rotation;
        final float spin;

        RockChip(int seed, int delay, float angle, float speed, float lift,
                 float size, float rotation, float spin) {
            this.seed = seed;
            this.delay = delay;
            this.angle = angle;
            this.speed = speed;
            this.lift = lift;
            this.size = size;
            this.rotation = rotation;
            this.spin = spin;
        }
    }

    private static final class LaunchJob {
        final Entity entity;
        final int origLayer;
        final float groundY;
        final float siz;
        final int stagePos;
        final int midh;
        float screenX;
        float screenY;
        float vx;
        float vy;
        int age;

        LaunchJob(Entity entity, int origLayer, float screenX, float screenY,
                  float vx, float vy, float groundY, float siz, int stagePos, int midh) {
            this.entity = entity;
            this.origLayer = origLayer;
            this.screenX = screenX;
            this.screenY = screenY;
            this.vx = vx;
            this.vy = vy;
            this.groundY = groundY;
            this.siz = siz;
            this.stagePos = stagePos;
            this.midh = midh;
        }
    }

    private static final class SquashJob {
        final Entity entity;
        int age;
        boolean killed;

        SquashJob(Entity entity) {
            this.entity = entity;
        }
    }

    public static boolean isEnabled(Object pageOrPainter, Object entity) {
        CrazyRuntime.StageRuntime rt = runtimeFor(pageOrPainter, entity);
        return rt != null && rt.config.impactFall;
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return false;
        synchronized (rt.impactFall.lock) {
            return !rt.impactFall.visuals.isEmpty()
                    || !rt.impactFall.launches.isEmpty()
                    || !rt.impactFall.squashes.isEmpty();
        }
    }

    public static boolean triggerLanding(Object pageOrPainter, Object entity,
                                         float screenX, float groundY,
                                         float impactVy, float maxFallHeightPx,
                                         int origLayer) {
        return triggerLandingInternal(pageOrPainter, entity, screenX, groundY,
                impactVy, maxFallHeightPx, origLayer, null, true);
    }

    public static boolean triggerLandingFromBomb(Object entity,
                                                 float screenX, float groundY,
                                                 float impactVy, float maxFallHeightPx,
                                                 int origLayer,
                                                 float siz, int stagePos, int midh) {
        return triggerLandingInternal(null, entity, screenX, groundY,
                impactVy, maxFallHeightPx, origLayer,
                new Transform(siz, stagePos, midh), false);
    }

    private static boolean triggerLandingInternal(Object pageOrPainter, Object entity,
                                                  float screenX, float groundY,
                                                  float impactVy, float maxFallHeightPx,
                                                  int origLayer, Transform overrideTransform,
                                                  boolean requireEnabled) {
        CrazyRuntime.StageRuntime rt = runtimeFor(pageOrPainter, entity);
        if (rt == null || (requireEnabled && !rt.config.impactFall) || !(entity instanceof Entity)) return false;
        if (maxFallHeightPx < (float) rt.config.impactFallMinHeightPx) return false;
        if (impactVy < (float) rt.config.impactFallMinSpeed) return false;

        StageBasis sb = (StageBasis) rt.stage;
        Transform tr = overrideTransform == null ? transformFor(pageOrPainter, sb) : overrideTransform;
        if (tr == null || tr.siz < 0.001f) return false;

        Entity falling = (Entity) entity;
        float gameX = screenToGameX(screenX, tr.siz, tr.stagePos);
        falling.pos = gameX;
        falling.lastPosition = gameX;
        try { EntityAccess.setLayer(falling, origLayer); } catch (Throwable ignored) {}

        float spriteRadius = Math.max(28f, EntityAccess.estimateScreenRadius(falling, tr.siz));
        float radiusPx = clamp(spriteRadius * (float) rt.config.impactFallRadiusScale, 140f, 520f);
        float radiusWorld = radiusPx / Math.max(0.001f, 0.32f * tr.siz);
        long maxH = Math.max(1L, falling.maxH);
        int centerDamage = clampDamage(Math.round(maxH * impactVy * (float) rt.config.impactFallDamageScale));
        Color color = colorFor(falling);
        int seed = stableSeed(falling, Math.round(impactVy * 10f));

        synchronized (rt.impactFall.lock) {
            rt.impactFall.visuals.add(new ImpactVisual(gameX, origLayer, radiusWorld, color,
                    secondsToFrames(rt.config.impactFallCrackHoldSeconds),
                    secondsToFrames(rt.config.impactFallCrackFadeSeconds), seed));
        }
        applyCameraShake(sb, impactVy, spriteRadius);

        ArrayList<Trait> attackTraits = traitsFor(falling);
        List<Target> targets = collectTargets(sb, falling, screenX, groundY, radiusPx, tr);
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            float falloff = clamp01(1f - target.distance / radiusPx);
            if (falloff <= 0f) continue;
            int damage = clampDamage(Math.round(centerDamage * falloff));
            if (damage > 0) damageTarget(sb, target.entity, damage, attackTraits);
            if (!target.launchImmune && target.entity instanceof Entity) {
                launch(rt, (Entity) target.entity, target, screenX, impactVy, falloff, tr);
            }
        }

        synchronized (rt.impactFall.lock) {
            rt.impactFall.squashes.put(falling, new SquashJob(falling));
        }
        Logger.log("Impact Fall triggered: entity=" + falling.getClass().getSimpleName()
                + " heightPx=" + Math.round(maxFallHeightPx)
                + " vy=" + Math.round(impactVy)
                + " damage=" + centerDamage
                + " radiusPx=" + Math.round(radiusPx)
                + " targets=" + targets.size());
        return true;
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        tickLaunches(rt);
        tickSquashes(rt);
        tickVisuals(rt);
    }

    public static boolean isManaged(Object entity) {
        CrazyRuntime.StageRuntime rt = runtimeFor(null, entity);
        if (rt == null || entity == null) return false;
        synchronized (rt.impactFall.lock) {
            if (rt.impactFall.squashes.containsKey(entity)) return true;
            for (int i = 0; i < rt.impactFall.launches.size(); i++) {
                LaunchJob job = rt.impactFall.launches.get(i);
                if (job != null && job.entity == entity) return true;
            }
        }
        return false;
    }

    public static float[] squashScale(Object entity) {
        CrazyRuntime.StageRuntime rt = runtimeFor(null, entity);
        if (rt == null || entity == null) return null;
        synchronized (rt.impactFall.lock) {
            SquashJob job = rt.impactFall.squashes.get(entity);
            if (job == null) return null;
            float p = clamp01(job.age / (float) Math.max(1, SQUASH_IN_FRAMES));
            float eased = 1f - (1f - p) * (1f - p);
            float sx = 1f + (SQUASH_X - 1f) * eased;
            float sy = 1f + (SQUASH_Y - 1f) * eased;
            return new float[]{sx, sy};
        }
    }

    public static void drawUnder(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || gra == null) return;
        List<ImpactVisual> visuals;
        synchronized (rt.impactFall.lock) {
            if (rt.impactFall.visuals.isEmpty()) return;
            visuals = new ArrayList<ImpactVisual>(rt.impactFall.visuals);
        }
        for (int i = 0; i < visuals.size(); i++) {
            ImpactVisual v = visuals.get(i);
            float x = CrazyRender.screenX(bbpainter, v.pos);
            float y = CrazyRender.groundY(bbpainter, v.layer);
            float r = Math.max(10f, v.radiusWorld * 0.32f * BBPainterAccess.getSiz(bbpainter));
            drawUnderSprites(gra, v, x, y, r);
        }
    }

    public static void drawOverlay(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || gra == null) return;
        List<ImpactVisual> visuals;
        synchronized (rt.impactFall.lock) {
            if (rt.impactFall.visuals.isEmpty()) return;
            visuals = new ArrayList<ImpactVisual>(rt.impactFall.visuals);
        }
        for (int i = 0; i < visuals.size(); i++) {
            ImpactVisual v = visuals.get(i);
            if (v.age >= FLASH_FRAMES) continue;
            float x = CrazyRender.screenX(bbpainter, v.pos);
            float y = CrazyRender.groundY(bbpainter, v.layer);
            float r = Math.max(10f, v.radiusWorld * 0.32f * BBPainterAccess.getSiz(bbpainter));
            drawOverlaySprites(gra, v, x, y, r);
        }
    }

    private static SmokePuff[] createSmoke(int seed) {
        int count = particleCount(seed);
        SmokePuff[] out = new SmokePuff[count];
        for (int i = 0; i < count; i++) {
            int s = seed + i * 7919;
            float angle = (float) (Math.PI * 2.0 * rand01(s + 11));
            float distance = 0.18f + 0.86f * rand01(s + 23);
            float lift = 0.18f + 0.42f * rand01(s + 37);
            float size = 0.15f + 0.17f * rand01(s + 41);
            float scaleX = 1.20f + 0.85f * rand01(s + 53);
            float scaleY = 0.62f + 0.52f * rand01(s + 59);
            float rotation = (float) (Math.PI * 2.0 * rand01(s + 61));
            float spin = -0.030f + 0.060f * rand01(s + 67);
            int delay = Math.round(6f * rand01(s + 71));
            out[i] = new SmokePuff(s, delay, angle, distance, lift, size, scaleX, scaleY, rotation, spin);
        }
        return out;
    }

    private static RockChip[] createRocks(int seed) {
        int count = 4 + Math.abs((seed >>> 3) % 4);
        RockChip[] out = new RockChip[count];
        for (int i = 0; i < count; i++) {
            int s = seed + i * 3571 + 97;
            float angle = (float) (Math.PI * 2.0 * rand01(s + 5));
            float speed = 0.28f + 0.76f * rand01(s + 13);
            float lift = 0.16f + 0.36f * rand01(s + 19);
            float size = 0.045f + 0.050f * rand01(s + 29);
            float rotation = (float) (Math.PI * 2.0 * rand01(s + 31));
            float spin = -0.095f + 0.190f * rand01(s + 43);
            int delay = Math.round(3f * rand01(s + 47));
            out[i] = new RockChip(s, delay, angle, speed, lift, size, rotation, spin);
        }
        return out;
    }

    private static void drawUnderSprites(FakeGraphics gra, ImpactVisual v, float x, float y, float r) {
        float crackA = crackAlpha(v);
        if (crackA > 0f) {
            FakeImage crack = sprite(SPRITE_CRACK, v.color, v.seed);
            drawSprite(gra, crack, x - r * 1.18f, y - r * 0.48f, r * 2.36f, r * 0.96f,
                    FakeGraphics.TRANS, Math.round(255f * crackA), 0);
        }

        if (v.age < RING_FRAMES) {
            float p = v.age / (float) RING_FRAMES;
            float e = easeOut(p);
            float ringR = r * (0.20f + 1.02f * e);
            int alpha = Math.round(230f * (1f - p));
            FakeImage wave = sprite(SPRITE_SHOCKWAVE, v.color, 0);
            drawSprite(gra, wave, x - ringR * 1.12f, y - ringR * 0.32f,
                    ringR * 2.24f, ringR * 0.64f, FakeGraphics.BLEND, Math.round(alpha * 0.42f), 1);
            drawSprite(gra, wave, x - ringR, y - ringR * 0.24f,
                    ringR * 2f, ringR * 0.48f, FakeGraphics.TRANS, alpha, 0);
        }

        if (v.age < DUST_FRAMES) {
            drawSmokeSprites(gra, v, x, y, r);
            drawRockSprites(gra, v, x, y, r);
        }
    }

    private static void drawOverlaySprites(FakeGraphics gra, ImpactVisual v, float x, float y, float r) {
        float p = v.age / (float) FLASH_FRAMES;
        float alpha = 1f - p;
        if (alpha <= 0f) return;
        FakeImage flash = sprite(SPRITE_FLASH, v.color, 0);
        drawSprite(gra, flash, x - r * 0.82f, y - r * 0.30f, r * 1.64f, r * 0.60f,
                FakeGraphics.BLEND, Math.round(150f * alpha), 1);
        drawSprite(gra, flash, x - r * 0.60f, y - r * 0.22f, r * 1.20f, r * 0.44f,
                FakeGraphics.TRANS, Math.round(205f * alpha), 0);
    }

    private static void drawSmokeSprites(FakeGraphics gra, ImpactVisual v, float x, float y, float r) {
        for (int i = 0; i < v.smoke.length; i++) {
            SmokePuff puf = v.smoke[i];
            int local = v.age - puf.delay;
            if (local < 0 || local >= DUST_FRAMES) continue;
            float p = local / (float) DUST_FRAMES;
            float e = easeOut(p);
            float dist = r * puf.distance * e;
            float px = x + (float) Math.cos(puf.angle) * dist;
            float py = y + (float) Math.sin(puf.angle) * dist * 0.22f
                    - r * puf.lift * (float) Math.sin(Math.PI * p);
            float growth = 0.70f + 0.68f * p;
            float w = r * puf.size * puf.scaleX * growth;
            float h = r * puf.size * puf.scaleY * growth;
            int alpha = Math.round(190f * (float) Math.pow(1f - p, 1.35));
            FakeImage smoke = sprite(SPRITE_SMOKE, v.color, puf.seed);
            drawSpriteRotated(gra, smoke, px, py, w, h, puf.rotation + puf.spin * local,
                    FakeGraphics.TRANS, alpha, 0);
        }
    }

    private static void drawRockSprites(FakeGraphics gra, ImpactVisual v, float x, float y, float r) {
        for (int i = 0; i < v.rocks.length; i++) {
            RockChip rock = v.rocks[i];
            int local = v.age - rock.delay;
            if (local < 0 || local >= 54) continue;
            float p = local / 54f;
            float px = x + (float) Math.cos(rock.angle) * r * rock.speed * p;
            float py = y + (float) Math.sin(rock.angle) * r * rock.speed * 0.20f * p
                    - r * rock.lift * (float) Math.sin(Math.PI * p)
                    + r * 0.10f * p * p;
            float d = r * rock.size * (1f - p * 0.18f);
            int alpha = Math.round(235f * (1f - p));
            FakeImage chip = sprite(SPRITE_ROCK, v.color, rock.seed);
            drawSpriteRotated(gra, chip, px, py, d, d, rock.rotation + rock.spin * local,
                    FakeGraphics.TRANS, alpha, 0);
        }
    }

    private static void drawSprite(FakeGraphics gra, FakeImage img, float x, float y, float w, float h,
                                   int composite, int alpha, int p1) {
        if (gra == null || img == null || w <= 1f || h <= 1f || alpha <= 0) return;
        try {
            gra.setComposite(composite, clamp255(alpha), p1);
            gra.drawImage(img, x, y, w, h);
        } catch (Throwable t) {
            drawSpriteFallback(gra, x, y, w, h, alpha);
        } finally {
            resetComposite(gra);
        }
    }

    private static void drawSpriteRotated(FakeGraphics gra, FakeImage img, float cx, float cy, float w, float h,
                                          float rotation, int composite, int alpha, int p1) {
        if (gra == null || img == null || w <= 1f || h <= 1f || alpha <= 0) return;
        FakeTransform old = null;
        try {
            old = gra.getTransform();
            gra.translate(cx, cy);
            if (Math.abs(rotation) > 0.001f) gra.rotate(rotation);
            drawSprite(gra, img, -w * 0.5f, -h * 0.5f, w, h, composite, alpha, p1);
        } catch (Throwable t) {
            drawSpriteFallback(gra, cx - w * 0.5f, cy - h * 0.5f, w, h, alpha);
        } finally {
            if (old != null) {
                try { gra.setTransform(old); } catch (Throwable ignored) {}
                try { gra.delete(old); } catch (Throwable ignored) {}
            }
        }
    }

    private static void resetComposite(FakeGraphics gra) {
        try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private static void drawSpriteFallback(FakeGraphics gra, float x, float y, float w, float h, int alpha) {
        try {
            gra.colRect(x, y + h * 0.45f, w, Math.max(1f, h * 0.10f),
                    255, 245, 210, Math.max(12, Math.min(90, alpha / 3)));
        } catch (Throwable ignored) {}
    }

    private static FakeImage sprite(int type, Color color, int seed) {
        int rgb = color == null ? 0xDCC070 : (color.getRGB() & 0x00FFFFFF);
        int key = type * 0x1f1f1f1f ^ rgb * 31 ^ seed * 131;
        FakeImage cached = SPRITE_CACHE.get(key);
        if (cached != null) return cached;
        synchronized (SPRITE_CACHE) {
            cached = SPRITE_CACHE.get(key);
            if (cached != null) return cached;
            FakeImage img = buildSprite(type, color == null ? new Color(220, 192, 112) : color, seed);
            if (img != null) {
                if (SPRITE_CACHE.size() > 256) SPRITE_CACHE.clear();
                SPRITE_CACHE.put(key, img);
            }
            return img;
        }
    }

    private static FakeImage buildSprite(int type, Color color, int seed) {
        try {
            switch (type) {
                case SPRITE_SHOCKWAVE:
                    return buildShockwaveSprite(color);
                case SPRITE_FLASH:
                    return buildFlashSprite(color);
                case SPRITE_CRACK:
                    return buildCrackSprite(color, seed);
                case SPRITE_SMOKE:
                    return buildSmokeSprite(color, seed);
                case SPRITE_ROCK:
                    return buildRockSprite(color, seed);
                default:
                    return null;
            }
        } catch (Throwable t) {
            Logger.err("Impact Fall sprite bake failed", t);
            return null;
        }
    }

    private static void tickLaunches(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.impactFall.lock) {
            Iterator<LaunchJob> it = rt.impactFall.launches.iterator();
            while (it.hasNext()) {
                LaunchJob job = it.next();
                if (job == null || job.entity == null || job.entity.dead || job.entity.health <= 0L) {
                    it.remove();
                    continue;
                }
                job.age++;
                job.vy += HoldState.GRAVITY;
                job.vx *= HoldState.AIR_DRAG;
                job.screenX += job.vx;
                job.screenY += job.vy;
                if (job.age > 180 || job.screenY >= job.groundY) {
                    job.screenY = job.groundY;
                    applyScreen(job.entity, job.screenX, job.screenY, job.siz, job.stagePos, job.midh, job.origLayer);
                    clearMotionState(job.entity);
                    it.remove();
                    continue;
                }
                int newLayer = (int) (((job.screenY - job.midh) / job.siz + 156f) / 4f);
                if (newLayer > job.origLayer) newLayer = job.origLayer;
                applyScreen(job.entity, job.screenX, job.screenY, job.siz, job.stagePos, job.midh, newLayer);
                clearMotionState(job.entity);
            }
        }
    }

    private static void tickSquashes(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.impactFall.lock) {
            ArrayList<SquashJob> jobs = new ArrayList<SquashJob>(rt.impactFall.squashes.values());
            for (int i = 0; i < jobs.size(); i++) {
                SquashJob job = jobs.get(i);
                if (job == null || job.entity == null) continue;
                if (job.entity.dead || readKbTime(job.entity) == -1) {
                    rt.impactFall.squashes.remove(job.entity);
                    continue;
                }
                clearMotionState(job.entity);
                job.age++;
                if (!job.killed && job.age >= SQUASH_IN_FRAMES + SQUASH_HOLD_FRAMES) {
                    job.killed = true;
                    try {
                        job.entity.kill(Entity.KillMode.NORMAL);
                    } catch (Throwable t) {
                        Logger.err("Impact Fall squash kill failed", t);
                    }
                    rt.impactFall.squashes.remove(job.entity);
                }
            }
        }
    }

    private static void tickVisuals(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.impactFall.lock) {
            Iterator<ImpactVisual> it = rt.impactFall.visuals.iterator();
            while (it.hasNext()) {
                ImpactVisual v = it.next();
                v.age++;
                if (v.done()) it.remove();
            }
        }
    }

    private static List<Target> collectTargets(StageBasis sb, Entity falling,
                                               float impactX, float impactY,
                                               float radiusPx, Transform tr) {
        ArrayList<Target> units = new ArrayList<Target>();
        try {
            for (int i = 0; i < sb.le.size(); i++) {
                Entity e = sb.le.get(i);
                if (e == null || e == falling || e.dead || e.health <= 0L) continue;
                if ((e.touchable() & 1) == 0) continue;
                float sx = gameToScreenX(e.pos, tr.siz, tr.stagePos);
                int layer = safeLayer(e);
                float sy = groundY(layer, tr.siz, tr.midh);
                float dist = dist(impactX, impactY, sx, sy);
                if (dist > radiusPx) continue;
                boolean launchImmune = e.isBase() || EntityAccess.isBoss(e);
                units.add(new Target(e, sx, sy, dist, false, launchImmune));
            }
        } catch (Throwable t) {
            Logger.err("Impact Fall target collection failed", t);
        }
        Collections.sort(units, new Comparator<Target>() {
            @Override
            public int compare(Target a, Target b) {
                return Float.compare(a.distance, b.distance);
            }
        });
        if (units.size() > MAX_TARGETS) {
            units = new ArrayList<Target>(units.subList(0, MAX_TARGETS));
        }
        addBaseTarget(units, sb.ubase, impactX, impactY, radiusPx, tr);
        addBaseTarget(units, sb.ebase, impactX, impactY, radiusPx, tr);
        return units;
    }

    private static void addBaseTarget(List<Target> targets, AbEntity base,
                                      float impactX, float impactY, float radiusPx, Transform tr) {
        if (base == null || base.health <= 0L) return;
        float sx = gameToScreenX(base.pos, tr.siz, tr.stagePos);
        float sy = groundY(0, tr.siz, tr.midh);
        float d = dist(impactX, impactY, sx, sy);
        if (d <= radiusPx) {
            targets.add(new Target(base, sx, sy, d, true, true));
        }
    }

    private static void launch(CrazyRuntime.StageRuntime rt, Entity e, Target target,
                               float impactX, float impactVy, float falloff, Transform tr) {
        if (rt.config.impactFallLaunchScale <= 0.001) return;
        if (isManaged(e) || FallingRegistry.isManaged(e)) return;
        try {
            HoldState hs = HoldState.get();
            if (hs.getHeldEntity() == e) return;
        } catch (Throwable ignored) {}

        float sign = target.screenX >= impactX ? 1f : -1f;
        float scale = (float) rt.config.impactFallLaunchScale;
        float vx = sign * (4f + 0.22f * impactVy) * falloff * scale;
        float vy = -(8f + 0.55f * impactVy) * falloff * scale;
        int layer = safeLayer(e);
        synchronized (rt.impactFall.lock) {
            rt.impactFall.launches.add(new LaunchJob(e, layer, target.screenX, target.screenY,
                    vx, vy, groundY(layer, tr.siz, tr.midh), tr.siz, tr.stagePos, tr.midh));
        }
    }

    private static void damageTarget(StageBasis sb, AbEntity target, int damage, ArrayList<Trait> traits) {
        if (target == null || damage <= 0) return;
        try {
            AttackCanon atk = new AttackCanon(sb.canon, damage,
                    traits == null ? new ArrayList<Trait>() : new ArrayList<Trait>(traits),
                    0, Data.Proc.blank(), target.pos - 1f, target.pos + 1f, 1);
            target.damaged(atk);
        } catch (Throwable t) {
            Logger.err("Impact Fall damage failed", t);
        }
    }

    private static FakeImage buildShockwaveSprite(Color color) {
        int w = 256;
        int h = 88;
        FakeImage img = newImage(w, h);
        if (img == null) return null;
        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float nx = (x - cx) / cx;
                float ny = (y - cy) / cy;
                float d = (float) Math.sqrt(nx * nx + ny * ny);
                float glow = 1f - Math.abs(d - 0.72f) / 0.30f;
                float core = 1f - Math.abs(d - 0.72f) / 0.060f;
                float inner = 1f - Math.abs(d - 0.64f) / 0.040f;
                glow = smooth(clamp01(glow));
                core = smooth(clamp01(core));
                inner = smooth(clamp01(inner));
                float a = clamp01(glow * 0.44f + core * 0.70f + inner * 0.32f);
                if (a <= 0.003f) continue;
                Color c = mix(color, new Color(255, 248, 220), clamp01(core * 0.82f + inner * 0.45f));
                int alpha = Math.round(235f * a * clamp01(1.04f - d * 0.18f));
                img.setRGB(x, y, argb(alpha, c.getRed(), c.getGreen(), c.getBlue()));
            }
        }
        return img;
    }

    private static FakeImage buildFlashSprite(Color color) {
        int w = 192;
        int h = 72;
        FakeImage img = newImage(w, h);
        if (img == null) return null;
        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float nx = (x - cx) / cx;
                float ny = (y - cy) / cy;
                float d = (float) Math.sqrt(nx * nx + ny * ny);
                if (d > 1f) continue;
                float env = smooth(1f - d);
                float core = smooth(clamp01(1f - d / 0.40f));
                Color c = mix(color, new Color(255, 250, 218), 0.45f + core * 0.55f);
                int alpha = Math.round(245f * clamp01(env * 0.72f + core * 0.48f));
                img.setRGB(x, y, argb(alpha, c.getRed(), c.getGreen(), c.getBlue()));
            }
        }
        return img;
    }

    private static FakeImage buildCrackSprite(Color color, int seed) {
        int w = 768;
        int h = 384;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        float cx = (w - 1) * 0.5f;
        float cy = (h - 1) * 0.5f;
        float rx = w * 0.43f;
        float ry = h * 0.35f;
        ArrayList<float[]> segments = new ArrayList<float[]>();

        int count = 7 + Math.abs(seed % 4);
        int clusters = 3 + Math.abs((seed >>> 4) % 3);
        float[] clusterAngles = new float[clusters];
        for (int i = 0; i < clusters; i++) {
            clusterAngles[i] = (float) (Math.PI * 2.0 * rand01(seed + i * 619 + 17));
        }

        for (int i = 0; i < count; i++) {
            int s = seed + i * 1013;
            float angle;
            if (rand01(s + 1) > 0.36f) {
                angle = clusterAngles[Math.abs((s >>> 5) % clusters)]
                        + (rand01(s + 3) - 0.5f) * 0.64f;
            } else {
                angle = (float) (Math.PI * 2.0 * rand01(s + 5));
            }
            float ux = (float) Math.cos(angle);
            float uy = (float) Math.sin(angle);
            float pxAxis = (float) Math.cos(angle + Math.PI / 2.0);
            float pyAxis = (float) Math.sin(angle + Math.PI / 2.0);
            float gx = 0f;
            float gy = 0f;
            float lastRadial = 0f;
            float maxLen = 0.62f + 0.30f * rand01(s + 13);
            int steps = 5 + Math.round(3f * rand01(s + 17));
            for (int k = 0; k < steps; k++) {
                float t0 = k / (float) steps;
                float t1 = (k + 1) / (float) steps;
                float radial = maxLen * t1 * (0.88f + 0.16f * rand01(s + k * 43));
                if (radial <= lastRadial) radial = lastRadial + maxLen / (steps * 1.6f);
                float side = ((k & 1) == 0 ? 1f : -1f) * (rand01(s + k * 31) > 0.5f ? 1f : -1f);
                float jag = side * (0.035f + 0.105f * rand01(s + k * 29)) * t1;
                if (rand01(s + k * 37) > 0.76f) {
                    jag += side * (0.040f + 0.080f * rand01(s + k * 41)) * t1;
                }
                float ngx = ux * radial + pxAxis * jag;
                float ngy = uy * radial + pyAxis * jag;
                float currentAngle = (float) Math.atan2(ngy - gy, ngx - gx);
                float progress = clamp01((float) Math.sqrt(ngx * ngx + ngy * ngy) / Math.max(0.001f, maxLen));
                float x0 = cx + gx * rx;
                float y0 = cy + gy * ry;
                float x1 = cx + ngx * rx;
                float y1 = cy + ngy * ry;
                float width0 = 15.5f * (float) Math.pow(1f - t0, 0.72f) + 1.6f;
                float width1 = 15.5f * (float) Math.pow(1f - progress, 0.72f) + 0.8f;
                segments.add(new float[]{x0, y0, x1, y1, width0, width1});
                if (k >= 2 && rand01(s + k * 47) > 0.50f) {
                    float branchSide = rand01(s + k * 53) > 0.5f ? 1f : -1f;
                    float ba = currentAngle + branchSide * (0.58f + 0.58f * rand01(s + k * 59));
                    float bl = maxLen * (0.10f + 0.17f * rand01(s + k * 61));
                    float bx = ngx + (float) Math.cos(ba) * bl;
                    float by = ngy + (float) Math.sin(ba) * bl;
                    float bt = clamp01((float) Math.sqrt(bx * bx + by * by) / Math.max(0.001f, maxLen));
                    segments.add(new float[]{
                            x1, y1, cx + bx * rx, cy + by * ry,
                            Math.max(2.2f, width1 * 0.56f),
                            Math.max(0.9f, 9.0f * (1f - bt))
                    });
                }
                gx = ngx;
                gy = ngy;
                lastRadial = radial;
            }
        }

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.SrcOver.derive(0.30f));
            drawCrackPolygons(g, segments, 2.80f, 1.00f, color);
            g.setComposite(AlphaComposite.SrcOver.derive(0.96f));
            drawCrackPolygons(g, segments, 1.48f, 1.00f, new Color(18, 12, 10));
            g.setComposite(AlphaComposite.SrcOver);
            drawCrackPolygons(g, segments, 0.78f, 0.82f, new Color(3, 2, 2));
            g.setComposite(AlphaComposite.SrcOver.derive(0.58f));
            drawCrackPolygons(g, segments, 0.28f, 0.62f, color);
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildSmokeSprite(Color color, int seed) {
        int w = 96;
        int h = 72;
        FakeImage img = newImage(w, h);
        if (img == null) return null;
        int lobes = 5 + Math.abs(seed % 5);
        float[] lx = new float[lobes];
        float[] ly = new float[lobes];
        float[] lr = new float[lobes];
        for (int i = 0; i < lobes; i++) {
            int s = seed + i * 151;
            lx[i] = 0.50f + (rand01(s + 1) - 0.5f) * 0.52f;
            ly[i] = 0.54f + (rand01(s + 3) - 0.5f) * 0.42f;
            lr[i] = 0.18f + 0.20f * rand01(s + 5);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float ux = x / (float) (w - 1);
                float uy = y / (float) (h - 1);
                float density = 0f;
                for (int i = 0; i < lobes; i++) {
                    float dx = (ux - lx[i]) / lr[i];
                    float dy = (uy - ly[i]) / (lr[i] * 0.78f);
                    float d2 = dx * dx + dy * dy;
                    density += (float) Math.exp(-d2 * 1.65f);
                }
                density = clamp01((density - 0.20f) / 1.45f);
                if (density <= 0.005f) continue;
                float noise = 0.78f + 0.24f * rand01(seed + x * 17 + y * 31);
                density = clamp01(density * noise);
                Color base = mix(new Color(86, 78, 66), color, 0.16f);
                Color hi = new Color(150, 138, 112);
                Color c = mix(base, hi, clamp01((0.70f - uy) * 0.55f));
                int alpha = Math.round(190f * smooth(density));
                img.setRGB(x, y, argb(alpha, c.getRed(), c.getGreen(), c.getBlue()));
            }
        }
        return img;
    }

    private static FakeImage buildRockSprite(Color color, int seed) {
        int n = 48;
        FakeImage img = newImage(n, n);
        if (img == null) return null;
        int points = 4 + Math.abs(seed % 3);
        float[] xs = new float[points];
        float[] ys = new float[points];
        for (int i = 0; i < points; i++) {
            int s = seed + i * 83;
            float a = (float) (Math.PI * 2.0 * i / points + (rand01(s + 1) - 0.5f) * 0.42f);
            float rr = 13f + 8f * rand01(s + 3);
            xs[i] = 24f + (float) Math.cos(a) * rr;
            ys[i] = 24f + (float) Math.sin(a) * rr * (0.72f + 0.35f * rand01(s + 5));
        }
        Color shadow = new Color(24, 20, 18);
        Color body = mix(new Color(92, 82, 68), color, 0.18f);
        Color hi = new Color(174, 158, 120);
        fillPolygon(img, xs, ys, 3f, 4f, shadow, 135);
        fillPolygon(img, xs, ys, 0f, 0f, body, 235);
        drawSoftLine(img, 18f, 17f, 29f, 14f, 2f, hi.getRed(), hi.getGreen(), hi.getBlue(), 170);
        return img;
    }

    private static void drawCrackPolygons(Graphics2D g, List<float[]> segments,
                                          float widthScale, float alphaScale, Color color) {
        g.setColor(color);
        for (int i = 0; i < segments.size(); i++) {
            float[] s = segments.get(i);
            Path2D path = taperedSegment(s[0], s[1], s[2], s[3],
                    Math.max(0.4f, s[4] * widthScale * alphaScale * CRACK_WIDTH_SCALE),
                    Math.max(0.25f, s[5] * widthScale * alphaScale * CRACK_WIDTH_SCALE));
            if (path != null) g.fill(path);
        }
    }

    private static Path2D taperedSegment(float x0, float y0, float x1, float y1, float w0, float w1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return null;
        float nx = -dy / len;
        float ny = dx / len;
        float h0 = w0 * 0.5f;
        float h1 = w1 * 0.5f;
        Path2D path = new Path2D.Float();
        path.moveTo(x0 + nx * h0, y0 + ny * h0);
        path.lineTo(x1 + nx * h1, y1 + ny * h1);
        path.lineTo(x1 - nx * h1, y1 - ny * h1);
        path.lineTo(x0 - nx * h0, y0 - ny * h0);
        path.closePath();
        return path;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FakeImage buildFakeImage(BufferedImage image) {
        try {
            if (ImageBuilder.builder == null || image == null) return null;
            return ((ImageBuilder) ImageBuilder.builder).build(image);
        } catch (Throwable t) {
            Logger.err("Impact Fall BufferedImage conversion failed", t);
            return null;
        }
    }

    private static FakeImage newImage(int w, int h) {
        try {
            if (ImageBuilder.builder == null) return null;
            FakeImage img = ImageBuilder.builder.build(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, 0);
                }
            }
            return img;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void fillPolygon(FakeImage img, float[] xs, float[] ys, float ox, float oy,
                                    Color color, int alpha) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (pointInPolygon(x + 0.5f - ox, y + 0.5f - oy, xs, ys)) {
                    blendPixel(img, x, y, color.getRed(), color.getGreen(), color.getBlue(), alpha);
                }
            }
        }
    }

    private static boolean pointInPolygon(float x, float y, float[] xs, float[] ys) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > y) != (ys[j] > y)
                    && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i] + 0.0001f) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void drawSoftLine(FakeImage img, float x0, float y0, float x1, float y1,
                                     float width, int red, int green, int blue, int alpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        int steps = Math.max(1, Math.round((float) Math.sqrt(dx * dx + dy * dy) * 1.5f));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float x = x0 + dx * t;
            float y = y0 + dy * t;
            stampSoftCircle(img, x, y, width, red, green, blue, alpha);
        }
    }

    private static void stampSoftCircle(FakeImage img, float cx, float cy, float radius,
                                        int red, int green, int blue, int alpha) {
        int minX = Math.max(0, (int) Math.floor(cx - radius));
        int maxX = Math.min(img.getWidth() - 1, (int) Math.ceil(cx + radius));
        int minY = Math.max(0, (int) Math.floor(cy - radius));
        int maxY = Math.min(img.getHeight() - 1, (int) Math.ceil(cy + radius));
        float inv = 1f / Math.max(0.001f, radius);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float dx = (x + 0.5f - cx) * inv;
                float dy = (y + 0.5f - cy) * inv;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 1f) continue;
                int a = Math.round(alpha * smooth(1f - d));
                blendPixel(img, x, y, red, green, blue, a);
            }
        }
    }

    private static void blendPixel(FakeImage img, int x, int y, int red, int green, int blue, int alpha) {
        alpha = clamp255(alpha);
        if (alpha <= 0) return;
        int dst = img.getRGB(x, y);
        int da = (dst >>> 24) & 0xFF;
        int dr = (dst >>> 16) & 0xFF;
        int dg = (dst >>> 8) & 0xFF;
        int db = dst & 0xFF;
        int outA = alpha + da * (255 - alpha) / 255;
        if (outA <= 0) return;
        int outR = (red * alpha + dr * da * (255 - alpha) / 255) / outA;
        int outG = (green * alpha + dg * da * (255 - alpha) / 255) / outA;
        int outB = (blue * alpha + db * da * (255 - alpha) / 255) / outA;
        img.setRGB(x, y, argb(outA, outR, outG, outB));
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (clamp255(alpha) << 24)
                | (clamp255(red) << 16)
                | (clamp255(green) << 8)
                | clamp255(blue);
    }

    private static Color mix(Color a, Color b, float t) {
        float p = clamp01(t);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * p),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * p),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * p));
    }

    private static void drawUnder2D(Graphics2D g, ImpactVisual v, float x, float y, float r) {
        java.awt.Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float crackAlpha = crackAlpha(v);
            if (crackAlpha > 0f) {
                drawCracks2D(g, v, x, y, r, crackAlpha);
            }

            if (v.age < RING_FRAMES) {
                float p = v.age / (float) RING_FRAMES;
                float ringR = r * (0.22f + 0.92f * easeOut(p));
                float alpha = (1f - p) * 0.98f;
                g.setComposite(AlphaComposite.SrcOver.derive(0.18f * alpha));
                g.setColor(v.color);
                g.fillOval(Math.round(x - ringR), Math.round(y - ringR * 0.22f),
                        Math.round(ringR * 2f), Math.round(ringR * 0.44f));
                g.setComposite(AlphaComposite.SrcOver.derive(alpha));
                g.setStroke(new BasicStroke(Math.max(5f, r * 0.045f * (1f - p * 0.35f)),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(v.color);
                g.drawOval(Math.round(x - ringR), Math.round(y - ringR * 0.22f),
                        Math.round(ringR * 2f), Math.round(ringR * 0.44f));
                g.setComposite(AlphaComposite.SrcOver.derive(0.78f * alpha));
                g.setStroke(new BasicStroke(Math.max(2f, r * 0.018f * (1f - p * 0.25f)),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(255, 248, 218));
                g.drawOval(Math.round(x - ringR * 0.96f), Math.round(y - ringR * 0.20f),
                        Math.round(ringR * 1.92f), Math.round(ringR * 0.40f));
            }

            if (v.age < DUST_FRAMES) {
                float p = v.age / (float) DUST_FRAMES;
                float alpha = (1f - p) * 0.88f;
                g.setComposite(AlphaComposite.SrcOver.derive(alpha));
                int count = particleCount(v.seed);
                for (int i = 0; i < count; i++) {
                    float rr = r * (0.10f + 0.70f * rand01(v.seed + i * 43));
                    float a = (float) (Math.PI * 2.0 * rand01(v.seed + i * 59));
                    float lift = r * 0.28f * (float) Math.sin(Math.PI * p) * rand01(v.seed + i * 7);
                    float px = x + (float) Math.cos(a) * rr * easeOut(p);
                    float py = y + (float) Math.sin(a) * rr * 0.18f * easeOut(p) - lift;
                    float size = Math.max(5f, r * (0.035f + 0.070f * rand01(v.seed + i * 11)) * (1f - p * 0.45f));
                    g.setColor(new Color(28, 24, 20, 160));
                    g.fillOval(Math.round(px - size * 0.75f), Math.round(py - size * 0.35f),
                            Math.round(size * 1.6f), Math.round(size * 0.9f));
                    g.setColor(i % 5 == 0 ? v.color : new Color(96, 86, 70));
                    g.fillOval(Math.round(px - size), Math.round(py - size), Math.round(size * 2f), Math.round(size * 2f));
                    g.setColor(new Color(170, 154, 118));
                    g.fillOval(Math.round(px - size * 0.35f), Math.round(py - size * 0.45f),
                            Math.max(1, Math.round(size * 0.55f)), Math.max(1, Math.round(size * 0.45f)));
                }
            }
        } finally {
            g.setComposite(oldComposite);
            g.setStroke(oldStroke);
            g.setColor(oldColor);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    private static void drawCracks2D(Graphics2D g, ImpactVisual v, float x, float y, float r, float crackAlpha) {
        int count = 12;
        ArrayList<Path2D> paths = new ArrayList<Path2D>();
        for (int i = 0; i < count; i++) {
            float a = (float) ((Math.PI * 2.0 * i / count) + (v.seed % 23) * 0.041);
            float len = r * (0.34f + 0.44f * rand01(v.seed + i * 31));
            float start = r * (0.04f + 0.05f * rand01(v.seed + i * 17));
            Path2D path = new Path2D.Float();
            for (int s = 0; s <= 4; s++) {
                float t = s / 4f;
                float jitter = (rand01(v.seed + i * 97 + s * 13) - 0.5f) * r * 0.14f * t;
                float px = x + (float) Math.cos(a) * (start + len * t)
                        + (float) Math.cos(a + Math.PI / 2.0) * jitter;
                float py = y + (float) Math.sin(a) * (start + len * t) * 0.25f
                        + (float) Math.sin(a + Math.PI / 2.0) * jitter * 0.20f;
                if (s == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            paths.add(path);
        }

        g.setComposite(AlphaComposite.SrcOver.derive(0.92f * crackAlpha));
        g.setStroke(new BasicStroke(Math.max(5f, r * 0.038f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(20, 14, 12));
        for (int i = 0; i < paths.size(); i++) g.draw(paths.get(i));

        g.setComposite(AlphaComposite.SrcOver.derive(0.58f * crackAlpha));
        g.setStroke(new BasicStroke(Math.max(2f, r * 0.014f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(v.color);
        for (int i = 0; i < paths.size(); i++) g.draw(paths.get(i));
    }

    private static void drawOverlay2D(Graphics2D g, ImpactVisual v, float x, float y, float r, float alpha) {
        java.awt.Composite oldComposite = g.getComposite();
        Color oldColor = g.getColor();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(0.30f * alpha));
            g.setColor(new Color(255, 248, 220));
            g.fillOval(Math.round(x - r * 0.42f), Math.round(y - r * 0.16f),
                    Math.round(r * 0.84f), Math.round(r * 0.32f));
            g.setComposite(AlphaComposite.SrcOver.derive(0.25f * alpha));
            g.setColor(v.color);
            g.fillOval(Math.round(x - r * 0.64f), Math.round(y - r * 0.24f),
                    Math.round(r * 1.28f), Math.round(r * 0.48f));
        } finally {
            g.setComposite(oldComposite);
            g.setColor(oldColor);
        }
    }

    private static void drawUnderFallback(FakeGraphics gra, ImpactVisual v, int x, int y, int r) {
        drawCracksFallback(gra, v, x, y, r);
        if (v.age < RING_FRAMES) {
            float p = v.age / (float) RING_FRAMES;
            int rr = Math.round(r * (0.22f + 0.92f * easeOut(p)));
            int a = Math.round(230f * (1f - p));
            drawFallbackEllipseOutline(gra, x, y, rr, Math.max(8, Math.round(rr * 0.22f)),
                    Math.max(4, Math.round(r * 0.030f)), v.color.getRed(), v.color.getGreen(), v.color.getBlue(), a);
            drawFallbackEllipseOutline(gra, x, y, Math.round(rr * 0.96f), Math.max(6, Math.round(rr * 0.19f)),
                    Math.max(2, Math.round(r * 0.014f)), 255, 248, 218, Math.round(170f * (1f - p)));
        }
        if (v.age < DUST_FRAMES) {
            drawDustFallback(gra, v, x, y, r);
        }
    }

    private static void drawCracksFallback(FakeGraphics gra, ImpactVisual v, int x, int y, int r) {
        int crackA = Math.round(225f * crackAlpha(v));
        if (crackA <= 0) return;
        int count = 12;
        for (int i = 0; i < count; i++) {
            float a = (float) ((Math.PI * 2.0 * i / count) + (v.seed % 23) * 0.041);
            float len = r * (0.34f + 0.44f * rand01(v.seed + i * 31));
            float start = r * (0.04f + 0.05f * rand01(v.seed + i * 17));
            int px = Math.round(x + (float) Math.cos(a) * start);
            int py = Math.round(y + (float) Math.sin(a) * start * 0.25f);
            for (int s = 1; s <= 4; s++) {
                float t = s / 4f;
                float jitter = (rand01(v.seed + i * 97 + s * 13) - 0.5f) * r * 0.14f * t;
                int nx = Math.round(x + (float) Math.cos(a) * (start + len * t)
                        + (float) Math.cos(a + Math.PI / 2.0) * jitter);
                int ny = Math.round(y + (float) Math.sin(a) * (start + len * t) * 0.25f
                        + (float) Math.sin(a + Math.PI / 2.0) * jitter * 0.20f);
                drawFallbackSegment(gra, px, py, nx, ny, Math.max(4, Math.round(r * 0.030f)),
                        20, 14, 12, crackA);
                drawFallbackSegment(gra, px, py, nx, ny, Math.max(2, Math.round(r * 0.010f)),
                        v.color.getRed(), v.color.getGreen(), v.color.getBlue(), Math.round(crackA * 0.52f));
                px = nx;
                py = ny;
            }
        }
    }

    private static void drawDustFallback(FakeGraphics gra, ImpactVisual v, int x, int y, int r) {
        float p = v.age / (float) DUST_FRAMES;
        int alpha = Math.round(220f * (1f - p));
        int count = particleCount(v.seed);
        for (int i = 0; i < count; i++) {
            float rr = r * (0.10f + 0.70f * rand01(v.seed + i * 43));
            float a = (float) (Math.PI * 2.0 * rand01(v.seed + i * 59));
            float lift = r * 0.28f * (float) Math.sin(Math.PI * p) * rand01(v.seed + i * 7);
            int px = Math.round(x + (float) Math.cos(a) * rr * easeOut(p));
            int py = Math.round(y + (float) Math.sin(a) * rr * 0.18f * easeOut(p) - lift);
            int size = Math.max(5, Math.round(r * (0.035f + 0.070f * rand01(v.seed + i * 11)) * (1f - p * 0.45f)));
            drawFallbackDisc(gra, px + size / 4, py + size / 3, Math.max(2, size * 3 / 4),
                    28, 24, 20, Math.round(alpha * 0.55f));
            if (i % 5 == 0) {
                drawFallbackDisc(gra, px, py, size, v.color.getRed(), v.color.getGreen(), v.color.getBlue(), alpha);
            } else {
                drawFallbackDisc(gra, px, py, size, 96, 86, 70, alpha);
            }
            drawFallbackDisc(gra, px - size / 4, py - size / 3, Math.max(1, size / 3),
                    170, 154, 118, Math.round(alpha * 0.75f));
        }
    }

    private static void drawFallbackEllipseFill(FakeGraphics gra, int cx, int cy, int rx, int ry,
                                                int red, int green, int blue, int alpha) {
        if (rx <= 0 || ry <= 0 || alpha <= 0) return;
        for (int dy = -ry; dy <= ry; dy += 2) {
            float yy = dy / (float) ry;
            int span = Math.round(rx * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
            gra.colRect(cx - span, cy + dy, span * 2 + 1, 2,
                    red, green, blue, clamp255(alpha));
        }
    }

    private static void drawFallbackEllipseOutline(FakeGraphics gra, int cx, int cy, int rx, int ry, int thickness,
                                                   int red, int green, int blue, int alpha) {
        if (rx <= 0 || ry <= 0 || alpha <= 0) return;
        int step = Math.max(4, Math.min(10, 540 / Math.max(40, rx)));
        int size = Math.max(2, thickness);
        for (int deg = 0; deg < 360; deg += step) {
            double a = Math.toRadians(deg);
            int x = Math.round(cx + (float) Math.cos(a) * rx);
            int y = Math.round(cy + (float) Math.sin(a) * ry);
            gra.colRect(x - size / 2, y - size / 2, size, size,
                    red, green, blue, clamp255(alpha));
        }
    }

    private static void drawFallbackDisc(FakeGraphics gra, int cx, int cy, int radius,
                                         int red, int green, int blue, int alpha) {
        if (radius <= 0 || alpha <= 0) return;
        for (int dy = -radius; dy <= radius; dy += 2) {
            float yy = dy / (float) radius;
            int span = Math.round(radius * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
            gra.colRect(cx - span, cy + dy, span * 2 + 1, 2,
                    red, green, blue, clamp255(alpha));
        }
    }

    private static void drawFallbackSegment(FakeGraphics gra, int x0, int y0, int x1, int y1, int thickness,
                                            int red, int green, int blue, int alpha) {
        if (alpha <= 0) return;
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            gra.colRect(x0 - thickness / 2, y0 - thickness / 2, thickness, thickness,
                    red, green, blue, clamp255(alpha));
            return;
        }
        int stride = Math.max(1, thickness / 2);
        for (int s = 0; s <= steps; s += stride) {
            float t = s / (float) steps;
            int x = Math.round(x0 + dx * t);
            int y = Math.round(y0 + dy * t);
            gra.colRect(x - thickness / 2, y - thickness / 2, thickness, thickness,
                    red, green, blue, clamp255(alpha));
        }
    }

    private static float crackAlpha(ImpactVisual v) {
        if (v.age <= v.crackHoldFrames) return 1f;
        return clamp01(1f - (v.age - v.crackHoldFrames) / (float) v.crackFadeFrames);
    }

    private static void applyCameraShake(StageBasis sb, float impactVy, float spriteRadiusPx) {
        try {
            float sizeFactor = clamp(spriteRadiusPx / 90f, 0.45f, 2.20f);
            float speedFactor = clamp(impactVy / 45f, 0.45f, 1.35f);
            int amp = clampInt(Math.round((5f + impactVy * 0.28f) * sizeFactor * speedFactor), 3, 34);
            int frames = clampInt(Math.round(7f + sizeFactor * 2.5f + impactVy * 0.06f), 8, 18);
            sb.shake = new int[]{frames, Math.max(1, amp / 4), amp};
            sb.shakeDuration = frames;
        } catch (Throwable ignored) {}
    }

    private static ArrayList<Trait> traitsFor(Entity entity) {
        ArrayList<Trait> out = new ArrayList<Trait>();
        try {
            Object data = BCUFields.get(entity, "data");
            Object traits = BCUFields.invoke(data, "getTraits");
            if (traits instanceof Iterable) {
                for (Object t : (Iterable<?>) traits) {
                    if (t instanceof Trait) out.add((Trait) t);
                }
            }
        } catch (Throwable ignored) {}
        if (out.isEmpty()) {
            try { out.add((Trait) UserProfile.getBCData().traits.get(16)); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static Color colorFor(Entity entity) {
        ArrayList<Trait> traits = traitsFor(entity);
        try {
            if (containsTrait(traits, 3)) return new Color(176, 196, 210);
            if (containsTrait(traits, 6)) return new Color(118, 208, 94);
            if (containsTrait(traits, 10)) return new Color(88, 220, 255);
            if (containsTrait(traits, 11)) return new Color(255, 232, 118);
            if (containsTrait(traits, 12)) return new Color(178, 88, 255);
            if (containsTrait(traits, 13)) return new Color(255, 128, 72);
            if (containsTrait(traits, 0)) return new Color(255, 78, 62);
        } catch (Throwable ignored) {}
        try {
            return entity.dire == -1 ? new Color(84, 206, 255) : new Color(255, 96, 82);
        } catch (Throwable ignored) {
            return new Color(220, 185, 95);
        }
    }

    private static boolean containsTrait(List<Trait> traits, int index) {
        try {
            Object t = UserProfile.getBCData().traits.get(index);
            return traits != null && traits.contains(t);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyScreen(Entity e, float sx, float sy, float siz,
                                    int stagePos, int midh, int layer) {
        float gameX = screenToGameX(sx, siz, stagePos);
        e.pos = gameX;
        e.lastPosition = gameX;
        try { EntityAccess.setLayer(e, layer); } catch (Throwable ignored) {}
    }

    private static void clearMotionState(Entity e) {
        if (e == null) return;
        try { BCUFields.field(e.getClass(), "kbTime").setInt(e, 0); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "walking").setBoolean(e, false); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "lastPosition").setFloat(e, e.pos); } catch (Throwable ignored) {}
    }

    private static int readKbTime(Entity e) {
        try {
            return BCUFields.getInt(e, "kbTime");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static CrazyRuntime.StageRuntime runtimeFor(Object pageOrPainter, Object entity) {
        if (pageOrPainter != null) {
            try {
                CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(pageOrPainter);
                if (rt != null) return rt;
            } catch (Throwable ignored) {}
            try {
                Object stage = BBPainterAccess.getStageBasis(pageOrPainter);
                CrazyRuntime.StageRuntime rt = CrazyRuntime.get(stage);
                if (rt != null) return rt;
            } catch (Throwable ignored) {}
        }
        if (entity instanceof Entity) {
            return CrazyRuntime.get(((Entity) entity).basis);
        }
        if (entity != null) {
            try {
                Object stage = BCUFields.get(entity, "basis");
                return CrazyRuntime.get(stage);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Transform transformFor(Object pageOrPainter, StageBasis sb) {
        if (pageOrPainter != null) {
            try {
                float siz = BBPainterAccess.getSiz(pageOrPainter);
                int stagePos = BBPainterAccess.getStagePos(pageOrPainter);
                int midh = BBPainterAccess.getMidh(pageOrPainter);
                return new Transform(siz, stagePos, midh);
            } catch (Throwable ignored) {}
            try {
                Object bb = BCUFields.get(pageOrPainter, "bb");
                Object bbp = BCUFields.get(bb, "bbp");
                Object bf = BCUFields.get(bbp, "bf");
                Object bsb = BCUFields.get(bf, "sb");
                float siz = BCUFields.getFloat(bsb, "siz");
                int stagePos = BCUFields.getInt(bsb, "pos");
                int midh = BCUFields.getInt(bbp, "midh");
                return new Transform(siz, stagePos, midh);
            } catch (Throwable ignored) {}
        }
        try {
            return new Transform(sb.siz, sb.pos, Math.round(156f * Math.max(0.5f, sb.siz) + 720f));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class Transform {
        final float siz;
        final int stagePos;
        final int midh;

        Transform(float siz, int stagePos, int midh) {
            this.siz = siz;
            this.stagePos = stagePos;
            this.midh = midh;
        }
    }

    private static float gameToScreenX(float pos, float siz, int stagePos) {
        return (pos * 0.32f + 200f) * siz + stagePos;
    }

    private static float screenToGameX(float screenX, float siz, int stagePos) {
        return ((screenX - stagePos) / siz - 200f) / 0.32f;
    }

    private static float groundY(int layer, float siz, int midh) {
        return midh - (156f - layer * 4f) * siz;
    }

    private static int safeLayer(Object entity) {
        try {
            return EntityAccess.getLayer(entity);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static int secondsToFrames(double seconds) {
        return Math.max(0, (int) Math.round(seconds * TICKS_PER_SECOND));
    }

    private static int clampDamage(long value) {
        if (value < 1L) return 1;
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) value;
    }

    private static int particleCount(int seed) {
        return 8 + Math.abs(seed % 5);
    }

    private static int clamp255(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOut(float v) {
        float t = clamp01(v);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static float smooth(float v) {
        float t = clamp01(v);
        return t * t * (3f - 2f * t);
    }

    private static float rand01(int seed) {
        int x = seed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        return (x & 0x7fffffff) / (float) 0x7fffffff;
    }

    private static int stableSeed(Object entity, int salt) {
        int h = System.identityHashCode(entity) ^ salt * 0x45d9f3b;
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 16;
        return h;
    }
}
