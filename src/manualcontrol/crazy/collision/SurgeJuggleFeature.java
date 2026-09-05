package manualcontrol.crazy.collision;

import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.attack.ContAb;
import common.battle.entity.Entity;
import common.system.P;
import common.system.fake.FakeGraphics;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

public final class SurgeJuggleFeature {

    public static volatile boolean ENABLED = true;

    public static volatile float HP_LIGHT = 1500f;
    public static volatile float HP_HEAVY = 25000f;
    public static volatile float SIZE_REF = 300f;
    public static volatile float SIZE_MIN = 0.35f;
    public static volatile float SIZE_MAX = 1.3f;
    public static volatile float MIN_STRENGTH = 0.08f;

    public static volatile float GRAVITY = 3.0f;
    public static volatile float FAST_FALL = 1.5f;
    public static volatile float BASE_VZ = 40f;
    public static volatile float VZ_MIN = 18f;
    public static volatile float VZ_MAX = 82f;
    public static volatile float DRIFT_VX = 2.5f;
    public static volatile float DRIFT_POP_DECAY = 0.85f;
    public static volatile float AIR_DRAG = 0.96f;
    public static volatile float POP_DECAY = 0.88f;
    public static volatile float POP_JITTER = 0.18f;
    public static volatile float SPIN_BASE = 0.09f;
    public static volatile float SPIN_MAX = 0.26f;
    public static volatile float SPIN_DECAY = 0.985f;
    public static volatile float SURGE_EDGE = 20f;
    public static volatile float KB_DISTANCE = 345f;
    public static volatile int MARK_LIFE = 6;
    public static volatile int MAX_PUFFS = 400;

    public static volatile boolean CREMATE = true;
    public static volatile int CREMATE_TICKS = 48;
    public static volatile int CREMATE_GRID = 22;
    public static volatile int MAX_CELLS = 2400;
    public static volatile float BURN_RIM = 0.14f;
    public static volatile int HIDE_AFTER_KILL = 24;
    public static volatile int EMBERS_PER_TICK = 5;

    private static final int SURGE_MASK = 0x14;
    private static final float RAT = 0.32f;

    private static final Map<Object, Mark> MARKS =
            Collections.synchronizedMap(new WeakHashMap<Object, Mark>());
    private static final Map<Object, Job> JOBS =
            Collections.synchronizedMap(new WeakHashMap<Object, Job>());
    private static final Map<Object, Burn> BURNS =
            Collections.synchronizedMap(new WeakHashMap<Object, Burn>());
    private static final List<Puff> PUFFS =
            Collections.synchronizedList(new ArrayList<Puff>());

    private static final Random RND = new Random(20260803L);
    private static volatile int seq = 0;
    private static volatile long lastTickNanos = 0L;
    private static volatile long tickIntervalNanos = 33_333_333L;
    private static volatile Method drawMethod;

    private SurgeJuggleFeature() {}

    private static final class Mark {
        int tick;
        int lastKb;
    }

    private static final class Job {
        final Entity entity;
        float vx;
        float vz;
        float height;
        float angle;
        float spin;
        float pop;
        float strength;
        float prevHeight;
        float prevAngle;
        float lastDx;
        int age;
        int pops;
        boolean finishing;
        boolean landed;
        boolean pendingKb;
        Job(Entity entity) { this.entity = entity; }
    }

