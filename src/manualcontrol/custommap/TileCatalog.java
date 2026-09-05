package manualcontrol.custommap;

import com.google.gson.Gson;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TileCatalog {

    private static final Gson GSON = new Gson();
    private static final Object CACHE_LOCK = new Object();
    private static volatile List<TileSet> cachedSets;
    private static volatile String cachedRoot;

    private TileCatalog() {}

    public static File bcuRoot() {
        return new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    }

    public static File tilesRoot() {
        return new File(bcuRoot(), "Tiles");
    }

    public static List<TileSet> scan() throws IOException {
        synchronized (CACHE_LOCK) {
            List<TileSet> fresh = scanFresh();
            cachedSets = fresh;
            cachedRoot = cacheRootKey();
            return fresh;
        }
    }

    public static List<TileSet> cached() throws IOException {
        List<TileSet> found = cachedSets;
        String root = cacheRootKey();
        if (found != null && root.equals(cachedRoot)) return found;
        synchronized (CACHE_LOCK) {
            found = cachedSets;
            if (found == null || !root.equals(cachedRoot)) {
                found = scanFresh();
                cachedSets = found;
                cachedRoot = root;
            }
            return found;
        }
    }

    private static List<TileSet> scanFresh() throws IOException {
        File root = tilesRoot();
        if (!root.exists() && !root.mkdirs())
            throw new IOException("Could not create Tiles folder: " + root);
        File[] dirs = root.listFiles();
        ArrayList<TileSet> out = new ArrayList<TileSet>();
        if (dirs != null) for (File dir : dirs) {
            if (dir != null && dir.isDirectory() && !dir.isHidden()
                    && !dir.getName().startsWith(".")) out.add(readTheme(dir));
        }
        Collections.sort(out, new Comparator<TileSet>() {
            @Override public int compare(TileSet a, TileSet b) {
                return a.biome.compareToIgnoreCase(b.biome);
            }
        });
        return Collections.unmodifiableList(out);
    }

    public static TileSet find(String biome) throws IOException {
        if (biome == null) return null;
        List<TileSet> sets = cached();
        for (TileSet set : sets)
            if (set.biome.equalsIgnoreCase(biome)) {
                if (set.catalogDirectoryStamp == directoryStamp(set.root)) return set;
                synchronized (CACHE_LOCK) {
                    List<TileSet> current = cachedSets;
                    for (int i = 0; current != null && i < current.size(); i++) {
                        TileSet candidate = current.get(i);
                        if (!candidate.biome.equalsIgnoreCase(biome)) continue;
                        if (candidate.catalogDirectoryStamp == directoryStamp(candidate.root))
                            return candidate;
                        TileSet refreshed = readTheme(candidate.root);
                        ArrayList<TileSet> replacement = new ArrayList<TileSet>(current);
                        replacement.set(i, refreshed);
                        cachedSets = Collections.unmodifiableList(replacement);
                        return refreshed;
                    }
                }
                return set;
            }
        return null;
    }

    private static String cacheRootKey() {
        return tilesRoot().getAbsolutePath().toLowerCase(Locale.ROOT);
    }

    private static long directoryStamp(File root) {
        if (root == null) return 0L;
        long stamp = root.lastModified() ^ root.length();
        File[] children = root.listFiles();
        if (children != null) for (File child : children) {
            if (child == null) continue;
            long item = child.getName().toLowerCase(Locale.ROOT).hashCode();
            item = item * 1099511628211L ^ child.lastModified();
            item = item * 1099511628211L ^ child.length();
            stamp ^= item;
            stamp += Long.rotateLeft(item, 17);
        }
        return stamp;
    }

    private static TileSet readTheme(File root) {
        TileSet set = new TileSet();
        set.biome = root.getName();
        set.root = root;
        set.catalogDirectoryStamp = directoryStamp(root);
        set.themeProfile = readThemeProfile(root, set.warnings);
        applySurfaceRenderProfile(set);

        ArrayList<File> pngs = new ArrayList<File>();
        collectPngs(root, pngs);
        Collections.sort(pngs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            }
        });
        set.scannedPngs = pngs.size();

        ArrayList<Asset> ground = new ArrayList<Asset>();
        ArrayList<Asset> water = new ArrayList<Asset>();
        ArrayList<Asset> trees = new ArrayList<Asset>();
        ArrayList<Asset> props = new ArrayList<Asset>();
        ArrayList<Asset> backgrounds = new ArrayList<Asset>();
        for (File file : pngs) {
            String relative = relative(root, file);
            if (isFloatingIslandSpan(relative)) {
                registerFloatingIslandSpan(set, file, relative);
                continue;
            }
            if (isInnerCornerJunction(relative)) {
                registerInnerCornerJunction(set, file);
                continue;
            }
            Kind kind = classify(relative);
            if (kind == Kind.IGNORE) { set.ignoredPngs++; continue; }
            Asset asset = inspect(file, relative, kind, set.warnings);
            if (asset == null) { set.ignoredPngs++; continue; }
            if (kind == Kind.GROUND) ground.add(asset);
            else if (kind == Kind.WATER) water.add(asset);
            else if (kind == Kind.TREE) trees.add(asset);
            else if (kind == Kind.PROP) props.add(asset);
            else if (kind == Kind.BACKGROUND) backgrounds.add(asset);
            else if (kind == Kind.ICE_SURFACE) registerIceSurface(set, asset);
            else if (kind == Kind.VFX) {
                String vfxKind = vfxKind(relative);
                List<File> files = set.vfxAssets.get(vfxKind);
                if (files == null) {
                    files = new ArrayList<File>();
                    set.vfxAssets.put(vfxKind, files);
                }
                files.add(file);
            }
            else set.ignoredPngs++;
        }

        for (List<File> files : set.vfxAssets.values())
            Collections.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return naturalPathCompare(a.getAbsolutePath(), b.getAbsolutePath());
                }
            });

        int cell = dominantSquareSize(ground);
        set.tilePixels = Math.max(1, cell);
        if (cell <= 0) {
            set.errors.add("No full-cell ground tiles were detected recursively in '" + set.biome + "'.");
            set.warnings.add("Ground hints: ground, terrain, tiles, platform, block, fill, repeat, center.");
            return set;
        }
        validateFloatingIslandSpans(set, cell);

        ArrayList<Asset> compatibleGround = new ArrayList<Asset>();
        for (Asset asset : ground) {
            if (asset.width != cell || asset.height != cell) {
                set.filteredPngs++;
                continue;
            }
            compatibleGround.add(asset);
        }
        assignGroundRoles(compatibleGround, set);
        selectContinuousSlopePairs(compatibleGround, set, cell);
        buildGroundFamilies(compatibleGround, set, cell);
        set.surfaceMaterial = detectSurfaceMaterial(
                set.themeProfile, set.biome, set.groundFamily, compatibleGround);
        if (set.surfaceMaterial == CustomMapDocument.SURFACE_ICE)
            set.warnings.add("Ice contact surface detected; generated units use inertia physics.");
        set.filteredPngs += Math.max(0, compatibleGround.size() - set.ground.size());
        if (set.ground.isEmpty()) set.errors.add("No square " + cell + "x" + cell + " ground tile is usable.");

        ArrayList<Asset> compatibleWater = new ArrayList<Asset>();
        for (Asset asset : water) {
            if (asset.width == cell && asset.height == cell) compatibleWater.add(asset);
            else set.filteredPngs++;
        }
        assignWaterRoles(compatibleWater, set);
        ArrayList<Asset> compatibleTrees = new ArrayList<Asset>();
        ArrayList<Asset> preferredTrees = new ArrayList<Asset>();
        for (Asset asset : trees) {
            if (asset.hasTransparency && asset.width > 0 && asset.height > 0
                    && asset.width <= cell * 8 && asset.height <= cell * 10) {
                compatibleTrees.add(asset);
                if (hasSegment(asset.relative, "tree") || hasSegment(asset.relative, "trees")
                        || any(asset.relative, "tree_")) preferredTrees.add(asset);
            } else set.filteredPngs++;
        }
        List<Asset> chosenTrees = preferredTrees.isEmpty() ? compatibleTrees : preferredTrees;
        for (Asset asset : chosenTrees) set.trees.add(asset.file);
        set.filteredPngs += compatibleTrees.size() - chosenTrees.size();
        Collections.sort(props, new Comparator<Asset>() {
            @Override public int compare(Asset a, Asset b) {
                return naturalPathCompare(a.relative, b.relative);
            }
        });
        for (Asset asset : props) {
            if (!asset.hasTransparency || asset.width <= 0 || asset.height <= 0) {
                set.filteredPngs++;
                set.warnings.add(asset.relative + ": prop PNG must be RGBA/transparent; ignored.");
            } else set.props.add(propAsset(asset));
        }
        int widestCloud = 1;
        for (Asset asset : backgrounds)
            if (any(normalized(asset.relative), "cloud"))
                widestCloud = Math.max(widestCloud, asset.width);
        for (Asset asset : backgrounds)
            set.backgrounds.add(backgroundAsset(asset, widestCloud, set.themeProfile));
        Collections.sort(set.backgrounds, new Comparator<BackgroundAsset>() {
            @Override public int compare(BackgroundAsset a, BackgroundAsset b) {
                int order = Integer.compare(backgroundRoleOrder(a.role), backgroundRoleOrder(b.role));
                return order != 0 ? order : a.file.getAbsolutePath().compareToIgnoreCase(b.file.getAbsolutePath());
            }
        });
        inheritSharedThemeResources(set);

        String liquidName = liquidName(set.themeProfile);
        set.warnings.add(0, "Auto-detected recursively: ground=" + set.ground.size()
                + " (surface=" + set.groundSurface.size() + ", fill=" + set.groundFill.size() + ")"
                + ", " + liquidName + "=" + set.water.size() + " (surface=" + set.waterSurface.size()
                + ", fill=" + set.waterFill.size() + "), tree/decoration=" + set.trees.size()
                + ", prop=" + set.props.size() + " (random=" + set.randomPropCount() + ")"
                + ", background=" + set.backgrounds.size()
                + ", vfx=" + set.vfxAssetCount()
                + ", cell=" + cell + "px, ignored/filtered=" + (set.ignoredPngs + set.filteredPngs) + ".");
        return set;
    }

    private static CustomMapDocument.ThemeProfile readThemeProfile(
            File root, List<String> warnings) {
        File file = new File(root, "theme-profile.json");
        if (!file.isFile()) return null;
        Reader reader = null;
        try {
            reader = new FileReader(file);
            CustomMapDocument.ThemeProfile parsed = GSON.fromJson(
                    reader, CustomMapDocument.ThemeProfile.class);
            CustomMapDocument.ThemeProfile normalized =
                    CustomMapDocument.ThemeProfile.normalized(parsed);
            if (normalized == null || normalized.profileId.isEmpty()) {
                warnings.add("theme-profile.json has no profileId; legacy theme behavior will be used.");
                return null;
            }
            return normalized;
        } catch (Throwable t) {
            warnings.add("theme-profile.json is unreadable; legacy theme behavior will be used.");
            return null;
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
        }
    }

    private static void assignGroundRoles(List<Asset> assets, TileSet set) {
        for (Asset asset : assets) {
            String value = normalized(asset.relative);
            if (any(value, "slope") && !any(value, "underside")) {

                int edgeDelta = isFourPartSlopeFamily(asset, assets)
                        ? asset.slopeDelta : asset.slopeRight - asset.slopeLeft;
                if (edgeDelta > 0) add(set.groundSlopeDown, asset.file);
                else if (edgeDelta < 0) add(set.groundSlopeUp, asset.file);
                else set.filteredPngs++;
                continue;
            }
            if (any(value, "platform_inner_corner_no_passthrough_left")) {
                add(set.groundStepJunctionLeft, asset.file);
                continue;
            }
            if (any(value, "platform_inner_corner_no_passthrough_right")) {
                add(set.groundStepJunctionRight, asset.file);
                continue;
            }
            if (any(value, "slope", "entrance", "entance", "passthrough", "pass_through",
                    "connector", "duplicate")) {
                set.filteredPngs++;
                continue;
            }

            boolean topLeft = any(value, "outer_corner_top_left", "top_left_outer_corner",
                    "outer_corner_with_platform_top_left", "top_left_edge");
            boolean topRight = any(value, "outer_corner_top_right", "top_right_outer_corner",
                    "outer_corner_with_platform_top_right", "top_right_edge");
            boolean bottomLeft = any(value, "outer_corner_bottom_left", "bottom_left_outer_corner",
                    "bottom_left_edge");
            boolean bottomRight = any(value, "outer_corner_bottom_right", "bottom_right_outer_corner",
                    "bottom_right_edge");
            boolean innerTopLeft = any(value, "inner_corner_top_left",
                    "top_left_inner_corner");
            boolean innerTopRight = any(value, "inner_corner_top_right",
                    "top_right_inner_corner");
            boolean innerBottomLeft = any(value, "inner_corner_bottom_left",
                    "bottom_left_inner_corner");
            boolean innerBottomRight = any(value, "inner_corner_bottom_right",
                    "bottom_right_inner_corner");

            if (any(value, "island_single", "platform_single"))
                add(set.groundPlatformSingle, asset.file);
            else if (any(value, "island_left_end", "platform_left_edge"))
                add(set.groundPlatformLeft, asset.file);
            else if (any(value, "island_right_end", "platform_right_edge"))
                add(set.groundPlatformRight, asset.file);
            else if (any(value, "island_center", "platform_inner_repeating", "platform_top_edge"))
                add(set.groundPlatformCenter, asset.file);
            else if (innerTopLeft) add(set.groundInnerTopLeft, asset.file);
            else if (innerTopRight) add(set.groundInnerTopRight, asset.file);
            else if (innerBottomLeft) add(set.groundInnerBottomLeft, asset.file);
            else if (innerBottomRight) add(set.groundInnerBottomRight, asset.file);
            else if (topLeft) add(set.groundTopLeft, asset.file);
            else if (topRight) add(set.groundTopRight, asset.file);
            else if (bottomLeft) add(set.groundBottomLeft, asset.file);
            else if (bottomRight) add(set.groundBottomRight, asset.file);
            else if (isGrassSurface(value)) add(set.groundSurface, asset.file);
            else if (isGroundFill(value)) add(set.groundFill, asset.file);
            else if (any(value, "left_edge_vertical", "left_edge_repeating",
                    "vertical_repeating_left", "vertical_repeat_left", "left_tile"))
                add(set.groundLeft, asset.file);
            else if (any(value, "right_edge_vertical", "right_edge_repeating",
                    "vertical_repeating_right", "vertical_repeat_right", "right_tile"))
                add(set.groundRight, asset.file);
            else if (any(value, "bottom_horizontal_repeating", "bottom_tile", "bottom_edge_repeating"))
                add(set.groundBottom, asset.file);
        }

        copyNamed(set.groundSlopeUp, set.groundSteepSlopeUp, "steep");
        copyNamed(set.groundSlopeDown, set.groundSteepSlopeDown, "steep");
        set.strictGroundRoles = hasAuthoredContourRoles(set);

        if (set.groundSurface.isEmpty()) {
            if (!set.groundPlatformCenter.isEmpty())
                set.groundSurface.addAll(set.groundPlatformCenter);
            if (set.groundSurface.isEmpty())
                for (Asset asset : assets) if (isSafeGround(asset.relative)
                        && !isGroundFill(asset.relative)
                        && !any(asset.relative, "bottom", "vertical", "underground"))
                    add(set.groundSurface, asset.file);
            if (set.groundSurface.isEmpty())
                for (Asset asset : assets) if (asset.explicitGround) add(set.groundSurface, asset.file);
            if (!set.groundSurface.isEmpty())
                set.warnings.add("No explicit grass/top tile name was found; using repeatable platform tiles as surface fallback.");
        }
        if (set.groundFill.isEmpty()) {
            if (!set.strictGroundRoles) {
                for (Asset asset : assets) if (isSafeGround(asset.relative)
                        && !any(asset.relative, "top", "bottom", "left", "right", "corner", "island"))
                    add(set.groundFill, asset.file);
                if (set.groundFill.isEmpty()) set.groundFill.addAll(set.groundSurface);
                set.warnings.add("No explicit ground fill tile was found; surface fallback will be used below ground.");
            } else {
                set.warnings.add("No explicit ground fill tile was found; authored terrain interiors will remain transparent.");
            }
        }
        preferMainFill(set.groundFill);

        String mainFamily = terrainFamily(set.groundSurface);
        String contourFamily = contourFamily(set, mainFamily);
        preferFamily(set.groundLeft, contourFamily);
        preferFamily(set.groundRight, contourFamily);
        preferFamily(set.groundBottom, contourFamily);
        preferFamily(set.groundTopLeft, contourFamily);
        preferFamily(set.groundTopRight, contourFamily);
        preferFamily(set.groundBottomLeft, contourFamily);
        preferFamily(set.groundBottomRight, contourFamily);

        preferNamed(set.groundLeft, "left_tile");
        preferNamed(set.groundRight, "right_tile");
        preferNamed(set.groundBottom, "bottom_tile");
        preferNamed(set.groundTopLeft, "outer_corner");
        preferNamed(set.groundTopRight, "outer_corner");
        preferNamed(set.groundBottomLeft, "outer_corner");
        preferNamed(set.groundBottomRight, "outer_corner");
        if (set.missingDiagonalInnerCorners) {
            preferNamed(set.groundTopLeft, "outer_corner_with_platform");
            preferNamed(set.groundTopRight, "outer_corner_with_platform");
        }

        preferNamed(set.groundInnerTopLeft, "platform", "grass");
        preferNamed(set.groundInnerTopRight, "platform", "grass");
        preferNamed(set.groundInnerBottomLeft, "platform", "grass");
        preferNamed(set.groundInnerBottomRight, "platform", "grass");
        preferNamed(set.groundPlatformCenter, "island");
        preferNamed(set.groundPlatformLeft, "island");
        preferNamed(set.groundPlatformRight, "island");
        preferNamed(set.groundPlatformSingle, "island");
        preferNamed(set.groundSlopeUp, "shallow");
        preferNamed(set.groundSlopeDown, "shallow");
        preferSlopeFamily(set.groundSlopeUp, set.groundSlopeDown);
        sortSlopes(set.groundSlopeUp, assets, true);
        sortSlopes(set.groundSlopeDown, assets, false);
        preferSlopeFamily(set.groundSteepSlopeUp, set.groundSteepSlopeDown);
        sortSlopes(set.groundSteepSlopeUp, assets, true);
        sortSlopes(set.groundSteepSlopeDown, assets, false);
        for (List<File> role : groundRoles(set)) for (File file : role) add(set.ground, file);
    }

    private static void assignWaterRoles(List<Asset> assets, TileSet set) {
        for (Asset asset : assets) {
            String value = normalized(asset.relative);
            if (any(value, "frame", "surface", "wave", "water_top", "top_water"))
                add(set.waterSurface, asset.file);
            else if (any(value, "fill", "body", "deep", "center", "colour", "color"))
                add(set.waterFill, asset.file);
        }
        if (set.waterSurface.isEmpty() && !assets.isEmpty()) {
            add(set.waterSurface, assets.get(0).file);
            set.warnings.add("No explicit water surface/frame name was found; using the first compatible water tile.");
        }
        if (set.waterFill.isEmpty() && !set.waterSurface.isEmpty()) {
            set.waterFill.add(set.waterSurface.get(0));
            set.warnings.add("No explicit water fill tile was found; the surface tile will be repeated below water.");
        }

        Collections.sort(set.waterSurface, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return naturalPathCompare(a.getAbsolutePath(), b.getAbsolutePath());
            }
        });
        for (File file : set.waterSurface) add(set.water, file);
        for (File file : set.waterFill) add(set.water, file);
        set.filteredPngs += Math.max(0, assets.size() - set.water.size());
    }

    private static int naturalPathCompare(String a, String b) {
        int ai = 0, bi = 0;
        while (ai < a.length() && bi < b.length()) {
            char ac = Character.toLowerCase(a.charAt(ai));
            char bc = Character.toLowerCase(b.charAt(bi));
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                int az = ai;
                int bz = bi;
                while (az < a.length() && a.charAt(az) == '0') az++;
                while (bz < b.length() && b.charAt(bz) == '0') bz++;
                int ae = az;
                int be = bz;
                while (ae < a.length() && Character.isDigit(a.charAt(ae))) ae++;
                while (be < b.length() && Character.isDigit(b.charAt(be))) be++;
                int digits = Integer.compare(ae - az, be - bz);
                if (digits != 0) return digits;
                for (int i = 0; i < ae - az; i++) {
                    int value = Character.compare(a.charAt(az + i), b.charAt(bz + i));
                    if (value != 0) return value;
                }

                int leading = Integer.compare(az - ai, bz - bi);
                if (leading != 0) return leading;
                ai = ae;
                bi = be;
                continue;
            }
            if (ac != bc) return Character.compare(ac, bc);
            ai++;
            bi++;
        }
        return Integer.compare(a.length() - ai, b.length() - bi);
    }

    private static boolean isGrassSurface(String value) {
        return any(value, "top_with_platform", "with_platform_horizontal",
                "top_tile", "grass_top", "grass_surface");
    }

    private static boolean isGroundFill(String value) {
        return any(value, "fill_texture", "underground_fill", "/fill.", "\\fill.",
                "color.png", "colour.png", "ground_fill", "terrain_fill");
    }

    private static boolean hasAuthoredContourRoles(TileSet set) {
        int roles = 0;
        if (!set.groundLeft.isEmpty()) roles++;
        if (!set.groundRight.isEmpty()) roles++;
        if (!set.groundBottom.isEmpty()) roles++;
        if (!set.groundTopLeft.isEmpty()) roles++;
        if (!set.groundTopRight.isEmpty()) roles++;
        if (!set.groundBottomLeft.isEmpty()) roles++;
        if (!set.groundBottomRight.isEmpty()) roles++;
        if (!set.groundInnerTopLeft.isEmpty()) roles++;
        if (!set.groundInnerTopRight.isEmpty()) roles++;
        if (!set.groundInnerBottomLeft.isEmpty()) roles++;
        if (!set.groundInnerBottomRight.isEmpty()) roles++;
        return roles >= 3;
    }

    private static List<List<File>> groundRoles(TileSet set) {
        ArrayList<List<File>> roles = new ArrayList<List<File>>();
        roles.add(set.groundSurface);
        roles.add(set.groundFill);
        roles.add(set.groundLeft);
        roles.add(set.groundRight);
        roles.add(set.groundBottom);
        roles.add(set.groundTopLeft);
        roles.add(set.groundTopRight);
        roles.add(set.groundBottomLeft);
        roles.add(set.groundBottomRight);
        roles.add(set.groundInnerTopLeft);
        roles.add(set.groundInnerTopRight);
        roles.add(set.groundInnerBottomLeft);
        roles.add(set.groundInnerBottomRight);
        roles.add(set.groundPlatformCenter);
        roles.add(set.groundPlatformLeft);
        roles.add(set.groundPlatformRight);
        roles.add(set.groundPlatformSingle);
        roles.add(set.groundSlopeUp);
        roles.add(set.groundSlopeDown);
        roles.add(set.groundSlopeUpSupport);
        roles.add(set.groundSlopeDownSupport);
        roles.add(set.groundSlopeUpEndpointSupport);
        roles.add(set.groundSlopeDownEndpointSupport);
        roles.add(set.groundSteepSlopeUp);
        roles.add(set.groundSteepSlopeDown);
        roles.add(set.groundSteepSlopeUpSupport);
        roles.add(set.groundSteepSlopeDownSupport);
        roles.add(set.groundSteepSlopeUpEndpointSupport);
        roles.add(set.groundSteepSlopeDownEndpointSupport);
        roles.add(set.groundStepJunctionLeft);
        roles.add(set.groundStepJunctionRight);
        return roles;
    }

    private static void add(List<File> files, File file) {
        if (file != null && !files.contains(file)) files.add(file);
    }

    private static void preferNamed(List<File> files, String... hints) {
        ArrayList<File> preferred = new ArrayList<File>();
        for (File file : files) if (any(file.getName(), hints)) preferred.add(file);
        if (!preferred.isEmpty()) {
            files.clear();
            files.addAll(preferred);
        }
    }

    private static void copyNamed(List<File> source, List<File> target,
                                  String... hints) {
        if (source == null || target == null) return;
        for (File file : source)
            if (file != null && any(file.getName(), hints)) add(target, file);
    }

    private static void preferMainFill(List<File> files) {
        ArrayList<File> explicit = new ArrayList<File>();
        ArrayList<File> aboveGround = new ArrayList<File>();
        for (File file : files) {
            String value = normalized(file.getName());
            if (!any(value, "underground", "cave", "subterranean"))
                aboveGround.add(file);
            if (!any(value, "underground", "cave", "subterranean")
                    && any(value, "fill_texture", "terrain_fill", "ground_fill",
                    "color.png", "colour.png"))
                explicit.add(file);
        }
        if (!explicit.isEmpty()) {
            files.clear();
            files.addAll(explicit);
        } else if (!aboveGround.isEmpty()) {
            files.clear();
            files.addAll(aboveGround);
        }
    }

    private static String terrainFamily(List<File> surfaces) {
        if (surfaces == null) return "";
        String[] families = {"block", "platform", "ground", "terrain", "floor"};
        for (String family : families)
            for (File file : surfaces)
                if (any(file.getName(), family)) return family;
        return "";
    }

    private static String contourFamily(TileSet set, String fallback) {
        List<File> surfaces = set == null ? null : set.groundSurface;
        if (surfaces != null)
            for (File file : surfaces) {
                String name = file == null ? "" : file.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("platform-") || name.startsWith("platform_")
                        || name.startsWith("grass-") || name.startsWith("grass_")
                        || set != null && !set.stackedSafeBandSlopes
                        && any(name, "with_platform"))
                    return "platform";
            }
        return fallback == null ? "" : fallback;
    }

    private static void preferFamily(List<File> files, String family) {
        if (files == null || files.isEmpty() || family == null || family.isEmpty()) return;
        ArrayList<File> preferred = new ArrayList<File>();
        for (File file : files) if (any(file.getName(), family)) preferred.add(file);
        if ("platform".equalsIgnoreCase(family)) {
            ArrayList<File> purePlatform = new ArrayList<File>();
            for (File file : preferred)
                if (file.getName().toLowerCase(Locale.ROOT).startsWith("platform-"))
                    purePlatform.add(file);
            if (!purePlatform.isEmpty()) preferred = purePlatform;
        }
        if (!preferred.isEmpty()) {
            files.clear();
            files.addAll(preferred);
        }
    }

    private static void preferSlopeFamily(List<File> up, List<File> down) {
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        for (File file : up) increment(counts, file.getParentFile().getAbsolutePath());
        for (File file : down) increment(counts, file.getParentFile().getAbsolutePath());
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount || (entry.getValue() == bestCount
                    && (best == null || entry.getKey().compareToIgnoreCase(best) < 0))) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (best != null) {
            retainParent(up, best);
            retainParent(down, best);
        }
    }

    private static void increment(Map<String, Integer> counts, String key) {
        Integer value = counts.get(key);
        counts.put(key, value == null ? 1 : value + 1);
    }

    private static void retainParent(List<File> files, String parent) {
        ArrayList<File> kept = new ArrayList<File>();
        for (File file : files)
            if (file.getParentFile().getAbsolutePath().equals(parent)) kept.add(file);
        if (!kept.isEmpty()) {
            files.clear();
            files.addAll(kept);
        }
    }

    private static void sortSlopes(List<File> files, List<Asset> assets, final boolean up) {
        final HashMap<File, Integer> means = new HashMap<File, Integer>();
        for (Asset asset : assets) means.put(asset.file, asset.slopeMean);
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                int am = means.containsKey(a) ? means.get(a) : 0;
                int bm = means.containsKey(b) ? means.get(b) : 0;
                int result = up ? Integer.compare(bm, am) : Integer.compare(am, bm);
                return result != 0 ? result : a.getName().compareToIgnoreCase(b.getName());
            }
        });
    }

    private static void selectContinuousSlopePairs(List<Asset> assets, TileSet set, int cell) {
        int tolerance = Math.max(2, Math.round(cell * 0.03f));
        HashMap<File, Asset> byFile = new HashMap<File, Asset>();
        for (Asset asset : assets) byFile.put(asset.file, asset);
        set.surfaceInsetPixels = referenceSurfaceInset(set.groundSurface, byFile);
        int upError;
        int downError;
        if (set.stackedSafeBandSlopes) {
            upError = selectStackedSafeBandSlopeChain(
                    set.groundSlopeUp, byFile, cell, false);
            downError = selectStackedSafeBandSlopeChain(
                    set.groundSlopeDown, byFile, cell, true);
        } else {
            upError = selectContinuousSlopeChain(set.groundSlopeUp, byFile, tolerance,
                    cell, set.surfaceInsetPixels, false);
            downError = selectContinuousSlopeChain(set.groundSlopeDown, byFile, tolerance,
                    cell, set.surfaceInsetPixels, true);
        }
        if (upError < 0 || downError < 0
                || set.groundSlopeUp.size() != set.groundSlopeDown.size()) {
            set.groundSlopeUp.clear();
            set.groundSlopeDown.clear();
            set.warnings.add("No bidirectional flat-to-flat slope chain passed contour tolerance "
                    + tolerance + "px; the main route will stay flat.");
        } else {
            buildMacroSlopeSupports(assets, set);
            for (File file : set.groundSlopeUpSupport) add(set.ground, file);
            for (File file : set.groundSlopeDownSupport) add(set.ground, file);
            buildThreePieceSlopeEndpointSupports(assets, set);
            for (File file : set.groundSlopeUpEndpointSupport) add(set.ground, file);
            for (File file : set.groundSlopeDownEndpointSupport) add(set.ground, file);
            set.maxSlopeSeamErrorPixels = Math.max(upError, downError);
            set.warnings.add("Slope contour chain: " + set.groundSlopeUp.size()
                    + " tiles/direction, surface inset " + set.surfaceInsetPixels
                    + "px, maximum seam error " + set.maxSlopeSeamErrorPixels + "px"
                    + (set.hasMacroSlopeSupports() ? ", macro support enabled."
                    : set.hasEndpointSlopeSupports() ? ", endpoint support enabled." : "."));
        }
        selectSteepSlopePair(assets, set, cell, tolerance, byFile);
    }

    private static void selectSteepSlopePair(List<Asset> assets, TileSet set,
                                             int cell, int tolerance,
                                             Map<File, Asset> byFile) {
        if (set.groundSteepSlopeUp.isEmpty()
                || set.groundSteepSlopeDown.isEmpty()) return;
        TileSet candidate = new TileSet();
        candidate.stackedSafeBandSlopes = set.stackedSafeBandSlopes;
        candidate.surfaceInsetPixels = set.surfaceInsetPixels;
        candidate.groundSurface.addAll(set.groundSurface);
        candidate.groundSteepSlopeUp.clear();
        candidate.groundSteepSlopeDown.clear();
        candidate.groundSlopeUp.addAll(set.groundSteepSlopeUp);
        candidate.groundSlopeDown.addAll(set.groundSteepSlopeDown);
        int upError;
        int downError;
        if (candidate.stackedSafeBandSlopes) {
            upError = selectStackedSafeBandSlopeChain(
                    candidate.groundSlopeUp, byFile, cell, false);
            downError = selectStackedSafeBandSlopeChain(
                    candidate.groundSlopeDown, byFile, cell, true);
        } else {
            upError = selectSteepSlopeChain(candidate.groundSlopeUp, byFile,
                    tolerance, cell, candidate.surfaceInsetPixels, false);
            downError = selectSteepSlopeChain(candidate.groundSlopeDown, byFile,
                    tolerance, cell, candidate.surfaceInsetPixels, true);
        }
        if (upError < 0 || downError < 0 || candidate.groundSlopeUp.isEmpty()
                || candidate.groundSlopeDown.isEmpty()) {
            set.groundSteepSlopeUp.clear();
            set.groundSteepSlopeDown.clear();
            set.warnings.add("Steep slope artwork is incomplete or its two directions do not connect flat-to-flat; Manual Steep is disabled.");
            return;
        }
        buildMacroSlopeSupports(assets, candidate);
        buildThreePieceSlopeEndpointSupports(assets, candidate);
        set.groundSteepSlopeUp.clear();
        set.groundSteepSlopeUp.addAll(candidate.groundSlopeUp);
        set.groundSteepSlopeDown.clear();
        set.groundSteepSlopeDown.addAll(candidate.groundSlopeDown);
        set.groundSteepSlopeUpSupport.addAll(candidate.groundSlopeUpSupport);
        set.groundSteepSlopeDownSupport.addAll(candidate.groundSlopeDownSupport);
        set.groundSteepSlopeUpEndpointSupport.addAll(
                candidate.groundSlopeUpEndpointSupport);
        set.groundSteepSlopeDownEndpointSupport.addAll(
                candidate.groundSlopeDownEndpointSupport);
        for (File file : set.groundSteepSlopeUp) add(set.ground, file);
        for (File file : set.groundSteepSlopeDown) add(set.ground, file);
        for (File file : set.groundSteepSlopeUpSupport) add(set.ground, file);
        for (File file : set.groundSteepSlopeDownSupport) add(set.ground, file);
        for (File file : set.groundSteepSlopeUpEndpointSupport) add(set.ground, file);
        for (File file : set.groundSteepSlopeDownEndpointSupport) add(set.ground, file);
        set.warnings.add("Verified steep slope contour: "
                + set.groundSteepSlopeUp.size() + " up / "
                + set.groundSteepSlopeDown.size() + " down tiles.");
    }

    private static int selectSteepSlopeChain(List<File> files,
                                             Map<File, Asset> byFile,
                                             int tolerance, int cell,
                                             int surfaceInset, boolean down) {
        if (files == null || files.isEmpty()) return -1;
        if (files.size() > 1)
            return selectContinuousSlopeChain(files, byFile, tolerance,
                    cell, surfaceInset, down);
        Asset asset = byFile.get(files.get(0));
        if (asset == null) return -1;
        boolean startsFlat = down ? asset.slopeLeft <= tolerance
                || Math.abs(asset.slopeLeft - surfaceInset) <= tolerance
                : asset.slopeLeft >= cell - tolerance;
        boolean endsFlat = down ? asset.slopeRight >= cell - tolerance
                : asset.slopeRight <= tolerance
                || Math.abs(asset.slopeRight - surfaceInset) <= tolerance;
        if (!startsFlat || !endsFlat) return -1;
        return Math.max(down
                        ? Math.abs(asset.slopeLeft - surfaceInset)
                        : Math.abs(asset.slopeLeft - cell),
                down ? Math.abs(asset.slopeRight - cell)
                        : Math.abs(asset.slopeRight - surfaceInset));
    }

    private static int selectStackedSafeBandSlopeChain(
            List<File> files, Map<File, Asset> byFile, int cell, boolean down) {
        if (files == null || files.size() < 4) return -1;
        LinkedHashMap<String, File[]> families = new LinkedHashMap<String, File[]>();
        for (File file : files) {
            int part = numberedSlopePart(file);
            if (part < 1 || part > 4) continue;
            Asset asset = byFile.get(file);
            if (asset == null) continue;
            int exactLeft = exactSlopeEndpoint(file, true);
            int exactRight = exactSlopeEndpoint(file, false);
            int signedDelta = exactRight - exactLeft;
            if ((!down && signedDelta >= 0) || (down && signedDelta <= 0)) continue;
            if (Math.min(exactLeft, exactRight) < cell / 8
                    || Math.max(exactLeft, exactRight) > cell - cell / 8)
                continue;
            String key = file.getParentFile().getAbsolutePath() + "\n"
                    + numberedSlopeFamily(file);
            File[] parts = families.get(key);
            if (parts == null) {
                parts = new File[4];
                families.put(key, parts);
            }
            parts[part - 1] = file;
        }

        File[] best = null;
        int bestError = Integer.MAX_VALUE;
        for (File[] parts : families.values()) {
            boolean complete = true;
            int total = 0;
            int maximumPhaseError = 0;
            int expectedPhase = Math.max(1, cell / 4);
            for (File part : parts) {
                Asset asset = byFile.get(part);
                if (asset == null) {
                    complete = false;
                    break;
                }
                int delta = Math.abs(exactSlopeEndpoint(part, false)
                        - exactSlopeEndpoint(part, true));
                total += delta;
                maximumPhaseError = Math.max(maximumPhaseError,
                        Math.abs(delta - expectedPhase));
            }
            if (!complete) continue;
            int error = Math.max(maximumPhaseError, Math.abs(total - cell));
            if (error < bestError) {
                best = parts;
                bestError = error;
            }
        }
        if (best == null || bestError > Math.max(3, cell / 32)) return -1;
        files.clear();
        Collections.addAll(files, best);
        return bestError;
    }

    private static int exactSlopeEndpoint(File file, boolean left) {
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) return Integer.MIN_VALUE / 4;
            int x = left ? 0 : image.getWidth() - 1;
            for (int y = 0; y < image.getHeight(); y++)
                if (((image.getRGB(x, y) >>> 24) & 0xff) > 16) return y;
            return image.getHeight();
        } catch (IOException ignored) {
            return Integer.MIN_VALUE / 4;
        }
    }

    private static void buildThreePieceSlopeEndpointSupports(List<Asset> assets, TileSet set) {
        set.groundSlopeUpEndpointSupport.clear();
        set.groundSlopeDownEndpointSupport.clear();
        if (set.hasMacroSlopeSupports()) return;

        if (set.groundSlopeUp.size() == 2
                && numberedSlopePart(set.groundSlopeUp.get(0)) == 2
                && numberedSlopePart(set.groundSlopeUp.get(1)) == 3) {
            File support = numberedSlopeSibling(assets, set.groundSlopeUp.get(0), 1);
            if (support != null) set.groundSlopeUpEndpointSupport.add(support);
        }
        if (set.groundSlopeDown.size() == 2
                && numberedSlopePart(set.groundSlopeDown.get(0)) == 3
                && numberedSlopePart(set.groundSlopeDown.get(1)) == 2) {
            File support = numberedSlopeSibling(assets, set.groundSlopeDown.get(1), 1);
            if (support != null) set.groundSlopeDownEndpointSupport.add(support);
        }
        if (set.groundSlopeUpEndpointSupport.isEmpty()
                || set.groundSlopeDownEndpointSupport.isEmpty()) {
            set.groundSlopeUpEndpointSupport.clear();
            set.groundSlopeDownEndpointSupport.clear();
        } else {
            set.sealSlopeUnderlay = true;
        }
    }

    private static File numberedSlopeSibling(List<Asset> assets, File source, int wantedPart) {
        if (source == null || numberedSlopePart(source) < 0) return null;
        String family = numberedSlopeFamily(source);
        for (Asset asset : assets)
            if (asset.file.getParentFile().equals(source.getParentFile())
                    && numberedSlopePart(asset.file) == wantedPart
                    && family.equals(numberedSlopeFamily(asset.file)))
                return asset.file;
        return null;
    }

    private static int numberedSlopePart(File file) {
        if (file == null) return -1;
        String name = file.getName().toLowerCase(Locale.ROOT).replaceFirst("\\.[^.]+$", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|[_ -])0*([0-9]+)$").matcher(name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String numberedSlopeFamily(File file) {
        if (file == null) return "";
        return file.getName().toLowerCase(Locale.ROOT)
                .replaceFirst("\\.[^.]+$", "")
                .replaceFirst("[_ -]*0*[0-9]+$", "")
                .replaceAll("[^a-z]+", "");
    }

    private static int selectContinuousSlopeChain(List<File> files, Map<File, Asset> byFile,
                                                  int tolerance, int cell, int surfaceInset,
                                                  boolean down) {
        if (files.size() < 2) return -1;
        ArrayList<File> chain = new ArrayList<File>();
        File current = null;
        for (File file : files) {
            Asset asset = byFile.get(file);
            if (asset == null) continue;
            boolean startsFlat = down ? (asset.slopeLeft <= tolerance
                    || Math.abs(asset.slopeLeft - surfaceInset) <= tolerance)
                    : asset.slopeLeft >= cell - tolerance;
            if (!startsFlat) continue;
            if (current == null || contourDelta(asset) < contourDelta(byFile.get(current)))
                current = file;
        }
        while (current != null && chain.size() < files.size()) {
            chain.add(current);
            Asset tail = byFile.get(current);
            boolean endsFlat = down ? tail.slopeRight >= cell - tolerance
                    : (tail.slopeRight <= tolerance
                    || Math.abs(tail.slopeRight - surfaceInset) <= tolerance);
            if (endsFlat) break;
            File next = null;
            for (File candidate : files) {
                if (chain.contains(candidate)) continue;
                Asset asset = byFile.get(candidate);
                if (asset == null || Math.abs(tail.slopeRight - asset.slopeLeft) > tolerance)
                    continue;
                if (next == null || contourDelta(asset) < contourDelta(byFile.get(next)))
                    next = candidate;
            }
            current = next;
        }
        if (chain.size() < 2) return -1;
        Asset tail = byFile.get(chain.get(chain.size() - 1));
        boolean endsFlat = down ? tail.slopeRight >= cell - tolerance
                : (tail.slopeRight <= tolerance
                || Math.abs(tail.slopeRight - surfaceInset) <= tolerance);
        if (!endsFlat) return -1;
        Asset first = byFile.get(chain.get(0));
        int maxError = down ? Math.abs(first.slopeLeft - surfaceInset)
                : Math.abs(first.slopeLeft - cell);
        for (int i = 1; i < chain.size(); i++) {
            Asset previous = byFile.get(chain.get(i - 1));
            Asset candidate = byFile.get(chain.get(i));
            maxError = Math.max(maxError,
                    Math.abs(previous.slopeRight - candidate.slopeLeft));
        }
        maxError = Math.max(maxError, down ? Math.abs(tail.slopeRight - cell)
                : Math.abs(tail.slopeRight - surfaceInset));
        files.clear();
        files.addAll(chain);
        return maxError;
    }

    private static void buildMacroSlopeSupports(List<Asset> assets, TileSet set) {
        set.groundSlopeDownSupport.clear();
        set.groundSlopeUpSupport.clear();
        if (set.groundSlopeDown.size() == 2) {
            File first = siblingPart(assets, set.groundSlopeDown.get(0), 3);
            File second = siblingPart(assets, set.groundSlopeDown.get(1), 4);
            if (first != null && second != null) {
                set.groundSlopeDownSupport.add(first);
                set.groundSlopeDownSupport.add(second);
            }
        }
        if (set.groundSlopeUp.size() == 2) {
            File first = siblingPart(assets, set.groundSlopeUp.get(0), 1);
            File second = siblingPart(assets, set.groundSlopeUp.get(1), 3);
            if (first != null && second != null) {
                set.groundSlopeUpSupport.add(first);
                set.groundSlopeUpSupport.add(second);
            }
        }
        if (set.groundSlopeDownSupport.size() != set.groundSlopeDown.size())
            set.groundSlopeDownSupport.clear();
        if (set.groundSlopeUpSupport.size() != set.groundSlopeUp.size())
            set.groundSlopeUpSupport.clear();
        if (!set.groundSlopeDown.isEmpty() && !set.groundSlopeUp.isEmpty()
                && set.groundSlopeDownSupport.isEmpty()
                && set.groundSlopeUpSupport.isEmpty())
            set.sealSlopeUnderlay = true;
    }

    private static File siblingPart(List<Asset> assets, File source, int wantedPart) {
        int sourcePart = slopePart(source);
        if (source == null || sourcePart < 0) return null;
        String family = slopePartFamily(source);
        for (Asset asset : assets)
            if (asset.file.getParentFile().equals(source.getParentFile())
                    && slopePart(asset.file) == wantedPart
                    && family.equals(slopePartFamily(asset.file)))
                return asset.file;
        return null;
    }

    private static int slopePart(File file) {
        if (file == null) return -1;
        String name = file.getName().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("part[-_ ]*0*([0-9]+)").matcher(name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static boolean isFourPartSlopeFamily(Asset source, List<Asset> assets) {
        if (source == null || slopePart(source.file) < 0) return false;
        boolean[] found = new boolean[5];
        String family = slopePartFamily(source.file);
        for (Asset asset : assets) {
            if (!asset.file.getParentFile().equals(source.file.getParentFile())
                    || !family.equals(slopePartFamily(asset.file))) continue;
            int part = slopePart(asset.file);
            if (part >= 1 && part <= 4) found[part] = true;
        }
        return found[1] && found[2] && found[3] && found[4];
    }

    private static String slopePartFamily(File file) {
        if (file == null) return "";
        return file.getName().toLowerCase(Locale.ROOT)
                .replaceAll("part[-_ ]*0*[0-9]+", "part")
                .replaceAll("[^a-z]+", "");
    }

    private static int referenceSurfaceInset(List<File> surfaces, Map<File, Asset> byFile) {
        if (surfaces == null || surfaces.isEmpty()) return 0;
        ArrayList<Integer> values = new ArrayList<Integer>();
        for (File file : surfaces) {
            Asset asset = byFile.get(file);
            if (asset == null) continue;
            values.add(Math.min(asset.slopeLeft, asset.slopeRight));
        }
        if (values.isEmpty()) return 0;
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    private static int contourDelta(Asset asset) {
        return asset == null ? Integer.MAX_VALUE : Math.abs(asset.slopeRight - asset.slopeLeft);
    }

    private static void buildGroundFamilies(List<Asset> assets, TileSet owner, int cell) {
        LinkedHashMap<String, List<Asset>> grouped = new LinkedHashMap<String, List<Asset>>();
        for (Asset asset : assets) {
            String family = firstPathSegment(asset.relative);
            if (family.isEmpty()) continue;
            List<Asset> members = grouped.get(family);
            if (members == null) {
                members = new ArrayList<Asset>();
                grouped.put(family, members);
            }
            members.add(asset);
        }
        if (grouped.size() < 2) return;

        ArrayList<TileSet> coherent = new ArrayList<TileSet>();
        for (Map.Entry<String, List<Asset>> entry : grouped.entrySet()) {
            TileSet family = new TileSet();
            family.biome = owner.biome;
            family.root = owner.root;
            family.groundFamily = entry.getKey();
            family.tilePixels = cell;
            family.scannedPngs = entry.getValue().size();
            family.themeProfile = owner.themeProfile;
            family.stackedSafeBandSlopes = owner.stackedSafeBandSlopes;
            family.missingDiagonalInnerCorners = owner.missingDiagonalInnerCorners;
            family.pixelLockedInnerCornerOverlays = owner.pixelLockedInnerCornerOverlays;
            family.widthSpecificFloatingIslands = owner.widthSpecificFloatingIslands;
            family.alphaTopFloatingIslandCollision = owner.alphaTopFloatingIslandCollision;
            family.snowOnlyFloatingIslands = owner.snowOnlyFloatingIslands;
            assignGroundRoles(entry.getValue(), family);
            selectContinuousSlopePairs(entry.getValue(), family, cell);
            family.surfaceMaterial = detectSurfaceMaterial(
                    owner.themeProfile, owner.biome, family.groundFamily,
                    entry.getValue());
            if (!family.groundSurface.isEmpty() && family.strictGroundRoles
                    && !family.groundLeft.isEmpty() && !family.groundRight.isEmpty())
                coherent.add(family);
        }
        if (coherent.size() < 2) return;
        for (TileSet family : coherent) {
            family.slopeRunGapTiles = 1;
            family.sealSlopeUnderlay = true;
        }
        owner.slopeRunGapTiles = 1;
        owner.sealSlopeUnderlay = true;
        Collections.sort(coherent, new Comparator<TileSet>() {
            @Override public int compare(TileSet a, TileSet b) {
                return a.groundFamily.compareToIgnoreCase(b.groundFamily);
            }
        });
        owner.groundFamilies.addAll(coherent);
        owner.warnings.add("Detected " + coherent.size()
                + " coherent ground palettes; one palette is locked per map seed.");
    }

    private static String firstPathSegment(String relative) {
        if (relative == null) return "";
        String value = relative.replace('\\', '/');
        int slash = value.indexOf('/');
        return slash <= 0 ? "" : value.substring(0, slash);
    }

    private static void inheritSharedThemeResources(TileSet owner) {
        for (TileSet family : owner.groundFamilies) {
            family.themeProfile = owner.themeProfile;
            family.stackedSafeBandSlopes = owner.stackedSafeBandSlopes;
            family.missingDiagonalInnerCorners = owner.missingDiagonalInnerCorners;
            family.pixelLockedInnerCornerOverlays = owner.pixelLockedInnerCornerOverlays;
            family.widthSpecificFloatingIslands = owner.widthSpecificFloatingIslands;
            family.alphaTopFloatingIslandCollision = owner.alphaTopFloatingIslandCollision;
            family.snowOnlyFloatingIslands = owner.snowOnlyFloatingIslands;
            if (owner.themeProfile != null
                    && owner.themeProfile.hasExplicitSurfaceMaterial())
                family.surfaceMaterial = owner.themeProfile.isIceSurface()
                        ? CustomMapDocument.SURFACE_ICE
                        : CustomMapDocument.SURFACE_NORMAL;
            family.water.addAll(owner.water);
            family.waterSurface.addAll(owner.waterSurface);
            family.waterFill.addAll(owner.waterFill);
            family.trees.addAll(owner.trees);
            family.props.addAll(owner.props);
            family.backgrounds.addAll(owner.backgrounds);
            family.innerCornerJunctions.putAll(owner.innerCornerJunctions);
            family.floatingIslandSpans.putAll(owner.floatingIslandSpans);
            for (Map.Entry<String, IceSurfaceAsset> entry : owner.iceSurfaceAssets.entrySet())
                family.iceSurfaceAssets.put(entry.getKey(), entry.getValue());
            for (Map.Entry<String, List<File>> entry : owner.vfxAssets.entrySet())
                family.vfxAssets.put(entry.getKey(), new ArrayList<File>(entry.getValue()));
            family.ignoredPngs = owner.ignoredPngs;
            family.filteredPngs += owner.filteredPngs;
            family.warnings.add(0, "Ground palette locked to '" + family.groundFamily + "'.");
        }
    }

    private static void applySurfaceRenderProfile(TileSet set) {
        if (set == null || set.themeProfile == null || set.themeProfile.surface == null) return;
        set.stackedSafeBandSlopes = "stacked-safe-band".equals(
                set.themeProfile.surface.slopeRenderMode);
        set.missingDiagonalInnerCorners = "missing-diagonal".equals(
                set.themeProfile.surface.innerCornerSemantics);
        set.pixelLockedInnerCornerOverlays = "pixel-locked-vertex-overlay".equals(
                set.themeProfile.surface.innerCornerRenderMode);
        set.embeddedBankIceBridge = "embedded-bank-socket".equals(
                set.themeProfile.surface.iceBridgeRenderMode);
        set.iceBridgeSocketInsetPixels = Math.max(1, Math.min(256,
                set.themeProfile.surface.iceBridgeSocketInsetPixels));
        set.widthSpecificFloatingIslands = "width-specific-span".equals(
                set.themeProfile.surface.floatingIslandRenderMode);
        set.alphaTopFloatingIslandCollision = "alpha-top-surface".equals(
                set.themeProfile.surface.floatingIslandCollisionMode);
        set.snowOnlyFloatingIslands = "snow-only".equals(
                set.themeProfile.surface.floatingIslandMaterialMode);
    }

    private static boolean isFloatingIslandSpan(String relative) {
        String value = normalized(relative);
        return hasSegment(value, "floating_islands")
                && value.matches(".*/island_span_0?[2-6]\\.png/.*");
    }

    private static void registerFloatingIslandSpan(TileSet set, File file,
                                                    String relative) {
        if (set == null || file == null) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)island[-_ ]span[-_ ]0*([2-6])\\.png")
                .matcher(file.getName());
        if (!matcher.matches()) return;
        int span = Integer.parseInt(matcher.group(1));
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null || !image.getColorModel().hasAlpha()) {
                set.warnings.add(relative + ": floating-island span must be RGBA; ignored.");
                return;
            }
            if (image.getHeight() <= 0 || image.getWidth() != span * image.getHeight()) {
                set.warnings.add(relative + ": expected a " + span
                        + "x1 tile aspect ratio; ignored.");
                return;
            }
            set.floatingIslandSpans.put(span, file);
        } catch (Throwable t) {
            set.warnings.add(relative + ": unreadable floating-island span; ignored.");
        }
    }

    private static void validateFloatingIslandSpans(TileSet set, int cell) {
        java.util.Iterator<Map.Entry<Integer, File>> iterator =
                set.floatingIslandSpans.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, File> entry = iterator.next();
            try {
                BufferedImage image = ImageIO.read(entry.getValue());
                if (image == null || image.getHeight() != cell
                        || image.getWidth() != entry.getKey() * cell) {
                    set.warnings.add(entry.getValue().getName()
                            + ": span dimensions do not match the " + cell
                            + "px terrain cell; ignored.");
                    iterator.remove();
                }
            } catch (Throwable t) {
                iterator.remove();
            }
        }
        if (set.widthSpecificFloatingIslands
                && set.floatingIslandSpans.size() != 5)
            set.errors.add("Width-specific floating islands require spans 02 through 06.");
    }

    private static boolean isInnerCornerJunction(String relative) {
        String value = normalized(relative);
        return hasSegment(value, "junctions") && value.contains("junction_inner_");
    }

    private static void registerInnerCornerJunction(TileSet set, File file) {
        if (set == null || file == null) return;
        String name = normalized(file.getName());
        String family = name.contains("/platform_") ? "platform" : "block";
        String corner = name.contains("top_left") ? "top-left"
                : name.contains("top_right") ? "top-right"
                : name.contains("bottom_left") ? "bottom-left"
                : name.contains("bottom_right") ? "bottom-right" : "";
        if (!corner.isEmpty()) set.innerCornerJunctions.put(family + ":" + corner, file);
    }

    private static void collectPngs(File directory, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null || child.isHidden() || child.getName().startsWith(".")) continue;
            if (child.isDirectory()) {

                if (isNonRuntimeDirectory(child)) continue;
                collectPngs(child, out);
            }
            else if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".png")) out.add(child);
        }
    }

    private static boolean isNonRuntimeDirectory(File directory) {
        if (directory == null) return false;
        String name = directory.getName().trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return "review".equals(name) || "_review".equals(name)
                || "qa".equals(name) || "_qa".equals(name)
                || "__pycache__".equals(name);
    }

    private static Asset inspect(File file, String relative, Kind kind, List<String> warnings) {
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                warnings.add(relative + ": unreadable PNG; ignored.");
                return null;
            }
            boolean transparent = kind == Kind.PROP
                    ? image.getColorModel().hasAlpha()
                    : (kind == Kind.TREE || kind == Kind.BACKGROUND) && hasTransparency(image);
            int[] slope = slopeContour(image);
            BackgroundAnalysis background = kind == Kind.BACKGROUND
                    ? analyzeBackground(image) : BackgroundAnalysis.full(image);
            return new Asset(file, relative, kind, image.getWidth(), image.getHeight(), transparent,
                    hasSegment(relative, "ground"), slope[0], slope[1], slope[2], slope[3],
                    background);
        } catch (Throwable t) {
            warnings.add(relative + ": unreadable PNG; ignored.");
            return null;
        }
    }

    private static int[] slopeContour(BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) return new int[]{0, 0, 0, 0};
        int band = Math.max(2, Math.round(image.getWidth() * .03f));
        int normalizedLeft = edgeContour(image, 0, Math.min(image.getWidth(), band));
        int normalizedRight = edgeContour(image, Math.max(0, image.getWidth() - band),
                image.getWidth());
        int delta = normalizedRight - normalizedLeft;
        if (Math.abs(delta) < Math.max(8, image.getHeight() / 8)) delta = 0;
        return new int[]{delta, (normalizedLeft + normalizedRight) / 2,
                normalizedLeft, normalizedRight};
    }

    private static int edgeContour(BufferedImage image, int fromX, int toX) {
        ArrayList<Integer> values = new ArrayList<Integer>();
        for (int x = fromX; x < toX; x++) {
            int first = image.getHeight();
            for (int y = 0; y < image.getHeight(); y++)
                if (((image.getRGB(x, y) >>> 24) & 0xff) > 16) {
                    first = y;
                    break;
                }
            values.add(first);
        }
        if (values.isEmpty()) return image.getHeight();
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    private static int dominantSquareSize(List<Asset> candidates) {
        HashMap<Integer, Integer> counts = new HashMap<Integer, Integer>();
        for (Asset asset : candidates) if (asset.width == asset.height && asset.width > 0) {
            Integer count = counts.get(asset.width);
            counts.put(asset.width, count == null ? 1 : count + 1);
        }
        int bestSize = -1, bestCount = -1;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount
                    || (entry.getValue() == bestCount && entry.getKey() < bestSize)) {
                bestSize = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestSize;
    }

    private static Kind classify(String relative) {
        String value = normalized(relative);
        if (any(value, "tilesheet", "tile_sheet", "atlas")) return Kind.IGNORE;
        if (hasSegment(value, "ice_surface")) return Kind.ICE_SURFACE;
        if (hasSegment(value, "vfx") && vfxKind(value) != null) return Kind.VFX;
        if (hasSegment(value, "environmental_props"))
            return hasSegment(value, "trees") ? Kind.TREE : Kind.PROP;
        if (hasSegment(value, "platformer_props")) return Kind.PROP;
        if (hasSegment(value, "background") || any(value, "sky", "cloud", "mountain", "sun",
                "parallax", "foreground_mountain", "distant", "backdrop")) return Kind.BACKGROUND;
        if (hasSegment(value, "water") || any(value, "water", "liquid", "river", "ocean", "lake"))
            return Kind.WATER;
        if (hasSegment(value, "tree") || any(value, "tree", "bush", "grass", "flower", "plant",
                "foliage", "vegetation", "shrub", "log")) return Kind.TREE;

        if (any(value, "outer_corner", "inner_corner", "top_left_edge", "top_right_edge",
                "bottom_left_edge", "bottom_right_edge", "left_edge_repeating",
                "right_edge_repeating", "color.png", "colour.png"))
            return Kind.GROUND;
        if (hasSegment(value, "ground") || any(value, "ground", "terrain", "tile", "platform",
                "block", "floor", "island", "pillar", "underground", "fill", "track", "slope"))
            return Kind.GROUND;
        return Kind.IGNORE;
    }

    private static String vfxKind(String relative) {
        String value = normalized(relative);
        if (value.contains("/vfx/dust/")) return "dust";
        if (value.contains("/vfx/splash/")) return "splash";
        if (value.contains("/vfx/land/")) return "land";
        if (value.contains("/vfx/edge/")) return "edge";
        if (value.contains("/vfx/ambient/")) return "ambient";
        if (value.contains("/vfx/ice_break/") || value.contains("/vfx/icebreak/"))
            return "ice-break";
        return null;
    }

    private static void registerIceSurface(TileSet set, Asset asset) {
        if (set == null || asset == null || asset.file == null) return;
        String key = fileStem(asset.relative).toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return;
        IceSurfaceAsset record = set.iceSurfaceAssets.get(key);
        if (record == null) {
            record = new IceSurfaceAsset();
            record.sourceKey = key;
            record.width = asset.width;
            record.height = asset.height;
            set.iceSurfaceAssets.put(key, record);
        }
        String path = normalized(asset.relative);
        if (hasSegment(path, "base")) record.base = asset.file;
        else if (hasSegment(path, "crack_01")) record.crack1 = asset.file;
        else if (hasSegment(path, "crack_02")) record.crack2 = asset.file;
        else if (hasSegment(path, "crack_03")) record.crack3 = asset.file;
    }

    private static PropAsset propAsset(Asset asset) {
        String value = normalized(asset.relative);
        String source = asset.relative.replace('\\', '/');
        String stem = fileStem(source);
        boolean bush = value.contains("/bushes/bush_");
        boolean grass = value.contains("/bushes/grass_");
        boolean flower = value.contains("/flowers/");
        boolean flowerPart = flower && any(value, "_head_", "stem", "/leaf.png", "/leaf_small.png");
        boolean completeFlower = flower && !flowerPart;
        boolean log = value.endsWith("/log.png/");
        boolean sign = value.contains("/platformer_props/sign_post_");
        boolean crate = value.contains("/platformer_props/trampoline/crate_");
        boolean random = bush || grass || completeFlower || log || sign || crate;

        PropAsset out = new PropAsset(asset.file, source, stablePropId(source),
                asset.width, asset.height);
        out.group = value.contains("/platformer_props/") ? "forge-prop" : "ground-flora";
        out.role = random ? "DECOR_VARIANT" : "EXPLICIT_ONLY";
        out.logicalId = out.id;
        out.randomEligible = random;
        if (bush) {
            String family = stem.replaceFirst("(?i)_green_[0-9]+$", "")
                    .replaceFirst("(?i)^bush_", "");
            out.logicalId = "volcano.decor.bush." + family;
            out.weight = variantWeight(stem, 1.30f, 1f, .65f);
            out.minGapTiles = 2f; out.maxCount = 6;
            out.minScalePercent = 80; out.maxScalePercent = 110;
            out.maxWidthTiles = 2.5f; out.maxHeightTiles = 1.35f;
        } else if (grass) {
            out.logicalId = "volcano.decor.grass";
            out.weight = variantWeight(stem, 1.35f, 1f, .70f);
            out.minGapTiles = 1f; out.maxCount = 10;
            out.minScalePercent = 80; out.maxScalePercent = 115;
            out.maxWidthTiles = 1.5f; out.maxHeightTiles = .8f;
        } else if (completeFlower) {
            String[] parts = stem.toLowerCase(Locale.ROOT).split("_");
            String color = parts.length > 0 ? parts[0] : "sulfur";
            String view = stem.toLowerCase(Locale.ROOT).contains("_side_") ? "side" : "front";
            boolean large = stem.toLowerCase(Locale.ROOT).endsWith("_large");
            String size = large ? "large" : "small";
            out.logicalId = "volcano.decor.geothermal-flower." + color + "." + view + "." + size;
            out.weight = flowerWeight(color, view, large);
            out.minGapTiles = large ? 4f : 2f; out.maxCount = large ? 2 : 3;
            out.minScalePercent = large ? 72 : 85;
            out.maxScalePercent = large ? 95 : 115;
            out.maxWidthTiles = "side".equals(view) ? (large ? 1.2f : .8f)
                    : (large ? 1.2f : .9f);
            out.maxHeightTiles = "side".equals(view) ? (large ? 1.75f : 1.1f)
                    : (large ? 2.2f : 1.5f);
        } else if (log) {
            out.logicalId = "volcano.decor.basalt-log";
            out.weight = .32f; out.minGapTiles = 6f; out.maxCount = 2;
            out.minScalePercent = 75; out.maxScalePercent = 100;
            out.maxWidthTiles = 2.5f; out.maxHeightTiles = 1.2f;
        } else if (sign) {
            out.logicalId = "volcano.decor.forge-sign";
            out.weight = .10f; out.minGapTiles = 8f; out.maxCount = 2;
            out.minScalePercent = 75; out.maxScalePercent = 95;
            out.maxWidthTiles = 1.5f; out.maxHeightTiles = 2.2f;
        } else if (crate) {
            out.logicalId = "volcano.decor.forge-crate";
            out.weight = .18f; out.minGapTiles = 5f; out.maxCount = 3;
            out.minScalePercent = 70; out.maxScalePercent = 95;
            out.maxWidthTiles = 1.6f; out.maxHeightTiles = 1.5f;
        } else if (flowerPart) {
            out.role = "PART"; out.deferredReason = "modular-component";
            out.anchor = "CONNECTOR"; out.baseline = "INHERIT";
        }
        else if (any(value, "tileset_door", "chest_")) {
            out.role = any(value, "tileset_door") ? "STRUCTURE" : "STATE";
            out.deferredReason = any(value, "tileset_door")
                    ? "landmark-placement-deferred" : "interactive-state-deferred";
        } else if (any(value, "blank_block", "stone_block", "surprise_block")) {
            out.role = "STRUCTURE"; out.deferredReason = "collision-deferred";
            out.anchor = "CELL_CENTER"; out.baseline = "TERRAIN_OVERLAY";
            out.layer = "TERRAIN_OVERLAY";
            out.maxWidthTiles = out.maxHeightTiles = 1f;
        } else if (value.contains("/flag/")) {
            out.role = any(value, "flag_flag_", "flag_pole") ? "PART" : "STATE";
            out.deferredReason = any(value, "flag_flag_", "flag_pole")
                    ? "modular-component" : "animation-state-deferred";
            if ("PART".equals(out.role)) { out.anchor = "CONNECTOR"; out.baseline = "INHERIT"; }
        } else if (value.contains("/ladder/")) {
            out.role = "PART"; out.deferredReason = "traversal-deferred";
            out.anchor = "CONNECTOR"; out.baseline = "INHERIT";
        } else if (value.contains("/spinning_coin/")) {
            out.role = "FRAME"; out.deferredReason = "collectible-animation-deferred";
            out.anchor = "AIR_CENTER"; out.baseline = "WORLD_FRONT";
            out.layer = "WORLD_FRONT";
        } else if (value.contains("/trampoline/trampoline_")) {
            out.role = "STATE"; out.deferredReason = "physics-deferred";
        } else {
            out.deferredReason = "explicit-placement-deferred";
        }
        if (!random) { out.weight = 0f; out.maxCount = 0; }
        return out;
    }

    private static String fileStem(String path) {
        if (path == null) return "";
        String value = path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static float variantWeight(String stem, float first, float second, float third) {
        String value = stem == null ? "" : stem;
        return value.endsWith("_01") ? first : value.endsWith("_02") ? second : third;
    }

    private static float flowerWeight(String color, String view, boolean large) {
        float colorWeight = "yellow".equals(color) ? 1.15f
                : "blue".equals(color) ? 1f : .85f;
        return colorWeight * ("side".equals(view) ? .85f : 1f) * (large ? .55f : 1f);
    }

    private static String stablePropId(String sourceKey) {
        String value = sourceKey == null ? "prop" : sourceKey.toLowerCase(Locale.ROOT)
                .replace('\\', '/');
        int dot = value.lastIndexOf('.');
        if (dot > value.lastIndexOf('/')) value = value.substring(0, dot);
        value = value.replace('_', '-').replace('/', '.');
        value = value.replaceAll("[^a-z0-9.-]+", "-")
                .replaceAll("(^[.-]+|[.-]+$)", "");
        return "vprop." + (value.isEmpty() ? "asset" : value);
    }

    private static boolean isSafeGround(String relative) {
        String value = normalized(relative);
        boolean repeatable = any(value, "repeat", "repeating", "fill", "center", "centre",
                "middle", "single", "top_tile", "top-tile", "color");
        boolean specialized = any(value, "slope", "corner", "entrance", "entance", "duplicate",
                "passthrough", "pass-through");
        return repeatable && !specialized;
    }

    private static String relative(File root, File file) {
        String base = root.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.length() > base.length() ? path.substring(base.length() + 1) : file.getName();
    }

    private static String normalized(String value) {
        return ("/" + value.replace('\\', '/').toLowerCase(Locale.ROOT) + "/")
                .replace(' ', '_').replace('-', '_');
    }

    private static byte detectSurfaceMaterial(
            CustomMapDocument.ThemeProfile profile, String biome,
            String family, List<Asset> assets) {
        if (profile != null && profile.hasExplicitSurfaceMaterial())
            return profile.isIceSurface()
                    ? CustomMapDocument.SURFACE_ICE
                    : CustomMapDocument.SURFACE_NORMAL;
        if (family != null && !family.trim().isEmpty() && hasIceToken(family))
            return CustomMapDocument.SURFACE_ICE;
        if (assets != null)
            for (Asset asset : assets)
                if (asset != null && hasIceToken(asset.relative))
                    return CustomMapDocument.SURFACE_ICE;
        if (assets != null && !assets.isEmpty())
            return CustomMapDocument.SURFACE_NORMAL;
        if ((family == null || family.trim().isEmpty()) && hasIceToken(biome))
            return CustomMapDocument.SURFACE_ICE;
        return CustomMapDocument.SURFACE_NORMAL;
    }

    private static boolean hasIceToken(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String[] tokens = value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String token : tokens)
            if ("ice".equals(token) || "icy".equals(token)
                    || "frozen".equals(token) || "glacier".equals(token)
                    || "glacial".equals(token)) return true;
        return false;
    }

    private static boolean hasSegment(String value, String segment) {
        String normalized = normalized(value);
        return normalized.contains("/" + segment.toLowerCase(Locale.ROOT) + "/");
    }

    private static boolean any(String value, String... needles) {
        String normalized = normalized(value);
        for (String needle : needles)
            if (normalized.contains(needle.toLowerCase(Locale.ROOT).replace('-', '_'))) return true;
        return false;
    }

    private static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        int stepX = Math.max(1, image.getWidth() / 96);
        int stepY = Math.max(1, image.getHeight() / 96);
        for (int y = 0; y < image.getHeight(); y += stepY)
            for (int x = 0; x < image.getWidth(); x += stepX)
                if (((image.getRGB(x, y) >>> 24) & 0xff) < 250) return true;
        return ((image.getRGB(0, 0) >>> 24) & 0xff) < 250;
    }

    private static boolean isLavaProfile(CustomMapDocument.ThemeProfile profile) {
        return profile != null && profile.isLava();
    }

    private static String liquidName(CustomMapDocument.ThemeProfile profile) {
        return isLavaProfile(profile) ? "lava" : "water";
    }

    private static BackgroundAsset backgroundAsset(
            Asset asset, int widestCloud, CustomMapDocument.ThemeProfile profile) {
        String value = normalized(asset.relative);
        String role;
        if (any(value, "sky_gradient", "sky", "gradient", "backdrop")) role = "sky";
        else if (!profileLandmarkRole(value, profile).isEmpty())
            role = profileLandmarkRole(value, profile);
        else if (any(value, "sun")) role = "sun";
        else if (any(value, "cloud")) {
            float widthRatio = asset.width / (float) Math.max(1, widestCloud);
            role = widthRatio <= 0.34f ? "cloud-small"
                    : widthRatio <= 0.74f ? "cloud-medium" : "cloud-large";
        } else if (any(value, "far", "distant"))
            role = "far-panorama";
        else if (any(value, "dark_green", "dark-green", "dark green"))
            role = "near-mountain";
        else if (any(value, "foreground", "near", "mountain", "hill"))
            role = asset.lightness < 0.47f ? "near-mountain" : "mid-mountain";
        else if (asset.contentWidth() >= asset.contentHeight() * 2)
            role = "far-panorama";
        else if (!asset.hasTransparency) role = "sky";
        else role = asset.contentWidth() >= asset.contentHeight() * 2
                    ? "far-panorama" : "mid-mountain";

        String palette = palette(value, asset);
        return new BackgroundAsset(asset.file, asset.relative, role, palette,
                asset.width, asset.height, asset.contentLeft, asset.contentTop,
                asset.contentRight, asset.contentBottom, asset.averageArgb,
                asset.hueDegrees, asset.saturation, asset.lightness);
    }

    private static String profileLandmarkRole(
            String value, CustomMapDocument.ThemeProfile profile) {
        if (any(value, "landmark_volcano", "volcano_landmark", "erupting_volcano"))
            return "landmark-volcano";
        if (profile == null || profile.landmark == null
                || profile.landmark.role == null || profile.landmark.role.isEmpty()) return "";
        return any(value, "sun", "landmark") ? profile.landmark.role : "";
    }

    private static String palette(String value, Asset asset) {
        if (any(value, "yellow", "gold", "sand")) return "yellow";
        if (any(value, "purple", "violet", "lavender")) return "purple";
        if (any(value, "dark_green", "dark-green", "dark green")) return "green-dark";
        if (any(value, "light_green", "light-green", "light green")) return "green-light";
        if (asset.saturation > 0.18f && asset.hueDegrees >= 65f && asset.hueDegrees <= 175f)
            return asset.lightness < 0.48f ? "green-dark" : "green-light";
        if (asset.saturation > 0.18f && asset.hueDegrees >= 35f && asset.hueDegrees < 65f)
            return "yellow";
        if (asset.saturation > 0.12f && asset.hueDegrees >= 250f && asset.hueDegrees <= 315f)
            return "purple";
        return "neutral";
    }

    private static int backgroundRoleOrder(String role) {
        if ("sky".equals(role)) return 0;
        if ("sun".equals(role)) return 10;
        if (role != null && role.startsWith("cloud-")) return 20;
        if (role != null && role.startsWith("landmark-")) return 25;
        if ("far-panorama".equals(role)) return 30;
        if ("mid-mountain".equals(role)) return 40;
        if ("near-mountain".equals(role)) return 50;
        return 60;
    }

    private static BackgroundAnalysis analyzeBackground(BufferedImage image) {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        long red = 0L, green = 0L, blue = 0L, count = 0L;
        int sampleStep = Math.max(1, (int) Math.sqrt(
                Math.max(1d, image.getWidth() * (double) image.getHeight() / 24000d)));
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                if (alpha <= 8) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (x % sampleStep == 0 && y % sampleStep == 0) {
                    red += (argb >>> 16) & 0xff;
                    green += (argb >>> 8) & 0xff;
                    blue += argb & 0xff;
                    count++;
                }
            }
        }
        if (maxX < 0 || maxY < 0)
            return BackgroundAnalysis.full(image);
        int r = count <= 0 ? 255 : (int) (red / count);
        int g = count <= 0 ? 255 : (int) (green / count);
        int b = count <= 0 ? 255 : (int) (blue / count);
        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
        float lightness = (Math.max(r, Math.max(g, b)) + Math.min(r, Math.min(g, b))) / 510f;
        return new BackgroundAnalysis(minX, minY, maxX, maxY,
                0xff000000 | (r << 16) | (g << 8) | b,
                hsb[0] * 360f, hsb[1], lightness);
    }

    private enum Kind { GROUND, WATER, TREE, PROP, BACKGROUND, ICE_SURFACE, VFX, IGNORE }

    private static final class Asset {
        final File file;
        final String relative;
        final Kind kind;
        final int width, height;
        final boolean hasTransparency;
        final boolean explicitGround;
        final int slopeDelta;
        final int slopeMean;
        final int slopeLeft;
        final int slopeRight;
        final int contentLeft, contentTop, contentRight, contentBottom;
        final int averageArgb;
        final float hueDegrees, saturation, lightness;

        Asset(File file, String relative, Kind kind, int width, int height,
              boolean hasTransparency, boolean explicitGround, int slopeDelta, int slopeMean,
              int slopeLeft, int slopeRight, BackgroundAnalysis background) {
            this.file = file;
            this.relative = relative;
            this.kind = kind;
            this.width = width;
            this.height = height;
            this.hasTransparency = hasTransparency;
            this.explicitGround = explicitGround;
            this.slopeDelta = slopeDelta;
            this.slopeMean = slopeMean;
            this.slopeLeft = slopeLeft;
            this.slopeRight = slopeRight;
            this.contentLeft = background.left;
            this.contentTop = background.top;
            this.contentRight = background.right;
            this.contentBottom = background.bottom;
            this.averageArgb = background.averageArgb;
            this.hueDegrees = background.hueDegrees;
            this.saturation = background.saturation;
            this.lightness = background.lightness;
        }

        int contentWidth() { return Math.max(1, contentRight - contentLeft + 1); }
        int contentHeight() { return Math.max(1, contentBottom - contentTop + 1); }
    }

    private static final class BackgroundAnalysis {
        final int left, top, right, bottom;
        final int averageArgb;
        final float hueDegrees, saturation, lightness;

        BackgroundAnalysis(int left, int top, int right, int bottom, int averageArgb,
                           float hueDegrees, float saturation, float lightness) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.averageArgb = averageArgb;
            this.hueDegrees = hueDegrees;
            this.saturation = saturation;
            this.lightness = lightness;
        }

        static BackgroundAnalysis full(BufferedImage image) {
            return new BackgroundAnalysis(0, 0, Math.max(0, image.getWidth() - 1),
                    Math.max(0, image.getHeight() - 1), 0xffffffff, 0f, 0f, 1f);
        }
    }

    public static final class TileSet {
        public String biome;
        public File root;
        private long catalogDirectoryStamp;
        public int tilePixels;
        public int scannedPngs;
        public int ignoredPngs;
        public int filteredPngs;

        public int surfaceInsetPixels;

        public int maxSlopeSeamErrorPixels;

        public int slopeRunGapTiles;

        public boolean sealSlopeUnderlay;

        public boolean stackedSafeBandSlopes;

        public boolean missingDiagonalInnerCorners;

        public boolean pixelLockedInnerCornerOverlays;

        public boolean embeddedBankIceBridge;

        public int iceBridgeSocketInsetPixels = 72;

        public boolean widthSpecificFloatingIslands;

        public boolean alphaTopFloatingIslandCollision;

        public boolean snowOnlyFloatingIslands;

        public boolean strictGroundRoles;

        public String groundFamily = "";

        public CustomMapDocument.ThemeProfile themeProfile;

        public byte surfaceMaterial = CustomMapDocument.SURFACE_NORMAL;

        public final List<TileSet> groundFamilies = new ArrayList<TileSet>();
        public final List<File> ground = new ArrayList<File>();
        public final List<File> water = new ArrayList<File>();
        public final List<File> trees = new ArrayList<File>();
        public final List<PropAsset> props = new ArrayList<PropAsset>();
        public final List<BackgroundAsset> backgrounds = new ArrayList<BackgroundAsset>();
        public final Map<String, List<File>> vfxAssets =
                new LinkedHashMap<String, List<File>>();
        public final Map<String, File> innerCornerJunctions =
                new LinkedHashMap<String, File>();
        public final Map<Integer, File> floatingIslandSpans =
                new LinkedHashMap<Integer, File>();
        public final Map<String, IceSurfaceAsset> iceSurfaceAssets =
                new LinkedHashMap<String, IceSurfaceAsset>();

        public final List<File> groundSurface = new ArrayList<File>();
        public final List<File> groundFill = new ArrayList<File>();
        public final List<File> groundLeft = new ArrayList<File>();
        public final List<File> groundRight = new ArrayList<File>();
        public final List<File> groundBottom = new ArrayList<File>();
        public final List<File> groundTopLeft = new ArrayList<File>();
        public final List<File> groundTopRight = new ArrayList<File>();
        public final List<File> groundBottomLeft = new ArrayList<File>();
        public final List<File> groundBottomRight = new ArrayList<File>();
        public final List<File> groundInnerTopLeft = new ArrayList<File>();
        public final List<File> groundInnerTopRight = new ArrayList<File>();
        public final List<File> groundInnerBottomLeft = new ArrayList<File>();
        public final List<File> groundInnerBottomRight = new ArrayList<File>();
        public final List<File> groundPlatformCenter = new ArrayList<File>();
        public final List<File> groundPlatformLeft = new ArrayList<File>();
        public final List<File> groundPlatformRight = new ArrayList<File>();
        public final List<File> groundPlatformSingle = new ArrayList<File>();
        public final List<File> groundSlopeUp = new ArrayList<File>();
        public final List<File> groundSlopeDown = new ArrayList<File>();

        public final List<File> groundSteepSlopeUp = new ArrayList<File>();
        public final List<File> groundSteepSlopeDown = new ArrayList<File>();

        public final List<File> groundStepJunctionLeft = new ArrayList<File>();
        public final List<File> groundStepJunctionRight = new ArrayList<File>();

        public final List<File> groundSlopeUpSupport = new ArrayList<File>();
        public final List<File> groundSlopeDownSupport = new ArrayList<File>();
        public final List<File> groundSteepSlopeUpSupport = new ArrayList<File>();
        public final List<File> groundSteepSlopeDownSupport = new ArrayList<File>();

        public final List<File> groundSlopeUpEndpointSupport = new ArrayList<File>();
        public final List<File> groundSlopeDownEndpointSupport = new ArrayList<File>();
        public final List<File> groundSteepSlopeUpEndpointSupport = new ArrayList<File>();
        public final List<File> groundSteepSlopeDownEndpointSupport = new ArrayList<File>();
        public final List<File> waterSurface = new ArrayList<File>();
        public final List<File> waterFill = new ArrayList<File>();
        public final List<String> errors = new ArrayList<String>();
        public final List<String> warnings = new ArrayList<String>();

        public boolean isUsable(double waterDensity, double treeDensity) {
            return isUsable(waterDensity, treeDensity, 0d);
        }

        public boolean isUsable(double waterDensity, double treeDensity, double propDensity) {
            return errors.isEmpty() && !ground.isEmpty()
                    && (waterDensity <= 0.0 || !water.isEmpty())
                    && (treeDensity <= 0.0 || !trees.isEmpty())
                    && (propDensity <= 0.0 || supportsProps());
        }

        public boolean isLavaTheme() {
            return isLavaProfile(themeProfile);
        }

        public boolean isIceSurfaceTheme() {
            return surfaceMaterial == CustomMapDocument.SURFACE_ICE;
        }

        public boolean supportsIceSurfaceDensity() {
            if (!hasCompleteIceOverlayAssets()) return false;
            if (groundFamilies.isEmpty())
                return surfaceMaterial == CustomMapDocument.SURFACE_NORMAL;
            for (TileSet family : groundFamilies) {
                if (family != null
                        && family.surfaceMaterial == CustomMapDocument.SURFACE_NORMAL)
                    return true;
            }
            return false;
        }

        public String liquidDisplayName() {
            return liquidName(themeProfile);
        }

        public int vfxAssetCount() {
            int count = 0;
            for (List<File> files : vfxAssets.values()) count += files.size();
            return count;
        }

        public boolean supportsBreakableIceAssets() {
            return hasCompleteIceOverlayAssets();
        }

        private boolean hasCompleteIceOverlayAssets() {
            if (iceSurfaceAssets.isEmpty()) return false;
            int complete = 0;
            for (IceSurfaceAsset asset : iceSurfaceAssets.values())
                if (asset != null && asset.isComplete()) complete++;
            List<File> frames = vfxAssets.get("ice-break");
            return complete > 0 && frames != null && frames.size() == 8;
        }

        public int randomPropCount() {
            int count = 0;
            for (PropAsset prop : props) if (prop != null && prop.randomEligible) count++;
            return count;
        }

        public boolean supportsProps() {
            return randomPropCount() > 0 && themeProfile != null
                    && themeProfile.profileId != null
                    && !themeProfile.profileId.trim().isEmpty();
        }

        public CustomMapDocument.PropManifest propManifest() {
            CustomMapDocument.PropManifest manifest = new CustomMapDocument.PropManifest();
            for (PropAsset prop : props) {
                if (prop == null) continue;
                CustomMapDocument.PropAssetRef ref = new CustomMapDocument.PropAssetRef();
                ref.id = prop.id;
                ref.logicalId = prop.logicalId;
                ref.sourceKey = prop.sourceKey;
                ref.group = prop.group;
                ref.role = prop.role;
                ref.anchor = prop.anchor;
                ref.baseline = prop.baseline;
                ref.layer = prop.layer;
                ref.collision = "NONE";
                ref.interaction = "NONE";
                ref.decorative = true;
                ref.randomEligible = prop.randomEligible;
                ref.deferredReason = prop.deferredReason;
                ref.sourceWidth = prop.width;
                ref.sourceHeight = prop.height;
                ref.weight = prop.weight;
                ref.maxCount = prop.maxCount;
                ref.minGapTiles = prop.minGapTiles;
                ref.minScalePercent = prop.minScalePercent;
                ref.maxScalePercent = prop.maxScalePercent;
                ref.maxWidthTiles = prop.maxWidthTiles;
                ref.maxHeightTiles = prop.maxHeightTiles;
                manifest.assets.add(ref);
            }
            return manifest;
        }

        public boolean supportsSlopes() {
            return !groundSlopeUp.isEmpty() && !groundSlopeDown.isEmpty();
        }

        public boolean supportsSteepSlopes() {
            return !groundSteepSlopeUp.isEmpty()
                    && !groundSteepSlopeDown.isEmpty();
        }

        public boolean hasMacroSlopeSupports() {
            return groundSlopeUpSupport.size() == groundSlopeUp.size()
                    && groundSlopeDownSupport.size() == groundSlopeDown.size()
                    && !groundSlopeUpSupport.isEmpty() && !groundSlopeDownSupport.isEmpty();
        }

        public boolean hasEndpointSlopeSupports() {
            return groundSlopeUpEndpointSupport.size() == 1
                    && groundSlopeDownEndpointSupport.size() == 1;
        }

        public TileSet resolveGroundFamily(long seed) {
            if (groundFamilies.isEmpty()) return this;
            long value = seed;
            value ^= value >>> 33;
            return groundFamilies.get((int) Math.floorMod(value, groundFamilies.size()));
        }

        public TileSet resolveBaseGroundFamily(long seed) {
            if (supportsIceSurfaceDensity()) {
                TileSet firstNormal = null;
                for (TileSet family : groundFamilies) {
                    if (family == null
                            || family.surfaceMaterial == CustomMapDocument.SURFACE_ICE)
                        continue;
                    if (firstNormal == null) firstNormal = family;
                    if ("snow".equalsIgnoreCase(family.groundFamily)) return family;
                }
                if (firstNormal != null) return firstNormal;
            }
            return resolveGroundFamily(seed);
        }

        public String validationMessage(double waterDensity, double treeDensity) {
            return validationMessage(waterDensity, treeDensity, 0d);
        }

        public String validationMessage(double waterDensity, double treeDensity,
                                        double propDensity) {
            ArrayList<String> all = new ArrayList<String>(errors);
            if (waterDensity > 0.0 && water.isEmpty()) {
                if (isLavaTheme())
                    all.add("No lava PNG was auto-detected; use a path/file name containing water or liquid, or set lava to 0%.");
                else
                    all.add("No water PNG was auto-detected; use a path/file name containing water or set water to 0%.");
            }
            if (treeDensity > 0.0 && trees.isEmpty())
                all.add("No transparent tree/vegetation PNG was auto-detected; use tree/bush/grass/flower naming or set trees to 0%.");
            if (propDensity > 0.0 && !supportsProps())
                all.add("Decorative props require a theme profile and complete PNGs under environmental_props/platformer_props; otherwise set props to 0%.");
            StringBuilder sb = new StringBuilder();
            if (all.isEmpty()) sb.append("Ready\n");
            else for (String error : all) sb.append("- ").append(error).append('\n');
            for (String warning : warnings) sb.append("  ").append(warning).append('\n');
            return sb.toString().trim();
        }

        public String detectionSummary() {
            return "ground " + ground.size() + " (top " + groundSurface.size() + ", fill "
                    + groundFill.size() + ", slopes " + (groundSlopeUp.size() + groundSlopeDown.size())
                    + (supportsSlopes() ? ", seam " + maxSlopeSeamErrorPixels + "px" : "")
                    + (groundFamilies.isEmpty() ? "" : ", palettes " + groundFamilies.size())
                    + (groundFamily.isEmpty() ? "" : ", palette " + groundFamily)
                    + (isIceSurfaceTheme() ? ", ICE" : "")
                    + ") | " + liquidDisplayName() + " " + water.size() + " (top "
                    + waterSurface.size() + ", fill " + waterFill.size() + ") | tree "
                    + trees.size() + " | prop " + props.size() + " (random "
                    + randomPropCount() + ") | background " + backgrounds.size()
                    + " | vfx " + vfxAssetCount()
                    + (themeProfile == null ? "" : " [" + themeProfile.profileId + "]")
                    + " | " + tilePixels + "px cell";
        }

        @Override public String toString() {
            String liquidMetric = isLavaTheme() ? " L" : " W";
            return biome + " [G" + ground.size() + liquidMetric + water.size() + " T" + trees.size()
                    + (props.isEmpty() ? "" : " P" + props.size()) + "]"
                    + (errors.isEmpty() ? "" : " (invalid)");
        }
    }

    public static final class IceSurfaceAsset {
        public String sourceKey = "";
        public File base;
        public File crack1;
        public File crack2;
        public File crack3;
        public int width;
        public int height;

        public boolean isComplete() {
            return base != null && base.isFile()
                    && crack1 != null && crack1.isFile()
                    && crack2 != null && crack2.isFile()
                    && crack3 != null && crack3.isFile()
                    && width > 0 && height > 0;
        }
    }

    public static final class PropAsset {
        public final File file;
        public final String sourceKey;
        public final String id;
        public String logicalId;
        public String group = "environmental";
        public String role = "EXPLICIT_ONLY";
        public String anchor = "BOTTOM_CENTER";
        public String baseline = "ALPHA_BOTTOM_TO_DRY_SURFACE";
        public String layer = "BEHIND_ACTORS";
        public boolean randomEligible;
        public String deferredReason = "";
        public final int width, height;
        public float weight = 1f;
        public int maxCount = 1;
        public float minGapTiles = 1.5f;
        public int minScalePercent = 82, maxScalePercent = 112;
        public float maxWidthTiles = 2f, maxHeightTiles = 2f;

        PropAsset(File file, String sourceKey, String id, int width, int height) {
            this.file = file;
            this.sourceKey = sourceKey;
            this.id = id;
            this.logicalId = id;
            this.width = width;
            this.height = height;
        }
    }

    public static final class BackgroundAsset {
        public final File file;
        public final String sourceKey;
        public final String role;
        public final String palette;
        public final int width, height;
        public final int contentLeft, contentTop, contentRight, contentBottom;
        public final int averageArgb;
        public final float hueDegrees, saturation, lightness;

        BackgroundAsset(File file, String sourceKey, String role, String palette,
                        int width, int height, int contentLeft, int contentTop,
                        int contentRight, int contentBottom, int averageArgb,
                        float hueDegrees, float saturation, float lightness) {
            this.file = file;
            this.sourceKey = sourceKey == null ? "" : sourceKey.replace('\\', '/');
            this.role = role;
            this.palette = palette;
            this.width = width;
            this.height = height;
            this.contentLeft = contentLeft;
            this.contentTop = contentTop;
            this.contentRight = contentRight;
            this.contentBottom = contentBottom;
            this.averageArgb = averageArgb;
            this.hueDegrees = hueDegrees;
            this.saturation = saturation;
            this.lightness = lightness;
        }

        public int contentWidth() { return Math.max(1, contentRight - contentLeft + 1); }
        public int contentHeight() { return Math.max(1, contentBottom - contentTop + 1); }
    }
}
