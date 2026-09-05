package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.BaseSafeZone;
import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.PlatformPatrol;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MovingPlatformValidator {

    public static final float BODY_CLEARANCE_TILES = 0.25f;
    public static final float HEADROOM_TILES = 2f;
    public static final float DOCK_HEIGHT_TOLERANCE_TILES = 0.25f;
    private static final float PATH_SAMPLE_TILES = 0.125f;
    private static final float OTHER_PATH_SAMPLE_TILES = 0.25f;
    private static final float EPSILON = 0.001f;

    private MovingPlatformValidator() {}

    public static final class Issue {
        public final String platformId;
        public final String code;
        public final String message;
        public final boolean blocking;

        Issue(String platformId, String code, String message, boolean blocking) {
            this.platformId = platformId == null ? "" : platformId;
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
            this.blocking = blocking;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    public static List<Issue> validate(ModeVariant variant) {
        List<Issue> out = new ArrayList<Issue>();
        if (variant == null || variant.secondaryPlatforms == null) return out;
        for (SecondaryPlatform platform : variant.secondaryPlatforms)
            out.addAll(validate(variant, platform));
        return out;
    }

    public static List<Issue> validate(ModeVariant variant, SecondaryPlatform platform) {
        Collector issues = new Collector(platformId(variant, platform));
        if (variant == null) {
            issues.add("NO_TERRAIN", "Patrol terrain is missing.");
            return issues.values;
        }
        if (platform == null) {
            issues.add("NO_PLATFORM", "The selected floating island is missing.");
            return issues.values;
        }
        if (!platform.isPatrolling()) return issues.values;
        PlatformPatrol patrol = platform.patrol;
        if (patrol == null || !finite(patrol.ax) || !finite(patrol.ay)
                || !finite(patrol.bx) || !finite(patrol.by)
                || !finite(patrol.speedTilesPerSecond)
                || !finite(patrol.durationSeconds) || !finite(patrol.dwellSeconds)) {
            issues.add("NON_FINITE", label(issues.platformId)
                    + " contains an invalid number.");
            return issues.values;
        }
        if (platform.widthTiles() <= 0) {
            issues.add("EMPTY_PLATFORM", label(issues.platformId)
                    + " has no platform tiles.");
            return issues.values;
        }
        Endpoint a = new Endpoint(patrol.ax, patrol.ay);
        Endpoint b = new Endpoint(patrol.bx, patrol.by);
        if (distance(a.x, a.y, b.x, b.y)
                <= MovingPlatformEngine.POSITION_EPSILON_TILES)
            issues.add("IDENTICAL_ENDPOINTS", label(issues.platformId)
                    + " needs two different patrol endpoints.");
        checkEndpointBounds(variant, platform, a, "A", issues);
        checkEndpointBounds(variant, platform, b, "B", issues);
        if (!issues.has("NON_FINITE") && !issues.has("EMPTY_PLATFORM")) {
            checkSweptTerrain(variant, platform, a, b, issues);
            checkReservedZones(variant, platform, a, b, issues);
            checkOtherMovingPlatforms(variant, platform, a, b, issues);
        }
        return issues.values;
    }

    public static String firstBlockingMessage(ModeVariant variant,
                                              SecondaryPlatform platform) {
        for (Issue issue : validate(variant, platform))
            if (issue.blocking) return issue.message;
        return "";
    }

    public static String firstBlockingMessage(ModeVariant variant) {
        for (Issue issue : validate(variant))
            if (issue.blocking) return issue.message;
        return "";
    }

    private static void checkEndpointBounds(ModeVariant variant,
                                            SecondaryPlatform platform,
                                            Endpoint endpoint, String name,
                                            Collector issues) {
        float half = platform.widthTiles() * 0.5f;
        if (endpoint.x - half < -EPSILON
                || endpoint.x + half > variant.width + EPSILON)
            issues.add("OUT_OF_BOUNDS_X", label(issues.platformId)
                    + " endpoint " + name
                    + " puts part of the island outside the map's left/right edge.");

        if (endpoint.y < 1f - EPSILON || endpoint.y > variant.height + EPSILON)
            issues.add("OUT_OF_BOUNDS_Y", label(issues.platformId)
                    + " endpoint " + name
                    + " puts part of the island outside the terrain grid height.");
    }

    private static void checkSweptTerrain(ModeVariant variant,
                                          SecondaryPlatform platform,
                                          Endpoint a, Endpoint b,
                                          Collector issues) {
        int steps = sampleCount(a, b, PATH_SAMPLE_TILES);
        Set<Long> movingOrigins = movingOriginCells(variant);
        for (int step = 0; step <= steps; step++) {
            float t = step / (float) steps;
            float center = lerp(a.x, b.x, t);
            float support = lerp(a.y, b.y, t);
            float bodyLeft = platform.collisionLeftTileX(center);
            float bodyRight = platform.collisionRightTileX(center);
            float bodyTop = platform.collisionSupportTileY(support);
            float bodyBottom = platform.collisionBodyBottomTileY(support);
            int minX = Math.max(0, (int) Math.floor(bodyLeft));
            int maxX = Math.min(variant.width - 1,
                    (int) Math.ceil(bodyRight) - 1);
            for (int x = minX; x <= maxX; x++) {
                for (int row = 0; row < variant.height; row++) {
                    int cell = variant.cell(x, row);

                    if (cell != CustomMapDocument.CELL_GROUND) continue;
                    long key = cellKey(x, row);

                    if (movingOrigins.contains(key)) continue;
                    float cellBottom = variant.height - row - 1f;
                    float cellTop = cellBottom + 1f;
                    if (IceBridgeBuilder.isDeckCell(variant, x, row)) {
                        if (bodyBottom < cellTop - EPSILON
                                && bodyTop > cellTop + EPSILON)
                            issues.add("BODY_TERRAIN", label(issues.platformId)
                                    + " passes through an ice bridge surface.");
                        continue;
                    }
                    boolean actual = overlaps(bodyLeft, bodyRight, bodyBottom, bodyTop,
                            x, x + 1f, cellBottom, cellTop, EPSILON);
                    if (actual) {
                        issues.add("BODY_TERRAIN",
                                label(issues.platformId)
                                        + " passes through terrain.");
                    }
                }
            }
        }
    }

    private static void checkReservedZones(ModeVariant variant,
                                           SecondaryPlatform platform,
                                           Endpoint a, Endpoint b,
                                           Collector issues) {
        int steps = sampleCount(a, b, PATH_SAMPLE_TILES);
        for (int step = 0; step <= steps; step++) {
            float t = step / (float) steps;
            float center = lerp(a.x, b.x, t);
            float support = lerp(a.y, b.y, t);
            float left = platform.collisionLeftTileX(center);
            float right = platform.collisionRightTileX(center);
            float bottom = platform.collisionBodyBottomTileY(support);
            float top = platform.collisionSupportTileY(support);
            if (variant.baseSafeZones != null)
                for (BaseSafeZone zone : variant.baseSafeZones) {
                    if (!isBasePosition(zone) || !finite(zone.supportLayer)) continue;
                    float zoneSupport = -zone.supportLayer
                            / Math.max(1f, variant.layerUnitsPerTile());
                    if (overlaps(left, right, bottom, top,
                            zone.centerX - 0.5f, zone.centerX + 1.5f,
                            zoneSupport, zoneSupport + HEADROOM_TILES,
                            EPSILON))
                        issues.add("BASE_POSITION", label(issues.platformId)
                                + " crosses the " + zone.role + " base position.");
                }
        }
    }

    private static boolean isBasePosition(BaseSafeZone zone) {
        return zone != null && ("player".equals(zone.role)
                || "enemy".equals(zone.role));
    }

    private static void checkOtherMovingPlatforms(ModeVariant variant,
                                                  SecondaryPlatform platform,
                                                  Endpoint a, Endpoint b,
                                                  Collector issues) {
        if (variant.secondaryPlatforms == null) return;
        float half = platform.widthTiles() * 0.5f;
        for (SecondaryPlatform other : variant.secondaryPlatforms) {
            if (other == null || other == platform || !other.isPatrolling()
                    || other.patrol == null || other.widthTiles() <= 0) continue;
            PlatformPatrol recipe = other.patrol;
            if (!finite(recipe.ax) || !finite(recipe.ay)
                    || !finite(recipe.bx) || !finite(recipe.by)) continue;
            Endpoint c = new Endpoint(recipe.ax, recipe.ay);
            Endpoint d = new Endpoint(recipe.bx, recipe.by);
            float otherHalf = other.widthTiles() * 0.5f;
            if (!envelopesOverlap(a, b, half, c, d, otherHalf)) continue;
            int firstSteps = sampleCount(a, b, OTHER_PATH_SAMPLE_TILES);
            boolean intersects = false;
            for (int i = 0; i <= firstSteps && !intersects; i++) {
                float t = i / (float) firstSteps;
                float x = lerp(a.x, b.x, t);
                float y = lerp(a.y, b.y, t);
                float horizontalReach = half + otherHalf + BODY_CLEARANCE_TILES;

                float verticalReach = 1f + HEADROOM_TILES;
                intersects = segmentIntersectsBox(c.x, c.y, d.x, d.y,
                        x - horizontalReach, x + horizontalReach,
                        y - verticalReach, y + verticalReach);
            }
            if (intersects)
                issues.add("MOVING_PATH_CROSSING", label(issues.platformId)
                        + " intersects the swept route of "
                        + label(platformId(variant, other))
                        + ". Moving islands cannot dock with each other.");
        }
    }

    private static boolean envelopesOverlap(Endpoint a, Endpoint b, float half,
                                            Endpoint c, Endpoint d, float otherHalf) {
        float clear = BODY_CLEARANCE_TILES;
        float leftA = Math.min(a.x, b.x) - half - clear;
        float rightA = Math.max(a.x, b.x) + half + clear;
        float bottomA = Math.min(a.y, b.y) - 1f - clear;
        float topA = Math.max(a.y, b.y) + HEADROOM_TILES;
        float leftB = Math.min(c.x, d.x) - otherHalf;
        float rightB = Math.max(c.x, d.x) + otherHalf;
        float bottomB = Math.min(c.y, d.y) - 1f;
        float topB = Math.max(c.y, d.y) + HEADROOM_TILES;
        return overlaps(leftA, rightA, bottomA, topA,
                leftB, rightB, bottomB, topB, EPSILON);
    }

    private static Set<Long> movingOriginCells(ModeVariant variant) {
        Set<Long> out = new HashSet<Long>();
        if (variant == null || variant.secondaryPlatforms == null) return out;
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || !platform.isPatrolling()) continue;
            int row = originRow(variant, platform);
            for (int x = Math.max(0, platform.startX);
                 x <= Math.min(variant.width - 1, platform.endX); x++)
                out.add(cellKey(x, row));
        }
        return out;
    }

    private static int originRow(ModeVariant variant, SecondaryPlatform platform) {
        return Math.max(0, Math.min(variant.height - 1, Math.round(
                variant.height + platform.supportLayer
                        / Math.max(1f, variant.layerUnitsPerTile()))));
    }

    private static int sampleCount(Endpoint a, Endpoint b, float spacing) {
        return Math.max(1, (int) Math.ceil(
                distance(a.x, a.y, b.x, b.y) / Math.max(0.01f, spacing)));
    }

    private static boolean overlaps(float leftA, float rightA,
                                    float bottomA, float topA,
                                    float leftB, float rightB,
                                    float bottomB, float topB,
                                    float epsilon) {
        return rightA > leftB + epsilon && rightB > leftA + epsilon
                && topA > bottomB + epsilon && topB > bottomA + epsilon;
    }

    private static boolean segmentIntersectsBox(float x0, float y0,
                                                float x1, float y1,
                                                float left, float right,
                                                float bottom, float top) {
        float[] range = {0f, 1f};
        float dx = x1 - x0;
        float dy = y1 - y0;
        return clip(-dx, x0 - left, range)
                && clip(dx, right - x0, range)
                && clip(-dy, y0 - bottom, range)
                && clip(dy, top - y0, range);
    }

    private static boolean clip(float p, float q, float[] range) {
        if (Math.abs(p) <= EPSILON) return q >= -EPSILON;
        float ratio = q / p;
        if (p < 0f) {
            if (ratio > range[1]) return false;
            if (ratio > range[0]) range[0] = ratio;
        } else {
            if (ratio < range[0]) return false;
            if (ratio < range[1]) range[1] = ratio;
        }
        return true;
    }

    private static String platformId(ModeVariant variant, SecondaryPlatform platform) {
        if (platform == null) return "";
        if (platform.id != null && !platform.id.trim().isEmpty()) return platform.id;
        int row = variant == null ? 0 : originRow(variant, platform);
        return CustomMapGenerator.stablePlatformId(
                variant, row, platform.startX, platform.endX);
    }

    private static String label(String id) {
        return id == null || id.isEmpty() ? "The moving island" : "Moving island " + id;
    }

    private static long cellKey(int x, int row) {
        return ((long) x << 32) ^ (row & 0xffffffffL);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float distance(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(bx - ax, by - ay);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static final class Endpoint {
        final float x;
        final float y;

        Endpoint(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Collector {
        final String platformId;
        final List<Issue> values = new ArrayList<Issue>();
        final Set<String> codes = new HashSet<String>();

        Collector(String platformId) {
            this.platformId = platformId;
        }

        boolean has(String code) {
            return codes.contains(code);
        }

        void add(String code, String message) {
            if (codes.add(code)) values.add(new Issue(platformId, code, message, true));
        }
    }
}
