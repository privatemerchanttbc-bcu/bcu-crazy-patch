package manualcontrol.crazy;

import manualcontrol.OptionalModes;
import manualcontrol.NativeRenderLifecycleGuard;

public final class CrazyStageHooks {
    private CrazyStageHooks() {}

    public static boolean onStageUpdate(Object stageBasis) {
        NativeRenderLifecycleGuard.beforeStageUpdate(stageBasis);
        manualcontrol.adventure.AdventureBridge.beforeStageUpdate(stageBasis);
        OptionalModes.beforeStageUpdate(stageBasis);
        manualcontrol.custommap.CustomMapBattleRuntime.beforeStageUpdate(stageBasis);
        CrazyRuntime.tick(stageBasis);
        boolean skip = CrazyRuntime.shouldSkipNativeStageUpdate(stageBasis);
        if (skip) {
            NativeRenderLifecycleGuard.onNativeStageSkipped(stageBasis, "stage-update-skip");
        }
        return skip;
    }

    public static boolean onStageAnimationUpdate(Object stageBasis) {
        boolean skip = CrazyRuntime.shouldSkipNativeStageAnimation(stageBasis);
        if (skip) {
            NativeRenderLifecycleGuard.onNativeStageSkipped(stageBasis, "stage-animation-skip");
        }
        return skip;
    }

    public static void afterStageUpdate(Object stageBasis) {
        NativeRenderLifecycleGuard.afterStageUpdate(stageBasis);
        manualcontrol.adventure.AdventureBridge.afterStageUpdate(stageBasis);
        OptionalModes.afterStageUpdate(stageBasis);
        CrazyRuntime.afterNativeStageUpdate(stageBasis);
        manualcontrol.custommap.CustomMapBattleRuntime.afterStageUpdate(stageBasis);
    }

    public static void overrideMaxMoney(Object stageBasis) {
        CrazyRuntime.overrideMaxMoney(stageBasis);
    }

    public static int beforeSpawn(Object stageBasis, int row, int col, boolean manual) {
        if (manualcontrol.adventure.AdventureBridge.blockAllSpawns(stageBasis)) return 0;
        if (OptionalModes.blockAllSpawns(stageBasis)) return 0;
        return CrazyRuntime.beforeSpawn(stageBasis, row, col, manual);
    }

    public static void onSpawn(boolean success, Object stageBasis, int row, int col, boolean manual) {
        CrazyRuntime.onSpawn(success, stageBasis, row, col, manual);
    }

    @SuppressWarnings("rawtypes")
    public static void onInRange(java.util.List result, Object stageBasis, int touch, int dire,
                                 float d0, float d1, boolean excludeRightEdge) {
        manualcontrol.adventure.AdventureBridge.filterBases(result, stageBasis);
        OptionalModes.filterBases(result, stageBasis);
        CrazyRuntime.adjustInRange(result, stageBasis, touch, dire, d0, d1, excludeRightEdge);
    }

    public static int entityCount(Object stageBasis, int dire) {
        return manualcontrol.crazy.unit.DiceSlotFeature.entityCount(stageBasis, dire);
    }

    public static int entityCountGroup(Object stageBasis, int dire, int group) {
        return manualcontrol.crazy.unit.DiceSlotFeature.entityCount(stageBasis, dire, group);
    }

    public static int entityCountRar(Object stageBasis, int rarity) {
        return manualcontrol.crazy.unit.DiceSlotFeature.entityCountRar(stageBasis, rarity);
    }
}
