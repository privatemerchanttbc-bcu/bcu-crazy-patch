package manualcontrol.custommap;

public final class CustomMapPhysicsRules {

    public static final float GROUND_CONTACT_INSET_LAYERS = 2.5f;
    public static final float SWIM_SPEED_RATIO = 0.45f;
    public static final float SWIM_CARRY_FLOOR_RATIO = 0.5f;
    public static final float SWIM_CARRY_RETENTION =
            1f - 3f * (1f - IceSurfaceRules.TUMBLE_FRICTION);
    public static final float SWIM_BASE_DEPTH_TILES = 0.38f;
    public static final float SWIM_BOB_TILES = 0.045f;
    public static final float SWIM_ATTACK_SINK_PER_TICK_TILES = 0.032f;
    public static final float SWIM_RECOVER_PER_TICK_TILES = 0.050f;
    public static final float FALL_ACCELERATION_TILES_PER_TICK = 0.045f;
    public static final float FALL_TERMINAL_TILES_PER_TICK = 0.90f;
    public static final float FALL_HORIZONTAL_RETENTION = 0.99f;
    public static final float JUMP_CARRY_RETENTION = 0.99f;
    public static final float JUMP_RELEASE_MULTIPLIER = 0.45f;
    public static final float FREE_JUMP_APEX_TILES = 1.25f;
    public static final float GAP_JUMP_SPEED_MULTIPLIER = 2f;
    public static final float VOID_FALL_NUDGE_RATIO = 0.035f;
    public static final float WATER_FALL_NUDGE_RATIO = 0.025f;

    public enum TransitionKind {
        NONE, STEP_UP, GAP, WATER_ENTRY, BLOCKED
    }

    public static final class SwimStep {
        public final int tick;
        public final float sinkLayer;
        public final float visualLayer;

        SwimStep(int tick, float sinkLayer, float visualLayer) {
            this.tick = tick;
            this.sinkLayer = sinkLayer;
            this.visualLayer = visualLayer;
        }
    }

    public static final class GroundTransition {
        public final TransitionKind kind;
        public final CustomMapRuntime.GapJump jump;

        GroundTransition(TransitionKind kind, CustomMapRuntime.GapJump jump) {
            this.kind = kind == null ? TransitionKind.NONE : kind;
            this.jump = jump;
        }

        public boolean startsJump() {
            return jump != null && (kind == TransitionKind.STEP_UP
                    || kind == TransitionKind.GAP
                    || kind == TransitionKind.WATER_ENTRY);
        }

        public boolean landsInWater() {
            return kind == TransitionKind.WATER_ENTRY;
        }
    }

    public static final class LiquidStep {
        public final int exposureTicks;
        public final long health;
        public final boolean damaged;

        LiquidStep(int exposureTicks, long health, boolean damaged) {
            this.exposureTicks = exposureTicks;
            this.health = health;
            this.damaged = damaged;
        }
    }

    private final CustomMapDocument document;
    private final CustomMapDocument.ModeVariant terrain;
    private final CustomMapDocument.ThemeLiquidProfile liquid;

    private CustomMapPhysicsRules(CustomMapDocument document,
                                  CustomMapDocument.ModeVariant terrain) {
        this.document = document;
        this.terrain = terrain;
        this.liquid = document == null || document.themeProfile == null
                ? null : document.themeProfile.liquid;
    }

    public static CustomMapPhysicsRules bind(
            CustomMapDocument document,
            CustomMapDocument.ModeVariant terrain) {
        return terrain == null ? null : new CustomMapPhysicsRules(document, terrain);
    }

    public CustomMapDocument document() { return document; }

    public CustomMapDocument.ModeVariant terrain() { return terrain; }

    public boolean isLava() {
        return liquid != null && "lava".equals(liquid.kind);
    }

    public float waterSpeedRatio() { return SWIM_SPEED_RATIO; }

    public float gravityPerStep(int simulationHz) {
        float tickRatio = 30f / Math.max(1, simulationHz);
        return terrain.layerUnitsPerTile() * FALL_ACCELERATION_TILES_PER_TICK
                * tickRatio * tickRatio;
    }

    public float terminalFallPerStep(int simulationHz) {
        return terrain.layerUnitsPerTile() * FALL_TERMINAL_TILES_PER_TICK
                * 30f / Math.max(1, simulationHz);
    }

