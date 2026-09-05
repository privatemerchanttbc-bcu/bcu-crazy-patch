package manualcontrol.adventure;

import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapLandingImpact;
import manualcontrol.custommap.CustomMapRuntime;
import manualcontrol.custommap.IceSurfaceRules;
import manualcontrol.custommap.MovingPlatformEngine;
import manualcontrol.custommap.MovingPlatformValidator;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

final class AdventureEnemyAI {

    private static final int DEFAULT_RANGE = 140;

    private static final int STUCK_LOG = 90, STUCK_CLEAR = 120;

    private static final int ATTACK_STALL_CLEAR = 45;

    private static final int ATTACK_STUCK_CLEAR = 600;

    private static final float EDGE_HOP_LIFT_TILES = 0.22f;
    private static final float AIR_GRAVITY_TILES = 0.045f;
    private static final float SWIM_SPEED_MULTIPLIER = 0.45f;
    private final java.util.WeakHashMap<Object, Integer> blockedTicks =
            new java.util.WeakHashMap<Object, Integer>();
    private final java.util.WeakHashMap<Object, Integer> attackTicks =
            new java.util.WeakHashMap<Object, Integer>();
    private final java.util.WeakHashMap<Object, Integer> attackStallTicks =
            new java.util.WeakHashMap<Object, Integer>();
    private final java.util.WeakHashMap<EEnemy, TerrainMotion> terrainMotion =
            new java.util.WeakHashMap<EEnemy, TerrainMotion>();

    private static final class TerrainMotion {
        final IceSurfaceRules.Motion ice = new IceSurfaceRules.Motion();
        int climbGrace;
        boolean slidePending;
        int slideTicks;
        int slideDirection;
        float slideSpeed;
        CustomMapRuntime.GapJump jump;
        int jumpTick;
        int jumpDuration;

        boolean airborne;
        float airVelocityX;
        float airVelocityLayer;
        boolean swimming;
        String platformId;
        long lastPlatformCarryTick = Long.MIN_VALUE;
        float attachedSupportLayer = Float.NaN;
        boolean platformOriginAir;
        float platformOriginLayer = Float.NaN;
        int landingStunTicks;
        int landingSlowTicks;
        float landingMoveMultiplier = 1f;
        String boardingPlatformId;
        String dockCrossPlatformId;
    }

    private static final class DockTarget {
        final float entryWorldX;
        final float supportLayer;
        final String platformId;

        DockTarget(float entryWorldX, float supportLayer, String platformId) {
            this.entryWorldX = entryWorldX;
            this.supportLayer = supportLayer;
            this.platformId = platformId;
        }
    }

    static boolean isDilationSkipTick(EEnemy enemy) {
        try {
            return enemy.basis.time % 10 < 3;
        } catch (Throwable ignored) {
            return false;
        }
    }

