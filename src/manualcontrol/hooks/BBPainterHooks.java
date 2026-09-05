package manualcontrol.hooks;

import common.system.fake.FakeGraphics;
import manualcontrol.OptionalModes;
import manualcontrol.HoldState;
import manualcontrol.EntitySelector;
import manualcontrol.NativeRenderLifecycleGuard;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.beam.ArmyCanonFeature;
import manualcontrol.crazy.beam.BeamFeature;
import manualcontrol.crazy.beam.CopyCatUfoFeature;
import manualcontrol.crazy.base.CatCanonFeature;
import manualcontrol.crazy.base.SlingshotBaseFeature;
import manualcontrol.crazy.unit.BombItemFeature;
import manualcontrol.crazy.unit.BoosterSlotFeature;
import manualcontrol.crazy.unit.BossItemFeature;
import manualcontrol.crazy.unit.CatCoinFeature;
import manualcontrol.crazy.unit.DiceSlotFeature;
import manualcontrol.crazy.unit.EggPetFeature;
import manualcontrol.crazy.unit.ReincarnationFeature;
import manualcontrol.crazy.unit.StackUnitFeature;
import manualcontrol.crazy.unit.TheRitualFeature;
import manualcontrol.crazy.fall.ImpactFallFeature;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.EntityAccess;
import manualcontrol.render.ProgressRing;
import java.awt.Point;

public final class BBPainterHooks {

    private BBPainterHooks() {}

