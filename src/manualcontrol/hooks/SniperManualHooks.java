package manualcontrol.hooks;

import common.battle.attack.AttackSimple;
import common.battle.entity.AbEntity;
import common.battle.entity.Entity;
import common.battle.entity.Sniper;
import common.pack.Identifier;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.util.Data;
import common.util.ImgCore;
import common.util.anim.EAnimD;
import common.util.stage.CastleImg;
import common.util.unit.Trait;
import manualcontrol.FallingRegistry;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.SniperManualState;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

public final class SniperManualHooks {

    private static final int PRE_TIME = 10;
    private static final int CURSOR_SIZE = 64;
    private static final int CURSOR_CENTER = CURSOR_SIZE / 2;
    private static final long NANOS_PER_RENDER_FRAME = 1_000_000_000L / 60L;
    private static final float BULLET_HIT_RADIUS = 7.0f;
    private static final float MUZZLE_BASE_OFFSET = 442.5f;
    private static final float BULLET_SPEED_WORLD = 375.0f;
    private static final WeakHashMap<Component, Cursor> ORIGINAL_CURSORS = new WeakHashMap<Component, Cursor>();
    private static Cursor crosshairCursor;
    private static boolean cursorCreateAttempted;
    private static volatile Field transformDataField;

    private SniperManualHooks() {}

