package manualcontrol.adventure;

import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapLandingImpact;
import manualcontrol.custommap.CustomMapRuntime;
import manualcontrol.custommap.IceSurfaceRules;
import manualcontrol.custommap.MovingPlatformEngine;
import manualcontrol.custommap.PlatformFallRules;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

final class AdventureController {

    private static final float MOVE_MULTIPLIER = 1.5f;
    private static final float JUMP_VELOCITY = -5.8f;
    private static final float JUMP_GRAVITY = 0.42f;

    private static final float JUMP_RELEASE_MULTIPLIER = 0.45f;

    private static final float EDGE_MARGIN = 100f;

    static final int ATTACK_SPEED_MULT = 2;

    static final float MIN_MOVE_SPEED = 20f;

    private boolean jumping;
    private float jumpLayer;
    private float jumpVelocity;
    private boolean jumpCutApplied;
    private int groundLayer;
    private int baseGroundLayer;
    private boolean groundCaptured;

    private boolean facingRight;

    private float atkAcc;
    private int atkStepsThisTick = ATTACK_SPEED_MULT;

    private boolean airJumpUsed;

    private boolean wasAttacking;
    private boolean attackIsEcho;
    private int echoCountdown;

    private boolean justLanded;

    private int climbGraceTicks;
    private boolean slidePending;
    private int slideTicks;
    private int slideDirection;
    private float slideSpeed;
    private final IceSurfaceRules.Motion ice = new IceSurfaceRules.Motion();
    private String platformId;
    private long lastPlatformCarryTick = Long.MIN_VALUE;
    private boolean platformOriginAir;
    private float platformOriginLayer = Float.NaN;
    private float inheritedPlatformX;
    private float inheritedPlatformLayer;
    private float lastPlatformDropTiles;
    private int landingStunTicks;
    private int landingSlowTicks;
    private float landingMoveMultiplier = 1f;
    private String boardingPlatformId;
    private CustomMapRuntime.GapJump boardingJump;
    private int boardingTick;
    private int boardingDuration;

    private static final java.util.WeakHashMap<Object, IceSurfaceRules.Motion>
            ACTIVE_ICE = new java.util.WeakHashMap<Object, IceSurfaceRules.Motion>();
    private static final java.util.WeakHashMap<Object, IceImpactStamp>
            ICE_IMPACTS = new java.util.WeakHashMap<Object, IceImpactStamp>();

    private static final class IceImpactStamp {
        final java.lang.ref.WeakReference<Object> other;
        final long tick;

        IceImpactStamp(Object other, long tick) {
            this.other = new java.lang.ref.WeakReference<Object>(other);
            this.tick = tick;
        }
    }

    boolean consumeJustLanded() {
        if (!justLanded) return false;
        justLanded = false;
        return true;
    }

    float consumePlatformDropTiles() {
        float out = lastPlatformDropTiles;
        lastPlatformDropTiles = 0f;
        return out;
    }

    boolean isFacingRight() { return facingRight; }

    int atkStepsThisTick() { return Math.max(1, atkStepsThisTick); }

    boolean isAirborne() { return jumping; }

    void noteIceOccupancy(EUnit player) {
        if (player == null) return;
        CustomMapRuntime.noteIceOccupant(player, EntityAccess.getPos(player),
                EntityAccess.getLayer(player), !jumping && boardingJump == null,
                platformId);
    }

    float jumpLiftLayers() { return jumping ? Math.max(0f, groundLayer - jumpLayer) : 0f; }

    int groundLayerInt() { return groundLayer; }

    private static Object uWalk, uIdle, uAtk, uHit;
    private static java.lang.reflect.Method setAnimMethod;
    private static boolean animCacheReady;

    void onPlayerSpawned(EUnit player) {
        finishPlatformBoarding(player);
        jumping = false;
        jumpVelocity = 0f;
        jumpCutApplied = false;
        facingRight = false;
        groundCaptured = false;
        airJumpUsed = false;
        wasAttacking = false;
        attackIsEcho = false;
        echoCountdown = 0;
        justLanded = false;
        climbGraceTicks = 0;
        slidePending = false;
        slideTicks = 0;
        slideDirection = 0;
        slideSpeed = 0f;
        ice.clear();
        registerIceMotion(player, ice);
        platformId = null;
        lastPlatformCarryTick = Long.MIN_VALUE;
        platformOriginAir = false;
        platformOriginLayer = Float.NaN;
        inheritedPlatformX = 0f;
        inheritedPlatformLayer = 0f;
        lastPlatformDropTiles = 0f;
        landingStunTicks = landingSlowTicks = 0;
        landingMoveMultiplier = 1f;
        boardingJump = null;
        boardingTick = boardingDuration = 0;
        atkAcc = 0f;
        try {
            groundLayer = EntityAccess.getLayer(player);
            baseGroundLayer = groundLayer;
            groundCaptured = true;
        } catch (Throwable ignored) {}
        AdventureInput.reset();
    }

