package manualcontrol.hooks;

import manualcontrol.HoldState;
import manualcontrol.FallingRegistry;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyBattleHooks;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.base.InteractiveBaseFeature;
import manualcontrol.crazy.base.SlingshotBaseFeature;
import manualcontrol.crazy.unit.BombItemFeature;
import manualcontrol.crazy.unit.BoosterSlotFeature;
import manualcontrol.crazy.unit.BossItemFeature;
import manualcontrol.crazy.unit.CatCoinFeature;
import manualcontrol.crazy.unit.EggPetFeature;
import manualcontrol.crazy.unit.TheRitualFeature;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;
import manualcontrol.render.ProgressRing;

import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.List;

public final class BattleInfoHooks {

    private BattleInfoHooks() {}

    private static final float HIT_RADIUS_BASE = 120.0f;

    public static Object cameraPainter(Object battleBox) {
        return battleBox == null ? null : BCUFields.get(battleBox, "bbp");
    }

    public static void beforeBattleConstructed(Object stage) {
        try {
            manualcontrol.custommap.CustomMapBattleRuntime.prepareStage(stage);
        } catch (Throwable t) {

            Logger.err("CustomMap: stage camera extent could not be prepared", t);
        }
    }

    public static void onBattleConstructed(Object page) {
        SniperManualHooks.onBattleConstructed(page);
        CrazyBattleHooks.onBattleConstructed(page);
    }

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (e == null) return false;
        try {
            Object bb = BCUFields.get(page, "bb");
            if (e.getSource() != bb) return false;

            HoldState state = HoldState.get();

            Object stateBp = state.getBattleInfoPage();
            if (stateBp != null && stateBp != page && state.getPhase() != HoldState.Phase.NONE) {
                Logger.log("Stale state detected (different BattleInfoPage) - resetting");
                state.forceReset();
            }
            if (CatCoinFeature.onMousePressed(page, e)) {
                return true;
            }
            if (TheRitualFeature.onMousePressed(page, e)) {
                return true;
            }
            if (EggPetFeature.onMousePressed(page, e)) {
                return true;
            }
            if (BoosterSlotFeature.onMousePressed(page, e)) {
                return true;
            }
            if (BombItemFeature.onMousePressed(page, e)) {
                return true;
            }
            if (BossItemFeature.onMousePressed(page, e)) {
                return true;
            }
            if (state.isHolding()) {
                Logger.log("onMousePressed: already holding, ignoring");
                return false;
            }
            if (state.isInControl()) {
                Logger.log("onMousePressed: in CONTROL, ignoring");
                return false;
            }
            if (SlingshotBaseFeature.onMousePressed(page, e)) {
                return true;
            }
            if (InteractiveBaseFeature.onMousePressed(page, e)) {
                return true;
            }
            if (SniperManualHooks.onMousePressed(page, e)) {
                return true;
            }
            Point p = e.getPoint();
            Object entity = findUnderCursor(page, p.x, p.y);
            if (entity == null) {

                if (e.getButton() == MouseEvent.BUTTON1)
                    manualcontrol.custommap.CustomMapRuntime
                            .beginVerticalCameraDrag(cameraPainter(bb), e.getY());
                Logger.log("onMousePressed at (" + p.x + "," + p.y + ") - no entity hit");
                return false;
            }

            if (!CrazyRuntime.isManualControlEnabled()) return false;
            float grabOffsetX = 0f;
            float grabOffsetY = 0f;
            float[] root = getEntityRootScreen(page, entity);
            if (root != null) {
                grabOffsetX = p.x - root[0];
                grabOffsetY = p.y - root[1];
            }
            state.startHold(entity, page, p.x, p.y, grabOffsetX, grabOffsetY);
            boolean isEnemy = EntityAccess.isEnemyUnit(entity);
            Logger.log("Hold STARTED: " + entity.getClass().getSimpleName()
                    + " (dire=" + EntityAccess.getDire(entity)
                    + ", " + (isEnemy ? "ENEMY" : "ALLY") + ")"
                    + String.format(" grabOffset=(%.1f,%.1f)", grabOffsetX, grabOffsetY));
            return true;
        } catch (Throwable t) {
            Logger.err("onMousePressed error", t);
            return false;
        }
    }

    private static long lastDragLog = 0;
    public static boolean onMouseDragged(Object page, MouseEvent e) {
        if (e == null) return false;
        try {
            Object bb = BCUFields.get(page, "bb");
            if (e.getSource() != bb) return false;

            if (BombItemFeature.onMouseDragged(page, e)) {
                return true;
            }
            if (EggPetFeature.onMouseDragged(page, e)) {
                return true;
            }
            if (BoosterSlotFeature.onMouseDragged(page, e)) {
                return true;
            }
            if (BossItemFeature.onMouseDragged(page, e)) {
                return true;
            }
            if (SlingshotBaseFeature.onMouseDragged(page, e)) {
                return true;
            }
            if (InteractiveBaseFeature.onMouseDragged(page, e)) {
                return true;
            }
            if (SniperManualHooks.onMouseDragged(page, e)) {
                return true;
            }

            HoldState state = HoldState.get();
            if (!state.isHolding()) {
                manualcontrol.custommap.CustomMapRuntime
                        .dragVerticalCamera(cameraPainter(bb), e.getY());
                return false;
            }

            Point p = e.getPoint();

            boolean above = isOverPlayerBase(page, p.x, p.y);
            state.updateCursor(p.x, p.y, above);

            long now = System.currentTimeMillis();
            if (now - lastDragLog > 500) {
                Logger.log("drag at (" + p.x + "," + p.y + ") above=" + above
                        + " progress=" + String.format("%.1f%%", state.getProgress() * 100));
                lastDragLog = now;
            }
            return true;
        } catch (Throwable t) {
            Logger.err("onMouseDragged error", t);
            return false;
        }
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        if (e == null) return false;
        try {
            manualcontrol.custommap.CustomMapRuntime.endVerticalCameraDrag();
            if (BombItemFeature.onMouseReleased(page, e)) {
                return true;
            }
            if (EggPetFeature.onMouseReleased(page, e)) {
                return true;
            }
            if (BoosterSlotFeature.onMouseReleased(page, e)) {
                return true;
            }
            if (BossItemFeature.onMouseReleased(page, e)) {
                return true;
            }
            if (SlingshotBaseFeature.onMouseReleased(page, e)) {
                return true;
            }
            if (InteractiveBaseFeature.onMouseReleased(page, e)) {
                return true;
            }
            if (SniperManualHooks.onMouseReleased(page, e)) {
                return true;
            }

            HoldState state = HoldState.get();
            if (!state.isHolding()) return false;

            float progress = state.getProgress();
            Object entity = state.getHeldEntity();
            boolean isEnemy = entity != null && EntityAccess.isEnemyUnit(entity);

            float[] vel = state.computeVelocity();
            float[] root = entity != null ? getEntityRootScreen(page, entity) : null;
            int releaseX = Math.round(root != null ? root[0] : state.getDragRootScreenX());
            int releaseY = Math.round(root != null ? root[1] : state.getDragRootScreenY());
            if (progress >= 1.0f) {
                Logger.log(String.format("HOLD complete (100%%) - FALLING + CONTROL on land. vel=(%.1f,%.1f)", vel[0], vel[1]));
                triggerSuccessBeam(page, entity);
                if (isEnemy) convertEnemy(entity);
                state.detachFalling(releaseX, releaseY, vel[0], vel[1], true);
            } else {
                Logger.log(String.format("HOLD released early at %.1f%% - FALLING back to AI. vel=(%.1f,%.1f)",
                        progress * 100, vel[0], vel[1]));
                state.detachFalling(releaseX, releaseY, vel[0], vel[1], false);
            }
            return true;
        } catch (Throwable t) {
            Logger.err("onMouseReleased error", t);
            return false;
        }
    }

    private static void triggerSuccessBeam(Object page, Object entity) {
        if (page == null || entity == null) return;
        try {
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");

            int rootX = Math.round((EntityAccess.getPos(entity) * 0.32f + 200f) * siz + sbPos);
            int rootY = Math.round(midh - (156 - EntityAccess.getLayer(entity) * 4) * siz);
            EntityAccess.SpriteOverlay overlay = EntityAccess.estimateSpriteOverlay(entity, siz, rootX, rootY);

            Object base = BCUFields.get(sb, "ubase");
            int baseX = Math.round((EntityAccess.getPos(base) * 0.32f + 200f) * siz + sbPos);
            int baseY = Math.round(midh - 210.0f * siz);
            ProgressRing.triggerSuccessBeam(baseX, baseY, overlay.centerX, overlay.centerY, overlay.radius);
        } catch (Throwable t) {
            Logger.err("triggerSuccessBeam failed", t);
        }
    }

    private static void convertEnemy(Object entity) {
        try {
            java.lang.reflect.Field dire = BCUFields.field(entity.getClass(), "dire");
            dire.setInt(entity, -1);
            manualcontrol.ConvertedRegistry.mark(entity);
            Logger.log("Enemy converted: dire=-1 + marked for sprite mirror");
        } catch (Throwable t) {
            Logger.err("convertEnemy failed", t);
        }
    }

    private static boolean isOverPlayerBase(Object page, int cursorX, int cursorY) {
        try {
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            Object base = BCUFields.get(sb, "ubase");
            if (base == null) return false;

            float baseX = (EntityAccess.getPos(base) * 0.32f + 200f) * siz + sbPos;
            float baseY = midh - (156 - EntityAccess.getLayer(base) * 4) * siz;

            float left, right, top;
            EntityAccess.SpriteBounds b = EntityAccess.estimateSpriteBounds(base, siz, baseX, baseY);
            if (b != null && (b.right - b.left) > 4f && (b.right - b.left) < 4000f) {
                left = b.left;
                right = b.right;
                top = b.top;
            } else {
                float half = 90f * Math.max(0.5f, siz);
                left = baseX - half;
                right = baseX + half;
                top = baseY - 150f * Math.max(0.5f, siz);
            }

            float padX = 24f;
            return cursorX >= left - padX && cursorX <= right + padX && cursorY < top;
        } catch (Throwable t) {
            return false;
        }
    }

    private static float[] getEntityRootScreen(Object page, Object entity) {
        try {
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            Object bf = BCUFields.get(bbp, "bf");
            Object sb = BCUFields.get(bf, "sb");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            float screenX = (EntityAccess.getPos(entity) * 0.32f + 200f) * siz + sbPos;
            float screenY = midh - (156 - EntityAccess.getLayer(entity) * 4) * siz;
            return new float[]{screenX, screenY};
        } catch (Throwable t) {
            try {
                Object basis = BCUFields.get(page, "basis");
                Object sb = BCUFields.get(basis, "sb");
                Object bb = BCUFields.get(page, "bb");
                Object bbp = BCUFields.get(bb, "bbp");
                float siz = BCUFields.getFloat(sb, "siz");
                int sbPos = BCUFields.getInt(sb, "pos");
                int midh = BCUFields.getInt(bbp, "midh");
                float screenX = (EntityAccess.getPos(entity) * 0.32f + 200f) * siz + sbPos;
                float screenY = midh - (156 - EntityAccess.getLayer(entity) * 4) * siz;
                return new float[]{screenX, screenY};
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object findUnderCursor(Object page, int mx, int my) {
        try {
            Object basis = BCUFields.get(page, "basis");
            Object sb = BCUFields.get(basis, "sb");
            List<Object> entities = (List<Object>) BCUFields.get(sb, "le");
            Object bb = BCUFields.get(page, "bb");

            float siz = 1.0f;
            int sbPos = 0;
            int midh = 500;
            try {
                Object bbp = BCUFields.get(bb, "bbp");
                siz = BCUFields.getFloat(sb, "siz");
                sbPos = BCUFields.getInt(sb, "pos");
                midh = BCUFields.getInt(bbp, "midh");
            } catch (Throwable transformErr) {
                Logger.log("findUnderCursor: can't read transform values, using defaults");
            }

            Logger.log("findUnderCursor cursor=(" + mx + "," + my + ") transform: siz=" + siz + " sbPos=" + sbPos + " midh=" + midh);

            final float HIT_RADIUS_X = 80.0f;
            final float HIT_RADIUS_Y_BAND = 250f;

            Object best = null;
            float bestDistSq = Float.MAX_VALUE;
            int candidates = 0;
            int loggedCount = 0;

            for (Object ent : entities) {
                if (ent == null) continue;
                if (EntityAccess.isDead(ent)) continue;
                if (EntityAccess.isBase(ent)) continue;
                if (EntityAccess.isBoss(ent)) continue;
                if (FallingRegistry.isManaged(ent)) continue;

                float entX = EntityAccess.getPos(ent);
                int entLayer = EntityAccess.getLayer(ent);

                float screenX = (entX * 0.32f + 200f) * siz + sbPos;
                float screenY = midh - (156 - entLayer * 4) * siz;

                float dx = screenX - mx;
                float dy = screenY - my;
                float distSq = dx * dx + dy * dy;
                candidates++;

                if (loggedCount < 6) {
                    Logger.log("  ent " + ent.getClass().getSimpleName()
                            + " game(pos=" + entX + " layer=" + entLayer + ")"
                            + " screen(" + (int) screenX + "," + (int) screenY + ")"
                            + " dx=" + (int) Math.abs(dx) + " dy=" + (int) Math.abs(dy));
                    loggedCount++;
                }

                EntityAccess.SpriteBounds b = EntityAccess.estimateSpriteBounds(ent, siz, screenX, screenY);
                if (b != null && b.contains(mx, my, 4f)) {
                    float cdx = b.centerX - mx;
                    float cdy = b.centerY - my;
                    float cDist = cdx * cdx + cdy * cdy;
                    if (cDist < bestDistSq) {
                        bestDistSq = cDist;
                        best = ent;
                    }
                }
            }

            if (best != null) {
                Logger.log("findUnderCursor: " + candidates + " entities, picked dist="
                        + (int) Math.sqrt(bestDistSq) + "px (" + best.getClass().getSimpleName()
                        + " pos=" + EntityAccess.getPos(best) + ")");
            } else {
                Logger.log("findUnderCursor: " + candidates + " entities, NONE matched. "
                        + "Try larger area or different position.");
            }
            return best;
        } catch (Throwable t) {
            Logger.err("findUnderCursor error", t);
            return null;
        }
    }

    private static int getCanvasHeight(Object bb) {
        try {
            return (int) BCUFields.invoke(bb, "getHeight");
        } catch (Throwable t) { return 800; }
    }
    private static int getCanvasWidth(Object bb) {
        try {
            return (int) BCUFields.invoke(bb, "getWidth");
        } catch (Throwable t) { return 1200; }
    }
}