    public static void onBattleConstructed(Object page) {
        try {
            Object sniper = getSniperFromPage(page);
            if (sniper == null) return;
            int defaultDamage = computeDefaultDamage(sniper);
            int cooldown = SniperManualState.rememberedCooldown();
            int damage = SniperManualState.rememberedDamageOr(defaultDamage);
            int sniperCount = SniperManualState.rememberedSniperCount();

            DialogResult result = showSettingsDialog(page, cooldown, damage, sniperCount);
            if (!result.accepted || !result.enabled) {
                SniperManualState.unregister(sniper);
                syncCursorForPage(page);
                Logger.log("Manual Sniper disabled for this battle");
                return;
            }

            SniperManualState.register(sniper, result.cooldownFrames, result.damage, result.sniperCount);
            invokeCancel(sniper);
            syncCursorForPage(page);
            Logger.log("Manual Sniper enabled: cooldown=" + result.cooldownFrames
                    + " damage=" + result.damage
                    + " count=" + result.sniperCount);
        } catch (Throwable t) {
            Logger.err("Manual Sniper setup failed", t);
        }
    }

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (e == null || e.getButton() != MouseEvent.BUTTON1) return false;
        try {
            Object sniper = getSniperFromPage(page);
            SniperManualState.Config cfg = sniper == null ? null : SniperManualState.get(sniper);
            if (cfg == null || !isSniperEnabled(sniper)) {
                syncCursorForPage(page);
                return false;
            }
            if (HoldState.get().isHolding()) return false;

            Point p = e.getPoint();
            cfg.rememberMouse(p.x, p.y);
            syncCursorForPage(page);
            if (e.getComponent() != null) {
                setCursor(e.getComponent(), true);
            }

            if (!cfg.isRapidFire() && !isReadyForClick(sniper, cfg)) {
                logReject(cfg, "shot ignored: cooldown/animation active");
                return true;
            }

            AimPoint aim = toAimPoint(page, p.x, p.y);
            if (aim == null) {
                logReject(cfg, "shot ignored: invalid cursor transform");
                return true;
            }
            Object target = findEnemyAtCursor(page, p.x, p.y);

            if (cfg.isRapidFire()) {
                cfg.startMouseHold(target, aim.pos, aim.layer, p.x, p.y);
            } else {
                cfg.stopMouseHold();
            }
            cfg.queueShot(target, aim.pos, aim.layer, p.x, p.y);
            long now = System.currentTimeMillis();
            if (now - cfg.lastShotLogMs > 120L) {
                Logger.log("Manual Sniper queued " + (cfg.isRapidFire() ? "rapid " : "") + "cursor shot pos=" + aim.pos
                        + " layer=" + aim.layer
                        + " target=" + (target == null ? "none" : target.getClass().getSimpleName()));
                cfg.lastShotLogMs = now;
            }
            return true;
        } catch (Throwable t) {
            Logger.err("Manual Sniper mouse hook failed", t);
            return false;
        }
    }

    public static boolean onMouseDragged(Object page, MouseEvent e) {
        if (e == null || (e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) return false;
        try {
            Object sniper = getSniperFromPage(page);
            SniperManualState.Config cfg = sniper == null ? null : SniperManualState.get(sniper);
            if (cfg == null || !cfg.enabled || !cfg.isRapidFire() || !cfg.isMouseDown()
                    || !isSniperEnabled(sniper)) {
                return false;
            }
            if (HoldState.get().isHolding()) return false;

            Point p = e.getPoint();
            AimPoint aim = toAimPoint(page, p.x, p.y);
            if (aim == null) return true;
            Object target = findEnemyAtCursor(page, p.x, p.y);
            cfg.updateMouseHold(target, aim.pos, aim.layer, p.x, p.y);
            syncCursorForPage(page);
            return true;
        } catch (Throwable t) {
            Logger.err("Manual Sniper drag hook failed", t);
            return false;
        }
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        if (e == null) return false;
        try {
            Object sniper = getSniperFromPage(page);
            SniperManualState.Config cfg = sniper == null ? null : SniperManualState.get(sniper);
            if (cfg == null) return false;
            boolean wasRapidHold = cfg.enabled && cfg.isRapidFire() && cfg.isMouseDown();
            cfg.stopMouseHold();
            syncCursorForPage(page);
            return wasRapidHold;
        } catch (Throwable t) {
            Logger.err("Manual Sniper release hook failed", t);
            return false;
        }
    }

    public static boolean onSniperUpdate(Object sniperObj) {
        SniperManualState.Config cfg = SniperManualState.get(sniperObj);
        if (cfg == null || !cfg.enabled) return false;
        try {
            updateManual((Sniper) sniperObj, cfg);
            return true;
        } catch (Throwable t) {
            Logger.err("Manual Sniper update failed", t);
            return false;
        }
    }

    public static void onDraw(Object bbpainter, FakeGraphics g) {
        try {
            Object sniper = getSniperFromPainter(bbpainter);
            SniperManualState.Config cfg = sniper == null ? null : SniperManualState.get(sniper);
            boolean active = cfg != null && cfg.enabled && isSniperEnabled(sniper);
            syncCursorForPainter(bbpainter, active);
            if (active && cfg != null) {
                updateMouseFromPointer(bbpainter, cfg);
            }
            if (active && cfg.sniperCount > 1 && g != null && sniper instanceof Sniper) {
                drawAdditionalSnipers(bbpainter, g, (Sniper) sniper, cfg);
            }
            if (active && g != null && sniper instanceof Sniper) {
                drawManualBullets(bbpainter, g, (Sniper) sniper, cfg);
            }
            if (active && g != null) {
                drawCanvasCrosshair(g, cfg);
            }
        } catch (Throwable t) {
            Logger.err("Manual Sniper draw hook failed", t);
        }
    }

    public static void beforeBattleDraw(Object bbpainter) {
        try {
            Object sniper = getSniperFromPainter(bbpainter);
            SniperManualState.Config cfg = sniper == null ? null : SniperManualState.get(sniper);
            if (cfg == null || !cfg.enabled || !(sniper instanceof Sniper)) return;
            ((Sniper) sniper).bulletX = 0.0;
            hideNativeBulletParts((Sniper) sniper);
        } catch (Throwable t) {
            Logger.err("Manual Sniper pre-draw cleanup failed", t);
        }
    }

    private static DialogResult showSettingsDialog(Object page, int cooldown, int damage, int sniperCount) {
        DialogResult result = new DialogResult();
        if (GraphicsEnvironment.isHeadless()) {
            result.accepted = false;
            return result;
        }

        JCheckBox enabled = new JCheckBox("Enable Manual Sniper", true);
        JSpinner cooldownSpinner = new JSpinner(new SpinnerNumberModel(cooldown, 0, 99999, 1));
        JSpinner damageSpinner = new JSpinner(new SpinnerNumberModel(damage, 1, Integer.MAX_VALUE, 1));
        JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(sniperCount, 1,
                SniperManualState.MAX_SNIPER_COUNT, 1));

        JPanel panel = new JPanel();
        panel.setLayout(new java.awt.GridLayout(0, 2, 8, 6));
        panel.add(enabled);
        panel.add(new JLabel(""));
        panel.add(new JLabel("Cooldown frames (0 = rapid fire)"));
        panel.add(cooldownSpinner);
        panel.add(new JLabel("Damage"));
        panel.add(damageSpinner);
        panel.add(new JLabel("Sniper count"));
        panel.add(countSpinner);

        Component parent = page instanceof Component ? (Component) page : null;
        int choice = JOptionPane.showConfirmDialog(parent, panel, "Manual Sniper",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        result.accepted = choice == JOptionPane.OK_OPTION;
        result.enabled = enabled.isSelected();
        result.cooldownFrames = ((Number) cooldownSpinner.getValue()).intValue();
        result.damage = ((Number) damageSpinner.getValue()).intValue();
        result.sniperCount = ((Number) countSpinner.getValue()).intValue();
        return result;
    }

    private static void updateManual(Sniper sniper, SniperManualState.Config cfg) throws Exception {
        if (!sniper.enabled || sniper.b.ubase.health <= 0L) {
            cfg.clearTargets();
            sniper.bulletX = 0.0;
            return;
        }

        if (cfg.isRapidFire()) {
            updateRapidManual(sniper, cfg);
            return;
        }

        int preTime = BCUFields.getInt(sniper, "preTime");
        int atkTime = BCUFields.getInt(sniper, "atkTime");

        if (cfg.cooldownRemaining > 0 && preTime == 0 && atkTime == 0) {
            cfg.cooldownRemaining--;
            BCUFields.setInt(sniper, "coolTime", cfg.cooldownRemaining);
        }

        SniperManualState.ShotRequest queued = cfg.consumeQueuedShot();
        if (queued != null && isReadyForShot(sniper, cfg)) {
            startShot(sniper, cfg, queued);
            preTime = BCUFields.getInt(sniper, "preTime");
            atkTime = BCUFields.getInt(sniper, "atkTime");
        }

        syncActiveTarget(sniper, cfg);
        updateAngle(sniper, preTime);

        if (preTime > 0) {
            preTime--;
            BCUFields.setInt(sniper, "preTime", preTime);
        }

        sniper.bulletX = 0.0;
        alterPart(sniper, 6, 12, 0.0f);
        updateManualBullets(sniper, cfg);
        syncManualVisual(sniper, cfg);

        if (atkTime > 0) {
            atkTime--;
            BCUFields.setInt(sniper, "atkTime", atkTime);
            getAttackAnim(sniper).update(false);
        } else {
            getIdleAnim(sniper).update(true);
        }

        rotateCannon(sniper);
        applyVisualState(sniper);
    }

    private static void updateRapidManual(Sniper sniper, SniperManualState.Config cfg) throws Exception {
        cfg.cooldownRemaining = 0;
        BCUFields.setInt(sniper, "coolTime", 0);
        BCUFields.setInt(sniper, "preTime", 0);
        sniper.bulletX = 0.0;
        alterPart(sniper, 6, 12, 0.0f);

        List<SniperManualState.ShotRequest> queued = cfg.drainQueuedRapidShots();
        boolean startedShot = false;
        if (!queued.isEmpty()) {
            for (SniperManualState.ShotRequest shot : queued) {
                startRapidShot(sniper, cfg, shot);
                startedShot = true;
            }
        } else if (cfg.isMouseDown()) {
            SniperManualState.ShotRequest heldShot = cfg.currentHeldShot();
            if (heldShot != null) {
                startRapidShot(sniper, cfg, heldShot);
                startedShot = true;
            }
        }

        if (startedShot) {
            EAnimD<?> atka = getAttackAnim(sniper);
            EAnimD<?> anim = getIdleAnim(sniper);
            BCUFields.setInt(sniper, "atkTime", atka.len());
            atka.setup();
            atka.update(false);
            anim.setup();
        }

        updateManualBullets(sniper, cfg);
        syncManualVisual(sniper, cfg);

        int atkTime = BCUFields.getInt(sniper, "atkTime");
        if (atkTime > 0) {
            atkTime--;
            BCUFields.setInt(sniper, "atkTime", atkTime);
            getAttackAnim(sniper).update(false);
        } else {
            getIdleAnim(sniper).update(true);
        }

        rotateCannon(sniper);
        applyVisualState(sniper);
    }

    private static void startRapidShot(Sniper sniper, SniperManualState.Config cfg,
                                       SniperManualState.ShotRequest shot) throws Exception {
        startManualBullets(sniper, cfg, shot);
    }

    private static double bulletCos(double angle) {
        double cos = Math.cos(Math.toRadians((int) angle));
        if (Math.abs(cos) < 0.0001) cos = cos < 0 ? -0.0001 : 0.0001;
        return cos;
    }

    private static void startShot(Sniper sniper, SniperManualState.Config cfg,
                                  SniperManualState.ShotRequest shot) throws Exception {
        startManualBullets(sniper, cfg, shot);

        EAnimD<?> atka = getAttackAnim(sniper);
        EAnimD<?> anim = getIdleAnim(sniper);
        BCUFields.setInt(sniper, "coolTime", cfg.cooldownFrames);
        BCUFields.setInt(sniper, "preTime", PRE_TIME);
        BCUFields.setInt(sniper, "atkTime", atka.len());
        atka.setup();
        atka.update(false);
        anim.setup();
        cfg.cooldownRemaining = cfg.cooldownFrames;
    }

    private static void startManualBullets(Sniper sniper, SniperManualState.Config cfg,
                                           SniperManualState.ShotRequest shot) throws Exception {
        int count = Math.min(cfg.sniperCount, SniperManualState.MAX_SNIPER_COUNT);
        SniperManualState.ManualBullet primary = null;
        for (int i = 0; i < count; i++) {
            SniperManualState.ManualBullet bullet = createManualBullet(sniper, shot, i);
            cfg.addManualBullet(bullet);
            if (i == 0) primary = bullet;
        }
        if (primary == null) return;
        cfg.markReticleShot(System.nanoTime());
        Object target = shot.getTarget();
        cfg.setActiveTarget(target, shot.aimPos, shot.aimLayer);
        BCUFields.set(sniper, "target", target);
        sniper.pos = shot.aimPos;
        sniper.layer = shot.aimLayer;
        sniper.targetAngle = primary.angle;
        sniper.cannonAngle = primary.angle;
        sniper.bulletAngle = primary.angle;
        sniper.bulletX = 0.0;
        alterPart(sniper, 6, 12, 0.0f);
    }

    private static SniperManualState.ManualBullet createManualBullet(
            Sniper sniper, SniperManualState.ShotRequest shot, int sniperIndex) {
        float siz = Math.max(0.001f, sniper.b.siz);
        float[] offset = sniperOffset(sniperIndex, siz, sniper.b.time);
        float renderAimPos = shot.aimPos - offset[0] / (0.32f * siz);
        float renderAimLayer = shot.aimLayer - offset[1] / (4.0f * siz);
        double angle = computeAngle(sniper, renderAimPos, renderAimLayer, PRE_TIME);
        float muzzlePos = sniper.getPos();
        float muzzleLayer = computeMuzzleLayer(muzzlePos, renderAimPos, renderAimLayer, angle);
        return new SniperManualState.ManualBullet(shot.getTarget(), sniperIndex, offset[0], offset[1],
                muzzlePos, muzzleLayer, renderAimPos, renderAimLayer, shot.aimPos, shot.aimLayer,
                angle, PRE_TIME);
    }

    private static float computeMuzzleLayer(float muzzlePos, float aimPos, float aimLayer, double angle) {
        double tan = Math.tan(Math.toRadians((int) angle));
        if (Double.isNaN(tan) || Double.isInfinite(tan) || Math.abs(tan) > 20.0) {
            return aimLayer;
        }
        return aimLayer + (float) ((muzzlePos - aimPos) * 0.32f * tan / 4.0f);
    }

    private static void updateManualBullets(Sniper sniper, SniperManualState.Config cfg) throws Exception {
        List<SniperManualState.ManualBullet> bullets = cfg.manualBulletsSnapshot();
        long nowNanos = System.nanoTime();
        for (SniperManualState.ManualBullet bullet : bullets) {
            if (bullet.preTime > 0) {
                bullet.preTime--;
                if (bullet.preTime > 0) continue;
            }

            ensureBulletFlightStarted(bullet, nowNanos);
            bullet.syncProgress(nowNanos);

            if (bullet.progress >= 1.0f) {
                Object hit = findEnemyAtAimSprite(sniper, bullet.impactAimPos, bullet.impactAimLayer);
                if (hit instanceof AbEntity && isValidDamageTarget(hit)) {
                    damageTarget(sniper, (AbEntity) hit, cfg.damage, bullet.impactAimLayer);
                }
                cfg.removeManualBullet(bullet);
            }
        }
        sniper.bulletX = 0.0;
        alterPart(sniper, 6, 12, 0.0f);
    }

    private static void ensureBulletFlightStarted(SniperManualState.ManualBullet bullet, long nowNanos) {
        if (!bullet.flightStarted()) {
            bullet.startFlight(nowNanos, bulletFlightDurationNanos(bullet));
        }
    }

    private static long bulletFlightDurationNanos(SniperManualState.ManualBullet bullet) {
        float frames = bulletDistance(bullet) / BULLET_SPEED_WORLD;
        return Math.max(NANOS_PER_RENDER_FRAME, Math.round(frames * NANOS_PER_RENDER_FRAME));
    }

    private static float bulletDistance(SniperManualState.ManualBullet bullet) {
        float distance = Math.abs((bullet.renderAimPos - bullet.muzzlePos) / (float) bulletCos(bullet.angle));
        if (Float.isNaN(distance) || Float.isInfinite(distance)) {
            float dx = bullet.renderAimPos - bullet.muzzlePos;
            float dy = (bullet.renderAimLayer - bullet.muzzleLayer) * 12.5f;
            distance = (float) Math.sqrt(dx * dx + dy * dy);
        }
        return Math.max(1.0f, distance);
    }

    private static void syncManualVisual(Sniper sniper, SniperManualState.Config cfg) throws Exception {
        SniperManualState.ManualBullet visual = cfg.visualManualBullet();
        if (visual == null) {
            if (BCUFields.getInt(sniper, "preTime") == 0 && BCUFields.getInt(sniper, "atkTime") == 0) {
                cfg.clearActiveTarget();
                BCUFields.set(sniper, "target", null);
                sniper.pos = -1.0;
            }
            sniper.bulletX = 0.0;
            alterPart(sniper, 6, 12, 0.0f);
            return;
        }

        Object target = visual.getInitialTarget();
        cfg.setActiveTarget(target, visual.impactAimPos, visual.impactAimLayer);
        BCUFields.set(sniper, "target", target);
        sniper.pos = visual.impactAimPos;
        sniper.layer = visual.impactAimLayer;
        sniper.targetAngle = visual.angle;
        sniper.bulletAngle = visual.angle;
        sniper.cannonAngle = visual.angle;
        sniper.bulletX = 0.0;
        alterPart(sniper, 6, 12, 0.0f);
    }

    private static void syncActiveTarget(Sniper sniper, SniperManualState.Config cfg) {
        boolean shotActive = cfg.hasManualBullets()
                || BCUFields.getInt(sniper, "preTime") > 0
                || BCUFields.getInt(sniper, "atkTime") > 0;
        if (shotActive && cfg.activePos > 0f) {
            sniper.pos = cfg.activePos;
            sniper.layer = cfg.activeLayer;
            return;
        }
        if (!shotActive) {
            cfg.clearActiveTarget();
            BCUFields.set(sniper, "target", null);
            sniper.pos = -1.0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object findEnemyAtAimSprite(Sniper sniper, float aimPos, float aimLayer) {
        try {
            List<Object> entities = (List<Object>) BCUFields.get(sniper.b, "le");
            float siz = Math.max(0.4f, BCUFields.getFloat(sniper.b, "siz"));
            float padding = BULLET_HIT_RADIUS * siz;
            ArrayList<Object> candidates = new ArrayList<Object>();
            for (Object ent : entities) {
                if (!isValidTarget(ent)) continue;
                float entityPos = EntityAccess.getPos(ent);
                float x = (aimPos - entityPos) * 0.32f * siz;
                float y = (aimLayer - EntityAccess.getLayer(ent)) * 4.0f * siz;
                EntityAccess.SpriteBounds bounds = EntityAccess.estimateSpriteBounds(ent, siz, 0f, 0f);
                if (!bounds.contains(x, y, padding)) continue;
                candidates.add(ent);
            }

            Object base = sniper.b.ebase;
            if (isValidEnemyBase(base)) {
                if (base instanceof Entity) {
                    float entityPos = EntityAccess.getPos(base);
                    float x = (aimPos - entityPos) * 0.32f * siz;
                    float y = (aimLayer - EntityAccess.getLayer(base)) * 4.0f * siz;
                    EntityAccess.SpriteBounds bounds = EntityAccess.estimateSpriteBounds(base, siz, 0f, 0f);
                    if (bounds.contains(x, y, padding)) {
                        candidates.add(base);
                    }
                } else if (base instanceof AbEntity) {
                    CastleBounds bounds = estimateEnemyCastleBounds(sniper, siz);
                    float x = (aimPos - ((AbEntity) base).pos) * 0.32f * siz;
                    float y = aimLayer * 4.0f * siz;
                    if (bounds.contains(x, y, padding)) {
                        candidates.add(base);
                    }
                }
            }
            if (candidates.isEmpty()) return null;
            if (candidates.size() == 1) return candidates.get(0);
            return candidates.get(randomIndex(sniper, candidates.size()));
        } catch (Throwable t) {
            Logger.err("Manual Sniper impact sprite lookup failed", t);
            return null;
        }
    }

    private static int randomIndex(Sniper sniper, int bound) {
        if (bound <= 1) return 0;
        try {
            return (int) (sniper.b.r.nextFloat() * bound) % bound;
        } catch (Throwable ignored) {
            return (int) (Math.random() * bound) % bound;
        }
    }

    private static CastleBounds estimateEnemyCastleBounds(Sniper sniper, float siz) {
        float width = 128.0f * siz;
        float height = 256.0f * siz;
        try {
            CastleImg castle = Identifier.getOr(sniper.b.st.castle, CastleImg.class);
            if (castle != null && castle.img != null) {
                FakeImage image = castle.img.getImg();
                if (image != null && image.isValid()) {
                    width = Math.max(1.0f, image.getWidth() * siz);
                    height = Math.max(1.0f, image.getHeight() * siz);
                }
            }
        } catch (Throwable ignored) {
        }
        return new CastleBounds(-width, -height, 0.0f, 0.0f);
    }

    private static void damageTarget(Sniper sniper, AbEntity target, int damage, float impactLayer) {
        Data.Proc proc = Data.Proc.blank();
        proc.SNIPER.prob = 1;
        ArrayList<Trait> traits = new ArrayList<Trait>();
        traits.add(null);
        int layer = Math.round(impactLayer);
        AttackSimple attack = new AttackSimple(null, sniper, damage, traits, layer, proc,
                0.0f, sniper.getPos(), false, null, -1, true, 1);
        attack.canon = -1;
        target.damaged(attack);
    }

    private static void updateAngle(Sniper sniper, int preTime) {
        if (sniper.pos == -1.0) {
            if (preTime == 0 && sniper.bulletX == 0.0) {
                sniper.bulletAngle = 0.0;
            }
            sniper.targetAngle = 0.0;
            return;
        }
        double theta = computeAngle(sniper, (float) sniper.pos, (float) sniper.layer, preTime);
        if (preTime == 0 && sniper.bulletX == 0.0) {
            sniper.bulletAngle = theta;
        }
        sniper.targetAngle = theta;
    }

    private static double computeAngle(Sniper sniper, float pos, float layer, int preTime) {
        int cx = sniper.b.st.len * 4 - 3200;
        int cy = 4400;
        int ux = (int) (pos * 4.0);
        int uy = 4480;
        double[] coords = sniper.bf.sniperCoords(preTime > 0);
        if (coords == null || coords.length < 2 || Math.abs(coords[1]) < 0.0001) {
            return sniper.targetAngle;
        }
        int scrollPos = (int) (-Math.round(coords[0] / 0.32f * 4.0 / coords[1]));
        int sniperX = (cx - scrollPos) / 10 + 203;
        int sniperY = (int) (Math.sin(0.10471975511965977 * sniper.b.time) * 10.0) + cy / 10 - 369;
        int uy10 = uy / 10;
        int uyScroll = (ux - scrollPos) / 10;
        return Math.toDegrees(Math.atan2((sniperY - uy10) - 4.0 * layer + 58.0,
                sniperX - uyScroll));
    }

    private static void rotateCannon(Sniper sniper) {
        if (sniper.bulletX > 0.0) {
            sniper.cannonAngle = sniper.targetAngle;
        } else if (sniper.cannonAngle != sniper.targetAngle) {
            if (sniper.cannonAngle < sniper.targetAngle) {
                sniper.cannonAngle += 1.0;
                if (sniper.cannonAngle > sniper.targetAngle) sniper.cannonAngle = sniper.targetAngle;
            } else {
                sniper.cannonAngle -= 1.0;
                if (sniper.cannonAngle < sniper.targetAngle) sniper.cannonAngle = sniper.targetAngle;
            }
        }
    }

    private static void applyVisualState(Sniper sniper) throws Exception {
        EAnimD<?> anim = getIdleAnim(sniper);
        EAnimD<?> atka = getAttackAnim(sniper);
        if (sniper.bulletX > 0.0) {
            anim.ent[6].alter(12, 0.0f);
            anim.ent[5].alter(11, (int) Math.round(sniper.bulletAngle * 10.0));
        } else {
            anim.ent[5].alter(11, (int) Math.round(sniper.cannonAngle * 10.0));
        }
        atka.ent[5].alter(11, (int) Math.round(sniper.bulletAngle * 10.0));
        int heightOffset = -((int) Math.round((659.6666666666666
                - 25.0 * Math.sin(Math.PI * sniper.b.time / 30.0)
                - sniper.height) * 0.32f));
        anim.ent[1].alter(5, heightOffset);
        atka.ent[1].alter(5, heightOffset);
        atka.ent[6].alter(9, 900.0f);
        anim.ent[6].alter(9, 900.0f);
        atka.ent[6].alter(10, 900.0f);
        anim.ent[6].alter(10, 900.0f);
        atka.ent[6].alter(12, 0.0f);
        anim.ent[6].alter(12, 0.0f);
    }

    private static void applySniperHeight(Sniper sniper, float height) throws Exception {
        sniper.height = height;
        int heightOffset = -((int) Math.round((659.6666666666666
                - 25.0 * Math.sin(Math.PI * sniper.b.time / 30.0)
                - height) * 0.32f));
        getIdleAnim(sniper).ent[1].alter(5, heightOffset);
        getAttackAnim(sniper).ent[1].alter(5, heightOffset);
    }

    private static void alterPart(Sniper sniper, int part, int key, float value) throws Exception {
        getAttackAnim(sniper).ent[part].alter(key, value);
        getIdleAnim(sniper).ent[part].alter(key, value);
    }

    private static EAnimD<?> getIdleAnim(Sniper sniper) {
        return (EAnimD<?>) BCUFields.get(sniper, "anim");
    }

    private static EAnimD<?> getAttackAnim(Sniper sniper) {
        return (EAnimD<?>) BCUFields.get(sniper, "atka");
    }

    private static boolean isReadyForClick(Object sniper, SniperManualState.Config cfg) {
        return isReadyForShot(sniper, cfg);
    }

    private static boolean isReadyForShot(Object sniper, SniperManualState.Config cfg) {
        return cfg.cooldownRemaining <= 0
                && BCUFields.getInt(sniper, "preTime") == 0
                && BCUFields.getInt(sniper, "atkTime") == 0
                && !cfg.hasManualBullets();
    }

    private static boolean isValidTarget(Object target) {
        if (target == null) return false;
        if (EntityAccess.isDead(target)) return false;
        if (EntityAccess.isBase(target)) return false;
        if (EntityAccess.getDire(target) != 1) return false;
        if (FallingRegistry.isManaged(target)) return false;
        try {
            return (((Number) BCUFields.invoke(target, "touchable")).intValue() & 1) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isValidDamageTarget(Object target) {
        if (target instanceof AbEntity && ((AbEntity) target).isBase()) {
            return isValidEnemyBase(target);
        }
        if (target instanceof Entity) return isValidTarget(target);
        return isValidEnemyBase(target);
    }

    private static boolean isValidEnemyBase(Object target) {
        if (!(target instanceof AbEntity)) return false;
        AbEntity base = (AbEntity) target;
        if (!base.isBase()) return false;
        if (base.dire != 1) return false;
        if (base.health <= 0L) return false;
        try {
            return (base.touchable() & 1) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static AimPoint toAimPoint(Object page, int mouseX, int mouseY) {
        try {
            Object basis = BCUFields.get(page, "basis");
            Object sb = BCUFields.get(basis, "sb");
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            float siz = BCUFields.getFloat(sb, "siz");
            if (siz < 0.001f) return null;
            int stagePos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            return toAimPoint(mouseX, mouseY, siz, stagePos, midh);
        } catch (Throwable t) {
            Logger.err("Manual Sniper aim transform failed", t);
            return null;
        }
    }

    private static AimPoint toAimPoint(int mouseX, int mouseY, float siz, int stagePos, int midh) {
        if (siz < 0.001f) return null;
        float pos = ((mouseX - stagePos) / siz - 200.0f) / 0.32f;
        float layer = (156.0f - (midh - mouseY) / siz) / 4.0f;
        return new AimPoint(pos, layer);
    }

    @SuppressWarnings("unchecked")
    private static Object findEnemyAtCursor(Object page, int mouseX, int mouseY) {
        Object basis = BCUFields.get(page, "basis");
        Object sb = BCUFields.get(basis, "sb");
        Object bb = BCUFields.get(page, "bb");
        Object bbp = BCUFields.get(bb, "bbp");
        List<Object> entities = (List<Object>) BCUFields.get(sb, "le");
        float siz = BCUFields.getFloat(sb, "siz");
        int stagePos = BCUFields.getInt(sb, "pos");
        int midh = BCUFields.getInt(bbp, "midh");
        int width = (int) BCUFields.invoke(bb, "getWidth");
        int height = (int) BCUFields.invoke(bb, "getHeight");
        return findEnemyAtCursorInStage(sb, mouseX, mouseY, siz, stagePos, midh, width, height);
    }

    private static Object findEnemyAtCursorFromPainter(Object bbpainter, int mouseX, int mouseY) {
        Object bf = BCUFields.get(bbpainter, "bf");
        Object sb = BCUFields.get(bf, "sb");
        Object box = BCUFields.get(bbpainter, "box");
        float siz = BCUFields.getFloat(sb, "siz");
        int stagePos = BCUFields.getInt(sb, "pos");
        int midh = BCUFields.getInt(bbpainter, "midh");
        int width = box instanceof Component ? ((Component) box).getWidth() : 0;
        int height = box instanceof Component ? ((Component) box).getHeight() : 0;
        if (width <= 0) width = 99999;
        if (height <= 0) height = 99999;
        return findEnemyAtCursorInStage(sb, mouseX, mouseY, siz, stagePos, midh, width, height);
    }

    @SuppressWarnings("unchecked")
    private static Object findEnemyAtCursorInStage(Object sb, int mouseX, int mouseY,
                                                   float siz, int stagePos, int midh,
                                                   int width, int height) {
        List<Object> entities = (List<Object>) BCUFields.get(sb, "le");
        Object best = null;
        float bestScore = Float.MAX_VALUE;
        for (Object ent : entities) {
            if (!isValidTarget(ent)) continue;
            float rootX = (EntityAccess.getPos(ent) * 0.32f + 200.0f) * siz + stagePos;
            float rootY = midh - (156 - EntityAccess.getLayer(ent) * 4) * siz;
            EntityAccess.SpriteBounds bounds = EntityAccess.estimateSpriteBounds(ent, siz, rootX, rootY);
            if (bounds.right < 0 || bounds.left > width || bounds.bottom < 0 || bounds.top > height) continue;
            if (!bounds.contains(mouseX, mouseY, BULLET_HIT_RADIUS * siz)) continue;
            float dx = bounds.centerX - mouseX;
            float dy = bounds.centerY - mouseY;
            float score = dx * dx + dy * dy;
            if (score < bestScore) {
                bestScore = score;
                best = ent;
            }
        }
        return best;
    }

    private static int computeDefaultDamage(Object sniper) {
        try {
            Object sb = BCUFields.get(sniper, "b");
            Object basisLu = BCUFields.get(sb, "b");
            Object treasure = BCUFields.invoke(basisLu, "t");
            boolean comboBanned = isSniperComboBanned(sb);
            Method getBaseHealth = BCUFields.method(treasure.getClass(), "getBaseHealth", boolean.class);
            int baseHealth = ((Number) getBaseHealth.invoke(treasure, comboBanned)).intValue();
            return Math.max(1, baseHealth / 20);
        } catch (Throwable t) {
            Logger.err("Manual Sniper default damage fallback", t);
            return 99999;
        }
    }

    private static boolean isSniperComboBanned(Object sb) {
        try {
            Object est = BCUFields.get(sb, "est");
            Object lim = BCUFields.get(est, "lim");
            Class<?> stageLimit = Class.forName("common.util.stage.StageLimit");
            Method[] methods = stageLimit.getDeclaredMethods();
            for (Method method : methods) {
                if (!"isComboBanned".equals(method.getName()) || method.getParameterTypes().length != 2) continue;
                method.setAccessible(true);
                Object value = method.invoke(null, lim, 10);
                return value instanceof Boolean && ((Boolean) value).booleanValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Method method = BCUFields.method(sb.getClass(), "isBanned", byte.class);
            Object value = method.invoke(sb, (byte) 10);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getSniperFromPage(Object page) {
        try {
            Object basis = BCUFields.get(page, "basis");
            Object sb = BCUFields.get(basis, "sb");
            return BCUFields.get(sb, "sniper");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getSniperFromPainter(Object bbpainter) {
        try {
            Object bf = BCUFields.get(bbpainter, "bf");
            Object sb = BCUFields.get(bf, "sb");
            return BCUFields.get(sb, "sniper");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSniperEnabled(Object sniper) {
        try {
            return BCUFields.getBoolean(sniper, "enabled");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void invokeCancel(Object sniper) {
        try {
            BCUFields.invoke(sniper, "cancel");
        } catch (Throwable ignored) {
        }
    }

    private static void logReject(SniperManualState.Config cfg, String message) {
        long now = System.currentTimeMillis();
        if (now - cfg.lastRejectLogMs > 500L) {
            Logger.log("Manual Sniper " + message);
            cfg.lastRejectLogMs = now;
        }
    }

    private static void syncCursorForPage(Object page) {
        try {
            Object sniper = getSniperFromPage(page);
            SniperManualState.Config cfg = sniper == null ? null : SniperManualState.get(sniper);
            boolean active = cfg != null && cfg.enabled && isSniperEnabled(sniper);
            Object bb = BCUFields.get(page, "bb");
            if (bb instanceof Component) {
                setCursor((Component) bb, active);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void syncCursorForPainter(Object bbpainter, boolean active) {
        try {
            Object box = BCUFields.get(bbpainter, "box");
            if (box instanceof Component) {
                setCursor((Component) box, active);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setCursor(Component component, boolean active) {
        if (component == null) return;
        synchronized (ORIGINAL_CURSORS) {
            Cursor cursor = active ? getCrosshairCursor() : null;
            applyCursor(component, active, cursor);
        }
    }

    private static void applyCursor(Component component, boolean active, Cursor cursor) {
        if (component == null) return;
        if (active) {
            if (!ORIGINAL_CURSORS.containsKey(component)) {
                ORIGINAL_CURSORS.put(component, component.getCursor());
            }
            Cursor effective = cursor != null ? cursor : Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
            if (component.getCursor() != effective) {
                component.setCursor(effective);
            }
        } else {
            Cursor original = ORIGINAL_CURSORS.remove(component);
            component.setCursor(original != null ? original : Cursor.getDefaultCursor());
        }

        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                applyCursor(children[i], active, cursor);
            }
        }
    }

    private static void updateMouseFromPointer(Object bbpainter, SniperManualState.Config cfg) {
        try {
            Object box = BCUFields.get(bbpainter, "box");
            if (!(box instanceof Component)) return;
            Component component = (Component) box;
            if (!component.isShowing()) return;
            PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null) return;
            Point screen = pointer.getLocation();
            Point origin = component.getLocationOnScreen();
            int x = screen.x - origin.x;
            int y = screen.y - origin.y;
            if (x >= 0 && y >= 0 && x < component.getWidth() && y < component.getHeight()) {
                updatePointerAimFromPainter(bbpainter, cfg, x, y);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updatePointerAimFromPainter(Object bbpainter, SniperManualState.Config cfg,
                                                    int mouseX, int mouseY) {
        try {
            if (!cfg.isRapidFire() || !cfg.isMouseDown()) {
                cfg.rememberMouse(mouseX, mouseY);
                return;
            }
            Object bf = BCUFields.get(bbpainter, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int stagePos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbpainter, "midh");
            AimPoint aim = toAimPoint(mouseX, mouseY, siz, stagePos, midh);
            if (aim == null) {
                cfg.rememberMouse(mouseX, mouseY);
                return;
            }
            Object target = findEnemyAtCursorFromPainter(bbpainter, mouseX, mouseY);
            cfg.updateMouseHold(target, aim.pos, aim.layer, mouseX, mouseY);
        } catch (Throwable ignored) {
            cfg.rememberMouse(mouseX, mouseY);
        }
    }

    private static void drawCanvasCrosshair(FakeGraphics g, SniperManualState.Config cfg) {
        int x = cfg.lastMouseX;
        int y = cfg.lastMouseY;
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) return;

        FakeTransform oldTransform = pushIdentityTransform(g);
        if (oldTransform == null) return;
        try {
            long nowNanos = System.nanoTime();
            long epochNanos = cfg.ensureReticleEpoch(nowNanos);
            long lastShotNanos = cfg.reticleLastShotNanos();
            double time = (nowNanos - epochNanos) / 1_000_000_000.0;
            double shotAge = lastShotNanos > 0L
                    ? (nowNanos - lastShotNanos) / 1_000_000_000.0 : 999.0;
            boolean fastBreath = shotAge < 1.15;

            double breathPhase = fastBreath ? shotAge * Math.PI * 5.6 : time * Math.PI * 1.25;
            double scale = fastBreath
                    ? 1.045 + 0.075 * Math.sin(breathPhase) * Math.max(0.35, 1.0 - shotAge / 1.6)
                    : 1.0 + 0.035 * Math.sin(breathPhase);
            int recoilY = shotAge < 0.22
                    ? -Math.round((float) (15.0 * Math.pow(1.0 - shotAge / 0.22, 2.0))) : 0;
            int swayX = Math.round((float) (Math.sin(time * 1.1) * 1.6 + Math.sin(time * 2.3) * 0.7));
            int swayY = Math.round((float) (Math.cos(time * 0.95) * 1.3));
            int cx = x + swayX;
            int cy = y + swayY + recoilY;

            long rotationStart = lastShotNanos > 0L ? lastShotNanos + 220_000_000L : epochNanos;
            double rotationAge = Math.max(0.0, (nowNanos - rotationStart) / 1_000_000_000.0);
            double rotation = rotationAge * Math.PI * 0.42;

            int r = Math.max(24, Math.round((float) (32.0 * scale)));
            int inner = Math.max(26, Math.round((float) (34.0 * scale)));
            int outer = Math.max(inner + 16, Math.round((float) (58.0 * scale)));
            int shadowDot = Math.max(4, Math.round((float) (5.0 * scale)));
            int redDot = Math.max(3, Math.round((float) (4.0 * scale)));
            g.setColor(90, 0, 0);
            drawRectCircle(g, cx + 1, cy + 1, r, shadowDot, rotation);
            drawReticleArms(g, cx + 1, cy + 1, rotation, inner, outer, shadowDot);

            g.setColor(255, 20, 20);
            drawRectCircle(g, cx, cy, r, redDot, rotation);
            drawReticleArms(g, cx, cy, rotation, inner, outer, redDot);
            g.setColor(255, 255, 255);
            fillDotRect(g, cx, cy, Math.max(2, Math.round((float) (3.0 * scale))));
        } finally {
            popTransform(g, oldTransform);
        }
    }

    private static void drawRectCircle(FakeGraphics g, int cx, int cy, int radius, int dot,
                                       double rotation) {
        int step = 9;
        int half = Math.max(1, dot / 2);
        for (int deg = 0; deg < 360; deg += step) {
            double rad = Math.toRadians(deg) + rotation;
            int x = cx + Math.round((float) Math.cos(rad) * radius);
            int y = cy + Math.round((float) Math.sin(rad) * radius);
            g.fillRect(x - half, y - half, dot, dot);
        }
    }

    private static void drawReticleArms(FakeGraphics g, int cx, int cy, double rotation,
                                        int inner, int outer, int dot) {
        for (int i = 0; i < 4; i++) {
            drawReticleArm(g, cx, cy, rotation + i * Math.PI / 2.0, inner, outer, dot);
        }
    }

    private static void drawReticleArm(FakeGraphics g, int cx, int cy, double angle,
                                       int inner, int outer, int dot) {
        int half = Math.max(1, dot / 2);
        int step = Math.max(3, dot);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (int dist = inner; dist <= outer; dist += step) {
            int x = cx + Math.round((float) (cos * dist));
            int y = cy + Math.round((float) (sin * dist));
            g.fillRect(x - half, y - half, dot, dot);
        }
    }

    private static void fillDotRect(FakeGraphics g, int cx, int cy, int radius) {
        int r = Math.max(1, radius);
        for (int dy = -r; dy <= r; dy++) {
            int span = (int) Math.floor(Math.sqrt(r * r - dy * dy));
            g.fillRect(cx - span, cy + dy, span * 2 + 1, 1);
        }
    }

    private static FakeTransform pushIdentityTransform(FakeGraphics g) {
        try {
            FakeTransform oldTransform = g.getTransform();
            FakeTransform identity = g.getTransform();
            Field field = transformDataField;
            if (field == null || field.getDeclaringClass() != identity.getClass()) {
                field = identity.getClass().getDeclaredField("data");
                field.setAccessible(true);
                transformDataField = field;
            }
            field.set(identity, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            g.setTransform(identity);
            try {
                g.delete(identity);
            } catch (Throwable ignored) {
            }
            return oldTransform;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean setIdentityTransform(FakeGraphics g) {
        try {
            FakeTransform identity = g.getTransform();
            Field field = transformDataField;
            if (field == null || field.getDeclaringClass() != identity.getClass()) {
                field = identity.getClass().getDeclaredField("data");
                field.setAccessible(true);
                transformDataField = field;
            }
            field.set(identity, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            g.setTransform(identity);
            try {
                g.delete(identity);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void popTransform(FakeGraphics g, FakeTransform oldTransform) {
        try {
            g.setTransform(oldTransform);
        } catch (Throwable ignored) {
        } finally {
            try {
                g.delete(oldTransform);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Cursor getCrosshairCursor() {
        if (cursorCreateAttempted) return crosshairCursor;
        cursorCreateAttempted = true;
        try {
            BufferedImage image = createCrosshairImage(CURSOR_SIZE, CURSOR_SIZE);
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Dimension best = toolkit.getBestCursorSize(CURSOR_SIZE, CURSOR_SIZE);
            Image cursorImage = image;
            Point hotSpot = new Point(CURSOR_CENTER, CURSOR_CENTER);
            if (best.width > 0 && best.height > 0
                    && (best.width != CURSOR_SIZE || best.height != CURSOR_SIZE)) {
                cursorImage = scaleCursorImage(image, best.width, best.height);
                hotSpot = new Point(best.width / 2, best.height / 2);
            } else if (best.width > 0 && best.height > 0) {
                cursorImage = toCompatibleCursorImage(image);
            }
            crosshairCursor = toolkit.createCustomCursor(cursorImage, hotSpot, "Manual Sniper");
        } catch (Throwable t) {
            crosshairCursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
            Logger.err("Manual Sniper custom cursor unavailable", t);
        }
        return crosshairCursor;
    }

    private static BufferedImage createCrosshairImage(int width, int height) {
        int centerX = width / 2;
        int centerY = height / 2;
        float sx = width / 64.0f;
        float sy = height / 64.0f;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(90, 0, 0, 170));
            g.setStroke(new BasicStroke(Math.max(2.0f, 5.0f * Math.min(sx, sy)),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(Math.round(15 * sx), Math.round(15 * sy), Math.round(34 * sx), Math.round(34 * sy));
            g.drawLine(centerX, Math.round(6 * sy), centerX, Math.round(18 * sy));
            g.drawLine(centerX, Math.round(46 * sy), centerX, Math.round(58 * sy));
            g.drawLine(Math.round(6 * sx), centerY, Math.round(18 * sx), centerY);
            g.drawLine(Math.round(46 * sx), centerY, Math.round(58 * sx), centerY);

            g.setColor(new Color(255, 20, 20, 245));
            g.setStroke(new BasicStroke(Math.max(1.0f, 3.0f * Math.min(sx, sy)),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(Math.round(16 * sx), Math.round(16 * sy), Math.round(32 * sx), Math.round(32 * sy));
            g.drawLine(centerX, Math.round(7 * sy), centerX, Math.round(18 * sy));
            g.drawLine(centerX, Math.round(46 * sy), centerX, Math.round(57 * sy));
            g.drawLine(Math.round(7 * sx), centerY, Math.round(18 * sx), centerY);
            g.drawLine(Math.round(46 * sx), centerY, Math.round(57 * sx), centerY);

            g.setColor(new Color(255, 255, 255, 230));
            int dot = Math.max(2, Math.round(4 * Math.min(sx, sy)));
            g.fillOval(centerX - dot / 2, centerY - dot / 2, dot, dot);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static Image scaleCursorImage(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return toCompatibleCursorImage(scaled);
    }

    private static Image toCompatibleCursorImage(BufferedImage source) {
        try {
            java.awt.GraphicsConfiguration gc = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();
            BufferedImage compatible = gc.createCompatibleImage(source.getWidth(), source.getHeight(), Transparency.TRANSLUCENT);
            Graphics2D g = compatible.createGraphics();
            try {
                g.drawImage(source, 0, 0, null);
            } finally {
                g.dispose();
            }
            return compatible;
        } catch (Throwable ignored) {
            return source;
        }
    }

    private static void drawManualBullets(Object bbpainter, FakeGraphics g, Sniper sniper,
                                          SniperManualState.Config cfg) {
        try {
            Object bf = BCUFields.get(bbpainter, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int stagePos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbpainter, "midh");
            float psiz = siz * 0.8f;
            float baseX = screenX(sniper.getPos(), siz, stagePos);
            float baseY = midh - 156.0f * siz;

            FakeTransform oldTransform = pushIdentityTransform(g);
            if (oldTransform == null) return;
            double oldCannonAngle = sniper.cannonAngle;
            double oldBulletAngle = sniper.bulletAngle;
            try {
                hideNativeBulletParts(sniper);
                long nowNanos = System.nanoTime();
                for (SniperManualState.ManualBullet bullet : cfg.manualBulletsSnapshot()) {
                    if (bullet.preTime > 0) continue;
                    ensureBulletFlightStarted(bullet, nowNanos);
                    drawManualBullet(g, sniper, bullet, baseX + bullet.originOffsetX,
                            baseY + bullet.originOffsetY, psiz, nowNanos);
                }
                hideNativeBulletParts(sniper);
            } finally {
                hideNativeBulletParts(sniper);
                getIdleAnim(sniper).ent[5].alter(11, (int) Math.round(oldCannonAngle * 10.0));
                getAttackAnim(sniper).ent[5].alter(11, (int) Math.round(oldBulletAngle * 10.0));
                popTransform(g, oldTransform);
            }
        } catch (Throwable t) {
            Logger.err("Manual Sniper bullet draw failed", t);
        }
    }

    private static void drawManualBullet(FakeGraphics g, Sniper sniper,
                                         SniperManualState.ManualBullet bullet,
                                         float baseX, float baseY, float psiz, long nowNanos) throws Exception {
        FakeTransform beforeBullet = g.getTransform();
        EAnimD<?> visual = BCUFields.getInt(sniper, "atkTime") > 0
                ? getAttackAnim(sniper) : getIdleAnim(sniper);
        try {
            float partOffset = nativeBulletPartOffset(sniper, bullet.currentPos(nowNanos), bullet.angle);

            visual.ent[5].alter(11, (int) Math.round(bullet.angle * 10.0));
            visual.ent[6].alter(4, partOffset);
            visual.ent[6].alter(9, 900.0f);
            visual.ent[6].alter(10, 900.0f);
            visual.ent[6].alter(12, 1000.0f);

            ImgCore.set(g);
            g.translate(baseX, baseY);
            P scale = P.newP(psiz, psiz);
            try {
                visual.ent[6].drawPart(g, scale);
            } finally {
                P.delete(scale);
            }
        } finally {
            try {
                g.setTransform(beforeBullet);
            } finally {
                g.delete(beforeBullet);
            }
        }
    }

    private static float nativeBulletPartOffset(Sniper sniper, float bulletPos, double angle) {
        return (float) ((bulletPos - sniper.b.ubase.pos - MUZZLE_BASE_OFFSET)
                / bulletCos(angle) * 0.32f * 0.75f);
    }

    private static void hideNativeBulletParts(Sniper sniper) throws Exception {
        getAttackAnim(sniper).ent[6].alter(12, 0.0f);
        getIdleAnim(sniper).ent[6].alter(12, 0.0f);
    }

    private static float screenX(float pos, float siz, int stagePos) {
        return (pos * 0.32f + 200.0f) * siz + stagePos;
    }

    private static void drawAdditionalSnipers(Object bbpainter, FakeGraphics g, Sniper sniper,
                                              SniperManualState.Config cfg) {
        try {
            Object bf = BCUFields.get(bbpainter, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int stagePos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbpainter, "midh");
            int time = BCUFields.getInt(sb, "time");
            float baseX = (sniper.getPos() * 0.32f + 200.0f) * siz + stagePos;
            float baseY = midh - 156.0f * siz;
            float psiz = siz * 0.8f;

            FakeTransform oldTransform = pushIdentityTransform(g);
            if (oldTransform == null) return;
            hideNativeBulletParts(sniper);
            double oldTargetAngle = sniper.targetAngle;
            double oldCannonAngle = sniper.cannonAngle;
            double oldBulletAngle = sniper.bulletAngle;
            try {
                for (int i = 1; i < cfg.sniperCount; i++) {
                    setIdentityTransform(g);
                    float[] offset = sniperOffset(i, siz, time);
                    SniperManualState.ManualBullet visual = cfg.visualManualBullet(i);
                    if (visual != null) {
                        sniper.targetAngle = visual.angle;
                        sniper.cannonAngle = visual.angle;
                        sniper.bulletAngle = visual.angle;
                        getIdleAnim(sniper).ent[5].alter(11, (int) Math.round(visual.angle * 10.0));
                        getAttackAnim(sniper).ent[5].alter(11, (int) Math.round(visual.angle * 10.0));
                    } else {
                        sniper.targetAngle = oldTargetAngle;
                        sniper.cannonAngle = oldCannonAngle;
                        sniper.bulletAngle = oldBulletAngle;
                        getIdleAnim(sniper).ent[5].alter(11, (int) Math.round(oldCannonAngle * 10.0));
                        getAttackAnim(sniper).ent[5].alter(11, (int) Math.round(oldBulletAngle * 10.0));
                    }
                    applySniperHeight(sniper, baseY + offset[1]);
                    P p = P.newP(baseX + offset[0], baseY + offset[1]);
                    try {
                        sniper.drawBase(g, p, psiz);
                    } finally {
                        P.delete(p);
                    }
                    setIdentityTransform(g);
                    hideNativeBulletParts(sniper);
                }
            } finally {
                popTransform(g, oldTransform);
                hideNativeBulletParts(sniper);
                sniper.targetAngle = oldTargetAngle;
                sniper.cannonAngle = oldCannonAngle;
                sniper.bulletAngle = oldBulletAngle;
                getIdleAnim(sniper).ent[5].alter(11, (int) Math.round(oldCannonAngle * 10.0));
                getAttackAnim(sniper).ent[5].alter(11, (int) Math.round(oldBulletAngle * 10.0));
                applySniperHeight(sniper, baseY);
            }
            sniper.height = baseY;
        } catch (Throwable t) {
            Logger.err("Manual Sniper clone draw failed", t);
        }
    }

    private static float[] sniperOffset(int index, float siz, int time) {
        if (index <= 0) return new float[]{0.0f, 0.0f};
        return cloneOffset(index, siz, time);
    }

    private static float[] cloneOffset(int index, float siz, int time) {
        int slot = index - 1;
        int row = slot / 3;
        int col = slot % 3;
        float scale = Math.max(1.0f, Math.min(1.4f, siz));
        float spacingX = 110.0f * scale;
        float spacingY = 88.0f * scale;
        float rowShift = (row % 2 == 0) ? 0.0f : 0.5f * spacingX;
        float bob = (float) Math.sin((time + index * 11) * Math.PI / 30.0) * Math.max(5.0f, 8.0f * siz);
        return new float[]{-(col + 1) * spacingX + rowShift, -row * spacingY + bob};
    }

    private static final class DialogResult {
        boolean accepted;
        boolean enabled;
        int cooldownFrames;
        int damage;
        int sniperCount;
    }

    private static final class AimPoint {
        final float pos;
        final float layer;

        AimPoint(float pos, float layer) {
            this.pos = pos;
            this.layer = layer;
        }
    }

    private static final class CastleBounds {
        final float left;
        final float top;
        final float right;
        final float bottom;
        final float centerX;
        final float centerY;

        CastleBounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.centerX = (left + right) * 0.5f;
            this.centerY = (top + bottom) * 0.5f;
        }

        boolean contains(float x, float y, float padding) {
            return x >= left - padding && x <= right + padding
                    && y >= top - padding && y <= bottom + padding;
        }
    }
}
