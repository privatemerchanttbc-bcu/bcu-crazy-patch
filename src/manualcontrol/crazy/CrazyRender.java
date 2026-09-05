package manualcontrol.crazy;

import common.system.fake.FakeGraphics;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.lang.reflect.Field;

public final class CrazyRender {

    private static volatile Field graphicsField;

    private CrazyRender() {}

    public static Graphics2D unwrap(FakeGraphics gra) {
        if (gra == null) return null;
        try {
            Field f = graphicsField;
            if (f != null && f.getDeclaringClass().isAssignableFrom(gra.getClass())) {
                Object g = f.get(gra);
                if (g instanceof Graphics2D) return (Graphics2D) g;
            }
            Class<?> c = gra.getClass();
            while (c != null) {
                Field[] fields = c.getDeclaredFields();
                for (int i = 0; i < fields.length; i++) {
                    f = fields[i];
                    if (Graphics2D.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object g = f.get(gra);
                        if (g instanceof Graphics2D) {
                            graphicsField = f;
                            return (Graphics2D) g;
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static float screenX(Object bbpainter, float pos) {
        float siz = BBPainterAccess.getSiz(bbpainter);
        int stagePos = BBPainterAccess.getStagePos(bbpainter);
        return (pos * 0.32f + 200f) * siz + stagePos;
    }

    public static float groundY(Object bbpainter, int layer) {
        return groundY(bbpainter, (float) layer);
    }

    public static float groundY(Object bbpainter, float layer) {
        float siz = BBPainterAccess.getSiz(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);
        return midh - (156 - layer * 4) * siz;
    }

    public static void line(Graphics2D g, float x0, float y0, float x1, float y1,
                            Color color, float width, float alpha) {
        if (g == null) return;
        java.awt.Composite oldComposite = g.getComposite();
        java.awt.Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(alpha)));
            g.setStroke(new BasicStroke(Math.max(1f, width), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(color);
            g.drawLine(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
        } finally {
            g.setComposite(oldComposite);
            g.setStroke(oldStroke);
            g.setColor(oldColor);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    public static void disc(Graphics2D g, float x, float y, float r, Color color, float alpha) {
        if (g == null || r <= 0f) return;
        java.awt.Composite oldComposite = g.getComposite();
        Color oldColor = g.getColor();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(alpha)));
            g.setColor(color);
            int d = Math.round(r * 2f);
            g.fillOval(Math.round(x - r), Math.round(y - r), d, d);
        } finally {
            g.setComposite(oldComposite);
            g.setColor(oldColor);
        }
    }

    public static Object stageFromPainter(Object bbpainter) {
        try {
            return BCUFields.get(bbpainter, "sb");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
