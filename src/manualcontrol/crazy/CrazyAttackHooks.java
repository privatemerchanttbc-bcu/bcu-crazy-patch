package manualcontrol.crazy;

import common.battle.attack.AttackAb;
import common.battle.entity.AbEntity;
import common.battle.entity.Entity;
import manualcontrol.crazy.unit.BoosterSlotFeature;
import manualcontrol.crazy.unit.BossItemFeature;
import manualcontrol.crazy.unit.CatCoinFeature;
import manualcontrol.crazy.unit.DiceSlotFeature;
import manualcontrol.crazy.unit.EggPetFeature;
import manualcontrol.crazy.unit.GrowingUnits;
import manualcontrol.crazy.unit.StackUnitFeature;
import manualcontrol.crazy.unit.TheRitualFeature;

public final class CrazyAttackHooks {
    private CrazyAttackHooks() {}

    private static long lastDmgHookLog = 0L;

    public static void onAttackExcuse(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        manualcontrol.crazy.unit.SpecialSummonFeature.onMiss(attack);

        manualcontrol.adventure.AdventureCombat.applyPlayerDamage(attack);
        manualcontrol.adventure.AdventureCombat.applyAdvancedAttackModifiers(attack);
        BossItemFeature.applyBossNativeCannonAttack(attack);
        Entity attacker = attack.attacker;
        if (attacker == null) return;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(attacker.basis);
        if (rt == null) return;
        if (rt.config.growingUnits) {
            CrazyRuntime.safe("growing-damage", rt, new Runnable() {
                @Override
                public void run() {
                    GrowingUnits.applyDamageMultiplier(rt, attack);
                }
            });
        }
        if (rt.config.bossItem) {
            CrazyRuntime.safe("boss-item-damage", rt, new Runnable() {
                @Override
                public void run() {
                    BossItemFeature.applyDamageMultiplier(rt, attack);
                }
            });
        }
        if (rt.config.eggPet || EggPetFeature.hasActive(rt)) {
            CrazyRuntime.safe("egg-pet-damage", rt, new Runnable() {
                @Override
                public void run() {
                    EggPetFeature.applyDamageMultiplier(rt, attack);
                }
            });
        }
        if (rt.config.boosterSlot) {
            CrazyRuntime.safe("booster-slot-damage", rt, new Runnable() {
                @Override
                public void run() {
                    BoosterSlotFeature.applyDamageMultiplier(rt, attack);
                }
            });
        }
        if (rt.config.theRitual || TheRitualFeature.hasActive(rt)) {
            CrazyRuntime.safe("the-ritual-damage", rt, new Runnable() {
                @Override
                public void run() {
                    TheRitualFeature.applyDamageMultiplier(rt, attack);
                }
            });
        }
        if (rt.config.stackUnit || StackUnitFeature.hasActive(rt)) {
            CrazyRuntime.safe("stack-unit-damage", rt, new Runnable() {
                @Override
                public void run() {
                    StackUnitFeature.applyDamageMultiplier(rt, attack);
                }
            });
        }
    }

    public static void onInvokeLater(Object modelObj, Object attackObj, Object targetObj) {
        manualcontrol.crazy.unit.SummonAttachFeature.noteAttackFromAttack(modelObj, attackObj);
        manualcontrol.crazy.unit.SpecialSummonFeature.onHitOrKill(modelObj, attackObj, targetObj);
    }

    public static void onSetProc(Object modelObj, int ind) {
        manualcontrol.crazy.unit.SummonAttachFeature.noteAttackIndex(modelObj, ind);
    }

    public static boolean onSummonAttach(Object modelObj, Object procObj, Object entObj,
                                         Object acsObj, int resist) {
        return manualcontrol.crazy.unit.SummonAttachFeature.onSummon(
                modelObj, procObj, entObj, acsObj, resist);
    }

    public static void beforeAttackCapture(Object attackObj) {
        BossItemFeature.scaleAttackRange(attackObj);
        EggPetFeature.scaleAttackRange(attackObj);
        manualcontrol.adventure.AdventureCombat.orientConjureAttack(attackObj);
        manualcontrol.crazy.collision.PhysicalCollision.beforeCapture(attackObj);
        manualcontrol.adventure.AdventureCombat.scaleCustomMapAttackRange(attackObj);
    }

    public static int overrideAttackDirection(int original, Object attackModel) {
        return manualcontrol.adventure.AdventureCombat.conjureAttackDirection(
                original, attackModel);
    }

    public static void afterAttackCapture(Object attackObj) {

        manualcontrol.adventure.AdventureCombat.stripBaseTargets(attackObj);

        manualcontrol.adventure.AdventureCombat.injectMeleeTargets(attackObj);
        manualcontrol.adventure.AdventureCombat.injectPlayerTarget(attackObj);
        manualcontrol.crazy.collision.PhysicalCollision.afterCapture(attackObj);
    }