    public static boolean skipCastle(Object bbpainter) {
        try {
            Object stage = BBPainterAccess.getStageBasis(bbpainter);
            return manualcontrol.adventure.AdventureBridge.isActiveStage(stage)
                    || OptionalModes.isActiveStage(stage);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void onDrawBeforeCastle(Object bbpainter, FakeGraphics g) {
        try {
            manualcontrol.custommap.CustomMapRuntime.drawUnderBeforeCastle(bbpainter, g);
        } catch (Throwable t) {
            manualcontrol.Logger.err("CustomMap: pre-castle terrain draw failed", t);
        }
    }

    public static void beginEnemyBaseDraw(Object bbpainter, FakeGraphics g) {
        manualcontrol.custommap.CustomMapBattleRuntime.beginEnemyBaseDraw(bbpainter, g);
    }

    public static void beginPlayerBaseDraw(Object bbpainter, FakeGraphics g) {
        manualcontrol.custommap.CustomMapBattleRuntime.beginPlayerBaseDraw(bbpainter, g);
    }

    public static void endBaseDraw(Object bbpainter, FakeGraphics g) {
        manualcontrol.custommap.CustomMapBattleRuntime.endBaseDraw(bbpainter, g);
    }

    public static boolean skipBottomBar(Object bbpainter) {
        try {
            return OptionalModes.hidesNativeUi(BBPainterAccess.getStageBasis(bbpainter));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean skipTopBar(Object bbpainter) {
        try {
            return OptionalModes.hidesNativeUi(BBPainterAccess.getStageBasis(bbpainter));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean onPress(Object bbpainter, Point p) {
        if (p == null) return false;

        if (OptionalModes.onPress(bbpainter, p.x, p.y)) return true;

        if (!CrazyRuntime.isManualControlEnabled()) return false;
        try {
            HoldState state = HoldState.get();
            if (state.isHolding()) {
                System.out.println("[ManualControl] onPress: already holding, ignoring");
                return false;
            }

            int entCount = -1;
            try {
                entCount = manualcontrol.reflect.BBPainterAccess.getEntityList(bbpainter).size();
            } catch (Throwable ignored) {}
            Object entity = EntitySelector.findUnderCursor(bbpainter, p.x, p.y);
            if (entity == null) {
                System.out.println("[ManualControl] onPress at (" + p.x + "," + p.y
                        + ") - no entity hit (entities on field: " + entCount + ")");
                return false;
            }
            float siz = BBPainterAccess.getSiz(bbpainter);
            int stagePos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            float rootX = EntitySelector.entityXtoScreenX(EntityAccess.getPos(entity), siz, stagePos);
            float rootY = EntitySelector.entityYatLayer(EntityAccess.getLayer(entity), midh, siz);
            state.startHold(entity, bbpainter, p.x, p.y, p.x - rootX, p.y - rootY);
            boolean isEnemy = EntityAccess.isEnemyUnit(entity);
            System.out.println("[ManualControl] Hold started: "
                    + entity.getClass().getSimpleName()
                    + " (dire=" + EntityAccess.getDire(entity)
                    + ", " + (isEnemy ? "enemy" : "ally") + ")");
            return true;
        } catch (Throwable t) {
            System.err.println("[ManualControl] onPress error: " + t);
            t.printStackTrace();
            return false;
        }
    }

    public static boolean onDrag(Object bbpainter, Point p) {
        if (p == null) return false;
        try {
            HoldState state = HoldState.get();
            if (!state.isHolding()) return false;

            boolean above = isOverPlayerBase(bbpainter, p.x, p.y);
            state.updateCursor(p.x, p.y, above);
            return true;
        } catch (Throwable t) {
            System.err.println("[ManualControl] onDrag error: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static boolean isOverPlayerBase(Object bbpainter, int cursorX, int cursorY) {
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int stagePos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            Object base = BBPainterAccess.getPlayerBase(bbpainter);
            if (base == null) return false;

            float baseX = EntitySelector.entityXtoScreenX(EntityAccess.getPos(base), siz, stagePos);
            float baseY = EntitySelector.entityYatLayer(EntityAccess.getLayer(base), midh, siz);

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
            boolean overX = cursorX >= left - padX && cursorX <= right + padX;
            boolean aboveTop = cursorY < top;
            return overX && aboveTop;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean onRelease(Object bbpainter) {
        try {
            HoldState state = HoldState.get();
            if (!state.isHolding()) return false;

            float progress = state.getProgress();
            Object entity = state.getHeldEntity();
            boolean isEnemy = entity != null && EntityAccess.isEnemyUnit(entity);

            if (progress >= 1.0f) {
                System.out.println("[ManualControl] HOLD complete (100%) - "
                        + (isEnemy ? "CONVERT" : "CONTROL") + " activated (Phase 3 will implement control)");

            } else {
                System.out.println(String.format(
                        "[ManualControl] HOLD released early at %.1f%% - unit returns to AI",
                        progress * 100));
            }
            state.release();
            return true;
        } catch (Throwable t) {
            System.err.println("[ManualControl] onRelease error: " + t);
            t.printStackTrace();
            return false;
        }
    }

    public static boolean onWheel(Object bbpainter, Point point, int rotation) {
        try {
            if (point != null && OptionalModes.onWheel(
                    bbpainter, point.x, point.y, rotation)) return true;
            return manualcontrol.custommap.CustomMapRuntime.onWheel(
                    bbpainter, point, rotation);
        } catch (Throwable t) {
            manualcontrol.Logger.err("CustomMap: wheel zoom failed", t);
            return false;
        }
    }

    private static Object fadeEntity;
    private static long fadeStartMs;
    private static final long FADE_MS = 1000L;

    public static void beforeDrawFrame(Object bbpainter) {
        try {
            if (bbpainter == null) return;
            Object stage = BBPainterAccess.getStageBasis(bbpainter);
            NativeRenderLifecycleGuard.beforeDrawFrame(stage);

            manualcontrol.custommap.CustomMapRuntime.applyZoomOverride(bbpainter);
            manualcontrol.custommap.CustomMapRuntime.applyManualVerticalCamera(bbpainter);
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(stage);
            if (rt != null) {
                BossItemFeature.applyCameraFocus(rt, bbpainter);
            }
            manualcontrol.adventure.AdventureBridge.applyCamera(bbpainter, stage);
            OptionalModes.applyCamera(bbpainter, stage);
        } catch (Throwable t) {
            System.err.println("[ManualControl] beforeDrawFrame error: " + t);
            t.printStackTrace();
        }
    }

    public static void onDrawUnder(Object bbpainter, FakeGraphics g) {
        try {
            SniperManualHooks.beforeBattleDraw(bbpainter);
            if (g == null) return;
            Object stage = BBPainterAccess.getStageBasis(bbpainter);
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(stage);
            if (rt != null) {
                BossItemFeature.drawUnder(rt, bbpainter, g);
                BombItemFeature.drawUnder(rt, bbpainter, g);
                EggPetFeature.drawUnder(rt, bbpainter, g);
                ImpactFallFeature.drawUnder(rt, bbpainter, g);
            }
            manualcontrol.custommap.CustomMapRuntime.drawUnder(bbpainter, g);
            manualcontrol.custommap.CustomMapBattleRuntime.drawVfx(bbpainter, g);

            manualcontrol.adventure.AdventureBridge.drawWorld(bbpainter, g);

            OptionalModes.drawWorld(bbpainter, g);
            HoldState state = HoldState.get();

            if (state.isInControl()) {
                manualcontrol.ControlMarker.setActive(state.getHeldEntity());
            } else {
                manualcontrol.ControlMarker.beginFadeOut();
            }
            drawControlMarker(g);

            if (!state.isHolding()) {
                fadeEntity = null;
                fadeStartMs = 0L;
                return;
            }
            Object entity = state.getHeldEntity();
            if (entity == null) return;

            if (entity != fadeEntity) {
                fadeEntity = entity;
                fadeStartMs = 0L;
            }

            if (!state.isChargingZone()) {
                fadeStartMs = 0L;
                return;
            }

            if (!manualcontrol.SpriteAnchor.hasFreshBox(entity)) {
                return;
            }

            long now = System.currentTimeMillis();
            if (fadeStartMs == 0L) fadeStartMs = now;
            float opacity = Math.max(0f, Math.min(1f, (now - fadeStartMs) / (float) FADE_MS));

            boolean isEnemy = EntityAccess.isEnemyUnit(entity);
            float siz = BBPainterAccess.getSiz(bbpainter);
            int stagePos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            EntityAccess.SpriteOverlay ov = ringOverlay(entity, siz, stagePos, midh);
            int[] beamOrigin = playerBeamOrigin(bbpainter, siz, stagePos, midh);
            ProgressRing.draw(g, ov.centerX, ov.centerY, ov.radius, state.getProgress(), isEnemy,
                    beamOrigin[0], beamOrigin[1], opacity);
        } catch (Throwable t) {
            System.err.println("[ManualControl] onDrawUnder error: " + t);
            t.printStackTrace();
        }
    }

    public static void onDrawAfterEntity(Object bbpainter, FakeGraphics g) {
        try {
            manualcontrol.custommap.CustomMapRuntime.drawWaterForeground(bbpainter, g);
            manualcontrol.custommap.CustomMapBattleRuntime.drawWaterVfxForeground(
                    bbpainter, g);
        } catch (Throwable t) {
            manualcontrol.Logger.err("CustomMap: post-unit water draw failed", t);
        }
    }

    private static void drawControlMarker(FakeGraphics g) {
        Object e = manualcontrol.ControlMarker.getEntity();
        if (e == null) return;
        if (!manualcontrol.SpriteAnchor.hasFreshLiveBox(e)) return;
        float op = manualcontrol.ControlMarker.opacity();
        if (op <= 0.01f) return;
        int cx = Math.round(manualcontrol.SpriteAnchor.getLiveBodyCX());
        int headTopY = Math.round(manualcontrol.SpriteAnchor.getLiveMinY());
        ProgressRing.drawControlMarker(g, cx, headTopY, System.currentTimeMillis(), op);
    }

    public static void onDraw(Object bbpainter, FakeGraphics g) {
        try {
            if (g == null) return;

            Object stage = BBPainterAccess.getStageBasis(bbpainter);
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(stage);
            if (rt != null) {
                BeamFeature.draw(rt, bbpainter, g);
                ArmyCanonFeature.draw(rt, bbpainter, g);
                CopyCatUfoFeature.draw(rt, bbpainter, g);
                BossItemFeature.draw(rt, bbpainter, g);
                BombItemFeature.draw(rt, bbpainter, g);
                CatCoinFeature.draw(rt, bbpainter, g);
                TheRitualFeature.draw(rt, bbpainter, g);
                StackUnitFeature.draw(rt, bbpainter, g);
                ReincarnationFeature.draw(rt, bbpainter, g);
                manualcontrol.crazy.unit.SummonAttachFeature.draw(rt, bbpainter, g);
                DiceSlotFeature.draw(rt, bbpainter, g);
                EggPetFeature.draw(rt, bbpainter, g);
                ImpactFallFeature.drawOverlay(rt, bbpainter, g);
                SlingshotBaseFeature.draw(rt, bbpainter, g);
                CatCanonFeature.draw(rt, bbpainter, g);

                BoosterSlotFeature.draw(rt, bbpainter, g);
            }
            manualcontrol.custommap.CustomMapRuntime.drawOver(bbpainter, g);

            manualcontrol.adventure.AdventureBridge.drawWorldOverlay(bbpainter, g);
            OptionalModes.drawWorldOverlay(bbpainter, g);
            SniperManualHooks.onDraw(bbpainter, g);

            manualcontrol.crazy.collision.DeathLaunchFeature.drawEffects(bbpainter, g);
            manualcontrol.crazy.collision.SurgeJuggleFeature.drawEffects(bbpainter, g);

            manualcontrol.crazy.collision.CollisionDebug.drawImpactVectors(bbpainter, g);

            manualcontrol.crazy.collision.CollisionHud.draw(g);

            manualcontrol.adventure.AdventureBridge.drawHud(bbpainter, g);

            OptionalModes.drawHud(bbpainter, g);
        } catch (Throwable t) {
            System.err.println("[ManualControl] onDraw error: " + t);
            t.printStackTrace();
        }
    }

    public static void onDrawNativeCannonAttacks(Object bbpainter, FakeGraphics g) {
        try {
            if (g == null) return;
            Object stage = BBPainterAccess.getStageBasis(bbpainter);
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(stage);
            if (rt != null) {
                BossItemFeature.drawNativeCannonAttacks(rt, bbpainter, g);
            }
        } catch (Throwable t) {
            System.err.println("[ManualControl] onDrawNativeCannonAttacks error: " + t);
            t.printStackTrace();
        }
    }

    private static void triggerSuccessBeam(Object bbpainter, Object entity) {
        if (bbpainter == null || entity == null) return;
        try {
            float siz = BBPainterAccess.getSiz(bbpainter);
            int stagePos = BBPainterAccess.getStagePos(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);
            EntityAccess.SpriteOverlay ov = ringOverlay(entity, siz, stagePos, midh);
            int[] beamOrigin = playerBeamOrigin(bbpainter, siz, stagePos, midh);
            ProgressRing.triggerSuccessBeam(beamOrigin[0], beamOrigin[1], ov.centerX, ov.centerY, ov.radius);
        } catch (Throwable t) {
            manualcontrol.Logger.err("triggerSuccessBeam failed", t);
        }
    }

    private static EntityAccess.SpriteOverlay ringOverlay(Object entity, float siz, int stagePos, int midh) {

        if (manualcontrol.SpriteAnchor.hasFreshBox(entity)) {
            return EntityAccess.overlayFromBox(
                    manualcontrol.SpriteAnchor.getBoxMinX(),
                    manualcontrol.SpriteAnchor.getBoxMinY(),
                    manualcontrol.SpriteAnchor.getBoxMaxX(),
                    manualcontrol.SpriteAnchor.getBoxMaxY(),
                    manualcontrol.SpriteAnchor.getBoxBodyCX(),
                    manualcontrol.SpriteAnchor.getBoxBodyCY());
        }
        if (manualcontrol.SpriteAnchor.hasFreshAnchor(entity)) {
            int anchorX = Math.round(manualcontrol.SpriteAnchor.getX());
            int anchorY = Math.round(manualcontrol.SpriteAnchor.getY());
            return EntityAccess.overlayFromAnchor(entity, anchorX, anchorY, siz);
        }
        int rootX = Math.round(EntitySelector.entityXtoScreenX(EntityAccess.getPos(entity), siz, stagePos));
        int rootY = Math.round(EntitySelector.entityYatLayer(EntityAccess.getLayer(entity), midh, siz));
        return EntityAccess.estimateSpriteOverlay(entity, siz, rootX, rootY);
    }

    private static int[] playerBeamOrigin(Object bbpainter, float siz, int stagePos, int midh) {
        try {
            Object base = BBPainterAccess.getPlayerBase(bbpainter);
            int x = Math.round(EntitySelector.entityXtoScreenX(EntityAccess.getPos(base), siz, stagePos));
            int y = Math.round(midh - 210.0f * siz);
            return new int[]{x, y};
        } catch (Throwable ignored) {
            try {
                int x = Math.round(BBPainterAccess.getWidth(bbpainter) - 90.0f * Math.max(0.5f, siz));
                int y = Math.round(midh - 210.0f * Math.max(0.5f, siz));
                return new int[]{x, y};
            } catch (Throwable ignored2) {
                return new int[]{0, midh};
            }
        }
    }
}