    public float freeJumpVelocity(int simulationHz) {
        float gravity = gravityPerStep(simulationHz);
        float apex = terrain.layerUnitsPerTile() * FREE_JUMP_APEX_TILES;
        return -(float) Math.sqrt(Math.max(0f, 2f * gravity * apex));
    }

    public int simulationStepsForMapTicks(int mapTicks, int simulationHz) {
        return Math.max(1, Math.round(Math.max(1, mapTicks)
                * Math.max(1, simulationHz) / 30f));
    }

    public int dryJumpDuration(CustomMapRuntime.GapJump jump,
                               float preferredHorizontalSpeed) {
        if (jump == null) return 1;
        return jump.duration(Math.max(1f, preferredHorizontalSpeed),
                jump.gapTiles > 0 ? GAP_JUMP_SPEED_MULTIPLIER : 1f);
    }

    public CustomMapRuntime.GapJump gapJump(float worldX, int direction) {
        return CustomMapRuntime.findGapJump(terrain, worldX, direction);
    }

    public float groundVisualLayer(float supportLayer) {
        return supportLayer + GROUND_CONTACT_INSET_LAYERS;
    }

    public SwimStep advanceSwim(int previousTick, float previousSinkLayer,
                                float actorWorldX, float waterLayer,
                                boolean attacking) {
        if (Float.isNaN(waterLayer))
            return new SwimStep(previousTick, previousSinkLayer, Float.NaN);
        int tick = previousTick == Integer.MAX_VALUE ? 1 : previousTick + 1;
        float sink = Math.max(0f, previousSinkLayer);
        if (attacking)
            sink += terrain.layerUnitsPerTile()
                    * SWIM_ATTACK_SINK_PER_TICK_TILES;
        else
            sink = Math.max(0f, sink - terrain.layerUnitsPerTile()
                    * SWIM_RECOVER_PER_TICK_TILES);
        float bob = attacking ? 0f : (float) Math.sin(
                tick * .32f + (actorWorldX % 97f) * .017f)
                * terrain.layerUnitsPerTile() * SWIM_BOB_TILES;
        float visual = waterLayer + terrain.layerUnitsPerTile()
                * SWIM_BASE_DEPTH_TILES + sink + bob;
        return new SwimStep(tick, sink, visual);
    }

    public float swimVisualLayer(int tick, float sinkLayer, float actorWorldX,
                                 float waterLayer, boolean attacking) {
        if (Float.isNaN(waterLayer)) return waterLayer;
        float bob = attacking ? 0f : (float) Math.sin(
                tick * .32f + (actorWorldX % 97f) * .017f)
                * terrain.layerUnitsPerTile() * SWIM_BOB_TILES;
        return waterLayer + terrain.layerUnitsPerTile()
                * SWIM_BASE_DEPTH_TILES + Math.max(0f, sinkLayer) + bob;
    }

    public GroundTransition groundTransition(float fromWorldX, float toWorldX,
                                             float actorLayer, int direction) {
        if (terrain == null || direction == 0 || fromWorldX == toWorldX)
            return new GroundTransition(TransitionKind.NONE, null);
        TerrainHeightfield.MainStep step = TerrainHeightfield.firstMainStep(
                terrain, fromWorldX, toWorldX);
        if (step == null) {
            float lookAhead = toWorldX + direction
                    * terrain.worldUnitsPerTile() * .32f;
            TerrainHeightfield.MainStep ahead = TerrainHeightfield.firstMainStep(
                    terrain, fromWorldX, lookAhead);
            if (ahead != null && ahead.kind == TerrainHeightfield.StepKind.UP)
                step = ahead;
        }
        if (step != null && step.kind == TerrainHeightfield.StepKind.UP) {
            CustomMapRuntime.GapJump jump = stepUpJump(step, fromWorldX);
            return new GroundTransition(jump == null
                    ? TransitionKind.BLOCKED : TransitionKind.STEP_UP, jump);
        }

        TerrainHeightfield.Contact destination = TerrainHeightfield.sample(
                terrain, toWorldX, actorLayer, false, false);
        if (destination.kind == CustomMapRuntime.TerrainKind.WATER) {
            CustomMapDocument.NavigationLink route =
                    swimRouteAt(toWorldX);
            CustomMapRuntime.GapJump jump = waterEntryJump(
                    route, fromWorldX, direction);
            return new GroundTransition(jump == null
                    ? TransitionKind.BLOCKED : TransitionKind.WATER_ENTRY, jump);
        }
        if (destination.kind == CustomMapRuntime.TerrainKind.VOID) {
            CustomMapRuntime.GapJump jump = CustomMapRuntime.findGapJump(
                    terrain, fromWorldX, direction);
            return new GroundTransition(jump == null
                    ? TransitionKind.NONE : TransitionKind.GAP, jump);
        }
        return new GroundTransition(TransitionKind.NONE, null);
    }