    void tick(EUnit player, float minPos, float maxPos, AdventureTeleport teleport,
              AdventureArchitect architect, AdventureAdvancedCores advanced,
              AdventureSpawnFx motionFx) {
        if (player == null) return;
        registerIceMotion(player, ice);
        if (!groundCaptured) onPlayerSpawned(player);

        if (!canDrive(player)) {
            AdventureInput.clearQueuedActions();
            ice.clear();
            detachPlatformIntoAir();
            cancelJump(player);
            setWalking(player, false);
            return;
        }

        clearMotionState(player);
        tickAttackImmunity(player);
        carryPatrolPlatform(player);
        if (landingStunTicks > 0) {
            landingStunTicks--;
            interruptAttackOnly(player);
            setWalking(player, false);
            setAnim(player, "WALK");
            syncLastPosition(player);
            return;
        }
        if (landingSlowTicks > 0) {
            landingSlowTicks--;
            if (landingSlowTicks == 0) landingMoveMultiplier = 1f;
        }
        if (climbGraceTicks > 0) climbGraceTicks--;

        boolean attackActive = isAttackActive(player);

        if (!attackActive) {
            int facing = inputFacing();
            if (facing > 0) facingRight = true;
            else if (facing < 0) facingRight = false;
        }

        boolean jumpPressed = AdventureInput.consumeJump();
        if (ice.active() && !ice.canVoluntarilyStop()) jumpPressed = false;
        boolean acrobatCancelled = jumpPressed && attackActive && advanced != null
                && advanced.tryAcrobatCancel(player);
        if (acrobatCancelled) {
            attackActive = false;
        }
        if (architect != null) {
            int support = architect.groundLayerAt(player.pos, baseGroundLayer);
            float sampledSupport = manualcontrol.custommap.CustomMapRuntime.playerSupportLayerAt(
                    player.pos, jumping ? jumpLayer : player.currentLayer,
                    jumping && jumpVelocity >= 0f, support);
            CustomMapRuntime.TerrainSample terrainSample =
                    CustomMapRuntime.sampleTerrain(player.pos,
                            jumping ? jumpLayer : player.currentLayer,
                            jumping && jumpVelocity >= 0f);
            if (Float.isNaN(sampledSupport)) {
                support = Math.round(manualcontrol.custommap.CustomMapRuntime.voidKillLayer());
                if (!jumping) {
                    ice.clear();
                    detachPlatformIntoAir();
                    jumping = true;
                    jumpLayer = player.currentLayer;
                    jumpVelocity = inheritedPlatformLayer;
                    inheritedPlatformLayer = 0f;
                    jumpCutApplied = true;
                }
            } else support = Math.round(sampledSupport);
            if (!jumping) {
                if (terrainSample.kind == CustomMapRuntime.TerrainKind.FLOATING
                        && terrainSample.platformId != null) {
                    platformId = terrainSample.platformId;
                    lastPlatformCarryTick = CustomMapRuntime.platformTick();
                } else if (terrainSample.kind == CustomMapRuntime.TerrainKind.MAIN) {
                    platformId = null;
                    lastPlatformCarryTick = Long.MIN_VALUE;
                }

                if (support < baseGroundLayer && player.currentLayer > support + 1) {
                    support = groundLayer;
                }
                if (player.currentLayer < support - 1) {

                    ice.clear();
                    detachPlatformIntoAir();
                    jumping = true;
                    jumpLayer = player.currentLayer;
                    jumpVelocity = inheritedPlatformLayer;
                    inheritedPlatformLayer = 0f;
                    jumpCutApplied = true;
                    groundLayer = support;
                } else {
                    groundLayer = support;
                    try { EntityAccess.setLayer(player, groundLayer); } catch (Throwable ignored) {}
                }
            } else if (support >= groundLayer
                    || (jumpVelocity >= 0f && support < groundLayer && jumpLayer <= support)) {
                groundLayer = support;
            }
        }
        if (boardingJump != null) {
            tickPlatformBoarding(player);
            AdventureInput.consumeAttack();
            interruptAttackOnly(player);
            setWalking(player, true);
            syncLastPosition(player);
            setAnim(player, "WALK");
            return;
        }
        if (jumpPressed) {
            if (!jumping) {
                ice.clear();
                detachPlatformIntoAir();
                jumping = true;
                jumpLayer = EntityAccess.getLayer(player);
                jumpVelocity = JUMP_VELOCITY * manualcontrol.custommap.CustomMapRuntime.worldScale()
                        * AdventureRuntime.cores().jumpMult()
                        + inheritedPlatformLayer;
                inheritedPlatformLayer = 0f;
                jumpCutApplied = false;
                setJumpInvincibility(player, true);
            } else if (!airJumpUsed && AdventureRuntime.cores().hasUnique("P1")) {

                airJumpUsed = true;
                jumpVelocity = JUMP_VELOCITY * manualcontrol.custommap.CustomMapRuntime.worldScale()
                        * AdventureRuntime.cores().jumpMult();
                jumpCutApplied = false;
                setJumpInvincibility(player, true);
            }
        }
        float airborneFromX = EntityAccess.getPos(player);
        float airborneFromLayer = jumping ? jumpLayer
                : EntityAccess.getLayer(player);
        stepJump(player);

        if (jumping) {
            ice.clear();
            AdventureInput.consumeAttack();
            interruptAttackOnly(player);
            boolean airMoving = moveHorizontal(
                    player, minPos, maxPos, 1f, architect);
            CustomMapRuntime.AirborneContact liquid =
                    CustomMapRuntime.sweepAirborne(
                            airborneFromX, airborneFromLayer,
                            EntityAccess.getPos(player), jumpLayer);
            if (liquid.kind() == CustomMapRuntime.TerrainKind.WATER) {
                EntityAccess.setPos(player, liquid.worldX);
                jumpLayer = liquid.actorLayer;
                EntityAccess.setLayer(player, Math.round(liquid.actorLayer));
                jumping = false;
                jumpVelocity = 0f;
                jumpCutApplied = true;
            }
            setWalking(player, airMoving);
            syncLastPosition(player);
            setAnim(player, "WALK");
            return;
        }

        if (tickIceMotion(player, minPos, maxPos, architect,
                motionFx, attackActive, !acrobatCancelled, advanced)) return;

        if (!jumping && (slidePending || slideTicks > 0)) {
            if (tickSlopeSlide(player, minPos, maxPos, architect, motionFx)) {
                AdventureInput.clearQueuedActions();
                setWalking(player, false);
                syncLastPosition(player);
                setAnim(player, "IDLE");
                return;
            }
        }

        if (!attackActive && AdventureInput.hasTeleportQueued()) {
            AdventureInput.consumeTeleport();
            if (teleport != null) {
                if (teleport.tryActivate(player, facingRight,
                        minPos + EDGE_MARGIN, maxPos - EDGE_MARGIN))
                    clearPlatformAttachmentForRelocation();
            }
        }

        if (!attackActive && wasAttacking) {
            if (!attackIsEcho && AdventureRuntime.cores().hasUnique("P2")) echoCountdown = 10;
            attackIsEcho = false;
        }
        wasAttacking = attackActive;
        if (!attackActive && echoCountdown > 0 && --echoCountdown == 0) {
            if (startAttack(player)) {
                if (advanced != null) advanced.noteAttackStarted(player);
                attackIsEcho = true;
                setWalking(player, false);
                advanceAttackDamage(player);
                syncLastPosition(player);
                wasAttacking = true;
                return;
            }
        }
        if (attackActive) {
            float attackMove = AdventureRuntime.cores().skirmisherPct();
            boolean attackMoving = attackMove > 0f && moveHorizontal(
                    player, minPos, maxPos, attackMove, architect);
            if (attackMoving && advanced != null) advanced.onSkirmishMove(player);
            setWalking(player, attackMoving);
            advanceAttackDamage(player);
            syncLastPosition(player);
            return;
        }

        if (!acrobatCancelled && AdventureInput.consumeAttack()) {
            setWalking(player, false);
            echoCountdown = 0;
            attackIsEcho = false;
            if (!startAttack(player)) setAnim(player, "ATK");
            else if (advanced != null) advanced.noteAttackStarted(player);
            advanceAttackDamage(player);
            syncLastPosition(player);
            return;
        }

        boolean moving = moveHorizontal(player, minPos, maxPos, 1f, architect);
        setWalking(player, moving);
        syncLastPosition(player);
        setAnim(player, moving ? "WALK" : "IDLE");
    }

