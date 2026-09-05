package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.BackgroundAssetRef;
import manualcontrol.custommap.CustomMapDocument.BackgroundBand;
import manualcontrol.custommap.CustomMapDocument.BackgroundManifest;
import manualcontrol.custommap.CustomMapDocument.MapSpec;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

final class BackgroundComposer {

    static final String GENERATED_SKY = "__generated_sky_gradient__";

    private BackgroundComposer() {}

    static BackgroundManifest compose(MapSpec spec, TileCatalog.TileSet tiles) {
        BackgroundManifest out = new BackgroundManifest();
        long sourceSeed = spec == null ? 1L : spec.seed;
        out.seed = mix64(sourceSeed ^ 0x4241434b47524f55L);
        out.complexity = clamp(spec == null ? 50 : spec.complexity, 0, 100);

        BackgroundAssetRef fallback = new BackgroundAssetRef();
        fallback.sourceKey = GENERATED_SKY;
        fallback.role = "base";
        fallback.palette = "sky";
        fallback.width = fallback.height = 64;
        fallback.contentRight = fallback.contentBottom = 63;
        fallback.averageArgb = 0xff78d7e8;
        out.assets.add(fallback);

        if (tiles != null) for (TileCatalog.BackgroundAsset source : tiles.backgrounds) {
            BackgroundAssetRef ref = new BackgroundAssetRef();
            ref.sourceKey = source.sourceKey;
            ref.role = source.role;
            ref.palette = source.palette;
            ref.width = Math.max(1, source.width);
            ref.height = Math.max(1, source.height);
            ref.contentLeft = clamp(source.contentLeft, 0, ref.width - 1);
            ref.contentTop = clamp(source.contentTop, 0, ref.height - 1);
            ref.contentRight = clamp(source.contentRight, ref.contentLeft, ref.width - 1);
            ref.contentBottom = clamp(source.contentBottom, ref.contentTop, ref.height - 1);
            ref.averageArgb = source.averageArgb;
            if ("sky".equals(ref.role) && tiles.themeProfile != null
                    && tiles.themeProfile.sky != null
                    && "packed-landscape-595x239".equals(
                    tiles.themeProfile.sky.renderMode)) {
                ref.width = source.width * 5;
                ref.height = source.height / 5;
                ref.contentLeft = ref.contentTop = 0;
                ref.contentRight = ref.width - 1;
                ref.contentBottom = ref.height - 1;
                ref.sourceTransform = "unpack-595x239";
            }
            out.assets.add(ref);
            if ("sky".equals(ref.role)) deriveSkyPalette(out, ref.averageArgb);
        }
        if (tiles != null && tiles.themeProfile != null
                && tiles.themeProfile.sky != null
                && tiles.themeProfile.sky.hasOverride()) {
            out.skyTopArgb = tiles.themeProfile.sky.topOr(out.skyTopArgb);
            out.skyBottomArgb = tiles.themeProfile.sky.bottomOr(out.skyBottomArgb);
        }

        out.bands.add(new BackgroundBand("base", "base", "cover", 0, 0,
                1f, 1f, 0f, 0f, 0f, 0f, 1, 1, 255, 255, 0x1001L));
        out.bands.add(new BackgroundBand("theme-sky", "sky", "sky", 1, 0,
                1f, 1f, 0f, 0f, 0f, 0f, 1, 1, 255, 255, 0x1002L));
        out.bands.add(new BackgroundBand("sun", "sun", "single", 5, 2,
                .16f, .23f, .06f, .20f, 0f, 0f, 1, 1, 235, 255, 0x2001L));

        boolean lunarStarfield = isLunarStarfield(tiles);
        float largeMinY = lunarStarfield ? .02f : .06f;
        float largeMaxY = lunarStarfield ? .42f : .25f;
        float mediumMinY = lunarStarfield ? .05f : .12f;
        float mediumMaxY = lunarStarfield ? .52f : .32f;
        float smallMinY = lunarStarfield ? .08f : .22f;
        float smallMaxY = lunarStarfield ? .62f : .38f;

        out.bands.add(new BackgroundBand("cloud-large", "cloud-large", "scatter", 10, 3,
                .30f, .44f, largeMinY, largeMaxY, .62f, 1.05f, 1, 1, 205, 245, 0x3001L));
        out.bands.add(new BackgroundBand("cloud-medium", "cloud-medium", "scatter", 11, 4,
                .18f, .30f, mediumMinY, mediumMaxY, .36f, .70f, 1, 3, 210, 250, 0x3002L));
        out.bands.add(new BackgroundBand("cloud-small", "cloud-small", "scatter", 12, 5,
                .10f, .18f, smallMinY, smallMaxY, .24f, .48f, 1, 3, 215, 252, 0x3003L));

        int landmarkIndex = 0;
        for (String role : landmarkRoles(tiles)) {
            BackgroundBand landmark = new BackgroundBand(role, role, "single", 16, 6,
                    .38f, .52f, .37f, .47f, 0f, 0f, 1, 1, 225, 248,
                    0x3501L + landmarkIndex * 0x101L);
            out.bands.add(landmark);
            landmarkIndex++;
        }

        BackgroundBand far = new BackgroundBand("far-panorama", "far-panorama", "repeat", 20, 8,
                .20f, .30f, .94f, .99f, 0f, 0f, 1, 1, 205, 235, 0x4001L);
        far.minHorizontalScale = 1.00f;
        far.maxHorizontalScale = 1.38f;
        out.bands.add(far);
        BackgroundBand mid = new BackgroundBand("mid-mountain", "mid-mountain", "scatter", 30, 15,
                .28f, .44f, .94f, 1.01f, .38f, .72f, 1, 2, 230, 255, 0x5001L);
        mid.minHorizontalScale = 1.08f;
        mid.maxHorizontalScale = 1.85f;
        out.bands.add(mid);
        BackgroundBand near = new BackgroundBand("near-mountain", "near-mountain", "scatter", 40, 26,
                .36f, .55f, .96f, 1.03f, .55f, 1.02f, 0, 1, 240, 255, 0x5002L);
        near.minHorizontalScale = 1.15f;
        near.maxHorizontalScale = 2.15f;
        out.bands.add(near);
        return out;
    }

    private static boolean isLunarStarfield(TileCatalog.TileSet tiles) {
        if (tiles == null || tiles.biome == null) return false;
        String biome = tiles.biome.toLowerCase(Locale.ROOT);
        return biome.contains("moon") || biome.contains("lunar");
    }

    private static Set<String> landmarkRoles(TileCatalog.TileSet tiles) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (tiles != null) for (TileCatalog.BackgroundAsset asset : tiles.backgrounds)
            if (asset != null && asset.role != null
                    && asset.role.startsWith("landmark-")) out.add(asset.role);
        return out;
    }

    private static void deriveSkyPalette(BackgroundManifest out, int average) {
        int r = (average >>> 16) & 0xff;
        int g = (average >>> 8) & 0xff;
        int b = average & 0xff;

        out.skyTopArgb = rgb(mix(r, 105, .40f), mix(g, 218, .40f), mix(b, 244, .40f));
        out.skyBottomArgb = rgb(mix(r, 220, .72f), mix(g, 245, .72f), mix(b, 248, .72f));
    }

    static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private static int mix(int a, int b, float amount) {
        return clamp(Math.round(a * (1f - amount) + b * amount), 0, 255);
    }

    private static int rgb(int r, int g, int b) {
        return 0xff000000 | (clamp(r, 0, 255) << 16)
                | (clamp(g, 0, 255) << 8) | clamp(b, 0, 255);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
