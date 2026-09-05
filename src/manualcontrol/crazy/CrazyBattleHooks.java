package manualcontrol.crazy;

import manualcontrol.OptionalModes;
import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;

public final class CrazyBattleHooks {

    private CrazyBattleHooks() {}

    public static void onBattleConstructed(Object page) {
        try {
            Object stage = CrazyRuntime.stageFromPage(page);

            if (manualcontrol.adventure.AdventureRuntime.isLaunchPending()) {
                manualcontrol.custommap.CustomMapBattleRuntime.release(null);
                OptionalModes.onForeignBattleConstructed();
                CrazyRuntime.register(stage, manualcontrol.adventure.AdventureBridge.adventureConfig());
                manualcontrol.adventure.AdventureBridge.adoptBattle(page);
                return;
            }
            if (OptionalModes.isLaunchPending()) {
                manualcontrol.custommap.CustomMapBattleRuntime.release(null);
                manualcontrol.adventure.AdventureBridge.onForeignBattleConstructed();
                CrazyRuntime.register(stage, OptionalModes.hollowConfig());
                OptionalModes.adoptBattle(page);
                return;
            }
            manualcontrol.adventure.AdventureBridge.onForeignBattleConstructed();
            OptionalModes.onForeignBattleConstructed();

            manualcontrol.custommap.CustomMapBattleRuntime.adoptIfCustom(stage);
            Component parent = parentComponent(page);
            CrazyConfig config = CrazyDialog.show(parent);
            CrazyRuntime.register(stage, config);
        } catch (Throwable t) {
            Logger.err("BCU Crazy setup failed", t);
            final Component parent = parentComponent(page);
            final String detail = t.getMessage() == null || t.getMessage().trim().isEmpty()
                    ? t.toString() : t.getMessage();
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    JOptionPane.showMessageDialog(parent,
                            "The selected battle could not be initialized:\n" + detail,
                            "Crazy BCU", JOptionPane.ERROR_MESSAGE);
                }
            });
        }
    }

    private static Component parentComponent(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            if (bb instanceof Component) return (Component) bb;
        } catch (Throwable ignored) {}
        return null;
    }
}