    private boolean tickIceMotion(
            EUnit player, float minPos, float maxPos,
            AdventureArchitect architect, AdventureSpawnFx motionFx,
            boolean attackActive, boolean allowAttackStart,
            AdventureAdvancedCores advanced) {
        CustomMapDocument.ModeVariant terrain = activeAdventureVariant();
        if (terrain == null || player == null) {
            ice.clear();
            return false;
        }
        float from = EntityAccess.getPos(player);
        CustomMapRuntime.TerrainSample support = CustomMapRuntime.sampleTerrain(
                from, EntityAccess.getLayer(player), false);
        int input = inputFacing();
        float nativeWorld = attackActive ? 0f
                : input * readSpeed(player) * MOVE_MULTIPLIER * .5f
                * AdventureRuntime.cores().moveMult()
                * CustomMapRuntime.worldScale() * landingMoveMultiplier;
        CustomMapRuntime.SlopeSample slope = CustomMapRuntime.sampleSlope(from);
        IceSurfaceRules.Step step = ice.tick(support.isIce(),
                nativeWorld / Math.max(1f, terrain.worldUnitsPerTile()),
                input != 0 ? input : facingRight ? 1 : -1,
                slope.downhillDirection);
        if (!step.forced) return false;

        stopSlopeSlide();
        boolean attackLocked = step.lockAttack;
        if (attackLocked) {
            AdventureInput.consumeAttack();
            interruptAttackOnly(player);
            attackActive = false;
        }
        int direction = step.deltaTiles > 0f ? 1 : -1;
        facingRight = direction > 0;
        float proposed = from + step.deltaTiles * terrain.worldUnitsPerTile();
        float margin = 200f * CustomMapRuntime.worldScale();
        minPos = -margin;
        float customWidth = CustomMapRuntime.worldWidth();
        if (!Float.isNaN(customWidth)) maxPos = customWidth + margin;
        proposed = Math.max(minPos + EDGE_MARGIN,
                Math.min(maxPos - EDGE_MARGIN, proposed));
        if (architect != null)
            proposed = architect.clampPlayerMove(player, from, proposed);
        proposed = clampIcePlayerAgainstOpponents(
                player, from, proposed, motionFx);
        CustomMapRuntime.AirborneContact liquid =
                CustomMapRuntime.sweepAirborne(from,
                        EntityAccess.getLayer(player), proposed,
                        EntityAccess.getLayer(player));
        if (liquid.kind() == CustomMapRuntime.TerrainKind.WATER) {
            EntityAccess.setPos(player, liquid.worldX);
            EntityAccess.setLayer(player, Math.round(liquid.actorLayer));
            jumpLayer = liquid.actorLayer;
            jumping = false;
            ice.clear();
            interruptAttackOnly(player);
            setWalking(player, true);
            setAnim(player, "WALK");
            if (motionFx != null)
                motionFx.slideDust(liquid.worldX,
                        Math.round(liquid.actorLayer), direction, false);
            syncLastPosition(player);
            return true;
        }

        CustomMapRuntime.TerrainSample destination = CustomMapRuntime.sampleTerrain(
                proposed, EntityAccess.getLayer(player), false);
        boolean retainedSupport = support.kind == CustomMapRuntime.TerrainKind.FLOATING
                ? destination.kind == CustomMapRuntime.TerrainKind.FLOATING
                && support.platformId != null
                && support.platformId.equals(destination.platformId)
                : destination.kind == CustomMapRuntime.TerrainKind.MAIN;
        EntityAccess.setPos(player, proposed);
        if (retainedSupport) {
            groundLayer = Math.round(destination.supportLayer);
            EntityAccess.setLayer(player, groundLayer);
        } else {
            ice.clear();
            detachPlatformIntoAir();
            jumping = true;
            jumpLayer = EntityAccess.getLayer(player);
            jumpVelocity = inheritedPlatformLayer;
            inheritedPlatformLayer = 0f;
            jumpCutApplied = true;
        }
        IceSurfaceRules.Phase phase = step.phase;
        if (!retainedSupport) {
            ice.clear();
        } else if (phase == IceSurfaceRules.Phase.GLIDE
                && !destination.isIce()) {
            if (Math.abs(step.deltaTiles) >= IceSurfaceRules.TUMBLE_THRESHOLD) {
                ice.beginTumble();
                phase = IceSurfaceRules.Phase.TUMBLE;
                attackLocked = true;
                AdventureInput.consumeAttack();
                interruptAttackOnly(player);
                attackActive = false;
            } else {
                ice.clear();
            }
        }
        if (motionFx != null)
            motionFx.slideDust(proposed, EntityAccess.getLayer(player),
                    direction, false);
        boolean attackRequested = false;
        if (!attackLocked && !attackActive && allowAttackStart
                && AdventureInput.consumeAttack()) {
            attackRequested = true;
            ice.clear();
            echoCountdown = 0;
            attackIsEcho = false;
            if (startAttack(player)) {
                if (advanced != null) advanced.noteAttackStarted(player);
                attackActive = true;
                wasAttacking = true;
            }
        }
        if (attackActive && !attackLocked) advanceAttackDamage(player);
        setWalking(player, !attackActive);
        setAnim(player, phase == IceSurfaceRules.Phase.TUMBLE
                ? "HIT" : attackActive || attackRequested ? "ATK" : "WALK");
        syncLastPosition(player);
        return true;
    }

