package manualcontrol.crazy.unit;

import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.attack.AttackCanon;
import common.battle.attack.ContAb;
import common.battle.entity.AbEntity;
import common.battle.entity.Cannon;
import common.battle.entity.Entity;
import common.CommonStatic;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.P;
import common.pack.UserProfile;
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
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class BossItemFeature {

    private static final int RETURN_FRAMES = 18;
    private static final int MIN_CINEMATIC_FRAMES = 90;
    private static final int MAX_CINEMATIC_FRAMES = 150;
    private static final int CAMERA_EASE_FRAMES = 18;
    private static final int CHAIN_INTRO_FRAMES = 18;
    private static final int IMPACT_VISIBLE_FRAMES = 14;
    private static final int BOSS_PULSE_FRAMES = 10;
    private static final int SCALE_STEP_FRAMES = 11;
    private static final int FINISH_BUFFER_FRAMES = 16;
    private static final int BASE_BOSS_HP = 1000000;
    private static final long BASE_BOSS_SHIELD = 3000000L;
    private static final long BASE_BOSS_SHIELD_PER_ABSORB = 250000L;
    private static final int BASE_BOSS_FINAL_STAND_HP = 500000;
    private static final int BASE_BOSS_NATIVE_GAP = 4;
    private static final int BOSS_NATIVE_CANNON_ANIMATION_FPS = 60;
    private static final int[] BASE_BOSS_NATIVE_CANNONS = new int[] {0, 1, 3, 4, 5, 6, 7};
    private static final short[] BASE_CANNON_Y = new short[] {-134, -134, -134, -250, -250, -134, -134, -134};
    private static final byte[] BASE_CANNON_X = new byte[] {0, 0, 0, 64, 64, 0, 0, 0};
    private static final float SCALE_PER_ABSORBED = 0.15f;
    private static final long SPRITE_BOX_TTL_MS = 180L;
    private static final int ALPHA_THRESHOLD = 16;
    private static final long NO_POINT = Long.MIN_VALUE;
    private static volatile Field transformDataField;
    private static final WeakHashMap<Object, SpriteBox> spriteBoxes = new WeakHashMap<Object, SpriteBox>();
    private static final WeakHashMap<FakeImage, AlphaBounds> alphaBoundsCache = new WeakHashMap<FakeImage, AlphaBounds>();
    private static final Map<String, Long> playerBaseMuzzleCache =
            Collections.synchronizedMap(new java.util.HashMap<String, Long>());
    private static final Set<Cannon> nativeBossCannons =
            Collections.newSetFromMap(new WeakHashMap<Cannon, Boolean>());
    private static final Set<Integer> nativeCannonSeen =
            Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private BossItemFeature() {}

    public static final class State {
        public boolean available = true;
        public boolean used;
        public boolean playerSpawnLocked;

        public boolean dragging;
        public int dragX;
        public int dragY;

        public boolean returning;
        public int returnFrame;
        public int returnFromX;
        public int returnFromY;

        public boolean cinematicActive;
        public AbEntity target;
        public int targetDire;
        public boolean baseCinematic;
        public int frame;
        public int totalFrames;
        public long absorbedMaxH;
        public long absorbedHealth;
        public long absorbedDamage;
        public long targetDamage;
        public int absorbedCount;
        public int lastImpactFrame = -10000;
        public int impactCount;

        public boolean cameraCaptured;
        public int cameraOriginalPos;

        public final List<Absorb> absorbs = new ArrayList<Absorb>();
        public final WeakHashMap<Object, BossStats> bosses = new WeakHashMap<Object, BossStats>();
        public final WeakHashMap<Object, BossBaseStats> baseBosses = new WeakHashMap<Object, BossBaseStats>();
        public final List<BaseBeam> baseBeams = new ArrayList<BaseBeam>();
        public final List<BossNativeCannonShot> nativeCannonShots = new ArrayList<BossNativeCannonShot>();
        public final Set<Object> rangeScaled = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
        public final Set<Object> damageScaled = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
        public final Set<Object> nativeBaseHit = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    }

    public static final class BossStats {
        public final float scale;
        public final double damageMultiplier;
        public final int absorbedCount;

        BossStats(float scale, double damageMultiplier, int absorbedCount) {
            this.scale = scale;
            this.damageMultiplier = damageMultiplier;
            this.absorbedCount = absorbedCount;
        }
    }

    private static final class BossBaseStats {
        final AbEntity base;
        final int dire;
        final float scale;
        final int absorbedCount;
        final int seed;
        final long maxShield;
        final int growthFrames;
        long shield;
        int frame;
        int nextBeamFrame;
        int firedCount;
        int nativeCycle;
        int finalBurstShots;
        boolean finalStandUsed;
        int lastPulseFrame = -10000;

        BossBaseStats(AbEntity base, int dire, float scale, int absorbedCount, long shield) {
            this.base = base;
            this.dire = dire;
            this.scale = scale;
            this.absorbedCount = absorbedCount;
            this.shield = Math.max(0L, shield);
            this.maxShield = this.shield;
            this.seed = stableSeed(base, absorbedCount + dire * 31);
            this.growthFrames = 45;
            this.nextBeamFrame = this.growthFrames + 12;
        }
    }

    private static final class BaseBeam {
        final AbEntity source;
        final AbEntity target;
        final float startPos;
        final float targetPos;
        final int type;
        final int seed;
        final boolean judgement;
        final int damage;
        final int warmup;
        final int duration;
        final int layer;
        int age;
        int lastDamageAge = -10000;

        BaseBeam(AbEntity source, AbEntity target, int type, boolean judgement,
                 int damage, int seed, int layer) {
            this.source = source;
            this.target = target;
            this.startPos = source == null ? 0f : source.pos;
            this.targetPos = target == null ? this.startPos : target.pos;
            this.type = type;
            this.judgement = judgement;
            this.damage = Math.max(1, damage);
            this.seed = seed;
            this.warmup = judgement ? 12 : 9;
            this.duration = judgement ? 58 : 42;
            this.layer = layer;
        }

        boolean activeDamage() {
            return age >= warmup && age < duration - 7;
        }

        boolean done() {
            return age >= duration;
        }
    }

    private static final class BossNativeCannonShot {
        final BossBaseStats stats;
        final Cannon cannon;
        final int cannonId;
        final int maxAge;
        final AbEntity aimTarget;
        final float aimPos;
        final List<ContAb> nativeEffects = new ArrayList<ContAb>();
        int age;
        int idleAge;
        int renderedFrames;
        int nativeEffectsCreated;
        boolean loggedRender;
        boolean loggedRetarget;
        boolean loggedAttackAnim;
        boolean loggedNativeEffects;

        BossNativeCannonShot(StageBasis sb, BossBaseStats stats, int cannonId,
                             AbEntity aimTarget, float aimPos) {
            this.stats = stats;
            this.cannonId = cannonId;
            this.aimTarget = aimTarget;
            this.aimPos = aimPos;
            this.cannon = new Cannon(sb, cannonId, sb.canon.deco, sb.canon.base);
            this.maxAge = cannonId == 0 || cannonId == 1 || cannonId == 5 || cannonId == 7 ? 150 : 110;
        }

        boolean done() {
            return age >= maxAge || age > 8 && idleAge >= 2 && renderedFrames > 0;
        }
    }

    private static final class Absorb {
        final Entity entity;
        final float startPos;
        final int startLayer;
        final int chainStartFrame;
        final int pinEndFrame;
        final int holdEndFrame;
        final int snapEndFrame;
        final int yankStartFrame;
        final int startFrame;
        final int impactFrame;
        final int seed;
        final float distanceFromBoss;
        final long maxH;
        final long health;
        final long damage;
        boolean removed;

        Absorb(Entity entity, int chainStartFrame, int pinEndFrame, int holdEndFrame,
               int snapEndFrame, int yankStartFrame,
               int impactFrame, float bossPos, int order) {
            this.entity = entity;
            this.startPos = entity.pos;
            this.startLayer = EntityAccess.getLayer(entity);
            this.chainStartFrame = Math.max(0, chainStartFrame);
            this.pinEndFrame = Math.max(this.chainStartFrame + 1, pinEndFrame);
            this.holdEndFrame = Math.max(this.pinEndFrame + 1, holdEndFrame);
            this.snapEndFrame = Math.max(this.holdEndFrame + 1, snapEndFrame);
            this.yankStartFrame = Math.max(this.snapEndFrame, yankStartFrame);
            this.startFrame = this.chainStartFrame;
            this.impactFrame = Math.max(this.yankStartFrame + 1, impactFrame);
            this.seed = stableSeed(entity, order);
            this.distanceFromBoss = Math.abs(entity.pos - bossPos);
            this.maxH = Math.max(0L, entity.maxH);
            this.health = Math.max(0L, entity.health);
            this.damage = totalDamage(entity);
        }
    }

    private static final class SpriteBox {
        float minX;
        float minY;
        float maxX;
        float maxY;
        float bodyCX;
        float bodyCY;
        long timeMs;
        final List<SpriteAlphaPart> parts = new ArrayList<SpriteAlphaPart>();

        void set(float minX, float minY, float maxX, float maxY, float bodyCX, float bodyCY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.bodyCX = bodyCX;
            this.bodyCY = bodyCY;
            this.timeMs = System.currentTimeMillis();
        }

        boolean fresh() {
            if (System.currentTimeMillis() - timeMs > SPRITE_BOX_TTL_MS) return false;
            float w = maxX - minX;
            float h = maxY - minY;
            return w > 2f && h > 2f && w < 6000f && h < 6000f;
        }
    }

    private static final class AlphaBounds {
        final int minX;
        final int minY;
        final int maxX;
        final int maxY;
        final boolean valid;

        AlphaBounds(int minX, int minY, int maxX, int maxY, boolean valid) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.valid = valid;
        }
    }

    private static final class BaseVisualBounds {
        float left;
        float top;
        float right;
        float bottom;
        boolean valid;

        void include(FakeImage img, float x, float y, float siz) {
            AlphaBounds ab = alphaBounds(img);
            if (ab == null || !ab.valid) return;
            float l = x + ab.minX * siz;
            float t = y + ab.minY * siz;
            float r = x + (ab.maxX + 1) * siz;
            float b = y + (ab.maxY + 1) * siz;
            if (!valid) {
                left = l; top = t; right = r; bottom = b; valid = true;
            } else {
                if (l < left) left = l;
                if (t < top) top = t;
                if (r > right) right = r;
                if (b > bottom) bottom = b;
            }
        }

        float centerX() { return (left + right) * 0.5f; }
        float centerY() { return (top + bottom) * 0.5f; }
    }

    private static final class AuraCircle {
        final int x;
        final int y;
        final int r;

        AuraCircle(int x, int y, int r) {
            this.x = x;
            this.y = y;
            this.r = r;
        }
    }

    private static final class BaseBossGeometry {
        final AbEntity base;
        final float siz;
        final float psiz;
        final float rootX;
        final float rootY;
        final float scale;
        final float pivotX;
        final float pivotY;
        final BaseVisualBounds visibleBounds;
        final BaseVisualBounds scaledBounds;
        final long muzzlePoint;
        final AuraCircle shield;
        final long[] nativeCannonOrigins = new long[BASE_CANNON_Y.length];

        BaseBossGeometry(AbEntity base, float siz, float psiz, float rootX, float rootY,
                         float scale, float pivotX, float pivotY, BaseVisualBounds visibleBounds,
                         BaseVisualBounds scaledBounds, long muzzlePoint, AuraCircle shield) {
            this.base = base;
            this.siz = siz;
            this.psiz = psiz;
            this.rootX = rootX;
            this.rootY = rootY;
            this.scale = scale;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.visibleBounds = visibleBounds;
            this.scaledBounds = scaledBounds;
            this.muzzlePoint = muzzlePoint;
            this.shield = shield;
        }

        long nativeCannonOrigin(int id) {
            if (id < 0 || id >= nativeCannonOrigins.length) return muzzlePoint;
            long p = nativeCannonOrigins[id];
            return p == 0L ? muzzlePoint : p;
        }
    }

    private static final class MuzzleCandidate {
        int x;
        int y;
        int score = Integer.MIN_VALUE;
        boolean found;

        void offer(int x, int y, int score) {
            if (!found || score > this.score) {
                this.found = true;
                this.x = x;
                this.y = y;
                this.score = score;
            }
        }
    }

    private static final class SpriteAlphaPart {
        final FakeImage image;
        final AlphaBounds alpha;
        final float drawX;
        final float drawY;
        final float drawW;
        final float drawH;
        final float[] matrix;

        SpriteAlphaPart(FakeImage image, AlphaBounds alpha, float drawX, float drawY,
                        float drawW, float drawH, float[] matrix) {
            this.image = image;
            this.alpha = alpha;
            this.drawX = drawX;
            this.drawY = drawY;
            this.drawW = drawW;
            this.drawH = drawH;
            this.matrix = matrix == null ? null : matrix.clone();
        }
    }

    private static final class Rect {
        final int x;
        final int y;
        final int w;
        final int h;

        Rect(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int px, int py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        int cx() { return x + w / 2; }
        int cy() { return y + h / 2; }
    }

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (e == null || page == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.bossItem;
            if (st.cinematicActive) return true;
            Rect icon = iconRectFromPage(page);
            if (st.available && !st.used && icon.contains(e.getX(), e.getY())) {
                st.dragging = true;
                st.returning = false;
                st.dragX = e.getX();
                st.dragY = e.getY();
                Logger.log("Boss Item drag started");
                return true;
            }
        } catch (Throwable t) {
            Logger.err("Boss Item mousePressed failed", t);
        }
        return false;
    }

    public static boolean onMouseDragged(Object page, MouseEvent e) {
        if (e == null || page == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.bossItem;
            if (st.cinematicActive) return true;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            return true;
        } catch (Throwable t) {
            Logger.err("Boss Item mouseDragged failed", t);
            return false;
        }
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        if (e == null || page == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.bossItem;
            if (st.cinematicActive) return true;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            st.dragging = false;
            AbEntity target = findTargetUnderCursor(page, e.getX(), e.getY());
            if (target != null && activate(rt, target)) {
                return true;
            }
            beginReturn(st, page, e.getX(), e.getY());
            Logger.log("Boss Item returned: no valid absorb target");
            return true;
        } catch (Throwable t) {
            Logger.err("Boss Item mouseReleased failed", t);
            return false;
        }
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return;
        State st = rt.bossItem;
        if (st.returning) {
            st.returnFrame++;
            if (st.returnFrame >= RETURN_FRAMES) {
                st.returning = false;
                st.returnFrame = 0;
            }
        }
        if (!st.cinematicActive) return;
        StageBasis sb = (StageBasis) rt.stage;
        st.frame++;
        for (int i = 0; i < st.absorbs.size(); i++) {
            Absorb a = st.absorbs.get(i);
            if (a.removed || a.entity == null) continue;
            if (st.frame < a.chainStartFrame) continue;
            moveAbsorbed(a, st.target, st.frame);
            if (st.frame >= a.impactFrame) {
                removeAbsorbed(sb, a.entity);
                a.removed = true;
                st.lastImpactFrame = st.frame;
                st.impactCount++;
            }
        }
        if (shouldFinishCinematic(st)) {
            finish(rt);
        }
    }

    public static void afterNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return;
        if (rt.bossItem.cinematicActive) return;
        tickBaseBosses(rt);
    }

    public static boolean shouldSkipNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        return enabled(rt) && rt.bossItem.cinematicActive;
    }

    public static boolean shouldBlockSpawn(CrazyRuntime.StageRuntime rt) {
        return enabled(rt) && rt.bossItem.playerSpawnLocked;
    }

    public static float[] playerBaseBossMuzzle(CrazyRuntime.StageRuntime rt, Object bbpainter) {
        if (!enabled(rt) || bbpainter == null) return null;
        try {
            StageBasis sb = (StageBasis) rt.stage;
            BossBaseStats stats = rt.bossItem.baseBosses.get(sb.ubase);
            if (stats == null || stats.base == null || stats.base.health <= 0L) return null;
            BaseBossGeometry geo = baseBossGeometry(rt, bbpainter, stats.base);
            if (geo == null) return null;
            return new float[] {unpackX(geo.muzzlePoint), unpackY(geo.muzzlePoint)};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void applyCameraFocus(CrazyRuntime.StageRuntime rt, Object bbpainter) {
        if (!enabled(rt) || bbpainter == null) return;
        State st = rt.bossItem;
        if (!st.cinematicActive || st.target == null) return;
        try {
            Object stage = rt.stage;
            if (!st.cameraCaptured) {
                st.cameraOriginalPos = BBPainterAccess.getStagePos(bbpainter);
                st.cameraCaptured = true;
            }
            int width = BBPainterAccess.getWidth(bbpainter);
            int maxW = BBPainterAccess.getMaxW(bbpainter);
            float siz = BBPainterAccess.getSiz(bbpainter);
            int minPos = Math.round(width - maxW * siz);
            if (minPos > 0) minPos = 0;
            int focusX = Math.round(width * 0.52f);
            int focusPos = Math.round(focusX - (st.target.pos * 0.32f + 200f) * siz);
            focusPos = clampInt(focusPos, minPos, 0);
            int original = clampInt(st.cameraOriginalPos, minPos, 0);
            float blendIn = easeInOut(clamp01(st.frame / (float) CAMERA_EASE_FRAMES));
            int base = Math.round(lerp(original, focusPos, blendIn));
            if (st.frame >= st.totalFrames - CAMERA_EASE_FRAMES) {
                float out = easeInOut(clamp01((st.frame - (st.totalFrames - CAMERA_EASE_FRAMES)) / (float) CAMERA_EASE_FRAMES));
                base = Math.round(lerp(focusPos, original, out));
            }
            int shake = cameraShake(st);
            BCUFields.setInt(stage, "pos", clampInt(base + shake, minPos, 0));
        } catch (Throwable t) {
            Logger.err("Boss Item camera focus failed", t);
        }
    }

    public static void drawUnder(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (!enabled(rt) || gra == null) return;
        State st = rt.bossItem;
        drawBaseBossUnder(rt, bbpainter, gra);
        if (st.cinematicActive) {
            drawRedOverlay(bbpainter, gra, st);
            if (st.frame >= CHAIN_INTRO_FRAMES) drawExecutionChains(rt, bbpainter, gra);
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (!enabled(rt) || gra == null) return;
        State st = rt.bossItem;
        drawBaseBossOver(rt, bbpainter, gra);
        Rect icon = iconRectFromPainter(bbpainter);
        boolean usable = st.available && !st.used && !st.cinematicActive;
        drawIcon(gra, icon.cx(), icon.cy(), icon.w, usable, st.used, 1f);
        if (st.returning) {
            float p = clamp01(st.returnFrame / (float) RETURN_FRAMES);
            float eased = easeOutBack(p);
            int x = Math.round(lerp(st.returnFromX, icon.cx(), eased));
            int y = Math.round(lerp(st.returnFromY, icon.cy(), eased));
            drawIcon(gra, x, y, icon.w, true, false, 0.82f);
        }
        if (st.dragging) {
            drawIcon(gra, st.dragX, st.dragY, icon.w, true, false, 0.9f);
        }
        drawBaseBossHud(rt, bbpainter, gra);
    }

    public static boolean wantsSpriteBounds(Object entity) {
        if (entity == null) return false;
        try {
            CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
            if (!enabled(rt)) return false;
            State st = rt.bossItem;
            if (!st.cinematicActive) return false;
            if (st.target == entity) return true;
            for (int i = 0; i < st.absorbs.size(); i++) {
                Absorb a = st.absorbs.get(i);
                if (!a.removed && a.entity == entity) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void recordSpriteBounds(Object entity, float minX, float minY, float maxX, float maxY,
                                          float bodyCX, float bodyCY) {
        if (entity == null) return;
        if (!finite(minX) || !finite(minY) || !finite(maxX) || !finite(maxY)) return;
        if (minX > maxX) { float t = minX; minX = maxX; maxX = t; }
        if (minY > maxY) { float t = minY; minY = maxY; maxY = t; }
        float w = maxX - minX;
        float h = maxY - minY;
        if (w <= 2f || h <= 2f || w >= 6000f || h >= 6000f) return;
        if (!finite(bodyCX) || bodyCX < minX || bodyCX > maxX) bodyCX = (minX + maxX) * 0.5f;
        if (!finite(bodyCY) || bodyCY < minY || bodyCY > maxY) bodyCY = (minY + maxY) * 0.5f;
        synchronized (spriteBoxes) {
            SpriteBox box = spriteBoxes.get(entity);
            if (box == null) {
                box = new SpriteBox();
                spriteBoxes.put(entity, box);
            }
            box.set(minX, minY, maxX, maxY, bodyCX, bodyCY);
        }
    }

    public static void recordSpriteParts(Object entity, List<manualcontrol.hooks.BoundsRecorder.SpritePart> parts) {
        if (entity == null || parts == null || parts.isEmpty()) return;
        synchronized (spriteBoxes) {
            SpriteBox box = spriteBoxes.get(entity);
            if (box == null) {
                box = new SpriteBox();
                spriteBoxes.put(entity, box);
            }
            box.parts.clear();
            int limit = Math.min(48, parts.size());
            for (int i = 0; i < limit; i++) {
                manualcontrol.hooks.BoundsRecorder.SpritePart p = parts.get(i);
                if (p == null || p.image == null || p.matrix == null || p.w == 0f || p.h == 0f) continue;
                AlphaBounds ab = alphaBounds(p.image);
                if (ab == null || !ab.valid) continue;
                box.parts.add(new SpriteAlphaPart(p.image, ab, p.x, p.y, p.w, p.h, p.matrix));
            }
            box.timeMs = System.currentTimeMillis();
        }
    }

    public static float drawScaleFor(Object entity) {
        if (entity == null) return 1f;
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt)) return 1f;
        State st = rt.bossItem;
        BossStats stats = st.bosses.get(entity);
        if (stats != null) return safeScale(stats.scale);
        BossBaseStats baseStats = st.baseBosses.get(entity);
        if (baseStats != null) return baseBossDrawScale(baseStats);
        if (st.cinematicActive) {
            if (st.target == entity) {
                return bossCinematicScale(st);
            }
            for (int i = 0; i < st.absorbs.size(); i++) {
                Absorb a = st.absorbs.get(i);
                if (a.entity != entity || a.removed) continue;
                if (st.frame < a.chainStartFrame) return 1f;
                if (st.frame < a.yankStartFrame) {
                    float t = chainTension(a, st.frame);
                    float jitter = Math.max(0f, (float) Math.sin((st.frame + a.seed) * 0.92f)) * 0.035f * t;
                    return safeScale(1f + jitter);
                }
                float p = yankProgress(a, st.frame);
                return Math.max(0.12f, 1f - easeInOut(p) * 0.88f);
            }
        }
        return 1f;
    }

    public static void applyDamageMultiplier(CrazyRuntime.StageRuntime rt, AttackAb attack) {
        if (!enabled(rt) || attack == null || attack.attacker == null) return;
        BossStats stats = rt.bossItem.bosses.get(attack.attacker);
        if (stats == null || stats.damageMultiplier <= 1.000001d) return;
        if (!rt.bossItem.damageScaled.add(attack)) return;
        long next = Math.round((double) attack.atk * stats.damageMultiplier);
        attack.atk = clampInt(next);
    }

    public static void scaleAttackRange(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        if (attack.attacker == null) return;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(attack.attacker.basis);
        if (!enabled(rt)) return;
        BossStats stats = rt.bossItem.bosses.get(attack.attacker);
        if (stats == null || stats.scale <= 1.0001f) return;
        if (!rt.bossItem.rangeScaled.add(attack)) return;
        try {
            java.lang.reflect.Field sta = BCUFields.field(attack.getClass(), "sta");
            java.lang.reflect.Field end = BCUFields.field(attack.getClass(), "end");
            float pos = attack.attacker.pos;
            sta.setFloat(attack, pos + (sta.getFloat(attack) - pos) * stats.scale);
            end.setFloat(attack, pos + (end.getFloat(attack) - pos) * stats.scale);
        } catch (Throwable t) {
            Logger.err("Boss Item range scale failed", t);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void appendScaledHitboxes(CrazyRuntime.StageRuntime rt, List result, int touch, int dire,
                                            float d0, float d1, boolean excludeRightEdge) {
        if (!enabled(rt) || result == null || dire == 0) return;
        State st = rt.bossItem;
        float left = Math.min(d0, d1);
        float right = Math.max(d0, d1);
        List<Object> bosses = new ArrayList<Object>(st.bosses.keySet());
        for (int i = 0; i < bosses.size(); i++) {
            Object obj = bosses.get(i);
            if (!(obj instanceof Entity)) continue;
            Entity e = (Entity) obj;
            BossStats stats = st.bosses.get(e);
            if (stats == null || stats.scale <= 1.0001f) continue;
            if (EntityAccess.getDire(e) != dire || e.dead || e.health <= 0L || e.isBase()) continue;
            if (!touchable(e, touch)) continue;
            float extra = scaledHitboxExtra(e, stats.scale);
            boolean inside = excludeRightEdge
                    ? e.pos >= left - extra && e.pos < right + extra
                    : e.pos >= left - extra && e.pos <= right + extra;
            if (inside && !result.contains(e)) result.add(e);
        }
    }

    private static boolean activate(CrazyRuntime.StageRuntime rt, AbEntity target) {
        if (!enabled(rt) || target == null) return false;
        State st = rt.bossItem;
        boolean baseTarget = target.isBase();
        boolean dead = target.health <= 0L || (target instanceof Entity && ((Entity) target).dead);
        if (!st.available || st.used || st.cinematicActive || dead) {
            return false;
        }
        StageBasis sb = (StageBasis) rt.stage;
        int dire = target.dire;
        List<Entity> candidates = collectAbsorbable(sb, target, dire);
        if (!baseTarget && candidates.isEmpty()) return false;

        Collections.sort(candidates, new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                return Float.compare(Math.abs(b.pos - target.pos), Math.abs(a.pos - target.pos));
            }
        });

        clearCinematic(st);
        cleanupExternalMotion(target);
        st.available = false;
        st.used = true;
        st.cinematicActive = true;
        st.target = target;
        st.targetDire = dire;
        st.baseCinematic = baseTarget;
        st.playerSpawnLocked = dire == -1;
        st.frame = 0;
        st.absorbedCount = candidates.size();
        st.targetDamage = target instanceof Entity ? totalDamage((Entity) target) : 0L;
        st.lastImpactFrame = -10000;
        st.impactCount = 0;
        st.cameraCaptured = false;
        st.cameraOriginalPos = 0;

        int count = candidates.size();
        int pinFrames = count <= 12 ? 8 : 6;
        int holdFrames = count <= 5 ? 56 : count <= 10 ? 46 : count <= 18 ? 34 : count <= 32 ? 24 : 18;
        int snapFrames = count <= 12 ? 8 : 6;
        int stagger = count <= 5 ? 4 : count <= 10 ? 3 : count <= 18 ? 2 : 1;
        int maxImpact = CHAIN_INTRO_FRAMES;
        for (int i = 0; i < candidates.size(); i++) {
            Entity e = candidates.get(i);
            cleanupExternalMotion(e);
            float distance = Math.abs(e.pos - target.pos);
            int chainStart = CHAIN_INTRO_FRAMES + i * stagger;
            int pinEnd = chainStart + pinFrames + (i % 3 == 0 ? 1 : 0);
            int holdEnd = pinEnd + holdFrames;
            int snapEnd = holdEnd + snapFrames;
            int pullStart = snapEnd;
            int pullFrames = clampInt(Math.round(14f + Math.min(10f, distance * 0.010f)), 14, 24);
            int impact = pullStart + pullFrames;
            Absorb a = new Absorb(e, chainStart, pinEnd, holdEnd, snapEnd, pullStart, impact, target.pos, i);
            st.absorbs.add(a);
            st.absorbedMaxH = safeAdd(st.absorbedMaxH, a.maxH);
            st.absorbedHealth = safeAdd(st.absorbedHealth, a.health);
            st.absorbedDamage = safeAdd(st.absorbedDamage, a.damage);
            if (impact > maxImpact) maxImpact = impact;
        }
        st.totalFrames = Math.max(MIN_CINEMATIC_FRAMES, maxImpact + FINISH_BUFFER_FRAMES);
        if (count <= 18 && st.totalFrames > MAX_CINEMATIC_FRAMES + 10) {
            Logger.log("Boss Item cinematic exceeds soft cap: frames=" + st.totalFrames + " count=" + count);
        }

        Logger.log("Boss Item activated targetDire=" + dire + " base=" + baseTarget
                + " absorbed=" + candidates.size() + " frames=" + st.totalFrames
                + " playerLock=" + st.playerSpawnLocked);
        return true;
    }

    private static void finish(CrazyRuntime.StageRuntime rt) {
        State st = rt.bossItem;
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = 0; i < st.absorbs.size(); i++) {
            Absorb a = st.absorbs.get(i);
            if (!a.removed && a.entity != null) {
                removeAbsorbed(sb, a.entity);
                a.removed = true;
            }
        }
        AbEntity target = st.target;
        if (target != null && target.isBase() && target.health > 0L) {
            transformBaseBoss(st, target);
        } else if (target instanceof Entity && !((Entity) target).dead) {
            Entity entityTarget = (Entity) target;
            long newMax = safeAdd(target.maxH, safeMul2(st.absorbedMaxH));
            long newHealth = safeAdd(Math.max(0L, target.health), safeMul2(st.absorbedHealth));
            if (newMax <= 0L) newMax = 1L;
            entityTarget.maxH = newMax;
            entityTarget.health = Math.max(1L, Math.min(newMax, newHealth));
            float scale = safeScale(1f + SCALE_PER_ABSORBED * st.absorbedCount);
            long newDamage = safeAdd(Math.max(1L, st.targetDamage), safeMul2(st.absorbedDamage));
            double mult = (double) Math.max(1L, newDamage) / (double) Math.max(1L, st.targetDamage);
            st.bosses.put(entityTarget, new BossStats(scale, mult, st.absorbedCount));
            Logger.log("Boss Item transform complete scale=" + scale + " damageMult=" + mult
                    + " maxH=" + entityTarget.maxH + " health=" + entityTarget.health);
        }
        clearSpriteBoxes(st);
        st.cinematicActive = false;
        st.dragging = false;
        st.returning = false;
        st.target = null;
        st.absorbs.clear();
        st.absorbedMaxH = 0L;
        st.absorbedHealth = 0L;
        st.absorbedDamage = 0L;
        st.targetDamage = 0L;
        st.absorbedCount = 0;
        st.baseCinematic = false;
        st.lastImpactFrame = -10000;
        st.impactCount = 0;
        restoreCamera(rt, st);
        st.frame = 0;
        st.totalFrames = 0;
    }

    private static void clearCinematic(State st) {
        clearSpriteBoxes(st);
        st.cinematicActive = false;
        st.target = null;
        st.absorbs.clear();
        st.absorbedMaxH = 0L;
        st.absorbedHealth = 0L;
        st.absorbedDamage = 0L;
        st.targetDamage = 0L;
        st.absorbedCount = 0;
        st.baseCinematic = false;
        st.lastImpactFrame = -10000;
        st.impactCount = 0;
        st.cameraCaptured = false;
        st.cameraOriginalPos = 0;
        st.frame = 0;
        st.totalFrames = 0;
    }

    private static void transformBaseBoss(State st, AbEntity base) {
        base.maxH = BASE_BOSS_HP;
        base.health = BASE_BOSS_HP;
        float scale = safeScale(Math.max(2.0f, 1f + SCALE_PER_ABSORBED * Math.max(0, st.absorbedCount)));
        long shield = safeAdd(BASE_BOSS_SHIELD,
                safeMul(BASE_BOSS_SHIELD_PER_ABSORB, Math.max(0, st.absorbedCount)));
        BossBaseStats stats = new BossBaseStats(base, base.dire, scale, st.absorbedCount, shield);
        st.baseBosses.put(base, stats);
        st.baseBeams.clear();
        clearNativeCannonShots(st);
        Logger.log("Boss Base awakened dire=" + base.dire + " scale=" + scale
                + " hp=" + BASE_BOSS_HP + " shield=" + shield
                + " absorbed=" + st.absorbedCount);
    }

    private static void tickBaseBosses(CrazyRuntime.StageRuntime rt) {
        State st = rt.bossItem;
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = st.nativeCannonShots.size() - 1; i >= 0; i--) {
            BossNativeCannonShot shot = st.nativeCannonShots.get(i);
            tickNativeCannonShot(rt, sb, shot);
            if (shot == null || shot.done()) {
                st.nativeCannonShots.remove(i);
            }
        }
        for (int i = st.baseBeams.size() - 1; i >= 0; i--) {
            BaseBeam beam = st.baseBeams.get(i);
            tickBaseBeam(rt, sb, beam);
            beam.age++;
            if (beam.done()) st.baseBeams.remove(i);
        }
        List<Object> bases = new ArrayList<Object>(st.baseBosses.keySet());
        for (int i = 0; i < bases.size(); i++) {
            Object obj = bases.get(i);
            if (!(obj instanceof AbEntity)) continue;
            BossBaseStats stats = st.baseBosses.get(obj);
            if (stats == null || stats.base == null) continue;
            if (stats.base.health <= 0L) {
                triggerFinalStand(st, stats);
            }
            stats.frame++;
            if (stats.base.health <= 0L) continue;
            if (activeBaseAttackCount(st, stats.base) > 0) continue;
            if (stats.frame < stats.nextBeamFrame && stats.finalBurstShots <= 0) continue;
            fireBaseBossAttack(rt, stats);
        }
    }

    private static void tickNativeCannonShot(CrazyRuntime.StageRuntime rt, StageBasis sb, BossNativeCannonShot shot) {
        if (shot == null || shot.cannon == null || shot.stats == null) return;
        int beforeTlw = safeListSize(sb.tlw);
        try {
            forceNativeCannonAim(shot);
            shot.cannon.update();
            forceNativeCannonAim(shot);
        } catch (Throwable t) {
            Logger.err("Boss Base native cannon update failed", t);
        }
        int created = flushNativeBossCannonEffects(sb, beforeTlw, shot);
        if (created > 0) {
            shot.nativeEffectsCreated += created;
            if (!shot.loggedNativeEffects) {
                shot.loggedNativeEffects = true;
                Logger.log("Boss Base native cannon id=" + shot.cannonId
                        + " created native effect count=" + created);
            }
        }
        boolean hasAtk = nativeCannonHas(shot.cannon, "atka");
        boolean hasExt = nativeCannonHas(shot.cannon, "exta");
        if (!shot.loggedAttackAnim && (hasAtk || hasExt)) {
            shot.loggedAttackAnim = true;
            Logger.log("Boss Base native cannon id=" + shot.cannonId
                    + " native ATK=" + hasAtk + " EXT=" + hasExt);
        }
        advanceNativeCannonVisualAnimation(shot);
        shot.age++;
        if (nativeCannonIdle(shot.cannon)) {
            shot.idleAge++;
        } else {
            shot.idleAge = 0;
        }
    }

    private static void advanceNativeCannonVisualAnimation(BossNativeCannonShot shot) {
        if (shot == null || BOSS_NATIVE_CANNON_ANIMATION_FPS <= 30) return;
        try {
            if (shot.cannon != null) {
                shot.cannon.updateAnimation();
            }
        } catch (Throwable t) {
            Logger.err("Boss Base native cannon 60fps animation update failed", t);
        }
        for (int i = shot.nativeEffects.size() - 1; i >= 0; i--) {
            ContAb cont = shot.nativeEffects.get(i);
            if (cont == null || !cont.activate) {
                shot.nativeEffects.remove(i);
                continue;
            }
            try {
                cont.updateAnimation();
            } catch (Throwable t) {
                Logger.err("Boss Base native effect 60fps animation update failed", t);
            }
        }
    }

    private static void tickBaseBeam(CrazyRuntime.StageRuntime rt, StageBasis sb, BaseBeam beam) {
        if (beam == null || !beam.activeDamage()) return;
        if (beam.age - beam.lastDamageAge < 5) return;
        beam.lastDamageAge = beam.age;
        int amount = beam.damage;
        damageBaseBeam(sb.canon, beam.target, amount);
        int splash = Math.max(1, Math.round(amount * (beam.judgement ? 0.58f : 0.32f)));
        int dire = beam.source == null ? -1 : beam.source.dire;
        int count = 0;
        for (Entity e : new ArrayList<Entity>(sb.le)) {
            if (count >= (beam.judgement ? 24 : 12)) break;
            if (e == null || e.dead || e.health <= 0L || e.isBase() || e.dire == dire) continue;
            if (!between(e.pos, Math.min(beam.startPos, beam.targetPos) - 80f,
                    Math.max(beam.startPos, beam.targetPos) + 80f)) continue;
            float lineDist = Math.abs(e.pos - beam.targetPos);
            if (lineDist <= (beam.judgement ? 420f : 270f) || beam.judgement) {
                damageBaseBeam(sb.canon, e, splash);
                count++;
            }
        }
    }

    private static void fireBaseBossAttack(CrazyRuntime.StageRuntime rt, BossBaseStats stats) {
        if (stats == null || stats.base == null) return;
        stats.firedCount++;
        boolean finisher = stats.firedCount % 5 == 0;
        if (finisher || stats.finalBurstShots > 0 && (stats.firedCount & 1) == 0) {
            fireExecutionFinisher(rt, stats);
        } else {
            fireNativeCannon(rt, stats);
        }
        stats.lastPulseFrame = stats.frame;
        if (stats.finalBurstShots > 0) {
            stats.finalBurstShots--;
            stats.nextBeamFrame = stats.frame + BASE_BOSS_NATIVE_GAP;
        } else {
            stats.nextBeamFrame = stats.frame + BASE_BOSS_NATIVE_GAP;
        }
    }

    private static void fireExecutionFinisher(CrazyRuntime.StageRuntime rt, BossBaseStats stats) {
        State st = rt.bossItem;
        StageBasis sb = (StageBasis) rt.stage;
        AbEntity target = chooseBaseBeamTarget(sb, stats);
        if (target == null) return;
        boolean judgement = true;
        int damage = baseBossBeamDamage(sb, stats, true);
        int layer = target instanceof Entity ? targetLayer(target) : 0;
        BaseBeam beam = new BaseBeam(stats.base, target, 4, judgement, damage,
                stableSeed(target, stats.firedCount + stats.seed), layer);
        st.baseBeams.add(beam);
    }

    private static void fireNativeCannon(CrazyRuntime.StageRuntime rt, BossBaseStats stats) {
        if (stats.base.dire != -1 || !stats.base.isBase()) {
            fireExecutionFinisher(rt, stats);
            return;
        }
        State st = rt.bossItem;
        StageBasis sb = (StageBasis) rt.stage;
        int idx = (stats.seed + stats.nativeCycle) & 0x7fffffff;
        idx %= BASE_BOSS_NATIVE_CANNONS.length;
        stats.nativeCycle++;
        int cannonId = BASE_BOSS_NATIVE_CANNONS[idx];
        AbEntity aimTarget = chooseNativeCannonTarget(sb, stats);
        float aimPos = nativeCannonAimPos(sb, stats, aimTarget);
        BossNativeCannonShot shot = new BossNativeCannonShot(sb, stats, cannonId, aimTarget, aimPos);
        nativeBossCannons.add(shot.cannon);
        if (!primeNativeCannon(sb, shot)) {
            fireExecutionFinisher(rt, stats);
            return;
        }
        st.nativeCannonShots.add(shot);
        Logger.log("Boss Base native cannon fired id=" + cannonId + " count=" + stats.firedCount
                + " BASE=" + nativeCannonHas(shot.cannon, "anim")
                + " ATK=" + nativeCannonHas(shot.cannon, "atka")
                + " EXT=" + nativeCannonHas(shot.cannon, "exta")
                + " preTime=" + nativeCannonInt(shot.cannon, "preTime", -999)
                + " aimPos=" + Math.round(aimPos)
                + nativeCannonVisualNote(cannonId));
    }

    private static AbEntity chooseNativeCannonTarget(StageBasis sb, BossBaseStats stats) {
        if (sb == null || stats == null) return null;
        Entity cluster = bestClusterTarget(sb, stats.dire);
        if (cluster != null) return cluster;
        Entity random = randomOpposingUnit(sb, stats);
        if (random != null) return random;
        AbEntity opposingBase = stats.dire == -1 ? sb.ebase : sb.ubase;
        return opposingBase != null && opposingBase.health > 0L ? opposingBase : null;
    }

    private static float nativeCannonAimPos(StageBasis sb, BossBaseStats stats, AbEntity target) {
        if (target != null && finite(target.pos)) return target.pos;
        if (stats != null && stats.dire == -1 && sb != null && sb.ebase != null) return sb.ebase.pos;
        if (stats != null && stats.dire == 1 && sb != null && sb.ubase != null) return sb.ubase.pos;
        return stats == null || stats.base == null ? 0f : stats.base.pos + (stats.dire == -1 ? 800f : -800f);
    }

    private static AbEntity chooseBaseBeamTarget(StageBasis sb, BossBaseStats stats) {
        int roll = Math.abs(stats.seed + stats.frame * 17 + stats.firedCount * 43) % 100;
        AbEntity opposingBase = stats.dire == -1 ? sb.ebase : sb.ubase;
        if (roll < 25 && opposingBase != null && opposingBase.health > 0L) return opposingBase;
        Entity cluster = bestClusterTarget(sb, stats.dire);
        if (roll < 85 && cluster != null) return cluster;
        Entity random = randomOpposingUnit(sb, stats);
        if (random != null) return random;
        return opposingBase != null && opposingBase.health > 0L ? opposingBase : null;
    }

    private static Entity bestClusterTarget(StageBasis sb, int sourceDire) {
        Entity best = null;
        int bestScore = -1;
        for (Entity e : sb.le) {
            if (e == null || e.dead || e.health <= 0L || e.isBase() || e.dire == sourceDire) continue;
            int score = 0;
            for (Entity other : sb.le) {
                if (other == null || other.dead || other.health <= 0L || other.isBase() || other.dire == sourceDire) continue;
                if (Math.abs(other.pos - e.pos) <= 260f) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private static Entity randomOpposingUnit(StageBasis sb, BossBaseStats stats) {
        List<Entity> units = new ArrayList<Entity>();
        for (Entity e : sb.le) {
            if (e == null || e.dead || e.health <= 0L || e.isBase() || e.dire == stats.dire) continue;
            units.add(e);
        }
        if (units.isEmpty()) return null;
        int idx = Math.abs(stats.seed + stats.frame * 11 + stats.firedCount * 7) % units.size();
        return units.get(idx);
    }

    private static int activeBaseAttackCount(State st, AbEntity base) {
        int count = 0;
        for (int i = 0; i < st.baseBeams.size(); i++) {
            BaseBeam beam = st.baseBeams.get(i);
            if (beam != null && beam.source == base && !beam.done()) count++;
        }
        for (int i = 0; i < st.nativeCannonShots.size(); i++) {
            BossNativeCannonShot shot = st.nativeCannonShots.get(i);
            if (shot != null && shot.stats != null && shot.stats.base == base && !shot.done()) count++;
        }
        return count;
    }

    private static int baseBossBeamDamage(StageBasis sb, BossBaseStats stats, boolean judgement) {
        long base = baseCannonDamage(sb);
        double mult = (judgement ? 25.0d : 10.0d) * (1.0d + Math.max(0, stats.absorbedCount) * 0.05d);
        return clampInt(Math.round(base * mult));
    }

    private static void triggerFinalStand(State st, BossBaseStats stats) {
        if (stats == null || stats.base == null || stats.finalStandUsed) return;
        stats.finalStandUsed = true;
        stats.base.maxH = Math.max(stats.base.maxH, BASE_BOSS_HP);
        stats.base.health = BASE_BOSS_FINAL_STAND_HP;
        stats.finalBurstShots = 5;
        stats.nextBeamFrame = Math.min(stats.nextBeamFrame, stats.frame);
        stats.lastPulseFrame = stats.frame;
        Logger.log("Boss Base final stand triggered dire=" + stats.dire);
    }

    private static void damageBaseBeam(Cannon cannon, AbEntity target, int amount) {
        if (cannon == null || target == null || amount <= 0 || target.health <= 0L) return;
        try {
            ArrayList<Trait> traits = new ArrayList<Trait>();
            traits.add((Trait) UserProfile.getBCData().traits.get(16));
            AttackCanon atk = new AttackCanon(cannon, amount, traits, 0, Data.Proc.blank(),
                    target.pos - 1f, target.pos + 1f, 1);
            target.damaged(atk);
        } catch (Throwable t) {
            Logger.err("Boss Base execution beam damage failed", t);
        }
    }

    public static boolean isBossNativeCannon(Cannon cannon) {
        return cannon != null && nativeBossCannons.contains(cannon);
    }

    public static void applyBossNativeCannonAttack(AttackAb attack) {
        if (attack == null || !(attack.model instanceof Cannon)) return;
        Cannon cannon = (Cannon) attack.model;
        if (!isBossNativeCannon(cannon)) return;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(cannon.b);
        if (!enabled(rt)) return;
        if (rt.bossItem.damageScaled.add(attack)) {
            attack.atk = clampInt(safeMul(attack.atk, 10L));
        }
        damageOpposingBaseFromAttack(rt, cannon, attack);
    }

    private static void damageOpposingBaseFromAttack(CrazyRuntime.StageRuntime rt, Cannon cannon, AttackAb attack) {
        if (rt == null || cannon == null || attack == null || attack.atk <= 0) return;
        if (!rt.bossItem.nativeBaseHit.add(attack)) return;
        StageBasis sb = (StageBasis) rt.stage;
        AbEntity target = cannon.getDire() == -1 ? sb.ebase : sb.ubase;
        if (target == null || target.health <= 0L) return;
        try {
            float sta = BCUFields.field(attack.getClass(), "sta").getFloat(attack);
            float end = BCUFields.field(attack.getClass(), "end").getFloat(attack);
            float left = Math.min(sta, end);
            float right = Math.max(sta, end);
            if (target.pos < left - 24f || target.pos > right + 24f) return;
            target.damaged(attack);
        } catch (Throwable t) {
            Logger.err("Boss Base native cannon base hit failed", t);
        }
    }

    private static boolean primeNativeCannon(StageBasis sb, BossNativeCannonShot shot) {
        if (sb == null || shot == null || shot.cannon == null) return false;
        try {
            forceNativeCannonAim(shot);
            shot.cannon.update();
            forceNativeCannonAim(shot);
            shot.cannon.activate();
            forceNativeCannonAim(shot);
            if (nativeCannonIdle(shot.cannon)) {
                nativeBossCannons.remove(shot.cannon);
                Logger.log("Boss Base native cannon warning: id=" + shot.cannonId
                        + " produced no active native visual; falling back to execution finisher");
                return false;
            }
            if (nativeCannonSeen.add(shot.cannonId)) {
                Logger.log("Boss Base native cannon verified id=" + shot.cannonId);
            }
            return true;
        } catch (Throwable t) {
            nativeBossCannons.remove(shot.cannon);
            Logger.err("Boss Base native cannon activate failed", t);
            return false;
        }
    }

    private static void forceNativeCannonAim(BossNativeCannonShot shot) {
        if (shot == null || shot.cannon == null || !finite(shot.aimPos)) return;
        shot.cannon.pos = shot.aimPos;
    }

    private static void clearNativeCannonShots(State st) {
        if (st == null) return;
        for (int i = 0; i < st.nativeCannonShots.size(); i++) {
            BossNativeCannonShot shot = st.nativeCannonShots.get(i);
            if (shot != null && shot.cannon != null) nativeBossCannons.remove(shot.cannon);
        }
        st.nativeCannonShots.clear();
    }

    private static boolean nativeCannonIdle(Cannon cannon) {
        if (cannon == null) return true;
        try {
            return BCUFields.get(cannon, "anim") == null
                    && BCUFields.get(cannon, "atka") == null
                    && BCUFields.get(cannon, "exta") == null
                    && nativeCannonInt(cannon, "preTime", 0) == 0
                    && nativeCannonInt(cannon, "duration", 0) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean nativeCannonHas(Cannon cannon, String field) {
        try {
            return cannon != null && BCUFields.get(cannon, field) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String nativeCannonVisualNote(int cannonId) {
        if (cannonId == 1 || cannonId == 7) {
            return " note=ContExtend has no strong native ray unless ref is enabled";
        }
        if (cannonId == 0 || cannonId == 5) {
            return " note=ContWaveCanon wave effect";
        }
        return "";
    }

    private static int safeListSize(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static int flushNativeBossCannonEffects(StageBasis sb, int beforeTlw, BossNativeCannonShot shot) {
        if (sb == null || sb.tlw == null || sb.tlw.isEmpty()) return 0;
        int start = Math.max(0, Math.min(beforeTlw, sb.tlw.size()));
        int created = sb.tlw.size() - start;
        if (created <= 0) return 0;
        try {
            for (int i = start; i < sb.tlw.size(); i++) {
                ContAb cont = sb.tlw.get(i);
                retargetNativeBossContinuation(shot, cont);
                if (shot != null && cont != null) {
                    shot.nativeEffects.add(cont);
                }
                sb.lw.add(cont);
            }
            Collections.sort(sb.lw, new Comparator<ContAb>() {
                @Override
                public int compare(ContAb a, ContAb b) {
                    int la = a == null ? 0 : a.layer;
                    int lb = b == null ? 0 : b.layer;
                    return la < lb ? -1 : la == lb ? 0 : 1;
                }
            });
            for (int i = sb.tlw.size() - 1; i >= start; i--) {
                sb.tlw.remove(i);
            }
        } catch (Throwable t) {
            Logger.err("Boss Base native cannon effect flush failed", t);
        }
        return created;
    }

    private static void retargetNativeBossContinuation(BossNativeCannonShot shot, ContAb cont) {
        if (shot == null || cont == null || !finite(shot.aimPos)) return;
        try {
            float oldPos = cont.pos;
            float delta = shot.aimPos - oldPos;
            if (Math.abs(delta) < 0.001f) return;
            cont.pos = shot.aimPos;
            shiftNativeContinuationAttack(cont, delta);
            if (!shot.loggedRetarget) {
                shot.loggedRetarget = true;
                Logger.log("Boss Base native cannon retarget id=" + shot.cannonId
                        + " effectPos=" + Math.round(oldPos) + "->" + Math.round(shot.aimPos));
            }
        } catch (Throwable t) {
            Logger.err("Boss Base native cannon effect retarget failed", t);
        }
    }

    private static void shiftNativeContinuationAttack(ContAb cont, float delta) {
        if (cont == null || !finite(delta) || Math.abs(delta) < 0.001f) return;
        try {
            Object attack = BCUFields.get(cont, "atk");
            shiftAttackRange(attack, delta);
        } catch (Throwable ignored) {
        }
    }

    private static void shiftAttackRange(Object attack, float delta) {
        if (attack == null || !finite(delta) || Math.abs(delta) < 0.001f) return;
        try {
            Field sta = BCUFields.field(attack.getClass(), "sta");
            Field end = BCUFields.field(attack.getClass(), "end");
            sta.setFloat(attack, sta.getFloat(attack) + delta);
            end.setFloat(attack, end.getFloat(attack) + delta);
        } catch (Throwable ignored) {
        }
    }

    private static int nativeCannonInt(Cannon cannon, String field, int fallback) {
        try {
            return BCUFields.getInt(cannon, field);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static void afterBaseDamaged(CrazyRuntime.StageRuntime rt, AbEntity base, AttackAb attack, long healthBefore) {
        if (!enabled(rt) || base == null || attack == null) return;
        BossBaseStats stats = rt.bossItem.baseBosses.get(base);
        if (stats == null) return;
        if (healthBefore <= base.health) return;
        long lost = healthBefore - Math.max(0L, base.health);
        if (stats.shield > 0L) {
            long absorbed = Math.min(stats.shield, lost);
            stats.shield -= absorbed;
            long overflow = lost - absorbed;
            base.health = overflow <= 0L ? healthBefore : Math.max(0L, healthBefore - overflow);
            stats.lastPulseFrame = stats.frame;
        }
        if (base.health <= 0L) {
            triggerFinalStand(rt.bossItem, stats);
        }
        if (base.health > base.maxH) base.health = base.maxH;
    }

    public static boolean applyBaseDrawTransform(CrazyRuntime.StageRuntime rt, Cannon cannon,
                                                 FakeGraphics g, P p, float siz) {
        if (!enabled(rt) || cannon == null || g == null || p == null) return false;
        StageBasis sb = (StageBasis) rt.stage;
        BossBaseStats stats = rt.bossItem.baseBosses.get(sb.ubase);
        if (stats == null || stats.base == null || stats.base.health <= 0L) return false;
        float scale = safeScale(stats.scale);
        if (scale <= 1.001f) return false;
        g.translate(p.x, p.y);
        g.scale(scale, scale);
        g.translate(-p.x, -p.y);
        return true;
    }

    public static float playerBaseDrawScale(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return 1f;
        StageBasis sb = (StageBasis) rt.stage;
        BossBaseStats stats = rt.bossItem.baseBosses.get(sb.ubase);
        if (stats == null || stats.base == null || stats.base.health <= 0L) return 1f;
        return baseBossDrawScale(stats);
    }

    public static float playerBaseFootPivotX(CrazyRuntime.StageRuntime rt, float x, float siz) {
        BaseVisualBounds b = playerBaseVisibleBounds(rt, x, 0f, siz);
        if (b != null && b.valid) return b.centerX();
        return x + 90f * Math.max(0.05f, siz);
    }

    public static float playerBaseFootPivotY(CrazyRuntime.StageRuntime rt, float y, float siz) {
        BaseVisualBounds b = playerBaseVisibleBounds(rt, 0f, y, siz);
        if (b != null && b.valid) return b.bottom;
        return y + 126f * Math.max(0.05f, siz);
    }

    private static List<Entity> collectAbsorbable(StageBasis sb, AbEntity target, int dire) {
        List<Entity> out = new ArrayList<Entity>();
        for (int i = 0; i < sb.le.size(); i++) {
            Entity e = sb.le.get(i);
            if (e == null || e == target) continue;
            if (e.isBase() || e.dead || e.health <= 0L) continue;
            if (e.dire == dire) out.add(e);
        }
        return out;
    }

    private static void moveAbsorbed(Absorb a, AbEntity target, int frame) {
        if (a == null || a.entity == null || target == null) return;
        float pos;
        if (frame < a.yankStartFrame) {
            float tension = chainTension(a, frame);
            float jitter = (float) Math.sin((frame + a.seed) * 1.55f) * (1.0f + 6.5f * tension);
            float tremor = (float) Math.sin((frame * 2.37f) + a.seed * 0.13f) * (0.4f + 3.2f * tension);
            pos = a.startPos + jitter + tremor;
        } else {
            float p = yankProgress(a, frame);
            float eased = snapPullEase(p);
            float whip = (float) Math.sin((frame + a.seed) * 1.45f) * (1f - p) * (8f + Math.min(22f, a.distanceFromBoss * 0.016f));
            pos = lerp(a.startPos, target.pos, eased) + whip;
        }
        a.entity.pos = pos;
        a.entity.lastPosition = a.entity.pos;
        int targetLayer = targetLayer(target);
        float layerP = frame < a.yankStartFrame ? 0f : easeInOut(yankProgress(a, frame));
        int layer = Math.round(lerp(a.startLayer, targetLayer, layerP));
        try { EntityAccess.setLayer(a.entity, layer); } catch (Throwable ignored) {}
    }

    private static void removeAbsorbed(StageBasis sb, Entity entity) {
        if (sb == null || entity == null) return;
        cleanupExternalMotion(entity);
        synchronized (spriteBoxes) { spriteBoxes.remove(entity); }
        try { sb.le.remove(entity); } catch (Throwable ignored) {}
        try { removeOwnedBattleObjects(sb.lw, entity); } catch (Throwable ignored) {}
        try { removeOwnedBattleObjects(sb.tlw, entity); } catch (Throwable ignored) {}
        try { removeOwnedBattleObjects((List<?>) BCUFields.get(sb, "la"), entity); } catch (Throwable ignored) {}
        try { removeFromNestedEntityLists(BCUFields.get(sb, "summoner"), entity); } catch (Throwable ignored) {}
    }

    private static void removeOwnedBattleObjects(List<?> list, Entity entity) {
        if (list == null || entity == null) return;
        Iterator<?> it = list.iterator();
        while (it.hasNext()) {
            Object obj = it.next();
            if (objectOwnedBy(obj, entity)) it.remove();
        }
    }

    private static void removeFromNestedEntityLists(Object obj, Entity entity) {
        if (obj == null || entity == null) return;
        if (obj instanceof List) {
            ((List<?>) obj).remove(entity);
            return;
        }
        Class<?> c = obj.getClass();
        if (!c.isArray()) return;
        int len = java.lang.reflect.Array.getLength(obj);
        for (int i = 0; i < len; i++) {
            removeFromNestedEntityLists(java.lang.reflect.Array.get(obj, i), entity);
        }
    }

    private static boolean objectOwnedBy(Object obj, Entity entity) {
        if (obj == null || entity == null) return false;
        try {
            Object attacker = BCUFields.field(obj.getClass(), "attacker").get(obj);
            if (attacker == entity) return true;
        } catch (Throwable ignored) {}
        try {
            Object origin = BCUFields.field(obj.getClass(), "origin").get(obj);
            if (origin != obj && objectOwnedBy(origin, entity)) return true;
        } catch (Throwable ignored) {}
        try {
            Object unit = BCUFields.field(obj.getClass(), "unit").get(obj);
            if (unit == entity) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void cleanupExternalMotion(Object entity) {
        if (entity == null) return;
        try {
            HoldState hs = HoldState.get();
            if (hs.getHeldEntity() == entity) hs.forceReset();
        } catch (Throwable ignored) {}
        try {
            List<FallingRegistry.Job> jobs = FallingRegistry.snapshot();
            for (int i = 0; i < jobs.size(); i++) {
                FallingRegistry.Job job = jobs.get(i);
                if (job.entity != entity) continue;
                if (job.savedInvincibility) {
                    try {
                        Object statusArr = BCUFields.get(job.entity, "status");
                        ((int[][]) statusArr)[44][0] = job.origStatus44;
                    } catch (Throwable ignored) {}
                }
                if (job.savedKbTime) {
                    try { BCUFields.field(job.entity.getClass(), "kbTime").setInt(job.entity, job.origKbTime); }
                    catch (Throwable ignored) {}
                }
                try { EntityAccess.setLayer(job.entity, job.origLayer); } catch (Throwable ignored) {}
                FallingRegistry.remove(job);
            }
        } catch (Throwable ignored) {}
    }

    private static void beginReturn(State st, Object page, int x, int y) {
        st.dragging = false;
        st.returning = true;
        st.returnFrame = 0;
        st.returnFromX = x;
        st.returnFromY = y;
    }

    private static AbEntity findTargetUnderCursor(Object page, int mx, int my) {
        try {
            Object basis = BCUFields.get(page, "basis");
            StageBasis sb = (StageBasis) BCUFields.get(basis, "sb");
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            Entity best = null;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < sb.le.size(); i++) {
                Entity e = sb.le.get(i);
                if (e == null || e.isBase() || e.dead || e.health <= 0L) continue;
                float sx = (e.pos * 0.32f + 200f) * siz + sbPos;
                float sy = midh - (156 - EntityAccess.getLayer(e) * 4) * siz;
                float dx = sx - mx;
                float dy = sy - my;
                if (Math.abs(dx) > 115f || Math.abs(dy) > 270f) continue;
                float d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
            if (best != null) return best;
            AbEntity base = baseUnderCursor(sb, sb.ubase, mx, my, siz, sbPos, midh);
            if (base != null) return base;
            return baseUnderCursor(sb, sb.ebase, mx, my, siz, sbPos, midh);
        } catch (Throwable t) {
            Logger.err("Boss Item target hit-test failed", t);
            return null;
        }
    }

    private static AbEntity baseUnderCursor(StageBasis sb, AbEntity base, int mx, int my,
                                            float siz, int sbPos, int midh) {
        if (base == null || base.health <= 0L) return null;
        float sx = (base.pos * 0.32f + 200f) * siz + sbPos;
        float sy = midh - 120f * siz;
        float halfW = Math.max(120f, 175f * siz);
        float halfH = Math.max(115f, 165f * siz);
        if (mx >= sx - halfW && mx <= sx + halfW && my >= sy - halfH && my <= sy + halfH) {
            return base;
        }
        return null;
    }

    private static boolean isBattleCanvasEvent(Object page, MouseEvent e) {
        try {
            Object bb = BCUFields.get(page, "bb");
            return e.getSource() == bb;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Rect iconRectFromPage(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            int w = ((Number) BCUFields.invoke(bb, "getWidth")).intValue();
            int h = ((Number) BCUFields.invoke(bb, "getHeight")).intValue();
            return iconRect(w, h);
        } catch (Throwable ignored) {
            return iconRect(1200, 800);
        }
    }

    private static Rect iconRectFromPainter(Object bbpainter) {
        try {
            return iconRect(BBPainterAccess.getWidth(bbpainter), BBPainterAccess.getHeight(bbpainter));
        } catch (Throwable ignored) {
            return iconRect(1200, 800);
        }
    }

    private static Rect iconRect(int w, int h) {
        int size = clampInt(Math.round(h * 0.075f), 44, 66);
        int margin = Math.max(12, Math.round(h * 0.018f));
        int x = w - size - margin;
        int y = Math.max(58, Math.round(h * 0.095f));
        return new Rect(x, y, size, size);
    }

    private static void drawRedOverlay(Object bbpainter, FakeGraphics gra, State st) {
        int w = 1200;
        int h = 800;
        try {
            w = BBPainterAccess.getWidth(bbpainter);
            h = BBPainterAccess.getHeight(bbpainter);
        } catch (Throwable ignored) {}
        float p = clamp01(st.frame / (float) Math.max(1, st.totalFrames));
        float introFade = Math.max(0.35f, Math.min(1f, st.frame / (float) Math.max(1, CHAIN_INTRO_FRAMES - 4)));
        float outFade = Math.min(1f, (1f - p) * 5f);
        float fade = Math.min(introFade, outFade);
        int alpha = Math.round((92f + 24f * (float) Math.sin(st.frame * 0.18f) + impactPulse(st) * 58f) * fade);
        Graphics2D g2 = CrazyRender.unwrap(gra);
        if (g2 != null) {
            java.awt.Composite old = g2.getComposite();
            Color oldColor = g2.getColor();
            AffineTransform oldTransform = g2.getTransform();
            try {
                g2.setTransform(new AffineTransform());
                g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(0.42f, alpha / 255f))));
                g2.setColor(new Color(150, 0, 0));
                g2.fillRect(0, 0, w, h);
            } finally {
                g2.setTransform(oldTransform);
                g2.setComposite(old);
                g2.setColor(oldColor);
            }
        } else {
            FakeTransform oldTransform = pushIdentityTransform(gra);
            try {
                gra.colRect(0, 0, w, h, 150, 0, 0, Math.max(0, Math.min(160, alpha)));
            } finally {
                popTransform(gra, oldTransform);
            }
        }
    }

    private static void drawBaseBossUnder(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.bossItem;
        if (st.baseBosses.isEmpty() && st.baseBeams.isEmpty() && st.nativeCannonShots.isEmpty()) return;
        FakeTransform oldTransform = pushIdentityTransform(gra);
        try {
            for (Object obj : new ArrayList<Object>(st.baseBosses.keySet())) {
                if (!(obj instanceof AbEntity)) continue;
                BossBaseStats stats = st.baseBosses.get(obj);
                if (stats == null || stats.base == null || stats.base.health <= 0L) continue;
                drawBaseBossShield(gra, bbpainter, stats, false);
            }
            for (int i = 0; i < st.baseBeams.size(); i++) {
                drawBaseBeamUnder(gra, bbpainter, st.baseBeams.get(i));
            }
        } finally {
            try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
            popTransform(gra, oldTransform);
        }
    }

    private static void drawBaseBossOver(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.bossItem;
        if (st.baseBosses.isEmpty() && st.baseBeams.isEmpty()) return;
        FakeTransform oldTransform = pushIdentityTransform(gra);
        try {
            for (Object obj : new ArrayList<Object>(st.baseBosses.keySet())) {
                if (!(obj instanceof AbEntity)) continue;
                BossBaseStats stats = st.baseBosses.get(obj);
                if (stats == null || stats.base == null || stats.base.health <= 0L) continue;
                drawBaseBossShield(gra, bbpainter, stats, true);
            }
            for (int i = 0; i < st.baseBeams.size(); i++) {
                drawBaseBeamOver(gra, bbpainter, st.baseBeams.get(i));
            }
        } finally {
            try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
            popTransform(gra, oldTransform);
        }
    }

    public static void drawNativeCannonAttacks(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (!enabled(rt) || bbpainter == null || gra == null) return;
        State st = rt.bossItem;
        if (st.nativeCannonShots.isEmpty()) return;
        StageBasis sb = (StageBasis) rt.stage;
        FakeTransform old = gra.getTransform();
        try {
            for (int i = 0; i < st.nativeCannonShots.size(); i++) {
                BossNativeCannonShot shot = st.nativeCannonShots.get(i);
                if (shot == null || shot.cannon == null || shot.stats == null || shot.stats.base != sb.ubase) continue;
                int id = clampInt(shot.cannonId, 0, BASE_CANNON_Y.length - 1);
                BaseBossGeometry geo = baseBossGeometry(rt, bbpainter, shot.stats.base);
                if (geo == null) continue;
                long basePoint = geo.nativeCannonOrigin(id);
                boolean hasBase = nativeCannonHas(shot.cannon, "anim");
                boolean hasAtk = nativeCannonHas(shot.cannon, "atka");
                boolean hasExt = nativeCannonHas(shot.cannon, "exta");
                if (!shot.loggedRender) {
                    shot.loggedRender = true;
                    Logger.log("Boss Base native cannon render id=" + shot.cannonId
                            + " BASE=" + hasBase + " ATK=" + hasAtk + " EXT=" + hasExt);
                }
                shot.renderedFrames++;
                gra.setTransform(old);
                drawScaledNativeCannonBase(gra, shot.cannon, geo, basePoint);
                gra.setTransform(old);
                forceNativeCannonAim(shot);
                float atkX = CrazyRender.screenX(bbpainter, nativeCannonDrawPos(shot));
                float atkY = geo.rootY;
                shot.cannon.drawAtk(gra, new P(atkX, atkY), geo.psiz);
            }
        } catch (Throwable t) {
            Logger.err("Boss Base native cannon draw failed", t);
        } finally {
            try { gra.setTransform(old); } catch (Throwable ignored) {}
            try { gra.delete(old); } catch (Throwable ignored) {}
        }
    }

    private static float nativeCannonDrawPos(BossNativeCannonShot shot) {
        if (shot == null || !finite(shot.aimPos)) {
            return shot == null || shot.cannon == null ? 0f : shot.cannon.pos;
        }
        return shot.aimPos;
    }

    private static void drawScaledNativeCannonBase(FakeGraphics gra, Cannon cannon,
                                                   BaseBossGeometry geo, long basePoint) {
        if (cannon == null || geo == null) return;
        float baseX = unpackX(basePoint);
        float baseY = unpackY(basePoint);
        if (geo.scale <= 1.001f) {
            cannon.drawBase(gra, new P(baseX, baseY), geo.psiz);
            return;
        }
        FakeTransform t = gra.getTransform();
        try {
            gra.translate(baseX, baseY);
            gra.scale(geo.scale, geo.scale);
            gra.translate(-baseX, -baseY);
            cannon.drawBase(gra, new P(baseX, baseY), geo.psiz);
        } finally {
            try { gra.setTransform(t); } catch (Throwable ignored) {}
            try { gra.delete(t); } catch (Throwable ignored) {}
        }
    }

    private static void drawBaseBossHud(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
    }

    private static void drawBaseBossShield(FakeGraphics gra, Object bbpainter, BossBaseStats stats, boolean front) {
        if (stats == null || stats.base == null) return;
        CrazyRuntime.StageRuntime rt = runtimeFromPainter(bbpainter);
        BaseBossGeometry geo = baseBossGeometry(rt, bbpainter, stats.base);
        if (geo == null || geo.shield == null) return;
        drawPolygonShield(gra, geo.shield, stats.seed, front);
    }

    private static void drawPolygonShield(FakeGraphics gra, AuraCircle c, int seed, boolean front) {
        if (gra == null || c == null || c.r <= 0) return;
        if (!front) {
            fillDisc(gra, c.x, c.y, c.r + 10, 105, 0, 0, 12);
            fillDisc(gra, c.x, c.y, c.r, 170, 20, 18, 18);
            drawShieldGrid(gra, c, seed, false);
            return;
        }
        drawShieldGrid(gra, c, seed, true);
        strokeDisc(gra, c.x, c.y, c.r + 1, 2, 255, 58, 36, 70);
        strokeDisc(gra, c.x, c.y, c.r + 12, 1, 255, 206, 64, 34);
    }

    private static void drawShieldGrid(FakeGraphics gra, AuraCircle c, int seed, boolean front) {
        int alpha = front ? 62 : 25;
        int hotAlpha = front ? 88 : 36;
        int dimAlpha = front ? 38 : 18;
        int thick = front ? 2 : 1;
        int r = c.r;
        for (int i = -3; i <= 3; i++) {
            if (i == 0) continue;
            int y = c.y + Math.round(i * r / 4.2f);
            int dy = y - c.y;
            int span = Math.round((float) Math.sqrt(Math.max(0, r * r - dy * dy)));
            drawRectLine(gra, c.x - span, y, c.x + span, y, thick, 255, 64, 40, dimAlpha);
        }
        for (int i = -2; i <= 2; i++) {
            if (i == 0) continue;
            drawShieldMeridian(gra, c, i / 3.0f, thick, front ? 255 : 190, front ? 78 : 28,
                    front ? 44 : 22, dimAlpha);
        }
        drawShieldFacetRows(gra, c, seed, thick, alpha, hotAlpha);
        strokeDisc(gra, c.x, c.y, r, thick, 255, 48, 34, alpha);
        strokeDisc(gra, c.x, c.y, Math.max(8, r - Math.max(12, r / 12)), 1, 255, 190, 54, dimAlpha);
    }

    private static void drawShieldMeridian(FakeGraphics gra, AuraCircle c, float offset, int thickness,
                                           int rr, int gg, int bb, int alpha) {
        int prevX = 0;
        int prevY = 0;
        boolean hasPrev = false;
        for (int step = -8; step <= 8; step++) {
            float ny = step / 8.0f;
            float curve = (float) Math.sqrt(Math.max(0f, 1f - ny * ny));
            int x = Math.round(c.x + offset * c.r * curve);
            int y = Math.round(c.y + ny * c.r);
            if (hasPrev) drawRectLine(gra, prevX, prevY, x, y, thickness, rr, gg, bb, alpha);
            prevX = x;
            prevY = y;
            hasPrev = true;
        }
    }

    private static void drawShieldFacetRows(FakeGraphics gra, AuraCircle c, int seed, int thickness,
                                            int alpha, int hotAlpha) {
        int rows = 5;
        int[][] prev = null;
        for (int row = 0; row <= rows; row++) {
            float ny = -0.82f + row * (1.64f / rows);
            int y = Math.round(c.y + ny * c.r);
            int span = Math.round(c.r * (float) Math.sqrt(Math.max(0f, 1f - ny * ny)));
            int cols = Math.max(4, 4 + row % 3);
            int[][] points = new int[cols + 1][2];
            for (int col = 0; col <= cols; col++) {
                float p = col / (float) cols;
                int jitter = ((seed >> ((row + col) & 15)) & 7) - 3;
                points[col][0] = Math.round(c.x - span + span * 2f * p) + jitter;
                points[col][1] = y + (((seed >> ((row * 3 + col) & 15)) & 3) - 1);
                if (col > 0) {
                    drawRectLine(gra, points[col - 1][0], points[col - 1][1],
                            points[col][0], points[col][1], thickness, 255, 74, 42, alpha);
                }
            }
            if (prev != null) {
                int limit = Math.min(prev.length, points.length);
                for (int col = 0; col < limit; col++) {
                    boolean hot = ((seed + row * 17 + col * 31) & 3) == 0;
                    drawRectLine(gra, prev[col][0], prev[col][1], points[col][0], points[col][1],
                            thickness, hot ? 255 : 220, hot ? 198 : 54, hot ? 70 : 38,
                            hot ? hotAlpha : alphaScale(alpha, 0.78f));
                }
            }
            prev = points;
        }
    }

    private static void drawBaseBeamUnder(FakeGraphics gra, Object bbpainter, BaseBeam beam) {
        if (beam == null) return;
        if (isExecutionBeam(beam)) {
            drawApocalypseExecutionBeam(gra, bbpainter, beam, false);
        } else {
            drawBaseBeam(gra, bbpainter, beam);
        }
    }

    private static void drawBaseBeamOver(FakeGraphics gra, Object bbpainter, BaseBeam beam) {
        if (beam == null || !isExecutionBeam(beam)) return;
        drawApocalypseExecutionBeam(gra, bbpainter, beam, true);
    }

    private static boolean isExecutionBeam(BaseBeam beam) {
        return beam != null && (beam.judgement || beam.type == 4);
    }

    private static void drawApocalypseExecutionBeam(FakeGraphics gra, Object bbpainter, BaseBeam beam, boolean over) {
        if (beam == null || beam.source == null) return;
        long sp = baseMuzzlePoint(beam.source, bbpainter);
        int sx = unpackX(sp);
        int sy = unpackY(sp);
        long tp = targetImpactPoint(beam.target, bbpainter, sx, sy);
        int tx = unpackX(tp);
        int ty = unpackY(tp);
        int thick = beam.judgement ? 54 : 38;
        float charge = clamp01(beam.age / (float) Math.max(1, beam.warmup));
        float fade = beam.age < beam.duration - 10 ? 1f : clamp01((beam.duration - beam.age) / 10f);
        int alpha = Math.round((beam.judgement ? 235f : 215f) * fade);
        if (beam.age < beam.warmup) {
            if (!over) {
                drawRectLine(gra, sx, sy, tx, ty, 1, 255, 20, 10, alphaScale(alpha, 0.26f + charge * 0.28f));
                strokeDisc(gra, tx, ty, 20 + Math.round(42f * charge), 2, 255, 42, 24, alphaScale(alpha, 0.36f));
            } else {
                fillDisc(gra, sx, sy, 8 + Math.round((beam.judgement ? 42f : 28f) * charge),
                        255, 92, 18, alphaScale(alpha, 0.30f));
                fillDisc(gra, sx, sy, 4 + Math.round(12f * charge),
                        255, 245, 180, alphaScale(alpha, 0.72f));
            }
            return;
        }
        int activeAge = beam.age - beam.warmup;
        float pulse = 1f + 0.10f * (float) Math.sin((beam.age + beam.seed) * 0.55f);
        if (!over) {
            drawApocalypseAura(gra, sx, sy, tx, ty, beam, Math.round(thick * 2.4f * pulse), alpha);
            drawChaosTendrils(gra, sx, sy, tx, ty, beam, thick, alpha, false);
            drawGroundCracks(gra, tx, ty, beam, alpha);
            return;
        }
        drawThickSegmentedBeam(gra, sx, sy, tx, ty, beam, Math.round(thick * pulse), alpha);
        drawWhiteLightning(gra, sx, sy, tx, ty, beam, thick, alpha);
        drawChaosTendrils(gra, sx, sy, tx, ty, beam, thick, alpha, true);
        drawMuzzleFlare(gra, sx, sy, tx, ty, beam, thick, alpha);
        drawApocalypseImpact(gra, tx, ty, beam, activeAge, alpha);
    }

    private static void drawBaseBeam(FakeGraphics gra, Object bbpainter, BaseBeam beam) {
        if (beam == null || beam.source == null) return;
        long sp = baseMuzzlePoint(beam.source, bbpainter);
        int sx = unpackX(sp);
        int sy = unpackY(sp);
        long tp = targetImpactPoint(beam.target, bbpainter, sx, sy);
        int tx = unpackX(tp);
        int ty = unpackY(tp);
        float charge = clamp01(beam.age / (float) Math.max(1, beam.warmup));
        float fade = beam.age < beam.duration - 8 ? 1f : clamp01((beam.duration - beam.age) / 8f);
        int alpha = Math.round(210f * fade);
        if (beam.type == 1) {
            drawCannonBlastBeam(gra, sx, sy, tx, ty, beam, charge, alpha);
            return;
        }
        if (beam.type == 2) {
            drawSweepCanonBeam(gra, sx, sy, tx, ty, beam, charge, alpha);
            return;
        }
        if (beam.type == 3) {
            drawCurseLightningBeam(gra, sx, sy, tx, ty, beam, charge, alpha);
            return;
        }
        if (beam.age < beam.warmup) {
            int r = Math.round((beam.judgement ? 42f : 26f) * charge);
            strokeDisc(gra, tx, ty, r, 2, 255, 42, 24, alphaScale(alpha, 0.52f));
            drawRectLine(gra, sx, sy, tx, ty, 1, 255, 42, 24, alphaScale(alpha, 0.30f + charge * 0.28f));
            return;
        }
        int glow = beam.judgement ? 18 : 11;
        int core = beam.judgement ? 5 : 3;
        for (int i = 0; i < (beam.judgement ? 4 : 2); i++) {
            float off = (i - 1.5f) * (beam.judgement ? 9f : 5f);
            drawOffsetBeamLine(gra, sx, sy, tx, ty, off, glow - i * 3,
                    120, 0, 0, alphaScale(alpha, 0.12f));
        }
        drawOffsetBeamLine(gra, sx, sy, tx, ty, 0f, beam.judgement ? 9 : 6,
                255, 32, 18, alphaScale(alpha, 0.38f));
        drawOffsetBeamLine(gra, sx, sy, tx, ty, 0f, core,
                255, beam.judgement ? 235 : 185, beam.judgement ? 132 : 48, alphaScale(alpha, 0.88f));
        drawExecutionBeamNoise(gra, sx, sy, tx, ty, beam);
        int age = Math.max(0, beam.age - beam.warmup);
        drawImpactBurst(gra, tx, ty, Math.min(IMPACT_VISIBLE_FRAMES - 1, age % IMPACT_VISIBLE_FRAMES), beam.seed);
    }

    private static void drawCannonBlastBeam(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                            BaseBeam beam, float charge, int alpha) {
        if (beam.age < beam.warmup) {
            int r = 14 + Math.round(28f * charge);
            fillDisc(gra, sx, sy, r, 255, 120, 20, alphaScale(alpha, 0.28f));
            strokeDisc(gra, sx, sy, r + 8, 3, 255, 220, 80, alphaScale(alpha, 0.48f));
            drawRectLine(gra, sx, sy, tx, ty, 2, 255, 150, 30, alphaScale(alpha, 0.28f));
            return;
        }
        float travel = easeOutQuart(clamp01((beam.age - beam.warmup) / (float) Math.max(1, beam.duration - beam.warmup - 8)));
        int hx = Math.round(lerp(sx, tx, travel));
        int hy = Math.round(lerp(sy, ty, travel));
        drawRectLine(gra, sx, sy, hx, hy, 8, 255, 92, 18, alphaScale(alpha, 0.22f));
        drawRectLine(gra, sx, sy, hx, hy, 3, 255, 220, 90, alphaScale(alpha, 0.74f));
        fillDisc(gra, hx, hy, 18, 255, 120, 24, alphaScale(alpha, 0.62f));
        fillDisc(gra, hx, hy, 8, 255, 245, 180, alphaScale(alpha, 0.88f));
        if (travel > 0.92f) drawImpactBurst(gra, tx, ty, beam.age % IMPACT_VISIBLE_FRAMES, beam.seed);
    }

    private static void drawSweepCanonBeam(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                           BaseBeam beam, float charge, int alpha) {
        if (beam.age < beam.warmup) {
            drawRectLine(gra, sx, sy, tx, ty, 2, 60, 180, 255, alphaScale(alpha, 0.35f + charge * 0.25f));
            strokeDisc(gra, tx, ty, 20 + Math.round(18f * charge), 2, 80, 220, 255, alphaScale(alpha, 0.48f));
            return;
        }
        for (int i = 0; i < 5; i++) {
            float off = (i - 2) * 7f;
            int a = alphaScale(alpha, i == 2 ? 0.75f : 0.34f);
            drawOffsetBeamLine(gra, sx, sy, tx, ty, off, i == 2 ? 4 : 2,
                    i == 2 ? 210 : 70, i == 2 ? 245 : 180, 255, a);
        }
        int waveCount = 6;
        for (int i = 0; i < waveCount; i++) {
            float p = ((beam.age * 0.055f + i / (float) waveCount) % 1f);
            int wx = Math.round(lerp(sx, tx, p));
            int wy = Math.round(lerp(sy, ty, p));
            strokeDisc(gra, wx, wy, 7 + (i & 1) * 4, 2, 180, 245, 255, alphaScale(alpha, 0.36f));
        }
    }

    private static void drawCurseLightningBeam(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                               BaseBeam beam, float charge, int alpha) {
        if (beam.age < beam.warmup) {
            drawRectLine(gra, sx, sy, tx, ty, 1, 170, 60, 255, alphaScale(alpha, 0.38f));
            fillDisc(gra, sx, sy, 9 + Math.round(18f * charge), 130, 0, 210, alphaScale(alpha, 0.45f));
            return;
        }
        int lx = sx;
        int ly = sy;
        int segments = 9;
        for (int i = 1; i <= segments; i++) {
            float p = i / (float) segments;
            int nx = Math.round(lerp(sx, tx, p));
            int ny = Math.round(lerp(sy, ty, p));
            int jitter = ((beam.seed + beam.age * 13 + i * 41) & 31) - 15;
            if (i < segments) {
                float dx = tx - sx;
                float dy = ty - sy;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1f) len = 1f;
                nx += Math.round(-dy / len * jitter);
                ny += Math.round(dx / len * jitter);
            }
            drawRectLine(gra, lx, ly, nx, ny, 7, 90, 0, 120, alphaScale(alpha, 0.18f));
            drawRectLine(gra, lx, ly, nx, ny, 3, 185, 50, 255, alphaScale(alpha, 0.72f));
            drawRectLine(gra, lx, ly, nx, ny, 1, 255, 230, 255, alphaScale(alpha, 0.88f));
            lx = nx;
            ly = ny;
        }
        drawImpactBurst(gra, tx, ty, beam.age % IMPACT_VISIBLE_FRAMES, beam.seed);
    }

    private static void drawOffsetBeamLine(FakeGraphics gra, int sx, int sy, int tx, int ty, float off,
                                           int thickness, int r, int g, int b, int alpha) {
        float dx = tx - sx;
        float dy = ty - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) len = 1f;
        float nx = -dy / len;
        float ny = dx / len;
        drawRectLine(gra, Math.round(sx + nx * off), Math.round(sy + ny * off),
                Math.round(tx + nx * off), Math.round(ty + ny * off),
                Math.max(1, thickness), r, g, b, alpha);
    }

    private static void drawExecutionBeamNoise(FakeGraphics gra, int sx, int sy, int tx, int ty, BaseBeam beam) {
        int shards = beam.judgement ? 9 : 5;
        for (int i = 0; i < shards; i++) {
            float p = ((beam.age * (7 + i) + beam.seed + i * 23) & 127) / 127f;
            float off = (((beam.seed >> (i % 12)) & 7) - 3) * (beam.judgement ? 3.2f : 2.1f);
            int x0 = Math.round(lerp(sx, tx, p));
            int y0 = Math.round(lerp(sy, ty, p));
            int x1 = Math.round(lerp(sx, tx, Math.min(1f, p + 0.05f)));
            int y1 = Math.round(lerp(sy, ty, Math.min(1f, p + 0.05f)));
            drawOffsetBeamLine(gra, x0, y0, x1, y1, off, beam.judgement ? 3 : 2,
                    255, 235, 170, beam.judgement ? 170 : 120);
        }
    }

    private static void drawApocalypseAura(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                           BaseBeam beam, int thick, int alpha) {
        drawOffsetBeamLine(gra, sx, sy, tx, ty, 0f, thick, 90, 0, 0, alphaScale(alpha, 0.10f));
        drawOffsetBeamLine(gra, sx, sy, tx, ty, -thick * 0.22f, Math.max(4, thick / 2),
                80, 0, 90, alphaScale(alpha, 0.09f));
        drawOffsetBeamLine(gra, sx, sy, tx, ty, thick * 0.20f, Math.max(4, thick / 2),
                160, 0, 0, alphaScale(alpha, 0.08f));
    }

    private static void drawThickSegmentedBeam(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                               BaseBeam beam, int thick, int alpha) {
        int segments = 10;
        long prev = jitteredBeamPoint(sx, sy, tx, ty, beam, 0f, 0f);
        for (int i = 1; i <= segments; i++) {
            float p = i / (float) segments;
            float wobble = (((beam.seed + beam.age * 17 + i * 43) & 15) - 7) * 0.45f;
            long next = jitteredBeamPoint(sx, sy, tx, ty, beam, p, wobble);
            int x0 = unpackX(prev);
            int y0 = unpackY(prev);
            int x1 = unpackX(next);
            int y1 = unpackY(next);
            drawRectLine(gra, x0, y0, x1, y1, Math.max(8, thick), 180, 12, 0, alphaScale(alpha, 0.58f));
            drawRectLine(gra, x0, y0, x1, y1, Math.max(6, Math.round(thick * 0.65f)), 255, 68, 12, alphaScale(alpha, 0.72f));
            drawRectLine(gra, x0, y0, x1, y1, Math.max(3, Math.round(thick * 0.32f)), 255, 198, 42, alphaScale(alpha, 0.86f));
            drawRectLine(gra, x0, y0, x1, y1, Math.max(2, Math.round(thick * 0.16f)), 255, 250, 220, alphaScale(alpha, 0.94f));
            prev = next;
        }
    }

    private static long jitteredBeamPoint(int sx, int sy, int tx, int ty, BaseBeam beam, float p, float wobble) {
        float dx = tx - sx;
        float dy = ty - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) len = 1f;
        float nx = -dy / len;
        float ny = dx / len;
        float wave = (float) Math.sin((beam.age * 0.72f) + p * 11.4f + (beam.seed & 31)) * wobble;
        return packPoint(Math.round(lerp(sx, tx, p) + nx * wave), Math.round(lerp(sy, ty, p) + ny * wave));
    }

    private static void drawWhiteLightning(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                           BaseBeam beam, int thick, int alpha) {
        int count = beam.judgement ? 8 : 5;
        for (int i = 0; i < count; i++) {
            float p0 = ((beam.seed + beam.age * (11 + i) + i * 37) & 127) / 160f;
            float p1 = Math.min(1f, p0 + 0.18f + (i & 1) * 0.07f);
            float off = (((beam.seed >> (i % 13)) & 15) - 7) * thick * 0.08f;
            drawJaggedLine(gra, sx, sy, tx, ty, p0, p1, off, 5, 255, 250, 235, alphaScale(alpha, 0.80f), beam.seed + i * 71);
            drawJaggedLine(gra, sx, sy, tx, ty, p0, p1, off, 2, 255, 255, 255, alphaScale(alpha, 0.95f), beam.seed + i * 97);
        }
    }

    private static void drawChaosTendrils(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                          BaseBeam beam, int thick, int alpha, boolean over) {
        int count = over ? 5 : 4;
        for (int i = 0; i < count; i++) {
            float p0 = ((beam.seed * (i + 3) + beam.age * (5 + i)) & 127) / 150f;
            float p1 = Math.min(1f, p0 + 0.25f);
            float side = ((i & 1) == 0 ? -1f : 1f);
            float off = side * thick * (0.62f + ((beam.seed >> (i % 9)) & 7) * 0.07f);
            if (over) {
                drawJaggedLine(gra, sx, sy, tx, ty, p0, p1, off, 4, 32, 0, 38, alphaScale(alpha, 0.58f), beam.seed + i * 53);
                drawJaggedLine(gra, sx, sy, tx, ty, p0, p1, off * 0.92f, 2, 160, 0, 210, alphaScale(alpha, 0.38f), beam.seed + i * 31);
            } else {
                drawJaggedLine(gra, sx, sy, tx, ty, p0, p1, off, 7, 25, 0, 0, alphaScale(alpha, 0.20f), beam.seed + i * 29);
            }
        }
    }

    private static void drawJaggedLine(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                       float p0, float p1, float off, int thickness,
                                       int r, int g, int b, int alpha, int seed) {
        float dx = tx - sx;
        float dy = ty - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) len = 1f;
        float nx = -dy / len;
        float ny = dx / len;
        int segments = 5;
        int lx = Math.round(lerp(sx, tx, p0) + nx * off);
        int ly = Math.round(lerp(sy, ty, p0) + ny * off);
        for (int i = 1; i <= segments; i++) {
            float p = lerp(p0, p1, i / (float) segments);
            float jitter = (((seed + i * 41) & 15) - 7) * 2.1f;
            int x = Math.round(lerp(sx, tx, p) + nx * (off + jitter));
            int y = Math.round(lerp(sy, ty, p) + ny * (off + jitter));
            drawRectLine(gra, lx, ly, x, y, thickness, r, g, b, alpha);
            lx = x;
            ly = y;
        }
    }

    private static void drawMuzzleFlare(FakeGraphics gra, int sx, int sy, int tx, int ty,
                                        BaseBeam beam, int thick, int alpha) {
        int r = Math.round((beam.judgement ? 48f : 34f) * (0.82f + 0.12f * (float) Math.sin(beam.age * 0.6f)));
        fillDisc(gra, sx, sy, r, 255, 56, 12, alphaScale(alpha, 0.36f));
        fillDisc(gra, sx, sy, Math.max(5, r / 3), 255, 250, 210, alphaScale(alpha, 0.88f));
        float dx = sx - tx;
        float dy = sy - ty;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) len = 1f;
        for (int i = 0; i < 5; i++) {
            float off = (i - 2) * thick * 0.13f;
            float nx = -dy / len;
            float ny = dx / len;
            int ex = Math.round(sx + dx / len * (18 + i * 5) + nx * off);
            int ey = Math.round(sy + dy / len * (18 + i * 5) + ny * off);
            drawRectLine(gra, sx, sy, ex, ey, 2, 255, 210, 64, alphaScale(alpha, 0.56f));
        }
    }

    private static void drawApocalypseImpact(FakeGraphics gra, int tx, int ty, BaseBeam beam, int age, int alpha) {
        int baseR = beam.judgement ? 130 : 90;
        float pulse = 0.85f + 0.16f * (float) Math.sin((beam.age + beam.seed) * 0.48f);
        int r = Math.round(baseR * pulse);
        fillDisc(gra, tx, ty, r, 255, 32, 20, alphaScale(alpha, 0.34f));
        fillDisc(gra, tx, ty, Math.max(10, r / 2), 255, 120, 20, alphaScale(alpha, 0.54f));
        fillDisc(gra, tx, ty, Math.max(8, r / 4), 255, 250, 220, alphaScale(alpha, 0.88f));
        int shards = beam.judgement ? 26 : 17;
        for (int i = 0; i < shards; i++) {
            double ang = (beam.seed * 0.013 + i * Math.PI * 2.0 / shards + beam.age * 0.03);
            int len = Math.round((beam.judgement ? 58f : 38f) + ((beam.seed + i * 19) & 31));
            int x0 = tx + Math.round((float) Math.cos(ang) * (r * 0.28f));
            int y0 = ty + Math.round((float) Math.sin(ang) * (r * 0.22f));
            int x1 = tx + Math.round((float) Math.cos(ang) * (r * 0.42f + len));
            int y1 = ty + Math.round((float) Math.sin(ang) * (r * 0.32f + len));
            boolean dark = (i & 3) == 0;
            drawRectLine(gra, x0, y0, x1, y1, dark ? 5 : 4,
                    dark ? 18 : 255, dark ? 0 : 80, dark ? 0 : 16, alphaScale(alpha, dark ? 0.72f : 0.68f));
        }
    }

    private static void drawGroundCracks(FakeGraphics gra, int tx, int ty, BaseBeam beam, int alpha) {
        int groundY = ty + (beam.judgement ? 54 : 38);
        int count = beam.judgement ? 8 : 5;
        for (int i = 0; i < count; i++) {
            int dir = (i & 1) == 0 ? -1 : 1;
            int x0 = tx + dir * (12 + i * 4);
            int y0 = groundY + ((beam.seed + i * 7) & 7) - 3;
            int x1 = x0 + dir * (38 + ((beam.seed >> (i % 11)) & 31));
            int y1 = y0 + (((beam.seed + i * 17) & 15) - 7);
            drawRectLine(gra, x0, y0, x1, y1, 4, 20, 0, 0, alphaScale(alpha, 0.50f));
            drawRectLine(gra, x0, y0, x1, y1, 2, 255, 40, 24, alphaScale(alpha, 0.34f));
        }
    }

    private static void drawShieldBar(FakeGraphics gra, int x, int y, int w, int h, BossBaseStats stats) {
        rect(gra, x - 2, y - 2, w + 4, h + 4, 20, 0, 0, 155);
        rect(gra, x, y, w, h, 70, 0, 0, 165);
        float p = stats.maxShield <= 0L ? 0f : clamp01(stats.shield / (float) stats.maxShield);
        rect(gra, x, y, Math.round(w * p), h, 255, 40, 24, 210);
        rect(gra, x, y, Math.round(w * p), Math.max(1, h / 3), 255, 210, 90, 170);
    }

    private static void drawExecutionChains(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.bossItem;
        if (st.target == null) return;
        FakeTransform oldTransform = pushIdentityTransform(gra);
        try {
            gra.setComposite(FakeGraphics.DEF, 0, 0);
            long bossPoint = bossBodyPoint(st.target, bbpainter);
            int tx = unpackX(bossPoint);
            int ty = unpackY(bossPoint);
            int active = activeChainCount(st);
            drawBossAura(gra, tx, ty, active, impactPulse(st));
            for (int i = 0; i < st.absorbs.size(); i++) {
                Absorb a = st.absorbs.get(i);
                int impactAge = st.frame - a.impactFrame;
                if (impactAge >= 0 && impactAge < IMPACT_VISIBLE_FRAMES) {
                    drawImpactBurst(gra, tx, ty, impactAge, a.seed);
                }
                if (a.removed || a.entity == null || st.frame < a.chainStartFrame) continue;
                long unitPoint = spriteAttachPoint(a.entity, bbpainter, tx, ty);
                int ux = unpackX(unitPoint);
                int uy = unpackY(unitPoint);
                long sourcePoint = bossChainSource(st.target, bbpainter, ux, uy);
                int sx = unpackX(sourcePoint);
                int sy = unpackY(sourcePoint);
                if (st.frame < a.pinEndFrame) {
                    long tip = pinTipPoint(a, st.frame, sx, sy, ux, uy);
                    int px = unpackX(tip);
                    int py = unpackY(tip);
                    float pin = pinProgress(a, st.frame);
                    drawPinShotChain(gra, sx, sy, px, py, sx, sy, ux, uy, a.seed, pin);
                    if (pin >= 0.86f) drawHookClamp(gra, px, py, sx, sy, a.seed, 0.65f);
                    continue;
                }
                float tension = chainTension(a, st.frame);
                if (st.frame < a.yankStartFrame) {
                    float snap = st.frame < a.holdEndFrame ? 0f
                            : easeOutQuart(clamp01((st.frame - a.holdEndFrame)
                            / (float) Math.max(1, a.snapEndFrame - a.holdEndFrame)));
                    drawSlackChain(gra, sx, sy, ux, uy, a.seed, snap, Math.round(124f + 42f * tension));
                    drawHookClamp(gra, ux, uy, sx, sy, a.seed, tension);
                    continue;
                }
                drawTautChain(gra, sx, sy, ux, uy, a.seed, tension, Math.round(142f + 36f * tension));
                drawHookClamp(gra, ux, uy, sx, sy, a.seed, tension);
                if (st.frame >= a.yankStartFrame) {
                    drawSpeedStreaks(gra, ux, uy, sx, sy, a.seed, yankProgress(a, st.frame));
                }
            }
        } finally {
            try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
            popTransform(gra, oldTransform);
        }
    }

    private static long pinTipPoint(Absorb a, int frame, int sx, int sy, int ux, int uy) {
        float p = easeOutQuart(pinProgress(a, frame));
        float sag = slackSag(sx, sy, ux, uy) * 0.36f;
        float x = lerp(sx, ux, p);
        float y = lerp(sy, uy, p) + (float) Math.sin(Math.PI * p) * sag;
        return packPoint(Math.round(x), Math.round(y));
    }

    private static void drawPinShotChain(FakeGraphics gra, int sx, int sy, int tipX, int tipY,
                                         int fullSX, int fullSY, int ux, int uy, int seed, float p) {
        float sag = slackSag(fullSX, fullSY, ux, uy) * 0.28f * (0.25f + 0.75f * p);
        drawGlowChainCurve(gra, sx, sy, tipX, tipY, sag, seed, Math.round(132f + 48f * p), false);
        int spark = Math.max(2, Math.round(3f + 3f * p));
        fillDisc(gra, tipX, tipY, spark, 255, 198, 54, alphaScale(190, 0.65f + 0.25f * p));
    }

    private static void drawSlackChain(FakeGraphics gra, int sx, int sy, int ux, int uy, int seed,
                                       float snap, int alpha) {
        float sag = slackSag(sx, sy, ux, uy) * (1f - clamp01(snap));
        float jitter = (float) Math.sin(seed * 0.19f + snap * 5.2f) * (5f + 7f * (1f - snap));
        drawGlowChainCurve(gra, sx, sy, ux, uy, Math.max(0f, sag + jitter), seed, alpha, true);
    }

    private static void drawTautChain(FakeGraphics gra, int sx, int sy, int ux, int uy, int seed,
                                      float tension, int alpha) {
        float sag = Math.max(0f, slackSag(sx, sy, ux, uy) * 0.05f * (1f - tension));
        drawGlowChainCurve(gra, sx, sy, ux, uy, sag, seed, alpha, true);
    }

    private static void drawGlowChainCurve(FakeGraphics gra, int x0, int y0, int x1, int y1,
                                           float sag, int seed, int alpha, boolean hot) {
        drawCurvePass(gra, x0, y0, x1, y1, sag, seed, 7, 110, 0, 0, alphaScale(alpha, 0.10f));
        drawCurvePass(gra, x0, y0, x1, y1, sag, seed, 5, 210, 0, 0, alphaScale(alpha, 0.16f));
        drawCurvePass(gra, x0, y0, x1, y1, sag, seed, 2, 255, 42, 24, alphaScale(alpha, 0.46f));
        drawCurveCoreSegments(gra, x0, y0, x1, y1, sag, seed, alpha);
        if (!hot) return;
        long a0 = curvePoint(x0, y0, x1, y1, sag, 0.04f);
        long a1 = curvePoint(x0, y0, x1, y1, sag, 0.25f);
        drawRectLine(gra, unpackX(a0), unpackY(a0), unpackX(a1), unpackY(a1),
                2, 255, 198, 54, alphaScale(alpha, 0.46f));
        long b0 = curvePoint(x0, y0, x1, y1, sag, 0.80f);
        long b1 = curvePoint(x0, y0, x1, y1, sag, 0.99f);
        drawRectLine(gra, unpackX(b0), unpackY(b0), unpackX(b1), unpackY(b1),
                2, 255, 126, 30, alphaScale(alpha, 0.52f));
    }

    private static void drawCurvePass(FakeGraphics gra, int x0, int y0, int x1, int y1, float sag,
                                      int seed, int thickness, int r, int g, int b, int alpha) {
        if (alpha <= 0) return;
        int segments = 7;
        long prev = curvePoint(x0, y0, x1, y1, sag, 0f);
        for (int i = 1; i <= segments; i++) {
            float t = i / (float) segments;
            long next = curvePoint(x0, y0, x1, y1, sag, t);
            drawRectLine(gra, unpackX(prev), unpackY(prev), unpackX(next), unpackY(next),
                    thickness, r, g, b, alpha);
            prev = next;
        }
    }

    private static void drawCurveCoreSegments(FakeGraphics gra, int x0, int y0, int x1, int y1,
                                              float sag, int seed, int alpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
        int parts = Math.max(5, Math.min(22, Math.round(len / 24f)));
        for (int i = 0; i < parts; i++) {
            float a = i / (float) parts;
            float b = Math.min(1f, (i + 0.58f) / (float) parts);
            long p0 = curvePoint(x0, y0, x1, y1, sag, a);
            long p1 = curvePoint(x0, y0, x1, y1, sag, b);
            boolean dark = ((i + seed) & 1) == 0;
            drawRectLine(gra, unpackX(p0), unpackY(p0), unpackX(p1), unpackY(p1),
                    dark ? 2 : 1, dark ? 18 : 255, dark ? 0 : 62, dark ? 0 : 34,
                    dark ? alphaScale(alpha, 0.54f) : alphaScale(alpha, 0.64f));
        }
    }

    private static long curvePoint(int x0, int y0, int x1, int y1, float sag, float t) {
        t = clamp01(t);
        float x = lerp(x0, x1, t);
        float y = lerp(y0, y1, t) + (float) Math.sin(Math.PI * t) * sag;
        return packPoint(Math.round(x), Math.round(y));
    }

    private static float slackSag(int x0, int y0, int x1, int y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        return clampFloat(34f + len * 0.16f + Math.max(0f, Math.abs(dy) * 0.08f), 42f, 138f);
    }

    private static long bossBodyPoint(AbEntity boss, Object bbpainter) {
        SpriteBox box = spriteBox(boss);
        if (box != null) return packPoint(Math.round(box.bodyCX), Math.round(box.bodyCY));
        float rootX = CrazyRender.screenX(bbpainter, boss.pos);
        float rootY = targetGroundY(bbpainter, boss);
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            EntityAccess.SpriteBounds b = estimateBounds(boss, siz, rootX, rootY);
            if (validBounds(b)) return scaledBoundsCenter(b, rootX, rootY, visualScaleForTarget(boss));
        } catch (Throwable ignored) {}
        return packPoint(Math.round(rootX), Math.round(rootY - 55f));
    }

    private static long baseBodyPoint(AbEntity base, Object bbpainter) {
        if (base == null) return packPoint(0, 0);
        if (base.dire == -1 && base.isBase()) {
            CrazyRuntime.StageRuntime rt = runtimeFromPainter(bbpainter);
            BaseBossGeometry geo = baseBossGeometry(rt, bbpainter, base);
            if (geo != null && geo.scaledBounds != null) {
                float bodyX = geo.scaledBounds.centerX();
                float bodyY = geo.scaledBounds.top + (geo.scaledBounds.bottom - geo.scaledBounds.top) * 0.43f;
                return packPoint(Math.round(bodyX), Math.round(bodyY));
            }
        }
        float rootX = CrazyRender.screenX(bbpainter, base.pos);
        float rootY = targetGroundY(bbpainter, base);
        float scale = visualScaleForTarget(base);
        float x = rootX + (base.dire == -1 ? 48f : -48f) * safeSiz(bbpainter) * scale;
        float y = rootY - (92f + 22f * Math.max(0f, scale - 1f)) * safeSiz(bbpainter);
        return packPoint(Math.round(x), Math.round(y));
    }

    private static long baseMuzzlePoint(AbEntity base, Object bbpainter) {
        if (base == null) return packPoint(0, 0);
        if (base.dire == -1 && base.isBase()) {
            CrazyRuntime.StageRuntime rt = runtimeFromPainter(bbpainter);
            BaseBossGeometry geo = baseBossGeometry(rt, bbpainter, base);
            if (geo != null) return geo.muzzlePoint;
        }
        long c = baseBodyPoint(base, bbpainter);
        return packPoint(unpackX(c) + (base.dire == -1 ? -42 : 42), unpackY(c) - 18);
    }

    private static long scaledPlayerBasePoint(Object bbpainter, float x, float y, float siz, float scale,
                                              float localX, float localY) {
        CrazyRuntime.StageRuntime rt = runtimeFromPainter(bbpainter);
        float pivotX = playerBaseFootPivotX(rt, x, siz);
        float pivotY = playerBaseFootPivotY(rt, y, siz);
        float px = x + localX * siz;
        float py = y + localY * siz;
        float sx = pivotX + (px - pivotX) * scale;
        float sy = pivotY + (py - pivotY) * scale;
        return packPoint(Math.round(sx), Math.round(sy));
    }

    private static long scaledScreenPoint(float x, float y, float pivotX, float pivotY, float scale) {
        float sx = pivotX + (x - pivotX) * scale;
        float sy = pivotY + (y - pivotY) * scale;
        return packPoint(Math.round(sx), Math.round(sy));
    }

    private static BaseBossGeometry baseBossGeometry(CrazyRuntime.StageRuntime rt, Object bbpainter, AbEntity base) {
        if (base == null || bbpainter == null) return null;
        float siz = safeSiz(bbpainter);
        float psiz = siz * 0.8f;
        float rootX = CrazyRender.screenX(bbpainter, base.pos);
        float rootY = base.dire == -1 && base.isBase() ? baseAnchorY(bbpainter) : targetGroundY(bbpainter, base);
        float scale = visualScaleForTarget(base);
        BaseVisualBounds visible;
        float pivotX;
        float pivotY;
        if (base.dire == -1 && base.isBase()) {
            visible = playerBaseVisibleBounds(rt, rootX, rootY, siz);
            pivotX = visible.centerX();
            pivotY = visible.bottom;
        } else {
            EntityAccess.SpriteBounds b = estimateBounds(base, siz, rootX, rootY);
            visible = new BaseVisualBounds();
            visible.left = b.left;
            visible.top = b.top;
            visible.right = b.right;
            visible.bottom = b.bottom;
            visible.valid = true;
            pivotX = visible.centerX();
            pivotY = visible.bottom;
        }
        BaseVisualBounds scaled = scaledBaseBounds(visible, pivotX, pivotY, scale);
        long muzzle = base.dire == -1 && base.isBase()
                ? playerBaseMuzzlePoint(rt, rootX, rootY, siz, pivotX, pivotY, scale, visible)
                : packPoint(Math.round(scaled.centerX()), Math.round(scaled.top + (scaled.bottom - scaled.top) * 0.34f));
        AuraCircle shield = shieldFromBounds(scaled, siz);
        BaseBossGeometry geo = new BaseBossGeometry(base, siz, psiz, rootX, rootY, scale,
                pivotX, pivotY, visible, scaled, muzzle, shield);
        for (int i = 0; i < geo.nativeCannonOrigins.length; i++) {
            float ox = rootX + BASE_CANNON_X[i] * siz;
            float oy = rootY + BASE_CANNON_Y[i] * siz;
            geo.nativeCannonOrigins[i] = scaledScreenPoint(ox, oy, pivotX, pivotY, scale);
        }
        return geo;
    }

    private static BaseVisualBounds scaledBaseBounds(BaseVisualBounds b, float pivotX, float pivotY, float scale) {
        BaseVisualBounds out = new BaseVisualBounds();
        if (b == null || !b.valid) return out;
        float l = pivotX + (b.left - pivotX) * scale;
        float r = pivotX + (b.right - pivotX) * scale;
        float t = pivotY + (b.top - pivotY) * scale;
        float bot = pivotY + (b.bottom - pivotY) * scale;
        out.left = Math.min(l, r);
        out.right = Math.max(l, r);
        out.top = Math.min(t, bot);
        out.bottom = Math.max(t, bot);
        out.valid = true;
        return out;
    }

    private static AuraCircle shieldFromBounds(BaseVisualBounds b, float siz) {
        if (b == null || !b.valid) return new AuraCircle(0, 0, 1);
        float w = Math.max(1f, b.right - b.left);
        float h = Math.max(1f, b.bottom - b.top);
        int cx = Math.round((b.left + b.right) * 0.5f);
        int cy = Math.round((b.top + b.bottom) * 0.5f);
        int r = Math.round((float) Math.sqrt(w * w + h * h) * 0.5f + Math.max(18f, 26f * siz));
        return new AuraCircle(cx, cy, Math.max(36, r));
    }

    private static long playerBaseMuzzlePoint(CrazyRuntime.StageRuntime rt, float rootX, float rootY,
                                              float siz, float pivotX, float pivotY, float scale,
                                              BaseVisualBounds visible) {
        long local = playerBaseMuzzleLocal(rt);
        if (local != NO_POINT) {
            float x = rootX + unpackX(local) * siz;
            float y = rootY + unpackY(local) * siz;
            return scaledScreenPoint(x, y, pivotX, pivotY, scale);
        }
        float x = visible.left + (visible.right - visible.left) * 0.285f;
        float y = visible.top + (visible.bottom - visible.top) * 0.485f;
        return scaledScreenPoint(x, y, pivotX, pivotY, scale);
    }

    private static long playerBaseMuzzleLocal(CrazyRuntime.StageRuntime rt) {
        int[] nyc = playerBaseNyc(rt);
        if (nyc == null || nyc.length < 3) return NO_POINT;
        String key = nyc[0] + ":" + nyc[1] + ":" + nyc[2];
        Long cached = playerBaseMuzzleCache.get(key);
        if (cached != null) return cached.longValue();
        long scanned = scanPlayerBaseMuzzleLocal(nyc);
        playerBaseMuzzleCache.put(key, scanned);
        return scanned;
    }

    private static long scanPlayerBaseMuzzleLocal(int[] nyc) {
        try {
            CommonStatic.BCAuxAssets aux = CommonStatic.getBCAssets();
            BaseVisualBounds b = playerBaseVisibleBounds(nyc, 0f, 0f, 1f);
            MuzzleCandidate best = new MuzzleCandidate();
            scanMuzzleLayer(aux.main[2][nyc[2]].getImg(), 0, -130, b, best);
            scanMuzzleLayer(aux.main[0][nyc[0]].getImg(), 0, -258, b, best);
            scanMuzzleLayer(aux.main[1][nyc[1]].getImg(), 0, -130, b, best);
            if (!best.found) return NO_POINT;
            return muzzleClusterCenter(nyc, b, best.x, best.y);
        } catch (Throwable ignored) {
            return NO_POINT;
        }
    }

    private static void scanMuzzleLayer(FakeImage img, int offX, int offY, BaseVisualBounds b, MuzzleCandidate best) {
        if (img == null || b == null || !b.valid) return;
        AlphaBounds ab = alphaBounds(img);
        if (ab == null || !ab.valid) return;
        for (int y = ab.minY; y <= ab.maxY; y++) {
            for (int x = ab.minX; x <= ab.maxX; x++) {
                int lx = offX + x;
                int ly = offY + y;
                int score = muzzlePixelScore(img, x, y, lx, ly, b);
                if (score > Integer.MIN_VALUE) best.offer(lx, ly, score);
            }
        }
    }

    private static int muzzlePixelScore(FakeImage img, int x, int y, int lx, int ly, BaseVisualBounds b) {
        if (!inMuzzleSearchRegion(lx, ly, b)) return Integer.MIN_VALUE;
        int argb;
        try {
            argb = img.getRGB(x, y);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
        if (!darkVisiblePixel(argb)) return Integer.MIN_VALUE;
        int r = (argb >>> 16) & 255;
        int g = (argb >>> 8) & 255;
        int bl = argb & 255;
        int darkness = 255 - (r + g + bl) / 3;
        float w = Math.max(1f, b.right - b.left);
        float h = Math.max(1f, b.bottom - b.top);
        float nx = (lx - b.left) / w;
        float ny = (ly - b.top) / h;
        int leftBias = Math.round((0.58f - nx) * 90f);
        int yBias = Math.round((0.34f - Math.abs(ny - 0.43f)) * 120f);
        int density = darkNeighborhood(img, x, y) * 10;
        return darkness + leftBias + yBias + density;
    }

    private static boolean inMuzzleSearchRegion(int lx, int ly, BaseVisualBounds b) {
        float w = Math.max(1f, b.right - b.left);
        float h = Math.max(1f, b.bottom - b.top);
        return lx >= b.left + w * 0.03f && lx <= b.left + w * 0.56f
                && ly >= b.top + h * 0.20f && ly <= b.top + h * 0.62f;
    }

    private static boolean darkVisiblePixel(int argb) {
        int a = (argb >>> 24) & 255;
        if (a <= 72) return false;
        int r = (argb >>> 16) & 255;
        int g = (argb >>> 8) & 255;
        int b = argb & 255;
        int max = Math.max(r, Math.max(g, b));
        int bright = (r + g + b) / 3;
        return bright < 118 && max < 150;
    }

    private static int darkNeighborhood(FakeImage img, int x, int y) {
        int count = 0;
        int w = img.getWidth();
        int h = img.getHeight();
        for (int dy = -5; dy <= 5; dy += 2) {
            int py = y + dy;
            if (py < 0 || py >= h) continue;
            for (int dx = -5; dx <= 5; dx += 2) {
                int px = x + dx;
                if (px < 0 || px >= w) continue;
                try {
                    if (darkVisiblePixel(img.getRGB(px, py))) count++;
                } catch (Throwable ignored) {}
            }
        }
        return count;
    }

    private static long muzzleClusterCenter(int[] nyc, BaseVisualBounds b, int cx, int cy) {
        try {
            CommonStatic.BCAuxAssets aux = CommonStatic.getBCAssets();
            int[] box = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, 0};
            collectMuzzleCluster(aux.main[2][nyc[2]].getImg(), 0, -130, b, cx, cy, box);
            collectMuzzleCluster(aux.main[0][nyc[0]].getImg(), 0, -258, b, cx, cy, box);
            collectMuzzleCluster(aux.main[1][nyc[1]].getImg(), 0, -130, b, cx, cy, box);
            if (box[4] >= 8) {
                return packPoint(Math.round((box[0] + box[2]) * 0.5f), Math.round((box[1] + box[3]) * 0.5f));
            }
        } catch (Throwable ignored) {}
        return packPoint(cx, cy);
    }

    private static void collectMuzzleCluster(FakeImage img, int offX, int offY, BaseVisualBounds b,
                                             int cx, int cy, int[] box) {
        if (img == null || box == null) return;
        AlphaBounds ab = alphaBounds(img);
        if (ab == null || !ab.valid) return;
        int radius2 = 34 * 34;
        for (int y = ab.minY; y <= ab.maxY; y++) {
            for (int x = ab.minX; x <= ab.maxX; x++) {
                int lx = offX + x;
                int ly = offY + y;
                int dx = lx - cx;
                int dy = ly - cy;
                if (dx * dx + dy * dy > radius2) continue;
                if (!inMuzzleSearchRegion(lx, ly, b)) continue;
                try {
                    if (!darkVisiblePixel(img.getRGB(x, y))) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                if (lx < box[0]) box[0] = lx;
                if (ly < box[1]) box[1] = ly;
                if (lx > box[2]) box[2] = lx;
                if (ly > box[3]) box[3] = ly;
                box[4]++;
            }
        }
    }

    private static BaseVisualBounds playerBaseVisibleBounds(CrazyRuntime.StageRuntime rt, float x, float y, float siz) {
        return playerBaseVisibleBounds(playerBaseNyc(rt), x, y, siz);
    }

    private static BaseVisualBounds playerBaseVisibleBounds(int[] nyc, float x, float y, float siz) {
        siz = Math.max(0.05f, siz);
        BaseVisualBounds out = new BaseVisualBounds();
        try {
            if (nyc != null) {
                CommonStatic.BCAuxAssets aux = CommonStatic.getBCAssets();
                out.include(aux.main[2][nyc[2]].getImg(), x, y - 130f * siz, siz);
                out.include(aux.main[0][nyc[0]].getImg(), x, y - 258f * siz, siz);
                out.include(aux.main[1][nyc[1]].getImg(), x, y - 130f * siz, siz);
            }
        } catch (Throwable ignored) {}
        if (!out.valid) {
            out.left = x;
            out.top = y - 258f * siz;
            out.right = x + 180f * siz;
            out.bottom = y + 126f * siz;
            out.valid = true;
        }
        return out;
    }

    private static AlphaBounds alphaBounds(FakeImage img) {
        if (img == null) return null;
        synchronized (alphaBoundsCache) {
            AlphaBounds cached = alphaBoundsCache.get(img);
            if (cached != null) return cached;
        }
        AlphaBounds result;
        try {
            int w = Math.max(0, img.getWidth());
            int h = Math.max(0, img.getHeight());
            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = (img.getRGB(x, y) >>> 24) & 255;
                    if (a <= ALPHA_THRESHOLD) continue;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
            result = maxX >= minX && maxY >= minY
                    ? new AlphaBounds(minX, minY, maxX, maxY, true)
                    : new AlphaBounds(0, 0, Math.max(0, w - 1), Math.max(0, h - 1), false);
        } catch (Throwable ignored) {
            result = new AlphaBounds(0, 0, 0, 0, false);
        }
        synchronized (alphaBoundsCache) {
            alphaBoundsCache.put(img, result);
        }
        return result;
    }

    private static float baseAnchorY(Object bbpainter) {
        try {
            return BBPainterAccess.getMidh(bbpainter) - 156f * BBPainterAccess.getSiz(bbpainter);
        } catch (Throwable ignored) {
            return CrazyRender.groundY(bbpainter, 0);
        }
    }

    private static long targetImpactPoint(AbEntity target, Object bbpainter, float sx, float sy) {
        if (target == null) return packPoint(Math.round(sx), Math.round(sy));
        if (target instanceof Entity) return spriteAttachPoint((Entity) target, bbpainter, sx, sy);
        return baseBodyPoint(target, bbpainter);
    }

    private static long bossChainSource(AbEntity boss, Object bbpainter, float unitX, float unitY) {
        SpriteBox box = spriteBox(boss);
        if (box != null) return pointInsideSpriteBox(box, unitX, unitY, 0.46f);
        float rootX = CrazyRender.screenX(bbpainter, boss.pos);
        float rootY = targetGroundY(bbpainter, boss);
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            EntityAccess.SpriteBounds b = estimateBounds(boss, siz, rootX, rootY);
            if (validBounds(b)) {
                float scale = visualScaleForTarget(boss);
                float left = rootX + (b.left - rootX) * scale;
                float right = rootX + (b.right - rootX) * scale;
                float top = rootY + (b.top - rootY) * scale;
                float bottom = rootY + (b.bottom - rootY) * scale;
                if (left > right) { float t = left; left = right; right = t; }
                if (top > bottom) { float t = top; top = bottom; bottom = t; }
                float cx = (left + right) * 0.5f;
                float cy = (top + bottom) * 0.5f;
                long edge = nearestRectPoint(left, top, right, bottom, unitX, unitY);
                float ex = unpackX(edge);
                float ey = unpackY(edge);
                return packPoint(Math.round(lerp(ex, cx, 0.58f)), Math.round(lerp(ey, cy, 0.58f)));
            }
        } catch (Throwable ignored) {}
        float dx = unitX - rootX;
        float dy = unitY - rootY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) len = 1f;
        return packPoint(Math.round(rootX + dx / len * 18f), Math.round(rootY - 55f + dy / len * 18f));
    }

    private static long spriteAttachPoint(Entity entity, Object bbpainter, float bossX, float bossY) {
        SpriteBox box = spriteBox(entity);
        if (box != null) return pointInsideSpriteBox(box, bossX, bossY, 0.14f);
        float rootX = CrazyRender.screenX(bbpainter, entity.pos);
        float rootY = CrazyRender.groundY(bbpainter, EntityAccess.getLayer(entity));
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            EntityAccess.SpriteBounds b = EntityAccess.estimateSpriteBounds(entity, siz, rootX, rootY);
            if (validBounds(b)) return pointInsideBounds(b.left, b.top, b.right, b.bottom, b.centerX, b.centerY, bossX, bossY, 0.12f);
        } catch (Throwable ignored) {}
        float fallbackX = rootX + (bossX >= rootX ? 42f : -42f) * Math.max(0.75f, safeSiz(bbpainter));
        return packPoint(Math.round(fallbackX), Math.round(rootY - 42f));
    }

    private static int targetLayer(AbEntity target) {
        if (target instanceof Entity) {
            try { return EntityAccess.getLayer(target); } catch (Throwable ignored) {}
        }
        return target != null && target.dire == -1 ? 9 : 0;
    }

    private static float targetGroundY(Object bbpainter, AbEntity target) {
        if (target != null && target.isBase()) return baseAnchorY(bbpainter);
        return CrazyRender.groundY(bbpainter, targetLayer(target));
    }

    private static float visualScaleForTarget(AbEntity target) {
        if (target == null) return 1f;
        if (target instanceof Entity) return drawScaleFor(target);
        CrazyRuntime.StageRuntime rt = runtimeForAbEntity(target);
        BossBaseStats stats = rt == null ? null : rt.bossItem.baseBosses.get(target);
        return stats == null ? 1f : baseBossDrawScale(stats);
    }

    private static EntityAccess.SpriteBounds estimateBounds(AbEntity target, float siz, float rootX, float rootY) {
        if (target instanceof Entity) return EntityAccess.estimateSpriteBounds(target, siz, rootX, rootY);
        float s = Math.max(0.5f, siz);
        float w = 210f * s;
        float h = 258f * s;
        if (target != null && target.dire == -1) {
            return makeBounds(rootX, rootY - h, rootX + w, rootY + 18f * s);
        }
        return makeBounds(rootX - w, rootY - h, rootX, rootY + 18f * s);
    }

    private static EntityAccess.SpriteBounds makeBounds(float left, float top, float right, float bottom) {
        try {
            java.lang.reflect.Constructor<EntityAccess.SpriteBounds> c =
                    EntityAccess.SpriteBounds.class.getDeclaredConstructor(float.class, float.class, float.class, float.class);
            c.setAccessible(true);
            return c.newInstance(left, top, right, bottom);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SpriteBox spriteBox(Object entity) {
        if (entity == null) return null;
        synchronized (spriteBoxes) {
            SpriteBox box = spriteBoxes.get(entity);
            if (box == null || !box.fresh()) return null;
            return box;
        }
    }

    private static long pointInsideSpriteBox(SpriteBox b, float towardX, float towardY, float inward) {
        long alpha = pointInsideAlphaSprite(b, towardX, towardY);
        if (alpha != NO_POINT) return alpha;
        return pointInsideBounds(b.minX, b.minY, b.maxX, b.maxY, b.bodyCX, b.bodyCY, towardX, towardY, inward);
    }

    private static long pointInsideAlphaSprite(SpriteBox b, float towardX, float towardY) {
        if (b == null || b.parts.isEmpty()) return NO_POINT;
        long best = NO_POINT;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < b.parts.size(); i++) {
            SpriteAlphaPart part = b.parts.get(i);
            long p = alphaRayHit(part, towardX, towardY);
            if (p == NO_POINT) continue;
            float d = dist2(unpackX(p), unpackY(p), towardX, towardY);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private static long alphaRayHit(SpriteAlphaPart part, float towardX, float towardY) {
        if (part == null || part.image == null || part.alpha == null || !part.alpha.valid || part.matrix == null) {
            return NO_POINT;
        }
        int iw;
        int ih;
        try {
            iw = Math.max(1, part.image.getWidth());
            ih = Math.max(1, part.image.getHeight());
        } catch (Throwable ignored) {
            return NO_POINT;
        }
        long localToward = inverseProject(part.matrix, towardX, towardY);
        if (localToward == NO_POINT) return NO_POINT;
        float startX = Float.intBitsToFloat(unpackX(localToward));
        float startY = Float.intBitsToFloat(unpackY(localToward));
        float centerU = (part.alpha.minX + part.alpha.maxX + 1f) * 0.5f;
        float centerV = (part.alpha.minY + part.alpha.maxY + 1f) * 0.5f;
        float centerX = part.drawX + centerU / iw * part.drawW;
        float centerY = part.drawY + centerV / ih * part.drawH;
        int steps = 96;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float lx = lerp(startX, centerX, t);
            float ly = lerp(startY, centerY, t);
            int u = Math.round((lx - part.drawX) / part.drawW * iw);
            int v = Math.round((ly - part.drawY) / part.drawH * ih);
            if (!visiblePixel(part, u, v)) continue;
            float dx = centerU - u;
            float dy = centerV - v;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            int iu = u;
            int iv = v;
            if (len > 0.001f) {
                int tu = Math.round(u + dx / len * 4f);
                int tv = Math.round(v + dy / len * 4f);
                if (visiblePixel(part, tu, tv)) {
                    iu = tu;
                    iv = tv;
                }
            }
            float hitX = part.drawX + (iu + 0.5f) / iw * part.drawW;
            float hitY = part.drawY + (iv + 0.5f) / ih * part.drawH;
            return project(part.matrix, hitX, hitY);
        }
        return NO_POINT;
    }

    private static boolean visiblePixel(SpriteAlphaPart part, int u, int v) {
        if (u < part.alpha.minX || u > part.alpha.maxX || v < part.alpha.minY || v > part.alpha.maxY) return false;
        try {
            int a = (part.image.getRGB(u, v) >>> 24) & 255;
            return a > ALPHA_THRESHOLD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long project(float[] m, float lx, float ly) {
        float x = m[0] * lx + m[1] * ly + m[2];
        float y = m[3] * lx + m[4] * ly + m[5];
        return packPoint(Math.round(x), Math.round(y));
    }

    private static long inverseProject(float[] m, float sx, float sy) {
        if (m == null || m.length < 6) return NO_POINT;
        float det = m[0] * m[4] - m[1] * m[3];
        if (Math.abs(det) < 0.00001f) return NO_POINT;
        float dx = sx - m[2];
        float dy = sy - m[5];
        float lx = (m[4] * dx - m[1] * dy) / det;
        float ly = (-m[3] * dx + m[0] * dy) / det;
        return packPoint(Float.floatToIntBits(lx), Float.floatToIntBits(ly));
    }

    private static long pointInsideBounds(float left, float top, float right, float bottom,
                                          float bodyCX, float bodyCY, float towardX, float towardY, float inward) {
        long edge = nearestRectPoint(left, top, right, bottom, towardX, towardY);
        float ex = unpackX(edge);
        float ey = unpackY(edge);
        float cx = clampFloat(bodyCX, left, right);
        float cy = clampFloat(bodyCY, top, bottom);
        inward = clamp01(inward);
        float x = lerp(ex, cx, inward);
        float y = lerp(ey, cy, inward);
        float marginX = Math.min(18f, Math.max(2f, (right - left) * 0.08f));
        float marginY = Math.min(18f, Math.max(2f, (bottom - top) * 0.08f));
        if (right - left > marginX * 2f) x = clampFloat(x, left + marginX, right - marginX);
        else x = clampFloat(x, left, right);
        if (bottom - top > marginY * 2f) y = clampFloat(y, top + marginY, bottom - marginY);
        else y = clampFloat(y, top, bottom);
        return packPoint(Math.round(x), Math.round(y));
    }

    private static boolean validBounds(EntityAccess.SpriteBounds b) {
        if (b == null) return false;
        float w = b.right - b.left;
        float h = b.bottom - b.top;
        return w > 2f && h > 2f && w < 6000f && h < 6000f;
    }

    private static long nearestBoundsPoint(EntityAccess.SpriteBounds b, float x, float y) {
        return nearestRectPoint(b.left, b.top, b.right, b.bottom, x, y);
    }

    private static long nearestRectPoint(float left, float top, float right, float bottom, float x, float y) {
        float cx = clampFloat(x, left, right);
        float cy = clampFloat(y, top, bottom);
        float bestX = cx;
        float bestY = top;
        float bestD = dist2(x, y, bestX, bestY);
        float d = dist2(x, y, cx, bottom);
        if (d < bestD) { bestD = d; bestX = cx; bestY = bottom; }
        d = dist2(x, y, left, cy);
        if (d < bestD) { bestD = d; bestX = left; bestY = cy; }
        d = dist2(x, y, right, cy);
        if (d < bestD) { bestX = right; bestY = cy; }
        return packPoint(Math.round(bestX), Math.round(bestY));
    }

    private static long scaledBoundsCenter(EntityAccess.SpriteBounds b, float rootX, float rootY, float scale) {
        float left = rootX + (b.left - rootX) * scale;
        float right = rootX + (b.right - rootX) * scale;
        float top = rootY + (b.top - rootY) * scale;
        float bottom = rootY + (b.bottom - rootY) * scale;
        return packPoint(Math.round((left + right) * 0.5f), Math.round((top + bottom) * 0.5f));
    }

    private static void drawBossAura(FakeGraphics gra, int cx, int cy, int activeChains, float pulse) {
        int base = 24 + Math.min(34, activeChains * 2);
        int glowAlpha = clampInt(Math.round(22f + activeChains * 3f + pulse * 58f), 0, 115);
        fillDisc(gra, cx, cy, base + 18, 150, 0, 0, glowAlpha);
        strokeDisc(gra, cx, cy, base + 8 + Math.round(pulse * 16f), 3, 255, 54, 34, alphaScale(glowAlpha, 0.72f));
        strokeDisc(gra, cx, cy, base + 20 + Math.round(pulse * 28f), 2, 255, 198, 54, alphaScale(glowAlpha, 0.42f));
    }

    private static void drawGlowChain(FakeGraphics gra, int x0, int y0, int x1, int y1, int seed,
                                      float tension, int alpha) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        float len = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
        if (len < 1f) return;
        float nx = -dy / len;
        float ny = dx / len;
        float wobble = (float) Math.sin((seed * 0.17f) + tension * 4.5f) * (5f + 11f * tension);
        int mx = Math.round((x0 + x1) * 0.5f + nx * wobble);
        int my = Math.round((y0 + y1) * 0.5f + ny * wobble);

        drawChainLine(gra, x0, y0, mx, my, x1, y1, 10, 110, 0, 0, alphaScale(alpha, 0.16f));
        drawChainLine(gra, x0, y0, mx, my, x1, y1, 7, 210, 0, 0, alphaScale(alpha, 0.22f));
        drawChainLine(gra, x0, y0, mx, my, x1, y1, 4, 255, 34, 22, alphaScale(alpha, 0.48f));
        drawChainCoreSegments(gra, x0, y0, mx, my, seed, alpha);
        drawChainCoreSegments(gra, mx, my, x1, y1, seed + 31, alpha);

        float hot = 0.26f + 0.22f * tension;
        int hx = Math.round(lerp(x0, x1, hot));
        int hy = Math.round(lerp(y0, y1, hot));
        drawRectLine(gra, x0, y0, hx, hy, 2, 255, 198, 54, alphaScale(alpha, 0.52f));
        int ux = Math.round(lerp(x0, x1, 0.82f));
        int uy = Math.round(lerp(y0, y1, 0.82f));
        drawRectLine(gra, ux, uy, x1, y1, 3, 255, 120, 30, alphaScale(alpha, 0.58f));
    }

    private static void drawChainLine(FakeGraphics gra, int x0, int y0, int mx, int my, int x1, int y1,
                                      int thickness, int r, int g, int b, int alpha) {
        drawRectLine(gra, x0, y0, mx, my, thickness, r, g, b, alpha);
        drawRectLine(gra, mx, my, x1, y1, thickness, r, g, b, alpha);
    }

    private static void drawChainCoreSegments(FakeGraphics gra, int x0, int y0, int x1, int y1, int seed, int alpha) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        float len = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
        int segments = Math.max(2, Math.min(18, Math.round(len / 26f)));
        for (int i = 0; i < segments; i++) {
            float a = i / (float) segments;
            float b = (i + 0.58f) / (float) segments;
            int sx = Math.round(lerp(x0, x1, a));
            int sy = Math.round(lerp(y0, y1, a));
            int ex = Math.round(lerp(x0, x1, b));
            int ey = Math.round(lerp(y0, y1, b));
            boolean dark = ((i + seed) & 1) == 0;
            drawRectLine(gra, sx, sy, ex, ey, dark ? 3 : 2,
                    dark ? 16 : 255, dark ? 0 : 58, dark ? 0 : 34,
                    dark ? alphaScale(alpha, 0.58f) : alphaScale(alpha, 0.68f));
        }
    }

    private static void drawHookClamp(FakeGraphics gra, int ux, int uy, int tx, int ty, int seed, float tension) {
        int dx = ux - tx;
        int dy = uy - ty;
        float len = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
        if (len < 1f) len = 1f;
        float nx = -dy / len;
        float ny = dx / len;
        int arm = 10 + Math.round(8f * tension);
        int ax0 = Math.round(ux + nx * arm);
        int ay0 = Math.round(uy + ny * arm);
        int ax1 = Math.round(ux - nx * arm);
        int ay1 = Math.round(uy - ny * arm);
        drawRectLine(gra, ax0, ay0, ax1, ay1, 3, 20, 0, 0, 155);
        drawRectLine(gra, ax0, ay0, ax1, ay1, 2, 255, 78, 34, 180);
        fillDisc(gra, ux, uy, 3 + ((seed & 1) == 0 ? 1 : 0), 255, 198, 54, 150);
    }

    private static void drawSpeedStreaks(FakeGraphics gra, int ux, int uy, int tx, int ty, int seed, float p) {
        int dx = ux - tx;
        int dy = uy - ty;
        float len = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
        if (len < 1f) return;
        float bx = dx / len;
        float by = dy / len;
        float nx = -by;
        float ny = bx;
        float force = 1f - p;
        for (int i = 0; i < 4; i++) {
            float side = ((i & 1) == 0 ? 1f : -1f) * (5f + i * 4f);
            int sx = Math.round(ux + nx * side);
            int sy = Math.round(uy + ny * side);
            int ex = Math.round(sx + bx * (24f + i * 13f + force * 24f));
            int ey = Math.round(sy + by * (24f + i * 13f + force * 24f));
            drawRectLine(gra, sx, sy, ex, ey, Math.max(1, 4 - i), 255, i == 0 ? 198 : 68,
                    i == 0 ? 54 : 34, alphaScale(150, (0.70f - i * 0.11f) * force));
        }
    }

    private static void drawImpactBurst(FakeGraphics gra, int cx, int cy, int age, int seed) {
        float p = clamp01(age / (float) IMPACT_VISIBLE_FRAMES);
        float fade = 1f - p;
        int alpha = alphaScale(255, fade);
        int r = 14 + Math.round(p * 54f);
        strokeDisc(gra, cx, cy, r, 5, 255, 54, 34, alphaScale(alpha, 0.80f));
        strokeDisc(gra, cx, cy, r + 12, 3, 255, 198, 54, alphaScale(alpha, 0.50f));
        int slash = 24 + Math.round(p * 45f);
        int skew = ((seed & 1) == 0) ? 1 : -1;
        drawRectLine(gra, cx - slash, cy - slash / 2 * skew, cx + slash, cy + slash / 2 * skew,
                6, 255, 198, 54, alphaScale(alpha, 0.75f));
        drawRectLine(gra, cx + slash / 2 * skew, cy - slash, cx - slash / 2 * skew, cy + slash,
                5, 255, 42, 22, alphaScale(alpha, 0.68f));
        fillDisc(gra, cx, cy, Math.max(2, 7 - age / 2), 255, 230, 150, alphaScale(alpha, 0.78f));
    }

    private static void drawIcon(FakeGraphics gra, int cx, int cy, int size, boolean enabled, boolean used, float opacity) {
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) {
            FakeTransform oldTransform = pushIdentityTransform(gra);
            try {
                gra.setComposite(FakeGraphics.DEF, 0, 0);
                drawIconFallback(gra, cx, cy, size, enabled, used, opacity);
                gra.setComposite(FakeGraphics.DEF, 0, 0);
            } finally {
                try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
                popTransform(gra, oldTransform);
            }
            return;
        }
        AffineTransform oldTransform = g.getTransform();
        java.awt.Composite oldComposite = g.getComposite();
        Color oldColor = g.getColor();
        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(opacity)));
            float pulse = enabled ? (float) Math.sin(System.currentTimeMillis() * 0.006) * 0.08f + 1f : 1f;
            int r = Math.round(size * 0.5f * pulse);
            Color glow = enabled ? new Color(255, 36, 20, 72) : new Color(80, 80, 80, 52);
            Color core = enabled ? new Color(58, 8, 12) : new Color(58, 58, 58);
            Color rim = enabled ? new Color(255, 198, 54) : new Color(140, 140, 140);
            Color hot = enabled ? new Color(255, 54, 34) : new Color(95, 95, 95);

            g.setColor(glow);
            g.fillOval(cx - r - 8, cy - r - 8, r * 2 + 16, r * 2 + 16);
            g.setColor(core);
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
            g.setStroke(new BasicStroke(Math.max(3f, size * 0.07f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(rim);
            g.drawOval(cx - r, cy - r, r * 2, r * 2);
            g.setStroke(new BasicStroke(Math.max(2f, size * 0.04f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(hot);
            g.drawOval(cx - r + 6, cy - r + 6, r * 2 - 12, r * 2 - 12);

            Path2D horns = new Path2D.Float();
            horns.moveTo(cx - size * 0.28f, cy - size * 0.17f);
            horns.lineTo(cx - size * 0.48f, cy - size * 0.38f);
            horns.lineTo(cx - size * 0.16f, cy - size * 0.28f);
            horns.moveTo(cx + size * 0.28f, cy - size * 0.17f);
            horns.lineTo(cx + size * 0.48f, cy - size * 0.38f);
            horns.lineTo(cx + size * 0.16f, cy - size * 0.28f);
            g.setStroke(new BasicStroke(Math.max(3f, size * 0.075f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(rim);
            g.draw(horns);

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(16, Math.round(size * 0.38f))));
            String label = used ? "X" : "B";
            java.awt.FontMetrics fm = g.getFontMetrics();
            int tx = cx - fm.stringWidth(label) / 2;
            int ty = cy + fm.getAscent() / 2 - 3;
            g.setColor(new Color(0, 0, 0, enabled ? 190 : 120));
            g.drawString(label, tx + 2, ty + 2);
            g.setColor(enabled ? new Color(255, 235, 170) : new Color(190, 190, 190));
            g.drawString(label, tx, ty);
        } finally {
            g.setTransform(oldTransform);
            g.setComposite(oldComposite);
            g.setColor(oldColor);
            g.setStroke(oldStroke);
            g.setFont(oldFont);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    private static void drawIconFallback(FakeGraphics gra, int cx, int cy, int size, boolean enabled, boolean used, float opacity) {
        int alpha = Math.round(255f * clamp01(opacity));
        int pulse = enabled ? Math.round((float) Math.sin(System.currentTimeMillis() * 0.006) * size * 0.04f) : 0;
        int r = Math.max(12, size / 2 + pulse);
        int rim = Math.max(3, size / 14);
        int innerRim = Math.max(2, size / 22);

        fillDisc(gra, cx, cy, r + 8, enabled ? 255 : 80, enabled ? 36 : 80, enabled ? 20 : 80, alphaScale(alpha, enabled ? 0.28f : 0.20f));
        fillDisc(gra, cx + 3, cy + 4, r + 2, 0, 0, 0, alphaScale(alpha, 0.38f));
        fillDisc(gra, cx, cy, r, enabled ? 58 : 58, enabled ? 8 : 58, enabled ? 12 : 58, alpha);
        strokeDisc(gra, cx, cy, r, rim, enabled ? 255 : 140, enabled ? 198 : 140, enabled ? 54 : 140, alpha);
        strokeDisc(gra, cx, cy, Math.max(4, r - 7), innerRim, enabled ? 255 : 95, enabled ? 54 : 95, enabled ? 34 : 95, alphaScale(alpha, 0.9f));
        rect(gra, cx - r / 2, cy - r / 2, r, Math.max(3, size / 9), 255, 255, 255, alphaScale(alpha, enabled ? 0.23f : 0.12f));

        int hornT = Math.max(3, size / 15);
        drawRectLine(gra, cx - Math.round(size * 0.27f), cy - Math.round(size * 0.16f),
                cx - Math.round(size * 0.46f), cy - Math.round(size * 0.35f), hornT,
                enabled ? 255 : 140, enabled ? 198 : 140, enabled ? 54 : 140, alpha);
        drawRectLine(gra, cx - Math.round(size * 0.46f), cy - Math.round(size * 0.35f),
                cx - Math.round(size * 0.17f), cy - Math.round(size * 0.27f), hornT,
                enabled ? 255 : 140, enabled ? 198 : 140, enabled ? 54 : 140, alpha);
        drawRectLine(gra, cx + Math.round(size * 0.27f), cy - Math.round(size * 0.16f),
                cx + Math.round(size * 0.46f), cy - Math.round(size * 0.35f), hornT,
                enabled ? 255 : 140, enabled ? 198 : 140, enabled ? 54 : 140, alpha);
        drawRectLine(gra, cx + Math.round(size * 0.46f), cy - Math.round(size * 0.35f),
                cx + Math.round(size * 0.17f), cy - Math.round(size * 0.27f), hornT,
                enabled ? 255 : 140, enabled ? 198 : 140, enabled ? 54 : 140, alpha);

        if (used) {
            drawBlockX(gra, cx + 2, cy + 2, size, 0, 0, 0, alphaScale(alpha, 0.75f));
            drawBlockX(gra, cx, cy, size, enabled ? 255 : 190, enabled ? 235 : 190, enabled ? 170 : 190, alpha);
        } else {
            drawBlockB(gra, cx + 2, cy + 2, size, 0, 0, 0, alphaScale(alpha, 0.75f));
            drawBlockB(gra, cx, cy, size, enabled ? 255 : 190, enabled ? 235 : 190, enabled ? 170 : 190, alpha);
        }
    }

    private static void fillDisc(FakeGraphics gra, int cx, int cy, int radius, int r, int g, int b, int alpha) {
        int rr = Math.max(1, radius);
        for (int dy = -rr; dy <= rr; dy++) {
            int span = (int) Math.floor(Math.sqrt((double) rr * rr - (double) dy * dy));
            rect(gra, cx - span, cy + dy, span * 2 + 1, 1, r, g, b, alpha);
        }
    }

    private static void strokeDisc(FakeGraphics gra, int cx, int cy, int radius, int thickness, int r, int g, int b, int alpha) {
        int outer = Math.max(1, radius);
        int inner = Math.max(0, outer - Math.max(1, thickness));
        for (int dy = -outer; dy <= outer; dy++) {
            int outerSpan = (int) Math.floor(Math.sqrt((double) outer * outer - (double) dy * dy));
            if (Math.abs(dy) > inner || inner == 0) {
                rect(gra, cx - outerSpan, cy + dy, outerSpan * 2 + 1, 1, r, g, b, alpha);
                continue;
            }
            int innerSpan = (int) Math.floor(Math.sqrt((double) inner * inner - (double) dy * dy));
            rect(gra, cx - outerSpan, cy + dy, outerSpan - innerSpan, 1, r, g, b, alpha);
            rect(gra, cx + innerSpan + 1, cy + dy, outerSpan - innerSpan, 1, r, g, b, alpha);
        }
    }

    private static void drawRectLine(FakeGraphics gra, int x0, int y0, int x1, int y1, int thickness,
                                     int r, int g, int b, int alpha) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(1, (int) (Math.sqrt((double) dx * dx + (double) dy * dy) / 3.0));
        int dot = Math.max(2, thickness);
        int half = dot / 2;
        for (int i = 0; i <= steps; i++) {
            float p = i / (float) steps;
            int x = Math.round(x0 + dx * p);
            int y = Math.round(y0 + dy * p);
            rect(gra, x - half, y - half, dot, dot, r, g, b, alpha);
        }
    }

    private static void drawBlockB(FakeGraphics gra, int cx, int cy, int size, int r, int g, int b, int alpha) {
        int w = Math.max(14, size / 4);
        int h = Math.max(19, size / 3);
        int t = Math.max(3, size / 13);
        int x = cx - w / 2;
        int y = cy - h / 2;
        rect(gra, x, y, t, h, r, g, b, alpha);
        rect(gra, x, y, w - t, t, r, g, b, alpha);
        rect(gra, x, y + h / 2 - t / 2, w - t, t, r, g, b, alpha);
        rect(gra, x, y + h - t, w - t, t, r, g, b, alpha);
        rect(gra, x + w - t, y + t, t, h / 2 - t, r, g, b, alpha);
        rect(gra, x + w - t, y + h / 2 + t / 2, t, h / 2 - t, r, g, b, alpha);
    }

    private static void drawBlockX(FakeGraphics gra, int cx, int cy, int size, int r, int g, int b, int alpha) {
        int half = Math.max(8, size / 6);
        int t = Math.max(4, size / 12);
        drawRectLine(gra, cx - half, cy - half, cx + half, cy + half, t, r, g, b, alpha);
        drawRectLine(gra, cx + half, cy - half, cx - half, cy + half, t, r, g, b, alpha);
    }

    private static void rect(FakeGraphics gra, int x, int y, int w, int h, int r, int g, int b, int alpha) {
        if (w <= 0 || h <= 0 || alpha <= 0) return;
        gra.colRect(x, y, w, h, clampInt(r, 0, 255), clampInt(g, 0, 255), clampInt(b, 0, 255), clampInt(alpha, 0, 255));
    }

    private static int alphaScale(int alpha, float scale) {
        return clampInt(Math.round(alpha * scale), 0, 255);
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

    private static boolean shouldFinishCinematic(State st) {
        if (st == null || !st.cinematicActive) return false;
        if (!allAbsorbedRemoved(st)) return false;
        int impactReady = st.lastImpactFrame < 0 ? st.totalFrames : st.lastImpactFrame + FINISH_BUFFER_FRAMES;
        return st.frame >= st.totalFrames && st.frame >= impactReady;
    }

    private static boolean allAbsorbedRemoved(State st) {
        for (int i = 0; i < st.absorbs.size(); i++) {
            Absorb a = st.absorbs.get(i);
            if (a != null && !a.removed && a.entity != null) return false;
        }
        return true;
    }

    private static float bossCinematicScale(State st) {
        int impacts = clampInt(st.impactCount, 0, Math.max(0, st.absorbedCount));
        if (impacts <= 0) return 1f;
        float prev = 1f + SCALE_PER_ABSORBED * Math.max(0, impacts - 1);
        float target = 1f + SCALE_PER_ABSORBED * impacts;
        int age = st.frame - st.lastImpactFrame;
        if (age >= 0 && age < SCALE_STEP_FRAMES) {
            float p = easeOutBack(age / (float) Math.max(1, SCALE_STEP_FRAMES));
            return safeScale(lerp(prev, target, p) + impactPulse(st) * 0.035f);
        }
        return safeScale(target);
    }

    private static void clearSpriteBoxes(State st) {
        if (st == null) return;
        synchronized (spriteBoxes) {
            if (st.target != null) spriteBoxes.remove(st.target);
            for (int i = 0; i < st.absorbs.size(); i++) {
                Absorb a = st.absorbs.get(i);
                if (a != null && a.entity != null) spriteBoxes.remove(a.entity);
            }
        }
    }

    private static void restoreCamera(CrazyRuntime.StageRuntime rt, State st) {
        if (rt == null || st == null || !st.cameraCaptured) return;
        try {
            BCUFields.setInt(rt.stage, "pos", st.cameraOriginalPos);
        } catch (Throwable ignored) {
        } finally {
            st.cameraCaptured = false;
            st.cameraOriginalPos = 0;
        }
    }

    private static int cameraShake(State st) {
        int age = st.frame - st.lastImpactFrame;
        if (age < 0 || age >= 8) return 0;
        float fade = 1f - age / 8f;
        int amp = Math.round(22f * fade * fade);
        return ((st.impactCount + age) & 1) == 0 ? amp : -amp;
    }

    private static float impactPulse(State st) {
        int age = st.frame - st.lastImpactFrame;
        if (age < 0 || age >= BOSS_PULSE_FRAMES) return 0f;
        float fade = 1f - age / (float) BOSS_PULSE_FRAMES;
        return fade * fade;
    }

    private static float baseBossPulse(BossBaseStats stats) {
        return 0f;
    }

    private static float baseBossDrawScale(BossBaseStats stats) {
        if (stats == null) return 1f;
        float grow = clamp01(stats.frame / (float) Math.max(1, stats.growthFrames));
        float base = lerp(1f, stats.scale, easeOutQuart(grow));
        if (grow < 1f) return safeScale(base);
        return safeScale(stats.scale);
    }

    private static float yankProgress(Absorb a, int frame) {
        return clamp01((frame - a.yankStartFrame) / (float) Math.max(1, a.impactFrame - a.yankStartFrame));
    }

    private static float pinProgress(Absorb a, int frame) {
        return clamp01((frame - a.chainStartFrame) / (float) Math.max(1, a.pinEndFrame - a.chainStartFrame));
    }

    private static float chainTension(Absorb a, int frame) {
        if (frame < a.pinEndFrame) {
            return 0.18f + 0.32f * pinProgress(a, frame);
        }
        if (frame < a.holdEndFrame) {
            return 0.28f + 0.07f * Math.max(0f, (float) Math.sin((frame + a.seed) * 0.23f));
        }
        if (frame < a.snapEndFrame) {
            float p = clamp01((frame - a.holdEndFrame) / (float) Math.max(1, a.snapEndFrame - a.holdEndFrame));
            return 0.34f + 0.66f * easeOutQuart(p);
        }
        return 1f - yankProgress(a, frame) * 0.28f;
    }

    private static int activeChainCount(State st) {
        int count = 0;
        for (int i = 0; i < st.absorbs.size(); i++) {
            Absorb a = st.absorbs.get(i);
            if (!a.removed && st.frame >= a.chainStartFrame && st.frame < a.impactFrame) count++;
        }
        return count;
    }

    private static int stableSeed(Object entity, int order) {
        int h = System.identityHashCode(entity);
        h ^= order * 0x45d9f3b;
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 16;
        return h & 0x7fffffff;
    }

    private static long packPoint(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static int unpackX(long p) {
        return (int) (p >> 32);
    }

    private static int unpackY(long p) {
        return (int) p;
    }

    private static float dist2(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        return dx * dx + dy * dy;
    }

    private static float clampFloat(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float safeSiz(Object bbpainter) {
        try {
            return Math.max(0.5f, BBPainterAccess.getSiz(bbpainter));
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private static CrazyRuntime.StageRuntime runtimeForEntity(Object entity) {
        if (!(entity instanceof Entity)) return null;
        return CrazyRuntime.get(((Entity) entity).basis);
    }

    private static CrazyRuntime.StageRuntime runtimeForAbEntity(Object entity) {
        if (entity instanceof Entity) return CrazyRuntime.get(((Entity) entity).basis);
        if (!(entity instanceof AbEntity)) return null;
        try {
            Object stage = BCUFields.get(entity, "sb");
            return CrazyRuntime.get(stage);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CrazyRuntime.StageRuntime runtimeFromPainter(Object bbpainter) {
        try {
            return CrazyRuntime.get(BBPainterAccess.getStageBasis(bbpainter));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int[] playerBaseNyc(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return null;
        try {
            return ((StageBasis) rt.stage).nyc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean enabled(CrazyRuntime.StageRuntime rt) {
        return rt != null && rt.config.bossItem;
    }

    private static boolean touchable(Entity e, int touch) {
        try {
            return (e.touchable() & touch) > 0;
        } catch (Throwable ignored) {
            return (touch & 1) != 0;
        }
    }

    private static float scaledHitboxExtra(Entity e, float scale) {
        float width = 120f;
        try { width = Math.max(40f, e.data.getWidth()); } catch (Throwable ignored) {}
        return width * 0.5f * Math.max(0f, scale - 1f);
    }

    private static long totalDamage(Entity e) {
        if (e == null) return 0L;
        long sum = 0L;
        try {
            Object aam = BCUFields.get(e, "aam");
            int[] atks = (int[]) BCUFields.get(aam, "atks");
            int n = Math.min(e.data.getAtkCount(), atks.length);
            for (int i = 0; i < n; i++) {
                if (atks[i] > 0) sum = safeAdd(sum, atks[i]);
            }
        } catch (Throwable ignored) {}
        return Math.max(0L, sum);
    }

    private static float safeScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return 1f;
        return Math.max(1f, Math.min(Float.MAX_VALUE / 4f, scale));
    }

    private static int clampInt(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long safeAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        if (b < 0L && a < Long.MIN_VALUE - b) return Long.MIN_VALUE;
        return a + b;
    }

    private static long safeMul2(long v) {
        if (v > Long.MAX_VALUE / 2L) return Long.MAX_VALUE;
        if (v < Long.MIN_VALUE / 2L) return Long.MIN_VALUE;
        return v * 2L;
    }

    private static long safeMul(long a, long b) {
        if (a == 0L || b == 0L) return 0L;
        if (a > 0L && b > 0L && a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
        if (a < 0L && b < 0L && a < Long.MAX_VALUE / b) return Long.MAX_VALUE;
        if (a > 0L && b < 0L && b < Long.MIN_VALUE / a) return Long.MIN_VALUE;
        if (a < 0L && b > 0L && a < Long.MIN_VALUE / b) return Long.MIN_VALUE;
        return a * b;
    }

    private static boolean between(float v, float a, float b) {
        return v >= Math.min(a, b) && v <= Math.max(a, b);
    }

    private static int baseCannonDamage(StageBasis sb) {
        try {
            Object treasure = sb.b.t();
            java.lang.reflect.Method m = treasure.getClass().getMethod("getCanonAtk", boolean.class);
            Object val = m.invoke(treasure, false);
            int atk = val instanceof Number ? ((Number) val).intValue() : 3000;
            return Math.max(3000, atk * Math.max(1, sb.cannonMultiplier()) / 100);
        } catch (Throwable ignored) {
            return 3000;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeInOut(float v) {
        v = clamp01(v);
        return v * v * (3f - 2f * v);
    }

    private static float easeOutBack(float v) {
        v = clamp01(v);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float t = v - 1f;
        return 1f + c3 * t * t * t + c1 * t * t;
    }

    private static float easeInBack(float v) {
        v = clamp01(v);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return c3 * v * v * v - c1 * v * v;
    }

    private static float snapPullEase(float v) {
        v = clamp01(v);
        float q = easeOutQuart(v);
        float back = easeInBack(v);
        float snap = q * 0.84f + back * 0.16f + (float) Math.sin(Math.PI * v) * 0.07f;
        return clamp01(snap);
    }

    private static float easeOutQuart(float v) {
        v = 1f - clamp01(v);
        return 1f - v * v * v * v;
    }

    private static float lerp(float a, float b, float p) {
        return a + (b - a) * p;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