    public CustomMapRuntime.GapJump stepUpJump(
            TerrainHeightfield.MainStep step, float currentWorldX) {
        if (terrain == null || step == null
                || step.kind != TerrainHeightfield.StepKind.UP) return null;
        int maxRows = terrain.profile == null ? 1
                : Math.max(1, terrain.profile.maxStepRows);
        if (step.heightRows(terrain) > maxRows) return null;
        float units = terrain.worldUnitsPerTile();
        float landingWorldX = (step.toTile
                + (step.direction > 0 ? .35f : .65f)) * units;
        float startLayer = terrain.surfaceLayerAt(currentWorldX);
        float landingLayer = terrain.surfaceLayerAt(landingWorldX);
        if (Float.isNaN(startLayer) || Float.isNaN(landingLayer)
                || landingLayer >= startLayer) return null;
        float rise = startLayer - landingLayer;
        float apex = rise * .62f + terrain.layerUnitsPerTile() * .90f;
        return new CustomMapRuntime.GapJump(currentWorldX, landingWorldX,
                startLayer, landingLayer, apex, step.direction, 0);
    }

    public CustomMapRuntime.GapJump waterExitJump(
            CustomMapDocument.NavigationLink route,
            float currentWorldX, float currentVisualLayer, int direction) {
        if (terrain == null || direction == 0) return null;
        if (route == null)
            route = swimRouteAt(currentWorldX);
        if (route == null || route.type != CustomMapDocument.NavigationType.SWIM)
            return null;
        int landingTile = direction > 0 ? route.toX : route.fromX;
        if (landingTile < 0 || landingTile >= terrain.width
                || terrain.surface[landingTile] < 0 || terrain.water[landingTile])
            return null;
        int riseRows = Math.abs(terrain.surface[route.fromX]
                - terrain.surface[route.toX]);
        int maxStepRows = terrain.profile == null ? 1
                : Math.max(1, terrain.profile.maxStepRows);
        if (riseRows > maxStepRows) return null;

        float landingWorldX = (landingTile + (direction > 0 ? .35f : .65f))
                * terrain.worldUnitsPerTile();
        float landingLayer = terrain.surfaceLayerAt(landingWorldX);
        if (Float.isNaN(currentVisualLayer) || Float.isNaN(landingLayer)) return null;
        float startLayer = currentVisualLayer - GROUND_CONTACT_INSET_LAYERS;
        float verticalDelta = Math.abs(landingLayer - startLayer);
        float apex = verticalDelta * .55f
                + terrain.layerUnitsPerTile() * 1.25f;
        int crossed = Math.max(1, route.spanEndX - route.spanStartX + 1);
        return new CustomMapRuntime.GapJump(currentWorldX, landingWorldX,
                startLayer, landingLayer, apex, direction, crossed);
    }

    public CustomMapRuntime.GapJump waterEntryJump(
            CustomMapDocument.NavigationLink route,
            float currentWorldX, int direction) {
        if (terrain == null || route == null || direction == 0
                || route.type != CustomMapDocument.NavigationType.SWIM) return null;
        int waterTile = direction > 0 ? route.spanStartX : route.spanEndX;
        if (waterTile < 0 || waterTile >= terrain.width
                || !terrain.water[waterTile]) return null;
        float startLayer = terrain.surfaceLayerAt(currentWorldX);
        float water = TerrainHeightfield.waterLayer(terrain, waterTile);
        if (Float.isNaN(startLayer) || Float.isNaN(water)) return null;
        float landingWorldX = (waterTile + (direction > 0 ? .42f : .58f))
                * terrain.worldUnitsPerTile();
        float landingLayer = water + terrain.layerUnitsPerTile()
                * SWIM_BASE_DEPTH_TILES - GROUND_CONTACT_INSET_LAYERS;
        float drop = Math.max(0f, landingLayer - startLayer);
        float apex = terrain.layerUnitsPerTile() * 1.05f
                + Math.min(terrain.layerUnitsPerTile(), drop * .25f);
        return new CustomMapRuntime.GapJump(currentWorldX, landingWorldX,
                startLayer, landingLayer, apex, direction, 1);
    }

