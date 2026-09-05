package manualcontrol.custommap;

import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.attack.ContAb;
import common.battle.entity.AbEntity;
import common.battle.entity.Cannon;
import common.battle.entity.EAnimCont;
import common.battle.entity.EEnemy;
import common.battle.entity.Entity;
import common.system.P;
import common.system.SymCoord;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.Res;
import common.util.stage.Stage;
import manualcontrol.FallingRegistry;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.fps.FpsHooks;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class CustomMapBattleRuntime {

    private enum MotionState { GROUND, CLIMB, SLIDE, ICE_TUMBLE, JUMP, SWIM, FALL }
    private enum VfxKind { DUST, SPLASH, LAND, EDGE, LAVA_DAMAGE, AMBIENT }

    private static final Object LOCK = new Object();
    private static final Map<Entity, Motion> MOTIONS = new WeakHashMap<Entity, Motion>();

    static final float GAP_JUMP_SPEED_MULTIPLIER =
            CustomMapPhysicsRules.GAP_JUMP_SPEED_MULTIPLIER;

    private static final Map<ContAb, Float> EFFECT_SOURCE_LAYERS =
            new WeakHashMap<ContAb, Float>();
    private static final Map<Class<?>, Field[]> EFFECT_ATTACK_FIELDS =
            new HashMap<Class<?>, Field[]>();
    private static final ArrayList<TerrainVfx> VFX = new ArrayList<TerrainVfx>();
    private static final ArrayList<TerrainVfx> AMBIENT_VFX =
            new ArrayList<TerrainVfx>();
    private static final ThreadLocal<FakeTransform> BASE_TRANSFORM =
            new ThreadLocal<FakeTransform>();

    private static final int AMBIENT_SNOW_SPAWN_INTERVAL = 4;
    private static final int AMBIENT_SNOW_HORIZONTAL_STRATA = 8;
    private static StageBasis active;
    private static CustomMapPhysicsRules activeRules;
    private static long animationTick;
    private static long vfxSerial;
    private static float vfxFocusWorldX = Float.NaN;
    private static float lastVfxRenderSubFrame;

    private CustomMapBattleRuntime() {}

    public static void prepareStage(Object stageObject) throws Exception {
        if (!(stageObject instanceof Stage)) return;
        Stage stage = (Stage) stageObject;
        String uuid = CustomMapRepository.uuidForStage(stage);
        if (uuid == null) return;
        CustomMapDocument doc = CustomMapRepository.load(uuid);
        if (doc == null || doc.battleTerrain == null) return;
        stage.len = normalStageLength(doc.battleTerrain);
        Logger.log("CustomMap: prepared native camera extent=" + stage.len
                + " before BBPainter construction");
    }

    static int normalStageLength(CustomMapDocument.ModeVariant terrain) {
        if (terrain == null || terrain.destination == null) return 3000;
        float destinationX = terrain.worldX(terrain.destination.x);
        if (Float.isNaN(destinationX) || Float.isInfinite(destinationX)) return 3000;
        return Math.max(3000, Math.round(destinationX) + 800);
    }

    public static boolean adoptIfCustom(Object stageObject) throws Exception {
        if (!(stageObject instanceof StageBasis)) {
            release(null);
            return false;
        }
        StageBasis stage = (StageBasis) stageObject;
        String uuid = CustomMapRepository.uuidForStage(stage.st);
        if (uuid == null) {
            release(null);
            return false;
        }
        CustomMapDocument doc = CustomMapRepository.load(uuid);
        if (doc == null)
            throw new IllegalArgumentException("Custom Map metadata is missing for the selected stage.");
        CustomMapRuntime.adoptNormal(stage, doc);
        CustomMapRuntime.resetMovingPlatforms();
        CustomMapDocument.ModeVariant terrain = doc.battleTerrain;

        stage.st.len = normalStageLength(terrain);
        float enemyBaseX = terrain.worldX(terrain.spawn.x);
        float playerBaseX = terrain.worldX(terrain.destination.x);
        stage.ebase.pos = enemyBaseX;
        stage.ebase.lastPosition = enemyBaseX;
        stage.ubase.pos = playerBaseX;
        stage.ubase.lastPosition = playerBaseX;
        syncBaseLayer(stage.ebase, terrain.surfaceLayerAt(enemyBaseX));
        syncBaseLayer(stage.ubase, terrain.surfaceLayerAt(playerBaseX));
        synchronized (LOCK) {
            active = stage;
            activeRules = CustomMapPhysicsRules.bind(doc, terrain);
            MOTIONS.clear();
            RIDER_LOG_TICKS.clear();
            EFFECT_SOURCE_LAYERS.clear();
            clearVfxState();
            animationTick = 0L;
        }
        CustomMapRuntime.followLayer(terrain.surfaceLayerAt(playerBaseX));
        Logger.log("CustomMap: normal stage active; enemy base=" + Math.round(enemyBaseX)
                + " player base=" + Math.round(playerBaseX));
        return true;
    }

    public static void release(Object stageObject) {
        synchronized (LOCK) {
            if (active == null || stageObject == null || active == stageObject) {
                StageBasis old = active;
                active = null;
                activeRules = null;
                MOTIONS.clear();
                EFFECT_SOURCE_LAYERS.clear();
                clearVfxState();
                BASE_TRANSFORM.remove();
                CustomMapRuntime.release(old);
            }
        }
    }

    public static boolean isActiveStage(Object stageObject) {
        return active != null && active == stageObject
                && CustomMapRuntime.isNormalBattleStage(stageObject);
    }

    public static boolean hasTerrainAirborneHurtVolume(Object entityObject) {
        if (!(entityObject instanceof Entity)) return false;
        Entity entity = (Entity) entityObject;
        synchronized (LOCK) {
            if (active == null || entity.basis != active) return false;
            Motion motion = MOTIONS.get(entity);
            return motion != null && !motion.manualOwned
                    && (motion.state == MotionState.JUMP
                    || motion.state == MotionState.FALL);
        }
    }

    public static boolean hasTerrainSwimCombatVolume(Object entityObject) {
        if (!(entityObject instanceof Entity)) return false;
        Entity entity = (Entity) entityObject;
        synchronized (LOCK) {
            if (active == null || entity.basis != active) return false;
            Motion motion = MOTIONS.get(entity);
            return motion != null && !motion.manualOwned
                    && motion.state == MotionState.SWIM;
        }
    }

    public static boolean canInitiateTerrainAttack(Object entityObject) {
        if (!(entityObject instanceof Entity)) return true;
        synchronized (LOCK) {
            Motion motion = MOTIONS.get((Entity) entityObject);
            return motion == null || motion.manualOwned
                    || (motion.state != MotionState.JUMP
                    && motion.state != MotionState.FALL
                    && motion.state != MotionState.ICE_TUMBLE
                    && (!motion.ice.active()
                    || motion.ice.canVoluntarilyStop())
                    && motion.stunTicks <= 0);
        }
    }

    public static boolean directAttackLineBlocked(Object attackerObject,
                                                  Object targetObject) {
        if (!(attackerObject instanceof Entity)
                || !(targetObject instanceof AbEntity)) return false;
        Entity attacker = (Entity) attackerObject;
        AbEntity target = (AbEntity) targetObject;
        CustomMapDocument.ModeVariant terrain = CustomMapRuntime.activeBattleTerrain();
        if (terrain == null || attacker.basis != active) return false;
        float ax = attacker.pos;
        float bx;
        float ay = attacker.currentLayer;
        float by;
        try {
            bx = BCUFields.getFloat(target, "pos");
            by = target instanceof Entity
                    ? ((Entity) target).currentLayer
                    : groundVisualLayer(terrain.surfaceLayerAt(bx));
        } catch (Throwable ignored) {
            return false;
        }
        float distance = Math.abs(bx - ax);
        if (distance < terrain.worldUnitsPerTile() * .12f) return false;
        int steps = Math.max(4, (int) Math.ceil(distance
                / Math.max(1f, terrain.worldUnitsPerTile() * .20f)));
        String sharedPlatform = null;
        CustomMapRuntime.TerrainSample as = CustomMapRuntime.sampleTerrain(ax, ay, false);
        CustomMapRuntime.TerrainSample bs = CustomMapRuntime.sampleTerrain(bx, by, false);
        if (as.platformId != null && as.platformId.equals(bs.platformId))
            sharedPlatform = as.platformId;
        for (int i = 1; i < steps; i++) {
            float f = i / (float) steps;
            float x = ax + (bx - ax) * f;
            float line = ay + (by - ay) * f;
            float main = terrain.surfaceLayerAt(x);
            if (!Float.isNaN(main)
                    && main < line - terrain.layerUnitsPerTile() * .20f)
                return true;
            if (terrain.secondaryPlatforms == null) continue;
            float tileX = x / terrain.worldUnitsPerTile();
            for (CustomMapDocument.SecondaryPlatform platform
                    : terrain.secondaryPlatforms) {
                if (platform == null || platform.id.equals(sharedPlatform)) continue;
                MovingPlatformEngine.Pose pose = CustomMapRuntime.platformPose(platform.id);
                if (pose == null || tileX < pose.collisionLeftTileX(platform)
                        || tileX >= pose.collisionRightTileX(platform)) continue;
                float top = pose.collisionSupportLayer(terrain, platform);
                float bottom = top + terrain.layerUnitsPerTile();
                if (line >= top + terrain.layerUnitsPerTile() * .08f
                        && line <= bottom) return true;
            }
        }
        return false;
    }

    public static boolean isActivePainter(Object painter) {
        try {
            return painter != null && isActiveStage(BBPainterAccess.getStageBasis(painter));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void beforeStageUpdate(Object stageObject) {
        if (!isActiveStage(stageObject)) return;
        StageBasis stage = (StageBasis) stageObject;
        synchronized (LOCK) {
            for (Motion motion : MOTIONS.values()) motion.captured = false;
            for (Entity entity : stage.le) {
                if (!eligible(entity)) continue;
                Motion motion = MOTIONS.get(entity);
                if (motion == null) {
                    motion = new Motion();
                    MOTIONS.put(entity, motion);
                }
                motion.beforePos = entity.pos;
                motion.beforeLayer = entity.currentLayer;
                motion.healthBefore = entity.health;
                motion.captured = true;
                if ((motion.state == MotionState.JUMP
                        || motion.state == MotionState.FALL
                        || motion.state == MotionState.ICE_TUMBLE
                        || (motion.ice.active()
                        && !motion.ice.canVoluntarilyStop())
                        || motion.stunTicks > 0)
                        && !isManualPositionOwner(entity))
                    suppressJumpCombat(entity, motion);
            }
        }
    }

    public static void afterStageUpdate(Object stageObject) {
        if (!isActiveStage(stageObject)) return;
        StageBasis stage = (StageBasis) stageObject;
        CustomMapDocument.ModeVariant terrain = CustomMapRuntime.activeBattleTerrain();
        if (terrain == null) return;
        projectBaseSmoke(stage, terrain);
        float focus = Float.NaN;
        synchronized (LOCK) {

            manualcontrol.crazy.collision.PhysicalCollision.ENABLED = true;

            if (!drivesTerrainMotion(stage.s_stop,
                    stage.ubase != null && stage.ebase != null,
                    stage.ubase == null ? 0L : stage.ubase.health,
                    stage.ebase == null ? 0L : stage.ebase.health))
                return;
            animationTick++;
            CustomMapRuntime.tickMovingPlatforms();
            tickVfx();
            for (Entity entity : stage.le) {
                if (!eligible(entity)) continue;
                Motion motion = MOTIONS.get(entity);
                if (motion == null) {
                    motion = new Motion();
                    motion.beforePos = entity.pos;
                    motion.beforeLayer = entity.currentLayer;
                    motion.healthBefore = entity.health;
                    MOTIONS.put(entity, motion);
                }
                updateEntity(entity, motion, terrain);
                CustomMapRuntime.noteIceOccupant(entity, entity.pos,
                        entity.currentLayer, isGroundedForIceLoad(motion),
                        motion.platformId);
                if (!motion.manualOwned && entity.dire < 0
                        && !entity.dead && entity.health > 0L) {
                    if (Float.isNaN(focus) || entity.pos < focus)
                        focus = entity.pos;
                }
            }
            preventOpposingCrossing(stage, terrain);
            preventBaseCrossing(stage, terrain);
            vfxFocusWorldX = !Float.isNaN(focus) ? focus
                    : stage.ubase == null ? terrain.worldWidth() * .5f : stage.ubase.pos;
        }
        if (!Float.isNaN(focus)) {
            float layer = terrain.surfaceLayerAt(focus);
            if (!Float.isNaN(layer)) CustomMapRuntime.followLayer(layer);
        } else {
            float playerBaseX = terrain.worldX(terrain.destination.x);
            CustomMapRuntime.followLayer(terrain.surfaceLayerAt(playerBaseX));
        }
    }

    private static boolean eligible(Entity entity) {
        return entity != null && !entity.isBase() && !entity.dead && entity.health > 0L;
    }

    static boolean drivesTerrainMotion(int stopTicks, boolean basesPresent,
                                       long playerBaseHealth,
                                       long enemyBaseHealth) {
        return stopTicks == 0 && basesPresent;
    }

    private static boolean isGroundedForIceLoad(Motion motion) {
        return motion != null && !motion.manualOwned
                && motion.state != MotionState.JUMP
                && motion.state != MotionState.FALL
                && motion.state != MotionState.SWIM;
    }

    private static void preventOpposingCrossing(
            StageBasis stage, CustomMapDocument.ModeVariant terrain) {
        if (stage == null || stage.le == null || terrain == null) return;
        float verticalTolerance = terrain.layerUnitsPerTile() * .80f;
        float separation = Math.max(1f, terrain.worldUnitsPerTile() * .012f);
        for (int i = 0; i < stage.le.size(); i++) {
            Entity a = stage.le.get(i);
            if (!eligible(a)) continue;
            Motion am = MOTIONS.get(a);
            if (am == null || !am.captured || am.manualOwned) continue;
            for (int j = i + 1; j < stage.le.size(); j++) {
                Entity b = stage.le.get(j);
                if (!eligible(b) || a.dire != -b.dire) continue;
                Motion bm = MOTIONS.get(b);
                if (bm == null || !bm.captured || bm.manualOwned) continue;
                if (Math.abs(a.currentLayer - b.currentLayer) > verticalTolerance
                        && Math.abs(am.beforeLayer - bm.beforeLayer)
                        > verticalTolerance) continue;
                float before = am.beforePos - bm.beforePos;
                float after = a.pos - b.pos;
                boolean crossed = before != 0f && before * after <= 0f;
                boolean approaching = Math.abs(after) < Math.abs(before);
                boolean close = Math.abs(after) <= terrain.worldUnitsPerTile() * .08f;
                boolean iceContact = am.ice.active() || bm.ice.active()
                        || am.swimCarryWorld != 0f || bm.swimCarryWorld != 0f;
                boolean spriteContact = false;
                if (iceContact && approaching) {
                    try {
                        spriteContact = manualcontrol.crazy.collision.PhysicalCollision
                                .strictSpriteContact(a, b);
                    } catch (Throwable ignored) {}
                }
                if (!crossed && !(approaching && (spriteContact || close)
                        && iceContact)) continue;
                float midpoint = (a.pos + b.pos) * .5f;
                resolveIceImpact(a, am, b, bm, terrain, midpoint,
                        (a.currentLayer + b.currentLayer) * .5f);
                if (before < 0f) {
                    a.pos = midpoint - separation;
                    b.pos = midpoint + separation;
                } else {
                    a.pos = midpoint + separation;
                    b.pos = midpoint - separation;
                }
                a.lastPosition = am.beforePos;
                b.lastPosition = bm.beforePos;
            }
        }
    }

    private static void resolveIceImpact(
            Entity a, Motion am, Entity b, Motion bm,
            CustomMapDocument.ModeVariant terrain, float worldX, float layer) {
        if (a == null || b == null || am == null || bm == null) return;
        if (am.state == MotionState.SWIM && bm.state == MotionState.SWIM) {
            resolveWaterImpact(a, am, b, bm, terrain, worldX, layer);
            return;
        }
        if (!am.ice.canImpact() && !bm.ice.canImpact()) return;
        IceSurfaceRules.Collision impact = IceSurfaceRules.resolve(
                collisionMass(a), am.ice.velocityTilesPerTick(),
                collisionMass(b), bm.ice.velocityTilesPerTick());
        if (!impact.active) return;

        am.ice.applyCollision(impact.velocityA, impact.staggerA);
        bm.ice.applyCollision(impact.velocityB, impact.staggerB);
        boolean hit = CustomMapLandingImpact.queueDeferredDamage(a,
                IceSurfaceRules.damage(a.maxH, impact.damageRatioA));
        if (CustomMapLandingImpact.queueDeferredDamage(b,
                IceSurfaceRules.damage(b.maxH, impact.damageRatioB))) hit = true;
        applyIceCollisionState(a, am);
        applyIceCollisionState(b, bm);
        if (hit) emit(VfxKind.LAND, worldX, layer,
                sign(impact.velocityA - impact.velocityB));
    }

    private static void resolveWaterImpact(
            Entity a, Motion am, Entity b, Motion bm,
            CustomMapDocument.ModeVariant terrain, float worldX, float layer) {
        if (terrain == null) return;
        float units = Math.max(1f, terrain.worldUnitsPerTile());
        IceSurfaceRules.Collision impact = IceSurfaceRules.resolve(
                collisionMass(a), am.swimCarryWorld / units,
                collisionMass(b), bm.swimCarryWorld / units,
                IceSurfaceRules.WATER_RESTITUTION,
                IceSurfaceRules.WATER_ENERGY_SCALE);
        if (!impact.active) return;
        am.swimCarryWorld = impact.velocityA * units;
        bm.swimCarryWorld = impact.velocityB * units;
        if (impact.staggerA) am.lastMoveDirection = sign(impact.velocityA);
        if (impact.staggerB) bm.lastMoveDirection = sign(impact.velocityB);
        boolean hit = CustomMapLandingImpact.queueDeferredDamage(a,
                IceSurfaceRules.damage(a.maxH, impact.damageRatioA));
        if (CustomMapLandingImpact.queueDeferredDamage(b,
                IceSurfaceRules.damage(b.maxH, impact.damageRatioB))) hit = true;
        if (hit) emit(VfxKind.SPLASH, worldX, layer,
                sign(impact.velocityA - impact.velocityB));
    }

    private static float collisionMass(Entity entity) {
        return entity == null ? 1f : Math.max(1f, (float) entity.maxH);
    }

    private static void applyIceCollisionState(Entity entity, Motion motion) {
        if (!motion.ice.active()) return;
        int direction = motion.ice.direction();
        if (direction != 0) motion.lastMoveDirection = direction;
        if (!motion.ice.tumbling()) return;
        motion.state = MotionState.ICE_TUMBLE;
        suppressJumpCombat(entity, motion);
        forceTumbleFrame(entity);
    }

    private static void preventBaseCrossing(
            StageBasis stage, CustomMapDocument.ModeVariant terrain) {
        if (stage == null || stage.le == null || terrain == null) return;
        float enemyBase = terrain.worldX(terrain.spawn.x);
        float playerBase = terrain.worldX(terrain.destination.x);
        float separation = Math.max(1f, terrain.worldUnitsPerTile() * .012f);
        for (Entity entity : stage.le) {
            if (!eligible(entity)) continue;
            Motion motion = MOTIONS.get(entity);
            if (motion == null || !motion.captured || motion.manualOwned) continue;
            boolean blocked = false;
            if (entity.dire > 0 && motion.beforePos < playerBase
                    && entity.pos >= playerBase) {
                entity.pos = playerBase - separation;
                blocked = true;
            } else if (entity.dire < 0 && motion.beforePos > enemyBase
                    && entity.pos <= enemyBase) {
                entity.pos = enemyBase + separation;
                blocked = true;
            }
            if (blocked && motion.ice.active()) {
                float velocity = motion.ice.velocityTilesPerTick();
                motion.ice.start(-velocity * .28f,
                        IceSurfaceRules.Phase.TUMBLE);
                motion.state = MotionState.ICE_TUMBLE;
                suppressJumpCombat(entity, motion);
                forceTumbleFrame(entity);
            }
        }
    }

    private static void updateEntity(Entity entity, Motion motion,
                                     CustomMapDocument.ModeVariant terrain) {

        if (isManualPositionOwner(entity)) {
            resumeCombatAfterTraversal(entity, motion);
            motion.manualOwned = true;
            motion.captured = false;
            return;
        }
        if (motion.manualOwned) {
            motion.manualOwned = false;
            resetAuthoredMotion(entity, motion);
            motion.beforePos = entity.pos;
            motion.beforeLayer = entity.currentLayer;
        }

        if (isNativeRelocationOwner(entity)) {
            resumeCombatAfterTraversal(entity, motion);

            float attempted = entity.pos;
            TerrainHeightfield.Sweep relocation = CustomMapRuntime.sweepMain(
                    terrain, motion.beforePos, attempted,
                    terrain.layerUnitsPerTile() * .60f);
            float resolved = relocation.blocked
                    ? relocation.worldX : attempted;
            TerrainHeightfield.Contact relocationSupport =
                    CustomMapRuntime.sampleMain(terrain, resolved,
                            entity.currentLayer);
            float layer = relocationSupport.kind
                    == CustomMapRuntime.TerrainKind.MAIN
                    ? relocationSupport.supportLayer : Float.NaN;
            if (Float.isNaN(layer)) {
                resolved = motion.beforePos;
                relocationSupport = CustomMapRuntime.sampleMain(
                        terrain, resolved, entity.currentLayer);
                layer = relocationSupport.kind
                        == CustomMapRuntime.TerrainKind.MAIN
                        ? relocationSupport.supportLayer : Float.NaN;
            }
            entity.pos = resolved;
            if (!Float.isNaN(layer))
                entity.currentLayer = Math.round(groundVisualLayer(layer));
            resetAuthoredMotion(entity, motion);
            motion.initialized = true;
            motion.beforePos = entity.pos;
            motion.beforeLayer = entity.currentLayer;
            sync(entity, motion);
            return;
        }
        if (motion.nativeOwned) {
            motion.nativeOwned = false;
            resetAuthoredMotion(entity, motion);
            motion.beforePos = entity.pos;
            motion.beforeLayer = entity.currentLayer;
        }
        boolean damaged = motion.captured && entity.health < motion.healthBefore;
        if (!motion.initialized) {
            motion.initialized = true;
            relocateNativeStageEnemySpawn(entity, motion, terrain);
            snapNewEntity(entity, motion, terrain);
            return;
        }

        motion.ice.setNativeSpeed(nativeSpeedTiles(entity, terrain));

        if (dropWhenSupportDisappears(entity, motion, terrain)) return;

        if (motion.stunTicks > 0) {
            motion.stunTicks--;
            entity.pos = motion.beforePos;
            entity.currentLayer = motion.beforeLayer;
            suppressJumpCombat(entity, motion);
            forceWalkFrame(entity);
            sync(entity, motion);
            if (motion.stunTicks == 0)
                resumeCombatAfterTraversal(entity, motion);
            return;
        }
        float landingMoveMultiplier = motion.slowTicks > 0
                ? motion.slowMovementMultiplier : 1f;
        if (motion.slowTicks > 0) {
            motion.slowTicks--;
            if (motion.slowTicks == 0) motion.slowMovementMultiplier = 1f;
        }

        if (motion.platformId != null
                && motion.state != MotionState.JUMP
                && motion.state != MotionState.FALL
                && motion.state != MotionState.SWIM) {
            updatePlatformRider(entity, motion, terrain,
                    landingMoveMultiplier, damaged);
            return;
        }

        if (motion.state == MotionState.JUMP) {
            updateJump(entity, motion, terrain, damaged);
            return;
        }
        if (motion.state == MotionState.FALL) {
            updateFall(entity, motion, terrain);
            return;
        }
        if (motion.state == MotionState.SWIM) {
            updateSwim(entity, motion, terrain);
            return;
        }
        if (motion.state == MotionState.SLIDE) {
            updateSlide(entity, motion, terrain);
            return;
        }
        if (motion.state == MotionState.ICE_TUMBLE) {
            updateIceTumble(entity, motion, terrain);
            return;
        }

        float rawNativeDelta = (entity.pos - motion.beforePos)
                * landingMoveMultiplier;
        if (terrain.isIceSurfaceAt(motion.beforePos, null)
                || motion.ice.active()) {
            CustomMapRuntime.SlopeSample iceSlope =
                    CustomMapRuntime.sampleSlope(terrain, motion.beforePos);
            IceSurfaceRules.Step iceStep = rulesFor(terrain).advanceIce(
                    motion.ice,
                    terrain.isIceSurfaceAt(motion.beforePos, null),
                    rawNativeDelta / Math.max(1f, terrain.worldUnitsPerTile()),
                    motion.lastMoveDirection != 0
                            ? motion.lastMoveDirection : entity.dire,
                    iceSlope.downhillDirection);
            if (iceStep.forced) {
                updateIceMain(entity, motion, terrain, iceStep);
                return;
            }
            if (!motion.ice.active())
                resumeCombatAfterTraversal(entity, motion);
        }
        float nativeDelta = platformLocomotionDelta(
                rawNativeDelta, isAttacking(entity), damaged);
        int nativeDirection = sign(nativeDelta);
        int rememberedDirection = nativeDirection != 0 ? nativeDirection : motion.lastMoveDirection;
        CustomMapRuntime.SlopeSample priorSlope =
                CustomMapRuntime.sampleSlope(terrain, motion.beforePos);
        if (damaged && priorSlope.isSlope()
                && (motion.state == MotionState.CLIMB
                || priorSlope.isUphill(motion.lastMoveDirection)
                || priorSlope.isUphill(rememberedDirection))) {
            beginSlide(entity, motion, terrain, priorSlope.downhillDirection,
                    Math.abs(nativeDelta));
            return;
        }

        if (nativeDirection == 0) {
            projectToMain(entity, motion, terrain,
                    mainSupportVerdict(terrain, entity.pos, entity.currentLayer,
                            motion.beforePos, motion.beforeLayer)
                            == SupportVerdict.TRAVERSAL
                            ? motion.beforePos : entity.pos);
            return;
        }
        motion.lastMoveDirection = nativeDirection;

        boolean forward = nativeDirection == entity.dire;
        float delta = nativeDelta;
        CustomMapRuntime.SlopeSample slope =
                CustomMapRuntime.sampleSlope(terrain, motion.beforePos);
        if (forward && slope.isSlope()) {
            if (slope.isUphill(nativeDirection)) delta *= 0.86f;
            else if (slope.downhillDirection == nativeDirection) delta *= 1.16f;
        }
        float candidate = motion.beforePos + delta;
        CustomMapRuntime.PlatformBoarding priorityBoarding =
                forward && !isAttacking(entity) && !damaged
                ? CustomMapRuntime.findPriorityPlatformBoarding(
                motion.beforePos, entity.currentLayer, motion.platformId,
                nativeDirection) : null;
        if (priorityBoarding != null) {
            beginPlatformBoardingJump(entity, motion, priorityBoarding,
                    Math.abs(delta));
            return;
        }
        CustomMapRuntime.TerrainSample forwardSupport =
                CustomMapRuntime.sampleTerrain(candidate, entity.currentLayer, false);
        TerrainHeightfield.Contact priorMain = CustomMapRuntime.sampleMain(
                terrain, motion.beforePos, entity.currentLayer);
        float priorMainSupport = priorMain.kind
                == CustomMapRuntime.TerrainKind.MAIN
                ? priorMain.supportLayer : Float.NaN;
        if (forwardSupport.kind == CustomMapRuntime.TerrainKind.FLOATING
                && forwardSupport.platformId != null
                && !Float.isNaN(priorMainSupport)
                && Math.abs(forwardSupport.supportLayer - priorMainSupport)
                <= terrain.layerUnitsPerTile() * .25f) {
            motion.platformId = forwardSupport.platformId;
            motion.lastPlatformCarryTick = CustomMapRuntime.platformTick();
            motion.waitingForDock = false;
            motion.state = MotionState.GROUND;
            entity.pos = candidate;
            entity.currentLayer = Math.round(groundVisualLayer(
                    forwardSupport.supportLayer));
            sync(entity, motion);
            return;
        }

        TerrainHeightfield.MainStep step = CustomMapRuntime.firstMainStep(
                terrain, motion.beforePos, candidate);
        if (step == null) {
            float lookAhead = candidate + nativeDirection
                    * terrain.worldUnitsPerTile() * 0.32f;
            TerrainHeightfield.MainStep ahead = CustomMapRuntime.firstMainStep(
                    terrain, motion.beforePos, lookAhead);
            if (ahead != null && ahead.kind == TerrainHeightfield.StepKind.UP)
                step = ahead;
        }
        if (step != null && step.kind == TerrainHeightfield.StepKind.UP) {
            CustomMapRuntime.GapJump jump = buildStepUpJump(
                    terrain, step, motion.beforePos);
            if (jump == null) {
                float safeX = step.boundaryWorldX - step.direction
                        * terrain.worldUnitsPerTile() * 0.025f;
                projectToMain(entity, motion, terrain, safeX);
                return;
            }
            beginDryJump(entity, motion, jump, Math.abs(delta));
            return;
        }
        if (step != null && step.kind == TerrainHeightfield.StepKind.DOWN) {
            float edgeX = step.boundaryWorldX + step.direction
                    * terrain.worldUnitsPerTile() * 0.025f;
            suppressJumpCombat(entity, motion);
            forceWalkFrame(entity);
            beginFall(entity, motion, edgeX, groundVisualLayer(step.fromLayer),
                    step.direction * Math.max(Math.abs(delta),
                            terrain.worldUnitsPerTile() * 0.022f), 0f);
            return;
        }
        TerrainHeightfield.Sweep sweep = CustomMapRuntime.sweepMain(
                terrain, motion.beforePos, candidate,
                terrain.layerUnitsPerTile() * 0.60f);
        if (resolveBlockedContact(entity, motion, terrain, sweep,
                nativeDirection, Math.abs(delta), 0f)) return;
        projectToMain(entity, motion, terrain, sweep.blocked ? sweep.worldX : candidate);
    }

    private static boolean dropWhenSupportDisappears(
            Entity entity, Motion motion,
            CustomMapDocument.ModeVariant terrain) {
        if (entity == null || motion == null || terrain == null
                || motion.state == MotionState.JUMP
                || motion.state == MotionState.FALL
                || motion.state == MotionState.SWIM) return false;
        if (motion.platformId != null) {
            CustomMapDocument.SecondaryPlatform platform =
                    terrain.secondaryPlatform(motion.platformId);
            MovingPlatformEngine.Pose pose =
                    CustomMapRuntime.platformPose(motion.platformId);
            CustomMapRuntime.TerrainSample support =
                    CustomMapRuntime.sampleTerrain(entity.pos,
                            entity.currentLayer, false);
            if (platform != null && pose != null
                    && support.kind == CustomMapRuntime.TerrainKind.FLOATING
                    && platform.id.equals(support.platformId)) return false;
            motion.platformId = null;
            beginFall(entity, motion, entity.pos, entity.currentLayer,
                    pose == null ? 0f : pose.deltaWorldX(terrain),
                    pose == null ? 0f : pose.deltaLayer(terrain));
            return true;
        }
        if (mainSupportVerdict(terrain, entity.pos, entity.currentLayer,
                motion.beforePos, motion.beforeLayer) != SupportVerdict.DROP)
            return false;
        beginFall(entity, motion, entity.pos, entity.currentLayer, 0f, 0f);
        return true;
    }

    enum SupportVerdict { SUPPORTED, TRAVERSAL, DROP }

    static SupportVerdict mainSupportVerdict(
            CustomMapDocument.ModeVariant terrain, float pos, float layer,
            float beforePos, float beforeLayer) {
        if (terrain == null) return SupportVerdict.DROP;
        if (CustomMapRuntime.sampleMain(terrain, pos, layer).kind
                == CustomMapRuntime.TerrainKind.MAIN)
            return SupportVerdict.SUPPORTED;
        if (CustomMapRuntime.sampleMain(terrain, beforePos, beforeLayer).kind
                == CustomMapRuntime.TerrainKind.MAIN)
            return SupportVerdict.TRAVERSAL;
        return SupportVerdict.DROP;
    }

    private static void updatePlatformRider(
            Entity entity, Motion motion, CustomMapDocument.ModeVariant terrain,
            float movementMultiplier, boolean damaged) {
        CustomMapDocument.SecondaryPlatform platform =
                terrain.secondaryPlatform(motion.platformId);
        MovingPlatformEngine.Pose pose =
                CustomMapRuntime.platformPose(motion.platformId);
        if (platform == null || pose == null) {
            motion.platformId = null;
            beginFall(entity, motion, entity.pos, entity.currentLayer, 0f, 0f);
            return;
        }
        CustomMapRuntime.TerrainSample currentSupport =
                CustomMapRuntime.sampleTerrain(entity.pos,
                        entity.currentLayer, false);
        if (currentSupport.kind != CustomMapRuntime.TerrainKind.FLOATING
                || !platform.id.equals(currentSupport.platformId)) {
            motion.platformId = null;
            beginFall(entity, motion, entity.pos, entity.currentLayer,
                    pose.deltaWorldX(terrain), pose.deltaLayer(terrain));
            return;
        }
        CustomMapRuntime.notePlatformRider(platform.id, entity);

        float units = terrain.worldUnitsPerTile();
        float rawNativeDelta = (entity.pos - motion.beforePos) * movementMultiplier;
        boolean iceForced = false;
        IceSurfaceRules.Step iceStep = null;
        if (platform.surfaceMaterial == CustomMapDocument.SURFACE_ICE
                || motion.ice.active()) {
            iceStep = rulesFor(terrain).advanceIce(
                    motion.ice,
                    platform.surfaceMaterial == CustomMapDocument.SURFACE_ICE,
                    rawNativeDelta / Math.max(1f, units),
                    motion.lastMoveDirection != 0
                            ? motion.lastMoveDirection : entity.dire, 0);
            iceForced = iceStep.forced;
        }
        float nativeDelta = iceForced
                ? iceStep.deltaTiles * units
                : platformLocomotionDelta(
                        rawNativeDelta, isAttacking(entity), damaged);
        if (!iceForced && !motion.ice.active())
            resumeCombatAfterTraversal(entity, motion);
        if (iceForced && iceStep.lockAttack) {
            suppressJumpCombat(entity, motion);
            if (iceStep.phase == IceSurfaceRules.Phase.TUMBLE) {
                motion.state = MotionState.ICE_TUMBLE;
                forceTumbleFrame(entity);
            } else forceWalkFrame(entity);
        }
        int direction = sign(nativeDelta);
        if (direction != 0) motion.lastMoveDirection = direction;
        long platformTick = CustomMapRuntime.platformTick();

        float carriedDelta = motion.lastPlatformCarryTick == platformTick
                ? 0f : pose.deltaWorldX(terrain);
        motion.lastPlatformCarryTick = platformTick;
        float carriedBefore = motion.beforePos + carriedDelta;
        float candidate = carriedBefore + nativeDelta;
        float support = pose.collisionSupportLayer(terrain, platform);
        float left = pose.collisionLeftTileX(platform) * units;
        float right = pose.collisionRightTileX(platform) * units;
        float inset = units * .025f;

        if (damaged && Math.abs(nativeDelta) > units * .30f) {
            detachFromPlatform(entity, motion, terrain, pose, support,
                    candidate, direction == 0 ? -entity.dire : direction,
                    nativeDelta * .65f);
            return;
        }

        MotionState riderState = iceForced
                && iceStep.phase == IceSurfaceRules.Phase.TUMBLE
                ? MotionState.ICE_TUMBLE : MotionState.GROUND;

        if (candidate >= left + inset && candidate <= right - inset) {
            motion.waitingForDock = false;
            motion.state = riderState;
            entity.pos = candidate;
            entity.currentLayer = Math.round(groundVisualLayer(support));
            sync(entity, motion);
            return;
        }

        int edgeDirection = candidate < left + inset ? -1 : 1;
        int travelDirection = direction != 0 ? direction : motion.lastMoveDirection;
        if (travelDirection != 0 && travelDirection != edgeDirection) {
            motion.waitingForDock = false;
            motion.state = reboundRiderOnIce(entity, motion)
                    ? MotionState.ICE_TUMBLE : riderState;
            entity.pos = Math.max(left + inset,
                    Math.min(right - inset, candidate));
            entity.currentLayer = Math.round(groundVisualLayer(support));
            sync(entity, motion);
            return;
        }
        float edge = edgeDirection < 0 ? left : right;
        MovingPlatformDocking.Target dock = (!platform.isPatrolling()
                || MovingPlatformEngine.isBoardingStop(pose))
                ? MovingPlatformDocking.find(terrain, platform,
                pose.centerTileX, pose.supportTileY, edgeDirection) : null;
        logRiderEdge(entity, terrain, platform, pose, edgeDirection,
                travelDirection, nativeDelta, candidate, left, right, dock);
        if (dock != null) {
            if (platform.isPatrolling() && motion.dockCrossPlatformId == null) {
                if (!CustomMapRuntime.beginPlatformDockCross(
                        platform.id, entity, 12)) {
                    entity.pos = Math.max(left + inset,
                            Math.min(right - inset, carriedBefore));
                    entity.currentLayer = Math.round(groundVisualLayer(support));
                    sync(entity, motion);
                    return;
                }
                motion.dockCrossPlatformId = platform.id;
            }
            boolean reached = edgeDirection > 0
                    ? candidate >= dock.entryWorldX
                    : candidate <= dock.entryWorldX;
            if (!reached) {
                motion.waitingForDock = false;
                entity.pos = candidate;
                entity.currentLayer = Math.round(groundVisualLayer(support));
                sync(entity, motion);
                return;
            }
            finishDockCross(entity, motion);
            motion.platformId = dock.platformId;
            motion.lastPlatformCarryTick = dock.platformId == null
                    ? Long.MIN_VALUE : CustomMapRuntime.platformTick();
            motion.waitingForDock = false;
            entity.pos = candidate;
            entity.currentLayer = Math.round(groundVisualLayer(dock.supportLayer));
            sync(entity, motion);
            return;
        }

        if (willDockDuringCycle(terrain, platform, edgeDirection)) {
            finishDockCross(entity, motion);
            motion.waitingForDock = true;
            if (reboundRiderOnIce(entity, motion))
                motion.state = MotionState.ICE_TUMBLE;
            entity.pos = Math.max(left + inset,
                    Math.min(right - inset, carriedBefore));
            entity.currentLayer = Math.round(groundVisualLayer(support));
            sync(entity, motion);
            return;
        }

        detachFromPlatform(entity, motion, terrain, pose, support,
                edge + edgeDirection * inset, edgeDirection,
                edgeDirection * Math.max(Math.abs(nativeDelta), units * .022f));
    }

    private static final Map<String, Long> RIDER_LOG_TICKS =
            new HashMap<String, Long>();
    private static final long RIDER_LOG_INTERVAL_TICKS = 30L;

    private static void logRiderEdge(Entity entity,
                                     CustomMapDocument.ModeVariant terrain,
                                     CustomMapDocument.SecondaryPlatform platform,
                                     MovingPlatformEngine.Pose pose,
                                     int edgeDirection, int travelDirection,
                                     float nativeDelta, float candidate,
                                     float left, float right,
                                     MovingPlatformDocking.Target dock) {
        try {
            String key = platform.id + "#" + System.identityHashCode(entity);
            Long last = RIDER_LOG_TICKS.get(key);
            if (last != null && animationTick - last < RIDER_LOG_INTERVAL_TICKS) return;
            RIDER_LOG_TICKS.put(key, animationTick);
            Logger.log("CustomMap rider " + key
                    + " pos=" + Math.round(candidate)
                    + " body=[" + Math.round(left) + ".." + Math.round(right) + "]"
                    + " edgeDir=" + edgeDirection
                    + " travelDir=" + travelDirection
                    + " delta=" + Math.round(nativeDelta)
                    + " dock=" + (dock == null ? "none"
                    : Math.round(dock.entryWorldX) + "@" + dock.supportLayer)
                    + " willDockDuringCycle="
                    + willDockDuringCycle(terrain, platform, edgeDirection)
                    + " boardingStop=" + MovingPlatformEngine.isBoardingStop(pose)
                    + " attacking=" + isAttacking(entity)
                    + " " + CustomMapRuntime.platformDebug(platform.id));
        } catch (Throwable ignored) {}
    }

    private static boolean reboundRiderOnIce(Entity entity, Motion motion) {
        if (!motion.ice.active()) return false;
        motion.ice.start(-motion.ice.velocityTilesPerTick() * .28f,
                IceSurfaceRules.Phase.TUMBLE);
        suppressJumpCombat(entity, motion);
        forceTumbleFrame(entity);
        return motion.ice.active();
    }

    private static void detachFromPlatform(
            Entity entity, Motion motion, CustomMapDocument.ModeVariant terrain,
            MovingPlatformEngine.Pose pose, float support, float x,
            int direction, float outwardVelocity) {
        finishDockCross(entity, motion);
        motion.platformOriginFall = true;
        motion.platformFallOriginLayer = support;
        motion.platformId = null;
        motion.waitingForDock = false;
        float inheritedX = pose.velocityWorldPerSecondX(terrain)
                / MovingPlatformEngine.TICKS_PER_SECOND;
        float inheritedLayer = pose.velocityLayerPerSecond(terrain)
                / MovingPlatformEngine.TICKS_PER_SECOND;
        suppressJumpCombat(entity, motion);
        forceWalkFrame(entity);
        beginFall(entity, motion, x, groundVisualLayer(support),
                inheritedX + outwardVelocity,
                inheritedLayer - terrain.layerUnitsPerTile() * .22f);
        motion.lastMoveDirection = direction;
    }

    private static void finishDockCross(Entity entity, Motion motion) {
        if (motion == null || motion.dockCrossPlatformId == null) return;
        CustomMapRuntime.finishPlatformBoarding(
                motion.dockCrossPlatformId, entity);
        motion.dockCrossPlatformId = null;
    }

    static float platformLocomotionDelta(float nativeDelta,
                                         boolean attacking,
                                         boolean damaged) {
        return attacking && !damaged ? 0f : nativeDelta;
    }

    private static void beginPlatformBoardingJump(
            Entity entity, Motion motion,
            CustomMapRuntime.PlatformBoarding boarding,
            float nativeSpeed) {
        if (!CustomMapRuntime.beginPlatformBoarding(
                boarding.platformId, entity, boarding.durationTicks)) return;
        beginDryJump(entity, motion, boarding.jump, nativeSpeed);
        motion.jumpLandingPlatformId = boarding.platformId;
        motion.jumpDuration = boarding.durationTicks;
    }

    private static boolean validDockSupport(
            CustomMapDocument.ModeVariant terrain, float worldX,
            float platformSupport, float targetSupport) {
        if (Float.isNaN(targetSupport)) return false;
        if (Math.abs(targetSupport - platformSupport)
                > terrain.layerUnitsPerTile() * .25f) return false;
        return !CustomMapRuntime.sampleSlope(terrain, worldX).isSlope();
    }

    private static CustomMapDocument.SecondaryPlatform staticDockAt(
            CustomMapDocument.ModeVariant terrain,
            CustomMapDocument.SecondaryPlatform source,
            float probeWorldX, float support) {
        if (terrain.secondaryPlatforms == null) return null;
        float tileX = probeWorldX / terrain.worldUnitsPerTile();
        for (CustomMapDocument.SecondaryPlatform candidate
                : terrain.secondaryPlatforms) {
            if (candidate == null || candidate == source || candidate.isPatrolling())
                continue;
            MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                    terrain, candidate, CustomMapRuntime.platformTick());
            if (tileX < pose.collisionLeftTileX(candidate) - .04f
                    || tileX > pose.collisionRightTileX(candidate) + .04f) continue;
            if (Math.abs(pose.collisionSupportLayer(terrain, candidate) - support)
                    <= terrain.layerUnitsPerTile() * .25f) return candidate;
        }
        return null;
    }

    private static boolean willDockDuringCycle(
            CustomMapDocument.ModeVariant terrain,
            CustomMapDocument.SecondaryPlatform platform, int direction) {
        if (platform == null || platform.patrol == null
                || !platform.patrol.enabled) return false;
        CustomMapDocument.PlatformPatrol patrol = platform.patrol;
        return MovingPlatformDocking.find(
                terrain, platform, patrol.ax, patrol.ay, direction) != null
                || MovingPlatformDocking.find(
                terrain, platform, patrol.bx, patrol.by, direction) != null;
    }

    static CustomMapRuntime.GapJump buildStepUpJump(
            CustomMapDocument.ModeVariant terrain,
            TerrainHeightfield.MainStep step, float currentWorldX) {
        CustomMapPhysicsRules rules = CustomMapPhysicsRules.bind(null, terrain);
        return rules == null ? null : rules.stepUpJump(step, currentWorldX);
    }

    private static void beginDryJump(Entity entity, Motion motion,
                                     CustomMapRuntime.GapJump jump,
                                     float nativeSpeed) {
        beginDryJump(entity, motion, jump, nativeSpeed, 1f);
    }

    private static void beginDryJump(Entity entity, Motion motion,
                                     CustomMapRuntime.GapJump jump,
                                     float nativeSpeed, float speedMultiplier) {
        motion.ice.clear();
        motion.state = MotionState.JUMP;
        motion.jump = jump;
        motion.jumpLandingPlatformId = null;
        motion.route = null;
        motion.jumpLandingWater = false;
        motion.jumpTick = 0;
        motion.jumpDuration = jump.duration(Math.max(1f, nativeSpeed),
                speedMultiplier);
        motion.lastMoveDirection = jump.direction;
        entity.pos = jump.startWorldX;
        entity.currentLayer = Math.round(groundVisualLayer(jump.startLayer));
        suppressJumpCombat(entity, motion);
        forceWalkFrame(entity);
        emit(VfxKind.DUST, entity.pos, jump.startLayer, jump.direction);
        sync(entity, motion);
    }

    private static void snapNewEntity(Entity entity, Motion motion,
                                      CustomMapDocument.ModeVariant terrain) {
        CustomMapRuntime.TerrainSample sample = CustomMapRuntime.sampleTerrain(
                entity.pos, entity.currentLayer, false);
        CustomMapRuntime.TerrainKind kind = sample.kind;
        if (kind == CustomMapRuntime.TerrainKind.FLOATING
                && sample.platformId != null) {
            motion.platformId = sample.platformId;
            motion.lastPlatformCarryTick = CustomMapRuntime.platformTick();
            motion.state = MotionState.GROUND;
            entity.currentLayer = Math.round(groundVisualLayer(sample.supportLayer));
            sync(entity, motion);
        } else if (kind == CustomMapRuntime.TerrainKind.MAIN) {
            projectToMain(entity, motion, terrain, entity.pos);
        } else if (kind == CustomMapRuntime.TerrainKind.WATER) {
            motion.ice.clear();
            motion.state = MotionState.SWIM;
            motion.route = TerrainHeightfield.containingLink(terrain, entity.pos,
                    CustomMapDocument.NavigationType.SWIM);
            motion.swimSinkLayer = 0f;
            motion.swimTick = 0;
            entity.currentLayer = Math.round(swimVisualLayer(terrain, motion,
                    waterLayer(terrain, entity.pos), false));
            sync(entity, motion);
        } else {
            beginFall(entity, motion, entity.pos, entity.currentLayer, 0f, 0f);
        }
    }

    private static void updateJump(Entity entity, Motion motion,
                                   CustomMapDocument.ModeVariant terrain, boolean damaged) {
        if (motion.jump == null) {
            beginFall(entity, motion, entity.pos, entity.currentLayer, 0f, 0f);
            return;
        }
        suppressJumpCombat(entity, motion);
        forceWalkFrame(entity);
        if (damaged) {
            float rebound = -motion.jump.direction * terrain.worldUnitsPerTile() * 0.075f;
            float upward = -terrain.layerUnitsPerTile() * 0.34f;
            beginFall(entity, motion, entity.pos, entity.currentLayer, rebound, upward);
            return;
        }
        motion.jumpTick++;
        float progress = motion.jumpTick / (float) Math.max(1, motion.jumpDuration);
        if (progress >= 1f) {
            entity.pos = motion.jump.landingWorldX;
            if (motion.jumpLandingWater) {
                float water = waterLayer(terrain, entity.pos);
                motion.state = MotionState.SWIM;
                motion.jump = null;
                motion.jumpLandingWater = false;
                motion.swimSinkLayer = 0f;
                motion.swimTick = 0;
                motion.swimCarryWorld = airborneCarry(motion)
                        * terrain.worldUnitsPerTile();
                if (motion.route == null)
                    motion.route = TerrainHeightfield.containingLink(
                            terrain, entity.pos,
                            CustomMapDocument.NavigationType.SWIM);
                entity.currentLayer = Math.round(swimVisualLayer(
                        terrain, motion, water, false));
                motion.iceCarryTilesPerTick = 0f;
                resumeCombatAfterTraversal(entity, motion);
                emit(VfxKind.SPLASH, entity.pos, water,
                        motion.lastMoveDirection);
                sync(entity, motion);
                return;
            }
            if (motion.jumpLandingPlatformId != null) {
                String boardingId = motion.jumpLandingPlatformId;
                CustomMapRuntime.TerrainSample landing =
                        CustomMapRuntime.sampleTerrain(entity.pos,
                                motion.jump.landingLayer, true);
                CustomMapRuntime.finishPlatformBoarding(boardingId, entity);
                motion.jumpLandingPlatformId = null;
                if (landing.kind != CustomMapRuntime.TerrainKind.FLOATING
                        || !boardingId.equals(landing.platformId)) {
                    beginFall(entity, motion, entity.pos,
                            motion.jump.landingLayer, 0f, 0f);
                    return;
                }
                motion.platformId = landing.platformId;
                motion.lastPlatformCarryTick = CustomMapRuntime.platformTick();
                entity.currentLayer = Math.round(groundVisualLayer(
                        landing.supportLayer));
                motion.state = MotionState.GROUND;
                motion.jump = null;
                motion.route = null;
                if (!restoreIceCarry(entity, motion, terrain, landing.platformId))
                    resumeCombatAfterTraversal(entity, motion);
                emit(VfxKind.LAND, entity.pos, landing.supportLayer,
                        motion.lastMoveDirection);
                sync(entity, motion);
                return;
            }
            float landingLayer = motion.jump.landingLayer;
            entity.currentLayer = Math.round(groundVisualLayer(landingLayer));
            motion.state = MotionState.GROUND;
            motion.jump = null;
            motion.route = null;
            if (!restoreIceCarry(entity, motion, terrain, null))
                resumeCombatAfterTraversal(entity, motion);
            emit(VfxKind.LAND, entity.pos, landingLayer, motion.lastMoveDirection);
            sync(entity, motion);
            return;
        }
        entity.pos = motion.jump.worldXAt(progress);
        entity.currentLayer = Math.round(groundVisualLayer(
                motion.jump.layerAt(progress)));
        forceWalkFrame(entity);
        sync(entity, motion);
    }

    private static void updateSwim(Entity entity, Motion motion,
                                   CustomMapDocument.ModeVariant terrain) {
        float nativeDelta = entity.pos - motion.beforePos;
        int direction = sign(nativeDelta);
        if (direction != 0) motion.lastMoveDirection = direction;
        boolean attacking = isAttacking(entity);
        CustomMapPhysicsRules rules = rulesFor(terrain);
        CustomMapPhysicsRules.SwimStep swim = rules.advanceSwim(
                motion.swimTick, motion.swimSinkLayer, motion.beforePos,
                waterLayer(terrain, motion.beforePos), attacking);
        motion.swimTick = swim.tick;
        motion.swimSinkLayer = swim.sinkLayer;
        if (!attacking) forceSwimWalk(entity, motion);
        float carry = motion.swimCarryWorld;
        if (carry != 0f) {
            float floor = nativeSpeedWorld(entity, terrain)
                    * rules.waterSpeedRatio()
                    * CustomMapPhysicsRules.SWIM_CARRY_FLOOR_RATIO;
            motion.swimCarryWorld = Math.abs(carry) <= floor
                    ? 0f : carry * CustomMapPhysicsRules.SWIM_CARRY_RETENTION;
        }
        float candidate = motion.beforePos
                + nativeDelta * rules.waterSpeedRatio() + carry;
        CustomMapRuntime.TerrainKind kind = mainKind(terrain, candidate);
        if (kind == CustomMapRuntime.TerrainKind.WATER) {
            entity.pos = candidate;
            float visual = rules.swimVisualLayer(
                    motion.swimTick, motion.swimSinkLayer, motion.beforePos,
                    waterLayer(terrain, candidate), attacking);
            entity.currentLayer = Math.round(visual);
            if (!CustomMapRuntime.isLavaLiquidAt(candidate, visual)
                    && visual >= terrain.layerUnitsPerTile() * 2f) {
                entity.health = 0L;
                entity.kill(Entity.KillMode.NORMAL);
                return;
            }
            sync(entity, motion);
        } else if (kind == CustomMapRuntime.TerrainKind.MAIN) {
            int exitDirection = direction != 0 ? direction : motion.lastMoveDirection;
            CustomMapRuntime.GapJump jump = buildWaterExitJump(
                    terrain, motion.route, motion.beforePos,
                    entity.currentLayer, exitDirection);
            if (jump == null) {

                beginFall(entity, motion, motion.beforePos, entity.currentLayer,
                        exitDirection * terrain.worldUnitsPerTile() * 0.025f, 0f);
                return;
            }
            motion.state = MotionState.JUMP;
            motion.jump = jump;
            motion.route = null;
            motion.jumpLandingWater = false;
            motion.jumpTick = 0;
            motion.swimCarryWorld = 0f;
            motion.jumpDuration = rules.waterExitDuration(
                    jump, Math.abs(nativeDelta));
            motion.lastMoveDirection = exitDirection;
            entity.pos = jump.startWorldX;
            entity.currentLayer = Math.round(groundVisualLayer(jump.startLayer));
            suppressJumpCombat(entity, motion);
            forceWalkFrame(entity);
            emit(VfxKind.SPLASH, entity.pos,
                    waterLayer(terrain, entity.pos), exitDirection);
            sync(entity, motion);
        } else {
            beginFall(entity, motion, candidate, entity.currentLayer,
                    direction * terrain.worldUnitsPerTile() * 0.025f, 0f);
        }
    }

    static CustomMapRuntime.GapJump buildWaterExitJump(
            CustomMapDocument.ModeVariant terrain,
            CustomMapDocument.NavigationLink route,
            float currentWorldX, float currentVisualLayer, int direction) {
        CustomMapPhysicsRules rules = CustomMapPhysicsRules.bind(null, terrain);
        return rules == null ? null : rules.waterExitJump(
                route, currentWorldX, currentVisualLayer, direction);
    }

    static CustomMapRuntime.GapJump buildWaterEntryJump(
            CustomMapDocument.ModeVariant terrain,
            CustomMapDocument.NavigationLink route,
            float currentWorldX, int direction) {
        CustomMapPhysicsRules rules = CustomMapPhysicsRules.bind(null, terrain);
        return rules == null ? null : rules.waterEntryJump(
                route, currentWorldX, direction);
    }

    private static void beginWaterEntryJump(
            Entity entity, Motion motion, CustomMapDocument.ModeVariant terrain,
            CustomMapDocument.NavigationLink route, float currentWorldX,
            int direction, float nativeSpeed) {
        motion.ice.clear();
        CustomMapRuntime.GapJump jump = buildWaterEntryJump(
                terrain, route, currentWorldX, direction);
        if (jump == null) {
            beginFall(entity, motion, currentWorldX, entity.currentLayer,
                    direction * terrain.worldUnitsPerTile() * .025f, 0f);
            return;
        }
        motion.state = MotionState.JUMP;
        motion.jump = jump;
        motion.route = route;
        motion.jumpLandingWater = true;
        motion.jumpTick = 0;
        motion.jumpDuration = rulesFor(terrain).waterEntryDuration(
                jump, nativeSpeed);
        motion.lastMoveDirection = direction;
        entity.pos = jump.startWorldX;
        entity.currentLayer = Math.round(groundVisualLayer(jump.startLayer));
        suppressJumpCombat(entity, motion);
        forceWalkFrame(entity);
        emit(VfxKind.DUST, entity.pos, jump.startLayer, direction);
        sync(entity, motion);
    }

    private static void relocateNativeStageEnemySpawn(
            Entity entity, Motion motion, CustomMapDocument.ModeVariant terrain) {
        if (!(entity instanceof EEnemy) || entity.dire <= 0 || entity.livingTime > 2
                || ((EEnemy) entity).line < 0) return;
        float spawn = enemySpawnWorldX(terrain);
        entity.pos = spawn;
        entity.lastPosition = spawn;
        motion.beforePos = spawn;
        motion.beforeLayer = Math.round(groundVisualLayer(
                terrain.surfaceLayerAt(spawn)));
    }

    static float enemySpawnWorldX(CustomMapDocument.ModeVariant terrain) {
        if (terrain == null || terrain.spawn == null) return 700f;
        return terrain.worldX(terrain.spawn.x) - 100f;
    }

    private static void updateIceMain(
            Entity entity, Motion motion,
            CustomMapDocument.ModeVariant terrain,
            IceSurfaceRules.Step step) {
        if (step.phase == IceSurfaceRules.Phase.TUMBLE) {
            motion.state = MotionState.ICE_TUMBLE;
            moveIceTumbleStep(entity, motion, terrain, step);
            return;
        }
        int direction = sign(step.deltaTiles);
        if (direction == 0) {
            motion.ice.clear();
            motion.state = MotionState.GROUND;
            projectToMain(entity, motion, terrain, motion.beforePos);
            return;
        }
        motion.lastMoveDirection = direction;
        if (step.lockAttack) {
            suppressJumpCombat(entity, motion);
            forceWalkFrame(entity);
        }
        float units = terrain.worldUnitsPerTile();
        float velocityWorld = step.deltaTiles * units;
        float candidate = motion.beforePos + velocityWorld;
        TerrainHeightfield.MainStep mainStep = CustomMapRuntime.firstMainStep(
                terrain, motion.beforePos, candidate);
        if (mainStep != null && mainStep.kind == TerrainHeightfield.StepKind.UP) {
            if (!motion.ice.canVoluntarilyStop()) {
                float safe = mainStep.boundaryWorldX - direction * units * .025f;
                entity.pos = safe;
                entity.currentLayer = Math.round(groundVisualLayer(
                        terrain.surfaceLayerAt(safe)));
                motion.ice.beginTumble();
                motion.state = MotionState.ICE_TUMBLE;
                suppressJumpCombat(entity, motion);
                forceTumbleFrame(entity);
                emit(VfxKind.LAND, safe, terrain.surfaceLayerAt(safe), direction);
                sync(entity, motion);
                return;
            }
            motion.ice.clear();
            CustomMapRuntime.GapJump jump = buildStepUpJump(
                    terrain, mainStep, motion.beforePos);
            if (jump != null) {
                beginDryJump(entity, motion, jump, Math.abs(velocityWorld));
                return;
            }
        }
        if (mainStep != null && mainStep.kind == TerrainHeightfield.StepKind.DOWN) {
            float edge = mainStep.boundaryWorldX + direction * units * .025f;
            motion.ice.clear();
            beginFall(entity, motion, edge, groundVisualLayer(mainStep.fromLayer),
                    velocityWorld, 0f);
            return;
        }

        TerrainHeightfield.Sweep sweep = CustomMapRuntime.sweepMain(
                terrain, motion.beforePos, candidate,
                terrain.layerUnitsPerTile() * .60f);
        if (resolveBlockedContact(entity, motion, terrain, sweep,
                direction, Math.abs(velocityWorld), velocityWorld)) return;
        if (sweep.blocked && sweep.contact.kind == CustomMapRuntime.TerrainKind.MAIN) {
            float safe = sweep.worldX;
            entity.pos = safe;
            entity.currentLayer = Math.round(groundVisualLayer(
                    terrain.surfaceLayerAt(safe)));
            motion.ice.beginTumble();
            motion.state = MotionState.ICE_TUMBLE;
            suppressJumpCombat(entity, motion);
            forceTumbleFrame(entity);
            emit(VfxKind.LAND, safe, terrain.surfaceLayerAt(safe), direction);
            sync(entity, motion);
            return;
        }

        float resolved = sweep.blocked ? sweep.worldX : candidate;
        entity.pos = resolved;
        entity.currentLayer = Math.round(groundVisualLayer(
                terrain.surfaceLayerAt(resolved)));
        if (!terrain.isIceSurfaceAt(resolved, null)) {
            if (Math.abs(step.deltaTiles) >= IceSurfaceRules.TUMBLE_THRESHOLD) {
                motion.ice.beginTumble();
                motion.state = MotionState.ICE_TUMBLE;
                suppressJumpCombat(entity, motion);
                forceTumbleFrame(entity);
                emit(VfxKind.DUST, resolved,
                        terrain.surfaceLayerAt(resolved), direction);
            } else {
                motion.ice.clear();
                motion.state = MotionState.GROUND;
                resumeCombatAfterTraversal(entity, motion);
            }
        } else motion.state = MotionState.GROUND;
        sync(entity, motion);
    }

    private static void updateIceTumble(Entity entity, Motion motion,
                                        CustomMapDocument.ModeVariant terrain) {
        IceSurfaceRules.Step step = rulesFor(terrain).advanceIce(
                motion.ice,
                terrain.isIceSurfaceAt(motion.beforePos, motion.platformId), 0f,
                motion.lastMoveDirection != 0
                        ? motion.lastMoveDirection : entity.dire, 0);
        if (!step.forced) {
            motion.state = MotionState.GROUND;
            resumeCombatAfterTraversal(entity, motion);
            projectToMain(entity, motion, terrain, motion.beforePos);
            return;
        }
        moveIceTumbleStep(entity, motion, terrain, step);
    }

    private static void moveIceTumbleStep(
            Entity entity, Motion motion,
            CustomMapDocument.ModeVariant terrain,
            IceSurfaceRules.Step step) {
        suppressJumpCombat(entity, motion);
        forceTumbleFrame(entity);
        int direction = sign(step.deltaTiles);
        if (direction == 0) {
            motion.ice.clear();
            motion.state = MotionState.GROUND;
            resumeCombatAfterTraversal(entity, motion);
            projectToMain(entity, motion, terrain, motion.beforePos);
            return;
        }
        motion.lastMoveDirection = direction;
        float units = terrain.worldUnitsPerTile();
        float candidate = motion.beforePos + step.deltaTiles * units;
        TerrainHeightfield.MainStep mainStep = CustomMapRuntime.firstMainStep(
                terrain, motion.beforePos, candidate);
        if (mainStep != null && mainStep.kind == TerrainHeightfield.StepKind.UP) {
            float safe = mainStep.boundaryWorldX - direction * units * .025f;
            entity.pos = safe;
            entity.currentLayer = Math.round(groundVisualLayer(
                    terrain.surfaceLayerAt(safe)));
            motion.ice.start(-step.deltaTiles * .35f,
                    IceSurfaceRules.Phase.TUMBLE);
            emit(VfxKind.LAND, safe, terrain.surfaceLayerAt(safe), direction);
            sync(entity, motion);
            return;
        }
        if (mainStep != null && mainStep.kind == TerrainHeightfield.StepKind.DOWN) {
            float edge = mainStep.boundaryWorldX + direction * units * .025f;
            float velocity = step.deltaTiles * units;
            motion.ice.clear();
            beginFall(entity, motion, edge, groundVisualLayer(mainStep.fromLayer),
                    velocity, 0f);
            return;
        }
        TerrainHeightfield.Sweep sweep = CustomMapRuntime.sweepMain(
                terrain, motion.beforePos, candidate,
                terrain.layerUnitsPerTile() * .60f);
        float velocity = step.deltaTiles * units;
        if (resolveBlockedContact(entity, motion, terrain, sweep,
                direction, Math.abs(velocity), velocity)) return;
        if (sweep.blocked && sweep.contact.kind != CustomMapRuntime.TerrainKind.MAIN) {
            motion.ice.clear();
            beginFall(entity, motion, sweep.worldX, entity.currentLayer,
                    velocity, 0f);
            return;
        }
        if (sweep.blocked) {
            entity.pos = sweep.worldX;
            entity.currentLayer = Math.round(groundVisualLayer(
                    terrain.surfaceLayerAt(sweep.worldX)));
            motion.ice.start(-step.deltaTiles * .35f,
                    IceSurfaceRules.Phase.TUMBLE);
            emit(VfxKind.LAND, entity.pos,
                    terrain.surfaceLayerAt(entity.pos), direction);
        } else {
            entity.pos = candidate;
            entity.currentLayer = Math.round(groundVisualLayer(
                    terrain.surfaceLayerAt(candidate)));
        }
        sync(entity, motion);
    }

    private static void beginSlide(Entity entity, Motion motion,
                                   CustomMapDocument.ModeVariant terrain,
                                   int downhillDirection, float nativeSpeed) {
        motion.state = MotionState.SLIDE;
        motion.slideDirection = downhillDirection == 0 ? -entity.dire : downhillDirection;
        motion.slideVelocity = Math.max(6f, nativeSpeed * 1.35f);
        motion.slideTicks = 18;
        emit(VfxKind.DUST, entity.pos, terrain.surfaceLayerAt(entity.pos),
                motion.slideDirection);
        updateSlide(entity, motion, terrain);
    }

    private static void updateSlide(Entity entity, Motion motion,
                                    CustomMapDocument.ModeVariant terrain) {
        if (motion.slideTicks-- <= 0 || motion.slideDirection == 0) {
            motion.state = MotionState.GROUND;
            projectToMain(entity, motion, terrain, entity.pos);
            return;
        }
        float candidate = motion.beforePos + motion.slideDirection * motion.slideVelocity;
        TerrainHeightfield.Sweep sweep = CustomMapRuntime.sweepMain(
                terrain, motion.beforePos, candidate,
                terrain.layerUnitsPerTile() * 0.60f);
        CustomMapRuntime.TerrainKind kind = sweep.blocked
                ? sweep.contact.kind : CustomMapRuntime.TerrainKind.MAIN;
        if (kind == CustomMapRuntime.TerrainKind.WATER) {
            CustomMapDocument.NavigationLink swim = TerrainHeightfield.link(
                    terrain, sweep.worldX, motion.slideDirection,
                    CustomMapDocument.NavigationType.SWIM);
            if (swim == null) {
                beginFall(entity, motion, candidate, entity.currentLayer,
                        motion.slideDirection * motion.slideVelocity, 0f);
                return;
            }
            beginWaterEntryJump(entity, motion, terrain, swim,
                    sweep.worldX, motion.slideDirection,
                    Math.max(1f, motion.slideVelocity));
            return;
        }
        if (kind == CustomMapRuntime.TerrainKind.VOID) {
            beginFall(entity, motion, candidate, entity.currentLayer,
                    motion.slideDirection * motion.slideVelocity, 0f);
            return;
        }
        float resolved = sweep.blocked ? sweep.worldX : candidate;
        CustomMapRuntime.SlopeSample slope =
                CustomMapRuntime.sampleSlope(terrain, resolved);
        entity.pos = resolved;
        entity.currentLayer = Math.round(groundVisualLayer(
                terrain.surfaceLayerAt(resolved)));
        motion.slideVelocity *= slope.isSlope()
                && slope.downhillDirection == motion.slideDirection ? 0.94f : 0.74f;
        if (!slope.isSlope() && motion.slideVelocity
                < terrain.worldUnitsPerTile() * 0.012f) motion.slideTicks = 0;
        sync(entity, motion);
    }

    private static boolean restoreIceCarry(
            Entity entity, Motion motion,
            CustomMapDocument.ModeVariant terrain, String platformId) {
        float carry = airborneCarry(motion);
        motion.iceCarryTilesPerTick = 0f;
        if (carry == 0f || terrain == null
                || !terrain.isIceSurfaceAt(entity.pos, platformId)) return false;
        motion.ice.start(carry, IceSurfaceRules.Phase.GLIDE);
        if (!motion.ice.active()) return false;
        motion.lastMoveDirection = motion.ice.direction();
        suppressJumpCombat(entity, motion);
        forceWalkFrame(entity);
        return true;
    }

    private static float airborneCarry(Motion motion) {
        float carry = motion.iceCarryTilesPerTick;
        if (carry == 0f) return 0f;
        int ticks = Math.max(0, motion.jumpTick);
        return carry * (float) Math.pow(
                CustomMapPhysicsRules.JUMP_CARRY_RETENTION, ticks);
    }

    private static void noteIceCarry(Motion motion) {
        motion.iceCarryTilesPerTick = motion.ice.active()
                ? motion.ice.velocityTilesPerTick() : 0f;
    }

    private static boolean resolveBlockedContact(
            Entity entity, Motion motion, CustomMapDocument.ModeVariant terrain,
            TerrainHeightfield.Sweep sweep, int direction,
            float nativeSpeed, float velocityWorld) {
        if (sweep == null || !sweep.blocked || sweep.contact == null
                || direction == 0) return false;
        CustomMapRuntime.TerrainKind kind = sweep.contact.kind;
        boolean isVoid = kind == CustomMapRuntime.TerrainKind.VOID;
        if (!isVoid && kind != CustomMapRuntime.TerrainKind.WATER) return false;
        if (isVoid) {
            CustomMapRuntime.GapJump jump = CustomMapRuntime.findGapJump(
                    terrain, sweep.worldX, direction);
            if (jump != null) {
                noteIceCarry(motion);
                beginDryJump(entity, motion, jump, nativeSpeed,
                        GAP_JUMP_SPEED_MULTIPLIER);
                return true;
            }
        } else {
            CustomMapDocument.NavigationLink swim = TerrainHeightfield.link(
                    terrain, sweep.worldX, direction,
                    CustomMapDocument.NavigationType.SWIM);
            if (swim != null) {
                noteIceCarry(motion);
                beginWaterEntryJump(entity, motion, terrain, swim,
                        sweep.worldX, direction, nativeSpeed);
                return true;
            }
        }
        float fall = velocityWorld;
        if (fall == 0f)
            fall = direction * terrain.worldUnitsPerTile()
                    * (isVoid ? CustomMapPhysicsRules.VOID_FALL_NUDGE_RATIO
                    : CustomMapPhysicsRules.WATER_FALL_NUDGE_RATIO);
        beginFall(entity, motion, sweep.worldX, entity.currentLayer, fall, 0f);
        return true;
    }

    private static void beginFall(Entity entity, Motion motion, float x, float layer,
                                  float velocityX, float velocityLayer) {
        if (velocityX == 0f && motion.ice.active()) {
            CustomMapDocument.ModeVariant terrain =
                    CustomMapRuntime.activeBattleTerrain();
            if (terrain != null)
                velocityX = motion.ice.velocityTilesPerTick()
                        * terrain.worldUnitsPerTile();
        }
        motion.iceCarryTilesPerTick = 0f;
        motion.swimCarryWorld = 0f;
        motion.ice.clear();
        finishDockCross(entity, motion);
        if (motion.jumpLandingPlatformId != null)
            CustomMapRuntime.finishPlatformBoarding(
                    motion.jumpLandingPlatformId, entity);
        motion.state = MotionState.FALL;
        motion.platformId = null;
        motion.lastPlatformCarryTick = Long.MIN_VALUE;
        motion.waitingForDock = false;
        motion.dockCrossPlatformId = null;
        motion.jump = null;
        motion.jumpLandingPlatformId = null;
        motion.jumpLandingWater = false;
        motion.fallX = x;
        motion.fallLayer = layer;
        motion.velocityX = velocityX;
        motion.velocityLayer = velocityLayer;
        entity.pos = x;
        entity.currentLayer = Math.round(layer);
        suppressJumpCombat(entity, motion);
        forceWalkFrame(entity);
        emit(VfxKind.EDGE, x, layer, sign(velocityX));
        sync(entity, motion);
    }

    private static void updateFall(Entity entity, Motion motion,
                                   CustomMapDocument.ModeVariant terrain) {
        suppressJumpCombat(entity, motion);
        forceWalkFrame(entity);
        float previousX = motion.fallX;
        float previousLayer = motion.fallLayer;
        motion.fallX += motion.velocityX;
        motion.velocityX *= CustomMapPhysicsRules.FALL_HORIZONTAL_RETENTION;
        CustomMapPhysicsRules rules = rulesFor(terrain);
        motion.velocityLayer = Math.min(
                motion.velocityLayer + rules.gravityPerStep(30),
                rules.terminalFallPerStep(30));
        motion.fallLayer += motion.velocityLayer;
        CustomMapRuntime.AirborneContact airborne = CustomMapRuntime.sweepAirborne(
                previousX, previousLayer, motion.fallX, motion.fallLayer);
        CustomMapRuntime.TerrainSample landing = airborne.terrain;
        if ((airborne.kind() == CustomMapRuntime.TerrainKind.MAIN
                || airborne.kind() == CustomMapRuntime.TerrainKind.FLOATING)
                && motion.velocityLayer >= 0f) {
            float support = landing.supportLayer;
            float tolerance = terrain.layerUnitsPerTile() * .12f;
            if (!Float.isNaN(support)
                    && previousLayer <= support + tolerance
                    && airborne.actorLayer >= support - tolerance) {
                motion.state = MotionState.GROUND;
                motion.platformId = airborne.kind()
                        == CustomMapRuntime.TerrainKind.FLOATING
                        ? landing.platformId : null;
                motion.lastPlatformCarryTick = motion.platformId == null
                        ? Long.MIN_VALUE : CustomMapRuntime.platformTick();
                motion.fallX = airborne.worldX;
                motion.fallLayer = airborne.actorLayer;
                entity.pos = airborne.worldX;
                entity.currentLayer = Math.round(groundVisualLayer(support));
                finishPlatformOriginLanding(entity, motion, terrain, support);
                resumeCombatAfterTraversal(entity, motion);
                if (motion.stunTicks > 0) suppressJumpCombat(entity, motion);
                emit(VfxKind.LAND, entity.pos, support, sign(motion.velocityX));
                sync(entity, motion);
                return;
            }
        }

        if (airborne.kind() == CustomMapRuntime.TerrainKind.WATER) {
            float water = landing.supportLayer;
            if (!Float.isNaN(water)) {
                motion.state = MotionState.SWIM;
                motion.platformId = null;
                motion.platformOriginFall = false;
                motion.platformFallOriginLayer = Float.NaN;
                motion.fallX = airborne.worldX;
                motion.fallLayer = airborne.actorLayer;
                if (motion.route == null)
                    motion.route = TerrainHeightfield.containingLink(
                            terrain, airborne.worldX,
                            CustomMapDocument.NavigationType.SWIM);
                motion.swimSinkLayer = 0f;
                motion.swimTick = 0;
                motion.swimCarryWorld = motion.velocityX;
                entity.pos = airborne.worldX;
                entity.currentLayer = Math.round(swimVisualLayer(
                        terrain, motion, water, false));
                resumeCombatAfterTraversal(entity, motion);
                emit(VfxKind.SPLASH, entity.pos, water, sign(motion.velocityX));
                sync(entity, motion);
                return;
            }
        }
        entity.pos = motion.fallX;
        entity.currentLayer = Math.round(motion.fallLayer);
        sync(entity, motion);
        if (motion.fallLayer >= terrain.layerUnitsPerTile() * 2f) {
            entity.health = 0L;
            entity.kill(Entity.KillMode.NORMAL);
        }
    }

    private static void finishPlatformOriginLanding(
            Entity entity, Motion motion, CustomMapDocument.ModeVariant terrain,
            float landingSupport) {
        if (!motion.platformOriginFall
                || Float.isNaN(motion.platformFallOriginLayer)) return;
        float dropTiles = Math.max(0f,
                (landingSupport - motion.platformFallOriginLayer)
                        / Math.max(1f, terrain.layerUnitsPerTile()));
        CustomMapLandingImpact.Result result =
                CustomMapLandingImpact.resolve(active, entity, dropTiles);
        boolean slowImmune = nativeImmunity(entity, "IMUSLOW");
        boolean stopImmune = nativeImmunity(entity, "IMUSTOP");
        motion.stunTicks = result.penalty.effectiveStunTicks(stopImmune);
        motion.slowTicks = result.penalty.effectiveSlowTicks(slowImmune);
        motion.slowMovementMultiplier = motion.slowTicks > 0
                ? result.penalty.movementMultiplier : 1f;
        motion.platformOriginFall = false;
        motion.platformFallOriginLayer = Float.NaN;
    }

    private static float nativeSpeedTiles(Entity entity,
                                          CustomMapDocument.ModeVariant terrain) {
        if (entity == null || terrain == null) return 0f;
        try {
            Object data = BCUFields.get(entity, "data");
            Object speed = BCUFields.invoke(data, "getSpeed");
            if (!(speed instanceof Number)) return 0f;
            float world = ((Number) speed).intValue() * .5f;
            return Math.max(0f, world / Math.max(1f, terrain.worldUnitsPerTile()));
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static float nativeSpeedWorld(Entity entity,
                                          CustomMapDocument.ModeVariant terrain) {
        return nativeSpeedTiles(entity, terrain)
                * Math.max(1f, terrain == null ? 1f : terrain.worldUnitsPerTile());
    }

    private static boolean nativeImmunity(Entity entity, String fieldName) {
        if (entity == null || fieldName == null) return false;
        try {
            Object data = BCUFields.get(entity, "data");
            Object proc = BCUFields.invoke(data, "getProc");
            Object immunity = BCUFields.get(proc, fieldName);
            Object exists = BCUFields.invoke(immunity, "exists");
            return exists instanceof Boolean && ((Boolean) exists).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void projectToMain(Entity entity, Motion motion,
                                      CustomMapDocument.ModeVariant terrain, float x) {
        TerrainHeightfield.Contact contact =
                CustomMapRuntime.sampleMain(terrain, x, entity.currentLayer);
        float layer = contact.kind == CustomMapRuntime.TerrainKind.MAIN
                ? contact.supportLayer : Float.NaN;
        if (Float.isNaN(layer)) {
            beginFall(entity, motion, x, entity.currentLayer, 0f, 0f);
            return;
        }
        CustomMapRuntime.SlopeSample slope =
                CustomMapRuntime.sampleSlope(terrain, x);
        motion.platformId = null;
        motion.lastPlatformCarryTick = Long.MIN_VALUE;
        motion.waitingForDock = false;
        motion.dockCrossPlatformId = null;
        motion.state = slope.isUphill(motion.lastMoveDirection)
                ? MotionState.CLIMB : MotionState.GROUND;
        entity.pos = x;
        entity.currentLayer = Math.round(groundVisualLayer(layer));
        sync(entity, motion);
    }

    private static boolean isManualPositionOwner(Entity entity) {
        if (entity == null) return false;
        HoldState hold = HoldState.get();
        if (hold.getHeldEntity() == entity
                && hold.getPhase() != HoldState.Phase.NONE) return true;
        return FallingRegistry.isManaged(entity);
    }

    private static boolean isNativeRelocationOwner(Entity entity) {
        if (entity == null) return false;
        try {
            int kbTime = BCUFields.getInt(entity, "kbTime");
            if (kbTime == -2 || kbTime == -3 || kbTime == -4) return true;
        } catch (Throwable ignored) {}
        try {
            int[][] status = entity.status;
            return status != null && status.length > 11
                    && status[11] != null && status[11].length > 2
                    && (status[11][0] > 0 || status[11][1] > 0
                    || status[11][2] != 0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void resetAuthoredMotion(Entity entity, Motion motion) {
        finishDockCross(entity, motion);
        if (motion.jumpLandingPlatformId != null)
            CustomMapRuntime.finishPlatformBoarding(
                    motion.jumpLandingPlatformId, entity);
        motion.initialized = false;
        motion.nativeOwned = false;
        motion.state = MotionState.GROUND;
        motion.jump = null;
        motion.jumpLandingPlatformId = null;
        motion.route = null;
        motion.jumpLandingWater = false;
        motion.jumpTick = 0;
        motion.jumpDuration = 0;
        motion.fallX = 0f;
        motion.fallLayer = 0f;
        motion.velocityX = 0f;
        motion.velocityLayer = 0f;
        motion.slideDirection = 0;
        motion.slideVelocity = 0f;
        motion.slideTicks = 0;
        motion.swimTick = 0;
        motion.swimSinkLayer = 0f;
        motion.platformId = null;
        motion.lastPlatformCarryTick = Long.MIN_VALUE;
        motion.waitingForDock = false;
        motion.dockCrossPlatformId = null;
        motion.platformOriginFall = false;
        motion.platformFallOriginLayer = Float.NaN;
        motion.stunTicks = 0;
        motion.slowTicks = 0;
        motion.slowMovementMultiplier = 1f;
        motion.lastMoveDirection = 0;
        motion.ice.clear();
    }

    private static void sync(Entity entity, Motion motion) {

        entity.lastPosition = motion == null ? entity.pos : motion.beforePos;
    }

    private static CustomMapRuntime.TerrainKind mainKind(
            CustomMapDocument.ModeVariant terrain, float worldX) {
        return CustomMapRuntime.sampleMain(terrain, worldX, 0f).kind;
    }

    private static float waterLayer(CustomMapDocument.ModeVariant terrain, float worldX) {
        if (!terrain.containsWorldX(worldX)) return Float.NaN;
        return TerrainHeightfield.waterLayer(terrain,
                TerrainHeightfield.tileAt(terrain, worldX));
    }

    private static float groundVisualLayer(float supportLayer) {
        return supportLayer + CustomMapPhysicsRules.GROUND_CONTACT_INSET_LAYERS;
    }

    private static float swimVisualLayer(CustomMapDocument.ModeVariant terrain,
                                         Motion motion, float waterLayer,
                                         boolean attacking) {
        CustomMapPhysicsRules rules = rulesFor(terrain);
        return rules.swimVisualLayer(
                motion.swimTick, motion.swimSinkLayer, motion.beforePos,
                waterLayer, attacking);
    }

    private static CustomMapPhysicsRules rulesFor(
            CustomMapDocument.ModeVariant terrain) {
        CustomMapPhysicsRules rules = activeRules;
        if (rules == null || rules.terrain() != terrain)
            rules = CustomMapPhysicsRules.bind(
                    CustomMapRuntime.activeDocument(), terrain);
        if (rules == null)
            throw new IllegalStateException("Custom Map physics requires terrain");
        return rules;
    }

    private static boolean isAttacking(Entity entity) {
        if (entity == null) return false;
        try {
            Object visual = BCUFields.field(entity.anim.getClass(), "anim").get(entity.anim);
            if (visual instanceof EAnimU
                    && ((EAnimU) visual).type == AnimU.UType.ATK) return true;
        } catch (Throwable ignored) {}
        try {
            Object attackManager = BCUFields.field(entity.getClass(), "atkm").get(entity);
            return BCUFields.getInt(attackManager, "atkTime") > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void suppressJumpCombat(Entity entity, Motion motion) {
        if (entity == null || motion == null) return;
        try {
            BCUFields.field(entity.getClass(), "waitTime").setInt(entity, 2);
            BCUFields.field(entity.getClass(), "walking").setBoolean(entity, true);
            BCUFields.field(entity.getClass(), "touch").setBoolean(entity, false);
            BCUFields.field(entity.getClass(), "touchEnemy").setBoolean(entity, false);
            Object attackManager = BCUFields.field(entity.getClass(), "atkm").get(entity);
            if (!motion.traversalCombatLocked) {
                motion.savedAttacksLeft = BCUFields.field(
                        attackManager.getClass(), "attacksLeft").getInt(attackManager);
                motion.traversalCombatLocked = true;
            }
            BCUFields.field(attackManager.getClass(), "atkTime").setInt(attackManager, 0);
            BCUFields.field(attackManager.getClass(), "tempAtk").setInt(attackManager, -1);
        } catch (Throwable ignored) {}
    }

    private static void resumeCombatAfterTraversal(Entity entity, Motion motion) {
        if (entity == null || motion == null || !motion.traversalCombatLocked) return;
        try {
            Object attackManager = BCUFields.field(entity.getClass(), "atkm").get(entity);
            int current = BCUFields.field(
                    attackManager.getClass(), "attacksLeft").getInt(attackManager);

            int resumed = resolvedAttackLoopAfterTraversal(
                    current, motion.savedAttacksLeft);
            if (resumed != current)
                BCUFields.field(attackManager.getClass(), "attacksLeft")
                        .setInt(attackManager, resumed);
            BCUFields.field(attackManager.getClass(), "atkTime").setInt(attackManager, 0);
            BCUFields.field(attackManager.getClass(), "tempAtk").setInt(attackManager, -1);
            BCUFields.field(entity.getClass(), "waitTime").setInt(entity, 0);
            BCUFields.field(entity.getClass(), "touch").setBoolean(entity, false);
            BCUFields.field(entity.getClass(), "touchEnemy").setBoolean(entity, false);
        } catch (Throwable ignored) {
        } finally {
            motion.traversalCombatLocked = false;
            motion.savedAttacksLeft = 0;
        }
    }

    static int resolvedAttackLoopAfterTraversal(int current, int saved) {
        return current == 0 && saved != 0 ? saved : current;
    }

    private static void forceWalkFrame(Entity entity) {
        if (entity == null) return;
        try {
            Object visual = BCUFields.field(entity.anim.getClass(), "anim").get(entity.anim);
            if (visual instanceof EAnimU) {
                EAnimU animation = (EAnimU) visual;
                if (animation.type != AnimU.UType.WALK)
                    animation.changeAnim(AnimU.UType.WALK, true);
            }
        } catch (Throwable ignored) {}
    }

    private static void forceTumbleFrame(Entity entity) {
        if (entity == null) return;
        try {
            Object visual = BCUFields.field(entity.anim.getClass(), "anim").get(entity.anim);
            if (visual instanceof EAnimU) {
                EAnimU animation = (EAnimU) visual;
                if (animation.type != AnimU.UType.HB)
                    animation.changeAnim(AnimU.UType.HB, false);
                animation.setTime(0f);
            }
        } catch (Throwable ignored) {
            forceWalkFrame(entity);
        }
    }

    private static void forceSwimWalk(Entity entity, Motion motion) {
        if (entity == null || motion == null) return;
        try {
            Object visual = BCUFields.field(entity.anim.getClass(), "anim").get(entity.anim);
            if (visual instanceof EAnimU) {
                EAnimU animation = (EAnimU) visual;
                if (animation.type != AnimU.UType.WALK)
                    animation.changeAnim(AnimU.UType.WALK, false);
                animation.setTime(motion.swimTick % Math.max(1, animation.len()));
            }
        } catch (Throwable ignored) {}
    }

    public static float[] motionDrawFx(Object entityObject) {
        if (!(entityObject instanceof Entity)) return null;
        synchronized (LOCK) {
            Motion motion = MOTIONS.get((Entity) entityObject);
            if (motion == null || motion.manualOwned) return null;
            if (motion.state == MotionState.ICE_TUMBLE) {
                float speed = Math.min(1f, Math.abs(
                        motion.ice.velocityTilesPerTick())
                        / IceSurfaceRules.MAX_GLIDE_SPEED);
                float rock = (float) Math.sin(animationTick * .55f) * .08f;
                return new float[]{motion.lastMoveDirection
                        * (.62f + rock) * Math.max(.45f, speed), .96f, 1.04f};
            }
            if (motion.state != MotionState.JUMP
                    || motion.jumpDuration <= 0) return null;
            float t = Math.max(0f, Math.min(1f,
                    motion.jumpTick / (float) motion.jumpDuration));
            float arc = (float) Math.sin(Math.PI * t);
            float rotation = motion.jump == null ? 0f
                    : motion.jump.direction * 0.055f * arc;
            float squash = t < .14f ? (0.14f - t) / .14f
                    : t > .84f ? (t - .84f) / .16f : 0f;
            return new float[]{rotation, 1f + squash * .08f,
                    1f - squash * .08f};
        }
    }

    public static long animationTick() {
        synchronized (LOCK) { return animationTick; }
    }

    static void emitLavaDamageVfx(float worldX, float layer, int direction) {
        synchronized (LOCK) {
            emit(VfxKind.LAVA_DAMAGE, worldX, layer, direction);
        }
    }

    public static void drawVfx(Object painter, FakeGraphics graphics) {
        drawVfxLayer(painter, graphics, false);
    }

    public static void drawWaterVfxForeground(Object painter, FakeGraphics graphics) {
        drawVfxLayer(painter, graphics, true);
    }

    private static void drawVfxLayer(
            Object painter, FakeGraphics graphics, boolean splashLayer) {
        if (graphics == null || !isActivePainter(painter)) return;
        synchronized (LOCK) {
            try {
                final Object activePainter = painter;
                final FakeGraphics activeGraphics = graphics;
                final boolean foreground = splashLayer;
                withRestoredVfxGraphics(graphics, new VfxGraphicsOperation() {
                    @Override public void draw() {
                        drawVfxList(activePainter, activeGraphics, VFX, foreground);
                        if (!foreground)
                            drawVfxList(activePainter, activeGraphics, AMBIENT_VFX, false);
                    }
                });
            } catch (Throwable t) {
                Logger.err("CustomMap: terrain VFX draw failed", t);
            }
        }
    }

    private interface VfxGraphicsOperation { void draw(); }

    private static void withRestoredVfxGraphics(
            FakeGraphics graphics, VfxGraphicsOperation operation) {
        FakeTransform originalTransform = null;
        try {
            originalTransform = graphics.getTransform();
            operation.draw();
        } finally {
            try {
                if (originalTransform != null)
                    graphics.setTransform(originalTransform);
            } finally {
                graphics.setComposite(FakeGraphics.DEF, 0, 0);
            }
        }
    }

    static void exerciseSpriteDrawStateForTesting(
            final FakeGraphics graphics, final FakeImage sprite,
            final boolean failAfterDraw) {
        withRestoredVfxGraphics(graphics, new VfxGraphicsOperation() {
            @Override public void draw() {
                graphics.setComposite(FakeGraphics.TRANS, 173, 0);
                graphics.translate(7f, 11f);
                graphics.drawImage(sprite, 2f, 3f, 13f, 17f);
                if (failAfterDraw)
                    throw new IllegalStateException("forced VFX draw failure");
            }
        });
    }

    private static void drawVfxList(Object painter, FakeGraphics graphics,
                                    List<TerrainVfx> effects,
                                    boolean splashLayer) {
        int viewportWidth = BBPainterAccess.getWidth(painter);
        int viewportHeight = BBPainterAccess.getHeight(painter);
        float tile = Math.max(1f, CustomMapRuntime.renderedTilePixels(painter));
        float renderSubFrame = vfxRenderSubFrameFraction(painter);
        for (TerrainVfx fx : effects) {
            if (isForegroundVfx(fx.kind) != splashLayer) continue;
            float t = interpolatedVfxProgress(fx.life, fx.maxLife,
                    renderSubFrame);
            float drift = fx.kind == VfxKind.AMBIENT ? 46f
                    : fx.kind == VfxKind.LAVA_DAMAGE ? 10f : 22f;
            int x = Math.round(CrazyRender.screenX(painter, fx.worldX)
                    + fx.direction * t * drift);
            float verticalTravel = fx.kind == VfxKind.AMBIENT
                    ? (usesStratifiedSnowAmbient() ? t * 54f : -t * 54f)
                    : -t * (fx.kind == VfxKind.LAVA_DAMAGE ? 28f
                    : fx.kind == VfxKind.SPLASH ? 38f : 18f);
            int y = Math.round(CustomMapRuntime.projectY(painter, fx.layer)
                    + verticalTravel);
            int alpha = fx.kind == VfxKind.AMBIENT
                    ? Math.max(10, Math.round(150f * fadeEnvelope(t)))
                    : fx.kind == VfxKind.LAVA_DAMAGE
                    ? Math.max(24, Math.round(245f * (1f - t)))
                    : Math.max(18, Math.round(220f * (1f - t)));
            List<FakeImage> sprites = CustomMapRuntime.themeVfxImages(vfxAssetKind(fx.kind));
            FakeImage sprite = sprites.isEmpty() ? null
                    : sprites.get(Math.floorMod(fx.assetIndex, sprites.size()));
            boolean spriteAvailable = sprite != null
                    && sprite.getWidth() > 0 && sprite.getHeight() > 0;
            if (spriteAvailable) {
                float target = tile * (fx.kind == VfxKind.AMBIENT ? .15f
                        : fx.kind == VfxKind.LAVA_DAMAGE ? .42f
                        : fx.kind == VfxKind.SPLASH ? .48f : .34f);
                target = Math.max(fx.kind == VfxKind.AMBIENT ? 10f
                                : fx.kind == VfxKind.LAVA_DAMAGE ? 22f : 18f,
                        Math.min(fx.kind == VfxKind.AMBIENT ? 42f
                                : fx.kind == VfxKind.LAVA_DAMAGE ? 86f : 92f,
                                target));
                float scale = target / Math.max(1f,
                        Math.max(sprite.getWidth(), sprite.getHeight()));
                float width = Math.max(1f, sprite.getWidth() * scale);
                float height = Math.max(1f, sprite.getHeight() * scale);
                float left = x - width * .5f;
                float top = y - height;
                if (left + width < -8f || left > viewportWidth + 8f
                        || top + height < -8f || top > viewportHeight + 8f) continue;
                graphics.setComposite(FakeGraphics.TRANS, alpha, 0);
                graphics.drawImage(sprite, left, top, width, height);
                continue;
            }
            if (!shouldDrawPrimitiveFallback(spriteAvailable,
                    fx.kind == VfxKind.AMBIENT)
                    || x < -32 || x > viewportWidth + 32
                    || y < -32 || y > viewportHeight + 32) continue;
            graphics.setComposite(FakeGraphics.TRANS, alpha, 0);
            setPrimitiveVfxColor(graphics, fx.kind);
            int spread = 4 + Math.round(t * 14f);
            graphics.fillRect(x - spread, y - 2, spread, 3);
            graphics.fillRect(x + 2, y - 5 - Math.round(t * 7f),
                    Math.max(2, spread / 2), 3);
        }
    }

    static boolean shouldDrawPrimitiveFallback(boolean spriteAvailable,
                                               boolean ambient) {
        return !spriteAvailable && !ambient;
    }

    static float interpolatedVfxProgress(int life, int maxLife,
                                         float subFrameFraction) {
        if (maxLife <= 0) return 1f;
        float fraction = Math.max(0f, Math.min(.999999f, subFrameFraction));
        float age = Math.max(0f, maxLife - life) + fraction;
        return Math.max(0f, Math.min(1f, age / maxLife));
    }

    static float vfxRenderSubFrameFraction(Object painter) {
        try {
            Object stageObject = BBPainterAccess.getStageBasis(painter);
            if (!(stageObject instanceof StageBasis)) return lastVfxRenderSubFrame;
            StageBasis stage = (StageBasis) stageObject;
            if (!drivesTerrainMotion(stage.s_stop,
                    stage.ubase != null && stage.ebase != null,
                    stage.ubase == null ? 0L : stage.ubase.health,
                    stage.ebase == null ? 0L : stage.ebase.health))
                return lastVfxRenderSubFrame;
        } catch (Throwable ignored) {
            return lastVfxRenderSubFrame;
        }
        lastVfxRenderSubFrame = FpsHooks.renderSubFrameFraction();
        return lastVfxRenderSubFrame;
    }

    private static boolean isForegroundVfx(VfxKind kind) {
        return kind == VfxKind.SPLASH || kind == VfxKind.LAVA_DAMAGE;
    }

    private static String vfxAssetKind(VfxKind kind) {
        if (kind == VfxKind.SPLASH) return "splash";
        if (kind == VfxKind.LAND) return "land";
        if (kind == VfxKind.EDGE) return "edge";
        if (kind == VfxKind.LAVA_DAMAGE) return "edge";
        if (kind == VfxKind.AMBIENT) return "ambient";
        return "dust";
    }

    private static float fadeEnvelope(float progress) {
        float fadeIn = Math.min(1f, progress * 6f);
        float fadeOut = Math.min(1f, (1f - progress) * 3f);
        return Math.max(0f, Math.min(fadeIn, fadeOut));
    }

    private static void setPrimitiveVfxColor(FakeGraphics graphics, VfxKind kind) {
        if (CustomMapRuntime.isLavaLiquid()) {
            if (kind == VfxKind.SPLASH) graphics.setColor(255, 92, 24);
            else if (kind == VfxKind.LAVA_DAMAGE) graphics.setColor(255, 174, 52);
            else if (kind == VfxKind.EDGE) graphics.setColor(255, 118, 36);
            else if (kind == VfxKind.LAND) graphics.setColor(92, 76, 70);
            else graphics.setColor(78, 65, 64);
            return;
        }
        if (kind == VfxKind.SPLASH) graphics.setColor(90, 225, 255);
        else if (kind == VfxKind.EDGE) graphics.setColor(255, 105, 70);
        else if (kind == VfxKind.LAND) graphics.setColor(225, 210, 165);
        else graphics.setColor(192, 154, 105);
    }

    private static void emit(VfxKind kind, float worldX, float layer, int direction) {
        if (Float.isNaN(worldX) || Float.isNaN(layer)) return;
        int cap = CustomMapRuntime.eventVfxCap();
        if (cap <= 0) return;
        long serial = vfxSerial++;
        addEventVfx(new TerrainVfx(kind, worldX, layer, direction,
                (int) serial, serial), cap);
    }

    private static void tickVfx() {
        Iterator<TerrainVfx> iterator = VFX.iterator();
        while (iterator.hasNext()) if (--iterator.next().life <= 0) iterator.remove();
        iterator = AMBIENT_VFX.iterator();
        while (iterator.hasNext()) if (--iterator.next().life <= 0) iterator.remove();
        emitAmbientVfx();
    }

    private static void emitAmbientVfx() {
        int cap = CustomMapRuntime.ambientVfxCap();
        boolean snow = usesStratifiedSnowAmbient();
        int interval = snow ? AMBIENT_SNOW_SPAWN_INTERVAL : 3;
        if (cap <= 0 || animationTick % interval != 0L
                || CustomMapRuntime.themeVfxImages("ambient").isEmpty()) return;
        CustomMapDocument.ModeVariant terrain = CustomMapRuntime.activeBattleTerrain();
        if (terrain == null || terrain.width <= 0) return;
        long spawnIndex = animationTick / interval;
        long mixed = BackgroundComposer.mix64(CustomMapRuntime.activeThemeSeed()
                ^ (spawnIndex * 0x9e3779b97f4a7c15L));
        float center = Float.isNaN(vfxFocusWorldX)
                ? terrain.worldWidth() * .5f : vfxFocusWorldX;
        float spread = terrain.worldUnitsPerTile() * 8f;
        float worldX = snow
                ? stratifiedAmbientWorldX(terrain.worldWidth(), center,
                spread, spawnIndex, unitFloat(mixed))
                : Math.min(terrain.worldWidth() - 1f,
                Math.max(0f, center + (unitFloat(mixed) * 2f - 1f) * spread));
        float support = terrain.surfaceLayerAt(worldX);
        if (Float.isNaN(support)) {
            int tileX = Math.max(0, Math.min(terrain.width - 1,
                    (int) (worldX / terrain.worldUnitsPerTile())));
            support = TerrainHeightfield.waterLayer(terrain, tileX);
        }
        if (Float.isNaN(support)) return;
        float liftTiles = 1.2f + unitFloat(BackgroundComposer.mix64(mixed + 0x51L)) * 4.8f;
        float layer = support - liftTiles * terrain.layerUnitsPerTile();
        int direction = (mixed & 1L) == 0L ? -1 : 1;
        int spriteCount = CustomMapRuntime.themeVfxImages("ambient").size();
        int asset = Math.floorMod((int) (mixed >>> 32), Math.max(1, spriteCount));
        addAmbientVfx(new TerrainVfx(VfxKind.AMBIENT, worldX, layer,
                direction, asset, vfxSerial++), cap);
    }

    private static boolean usesStratifiedSnowAmbient() {
        CustomMapDocument.ThemeProfile profile = CustomMapRuntime.activeThemeProfile();
        if (profile == null || profile.vfx == null
                || profile.vfx.profileId == null) return false;
        return profile.vfx.profileId.toLowerCase(java.util.Locale.ROOT)
                .startsWith("snow-ice-");
    }

    static float stratifiedAmbientWorldX(float worldWidth, float center,
                                          float spread, long spawnIndex,
                                          float jitter) {
        if (!(worldWidth > 0f)) return 0f;
        float left = Math.max(0f, center - Math.max(0f, spread));
        float right = Math.min(worldWidth - 1f,
                center + Math.max(0f, spread));
        if (right <= left) return Math.max(0f, Math.min(worldWidth - 1f, center));
        int stratum = Math.floorMod((int) (spawnIndex
                % AMBIENT_SNOW_HORIZONTAL_STRATA),
                AMBIENT_SNOW_HORIZONTAL_STRATA);
        float band = (right - left) / AMBIENT_SNOW_HORIZONTAL_STRATA;
        float unit = Math.max(0f, Math.min(.999999f, jitter));
        return left + (stratum + unit) * band;
    }

    private static void addEventVfx(TerrainVfx effect, int cap) {
        if (effect == null || cap <= 0) return;
        VFX.add(effect);
        while (VFX.size() > cap) VFX.remove(0);
    }

    private static void addAmbientVfx(TerrainVfx effect, int cap) {
        if (effect == null || cap <= 0) return;
        AMBIENT_VFX.add(effect);
        while (AMBIENT_VFX.size() > cap) AMBIENT_VFX.remove(0);
    }

    private static void clearVfxState() {
        VFX.clear();
        AMBIENT_VFX.clear();
        vfxSerial = 0L;
        vfxFocusWorldX = Float.NaN;
        lastVfxRenderSubFrame = 0f;
    }

    static int[] exerciseVfxCapsAndClearForTesting(
            int eventCap, int ambientCap, int eventAttempts, int ambientAttempts) {
        synchronized (LOCK) {
            clearVfxState();
            for (int i = 0; i < Math.max(0, eventAttempts); i++)
                addEventVfx(new TerrainVfx(VfxKind.DUST,
                        i, 0f, 1, i, i), eventCap);
            for (int i = 0; i < Math.max(0, ambientAttempts); i++)
                addAmbientVfx(new TerrainVfx(VfxKind.AMBIENT,
                        i, 0f, 1, i, i), ambientCap);
            int eventCount = VFX.size();
            int ambientCount = AMBIENT_VFX.size();
            int totalCount = eventCount + ambientCount;
            clearVfxState();
            return new int[]{eventCount, ambientCount, totalCount,
                    VFX.size(), AMBIENT_VFX.size()};
        }
    }

    static int[] exerciseLavaDamageVfxForTesting(int cap, int attempts) {
        synchronized (LOCK) {
            clearVfxState();
            for (int i = 0; i < Math.max(0, attempts); i++)
                addEventVfx(new TerrainVfx(VfxKind.LAVA_DAMAGE,
                        i, 0f, 1, i, i), cap);
            int matching = 0;
            for (TerrainVfx effect : VFX)
                if (effect.kind == VfxKind.LAVA_DAMAGE
                        && effect.maxLife == 22
                        && isForegroundVfx(effect.kind)
                        && "edge".equals(vfxAssetKind(effect.kind))) matching++;
            int eventCount = VFX.size();
            clearVfxState();
            return new int[]{eventCount, matching, VFX.size()};
        }
    }

    private static float unitFloat(long value) {
        return (float) ((value >>> 11) * 0x1.0p-53);
    }

    public static void beginEnemyBaseDraw(Object painter, FakeGraphics graphics) {
        if (!isActivePainter(painter) || graphics == null) return;
        try {
            BASE_TRANSFORM.set(graphics.getTransform());
            graphics.translate(0f, baseYOffset(painter, true));
        } catch (Throwable t) {
            Logger.err("CustomMap: enemy base terrain projection failed", t);
        }
    }

    public static void beginPlayerBaseDraw(Object painter, FakeGraphics graphics) {
        if (!isActivePainter(painter) || graphics == null) return;
        try {
            graphics.translate(0f, baseYOffset(painter, false));
        } catch (Throwable t) {
            Logger.err("CustomMap: player base terrain projection failed", t);
        }
    }

    public static void endBaseDraw(Object painter, FakeGraphics graphics) {
        FakeTransform transform = BASE_TRANSFORM.get();
        BASE_TRANSFORM.remove();
        if (transform == null || graphics == null) return;
        try {
            graphics.setTransform(transform);
        } catch (Throwable t) {
            Logger.err("CustomMap: base transform restore failed", t);
        }
    }

    public static P drawProjectedBaseIndicator(
            AbEntity base, SymCoord coordinate, boolean trail, Object painter) {
        if (base == null || coordinate == null) return null;
        try {
            if (isActivePainter(painter) && active != null) {
                boolean enemy = base == active.ebase;
                boolean player = base == active.ubase;
                if (enemy || player)
                    coordinate.y += baseYOffset(painter, enemy);
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: base HUD terrain projection failed", t);
        }
        return Res.getBase(base, coordinate, trail);
    }

    public static void translateGroundEffect(
            Object painter, FakeGraphics graphics, Object effectObject) {
        if (!(effectObject instanceof ContAb) || graphics == null
                || !isActivePainter(painter)) return;
        try {
            ContAb effect = (ContAb) effectObject;
            CustomMapDocument.ModeVariant terrain =
                    CustomMapRuntime.activeBattleTerrain();
            if (terrain == null) return;
            float sourceLayer = effectSourceLayer(effect);
            float targetLayer = effectTargetLayer(
                    terrain, effect.pos, effect.layer, sourceLayer);
            if (Float.isNaN(targetLayer)) return;
            float siz = BBPainterAccess.getSiz(painter);
            float nativeY = BBPainterAccess.getMidh(painter)
                    - (156f - effect.layer * 4f) * siz;
            float terrainY = CustomMapRuntime.projectY(painter, targetLayer);
            graphics.translate(0f, terrainY - nativeY);
        } catch (Throwable t) {
            Logger.err("CustomMap: ground effect terrain projection failed", t);
        }
    }

    static float groundEffectTargetLayer(
            CustomMapDocument.ModeVariant terrain, float worldX,
            float nativeLayer) {
        if (terrain == null || Float.isNaN(nativeLayer)
                || Math.abs(nativeLayer) > Math.max(8f,
                terrain.layerUnitsPerTile() * .35f)) return Float.NaN;
        float support = terrain.surfaceLayerAt(worldX);
        return Float.isNaN(support) ? Float.NaN
                : groundVisualLayer(support);
    }

    static float effectTargetLayer(
            CustomMapDocument.ModeVariant terrain, float worldX,
            float nativeLayer, float sourceLayer) {
        if (!Float.isNaN(sourceLayer) && !Float.isInfinite(sourceLayer))
            return sourceLayer;
        return groundEffectTargetLayer(terrain, worldX, nativeLayer);
    }

    private static float effectSourceLayer(ContAb effect) {
        synchronized (LOCK) {
            Float captured = EFFECT_SOURCE_LAYERS.get(effect);
            if (captured != null || EFFECT_SOURCE_LAYERS.containsKey(effect))
                return captured == null ? Float.NaN : captured.floatValue();

            Entity source = effectSourceEntity(effect);
            float layer = source != null && source.basis == active
                    ? source.currentLayer : Float.NaN;
            EFFECT_SOURCE_LAYERS.put(effect, Float.valueOf(layer));
            return layer;
        }
    }

    private static Entity effectSourceEntity(ContAb effect) {
        if (effect == null) return null;
        try {
            Field[] fields = EFFECT_ATTACK_FIELDS.get(effect.getClass());
            if (fields == null) {
                ArrayList<Field> found = new ArrayList<Field>();
                for (Class<?> type = effect.getClass();
                     type != null && type != ContAb.class;
                     type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        Class<?> fieldType = field.getType();
                        boolean attack = AttackAb.class.isAssignableFrom(fieldType);
                        boolean attacks = fieldType.isArray()
                                && AttackAb.class.isAssignableFrom(
                                fieldType.getComponentType());
                        if (!attack && !attacks) continue;
                        field.setAccessible(true);
                        found.add(field);
                    }
                }
                fields = found.toArray(new Field[found.size()]);
                EFFECT_ATTACK_FIELDS.put(effect.getClass(), fields);
            }
            for (Field field : fields) {
                Object value = field.get(effect);
                if (value instanceof AttackAb) {
                    Entity attacker = attackSourceEntity((AttackAb) value);
                    if (attacker != null) return attacker;
                } else if (value instanceof Object[]) {
                    Object[] attacks = (Object[]) value;
                    for (Object candidate : attacks) {
                        if (!(candidate instanceof AttackAb)) continue;
                        Entity attacker = attackSourceEntity((AttackAb) candidate);
                        if (attacker != null) return attacker;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: effect creator layer lookup failed", t);
        }
        return null;
    }

    private static Entity attackSourceEntity(AttackAb attack) {
        for (int depth = 0; attack != null && depth < 16; depth++) {
            if (attack.attacker != null) return attack.attacker;
            AttackAb parent = attack.origin;
            if (parent == attack) break;
            attack = parent;
        }
        return null;
    }

    private static void projectBaseSmoke(StageBasis stage,
                                         CustomMapDocument.ModeVariant terrain) {
        try {
            projectSmokeList(stage.ebaseSmoke, terrain,
                    anchorSmokeLayer(terrain, terrain.spawn));
            projectSmokeList(stage.ubaseSmoke, terrain,
                    anchorSmokeLayer(terrain, terrain.destination));
        } catch (Throwable t) {
            Logger.err("CustomMap: base smoke terrain projection failed", t);
        }
    }

    private static int anchorSmokeLayer(CustomMapDocument.ModeVariant terrain,
                                        CustomMapDocument.MapAnchor anchor) {
        if (anchor == null) return Integer.MIN_VALUE;
        float support = terrain.surfaceLayerAt(terrain.worldX(anchor.x));
        return Float.isNaN(support) ? Integer.MIN_VALUE
                : Math.round(groundVisualLayer(support));
    }

    private static void projectSmokeList(List<EAnimCont> smoke,
                                         CustomMapDocument.ModeVariant terrain,
                                         int fallbackLayer) {
        if (smoke == null || smoke.isEmpty() || fallbackLayer == Integer.MIN_VALUE)
            return;
        for (int i = 0; i < smoke.size(); i++) {
            EAnimCont puff = smoke.get(i);
            if (puff == null) continue;
            float support = terrain.surfaceLayerAt(puff.pos);
            int layer = Float.isNaN(support) ? fallbackLayer
                    : Math.round(groundVisualLayer(support));
            if (puff.layer == layer) continue;
            BCUFields.setInt(puff, "layer", layer);
        }
    }

    public static void drawCannonBase(Cannon cannon, FakeGraphics graphics,
                                      P point, float size, Object painter) {
        if (cannon == null) return;
        applyCannonBaseOffset(point, painter);
        cannon.drawBase(graphics, point, size);
    }

    public static void drawCannonAttack(Cannon cannon, FakeGraphics graphics,
                                        P point, float size, Object painter) {
        if (cannon == null) return;
        applyCannonAttackOffset(point, cannon, painter);
        cannon.drawAtk(graphics, point, size);
    }

    private static void applyCannonBaseOffset(P point, Object painter) {
        if (point == null || !isActivePainter(painter)) return;
        try {
            point.y += baseYOffset(painter, false);
        } catch (Throwable t) {
            Logger.err("CustomMap: cannon base terrain projection failed", t);
        }
    }

    private static void applyCannonAttackOffset(P point, Cannon cannon,
                                                Object painter) {
        if (point == null || !isActivePainter(painter)) return;
        try {
            CustomMapDocument.ModeVariant terrain =
                    CustomMapRuntime.activeBattleTerrain();
            if (terrain == null) return;
            float layer = groundEffectTargetLayer(terrain, cannon.pos, 0f);
            if (Float.isNaN(layer)) {
                point.y += baseYOffset(painter, false);
                return;
            }
            float nativeGround = BBPainterAccess.getMidh(painter)
                    - 156f * BBPainterAccess.getSiz(painter);
            point.y += CustomMapRuntime.projectY(painter, layer) - nativeGround;
        } catch (Throwable t) {
            Logger.err("CustomMap: cannon attack terrain projection failed", t);
        }
    }

    private static float baseYOffset(Object painter, boolean enemy) {
        CustomMapDocument.ModeVariant terrain = CustomMapRuntime.activeBattleTerrain();
        if (terrain == null) return 0f;
        CustomMapDocument.MapAnchor anchor = enemy ? terrain.spawn : terrain.destination;
        if (anchor == null) return 0f;
        float layer = terrain.surfaceLayerAt(terrain.worldX(anchor.x));
        if (Float.isNaN(layer)) return 0f;
        float nativeGround = BBPainterAccess.getMidh(painter)
                - 156f * BBPainterAccess.getSiz(painter);
        return CustomMapRuntime.projectY(painter, groundVisualLayer(layer)) - nativeGround;
    }

    private static void syncBaseLayer(AbEntity base, float supportLayer) {
        if (!(base instanceof Entity) || Float.isNaN(supportLayer)) return;
        Entity entity = (Entity) base;
        entity.currentLayer = Math.round(groundVisualLayer(supportLayer));
    }

    private static int sign(float value) {
        return value > 0.001f ? 1 : value < -0.001f ? -1 : 0;
    }

    private static final class Motion {
        MotionState state = MotionState.GROUND;
        final IceSurfaceRules.Motion ice = new IceSurfaceRules.Motion();
        float iceCarryTilesPerTick;
        boolean initialized;
        boolean captured;
        boolean manualOwned;
        boolean nativeOwned;
        boolean jumpLandingWater;
        boolean traversalCombatLocked;
        int savedAttacksLeft;
        float swimCarryWorld;
        float beforePos;
        int beforeLayer;
        long healthBefore;
        int lastMoveDirection;
        CustomMapRuntime.GapJump jump;
        String jumpLandingPlatformId;
        CustomMapDocument.NavigationLink route;
        int jumpTick;
        int jumpDuration;
        float fallX;
        float fallLayer;
        float velocityX;
        float velocityLayer;
        int slideDirection;
        float slideVelocity;
        int slideTicks;
        int swimTick;
        float swimSinkLayer;
        String platformId;
        long lastPlatformCarryTick = Long.MIN_VALUE;
        boolean waitingForDock;
        String dockCrossPlatformId;
        boolean platformOriginFall;
        float platformFallOriginLayer = Float.NaN;
        int stunTicks;
        int slowTicks;
        float slowMovementMultiplier = 1f;
    }

    private static final class TerrainVfx {
        final VfxKind kind;
        final float worldX;
        final float layer;
        final int direction;
        final int maxLife;
        final int assetIndex;
        final long serial;
        int life;

        TerrainVfx(VfxKind kind, float worldX, float layer, int direction,
                   int assetIndex, long serial) {
            this.kind = kind;
            this.worldX = worldX;
            this.layer = layer;
            this.direction = direction == 0 ? 1 : direction;
            this.assetIndex = assetIndex;
            this.serial = serial;
            this.maxLife = kind == VfxKind.AMBIENT ? 96
                    : kind == VfxKind.LAVA_DAMAGE ? 22
                    : kind == VfxKind.SPLASH ? 18 : 13;
            this.life = maxLife;
        }
    }
}
