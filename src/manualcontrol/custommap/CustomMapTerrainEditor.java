package manualcontrol.custommap;

import com.google.gson.Gson;
import manualcontrol.custommap.CustomMapDocument.ManualDecoration;
import manualcontrol.custommap.CustomMapDocument.ManualEffect;
import manualcontrol.custommap.CustomMapDocument.ManualIceBridge;
import manualcontrol.custommap.CustomMapDocument.ManualIslandRun;
import manualcontrol.custommap.CustomMapDocument.ManualSlopeRun;
import manualcontrol.custommap.CustomMapDocument.ManualTile;
import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.PlatformPatrol;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;

import java.awt.Point;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class CustomMapTerrainEditor {

    enum Tool {
        SELECT, TERRAIN, SLOPE, ICE, ISLAND, DECORATION, ENVIRONMENT, ERASER
    }

    enum SlopeMode {
        AUTO("Auto"), GENTLE("Gentle"), STEEP("Steep");
        final String label;
        SlopeMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum IceMode {
        APPLY("Apply ice"), REMOVE("Remove ice");
        final String label;
        IceMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    static final class StrokeProposal {
        final boolean valid;
        final String message;
        final Point requestedStart;
        final Point requestedEnd;
        final Point start;
        final Point end;
        final List<Point> cells;
        final String kind;
        final String slopeStyle;
        final boolean bridge;

        private StrokeProposal(boolean valid, String message, Point requestedStart,
                               Point requestedEnd, Point start, Point end,
                               List<Point> cells, String kind,
                               String slopeStyle, boolean bridge) {
            this.valid = valid;
            this.message = message == null ? "" : message;
            this.requestedStart = requestedStart;
            this.requestedEnd = requestedEnd;
            this.start = start;
            this.end = end;
            this.cells = cells == null ? new ArrayList<Point>() : cells;
            this.kind = kind == null ? "stroke" : kind;
            this.slopeStyle = slopeStyle == null ? "" : slopeStyle;
            this.bridge = bridge;
        }

        static StrokeProposal rejected(String message, Point a, Point b) {
            return new StrokeProposal(false, message, a, b, a, b,
                    new ArrayList<Point>(), "stroke", "", false);
        }

        static StrokeProposal accepted(Point requestedStart, Point requestedEnd,
                                       Point start, Point end, List<Point> cells,
                                       String kind, String style, boolean bridge) {
            return new StrokeProposal(true, "", requestedStart, requestedEnd,
                    start, end, cells, kind, style, bridge);
        }
    }

    static final class Result {
        final ModeVariant variant;
        final String message;
        final boolean changed;

        private Result(ModeVariant variant, String message, boolean changed) {
            this.variant = variant;
            this.message = message;
            this.changed = changed;
        }

        static Result accepted(ModeVariant variant) {
            return new Result(variant, "Stroke applied.", true);
        }

        static Result rejected(String message) {
            return new Result(null, message, false);
        }
    }

    private static final Gson GSON = new Gson();
    private static final int MAX_ISLAND_WIDTH = 12;

    private CustomMapTerrainEditor() {}

    static String snapshot(ModeVariant variant) { return GSON.toJson(variant); }

    static ModeVariant restore(String json) {
        return GSON.fromJson(json, ModeVariant.class);
    }

    static Result apply(ModeVariant source, List<Point> stroke,
                        CustomMapPalette.Asset asset, Tool tool,
                        int brushSize, boolean battle) {
        return apply(source, stroke, asset, tool, brushSize, battle,
                SlopeMode.AUTO, IceMode.APPLY);
    }

    static Result apply(ModeVariant source, List<Point> stroke,
                        CustomMapPalette.Asset asset, Tool tool,
                        int brushSize, boolean battle, SlopeMode slopeMode,
                        IceMode iceMode) {
        StrokeProposal proposal = propose(source, stroke, asset, tool,
                brushSize, slopeMode, iceMode);
        if (!proposal.valid) return Result.rejected(proposal.message);

        ModeVariant work = restore(snapshot(source));
        normalizeLists(work);
        Set<String> moving = movingPlatformIds(work);

        if (tool == Tool.DECORATION) {
            Point point = proposal.end;
            if (work.surface == null || work.surface[point.x] < 0
                    || work.water != null && work.water[point.x])
                return Result.rejected("A tree or decoration needs a dry surface below its anchor.");
            ManualDecoration decoration = new ManualDecoration();
            decoration.asset = asset.id;
            decoration.sourceTheme = asset.theme;
            decoration.category = asset.category == CustomMapPalette.Category.TREE
                    ? "tree" : "prop";
            decoration.x = point.x + .5f;
            decoration.anchorLayer = work.walkLayerAtTile(point.x);
            decoration.order = work.manualDecorations.size();
            work.manualDecorations.add(decoration);
            return Result.accepted(work);
        }
        if (tool == Tool.ENVIRONMENT) {
            if (asset.category == CustomMapPalette.Category.BACKGROUND) {
                CustomMapDocument.BackgroundLayer layer =
                        new CustomMapDocument.BackgroundLayer();
                layer.asset = asset.id;
                layer.role = asset.role;
                layer.order = work.manualBackground.size();
                layer.fit = "cover";
                layer.anchor = "bottom";
                work.manualBackground.add(layer);
            } else {
                ManualEffect effect = new ManualEffect();
                effect.asset = asset.id;
                effect.sourceTheme = asset.theme;
                effect.scope = "global";
                effect.startX = 0f;
                effect.endX = work.width;
                effect.order = work.manualEffects.size();
                work.manualEffects.add(effect);
            }
            return Result.accepted(work);
        }

        if (tool == Tool.SLOPE) applySlope(work, proposal, asset);
        else if (tool == Tool.ICE) applyIce(work, proposal, asset, iceMode);
        else if (tool == Tool.ISLAND) applyIsland(work, proposal, asset);
        else for (Point point : proposal.cells)
            applyCell(work, point.x, point.y, asset, tool);

        CustomMapGenerator.rebuildAfterManualEdit(work, battle);
        String objectIssue = unsupportedObjectIssue(work);
        if (objectIssue != null) return Result.rejected(objectIssue);
        if (!sameMovingPlatforms(moving, work))
            return Result.rejected("This stroke changes a moving island's shape or patrol path. Make the island static first.");
        String connectorIssue = firstConnectorIssue(work);
        if (connectorIssue != null) return Result.rejected(connectorIssue);
        if (!work.reachable)
            return Result.rejected(readableValidation(work.validation, proposal.start));
        return Result.accepted(work);
    }

    static StrokeProposal propose(ModeVariant source, List<Point> stroke,
                                  CustomMapPalette.Asset asset, Tool tool,
                                  int brushSize, SlopeMode slopeMode,
                                  IceMode iceMode) {
        Point requestedStart = firstPoint(stroke);
        Point requestedEnd = lastPoint(stroke);
        if (source == null)
            return StrokeProposal.rejected("Generate this tab before using editing tools.",
                    requestedStart, requestedEnd);
        if (tool == null || tool == Tool.SELECT)
            return StrokeProposal.rejected("Choose a painting tool first.",
                    requestedStart, requestedEnd);
        if (tool != Tool.ERASER && asset == null)
            return StrokeProposal.rejected("Choose a tile in the palette first.",
                    requestedStart, requestedEnd);
        if (asset != null && !asset.supported)
            return StrokeProposal.rejected(asset.disabledReason,
                    requestedStart, requestedEnd);
        if (asset != null && !toolAccepts(tool, asset.category))
            return StrokeProposal.rejected("The selected palette item belongs to '"
                    + asset.category + "'. Choose its matching toolbar mode.",
                    requestedStart, requestedEnd);
        if (requestedStart == null || requestedEnd == null)
            return StrokeProposal.rejected("The stroke does not touch the map.",
                    requestedStart, requestedEnd);

        if (tool == Tool.SLOPE)
            return proposeSlope(source, requestedStart, requestedEnd, asset,
                    slopeMode == null ? SlopeMode.AUTO : slopeMode);
        if (tool == Tool.ICE)
            return proposeIce(source, requestedStart, requestedEnd,
                    iceMode == null ? IceMode.APPLY : iceMode);
        if (tool == Tool.ISLAND)
            return proposeIsland(source, requestedStart, requestedEnd, asset);

        List<Point> cells = continuousCells(stroke, source.width, source.height,
                Math.max(1, Math.min(2, brushSize)));
        if (cells.isEmpty())
            return StrokeProposal.rejected("The stroke is outside the map.",
                    requestedStart, requestedEnd);
        Point end = cells.get(cells.size() - 1);
        return StrokeProposal.accepted(requestedStart, requestedEnd,
                cells.get(0), end, cells, "stroke", "", false);
    }

    private static StrokeProposal proposeSlope(ModeVariant v, Point requestA,
                                               Point requestB,
                                               CustomMapPalette.Asset asset,
                                               SlopeMode requestedMode) {
        if (requestA.x < 0 || requestA.x >= v.width)
            return StrokeProposal.rejected("The slope start is outside the map.", requestA, requestB);
        int startRow = mainDrySurface(v, requestA.x);
        if (startRow < 0)
            return StrokeProposal.rejected("The slope start must be on an existing dry ground surface.", requestA, requestB);

        TileCatalog.TileSet family = resolveFamily(asset, v.seed);
        if (family == null || !family.supportsSlopes())
            return StrokeProposal.rejected("This theme has no complete two-direction slope set.", requestA, requestB);
        if (requestedMode == SlopeMode.STEEP && !family.supportsSteepSlopes())
            return StrokeProposal.rejected("Steep is locked because this theme is missing a complete connector set.", requestA, requestB);

        SlopeCandidate best = null;
        SlopeMode[] modes = requestedMode == SlopeMode.AUTO
                ? new SlopeMode[]{SlopeMode.GENTLE, SlopeMode.STEEP}
                : new SlopeMode[]{requestedMode};
        for (SlopeMode mode : modes) {
            int upPhases = mode == SlopeMode.STEEP
                    ? family.groundSteepSlopeUp.size() : family.groundSlopeUp.size();
            int downPhases = mode == SlopeMode.STEEP
                    ? family.groundSteepSlopeDown.size() : family.groundSlopeDown.size();
            if (upPhases <= 0 || downPhases <= 0
                    || mode == SlopeMode.STEEP && !family.supportsSteepSlopes())
                continue;
            SlopeCandidate candidate = slopeCandidate(v, requestA.x, startRow,
                    requestB, upPhases, downPhases, mode);
            if (candidate != null && (best == null
                    || candidate.snapDistance < best.snapDistance
                    || candidate.snapDistance == best.snapDistance
                    && candidate.mode == SlopeMode.GENTLE
                    && best.mode == SlopeMode.STEEP)) best = candidate;
        }
        if (best == null)
            return StrokeProposal.rejected("No complete slope fits these two surfaces. Move the end by up to two columns or choose the other slope style.", requestA, requestB);

        int leftX = Math.min(requestA.x, best.endX);
        int rightX = Math.max(requestA.x, best.endX);
        int leftRow = requestA.x <= best.endX ? startRow : best.endRow;
        int rightRow = requestA.x <= best.endX ? best.endRow : startRow;
        int direction = rightRow > leftRow ? 1 : -1;
        ArrayList<Point> cells = new ArrayList<Point>();
        cells.add(new Point(leftX, leftRow));
        int phaseCount = best.phases;
        for (int x = leftX + 1; x < rightX; x++) {
            int offset = x - leftX - 1;
            int riseStep = offset / phaseCount;
            int phase = offset % phaseCount;
            float row = leftRow + direction * (riseStep
                    + (phase + .5f) / phaseCount);
            int rasterRow = direction > 0 ? (int) Math.ceil(row) - 1
                    : (int) Math.floor(row);
            rasterRow = clamp(rasterRow, 0, v.height - 1);
            for (int y = rasterRow; y < v.height; y++) cells.add(new Point(x, y));
        }
        cells.add(new Point(rightX, rightRow));
        Point snappedStart = new Point(requestA.x, startRow);
        Point snappedEnd = new Point(best.endX, best.endRow);
        return StrokeProposal.accepted(requestA, requestB, snappedStart,
                snappedEnd, cells, "slope", best.mode == SlopeMode.STEEP
                        ? "steep" : "gentle", false);
    }

    private static SlopeCandidate slopeCandidate(ModeVariant v, int startX,
                                                 int startRow, Point requestedEnd,
                                                 int upPhases, int downPhases,
                                                 SlopeMode mode) {
        int desiredRow = -1;
        int closestDistance = Integer.MAX_VALUE;
        for (int offset : new int[]{0, -1, 1, -2, 2}) {
            int x = requestedEnd.x + offset;
            int row = mainDrySurface(v, x);
            int distance = Math.abs(row - requestedEnd.y);
            if (row >= 0 && row != startRow
                    && (distance < closestDistance
                    || distance == closestDistance && desiredRow < 0)) {
                desiredRow = row;
                closestDistance = distance;
            }
        }
        if (desiredRow < 0 || desiredRow == startRow) return null;
        int rise = Math.abs(desiredRow - startRow);
        SlopeCandidate best = null;
        for (int offset : new int[]{0, -1, 1, -2, 2}) {
            int x = requestedEnd.x + offset;
            int leftRow = startX <= x ? startRow : desiredRow;
            int rightRow = startX <= x ? desiredRow : startRow;
            int phases = rightRow < leftRow ? upPhases : downPhases;
            int requiredDistance = phases * rise + 1;
            if (x < 0 || x >= v.width || x == startX
                    || mainDrySurface(v, x) != desiredRow
                    || Math.abs(x - startX) != requiredDistance) continue;
            if (crossesProtectedAnchor(v, Math.min(startX, x), Math.max(startX, x)))
                continue;
            SlopeCandidate candidate = new SlopeCandidate(x, desiredRow,
                    Math.abs(offset), phases, mode);
            if (best == null || candidate.snapDistance < best.snapDistance) best = candidate;
        }
        return best;
    }

    private static StrokeProposal proposeIce(ModeVariant v, Point requestA,
                                             Point requestB, IceMode mode) {
        Point start = nearestTop(v, requestA.x, requestA.y);
        Point end = nearestTop(v, requestB.x, requestB.y);
        if (start == null || end == null)
            return StrokeProposal.rejected("Both ice endpoints must touch a dry surface or opposite banks.", requestA, requestB);
        if (start.x > end.x) { Point swap = start; start = end; end = swap; }

        if (mode == IceMode.REMOVE) {
            ManualIceBridge bridge = iceBridgeBetween(v, start.x, end.x);
            if (bridge != null) {
                ArrayList<Point> cells = new ArrayList<Point>();
                for (int x = bridge.startX; x <= bridge.endX; x++)
                    cells.add(new Point(x, bridge.row));
                return StrokeProposal.accepted(requestA, requestB,
                        new Point(bridge.startX - 1, bridge.row),
                        new Point(bridge.endX + 1, bridge.row), cells,
                        "ice-bridge", "", true);
            }
        }

        ArrayList<Point> surface = traceSurface(v, start, end);
        if (!surface.isEmpty()) {
            return StrokeProposal.accepted(requestA, requestB, start, end,
                    surface, "ice-surface", "", false);
        }
        int gap = end.x - start.x - 1;
        if (mode == IceMode.REMOVE) {
            return StrokeProposal.rejected("There is no continuous ice surface or manual ice bridge between these points.", requestA, requestB);
        }
        if (start.y != end.y)
            return StrokeProposal.rejected("An ice bridge needs two banks at the same height.", requestA, requestB);
        if (gap < IceBridgeBuilder.MIN_SPAN_TILES
                || gap > IceBridgeBuilder.MAX_SPAN_TILES)
            return StrokeProposal.rejected("An ice bridge gap must be 2-9 tiles wide.", requestA, requestB);
        for (int x = start.x + 1; x < end.x; x++)
            if (!openForBridge(v, x))
                return StrokeProposal.rejected("The area between the banks mixes ground and gaps, so the whole ice stroke was cancelled.", requestA, requestB);
        ArrayList<Point> deck = new ArrayList<Point>();
        for (int x = start.x + 1; x < end.x; x++) deck.add(new Point(x, start.y));
        return StrokeProposal.accepted(requestA, requestB, start, end,
                deck, "ice-bridge", "", true);
    }

    private static StrokeProposal proposeIsland(ModeVariant v, Point requestA,
                                                Point requestB,
                                                CustomMapPalette.Asset asset) {
        int y = clamp(requestA.y, 0, v.height - 2);
        int direction = requestB.x < requestA.x ? -1 : 1;
        int requestedWidth = Math.min(MAX_ISLAND_WIDTH,
                Math.abs(requestB.x - requestA.x) + 1);
        TileCatalog.TileSet family = resolveFamily(asset, v.seed);
        if (family == null)
            return StrokeProposal.rejected("The floating-island artwork is no longer available.", requestA, requestB);
        int width = nearestIslandWidth(family, requestedWidth);
        if (width <= 0)
            return StrokeProposal.rejected("This theme has neither a complete island image nor left/middle/right pieces.", requestA, requestB);
        int endX = requestA.x + direction * (width - 1);
        int left = Math.min(requestA.x, endX), right = Math.max(requestA.x, endX);
        if (left < 0 || right >= v.width)
            return StrokeProposal.rejected("The floating island would extend outside the map.", requestA, requestB);
        ArrayList<Point> cells = new ArrayList<Point>();
        for (int x = left; x <= right; x++) {
            if (v.cell(x, y) != CustomMapDocument.CELL_AIR
                    || v.cell(x, y - 1) != CustomMapDocument.CELL_AIR)
                return StrokeProposal.rejected("The floating island needs empty space at its chosen height.", requestA, requestB);
            cells.add(new Point(x, y));
        }
        return StrokeProposal.accepted(requestA, requestB,
                new Point(left, y), new Point(right, y), cells,
                "island", "", false);
    }

    static Result moveDecoration(ModeVariant source, int index, int tileX) {
        if (source == null || source.manualDecorations == null
                || index < 0 || index >= source.manualDecorations.size())
            return Result.rejected("The decoration is no longer available.");
        ModeVariant work = restore(snapshot(source));
        ManualDecoration decoration = work.manualDecorations.get(index);
        int x = Math.max(0, Math.min(work.width - 1, tileX));
        if (!drySurface(work, x))
            return Result.rejected("The decoration needs a dry surface at column " + x + ".");
        decoration.x = x + .5f;
        decoration.anchorLayer = work.walkLayerAtTile(x);
        return Result.accepted(work);
    }

    private static void applySlope(ModeVariant v, StrokeProposal proposal,
                                   CustomMapPalette.Asset asset) {
        Point a = proposal.start, b = proposal.end;
        int leftX = Math.min(a.x, b.x), rightX = Math.max(a.x, b.x);
        int leftRow = a.x <= b.x ? a.y : b.y;
        int rightRow = a.x <= b.x ? b.y : a.y;
        int direction = rightRow > leftRow ? 1 : -1;
        TileCatalog.TileSet family = resolveFamily(asset, v.seed);
        int phases = "steep".equals(proposal.slopeStyle)
                ? family.groundSteepSlopeUp.size() : family.groundSlopeUp.size();
        removeSemanticRuns(v, leftX, rightX);
        int run = nextSlopeRun(v);
        for (int x = leftX + 1; x < rightX; x++) {
            int offset = x - leftX - 1;
            int step = offset / phases;
            int phase = offset % phases;
            float surfaceRow = leftRow + direction
                    * (step + (phase + .5f) / phases);
            int row = direction > 0 ? (int) Math.ceil(surfaceRow) - 1
                    : (int) Math.floor(surfaceRow);
            row = clamp(row, 0, v.height - 1);
            int oldTop = v.surface == null ? row : v.surface[x];
            if (oldTop >= 0 && oldTop < row)
                for (int y = oldTop; y < row; y++) {
                    v.setCell(x, y, CustomMapDocument.CELL_AIR);
                    removeManualTile(v, x, y);
                }
            for (int y = row; y < v.height; y++) {
                v.setCell(x, y, CustomMapDocument.CELL_GROUND);
                putManualTile(v, new ManualTile(x, y, "slope", asset.material,
                        asset.theme, asset.family, asset.id, asset.role));
            }
            v.surface[x] = row;
            v.walkSurfaceLayers[x] = -(v.height - surfaceRow)
                    * v.layerUnitsPerTile();
            v.slopeDirection[x] = direction;
            v.slopeRunId[x] = run;
            v.slopePhase[x] = Math.round((phase + 1f) * 100f / phases);
        }
        ManualSlopeRun record = new ManualSlopeRun();
        record.runId = run;
        record.startX = a.x;
        record.startRow = a.y;
        record.endX = b.x;
        record.endRow = b.y;
        record.style = proposal.slopeStyle;
        record.sourceTheme = asset.theme;
        record.family = asset.family;
        v.manualSlopes.add(record);
    }

    private static void applyIce(ModeVariant v, StrokeProposal proposal,
                                 CustomMapPalette.Asset asset, IceMode mode) {
        if (proposal.bridge) {
            if (mode == IceMode.REMOVE) {
                ManualIceBridge bridge = iceBridgeBetween(v,
                        proposal.start.x, proposal.end.x);
                if (bridge != null) restoreBridge(v, bridge);
                return;
            }
            ManualIceBridge bridge = new ManualIceBridge();
            bridge.row = proposal.start.y;
            bridge.startX = proposal.start.x + 1;
            bridge.endX = proposal.end.x - 1;
            bridge.sourceTheme = asset.theme;
            bridge.family = asset.family;
            int width = bridge.endX - bridge.startX + 1;
            bridge.previousCells = new int[width * v.height];
            for (int x = bridge.startX; x <= bridge.endX; x++) {
                int offset = (x - bridge.startX) * v.height;
                for (int y = 0; y < v.height; y++)
                    bridge.previousCells[offset + y] = v.cell(x, y);
                v.setCell(x, bridge.row, CustomMapDocument.CELL_GROUND);
                ManualTile tile = new ManualTile(x, bridge.row, "ice-bridge",
                        CustomMapDocument.MATERIAL_ICE, "", "", "", "ice-auto");
                tile.materialTheme = asset.theme;
                tile.materialFamily = asset.family;
                tile.materialAsset = asset.id;
                putManualTile(v, tile);
            }
            v.manualIceBridges.add(bridge);
            return;
        }
        for (Point point : proposal.cells) {
            ManualTile tile = v.manualTileAt(point.x, point.y);
            String priorMaterial = v.materialAt(point.x, point.y);
            if (tile == null) {
                tile = new ManualTile(point.x, point.y,
                        isSecondaryTop(v, point.x, point.y) ? "island"
                                : v.slopeDirection != null
                                && v.slopeDirection[point.x] != 0 ? "slope" : "ground",
                        CustomMapDocument.MATERIAL_NORMAL,
                        "", "", "", "terrain-auto");
                v.manualTiles.add(tile);
            }
            if (mode == IceMode.APPLY) {
                if (empty(tile.materialTheme))
                    tile.materialUnderlay = priorMaterial;
                tile.material = CustomMapDocument.MATERIAL_ICE;
                tile.materialTheme = asset.theme;
                tile.materialFamily = asset.family;
                tile.materialAsset = asset.id;
            } else {
                tile.material = empty(tile.materialUnderlay)
                        ? CustomMapDocument.MATERIAL_NORMAL
                        : tile.materialUnderlay;
                tile.materialTheme = "";
                tile.materialFamily = "";
                tile.materialAsset = "";
                tile.materialUnderlay = "";
            }
        }
    }

    private static void applyIsland(ModeVariant v, StrokeProposal proposal,
                                    CustomMapPalette.Asset asset) {
        for (Point point : proposal.cells) {
            v.setCell(point.x, point.y, CustomMapDocument.CELL_GROUND);
            putManualTile(v, new ManualTile(point.x, point.y, "island",
                    asset.material, asset.theme, asset.family, asset.id, asset.role));
        }
        ManualIslandRun run = new ManualIslandRun();
        run.row = proposal.start.y;
        run.startX = proposal.start.x;
        run.endX = proposal.end.x;
        run.sourceTheme = asset.theme;
        run.family = asset.family;
        run.asset = asset.id;
        v.manualIslands.add(run);
    }

    private static void applyCell(ModeVariant v, int x, int y,
                                  CustomMapPalette.Asset asset, Tool tool) {
        if (tool == Tool.ERASER) {
            v.setCell(x, y, CustomMapDocument.CELL_AIR);
            removeManualTile(v, x, y);
            clearSlopeColumn(v, x);
            removeSemanticRuns(v, x, x);
            if (v.manualDecorations != null)
                for (int i = v.manualDecorations.size() - 1; i >= 0; i--)
                    if (Math.floor(v.manualDecorations.get(i).x) == x)
                        v.manualDecorations.remove(i);
            return;
        }
        boolean liquid = asset.category == CustomMapPalette.Category.LIQUID;
        v.setCell(x, y, liquid ? CustomMapDocument.CELL_WATER
                : CustomMapDocument.CELL_GROUND);
        clearSlopeColumn(v, x);
        removeSemanticRuns(v, x, x);
        putManualTile(v, new ManualTile(x, y,
                liquid ? "liquid" : "ground", asset.material,
                asset.theme, asset.family, asset.id, asset.role));
    }

    private static void restoreBridge(ModeVariant v, ManualIceBridge bridge) {
        int width = bridge.endX - bridge.startX + 1;
        if (bridge.previousCells != null
                && bridge.previousCells.length == width * v.height)
            for (int x = bridge.startX; x <= bridge.endX; x++) {
                int offset = (x - bridge.startX) * v.height;
                for (int y = 0; y < v.height; y++)
                    v.setCell(x, y, bridge.previousCells[offset + y]);
                removeManualTile(v, x, bridge.row);
            }
        v.manualIceBridges.remove(bridge);
    }

    private static String unsupportedObjectIssue(ModeVariant v) {
        if (v.trees != null)
            for (CustomMapDocument.TreePlacement tree : v.trees)
                if (tree != null && !drySurface(v, tree.x))
                    return "The stroke was cancelled because the tree at column "
                            + tree.x + " would have no surface below it.";
        if (v.props != null)
            for (CustomMapDocument.PropPlacement prop : v.props)
                if (prop != null && !drySurface(v, prop.x))
                    return "The stroke was cancelled because the decoration at column "
                            + prop.x + " would have no surface below it.";
        if (v.manualDecorations != null)
            for (ManualDecoration decoration : v.manualDecorations) {
                int x = decoration == null ? -1 : (int) Math.floor(decoration.x);
                if (!drySurface(v, x))
                    return "The stroke was cancelled because a manual decoration at column "
                            + x + " would have no surface below it.";
                decoration.anchorLayer = v.walkLayerAtTile(x);
            }
        return null;
    }

    private static boolean drySurface(ModeVariant v, int x) {
        return v != null && x >= 0 && x < v.width && v.surface != null
                && v.surface[x] >= 0 && (v.water == null || !v.water[x]);
    }

    private static boolean toolAccepts(Tool tool,
                                       CustomMapPalette.Category category) {
        if (tool == Tool.ERASER) return true;
        if (tool == Tool.SLOPE) return category == CustomMapPalette.Category.SLOPE;
        if (tool == Tool.ICE) return category == CustomMapPalette.Category.ICE;
        if (tool == Tool.ISLAND) return category == CustomMapPalette.Category.ISLAND;
        if (tool == Tool.DECORATION)
            return category == CustomMapPalette.Category.TREE
                    || category == CustomMapPalette.Category.PROP;
        if (tool == Tool.ENVIRONMENT)
            return category == CustomMapPalette.Category.BACKGROUND;
        return tool == Tool.TERRAIN
                && (category == CustomMapPalette.Category.TERRAIN
                || category == CustomMapPalette.Category.LIQUID);
    }

    private static TileCatalog.TileSet resolveFamily(CustomMapPalette.Asset asset,
                                                     long seed) {
        if (asset == null || asset.theme == null) return null;
        try {
            TileCatalog.TileSet root = TileCatalog.find(asset.theme);
            if (root == null) return null;
            if (asset.family != null && !asset.family.isEmpty())
                for (TileCatalog.TileSet family : root.groundFamilies)
                    if (family != null && asset.family.equalsIgnoreCase(family.groundFamily))
                        return family;
            return root.resolveBaseGroundFamily(seed);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static int nearestIslandWidth(TileCatalog.TileSet set, int requested) {
        if (set == null) return -1;
        boolean composable = !set.groundPlatformSingle.isEmpty()
                && !set.groundPlatformLeft.isEmpty()
                && !set.groundPlatformCenter.isEmpty()
                && !set.groundPlatformRight.isEmpty();
        if (requested == 1 && !set.groundPlatformSingle.isEmpty()) return 1;
        if (set.floatingIslandSpans.containsKey(requested) || composable) return requested;
        int best = -1;
        for (Integer width : set.floatingIslandSpans.keySet())
            if (width != null && width > 0 && width <= MAX_ISLAND_WIDTH
                    && (best < 0 || Math.abs(width - requested) < Math.abs(best - requested)))
                best = width;
        return best;
    }

    private static Point nearestTop(ModeVariant v, int x, int requestedY) {
        if (x < 0 || x >= v.width) return null;
        Point best = null;
        int distance = Integer.MAX_VALUE;
        for (int y = 0; y < v.height; y++)
            if (v.cell(x, y) == CustomMapDocument.CELL_GROUND
                    && v.cell(x, y - 1) != CustomMapDocument.CELL_GROUND) {
                int d = Math.abs(y - requestedY);
                if (d < distance) { distance = d; best = new Point(x, y); }
            }
        return best;
    }

    private static ArrayList<Point> traceSurface(ModeVariant v, Point start, Point end) {
        ArrayList<Point> out = new ArrayList<Point>();
        int previous = start.y;
        for (int x = start.x; x <= end.x; x++) {
            Point top = nearestTop(v, x, previous);
            if (top == null || Math.abs(top.y - previous) > 1) {
                out.clear();
                return out;
            }
            if (x == end.x && top.y != end.y) {
                out.clear();
                return out;
            }
            out.add(top);
            previous = top.y;
        }
        return out;
    }

    private static boolean openForBridge(ModeVariant v, int x) {
        if (x < 0 || x >= v.width) return false;
        return v.surface == null || v.surface[x] < 0
                || v.water != null && v.water[x];
    }

    private static ManualIceBridge iceBridgeBetween(ModeVariant v, int x1, int x2) {
        if (v.manualIceBridges == null) return null;
        int left = Math.min(x1, x2), right = Math.max(x1, x2);
        for (ManualIceBridge bridge : v.manualIceBridges)
            if (bridge != null && bridge.startX >= left
                    && bridge.endX <= right) return bridge;
        return null;
    }

    private static boolean isSecondaryTop(ModeVariant v, int x, int y) {
        return v.cell(x, y) == CustomMapDocument.CELL_GROUND
                && v.cell(x, y - 1) != CustomMapDocument.CELL_GROUND
                && (v.surface == null || v.surface[x] != y);
    }

    private static int mainDrySurface(ModeVariant v, int x) {
        return v != null && v.surface != null && x >= 0 && x < v.width
                && v.surface[x] >= 0 && (v.water == null || !v.water[x])
                ? v.surface[x] : -1;
    }

    private static boolean crossesProtectedAnchor(ModeVariant v, int left, int right) {
        if (v.baseSafeZones == null) return false;
        for (CustomMapDocument.BaseSafeZone zone : v.baseSafeZones)
            if (zone != null)
                for (int x = left + 1; x < right; x++)
                    if (zone.containsTile(x)) return true;
        return false;
    }

    private static void removeSemanticRuns(ModeVariant v, int left, int right) {
        if (v.manualSlopes != null)
            for (int i = v.manualSlopes.size() - 1; i >= 0; i--) {
                ManualSlopeRun run = v.manualSlopes.get(i);
                if (run != null && overlaps(left, right,
                        Math.min(run.startX, run.endX), Math.max(run.startX, run.endX)))
                    v.manualSlopes.remove(i);
            }
        if (v.manualIslands != null)
            for (int i = v.manualIslands.size() - 1; i >= 0; i--) {
                ManualIslandRun run = v.manualIslands.get(i);
                if (run != null && overlaps(left, right, run.startX, run.endX))
                    v.manualIslands.remove(i);
            }
        if (v.manualIceBridges != null)
            for (int i = v.manualIceBridges.size() - 1; i >= 0; i--) {
                ManualIceBridge bridge = v.manualIceBridges.get(i);
                if (bridge != null && overlaps(left, right,
                        bridge.startX, bridge.endX)) v.manualIceBridges.remove(i);
            }
    }

    private static boolean overlaps(int a1, int a2, int b1, int b2) {
        return Math.max(a1, b1) <= Math.min(a2, b2);
    }

    private static int nextSlopeRun(ModeVariant v) {
        int max = 0;
        if (v.slopeRunId != null) for (int id : v.slopeRunId) max = Math.max(max, id);
        return max + 1;
    }

    private static void clearSlopeColumn(ModeVariant v, int x) {
        if (v.slopeDirection != null && x < v.slopeDirection.length)
            v.slopeDirection[x] = 0;
        if (v.slopePhase != null && x < v.slopePhase.length) v.slopePhase[x] = 0;
        if (v.slopeRunId != null && x < v.slopeRunId.length) v.slopeRunId[x] = 0;
    }

    private static void putManualTile(ModeVariant v, ManualTile value) {
        removeManualTile(v, value.x, value.y);
        v.manualTiles.add(value);
    }

    private static void removeManualTile(ModeVariant v, int x, int y) {
        if (v.manualTiles == null) return;
        for (int i = v.manualTiles.size() - 1; i >= 0; i--) {
            ManualTile tile = v.manualTiles.get(i);
            if (tile != null && tile.x == x && tile.y == y) v.manualTiles.remove(i);
        }
    }

    private static List<Point> continuousCells(List<Point> input, int width,
                                               int height, int brush) {
        LinkedHashSet<Point> line = new LinkedHashSet<Point>();
        Point previous = null;
        for (Point point : input) {
            if (point == null) continue;
            if (previous == null) addLinePoint(line, point.x, point.y);
            else rasterLine(line, previous.x, previous.y, point.x, point.y);
            previous = point;
        }
        LinkedHashSet<Point> expanded = new LinkedHashSet<Point>();
        for (Point point : line)
            for (int by = 0; by < brush; by++) for (int bx = 0; bx < brush; bx++) {
                int x = point.x + bx, y = point.y + by;
                if (x >= 0 && y >= 0 && x < width && y < height)
                    expanded.add(new Point(x, y));
            }
        return new ArrayList<Point>(expanded);
    }

    private static void rasterLine(Set<Point> out, int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            addLinePoint(out, x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }

    private static void addLinePoint(Set<Point> out, int x, int y) {
        out.add(new Point(x, y));
    }

    private static void normalizeLists(ModeVariant v) {
        if (v.manualTiles == null) v.manualTiles = new ArrayList<ManualTile>();
        if (v.manualSlopes == null) v.manualSlopes = new ArrayList<ManualSlopeRun>();
        if (v.manualIslands == null) v.manualIslands = new ArrayList<ManualIslandRun>();
        if (v.manualIceBridges == null) v.manualIceBridges = new ArrayList<ManualIceBridge>();
        if (v.manualDecorations == null)
            v.manualDecorations = new ArrayList<ManualDecoration>();
        if (v.manualBackground == null)
            v.manualBackground = new ArrayList<CustomMapDocument.BackgroundLayer>();
        if (v.manualEffects == null) v.manualEffects = new ArrayList<ManualEffect>();
    }

    private static Set<String> movingPlatformIds(ModeVariant v) {
        HashSet<String> out = new HashSet<String>();
        if (v.secondaryPlatforms != null)
            for (SecondaryPlatform platform : v.secondaryPlatforms) {
                PlatformPatrol patrol = platform == null ? null : platform.patrol;
                if (patrol != null && patrol.enabled) out.add(platform.id);
            }
        return out;
    }

    private static boolean sameMovingPlatforms(Set<String> before, ModeVariant after) {
        if (before.isEmpty()) return true;
        Set<String> current = movingPlatformIds(after);
        return current.containsAll(before);
    }

    private static String firstConnectorIssue(ModeVariant v) {
        if (v.manualTiles == null) return null;
        Set<String> checked = new HashSet<String>();
        for (ManualTile tile : v.manualTiles) {
            if (tile == null) continue;
            String[] themes = new String[]{tile.sourceTheme, tile.materialTheme};
            for (String theme : themes) {
                if (theme == null || theme.isEmpty() || !checked.add(theme)) continue;
                try {
                    TileCatalog.TileSet set = TileCatalog.find(theme);
                    if (set == null)
                        return "Theme '" + theme
                                + "' is no longer available, so this stroke cannot be connected safely.";
                    TileCatalog.TileSet family = set.resolveBaseGroundFamily(v.seed);
                    if (family.ground.isEmpty() || family.groundSurface.isEmpty())
                        return "Theme '" + theme
                                + "' is missing the surface/center pieces required by this stroke.";
                } catch (IOException e) {
                    return "Studio could not verify the connector pieces for theme '"
                            + theme + "'.";
                }
            }
        }
        return null;
    }

    private static String readableValidation(String validation, Point point) {
        String reason = validation == null || validation.trim().isEmpty()
                ? "the map would no longer be playable" : validation.trim();
        return "The whole stroke was cancelled near tile " + point.x + "/"
                + point.y + ": " + reason;
    }

    private static Point firstPoint(List<Point> points) {
        if (points == null) return null;
        for (Point point : points) if (point != null) return point;
        return null;
    }

    private static Point lastPoint(List<Point> points) {
        if (points == null) return null;
        for (int i = points.size() - 1; i >= 0; i--)
            if (points.get(i) != null) return points.get(i);
        return null;
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class SlopeCandidate {
        final int endX;
        final int endRow;
        final int snapDistance;
        final int phases;
        final SlopeMode mode;

        SlopeCandidate(int endX, int endRow, int snapDistance,
                       int phases, SlopeMode mode) {
            this.endX = endX;
            this.endRow = endRow;
            this.snapDistance = snapDistance;
            this.phases = phases;
            this.mode = mode;
        }
    }
}
