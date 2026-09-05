package manualcontrol.custommap;

import common.pack.PackData;
import common.pack.UserProfile;
import common.system.fake.FakeImage;
import common.util.anim.AnimU;
import common.util.unit.Form;
import common.util.unit.Unit;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class UnitReferenceCatalog {

    private static List<BufferedImage> cached;

    private UnitReferenceCatalog() {}

    static synchronized List<BufferedImage> images() {
        if (cached != null) return cached;
        ArrayList<BufferedImage> candidates = new ArrayList<BufferedImage>();
        try {
            for (PackData pack : UserProfile.getAllPacks()) {
                if (pack == null || pack.units == null) continue;
                List<Unit> units;
                try { units = pack.units.getList(); } catch (Throwable ignored) { continue; }
                if (units == null) continue;
                for (Unit unit : units) {
                    if (unit == null || unit.forms == null) continue;
                    for (Form form : unit.forms) {
                        BufferedImage image = imageOf(form);
                        if (image != null) candidates.add(cropAlpha(image));
                        if (candidates.size() >= 72) break;
                    }
                    if (candidates.size() >= 72) break;
                }
                if (candidates.size() >= 72) break;
            }
        } catch (Throwable ignored) {}

        if (candidates.size() >= 3) {
            Collections.sort(candidates, new Comparator<BufferedImage>() {
                @Override public int compare(BufferedImage a, BufferedImage b) {
                    long aa = (long) a.getWidth() * a.getHeight();
                    long bb = (long) b.getWidth() * b.getHeight();
                    return Long.compare(aa, bb);
                }
            });
            ArrayList<BufferedImage> chosen = new ArrayList<BufferedImage>();
            chosen.add(candidates.get(Math.max(0, candidates.size() / 6)));
            chosen.add(candidates.get(candidates.size() / 2));
            chosen.add(candidates.get(Math.min(candidates.size() - 1,
                    candidates.size() * 5 / 6)));
            cached = Collections.unmodifiableList(chosen);
        } else {
            ArrayList<BufferedImage> fallback = new ArrayList<BufferedImage>();
            fallback.add(silhouette(new Color(245, 245, 245), 0.80f));
            fallback.add(silhouette(new Color(255, 220, 105), 1.00f));
            fallback.add(silhouette(new Color(235, 135, 120), 1.25f));
            cached = Collections.unmodifiableList(fallback);
        }
        return cached;
    }

    private static BufferedImage imageOf(Form form) {
        try {
            if (form == null || form.anim == null) return null;
            AnimU<?> animation = (AnimU<?>) form.anim;
            FakeImage image = animation.getUni() == null
                    ? null : animation.getUni().getImg();
            Object buffered = image == null ? null : image.bimg();
            if (!(buffered instanceof BufferedImage)) return null;
            BufferedImage result = (BufferedImage) buffered;
            return result.getWidth() > 4 && result.getHeight() > 4 ? result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BufferedImage cropAlpha(BufferedImage source) {
        int left = source.getWidth(), top = source.getHeight(), right = -1, bottom = -1;
        int step = Math.max(1, Math.max(source.getWidth(), source.getHeight()) / 512);
        for (int y = 0; y < source.getHeight(); y += step)
            for (int x = 0; x < source.getWidth(); x += step)
                if (((source.getRGB(x, y) >>> 24) & 0xff) > 12) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
        if (right < left || bottom < top) return source;
        left = Math.max(0, left - step);
        top = Math.max(0, top - step);
        right = Math.min(source.getWidth() - 1, right + step);
        bottom = Math.min(source.getHeight() - 1, bottom + step);
        return source.getSubimage(left, top, right - left + 1, bottom - top + 1);
    }

    private static BufferedImage silhouette(Color color, float widthScale) {
        BufferedImage image = new BufferedImage(96, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0, 0, 0, 150));
            int w = Math.round(58 * widthScale);
            int x = (96 - w) / 2;
            g.fillOval(x - 3, 45, w + 6, 64);
            g.fillOval(25, 15, 52, 52);
            g.fillPolygon(new int[]{29, 36, 43}, new int[]{22, 2, 24}, 3);
            g.fillPolygon(new int[]{55, 65, 70}, new int[]{23, 2, 25}, 3);
            g.setColor(color);
            g.fillOval(x, 42, w, 64);
            g.fillOval(28, 17, 46, 48);
            g.fillPolygon(new int[]{31, 37, 44}, new int[]{24, 7, 25}, 3);
            g.fillPolygon(new int[]{56, 64, 68}, new int[]{25, 7, 26}, 3);
            g.setColor(Color.DARK_GRAY);
            g.fillOval(41, 35, 4, 6);
            g.fillOval(58, 35, 4, 6);
        } finally {
            g.dispose();
        }
        return image;
    }
}