    private CustomMapDocument.NavigationLink swimRouteAt(float worldX) {
        CustomMapDocument.NavigationLink authored =
                TerrainHeightfield.containingLink(terrain, worldX,
                        CustomMapDocument.NavigationType.SWIM);
        if (authored != null || terrain == null || terrain.water == null
                || !terrain.containsWorldX(worldX)) return authored;
        int tile = TerrainHeightfield.tileAt(terrain, worldX);
        if (tile < 0 || tile >= terrain.width || !terrain.water[tile]) return null;
        int first = tile, last = tile;
        while (first > 0 && terrain.water[first - 1]) first--;
        while (last + 1 < terrain.width && terrain.water[last + 1]) last++;
        int from = first - 1;
        int to = last + 1;
        if (from < 0 || to >= terrain.width || terrain.surface == null
                || terrain.surface[from] < 0 || terrain.surface[to] < 0)
            return null;
        CustomMapDocument.NavigationLink fallback =
                new CustomMapDocument.NavigationLink(
                        CustomMapDocument.NavigationType.SWIM,
                        from, to, terrain.walkLayerAtTile(from),
                        terrain.walkLayerAtTile(to), first, last);
        fallback.bidirectional = true;
        return fallback;
    }

    public int waterExitDuration(CustomMapRuntime.GapJump jump,
                                 float preferredHorizontalSpeed) {
        if (jump == null) return 1;
        int riseRows = Math.round(Math.abs(jump.landingLayer - jump.startLayer)
                / Math.max(1f, terrain.layerUnitsPerTile()));
        return Math.max(jump.duration(Math.max(1f, preferredHorizontalSpeed)),
                Math.min(96, 30 + riseRows * 8));
    }

    public int waterEntryDuration(CustomMapRuntime.GapJump jump,
                                  float preferredHorizontalSpeed) {
        if (jump == null) return 1;
        int dropRows = Math.round(Math.max(0f,
                jump.landingLayer - jump.startLayer)
                / Math.max(1f, terrain.layerUnitsPerTile()));
        return Math.max(jump.duration(Math.max(1f, preferredHorizontalSpeed)),
                Math.min(96, 30 + dropRows * 5));
    }

    public IceSurfaceRules.Step advanceIce(IceSurfaceRules.Motion motion,
                                           boolean onIce,
                                           float nativeDeltaTiles,
                                           int facingDirection,
                                           int downhillDirection) {
        return motion.tick(onIce, nativeDeltaTiles,
                facingDirection, downhillDirection);
    }

    public LiquidStep advanceLiquid(int previousExposureTicks,
                                    boolean submerged,
                                    long health, long maxHealth) {
        if (!submerged || !isLava()) return new LiquidStep(0, health, false);
        int exposure = nextLiquidExposure(previousExposureTicks, true);
        long nextHealth = lavaPulseDue(exposure, liquid)
                ? lavaHealthAfterPulse(health, maxHealth, liquid) : health;
        return new LiquidStep(exposure, nextHealth, nextHealth < health);
    }

    public long liquidHealthAtExposure(int exposureTicks,
                                       long health, long maxHealth) {
        return isLava() && lavaPulseDue(exposureTicks, liquid)
                ? lavaHealthAfterPulse(health, maxHealth, liquid) : health;
    }

    public static int nextLiquidExposure(int previousTicks, boolean submerged) {
        if (!submerged) return 0;
        return previousTicks >= Integer.MAX_VALUE - 1 ? 1
                : Math.max(0, previousTicks) + 1;
    }

    public static boolean lavaPulseDue(
            int exposureTicks, CustomMapDocument.ThemeLiquidProfile liquid) {
        if (liquid == null) return false;
        int elapsed = exposureTicks - Math.max(0, liquid.graceTicks);
        return elapsed > 0
                && elapsed % Math.max(1, liquid.damageIntervalTicks) == 0;
    }

    public static long lavaHealthAfterPulse(
            long health, long maxHealth,
            CustomMapDocument.ThemeLiquidProfile liquid) {
        if (liquid == null) return health;
        long minimum = Math.max(0L, liquid.minimumHealth);
        if (health <= minimum) return health;
        double raw = Math.max(1L, maxHealth)
                * liquid.maxHealthDamagePercent / 100d;
        long loss = raw >= Long.MAX_VALUE ? Long.MAX_VALUE
                : Math.max(1L, Math.round(raw));
        long removable = health - minimum;
        return loss >= removable ? minimum : health - loss;
    }
}
