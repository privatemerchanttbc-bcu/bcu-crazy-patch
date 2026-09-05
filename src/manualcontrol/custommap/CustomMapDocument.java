package manualcontrol.custommap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CustomMapDocument {

    public static final int SCHEMA_VERSION = 2;

    public static final int STATIC_TERRAIN_REVISION = 20;
    public static final int PATROL_TERRAIN_REVISION = 21;
    public static final int TERRAIN_REVISION = STATIC_TERRAIN_REVISION;
    public static final int LATEST_TERRAIN_REVISION = PATROL_TERRAIN_REVISION;
    public static final int BACKGROUND_REVISION = 3;
    public static final int CELL_AIR = 0;
    public static final int CELL_GROUND = 1;
    public static final int CELL_WATER = 2;

    public static final byte SURFACE_NORMAL = 0;
    public static final byte SURFACE_ICE = 1;

    public static final String MATERIAL_NORMAL = "normal";
    public static final String MATERIAL_ICE = "ice";
    public static final String MATERIAL_WATER = "water";
    public static final String MATERIAL_LAVA = "lava";

    public static final int WORLD_PER_TILE = 500;
    public static final int LAYERS_PER_TILE = 40;
    public static final int CHUNK_TILES = 8;

    public int schemaVersion = SCHEMA_VERSION;

    public int terrainRevision = TERRAIN_REVISION;

    public int backgroundRevision = BACKGROUND_REVISION;
    public String uuid;
    public String name;
    public long createdAt;
    public long updatedAt;
    public MapSpec spec = new MapSpec();

    public ModeVariant battleTerrain;
    public Map<String, ModeVariant> variants = new LinkedHashMap<String, ModeVariant>();
    public BackgroundManifest backgroundManifest = new BackgroundManifest();

    public PropManifest propManifest = new PropManifest();

    public IceSurfaceManifest iceSurfaceManifest = new IceSurfaceManifest();

    public ThemeProfile themeProfile;

    public List<BackgroundLayer> background = new ArrayList<BackgroundLayer>();

    public static final class ThemeProfile {
        public String profileId = "";
        public String style = "";
        public ThemeSkyProfile sky = new ThemeSkyProfile();
        public ThemeLandmarkProfile landmark = new ThemeLandmarkProfile();
        public ThemeLiquidProfile liquid = new ThemeLiquidProfile();
        public ThemeSurfaceProfile surface = new ThemeSurfaceProfile();
        public ThemeVfxProfile vfx = new ThemeVfxProfile();

        public static ThemeProfile normalized(ThemeProfile source) {
            if (source == null) return null;
            ThemeProfile out = new ThemeProfile();
            out.profileId = text(source.profileId, 96);
            out.style = text(source.style, 160);
            out.sky = ThemeSkyProfile.normalized(source.sky);
            out.landmark = ThemeLandmarkProfile.normalized(source.landmark);
            out.liquid = ThemeLiquidProfile.normalized(source.liquid);
            out.surface = ThemeSurfaceProfile.normalized(source.surface);
            out.vfx = ThemeVfxProfile.normalized(source.vfx);
            return out;
        }

        public boolean isLava() {
            return liquid != null && "lava".equals(liquid.kind);
        }

        public boolean isIceSurface() {
            return surface != null && "ice".equals(surface.kind);
        }

        public boolean hasExplicitSurfaceMaterial() {
            return surface != null && ("ice".equals(surface.kind)
                    || "normal".equals(surface.kind));
        }

        private static String text(String value, int limit) {
            if (value == null) return "";
            String out = value.trim();
            return out.length() <= limit ? out : out.substring(0, limit);
        }
    }

    public static final class ThemeSurfaceProfile {
        public String kind = "auto";

        public String slopeRenderMode = "legacy";

        public String innerCornerSemantics = "legacy-opposite";

        public String innerCornerRenderMode = "legacy-tile";

        public String innerCornerAssetSet = "";

        public String iceBridgeRenderMode = "legacy";

        public int iceBridgeSocketInsetPixels = 72;

        public String floatingIslandRenderMode = "legacy";

        public String floatingIslandCollisionMode = "legacy";

        public String floatingIslandMaterialMode = "legacy";

        static ThemeSurfaceProfile normalized(ThemeSurfaceProfile source) {
            ThemeSurfaceProfile out = new ThemeSurfaceProfile();
            if (source == null) return out;
            String kind = ThemeProfile.text(source.kind, 24)
                    .toLowerCase(java.util.Locale.ROOT);
            out.kind = "ice".equals(kind) ? "ice"
                    : "normal".equals(kind) ? "normal" : "auto";
            String slopeRenderMode = ThemeProfile.text(source.slopeRenderMode, 40)
                    .toLowerCase(java.util.Locale.ROOT);
            out.slopeRenderMode = "stacked-safe-band".equals(slopeRenderMode)
                    ? "stacked-safe-band" : "legacy";
            String innerCornerSemantics = ThemeProfile.text(
                    source.innerCornerSemantics, 40)
                    .toLowerCase(java.util.Locale.ROOT);
            out.innerCornerSemantics = "missing-diagonal".equals(innerCornerSemantics)
                    ? "missing-diagonal" : "legacy-opposite";
            String innerCornerRenderMode = ThemeProfile.text(
                    source.innerCornerRenderMode, 48)
                    .toLowerCase(java.util.Locale.ROOT);
            out.innerCornerRenderMode = "pixel-locked-vertex-overlay".equals(
                    innerCornerRenderMode)
                    ? "pixel-locked-vertex-overlay" : "legacy-tile";
            out.innerCornerAssetSet = ThemeProfile.text(source.innerCornerAssetSet, 160);
            String iceBridgeRenderMode = ThemeProfile.text(
                    source.iceBridgeRenderMode, 48)
                    .toLowerCase(java.util.Locale.ROOT);
            out.iceBridgeRenderMode = "embedded-bank-socket".equals(
                    iceBridgeRenderMode) ? "embedded-bank-socket" : "legacy";
            out.iceBridgeSocketInsetPixels = Math.max(1, Math.min(256,
                    source.iceBridgeSocketInsetPixels));
            String floatingIslandRenderMode = ThemeProfile.text(
                    source.floatingIslandRenderMode, 48)
                    .toLowerCase(java.util.Locale.ROOT);
            out.floatingIslandRenderMode = "width-specific-span".equals(
                    floatingIslandRenderMode) ? "width-specific-span" : "legacy";
            String floatingIslandCollisionMode = ThemeProfile.text(
                    source.floatingIslandCollisionMode, 48)
                    .toLowerCase(java.util.Locale.ROOT);
            out.floatingIslandCollisionMode = "alpha-top-surface".equals(
                    floatingIslandCollisionMode) ? "alpha-top-surface" : "legacy";
            String floatingIslandMaterialMode = ThemeProfile.text(
                    source.floatingIslandMaterialMode, 48)
                    .toLowerCase(java.util.Locale.ROOT);
            out.floatingIslandMaterialMode = "snow-only".equals(
                    floatingIslandMaterialMode) ? "snow-only" : "legacy";
            return out;
        }
    }

    public static final class ThemeSkyProfile {
        public String topArgb = "";
        public String bottomArgb = "";
        public String renderMode = "legacy";

        static ThemeSkyProfile normalized(ThemeSkyProfile source) {
            ThemeSkyProfile out = new ThemeSkyProfile();
            if (source == null) return out;
            out.topArgb = canonicalColor(source.topArgb);
            out.bottomArgb = canonicalColor(source.bottomArgb);
            String renderMode = ThemeProfile.text(source.renderMode, 48)
                    .toLowerCase(java.util.Locale.ROOT);
            out.renderMode = "packed-landscape-595x239".equals(renderMode)
                    ? "packed-landscape-595x239" : "legacy";
            return out;
        }

        public boolean hasOverride() {
            return !topArgb.isEmpty() || !bottomArgb.isEmpty();
        }

        public int topOr(int fallback) { return colorOr(topArgb, fallback); }
        public int bottomOr(int fallback) { return colorOr(bottomArgb, fallback); }

        private static String canonicalColor(String value) {
            if (value == null) return "";
            String hex = value.trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            else if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
            if (hex.length() != 6 && hex.length() != 8) return "";
            for (int i = 0; i < hex.length(); i++)
                if (Character.digit(hex.charAt(i), 16) < 0) return "";
            if (hex.length() == 6) hex = "FF" + hex;
            return "#" + hex.toUpperCase(java.util.Locale.ROOT);
        }

        private static int colorOr(String value, int fallback) {
            if (value == null || value.length() != 9 || value.charAt(0) != '#') return fallback;
            try { return (int) Long.parseLong(value.substring(1), 16); }
            catch (NumberFormatException ignored) { return fallback; }
        }
    }

    public static final class ThemeLandmarkProfile {
        public String role = "";

        static ThemeLandmarkProfile normalized(ThemeLandmarkProfile source) {
            ThemeLandmarkProfile out = new ThemeLandmarkProfile();
            if (source == null) return out;
            String role = ThemeProfile.text(source.role, 64).toLowerCase(java.util.Locale.ROOT);
            out.role = role.matches("[a-z0-9][a-z0-9-]*") ? role : "";
            return out;
        }
    }

    public static final class ThemeLiquidProfile {
        public String kind = "water";
        public int foregroundAlpha = 128;
        public int graceTicks = 15;
        public int damageIntervalTicks = 30;
        public double maxHealthDamagePercent = 5.0;
        public long minimumHealth = 1L;

        static ThemeLiquidProfile normalized(ThemeLiquidProfile source) {
            ThemeLiquidProfile out = new ThemeLiquidProfile();
            if (source == null) return out;
            String kind = ThemeProfile.text(source.kind, 24).toLowerCase(java.util.Locale.ROOT);
            out.kind = "lava".equals(kind) ? "lava" : "water";
            out.foregroundAlpha = clamp(source.foregroundAlpha, 0, 255);
            out.graceTicks = clamp(source.graceTicks, 0, 600);
            out.damageIntervalTicks = clamp(source.damageIntervalTicks, 1, 1800);
            double percent = source.maxHealthDamagePercent;
            if (Double.isNaN(percent) || Double.isInfinite(percent) || percent <= 0d)
                percent = 5d;
            out.maxHealthDamagePercent = Math.max(.01d, Math.min(100d, percent));
            out.minimumHealth = Math.max(0L, source.minimumHealth);
            return out;
        }
    }

    public static final class ThemeVfxProfile {
        public String profileId = "";
        public int totalCap = 96;
        public int eventCap = 96;
        public int ambientCap;
        public int styleKitVersion;
        public int engineMinVersion;
        public int recipeRevision;
        public String styleKitHash = "";
        public Map<String, List<String>> assets = new LinkedHashMap<String, List<String>>();
        public Map<String, ThemeVfxRecipe> recipes =
                new LinkedHashMap<String, ThemeVfxRecipe>();

        static ThemeVfxProfile normalized(ThemeVfxProfile source) {
            ThemeVfxProfile out = new ThemeVfxProfile();
            if (source == null) return out;
            out.profileId = ThemeProfile.text(source.profileId, 96);
            out.totalCap = clamp(source.totalCap, 1, 96);
            out.eventCap = clamp(source.eventCap, 0, out.totalCap);
            out.ambientCap = clamp(source.ambientCap, 0, out.totalCap - out.eventCap);
            out.styleKitVersion = clamp(source.styleKitVersion, 0, 999);
            out.engineMinVersion = clamp(source.engineMinVersion, 0, 999);
            out.recipeRevision = clamp(source.recipeRevision, 0, Integer.MAX_VALUE);
            String declaredHash = ThemeProfile.text(source.styleKitHash, 64)
                    .toLowerCase(java.util.Locale.ROOT);
            out.styleKitHash = declaredHash.isEmpty() ? ""
                    : declaredHash.matches("[0-9a-f]{64}")
                    ? declaredHash : "!invalid";
            if (source.assets != null)
                for (Map.Entry<String, List<String>> entry : source.assets.entrySet()) {
                    String kind = ThemeProfile.text(entry.getKey(), 32)
                            .toLowerCase(java.util.Locale.ROOT);
                    if (!kind.matches("[a-z][a-z0-9-]*") || entry.getValue() == null) continue;
                    ArrayList<String> paths = new ArrayList<String>();
                    for (String raw : entry.getValue()) {
                        String path = raw == null ? "" : raw.trim().replace('\\', '/');
                        if (!path.startsWith("assets/vfx/") || path.contains("../")
                                || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) continue;
                        if (!paths.contains(path)) paths.add(path);
                    }
                    if (!paths.isEmpty()) out.assets.put(kind, paths);
                }
            if (source.recipes != null)
                for (Map.Entry<String, ThemeVfxRecipe> entry : source.recipes.entrySet()) {
                    String eventKey = ThemeProfile.text(entry.getKey(), 64)
                            .toLowerCase(java.util.Locale.ROOT);
                    if (!eventKey.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+"))
                        continue;
                    ThemeVfxRecipe recipe = ThemeVfxRecipe.normalized(entry.getValue(), eventKey);
                    if (recipe != null) out.recipes.put(eventKey, recipe);
                }
            return out;
        }
    }

    public static final class ThemeVfxRecipe {
        public String layer = "foreground";
        public int lifetimeTicks = 18;
        public int burst = 1;
        public double rate;
        public double velocityMinX;
        public double velocityMaxX;
        public double velocityMinY;
        public double velocityMaxY;
        public double gravity;
        public List<ThemeVfxKeyframe> scale = new ArrayList<ThemeVfxKeyframe>();
        public List<ThemeVfxKeyframe> alpha = new ArrayList<ThemeVfxKeyframe>();
        public long seedSalt;
        public String assetKind = "";
        public String blend = "normal";
        public String primitive = "rectangle";
        public String primitiveArgb = "#FFFFFFFF";
        public String missingAsset = "rectangle";
        public ThemeVfxAudio audio = new ThemeVfxAudio();

        static ThemeVfxRecipe normalized(ThemeVfxRecipe source, String eventKey) {
            if (source == null) return null;
            ThemeVfxRecipe out = new ThemeVfxRecipe();
            String layer = ThemeProfile.text(source.layer, 24)
                    .toLowerCase(java.util.Locale.ROOT);
            out.layer = "background".equals(layer) || "world".equals(layer)
                    || "overlay".equals(layer) ? layer : "foreground";
            out.lifetimeTicks = clamp(source.lifetimeTicks, 1, 600);
            out.burst = clamp(source.burst, 0, 64);
            out.rate = finiteClamp(source.rate, 0d, 64d, 0d);
            out.velocityMinX = finiteClamp(source.velocityMinX, -32d, 32d, 0d);
            out.velocityMaxX = finiteClamp(source.velocityMaxX, -32d, 32d, 0d);
            if (out.velocityMinX > out.velocityMaxX) {
                double swap = out.velocityMinX;
                out.velocityMinX = out.velocityMaxX;
                out.velocityMaxX = swap;
            }
            out.velocityMinY = finiteClamp(source.velocityMinY, -32d, 32d, 0d);
            out.velocityMaxY = finiteClamp(source.velocityMaxY, -32d, 32d, 0d);
            if (out.velocityMinY > out.velocityMaxY) {
                double swap = out.velocityMinY;
                out.velocityMinY = out.velocityMaxY;
                out.velocityMaxY = swap;
            }
            out.gravity = finiteClamp(source.gravity, -4d, 4d, 0d);
            out.scale = ThemeVfxKeyframe.normalized(source.scale, out.lifetimeTicks,
                    0d, 8d, 1d);
            out.alpha = ThemeVfxKeyframe.normalized(source.alpha, out.lifetimeTicks,
                    0d, 1d, 1d);
            out.seedSalt = source.seedSalt;
            String assetKind = ThemeProfile.text(source.assetKind, 32)
                    .toLowerCase(java.util.Locale.ROOT);
            out.assetKind = assetKind.matches("[a-z][a-z0-9-]*") ? assetKind : "";
            out.blend = "normal";
            out.primitive = "rectangle";
            String color = ThemeSkyProfile.canonicalColor(source.primitiveArgb);
            out.primitiveArgb = color.isEmpty() ? "#FFFFFFFF" : color;
            String missing = ThemeProfile.text(source.missingAsset, 24)
                    .toLowerCase(java.util.Locale.ROOT);
            out.missingAsset = eventKey.startsWith("ambient.") ? "skip"
                    : "skip".equals(missing) ? "skip" : "rectangle";
            out.audio = ThemeVfxAudio.normalized(source.audio);
            return out;
        }
    }

    public static final class ThemeVfxKeyframe {
        public int tick;
        public double value = 1d;

        static List<ThemeVfxKeyframe> normalized(List<ThemeVfxKeyframe> source,
                                                 int lifetime, double min,
                                                 double max, double fallback) {
            ArrayList<ThemeVfxKeyframe> out = new ArrayList<ThemeVfxKeyframe>();
            if (source != null) for (ThemeVfxKeyframe raw : source) {
                if (raw == null) continue;
                ThemeVfxKeyframe frame = new ThemeVfxKeyframe();
                frame.tick = clamp(raw.tick, 0, lifetime);
                frame.value = finiteClamp(raw.value, min, max, fallback);
                int replace = -1;
                for (int i = 0; i < out.size(); i++)
                    if (out.get(i).tick == frame.tick) replace = i;
                if (replace >= 0) out.set(replace, frame); else out.add(frame);
            }
            java.util.Collections.sort(out,
                    new java.util.Comparator<ThemeVfxKeyframe>() {
                        @Override public int compare(ThemeVfxKeyframe a,
                                                     ThemeVfxKeyframe b) {
                            return Integer.compare(a.tick, b.tick);
                        }
                    });
            if (out.isEmpty()) {
                ThemeVfxKeyframe frame = new ThemeVfxKeyframe();
                frame.value = fallback;
                out.add(frame);
            }
            return out;
        }
    }

    public static final class ThemeVfxAudio {
        public int soundId = -1;
        public int cooldownTicks;

        static ThemeVfxAudio normalized(ThemeVfxAudio source) {
            ThemeVfxAudio out = new ThemeVfxAudio();
            if (source == null) return out;
            out.soundId = source.soundId < 0 ? -1 : clamp(source.soundId, 0, 4095);
            out.cooldownTicks = clamp(source.cooldownTicks, 0, 600);
            return out;
        }
    }

    private static double finiteClamp(double value, double min, double max,
                                      double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static boolean isSupportedTerrainRevision(int revision) {
        return revision == STATIC_TERRAIN_REVISION
                || revision == PATROL_TERRAIN_REVISION;
    }

    public boolean hasEnabledPlatformPatrols() {
        if (battleTerrain != null && battleTerrain.hasEnabledPlatformPatrols()) return true;
        if (variants != null)
            for (ModeVariant variant : variants.values())
                if (variant != null && variant.hasEnabledPlatformPatrols()) return true;
        return false;
    }

    public boolean requiresPatrolTerrainRevision() {
        return terrainRevision >= PATROL_TERRAIN_REVISION
                || hasEnabledPlatformPatrols();
    }

    public void markPatrolRevisionIfNeeded() {
        if (terrainRevision >= STATIC_TERRAIN_REVISION
                && hasEnabledPlatformPatrols())
            terrainRevision = PATROL_TERRAIN_REVISION;
    }

    public ModeVariant variant(MapMode mode) {
        return mode == null ? null : variants.get(mode.id);
    }

    public enum MapMode {
        ADVENTURE("adventure", "Adventure"),
        HEIST("heist", "Cat Heist"),
        DUEL("duel", "Duel"),
        DERBY("derby", "Derby"),
        ARENA("arena", "Arena");

        public final String id;
        public final String title;

        MapMode(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public static MapMode fromId(String id) {
            if (id != null) for (MapMode mode : values()) if (mode.id.equalsIgnoreCase(id)) return mode;
            return null;
        }
    }

    public static final class MapSpec {
        public String name = "New Custom Map";
        public String biome = "";
        public long seed = 1L;
        public int width = 75;
        public int height = 12;
        public double groundDensity = 45.0;
        public double waterDensity = 12.0;
        public double treeDensity = 20.0;

        public double iceSurfaceDensity = 20.0;

        public double iceBridgeDensity = 35.0;

        public double propDensity;

        public int slopeMinY = 2;

        public int slopeMaxY = 10;

        public int slopeCount = 8;

        public double slopeCoverage;

        public int slopeMinRise = 1;

        public int slopeMaxRise = 4;

        public int slopeMinLength = 1;

        public int slopeMaxLength = 48;

        public double floatingIslandDensity = 25.0;

        public int floatingIslandCount = -1;

        public int floatingIslandLayers = -1;

        public int complexity = 50;
        public String difficulty = "Normal";
        public int adventureEnemyOverride = -1;
        public int heistEnemyOverride = -1;

        public List<String> enemyPool = new ArrayList<String>();
        public List<String> modes = new ArrayList<String>();

        public MapSpec() {
            modes.add(MapMode.ADVENTURE.id);
            modes.add(MapMode.HEIST.id);
            modes.add(MapMode.DUEL.id);
        }

        public boolean supports(MapMode mode) {
            return mode != null && modes != null && modes.contains(mode.id);
        }

    }

    public static final class ModeVariant {
        public String mode;
        public long seed;
        public int width;
        public int height;

        public int[] cells;

        public int[] surface;

        public byte[] surfaceMaterials;

        public String[] iceSurfaceKeys;

        public float[] walkSurfaceLayers;

        public int[] slopeDirection;

        public int[] slopePhase;

        public int[] slopeRunId;
        public boolean[] water;
        public TileGrid grid = new TileGrid();
        public TerrainProfile profile = new TerrainProfile();
        public List<SurfaceEdge> surfaceGraph = new ArrayList<SurfaceEdge>();

        public List<NavigationLink> navigationLinks = new ArrayList<NavigationLink>();

        public List<SecondaryPlatform> secondaryPlatforms = new ArrayList<SecondaryPlatform>();

        public List<BaseSafeZone> baseSafeZones = new ArrayList<BaseSafeZone>();
        public MapAnchor spawn = new MapAnchor();
        public MapAnchor destination = new MapAnchor();
        public List<MapAnchor> checkpoints = new ArrayList<MapAnchor>();
        public List<TreePlacement> trees = new ArrayList<TreePlacement>();
        public List<PropPlacement> props = new ArrayList<PropPlacement>();
        public List<EnemyPlacement> enemies = new ArrayList<EnemyPlacement>();

        public List<ManualTile> manualTiles = new ArrayList<ManualTile>();

        public List<ManualSlopeRun> manualSlopes =
                new ArrayList<ManualSlopeRun>();
        public List<ManualIslandRun> manualIslands =
                new ArrayList<ManualIslandRun>();
        public List<ManualIceBridge> manualIceBridges =
                new ArrayList<ManualIceBridge>();

        public List<ManualDecoration> manualDecorations =
                new ArrayList<ManualDecoration>();
        public List<BackgroundLayer> manualBackground =
                new ArrayList<BackgroundLayer>();
        public List<ManualEffect> manualEffects = new ArrayList<ManualEffect>();

        public List<TerrainMotif> motifs = new ArrayList<TerrainMotif>();
        public double achievedGroundDensity;
        public double achievedWaterDensity;
        public double achievedTreeDensity;
        public double achievedPropDensity;
        public double achievedIceSurfaceDensity;
        public int achievedIceBridgeCount;

        public int achievedSlopeCount;
        public int achievedSlopeMinY;
        public int achievedSlopeMaxY;
        public int achievedSlopeMotifCount;
        public int achievedSlopeMinRise;
        public int achievedSlopeMaxRise;
        public int achievedSlopeMinLength;
        public int achievedSlopeMaxLength;
        public double achievedSlopeCoverage;

        public double achievedFloatingIslandDensity;
        public int elevationChanges;
        public int waterZoneCount;
        public int floatingIslandCount;
        public int floatingIslandLayerCount;
        public int objectCount;
        public boolean reachable;
        public String validation = "";

        public int cell(int x, int y) {
            if (cells == null || x < 0 || y < 0 || x >= width || y >= height) return CELL_AIR;
            return cells[y * width + x];
        }

        public void setCell(int x, int y, int value) {
            if (cells != null && x >= 0 && y >= 0 && x < width && y < height)
                cells[y * width + x] = value;
        }

        public ManualTile manualTileAt(int x, int y) {
            if (manualTiles == null) return null;
            for (ManualTile tile : manualTiles)
                if (tile != null && tile.x == x && tile.y == y) return tile;
            return null;
        }

        public String materialAt(int x, int y) {
            ManualTile tile = manualTileAt(x, y);
            if (tile != null && tile.material != null
                    && !tile.material.trim().isEmpty()) return tile.material;
            if (cell(x, y) == CELL_WATER) return MATERIAL_WATER;
            if (cell(x, y) == CELL_GROUND && surfaceMaterials != null
                    && x >= 0 && x < surfaceMaterials.length
                    && surface != null && x < surface.length && surface[x] == y
                    && surfaceMaterials[x] == SURFACE_ICE) return MATERIAL_ICE;
            return MATERIAL_NORMAL;
        }

        public String liquidMaterialAt(float worldX) {
            if (!containsWorldX(worldX)) return MATERIAL_WATER;
            int x = Math.max(0, Math.min(width - 1,
                    (int) Math.floor(worldX / Math.max(1f, worldUnitsPerTile()))));
            if (manualTiles != null)
                for (ManualTile tile : manualTiles)
                    if (tile != null && tile.x == x && cell(tile.x, tile.y) == CELL_WATER
                            && MATERIAL_LAVA.equals(tile.material)) return MATERIAL_LAVA;
            return MATERIAL_WATER;
        }

        public String explicitLiquidMaterialAt(float worldX) {
            if (!containsWorldX(worldX) || manualTiles == null) return "";
            int x = Math.max(0, Math.min(width - 1,
                    (int) Math.floor(worldX / Math.max(1f, worldUnitsPerTile()))));
            for (ManualTile tile : manualTiles)
                if (tile != null && tile.x == x && cell(tile.x, tile.y) == CELL_WATER
                        && (MATERIAL_LAVA.equals(tile.material)
                        || MATERIAL_WATER.equals(tile.material))) return tile.material;
            return "";
        }

        public boolean hasManualEdits() {
            return manualTiles != null && !manualTiles.isEmpty()
                    || manualSlopes != null && !manualSlopes.isEmpty()
                    || manualIslands != null && !manualIslands.isEmpty()
                    || manualIceBridges != null && !manualIceBridges.isEmpty()
                    || manualDecorations != null && !manualDecorations.isEmpty()
                    || manualBackground != null && !manualBackground.isEmpty()
                    || manualEffects != null && !manualEffects.isEmpty();
        }

        public int worldUnitsPerTile() {
            return profile == null || profile.worldUnitsPerTile <= 0
                    ? WORLD_PER_TILE : profile.worldUnitsPerTile;
        }

        public int layerUnitsPerTile() {
            return profile == null || profile.layerUnitsPerTile <= 0
                    ? LAYERS_PER_TILE : profile.layerUnitsPerTile;
        }

        public float worldWidth() { return width * (float) worldUnitsPerTile(); }

        public boolean containsWorldX(float worldX) {
            return worldX >= 0f && worldX < worldWidth();
        }

        public float worldX(int tileX) {
            return (tileX + 0.5f) * worldUnitsPerTile();
        }

        public byte surfaceMaterialAt(float worldX) {
            if (!containsWorldX(worldX)) return SURFACE_NORMAL;
            int x = Math.max(0, Math.min(width - 1,
                    (int) Math.floor(worldX / Math.max(1f, worldUnitsPerTile()))));
            if (surfaceMaterials != null && surfaceMaterials.length == width)
                return surfaceMaterials[x] == SURFACE_ICE ? SURFACE_ICE : SURFACE_NORMAL;
            return profile != null && profile.surfaceMaterial == SURFACE_ICE
                    ? SURFACE_ICE : SURFACE_NORMAL;
        }

        public byte surfaceMaterialAt(float worldX, String platformId) {
            if (platformId != null) {
                SecondaryPlatform platform = secondaryPlatform(platformId);
                if (platform != null)
                    return platform.surfaceMaterial == SURFACE_ICE
                            ? SURFACE_ICE : SURFACE_NORMAL;
            }
            return surfaceMaterialAt(worldX);
        }

        public boolean isIceSurfaceAt(float worldX, String platformId) {
            return surfaceMaterialAt(worldX, platformId) == SURFACE_ICE;
        }

        public float surfaceLayerAt(float worldX) {
            if (surface == null || surface.length == 0 || !containsWorldX(worldX)) return Float.NaN;
            int units = worldUnitsPerTile();
            int x = (int) (worldX / units);
            int row = surface[x];
            if (row < 0) return Float.NaN;
            if (walkSurfaceLayers != null && walkSurfaceLayers.length == surface.length) {
                float center = worldX / units - 0.5f;
                int left = Math.max(0, Math.min(surface.length - 1, (int) Math.floor(center)));
                int right = Math.min(surface.length - 1, left + 1);
                if (surface[left] >= 0 && surface[right] >= 0) {

                    if (!isContinuousSurfaceBetween(left, right))
                        return walkSurfaceLayers[x];
                    float t = Math.max(0f, Math.min(1f, center - (float) Math.floor(center)));
                    return walkSurfaceLayers[left]
                            + (walkSurfaceLayers[right] - walkSurfaceLayers[left]) * t;
                }
                return walkSurfaceLayers[x];
            }
            int bottomDistance = Math.max(0, height - row);
            return -bottomDistance * layerUnitsPerTile();
        }

        public float walkLayerAtTile(int x) {
            x = Math.max(0, Math.min(width - 1, x));
            if (walkSurfaceLayers != null && walkSurfaceLayers.length == width)
                return walkSurfaceLayers[x];
            int row = surface == null || x >= surface.length ? -1 : surface[x];
            return row < 0 ? Float.NaN : -(height - row) * layerUnitsPerTile();
        }

        public boolean isContinuousSurfaceBetween(int left, int right) {
            if (left < 0 || right < 0 || left >= width || right >= width
                    || Math.abs(left - right) != 1 || surface == null
                    || surface[left] < 0 || surface[right] < 0
                    || (water != null && (water[left] || water[right]))) return false;
            if (left > right) {
                int swap = left;
                left = right;
                right = swap;
            }
            float leftLayer = walkLayerAtTile(left);
            float rightLayer = walkLayerAtTile(right);
            if (Float.isNaN(leftLayer) || Float.isNaN(rightLayer)) return false;
            float delta = rightLayer - leftLayer;
            float flatTolerance = Math.max(0.01f, layerUnitsPerTile() * 0.015f);
            if (Math.abs(delta) <= flatTolerance) return true;
            if (slopeDirection == null || slopeDirection.length != width) return false;
            int expected = delta > 0f ? 1 : -1;
            int leftDirection = slopeDirection[left];
            int rightDirection = slopeDirection[right];

            boolean sameDirectedChain = leftDirection == expected
                    && rightDirection == expected;
            float slopeContactLimit = layerUnitsPerTile()
                    * (sameDirectedChain ? 1.05f : 0.55f) + 0.01f;
            if (Math.abs(delta) > slopeContactLimit) return false;
            return leftDirection == expected || rightDirection == expected;
        }

        public boolean isWaterAt(float worldX) {
            if (water == null || water.length == 0 || !containsWorldX(worldX)) return false;
            int x = (int) (worldX / worldUnitsPerTile());
            return water[x];
        }

        public boolean hasEnabledPlatformPatrols() {
            if (secondaryPlatforms == null) return false;
            for (SecondaryPlatform platform : secondaryPlatforms)
                if (platform != null && platform.isPatrolling()) return true;
            return false;
        }

        public SecondaryPlatform secondaryPlatform(String id) {
            if (id == null || id.isEmpty() || secondaryPlatforms == null) return null;
            for (SecondaryPlatform platform : secondaryPlatforms)
                if (platform != null && id.equals(platform.id)) return platform;
            return null;
        }
    }

    public static final class ManualTile {
        public int x;
        public int y;
        public String geometry = "ground";
        public String material = MATERIAL_NORMAL;
        public String sourceTheme = "";
        public String family = "";
        public String preferredAsset = "";
        public String preferredRole = "";
        public String materialTheme = "";
        public String materialFamily = "";
        public String materialAsset = "";
        public String materialUnderlay = "";

        public ManualTile() {}

        public ManualTile(int x, int y, String geometry, String material,
                          String sourceTheme, String family,
                          String preferredAsset, String preferredRole) {
            this.x = x;
            this.y = y;
            this.geometry = geometry == null ? "ground" : geometry;
            this.material = material == null ? MATERIAL_NORMAL : material;
            this.sourceTheme = sourceTheme == null ? "" : sourceTheme;
            this.family = family == null ? "" : family;
            this.preferredAsset = preferredAsset == null ? "" : preferredAsset;
            this.preferredRole = preferredRole == null ? "" : preferredRole;
        }
    }

    public static final class ManualSlopeRun {
        public int runId;
        public int startX;
        public int startRow;
        public int endX;
        public int endRow;
        public String style = "gentle";
        public String sourceTheme = "";
        public String family = "";
    }

    public static final class ManualIslandRun {
        public int row;
        public int startX;
        public int endX;
        public String sourceTheme = "";
        public String family = "";
        public String asset = "";
    }

    public static final class ManualIceBridge {
        public int row;
        public int startX;
        public int endX;
        public String sourceTheme = "";
        public String family = "";
        public int[] previousCells;
    }

    public static final class ManualDecoration {
        public String asset = "";
        public String sourceTheme = "";
        public String category = "prop";
        public float x;
        public float anchorLayer;
        public float scale = 1f;
        public int order;
    }

    public static final class ManualEffect {
        public String asset = "";
        public String sourceTheme = "";
        public String scope = "global";
        public float startX;
        public float endX;
        public int order;
    }

    public static final class BackgroundLayer {
        public String asset = "";
        public String role = "mid";
        public int order;

        public int parallaxPercent;
        public String anchor = "bottom";
        public String fit = "contain";
        public boolean repeatX;

        public BackgroundLayer() {}

        public BackgroundLayer(String asset, String role, int order, int parallaxPercent,
                               String anchor, String fit, boolean repeatX) {
            this.asset = asset;
            this.role = role;
            this.order = order;
            this.parallaxPercent = parallaxPercent;
            this.anchor = anchor;
            this.fit = fit;
            this.repeatX = repeatX;
        }
    }

    public static final class BackgroundManifest {
        public long seed;
        public int complexity;
        public int skyTopArgb = 0xff53d7ef;
        public int skyBottomArgb = 0xffc8f4f7;
        public List<BackgroundAssetRef> assets = new ArrayList<BackgroundAssetRef>();
        public List<BackgroundBand> bands = new ArrayList<BackgroundBand>();
    }

    public static final class BackgroundAssetRef {
        public String sourceKey = "";
        public String asset = "";
        public String role = "";
        public String palette = "neutral";
        public int width = 1;
        public int height = 1;
        public int contentLeft;
        public int contentTop;
        public int contentRight;
        public int contentBottom;
        public int averageArgb = 0xffffffff;
        public String sourceTransform = "";

        public int contentWidth() {
            return Math.max(1, contentRight - contentLeft + 1);
        }

        public int contentHeight() {
            return Math.max(1, contentBottom - contentTop + 1);
        }
    }

    public static final class BackgroundBand {
        public String id = "";
        public String role = "";
        public String mode = "scatter";
        public int order;
        public int parallaxPercent;
        public float minSize;
        public float maxSize;
        public float minY;
        public float maxY;
        public float minGap;
        public float maxGap;
        public int minCount;
        public int maxCount;
        public int minAlpha = 255;
        public int maxAlpha = 255;

        public float minHorizontalScale = 1f;
        public float maxHorizontalScale = 1f;
        public long seedSalt;

        public BackgroundBand() {}

        public BackgroundBand(String id, String role, String mode, int order,
                              int parallaxPercent, float minSize, float maxSize,
                              float minY, float maxY, float minGap, float maxGap,
                              int minCount, int maxCount, int minAlpha, int maxAlpha,
                              long seedSalt) {
            this.id = id;
            this.role = role;
            this.mode = mode;
            this.order = order;
            this.parallaxPercent = parallaxPercent;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.minY = minY;
            this.maxY = maxY;
            this.minGap = minGap;
            this.maxGap = maxGap;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.minAlpha = minAlpha;
            this.maxAlpha = maxAlpha;
            this.seedSalt = seedSalt;
        }
    }

    public static final class MapAnchor {
        public int x;
        public int y;

        public MapAnchor() {}

        public MapAnchor(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class TreePlacement {
        public int x;
        public int y;
        public int asset;

        public int scalePercent = 100;

        public int xOffsetPercent;
        public int widthTiles = 1;
        public int heightTiles = 1;

        public TreePlacement() {}

        public TreePlacement(int x, int y, int asset) {
            this.x = x;
            this.y = y;
            this.asset = asset;
        }
    }

    public static final class PropManifest {
        public int revision = 1;
        public List<PropAssetRef> assets = new ArrayList<PropAssetRef>();

        public PropAssetRef find(String id) {
            if (id == null || assets == null) return null;
            for (PropAssetRef ref : assets)
                if (ref != null && id.equals(ref.id)) return ref;
            return null;
        }

        public int randomEligibleCount() {
            int count = 0;
            if (assets != null) for (PropAssetRef ref : assets)
                if (ref != null && ref.randomEligible) count++;
            return count;
        }
    }

    public static final class IceSurfaceManifest {
        public int revision = 1;
        public int sourceTilePixels = 256;
        public int surfaceBandPixels = 34;
        public int breakFrameTicks = 3;
        public int breakPivotX = 128;
        public int breakPivotY = 96;
        public Map<String, IceSurfaceAssetRef> tiles =
                new LinkedHashMap<String, IceSurfaceAssetRef>();
        public List<String> breakFrames = new ArrayList<String>();

        public IceSurfaceAssetRef find(String source) {
            if (tiles == null || source == null) return null;
            return tiles.get(tileKey(source));
        }

        public boolean isReady() {
            if (revision != 1 || tiles == null || tiles.isEmpty()
                    || breakFrames == null || breakFrames.size() != 8) return false;
            for (IceSurfaceAssetRef ref : tiles.values())
                if (ref != null && ref.isComplete()) return true;
            return false;
        }

        public static String tileKey(String source) {
            if (source == null) return "";
            String value = source.replace('\\', '/').trim();
            int slash = value.lastIndexOf('/');
            if (slash >= 0) value = value.substring(slash + 1);
            int dot = value.lastIndexOf('.');
            if (dot > 0) value = value.substring(0, dot);
            return value.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final class IceSurfaceAssetRef {
        public String sourceKey = "";
        public String base = "";
        public String crack1 = "";
        public String crack2 = "";
        public String crack3 = "";
        public int width = 1;
        public int height = 1;

        public String imageForCrackLevel(int level) {
            if (level <= 0) return base;
            if (level == 1) return crack1;
            if (level == 2) return crack2;
            return crack3;
        }

        public boolean isComplete() {
            return sourceKey != null && !sourceKey.isEmpty()
                    && base != null && !base.isEmpty()
                    && crack1 != null && !crack1.isEmpty()
                    && crack2 != null && !crack2.isEmpty()
                    && crack3 != null && !crack3.isEmpty();
        }
    }

    public static final class PropAssetRef {
        public String id = "";
        public String logicalId = "";
        public String sourceKey = "";
        public String asset = "";
        public String group = "";
        public String role = "";
        public String anchor = "BOTTOM_CENTER";
        public String baseline = "ALPHA_BOTTOM_TO_DRY_SURFACE";
        public String layer = "BEHIND_ACTORS";
        public String collision = "NONE";
        public String interaction = "NONE";
        public boolean decorative = true;
        public boolean randomEligible;
        public String deferredReason = "";
        public int sourceWidth;
        public int sourceHeight;
        public float weight = 1f;
        public int maxCount;
        public float minGapTiles = 1.5f;
        public int minScalePercent = 82;
        public int maxScalePercent = 112;
        public float maxWidthTiles = 2f;
        public float maxHeightTiles = 2f;
    }

    public static final class PropPlacement {
        public int x;
        public int y;
        public String assetId = "";
        public int scalePercent = 100;
        public int xOffsetPercent;
        public String layer = "BEHIND_ACTORS";

        public PropPlacement() {}

        public PropPlacement(int x, int y, String assetId) {
            this.x = x;
            this.y = y;
            this.assetId = assetId == null ? "" : assetId;
        }
    }

    public static final class TileGrid {
        public int width;
        public int height;
        public int[] cells;
    }

    public static final class TerrainProfile {
        public String profileId = "adventure";
        public int worldUnitsPerTile = WORLD_PER_TILE;
        public int layerUnitsPerTile = LAYERS_PER_TILE;
        public int maxJumpGap = 4;
        public int maxStepRows = 5;
        public boolean treesBlockSight;
        public boolean waterHazard = true;
        public int complexity = 50;

        public byte surfaceMaterial = SURFACE_NORMAL;

        public float surfaceInsetRatio;
        public ComplexityProfile complexityProfile = new ComplexityProfile();
    }

    public static final class ComplexityProfile {
        public int requestedTier = 1;
        public int achievedTier = 1;
        public double structuralScore;
        public double targetScore;
        public int elevationSpanRows;
        public int objectClusterCount;
        public int chasmStepGroupCount;
        public String tierName = "Flat";
        public String capReason = "";
    }

    public enum TerrainMotifType {
        FLAT, RAMP, RAMP_CHAIN, PEAK, VALLEY, TERRACE,
        WATER_CROSSING, FLOATING_CLUSTER, STEP_UP, DROP_DOWN, CHASM
    }

    public static final class TerrainMotif {
        public TerrainMotifType type = TerrainMotifType.FLAT;
        public int startX;
        public int endX;
        public int startRow;
        public int endRow;
        public int transitions;

        public TerrainMotif() {}

        public TerrainMotif(TerrainMotifType type, int startX, int endX,
                            int startRow, int endRow, int transitions) {
            this.type = type;
            this.startX = startX;
            this.endX = endX;
            this.startRow = startRow;
            this.endRow = endRow;
            this.transitions = transitions;
        }
    }

    public static final class SurfaceEdge {
        public int fromX;
        public int toX;
        public boolean jump;

        public SurfaceEdge() {}
        public SurfaceEdge(int fromX, int toX, boolean jump) {
            this.fromX = fromX;
            this.toX = toX;
            this.jump = jump;
        }
    }

    public enum NavigationType {
        WALK, JUMP, SWIM, STEP_UP, DROP_DOWN
    }

    public static final class NavigationLink {
        public NavigationType type = NavigationType.WALK;
        public int fromX;
        public int toX;
        public float fromLayer;
        public float toLayer;

        public int spanStartX;
        public int spanEndX;
        public boolean bidirectional = true;

        public NavigationLink() {}

        public NavigationLink(NavigationType type, int fromX, int toX,
                              float fromLayer, float toLayer,
                              int spanStartX, int spanEndX) {
            this.type = type;
            this.fromX = fromX;
            this.toX = toX;
            this.fromLayer = fromLayer;
            this.toLayer = toLayer;
            this.spanStartX = spanStartX;
            this.spanEndX = spanEndX;
        }
    }

    public static final class SecondaryPlatform {

        public String id = "";
        public int startX;
        public int endX;
        public float supportLayer;
        public boolean oneWay = true;

        public boolean playerOnly = true;

        public byte surfaceMaterial = SURFACE_NORMAL;

        public String[] iceSurfaceKeys;

        public String collisionMode = "legacy";

        public int collisionLeftInsetPermille;
        public int collisionRightInsetPermille;

        public int collisionTopOffsetPermille;

        public int collisionBottomInsetPermille;

        public PlatformPatrol patrol = new PlatformPatrol();

        public SecondaryPlatform() {}

        public SecondaryPlatform(int startX, int endX, float supportLayer) {
            this.startX = startX;
            this.endX = endX;
            this.supportLayer = supportLayer;
        }

        public int widthTiles() {
            return Math.max(0, endX - startX + 1);
        }

        public float originCenterTileX() {
            return (startX + endX + 1) * 0.5f;
        }

        public boolean isPatrolling() {
            return patrol != null && patrol.enabled;
        }

        public boolean hasAlphaTopCollision() {
            return "alpha-top-surface-v1".equals(collisionMode);
        }

        public float collisionLeftTileX(float centerTileX) {
            float left = centerTileX - widthTiles() * .5f;
            return hasAlphaTopCollision()
                    ? left + widthTiles() * clampPermille(collisionLeftInsetPermille)
                    / 1000f : left;
        }

        public float collisionRightTileX(float centerTileX) {
            float right = centerTileX + widthTiles() * .5f;
            return hasAlphaTopCollision()
                    ? right - widthTiles() * clampPermille(collisionRightInsetPermille)
                    / 1000f : right;
        }

        public float collisionSupportLayer(float baseSupportLayer,
                                           float layerUnitsPerTile) {
            return hasAlphaTopCollision()
                    ? baseSupportLayer + clampPermille(collisionTopOffsetPermille)
                    * Math.max(1f, layerUnitsPerTile) / 1000f
                    : baseSupportLayer;
        }

        public float collisionSupportTileY(float baseSupportTileY) {
            return hasAlphaTopCollision()
                    ? baseSupportTileY - clampPermille(collisionTopOffsetPermille) / 1000f
                    : baseSupportTileY;
        }

        public float collisionBodyBottomTileY(float baseSupportTileY) {
            return hasAlphaTopCollision()
                    ? baseSupportTileY - 1f
                    + clampUnitPermille(collisionBottomInsetPermille) / 1000f
                    : baseSupportTileY - 1f;
        }

        private static int clampPermille(int value) {
            return Math.max(0, Math.min(250, value));
        }

        private static int clampUnitPermille(int value) {
            return Math.max(0, Math.min(1000, value));
        }
    }

    public static final class PlatformPatrol {
        public boolean enabled;

        public boolean coordinatesInitialized;
        public float ax;
        public float ay;
        public float bx;
        public float by;

        public float speedTilesPerSecond = 1f;

        public float durationSeconds;

        public float dwellSeconds = 1f;

        public String timingAuthority = "speed";
        public String easing = "ease-in-out";
    }

    public static final class BaseSafeZone {
        public String role = "enemy";
        public int centerX;
        public int startX;
        public int endX;
        public float supportLayer;

        public BaseSafeZone() {}

        public BaseSafeZone(String role, int centerX, int startX, int endX,
                            float supportLayer) {
            this.role = role;
            this.centerX = centerX;
            this.startX = startX;
            this.endX = endX;
            this.supportLayer = supportLayer;
        }

        public boolean containsTile(int x) {
            return x >= startX && x <= endX;
        }
    }

    public static final class EnemyPlacement {
        public String enemyId = "auto";
        public int x;
        public int y;
        public int hpPercent = 100;
        public int attackPercent = 100;
        public boolean boss;

        public EnemyPlacement() {}

        public EnemyPlacement(int x, int y, int hpPercent, int attackPercent) {
            this.x = x;
            this.y = y;
            this.hpPercent = hpPercent;
            this.attackPercent = attackPercent;
        }
    }
}