    private float clampIcePlayerAgainstOpponents(
            EUnit player, float from, float proposed,
            AdventureSpawnFx motionFx) {
        if (player == null || player.basis == null || player.basis.le == null)
            return proposed;
        CustomMapDocument.ModeVariant terrain = activeAdventureVariant();
        float layerTolerance = terrain == null ? 18f
                : terrain.layerUnitsPerTile() * .80f;
        float separation = terrain == null ? 3f
                : Math.max(1f, terrain.worldUnitsPerTile() * .012f);
        common.battle.entity.Entity hit = null;
        float contact = proposed;
        for (common.battle.entity.Entity candidate : player.basis.le) {
            if (!(candidate instanceof common.battle.entity.EEnemy)
                    || candidate.dead || candidate.health <= 0L
                    || Math.abs(candidate.currentLayer - player.currentLayer)
                    > layerTolerance) continue;
            if (proposed > from && candidate.pos >= from
                    && candidate.pos <= contact) {
                hit = candidate;
                contact = candidate.pos - separation;
            } else if (proposed < from && candidate.pos <= from
                    && candidate.pos >= contact) {
                hit = candidate;
                contact = candidate.pos + separation;
            }
        }
        if (hit == null) return proposed;
        boolean impacted = false;
        if (claimIceImpact(player, hit)) {
            IceSurfaceRules.Motion otherIce = registeredIceMotion(hit);
            float playerVelocity = ice.velocityTilesPerTick();
            float otherVelocity = otherIce == null
                    ? 0f : otherIce.velocityTilesPerTick();
            IceSurfaceRules.Impact playerImpact = ice.hitEnemy(otherVelocity);
            IceSurfaceRules.Impact otherImpact = otherIce == null ? null
                    : otherIce.hitEnemy(playerVelocity);
            if (playerImpact.active) {
                AdventureCombat.queueEffectDamage(hit, IceSurfaceRules.damage(
                        player.maxH, playerImpact.damageRatio));
                impacted = true;
            }
            if (otherImpact != null && otherImpact.active) {
                AdventureCombat.queueEffectDamage(player, IceSurfaceRules.damage(
                        hit.maxH, otherImpact.damageRatio));
                impacted = true;
            }
        }
        if (impacted) {
            if (motionFx != null)
                motionFx.spawnColored(hit.pos, hit.currentLayer,
                        120, 225, 255, 235, 250, 255);
        }
        return contact;
    }

    private boolean moveHorizontal(EUnit player, float minPos, float maxPos,
                                   float scale, AdventureArchitect architect) {
        float move = readSpeed(player) * MOVE_MULTIPLIER * 0.5f
                * AdventureRuntime.cores().moveMult() * scale
                * manualcontrol.custommap.CustomMapRuntime.worldScale()
                * landingMoveMultiplier;

        if (jumping) move *= 1.18f;
        float delta = 0f;
        if (AdventureInput.left()) delta -= move;
        if (AdventureInput.right()) delta += move;
        if (delta == 0f) return false;
        float from = EntityAccess.getPos(player);
        float next = from + delta;
        if (manualcontrol.custommap.CustomMapRuntime.activeDocument() != null) {
            float margin = 200f * manualcontrol.custommap.CustomMapRuntime.worldScale();
            minPos = -margin;
            float customWidth = manualcontrol.custommap.CustomMapRuntime.worldWidth();
            if (!Float.isNaN(customWidth)) maxPos = customWidth + margin;
        }
        if (next < minPos + EDGE_MARGIN) next = minPos + EDGE_MARGIN;
        if (next > maxPos - EDGE_MARGIN) next = maxPos - EDGE_MARGIN;
        if (architect != null) next = architect.clampPlayerMove(player, from, next);
        next = clampAgainstOpponents(player, from, next);
        int direction = delta > 0f ? 1 : -1;
        if (!jumping && scale >= .99f
                && startPlatformBoarding(player, direction)) return true;
        EntityAccess.setPos(player, next);
        if (manualcontrol.custommap.CustomMapRuntime.isUphillMovement(from, next))
            climbGraceTicks = 6;
        return Math.abs(next - from) > 0.001f;
    }

