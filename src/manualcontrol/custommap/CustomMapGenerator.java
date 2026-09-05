package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.EnemyPlacement;
import manualcontrol.custommap.CustomMapDocument.ComplexityProfile;
import manualcontrol.custommap.CustomMapDocument.MapAnchor;
import manualcontrol.custommap.CustomMapDocument.MapMode;
import manualcontrol.custommap.CustomMapDocument.MapSpec;
import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.TerrainMotif;
import manualcontrol.custommap.CustomMapDocument.TerrainMotifType;
import manualcontrol.custommap.CustomMapDocument.TreePlacement;
import manualcontrol.custommap.CustomMapDocument.PropAssetRef;
import manualcontrol.custommap.CustomMapDocument.PropPlacement;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class CustomMapGenerator {

    public static final int MIN_MAP_HEIGHT = 8;
    public static final int MAX_MAP_HEIGHT = 32;
    public static final int DEFAULT_MAP_HEIGHT = 12;
    public static final int MAX_GROUND_HEIGHT = 12;

    private CustomMapGenerator() {}

    static final int CHASM_MIN_TILES = 1;
    static final int CHASM_MAX_TILES = 2;
    static final int RIVER_MIN_TILES = 3;
    static final int RIVER_MAX_TILES = 7;
    static final int SLOPE_MIN_TILES = 1;
    static final int SLOPE_MAX_TILES = 6;
    static final int VERTICAL_STEP_MIN_TILES = 1;
    static final int VERTICAL_STEP_MAX_TILES = 3;

    static final int FLOATING_ISLAND_THICKNESS = 1;
    static final int FLOATING_ISLAND_LAYER_GAP = 1;
    static final int FLOATING_ISLAND_TOP_MARGIN = 1;
    static final int FLOATING_ISLAND_GROUND_HEADROOM = 2;
    public static final int MAX_FLOATING_ISLAND_COUNT = 30;

    public static CustomMapDocument generate(MapSpec input, String existingUuid) {
        return generate(input, existingUuid, null);
    }

    public static CustomMapDocument generate(MapSpec input, String existingUuid,
                                             TileCatalog.TileSet tiles) {
        validateSpec(input);
        boolean mixedIceSurface = tiles != null
                && tiles.supportsIceSurfaceDensity();
        TileCatalog.TileSet resolvedTiles = tiles == null
                ? null : tiles.resolveBaseGroundFamily(input.seed);
        CustomMapDocument doc = new CustomMapDocument();
        doc.uuid = existingUuid == null || existingUuid.trim().isEmpty()
                ? UUID.randomUUID().toString() : existingUuid;
        doc.name = cleanName(input.name);
        doc.createdAt = System.currentTimeMillis();
        doc.updatedAt = doc.createdAt;
        doc.spec = copy(input);
        doc.themeProfile = CustomMapDocument.ThemeProfile.normalized(
                resolvedTiles == null ? null : resolvedTiles.themeProfile);
        doc.propManifest = resolvedTiles != null && resolvedTiles.supportsProps()
                && effectivePropDensity(input) > 0d
                ? resolvedTiles.propManifest() : new CustomMapDocument.PropManifest();
        double bridgeDensity = iceBridgesAllowed(mixedIceSurface, resolvedTiles)
                ? effectiveIceBridgeDensity(input) : 0.0;
        boolean slopesAvailable = resolvedTiles == null || resolvedTiles.supportsSlopes();
        int slopeSpan = resolvedTiles == null ? 2 : Math.max(SLOPE_MIN_TILES, Math.min(SLOPE_MAX_TILES,
                Math.min(resolvedTiles.groundSlopeUp.size(), resolvedTiles.groundSlopeDown.size())));
        int slopeRunGap = resolvedTiles == null ? 0
                : Math.max(0, resolvedTiles.slopeRunGapTiles);
        for (String modeId : doc.spec.modes) {
            MapMode mode = MapMode.fromId(modeId);
            if (mode != null) doc.variants.put(mode.id,
                    generateVariant(doc.spec, mode, slopesAvailable, slopeSpan,
                            slopeRunGap, false, bridgeDensity));
        }

        doc.battleTerrain = generateBattleTerrain(doc.spec, slopesAvailable,
                slopeSpan, slopeRunGap, bridgeDensity);
        if (resolvedTiles != null && resolvedTiles.isLavaTheme())
            replaceDocumentVolcanoTrees(doc, effectiveTreeDensity(doc.spec),
                    doc.spec.complexity, resolvedTiles.trees.size());
        placeDocumentProps(doc);
        applyThemeContactMetrics(doc, tiles);
        applySurfaceMaterial(doc, resolvedTiles == null
                ? CustomMapDocument.SURFACE_NORMAL
                : resolvedTiles.surfaceMaterial);
        if (mixedIceSurface)
            applyIceSurfaceDensity(doc, input.iceSurfaceDensity);
        applyFloatingIslandMaterialPolicy(doc, resolvedTiles != null
                && resolvedTiles.snowOnlyFloatingIslands);
        if (resolvedTiles != null)
            try {
                CustomMapChunkWriter.assignFloatingIslandCollisionProfiles(
                        doc, resolvedTiles);
            } catch (IOException ex) {
                throw new IllegalArgumentException(
                        "Could not inspect floating-island collision artwork.", ex);
            }
        doc.backgroundRevision = CustomMapDocument.BACKGROUND_REVISION;
        doc.backgroundManifest = BackgroundComposer.compose(doc.spec, resolvedTiles);
        return doc;
    }

    public static void validateSpec(MapSpec spec) {
        if (spec == null) throw new IllegalArgumentException("Map settings are missing.");
        if (cleanName(spec.name).isEmpty()) throw new IllegalArgumentException("Map name is required.");
        if (spec.biome == null || spec.biome.trim().isEmpty()) throw new IllegalArgumentException("Select a biome.");
        if (spec.width < 30 || spec.width > 120)
            throw new IllegalArgumentException("Width must be between 30 and 120 tiles.");
        if (spec.height < MIN_MAP_HEIGHT || spec.height > MAX_MAP_HEIGHT)
            throw new IllegalArgumentException("Height must be between "
                    + MIN_MAP_HEIGHT + " and " + MAX_MAP_HEIGHT + " tiles.");
        checkDensity("Ground", spec.groundDensity);
        checkDensity("Water", spec.waterDensity);
        checkDensity("Tree", spec.treeDensity);
        checkDensity("Prop", spec.propDensity);
        if (Double.isNaN(spec.iceSurfaceDensity)
                || spec.iceSurfaceDensity < 0.0 || spec.iceSurfaceDensity > 70.0)
            throw new IllegalArgumentException(
                    "Ice surface density must be from 0% to 70%.");
        checkDensity("Ice bridge", spec.iceBridgeDensity);
        checkDensity("Floating island", spec.floatingIslandDensity);
        if (spec.floatingIslandCount < -1
                || spec.floatingIslandCount > MAX_FLOATING_ISLAND_COUNT)
            throw new IllegalArgumentException("Floating island count must be between 0 and "
                    + MAX_FLOATING_ISLAND_COUNT + ".");
        int maxIslandLayers = maxFloatingIslandLayers(spec.height);
        if (spec.floatingIslandLayers < -1
                || spec.floatingIslandLayers > maxIslandLayers)
            throw new IllegalArgumentException("Floating island layers must be between 0 and "
                    + maxIslandLayers + " for a " + spec.height + "-tile map.");
        int maximumTerrainY = Math.min(MAX_GROUND_HEIGHT, spec.height);
        if (spec.slopeMinY < 2 || spec.slopeMinY > maximumTerrainY)
            throw new IllegalArgumentException("Slope lowest Y must be between 2 and "
                    + maximumTerrainY + ".");
        if (spec.slopeMaxY < 2 || spec.slopeMaxY > maximumTerrainY)
            throw new IllegalArgumentException("Slope highest Y must be between 2 and "
                    + maximumTerrainY + ".");
        if (spec.slopeMinY > spec.slopeMaxY)
            throw new IllegalArgumentException("Slope lowest Y cannot exceed highest Y.");
        if (spec.slopeCount < 0 || spec.slopeCount > 120)
            throw new IllegalArgumentException("Slope count must be between 0 and 120.");
        if (Double.isNaN(spec.slopeCoverage)
                || spec.slopeCoverage < 0.0 || spec.slopeCoverage > 80.0)
            throw new IllegalArgumentException("Slope coverage must be from 0% to 80%.");
        if (spec.slopeMinRise < 1 || spec.slopeMinRise > 10
                || spec.slopeMaxRise < 1 || spec.slopeMaxRise > 10
                || spec.slopeMinRise > spec.slopeMaxRise)
            throw new IllegalArgumentException(
                    "Slope rise must be between 1 and 10 tiles, with minimum not exceeding maximum.");
        if (spec.slopeMinLength < 1 || spec.slopeMinLength > 60
                || spec.slopeMaxLength < 1 || spec.slopeMaxLength > 60
                || spec.slopeMinLength > spec.slopeMaxLength)
            throw new IllegalArgumentException(
                    "Slope length must be between 1 and 60 tiles, with minimum not exceeding maximum.");
        if (spec.complexity < 0 || spec.complexity > 100)
            throw new IllegalArgumentException("Complexity must be from 0 to 100.");
        if (spec.groundDensity < 8.0 || spec.groundDensity > 85.0)
            throw new IllegalArgumentException("Ground density must be between 8% and 85% for a playable side-view map.");
        if (spec.waterDensity > 60.0)
            throw new IllegalArgumentException("Water density above 60% cannot guarantee a traversable route.");
    }

    private static void checkDensity(String name, double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 100.0)
            throw new IllegalArgumentException(name + " density must be from 0% to 100%.");
    }

    public static double effectiveWaterDensity(MapSpec spec) {
        double factor = 0.35 + 1.30 * complexity01(spec);
        return Math.min(60.0, Math.max(0.0, spec.waterDensity * factor));
    }

    public static double effectiveTreeDensity(MapSpec spec) {
        double factor = 0.25 + 1.50 * complexity01(spec);
        return Math.min(100.0, Math.max(0.0, spec.treeDensity * factor));
    }

    public static double effectivePropDensity(MapSpec spec) {
        return Math.min(100.0, Math.max(0.0, spec == null ? 0.0 : spec.propDensity));
    }

    public static double effectiveFloatingIslandDensity(MapSpec spec) {
        return Math.min(100.0, Math.max(0.0,
                (spec == null ? 25.0 : spec.floatingIslandDensity) * complexity01(spec)));
    }

    public static int maxFloatingIslandLayers(int height) {
        int mainSurface = Math.max(0, height - 2);
        int highestRow = mainSurface - FLOATING_ISLAND_THICKNESS
                - FLOATING_ISLAND_GROUND_HEADROOM;
        if (highestRow < FLOATING_ISLAND_TOP_MARGIN) return 0;
        int stride = FLOATING_ISLAND_THICKNESS + FLOATING_ISLAND_LAYER_GAP;
        return 1 + (highestRow - FLOATING_ISLAND_TOP_MARGIN) / stride;
    }

    public static int requestedFloatingIslandCount(MapSpec spec) {
        if (spec == null) return 0;
        if (spec.floatingIslandLayers == 0) return 0;
        if (spec.floatingIslandCount >= 0)
            return Math.min(MAX_FLOATING_ISLAND_COUNT, spec.floatingIslandCount);
        int wantedCells = (int) Math.round(Math.max(1, spec.width)
                * effectiveFloatingIslandDensity(spec) / 100.0);
        int averageWidth = spec.complexity >= 70 ? 4 : 3;
        return Math.min(MAX_FLOATING_ISLAND_COUNT,
                Math.max(0, Math.round(wantedCells / (float) averageWidth)));
    }

    public static int requestedFloatingIslandLayers(MapSpec spec) {
        int count = requestedFloatingIslandCount(spec);
        if (count <= 0 || spec == null) return 0;
        int maximum = maxFloatingIslandLayers(spec.height);
        if (spec.floatingIslandLayers >= 0)
            return Math.min(count, Math.min(maximum, spec.floatingIslandLayers));
        int automatic = spec.complexity >= 70 ? 3 : count >= 2 ? 2 : 1;
        return Math.min(count, Math.min(maximum, automatic));
    }

    private static double complexity01(MapSpec spec) {
        return Math.max(0.0, Math.min(1.0, (spec == null ? 50 : spec.complexity) / 100.0));
    }

    private static final String[] TIER_NAMES = {
            "Flat", "Sparse Slopes", "Rolling", "Low Hills", "Peaks & Valleys",
            "Terraces", "Slope Chains", "Compound Hills", "Dense Mixed", "Extreme"
    };
    private static final int[] TRANSITION_MIN = {0, 1, 2, 4, 5, 7, 9, 12, 15, 20};
    private static final int[] TRANSITION_MAX = {0, 2, 4, 6, 8, 10, 13, 16, 20, 28};
    private static final int[] SPAN_MIN = {0, 1, 2, 3, 4, 4, 5, 6, 7, 8};
    private static final int[] SPAN_MAX = {1, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    private static final int[] ISLAND_MIN = {0, 0, 1, 1, 2, 2, 3, 4, 5, 6};
    private static final int[] ISLAND_MAX = {0, 1, 1, 2, 2, 3, 4, 5, 7, 9};
    private static final int[] WATER_ZONE_MIN = {1, 1, 1, 1, 2, 2, 3, 3, 4, 4};
    private static final int[] WATER_ZONE_MAX = {1, 1, 2, 2, 3, 3, 4, 5, 5, 6};
    private static final int[] BATTLE_FEATURE_MIN = {0, 0, 0, 0, 1, 1, 1, 2, 2, 3};
    private static final int[] BATTLE_FEATURE_MAX = {0, 0, 0, 1, 1, 1, 2, 2, 3, 4};
    private static final int[] MAX_CHAIN = {1, 1, 1, 2, 2, 3, 4, 5, 6, 7};

    public static int complexityTier(int complexity) {
        return Math.min(10, Math.max(1, Math.max(0, Math.min(100, complexity)) / 10 + 1));
    }

    public static String complexityTierName(int complexity) {
        return TIER_NAMES[complexityTier(complexity) - 1];
    }

    private static TierBudget tierBudget(int complexity, int width) {
        int tier = complexityTier(complexity);
        int index = tier - 1;
        int start = index * 10;
        int end = tier == 10 ? 100 : start + 9;
        float progress = end <= start ? 1f
                : (Math.max(start, Math.min(end, complexity)) - start) / (float) (end - start);
        float widthScale = width / 120f;
        TierBudget out = new TierBudget();
        out.tier = tier;
        out.name = TIER_NAMES[index];
        out.transitionTarget = scaleBudget(lerp(TRANSITION_MIN[index],
                TRANSITION_MAX[index], progress), widthScale, tier > 1);
        out.spanTarget = Math.round(lerp(SPAN_MIN[index], SPAN_MAX[index], progress));
        out.islandTarget = scaleBudget(lerp(ISLAND_MIN[index],
                ISLAND_MAX[index], progress), widthScale, tier >= 3);
        out.waterZoneTarget = scaleBudget(lerp(WATER_ZONE_MIN[index],
                WATER_ZONE_MAX[index], progress), widthScale, true);
        out.battleFeatureTarget = scaleBudget(lerp(BATTLE_FEATURE_MIN[index],
                BATTLE_FEATURE_MAX[index], progress), widthScale, tier >= 4);
        out.maxChain = MAX_CHAIN[index];
        out.targetScore = tier == 10 ? 45.0 : index * 5.0;
        return out;
    }

    private static int scaleBudget(float value, float widthScale, boolean positiveFloor) {
        int scaled = Math.round(value * widthScale);
        return positiveFloor && value > 0f ? Math.max(1, scaled) : Math.max(0, scaled);
    }

    private static float lerp(int low, int high, float progress) {
        return low + (high - low) * Math.max(0f, Math.min(1f, progress));
    }

    private static ModeVariant generateVariant(MapSpec spec, MapMode mode, boolean slopesAvailable,
                                               int slopeSpan, int slopeRunGap) {
        return generateVariant(spec, mode, slopesAvailable, slopeSpan, slopeRunGap,
                false, 0.0);
    }

    private static ModeVariant generateVariant(MapSpec spec, MapMode mode, boolean slopesAvailable,
                                               int slopeSpan, int slopeRunGap,
                                               boolean reserveBattleBases,
                                               double iceBridgeDensity) {
        ModeVariant v = new ModeVariant();
        v.mode = mode.id;
        v.seed = mix(spec.seed, mode.id);
        v.width = spec.width;
        v.height = spec.height;
        v.cells = new int[v.width * v.height];
        v.grid.width = v.width;
        v.grid.height = v.height;
        v.grid.cells = v.cells;
        v.surface = new int[v.width];
        v.walkSurfaceLayers = new float[v.width];
        v.slopeDirection = new int[v.width];
        v.slopePhase = new int[v.width];
        v.slopeRunId = new int[v.width];
        v.water = new boolean[v.width];
        v.profile.profileId = mode.id;
        v.profile.maxJumpGap = mode == MapMode.HEIST ? 3 : mode == MapMode.ADVENTURE ? 4 : 0;
        v.profile.maxStepRows = !slopesAvailable || mode == MapMode.DUEL || mode == MapMode.DERBY
                || spec.complexity <= 15 ? 0 : 1;
        v.profile.complexity = spec.complexity;
        v.profile.treesBlockSight = mode == MapMode.HEIST;
        v.profile.waterHazard = mode == MapMode.ADVENTURE || mode == MapMode.HEIST;

        boolean flat = mode == MapMode.DUEL || mode == MapMode.DERBY;
        boolean flatMainRoute = flat || !slopesAvailable;
        double effectiveWater = effectiveWaterDensity(spec);
        double effectiveTrees = effectiveTreeDensity(spec);
        int requestedIslands = requestedFloatingIslandCount(spec);
        int requestedIslandLayers = requestedFloatingIslandLayers(spec);
        TerrainPlan plan = planTerrain(v, spec.groundDensity, effectiveWater,
                new Random(mix(v.seed, "water")),
                new Random(mix(v.seed, "main-terrain")),
                new Random(mix(v.seed, "islands")),
                mode, flatMainRoute, spec.complexity, slopeSpan,
                slopeRunGap,
                spec.slopeMinY, spec.slopeMaxY, spec.slopeCount,
                spec.slopeCoverage,
                spec.slopeMinRise, spec.slopeMaxRise,
                spec.slopeMinLength, spec.slopeMaxLength,
                requestedIslands, requestedIslandLayers, reserveBattleBases);
        rasterize(v, plan);
        IceBridgeBuilder.apply(v, iceBridgeDensity, mode.id);

        float spawnRatio = mode == MapMode.DUEL ? 0.42f : mode == MapMode.DERBY ? 0.15f : 0.08f;
        float goalRatio = mode == MapMode.DUEL ? 0.58f : mode == MapMode.DERBY ? 0.85f : 0.92f;
        int spawnX = Math.max(2, Math.min(v.width - 3, Math.round(v.width * spawnRatio)));
        int goalX = Math.max(spawnX + 5, Math.min(v.width - 3, Math.round(v.width * goalRatio)));
        v.spawn = snap(v, spawnX);
        v.destination = snap(v, goalX);
        buildCheckpoints(v);
        buildSurfaceGraph(v, mode);
        rebuildSecondaryPlatforms(v);
        placeTrees(v, effectiveTrees, new Random(mix(v.seed, "objects")), spec.complexity);
        placeEnemies(v, spec, mode, new Random(mix(v.seed, "enemies")));
        recomputeMetrics(v);
        recomputeSlopeCoverage(v, spec, slopeSpan, reserveBattleBases);
        recomputeComplexity(v);
        if (!reserveBattleBases) appendFinalSlopeCap(v, spec, slopesAvailable,
                slopeSpan, reserveBattleBases);
        validate(v, mode);
        return v;
    }

    private static ModeVariant generateBattleTerrain(MapSpec spec, boolean slopesAvailable,
                                                     int slopeSpan, int slopeRunGap,
                                                     double iceBridgeDensity) {
        MapSpec battleSpec = copy(spec);
        TierBudget budget = tierBudget(spec.complexity, spec.width);
        if (battleSpec.slopeCoverage <= 0.0 && budget.battleFeatureTarget > 0) {
            int battleSlopeBudget = Math.max(1, spec.width / 20);
            battleSpec.slopeCount = Math.min(battleSpec.slopeCount,
                    battleSlopeBudget);
        }
        battleSpec.seed = mix(spec.seed, "normal-bcu-stage");
        ModeVariant v = generateVariant(battleSpec, MapMode.ADVENTURE,
                slopesAvailable, slopeSpan, slopeRunGap, true, 0.0);
        v.mode = "battle";
        v.seed = mix(spec.seed, "battle");
        v.profile.profileId = "battle";
        v.profile.maxJumpGap = 4;
        v.profile.maxStepRows = 5;
        v.profile.treesBlockSight = false;
        v.profile.waterHazard = false;
        v.enemies.clear();

        removeFloatingTerrain(v);
        v.trees.clear();

        int spawnX = Math.max(3, Math.min(v.width - 4, Math.round(v.width * 0.08f)));
        int goalX = Math.max(spawnX + 8,
                Math.min(v.width - 4, Math.round(v.width * 0.92f)));

        v.spawn = snap(v, spawnX);
        v.destination = snap(v, goalX);
        enforceBaseSafeZone(v, v.spawn, "enemy");
        enforceBaseSafeZone(v, v.destination, "player");

        int chasmWanted = budget.battleFeatureTarget == 0 ? 0
                : Math.max(1, (budget.battleFeatureTarget + 1) / 2);
        int stepWanted = Math.max(0, budget.battleFeatureTarget - chasmWanted);

        int chasms = addBattleChasms(v, chasmWanted,
                new Random(mix(v.seed, "battle-chasms")), spec, slopeSpan);
        int steps = addBattleSteps(v, stepWanted,
                new Random(mix(v.seed, "battle-steps")));

        int missing = budget.battleFeatureTarget - steps - chasms;
        if (missing > 0) {
            int extraChasms = addBattleChasms(v, missing,
                    new Random(mix(v.seed, "battle-fill-chasms")), spec, slopeSpan);
            chasms += extraChasms;
            missing -= extraChasms;
        }
        if (missing > 0)
            steps += addBattleSteps(v, missing,
                    new Random(mix(v.seed, "battle-fill-steps")));
        if (steps + chasms < budget.battleFeatureTarget)
            appendCapReason(v, "Normal-stage space capped chasm/step groups at "
                    + (steps + chasms) + " of " + budget.battleFeatureTarget + ".");

        normalizeWaterLevels(v);
        IceBridgeBuilder.apply(v, iceBridgeDensity, "battle");
        placeFloatingPlatforms(v, new Random(mix(v.seed, "battle-islands")),
                spec.complexity, requestedFloatingIslandCount(spec),
                requestedFloatingIslandLayers(spec));
        placeTrees(v, effectiveTreeDensity(spec),
                new Random(mix(v.seed, "battle-objects")), spec.complexity);
        buildCheckpoints(v);
        buildBattleNavigation(v);
        rebuildSecondaryPlatforms(v);
        v.elevationChanges = countSlopeRuns(v);
        recomputeMetrics(v);
        recomputeSlopeCoverage(v, spec, slopeSpan, true);
        recomputeComplexity(v);
        appendFinalSlopeCap(v, spec, slopesAvailable, slopeSpan, true);
        validateBattle(v);
        return v;
    }

    private static void removeFloatingTerrain(ModeVariant v) {
        if (v == null || v.cells == null || v.surface == null) return;
        for (int x = 0; x < v.width; x++) {
            int limit = v.surface[x] < 0 ? v.height : v.surface[x];
            for (int y = 0; y < limit; y++)
                if (v.cell(x, y) == CustomMapDocument.CELL_GROUND)
                    v.setCell(x, y, CustomMapDocument.CELL_AIR);
        }
        if (v.motifs != null)
            for (int i = v.motifs.size() - 1; i >= 0; i--)
                if (v.motifs.get(i) != null
                        && v.motifs.get(i).type == TerrainMotifType.FLOATING_CLUSTER)
                    v.motifs.remove(i);
        v.floatingIslandCount = 0;
        v.floatingIslandLayerCount = 0;
        if (v.secondaryPlatforms != null) v.secondaryPlatforms.clear();
    }

    private static int addBattleChasms(ModeVariant v, int wanted, Random random,
                                       MapSpec spec, int slopeSpan) {
        int made = 0;
        int windowRejected = 0, flattenRejected = 0;
        int topologyRejected = 0, slopeRejected = 0, routeRejected = 0;
        int coverageRejected = 0;
        for (int attempt = 0; attempt < 512 && made < wanted; attempt++) {
            int gapWidth = CHASM_MIN_TILES
                    + random.nextInt(CHASM_MAX_TILES - CHASM_MIN_TILES + 1);
            gapWidth = Math.min(gapWidth, v.profile.maxJumpGap);
            int start = 5 + random.nextInt(Math.max(1, v.width - gapWidth - 10));
            int end = start + gapWidth - 1;
            if (insideBaseSafeZone(v, start - 2, end + 2)) continue;

            if (!isDryBattleFeatureWindow(v, start, end)) {
                windowRejected++;
                continue;
            }
            TerrainSnapshot before = new TerrainSnapshot(v);
            int row = flattenBattleWindow(v, start - 2, end + 2,
                    v.surface[start - 1]);
            if (row < 0) {
                flattenRejected++;
                before.restore(v);
                continue;
            }
            for (int x = start; x <= end; x++) {
                for (int y = 0; y < v.height; y++) v.setCell(x, y, CustomMapDocument.CELL_AIR);
                v.surface[x] = -1;
                v.walkSurfaceLayers[x] = 0f;
                v.slopeDirection[x] = 0;
                v.slopePhase[x] = 0;
                if (v.slopeRunId != null && x < v.slopeRunId.length) v.slopeRunId[x] = 0;
                v.water[x] = false;
            }
            clearObjects(v, start - 2, end + 2);
            v.motifs.add(new TerrainMotif(TerrainMotifType.CHASM,
                    start, end, row, row, 0));
            repairAuthoredMainTopology(v);
            if (AuthoredTileTopology.firstIssue(v) != null
                    || invalidSlopeJoin(v) >= 0
                    || !battleGapGeometryTraversable(v)) {
                if (AuthoredTileTopology.firstIssue(v) != null) topologyRejected++;
                else if (invalidSlopeJoin(v) >= 0) slopeRejected++;
                else routeRejected++;
                before.restore(v);
                continue;
            }
            if (exceedsSlopeCoverageTarget(v, spec, slopeSpan, true)) {
                coverageRejected++;
                before.restore(v);
                continue;
            }
            made++;
        }
        if (made < wanted)
            appendCapReason(v, "Chasm candidates rejected: window=" + windowRejected
                    + ", flatten=" + flattenRejected + ", topology=" + topologyRejected
                    + ", slope=" + slopeRejected + ", route=" + routeRejected
                    + ", slope-coverage=" + coverageRejected + ".");
        return made;
    }

    private static int addBattleSteps(ModeVariant v, int wanted, Random random) {
        int made = 0;
        int windowRejected = 0, flattenRejected = 0;
        int topologyRejected = 0, slopeRejected = 0, routeRejected = 0;
        for (int attempt = 0; attempt < 512 && made < wanted; attempt++) {
            int length = 3 + random.nextInt(4);
            int start = 5 + random.nextInt(Math.max(1, v.width - length - 10));
            int end = start + length - 1;
            if (insideBaseSafeZone(v, start - 2, end + 2)) continue;
            if (!isDryBattleFeatureWindow(v, start, end)) {
                windowRejected++;
                continue;
            }
            TerrainSnapshot before = new TerrainSnapshot(v);
            int row = flattenBattleWindow(v, start - 2, end + 2,
                    v.surface[start - 1]);
            if (row < 0) {
                flattenRejected++;
                before.restore(v);
                continue;
            }
            int maximum = Math.min(VERTICAL_STEP_MAX_TILES,
                    Math.max(VERTICAL_STEP_MIN_TILES, v.profile.maxStepRows));
            int delta = VERTICAL_STEP_MIN_TILES
                    + random.nextInt(maximum - VERTICAL_STEP_MIN_TILES + 1);
            if (random.nextBoolean()) delta = -delta;
            int shifted = clamp(row + delta, Math.max(1, v.height - 12), v.height - 2);
            if (shifted == row) {
                before.restore(v);
                continue;
            }
            for (int x = start; x <= end; x++) setMainSurface(v, x, shifted);
            clearObjects(v, start - 2, end + 2);
            TerrainMotifType entering = shifted < row
                    ? TerrainMotifType.STEP_UP : TerrainMotifType.DROP_DOWN;
            TerrainMotifType leaving = shifted < row
                    ? TerrainMotifType.DROP_DOWN : TerrainMotifType.STEP_UP;
            v.motifs.add(new TerrainMotif(entering, start - 1, start,
                    row, shifted, 1));
            v.motifs.add(new TerrainMotif(leaving, end, end + 1,
                    shifted, row, 1));
            repairAuthoredMainTopology(v);
            if (AuthoredTileTopology.firstIssue(v) != null
                    || invalidSlopeJoin(v) >= 0
                    || !battleGapGeometryTraversable(v)) {
                if (AuthoredTileTopology.firstIssue(v) != null) topologyRejected++;
                else if (invalidSlopeJoin(v) >= 0) slopeRejected++;
                else routeRejected++;
                before.restore(v);
                continue;
            }
            made++;
        }
        if (made < wanted)
            appendCapReason(v, "Step candidates rejected: window=" + windowRejected
                    + ", flatten=" + flattenRejected + ", topology=" + topologyRejected
                    + ", slope=" + slopeRejected + ", route=" + routeRejected + ".");
        return made;
    }

    private static boolean battleGapGeometryTraversable(ModeVariant v) {
        if (v == null || v.surface == null || v.water == null
                || v.spawn == null || v.destination == null) return false;
        int maxStep = Math.max(1, v.profile.maxStepRows);
        int maxJump = Math.max(1, v.profile.maxJumpGap);
        int scan = v.spawn.x;
        while (scan <= v.destination.x) {
            if (dryMain(v, scan)) {
                scan++;
                continue;
            }
            int start = scan;
            boolean allWater = true;
            boolean allVoid = true;
            while (scan <= v.destination.x && !dryMain(v, scan)) {
                allWater &= v.water[scan];
                allVoid &= !v.water[scan] && v.surface[scan] < 0;
                scan++;
            }
            int left = start - 1;
            int right = scan;
            int span = right - start;
            if (left < v.spawn.x || right > v.destination.x
                    || !dryMain(v, left) || !dryMain(v, right)
                    || Math.abs(v.surface[left] - v.surface[right]) > maxStep)
                return false;
            if (!allWater && !(allVoid && span <= maxJump)) return false;
        }
        return true;
    }

    private static void repairAuthoredMainTopology(ModeVariant v) {
        int budget = Math.max(1, v.width * 2);
        while (budget-- > 0) {
            AuthoredTileTopology.Issue issue = AuthoredTileTopology.firstIssue(v);
            if (issue == null) return;
            int x = issue.x;
            if (v.surface == null || x < 0 || x >= v.width || v.surface[x] < 0
                    || v.water[x] || insideBaseSafeZone(v, x, x)) return;
            int leftRow = repairNeighbourRow(v, x - 1);
            int rightRow = repairNeighbourRow(v, x + 1);
            int replacement;
            if (leftRow < 0 && rightRow < 0) return;
            if (leftRow < 0) replacement = rightRow;
            else if (rightRow < 0) replacement = leftRow;
            else {
                int leftRun = flatRunLength(v, x - 1, -1, leftRow);
                int rightRun = flatRunLength(v, x + 1, 1, rightRow);
                if (leftRun == rightRun)
                    replacement = Math.abs(leftRow - v.surface[x])
                            <= Math.abs(rightRow - v.surface[x]) ? leftRow : rightRow;
                else replacement = leftRun > rightRun ? leftRow : rightRow;
            }
            if (replacement == v.surface[x]) return;
            setMainSurface(v, x, replacement);
        }
    }

    private static int repairNeighbourRow(ModeVariant v, int x) {
        if (!dryMain(v, x) || v.slopeDirection[x] != 0) return -1;
        return v.surface[x];
    }

    private static int flatRunLength(ModeVariant v, int x, int direction, int row) {
        int length = 0;
        while (x >= 0 && x < v.width && dryMain(v, x)
                && v.slopeDirection[x] == 0 && v.surface[x] == row) {
            length++;
            x += direction;
        }
        return length;
    }

    private static final class TerrainSnapshot {
        final int[] cells;
        final int[] surface;
        final float[] walk;
        final int[] slopeDirection;
        final int[] slopePhase;
        final int[] slopeRunId;
        final boolean[] water;
        final ArrayList<TerrainMotif> motifs;

        TerrainSnapshot(ModeVariant v) {
            cells = v.cells.clone();
            surface = v.surface.clone();
            walk = v.walkSurfaceLayers.clone();
            slopeDirection = v.slopeDirection.clone();
            slopePhase = v.slopePhase.clone();
            slopeRunId = v.slopeRunId == null ? null : v.slopeRunId.clone();
            water = v.water.clone();
            motifs = new ArrayList<TerrainMotif>(v.motifs);
        }

        void restore(ModeVariant v) {
            System.arraycopy(cells, 0, v.cells, 0, cells.length);
            System.arraycopy(surface, 0, v.surface, 0, surface.length);
            System.arraycopy(walk, 0, v.walkSurfaceLayers, 0, walk.length);
            System.arraycopy(slopeDirection, 0, v.slopeDirection, 0,
                    slopeDirection.length);
            System.arraycopy(slopePhase, 0, v.slopePhase, 0, slopePhase.length);
            if (slopeRunId != null && v.slopeRunId != null)
                System.arraycopy(slopeRunId, 0, v.slopeRunId, 0,
                        slopeRunId.length);
            System.arraycopy(water, 0, v.water, 0, water.length);
            v.motifs.clear();
            v.motifs.addAll(motifs);
        }
    }

    private static boolean isDryBattleFeatureWindow(ModeVariant v, int start, int end) {
        int left = start - 2;
        int right = end + 2;
        int row = -1;
        for (int x = left; x <= right; x++) {
            if (x < 0 || x >= v.width || v.surface[x] < 0 || v.water[x]) return false;
            if (v.slopeDirection != null && v.slopeDirection[x] != 0) return false;
            if (row < 0) row = v.surface[x];
            else if (v.surface[x] != row) return false;
        }
        return !hasFloatingGround(v, left, right);
    }

    private static int flattenBattleWindow(ModeVariant v, int start, int end, int row) {
        int flatRow = -1;
        for (int x = start; x <= end; x++) {
            if (!dryMain(v, x)
                    || v.slopeDirection != null && v.slopeDirection[x] != 0)
                return -1;
            if (flatRow < 0) flatRow = v.surface[x];
            else if (v.surface[x] != flatRow) return -1;
        }

        if (leavesSingleDryShoulder(v, start, end)
                || hasFloatingGround(v, start - 1, end + 1)
                || intersectsBattleFeature(v, start - 1, end + 1)) return -1;
        if (insideBaseSafeZone(v, start, end)) return -1;
        int lower = Math.max(1, v.height - 12);
        int upper = v.height - 2;
        int maxStep = Math.min(VERTICAL_STEP_MAX_TILES,
                Math.max(VERTICAL_STEP_MIN_TILES, v.profile.maxStepRows));
        if (start > 0 && v.surface[start - 1] >= 0) {
            lower = Math.max(lower, v.surface[start - 1] - maxStep);
            upper = Math.min(upper, v.surface[start - 1] + maxStep);
        }
        if (end + 1 < v.width && v.surface[end + 1] >= 0) {
            lower = Math.max(lower, v.surface[end + 1] - maxStep);
            upper = Math.min(upper, v.surface[end + 1] + maxStep);
        }
        if (lower > upper) return -1;
        int preferred = clamp(row, lower, upper);
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        float contactLimit = maxStep * v.layerUnitsPerTile() + 0.01f;
        for (int candidate = lower; candidate <= upper; candidate++) {
            float contact = rowToLayer(v, candidate);
            if (start > 0 && dryMain(v, start - 1)
                    && Math.abs(contact - v.walkSurfaceLayers[start - 1]) > contactLimit)
                continue;
            if (end + 1 < v.width && dryMain(v, end + 1)
                    && Math.abs(contact - v.walkSurfaceLayers[end + 1]) > contactLimit)
                continue;
            int distance = Math.abs(candidate - preferred);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best < 0) return -1;
        row = best;
        for (int x = start; x <= end; x++) setMainSurface(v, x, row);
        clearObjects(v, start, end);
        removeOverlappingTerrainMotifs(v, start, end);
        return row;
    }

    private static boolean leavesSingleDryShoulder(ModeVariant v, int start, int end) {
        boolean singleLeft = start > 0 && dryMain(v, start - 1)
                && (start < 2 || !dryMain(v, start - 2));
        boolean singleRight = end + 1 < v.width && dryMain(v, end + 1)
                && (end + 2 >= v.width || !dryMain(v, end + 2));
        return singleLeft || singleRight;
    }

    private static boolean hasFloatingGround(ModeVariant v, int start, int end) {
        if (v == null || v.surface == null) return false;
        for (int x = Math.max(0, start); x <= Math.min(v.width - 1, end); x++) {
            int main = v.surface[x];
            int limit = main < 0 ? v.height : main;
            for (int y = 0; y < limit; y++)
                if (v.cell(x, y) == CustomMapDocument.CELL_GROUND) return true;
        }
        return false;
    }

    private static boolean intersectsBattleFeature(ModeVariant v, int start, int end) {
        if (v == null || v.motifs == null) return false;
        for (TerrainMotif motif : v.motifs) {
            if (motif == null || motif.endX < start || motif.startX > end) continue;
            if (motif.type == TerrainMotifType.STEP_UP
                    || motif.type == TerrainMotifType.DROP_DOWN
                    || motif.type == TerrainMotifType.CHASM) return true;
        }
        return false;
    }

    private static void removeOverlappingTerrainMotifs(ModeVariant v, int start, int end) {
        if (v.motifs == null) return;
        for (int i = v.motifs.size() - 1; i >= 0; i--) {
            TerrainMotif motif = v.motifs.get(i);
            if (motif == null || motif.endX < start || motif.startX > end) continue;
            if (motif.type == TerrainMotifType.RAMP
                    || motif.type == TerrainMotifType.RAMP_CHAIN
                    || motif.type == TerrainMotifType.PEAK
                    || motif.type == TerrainMotifType.VALLEY
                    || motif.type == TerrainMotifType.TERRACE
                    || motif.type == TerrainMotifType.FLAT)
                v.motifs.remove(i);
        }
    }

    private static void setMainSurface(ModeVariant v, int x, int row) {
        if (x < 0 || x >= v.width || v.water[x]) return;
        int old = v.surface[x];
        if (old >= 0) for (int y = old; y < v.height; y++)
            v.setCell(x, y, CustomMapDocument.CELL_AIR);
        for (int y = row; y < v.height; y++)
            v.setCell(x, y, CustomMapDocument.CELL_GROUND);
        v.surface[x] = row;
        v.walkSurfaceLayers[x] = rowToLayer(v, row);
        v.slopeDirection[x] = 0;
        v.slopePhase[x] = 0;
        if (v.slopeRunId != null && x < v.slopeRunId.length) v.slopeRunId[x] = 0;
    }

    private static boolean insideBaseSafeZone(ModeVariant v, int start, int end) {
        if (v.baseSafeZones == null) return false;
        for (CustomMapDocument.BaseSafeZone zone : v.baseSafeZones)
            if (zone != null && start <= zone.endX + 2 && end >= zone.startX - 2)
                return true;
        return false;
    }

    private static void clearObjects(ModeVariant v, int start, int end) {
        for (int i = v.trees.size() - 1; i >= 0; i--) {
            int x = v.trees.get(i).x;
            if (x >= start && x <= end) v.trees.remove(i);
        }
    }

    private static void enforceBaseSafeZone(ModeVariant v, MapAnchor anchor, String role) {
        if (v == null || anchor == null) return;
        int half = 4;
        int center = clamp(anchor.x, half, v.width - half - 1);
        int row = v.surface[center];
        if (row < 0) {
            MapAnchor snapped = snap(v, center);
            row = clamp(snapped.y + 1, Math.max(0, v.height - 12), v.height - 2);
        }
        row = clamp(row, Math.max(0, v.height - 12), v.height - 2);
        int start = center - half;
        int end = center + half;

        int lower = Math.max(0, v.height - 12);
        int upper = v.height - 2;
        if (start > 0 && v.surface[start - 1] >= 0 && !v.water[start - 1]) {
            lower = Math.max(lower, v.surface[start - 1] - VERTICAL_STEP_MAX_TILES);
            upper = Math.min(upper, v.surface[start - 1] + VERTICAL_STEP_MAX_TILES);
        }
        if (end + 1 < v.width && v.surface[end + 1] >= 0 && !v.water[end + 1]) {
            lower = Math.max(lower, v.surface[end + 1] - VERTICAL_STEP_MAX_TILES);
            upper = Math.min(upper, v.surface[end + 1] + VERTICAL_STEP_MAX_TILES);
        }
        if (lower <= upper) row = clamp(row, lower, upper);
        for (int x = start; x <= end; x++) {
            for (int y = 0; y < v.height; y++)
                v.setCell(x, y, y >= row
                        ? CustomMapDocument.CELL_GROUND : CustomMapDocument.CELL_AIR);
            v.surface[x] = row;
            v.walkSurfaceLayers[x] = rowToLayer(v, row);
            v.slopeDirection[x] = 0;
            v.slopePhase[x] = 0;
            if (v.slopeRunId != null && x < v.slopeRunId.length) v.slopeRunId[x] = 0;
            v.water[x] = false;
        }
        anchor.x = center;
        anchor.y = row - 1;
        persistBaseSafeZone(v, anchor, role);
    }

    private static void persistBaseSafeZone(ModeVariant v, MapAnchor anchor, String role) {
        if (v == null || anchor == null || role == null) return;
        int half = 4;
        int start = anchor.x - half;
        int end = anchor.x + half;
        if (v.baseSafeZones == null)
            v.baseSafeZones = new ArrayList<CustomMapDocument.BaseSafeZone>();
        for (int i = v.baseSafeZones.size() - 1; i >= 0; i--)
            if (role.equals(v.baseSafeZones.get(i).role)) v.baseSafeZones.remove(i);
        v.baseSafeZones.add(new CustomMapDocument.BaseSafeZone(role, anchor.x, start, end,
                v.walkLayerAtTile(anchor.x)));
    }

    private static TerrainPlan planTerrain(ModeVariant v, double groundPct, double waterPct,
                                           Random waterRandom, Random terrainRandom,
                                           Random islandRandom, MapMode mode, boolean flat,
                                           int complexity, int slopeSpan,
                                           int slopeRunGap,
                                           int slopeMinY, int slopeMaxY, int slopeCount,
                                           double slopeCoverage,
                                           int slopeMinRise, int slopeMaxRise,
                                           int slopeMinLength, int slopeMaxLength,
                                           int requestedIslandCount,
                                           int requestedIslandLayers,
                                           boolean reserveBattleBases) {
        TierBudget budget = tierBudget(complexity, v.width);
        TerrainPlan plan = new TerrainPlan(v.width, v.height);
        plan.requestedTier = budget.tier;
        plan.targetScore = budget.targetScore;
        planWater(plan, waterPct, waterRandom, mode, complexity,
                reserveBattleBases ? 7 : 3);
        int dry = 0;
        for (boolean water : plan.water) if (!water) dry++;
        int requestedSlopeSections = Math.max(0, slopeCount);
        double requestedSlopeCoverage = Math.max(0.0, Math.min(80.0, slopeCoverage));
        int islandCountTarget = mode == MapMode.DUEL || mode == MapMode.DERBY
                ? 0 : Math.max(0, requestedIslandCount);
        int islandLayerTarget = islandCountTarget == 0 ? 0
                : Math.max(1, Math.min(requestedIslandLayers, islandCountTarget));
        int estimatedIslandCells = estimatedFloatingIslandCells(v.width,
                islandCountTarget, islandLayerTarget, complexity);
        int terrainBandHeight = Math.min(v.height, MAX_GROUND_HEIGHT);
        int targetGround = (int) Math.round(v.width * terrainBandHeight
                * groundPct / 100.0);
        int lowestY = clamp(slopeMinY, 2, v.height);
        int highestY = clamp(slopeMaxY, lowestY, v.height);
        int minRow = v.height - highestY;
        int maxRow = v.height - lowestY;
        int verticalEnvelope = Math.max(0, maxRow - minRow);
        int nativeSpan = Math.max(1, slopeSpan);
        int minRiseFromLength = Math.max(1,
                (Math.max(1, slopeMinLength) + nativeSpan - 1) / nativeSpan);
        int maxRiseFromLength = Math.max(1,
                Math.max(1, slopeMaxLength) / nativeSpan);
        budget.minChain = Math.max(1, Math.max(slopeMinRise, minRiseFromLength));
        budget.maxChain = Math.max(1, Math.min(slopeMaxRise, maxRiseFromLength));
        if (verticalEnvelope > 0)
            budget.maxChain = Math.min(budget.maxChain, verticalEnvelope);
        if (budget.minChain > budget.maxChain) {
            int closest = Math.max(1, Math.min(verticalEnvelope, budget.maxChain));
            budget.minChain = closest;
            budget.maxChain = closest;
            plan.capReason = appendReason(plan.capReason,
                    "The requested slope rise/length has no exact contour for this theme; "
                            + "using the closest legal compound slope of " + closest
                            + " tile(s) rise and " + (closest * nativeSpan)
                            + " tile(s) length.");
        }
        int thickness = clamp((int) Math.round((targetGround - estimatedIslandCells)
                / (double) Math.max(1, dry)), lowestY, highestY);
        int baseRow = v.height - thickness;
        for (int x = 0; x < v.width; x++) plan.rows[x] = plan.water[x] ? Float.NaN : baseRow;

        ArrayList<DrySegment> segments = new ArrayList<DrySegment>();
        int x = 0;
        while (x < v.width) {
            while (x < v.width && plan.water[x]) x++;
            int start = x;
            while (x < v.width && !plan.water[x]) x++;
            int end = x - 1;
            if (start > end) continue;
            int leftFlat = start > 0 && plan.water[start - 1] ? 2 : 4;
            int rightFlat = end + 1 < plan.width && plan.water[end + 1] ? 2 : 4;
            addDrySegments(segments, start, end, leftFlat, rightFlat,
                    slopeSpan, slopeRunGap, budget.maxChain,
                    reserveBattleBases, plan.width);
        }
        int capacity = 0;
        for (DrySegment segment : segments) capacity += segment.capacity;
        int coverageSlopeSections = (int) Math.round(capacity
                * requestedSlopeCoverage / 100.0);
        if (requestedSlopeCoverage > 0.0 && capacity > 0)
            coverageSlopeSections = Math.max(1, coverageSlopeSections);
        boolean coverageDriven = requestedSlopeCoverage > 0.0;
        budget.transitionTarget = coverageDriven
                ? coverageSlopeSections : requestedSlopeSections;

        if (!flat && budget.transitionTarget > 0 && highestY > lowestY) {
            int wanted = Math.min(budget.transitionTarget, capacity);
            if (wanted < budget.transitionTarget)
                plan.capReason = appendReason(plan.capReason,
                        "Width/height, water banks and reserved base areas cap slope count at "
                                + wanted + " of " + budget.transitionTarget
                                + (coverageDriven
                                ? " section(s) needed for the "
                                + oneDecimal(requestedSlopeCoverage) + "% coverage target."
                                : " requested legacy section(s)."));

            ArrayList<DrySegment> allocationOrder = new ArrayList<DrySegment>(segments);
            Collections.shuffle(allocationOrder, terrainRandom);
            int allocated = 0;
            while (allocated < wanted) {
                boolean changed = false;
                for (DrySegment segment : allocationOrder) {
                    if (allocated >= wanted) break;
                    if (segment.allocated < segment.capacity) {
                        segment.allocated++;
                        allocated++;
                        changed = true;
                    }
                }
                if (!changed) break;
            }

            int ordinal = 0;
            int carriedRow = baseRow;
            for (DrySegment segment : segments) {
                if (segment.allocated > 0) {
                    carriedRow = buildSegmentGrammar(plan, segment, carriedRow,
                            minRow, maxRow, slopeSpan, slopeRunGap,
                            budget, terrainRandom, ordinal++);
                } else {
                    for (int px = segment.start; px <= segment.end; px++)
                        plan.rows[px] = carriedRow;
                }
            }
        } else if (budget.transitionTarget > 0) {
            if (mode == MapMode.DUEL || mode == MapMode.DERBY)
                plan.capReason = appendReason(plan.capReason,
                        "Duel/Derby intentionally cap slope count at 0 of "
                                + budget.transitionTarget + ".");
            else if (flat)
                plan.capReason = appendReason(plan.capReason,
                        "Bidirectional slope assets are unavailable; slope count is 0 of "
                                + budget.transitionTarget + ".");
            else
                plan.capReason = appendReason(plan.capReason,
                        "Slope Y range " + lowestY + ".." + highestY
                                + " has no elevation change; slope count is 0 of "
                                + budget.transitionTarget + ".");
        }

        int bankStep = mode == MapMode.HEIST ? 3 : mode == MapMode.ADVENTURE ? 5 : Integer.MAX_VALUE;
        balanceGroundDensity(plan, baseRow, targetGround - estimatedIslandCells,
                minRow, maxRow, bankStep);
        if (islandCountTarget > 0 && islandLayerTarget > 0)
            planIslands(plan, islandRandom, complexity, islandCountTarget,
                    islandLayerTarget, reserveBattleBases);
        balanceGroundDensity(plan, baseRow,
                targetGround - plannedIslandCells(plan.islands),
                minRow, maxRow, bankStep);
        recordWaterAndFlatMotifs(plan);
        refreshMotifRows(plan);
        return plan;
    }

    private static int slopeCapacity(int length, int slopeSpan, int slopeRunGap, int maxChain,
                                     int leftFlat, int rightFlat) {
        int available = length - leftFlat - rightFlat;
        int used = 0;
        int transitions = 0;
        int chain = 0;
        while (true) {
            int separator = transitions == 0 ? 0 : chain >= maxChain ? 4 : 0;
            if (used + separator + slopeSpan > available) break;
            used += separator + slopeSpan;
            transitions++;
            chain = chain >= maxChain ? 1 : chain + 1;
        }
        return transitions;
    }

    private static void addDrySegments(List<DrySegment> out, int start, int end,
                                       int leftFlat, int rightFlat, int slopeSpan,
                                       int slopeRunGap, int maxChain,
                                       boolean reserveBattleBases,
                                       int width) {
        if (!reserveBattleBases) {
            addSlopeSegment(out, start, end, leftFlat, rightFlat,
                    slopeSpan, slopeRunGap, maxChain);
            return;
        }
        int spawn = clamp(Math.round(width * .08f), 4, width - 5);
        int goal = clamp(Math.max(spawn + 8, Math.round(width * .92f)),
                4, width - 5);
        int[] centers = {spawn, goal};
        int cursor = start;
        for (int center : centers) {
            int reservedStart = Math.max(start, center - 5);
            int reservedEnd = Math.min(end, center + 5);
            if (reservedEnd < cursor || reservedStart > end) continue;
            if (reservedStart > cursor)
                addSlopeSegment(out, cursor, reservedStart - 1,
                        cursor == start ? leftFlat : 4, 4,
                        slopeSpan, slopeRunGap, maxChain);
            int protectedStart = Math.max(cursor, reservedStart);
            if (protectedStart <= reservedEnd)
                out.add(new DrySegment(protectedStart, reservedEnd, 0, 0, 0));
            cursor = Math.max(cursor, reservedEnd + 1);
        }
        if (cursor <= end)
            addSlopeSegment(out, cursor, end, cursor == start ? leftFlat : 4,
                    rightFlat, slopeSpan, slopeRunGap, maxChain);
    }

    private static void addSlopeSegment(List<DrySegment> out, int start, int end,
                                        int leftFlat, int rightFlat, int slopeSpan,
                                        int slopeRunGap, int maxChain) {
        if (start > end) return;
        int capacity = slopeCapacity(end - start + 1, slopeSpan,
                slopeRunGap, maxChain, leftFlat, rightFlat);
        out.add(new DrySegment(start, end, leftFlat, rightFlat, capacity));
    }

    private static int buildSegmentGrammar(TerrainPlan plan, DrySegment segment,
                                           int startRow, int minRow, int maxRow,
                                           int slopeSpan, int slopeRunGap,
                                           TierBudget budget,
                                           Random random, int ordinal) {
        int transitions = segment.allocated;
        int cursor = segment.start + segment.leftFlat;
        int limit = segment.end - segment.rightFlat;
        int current = clamp(startRow, minRow, maxRow);
        for (int x = segment.start; x <= segment.end; x++) plan.rows[x] = current;

        int direction = (ordinal & 1) == 0 ? -1 : 1;
        int remaining = transitions;
        int group = 0;

        while (remaining > 0 && cursor + slopeSpan - 1 <= limit) {
            int widthRuns = Math.max(0, (limit - cursor + 1) / slopeSpan);
            int chain = Math.min(Math.min(budget.maxChain, remaining), widthRuns);
            if (chain <= 0) break;

            int upSpace = Math.max(0, current - minRow);
            int downSpace = Math.max(0, maxRow - current);
            int preferredSpace = direction < 0 ? upSpace : downSpace;
            int oppositeSpace = direction < 0 ? downSpace : upSpace;
            if (preferredSpace < chain && oppositeSpace > preferredSpace) {
                direction = -direction;
                preferredSpace = oppositeSpace;
            }
            chain = Math.min(chain, preferredSpace);
            if (chain <= 0) break;
            if (chain < budget.minChain && remaining >= budget.minChain) {
                plan.capReason = appendReason(plan.capReason,
                        "Available width/elevation forced one compound slope below the requested "
                                + budget.minChain + "-tile minimum rise.");
            }
            int motifStart = cursor;
            int motifStartRow = current;

            for (int run = 0; run < chain; run++) {
                writeSlopeRun(plan, cursor, current, direction, slopeSpan);
                current = clamp(current + direction, minRow, maxRow);
                cursor += slopeSpan;
                remaining--;
            }

            TerrainMotifType type = chain == 1
                    ? TerrainMotifType.RAMP : TerrainMotifType.RAMP_CHAIN;
            plan.motifs.add(new TerrainMotif(type, motifStart, cursor - 1,
                    motifStartRow, current, chain));

            if (remaining > 0) {
                int plateau = 4;
                if (cursor + plateau + slopeSpan - 1 > limit) break;
                for (int px = cursor; px < cursor + plateau; px++) plan.rows[px] = current;
                cursor += plateau;
                direction = -direction;
            }
            group++;
        }
        for (int x = cursor; x <= segment.end; x++) plan.rows[x] = current;
        if (remaining > 0)
            plan.capReason = appendReason(plan.capReason,
                    "Compound-slope packing achieved " + (transitions - remaining)
                            + " of " + transitions + " allocated slope sections in one segment.");
        return current;
    }

    private static void writeSlopeRun(TerrainPlan plan, int start, int current,
                                      int direction, int slopeSpan) {
        int runId = ++plan.nextSlopeRunId;
        for (int phase = 0; phase < slopeSpan; phase++) {
            int x = start + phase;
            float centreProgress = (phase + .5f) / slopeSpan;
            float phaseProgress = (phase + 1f) / slopeSpan;
            plan.rows[x] = current + direction * centreProgress;
            plan.slopeDirection[x] = direction;
            plan.slopePhase[x] = Math.round(phaseProgress * 100f);
            plan.slopeRunId[x] = runId;
        }
        plan.elevationChanges++;
    }

    private static void recordWaterAndFlatMotifs(TerrainPlan plan) {
        int x = 0;
        while (x < plan.width) {
            if (plan.water[x]) {
                int start = x;
                while (x + 1 < plan.width && plan.water[x + 1]) x++;
                plan.motifs.add(new TerrainMotif(TerrainMotifType.WATER_CROSSING,
                        start, x, 0, 0, 0));
                x++;
                continue;
            }
            if (plan.slopeDirection[x] == 0) {
                int start = x;
                while (x + 1 < plan.width && !plan.water[x + 1]
                        && plan.slopeDirection[x + 1] == 0) x++;
                if (x - start + 1 >= 2)
                    plan.motifs.add(new TerrainMotif(TerrainMotifType.FLAT,
                            start, x, Math.round(plan.rows[start]),
                            Math.round(plan.rows[x]), 0));
            }
            x++;
        }
        for (IslandPlan island : plan.islands)
            plan.motifs.add(new TerrainMotif(TerrainMotifType.FLOATING_CLUSTER,
                    island.start, island.start + island.length - 1,
                    island.row, island.row, 0));
    }

    private static void refreshMotifRows(TerrainPlan plan) {
        for (TerrainMotif motif : plan.motifs) {
            if (motif == null || motif.type == TerrainMotifType.WATER_CROSSING
                    || motif.type == TerrainMotifType.FLOATING_CLUSTER) continue;
            int start = clamp(motif.startX, 0, plan.width - 1);
            int end = clamp(motif.endX, start, plan.width - 1);
            motif.startRow = Float.isNaN(plan.rows[start]) ? 0 : Math.round(plan.rows[start]);
            motif.endRow = Float.isNaN(plan.rows[end]) ? motif.startRow
                    : Math.round(plan.rows[end]);
        }
    }

    private static void balanceGroundDensity(TerrainPlan plan, int baseRow,
                                             int targetMainCells, int minRow, int maxRow,
                                             int maxBankStep) {
        int current = plannedGroundCells(plan);

        for (int scan = 0; scan < plan.width; ) {
            while (scan < plan.width && plan.water[scan]) scan++;
            int start = scan;
            while (scan < plan.width && !plan.water[scan]) scan++;
            int end = scan - 1;
            if (start > end) continue;
            boolean hasSlope = false, validMirror = true;
            for (int x = start; x <= end; x++) {
                hasSlope |= plan.slopeDirection[x] != 0;
                float mirrored = 2f * baseRow - plan.rows[x];
                if (mirrored < minRow || mirrored > maxRow) validMirror = false;
            }
            if (!hasSlope || !validMirror) continue;
            int before = current;
            mirrorSegment(plan, start, end, baseRow);
            int after = plannedGroundCells(plan);
            if (waterBanksReachable(plan, maxBankStep)
                    && islandClearanceValid(plan)
                    && Math.abs(after - targetMainCells) < Math.abs(before - targetMainCells)) {
                current = after;
            } else {
                mirrorSegment(plan, start, end, baseRow);
            }
        }

        boolean improved;
        do {
            improved = false;
            int bestStart = -1, bestEnd = -1, bestDelta = 0, bestError = Math.abs(current - targetMainCells);
            for (int scan = 0; scan < plan.width; ) {
                while (scan < plan.width && plan.water[scan]) scan++;
                int start = scan;
                while (scan < plan.width && !plan.water[scan]) scan++;
                int end = scan - 1;
                if (start > end) continue;
                for (int delta : new int[]{-1, 1}) {
                    boolean valid = true;
                    for (int x = start; x <= end; x++)
                        if (plan.rows[x] + delta < minRow || plan.rows[x] + delta > maxRow) {
                            valid = false;
                            break;
                        }
                    if (!valid) continue;
                    shiftSegment(plan, start, end, delta);
                    int candidate = plannedGroundCells(plan);
                    boolean banksReachable = waterBanksReachable(plan, maxBankStep);
                    boolean islandsClear = islandClearanceValid(plan);
                    shiftSegment(plan, start, end, -delta);
                    if (!banksReachable || !islandsClear) continue;
                    int error = Math.abs(candidate - targetMainCells);
                    if (error < bestError) {
                        bestError = error;
                        bestStart = start;
                        bestEnd = end;
                        bestDelta = delta;
                    }
                }
            }
            if (bestStart >= 0) {
                shiftSegment(plan, bestStart, bestEnd, bestDelta);
                current = plannedGroundCells(plan);
                improved = true;
            }
        } while (improved);
    }

    private static boolean waterBanksReachable(TerrainPlan plan, int maxStep) {
        if (maxStep == Integer.MAX_VALUE) return true;
        for (int x = 0; x < plan.width; ) {
            if (!plan.water[x]) { x++; continue; }
            int start = x;
            while (x + 1 < plan.width && plan.water[x + 1]) x++;
            int end = x;
            if (start > 0 && end + 1 < plan.width
                    && !Float.isNaN(plan.rows[start - 1]) && !Float.isNaN(plan.rows[end + 1])
                    && Math.abs(plan.rows[start - 1] - plan.rows[end + 1]) > maxStep)
                return false;
            x++;
        }
        return true;
    }

    private static void mirrorSegment(TerrainPlan plan, int start, int end, int baseRow) {
        for (int x = start; x <= end; x++) {
            plan.rows[x] = 2f * baseRow - plan.rows[x];
            plan.slopeDirection[x] = -plan.slopeDirection[x];
        }
    }

    private static void shiftSegment(TerrainPlan plan, int start, int end, int delta) {
        for (int x = start; x <= end; x++) plan.rows[x] += delta;
    }

    private static int plannedGroundCells(TerrainPlan plan) {
        int cells = 0;
        for (int x = 0; x < plan.width; x++) {
            if (plan.water[x]) continue;
            int rasterRow = plannedRasterRow(plan, x);
            cells += plan.height - rasterRow;
        }
        return cells;
    }

    private static int plannedRasterRow(TerrainPlan plan, int x) {
        float row = plan.rows[x];
        return plan.slopeDirection[x] > 0 ? (int) Math.ceil(row) - 1
                : plan.slopeDirection[x] < 0 ? (int) Math.floor(row)
                : Math.round(row);
    }

    private static void planWater(TerrainPlan plan, double waterPct, Random random,
                                  MapMode mode, int complexity, int anchorClearance) {
        int wanted = (int) Math.round(plan.width * waterPct / 100.0);
        if (wanted <= 0) return;
        final int minRun = RIVER_MIN_TILES;
        int left = 2, right = plan.width - 3;
        ArrayList<Integer> candidates = new ArrayList<Integer>();
        TierBudget budget = tierBudget(complexity, plan.width);
        int minimumAccepted = (int) Math.ceil(plan.width
                * Math.max(0.0, waterPct - 3.0) / 100.0);
        if (mode == MapMode.DUEL || mode == MapMode.DERBY) {
            int playLeft = Math.round(plan.width * (mode == MapMode.DUEL ? 0.32f : 0.15f));
            int playRight = Math.round(plan.width * (mode == MapMode.DUEL ? 0.68f : 0.85f));
            int maxRun = RIVER_MAX_TILES;
            for (int x = 0; x + maxRun <= playLeft; x++) candidates.add(x);
            for (int x = playRight + 1; x + maxRun <= plan.width; x++) candidates.add(x);
            Collections.shuffle(candidates, random);
            placeWaterRuns(plan, candidates, wanted, minimumAccepted,
                    Math.min(4, budget.waterZoneTarget), minRun, maxRun,
                    0, plan.width - 1, 2, 0, random);
            return;
        }
        int spawn = Math.round(plan.width * 0.08f), goal = Math.round(plan.width * 0.92f);
        int maxRun = anchorClearance > 3 ? RIVER_MAX_TILES
                : mode == MapMode.HEIST ? 3 : 4;

        for (int x = left + 3; x <= right - 2; x++) {
            boolean clear = Math.abs(x - spawn) > anchorClearance
                    && Math.abs(x - goal) > anchorClearance;

            if (clear && anchorClearance > 3)
                for (int runX = x + 1; runX < Math.min(plan.width, x + maxRun); runX++)
                    if (Math.abs(runX - spawn) <= anchorClearance
                            || Math.abs(runX - goal) <= anchorClearance) {
                        clear = false;
                        break;
                    }
            if (clear) candidates.add(x);
        }
        Collections.shuffle(candidates, random);
        placeWaterRuns(plan, candidates, wanted, minimumAccepted,
                budget.waterZoneTarget, minRun, maxRun,
                left, right, 6, 2, random);
    }

    private static void placeWaterRuns(TerrainPlan plan, List<Integer> candidates,
                                       int wanted, int minimumAccepted, int requestedZones,
                                       int minRun, int maxRun, int left, int right,
                                       int separation, int rightMargin, Random random) {
        int zones = Math.max(1, Math.max(requestedZones,
                (minimumAccepted + maxRun - 1) / maxRun));
        while (zones > 1 && zones * minRun > wanted
                && (zones - 1) * maxRun >= minimumAccepted) zones--;
        int target = Math.max(zones * minRun,
                Math.max(minimumAccepted, Math.min(wanted, zones * maxRun)));
        target = Math.min(target, zones * maxRun);
        int[] widths = new int[zones];
        Arrays.fill(widths, minRun);
        int remaining = target - zones * minRun;
        for (int cursor = 0; remaining > 0; cursor = (cursor + 1) % zones)
            if (widths[cursor] < maxRun) {
                widths[cursor]++;
                remaining--;
            }
        for (int i = widths.length - 1; i > 0; i--) {
            int swap = random.nextInt(i + 1);
            int value = widths[i];
            widths[i] = widths[swap];
            widths[swap] = value;
        }

        int placedZones = placeWaterRunsAttempt(plan, candidates, widths,
                left, right, separation, rightMargin);
        if (placedZones >= widths.length) return;

        Arrays.fill(plan.water, false);
        Collections.sort(candidates);
        placeWaterRunsAttempt(plan, candidates, widths,
                left, right, separation, rightMargin);
    }

    private static int placeWaterRunsAttempt(TerrainPlan plan, List<Integer> candidates,
                                             int[] widths, int left, int right,
                                             int separation, int rightMargin) {
        int placedZones = 0;
        for (Integer start : candidates) {
            if (placedZones >= widths.length || plan.water[start]) continue;
            int run = widths[placedZones];
            int end = start + run - 1;

            if (start - left == 1 || right - rightMargin - end == 1) continue;
            boolean clear = true;
            for (int x = Math.max(left, start - separation);
                 x <= Math.min(right, end + separation); x++)
                if (plan.water[x]) { clear = false; break; }
            if (!clear || end > right - rightMargin) continue;
            for (int x = start; x < start + run; x++) plan.water[x] = true;
            placedZones++;
        }
        return placedZones;
    }

    private static void planIslands(TerrainPlan plan, Random random, int complexity,
                                    int wantedCount, int wantedLayers,
                                    boolean reserveBattleBases) {
        int layers = Math.min(wantedCount,
                Math.min(wantedLayers, maxFloatingIslandLayers(plan.height)));
        int[] tierRows = floatingIslandTierRows(plan.height, layers);
        int placed = 0;
        for (int tier = 0; tier < tierRows.length; tier++) {
            int quota = wantedCount / layers + (tier < wantedCount % layers ? 1 : 0);
            int[] desiredLengths = islandLengthsForTier(plan.width, quota,
                    complexity, random);
            long placementSeed = random.nextLong();
            int row = bestIslandTierRow(plan, desiredLengths, quota,
                    tierRows[tier], reserveBattleBases, placementSeed);
            placed += placePlannedIslandTier(plan, new Random(placementSeed),
                    desiredLengths, quota, row, reserveBattleBases);
        }
        if (placed < wantedCount)
            plan.capReason = appendReason(plan.capReason,
                    "Map width, terrain and airspace cap floating-island count at "
                            + placed + " of " + wantedCount + ".");
        int actualLayers = countIslandLayers(plan.islands);
        if (actualLayers < layers)
            plan.capReason = appendReason(plan.capReason,
                    "Usable airspace caps floating-island layers at "
                            + actualLayers + " of " + layers + ".");
    }

    private static int bestIslandTierRow(TerrainPlan plan, int[] desiredLengths,
                                          int quota, int desiredRow,
                                          boolean reserveBattleBases,
                                          long placementSeed) {
        int minimum = FLOATING_ISLAND_TOP_MARGIN;
        int maximum = Math.max(minimum, plan.height - 2
                - FLOATING_ISLAND_THICKNESS - FLOATING_ISLAND_GROUND_HEADROOM);
        int desired = clamp(desiredRow, minimum, maximum);
        int bestRow = desired;
        int bestPlaced = -1;
        for (int distance = 0; distance <= maximum - minimum; distance++) {
            int first = desired - distance;
            int second = desired + distance;
            int[] candidates = distance == 0
                    ? new int[]{first} : new int[]{first, second};
            for (int row : candidates) {
                if (row < minimum || row > maximum
                        || !islandTierRowSeparated(plan.islands, row)) continue;
                int before = plan.islands.size();
                int candidatePlaced = placePlannedIslandTier(plan,
                        new Random(placementSeed), desiredLengths, quota, row,
                        reserveBattleBases);
                while (plan.islands.size() > before)
                    plan.islands.remove(plan.islands.size() - 1);
                if (candidatePlaced > bestPlaced) {
                    bestPlaced = candidatePlaced;
                    bestRow = row;
                }
                if (candidatePlaced == quota) return row;
            }
        }
        return bestRow;
    }

    private static boolean islandTierRowSeparated(List<IslandPlan> islands, int row) {
        int stride = FLOATING_ISLAND_THICKNESS + FLOATING_ISLAND_LAYER_GAP;
        if (islands != null) for (IslandPlan island : islands)
            if (island != null && Math.abs(island.row - row) < stride) return false;
        return true;
    }

    private static int placePlannedIslandTier(TerrainPlan plan, Random random,
                                               int[] desiredLengths, int quota,
                                               int row,
                                               boolean reserveBattleBases) {
        int placed = 0;
        ArrayList<Integer> failedLengths = new ArrayList<Integer>();
        int usableStart = 3;
        int usableEnd = plan.width - 4;
        int usable = Math.max(0, usableEnd - usableStart + 1);
        for (int item = 0; item < quota; item++) {
            int slotStart = usableStart + usable * item / Math.max(1, quota);
            int slotEnd = usableStart
                    + usable * (item + 1) / Math.max(1, quota) - 1;
            int contentStart = slotStart + (item > 0 ? 1 : 0);
            int contentEnd = slotEnd + (item + 1 < quota ? -1 : 0);
            boolean added = tryPlacePlannedIsland(plan, random,
                    desiredLengths[item], row, contentStart, contentEnd,
                    reserveBattleBases, true);
            if (!added) failedLengths.add(desiredLengths[item]);
            if (added) placed++;
        }
        for (Integer ignored : failedLengths)
            if (tryPlacePlannedIsland(plan, random, 2, row,
                    usableStart, usableEnd, reserveBattleBases, false)) placed++;
        return placed;
    }

    private static boolean tryPlacePlannedIsland(TerrainPlan plan, Random random,
                                                  int desiredLength, int row, int rangeStart,
                                                  int rangeEnd,
                                                  boolean reserveBattleBases,
                                                  boolean randomizeStart) {
        int available = rangeEnd - rangeStart + 1;
        if (available < 2) return false;
        int desired = Math.min(available, Math.max(2, desiredLength));
        for (int length = desired; length >= 2; length--) {
            int positions = available - length + 1;
            int offset = !randomizeStart || positions <= 1
                    ? 0 : random.nextInt(positions);
            for (int step = 0; step < positions; step++) {
                int start = rangeStart + (offset + step) % positions;
                if (!plannedIslandCandidateValid(plan, start, length, row,
                        reserveBattleBases)) continue;
                plan.islands.add(new IslandPlan(start, length, row,
                        FLOATING_ISLAND_THICKNESS));
                return true;
            }
        }
        return false;
    }

    private static boolean plannedIslandCandidateValid(TerrainPlan plan, int start,
                                                        int length, int row,
                                                        boolean reserveBattleBases) {
        int end = start + length - 1;
        int thickness = FLOATING_ISLAND_THICKNESS;
        if (start < 0 || end >= plan.width) return false;
        if (reserveBattleBases && overlapsReservedBase(plan.width, start, end))
            return false;
        float reference = Float.NaN;
        for (int x = start; x <= end; x++) {
            float support = plannedSupportRow(plan, x);
            reference = Float.isNaN(reference)
                    ? support : Math.min(reference, support);
        }
        if (Float.isNaN(reference)) reference = plan.height - 2;
        if (row < FLOATING_ISLAND_TOP_MARGIN
                || row + thickness >= reference - 1) return false;
        if (!islandSidesClear(plan, start, length, row, thickness)) return false;
        return !overlapsPlannedIsland(plan.islands, start, length, row,
                thickness, 2, FLOATING_ISLAND_LAYER_GAP);
    }

    static int[] floatingIslandTierRows(int height, int requestedLayers) {
        int layers = Math.max(0, Math.min(requestedLayers,
                maxFloatingIslandLayers(height)));
        if (layers == 0) return new int[0];
        int lowest = FLOATING_ISLAND_TOP_MARGIN;
        int highest = Math.max(lowest, height - 2 - FLOATING_ISLAND_THICKNESS
                - FLOATING_ISLAND_GROUND_HEADROOM);
        int[] rows = new int[layers];
        if (layers == 1) {
            rows[0] = Math.round((lowest + highest) * .5f);
            return rows;
        }
        float span = highest - lowest;
        for (int i = 0; i < layers; i++)
            rows[i] = Math.round(lowest + span * i / (layers - 1f));
        return rows;
    }

    private static int islandAverageWidth(int width, int islandsOnTier,
                                          int complexity) {
        int usable = Math.max(2, width - 6);
        int gaps = Math.max(0, islandsOnTier - 1) * 2;
        int capacityWidth = Math.max(2,
                (usable - gaps) / Math.max(1, islandsOnTier));
        int target = complexity >= 70 ? 4 : 3;
        return Math.max(2, Math.min(target, capacityWidth));
    }

    private static int[] islandLengthsForTier(int width, int islandsOnTier,
                                              int complexity, Random random) {
        int count = Math.max(0, islandsOnTier);
        int[] lengths = new int[count];
        int average = islandAverageWidth(width, count, complexity);
        java.util.Arrays.fill(lengths, average);
        int usable = Math.max(2, width - 6);
        int gaps = Math.max(0, count - 1) * 2;
        int capacityWidth = Math.max(2, (usable - gaps) / Math.max(1, count));
        int naturalMax = complexity >= 70 ? 6 : 5;
        if (average > 2 && average + 1 <= Math.min(naturalMax, capacityWidth)) {
            for (int i = 0; i + 1 < count; i += 2) {
                lengths[i] = average - 1;
                lengths[i + 1] = average + 1;
            }
            for (int i = count - 1; i > 0; i--) {
                int swap = random.nextInt(i + 1);
                int value = lengths[i];
                lengths[i] = lengths[swap];
                lengths[swap] = value;
            }
        }
        return lengths;
    }

    private static int estimatedFloatingIslandCells(int width, int wantedCount,
                                                     int wantedLayers,
                                                     int complexity) {
        if (wantedCount <= 0 || wantedLayers <= 0) return 0;
        int layers = Math.min(wantedCount, wantedLayers);
        int usable = Math.max(2, width - 6);
        int maximumPerTier = Math.max(0, (usable + 2) / 4);
        int cells = 0;
        for (int tier = 0; tier < layers; tier++) {
            int quota = wantedCount / layers
                    + (tier < wantedCount % layers ? 1 : 0);
            int feasible = Math.min(quota, maximumPerTier);
            cells += feasible * islandAverageWidth(width, quota, complexity);
        }
        return cells;
    }

    private static boolean overlapsPlannedIsland(List<IslandPlan> islands,
                                                  int start, int length,
                                                  int row, int thickness,
                                                  int horizontalPadding,
                                                  int verticalGap) {
        int end = start + length - 1;
        for (IslandPlan island : islands) {
            int islandEnd = island.start + island.length - 1;
            boolean horizontalNear = start <= islandEnd + horizontalPadding
                    && end + horizontalPadding >= island.start;
            if (horizontalNear) return true;
        }
        return false;
    }

    private static int countIslandLayers(List<IslandPlan> islands) {
        Set<Integer> rows = new HashSet<Integer>();
        if (islands != null) for (IslandPlan island : islands)
            if (island != null) rows.add(island.row);
        return rows.size();
    }

    private static int plannedIslandCells(List<IslandPlan> islands) {
        int cells = 0;
        if (islands != null) for (IslandPlan island : islands)
            if (island != null) cells += island.length * island.thickness;
        return cells;
    }

    private static boolean islandClearanceValid(TerrainPlan plan) {
        if (plan == null || plan.islands == null) return true;
        for (IslandPlan island : plan.islands) {
            if (island == null) continue;
            float reference = Float.NaN;
            for (int x = island.start; x < island.start + island.length; x++) {
                float support = plannedSupportRow(plan, x);
                reference = Float.isNaN(reference)
                        ? support : Math.min(reference, support);
            }
            if (Float.isNaN(reference)) reference = plan.height - 2;
            if (island.row + island.thickness >= reference - 1
                    || !islandSidesClear(plan, island.start, island.length,
                    island.row, island.thickness)) return false;
        }
        return true;
    }

    private static boolean overlapsReservedBase(int width, int start, int end) {
        int spawn = clamp(Math.round(width * .08f), 4, width - 5);
        int goal = clamp(Math.max(spawn + 8, Math.round(width * .92f)),
                4, width - 5);
        return start <= spawn + 5 && end >= spawn - 5
                || start <= goal + 5 && end >= goal - 5;
    }

    private static boolean islandSidesClear(TerrainPlan plan, int start, int length,
                                            int row, int thickness) {
        int bottom = row + thickness - 1;
        int[] sides = {start - 1, start + length};
        for (int x : sides) {
            if (x < 0 || x >= plan.width || plan.water[x]
                    || Float.isNaN(plan.rows[x])) continue;
            if (plannedRasterRow(plan, x) <= bottom) return false;
        }
        return true;
    }

    private static float plannedSupportRow(TerrainPlan plan, int x) {
        if (!plan.water[x] && !Float.isNaN(plan.rows[x])) return plan.rows[x];
        int start = x, end = x;
        while (start > 0 && plan.water[start - 1]) start--;
        while (end + 1 < plan.width && plan.water[end + 1]) end++;
        float left = start > 0 && !Float.isNaN(plan.rows[start - 1])
                ? plan.rows[start - 1] : plan.height - 2;
        float right = end + 1 < plan.width && !Float.isNaN(plan.rows[end + 1])
                ? plan.rows[end + 1] : plan.height - 2;
        return Math.max(left, right);
    }

    private static void rasterize(ModeVariant v, TerrainPlan plan) {
        v.motifs.clear();
        v.motifs.addAll(plan.motifs);
        for (int x = 0; x < v.width; x++) {
            v.water[x] = plan.water[x];
            v.slopeDirection[x] = plan.slopeDirection[x];
            v.slopePhase[x] = plan.slopePhase[x];
            v.slopeRunId[x] = plan.slopeRunId[x];
            if (plan.water[x]) {
                v.surface[x] = -1;

                v.walkSurfaceLayers[x] = 0f;
                continue;
            }
            float planned = plan.rows[x];
            int row = plan.slopeDirection[x] > 0 ? (int) Math.ceil(planned) - 1
                    : plan.slopeDirection[x] < 0 ? (int) Math.floor(planned) : Math.round(planned);
            row = clamp(row, Math.max(0, v.height - 12), v.height - 2);
            v.surface[x] = row;
            v.walkSurfaceLayers[x] = rowToLayer(v, planned);
            for (int y = row; y < v.height; y++) v.setCell(x, y, CustomMapDocument.CELL_GROUND);
        }
        normalizeWaterLevels(v);
        for (IslandPlan island : plan.islands)
            for (int ix = island.start; ix < island.start + island.length; ix++)
                for (int y = island.row; y < island.row + island.thickness; y++)
                    v.setCell(ix, y, CustomMapDocument.CELL_GROUND);
        v.elevationChanges = plan.elevationChanges;
        v.floatingIslandCount = plan.islands.size();
        v.floatingIslandLayerCount = countIslandLayers(plan.islands);
        v.profile.complexityProfile.requestedTier = plan.requestedTier;
        v.profile.complexityProfile.targetScore = plan.targetScore;
        v.profile.complexityProfile.tierName = TIER_NAMES[plan.requestedTier - 1];
        v.profile.complexityProfile.capReason = plan.capReason;
    }

    static void normalizeWaterLevels(ModeVariant v) {
        if (v == null || v.cells == null || v.water == null || v.surface == null) return;
        v.waterZoneCount = 0;
        int x = 0;
        while (x < v.width) {
            if (!v.water[x]) { x++; continue; }
            int start = x;
            while (x + 1 < v.width && v.water[x + 1]) x++;
            int end = x;

            int left = start > 0 && v.surface[start - 1] >= 0
                    ? v.surface[start - 1] : -1;
            int right = end + 1 < v.width && v.surface[end + 1] >= 0
                    ? v.surface[end + 1] : -1;
            int waterTop;
            if (left >= 0 && right >= 0) waterTop = Math.max(left, right);
            else if (left >= 0) waterTop = left;
            else if (right >= 0) waterTop = right;
            else waterTop = existingWaterTop(v, start, end);
            waterTop = clamp(waterTop, 1, v.height - 1);

            for (int wx = start; wx <= end; wx++) {
                for (int y = 0; y < v.height; y++)
                    if (v.cell(wx, y) == CustomMapDocument.CELL_WATER)
                        v.setCell(wx, y, CustomMapDocument.CELL_AIR);
                for (int y = waterTop; y < v.height; y++)
                    if (v.cell(wx, y) != CustomMapDocument.CELL_GROUND)
                        v.setCell(wx, y, CustomMapDocument.CELL_WATER);
            }
            v.waterZoneCount++;
            x++;
        }
    }

    private static int existingWaterTop(ModeVariant v, int start, int end) {
        int top = v.height - 2;
        for (int x = start; x <= end; x++)
            for (int y = 0; y < v.height; y++)
                if (v.cell(x, y) == CustomMapDocument.CELL_WATER) {
                    top = Math.min(top, y);
                    break;
                }
        return top;
    }

    private static void applyThemeContactMetrics(CustomMapDocument doc,
                                                 TileCatalog.TileSet tiles) {
        float ratio = 0f;
        if (tiles != null && tiles.tilePixels > 0)
            ratio = Math.max(0f, Math.min(.25f,
                    tiles.surfaceInsetPixels / (float) tiles.tilePixels));
        if (doc.battleTerrain != null && doc.battleTerrain.profile != null)
            doc.battleTerrain.profile.surfaceInsetRatio = ratio;
        for (ModeVariant variant : doc.variants.values())
            if (variant != null && variant.profile != null)
                variant.profile.surfaceInsetRatio = ratio;
    }

    private static void applySurfaceMaterial(CustomMapDocument doc, byte material) {
        byte safe = material == CustomMapDocument.SURFACE_ICE
                ? CustomMapDocument.SURFACE_ICE
                : CustomMapDocument.SURFACE_NORMAL;
        applySurfaceMaterial(doc == null ? null : doc.battleTerrain, safe);
        if (doc != null && doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                applySurfaceMaterial(variant, safe);
    }

    private static void applySurfaceMaterial(ModeVariant variant, byte material) {
        if (variant == null) return;
        if (variant.profile != null) variant.profile.surfaceMaterial = material;
        variant.surfaceMaterials = new byte[Math.max(0, variant.width)];
        for (int x = 0; x < variant.surfaceMaterials.length; x++)
            variant.surfaceMaterials[x] = variant.surface != null
                    && x < variant.surface.length && variant.surface[x] >= 0
                    && (variant.water == null || x >= variant.water.length
                    || !variant.water[x])
                    ? material : CustomMapDocument.SURFACE_NORMAL;
        if (variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform
                    : variant.secondaryPlatforms)
                if (platform != null) platform.surfaceMaterial = material;
        IceBridgeBuilder.markDeckColumnsAsIce(variant);
        variant.achievedIceSurfaceDensity = material == CustomMapDocument.SURFACE_ICE
                ? 100.0 : 0.0;
    }

    static boolean iceBridgesAllowed(boolean mixedIceSurface,
                                     TileCatalog.TileSet resolvedTiles) {
        return mixedIceSurface || (resolvedTiles != null
                && resolvedTiles.surfaceMaterial == CustomMapDocument.SURFACE_ICE);
    }

    public static double effectiveIceBridgeDensity(MapSpec spec) {
        if (spec == null || Double.isNaN(spec.iceBridgeDensity)) return 0.0;
        return Math.max(0.0, Math.min(100.0, spec.iceBridgeDensity));
    }

    private static void applyIceSurfaceDensity(CustomMapDocument doc,
                                               double requestedDensity) {
        double density = Math.max(0.0, Math.min(20.0, requestedDensity));
        if (doc == null) return;
        applyIceSurfaceDensity(doc.battleTerrain, density);
        if (doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                applyIceSurfaceDensity(variant, density);
    }

    static void applyIceSurfaceDensity(ModeVariant variant,
                                       double requestedDensity) {
        if (variant == null) return;
        applySurfaceMaterial(variant, CustomMapDocument.SURFACE_NORMAL);
        if (requestedDensity <= 0.0 || variant.width <= 0) return;

        boolean[] eligible = new boolean[variant.width];
        int eligibleCount = 0;
        for (int x = 0; x < variant.width; x++) {
            eligible[x] = variant.surface != null && x < variant.surface.length
                    && variant.surface[x] >= 0
                    && (variant.water == null || x >= variant.water.length
                    || !variant.water[x])
                    && !IceBridgeBuilder.isDeckColumn(variant, x)
                    && !protectedIceMaterialTile(variant, x);
            if (eligible[x]) eligibleCount++;
        }
        if (eligibleCount < 3) return;

        ArrayList<IceRunCandidate> candidates =
                new ArrayList<IceRunCandidate>();
        for (int start = 0; start < variant.width; start++) {
            if (!eligible[start]) continue;
            for (int length = 3; length <= 9 && start + length <= variant.width;
                 length++) {
                int end = start + length - 1;
                boolean valid = true;
                for (int x = start; x <= end; x++)
                    if (!eligible[x]) { valid = false; break; }
                if (!valid || splitsSlopeRun(variant, start, end)
                        || materialBoundaryOnSlope(variant, start, end)) continue;
                candidates.add(new IceRunCandidate(start, end));
            }
        }

        Random random = new Random(mix(variant.seed, "ice-surface-materials"));
        Collections.shuffle(candidates, random);
        int target = (int) Math.round(eligibleCount * requestedDensity / 100.0);
        int assigned = 0;
        while (assigned < target) {
            IceRunCandidate best = null;
            int bestScore = Integer.MAX_VALUE;
            for (IceRunCandidate candidate : candidates) {
                if (!candidate.available(variant.surfaceMaterials)) continue;
                int score = Math.abs(target - assigned - candidate.length());
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best == null) break;
            for (int x = best.start; x <= best.end; x++)
                variant.surfaceMaterials[x] = CustomMapDocument.SURFACE_ICE;
            assigned += best.length();
            candidates.remove(best);
            if (assigned >= target && Math.abs(target - assigned) <= 2) break;
        }

        if (variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform
                    : variant.secondaryPlatforms) {
                if (platform == null) continue;
                int length = platform.widthTiles();
                platform.surfaceMaterial = length >= 3 && length <= 9
                        && random.nextDouble() * 100.0 < requestedDensity
                        ? CustomMapDocument.SURFACE_ICE
                        : CustomMapDocument.SURFACE_NORMAL;
            }
        variant.achievedIceSurfaceDensity = eligibleCount <= 0 ? 0.0
                : assigned * 100.0 / eligibleCount;
    }

    static void applyFloatingIslandMaterialPolicy(CustomMapDocument doc,
                                                  boolean snowOnly) {
        if (doc == null || !snowOnly) return;
        applyFloatingIslandMaterialPolicy(doc.battleTerrain);
        if (doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                applyFloatingIslandMaterialPolicy(variant);
    }

    private static void applyFloatingIslandMaterialPolicy(ModeVariant variant) {
        if (variant == null || variant.secondaryPlatforms == null) return;
        for (CustomMapDocument.SecondaryPlatform platform
                : variant.secondaryPlatforms) {
            if (platform == null) continue;
            platform.surfaceMaterial = CustomMapDocument.SURFACE_NORMAL;
            platform.iceSurfaceKeys = new String[platform.widthTiles()];
        }
    }

    private static boolean protectedIceMaterialTile(ModeVariant variant, int x) {
        if (variant.spawn != null && variant.spawn.x == x) return true;
        if (variant.destination != null && variant.destination.x == x) return true;
        if (variant.checkpoints != null)
            for (CustomMapDocument.MapAnchor anchor : variant.checkpoints)
                if (anchor != null && anchor.x == x) return true;
        if (variant.baseSafeZones != null)
            for (CustomMapDocument.BaseSafeZone zone : variant.baseSafeZones)
                if (zone != null && zone.containsTile(x)) return true;
        return false;
    }

    private static boolean splitsSlopeRun(ModeVariant variant, int start, int end) {
        if (variant.slopeRunId == null) return false;
        int leftId = start < variant.slopeRunId.length
                ? variant.slopeRunId[start] : 0;
        int rightId = end < variant.slopeRunId.length
                ? variant.slopeRunId[end] : 0;
        return (leftId > 0 && start > 0
                && variant.slopeRunId[start - 1] == leftId)
                || (rightId > 0 && end + 1 < variant.slopeRunId.length
                && variant.slopeRunId[end + 1] == rightId);
    }

    private static boolean materialBoundaryOnSlope(
            ModeVariant variant, int start, int end) {
        if (variant == null || variant.slopeDirection == null) return false;
        return (start >= 0 && start < variant.slopeDirection.length
                && variant.slopeDirection[start] != 0)
                || (end >= 0 && end < variant.slopeDirection.length
                && variant.slopeDirection[end] != 0);
    }

    private static final class IceRunCandidate {
        final int start;
        final int end;

        IceRunCandidate(int start, int end) {
            this.start = start;
            this.end = end;
        }

        int length() { return end - start + 1; }

        boolean available(byte[] materials) {
            if (materials == null || start < 0 || end >= materials.length)
                return false;
            if (start > 0 && materials[start - 1] == CustomMapDocument.SURFACE_ICE)
                return false;
            if (end + 1 < materials.length
                    && materials[end + 1] == CustomMapDocument.SURFACE_ICE)
                return false;
            for (int x = start; x <= end; x++)
                if (materials[x] == CustomMapDocument.SURFACE_ICE) return false;
            return true;
        }
    }

    private static final class TerrainPlan {
        final int width, height;
        final float[] rows;
        final boolean[] water;
        final int[] slopeDirection;
        final int[] slopePhase;
        final int[] slopeRunId;
        final List<IslandPlan> islands = new ArrayList<IslandPlan>();
        final List<TerrainMotif> motifs = new ArrayList<TerrainMotif>();
        int elevationChanges;
        int nextSlopeRunId;
        int requestedTier = 1;
        double targetScore;
        String capReason = "";

        TerrainPlan(int width, int height) {
            this.width = width;
            this.height = height;
            rows = new float[width];
            water = new boolean[width];
            slopeDirection = new int[width];
            slopePhase = new int[width];
            slopeRunId = new int[width];
        }
    }

    private static final class DrySegment {
        final int start, end, leftFlat, rightFlat, capacity;
        int allocated;

        DrySegment(int start, int end, int leftFlat, int rightFlat, int capacity) {
            this.start = start;
            this.end = end;
            this.leftFlat = leftFlat;
            this.rightFlat = rightFlat;
            this.capacity = capacity;
        }

        int length() { return end - start + 1; }
    }

    private static final class TierBudget {
        int tier;
        String name;
        int transitionTarget;
        int spanTarget;
        int islandTarget;
        int waterZoneTarget;
        int battleFeatureTarget;
        int maxChain;
        int minChain = 1;
        double targetScore;
    }

    private static final class IslandPlan {
        final int start, length, row, thickness;
        IslandPlan(int start, int length, int row, int thickness) {
            this.start = start;
            this.length = length;
            this.row = row;
            this.thickness = thickness;
        }
    }

    private static void buildGround(ModeVariant v, double groundPct, double waterPct,
                                    Random random, boolean flat, int complexity) {
        double dryFraction = Math.max(0.35, 1.0 - waterPct / 100.0);
        int baseRows = (int) Math.round((groundPct / 100.0) * v.height / dryFraction);
        int maxThickness = Math.max(2, Math.min(12, v.height - 2));
        baseRows = clamp(baseRows, 2, maxThickness);
        int baseRow = v.height - baseRows;
        float[] rows = new float[v.width];
        java.util.Arrays.fill(rows, baseRow);

        if (!flat && complexity > 15) {
            int minRow = v.height - maxThickness;
            int maxRow = v.height - 2;
            int ramps = Math.max(1, Math.round((complexity - 15) / 85f * v.width / 12f));
            int cursor = 5;
            int currentRow = baseRow;
            for (int ramp = 0; ramp < ramps && cursor < v.width - 8; ramp++) {
                int plateau = Math.max(5, 25 - complexity / 5) + random.nextInt(7);
                cursor += plateau;
                if (cursor >= v.width - 8) break;
                int direction;
                if (currentRow <= minRow) direction = 1;
                else if (currentRow >= maxRow) direction = -1;
                else direction = random.nextBoolean() ? 1 : -1;

                int rampLength = 2;
                int end = Math.min(v.width - 4, cursor + rampLength);
                for (int x = cursor; x < end; x++) {
                    float span = Math.max(1f, end - cursor);
                    float centreProgress = (x - cursor + .5f) / span;
                    float phaseProgress = (x - cursor + 1f) / span;
                    rows[x] = currentRow + direction * centreProgress;
                    v.slopeDirection[x] = direction;
                    v.slopePhase[x] = Math.round(phaseProgress * 100f);
                }
                currentRow = clamp(currentRow + direction, minRow, maxRow);
                for (int x = end; x < v.width; x++) rows[x] = currentRow;
                cursor = end;
                v.elevationChanges++;
            }

            double average = 0.0;
            for (float row : rows) average += row;
            average /= Math.max(1, rows.length);
            float shift = (float) (baseRow - average);
            for (int i = 0; i < rows.length; i++)
                rows[i] = Math.max(minRow, Math.min(maxRow, rows[i] + shift));
        }
        for (int x = 0; x < v.width; x++) {
            int row;
            if (v.slopeDirection[x] > 0) row = (int) Math.ceil(rows[x]) - 1;
            else if (v.slopeDirection[x] < 0) row = (int) Math.floor(rows[x]);
            else row = Math.round(rows[x]);
            row = clamp(row, v.height - maxThickness, v.height - 2);
            v.surface[x] = row;
            v.walkSurfaceLayers[x] = rowToLayer(v, rows[x]);
            for (int y = row; y < v.height; y++)
                v.setCell(x, y, CustomMapDocument.CELL_GROUND);
        }
    }

    private static void makeGround(ModeVariant v, int x, int row) {
        if (x < 0 || x >= v.width) return;
        row = clamp(row, 1, v.height - 1);
        for (int y = 0; y < v.height; y++) v.setCell(x, y, CustomMapDocument.CELL_AIR);
        for (int y = row; y < v.height; y++) v.setCell(x, y, CustomMapDocument.CELL_GROUND);
        v.surface[x] = row;
        if (v.walkSurfaceLayers != null) v.walkSurfaceLayers[x] = rowToLayer(v, row);
        if (v.slopeDirection != null) v.slopeDirection[x] = 0;
        if (v.slopePhase != null) v.slopePhase[x] = 0;
        v.water[x] = false;
    }

    private static void repairAnchorsAndRoute(ModeVariant v, MapMode mode) {
        float spawnRatio = mode == MapMode.DUEL ? 0.42f : 0.08f;
        float goalRatio = mode == MapMode.DUEL ? 0.58f : 0.92f;
        int spawn = Math.max(2, Math.round(v.width * spawnRatio));
        int goal = Math.min(v.width - 3, Math.round(v.width * goalRatio));
        int anchorRadius = mode == MapMode.DUEL || mode == MapMode.DERBY ? 0 : 2;
        for (int x = Math.max(0, spawn - anchorRadius); x <= Math.min(v.width - 1, spawn + anchorRadius); x++)
            if (v.surface[x] < 0) makeGround(v, x, nearestSurface(v, x, x));
        for (int x = Math.max(0, goal - anchorRadius); x <= Math.min(v.width - 1, goal + anchorRadius); x++)
            if (v.surface[x] < 0) makeGround(v, x, nearestSurface(v, x, x));

        int maxGap = mode == MapMode.HEIST ? 3 : 4;
        int gap = 0;
        for (int x = spawn; x <= goal; x++) {
            if (v.surface[x] < 0) {
                gap++;
                if (gap > maxGap) {
                    int left = x - gap;
                    int right = Math.min(v.width - 1, x + 1);
                    int row = nearestSurface(v, left, right);
                    makeGround(v, x, row);
                    gap = 0;
                }
            } else gap = 0;
        }
    }

    private static int nearestSurface(ModeVariant v, int left, int right) {
        for (int d = 0; d < v.width; d++) {
            int a = left - d;
            int b = right + d;
            if (a >= 0 && v.surface[a] >= 0) return v.surface[a];
            if (b < v.width && v.surface[b] >= 0) return v.surface[b];
        }
        return v.height - 2;
    }

    private static void normalizeWaterZones(ModeVariant v) {
        v.waterZoneCount = 0;
        int x = 0;
        while (x < v.width) {
            if (!v.water[x]) { x++; continue; }
            int start = x;
            while (x + 1 < v.width && v.water[x + 1]) x++;
            int end = x;
            int leftRow = start > 0 && v.surface[start - 1] >= 0
                    ? v.surface[start - 1] : nearestSurface(v, start - 1, start - 1);
            int rightRow = end + 1 < v.width && v.surface[end + 1] >= 0
                    ? v.surface[end + 1] : nearestSurface(v, end + 1, end + 1);
            int waterTop = clamp(Math.max(leftRow, rightRow), 1, v.height - 1);
            for (int wx = start; wx <= end; wx++) {
                for (int y = 0; y < v.height; y++) {

                    if (v.cell(wx, y) == CustomMapDocument.CELL_GROUND) continue;
                    v.setCell(wx, y, y >= waterTop
                            ? CustomMapDocument.CELL_WATER : CustomMapDocument.CELL_AIR);
                }
                v.surface[wx] = -1;
                v.walkSurfaceLayers[wx] = 0f;
                v.slopeDirection[wx] = 0;
                v.slopePhase[wx] = 0;
            }
            v.waterZoneCount++;
            x++;
        }
    }

    private static void stabilizeShoreBanks(ModeVariant v) {
        int x = 0;
        while (x < v.width) {
            if (!v.water[x]) { x++; continue; }
            int start = x;
            while (x + 1 < v.width && v.water[x + 1]) x++;
            int end = x;

            if (start >= 3) {
                int row = v.surface[start - 3] >= 0
                        ? v.surface[start - 3] : nearestSurface(v, start - 3, start - 1);
                for (int bank = start - 2; bank < start; bank++)
                    setMainSurfacePreservingIslands(v, bank, row);
            }
            if (end + 3 < v.width) {
                int row = v.surface[end + 3] >= 0
                        ? v.surface[end + 3] : nearestSurface(v, end + 1, end + 3);
                for (int bank = end + 1; bank <= end + 2; bank++)
                    setMainSurfacePreservingIslands(v, bank, row);
            }
            x++;
        }
    }

    private static void setMainSurfacePreservingIslands(ModeVariant v, int x, int row) {
        if (x < 0 || x >= v.width || v.water[x] || v.surface[x] < 0) return;
        int oldSurface = v.surface[x];
        row = clamp(row, Math.max(1, v.height - 12), v.height - 2);

        for (int y = oldSurface; y < v.height; y++)
            v.setCell(x, y, CustomMapDocument.CELL_AIR);
        for (int y = row; y < v.height; y++)
            v.setCell(x, y, CustomMapDocument.CELL_GROUND);
        v.surface[x] = row;
        v.walkSurfaceLayers[x] = rowToLayer(v, row);
        v.slopeDirection[x] = 0;
        v.slopePhase[x] = 0;
    }

    private static void placeFloatingPlatforms(ModeVariant v, Random random, int complexity,
                                               int wantedCount, int wantedLayers) {
        if (wantedCount <= 0 || wantedLayers <= 0) return;
        int layers = Math.min(wantedCount,
                Math.min(wantedLayers, maxFloatingIslandLayers(v.height)));
        int[] tierRows = floatingIslandTierRows(v.height, layers);
        int placed = 0;
        Set<Integer> placedRows = new HashSet<Integer>();
        for (int tier = 0; tier < tierRows.length; tier++) {
            int quota = wantedCount / layers + (tier < wantedCount % layers ? 1 : 0);
            int[] desiredLengths = islandLengthsForTier(v.width, quota,
                    complexity, random);
            long placementSeed = random.nextLong();
            int row = bestFloatingTierRow(v, desiredLengths, quota,
                    tierRows[tier], placementSeed);
            int tierPlaced = placeFloatingPlatformTier(v,
                    new Random(placementSeed), desiredLengths, quota, row);
            placed += tierPlaced;
            if (tierPlaced > 0) placedRows.add(row);
        }
        v.floatingIslandCount = placed;
        v.floatingIslandLayerCount = placedRows.size();
        if (placed < wantedCount)
            appendCapReason(v, "Map width, terrain and airspace capped floating-island count at "
                    + placed + " of " + wantedCount + ".");
        if (placedRows.size() < layers)
            appendCapReason(v, "Usable airspace capped floating-island layers at "
                    + placedRows.size() + " of " + layers + ".");
    }

    private static int bestFloatingTierRow(ModeVariant v, int[] desiredLengths,
                                            int quota, int desiredRow,
                                            long placementSeed) {
        int minimum = FLOATING_ISLAND_TOP_MARGIN;
        int maximum = Math.max(minimum, v.height - 2
                - FLOATING_ISLAND_THICKNESS - FLOATING_ISLAND_GROUND_HEADROOM);
        int desired = clamp(desiredRow, minimum, maximum);
        int bestRow = desired;
        int bestPlaced = -1;
        for (int distance = 0; distance <= maximum - minimum; distance++) {
            int first = desired - distance;
            int second = desired + distance;
            int[] candidates = distance == 0
                    ? new int[]{first} : new int[]{first, second};
            for (int row : candidates) {
                if (row < minimum || row > maximum
                        || !floatingTierRowSeparated(v, row)) continue;
                int motifCount = v.motifs == null ? 0 : v.motifs.size();
                int candidatePlaced = placeFloatingPlatformTier(v,
                        new Random(placementSeed), desiredLengths, quota, row);
                removeFloatingPlatformsAfter(v, motifCount);
                if (candidatePlaced > bestPlaced) {
                    bestPlaced = candidatePlaced;
                    bestRow = row;
                }
                if (candidatePlaced == quota) return row;
            }
        }
        return bestRow;
    }

    private static boolean floatingTierRowSeparated(ModeVariant v, int row) {
        int stride = FLOATING_ISLAND_THICKNESS + FLOATING_ISLAND_LAYER_GAP;
        if (v.motifs != null) for (TerrainMotif motif : v.motifs)
            if (motif != null && motif.type == TerrainMotifType.FLOATING_CLUSTER
                    && Math.abs(motif.startRow - row) < stride) return false;
        return true;
    }

    private static int placeFloatingPlatformTier(ModeVariant v, Random random,
                                                  int[] desiredLengths,
                                                  int quota, int row) {
        int placed = 0;
        ArrayList<Integer> failedLengths = new ArrayList<Integer>();
        int usableStart = 3;
        int usableEnd = v.width - 4;
        int usable = Math.max(0, usableEnd - usableStart + 1);
        for (int item = 0; item < quota; item++) {
            int slotStart = usableStart + usable * item / Math.max(1, quota);
            int slotEnd = usableStart
                    + usable * (item + 1) / Math.max(1, quota) - 1;
            int contentStart = slotStart + (item > 0 ? 1 : 0);
            int contentEnd = slotEnd + (item + 1 < quota ? -1 : 0);
            boolean added = tryPlaceFloatingPlatform(v, random,
                    desiredLengths[item], row, contentStart, contentEnd, true);
            if (!added) failedLengths.add(desiredLengths[item]);
            if (added) placed++;
        }
        for (Integer ignored : failedLengths)
            if (tryPlaceFloatingPlatform(v, random, 2, row,
                    usableStart, usableEnd, false)) placed++;
        return placed;
    }

    private static void removeFloatingPlatformsAfter(ModeVariant v, int motifCount) {
        if (v.motifs == null) return;
        while (v.motifs.size() > motifCount) {
            TerrainMotif motif = v.motifs.remove(v.motifs.size() - 1);
            if (motif == null || motif.type != TerrainMotifType.FLOATING_CLUSTER) continue;
            for (int x = Math.max(0, motif.startX);
                 x <= Math.min(v.width - 1, motif.endX); x++)
                for (int y = Math.max(0, motif.startRow);
                     y <= Math.min(v.height - 1, motif.endRow); y++)
                    if (v.surface[x] < 0 || y < v.surface[x])
                        v.setCell(x, y, CustomMapDocument.CELL_AIR);
        }
    }

    private static boolean tryPlaceFloatingPlatform(ModeVariant v, Random random,
                                                     int desiredLength, int row, int rangeStart,
                                                     int rangeEnd,
                                                     boolean randomizeStart) {
        int available = rangeEnd - rangeStart + 1;
        if (available < 2) return false;
        int desired = Math.min(available, Math.max(2, desiredLength));
        int thickness = FLOATING_ISLAND_THICKNESS;
        for (int length = desired; length >= 2; length--) {
            int positions = available - length + 1;
            int offset = !randomizeStart || positions <= 1
                    ? 0 : random.nextInt(positions);
            for (int step = 0; step < positions; step++) {
                int start = rangeStart + (offset + step) % positions;
                if (insideBaseSafeZone(v, start - 1, start + length)) continue;
                int reference = highestSurfaceAcross(v, start, start + length - 1);
                if (row < FLOATING_ISLAND_TOP_MARGIN
                        || row + thickness >= reference - 1) continue;
                if (overlapsExistingFloating(v, start, length, row, thickness,
                        2, FLOATING_ISLAND_LAYER_GAP)) continue;
                if (floatingSidesTouchMain(v, start, length, row, thickness)) continue;
                if (!floatingAreaClear(v, start, length, row, thickness)) continue;
                for (int x = start; x < start + length; x++)
                    for (int y = row; y < row + thickness; y++)
                        v.setCell(x, y, CustomMapDocument.CELL_GROUND);
                v.motifs.add(new TerrainMotif(TerrainMotifType.FLOATING_CLUSTER,
                        start, start + length - 1, row, row, 0));
                if (AuthoredTileTopology.firstIssue(v) == null) return true;
                for (int x = start; x < start + length; x++)
                    for (int y = row; y < row + thickness; y++)
                        v.setCell(x, y, CustomMapDocument.CELL_AIR);
                v.motifs.remove(v.motifs.size() - 1);
            }
        }
        return false;
    }

    private static boolean overlapsExistingFloating(ModeVariant v, int start,
                                                     int length, int row,
                                                     int thickness,
                                                     int horizontalPadding,
                                                     int verticalGap) {
        int end = start + length - 1;
        if (v.motifs == null) return false;
        for (TerrainMotif motif : v.motifs) {
            if (motif == null || motif.type != TerrainMotifType.FLOATING_CLUSTER)
                continue;
            boolean horizontalNear = start <= motif.endX + horizontalPadding
                    && end + horizontalPadding >= motif.startX;
            if (horizontalNear) return true;
        }
        return false;
    }

    private static int highestSurfaceAcross(ModeVariant v, int start, int end) {
        int reference = v.height - 2;
        for (int x = Math.max(0, start); x <= Math.min(v.width - 1, end); x++) {
            int support = v.surface[x] >= 0 ? v.surface[x] : nearestSurface(v, x, x);
            reference = Math.min(reference, support);
        }
        return reference;
    }

    private static boolean floatingAreaClear(ModeVariant v, int start, int length,
                                             int row, int thickness) {
        int left = Math.max(0, start - 1);
        int right = Math.min(v.width - 1, start + length);
        int top = Math.max(0, row - 1);
        int bottom = Math.min(v.height - 1, row + thickness);
        for (int x = left; x <= right; x++) {
            int main = v.surface[x] < 0 ? v.height : v.surface[x];
            for (int y = top; y <= bottom; y++)
                if (y < main && v.cell(x, y) == CustomMapDocument.CELL_GROUND)
                    return false;
        }
        return true;
    }

    private static boolean floatingSidesTouchMain(ModeVariant v, int start, int length,
                                                  int row, int thickness) {
        int bottom = row + thickness - 1;
        int[] sides = {start - 1, start + length};
        for (int x : sides) {
            if (x < 0 || x >= v.width || v.surface[x] < 0) continue;
            if (v.surface[x] <= bottom) return true;
        }
        return false;
    }

    private static void placeDocumentProps(CustomMapDocument doc) {
        if (doc == null || doc.spec == null || doc.propManifest == null
                || doc.propManifest.assets == null
                || doc.themeProfile == null || doc.themeProfile.profileId == null
                || doc.themeProfile.profileId.trim().isEmpty()
                || effectivePropDensity(doc.spec) <= 0d) return;
        ArrayList<PropAssetRef> eligible = new ArrayList<PropAssetRef>();
        for (PropAssetRef ref : doc.propManifest.assets)
            if (ref != null && ref.randomEligible && ref.decorative
                    && ref.id != null && !ref.id.isEmpty()
                    && ref.weight > 0f && ref.maxCount > 0)
                eligible.add(ref);
        if (eligible.isEmpty()) return;
        if (doc.battleTerrain != null)
            placeProps(doc.battleTerrain, doc.spec.propDensity, eligible);
        if (doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                placeProps(variant, doc.spec.propDensity, eligible);
    }

    private static void placeProps(ModeVariant v, double density,
                                   List<PropAssetRef> eligible) {
        if (v == null || eligible == null || eligible.isEmpty()) return;
        if (v.props == null) v.props = new ArrayList<PropPlacement>();
        else v.props.clear();
        int globalCap = Math.min(48, Math.max(0, Math.round(v.width * .18f)));
        int wanted = Math.min(globalCap, Math.max(0,
                (int) Math.round(globalCap * Math.max(0d, Math.min(100d, density)) / 100d)));
        if (wanted <= 0) { v.achievedPropDensity = 0d; return; }

        ArrayList<MapAnchor> candidates = new ArrayList<MapAnchor>();
        for (int x = 2; x + 2 < v.width; x++) {
            int y = v.surface == null || x >= v.surface.length ? -1 : v.surface[x];
            if (y < 1 || !dryFlatCandidate(v, x, y)) continue;
            if (insideBaseSafeZone(v, x - 2, x + 2) || nearObjective(v, x)
                    || onSecondaryPlatform(v, x) || nearTree(v, x)) continue;
            candidates.add(new MapAnchor(x, y));
        }
        Collections.shuffle(candidates, new Random(mix(v.seed, "props-v1-candidates")));
        Map<String, Integer> logicalCounts = new LinkedHashMap<String, Integer>();
        ArrayList<PropAssetRef> placedRefs = new ArrayList<PropAssetRef>();
        for (MapAnchor spot : candidates) {
            if (v.props.size() >= wanted) break;
            Random random = new Random(mix(v.seed,
                    (v.mode == null ? "" : v.mode) + "|" + spot.y + "|" + spot.x + "|props-v1"));
            PropAssetRef ref = chooseProp(eligible, logicalCounts, v.props,
                    placedRefs, spot, v, random);
            if (ref == null) continue;
            PropPlacement placement = new PropPlacement(spot.x, spot.y, ref.id);
            placement.layer = ref.layer == null || ref.layer.isEmpty()
                    ? "BEHIND_ACTORS" : ref.layer;
            int low = Math.max(25, Math.min(ref.minScalePercent, ref.maxScalePercent));
            int high = Math.max(low, Math.max(ref.minScalePercent, ref.maxScalePercent));
            placement.scalePercent = low + random.nextInt(high - low + 1);
            placement.xOffsetPercent = -18 + random.nextInt(37);
            v.props.add(placement);
            placedRefs.add(ref);
            String logical = ref.logicalId == null ? ref.id : ref.logicalId;
            Integer count = logicalCounts.get(logical);
            logicalCounts.put(logical, count == null ? 1 : count + 1);
        }
        v.achievedPropDensity = 100d * v.props.size() / Math.max(1, globalCap);
        v.objectCount += v.props.size();
        if (v.props.size() < wanted)
            appendCapReason(v, "Decorative prop spacing capped props at "
                    + v.props.size() + " of " + wanted + ".");
    }

    private static PropAssetRef chooseProp(List<PropAssetRef> eligible,
                                           Map<String, Integer> logicalCounts,
                                           List<PropPlacement> placements,
                                           List<PropAssetRef> placedRefs,
                                           MapAnchor spot, ModeVariant v, Random random) {
        ArrayList<PropAssetRef> choices = new ArrayList<PropAssetRef>();
        float total = 0f;
        for (PropAssetRef ref : eligible) {
            String logical = ref.logicalId == null ? ref.id : ref.logicalId;
            Integer count = logicalCounts.get(logical);
            if (count != null && count >= ref.maxCount) continue;
            if (!propFootprintClear(v, spot.x, spot.y, ref)) continue;
            boolean separated = true;
            for (int i = 0; i < placements.size(); i++) {
                PropPlacement prior = placements.get(i);
                PropAssetRef priorRef = placedRefs.get(i);
                float gap = Math.max(Math.max(ref.minGapTiles, priorRef.minGapTiles),
                        propHorizontalRadius(ref) + propHorizontalRadius(priorRef));
                if (Math.abs(prior.x - spot.x) < gap
                        || ref.id.equals(prior.assetId) && Math.abs(prior.x - spot.x) < 6) {
                    separated = false;
                    break;
                }
            }
            if (!separated) continue;
            choices.add(ref);
            total += Math.max(.001f, ref.weight);
        }
        if (choices.isEmpty()) return null;
        float pick = random.nextFloat() * total;
        for (PropAssetRef ref : choices) {
            pick -= Math.max(.001f, ref.weight);
            if (pick <= 0f) return ref;
        }
        return choices.get(choices.size() - 1);
    }

    private static boolean dryFlatCandidate(ModeVariant v, int x, int row) {
        if (v.water == null || v.water[x]
                || x > 0 && v.water[x - 1]
                || x + 1 < v.width && v.water[x + 1]) return false;
        for (int ix = x - 1; ix <= x + 1; ix++)
            if (ix < 0 || ix >= v.width || v.surface[ix] != row
                    || v.water[ix] || v.slopeDirection != null && v.slopeDirection[ix] != 0)
                return false;
        return v.cell(x, row) == CustomMapDocument.CELL_GROUND
                && v.cell(x, row - 1) == CustomMapDocument.CELL_AIR;
    }

    private static boolean propFootprintClear(ModeVariant v, int x, int row, PropAssetRef ref) {
        int half = Math.max(0, (int) Math.ceil(propHorizontalRadius(ref) - .5f));
        float maxScale = Math.max(25, ref.maxScalePercent) / 100f;
        int clearance = Math.max(1, (int) Math.ceil(
                Math.max(.5f, ref.maxHeightTiles) * maxScale));
        for (int ix = Math.max(0, x - half - 1);
             ix <= Math.min(v.width - 1, x + half + 1); ix++)
            if (v.water[ix]) return false;
        for (int ix = x - half; ix <= x + half; ix++) {
            if (ix < 0 || ix >= v.width || v.surface[ix] != row || v.water[ix]
                    || v.slopeDirection != null && v.slopeDirection[ix] != 0) return false;
            for (int iy = Math.max(0, row - clearance); iy < row; iy++)
                if (v.cell(ix, iy) != CustomMapDocument.CELL_AIR) return false;
        }
        return true;
    }

    private static boolean nearObjective(ModeVariant v, int x) {
        if (v.spawn != null && Math.abs(v.spawn.x - x) <= 3) return true;
        if (v.destination != null && Math.abs(v.destination.x - x) <= 3) return true;
        if (v.checkpoints != null) for (MapAnchor point : v.checkpoints)
            if (point != null && Math.abs(point.x - x) <= 1) return true;
        return false;
    }

    private static boolean onSecondaryPlatform(ModeVariant v, int x) {
        if (v.secondaryPlatforms == null) return false;
        for (CustomMapDocument.SecondaryPlatform platform : v.secondaryPlatforms)
            if (platform != null && x >= platform.startX && x <= platform.endX) return true;
        return false;
    }

    private static boolean nearTree(ModeVariant v, int x) {
        if (v.trees == null) return false;
        for (TreePlacement tree : v.trees)
            if (tree != null && Math.abs(tree.x - x) < 3) return true;
        return false;
    }

    private static float propHorizontalRadius(PropAssetRef ref) {
        if (ref == null) return .5f;
        float scale = Math.max(25, ref.maxScalePercent) / 100f;
        return Math.max(.25f, ref.maxWidthTiles) * scale * .5f + .18f;
    }

    private static void placeTrees(ModeVariant v, double treePct, Random random, int complexity) {
        ArrayList<MapAnchor> eligible = new ArrayList<MapAnchor>();
        int dryColumns = 0;
        for (int x = 2; x < v.width - 2; x++) if (!v.water[x] && v.surface[x] >= 0) {
            dryColumns++;
            if (v.slopeDirection[x] == 0 && v.slopeDirection[x - 1] == 0
                    && v.slopeDirection[x + 1] == 0
                    && !IceBridgeBuilder.isDeckColumn(v, x))
                eligible.add(new MapAnchor(x, v.surface[x]));
        }
        if (complexity >= 55) {
            for (int x = 2; x < v.width - 2; x++) for (int y = 1; y < v.height - 1; y++) {
                if (v.cell(x, y) == CustomMapDocument.CELL_GROUND
                        && v.cell(x, y - 1) == CustomMapDocument.CELL_AIR
                        && (v.surface[x] < 0 || y < v.surface[x]))
                    eligible.add(new MapAnchor(x, y));
            }
        }
        int wanted = (int) Math.round(dryColumns * treePct / 100.0);
        ArrayList<MapAnchor> selected = selectLegacyTreeCandidates(eligible, random, wanted);
        for (MapAnchor spot : selected) {
            TreePlacement tree = new TreePlacement(spot.x, spot.y,
                    random.nextInt() & 0x7fffffff);
            tree.scalePercent = 75 + random.nextInt(51);
            tree.xOffsetPercent = -22 + random.nextInt(45);
            v.trees.add(tree);
        }
        if (selected.size() < wanted)
            appendCapReason(v, "Natural tree spacing capped decorations at "
                    + selected.size() + " of " + wanted + ".");
        v.objectCount = v.trees.size();
    }

    private static ArrayList<MapAnchor> selectLegacyTreeCandidates(
            ArrayList<MapAnchor> source, Random random, int wanted) {
        ArrayList<MapAnchor> shuffled = new ArrayList<MapAnchor>(source);
        Collections.shuffle(shuffled, random);
        int maximumX = -1;
        for (MapAnchor candidate : source) maximumX = Math.max(maximumX, candidate.x);
        boolean[] seenX = new boolean[Math.max(0, maximumX + 1)];
        ArrayList<MapAnchor> unique = new ArrayList<MapAnchor>();
        for (MapAnchor candidate : shuffled)
            if (candidate.x >= 0 && candidate.x < seenX.length && !seenX[candidate.x]) {
                seenX[candidate.x] = true;
                unique.add(candidate);
            }

        int target = Math.min(Math.max(0, wanted),
                Math.max(0, Math.round(unique.size() * .50f)));
        ArrayList<MapAnchor> best = new ArrayList<MapAnchor>();
        for (int attempt = 0; attempt < 48 && best.size() < target; attempt++) {
            ArrayList<MapAnchor> order = new ArrayList<MapAnchor>(unique);
            Collections.shuffle(order, random);
            ArrayList<MapAnchor> candidateSet = new ArrayList<MapAnchor>();
            for (MapAnchor candidate : order) {
                if (!hasTreeWithin(candidateSet, candidate.x, 1))
                    candidateSet.add(candidate);
                if (candidateSet.size() >= target) break;
            }
            if (candidateSet.size() > best.size()) best = candidateSet;
        }

        if (best.size() < target) {
            ArrayList<MapAnchor> companions = new ArrayList<MapAnchor>(unique);
            Collections.shuffle(companions, random);
            for (MapAnchor candidate : companions) {
                if (!createsDenseTreeRun(best, candidate.x)) best.add(candidate);
                if (best.size() >= target) break;
            }
        }
        if (best.size() > target)
            return new ArrayList<MapAnchor>(best.subList(0, target));
        return best;
    }

    private static String placeVolcanoTrees(
            ModeVariant v, double treePct, Random random, int complexity) {
        ArrayList<MapAnchor> eligible = new ArrayList<MapAnchor>();
        int dryColumns = 0;
        for (int x = 2; x < v.width - 2; x++) if (!v.water[x] && v.surface[x] >= 0) {
            dryColumns++;
            if (treeSupportSafe(v, x, v.surface[x]))
                eligible.add(new MapAnchor(x, v.surface[x]));
        }
        if (complexity >= 55)
            for (int x = 2; x < v.width - 2; x++) for (int y = 1; y < v.height - 1; y++)
                if (v.cell(x, y) == CustomMapDocument.CELL_GROUND
                        && v.cell(x, y - 1) == CustomMapDocument.CELL_AIR
                        && (v.surface[x] < 0 || y < v.surface[x])
                        && treeSupportSafe(v, x, y))
                    eligible.add(new MapAnchor(x, y));
        int wanted = (int) Math.round(dryColumns * treePct / 100.0);
        ArrayList<MapAnchor> selected = selectVolcanoTreeCandidates(eligible, random, wanted);
        for (MapAnchor spot : selected)
            v.trees.add(new TreePlacement(spot.x, spot.y,
                    random.nextInt() & 0x7fffffff));
        v.objectCount = v.trees.size();
        return selected.size() < wanted
                ? "Natural tree spacing capped decorations at "
                + selected.size() + " of " + wanted + "." : "";
    }

    private static ArrayList<MapAnchor> selectVolcanoTreeCandidates(
            ArrayList<MapAnchor> source, Random random, int wanted) {
        ArrayList<MapAnchor> shuffled = new ArrayList<MapAnchor>(source);
        Collections.shuffle(shuffled, random);
        int maximumX = -1;
        for (MapAnchor candidate : source) maximumX = Math.max(maximumX, candidate.x);
        boolean[] seenX = new boolean[Math.max(0, maximumX + 1)];
        ArrayList<MapAnchor> unique = new ArrayList<MapAnchor>();
        for (MapAnchor candidate : shuffled)
            if (candidate.x >= 0 && candidate.x < seenX.length && !seenX[candidate.x]) {
                seenX[candidate.x] = true;
                unique.add(candidate);
            }

        int target = Math.min(Math.max(0, wanted),
                Math.max(0, Math.round(unique.size() * .50f)));
        ArrayList<MapAnchor> best = new ArrayList<MapAnchor>();
        for (int attempt = 0; attempt < 48 && best.size() < target; attempt++) {
            ArrayList<MapAnchor> order = new ArrayList<MapAnchor>(unique);
            Collections.shuffle(order, random);
            ArrayList<MapAnchor> candidateSet = new ArrayList<MapAnchor>();
            for (MapAnchor candidate : order) {

                if (!hasTreeWithin(candidateSet, candidate.x, 2))
                    candidateSet.add(candidate);
                if (candidateSet.size() >= target) break;
            }
            if (candidateSet.size() > best.size()) best = candidateSet;
        }

        if (best.size() < target) {
            ArrayList<MapAnchor> companions = new ArrayList<MapAnchor>(unique);
            Collections.shuffle(companions, random);
            for (MapAnchor candidate : companions) {
                if (!hasTreeWithin(best, candidate.x, 2)) best.add(candidate);
                if (best.size() >= target) break;
            }
        }

        if (best.size() < target) {
            ArrayList<MapAnchor> guaranteed = new ArrayList<MapAnchor>(unique);
            Collections.sort(guaranteed, new java.util.Comparator<MapAnchor>() {
                @Override public int compare(MapAnchor a, MapAnchor b) {
                    return Integer.compare(a.x, b.x);
                }
            });
            ArrayList<MapAnchor> spaced = new ArrayList<MapAnchor>();
            for (MapAnchor candidate : guaranteed)
                if (!hasTreeWithin(spaced, candidate.x, 2)) spaced.add(candidate);
            Collections.shuffle(spaced, random);
            if (spaced.size() > target)
                spaced = new ArrayList<MapAnchor>(spaced.subList(0, target));
            if (spaced.size() > best.size()) best = spaced;
        }
        if (best.size() > target)
            return new ArrayList<MapAnchor>(best.subList(0, target));
        return best;
    }

    private static boolean hasTreeWithin(List<MapAnchor> selected, int x, int radius) {
        for (MapAnchor tree : selected)
            if (Math.abs(tree.x - x) <= radius) return true;
        return false;
    }

    private static boolean treeSupportSafe(ModeVariant v, int x, int row) {
        if (v == null || row < 1 || x - 1 < 0 || x + 1 >= v.width) return false;
        boolean mainSurface = v.surface != null && x < v.surface.length
                && v.surface[x] == row;
        for (int ix = x - 1; ix <= x + 1; ix++) {
            if (v.cell(ix, row) != CustomMapDocument.CELL_GROUND
                    || v.cell(ix, row - 1) != CustomMapDocument.CELL_AIR) return false;
            if (mainSurface && (v.surface[ix] != row || v.water[ix]
                    || v.slopeDirection != null && v.slopeDirection[ix] != 0)) return false;
        }
        if (mainSurface) for (int ix = Math.max(0, x - 2);
                                 ix <= Math.min(v.width - 1, x + 2); ix++)
            if (v.water[ix]) return false;
        return true;
    }

    private static void replaceDocumentVolcanoTrees(
            CustomMapDocument doc, double treePct, int complexity, int assetCount) {
        if (doc == null) return;
        replaceVolcanoTrees(doc.battleTerrain, treePct, complexity, assetCount);
        if (doc.variants != null) for (ModeVariant variant : doc.variants.values())
            replaceVolcanoTrees(variant, treePct, complexity, assetCount);
    }

    private static void replaceVolcanoTrees(
            ModeVariant v, double treePct, int complexity, int assetCount) {
        if (v == null) return;
        if (v.profile != null && v.profile.complexityProfile != null
                && v.profile.complexityProfile.capReason != null)
            v.profile.complexityProfile.capReason = v.profile.complexityProfile.capReason
                    .replaceAll("Natural tree spacing capped decorations at \\d+ of \\d+\\.\\s*", "")
                    .trim();
        v.trees.clear();
        String treeCapReason = placeVolcanoTrees(v, treePct,
                new Random(mix(v.seed, "volcano-tree-placement-v2")), complexity);
        finalizeTreeVisuals(v, assetCount);
        recomputeMetrics(v);
        recomputeComplexity(v);
        if (!treeCapReason.isEmpty()) appendCapReason(v, treeCapReason);
    }

    private static void finalizeTreeVisuals(ModeVariant v, int assetCount) {
        if (v == null || v.trees == null || v.trees.isEmpty()) return;
        ArrayList<TreePlacement> ordered = new ArrayList<TreePlacement>(v.trees);
        Collections.sort(ordered, new java.util.Comparator<TreePlacement>() {
            @Override public int compare(TreePlacement a, TreePlacement b) {
                int x = Integer.compare(a == null ? Integer.MAX_VALUE : a.x,
                        b == null ? Integer.MAX_VALUE : b.x);
                if (x != 0) return x;
                return Integer.compare(a == null ? Integer.MAX_VALUE : a.y,
                        b == null ? Integer.MAX_VALUE : b.y);
            }
        });
        Random style = new Random(mix(v.seed, "tree-visuals-v2"));
        ArrayList<Integer> buckets = treeScaleBuckets(ordered.size(), style);
        ArrayList<TreePlacement> assigned = new ArrayList<TreePlacement>();
        boolean forcedSmall = false;
        boolean forcedLarge = false;
        for (int i = 0; i < ordered.size(); i++) {
            TreePlacement tree = ordered.get(i);
            if (tree == null) continue;
            int bucket = buckets.get(i);
            if (bucket == 0) {
                tree.scalePercent = ordered.size() >= 6 && !forcedSmall
                        ? 60 + style.nextInt(9) : 60 + style.nextInt(19);
                forcedSmall = true;
            } else if (bucket == 2) {
                tree.scalePercent = ordered.size() >= 6 && !forcedLarge
                        ? 128 + style.nextInt(8) : 118 + style.nextInt(18);
                forcedLarge = true;
            }
            else tree.scalePercent = 88 + style.nextInt(21);
            tree.xOffsetPercent = -10 + style.nextInt(21);
            tree.asset = chooseTreeAsset(style, assetCount, tree, assigned);
            assigned.add(tree);
        }
    }

    private static ArrayList<Integer> treeScaleBuckets(int count, Random random) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        if (count <= 0) return out;
        if (count == 1) { out.add(1); return out; }
        int small = Math.max(1, Math.round(count * .30f));
        int large = Math.max(1, Math.round(count * .25f));
        int medium = count - small - large;
        if (count >= 3 && medium < 1) {
            if (small >= large && small > 1) small--;
            else if (large > 1) large--;
            medium = count - small - large;
        }
        int[] remaining = {small, medium, large};
        int previous = -1;
        while (out.size() < count) {
            int bestRemaining = -1;
            ArrayList<Integer> choices = new ArrayList<Integer>();
            for (int bucket = 0; bucket < remaining.length; bucket++) {
                if (bucket == previous || remaining[bucket] <= 0) continue;
                if (remaining[bucket] > bestRemaining) {
                    choices.clear();
                    bestRemaining = remaining[bucket];
                }
                if (remaining[bucket] == bestRemaining) choices.add(bucket);
            }
            if (choices.isEmpty()) for (int bucket = 0; bucket < remaining.length; bucket++)
                if (remaining[bucket] > 0) choices.add(bucket);
            int chosen = choices.get(random.nextInt(choices.size()));
            out.add(chosen);
            remaining[chosen]--;
            previous = chosen;
        }
        return out;
    }

    private static int chooseTreeAsset(Random random, int assetCount,
                                       TreePlacement current,
                                       List<TreePlacement> assigned) {
        if (assetCount <= 0) return random.nextInt() & 0x7fffffff;
        int start = Math.floorMod(random.nextInt(), assetCount);
        for (int step = 0; step < assetCount; step++) {
            int candidate = (start + step) % assetCount;
            boolean nearbyDuplicate = false;
            for (TreePlacement prior : assigned)
                if (prior != null && Math.abs(prior.x - current.x) < 6
                        && Math.floorMod(prior.asset, assetCount) == candidate) {
                    nearbyDuplicate = true;
                    break;
                }
            if (!nearbyDuplicate) return candidate;
        }
        return start;
    }

    private static boolean createsDenseTreeRun(List<MapAnchor> selected, int x) {
        boolean left1 = false, left2 = false, right1 = false, right2 = false;
        for (MapAnchor tree : selected) {
            if (tree.x == x) return true;
            left1 |= tree.x == x - 1;
            left2 |= tree.x == x - 2;
            right1 |= tree.x == x + 1;
            right2 |= tree.x == x + 2;
        }
        return left2 && left1 || left1 && right1 || right1 && right2;
    }

    private static void nudgeGroundDensity(ModeVariant v, double groundPct, int complexity) {
        if (complexity <= 15) return;
        int current = 0;
        for (int cell : v.cells) if (cell == CustomMapDocument.CELL_GROUND) current++;
        int lower = (int) Math.ceil(v.cells.length * Math.max(0.0, groundPct - 1.5) / 100.0);
        int upper = (int) Math.floor(v.cells.length * Math.min(100.0, groundPct + 1.5) / 100.0);
        int change = current > upper ? 1 : current < lower ? -1 : 0;
        int remaining = current > upper ? current - upper : current < lower ? lower - current : 0;
        if (change == 0 || remaining <= 0) return;

        for (int end = v.width - 1; end >= 1 && remaining > 0; ) {
            while (end >= 1 && v.surface[end] < 0) end--;
            if (end < 1) break;
            int runStart = end;
            while (runStart > 0 && v.surface[runStart - 1] >= 0) runStart--;
            int available = Math.max(0, end - runStart);
            int take = Math.min(remaining, available);
            int start = end - take + 1;
            int adjusted = 0;
            for (int x = start; x <= end; x++) {
                int oldRow = v.surface[x];
                int newRow = oldRow + change;
                int thickness = v.height - newRow;
                if (thickness < 2 || thickness > 12) continue;
                int progressColumn = x - start + 1;
                float phaseProgress = Math.min(1f, progressColumn / 2f);
                float centreProgress = Math.min(1f, (progressColumn - .5f) / 2f);
                boolean rampCell = progressColumn <= 2;

                if (change > 0) {
                    if (!rampCell) {
                        v.setCell(x, oldRow, CustomMapDocument.CELL_AIR);
                        adjusted++;
                    }
                } else {
                    v.setCell(x, newRow, CustomMapDocument.CELL_GROUND);
                    adjusted++;
                }
                v.surface[x] = rampCell && change > 0 ? oldRow : newRow;
                float oldFloatRow = v.height
                        + v.walkSurfaceLayers[x] / v.layerUnitsPerTile();
                v.walkSurfaceLayers[x] = rowToLayer(v,
                        oldFloatRow + change * centreProgress);
                if (rampCell) {
                    v.slopeDirection[x] = change;
                    v.slopePhase[x] = Math.round(phaseProgress * 100f);
                }
            }
            if (adjusted > 0) v.elevationChanges++;
            remaining -= adjusted;
            end = runStart - 2;
        }
    }

    private static void smoothEnemySurface(ModeVariant v) {
        for (int x = 1; x < v.width; x++) {
            if (v.surface[x - 1] < 0 || v.surface[x] < 0) continue;
            float previous = v.walkSurfaceLayers[x - 1];
            float current = v.walkSurfaceLayers[x];
            float delta = current - previous;
            if (Math.abs(delta) > 4f) {
                current = previous + Math.signum(delta) * 4f;
                v.walkSurfaceLayers[x] = current;
            }
        }
    }

    private static float rowToLayer(ModeVariant v, float row) {

        return -(v.height - row) * v.layerUnitsPerTile();
    }

    private static void buildCheckpoints(ModeVariant v) {
        v.checkpoints.clear();
        v.checkpoints.add(new MapAnchor(v.spawn.x, v.spawn.y));
        int next = v.spawn.x + 20;
        while (next < v.destination.x) {
            MapAnchor cp = snap(v, next);
            if (!v.water[cp.x]) v.checkpoints.add(cp);
            next += 20;
        }
    }

    private static void buildSurfaceGraph(ModeVariant v, MapMode mode) {
        v.surfaceGraph.clear();
        int maxGap = mode == MapMode.HEIST ? 3 : mode == MapMode.ADVENTURE ? 4 : 0;
        buildNavigationLinks(v, false, maxGap);
        for (CustomMapDocument.NavigationLink link : v.navigationLinks)
            if (link.type == CustomMapDocument.NavigationType.WALK
                    || link.type == CustomMapDocument.NavigationType.JUMP)
                v.surfaceGraph.add(new CustomMapDocument.SurfaceEdge(
                        link.fromX, link.toX,
                        link.type == CustomMapDocument.NavigationType.JUMP));
    }

    private static void buildBattleNavigation(ModeVariant v) {
        v.surfaceGraph.clear();
        buildNavigationLinks(v, true, Math.max(1, v.profile.maxJumpGap));
        for (CustomMapDocument.NavigationLink link : v.navigationLinks)
            if (link.type != CustomMapDocument.NavigationType.SWIM)
                v.surfaceGraph.add(new CustomMapDocument.SurfaceEdge(
                        link.fromX, link.toX,
                        link.type == CustomMapDocument.NavigationType.JUMP
                                || link.type == CustomMapDocument.NavigationType.STEP_UP));
    }

    private static void buildNavigationLinks(ModeVariant v, boolean waterTraversable,
                                             int maxJumpGap) {
        if (v.navigationLinks == null)
            v.navigationLinks = new ArrayList<CustomMapDocument.NavigationLink>();
        else v.navigationLinks.clear();
        if (v.surface == null || v.water == null) return;

        for (int x = 0; x < v.width - 1; x++) {
            if (dryMain(v, x) && dryMain(v, x + 1)) {
                float leftLayer = v.walkLayerAtTile(x);
                float rightLayer = v.walkLayerAtTile(x + 1);
                if (v.isContinuousSurfaceBetween(x, x + 1)) {
                    v.navigationLinks.add(new CustomMapDocument.NavigationLink(
                            CustomMapDocument.NavigationType.WALK, x, x + 1,
                            leftLayer, rightLayer, x + 1, x + 1));
                } else {
                    int rows = Math.round(Math.abs(rightLayer - leftLayer)
                            / Math.max(1f, v.layerUnitsPerTile()));
                    if (rows <= Math.max(1, v.profile.maxStepRows)) {
                        CustomMapDocument.NavigationLink right =
                                new CustomMapDocument.NavigationLink(
                                        rightLayer < leftLayer
                                                ? CustomMapDocument.NavigationType.STEP_UP
                                                : CustomMapDocument.NavigationType.DROP_DOWN,
                                        x, x + 1, leftLayer, rightLayer, x + 1, x + 1);
                        right.bidirectional = false;
                        v.navigationLinks.add(right);
                        CustomMapDocument.NavigationLink left =
                                new CustomMapDocument.NavigationLink(
                                        leftLayer < rightLayer
                                                ? CustomMapDocument.NavigationType.STEP_UP
                                                : CustomMapDocument.NavigationType.DROP_DOWN,
                                        x + 1, x, rightLayer, leftLayer, x, x);
                        left.bidirectional = false;
                        v.navigationLinks.add(left);
                    }
                }
            }
        }

        for (int scan = 0; scan < v.width; ) {
            if (dryMain(v, scan)) {
                scan++;
                continue;
            }
            int start = scan;
            boolean allWater = true;
            boolean allVoid = true;
            while (scan < v.width && !dryMain(v, scan)) {
                allWater &= v.water[scan];
                allVoid &= !v.water[scan] && v.surface[scan] < 0;
                scan++;
            }
            int end = scan - 1;
            int left = start - 1;
            int right = scan;
            if (left < 0 || right >= v.width || !dryMain(v, left) || !dryMain(v, right))
                continue;
            int span = end - start + 1;
            CustomMapDocument.NavigationType type = null;
            if (allWater && waterTraversable
                    && Math.abs(v.surface[left] - v.surface[right])
                    <= Math.max(1, v.profile.maxStepRows))
                type = CustomMapDocument.NavigationType.SWIM;
            else if (allVoid && span <= maxJumpGap
                    && Math.abs(v.surface[left] - v.surface[right])
                    <= Math.max(1, v.profile.maxStepRows))
                type = CustomMapDocument.NavigationType.JUMP;
            if (type != null)
                v.navigationLinks.add(new CustomMapDocument.NavigationLink(
                        type, left, right, v.walkLayerAtTile(left),
                        v.walkLayerAtTile(right), start, end));
        }
    }

    private static boolean dryMain(ModeVariant v, int x) {
        return x >= 0 && x < v.width && v.surface[x] >= 0 && !v.water[x];
    }

    private static void rebuildSecondaryPlatforms(ModeVariant v) {
        Map<String, CustomMapDocument.PlatformPatrol> patrols =
                new LinkedHashMap<String, CustomMapDocument.PlatformPatrol>();
        Map<String, CustomMapDocument.SecondaryPlatform> previousPlatforms =
                new LinkedHashMap<String, CustomMapDocument.SecondaryPlatform>();
        if (v.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform old : v.secondaryPlatforms) {
                if (old == null) continue;
                String oldId = old.id;
                if (oldId == null || oldId.trim().isEmpty()) {
                    int oldRow = Math.round(v.height
                            + old.supportLayer / Math.max(1f, v.layerUnitsPerTile()));
                    oldId = stablePlatformId(v, oldRow, old.startX, old.endX);
                }
                previousPlatforms.put(oldId, old);
                if (old.patrol != null) patrols.put(oldId, old.patrol);
            }
        if (v.secondaryPlatforms == null)
            v.secondaryPlatforms = new ArrayList<CustomMapDocument.SecondaryPlatform>();
        else v.secondaryPlatforms.clear();
        for (int row = 0; row < v.height; row++) {
            int x = 0;
            while (x < v.width) {
                while (x < v.width && !secondaryTop(v, x, row)) x++;
                int start = x;
                while (x < v.width && secondaryTop(v, x, row)) x++;
                if (start < x) {
                    CustomMapDocument.SecondaryPlatform platform =
                            new CustomMapDocument.SecondaryPlatform(start, x - 1,
                                    rowToLayer(v, row));
                    platform.id = stablePlatformId(v, row, start, x - 1);
                    CustomMapDocument.PlatformPatrol oldPatrol = patrols.get(platform.id);
                    CustomMapDocument.SecondaryPlatform previous =
                            previousPlatforms.get(platform.id);
                    if (previous != null) {
                        platform.collisionMode = previous.collisionMode;
                        platform.collisionLeftInsetPermille =
                                previous.collisionLeftInsetPermille;
                        platform.collisionRightInsetPermille =
                                previous.collisionRightInsetPermille;
                        platform.collisionTopOffsetPermille =
                                previous.collisionTopOffsetPermille;
                        platform.collisionBottomInsetPermille =
                                previous.collisionBottomInsetPermille;
                    }
                    if (oldPatrol == null)
                        MovingPlatformEngine.initializeAtOrigin(v, platform);
                    else {
                        platform.patrol = oldPatrol;
                        MovingPlatformEngine.normalize(v, platform);
                    }
                    v.secondaryPlatforms.add(platform);
                }
            }
        }
    }

    public static String stablePlatformId(ModeVariant variant, int row,
                                          int startX, int endX) {
        String mode = variant == null || variant.mode == null
                ? "map" : variant.mode.toLowerCase();
        StringBuilder safeMode = new StringBuilder();
        for (int i = 0; i < mode.length(); i++) {
            char c = mode.charAt(i);
            safeMode.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    ? c : '-');
        }
        if (safeMode.length() == 0) safeMode.append("map");
        return "p-" + safeMode + "-r" + Math.max(0, row)
                + "-x" + Math.max(0, startX) + "-" + Math.max(startX, endX);
    }

    private static boolean secondaryTop(ModeVariant v, int x, int row) {
        return v.cell(x, row) == CustomMapDocument.CELL_GROUND
                && v.cell(x, row - 1) != CustomMapDocument.CELL_GROUND
                && (v.surface[x] < 0 || row != v.surface[x]);
    }

    private static void placeEnemies(ModeVariant v, MapSpec spec, MapMode mode, Random random) {
        int count;
        if (mode == MapMode.DUEL) count = 1;
        else if (mode == MapMode.DERBY) count = 6;
        else count = enemyCount(spec, mode);
        if (mode == MapMode.DUEL || mode == MapMode.DERBY) return;
        int hp = difficultyStat(spec.difficulty);
        int span = Math.max(1, v.destination.x - v.spawn.x - 8);
        for (int i = 0; i < count; i++) {
            int target = v.spawn.x + 4 + Math.round((i + 1f) * span / (count + 1f));
            target += random.nextInt(5) - 2;
            MapAnchor a = snap(v, target);
            EnemyPlacement placement = new EnemyPlacement(a.x, a.y, hp, hp);
            if (spec.enemyPool != null && !spec.enemyPool.isEmpty())
                placement.enemyId = spec.enemyPool.get(Math.floorMod(random.nextInt(), spec.enemyPool.size()));
            placement.boss = "Hard".equalsIgnoreCase(spec.difficulty) && i == count - 1;
            v.enemies.add(placement);
        }
    }

    private static int enemyCount(MapSpec spec, MapMode mode) {
        int override = mode == MapMode.ADVENTURE ? spec.adventureEnemyOverride : spec.heistEnemyOverride;
        boolean easy = "Easy".equalsIgnoreCase(spec.difficulty);
        boolean hard = "Hard".equalsIgnoreCase(spec.difficulty);
        int min = mode == MapMode.ADVENTURE
                ? (easy ? 3 : hard ? 7 : 5) : (easy ? 2 : hard ? 4 : 3);
        int max = mode == MapMode.ADVENTURE
                ? (easy ? 12 : hard ? 30 : 20) : (easy ? 8 : hard ? 16 : 12);
        if (override >= 0) return clamp(override, min, max);
        double scale = spec.width / 120.0;
        if (mode == MapMode.ADVENTURE) {
            int value = (int) Math.round((easy ? 6 : hard ? 14 : 10) * scale);
            return clamp(value, min, max);
        }
        int value = (int) Math.round((easy ? 4 : hard ? 8 : 6) * scale);
        return clamp(value, min, max);
    }

    private static int difficultyStat(String difficulty) {
        if ("Easy".equalsIgnoreCase(difficulty)) return 85;
        if ("Hard".equalsIgnoreCase(difficulty)) return 120;
        return 100;
    }

    public static MapAnchor snap(ModeVariant v, int requestedX) {
        int x = clamp(requestedX, 0, v.width - 1);
        for (int d = 0; d < v.width; d++) {
            int a = x - d;
            int b = x + d;
            if (a >= 0 && v.surface[a] >= 0 && !v.water[a]) return new MapAnchor(a, v.surface[a] - 1);
            if (b < v.width && v.surface[b] >= 0 && !v.water[b]) return new MapAnchor(b, v.surface[b] - 1);
        }
        return new MapAnchor(x, Math.max(0, v.height - 2));
    }

    public static MapAnchor snapBase(ModeVariant v, int requestedX) {
        return snapEligibleAnchor(v, requestedX, 4, 0,
                v == null ? -1 : v.width - 1);
    }

    private static boolean isBasePlateau(ModeVariant v, int center) {
        return isEligibleAnchorSite(v, center, 4);
    }

    public static boolean moveAnchor(ModeVariant v, boolean spawn, int x) {
        if (v == null) return false;
        int min = spawn || v.spawn == null ? 0 : v.spawn.x + 5;
        int max = !spawn || v.destination == null ? v.width - 1 : v.destination.x - 5;
        MapAnchor value = snapEligibleAnchor(v, x, 1, min, max);
        if (value == null) {
            v.validation = "No eligible flat, dry anchor site exists near column " + x + ".";
            return false;
        }
        if (spawn) v.spawn = value; else v.destination = value;
        buildCheckpoints(v);
        validate(v, MapMode.fromId(v.mode));
        return true;
    }

    public static boolean moveBattleAnchor(ModeVariant v, boolean enemyBase, int x) {
        if (v == null) return false;
        int min = enemyBase || v.spawn == null ? 0 : v.spawn.x + 9;
        int max = !enemyBase || v.destination == null ? v.width - 1
                : v.destination.x - 9;
        MapAnchor value = snapEligibleAnchor(v, x, 4, min, max);
        if (value == null) {
            v.validation = "No eligible 9-column flat, dry base site exists near column "
                    + x + ". Terrain was not changed.";
            return false;
        }
        if (enemyBase) v.spawn = value; else v.destination = value;
        persistBaseSafeZone(v, value, enemyBase ? "enemy" : "player");
        buildCheckpoints(v);
        buildBattleNavigation(v);
        rebuildSecondaryPlatforms(v);
        v.elevationChanges = countSlopeRuns(v);
        recomputeMetrics(v);
        recomputeComplexity(v);
        validateBattle(v);
        return true;
    }

    private static MapAnchor snapEligibleAnchor(ModeVariant v, int requestedX,
                                                int halfWidth, int minX, int maxX) {
        if (v == null || v.width <= 0) return null;
        minX = clamp(minX, 0, v.width - 1);
        maxX = clamp(maxX, 0, v.width - 1);
        if (minX > maxX) return null;
        int x = clamp(requestedX, minX, maxX);
        for (int d = 0; d < v.width; d++) {
            int left = x - d;
            int right = x + d;
            if (left >= minX && isEligibleAnchorSite(v, left, halfWidth))
                return new MapAnchor(left, v.surface[left] - 1);
            if (right != left && right <= maxX
                    && isEligibleAnchorSite(v, right, halfWidth))
                return new MapAnchor(right, v.surface[right] - 1);
        }
        return null;
    }

    private static boolean isEligibleAnchorSite(ModeVariant v, int center, int halfWidth) {
        if (v == null || v.surface == null || v.water == null
                || center < halfWidth || center >= v.width - halfWidth
                || v.surface[center] < 0 || v.water[center]) return false;
        int row = v.surface[center];
        for (int x = center - halfWidth; x <= center + halfWidth; x++)
            if (v.surface[x] != row || v.water[x]
                    || (v.slopeDirection != null && v.slopeDirection[x] != 0)) return false;

        return true;
    }

    private static void recomputeMetrics(ModeVariant v) {
        int ground = 0;
        for (int cell : v.cells) if (cell == CustomMapDocument.CELL_GROUND) ground++;
        int terrainBandCells = v.width * Math.min(v.height, MAX_GROUND_HEIGHT);
        v.achievedGroundDensity = 100.0 * ground / Math.max(1, terrainBandCells);
        int water = 0, dry = 0;
        int slopeMinY = Integer.MAX_VALUE;
        int slopeMaxY = Integer.MIN_VALUE;
        for (int x = 0; x < v.width; x++) {
            if (v.water[x]) water++;
            else if (v.surface[x] >= 0) {
                dry++;
                if (v.slopeDirection != null && x < v.slopeDirection.length
                        && v.slopeDirection[x] != 0) {
                    int y = Math.round(-v.walkSurfaceLayers[x]
                            / Math.max(1f, v.layerUnitsPerTile()));
                    slopeMinY = Math.min(slopeMinY, y);
                    slopeMaxY = Math.max(slopeMaxY, y);
                }
            }
        }
        v.achievedWaterDensity = 100.0 * water / Math.max(1, water + dry);
        v.achievedTreeDensity = 100.0 * v.trees.size() / Math.max(1, dry);
        v.achievedSlopeCount = countSlopeRuns(v);
        v.achievedSlopeMinY = slopeMinY == Integer.MAX_VALUE ? 0 : slopeMinY;
        v.achievedSlopeMaxY = slopeMaxY == Integer.MIN_VALUE ? 0 : slopeMaxY;
        recomputeCompoundSlopeMetrics(v);
        boolean[] floating = new boolean[Math.max(0, v.width)];
        Set<Integer> floatingRows = new HashSet<Integer>();
        int floatingCount = 0;
        if (v.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform : v.secondaryPlatforms) {
                if (platform == null) continue;
                floatingCount++;
                floatingRows.add(Math.round(-platform.supportLayer
                        / Math.max(1f, v.layerUnitsPerTile())));
                for (int x = Math.max(0, platform.startX);
                     x <= Math.min(v.width - 1, platform.endX); x++)
                    floating[x] = true;
            }
        int floatingColumns = 0;
        for (boolean covered : floating) if (covered) floatingColumns++;
        v.floatingIslandCount = floatingCount;
        v.floatingIslandLayerCount = floatingRows.size();
        v.achievedFloatingIslandDensity = 100.0 * floatingColumns / Math.max(1, v.width);
    }

    private static void recomputeCompoundSlopeMetrics(ModeVariant v) {
        int count = 0;
        int minRise = Integer.MAX_VALUE;
        int maxRise = 0;
        int minLength = Integer.MAX_VALUE;
        int maxLength = 0;
        if (v != null && v.motifs != null)
            for (TerrainMotif motif : v.motifs) {
                if (motif == null || motif.transitions <= 0
                        || (motif.type != TerrainMotifType.RAMP
                        && motif.type != TerrainMotifType.RAMP_CHAIN
                        && motif.type != TerrainMotifType.PEAK
                        && motif.type != TerrainMotifType.VALLEY
                        && motif.type != TerrainMotifType.TERRACE)) continue;
                int rise = Math.max(1, Math.abs(motif.endRow - motif.startRow));
                int length = Math.max(1, motif.endX - motif.startX + 1);
                count++;
                minRise = Math.min(minRise, rise);
                maxRise = Math.max(maxRise, rise);
                minLength = Math.min(minLength, length);
                maxLength = Math.max(maxLength, length);
            }
        v.achievedSlopeMotifCount = count;
        v.achievedSlopeMinRise = count == 0 ? 0 : minRise;
        v.achievedSlopeMaxRise = count == 0 ? 0 : maxRise;
        v.achievedSlopeMinLength = count == 0 ? 0 : minLength;
        v.achievedSlopeMaxLength = count == 0 ? 0 : maxLength;
    }

    private static void recomputeSlopeCoverage(ModeVariant v, MapSpec spec,
                                               int slopeSpan,
                                               boolean reserveBattleBases) {
        if (v == null || spec == null || v.surface == null) return;
        int nativeSpan = Math.max(1, slopeSpan);
        int lowestY = clamp(spec.slopeMinY, 2, v.height);
        int highestY = clamp(spec.slopeMaxY, lowestY, v.height);
        int verticalEnvelope = Math.max(0, highestY - lowestY);
        int maxRiseFromLength = Math.max(1,
                Math.max(1, spec.slopeMaxLength) / nativeSpan);
        int maxChain = Math.max(1, Math.min(spec.slopeMaxRise, maxRiseFromLength));
        if (verticalEnvelope > 0) maxChain = Math.min(maxChain, verticalEnvelope);

        ArrayList<DrySegment> segments = new ArrayList<DrySegment>();
        int x = 0;
        while (x < v.width) {
            while (x < v.width
                    && !slopeCoverageRouteColumn(v, x, reserveBattleBases)) x++;
            int start = x;
            while (x < v.width
                    && slopeCoverageRouteColumn(v, x, reserveBattleBases)) x++;
            int end = x - 1;
            if (start > end) continue;
            boolean leftWater = start > 0 && v.water != null && v.water[start - 1];
            boolean rightWater = end + 1 < v.width && v.water != null && v.water[end + 1];
            addDrySegments(segments, start, end,
                    leftWater ? 2 : 4, rightWater ? 2 : 4,
                    nativeSpan, 0, maxChain, reserveBattleBases, v.width);
        }
        int eligibleColumns = 0;
        for (DrySegment segment : segments) eligibleColumns += segment.capacity * nativeSpan;
        int slopeColumns = 0;
        if (v.slopeDirection != null)
            for (int direction : v.slopeDirection) if (direction != 0) slopeColumns++;
        v.achievedSlopeCoverage = eligibleColumns <= 0 ? 0.0
                : Math.min(100.0, slopeColumns * 100.0 / eligibleColumns);
    }

    private static boolean slopeCoverageRouteColumn(ModeVariant v, int x,
                                                     boolean reserveBattleBases) {
        if (v == null || x < 0 || x >= v.width || v.water == null || v.water[x])
            return false;
        return v.surface[x] >= 0 || reserveBattleBases;
    }

    private static boolean exceedsSlopeCoverageTarget(ModeVariant v, MapSpec spec,
                                                       int slopeSpan,
                                                       boolean reserveBattleBases) {
        if (v == null || spec == null || spec.slopeCoverage <= 0.0) return false;
        double previous = v.achievedSlopeCoverage;
        recomputeSlopeCoverage(v, spec, slopeSpan, reserveBattleBases);
        double achieved = v.achievedSlopeCoverage;
        double tolerance = slopeCoverageTolerance(v, spec, slopeSpan,
                reserveBattleBases);
        v.achievedSlopeCoverage = previous;
        return achieved > Math.min(80.0, spec.slopeCoverage) + tolerance + 0.0001;
    }

    private static void recomputeComplexity(ModeVariant v) {
        if (v == null || v.profile == null) return;
        if (v.profile.complexityProfile == null)
            v.profile.complexityProfile = new ComplexityProfile();
        ComplexityProfile result = v.profile.complexityProfile;
        TierBudget budget = tierBudget(v.profile.complexity, v.width);
        result.requestedTier = budget.tier;
        result.tierName = budget.name;
        result.targetScore = budget.targetScore;

        int slopeRuns = countSlopeRuns(v);
        v.elevationChanges = slopeRuns;
        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        for (int x = 0; x < v.width; x++) {
            if (v.surface[x] < 0 || v.water[x]) continue;
            int row = Math.round(-v.walkLayerAtTile(x)
                    / Math.max(1f, v.layerUnitsPerTile()));
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        int span = minRow == Integer.MAX_VALUE ? 0 : maxRow - minRow;
        int objectClusters = countObjectClusters(v);
        int chasmStepGroups = countChasmStepGroups(v);
        double raw = slopeRuns + 1.5 * span + 2.0 * v.waterZoneCount
                + 2.5 * chasmStepGroups + 1.5 * v.floatingIslandCount
                + 0.5 * objectClusters;
        double normalized = raw * 120.0 / Math.max(1, v.width);
        result.structuralScore = normalized;
        result.elevationSpanRows = span;
        result.objectClusterCount = objectClusters;
        result.chasmStepGroupCount = chasmStepGroups;

        if ("duel".equalsIgnoreCase(v.mode) || "derby".equalsIgnoreCase(v.mode)) {
            result.achievedTier = result.requestedTier;
            result.capReason = "Duel/Derby gameplay profile intentionally remains flat in its play area.";
            return;
        }
        result.achievedTier = Math.min(result.requestedTier, Math.min(10, Math.max(1,
                (int) Math.floor(normalized / 5.0) + 1)));
        if (result.achievedTier < result.requestedTier && result.capReason.trim().isEmpty())
            result.capReason = "Geometry/assets achieved Tier " + result.achievedTier
                    + " of requested Tier " + result.requestedTier + ".";
    }

    private static int countSlopeRuns(ModeVariant v) {
        if (v == null || v.slopeDirection == null) return 0;
        int count = 0;
        int previousRun = 0;
        int previousDirection = 0;
        int previousPhase = 0;
        for (int x = 0; x < v.width; x++) {
            int direction = v.slopeDirection[x];
            if (direction == 0) {
                previousRun = 0;
                previousDirection = 0;
                previousPhase = 0;
                continue;
            }
            int run = v.slopeRunId != null && x < v.slopeRunId.length
                    ? v.slopeRunId[x] : 0;
            int phase = v.slopePhase != null && x < v.slopePhase.length
                    ? v.slopePhase[x] : 0;
            if ((run > 0 && run != previousRun)
                    || (run == 0 && (previousDirection != direction || phase <= previousPhase)))
                count++;
            previousRun = run;
            previousDirection = direction;
            previousPhase = phase;
        }
        return count;
    }

    private static int countObjectClusters(ModeVariant v) {
        if (v == null || v.trees == null || v.trees.isEmpty()) return 0;
        ArrayList<Integer> columns = new ArrayList<Integer>();
        for (TreePlacement tree : v.trees) if (tree != null) columns.add(tree.x);
        Collections.sort(columns);
        int clusters = 0;
        int previous = Integer.MIN_VALUE / 2;
        for (Integer column : columns) {
            if (column - previous > 2) clusters++;
            previous = column;
        }
        return clusters;
    }

    private static int countChasmStepGroups(ModeVariant v) {
        if (v == null || v.motifs == null) return 0;
        int chasms = 0;
        int steps = 0;
        for (TerrainMotif motif : v.motifs) {
            if (motif == null || motif.type == null) continue;
            if (motif.type == TerrainMotifType.CHASM) chasms++;
            else if (motif.type == TerrainMotifType.STEP_UP
                    || motif.type == TerrainMotifType.DROP_DOWN) steps++;
        }
        return chasms + (steps + 1) / 2;
    }

    private static void appendCapReason(ModeVariant v, String reason) {
        if (v == null || v.profile == null) return;
        if (v.profile.complexityProfile == null)
            v.profile.complexityProfile = new ComplexityProfile();
        String current = v.profile.complexityProfile.capReason;
        v.profile.complexityProfile.capReason = current == null || current.trim().isEmpty()
                ? reason : current + " " + reason;
    }

    private static void appendFinalSlopeCap(ModeVariant v, MapSpec spec,
                                            boolean slopesAvailable,
                                            int slopeSpan,
                                            boolean reserveBattleBases) {
        if (v == null || spec == null || v.profile == null) return;
        int requestedCount = Math.max(0, spec.slopeCount);
        double requestedCoverage = Math.max(0.0, Math.min(80.0, spec.slopeCoverage));
        if (requestedCount == 0 && requestedCoverage <= 0.0) return;
        if ("duel".equalsIgnoreCase(v.mode) || "derby".equalsIgnoreCase(v.mode)) return;

        boolean coverageDriven = requestedCoverage > 0.0;
        double coverageTolerance = slopeCoverageTolerance(v, spec, slopeSpan,
                reserveBattleBases);
        boolean countShort = !coverageDriven && v.achievedSlopeCount < requestedCount;
        boolean coverageShort = coverageDriven
                && v.achievedSlopeCoverage + coverageTolerance < requestedCoverage;
        if (!countShort && !coverageShort) return;
        StringBuilder reason = new StringBuilder("Final slope result: ");
        if (!slopesAvailable) reason.append("the theme has no valid paired slope contours; ");
        if (coverageDriven)
            reason.append(oneDecimal(v.achievedSlopeCoverage)).append("% of ")
                    .append(oneDecimal(requestedCoverage))
                    .append("% requested eligible coverage (tolerance ")
                    .append(oneDecimal(coverageTolerance)).append(" percentage points).");
        else
            reason.append(v.achievedSlopeCount).append(" of ").append(requestedCount)
                    .append(" requested legacy section(s).");
        appendCapReason(v, reason.toString());
    }

    private static double slopeCoverageTolerance(ModeVariant v, MapSpec spec,
                                                  int slopeSpan,
                                                  boolean reserveBattleBases) {
        if (v == null || spec == null || v.surface == null) return 3.0;
        int nativeSpan = Math.max(1, slopeSpan);
        int lowestY = clamp(spec.slopeMinY, 2, v.height);
        int highestY = clamp(spec.slopeMaxY, lowestY, v.height);
        int verticalEnvelope = Math.max(0, highestY - lowestY);
        int maxRiseFromLength = Math.max(1,
                Math.max(1, spec.slopeMaxLength) / nativeSpan);
        int maxChain = Math.max(1, Math.min(spec.slopeMaxRise, maxRiseFromLength));
        if (verticalEnvelope > 0) maxChain = Math.min(maxChain, verticalEnvelope);

        ArrayList<DrySegment> segments = new ArrayList<DrySegment>();
        int x = 0;
        while (x < v.width) {
            while (x < v.width
                    && !slopeCoverageRouteColumn(v, x, reserveBattleBases)) x++;
            int start = x;
            while (x < v.width
                    && slopeCoverageRouteColumn(v, x, reserveBattleBases)) x++;
            int end = x - 1;
            if (start > end) continue;
            boolean leftWater = start > 0 && v.water != null && v.water[start - 1];
            boolean rightWater = end + 1 < v.width && v.water != null && v.water[end + 1];
            addDrySegments(segments, start, end,
                    leftWater ? 2 : 4, rightWater ? 2 : 4,
                    nativeSpan, 0, maxChain, reserveBattleBases, v.width);
        }
        int capacity = 0;
        for (DrySegment segment : segments) capacity += segment.capacity;
        double oneNativeSection = capacity <= 0 ? 100.0 : 100.0 / capacity;
        return Math.max(3.0, oneNativeSection);
    }

    private static String appendReason(String current, String reason) {
        return current == null || current.trim().isEmpty()
                ? reason : current + " " + reason;
    }

    private static double oneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public static void validate(ModeVariant v, MapMode mode) {
        if (v == null || mode == null) return;
        AuthoredTileTopology.Issue topologyIssue =
                AuthoredTileTopology.firstIssue(v);
        if (topologyIssue != null) {
            v.reachable = false;
            v.validation = topologyIssue.message();
            return;
        }
        int invalidSlopeJoin = invalidSlopeJoin(v);
        if (invalidSlopeJoin >= 0) {
            v.reachable = false;
            v.validation = "Slope at columns " + invalidSlopeJoin + "/"
                    + (invalidSlopeJoin + 1)
                    + " meets a cliff without a supported flat transition.";
            return;
        }
        v.reachable = reachable(v, mode);
        if (!v.reachable) v.validation = "No traversable route from spawn to destination.";
        else if (v.spawn == null || v.destination == null) v.validation = "Spawn and destination are required.";
        else v.validation = "Ready";
    }

    public static void rebuildAfterManualEdit(ModeVariant v, boolean battle) {
        if (v == null || v.cells == null || v.width <= 0 || v.height <= 0) return;
        int[] previousSurface = v.surface == null ? null : v.surface.clone();
        float[] previousWalk = v.walkSurfaceLayers == null
                ? null : v.walkSurfaceLayers.clone();
        if (v.grid == null) v.grid = new CustomMapDocument.TileGrid();
        v.grid.width = v.width;
        v.grid.height = v.height;
        v.grid.cells = v.cells;
        if (v.surface == null || v.surface.length != v.width)
            v.surface = new int[v.width];
        if (v.walkSurfaceLayers == null || v.walkSurfaceLayers.length != v.width)
            v.walkSurfaceLayers = new float[v.width];
        if (v.surfaceMaterials == null || v.surfaceMaterials.length != v.width)
            v.surfaceMaterials = new byte[v.width];
        if (v.water == null || v.water.length != v.width)
            v.water = new boolean[v.width];
        if (v.slopeDirection == null || v.slopeDirection.length != v.width)
            v.slopeDirection = new int[v.width];
        if (v.slopePhase == null || v.slopePhase.length != v.width)
            v.slopePhase = new int[v.width];
        if (v.slopeRunId == null || v.slopeRunId.length != v.width)
            v.slopeRunId = new int[v.width];
        if (v.profile == null) v.profile = new CustomMapDocument.TerrainProfile();

        for (int x = 0; x < v.width; x++) {
            boolean hasWater = false;
            for (int y = 0; y < v.height; y++)
                hasWater |= v.cell(x, y) == CustomMapDocument.CELL_WATER;
            int y = v.height - 1;
            while (y >= 0 && v.cell(x, y) == CustomMapDocument.CELL_GROUND) y--;
            int top = y == v.height - 1 ? -1 : y + 1;
            int manualBridgeRow = manualIceBridgeRow(v, x);
            if (manualBridgeRow >= 0
                    && v.cell(x, manualBridgeRow) == CustomMapDocument.CELL_GROUND)
                top = manualBridgeRow;
            v.surface[x] = top;
            v.water[x] = top < 0 && hasWater;
            boolean unchangedSlope = top >= 0 && previousSurface != null
                    && previousWalk != null && x < previousSurface.length
                    && x < previousWalk.length && previousSurface[x] == top
                    && v.slopeDirection[x] != 0;
            v.walkSurfaceLayers[x] = top < 0 ? 0f : unchangedSlope
                    ? previousWalk[x] : rowToLayer(v, top);
            v.surfaceMaterials[x] = top >= 0
                    && CustomMapDocument.MATERIAL_ICE.equals(v.materialAt(x, top))
                    ? CustomMapDocument.SURFACE_ICE
                    : CustomMapDocument.SURFACE_NORMAL;
            if (top < 0 || v.water[x]) {
                v.slopeDirection[x] = 0;
                v.slopePhase[x] = 0;
                v.slopeRunId[x] = 0;
            }
        }
        normalizeWaterLevels(v);
        rebuildSecondaryPlatforms(v);
        applyManualPlatformMaterials(v);
        snapObjectsAfterTerrainEdit(v);
        if (v.spawn != null && v.spawn.x >= 0 && v.spawn.x < v.width
                && v.surface[v.spawn.x] >= 0) v.spawn.y = v.surface[v.spawn.x] - 1;
        if (v.destination != null && v.destination.x >= 0
                && v.destination.x < v.width && v.surface[v.destination.x] >= 0)
            v.destination.y = v.surface[v.destination.x] - 1;
        buildCheckpoints(v);
        if (battle) buildBattleNavigation(v);
        else buildSurfaceGraph(v, MapMode.fromId(v.mode));
        recomputeMetrics(v);
        recomputeComplexity(v);
        if (battle) validateBattle(v);
        else validate(v, MapMode.fromId(v.mode));
    }

    private static int manualIceBridgeRow(ModeVariant v, int x) {
        if (v == null || v.manualIceBridges == null) return -1;
        for (CustomMapDocument.ManualIceBridge bridge : v.manualIceBridges)
            if (bridge != null && x >= bridge.startX && x <= bridge.endX)
                return bridge.row;
        return -1;
    }

    private static void applyManualPlatformMaterials(ModeVariant v) {
        if (v.secondaryPlatforms == null) return;
        for (CustomMapDocument.SecondaryPlatform platform : v.secondaryPlatforms) {
            if (platform == null) continue;
            int row = Math.max(0, Math.min(v.height - 1, Math.round(
                    v.height + platform.supportLayer
                            / Math.max(1f, v.layerUnitsPerTile()))));
            platform.surfaceMaterial = CustomMapDocument.SURFACE_NORMAL;
            for (int x = platform.startX; x <= platform.endX; x++)
                if (CustomMapDocument.MATERIAL_ICE.equals(v.materialAt(x, row))) {
                    platform.surfaceMaterial = CustomMapDocument.SURFACE_ICE;
                    break;
                }
        }
    }

    private static void snapObjectsAfterTerrainEdit(ModeVariant v) {
        if (v.trees != null)
            for (TreePlacement tree : v.trees)
                if (tree != null && tree.x >= 0 && tree.x < v.width
                        && v.surface[tree.x] >= 0 && !v.water[tree.x])
                    tree.y = v.surface[tree.x] - 1;
        if (v.props != null)
            for (CustomMapDocument.PropPlacement prop : v.props)
                if (prop != null && prop.x >= 0 && prop.x < v.width
                        && v.surface[prop.x] >= 0 && !v.water[prop.x])
                    prop.y = v.surface[prop.x] - 1;
    }

    public static void validateBattle(ModeVariant v) {
        if (v == null) return;
        AuthoredTileTopology.Issue topologyIssue =
                AuthoredTileTopology.firstIssue(v);
        if (topologyIssue != null) {
            v.reachable = false;
            v.validation = topologyIssue.message();
            return;
        }
        int invalidSlopeJoin = invalidSlopeJoin(v);
        if (invalidSlopeJoin >= 0) {
            v.reachable = false;
            v.validation = "Slope at columns " + invalidSlopeJoin + "/"
                    + (invalidSlopeJoin + 1)
                    + " meets a cliff/step without a supported flat transition.";
            return;
        }
        if (v.spawn == null || v.destination == null) {
            v.reachable = false;
            v.validation = "Enemy base (S) and player base (G) are required.";
            return;
        }
        if (v.spawn.x >= v.destination.x) {
            v.reachable = false;
            v.validation = "Enemy base (S) must be left of player base (G).";
            return;
        }
        if (!isBasePlateau(v, v.spawn.x) || !isBasePlateau(v, v.destination.x)) {
            v.reachable = false;
            v.validation = "Both bases must stand on a wide, flat, dry plateau.";
            return;
        }
        if (!hasSafeZone(v, "enemy", v.spawn.x)
                || !hasSafeZone(v, "player", v.destination.x)) {
            v.reachable = false;
            v.validation = "Both bases require a persisted flat, dry spawn-safe zone.";
            return;
        }
        int x = v.spawn.x;
        while (x <= v.destination.x) {
            if (v.surface[x] >= 0 || v.water[x]) {
                x++;
                continue;
            }
            int start = x;
            while (x <= v.destination.x && v.surface[x] < 0 && !v.water[x]) x++;
            int gap = x - start;
            if (start <= v.spawn.x || x > v.destination.x
                    || gap > Math.max(1, v.profile.maxJumpGap)
                    || v.surface[start - 1] < 0 || v.surface[x] < 0
                    || Math.abs(v.surface[start - 1] - v.surface[x])
                    > Math.max(1, v.profile.maxStepRows)) {
                v.reachable = false;
                v.validation = "A chasm between the bases exceeds the normal-unit jump envelope.";
                return;
            }
        }
        if (!navigationReaches(v, v.spawn.x, v.destination.x)) {
            v.reachable = false;
            v.validation = "The WALK/JUMP/SWIM navigation links do not connect both bases.";
            return;
        }
        v.reachable = true;
        v.validation = "Ready for normal BCU stage play";
    }

    private static int invalidSlopeJoin(ModeVariant v) {
        if (v == null || v.slopeDirection == null) return -1;
        for (int x = 0; x + 1 < v.width; x++) {
            if (!dryMain(v, x) || !dryMain(v, x + 1)) continue;
            boolean slope = v.slopeDirection[x] != 0
                    || v.slopeDirection[x + 1] != 0;
            if (slope && !v.isContinuousSurfaceBetween(x, x + 1)) return x;
        }
        return -1;
    }

    private static boolean hasSafeZone(ModeVariant v, String role, int center) {
        if (v.baseSafeZones == null) return false;
        for (CustomMapDocument.BaseSafeZone zone : v.baseSafeZones)
            if (zone != null && role.equals(zone.role) && zone.centerX == center
                    && zone.endX - zone.startX + 1 >= 7) return true;
        return false;
    }

    private static boolean navigationReaches(ModeVariant v, int from, int to) {
        if (v.navigationLinks == null || v.navigationLinks.isEmpty()) return false;
        boolean[] seen = new boolean[v.width];
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        seen[from] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            int x = queue.removeFirst();
            if (x == to) return true;
            for (CustomMapDocument.NavigationLink link : v.navigationLinks) {
                if (link == null) continue;
                int next = -1;
                if (link.fromX == x) next = link.toX;
                else if (link.bidirectional && link.toX == x) next = link.fromX;
                if (next >= 0 && next < v.width && !seen[next]) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private static boolean reachable(ModeVariant v, MapMode mode) {
        if (v.spawn == null || v.destination == null) return false;
        if (mode == MapMode.DUEL || mode == MapMode.DERBY) {
            for (int x = Math.min(v.spawn.x, v.destination.x); x <= Math.max(v.spawn.x, v.destination.x); x++)
                if (v.surface[x] < 0 || v.water[x]) return false;
            return true;
        }
        int maxGap = mode == MapMode.HEIST ? 3 : 4;
        int maxStep = mode == MapMode.HEIST ? 3 : 5;
        boolean[] seen = new boolean[v.width];
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        queue.add(v.spawn.x);
        seen[v.spawn.x] = true;
        while (!queue.isEmpty()) {
            int x = queue.removeFirst();
            if (x == v.destination.x) return true;
            for (int nx = Math.max(0, x - maxGap - 1); nx <= Math.min(v.width - 1, x + maxGap + 1); nx++) {
                if (seen[nx] || v.surface[nx] < 0) continue;
                int distance = Math.abs(nx - x);
                if (distance > 1) {
                    boolean gapOnly = true;
                    int lo = Math.min(x, nx), hi = Math.max(x, nx);
                    for (int m = lo + 1; m < hi; m++) if (v.surface[m] >= 0) { gapOnly = false; break; }
                    if (!gapOnly || distance - 1 > maxGap) continue;
                }
                if (Math.abs(v.surface[nx] - v.surface[x]) > maxStep) continue;
                seen[nx] = true;
                queue.add(nx);
            }
        }
        return false;
    }

    private static long mix(long seed, String text) {
        long value = seed ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < text.length(); i++) {
            value ^= text.charAt(i);
            value *= 0x100000001B3L;
            value ^= value >>> 29;
        }
        return value;
    }

    private static MapSpec copy(MapSpec in) {
        MapSpec out = new MapSpec();
        out.name = cleanName(in.name);
        out.biome = in.biome == null ? "" : in.biome.trim();
        out.seed = in.seed;
        out.width = in.width;
        out.height = in.height;
        out.groundDensity = in.groundDensity;
        out.waterDensity = in.waterDensity;
        out.treeDensity = in.treeDensity;
        out.propDensity = in.propDensity;
        out.iceSurfaceDensity = in.iceSurfaceDensity;
        out.iceBridgeDensity = in.iceBridgeDensity;
        out.slopeMinY = in.slopeMinY;
        out.slopeMaxY = in.slopeMaxY;
        out.slopeCount = in.slopeCount;
        out.slopeCoverage = in.slopeCoverage;
        out.slopeMinRise = in.slopeMinRise;
        out.slopeMaxRise = in.slopeMaxRise;
        out.slopeMinLength = in.slopeMinLength;
        out.slopeMaxLength = in.slopeMaxLength;
        out.floatingIslandDensity = in.floatingIslandDensity;
        out.floatingIslandCount = in.floatingIslandCount;
        out.floatingIslandLayers = in.floatingIslandLayers;
        out.complexity = in.complexity;
        out.difficulty = in.difficulty == null ? "Normal" : in.difficulty;
        out.adventureEnemyOverride = in.adventureEnemyOverride;
        out.heistEnemyOverride = in.heistEnemyOverride;
        out.enemyPool.clear();
        if (in.enemyPool != null) out.enemyPool.addAll(in.enemyPool);
        out.modes.clear();
        if (in.modes != null) out.modes.addAll(in.modes);
        return out;
    }

    private static String cleanName(String name) {
        return name == null ? "" : name.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
