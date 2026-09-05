package manualcontrol.custommap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class IceBridgeBuilder {

    public static final int MIN_SPAN_TILES = 2;
    public static final int MAX_SPAN_TILES = 9;

    public static final int WATER_CLEARANCE_ROWS = 1;

    private IceBridgeBuilder() {}

    public static void apply(CustomMapDocument doc, double requestedDensity) {
        if (doc == null) return;
        apply(doc.battleTerrain, requestedDensity, "battle");
        if (doc.variants == null) return;
        for (CustomMapDocument.ModeVariant variant : doc.variants.values())
            apply(variant, requestedDensity, variant == null ? "" : variant.mode);
    }

    public static void apply(CustomMapDocument.ModeVariant v,
                             double requestedDensity, String salt) {
        if (v == null || v.width <= 0 || v.surface == null) return;
        v.achievedIceBridgeCount = 0;
        double density = Math.max(0.0, Math.min(100.0, requestedDensity));
        if (density <= 0.0) return;

        List<Span> spans = collectSpans(v);
        if (spans.isEmpty()) return;
        Random random = new Random(mix(v.seed, "ice-bridge-" + salt));
        Collections.shuffle(spans, random);
        int target = (int) Math.round(spans.size() * density / 100.0);
        if (target <= 0 && density > 0.0) target = 1;

        int built = 0;
        for (Span span : spans) {
            if (built >= target) break;
            if (!buildDeck(v, span)) continue;
            built++;
        }
        v.achievedIceBridgeCount = built;
    }

    public static boolean isDeckColumn(CustomMapDocument.ModeVariant v, int x) {
        if (v == null || v.surface == null || x < 0 || x >= v.width
                || v.water != null && x < v.water.length && v.water[x]) return false;
        int row = x < v.surface.length ? v.surface[x] : -1;
        if (row < 0 || row + 1 >= v.height) return false;
        return v.cell(x, row) == CustomMapDocument.CELL_GROUND
                && v.cell(x, row + 1) != CustomMapDocument.CELL_GROUND;
    }

    public static boolean isDeckCell(CustomMapDocument.ModeVariant v,
                                     int x, int row) {
        return v != null && v.surface != null && x >= 0 && x < v.width
                && x < v.surface.length && row == v.surface[x]
                && isDeckColumn(v, x);
    }

    public static void markDeckColumnsAsIce(CustomMapDocument.ModeVariant v) {
        if (v == null || v.surfaceMaterials == null
                || v.surfaceMaterials.length != v.width) return;
        for (int x = 0; x < v.width; x++)
            if (isDeckColumn(v, x))
                v.surfaceMaterials[x] = CustomMapDocument.SURFACE_ICE;
    }

    private static List<Span> collectSpans(CustomMapDocument.ModeVariant v) {
        ArrayList<Span> out = new ArrayList<Span>();
        int x = 0;
        while (x < v.width) {
            if (!isOpen(v, x)) { x++; continue; }
            int start = x;
            while (x + 1 < v.width && isOpen(v, x + 1)) x++;
            int end = x;
            x++;
            int length = end - start + 1;
            if (length < MIN_SPAN_TILES || length > MAX_SPAN_TILES) continue;
            if (start - 1 < 0 || end + 1 >= v.width) continue;
            int leftRow = v.surface[start - 1];
            int rightRow = v.surface[end + 1];
            if (leftRow < 0 || rightRow < 0 || leftRow != rightRow) continue;
            if (leftRow + 1 + WATER_CLEARANCE_ROWS >= v.height) continue;
            if (touchesBaseSafeZone(v, start - 1, end + 1)) continue;
            if (crossesSlope(v, start - 1, end + 1)) continue;
            out.add(new Span(start, end, leftRow, spanHasWater(v, start, end)));
        }
        return out;
    }

    private static boolean isOpen(CustomMapDocument.ModeVariant v, int x) {
        if (x < 0 || x >= v.width) return false;
        boolean water = v.water != null && x < v.water.length && v.water[x];
        return water || v.surface[x] < 0;
    }

    private static boolean spanHasWater(CustomMapDocument.ModeVariant v,
                                        int start, int end) {
        if (v.water == null) return false;
        for (int x = start; x <= end; x++)
            if (x < v.water.length && v.water[x]) return true;
        return false;
    }

    private static boolean touchesBaseSafeZone(CustomMapDocument.ModeVariant v,
                                               int start, int end) {
        if (v.baseSafeZones == null) return false;
        for (CustomMapDocument.BaseSafeZone zone : v.baseSafeZones) {
            if (zone == null) continue;
            for (int x = start; x <= end; x++)
                if (zone.containsTile(x)) return true;
        }
        return false;
    }

    private static boolean crossesSlope(CustomMapDocument.ModeVariant v,
                                        int start, int end) {
        if (v.slopeDirection == null) return false;
        for (int x = start; x <= end; x++)
            if (x >= 0 && x < v.slopeDirection.length && v.slopeDirection[x] != 0)
                return true;
        return false;
    }

    private static boolean buildDeck(CustomMapDocument.ModeVariant v, Span span) {
        int deckRow = span.deckRow;
        int waterTop = deckRow + 1 + WATER_CLEARANCE_ROWS;
        if (span.water && waterTop >= v.height) return false;

        for (int x = span.start; x <= span.end; x++) {
            for (int y = deckRow; y < v.height; y++) {
                int cell = v.cell(x, y);
                if (cell == CustomMapDocument.CELL_WATER
                        || cell == CustomMapDocument.CELL_GROUND)
                    v.setCell(x, y, CustomMapDocument.CELL_AIR);
            }
            v.setCell(x, deckRow, CustomMapDocument.CELL_GROUND);
            if (span.water)
                for (int y = waterTop; y < v.height; y++)
                    v.setCell(x, y, CustomMapDocument.CELL_WATER);

            v.surface[x] = deckRow;
            if (v.water != null && x < v.water.length) v.water[x] = false;
            if (v.walkSurfaceLayers != null && x < v.walkSurfaceLayers.length)
                v.walkSurfaceLayers[x] = -(v.height - deckRow)
                        * (float) v.layerUnitsPerTile();
            if (v.slopeDirection != null && x < v.slopeDirection.length)
                v.slopeDirection[x] = 0;
            if (v.slopePhase != null && x < v.slopePhase.length)
                v.slopePhase[x] = 0;
            if (v.slopeRunId != null && x < v.slopeRunId.length)
                v.slopeRunId[x] = 0;
            if (v.surfaceMaterials != null && x < v.surfaceMaterials.length)
                v.surfaceMaterials[x] = CustomMapDocument.SURFACE_ICE;
        }
        return true;
    }

    private static long mix(long seed, String text) {
        long h = seed * 0x9E3779B97F4A7C15L;
        if (text != null)
            for (int i = 0; i < text.length(); i++)
                h = h * 31L + text.charAt(i);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    private static final class Span {
        final int start;
        final int end;
        final int deckRow;
        final boolean water;

        Span(int start, int end, int deckRow, boolean water) {
            this.start = start;
            this.end = end;
            this.deckRow = deckRow;
            this.water = water;
        }
    }
}
