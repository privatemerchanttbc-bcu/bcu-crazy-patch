package manualcontrol.crazy.base;

import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.attack.AtkModelEntity;
import common.battle.entity.AbEntity;
import common.battle.entity.Entity;
import common.battle.entity.EUnit;
import common.CommonStatic;
import common.pack.UserProfile;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.stage.Limit;
import common.util.stage.StageMap;
import common.util.anim.AnimU;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.fall.ImpactFallFeature;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SlingshotBaseFeature {

    private static final int QUEUE_SIZE = 10;
    private static final int SLINGSHOT_LEVEL = 20;
    private static final int SLINGSHOT_PLUS_LEVEL = 20;
    private static final float MIN_PULL_PX = 12f;
    private static final float MAX_PULL_PX = 240f;
    private static final float DAMAGE_PULL_2X_PX = 80f;
    private static final float DAMAGE_MIN_MULT = 0.5f;
    private static final float DAMAGE_MID_MULT = 2.0f;
    private static final float DAMAGE_MAX_MULT = 10.0f;
    private static final float POWER = 0.235f;
    private static final float GRAVITY = 0.88f;
    private static final float AIR_DRAG = 0.997f;
    private static final int PREVIEW_MAX_FRAMES = 240;
    private static final int PREVIEW_STEP_FRAMES = 3;
    private static final int MORPH_FRAMES = 90;
    private static final int FADE_FRAMES = 20;
    private static final float PROJECTILE_ICON_SIZE = 54f;
    private static final String OFFICIAL_PACK = "000000";
    private static final float BASE_MUZZLE_X = 82f;
    private static final float BASE_MUZZLE_Y = -176f;
    private static final float HOOK_LEFT_X = -48f;
    private static final float HOOK_LEFT_Y = -36f;
    private static final float HOOK_RIGHT_X = 48f;
    private static final float HOOK_RIGHT_Y = -36f;
    private static final float POUCH_REST_Y = 18f;
    private static final float PULL_HIT_RADIUS = 96f;
    private static final float PULL_HIT_MAX_BELOW = 90f;
    private static final int RARITY_NORMAL = 0;
    private static final int RARITY_EX = 1;
    private static final int RARITY_RARE = 2;
    private static final int RARITY_SUPER_RARE = 3;
    private static final int RARITY_UBER = 4;
    private static final int RARITY_LEGEND = 5;
    private static final int RARITY_GROUP_LEGEND = 0;
    private static final int RARITY_GROUP_UBER = 1;
    private static final int RARITY_GROUP_SUPER_RARE = 2;
    private static final int RARITY_GROUP_RARE = 3;
    private static final int RARITY_GROUP_EX_NORMAL = 4;
    private static final int[] RARITY_GROUP_WEIGHTS = new int[]{5, 10, 25, 35, 25};
    private static final int RARITY_GROUP_COUNT = 5;

    private static volatile FakeImage slingBandImage;
    private static volatile FakeImage slingPouchImage;
    private static volatile FakeImage slingHookImage;
    private static volatile FakeImage trajectoryDotImage;
    private static volatile FakeImage trajectoryShadowImage;
    private static volatile FakeImage trajectoryMarkerImage;
    private static final Object POOL_CACHE_LOCK = new Object();
    private static ArrayList<Form> cachedOfficialPool;
    private static int cachedOfficialUnitCount = -1;
    private static int cachedOfficialFormCount = -1;

    private SlingshotBaseFeature() {}

    public static final class State {
        public final Object lock = new Object();
        public final ArrayList<Form> pool = new ArrayList<Form>();
        public final ArrayList<Form>[] rarityPools = createRarityPools();
        public final ArrayList<Ammo> queue = new ArrayList<Ammo>();
        public final ArrayList<Projectile> projectiles = new ArrayList<Projectile>();
        public final ArrayList<FadeIcon> fades = new ArrayList<FadeIcon>();
        public final Map<Object, Projectile> byEntity = new WeakHashMap<Object, Projectile>();
        public boolean initialized;
        public boolean poolWarningLogged;
        public boolean dragging;
        public int dragX;
        public int dragY;
        public float originX;
        public float originY;
        public boolean baseAnchorValid;
        public float baseAnchorX;
        public float baseAnchorY;
        public float baseSiz = 1f;
        public float lastSlotW = 58f;
        public float lastSlotH = 58f;
    }

    public static final class Ammo {
        public final Form form;
        public final EForm eform;
        public final FakeImage icon;
        public final int level;
        public final int plusLevel;
        public final int previewLayer;

        Ammo(Form form, FakeImage icon, int previewLayer) {
            this.form = form;
            this.level = SLINGSHOT_LEVEL;
            this.plusLevel = SLINGSHOT_PLUS_LEVEL;
            this.eform = new EForm(form, slingshotLevel());
            this.icon = icon;
            this.previewLayer = previewLayer;
        }
    }

    private static final class Projectile {
        final EUnit unit;
        final Ammo ammo;
        final Object page;
        final int origLayer;
        final float speedMultiplier;
        float screenX;
        float screenY;
        float vx;
        float vy;
        float groundY;
        float maxHeightPx;
        float spin;
        boolean flying = true;
        boolean morphing;
        int morphFrame;

        Projectile(EUnit unit, Ammo ammo, Object page, int origLayer, float screenX, float screenY,
                   float vx, float vy, float groundY, float speedMultiplier) {
            this.unit = unit;
            this.ammo = ammo;
            this.page = page;
            this.origLayer = origLayer;
            this.screenX = screenX;
            this.screenY = screenY;
            this.vx = vx;
            this.vy = vy;
            this.groundY = groundY;
            this.speedMultiplier = speedMultiplier;
        }
    }

    private static final class FadeIcon {
        final FakeImage icon;
        final int slot;
        int frame;

        FadeIcon(FakeImage icon, int slot) {
            this.icon = icon;
            this.slot = slot;
        }
    }

    private static final class Target {
        final AbEntity entity;
        final float screenX;
        final float screenY;
        final float radius;
        final float distance;

        Target(AbEntity entity, float screenX, float screenY, float radius, float distance) {
            this.entity = entity;
            this.screenX = screenX;
            this.screenY = screenY;
            this.radius = radius;
            this.distance = distance;
        }
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

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (e == null || e.getButton() != MouseEvent.BUTTON1) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
        if (rt == null || !rt.config.slingshotBase) return false;
        init(rt);
        Transform tr = transformForPage(page, (StageBasis) rt.stage);
        if (tr == null) return false;
        float[] muzzle = muzzleForRuntime(rt, page, tr);
        float dx = e.getX() - muzzle[0];
        float dy = e.getY() - muzzle[1];
        float hitRadius = Math.max(56f, PULL_HIT_RADIUS * tr.siz);
        if (e.getY() > muzzle[1] + PULL_HIT_MAX_BELOW * tr.siz) return false;
        if (dx * dx + dy * dy > hitRadius * hitRadius) return false;
        synchronized (rt.slingshot.lock) {
            rt.slingshot.dragging = true;
            rt.slingshot.originX = muzzle[0];
            rt.slingshot.originY = muzzle[1];
            rt.slingshot.dragX = e.getX();
            rt.slingshot.dragY = e.getY();
        }
        Logger.log("Slingshot drag started");
        return true;
    }

    public static boolean onMouseDragged(Object page, MouseEvent e) {
        CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
        if (rt == null || !rt.config.slingshotBase) return false;
        synchronized (rt.slingshot.lock) {
            if (!rt.slingshot.dragging) return false;
            rt.slingshot.dragX = e.getX();
            rt.slingshot.dragY = e.getY();
        }
        return true;
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
        if (rt == null || !rt.config.slingshotBase) return false;
        State st = rt.slingshot;
        float ox;
        float oy;
        int rx;
        int ry;
        synchronized (st.lock) {
            if (!st.dragging) return false;
            st.dragging = false;
            ox = st.originX;
            oy = st.originY;
            rx = e == null ? st.dragX : e.getX();
            ry = e == null ? st.dragY : e.getY();
        }

        float pullX = ox - rx;
        float pullY = oy - ry;
        float pull = (float) Math.sqrt(pullX * pullX + pullY * pullY);
        if (pull < MIN_PULL_PX) {
            Logger.log("Slingshot release cancelled: pull=" + Math.round(pull));
            return true;
        }
        if (pull > MAX_PULL_PX) {
            float s = MAX_PULL_PX / pull;
            pullX *= s;
            pullY *= s;
            pull = MAX_PULL_PX;
        }
        StageBasis sb = (StageBasis) rt.stage;
        Ammo queued = peekAmmo(rt);
        if (queued == null) return true;
        int shotCost = rt.config.slingshotMoneyCost ? moneyCost(sb, queued) : 0;
        if (shotCost > 0 && sb.money < shotCost) {
            try { CommonStatic.setSE(15); } catch (Throwable ignored) {}
            Logger.log("Slingshot release cancelled: money=" + sb.money + " cost=" + shotCost);
            return true;
        }
        Ammo ammo = consumeAmmo(rt);
        if (ammo == null) return true;
        float speedMul = damageMultiplierForPull(pull);
        launch(rt, page, ammo, ox, oy, pullX * POWER, pullY * POWER, speedMul, shotCost);
        return true;
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null || !rt.config.slingshotBase) return;
        init(rt);
        tickFades(rt);
        tickProjectiles(rt);
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics g) {
        if (rt == null || !rt.config.slingshotBase || g == null) return;
        init(rt);
        Transform tr = transformForPainter(bbpainter);
        if (tr == null) return;
        drawTrajectory(rt, tr, g);
        drawProjectiles(rt, g);
        drawDraggedAmmoOverlay(rt, g);
    }

    public static boolean isManaged(Object entity) {
        Projectile p = projectileFor(entity);
        return p != null && (p.flying || p.morphing);
    }

    public static boolean shouldHideNativeSprite(Object entity) {
        Projectile p = projectileFor(entity);
        return p != null && p.flying;
    }

    public static float drawScaleFor(Object entity) {
        Projectile p = projectileFor(entity);
        if (p == null || !p.morphing) return 1f;
        return clamp01(p.morphFrame / (float) MORPH_FRAMES);
    }

    public static boolean isSlingshotOverlayEntity(Object entity) {
        Projectile p = projectileFor(entity);
        return p != null && (p.flying || p.morphing);
    }

    private static Projectile projectileFor(Object entity) {
        if (!(entity instanceof Entity)) return null;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return null;
        synchronized (rt.slingshot.lock) {
            return rt.slingshot.byEntity.get(entity);
        }
    }

    private static void init(CrazyRuntime.StageRuntime rt) {
        State st = rt.slingshot;
        synchronized (st.lock) {
            if (st.initialized) return;
            StageBasis sb = (StageBasis) rt.stage;
            buildPool(st, sb);
            while (st.queue.size() < QUEUE_SIZE) {
                Ammo ammo = randomAmmo(st, sb);
                if (ammo == null) break;
                st.queue.add(ammo);
            }
            st.initialized = true;
            Logger.log("Slingshot Base initialized: pool=" + st.pool.size()
                    + " rarityPools=[legend=" + st.rarityPools[RARITY_GROUP_LEGEND].size()
                    + ", uber=" + st.rarityPools[RARITY_GROUP_UBER].size()
                    + ", super=" + st.rarityPools[RARITY_GROUP_SUPER_RARE].size()
                    + ", rare=" + st.rarityPools[RARITY_GROUP_RARE].size()
                    + ", exNormal=" + st.rarityPools[RARITY_GROUP_EX_NORMAL].size()
                    + "] queue=" + st.queue.size());
        }
    }

    private static void buildPool(State st, StageBasis sb) {
        st.pool.clear();
        clearRarityPools(st);
        try {
            ArrayList<Form> cached = cachedOfficialPool();
            for (int i = 0; i < cached.size(); i++) addToPool(st, cached.get(i));
        } catch (Throwable t) {
            Logger.err("Slingshot pool build failed", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Form>[] createRarityPools() {
        ArrayList<Form>[] pools = (ArrayList<Form>[]) new ArrayList[RARITY_GROUP_COUNT];
        for (int i = 0; i < pools.length; i++) pools[i] = new ArrayList<Form>();
        return pools;
    }

    private static void clearRarityPools(State st) {
        for (int i = 0; i < st.rarityPools.length; i++) st.rarityPools[i].clear();
    }

    private static void addToPool(State st, Form form) {
        if (form == null) return;
        int group = rarityGroup(form);
        if (group < 0 || group >= st.rarityPools.length) return;
        st.pool.add(form);
        st.rarityPools[group].add(form);
    }

    private static ArrayList<Form> cachedOfficialPool() {
        synchronized (POOL_CACHE_LOCK) {
            List<Unit> units = UserProfile.getBCData().units.getList();
            int unitCount = units == null ? 0 : units.size();
            int formCount = countForms(units);
            if (cachedOfficialPool != null
                    && cachedOfficialUnitCount == unitCount
                    && cachedOfficialFormCount == formCount) {
                return new ArrayList<Form>(cachedOfficialPool);
            }

            ArrayList<Form> next = new ArrayList<Form>();
            if (units != null) {
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (u == null || u.id == null || !OFFICIAL_PACK.equals(u.id.pack) || u.forms == null) continue;
                    for (int j = 0; j < u.forms.length; j++) {
                        Form f = u.forms[j];
                        if (isEligibleForm(f)) next.add(f);
                    }
                }
            }
            cachedOfficialPool = next;
            cachedOfficialUnitCount = unitCount;
            cachedOfficialFormCount = formCount;
            Logger.log("Slingshot official pool cached: forms=" + next.size());
            return new ArrayList<Form>(next);
        }
    }

    private static int countForms(List<Unit> units) {
        if (units == null) return 0;
        int count = 0;
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (u != null && u.forms != null) count += u.forms.length;
        }
        return count;
    }

    private static boolean isEligibleForm(Form f) {
        if (f == null || f.du == null || f.unit == null) return false;
        try {
            if (f.du.getPrice() <= 0) return false;
            if (f.du.getProc() != null && f.du.getProc().SPIRIT.exists()) return false;
            if (f.du.getAtkCount() <= 0 || f.du.getAtkModel(0) == null) return false;
            FakeImage icon = iconFor(f);
            return icon != null && icon.isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Ammo randomAmmo(State st, StageBasis sb) {
        if (st.pool.isEmpty()) {
            if (!st.poolWarningLogged) {
                st.poolWarningLogged = true;
                Logger.log("Slingshot Base has no eligible official player-unit forms");
            }
            return null;
        }
        for (int tries = 0; tries < 30 && !st.pool.isEmpty(); tries++) {
            int group = randomRarityGroup(st, sb);
            if (group < 0) return null;
            ArrayList<Form> forms = st.rarityPools[group];
            int idx = randInt(sb, forms.size());
            Form form = forms.get(idx);
            try {
                FakeImage icon = iconFor(form);
                if (icon == null || !icon.isValid()) throw new IllegalStateException("missing icon");
                int previewLayer = 0;
                try {
                    EUnit probe = new EForm(form, slingshotLevel()).getEntity(sb, null, false, false);
                    if (probe != null) previewLayer = EntityAccess.getLayer(probe);
                } catch (Throwable ignored) {}
                return new Ammo(form, icon, previewLayer);
            } catch (Throwable t) {
                removeFromPool(st, form);
                Logger.err("Slingshot removed bad form from pool", t);
            }
        }
        return null;
    }

    private static Level slingshotLevel() {
        Level level = new Level(SLINGSHOT_LEVEL);
        try { level.setPlusLevel(SLINGSHOT_PLUS_LEVEL); } catch (Throwable ignored) {}
        return level;
    }

    private static float damageMultiplierForPull(float pull) {
        float capped = clamp(pull, MIN_PULL_PX, MAX_PULL_PX);
        if (capped <= DAMAGE_PULL_2X_PX) {
            float t = clamp01(capped / DAMAGE_PULL_2X_PX);
            return DAMAGE_MIN_MULT + (DAMAGE_MID_MULT - DAMAGE_MIN_MULT) * t;
        }
        float t = clamp01((capped - DAMAGE_PULL_2X_PX) / Math.max(1f, MAX_PULL_PX - DAMAGE_PULL_2X_PX));
        return DAMAGE_MID_MULT + (DAMAGE_MAX_MULT - DAMAGE_MID_MULT) * t;
    }

    private static int randomRarityGroup(State st, StageBasis sb) {
        int total = 0;
        for (int i = 0; i < RARITY_GROUP_COUNT; i++) {
            if (!st.rarityPools[i].isEmpty()) total += RARITY_GROUP_WEIGHTS[i];
        }
        if (total <= 0) return -1;
        int roll = randInt(sb, total);
        int acc = 0;
        for (int i = 0; i < RARITY_GROUP_COUNT; i++) {
            if (st.rarityPools[i].isEmpty()) continue;
            acc += RARITY_GROUP_WEIGHTS[i];
            if (roll < acc) return i;
        }
        return -1;
    }

    private static int rarityGroup(Form form) {
        if (form == null || form.unit == null) return -1;
        int rarity = form.unit.rarity;
        if (rarity == RARITY_LEGEND) return RARITY_GROUP_LEGEND;
        if (rarity == RARITY_UBER) return RARITY_GROUP_UBER;
        if (rarity == RARITY_SUPER_RARE) return RARITY_GROUP_SUPER_RARE;
        if (rarity == RARITY_RARE) return RARITY_GROUP_RARE;
        if (rarity == RARITY_EX || rarity == RARITY_NORMAL) return RARITY_GROUP_EX_NORMAL;
        return -1;
    }

    private static void removeFromPool(State st, Form form) {
        st.pool.remove(form);
        int group = rarityGroup(form);
        if (group >= 0 && group < st.rarityPools.length) st.rarityPools[group].remove(form);
    }

    private static Ammo consumeAmmo(CrazyRuntime.StageRuntime rt) {
        State st = rt.slingshot;
        StageBasis sb = (StageBasis) rt.stage;
        synchronized (st.lock) {
            init(rt);
            if (st.queue.isEmpty()) return null;
            Ammo ammo = st.queue.remove(0);
            st.fades.add(new FadeIcon(ammo.icon, 0));
            Ammo next = randomAmmo(st, sb);
            if (next != null) st.queue.add(next);
            return ammo;
        }
    }

    private static Ammo peekAmmo(CrazyRuntime.StageRuntime rt) {
        State st = rt.slingshot;
        synchronized (st.lock) {
            init(rt);
            return st.queue.isEmpty() ? null : st.queue.get(0);
        }
    }

    private static int moneyCost(StageBasis sb, Ammo ammo) {
        if (sb == null || ammo == null || ammo.eform == null) return 0;
        int cost;
        try {
            int priceType = 0;
            try {
                Object cont = sb.st == null ? null : sb.st.getCont();
                if (cont instanceof StageMap) priceType = ((StageMap) cont).price;
            } catch (Throwable ignored) {}
            cost = (int) (ammo.eform.getPrice(priceType) * 100.0f);
        } catch (Throwable t) {
            try { cost = ammo.form == null || ammo.form.du == null ? 0 : ammo.form.du.getPrice() * 100; }
            catch (Throwable ignored) { cost = 0; }
        }
        try {
            Limit lim = sb.est == null ? null : sb.est.lim;
            if (lim != null && lim.stageLimit != null) {
                int global = sb.globalCost();
                if (global > 0) cost = global * 100;
                if (cost > 0 && ammo.form != null && ammo.form.unit != null) {
                    int rarity = ammo.form.unit.rarity;
                    int[] mult = lim.stageLimit.costMultiplier;
                    if (mult != null && rarity >= 0 && rarity < mult.length) {
                        cost = cost * mult[rarity] / 100;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return Math.max(0, cost);
    }

    private static void launch(CrazyRuntime.StageRuntime rt, Object page, Ammo ammo,
                               float sx, float sy, float vx, float vy, float speedMultiplier, int shotCost) {
        StageBasis sb = (StageBasis) rt.stage;
        Transform tr = transformForPage(page, sb);
        if (tr == null) return;
        try {
            EUnit unit = ammo.eform.getEntity(sb, null, false, false);
            int origLayer = EntityAccess.getLayer(unit);
            unit.added(-1, screenToGameX(sx, tr));
            applyScreen(unit, sx, sy, tr, origLayer);
            sb.le.add(unit);
            sb.le.sort(new Comparator<Entity>() {
                @Override
                public int compare(Entity a, Entity b) {
                    return Integer.compare(a.currentLayer, b.currentLayer);
                }
            });
            if (shotCost > 0) {
                if (sb.money < shotCost) {
                    try { CommonStatic.setSE(15); } catch (Throwable ignored) {}
                    Logger.log("Slingshot launch cancelled after consume: money=" + sb.money + " cost=" + shotCost);
                    try { sb.le.remove(unit); } catch (Throwable ignored) {}
                    try { unit.kill(Entity.KillMode.NORMAL); } catch (Throwable ignored) {}
                    return;
                }
                sb.money -= shotCost;
                try { BCUFields.setInt(unit, "price", shotCost); } catch (Throwable ignored) {}
            }
            Projectile p = new Projectile(unit, ammo, page, origLayer, sx, sy, vx, vy,
                    groundY(origLayer, tr), clamp(speedMultiplier, DAMAGE_MIN_MULT, DAMAGE_MAX_MULT));
            synchronized (rt.slingshot.lock) {
                rt.slingshot.projectiles.add(p);
                rt.slingshot.byEntity.put(unit, p);
            }
            Logger.log("Slingshot launched level=" + ammo.level + "+" + ammo.plusLevel
                    + " form=" + ammo.form
                    + " vel=(" + Math.round(vx) + "," + Math.round(vy) + ")"
                    + " cost=" + shotCost
                    + " damageMul=" + String.format("%.2f", p.speedMultiplier));
        } catch (Throwable t) {
            Logger.err("Slingshot launch failed", t);
        }
    }

    private static void tickProjectiles(CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        State st = rt.slingshot;
        ArrayList<Projectile> copy;
        synchronized (st.lock) {
            copy = new ArrayList<Projectile>(st.projectiles);
        }
        for (int i = 0; i < copy.size(); i++) {
            Projectile p = copy.get(i);
            if (p == null || p.unit == null || p.unit.dead) {
                removeProjectile(rt, p, true);
                continue;
            }
            Transform tr = transformForPage(p.page, sb);
            if (p.morphing) {
                if (tr != null) {
                    p.screenX = gameToScreenX(p.unit.pos, tr);
                    p.screenY = groundY(p.origLayer, tr);
                    applyScreen(p.unit, p.screenX, p.screenY, tr, p.origLayer);
                }
                p.morphFrame++;
                if (p.morphFrame >= MORPH_FRAMES) {
                    synchronized (st.lock) {
                        st.projectiles.remove(p);
                        st.byEntity.remove(p.unit);
                    }
                }
                continue;
            }
            if (tr == null) continue;
            p.vx *= AIR_DRAG;
            p.vy = p.vy * AIR_DRAG + GRAVITY;
            p.screenX += p.vx;
            p.screenY += p.vy;
            p.spin += p.vx * 0.018f;
            p.groundY = groundY(p.origLayer, tr);
            p.maxHeightPx = Math.max(p.maxHeightPx, Math.max(0f, p.groundY - p.screenY));
            applyScreen(p.unit, p.screenX, p.screenY, tr, p.origLayer);

            Target target = findCollisionTarget(sb, p, tr);
            if (target != null) {
                applyCollisionDamage(sb, p, target.entity);
                removeProjectile(rt, p, true);
                continue;
            }
            if (p.screenY >= p.groundY) {
                landProjectile(rt, p, tr);
            }
        }
    }

    private static void landProjectile(CrazyRuntime.StageRuntime rt, Projectile p, Transform tr) {
        p.screenY = p.groundY;
        applyScreen(p.unit, p.screenX, p.groundY, tr, p.origLayer);
        if (ImpactFallFeature.isEnabled(p.page, p.unit)) {
            boolean impacted = ImpactFallFeature.triggerLanding(p.page, p.unit, p.screenX, p.groundY,
                    Math.max(0f, p.vy), p.maxHeightPx, p.origLayer);
            if (impacted) {
                removeProjectile(rt, p, true);
                return;
            }
        }
        p.flying = false;
        p.morphing = true;
        p.morphFrame = 0;
        try { EntityAccess.setLayer(p.unit, p.origLayer); } catch (Throwable ignored) {}
        try { BCUFields.field(p.unit.getClass(), "lastPosition").setFloat(p.unit, p.unit.pos); } catch (Throwable ignored) {}
        Logger.log("Slingshot projectile deployed after landing");
    }

    private static Target findCollisionTarget(StageBasis sb, Projectile p, Transform tr) {
        Target best = null;
        float projectileRadius = iconRadius(p);
        try {
            for (int i = 0; i < sb.le.size(); i++) {
                Entity e = sb.le.get(i);
                if (e == null || e == p.unit || e.dead || e.health <= 0L) continue;
                if (e.dire != 1) continue;
                float sx = gameToScreenX(e.pos, tr);
                float sy = groundY(EntityAccess.getLayer(e), tr);
                float radius = targetRadius(e, tr);
                float d = dist(p.screenX, p.screenY, sx, sy);
                if (d <= projectileRadius + radius && (best == null || d < best.distance)) {
                    best = new Target(e, sx, sy, radius, d);
                }
            }
        } catch (Throwable t) {
            Logger.err("Slingshot collision scan failed", t);
        }
        try {
            AbEntity base = sb.ebase;
            if (base != null && base.health > 0L) {
                float sx = gameToScreenX(base.pos, tr);
                float sy = groundY(0, tr);
                float radius = Math.max(80f, 145f * tr.siz);
                float d = dist(p.screenX, p.screenY, sx, sy);
                if (d <= projectileRadius + radius && (best == null || d < best.distance)) {
                    best = new Target(base, sx, sy, radius, d);
                }
            }
        } catch (Throwable ignored) {}
        return best;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyCollisionDamage(StageBasis sb, Projectile p, AbEntity target) {
        if (target == null || p == null || p.unit == null) return;
        try {
            p.unit.pos = target.pos;
            p.unit.lastPosition = target.pos;
            p.unit.dire = -1;
            AtkModelEntity model = (AtkModelEntity) BCUFields.get(p.unit, "aam");
            int count = Math.max(1, p.unit.data.getAtkCount());
            for (int i = 0; i < count; i++) {
                AttackAb atk = model.getAttack(i);
                if (atk == null) continue;
                int scaled = Math.max(1, Math.round(atk.atk * p.speedMultiplier));
                atk.atk = scaled;
                try { BCUFields.field(atk.getClass(), "rawAtk").setInt(atk, scaled); } catch (Throwable ignored) {}
                atk.sta = target.pos - 1f;
                atk.end = target.pos + 1f;
                List capt = (List) BCUFields.get(atk, "capt");
                capt.clear();
                capt.add(target);
                atk.excuse();
            }
            Logger.log("Slingshot collision damage applied target=" + target.getClass().getSimpleName()
                    + " speedMul=" + String.format("%.2f", p.speedMultiplier));
        } catch (Throwable t) {
            Logger.err("Slingshot collision damage failed", t);
        }
    }

    private static void removeProjectile(CrazyRuntime.StageRuntime rt, Projectile p, boolean removeEntity) {
        if (p == null) return;
        synchronized (rt.slingshot.lock) {
            rt.slingshot.projectiles.remove(p);
            if (p.unit != null) rt.slingshot.byEntity.remove(p.unit);
        }
        if (removeEntity && p.unit != null) {
            try { p.unit.kill(Entity.KillMode.NORMAL); } catch (Throwable ignored) {}
            try { ((StageBasis) rt.stage).le.remove(p.unit); } catch (Throwable ignored) {}
        }
    }

    private static void tickFades(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.slingshot.lock) {
            Iterator<FadeIcon> it = rt.slingshot.fades.iterator();
            while (it.hasNext()) {
                FadeIcon f = it.next();
                f.frame++;
                if (f.frame >= FADE_FRAMES) it.remove();
            }
        }
    }

    public static void drawNativeLineupOverlay(Object bbpainter, FakeGraphics g, int width, int height,
                                               float scale, float gap, boolean upper, int row) {
        CrazyRuntime.StageRuntime rt = runtimeForPainter(bbpainter);
        if (rt == null || g == null || !rt.config.slingshotBase) return;
        init(rt);
        FakeImage slot = lineupReferenceImage(rt, bbpainter);
        if (slot == null) return;
        int slotW = Math.round(scale * Math.max(1, slot.getWidth()));
        int slotH = Math.round(scale * Math.max(1, slot.getHeight()));
        recordSlotSize(rt, slotW, slotH);
        for (int col = 0; col < 5; col++) {
            float x = (width - slotW * 5f) * 0.5f + slotW * col + gap * (col - 2f)
                    + (row == 0 ? 0f : gap * 0.5f);
            float y = height - slotH - (upper ? 0f : slotH * 0.1f);
            drawAmmoOverlaySlot(rt, g, row * 5 + col, x, y, slotW, slotH);
        }
    }

    public static void drawNativeTwoRowLineupOverlay(Object bbpainter, FakeGraphics g, int width, int height,
                                                     float scale, float gap, float rowGap) {
        CrazyRuntime.StageRuntime rt = runtimeForPainter(bbpainter);
        if (rt == null || g == null || !rt.config.slingshotBase) return;
        init(rt);
        FakeImage slot = lineupReferenceImage(rt, bbpainter);
        if (slot == null) return;
        int slotW = Math.round(scale * Math.max(1, slot.getWidth()));
        int slotH = Math.round(scale * Math.max(1, slot.getHeight()));
        recordSlotSize(rt, slotW, slotH);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 5; col++) {
                float x = (width - slotW * 5f) * 0.5f + slotW * col + gap * (col - 2f);
                float y = height - (2f - row) * (slotH + rowGap);
                drawAmmoOverlaySlot(rt, g, row * 5 + col, x, y, slotW, slotH);
            }
        }
    }

    private static void drawAmmoOverlaySlot(CrazyRuntime.StageRuntime rt, FakeGraphics g, int index,
                                            float x, float y, float w, float h) {
        Ammo ammo = null;
        ArrayList<FadeIcon> fades;
        boolean dragging;
        synchronized (rt.slingshot.lock) {
            if (index >= 0 && index < rt.slingshot.queue.size()) ammo = rt.slingshot.queue.get(index);
            fades = new ArrayList<FadeIcon>(rt.slingshot.fades);
            dragging = rt.slingshot.dragging;
        }
        if (ammo == null) return;
        if (dragging && index == 0) {
            g.colRect(x, y, w, h, 16, 17, 20, 96);
        } else {
            drawImageInBox(g, ammo.icon, x, y, w, h, index == 0 ? 1f : 0.96f);
        }
        for (int i = 0; i < fades.size(); i++) {
            FadeIcon f = fades.get(i);
            if (f == null || f.icon == null || f.slot != index) continue;
            float alpha = 1f - clamp01(f.frame / (float) FADE_FRAMES);
            drawImageInBox(g, f.icon, x, y, w, h, alpha);
        }
    }

    private static void drawDraggedAmmo(FakeGraphics g, CrazyRuntime.StageRuntime rt, float cx, float cy) {
        Ammo ammo = null;
        synchronized (rt.slingshot.lock) {
            if (!rt.slingshot.queue.isEmpty()) ammo = rt.slingshot.queue.get(0);
        }
        if (ammo == null || ammo.icon == null) return;
        drawIcon(g, ammo.icon, cx, cy, PROJECTILE_ICON_SIZE, 0f, 0.98f);
    }

    private static void drawDraggedAmmoOverlay(CrazyRuntime.StageRuntime rt, FakeGraphics g) {
        boolean dragging;
        int x;
        int y;
        synchronized (rt.slingshot.lock) {
            dragging = rt.slingshot.dragging;
            x = rt.slingshot.dragX;
            y = rt.slingshot.dragY;
        }
        if (dragging) drawDraggedAmmo(g, rt, x, y);
    }

    private static void recordSlotSize(CrazyRuntime.StageRuntime rt, float w, float h) {
        synchronized (rt.slingshot.lock) {
            rt.slingshot.lastSlotW = Math.max(12f, w);
            rt.slingshot.lastSlotH = Math.max(12f, h);
        }
    }

    private static FakeImage firstQueueIcon(CrazyRuntime.StageRuntime rt) {
        synchronized (rt.slingshot.lock) {
            for (int i = 0; i < rt.slingshot.queue.size(); i++) {
                Ammo ammo = rt.slingshot.queue.get(i);
                if (ammo != null && ammo.icon != null) return ammo.icon;
            }
        }
        return null;
    }

    private static CrazyRuntime.StageRuntime runtimeForPainter(Object bbpainter) {
        try {
            Object stage = BBPainterAccess.getStageBasis(bbpainter);
            return CrazyRuntime.get(stage);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static FakeImage nativeSlotImage(Object bbpainter) {
        try {
            Object aux = BCUFields.get(bbpainter, "aux");
            Object slots = BCUFields.get(aux, "slot");
            Object slot0 = ((Object[]) slots)[0];
            Object img = BCUFields.invoke(slot0, "getImg");
            return img instanceof FakeImage ? (FakeImage) img : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static FakeImage lineupReferenceImage(CrazyRuntime.StageRuntime rt, Object bbpainter) {
        FakeImage img = firstQueueIcon(rt);
        if (img != null && img.isValid()) return img;
        img = nativeSlotImage(bbpainter);
        return img != null && img.isValid() ? img : null;
    }

    public static void drawBaseAttachments(CrazyRuntime.StageRuntime rt, FakeGraphics g, float baseX, float baseY, float siz) {
        if (rt == null || g == null || !rt.config.slingshotBase) return;
        init(rt);
        boolean dragging;
        int dx;
        int dy;
        float mx;
        float my;
        float scale = slingScale(siz);
        synchronized (rt.slingshot.lock) {
            recordBaseAnchor(rt.slingshot, baseX, baseY, siz);
            dragging = rt.slingshot.dragging;
            dx = rt.slingshot.dragX;
            dy = rt.slingshot.dragY;
        }
        float[] muzzle = muzzleFromBaseAnchor(baseX, baseY, siz);
        mx = muzzle[0];
        my = muzzle[1];
        if (dragging) {
            drawSlingshotBands(g, mx, my, dx, dy, scale, true);
        } else {
            drawSlingshotBands(g, mx, my, mx, my + POUCH_REST_Y * scale, scale, false);
        }
    }

    private static void recordBaseAnchor(State st, float baseX, float baseY, float siz) {
        st.baseAnchorValid = true;
        st.baseAnchorX = baseX;
        st.baseAnchorY = baseY;
        st.baseSiz = siz;
    }

    private static void drawTrajectory(CrazyRuntime.StageRuntime rt, Transform tr, FakeGraphics g) {
        boolean dragging;
        int dx;
        int dy;
        float ox;
        float oy;
        synchronized (rt.slingshot.lock) {
            dragging = rt.slingshot.dragging;
            dx = rt.slingshot.dragX;
            dy = rt.slingshot.dragY;
            ox = rt.slingshot.originX;
            oy = rt.slingshot.originY;
        }
        if (!dragging) return;
        drawTrajectoryPreview(rt, g, tr, ox, oy, dx, dy);
    }

    private static void drawTrajectoryPreview(CrazyRuntime.StageRuntime rt, FakeGraphics g, Transform tr,
                                              float ox, float oy, int dragX, int dragY) {
        if (g == null || tr == null) return;
        float pullX = ox - dragX;
        float pullY = oy - dragY;
        float pull = (float) Math.sqrt(pullX * pullX + pullY * pullY);
        if (pull < MIN_PULL_PX) return;
        if (pull > MAX_PULL_PX) {
            float s = MAX_PULL_PX / pull;
            pullX *= s;
            pullY *= s;
        }

        float x = ox;
        float y = oy;
        float vx = pullX * POWER;
        float vy = pullY * POWER;
        float ground = groundY(previewLayer(rt), tr);
        float landX = x;
        float landY = y;
        boolean landed = false;
        int dot = 0;

        for (int frame = 1; frame <= PREVIEW_MAX_FRAMES; frame++) {
            float prevX = x;
            float prevY = y;
            vx *= AIR_DRAG;
            vy = vy * AIR_DRAG + GRAVITY;
            x += vx;
            y += vy;

            if (frame % PREVIEW_STEP_FRAMES == 0 && y <= ground + 3f) {
                float t = frame / (float) PREVIEW_MAX_FRAMES;
                float alpha = clamp(0.82f - dot * 0.009f, 0.28f, 0.82f);
                float size = clamp(12f * tr.siz, 7f, 15f) * (1f - Math.min(0.28f, t * 0.28f));
                drawCenteredImage(g, trajectoryShadowImage(), x + 1.5f * tr.siz, y + 2f * tr.siz,
                        size * 1.55f, size * 1.00f, 0f, alpha * 0.34f);
                drawCenteredImage(g, trajectoryDotImage(), x, y, size, size, 0f, alpha);
                dot++;
            }

            if (y >= ground) {
                if (Math.abs(y - prevY) > 0.001f) {
                    float a = clamp01((ground - prevY) / (y - prevY));
                    landX = prevX + (x - prevX) * a;
                } else {
                    landX = x;
                }
                landY = ground;
                landed = true;
                break;
            }
        }

        if (!landed) {
            landX = x;
            landY = Math.min(y, ground);
        }
        float marker = clamp(34f * tr.siz, 22f, 42f);
        drawCenteredImage(g, trajectoryMarkerImage(), landX, landY, marker, marker * 0.58f, 0f, 0.92f);
    }

    private static void drawSlingshotBands(FakeGraphics g, float muzzleX, float muzzleY,
                                           float pullX, float pullY, float scale, boolean dragging) {
        FakeImage band = slingBandImage();
        FakeImage pouch = slingPouchImage();
        FakeImage hook = slingHookImage();
        float leftX = muzzleX + HOOK_LEFT_X * scale;
        float leftY = muzzleY + HOOK_LEFT_Y * scale;
        float rightX = muzzleX + HOOK_RIGHT_X * scale;
        float rightY = muzzleY + HOOK_RIGHT_Y * scale;
        float pouchX = dragging ? pullX : muzzleX;
        float pouchY = dragging ? pullY : muzzleY + POUCH_REST_Y * scale;
        float bandH = clamp(11f * scale, 5.5f, 14f);

        if (hook != null && hook.isValid()) {
            drawCenteredImage(g, hook, leftX, leftY, 42f * scale, 36f * scale, -0.20f, 0.96f);
            drawCenteredImage(g, hook, rightX, rightY, 42f * scale, 36f * scale, 0.20f, 0.96f);
        }

        if (band != null && band.isValid()) {
            drawSegmentImage(g, band, leftX, leftY, pouchX - 8f * scale, pouchY + 1.5f * scale, bandH, 0.96f);
            drawSegmentImage(g, band, rightX, rightY, pouchX + 8f * scale, pouchY + 1.5f * scale, bandH, 0.96f);
        }

        float angle = dragging ? (float) Math.atan2(pouchY - muzzleY, pouchX - muzzleX) : 0f;
        float pouchW = (dragging ? 52f : 42f) * scale;
        float pouchH = (dragging ? 32f : 25f) * scale;
        if (pouch != null && pouch.isValid()) {
            drawCenteredImage(g, pouch, pouchX, pouchY, pouchW, pouchH, angle, 0.98f);
        }
    }

    private static void drawSegmentImage(FakeGraphics g, FakeImage img, float x0, float y0, float x1, float y1,
                                         float height, float alpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (img == null || len < 0.001f || height <= 0f) return;
        FakeTransform old = null;
        int a = clamp255(Math.round(255f * clamp01(alpha)));
        if (a <= 0) return;
        try {
            g.setComposite(FakeGraphics.TRANS, a, 0);
            old = g.getTransform();
            g.translate(x0, y0);
            g.rotate((float) Math.atan2(dy, dx));
            g.drawImage(img, 0f, -height * 0.5f, len, height);
        } catch (Throwable t) {
            Logger.err("Slingshot band draw failed", t);
        } finally {
            if (old != null) {
                try {
                    g.setTransform(old);
                    g.delete(old);
                } catch (Throwable ignored) {}
            }
            resetComposite(g);
        }
    }

    private static void drawCenteredImage(FakeGraphics g, FakeImage img, float cx, float cy,
                                          float w, float h, float rotation, float alpha) {
        if (img == null || w <= 0f || h <= 0f) return;
        FakeTransform old = null;
        int a = clamp255(Math.round(255f * clamp01(alpha)));
        if (a <= 0) return;
        try {
            g.setComposite(FakeGraphics.TRANS, a, 0);
            old = g.getTransform();
            g.translate(cx, cy);
            if (Math.abs(rotation) > 0.001f) g.rotate(rotation);
            g.drawImage(img, -w * 0.5f, -h * 0.5f, w, h);
        } catch (Throwable t) {
            Logger.err("Slingshot pouch draw failed", t);
        } finally {
            if (old != null) {
                try {
                    g.setTransform(old);
                    g.delete(old);
                } catch (Throwable ignored) {}
            }
            resetComposite(g);
        }
    }

    private static int previewLayer(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return 0;
        synchronized (rt.slingshot.lock) {
            return rt.slingshot.queue.isEmpty() || rt.slingshot.queue.get(0) == null
                    ? 0
                    : rt.slingshot.queue.get(0).previewLayer;
        }
    }

    private static float slingScale(float siz) {
        return clamp(0.86f * siz, 0.56f, 1.18f);
    }

    private static FakeImage slingBandImage() {
        FakeImage img = slingBandImage;
        if (img != null) return img;
        synchronized (SlingshotBaseFeature.class) {
            if (slingBandImage == null) slingBandImage = buildSlingBandImage();
            return slingBandImage;
        }
    }

    private static FakeImage slingPouchImage() {
        FakeImage img = slingPouchImage;
        if (img != null) return img;
        synchronized (SlingshotBaseFeature.class) {
            if (slingPouchImage == null) slingPouchImage = buildSlingPouchImage();
            return slingPouchImage;
        }
    }

    private static FakeImage slingHookImage() {
        FakeImage img = slingHookImage;
        if (img != null) return img;
        synchronized (SlingshotBaseFeature.class) {
            if (slingHookImage == null) slingHookImage = buildSlingHookImage();
            return slingHookImage;
        }
    }

    private static FakeImage trajectoryDotImage() {
        FakeImage img = trajectoryDotImage;
        if (img != null) return img;
        synchronized (SlingshotBaseFeature.class) {
            if (trajectoryDotImage == null) {
                trajectoryDotImage = buildSoftCircleImage(48, new Color(255, 242, 144), new Color(255, 255, 238));
            }
            return trajectoryDotImage;
        }
    }

    private static FakeImage trajectoryShadowImage() {
        FakeImage img = trajectoryShadowImage;
        if (img != null) return img;
        synchronized (SlingshotBaseFeature.class) {
            if (trajectoryShadowImage == null) {
                trajectoryShadowImage = buildSoftCircleImage(48, new Color(14, 14, 18), new Color(70, 62, 45));
            }
            return trajectoryShadowImage;
        }
    }

    private static FakeImage trajectoryMarkerImage() {
        FakeImage img = trajectoryMarkerImage;
        if (img != null) return img;
        synchronized (SlingshotBaseFeature.class) {
            if (trajectoryMarkerImage == null) trajectoryMarkerImage = buildTrajectoryMarkerImage();
            return trajectoryMarkerImage;
        }
    }

    private static FakeImage buildSlingHookImage() {
        BufferedImage image = new BufferedImage(72, 58, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.SrcOver.derive(0.26f));
            g.setColor(new Color(0, 0, 0));
            g.fillRoundRect(13, 15, 48, 34, 14, 14);
            g.setComposite(AlphaComposite.SrcOver);
            g.setPaint(new GradientPaint(8f, 0f, new Color(82, 49, 26), 62f, 58f, new Color(178, 116, 55)));
            g.fillRoundRect(9, 8, 50, 34, 15, 15);
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(56, 35, 23));
            g.drawRoundRect(9, 8, 50, 34, 15, 15);
            g.setComposite(AlphaComposite.SrcOver.derive(0.72f));
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(224, 157, 80));
            g.drawLine(18, 17, 46, 14);
            g.setComposite(AlphaComposite.SrcOver.derive(0.90f));
            g.setColor(new Color(38, 24, 18));
            g.fillOval(22, 26, 13, 12);
            g.fillOval(39, 25, 13, 12);
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildSlingBandImage() {
        BufferedImage image = new BufferedImage(128, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(0.94f));
            g.setPaint(new GradientPaint(0f, 0f, new Color(26, 18, 16),
                    0f, 24f, new Color(100, 44, 34)));
            g.fillRoundRect(0, 3, 128, 18, 18, 18);
            g.setComposite(AlphaComposite.SrcOver.derive(0.48f));
            g.setColor(new Color(230, 137, 91));
            g.fillRoundRect(4, 6, 120, 5, 8, 8);
            g.setComposite(AlphaComposite.SrcOver.derive(0.38f));
            g.setColor(new Color(5, 4, 4));
            g.fillRoundRect(2, 16, 124, 4, 8, 8);
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildSoftCircleImage(int size, Color outer, Color inner) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float cx = (size - 1) * 0.5f;
            float cy = cx;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx = (x - cx) / Math.max(1f, cx);
                    float dy = (y - cy) / Math.max(1f, cy);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d > 1f) continue;
                    float a = smooth(1f - d);
                    Color c = mix(outer, inner, smooth(clamp01(1f - d * 1.25f)));
                    image.setRGB(x, y, argb(Math.round(255f * a), c.getRed(), c.getGreen(), c.getBlue()));
                }
            }
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildTrajectoryMarkerImage() {
        BufferedImage image = new BufferedImage(96, 54, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(0.30f));
            g.setColor(new Color(10, 10, 12));
            g.fillOval(7, 10, 82, 38);
            g.setComposite(AlphaComposite.SrcOver.derive(0.78f));
            g.setColor(new Color(255, 204, 64));
            g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(15, 13, 66, 29);
            g.setComposite(AlphaComposite.SrcOver.derive(0.92f));
            g.setColor(new Color(255, 250, 220));
            g.fillOval(40, 24, 16, 8);
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildSlingPouchImage() {
        BufferedImage image = new BufferedImage(96, 54, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Path2D pouch = new Path2D.Float();
            pouch.moveTo(10f, 25f);
            pouch.curveTo(24f, 5f, 72f, 5f, 86f, 25f);
            pouch.curveTo(75f, 48f, 25f, 48f, 10f, 25f);
            pouch.closePath();

            g.setComposite(AlphaComposite.SrcOver.derive(0.26f));
            g.setColor(new Color(0, 0, 0));
            g.translate(3, 5);
            g.fill(pouch);
            g.translate(-3, -5);

            g.setComposite(AlphaComposite.SrcOver);
            g.setPaint(new GradientPaint(0f, 8f, new Color(145, 79, 38),
                    0f, 52f, new Color(63, 38, 26)));
            g.fill(pouch);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(38, 24, 18));
            g.draw(pouch);

            g.setComposite(AlphaComposite.SrcOver.derive(0.38f));
            g.setColor(new Color(241, 174, 92));
            g.fill(new Ellipse2D.Float(24f, 12f, 48f, 14f));

            g.setComposite(AlphaComposite.SrcOver.derive(0.72f));
            g.setColor(new Color(37, 24, 18));
            for (int i = 0; i < 7; i++) {
                g.fillOval(21 + i * 9, 35, 3, 3);
            }
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FakeImage buildFakeImage(BufferedImage image) {
        try {
            if (ImageBuilder.builder == null || image == null) return null;
            return ((ImageBuilder) ImageBuilder.builder).build(image);
        } catch (Throwable t) {
            Logger.err("Slingshot FakeImage conversion failed", t);
            return null;
        }
    }

    private static void drawProjectiles(CrazyRuntime.StageRuntime rt, FakeGraphics g) {
        ArrayList<Projectile> copy;
        synchronized (rt.slingshot.lock) {
            copy = new ArrayList<Projectile>(rt.slingshot.projectiles);
        }
        for (int i = 0; i < copy.size(); i++) {
            Projectile p = copy.get(i);
            if (p == null || p.ammo == null || p.ammo.icon == null) continue;
            if (p.flying) {
                drawIcon(g, p.ammo.icon, p.screenX, p.screenY, PROJECTILE_ICON_SIZE, p.spin, 1f);
            } else if (p.morphing) {
                float t = clamp01(p.morphFrame / (float) MORPH_FRAMES);
                float alpha = 1f - t;
                float size = 62f * alpha;
                drawIcon(g, p.ammo.icon, p.screenX, p.screenY, size, 0f, alpha);
            }
        }
    }

    private static void drawImageInBox(FakeGraphics g, FakeImage img, float x, float y, float boxW, float boxH, float alpha) {
        if (img == null) return;
        int oldAlpha = clamp255(Math.round(255f * clamp01(alpha)));
        if (oldAlpha <= 0 || boxW <= 0f || boxH <= 0f) return;
        try {
            g.setComposite(FakeGraphics.TRANS, oldAlpha, 0);
            float ratio = Math.min(boxW / Math.max(1, img.getWidth()),
                    boxH / Math.max(1, img.getHeight()));
            float w = img.getWidth() * ratio;
            float h = img.getHeight() * ratio;
            g.drawImage(img, x + (boxW - w) * 0.5f, y + (boxH - h) * 0.5f, w, h);
        } finally {
            resetComposite(g);
        }
    }

    private static void drawIcon(FakeGraphics g, FakeImage img, float cx, float cy, float size, float rotation, float alpha) {
        if (img == null) return;
        int a = clamp255(Math.round(255f * clamp01(alpha)));
        if (a <= 0 || size <= 0.5f) return;
        FakeTransform at = null;
        try {
            g.setComposite(FakeGraphics.TRANS, a, 0);
            at = g.getTransform();
            g.translate(cx, cy);
            if (Math.abs(rotation) > 0.001f) g.rotate(rotation);
            float ratio = size / Math.max(1f, Math.max(img.getWidth(), img.getHeight()));
            float w = img.getWidth() * ratio;
            float h = img.getHeight() * ratio;
            g.drawImage(img, -w * 0.5f, -h * 0.5f, w, h);
        } catch (Throwable t) {
            Logger.err("Slingshot icon draw failed", t);
        } finally {
            if (at != null) {
                try {
                    g.setTransform(at);
                    g.delete(at);
                } catch (Throwable ignored) {}
            }
            resetComposite(g);
        }
    }

    private static FakeImage iconFor(Form form) {
        if (form == null || !(form.anim instanceof AnimU)) return null;
        return ((AnimU<?>) form.anim).getUni().getImg();
    }

    private static float[] muzzleForRuntime(CrazyRuntime.StageRuntime rt, Object page, Transform tr) {
        synchronized (rt.slingshot.lock) {
            if (rt.slingshot.baseAnchorValid) {
                return muzzleFromBaseAnchor(rt.slingshot.baseAnchorX, rt.slingshot.baseAnchorY, rt.slingshot.baseSiz);
            }
        }
        return muzzleForPage(page, tr);
    }

    private static float[] muzzleFromBaseAnchor(float baseX, float baseY, float siz) {
        return new float[]{baseX + BASE_MUZZLE_X * siz, baseY + BASE_MUZZLE_Y * siz};
    }

    private static float[] muzzleForPage(Object page, Transform tr) {
        try {
            Object stage = CrazyRuntime.stageFromPage(page);
            return muzzleForStage((StageBasis) stage, tr);
        } catch (Throwable ignored) {
            return new float[]{tr.width - 120f * tr.siz, tr.midh - 190f * tr.siz};
        }
    }

    private static float[] muzzleForStage(StageBasis sb, Transform tr) {
        try {
            float baseX = gameToScreenX(sb.ubase.pos, tr);
            float baseY = groundY(0, tr);
            return new float[]{baseX + 66f * tr.siz, baseY - 141f * tr.siz};
        } catch (Throwable ignored) {
            return new float[]{tr.width - 120f * tr.siz, tr.midh - 190f * tr.siz};
        }
    }

    private static Transform transformForPainter(Object bbpainter) {
        try {
            return new Transform(BBPainterAccess.getSiz(bbpainter), BBPainterAccess.getStagePos(bbpainter),
                    BBPainterAccess.getMidh(bbpainter), BBPainterAccess.getWidth(bbpainter),
                    BBPainterAccess.getHeight(bbpainter));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Transform transformForPage(Object page, StageBasis expected) {
        try {
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sbObj = BCUFields.get(bf, "sb");
            StageBasis sb = sbObj instanceof StageBasis ? (StageBasis) sbObj : expected;
            float siz = BCUFields.getFloat(sb, "siz");
            int stagePos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            int width = (int) BCUFields.invoke(bb, "getWidth");
            int height = (int) BCUFields.invoke(bb, "getHeight");
            return new Transform(siz, stagePos, midh, width, height);
        } catch (Throwable t) {
            try {
                return new Transform(expected.siz, expected.pos, Math.round(expected.midH), 1280, 720);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static void applyScreen(EUnit unit, float sx, float sy, Transform tr, int origLayer) {
        float gameX = screenToGameX(sx, tr);
        int layer = screenToLayer(sy, tr);
        if (layer > origLayer) layer = origLayer;
        unit.pos = gameX;
        unit.lastPosition = gameX;
        try { EntityAccess.setLayer(unit, layer); } catch (Throwable ignored) {}
    }

    private static float screenToGameX(float sx, Transform tr) {
        return ((sx - tr.stagePos) / Math.max(0.001f, tr.siz) - 200f) / 0.32f;
    }

    private static int screenToLayer(float sy, Transform tr) {
        return Math.round(((sy - tr.midh) / Math.max(0.001f, tr.siz) + 156f) / 4f);
    }

    private static float gameToScreenX(float pos, Transform tr) {
        return (pos * 0.32f + 200f) * tr.siz + tr.stagePos;
    }

    private static float groundY(int layer, Transform tr) {
        return tr.midh - (156f - layer * 4f) * tr.siz;
    }

    private static float iconRadius(Projectile p) {
        if (p == null || p.ammo == null || p.ammo.icon == null) return 28f;
        return Math.max(24f, Math.min(42f, Math.max(p.ammo.icon.getWidth(), p.ammo.icon.getHeight()) * 0.25f));
    }

    private static float targetRadius(Entity e, Transform tr) {
        try {
            float width = Math.max(40f, e.data.getWidth());
            return clamp(width * 0.32f * tr.siz * 0.55f + 24f, 30f, 150f);
        } catch (Throwable ignored) {
            return 60f;
        }
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x0 - x1;
        float dy = y0 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static int randInt(StageBasis sb, int bound) {
        if (bound <= 1) return 0;
        int v = (int) (sb.r.nextFloat() * bound);
        return Math.max(0, Math.min(bound - 1, v));
    }

    private static void resetComposite(FakeGraphics g) {
        try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
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

    private static float smooth(float t) {
        float p = clamp01(t);
        return p * p * (3f - 2f * p);
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