    void tick(EEnemy enemy, EUnit player, AdventureArchitect architect,
              AdventureSpawnFx motionFx) {
        if (enemy == null || player == null) return;
        if (!AdventureController.canDrive(player))
            AdventureController.clearRegisteredIce(player);
        TerrainMotion motion = motion(enemy);
        if (motion.platformId != null)
            CustomMapRuntime.notePlatformRider(motion.platformId, enemy);

        long hp = AdventureController.readHealth(enemy);
        boolean zombie = AdventureController.isZombieReviveActive(enemy);
        boolean corpse = AdventureController.hasCorpse(enemy);
        int kb = AdventureController.readKbTime(enemy);
        boolean dead = AdventureController.readDead(enemy);
        if (hp <= 0L) {

            if (!dead && !zombie && !corpse && kb == 0) {
                AdventureCombat.ensureLethalTransition(enemy);
            }
            blockedTicks.remove(enemy);
            attackTicks.remove(enemy);
            attackStallTicks.remove(enemy);
            finishBoarding(enemy, motion);
            motion.ice.clear();
            AdventureController.unregisterIceMotion(enemy);
            terrainMotion.remove(enemy);
            return;
        }
        if (dead || zombie || corpse || kb != 0) {

            motion.ice.clear();

            if (kb != 0 && motion.platformId != null) {
                detachPlatform(enemy, motion, false, 0, 0f);
            }

            if (hp > 0L) {
                Integer n = blockedTicks.get(enemy);
                int t = (n == null ? 0 : n) + 1;
                if (t == STUCK_LOG) {
                    manualcontrol.Logger.log("Adventure: enemy blocked " + STUCK_LOG
                            + "t hp=" + hp + " zombie=" + zombie + " corpse=" + corpse
                            + " kb=" + kb + " pos=" + Math.round(EntityAccess.getPos(enemy))
                            + " player=" + Math.round(player.pos));
                }
                if (t >= STUCK_CLEAR && !zombie) {
                    if (corpse) AdventureController.clearCorpse(enemy);
                    AdventureController.interruptAction(enemy, 8);
                    blockedTicks.remove(enemy);
                    manualcontrol.Logger.log("Adventure: force-unstuck enemy state kb="
                            + kb + " corpse=" + corpse);
                    return;
                }
                blockedTicks.put(enemy, t);
            } else {
                blockedTicks.remove(enemy);
            }
            return;
        }
        blockedTicks.remove(enemy);

        if (architect != null && architect.tickEnemyLaunch(enemy)) {

            finishBoarding(enemy, motion);
            clearAir(motion);
            motion.jump = null;
            motion.jumpTick = motion.jumpDuration = 0;
            motion.ice.clear();
            stopSlide(motion);
            return;
        }

        carryPlatform(enemy, motion);
        reconcileGroundContact(enemy, motion);

        if (motion.landingStunTicks > 0) {
            motion.landingStunTicks--;
            AdventureController.interruptAttackOnly(enemy);
            AdventureController.setWalking(enemy, false);
            AdventureController.syncLastPosition(enemy);
            AdventureController.setAnim(enemy, "WALK");
            return;
        }
        if (motion.landingSlowTicks > 0) {
            motion.landingSlowTicks--;
            if (motion.landingSlowTicks == 0) motion.landingMoveMultiplier = 1f;
        }

        if (motion.airborne) {
            if (tickPlatformAir(enemy, motion, motionFx)) return;
        }

        if (motion.jump != null) {
            if (tickGapJump(enemy, motion, motionFx)) return;
        }
        if (motion.climbGrace > 0) motion.climbGrace--;
        if (motion.slidePending || motion.slideTicks > 0) {
            if (tickSlopeSlide(enemy, motion, motionFx)) return;
        }

        AdventureController.clearMotionState(enemy);
        AdventureController.tickWaitTime(enemy);

        boolean dilated = AdventureRuntime.cores().hasUnique("P4");
        boolean dilationSkip = dilated && isDilationSkipTick(enemy);
        float speedScale = dilated ? 0.7f : 1f;

        float ex = EntityAccess.getPos(enemy);

        AdventureArchitect.Construct construct = architect == null ? null : architect.targetFor(enemy);
        boolean constructTarget = construct != null;
        float tauntX = constructTarget ? Float.NaN : AdventureBridge.tauntXFor(enemy);
        boolean taunted = tauntX == tauntX;
        float px = constructTarget ? construct.x : taunted ? tauntX : player.pos;

        if (tickIceMotion(enemy, player, motion, px,
                speedScale, motionFx)) return;

        if (AdventureController.isAttackActive(enemy)) {
            Integer n = attackTicks.get(enemy);
            int t = (n == null ? 0 : n) + 1;
            int stall = 0;
            if (!dilationSkip) {
                int before = AdventureController.readAttackTime(enemy);
                boolean updated = AdventureController.updateAttack(enemy);
                int after = AdventureController.readAttackTime(enemy);
                Integer oldStall = attackStallTicks.get(enemy);
                stall = updated && (after < before || after <= 0)
                        ? 0 : (oldStall == null ? 0 : oldStall) + 1;
                if (stall == 0) attackStallTicks.remove(enemy);
                else attackStallTicks.put(enemy, stall);
            }
            if (stall >= ATTACK_STALL_CLEAR || t >= ATTACK_STUCK_CLEAR) {
                AdventureController.interruptAction(enemy, 8);
                attackTicks.remove(enemy);
                attackStallTicks.remove(enemy);
                manualcontrol.Logger.log("Adventure: force-cancelled stuck enemy attack"
                        + " atkTime=" + AdventureController.readAttackTime(enemy)
                        + " stalled=" + stall + " total=" + t);
                return;
            }
            attackTicks.put(enemy, t);
            AdventureBridge.setFlipped(enemy, px < ex);
            AdventureController.setWalking(enemy, false);
            AdventureController.syncLastPosition(enemy);
            return;
        }
        attackTicks.remove(enemy);
        attackStallTicks.remove(enemy);

        float dx = px - ex;
        float range = attackRange(enemy);

        boolean engage = constructTarget
                ? architect.canEngageConstruct(enemy, range)
                : taunted ? Math.abs(dx) <= range : canEngage(enemy, player, range, dx);
        if (engage) {

            AdventureBridge.setFlipped(enemy, px < ex);
            AdventureController.setWalking(enemy, false);
            if (AdventureController.readWaitTime(enemy) > 0) {
                AdventureController.setAnim(enemy, "IDLE");
            } else if (!AdventureController.startAttack(enemy)) {
                AdventureController.setAnim(enemy, "ATK");
            }
            AdventureController.syncLastPosition(enemy);
            return;
        }

        float speed = AdventureController.readSpeed(enemy) * 0.5f * speedScale
                * manualcontrol.custommap.CustomMapRuntime.worldScale()
                * motion.landingMoveMultiplier;
        float step = Math.signum(dx) * Math.min(speed, Math.abs(dx));
        boolean moving = Math.abs(step) > 0.5f;
        if (moving) {
            AdventureBridge.setFlipped(enemy, step < 0f);
            float next = ex + step;
            if (architect != null) next = architect.clampEnemyMove(enemy, ex, next);
            next = clampAgainstPlayer(enemy, player, ex, next);
            CustomMapRuntime.PlatformBoarding boarding = !motion.swimming
                    ? CustomMapRuntime.findPriorityPlatformBoarding(ex,
                    EntityAccess.getLayer(enemy), motion.platformId,
                    step > 0f ? 1 : -1) : null;
            if (boarding != null && startPlatformBoarding(enemy, motion,
                    boarding, motionFx)) {
                return;
            } else if (motion.swimming) {
                int traversed = moveWhileSwimming(enemy, motion, ex, next,
                        step > 0f ? 1 : -1, speed, motionFx);
                if (traversed < 0) {
                    lockAirbornePose(enemy);
                    return;
                }
                moving = traversed > 0;
            } else if (motion.platformId != null) {
                int traversed = moveOnPlatform(enemy, motion, ex, next,
                        step > 0f ? 1 : -1, speed, motionFx);
                if (traversed < 0) {
                    lockAirbornePose(enemy);
                    return;
                }
                moving = traversed > 0;
            } else if (!CustomMapRuntime.canEnemyWalk(ex, next)) {
                CustomMapRuntime.GapJump jump =
                        CustomMapRuntime.findGapJump(ex, step > 0f ? 1 : -1);
                boolean targetAcrossGap = jump != null
                        && (step > 0f ? px >= jump.landingWorldX : px <= jump.landingWorldX);
                if (targetAcrossGap) {
                    startGapJump(enemy, motion, jump, speed, motionFx);
                    return;
                }
                moving = false;
                AdventureBridge.setFlipped(enemy, step > 0f);
            } else {
                EntityAccess.setPos(enemy, next);
                if (CustomMapRuntime.isUphillMovement(ex, next)) motion.climbGrace = 6;
            }
        } else {
            AdventureBridge.setFlipped(enemy, px < ex);
        }
        if (motion.swimming) {
            float water = CustomMapRuntime.waterSurfaceLayerAt(EntityAccess.getPos(enemy));
            if (!Float.isNaN(water)) EntityAccess.setLayer(enemy, Math.round(water));
        } else if (motion.platformId != null && !Float.isNaN(motion.attachedSupportLayer)) {
            EntityAccess.setLayer(enemy, Math.round(motion.attachedSupportLayer));
        } else {
            EntityAccess.setLayer(enemy, Math.round(
                    CustomMapRuntime.surfaceLayerAt(EntityAccess.getPos(enemy),
                            EntityAccess.getLayer(enemy))));
        }
        AdventureController.setWalking(enemy, moving);
        AdventureController.syncLastPosition(enemy);
        AdventureController.setAnim(enemy, moving ? "WALK" : "IDLE");
    }

