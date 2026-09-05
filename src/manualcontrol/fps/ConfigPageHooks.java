package manualcontrol.fps;

import manualcontrol.Logger;
import page.Page;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import java.awt.Container;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ConfigPageHooks {

    private static final Map<Object, Widgets> WIDGETS =
            Collections.synchronizedMap(new WeakHashMap<Object, Widgets>());

    private ConfigPageHooks() {}

    private static final class Widgets {
        final JLabel label;
        final JComboBox<String> combo;
        Widgets(JLabel label, JComboBox<String> combo) {
            this.label = label;
            this.combo = combo;
        }
    }

    public static void onConfigPageBuilt(Object page) {
        try {
            JLabel label = new JLabel("Battle FPS");
            String[] items = new String[FpsConfig.CHOICES.length];
            int sel = 0;
            for (int i = 0; i < FpsConfig.CHOICES.length; i++) {
                items[i] = FpsConfig.CHOICES[i] + " FPS";
                if (FpsConfig.CHOICES[i] == FpsConfig.targetFps()) sel = i;
            }
            final JComboBox<String> combo = new JComboBox<String>(items);
            combo.setSelectedIndex(sel);
            combo.addActionListener(e -> {
                int idx = combo.getSelectedIndex();
                if (idx >= 0 && idx < FpsConfig.CHOICES.length) {
                    FpsConfig.setTargetFps(FpsConfig.CHOICES[idx]);
                }
            });
            ((Container) page).add(label);
            ((Container) page).add(combo);
            WIDGETS.put(page, new Widgets(label, combo));
            Logger.log("FPS: ConfigPage selector added");
        } catch (Throwable t) {
            Logger.err("FPS: failed to add ConfigPage selector", t);
        }
    }

    public static void onConfigPageResized(Object page, int w, int h) {
        Widgets ws = WIDGETS.get(page);
        if (ws == null) return;
        try {
            Page.set(ws.label, w, h, 1750, 725, 300, 50);
            Page.set(ws.combo, w, h, 1750, 790, 300, 50);
        } catch (Throwable t) {
            Logger.err("FPS: failed to place ConfigPage selector", t);
        }
    }
}