    private static float clampAgainstOpponents(
            EUnit player, float from, float proposed) {
        if (player == null || player.basis == null || player.basis.le == null)
            return proposed;
        CustomMapDocument.ModeVariant terrain = activeAdventureVariant();
        float layers = terrain == null ? 18f
                : terrain.layerUnitsPerTile() * .80f;
        float separation = terrain == null ? 3f
                : Math.max(1f, terrain.worldUnitsPerTile() * .012f);
        float best = proposed;
        for (common.battle.entity.Entity candidate : player.basis.le) {
            if (!(candidate instanceof common.battle.entity.EEnemy)
                    || candidate.dead || candidate.health <= 0L) continue;
            if (Math.abs(candidate.currentLayer - player.currentLayer) > layers)
                continue;
            if (proposed > from && candidate.pos >= from
                    && candidate.pos <= best)
                best = candidate.pos - separation;
            else if (proposed < from && candidate.pos <= from
                    && candidate.pos >= best)
                best = candidate.pos + separation;
        }
        return best;
    }

    void onDamagedWhileMoving(EUnit player) {
        if (player == null || jumping || ice.active()
                || slideTicks > 0 || climbGraceTicks <= 0) return;
        CustomMapRuntime.SlopeSample slope = CustomMapRuntime.sampleSlope(player.pos);
        if (!slope.isSlope()) return;
        slideDirection = slope.downhillDirection;
        slidePending = slideDirection != 0;
    }

    private boolean tickSlopeSlide(EUnit player, float minPos, float maxPos,
                                   AdventureArchitect architect, AdventureSpawnFx motionFx) {
        if (slidePending) {
            slidePending = false;
            CustomMapRuntime.SlopeSample slope = CustomMapRuntime.sampleSlope(player.pos);
            if (!slope.isSlope()) return false;
            slideDirection = slope.downhillDirection;
            float walk = readSpeed(player) * MOVE_MULTIPLIER * 0.5f
                    * AdventureRuntime.cores().moveMult() * CustomMapRuntime.worldScale();
            slideSpeed = Math.max(walk * 0.72f,
                    CustomMapRuntime.worldScale() * 4.5f);
            slideTicks = 30;
            interruptAttackOnly(player);
            if (motionFx != null)
                motionFx.slideDust(player.pos, EntityAccess.getLayer(player),
                        slideDirection, false);
        }
        if (slideTicks <= 0 || slideDirection == 0) return false;

        float from = EntityAccess.getPos(player);
        float maxSlide = Math.max(slideSpeed,
                CustomMapRuntime.worldScale() * 5.5f);
        float next = from + slideDirection * maxSlide;
        if (CustomMapRuntime.activeDocument() != null) {
            float margin = 200f * CustomMapRuntime.worldScale();
            minPos = -margin;
            float customWidth = CustomMapRuntime.worldWidth();
            if (!Float.isNaN(customWidth)) maxPos = customWidth + margin;
        }
        if (next < minPos + EDGE_MARGIN) next = minPos + EDGE_MARGIN;
        if (next > maxPos - EDGE_MARGIN) next = maxPos - EDGE_MARGIN;
        if (architect != null) next = architect.clampPlayerMove(player, from, next);
        if (Math.abs(next - from) < 0.001f
                || !CustomMapRuntime.canSlide(from, next, slideDirection)) {
            stopSlopeSlide();
            return false;
        }

        EntityAccess.setPos(player, next);
        float layer = CustomMapRuntime.surfaceLayerAt(next, EntityAccess.getLayer(player));
        groundLayer = Math.round(layer);
        EntityAccess.setLayer(player, groundLayer);
        slideSpeed = Math.min(slideSpeed + CustomMapRuntime.worldScale() * 0.35f,
                readSpeed(player) * CustomMapRuntime.worldScale());
        slideTicks--;
        if (motionFx != null && (slideTicks % 3 == 0))
            motionFx.slideDust(next, groundLayer, slideDirection, false);
        if (slideTicks <= 0) stopSlopeSlide();
        return true;
    }

    private void stopSlopeSlide() {
        slidePending = false;
        slideTicks = 0;
        slideDirection = 0;
        slideSpeed = 0f;
    }

    boolean isJumping() { return jumping; }

    void forceJump(EUnit player, float multiplier) {
        if (player == null || jumping || !canDrive(player)) return;
        ice.clear();
        detachPlatformIntoAir();
        jumping = true;
        jumpLayer = EntityAccess.getLayer(player);
        jumpVelocity = JUMP_VELOCITY * Math.max(0.1f, multiplier)
                * AdventureRuntime.cores().jumpMult() + inheritedPlatformLayer;
        inheritedPlatformLayer = 0f;
        jumpCutApplied = false;
        setJumpInvincibility(player, true);
    }

    private void advanceAttackDamage(EUnit player) {
        float total = ATTACK_SPEED_MULT * (1f + AdventureRuntime.cores().atkSpeedBonus());
        atkAcc += total;
        int steps = (int) atkAcc;
        atkAcc -= steps;
        atkStepsThisTick = steps;
        for (int i = 0; i < steps && isAttackActive(player); i++) {
            updateAttack(player);
        }
    }

    private void carryPatrolPlatform(EUnit player) {
        if (player == null || jumping || platformId == null) return;
        MovingPlatformEngine.Pose pose = CustomMapRuntime.platformPose(platformId);
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        if (pose == null || variant == null) {
            platformId = null;
            lastPlatformCarryTick = Long.MIN_VALUE;
            return;
        }
        CustomMapRuntime.TerrainSample support = CustomMapRuntime.sampleTerrain(
                EntityAccess.getPos(player), EntityAccess.getLayer(player), false);
        if (support.kind != CustomMapRuntime.TerrainKind.FLOATING
                || !platformId.equals(support.platformId)) {
            detachPlatformIntoAir();
            return;
        }
        long tick = CustomMapRuntime.platformTick();
        if (lastPlatformCarryTick != tick) {
            EntityAccess.setPos(player, EntityAccess.getPos(player)
                    + pose.deltaWorldX(variant));
            lastPlatformCarryTick = tick;
        }
        groundLayer = Math.round(pose.supportLayer(variant));
        EntityAccess.setLayer(player, groundLayer);
    }

