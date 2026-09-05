package manualcontrol.adventure;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;

final class AdventureLandingVfxDialog {

    private static final Color PANEL_BG = new Color(22, 25, 31);
    private static final Color CARD_BG = new Color(31, 35, 43);
    private static final Color SELECTED = new Color(90, 210, 255);

    private AdventureLandingVfxDialog() {}

    static AdventureLandingVfx choose(Component parent, AdventureLandingVfx current) {
        final AdventureLandingVfx initial =
                current == null ? AdventureLandingVfx.CRYSTAL : current;
        final AdventureLandingVfx[] styles = AdventureLandingVfx.values();
        final JRadioButton[] radios = new JRadioButton[styles.length];
        final JPanel[] cards = new JPanel[styles.length];
        final ButtonGroup group = new ButtonGroup();

        JPanel options = new JPanel();
        options.setBackground(PANEL_BG);
        options.setLayout(new BoxLayout(options, BoxLayout.X_AXIS));
        options.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        for (int i = 0; i < styles.length; i++) {
            final AdventureLandingVfx style = styles[i];
            final JRadioButton radio = new JRadioButton(style.displayName);
            radio.setForeground(Color.WHITE);
            radio.setBackground(CARD_BG);
            radio.setFont(radio.getFont().deriveFont(Font.BOLD, 14f));
            radio.setFocusPainted(false);
            group.add(radio);
            radios[i] = radio;

            JLabel description = new JLabel(
                    "<html><div style='width:224px;color:#c7ccd6'>"
                            + style.description + "</div></html>");
            description.setFont(description.getFont().deriveFont(12f));

            JPanel card = new JPanel(new BorderLayout(6, 6));
            card.setBackground(CARD_BG);
            card.setBorder(cardBorder(style == initial));
            card.add(radio, BorderLayout.NORTH);
            card.add(new Preview(style), BorderLayout.CENTER);
            card.add(description, BorderLayout.SOUTH);
            card.setPreferredSize(new Dimension(260, 220));
            cards[i] = card;
            options.add(card);
            if (i + 1 < styles.length) {
                JPanel gap = new JPanel();
                gap.setOpaque(false);
                gap.setPreferredSize(new Dimension(8, 1));
                gap.setMaximumSize(new Dimension(8, Integer.MAX_VALUE));
                options.add(gap);
            }

            radio.setSelected(style == initial);
            bindSelection(card, radio);
        }

        ActionListener refresh = new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                for (int i = 0; i < cards.length; i++) {
                    cards[i].setBorder(cardBorder(radios[i].isSelected()));
                    cards[i].repaint();
                }
            }
        };
        for (JRadioButton radio : radios) radio.addActionListener(refresh);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(PANEL_BG);
        JLabel heading = new JLabel(
                "<html><b style='font-size:16px;color:white'>Choose Bouncy Castle landing VFX</b>"
                        + "<br><span style='color:#aeb5c2'>This choice is saved for this Adventure slot.</span></html>");
        heading.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        root.add(heading, BorderLayout.NORTH);
        root.add(options, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(parent, root,
                "Bouncy Castle VFX", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;
        for (int i = 0; i < styles.length; i++) {
            if (radios[i].isSelected()) return styles[i];
        }
        return initial;
    }

    private static javax.swing.border.Border cardBorder(boolean selected) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected ? SELECTED : new Color(66, 72, 84),
                        selected ? 3 : 1),
                BorderFactory.createEmptyBorder(selected ? 7 : 9, selected ? 7 : 9,
                        selected ? 7 : 9, selected ? 7 : 9));
    }

    private static void bindSelection(Component component, final JRadioButton radio) {
        if (component != radio) {
            component.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!radio.isSelected()) radio.doClick(0);
                }
            });
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                bindSelection(child, radio);
            }
        }
    }

    private static final class Preview extends JComponent {
        private final AdventureLandingVfx style;
        private float phase;
        private final Timer timer;

        Preview(AdventureLandingVfx style) {
            this.style = style;
            setPreferredSize(new Dimension(236, 142));
            setMinimumSize(new Dimension(180, 130));
            timer = new Timer(40, new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    phase += 0.024f;
                    if (phase >= 1f) phase -= 1f;
                    repaint();
                }
            });
        }

        @Override public void addNotify() {
            super.addNotify();
            timer.start();
        }

        @Override public void removeNotify() {
            timer.stop();
            super.removeNotify();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int w = getWidth(), h = getHeight();
                float cycle = phase < 0.88f ? phase / 0.88f : 1f;
                AdventureLandingVfxRenderer.paintPreview(g, style, w, h,
                        Math.round(cycle * AdventureLandingVfxRenderer.DURATION));
            } finally {
                g.dispose();
            }
        }

        private void paintCrystal(Graphics2D g, float cx, float ground, float t) {
            ring(g, cx, ground - 3f, 20f + t * 92f, 10f + t * 21f,
                    new Color(95, 225, 255, alpha(1f - t)), 2.4f);
            ring(g, cx, ground - 3f, 10f + t * 70f, 5f + t * 15f,
                    new Color(235, 255, 255, alpha(1f - t)), 1.4f);
            g.setColor(new Color(80, 205, 245, 220));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 7; i++) {
                double a = Math.PI + i * Math.PI / 6.0;
                float x = cx + (float) Math.cos(a) * (22f + 18f * t);
                float y = ground + (float) Math.sin(a) * (12f + 7f * t);
                Path2D.Float shard = new Path2D.Float();
                shard.moveTo(x, y);
                shard.lineTo(x + (float) Math.cos(a) * 24f, y + (float) Math.sin(a) * 35f);
                shard.lineTo(x + (float) Math.cos(a + 0.35) * 10f,
                        y + (float) Math.sin(a + 0.35) * 12f);
                shard.closePath();
                g.draw(shard);
            }
            cracks(g, cx, ground, new Color(110, 225, 255, 185), 6);
        }

        private void paintSolar(Graphics2D g, float cx, float ground, float t) {
            int beamA = alpha(Math.max(0f, 1f - t * 1.7f));
            g.setColor(new Color(255, 195, 65, Math.max(0, beamA / 3)));
            g.fillRect(Math.round(cx - 20f + t * 14f), 4,
                    Math.round(40f - t * 28f), Math.round(ground - 4f));
            g.setColor(new Color(255, 250, 215, beamA));
            g.fillRect(Math.round(cx - 5f), 4, 10, Math.round(ground - 4f));
            for (int i = 0; i < 3; i++) {
                float local = Math.max(0f, Math.min(1f, t * 1.35f - i * 0.17f));
                ring(g, cx, ground - 2f, 14f + local * (78f + i * 12f),
                        5f + local * (17f + i * 2f),
                        new Color(255, 185 + i * 20, 65, alpha(1f - local)), 2.4f - i * 0.4f);
            }
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 9; i++) {
                double a = Math.PI + i * Math.PI / 8.0;
                float len = 28f + t * (28f + (i % 3) * 9f);
                g.setColor(new Color(255, 215, 105, 180));
                g.drawLine(Math.round(cx + (float) Math.cos(a) * 11f),
                        Math.round(ground + (float) Math.sin(a) * 5f),
                        Math.round(cx + (float) Math.cos(a) * len),
                        Math.round(ground + (float) Math.sin(a) * len * 0.42f));
            }
        }

        private void paintVoid(Graphics2D g, float cx, float ground, float t) {
            ring(g, cx, ground - 4f, 16f + t * 96f, 7f + t * 23f,
                    new Color(174, 82, 255, alpha(1f - t)), 3f);
            ring(g, cx, ground - 4f, 8f + t * 68f, 4f + t * 16f,
                    new Color(84, 235, 255, alpha(1f - t)), 1.5f);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < 3; i++) {
                    float x0 = cx + side * (9f + i * 11f);
                    float x1 = cx + side * (54f + i * 15f);
                    float y0 = ground - 10f - i * 7f;
                    Path2D.Float bolt = new Path2D.Float();
                    bolt.moveTo(x0, y0);
                    bolt.lineTo((x0 + x1) * 0.5f - side * 7f, y0 - 18f);
                    bolt.lineTo((x0 + x1) * 0.62f + side * 5f, y0 - 9f);
                    bolt.lineTo(x1, ground - 4f - i * 2f);
                    g.setColor(i % 2 == 0
                            ? new Color(90, 240, 255, 220)
                            : new Color(190, 95, 255, 210));
                    g.draw(bolt);
                }
            }
            g.setColor(new Color(185, 90, 255, 180));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(Math.round(cx - 87f), Math.round(ground - 46f), 174, 52, 198, 144);
        }

        private static void paintPlayer(Graphics2D g, float cx, float ground) {
            g.setColor(new Color(235, 239, 245));
            g.fillOval(Math.round(cx - 7f), Math.round(ground - 47f), 14, 14);
            g.setColor(new Color(172, 181, 196));
            g.fillRoundRect(Math.round(cx - 9f), Math.round(ground - 34f), 18, 27, 5, 5);
        }

        private static void cracks(Graphics2D g, float cx, float ground, Color color, int count) {
            g.setColor(color);
            for (int i = 0; i < count; i++) {
                float dir = i < count / 2 ? -1f : 1f;
                float spread = 20f + (i % 3) * 16f;
                Path2D.Float p = new Path2D.Float();
                p.moveTo(cx + dir * 8f, ground);
                p.lineTo(cx + dir * spread * 0.45f, ground - 3f - (i % 2) * 3f);
                p.lineTo(cx + dir * spread * 0.72f, ground + 1f);
                p.lineTo(cx + dir * spread, ground - 2f);
                g.draw(p);
            }
        }

        private static void ring(Graphics2D g, float cx, float cy, float rx, float ry,
                                 Color color, float stroke) {
            if (color.getAlpha() <= 0) return;
            g.setColor(color);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(Math.round(cx - rx), Math.round(cy - ry),
                    Math.max(1, Math.round(rx * 2f)), Math.max(1, Math.round(ry * 2f)));
        }

        private static int alpha(float value) {
            return Math.max(0, Math.min(255, Math.round(value * 235f)));
        }

        private static float easeOut(float t) {
            float inv = 1f - Math.max(0f, Math.min(1f, t));
            return 1f - inv * inv * inv;
        }
    }
}
