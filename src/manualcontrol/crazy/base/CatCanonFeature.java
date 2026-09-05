package manualcontrol.crazy.base;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.attack.AtkModelEntity;
import common.battle.attack.AttackSimple;
import common.battle.data.MaskAtk;
import common.battle.entity.AbEntity;
import common.battle.entity.ECastle;
import common.battle.entity.Entity;
import common.battle.entity.EUnit;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import common.util.Data;
import common.util.anim.AnimU;
import common.util.unit.EForm;
import common.util.unit.Form;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.hooks.BoundsRecorder;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class CatCanonFeature {

    private static final int FIRE_INTERVAL = 300;
    private static final float FAN_DEGREES = 120f;
    private static final float ENEMY_BASE_DAMAGE_MULT = 0.05f;
    private static final float PROJECTILE_SCREEN_SPEED = 30f;
    private static final float PROJECTILE_TARGET_SIZE = 72f;
    private static final float PROJECTILE_INITIAL_SCALE = 0.58f;
    private static final float PROJECTILE_MIN_SCALE = 0.10f;
    private static final float PROJECTILE_MAX_SCALE = 1.35f;
    private static final int SHARD_FRAMES = 34;
    private static final int SHARD_COUNT = 16;
    private static final int ALPHA_THRESHOLD = 40;

    private static final int RECOIL_FRAMES = 12;
    private static final float SPRING_STIFF = 0.25f;
    private static final float SPRING_DAMP = 0.78f;
    private static final float KICK_FIRE = 0.18f;
    private static final int TRAIL_LEN = 6;
    private static final int IMPACT_FLASH_FRAMES = 14;
    private static final long BOX_MAX_AGE_MS = 240L;
    private static final float MUZZLE_DX = 108f;
    private static final float MUZZLE_DY = -154f;
    private static final float CEILING_LAYER = -115f;

    private static final short[] CANNON_Y = {-134, -134, -134, -250, -250, -134, -134, -134};
    private static final byte[] CANNON_X = {0, 0, 0, 64, 64, 0, 0, 0};
    private static final float[] CANNON_MUZZLE_DX = {28f, 28f, 28f, 28f, 28f, 28f, 28f, 28f};
    private static final float[] CANNON_MUZZLE_DY = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    private static final float CANNON_SPRITE_SCALE = 0.8f;

    private static final float GAUGE_WIDTH = 15f;
    private static final float GAUGE_HEIGHT = 105f;
    private static final float GAUGE_DX = 150f;
    private static final float GAUGE_BOTTOM_DY = -4f;
    private static final float FRAME_MARGIN = 6f;
    private static final int AMMO_ROWS = 4;
    private static final float RACK_CELL = 28f;
    private static final float RACK_GAP = 6f;

    private static final Map<FakeImage, float[]> ALPHA_BOUNDS_CACHE =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, float[]>());
    private static Object animUTypeWalk;
    private static java.lang.reflect.Method setAnimMethod;
    private static java.lang.reflect.Method animUpdateMethod;
    private static boolean animCacheInitialized;

    private CatCanonFeature() {}

    public static final class State {
        public final Object lock = new Object();
        public final ArrayList<Ammo> ammo = new ArrayList<Ammo>();
        public final ArrayList<Projectile> projectiles = new ArrayList<Projectile>();
        public final ArrayList<Shard> shards = new ArrayList<Shard>();
        public final ArrayList<Flash> impactFlashes = new ArrayList<Flash>();
        public float springPos = 1f;
        public float springVel;
        public int recoilFrame;
        public int animFrame;
        public float castlePivotX;
        public float castlePivotY;
        public boolean castlePivotSet;
        public final Map<Object, Projectile> byEntity = new WeakHashMap<Object, Projectile>();
        public final Map<Object, BoxRec> boxes = new WeakHashMap<Object, BoxRec>();
        public final Map<Object, Boolean> tracked = new WeakHashMap<Object, Boolean>();
        public int fireTimer;
        public int nextAmmoSerial;
        public boolean baseAnchorValid;
        public float baseAnchorX;
        public float baseAnchorY;
        public float baseSiz = 1f;
        public boolean transformValid;
        public float lastSiz = 1f;
        public int lastStagePos;
        public int lastMidh;
        public int lastWidth = 1280;
        public int lastHeight = 720;
        public int muzzleFlash;
    }

    private static final class Ammo {
        final Form form;
        final EForm eform;
        final FakeImage icon;
        final int row;
        final int col;
        final int cost;
        final int cooldown;
        final int serial;

        Ammo(Form form, EForm eform, FakeImage icon, int row, int col, int cost, int cooldown, int serial) {
            this.form = form;
            this.eform = eform;
            this.icon = icon;
            this.row = row;
            this.col = col;
            this.cost = cost;
            this.cooldown = cooldown;
            this.serial = serial;
        }
    }

    private static final class Projectile {
        final Ammo ammo;
        final EUnit unit;
        final int originalLayer;
        float x;
        float layerY;
        float vx;
        float vy;
        float drawScale = PROJECTILE_INITIAL_SCALE;
        boolean scaleResolved;

        final float[] trailX = new float[TRAIL_LEN];
        final float[] trailY = new float[TRAIL_LEN];
        float trailDim = 24f;
        int trailHead = -1;
        int trailCount;

        Projectile(Ammo ammo, EUnit unit, int originalLayer, float x, float layerY, float vx, float vy) {
            this.ammo = ammo;
            this.unit = unit;
            this.originalLayer = originalLayer;
            this.x = x;
            this.layerY = layerY;
            this.vx = vx;
            this.vy = vy;
        }
    }

    private static final class BoxRec {
        float minX, minY, maxX, maxY, cx, cy;
        long timeMs;

        boolean overlaps(BoxRec other) {
            return other != null && minX <= other.maxX && maxX >= other.minX
                    && minY <= other.maxY && maxY >= other.minY;
        }
    }

    private static final class Shard {
        float x;
        float y;
        float vx;
        float vy;
        float size;
        int age;
        Color color;
    }

    private static final class Flash {
        float x;
        float y;
        float size;
        int age;
    }

    private static final class Transform {
        final float siz;
        final int stagePos;
        final int midh;
        final int width;
        final int height;

        Transform(float siz, int stagePos, int midh, int width, int height) {
            this.siz = siz;
            this.stagePos = stagePos;
            this.midh = midh;
            this.width = width;
            this.height = height;
        }
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return false;
        synchronized (rt.catCanon.lock) {
            return !rt.catCanon.projectiles.isEmpty() || !rt.catCanon.shards.isEmpty();
        }
    }

    public static void removeProjectilesFromRange(CrazyRuntime.StageRuntime rt, List<?> list, int dire) {
        if (rt == null || list == null || dire != -1) return;
        synchronized (rt.catCanon.lock) {
            if (rt.catCanon.byEntity.isEmpty()) return;
            Iterator<?> it = list.iterator();
            while (it.hasNext()) {
                if (rt.catCanon.byEntity.containsKey(it.next())) it.remove();
            }
        }
    }

    public static int beforeSpawn(CrazyRuntime.StageRuntime rt, int row, int col, boolean manual) {
        if (rt == null || !rt.config.catCanonBase) return -1;
        StageBasis sb = (StageBasis) rt.stage;

        boolean locked = false;
        try { locked = sb.locks[row][col]; } catch (Throwable ignored) {}
        if (!manual && !locked) return -1;
        try {
            String denial = whyCannotLoad(sb, row, col);
            if (denial != null) {
                if (manual) {
                    reject();
                    logRejectOnce(row, col, denial);
                }
                return 0;
            }
            EForm eform = sb.b.lu.efs[row][col];
            Form form = sb.b.lu.fs[row][col];
            int cost = Math.max(0, sb.elu.price[row][col]);
            int cooldown = Math.max(0, sb.elu.maxC[row][col]);
            FakeImage icon = iconFor(form);
            synchronized (rt.catCanon.lock) {
                rt.catCanon.ammo.add(new Ammo(form, eform, icon, row, col, cost, cooldown,
                        rt.catCanon.nextAmmoSerial++));
                if (rt.catCanon.ammo.size() == 1) rt.catCanon.fireTimer = 0;
            }
            sb.money -= cost;
            sb.unitRespawnTime = 1;
            sb.elu.cool[row][col] = cooldown;
            decrementDeployLimit(sb);
            markSpiritIfNeeded(sb, form, row, col);
            try { CommonStatic.setSE(19); } catch (Throwable ignored) {}
            Logger.log("Cat Canon loaded row=" + row + " col=" + col
                    + " ammo=" + rt.catCanon.ammo.size() + " cost=" + cost);
            return 1;
        } catch (Throwable t) {
            Logger.err("Cat Canon load failed", t);
            if (manual) reject();
            return 0;
        }
    }

    private static boolean canLoad(StageBasis sb, int row, int col) {
        return whyCannotLoad(sb, row, col) == null;
    }

    private static String whyCannotLoad(StageBasis sb, int row, int col) {
        if (sb == null || row < 0 || row >= 2 || col < 0 || col >= 5) return "bounds";
        if (sb.ubase == null) return "ubase-null";
        if (sb.ubase.health == 0L) return "ubase-dead";
        if (sb.unitRespawnTime > 0) return "respawn";
        EForm eform = sb.b == null || sb.b.lu == null ? null : sb.b.lu.efs[row][col];
        Form form = sb.b == null || sb.b.lu == null ? null : sb.b.lu.fs[row][col];
        if (eform == null) return "eform-null";
        if (form == null) return "form-null";
        if (form.du == null) return "du-null";
        if (sb.elu == null) return "elu-null";
        if (sb.elu.price[row][col] < 0) return "price-neg(" + sb.elu.price[row][col] + ")";
        if (sb.elu.cool[row][col] > 0) return "cooldown";
        if (sb.elu.price[row][col] > sb.money) return "money";
        try {
            if (form.du.getProc().SPIRIT.exists() && sb.summonerSummoned[row][col]) return "spirit";
        } catch (Throwable ignored) {}
        return deployLimitReason(sb);
    }

    private static String deployLimitReason(StageBasis sb) {

        try {
            int maxSpawnLeft = intField(sb, "maxCatSpawns", "unitLeft", -1);
            if (maxSpawnLeft == 0) return "maxCatSpawns=0";
        } catch (Throwable ignored) {}
        return null;
    }

    private static String lastDenial;

    private static void logRejectOnce(int row, int col, String reason) {
        String key = row + "," + col + ":" + reason;
        if (key.equals(lastDenial)) return;
        lastDenial = key;
        Logger.log("Cat Canon load rejected row=" + row + " col=" + col + " reason=" + reason);
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        StageBasis sbase = (StageBasis) rt.stage;
        if (sbase != null && (sbase.ubase == null || sbase.ubase.health <= 0L)) {
            releaseOnDefeat(rt);
            return;
        }
        tickShards(rt);
        tickBaseAnim(rt);
        tickImpactFlashes(rt);
        trackPotentialTargets(rt);
        tickProjectiles(rt);
        if (!rt.config.catCanonBase) return;
        boolean shouldFire = false;
        synchronized (rt.catCanon.lock) {
            if (rt.catCanon.ammo.isEmpty()) {
                rt.catCanon.fireTimer = 0;
            } else {
                rt.catCanon.fireTimer++;
                if (rt.catCanon.fireTimer >= FIRE_INTERVAL) {
                    rt.catCanon.fireTimer = 0;
                    shouldFire = true;
                }
            }
        }
        if (shouldFire) fireVolley(rt);
    }

    private static void fireVolley(CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        ArrayList<Ammo> volley;
        synchronized (rt.catCanon.lock) {
            if (rt.catCanon.ammo.isEmpty()) return;
            volley = new ArrayList<Ammo>(rt.catCanon.ammo);
        }
        float startX = muzzleWorldX(rt, sb);
        float startLayer = muzzleLayer(rt, sb);
        int n = volley.size();
        float center = 180f;
        float span = n <= 1 ? 0f : FAN_DEGREES;
        for (int i = 0; i < n; i++) {
            float t = n <= 1 ? 0.5f : i / (float) (n - 1);
            float deg = center - span * 0.5f + span * t;
            launchProjectile(rt, volley.get(i), startX, startLayer, deg);
        }
        synchronized (rt.catCanon.lock) {
            rt.catCanon.muzzleFlash = 12;
            rt.catCanon.recoilFrame = RECOIL_FRAMES;
            rt.catCanon.springVel += KICK_FIRE;
        }
        try { CommonStatic.setSE(5); } catch (Throwable ignored) {}
        Logger.log("Cat Canon volley fired count=" + n);
    }

    private static void launchProjectile(CrazyRuntime.StageRuntime rt, Ammo ammo, float startX, float startLayer, float deg) {
        if (ammo == null || ammo.eform == null) return;
        StageBasis sb = (StageBasis) rt.stage;
        try {
            EUnit unit = ammo.eform.getEntity(sb, null, false, false);
            unit.added(-1, startX);
            unit.dire = -1;
            unit.pos = startX;
            unit.lastPosition = startX;
            applyStageDamageLimits(sb, unit);
            makeUntouchable(unit);
            setWalkAnim(unit);
            tickAnim(unit);
            int originalLayer = safeLayer(unit);
            EntityAccess.setLayer(unit, Math.round(startLayer));
            try { BCUFields.setInt(unit, "price", ammo.cost); } catch (Throwable ignored) {}
            addEntitySorted(sb, unit);

            double rad = Math.toRadians(deg);
            float vx = (float) Math.cos(rad) * PROJECTILE_SCREEN_SPEED / 0.32f;
            float vy = (float) Math.sin(rad) * PROJECTILE_SCREEN_SPEED / 4f;
            Projectile p = new Projectile(ammo, unit, originalLayer, startX, startLayer, vx, vy);
            synchronized (rt.catCanon.lock) {
                rt.catCanon.projectiles.add(p);
                rt.catCanon.byEntity.put(unit, p);
                rt.catCanon.tracked.put(unit, Boolean.TRUE);
            }
        } catch (Throwable t) {
            Logger.err("Cat Canon projectile launch failed", t);
        }
    }

    private static void tickProjectiles(CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        ArrayList<Projectile> copy;
        synchronized (rt.catCanon.lock) {
            copy = new ArrayList<Projectile>(rt.catCanon.projectiles);
        }
        for (int i = 0; i < copy.size(); i++) {
            Projectile p = copy.get(i);
            if (p == null || p.unit == null || p.unit.dead || p.unit.health <= 0L) {
                removeProjectile(rt, p, true);
                continue;
            }

            p.x += p.vx;
            p.layerY += p.vy;
            bounceWorld(rt, sb, p);
            applyProjectilePosition(p);

            BoxRec projectileBox = freshBox(rt, p.unit);
            pushTrail(rt, p, projectileBox);
            if (projectileBox == null) continue;
            ArrayList<AbEntity> hits = collisionTargets(rt, sb, projectileBox);
            if (!hits.isEmpty()) {
                applyProjectileDamage(sb, p, hits);
                spawnShards(rt, projectileBox);
                spawnImpactFlash(rt, projectileBox);
                removeProjectile(rt, p, true);
            }
        }
    }

    private static ArrayList<AbEntity> collisionTargets(CrazyRuntime.StageRuntime rt, StageBasis sb, BoxRec projectileBox) {
        ArrayList<AbEntity> hits = new ArrayList<AbEntity>();
        try {
            for (int i = 0; i < sb.le.size(); i++) {
                Entity e = sb.le.get(i);
                if (e == null || e.dead || e.health <= 0L || e.dire != 1 || e.isBase()) continue;
                BoxRec eb = freshBox(rt, e);
                if (projectileBox.overlaps(eb)) hits.add(e);
            }
        } catch (Throwable t) {
            Logger.err("Cat Canon collision scan failed", t);
        }
        try {
            if (sb.ebase != null && sb.ebase.health > 0L) {
                BoxRec base = sb.ebase instanceof Entity
                        ? freshBox(rt, (Entity) sb.ebase)
                        : estimatedEnemyBaseBox(rt, sb);
                if (projectileBox.overlaps(base)) hits.add(sb.ebase);
            }
        } catch (Throwable ignored) {}
        return hits;
    }

    private static void applyProjectileDamage(StageBasis sb, Projectile p, List<AbEntity> targets) {
        if (p == null || p.unit == null || targets == null || targets.isEmpty()) return;
        try {

            Object ebase = sb == null ? null : sb.ebase;
            List<AbEntity> normal = new ArrayList<AbEntity>();
            List<AbEntity> baseHit = new ArrayList<AbEntity>();
            for (int i = 0; i < targets.size(); i++) {
                AbEntity t = targets.get(i);
                if (t == null) continue;
                if (t == ebase) baseHit.add(t); else normal.add(t);
            }
            p.unit.pos = averagePos(targets);
            p.unit.lastPosition = p.unit.pos;
            p.unit.dire = -1;
            AtkModelEntity model = (AtkModelEntity) BCUFields.get(p.unit, "aam");
            int count = Math.max(1, p.unit.data.getAtkCount());
            int hpBonus = clampToInt(Math.max(1L, p.unit.maxH));
            boolean bonusAdded = false;
            for (int i = 0; i < count; i++) {
                MaskAtk mask = p.unit.data.getAtkModel(i);
                if (mask == null) continue;
                Data.Proc proc = Data.Proc.blank();
                int atkValue = Math.max(0, catGetAttack(model, i, proc));
                if (!bonusAdded) {
                    atkValue = safeAdd(atkValue, hpBonus);
                    bonusAdded = true;
                }
                if (atkValue <= 0) atkValue = 1;
                if (!normal.isEmpty()) {
                    excuseAttack(p, model, atkValue, proc, mask, i, normal);
                }
                if (!baseHit.isEmpty()) {
                    int baseAtk = Math.max(1, Math.round(atkValue * ENEMY_BASE_DAMAGE_MULT));
                    excuseAttack(p, model, baseAtk, proc, mask, i, baseHit);
                }
            }
            for (int i = 0; i < targets.size(); i++) {
                try { targets.get(i).postUpdate(); } catch (Throwable ignored) {}
            }
            Logger.log("Cat Canon hit targets=" + targets.size() + " base=" + baseHit.size()
                    + " hpBonus=" + hpBonus);
        } catch (Throwable t) {
            Logger.err("Cat Canon damage failed", t);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void excuseAttack(Projectile p, AtkModelEntity model, int atkValue, Data.Proc proc,
                                     MaskAtk mask, int ind, List<AbEntity> targets) {
        try {
            AttackSimple atk = new AttackSimple(p.unit, model, atkValue, p.unit.traits,
                    p.unit.getAbi(), proc, p.unit.pos - 1f, p.unit.pos + 1f, mask,
                    safeLayer(p.unit), false, ind);
            List capt = (List) BCUFields.get(atk, "capt");
            capt.clear();
            capt.addAll(targets);
            atk.excuse();
        } catch (Throwable t) {
            Logger.err("Cat Canon attack excuse failed", t);
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics g) {
        if (rt == null || g == null) return;
        StageBasis sb = (StageBasis) rt.stage;
        if (sb == null || sb.ubase == null || sb.ubase.health <= 0L) return;
        prewarmGlow(rt);
        rememberTransform(rt, bbpainter);
        drawProjectileTrails(rt, g);
        drawMuzzleCharge(rt, bbpainter, g);
        drawMuzzleFlash(rt, bbpainter, g);
        drawShards(rt, g);
        drawImpactFlashes(rt, g);
        drawAmmoRack(rt, bbpainter, g);
        drawFireGauge(rt, bbpainter, g);
    }

    private static float[] baseScreenPos(CrazyRuntime.StageRuntime rt, Object bbpainter) {
        boolean valid;
        float ax, ay, siz;
        synchronized (rt.catCanon.lock) {
            valid = rt.catCanon.baseAnchorValid;
            ax = rt.catCanon.baseAnchorX;
            ay = rt.catCanon.baseAnchorY;
            siz = rt.catCanon.baseSiz <= 0f ? 1f : rt.catCanon.baseSiz;
        }
        if (valid) return new float[]{ax, ay, siz};
        if (bbpainter == null) return null;
        try {
            StageBasis sb = (StageBasis) rt.stage;
            float s = BBPainterAccess.getSiz(bbpainter);
            float bx = CrazyRender.screenX(bbpainter, sb.ubase.pos);
            float by = BBPainterAccess.getMidh(bbpainter) - 156f * s;
            return new float[]{bx, by, s};
        } catch (Throwable t) {
            return null;
        }
    }

    private static float gaugeScreenX(float baseX, float siz) { return baseX + GAUGE_DX * siz; }
    private static float gaugeFootY(float baseY, float siz) { return baseY + GAUGE_BOTTOM_DY * siz; }

    private static void drawAmmoRack(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (gra == null) return;
        ArrayList<Ammo> ammo;
        synchronized (rt.catCanon.lock) {
            if (!rt.config.catCanonBase && !hasActive(rt)) return;
            ammo = new ArrayList<Ammo>(rt.catCanon.ammo);
        }
        if (ammo.isEmpty()) return;
        float[] bp = baseScreenPos(rt, bbpainter);
        if (bp == null) return;
        float baseX = bp[0], baseY = bp[1], siz = bp[2];
        float cell = Math.max(8f, RACK_CELL * siz);
        float pad = cell * 0.16f;
        int count = ammo.size();

        float boxX = gaugeScreenX(baseX, siz) - (cell - GAUGE_WIDTH * siz) * 0.5f;
        float boxY = gaugeFootY(baseY, siz) + RACK_GAP * siz;

        float height = safePainterHeight(bbpainter);
        int maxRows = Math.max(AMMO_ROWS, (int) ((height * 0.95f - boxY - pad * 2f) / cell));
        if (maxRows < 1) maxRows = 1;
        int usedCols = (count + maxRows - 1) / maxRows;
        int usedRows = Math.min(count, maxRows);
        float boxW = usedCols * cell + pad * 2f;
        float boxH = usedRows * cell + pad * 2f;
        float iconSize = cell * 0.86f;
        float bw = Math.max(1.5f, cell * 0.06f);
        gra.setComposite(FakeGraphics.TRANS, 105, 0);
        gra.setColor(14, 16, 22);
        gra.fillRect(boxX, boxY, boxW, boxH);
        gra.setComposite(FakeGraphics.TRANS, 205, 0);
        gra.setColor(255, 211, 88);
        gra.fillRect(boxX, boxY, boxW, bw);
        gra.fillRect(boxX, boxY + boxH - bw, boxW, bw);
        gra.fillRect(boxX, boxY, bw, boxH);
        gra.fillRect(boxX + boxW - bw, boxY, bw, boxH);
        gra.setComposite(FakeGraphics.DEF, 0, 0);
        for (int i = 0; i < count; i++) {
            int c = i / maxRows;
            int r = i % maxRows;
            float ix = boxX + pad + c * cell + (cell - iconSize) * 0.5f;
            float iy = boxY + pad + r * cell + (cell - iconSize) * 0.5f;
            drawIcon(gra, ammo.get(i).icon, ix, iy, iconSize, 1f);
        }
    }

    private static void drawFireGauge(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (gra == null) return;
        int timer, frame;
        boolean armed;
        synchronized (rt.catCanon.lock) {
            timer = rt.catCanon.fireTimer;
            frame = rt.catCanon.animFrame;
            armed = !rt.catCanon.ammo.isEmpty();
        }
        if (!armed) return;
        float[] bp = baseScreenPos(rt, bbpainter);
        if (bp == null) return;
        float baseX = bp[0], baseY = bp[1], siz = bp[2];
        float w = Math.max(4f, GAUGE_WIDTH * siz);
        float h = Math.max(8f, GAUGE_HEIGHT * siz);
        float x = gaugeScreenX(baseX, siz);
        float bottom = gaugeFootY(baseY, siz);
        float top = bottom - h;
        float frac = clamp01(timer / (float) FIRE_INTERVAL);
        boolean ready = frac >= 0.999f;
        float fillH = h * frac;
        float bw = Math.max(1.5f, 2f * siz);
        try {
            gra.setComposite(FakeGraphics.TRANS, 170, 0);
            gra.setColor(18, 20, 26);
            gra.fillRect(x, top, w, h);
            if (fillH > 0f) {
                gra.setComposite(FakeGraphics.TRANS, 235, 0);
                if (ready) gra.setColor(120, 240, 140); else gra.setColor(255, 196, 64);
                gra.fillRect(x, bottom - fillH, w, fillH);
                if (ready) gra.setColor(225, 255, 230); else gra.setColor(255, 235, 160);
                gra.fillRect(x, bottom - fillH, w, Math.max(2f, 2f * siz));
            }
            gra.setComposite(FakeGraphics.TRANS, 245, 0);
            gra.setColor(255, 211, 88);
            gra.fillRect(x, top, w, bw);
            gra.fillRect(x, bottom - bw, w, bw);
            gra.fillRect(x, top, bw, h);
            gra.fillRect(x + w - bw, top, bw, h);

            float glowAmt = clamp01((frac - 0.78f) / 0.22f);
            if (glowAmt > 0.02f) {
                float pulse = 0.6f + 0.4f * (float) Math.sin(frame * 0.5f);
                drawGlowTexAdditive(gra, ready ? getGlowWhite() : getGlowHot(),
                        x + w * 0.5f, bottom - fillH, w * (2.4f + 1.6f * glowAmt) * pulse, Math.round(150 * glowAmt));
            }
        } finally {
            try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
        }
    }

    public static void recordBaseAnchor(CrazyRuntime.StageRuntime rt, float baseX, float baseY, float siz) {
        if (rt == null) return;
        synchronized (rt.catCanon.lock) {
            rt.catCanon.baseAnchorValid = true;
            rt.catCanon.baseAnchorX = baseX;
            rt.catCanon.baseAnchorY = baseY;
            rt.catCanon.baseSiz = siz;
        }
    }

    public static boolean isManaged(Object entity) {
        Projectile p = projectileFor(entity);
        return p != null;
    }

    public static float drawScaleFor(Object entity) {
        Projectile p = projectileFor(entity);
        return p == null ? 1f : p.drawScale;
    }

    public static boolean wantsSpriteBounds(Object entity) {
        if (!(entity instanceof Entity)) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return false;
        synchronized (rt.catCanon.lock) {
            return rt.catCanon.tracked.containsKey(entity);
        }
    }

    public static void recordSpriteBounds(Object entity, float minX, float minY, float maxX, float maxY,
                                          float bodyCX, float bodyCY) {
        if (!(entity instanceof Entity)) return;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return;
        recordBox(rt, entity, minX, minY, maxX, maxY, bodyCX, bodyCY);
        Projectile p = projectileFor(entity);
        if (p != null) updateProjectileScale(p, maxX - minX, maxY - minY);
    }

    public static void recordSpriteParts(Object entity, List<BoundsRecorder.SpritePart> parts) {
        if (!(entity instanceof Entity) || parts == null || parts.isEmpty()) return;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float sumA = 0f, sumAX = 0f, sumAY = 0f;
        boolean any = false;
        int limit = Math.min(64, parts.size());
        for (int i = 0; i < limit; i++) {
            BoundsRecorder.SpritePart p = parts.get(i);
            if (p == null || p.image == null || p.matrix == null || p.w == 0f || p.h == 0f) continue;
            float[] ab = alphaBoundsUV(p.image);
            if (ab == null) continue;
            int iw;
            int ih;
            try {
                iw = p.image.getWidth();
                ih = p.image.getHeight();
            } catch (Throwable ignored) {
                continue;
            }
            if (iw <= 0 || ih <= 0) continue;
            float lx0 = p.x + ab[0] / iw * p.w;
            float ly0 = p.y + ab[1] / ih * p.h;
            float lx1 = p.x + (ab[2] + 1f) / iw * p.w;
            float ly1 = p.y + (ab[3] + 1f) / ih * p.h;
            float[] m = p.matrix;
            float pMinX = Float.MAX_VALUE, pMinY = Float.MAX_VALUE;
            float pMaxX = -Float.MAX_VALUE, pMaxY = -Float.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                float lx = (c == 0 || c == 3) ? lx0 : lx1;
                float ly = (c < 2) ? ly0 : ly1;
                float sx = m[0] * lx + m[1] * ly + m[2];
                float sy = m[3] * lx + m[4] * ly + m[5];
                if (sx < pMinX) pMinX = sx;
                if (sx > pMaxX) pMaxX = sx;
                if (sy < pMinY) pMinY = sy;
                if (sy > pMaxY) pMaxY = sy;
            }
            if (pMinX < minX) minX = pMinX;
            if (pMinY < minY) minY = pMinY;
            if (pMaxX > maxX) maxX = pMaxX;
            if (pMaxY > maxY) maxY = pMaxY;
            float area = (pMaxX - pMinX) * (pMaxY - pMinY);
            if (area > 0f) {
                sumA += area;
                sumAX += area * (pMinX + pMaxX) * 0.5f;
                sumAY += area * (pMinY + pMaxY) * 0.5f;
            }
            any = true;
        }
        if (!any || sumA <= 0f) return;
        recordSpriteBounds(entity, minX, minY, maxX, maxY, sumAX / sumA, sumAY / sumA);
    }

    private static void trackPotentialTargets(CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        synchronized (rt.catCanon.lock) {
            for (int i = 0; i < rt.catCanon.projectiles.size(); i++) {
                Projectile p = rt.catCanon.projectiles.get(i);
                if (p != null && p.unit != null) rt.catCanon.tracked.put(p.unit, Boolean.TRUE);
            }
            try {
                for (int i = 0; i < sb.le.size(); i++) {
                    Entity e = sb.le.get(i);
                    if (e != null && e.dire == 1 && !e.dead && e.health > 0L && !e.isBase()) {
                        rt.catCanon.tracked.put(e, Boolean.TRUE);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void recordBox(CrazyRuntime.StageRuntime rt, Object entity, float minX, float minY, float maxX, float maxY,
                                  float bodyCX, float bodyCY) {
        float w = maxX - minX;
        float h = maxY - minY;
        if (!(w > 2f) || !(h > 2f) || w >= 6000f || h >= 6000f) return;
        if (!(bodyCX >= minX && bodyCX <= maxX)) bodyCX = (minX + maxX) * 0.5f;
        if (!(bodyCY >= minY && bodyCY <= maxY)) bodyCY = (minY + maxY) * 0.5f;
        synchronized (rt.catCanon.lock) {
            BoxRec r = rt.catCanon.boxes.get(entity);
            if (r == null) {
                r = new BoxRec();
                rt.catCanon.boxes.put(entity, r);
            }
            r.minX = minX;
            r.minY = minY;
            r.maxX = maxX;
            r.maxY = maxY;
            r.cx = bodyCX;
            r.cy = bodyCY;
            r.timeMs = System.currentTimeMillis();
        }
    }

    private static BoxRec freshBox(CrazyRuntime.StageRuntime rt, Object entity) {
        synchronized (rt.catCanon.lock) {
            BoxRec r = rt.catCanon.boxes.get(entity);
            if (r == null) return null;
            if (System.currentTimeMillis() - r.timeMs > BOX_MAX_AGE_MS) return null;
            return copyBox(r);
        }
    }

    private static void updateProjectileScale(Projectile p, float w, float h) {

        if (p == null || p.scaleResolved || w <= 2f || h <= 2f) return;
        float dim = Math.max(w, h);
        if (dim <= 1f) return;
        p.drawScale = clamp(p.drawScale * PROJECTILE_TARGET_SIZE / dim,
                PROJECTILE_MIN_SCALE, PROJECTILE_MAX_SCALE);
        p.scaleResolved = true;
    }

    private static Projectile projectileFor(Object entity) {
        if (!(entity instanceof Entity)) return null;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return null;
        synchronized (rt.catCanon.lock) {
            return rt.catCanon.byEntity.get(entity);
        }
    }

    private static void bounceWorld(CrazyRuntime.StageRuntime rt, StageBasis sb, Projectile p) {
        float minX, maxX, ceilLayer, groundLayer;
        float[] b = bounceBounds(rt, sb);
        if (b != null) {
            minX = b[0];
            maxX = b[1];
            ceilLayer = b[2];
            groundLayer = b[3];
        } else {
            minX = 0f;
            maxX = stageLength(sb);
            ceilLayer = CEILING_LAYER;
            groundLayer = Math.max(0f, p.originalLayer);
        }
        if (maxX <= minX) {
            minX = 0f;
            maxX = stageLength(sb);
        }
        if (p.x < minX) {
            p.x = minX + (minX - p.x);
            p.vx = Math.abs(p.vx);
        } else if (p.x > maxX) {
            p.x = maxX - (p.x - maxX);
            p.vx = -Math.abs(p.vx);
        }
        if (p.layerY > groundLayer) {
            p.layerY = groundLayer - (p.layerY - groundLayer);
            p.vy = -Math.abs(p.vy);
        } else if (p.layerY < ceilLayer) {
            p.layerY = ceilLayer + (ceilLayer - p.layerY);
            p.vy = Math.abs(p.vy);
        }
    }

    private static float[] bounceBounds(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        Transform tr;
        synchronized (rt.catCanon.lock) {
            if (!rt.catCanon.transformValid) return null;
            tr = new Transform(rt.catCanon.lastSiz, rt.catCanon.lastStagePos, rt.catCanon.lastMidh,
                    rt.catCanon.lastWidth, rt.catCanon.lastHeight);
        }
        if (tr.siz <= 0f) return null;
        float a = screenToWorldX(FRAME_MARGIN, tr);
        float c = screenToWorldX(tr.width - FRAME_MARGIN, tr);
        float minX = Math.max(0f, Math.min(a, c));
        float maxX = Math.max(a, c);

        maxX = Math.max(maxX, muzzleWorldX(rt, sb) + 40f);
        float ceilLayer = screenToLayer(FRAME_MARGIN, tr);
        return new float[]{minX, maxX, ceilLayer, 0f};
    }

    private static float screenToWorldX(float screenX, Transform tr) {
        return (((screenX - tr.stagePos) / tr.siz) - 200f) / 0.32f;
    }

    private static float screenToLayer(float screenY, Transform tr) {
        return (screenY - tr.midh) / (4f * tr.siz) + 39f;
    }

    private static void applyProjectilePosition(Projectile p) {
        p.unit.pos = p.x;
        p.unit.lastPosition = p.x;
        try { EntityAccess.setLayer(p.unit, Math.round(p.layerY)); } catch (Throwable ignored) {}
        try { BCUFields.field(p.unit.getClass(), "layer").setInt(p.unit, Math.round(p.layerY)); } catch (Throwable ignored) {}
    }

    private static void removeProjectile(CrazyRuntime.StageRuntime rt, Projectile p, boolean removeEntity) {
        if (p == null) return;
        synchronized (rt.catCanon.lock) {
            rt.catCanon.projectiles.remove(p);
            if (p.unit != null) {
                rt.catCanon.byEntity.remove(p.unit);
                rt.catCanon.tracked.remove(p.unit);
                rt.catCanon.boxes.remove(p.unit);
            }
        }
        if (removeEntity && p.unit != null) {
            try { p.unit.kill(Entity.KillMode.NORMAL); } catch (Throwable ignored) {}
            try { ((StageBasis) rt.stage).le.remove(p.unit); } catch (Throwable ignored) {}
        }
    }

    private static void spawnShards(CrazyRuntime.StageRuntime rt, BoxRec box) {
        StageBasis sb = (StageBasis) rt.stage;
        synchronized (rt.catCanon.lock) {
            for (int i = 0; i < SHARD_COUNT; i++) {
                float angle = (float) (Math.PI * 2.0 * i / SHARD_COUNT + sb.r.nextFloat() * 0.25f);
                float speed = 2.0f + sb.r.nextFloat() * 5.5f;
                Shard s = new Shard();
                s.x = box.cx;
                s.y = box.cy;
                s.vx = (float) Math.cos(angle) * speed;
                s.vy = (float) Math.sin(angle) * speed - 2.8f;
                s.size = 4f + sb.r.nextFloat() * 8f;
                int r = 210 + (int) (sb.r.nextFloat() * 45f);
                int g = 160 + (int) (sb.r.nextFloat() * 65f);
                int b = 80 + (int) (sb.r.nextFloat() * 60f);
                s.color = new Color(clamp255(r), clamp255(g), clamp255(b));
                rt.catCanon.shards.add(s);
            }
        }
    }

    private static void tickShards(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.catCanon.lock) {
            Iterator<Shard> it = rt.catCanon.shards.iterator();
            while (it.hasNext()) {
                Shard s = it.next();
                s.age++;
                s.vy += 0.42f;
                s.x += s.vx;
                s.y += s.vy;
                if (s.age >= SHARD_FRAMES) it.remove();
            }
            if (rt.catCanon.muzzleFlash > 0) rt.catCanon.muzzleFlash--;
        }
    }

    private static void drawShards(CrazyRuntime.StageRuntime rt, FakeGraphics gra) {
        if (gra == null) return;
        ArrayList<Shard> copy;
        synchronized (rt.catCanon.lock) {
            if (rt.catCanon.shards.isEmpty()) return;
            copy = new ArrayList<Shard>(rt.catCanon.shards);
        }
        FakeImage ember = getEmber();
        try {
            for (int i = 0; i < copy.size(); i++) {
                Shard s = copy.get(i);
                float a = clamp01(1f - s.age / (float) SHARD_FRAMES);
                if (a <= 0f) continue;
                float size = Math.max(2f, s.size * (0.45f + a * 0.55f));
                if (ember != null) drawGlowTexAdditive(gra, ember, s.x, s.y, size * 2.6f, Math.round(150 * a));
                gra.setComposite(FakeGraphics.TRANS, Math.round(255 * a), 0);
                gra.setColor(s.color.getRed(), s.color.getGreen(), s.color.getBlue());
                gra.fillRect(s.x - size * 0.5f, s.y - size * 0.5f, size, size);
            }
        } finally {
            try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
        }
    }

    private static void drawMuzzleFlash(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        int flash;
        synchronized (rt.catCanon.lock) {
            flash = rt.catCanon.muzzleFlash;
        }
        if (flash <= 0 || gra == null) return;
        float[] m = muzzleScreenPos(rt, bbpainter);
        if (m == null) return;
        float siz = m[2];
        FakeImage hot = getGlowHot(), white = getGlowWhite(), ember = getEmber();
        float p = flash / 12f;
        float grow = 1f - p;
        drawGlowTexAdditive(gra, hot, m[0], m[1], (58f + 46f * grow) * siz, Math.round(150 * p));
        drawGlowTexAdditive(gra, hot, m[0], m[1], (32f + 28f * grow) * siz, Math.round(205 * p));
        drawGlowTexAdditive(gra, white, m[0], m[1], (15f + 15f * grow) * siz, Math.round(235 * p));
        if (ember != null) {
            for (int k = 0; k < 6; k++) {
                float ang = (float) (Math.PI * 2.0 * k / 6.0 + k * 0.4);
                float dist = (10f + 30f * grow) * siz;
                drawGlowTexAdditive(gra, ember, m[0] + (float) Math.cos(ang) * dist,
                        m[1] + (float) Math.sin(ang) * dist * 0.7f, 11f * siz * p, Math.round(190 * p));
            }
        }
    }

    private static void oval(FakeGraphics gra, float cx, float cy, float rx, float ry,
                             int red, int grn, int blu, int alpha) {
        if (gra == null || alpha <= 0 || rx <= 0.5f || ry <= 0.5f) return;
        try {
            gra.setComposite(FakeGraphics.TRANS, Math.max(0, Math.min(255, alpha)), 0);
            gra.setColor(red, grn, blu);
            int iy = Math.max(1, Math.round(ry));
            int ix = Math.max(1, Math.round(rx));
            for (int dy = -iy; dy <= iy; dy++) {
                float yy = dy / (float) iy;
                int span = Math.round(ix * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
                gra.fillRect(cx - span, cy + dy, span * 2 + 1, 1);
            }
        } finally {
            try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
        }
    }

    private static float[] muzzleScreenPos(CrazyRuntime.StageRuntime rt, Object bbpainter) {
        float[] bp = baseScreenPos(rt, bbpainter);
        if (bp == null) return null;
        int id = cannonId((StageBasis) rt.stage);
        float mx = bp[0] + (CANNON_X[id] + CANNON_MUZZLE_DX[id] * CANNON_SPRITE_SCALE) * bp[2];
        float my = bp[1] + (CANNON_Y[id] + CANNON_MUZZLE_DY[id] * CANNON_SPRITE_SCALE) * bp[2];
        return new float[]{mx, my, bp[2]};
    }

    private static void tickBaseAnim(CrazyRuntime.StageRuntime rt) {
        State st = rt.catCanon;
        synchronized (st.lock) {
            st.animFrame++;
            st.springVel += (1f - st.springPos) * SPRING_STIFF;
            st.springVel *= SPRING_DAMP;
            st.springPos += st.springVel;
            if (st.springPos < 0.62f) { st.springPos = 0.62f; if (st.springVel < 0f) st.springVel = 0f; }
            if (st.springPos > 1.35f) { st.springPos = 1.35f; if (st.springVel > 0f) st.springVel = 0f; }
            if (st.recoilFrame > 0) st.recoilFrame--;
        }
    }

    public static float[] baseTransform(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return null;
        State st = rt.catCanon;
        float sy, vel;
        int recoilFrame, frame;
        synchronized (st.lock) {
            sy = st.springPos;
            vel = st.springVel;
            recoilFrame = st.recoilFrame;
            frame = st.animFrame;
        }
        float dev = 1f - sy;
        float recoil = recoilFrame > 0 ? recoilFrame / (float) RECOIL_FRAMES : 0f;
        if (Math.abs(dev) < 0.004f && Math.abs(vel) < 0.002f && recoil <= 0.01f) return null;
        float sx = 1f + dev * 0.6f;
        float ease = recoil * recoil;
        float dx = 10f * ease;
        float shake = Math.max(0f, dev) * 5f;
        if (shake > 0.05f) dx += (float) Math.sin(frame * 1.7f) * shake;
        return new float[]{sx, sy, dx, 0f, -0.03f * ease};
    }

    public static void notifyCastlePivot(CrazyRuntime.StageRuntime rt, float px, float py) {
        if (rt == null) return;
        synchronized (rt.catCanon.lock) {
            rt.catCanon.castlePivotX = px;
            rt.catCanon.castlePivotY = py;
            rt.catCanon.castlePivotSet = true;
        }
    }

    public static float pivotXOr(CrazyRuntime.StageRuntime rt, float fallback) {
        if (rt == null) return fallback;
        synchronized (rt.catCanon.lock) {
            return rt.catCanon.castlePivotSet ? rt.catCanon.castlePivotX : fallback;
        }
    }

    public static float pivotYOr(CrazyRuntime.StageRuntime rt, float fallback) {
        if (rt == null) return fallback;
        synchronized (rt.catCanon.lock) {
            return rt.catCanon.castlePivotSet ? rt.catCanon.castlePivotY : fallback;
        }
    }

    private static void spawnImpactFlash(CrazyRuntime.StageRuntime rt, BoxRec box) {
        Flash f = new Flash();
        f.x = box.cx;
        f.y = box.cy;
        f.size = Math.max(14f, Math.min(box.maxX - box.minX, box.maxY - box.minY) * 0.6f);
        synchronized (rt.catCanon.lock) {
            rt.catCanon.impactFlashes.add(f);
        }
    }

    private static void tickImpactFlashes(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.catCanon.lock) {
            Iterator<Flash> it = rt.catCanon.impactFlashes.iterator();
            while (it.hasNext()) {
                if (++it.next().age >= IMPACT_FLASH_FRAMES) it.remove();
            }
        }
    }

    private static void drawImpactFlashes(CrazyRuntime.StageRuntime rt, FakeGraphics gra) {
        if (gra == null) return;
        ArrayList<Flash> copy;
        synchronized (rt.catCanon.lock) {
            if (rt.catCanon.impactFlashes.isEmpty()) return;
            copy = new ArrayList<Flash>(rt.catCanon.impactFlashes);
        }
        FakeImage hot = getGlowHot(), white = getGlowWhite(), ember = getEmber(), ring = getRing();
        for (int i = 0; i < copy.size(); i++) {
            Flash f = copy.get(i);
            float t = clamp01(f.age / (float) IMPACT_FLASH_FRAMES);
            float fade = 1f - t;
            float r = f.size;
            drawGlowTexAdditive(gra, hot, f.x, f.y, r * (1.2f + 2.2f * t), Math.round(220 * fade));
            drawGlowTexAdditive(gra, white, f.x, f.y, r * (0.5f + 1.0f * t), Math.round(240 * fade * fade));
            if (ring != null) drawGlowTexAdditive(gra, ring, f.x, f.y, r * (1.6f + 5.0f * t), Math.round(210 * fade));
            if (ember != null) {
                for (int k = 0; k < 6; k++) {
                    float ang = (float) (Math.PI * 2.0 * k / 6.0 + (k % 2) * 0.5);
                    float dist = r * (0.4f + 2.4f * t);
                    drawGlowTexAdditive(gra, ember, f.x + (float) Math.cos(ang) * dist,
                            f.y + (float) Math.sin(ang) * dist, r * 0.35f * fade, Math.round(200 * fade));
                }
            }
        }
    }

    private static void pushTrail(CrazyRuntime.StageRuntime rt, Projectile p, BoxRec box) {
        float sx, sy, dim;
        if (box != null) {
            sx = box.cx;
            sy = box.cy;
            dim = Math.max(8f, Math.max(box.maxX - box.minX, box.maxY - box.minY));
        } else {
            Transform tr;
            synchronized (rt.catCanon.lock) {
                if (!rt.catCanon.transformValid) return;
                tr = new Transform(rt.catCanon.lastSiz, rt.catCanon.lastStagePos, rt.catCanon.lastMidh,
                        rt.catCanon.lastWidth, rt.catCanon.lastHeight);
            }
            sx = gameToScreenX(p.x, tr);
            sy = screenYf(p.layerY, tr);
            float scale = p.scaleResolved ? p.drawScale : PROJECTILE_INITIAL_SCALE;
            dim = Math.max(8f, PROJECTILE_TARGET_SIZE * scale * tr.siz);
        }
        p.trailHead = (p.trailHead + 1) % TRAIL_LEN;
        p.trailX[p.trailHead] = sx;
        p.trailY[p.trailHead] = sy;
        p.trailDim = dim;
        if (p.trailCount < TRAIL_LEN) p.trailCount++;
    }

    private static void drawProjectileTrails(CrazyRuntime.StageRuntime rt, FakeGraphics gra) {
        if (gra == null) return;
        FakeImage hot = getGlowHot();
        FakeImage white = getGlowWhite();
        if (hot == null) return;
        ArrayList<Projectile> copy;
        int frame;
        synchronized (rt.catCanon.lock) {
            if (rt.catCanon.projectiles.isEmpty()) return;
            copy = new ArrayList<Projectile>(rt.catCanon.projectiles);
            frame = rt.catCanon.animFrame;
        }
        for (int i = 0; i < copy.size(); i++) {
            Projectile p = copy.get(i);
            if (p == null || p.trailCount <= 0 || p.trailHead < 0) continue;
            float dim = p.trailDim;

            for (int k = p.trailCount - 1; k >= 0; k--) {
                int idx = ((p.trailHead - k) % TRAIL_LEN + TRAIL_LEN) % TRAIL_LEN;
                float f = 1f - k / (float) TRAIL_LEN;
                drawGlowTexAdditive(gra, hot, p.trailX[idx], p.trailY[idx],
                        dim * (0.5f + 0.7f * f), Math.round(140 * f * f));
            }

            float pulse = 0.5f + 0.5f * (float) Math.sin(frame * 0.35f + i);
            drawGlowTexAdditive(gra, hot, p.trailX[p.trailHead], p.trailY[p.trailHead],
                    dim * (1.2f + 0.3f * pulse), Math.round(60 + 35 * pulse));
            drawGlowTexAdditive(gra, white, p.trailX[p.trailHead], p.trailY[p.trailHead], dim * 0.55f, 190);
        }
    }

    private static void drawMuzzleCharge(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (gra == null) return;
        int timer, frame;
        boolean armed, flashing;
        synchronized (rt.catCanon.lock) {
            timer = rt.catCanon.fireTimer;
            frame = rt.catCanon.animFrame;
            armed = !rt.catCanon.ammo.isEmpty();
            flashing = rt.catCanon.muzzleFlash > 0;
        }
        if (!armed || flashing) return;
        float charge = clamp01((timer / (float) FIRE_INTERVAL - 0.7f) / 0.3f);
        if (charge <= 0.02f) return;
        float[] m = muzzleScreenPos(rt, bbpainter);
        if (m == null) return;
        float siz = m[2];
        float pulse = 0.6f + 0.4f * (float) Math.sin(frame * 0.5f);
        drawGlowTexAdditive(gra, getGlowHot(), m[0], m[1], (10f + 26f * charge) * pulse * siz, Math.round(120 * charge));
        drawGlowTexAdditive(gra, getGlowWhite(), m[0], m[1], (4f + 12f * charge) * pulse * siz, Math.round(150 * charge));
    }

    private static float screenYf(float layer, Transform tr) {
        return tr.midh - (156f - layer * 4f) * tr.siz;
    }

    private static volatile FakeImage glowHot, glowWhite, glowEmber, glowRing;

    private static FakeImage getGlowHot() {
        FakeImage g = glowHot;
        if (g != null) return g;
        g = bakeRadialGlow(Color.WHITE, new Color(255, 205, 110), new Color(235, 140, 45));
        if (g != null) glowHot = g;
        return g;
    }

    private static FakeImage getGlowWhite() {
        FakeImage g = glowWhite;
        if (g != null) return g;
        g = bakeRadialGlow(Color.WHITE, Color.WHITE, new Color(255, 240, 205));
        if (g != null) glowWhite = g;
        return g;
    }

    private static FakeImage getEmber() {
        FakeImage g = glowEmber;
        if (g != null) return g;
        g = bakeRadialGlow(new Color(255, 245, 210), new Color(255, 180, 80), new Color(220, 90, 30));
        if (g != null) glowEmber = g;
        return g;
    }

    private static FakeImage getRing() {
        FakeImage g = glowRing;
        if (g != null) return g;
        g = bakeRing();
        if (g != null) glowRing = g;
        return g;
    }

    private static FakeImage bakeRadialGlow(Color c0, Color c1, Color c2) {
        try {
            final int N = 64;
            FakeImage img = ImageBuilder.builder.build(N, N);
            float c = (N - 1) / 2f;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    float dx = (x - c) / (N / 2f), dy = (y - c) / (N / 2f);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    Color base = (d < 0.45f)
                            ? lerpColor(c0, c1, smooth(d / 0.45f))
                            : lerpColor(c1, c2, smooth(clamp01((d - 0.45f) / 0.55f)));
                    float env = clamp01(1f - d);
                    env = env * env;
                    int r = Math.round(base.getRed() * env);
                    int gg = Math.round(base.getGreen() * env);
                    int b = Math.round(base.getBlue() * env);
                    img.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Cat Canon glow bake failed", t);
            return null;
        }
    }

    private static FakeImage bakeRing() {
        try {
            final int N = 64;
            FakeImage img = ImageBuilder.builder.build(N, N);
            float c = (N - 1) / 2f;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    float dx = (x - c) / (N / 2f), dy = (y - c) / (N / 2f);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float e = (d - 0.74f) / 0.15f;
                    float env = d > 1f ? 0f : (float) Math.exp(-e * e);
                    int r = Math.round(255 * env), gg = Math.round(228 * env), b = Math.round(155 * env);
                    img.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Cat Canon ring bake failed", t);
            return null;
        }
    }

    private static void drawGlowTexAdditive(FakeGraphics gra, FakeImage tex, float cx, float cy,
                                            float diameter, int alpha) {
        if (gra == null || tex == null || diameter < 2f || alpha <= 1) return;
        try {
            gra.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, alpha)), 1);
            gra.drawImage(tex, cx - diameter / 2f, cy - diameter / 2f, diameter, diameter);
        } finally {
            try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
        }
    }

    private static Color lerpColor(Color a, Color b, float t) {
        t = clamp01(t);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    private static volatile boolean glowPrewarmed;

    private static void prewarmGlow(CrazyRuntime.StageRuntime rt) {
        if (glowPrewarmed || rt == null || rt.config == null || !rt.config.catCanonBase) return;
        getGlowHot();
        getGlowWhite();
        getEmber();
        getRing();
        glowPrewarmed = true;
    }

    private static void releaseOnDefeat(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.catCanon.lock) {
            rt.catCanon.projectiles.clear();
            rt.catCanon.byEntity.clear();
            rt.catCanon.boxes.clear();
            rt.catCanon.shards.clear();
            rt.catCanon.impactFlashes.clear();
            rt.catCanon.muzzleFlash = 0;
            rt.catCanon.springPos = 1f;
            rt.catCanon.springVel = 0f;
            rt.catCanon.recoilFrame = 0;
        }
    }

    private static void drawAmmoUi(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (bbpainter == null || (!rt.config.catCanonBase && !hasActive(rt))) return;
        ArrayList<Ammo> ammo;
        int timer;
        synchronized (rt.catCanon.lock) {
            ammo = new ArrayList<Ammo>(rt.catCanon.ammo);
            timer = rt.catCanon.fireTimer;
        }
        if (ammo.isEmpty()) return;
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) return;
        int width = safePainterWidth(bbpainter);
        int height = safePainterHeight(bbpainter);
        int panelW = Math.min(380, Math.max(220, width / 3));
        int panelH = 70;
        int x = Math.max(12, width - panelW - 24);
        int y = Math.max(12, height - 158);
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color old = g.getColor();
        Font oldFont = g.getFont();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(0.78f));
            g.setColor(new Color(22, 24, 29));
            g.fillRoundRect(x, y, panelW, panelH, 10, 10);
            g.setComposite(AlphaComposite.SrcOver.derive(0.95f));
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(255, 211, 88));
            g.drawRoundRect(x + 1, y + 1, panelW - 2, panelH - 2, 10, 10);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.setColor(new Color(255, 242, 175));
            g.drawString("CAT CANON x" + ammo.size(), x + 14, y + 22);
            float progress = ammo.isEmpty() ? 0f : timer / (float) FIRE_INTERVAL;
            g.setColor(new Color(58, 64, 76));
            g.fillRoundRect(x + 14, y + 30, panelW - 28, 7, 6, 6);
            g.setColor(new Color(255, 202, 74));
            g.fillRoundRect(x + 14, y + 30, Math.round((panelW - 28) * clamp01(progress)), 7, 6, 6);
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(old);
            g.setFont(oldFont);
            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }

        int max = Math.min(8, ammo.size());
        for (int i = 0; i < max; i++) {
            drawIcon(gra, ammo.get(i).icon, x + 20 + i * 38, y + 44, 30f, 1f);
        }
        if (ammo.size() > max) {
            drawSmallText(gra, "+" + (ammo.size() - max), x + 20 + max * 38, y + 65);
        }
    }

    private static void drawIcon(FakeGraphics g, FakeImage img, float x, float y, float size, float alpha) {
        if (g == null || img == null || size <= 0f) return;
        try {
            g.setComposite(FakeGraphics.TRANS, clamp255(Math.round(alpha * 255f)), 0);
            float ratio = size / Math.max(1f, Math.max(img.getWidth(), img.getHeight()));
            float w = img.getWidth() * ratio;
            float h = img.getHeight() * ratio;
            g.drawImage(img, x, y, w, h);
        } catch (Throwable ignored) {
        } finally {
            try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
        }
    }

    private static void drawSmallText(FakeGraphics gra, String text, int x, int y) {
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null || text == null) return;
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color old = g.getColor();
        Font oldFont = g.getFont();
        try {
            g.setTransform(new AffineTransform());
            g.setComposite(AlphaComposite.SrcOver);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(new Color(0, 0, 0, 190));
            g.drawString(text, x + 1, y + 1);
            g.setColor(new Color(255, 242, 175));
            g.drawString(text, x, y);
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(old);
            g.setFont(oldFont);
        }
    }

    private static void rememberTransform(CrazyRuntime.StageRuntime rt, Object bbpainter) {
        if (bbpainter == null) return;
        try {
            synchronized (rt.catCanon.lock) {
                rt.catCanon.lastSiz = BBPainterAccess.getSiz(bbpainter);
                rt.catCanon.lastStagePos = BBPainterAccess.getStagePos(bbpainter);
                rt.catCanon.lastMidh = BBPainterAccess.getMidh(bbpainter);
                rt.catCanon.lastWidth = BBPainterAccess.getWidth(bbpainter);
                rt.catCanon.lastHeight = BBPainterAccess.getHeight(bbpainter);
                rt.catCanon.transformValid = true;
            }
        } catch (Throwable ignored) {}
    }

    private static BoxRec estimatedEnemyBaseBox(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        Transform tr;
        synchronized (rt.catCanon.lock) {
            if (!rt.catCanon.transformValid) return null;
            tr = new Transform(rt.catCanon.lastSiz, rt.catCanon.lastStagePos, rt.catCanon.lastMidh,
                    rt.catCanon.lastWidth, rt.catCanon.lastHeight);
        }
        float x = gameToScreenX(sb.ebase.pos, tr);
        float y = groundY(0, tr);
        BoxRec box = new BoxRec();
        float w = 220f * tr.siz;
        float h = 230f * tr.siz;
        box.minX = x - w * 0.65f;
        box.maxX = x + w * 0.35f;
        box.minY = y - h;
        box.maxY = y + 10f * tr.siz;
        box.cx = (box.minX + box.maxX) * 0.5f;
        box.cy = (box.minY + box.maxY) * 0.5f;
        box.timeMs = System.currentTimeMillis();
        return box;
    }

    private static void addEntitySorted(StageBasis sb, EUnit unit) {
        sb.le.add(unit);
        sb.le.sort(new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                return Integer.compare(safeLayer(a), safeLayer(b));
            }
        });
    }

    private static void makeUntouchable(Entity e) {
        try {
            int[][] status = (int[][]) BCUFields.get(e, "status");
            status[44][0] = 99999;
        } catch (Throwable ignored) {}
    }

    private static void setWalkAnim(Object entity) {
        try {
            Object animMgr = BCUFields.get(entity, "anim");
            if (animMgr == null) return;
            initAnimCache(animMgr);
            if (setAnimMethod != null && animUTypeWalk != null) setAnimMethod.invoke(animMgr, animUTypeWalk, true);
            try { BCUFields.field(entity.getClass(), "walking").setBoolean(entity, true); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void tickAnim(Object entity) {
        try {
            Object animMgr = BCUFields.get(entity, "anim");
            if (animMgr == null) return;
            initAnimCache(animMgr);
            if (animUpdateMethod != null) animUpdateMethod.invoke(animMgr);
        } catch (Throwable ignored) {}
    }

    private static void initAnimCache(Object animMgr) {
        if (animCacheInitialized) return;
        try {
            Class<?> uTypeCls = Class.forName("common.util.anim.AnimU$UType");
            for (Object e : uTypeCls.getEnumConstants()) {
                if ("WALK".equals(((Enum<?>) e).name())) animUTypeWalk = e;
            }
            setAnimMethod = BCUFields.method(animMgr.getClass(), "setAnim", uTypeCls, boolean.class);
            animUpdateMethod = BCUFields.method(animMgr.getClass(), "update");
        } catch (Throwable t) {
            Logger.err("Cat Canon anim cache init failed", t);
        } finally {
            animCacheInitialized = true;
        }
    }

    private static void applyStageDamageLimits(StageBasis sb, EUnit unit) {
        try {
            int hp = intField(sb, "unitHp", "unitHp", -1);
            if (hp != -1) unit.health = unit.maxH = hp;
        } catch (Throwable ignored) {}
        try {
            int dmg = intField(sb, "unitDamage", "unitDamage", -1);
            if (dmg != -1) {
                AtkModelEntity model = (AtkModelEntity) BCUFields.get(unit, "aam");
                int[] atks = (int[]) BCUFields.get(model, "atks");
                java.util.Arrays.fill(atks, dmg);
            }
        } catch (Throwable ignored) {}
    }

    private static void reject() {
        try { CommonStatic.setSE(15); } catch (Throwable ignored) {}
    }

    private static void decrementDeployLimit(StageBasis sb) {
        if (decrementPositiveField(sb, "maxCatSpawns")) return;
        decrementPositiveField(sb, "unitLeft");
    }

    private static boolean decrementPositiveField(Object obj, String field) {
        try {
            int v = BCUFields.getInt(obj, field);
            if (v > 0) {
                BCUFields.setInt(obj, field, v - 1);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markSpiritIfNeeded(StageBasis sb, Form form, int row, int col) {
        try {
            if (form != null && form.du != null && form.du.getProc().SPIRIT.exists()) {
                sb.summonerSummoned[row][col] = true;
                sb.spiritCooldown[row][col] = 15;
            }
        } catch (Throwable ignored) {}
    }

    private static int cannonId(StageBasis sb) {
        try { return Math.max(0, Math.min(CANNON_Y.length - 1, sb.canon.id)); }
        catch (Throwable ignored) { return 0; }
    }

    private static float muzzleWorldX(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        try {
            int id = cannonId(sb);

            float worldOffset = (CANNON_X[id] + CANNON_MUZZLE_DX[id] * CANNON_SPRITE_SCALE) / 0.32f;
            return sb.ubase.pos + worldOffset;
        } catch (Throwable ignored) {
            return stageLength(sb) - 680f;
        }
    }

    private static float muzzleLayer(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        try {
            int id = cannonId(sb);
            return (CANNON_Y[id] + CANNON_MUZZLE_DY[id] * CANNON_SPRITE_SCALE) / 4f;
        } catch (Throwable ignored) {
            return MUZZLE_DY / 4f;
        }
    }

    private static float[] muzzleScreen(CrazyRuntime.StageRuntime rt, Object bbpainter, StageBasis sb) {
        synchronized (rt.catCanon.lock) {
            if (rt.catCanon.baseAnchorValid) {
                float siz = rt.catCanon.baseSiz;
                return new float[]{rt.catCanon.baseAnchorX + MUZZLE_DX * siz,
                        rt.catCanon.baseAnchorY + MUZZLE_DY * siz};
            }
        }
        float siz = BBPainterAccess.getSiz(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);
        return new float[]{CrazyRender.screenX(bbpainter, muzzleWorldX(rt, sb)),
                midh + (muzzleLayer(rt, sb) * 4f - 156f) * siz};
    }

    private static float stageLength(StageBasis sb) {
        try { return Math.max(1f, sb.st.len); } catch (Throwable ignored) { return 6000f; }
    }

    private static float gameToScreenX(float pos, Transform tr) {
        return (pos * 0.32f + 200f) * tr.siz + tr.stagePos;
    }

    private static float groundY(int layer, Transform tr) {
        return tr.midh - (156f - layer * 4f) * tr.siz;
    }

    private static float averagePos(List<AbEntity> targets) {
        if (targets == null || targets.isEmpty()) return 0f;
        float sum = 0f;
        for (int i = 0; i < targets.size(); i++) sum += targets.get(i).pos;
        return sum / targets.size();
    }

    private static BoxRec copyBox(BoxRec src) {
        BoxRec out = new BoxRec();
        out.minX = src.minX;
        out.minY = src.minY;
        out.maxX = src.maxX;
        out.maxY = src.maxY;
        out.cx = src.cx;
        out.cy = src.cy;
        out.timeMs = src.timeMs;
        return out;
    }

    private static float[] alphaBoundsUV(FakeImage img) {
        if (img == null) return null;
        float[] cached = ALPHA_BOUNDS_CACHE.get(img);
        if (cached != null) return cached.length == 0 ? null : cached;
        float[] result;
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
            result = (maxX >= minX && maxY >= minY)
                    ? new float[]{minX, minY, maxX, maxY}
                    : new float[0];
        } catch (Throwable ignored) {
            result = new float[0];
        }
        ALPHA_BOUNDS_CACHE.put(img, result);
        return result.length == 0 ? null : result;
    }

    private static FakeImage iconFor(Form form) {
        try {
            if (form == null || !(form.anim instanceof AnimU)) return null;
            return ((AnimU<?>) form.anim).getUni().getImg();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int safeLayer(Object entity) {
        try { return EntityAccess.getLayer(entity); } catch (Throwable ignored) {}
        try { return BCUFields.getInt(entity, "layer"); } catch (Throwable ignored) {}
        try { return BCUFields.getInt(entity, "currentLayer"); } catch (Throwable ignored) {}
        return 0;
    }

    private static int intField(Object obj, String primary, String fallback, int def) {
        try { return BCUFields.getInt(obj, primary); } catch (Throwable ignored) {}
        try { return BCUFields.getInt(obj, fallback); } catch (Throwable ignored) {}
        return def;
    }

    private static int[] intArrayField(Object obj, String primary, String fallback) {
        try { return (int[]) BCUFields.get(obj, primary); } catch (Throwable ignored) {}
        try { return (int[]) BCUFields.get(obj, fallback); } catch (Throwable ignored) {}
        return null;
    }

    private static int safePainterWidth(Object bbpainter) {
        try { return BBPainterAccess.getWidth(bbpainter); } catch (Throwable ignored) { return 1280; }
    }

    private static int safePainterHeight(Object bbpainter) {
        try { return BBPainterAccess.getHeight(bbpainter); } catch (Throwable ignored) { return 720; }
    }

    private static int safeAdd(int a, int b) {
        long v = (long) a + (long) b;
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    private static int clampToInt(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    private static int catGetAttack(AtkModelEntity model, int ind, Data.Proc proc) {
        try {
            Object val = BCUFields.method(model.getClass(), "getAttack", int.class, Data.Proc.class)
                    .invoke(model, ind, proc);
            return val instanceof Number ? ((Number) val).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