    private static final class Cell {
        final float x;
        final float y;
        final float w;
        final float h;
        final float burn;
        Cell(float x, float y, float w, float h, float burn) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.burn = burn;
        }
    }

    private static final class Burn {
        final Entity entity;
        final Cell[] cells;
        final float pivotX;
        final float pivotY;
        int age;
        int deadAge;
        boolean killed;
        Burn(Entity entity, Cell[] cells, float pivotX, float pivotY) {
            this.entity = entity;
            this.cells = cells;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
        }
    }

    private static final class Puff {
        final int kind;
        float wx;
        float wy;
        float vx;
        float vy;
        final float size;
        final int layer;
        final int life;
        final int seed;
        int age;
        Puff(int kind, float wx, float wy, float vx, float vy, float size, int layer, int life, int seed) {
            this.kind = kind;
            this.wx = wx;
            this.wy = wy;
            this.vx = vx;
            this.vy = vy;
            this.size = size;
            this.layer = layer;
            this.life = life;
            this.seed = seed;
        }
    }

    public static boolean hasActive() {
        return !JOBS.isEmpty() || !BURNS.isEmpty() || !PUFFS.isEmpty();
    }

    public static boolean isJuggling(Object entity) {
        return entity != null && (JOBS.containsKey(entity) || BURNS.containsKey(entity));
    }

    public static boolean shouldHideNativeSprite(Object entity) {
        return entity != null && BURNS.containsKey(entity);
    }

    public static void onSurgeDamage(Object entityObj, Object attackObj) {
        if (!ENABLED || !PhysicalCollision.ENABLED) return;
        if (!(entityObj instanceof Entity) || !(attackObj instanceof AttackAb)) return;
        Entity e = (Entity) entityObj;
        AttackAb atk = (AttackAb) attackObj;
        if ((atk.waveType & SURGE_MASK) == 0) return;
        try {
            if (e.isBase()) return;
        } catch (Throwable ignored) {
            return;
        }
        if (DeathLaunchFeature.isLaunching(e)) return;
        Mark m = MARKS.get(e);
        if (m == null) {
            m = new Mark();
            try {
                m.lastKb = BCUFields.getInt(e, "kbTime");
            } catch (Throwable ignored) {}
            MARKS.put(e, m);
        }
        m.tick = seq;
    }

    public static boolean onLethal(Object entityObj) {
        if (!ENABLED || !PhysicalCollision.ENABLED) return false;
        if (!(entityObj instanceof Entity)) return false;
        Entity e = (Entity) entityObj;
        if (BURNS.containsKey(e)) {
            holdAlive(e);
            return true;
        }
        if (!JOBS.containsKey(e)) return false;
        if (!CREMATE) return false;
        if (isSoulPhase(e)) return false;
        Burn b = buildBurn(e);
        if (b == null) return false;
        if (!holdAlive(e)) return false;
        BURNS.put(e, b);
        Logger.log("SurgeJuggle: cremating " + e.getClass().getSimpleName()
                + " cells=" + b.cells.length);
        return true;
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            long d = now - lastTickNanos;
            if (d > 1_000_000L && d < 200_000_000L) tickIntervalNanos = d;
        }
        lastTickNanos = now;
        seq++;
        tickPuffs();
        StageBasis sb = null;
        if (rt != null && rt.stage instanceof StageBasis) sb = (StageBasis) rt.stage;
        if (sb != null) {
            scanForHitback(sb);
            tickJobs(sb);
        }
        tickBurns();
    }

    public static void afterNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        if (JOBS.isEmpty()) return;
        StageBasis sb = null;
        if (rt != null && rt.stage instanceof StageBasis) sb = (StageBasis) rt.stage;
        if (sb == null) return;
        List<Job> jobs;
        synchronized (JOBS) {
            jobs = new ArrayList<Job>(JOBS.values());
        }
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            if (j == null || j.entity == null) continue;
            Entity e = j.entity;
            if (e.basis != sb) continue;
            if (BURNS.containsKey(e)) continue;
            if (isSoulPhase(e)) continue;
            suppressKb(e);
            stopAttack(e);
            PhysicalCollision.clearTouchFields(e);
            DeathLaunchFeature.forceHitbackAnim(e);
        }
    }

    private static void stopAttack(Entity e) {
        try {
            Object atkm = BCUFields.get(e, "atkm");
            if (atkm != null) BCUFields.invoke(atkm, "stopAtk");
        } catch (Throwable ignored) {}
    }

    private static void scanForHitback(StageBasis sb) {
        if (!ENABLED || !PhysicalCollision.ENABLED) return;
        List<Entity> le;
        try {
            le = new ArrayList<Entity>(sb.le);
        } catch (Throwable ignored) {
            return;
        }
        for (int i = 0; i < le.size(); i++) {
            Entity e = le.get(i);
            if (e == null) continue;
            Mark m = MARKS.get(e);
            if (m == null) continue;
            if (seq - m.tick > MARK_LIFE) {
                MARKS.remove(e);
                continue;
            }
            if (JOBS.containsKey(e) || BURNS.containsKey(e)) continue;
            if (DeathLaunchFeature.isLaunching(e)) continue;
            int kb = 0;
            try {
                kb = BCUFields.getInt(e, "kbTime");
            } catch (Throwable ignored) {}
            boolean fresh = kb > 0 && m.lastKb <= 0;
            m.lastKb = kb;
            if (!fresh) continue;
            if (kbType(e) != 1) continue;
            start(e);
        }
    }

    private static void start(Entity e) {
        if (isSoulPhase(e)) return;
        try {
            if (e.isBase()) return;
        } catch (Throwable ignored) {
            return;
        }
        if (surgeAt(e) == null) return;
        float strength = strengthOf(e);
        if (strength < MIN_STRENGTH) return;
        Job j = new Job(e);
        j.strength = strength;
        j.pop = clamp(BASE_VZ * strength, VZ_MIN, VZ_MAX);
        j.vz = j.pop;
        j.vx = -sign(e.dire) * DRIFT_VX * strength;
        j.spin = -sign(e.dire) * clamp(SPIN_BASE * strength, 0.02f, SPIN_MAX);
        j.height = 0.5f;
        j.prevHeight = j.height;
        j.pendingKb = true;
        JOBS.put(e, j);
        suppressKb(e);
        DeathLaunchFeature.forceHitbackAnim(e);
        Logger.log("SurgeJuggle: " + e.getClass().getSimpleName()
                + " tossed strength=" + String.format("%.2f", strength)
                + " vz=" + Math.round(j.vz));
    }

    private static void tickJobs(StageBasis sb) {
        if (JOBS.isEmpty()) return;
        List<Job> jobs;
        synchronized (JOBS) {
            jobs = new ArrayList<Job>(JOBS.values());
        }
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            if (j == null || j.entity == null) continue;
            Entity e = j.entity;
            if (e.basis != sb) continue;
            boolean burning = BURNS.containsKey(e);
            if (!burning && isSoulPhase(e)) {
                JOBS.remove(e);
                continue;
            }
            j.prevHeight = j.height;
            j.prevAngle = j.angle;
            j.lastDx = 0f;
            j.age++;

            DeathLaunchFeature.forceHitbackAnim(e);
            PhysicalCollision.clearTouchFields(e);
            int kb = 0;
            try {
                kb = BCUFields.getInt(e, "kbTime");
            } catch (Throwable ignored) {}
            if (kb > 0) j.pendingKb = true;
            suppressKb(e);

            if (!j.finishing && surgeAt(e) == null) j.finishing = true;

            if (j.landed) {
                if (burning) continue;
                j.angle *= 0.45f;
                if (Math.abs(j.angle) < 0.03f) {
                    j.angle = 0f;
                    release(e, j, false);
                }
                continue;
            }

            j.vz -= GRAVITY * (j.vz < 0f ? FAST_FALL : 1f);
            j.height += j.vz;
            j.spin *= SPIN_DECAY;
            j.angle += j.spin;
            move(e, j);
            j.vx *= AIR_DRAG;

            if (j.height <= 0f) {
                float impact = -j.vz;
                j.height = 0f;
                boolean inSurge = !j.finishing && surgeAt(e) != null;
                if (inSurge) {
                    j.pops++;
                    float jitter = 1f + (RND.nextFloat() - 0.5f) * 2f * POP_JITTER;
                    j.pop = clamp(j.pop * POP_DECAY * jitter, VZ_MIN, VZ_MAX);
                    j.vz = j.pop;
                    j.vx = -sign(e.dire) * DRIFT_VX * j.strength
                            * (float) Math.pow(DRIFT_POP_DECAY, j.pops)
                            * (0.6f + RND.nextFloat() * 0.8f);
                    j.spin = -sign(e.dire)
                            * clamp(SPIN_BASE * j.strength * (0.7f + RND.nextFloat() * 0.9f),
                                    0.02f, SPIN_MAX);
                    spawnAsh(e, Math.max(impact, 12f), true);
                } else {
                    j.landed = true;
                    j.vz = 0f;
                    j.vx = 0f;
                    spawnAsh(e, Math.max(impact, 8f), false);
                }
            }
        }
    }

    private static void release(Entity e, Job j, boolean burning) {
        JOBS.remove(e);
        MARKS.remove(e);
        suppressKb(e);
        if (!burning && j.pendingKb) {
            try {
                e.interrupt(1, KB_DISTANCE);
            } catch (Throwable ignored) {}
        }
        Logger.log("SurgeJuggle: " + e.getClass().getSimpleName()
                + " landed after " + j.age + " ticks (" + j.pops + " pop(s))");
    }

    private static void move(Entity e, Job j) {
        try {
            float pos = BCUFields.getFloat(e, "pos");
            float want = pos + j.vx;
            float clamped = clampToField(e, want);
            if (clamped != want) j.vx = 0f;
            j.lastDx = clamped - pos;
            BCUFields.field(e.getClass(), "pos").setFloat(e, clamped);
            BCUFields.field(e.getClass(), "lastPosition").setFloat(e, clamped);
        } catch (Throwable ignored) {}
        try {
            BCUFields.field(e.getClass(), "walking").setBoolean(e, false);
        } catch (Throwable ignored) {}
    }

    private static void suppressKb(Entity e) {
        try {
            java.lang.reflect.Field f = BCUFields.field(e.getClass(), "kbTime");
            if (f.getInt(e) > 0) f.setInt(e, 0);
        } catch (Throwable ignored) {}
        try {
            BCUFields.field(e.getClass(), "walking").setBoolean(e, false);
        } catch (Throwable ignored) {}
    }

    private static int kbType(Entity e) {
        try {
            Object kb = BCUFields.get(e, "kb");
            if (kb == null) return 1;
            return BCUFields.getInt(kb, "kbType");
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static ContAb surgeAt(Entity e) {
        try {
            StageBasis sb = e.basis;
            if (sb == null) return null;
            ContAb hit = scanSurge(sb.lw, e.pos);
            if (hit == null) hit = scanSurge(sb.tlw, e.pos);
            return hit;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ContAb scanSurge(List<ContAb> list, float pos) {
        if (list == null) return null;
        List<ContAb> copy;
        try {
            copy = new ArrayList<ContAb>(list);
        } catch (Throwable ignored) {
            return null;
        }
        for (int i = 0; i < copy.size(); i++) {
            ContAb c = copy.get(i);
            if (c == null || !c.activate) continue;
            AttackAb v = attackOf(c);
            if (v == null) continue;
            if ((v.waveType & SURGE_MASK) == 0) continue;
            float s;
            float t;
            try {
                s = staField().getFloat(v);
                t = endField().getFloat(v);
            } catch (Throwable ignored) {
                continue;
            }
            float lo = Math.min(s, t) - SURGE_EDGE;
            float hi = Math.max(s, t) + SURGE_EDGE;
            if (pos >= lo && pos <= hi) return c;
        }
        return null;
    }

    private static final Map<Class<?>, java.lang.reflect.Field> CONT_ATTACK =
            Collections.synchronizedMap(new java.util.HashMap<Class<?>, java.lang.reflect.Field>());
    private static volatile java.lang.reflect.Field staF;
    private static volatile java.lang.reflect.Field endF;

    private static AttackAb attackOf(ContAb c) {
        Class<?> cls = c.getClass();
        java.lang.reflect.Field f;
        if (CONT_ATTACK.containsKey(cls)) {
            f = CONT_ATTACK.get(cls);
        } else {
            f = null;
            Class<?> walk = cls;
            while (walk != null && f == null) {
                java.lang.reflect.Field[] fs = walk.getDeclaredFields();
                for (int i = 0; i < fs.length; i++) {
                    if (AttackAb.class.isAssignableFrom(fs[i].getType())) {
                        fs[i].setAccessible(true);
                        f = fs[i];
                        break;
                    }
                }
                walk = walk.getSuperclass();
            }
            CONT_ATTACK.put(cls, f);
        }
        if (f == null) return null;
        try {
            Object v = f.get(c);
            return v instanceof AttackAb ? (AttackAb) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Field staField() throws NoSuchFieldException {
        java.lang.reflect.Field f = staF;
        if (f == null) {
            f = BCUFields.field(AttackAb.class, "sta");
            staF = f;
        }
        return f;
    }

    private static java.lang.reflect.Field endField() throws NoSuchFieldException {
        java.lang.reflect.Field f = endF;
        if (f == null) {
            f = BCUFields.field(AttackAb.class, "end");
            endF = f;
        }
        return f;
    }

    private static float strengthOf(Entity e) {
        float hp = baseHp(e);
        if (hp <= 0f) return 0f;
        float span = Math.max(1f, HP_HEAVY - HP_LIGHT);
        float hpScale = clamp((HP_HEAVY - hp) / span, 0f, 1f);
        if (hpScale <= 0f) return 0f;
        float resist = surgeResist(e);
        if (resist <= 0f) return 0f;
        return clamp(hpScale * sizeScale(e) * resist, 0f, 1.5f);
    }

    private static float baseHp(Entity e) {
        try {
            Object data = BCUFields.get(e, "data");
            if (data != null) {
                Object hp = BCUFields.invoke(data, "getHp");
                if (hp instanceof Number) {
                    float v = ((Number) hp).floatValue();
                    if (v > 0f) return v;
                }
            }
        } catch (Throwable ignored) {}
        return e.maxH > 0L ? (float) e.maxH : 0f;
    }

    private static float sizeScale(Entity e) {
        try {
            SpriteBounds.WorldBox b = SpriteBounds.of(e);
            if (b == null) return 1f;
            float w = Math.abs(b.x1 - b.x0);
            float h = Math.abs(b.y1 - b.y0);
            float d = (float) Math.sqrt(Math.max(1f, w * h));
            return clamp(SIZE_REF / Math.max(1f, d), SIZE_MIN, SIZE_MAX);
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private static float surgeResist(Entity e) {
        try {
            Object proc = BCUFields.invoke(e, "getProc");
            if (proc == null) return 1f;
            Object imu = BCUFields.get(proc, "IMUVOLC");
            if (imu == null) return 1f;
            int mult = BCUFields.getInt(imu, "mult");
            if (mult <= 0) return 1f;
            if (mult >= 100) return 0f;
            return (100f - mult) / 100f;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    public static float[] drawFx(Object entity) {
        Job j = entity == null ? null : JOBS.get(entity);
        if (j == null) return null;
        if (BURNS.containsKey(entity)) return null;
        if (isSoulPhase(j.entity)) return null;
        float a = subTick();
        float height = j.prevHeight + (j.height - j.prevHeight) * a;
        float angle = j.prevAngle + (j.angle - j.prevAngle) * a;
        float offX = j.lastDx * (a - 1f);
        return new float[]{height, angle, 0f, 0f, 0f, offX};
    }

    public static void drawCremation(Object entity, FakeGraphics g, P p, float siz) {
        if (g == null || p == null || entity == null) return;
        Burn b = BURNS.get(entity);
        if (b == null || b.cells == null || b.cells.length == 0) return;
        try {
            float t = clamp((b.age + subTick()) / Math.max(1f, CREMATE_TICKS), 0f, 1.2f);
            boolean mirror = manualcontrol.hooks.DrawHook.shouldMirrorForCurrentFacing(entity);
            float s = mirror ? -1f : 1f;
            float lift = 0f;
            float angle = 0f;
            float offX = 0f;
            Job j = JOBS.get(entity);
            if (j != null) {
                float a = subTick();
                lift = (j.prevHeight + (j.height - j.prevHeight) * a) * RAT * siz;
                angle = j.prevAngle + (j.angle - j.prevAngle) * a;
                offX = j.lastDx * (a - 1f) * RAT * siz;
            }
            float ca = (float) Math.cos(angle);
            float sa = (float) Math.sin(angle);
            float pvx = p.x + s * b.pivotX * siz + offX;
            float pvy = p.y + b.pivotY * siz - lift;
            for (int i = 0; i < b.cells.length; i++) {
                Cell c = b.cells[i];
                if (t >= c.burn) continue;
                float cx = p.x + s * c.x * siz + offX;
                float cy = p.y + c.y * siz - lift;
                float w = Math.max(1.5f, c.w * siz * 1.2f);
                float h = Math.max(1.5f, c.h * siz * 1.2f);
                float dx = cx - pvx;
                float dy = cy - pvy;
                float rx = pvx + dx * ca - dy * sa;
                float ry = pvy + dx * sa + dy * ca;
                if (!finite(rx) || !finite(ry) || Math.abs(rx) > 16000f || Math.abs(ry) > 16000f) continue;
                float left = c.burn - t;
                if (left < BURN_RIM) {
                    float f = clamp(left / Math.max(0.001f, BURN_RIM), 0f, 1f);
                    int cr = (int) (255f - 55f * f);
                    int cg = (int) (240f - 185f * f);
                    int cb = (int) (170f - 155f * f);
                    g.colRect(rx, ry, w, h, cr, cg, cb, 255);
                } else {
                    g.colRect(rx, ry, w, h, 14, 11, 14, 255);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Burn buildBurn(Entity e) {
        try {
            Object am = BCUFields.get(e, "anim");
            if (am == null) return null;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return null;
            MeasuringGraphics mg = new MeasuringGraphics(SpriteBounds.ALPHA_PROVIDER, true);
            Method m = drawMethod;
            if (m == null || m.getDeclaringClass() != anim.getClass()) {
                m = BCUFields.method(anim.getClass(), "draw",
                        FakeGraphics.class, P.class, float.class);
                drawMethod = m;
            }
            m.invoke(anim, mg, new P(0f, 0f), 1f);
            if (!mg.hasBox()) return null;
            List<MeasuringGraphics.PartQuad> quads = mg.quads();
            if (quads == null || quads.isEmpty()) return null;

            int grid = CREMATE_GRID;
            int guess = quads.size() * grid * grid;
            while (guess > MAX_CELLS && grid > 6) {
                grid--;
                guess = quads.size() * grid * grid;
            }

            List<Cell> out = new ArrayList<Cell>();
            for (int qi = 0; qi < quads.size(); qi++) {
                MeasuringGraphics.PartQuad q = quads.get(qi);
                if (q == null || q.pts == null || q.pts.length < 8) continue;
                if (AlphaBounds.isShadow(q.image)) continue;
                AlphaBounds.Mask mask = AlphaBounds.mask(q.image);
                int gw = grid;
                int gh = grid;
                if (mask != null) {
                    gw = Math.max(4, Math.min(grid, mask.gw));
                    gh = Math.max(4, Math.min(grid, mask.gh));
                }
                boolean[] on = new boolean[gw * gh];
                for (int y = 0; y < gh; y++) {
                    for (int x = 0; x < gw; x++) {
                        float u = (x + 0.5f) / gw;
                        float v = (y + 0.5f) / gh;
                        on[y * gw + x] = mask == null || mask.opaque(u, v);
                    }
                }
                int[] dist = inwardDistance(on, gw, gh);
                int maxDist = 1;
                for (int k = 0; k < dist.length; k++) {
                    if (dist[k] > maxDist) maxDist = dist[k];
                }
                float[] pts = q.pts;
                float ux = (pts[2] - pts[0]) / gw;
                float uy = (pts[3] - pts[1]) / gw;
                float vx = (pts[6] - pts[0]) / gh;
                float vy = (pts[7] - pts[1]) / gh;
                float cw = Math.abs(ux) + Math.abs(vx);
                float ch = Math.abs(uy) + Math.abs(vy);
                for (int y = 0; y < gh; y++) {
                    for (int x = 0; x < gw; x++) {
                        int idx = y * gw + x;
                        if (!on[idx]) continue;
                        float d = dist[idx] / (float) maxDist;
                        float burn = 0.08f + 0.92f * d
                                + (RND.nextFloat() - 0.5f) * 0.22f;
                        burn = clamp(burn, 0.03f, 1f);
                        float ox = pts[0] + ux * x + vx * y;
                        float oy = pts[1] + uy * x + vy * y;
                        out.add(new Cell(ox - cw * 0.5f, oy - ch * 0.5f, cw, ch, burn));
                    }
                }
            }
            if (out.isEmpty()) return null;
            float pivotX = (mg.minX() + mg.maxX()) * 0.5f;
            float pivotY = (mg.minY() + mg.maxY()) * 0.5f;
            float[] c = CollisionDebug.spriteCenterOffsetPx(e, 1f);
            if (c != null) {
                pivotX = c[0];
                pivotY = c[1];
            }
            return new Burn(e, out.toArray(new Cell[out.size()]), pivotX, pivotY);
        } catch (Throwable t) {
            Logger.err("SurgeJuggle buildBurn failed", t);
            return null;
        }
    }

    private static int[] inwardDistance(boolean[] on, int gw, int gh) {
        int big = gw + gh + 2;
        int[] d = new int[gw * gh];
        for (int i = 0; i < d.length; i++) d[i] = on[i] ? big : 0;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int i = y * gw + x;
                if (d[i] == 0) continue;
                int best = d[i];
                int left = x > 0 ? d[i - 1] + 1 : 1;
                int up = y > 0 ? d[i - gw] + 1 : 1;
                if (left < best) best = left;
                if (up < best) best = up;
                d[i] = best;
            }
        }
        for (int y = gh - 1; y >= 0; y--) {
            for (int x = gw - 1; x >= 0; x--) {
                int i = y * gw + x;
                if (d[i] == 0) continue;
                int best = d[i];
                int right = x < gw - 1 ? d[i + 1] + 1 : 1;
                int down = y < gh - 1 ? d[i + gw] + 1 : 1;
                if (right < best) best = right;
                if (down < best) best = down;
                d[i] = best;
            }
        }
        return d;
    }

    private static void tickBurns() {
        if (BURNS.isEmpty()) return;
        List<Burn> burns;
        synchronized (BURNS) {
            burns = new ArrayList<Burn>(BURNS.values());
        }
        for (int i = 0; i < burns.size(); i++) {
            Burn b = burns.get(i);
            if (b == null || b.entity == null) continue;
            Entity e = b.entity;
            if (b.killed) {
                b.deadAge++;
                if (b.deadAge > HIDE_AFTER_KILL) BURNS.remove(e);
                continue;
            }
            b.age++;
            holdAlive(e);
            PhysicalCollision.clearTouchFields(e);
            spawnBurnEffects(b);
            if (b.age >= CREMATE_TICKS) {
                b.killed = true;
                JOBS.remove(e);
                try {
                    BCUFields.field(e.getClass(), "health").setLong(e, 0L);
                } catch (Throwable ignored) {}
                try {
                    e.kill(Entity.KillMode.NORMAL);
                } catch (Throwable t) {
                    Logger.err("SurgeJuggle cremation kill failed", t);
                }
                Logger.log("SurgeJuggle: cremation done for " + e.getClass().getSimpleName());
            }
        }
    }

    private static void spawnBurnEffects(Burn b) {
        if (b.cells == null || b.cells.length == 0) return;
        float t0 = (b.age - 1) / (float) Math.max(1, CREMATE_TICKS);
        float t1 = b.age / (float) Math.max(1, CREMATE_TICKS);
        Entity e = b.entity;
        float pos;
        int layer = 0;
        try {
            pos = BCUFields.getFloat(e, "pos");
        } catch (Throwable ignored) {
            return;
        }
        try {
            layer = manualcontrol.reflect.EntityAccess.getLayer(e);
        } catch (Throwable ignored) {}
        boolean mirror = false;
        try {
            mirror = manualcontrol.hooks.DrawHook.shouldMirrorForCurrentFacing(e);
        } catch (Throwable ignored) {}
        float s = mirror ? -1f : 1f;
        float lift = 0f;
        Job j = JOBS.get(e);
        if (j != null) lift = j.height;
        int spawned = 0;
        for (int i = 0; i < b.cells.length && spawned < EMBERS_PER_TICK; i++) {
            Cell c = b.cells[i];
            if (c.burn < t0 || c.burn >= t1) continue;
            if (RND.nextFloat() > 0.35f) continue;
            float wx = pos + s * c.x / RAT;
            float wy = c.y / RAT - lift;
            PUFFS.add(new Puff(1, wx, wy,
                    (RND.nextFloat() - 0.5f) * 6f,
                    -(6f + RND.nextFloat() * 10f),
                    2.5f + RND.nextFloat() * 2.5f, layer, 26 + RND.nextInt(14),
                    RND.nextInt(1000)));
            spawned++;
        }
        if ((b.age & 3) == 0) {
            PUFFS.add(new Puff(2, pos + (RND.nextFloat() - 0.5f) * 40f,
                    -lift - 20f - RND.nextFloat() * 40f,
                    (RND.nextFloat() - 0.5f) * 3f, -(3f + RND.nextFloat() * 4f),
                    10f + RND.nextFloat() * 10f, layer, 46, RND.nextInt(1000)));
        }
    }

    private static void spawnAsh(Entity e, float impact, boolean hot) {
        try {
            float pos = BCUFields.getFloat(e, "pos");
            int layer = 0;
            try {
                layer = manualcontrol.reflect.EntityAccess.getLayer(e);
            } catch (Throwable ignored) {}
            float strength = clamp(impact / 40f, 0.3f, 1.5f);
            PUFFS.add(new Puff(0, pos, 0f, 0f, 0f, strength, layer, 18, RND.nextInt(1000)));
            if (!hot) return;
            int n = 3 + RND.nextInt(3);
            for (int i = 0; i < n; i++) {
                PUFFS.add(new Puff(1, pos + (RND.nextFloat() - 0.5f) * 60f, -4f,
                        (RND.nextFloat() - 0.5f) * 8f, -(8f + RND.nextFloat() * 12f),
                        2f + RND.nextFloat() * 3f, layer, 24 + RND.nextInt(12),
                        RND.nextInt(1000)));
            }
        } catch (Throwable ignored) {}
    }

    private static void tickPuffs() {
        synchronized (PUFFS) {
            for (int i = PUFFS.size() - 1; i >= 0; i--) {
                Puff p = PUFFS.get(i);
                p.age++;
                if (p.kind != 0) {
                    p.wx += p.vx;
                    p.wy += p.vy;
                    p.vy *= 0.94f;
                    p.vx *= 0.96f;
                }
                if (p.age > p.life) PUFFS.remove(i);
            }
            while (PUFFS.size() > MAX_PUFFS) PUFFS.remove(0);
        }
    }

    public static void drawEffects(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || PUFFS.isEmpty()) return;
        List<Puff> puffs;
        synchronized (PUFFS) {
            puffs = new ArrayList<Puff>(PUFFS);
        }
        common.system.fake.FakeTransform old = CollisionHud.pushIdentityTransform(g);
        try {
            float siz = manualcontrol.reflect.BBPainterAccess.getSiz(bbpainter);
            int stagePos = manualcontrol.reflect.BBPainterAccess.getStagePos(bbpainter);
            int midh = manualcontrol.reflect.BBPainterAccess.getMidh(bbpainter);
            if (!finite(siz) || siz <= 0.01f) return;
            float sub = subTick();
            for (int i = 0; i < puffs.size(); i++) {
                Puff p = puffs.get(i);
                float t = clamp((p.age - 1f + sub) / Math.max(1, p.life), 0f, 1f);
                float sx = (p.wx * RAT + 200f) * siz + stagePos;
                float groundY = midh - (156f - p.layer * 4f) * siz;
                float sy = groundY + p.wy * RAT * siz;
                if (!finite(sx) || !finite(sy)
                        || Math.abs(sx) > 16000f || Math.abs(sy) > 16000f) continue;
                if (p.kind == 0) {
                    int alpha = Math.max(0, Math.round((1f - t) * 130f));
                    if (alpha <= 2) continue;
                    for (int b = 0; b < 4; b++) {
                        float side = (b % 2 == 0) ? 1f : -1f;
                        float jitter = ((p.seed * 31 + b * 17) % 7) - 3f;
                        float r = (5f + 14f * t) * p.size * (0.7f + 0.25f * b) * siz;
                        if (!finite(r) || r <= 0.5f || r > 400f) continue;
                        float ox = side * ((6f + 26f * t) * p.size * (0.5f + 0.2f * b) + jitter) * siz;
                        float oy = -(2f + 8f * t) * (0.3f * b) * siz;
                        g.colRect(sx + ox - r, sy + oy - r * 0.6f, r * 2f, r * 1.2f,
                                138, 118, 96, alpha);
                    }
                } else if (p.kind == 1) {
                    int alpha = Math.max(0, Math.round((1f - t) * 240f));
                    if (alpha <= 3) continue;
                    float r = Math.max(1f, p.size * siz * (1f - 0.4f * t));
                    int cr = 255;
                    int cg = Math.round(225f - 150f * t);
                    int cb = Math.round(150f - 130f * t);
                    g.colRect(sx - r * 0.5f, sy - r * 0.5f, r, r, cr, cg, cb, alpha);
                    float halo = r * 2.2f;
                    g.colRect(sx - halo * 0.5f, sy - halo * 0.5f, halo, halo,
                            255, 130, 40, Math.max(0, alpha / 4));
                } else {
                    int alpha = Math.max(0, Math.round((1f - t) * 90f));
                    if (alpha <= 2) continue;
                    float r = (p.size + 22f * t) * siz;
                    if (!finite(r) || r <= 0.5f || r > 400f) continue;
                    g.colRect(sx - r, sy - r * 0.7f, r * 2f, r * 1.4f,
                            58, 52, 52, alpha);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            CollisionHud.popTransform(g, old);
        }
    }

    private static boolean holdAlive(Entity e) {
        try {
            BCUFields.field(e.getClass(), "health").setLong(e, 1L);
            BCUFields.field(e.getClass(), "damage").setLong(e, 0L);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static float clampToField(Entity e, float pos) {
        try {
            StageBasis sb = e.basis;
            float a = BCUFields.getFloat(sb.ubase, "pos");
            float b = BCUFields.getFloat(sb.ebase, "pos");
            float lo = Math.min(a, b) + 10f;
            float hi = Math.max(a, b) - 10f;
            if (pos < lo) return lo;
            if (pos > hi) return hi;
        } catch (Throwable ignored) {}
        return pos;
    }

    private static boolean isSoulPhase(Entity e) {
        if (e == null) return true;
        if (e.dead) return true;
        try {
            Object am = BCUFields.get(e, "anim");
            if (am != null && BCUFields.getInt(am, "dead") >= 0) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    static float subTick() {
        long lt = lastTickNanos;
        if (lt == 0L) return 1f;
        float a = (System.nanoTime() - lt) / (float) tickIntervalNanos;
        return a < 0f ? 0f : (a > 1f ? 1f : a);
    }

    private static float sign(int v) {
        return v >= 0 ? 1f : -1f;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