    private boolean tickIceMotion(
            EEnemy enemy, EUnit player, TerrainMotion motion,
            float targetX, float speedScale,
            AdventureSpawnFx motionFx) {
        CustomMapDocument.ModeVariant terrain = activeAdventureVariant();
        if (terrain == null || enemy == null || motion.airborne
                || motion.jump != null || motion.swimming) {
            motion.ice.clear();
            return false;
        }
        float from = EntityAccess.getPos(enemy);
        CustomMapRuntime.TerrainSample support = CustomMapRuntime.sampleTerrain(
                from, EntityAccess.getLayer(enemy), false);
        boolean attacking = AdventureController.isAttackActive(enemy);
        boolean canChooseAttack = !motion.ice.active()
                || motion.ice.canVoluntarilyStop();
        boolean targetInRange = Math.abs(targetX - from) <= attackRange(enemy);
        int direction = attacking || (canChooseAttack && targetInRange)
                ? 0 : targetX > from ? 1 : targetX < from ? -1 : 0;
        float nativeWorld = direction * AdventureController.readSpeed(enemy) * .5f
                * speedScale * CustomMapRuntime.worldScale()
                * motion.landingMoveMultiplier;
        CustomMapRuntime.SlopeSample slope = CustomMapRuntime.sampleSlope(from);
        IceSurfaceRules.Step step = motion.ice.tick(support.isIce(),
                nativeWorld / Math.max(1f, terrain.worldUnitsPerTile()),
                direction != 0 ? direction : targetX >= from ? 1 : -1,
                slope.downhillDirection);
        if (!step.forced) return false;

        stopSlide(motion);
        if (step.lockAttack) AdventureController.interruptAttackOnly(enemy);
        direction = step.deltaTiles > 0f ? 1 : -1;
        AdventureBridge.setFlipped(enemy, direction < 0);
        float proposed = from + step.deltaTiles * terrain.worldUnitsPerTile();
        proposed = clampIceEnemyAgainstPlayer(
                enemy, player, motion, from, proposed, motionFx);
        CustomMapRuntime.AirborneContact liquid =
                CustomMapRuntime.sweepAirborne(from,
                        EntityAccess.getLayer(enemy), proposed,
                        EntityAccess.getLayer(enemy));
        if (liquid.kind() == CustomMapRuntime.TerrainKind.WATER) {
            EntityAccess.setPos(enemy, liquid.worldX);
            EntityAccess.setLayer(enemy, Math.round(liquid.actorLayer));
            enterWater(motion);
            if (motionFx != null)
                motionFx.slideDust(liquid.worldX,
                        Math.round(liquid.actorLayer), direction, true);
            AdventureController.setWalking(enemy, true);
            AdventureController.syncLastPosition(enemy);
            AdventureController.setAnim(enemy, "WALK");
            return true;
        }
        CustomMapRuntime.TerrainSample destination = CustomMapRuntime.sampleTerrain(
                proposed, EntityAccess.getLayer(enemy), false);
        boolean retainedSupport = support.kind == CustomMapRuntime.TerrainKind.FLOATING
                ? destination.kind == CustomMapRuntime.TerrainKind.FLOATING
                && support.platformId != null
                && support.platformId.equals(destination.platformId)
                : destination.kind == CustomMapRuntime.TerrainKind.MAIN;
        EntityAccess.setPos(enemy, proposed);
        if (retainedSupport) {
            EntityAccess.setLayer(enemy, Math.round(destination.supportLayer));
        } else {
            motion.ice.clear();
            if (motion.platformId != null)
                detachPlatform(enemy, motion, false, direction,
                        Math.abs(step.deltaTiles) * terrain.worldUnitsPerTile());
            else {
                motion.airborne = true;
                motion.airVelocityX = 0f;
                motion.airVelocityLayer = 0f;
                motion.attachedSupportLayer = Float.NaN;
            }
        }
        IceSurfaceRules.Phase phase = step.phase;
        if (!retainedSupport) {
            motion.ice.clear();
        } else if (phase == IceSurfaceRules.Phase.GLIDE
                && !destination.isIce()) {
            if (Math.abs(step.deltaTiles) >= IceSurfaceRules.TUMBLE_THRESHOLD) {
                motion.ice.beginTumble();
                phase = IceSurfaceRules.Phase.TUMBLE;
                AdventureController.interruptAttackOnly(enemy);
            } else {
                motion.ice.clear();
            }
        }
        if (motionFx != null)
            motionFx.slideDust(proposed, EntityAccess.getLayer(enemy),
                    direction, true);
        AdventureController.setWalking(enemy, true);
        AdventureController.syncLastPosition(enemy);
        AdventureController.setAnim(enemy,
                phase == IceSurfaceRules.Phase.TUMBLE ? "HIT" : "WALK");
        return true;
    }

    private float clampIceEnemyAgainstPlayer(
            EEnemy enemy, EUnit player, TerrainMotion motion,
            float from, float proposed, AdventureSpawnFx motionFx) {
        if (enemy == null || player == null) return proposed;
        CustomMapDocument.ModeVariant terrain = activeAdventureVariant();
        float tolerance = terrain == null ? 18f
                : terrain.layerUnitsPerTile() * .80f;
        if (Math.abs(enemy.currentLayer - player.currentLayer) > tolerance)
            return proposed;
        boolean crossed = proposed > from
                ? player.pos >= from && player.pos <= proposed
                : player.pos <= from && player.pos >= proposed;
        if (!crossed) return proposed;
        boolean impacted = false;
        if (AdventureController.claimIceImpact(enemy, player)) {
            IceSurfaceRules.Motion playerIce =
                    AdventureController.registeredIceMotion(player);
            float enemyVelocity = motion.ice.velocityTilesPerTick();
            float playerVelocity = playerIce == null
                    ? 0f : playerIce.velocityTilesPerTick();
            IceSurfaceRules.Impact enemyImpact =
                    motion.ice.hitEnemy(playerVelocity);
            IceSurfaceRules.Impact playerImpact = playerIce == null ? null
                    : playerIce.hitEnemy(enemyVelocity);
            if (enemyImpact.active) {
                AdventureCombat.queueEffectDamage(player, IceSurfaceRules.damage(
                        enemy.maxH, enemyImpact.damageRatio));
                impacted = true;
            }
            if (playerImpact != null && playerImpact.active) {
                AdventureCombat.queueEffectDamage(enemy, IceSurfaceRules.damage(
                        player.maxH, playerImpact.damageRatio));
                impacted = true;
            }
        }
        if (impacted) {
            if (motionFx != null)
                motionFx.spawnColored(player.pos, player.currentLayer,
                        120, 225, 255, 235, 250, 255);
        }
        float separation = terrain == null ? 3f
                : Math.max(1f, terrain.worldUnitsPerTile() * .012f);
        return proposed > from ? player.pos - separation : player.pos + separation;
    }

