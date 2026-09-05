package manualcontrol.custommap;

import manualcontrol.Logger;
import page.JBTN;
import page.Page;

import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class CustomMapHooks {

    private static final Map<Object, JBTN> BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<Object, JBTN>());

    private CustomMapHooks() {}

    public static void onMainPageBuilt(final Object mainPage) {
        if (!manualcontrol.FeatureFlags.customMaps) return;
        try {
            if (BUTTONS.containsKey(mainPage)) return;
            JBTN button = new JBTN("Custom Map Studio");
            button.setLnr((Consumer<ActionEvent>) new Consumer<ActionEvent>() {
                @Override public void accept(ActionEvent event) {
                    try { CustomMapStudioDialog.show(mainPage); }
                    catch (Throwable t) { Logger.err("CustomMap: Studio failed", t); }
                }
            });
            ((Container) mainPage).add(button);
            BUTTONS.put(mainPage, button);
            Logger.log("CustomMap: MainPage Studio button added");
        } catch (Throwable t) {
            Logger.err("CustomMap: failed to add Studio button", t);
        }
    }

    public static void onMainPageResized(Object mainPage, int w, int h) {
        JBTN button = BUTTONS.get(mainPage);
        if (button == null) return;
        try { Page.set(button, w, h, 1500, 840, 200, 50); }
        catch (Throwable t) { Logger.err("CustomMap: failed to place Studio button", t); }
    }
}