    public static boolean afterCheckTouch(boolean result, Object entity) {
        try {
            return manualcontrol.crazy.collision.PhysicalCollision.filterTouch(result, entity);
        } catch (Throwable t) {
            return result;
        }
    }

    public static long pendingDamage(Object entity) {
        try {
            return Math.max(0L, manualcontrol.reflect.BCUFields.getLong(entity, "damage"));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void afterEntityDamaged(boolean result, Object entity, Object attack,
                                          long healthBefore, long pendingBefore) {
        if (!(entity instanceof AbEntity) || !(attack instanceof AttackAb)) return;
        AbEntity ab = (AbEntity) entity;

        manualcontrol.adventure.AdventureBridge.onEntityDamaged(
                entity, attack, healthBefore, pendingBefore);

        try {
            manualcontrol.crazy.collision.SurgeJuggleFeature.onSurgeDamage(entity, attack);
        } catch (Throwable t) {
            manualcontrol.Logger.err("SurgeJuggle damage hook failed", t);
        }
        try {
            if (manualcontrol.crazy.collision.PhysicalCollision.ENABLED
                    && entity instanceof Entity
                    && !manualcontrol.crazy.collision.DeathLaunchFeature.isLaunching(entity)) {
                long pending = 0L;
                try {
                    pending = manualcontrol.reflect.BCUFields.getLong(entity, "damage");
                } catch (Throwable ignored) {}
                long now = System.currentTimeMillis();
                if (now - lastDmgHookLog > 1000L) {
                    manualcontrol.Logger.log("dmgHook: victim=" + entity.getClass().getSimpleName()
                            + " hp=" + ab.health + " pending=" + pending);
                    lastDmgHookLog = now;
                }
                if (ab.health - pending <= 0L
                        && !manualcontrol.crazy.collision.SurgeJuggleFeature.onLethal(entity)) {
                    manualcontrol.crazy.collision.PhysicalCollision.Impact im =
                            manualcontrol.crazy.collision.PhysicalCollision.consumeImpact(entity, attack);
                    manualcontrol.Logger.log("DeathLaunch lethal: victim="
                            + entity.getClass().getSimpleName()
                            + " hp=" + ab.health + " pending=" + pending
                            + " impact=" + (im != null ? "FOUND" : "none (attack not physical-registered)"));
                    if (im != null) {

                        float excessRatio = 0f;
                        try {
                            Object maxH = manualcontrol.reflect.BCUFields.get(entity, "maxH");
                            if (maxH instanceof Number && ((Number) maxH).floatValue() > 0f) {
                                excessRatio = Math.max(0f,
                                        (pending - ab.health) / ((Number) maxH).floatValue());
                            }
                        } catch (Throwable ignored) {}
                        manualcontrol.crazy.collision.DeathLaunchFeature.trigger(entity, im, excessRatio);
                    }
                }
            }
        } catch (Throwable t) {
            manualcontrol.Logger.err("DeathLaunch lethal-check failed", t);
        }
        CrazyRuntime.StageRuntime rt = runtimeFor(ab);
        if (rt == null) return;
        if (rt.config.bossItem) {
            CrazyRuntime.safe("boss-base-shield", rt, new Runnable() {
                @Override
                public void run() {
                    BossItemFeature.afterBaseDamaged(rt, ab, (AttackAb) attack, healthBefore);
                }
            });
        }
        if (rt.config.catCoin || CatCoinFeature.hasActive(rt)) {
            CrazyRuntime.safe("cat-coin-damage", rt, new Runnable() {
                @Override
                public void run() {
                    CatCoinFeature.afterEntityDamaged(rt, ab, healthBefore);
                }
            });
        }
        if (rt.config.eggPet || EggPetFeature.hasActive(rt)) {
            CrazyRuntime.safe("egg-pet-after-damage", rt, new Runnable() {
                @Override
                public void run() {
                    EggPetFeature.afterEntityDamaged(rt, ab, healthBefore);
                }
            });
        }
    }

    public static void beforeEnemyKill(Object entity) {
        DiceSlotFeature.beforeEnemyKill(entity);
        EggPetFeature.beforeEnemyKill(entity);
    }

    public static void afterEnemyKill(Object entity, Object killMode) {
        DiceSlotFeature.afterEnemyKill(entity, killMode);
        EggPetFeature.afterEnemyKill(entity, killMode);
    }

    private static CrazyRuntime.StageRuntime runtimeFor(AbEntity entity) {
        if (entity instanceof Entity) return CrazyRuntime.get(((Entity) entity).basis);
        try {
            Object stage = manualcontrol.reflect.BCUFields.get(entity, "sb");
            return CrazyRuntime.get(stage);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
