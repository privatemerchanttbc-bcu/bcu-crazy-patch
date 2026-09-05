package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.BackgroundAssetRef;
import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;
import manualcontrol.custommap.CustomMapDocument.TreePlacement;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

final class CustomMapChunkWriter {

    static final int BAKED_TILE_PIXELS = 128;

    private CustomMapChunkWriter() {}

    static void write(File mapRoot, CustomMapDocument doc, TileCatalog.TileSet tiles) throws IOException {
        if (tiles != null && doc != null && doc.spec != null)
            tiles = tiles.resolveBaseGroundFamily(doc.spec.seed);
        if (doc != null)
            doc.themeProfile = CustomMapDocument.ThemeProfile.normalized(doc.themeProfile);
        CustomMapGenerator.applyFloatingIslandMaterialPolicy(doc,
                tiles != null && tiles.snowOnlyFloatingIslands);
        assignFloatingIslandCollisionProfiles(doc, tiles);
        File assets = new File(mapRoot, "assets");
        copyCategory(tiles.ground, new File(assets, "ground"));
        copyCategory(tiles.water, new File(assets, "water"));

        copyCategory(tiles.waterSurface, new File(assets, "water_surface"));

        copyCategory(tiles.waterFill, new File(assets, "water_fill"));
        copyCategory(tiles.trees, new File(assets, "tree"));
        writeInnerCornerJunctions(new File(assets, "junction"), tiles);
        writeProps(new File(assets, "props"), doc, tiles);
        writeBackgrounds(new File(assets, "background"), doc, tiles);
        writeVfx(new File(assets, "vfx"), doc, tiles);
        TileCatalog.TileSet iceTiles = manualIceTiles(doc, tiles);
        writeIceSurfaceAssets(assets, doc, iceTiles);
        assignIceSurfaceKeys(doc, iceTiles);

        BakedAssets images = new BakedAssets();
        images.ground = readAll(tiles.ground);
        images.groundSurface = readAll(tiles.groundSurface);
        images.groundFill = readAll(tiles.groundFill);
        images.groundLeft = readAll(tiles.groundLeft);
        images.groundRight = readAll(tiles.groundRight);
        images.groundBottom = readAll(tiles.groundBottom);
        images.groundTopLeft = readAll(tiles.groundTopLeft);
        images.groundTopRight = readAll(tiles.groundTopRight);
        images.groundBottomLeft = readAll(tiles.groundBottomLeft);
        images.groundBottomRight = readAll(tiles.groundBottomRight);
        images.groundInnerTopLeft = readAll(tiles.groundInnerTopLeft);
        images.groundInnerTopRight = readAll(tiles.groundInnerTopRight);
        images.groundInnerBottomLeft = readAll(tiles.groundInnerBottomLeft);
        images.groundInnerBottomRight = readAll(tiles.groundInnerBottomRight);
        images.groundPlatformCenter = readAll(tiles.groundPlatformCenter);
        images.groundPlatformLeft = readAll(tiles.groundPlatformLeft);
        images.groundPlatformRight = readAll(tiles.groundPlatformRight);
        images.groundPlatformSingle = readAll(tiles.groundPlatformSingle);
        images.groundSlopeUp = readAll(tiles.groundSlopeUp);
        images.groundSlopeDown = readAll(tiles.groundSlopeDown);
        images.groundSteepSlopeUp = readAll(tiles.groundSteepSlopeUp);
        images.groundSteepSlopeDown = readAll(tiles.groundSteepSlopeDown);
        images.groundSlopeUpSupport = readAll(tiles.groundSlopeUpSupport);
        images.groundSlopeDownSupport = readAll(tiles.groundSlopeDownSupport);
        images.groundSteepSlopeUpSupport = readAll(tiles.groundSteepSlopeUpSupport);
        images.groundSteepSlopeDownSupport = readAll(tiles.groundSteepSlopeDownSupport);
        images.groundSlopeUpEndpointSupport = readAll(tiles.groundSlopeUpEndpointSupport);
        images.groundSlopeDownEndpointSupport = readAll(tiles.groundSlopeDownEndpointSupport);
        images.groundSteepSlopeUpEndpointSupport =
                readAll(tiles.groundSteepSlopeUpEndpointSupport);
        images.groundSteepSlopeDownEndpointSupport =
                readAll(tiles.groundSteepSlopeDownEndpointSupport);
        images.groundStepJunctionLeft = readAll(tiles.groundStepJunctionLeft);
        images.groundStepJunctionRight = readAll(tiles.groundStepJunctionRight);
        images.iceSurfaceBase = readIceBase(tiles, tiles.groundSurface);
        images.iceSurfaceTopLeft = readIceBase(tiles, tiles.groundTopLeft);
        images.iceSurfaceTopRight = readIceBase(tiles, tiles.groundTopRight);
        images.iceSurfacePlatformCenter = readIceBase(tiles, tiles.groundPlatformCenter);
        images.iceSurfacePlatformLeft = readIceBase(tiles, tiles.groundPlatformLeft);
        images.iceSurfacePlatformRight = readIceBase(tiles, tiles.groundPlatformRight);
        images.iceSurfacePlatformSingle = readIceBase(tiles, tiles.groundPlatformSingle);
        images.iceSurfaceSlopeUp = readIceBase(tiles, tiles.groundSlopeUp);
        images.iceSurfaceSlopeDown = readIceBase(tiles, tiles.groundSlopeDown);
        images.iceSurfaceStepJunctionLeft = readIceBaseByKey(
                tiles, IceSurfaceTopologyResolver.STEP_JUNCTION_LEFT_KEY);
        images.iceSurfaceStepJunctionRight = readIceBaseByKey(
                tiles, IceSurfaceTopologyResolver.STEP_JUNCTION_RIGHT_KEY);
        images.blankMissingGroundInterior = tiles.strictGroundRoles;
        images.sealSlopeUnderlay = tiles.sealSlopeUnderlay;
        images.stackedSafeBandSlopes = tiles.stackedSafeBandSlopes;
        images.missingDiagonalInnerCorners = tiles.missingDiagonalInnerCorners;
        images.pixelLockedInnerCornerOverlays = tiles.pixelLockedInnerCornerOverlays;
        images.embeddedBankIceBridge = tiles.embeddedBankIceBridge;
        images.iceBridgeSocketInsetPixels = tiles.iceBridgeSocketInsetPixels;
        images.widthSpecificFloatingIslands = tiles.widthSpecificFloatingIslands;
        images.snowOnlyFloatingIslands = tiles.snowOnlyFloatingIslands;
        for (Map.Entry<Integer, File> entry : tiles.floatingIslandSpans.entrySet()) {
            BufferedImage image = ImageIO.read(entry.getValue());
            if (image != null) images.floatingIslandSpans.put(entry.getKey(), image);
        }
        for (Map.Entry<String, File> entry : tiles.innerCornerJunctions.entrySet()) {
            BufferedImage image = ImageIO.read(entry.getValue());
            if (image != null) images.innerCornerJunctions.put(entry.getKey(), image);
        }
        images.water = readAll(tiles.water);
        images.waterSurface = readAll(tiles.waterSurface);
        images.waterFill = readAll(tiles.waterFill);
        images.omitExposedWaterSurface = true;
        images.trees = readAll(tiles.trees);
        loadManualThemeAssets(mapRoot, doc, images);
        int tilePx = Math.max(1, Math.min(BAKED_TILE_PIXELS, tiles.tilePixels));
        if (doc.battleTerrain != null)
            bakeVariant(mapRoot, "battle", doc.battleTerrain, tilePx, images);
        for (ModeVariant variant : doc.variants.values()) {
            bakeVariant(mapRoot, variant.mode, variant, tilePx, images);
        }
    }