    private static float clampAgainstPlayer(EEnemy enemy, EUnit player,
                                            float from, float proposed) {
        if (enemy == null || player == null) return proposed;
        CustomMapDocument.ModeVariant terrain = activeAdventureVariant();
        float layers = terrain == null ? 18f
                : terrain.layerUnitsPerTile() * .80f;
        if (Math.abs(enemy.currentLayer - player.currentLayer) > layers)
            return proposed;
        float separation = terrain == null ? 3f
                : Math.max(1f, terrain.worldUnitsPerTile() * .012f);
        if (proposed > from && player.pos >= from && player.pos <= proposed)
            return player.pos - separation;
        if (proposed < from && player.pos <= from && player.pos >= proposed)
            return player.pos + separation;
        return proposed;
    }

    void onDamagedWhileMoving(EEnemy enemy) {
        if (enemy == null) return;
        TerrainMotion motion = terrainMotion.get(enemy);
        if (motion == null || motion.jump != null || motion.airborne
                || motion.swimming || motion.platformId != null || motion.slideTicks > 0
                || motion.ice.active()
                || motion.climbGrace <= 0) return;
        CustomMapRuntime.SlopeSample slope = CustomMapRuntime.sampleSlope(enemy.pos);
        if (!slope.isSlope()) return;
        motion.slideDirection = slope.downhillDirection;
        motion.slidePending = motion.slideDirection != 0;
    }

    void noteIceOccupancy(EEnemy enemy) {
        TerrainMotion motion = enemy == null ? null : terrainMotion.get(enemy);
        if (motion == null) return;
        CustomMapRuntime.noteIceOccupant(enemy, EntityAccess.getPos(enemy),
                EntityAccess.getLayer(enemy), !motion.airborne
                        && motion.jump == null && !motion.swimming
                        && motion.boardingPlatformId == null,
                motion.platformId);
    }

    private TerrainMotion motion(EEnemy enemy) {
        TerrainMotion value = terrainMotion.get(enemy);
        if (value == null) {
            value = new TerrainMotion();
            terrainMotion.put(enemy, value);
        }
        AdventureController.registerIceMotion(enemy, value.ice);
        return value;
    }

    private void carryPlatform(EEnemy enemy, TerrainMotion motion) {
        if (enemy == null || motion.airborne || motion.jump != null
                || motion.swimming || motion.platformId == null) return;
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        CustomMapDocument.SecondaryPlatform platform = platform(variant, motion.platformId);
        if (variant == null || platform == null) {
            detachPlatform(enemy, motion, false, 0, 0f);
            return;
        }
        MovingPlatformEngine.Pose pose = platform.isPatrolling()
                ? CustomMapRuntime.platformPose(platform.id) : null;
        if (platform.isPatrolling() && pose == null) {
            detachPlatform(enemy, motion, false, 0, 0f);
            return;
        }
        CustomMapRuntime.TerrainSample current = CustomMapRuntime.sampleTerrain(
                EntityAccess.getPos(enemy), EntityAccess.getLayer(enemy), false);
        if (current.kind != CustomMapRuntime.TerrainKind.FLOATING
                || !platform.id.equals(current.platformId)) {
            detachPlatform(enemy, motion, false, 0, 0f);
            return;
        }
        if (pose != null) {
            long tick = CustomMapRuntime.platformTick();
            if (motion.lastPlatformCarryTick != tick) {
                EntityAccess.setPos(enemy, EntityAccess.getPos(enemy)
                        + pose.deltaWorldX(variant));
                motion.lastPlatformCarryTick = tick;
            }
            motion.attachedSupportLayer = pose.supportLayer(variant);
        } else {
            motion.attachedSupportLayer = platform.supportLayer;
        }
        EntityAccess.setLayer(enemy, Math.round(motion.attachedSupportLayer));
    }

    private void reconcileGroundContact(EEnemy enemy, TerrainMotion motion) {
        if (enemy == null || motion.airborne || motion.jump != null || motion.swimming)
            return;
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        if (variant == null) return;
        float layer = EntityAccess.getLayer(enemy);
        CustomMapRuntime.TerrainSample sample = CustomMapRuntime.sampleTerrain(
                EntityAccess.getPos(enemy), layer, false);
        float tolerance = variant.layerUnitsPerTile() * .28f;
        if (sample.kind == CustomMapRuntime.TerrainKind.FLOATING
                && sample.platformId != null
                && Math.abs(layer - sample.supportLayer) <= tolerance) {
            motion.platformId = sample.platformId;
            motion.lastPlatformCarryTick = CustomMapRuntime.platformTick();
            motion.attachedSupportLayer = sample.supportLayer;
            EntityAccess.setLayer(enemy, Math.round(sample.supportLayer));
            return;
        }
        if (motion.platformId != null) return;

        float water = CustomMapRuntime.waterSurfaceLayerAt(EntityAccess.getPos(enemy));
        if (!Float.isNaN(water) && layer >= water - tolerance) {
            enterWater(motion);
            EntityAccess.setLayer(enemy, Math.round(water));
        } else if (!sample.hasSupport()) {
            motion.ice.clear();
            motion.airborne = true;
            motion.airVelocityX = 0f;
            motion.airVelocityLayer = 0f;
            motion.attachedSupportLayer = Float.NaN;
        }
    }

