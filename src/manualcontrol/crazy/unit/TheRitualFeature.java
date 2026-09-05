package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.pack.UserProfile;
import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.data.MaskEnemy;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.anim.EAnimU;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.FallingRegistry;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyPreloadService;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.Color;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class TheRitualFeature {

    private static final int COOLDOWN_FRAMES = 20 * 30;
    private static final int SACRIFICE_VFX_FRAMES = 42;
    private static final int SPAWN_VFX_FRAMES = 84;
    private static final int SHAKE_FRAMES = 18;

    private static final int SUMMON_VFX_DELAY = 20;
    private static final int SUMMON_REVEAL_FRAME = SUMMON_VFX_DELAY + Math.round(SPAWN_VFX_FRAMES * 0.66f);

    private static final int MATERIALIZE_FRAMES = 22;
    private static final long HP_THRESHOLD = 1000000L;
    private static final long ATTACK_THRESHOLD = 50000L;
    private static final int SUMMON_LEVEL = 20;
    private static final int SUMMON_PLUS_LEVEL = 70;
    private static final String OFFICIAL_PACK = "000000";

    private static final double HP_PCT_PER = 0.01d;
    private static final double ATK_PCT_PER = 0.05d;
    private static final float SCALE_PCT_PER = 0.01f;
    private static final double ATKSPD_PCT_PER = 0.01d;
    private static final float MOVESPD_UNITS_PER = 0.2f;

    private static final int TIER_SATURATION = 25;
    private static final float TIER_WINDOW_FRAC = 0.12f;

    private static final Color DIVINE_CORE = new Color(255, 255, 255);
    private static final Color DIVINE_HOT = new Color(255, 233, 168);
    private static final Color DIVINE_DEEP = new Color(255, 194, 74);
    private static final Color DIVINE_RIM = new Color(232, 165, 58);
    private static final Color DIVINE_NIGHT = new Color(36, 28, 54);

    private static volatile Field transformDataField;

    private TheRitualFeature() {}

    public static int preloadFlags() {
        return CrazyPreloadService.PRELOAD_OFFICIAL_PLAYER_FORMS;
    }

    public static final class State {
        public final Object lock = new Object();
        public final ArrayList<Candidate> pool = new ArrayList<Candidate>();
        public final ArrayList<RitualVfx> vfx = new ArrayList<RitualVfx>();
        public final Map<Object, Buff> buffs = new WeakHashMap<Object, Buff>();
        public final Map<Object, Float> beforePos = new WeakHashMap<Object, Float>();
        public final Map<Object, MotionState> motion = new WeakHashMap<Object, MotionState>();
        public final Set<Object> damageScaled = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
        public boolean initialized;
        public boolean poolWarningLogged;
        public int cooldown;
        public int shakeFrame;

        public PendingSummon pending;

        public FakeImage glowTex;
        public FakeImage ringTex;
        public FakeImage sparkTex;
        public boolean texturesBaked;
        public boolean textureWarningLogged;
    }

    private static final class PendingSummon {
        final Form form;
        final String key;
        final int count;
        final float pos;
        final int revealAt;
        int timer;
        boolean revealed;

        PendingSummon(Form form, String key, int count, float pos, int revealAt) {
            this.form = form;
            this.key = key;
            this.count = count;
            this.pos = pos;
            this.revealAt = revealAt;
        }
    }

    private static final class Candidate {
        final Form form;
        final String key;
        final long hp;
        final long attack;
        final double score;

        Candidate(Form form, String key, long hp, long attack) {
            this.form = form;
            this.key = key;
            this.hp = hp;
            this.attack = attack;
            this.score = Math.max((double) hp / HP_THRESHOLD, (double) attack / ATTACK_THRESHOLD);
        }
    }

    private static final class Buff {
        final int sacrifices;
        final long baseMaxH;
        final int baseSpeed;
        int age;

        Buff(int sacrifices, long baseMaxH, int baseSpeed) {
            this.sacrifices = Math.max(0, sacrifices);
            this.baseMaxH = Math.max(1L, baseMaxH);
            this.baseSpeed = Math.max(1, baseSpeed);
        }
    }

    private static final class MotionState {
        int lastWait;
        int lastPre;
        int lastAtk;
        boolean attackAnimLogged;
    }

    private static final class RitualVfx {
        final float pos;
        final int layer;
        final boolean spawn;
        final int seed;
        final int count;
        int delay;
        int age;

        RitualVfx(float pos, int layer, boolean spawn, int seed, int count, int delay) {
            this.pos = pos;
            this.layer = layer;
            this.spawn = spawn;
            this.seed = seed;
            this.count = Math.max(0, count);
            this.delay = Math.max(0, delay);
        }

        int maxAge() {
            return spawn ? SPAWN_VFX_FRAMES : SACRIFICE_VFX_FRAMES;
        }

        boolean started() {
            return delay <= 0;
        }

        boolean done() {
            return started() && age >= maxAge();
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
        if (page == null || e == null || e.getButton() != MouseEvent.BUTTON1) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            ensureInitialized(rt);
            Rect icon = iconRectFromPage(page);
            if (!icon.contains(e.getX(), e.getY())) return false;
            if (!usable(rt)) {
                rt.theRitual.shakeFrame = SHAKE_FRAMES;
                reject();
                return true;
            }
            if (!activate(rt)) {
                rt.theRitual.shakeFrame = SHAKE_FRAMES;
                reject();
            }
            return true;
        } catch (Throwable t) {
            Logger.err("The Ritual mouse press failed", t);
            return false;
        }
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null || (!rt.config.theRitual && !hasActive(rt))) return;
        State st = rt.theRitual;
        if (st.cooldown > 0) st.cooldown--;
        if (st.shakeFrame > 0) st.shakeFrame--;
        for (Iterator<RitualVfx> it = st.vfx.iterator(); it.hasNext();) {
            RitualVfx fx = it.next();
            if (fx == null || fx.done()) {
                it.remove();
                continue;
            }
            if (fx.delay > 0) fx.delay--;
            else fx.age++;
        }
        if (st.pending != null) {
            PendingSummon ps = st.pending;
            ps.timer++;
            if (!ps.revealed && ps.timer >= ps.revealAt) revealSummon(rt, st, ps);
            if (ps.revealed) st.pending = null;
        }
        for (Buff b : st.buffs.values()) {
            if (b != null && b.age <= MATERIALIZE_FRAMES) b.age++;
        }
        cleanupTracked(st);
        capturePositions(st);
    }

    public static void afterNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        if (rt == null || !hasActive(rt)) return;
        State st = rt.theRitual;
        try {
            for (Object obj : new ArrayList<Object>(st.buffs.keySet())) {
                if (!(obj instanceof Entity)) continue;
                Entity e = (Entity) obj;
                Buff buff = st.buffs.get(e);
                if (buff == null || e.dead || e.health <= 0L) {
                    st.buffs.remove(e);
                    st.beforePos.remove(e);
                    st.motion.remove(e);
                    continue;
                }
                applyMovementBonus(st, e, buff);
                accelerateTimers(st, e, buff.sacrifices);
            }
        } finally {
            st.beforePos.clear();
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (!enabled(rt) || bbpainter == null || gra == null) return;
        ensureInitialized(rt);
        drawVfx(rt, bbpainter, gra);
        drawHudIcon(rt, bbpainter, gra);
    }

    public static float drawScaleFor(Object entity) {
        Buff buff = buffFor(entity);
        if (buff == null || buff.sacrifices <= 0) return 1f;
        float scale = 1f + buff.sacrifices * SCALE_PCT_PER;
        return finite(scale) ? Math.max(1f, scale) : 1f;
    }

    public static float[] materializeDraw(Object entity) {
        Buff buff = buffFor(entity);
        if (buff == null || buff.age >= MATERIALIZE_FRAMES) return null;
        float t = clamp01(buff.age / (float) MATERIALIZE_FRAMES);
        float scaleY = Math.max(0.04f, 1f - (1f - t) * (1f - t));
        float alpha = clamp01(t * 1.5f);
        return new float[]{0f, scaleY, alpha};
    }

    public static void applyDamageMultiplier(CrazyRuntime.StageRuntime rt, AttackAb attack) {
        if (rt == null || attack == null || attack.attacker == null) return;
        Buff buff = rt.theRitual.buffs.get(attack.attacker);
        if (buff == null || buff.sacrifices <= 0) return;
        if (!rt.theRitual.damageScaled.add(attack)) return;
        long next = Math.round((double) attack.atk * (1.0d + buff.sacrifices * ATK_PCT_PER));
        attack.atk = clampInt(next);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void appendScaledHitboxes(CrazyRuntime.StageRuntime rt, List result, int touch, int dire,
                                            float d0, float d1, boolean excludeRightEdge) {
        if (rt == null || result == null || dire == 0) return;
        float left = Math.min(d0, d1);
        float right = Math.max(d0, d1);
        for (Object obj : new ArrayList<Object>(rt.theRitual.buffs.keySet())) {
            if (!(obj instanceof Entity)) continue;
            Entity e = (Entity) obj;
            Buff buff = rt.theRitual.buffs.get(e);
            if (buff == null || buff.sacrifices <= 0) continue;
            if (e.dire != dire || e.dead || e.health <= 0L || e.isBase()) continue;
            if (!touchable(e, touch)) continue;
            float scale = 1f + buff.sacrifices * SCALE_PCT_PER;
            float extra = scaledHitboxExtra(e, scale);
            boolean inside = excludeRightEdge
                    ? e.pos >= left - extra && e.pos < right + extra
                    : e.pos >= left - extra && e.pos <= right + extra;
            if (inside && !result.contains(e)) result.add(e);
        }
    }

    public static boolean isRitualSummoned(Object entity) {
        return buffFor(entity) != null;
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        return rt != null && (!rt.theRitual.buffs.isEmpty()
                || !rt.theRitual.vfx.isEmpty()
                || rt.theRitual.pending != null
                || rt.theRitual.cooldown > 0
                || rt.theRitual.shakeFrame > 0);
    }

    private static boolean activate(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return false;
        ensureInitialized(rt);
        State st = rt.theRitual;
        if (st.pending != null) return false;
        StageBasis sb = (StageBasis) rt.stage;
        List<Entity> sacrifices = collectSacrifices(sb);
        if (sacrifices.isEmpty() || st.pool.isEmpty()) return false;
        int count = sacrifices.size();
        Candidate c = pickTieredCandidate(sb, st.pool, count);
        if (c == null || c.form == null) return false;

        for (int i = 0; i < sacrifices.size(); i++) {
            Entity e = sacrifices.get(i);
            if (e == null) continue;
            st.vfx.add(new RitualVfx(e.pos, safeLayer(e), false, stableSeed(e, i), 0, 0));
            rewardEnemySacrifice(sb, e);
            removeSacrificed(sb, e);
        }

        float pos = playerBaseSpawnPos(sb);
        st.vfx.add(new RitualVfx(pos, 0, true, stableSeed(c.form, count), count, SUMMON_VFX_DELAY));
        st.pending = new PendingSummon(c.form, c.key, count, pos, SUMMON_REVEAL_FRAME);
        st.cooldown = COOLDOWN_FRAMES;
        st.shakeFrame = 0;
        try { CommonStatic.setSE(29); } catch (Throwable ignored) {}
        Logger.log("The Ritual initiated key=" + c.key
                + " sacrifices=" + count
                + " hpProbe=" + c.hp
                + " atkProbe=" + c.attack
                + " pool=" + st.pool.size());
        return true;
    }

    private static void revealSummon(CrazyRuntime.StageRuntime rt, State st, PendingSummon ps) {
        ps.revealed = true;
        try {
            StageBasis sb = (StageBasis) rt.stage;
            EUnit unit = createSummonedUnit(sb, ps.form);
            if (unit == null) return;
            unit.added(-1, ps.pos);
            prepareSpawnedUnit(unit);
            applySpawnBuff(st, unit, ps.count);
            addEntitySorted(sb, unit);
            try { CommonStatic.setSE(29); } catch (Throwable ignored) {}
            Logger.log("The Ritual revealed key=" + ps.key + " sacrifices=" + ps.count);
        } catch (Throwable t) {
            Logger.err("The Ritual reveal failed", t);
        }
    }

    private static List<Entity> collectSacrifices(StageBasis sb) {
        ArrayList<Entity> out = new ArrayList<Entity>();
        if (sb == null || sb.le == null) return out;
        for (int i = 0; i < sb.le.size(); i++) {
            Entity e = sb.le.get(i);
            if (e == null || e.dead || e.health <= 0L || e.isBase()) continue;
            if (EntityAccess.isBoss(e)) continue;
            if (isRitualSummoned(e)) continue;
            out.add(e);
        }
        return out;
    }

    private static EUnit createSummonedUnit(StageBasis sb, Form form) {
        try {
            EForm ef = new EForm(form, summonLevel());
            return ef.getEntity(sb, null, false, false);
        } catch (Throwable t) {
            Logger.err("The Ritual summon creation failed", t);
            return null;
        }
    }

    private static void applySpawnBuff(State st, EUnit unit, int sacrifices) {
        if (unit == null) return;
        int count = Math.max(0, sacrifices);
        long baseMax = Math.max(1L, unit.maxH);
        long boosted = scaleLong(baseMax, count);
        unit.maxH = Math.max(1L, boosted);
        unit.health = unit.maxH;
        int baseSpeed = 1;
        try { baseSpeed = Math.max(1, unit.data.getSpeed()); } catch (Throwable ignored) {}
        st.buffs.put(unit, new Buff(count, baseMax, baseSpeed));
    }

    private static void rewardEnemySacrifice(StageBasis sb, Entity e) {
        if (sb == null || !(e instanceof EEnemy) || e.dire != 1) return;
        try {
            if (!stageAllowsEnemyDrop(sb)) return;
            float statusBonus = 0f;
            try { statusBonus = e.status[52][0] / 100.0f; } catch (Throwable ignored) {}
            float mul = dropMultiplier(sb) * (1.0f + statusBonus);
            int drop = ((MaskEnemy) e.data).getDrop();
            long next = Math.round((double) sb.money + (double) mul * (double) drop);
            sb.money = clampInt(next);
        } catch (Throwable t) {
            Logger.err("The Ritual enemy reward failed", t);
        }
    }

    private static boolean stageAllowsEnemyDrop(StageBasis sb) {
        if (sb == null) return false;
        try {
            return BCUFields.getBoolean(sb.st, "drop");
        } catch (Throwable ignored) {}
        try {
            if (BCUFields.getBoolean(sb.st, "trail")) return false;
        } catch (Throwable ignored) {}
        try {
            if (sb.maxBankLimit() > 0) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    private static float dropMultiplier(StageBasis sb) {
        if (sb == null || sb.b == null) return 1f;
        try {
            Object treasure = BCUFields.invoke(sb.b, "t");
            Method m = BCUFields.method(treasure.getClass(), "getDropMulti");
            Object value = m.invoke(treasure);
            float base = value instanceof Number ? ((Number) value).floatValue() : 1f;
            return base * (1.0f + comboInc(sb, 12) * 0.01f);
        } catch (Throwable ignored) {}
        try {
            Object treasure = BCUFields.invoke(sb.b, "t");
            Method m = BCUFields.method(treasure.getClass(), "getDropMulti", boolean.class);
            Object value = m.invoke(treasure, isComboBanned(sb, 12));
            return value instanceof Number ? ((Number) value).floatValue() : 1f;
        } catch (Throwable ignored) {}
        return 1f;
    }

    private static int comboInc(StageBasis sb, int comboId) {
        if (sb == null || sb.b == null || isComboBanned(sb, comboId)) return 0;
        Method[] methods = sb.b.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (!"getInc".equals(m.getName()) || m.getParameterTypes().length != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] != int.class && p[0] != Integer.TYPE) continue;
            try {
                Object value = m.invoke(sb.b, comboId, new ArrayList<Object>());
                return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0;
            } catch (Throwable ignored) {}
        }
        try {
            Method m = BCUFields.method(sb.b.getClass(), "getInc", int.class);
            Object value = m.invoke(sb.b, comboId);
            return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0;
        } catch (Throwable ignored) {}
        return 0;
    }

    private static boolean isComboBanned(StageBasis sb, int comboId) {
        try {
            Object est = BCUFields.get(sb, "est");
            Object lim = BCUFields.get(est, "lim");
            Class<?> stageLimit = Class.forName("common.util.stage.StageLimit");
            Method[] methods = stageLimit.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                if (!"isComboBanned".equals(method.getName()) || method.getParameterTypes().length != 2) continue;
                method.setAccessible(true);
                Object value = method.invoke(null, lim, comboId);
                return value instanceof Boolean && ((Boolean) value).booleanValue();
            }
        } catch (Throwable ignored) {}
        try {
            Method method = BCUFields.method(sb.getClass(), "isBanned", byte.class);
            Object value = method.invoke(sb, (byte) comboId);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable ignored) {}
        return false;
    }

    private static void removeSacrificed(StageBasis sb, Entity entity) {
        if (sb == null || entity == null) return;
        cleanupExternalMotion(entity);
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

    private static void addEntitySorted(StageBasis sb, Entity e) {
        sb.le.add(e);
        Collections.sort(sb.le, new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                return Integer.compare(a.currentLayer, b.currentLayer);
            }
        });
    }

    private static void prepareSpawnedUnit(EUnit unit) {
        if (unit == null) return;
        try {
            unit.getProc().MONEYBACK.mult = 0;
            unit.getProc().CANONCHARGE.mult = 0;
        } catch (Throwable ignored) {}
        try {
            int[] delayedCooldown = unit.status[64];
            if (delayedCooldown != null) {
                for (int i = 0; i < delayedCooldown.length; i++) delayedCooldown[i] = 0;
            }
        } catch (Throwable ignored) {}
        try { BCUFields.setInt(unit, "price", 0); } catch (Throwable ignored) {}
    }

    private static void ensureInitialized(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        State st = rt.theRitual;
        if (st.initialized) return;
        synchronized (st.lock) {
            if (st.initialized) return;
            buildPool(rt);
            st.initialized = true;
        }
    }

    private static void buildPool(CrazyRuntime.StageRuntime rt) {
        State st = rt.theRitual;
        st.pool.clear();
        int scanned = 0, hpQ = 0, atkQ = 0;
        try {
            long start = System.currentTimeMillis();
            StageBasis sb = (StageBasis) rt.stage;

            List<Unit> units = null;
            try { units = UserProfile.getBCData().units.getList(); } catch (Throwable ignored) {}

            java.util.Set<String> summonTargets = collectSummonTargets(units);
            int excludedSummon = 0;
            if (units != null) {
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (u == null || u.id == null || u.forms == null) continue;
                    if (!OFFICIAL_PACK.equals(u.id.pack)) continue;
                    if (summonTargets.contains(u.id.pack + ":" + u.id.id)) { excludedSummon++; continue; }
                    for (int f = 0; f < u.forms.length; f++) {
                        scanned++;
                        Candidate c = candidateForForm(sb, u.forms[f]);
                        if (c == null) continue;
                        st.pool.add(c);
                        if (c.hp > HP_THRESHOLD) hpQ++;
                        if (c.attack > ATTACK_THRESHOLD) atkQ++;
                    }
                }
            }

            if (st.pool.isEmpty()) {
                CrazyPreloadService.Snapshot snap = CrazyPreloadService.currentSnapshot();
                if (snap != null) {
                    for (int i = 0; i < snap.officialPlayerForms.size(); i++) {
                        Candidate c = candidateForForm(sb, snap.officialPlayerForms.get(i).form);
                        if (c != null) st.pool.add(c);
                    }
                }
            }

            Collections.sort(st.pool, new Comparator<Candidate>() {
                @Override
                public int compare(Candidate a, Candidate b) {
                    return Double.compare(a.score, b.score);
                }
            });
            Logger.log("The Ritual pool initialized: forms=" + st.pool.size()
                    + " scanned=" + scanned + " hpQualified=" + hpQ + " atkQualified=" + atkQ
                    + " excludedSummon=" + excludedSummon + " summonTargets=" + summonTargets.size()
                    + " time=" + (System.currentTimeMillis() - start) + "ms");
        } catch (Throwable t) {
            Logger.err("The Ritual pool build failed", t);
        }
        if (st.pool.isEmpty() && !st.poolWarningLogged) {
            st.poolWarningLogged = true;
            Logger.log("The Ritual has no eligible official player forms. "
                    + "Try lower HP/attack thresholds or allow a broader pool.");
        }
    }

    private static Candidate candidateForForm(StageBasis sb, Form form) {
        if (form == null || form.du == null || form.unit == null || form.unit.id == null) return null;
        try {
            String key = "U:" + form.unit.id.pack + ":" + form.unit.id.id + ":" + form.fid;
            EUnit probe = new EForm(form, summonLevel()).getEntity(sb, null, false, false);
            long hp = probe == null ? 0L : Math.max(0L, probe.maxH);
            long atk = probe == null ? 0L : totalAttack(probe);
            if (hp > HP_THRESHOLD || atk > ATTACK_THRESHOLD) {
                return new Candidate(form, key, hp, atk);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static java.util.Set<String> collectSummonTargets(List<Unit> units) {
        java.util.HashSet<String> out = new java.util.HashSet<String>();
        if (units == null) return out;
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (u == null || u.forms == null) continue;
            for (int f = 0; f < u.forms.length; f++) {
                Form form = u.forms[f];
                if (form == null || form.du == null) continue;
                try {
                    Object proc = BCUFields.invoke(form.du, "getProc");
                    if (proc == null) continue;
                    try { addSummonTarget(out, BCUFields.get(proc, "SUMMON")); } catch (Throwable ignored) {}
                    try { addSummonTarget(out, BCUFields.get(proc, "IMUSUMMON")); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static void addSummonTarget(java.util.Set<String> out, Object summon) {
        if (summon == null) return;
        try {
            int prob = BCUFields.getInt(summon, "prob");
            if (prob <= 0) return;
            Object id = BCUFields.get(summon, "id");
            if (id == null) return;
            Object pack = BCUFields.get(id, "pack");
            Object iid = BCUFields.get(id, "id");
            out.add(String.valueOf(pack) + ":" + String.valueOf(iid));
        } catch (Throwable ignored) {}
    }

    private static Level summonLevel() {
        Level level = new Level(SUMMON_LEVEL);
        try { level.setPlusLevel(SUMMON_PLUS_LEVEL); } catch (Throwable ignored) {}
        try { if (level.getOrbs() == null) level.setOrbs(new int[0][]); } catch (Throwable ignored) {}
        return level;
    }

    private static Candidate pickTieredCandidate(StageBasis sb, ArrayList<Candidate> pool, int count) {
        if (pool == null || pool.isEmpty()) return null;
        int n = pool.size();
        if (n == 1) return pool.get(0);
        float t = clamp01((Math.max(1, count) - 1) / (float) Math.max(1, TIER_SATURATION - 1));
        int center = Math.round(t * (n - 1));
        int w = Math.max(1, Math.round(n * TIER_WINDOW_FRAC));
        int lo = Math.max(0, center - w);
        int hi = Math.min(n - 1, center + w);
        return pool.get(lo + randInt(sb, hi - lo + 1));
    }

    private static void cleanupTracked(State st) {
        for (Object obj : new ArrayList<Object>(st.buffs.keySet())) {
            if (!(obj instanceof Entity)) {
                st.buffs.remove(obj);
                st.beforePos.remove(obj);
                st.motion.remove(obj);
                continue;
            }
            Entity e = (Entity) obj;
            if (e.dead || e.health <= 0L) {
                st.buffs.remove(e);
                st.beforePos.remove(e);
                st.motion.remove(e);
            }
        }
    }

    private static void capturePositions(State st) {
        st.beforePos.clear();
        for (Object obj : new ArrayList<Object>(st.buffs.keySet())) {
            if (obj instanceof Entity) {
                Entity e = (Entity) obj;
                if (!e.dead && e.health > 0L) st.beforePos.put(e, e.pos);
            }
        }
    }

    private static void applyMovementBonus(State st, Entity e, Buff buff) {
        if (buff == null || buff.sacrifices <= 0) return;
        Float before = st.beforePos.get(e);
        if (before == null) return;
        float delta = e.pos - before.floatValue();
        if (!finite(delta) || Math.abs(delta) <= 0.0001f || Math.abs(delta) >= 2000f) return;
        float bonusSpeed = buff.sacrifices * MOVESPD_UNITS_PER;
        float multiplier = 1f + bonusSpeed / Math.max(1f, buff.baseSpeed);
        float extra = delta * Math.max(0f, multiplier - 1f);
        if (!finite(extra)) return;
        e.pos += extra;
        try { BCUFields.field(e.getClass(), "lastPosition").setFloat(e, e.pos); } catch (Throwable ignored) {}
    }

    private static void accelerateTimers(State st, Entity e, int sacrifices) {
        if (sacrifices <= 0) return;
        MotionState ms = st.motion.get(e);
        if (ms == null) {
            ms = new MotionState();
            st.motion.put(e, ms);
        }
        try {
            int wait = BCUFields.getInt(e, "waitTime");
            if (wait > 1 && (ms.lastWait <= 1 || wait > ms.lastWait + 1)) {
                wait = reduceFrames(wait, sacrifices);
                BCUFields.setInt(e, "waitTime", wait);
            }
            ms.lastWait = wait;
        } catch (Throwable ignored) {}
        try {
            Object atkm = BCUFields.get(e, "atkm");
            int pre = BCUFields.getInt(atkm, "preTime");
            int atk = BCUFields.getInt(atkm, "atkTime");
            if (pre > 1 && (ms.lastPre <= 1 || pre > ms.lastPre + 1)) {
                pre = reduceFrames(pre, sacrifices);
                BCUFields.setInt(atkm, "preTime", pre);
            }
            if (atk > 1 && (ms.lastAtk <= 1 || atk > ms.lastAtk + 1)) {
                atk = reduceFrames(atk, sacrifices);
                BCUFields.setInt(atkm, "atkTime", atk);
            }
            ms.lastPre = pre;
            ms.lastAtk = atk;
            fastForwardAttackAnimation(ms, e, sacrifices);
        } catch (Throwable ignored) {}
    }

    private static void fastForwardAttackAnimation(MotionState ms, Entity e, int sacrifices) {
        if (ms == null || e == null || sacrifices <= 0) return;
        try {
            Object animMgr = BCUFields.get(e, "anim");
            Object animObj = BCUFields.get(animMgr, "anim");
            if (!(animObj instanceof EAnimU)) return;
            EAnimU anim = (EAnimU) animObj;
            Object type = BCUFields.get(anim, "type");
            if (!(type instanceof Enum) || !"ATK".equals(((Enum<?>) type).name())) return;
            int len = Math.max(1, anim.len());
            int reduced = reduceFrames(len, sacrifices);
            if (reduced >= len) return;
            float cur = anim.ind();
            if (!finite(cur) || cur < 0f) return;
            float speed = len / (float) Math.max(1, reduced);
            float extra = nativeAnimationStep(anim) * Math.max(0f, speed - 1f);
            float next = Math.min(Math.max(0f, len - 1f), cur + extra);
            if (next > cur + 0.001f) {
                anim.setTime(next);
                if (!ms.attackAnimLogged) {
                    ms.attackAnimLogged = true;
                    Logger.log("The Ritual attack animation speed active sacrifices=" + sacrifices);
                }
            }
        } catch (Throwable t) {
            Logger.err("The Ritual attack animation speed failed", t);
        }
    }

    private static float nativeAnimationStep(EAnimU anim) {
        try {
            if (CommonStatic.getConfig().performanceModeAnimation && anim != null && anim.ind() >= 0f) {
                return 0.5f;
            }
        } catch (Throwable ignored) {}
        return 1f;
    }

    private static int reduceFrames(int base, int sacrifices) {
        double faster = 1.0d + Math.max(0, sacrifices) * ATKSPD_PCT_PER;
        long reduced = Math.round(Math.max(1, base) / faster);
        return Math.max(1, clampInt(reduced));
    }

    private static Buff buffFor(Object entity) {
        if (!(entity instanceof Entity)) return null;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return null;
        return rt.theRitual.buffs.get(entity);
    }

    private static void drawHudIcon(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        Rect r = iconRectFromPainter(bbpainter);
        State st = rt.theRitual;
        ensureTextures(st);
        boolean on = usable(rt);
        boolean gl = isGl(gra);
        int cx = r.cx() + shakeOffset(st);
        int cy = r.cy();
        FakeTransform old = pushIdentityTransform(gra);
        try {
            drawIconMedallion(gra, gl, st, cx, cy, r.w, on, st.cooldown);
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void drawIconMedallion(FakeGraphics gra, boolean gl, State st,
                                          int cx, int cy, int size, boolean active, int cooldown) {
        float rad = size * 0.5f;
        Color rim = active ? DIVINE_RIM : new Color(120, 116, 104);
        Color rimHi = active ? DIVINE_HOT : new Color(168, 160, 142);
        Color center = active ? DIVINE_NIGHT : new Color(30, 28, 36);

        fillEllipse(gra, gl, cx + 2, cy + 3, rad, rad, Color.BLACK, 110);
        fillEllipse(gra, gl, cx, cy, rad, rad, rim, 255);
        fillEllipse(gra, gl, cx, cy, rad - 2f, rad - 2f, rimHi, 255);
        fillEllipse(gra, gl, cx, cy, rad - 4f, rad - 4f, rim, 255);
        fillEllipse(gra, gl, cx, cy, rad - 7f, rad - 7f, center, 255);

        float pulse = active ? 0.85f + 0.15f * (float) Math.sin(phase() * 3.2f) : 0.5f;
        if (active && st.glowTex != null) glowAdd(gra, st.glowTex, cx, cy, rad * 2.3f, rad * 2.3f, Math.round(70f * pulse));
        if (st.ringTex != null) glowAdd(gra, st.ringTex, cx, cy, rad * 1.7f, rad * 1.7f, active ? 150 : 70);
        if (st.glowTex != null) glowAdd(gra, st.glowTex, cx, cy, rad * 0.9f, rad * 0.9f, active ? 120 : 60);

        Color rune = active ? DIVINE_HOT : new Color(150, 146, 132);
        for (int k = 0; k < 8; k++) {
            double a = -Math.PI / 2.0 + k * (Math.PI * 2.0 / 8);
            fillEllipse(gra, gl, cx + (float) Math.cos(a) * rad * 0.62f, cy + (float) Math.sin(a) * rad * 0.62f,
                    rad * 0.045f, rad * 0.045f, rune, active ? 235 : 150);
        }

        Color paw = active ? new Color(255, 244, 206) : new Color(150, 146, 132);
        int pa = active ? 255 : 170;
        fillEllipse(gra, gl, cx, cy + rad * 0.13f, rad * 0.26f, rad * 0.22f, paw, pa);
        fillEllipse(gra, gl, cx - rad * 0.27f, cy - rad * 0.11f, rad * 0.095f, rad * 0.12f, paw, pa);
        fillEllipse(gra, gl, cx - rad * 0.10f, cy - rad * 0.26f, rad * 0.095f, rad * 0.12f, paw, pa);
        fillEllipse(gra, gl, cx + rad * 0.10f, cy - rad * 0.26f, rad * 0.095f, rad * 0.12f, paw, pa);
        fillEllipse(gra, gl, cx + rad * 0.27f, cy - rad * 0.11f, rad * 0.095f, rad * 0.12f, paw, pa);

        if (cooldown > 0) drawCooldownMeter(gra, gl, st, cx, cy, rad, 1f - cooldown / (float) COOLDOWN_FRAMES);
    }

    private static void drawCooldownMeter(FakeGraphics gra, boolean gl, State st,
                                          int cx, int cy, float rad, float progress) {
        fillEllipse(gra, gl, cx, cy, rad - 7f, rad - 7f, Color.BLACK, Math.round(150f * (1f - clamp01(progress))));
        int dots = 24;
        float ring = rad * 0.86f;
        int litCount = Math.round(clamp01(progress) * dots);
        for (int k = 0; k < dots; k++) {
            double a = -Math.PI / 2.0 + k * (Math.PI * 2.0 / dots);
            float dx = cx + (float) Math.cos(a) * ring;
            float dy = cy + (float) Math.sin(a) * ring;
            if (k < litCount && st.sparkTex != null) {
                glowAdd(gra, st.sparkTex, dx, dy, rad * 0.26f, rad * 0.26f, 220);
            } else {
                fillEllipse(gra, gl, dx, dy, rad * 0.05f, rad * 0.05f, new Color(70, 66, 58), 200);
            }
        }
    }

    private static void drawVfx(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.theRitual;
        ensureTextures(st);
        boolean gl = isGl(gra);
        float siz = 1f;
        try { siz = BBPainterAccess.getSiz(bbpainter); } catch (Throwable ignored) {}
        siz = Math.max(0.45f, siz);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            for (int i = 0; i < st.vfx.size(); i++) {
                RitualVfx fx = st.vfx.get(i);
                if (fx == null || !fx.started()) continue;
                float x = CrazyRender.screenX(bbpainter, fx.pos);
                float groundY = CrazyRender.groundY(bbpainter, fx.layer);
                float p = clamp01(fx.age / (float) Math.max(1, fx.maxAge()));
                if (fx.spawn) drawSummonVfx(gra, gl, st, x, groundY, fx, siz, p);
                else drawSacrificeVfx(gra, gl, st, x, groundY, siz, p, fx.seed);
            }
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void drawSacrificeVfx(FakeGraphics gra, boolean gl, State st,
                                         float x, float groundY, float siz, float p, int seed) {
        float fade = 1f - p;
        float cy = groundY - 46f * siz;
        if (st.ringTex != null) {
            float ring = (50f * (1.1f - p)) * siz;
            glowAdd(gra, st.ringTex, x, cy, ring * 2f, ring * 2f, Math.round(150f * fade));
        }
        if (st.sparkTex != null) {
            for (int k = 0; k < 6; k++) {
                double a = (seed % 360) * Math.PI / 180.0 + k * (Math.PI / 3.0);
                float dist = fade * 40f * siz;
                float mx = x + (float) Math.cos(a) * dist;
                float my = cy + (float) Math.sin(a) * dist - p * 18f * siz;
                glowAdd(gra, st.sparkTex, mx, my, 16f * siz, 16f * siz, Math.round(200f * fade));
            }
        }
        if (st.glowTex != null) {
            float pop = (float) Math.sin(clamp01(p) * Math.PI);
            glowAdd(gra, st.glowTex, x, cy, 64f * siz * pop, 64f * siz * pop, Math.round(220f * pop));
            glowAdd(gra, st.glowTex, x, cy - p * 50f * siz, 10f * siz, 44f * siz, Math.round(120f * fade));
        } else {

            fillEllipse(gra, gl, x, cy, 22f * siz * fade, 22f * siz * fade, DIVINE_HOT, Math.round(150f * fade));
        }
    }

    private static void drawSummonVfx(FakeGraphics gra, boolean gl, State st,
                                      float x, float groundY, RitualVfx fx, float siz, float p) {
        float power = 1f + Math.min(fx.count, 40) * 0.04f;
        float sealY = groundY;
        float bloomY = groundY - 60f * siz;
        float charge = clamp01(p / 0.25f);
        float burst = smooth(clamp01((p - 0.20f) / 0.35f));

        float sealA = charge * (p < 0.85f ? 1f : clamp01((1f - p) / 0.15f));
        if (sealA > 0f && st.ringTex != null) {
            float sealR = (70f + 26f * charge) * siz * power;
            glowAdd(gra, st.ringTex, x, sealY, sealR * 2.2f, sealR * 0.9f, Math.round(170f * sealA));
            glowAdd(gra, st.ringTex, x, sealY, sealR * 1.5f, sealR * 0.62f, Math.round(120f * sealA));
            if (st.sparkTex != null) {
                float rot = p * 3.0f + fx.seed * 0.01f;
                for (int k = 0; k < 8; k++) {
                    double a = rot + k * (Math.PI * 2.0 / 8);
                    float nx = x + (float) Math.cos(a) * sealR;
                    float ny = sealY + (float) Math.sin(a) * sealR * 0.42f;
                    glowAdd(gra, st.sparkTex, nx, ny, 20f * siz, 20f * siz, Math.round(170f * sealA));
                }
            }
        }

        float pillarA = burst * clamp01(1f - (p - 0.6f) / 0.4f);
        if (pillarA > 0f) {
            float pillarH = (200f + 120f * power) * siz;
            float halfW = (26f + 10f * power) * siz;
            int bands = Math.max(8, Math.round(pillarH / (9f * siz)));
            for (int i = 0; i < bands; i++) {
                float f0 = i / (float) bands;
                float by = sealY - pillarH * ((i + 1) / (float) bands);
                float bh = pillarH / bands + 1f;
                float fade = (1f - f0) * (1f - f0);
                float hw = halfW * (1f - 0.45f * f0);
                colFill(gra, gl, x - hw, by, hw * 2f, bh, DIVINE_DEEP, Math.round(30f * pillarA * fade));
                colFill(gra, gl, x - hw * 0.6f, by, hw * 1.2f, bh, DIVINE_HOT, Math.round(36f * pillarA * fade));
                colFill(gra, gl, x - hw * 0.26f, by, hw * 0.52f, bh, DIVINE_CORE, Math.round(44f * pillarA * fade));
            }
            if (st.glowTex != null) {
                float gd = halfW * 2.2f;
                int n = Math.max(1, Math.round(pillarH / (gd * 0.42f)));
                for (int i = 0; i <= n; i++) {
                    float fr = i / (float) n;
                    float yy = sealY - pillarH * fr;
                    float fade = (1f - fr) * (1f - fr);
                    float gw = gd * (1f - 0.4f * fr);
                    glowAdd(gra, st.glowTex, x, yy, gw, gw, Math.round(72f * pillarA * fade));
                }
            }
        }

        if (st.glowTex != null) {
            float bloomA = (float) Math.exp(-((p - 0.35f) * (p - 0.35f)) / (2f * 0.13f * 0.13f));
            if (bloomA > 0.01f) {
                float bw = (120f + 60f * power) * siz * (0.6f + 0.6f * p);
                glowAdd(gra, st.glowTex, x, bloomY, bw, bw, Math.round(220f * bloomA));
                glowAdd(gra, st.glowTex, x, bloomY, 60f * siz, 60f * siz, Math.round(180f * bloomA));
            }
        }

        if (st.glowTex != null) {
            float deliverA = (float) Math.exp(-((p - 0.66f) * (p - 0.66f)) / (2f * 0.09f * 0.09f));
            if (deliverA > 0.01f) {
                float dw = (150f + 80f * power) * siz;
                glowAdd(gra, st.glowTex, x, bloomY, dw, dw, Math.round(235f * deliverA));
                if (st.ringTex != null) {
                    glowAdd(gra, st.ringTex, x, sealY, (170f + 80f * power) * siz,
                            (74f + 30f * power) * siz, Math.round(150f * deliverA));
                }
            }
        }

        if (st.ringTex != null) {
            for (int k = 0; k < 3; k++) {
                float rp = clamp01((p - 0.25f - k * 0.08f) / 0.5f);
                if (rp <= 0f) continue;
                float ringR = rp * (160f + 80f * power) * siz;
                glowAdd(gra, st.ringTex, x, bloomY, ringR * 2f, ringR, Math.round(150f * (1f - rp)));
            }
        }

        float sp = clamp01((p - 0.45f) / 0.55f);
        if (sp > 0f && st.sparkTex != null) {
            for (int k = 0; k < 10; k++) {
                double a = fx.seed * 0.013f + k * (Math.PI * 2.0 / 10);
                float rad2 = (40f + 70f * power) * siz;
                float fall = sp * (60f + 40f * power) * siz;
                float sx = x + (float) Math.cos(a) * rad2 * (1f - 0.3f * sp);
                float sy = bloomY + (float) Math.sin(a) * rad2 * 0.5f + fall - 30f * siz;
                glowAdd(gra, st.sparkTex, sx, sy, 16f * siz, 16f * siz, Math.round(180f * (1f - sp)));
            }
        }

        if (st.glowTex == null) {

            float bw = (60f + 30f * power) * siz * (0.5f + p);
            fillEllipse(gra, gl, x, bloomY, bw, bw, DIVINE_HOT, Math.round(180f * (1f - p)));
        }
    }

    private static boolean isGl(FakeGraphics gra) {
        return gra != null && gra.getClass().getName().contains("GLGraphics");
    }

    private static float phase() {
        return (System.currentTimeMillis() % 100000L) / 1000f;
    }

    private static void ensureTextures(State st) {
        if (st == null || st.texturesBaked) return;
        try {
            if (ImageBuilder.builder == null) return;
            FakeImage glow = bakeRadialGlow(DIVINE_CORE, DIVINE_HOT, DIVINE_DEEP);
            FakeImage ring = bakeRing(DIVINE_HOT);
            FakeImage spark = bakeSpark();
            if (glow != null && ring != null && spark != null) {
                st.glowTex = glow;
                st.ringTex = ring;
                st.sparkTex = spark;
                st.texturesBaked = true;
                Logger.log("The Ritual VFX textures baked (divine gold)");
            }
        } catch (Throwable t) {
            if (!st.textureWarningLogged) {
                st.textureWarningLogged = true;
                Logger.err("The Ritual texture bake failed", t);
            }
        }
    }

    private static void glowAdd(FakeGraphics g, FakeImage tex, float cx, float cy, float w, float h, int alpha) {
        if (tex == null || alpha <= 1 || w < 2f || h < 2f) return;
        try {
            g.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, alpha)), 1);
            g.drawImage(tex, cx - w / 2f, cy - h / 2f, w, h);
        } catch (Throwable ignored) {
        } finally {
            g.setComposite(FakeGraphics.DEF, 0, 0);
        }
    }

    private static void colFill(FakeGraphics g, boolean gl, float x, float y, float w, float h, Color c, int alpha) {
        if (alpha <= 0 || w < 1f || h < 1f) return;
        int a = Math.max(0, Math.min(255, alpha));
        if (gl) {
            g.colRect(x, y, w, h, c.getRed(), c.getGreen(), c.getBlue(), a);
        } else {
            try {
                g.setComposite(FakeGraphics.TRANS, a, 0);
                g.setColor(c.getRed(), c.getGreen(), c.getBlue());
                g.fillRect(Math.round(x), Math.round(y), Math.max(1, Math.round(w)), Math.max(1, Math.round(h)));
            } finally {
                g.setComposite(FakeGraphics.DEF, 0, 0);
            }
        }
    }

    private static void fillEllipse(FakeGraphics g, boolean gl, float cx, float cy, float rx, float ry, Color c, int alpha) {
        if (alpha <= 0 || rx <= 0f || ry <= 0f) return;
        int a = Math.max(0, Math.min(255, alpha));
        int iy = Math.max(1, Math.round(ry));
        int ix = Math.max(1, Math.round(rx));
        if (!gl) {
            g.setComposite(FakeGraphics.TRANS, a, 0);
            g.setColor(c.getRed(), c.getGreen(), c.getBlue());
        }
        try {
            for (int dy = -iy; dy <= iy; dy++) {
                float yy = dy / (float) iy;
                int span = Math.round(ix * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
                if (span <= 0) continue;
                if (gl) g.colRect(cx - span, cy + dy, span * 2 + 1, 1, c.getRed(), c.getGreen(), c.getBlue(), a);
                else g.fillRect(Math.round(cx) - span, Math.round(cy) + dy, span * 2 + 1, 1);
            }
        } finally {
            if (!gl) g.setComposite(FakeGraphics.DEF, 0, 0);
        }
    }

    private static FakeImage bakeRadialGlow(Color c0, Color c1, Color c2) {
        final int N = 64;
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float dx = (x - c) / (N / 2f), dy = (y - c) / (N / 2f);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                Color bse = (d < 0.45f)
                        ? lerp(c0, c1, smooth(d / 0.45f))
                        : lerp(c1, c2, smooth(clamp01((d - 0.45f) / 0.55f)));
                float env = clamp01(1f - d);
                env = env * env;
                int r = Math.round(bse.getRed() * env);
                int gg = Math.round(bse.getGreen() * env);
                int b = Math.round(bse.getBlue() * env);
                int a = clamp255(255f * env);
                img.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return img;
    }

    private static FakeImage bakeRing(Color base) {
        final int N = 64;
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float d = (float) Math.sqrt((x - c) * (x - c) + (y - c) * (y - c)) - 23f;
                float env = (float) Math.exp(-(d * d) / (2f * 3.1f * 3.1f));
                float core = (float) Math.exp(-(d * d) / (2f * 1.1f * 1.1f));
                int r = clamp255(base.getRed() * env + 255f * core * 0.85f);
                int g = clamp255(base.getGreen() * env + 255f * core * 0.85f);
                int b = clamp255(base.getBlue() * env + 255f * core * 0.85f);
                int a = clamp255(255f * clamp01(env + core));
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static FakeImage bakeSpark() {
        final int N = 32;
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float fx = x - c, fy = y - c;
                float armH = (float) (Math.pow(Math.max(0f, 1f - Math.abs(fx) / 14f), 2.6)
                        * Math.pow(Math.max(0f, 1f - Math.abs(fy) / 1.9f), 2.0));
                float armV = (float) (Math.pow(Math.max(0f, 1f - Math.abs(fy) / 11f), 2.6)
                        * Math.pow(Math.max(0f, 1f - Math.abs(fx) / 1.9f), 2.0));
                float rr = (float) Math.sqrt(fx * fx + fy * fy);
                float glow = (float) Math.pow(Math.max(0f, 1f - rr / 5.5f), 2.0);
                float i = clamp01(Math.max(armH, Math.max(armV, glow)));
                int v = clamp255(255f * i);
                int a = clamp255(255f * i);
                img.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static Color lerp(Color a, Color b, float t) {
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

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static boolean usable(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return false;
        ensureInitialized(rt);
        State st = rt.theRitual;
        return st.cooldown <= 0
                && !st.pool.isEmpty()
                && battleRunning((StageBasis) rt.stage)
                && !collectSacrifices((StageBasis) rt.stage).isEmpty();
    }

    private static boolean enabled(CrazyRuntime.StageRuntime rt) {
        return rt != null && rt.config.theRitual;
    }

    private static boolean battleRunning(StageBasis sb) {
        try {
            return sb != null && sb.ubase != null && sb.ebase != null
                    && sb.ubase.health > 0L && sb.ebase.health > 0L && sb.s_stop == 0;
        } catch (Throwable ignored) {
            return false;
        }
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
        int centerX = Math.round(w * 0.493f + size * 1.18f);
        int centerY = Math.round(h * 0.071f);
        return new Rect(centerX - size / 2, centerY - size / 2, size, size);
    }

    private static int shakeOffset(State st) {
        if (st == null || st.shakeFrame <= 0) return 0;
        int f = st.shakeFrame;
        return Math.round((float) Math.sin(f * 2.7f) * Math.min(10f, 2f + f * 0.35f));
    }

    private static FakeTransform pushIdentityTransform(FakeGraphics gra) {
        if (gra == null) return null;
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

    private static void popTransform(FakeGraphics gra, FakeTransform old) {
        if (gra == null || old == null) return;
        try {
            gra.setTransform(old);
            gra.delete(old);
        } catch (Throwable ignored) {}
    }

    private static void resetComposite(FakeGraphics gra) {
        try { if (gra != null) gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private static int safeLayer(Entity e) {
        try { return Math.max(0, Math.min(9, e.currentLayer)); } catch (Throwable ignored) { return 0; }
    }

    private static float playerBaseSpawnPos(StageBasis sb) {
        try {
            return Math.max(sb.ebase.pos + 220f, sb.ubase.pos - 160f);
        } catch (Throwable ignored) {
            try { return sb.st.len - 160f; } catch (Throwable ignored2) { return 0f; }
        }
    }

    private static int randInt(StageBasis sb, int bound) {
        if (bound <= 1) return 0;
        try {
            int v = (int) (sb.r.nextFloat() * bound);
            return Math.max(0, Math.min(bound - 1, v));
        } catch (Throwable ignored) {}
        return (int) (Math.random() * bound);
    }

    private static long totalAttack(Entity e) {
        long sum = 0L;
        try {
            Object aam = BCUFields.get(e, "aam");
            int[] atks = (int[]) BCUFields.get(aam, "atks");
            for (int i = 0; i < atks.length; i++) if (atks[i] > 0) sum += atks[i];
        } catch (Throwable ignored) {
            try { sum = Math.max(1, e.getAtk()); } catch (Throwable ignored2) {}
        }
        return Math.max(1L, sum);
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

    private static long scaleLong(long base, int percent) {
        double v = (double) Math.max(1L, base) * (1.0d + Math.max(0, percent) * HP_PCT_PER);
        if (Double.isNaN(v) || v <= 1d) return 1L;
        if (v >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, Math.round(v));
    }

    private static int clampInt(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static int stableSeed(Object o, int salt) {
        int h = o == null ? 0 : System.identityHashCode(o);
        h ^= salt * 0x9E3779B9;
        h ^= h >>> 16;
        return h;
    }

    private static void reject() {
        try { CommonStatic.setSE(15); } catch (Throwable ignored) {}
    }
}
