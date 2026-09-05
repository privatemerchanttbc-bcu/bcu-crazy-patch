package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.data.MaskUnit;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.pack.UserProfile;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.anim.EAnimI;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ReincarnationFeature {

    private static final float PEAK_PX = 50f;
    private static final int RISE_FRAMES = 30;
    private static final int FALL_FRAMES = 30;
    private static final int TOTAL_FLIGHT = RISE_FRAMES + FALL_FRAMES;
    private static final int IMPLODE_FRAMES = 12;
    private static final int APEX_FLARE_FRAMES = 14;
    private static final int FLASH_FRAMES = 40;
    private static final int MATERIALIZE_FRAMES = 24;
    private static final float SWAY_PX = 7f;

    private static final int EMBER_INTERVAL = 3;
    private static final int EMBER_LIFE = 26;
    private static final float EMBER_FALL = 1.5f;

    private static final int MIN_CANDIDATE_PRICE = 76;
    private static final int[] TOLERANCES = {0, 50, 100, 200, 400, 800};
    private static final int PAIR_ATTEMPTS = 48;
    private static final int NEAREST_SCAN_CAP = 20000;

    private static final float SPAWN_SPREAD = 60f;
    private static final int SUMMON_LEVEL = 20;
    private static final int SUMMON_PLUS_LEVEL = 50;

    private static final int SE_CAPTURE = 19;
    private static final int SE_APEX = 27;
    private static final int SE_HATCH = 29;

    private static final Color SOUL_CORE = new Color(236, 255, 255);
    private static final Color SOUL_HOT = new Color(150, 226, 255);
    private static final Color SOUL_DEEP = new Color(116, 132, 255);
    private static final Color SOUL_VIOLET = new Color(168, 108, 244);

    private static final int GLOW_TEX = 128;
    private static final int RING_TEX = 96;
    private static final int SPARK_TEX = 48;

    private static volatile Field transformDataField;

    private ReincarnationFeature() {}

    public static final class State {
        public final Object lock = new Object();
        public final List<PriceForm> pool = new ArrayList<PriceForm>();
        public final List<SoulFlight> flights = new ArrayList<SoulFlight>();
        public final Set<Object> seen = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

        public final Map<Object, Integer> materialize = new WeakHashMap<Object, Integer>();
        public boolean initialized;
        public boolean poolWarningLogged;
        public FakeImage glowTex;
        public FakeImage ringTex;
        public FakeImage sparkTex;
        public boolean texturesBaked;
        public boolean textureWarningLogged;
    }

    private static final class PriceForm {
        final Form form;
        final int price;
        final String unitKey;

        PriceForm(Form form, int price, String unitKey) {
            this.form = form;
            this.price = price;
            this.unitKey = unitKey;
        }
    }

    private static final class Ember {
        float bx;
        float h;
        int age;
        final int seed;

        Ember(float bx, float h, int seed) {
            this.bx = bx;
            this.h = h;
            this.seed = seed;
        }
    }

    private static final class SoulFlight {
        final EAnimI soul;
        final float pos;
        final int layer;
        final int targetPrice;
        final Object deadData;
        final float power;
        final int seed;
        final List<Ember> embers = new ArrayList<Ember>();
        int age;
        int apexFlare;
        boolean reversed;
        boolean hatched;
        int flashAge = -1;

        SoulFlight(EAnimI soul, float pos, int layer, int targetPrice, Object deadData,
                   float power, int seed) {
            this.soul = soul;
            this.pos = pos;
            this.layer = layer;
            this.targetPrice = targetPrice;
            this.deadData = deadData;
            this.power = power;
            this.seed = seed;
        }
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        return rt != null && (!rt.reincarnation.flights.isEmpty() || !rt.reincarnation.materialize.isEmpty());
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null || (!rt.config.reincarnation && !hasActive(rt))) return;
        ensureInitialized(rt);
        State st = rt.reincarnation;
        StageBasis sb = (StageBasis) rt.stage;
        if (rt.config.reincarnation && battleRunning(sb)) scanDeaths(rt, sb, st);
        advanceFlights(rt, sb, st);
        ageMaterialize(st);
    }

    private static void scanDeaths(CrazyRuntime.StageRuntime rt, StageBasis sb, State st) {
        if (sb == null || sb.le == null) return;
        int threshold = threshold(rt);

        List<Entity> snapshot = new ArrayList<Entity>(sb.le);
        for (int i = 0; i < snapshot.size(); i++) {
            Entity e = snapshot.get(i);
            if (!(e instanceof EUnit)) continue;
            if (e.dire != -1 || e.isBase()) continue;
            if (st.seen.contains(e)) continue;

            boolean dead = e.health <= 0L || soulFramesLeft(e) > 0;
            if (!dead) continue;
            st.seen.add(e);
            int fieldPrice = reflectedPrice(e);
            int maskPrice = maskPrice(e);
            int price = fieldPrice > 0 ? fieldPrice : Math.max(0, maskPrice);
            Logger.log("Reincarnation death detected: fieldPrice=" + fieldPrice
                    + " maskPrice=" + maskPrice + " used=" + price + " threshold=" + threshold
                    + " unit=" + e.getClass().getSimpleName());
            if (price <= threshold) continue;
            EAnimI soul = capturedSoul(e);
            int seed = System.identityHashCode(e);
            st.flights.add(new SoulFlight(soul, e.pos, safeLayer(e), price, e.data,
                    powerFactor(price), seed));
            detachEntity(sb, e);
            try { CommonStatic.setSE(SE_CAPTURE); } catch (Throwable ignored) {}
            Logger.log("Reincarnation captured price=" + price + " pool=" + st.pool.size());
        }
    }

    private static void advanceFlights(CrazyRuntime.StageRuntime rt, StageBasis sb, State st) {
        for (Iterator<SoulFlight> it = st.flights.iterator(); it.hasNext();) {
            SoulFlight f = it.next();
            if (f == null) { it.remove(); continue; }
            if (f.soul != null) {
                try { f.soul.update(false); } catch (Throwable ignored) {}
            }
            if (f.apexFlare > 0) f.apexFlare--;
            if (!f.hatched) {
                f.age++;
                updateEmbers(sb, f);
                if (!f.reversed && f.age >= RISE_FRAMES) {
                    f.reversed = true;
                    f.apexFlare = APEX_FLARE_FRAMES;
                    try { CommonStatic.setSE(SE_APEX); } catch (Throwable ignored) {}
                }
                if (f.age >= TOTAL_FLIGHT) {
                    hatch(rt, sb, st, f);
                    f.hatched = true;
                    f.flashAge = 0;
                }
            } else {
                f.flashAge++;
                if (!f.embers.isEmpty()) updateEmbers(sb, f);
                if (f.flashAge >= FLASH_FRAMES && f.embers.isEmpty()) it.remove();
            }
        }
    }

    private static void updateEmbers(StageBasis sb, SoulFlight f) {

        if (!f.hatched && f.age <= RISE_FRAMES + 4 && f.age % EMBER_INTERVAL == 0) {
            int count = 1 + Math.round(f.power);
            float h = PEAK_PX * shape01(f.age);
            for (int k = 0; k < count; k++) {
                float bx = (randFloat(sb) - 0.5f) * 26f;
                f.embers.add(new Ember(bx, h + randFloat(sb) * 6f, f.seed + f.age * 31 + k));
            }
        }
        for (Iterator<Ember> it = f.embers.iterator(); it.hasNext();) {
            Ember em = it.next();
            em.age++;
            em.h -= EMBER_FALL;
            em.bx += (((em.seed >> 3) & 1) == 0 ? 0.18f : -0.18f);
            if (em.age >= EMBER_LIFE || em.h <= 0f) it.remove();
        }
    }

    private static void ageMaterialize(State st) {
        if (st.materialize.isEmpty()) return;
        for (Object key : new ArrayList<Object>(st.materialize.keySet())) {
            Integer age = st.materialize.get(key);
            if (age == null || age >= MATERIALIZE_FRAMES
                    || !(key instanceof Entity) || ((Entity) key).dead) {
                st.materialize.remove(key);
            } else {
                st.materialize.put(key, age + 1);
            }
        }
    }

    private static void hatch(CrazyRuntime.StageRuntime rt, StageBasis sb, State st, SoulFlight f) {
        try {
            PriceForm[] pair = pickPair(sb, st, f.targetPrice, f.deadData);
            if (pair == null) {
                Logger.log("Reincarnation found no candidate pair for price=" + f.targetPrice);
                return;
            }
            spawnChild(sb, st, pair[0], f.pos - SPAWN_SPREAD);
            spawnChild(sb, st, pair[1], f.pos + SPAWN_SPREAD);
            try { CommonStatic.setSE(SE_HATCH); } catch (Throwable ignored) {}
            Logger.log("Reincarnation hatched target=" + f.targetPrice
                    + " -> " + pair[0].price + "+" + pair[1].price);
        } catch (Throwable t) {
            Logger.err("Reincarnation hatch failed", t);
        }
    }

    private static void spawnChild(StageBasis sb, State st, PriceForm pf, float pos) {
        if (pf == null || pf.form == null) return;
        try {
            EForm ef = new EForm(pf.form, summonLevel());
            EUnit unit = ef.getEntity(sb, null, false, false);
            if (unit == null) return;
            unit.added(-1, pos);
            prepareSpawnedUnit(unit, pf.price);
            addEntitySorted(sb, unit);
            st.materialize.put(unit, 0);
        } catch (Throwable t) {
            Logger.err("Reincarnation child spawn failed", t);
        }
    }

    private static void prepareSpawnedUnit(EUnit unit, int price) {
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
        try { BCUFields.setInt(unit, "price", Math.max(0, price)); } catch (Throwable ignored) {}
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

    public static float[] materializeDraw(Object entity) {
        Integer age = materializeAge(entity);
        if (age == null || age >= MATERIALIZE_FRAMES) return null;
        float t = clamp01(age / (float) MATERIALIZE_FRAMES);
        float scaleY = Math.max(0.04f, 1f - (1f - t) * (1f - t));
        float alpha = clamp01(t * 1.5f);
        return new float[]{0f, scaleY, alpha};
    }

    private static Integer materializeAge(Object entity) {
        if (!(entity instanceof Entity)) return null;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return null;
        return rt.reincarnation.materialize.get(entity);
    }

    private static PriceForm[] pickPair(StageBasis sb, State st, int target, Object deadData) {
        List<PriceForm> pool = st.pool;
        if (pool.size() < 2) return null;
        for (int ti = 0; ti < TOLERANCES.length; ti++) {
            int tol = TOLERANCES[ti];
            for (int attempt = 0; attempt < PAIR_ATTEMPTS; attempt++) {
                PriceForm a = pool.get(randInt(sb, pool.size()));
                if (excluded(a, deadData)) continue;
                int want = target - a.price;
                if (want < MIN_CANDIDATE_PRICE) continue;
                PriceForm b = findNear(sb, pool, want, tol, a.unitKey, deadData);
                if (b != null) return new PriceForm[]{a, b};
            }
        }
        return nearestPair(pool, target, deadData);
    }

    private static PriceForm findNear(StageBasis sb, List<PriceForm> pool, int want, int tol,
                                      String otherKey, Object deadData) {
        ArrayList<PriceForm> hits = new ArrayList<PriceForm>();
        for (int i = 0; i < pool.size(); i++) {
            PriceForm p = pool.get(i);
            if (excluded(p, deadData)) continue;
            if (p.unitKey != null && p.unitKey.equals(otherKey)) continue;
            if (Math.abs(p.price - want) <= tol) hits.add(p);
        }
        if (hits.isEmpty()) return null;
        return hits.get(randInt(sb, hits.size()));
    }

    private static PriceForm[] nearestPair(List<PriceForm> pool, int target, Object deadData) {
        ArrayList<PriceForm> usable = new ArrayList<PriceForm>();
        for (int i = 0; i < pool.size(); i++) {
            if (!excluded(pool.get(i), deadData)) usable.add(pool.get(i));
        }
        if (usable.size() < 2) return null;
        int n = usable.size();
        int budget = NEAREST_SCAN_CAP;
        long best = Long.MAX_VALUE;
        PriceForm ba = null, bb = null;
        for (int i = 0; i < n && budget > 0; i++) {
            PriceForm a = usable.get(i);
            for (int j = i + 1; j < n && budget > 0; j++, budget--) {
                PriceForm b = usable.get(j);
                if (a.unitKey != null && a.unitKey.equals(b.unitKey)) continue;
                long diff = Math.abs((long) a.price + b.price - target);
                if (diff < best) { best = diff; ba = a; bb = b; }
            }
        }
        return ba == null ? null : new PriceForm[]{ba, bb};
    }

    private static boolean excluded(PriceForm p, Object deadData) {
        return p == null || p.form == null
                || (deadData != null && p.form.du == deadData);
    }

    private static void ensureInitialized(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        State st = rt.reincarnation;
        if (st.initialized) return;
        synchronized (st.lock) {
            if (st.initialized) return;
            buildPool(st);
            st.initialized = true;
        }
    }

    private static void buildPool(State st) {
        st.pool.clear();
        try {
            List<Unit> units = null;
            try { units = UserProfile.getBCData().units.getList(); } catch (Throwable ignored) {}
            if (units != null) {
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (u == null || u.forms == null || u.id == null) continue;
                    Form form = highestForm(u);
                    if (form == null || form.du == null) continue;
                    int price = 0;
                    try { price = form.du.getPrice(); } catch (Throwable ignored) {}
                    if (price < MIN_CANDIDATE_PRICE) continue;
                    st.pool.add(new PriceForm(form, price, u.id.pack + ":" + u.id.id));
                }
            }
            int maxPrice = 0, over1000 = 0, over2000 = 0, over4000 = 0;
            for (int i = 0; i < st.pool.size(); i++) {
                int p = st.pool.get(i).price;
                if (p > maxPrice) maxPrice = p;
                if (p > 1000) over1000++;
                if (p > 2000) over2000++;
                if (p > 4000) over4000++;
            }
            Logger.log("Reincarnation pool initialized: forms=" + st.pool.size()
                    + " maxPrice=" + maxPrice + " over1000=" + over1000
                    + " over2000=" + over2000 + " over4000=" + over4000);
        } catch (Throwable t) {
            Logger.err("Reincarnation pool build failed", t);
        }
        if (st.pool.size() < 2 && !st.poolWarningLogged) {
            st.poolWarningLogged = true;
            Logger.log("Reincarnation has fewer than 2 eligible player forms; nothing will hatch.");
        }
    }

    private static Form highestForm(Unit u) {
        for (int f = u.forms.length - 1; f >= 0; f--) {
            if (u.forms[f] != null && u.forms[f].du != null) return u.forms[f];
        }
        return null;
    }

    private static Level summonLevel() {
        Level level = new Level(SUMMON_LEVEL);
        try { level.setPlusLevel(SUMMON_PLUS_LEVEL); } catch (Throwable ignored) {}
        try { if (level.getOrbs() == null) level.setOrbs(new int[0][]); } catch (Throwable ignored) {}
        return level;
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || bbpainter == null || gra == null) return;
        if (!rt.config.reincarnation && !hasActive(rt)) return;
        State st = rt.reincarnation;
        if (st.flights.isEmpty()) return;
        ensureTextures(st);
        boolean gl = isGl(gra);
        float siz = 1f;
        try { siz = BBPainterAccess.getSiz(bbpainter); } catch (Throwable ignored) {}
        siz = Math.max(0.45f, siz);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            for (int i = 0; i < st.flights.size(); i++) {
                SoulFlight f = st.flights.get(i);
                if (f == null) continue;
                float x = CrazyRender.screenX(bbpainter, f.pos);
                float groundY = CrazyRender.groundY(bbpainter, f.layer);
                if (!f.hatched) drawFlight(gra, gl, bbpainter, st, f, x, groundY, siz);
                else drawHatch(gra, gl, bbpainter, st, f, x, groundY, siz);
            }
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void drawFlight(FakeGraphics gra, boolean gl, Object bbpainter, State st,
                                   SoulFlight f, float x, float groundY, float siz) {
        float pw = f.power;
        boolean descending = f.age > RISE_FRAMES;
        float shape = shape01(f.age);
        float offset = PEAK_PX * shape * siz;
        float fade = 1f;
        float swayX = (float) Math.sin(f.age * 0.28f + f.seed) * SWAY_PX * siz * (0.4f + 0.6f * shape);
        float soulX = x + swayX;
        float soulY = groundY - offset;

        if (f.age < IMPLODE_FRAMES) {
            float ip = f.age / (float) IMPLODE_FRAMES;
            float inv = 1f - ip;
            if (st.ringTex != null) {
                float r = (60f + 30f * pw) * inv * siz;
                glowAdd(gra, st.ringTex, x, groundY - 20f * siz, r * 2f, r, Math.round(180f * inv));
            }
            if (st.glowTex != null) {
                float pop = (float) Math.sin(clamp01(ip) * Math.PI);
                glowAdd(gra, st.glowTex, x, groundY - 24f * siz, 70f * siz * pop, 70f * siz * pop,
                        Math.round(200f * pop));
            }
        }

        if (st.sparkTex != null) {
            for (int k = 0; k < f.embers.size(); k++) {
                Ember em = f.embers.get(k);
                float a = 1f - em.age / (float) EMBER_LIFE;
                float ex = CrazyRender.screenX(bbpainter, f.pos + em.bx);
                float ey = groundY - em.h * siz;
                glowAdd(gra, st.sparkTex, ex, ey, 14f * siz * (0.6f + 0.6f * a), 14f * siz * (0.6f + 0.6f * a),
                        Math.round(200f * clamp01(a)));
            }
        }

        if (descending) {
            float fp = clamp01((f.age - RISE_FRAMES) / (float) FALL_FRAMES);
            drawLandingSeal(gra, st, x, groundY, fp, pw, siz, f.seed);

            if (st.glowTex != null) {
                glowAdd(gra, st.glowTex, soulX, soulY + 34f * siz, 26f * siz * pw, 92f * siz,
                        Math.round(120f * fp));
            }
        }

        if (st.glowTex != null) {
            float pulse = 0.78f + 0.22f * (float) Math.sin(f.age * 0.4f + f.seed);
            float auraR = (58f + 34f * pw) * siz;
            glowAdd(gra, st.glowTex, soulX, soulY - 16f * siz, auraR, auraR, Math.round(120f * pulse * fade));
            glowAdd(gra, st.glowTex, soulX, soulY - 16f * siz, auraR * 0.5f, auraR * 0.5f,
                    Math.round(150f * pulse * fade));
        }

        drawSoulSprite(gra, gl, st, f, soulX, soulY, siz);

        if (f.apexFlare > 0 && st.glowTex != null) {
            float ap = f.apexFlare / (float) APEX_FLARE_FRAMES;
            float apexY = groundY - PEAK_PX * siz;
            float burst = (float) Math.sin(clamp01(ap) * Math.PI);
            glowAdd(gra, st.glowTex, x, apexY - 14f * siz, (120f + 60f * pw) * siz * (0.6f + burst),
                    (120f + 60f * pw) * siz * (0.6f + burst), Math.round(210f * ap));
            if (st.ringTex != null) {
                float r = (1f - ap) * (90f + 50f * pw) * siz;
                glowAdd(gra, st.ringTex, x, apexY - 14f * siz, r * 2f, r * 2f, Math.round(170f * ap));
            }
        }
    }

    private static void drawSoulSprite(FakeGraphics gra, boolean gl, State st, SoulFlight f,
                                       float x, float y, float siz) {
        if (f.soul != null) {
            try {
                f.soul.draw(gra, new P(x, y), siz * 1.15f);
                return;
            } catch (Throwable ignored) {}
        }

        if (st.glowTex != null) {
            glowAdd(gra, st.glowTex, x, y - 20f * siz, 52f * siz, 68f * siz, 190);
        } else {
            fillEllipse(gra, gl, x, y - 20f * siz, 16f * siz, 20f * siz, SOUL_HOT, 190);
        }
    }

    private static void drawLandingSeal(FakeGraphics gra, State st, float x, float groundY,
                                        float fp, float pw, float siz, int seed) {
        if (st.ringTex == null) return;
        float intensity = fp * fp;
        float sealR = (56f + 30f * pw) * (0.6f + 0.4f * fp) * siz;
        glowAdd(gra, st.ringTex, x, groundY, sealR * 2.2f, sealR * 0.9f, Math.round(150f * intensity));
        glowAdd(gra, st.ringTex, x, groundY, sealR * 1.45f, sealR * 0.6f, Math.round(110f * intensity));
        if (st.sparkTex != null) {
            float rot = fp * 3.4f + seed * 0.01f;
            int nodes = 8;
            for (int k = 0; k < nodes; k++) {
                double a = rot + k * (Math.PI * 2.0 / nodes);
                float nx = x + (float) Math.cos(a) * sealR;
                float ny = groundY + (float) Math.sin(a) * sealR * 0.42f;
                glowAdd(gra, st.sparkTex, nx, ny, 18f * siz, 18f * siz, Math.round(170f * intensity));
            }
        }
    }

    private static void drawHatch(FakeGraphics gra, boolean gl, Object bbpainter, State st,
                                  SoulFlight f, float x, float groundY, float siz) {
        float p = clamp01(f.flashAge / (float) FLASH_FRAMES);
        float pw = f.power;
        float lx = CrazyRender.screenX(bbpainter, f.pos - SPAWN_SPREAD);
        float rx = CrazyRender.screenX(bbpainter, f.pos + SPAWN_SPREAD);
        drawChildBurst(gra, gl, st, lx, groundY, p, pw, siz, f.seed);
        drawChildBurst(gra, gl, st, rx, groundY, p, pw, siz, f.seed ^ 0x5bd1e995);

        if (st.ringTex != null) {
            for (int k = 0; k < 3; k++) {
                float rp = clamp01((p - k * 0.12f) / 0.6f);
                if (rp <= 0f) continue;
                float ringR = rp * (150f + 80f * pw) * siz;
                glowAdd(gra, st.ringTex, x, groundY, ringR * 2f, ringR * 0.8f, Math.round(150f * (1f - rp)));
            }
        }
    }

    private static void drawChildBurst(FakeGraphics gra, boolean gl, State st,
                                       float x, float groundY, float p, float pw, float siz, int seed) {
        float bloomY = groundY - 52f * siz;
        float charge = clamp01(p / 0.25f);
        float burst = smooth(clamp01((p - 0.15f) / 0.4f));

        float pillarA = burst * clamp01(1f - (p - 0.6f) / 0.4f);
        if (pillarA > 0f) {
            float pillarH = (190f + 120f * pw) * siz;
            float halfW = (22f + 9f * pw) * siz;
            int bands = Math.max(8, Math.round(pillarH / (9f * siz)));
            for (int i = 0; i < bands; i++) {
                float f0 = i / (float) bands;
                float by = groundY - pillarH * ((i + 1) / (float) bands);
                float bh = pillarH / bands + 1f;
                float ff = (1f - f0) * (1f - f0);
                float hw = halfW * (1f - 0.45f * f0);
                colFill(gra, gl, x - hw, by, hw * 2f, bh, SOUL_DEEP, Math.round(28f * pillarA * ff));
                colFill(gra, gl, x - hw * 0.6f, by, hw * 1.2f, bh, SOUL_HOT, Math.round(34f * pillarA * ff));
                colFill(gra, gl, x - hw * 0.26f, by, hw * 0.52f, bh, SOUL_CORE, Math.round(44f * pillarA * ff));
            }
            if (st.glowTex != null) {
                float gd = halfW * 2.2f;
                int n = Math.max(1, Math.round(pillarH / (gd * 0.42f)));
                for (int i = 0; i <= n; i++) {
                    float fr = i / (float) n;
                    float yy = groundY - pillarH * fr;
                    float ff = (1f - fr) * (1f - fr);
                    float gw = gd * (1f - 0.4f * fr);
                    glowAdd(gra, st.glowTex, x, yy, gw, gw, Math.round(70f * pillarA * ff));
                }
            }
        }

        if (st.glowTex != null) {
            float bloomA = (float) Math.exp(-((p - 0.4f) * (p - 0.4f)) / (2f * 0.14f * 0.14f));
            if (bloomA > 0.01f) {
                float bw = (120f + 70f * pw) * siz * (0.6f + 0.5f * p);
                glowAdd(gra, st.glowTex, x, bloomY, bw, bw, Math.round(225f * bloomA));
                glowAdd(gra, st.glowTex, x, bloomY, bw * 0.45f, bw * 0.45f, Math.round(200f * bloomA));
            }
        }

        if (st.ringTex != null) {
            float sealA = charge * (p < 0.85f ? 1f : clamp01((1f - p) / 0.15f));
            float sealR = (70f + 28f * pw) * siz * charge;
            glowAdd(gra, st.ringTex, x, groundY, sealR * 2.2f, sealR * 0.9f, Math.round(160f * sealA));
            if (st.sparkTex != null) {
                for (int k = 0; k < 8; k++) {
                    double a = p * 3.2f + seed * 0.01f + k * (Math.PI * 2.0 / 8);
                    float nx = x + (float) Math.cos(a) * sealR;
                    float ny = groundY + (float) Math.sin(a) * sealR * 0.42f;
                    glowAdd(gra, st.sparkTex, nx, ny, 18f * siz, 18f * siz, Math.round(160f * sealA));
                }
            }
        }

        float sp = clamp01((p - 0.4f) / 0.6f);
        if (sp > 0f && st.sparkTex != null) {
            for (int k = 0; k < 9; k++) {
                double a = seed * 0.013f + k * (Math.PI * 2.0 / 9);
                float rad = (40f + 60f * pw) * siz;
                float fall = sp * (55f + 35f * pw) * siz;
                float sx = x + (float) Math.cos(a) * rad * (1f - 0.3f * sp);
                float sy = bloomY + (float) Math.sin(a) * rad * 0.5f + fall - 28f * siz;
                glowAdd(gra, st.sparkTex, sx, sy, 15f * siz, 15f * siz, Math.round(180f * (1f - sp)));
            }
        }

        if (st.glowTex == null) {
            float bw = (60f + 30f * pw) * siz * (0.5f + p);
            fillEllipse(gra, gl, x, bloomY, bw, bw, SOUL_HOT, Math.round(180f * (1f - p)));
        }
    }

    private static int priceOf(Entity e) {
        int p = reflectedPrice(e);
        if (p > 0) return p;
        return Math.max(0, maskPrice(e));
    }

    private static int reflectedPrice(Entity e) {
        try { return BCUFields.getInt(e, "price"); } catch (Throwable ignored) { return -1; }
    }

    private static int maskPrice(Entity e) {
        try {
            if (e.data instanceof MaskUnit) return ((MaskUnit) e.data).getPrice();
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int soulFramesLeft(Entity e) {
        try {
            Object anim = BCUFields.get(e, "anim");
            return BCUFields.getInt(anim, "dead");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static EAnimI capturedSoul(Entity e) {
        try {
            Object anim = BCUFields.get(e, "anim");
            Object soul = BCUFields.get(anim, "soul");
            return soul instanceof EAnimI ? (EAnimI) soul : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void detachEntity(StageBasis sb, Entity e) {
        try { sb.le.remove(e); } catch (Throwable ignored) {}
        try {
            HoldState hs = HoldState.get();
            if (hs != null && hs.getHeldEntity() == e) hs.forceReset();
        } catch (Throwable ignored) {}
    }

    private static int threshold(CrazyRuntime.StageRuntime rt) {
        try { return rt.config.reincarnationThreshold; } catch (Throwable ignored) { return 1500; }
    }

    private static float powerFactor(int price) {
        return 1f + clamp01((price - 1500f) / 4000f) * 1.3f;
    }

    private static float shape01(int age) {
        if (age <= RISE_FRAMES) return clamp01(age / (float) RISE_FRAMES);
        return 1f - clamp01((age - RISE_FRAMES) / (float) FALL_FRAMES);
    }

    private static boolean battleRunning(StageBasis sb) {
        try {
            return sb != null && sb.ubase != null && sb.ebase != null
                    && sb.ubase.health > 0L && sb.ebase.health > 0L && sb.s_stop == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int safeLayer(Entity e) {
        try { return Math.max(0, Math.min(9, e.currentLayer)); } catch (Throwable ignored) { return 0; }
    }

    private static int randInt(StageBasis sb, int bound) {
        if (bound <= 1) return 0;
        try {
            int v = (int) (sb.r.nextFloat() * bound);
            return Math.max(0, Math.min(bound - 1, v));
        } catch (Throwable ignored) {}
        return (int) (Math.random() * bound);
    }

    private static float randFloat(StageBasis sb) {
        try { return sb.r.nextFloat(); } catch (Throwable ignored) {}
        return (float) Math.random();
    }

    private static boolean isGl(FakeGraphics gra) {
        return gra != null && gra.getClass().getName().contains("GLGraphics");
    }

    private static void ensureTextures(State st) {
        if (st == null || st.texturesBaked) return;
        try {
            if (ImageBuilder.builder == null) return;
            FakeImage glow = bakeRadialGlow(GLOW_TEX, SOUL_CORE, SOUL_HOT, SOUL_DEEP);
            FakeImage ring = bakeRing(RING_TEX, SOUL_HOT);
            FakeImage spark = bakeSpark(SPARK_TEX);
            if (glow != null && ring != null && spark != null) {
                st.glowTex = glow;
                st.ringTex = ring;
                st.sparkTex = spark;
                st.texturesBaked = true;
                Logger.log("Reincarnation VFX textures baked (soul-fire)");
            }
        } catch (Throwable t) {
            if (!st.textureWarningLogged) {
                st.textureWarningLogged = true;
                Logger.err("Reincarnation texture bake failed", t);
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

    private static FakeImage bakeRadialGlow(int N, Color c0, Color c1, Color c2) {
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

    private static FakeImage bakeRing(int N, Color base) {
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        float rad = N * 0.36f;
        float sig = N * 0.05f;
        float coreSig = N * 0.018f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float d = (float) Math.sqrt((x - c) * (x - c) + (y - c) * (y - c)) - rad;
                float env = (float) Math.exp(-(d * d) / (2f * sig * sig));
                float core = (float) Math.exp(-(d * d) / (2f * coreSig * coreSig));
                int r = clamp255(base.getRed() * env + 255f * core * 0.85f);
                int g = clamp255(base.getGreen() * env + 255f * core * 0.85f);
                int b = clamp255(base.getBlue() * env + 255f * core * 0.85f);
                int a = clamp255(255f * clamp01(env + core));
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static FakeImage bakeSpark(int N) {
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        float arm = N * 0.44f;
        float thin = N * 0.06f;
        float glowR = N * 0.17f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float fx = x - c, fy = y - c;
                float armH = (float) (Math.pow(Math.max(0f, 1f - Math.abs(fx) / arm), 2.6)
                        * Math.pow(Math.max(0f, 1f - Math.abs(fy) / thin), 2.0));
                float armV = (float) (Math.pow(Math.max(0f, 1f - Math.abs(fy) / arm), 2.6)
                        * Math.pow(Math.max(0f, 1f - Math.abs(fx) / thin), 2.0));
                float rr = (float) Math.sqrt(fx * fx + fy * fy);
                float glow = (float) Math.pow(Math.max(0f, 1f - rr / glowR), 2.0);
                float i = clamp01(Math.max(armH, Math.max(armV, glow)));

                int r = clamp255(SOUL_HOT.getRed() * i * 0.35f + 255f * i * 0.65f);
                int gg = clamp255(SOUL_HOT.getGreen() * i * 0.35f + 255f * i * 0.65f);
                int b = clamp255(255f * i);
                int a = clamp255(255f * i);
                img.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
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

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
