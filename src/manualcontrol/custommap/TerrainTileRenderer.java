package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.ModeVariant;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class TerrainTileRenderer {

    private TerrainTileRenderer() {}

    static class Assets {
        List<BufferedImage> ground = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSurface = new ArrayList<BufferedImage>();
        List<BufferedImage> groundFill = new ArrayList<BufferedImage>();
        List<BufferedImage> groundLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> groundRight = new ArrayList<BufferedImage>();
        List<BufferedImage> groundBottom = new ArrayList<BufferedImage>();
        List<BufferedImage> groundTopLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> groundTopRight = new ArrayList<BufferedImage>();
        List<BufferedImage> groundBottomLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> groundBottomRight = new ArrayList<BufferedImage>();
        List<BufferedImage> groundInnerTopLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> groundInnerTopRight = new ArrayList<BufferedImage>();
        List<BufferedImage> groundInnerBottomLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> groundInnerBottomRight = new ArrayList<BufferedImage>();
        List<BufferedImage> groundPlatformCenter = new ArrayList<BufferedImage>();
        List<BufferedImage> groundPlatformLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> groundPlatformRight = new ArrayList<BufferedImage>();
        List<BufferedImage> groundPlatformSingle = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSlopeUp = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSlopeDown = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSteepSlopeUp = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSteepSlopeDown = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSlopeUpSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSlopeDownSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSteepSlopeUpSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSteepSlopeDownSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSlopeUpEndpointSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSlopeDownEndpointSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSteepSlopeUpEndpointSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundSteepSlopeDownEndpointSupport = new ArrayList<BufferedImage>();
        List<BufferedImage> groundStepJunctionLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> groundStepJunctionRight = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfaceBase = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfaceTopLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfaceTopRight = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfacePlatformCenter = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfacePlatformLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfacePlatformRight = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfacePlatformSingle = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfaceSlopeUp = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfaceSlopeDown = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfaceStepJunctionLeft = new ArrayList<BufferedImage>();
        List<BufferedImage> iceSurfaceStepJunctionRight = new ArrayList<BufferedImage>();
        List<BufferedImage> water = new ArrayList<BufferedImage>();
        List<BufferedImage> waterSurface = new ArrayList<BufferedImage>();
        List<BufferedImage> waterFill = new ArrayList<BufferedImage>();
        List<BufferedImage> trees = new ArrayList<BufferedImage>();
        Map<String, BufferedImage> innerCornerJunctions =
                new LinkedHashMap<String, BufferedImage>();
        Map<Integer, BufferedImage> floatingIslandSpans =
                new LinkedHashMap<Integer, BufferedImage>();

        Map<String, Assets> themeAssets = new LinkedHashMap<String, Assets>();
        Map<String, BufferedImage> preferredAssets =
                new LinkedHashMap<String, BufferedImage>();

        boolean blankMissingGroundInterior;

        boolean sealSlopeUnderlay;

        boolean stackedSafeBandSlopes;

        boolean missingDiagonalInnerCorners;

        boolean pixelLockedInnerCornerOverlays;

        boolean embeddedBankIceBridge;

        int iceBridgeSocketInsetPixels = 72;

        boolean widthSpecificFloatingIslands;

        boolean snowOnlyFloatingIslands;

        boolean omitExposedWaterSurface;
    }

    private static final Map<BufferedImage, SeamProfile> PROFILE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<BufferedImage, SeamProfile>());
    private static final Map<BufferedImage, BufferedImage[]> ICE_TRANSITION_CACHE =
            Collections.synchronizedMap(new WeakHashMap<BufferedImage, BufferedImage[]>());

    static void draw(Graphics2D g, ModeVariant v, Assets images,
                     float tileSize, float originX, float originY) {
        if (g == null || v == null || images == null || tileSize <= 0f) return;

        for (int y = 0; y < v.height; y++) for (int x = 0; x < v.width; x++) {
            if (v.cell(x, y) != CustomMapDocument.CELL_WATER) continue;
            Assets cellImages = assetsAt(v, images, x, y);
            if (cellImages.omitExposedWaterSurface
                    && v.cell(x, y - 1) != CustomMapDocument.CELL_WATER) continue;
            drawCell(g, imageForWater(v, cellImages, x, y), x, y,
                    tileSize, originX, originY, 0f);
        }

        for (int y = 0; y < v.height; y++) for (int x = 0; x < v.width; x++) {
            if (v.cell(x, y) != CustomMapDocument.CELL_GROUND) continue;
            int neighborX = v.cell(x - 1, y) == CustomMapDocument.CELL_WATER ? x - 1
                    : v.cell(x + 1, y) == CustomMapDocument.CELL_WATER ? x + 1 : Integer.MIN_VALUE;
            if (neighborX == Integer.MIN_VALUE) continue;

            Assets waterImages = assetsAt(v, images, neighborX, y);
            if (waterImages.omitExposedWaterSurface
                    && v.cell(neighborX, y - 1) != CustomMapDocument.CELL_WATER) continue;
            drawCell(g, imageForWater(v, waterImages, neighborX, y), x, y,
                    tileSize, originX, originY, 0f);
        }

        drawIceBridgeSockets(g, v, images, tileSize, originX, originY);

        drawWidthSpecificFloatingIslands(g, v, images, tileSize, originX, originY);

        for (int y = 0; y < v.height; y++) for (int x = 0; x < v.width; x++) {
            int material = v.cell(x, y);
            if (material != CustomMapDocument.CELL_GROUND || isSlopeCell(v, x, y)) continue;
            Assets cellImages = assetsAt(v, images, x, y);
            if (isIceDeckCell(v, materialAssetsAt(v, images, x, y), x, y)) continue;
            if (isWidthSpecificFloatingIslandCell(v, cellImages, x, y)) continue;
            List<BufferedImage> stepJunction = groundStepJunction(v, cellImages, x, y);
            if (!stepJunction.isEmpty()) {
                List<BufferedImage> fill = nonEmpty(cellImages.groundFill,
                        cellImages.groundSurface, cellImages.ground);
                if (!fill.isEmpty())
                    drawImage(g, fill.get(stableRoleIndex(v, fill.size())),
                            x, y, tileSize, originX, originY, 0f);
                drawImage(g, stepJunction.get(stableRoleIndex(v,
                                stepJunction.size())),
                        x, y, tileSize, originX, originY, 0f);
                continue;
            }
            List<BufferedImage> selected = choose(v, cellImages, x, y, material);
            if (selected.isEmpty()) continue;
            BufferedImage image = preferredImage(v, images, cellImages,
                    x, y, selected.get(0));
            if (v.slopeDirection != null && x < v.slopeDirection.length
                    && v.slopeDirection[x] != 0 && v.surface != null
                    && y == v.surface[x] + 1) {
                BufferedImage support = slopeSupport(v, cellImages, x);
                if (support != null) continue;
                List<BufferedImage> slopes = slopeSurface(v, cellImages, x);
                if (!slopes.isEmpty()) {
                    int index = slopeIndex(v, x, slopes.size());
                    float sourceOffset = cellImages.stackedSafeBandSlopes
                            ? stackedSafeBandSlopeOffset(slopes, index,
                            v.slopeDirection[x], cellImages.groundSurface)
                            : slopeOffset(slopes, index,
                            v.slopeDirection[x], cellImages.groundSurface);
                    BufferedImage endpointSupport = slopeEndpointSupport(v, cellImages, x);
                    BufferedImage underlay = endpointSupport;
                    if (endpointSupport != null) {
                        List<BufferedImage> fills = nonEmpty(cellImages.groundFill,
                                cellImages.ground);
                        if (!fills.isEmpty())
                            drawImage(g, fills.get(stableRoleIndex(v, fills.size())),
                                    x, y, tileSize, originX, originY, 0f);
                    }
                    if (underlay == null) {
                        List<BufferedImage> fills = nonEmpty(cellImages.groundFill, cellImages.ground);
                        underlay = fills.isEmpty() ? image
                                : fills.get(stableRoleIndex(v, fills.size()));
                    }
                    if (cellImages.sealSlopeUnderlay) {
                        float scaledOffset = sourceOffset * tileSize
                                / Math.max(1f, slopes.get(index).getHeight());
                        if (cellImages.stackedSafeBandSlopes) {
                            if (scaledOffset < 0f)
                                drawImageExtendedTop(g, underlay, x, y, tileSize,
                                        originX, originY, scaledOffset);
                            else
                                drawImageClippedTop(g, underlay, x, y, tileSize,
                                        originX, originY, scaledOffset);
                        }
                        else
                            drawSlopeUnderlayEndpointAligned(g, underlay, x, y,
                                    tileSize, originX, originY, scaledOffset,
                                    v.slopeDirection[x], index, slopes.size(),
                                    cellImages.groundSurface);
                        continue;
                    }
                    float clippedTop = Math.max(0f, sourceOffset * tileSize
                            / Math.max(1f, slopes.get(index).getHeight()));
                    drawImageClippedTop(g, underlay, x, y, tileSize,
                            originX, originY, clippedTop);
                    continue;
                }
            }
            drawImage(g, image, x, y, tileSize, originX, originY, 0f);
        }

        for (int x = 0; x < v.width; x++) {
            if (v.slopeDirection == null || x >= v.slopeDirection.length
                    || v.slopeDirection[x] == 0 || v.surface == null) continue;
            int y = v.surface[x] + 1;
            if (v.cell(x, y) != CustomMapDocument.CELL_GROUND) continue;
            Assets cellImages = assetsAt(v, images, x, Math.max(0, v.surface[x]));
            BufferedImage support = slopeSupport(v, cellImages, x);
            if (support != null)
                drawImage(g, support, x, y, tileSize, originX, originY, 0f);
        }

        for (int y = 0; y < v.height; y++) for (int x = 0; x < v.width; x++) {
            if (!isSlopeCell(v, x, y)) continue;
            Assets cellImages = assetsAt(v, images, x, y);
            List<BufferedImage> source = slopeSurface(v, cellImages, x);
            if (source.isEmpty()) {
                drawCell(g, choose(v, cellImages, x, y, CustomMapDocument.CELL_GROUND),
                        x, y, tileSize, originX, originY, 0f);
                continue;
            }
            int index = slopeIndex(v, x, source.size());
            BufferedImage image = source.get(index);

            if (slopeSupport(v, cellImages, x) != null)
                drawImage(g, image, x, y, tileSize, originX, originY, 0f);
            else {
                float offset = cellImages.stackedSafeBandSlopes
                        ? stackedSafeBandSlopeOffset(source, index,
                        v.slopeDirection[x], cellImages.groundSurface)
                        : slopeOffset(source, index,
                        v.slopeDirection[x], cellImages.groundSurface);
                float scaledOffset = offset * tileSize / Math.max(1f, image.getHeight());
                if (cellImages.stackedSafeBandSlopes)
                    drawImage(g, image, x, y, tileSize, originX, originY,
                            scaledOffset);
                else if (cellImages.sealSlopeUnderlay)
                    drawSlopeImageEndpointAligned(g, image, x, y, tileSize,
                            originX, originY, scaledOffset, v.slopeDirection[x],
                            index, source.size(), cellImages.groundSurface);
                else
                    drawSlopeImage(g, image, x, y, tileSize, originX, originY,
                            scaledOffset);
            }
        }

        drawPixelLockedInnerCorners(g, v, images, tileSize, originX, originY);
        drawIceSurfaceLayer(g, v, images, tileSize, originX, originY);
    }

    private static Assets assetsAt(ModeVariant v, Assets root, int x, int y) {
        if (v == null || root == null || root.themeAssets == null
                || root.themeAssets.isEmpty()) return root;
        CustomMapDocument.ManualTile tile = v.manualTileAt(x, y);
        if (tile == null || tile.sourceTheme == null) return root;
        Assets selected = root.themeAssets.get(themeKey(tile.sourceTheme, tile.family));
        if (selected == null) selected = root.themeAssets.get(tile.sourceTheme);
        return selected == null ? root : selected;
    }

    private static Assets materialAssetsAt(ModeVariant v, Assets root, int x, int y) {
        if (v == null || root == null || root.themeAssets == null
                || root.themeAssets.isEmpty()) return root;
        CustomMapDocument.ManualTile tile = v.manualTileAt(x, y);
        if (tile == null || tile.materialTheme == null
                || tile.materialTheme.isEmpty()) return assetsAt(v, root, x, y);
        Assets selected = root.themeAssets.get(
                themeKey(tile.materialTheme, tile.materialFamily));
        if (selected == null) selected = root.themeAssets.get(tile.materialTheme);
        return selected == null ? assetsAt(v, root, x, y) : selected;
    }

    static String themeKey(String theme, String family) {
        String safeTheme = theme == null ? "" : theme;
        String safeFamily = family == null ? "" : family;
        return safeFamily.isEmpty() ? safeTheme : safeTheme + "\n" + safeFamily;
    }

    private static BufferedImage preferredImage(ModeVariant v, Assets root,
                                                Assets selectedAssets,
                                                int x, int y,
                                                BufferedImage fallback) {
        CustomMapDocument.ManualTile tile = v.manualTileAt(x, y);
        if (tile == null || tile.preferredAsset == null
                || tile.preferredAsset.isEmpty()) return fallback;
        String derived = derivedRole(v, x, y);
        if (!derived.equals(tile.preferredRole)) return fallback;
        BufferedImage image = root.preferredAssets.get(tile.preferredAsset);
        if (image == null) image = selectedAssets.preferredAssets.get(tile.preferredAsset);
        return image == null ? fallback : image;
    }

    private static String derivedRole(ModeVariant v, int x, int y) {
        boolean floating = v.surface == null || v.surface[x] < 0 || y < v.surface[x];
        boolean deck = !floating && v.cell(x, y + 1) != CustomMapDocument.CELL_GROUND;
        if ((floating || deck) && v.cell(x, y - 1) != CustomMapDocument.CELL_GROUND) {
            boolean left = v.cell(x - 1, y) == CustomMapDocument.CELL_GROUND;
            boolean right = v.cell(x + 1, y) == CustomMapDocument.CELL_GROUND;
            if (!left && !right) return "island-single";
            if (!left) return "platform-left";
            if (!right) return "platform-right";
            return "platform-center";
        }
        boolean up = v.cell(x, y - 1) == CustomMapDocument.CELL_GROUND;
        boolean down = v.cell(x, y + 1) == CustomMapDocument.CELL_GROUND;
        boolean left = v.cell(x - 1, y) == CustomMapDocument.CELL_GROUND;
        boolean right = v.cell(x + 1, y) == CustomMapDocument.CELL_GROUND;
        if (!up && !left) return "outer-top-left";
        if (!up && !right) return "outer-top-right";
        if (!down && !left) return "outer-bottom-left";
        if (!down && !right) return "outer-bottom-right";
        if (!up) return "surface";
        if (!left) return "left-edge";
        if (!right) return "right-edge";
        if (!down) return "bottom-edge";
        return "center";
    }

    private static void drawWidthSpecificFloatingIslands(
            Graphics2D g, ModeVariant v, Assets images, float tileSize,
            float originX, float originY) {
        if (v.secondaryPlatforms == null) return;
        for (CustomMapDocument.SecondaryPlatform platform : v.secondaryPlatforms) {
            if (platform == null) continue;
            int row = secondaryPlatformRow(v, platform);
            Assets platformImages = assetsAt(v, images, platform.startX, row);
            if (!platformImages.widthSpecificFloatingIslands
                    || platformImages.floatingIslandSpans.isEmpty()) continue;
            int width = platform.widthTiles();
            BufferedImage image = platformImages.floatingIslandSpans.get(width);
            if (image == null) continue;
            int left = Math.round(originX + platform.startX * tileSize);
            int top = Math.round(originY + row * tileSize);
            int right = Math.round(originX + (platform.endX + 1) * tileSize);
            int bottom = Math.round(originY + (row + 1) * tileSize);
            g.drawImage(image, left, top, Math.max(left + 1, right),
                    Math.max(top + 1, bottom), 0, 0, image.getWidth(),
                    image.getHeight(), null);
        }
    }

    private static boolean isWidthSpecificFloatingIslandCell(
            ModeVariant v, Assets images, int x, int y) {
        if (v.secondaryPlatforms == null) return false;
        for (CustomMapDocument.SecondaryPlatform platform : v.secondaryPlatforms) {
            if (platform == null) continue;
            int row = secondaryPlatformRow(v, platform);
            Assets platformImages = assetsAt(v, images, platform.startX, row);
            if (!platformImages.widthSpecificFloatingIslands
                    || !platformImages.floatingIslandSpans.containsKey(
                    platform.widthTiles())) continue;
            if (y == row
                    && x >= platform.startX && x <= platform.endX) return true;
        }
        return false;
    }

    private static int secondaryPlatformRow(
            ModeVariant v, CustomMapDocument.SecondaryPlatform platform) {
        return Math.max(0, Math.min(v.height - 1, Math.round(
                v.height + platform.supportLayer
                        / Math.max(1f, v.layerUnitsPerTile()))));
    }

    private static void drawPixelLockedInnerCorners(Graphics2D g, ModeVariant v,
                                                     Assets images, float tileSize,
                                                     float originX, float originY) {
        if (!images.pixelLockedInnerCornerOverlays
                || images.innerCornerJunctions.isEmpty()) return;
        for (int y = 0; y < v.height; y++) for (int x = 0; x < v.width; x++) {
            if (v.cell(x, y) != CustomMapDocument.CELL_GROUND || isSlopeCell(v, x, y))
                continue;
            boolean slopeNeighbor = false;
            if (v.slopeDirection != null) {
                for (int sx = Math.max(0, x - 1);
                     sx <= Math.min(v.slopeDirection.length - 1, x + 1); sx++)
                    if (v.slopeDirection[sx] != 0) slopeNeighbor = true;
            }
            if (slopeNeighbor) continue;
            boolean up = v.cell(x, y - 1) == CustomMapDocument.CELL_GROUND;
            boolean down = v.cell(x, y + 1) == CustomMapDocument.CELL_GROUND;
            boolean left = v.cell(x - 1, y) == CustomMapDocument.CELL_GROUND;
            boolean right = v.cell(x + 1, y) == CustomMapDocument.CELL_GROUND;
            String corner = null;
            float vertexX = x;
            float vertexY = y;
            if (up && left && v.cell(x - 1, y - 1) != CustomMapDocument.CELL_GROUND) {
                corner = "top-left";
            } else if (up && right
                    && v.cell(x + 1, y - 1) != CustomMapDocument.CELL_GROUND) {
                corner = "top-right";
                vertexX = x + 1f;
            } else if (down && left
                    && v.cell(x - 1, y + 1) != CustomMapDocument.CELL_GROUND) {
                corner = "bottom-left";
                vertexY = y + 1f;
            } else if (down && right
                    && v.cell(x + 1, y + 1) != CustomMapDocument.CELL_GROUND) {
                corner = "bottom-right";
                vertexX = x + 1f;
                vertexY = y + 1f;
            }
            if (corner == null) continue;
            BufferedImage image = images.innerCornerJunctions.get("block:" + corner);
            if (image == null) image = images.innerCornerJunctions.get("platform:" + corner);
            if (image == null) continue;
            drawVertexOverlay(g, image, vertexX, vertexY, tileSize, originX, originY);
        }
    }

    private static void drawVertexOverlay(Graphics2D g, BufferedImage image,
                                          float vertexX, float vertexY,
                                          float tileSize, float originX, float originY) {
        if (image == null) return;
        float scale = tileSize / 256f;
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;
        int left = Math.round(originX + vertexX * tileSize - width * .5f);
        int top = Math.round(originY + vertexY * tileSize - height * .5f);
        int right = Math.round(originX + vertexX * tileSize + width * .5f);
        int bottom = Math.round(originY + vertexY * tileSize + height * .5f);
        Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, left, top, Math.max(1, right - left),
                Math.max(1, bottom - top), null);
        if (interpolation != null)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
    }

    private static void drawIceSurfaceLayer(Graphics2D g, ModeVariant v,
                                            Assets images, float tileSize,
                                            float originX, float originY) {
        if (v.surfaceMaterials == null) return;
        for (int x = 0; x < v.width; x++) {
            if (x >= v.surfaceMaterials.length
                    || v.surfaceMaterials[x] != CustomMapDocument.SURFACE_ICE
                    || v.surface == null || x >= v.surface.length) continue;
            int y = v.surface[x];
            if (y < 0 || v.cell(x, y) != CustomMapDocument.CELL_GROUND) continue;
            Assets cellImages = materialAssetsAt(v, images, x, y);
            if (cellImages.iceSurfaceBase.isEmpty()) continue;
            IceSurfaceTopologyResolver.Role role =
                    IceSurfaceTopologyResolver.mainRole(
                            v, x, y, cellImages.embeddedBankIceBridge);
            if (role == IceSurfaceTopologyResolver.Role.BRIDGE) {
                List<BufferedImage> bridge = iceImagesForRole(cellImages, role);
                if (!bridge.isEmpty())
                    drawBridgeImage(g, bridge.get(stableRoleIndex(v, bridge.size())),
                            v, x, y, tileSize, originX, originY);
                continue;
            }
            if (role == IceSurfaceTopologyResolver.Role.PLATFORM_CENTER
                    || role == IceSurfaceTopologyResolver.Role.PLATFORM_LEFT
                    || role == IceSurfaceTopologyResolver.Role.PLATFORM_RIGHT
                    || role == IceSurfaceTopologyResolver.Role.PLATFORM_SINGLE) {
                List<BufferedImage> deck = iceImagesForRole(cellImages, role);
                if (!deck.isEmpty())
                    drawImage(g, deck.get(stableRoleIndex(v, deck.size())),
                            x, y, tileSize, originX, originY, 0f);
                continue;
            }
            boolean fadeLeft = iceTransitionFromSnow(v, x - 1, x);
            boolean fadeRight = iceTransitionFromSnow(v, x + 1, x);
            if (role == IceSurfaceTopologyResolver.Role.SLOPE_UP
                    || role == IceSurfaceTopologyResolver.Role.SLOPE_DOWN) {
                List<BufferedImage> source = iceImagesForRole(cellImages, role);
                if (source.isEmpty()) continue;
                int index = slopeIndex(v, x, source.size());
                BufferedImage image = iceTransitionVariant(
                        source.get(index), fadeLeft, fadeRight);
                Assets terrainImages = assetsAt(v, images, x, y);
                List<BufferedImage> terrain = slopeSurface(v, terrainImages, x);
                int terrainIndex = terrain.isEmpty() ? index
                        : Math.max(0, Math.min(terrain.size() - 1, index));
                float offset = cellImages.stackedSafeBandSlopes || terrain.isEmpty()
                        ? slopeOffset(source, index, v.slopeDirection[x], cellImages.iceSurfaceBase)
                        : slopeOffset(terrain, terrainIndex,
                        v.slopeDirection[x], cellImages.groundSurface);
                float scaledOffset = offset * tileSize / Math.max(1f, image.getHeight());
                if (cellImages.sealSlopeUnderlay && !cellImages.stackedSafeBandSlopes)
                    drawSlopeImageEndpointAligned(g, image, x, y, tileSize,
                            originX, originY, scaledOffset, v.slopeDirection[x],
                            index, source.size(), cellImages.groundSurface);
                else
                    drawSlopeImage(g, image, x, y, tileSize, originX, originY,
                            scaledOffset);
                continue;
            }
            List<BufferedImage> source = iceImagesForRole(cellImages, role);
            if (!source.isEmpty())
                drawImage(g, iceTransitionVariant(
                        source.get(stableRoleIndex(v, source.size())), fadeLeft, fadeRight), x, y,
                        tileSize, originX, originY, 0f);
        }

        if (v.secondaryPlatforms == null) return;
        for (CustomMapDocument.SecondaryPlatform platform : v.secondaryPlatforms) {
            if (platform == null
                    || platform.surfaceMaterial != CustomMapDocument.SURFACE_ICE)
                continue;
            int row = Math.max(0, Math.min(v.height - 1, Math.round(
                    v.height + platform.supportLayer
                            / Math.max(1f, v.layerUnitsPerTile()))));
            for (int local = 0; local < platform.widthTiles(); local++) {
                int x = platform.startX + local;
                Assets cellImages = materialAssetsAt(v, images, x, row);
                if (cellImages.snowOnlyFloatingIslands) continue;
                List<BufferedImage> source = iceImagesForRole(cellImages,
                        IceSurfaceTopologyResolver.platformRole(platform, local));
                if (!source.isEmpty())
                    drawImage(g, source.get(stableRoleIndex(v, source.size())),
                            x, row, tileSize, originX, originY, 0f);
            }
        }
    }

    private static List<BufferedImage> iceImagesForRole(
            Assets images, IceSurfaceTopologyResolver.Role role) {
        if (images == null || role == null) return Collections.emptyList();
        switch (role) {
            case BRIDGE:
            case MAIN_CENTER:
                return images.iceSurfaceBase;
            case MAIN_LEFT:
                return nonEmpty(images.iceSurfaceTopLeft, images.iceSurfaceBase);
            case MAIN_RIGHT:
                return nonEmpty(images.iceSurfaceTopRight, images.iceSurfaceBase);
            case PLATFORM_SINGLE:
                return nonEmpty(images.iceSurfacePlatformSingle,
                        images.iceSurfacePlatformCenter, images.iceSurfaceBase);
            case PLATFORM_LEFT:
                return nonEmpty(images.iceSurfacePlatformLeft,
                        images.iceSurfacePlatformCenter, images.iceSurfaceBase);
            case PLATFORM_RIGHT:
                return nonEmpty(images.iceSurfacePlatformRight,
                        images.iceSurfacePlatformCenter, images.iceSurfaceBase);
            case PLATFORM_CENTER:
                return nonEmpty(images.iceSurfacePlatformCenter,
                        images.iceSurfaceBase);
            case SLOPE_UP:
                return images.iceSurfaceSlopeUp;
            case SLOPE_DOWN:
                return images.iceSurfaceSlopeDown;
            case STEP_JUNCTION_LEFT:
                return nonEmpty(images.iceSurfaceStepJunctionLeft,
                        images.iceSurfaceBase);
            case STEP_JUNCTION_RIGHT:
                return nonEmpty(images.iceSurfaceStepJunctionRight,
                        images.iceSurfaceBase);
            default:
                return Collections.emptyList();
        }
    }

    private static List<BufferedImage> groundStepJunction(
            ModeVariant variant, Assets images, int x, int y) {
        if (variant == null || images == null || variant.surface == null
                || x < 0 || x >= variant.width || x >= variant.surface.length
                || y != variant.surface[x] || variant.surfaceMaterials == null
                || x >= variant.surfaceMaterials.length
                || variant.surfaceMaterials[x] != CustomMapDocument.SURFACE_ICE)
            return Collections.emptyList();
        IceSurfaceTopologyResolver.Role role =
                IceSurfaceTopologyResolver.mainRole(
                        variant, x, y, images.embeddedBankIceBridge);
        if (role == IceSurfaceTopologyResolver.Role.STEP_JUNCTION_LEFT)
            return images.groundStepJunctionLeft;
        if (role == IceSurfaceTopologyResolver.Role.STEP_JUNCTION_RIGHT)
            return images.groundStepJunctionRight;
        return Collections.emptyList();
    }

    private static void drawIceBridgeSockets(Graphics2D g, ModeVariant v,
                                             Assets images, float tileSize,
                                             float originX, float originY) {
        if (v == null || v.surface == null || v.surfaceMaterials == null)
            return;
        for (int x = 0; x < v.width; x++) {
            if (!IceSurfaceTopologyResolver.isIceBridgeColumn(v, x)
                    || IceSurfaceTopologyResolver.isIceBridgeColumn(v, x - 1)) continue;
            int end = IceSurfaceTopologyResolver.bridgeEnd(v, x);
            int y = v.surface[x];
            Assets bridgeImages = materialAssetsAt(v, images, x, y);
            if (!bridgeImages.embeddedBankIceBridge
                    || bridgeImages.iceSurfaceBase.isEmpty()) continue;
            BufferedImage socket = bridgeImages.iceSurfaceBase.get(
                    stableRoleIndex(v, bridgeImages.iceSurfaceBase.size()));
            int inset = Math.max(1, Math.round(tileSize * Math.max(1,
                    Math.min(256, bridgeImages.iceBridgeSocketInsetPixels)) / 256f));
            drawBankSocket(g, socket, x - 1, y, inset, false,
                    tileSize, originX, originY);
            drawBankSocket(g, socket, end + 1, y, inset, true,
                    tileSize, originX, originY);
            x = end;
        }
    }

    private static void drawBankSocket(Graphics2D g, BufferedImage image,
                                       int bankX, int y, int inset,
                                       boolean fromLeft, float tileSize,
                                       float originX, float originY) {
        if (image == null || bankX < 0) return;
        int left = Math.round(originX + bankX * tileSize);
        int top = Math.round(originY + y * tileSize);
        int right = Math.round(originX + (bankX + 1) * tileSize);
        int bottom = Math.round(originY + (y + 1) * tileSize);
        Graphics2D clipped = (Graphics2D) g.create();
        try {
            if (fromLeft)
                clipped.clipRect(left, top, Math.min(inset, right - left),
                        Math.max(1, bottom - top));
            else
                clipped.clipRect(Math.max(left, right - inset), top,
                        Math.min(inset, right - left), Math.max(1, bottom - top));
            clipped.drawImage(image, left, top, Math.max(1, right - left),
                    Math.max(1, bottom - top), null);
        } finally {
            clipped.dispose();
        }
    }

    private static void drawBridgeImage(Graphics2D g, BufferedImage image,
                                        ModeVariant v, int x, int y,
                                        float tileSize, float originX,
                                        float originY) {
        if (image == null) return;
        int start = IceSurfaceTopologyResolver.bridgeStart(v, x);
        int end = IceSurfaceTopologyResolver.bridgeEnd(v, x);
        if (start < 0 || end < start) return;
        int spanLeft = Math.round(originX + start * tileSize);
        int spanRight = Math.round(originX + (end + 1) * tileSize);
        int left = Math.round(originX + x * tileSize);
        int top = Math.round(originY + y * tileSize);
        int right = Math.round(originX + (x + 1) * tileSize);
        int bottom = Math.round(originY + (y + 1) * tileSize);
        int drawLeft = x > start ? left - 1 : left;
        int drawRight = x < end ? right + 1 : right;
        Graphics2D clipped = (Graphics2D) g.create();
        try {
            clipped.clipRect(spanLeft, top, Math.max(1, spanRight - spanLeft),
                    Math.max(1, bottom - top));
            clipped.drawImage(image, drawLeft, top,
                    Math.max(1, drawRight - drawLeft), Math.max(1, bottom - top), null);
        } finally {
            clipped.dispose();
        }
    }

    private static boolean isDeckCell(ModeVariant v, int x, int y) {
        return v.surface != null && x >= 0 && x < v.width
                && x < v.surface.length && y == v.surface[x]
                && IceBridgeBuilder.isDeckColumn(v, x);
    }

    private static boolean isIceDeckCell(ModeVariant v, Assets images, int x, int y) {
        return !images.iceSurfaceBase.isEmpty()
                && v.surfaceMaterials != null && x < v.surfaceMaterials.length
                && v.surfaceMaterials[x] == CustomMapDocument.SURFACE_ICE
                && v.surface != null && x < v.surface.length && y == v.surface[x]
                && IceBridgeBuilder.isDeckColumn(v, x);
    }

    private static boolean iceTransitionFromSnow(ModeVariant v, int neighborX, int iceX) {
        if (v == null || v.surfaceMaterials == null || neighborX < 0
                || neighborX >= v.surfaceMaterials.length || iceX < 0
                || iceX >= v.surfaceMaterials.length
                || v.surfaceMaterials[iceX] != CustomMapDocument.SURFACE_ICE
                || v.surfaceMaterials[neighborX] == CustomMapDocument.SURFACE_ICE)
            return false;
        int left = Math.min(neighborX, iceX);
        int right = Math.max(neighborX, iceX);
        return v.isContinuousSurfaceBetween(left, right);
    }

    private static BufferedImage iceTransitionVariant(
            BufferedImage source, boolean fadeLeft, boolean fadeRight) {
        if (source == null || (!fadeLeft && !fadeRight)) return source;
        int index = (fadeLeft ? 1 : 0) | (fadeRight ? 2 : 0);
        BufferedImage[] variants = ICE_TRANSITION_CACHE.get(source);
        if (variants == null) {
            variants = new BufferedImage[4];
            ICE_TRANSITION_CACHE.put(source, variants);
        }
        if (variants[index] != null) return variants[index];
        BufferedImage result = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int transition = Math.max(8, Math.round(source.getWidth() * .25f));
        for (int y = 0; y < source.getHeight(); y++)
            for (int x = 0; x < source.getWidth(); x++) {
                int rgba = source.getRGB(x, y);
                int alpha = (rgba >>> 24) & 0xff;
                float factor = 1f;
                if (fadeLeft && x < transition)
                    factor = Math.min(factor, x / (float) transition);
                if (fadeRight && x >= source.getWidth() - transition)
                    factor = Math.min(factor,
                            (source.getWidth() - 1 - x) / (float) transition);
                int blendedAlpha = Math.max(0, Math.min(255, Math.round(alpha * factor)));
                result.setRGB(x, y, (rgba & 0x00ffffff) | (blendedAlpha << 24));
            }
        variants[index] = result;
        return result;
    }

    private static void drawCell(Graphics2D g, BufferedImage image, int x, int y,
                                 float tileSize, float originX, float originY, float offsetY) {
        if (image != null) drawImage(g, image, x, y, tileSize, originX, originY, offsetY);
    }

    private static void drawCell(Graphics2D g, List<BufferedImage> source, int x, int y,
                                 float tileSize, float originX, float originY, float offsetY) {
        if (source == null || source.isEmpty()) return;
        drawImage(g, source.get(0), x, y, tileSize, originX, originY, offsetY);
    }

    private static void drawImage(Graphics2D g, BufferedImage image, int x, int y,
                                  float tileSize, float originX, float originY, float offsetY) {
        if (image == null) return;
        int left = Math.round(originX + x * tileSize);
        int top = Math.round(originY + y * tileSize + offsetY);
        int right = Math.round(originX + (x + 1) * tileSize);
        int bottom = Math.round(originY + (y + 1) * tileSize + offsetY);
        g.drawImage(image, left, top, Math.max(1, right - left), Math.max(1, bottom - top), null);
    }

    private static void drawSlopeImage(Graphics2D g, BufferedImage image, int x, int y,
                                       float tileSize, float originX, float originY,
                                       float offsetY) {
        SeamProfile seam = profile(image);
        int left = Math.round(originX + x * tileSize);
        int top = Math.round(originY + y * tileSize + offsetY);
        int right = Math.round(originX + (x + 1) * tileSize);
        int bottom = Math.round(originY + (y + 1) * tileSize + offsetY);

        RenderingHints originalHints = g.getRenderingHints();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, left - 1, top,
                Math.max(left + 1, right + 1), Math.max(top + 1, bottom),
                seam.contentLeft, 0, seam.contentRight + 1, image.getHeight(), null);
        g.setRenderingHints(originalHints);
    }

    private static void drawSlopeImageEndpointAligned(
            Graphics2D g, BufferedImage image, int x, int y,
            float tileSize, float originX, float originY, float offsetY,
            int direction, int index, int chainSize,
            List<BufferedImage> flatTiles) {
        if (image == null || chainSize <= 0 || flatTiles == null || flatTiles.isEmpty()) {
            drawSlopeImage(g, image, x, y, tileSize, originX, originY, offsetY);
            return;
        }
        SeamProfile seam = profile(image);
        float inset = profile(flatTiles.get(0)).flatInset
                * tileSize / Math.max(1f, image.getHeight());
        float phaseLeft;
        float phaseRight;
        if (direction < 0) {
            phaseLeft = (chainSize - index) / (float) chainSize;
            phaseRight = (chainSize - index - 1) / (float) chainSize;
        } else {
            phaseLeft = index / (float) chainSize;
            phaseRight = (index + 1) / (float) chainSize;
        }
        float correctionLeft = inset * phaseLeft;
        float correctionRight = inset * phaseRight;
        float contentWidth = Math.max(1f, seam.contentRight - seam.contentLeft);
        float left = originX + x * tileSize - 1f;
        float top = originY + y * tileSize + offsetY;
        float scaleX = (tileSize + 2f)
                / Math.max(1f, seam.contentRight - seam.contentLeft + 1f);
        float scaleY = tileSize / Math.max(1f, image.getHeight());
        float shearY = (correctionRight - correctionLeft) / contentWidth;
        AffineTransform transform = new AffineTransform(
                scaleX, shearY, 0f, scaleY,
                left - seam.contentLeft * scaleX,
                top + correctionLeft - seam.contentLeft * shearY);

        RenderingHints originalHints = g.getRenderingHints();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, transform, null);
        g.setRenderingHints(originalHints);
    }

    private static void drawSlopeUnderlayEndpointAligned(
            Graphics2D g, BufferedImage image, int x, int y,
            float tileSize, float originX, float originY, float offsetY,
            int direction, int index, int chainSize,
            List<BufferedImage> flatTiles) {
        if (image == null || chainSize <= 0 || flatTiles == null || flatTiles.isEmpty()) {
            drawImage(g, image, x, y, tileSize, originX, originY, offsetY);
            return;
        }
        float inset = profile(flatTiles.get(0)).flatInset
                * tileSize / Math.max(1f, image.getHeight());
        float phaseLeft;
        float phaseRight;
        if (direction < 0) {
            phaseLeft = (chainSize - index) / (float) chainSize;
            phaseRight = (chainSize - index - 1) / (float) chainSize;
        } else {
            phaseLeft = index / (float) chainSize;
            phaseRight = (index + 1) / (float) chainSize;
        }
        float correctionLeft = inset * phaseLeft;
        float correctionRight = inset * phaseRight;
        float left = originX + x * tileSize - 1f;
        float top = originY + y * tileSize + offsetY;
        float scaleX = (tileSize + 2f) / Math.max(1f, image.getWidth());
        float scaleY = tileSize / Math.max(1f, image.getHeight());
        float shearY = (correctionRight - correctionLeft)
                / Math.max(1f, image.getWidth() - 1f);
        AffineTransform transform = new AffineTransform(
                scaleX, shearY, 0f, scaleY, left, top + correctionLeft);

        RenderingHints originalHints = g.getRenderingHints();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, transform, null);
        g.setRenderingHints(originalHints);
    }

    private static void drawImageClippedTop(Graphics2D g, BufferedImage image, int x, int y,
                                            float tileSize, float originX, float originY,
                                            float clippedTop) {
        int left = Math.round(originX + x * tileSize);
        int cellTop = Math.round(originY + y * tileSize);
        int top = Math.round(originY + y * tileSize + clippedTop);
        int right = Math.round(originX + (x + 1) * tileSize);
        int bottom = Math.round(originY + (y + 1) * tileSize);
        if (top >= bottom) return;
        int sourceTop = Math.max(0, Math.min(image.getHeight() - 1,
                Math.round((top - cellTop) * image.getHeight() / Math.max(1f, tileSize))));
        g.drawImage(image, left, top, Math.max(left + 1, right), bottom,
                0, sourceTop, image.getWidth(), image.getHeight(), null);
    }

    private static void drawImageExtendedTop(Graphics2D g, BufferedImage image, int x, int y,
                                             float tileSize, float originX, float originY,
                                             float extension) {
        int left = Math.round(originX + x * tileSize);
        int top = Math.round(originY + y * tileSize + Math.min(0f, extension));
        int right = Math.round(originX + (x + 1) * tileSize);
        int bottom = Math.round(originY + (y + 1) * tileSize);
        g.drawImage(image, left, top, Math.max(left + 1, right), Math.max(top + 1, bottom),
                0, 0, image.getWidth(), image.getHeight(), null);
    }

    private static BufferedImage imageForWater(ModeVariant v, Assets images, int x, int y) {
        boolean top = v.cell(x, y - 1) != CustomMapDocument.CELL_WATER;
        List<BufferedImage> source = nonEmpty(top ? images.waterSurface : images.waterFill,
                images.water);
        return source.isEmpty() ? null : source.get(stableRoleIndex(v, source.size()));
    }

    private static boolean isSlopeCell(ModeVariant v, int x, int y) {
        return v.slopeDirection != null && x >= 0 && x < v.slopeDirection.length
                && v.slopeDirection[x] != 0 && v.surface != null && x < v.surface.length
                && y == v.surface[x];
    }

    private static BufferedImage slopeSupport(ModeVariant v, Assets images, int x) {
        if (v.slopeDirection == null || x < 0 || x >= v.slopeDirection.length
                || v.slopeDirection[x] == 0) return null;
        List<BufferedImage> surface = slopeSurface(v, images, x);
        boolean steep = isSteepSlope(v, x);
        List<BufferedImage> support = v.slopeDirection[x] < 0
                ? steep ? images.groundSteepSlopeUpSupport
                : images.groundSlopeUpSupport
                : steep ? images.groundSteepSlopeDownSupport
                : images.groundSlopeDownSupport;
        if (surface.isEmpty() || support.size() != surface.size()) return null;
        int index = slopeIndex(v, x, surface.size());
        return index >= 0 && index < support.size() ? support.get(index) : null;
    }

    private static BufferedImage slopeEndpointSupport(ModeVariant v, Assets images, int x) {
        if (v.slopeDirection == null || x < 0 || x >= v.slopeDirection.length
                || v.slopeDirection[x] == 0) return null;
        boolean rising = v.slopeDirection[x] < 0;
        List<BufferedImage> surface = slopeSurface(v, images, x);
        boolean steep = isSteepSlope(v, x);
        List<BufferedImage> support = rising
                ? steep ? images.groundSteepSlopeUpEndpointSupport
                : images.groundSlopeUpEndpointSupport
                : steep ? images.groundSteepSlopeDownEndpointSupport
                : images.groundSlopeDownEndpointSupport;
        if (surface.isEmpty() || support.isEmpty()) return null;
        int index = slopeIndex(v, x, surface.size());
        int endpoint = rising ? 0 : surface.size() - 1;
        return index == endpoint ? support.get(0) : null;
    }

    private static List<BufferedImage> slopeSurface(ModeVariant v,
                                                    Assets images, int x) {
        boolean rising = v.slopeDirection != null && x >= 0
                && x < v.slopeDirection.length && v.slopeDirection[x] < 0;
        if (isSteepSlope(v, x)) {
            List<BufferedImage> steep = rising
                    ? images.groundSteepSlopeUp : images.groundSteepSlopeDown;
            if (steep != null && !steep.isEmpty()) return steep;
        }
        return rising ? images.groundSlopeUp : images.groundSlopeDown;
    }

    private static boolean isSteepSlope(ModeVariant v, int x) {
        if (v == null || v.manualSlopes == null || v.slopeRunId == null
                || x < 0 || x >= v.slopeRunId.length) return false;
        int runId = v.slopeRunId[x];
        if (runId == 0) return false;
        for (CustomMapDocument.ManualSlopeRun run : v.manualSlopes)
            if (run != null && run.runId == runId)
                return "steep".equals(run.style);
        return false;
    }

    private static List<BufferedImage> choose(ModeVariant v, Assets images,
                                              int x, int y, int material) {
        if (material == CustomMapDocument.CELL_WATER) {
            BufferedImage water = imageForWater(v, images, x, y);
            return water == null ? Collections.<BufferedImage>emptyList()
                    : Collections.singletonList(water);
        }
        if (material != CustomMapDocument.CELL_GROUND) return Collections.emptyList();

        boolean floating = v.surface[x] < 0 || y < v.surface[x];
        boolean deck = !floating
                && v.cell(x, y + 1) != CustomMapDocument.CELL_GROUND;
        if ((floating || deck) && v.cell(x, y - 1) != CustomMapDocument.CELL_GROUND) {
            boolean leftPlatform = v.cell(x - 1, y) == CustomMapDocument.CELL_GROUND
                    && (deck || v.surface[x - 1] < 0 || y < v.surface[x - 1]);
            boolean rightPlatform = v.cell(x + 1, y) == CustomMapDocument.CELL_GROUND
                    && (deck || v.surface[x + 1] < 0 || y < v.surface[x + 1]);
            if (!leftPlatform && !rightPlatform)
                return selected(v, nonEmpty(images.groundPlatformSingle,
                        images.groundPlatformCenter, images.groundSurface, images.ground));
            if (!leftPlatform)
                return selected(v, nonEmpty(images.groundPlatformLeft,
                        images.groundPlatformCenter, images.groundSurface, images.ground));
            if (!rightPlatform)
                return selected(v, nonEmpty(images.groundPlatformRight,
                        images.groundPlatformCenter, images.groundSurface, images.ground));
            return selected(v, nonEmpty(images.groundPlatformCenter,
                    images.groundSurface, images.ground));
        }

        boolean up = v.cell(x, y - 1) == CustomMapDocument.CELL_GROUND;
        boolean down = v.cell(x, y + 1) == CustomMapDocument.CELL_GROUND;
        boolean actualLeft = v.cell(x - 1, y) == CustomMapDocument.CELL_GROUND
                && !isDeckCell(v, x - 1, y);
        boolean actualRight = v.cell(x + 1, y) == CustomMapDocument.CELL_GROUND
                && !isDeckCell(v, x + 1, y);
        boolean leftContour = mainContourConnects(v, x - 1, x, x, y, up)
                && !isDeckCell(v, x - 1, y);
        boolean rightContour = mainContourConnects(v, x, x + 1, x, y, up)
                && !isDeckCell(v, x + 1, y);

        boolean left = actualLeft || leftContour;
        boolean right = actualRight || rightContour;
        boolean upLeft = v.cell(x - 1, y - 1) == CustomMapDocument.CELL_GROUND;
        boolean upRight = v.cell(x + 1, y - 1) == CustomMapDocument.CELL_GROUND;
        boolean downLeft = v.cell(x - 1, y + 1) == CustomMapDocument.CELL_GROUND;
        boolean downRight = v.cell(x + 1, y + 1) == CustomMapDocument.CELL_GROUND;
        List<BufferedImage> source;

        if (up && actualLeft && !upLeft && !leftContour
                && !(images.missingDiagonalInnerCorners
                ? images.groundInnerTopLeft : images.groundInnerBottomRight).isEmpty())
            source = images.missingDiagonalInnerCorners
                    ? images.groundInnerTopLeft : images.groundInnerBottomRight;
        else if (up && actualRight && !upRight && !rightContour
                && !(images.missingDiagonalInnerCorners
                ? images.groundInnerTopRight : images.groundInnerBottomLeft).isEmpty())
            source = images.missingDiagonalInnerCorners
                    ? images.groundInnerTopRight : images.groundInnerBottomLeft;
        else if (down && actualLeft && !downLeft && !leftContour
                && !(images.missingDiagonalInnerCorners
                ? images.groundInnerBottomLeft : images.groundInnerTopRight).isEmpty())
            source = images.missingDiagonalInnerCorners
                    ? images.groundInnerBottomLeft : images.groundInnerTopRight;
        else if (down && actualRight && !downRight && !rightContour
                && !(images.missingDiagonalInnerCorners
                ? images.groundInnerBottomRight : images.groundInnerTopLeft).isEmpty())
            source = images.missingDiagonalInnerCorners
                    ? images.groundInnerBottomRight : images.groundInnerTopLeft;
        else if (!up && !left) source = nonEmpty(images.groundTopLeft, images.groundSurface, images.ground);
        else if (!up && !right) source = nonEmpty(images.groundTopRight, images.groundSurface, images.ground);
        else if (!down && !left) source = nonEmpty(images.groundBottomLeft, images.groundBottom, images.groundFill);
        else if (!down && !right) source = nonEmpty(images.groundBottomRight, images.groundBottom, images.groundFill);
        else if (!up) source = nonEmpty(images.groundSurface, images.ground);
        else if (!left) source = nonEmpty(images.groundLeft, images.groundFill, images.ground);
        else if (!right) source = nonEmpty(images.groundRight, images.groundFill, images.ground);
        else if (!down) source = nonEmpty(images.groundBottom, images.groundFill, images.ground);
        else source = images.blankMissingGroundInterior
                ? images.groundFill : nonEmpty(images.groundFill, images.ground);
        return selected(v, source);
    }

    private static boolean mainContourConnects(ModeVariant v, int leftX, int rightX,
                                               int cellX, int y, boolean hasGroundAbove) {
        if (hasGroundAbove || v.surface == null || cellX < 0 || cellX >= v.width
                || y != v.surface[cellX] || leftX < 0 || rightX >= v.width) return false;
        return v.isContinuousSurfaceBetween(leftX, rightX);
    }

    private static List<BufferedImage> selected(ModeVariant v, List<BufferedImage> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(source.get(stableRoleIndex(v, source.size())));
    }

    @SafeVarargs
    private static <T> List<T> nonEmpty(List<T>... choices) {
        for (List<T> choice : choices) if (choice != null && !choice.isEmpty()) return choice;
        return Collections.emptyList();
    }

    private static int stableRoleIndex(ModeVariant v, int size) {
        long value = v.seed;
        value ^= value >>> 33;
        return (int) Math.floorMod(value, size);
    }

    private static int slopeIndex(ModeVariant v, int x, int size) {
        int phase = v.slopePhase == null || x >= v.slopePhase.length ? 0 : v.slopePhase[x];
        return Math.max(0, Math.min(size - 1, (Math.max(1, phase) - 1) * size / 100));
    }

    private static float slopeOffset(List<BufferedImage> chain, int index, int direction,
                                     List<BufferedImage> flatTiles) {
        if (chain == null || chain.isEmpty()) return 0f;
        BufferedImage first = chain.get(0);
        SeamProfile firstProfile = profile(first);
        int flatInset = flatTiles == null || flatTiles.isEmpty()
                ? 0 : profile(flatTiles.get(0)).flatInset;
        float offset = direction < 0
                ? first.getHeight() + flatInset - firstProfile.left
                : flatInset - firstProfile.left;
        for (int i = 1; i <= index && i < chain.size(); i++) {
            SeamProfile previous = profile(chain.get(i - 1));
            SeamProfile current = profile(chain.get(i));
            offset += previous.right - current.left;
        }
        return offset;
    }

    private static float stackedSafeBandSlopeOffset(
            List<BufferedImage> chain, int index, int direction,
            List<BufferedImage> flatTiles) {
        if (chain == null || chain.isEmpty()) return 0f;
        BufferedImage first = chain.get(0);
        int flatInset = flatTiles == null || flatTiles.isEmpty()
                ? 0 : profile(flatTiles.get(0)).flatInset;
        float offset = direction < 0
                ? first.getHeight() + flatInset - columnContour(first, 0)
                : flatInset - columnContour(first, 0);
        for (int i = 1; i <= index && i < chain.size(); i++) {
            BufferedImage previous = chain.get(i - 1);
            BufferedImage current = chain.get(i);
            offset += columnContour(previous, previous.getWidth() - 1)
                    - columnContour(current, 0);
        }
        return offset;
    }

    private static int columnContour(BufferedImage image, int x) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return 0;
        int safeX = Math.max(0, Math.min(image.getWidth() - 1, x));
        for (int y = 0; y < image.getHeight(); y++)
            if (((image.getRGB(safeX, y) >>> 24) & 0xff) > 16) return y;
        return image.getHeight();
    }

    private static SeamProfile profile(BufferedImage image) {
        SeamProfile cached = PROFILE_CACHE.get(image);
        if (cached != null) return cached;
        int band = Math.max(2, Math.round(image.getWidth() * .03f));
        int left = edgeContour(image, 0, Math.min(image.getWidth(), band));
        int right = edgeContour(image, Math.max(0, image.getWidth() - band), image.getWidth());
        int flat = edgeContour(image, Math.max(0, image.getWidth() / 5),
                Math.max(1, image.getWidth() * 4 / 5));
        int contentLeft = image.getWidth(), contentRight = -1;
        for (int x = 0; x < image.getWidth(); x++) {
            boolean visible = false;
            for (int y = 0; y < image.getHeight(); y++)
                if (((image.getRGB(x, y) >>> 24) & 0xff) > 16) {
                    visible = true;
                    break;
                }
            if (visible) {
                contentLeft = Math.min(contentLeft, x);
                contentRight = Math.max(contentRight, x);
            }
        }
        if (contentRight < contentLeft) {
            contentLeft = 0;
            contentRight = Math.max(0, image.getWidth() - 1);
        }
        SeamProfile created = new SeamProfile(left, right, flat, contentLeft, contentRight);
        PROFILE_CACHE.put(image, created);
        return created;
    }

    private static int edgeContour(BufferedImage image, int fromX, int toX) {
        ArrayList<Integer> values = new ArrayList<Integer>();
        for (int x = fromX; x < toX; x++) {
            int found = image.getHeight();
            for (int y = 0; y < image.getHeight(); y++) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) > 16) {
                    found = y;
                    break;
                }
            }
            values.add(found);
        }
        if (values.isEmpty()) return 0;
        Collections.sort(values);
        int trim = values.size() >= 10 ? values.size() / 10 : 0;
        int middle = trim + (values.size() - trim * 2) / 2;
        return values.get(Math.max(0, Math.min(values.size() - 1, middle)));
    }

    private static final class SeamProfile {
        final int left;
        final int right;
        final int flatInset;
        final int contentLeft;
        final int contentRight;

        SeamProfile(int left, int right, int flatInset, int contentLeft, int contentRight) {
            this.left = left;
            this.right = right;
            this.flatInset = flatInset;
            this.contentLeft = contentLeft;
            this.contentRight = contentRight;
        }
    }

}
