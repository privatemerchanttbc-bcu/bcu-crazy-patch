package manualcontrol.crazy.unit;

import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;
import page.JBTN;
import page.Page;

import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class SummonAttachEditorHooks {

    private SummonAttachEditorHooks() {}

    private static final int BTN_X = 1210;
    private static final int BTN_Y = 50;
    private static final int BTN_W = 280;
    private static final int BTN_H = 50;

    private static final Map<Object, JBTN> BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<Object, JBTN>());

    public static void onFormEditPageBuilt(final Object page) {
        try {
            final Object form = BCUFields.get(page, "form");
            if (form == null) return;
            JBTN btn = new JBTN("Summon: Attach to Part");
            btn.setLnr((Consumer<ActionEvent>) new Consumer<ActionEvent>() {
                @Override
                public void accept(ActionEvent e) {
                    SummonAttachEditorDialog.open(form);
                }
            });
            Container c = (Container) page;
            c.add(btn);
            BUTTONS.put(page, btn);
            if (c.getWidth() > 0 && c.getHeight() > 0) {
                Page.set(btn, c.getWidth(), c.getHeight(), BTN_X, BTN_Y, BTN_W, BTN_H);
            }
            Logger.log("summon-attach: FormEditPage button added");
        } catch (Throwable t) {
            Logger.err("summon-attach: failed to add editor button", t);
        }
    }

    public static void onFormEditPageResized(Object page, int w, int h) {
        JBTN btn = BUTTONS.get(page);
        if (btn == null) return;
        try {
            Page.set(btn, w, h, BTN_X, BTN_Y, BTN_W, BTN_H);
        } catch (Throwable t) {
            Logger.err("summon-attach: failed to place editor button", t);
        }
    }
}
