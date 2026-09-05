package manualcontrol.custommap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CustomMapPalette {

    enum Category {
        TERRAIN("Terrain"), SLOPE("Slope"), LIQUID("Liquid"), ICE("Ice"),
        ISLAND("Floating island"), TREE("Tree"), PROP("Decoration"),
        BACKGROUND("Background"), ADVANCED("Advanced");

        final String label;
        Category(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    static final class Asset {
        final String id;
        final String name;
        final String theme;
        final String family;
        final Category category;
        final String role;
        final String material;
        final File file;
        final boolean supported;
        final String disabledReason;

        Asset(String id, String name, String theme, String family,
              Category category, String role, String material, File file,
              boolean supported, String disabledReason) {
            this.id = id;
            this.name = name;
            this.theme = theme;
            this.family = family;
            this.category = category;
            this.role = role;
            this.material = material;
            this.file = file;
            this.supported = supported;
            this.disabledReason = disabledReason == null ? "" : disabledReason;
        }

        boolean matches(String query) {
            if (query == null || query.trim().isEmpty()) return true;
            String q = query.trim().toLowerCase(Locale.ROOT);
            return name.toLowerCase(Locale.ROOT).contains(q)
                    || role.toLowerCase(Locale.ROOT).contains(q)
                    || family.toLowerCase(Locale.ROOT).contains(q)
                    || id.toLowerCase(Locale.ROOT).contains(q);
        }

        @Override public String toString() { return name; }
    }

    private CustomMapPalette() {}

    static List<Asset> scan(TileCatalog.TileSet rootSet, boolean advanced) {
        if (rootSet == null) return Collections.emptyList();
        ArrayList<Asset> out = new ArrayList<Asset>();
        Set<String> claimed = new HashSet<String>();
        List<TileCatalog.TileSet> families = rootSet.groundFamilies.isEmpty()
                ? Collections.singletonList(rootSet) : rootSet.groundFamilies;
        for (TileCatalog.TileSet family : families) {
            if (family == null) continue;
            String familyId = text(family.groundFamily, "default");
            String groundMaterial = family.surfaceMaterial == CustomMapDocument.SURFACE_ICE
                    ? CustomMapDocument.MATERIAL_ICE : CustomMapDocument.MATERIAL_NORMAL;
            File terrain = first(family.groundSurface, family.groundPlatformCenter,
                    family.ground, family.groundFill);
            addRepresentative(out, claimed, rootSet, terrain, Category.TERRAIN,
                    "terrain-auto", familyId, groundMaterial,
                    logicalName("Terrain", familyId));
            if (family.supportsSlopes())
                addRepresentative(out, claimed, rootSet,
                        middle(family.groundSlopeUp), Category.SLOPE,
                        "slope-auto", familyId, groundMaterial,
                        logicalName("Slope", familyId));
            File island = first(family.groundPlatformSingle,
                    family.groundPlatformCenter,
                    new ArrayList<File>(rootSet.floatingIslandSpans.values()));
            if (island != null)
                addRepresentative(out, claimed, rootSet, island, Category.ISLAND,
                        "island-auto", familyId, groundMaterial,
                        logicalName("Floating island", familyId));
        }

        String liquid = rootSet.isLavaTheme()
                ? CustomMapDocument.MATERIAL_LAVA : CustomMapDocument.MATERIAL_WATER;
        File liquidRepresentative = first(rootSet.waterSurface,
                rootSet.waterFill, rootSet.water);
        if (liquidRepresentative != null)
            addRepresentative(out, claimed, rootSet, liquidRepresentative,
                    Category.LIQUID, "liquid-auto", "liquid", liquid,
                    rootSet.isLavaTheme() ? "Lava" : "Water");

        File iceRepresentative = null;
        for (TileCatalog.IceSurfaceAsset ice : rootSet.iceSurfaceAssets.values()) {
            if (ice == null) continue;
            if (iceRepresentative == null || ice.sourceKey != null
                    && ice.sourceKey.contains("horizontal"))
                iceRepresentative = ice.base;
        }
        if (iceRepresentative != null)
            addRepresentative(out, claimed, rootSet, iceRepresentative,
                    Category.ICE, "ice-auto", "ice",
                    CustomMapDocument.MATERIAL_ICE, "Ice surface");
        add(out, claimed, rootSet, rootSet.trees, Category.TREE,
                "tree", "environment", CustomMapDocument.MATERIAL_NORMAL);
        Set<String> logicalProps = new HashSet<String>();
        for (TileCatalog.PropAsset prop : rootSet.props)
            if (prop != null && logicalProps.add(text(prop.logicalId, prop.id)))
                addOne(out, claimed, rootSet, prop.file,
                    Category.PROP, text(prop.role, "decoration"),
                    text(prop.group, "environment"),
                    CustomMapDocument.MATERIAL_NORMAL);
        for (TileCatalog.BackgroundAsset bg : rootSet.backgrounds)
            if (bg != null) addOne(out, claimed, rootSet, bg.file,
                    Category.BACKGROUND, text(bg.role, "background"),
                    text(bg.palette, "environment"),
                    CustomMapDocument.MATERIAL_NORMAL);
        for (List<File> frames : rootSet.vfxAssets.values())
            if (frames != null)
                for (File frame : frames)
                    if (frame != null) claimed.add(canonical(frame));

        if (advanced) {
            ArrayList<File> pngs = new ArrayList<File>();
            collectPng(rootSet.root, pngs);
            for (File file : pngs) {
                if (isEffectFile(rootSet, file)) continue;
                String key = canonical(file);
                if (claimed.contains(key)) continue;
                String id = stableId(rootSet, file);
                out.add(new Asset(id, displayName(file), rootSet.biome, "",
                        Category.ADVANCED, "unclassified",
                        CustomMapDocument.MATERIAL_NORMAL, file, false,
                        "This image has no complete, verified connector set and cannot be placed safely."));
            }
        }
        Collections.sort(out, new Comparator<Asset>() {
            @Override public int compare(Asset a, Asset b) {
                int category = a.category.ordinal() - b.category.ordinal();
                if (category != 0) return category;
                int role = a.role.compareToIgnoreCase(b.role);
                return role != 0 ? role : a.id.compareToIgnoreCase(b.id);
            }
        });
        return out;
    }

    private static void add(List<Asset> out, Set<String> claimed,
                            TileCatalog.TileSet set, List<File> files,
                            Category category, String role, String family,
                            String material) {
        if (files == null) return;
        for (File file : new LinkedHashSet<File>(files))
            addOne(out, claimed, set, file, category, role, family, material);
    }

    private static void addOne(List<Asset> out, Set<String> claimed,
                               TileCatalog.TileSet set, File file,
                               Category category, String role, String family,
                               String material) {
        if (file == null || !file.isFile()) return;
        String key = canonical(file);
        if (!claimed.add(key)) return;
        out.add(new Asset(stableId(set, file), displayName(file), set.biome,
                family, category, role, material, file, true, ""));
    }

    private static void addRepresentative(List<Asset> out, Set<String> claimed,
                                          TileCatalog.TileSet set, File file,
                                          Category category, String role,
                                          String family, String material,
                                          String name) {
        if (file == null || !file.isFile()) return;
        claimed.add(canonical(file));
        String id = stableId(set, file);
        out.add(new Asset(id, text(name, displayName(file)), set.biome,
                family, category, role, material, file, true, ""));
    }

    @SafeVarargs
    private static File first(List<File>... choices) {
        if (choices == null) return null;
        for (List<File> files : choices)
            if (files != null)
                for (File file : files) if (file != null && file.isFile()) return file;
        return null;
    }

    private static File middle(List<File> files) {
        return files == null || files.isEmpty() ? null
                : files.get(Math.max(0, (files.size() - 1) / 2));
    }

    private static String logicalName(String kind, String family) {
        String value = text(family, "default");
        return "default".equalsIgnoreCase(value) ? kind : kind + " - " + value;
    }

    private static String stableId(TileCatalog.TileSet set, File file) {
        String relative;
        try {
            relative = set.root.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize()).toString();
        } catch (Throwable ignored) { relative = file.getName(); }
        return text(set.biome, "theme") + "/" + relative.replace('\\', '/');
    }

    private static boolean isEffectFile(TileCatalog.TileSet set, File file) {
        if (file == null) return false;
        String id = stableId(set, file).toLowerCase(Locale.ROOT);
        String name = file.getName().toLowerCase(Locale.ROOT);
        return id.contains("vfx") || id.contains("/effect/")
                || id.contains("/effects/") || name.startsWith("effect");
    }

    private static String displayName(File file) {
        String name = file == null ? "Tile" : file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ').replace('-', ' ');
    }

    private static String canonical(File file) {
        try { return file.getCanonicalPath().toLowerCase(Locale.ROOT); }
        catch (IOException ignored) {
            return file.getAbsolutePath().toLowerCase(Locale.ROOT);
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void collectPng(File file, List<File> out) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectPng(child, out);
    }
}
