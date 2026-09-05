package manualcontrol.hooks;

import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import manualcontrol.ConvertedRegistry;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.unit.GrowingUnits;
import manualcontrol.crazy.unit.ReincarnationFeature;
import manualcontrol.crazy.unit.StackUnitFeature;
import manualcontrol.crazy.unit.BombItemFeature;
import manualcontrol.crazy.unit.BoosterSlotFeature;
import manualcontrol.crazy.unit.BossItemFeature;
import manualcontrol.crazy.unit.CatCoinFeature;
import manualcontrol.crazy.unit.EggPetFeature;
import manualcontrol.crazy.unit.TheRitualFeature;
import manualcontrol.crazy.base.CatCanonFeature;
import manualcontrol.crazy.base.SlingshotBaseFeature;
import manualcontrol.crazy.fall.ImpactFallFeature;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class DrawHook {

    private DrawHook() {}

    private static volatile Method drawMethod;
    private static volatile Method drawEffMethod;
    private static final Set<Object> MIRROR_LOGGED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    private static long lastLog = 0;
    private static int callCount = 0;
    public static void drawAnim(Object am, FakeGraphics g, P p, float siz) {
        if (am == null || g == null || p == null) return;
        try {
            Object entity = getEntityFromAnimManager(am);
            if (SlingshotBaseFeature.shouldHideNativeSprite(entity)) {
                return;
            }
            if (manualcontrol.crazy.collision.SurgeJuggleFeature.shouldHideNativeSprite(entity)) {
                manualcontrol.crazy.collision.SurgeJuggleFeature.drawCremation(entity, g, p, siz);
                return;
            }
            boolean mirror = shouldMirrorForCurrentFacing(entity);
            boolean target = entity != null && isAnchorTarget(entity);
            boolean bossBoundsTarget = entity != null && BossItemFeature.wantsSpriteBounds(entity);
            boolean bombBoundsTarget = entity != null && BombItemFeature.wantsSpriteBounds(entity);
            boolean beamBoundsTarget = entity != null
                    && manualcontrol.crazy.beam.BeamFeature.wantsSpriteBounds(entity);
            boolean catCanonBoundsTarget = entity != null && CatCanonFeature.wantsSpriteBounds(entity);
            boolean copyCatBoundsTarget = entity != null
                    && manualcontrol.crazy.beam.CopyCatUfoFeature.wantsSpriteBounds(entity);
            boolean measureTarget = target || bossBoundsTarget || bombBoundsTarget || beamBoundsTarget
                    || catCanonBoundsTarget || copyCatBoundsTarget;
            float growthScale = GrowingUnits.scaleFor(entity)
                    * StackUnitFeature.scaleFor(entity)
                    * BossItemFeature.drawScaleFor(entity)
                    * BombItemFeature.drawScaleFor(entity)
                    * EggPetFeature.drawScaleFor(entity)
                    * BoosterSlotFeature.drawScaleFor(entity)
                    * TheRitualFeature.drawScaleFor(entity)
                    * manualcontrol.adventure.AdventureBridge.drawScaleFor(entity);
            float slingshotSpawnScale = SlingshotBaseFeature.drawScaleFor(entity);
            float catCanonScale = CatCanonFeature.drawScaleFor(entity);
            float[] impactSquash = ImpactFallFeature.squashScale(entity);
            float[] catCoinEmerge = CatCoinFeature.emergeDraw(entity);
            float[] ritualMaterialize = TheRitualFeature.materializeDraw(entity);
            float[] reincMaterialize = ReincarnationFeature.materializeDraw(entity);
            float armyScale = 1f;
            float armyRot = 0f;
            try {
                if (manualcontrol.crazy.beam.ArmyCanonFeature.isManaged(entity)) {
                    armyScale = manualcontrol.crazy.beam.ArmyCanonFeature.drawScale(entity);
                    armyRot = manualcontrol.crazy.beam.ArmyCanonFeature.drawRotation(entity);
                }
            } catch (Throwable ignored) {}

            if (target) {
                manualcontrol.SpriteAnchor.record(entity, p.x, p.y, siz);
            }

            boolean hitbackAnim = target && isHitbackAnim(am);
            BoundsRecorder rec = null;
            FakeGraphics drawG = g;
            if (measureTarget) {
                rec = BoundsRecorder.begin(g);
                drawG = rec.proxy();
            }

            callCount++;
            long now = System.currentTimeMillis();

            if (now - lastLog > 1000) {
                Logger.log("DrawHook.drawAnim active: calls=" + callCount
                        + " lastEntity=" + (entity == null ? "null" : entity.getClass().getSimpleName())
                        + " mirror=" + mirror);
                lastLog = now;
                callCount = 0;
            }

            float copyCatScale = manualcontrol.crazy.beam.CopyCatUfoFeature.drawScaleFor(entity);
            FakeTransform copyCatTransform = null;
            boolean copyCatScaled = Math.abs(copyCatScale - 1f) > 0.001f;
            if (copyCatScaled) {
                copyCatTransform = drawG.getTransform();
                drawG.translate(p.x, p.y);
                drawG.scale(copyCatScale, copyCatScale);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform growthTransform = null;
            boolean scaled = growthScale > 1.0001f;
            if (scaled) {
                growthTransform = drawG.getTransform();
                drawG.translate(p.x, p.y);
                drawG.scale(growthScale, growthScale);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform slingshotSpawnTransform = null;
            boolean slingshotSpawnScaled = Math.abs(slingshotSpawnScale - 1f) > 0.001f;
            if (slingshotSpawnScaled) {
                slingshotSpawnTransform = drawG.getTransform();
                drawG.translate(p.x, p.y);
                drawG.scale(slingshotSpawnScale, slingshotSpawnScale);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform catCanonTransform = null;
            boolean catCanonScaled = Math.abs(catCanonScale - 1f) > 0.001f;
            if (catCanonScaled) {
                catCanonTransform = drawG.getTransform();
                drawG.translate(p.x, p.y);
                drawG.scale(catCanonScale, catCanonScale);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform armyTransform = null;
            boolean armyTransformed = Math.abs(armyScale - 1f) > 0.001f || Math.abs(armyRot) > 0.001f;
            if (armyTransformed) {
                armyTransform = drawG.getTransform();
                drawG.translate(p.x, p.y);
                if (Math.abs(armyRot) > 0.001f) drawG.rotate(armyRot);
                if (Math.abs(armyScale - 1f) > 0.001f) drawG.scale(armyScale, armyScale);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform impactTransform = null;
            boolean impactTransformed = impactSquash != null
                    && (Math.abs(impactSquash[0] - 1f) > 0.001f || Math.abs(impactSquash[1] - 1f) > 0.001f);
            if (impactTransformed) {
                impactTransform = drawG.getTransform();
                drawG.translate(p.x, p.y);
                drawG.scale(impactSquash[0], impactSquash[1]);
                drawG.translate(-p.x, -p.y);
            }

            float[] terrainMotion = null;
            try {
                terrainMotion = manualcontrol.custommap.CustomMapBattleRuntime
                        .motionDrawFx(entity);
            } catch (Throwable ignored) {}
            float[] deathLaunch = null;
            try {
                deathLaunch = manualcontrol.crazy.collision.DeathLaunchFeature.drawFx(entity);
                if (deathLaunch == null) {
                    deathLaunch = manualcontrol.crazy.collision.SurgeJuggleFeature.drawFx(entity);
                }
            } catch (Throwable ignored) {}
            boolean launched = deathLaunch != null;
            boolean terrainMotionTransformed = terrainMotion != null;

            boolean posedBody = launched || (terrainMotionTransformed
                    && Math.abs(terrainMotion[0]) > 0.02f);
            FakeTransform poseGround = posedBody ? drawG.getTransform() : null;

            FakeTransform terrainMotionTransform = null;
            if (terrainMotionTransformed) {
                terrainMotionTransform = drawG.getTransform();
                drawG.translate(p.x, p.y);
                drawG.rotate(terrainMotion[0]);
                drawG.scale(terrainMotion[1], terrainMotion[2]);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform launchTransform = null;
            if (launched) {
                launchTransform = drawG.getTransform();
                float liftPx = deathLaunch[0] * 0.32f * siz;

                float cx = p.x, cy = p.y;
                try {
                    float[] c = manualcontrol.crazy.collision.CollisionDebug
                            .spriteCenterOffsetPx(entity, siz * growthScale);
                    if (c != null) {
                        cx = p.x + (mirror ? -c[0] : c[0]);
                        cy = p.y + c[1];
                    }
                } catch (Throwable ignored) {}

                float interpXpx = deathLaunch.length >= 6
                        ? deathLaunch[5] * 0.32f * siz : 0f;
                drawG.translate(cx + interpXpx, cy - liftPx);
                if (deathLaunch.length >= 5) {

                    float squash = deathLaunch[4];
                    if (squash > 0.001f) {
                        drawG.scale(1f + squash * 0.9f, 1f - squash * 0.55f);
                    }

                    float stretch = deathLaunch[2];
                    if (stretch > 0.001f) {
                        drawG.rotate(deathLaunch[3]);
                        drawG.scale(1f + stretch, 1f - stretch * 0.6f);
                        drawG.rotate(-deathLaunch[3]);
                    }
                }
                drawG.rotate(deathLaunch[1]);
                drawG.translate(-cx, -cy);
            }

            FakeTransform catCoinTransform = null;
            boolean catCoinTransformed = catCoinEmerge != null;
            if (catCoinTransformed) {
                catCoinTransform = drawG.getTransform();
                int alpha = Math.max(0, Math.min(255, Math.round(catCoinEmerge[2] * 255f)));
                drawG.setComposite(FakeGraphics.TRANS, alpha, 0);
                drawG.translate(p.x, p.y + catCoinEmerge[0]);
                drawG.scale(1f, catCoinEmerge[1]);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform ritualTransform = null;
            boolean ritualMaterialized = ritualMaterialize != null;
            if (ritualMaterialized) {
                ritualTransform = drawG.getTransform();
                int alpha = Math.max(0, Math.min(255, Math.round(ritualMaterialize[2] * 255f)));
                drawG.setComposite(FakeGraphics.TRANS, alpha, 0);
                drawG.translate(p.x, p.y + ritualMaterialize[0]);
                drawG.scale(1f, ritualMaterialize[1]);
                drawG.translate(-p.x, -p.y);
            }

            FakeTransform reincTransform = null;
            boolean reincMaterialized = reincMaterialize != null;
            if (reincMaterialized) {
                reincTransform = drawG.getTransform();
                int alpha = Math.max(0, Math.min(255, Math.round(reincMaterialize[2] * 255f)));
                drawG.setComposite(FakeGraphics.TRANS, alpha, 0);
                drawG.translate(p.x, p.y + reincMaterialize[0]);
                drawG.scale(1f, reincMaterialize[1]);
                drawG.translate(-p.x, -p.y);
            }

            float[] evoFx = null;
            try {
                evoFx = manualcontrol.crazy.beam.BeamFeature.evolutionDrawFx(entity);
            } catch (Throwable ignored) {}
            FakeTransform evoTransform = null;
            boolean evoScaled = evoFx != null
                    && (Math.abs(evoFx[1] - 1f) > 0.001f || Math.abs(evoFx[2]) > 0.01f);
            boolean evoAlpha = evoFx != null && evoFx[0] < 254.5f;
            if (evoScaled) {
                evoTransform = drawG.getTransform();
                drawG.translate(p.x + evoFx[2], p.y);
                drawG.scale(evoFx[1], evoFx[1]);
                drawG.translate(-p.x, -p.y);
            }
            if (evoAlpha) {
                drawG.setComposite(FakeGraphics.TRANS,
                        Math.max(0, Math.min(255, Math.round(evoFx[0]))), 0);
            }

            try {
                if (mirror) {
                    if (MIRROR_LOGGED.add(entity)) {
                        Logger.log("DrawHook mirror active for " + entity.getClass().getSimpleName()
                                + " p=(" + Math.round(p.x) + "," + Math.round(p.y) + ")"
                                + " siz=" + siz);
                    }

                    float pivotX = p.x;
                    float mc = manualcontrol.adventure.AdventureBridge.mirrorCenter(entity);
                    if (mc == mc) pivotX = p.x + mc * siz;
                    FakeTransform at = drawG.getTransform();
                    try {
                        drawG.translate(pivotX, 0);
                        drawG.scale(-1f, 1f);
                        drawG.translate(-pivotX, 0);

                        manualcontrol.crazy.collision.BodyShadow.Hidden shadow =
                                posedBody
                                ? manualcontrol.crazy.collision.BodyShadow.beginPosed(
                                entity, am, drawG, p, siz, poseGround, true, pivotX)
                                : null;
                        try {
                            if (!manualcontrol.adventure.AdventureBridge.drawSplitShadow(entity, am, drawG, p, siz)) {
                                invokeDraw(am, drawG, p, siz);
                            }
                        } finally {
                            manualcontrol.crazy.collision.BodyShadow.endPosed(shadow);
                        }
                    } finally {
                        drawG.setTransform(at);
                        drawG.delete(at);
                    }
                } else {
                    manualcontrol.crazy.collision.BodyShadow.Hidden shadow =
                            posedBody
                            ? manualcontrol.crazy.collision.BodyShadow.beginPosed(
                            entity, am, drawG, p, siz, poseGround, false, p.x)
                            : null;
                    try {
                        if (!manualcontrol.adventure.AdventureBridge.drawSplitShadow(entity, am, drawG, p, siz)) {
                            invokeDraw(am, drawG, p, siz);
                        }
                    } finally {
                        manualcontrol.crazy.collision.BodyShadow.endPosed(shadow);
                    }
                }
            } finally {
                if (evoAlpha) {
                    try { drawG.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
                }
                if (evoScaled) {
                    drawG.setTransform(evoTransform);
                    drawG.delete(evoTransform);
                }
                if (reincMaterialized) {
                    drawG.setTransform(reincTransform);
                    drawG.delete(reincTransform);
                    try { drawG.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
                }
                if (ritualMaterialized) {
                    drawG.setTransform(ritualTransform);
                    drawG.delete(ritualTransform);
                    try { drawG.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
                }
                if (catCoinTransformed) {
                    drawG.setTransform(catCoinTransform);
                    drawG.delete(catCoinTransform);
                    try { drawG.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
                }
                if (launched) {
                    drawG.setTransform(launchTransform);
                    drawG.delete(launchTransform);
                }
                if (terrainMotionTransformed) {
                    drawG.setTransform(terrainMotionTransform);
                    drawG.delete(terrainMotionTransform);
                }
                if (impactTransformed) {
                    drawG.setTransform(impactTransform);
                    drawG.delete(impactTransform);
                }
                if (armyTransformed) {
                    drawG.setTransform(armyTransform);
                    drawG.delete(armyTransform);
                }
                if (catCanonScaled) {
                    drawG.setTransform(catCanonTransform);
                    drawG.delete(catCanonTransform);
                }
                if (slingshotSpawnScaled) {
                    drawG.setTransform(slingshotSpawnTransform);
                    drawG.delete(slingshotSpawnTransform);
                }
                if (scaled) {
                    drawG.setTransform(growthTransform);
                    drawG.delete(growthTransform);
                }
                if (copyCatScaled) {
                    drawG.setTransform(copyCatTransform);
                    drawG.delete(copyCatTransform);
                }
                if (poseGround != null) {
                    try { drawG.delete(poseGround); } catch (Throwable ignored) {}
                }
            }

            manualcontrol.crazy.unit.SummonAttachFeature.drawCarried(entity, g, p, siz);

            if (rec != null && rec.hasBox()) {
                if (target) {

                manualcontrol.SpriteAnchor.recordLiveBox(entity, rec.minX(), rec.minY(), rec.maxX(), rec.maxY(),
                        rec.bodyCX(), rec.bodyCY());

                if (hitbackAnim) {
                    manualcontrol.SpriteAnchor.recordBox(entity, rec.minX(), rec.minY(), rec.maxX(), rec.maxY(),
                            rec.bodyCX(), rec.bodyCY());
                }
                }
                if (bossBoundsTarget) {
                    BossItemFeature.recordSpriteBounds(entity, rec.minX(), rec.minY(), rec.maxX(), rec.maxY(),
                            rec.bodyCX(), rec.bodyCY());
                    BossItemFeature.recordSpriteParts(entity, rec.parts());
                }
                if (bombBoundsTarget) {
                    BombItemFeature.recordSpriteBounds(entity, rec.minX(), rec.minY(), rec.maxX(), rec.maxY(),
                            rec.bodyCX(), rec.bodyCY());
                    BombItemFeature.recordSpriteParts(entity, rec.parts());
                }
                if (beamBoundsTarget) {

                    manualcontrol.crazy.beam.BeamFeature.recordSpriteBounds(entity,
                            rec.minX(), rec.minY(), rec.maxX(), rec.maxY(),
                            rec.bodyCX(), rec.bodyCY());
                    manualcontrol.crazy.beam.BeamFeature.recordSpriteParts(entity, rec.parts());
                }
                if (catCanonBoundsTarget) {
                    CatCanonFeature.recordSpriteBounds(entity, rec.minX(), rec.minY(), rec.maxX(), rec.maxY(),
                            rec.bodyCX(), rec.bodyCY());
                    CatCanonFeature.recordSpriteParts(entity, rec.parts());
                }
                if (copyCatBoundsTarget) {

                    manualcontrol.crazy.beam.CopyCatUfoFeature.recordSpriteBounds(entity,
                            rec.minX(), rec.minY(), rec.maxX(), rec.maxY(), rec.bodyCX(), rec.bodyCY());
                    manualcontrol.crazy.beam.CopyCatUfoFeature.recordSpriteParts(entity, rec.parts());
                }
            }
            if (bombBoundsTarget) {
                try {
                    BombItemFeature.drawAttachedForEntity(entity, g);
                } catch (Throwable t) {
                    Logger.err("DrawHook bomb attached draw failed", t);
                }
            }

            if (manualcontrol.crazy.collision.PhysicalCollision.ENABLED
                    || manualcontrol.crazy.collision.CollisionDebug.OVERLAY) {
                manualcontrol.crazy.collision.AlphaBounds.warmEntity(entity);

                manualcontrol.crazy.collision.SpriteScale.record(entity, siz, growthScale);
            }
            if (manualcontrol.crazy.collision.CollisionDebug.OVERLAY) {

                manualcontrol.crazy.collision.CollisionDebug.drawEntityBox(entity, g, p, siz * growthScale);
            }
        } catch (Throwable t) {
            Logger.err("DrawHook.drawAnim error", t);

            try { invokeDraw(am, g, p, siz); } catch (Throwable ignored) {}
        }
    }

    public static void drawEff(Object am, FakeGraphics g, P p, float siz) {
        if (am == null || g == null || p == null) return;
        try {
            if (manualcontrol.crazy.collision.SurgeJuggleFeature
                    .shouldHideNativeSprite(getEntityFromAnimManager(am))) {
                return;
            }
        } catch (Throwable ignored) {}
        try {
            invokeDrawEff(am, g, p, siz);
        } catch (Throwable t) {
            Logger.err("DrawHook.drawEff error", t);
            try { invokeDrawEff(am, g, p, siz); } catch (Throwable ignored) {}
        }
    }

    private static void invokeDraw(Object am, FakeGraphics g, P p, float siz) throws Exception {
        Method m = drawMethod;
        if (m == null) {
            synchronized (DrawHook.class) {
                if (drawMethod == null) {
                    m = am.getClass().getDeclaredMethod("draw", FakeGraphics.class, P.class, float.class);
                    m.setAccessible(true);
                    drawMethod = m;
                } else {
                    m = drawMethod;
                }
            }
        }
        m.invoke(am, g, p, siz);
    }

    private static void invokeDrawEff(Object am, FakeGraphics g, P p, float siz) throws Exception {
        Method m = drawEffMethod;
        if (m == null) {
            synchronized (DrawHook.class) {
                if (drawEffMethod == null) {
                    m = am.getClass().getDeclaredMethod("drawEff", FakeGraphics.class, P.class, float.class);
                    m.setAccessible(true);
                    drawEffMethod = m;
                } else {
                    m = drawEffMethod;
                }
            }
        }
        m.invoke(am, g, p, siz);
    }

    private static boolean isAnchorTarget(Object entity) {
        try {
            HoldState state = HoldState.get();
            if (state.getPhase() != HoldState.Phase.NONE && state.getHeldEntity() == entity) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (manualcontrol.ControlMarker.tracks(entity)) return true;
        } catch (Throwable ignored) {}
        try {

            if (manualcontrol.crazy.beam.BeamFeature.isEvolutionFrozen(entity)) return true;
        } catch (Throwable ignored) {}
        try {

            if (manualcontrol.crazy.beam.BeamFeature.isKameAimTarget(entity)) return true;
        } catch (Throwable ignored) {}
        try {
            if (manualcontrol.crazy.beam.ArmyCanonFeature.isManaged(entity)) return true;
        } catch (Throwable ignored) {}
        try {
            if (manualcontrol.crazy.base.SlingshotBaseFeature.isSlingshotOverlayEntity(entity)) return true;
        } catch (Throwable ignored) {}
            try {
                if (CatCoinFeature.isManaged(entity)) return true;
            } catch (Throwable ignored) {}
            try {
                if (EggPetFeature.isAnchorTarget(entity)) return true;
            } catch (Throwable ignored) {}
            try {
                return manualcontrol.FallingRegistry.isManaged(entity);
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isHitbackAnim(Object am) {
        try {
            Object eanim = BCUFields.field(am.getClass(), "anim").get(am);
            if (eanim == null) return false;
            Object type = BCUFields.field(eanim.getClass(), "type").get(eanim);
            if (!(type instanceof Enum)) return false;
            String n = ((Enum<?>) type).name();
            return "HB".equals(n) || "KB".equals(n) || "HITBACK".equals(n);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getEntityFromAnimManager(Object am) {
        try {
            return BCUFields.field(am.getClass(), "e").get(am);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean shouldMirrorForCurrentFacing(Object entity) {
        if (entity == null) return false;
        try {

            if (manualcontrol.adventure.AdventureBridge.isAdventureFlipped(entity)) return true;
            int dire = BCUFields.getInt(entity, "dire");
            String cls = entity.getClass().getName();
            if ("common.battle.entity.EUnit".equals(cls)) {
                HoldState state = HoldState.get();
                return (state.isInControl() && state.getHeldEntity() == entity && dire == 1)
                        || (CatCoinFeature.isEnemyOwnedUnit(entity) && dire == 1);
            }
            if ("common.battle.entity.EEnemy".equals(cls)) {
                return ConvertedRegistry.isConverted(entity) && dire == -1;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
