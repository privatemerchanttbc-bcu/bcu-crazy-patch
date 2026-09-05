package manualcontrol.render;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public final class ModeText {

    private static Field transformDataField;
    private static final Map<String, FakeImage> CACHE = new HashMap<String, FakeImage>();

    private ModeText() {}

    public static FakeImage text(String s, Color color, int fontPx) {
        String key = fontPx + ":" + color.getRGB() + ":" + s;
        FakeImage cached = CACHE.get(key);
        if (cached != null) return cached;
        FakeImage baked = bakeText(s, color, fontPx);
        if (baked != null) CACHE.put(key, baked);
        return baked;
    }

    public static FakeImage bakeText(String text, Color color, int fontPx) {
        try {
            if (ImageBuilder.builder == null || text == null || text.isEmpty()) return null;
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontPx);
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = probe.createGraphics();
            pg.setFont(font);
            FontMetrics fm = pg.getFontMetrics();
            int w = Math.max(1, fm.stringWidth(text) + 8);
            int h = Math.max(1, fm.getHeight() + 4);
            int baseline = fm.getAscent() + 2;
            pg.dispose();

            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gg = bi.createGraphics();
            gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gg.setFont(font);
            gg.setColor(new Color(0, 0, 0, 200));
            gg.drawString(text, 5, baseline + 1);
            gg.setColor(color);
            gg.drawString(text, 4, baseline);
            gg.dispose();

            FakeImage img = ImageBuilder.builder.build(w, h);
            if (img == null) return null;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    img.setRGB(x, y, bi.getRGB(x, y));
            return img;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static FakeTransform pushIdentity(FakeGraphics gra) {
        try {
            FakeTransform oldTransform = gra.getTransform();
            FakeTransform identity = gra.getTransform();
            Field f = transformDataField;
            if (f == null || f.getDeclaringClass() != identity.getClass()) {
                f = identity.getClass().getDeclaredField("data");
                f.setAccessible(true);
                transformDataField = f;
            }
            f.set(identity, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            gra.setTransform(identity);
            try { gra.delete(identity); } catch (Throwable ignored) {}
            return oldTransform;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void popIdentity(FakeGraphics gra, FakeTransform oldTransform) {
        if (oldTransform == null) return;
        try {
            gra.setTransform(oldTransform);
        } catch (Throwable ignored) {
        } finally {
            try { gra.delete(oldTransform); } catch (Throwable ignored) {}
        }
    }
}
