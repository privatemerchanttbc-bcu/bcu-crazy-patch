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

public final class SpecialSummonEditorHooks {

    private SpecialSummonEditorHooks() {}

    private static final Map<Object, JBTN> BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<Object, JBTN>());

    public static void onFormEditPageBuilt(final Object page) {
        try {
            final Object form = BCUFields.get(page, "form");
            if (form == null) return;
            JBTN btn = new JBTN("Special SM");
            btn.setLnr((Consumer<ActionEvent>) new Consumer<ActionEvent>() {
                @Override
                public void accept(ActionEvent e) {
                    SpecialSummonEditorDialog.open(form);
                }
            });
            Container c = (Container) page;
            c.add(btn);
            BUTTONS.put(page, btn);
            if (c.getWidth() > 0 && c.getHeight() > 0) {
                Page.set(btn, c.getWidth(), c.getHeight(), 1000, 50, 200, 50);
            }
            Logger.log("special-summon: FormEditPage button added");
            SummonAttachEditorHooks.onFormEditPageBuilt(page);
        } catch (Throwable t) {
            Logger.err("special-summon: failed to add editor button", t);
        }
    }

    public static void onFormEditPageResized(Object page, int w, int h) {
        SummonAttachEditorHooks.onFormEditPageResized(page, w, h);
        JBTN btn = BUTTONS.get(page);
        if (btn == null) return;
        try {
            Page.set(btn, w, h, 1000, 50, 200, 50);
        } catch (Throwable t) {
            Logger.err("special-summon: failed to place editor button", t);
        }
    }
}
