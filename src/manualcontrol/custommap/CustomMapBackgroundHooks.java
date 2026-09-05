package manualcontrol.custommap;

import common.util.pack.Background;

import javax.swing.ImageIcon;

public final class CustomMapBackgroundHooks {
    private CustomMapBackgroundHooks() {}

    public static void prepareCatalog() {
        try {
            CustomMapRepository.synchronizeBackgroundCatalog();
        } catch (Throwable t) {
            manualcontrol.Logger.err(
                    "CustomMap: Background catalog synchronization failed", t);
        }
    }

    public static String displayName(Object value) {
        if (!(value instanceof Background)) return null;
        Background background = (Background) value;
        String name = CustomMapRepository.nameForBackground(background.id);
        return name == null ? null : "Custom Stage: " + name;
    }

    public static ImageIcon previewIcon(Object value, int width, int height) {
        if (!(value instanceof Background) || width <= 0 || height <= 0) return null;
        try {
            return CustomMapThumbnail.icon(((Background) value).id, width, height);
        } catch (Throwable t) {
            manualcontrol.Logger.err("CustomMap: Background preview failed", t);
            return null;
        }
    }
}
