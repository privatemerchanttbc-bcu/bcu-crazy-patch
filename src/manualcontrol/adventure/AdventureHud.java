package manualcontrol.adventure;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;

final class AdventureHud {

    private static final float MARGIN_X = 16f;
    private static final float TOP_Y = 96f;

    private static Field transformDataField;

    private String cachedLevelText;
    private FakeImage cachedLevelTex;
    private String cachedEnemyText;
    private FakeImage cachedEnemyTex;

    void draw(FakeGraphics g, Object bbpainter, AdventureBattle battle) {
        if (g == null) return;
        FakeTransform old = pushIdentity(g);
        try {

            int lives = AdventureRuntime.livesLeft();
            float hx = MARGIN_X, hy = TOP_Y;
            for (int i = 0; i < lives; i++) {
                heart(g, hx + i * 26f, hy, 18f);
            }

            String levelText = battle.levelName();
            if (levelText != null) {
                FakeImage tex = levelTexture(levelText);
                if (tex != null) g.drawImage(tex, MARGIN_X, hy + 26f);
            }

            String enemyText = "Enemies: " + battle.remainingEnemies();
            FakeImage etex = enemyTexture(enemyText);
            if (etex != null) g.drawImage(etex, MARGIN_X, hy + 56f);

            drawPlayerHpBar(g, bbpainter, battle);
            drawArchitectSelector(g, bbpainter, battle);

            drawCoreRow(g, hy + 82f);
            drawPickedBanner(g, bbpainter, battle);
            drawMessage(g, bbpainter);
        } catch (Throwable ignored) {
        } finally {
            popIdentity(g, old);
        }
    }

    private final java.util.Map<String, FakeImage> coreGlyphs = new java.util.HashMap<String, FakeImage>();
    private final java.util.Map<Integer, FakeImage> countTex = new java.util.HashMap<Integer, FakeImage>();
    private FakeImage overflowTex;
    private String bannerUid;
    private FakeImage bannerTex;

    private void drawCoreRow(FakeGraphics g, float y) {
        java.util.List<manualcontrol.adventure.AdventureCore> owned =
                AdventureRuntime.cores().ownedList();
        if (owned.isEmpty()) return;

        java.util.LinkedHashMap<String, manualcontrol.adventure.AdventureCore> firsts =
                new java.util.LinkedHashMap<String, manualcontrol.adventure.AdventureCore>();
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<String, Integer>();
        for (manualcontrol.adventure.AdventureCore c : owned) {
            if (!firsts.containsKey(c.uid)) firsts.put(c.uid, c);
            Integer n = counts.get(c.uid);
            counts.put(c.uid, n == null ? 1 : n + 1);
        }
        float x = MARGIN_X + 11f;
        int drawn = 0, total = firsts.size();
        for (java.util.Map.Entry<String, manualcontrol.adventure.AdventureCore> en : firsts.entrySet()) {
            if (drawn == 9 && total > 10) {
                if (overflowTex == null) {
                    overflowTex = bakeText("+" + (total - 9), new java.awt.Color(220, 226, 234), 12);
                }
                if (overflowTex != null) {
                    g.drawImage(overflowTex, x - overflowTex.getWidth() / 2f, y + 4);
                }
                break;
            }
            manualcontrol.adventure.AdventureCore c = en.getValue();
            drawCoreIcon(g, c, x, y + 11f);
            int n = counts.get(en.getKey());
            if (n > 1) {
                FakeImage ct = countTex.get(n);
                if (ct == null) {
                    ct = bakeText("x" + n, new java.awt.Color(230, 236, 244), 10);
                    if (ct != null) countTex.put(n, ct);
                }
                if (ct != null) g.drawImage(ct, x + 6f, y + 12f);
            }
            x += 28f;
            drawn++;
        }
    }

