package manualcontrol.crazy.collision;

import common.battle.attack.AttackAb;
import common.battle.entity.AbEntity;
import common.battle.entity.Entity;
import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class PhysicalCollision {

    public static volatile boolean ENABLED = true;

    public static volatile boolean NARROW_PHASE = true;

    public static volatile boolean BROAD_PHASE = true;

    public static volatile boolean PHYSICAL_ALL = false;

    public static volatile boolean SILHOUETTE = true;

    public static volatile boolean PIXEL_MASK = true;

    public static volatile int MASK_SAMPLES = 12;

    public static volatile int SWING_WINDOW = 3;

    public static volatile int CONTACT_WINDOW_FWD = 3;

    public static volatile float STRIKER_SIZE_REF = 200f;
    public static volatile float VICTIM_SIZE_REF = 300f;
    public static volatile float MASS_MIN = 0.4f;
    public static volatile float MASS_MAX = 2.5f;

    public static volatile float MELEE_MARGIN = 200f;

    public static volatile boolean CONTACT_INCLUDE = true;

    public static volatile boolean VERTICAL_GATE = true;
    public static volatile float VERTICAL_REACH_MARGIN = 60f;

    public static volatile boolean WALK_PAST_UNREACHABLE = true;

    public static volatile boolean MELEE_CLOSE_IN = true;
    public static volatile float STOP_GAP = 80f;

    private static long lastWalkLog = 0L;

    private static final int ST_VANILLA = 0;
    private static final int ST_RANGED = 1;
    private static final int ST_MELEE = 2;

    private static long lastEligibleLog = 0L;
    private static long lastImpactLog = 0L;
    private static long lastIncludeLog = 0L;
    private static long lastVertLog = 0L;
    private static long lastTerrainAirborneLog = 0L;

    private PhysicalCollision() {}

    private static final Map<Object, Integer> ELIGIBLE =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());

    private static final Map<Object, AttackWindow> ORIGINAL_WINDOWS =
            Collections.synchronizedMap(new WeakHashMap<Object, AttackWindow>());
    private static final class AttackWindow {
        final float sta;
        final float end;

        AttackWindow(float sta, float end) {
            this.sta = sta;
            this.end = end;
        }
    }

    public static final class Impact {
        public final Object attack;
        public final Object attacker;
        public final float dirX;
        public final float upFactor;
        public final float swingX, swingY;
        public final float overkill;
        public final float massMult;
        public final long ms;
        Impact(Object attack, Object attacker, float dirX, float upFactor,
               float swingX, float swingY, float overkill, float massMult) {
            this.attack = attack;
            this.attacker = attacker;
            this.dirX = dirX;
            this.upFactor = upFactor;
            this.swingX = swingX;
            this.swingY = swingY;
            this.overkill = overkill;
            this.massMult = massMult;
            this.ms = System.currentTimeMillis();
        }
    }

    private static final Map<Object, Impact> IMPACTS =
            Collections.synchronizedMap(new WeakHashMap<Object, Impact>());

    public static Impact consumeImpact(Object victim, Object attack) {
        if (victim == null) return null;
        Impact im = IMPACTS.remove(victim);
        if (im == null) return null;
        if (im.attack != attack) return null;
        if (System.currentTimeMillis() - im.ms > 1000L) return null;
        return im;
    }

    public static boolean spriteContact(Object attacker, Object target) {
        if (attacker == null || target == null) return true;
        try {
            SpriteBounds.Silhouette a = SpriteBounds.silhouetteOf(attacker);
            SpriteBounds.Silhouette e = SpriteBounds.silhouetteOf(target);
            if (a == null || e == null) return true;
            if (!a.box.overlaps(e.box)) return false;
            if (SILHOUETTE && !a.quads.isEmpty() && !e.quads.isEmpty()) {
                return silhouettesTouch(a.quads, e.quads) != null;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean strictSpriteContact(Object first, Object second) {
        if (first == null || second == null) return false;
        try {
            SpriteBounds.Silhouette a = SpriteBounds.silhouetteOf(first);
            SpriteBounds.Silhouette b = SpriteBounds.silhouetteOf(second);
            if (a == null || b == null || !a.box.overlaps(b.box)) return false;
            if (a.quads.isEmpty() || b.quads.isEmpty()) return false;
            return silhouettesTouch(a.quads, b.quads) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean strictSilhouetteContact(SpriteBounds.Silhouette first,
                                                  SpriteBounds.Silhouette second) {
        if (first == null || second == null || first.box == null || second.box == null
                || !first.box.overlaps(second.box)
                || first.quads == null || second.quads == null
                || first.quads.isEmpty() || second.quads.isEmpty()) return false;
        return silhouettesTouch(first.quads, second.quads) != null;
    }

    public static void beforeCapture(Object attackObj) {
        if (!ENABLED) return;
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb atk = (AttackAb) attackObj;
        Entity attacker = atk.attacker;
        if (attacker == null) return;

        if (manualcontrol.adventure.AdventureBridge.isConjuredSpirit(attacker)) return;
        try {
            SpriteBounds.WorldBox box = SpriteBounds.of(attacker);
            float sta = BCUFields.getFloat(atk, "sta");
            float end = BCUFields.getFloat(atk, "end");
            ORIGINAL_WINDOWS.put(atk, new AttackWindow(sta, end));

            int state;
            if (box == null) state = ST_VANILLA;
            else if (isLdOmni(attacker) && !PHYSICAL_ALL) state = ST_VANILLA;
            else if (meleeEligible(attacker, box, sta, end)) state = ST_MELEE;
            else state = ST_RANGED;
            ELIGIBLE.put(atk, state);

            if (state == ST_MELEE && BROAD_PHASE) {
                float left = Math.min(Math.min(sta, end), box.x0);
                float right = Math.max(Math.max(sta, end), box.x1);
                setFloat(atk, "sta", left);
                setFloat(atk, "end", right);
            }
        } catch (Throwable t) {
            Logger.err("PhysicalCollision.beforeCapture failed", t);
        }
    }

    static void clearTouchFields(Entity e) {
        try { BCUFields.field(e.getClass(), "touch").setBoolean(e, false); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "touchEnemy").setBoolean(e, false); } catch (Throwable ignored) {}
    }

    private static void establishTouchFields(Entity e) {
        try { BCUFields.field(e.getClass(), "touch").setBoolean(e, true); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "touchEnemy").setBoolean(e, true); } catch (Throwable ignored) {}
    }

    private static boolean isLdOmni(Entity attacker) {
        try {
            Object data = BCUFields.get(attacker, "data");
            return invokeBool(data, "isLD") || invokeBool(data, "isOmni");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean filterTouch(boolean result, Object entityObj) {
        if (!(entityObj instanceof Entity)) return result;
        Entity e = (Entity) entityObj;

        if (ENABLED && (DeathLaunchFeature.isLaunching(e) || SurgeJuggleFeature.isJuggling(e))) {
            clearTouchFields(e);
            return false;
        }
        if (!ENABLED) return result;

        if (manualcontrol.custommap.CustomMapBattleRuntime.isActiveStage(e.basis))
            return filterCustomMapTouch(result, e);
        if (!result) return false;
        if (!WALK_PAST_UNREACHABLE) return result;
        try {
            if (isLdOmni(e)) return result;
            SpriteBounds.WorldBox aBox = SpriteBounds.of(e);
            if (aBox == null) return result;

            Object aam = BCUFields.get(e, "aam");
            float[] ds = (float[]) BCUFields.invoke(aam, "touchRange");
            Object touchObj = BCUFields.invoke(e, "getTouch");
            int touchFlags = touchObj instanceof Number ? ((Number) touchObj).intValue() : 1;
            List<AbEntity> le = e.basis.inRange(touchFlags, -e.dire, ds[0], ds[1], false);
            if (le == null || le.isEmpty()) return result;

            boolean meleeLike = MELEE_CLOSE_IN && meleeEligible(e, aBox, ds[0], ds[1]);
            float pos = BCUFields.getFloat(e, "pos");
            float attackFar = Math.abs(ds[0] - pos) > Math.abs(ds[1] - pos) ? ds[0] : ds[1];
            float dirAtk = Math.signum(attackFar - pos);

            for (int i = 0; i < le.size(); i++) {
                AbEntity c = le.get(i);
                if (c == null) continue;
                if (DeathLaunchFeature.isLaunching(c) || SurgeJuggleFeature.isJuggling(c)) continue;
                if (isBase(c)) return result;
                SpriteBounds.WorldBox cBox = SpriteBounds.of(c);
                if (cBox == null) return result;
                if (cBox.y1 < aBox.y0 - VERTICAL_REACH_MARGIN) continue;
                if (meleeLike && dirAtk != 0f) {

                    float gap = dirAtk > 0f ? cBox.x0 - aBox.x1 : aBox.x0 - cBox.x1;
                    if (gap > STOP_GAP) continue;
                }
                return result;
            }

            clearTouchFields(e);
            long now = System.currentTimeMillis();
            if (now - lastWalkLog > 1000L) {
                Logger.log("PhysCol walk-past: " + e.getClass().getSimpleName()
                        + " ignores " + le.size() + " unreachable flyer(s), keeps walking");
                lastWalkLog = now;
            }
            return false;
        } catch (Throwable t) {
            return result;
        }
    }

    private static boolean filterCustomMapTouch(boolean nativeResult, Entity attacker) {
        if (attacker == null) return nativeResult;
        if (!manualcontrol.custommap.CustomMapBattleRuntime
                .canInitiateTerrainAttack(attacker)) {
            clearTouchFields(attacker);
            return false;
        }
        try {
            Object aam = BCUFields.get(attacker, "aam");
            float[] range = (float[]) BCUFields.invoke(aam, "touchRange");
            Object touchObj = BCUFields.invoke(attacker, "getTouch");
            int touchFlags = touchObj instanceof Number
                    ? ((Number) touchObj).intValue() : 1;
            SpriteBounds.WorldBox attackerBox = SpriteBounds.of(attacker);

            if (attackerBox == null || isLdOmni(attacker)
                    || !meleeEligible(attacker, attackerBox, range[0], range[1]))
                return nativeResult;

            if (attacker.basis != null && attacker.basis.le != null) {
                for (int i = 0; i < attacker.basis.le.size(); i++) {
                    Entity target = attacker.basis.le.get(i);
                    if (!customEngagementCandidate(attacker, target, touchFlags))
                        continue;
                    SpriteBounds.WorldBox targetBox = SpriteBounds.of(target);
                    if (targetBox == null) {

                        return nativeResult;
                    }
                    if (!currentAttackRegionOverlaps(attacker.pos, attackerBox,
                            range[0], range[1], targetBox)) continue;
                    if (manualcontrol.custommap.CustomMapBattleRuntime
                            .directAttackLineBlocked(attacker, target)) continue;
                    establishTouchFields(attacker);
                    return true;
                }
            }

            AbEntity base = null;
            try { base = attacker.basis.getBase(attacker.dire); }
            catch (Throwable ignored) {}
            if (base != null && customEngagementCandidate(
                    attacker, base, touchFlags)) {
                SpriteBounds.WorldBox baseBox = customMapVisibleBox(base);
                if (baseBox == null) return nativeResult;
                if (currentAttackRegionOverlaps(attacker.pos, attackerBox,
                        range[0], range[1], baseBox)
                        && !manualcontrol.custommap.CustomMapBattleRuntime
                        .directAttackLineBlocked(attacker, base)) {
                    establishTouchFields(attacker);
                    return true;
                }
            }

            clearTouchFields(attacker);
            return false;
        } catch (Throwable ignored) {
            return nativeResult;
        }
    }

    private static boolean customEngagementCandidate(
            Entity attacker, AbEntity target, int touchFlags) {
        boolean dead = target != null && (target.health <= 0L
                || (target instanceof Entity && ((Entity) target).dead));
        if (target == null || target == attacker || dead || isBase(target)) {
            if (target == null || target == attacker || dead) return false;
        } else if (!(target instanceof Entity)
                || ((Entity) target).dire != -attacker.dire) return false;
        if (DeathLaunchFeature.isLaunching(target)
                || SurgeJuggleFeature.isJuggling(target)) return false;
        try {
            if ((target.touchable() & touchFlags) == 0) return false;
            if ((attacker.getAbi() & 8) > 0) {
                @SuppressWarnings("rawtypes")
                List traits = (List) BCUFields.get(attacker, "traits");
                if (!target.traitCompatible(traits, attacker, true)) return false;
            }
        } catch (Throwable ignored) {

            return true;
        }
        return true;
    }

    public static boolean currentAttackRegionOverlaps(
            float attackerPos, SpriteBounds.WorldBox attackerBox,
            float sta, float end, SpriteBounds.WorldBox targetBox) {
        if (attackerBox == null || targetBox == null
                || Float.isNaN(attackerPos) || Float.isNaN(sta) || Float.isNaN(end))
            return false;
        float attackFar = Math.abs(sta - attackerPos) > Math.abs(end - attackerPos)
                ? sta : end;
        float direction = Math.signum(attackFar - attackerPos);

        if (direction > 0f && targetBox.x1 < attackerPos - .51f) return false;
        if (direction < 0f && targetBox.x0 > attackerPos + .51f) return false;

        float x0 = Math.min(Math.min(sta, end), attackerPos);
        float x1 = Math.max(Math.max(sta, end), attackerPos);
        if (direction >= 0f) x1 = Math.max(x1, attackerBox.x1);
        if (direction <= 0f) x0 = Math.min(x0, attackerBox.x0);
        SpriteBounds.WorldBox region = new SpriteBounds.WorldBox(
                x0, attackerBox.y0 - VERTICAL_REACH_MARGIN,
                x1, attackerBox.y1 + VERTICAL_REACH_MARGIN);
        return region.overlaps(targetBox);
    }

    public static void afterCapture(Object attackObj) {
        if (!ENABLED) return;
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb atk = (AttackAb) attackObj;
        Integer stateObj = ELIGIBLE.remove(atk);
        AttackWindow original = ORIGINAL_WINDOWS.remove(atk);
        if (original != null) {
            setFloat(atk, "sta", original.sta);
            setFloat(atk, "end", original.end);
        }
        if (stateObj == null) return;
        int state = stateObj.intValue();
        Entity attacker = atk.attacker;
        if (attacker == null) return;
        boolean customDirect = manualcontrol.custommap.CustomMapBattleRuntime
                .isActiveStage(attacker.basis) && !isPropagatingAttack(atk);

        if (manualcontrol.adventure.AdventureBridge.isAdventureEntity(attacker)) return;
        try {
            @SuppressWarnings("unchecked")
            List<AbEntity> baseChecked =
                    (List<AbEntity>) BCUFields.get(atk, "capt");
            pruneIllegalBaseTargets(atk, attacker, baseChecked, original);
            if (customDirect && baseChecked != null) {
                Iterator<AbEntity> terrainTargets = baseChecked.iterator();
                while (terrainTargets.hasNext()) {
                    AbEntity target = terrainTargets.next();
                    if (manualcontrol.custommap.CustomMapBattleRuntime
                            .directAttackLineBlocked(attacker, target))
                        terrainTargets.remove();
                }
            }
        } catch (Throwable t) {
            Logger.err("PhysicalCollision base-range validation failed", t);
        }
        try {
            SpriteBounds.Silhouette aSil = SpriteBounds.silhouetteOf(attacker);
            if (aSil == null) return;

            @SuppressWarnings("unchecked")
            List<AbEntity> capt = (List<AbEntity>) BCUFields.get(atk, "capt");
            if (capt == null) return;

            if (!capt.isEmpty()) {
                Iterator<AbEntity> corpses = capt.iterator();
                while (corpses.hasNext()) {
                    AbEntity cand = corpses.next();
                    if (DeathLaunchFeature.isLaunching(cand)
                            || SurgeJuggleFeature.isJuggling(cand)) corpses.remove();
                }
            }

            SwingContext[] ctxH = new SwingContext[1];
            boolean[] ctxBuilt = new boolean[1];

            if (customDirect && state == ST_MELEE) {
                reconcileCustomMapBaseTarget(atk, attacker, aSil, capt,
                        ctxH, ctxBuilt);
            }

            if (state == ST_MELEE && NARROW_PHASE && !capt.isEmpty()) {
                Iterator<AbEntity> it = capt.iterator();
                while (it.hasNext()) {
                    AbEntity e = it.next();
                    if (isBase(e)) continue;
                    if (keepTerrainTraversalCapture(attacker, e)) continue;
                    SpriteBounds.Silhouette eSil = SpriteBounds.silhouetteOf(e);
                    if (eSil == null) continue;
                    if (!aSil.box.overlaps(eSil.box)) {
                        it.remove();
                        continue;
                    }

                    Contact contact = null;
                    if (SILHOUETTE && !aSil.quads.isEmpty() && !eSil.quads.isEmpty()) {
                        contact = contactWindowed(attacker, aSil, eSil, ctxH, ctxBuilt);
                        if (contact == null) {
                            it.remove();
                            continue;
                        }
                    }

                    if (!ctxBuilt[0]) {
                        ctxH[0] = buildSwingContext(attacker, aSil);
                        ctxBuilt[0] = true;
                    }
                    registerImpact(atk, attacker, e, eSil, contact, ctxH[0]);
                }
            } else if (state == ST_RANGED && VERTICAL_GATE && !capt.isEmpty()) {

                Iterator<AbEntity> it = capt.iterator();
                while (it.hasNext()) {
                    AbEntity e = it.next();
                    if (isBase(e)) continue;
                    if (keepTerrainTraversalCapture(attacker, e)) continue;
                    SpriteBounds.Silhouette eSil = SpriteBounds.silhouetteOf(e);
                    if (eSil == null) continue;
                    if (eSil.box.y1 < aSil.box.y0 - VERTICAL_REACH_MARGIN) {
                        it.remove();
                        long now = System.currentTimeMillis();
                        if (now - lastVertLog > 500L) {
                            Logger.log("PhysCol vertical gate: " + e.getClass().getSimpleName()
                                    + " floats above " + attacker.getClass().getSimpleName()
                                    + " (victimBottom=" + Math.round(eSil.box.y1)
                                    + " < attackerTop=" + Math.round(aSil.box.y0) + " - margin)");
                            lastVertLog = now;
                        }
                    }
                }
            }

            if (CONTACT_INCLUDE && !aSil.quads.isEmpty()) {
                includeTouching(atk, attacker, aSil, capt, ctxH, ctxBuilt);
            }
        } catch (Throwable t) {
            Logger.err("PhysicalCollision.afterCapture failed", t);
        }
    }

    private static boolean isPropagatingAttack(AttackAb attack) {
        if (attack == null) return false;
        String name = attack.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("wave") || name.contains("surge")
                || name.contains("volcano") || name.contains("blast");
    }

    private static void pruneIllegalBaseTargets(AttackAb atk, Entity attacker,
                                                List<AbEntity> captured,
                                                AttackWindow original) {
        if (captured == null || captured.isEmpty() || attacker == null) return;
        AbEntity opposingBase = null;
        try {
            opposingBase = attacker.basis.getBase(attacker.dire);
        } catch (Throwable ignored) {}
        if (opposingBase == null) return;
        boolean longAttack = false;
        try {
            longAttack = BCUFields.getBoolean(atk, "isLongAtk");
        } catch (Throwable ignored) {}
        float sta = original == null ? BCUFields.getFloat(atk, "sta") : original.sta;
        float end = original == null ? BCUFields.getFloat(atk, "end") : original.end;
        Iterator<AbEntity> iterator = captured.iterator();
        while (iterator.hasNext()) {
            AbEntity target = iterator.next();
            if (!isBase(target)) continue;
            boolean allowed = opposingBase != null && target == opposingBase
                    && baseAllowedByOriginalRange(target.pos, sta, end,
                    longAttack, attacker.dire, atk.dire);
            if (!allowed) iterator.remove();
        }
    }

    private static void reconcileCustomMapBaseTarget(
            AttackAb atk, Entity attacker, SpriteBounds.Silhouette attackerSil,
            List<AbEntity> captured, SwingContext[] ctxH, boolean[] ctxBuilt) {
        if (atk == null || attacker == null || attackerSil == null
                || captured == null) return;
        AbEntity opposing = null;
        try { opposing = attacker.basis.getBase(attacker.dire); }
        catch (Throwable ignored) {}

        Iterator<AbEntity> bases = captured.iterator();
        while (bases.hasNext()) {
            AbEntity candidate = bases.next();
            if (isBase(candidate) && candidate != opposing) bases.remove();
        }
        if (opposing == null) return;
        try {
            if ((opposing.touchable() & atk.touch) == 0
                    || ((atk.abi & 8) > 0
                    && !opposing.traitCompatible(atk.trait, attacker, true))) {
                captured.remove(opposing);
                return;
            }
        } catch (Throwable ignored) {

            return;
        }

        SpriteBounds.Silhouette baseSil = SpriteBounds.silhouetteOf(opposing);
        if (baseSil == null) {
            SpriteBounds.WorldBox estimated = customMapVisibleBox(opposing);
            if (estimated != null) baseSil = new SpriteBounds.Silhouette(
                    estimated, Collections.<MeasuringGraphics.PartQuad>emptyList());
        }
        if (baseSil == null) return;

        boolean touches = !manualcontrol.custommap.CustomMapBattleRuntime
                .directAttackLineBlocked(attacker, opposing)
                && attackerSil.box.overlaps(baseSil.box);
        if (touches && SILHOUETTE && !attackerSil.quads.isEmpty()
                && !baseSil.quads.isEmpty()) {
            touches = contactWindowed(attacker, attackerSil, baseSil,
                    ctxH, ctxBuilt) != null;
        }
        if (!touches) {
            captured.remove(opposing);
            return;
        }

        if (captured.contains(opposing)) return;
        boolean single;
        try { single = !BCUFields.getBoolean(atk, "range"); }
        catch (Throwable ignored) { return; }

        if (!single || captured.isEmpty()) captured.add(opposing);
    }

    private static SpriteBounds.WorldBox customMapVisibleBox(AbEntity target) {
        SpriteBounds.WorldBox measured = SpriteBounds.of(target);
        if (measured != null || target == null || !isBase(target)) return measured;
        try {
            manualcontrol.custommap.CustomMapDocument.ModeVariant terrain =
                    manualcontrol.custommap.CustomMapRuntime.activeBattleTerrain();
            if (terrain == null || !terrain.containsWorldX(target.pos)) return null;
            float support = terrain.surfaceLayerAt(target.pos);
            if (Float.isNaN(support)) return null;
            float groundY = (support + 2.5f) * 4f / SpriteBounds.RAT;
            float halfWidth = 90f / SpriteBounds.RAT;
            float height = 150f / SpriteBounds.RAT;
            float bottomPad = 8f / SpriteBounds.RAT;
            return new SpriteBounds.WorldBox(target.pos - halfWidth,
                    groundY - height, target.pos + halfWidth,
                    groundY + bottomPad);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean baseAllowedByOriginalRange(
            float basePos, float sta, float end, boolean longAttack,
            int attackerDirection, int attackDirection) {
        float min = Math.min(sta, end) - 0.51f;
        float max = Math.max(sta, end) + 0.51f;
        if (basePos >= min && basePos <= max) return true;
        if (!longAttack || attackerDirection == 0
                || attackerDirection != attackDirection) return false;
        return attackerDirection < 0 ? sta <= basePos : sta >= basePos;
    }

    private static void includeTouching(AttackAb atk, Entity attacker,
                                        SpriteBounds.Silhouette aSil, List<AbEntity> capt,
                                        SwingContext[] ctxH, boolean[] ctxBuilt) {
        try {
            boolean single;
            try {
                single = !BCUFields.getBoolean(atk, "range");
            } catch (Throwable t) {
                return;
            }
            if (single && !capt.isEmpty()) return;

            java.util.Set<?> attacked = null;
            try {
                attacked = (java.util.Set<?>) BCUFields.get(atk, "attacked");
            } catch (Throwable ignored) {}

            common.battle.StageBasis sb;
            try {
                sb = (common.battle.StageBasis) BCUFields.get(attacker, "basis");
            } catch (Throwable t) {
                return;
            }
            if (sb == null) return;

            ArrayList<AbEntity> extras = new ArrayList<AbEntity>();
            ArrayList<SpriteBounds.Silhouette> extraSils = new ArrayList<SpriteBounds.Silhouette>();
            ArrayList<Contact> extraContacts = new ArrayList<Contact>();
            for (int i = 0; i < sb.le.size(); i++) {
                Entity e = sb.le.get(i);
                if (e == null || e == attacker || e.dead) continue;
                if (DeathLaunchFeature.isLaunching(e) || SurgeJuggleFeature.isJuggling(e)) continue;
                if (e.dire != -atk.dire) continue;
                try {
                    if ((e.touchable() & atk.touch) == 0) continue;
                } catch (Throwable t) {
                    continue;
                }
                if (capt.contains(e)) continue;
                if (attacked != null && attacked.contains(e)) continue;
                if ((atk.abi & 8) > 0 && !ctargetable(e, atk, attacker)) continue;
                SpriteBounds.Silhouette eSil = SpriteBounds.silhouetteOf(e);
                if (eSil == null || !aSil.box.overlaps(eSil.box)) continue;
                Contact c = null;
                if (!eSil.quads.isEmpty()) {
                    c = contactWindowed(attacker, aSil, eSil, ctxH, ctxBuilt);
                    if (c == null) continue;
                }
                extras.add(e);
                extraSils.add(eSil);
                extraContacts.add(c);
            }
            if (extras.isEmpty()) return;

            int fromIdx, toIdx;
            if (single) {

                int best = 0;
                for (int i = 1; i < extras.size(); i++) {
                    boolean closer = atk.dire == 1
                            ? extras.get(i).pos < extras.get(best).pos
                            : extras.get(i).pos > extras.get(best).pos;
                    if (closer) best = i;
                }
                fromIdx = best;
                toIdx = best + 1;
            } else {
                fromIdx = 0;
                toIdx = extras.size();
            }
            for (int i = fromIdx; i < toIdx; i++) {
                AbEntity e = extras.get(i);
                capt.add(e);
                if (!ctxBuilt[0]) {
                    ctxH[0] = buildSwingContext(attacker, aSil);
                    ctxBuilt[0] = true;
                }
                registerImpact(atk, attacker, e, extraSils.get(i), extraContacts.get(i), ctxH[0]);
            }
            long now = System.currentTimeMillis();
            if (now - lastIncludeLog > 500L) {
                Logger.log("PhysCol contact-include: +" + (toIdx - fromIdx)
                        + " victim(s) for " + attacker.getClass().getSimpleName()
                        + (single ? " [single-target reselect]" : " [area]"));
                lastIncludeLog = now;
            }
        } catch (Throwable t) {
            Logger.err("PhysCol contact-include failed", t);
        }
    }

    private static boolean ctargetable(Entity e, AttackAb atk, Entity attacker) {
        try {
            java.lang.reflect.Method m = BCUFields.method(e.getClass(), "ctargetable",
                    ArrayList.class, Entity.class, boolean.class);
            Object r = m.invoke(e, atk.trait, attacker, true);
            return r instanceof Boolean && ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void registerImpact(AttackAb atk, Entity attacker, AbEntity victim,
                                       SpriteBounds.Silhouette vSil, Contact contact,
                                       SwingContext swingCtx) {
        try {
            float vPos = BCUFields.getFloat(victim, "pos");
            float aPos = BCUFields.getFloat(attacker, "pos");
            float dirX = Math.signum(vPos - aPos);
            if (dirX == 0f) {

                try { dirX = BCUFields.getInt(attacker, "dire"); } catch (Throwable ignored) {}
                if (dirX == 0f) dirX = 1f;
            }

            float upFactor = 0.5f;
            if (contact != null && vSil != null) {
                float centerY = (vSil.box.y0 + vSil.box.y1) * 0.5f;
                float halfH = Math.max(1f, (vSil.box.y1 - vSil.box.y0) * 0.5f);

                float f = (contact.y - centerY) / halfH;
                if (f < -1f) f = -1f;
                if (f > 1f) f = 1f;
                upFactor = 0.5f + 0.5f * f;
            }

            float overkill = 0f;
            try {
                Object maxH = BCUFields.get(victim, "maxH");
                if (maxH instanceof Number && ((Number) maxH).floatValue() > 0f) {
                    overkill = Math.max(0f, atk.atk / ((Number) maxH).floatValue());
                }
            } catch (Throwable ignored) {}

            float[] swing = contactSwing(swingCtx, contact);
            boolean contactSwing = swing != null;
            if (swing == null) swing = legacySwing(swingCtx);
            float sx = swing == null ? 0f : swing[0];
            float sy = swing == null ? 0f : swing[1];

            float vArea = Math.max(1f, (vSil.box.x1 - vSil.box.x0) * (vSil.box.y1 - vSil.box.y0));
            float victimNorm = clampf((float) Math.sqrt(vArea) / VICTIM_SIZE_REF, 0.4f, 3.0f);
            float strikerNorm = 1f;
            if (contact != null) {
                strikerNorm = clampf((float) Math.sqrt(Math.max(1f, contact.area)) / STRIKER_SIZE_REF,
                        0.6f, 1.8f);
            }
            float massMult = clampf(strikerNorm / victimNorm, MASS_MIN, MASS_MAX);

            Impact im = new Impact(atk, attacker, dirX, upFactor, sx, sy, overkill, massMult);
            IMPACTS.put(victim, im);
            long now = System.currentTimeMillis();
            if (now - lastImpactLog > 500L) {
                Logger.log("PhysCol impact registered: victim=" + victim.getClass().getSimpleName()
                        + " dirX=" + (int) dirX + " upFactor=" + String.format("%.2f", upFactor)
                        + " swing=(" + Math.round(sx) + "," + Math.round(sy) + ")"
                        + (contactSwing ? " [contact-point]" : " [centroid]")
                        + " mass=" + String.format("%.2f", massMult));
                lastImpactLog = now;
            }

            if (CollisionDebug.OVERLAY) {
                float cx = contact != null ? contact.x
                        : (vSil.box.x0 + vSil.box.x1) * 0.5f;
                float cy = contact != null ? contact.y
                        : (vSil.box.y0 + vSil.box.y1) * 0.5f;
                float[] v = DeathLaunchFeature.computeVelocity(im, im.overkill);
                int layer = 0;
                try { layer = manualcontrol.reflect.EntityAccess.getLayer(victim); } catch (Throwable ignored) {}
                CollisionDebug.recordImpact(cx, cy, v[0], v[1], layer);
            }
        } catch (Throwable ignored) {

        }
    }

    private static final class SwingContext {
        final List<List<MeasuringGraphics.PartQuad>> frames =
                new ArrayList<List<MeasuringGraphics.PartQuad>>();
        final List<List<MeasuringGraphics.PartQuad>> future =
                new ArrayList<List<MeasuringGraphics.PartQuad>>();
    }

    private static SwingContext buildSwingContext(Entity attacker, SpriteBounds.Silhouette cur) {
        SwingContext ctx = new SwingContext();
        ctx.frames.add(cur.quads);
        try {
            Object am = BCUFields.get(attacker, "anim");
            if (am == null) return ctx;
            Object anim = BCUFields.get(am, "anim");
            if (anim == null) return ctx;
            float f = BCUFields.getFloat(anim, "f");
            java.lang.reflect.Method setTime =
                    BCUFields.method(anim.getClass(), "setTime", float.class);
            try {
                for (int k = 1; k <= SWING_WINDOW && f - k >= 0f; k++) {
                    setTime.invoke(anim, f - k);
                    SpriteBounds.Silhouette s = SpriteBounds.silhouetteOf(attacker);
                    if (s == null || s.quads.isEmpty()) break;
                    ctx.frames.add(s.quads);
                }
                for (int k = 1; k <= CONTACT_WINDOW_FWD; k++) {
                    setTime.invoke(anim, f + k);
                    SpriteBounds.Silhouette s = SpriteBounds.silhouetteOf(attacker);
                    if (s == null || s.quads.isEmpty()) break;
                    ctx.future.add(s.quads);
                }
            } finally {
                setTime.invoke(anim, f);
            }
        } catch (Throwable ignored) {}
        return ctx;
    }

    private static long lastTemporalLog = 0L;

    private static Contact contactWindowed(Entity attacker, SpriteBounds.Silhouette aSil,
                                           SpriteBounds.Silhouette eSil,
                                           SwingContext[] ctxH, boolean[] ctxBuilt) {
        Contact c = silhouettesTouch(aSil.quads, eSil.quads);
        if (c != null) return c;
        if (!ctxBuilt[0]) {
            ctxH[0] = buildSwingContext(attacker, aSil);
            ctxBuilt[0] = true;
        }
        SwingContext ctx = ctxH[0];
        if (ctx == null) return null;
        int nb = ctx.frames.size() - 1;
        int nf = ctx.future.size();
        for (int k = 1; k <= Math.max(nb, nf); k++) {
            if (k <= nf) {
                Contact cf = silhouettesTouch(ctx.future.get(k - 1), eSil.quads);
                if (cf != null) return temporalHit(cf, k);
            }
            if (k <= nb) {
                Contact cb = silhouettesTouch(ctx.frames.get(k), eSil.quads);
                if (cb != null) return temporalHit(cb, -k);
            }
        }
        return null;
    }

    private static Contact temporalHit(Contact c, int frameOffset) {
        long now = System.currentTimeMillis();
        if (now - lastTemporalLog > 500L) {
            Logger.log("PhysCol temporal contact: hit found at frame offset "
                    + (frameOffset > 0 ? "+" : "") + frameOffset);
            lastTemporalLog = now;
        }
        return new Contact(c.x, c.y, -1, c.ua, c.va, c.area);
    }

    private static float[] contactSwing(SwingContext ctx, Contact c) {
        if (ctx == null || c == null || ctx.frames.size() < 2) return null;
        List<MeasuringGraphics.PartQuad> cur = ctx.frames.get(0);
        if (c.aIndex < 0 || c.aIndex >= cur.size()) return null;
        MeasuringGraphics.PartQuad strike = cur.get(c.aIndex);
        ArrayList<float[]> pts = new ArrayList<float[]>();
        pts.add(mapUV(strike.pts, c.ua, c.va));
        for (int k = 1; k < ctx.frames.size(); k++) {
            MeasuringGraphics.PartQuad m = findMatch(ctx.frames.get(k), strike);
            if (m == null) break;
            pts.add(mapUV(m.pts, c.ua, c.va));
            strike = m;
        }
        if (pts.size() < 2) return null;
        float best = 0f, bx = 0f, by = 0f;
        for (int k = 0; k + 1 < pts.size(); k++) {
            float dx = pts.get(k)[0] - pts.get(k + 1)[0];
            float dy = pts.get(k)[1] - pts.get(k + 1)[1];
            float d2 = dx * dx + dy * dy;
            if (d2 > best) {
                best = d2;
                bx = dx;
                by = dy;
            }
        }
        return best >= 1f ? new float[]{bx, by} : null;
    }

    private static float[] legacySwing(SwingContext ctx) {
        if (ctx == null || ctx.frames.size() < 2) return null;
        float best = 0f, bx = 0f, by = 0f;
        for (int k = 0; k + 1 < ctx.frames.size(); k++) {
            List<MeasuringGraphics.PartQuad> cur = ctx.frames.get(k);
            List<MeasuringGraphics.PartQuad> prev = ctx.frames.get(k + 1);
            int n = Math.min(cur.size(), prev.size());
            for (int i = 0; i < n; i++) {
                MeasuringGraphics.PartQuad qc = cur.get(i);
                MeasuringGraphics.PartQuad qp = prev.get(i);
                if (qc.image != qp.image) continue;
                float dx = centroidX(qc.pts) - centroidX(qp.pts);
                float dy = centroidY(qc.pts) - centroidY(qp.pts);
                float d2 = dx * dx + dy * dy;
                if (d2 > best) {
                    best = d2;
                    bx = dx;
                    by = dy;
                }
            }
        }
        return best >= 1f ? new float[]{bx, by} : null;
    }

    private static float[] mapUV(float[] q, float u, float v) {
        float x = q[0] + u * (q[2] - q[0]) + v * (q[6] - q[0]);
        float y = q[1] + u * (q[3] - q[1]) + v * (q[7] - q[1]);
        return new float[]{x, y};
    }

    private static MeasuringGraphics.PartQuad findMatch(
            List<MeasuringGraphics.PartQuad> list, MeasuringGraphics.PartQuad ref) {
        float rcx = centroidX(ref.pts), rcy = centroidY(ref.pts);
        MeasuringGraphics.PartQuad best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            MeasuringGraphics.PartQuad q = list.get(i);
            if (q.image != ref.image) continue;
            float dx = centroidX(q.pts) - rcx;
            float dy = centroidY(q.pts) - rcy;
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = q;
            }
        }
        return best;
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float centroidX(float[] p) {
        return (p[0] + p[2] + p[4] + p[6]) * 0.25f;
    }

    private static float centroidY(float[] p) {
        return (p[1] + p[3] + p[5] + p[7]) * 0.25f;
    }

    private static final class Contact {
        final float x, y;
        final int aIndex;
        final float ua, va;
        final float area;
        Contact(float x, float y, int aIndex, float ua, float va, float area) {
            this.x = x;
            this.y = y;
            this.aIndex = aIndex;
            this.ua = ua;
            this.va = va;
            this.area = area;
        }
    }

    private static Contact silhouettesTouch(List<MeasuringGraphics.PartQuad> a,
                                            List<MeasuringGraphics.PartQuad> b) {
        for (int i = 0; i < a.size(); i++) {
            MeasuringGraphics.PartQuad qa = a.get(i);
            for (int j = 0; j < b.size(); j++) {
                MeasuringGraphics.PartQuad qb = b.get(j);
                if (!quadsIntersect(qa.pts, qb.pts)) continue;
                if (!PIXEL_MASK) return contactAt(quadOverlapCenter(qa.pts, qb.pts), i, qa);
                AlphaBounds.Mask ma = AlphaBounds.mask(qa.image);
                AlphaBounds.Mask mb = AlphaBounds.mask(qb.image);

                if (ma == null || mb == null) return contactAt(quadOverlapCenter(qa.pts, qb.pts), i, qa);
                float[] hit = masksTouch(qa, ma, qb, mb);
                if (hit != null) {
                    return new Contact(hit[0], hit[1], i, hit[2], hit[3], quadArea(qa.pts));
                }
            }
        }
        return null;
    }

    static float[] silhouetteContactPoint(List<MeasuringGraphics.PartQuad> a,
                                          List<MeasuringGraphics.PartQuad> b) {
        Contact c = silhouettesTouch(a, b);
        return c == null ? null : new float[]{c.x, c.y};
    }

    static float alphaOverlapExtent(List<MeasuringGraphics.PartQuad> a,
                                    List<MeasuringGraphics.PartQuad> b, boolean axisX) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < a.size(); i++) {
            MeasuringGraphics.PartQuad qa = a.get(i);
            for (int j = 0; j < b.size(); j++) {
                MeasuringGraphics.PartQuad qb = b.get(j);
                if (!quadsIntersect(qa.pts, qb.pts)) continue;
                float ix0 = Math.max(min4x(qa.pts), min4x(qb.pts));
                float ix1 = Math.min(max4x(qa.pts), max4x(qb.pts));
                float iy0 = Math.max(min4y(qa.pts), min4y(qb.pts));
                float iy1 = Math.min(max4y(qa.pts), max4y(qb.pts));
                if (ix0 >= ix1 || iy0 >= iy1) continue;

                AlphaBounds.Mask ma = AlphaBounds.mask(qa.image);
                AlphaBounds.Mask mb = AlphaBounds.mask(qb.image);
                float[] fa = inverseFrame(qa.pts);
                float[] fb = inverseFrame(qb.pts);
                if (ma == null || mb == null || fa == null || fb == null) {

                    lo = Math.min(lo, axisX ? ix0 : iy0);
                    hi = Math.max(hi, axisX ? ix1 : iy1);
                    continue;
                }
                int n = Math.max(4, MASK_SAMPLES);
                float dx = (ix1 - ix0) / n;
                float dy = (iy1 - iy0) / n;
                for (int r = 0; r < n; r++) {
                    float y = iy0 + (r + 0.5f) * dy;
                    for (int cIdx = 0; cIdx < n; cIdx++) {
                        float x = ix0 + (cIdx + 0.5f) * dx;
                        float rxa = x - fa[0], rya = y - fa[1];
                        float ua = rxa * fa[2] + rya * fa[3];
                        if (ua < 0f || ua >= 1f) continue;
                        float va = rxa * fa[4] + rya * fa[5];
                        if (va < 0f || va >= 1f) continue;
                        if (!ma.opaque(ua, va)) continue;
                        float rxb = x - fb[0], ryb = y - fb[1];
                        float ub = rxb * fb[2] + ryb * fb[3];
                        if (ub < 0f || ub >= 1f) continue;
                        float vb = rxb * fb[4] + ryb * fb[5];
                        if (vb < 0f || vb >= 1f) continue;
                        if (!mb.opaque(ub, vb)) continue;
                        float v = axisX ? x : y;
                        if (v < lo) lo = v;
                        if (v > hi) hi = v;
                    }
                }
            }
        }
        return hi >= lo ? Math.max(0.01f, hi - lo) : 0f;
    }

    private static Contact contactAt(float[] pt, int aIndex, MeasuringGraphics.PartQuad qa) {
        float ua = 0.5f, va = 0.5f;
        float[] f = inverseFrame(qa.pts);
        if (f != null) {
            float rx = pt[0] - f[0], ry = pt[1] - f[1];
            ua = clampf(rx * f[2] + ry * f[3], 0f, 0.999f);
            va = clampf(rx * f[4] + ry * f[5], 0f, 0.999f);
        }
        return new Contact(pt[0], pt[1], aIndex, ua, va, quadArea(qa.pts));
    }

    private static float quadArea(float[] p) {
        float ux = p[2] - p[0], uy = p[3] - p[1];
        float vx = p[6] - p[0], vy = p[7] - p[1];
        return Math.abs(ux * vy - uy * vx);
    }

    private static float[] quadOverlapCenter(float[] a, float[] b) {
        float x0 = Math.max(min4x(a), min4x(b));
        float x1 = Math.min(max4x(a), max4x(b));
        float y0 = Math.max(min4y(a), min4y(b));
        float y1 = Math.min(max4y(a), max4y(b));
        return new float[]{(x0 + x1) * 0.5f, (y0 + y1) * 0.5f};
    }

    private static float[] masksTouch(MeasuringGraphics.PartQuad qa, AlphaBounds.Mask ma,
                                      MeasuringGraphics.PartQuad qb, AlphaBounds.Mask mb) {
        float ax0 = min4x(qa.pts), ax1 = max4x(qa.pts);
        float ay0 = min4y(qa.pts), ay1 = max4y(qa.pts);
        float bx0 = min4x(qb.pts), bx1 = max4x(qb.pts);
        float by0 = min4y(qb.pts), by1 = max4y(qb.pts);
        float ix0 = Math.max(ax0, bx0), ix1 = Math.min(ax1, bx1);
        float iy0 = Math.max(ay0, by0), iy1 = Math.min(ay1, by1);
        if (ix0 >= ix1 || iy0 >= iy1) return null;

        float[] fa = inverseFrame(qa.pts);
        float[] fb = inverseFrame(qb.pts);
        if (fa == null || fb == null) {
            return new float[]{(ix0 + ix1) * 0.5f, (iy0 + iy1) * 0.5f, 0.5f, 0.5f};
        }

        int n = Math.max(4, MASK_SAMPLES);
        float dx = (ix1 - ix0) / n;
        float dy = (iy1 - iy0) / n;
        for (int j = 0; j < n; j++) {
            float y = iy0 + (j + 0.5f) * dy;
            for (int i = 0; i < n; i++) {
                float x = ix0 + (i + 0.5f) * dx;
                float rxa = x - fa[0], rya = y - fa[1];
                float ua = rxa * fa[2] + rya * fa[3];
                if (ua < 0f || ua >= 1f) continue;
                float va = rxa * fa[4] + rya * fa[5];
                if (va < 0f || va >= 1f) continue;
                if (!ma.opaque(ua, va)) continue;
                float rxb = x - fb[0], ryb = y - fb[1];
                float ub = rxb * fb[2] + ryb * fb[3];
                if (ub < 0f || ub >= 1f) continue;
                float vb = rxb * fb[4] + ryb * fb[5];
                if (vb < 0f || vb >= 1f) continue;
                if (mb.opaque(ub, vb)) return new float[]{x, y, ua, va};
            }
        }
        return null;
    }

    private static float[] inverseFrame(float[] p) {
        float ox = p[0], oy = p[1];
        float ux = p[2] - ox, uy = p[3] - oy;
        float vx = p[6] - ox, vy = p[7] - oy;
        float det = ux * vy - uy * vx;
        if (Math.abs(det) < 1e-6f) return null;
        return new float[]{ox, oy, vy / det, -vx / det, -uy / det, ux / det};
    }

    private static float min4x(float[] p) {
        return Math.min(Math.min(p[0], p[2]), Math.min(p[4], p[6]));
    }
    private static float max4x(float[] p) {
        return Math.max(Math.max(p[0], p[2]), Math.max(p[4], p[6]));
    }
    private static float min4y(float[] p) {
        return Math.min(Math.min(p[1], p[3]), Math.min(p[5], p[7]));
    }
    private static float max4y(float[] p) {
        return Math.max(Math.max(p[1], p[3]), Math.max(p[5], p[7]));
    }

    private static boolean quadsIntersect(float[] a, float[] b) {
        return !separated(a, b) && !separated(b, a);
    }

    private static boolean separated(float[] p, float[] q) {
        for (int i = 0; i < 4; i++) {
            float ex = p[(i * 2 + 2) % 8] - p[i * 2];
            float ey = p[(i * 2 + 3) % 8] - p[i * 2 + 1];
            float ax = -ey, ay = ex;
            if (ax * ax + ay * ay < 1e-9f) continue;
            float minP = Float.MAX_VALUE, maxP = -Float.MAX_VALUE;
            float minQ = Float.MAX_VALUE, maxQ = -Float.MAX_VALUE;
            for (int j = 0; j < 8; j += 2) {
                float dp = ax * p[j] + ay * p[j + 1];
                if (dp < minP) minP = dp;
                if (dp > maxP) maxP = dp;
                float dq = ax * q[j] + ay * q[j + 1];
                if (dq < minQ) minQ = dq;
                if (dq > maxQ) maxQ = dq;
            }
            if (maxP < minQ || maxQ < minP) return true;
        }
        return false;
    }

    private static boolean meleeEligible(Entity attacker, SpriteBounds.WorldBox box,
                                         float sta, float end) {
        if (PHYSICAL_ALL) return true;
        if (box == null) return false;
        float pos = BCUFields.getFloat(attacker, "pos");
        float attackFar = Math.abs(sta - pos) > Math.abs(end - pos) ? sta : end;

        float dirAtk = Math.signum(attackFar - pos);
        if (dirAtk == 0f) return true;
        float spriteFarInDir = dirAtk > 0f ? box.x1 : box.x0;

        float reachBeyond = dirAtk * (attackFar - spriteFarInDir);
        boolean ok = reachBeyond <= MELEE_MARGIN;
        long now = System.currentTimeMillis();
        if (now - lastEligibleLog > 500L) {
            Logger.log("PhysCol eligible=" + ok + " attacker=" + attacker.getClass().getSimpleName()
                    + " reachBeyond=" + Math.round(reachBeyond) + " (margin " + Math.round(MELEE_MARGIN) + ")");
            lastEligibleLog = now;
        }
        return ok;
    }

    private static boolean invokeBool(Object obj, String method) {
        try {
            Object r = BCUFields.invoke(obj, method);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBase(Object e) {

        if (e instanceof AbEntity) return ((AbEntity) e).isBase();
        return invokeBool(e, "isBase");
    }

    private static boolean keepTerrainTraversalCapture(Entity attacker, AbEntity target) {
        if (attacker == null || target == null) return false;
        boolean retained = manualcontrol.custommap.CustomMapBattleRuntime
                .hasTerrainSwimCombatVolume(attacker)
                || manualcontrol.custommap.CustomMapBattleRuntime
                .hasTerrainSwimCombatVolume(target);
        if (!retained) return false;
        long now = System.currentTimeMillis();
        if (now - lastTerrainAirborneLog > 500L) {
            Logger.log("PhysCol terrain-traversal capture retained: attacker="
                    + attacker.getClass().getSimpleName() + " target="
                    + target.getClass().getSimpleName());
            lastTerrainAirborneLog = now;
        }
        return true;
    }

    private static void setFloat(Object obj, String field, float value) {
        try {
            BCUFields.field(obj.getClass(), field).setFloat(obj, value);
        } catch (Throwable t) {
            Logger.err("PhysicalCollision: failed to set " + field, t);
        }
    }
}