    private static TileCatalog.TileSet manualIceTiles(CustomMapDocument doc,
                                                      TileCatalog.TileSet fallback)
            throws IOException {
        ArrayList<ModeVariant> variants = new ArrayList<ModeVariant>();
        if (doc != null && doc.battleTerrain != null) variants.add(doc.battleTerrain);
        if (doc != null && doc.variants != null) variants.addAll(doc.variants.values());
        for (ModeVariant variant : variants) {
            if (variant == null || variant.manualTiles == null) continue;
            for (CustomMapDocument.ManualTile tile : variant.manualTiles) {
                if (tile == null
                        || !CustomMapDocument.MATERIAL_ICE.equals(tile.material)
                        || tile.materialTheme == null || tile.materialTheme.isEmpty()) continue;
                TileCatalog.TileSet set = TileCatalog.find(tile.materialTheme);
                if (set != null && set.supportsBreakableIceAssets())
                    return paletteFamily(set, tile.materialFamily,
                            doc.spec == null ? 0L : doc.spec.seed);
            }
        }
        return fallback;
    }

    private static void loadManualThemeAssets(File mapRoot,
                                              CustomMapDocument doc,
                                              BakedAssets root) throws IOException {
        Set<String> keys = new HashSet<String>();
        collectManualThemeKeys(doc == null ? null : doc.battleTerrain, keys);
        if (doc != null && doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                collectManualThemeKeys(variant, keys);
        for (String key : keys) {
            int split = key.indexOf('\n');
            String theme = split < 0 ? key : key.substring(0, split);
            String family = split < 0 ? "" : key.substring(split + 1);
            TileCatalog.TileSet set = TileCatalog.find(theme);
            if (set == null)
                throw new IOException("Palette theme '" + theme
                        + "' is unavailable; the edited map cannot be baked portably.");
            TileCatalog.TileSet source = paletteFamily(set, family,
                    doc == null || doc.spec == null ? 0L : doc.spec.seed);
            root.themeAssets.put(key, readTerrainAssets(source));
            copyPaletteConnectorSet(new File(mapRoot, "assets/palette/"
                    + safeName(theme) + "/connectors/" + safeName(family)), source);
        }
        loadPreferredAssets(doc == null ? null : doc.battleTerrain, root);
        if (doc != null && doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                loadPreferredAssets(variant, root);
        embedSelectedPaletteAssets(mapRoot, doc);
    }

    private static void loadPreferredAssets(ModeVariant variant,
                                            BakedAssets root) throws IOException {
        if (variant == null || variant.manualTiles == null) return;
        for (CustomMapDocument.ManualTile tile : variant.manualTiles) {
            if (tile == null) continue;
            loadPreferredAsset(tile.preferredAsset, root);
            loadPreferredAsset(tile.materialAsset, root);
        }
    }

    private static void loadPreferredAsset(String assetId, BakedAssets root)
            throws IOException {
            if (assetId == null || assetId.isEmpty()
                    || root.preferredAssets.containsKey(assetId)) return;
            int slash = assetId.indexOf('/');
            if (slash <= 0 || slash + 1 >= assetId.length()) return;
            TileCatalog.TileSet set = TileCatalog.find(
                    assetId.substring(0, slash));
            if (set == null || set.root == null) return;
            File file = new File(set.root, assetId.substring(slash + 1)
                    .replace('/', File.separatorChar)).getCanonicalFile();
            if (!file.toPath().startsWith(set.root.getCanonicalFile().toPath())
                    || !file.isFile()) return;
            BufferedImage image = ImageIO.read(file);
            if (image != null) root.preferredAssets.put(assetId, image);
    }

    private static void collectManualThemeKeys(ModeVariant variant,
                                               Set<String> out) {
        if (variant == null || variant.manualTiles == null) return;
        for (CustomMapDocument.ManualTile tile : variant.manualTiles) {
            if (tile == null) continue;
            if (tile.sourceTheme != null && !tile.sourceTheme.trim().isEmpty())
                out.add(TerrainTileRenderer.themeKey(tile.sourceTheme, tile.family));
            if (tile.materialTheme != null && !tile.materialTheme.trim().isEmpty())
                out.add(TerrainTileRenderer.themeKey(
                        tile.materialTheme, tile.materialFamily));
        }
    }

    private static TileCatalog.TileSet paletteFamily(TileCatalog.TileSet set,
                                                     String family,
                                                     long seed) {
        if (family != null && !family.isEmpty())
            for (TileCatalog.TileSet candidate : set.groundFamilies)
                if (candidate != null && family.equalsIgnoreCase(candidate.groundFamily))
                    return candidate;
        return set.resolveBaseGroundFamily(seed);
    }

    private static BakedAssets readTerrainAssets(TileCatalog.TileSet set)
            throws IOException {
        BakedAssets images = new BakedAssets();
        images.ground = readAll(set.ground);
        images.groundSurface = readAll(set.groundSurface);
        images.groundFill = readAll(set.groundFill);
        images.groundLeft = readAll(set.groundLeft);
        images.groundRight = readAll(set.groundRight);
        images.groundBottom = readAll(set.groundBottom);
        images.groundTopLeft = readAll(set.groundTopLeft);
        images.groundTopRight = readAll(set.groundTopRight);
        images.groundBottomLeft = readAll(set.groundBottomLeft);
        images.groundBottomRight = readAll(set.groundBottomRight);
        images.groundInnerTopLeft = readAll(set.groundInnerTopLeft);
        images.groundInnerTopRight = readAll(set.groundInnerTopRight);
        images.groundInnerBottomLeft = readAll(set.groundInnerBottomLeft);
        images.groundInnerBottomRight = readAll(set.groundInnerBottomRight);
        images.groundPlatformCenter = readAll(set.groundPlatformCenter);
        images.groundPlatformLeft = readAll(set.groundPlatformLeft);
        images.groundPlatformRight = readAll(set.groundPlatformRight);
        images.groundPlatformSingle = readAll(set.groundPlatformSingle);
        images.groundSlopeUp = readAll(set.groundSlopeUp);
        images.groundSlopeDown = readAll(set.groundSlopeDown);
        images.groundSteepSlopeUp = readAll(set.groundSteepSlopeUp);
        images.groundSteepSlopeDown = readAll(set.groundSteepSlopeDown);
        images.groundSlopeUpSupport = readAll(set.groundSlopeUpSupport);
        images.groundSlopeDownSupport = readAll(set.groundSlopeDownSupport);
        images.groundSteepSlopeUpSupport = readAll(set.groundSteepSlopeUpSupport);
        images.groundSteepSlopeDownSupport = readAll(set.groundSteepSlopeDownSupport);
        images.groundSlopeUpEndpointSupport = readAll(set.groundSlopeUpEndpointSupport);
        images.groundSlopeDownEndpointSupport = readAll(set.groundSlopeDownEndpointSupport);
        images.groundSteepSlopeUpEndpointSupport =
                readAll(set.groundSteepSlopeUpEndpointSupport);
        images.groundSteepSlopeDownEndpointSupport =
                readAll(set.groundSteepSlopeDownEndpointSupport);
        images.groundStepJunctionLeft = readAll(set.groundStepJunctionLeft);
        images.groundStepJunctionRight = readAll(set.groundStepJunctionRight);
        images.iceSurfaceBase = readIceBase(set, set.groundSurface);
        images.iceSurfaceTopLeft = readIceBase(set, set.groundTopLeft);
        images.iceSurfaceTopRight = readIceBase(set, set.groundTopRight);
        images.iceSurfacePlatformCenter = readIceBase(set, set.groundPlatformCenter);
        images.iceSurfacePlatformLeft = readIceBase(set, set.groundPlatformLeft);
        images.iceSurfacePlatformRight = readIceBase(set, set.groundPlatformRight);
        images.iceSurfacePlatformSingle = readIceBase(set, set.groundPlatformSingle);
        images.iceSurfaceSlopeUp = readIceBase(set, set.groundSlopeUp);
        images.iceSurfaceSlopeDown = readIceBase(set, set.groundSlopeDown);
        images.water = readAll(set.water);
        images.waterSurface = readAll(set.waterSurface);
        images.waterFill = readAll(set.waterFill);
        images.blankMissingGroundInterior = set.strictGroundRoles;
        images.sealSlopeUnderlay = set.sealSlopeUnderlay;
        images.stackedSafeBandSlopes = set.stackedSafeBandSlopes;
        images.missingDiagonalInnerCorners = set.missingDiagonalInnerCorners;
        images.pixelLockedInnerCornerOverlays = set.pixelLockedInnerCornerOverlays;
        images.embeddedBankIceBridge = set.embeddedBankIceBridge;
        images.iceBridgeSocketInsetPixels = set.iceBridgeSocketInsetPixels;
        images.widthSpecificFloatingIslands = set.widthSpecificFloatingIslands;
        images.snowOnlyFloatingIslands = set.snowOnlyFloatingIslands;
        images.omitExposedWaterSurface = true;
        for (Map.Entry<Integer, File> entry : set.floatingIslandSpans.entrySet()) {
            BufferedImage image = ImageIO.read(entry.getValue());
            if (image != null) images.floatingIslandSpans.put(entry.getKey(), image);
        }
        return images;
    }

    private static void copyPaletteConnectorSet(File target,
                                                TileCatalog.TileSet set)
            throws IOException {
        copyCategory(set.ground, new File(target, "ground"));
        copyCategory(set.groundSurface, new File(target, "surface"));
        copyCategory(set.groundFill, new File(target, "fill"));
        copyCategory(set.groundLeft, new File(target, "left"));
        copyCategory(set.groundRight, new File(target, "right"));
        copyCategory(set.groundBottom, new File(target, "bottom"));
        copyCategory(set.groundTopLeft, new File(target, "top_left"));
        copyCategory(set.groundTopRight, new File(target, "top_right"));
        copyCategory(set.groundBottomLeft, new File(target, "bottom_left"));
        copyCategory(set.groundBottomRight, new File(target, "bottom_right"));
        copyCategory(set.groundInnerTopLeft, new File(target, "inner_top_left"));
        copyCategory(set.groundInnerTopRight, new File(target, "inner_top_right"));
        copyCategory(set.groundInnerBottomLeft, new File(target, "inner_bottom_left"));
        copyCategory(set.groundInnerBottomRight, new File(target, "inner_bottom_right"));
        copyCategory(set.groundPlatformCenter, new File(target, "platform_center"));
        copyCategory(set.groundPlatformLeft, new File(target, "platform_left"));
        copyCategory(set.groundPlatformRight, new File(target, "platform_right"));
        copyCategory(set.groundPlatformSingle, new File(target, "platform_single"));
        copyCategory(set.groundSlopeUp, new File(target, "slope_up"));
        copyCategory(set.groundSlopeDown, new File(target, "slope_down"));
        copyCategory(set.groundSteepSlopeUp, new File(target, "slope_steep_up"));
        copyCategory(set.groundSteepSlopeDown, new File(target, "slope_steep_down"));
        copyCategory(set.groundSlopeUpSupport, new File(target, "slope_up_support"));
        copyCategory(set.groundSlopeDownSupport, new File(target, "slope_down_support"));
        copyCategory(set.groundSteepSlopeUpSupport,
                new File(target, "slope_steep_up_support"));
        copyCategory(set.groundSteepSlopeDownSupport,
                new File(target, "slope_steep_down_support"));
        copyCategory(set.groundSlopeUpEndpointSupport,
                new File(target, "slope_up_endpoint_support"));
        copyCategory(set.groundSlopeDownEndpointSupport,
                new File(target, "slope_down_endpoint_support"));
        copyCategory(set.groundSteepSlopeUpEndpointSupport,
                new File(target, "slope_steep_up_endpoint_support"));
        copyCategory(set.groundSteepSlopeDownEndpointSupport,
                new File(target, "slope_steep_down_endpoint_support"));
        copyCategory(iceBaseFiles(set, set.groundSurface),
                new File(target, "ice_surface"));
        copyCategory(iceBaseFiles(set, set.groundTopLeft),
                new File(target, "ice_top_left"));
        copyCategory(iceBaseFiles(set, set.groundTopRight),
                new File(target, "ice_top_right"));
        copyCategory(iceBaseFiles(set, set.groundPlatformCenter),
                new File(target, "ice_platform_center"));
        copyCategory(iceBaseFiles(set, set.groundPlatformLeft),
                new File(target, "ice_platform_left"));
        copyCategory(iceBaseFiles(set, set.groundPlatformRight),
                new File(target, "ice_platform_right"));
        copyCategory(iceBaseFiles(set, set.groundPlatformSingle),
                new File(target, "ice_platform_single"));
        copyCategory(iceBaseFiles(set, set.groundSlopeUp),
                new File(target, "ice_slope_up"));
        copyCategory(iceBaseFiles(set, set.groundSlopeDown),
                new File(target, "ice_slope_down"));
        copyFloatingIslandSpans(set, new File(target, "floating_span"));
        copyCategory(set.water, new File(target, "liquid"));
        copyCategory(set.waterSurface, new File(target, "liquid_surface"));
        copyCategory(set.waterFill, new File(target, "liquid_fill"));
        for (Map.Entry<String, List<File>> entry : set.vfxAssets.entrySet())
            if (entry.getValue() != null)
                copyCategory(entry.getValue(), new File(target,
                        "vfx/" + safeName(entry.getKey())));
    }

    private static void embedSelectedPaletteAssets(File mapRoot,
                                                   CustomMapDocument doc)
            throws IOException {
        Set<String> ids = new HashSet<String>();
        collectSelectedAssetIds(doc == null ? null : doc.battleTerrain, ids);
        if (doc != null && doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                collectSelectedAssetIds(variant, ids);
        for (String id : ids) {
            int slash = id.indexOf('/');
            if (slash <= 0 || slash + 1 >= id.length()) continue;
            String theme = id.substring(0, slash);
            TileCatalog.TileSet set = TileCatalog.find(theme);
            if (set == null || set.root == null) continue;
            File source = new File(set.root, id.substring(slash + 1)
                    .replace('/', File.separatorChar)).getCanonicalFile();
            File root = set.root.getCanonicalFile();
            if (!source.toPath().startsWith(root.toPath()) || !source.isFile()) continue;
            File target = new File(mapRoot, "assets/palette/" + safeName(theme)
                    + "/selected/" + id.substring(slash + 1)
                    .replace('/', File.separatorChar));
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new IOException("Could not create " + parent);
            Files.copy(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void collectSelectedAssetIds(ModeVariant variant,
                                                Set<String> out) {
        if (variant == null) return;
        if (variant.manualTiles != null)
            for (CustomMapDocument.ManualTile tile : variant.manualTiles)
                if (tile != null && tile.preferredAsset != null)
                    out.add(tile.preferredAsset);
        if (variant.manualTiles != null)
            for (CustomMapDocument.ManualTile tile : variant.manualTiles)
                if (tile != null && tile.materialAsset != null)
                    out.add(tile.materialAsset);
        if (variant.manualDecorations != null)
            for (CustomMapDocument.ManualDecoration decoration
                    : variant.manualDecorations)
                if (decoration != null && decoration.asset != null)
                    out.add(decoration.asset);
        if (variant.manualBackground != null)
            for (CustomMapDocument.BackgroundLayer layer : variant.manualBackground)
                if (layer != null && layer.asset != null) out.add(layer.asset);
        if (variant.manualEffects != null)
            for (CustomMapDocument.ManualEffect effect : variant.manualEffects)
                if (effect != null && effect.asset != null) out.add(effect.asset);
    }

    private static String safeName(String value) {
        if (value == null || value.trim().isEmpty()) return "default";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static void writeFromEmbedded(File mapRoot, File oldRoot,
                                  CustomMapDocument oldDoc,
                                  CustomMapDocument doc) throws IOException {
        if (mapRoot == null || oldRoot == null || oldDoc == null || doc == null
                || !oldRoot.isDirectory())
            throw new IOException("The saved map payload is unavailable for patrol editing.");
        copyTree(oldRoot, mapRoot);
        patchEmbeddedVariant(mapRoot, "battle", oldDoc.battleTerrain,
                doc.battleTerrain);
        if (doc.variants != null)
            for (ModeVariant variant : doc.variants.values()) {
                ModeVariant old = oldDoc.variants == null ? null
                        : oldDoc.variants.get(variant.mode);
                patchEmbeddedVariant(mapRoot, variant.mode, old, variant);
            }
    }

    static void assignFloatingIslandCollisionProfiles(
            CustomMapDocument doc, TileCatalog.TileSet tiles) throws IOException {
        if (doc == null || tiles == null) return;
        Map<Integer, IslandCollisionProfile> profiles =
                new java.util.LinkedHashMap<Integer, IslandCollisionProfile>();
        if (tiles.alphaTopFloatingIslandCollision)
            for (Map.Entry<Integer, File> entry : tiles.floatingIslandSpans.entrySet()) {
                BufferedImage image = ImageIO.read(entry.getValue());
                if (image != null) profiles.put(entry.getKey(), collisionProfile(image));
            }
        applyFloatingIslandCollision(doc.battleTerrain, profiles);
        if (doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                applyFloatingIslandCollision(variant, profiles);
    }

    private static void applyFloatingIslandCollision(
            ModeVariant variant, Map<Integer, IslandCollisionProfile> profiles) {
        if (variant == null || variant.secondaryPlatforms == null) return;
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null) continue;
            IslandCollisionProfile profile = profiles.get(platform.widthTiles());
            if (profile == null) {
                platform.collisionMode = "legacy";
                platform.collisionLeftInsetPermille = 0;
                platform.collisionRightInsetPermille = 0;
                platform.collisionTopOffsetPermille = 0;
                platform.collisionBottomInsetPermille = 0;
                continue;
            }
            platform.collisionMode = "alpha-top-surface-v1";
            platform.collisionLeftInsetPermille = profile.leftInsetPermille;
            platform.collisionRightInsetPermille = profile.rightInsetPermille;
            platform.collisionTopOffsetPermille = profile.topOffsetPermille;
            platform.collisionBottomInsetPermille = profile.bottomInsetPermille;
        }
    }

    private static IslandCollisionProfile collisionProfile(BufferedImage image) {
        int left = image.getWidth(), top = image.getHeight(), right = -1, bottom = -1;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if (((image.getRGB(x, y) >>> 24) & 255) >= 24) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
        if (right < left) return new IslandCollisionProfile(0, 0, 0, 0);
        int leftInset = Math.round(left * 1000f / Math.max(1, image.getWidth()));
        int rightInset = Math.round((image.getWidth() - 1 - right) * 1000f
                / Math.max(1, image.getWidth()));
        int topOffset = Math.round(top * 1000f / Math.max(1, image.getHeight()));
        int bottomInset = Math.round((image.getHeight() - 1 - bottom) * 1000f
                / Math.max(1, image.getHeight()));
        return new IslandCollisionProfile(leftInset, rightInset,
                topOffset, bottomInset);
    }

    private static final class IslandCollisionProfile {
        final int leftInsetPermille;
        final int rightInsetPermille;
        final int topOffsetPermille;
        final int bottomInsetPermille;

        IslandCollisionProfile(int leftInsetPermille, int rightInsetPermille,
                               int topOffsetPermille, int bottomInsetPermille) {
            this.leftInsetPermille = leftInsetPermille;
            this.rightInsetPermille = rightInsetPermille;
            this.topOffsetPermille = topOffsetPermille;
            this.bottomInsetPermille = bottomInsetPermille;
        }
    }

    private static void patchEmbeddedVariant(File mapRoot, String id,
                                             ModeVariant oldVariant,
                                             ModeVariant variant) throws IOException {
        if (oldVariant == null || variant == null)
            throw new IOException("Saved terrain variant " + id + " is missing.");
        File variantRoot = new File(mapRoot, id);
        File underRoot = new File(variantRoot, "chunks/under");
        int tilePx = embeddedTilePixels(underRoot);

        if (oldVariant.secondaryPlatforms != null)
            for (SecondaryPlatform old : oldVariant.secondaryPlatforms) {
                if (old == null || !old.isPatrolling()) continue;
                SecondaryPlatform current = variant.secondaryPlatform(old.id);
                if (current != null && current.isPatrolling()) continue;
                File sprite = new File(new File(variantRoot, "platforms"),
                        safePlatformId(old, oldVariant) + "/under.png");
                BufferedImage image = sprite.isFile() ? ImageIO.read(sprite) : null;
                if (image == null || isEmpty(image))
                    throw new IOException("Saved moving-platform sprite is missing: "
                            + old.id);
                SecondaryPlatform destination = current == null ? old : current;
                pastePlatformIntoChunks(underRoot, destination, variant,
                        tilePx, image);
            }

        if (variant.secondaryPlatforms != null)
            for (SecondaryPlatform platform : variant.secondaryPlatforms) {
                if (platform == null || !platform.isPatrolling()) continue;
                File directory = new File(new File(variantRoot, "platforms"),
                        safePlatformId(platform, variant));
                File underFile = new File(directory, "under.png");
                BufferedImage under = underFile.isFile() ? ImageIO.read(underFile) : null;
                if (under == null || isEmpty(under)) {
                    under = extractPlatformFromChunks(underRoot, platform,
                            variant, tilePx);
                    if (under == null || isEmpty(under))
                        throw new IOException("Could not recover embedded terrain for moving platform "
                                + platform.id + ".");
                    if (!directory.exists() && !directory.mkdirs())
                        throw new IOException("Could not create " + directory);
                    ImageIO.write(under, "png", underFile);
                    ImageIO.write(new BufferedImage(under.getWidth(), under.getHeight(),
                            BufferedImage.TYPE_INT_ARGB), "png",
                            new File(directory, "over.png"));
                }
                clearPlatformFromChunks(underRoot, platform, variant, tilePx);
            }

        File treeRoot = new File(variantRoot, "chunks/trees");
        clearDirectory(treeRoot);
        BakedAssets embedded = new BakedAssets();
        embedded.trees = readEmbedded(new File(mapRoot, "assets/tree"));
        if (variant.trees != null && !variant.trees.isEmpty()
                && embedded.trees.isEmpty())
            throw new IOException("Embedded tree assets are missing.");
        bakeTreeLayer(treeRoot, variant, tilePx, embedded);
    }

    private static int embeddedTilePixels(File underRoot) throws IOException {
        File[] files = underRoot == null ? null : underRoot.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".png")) continue;
                BufferedImage image = ImageIO.read(file);
                if (image != null && image.getWidth() >= CustomMapDocument.CHUNK_TILES)
                    return Math.max(1, image.getWidth()
                            / CustomMapDocument.CHUNK_TILES);
            }
        }
        throw new IOException("Saved terrain chunks are missing.");
    }

    private static BufferedImage extractPlatformFromChunks(
            File underRoot, SecondaryPlatform platform, ModeVariant variant,
            int tilePx) throws IOException {
        int row = platformRow(variant, platform);
        BufferedImage out = new BufferedImage(platform.widthTiles() * tilePx,
                tilePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            for (int x = platform.startX; x <= platform.endX; x++) {
                File chunk = chunkFile(underRoot, x, row);
                BufferedImage image = chunk.isFile() ? ImageIO.read(chunk) : null;
                if (image == null) continue;
                int localX = Math.floorMod(x, CustomMapDocument.CHUNK_TILES) * tilePx;
                int localY = Math.floorMod(row, CustomMapDocument.CHUNK_TILES) * tilePx;
                g.drawImage(image, (x - platform.startX) * tilePx, 0,
                        (x - platform.startX + 1) * tilePx, tilePx,
                        localX, localY, localX + tilePx, localY + tilePx, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void pastePlatformIntoChunks(File underRoot,
                                                SecondaryPlatform platform,
                                                ModeVariant variant, int tilePx,
                                                BufferedImage sprite) throws IOException {
        int row = platformRow(variant, platform);
        for (int x = platform.startX; x <= platform.endX; x++) {
            File chunk = chunkFile(underRoot, x, row);
            BufferedImage image = chunk.isFile() ? ImageIO.read(chunk) : null;
            int chunkPx = CustomMapDocument.CHUNK_TILES * tilePx;
            if (image == null) image = new BufferedImage(chunkPx, chunkPx,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                int localX = Math.floorMod(x, CustomMapDocument.CHUNK_TILES) * tilePx;
                int localY = Math.floorMod(row, CustomMapDocument.CHUNK_TILES) * tilePx;
                int sourceX = (x - platform.startX) * tilePx;
                g.drawImage(sprite, localX, localY, localX + tilePx,
                        localY + tilePx, sourceX, 0, sourceX + tilePx,
                        tilePx, null);
            } finally {
                g.dispose();
            }
            if (!chunk.getParentFile().exists() && !chunk.getParentFile().mkdirs())
                throw new IOException("Could not create " + chunk.getParentFile());
            ImageIO.write(image, "png", chunk);
        }
    }

    private static void clearPlatformFromChunks(File underRoot,
                                                SecondaryPlatform platform,
                                                ModeVariant variant,
                                                int tilePx) throws IOException {
        int row = platformRow(variant, platform);
        for (int x = platform.startX; x <= platform.endX; x++) {
            File chunk = chunkFile(underRoot, x, row);
            BufferedImage image = chunk.isFile() ? ImageIO.read(chunk) : null;
            if (image == null) continue;
            Graphics2D g = image.createGraphics();
            try {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(Math.floorMod(x, CustomMapDocument.CHUNK_TILES) * tilePx,
                        Math.floorMod(row, CustomMapDocument.CHUNK_TILES) * tilePx,
                        tilePx, tilePx);
            } finally {
                g.dispose();
            }
            if (isEmpty(image)) Files.deleteIfExists(chunk.toPath());
            else ImageIO.write(image, "png", chunk);
        }
    }

    private static File chunkFile(File root, int tileX, int tileY) {
        int cx = Math.floorDiv(tileX, CustomMapDocument.CHUNK_TILES);
        int cy = Math.floorDiv(tileY, CustomMapDocument.CHUNK_TILES);
        return new File(root, cx + "_" + cy + ".png");
    }

    private static List<BufferedImage> readEmbedded(File directory) throws IOException {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".png")) continue;
            BufferedImage image = ImageIO.read(file);
            if (image == null) throw new IOException("Unreadable embedded PNG: " + file);
            out.add(image);
        }
        return out;
    }

    private static void copyTree(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs())
                throw new IOException("Could not create " + target);
            File[] children = source.listFiles();
            if (children != null) for (File child : children)
                copyTree(child, new File(target, child.getName()));
        } else {
            Files.copy(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void clearDirectory(File directory) throws IOException {
        if (directory == null || !directory.exists()) return;
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) clearDirectory(child);
            Files.deleteIfExists(child.toPath());
        }
    }

    private static void bakeVariant(File mapRoot, String id, ModeVariant variant,
                                    int tilePx, BakedAssets images) throws IOException {
        File variantRoot = new File(mapRoot, id);
        bakeLayer(new File(variantRoot, "chunks/under"), variant, tilePx, images, false);
        bakeTreeLayer(new File(variantRoot, "chunks/trees"), variant, tilePx, images);
        bakeLayer(new File(variantRoot, "chunks/over"), variant, tilePx, images, true);
        bakePatrolPlatforms(new File(variantRoot, "platforms"), variant, tilePx, images);
    }

    private static void copyCategory(List<File> sources, File target) throws IOException {
        if (sources.isEmpty()) return;
        if (!target.exists() && !target.mkdirs()) throw new IOException("Could not create " + target);
        for (int i = 0; i < sources.size(); i++) {
            Files.copy(sources.get(i).toPath(), new File(target, String.format("%03d.png", i)).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<File> iceBaseFiles(TileCatalog.TileSet set,
                                           List<File> topologySources) {
        ArrayList<File> out = new ArrayList<File>();
        if (set == null || topologySources == null) return out;
        for (File source : topologySources) {
            String key = CustomMapDocument.IceSurfaceManifest.tileKey(
                    source == null ? "" : source.getName());
            TileCatalog.IceSurfaceAsset asset = set.iceSurfaceAssets.get(key);
            if (asset != null && asset.base != null && asset.base.isFile())
                out.add(asset.base);
        }
        return out;
    }

    private static void copyFloatingIslandSpans(TileCatalog.TileSet set,
                                                File target) throws IOException {
        if (set == null || set.floatingIslandSpans.isEmpty()) return;
        if (!target.exists() && !target.mkdirs())
            throw new IOException("Could not create " + target);
        for (Map.Entry<Integer, File> entry : set.floatingIslandSpans.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            Files.copy(entry.getValue().toPath(), new File(target,
                    String.format("%03d.png", entry.getKey())).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeInnerCornerJunctions(File target,
                                                  TileCatalog.TileSet tiles)
            throws IOException {
        if (tiles == null || tiles.innerCornerJunctions.isEmpty()) return;
        if (!target.exists() && !target.mkdirs())
            throw new IOException("Could not create " + target);
        for (Map.Entry<String, File> entry : tiles.innerCornerJunctions.entrySet()) {
            String name = entry.getKey().replace(':', '-') + ".png";
            Files.copy(entry.getValue().toPath(), new File(target, name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeProps(File target, CustomMapDocument doc,
                                   TileCatalog.TileSet tiles) throws IOException {
        if (doc == null || tiles == null) return;
        if (doc.propManifest == null || doc.propManifest.assets == null
                || doc.propManifest.assets.isEmpty()) return;
        if (!target.exists() && !target.mkdirs())
            throw new IOException("Could not create " + target);
        if (doc.propManifest.assets.size() != tiles.props.size())
            throw new IOException("Decorative prop manifest is incomplete.");
        for (int i = 0; i < tiles.props.size(); i++) {
            TileCatalog.PropAsset source = tiles.props.get(i);
            CustomMapDocument.PropAssetRef ref = doc.propManifest.assets.get(i);
            if (source == null || ref == null
                    || !source.id.equals(ref.id)
                    || !source.sourceKey.equals(ref.sourceKey))
                throw new IOException("Decorative prop manifest order is unstable at index " + i + ".");
            File output = new File(target, String.format("%03d.png", i));
            Files.copy(source.file.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ref.asset = "assets/props/" + output.getName();
        }
    }

    private static void writeVfx(File target, CustomMapDocument doc,
                                 TileCatalog.TileSet tiles) throws IOException {
        if (doc == null || doc.themeProfile == null || doc.themeProfile.vfx == null
                || doc.themeProfile.vfx.profileId == null
                || doc.themeProfile.vfx.profileId.trim().isEmpty()) return;
        doc.themeProfile.vfx.assets.clear();
        String[] kinds = {"dust", "splash", "land", "edge", "ambient"};
        for (String kind : kinds) {
            List<File> sources = tiles.vfxAssets.get(kind);
            if (sources == null || sources.isEmpty()) continue;
            File directory = new File(target, kind);
            if (!directory.exists() && !directory.mkdirs())
                throw new IOException("Could not create " + directory);
            ArrayList<String> embedded = new ArrayList<String>();
            for (int i = 0; i < sources.size(); i++) {
                File output = new File(directory, String.format("%03d.png", i));
                Files.copy(sources.get(i).toPath(), output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                embedded.add("assets/vfx/" + kind + "/" + output.getName());
            }
            doc.themeProfile.vfx.assets.put(kind, embedded);
        }
    }

    private static void writeIceSurfaceAssets(File assets, CustomMapDocument doc,
                                              TileCatalog.TileSet tiles)
            throws IOException {
        if (doc == null) return;
        CustomMapDocument.IceSurfaceManifest manifest =
                new CustomMapDocument.IceSurfaceManifest();
        doc.iceSurfaceManifest = manifest;
        if (tiles == null || !tiles.supportsBreakableIceAssets()) return;
        if (!tiles.supportsBreakableIceAssets())
            throw new IOException("The selected ice palette is missing a complete base/crack-01/"
                    + "crack-02/crack-03 surface set or the 8 ice-break VFX frames.");

        ArrayList<String> keys = new ArrayList<String>(tiles.iceSurfaceAssets.keySet());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        File surfaceRoot = new File(assets, "ice_surface");
        String[] states = {"base", "crack-01", "crack-02", "crack-03"};
        for (String state : states) {
            File directory = new File(surfaceRoot, state);
            if (!directory.exists() && !directory.mkdirs())
                throw new IOException("Could not create " + directory);
        }
        int index = 0;
        for (String key : keys) {
            TileCatalog.IceSurfaceAsset source = tiles.iceSurfaceAssets.get(key);
            if (source == null || !source.isComplete()) continue;
            String outputName = String.format("%03d.png", index++);
            CustomMapDocument.IceSurfaceAssetRef ref =
                    new CustomMapDocument.IceSurfaceAssetRef();
            ref.sourceKey = CustomMapDocument.IceSurfaceManifest.tileKey(source.sourceKey);
            ref.width = source.width;
            ref.height = source.height;
            ref.base = copyIceAsset(source.base, surfaceRoot, "base", outputName);
            ref.crack1 = copyIceAsset(source.crack1, surfaceRoot, "crack-01", outputName);
            ref.crack2 = copyIceAsset(source.crack2, surfaceRoot, "crack-02", outputName);
            ref.crack3 = copyIceAsset(source.crack3, surfaceRoot, "crack-03", outputName);
            manifest.tiles.put(ref.sourceKey, ref);
        }

        List<File> breakFrames = tiles.vfxAssets.get("ice-break");
        File breakRoot = new File(new File(assets, "vfx"), "ice-break");
        if (!breakRoot.exists() && !breakRoot.mkdirs())
            throw new IOException("Could not create " + breakRoot);
        for (int i = 0; i < breakFrames.size(); i++) {
            File output = new File(breakRoot, String.format("%03d.png", i));
            Files.copy(breakFrames.get(i).toPath(), output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            manifest.breakFrames.add("assets/vfx/ice-break/" + output.getName());
        }
        if (!manifest.isReady())
            throw new IOException("The embedded breakable-ice asset manifest is incomplete.");
    }

    private static String copyIceAsset(File source, File root, String state,
                                       String outputName) throws IOException {
        File output = new File(new File(root, state), outputName);
        Files.copy(source.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return "assets/ice_surface/" + state + "/" + outputName;
    }

    private static void assignIceSurfaceKeys(CustomMapDocument doc,
                                             TileCatalog.TileSet tiles)
            throws IOException {
        if (doc == null) return;
        assignIceSurfaceKeys(doc.battleTerrain, doc.iceSurfaceManifest, tiles);
        if (doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                assignIceSurfaceKeys(variant, doc.iceSurfaceManifest, tiles);
    }

    private static void assignIceSurfaceKeys(
            ModeVariant variant, CustomMapDocument.IceSurfaceManifest manifest,
            TileCatalog.TileSet tiles) throws IOException {
        if (variant == null) return;
        variant.iceSurfaceKeys = new String[Math.max(0, variant.width)];
        if (variant.secondaryPlatforms != null)
            for (SecondaryPlatform platform : variant.secondaryPlatforms)
                if (platform != null)
                    platform.iceSurfaceKeys = new String[platform.widthTiles()];
        if (tiles == null || manifest == null || !manifest.isReady()) return;

        for (int x = 0; x < variant.width; x++) {
            if (variant.surface == null || x >= variant.surface.length
                    || variant.surface[x] < 0
                    || variant.surfaceMaterialAt(variant.worldX(x))
                    != CustomMapDocument.SURFACE_ICE) continue;
            variant.iceSurfaceKeys[x] = topologyKey(
                    variant, tiles, manifest, x, variant.surface[x]);
        }
        if (variant.secondaryPlatforms == null) return;
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null
                    || platform.surfaceMaterial != CustomMapDocument.SURFACE_ICE)
                continue;
            int row = platformRow(variant, platform);
            for (int local = 0; local < platform.widthTiles(); local++) {
                platform.iceSurfaceKeys[local] = topologyKeyForRole(
                        variant, tiles, manifest,
                        IceSurfaceTopologyResolver.platformRole(platform, local),
                        platform.startX + local);
            }
        }
    }

    private static String topologyKey(
            ModeVariant variant, TileCatalog.TileSet tiles,
            CustomMapDocument.IceSurfaceManifest manifest, int x, int y)
            throws IOException {
        return topologyKeyForRole(variant, tiles, manifest,
                IceSurfaceTopologyResolver.mainRole(
                        variant, x, y, tiles.embeddedBankIceBridge), x);
    }

    private static String topologyKeyForRole(
            ModeVariant variant, TileCatalog.TileSet tiles,
            CustomMapDocument.IceSurfaceManifest manifest,
            IceSurfaceTopologyResolver.Role role, int x) throws IOException {
        List<File> source = iceSourcesForRole(tiles, role);
        if (source.isEmpty())
            throw new IOException("No authored surface topology for breakable ice at "
                    + x + " (" + role + ").");
        if (role == IceSurfaceTopologyResolver.Role.SLOPE_UP
                || role == IceSurfaceTopologyResolver.Role.SLOPE_DOWN) {
            int phase = variant.slopePhase == null || x < 0
                    || x >= variant.slopePhase.length ? 0 : variant.slopePhase[x];
            int index = Math.max(0, Math.min(source.size() - 1,
                    (Math.max(1, phase) - 1) * source.size() / 100));
            return requireIceTopology(manifest, source.get(index));
        }
        long value = variant.seed ^ (variant.seed >>> 33);
        return requireIceTopology(manifest,
                source.get((int) Math.floorMod(value, source.size())));
    }

    private static List<File> iceSourcesForRole(
            TileCatalog.TileSet tiles, IceSurfaceTopologyResolver.Role role) {
        if (tiles == null || role == null) return Collections.emptyList();
        switch (role) {
            case BRIDGE:
            case MAIN_CENTER:
                return firstNonEmpty(tiles.groundSurface, tiles.ground);
            case MAIN_LEFT:
                return firstNonEmpty(tiles.groundTopLeft,
                        tiles.groundSurface, tiles.ground);
            case MAIN_RIGHT:
                return firstNonEmpty(tiles.groundTopRight,
                        tiles.groundSurface, tiles.ground);
            case PLATFORM_SINGLE:
                return firstNonEmpty(tiles.groundPlatformSingle,
                        tiles.groundPlatformCenter, tiles.groundSurface, tiles.ground);
            case PLATFORM_LEFT:
                return firstNonEmpty(tiles.groundPlatformLeft,
                        tiles.groundPlatformCenter, tiles.groundSurface, tiles.ground);
            case PLATFORM_RIGHT:
                return firstNonEmpty(tiles.groundPlatformRight,
                        tiles.groundPlatformCenter, tiles.groundSurface, tiles.ground);
            case PLATFORM_CENTER:
                return firstNonEmpty(tiles.groundPlatformCenter,
                        tiles.groundSurface, tiles.ground);
            case SLOPE_UP:
                return tiles.groundSlopeUp;
            case SLOPE_DOWN:
                return tiles.groundSlopeDown;
            case STEP_JUNCTION_LEFT:
                return firstNonEmpty(iceSourceByKey(tiles,
                                IceSurfaceTopologyResolver.STEP_JUNCTION_LEFT_KEY),
                        tiles.groundSurface, tiles.ground);
            case STEP_JUNCTION_RIGHT:
                return firstNonEmpty(iceSourceByKey(tiles,
                                IceSurfaceTopologyResolver.STEP_JUNCTION_RIGHT_KEY),
                        tiles.groundSurface, tiles.ground);
            default:
                return Collections.emptyList();
        }
    }

    private static List<File> iceSourceByKey(
            TileCatalog.TileSet tiles, String sourceKey) {
        if (tiles == null || sourceKey == null) return Collections.emptyList();
        TileCatalog.IceSurfaceAsset asset = tiles.iceSurfaceAssets.get(
                CustomMapDocument.IceSurfaceManifest.tileKey(sourceKey));
        if (asset == null || !asset.isComplete())
            return Collections.emptyList();
        return Collections.singletonList(asset.base);
    }

    @SafeVarargs
    private static <T> List<T> firstNonEmpty(List<T>... choices) {
        for (List<T> choice : choices)
            if (choice != null && !choice.isEmpty()) return choice;
        return Collections.emptyList();
    }

    private static String requireIceTopology(
            CustomMapDocument.IceSurfaceManifest manifest, File source)
            throws IOException {
        String key = CustomMapDocument.IceSurfaceManifest.tileKey(
                source == null ? "" : source.getName());
        if (manifest.find(key) == null)
            throw new IOException("Ice crack overlay is missing for terrain topology '"
                    + key + "'.");
        return key;
    }

    private static List<BufferedImage> readAll(List<File> files) throws IOException {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        for (File file : files) {
            BufferedImage image = ImageIO.read(file);
            if (image == null) throw new IOException("Unreadable PNG: " + file);
            out.add(image);
        }
        return out;
    }

    private static List<BufferedImage> readIceBase(
            TileCatalog.TileSet tiles, List<File> topologySources)
            throws IOException {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        if (tiles == null || topologySources == null) return out;
        for (File source : topologySources) {
            String key = CustomMapDocument.IceSurfaceManifest.tileKey(
                    source == null ? "" : source.getName());
            TileCatalog.IceSurfaceAsset asset = tiles.iceSurfaceAssets.get(key);
            if (asset == null || asset.base == null) continue;
            BufferedImage image = ImageIO.read(asset.base);
            if (image == null)
                throw new IOException("Unreadable Ice surface PNG: " + asset.base);
            out.add(image);
        }
        return out;
    }

    private static List<BufferedImage> readIceBaseByKey(
            TileCatalog.TileSet tiles, String sourceKey) throws IOException {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        for (File source : iceSourceByKey(tiles, sourceKey)) {
            BufferedImage image = ImageIO.read(source);
            if (image == null) throw new IOException("Unreadable PNG: " + source);
            out.add(image);
        }
        return out;
    }

    private static void bakeLayer(File root, ModeVariant v, int tilePx,
                                  BakedAssets images, boolean overlay) throws IOException {
        if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create " + root);
        int chunkTiles = CustomMapDocument.CHUNK_TILES;
        int chunkPx = chunkTiles * tilePx;
        int chunksX = (v.width + chunkTiles - 1) / chunkTiles;
        int chunksY = (v.height + chunkTiles - 1) / chunkTiles;
        for (int cy = 0; cy < chunksY; cy++) {
            for (int cx = 0; cx < chunksX; cx++) {
                BufferedImage image = new BufferedImage(chunkPx, chunkPx, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try {
                    g.setComposite(AlphaComposite.SrcOver);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    if (!overlay) {
                        TerrainTileRenderer.draw(g, v, images, tilePx,
                                -cx * chunkTiles * tilePx, -cy * chunkTiles * tilePx);
                    }
                } finally {
                    g.dispose();
                }
                maskPatrolPlatforms(image, v, tilePx,
                        -cx * chunkTiles * tilePx, -cy * chunkTiles * tilePx);
                if (!isEmpty(image)) ImageIO.write(image, "png", new File(root, cx + "_" + cy + ".png"));
            }
        }
    }

    static void maskPatrolPlatforms(BufferedImage image, ModeVariant variant,
                                    float tilePx, float offsetX, float offsetY) {
        if (image == null || variant == null || variant.secondaryPlatforms == null) return;
        Graphics2D clear = image.createGraphics();
        try {
            clear.setComposite(AlphaComposite.Clear);
            for (SecondaryPlatform platform : variant.secondaryPlatforms) {
                if (platform == null || !platform.isPatrolling()) continue;
                int row = platformRow(variant, platform);
                int left = Math.round(offsetX + platform.startX * tilePx);
                int top = Math.round(offsetY + row * tilePx);
                int right = Math.round(offsetX + (platform.endX + 1) * tilePx);
                int bottom = Math.round(offsetY + (row + 1) * tilePx);
                clear.fillRect(left, top, Math.max(1, right - left),
                        Math.max(1, bottom - top));
            }
        } finally {
            clear.dispose();
        }
    }

    static BufferedImage renderPatrolPlatform(ModeVariant variant,
                                              SecondaryPlatform platform,
                                              int tilePx,
                                              TerrainTileRenderer.Assets images) {
        if (variant == null || platform == null || images == null
                || platform.widthTiles() <= 0) return null;
        int row = platformRow(variant, platform);
        BufferedImage out = new BufferedImage(platform.widthTiles() * tilePx,
                tilePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            TerrainTileRenderer.draw(g, variant, images, tilePx,
                    -platform.startX * tilePx, -row * tilePx);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void bakePatrolPlatforms(File root, ModeVariant variant,
                                            int tilePx, BakedAssets images) throws IOException {
        if (variant == null || variant.secondaryPlatforms == null) return;
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || !platform.isPatrolling()) continue;
            BufferedImage under = renderPatrolPlatform(variant, platform, tilePx, images);
            if (under == null || isEmpty(under))
                throw new IOException("Moving platform " + platform.id
                        + " has no renderable terrain pixels.");
            File directory = new File(root, safePlatformId(platform, variant));
            if (!directory.exists() && !directory.mkdirs())
                throw new IOException("Could not create " + directory);
            ImageIO.write(under, "png", new File(directory, "under.png"));

            BufferedImage over = new BufferedImage(under.getWidth(), under.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(over, "png", new File(directory, "over.png"));
        }
    }

    static String safePlatformId(SecondaryPlatform platform, ModeVariant variant) {
        String raw = platform == null ? "" : platform.id;
        if (raw == null || raw.trim().isEmpty()) {
            int row = platform == null ? 0 : platformRow(variant, platform);
            raw = "island-" + (platform == null ? 0 : platform.startX) + "-"
                    + (platform == null ? 0 : platform.endX) + "-" + row;
        }
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "island" : safe;
    }

    private static int platformRow(ModeVariant variant, SecondaryPlatform platform) {
        if (variant == null || platform == null) return 0;
        return Math.max(0, Math.min(variant.height - 1, Math.round(
                variant.height + platform.supportLayer
                        / Math.max(1f, variant.layerUnitsPerTile()))));
    }

    private static void bakeTreeLayer(File root, ModeVariant v, int tilePx,
                                      BakedAssets images) throws IOException {
        if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create " + root);
        int chunkTiles = CustomMapDocument.CHUNK_TILES;
        int chunkPx = chunkTiles * tilePx;
        int chunksX = (v.width + chunkTiles - 1) / chunkTiles;
        int chunksY = (v.height + chunkTiles - 1) / chunkTiles;
        for (int cy = 0; cy < chunksY; cy++) for (int cx = 0; cx < chunksX; cx++) {
            BufferedImage image = new BufferedImage(chunkPx, chunkPx, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setComposite(AlphaComposite.SrcOver);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                drawTrees(g, v, images.trees, cx, cy, chunkTiles, tilePx, chunkPx);
            } finally {
                g.dispose();
            }
            if (!isEmpty(image)) ImageIO.write(image, "png", new File(root, cx + "_" + cy + ".png"));
        }
    }

    private static void drawTrees(Graphics2D g, ModeVariant v, List<BufferedImage> trees,
                                  int cx, int cy, int chunkTiles, int tilePx, int chunkPx) {
        if (trees.isEmpty()) return;
        int chunkX = cx * chunkTiles * tilePx;
        int chunkY = cy * chunkTiles * tilePx;
        for (TreePlacement placement : v.trees) {
            if (treeOnPatrolPlatform(v, placement)) continue;
            BufferedImage tree = trees.get(Math.floorMod(placement.asset, trees.size()));
            float scale = CustomMapPreviewPanel.treeAssetScale(tilePx,
                    tree.getWidth(), tree.getHeight(), placement.scalePercent);
            scale *= CustomMapPreviewPanel.treeFitScale(v, placement, tilePx,
                    tree.getWidth() * scale);
            int width = Math.max(1, Math.round(tree.getWidth() * scale));
            int height = Math.max(1, Math.round(tree.getHeight() * scale));
            placement.widthTiles = Math.max(1, (width + tilePx - 1) / tilePx);
            placement.heightTiles = Math.max(1, (height + tilePx - 1) / tilePx);
            int worldCenterX = Math.round(CustomMapPreviewPanel.treeCenterX(
                    v, placement, tilePx, width));
            int worldBottom = Math.round((placement.y
                    + CustomMapPreviewPanel.treeRootContactRatio(v)) * tilePx);
            int opaqueBottom = Math.max(1, Math.round(alphaContentBottom(tree) * scale));
            int dx = worldCenterX - width / 2 - chunkX;
            int dy = worldBottom - opaqueBottom - chunkY;
            if (dx + width <= 0 || dy + height <= 0 || dx >= chunkPx || dy >= chunkPx) continue;
            g.drawImage(tree, dx, dy, width, height, null);
        }
    }

    private static boolean treeOnPatrolPlatform(ModeVariant variant, TreePlacement tree) {
        if (variant == null || tree == null || variant.secondaryPlatforms == null) return false;
        for (SecondaryPlatform platform : variant.secondaryPlatforms)
            if (platform != null && platform.isPatrolling()
                    && tree.x >= platform.startX && tree.x <= platform.endX
                    && tree.y == platformRow(variant, platform)) return true;
        return false;
    }

    private static int alphaContentBottom(BufferedImage image) {
        for (int y = image.getHeight() - 1; y >= 0; y--)
            for (int x = 0; x < image.getWidth(); x++)
                if (((image.getRGB(x, y) >>> 24) & 0xff) >= 16) return y + 1;
        return image.getHeight();
    }

    private static void writeBackgrounds(File target, CustomMapDocument doc,
                                         TileCatalog.TileSet tiles) throws IOException {
        if (!target.exists() && !target.mkdirs()) throw new IOException("Could not create " + target);
        if (doc.backgroundRevision != CustomMapDocument.BACKGROUND_REVISION
                || doc.backgroundManifest == null || doc.backgroundManifest.assets == null)
            throw new IOException("Background composition is legacy. Regenerate the map.");
        doc.background.clear();
        File fallback = new File(target, "000.png");
        if (doc.themeProfile != null && doc.themeProfile.sky != null
                && doc.themeProfile.sky.hasOverride())
            writeThemeSkyGradient(fallback, doc.backgroundManifest.skyTopArgb,
                    doc.backgroundManifest.skyBottomArgb);
        else
            writePaletteGradient(fallback,
                    tiles.groundSurface.isEmpty() ? tiles.ground : tiles.groundSurface);
        int index = 1;
        for (BackgroundAssetRef ref : doc.backgroundManifest.assets) {
            if (ref == null) continue;
            if (BackgroundComposer.GENERATED_SKY.equals(ref.sourceKey)) {
                ref.asset = "assets/background/000.png";
                continue;
            }
            TileCatalog.BackgroundAsset asset = findBackground(tiles, ref.sourceKey);
            if (asset == null) throw new IOException("Background asset is no longer available: "
                    + ref.sourceKey + ". Rescan the theme and Regenerate the map.");
            File output = new File(target, String.format("%03d.png", index++));
            BufferedImage packedSky = readPackedSky(ref, asset);
            if (packedSky != null) {
                BufferedImage decoded = unpackLandscape(packedSky, ref.width, ref.height);
                if (!ImageIO.write(decoded, "png", output))
                    throw new IOException("No PNG writer is available for packed sky.");
                ref.sourceTransform = "unpack-595x239";
            } else {
                Files.copy(asset.file.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            ref.asset = "assets/background/" + output.getName();
        }
    }

    private static BufferedImage readPackedSky(BackgroundAssetRef ref,
                                               TileCatalog.BackgroundAsset asset) throws IOException {
        boolean declared = "unpack-595x239".equals(ref.sourceTransform);
        if (!declared && !"sky".equals(ref.role)) return null;
        BufferedImage source = ImageIO.read(asset.file);
        if (source == null) {
            if (declared) throw new IOException("Unreadable packed sky: " + asset.file);
            return null;
        }
        if (source.getWidth() == ref.width && source.getHeight() == ref.height) return null;
        if ((long) source.getWidth() * source.getHeight() != (long) ref.width * ref.height) {
            if (declared) throw new IOException("Packed sky pixel count does not match metadata: "
                    + asset.file);
            return null;
        }
        return source;
    }

    private static BufferedImage unpackLandscape(BufferedImage source, int width, int height) {
        if ((long) width * height != (long) source.getWidth() * source.getHeight())
            throw new IllegalArgumentException("Packed sky pixel count does not match metadata");
        BufferedImage out = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        int packedWidth = source.getWidth();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int index = y * width + x;
            out.setRGB(x, y, source.getRGB(index % packedWidth, index / packedWidth));
        }
        return out;
    }

    private static TileCatalog.BackgroundAsset findBackground(TileCatalog.TileSet tiles,
                                                               String sourceKey) {
        if (tiles == null || sourceKey == null) return null;
        String wanted = sourceKey.replace('\\', '/');
        for (TileCatalog.BackgroundAsset asset : tiles.backgrounds)
            if (asset != null && wanted.equalsIgnoreCase(asset.sourceKey)) return asset;
        return null;
    }

    private static void writePaletteGradient(File output, List<File> sources) throws IOException {
        int base = 0xff75b7df;
        if (sources != null && !sources.isEmpty()) {
            BufferedImage sample = ImageIO.read(sources.get(0));
            if (sample != null) {
                long r = 0, g = 0, b = 0, count = 0;
                int stepX = Math.max(1, sample.getWidth() / 16);
                int stepY = Math.max(1, sample.getHeight() / 16);
                for (int y = 0; y < sample.getHeight(); y += stepY)
                    for (int x = 0; x < sample.getWidth(); x += stepX) {
                        int argb = sample.getRGB(x, y);
                        if (((argb >>> 24) & 0xff) < 32) continue;
                        r += (argb >>> 16) & 0xff;
                        g += (argb >>> 8) & 0xff;
                        b += argb & 0xff;
                        count++;
                    }
                if (count > 0) base = 0xff000000 | ((int) (r / count) << 16)
                        | ((int) (g / count) << 8) | (int) (b / count);
            }
        }
        BufferedImage gradient = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int br = (base >>> 16) & 0xff, bg = (base >>> 8) & 0xff, bb = base & 0xff;
        for (int y = 0; y < gradient.getHeight(); y++) {
            float t = y / 63f;
            int rr = clampColor(Math.round(br * 0.35f + (155f + br * 0.25f) * (1f - t)));
            int gg = clampColor(Math.round(bg * 0.35f + (205f + bg * 0.15f) * (1f - t)));
            int bl = clampColor(Math.round(bb * 0.45f + 245f * (1f - t)));
            int color = 0xff000000 | (rr << 16) | (gg << 8) | bl;
            for (int x = 0; x < gradient.getWidth(); x++) gradient.setRGB(x, y, color);
        }
        ImageIO.write(gradient, "png", output);
    }

    private static void writeThemeSkyGradient(File output, int topArgb,
                                              int bottomArgb) throws IOException {
        BufferedImage gradient = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < gradient.getHeight(); y++) {
            float t = y / 63f;
            int a = mixChannel((topArgb >>> 24) & 0xff, (bottomArgb >>> 24) & 0xff, t);
            int r = mixChannel((topArgb >>> 16) & 0xff, (bottomArgb >>> 16) & 0xff, t);
            int g = mixChannel((topArgb >>> 8) & 0xff, (bottomArgb >>> 8) & 0xff, t);
            int b = mixChannel(topArgb & 0xff, bottomArgb & 0xff, t);
            int argb = (a << 24) | (r << 16) | (g << 8) | b;
            for (int x = 0; x < gradient.getWidth(); x++) gradient.setRGB(x, y, argb);
        }
        ImageIO.write(gradient, "png", output);
    }

    private static int mixChannel(int from, int to, float amount) {
        return Math.max(0, Math.min(255, Math.round(from + (to - from) * amount)));
    }

    private static int clampColor(int value) { return Math.max(0, Math.min(255, value)); }

    private static boolean isEmpty(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y += 8)
            for (int x = 0; x < image.getWidth(); x += 8)
                if (((image.getRGB(x, y) >>> 24) & 0xff) != 0) return false;
        return true;
    }

    private static final class BakedAssets extends TerrainTileRenderer.Assets {}
}
