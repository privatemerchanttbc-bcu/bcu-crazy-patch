package manualcontrol.adventure;

import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import manualcontrol.reflect.BBPainterAccess;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class AdventureCoreVfx {

    private static final int MAX_PARTICLES = 96;
    private static final int MAX_BURSTS = 24;

    private static final int SCORCH = 0;
    private static final int BURN_PULSE = 1;
    private static final int BREAKER = 2;
    private static final int POISE_BREAK = 3;
    private static final int SUPPRESS = 4;
    private static final int RELAY = 5;
    private static final int CONDUIT = 6;
    private static final int DEBT = 7;
    private static final int DEBT_MATURE = 8;
    private static final int ACROBAT = 9;
    private static final int RELENTLESS = 10;
    private static final int ARCH_INSTALL = 11;
    private static final int ARCH_LOCK = 12;
    private static final int ARCH_BREAK = 13;
    private static final int ARCH_HIT = 14;
    private static final int LAUNCH = 15;
    private static final int CONDUIT_NODE = 16;
    private static final int ARCH_EXPIRE = 17;
    private static final int ARCH_EXPIRE_SCAN = 18;
    private static final int ARCH_DESTROY_FLASH = 19;

    private static final String ROOT = "/manualcontrol/adventure/vfx/";
    private static final String[] ARCH_FILES = {
            "architect_wall.png", "architect_platform.png", "architect_gate.png",
            "architect_launch_pad.png", "architect_anchor.png"
    };

    private static final class Fx {
        int kind;
        float x0;
        float x1;
        int layer;
        int age;
        int life;
        float scale;
        int variant;
        int intensity;
    }

    private final AdventureBattle battle;
    private final List<Fx> particles = new ArrayList<Fx>();
    private final List<Fx> bursts = new ArrayList<Fx>();
    private final Map<String, FakeImage> textures = new HashMap<String, FakeImage>();
    private final FakeImage[][] blueprints = new FakeImage[AdventureArchitect.TYPE_COUNT][2];

    AdventureCoreVfx(AdventureBattle battle) {
        this.battle = battle;
    }

    void onLevelStart() {
        particles.clear();
        bursts.clear();
    }

    void tick() {
        tickList(particles);
        tickList(bursts);
    }

    private static void tickList(List<Fx> list) {
        Iterator<Fx> it = list.iterator();
        while (it.hasNext()) {
            Fx fx = it.next();
            if (++fx.age >= fx.life) it.remove();
        }
    }

    void spawnScorch(EEnemy enemy) { spawnEntity(SCORCH, enemy, 24, 0.8f, false); }
    void spawnBurnPulse(EEnemy enemy) { spawnEntity(BURN_PULSE, enemy, 16, 1f, true); }
    void spawnBreaker(EEnemy enemy, float meter) {
        spawnEntity(BREAKER, enemy, 18, 0.75f + meter * 0.25f, false);
    }
    void spawnPoiseBreak(EEnemy enemy) { spawnEntity(POISE_BREAK, enemy, 22, 1.2f, true); }
    void spawnSuppress(EEnemy enemy) { spawnEntity(SUPPRESS, enemy, 28, 0.9f, false); }
    void spawnDebt(EUnit player) { spawnEntity(DEBT, player, 24, 0.9f, false); }
    void spawnDebtMature(EUnit player) { spawnEntity(DEBT_MATURE, player, 22, 1.2f, true); }
    void spawnAcrobat(EUnit player) { spawnEntity(ACROBAT, player, 18, 1f, true); }
    void spawnSkirmisher(EUnit player) { spawnEntity(ACROBAT, player, 10, 0.75f, false); }
    void spawnRelentless(EUnit player) { spawnEntity(RELENTLESS, player, 26, 1f, true); }
    void spawnConduitNode(EEnemy enemy) { spawnEntity(CONDUIT_NODE, enemy, 28, 0.75f, false); }

    void spawnRelay(EEnemy from, EEnemy to) {
        if (from == null || to == null) return;
        add(RELAY, from.pos, to.pos, from.currentLayer, 18, 1f, true, 0);
    }

    void spawnConduit(EEnemy from, EEnemy to) {
        if (from == null || to == null) return;
        add(CONDUIT, from.pos, to.pos, from.currentLayer, 16, 1f, true, 0);
    }

    void spawnArchitectInstall(float x, int layer, int type) {
        add(ARCH_INSTALL, x, x, layer, 24, 1f, true, type);
    }
    void spawnArchitectLock(float x, int layer, int type) {
        add(ARCH_LOCK, x, x, layer, 20, 1f, true, type);
    }
    void spawnArchitectDestroyed(float x, int layer, int type) {
        add(ARCH_DESTROY_FLASH, x, x, layer, 10, 1f, true, type);
        add(ARCH_BREAK, x, x, layer, 24, 1f, true, type);
    }
    void spawnArchitectExpired(float x, int layer, int type) {
        add(ARCH_EXPIRE, x, x, layer, 26, 1f, true, type);
        add(ARCH_EXPIRE_SCAN, x, x, layer, 22, 1f, true, type);
    }
    void spawnArchitectHit(float x, int layer, int type) {
        add(ARCH_HIT, x, x, layer, 12, 0.8f, false, type);
    }
    void spawnLaunch(float x, int layer) { add(LAUNCH, x, x, layer, 18, 1f, true, 0); }

    private void spawnEntity(int kind, common.battle.entity.Entity entity,
                             int life, float scale, boolean burst) {
        if (entity == null) return;
        add(kind, entity.pos, entity.pos, entity.currentLayer, life, scale, burst, 0);
    }

    private void add(int kind, float x0, float x1, int layer, int life,
                     float scale, boolean burst, int variant) {
        List<Fx> list = burst ? bursts : particles;
        int cap = burst ? MAX_BURSTS : MAX_PARTICLES;
        if (list.size() >= cap) return;

        for (int i = list.size() - 1; i >= 0; i--) {
            Fx old = list.get(i);
            if (old.kind == kind && old.age <= 3 && Math.abs(old.x0 - x0) < 20f) {
                old.age = 0;
                old.life = Math.max(old.life, life);
                old.scale = Math.max(old.scale, scale);
                return;
            }
        }
        Fx fx = new Fx();
        fx.kind = kind;
        fx.x0 = x0;
        fx.x1 = x1;
        fx.layer = layer;
        fx.life = Math.max(1, life);
        fx.scale = scale;
        fx.variant = variant;
        fx.intensity = intensityFor(kind);
        list.add(fx);
    }

    private int intensityFor(int kind) {
        AdventureCoreState cores = AdventureRuntime.cores();
        float ratio;
        switch (kind) {
            case SCORCH:
            case BURN_PULSE: ratio = cores.scorchPct() / 1.5f; break;
            case BREAKER:
            case POISE_BREAK: ratio = cores.breakerPoise() / 100f; break;
            case SUPPRESS: ratio = cores.suppressionPct() / 0.6f; break;
            case RELAY: ratio = cores.impactRelayPct() / 0.9f; break;
            case CONDUIT:
            case CONDUIT_NODE: ratio = cores.conduitPct() / 0.6f; break;
            case ACROBAT: ratio = Math.max(cores.skirmisherPct(), cores.acrobatPct()); break;
            case RELENTLESS: ratio = cores.relentlessChance(); break;
            case DEBT:
            case DEBT_MATURE: ratio = cores.temporalDebtPct() / 0.8f; break;
            default: ratio = cores.architectRank() / 5f; break;
        }
        return ratio <= 0.33f ? 1 : ratio <= 0.66f ? 2 : 3;
    }

    void drawGround(Object bbpainter, FakeGraphics g) {
        drawList(bbpainter, g, bursts, true);
    }

    void drawWorldOverlay(Object bbpainter, FakeGraphics g) {
        drawList(bbpainter, g, particles, false);
        drawList(bbpainter, g, bursts, false);
        drawPersistentStatuses(bbpainter, g);
    }

    private void drawPersistentStatuses(Object bbpainter, FakeGraphics g) {
        if (g == null || bbpainter == null) return;
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            int width = BBPainterAccess.getWidth(bbpainter);
            int pulse = 145 + Math.abs((battle.sb.time % 16) - 8) * 8;
            for (EEnemy enemy : battle.liveEnemies()) {
                if (enemy == null || enemy.dead || enemy.health <= 0L) continue;
                float x = worldX(enemy.pos, siz, sbPos);
                if (x < -100f || x > width + 100f) continue;
                float ground = midh - (156 - enemy.currentLayer * 4) * siz;
                float y = ground - 92f * siz;
                if (battle.advancedCores.isBurning(enemy)) {
                    drawStatus(g, texture("flame.png"), x - 24f * siz, y,
                            30f * siz, pulse);
                }
                float poise = battle.advancedCores.poiseFraction(enemy);
                if (poise > 0f) {
                    drawStatus(g, texture("poise_arc.png"), x, y - 12f * siz,
                            (38f + 16f * poise) * siz, 150 + Math.round(90f * poise));
                }
                if (battle.advancedCores.isSuppressed(enemy)) {
                    drawStatus(g, texture("suppress_seal.png"), x + 23f * siz, y,
                            28f * siz, pulse);
                }
                if (battle.advancedCores.isLinked(enemy)) {
                    drawStatus(g, texture("conduit_node.png"), x, y - 37f * siz,
                            22f * siz, pulse + 20);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void drawStatus(FakeGraphics g, FakeImage image, float cx, float cy,
                                   float size, int alpha) {
        if (image == null) return;
        float h = size * image.getHeight() / Math.max(1f, image.getWidth());
        g.setComposite(FakeGraphics.TRANS, Math.max(0, Math.min(255, alpha)), 0);
        g.drawImage(image, cx - size / 2f, cy - h / 2f, size, h);
        g.setComposite(FakeGraphics.DEF, 0, 0);
    }

    private void drawList(Object bbpainter, FakeGraphics g, List<Fx> list, boolean groundOnly) {
        if (g == null || bbpainter == null) return;
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int sbPos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            int width = BBPainterAccess.getWidth(bbpainter);
            for (Fx fx : list) {
                boolean ground = isGroundFx(fx.kind);
                if (ground != groundOnly) continue;
                float sx0 = worldX(fx.x0, siz, sbPos);
                float sx1 = worldX(fx.x1, siz, sbPos);
                if (Math.max(sx0, sx1) < -180f || Math.min(sx0, sx1) > width + 180f) continue;
                float groundY = midh - (156 - fx.layer * 4) * siz;
                drawFx(g, fx, sx0, sx1, groundY, siz);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isGroundFx(int kind) {
        return kind >= ARCH_INSTALL && kind <= LAUNCH
                || kind == ARCH_EXPIRE || kind == ARCH_EXPIRE_SCAN
                || kind == ARCH_DESTROY_FLASH;
    }

    private void drawFx(FakeGraphics g, Fx fx, float x0, float x1, float ground, float siz) {
        float p = Math.max(0f, Math.min(1f, fx.age / (float) fx.life));
        float intensityAlpha = 0.72f + 0.14f * Math.max(1, Math.min(3, fx.intensity));
        int alpha = Math.max(0, Math.min(255,
                Math.round(255f * (1f - p) * (1f - p) * intensityAlpha)));
        String file = null;
        FakeImage image = null;
        float w = 58f * siz * fx.scale;
        float h = w;
        float cx = (x0 + x1) * 0.5f;
        float cy = ground - 72f * siz;
        switch (fx.kind) {
            case SCORCH: file = "flame.png"; cy -= 18f * siz; break;
            case BURN_PULSE: file = "ember.png"; w *= 1.4f; h *= 1.4f; break;
            case BREAKER: file = "poise_arc.png"; w *= 1.35f; h *= 1.35f; break;
            case POISE_BREAK: file = "poise_shatter.png"; w *= 2f; h *= 2f; break;
            case SUPPRESS: file = "suppress_seal.png"; w *= 1.3f; h *= 1.3f; break;
            case RELAY: file = "relay_impact.png"; w *= 1.8f; h *= 1.8f; break;
            case CONDUIT:
                file = "conduit_ribbon.png";
                w = Math.max(12f, Math.abs(x1 - x0));
                h = 24f * siz * fx.scale;
                cy -= 14f * siz;
                break;
            case DEBT: file = "debt_shard.png"; break;
            case DEBT_MATURE: file = "debt_clock.png"; w *= 1.7f; h *= 1.7f; break;
            case ACROBAT: file = "motion_crescent.png"; w *= 1.5f; h *= 1.2f; break;
            case RELENTLESS: file = "relentless_brace.png"; w *= 1.5f; h *= 1.5f; break;
            case ARCH_INSTALL:
                file = "install_scan.png";
                w = (60f + fx.age * 3f) * siz * AdventureArchitect.VISUAL_SCALE;
                h = (18f + fx.age * 2.4f) * siz * AdventureArchitect.VISUAL_SCALE;
                cy = ground - h * 0.5f;
                alpha = Math.max(35, Math.round(220f * (1f - p * 0.4f)));
                break;
            case ARCH_LOCK:
                file = "architect_lock.png";
                w *= 2f * AdventureArchitect.VISUAL_SCALE;
                h *= 2f * AdventureArchitect.VISUAL_SCALE;
                cy = ground - h * 0.45f;
                break;
            case ARCH_BREAK:
                file = "architect_debris.png";
                w = architectVisualWidth(fx.variant, siz) * (0.72f + 0.58f * p);
                h = w * 0.895f;
                cy = ground - h * (0.34f - 0.08f * p);
                alpha = Math.max(0, Math.round(255f * (1f - p) * (1f - p)));
                break;
            case ARCH_HIT: file = "relay_impact.png"; cy = ground - 45f * siz; break;
            case LAUNCH: file = "launch_energy.png"; w *= 1.7f; h *= 1.8f; cy = ground - h * 0.5f; break;
            case CONDUIT_NODE: file = "conduit_node.png"; w *= 0.8f; h *= 0.8f; break;
            case ARCH_DESTROY_FLASH:
                file = "relay_impact.png";
                w = architectVisualWidth(fx.variant, siz) * (0.45f + 0.85f * p);
                h = w;
                cy = ground - h * 0.42f;
                alpha = Math.max(0, Math.round(245f * (1f - p)));
                break;
            case ARCH_EXPIRE:
                image = architectBlueprint(fx.variant, true);
                w = architectVisualWidth(fx.variant, siz) * (1f - 0.08f * p);
                if (image != null) h = w * image.getHeight() / Math.max(1f, image.getWidth());
                cy = ground - h * 0.5f - 18f * siz * p;
                alpha = Math.max(0, Math.round(210f * (1f - p) * (1f - p)));
                break;
            case ARCH_EXPIRE_SCAN:
                file = "install_scan.png";
                w = architectVisualWidth(fx.variant, siz) * (1f - 0.5f * p);
                h = Math.max(14f * siz, w * 0.18f);
                cy = ground - architectVisualHeight(fx.variant, siz) * p;
                alpha = Math.max(0, Math.round(230f * (1f - p)));
                break;
            default: return;
        }
        if (image == null && file != null) image = texture(file);
        if (image == null) return;
        g.setComposite(FakeGraphics.TRANS, alpha, 0);
        g.drawImage(image, cx - w / 2f, cy - h / 2f, w, h);
        g.setComposite(FakeGraphics.DEF, 0, 0);
    }

    private float architectVisualWidth(int type, float siz) {
        int i = Math.max(0, Math.min(AdventureArchitect.TYPE_COUNT - 1, type));
        return AdventureArchitect.visualWidth(i, siz);
    }

    private float architectVisualHeight(int type, float siz) {
        FakeImage image = architectTexture(type);
        if (image == null) return architectVisualWidth(type, siz);
        return architectVisualWidth(type, siz)
                * image.getHeight() / Math.max(1f, image.getWidth());
    }

    FakeImage architectTexture(int type) {
        int i = Math.max(0, Math.min(ARCH_FILES.length - 1, type));
        return texture(ARCH_FILES[i]);
    }

    FakeImage anchorFieldTexture() { return texture("install_scan.png"); }

    FakeImage architectBlueprint(int type, boolean valid) {
        int i = Math.max(0, Math.min(AdventureArchitect.TYPE_COUNT - 1, type));
        int state = valid ? 1 : 0;
        FakeImage cached = blueprints[i][state];
        if (cached != null) return cached;
        BufferedImage source = loadBuffered(ARCH_FILES[i]);
        if (source == null) source = fallbackBuffered(ARCH_FILES[i]);
        if (source == null) return architectTexture(i);
        int tr = valid ? 42 : 245;
        int tg = valid ? 225 : 65;
        int tb = valid ? 245 : 55;
        BufferedImage tinted = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int a = argb >>> 24;
                if (a == 0) continue;
                int lum = (((argb >> 16) & 255) * 3 + ((argb >> 8) & 255) * 6
                        + (argb & 255)) / 10;
                int outA = Math.min(220, Math.max(45, a * (90 + lum) / 255));
                tinted.setRGB(x, y, (outA << 24) | (tr << 16) | (tg << 8) | tb);
            }
        }
        cached = toFake(tinted);
        blueprints[i][state] = cached;
        return cached;
    }

    private FakeImage texture(String file) {
        FakeImage cached = textures.get(file);
        if (cached != null) return cached;
        BufferedImage image = loadBuffered(file);
        if (image == null) image = fallbackBuffered(file);
        cached = toFake(image);
        if (cached != null) textures.put(file, cached);
        return cached;
    }

    private static BufferedImage loadBuffered(String file) {
        InputStream in = null;
        try {
            in = AdventureCoreVfx.class.getResourceAsStream(ROOT + file);
            return in == null ? null : ImageIO.read(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static FakeImage toFake(BufferedImage image) {
        if (image == null || ImageBuilder.builder == null) return null;
        try {
            FakeImage out = ImageBuilder.builder.build(image.getWidth(), image.getHeight());
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) out.setRGB(x, y, image.getRGB(x, y));
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BufferedImage fallbackBuffered(String key) {
        int w = key.startsWith("architect_") ? 256 : 128;
        int h = key.startsWith("architect_") ? 256 : 128;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(Math.max(2f, w / 48f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        if (key.startsWith("architect_")) {
            drawFallbackConstruct(g, key, w, h);
        } else {
            Color c = key.contains("debt") ? new Color(242, 64, 72, 220)
                    : key.contains("suppress") ? new Color(185, 78, 235, 220)
                    : key.contains("flame") || key.contains("ember")
                    ? new Color(255, 102, 35, 230) : new Color(48, 225, 245, 225);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 48));
            g.fillOval(10, 10, w - 20, h - 20);
            g.setColor(c);
            Path2D.Float path = new Path2D.Float();
            path.moveTo(w * 0.5, h * 0.08);
            path.lineTo(w * 0.64, h * 0.38);
            path.lineTo(w * 0.92, h * 0.5);
            path.lineTo(w * 0.64, h * 0.62);
            path.lineTo(w * 0.5, h * 0.92);
            path.lineTo(w * 0.36, h * 0.62);
            path.lineTo(w * 0.08, h * 0.5);
            path.lineTo(w * 0.36, h * 0.38);
            path.closePath();
            g.draw(path);
        }
        g.dispose();
        return image;
    }

    private static void drawFallbackConstruct(Graphics2D g, String key, int w, int h) {
        g.setColor(new Color(24, 29, 32, 245));
        int x = 28, y = 55, ww = w - 56, hh = h - 76;
        if (key.contains("platform")) { y = 130; hh = 45; }
        if (key.contains("launch")) { y = 145; hh = 50; }
        if (key.contains("anchor")) { x = 82; ww = 92; y = 42; }
        g.fillRoundRect(x, y, ww, hh, 8, 8);
        g.setColor(new Color(50, 220, 235, 235));
        g.drawRoundRect(x, y, ww, hh, 8, 8);
        g.setColor(new Color(245, 191, 48, 235));
        for (int i = 0; i < 4; i++) g.fillRect(x + 12 + i * Math.max(16, ww / 4), y + 10, 7, 20);
        if (key.contains("gate")) {
            g.setColor(new Color(245, 75, 45, 130));
            g.fillRect(x + ww / 3, y + 18, ww / 3, hh - 36);
        }
    }

    private static float worldX(float x, float siz, int sbPos) {
        return (x * 0.32f + 200f) * siz + sbPos;
    }
}
