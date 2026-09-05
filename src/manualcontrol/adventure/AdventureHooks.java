package manualcontrol.adventure;

import manualcontrol.Logger;
import page.JBTN;
import page.Page;

import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class AdventureHooks {

    private static final Map<Object, JBTN> BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<Object, JBTN>());

    private AdventureHooks() {}

    public static void onMainPageBuilt(final Object mainPage) {
        if (!manualcontrol.FeatureFlags.adventure) return;
        try {
            JBTN btn = new JBTN("Adventure Mode");
            btn.setLnr((Consumer<ActionEvent>) new Consumer<ActionEvent>() {
                @Override
                public void accept(ActionEvent e) {
                    onAdventureClicked(mainPage);
                }
            });
            ((Container) mainPage).add(btn);
            BUTTONS.put(mainPage, btn);
            Logger.log("Adventure: MainPage button added");
        } catch (Throwable t) {
            Logger.err("Adventure: failed to add MainPage button", t);
        }
    }

    public static void onMainPageResized(Object mainPage, int w, int h) {
        JBTN btn = BUTTONS.get(mainPage);
        if (btn == null) return;
        try {
            Page.set(btn, w, h, 1500, 600, 200, 50);
        } catch (Throwable t) {
            Logger.err("Adventure: failed to place MainPage button", t);
        }
    }

    static void onAdventureClicked(Object mainPage) {
        try {
            AdventureSlotDialog.show(mainPage);
        } catch (Throwable t) {
            Logger.err("Adventure: slot dialog failed", t);
        }
    }
}