    private int moveOnPlatform(EEnemy enemy, TerrainMotion motion, float from,
                               float requestedNext, int direction, float speed,
                               AdventureSpawnFx motionFx) {
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        CustomMapDocument.SecondaryPlatform platform = platform(variant, motion.platformId);
        if (variant == null || platform == null) {
            detachPlatform(enemy, motion, false, direction, speed);
            return -1;
        }
        MovingPlatformEngine.Pose pose = platform.isPatrolling()
                ? CustomMapRuntime.platformPose(platform.id) : null;
        float centerTile = pose == null ? platform.originCenterTileX() : pose.centerTileX;
        float supportTile = pose == null
                ? -platform.supportLayer / Math.max(1f, variant.layerUnitsPerTile())
                : pose.supportTileY;
        float half = platform.widthTiles() * .5f;
        float left = (centerTile - half) * variant.worldUnitsPerTile();
        float right = (centerTile + half) * variant.worldUnitsPerTile();
        float inset = Math.max(0.5f, variant.worldUnitsPerTile() * .012f);
        if (requestedNext >= left + inset && requestedNext <= right - inset) {
            EntityAccess.setPos(enemy, requestedNext);
            return Math.abs(requestedNext - from) > .001f ? 1 : 0;
        }

        DockTarget dock = (pose == null || MovingPlatformEngine.isBoardingStop(pose))
                ? findDockTarget(variant, platform, centerTile, supportTile, direction)
                : null;
        if (dock != null) {
            if (platform.isPatrolling()
                    && motion.dockCrossPlatformId == null) {
                if (!CustomMapRuntime.beginPlatformBoarding(
                        platform.id, enemy, 12)) return 0;
                motion.dockCrossPlatformId = platform.id;
            }
            EntityAccess.setPos(enemy, requestedNext);
            boolean crossed = direction > 0
                    ? requestedNext >= dock.entryWorldX
                    : requestedNext <= dock.entryWorldX;
            if (!crossed) {
                EntityAccess.setLayer(enemy, Math.round(
                        pose == null ? platform.supportLayer
                                : pose.supportLayer(variant)));
                return 1;
            }
            EntityAccess.setLayer(enemy, Math.round(dock.supportLayer));
            motion.platformId = dock.platformId;
            motion.lastPlatformCarryTick = dock.platformId == null
                    ? Long.MIN_VALUE : CustomMapRuntime.platformTick();
            motion.attachedSupportLayer = dock.platformId == null
                    ? Float.NaN : dock.supportLayer;
            motion.platformOriginAir = false;
            motion.platformOriginLayer = Float.NaN;
            finishDockCross(enemy, motion);
            return 1;
        }

        if (platform.isPatrolling()
                && willEverDock(variant, platform, direction)) return 0;

        detachPlatform(enemy, motion, true, direction, speed);
        if (motionFx != null)
            motionFx.slideDust(from, EntityAccess.getLayer(enemy), direction, true);
        return -1;
    }

    private int moveWhileSwimming(EEnemy enemy, TerrainMotion motion, float from,
                                  float requestedNext, int direction, float speed,
                                  AdventureSpawnFx motionFx) {
        float next = from + (requestedNext - from) * SWIM_SPEED_MULTIPLIER;
        if (CustomMapRuntime.isWater(next)) {
            EntityAccess.setPos(enemy, next);
            float water = CustomMapRuntime.waterSurfaceLayerAt(next);
            if (!Float.isNaN(water)) EntityAccess.setLayer(enemy, Math.round(water));
            return Math.abs(next - from) > .001f ? 1 : 0;
        }

        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        float targetLayer = variant == null ? Float.NaN : variant.surfaceLayerAt(next);
        startWaterExit(enemy, motion, direction, speed, targetLayer);
        if (motionFx != null)
            motionFx.slideDust(from, EntityAccess.getLayer(enemy), direction, true);
        return -1;
    }

    private void startWaterExit(EEnemy enemy, TerrainMotion motion, int direction,
                                float speed, float targetLayer) {
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        if (variant == null) return;
        motion.ice.clear();
        float layerUnits = Math.max(1f, variant.layerUnitsPerTile());
        float current = EntityAccess.getLayer(enemy);
        float neededLift = Float.isNaN(targetLayer) ? layerUnits * .75f
                : Math.max(0f, current - targetLayer) + layerUnits * .55f;
        float gravity = AIR_GRAVITY_TILES * layerUnits;
        motion.swimming = false;
        motion.airborne = true;
        motion.airVelocityX = direction * Math.max(speed * SWIM_SPEED_MULTIPLIER,
                variant.worldUnitsPerTile() / 18f);
        motion.airVelocityLayer = -Math.max(EDGE_HOP_LIFT_TILES * layerUnits,
                (float) Math.sqrt(2f * gravity * neededLift));
        motion.platformOriginAir = false;
        motion.platformOriginLayer = Float.NaN;
        AdventureController.interruptAttackOnly(enemy);
    }

    private void detachPlatform(EEnemy enemy, TerrainMotion motion, boolean hop,
                                int direction, float walkSpeed) {
        motion.ice.clear();
        finishDockCross(enemy, motion);
        if (motion.platformId == null) return;
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        CustomMapDocument.SecondaryPlatform platform = platform(variant, motion.platformId);
        MovingPlatformEngine.Pose pose = platform != null && platform.isPatrolling()
                ? CustomMapRuntime.platformPose(platform.id) : null;
        float support = !Float.isNaN(motion.attachedSupportLayer)
                ? motion.attachedSupportLayer : EntityAccess.getLayer(enemy);
        motion.platformOriginAir = true;
        motion.platformOriginLayer = support;
        motion.airborne = true;
        motion.swimming = false;
        motion.airVelocityX = pose == null || variant == null ? 0f
                : pose.velocityWorldPerSecondX(variant)
                / MovingPlatformEngine.TICKS_PER_SECOND;
        motion.airVelocityLayer = pose == null || variant == null ? 0f
                : pose.velocityLayerPerSecond(variant)
                / MovingPlatformEngine.TICKS_PER_SECOND;
        if (hop && variant != null) {
            motion.airVelocityX += direction * Math.max(walkSpeed * .70f,
                    variant.worldUnitsPerTile() / 30f);
            motion.airVelocityLayer -= EDGE_HOP_LIFT_TILES
                    * variant.layerUnitsPerTile();
        }
        motion.platformId = null;
        motion.lastPlatformCarryTick = Long.MIN_VALUE;
        motion.attachedSupportLayer = Float.NaN;
        AdventureController.interruptAttackOnly(enemy);
    }

