package manualcontrol.adventure;

import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;

final class AdventureTransition {

    private AdventureTransition() {}

    static void toLevel(Object fromBattlePage, int levelIndex) {
        markBackClicked(fromBattlePage);
        AdventureLauncher.launchLevel(fromBattlePage, levelIndex);
    }

    static void quitToMenu(final Object battlePage) {
        final Object origin = AdventureRuntime.originPage();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    if (battlePage != null) {
                        markBackClicked(battlePage);
                        JButton back = (JButton) BCUFields.method(
                                battlePage.getClass(), "getBackButton").invoke(battlePage);
                        if (back != null) back.doClick();
                    }
                } catch (Throwable t) {
                    Logger.err("Adventure: quitToMenu navigation failed", t);
                } finally {
                    AdventureBridge.deactivate("pause-quit");
                }
                if (origin != null) {
                    try { AdventureSlotDialog.show(origin); } catch (Throwable ignored) {}
                }
            }
        });
    }

    static void finish(final Object battlePage, final boolean victory) {
        final Object origin = AdventureRuntime.originPage();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Component parent = origin instanceof Component ? (Component) origin : null;
                boolean ironmanWiped = false;
                try {

                    if (!victory && AdventureRuntime.isIronman() && AdventureRuntime.activeSlot() >= 0) {
                        AdventureRuntime.deleteActiveSlotIfIronman();
                        ironmanWiped = true;
                    }
                    if (battlePage != null) {
                        markBackClicked(battlePage);
                        JButton back = (JButton) BCUFields.method(
                                battlePage.getClass(), "getBackButton").invoke(battlePage);
                        if (back != null) back.doClick();
                    }
                } catch (Throwable t) {
                    Logger.err("Adventure: finish navigation failed", t);
                } finally {
                    AdventureBridge.deactivate(victory ? "victory" : "game-over");
                }

                JOptionPane.showMessageDialog(parent,
                        victory ? "Adventure complete - you cleared every level!"
                                : (ironmanWiped ? "Game over. Ironman save deleted." : "Game over."),
                        "Adventure Mode",
                        victory ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);

                if (origin != null) {
                    try { AdventureSlotDialog.show(origin); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static void markBackClicked(Object battlePage) {
        if (battlePage == null) return;
        try {
            BCUFields.field(battlePage.getClass(), "backClicked").setBoolean(battlePage, true);
        } catch (Throwable ignored) {}
    }
}
