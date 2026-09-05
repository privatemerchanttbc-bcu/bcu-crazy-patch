package manualcontrol.crazy.beam;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.attack.AtkModelEntity;
import common.battle.attack.AttackAb;
import common.battle.attack.AttackSimple;
import common.battle.data.MaskAtk;
import common.battle.entity.AbEntity;
import common.battle.entity.Cannon;
import common.battle.entity.ECastle;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.util.Data;
import common.util.pack.NyCastle;
import manualcontrol.ConvertedRegistry;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyConfig;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ArmyCanonFeature {

    private static final float SUCTION_SPEED = 220f;
    private static final float PROJECTILE_SPEED = 260f;
    private static final int SHOT_INTERVAL = 15;
    private static final int MAX_PROJECTILE_FRAMES = 90;
    private static final float IMPACT_EPS = 45f;
    private static final float THROW_MIN = 70f;
    private static final float THROW_MAX = 150f;
    private static final short[] CANNON_Y = new short[]{-134, -134, -134, -250, -250, -134, -134, -134};
    private static final byte[] CANNON_X = new byte[]{0, 0, 0, 64, 64, 0, 0, 0};
    private static final float[] MUZZLE_DX = new float[]{28f, 28f, 28f, 28f, 28f, 28f, 28f, 28f};
    private static final float[] MUZZLE_DY = new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    private static final float CANNON_SPRITE_SCALE = 0.8f;
    private static final float PROJECTILE_START_SCALE = 0.22f;
    private static final float SPRING_STIFF = 0.25f;
    private static final float SPRING_DAMP = 0.78f;
    private static final float CHARGE_EFFORT = 0.05f;
    private static final float CHARGE_MAX = 0.18f;
    private static final float KICK_FIRE = 0.16f;
    private static final float KICK_GULP = -0.05f;
    private static final float SHAKE_PER_COMPRESSION = 3.0f;
    private static final int RECOIL_FRAMES = 12;
    private static final boolean DEBUG_OVERLAY = Boolean.getBoolean("mc.armycanon.debug");
    private static final Map<Object, Boolean> MANAGED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private ArmyCanonFeature() {}

    public static final class State {
        public boolean active;
        public boolean locked;
        public int frame;
        public int nextShotFrame;
        public Cannon cannon;
        public UnitState sucking;
        public final List<UnitState> suctionQueue = new ArrayList<UnitState>();
        public final List<UnitState> loadedQueue = new ArrayList<UnitState>();
        public final List<Projectile> projectiles = new ArrayList<Projectile>();
        public final List<FallingDeath> falling = new ArrayList<FallingDeath>();
        public float muzzlePulse;
        public int recoilFrame;

        public float springPos = 1f;
        public float springVel;

        public int animFrame;
        public int totalQueued;

        public float pivotX;
        public float pivotY;
        public boolean pivotSet;
    }

    private static final class UnitState {
        final Entity entity;
        final float originalPos;
        final int originalLayer;
        final int originalStatus44;
        final int attackCount;
        boolean loaded;

        UnitState(Entity entity) {
            this.entity = entity;
            this.originalPos = entity.pos;
            this.originalLayer = readLayer(entity);
            this.originalStatus44 = readStatus44(entity);
            this.attackCount = Math.max(1, safeAtkCount(entity));
        }
    }

    private static final class Projectile {
        final UnitState unit;
        AbEntity target;
        final float startPos;
        final float totalDistance;
        float pos;
        int layer;
        int age;
        float scale;

        Projectile(UnitState unit, AbEntity target, float startPos) {
            this.unit = unit;
            this.target = target;
            this.startPos = startPos;
            this.totalDistance = Math.max(1f, Math.abs(target.pos - startPos));
            this.pos = startPos;
            this.layer = unit.originalLayer;
            this.scale = PROJECTILE_START_SCALE;
        }
    }

    private static final class FallingDeath {
        final Entity entity;
        float vx;
        float vy;
        int groundLayer;
        int age;

        FallingDeath(Entity entity, float vx, float vy, int groundLayer) {
            this.entity = entity;
            this.vx = vx;
            this.vy = vy;
            this.groundLayer = groundLayer;
        }
    }

    public static void activate(CrazyRuntime.StageRuntime rt, Cannon cannon) {
        StageBasis sb = (StageBasis) rt.stage;
        clear(rt, false);
        clearNativeCannonState(cannon);
        primeNativeBaseAnim(cannon);
        rt.armyCanon.active = true;
        rt.armyCanon.locked = true;
        rt.armyCanon.cannon = cannon;
        rt.armyCanon.frame = 0;
        rt.armyCanon.nextShotFrame = 0;
        rt.armyCanon.recoilFrame = 0;
        List<Entity> units = collectUnits(sb);
        Collections.sort(units, new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                float da = Math.abs(a.pos - sb.ubase.pos);
                float db = Math.abs(b.pos - sb.ubase.pos);
                return Float.compare(da, db);
            }
        });
        for (int i = 0; i < units.size(); i++) {
            UnitState us = new UnitState(units.get(i));
            MANAGED.put(us.entity, Boolean.TRUE);
            makeUntouchable(us.entity);
            rt.armyCanon.suctionQueue.add(us);
        }
        rt.armyCanon.totalQueued = rt.armyCanon.suctionQueue.size();
        if (rt.armyCanon.suctionQueue.isEmpty()) {

            rt.armyCanon.recoilFrame = RECOIL_FRAMES;
            rt.armyCanon.muzzlePulse = 1f;
            rt.armyCanon.springVel += KICK_FIRE;
            rt.armyCanon.active = false;
            rt.armyCanon.locked = true;
            Logger.log("Army Canon: no units to load, idle misfire (base HP untouched)");
            return;
        }
        Logger.log("Army Canon activated units=" + rt.armyCanon.suctionQueue.size());
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        tickSpring(rt.armyCanon);
        tickFalling(rt);
    }

    private static void tickSpring(State st) {
        st.animFrame++;
        float target = springTarget(st);
        st.springVel += (target - st.springPos) * SPRING_STIFF;
        st.springVel *= SPRING_DAMP;
        st.springPos += st.springVel;
        if (st.springPos < 0.62f) {
            st.springPos = 0.62f;
            if (st.springVel < 0f) st.springVel = 0f;
        }
        if (st.springPos > 1.35f) {
            st.springPos = 1.35f;
            if (st.springVel > 0f) st.springVel = 0f;
        }
    }

    private static float springTarget(State st) {
        if (!st.active) return 1f;
        boolean charging = st.sucking != null || !st.suctionQueue.isEmpty() || !st.loadedQueue.isEmpty();
        if (!charging) return 1f;
        float loaded = st.loadedQueue.size() + (st.sucking != null ? 0.5f : 0f);
        float frac = st.totalQueued <= 0 ? 0f : Math.min(1f, loaded / st.totalQueued);
        return 1f - CHARGE_EFFORT - (CHARGE_MAX - CHARGE_EFFORT) * frac;
    }

    public static void updateActive(CrazyRuntime.StageRuntime rt, Cannon cannon) {
        State st = rt.armyCanon;
        if (!st.active && !st.locked) return;
        StageBasis sb = (StageBasis) rt.stage;
        st.frame++;
        st.muzzlePulse = Math.max(0f, st.muzzlePulse - 0.08f);
        if (st.recoilFrame > 0) st.recoilFrame--;
        tickSuction(st, sb);
        tickShooting(st, sb, cannon);
        tickProjectiles(rt, st, sb, cannon);
        if (st.sucking == null && st.suctionQueue.isEmpty() && st.loadedQueue.isEmpty() && st.projectiles.isEmpty()
                && st.recoilFrame <= 0) {
            clear(rt, true);
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt == null ? null : rt.armyCanon;
        if (st == null || gra == null) return;
        if (DEBUG_OVERLAY) drawDebug(gra, st);
        if (!st.active && !st.locked && st.falling.isEmpty()) return;
        float[] muzzle = muzzleScreen(bbpainter, (StageBasis) rt.stage);
        float[] t = baseTransform(rt);
        if (t != null && st.pivotSet) {

            muzzle[0] = st.pivotX + (muzzle[0] - st.pivotX) * t[0] + t[2];
            muzzle[1] = st.pivotY + (muzzle[1] - st.pivotY) * t[1] + t[3];
        }
        drawMuzzle(gra, muzzle[0], muzzle[1], st);
    }

    public static boolean isManaged(Object entity) {
        return entity != null && MANAGED.containsKey(entity);
    }

    public static float drawScale(Object entity) {
        if (entity == null) return 1f;
        CrazyRuntime.StageRuntime rt = runtimeFor(entity);
        if (rt == null) return 1f;
        State st = rt.armyCanon;
        if (st.sucking != null && st.sucking.entity == entity) {
            StageBasis sb = (StageBasis) rt.stage;
            float total = Math.max(1f, Math.abs(st.sucking.originalPos - sb.ubase.pos));
            float left = Math.abs(st.sucking.entity.pos - sb.ubase.pos);
            float p = 1f - Math.max(0f, Math.min(1f, left / total));
            return 1f - p * 0.78f;
        }
        for (int i = 0; i < st.loadedQueue.size(); i++) {
            if (st.loadedQueue.get(i).entity == entity) return 0.22f;
        }
        for (int i = 0; i < st.projectiles.size(); i++) {
            Projectile p = st.projectiles.get(i);
            if (p.unit.entity == entity) return Math.max(0.22f, Math.min(1f, p.scale));
        }
        return 1f;
    }

    public static float drawRotation(Object entity) {
        if (entity == null) return 0f;
        CrazyRuntime.StageRuntime rt = runtimeFor(entity);
        if (rt == null) return 0f;
        State st = rt.armyCanon;
        for (int i = 0; i < st.projectiles.size(); i++) {
            Projectile p = st.projectiles.get(i);
            if (p.unit.entity == entity) {
                return (float) Math.sin(p.age * 0.34f) * 0.22f;
            }
        }
        for (int i = 0; i < st.falling.size(); i++) {
            FallingDeath fd = st.falling.get(i);
            if (fd.entity == entity) return fd.age * 0.18f;
        }
        return 0f;
    }

    public static float[] baseTransform(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return null;
        State st = rt.armyCanon;
        if (st == null) return null;
        float sy = st.springPos;
        float dev = 1f - sy;
        float recoil = st.recoilFrame > 0 ? st.recoilFrame / (float) RECOIL_FRAMES : 0f;
        if (Math.abs(dev) < 0.004f && Math.abs(st.springVel) < 0.002f && recoil <= 0.01f) return null;
        float sx = 1f + dev * 0.6f;
        float ease = recoil * recoil;
        float dx = 8f * ease;
        float shakeAmp = Math.max(0f, dev) * SHAKE_PER_COMPRESSION;
        if (shakeAmp > 0.05f) {
            dx += (float) Math.sin(st.animFrame * 1.7f) * shakeAmp;
        }
        return new float[]{sx, sy, dx, 0f, -0.04f * ease};
    }

    public static float[] castleTransform(CrazyRuntime.StageRuntime rt) {
        if (rt == null || rt.config == null || rt.config.beamMode != CrazyConfig.BeamMode.ARMY_CANON) return null;
        return baseTransform(rt);
    }

    public static void notifyCastlePivot(CrazyRuntime.StageRuntime rt, float px, float py) {
        if (rt == null) return;
        State st = rt.armyCanon;
        if (st == null) return;
        st.pivotX = px;
        st.pivotY = py;
        st.pivotSet = true;
    }

    public static float pivotXOr(CrazyRuntime.StageRuntime rt, float fallback) {
        State st = rt == null ? null : rt.armyCanon;
        return st != null && st.pivotSet ? st.pivotX : fallback;
    }

    public static float pivotYOr(CrazyRuntime.StageRuntime rt, float fallback) {
        State st = rt == null ? null : rt.armyCanon;
        return st != null && st.pivotSet ? st.pivotY : fallback;
    }

    private static void tickSuction(State st, StageBasis sb) {
        if (st.sucking == null && !st.suctionQueue.isEmpty()) {
            st.sucking = st.suctionQueue.remove(0);
            st.muzzlePulse = 1f;
        }
        if (st.sucking == null) return;
        UnitState us = st.sucking;
        if (!isValidManagedUnit(us.entity)) {
            MANAGED.remove(us.entity);
            st.sucking = null;
            return;
        }
        float target = muzzleWorldPos(sb);
        int targetLayer = muzzleLayer(sb);
        us.entity.pos = moveToward(us.entity.pos, target, SUCTION_SPEED);
        setFloatQuiet(us.entity, "lastPosition", us.entity.pos);
        writeLayer(us.entity, moveLayer(readLayer(us.entity), targetLayer, 4));
        makeUntouchable(us.entity);
        if (Math.abs(us.entity.pos - target) <= 5f) {
            us.entity.pos = target;
            writeLayer(us.entity, targetLayer);
            us.loaded = true;
            st.loadedQueue.add(us);
            st.sucking = null;
            st.muzzlePulse = 1f;
            st.springVel += KICK_GULP;
        }
    }

    private static void tickShooting(State st, StageBasis sb, Cannon cannon) {
        if (st.loadedQueue.isEmpty()) return;
        if (st.sucking != null || !st.suctionQueue.isEmpty()) return;
        if (st.frame < st.nextShotFrame) return;
        UnitState us = st.loadedQueue.remove(0);
        AbEntity target = nextTarget(sb, null);
        if (target == null) {
            startFallingDeath(st, sb, us.entity);
            st.nextShotFrame = st.frame + SHOT_INTERVAL;
            return;
        }
        float start = muzzleWorldPos(sb);
        us.entity.pos = start;
        writeLayer(us.entity, muzzleLayer(sb));
        Projectile p = new Projectile(us, target, start);
        p.layer = muzzleLayer(sb);
        st.projectiles.add(p);
        st.nextShotFrame = st.frame + SHOT_INTERVAL;
        st.recoilFrame = RECOIL_FRAMES;
        st.muzzlePulse = 1f;
        st.springVel += KICK_FIRE;
    }

    private static void tickProjectiles(CrazyRuntime.StageRuntime rt, State st, StageBasis sb, Cannon cannon) {
        for (int i = st.projectiles.size() - 1; i >= 0; i--) {
            Projectile p = st.projectiles.get(i);
            if (p.unit.entity == null || p.unit.entity.health <= 0L || p.unit.entity.dead) {
                st.projectiles.remove(i);
                continue;
            }
            if (!validTarget(p.target)) {
                p.target = nextTarget(sb, null);
                if (p.target == null) {
                    startFallingDeath(st, sb, p.unit.entity);
                    st.projectiles.remove(i);
                    continue;
                }
            }
            p.age++;
            p.pos = moveToward(p.pos, p.target.pos, PROJECTILE_SPEED);
            float travelled = Math.abs(p.pos - p.startPos);
            float progress = Math.max(0f, Math.min(1f, travelled / p.totalDistance));
            p.scale = PROJECTILE_START_SCALE + (1f - PROJECTILE_START_SCALE) * progress;
            p.unit.entity.pos = p.pos;
            writeLayer(p.unit.entity, moveLayer(p.layer, targetLayer(p.target), 3));
            p.layer = readLayer(p.unit.entity);
            setFloatQuiet(p.unit.entity, "lastPosition", p.pos);
            makeUntouchable(p.unit.entity);
            if (Math.abs(p.pos - p.target.pos) <= IMPACT_EPS || p.age >= MAX_PROJECTILE_FRAMES) {
                impact(rt, st, sb, cannon, p);
                st.projectiles.remove(i);
            }
        }
    }

    private static void impact(CrazyRuntime.StageRuntime rt, State st, StageBasis sb, Cannon cannon, Projectile p) {
        AbEntity target = p.target;
        if (target != null) {
            applyUnitAttack(p.unit.entity, target);
            forcePostUpdate(target);
        }
        startFallingDeath(st, sb, p.unit.entity);
    }

    private static void startFallingDeath(State st, StageBasis sb, Entity entity) {
        if (entity == null) return;
        float dir = sb.r.nextFloat() < 0.5f ? -1f : 1f;
        float mag = THROW_MIN + sb.r.nextFloat() * (THROW_MAX - THROW_MIN);

        st.falling.add(new FallingDeath(entity, dir * mag / 18f, -5f - sb.r.nextFloat() * 3f, readLayer(entity)));
    }

    private static void tickFalling(CrazyRuntime.StageRuntime rt) {
        State st = rt.armyCanon;
        for (int i = st.falling.size() - 1; i >= 0; i--) {
            FallingDeath fd = st.falling.get(i);
            Entity e = fd.entity;
            if (e == null) {
                st.falling.remove(i);
                continue;
            }
            fd.age++;
            fd.vy += 1.3f;
            e.pos += fd.vx;
            int layer = Math.round(readLayer(e) + fd.vy);
            if (layer >= fd.groundLayer || fd.age > 30) {
                writeLayer(e, fd.groundLayer);
                restoreTouchable(e);
                e.health = 0L;
                try { e.kill(Entity.KillMode.NORMAL); } catch (Throwable ignored) {}
                MANAGED.remove(e);
                st.falling.remove(i);
            } else {
                writeLayer(e, layer);
                setFloatQuiet(e, "lastPosition", e.pos);
                makeUntouchable(e);
            }
        }
    }

    private static List<Entity> collectUnits(StageBasis sb) {
        List<Entity> out = new ArrayList<Entity>();
        for (int i = 0; i < sb.le.size(); i++) {
            Entity e = sb.le.get(i);
            if (!isArmyCandidate(e)) continue;
            out.add(e);
        }
        return out;
    }

    private static CrazyRuntime.StageRuntime runtimeFor(Object entity) {
        if (!(entity instanceof Entity)) return null;
        try {
            return CrazyRuntime.get(((Entity) entity).basis);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isArmyCandidate(Entity e) {
        if (e == null || e.dead || e.health <= 0L || e.isBase()) return false;
        if (EntityAccess.isBoss(e)) return false;
        if (e instanceof EUnit && ((EUnit) e).isSpirit) return false;
        boolean player = e.dire == -1 || ConvertedRegistry.isConverted(e);
        if (!player) return false;
        try {
            if ((e.touchable() & 1) == 0) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    private static boolean isValidManagedUnit(Entity e) {
        return e != null && !e.dead && e.health > 0L;
    }

    private static AbEntity nextTarget(StageBasis sb, AbEntity skip) {
        Entity best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < sb.le.size(); i++) {
            Entity e = sb.le.get(i);
            if (e == skip || !isLiveEnemy(e)) continue;
            float d = Math.abs(e.pos - sb.ubase.pos);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best != null) return best;
        if (sb.ebase != skip && sb.ebase != null && sb.ebase.health > 0L) return sb.ebase;
        return null;
    }

    private static boolean isLiveEnemy(Entity e) {
        if (e == null || e.dead || e.health <= 0L || e.dire != 1 || e.isBase()) return false;
        try {
            return (e.touchable() & 1) != 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean validTarget(AbEntity target) {
        if (target == null || target.health <= 0L) return false;
        if (target instanceof Entity) return isLiveEnemy((Entity) target);
        return target instanceof ECastle;
    }

    private static void applyUnitAttack(Entity unit, AbEntity target) {
        try {
            AtkModelEntity model = (AtkModelEntity) BCUFields.get(unit, "aam");
            int count = Math.max(1, unit.data.getAtkCount());
            for (int i = 0; i < count; i++) {
                Data.Proc proc = Data.Proc.blank();
                int atkValue = modelAttack(model, i, proc);
                MaskAtk mask = unit.data.getAtkModel(i);
                AttackAb atk = new AttackSimple(unit, model, atkValue, unit.traits, unit.getAbi(), proc,
                        target.pos - 1f, target.pos + 1f, mask, readLayer(unit), false, i);
                if (atk != null) target.damaged(atk);
            }
        } catch (Throwable t) {
            Logger.err("Army Canon unit damage failed", t);
        }
    }

    private static void forcePostUpdate(AbEntity target) {
        try {
            target.postUpdate();
        } catch (Throwable ignored) {}
    }

    private static int modelAttack(AtkModelEntity model, int ind, Data.Proc proc) throws Exception {
        Object val = BCUFields.method(model.getClass(), "getAttack", int.class, Data.Proc.class)
                .invoke(model, ind, proc);
        return val instanceof Number ? ((Number) val).intValue() : 0;
    }

    private static void clear(CrazyRuntime.StageRuntime rt, boolean preserveFalling) {
        State st = rt.armyCanon;
        restore(st.sucking);
        for (int i = 0; i < st.suctionQueue.size(); i++) restore(st.suctionQueue.get(i));
        for (int i = 0; i < st.loadedQueue.size(); i++) restore(st.loadedQueue.get(i));
        for (int i = 0; i < st.projectiles.size(); i++) restore(st.projectiles.get(i).unit);
        st.active = false;
        st.locked = false;
        clearNativeCannonState(st.cannon);
        st.cannon = null;
        st.sucking = null;
        st.suctionQueue.clear();
        st.loadedQueue.clear();
        st.projectiles.clear();
        st.nextShotFrame = 0;
        st.muzzlePulse = 0f;
        st.recoilFrame = 0;
        st.totalQueued = 0;

        if (!preserveFalling) {
            for (int i = 0; i < st.falling.size(); i++) {
                MANAGED.remove(st.falling.get(i).entity);
            }
            st.falling.clear();
        }
    }

    private static void clearNativeCannonState(Cannon cannon) {
        if (cannon == null) return;
        try { BCUFields.set(cannon, "anim", null); } catch (Throwable ignored) {}
        try { BCUFields.set(cannon, "atka", null); } catch (Throwable ignored) {}
        try { BCUFields.set(cannon, "exta", null); } catch (Throwable ignored) {}
        try { BCUFields.setInt(cannon, "preTime", 0); } catch (Throwable ignored) {}
        try { BCUFields.setInt(cannon, "duration", 0); } catch (Throwable ignored) {}
    }

    private static void primeNativeBaseAnim(Cannon cannon) {
        if (cannon == null) return;
        try {
            BCUFields.set(cannon, "anim", CommonStatic.getBCAssets().atks[cannon.id].getEAnim(NyCastle.NyType.BASE));
        } catch (Throwable ignored) {}
    }

    private static void restore(UnitState us) {
        if (us == null || us.entity == null) return;
        if (us.entity.health > 0L && !us.entity.dead) {
            writeLayer(us.entity, us.originalLayer);
            restoreTouchable(us.entity, us.originalStatus44);
        }
        MANAGED.remove(us.entity);
    }

    private static void makeUntouchable(Entity e) {
        try {
            int[][] status = (int[][]) BCUFields.get(e, "status");
            status[44][0] = 99999;
        } catch (Throwable ignored) {}
    }

    private static void restoreTouchable(Entity e) {
        restoreTouchable(e, 0);
    }

    private static void restoreTouchable(Entity e, int value) {
        try {
            int[][] status = (int[][]) BCUFields.get(e, "status");
            status[44][0] = value;
        } catch (Throwable ignored) {}
    }

    private static int readStatus44(Entity e) {
        try {
            int[][] status = (int[][]) BCUFields.get(e, "status");
            return status[44][0];
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int safeAtkCount(Entity e) {
        try {
            return e.data.getAtkCount();
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static int readLayer(Object entity) {
        try {
            return EntityAccess.getLayer(entity);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void writeLayer(Object entity, int layer) {
        try {
            EntityAccess.setLayer(entity, layer);
        } catch (Throwable ignored) {}
        try {
            BCUFields.field(entity.getClass(), "layer").setInt(entity, layer);
        } catch (Throwable ignored) {}
    }

    private static void setFloatQuiet(Object entity, String field, float value) {
        try {
            BCUFields.field(entity.getClass(), field).setFloat(entity, value);
        } catch (Throwable ignored) {}
    }

    private static float moveToward(float current, float target, float maxStep) {
        float d = target - current;
        if (Math.abs(d) <= maxStep) return target;
        return current + Math.signum(d) * maxStep;
    }

    private static int moveLayer(int current, int target, int maxStep) {
        int d = target - current;
        if (Math.abs(d) <= maxStep) return target;
        return current + (d > 0 ? maxStep : -maxStep);
    }

    private static float muzzleWorldPos(StageBasis sb) {
        int id = cannonId(sb);
        float baseWorldPos = sb.ubase != null ? sb.ubase.pos : sb.st.len - 800f;
        return baseWorldPos + CANNON_X[id] / 0.32f + (MUZZLE_DX[id] * CANNON_SPRITE_SCALE) / 0.32f;
    }

    private static int muzzleLayer(StageBasis sb) {
        int id = cannonId(sb);
        return Math.round((CANNON_Y[id] + MUZZLE_DY[id] * CANNON_SPRITE_SCALE) / 4f);
    }

    private static int targetLayer(AbEntity target) {
        if (target instanceof Entity) return readLayer(target);
        return 0;
    }

    private static int cannonId(StageBasis sb) {
        try {
            return Math.max(0, Math.min(CANNON_Y.length - 1, sb.canon.id));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static float[] muzzleScreen(Object bbpainter, StageBasis sb) {
        float siz = BBPainterAccess.getSiz(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);
        int id = cannonId(sb);
        float baseWorldPos = sb.ubase != null ? sb.ubase.pos : sb.st.len - 800f;
        float psiz = siz * CANNON_SPRITE_SCALE;
        float x = CrazyRender.screenX(bbpainter, baseWorldPos) + CANNON_X[id] * siz + MUZZLE_DX[id] * psiz;
        float y = midh + (CANNON_Y[id] - 156f) * siz + MUZZLE_DY[id] * psiz;
        return new float[]{x, y};
    }

    private static void drawDebug(FakeGraphics gra, State st) {
        try {
            int x = 12;
            int y = 36;
            debugBar(gra, x, y, (st.springPos - 0.6f) / 0.8f, new Color(120, 220, 120));
            float charge = st.totalQueued <= 0 ? 0f
                    : Math.min(1f, st.loadedQueue.size() / (float) st.totalQueued);
            debugBar(gra, x, y + 10, charge, new Color(255, 200, 80));
            debugBar(gra, x, y + 20, st.recoilFrame / (float) RECOIL_FRAMES, new Color(255, 100, 100));
            debugBar(gra, x, y + 30, st.active ? 1f : (st.locked ? 0.5f : 0f), new Color(120, 160, 255));
        } catch (Throwable ignored) {}
    }

    private static void debugBar(FakeGraphics gra, int x, int y, float frac, Color c) {
        frac = Math.max(0f, Math.min(1f, frac));
        gra.setComposite(FakeGraphics.TRANS, 150, 0);
        gra.setColor(40, 40, 40);
        gra.fillRect(x, y, 120, 6);
        gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
        gra.fillRect(x, y, Math.round(120 * frac), 6);
        gra.setComposite(FakeGraphics.DEF, 0, 0);
    }

    private static void drawMuzzle(FakeGraphics gra, float x, float y, State st) {
        float pulse = Math.max(st.muzzlePulse, st.recoilFrame > 0 ? st.recoilFrame / (float) RECOIL_FRAMES : 0f);
        if (pulse <= 0.01f) return;
        float sx = st.recoilFrame > 0 ? 1.25f : 0.9f;
        float sy = st.recoilFrame > 0 ? 0.9f : 1.16f;
        oval(gra, x, y, 14f * sx * pulse, 9f * sy * pulse, new Color(230, 250, 255), 90);
    }

    private static void oval(FakeGraphics gra, float x, float y, float rx, float ry, Color c, int alpha) {
        if (alpha <= 0 || rx <= 0f || ry <= 0f) return;
        common.system.fake.FakeTransform at = gra.getTransform();
        try {
            gra.setComposite(FakeGraphics.TRANS, Math.max(0, Math.min(255, alpha)), 0);
            gra.setColor(c.getRed(), c.getGreen(), c.getBlue());
            int ix = Math.max(1, Math.round(rx));
            int iy = Math.max(1, Math.round(ry));
            for (int dy = -iy; dy <= iy; dy++) {
                float yy = dy / (float) iy;
                int span = Math.round(ix * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
                gra.fillRect(x - span, y + dy, span * 2 + 1, 1);
            }
        } finally {
            gra.setComposite(FakeGraphics.DEF, 0, 0);
            gra.setTransform(at);
            gra.delete(at);
        }
    }
}