    private boolean tickPlatformAir(EEnemy enemy, TerrainMotion motion,
                                    AdventureSpawnFx motionFx) {
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        if (variant == null) {
            motion.airborne = false;
            return false;
        }
        float fromX = EntityAccess.getPos(enemy);
        float fromLayer = EntityAccess.getLayer(enemy);
        motion.airVelocityLayer += AIR_GRAVITY_TILES * variant.layerUnitsPerTile();
        float toX = fromX + motion.airVelocityX;
        float toLayer = fromLayer + motion.airVelocityLayer;

        CustomMapRuntime.AirborneContact contact =
                CustomMapRuntime.sweepAirborne(
                        fromX, fromLayer, toX, toLayer);
        if (contact.hit()) {
            if (contact.kind() == CustomMapRuntime.TerrainKind.MAIN
                    || contact.kind() == CustomMapRuntime.TerrainKind.FLOATING) {
                EntityAccess.setPos(enemy, contact.worldX);
                finishLanding(enemy, motion, contact.terrain, motionFx);
                lockAirbornePose(enemy);
                return true;
            }
            if (contact.kind() == CustomMapRuntime.TerrainKind.WATER) {
                EntityAccess.setPos(enemy, contact.worldX);
                EntityAccess.setLayer(enemy, Math.round(contact.actorLayer));
                enterWater(motion);
                if (motionFx != null)
                    motionFx.slideDust(contact.worldX,
                            Math.round(contact.actorLayer),
                            motion.airVelocityX < 0f ? -1 : 1, true);
                lockAirbornePose(enemy);
                return true;
            }
        }

        EntityAccess.setPos(enemy, toX);
        EntityAccess.setLayer(enemy, Math.round(toLayer));
        if (CustomMapRuntime.belowVoidKillPlane(toLayer)) {
            AdventureCombat.queueEffectDamage(enemy,
                    Math.max(1L, enemy.maxH) + 1L);
            finishBoarding(enemy, motion);
            clearAir(motion);
        }
        lockAirbornePose(enemy);
        return true;
    }

    private void finishLanding(EEnemy enemy, TerrainMotion motion,
                               CustomMapRuntime.TerrainSample contact,
                               AdventureSpawnFx motionFx) {
        int landingDirection = motion.airVelocityX < 0f ? -1 : 1;
        motion.airborne = false;
        motion.swimming = false;
        motion.airVelocityX = motion.airVelocityLayer = 0f;
        EntityAccess.setLayer(enemy, Math.round(contact.supportLayer));
        if (contact.kind == CustomMapRuntime.TerrainKind.FLOATING
                && contact.platformId != null) {
            motion.platformId = contact.platformId;
            motion.lastPlatformCarryTick = CustomMapRuntime.platformTick();
            motion.attachedSupportLayer = contact.supportLayer;
        } else {
            motion.platformId = null;
            motion.lastPlatformCarryTick = Long.MIN_VALUE;
            motion.attachedSupportLayer = Float.NaN;
        }
        applyPlatformLanding(enemy, motion, contact.supportLayer);
        if (motionFx != null)
            motionFx.slideDust(EntityAccess.getPos(enemy),
                    Math.round(contact.supportLayer),
                    landingDirection, true);
    }

    private void applyPlatformLanding(EEnemy enemy, TerrainMotion motion,
                                      float landingLayer) {
        if (!motion.platformOriginAir || Float.isNaN(motion.platformOriginLayer)) return;
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        if (variant == null) return;
        float dropTiles = Math.max(0f, (landingLayer - motion.platformOriginLayer)
                / Math.max(1f, variant.layerUnitsPerTile()));
        CustomMapLandingImpact.Result result = CustomMapLandingImpact.resolve(
                enemy.basis, enemy, dropTiles);
        motion.landingStunTicks = result.penalty.effectiveStunTicks(
                nativeImmunity(enemy, "IMUSTOP"));
        motion.landingSlowTicks = result.penalty.effectiveSlowTicks(
                nativeImmunity(enemy, "IMUSLOW"));
        motion.landingMoveMultiplier = motion.landingSlowTicks > 0
                ? result.penalty.movementMultiplier : 1f;
        motion.platformOriginAir = false;
        motion.platformOriginLayer = Float.NaN;
    }

