package manualcontrol.hooks;

import manualcontrol.HoldState;
import manualcontrol.FallingRegistry;
import manualcontrol.crazy.beam.BeamFeature;
import manualcontrol.crazy.beam.ArmyCanonFeature;
import manualcontrol.crazy.base.CatCanonFeature;
import manualcontrol.crazy.base.SlingshotBaseFeature;
import manualcontrol.crazy.fall.ImpactFallFeature;
import manualcontrol.crazy.unit.BombItemFeature;
import manualcontrol.crazy.unit.CatCoinFeature;
import manualcontrol.reflect.BCUFields;

public final class EntityHooks {

    private EntityHooks() {}

    public static boolean shouldSkipUpdate(Object entity) {
        manualcontrol.crazy.unit.SpecialSummonFeature.scmTick(entity);
        if (manualcontrol.adventure.AdventureBridge.skipNativeUpdate(entity)) return true;
        if (BeamFeature.isEvolutionFrozen(entity)) return true;
        if (ArmyCanonFeature.isManaged(entity)) return true;
        if (SlingshotBaseFeature.isManaged(entity)) return true;
        if (CatCanonFeature.isManaged(entity)) return true;
        if (ImpactFallFeature.isManaged(entity)) return true;
        if (BombItemFeature.isManaged(entity)) return true;
        if (CatCoinFeature.isManaged(entity)) return true;
        if (FallingRegistry.isManaged(entity)) return true;
        HoldState state = HoldState.get();
        HoldState.Phase phase = state.getPhase();
        if (phase == HoldState.Phase.NONE) return false;
        Object held = state.getHeldEntity();
        if (held == null || held != entity) return false;
        if (phase == HoldState.Phase.CONTROL && shouldRunNativeDeathOrRevive(entity)) return false;
        if (phase == HoldState.Phase.CONTROL && readKbTime(entity) != 0) return false;
        return true;
    }

    public static boolean shouldSkipUpdateMove(Object entity) {
        if (manualcontrol.crazy.unit.SpecialSummonFeature.isScmAttacker(entity)) return true;
        if (manualcontrol.adventure.AdventureBridge.skipNativeUpdateMove(entity)) return true;
        if (BeamFeature.isEvolutionFrozen(entity)) return true;
        if (ArmyCanonFeature.isManaged(entity)) return true;
        if (SlingshotBaseFeature.isManaged(entity)) return true;
        if (CatCanonFeature.isManaged(entity)) return true;
        if (ImpactFallFeature.isManaged(entity)) return true;
        if (BombItemFeature.isManaged(entity)) return true;
        if (CatCoinFeature.isManaged(entity)) return true;
        if (FallingRegistry.isManaged(entity)) return true;
        HoldState state = HoldState.get();
        HoldState.Phase phase = state.getPhase();
        if (phase == HoldState.Phase.NONE) return false;
        Object held = state.getHeldEntity();
        return held != null && held == entity;
    }

    public static boolean shouldSkipUpdate2(Object entity) {
        if (manualcontrol.adventure.AdventureBridge.skipNativeUpdate(entity)) return true;
        if (BeamFeature.isEvolutionFrozen(entity)) return true;
        if (ArmyCanonFeature.isManaged(entity)) return true;
        if (SlingshotBaseFeature.isManaged(entity)) return true;
        if (CatCanonFeature.isManaged(entity)) return true;
        if (ImpactFallFeature.isManaged(entity)) return true;
        if (BombItemFeature.isManaged(entity)) return true;
        if (CatCoinFeature.isManaged(entity)) return true;
        if (FallingRegistry.isManaged(entity)) return true;
        HoldState state = HoldState.get();
        HoldState.Phase phase = state.getPhase();
        if (phase == HoldState.Phase.NONE) return false;
        Object held = state.getHeldEntity();
        if (held == null || held != entity) return false;
        if (phase == HoldState.Phase.CONTROL && shouldRunNativeDeathOrRevive(entity)) return false;
        if (phase == HoldState.Phase.CONTROL && readKbTime(entity) != 0) return false;
        return true;
    }

    public static boolean shouldSkipUpdateAnimation(Object entity) {
        return manualcontrol.adventure.AdventureBridge.onUpdateAnimation(entity);
    }

    private static boolean shouldRunNativeDeathOrRevive(Object entity) {
        try {
            if (BCUFields.field(entity.getClass(), "health").getLong(entity) <= 0L) return true;
        } catch (Throwable ignored) {}
        try {
            Object statusArr = BCUFields.get(entity, "status");
            if (((int[][]) statusArr)[48][1] > 0) return true;
        } catch (Throwable ignored) {}
        try {
            Object anim = BCUFields.get(entity, "anim");
            return BCUFields.get(anim, "corpse") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int readKbTime(Object entity) {
        try {
            return BCUFields.getInt(entity, "kbTime");
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
