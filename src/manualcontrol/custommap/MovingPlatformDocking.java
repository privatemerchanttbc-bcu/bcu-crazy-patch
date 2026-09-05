package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;

public final class MovingPlatformDocking {

    public static final class Target {
        public final float entryWorldX;
        public final float supportLayer;

        public final String platformId;

        final SecondaryPlatform dockPlatform;

        Target(float entryWorldX, float supportLayer, String platformId) {
            this(entryWorldX, supportLayer, platformId, null);
        }

        Target(float entryWorldX, float supportLayer, String platformId,
               SecondaryPlatform dockPlatform) {
            this.entryWorldX = entryWorldX;
            this.supportLayer = supportLayer;
            this.platformId = platformId;
            this.dockPlatform = dockPlatform;
        }
    }

    private static final float EPSILON = .002f;

    private MovingPlatformDocking() {}

    public static Target find(ModeVariant variant, SecondaryPlatform rider,
                              float centerTile, float supportTile, int direction) {
        if (variant == null || rider == null || direction == 0) return null;
        float edge = direction > 0
                ? rider.collisionRightTileX(centerTile)
                : rider.collisionLeftTileX(centerTile);
        float riderSupportTile = rider.collisionSupportTileY(supportTile);
        float maxGap = MovingPlatformValidator.BODY_CLEARANCE_TILES + EPSILON;
        float tolerance = MovingPlatformValidator.DOCK_HEIGHT_TOLERANCE_TILES
                + EPSILON;
        float bestGap = Float.MAX_VALUE;
        Target best = null;

        for (int x = 0; x < variant.width; x++) {
            if (variant.water != null && variant.water[x]) continue;
            if (variant.slopeDirection != null && variant.slopeDirection[x] != 0)
                continue;
            float layer = variant.walkLayerAtTile(x);
            if (Float.isNaN(layer)) continue;
            float height = -layer / Math.max(1f, variant.layerUnitsPerTile());
            if (Math.abs(height - riderSupportTile) > tolerance) continue;
            int neighbor = x + direction;
            if (neighbor < 0 || neighbor >= variant.width
                    || (variant.water != null && variant.water[neighbor])
                    || (variant.slopeDirection != null
                    && variant.slopeDirection[neighbor] != 0)
                    || Math.abs(variant.walkLayerAtTile(neighbor) - layer)
                    > variant.layerUnitsPerTile() * .03f) continue;
            float gap = direction > 0 ? x - edge : edge - (x + 1f);
            if (gap < -EPSILON || gap > maxGap || gap >= bestGap) continue;
            float entryTile = direction > 0 ? x + .06f : x + .94f;
            best = new Target(entryTile * variant.worldUnitsPerTile(), layer, null);
            bestGap = gap;
        }

        if (variant.secondaryPlatforms != null)
            for (SecondaryPlatform candidate : variant.secondaryPlatforms) {
                if (candidate == null || candidate == rider || candidate.isPatrolling())
                    continue;
                float candidateSupport = candidate.collisionSupportLayer(
                        candidate.supportLayer, variant.layerUnitsPerTile());
                float height = -candidateSupport
                        / Math.max(1f, variant.layerUnitsPerTile());
                if (Math.abs(height - riderSupportTile) > tolerance) continue;
                float left = candidate.collisionLeftTileX(
                        candidate.originCenterTileX());
                float right = candidate.collisionRightTileX(
                        candidate.originCenterTileX());
                float gap = direction > 0 ? left - edge : edge - right;
                if (gap < -EPSILON || gap > maxGap || gap >= bestGap) continue;
                float entryTile = direction > 0 ? left + .06f : right - .06f;
                best = new Target(entryTile * variant.worldUnitsPerTile(),
                        candidateSupport, candidate.id, candidate);
                bestGap = gap;
            }
        return best;
    }
}
