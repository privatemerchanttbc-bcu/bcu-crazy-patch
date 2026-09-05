package manualcontrol.crazy.collision;

import common.battle.StageBasis;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BCUFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class DeathLaunchFeature {

    public static volatile boolean ENABLED = true;

    public static volatile float GRAVITY = 3.0f;

    public static volatile float FAST_FALL = 1.7f;

    public static volatile float LAUNCH_BURST = 2.4f;
    public static volatile float BASE_VX = 32f;
    public static volatile float OVERKILL_VX = 44f;

    public static volatile float OVERKILL_CAP = 4f;

    public static volatile float EXCESS_FULL = 0.5f;

    public static volatile float MIN_LAUNCH_SPEED = 50f;
    public static volatile float BASE_VZ = 26f;
    public static volatile float UP_VZ = 50f;
    public static volatile float SWING_GAIN = 1.7f;
    public static volatile float AIR_DRAG = 0.95f;
    public static volatile int MAX_FLIGHT_TICKS = 240;

    private static volatile long lastTickNanos = 0L;
    private static volatile long tickIntervalNanos = 33_333_333L;

    private static float subTickAlpha() {
        long lt = lastTickNanos;
        if (lt == 0L) return 1f;
        float a = (System.nanoTime() - lt) / (float) tickIntervalNanos;
        return a < 0f ? 0f : (a > 1f ? 1f : a);
    }

    public static volatile float SWING_MAX = 60f;
    public static volatile float VX_MAX = 260f;
    public static volatile float VZ_MIN = 20f;
    public static volatile float VZ_MAX = 100f;

    public static volatile float SPIN_PER_SPEED = 0.010f;
    public static volatile float SPIN_MIN = 0.10f;
    public static volatile float SPIN_MAX = 0.55f;
    public static volatile float RESTITUTION = 0.45f;
    public static volatile float BOUNCE_FRICTION = 0.75f;
    public static volatile int MAX_BOUNCES = 3;
    public static volatile float BOUNCE_MIN_SPEED = 6f;
    public static volatile float SLIDE_FRICTION = 0.88f;
    public static volatile float SETTLE_SPEED = 1.5f;

    public static volatile float WALL_RESTITUTION = 0.35f;

    public static volatile boolean DEFORM = false;
    public static volatile float STRETCH_REF = 200f;
    public static volatile float STRETCH_MAX = 0.35f;
    public static volatile int SQUASH_TICKS = 4;

    public static volatile boolean CORPSE_STRIKE = true;
    public static volatile float STRIKE_MIN_SPEED = 15f;

    public static volatile float DOMINO_ENERGY = 1.0f;

    public static volatile float MOMENTUM_KEEP_MIN = 0.15f;

    public static volatile float PEN_MIN_FRAC = 0.10f;
    public static volatile float PEN_MAX_FRAC = 0.50f;

    public static volatile float PEN_SPEED_MAX = 190f;

    public static volatile int STRIKE_ARM_TICKS = 4;
    public static volatile float STRIKE_ARM_DIST = 60f;

    public static volatile boolean BODY_BLOCK = true;
    public static volatile float BODY_BLOCK_RESTITUTION = 0.3f;

    public static volatile float CHAIN_TRANSFER = 0.85f;
    public static volatile float CHAIN_MIN_VZ = 6f;

    public static volatile float ENERGY_DECAY_DIST = 1500f;

    private static final Map<Object, Job> JOBS =
            Collections.synchronizedMap(new WeakHashMap<Object, Job>());

    private static final List<Dust> DUST =
            Collections.synchronizedList(new ArrayList<Dust>());
    private static final int DUST_LIFE = 18;

    private DeathLaunchFeature() {}

    private static final class Job {
        final Entity entity;
        float vx;
        float vz;
        float height;
        float angle;
        float spin;
        int age;
        int bounces;
        boolean sliding;
        int squashTicks;
        float lastImpact;
        float energy;

        float prevHeight;
        float prevAngle;
        float lastDx;
        float traveled;
        final Set<Object> struck = new HashSet<Object>();
        Job(Entity entity) { this.entity = entity; }
    }

    private static final class Dust {
        final float worldX;
        final int layer;
        final float strength;
        final int seed;
        int age;
        Dust(float worldX, int layer, float strength, int seed) {
            this.worldX = worldX;
            this.layer = layer;
            this.strength = strength;
            this.seed = seed;
        }
    }

    private static int dustSeed = 0;

    public static boolean hasActive() {
        return !JOBS.isEmpty() || !DUST.isEmpty();
    }

    public static boolean isLaunching(Object entity) {
        return entity != null && JOBS.containsKey(entity);
    }

    public static float[] drawFx(Object entity) {
        Job j = entity == null ? null : JOBS.get(entity);
        if (j == null) return null;
        if (isSoulPhase(j.entity)) return null;
        float stretch = 0f;
        float velAngle = 0f;
        float squash = 0f;
        if (DEFORM) {
            float speed = (float) Math.sqrt(j.vx * j.vx + j.vz * j.vz);
            stretch = j.sliding ? 0f
                    : Math.min(STRETCH_MAX, speed / Math.max(1f, STRETCH_REF));

            velAngle = (float) Math.atan2(-j.vz, j.vx);
            if (j.squashTicks > 0) {
                float k = j.squashTicks / (float) Math.max(1, SQUASH_TICKS);
                float imp = Math.max(0.2f, Math.min(1f, j.lastImpact / 40f));
                squash = 0.6f * k * imp;
            }
        }

        float a = subTickAlpha();
        float height = j.prevHeight + (j.height - j.prevHeight) * a;
        float angle = j.prevAngle + (j.angle - j.prevAngle) * a;
        float offX = j.lastDx * (a - 1f);
        return new float[]{height, angle, stretch, velAngle, squash, offX};
    }

    public static void trigger(Object entityObj, PhysicalCollision.Impact im, float excessRatio) {
        if (!ENABLED || im == null || !(entityObj instanceof Entity)) return;
        Entity e = (Entity) entityObj;
        if (JOBS.containsKey(e)) return;
        if (SurgeJuggleFeature.isJuggling(e)) return;
        if (isSoulPhase(e)) return;

        float[] v = computeVelocity(im, excessRatio);
        float speed = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1]);
        if (speed < MIN_LAUNCH_SPEED) {
            long now = System.currentTimeMillis();
            if (now - lastWeakLog > 500L) {
                Logger.log("DeathLaunch skipped (weak kill): speed=" + Math.round(speed)
                        + " < " + Math.round(MIN_LAUNCH_SPEED) + " - vanilla death");
                lastWeakLog = now;
            }
            return;
        }

        if (!holdAlive(e)) {
            Logger.log("DeathLaunch revive failed - vanilla death proceeds");
            return;
        }

        float vz = Math.max(v[1], VZ_MIN);

        float energy = 0f;
        try {
            Object mh = BCUFields.get(e, "maxH");
            if (mh instanceof Number && ((Number) mh).floatValue() > 0f) {
                energy = Math.max(0f, excessRatio * ((Number) mh).floatValue() * DOMINO_ENERGY);
            }
        } catch (Throwable ignored) {}
        startJob(e, v[0], vz, energy);
        Logger.log("DeathLaunch: " + e.getClass().getSimpleName()
                + " launched vx=" + Math.round(v[0]) + " vz=" + Math.round(vz)
                + " upFactor=" + String.format("%.2f", im.upFactor)
                + " excess=" + String.format("%.2f", excessRatio)
                + " mass=" + String.format("%.2f", im.massMult));
    }

    private static long lastWeakLog = 0L;

    private static Job startJob(Entity e, float vx, float vz, float energyHP) {
        Job job = new Job(e);
        job.vx = vx;
        job.vz = vz;

        job.spin = -Math.signum(vx)
                * clamp(Math.abs(vx) * SPIN_PER_SPEED, SPIN_MIN, SPIN_MAX);
        job.height = 0.5f;
        job.prevHeight = job.height;
        job.prevAngle = job.angle;
        job.energy = Math.max(0f, energyHP);
        forceHitbackAnim(e);
        JOBS.put(e, job);
        return job;
    }

    public static float[] computeVelocity(PhysicalCollision.Impact im, float excessRatio) {
        float ok = Math.min(OVERKILL_CAP, Math.max(0f, excessRatio));

        float s = clamp(excessRatio / Math.max(0.01f, EXCESS_FULL), 0f, 1f);
        float vx = im.dirX * (BASE_VX * s + OVERKILL_VX * ok);
        float vz = (BASE_VZ + UP_VZ * im.upFactor) * s;

        vx += clamp(im.swingX * SWING_GAIN, -SWING_MAX, SWING_MAX) * s;
        vz += clamp(-im.swingY * SWING_GAIN, -SWING_MAX, SWING_MAX) * s;

        vx *= im.massMult;
        vz *= im.massMult;

        vx *= LAUNCH_BURST;

        vx = clamp(vx, -VX_MAX, VX_MAX);
        vz = Math.min(vz, VZ_MAX);
        return new float[]{vx, vz};
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {

        long now = System.nanoTime();
        if (lastTickNanos != 0L) {
            long d = now - lastTickNanos;
            if (d > 1_000_000L && d < 200_000_000L) tickIntervalNanos = d;
        }
        lastTickNanos = now;

        tickDust();
        if (JOBS.isEmpty()) return;
        List<Job> jobs;
        synchronized (JOBS) {
            jobs = new ArrayList<Job>(JOBS.values());
        }
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (job == null || job.entity == null) continue;
            Entity e = job.entity;
            if (e.basis != rt.stage) continue;
            if (isSoulPhase(e)) {
                JOBS.remove(e);
                continue;
            }
            holdAlive(e);
            forceHitbackAnim(e);
            PhysicalCollision.clearTouchFields(e);

            job.prevHeight = job.height;
            job.prevAngle = job.angle;
            job.lastDx = 0f;
            job.age++;
            if (job.squashTicks > 0) job.squashTicks--;
            if (job.age > MAX_FLIGHT_TICKS) {
                settle(e, job);
                continue;
            }

            if (job.sliding) {
                job.vx *= SLIDE_FRICTION;
                slideMove(e, job);
                job.spin *= 0.80f;
                job.angle += job.spin;
                if ((job.age & 3) == 0 && Math.abs(job.vx) > 4f) {
                    spawnDust(e, Math.abs(job.vx) * 0.5f);
                }
                if (Math.abs(job.vx) < SETTLE_SPEED) settle(e, job);
                continue;
            }

            job.vz -= GRAVITY * (job.vz < 0f ? FAST_FALL : 1f);
            job.height += job.vz;
            job.spin *= 0.985f;
            job.angle += job.spin;
            flyMove(e, job);
            job.vx *= AIR_DRAG;
            if (CORPSE_STRIKE) strikeAllies(e, job);

            if (job.height <= 0f) {
                float impact = -job.vz;
                job.height = 0f;
                job.lastImpact = impact;
                job.squashTicks = SQUASH_TICKS;
                if (job.bounces < MAX_BOUNCES && impact > BOUNCE_MIN_SPEED) {
                    job.bounces++;
                    job.vz = impact * RESTITUTION;
                    job.vx *= BOUNCE_FRICTION;
                    job.spin *= 0.65f;
                    spawnDust(e, impact);
                } else {
                    job.sliding = true;
                    job.vz = 0f;
                    spawnDust(e, Math.max(impact, Math.abs(job.vx) * 0.5f));
                }
            }
        }
    }

    private static void strikeAllies(Entity corpse, Job job) {
        float speed = (float) Math.sqrt(job.vx * job.vx + job.vz * job.vz);
        if (speed < STRIKE_MIN_SPEED) return;

        if (job.age < STRIKE_ARM_TICKS || job.traveled < STRIKE_ARM_DIST) return;

        boolean canStrike = job.energy > 0f;
        if (!canStrike && !BODY_BLOCK) return;
        try {
            SpriteBounds.Silhouette cs = SpriteBounds.silhouetteOf(corpse);
            if (cs == null) return;

            List<MeasuringGraphics.PartQuad> body = null;
            float bx0, bx1, by0, by1;
            if (!cs.quads.isEmpty()) {
                float sw = 0f, pvx = 0f, pvy = 0f;
                for (MeasuringGraphics.PartQuad q : cs.quads) {
                    float[] p = q.pts;
                    float area = Math.abs((p[2] - p[0]) * (p[7] - p[1])
                            - (p[6] - p[0]) * (p[3] - p[1]));
                    float w = area * AlphaBounds.fillRatio(q.image);
                    if (w <= 0f) continue;
                    pvx += w * (p[0] + p[2] + p[4] + p[6]) * 0.25f;
                    pvy += w * (p[1] + p[3] + p[5] + p[7]) * 0.25f;
                    sw += w;
                }
                if (sw > 1e-3f) {
                    pvx /= sw;
                    pvy /= sw;
                } else {
                    pvx = (cs.box.x0 + cs.box.x1) * 0.5f;
                    pvy = (cs.box.y0 + cs.box.y1) * 0.5f;
                }
                float ca = (float) Math.cos(job.angle);
                float sa = (float) Math.sin(job.angle);
                body = new ArrayList<MeasuringGraphics.PartQuad>(cs.quads.size());
                bx0 = Float.MAX_VALUE;
                by0 = Float.MAX_VALUE;
                bx1 = -Float.MAX_VALUE;
                by1 = -Float.MAX_VALUE;
                for (MeasuringGraphics.PartQuad q : cs.quads) {
                    float[] p = q.pts.clone();
                    for (int k = 0; k < 8; k += 2) {
                        float dx = p[k] - pvx;
                        float dy = p[k + 1] - pvy;
                        float nx = pvx + dx * ca - dy * sa;
                        float ny = pvy + dx * sa + dy * ca - job.height;
                        p[k] = nx;
                        p[k + 1] = ny;
                        if (nx < bx0) bx0 = nx;
                        if (nx > bx1) bx1 = nx;
                        if (ny < by0) by0 = ny;
                        if (ny > by1) by1 = ny;
                    }
                    body.add(new MeasuringGraphics.PartQuad(p, q.image));
                }
            } else {
                bx0 = cs.box.x0;
                bx1 = cs.box.x1;
                by0 = cs.box.y0 - job.height;
                by1 = cs.box.y1 - job.height;
            }

            StageBasis sb = (StageBasis) BCUFields.get(corpse, "basis");
            if (sb == null) return;
            float corpsePos = BCUFields.getFloat(corpse, "pos");

            float speedNorm = clamp((speed - STRIKE_MIN_SPEED)
                    / Math.max(1f, PEN_SPEED_MAX - STRIKE_MIN_SPEED), 0f, 1f);
            float penFrac = PEN_MIN_FRAC + (PEN_MAX_FRAC - PEN_MIN_FRAC) * speedNorm;

            Entity best = null;
            float bestDist = Float.MAX_VALUE;
            boolean bestIsBlock = false;
            for (int i = 0; i < sb.le.size(); i++) {
                Entity t = sb.le.get(i);
                if (t == null || t == corpse || t.dead) continue;
                boolean sameSide = t.dire == corpse.dire;
                if (sameSide && !canStrike) continue;
                if (!sameSide && !BODY_BLOCK) continue;
                if (isLaunching(t) || SurgeJuggleFeature.isJuggling(t)) continue;
                if (job.struck.contains(t)) continue;
                SpriteBounds.Silhouette ts = SpriteBounds.silhouetteOf(t);
                if (ts == null) continue;

                if (!(bx0 <= ts.box.x1 && ts.box.x0 <= bx1)) continue;
                if (!(by0 <= ts.box.y1 && ts.box.y0 <= by1)) continue;

                boolean axisX = Math.abs(job.vx) >= Math.abs(job.vz);
                float dim = axisX ? ts.box.x1 - ts.box.x0 : ts.box.y1 - ts.box.y0;
                float pen;
                if (body != null && !ts.quads.isEmpty()) {
                    pen = PhysicalCollision.alphaOverlapExtent(body, ts.quads, axisX);
                    if (pen <= 0f) continue;
                } else {

                    pen = axisX
                            ? Math.min(bx1, ts.box.x1) - Math.max(bx0, ts.box.x0)
                            : Math.min(by1, ts.box.y1) - Math.max(by0, ts.box.y0);
                    if (pen <= 0f) continue;
                }
                if (pen < penFrac * Math.max(1f, dim)) continue;
                float dist = Math.abs(t.pos - corpsePos);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = t;
                    bestIsBlock = !sameSide;
                }
            }
            if (best == null) return;
            Entity t = best;

            if (bestIsBlock) {

                job.struck.add(t);
                job.vx = -job.vx * BODY_BLOCK_RESTITUTION;
                job.vz *= 0.6f;
                job.spin = -job.spin * 0.8f;
                spawnDust(corpse, speed * 0.5f);
                Logger.log("DeathLaunch blocked: " + corpse.getClass().getSimpleName()
                        + " corpse bounced off living " + t.getClass().getSimpleName()
                        + " (no damage)");
                return;
            }

            job.struck.add(t);
            float pool = job.energy;
            long oldDmg;
            long remHP;
            try {
                oldDmg = BCUFields.getLong(t, "damage");
                remHP = Math.max(0L, t.health - oldDmg);
                long dmg = (long) Math.max(1f, pool);
                BCUFields.field(t.getClass(), "damage").setLong(t, oldDmg + dmg);
            } catch (Throwable ignored) {
                return;
            }

            movePos(t, Math.signum(job.vx) * 12f);

            float inVx = job.vx;
            float inVz = job.vz;

            job.energy = 0f;
            float absorbed = Math.min(pool, remHP);
            float remainder = pool - remHP;

            float frac = pool > 0f ? absorbed / pool : 1f;
            float keep = Math.max(MOMENTUM_KEEP_MIN, 1f - frac);
            job.vx *= keep;
            job.vz *= keep;
            job.spin *= keep;
            Logger.log("DeathLaunch domino: " + corpse.getClass().getSimpleName()
                    + " corpse struck " + t.getClass().getSimpleName()
                    + " pool=" + Math.round(pool) + " absorbed=" + Math.round(absorbed)
                    + " remainder=" + Math.max(0, Math.round(remainder))
                    + " momentumKeep=" + String.format("%.2f", keep)
                    + " embedFrac=" + String.format("%.2f", penFrac));

            if (remainder > 0f) {

                try {
                    float vxN = inVx * CHAIN_TRANSFER;
                    float vzN = inVz * CHAIN_TRANSFER;
                    if (vzN < CHAIN_MIN_VZ) vzN = CHAIN_MIN_VZ;
                    float chainSpeed = (float) Math.sqrt(vxN * vxN + vzN * vzN);

                    if (chainSpeed >= MIN_LAUNCH_SPEED
                            && !isSoulPhase(t) && !JOBS.containsKey(t) && holdAlive(t)) {
                        startJob(t, vxN, vzN, remainder);
                        Logger.log("DeathLaunch chain: " + t.getClass().getSimpleName()
                                + " inherits vx=" + Math.round(vxN) + " vz=" + Math.round(vzN)
                                + " energy=" + Math.round(remainder));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {

        }
    }

    private static void spawnDust(Entity e, float impactSpeed) {
        try {
            float pos = BCUFields.getFloat(e, "pos");
            int layer = 0;
            try { layer = manualcontrol.reflect.EntityAccess.getLayer(e); } catch (Throwable ignored) {}
            float strength = clamp(impactSpeed / 40f, 0.3f, 1.5f);
            DUST.add(new Dust(pos, layer, strength, dustSeed++));
        } catch (Throwable ignored) {}
    }

    private static void tickDust() {
        synchronized (DUST) {
            for (int i = DUST.size() - 1; i >= 0; i--) {
                Dust d = DUST.get(i);
                d.age++;
                if (d.age > DUST_LIFE) DUST.remove(i);
            }
        }
    }

    public static void drawEffects(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null || DUST.isEmpty()) return;
        List<Dust> dust;
        synchronized (DUST) {
            dust = new ArrayList<Dust>(DUST);
        }
        common.system.fake.FakeTransform old = CollisionHud.pushIdentityTransform(g);
        try {
            float siz = manualcontrol.reflect.BBPainterAccess.getSiz(bbpainter);
            int stagePos = manualcontrol.reflect.BBPainterAccess.getStagePos(bbpainter);
            int midh = manualcontrol.reflect.BBPainterAccess.getMidh(bbpainter);
            if (!isFinite(siz) || siz <= 0.01f) return;
            float subTick = subTickAlpha();
            for (Dust d : dust) {
                float sx = (d.worldX * 0.32f + 200f) * siz + stagePos;
                float gy = midh - (156f - d.layer * 4f) * siz;
                if (!isFinite(sx) || !isFinite(gy)
                        || Math.abs(sx) > 16000f || Math.abs(gy) > 16000f) continue;
                float t = Math.max(0f, Math.min(1f, (d.age - 1f + subTick) / DUST_LIFE));
                int alpha = Math.max(0, Math.round((1f - t) * 120f));
                if (alpha <= 2) continue;
                for (int b = 0; b < 4; b++) {
                    float side = (b % 2 == 0) ? 1f : -1f;
                    float jitter = ((d.seed * 31 + b * 17) % 7) - 3f;
                    float r = (5f + 14f * t) * d.strength * (0.7f + 0.25f * b) * siz;
                    if (!isFinite(r) || r <= 0.5f || r > 400f) continue;
                    float ox = side * ((6f + 26f * t) * d.strength * (0.5f + 0.2f * b) + jitter) * siz;
                    float oy = -(2f + 8f * t) * (0.3f * b) * siz;
                    g.colRect(sx + ox - r, gy + oy - r * 0.6f, r * 2f, r * 1.2f,
                            125, 110, 92, alpha);
                }
            }
        } catch (Throwable ignored) {

        } finally {
            CollisionHud.popTransform(g, old);
        }
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static void settle(Entity e, Job job) {
        JOBS.remove(e);
        Logger.log("DeathLaunch: " + e.getClass().getSimpleName()
                + " settled after " + job.age + " ticks (" + job.bounces + " bounce(s)) - killing");
        try {
            e.kill(Entity.KillMode.NORMAL);
        } catch (Throwable t) {
            Logger.err("DeathLaunch settle kill failed", t);
            try { BCUFields.field(e.getClass(), "health").setLong(e, 0L); } catch (Throwable ignored) {}
        }
    }

    private static void movePos(Entity e, float dx) {
        try {
            float pos = BCUFields.getFloat(e, "pos") + dx;
            pos = clampToField(e, pos);
            BCUFields.field(e.getClass(), "pos").setFloat(e, pos);
        } catch (Throwable ignored) {}
        clearMotionState(e);
    }

    private static void flyMove(Entity e, Job job) {
        try {
            float pos = BCUFields.getFloat(e, "pos");
            float want = pos + job.vx;
            float clamped = clampToField(e, want);
            if (clamped != want) {
                job.vx = -job.vx * WALL_RESTITUTION;
                job.spin = -job.spin * 0.8f;
                spawnDust(e, Math.abs(job.vx) + 8f);
            }
            job.lastDx = clamped - pos;
            job.traveled += Math.abs(job.lastDx);

            if (job.energy > 0f) {
                job.energy *= Math.max(0f, 1f - Math.abs(job.lastDx) / Math.max(1f, ENERGY_DECAY_DIST));
            }
            BCUFields.field(e.getClass(), "pos").setFloat(e, clamped);
        } catch (Throwable ignored) {}
        clearMotionState(e);
    }

    private static void slideMove(Entity e, Job job) {
        try {
            float pos = BCUFields.getFloat(e, "pos");
            float want = pos + job.vx;
            float clamped = clampToField(e, want);
            if (clamped != want) job.vx = 0f;
            job.lastDx = clamped - pos;
            BCUFields.field(e.getClass(), "pos").setFloat(e, clamped);
        } catch (Throwable ignored) {}
        clearMotionState(e);
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
            StageBasis sb = (StageBasis) BCUFields.get(e, "basis");
            float a = BCUFields.getFloat(sb.ubase, "pos");
            float b = BCUFields.getFloat(sb.ebase, "pos");
            float lo = Math.min(a, b) + 10f;
            float hi = Math.max(a, b) - 10f;
            if (pos < lo) return lo;
            if (pos > hi) return hi;
        } catch (Throwable ignored) {}
        return pos;
    }

    private static void clearMotionState(Entity e) {
        try { BCUFields.field(e.getClass(), "kbTime").setInt(e, 0); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "walking").setBoolean(e, false); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "lastPosition").setFloat(e, e.pos); } catch (Throwable ignored) {}
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

    private static volatile Class<?> uTypeClass;
    private static volatile Object hbType;

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void forceHitbackAnim(Entity e) {
        try {
            Object am = BCUFields.get(e, "anim");
            if (am == null) return;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return;
            Object type = BCUFields.get(anim, "type");
            if (type instanceof Enum && "HB".equals(((Enum<?>) type).name())) return;
            if (hbType == null) {
                Class<?> ut = Class.forName("common.util.anim.AnimU$UType");
                hbType = Enum.valueOf((Class<Enum>) ut, "HB");
                uTypeClass = ut;
            }
            java.lang.reflect.Method m;
            try {
                m = BCUFields.method(anim.getClass(), "changeAnim", uTypeClass, boolean.class);
            } catch (Throwable t) {
                m = BCUFields.method(anim.getClass(), "changeAnim", Enum.class, boolean.class);
            }
            m.invoke(anim, hbType, true);
        } catch (Throwable ignored) {

        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
