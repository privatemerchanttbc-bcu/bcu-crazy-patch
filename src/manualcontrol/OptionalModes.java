package manualcontrol;

import common.system.fake.FakeGraphics;
import manualcontrol.crazy.CrazyConfig;

import java.util.List;

public final class OptionalModes {

    private static final String BRIDGE = "manualcontrol.modes.core.ModeBridge";
    private static final String RUNTIME = "manualcontrol.modes.core.ModeRuntime";

    private static final OptionalFeatures.Call IS_ACTIVE_STAGE =
            OptionalFeatures.bind(BRIDGE, "isActiveStage", Object.class);
    private static final OptionalFeatures.Call HIDES_NATIVE_UI =
            OptionalFeatures.bind(BRIDGE, "hidesNativeUi", Object.class);
    private static final OptionalFeatures.Call ON_PRESS =
            OptionalFeatures.bind(BRIDGE, "onPress", Object.class, int.class, int.class);
    private static final OptionalFeatures.Call ON_WHEEL =
            OptionalFeatures.bind(BRIDGE, "onWheel", Object.class, int.class, int.class, int.class);
    private static final OptionalFeatures.Call APPLY_CAMERA =
            OptionalFeatures.bind(BRIDGE, "applyCamera", Object.class, Object.class);
    private static final OptionalFeatures.Call DRAW_WORLD =
            OptionalFeatures.bind(BRIDGE, "drawWorld", Object.class, FakeGraphics.class);
    private static final OptionalFeatures.Call DRAW_WORLD_OVERLAY =
            OptionalFeatures.bind(BRIDGE, "drawWorldOverlay", Object.class, FakeGraphics.class);
    private static final OptionalFeatures.Call DRAW_HUD =
            OptionalFeatures.bind(BRIDGE, "drawHud", Object.class, FakeGraphics.class);
    private static final OptionalFeatures.Call WANTS_KEYS =
            OptionalFeatures.bind(BRIDGE, "wantsKeys", Object.class);
    private static final OptionalFeatures.Call HANDLE_KEY =
            OptionalFeatures.bind(BRIDGE, "handleKey", int.class, boolean.class);
    private static final OptionalFeatures.Call ON_SUB_FRAME =
            OptionalFeatures.bind(BRIDGE, "onSubFrame");
    private static final OptionalFeatures.Call ON_FOREIGN_BATTLE =
            OptionalFeatures.bind(BRIDGE, "onForeignBattleConstructed");
    private static final OptionalFeatures.Call HOLLOW_CONFIG =
            OptionalFeatures.bind(BRIDGE, "hollowConfig");
    private static final OptionalFeatures.Call ADOPT_BATTLE =
            OptionalFeatures.bind(BRIDGE, "adoptBattle", Object.class);
    private static final OptionalFeatures.Call BEFORE_STAGE_UPDATE =
            OptionalFeatures.bind(BRIDGE, "beforeStageUpdate", Object.class);
    private static final OptionalFeatures.Call AFTER_STAGE_UPDATE =
            OptionalFeatures.bind(BRIDGE, "afterStageUpdate", Object.class);
    private static final OptionalFeatures.Call BLOCK_ALL_SPAWNS =
            OptionalFeatures.bind(BRIDGE, "blockAllSpawns", Object.class);
    private static final OptionalFeatures.Call FILTER_BASES =
            OptionalFeatures.bind(BRIDGE, "filterBases", List.class, Object.class);
    private static final OptionalFeatures.Call IS_LAUNCH_PENDING =
            OptionalFeatures.bind(RUNTIME, "isLaunchPending");

    private OptionalModes() {}

    public static boolean isActiveStage(Object stageBasis) {
        return IS_ACTIVE_STAGE.invokeBoolean(false, stageBasis);
    }

    public static boolean hidesNativeUi(Object stageBasis) {
        return HIDES_NATIVE_UI.invokeBoolean(false, stageBasis);
    }

    public static boolean onPress(Object bbpainter, int x, int y) {
        return ON_PRESS.invokeBoolean(false, bbpainter, Integer.valueOf(x), Integer.valueOf(y));
    }

    public static boolean onWheel(Object bbpainter, int x, int y, int rotation) {
        return ON_WHEEL.invokeBoolean(false, bbpainter,
                Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(rotation));
    }

    public static void applyCamera(Object bbpainter, Object stageBasis) {
        APPLY_CAMERA.invoke(bbpainter, stageBasis);
    }

    public static void drawWorld(Object bbpainter, FakeGraphics g) {
        DRAW_WORLD.invoke(bbpainter, g);
    }

    public static void drawWorldOverlay(Object bbpainter, FakeGraphics g) {
        DRAW_WORLD_OVERLAY.invoke(bbpainter, g);
    }

    public static void drawHud(Object bbpainter, FakeGraphics g) {
        DRAW_HUD.invoke(bbpainter, g);
    }

    public static boolean wantsKeys(Object keyHandler) {
        return WANTS_KEYS.invokeBoolean(false, keyHandler);
    }

    public static boolean handleKey(int code, boolean down) {
        return HANDLE_KEY.invokeBoolean(false, Integer.valueOf(code), Boolean.valueOf(down));
    }

    public static void onSubFrame() {
        ON_SUB_FRAME.invoke();
    }

    public static void onForeignBattleConstructed() {
        ON_FOREIGN_BATTLE.invoke();
    }

    public static CrazyConfig hollowConfig() {
        Object value = HOLLOW_CONFIG.invoke();
        return value instanceof CrazyConfig ? (CrazyConfig) value : null;
    }

    public static void adoptBattle(Object page) {
        ADOPT_BATTLE.invoke(page);
    }

    public static void beforeStageUpdate(Object stageBasis) {
        BEFORE_STAGE_UPDATE.invoke(stageBasis);
    }

    public static void afterStageUpdate(Object stageBasis) {
        AFTER_STAGE_UPDATE.invoke(stageBasis);
    }

    public static boolean blockAllSpawns(Object stageBasis) {
        return BLOCK_ALL_SPAWNS.invokeBoolean(false, stageBasis);
    }

    @SuppressWarnings("rawtypes")
    public static void filterBases(List result, Object stageBasis) {
        FILTER_BASES.invoke(result, stageBasis);
    }

    public static boolean isLaunchPending() {
        return IS_LAUNCH_PENDING.invokeBoolean(false);
    }
}
