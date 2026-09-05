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

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;

final class AdventureAnimPreview extends JPanel {

    private static final AnimU.UType[] TYPES = {
            AnimU.UType.WALK, AnimU.UType.IDLE, AnimU.UType.ATK, AnimU.UType.HB
    };
    private static final String[] NAMES = {"Walking", "Idle", "Attack", "Hitback"};

    private final Cell[] cells = new Cell[TYPES.length];
    private final Timer timer;

    AdventureAnimPreview() {
        setLayout(new GridLayout(2, 2, 4, 4));
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new Cell(NAMES[i], TYPES[i]);
            add(cells[i]);
        }
        setPreferredSize(new Dimension(2 * (Cell.W + 14), 2 * (Cell.H + 26)));

        timer = new Timer(16, e -> {
            for (Cell c : cells) c.step();
        });
    }

    void setForm(Form f) {
        for (Cell c : cells) c.load(f);
    }

    void start() { timer.start(); }
    void stop() { timer.stop(); }

    private static final class Cell extends JPanel {
        static final int W = 168, H = 150;

        private static final float FRAME_STEP = 0.5f;
        private final AnimU.UType type;
        private EAnimU anim;
        private FakeImage canvas;
        private BufferedImage bimg;
        private float siz = 0.5f;
        private P origin = new P(W / 2f, H * 0.86f);

        Cell(String title, AnimU.UType type) {
            this.type = type;
            setBorder(BorderFactory.createTitledBorder(title));
            setPreferredSize(new Dimension(W + 12, H + 24));
        }

        void load(Form f) {
            anim = null;
            try {
                if (f == null) return;
                EAnimU a = f.getEAnim(type);
                if (a == null) return;
                computeFit(f);
                ensureCanvas();
                anim = a;
            } catch (Throwable ignored) {
                anim = null;
            }
            repaint();
        }

        private void computeFit(Form f) {
            try {
                EAnimU probe = f.getEAnim(type);
                if (probe == null) return;
                probe.update(false);
                MeasuringGraphics mg = new MeasuringGraphics();
                probe.draw(mg, new P(0f, 0f), 1f);
                if (!mg.hasBox()) return;
                float bw = Math.max(1f, mg.maxX() - mg.minX());
                float bh = Math.max(1f, mg.maxY() - mg.minY());
                float s = Math.min((W * 0.82f) / bw, (H * 0.82f) / bh);
                siz = Math.max(0.12f, Math.min(4f, s));
                float ox = W / 2f - siz * (mg.minX() + mg.maxX()) / 2f;
                float oy = H - 8f - siz * mg.maxY();
                origin = new P(ox, oy);
            } catch (Throwable ignored) {}
        }

        private void ensureCanvas() {
            if (canvas != null && bimg != null) return;
            try {
                if (ImageBuilder.builder == null) return;
                canvas = ImageBuilder.builder.build(W, H);
                Object bi = BCUFields.invoke(canvas, "bimg");
                if (bi instanceof BufferedImage) bimg = (BufferedImage) bi;
            } catch (Throwable ignored) {
                canvas = null;
                bimg = null;
            }
        }

        void step() {
            if (anim == null || canvas == null || bimg == null) return;
            try {
                Graphics2D clr = bimg.createGraphics();
                clr.setComposite(AlphaComposite.Clear);
                clr.fillRect(0, 0, W, H);
                clr.dispose();

                float nf = (anim.f < 0f ? 0f : anim.f) + FRAME_STEP;
                int len = 0;
                try { len = anim.len(); } catch (Throwable ignored) {}
                if (len > 0 && nf >= len) nf = nf % len;
                anim.setTime(nf);
                FakeGraphics fg = canvas.getGraphics();
                anim.draw(fg, origin, siz);
                repaint();
            } catch (Throwable ignored) {}
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            java.awt.Insets in = getInsets();
            g.setColor(new java.awt.Color(46, 52, 60));
            g.fillRect(in.left, in.top, W, H);
            if (bimg != null) {
                g.drawImage(bimg, in.left, in.top, null);
            } else {
                g.setColor(java.awt.Color.GRAY);
                g.drawString("-", in.left + W / 2 - 4, in.top + H / 2);
            }
        }
    }
}
