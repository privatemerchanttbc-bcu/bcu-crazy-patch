package manualcontrol.adventure;

import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.unit.Form;
import manualcontrol.crazy.collision.MeasuringGraphics;
import manualcontrol.reflect.BCUFields;

import javax.swing.ImageIcon;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

final class AdventureUnitIcon {

    private AdventureUnitIcon() {}

    static ImageIcon render(Form f, int size) {
        if (f == null || size <= 0) return null;
        try {
            if (ImageBuilder.builder == null) return null;
            FakeImage canvas = ImageBuilder.builder.build(size, size);
            Object bi = BCUFields.invoke(canvas, "bimg");
            if (!(bi instanceof BufferedImage)) return null;
            BufferedImage bimg = (BufferedImage) bi;

            AnimU.UType type = AnimU.UType.IDLE;
            EAnimU probe = f.getEAnim(type);
            if (probe == null) {
                type = AnimU.UType.WALK;
                probe = f.getEAnim(type);
            }
            if (probe == null) return null;

            probe.update(false);
            MeasuringGraphics mg = new MeasuringGraphics();
            probe.draw(mg, new P(0f, 0f), 1f);
            float siz = 0.4f;
            P origin = new P(size / 2f, size * 0.9f);
            if (mg.hasBox()) {
                float bw = Math.max(1f, mg.maxX() - mg.minX());
                float bh = Math.max(1f, mg.maxY() - mg.minY());
                float s = Math.min((size * 0.84f) / bw, (size * 0.84f) / bh);
                siz = Math.max(0.05f, Math.min(4f, s));
                float ox = size / 2f - siz * (mg.minX() + mg.maxX()) / 2f;
                float oy = size - 4f - siz * mg.maxY();
                origin = new P(ox, oy);
            }

            Graphics2D clr = bimg.createGraphics();
            clr.setComposite(AlphaComposite.Clear);
            clr.fillRect(0, 0, size, size);
            clr.dispose();

            EAnimU anim = f.getEAnim(type);
            if (anim == null) return null;
            anim.update(false);
            FakeGraphics fg = canvas.getGraphics();
            anim.draw(fg, origin, siz);

            BufferedImage copy = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D cg = copy.createGraphics();
            cg.drawImage(bimg, 0, 0, null);
            cg.dispose();
            return new ImageIcon(copy);
        } catch (Throwable t) {
            return null;
        }
    }
}
