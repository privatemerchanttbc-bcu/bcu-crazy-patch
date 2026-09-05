package manualcontrol.adventure;

import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.entity.AbEntity;
import common.battle.entity.EEnemy;
import common.battle.entity.Entity;
import manualcontrol.reflect.BCUFields;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class AdventureCombat {

    private static final float MIN_REACH = 140f;

    private AdventureCombat() {}

    private static final java.util.Random RNG = new java.util.Random();

    private static final Set<Object> ORIENTED_CONJURE_ATTACKS =
            Collections.synchronizedSet(Collections.newSetFromMap(
                    new WeakHashMap<Object, Boolean>()));
    private static final Set<Object> SCALED_CUSTOM_ATTACKS =
            Collections.synchronizedSet(Collections.newSetFromMap(
                    new WeakHashMap<Object, Boolean>()));

    static volatile long lastPlayerAtk;
    private static volatile boolean deferredDamageErrorLogged;

    static void resetPlayerDamage() { lastPlayerAtk = 0L; }

    static boolean isDirectAttack(AttackAb attack) {
        return attack != null && "AttackSimple".equals(attack.getClass().getSimpleName());
    }

    public static void applyAdvancedAttackModifiers(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AdventureBattle battle = AdventureBridge.activeBattle();
        if (battle == null) return;
        AttackAb attack = (AttackAb) attackObj;
        battle.advancedCores.modifyEnemyAttack(attack);
        battle.architect.onEnemyAttack(attack);
    }

    public static int conjureAttackDirection(int original, Object attackModel) {
        try {
            Object attacker = BCUFields.get(attackModel, "e");
            if (AdventureBridge.isConjuredSpirit(attacker)) {
                return AdventureBridge.isAdventureFlipped(attacker) ? 1 : -1;
            }
        } catch (Throwable ignored) {}
        return original;
    }

    public static void orientConjureAttack(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        Entity attacker = attack.attacker;
        if (attacker == null || !AdventureBridge.isConjuredSpirit(attacker)
                || !AdventureBridge.isAdventureFlipped(attacker)
                || !ORIENTED_CONJURE_ATTACKS.add(attack)) {
            return;
        }
        float center = attacker.pos;
        float oldSta = attack.sta;
        float oldEnd = attack.end;
        attack.sta = 2f * center - oldSta;
        attack.end = 2f * center - oldEnd;

        attack.dire = -attack.dire;
    }

    public static void scaleCustomMapAttackRange(Object attackObj) {
        if (!(attackObj instanceof AttackAb) || !SCALED_CUSTOM_ATTACKS.add(attackObj)) return;
        boolean adventure = manualcontrol.custommap.CustomMapRuntime.activeVariant(
                manualcontrol.custommap.CustomMapDocument.MapMode.ADVENTURE) != null;
        boolean normalBattle =
                manualcontrol.custommap.CustomMapRuntime.activeBattleTerrain() != null;
        if (!adventure && !normalBattle) return;
        AttackAb attack = (AttackAb) attackObj;
        Entity attacker = attack.attacker;
        if (attacker == null || (adventure && !AdventureBridge.isAdventureEntity(attacker))
                || (normalBattle && !manualcontrol.custommap.CustomMapRuntime
                        .isNormalBattleStage(attacker.basis))) return;
        float scale = customTerrainAttackScale(adventure, normalBattle,
                manualcontrol.custommap.CustomMapRuntime.worldScale());
        if (Math.abs(scale - 1f) < 0.0001f) return;
        float center = attacker.pos;
        attack.sta = center + (attack.sta - center) * scale;
        attack.end = center + (attack.end - center) * scale;
    }

    public static float customTerrainAttackScale(
            boolean adventure, boolean normalBattle, float worldScale) {
        if (adventure) return Math.max(0.01f, worldScale);
        return 1f;
    }

    static long currentPlayerDamage(common.battle.entity.EUnit player) {
        long last = lastPlayerAtk;
        if (last > 0L) return last;
        if (player == null) return 1L;
        try {
            double mult = AdventureBattle.DAMAGE_MULT * AdventureRuntime.cores().dmgMult();
            long v = Math.round(player.getAtk() * mult);
            return Math.max(1L, v);
        } catch (Throwable ignored) {
            return 1L;
        }
    }

    static boolean queueEffectDamage(Entity target, long amount) {
        if (target == null || amount <= 0L || target.dead || target.health <= 0L) return false;
        try {
            long pending = Math.max(0L, BCUFields.getLong(target, "damage"));
            long next = pending > Long.MAX_VALUE - amount ? Long.MAX_VALUE : pending + amount;

            if (next == target.health && next < Long.MAX_VALUE) next++;
            BCUFields.field(target.getClass(), "damage").setLong(target, next);
            return true;
        } catch (Throwable failure) {
            if (!deferredDamageErrorLogged) {
                deferredDamageErrorLogged = true;
                manualcontrol.Logger.err(
                        "Adventure: deferred effect damage unavailable; refusing unsafe health cut",
                        failure);
            }
            return false;
        }
    }

    static boolean ensureLethalTransition(Entity entity) {
        if (entity == null || entity.dead || entity.health > 0L
                || AdventureController.readKbTime(entity) != 0
                || AdventureController.isZombieReviveActive(entity)
                || AdventureController.hasCorpse(entity)) {
            return false;
        }
        try {
            BCUFields.method(entity.getClass(), "preKill").invoke(entity);
            manualcontrol.Logger.log("Adventure: repaired zero-HP enemy death transition");
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    static boolean overlapsHorizontal(Entity target, float x0, float x1) {
        if (target == null) return false;
        float lo = Math.min(x0, x1);
        float hi = Math.max(x0, x1);
        try {
            manualcontrol.crazy.collision.SpriteBounds.WorldBox box =
                    manualcontrol.crazy.collision.SpriteBounds.of(target);
            if (box != null) return box.x1 >= lo && box.x0 <= hi;
        } catch (Throwable ignored) {}
        float half = bodyHalfWidth(target);
        return target.pos + half >= lo && target.pos - half <= hi;
    }

    @SuppressWarnings("unchecked")
    public static void stripBaseTargets(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        Entity attacker = attack.attacker;
        if (attacker == null) return;
        try {
            StageBasis sb = attacker.basis;
            if (sb == null || !AdventureBridge.isActiveStage(sb)) return;
            List<AbEntity> capt = (List<AbEntity>) BCUFields.get(attack, "capt");
            if (capt == null || capt.isEmpty()) return;
            capt.remove(sb.ebase);
            capt.remove(sb.ubase);
        } catch (Throwable ignored) {}
    }

    public static void applyPlayerDamage(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        Entity attacker = attack.attacker;
        if (!AdventureBridge.isPlayerEntity(attacker)) return;
        manualcontrol.adventure.AdventureCoreState cores = AdventureRuntime.cores();
        double mult = AdventureBattle.DAMAGE_MULT * cores.dmgMult();

        if (cores.critChance() > 0f && RNG.nextFloat() < cores.critChance()) {
            mult *= 2.0;
        }

        try {
            if (cores.berserkBonus() > 0f
                    && attacker.maxH > 0 && attacker.health * 10L < attacker.maxH * 3L) {
                mult *= 1.0 + cores.berserkBonus();
            }
        } catch (Throwable ignored) {}

        if (cores.consumeStaticCharge()) mult *= 4.0;
        long v = Math.round(attack.atk * mult);
        if (v > Integer.MAX_VALUE) v = Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) v = Integer.MIN_VALUE;
        attack.atk = (int) v;
        lastPlayerAtk = Math.max(1, v);
    }

    @SuppressWarnings("unchecked")
    public static void injectMeleeTargets(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        Entity attacker = attack.attacker;
        if (attacker == null || !AdventureBridge.isPlayerEntity(attacker)) return;
        try {
            StageBasis sb = attacker.basis;
            if (sb == null || sb.le == null) return;
            List<AbEntity> capt = (List<AbEntity>) BCUFields.get(attack, "capt");
            if (capt == null) return;

            float px = attacker.pos;
            float left = Math.min(attack.sta, attack.end);
            float right = Math.max(attack.sta, attack.end);
            float ilo, ihi;
            if (AdventureBridge.isAdventureFlipped(attacker)) {
                ilo = 2f * px - right;
                ihi = 2f * px - left;
            } else {
                ilo = left;
                ihi = right;
            }
            if (ihi - ilo < 2f * MIN_REACH) {
                float c = (ilo + ihi) * 0.5f;
                ilo = c - MIN_REACH;
                ihi = c + MIN_REACH;
            }

            float reach = AdventureRuntime.cores().reachMult();
            if (reach > 1f) {
                float c = (ilo + ihi) * 0.5f, half = (ihi - ilo) * 0.5f * reach;
                ilo = c - half;
                ihi = c + half;
            }
            ilo -= PAD;
            ihi += PAD;

            manualcontrol.crazy.collision.SpriteBounds.WorldBox aBox =
                    manualcontrol.crazy.collision.PhysicalCollision.ENABLED
                            ? manualcontrol.crazy.collision.SpriteBounds.of(attacker) : null;

            if (aBox != null && reach > 1f) {
                float cx = (aBox.x0 + aBox.x1) * 0.5f, hw = (aBox.x1 - aBox.x0) * 0.5f * reach;
                aBox = new manualcontrol.crazy.collision.SpriteBounds.WorldBox(
                        cx - hw, aBox.y0, cx + hw, aBox.y1);
            }

            for (Entity e : sb.le) {
                if (!(e instanceof EEnemy)) continue;
                if (e.dead || e.health <= 0L) continue;
                if (AdventureController.isRevivingCorpse(e)) continue;
                boolean hit;
                manualcontrol.crazy.collision.SpriteBounds.WorldBox eb =
                        aBox != null ? manualcontrol.crazy.collision.SpriteBounds.of(e) : null;
                if (aBox != null && eb != null) {
                    hit = aBox.overlaps(eb);
                } else {

                    float half = bodyHalfWidth(e);
                    hit = !(e.pos + half < ilo || e.pos - half > ihi);
                }
                if (hit && !isPropagatingAttack(attack)
                        && manualcontrol.custommap.CustomMapRuntime.directLineBlocked(
                        attacker.pos, attacker.currentLayer,
                        e.pos, e.currentLayer)) hit = false;
                if (hit && !capt.contains(e)) capt.add(e);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static void injectPlayerTarget(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        Entity attacker = attack.attacker;
        if (attacker == null
                || !AdventureBridge.isAdventureEntity(attacker)
                || AdventureBridge.isPlayerEntity(attacker)) return;
        try {
            AdventureBattle b = AdventureBridge.activeBattle();
            if (b == null) return;
            common.battle.entity.EUnit player = b.player();
            if (player == null || player.dead || player.health <= 0L
                    || b.isCustomHazardInvulnerable()) return;
            if (b.architect.enemyTargetsConstruct(attacker)
                    || b.architect.blocksAttack(attacker.pos, player.pos)) return;
            List<AbEntity> capt = (List<AbEntity>) BCUFields.get(attack, "capt");
            if (capt == null || capt.contains(player)) return;

            float ex = attacker.pos;
            float left = Math.min(attack.sta, attack.end);
            float right = Math.max(attack.sta, attack.end);
            float ilo, ihi;
            if (AdventureBridge.isAdventureFlipped(attacker)) {
                ilo = 2f * ex - right;
                ihi = 2f * ex - left;
            } else {
                ilo = left;
                ihi = right;
            }
            if (ihi - ilo < 2f * MIN_REACH) {
                float c = (ilo + ihi) * 0.5f;
                ilo = c - MIN_REACH;
                ihi = c + MIN_REACH;
            }
            ilo -= PAD;
            ihi += PAD;

            boolean hit;
            manualcontrol.crazy.collision.SpriteBounds.WorldBox aBox = null, pb = null;
            if (manualcontrol.crazy.collision.PhysicalCollision.ENABLED) {
                aBox = manualcontrol.crazy.collision.SpriteBounds.of(attacker);
                pb = manualcontrol.crazy.collision.SpriteBounds.of(player);
            }
            if (aBox != null && pb != null) {
                hit = aBox.overlaps(pb);
            } else {
                float half = bodyHalfWidth(player);
                hit = !(player.pos + half < ilo || player.pos - half > ihi);
            }
            if (hit && !isPropagatingAttack(attack)
                    && manualcontrol.custommap.CustomMapRuntime.directLineBlocked(
                    attacker.pos, attacker.currentLayer,
                    player.pos, player.currentLayer)) hit = false;
            if (hit) capt.add(player);
        } catch (Throwable ignored) {}
    }

    private static boolean isPropagatingAttack(AttackAb attack) {
        if (attack == null) return false;
        String name = attack.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("wave") || name.contains("surge")
                || name.contains("volcano") || name.contains("explosion");
    }

    private static final float PAD = 12f;

    private static float bodyHalfWidth(Object e) {
        try {
            Object data = BCUFields.get(e, "data");
            Object w = BCUFields.invoke(data, "getWidth");
            if (w instanceof Number) return Math.max(((Number) w).floatValue() * 0.5f, 60f);
        } catch (Throwable ignored) {}
        return 100f;
    }
}