    private void drawCoreIcon(FakeGraphics g, manualcontrol.adventure.AdventureCore c, float cx, float cy) {
        float r = 11f;
        g.colRect(cx - r + 2, cy - r + 2, (r - 2) * 2, (r - 2) * 2, 16, 19, 25, 210);
        g.setColor(c.tier.r, c.tier.g, c.tier.b);
        g.drawLine(cx, cy - r, cx + r, cy);
        g.drawLine(cx + r, cy, cx, cy + r);
        g.drawLine(cx, cy + r, cx - r, cy);
        g.drawLine(cx - r, cy, cx, cy - r);
        FakeImage glyph = coreGlyphs.get(c.uid);
        if (glyph == null) {
            glyph = bakeText(c.glyph, new java.awt.Color(c.tier.r, c.tier.g, c.tier.b), 13);
            if (glyph != null) coreGlyphs.put(c.uid, glyph);
        }
        if (glyph != null) {
            g.drawImage(glyph, cx - glyph.getWidth() / 2f, cy - glyph.getHeight() / 2f);
        }
        if (c.unique) {
            g.colRect(cx + r - 5, cy - r, 4, 4, 255, 90, 90, 255);
        }
    }

    private FakeImage msgTex;
    private long msgUntilMs;

    void showMessage(String text, int r, int g, int b) {
        try {
            msgTex = bakeText(text, new java.awt.Color(r, g, b), 18);
            msgUntilMs = System.currentTimeMillis() + 2500L;
        } catch (Throwable ignored) {}
    }

    private void drawMessage(FakeGraphics g, Object bbpainter) {
        if (msgTex == null || System.currentTimeMillis() > msgUntilMs) return;
        try {
            int w = manualcontrol.reflect.BBPainterAccess.getWidth(bbpainter);
            g.drawImage(msgTex, w / 2f - msgTex.getWidth() / 2f, 148);
        } catch (Throwable ignored) {}
    }

    private void drawPickedBanner(FakeGraphics g, Object bbpainter, AdventureBattle battle) {
        manualcontrol.adventure.AdventureCore c = AdventureRuntime.lastPicked();
        if (c == null) return;
        int t = battle.sb.time;
        if (t > 130) return;
        if (!c.uid.equals(bannerUid) || bannerTex == null) {
            bannerUid = c.uid;
            bannerTex = bakeText("PICKED:  " + c.name.toUpperCase(java.util.Locale.ROOT),
                    new java.awt.Color(c.tier.r, c.tier.g, c.tier.b), 19);
        }
        if (bannerTex == null) return;
        int w = manualcontrol.reflect.BBPainterAccess.getWidth(bbpainter);
        int alpha = t > 100 ? Math.max(0, 255 - (t - 100) * 8) : 255;
        try {
            g.setComposite(common.system.fake.FakeGraphics.TRANS, alpha, 0);
            g.drawImage(bannerTex, w / 2f - bannerTex.getWidth() / 2f, 118);
            g.setComposite(common.system.fake.FakeGraphics.DEF, 0, 0);
        } catch (Throwable ignored) {}
    }

