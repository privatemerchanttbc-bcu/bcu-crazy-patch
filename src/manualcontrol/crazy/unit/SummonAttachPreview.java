package manualcontrol.crazy.unit;

import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.anim.EPart;
import common.util.unit.Form;

import manualcontrol.crazy.collision.AnimGeometry;
import manualcontrol.crazy.collision.MeasuringGraphics;
import manualcontrol.reflect.BCUFields;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

final class SummonAttachPreview {

    private SummonAttachPreview() {}

    static BufferedImage render(Form summoner, AnimU.UType type, int frame, int part,
                                Form ghost, AnimU.UType ghostType, float scale,
                                float offX, float offY, int w, int h) {
        try {
            if (summoner == null || ImageBuilder.builder == null) return null;
            EAnimU anim = summoner.getEAnim(type);
            if (anim == null) return null;
            anim.setTime(frame);

            MeasuringGraphics mg = new MeasuringGraphics();
            anim.draw(mg, new P(0f, 0f), 1f);
            if (!mg.hasBox()) return null;

            float bw = Math.max(1f, mg.maxX() - mg.minX());
            float bh = Math.max(1f, mg.maxY() - mg.minY());
            float s = Math.min((w * 0.70f) / bw, (h * 0.70f) / bh);
            float siz = Math.max(0.05f, Math.min(6f, s));
            float ox = w / 2f - siz * (mg.minX() + mg.maxX()) / 2f;
            float oy = h - 10f - siz * mg.maxY();

            FakeImage canvas = ImageBuilder.builder.build(w, h);
            Object bi = BCUFields.invoke(canvas, "bimg");
            if (!(bi instanceof BufferedImage)) return null;
            BufferedImage bimg = (BufferedImage) bi;

            Graphics2D clr = bimg.createGraphics();
            clr.setComposite(AlphaComposite.Clear);
            clr.fillRect(0, 0, w, h);
            clr.dispose();

            anim.setTime(frame);
            FakeGraphics fg = canvas.getGraphics();
            anim.draw(fg, new P(ox, oy), siz);

            anim.setTime(frame);
            EPart target = partOf(anim, part);
            float[] geom = target == null ? null : geometry(target, siz);

            if (geom != null && ghost != null) {
                drawGhost(fg, ghost, ghostType, ox + geom[0] + offX * siz,
                        oy + geom[1] + offY * siz, geom[2], geom[3], geom[4], siz * scale);
            }

            Graphics2D g2 = bimg.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (target != null) {
                    AnimGeometry.PartBox box = AnimGeometry.partBox(target, anim, siz);
                    g2.setStroke(new BasicStroke(2f));
                    g2.setColor(new Color(255, 190, 40));
                    if (box.hasSprite) {
                        int[] xs = new int[4];
                        int[] ys = new int[4];
                        for (int i = 0; i < 4; i++) {
                            xs[i] = Math.round((float) box.xs[i] + ox);
                            ys[i] = Math.round((float) box.ys[i] + oy);
                        }
                        g2.drawPolygon(xs, ys, 4);
                    }
                    int px = Math.round((float) box.pivotX + ox);
                    int py = Math.round((float) box.pivotY + oy);
                    g2.drawLine(px - 6, py, px + 6, py);
                    g2.drawLine(px, py - 6, px, py + 6);
                }
                g2.setColor(new Color(120, 120, 120));
                g2.drawLine(0, Math.round(oy), w, Math.round(oy));
            } finally {
                g2.dispose();
            }
            return bimg;
        } catch (Throwable t) {
            return null;
        }
    }

    static float[] geometry(EPart part, float siz) {
        try {
            AffineTransform at = AnimGeometry.partTransform(part, siz);
            double m00 = at.getScaleX();
            double m10 = at.getShearY();
            double m01 = at.getShearX();
            double m11 = at.getScaleY();
            double rot = Math.atan2(m10, m00);
            double det = m00 * m11 - m01 * m10;
            double[] size = AnimGeometry.partSize(part);
            float sx = (float) size[0];
            float sy = (float) size[1];
            if (det < 0) sx = -sx;
            return new float[]{(float) at.getTranslateX(), (float) at.getTranslateY(), sx, sy, (float) rot};
        } catch (Throwable t) {
            return null;
        }
    }

    static EPart partOf(EAnimU anim, int index) {
        if (anim == null || anim.ent == null) return null;
        if (index < 0 || index >= anim.ent.length) return null;
        return anim.ent[index];
    }

    private static void drawGhost(FakeGraphics fg, Form ghost, AnimU.UType type,
                                  float x, float y, float sx, float sy, float rot, float siz) {
        EAnimU anim;
        try {
            anim = ghost.getEAnim(type);
        } catch (Throwable t) {
            return;
        }
        if (anim == null) return;
        anim.setTime(0f);
        FakeTransform old = null;
        try {
            old = fg.getTransform();
        } catch (Throwable ignored) {}
        try {
            fg.translate(x, y);
            if (Math.abs(rot) > 0.001f) fg.rotate(rot);
            if (Math.abs(sx - 1f) > 0.001f || Math.abs(sy - 1f) > 0.001f) fg.scale(sx, sy);
            anim.draw(fg, new P(0f, 0f), siz);
        } catch (Throwable ignored) {
        } finally {
            if (old != null) {
                try {
                    fg.setTransform(old);
                    fg.delete(old);
                } catch (Throwable ignored) {}
            }
        }
    }
}