    private void detachPlatformIntoAir() {
        if (platformId == null) return;
        MovingPlatformEngine.Pose pose = CustomMapRuntime.platformPose(platformId);
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        if (pose != null && variant != null) {
            platformOriginAir = true;
            platformOriginLayer = pose.supportLayer(variant);
            inheritedPlatformX = pose.velocityWorldPerSecondX(variant)
                    / MovingPlatformEngine.TICKS_PER_SECOND;
            inheritedPlatformLayer = pose.velocityLayerPerSecond(variant)
                    / MovingPlatformEngine.TICKS_PER_SECOND;
        }
        platformId = null;
        lastPlatformCarryTick = Long.MIN_VALUE;
    }

    private void clearPlatformAttachmentForRelocation() {
        ice.clear();
        platformId = null;
        lastPlatformCarryTick = Long.MIN_VALUE;
        platformOriginAir = false;
        platformOriginLayer = Float.NaN;
        inheritedPlatformX = 0f;
        inheritedPlatformLayer = 0f;
    }

    static synchronized void registerIceMotion(
            Object entity, IceSurfaceRules.Motion motion) {
        if (entity != null && motion != null) ACTIVE_ICE.put(entity, motion);
    }

    static synchronized IceSurfaceRules.Motion registeredIceMotion(Object entity) {
        return entity == null ? null : ACTIVE_ICE.get(entity);
    }

    static synchronized void clearRegisteredIce(Object entity) {
        IceSurfaceRules.Motion motion = entity == null ? null : ACTIVE_ICE.get(entity);
        if (motion != null) motion.clear();
    }

    static synchronized void unregisterIceMotion(Object entity) {
        if (entity == null) return;
        ACTIVE_ICE.remove(entity);
        ICE_IMPACTS.remove(entity);
    }

    static synchronized boolean claimIceImpact(Entity actor, Entity other) {
        if (actor == null || other == null) return false;
        long tick = actor.basis == null ? CustomMapRuntime.platformTick()
                : actor.basis.time;
        IceImpactStamp actorStamp = ICE_IMPACTS.get(actor);
        IceImpactStamp otherStamp = ICE_IMPACTS.get(other);
        if ((actorStamp != null && actorStamp.tick == tick
                && actorStamp.other.get() == other)
                || (otherStamp != null && otherStamp.tick == tick
                && otherStamp.other.get() == actor)) return false;
        ICE_IMPACTS.put(actor, new IceImpactStamp(other, tick));
        ICE_IMPACTS.put(other, new IceImpactStamp(actor, tick));
        return true;
    }

    private static CustomMapDocument.ModeVariant activeAdventureVariant() {
        CustomMapDocument document = CustomMapRuntime.activeDocument();
        return document == null ? null
                : document.variant(CustomMapDocument.MapMode.ADVENTURE);
    }

    private void applyPlatformLanding(EUnit player) {
        if (!platformOriginAir || Float.isNaN(platformOriginLayer)) return;
        CustomMapDocument.ModeVariant variant = activeAdventureVariant();
        if (variant == null) return;
        float dropTiles = Math.max(0f, (groundLayer - platformOriginLayer)
                / Math.max(1f, variant.layerUnitsPerTile()));
        CustomMapLandingImpact.Result result =
                CustomMapLandingImpact.resolveSelf(player, dropTiles);
        landingStunTicks = result.penalty.effectiveStunTicks(
                nativeImmunity(player, "IMUSTOP"));
        landingSlowTicks = result.penalty.effectiveSlowTicks(
                nativeImmunity(player, "IMUSLOW"));
        landingMoveMultiplier = landingSlowTicks > 0
                ? result.penalty.movementMultiplier : 1f;
        lastPlatformDropTiles = dropTiles;
        platformOriginAir = false;
        platformOriginLayer = Float.NaN;
        inheritedPlatformX = 0f;
        inheritedPlatformLayer = 0f;
    }

