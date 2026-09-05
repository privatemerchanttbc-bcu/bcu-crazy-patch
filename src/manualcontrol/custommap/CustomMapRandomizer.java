package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.MapSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class CustomMapRandomizer {

    enum Preset {
        ANY("Anything", 30, 120, 8, 85, 0, 60, 0, 100, 0, 100, 0, 28, 0, 12, 0, 25),
        FLAT("Flat & sparse", 40, 100, 35, 55, 0, 8, 0, 25, 0, 15, 0, 1, 0, 1, 0, 0),
        ROLLING_HILLS("Rolling hills", 60, 120, 35, 60, 0, 15, 10, 45, 20, 45, 3, 10, 1, 4, 10, 30),
        RIVERLANDS("Riverlands", 60, 120, 30, 55, 25, 45, 15, 60, 45, 75, 2, 8, 1, 5, 5, 20),
        MOUNTAINOUS("Mountainous", 70, 120, 40, 70, 0, 20, 10, 50, 70, 95, 10, 20, 3, 8, 20, 45),
        SLOPE_HEAVY("Slope-heavy", 90, 120, 45, 75, 0, 15, 10, 45, 65, 100, 24, 60, 0, 4, 30, 80),
        EXTREME("Extreme terrain", 90, 120, 35, 70, 10, 35, 30, 80, 90, 100, 18, 32, 6, 12, 30, 60),
        WATER_HEAVY("Water-heavy", 75, 120, 25, 50, 40, 60, 10, 60, 60, 100, 3, 10, 2, 6, 0, 15),
        PERFORMANCE("Performance friendly", 30, 75, 35, 55, 0, 10, 0, 15, 0, 30, 0, 4, 0, 2, 0, 5);

        final String label;
        final int widthMin, widthMax;
        final int groundMin, groundMax;
        final int waterMin, waterMax;
        final int treeMin, treeMax;
        final int complexityMin, complexityMax;
        final int slopeCountMin, slopeCountMax;
        final int islandMin, islandMax;
        final int slopeCoverageMin, slopeCoverageMax;

        Preset(String label, int widthMin, int widthMax,
               int groundMin, int groundMax, int waterMin, int waterMax,
               int treeMin, int treeMax, int complexityMin, int complexityMax,
               int slopeCountMin, int slopeCountMax, int islandMin, int islandMax,
               int slopeCoverageMin, int slopeCoverageMax) {
            this.label = label;
            this.widthMin = widthMin;
            this.widthMax = widthMax;
            this.groundMin = groundMin;
            this.groundMax = groundMax;
            this.waterMin = waterMin;
            this.waterMax = waterMax;
            this.treeMin = treeMin;
            this.treeMax = treeMax;
            this.complexityMin = complexityMin;
            this.complexityMax = complexityMax;
            this.slopeCountMin = slopeCountMin;
            this.slopeCountMax = slopeCountMax;
            this.islandMin = islandMin;
            this.islandMax = islandMax;
            this.slopeCoverageMin = slopeCoverageMin;
            this.slopeCoverageMax = slopeCoverageMax;
        }

        @Override public String toString() {
            return label;
        }
    }

    private CustomMapRandomizer() {}

    static MapSpec create(List<TileCatalog.TileSet> available, Random random) {
        return create(available, random, Preset.ANY);
    }

    static MapSpec create(List<TileCatalog.TileSet> available, Random random,
                          Preset requestedPreset) {
        if (random == null) throw new IllegalArgumentException("Random source is missing.");
        Preset preset = requestedPreset == null ? Preset.ANY : requestedPreset;
        ArrayList<TileCatalog.TileSet> usable = new ArrayList<TileCatalog.TileSet>();
        if (available != null) for (TileCatalog.TileSet set : available) {
            if (set == null || !set.isUsable(0.0, 0.0)) continue;
            usable.add(set);
        }
        if (usable.isEmpty())
            throw new IllegalArgumentException(missingThemeMessage());

        TileCatalog.TileSet selected = usable.get(random.nextInt(usable.size()));
        MapSpec spec = new MapSpec();
        spec.seed = random.nextLong();
        String suffix = Long.toUnsignedString(spec.seed, 36).toUpperCase(Locale.ROOT);
        if (suffix.length() > 8) suffix = suffix.substring(suffix.length() - 8);
        spec.name = preset.label + " " + suffix;
        spec.biome = selected.biome;
        spec.width = between(random, preset.widthMin, preset.widthMax);
        spec.height = between(random, CustomMapGenerator.MIN_MAP_HEIGHT,
                CustomMapGenerator.MAX_MAP_HEIGHT);
        spec.groundDensity = between(random, preset.groundMin, preset.groundMax);
        spec.waterDensity = selected.water.isEmpty() ? 0.0
                : between(random, preset.waterMin, preset.waterMax);
        spec.treeDensity = selected.trees.isEmpty() ? 0.0
                : between(random, preset.treeMin, preset.treeMax);
        spec.iceSurfaceDensity = selected.supportsIceSurfaceDensity()
                ? between(random, 5, 20) : 0.0;
        spec.iceBridgeDensity = CustomMapGenerator.iceBridgesAllowed(
                selected.supportsIceSurfaceDensity(), selected)
                ? between(random, 20, 60) : 0.0;
        spec.propDensity = !selected.supportsProps() ? 0.0
                : between(random, 0, Math.min(100, preset.treeMax));
        spec.complexity = between(random, preset.complexityMin, preset.complexityMax);
        TileCatalog.TileSet slopeTiles = selected.resolveBaseGroundFamily(spec.seed);
        boolean supportsSlopes = slopeTiles != null && slopeTiles.supportsSlopes();
        spec.slopeCount = supportsSlopes
                ? between(random, preset.slopeCountMin, preset.slopeCountMax) : 0;
        spec.slopeCoverage = supportsSlopes
                ? between(random, preset.slopeCoverageMin, preset.slopeCoverageMax) : 0.0;
        int requestedSpan = Math.max(1, Math.min(10, 1 + spec.complexity / 11));
        int maximumTerrainY = Math.min(CustomMapGenerator.MAX_GROUND_HEIGHT,
                spec.height);
        spec.slopeMinY = between(random, 2,
                Math.max(2, maximumTerrainY - requestedSpan));
        spec.slopeMaxY = Math.min(maximumTerrainY,
                spec.slopeMinY + requestedSpan);
        if (supportsSlopes) {
            int availableRise = Math.max(1, Math.min(10,
                    spec.slopeMaxY - spec.slopeMinY));
            int preferredMinRise = Math.max(1, Math.min(availableRise,
                    1 + spec.complexity / 35));
            spec.slopeMinRise = between(random, 1, preferredMinRise);
            int maxRiseFloor = Math.max(spec.slopeMinRise, availableRise - 2);
            spec.slopeMaxRise = between(random, maxRiseFloor, availableRise);

            int usableLength = Math.max(1, Math.min(60, spec.width - 18));
            int complexityLength = Math.max(1, Math.min(60,
                    4 + (int) Math.round(spec.complexity * 56.0 / 100.0)));
            int maxLengthBudget = Math.min(usableLength, complexityLength);
            int minLengthLow = Math.max(1, maxLengthBudget / 8);
            int minLengthHigh = Math.max(minLengthLow, maxLengthBudget / 3);
            spec.slopeMinLength = between(random, minLengthLow, minLengthHigh);
            int maxLengthLow = Math.max(spec.slopeMinLength,
                    (maxLengthBudget * 2) / 3);
            spec.slopeMaxLength = between(random, maxLengthLow,
                    maxLengthBudget);
        }
        spec.floatingIslandDensity = 0.0;
        spec.floatingIslandCount = between(random, preset.islandMin, preset.islandMax);
        spec.floatingIslandLayers = spec.floatingIslandCount == 0 ? 0
                : between(random, 1, Math.min(spec.floatingIslandCount,
                CustomMapGenerator.maxFloatingIslandLayers(spec.height)));

        String[] difficulties = {"Easy", "Normal", "Hard"};
        spec.difficulty = difficulties[random.nextInt(difficulties.length)];
        spec.adventureEnemyOverride = randomEnemyOverride(random, spec.difficulty, true);
        spec.heistEnemyOverride = randomEnemyOverride(random, spec.difficulty, false);

        spec.enemyPool.clear();

        return spec;
    }

    private static String missingThemeMessage() {
        return "No usable biome is available for Random generation.";
    }

    private static int randomEnemyOverride(Random random, String difficulty,
                                           boolean adventure) {
        if (random.nextInt(4) == 0) return -1;
        boolean easy = "Easy".equalsIgnoreCase(difficulty);
        boolean hard = "Hard".equalsIgnoreCase(difficulty);
        int min = adventure ? (easy ? 3 : hard ? 7 : 5)
                : (easy ? 2 : hard ? 4 : 3);
        int max = adventure ? (easy ? 12 : hard ? 30 : 20)
                : (easy ? 8 : hard ? 16 : 12);
        return between(random, min, max);
    }

    private static int between(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
