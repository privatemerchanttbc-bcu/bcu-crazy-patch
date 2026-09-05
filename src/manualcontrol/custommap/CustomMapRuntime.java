package manualcontrol.custommap;

import common.battle.StageBasis;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;

import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class CustomMapRuntime {

    private static final int PLATFORM_RIDER_LEASE_TICKS = 4;
    private static final int MAX_STATION_HOLD_TICKS = 300;

    public enum TerrainKind { MAIN, FLOATING, WATER, VOID }

    public static final class TerrainSample {
        public final TerrainKind kind;
        public final float supportLayer;
        public final boolean inBounds;
        public final byte surfaceMaterial;
        public final String material;
        public final String platformId;
        public final CustomMapDocument.SecondaryPlatform platform;
        public final MovingPlatformEngine.Pose platformPose;

        TerrainSample(TerrainKind kind, float supportLayer, boolean inBounds) {
            this(kind, supportLayer, inBounds, null, null,
                    CustomMapDocument.SURFACE_NORMAL, null);
        }

        TerrainSample(TerrainKind kind, float supportLayer, boolean inBounds,
                      CustomMapDocument.SecondaryPlatform platform,
                      MovingPlatformEngine.Pose platformPose,
                      byte surfaceMaterial) {
            this(kind, supportLayer, inBounds, platform, platformPose,
                    surfaceMaterial, null);
        }

        TerrainSample(TerrainKind kind, float supportLayer, boolean inBounds,
                      CustomMapDocument.SecondaryPlatform platform,
                      MovingPlatformEngine.Pose platformPose,
                      byte surfaceMaterial, String material) {
            this.kind = kind;
            this.supportLayer = supportLayer;
            this.inBounds = inBounds;
            this.surfaceMaterial = surfaceMaterial == CustomMapDocument.SURFACE_ICE
                    ? CustomMapDocument.SURFACE_ICE
                    : CustomMapDocument.SURFACE_NORMAL;
            this.platform = platform;
            this.platformId = platform == null ? null : platform.id;
            this.platformPose = platformPose;
            this.material = material == null || material.trim().isEmpty()
                    ? kind == TerrainKind.WATER
                    ? CustomMapDocument.MATERIAL_WATER
                    : this.surfaceMaterial == CustomMapDocument.SURFACE_ICE
                    ? CustomMapDocument.MATERIAL_ICE
                    : CustomMapDocument.MATERIAL_NORMAL
                    : material;
        }

        public boolean hasSupport() { return kind == TerrainKind.MAIN || kind == TerrainKind.FLOATING; }

        public boolean isIce() {
            return hasSupport() && surfaceMaterial == CustomMapDocument.SURFACE_ICE;
        }

        public boolean isLava() {
            return kind == TerrainKind.WATER
                    && CustomMapDocument.MATERIAL_LAVA.equals(material);
        }

        public float deltaWorldX(CustomMapDocument.ModeVariant variant) {
            return platformPose == null ? 0f : platformPose.deltaWorldX(variant);
        }

        public float deltaLayer(CustomMapDocument.ModeVariant variant) {
            return platformPose == null ? 0f : platformPose.deltaLayer(variant);
        }
    }

    public static final class SnapshotIceRuntime {
        private final CustomMapDocument doc;
        private final CustomMapDocument.ModeVariant variant;
        private final BreakableIceEngine ice = new BreakableIceEngine();
        private final TerrainHeightfield.SupportAvailability availability;

        public SnapshotIceRuntime(CustomMapDocument doc,
                                  CustomMapDocument.ModeVariant variant) {
            this.doc = doc;
            this.variant = variant;
            this.availability = new TerrainHeightfield.SupportAvailability() {
                @Override public boolean isAvailable(
                        CustomMapDocument.ModeVariant terrain,
                        CustomMapDocument.SecondaryPlatform platform, int tileX) {
                    if (!enabled()) return true;
                    BreakableIceEngine.SupportKey key;
                    if (platform != null) {
                        if (platform.surfaceMaterial != CustomMapDocument.SURFACE_ICE)
                            return true;
                        key = BreakableIceEngine.SupportKey.floating(platform.id, tileX);
                    } else {
                        if (tileX < 0 || tileX >= terrain.width
                                || terrain.surfaceMaterialAt(terrain.worldX(tileX))
                                != CustomMapDocument.SURFACE_ICE
                                || protectedIceTile(terrain, tileX)) return true;
                        key = BreakableIceEngine.SupportKey.main(tileX);
                    }
                    return !keySupported(key) || ice.collisionPresent(key);
                }
            };
        }

        public TerrainSample sample(float worldX, float actorLayer,
                                    boolean falling, long platformTick) {
            return terrainSample(TerrainHeightfield.sample(variant, worldX,
                    actorLayer, falling, true, platformTick, availability));
        }

        public void note(Object actor, float worldX, float actorLayer,
                         boolean grounded, String platformId, long platformTick) {
            if (!enabled() || actor == null || !grounded) return;
            TerrainHeightfield.Contact contact = TerrainHeightfield.sample(
                    variant, worldX, actorLayer, false, true,
                    platformTick, availability);
            if (contact == null || !contact.grounded()
                    || contact.surfaceMaterial != CustomMapDocument.SURFACE_ICE)
                return;
            if (platformId != null && !platformId.equals(contact.platformId)) return;
            BreakableIceEngine.SupportKey key;
            if (contact.platform != null && contact.platformLocalX >= 0)
                key = BreakableIceEngine.SupportKey.floating(
                        contact.platform.id, contact.platformLocalX);
            else if (contact.mainTileX >= 0
                    && !protectedIceTile(variant, contact.mainTileX))
                key = BreakableIceEngine.SupportKey.main(contact.mainTileX);
            else return;
            if (keySupported(key)) ice.reportOccupant(key, actor);
        }

        public void advanceTick() { if (enabled()) ice.advanceTick(); }

        public void reset() { ice.clear(); }

        private boolean enabled() {
            return doc != null && doc.iceSurfaceManifest != null
                    && doc.iceSurfaceManifest.isReady() && variant != null;
        }

        private boolean keySupported(BreakableIceEngine.SupportKey key) {
            if (!enabled() || key == null) return false;
            String topology = null;
            if (key.kind() == BreakableIceEngine.SupportKind.MAIN) {
                if (variant.iceSurfaceKeys != null && key.tileX() >= 0
                        && key.tileX() < variant.iceSurfaceKeys.length)
                    topology = variant.iceSurfaceKeys[key.tileX()];
            } else {
                CustomMapDocument.SecondaryPlatform platform =
                        variant.secondaryPlatform(key.platformId());
                if (platform != null && platform.iceSurfaceKeys != null
                        && key.tileX() >= 0 && key.tileX() < platform.iceSurfaceKeys.length)
                    topology = platform.iceSurfaceKeys[key.tileX()];
            }
            CustomMapDocument.IceSurfaceAssetRef ref = topology == null
                    || topology.isEmpty() ? null : doc.iceSurfaceManifest.find(topology);
            return ref != null && ref.isComplete();
        }
    }

    public static final class AirborneContact {
        public final float worldX;
        public final float actorLayer;
        public final float fraction;
        public final TerrainSample terrain;

        AirborneContact(float worldX, float actorLayer, float fraction,
                        TerrainSample terrain) {
            this.worldX = worldX;
            this.actorLayer = actorLayer;
            this.fraction = fraction;
            this.terrain = terrain;
        }

        public TerrainKind kind() {
            return terrain == null ? TerrainKind.VOID : terrain.kind;
        }

        public boolean hit() {
            return kind() != TerrainKind.VOID;
        }
    }

    public static final class SlopeSample {
        public final boolean onMainRoute;

        public final float risePerTile;

        public final int downhillDirection;

        SlopeSample(boolean onMainRoute, float risePerTile, int downhillDirection) {
            this.onMainRoute = onMainRoute;
            this.risePerTile = risePerTile;
            this.downhillDirection = downhillDirection;
        }

        public boolean isSlope() { return onMainRoute && downhillDirection != 0; }

        public boolean isUphill(int movementDirection) {
            return isSlope() && movementDirection != 0
                    && movementDirection != downhillDirection;
        }
    }

    public static final class GapJump {

        public static final int MIN_BOOSTED_FLIGHT_TICKS = 6;

        public final float startWorldX;
        public final float landingWorldX;
        public final float startLayer;
        public final float landingLayer;
        public final float apexLift;
        public final int direction;
        public final int gapTiles;

        public GapJump(float startWorldX, float landingWorldX, float startLayer,
                       float landingLayer, float apexLift, int direction,
                       int gapTiles) {
            this.startWorldX = startWorldX;
            this.landingWorldX = landingWorldX;
            this.startLayer = startLayer;
            this.landingLayer = landingLayer;
            this.apexLift = apexLift;
            this.direction = direction;
            this.gapTiles = gapTiles;
        }

        public float worldXAt(float progress) {
            float t = clamp01(progress);
            return startWorldX + (landingWorldX - startWorldX) * t;
        }

        public float layerAt(float progress) {
            float t = clamp01(progress);
            float baseline = startLayer + (landingLayer - startLayer) * t;
            return baseline - apexLift * 4f * t * (1f - t);
        }

        public int duration(float preferredHorizontalSpeed) {
            float distance = Math.abs(landingWorldX - startWorldX);
            float speed = Math.max(1f, preferredHorizontalSpeed) * 2.2f;
            return clamp(Math.round(distance / speed), 30, 96);
        }

        public int duration(float preferredHorizontalSpeed, float speedMultiplier) {
            int unboosted = duration(preferredHorizontalSpeed);
            float boost = Math.max(1f, speedMultiplier);
            float distance = Math.abs(landingWorldX - startWorldX);
            float speed = Math.max(1f, preferredHorizontalSpeed) * 2.2f * boost;
            return clamp(Math.round(distance / speed),
                    Math.min(MIN_BOOSTED_FLIGHT_TICKS, unboosted), unboosted);
        }
    }

    public static final class PlatformBoarding {
        public final String platformId;
        public final GapJump jump;
        public final int durationTicks;

        PlatformBoarding(String platformId, GapJump jump, int durationTicks) {
            this.platformId = platformId;
            this.jump = jump;
            this.durationTicks = durationTicks;
        }
    }

    private static final Object LOCK = new Object();
    private static Pending pending;
    private static Binding active;
    private static final Map<String, FakeImage> IMAGE_CACHE = new HashMap<String, FakeImage>();
    private static final Set<String> MISSING_IMAGES = new HashSet<String>();
    private static final Map<String, List<FakeImage>> DEFERRED_IMAGE_UNLOADS =
            new HashMap<String, List<FakeImage>>();
    private static final Map<String, Integer> ALPHA_BOTTOM_CACHE =
            new HashMap<String, Integer>();

    private static final float MIN_NUMERIC_ZOOM = 0.001f;

    private CustomMapRuntime() {}

    public static void invalidateAssets(String uuid) {
        boolean activeScope;
        synchronized (LOCK) {
            activeScope = active != null && active.doc != null
                    && uuid != null && uuid.trim().equals(active.doc.uuid);
        }
        evictAssetScope(uuid, !activeScope);
    }

    private static void evictAssetScope(String uuid, boolean unloadImages) {
        if (uuid == null || uuid.trim().isEmpty()) return;
        String scope = uuid.trim();
        String prefix = "custom_maps/" + scope + "/";
        ArrayList<FakeImage> removed = new ArrayList<FakeImage>();
        synchronized (IMAGE_CACHE) {
            java.util.Iterator<Map.Entry<String, FakeImage>> images =
                    IMAGE_CACHE.entrySet().iterator();
            while (images.hasNext()) {
                Map.Entry<String, FakeImage> entry = images.next();
                if (!entry.getKey().startsWith(prefix)) continue;
                if (entry.getValue() != null)
                    removed.add(entry.getValue());
                images.remove();
            }
            if (unloadImages) {
                List<FakeImage> deferred = DEFERRED_IMAGE_UNLOADS.remove(scope);
                if (deferred != null) removed.addAll(deferred);
            } else if (!removed.isEmpty()) {
                List<FakeImage> deferred = DEFERRED_IMAGE_UNLOADS.get(scope);
                if (deferred == null) {
                    deferred = new ArrayList<FakeImage>();
                    DEFERRED_IMAGE_UNLOADS.put(scope, deferred);
                }
                deferred.addAll(removed);
                removed.clear();
            }
            java.util.Iterator<String> missing = MISSING_IMAGES.iterator();
            while (missing.hasNext()) if (missing.next().startsWith(prefix)) missing.remove();
        }
        synchronized (ALPHA_BOTTOM_CACHE) {
            java.util.Iterator<String> alpha = ALPHA_BOTTOM_CACHE.keySet().iterator();
            while (alpha.hasNext()) if (alpha.next().startsWith(prefix)) alpha.remove();
        }
        Set<FakeImage> unique = Collections.newSetFromMap(
                new IdentityHashMap<FakeImage, Boolean>());
        unique.addAll(removed);
        for (FakeImage image : unique)
            try { image.unload(); } catch (Throwable ignored) {}
    }

    public static void prepare(String uuid, CustomMapDocument.MapMode mode) throws Exception {
        if (uuid == null || uuid.trim().isEmpty()) {
            clearPending();
            return;
        }
        CustomMapDocument doc = CustomMapRepository.load(uuid);
        if (doc == null) throw new IllegalArgumentException("Custom map metadata was not found: " + uuid);
        if (doc.schemaVersion != CustomMapDocument.SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported custom map schema " + doc.schemaVersion + ".");
        doc.themeProfile = CustomMapDocument.ThemeProfile.normalized(doc.themeProfile);
        validateTerrainRevision(doc);
        if (doc.backgroundRevision != CustomMapDocument.BACKGROUND_REVISION
                || doc.backgroundManifest == null || doc.backgroundManifest.bands == null
                || doc.backgroundManifest.bands.isEmpty())
            throw new IllegalArgumentException("This map uses a legacy background composition. "
                    + "Open it in Custom Map Studio and Regenerate it before launch.");
        if (doc.spec == null || !doc.spec.supports(mode))
            throw new IllegalArgumentException(doc.name + " is not compatible with " + mode.title + ".");
        CustomMapDocument.ModeVariant variant = doc.variant(mode);
        if (variant == null) throw new IllegalArgumentException("The " + mode.title + " variant is missing.");
        CustomMapGenerator.validate(variant, mode);
        if (!variant.reachable) throw new IllegalArgumentException(variant.validation);
        validateEmbeddedAssets(doc, variant, mode.id, mode.title);
        if (CustomMapRepository.stage(uuid) == null)
            throw new IllegalArgumentException("The BCU stage entry for this custom map is missing.");
        synchronized (LOCK) {
            pending = new Pending(doc, mode);
        }
        Logger.log("CustomMap: prepared '" + doc.name + "' for " + mode.id);
    }

    public static void clearPending() {
        synchronized (LOCK) { pending = null; }
    }

    public static String pendingMapId() {
        synchronized (LOCK) { return pending == null ? null : pending.doc.uuid; }
    }

    public static CustomMapDocument.MapMode pendingMode() {
        synchronized (LOCK) { return pending == null ? null : pending.mode; }
    }

    public static common.util.stage.Stage pendingStage(CustomMapDocument.MapMode mode) {
        synchronized (LOCK) {
            return pending != null && pending.mode == mode ? CustomMapRepository.stage(pending.doc.uuid) : null;
        }
    }

    public static void adopt(StageBasis stage, CustomMapDocument.MapMode mode) {
        Binding previous;
        Binding replacement;
        synchronized (LOCK) {
            previous = active;
            if (stage != null && pending != null && pending.mode == mode) {
                replacement = new Binding(stage, pending.doc, pending.mode);
                active = replacement;
                Logger.log("CustomMap: runtime adopted '" + pending.doc.name + "' for " + mode.id);
            } else {
                replacement = null;
                active = null;
            }
        }
        evictReplacedBinding(previous, replacement);
    }

    public static void adoptNormal(StageBasis stage, CustomMapDocument doc) throws Exception {
        if (stage == null || doc == null)
            throw new IllegalArgumentException("Normal custom-map battle data is missing.");
        if (doc.schemaVersion != CustomMapDocument.SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported custom map schema " + doc.schemaVersion + ".");
        doc.themeProfile = CustomMapDocument.ThemeProfile.normalized(doc.themeProfile);
        validateTerrainRevision(doc);
        if (doc.battleTerrain == null)
            throw new IllegalArgumentException("This stage uses legacy terrain. Open it in Custom Map Studio "
                    + "and Regenerate it before normal BCU play.");
        if (doc.backgroundRevision != CustomMapDocument.BACKGROUND_REVISION
                || doc.backgroundManifest == null || doc.backgroundManifest.bands == null
                || doc.backgroundManifest.bands.isEmpty())
            throw new IllegalArgumentException("This stage uses a legacy background composition. Regenerate it.");
        CustomMapGenerator.validateBattle(doc.battleTerrain);
        if (!doc.battleTerrain.reachable)
            throw new IllegalArgumentException(doc.battleTerrain.validation);
        validateEmbeddedAssets(doc, doc.battleTerrain, "battle", "normal BCU stage");
        Binding previous;
        Binding replacement;
        synchronized (LOCK) {
            previous = active;
            replacement = new Binding(stage, doc, doc.battleTerrain, null, "battle", true);
            active = replacement;
        }
        evictReplacedBinding(previous, replacement);
        Logger.log("CustomMap: runtime adopted normal BCU stage '" + doc.name + "'");
    }

    public static void release(Object stage) {
        Binding released = null;
        synchronized (LOCK) {
            if (active != null && (stage == null || active.stage == stage)) {
                released = active;
                active = null;
            }
        }
        if (released != null) {
            disposeDynamicIce(released);
            evictAssetScope(released.doc.uuid, true);
        }
    }

    private static void evictReplacedBinding(Binding previous, Binding replacement) {
        if (previous == null || previous == replacement || previous.doc == null) return;
        disposeDynamicIce(previous);
        String oldUuid = previous.doc.uuid;
        String newUuid = replacement == null || replacement.doc == null
                ? null : replacement.doc.uuid;
        if (oldUuid != null && !oldUuid.equals(newUuid)) evictAssetScope(oldUuid, true);
    }

    private static void disposeDynamicIce(Binding binding) {
        if (binding == null) return;
        synchronized (binding.iceRenderLock) {
            for (FakeImage image : binding.iceMaskedImages.values())
                if (image != null) try { image.unload(); } catch (Throwable ignored) {}
            binding.iceMaskedImages.clear();
            binding.iceOriginalCells.clear();
        }
    }

    public static boolean isActiveStage(Object stage) {
        Binding b = active;
        return b != null && b.stage == stage;
    }

    public static int preloadArenaTerrainChunks(Object stage) {
        return preloadArenaTerrainChunks(stage, null);
    }

    public interface ArenaPreloadProgress {
        void onProgress(int completed, int total);
    }

    public static int preloadArenaTerrainChunks(
            Object stage, ArenaPreloadProgress progress) {
        Binding b;
        synchronized (LOCK) {
            b = active;
            if (b == null || b.variant == null || b.doc == null
                    || b.stage != stage) return 0;
        }
        long started = System.nanoTime();
        int chunk = CustomMapDocument.CHUNK_TILES;
        int chunksX = (b.variant.width + chunk - 1) / chunk;
        int chunksY = (b.variant.height + chunk - 1) / chunk;
        int loaded = 0;
        String root = "custom_maps/" + b.doc.uuid + "/" + b.assetId + "/chunks/";
        String[] layers = {"under", "trees", "over"};
        int total = Math.max(1, layers.length * chunksX * chunksY);
        int completed = 0;
        for (int layer = 0; layer < layers.length; layer++) {
            for (int cx = 0; cx < chunksX; cx++) {
                for (int cy = 0; cy < chunksY; cy++) {
                    String path = root + layers[layer] + "/" + cx + "_" + cy + ".png";
                    if (validImage(image(path))) loaded++;
                    completed++;
                    if (progress != null) progress.onProgress(completed, total);
                }
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        Logger.log("CustomMap: Arena preloaded " + loaded + " terrain chunk(s) in "
                + elapsedMs + "ms");
        return loaded;
    }

    public static CustomMapDocument activeDocument() {
        Binding b = active;
        return b == null ? null : b.doc;
    }

    public static CustomMapDocument.ThemeProfile activeThemeProfile() {
        Binding b = active;
        return b == null ? null : b.doc.themeProfile;
    }

    public static boolean isLavaLiquid() {
        CustomMapDocument.ThemeProfile profile = activeThemeProfile();
        return profile != null && profile.isLava();
    }

    public static boolean isLavaLiquidAt(float worldX, float actorLayer) {
        Binding b = active;
        TerrainSample sample = sampleTerrain(worldX, actorLayer, false);
        return sample != null && sample.kind == TerrainKind.WATER
                && lavaProfileAt(b, worldX) != null;
    }

    public static CustomMapDocument.ModeVariant activeVariant(CustomMapDocument.MapMode mode) {
        Binding b = active;
        return b != null && b.mode == mode ? b.variant : null;
    }

    public static CustomMapDocument.ModeVariant activeBattleTerrain() {
        Binding b = active;
        return b != null && b.normalBattle ? b.variant : null;
    }

    public static boolean isNormalBattleStage(Object stage) {
        Binding b = active;
        return b != null && b.normalBattle && b.stage == stage;
    }

    public static float surfaceLayerAt(float worldX, float fallback) {
        Binding b = active;
        if (b == null) return fallback;
        float value = b.variant.surfaceLayerAt(worldX);
        return Float.isNaN(value) ? fallback : value;
    }

    public static float worldWidth() {
        Binding b = active;
        return b == null ? Float.NaN : b.variant.worldWidth();
    }

    public static float worldScale() {
        Binding b = active;
        return b == null ? 1f : b.variant.worldUnitsPerTile() / 100f;
    }

    public static boolean isOutOfBounds(float worldX) {
        Binding b = active;
        return b != null && !b.variant.containsWorldX(worldX);
    }

    public static TerrainSample sampleTerrain(float worldX, float actorLayer) {
        return sampleTerrain(worldX, actorLayer, false);
    }

    public static TerrainSample sampleTerrain(float worldX, float actorLayer,
                                               boolean falling) {
        Binding b = active;
        if (b == null)
            return new TerrainSample(TerrainKind.VOID, Float.NaN, false);
        TerrainHeightfield.Contact contact = TerrainHeightfield.sample(
                b.variant, worldX, actorLayer, falling, true, b.platformTick,
                b.supportAvailability);
        return terrainSample(contact);
    }

    public static TerrainSample sampleTerrainSnapshot(
            CustomMapDocument.ModeVariant variant, float worldX,
            float actorLayer, boolean falling) {
        return sampleTerrainSnapshot(variant, worldX, actorLayer, falling, 0L);
    }

    public static TerrainSample sampleTerrainSnapshot(
            CustomMapDocument.ModeVariant variant, float worldX,
            float actorLayer, boolean falling, long platformTick) {
        if (variant == null)
            return new TerrainSample(TerrainKind.VOID, Float.NaN, false);
        return terrainSample(TerrainHeightfield.sample(
                variant, worldX, actorLayer, falling, true, platformTick,
                TerrainHeightfield.ALLOW_ALL));
    }

    public static float playerSupportLayerAtSnapshot(
            CustomMapDocument.ModeVariant variant, float worldX,
            float currentLayer, boolean falling, float fallback) {
        if (variant == null) return fallback;
        TerrainHeightfield.Contact contact = TerrainHeightfield.sample(
                variant, worldX, currentLayer, falling, true, 0L,
                TerrainHeightfield.ALLOW_ALL);
        return contact.grounded() ? contact.supportLayer
                : contact.kind == TerrainKind.WATER
                || contact.kind == TerrainKind.VOID ? Float.NaN : fallback;
    }

    public static boolean isSolidSnapshot(CustomMapDocument.ModeVariant variant,
                                          float worldX, int layer) {
        if (variant == null || !variant.containsWorldX(worldX)) return false;
        int x = (int) (worldX / variant.worldUnitsPerTile());
        int bottomDistance = Math.max(0,
                Math.round(-layer / (float) variant.layerUnitsPerTile()));
        int row = clamp(variant.height - 1 - bottomDistance, 0, variant.height - 1);
        return variant.cell(x, row) == CustomMapDocument.CELL_GROUND;
    }

    public static TerrainSample sweepLanding(float fromWorldX, float fromLayer,
                                             float toWorldX, float toLayer) {
        Binding b = active;
        if (b == null)
            return new TerrainSample(TerrainKind.VOID, Float.NaN, false);
        return terrainSample(TerrainHeightfield.sweepLanding(b.variant,
                fromWorldX, fromLayer, toWorldX, toLayer, b.platformTick, true,
                b.supportAvailability));
    }

    public static AirborneContact sweepAirborne(
            float fromWorldX, float fromLayer,
            float toWorldX, float toLayer) {
        Binding b = active;
        if (b == null) return new AirborneContact(
                toWorldX, toLayer, 1f,
                new TerrainSample(TerrainKind.VOID, Float.NaN, false));
        TerrainHeightfield.AirborneContact contact =
                TerrainHeightfield.sweepAirborne(b.variant,
                        fromWorldX, fromLayer, toWorldX, toLayer,
                        b.platformTick, true, b.supportAvailability);
        return new AirborneContact(contact.worldX, contact.actorLayer,
                contact.fraction, terrainSample(contact.contact));
    }

    private static TerrainSample terrainSample(TerrainHeightfield.Contact contact) {
        if (contact == null)
            return new TerrainSample(TerrainKind.VOID, Float.NaN, false);
        return new TerrainSample(contact.kind, contact.supportLayer,
                contact.inBounds, contact.platform, contact.platformPose,
                contact.surfaceMaterial, contact.material);
    }

    public static void noteIceOccupant(Object actor, float worldX,
                                       float actorLayer, boolean grounded,
                                       String platformId) {
        Binding b = active;
        if (b == null || b.variant == null || actor == null || !grounded
                || !breakableIceEnabled(b)) return;
        TerrainHeightfield.Contact contact = TerrainHeightfield.sample(
                b.variant, worldX, actorLayer, false, true, b.platformTick,
                b.supportAvailability);
        if (contact == null || !contact.grounded()
                || contact.surfaceMaterial != CustomMapDocument.SURFACE_ICE)
            return;
        if (platformId != null && !platformId.equals(contact.platformId)) return;
        BreakableIceEngine.SupportKey key;
        if (contact.platform != null && contact.platformLocalX >= 0) {
            key = BreakableIceEngine.SupportKey.floating(
                    contact.platform.id, contact.platformLocalX);
        } else if (contact.mainTileX >= 0
                && !protectedIceTile(b.variant, contact.mainTileX)) {
            key = BreakableIceEngine.SupportKey.main(contact.mainTileX);
        } else return;
        if (!breakableIceKeySupported(b, key)) return;
        b.breakableIce.reportOccupant(key, actor);
    }

    private static void advanceBreakableIce(Binding b) {
        b.breakableIce.advanceTick();
        Map<BreakableIceEngine.SupportKey, BreakableIceEngine.VisualState> next =
                b.breakableIce.snapshot();
        Set<BreakableIceEngine.SupportKey> missing =
                new HashSet<BreakableIceEngine.SupportKey>();
        for (Map.Entry<BreakableIceEngine.SupportKey,
                BreakableIceEngine.VisualState> entry : next.entrySet()) {
            if (!entry.getValue().collisionPresent()) missing.add(entry.getKey());
        }
        if (!missing.equals(b.iceMissingSupports)) b.iceMaskRevision++;
        b.iceVisualStates = next;
        b.iceMissingSupports = Collections.unmodifiableSet(missing);
    }

    private static boolean protectedIceTile(
            CustomMapDocument.ModeVariant variant, int tileX) {
        if (variant == null || tileX < 0 || tileX >= variant.width) return true;
        if (variant.spawn != null && variant.spawn.x == tileX) return true;
        if (variant.destination != null && variant.destination.x == tileX) return true;
        if (variant.checkpoints != null)
            for (CustomMapDocument.MapAnchor checkpoint : variant.checkpoints)
                if (checkpoint != null && checkpoint.x == tileX) return true;
        if (variant.baseSafeZones != null)
            for (CustomMapDocument.BaseSafeZone zone : variant.baseSafeZones)
                if (zone != null && zone.containsTile(tileX)) return true;
        return false;
    }

    private static TerrainHeightfield.SupportAvailability iceAvailability(
            final Binding binding) {
        if (binding == null || binding.variant == null)
            return TerrainHeightfield.ALLOW_ALL;
        return new TerrainHeightfield.SupportAvailability() {
            @Override
            public boolean isAvailable(CustomMapDocument.ModeVariant variant,
                                       CustomMapDocument.SecondaryPlatform platform,
                                       int tileX) {
                if (platform != null) {
                    if (platform.surfaceMaterial != CustomMapDocument.SURFACE_ICE)
                        return true;
                    BreakableIceEngine.SupportKey key =
                            BreakableIceEngine.SupportKey.floating(
                                    platform.id, tileX);
                    return !breakableIceKeySupported(binding, key)
                            || binding.breakableIce.collisionPresent(key);
                }
                if (tileX < 0 || tileX >= variant.width
                        || variant.surfaceMaterialAt(variant.worldX(tileX))
                        != CustomMapDocument.SURFACE_ICE
                        || protectedIceTile(variant, tileX)) return true;
                BreakableIceEngine.SupportKey key =
                        BreakableIceEngine.SupportKey.main(tileX);
                return !breakableIceKeySupported(binding, key)
                        || binding.breakableIce.collisionPresent(key);
            }
        };
    }

    private static boolean breakableIceEnabled(Binding b) {
        return b != null && b.doc != null && b.doc.iceSurfaceManifest != null
                && b.doc.iceSurfaceManifest.isReady();
    }

    private static boolean breakableIceKeySupported(
            Binding b, BreakableIceEngine.SupportKey key) {
        if (!breakableIceEnabled(b) || key == null) return false;
        String topology = null;
        if (key.kind() == BreakableIceEngine.SupportKind.MAIN) {
            if (b.variant.iceSurfaceKeys != null && key.tileX() >= 0
                    && key.tileX() < b.variant.iceSurfaceKeys.length)
                topology = b.variant.iceSurfaceKeys[key.tileX()];
        } else {
            CustomMapDocument.SecondaryPlatform platform =
                    b.variant.secondaryPlatform(key.platformId());
            if (platform != null && platform.iceSurfaceKeys != null
                    && key.tileX() >= 0
                    && key.tileX() < platform.iceSurfaceKeys.length)
                topology = platform.iceSurfaceKeys[key.tileX()];
        }
        CustomMapDocument.IceSurfaceAssetRef ref = topology == null
                || topology.isEmpty() ? null
                : b.doc.iceSurfaceManifest.find(topology);
        return ref != null && ref.isComplete();
    }

    public static void tickMovingPlatforms() {
        tickMovingPlatforms(true);
    }

    public static void tickMovingPlatforms(boolean tickLiquidHazard) {
        Binding b = active;
        if (b == null || b.variant == null) return;
        advanceBreakableIce(b);
        if (tickLiquidHazard) tickLavaHazards(b);
        if (!b.variant.hasEnabledPlatformPatrols()) return;
        b.platformTick++;
        for (PlatformRuntimeState state : b.platformStates.values()) {
            state.expireBoarders(b.platformTick);
            MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                    b.variant, state.platform, state.localTick);
            boolean dwelling = MovingPlatformEngine.isBoardingStop(pose);
            if (!dwelling) {
                state.gateClosed = false;
                state.stationHoldStartedTick = Long.MIN_VALUE;
            }
            long remaining = MovingPlatformEngine.remainingDwellTicks(
                    b.variant, state.platform, state.localTick);
            if (dwelling && remaining <= 1L && !state.boarders.isEmpty()) {
                state.gateClosed = true;
                if (state.stationHoldStartedTick == Long.MIN_VALUE)
                    state.stationHoldStartedTick = b.platformTick;
                long heldTicks = b.platformTick - state.stationHoldStartedTick;
                if (shouldHoldPlatformAtStation(
                        true, remaining, state.boarders.size(), heldTicks))
                    continue;
            } else if (dwelling && remaining > 1L) {
                state.stationHoldStartedTick = Long.MIN_VALUE;
            }
            state.localTick++;
        }
    }

    public static void tickLiquidHazards() {
        Binding b = active;
        if (b != null && b.variant != null) tickLavaHazards(b);
    }

    private static void tickLavaHazards(Binding b) {
        if (b.stage == null || b.stage.le == null) {
            b.liquidExposure.clear();
            return;
        }
        for (Entity entity : b.stage.le) {
            CustomMapDocument.ThemeLiquidProfile liquid =
                    lavaProfileAt(b, entity == null ? Float.NaN : entity.pos);
            if (entity == null || entity.isBase() || entity.dead || entity.health <= 0L
                    || liquid == null
                    || !isSwimmingInLiquid(b.variant, entity.pos, entity.currentLayer)) {
                if (entity != null) b.liquidExposure.remove(entity);
                continue;
            }
            Integer previous = b.liquidExposure.get(entity);
            LavaHazardStep step = advanceLavaHazard(
                    previous == null ? 0 : previous, true,
                    entity.health, entity.maxH, liquid,
                    entity.pos, entity.currentLayer, -entity.dire, true);
            b.liquidExposure.put(entity, step.exposureTicks);
            entity.health = step.health;
        }
    }

    public static int advanceLiquidExposure(int previousTicks, boolean submerged) {
        return nextLiquidExposure(previousTicks, submerged);
    }

    public static long liquidDamageTick(int exposureTicks, long health, long maxHealth,
                                        float worldX, float layer, int direction) {
        Binding b = active;
        CustomMapDocument.ThemeLiquidProfile liquid = lavaProfileAt(b, worldX);
        if (liquid == null) return health;
        if (!lavaPulseDue(exposureTicks, liquid)) return health;
        long next = lavaHealthAfterPulse(health, maxHealth, liquid);
        if (next < health) CustomMapLavaFeedback.emit(worldX, layer, direction);
        return next;
    }

    private static CustomMapDocument.ThemeLiquidProfile lavaProfileAt(
            Binding binding, float worldX) {
        if (binding == null || binding.variant == null || Float.isNaN(worldX))
            return null;
        String explicit = binding.variant.explicitLiquidMaterialAt(worldX);
        if (CustomMapDocument.MATERIAL_WATER.equals(explicit)) return null;
        CustomMapDocument.ThemeLiquidProfile source = binding.doc == null
                || binding.doc.themeProfile == null
                ? null : binding.doc.themeProfile.liquid;
        if (!CustomMapDocument.MATERIAL_LAVA.equals(explicit)
                && (source == null || !"lava".equals(source.kind))) return null;
        if (source != null && "lava".equals(source.kind)) return source;
        CustomMapDocument.ThemeLiquidProfile fallback =
                new CustomMapDocument.ThemeLiquidProfile();
        fallback.kind = "lava";
        return fallback;
    }

    public static void emitLavaDamageFeedback(float worldX, float layer,
                                              int direction) {
        CustomMapLavaFeedback.emit(worldX, layer, direction);
    }

    static final class LavaHazardStep {
        final int exposureTicks;
        final long health;
        final boolean damaged;

        LavaHazardStep(int exposureTicks, long health, boolean damaged) {
            this.exposureTicks = exposureTicks;
            this.health = health;
            this.damaged = damaged;
        }
    }

    static LavaHazardStep advanceLavaHazard(
            int previousExposureTicks, boolean swimming,
            long health, long maxHealth,
            CustomMapDocument.ThemeLiquidProfile liquid,
            float worldX, float layer, int direction, boolean emitFeedback) {
        if (!swimming || liquid == null || !"lava".equals(liquid.kind))
            return new LavaHazardStep(0, health, false);
        int exposure = nextLiquidExposure(previousExposureTicks, true);
        long nextHealth = lavaPulseDue(exposure, liquid)
                ? lavaHealthAfterPulse(health, maxHealth, liquid) : health;
        boolean damaged = nextHealth < health;
        if (damaged && emitFeedback)
            CustomMapLavaFeedback.emit(worldX, layer, direction);
        return new LavaHazardStep(exposure, nextHealth, damaged);
    }

    private static boolean isSwimmingInLiquid(
            CustomMapDocument.ModeVariant variant, float worldX, float actorLayer) {
        if (variant == null || !variant.containsWorldX(worldX)
                || !variant.isWaterAt(worldX)) return false;
        int x = (int) (worldX / variant.worldUnitsPerTile());
        float surface = waterLayer(variant, x);
        return !Float.isNaN(surface) && actorLayer >= surface - 1f;
    }

    static boolean lavaPulseDue(int exposureTicks,
                                CustomMapDocument.ThemeLiquidProfile liquid) {
        return CustomMapPhysicsRules.lavaPulseDue(exposureTicks, liquid);
    }

    static int nextLiquidExposure(int previousTicks, boolean swimming) {
        return CustomMapPhysicsRules.nextLiquidExposure(previousTicks, swimming);
    }

    static long lavaHealthAfterPulse(long health, long maxHealth,
                                     CustomMapDocument.ThemeLiquidProfile liquid) {
        return CustomMapPhysicsRules.lavaHealthAfterPulse(
                health, maxHealth, liquid);
    }

    public static void resetMovingPlatforms() {
        Binding b = active;
        if (b != null) {
            b.platformTick = 0L;
            b.liquidExposure.clear();
            b.breakableIce.clear();
            b.iceVisualStates = Collections.emptyMap();
            b.iceMissingSupports = Collections.emptySet();
            b.iceMaskRevision++;
            for (PlatformRuntimeState state : b.platformStates.values())
                state.reset();
        }
    }

    public static long platformTick() {
        Binding b = active;
        return b == null ? 0L : b.platformTick;
    }

    public static MovingPlatformEngine.Pose platformPose(String platformId) {
        Binding b = active;
        if (b == null || b.variant == null) return null;
        CustomMapDocument.SecondaryPlatform platform =
                b.variant.secondaryPlatform(platformId);
        return platform == null ? null : MovingPlatformEngine.poseAtTick(
                b.variant, platform, platformEvaluationTick(
                b.variant, platform, b.platformTick));
    }

    static long platformEvaluationTick(CustomMapDocument.ModeVariant variant,
                                       CustomMapDocument.SecondaryPlatform platform,
                                       long fallbackTick) {
        Binding b = active;
        if (b == null || b.variant != variant || platform == null) return fallbackTick;
        PlatformRuntimeState state = b.platformStates.get(platform.id);
        return state == null ? fallbackTick : state.localTick;
    }

    public static boolean canBeginPlatformBoarding(String platformId) {
        Binding b = active;
        PlatformRuntimeState state = b == null ? null
                : b.platformStates.get(platformId);
        if (state == null || state.gateClosed) return false;
        MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                b.variant, state.platform, state.localTick);
        return MovingPlatformEngine.isBoardingStop(pose);
    }

    public static boolean beginPlatformBoarding(String platformId, Object rider,
                                                 int expectedTicks) {
        Binding b = active;
        PlatformRuntimeState state = b == null ? null
                : b.platformStates.get(platformId);
        if (state == null || rider == null) return false;
        MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                b.variant, state.platform, state.localTick);
        if (!MovingPlatformEngine.isBoardingStop(pose)) return false;

        if (state.gateClosed && !state.boarders.containsKey(rider)) return false;
        state.boarders.put(rider,
                b.platformTick + Math.max(36, expectedTicks + 12));
        return true;
    }

    public static boolean beginPlatformDockCross(String platformId, Object rider,
                                                 int expectedTicks) {
        Binding b = active;
        PlatformRuntimeState state = b == null ? null
                : b.platformStates.get(platformId);
        if (state == null || rider == null) return false;
        state.boarders.put(rider,
                b.platformTick + Math.max(36, expectedTicks + 12));
        return true;
    }

    public static void notePlatformRider(String platformId, Object rider) {
        Binding b = active;
        PlatformRuntimeState state = b == null || platformId == null
                ? null : b.platformStates.get(platformId);
        if (state == null || rider == null) return;
        if (state.boarders.containsKey(rider))
            state.boarders.put(rider, b.platformTick + PLATFORM_RIDER_LEASE_TICKS);
    }

    public static String platformDebug(String platformId) {
        Binding b = active;
        PlatformRuntimeState state = b == null || platformId == null
                ? null : b.platformStates.get(platformId);
        if (state == null) return "state=missing";
        MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                b.variant, state.platform, state.localTick);
        return "localTick=" + state.localTick
                + " leg=" + pose.leg
                + " center=" + round2(pose.centerTileX)
                + " supportY=" + round2(pose.supportTileY)
                + " remainingDwell=" + MovingPlatformEngine.remainingDwellTicks(
                        b.variant, state.platform, state.localTick)
                + " gateClosed=" + state.gateClosed
                + " boarders=" + state.boarders.size()
                + " held=" + (state.stationHoldStartedTick == Long.MIN_VALUE
                        ? -1L : b.platformTick - state.stationHoldStartedTick);
    }

    private static float round2(float value) {
        return Math.round(value * 100f) / 100f;
    }

    public static void finishPlatformBoarding(String platformId, Object rider) {
        Binding b = active;
        PlatformRuntimeState state = b == null ? null
                : b.platformStates.get(platformId);
        if (state != null && rider != null) state.boarders.remove(rider);
    }

    static boolean shouldHoldPlatformAtStation(boolean dwelling,
                                               long remainingDwellTicks,
                                               int passengerCount,
                                               long heldTicks) {
        return dwelling && remainingDwellTicks <= 1L && passengerCount > 0
                && heldTicks < MAX_STATION_HOLD_TICKS;
    }

    public static PlatformBoarding findPlatformBoarding(
            float actorWorldX, float actorLayer, String sourcePlatformId,
            int movementDirection) {
        Binding b = active;
        if (b == null || b.variant == null || movementDirection == 0
                || b.variant.secondaryPlatforms == null) return null;
        CustomMapDocument.ModeVariant terrain = b.variant;
        float units = terrain.worldUnitsPerTile();
        float currentSupport;
        if (sourcePlatformId == null) {
            currentSupport = terrain.surfaceLayerAt(actorWorldX);
        } else {
            CustomMapDocument.SecondaryPlatform source =
                    terrain.secondaryPlatform(sourcePlatformId);
            if (source == null || source.isPatrolling()) return null;
            currentSupport = source.collisionSupportLayer(
                    source.supportLayer, terrain.layerUnitsPerTile());
        }
        if (Float.isNaN(currentSupport)
                || Math.abs(actorLayer - currentSupport)
                > terrain.layerUnitsPerTile() * .30f) return null;

        float actorTileX = actorWorldX / units;
        PlatformBoarding nearest = null;
        float nearestApproach = Float.POSITIVE_INFINITY;
        for (CustomMapDocument.SecondaryPlatform platform
                : terrain.secondaryPlatforms) {
            if (platform == null || !platform.isPatrolling()
                    || !canBeginPlatformBoarding(platform.id)) continue;
            MovingPlatformEngine.Pose pose = platformPose(platform.id);
            if (!MovingPlatformEngine.isBoardingStop(pose)) continue;
            MovingPlatformDocking.Target dock = MovingPlatformDocking.find(
                    terrain, platform, pose.centerTileX, pose.supportTileY,
                    -movementDirection);
            if (dock == null || !sameId(dock.platformId, sourcePlatformId)
                    || Math.abs(dock.supportLayer - currentSupport)
                    > terrain.layerUnitsPerTile() * .25f) continue;

            float nearEdge = movementDirection > 0
                    ? pose.collisionLeftTileX(platform)
                    : pose.collisionRightTileX(platform);
            float approach = movementDirection > 0
                    ? nearEdge - actorTileX : actorTileX - nearEdge;
            if (approach < -.06f || approach > .70f) continue;
            float approachDistance = Math.abs(approach);
            if (approachDistance >= nearestApproach) continue;
            float landingWorldX = (nearEdge + movementDirection * .18f) * units;
            float distanceTiles = Math.abs(landingWorldX - actorWorldX) / units;
            int duration = clamp(Math.round(12f + distanceTiles * 12f), 12, 24);
            float landingSupport = pose.collisionSupportLayer(terrain, platform);
            float rise = Math.max(0f, currentSupport - landingSupport);
            GapJump jump = new GapJump(actorWorldX, landingWorldX,
                    currentSupport, landingSupport,
                    terrain.layerUnitsPerTile() * .55f + rise * .55f,
                    movementDirection, 0);
            nearest = new PlatformBoarding(platform.id, jump, duration);
            nearestApproach = approachDistance;
        }
        return nearest;
    }

    public static PlatformBoarding findPriorityPlatformBoarding(
            float actorWorldX, float actorLayer, String sourcePlatformId,
            int preferredDirection) {
        int direction = preferredDirection < 0 ? -1
                : preferredDirection > 0 ? 1 : 0;
        if (direction == 0) return null;
        return findPlatformBoarding(actorWorldX, actorLayer,
                sourcePlatformId, direction);
    }

    private static boolean sameId(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static float platformDeltaWorldX(String platformId) {
        Binding b = active;
        MovingPlatformEngine.Pose pose = platformPose(platformId);
        return b == null || pose == null ? 0f : pose.deltaWorldX(b.variant);
    }

    public static float platformDeltaLayer(String platformId) {
        Binding b = active;
        MovingPlatformEngine.Pose pose = platformPose(platformId);
        return b == null || pose == null ? 0f : pose.deltaLayer(b.variant);
    }

    public static boolean directLineBlocked(float ax, float ay,
                                            float bx, float by) {
        Binding binding = active;
        if (binding == null || binding.variant == null) return false;
        CustomMapDocument.ModeVariant terrain = binding.variant;
        float distance = Math.abs(bx - ax);
        if (distance < terrain.worldUnitsPerTile() * .12f) return false;
        int steps = Math.max(4, (int) Math.ceil(distance
                / Math.max(1f, terrain.worldUnitsPerTile() * .20f)));
        TerrainSample start = sampleTerrain(ax, ay, false);
        TerrainSample end = sampleTerrain(bx, by, false);
        String shared = start.platformId != null
                && start.platformId.equals(end.platformId)
                ? start.platformId : null;
        for (int i = 1; i < steps; i++) {
            float f = i / (float) steps;
            float x = ax + (bx - ax) * f;
            float line = ay + (by - ay) * f;
            float main = terrain.surfaceLayerAt(x);
            if (!Float.isNaN(main)
                    && main < line - terrain.layerUnitsPerTile() * .20f)
                return true;
            if (terrain.secondaryPlatforms == null) continue;
            float tileX = x / terrain.worldUnitsPerTile();
            for (CustomMapDocument.SecondaryPlatform platform
                    : terrain.secondaryPlatforms) {
                if (platform == null || platform.id.equals(shared)) continue;
                MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                        terrain, platform, platformEvaluationTick(
                        terrain, platform, binding.platformTick));
                if (tileX < pose.collisionLeftTileX(platform)
                        || tileX >= pose.collisionRightTileX(platform)) continue;
                float top = pose.collisionSupportLayer(terrain, platform);
                float bottom = top + terrain.layerUnitsPerTile();
                if (line >= top + terrain.layerUnitsPerTile() * .08f
                        && line <= bottom) return true;
            }
        }
        return false;
    }

    public static SlopeSample sampleSlope(float worldX) {
        Binding b = active;
        return b == null ? new SlopeSample(false, 0f, 0)
                : sampleSlope(b.variant, worldX);
    }

    public static SlopeSample sampleSlope(CustomMapDocument.ModeVariant variant,
                                          float worldX) {
        if (variant == null || !variant.containsWorldX(worldX)
                || variant.surface == null || variant.slopeDirection == null)
            return new SlopeSample(false, 0f, 0);
        int units = variant.worldUnitsPerTile();
        int column = clamp((int) (worldX / units), 0, variant.width - 1);
        if (variant.surface[column] < 0 || variant.water[column])
            return new SlopeSample(false, 0f, 0);

        int direction = variant.slopeDirection[column];
        if (direction == 0) {

            float local = worldX / units - column;
            if (local < 0.18f && column > 0) direction = variant.slopeDirection[column - 1];
            else if (local > 0.82f && column + 1 < variant.width)
                direction = variant.slopeDirection[column + 1];
        }
        if (direction == 0) return new SlopeSample(true, 0f, 0);

        float span = units * 0.35f;
        float left = variant.surfaceLayerAt(Math.max(0.001f, worldX - span));
        float right = variant.surfaceLayerAt(Math.min(variant.worldWidth() - 0.001f,
                worldX + span));
        float rise = (!Float.isNaN(left) && !Float.isNaN(right))
                ? (right - left) * units / Math.max(1f, span * 2f)
                : direction * variant.layerUnitsPerTile();
        if (Math.abs(rise) < variant.layerUnitsPerTile() * 0.01f)
            rise = direction * variant.layerUnitsPerTile() * 0.01f;
        return new SlopeSample(true, rise, direction > 0 ? 1 : -1);
    }

    public static boolean isUphillMovement(float fromWorldX, float toWorldX) {
        Binding b = active;
        return b != null && isUphillMovement(b.variant, fromWorldX, toWorldX);
    }

    public static boolean isUphillMovement(CustomMapDocument.ModeVariant variant,
                                           float fromWorldX, float toWorldX) {
        int movement = toWorldX > fromWorldX ? 1 : toWorldX < fromWorldX ? -1 : 0;
        if (movement == 0) return false;
        return sampleSlope(variant, (fromWorldX + toWorldX) * 0.5f).isUphill(movement);
    }

    public static boolean canSlide(float fromWorldX, float toWorldX, int direction) {
        Binding b = active;
        if (b == null || direction == 0 || !canEnemyWalk(fromWorldX, toWorldX)) return false;
        SlopeSample from = sampleSlope(b.variant, fromWorldX);
        SlopeSample to = sampleSlope(b.variant, toWorldX);
        return from.isSlope() && from.downhillDirection == direction
                && (to.isSlope() ? to.downhillDirection == direction : true);
    }

    public static GapJump findGapJump(float worldX, int direction) {
        Binding b = active;
        return b == null ? null : findGapJump(b.variant, worldX, direction);
    }

    public static GapJump findGapJump(CustomMapDocument.ModeVariant variant,
                                      float worldX, int direction) {
        if (variant == null || direction == 0 || !variant.containsWorldX(worldX)
                || variant.surface == null || variant.water == null) return null;
        direction = direction > 0 ? 1 : -1;
        int units = variant.worldUnitsPerTile();
        int from = clamp((int) (worldX / units), 0, variant.width - 1);
        if (variant.surface[from] < 0 || variant.water[from]) return null;

        CustomMapDocument.NavigationLink authored = TerrainHeightfield.link(
                variant, worldX, direction, CustomMapDocument.NavigationType.JUMP);
        int scan;
        int gapTiles;
        if (authored != null) {
            scan = direction > 0 ? authored.toX : authored.fromX;
            gapTiles = authored.spanEndX - authored.spanStartX + 1;
        } else {

            if (variant.navigationLinks != null && !variant.navigationLinks.isEmpty()) return null;
            int gap = from + direction;
            if (gap < 0 || gap >= variant.width || variant.water[gap]
                    || variant.surface[gap] >= 0) return null;
            gapTiles = 0;
            scan = gap;
            while (scan >= 0 && scan < variant.width
                    && variant.surface[scan] < 0 && !variant.water[scan]) {
                gapTiles++;
                scan += direction;
            }
        }
        int maxGap = variant.profile == null ? 0 : variant.profile.maxJumpGap;
        if (gapTiles <= 0 || gapTiles > maxGap || scan < 0 || scan >= variant.width
                || variant.surface[scan] < 0 || variant.water[scan]) return null;

        int maxStep = variant.profile == null ? 0 : variant.profile.maxStepRows;
        if (variant.profile != null
                && "heist".equalsIgnoreCase(variant.profile.profileId)) maxStep = Math.max(maxStep, 3);
        if (variant.profile != null
                && "adventure".equalsIgnoreCase(variant.profile.profileId)) maxStep = Math.max(maxStep, 5);
        if (Math.abs(variant.surface[scan] - variant.surface[from]) > maxStep) return null;
        float startLayer = variant.surfaceLayerAt(worldX);
        float landingX = (scan + (direction > 0 ? 0.35f : 0.65f)) * units;
        float landingLayer = variant.surfaceLayerAt(landingX);
        if (Float.isNaN(startLayer) || Float.isNaN(landingLayer)) return null;
        float heightRows = Math.abs(variant.surface[scan] - variant.surface[from]);
        float apex = variant.layerUnitsPerTile() * (1.25f + Math.min(0.9f, heightRows * 0.16f));
        return new GapJump(worldX, landingX, startLayer, landingLayer,
                apex, direction, gapTiles);
    }

    public static boolean belowVoidKillPlane(float actorLayer) {
        Binding b = active;
        return b != null && actorLayer >= b.variant.layerUnitsPerTile() * 2f;
    }

    public static float voidKillLayer() {
        Binding b = active;
        return b == null ? 0f : b.variant.layerUnitsPerTile() * 2f;
    }

    public static boolean isWater(float worldX) {
        Binding b = active;
        return b != null && b.variant.isWaterAt(worldX);
    }

    public static float waterSurfaceLayerAt(float worldX) {
        Binding b = active;
        if (b == null || !b.variant.containsWorldX(worldX)) return Float.NaN;
        int x = (int) (worldX / b.variant.worldUnitsPerTile());
        return waterLayer(b.variant, x);
    }

    public static boolean isWaterHazard(float worldX, float actorLayer) {
        Binding b = active;
        if (b == null || !b.variant.isWaterAt(worldX)) return false;
        if (!b.variant.containsWorldX(worldX)) return false;
        int x = (int) (worldX / b.variant.worldUnitsPerTile());
        for (int row = 0; row < b.variant.height; row++) {
            if (b.variant.cell(x, row) == CustomMapDocument.CELL_WATER) {
                float waterLayer = -(b.variant.height - row) * b.variant.layerUnitsPerTile();
                return actorLayer >= waterLayer - 1f;
            }
        }
        return false;
    }

    public static boolean canEnemyWalk(float fromWorldX, float toWorldX) {
        Binding b = active;
        if (b == null) return true;
        TerrainHeightfield.Sweep sweep = sweepMain(b.variant, fromWorldX,
                toWorldX, b.variant.layerUnitsPerTile() * 0.55f);
        return !sweep.blocked && sweep.contact.kind == TerrainKind.MAIN;
    }

    static TerrainHeightfield.Sweep sweepMain(
            CustomMapDocument.ModeVariant terrain, float fromWorldX,
            float toWorldX, float maximumRiseLayer) {
        Binding b = active;
        return TerrainHeightfield.sweepMain(terrain, fromWorldX, toWorldX,
                maximumRiseLayer, b != null && b.variant == terrain
                        ? b.supportAvailability : TerrainHeightfield.ALLOW_ALL);
    }

    static TerrainHeightfield.MainStep firstMainStep(
            CustomMapDocument.ModeVariant terrain, float fromWorldX,
            float toWorldX) {
        Binding b = active;
        return TerrainHeightfield.firstMainStep(terrain, fromWorldX, toWorldX,
                b != null && b.variant == terrain
                        ? b.supportAvailability : TerrainHeightfield.ALLOW_ALL);
    }

    static TerrainHeightfield.Contact sampleMain(
            CustomMapDocument.ModeVariant terrain, float worldX,
            float actorLayer) {
        Binding b = active;
        return TerrainHeightfield.sample(terrain, worldX, actorLayer,
                false, false, b == null ? 0L : b.platformTick,
                b != null && b.variant == terrain
                        ? b.supportAvailability : TerrainHeightfield.ALLOW_ALL);
    }

    public static boolean isSolid(float worldX, int layer) {
        Binding b = active;
        if (b == null) return false;
        if (!b.variant.containsWorldX(worldX)) return false;
        int x = (int) (worldX / b.variant.worldUnitsPerTile());
        int bottomDistance = Math.max(0, Math.round(-layer / (float) b.variant.layerUnitsPerTile()));
        int row = clamp(b.variant.height - 1 - bottomDistance, 0, b.variant.height - 1);
        if (isPatrolOriginCell(b.variant, x, row)) return false;
        if (b.variant.surfaceMaterialAt(worldX) == CustomMapDocument.SURFACE_ICE
                && !b.supportAvailability.isAvailable(b.variant, null, x))
            return false;
        return b.variant.cell(x, row) == CustomMapDocument.CELL_GROUND;
    }

    public static float playerSupportLayerAt(float worldX, float currentLayer,
                                             boolean falling, float fallback) {
        Binding b = active;
        if (b == null) return fallback;
        TerrainHeightfield.Contact contact = TerrainHeightfield.sample(
                b.variant, worldX, currentLayer, falling, true, b.platformTick,
                b.supportAvailability);
        return contact.grounded() ? contact.supportLayer
                : contact.kind == TerrainKind.WATER
                || contact.kind == TerrainKind.VOID ? Float.NaN : fallback;
    }

    public static boolean blocksSight(float fromWorldX, float toWorldX) {
        Binding b = active;
        if (b == null || b.mode != CustomMapDocument.MapMode.HEIST) return false;
        float lo = Math.min(fromWorldX, toWorldX);
        float hi = Math.max(fromWorldX, toWorldX);
        for (CustomMapDocument.TreePlacement tree : b.variant.trees) {
            float x = b.variant.worldX(tree.x) + tree.xOffsetPercent / 100f
                    * b.variant.worldUnitsPerTile();
            float half = Math.max(1, tree.widthTiles) * b.variant.worldUnitsPerTile() * 0.5f;
            if (x + half > lo + 24f && x - half < hi - 24f) return true;
        }
        return false;
    }

    public static void noteCheckpoint(float worldX) {
        Binding b = active;
        if (b == null || b.variant.checkpoints == null) return;
        boolean forward = b.variant.destination.x >= b.variant.spawn.x;
        for (int i = 0; i < b.variant.checkpoints.size(); i++) {
            CustomMapDocument.MapAnchor cp = b.variant.checkpoints.get(i);
            float x = b.variant.worldX(cp.x);
            if ((forward && worldX >= x) || (!forward && worldX <= x)) b.lastCheckpoint = i;
        }
    }

    public static float lastCheckpoint() {
        Binding b = active;
        if (b == null) return Float.NaN;
        return checkpointWorldX(b);
    }

    public static float respawnWorldX(float fallback) {
        Binding b = active;
        return b == null ? fallback : checkpointWorldX(b);
    }

    public static List<Float> findPath(float fromWorldX, float toWorldX) {
        Binding b = active;
        if (b == null) return Collections.emptyList();
        if (!b.variant.containsWorldX(fromWorldX) || !b.variant.containsWorldX(toWorldX))
            return Collections.emptyList();
        int from = (int) (fromWorldX / b.variant.worldUnitsPerTile());
        int to = (int) (toWorldX / b.variant.worldUnitsPerTile());
        if (b.variant.navigationLinks != null && !b.variant.navigationLinks.isEmpty())
            return navigationPath(b.variant, from, to);
        if (b.variant.surfaceGraph == null || b.variant.surfaceGraph.isEmpty())
            return sequentialPath(b.variant, from, to);
        int[] previous = new int[b.variant.width];
        java.util.Arrays.fill(previous, -2);
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<Integer>();
        previous[from] = -1;
        open.add(from);
        while (!open.isEmpty() && previous[to] == -2) {
            int x = open.removeFirst();
            for (CustomMapDocument.SurfaceEdge edge : b.variant.surfaceGraph) {
                int next = edge.fromX == x ? edge.toX : edge.toX == x ? edge.fromX : -1;
                if (next >= 0 && previous[next] == -2) {
                    previous[next] = x;
                    open.add(next);
                }
            }
        }
        if (previous[to] == -2) return Collections.emptyList();
        ArrayList<Float> path = new ArrayList<Float>();
        for (int x = to; x >= 0; x = previous[x]) {
            path.add(b.variant.worldX(x));
            if (x == from) break;
        }
        Collections.reverse(path);
        return path;
    }

    private static List<Float> navigationPath(CustomMapDocument.ModeVariant variant,
                                              int from, int to) {
        int[] previous = new int[variant.width];
        java.util.Arrays.fill(previous, -2);
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<Integer>();
        previous[from] = -1;
        open.add(from);
        while (!open.isEmpty() && previous[to] == -2) {
            int x = open.removeFirst();
            for (CustomMapDocument.NavigationLink link : variant.navigationLinks) {
                if (link == null) continue;
                int next = link.fromX == x ? link.toX
                        : link.bidirectional && link.toX == x ? link.fromX : -1;
                if (next >= 0 && next < variant.width && previous[next] == -2) {
                    previous[next] = x;
                    open.add(next);
                }
            }
        }
        if (previous[to] == -2) return Collections.emptyList();
        ArrayList<Float> path = new ArrayList<Float>();
        for (int x = to; x >= 0; x = previous[x]) {
            path.add(variant.worldX(x));
            if (x == from) break;
        }
        Collections.reverse(path);
        return path;
    }

    private static List<Float> sequentialPath(CustomMapDocument.ModeVariant variant, int from, int to) {
        int direction = from <= to ? 1 : -1;
        ArrayList<Float> path = new ArrayList<Float>();
        for (int x = from; ; x += direction) {
            if (variant.surface[x] >= 0) path.add(variant.worldX(x));
            if (x == to) break;
        }
        return path;
    }

    public static void followLayer(float layer) {
        Binding b = active;
        if (b == null || !finite(layer)) return;
        b.focusLayer = layer;
        b.focusMinLayer = layer;
        b.focusMaxLayer = layer;
    }

    public static void beginVerticalCameraDrag(Object painter, int mouseY) {
        Binding b = active;
        if (b == null || painter == null) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            b.verticalCameraDragging = true;
            b.lastVerticalCameraDragY = mouseY;
        } catch (Throwable t) {
            Logger.err("CustomMap: vertical camera drag could not start", t);
        }
    }

    public static void dragVerticalCamera(Object painter, int mouseY) {
        Binding b = active;
        if (b == null || painter == null || !b.verticalCameraDragging) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            int delta = mouseY - b.lastVerticalCameraDragY;
            b.lastVerticalCameraDragY = mouseY;
            b.cameraPixels = clampCameraToComposition(
                    painter, b, b.cameraPixels + delta);
            b.cameraInitialized = true;
        } catch (Throwable t) {
            Logger.err("CustomMap: vertical camera drag failed", t);
        }
    }

    public static void endVerticalCameraDrag() {
        Binding b = active;
        if (b != null) b.verticalCameraDragging = false;
    }

    public static boolean isVerticalCameraDragging(Object painter) {
        Binding b = active;
        if (b == null || painter == null || !b.verticalCameraDragging) return false;
        try {
            return BBPainterAccess.getStageBasis(painter) == b.stage;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static float projectY(Object painter, float layer) {
        Binding b = active;
        return rawGroundY(painter, layer)
                + (b == null || usesNativeVerticalProjection(b)
                ? 0f : b.cameraPixels);
    }

    public static void applyManualVerticalCamera(Object painter) {
        Binding b = active;
        if (b == null || painter == null) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            initializeBattleCamera(painter, b);
            b.cameraPixels = clampCameraToComposition(
                    painter, b, b.cameraPixels);
            if (usesNativeVerticalProjection(b) && Math.abs(b.cameraPixels) > .01f)
                BCUFields.setInt(painter, "midh",
                        BBPainterAccess.getMidh(painter)
                                + Math.round(b.cameraPixels));
        } catch (Throwable t) {
            Logger.err("CustomMap: manual vertical camera projection failed", t);
        }
    }

    public static boolean onWheel(Object painter, Point point, int rotation) {
        Binding b = active;
        if (b == null || painter == null || point == null || rotation == 0)
            return false;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return false;
            float current = BBPainterAccess.getSiz(painter);
            float max = BCUFields.getFloat(painter, "maxSiz");
            float next = zoomAfterWheel(current, rotation, max);
            int oldPos = BBPainterAccess.getStagePos(painter);
            float ratio = next / Math.max(MIN_NUMERIC_ZOOM, current);
            int anchoredPos = Math.round(point.x
                    - (point.x - oldPos) * ratio);
            b.requestedZoom = next;
            BBPainterAccess.setSiz(painter, next);
            BCUFields.setInt(b.stage, "pos", anchoredPos);
            return true;
        } catch (Throwable t) {
            Logger.err("CustomMap: unbounded wheel zoom failed", t);
            return false;
        }
    }

    public static void applyZoomOverride(Object painter) {
        Binding b = active;
        if (b == null || painter == null || !finitePositive(b.requestedZoom))
            return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            BBPainterAccess.setSiz(painter, b.requestedZoom);
        } catch (Throwable t) {
            Logger.err("CustomMap: zoom override failed", t);
        }
    }

    static float zoomAfterWheel(float current, int rotation, float maximum) {
        if (!finitePositive(current)) current = 1f;
        double factor = Math.pow(0.9d, rotation);
        double candidate = current * factor;
        if (Double.isNaN(candidate) || Double.isInfinite(candidate))
            candidate = rotation > 0 ? MIN_NUMERIC_ZOOM
                    : finitePositive(maximum) ? maximum : current;
        float next = (float) Math.max(MIN_NUMERIC_ZOOM, candidate);
        if (rotation < 0 && finitePositive(maximum))
            next = Math.min(next, maximum);
        return next;
    }

    public static void drawUnder(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (b != null && b.normalBattle && painter != null
                && BBPainterAccess.getStageBasis(painter) == b.stage) {

            drawChunkLayer(painter, graphics, "over");
            drawPatrolPlatforms(painter, graphics, "over");
            return;
        }
        drawUnderNow(painter, graphics);
    }

    public static void drawUnderBeforeCastle(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (b == null || !b.normalBattle || painter == null
                || BBPainterAccess.getStageBasis(painter) != b.stage) return;
        drawUnderNow(painter, graphics);
    }

    private static void drawUnderNow(Object painter, FakeGraphics graphics) {
        drawBackground(painter, graphics);

        drawAnimatedWaterUnderlay(painter, graphics);

        drawTrees(painter, graphics);
        drawProps(painter, graphics);
        drawChunkLayer(painter, graphics, "under");
        drawPatrolPlatforms(painter, graphics, "under");
        drawBreakableIce(painter, graphics);
    }
    public static void drawOver(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (b != null && b.normalBattle && painter != null
                && BBPainterAccess.getStageBasis(painter) == b.stage) return;
        drawChunkLayer(painter, graphics, "over");
        drawPatrolPlatforms(painter, graphics, "over");
    }

    private static void drawAnimatedWaterUnderlay(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (!validWaterPainter(b, painter, graphics)) return;
        try {
            FakeImage frame = currentWaterSurfaceFrame(b);
            if (!validImage(frame)) return;
            int viewportWidth = BBPainterAccess.getWidth(painter);
            float renderedTile = renderedTile(painter, b);
            Set<Long> drawnShoreCells = new HashSet<Long>();
            for (int x = 0; x < b.variant.width; x++) {
                float left = waterCellLeft(painter, b, x);
                if (left + renderedTile < -2f || left > viewportWidth + 2f) continue;
                for (int row = 0; row < b.variant.height; row++) {
                    if (!isExposedWater(b.variant, x, row)) continue;
                    float top = waterCellTop(painter, b, row);
                    graphics.drawImage(frame, left, top, renderedTile, renderedTile);
                    drawAnimatedShoreCell(painter, graphics, b, frame, x - 1, row,
                            renderedTile, viewportWidth, drawnShoreCells);
                    drawAnimatedShoreCell(painter, graphics, b, frame, x + 1, row,
                            renderedTile, viewportWidth, drawnShoreCells);
                }
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: animated water underlay failed", t);
        } finally {
            try { graphics.setComposite(FakeGraphics.DEF, 0, 0); }
            catch (Throwable ignored) {}
        }
    }

    private static void drawAnimatedShoreCell(
            Object painter, FakeGraphics graphics, Binding b, FakeImage frame,
            int shoreX, int row, float renderedTile, int viewportWidth,
            Set<Long> drawn) {
        if (shoreX < 0 || shoreX >= b.variant.width
                || b.variant.cell(shoreX, row) != CustomMapDocument.CELL_GROUND
                || isPatrolOriginCell(b.variant, shoreX, row)) return;
        long key = ((long) row << 32) | (shoreX & 0xffffffffL);
        if (!drawn.add(key)) return;
        float left = waterCellLeft(painter, b, shoreX);
        if (left + renderedTile < -2f || left > viewportWidth + 2f) return;
        graphics.drawImage(frame, left, waterCellTop(painter, b, row),
                renderedTile, renderedTile);
    }

    public static void drawWaterForeground(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (!validWaterPainter(b, painter, graphics)) return;
        try {
            FakeImage frame = currentWaterSurfaceFrame(b);
            FakeImage fill = image("custom_maps/" + b.doc.uuid
                    + "/assets/water_fill/000.png");
            if (!validImage(frame)) return;
            if (!validImage(fill)) fill = frame;
            int viewportWidth = BBPainterAccess.getWidth(painter);
            float renderedTile = renderedTile(painter, b);

            List<PlatformBodyFootprint> platformBodies =
                    platformBodyFootprints(b.variant, b.platformTick);
            graphics.setComposite(FakeGraphics.TRANS, liquidForegroundAlpha(b), 0);
            for (int x = 0; x < b.variant.width; x++) {
                float left = waterCellLeft(painter, b, x);
                if (left + renderedTile < -2f || left > viewportWidth + 2f) continue;
                for (int row = 0; row < b.variant.height; row++) {
                    if (b.variant.cell(x, row) != CustomMapDocument.CELL_WATER) continue;
                    if (platformBodyOverlapsCell(
                            platformBodies, x, row, b.variant.height)) continue;
                    FakeImage tile = isExposedWater(b.variant, x, row) ? frame : fill;
                    graphics.drawImage(tile, left, waterCellTop(painter, b, row),
                            renderedTile, renderedTile);
                }
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: animated water foreground failed", t);
        } finally {
            try { graphics.setComposite(FakeGraphics.DEF, 0, 0); }
            catch (Throwable ignored) {}
        }
    }

    private static final class PlatformBodyFootprint {
        final float leftTileX;
        final float rightTileX;
        final float bottomTileY;
        final float topTileY;

        PlatformBodyFootprint(float leftTileX, float rightTileX,
                              float bottomTileY, float topTileY) {
            this.leftTileX = leftTileX;
            this.rightTileX = rightTileX;
            this.bottomTileY = bottomTileY;
            this.topTileY = topTileY;
        }
    }

    private static List<PlatformBodyFootprint> platformBodyFootprints(
            CustomMapDocument.ModeVariant variant, long gameplayTick) {
        if (variant == null || variant.secondaryPlatforms == null
                || variant.secondaryPlatforms.isEmpty()) return Collections.emptyList();
        List<PlatformBodyFootprint> out = new ArrayList<PlatformBodyFootprint>(
                variant.secondaryPlatforms.size());
        for (CustomMapDocument.SecondaryPlatform platform
                : variant.secondaryPlatforms) {
            if (platform == null || platform.widthTiles() <= 0) continue;
            MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                    variant, platform, platformEvaluationTick(
                    variant, platform, gameplayTick));
            if (pose == null) continue;
            out.add(new PlatformBodyFootprint(
                    pose.collisionLeftTileX(platform),
                    pose.collisionRightTileX(platform),
                    platform.collisionBodyBottomTileY(pose.supportTileY),
                    pose.collisionSupportTileY(platform)));
        }
        return out;
    }

    private static boolean platformBodyOverlapsCell(
            List<PlatformBodyFootprint> bodies, int cellX, int row,
            int gridHeight) {
        if (bodies == null || bodies.isEmpty()) return false;
        float cellLeft = cellX;
        float cellRight = cellX + 1f;
        float cellTop = gridHeight - row;
        float cellBottom = cellTop - 1f;
        for (PlatformBodyFootprint body : bodies)
            if (body != null
                    && body.leftTileX < cellRight
                    && body.rightTileX > cellLeft
                    && body.bottomTileY < cellTop
                    && body.topTileY > cellBottom) return true;
        return false;
    }

    private static boolean validWaterPainter(
            Binding b, Object painter, FakeGraphics graphics) {
        return b != null && painter != null && graphics != null
                && b.variant != null && b.variant.cells != null
                && BBPainterAccess.getStageBasis(painter) == b.stage;
    }

    private static boolean isExposedWater(
            CustomMapDocument.ModeVariant variant, int x, int row) {
        return variant.cell(x, row) == CustomMapDocument.CELL_WATER
                && variant.cell(x, row - 1) != CustomMapDocument.CELL_WATER;
    }

    private static float renderedTile(Object painter, Binding b) {
        return b.variant.worldUnitsPerTile() * .32f * BBPainterAccess.getSiz(painter);
    }

    static float renderedTilePixels(Object painter) {
        Binding b = active;
        return b == null || painter == null ? 0f : renderedTile(painter, b);
    }

    private static int liquidForegroundAlpha(Binding b) {
        if (b == null || b.doc == null || b.doc.themeProfile == null
                || b.doc.themeProfile.liquid == null) return 128;
        return Math.max(0, Math.min(255,
                b.doc.themeProfile.liquid.foregroundAlpha));
    }

    private static float waterCellLeft(Object painter, Binding b, int x) {
        return CrazyRender.screenX(painter, x * b.variant.worldUnitsPerTile());
    }

    private static float waterCellTop(Object painter, Binding b, int row) {
        float layer = -(b.variant.height - row) * b.variant.layerUnitsPerTile();
        return projectY(painter, layer);
    }

    private static boolean validImage(FakeImage image) {
        try {
            return image != null && image.isValid()
                    && image.getWidth() > 0 && image.getHeight() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static FakeImage currentWaterSurfaceFrame(Binding b) {
        int frameCount = waterSurfaceFrameCount(b);
        if (frameCount <= 0) return null;
        long tick = b.normalBattle
                ? CustomMapBattleRuntime.animationTick()
                : System.currentTimeMillis() / 33L;
        int frameIndex = (int) Math.floorMod(tick / 3L, (long) frameCount);
        return image("custom_maps/" + b.doc.uuid
                + "/assets/water_surface/" + String.format("%03d.png", frameIndex));
    }

    private static int waterSurfaceFrameCount(Binding b) {
        if (b.waterSurfaceFrameCount >= 0) return b.waterSurfaceFrameCount;
        int count = 0;
        while (count < 512 && fileExists("custom_maps/" + b.doc.uuid
                + "/assets/water_surface/" + String.format("%03d.png", count))) count++;
        b.waterSurfaceFrameCount = count;
        return count;
    }

    private static void drawTrees(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (b == null || b.variant.trees == null || b.variant.trees.isEmpty()
                || painter == null || graphics == null) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            int count = treeAssetCount(b);
            if (count <= 0) return;
            String groundPath = "custom_maps/" + b.doc.uuid + "/assets/ground/000.png";
            FakeImage groundTile = image(groundPath);
            int sourceTile = groundTile == null ? 16
                    : Math.max(1, Math.min(groundTile.getWidth(), groundTile.getHeight()));
            float siz = BBPainterAccess.getSiz(painter);
            float renderedTile = b.variant.worldUnitsPerTile() * .32f * siz;
            int viewportWidth = BBPainterAccess.getWidth(painter);
            int viewportHeight = BBPainterAccess.getHeight(painter);
            for (CustomMapDocument.TreePlacement placement : b.variant.trees) {
                if (treeOnPatrolPlatform(b.variant, placement)) continue;
                int assetIndex = Math.floorMod(placement.asset, count);
                String relative = "custom_maps/" + b.doc.uuid + "/assets/tree/"
                        + String.format("%03d.png", assetIndex);
                FakeImage tree = image(relative);
                if (tree == null || tree.getWidth() <= 0 || tree.getHeight() <= 0) continue;

                float sourceScale = CustomMapPreviewPanel.treeAssetScale(sourceTile,
                        tree.getWidth(), tree.getHeight(), placement.scalePercent);
                float screenScale = sourceScale * renderedTile / sourceTile;
                float drawW = tree.getWidth() * screenScale;
                float drawH = tree.getHeight() * screenScale;
                float centerX = CrazyRender.screenX(painter,
                        b.variant.worldX(placement.x)
                                + placement.xOffsetPercent / 100f
                                * b.variant.worldUnitsPerTile());
                float supportLayer = -(b.variant.height - placement.y)
                        * b.variant.layerUnitsPerTile();

                float rootY = projectY(painter, supportLayer) + renderedTile
                        * CustomMapPreviewPanel.treeRootContactRatio(b.variant);
                int opaqueBottom = alphaContentBottom(relative, tree);
                float drawX = centerX - drawW * .5f;
                float drawY = rootY - opaqueBottom * screenScale;
                if (drawX + drawW < -32f || drawX > viewportWidth + 32f
                        || drawY + drawH < -32f || drawY > viewportHeight + 32f) continue;
                graphics.drawImage(tree, drawX, drawY, drawW, drawH);
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: direct tree render failed", t);
        }
    }

    private static int treeAssetCount(Binding b) {
        if (b.treeAssetCount >= 0) return b.treeAssetCount;
        int count = 0;
        while (count < 256 && fileExists("custom_maps/" + b.doc.uuid
                + "/assets/tree/" + String.format("%03d.png", count))) count++;
        b.treeAssetCount = count;
        return count;
    }

    private static void drawProps(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (b == null || b.variant == null || b.variant.props == null
                || b.variant.props.isEmpty() || b.doc == null
                || b.doc.propManifest == null || painter == null || graphics == null) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            String groundPath = "custom_maps/" + b.doc.uuid + "/assets/ground/000.png";
            FakeImage groundTile = image(groundPath);
            int sourceTile = groundTile == null ? 16
                    : Math.max(1, Math.min(groundTile.getWidth(), groundTile.getHeight()));
            float renderedTile = b.variant.worldUnitsPerTile() * .32f
                    * BBPainterAccess.getSiz(painter);
            int viewportWidth = BBPainterAccess.getWidth(painter);
            int viewportHeight = BBPainterAccess.getHeight(painter);
            for (CustomMapDocument.PropPlacement placement : b.variant.props) {
                if (placement == null || placement.assetId == null) continue;
                CustomMapDocument.PropAssetRef ref = b.propAssetsById.get(placement.assetId);
                if (ref == null || !ref.decorative || !"NONE".equals(ref.collision)
                        || !"NONE".equals(ref.interaction)
                        || !"BEHIND_ACTORS".equals(placement.layer)
                        || ref.asset == null || ref.asset.isEmpty()) continue;
                String relative = "custom_maps/" + b.doc.uuid + "/" + ref.asset;
                FakeImage prop = image(relative);
                if (!validImage(prop)) continue;
                float sourceScale = Math.min(
                        Math.max(.25f, ref.maxWidthTiles) * sourceTile / prop.getWidth(),
                        Math.max(.25f, ref.maxHeightTiles) * sourceTile / prop.getHeight());
                float variation = placement.scalePercent <= 0
                        ? 1f : placement.scalePercent / 100f;
                float screenScale = sourceScale * variation * renderedTile / sourceTile;
                float drawW = prop.getWidth() * screenScale;
                float drawH = prop.getHeight() * screenScale;
                float centerX = CrazyRender.screenX(painter,
                        b.variant.worldX(placement.x)
                                + placement.xOffsetPercent / 100f
                                * b.variant.worldUnitsPerTile());
                float supportLayer = -(b.variant.height - placement.y)
                        * b.variant.layerUnitsPerTile();
                float rootY = projectY(painter, supportLayer) + renderedTile
                        * CustomMapPreviewPanel.treeRootContactRatio(b.variant);
                int opaqueBottom = alphaContentBottom(relative, prop);
                float drawX = centerX - drawW * .5f;
                float drawY = rootY - opaqueBottom * screenScale;
                if (drawX + drawW < -32f || drawX > viewportWidth + 32f
                        || drawY + drawH < -32f || drawY > viewportHeight + 32f) continue;
                graphics.drawImage(prop, drawX, drawY, drawW, drawH);
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: decorative prop render failed", t);
        }
    }

    private static int alphaContentBottom(String key, FakeImage image) {
        synchronized (ALPHA_BOTTOM_CACHE) {
            Integer cached = ALPHA_BOTTOM_CACHE.get(key);
            if (cached != null) return cached;
        }
        int bottom = image.getHeight();
        outer:
        for (int y = image.getHeight() - 1; y >= 0; y--) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) >= 16) {
                    bottom = y + 1;
                    break outer;
                }
            }
        }
        synchronized (ALPHA_BOTTOM_CACHE) { ALPHA_BOTTOM_CACHE.put(key, bottom); }
        return bottom;
    }

    private static void drawChunkLayer(Object painter, FakeGraphics graphics, String layer) {
        Binding b = active;
        if (b == null || painter == null || graphics == null) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            int chunk = CustomMapDocument.CHUNK_TILES;
            int chunksX = (b.variant.width + chunk - 1) / chunk;
            int chunksY = (b.variant.height + chunk - 1) / chunk;
            float siz = BBPainterAccess.getSiz(painter);
            float renderedTile = b.variant.worldUnitsPerTile() * 0.32f * siz;
            float width = BBPainterAccess.getWidth(painter);
            float height = BBPainterAccess.getHeight(painter);
            float ground = CrazyRender.groundY(painter, 0)
                    + (usesNativeVerticalProjection(b)
                    ? 0f : b.cameraPixels);
            for (int cx = 0; cx < chunksX; cx++) {
                float x = CrazyRender.screenX(painter, cx * chunk * b.variant.worldUnitsPerTile());
                float drawW = chunk * renderedTile;
                if (x + drawW < -64f || x > width + 64f) continue;
                for (int cy = 0; cy < chunksY; cy++) {
                    float y = ground - (b.variant.height - cy * chunk) * renderedTile;
                    float drawH = chunk * renderedTile;
                    if (y + drawH < -64f || y > height + 64f) continue;
                    String relative = "custom_maps/" + b.doc.uuid + "/" + b.assetId
                            + "/chunks/" + layer + "/" + cx + "_" + cy + ".png";
                    FakeImage image = image(relative);
                    if (image == null) continue;
                    image = maskedIceChunk(b, relative, image, cx, cy);

                    graphics.drawImage(image, x, y, drawW, drawH);
                }
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: chunk render failed", t);
        }
    }

    private static void drawPatrolPlatforms(Object painter, FakeGraphics graphics,
                                            String layer) {
        Binding b = active;
        if (b == null || painter == null || graphics == null
                || b.variant == null || b.variant.secondaryPlatforms == null) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            float renderedTile = renderedTile(painter, b);
            int viewportWidth = BBPainterAccess.getWidth(painter);
            int viewportHeight = BBPainterAccess.getHeight(painter);
            for (CustomMapDocument.SecondaryPlatform platform
                    : b.variant.secondaryPlatforms) {
                if (platform == null || !platform.isPatrolling()) continue;
                String platformRoot = b.platformAssetRoots.get(platform.id);
                if (platformRoot == null) continue;
                String relative = platformRoot + layer + ".png";
                FakeImage sprite = image(relative);
                if (!validImage(sprite)) continue;
                sprite = maskedIcePlatform(b, relative, sprite, platform);
                MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                        b.variant, platform, platformEvaluationTick(
                        b.variant, platform, b.platformTick));
                float width = platform.widthTiles() * renderedTile;
                float height = renderedTile;
                float leftWorld = (pose.centerTileX - platform.widthTiles() * .5f)
                        * b.variant.worldUnitsPerTile();
                float x = CrazyRender.screenX(painter, leftWorld);
                float y = projectY(painter, pose.supportLayer(b.variant));
                if (x + width < -64f || x > viewportWidth + 64f
                        || y + height < -64f || y > viewportHeight + 64f) continue;
                graphics.drawImage(sprite, x, y, width, height);
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: moving platform render failed", t);
        }
    }

    private static void drawBreakableIce(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (b == null || painter == null || graphics == null
                || b.iceVisualStates.isEmpty() || b.doc == null
                || b.doc.iceSurfaceManifest == null
                || !b.doc.iceSurfaceManifest.isReady()) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            float tile = renderedTile(painter, b);
            for (BreakableIceEngine.VisualState state
                    : b.iceVisualStates.values()) {
                IcePlacement placement = icePlacement(painter, b, state.key());
                if (placement == null) continue;
                if (state.phase() == BreakableIceEngine.Phase.REBUILDING) {
                    FakeImage original = originalIceCell(b, state.key());
                    if (validImage(original)) {
                        int alpha = Math.max(0, Math.min(255,
                                Math.round(state.progress() * 255f)));
                        graphics.setComposite(FakeGraphics.TRANS, alpha, 0);
                        graphics.drawImage(original, placement.left,
                                placement.top, tile, tile);
                        graphics.setComposite(FakeGraphics.DEF, 0, 0);
                    }
                } else if (state.crackLevel() > 0
                        && state.phase() != BreakableIceEngine.Phase.BROKEN) {
                    FakeImage crack = iceSurfaceImage(
                            b, placement.topologyKey, state.crackLevel());
                    if (validImage(crack))
                        graphics.drawImage(crack, placement.left,
                                placement.top, tile, tile);
                }
                if (state.phase() == BreakableIceEngine.Phase.BROKEN)
                    drawIceBreakFrame(painter, graphics, b, placement, state, tile);
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: breakable ice render failed", t);
            try { graphics.setComposite(FakeGraphics.DEF, 0, 0); }
            catch (Throwable ignored) {}
        }
    }

    private static void drawIceBreakFrame(
            Object painter, FakeGraphics graphics, Binding b, IcePlacement placement,
            BreakableIceEngine.VisualState state, float tile) {
        CustomMapDocument.IceSurfaceManifest manifest = b.doc.iceSurfaceManifest;
        int frameTicks = Math.max(1, manifest.breakFrameTicks);
        float framePosition = iceBreakFramePosition(state.progressTicks(),
                frameTicks,
                CustomMapBattleRuntime.vfxRenderSubFrameFraction(painter));
        int frameIndex = (int) Math.floor(framePosition);
        if (frameIndex < 0 || frameIndex >= manifest.breakFrames.size()) return;
        FakeImage frame = iceBreakFrame(b, frameIndex);
        if (!validImage(frame)) return;
        float scale = 1.35f;
        float width = tile * scale;
        float height = tile * scale;
        float sourceWidth = Math.max(1f, manifest.sourceTilePixels);
        float sourceHeight = Math.max(1f, manifest.sourceTilePixels);
        float x = placement.centerX
                - width * manifest.breakPivotX / sourceWidth;
        float y = placement.supportY
                - height * manifest.breakPivotY / sourceHeight;
        float blend = Math.max(0f, Math.min(1f,
                framePosition - frameIndex));
        FakeImage next = frameIndex + 1 < manifest.breakFrames.size()
                ? iceBreakFrame(b, frameIndex + 1) : null;
        if (validImage(next) && blend > 0f) {
            int nextAlpha = Math.max(0, Math.min(255,
                    Math.round(blend * 255f)));
            graphics.setComposite(FakeGraphics.TRANS, 255 - nextAlpha, 0);
            graphics.drawImage(frame, x, y, width, height);
            graphics.setComposite(FakeGraphics.TRANS, nextAlpha, 0);
            graphics.drawImage(next, x, y, width, height);
            graphics.setComposite(FakeGraphics.DEF, 0, 0);
        } else graphics.drawImage(frame, x, y, width, height);
    }

    static float iceBreakFramePosition(int progressTicks, int frameTicks,
                                       float subFrameFraction) {
        int duration = Math.max(1, frameTicks);
        float fraction = Math.max(0f, Math.min(.999999f, subFrameFraction));
        return (Math.max(0, progressTicks) + fraction) / duration;
    }

    private static FakeImage iceSurfaceImage(Binding b, String topologyKey,
                                             int crackLevel) {
        if (b == null || topologyKey == null || topologyKey.isEmpty()) return null;
        synchronized (b.iceRenderLock) {
            FakeImage[] images = b.iceSurfaceImages.get(topologyKey);
            if (images == null) {
                images = new FakeImage[4];
                b.iceSurfaceImages.put(topologyKey, images);
            }
            int index = Math.max(0, Math.min(images.length - 1, crackLevel));
            if (!validImage(images[index])) {
                CustomMapDocument.IceSurfaceAssetRef ref =
                        b.doc.iceSurfaceManifest.find(topologyKey);
                String relative = ref == null ? null
                        : ref.imageForCrackLevel(index);
                if (relative != null && !relative.contains("../"))
                    images[index] = image("custom_maps/" + b.doc.uuid + "/"
                            + relative.replace('\\', '/'));
            }
            return images[index];
        }
    }

    private static FakeImage iceBreakFrame(Binding b, int index) {
        if (b == null || b.doc == null || b.doc.iceSurfaceManifest == null
                || index < 0
                || index >= b.doc.iceSurfaceManifest.breakFrames.size()) return null;
        synchronized (b.iceRenderLock) {
            while (b.iceBreakFrames.size()
                    < b.doc.iceSurfaceManifest.breakFrames.size())
                b.iceBreakFrames.add(null);
            FakeImage frame = b.iceBreakFrames.get(index);
            if (!validImage(frame)) {
                String relative = b.doc.iceSurfaceManifest.breakFrames.get(index);
                if (relative != null && !relative.contains("../")) {
                    frame = image("custom_maps/" + b.doc.uuid + "/"
                            + relative.replace('\\', '/'));
                    b.iceBreakFrames.set(index, frame);
                }
            }
            return frame;
        }
    }

    private static IcePlacement icePlacement(
            Object painter, Binding b, BreakableIceEngine.SupportKey key) {
        if (b == null || key == null || b.variant == null) return null;
        int worldTileX;
        int row;
        float leftWorld;
        float supportLayer;
        String topology;
        if (key.kind() == BreakableIceEngine.SupportKind.MAIN) {
            worldTileX = key.tileX();
            if (worldTileX < 0 || worldTileX >= b.variant.width
                    || b.variant.surface == null
                    || worldTileX >= b.variant.surface.length) return null;
            row = b.variant.surface[worldTileX];
            if (row < 0) return null;
            leftWorld = worldTileX * b.variant.worldUnitsPerTile();
            supportLayer = b.variant.surfaceLayerAt(
                    b.variant.worldX(worldTileX));
            topology = b.variant.iceSurfaceKeys != null
                    && worldTileX < b.variant.iceSurfaceKeys.length
                    ? b.variant.iceSurfaceKeys[worldTileX] : null;
        } else {
            CustomMapDocument.SecondaryPlatform platform =
                    b.variant.secondaryPlatform(key.platformId());
            if (platform == null || key.tileX() < 0
                    || key.tileX() >= platform.widthTiles()) return null;
            MovingPlatformEngine.Pose pose = platformPose(platform.id);
            if (pose == null) return null;
            row = platformRow(b.variant, platform);
            worldTileX = platform.startX + key.tileX();
            leftWorld = (pose.leftTileX(platform) + key.tileX())
                    * b.variant.worldUnitsPerTile();
            supportLayer = pose.supportLayer(b.variant);
            topology = platform.iceSurfaceKeys != null
                    && key.tileX() < platform.iceSurfaceKeys.length
                    ? platform.iceSurfaceKeys[key.tileX()] : null;
        }
        float left = CrazyRender.screenX(painter, leftWorld);
        float top = key.kind() == BreakableIceEngine.SupportKind.MAIN
                ? waterCellTop(painter, b, row)
                : projectY(painter, supportLayer);
        float center = left + renderedTile(painter, b) * .5f;
        return new IcePlacement(left, top, center,
                projectY(painter, supportLayer), topology);
    }

    private static FakeImage originalIceCell(
            Binding b, BreakableIceEngine.SupportKey key) {
        String cacheKey = key.toString();
        synchronized (b.iceRenderLock) {
            FakeImage cached = b.iceOriginalCells.get(cacheKey);
            if (cached != null) return cached;
            FakeImage source;
            int localX;
            int localY;
            int cellsWide;
            int cellsHigh;
            if (key.kind() == BreakableIceEngine.SupportKind.MAIN) {
                int x = key.tileX();
                if (b.variant.surface == null || x < 0
                        || x >= b.variant.surface.length) return null;
                int row = b.variant.surface[x];
                int chunk = CustomMapDocument.CHUNK_TILES;
                String relative = "custom_maps/" + b.doc.uuid + "/" + b.assetId
                        + "/chunks/under/" + (x / chunk) + "_"
                        + (row / chunk) + ".png";
                source = image(relative);
                localX = x % chunk;
                localY = row % chunk;
                cellsWide = cellsHigh = chunk;
            } else {
                CustomMapDocument.SecondaryPlatform platform =
                        b.variant.secondaryPlatform(key.platformId());
                if (platform == null) return null;
                if (!platform.isPatrolling()) {
                    int x = platform.startX + key.tileX();
                    int row = platformRow(b.variant, platform);
                    int chunk = CustomMapDocument.CHUNK_TILES;
                    String relative = "custom_maps/" + b.doc.uuid + "/" + b.assetId
                            + "/chunks/under/" + (x / chunk) + "_"
                            + (row / chunk) + ".png";
                    source = image(relative);
                    localX = x % chunk;
                    localY = row % chunk;
                    cellsWide = cellsHigh = chunk;
                } else {
                    String root = b.platformAssetRoots.get(platform.id);
                    source = root == null ? null : image(root + "under.png");
                    localX = key.tileX();
                    localY = 0;
                    cellsWide = Math.max(1, platform.widthTiles());
                    cellsHigh = 1;
                }
            }
            if (!validImage(source)) return null;
            int left = localX * source.getWidth() / cellsWide;
            int right = (localX + 1) * source.getWidth() / cellsWide;
            int top = localY * source.getHeight() / cellsHigh;
            int bottom = (localY + 1) * source.getHeight() / cellsHigh;
            FakeImage cell = source.getSubimage(left, top,
                    Math.max(1, right - left), Math.max(1, bottom - top));
            b.iceOriginalCells.put(cacheKey, cell);
            return cell;
        }
    }

    private static final class IcePlacement {
        final float left, top, centerX, supportY;
        final String topologyKey;

        IcePlacement(float left, float top, float centerX, float supportY,
                     String topologyKey) {
            this.left = left;
            this.top = top;
            this.centerX = centerX;
            this.supportY = supportY;
            this.topologyKey = topologyKey;
        }
    }

    private static FakeImage maskedIceChunk(
            Binding b, String relative, FakeImage source, int chunkX, int chunkY) {
        if (b == null || source == null || b.iceMissingSupports.isEmpty()) return source;
        synchronized (b.iceRenderLock) {
            refreshIceMaskCacheLocked(b);
            FakeImage cached = b.iceMaskedImages.get(relative);
            if (cached != null) return cached;
            FakeImage copy = source.cloneImage();
            if (!validImage(copy)) return source;
            int chunk = CustomMapDocument.CHUNK_TILES;
            for (BreakableIceEngine.SupportKey key : b.iceMissingSupports) {
                int worldX;
                int row;
                if (key.kind() == BreakableIceEngine.SupportKind.MAIN) {
                    worldX = key.tileX();
                    if (b.variant.surface == null || worldX < 0
                            || worldX >= b.variant.surface.length) continue;
                    row = b.variant.surface[worldX];
                    if (row < 0 || worldX / chunk != chunkX) continue;
                    int firstRow = Math.max(row, chunkY * chunk);
                    int lastRow = Math.min(b.variant.height,
                            (chunkY + 1) * chunk);
                    for (int worldRow = firstRow;
                         worldRow < lastRow; worldRow++)
                        clearImageCell(copy, worldX - chunkX * chunk,
                                worldRow - chunkY * chunk, chunk, chunk);
                    continue;
                } else {
                    CustomMapDocument.SecondaryPlatform platform =
                            b.variant.secondaryPlatform(key.platformId());
                    if (platform == null || platform.isPatrolling()) continue;
                    worldX = platform.startX + key.tileX();
                    row = platformRow(b.variant, platform);
                }
                if (row < 0 || worldX / chunk != chunkX || row / chunk != chunkY)
                    continue;
                clearImageCell(copy, worldX - chunkX * chunk,
                        row - chunkY * chunk, chunk, chunk);
            }
            b.iceMaskedImages.put(relative, copy);
            return copy;
        }
    }

    private static FakeImage maskedIcePlatform(
            Binding b, String relative, FakeImage source,
            CustomMapDocument.SecondaryPlatform platform) {
        if (b == null || source == null || platform == null
                || b.iceMissingSupports.isEmpty()) return source;
        boolean affected = false;
        for (BreakableIceEngine.SupportKey key : b.iceMissingSupports)
            if (key.kind() == BreakableIceEngine.SupportKind.FLOATING
                    && platform.id.equals(key.platformId())) {
                affected = true;
                break;
            }
        if (!affected) return source;
        synchronized (b.iceRenderLock) {
            refreshIceMaskCacheLocked(b);
            FakeImage cached = b.iceMaskedImages.get(relative);
            if (cached != null) return cached;
            FakeImage copy = source.cloneImage();
            if (!validImage(copy)) return source;
            for (BreakableIceEngine.SupportKey key : b.iceMissingSupports)
                if (key.kind() == BreakableIceEngine.SupportKind.FLOATING
                        && platform.id.equals(key.platformId()))
                    clearImageCell(copy, key.tileX(), 0,
                            Math.max(1, platform.widthTiles()), 1);
            b.iceMaskedImages.put(relative, copy);
            return copy;
        }
    }

    private static void clearImageCell(FakeImage image, int cellX, int cellY,
                                       int cellsWide, int cellsHigh) {
        if (!validImage(image) || cellX < 0 || cellY < 0
                || cellX >= cellsWide || cellY >= cellsHigh) return;
        int left = cellX * image.getWidth() / cellsWide;
        int right = (cellX + 1) * image.getWidth() / cellsWide;
        int top = cellY * image.getHeight() / cellsHigh;
        int bottom = (cellY + 1) * image.getHeight() / cellsHigh;
        for (int y = top; y < bottom; y++)
            for (int x = left; x < right; x++) image.setRGB(x, y, 0);
    }

    private static void refreshIceMaskCacheLocked(Binding b) {
        if (b.appliedIceMaskRevision == b.iceMaskRevision) return;
        for (FakeImage image : b.iceMaskedImages.values())
            if (image != null) try { image.unload(); } catch (Throwable ignored) {}
        b.iceMaskedImages.clear();
        b.appliedIceMaskRevision = b.iceMaskRevision;
    }

    private static FakeImage image(String path) {
        synchronized (IMAGE_CACHE) {
            if (IMAGE_CACHE.containsKey(path)) return IMAGE_CACHE.get(path);
            if (MISSING_IMAGES.contains(path)) return null;
        }
        InputStream in = null;
        try {
            in = CustomMapRepository.stream(path);
            if (in == null) {
                synchronized (IMAGE_CACHE) { MISSING_IMAGES.add(path); }
                return null;
            }
            if (ImageBuilder.builder == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            byte[] block = new byte[32 * 1024];
            int read;
            while ((read = in.read(block)) >= 0) {
                if (read > 0) bytes.write(block, 0, read);
            }

            FakeImage out = ImageBuilder.builder.build(bytes.toByteArray());
            if (!validImage(out)) {
                if (out != null) try { out.unload(); } catch (Throwable ignored) {}
                synchronized (IMAGE_CACHE) { MISSING_IMAGES.add(path); }
                return null;
            }
            synchronized (IMAGE_CACHE) { IMAGE_CACHE.put(path, out); }
            return out;
        } catch (Throwable ignored) {
            synchronized (IMAGE_CACHE) { MISSING_IMAGES.add(path); }
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    static FakeImage loadEmbeddedImageForTesting(String path) {
        return image(path);
    }

    static int cachedImageCountForTesting() {
        synchronized (IMAGE_CACHE) { return IMAGE_CACHE.size(); }
    }

    static int missingImageCountForTesting() {
        synchronized (IMAGE_CACHE) { return MISSING_IMAGES.size(); }
    }

    static void activateAssetScopeForTesting(String uuid) {
        CustomMapDocument document = new CustomMapDocument();
        document.uuid = uuid;
        synchronized (LOCK) {
            if (active != null)
                throw new IllegalStateException("A runtime binding is already active");
            active = new Binding(document);
        }
    }

    static int deferredImageCountForTesting() {
        synchronized (IMAGE_CACHE) {
            int count = 0;
            for (List<FakeImage> images : DEFERRED_IMAGE_UNLOADS.values())
                count += images == null ? 0 : images.size();
            return count;
        }
    }

    static void clearAssetCachesForTesting() {
        ArrayList<FakeImage> images;
        synchronized (IMAGE_CACHE) {
            images = new ArrayList<FakeImage>(IMAGE_CACHE.values());
            for (List<FakeImage> deferred : DEFERRED_IMAGE_UNLOADS.values())
                if (deferred != null) images.addAll(deferred);
            IMAGE_CACHE.clear();
            MISSING_IMAGES.clear();
            DEFERRED_IMAGE_UNLOADS.clear();
        }
        synchronized (ALPHA_BOTTOM_CACHE) { ALPHA_BOTTOM_CACHE.clear(); }
        Set<FakeImage> unique = Collections.newSetFromMap(
                new IdentityHashMap<FakeImage, Boolean>());
        unique.addAll(images);
        for (FakeImage image : unique)
            if (image != null) try { image.unload(); } catch (Throwable ignored) {}
    }

    static List<FakeImage> themeVfxImages(String kind) {
        Binding b = active;
        if (b == null || kind == null) return Collections.emptyList();
        List<FakeImage> images = b.vfxImages.get(kind.toLowerCase(java.util.Locale.ROOT));
        return images == null ? Collections.<FakeImage>emptyList() : images;
    }

    static long activeThemeSeed() {
        Binding b = active;
        if (b == null || b.doc == null) return 1L;
        if (b.doc.spec != null) return b.doc.spec.seed;
        return b.doc.backgroundManifest == null ? 1L : b.doc.backgroundManifest.seed;
    }

    static int eventVfxCap() {
        Binding b = active;
        return eventVfxCap(b == null ? null : b.doc.themeProfile);
    }

    static int eventVfxCap(CustomMapDocument.ThemeProfile profile) {
        CustomMapDocument.ThemeVfxProfile vfx = profile == null ? null : profile.vfx;
        return vfx == null || vfx.profileId == null || vfx.profileId.isEmpty()
                ? 96 : Math.max(0, Math.min(96, vfx.eventCap));
    }

    static int ambientVfxCap() {
        Binding b = active;
        return ambientVfxCap(b == null ? null : b.doc.themeProfile);
    }

    static int ambientVfxCap(CustomMapDocument.ThemeProfile profile) {
        CustomMapDocument.ThemeVfxProfile vfx = profile == null ? null : profile.vfx;
        return vfx == null || vfx.profileId == null || vfx.profileId.isEmpty()
                ? 0 : Math.max(0, Math.min(96 - eventVfxCap(profile), vfx.ambientCap));
    }

    private static float waterLayer(CustomMapDocument.ModeVariant variant, int x) {
        for (int row = 0; row < variant.height; row++)
            if (variant.cell(x, row) == CustomMapDocument.CELL_WATER)
                return -(variant.height - row) * variant.layerUnitsPerTile();
        return Float.NaN;
    }

    private static void updateVisibleUnitFocus(Object painter, Binding b) {
        float minLayer = Float.POSITIVE_INFINITY;
        float maxLayer = Float.NEGATIVE_INFINITY;
        int count = 0;
        int width = BBPainterAccess.getWidth(painter);
        for (Entity entity : b.stage.le) {
            if (entity == null || entity.isBase() || entity.dead
                    || entity.health <= 0L) continue;
            float screenX = CrazyRender.screenX(painter, entity.pos);

            if (screenX < -width * .12f || screenX > width * 1.12f) continue;
            float layer = entity.currentLayer;
            if (!finite(layer)) continue;
            minLayer = Math.min(minLayer, layer);
            maxLayer = Math.max(maxLayer, layer);
            count++;
        }
        if (count == 0) {
            float siz = Math.max(MIN_NUMERIC_ZOOM,
                    BBPainterAccess.getSiz(painter));
            float worldX = ((width * .5f - BBPainterAccess.getStagePos(painter))
                    / siz - 200f) / .32f;
            float terrainLayer = b.variant.surfaceLayerAt(worldX);
            if (finite(terrainLayer)) {
                minLayer = terrainLayer;
                maxLayer = terrainLayer;
                count = 1;
            }
        }
        if (count > 0) {
            b.focusMinLayer = minLayer;
            b.focusMaxLayer = maxLayer;
            b.focusLayer = (minLayer + maxLayer) * .5f;
        }
    }

    static void initializeBattleCamera(Object painter, Binding b) {
        if (painter == null || b == null || b.variant == null
                || b.cameraInitialized) return;
        float height = BBPainterAccess.getHeight(painter);
        if (!(height > 1f)) return;
        float layer = b.variant.spawn == null ? Float.NaN
                : b.variant.surfaceLayerAt(b.variant.worldX(b.variant.spawn.x));
        if (!finite(layer)) layer = b.focusLayer;
        if (!finite(layer)) return;
        float terrainProjection = b.variant.height
                * b.variant.layerUnitsPerTile() * 4f
                * Math.max(MIN_NUMERIC_ZOOM, BBPainterAccess.getSiz(painter));
        b.cameraPixels = desiredCameraOffset(
                rawGroundY(painter, layer), height, terrainProjection);
        b.cameraInitialized = true;
    }

    private static void initializeManualCamera(Object painter, Binding b) {
        if (painter == null || b == null || b.variant == null) return;
        if (b.cameraInitialized) return;
        int viewportWidth = BBPainterAccess.getWidth(painter);
        float siz = Math.max(MIN_NUMERIC_ZOOM, BBPainterAccess.getSiz(painter));
        float centerWorldX = ((viewportWidth * .5f
                - BBPainterAccess.getStagePos(painter)) / siz - 200f) / .32f;
        float layer = nearestDryMainLayer(b.variant, centerWorldX);
        if (!finite(layer)) return;
        float targetY = BBPainterAccess.getHeight(painter) * .52f;
        b.focusLayer = layer;
        b.focusMinLayer = layer;
        b.focusMaxLayer = layer;
        b.cameraPixels = targetY - rawGroundY(painter, layer);
        b.cameraInitialized = true;
    }

    private static float clampCameraToComposition(Object painter, Binding b,
                                                  float offset) {
        float height = Math.max(1f, BBPainterAccess.getHeight(painter));
        float siz = Math.max(MIN_NUMERIC_ZOOM, BBPainterAccess.getSiz(painter));
        float terrainSpan = b.variant.height
                * b.variant.layerUnitsPerTile() * 4f * siz;
        float backgroundSpan = BackgroundLayoutEngine.verticalContentSpan(
                b.doc.backgroundManifest, BBPainterAccess.getWidth(painter),
                BBPainterAccess.getHeight(painter));
        float limit = Math.max(height, terrainSpan + backgroundSpan);
        return Math.max(-limit, Math.min(limit, offset));
    }

    static float nearestDryMainLayer(CustomMapDocument.ModeVariant variant,
                                     float worldX) {
        if (variant == null || variant.width <= 0 || variant.surface == null)
            return Float.NaN;
        int center = clamp((int) Math.floor(worldX
                / Math.max(1f, variant.worldUnitsPerTile())), 0, variant.width - 1);
        for (int distance = 0; distance < variant.width; distance++) {
            int left = center - distance;
            if (dryMainColumn(variant, left)) {
                float sampleX = distance == 0 && variant.containsWorldX(worldX)
                        ? worldX : (left + .5f) * variant.worldUnitsPerTile();
                float layer = variant.surfaceLayerAt(sampleX);
                if (finite(layer)) return layer;
                return variant.walkLayerAtTile(left);
            }
            int right = center + distance;
            if (right != left && dryMainColumn(variant, right)) {
                float layer = variant.surfaceLayerAt(
                        (right + .5f) * variant.worldUnitsPerTile());
                if (finite(layer)) return layer;
                return variant.walkLayerAtTile(right);
            }
        }
        return Float.NaN;
    }

    private static boolean dryMainColumn(CustomMapDocument.ModeVariant variant, int x) {
        return x >= 0 && x < variant.width && variant.surface[x] >= 0
                && (variant.water == null || !variant.water[x]);
    }

    private static void updateCamera(Object painter, Binding b) {
        if (b == null || !finite(b.focusLayer)) return;
        float minLayer = finite(b.focusMinLayer)
                ? b.focusMinLayer : b.focusLayer;
        float maxLayer = finite(b.focusMaxLayer)
                ? b.focusMaxLayer : b.focusLayer;
        float rawA = rawGroundY(painter, minLayer);
        float rawB = rawGroundY(painter, maxLayer);
        float rawTop = Math.min(rawA, rawB);
        float rawBottom = Math.max(rawA, rawB);
        float height = BBPainterAccess.getHeight(painter);
        float siz = BBPainterAccess.getSiz(painter);
        float terrainProjection = b.variant.height
                * b.variant.layerUnitsPerTile() * 4f * siz;
        float wanted = desiredCameraOffsetForRange(
                rawTop, rawBottom, height, terrainProjection);
        if (!b.cameraInitialized) {

            b.cameraPixels = wanted;
            b.cameraInitialized = true;
        } else {
            b.cameraPixels += (wanted - b.cameraPixels) * 0.20f;

            b.cameraPixels = keepRangeVisible(
                    b.cameraPixels, rawTop, rawBottom, height);
        }
    }

    static float desiredCameraOffset(float rawGround, float viewportHeight,
                                     float terrainProjectionHeight) {
        return desiredCameraOffsetForRange(rawGround, rawGround,
                viewportHeight, terrainProjectionHeight);
    }

    static float desiredCameraOffsetForRange(
            float rawTop, float rawBottom, float viewportHeight,
            float terrainProjectionHeight) {
        if (rawTop > rawBottom) {
            float swap = rawTop;
            rawTop = rawBottom;
            rawBottom = swap;
        }
        float safeTop = viewportHeight * .18f;
        float safeBottom = viewportHeight * .72f;
        float centerTarget = viewportHeight * .52f;
        float wanted = centerTarget - (rawTop + rawBottom) * .5f;
        float span = rawBottom - rawTop;
        if (span <= safeBottom - safeTop) {
            wanted = Math.max(wanted, safeTop - rawTop);
            wanted = Math.min(wanted, safeBottom - rawBottom);
        }

        float limit = Math.max(viewportHeight * 0.75f,
                Math.max(0f, terrainProjectionHeight) + viewportHeight * 0.25f);
        return Math.max(-limit, Math.min(limit, wanted));
    }

    private static float keepRangeVisible(
            float offset, float rawTop, float rawBottom, float viewportHeight) {
        float safeTop = viewportHeight * .12f;
        float safeBottom = viewportHeight * .80f;
        if (rawBottom - rawTop > safeBottom - safeTop)
            return viewportHeight * .46f - (rawTop + rawBottom) * .5f;
        if (rawTop + offset < safeTop)
            offset += safeTop - (rawTop + offset);
        if (rawBottom + offset > safeBottom)
            offset -= rawBottom + offset - safeBottom;
        return offset;
    }

    private static boolean usesNativeVerticalProjection(Binding b) {
        return b != null && (b.normalBattle
                || b.mode == CustomMapDocument.MapMode.ADVENTURE);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean finitePositive(float value) {
        return finite(value) && value > 0f;
    }

    private static float rawGroundY(Object painter, float layer) {
        float siz = BBPainterAccess.getSiz(painter);
        return BBPainterAccess.getMidh(painter) - (156f - layer * 4f) * siz;
    }

    private static void drawBackground(Object painter, FakeGraphics graphics) {
        Binding b = active;
        if (b == null || painter == null || graphics == null) return;
        try {
            if (BBPainterAccess.getStageBasis(painter) != b.stage) return;
            int width = BBPainterAccess.getWidth(painter);
            int height = BBPainterAccess.getHeight(painter);
            if (b.doc.backgroundManifest == null) return;

            int sky = b.doc.backgroundManifest.skyTopArgb;
            graphics.setComposite(FakeGraphics.DEF, 0, 0);
            graphics.setColor((sky >>> 16) & 0xff, (sky >>> 8) & 0xff, sky & 0xff);
            graphics.fillRect(0, 0, width + 2, height + 2);
            int stagePos = BBPainterAccess.getStagePos(painter);
            List<BackgroundLayoutEngine.DrawCommand> commands = BackgroundLayoutEngine.layout(
                    b.doc.backgroundManifest, width, height, stagePos,
                    b.cameraPixels);
            for (BackgroundLayoutEngine.DrawCommand command : commands) {
                if (command.asset == null || command.asset.asset == null
                        || command.asset.asset.isEmpty()) continue;
                FakeImage bg = image("custom_maps/" + b.doc.uuid + "/" + command.asset.asset);
                if (bg == null || bg.getWidth() <= 0 || bg.getHeight() <= 0) continue;
                boolean translucent = command.alpha < 255;
                try {
                    if (translucent)
                        graphics.setComposite(FakeGraphics.TRANS, command.alpha, 0);
                    graphics.drawImage(bg, command.x, command.y, command.width, command.height);
                } finally {
                    if (translucent) graphics.setComposite(FakeGraphics.DEF, 0, 0);
                }
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: background render failed", t);
        }
    }

    private static void validateEmbeddedAssets(CustomMapDocument doc,
                                               CustomMapDocument.ModeVariant variant,
                                               String variantId,
                                               String title) throws Exception {
        String patrolIssue = MovingPlatformValidator.firstBlockingMessage(variant);
        if (patrolIssue != null && !patrolIssue.isEmpty())
            throw new IllegalArgumentException(patrolIssue);
        requireFile("custom_maps/" + doc.uuid + "/assets/ground/000.png", "embedded ground tile");
        if (doc.spec.waterDensity > 0.0) {
            requireFile("custom_maps/" + doc.uuid + "/assets/water/000.png", "embedded water tile");
            requireFile("custom_maps/" + doc.uuid + "/assets/water_surface/000.png",
                    "embedded water-surface animation frame");
            requireFile("custom_maps/" + doc.uuid + "/assets/water_fill/000.png",
                    "embedded water-body tile");
        }
        if (doc.spec.treeDensity > 0.0)
            requireFile("custom_maps/" + doc.uuid + "/assets/tree/000.png", "embedded tree tile");
        Map<String, CustomMapDocument.PropAssetRef> propRefs =
                new HashMap<String, CustomMapDocument.PropAssetRef>();
        if (doc.propManifest != null && doc.propManifest.assets != null)
            for (CustomMapDocument.PropAssetRef ref : doc.propManifest.assets) {
                if (ref == null || ref.id == null || ref.id.isEmpty()
                        || propRefs.put(ref.id, ref) != null)
                    throw new IllegalArgumentException("A decorative prop id is missing or duplicated.");
                String asset = ref.asset == null ? "" : ref.asset.replace('\\', '/');
                if (!isSafePropAssetPath(asset))
                    throw new IllegalArgumentException("A decorative prop asset reference is unsafe.");
            }
        if (variant.props != null) for (CustomMapDocument.PropPlacement placement : variant.props) {
            if (placement == null || placement.assetId == null
                    || !propRefs.containsKey(placement.assetId))
                throw new IllegalArgumentException("A decorative prop placement has no manifest asset.");
            CustomMapDocument.PropAssetRef ref = propRefs.get(placement.assetId);
            if (!ref.decorative || !"NONE".equals(ref.collision)
                    || !"NONE".equals(ref.interaction))
                throw new IllegalArgumentException("Interactive prop behavior is not enabled in this revision.");
        }
        requireFile("custom_maps/" + doc.uuid + "/assets/background/000.png", "embedded background");
        if (doc.backgroundManifest == null || doc.backgroundManifest.assets == null
                || doc.backgroundManifest.bands == null || doc.backgroundManifest.bands.isEmpty())
            throw new IllegalArgumentException("The background composition manifest is missing.");
        for (CustomMapDocument.BackgroundAssetRef asset : doc.backgroundManifest.assets) {
            if (asset == null || asset.asset == null || asset.asset.isEmpty())
                throw new IllegalArgumentException("A background asset reference is incomplete.");
            requireFile("custom_maps/" + doc.uuid + "/" + asset.asset, "embedded background asset");
        }
        int chunksX = (variant.width + CustomMapDocument.CHUNK_TILES - 1) / CustomMapDocument.CHUNK_TILES;
        int chunksY = (variant.height + CustomMapDocument.CHUNK_TILES - 1) / CustomMapDocument.CHUNK_TILES;
        boolean terrain = false;
        for (int cy = 0; cy < chunksY && !terrain; cy++) for (int cx = 0; cx < chunksX; cx++) {
            if (fileExists("custom_maps/" + doc.uuid + "/" + variantId
                    + "/chunks/under/" + cx + "_" + cy + ".png")) { terrain = true; break; }
        }
        if (!terrain) throw new IllegalArgumentException("The saved " + title + " terrain chunks are missing.");
        Set<String> platformIds = new HashSet<String>();
        Set<String> platformAssetIds = new HashSet<String>();
        if (variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform : variant.secondaryPlatforms) {
                if (platform == null || !platform.isPatrolling()) continue;
                if (platform.id == null || platform.id.trim().isEmpty()
                        || !platformIds.add(platform.id))
                    throw new IllegalArgumentException("A moving platform id is missing or duplicated.");
                if (!platformAssetIds.add(CustomMapChunkWriter.safePlatformId(platform, variant)))
                    throw new IllegalArgumentException("Moving platform asset paths are duplicated.");
                String root = "custom_maps/" + doc.uuid + "/" + variantId
                        + "/platforms/" + CustomMapChunkWriter.safePlatformId(
                        platform, variant) + "/";
                requireFile(root + "under.png", "moving-platform terrain sprite");
                requireFile(root + "over.png", "moving-platform foreground sprite");
                if (variant.trees != null)
                    for (CustomMapDocument.TreePlacement tree : variant.trees)
                        if (tree != null && tree.x >= platform.startX
                                && tree.x <= platform.endX
                                && tree.y == platformRow(variant, platform))
                            throw new IllegalArgumentException("Moving platform "
                                    + platform.id + " is not empty.");
            }
        validateEmbeddedIceAssets(doc, variant);
    }

    private static void validateEmbeddedIceAssets(
            CustomMapDocument doc, CustomMapDocument.ModeVariant variant)
            throws Exception {
        CustomMapDocument.IceSurfaceManifest manifest =
                doc == null ? null : doc.iceSurfaceManifest;
        boolean declared = manifest != null
                && ((manifest.tiles != null && !manifest.tiles.isEmpty())
                || (manifest.breakFrames != null
                && !manifest.breakFrames.isEmpty()));
        if (!declared) return;
        if (!manifest.isReady())
            throw new IllegalArgumentException(
                    "The embedded breakable-ice asset manifest is incomplete.");
        Set<String> used = new HashSet<String>();
        if (variant.iceSurfaceKeys != null)
            for (String key : variant.iceSurfaceKeys)
                if (key != null && !key.isEmpty()) used.add(key);
        if (variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform
                    : variant.secondaryPlatforms)
                if (platform != null && platform.iceSurfaceKeys != null)
                    for (String key : platform.iceSurfaceKeys)
                        if (key != null && !key.isEmpty()) used.add(key);
        for (String key : used) {
            CustomMapDocument.IceSurfaceAssetRef ref = manifest.find(key);
            if (ref == null || !ref.isComplete())
                throw new IllegalArgumentException(
                        "The embedded ice tile '" + key + "' is incomplete.");
            requireEmbeddedIceFile(doc, ref.base, "ice base overlay");
            requireEmbeddedIceFile(doc, ref.crack1, "ice crack-01 overlay");
            requireEmbeddedIceFile(doc, ref.crack2, "ice crack-02 overlay");
            requireEmbeddedIceFile(doc, ref.crack3, "ice crack-03 overlay");
        }
        for (String frame : manifest.breakFrames)
            requireEmbeddedIceFile(doc, frame, "ice-break VFX frame");
    }

    private static void requireEmbeddedIceFile(
            CustomMapDocument doc, String relative, String label)
            throws Exception {
        String safe = relative == null ? ""
                : relative.replace('\\', '/');
        if ((!safe.startsWith("assets/ice_surface/")
                && !safe.startsWith("assets/vfx/ice-break/"))
                || safe.contains("../"))
            throw new IllegalArgumentException(
                    "An embedded " + label + " path is unsafe.");
        requireFile("custom_maps/" + doc.uuid + "/" + safe,
                "embedded " + label);
    }

    private static void validateTerrainRevision(CustomMapDocument doc) {
        if (doc == null || !CustomMapDocument.isSupportedTerrainRevision(doc.terrainRevision))
            throw new IllegalArgumentException("Unsupported custom terrain revision "
                    + (doc == null ? "missing" : doc.terrainRevision) + ".");
        if (doc.hasEnabledPlatformPatrols()
                && doc.terrainRevision != CustomMapDocument.PATROL_TERRAIN_REVISION)
            throw new IllegalArgumentException("This map contains moving islands but is not saved "
                    + "as terrain revision " + CustomMapDocument.PATROL_TERRAIN_REVISION + ".");
    }

    private static boolean isPatrolOriginCell(CustomMapDocument.ModeVariant variant,
                                               int x, int row) {
        if (variant == null || variant.secondaryPlatforms == null) return false;
        for (CustomMapDocument.SecondaryPlatform platform : variant.secondaryPlatforms)
            if (platform != null && platform.isPatrolling()
                    && x >= platform.startX && x <= platform.endX
                    && row == platformRow(variant, platform)) return true;
        return false;
    }

    private static boolean treeOnPatrolPlatform(CustomMapDocument.ModeVariant variant,
                                                CustomMapDocument.TreePlacement tree) {
        if (variant == null || tree == null || variant.secondaryPlatforms == null) return false;
        for (CustomMapDocument.SecondaryPlatform platform : variant.secondaryPlatforms)
            if (platform != null && platform.isPatrolling()
                    && tree.x >= platform.startX && tree.x <= platform.endX
                    && tree.y == platformRow(variant, platform)) return true;
        return false;
    }

    private static int platformRow(CustomMapDocument.ModeVariant variant,
                                   CustomMapDocument.SecondaryPlatform platform) {
        return Math.max(0, Math.min(variant.height - 1, Math.round(
                variant.height + platform.supportLayer
                        / Math.max(1f, variant.layerUnitsPerTile()))));
    }

    private static void preloadAssets(Binding b) {
        if (b == null || b.variant == null || b.doc == null) return;
        try {
            String map = "custom_maps/" + b.doc.uuid + "/";
            image(map + "assets/ground/000.png");
            if (b.doc.backgroundManifest != null
                    && b.doc.backgroundManifest.assets != null)
                for (CustomMapDocument.BackgroundAssetRef asset
                        : b.doc.backgroundManifest.assets)
                    if (asset != null && asset.asset != null && !asset.asset.isEmpty())
                        image(map + asset.asset);

            if (b.variant.secondaryPlatforms != null)
                for (CustomMapDocument.SecondaryPlatform platform
                        : b.variant.secondaryPlatforms) {
                    if (platform == null || !platform.isPatrolling()) continue;
                    String platformRoot = b.platformAssetRoots.get(platform.id);
                    if (platformRoot == null) continue;
                    image(platformRoot + "under.png");
                    image(platformRoot + "over.png");
                }
            for (int i = 0; i < 256; i++) {
                String path = map + "assets/tree/" + String.format("%03d.png", i);
                if (!fileExists(path)) break;
                image(path);
            }
            if (b.variant.props != null) {
                Set<String> loadedProps = new HashSet<String>();
                for (CustomMapDocument.PropPlacement placement : b.variant.props) {
                    if (placement == null || !loadedProps.add(placement.assetId)) continue;
                    CustomMapDocument.PropAssetRef ref = b.propAssetsById.get(placement.assetId);
                    if (ref != null && ref.asset != null && !ref.asset.isEmpty())
                        image(map + ref.asset);
                }
            }
            for (int i = 0; i < 512; i++) {
                String path = map + "assets/water_surface/" + String.format("%03d.png", i);
                if (!fileExists(path)) break;
                image(path);
            }
            image(map + "assets/water_fill/000.png");
            CustomMapDocument.ThemeVfxProfile vfx = b.doc.themeProfile == null
                    ? null : b.doc.themeProfile.vfx;
            if (vfx != null && vfx.assets != null) {
                String[] kinds = {"dust", "splash", "land", "edge", "ambient"};
                for (String kind : kinds) {
                    List<String> paths = vfx.assets.get(kind);
                    if (paths == null || paths.isEmpty()) continue;
                    ArrayList<FakeImage> loaded = new ArrayList<FakeImage>();
                    for (String relative : paths) {
                        if (relative == null) continue;
                        String safe = relative.replace('\\', '/');
                        if (!safe.startsWith("assets/vfx/") || safe.contains("../")) continue;
                        FakeImage sprite = image(map + safe);
                        if (validImage(sprite)) loaded.add(sprite);
                    }
                    if (!loaded.isEmpty())
                        b.vfxImages.put(kind, Collections.unmodifiableList(loaded));
                }
            }
            preloadIceAssets(b, map);
        } catch (Throwable t) {
            Logger.err("CustomMap: asset preload failed", t);
        }
    }

    private static void preloadIceAssets(Binding b, String mapRoot) {
        if (!breakableIceEnabled(b) || mapRoot == null) return;
        Set<String> topologyKeys = new HashSet<String>();
        if (b.variant.iceSurfaceKeys != null)
            for (String key : b.variant.iceSurfaceKeys)
                if (key != null && !key.isEmpty()) topologyKeys.add(key);
        if (b.variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform
                    : b.variant.secondaryPlatforms)
                if (platform != null && platform.iceSurfaceKeys != null)
                    for (String key : platform.iceSurfaceKeys)
                        if (key != null && !key.isEmpty()) topologyKeys.add(key);
        for (String key : topologyKeys) {
            CustomMapDocument.IceSurfaceAssetRef ref =
                    b.doc.iceSurfaceManifest.find(key);
            if (ref == null || !ref.isComplete()) continue;
            FakeImage[] states = new FakeImage[4];
            for (int level = 0; level < states.length; level++) {
                String relative = ref.imageForCrackLevel(level);
                states[level] = relative == null || relative.contains("../")
                        ? null : image(mapRoot + relative.replace('\\', '/'));
            }
            b.iceSurfaceImages.put(key, states);
        }
        b.iceBreakFrames.clear();
        for (String relative : b.doc.iceSurfaceManifest.breakFrames) {
            FakeImage frame = relative == null || relative.contains("../")
                    ? null : image(mapRoot + relative.replace('\\', '/'));
            b.iceBreakFrames.add(frame);
        }
    }

    private static void requireFile(String path, String label) throws Exception {
        if (!fileExists(path)) throw new IllegalArgumentException("Missing " + label + ": " + path);
    }

    static boolean isSafePropAssetPath(String path) {
        if (path == null) return false;
        String safe = path.replace('\\', '/');
        return safe.startsWith("assets/props/") && !safe.contains("../")
                && safe.matches("assets/props/[0-9]{3}\\.png");
    }

    private static boolean fileExists(String path) {
        InputStream in = null;
        try {
            in = CustomMapRepository.stream(path);
            return in != null;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static float checkpointWorldX(Binding b) {
        if (b.variant.checkpoints != null && !b.variant.checkpoints.isEmpty()) {
            int i = clamp(b.lastCheckpoint, 0, b.variant.checkpoints.size() - 1);
            return b.variant.worldX(b.variant.checkpoints.get(i).x);
        }
        return b.variant.worldX(b.variant.spawn.x);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class Pending {
        final CustomMapDocument doc;
        final CustomMapDocument.MapMode mode;
        Pending(CustomMapDocument doc, CustomMapDocument.MapMode mode) { this.doc = doc; this.mode = mode; }
    }

    private static final class Binding {
        final StageBasis stage;
        final CustomMapDocument doc;
        final CustomMapDocument.MapMode mode;
        final CustomMapDocument.ModeVariant variant;
        final String assetId;
        final boolean normalBattle;
        volatile int lastCheckpoint;
        volatile float focusLayer = Float.NaN;
        volatile float focusMinLayer = Float.NaN;
        volatile float focusMaxLayer = Float.NaN;
        volatile float cameraPixels;
        volatile boolean cameraInitialized;
        volatile boolean verticalCameraDragging;
        volatile int lastVerticalCameraDragY;
        volatile float requestedZoom = Float.NaN;
        volatile int treeAssetCount = -1;
        volatile int waterSurfaceFrameCount = -1;
        volatile long platformTick;
        final BreakableIceEngine breakableIce = new BreakableIceEngine();
        final TerrainHeightfield.SupportAvailability supportAvailability;
        volatile Map<BreakableIceEngine.SupportKey,
                BreakableIceEngine.VisualState> iceVisualStates =
                Collections.emptyMap();
        volatile Set<BreakableIceEngine.SupportKey> iceMissingSupports =
                Collections.emptySet();
        volatile long iceMaskRevision;
        final Object iceRenderLock = new Object();
        final Map<String, FakeImage> iceMaskedImages =
                new HashMap<String, FakeImage>();
        final Map<String, FakeImage> iceOriginalCells =
                new HashMap<String, FakeImage>();
        long appliedIceMaskRevision = Long.MIN_VALUE;
        final Map<String, FakeImage[]> iceSurfaceImages =
                new HashMap<String, FakeImage[]>();
        final List<FakeImage> iceBreakFrames = new ArrayList<FakeImage>();
        final Map<String, String> platformAssetRoots = new HashMap<String, String>();
        final Map<String, PlatformRuntimeState> platformStates =
                new HashMap<String, PlatformRuntimeState>();
        final Map<Entity, Integer> liquidExposure =
                new WeakHashMap<Entity, Integer>();
        final Map<String, List<FakeImage>> vfxImages =
                new HashMap<String, List<FakeImage>>();
        final Map<String, CustomMapDocument.PropAssetRef> propAssetsById =
                new HashMap<String, CustomMapDocument.PropAssetRef>();

        Binding(StageBasis stage, CustomMapDocument doc, CustomMapDocument.MapMode mode) {
            this(stage, doc, doc.variant(mode), mode, mode.id, false);
        }

        Binding(StageBasis stage, CustomMapDocument doc,
                CustomMapDocument.ModeVariant variant, CustomMapDocument.MapMode mode,
                String assetId, boolean normalBattle) {
            this.stage = stage;
            this.doc = doc;
            this.mode = mode;
            this.variant = variant;
            this.assetId = assetId;
            this.normalBattle = normalBattle;
            this.supportAvailability = iceAvailability(this);
            if (doc != null && doc.propManifest != null
                    && doc.propManifest.assets != null)
                for (CustomMapDocument.PropAssetRef ref : doc.propManifest.assets)
                    if (ref != null && ref.id != null && !ref.id.isEmpty())
                        propAssetsById.put(ref.id, ref);
            if (variant != null && variant.secondaryPlatforms != null)
                for (CustomMapDocument.SecondaryPlatform platform
                        : variant.secondaryPlatforms)
                    if (platform != null && platform.isPatrolling()) {
                        platformAssetRoots.put(platform.id,
                                "custom_maps/" + doc.uuid + "/" + assetId
                                        + "/platforms/"
                                        + CustomMapChunkWriter.safePlatformId(
                                        platform, variant) + "/");
                        platformStates.put(platform.id,
                                new PlatformRuntimeState(platform));
                    }
            if (variant != null && variant.spawn != null) {
                float initial = variant.surfaceLayerAt(
                        variant.worldX(variant.spawn.x));
                if (finite(initial)) {
                    focusLayer = initial;
                    focusMinLayer = initial;
                    focusMaxLayer = initial;
                }
                }
            preloadAssets(this);
        }

        Binding(CustomMapDocument doc) {
            this.stage = null;
            this.doc = doc;
            this.mode = null;
            this.variant = null;
            this.assetId = "test";
            this.normalBattle = false;
            this.supportAvailability = TerrainHeightfield.ALLOW_ALL;
            if (doc != null && doc.propManifest != null
                    && doc.propManifest.assets != null)
                for (CustomMapDocument.PropAssetRef ref : doc.propManifest.assets)
                    if (ref != null && ref.id != null && !ref.id.isEmpty())
                        propAssetsById.put(ref.id, ref);
        }
    }

    private static final class PlatformRuntimeState {
        final CustomMapDocument.SecondaryPlatform platform;
        final IdentityHashMap<Object, Long> boarders =
                new IdentityHashMap<Object, Long>();
        long localTick;
        boolean gateClosed;
        long stationHoldStartedTick = Long.MIN_VALUE;

        PlatformRuntimeState(CustomMapDocument.SecondaryPlatform platform) {
            this.platform = platform;
        }

        void expireBoarders(long globalTick) {
            Iterator<Map.Entry<Object, Long>> iterator =
                    boarders.entrySet().iterator();
            while (iterator.hasNext())
                if (iterator.next().getValue() < globalTick) iterator.remove();
        }

        void reset() {
            localTick = 0L;
            gateClosed = false;
            stationHoldStartedTick = Long.MIN_VALUE;
            boarders.clear();
        }
    }
}