    private static boolean nativeImmunity(EUnit player, String fieldName) {
        try {
            Object data = BCUFields.get(player, "data");
            Object proc = BCUFields.invoke(data, "getProc");
            Object immunity = BCUFields.get(proc, fieldName);
            Object exists = BCUFields.invoke(immunity, "exists");
            return exists instanceof Boolean && ((Boolean) exists).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean startPlatformBoarding(EUnit player, int direction) {
        CustomMapRuntime.PlatformBoarding boarding =
                CustomMapRuntime.findPlatformBoarding(
                        EntityAccess.getPos(player), EntityAccess.getLayer(player),
                        platformId, direction);
        if (boarding == null || !CustomMapRuntime.beginPlatformBoarding(
                boarding.platformId, player, boarding.durationTicks)) return false;
        ice.clear();
        boardingPlatformId = boarding.platformId;
        boardingJump = boarding.jump;
        boardingTick = 0;
        boardingDuration = boarding.durationTicks;
        jumping = true;
        jumpLayer = boarding.jump.startLayer;
        jumpVelocity = 0f;
        jumpCutApplied = true;
        platformId = null;
        lastPlatformCarryTick = Long.MIN_VALUE;
        interruptAttackOnly(player);
        setJumpInvincibility(player, true);
        return true;
    }

    private void tickPlatformBoarding(EUnit player) {
        CustomMapRuntime.GapJump jump = boardingJump;
        if (jump == null) return;
        float previousPos = EntityAccess.getPos(player);
        float previousLayer = jumpLayer;
        boardingTick++;
        float progress = boardingTick / (float) Math.max(1, boardingDuration);
        float nextX = jump.worldXAt(progress);
        float nextLayer = jump.layerAt(progress);
        EntityAccess.setPos(player, nextX);
        jumpLayer = nextLayer;
        EntityAccess.setLayer(player, Math.round(nextLayer));

        CustomMapRuntime.AirborneContact airborne =
                CustomMapRuntime.sweepAirborne(
                        previousPos, previousLayer, nextX, nextLayer);
        if (airborne.kind() == CustomMapRuntime.TerrainKind.WATER) {
            EntityAccess.setPos(player, airborne.worldX);
            jumpLayer = airborne.actorLayer;
            EntityAccess.setLayer(player, Math.round(airborne.actorLayer));
            jumping = false;
            jumpVelocity = 0f;
            jumpCutApplied = true;
            setJumpInvincibility(player, false);
            finishPlatformBoarding(player);
            return;
        }
        CustomMapRuntime.TerrainSample contact =
                airborne.kind() == CustomMapRuntime.TerrainKind.MAIN
                || airborne.kind() == CustomMapRuntime.TerrainKind.FLOATING
                ? airborne.terrain : null;
        boolean intended = contact != null && boardingPlatformId != null
                && boardingPlatformId.equals(contact.platformId);
        if (intended && contact.hasSupport()) {
            finishPlatformBoardingLanding(player, contact);
            return;
        }
        if (boardingTick >= boardingDuration) {
            CustomMapRuntime.TerrainSample landing = CustomMapRuntime.sampleTerrain(
                    jump.landingWorldX, jump.landingLayer, true);
            if (boardingPlatformId != null
                    && boardingPlatformId.equals(landing.platformId)
                    && landing.hasSupport()) {
                EntityAccess.setPos(player, jump.landingWorldX);
                finishPlatformBoardingLanding(player, landing);
            } else {
                jumpLayer = jump.landingLayer;
                jumpVelocity = 0f;
                jumpCutApplied = true;
                finishPlatformBoarding(player);
            }
        }
    }

    private void finishPlatformBoardingLanding(
            EUnit player, CustomMapRuntime.TerrainSample landing) {
        groundLayer = Math.round(landing.supportLayer);
        jumpLayer = landing.supportLayer;
        EntityAccess.setLayer(player, groundLayer);
        platformId = landing.platformId;
        lastPlatformCarryTick = CustomMapRuntime.platformTick();
        jumping = false;
        jumpVelocity = 0f;
        jumpCutApplied = false;
        justLanded = true;
        airJumpUsed = false;
        setJumpInvincibility(player, false);
        finishPlatformBoarding(player);
    }

    private void finishPlatformBoarding(EUnit player) {
        if (boardingPlatformId != null)
            CustomMapRuntime.finishPlatformBoarding(boardingPlatformId, player);
        boardingPlatformId = null;
        boardingJump = null;
        boardingTick = boardingDuration = 0;
    }

    private void stepJump(EUnit player) {
        if (!jumping) return;
        if (!jumpCutApplied) {
            if (!AdventureInput.jumpHeld() && jumpVelocity < 0f) {
                jumpVelocity *= JUMP_RELEASE_MULTIPLIER;
                jumpCutApplied = true;
            } else if (jumpVelocity >= 0f) {
                jumpCutApplied = true;
            }
        }
        jumpVelocity += JUMP_GRAVITY * manualcontrol.custommap.CustomMapRuntime.worldScale()
                * AdventureRuntime.cores().gravMult();
        jumpLayer += jumpVelocity;
        if (Math.abs(inheritedPlatformX) > .001f) {
            EntityAccess.setPos(player, EntityAccess.getPos(player)
                    + inheritedPlatformX);
            inheritedPlatformX *= .985f;
        }
        boolean landed = false;
        if (jumpLayer >= groundLayer) {
            jumpLayer = groundLayer;
            jumping = false;
            landed = true;
            justLanded = true;
            airJumpUsed = false;
            jumpCutApplied = false;
            applyPlatformLanding(player);
        }
        try { EntityAccess.setLayer(player, Math.round(jumpLayer)); } catch (Throwable ignored) {}
        setJumpInvincibility(player, !landed);
    }

    private void cancelJump(EUnit player) {
        finishPlatformBoarding(player);
        if (jumping) {
            setJumpInvincibility(player, false);
            jumping = false;
            jumpCutApplied = false;
            if (groundCaptured) {
                try { EntityAccess.setLayer(player, groundLayer); } catch (Throwable ignored) {}
            }
        }
    }

    private static int inputFacing() {
        boolean left = AdventureInput.left();
        boolean right = AdventureInput.right();
        if (left == right) return 0;
        return right ? 1 : -1;
    }

    static boolean canDrive(Object entity) {
        return readHealth(entity) > 0L
                && !readDead(entity)
                && !isZombieReviveActive(entity)
                && !hasCorpse(entity)
                && readKbTime(entity) == 0;
    }

    static long readHealth(Object entity) {
        try {
            return BCUFields.field(entity.getClass(), "health").getLong(entity);
        } catch (Throwable ignored) {
            return 1L;
        }
    }

    static boolean readDead(Object entity) {
        try {
            return BCUFields.field(entity.getClass(), "dead").getBoolean(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isZombieReviveActive(Object entity) {
        try {
            Object statusArr = BCUFields.get(entity, "status");
            return ((int[][]) statusArr)[48][1] > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasCorpse(Object entity) {
        try {
            Object anim = BCUFields.get(entity, "anim");
            return BCUFields.get(anim, "corpse") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isRevivingCorpse(Object entity) {
        return isZombieReviveActive(entity) || hasCorpse(entity);
    }

    static boolean finishZombieRevive(Object entity) {
        if (entity == null || !isZombieReviveActive(entity) || !hasCorpse(entity)) return false;
        try {
            int[][] status = (int[][]) BCUFields.get(entity, "status");
            status[48][1] = 0;
            clearCorpse(entity);
            try { BCUFields.field(entity.getClass(), "dead").setBoolean(entity, false); }
            catch (Throwable ignored) {}
            syncLastPosition(entity);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int readKbTime(Object entity) {
        try {
            return BCUFields.getInt(entity, "kbTime");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static void setWalking(Object entity, boolean walking) {
        try {
            BCUFields.field(entity.getClass(), "walking").setBoolean(entity, walking);
        } catch (Throwable ignored) {}
    }

    static void clearMotionState(Object entity) {
        try { BCUFields.field(entity.getClass(), "kbTime").setInt(entity, 0); } catch (Throwable ignored) {}
    }

    static void interruptAction(Object entity, int waitTicks) {
        if (entity == null) return;
        clearMotionState(entity);
        setWalking(entity, false);
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            BCUFields.field(atkm.getClass(), "preTime").setInt(atkm, 0);
            BCUFields.field(atkm.getClass(), "atkTime").setInt(atkm, 0);
        } catch (Throwable ignored) {}
        if (waitTicks > 0) {
            try {
                int wait = BCUFields.getInt(entity, "waitTime");
                BCUFields.field(entity.getClass(), "waitTime").setInt(entity, Math.max(wait, waitTicks));
            } catch (Throwable ignored) {}
        }
        setAnim(entity, "IDLE");
        syncLastPosition(entity);
    }

    static void interruptAttackOnly(Object entity) {
        if (entity == null) return;
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            BCUFields.field(atkm.getClass(), "preTime").setInt(atkm, 0);
            BCUFields.field(atkm.getClass(), "atkTime").setInt(atkm, 0);
        } catch (Throwable ignored) {}
        setWalking(entity, false);
        syncLastPosition(entity);
    }

    static void clearCorpse(Object entity) {
        try {
            Object anim = BCUFields.get(entity, "anim");
            if (anim != null) BCUFields.set(anim, "corpse", null);
        } catch (Throwable ignored) {}
    }

    static int readWaitTime(Object entity) {
        try {
            return BCUFields.getInt(entity, "waitTime");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static void tickWaitTime(Object entity) {
        try {
            int wait = BCUFields.getInt(entity, "waitTime");
            if (wait > 0) BCUFields.field(entity.getClass(), "waitTime").setInt(entity, wait - 1);
        } catch (Throwable ignored) {}
    }

    static void syncLastPosition(Object entity) {
        try {
            float pos = EntityAccess.getPos(entity);
            BCUFields.field(entity.getClass(), "lastPosition").setFloat(entity, pos);
        } catch (Throwable ignored) {}
    }

    static void setJumpInvincibility(Object entity, boolean on) {
        if (entity == null) return;
        try {
            Object statusArr = BCUFields.get(entity, "status");
            ((int[][]) statusArr)[44][0] = on ? 99999 : 0;
        } catch (Throwable ignored) {}
    }

    static void tickAttackImmunity(Object entity) {
        try {
            Object statusArr = BCUFields.get(entity, "status");
            int[] s44 = ((int[][]) statusArr)[44];
            if (s44[0] > 0) s44[0]--;
        } catch (Throwable ignored) {}
    }

    static boolean isAttackActive(Object entity) {
        return readAttackTime(entity) > 0;
    }

    static int readAttackTime(Object entity) {
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            return BCUFields.field(atkm.getClass(), "atkTime").getInt(atkm);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static boolean startAttack(Object entity) {
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            if (BCUFields.field(atkm.getClass(), "atkTime").getInt(atkm) > 0) return false;
            int loops = 1;
            try {
                Object data = BCUFields.get(entity, "data");
                Object value = BCUFields.invoke(data, "getAtkLoop");
                if (value instanceof Number) loops = ((Number) value).intValue();
            } catch (Throwable ignored) {}
            if (loops == 0) loops = 1;
            try { BCUFields.field(atkm.getClass(), "attacksLeft").setInt(atkm, loops); } catch (Throwable ignored) {}
            try { BCUFields.field(entity.getClass(), "waitTime").setInt(entity, 0); } catch (Throwable ignored) {}
            BCUFields.method(atkm.getClass(), "startAttack").invoke(atkm);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static float readSpeed(Object entity) {
        float speed = 10f;
        try {
            Object data = BCUFields.get(entity, "data");
            Object s = BCUFields.invoke(data, "getSpeed");
            if (s instanceof Number) speed = ((Number) s).floatValue();
        } catch (Throwable ignored) {}

        return Math.max(speed, MIN_MOVE_SPEED);
    }

    static boolean updateAttack(Object entity) {
        try {
            Object atkm = BCUFields.get(entity, "atkm");
            BCUFields.method(atkm.getClass(), "updateAttack").invoke(atkm);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void tickAnim(Object entity) {
        try {
            Object animMgr = BCUFields.get(entity, "anim");
            if (animMgr == null) return;
            BCUFields.method(animMgr.getClass(), "update").invoke(animMgr);
        } catch (Throwable ignored) {}
    }

    static void setAnim(Object entity, String kind) {
        try {
            Object animMgr = BCUFields.get(entity, "anim");
            if (animMgr == null) return;
            initAnimCache(animMgr);
            if (setAnimMethod == null) return;
            Object type = "WALK".equals(kind) ? uWalk
                    : "ATK".equals(kind) ? uAtk
                    : "HIT".equals(kind) && uHit != null ? uHit : uIdle;
            if (type != null) setAnimMethod.invoke(animMgr, type, true);
        } catch (Throwable ignored) {}
    }

    private static void initAnimCache(Object animManager) {
        if (animCacheReady) return;
        try {
            Class<?> uTypeCls = Class.forName("common.util.anim.AnimU$UType");
            for (Object e : uTypeCls.getEnumConstants()) {
                String name = ((Enum<?>) e).name();
                if (name.equals("WALK")) uWalk = e;
                if (name.equals("IDLE")) uIdle = e;
                if (name.equals("ATK")) uAtk = e;
                if (name.equals("HB") || name.equals("KB")
                        || name.equals("HITBACK"))
                    if (uHit == null) uHit = e;
            }
            setAnimMethod = BCUFields.method(animManager.getClass(), "setAnim", uTypeCls, boolean.class);
            animCacheReady = true;
        } catch (Throwable ignored) {}
    }
}