    private void drawPlayerHpBar(FakeGraphics g, Object bbpainter, AdventureBattle battle) {
        common.battle.entity.EUnit player = battle.player();
        if (player == null || bbpainter == null) return;
        try {
            if (player.dead || player.maxH <= 0L) return;
            float siz = manualcontrol.reflect.BBPainterAccess.getSiz(bbpainter);
            int sbPos = manualcontrol.reflect.BBPainterAccess.getStagePos(bbpainter);
            int midh = manualcontrol.reflect.BBPainterAccess.getMidh(bbpainter);
            if (siz <= 0.0001f) return;

            float rootX = (player.pos * 0.32f + 200f) * siz + sbPos;
            float ground = midh - (156 - player.currentLayer * 4) * siz;
            float w = 76f * Math.max(0.5f, siz);
            float h = 9f * Math.max(0.5f, siz);

            float scale = manualcontrol.adventure.AdventureBridge.drawScaleFor(player);
            float c = manualcontrol.adventure.AdventureBridge.mirrorCenter(player);
            float centerX = rootX + (c == c ? c * siz * scale : 0f);
            float x = centerX - w / 2f;
            float t = manualcontrol.adventure.AdventureBridge.playerSpriteTop();
            float y = (t == t)
                    ? ground + t * siz * scale - h - 10f * Math.max(0.5f, siz)
                    : ground - 150f * Math.max(0.5f, siz);

            float frac = Math.max(0f, Math.min(1f, (float) player.health / (float) player.maxH));

            g.colRect(x - 1f, y - 1f, w + 2f, h + 2f, 0, 0, 0, 190);

            int r = frac > 0.5f ? Math.round(510f * (1f - frac)) : 255;
            int gr = frac > 0.5f ? 220 : Math.round(440f * frac);
            g.colRect(x, y, w * frac, h, Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, gr)), 60, 255);
            g.setColor(230, 230, 230);
            g.drawRect(x, y, w, h);
            float gap = 2f * Math.max(0.5f, siz);
            float resourceH = 5f * Math.max(0.5f, siz);
            float resourceY = y + h + gap;
            if (drawTeleportStamina(g, battle.teleport, x, resourceY, w, resourceH)) {
                resourceY += resourceH + gap;
            }
            drawTemporalDebt(g, battle, player, x, resourceY, w, resourceH);
        } catch (Throwable ignored) {}
    }

    private boolean drawTeleportStamina(FakeGraphics g, AdventureTeleport teleport,
                                        float x, float y, float w, float h) {
        if (teleport == null || teleport.rank() <= 0 || teleport.maxCharges() <= 0) return false;
        int segments = teleport.maxCharges();
        int ready = Math.max(0, Math.min(segments, teleport.charges()));
        float progress = teleport.rechargeProgress();
        float segmentW = w / segments;

        g.colRect(x - 1f, y - 1f, w + 2f, h + 2f, 0, 0, 0, 205);
        g.colRect(x, y, w, h, 8, 26, 50, 235);
        for (int i = 0; i < segments; i++) {
            float fill = i < ready ? 1f : i == ready && ready < segments ? progress : 0f;
            float sx = x + segmentW * i;
            float insetLeft = i == 0 ? 0f : 1f;
            float usable = Math.max(0f, segmentW - insetLeft);
            if (fill > 0f) {
                float fillW = usable * fill;
                g.colRect(sx + insetLeft, y, fillW, h, 35, 135, 245, 255);
                g.colRect(sx + insetLeft, y, fillW, Math.max(1f, h * 0.35f),
                        125, 220, 255, 235);
            }
            if (i > 0) {
                g.colRect(sx, y, 1f, h, 0, 8, 18, 255);
            }
        }
        g.setColor(185, 230, 255);
        g.drawRect(x, y, w, h);
        return true;
    }

    private void drawTemporalDebt(FakeGraphics g, AdventureBattle battle,
                                  common.battle.entity.EUnit player,
                                  float x, float y, float w, float h) {
        if (AdventureRuntime.cores().temporalDebtPct() <= 0f || player.maxH <= 0L) return;
        float fraction = Math.max(0f, Math.min(1f,
                battle.advancedCores.debtTotal() / (float) player.maxH));
        boolean urgent = battle.advancedCores.debtDueSoon();
        int pulse = urgent ? 150 + Math.abs((battle.sb.time % 10) - 5) * 20 : 225;
        int cells = 10;
        float cellW = w / cells;
        g.colRect(x - 1f, y - 1f, w + 2f, h + 2f, 0, 0, 0, 215);
        g.colRect(x, y, w, h, 38, 8, 12, 235);
        for (int i = 0; i < cells; i++) {
            float fill = Math.max(0f, Math.min(1f, fraction * cells - i));
            float sx = x + i * cellW;
            if (fill > 0f) {
                g.colRect(sx + (i == 0 ? 0f : 1f), y,
                        Math.max(0f, (cellW - (i == 0 ? 0f : 1f)) * fill), h,
                        245, 48, 62, pulse);
            }
            if (i > 0) g.colRect(sx, y, 1f, h, 10, 2, 4, 255);
        }
        g.setColor(255, urgent ? 185 : 105, urgent ? 185 : 115);
        g.drawRect(x, y, w, h);
    }

    private final java.util.Map<String, FakeImage> architectLabels =
            new java.util.HashMap<String, FakeImage>();

    private void drawArchitectSelector(FakeGraphics g, Object bbpainter, AdventureBattle battle) {
        if (battle.architect.rank() <= 0) return;
        try {
            int width = manualcontrol.reflect.BBPainterAccess.getWidth(bbpainter);
            int height = manualcontrol.reflect.BBPainterAccess.getHeight(bbpainter);
            float size = 54f;
            float x = width * 0.5f - size * 0.5f;
            float y = height - 82f;
            FakeImage icon = battle.coreVfx.architectTexture(battle.architect.selectedType());
            g.colRect(x - 4f, y - 4f, size + 8f, size + 8f, 5, 10, 14, 220);
            if (icon != null) g.drawImage(icon, x, y, size, size);
            float cd = battle.architect.cooldownMaxTicks() <= 0 ? 0f
                    : battle.architect.cooldownTicks()
                    / (float) battle.architect.cooldownMaxTicks();
            if (cd > 0f) g.colRect(x, y, size, size * cd, 4, 10, 16, 185);
            int readySegments = Math.max(0, Math.min(8, Math.round((1f - cd) * 8f)));
            for (int i = 0; i < 8; i++) {
                int r = i < readySegments ? 48 : 55;
                int gg = i < readySegments ? 225 : 68;
                int b = i < readySegments ? 245 : 74;
                if (i < 2) g.colRect(x + i * size / 2f, y - 3f, size / 2f - 1f, 2f, r, gg, b, 245);
                else if (i < 4) g.colRect(x + size + 1f, y + (i - 2) * size / 2f, 2f, size / 2f - 1f, r, gg, b, 245);
                else if (i < 6) g.colRect(x + (5 - i) * size / 2f, y + size + 1f, size / 2f - 1f, 2f, r, gg, b, 245);
                else g.colRect(x - 3f, y + (7 - i) * size / 2f, 2f, size / 2f - 1f, r, gg, b, 245);
            }
            String label = battle.architect.selectedName();
            FakeImage text = architectLabels.get(label);
            if (text == null) {
                text = bakeText(label, new Color(205, 238, 242), 11);
                if (text != null) architectLabels.put(label, text);
            }
            if (text != null) g.drawImage(text,
                    width * 0.5f - text.getWidth() / 2f, y + size + 5f);
        } catch (Throwable ignored) {}
    }

    private FakeImage levelTexture(String text) {
        if (text.equals(cachedLevelText) && cachedLevelTex != null) return cachedLevelTex;
        FakeImage tex = bakeText(text, new Color(255, 236, 150), 20);
        if (tex != null) {
            cachedLevelText = text;
            cachedLevelTex = tex;
        }
        return cachedLevelTex;
    }

    private FakeImage enemyTexture(String text) {
        if (text.equals(cachedEnemyText) && cachedEnemyTex != null) return cachedEnemyTex;
        FakeImage tex = bakeText(text, new Color(235, 235, 235), 18);
        if (tex != null) {
            cachedEnemyText = text;
            cachedEnemyTex = tex;
        }
        return cachedEnemyTex;
    }

    private static void heart(FakeGraphics g, float x, float y, float s) {

        g.colRect(x, y + s * 0.15f, s * 0.45f, s * 0.5f, 230, 40, 60, 255);
        g.colRect(x + s * 0.55f, y + s * 0.15f, s * 0.45f, s * 0.5f, 230, 40, 60, 255);
        g.colRect(x + s * 0.1f, y + s * 0.4f, s * 0.8f, s * 0.45f, 230, 40, 60, 255);
        g.colRect(x + s * 0.28f, y + s * 0.7f, s * 0.44f, s * 0.28f, 230, 40, 60, 255);
    }

    static FakeImage bakeText(String text, Color color, int fontPx) {
        try {
            if (ImageBuilder.builder == null || text == null || text.isEmpty()) return null;
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontPx);
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D pg = probe.createGraphics();
            pg.setFont(font);
            java.awt.FontMetrics fm = pg.getFontMetrics();
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
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, bi.getRGB(x, y));
                }
            }
            return img;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static FakeTransform pushIdentity(FakeGraphics gra) {
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

    static void popIdentity(FakeGraphics gra, FakeTransform oldTransform) {
        if (oldTransform == null) return;
        try {
            gra.setTransform(oldTransform);
        } catch (Throwable ignored) {
        } finally {
            try { gra.delete(oldTransform); } catch (Throwable ignored) {}
        }
    }
}
