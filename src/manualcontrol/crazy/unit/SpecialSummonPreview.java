package manualcontrol.crazy.unit;

import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.unit.Form;

import manualcontrol.crazy.collision.MeasuringGraphics;
import manualcontrol.reflect.BCUFields;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

final class SpecialSummonPreview {

    private SpecialSummonPreview() {}

    static BufferedImage render(Form f, int w, int h) {
        try {
            if (f == null || ImageBuilder.builder == null) return null;
            EAnimU anim = f.getEAnim(AnimU.UType.WALK);
            if (anim == null) return null;
            anim.update(false);

            MeasuringGraphics mg = new MeasuringGraphics();
            anim.draw(mg, new P(0f, 0f), 1f);
            if (!mg.hasBox()) return null;

            float bw = Math.max(1f, mg.maxX() - mg.minX());
            float bh = Math.max(1f, mg.maxY() - mg.minY());
            float s = Math.min((w * 0.82f) / bw, (h * 0.82f) / bh);
            float siz = Math.max(0.05f, Math.min(6f, s));
            float ox = w / 2f - siz * (mg.minX() + mg.maxX()) / 2f;
            float oy = h - 6f - siz * mg.maxY();

            FakeImage canvas = ImageBuilder.builder.build(w, h);
            Object bi = BCUFields.invoke(canvas, "bimg");
            if (!(bi instanceof BufferedImage)) return null;
            BufferedImage bimg = (BufferedImage) bi;

            Graphics2D clr = bimg.createGraphics();
            clr.setComposite(AlphaComposite.Clear);
            clr.fillRect(0, 0, w, h);
            clr.dispose();

            anim.setTime(0f);
            FakeGraphics fg = canvas.getGraphics();
            anim.draw(fg, new P(ox, oy), siz);
            return bimg;
        } catch (Throwable t) {
            return null;
        }
    }
}
