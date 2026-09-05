package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;

final class IceSurfaceTopologyResolver {

    static final String STEP_JUNCTION_LEFT_KEY =
            "platform-inner-corner-no-passthrough-left";
    static final String STEP_JUNCTION_RIGHT_KEY =
            "platform-inner-corner-no-passthrough-right";

    enum Role {
        BRIDGE,
        MAIN_CENTER,
        MAIN_LEFT,
        MAIN_RIGHT,
        PLATFORM_CENTER,
        PLATFORM_LEFT,
        PLATFORM_RIGHT,
        PLATFORM_SINGLE,
        SLOPE_UP,
        SLOPE_DOWN,
        STEP_JUNCTION_LEFT,
        STEP_JUNCTION_RIGHT
    }

    private IceSurfaceTopologyResolver() {}

    static Role mainRole(ModeVariant variant, int x, int y,
                         boolean embeddedBankSocket) {
        if (isSlope(variant, x, y))
            return variant.slopeDirection[x] < 0 ? Role.SLOPE_UP : Role.SLOPE_DOWN;

        if (isIceBridgeColumn(variant, x)) {
            if (embeddedBankSocket) return Role.BRIDGE;
            boolean left = isIceBridgeColumn(variant, x - 1)
                    || variant.cell(x - 1, y) == CustomMapDocument.CELL_GROUND;
            boolean right = isIceBridgeColumn(variant, x + 1)
                    || variant.cell(x + 1, y) == CustomMapDocument.CELL_GROUND;
            return platformRole(left, right);
        }

        boolean floating = variant.surface == null || x < 0 || x >= variant.width
                || x >= variant.surface.length || variant.surface[x] < 0
                || y < variant.surface[x];
        if (floating) {
            boolean left = floatingNeighbor(variant, x - 1, y);
            boolean right = floatingNeighbor(variant, x + 1, y);
            return platformRole(left, right);
        }

        boolean iceMaterial = variant.surfaceMaterials != null && x >= 0
                && x < variant.surfaceMaterials.length
                && variant.surfaceMaterials[x] == CustomMapDocument.SURFACE_ICE;
        if (iceMaterial) {
            boolean raisedLeft = isRaisedNonIceWall(variant, x - 1, y);
            boolean raisedRight = isRaisedNonIceWall(variant, x + 1, y);
            if (raisedLeft && !raisedRight) return Role.STEP_JUNCTION_LEFT;
            if (raisedRight && !raisedLeft) return Role.STEP_JUNCTION_RIGHT;
        }

        boolean left = variant.cell(x - 1, y) == CustomMapDocument.CELL_GROUND
                || (x > 0 && variant.isContinuousSurfaceBetween(x - 1, x));
        boolean right = variant.cell(x + 1, y) == CustomMapDocument.CELL_GROUND
                || (x + 1 < variant.width
                && variant.isContinuousSurfaceBetween(x, x + 1));
        return !left ? Role.MAIN_LEFT : !right ? Role.MAIN_RIGHT : Role.MAIN_CENTER;
    }

    static Role platformRole(SecondaryPlatform platform, int local) {
        if (platform == null || platform.widthTiles() <= 1) return Role.PLATFORM_SINGLE;
        if (local <= 0) return Role.PLATFORM_LEFT;
        if (local >= platform.widthTiles() - 1) return Role.PLATFORM_RIGHT;
        return Role.PLATFORM_CENTER;
    }

    static int bridgeStart(ModeVariant variant, int x) {
        if (!isIceBridgeColumn(variant, x)) return -1;
        int start = x;
        while (isIceBridgeColumn(variant, start - 1)) start--;
        return start;
    }

    static int bridgeEnd(ModeVariant variant, int x) {
        if (!isIceBridgeColumn(variant, x)) return -1;
        int end = x;
        while (isIceBridgeColumn(variant, end + 1)) end++;
        return end;
    }

    static boolean isIceBridgeColumn(ModeVariant variant, int x) {
        return variant != null && variant.surfaceMaterials != null && x >= 0
                && x < variant.surfaceMaterials.length
                && variant.surfaceMaterials[x] == CustomMapDocument.SURFACE_ICE
                && IceBridgeBuilder.isDeckColumn(variant, x);
    }

    private static boolean isSlope(ModeVariant variant, int x, int y) {
        return variant != null && variant.slopeDirection != null && x >= 0
                && x < variant.slopeDirection.length
                && variant.slopeDirection[x] != 0 && variant.surface != null
                && x < variant.surface.length && y == variant.surface[x];
    }

    private static boolean floatingNeighbor(ModeVariant variant, int x, int y) {
        return variant != null && x >= 0 && x < variant.width
                && variant.cell(x, y) == CustomMapDocument.CELL_GROUND
                && (variant.surface == null || x >= variant.surface.length
                || variant.surface[x] < 0 || y < variant.surface[x]);
    }

    private static boolean isRaisedNonIceWall(ModeVariant variant, int x, int y) {
        if (variant == null || variant.surface == null
                || variant.surfaceMaterials == null || x < 0
                || x >= variant.width || x >= variant.surface.length
                || x >= variant.surfaceMaterials.length
                || variant.surfaceMaterials[x] == CustomMapDocument.SURFACE_ICE
                || variant.surface[x] < 0 || variant.surface[x] >= y
                || variant.cell(x, y) != CustomMapDocument.CELL_GROUND)
            return false;
        return variant.slopeDirection == null || x >= variant.slopeDirection.length
                || variant.slopeDirection[x] == 0;
    }

    private static Role platformRole(boolean left, boolean right) {
        if (!left && !right) return Role.PLATFORM_SINGLE;
        if (!left) return Role.PLATFORM_LEFT;
        if (!right) return Role.PLATFORM_RIGHT;
        return Role.PLATFORM_CENTER;
    }
}
