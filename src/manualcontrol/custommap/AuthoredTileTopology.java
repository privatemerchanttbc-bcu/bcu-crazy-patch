package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.ModeVariant;

final class AuthoredTileTopology {

    private AuthoredTileTopology() {}

    static Issue firstIssue(ModeVariant variant) {
        if (variant == null || variant.cells == null) return null;
        for (int y = 0; y < variant.height; y++)
            for (int x = 0; x < variant.width; x++) {
                Issue issue = issueAt(variant, x, y);
                if (issue != null) return issue;
            }
        return null;
    }

    static Issue issueAt(ModeVariant variant, int x, int y) {
        if (variant == null
                || variant.cell(x, y) != CustomMapDocument.CELL_GROUND)
            return null;

        boolean up = ground(variant, x, y - 1);
        boolean down = ground(variant, x, y + 1);
        boolean actualLeft = ground(variant, x - 1, y);
        boolean actualRight = ground(variant, x + 1, y);

        boolean floating = variant.surface == null || x >= variant.surface.length
                || variant.surface[x] < 0 || y < variant.surface[x];
        if (!up && (floating || !down)) return null;

        if (variant.slopeDirection != null && x < variant.slopeDirection.length
                && variant.slopeDirection[x] != 0 && variant.surface != null
                && x < variant.surface.length
                && (y == variant.surface[x] || y == variant.surface[x] + 1))
            return null;

        boolean upLeft = ground(variant, x - 1, y - 1);
        boolean upRight = ground(variant, x + 1, y - 1);
        boolean downLeft = ground(variant, x - 1, y + 1);
        boolean downRight = ground(variant, x + 1, y + 1);
        boolean leftContour = !up && variant.surface != null
                && x < variant.surface.length && y == variant.surface[x]
                && x > 0 && variant.isContinuousSurfaceBetween(x - 1, x);
        boolean rightContour = !up && variant.surface != null
                && x < variant.surface.length && y == variant.surface[x]
                && x + 1 < variant.width
                && variant.isContinuousSurfaceBetween(x, x + 1);
        boolean left = actualLeft || leftContour;
        boolean right = actualRight || rightContour;
        int cardinals = count(up, down, left, right);
        int innerCorners = 0;
        if (up && actualLeft && !upLeft && !leftContour) innerCorners++;
        if (up && actualRight && !upRight && !rightContour) innerCorners++;
        if (down && actualLeft && !downLeft && !leftContour) innerCorners++;
        if (down && actualRight && !downRight && !rightContour) innerCorners++;

        if (innerCorners > 0) {
            if (cardinals == 4 && innerCorners == 1) return null;
            return new Issue(x, y, mask(up, down, left, right),
                    "an inner corner and another exposed contour share one cell");
        }
        if (cardinals >= 3) return null;
        if (cardinals == 2 && ((up && left) || (up && right)
                || (down && left) || (down && right))) return null;
        if (cardinals == 2)
            return new Issue(x, y, mask(up, down, left, right),
                    "opposite exposed edges require two authored edge roles");
        return new Issue(x, y, mask(up, down, left, right),
                "a main-terrain cap exposes three or four sides");
    }

    private static boolean ground(ModeVariant variant, int x, int y) {
        return variant.cell(x, y) == CustomMapDocument.CELL_GROUND;
    }

    private static int count(boolean... values) {
        int total = 0;
        for (boolean value : values) if (value) total++;
        return total;
    }

    private static String mask(boolean up, boolean down, boolean left, boolean right) {
        return (up ? "U" : "-") + (down ? "D" : "-")
                + (left ? "L" : "-") + (right ? "R" : "-");
    }

    static final class Issue {
        final int x;
        final int y;
        final String cardinalMask;
        final String reason;

        Issue(int x, int y, String cardinalMask, String reason) {
            this.x = x;
            this.y = y;
            this.cardinalMask = cardinalMask;
            this.reason = reason;
        }

        String message() {
            return "Unrenderable authored autotile topology at " + x + "/" + y
                    + " [" + cardinalMask + "]: " + reason + ".";
        }
    }
}