    private static boolean nativeImmunity(EEnemy enemy, String fieldName) {
        try {
            Object data = BCUFields.get(enemy, "data");
            Object proc = BCUFields.invoke(data, "getProc");
            Object immunity = BCUFields.get(proc, fieldName);
            Object exists = BCUFields.invoke(immunity, "exists");
            return exists instanceof Boolean && ((Boolean) exists).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void enterWater(TerrainMotion motion) {
        motion.ice.clear();
        motion.airborne = false;
        motion.swimming = true;
        motion.platformId = null;
        motion.lastPlatformCarryTick = Long.MIN_VALUE;
        motion.attachedSupportLayer = Float.NaN;
        motion.platformOriginAir = false;
        motion.platformOriginLayer = Float.NaN;
        motion.airVelocityX = motion.airVelocityLayer = 0f;
    }

    private static void clearAir(TerrainMotion motion) {
        motion.ice.clear();
        motion.airborne = false;
        motion.swimming = false;
        motion.platformId = null;
        motion.lastPlatformCarryTick = Long.MIN_VALUE;
        motion.attachedSupportLayer = Float.NaN;
        motion.platformOriginAir = false;
        motion.platformOriginLayer = Float.NaN;
        motion.airVelocityX = motion.airVelocityLayer = 0f;
        motion.boardingPlatformId = null;
        motion.dockCrossPlatformId = null;
    }

    private static void finishBoarding(EEnemy enemy, TerrainMotion motion) {
        if (motion == null || motion.boardingPlatformId == null) return;
        CustomMapRuntime.finishPlatformBoarding(
                motion.boardingPlatformId, enemy);
        motion.boardingPlatformId = null;
    }

    private static void finishDockCross(EEnemy enemy, TerrainMotion motion) {
        if (motion == null || motion.dockCrossPlatformId == null) return;
        CustomMapRuntime.finishPlatformBoarding(
                motion.dockCrossPlatformId, enemy);
        motion.dockCrossPlatformId = null;
    }

    private static void lockAirbornePose(EEnemy enemy) {
        AdventureController.interruptAttackOnly(enemy);
        AdventureController.setWalking(enemy, true);
        AdventureController.syncLastPosition(enemy);

        AdventureController.setAnim(enemy, "WALK");
    }

    private static CustomMapDocument.ModeVariant activeAdventureVariant() {
        CustomMapDocument document = CustomMapRuntime.activeDocument();
        return document == null ? null
                : document.variant(CustomMapDocument.MapMode.ADVENTURE);
    }

    private static CustomMapDocument.SecondaryPlatform platform(
            CustomMapDocument.ModeVariant variant, String id) {
        return variant == null || id == null ? null : variant.secondaryPlatform(id);
    }

    private static boolean willEverDock(CustomMapDocument.ModeVariant variant,
                                        CustomMapDocument.SecondaryPlatform platform,
                                        int direction) {
        if (variant == null || platform == null || platform.patrol == null) return false;
        CustomMapDocument.PlatformPatrol patrol = platform.patrol;
        return findDockTarget(variant, platform, patrol.ax, patrol.ay, direction) != null
                || findDockTarget(variant, platform, patrol.bx, patrol.by, direction) != null;
    }

    private static DockTarget findDockTarget(CustomMapDocument.ModeVariant variant,
                                             CustomMapDocument.SecondaryPlatform rider,
                                             float centerTile, float supportTile,
                                             int direction) {
        if (variant == null || rider == null || direction == 0) return null;
        float half = rider.widthTiles() * .5f;
        float edge = centerTile + (direction > 0 ? half : -half);
        float maxGap = MovingPlatformValidator.BODY_CLEARANCE_TILES + .002f;
        float tolerance = MovingPlatformValidator.DOCK_HEIGHT_TOLERANCE_TILES + .002f;
        DockTarget best = null;
        float bestGap = Float.MAX_VALUE;

        for (int x = 0; x < variant.width; x++) {
            if (variant.water != null && variant.water[x]) continue;
            if (variant.slopeDirection != null && variant.slopeDirection[x] != 0)
                continue;
            float layer = variant.walkLayerAtTile(x);
            if (Float.isNaN(layer)) continue;
            float height = -layer / Math.max(1f, variant.layerUnitsPerTile());
            if (Math.abs(height - supportTile) > tolerance) continue;
            int flatNeighbor = x + direction;
            if (flatNeighbor < 0 || flatNeighbor >= variant.width
                    || (variant.water != null && variant.water[flatNeighbor])
                    || (variant.slopeDirection != null
                    && variant.slopeDirection[flatNeighbor] != 0)
                    || Math.abs(variant.walkLayerAtTile(flatNeighbor) - layer)
                    > variant.layerUnitsPerTile() * .03f) continue;
            float gap = direction > 0 ? x - edge : edge - (x + 1f);
            if (gap < -.002f || gap > maxGap || gap >= bestGap) continue;
            float entryTile = direction > 0 ? x + .06f : x + .94f;
            best = new DockTarget(entryTile * variant.worldUnitsPerTile(), layer, null);
            bestGap = gap;
        }

        if (variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform candidate : variant.secondaryPlatforms) {
                if (candidate == null || candidate == rider || candidate.isPatrolling()) continue;
                float height = -candidate.supportLayer
                        / Math.max(1f, variant.layerUnitsPerTile());
                if (Math.abs(height - supportTile) > tolerance) continue;
                float left = candidate.originCenterTileX() - candidate.widthTiles() * .5f;
                float right = candidate.originCenterTileX() + candidate.widthTiles() * .5f;
                float gap = direction > 0 ? left - edge : edge - right;
                if (gap < -.002f || gap > maxGap || gap >= bestGap) continue;
                float entryTile = direction > 0 ? left + .06f : right - .06f;
                best = new DockTarget(entryTile * variant.worldUnitsPerTile(),
                        candidate.supportLayer, candidate.id);
                bestGap = gap;
            }
        return best;
    }

    private void startGapJump(EEnemy enemy, TerrainMotion motion,
                              CustomMapRuntime.GapJump jump, float walkSpeed,
                              AdventureSpawnFx motionFx) {
        finishBoarding(enemy, motion);
        motion.ice.clear();
        motion.jump = jump;
        motion.jumpTick = 0;
        motion.jumpDuration = jump.duration(Math.max(walkSpeed * 1.45f,
                CustomMapRuntime.worldScale() * 8f));
        motion.airborne = false;
        motion.swimming = false;
        motion.slidePending = false;
        motion.slideTicks = 0;
        AdventureController.interruptAttackOnly(enemy);
        if (motionFx != null)
            motionFx.slideDust(jump.startWorldX, Math.round(jump.startLayer),
                    jump.direction, true);
    }

    private boolean startPlatformBoarding(EEnemy enemy, TerrainMotion motion,
                                          CustomMapRuntime.PlatformBoarding boarding,
                                          AdventureSpawnFx motionFx) {
        if (boarding == null || !CustomMapRuntime.beginPlatformBoarding(
                boarding.platformId, enemy, boarding.durationTicks)) return false;
        motion.ice.clear();
        motion.boardingPlatformId = boarding.platformId;
        motion.jump = boarding.jump;
        motion.jumpTick = 0;
        motion.jumpDuration = boarding.durationTicks;
        motion.airborne = false;
        motion.swimming = false;
        motion.platformId = null;
        motion.lastPlatformCarryTick = Long.MIN_VALUE;
        motion.attachedSupportLayer = Float.NaN;
        motion.slidePending = false;
        motion.slideTicks = 0;
        AdventureBridge.setFlipped(enemy, boarding.jump.direction < 0);
        AdventureController.interruptAttackOnly(enemy);
        if (motionFx != null)
            motionFx.slideDust(boarding.jump.startWorldX,
                    Math.round(boarding.jump.startLayer),
                    boarding.jump.direction, true);
        return true;
    }

    private boolean tickGapJump(EEnemy enemy, TerrainMotion motion,
                                AdventureSpawnFx motionFx) {
        CustomMapRuntime.GapJump jump = motion.jump;
        if (jump == null) return false;
        motion.jumpTick++;
        float progress = motion.jumpTick / (float) Math.max(1, motion.jumpDuration);
        float fromX = EntityAccess.getPos(enemy);
        float fromLayer = EntityAccess.getLayer(enemy);
        float nextX = jump.worldXAt(progress);
        float nextLayer = jump.layerAt(progress);
        CustomMapRuntime.AirborneContact airborne =
                CustomMapRuntime.sweepAirborne(
                        fromX, fromLayer, nextX, nextLayer);
        CustomMapRuntime.TerrainSample landed =
                airborne.kind() == CustomMapRuntime.TerrainKind.MAIN
                || airborne.kind() == CustomMapRuntime.TerrainKind.FLOATING
                ? airborne.terrain : null;
        boolean boarding = motion.boardingPlatformId != null;
        boolean intendedLanding = !boarding || (landed != null
                && motion.boardingPlatformId.equals(landed.platformId));
        if (landed != null && landed.hasSupport() && intendedLanding) {
            EntityAccess.setPos(enemy, airborne.worldX);
            finishLanding(enemy, motion, landed, motionFx);
            finishBoarding(enemy, motion);
            motion.jump = null;
            motion.jumpTick = motion.jumpDuration = 0;
            lockAirbornePose(enemy);
            return true;
        }
        if (airborne.kind() == CustomMapRuntime.TerrainKind.WATER) {
            EntityAccess.setPos(enemy, airborne.worldX);
            EntityAccess.setLayer(enemy, Math.round(airborne.actorLayer));
            enterWater(motion);
            finishBoarding(enemy, motion);
            motion.jump = null;
            motion.jumpTick = motion.jumpDuration = 0;
            lockAirbornePose(enemy);
            return true;
        }
        EntityAccess.setPos(enemy, nextX);
        EntityAccess.setLayer(enemy, Math.round(nextLayer));
        AdventureBridge.setFlipped(enemy, jump.direction < 0);
        lockAirbornePose(enemy);
        if (motion.jumpTick >= motion.jumpDuration) {
            EntityAccess.setPos(enemy, jump.landingWorldX);
            CustomMapRuntime.TerrainSample contact = CustomMapRuntime.sampleTerrain(
                    jump.landingWorldX, jump.landingLayer, true);
            boolean correctPlatform = motion.boardingPlatformId == null
                    || motion.boardingPlatformId.equals(contact.platformId);
            if (contact.hasSupport() && correctPlatform) {
                finishLanding(enemy, motion, contact, motionFx);
            } else if (motion.boardingPlatformId != null) {
                motion.airborne = true;
                motion.airVelocityX = 0f;
                motion.airVelocityLayer = 0f;
                motion.platformId = null;
                EntityAccess.setLayer(enemy, Math.round(jump.landingLayer));
            } else {
                EntityAccess.setLayer(enemy, Math.round(jump.landingLayer));
            }
            if (motionFx != null)
                motionFx.slideDust(jump.landingWorldX, Math.round(jump.landingLayer),
                        jump.direction, true);
            finishBoarding(enemy, motion);
            motion.jump = null;
            motion.jumpTick = motion.jumpDuration = 0;
        }
        return true;
    }

    private boolean tickSlopeSlide(EEnemy enemy, TerrainMotion motion,
                                   AdventureSpawnFx motionFx) {
        if (motion.slidePending) {
            motion.slidePending = false;
            CustomMapRuntime.SlopeSample slope = CustomMapRuntime.sampleSlope(enemy.pos);
            if (!slope.isSlope()) return false;
            motion.slideDirection = slope.downhillDirection;
            float walk = AdventureController.readSpeed(enemy) * 0.5f
                    * CustomMapRuntime.worldScale() * motion.landingMoveMultiplier;
            motion.slideSpeed = Math.max(walk * 0.75f,
                    CustomMapRuntime.worldScale() * 4f);
            motion.slideTicks = 26;
            AdventureController.interruptAttackOnly(enemy);
            if (motionFx != null)
                motionFx.slideDust(enemy.pos, EntityAccess.getLayer(enemy),
                        motion.slideDirection, true);
        }
        if (motion.slideTicks <= 0 || motion.slideDirection == 0) return false;
        float from = enemy.pos;
        float next = from + motion.slideDirection * motion.slideSpeed;
        if (!CustomMapRuntime.canSlide(from, next, motion.slideDirection)) {
            stopSlide(motion);
            return false;
        }
        EntityAccess.setPos(enemy, next);
        EntityAccess.setLayer(enemy, Math.round(
                CustomMapRuntime.surfaceLayerAt(next, enemy.currentLayer)));
        motion.slideSpeed = Math.min(motion.slideSpeed + CustomMapRuntime.worldScale() * 0.3f,
                AdventureController.readSpeed(enemy) * CustomMapRuntime.worldScale()
                        * motion.landingMoveMultiplier);
        motion.slideTicks--;
        AdventureController.setWalking(enemy, false);
        AdventureController.syncLastPosition(enemy);
        AdventureController.setAnim(enemy, "IDLE");
        if (motionFx != null && motion.slideTicks % 3 == 0)
            motionFx.slideDust(next, enemy.currentLayer, motion.slideDirection, true);
        if (motion.slideTicks <= 0) stopSlide(motion);
        return true;
    }

    private static void stopSlide(TerrainMotion motion) {
        motion.slidePending = false;
        motion.slideTicks = 0;
        motion.slideDirection = 0;
        motion.slideSpeed = 0f;
    }

    private static boolean canEngage(EEnemy enemy, EUnit player, float range, float dx) {
        if (!manualcontrol.crazy.collision.PhysicalCollision.ENABLED) {
            return Math.abs(dx) <= range;
        }
        try {
            manualcontrol.crazy.collision.SpriteBounds.WorldBox eb =
                    manualcontrol.crazy.collision.SpriteBounds.of(enemy);
            manualcontrol.crazy.collision.SpriteBounds.WorldBox pb =
                    manualcontrol.crazy.collision.SpriteBounds.of(player);
            if (eb == null || pb == null) return Math.abs(dx) <= range;
            float verticalMargin =
                    manualcontrol.crazy.collision.PhysicalCollision.VERTICAL_REACH_MARGIN;
            if (pb.y1 < eb.y0 - verticalMargin || eb.y1 < pb.y0 - verticalMargin) {
                return false;
            }
            if (Math.abs(dx) <= range) return true;
            boolean facingLeft = dx < 0f;
            float lo = facingLeft ? eb.x0 - range : eb.x0;
            float hi = facingLeft ? eb.x1 : eb.x1 + range;
            return pb.x1 >= lo && pb.x0 <= hi;
        } catch (Throwable ignored) {
            return Math.abs(dx) <= range;
        }
    }

    private static int attackRange(Object enemy) {
        try {
            Object data = BCUFields.get(enemy, "data");
            Object r = BCUFields.invoke(data, "getRange");
            if (r instanceof Number) {
                int v = ((Number) r).intValue();
                if (v > 0) return Math.round(v
                        * manualcontrol.custommap.CustomMapRuntime.worldScale());
            }
        } catch (Throwable ignored) {}
        return Math.round(DEFAULT_RANGE
                * manualcontrol.custommap.CustomMapRuntime.worldScale());
    }
}
