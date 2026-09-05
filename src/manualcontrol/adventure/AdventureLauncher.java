package manualcontrol.adventure;

import common.battle.BasisLU;
import common.battle.BasisSet;
import common.util.stage.MapColc;
import common.util.stage.Stage;
import common.util.stage.StageMap;
import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapRuntime;

import javax.swing.SwingUtilities;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class AdventureLauncher {

    private static volatile List<Stage> levels;

    private AdventureLauncher() {}

    public static List<Stage> levels() {
        List<Stage> ls = levels;
        if (ls != null) return ls;
        synchronized (AdventureLauncher.class) {
            if (levels != null) return levels;
            ArrayList<Stage> out = new ArrayList<Stage>();
            try {
                MapColc mc = MapColc.DefMapColc.getMap("CH");
                if (mc == null) {
                    Logger.err("Adventure: DefMapColc.getMap(\"CH\") returned null", null);
                } else {
                    for (StageMap sm : mc.maps.getList()) {
                        if (sm == null) continue;
                        for (Stage st : sm.list.getList()) {
                            if (st != null) out.add(st);
                        }
                    }
                    Logger.log("Adventure: level list built from map 'CH' (" + mc
                            + "): " + out.size() + " stages");
                    for (int i = 0; i < Math.min(3, out.size()); i++) {
                        Logger.log("Adventure:   level " + i + " = " + levelName(i, out.get(i)));
                    }
                }
            } catch (Throwable t) {
                Logger.err("Adventure: failed to build level list", t);
            }
            levels = out;
            return out;
        }
    }

    public static String levelName(int index, Stage st) {
        String name = null;
        try {
            if (st.names != null) name = st.names.toString();
            if (name == null || name.trim().isEmpty()) name = st.name;
        } catch (Throwable ignored) {}
        if (name == null || name.trim().isEmpty()) name = String.valueOf(st);
        return "Lv " + (index + 1) + ": " + name;
    }

    public static void launchLevel(final Object fromPage, final int levelIndex) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Stage> ls = levels();
                    Object origin = AdventureRuntime.originPage();
                    if (origin == null) origin = fromPage;
                    Stage custom = CustomMapRuntime.pendingStage(CustomMapDocument.MapMode.ADVENTURE);
                    if (fromPage == null || levelIndex < 0 || (custom == null && levelIndex >= ls.size())) {
                        Logger.err("Adventure: cannot launch level " + levelIndex
                                + " (levels=" + ls.size() + ", page=" + fromPage + ")", null);
                        AdventureBridge.deactivate("launch-out-of-range");
                        return;
                    }
                    Stage st = custom != null ? custom : ls.get(levelIndex);
                    AdventureRuntime.onLaunching(levelIndex);
                    Logger.log("Adventure: launching " + levelName(levelIndex, st));

                    Class<?> pageCls = Class.forName("page.Page");
                    Class<?> infoCls = Class.forName("page.battle.BattleInfoPage");
                    Constructor<?> ctor = infoCls.getDeclaredConstructor(
                            pageCls, Stage.class, int.class, BasisLU.class, int[].class);
                    ctor.setAccessible(true);
                    BasisLU lu = BasisSet.current().sele;
                    Object infoPage = ctor.newInstance(origin, st, 0, lu, new int[]{0});

                    Method changePanel = BCUFields.method(fromPage.getClass(), "changePanel", pageCls);
                    changePanel.invoke(fromPage, infoPage);
                } catch (Throwable t) {
                    Logger.err("Adventure: launchLevel failed", t);
                    AdventureBridge.deactivate("launch-failed");
                }
            }
        });
    }
}
