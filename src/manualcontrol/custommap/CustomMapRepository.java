package manualcontrol.custommap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import common.pack.Identifier;
import common.pack.PackData;
import common.pack.Source;
import common.pack.UserProfile;
import common.util.pack.Background;
import common.util.stage.Stage;
import common.util.stage.StageMap;
import common.util.unit.Enemy;
import common.util.lang.MultiLangData;
import manualcontrol.Logger;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class CustomMapRepository {

    public static final String PACK_ID = "custommap";
    public static final String PACK_NAME = "Custom Map";
    public static final String MARKER = "Crazy BCU Custom Map Studio schema 2";
    private static final String LEGACY_MARKER = "Crazy BCU Custom Map Studio schema 1";
    public static final String INDEX_PATH = "custom_maps/index.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CustomMapRepository() {}

    public static List<MapRecord> list() {
        try {
            PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
            if (pack != null) return liveRecords(pack, readIndex(pack));
            File index = new File(workspaceRoot(), INDEX_PATH.replace('/', File.separatorChar));
            if (index.isFile()) return readIndex(index).maps;
        } catch (Throwable t) {
            Logger.err("CustomMap: list failed", t);
        }
        return new ArrayList<MapRecord>();
    }

    private static List<MapRecord> liveRecords(PackData.UserPack pack, MapIndex index) {
        ArrayList<MapRecord> live = new ArrayList<MapRecord>();
        if (index == null || index.maps == null) return live;
        for (MapRecord record : index.maps) {
            if (isLive(pack, index, record)) live.add(record);
            else if (record != null) Logger.log("CustomMap: hiding deleted map '"
                    + record.name + "' (" + record.uuid + ")");
        }
        return live;
    }

    private static boolean isLive(PackData.UserPack pack, MapIndex index, MapRecord record) {
        if (pack == null || record == null || record.uuid == null) return false;
        if (!fileExists(pack, "custom_maps/" + record.uuid + "/map.json")) return false;
        Stage stage = rawStage(pack, record);
        return stage != null && ownsStage(index, record, stage);
    }

    private static Stage rawStage(PackData.UserPack pack, MapRecord record) {
        if (record.stageId < 0) return null;
        StageMap sm = pack.mc.maps.getRaw(record.stageMapId);
        return sm == null ? null : sm.list.getRaw(record.stageId);
    }

    private static boolean ownsStage(MapIndex index, MapRecord record, Stage stage) {
        int background = customBackgroundId(stage.bg);
        if (background >= 0) return record.backgroundId == background;
        if (index == null || index.maps == null) return true;
        MapRecord best = null;
        for (MapRecord other : index.maps) {
            if (other == null || other.stageMapId != record.stageMapId
                    || other.stageId != record.stageId) continue;
            if (best == null || other.updatedAt > best.updatedAt) best = other;
        }
        return best == null || best == record;
    }

    private static int customBackgroundId(Identifier<Background> background) {
        return background == null || !PACK_ID.equals(background.pack) ? -1 : background.id;
    }

    private static boolean fileExists(PackData.UserPack pack, String path) {
        InputStream in = null;
        try {
            in = pack.source.streamFile(path);
            return in != null;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    public static CustomMapDocument load(String uuid) throws IOException {
        if (uuid == null || uuid.trim().isEmpty()) return null;
        PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
        if (pack != null) {
            if (!fileExists(pack, "custom_maps/" + uuid + "/map.json")) return null;
            return readDocument(pack, uuid);
        }
        File file = new File(workspaceRoot(), "custom_maps/" + uuid + "/map.json");
        if (!file.isFile()) return null;
        CustomMapDocument doc = readJson(file, CustomMapDocument.class);
        normalizeLoadedRevisions(doc);
        return doc;
    }

    public static CustomMapDocument load(PackData.UserPack pack, String uuid) throws IOException {
        return pack == null ? null : readDocument(pack, uuid);
    }

    public static synchronized MapRecord save(CustomMapDocument doc, TileCatalog.TileSet tiles) throws Exception {
        if (doc == null || doc.uuid == null) throw new IllegalArgumentException("Generate a map before saving.");
        CustomMapDocument embeddedOld = null;
        try { embeddedOld = load(doc.uuid); } catch (Throwable ignored) {}
        doc.markPatrolRevisionIfNeeded();
        if (!CustomMapDocument.isSupportedTerrainRevision(doc.terrainRevision))
            throw new IllegalArgumentException("This map uses legacy terrain revision "
                    + doc.terrainRevision + ". Regenerate it before Save or Export.");
        if (doc.hasEnabledPlatformPatrols()
                && doc.terrainRevision != CustomMapDocument.PATROL_TERRAIN_REVISION)
            throw new IllegalArgumentException("Moving islands require terrain revision "
                    + CustomMapDocument.PATROL_TERRAIN_REVISION + ".");
        if (doc.backgroundRevision != CustomMapDocument.BACKGROUND_REVISION
                || doc.backgroundManifest == null)
            throw new IllegalArgumentException("This map uses a legacy background composition. "
                    + "Regenerate it before Save or Export.");

        boolean sourceBiomeMatches = tiles != null && doc.spec != null
                && sameBiome(tiles.biome, doc.spec.biome);
        boolean sourceTilesUsable = sourceBiomeMatches
                && tiles.isUsable(doc.spec.waterDensity, doc.spec.treeDensity,
                doc.spec.propDensity);
        boolean embeddedPatrolSave = !sourceTilesUsable && embeddedOld != null
                && sameTerrainGeometry(embeddedOld, doc);
        if (!sourceTilesUsable && !embeddedPatrolSave) {
            if (!sourceBiomeMatches)
                throw new IllegalArgumentException("Source biome '"
                        + (doc.spec == null ? "" : doc.spec.biome)
                        + "' is unavailable. Select a theme and Regenerate before saving it.");
            throw new IllegalArgumentException(tiles.validationMessage(
                    doc.spec.waterDensity, doc.spec.treeDensity, doc.spec.propDensity));
        }
        if (embeddedPatrolSave && embeddedOld.spec != null) {

            String displayName = doc.name;
            doc.spec = embeddedOld.spec;
            doc.spec.name = displayName;
        }
        normalizeJsonTerrain(doc);
        doc.schemaVersion = CustomMapDocument.SCHEMA_VERSION;
        validateEnemyPool(doc);
        if (doc.battleTerrain == null)
            throw new IllegalArgumentException("Normal BCU stage terrain is missing. Regenerate the map.");
        CustomMapGenerator.validateBattle(doc.battleTerrain);
        if (!doc.battleTerrain.reachable)
            throw new IllegalArgumentException("BCU stage: " + doc.battleTerrain.validation);
        for (CustomMapDocument.ModeVariant variant : doc.variants.values()) {
            CustomMapGenerator.validate(variant, CustomMapDocument.MapMode.fromId(variant.mode));
            if (!variant.reachable) throw new IllegalArgumentException(variant.mode + ": " + variant.validation);
        }

        PackData.UserPack pack = getOrCreateWorkspacePack();
        List<String> dependencySnapshot = new ArrayList<String>(pack.desc.dependency);
        addEnemyDependencies(pack, doc);
        MapIndex index = readIndexIfPresent(pack);
        MapRecord record = find(index, doc.uuid);
        final boolean newRecord = record == null;
        if (record == null) {
            record = new MapRecord();
            record.uuid = doc.uuid;
            record.stageMapId = 0;
            index.maps.add(record);
        }

        CustomMapDocument old = embeddedOld;
        if (old == null)
            try { old = readDocument(pack, doc.uuid); } catch (Throwable ignored) {}
        if (old != null && old.createdAt > 0) doc.createdAt = old.createdAt;
        if (doc.createdAt <= 0) doc.createdAt = System.currentTimeMillis();
        doc.updatedAt = System.currentTimeMillis();
        doc.name = doc.spec.name == null ? doc.name : doc.spec.name.trim();
        record.name = doc.name;
        record.updatedAt = doc.updatedAt;

        StageMap stageMap = ensureStageMap(pack);
        Stage previousStage = record.stageId < 0 ? null : stageMap.list.getRaw(record.stageId);
        boolean previousStageOwned = previousStage != null
                && ownsStage(index, record, previousStage);
        StageSnapshot stageSnapshot = previousStageOwned
                ? new StageSnapshot(previousStage) : null;
        int previousBackgroundId = record.backgroundId;

        Stage stage = ensureStage(pack, index, record, doc);
        int qualifiedStageMap = stage.id == null ? -1
                : stageMapIdFromQualifiedPack(stage.id.pack);
        if (qualifiedStageMap >= 0) record.stageMapId = qualifiedStageMap;
        else {
            try {
                record.stageMapId = stage.id.getCont() instanceof StageMap
                        ? ((StageMap) stage.id.getCont()).id.id : 0;
            } catch (Throwable ignored) {
                record.stageMapId = 0;
            }
        }
        record.stageId = stage.id.id;

        if (record.backgroundId < 0) {
            int preferred = Math.max(0, record.stageId);
            while (pack.bgs.getRaw(preferred) != null) preferred++;
            record.backgroundId = preferred;
        }
        Background previousBackground = pack.bgs.getRaw(record.backgroundId);

        File root = workspaceRoot();
        File customRoot = new File(root, "custom_maps");
        if (!customRoot.exists() && !customRoot.mkdirs()) throw new IOException("Could not create " + customRoot);
        File temp = new File(customRoot, doc.uuid + ".tmp-" + System.nanoTime());
        if (!temp.mkdirs()) throw new IOException("Could not create temporary map folder.");
        File target = new File(customRoot, doc.uuid);
        File previous = new File(customRoot, doc.uuid + ".previous");
        File indexFile = new File(root, INDEX_PATH.replace('/', File.separatorChar));
        File indexBackup = new File(indexFile.getParentFile(), indexFile.getName() + ".previous");
        File backgroundFile = new File(new File(root, "backgrounds"),
                trio(record.backgroundId) + ".png");
        File backgroundBackup = new File(backgroundFile.getParentFile(),
                backgroundFile.getName() + ".previous");
        boolean swapped = false;
        boolean indexWritten = false;
        boolean committed = false;
        try {
            if (embeddedPatrolSave)
                CustomMapChunkWriter.writeFromEmbedded(temp, target, old, doc);
            else
                CustomMapChunkWriter.write(temp, doc, tiles);
            writeJson(new File(temp, "map.json"), doc);
            verifyStagedMap(temp, doc);
            deleteTree(previous);
            deleteTree(indexBackup);
            deleteTree(backgroundBackup);
            if (indexFile.isFile()) Files.copy(indexFile.toPath(), indexBackup.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            if (backgroundFile.isFile()) {
                File parent = backgroundBackup.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs())
                    throw new IOException("Could not create " + parent);
                Files.copy(backgroundFile.toPath(), backgroundBackup.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (target.exists()) Files.move(target.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            swapped = true;

            Background background = ensureBackground(pack, record, doc, target);
            stage.bg = background.id;

            Collections.sort(index.maps, new Comparator<MapRecord>() {
                @Override public int compare(MapRecord a, MapRecord b) {
                    String an = a == null || a.name == null ? "" : a.name;
                    String bn = b == null || b.name == null ? "" : b.name;
                    return an.compareToIgnoreCase(bn);
                }
            });
            writeJson(indexFile, index);
            indexWritten = true;
            Source.Workspace.saveWorkspace();
            committed = true;
        } catch (Throwable failure) {
            Throwable rollbackFailure = null;
            try {
                if (swapped || previous.exists()) {
                    deleteTree(target);
                    if (previous.exists()) Files.move(previous.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                if (backgroundBackup.isFile()) {
                    Files.move(backgroundBackup.toPath(), backgroundFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } else {

                    deleteTree(backgroundFile);
                }
                pack.bgs.set(record.backgroundId, previousBackground);
                pack.desc.dependency.clear();
                pack.desc.dependency.addAll(dependencySnapshot);
                if (stageSnapshot != null) stageSnapshot.restore(stage);
                else if (!previousStageOwned && stage != null && stage.id != null)
                    stageMap.list.set(stage.id.id, (Stage) null);
                record.backgroundId = previousBackgroundId;
                if (indexBackup.isFile()) {
                    Files.move(indexBackup.toPath(), indexFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } else if (indexWritten && newRecord) {
                    deleteTree(indexFile);
                }
                Source.Workspace.saveWorkspace();
            } catch (Throwable rollback) {
                rollbackFailure = rollback;
            }
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw new IOException("Custom Map save failed.", failure);
        } finally {
            deleteTreeQuietly(temp);
            if (committed) {
                deleteTreeQuietly(previous);
                deleteTreeQuietly(indexBackup);
                deleteTreeQuietly(backgroundBackup);
            }
        }
        CustomMapRuntime.invalidateAssets(doc.uuid);
        CustomMapThumbnail.invalidate(doc.uuid);
        Logger.log("CustomMap: saved '" + doc.name + "' uuid=" + doc.uuid
                + " stage=" + record.stageMapId + ":" + record.stageId);
        return record;
    }

    public static synchronized boolean deletePermanently(String uuid) throws Exception {
        if (uuid == null || uuid.trim().isEmpty())
            throw new IllegalArgumentException("Select a saved map to delete.");
        PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
        if (pack == null) throw new IOException("The Custom Map pack is not loaded.");
        if (!(pack.source instanceof Source.Workspace))
            throw new IOException("The installed Custom Map pack is read-only and cannot delete maps.");
        boolean marked = ownsMarker(pack.desc.desc)
                || new File(workspaceRoot(), INDEX_PATH.replace('/', File.separatorChar)).isFile();
        if (!marked) throw new IOException("Pack id '" + PACK_ID
                + "' is not owned by Custom Map Studio.");

        MapIndex index = readIndex(pack);
        MapRecord record = find(index, uuid.trim());
        if (record == null) return false;

        StageMap stageMap = pack.mc.maps.getRaw(record.stageMapId);
        Stage stage = rawStage(pack, record);
        boolean removeStage = stageMap != null && stage != null
                && ownsStage(index, record, stage);
        Background background = record.backgroundId < 0
                ? null : pack.bgs.getRaw(record.backgroundId);
        boolean removeBackground = record.backgroundId >= 0
                && !backgroundClaimedByAnotherLiveMap(pack, index, record);

        File customRoot = new File(workspaceRoot(), "custom_maps");
        File mapRoot = safeChild(customRoot, record.uuid);
        File backgroundFile = record.backgroundId < 0 ? null
                : new File(new File(workspaceRoot(), "backgrounds"),
                trio(record.backgroundId) + ".png");
        long token = System.nanoTime();
        File mapQuarantine = quarantine(mapRoot, token);
        File backgroundQuarantine = removeBackground
                ? quarantine(backgroundFile, token) : null;
        int indexPosition = index.maps.indexOf(record);
        boolean indexChanged = false;
        boolean stageChanged = false;
        boolean backgroundChanged = false;
        try {
            indexChanged = index.maps.remove(record);
            if (removeStage) {
                stageMap.list.set(record.stageId, (Stage) null);
                stageChanged = true;
            }
            if (removeBackground && background != null) {
                pack.bgs.set(record.backgroundId, (Background) null);
                backgroundChanged = true;
            }
            writeJson(new File(workspaceRoot(),
                    INDEX_PATH.replace('/', File.separatorChar)), index);
            Source.Workspace.saveWorkspace();
        } catch (Throwable failure) {
            Throwable rollbackFailure = null;
            try {
                if (backgroundChanged)
                    pack.bgs.set(record.backgroundId, background);
                if (stageChanged)
                    stageMap.list.set(record.stageId, stage);
                if (indexChanged) {
                    int position = Math.max(0, Math.min(indexPosition, index.maps.size()));
                    index.maps.add(position, record);
                }
                restoreQuarantine(mapQuarantine, mapRoot);
                restoreQuarantine(backgroundQuarantine, backgroundFile);
                writeJson(new File(workspaceRoot(),
                        INDEX_PATH.replace('/', File.separatorChar)), index);
                Source.Workspace.saveWorkspace();
            } catch (Throwable rollback) {
                rollbackFailure = rollback;
            }
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw new IOException("Permanent map deletion failed.", failure);
        }

        deleteTreeQuietly(mapQuarantine);
        deleteTreeQuietly(backgroundQuarantine);
        CustomMapRuntime.invalidateAssets(record.uuid);
        CustomMapThumbnail.invalidate(record.uuid);
        Logger.log("CustomMap: permanently deleted '" + record.name + "' uuid="
                + record.uuid + " stage=" + record.stageMapId + ":" + record.stageId
                + " background=" + record.backgroundId);
        return true;
    }

    private static boolean backgroundClaimedByAnotherLiveMap(PackData.UserPack pack,
                                                               MapIndex index,
                                                               MapRecord target) {
        if (index == null || index.maps == null || target == null) return false;
        for (MapRecord other : index.maps) {
            if (other == null || other == target
                    || other.backgroundId != target.backgroundId) continue;
            if (isLive(pack, index, other)) return true;
        }
        return false;
    }

    private static File safeChild(File parent, String child) throws IOException {
        if (parent == null || child == null || child.trim().isEmpty())
            throw new IOException("Invalid Custom Map storage path.");
        File resolved = new File(parent, child).getCanonicalFile();
        File root = parent.getCanonicalFile();
        String rootPrefix = root.getPath() + File.separator;
        if (!resolved.getPath().startsWith(rootPrefix))
            throw new IOException("Refusing to delete a path outside " + root);
        return resolved;
    }

    private static File quarantine(File source, long token) throws IOException {
        if (source == null || !source.exists()) return null;
        File target = new File(source.getParentFile(), source.getName()
                + ".deleting-" + Long.toUnsignedString(token));
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable ignored) {
            Files.move(source.toPath(), target.toPath());
        }
        return target;
    }

    private static void restoreQuarantine(File quarantine, File target) throws IOException {
        if (quarantine == null || target == null || !quarantine.exists()) return;
        Files.move(quarantine.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static synchronized File exportPack() throws Exception {
        PackData.UserPack pack = getOrCreateWorkspacePack();
        MapIndex index = readIndexIfPresent(pack);
        for (MapRecord record : index.maps) {
            CustomMapDocument doc = record == null ? null : readDocument(pack, record.uuid);
            if (doc == null || !CustomMapDocument.isSupportedTerrainRevision(doc.terrainRevision)
                    || doc.backgroundRevision != CustomMapDocument.BACKGROUND_REVISION
                    || doc.battleTerrain == null
                    || (doc.hasEnabledPlatformPatrols()
                    && doc.terrainRevision != CustomMapDocument.PATROL_TERRAIN_REVISION))
                throw new IOException("Export blocked: map '" + (record == null ? "unknown" : record.name)
                        + "' must be opened and Regenerated to current terrain/background revisions.");
            String patrolIssue = MovingPlatformValidator.firstBlockingMessage(
                    doc.battleTerrain);
            if ((patrolIssue == null || patrolIssue.isEmpty())
                    && doc.variants != null)
                for (CustomMapDocument.ModeVariant variant : doc.variants.values()) {
                    patrolIssue = MovingPlatformValidator.firstBlockingMessage(variant);
                    if (patrolIssue != null && !patrolIssue.isEmpty()) break;
                }
            if (patrolIssue != null && !patrolIssue.isEmpty())
                throw new IOException("Export blocked: map '" + record.name
                        + "' contains an invalid moving-island path: " + patrolIssue);

            verifyStagedMap(new File(workspaceRoot(), "custom_maps/" + record.uuid), doc);
            if (record.backgroundId < 0 || pack.bgs.getRaw(record.backgroundId) == null)
                throw new IOException("Export blocked: map '" + record.name
                        + "' has no registered Custom Stage Background.");
            File selector = new File(new File(workspaceRoot(), "backgrounds"),
                    trio(record.backgroundId) + ".png");
            if (!selector.isFile() || selector.length() <= 0L)
                throw new IOException("Export blocked: map '" + record.name
                        + "' is missing Background selector image "
                        + selector.getName() + ".");
        }
        if (!(pack.source instanceof Source.Workspace)) throw new IOException("Custom Map pack is not an editable workspace.");
        Source.Workspace.saveWorkspace();
        ((Source.Workspace) pack.source).export(pack, null, null, new Consumer<Double>() {
            @Override public void accept(Double ignored) {}
        });
        File output = new File(TileCatalog.bcuRoot(), "exports/" + PACK_ID + ".pack.bcuzip");
        Logger.log("CustomMap: exported " + output);
        return output;
    }

    public static Stage stage(String uuid) {
        try {
            PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
            if (pack == null) return null;
            MapIndex index = readIndex(pack);
            MapRecord record = find(index, uuid);
            if (record == null || !isLive(pack, index, record)) return null;
            return rawStage(pack, record);
        } catch (Throwable t) {
            Logger.err("CustomMap: stage lookup failed for " + uuid, t);
            return null;
        }
    }

    public static String uuidForStage(Stage stage) {
        if (stage == null || stage.id == null) return null;
        try {
            PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
            if (pack == null) return null;
            MapIndex index = readIndex(pack);

            String byBackground = uuidForBackground(index, stage.bg);
            if (byBackground != null) return byBackground;

            if (!isCustomStagePack(stage.id.pack)) return null;
            int stageMapId = stage.id.getCont() instanceof StageMap
                    ? ((StageMap) stage.id.getCont()).id.id : -1;
            if (stageMapId < 0) stageMapId =
                    stageMapIdFromQualifiedPack(stage.id.pack);
            for (MapRecord record : index.maps) {
                if (record != null && record.stageMapId == stageMapId
                        && record.stageId == stage.id.id
                        && isLive(pack, index, record)) return record.uuid;
            }
        } catch (Throwable t) {
            Logger.err("CustomMap: stage id lookup failed", t);
        }
        return null;
    }

    static boolean isCustomStagePack(String packId) {
        return PACK_ID.equals(packId)
                || (packId != null && packId.startsWith(PACK_ID + "/"));
    }

    static int stageMapIdFromQualifiedPack(String packId) {
        if (packId == null || !packId.startsWith(PACK_ID + "/")) return -1;
        try {
            return Integer.parseInt(packId.substring(PACK_ID.length() + 1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static String nameForBackground(Identifier<Background> background) {
        if (background == null || !PACK_ID.equals(background.pack)) return null;
        try {
            PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
            if (pack == null) return null;
            MapIndex index = readIndex(pack);
            for (MapRecord record : index.maps)
                if (record != null && record.backgroundId == background.id)
                    return record.name;
        } catch (Throwable ignored) {}
        return null;
    }

    public static CustomMapDocument documentForBackground(Identifier<Background> background) {
        if (background == null || !PACK_ID.equals(background.pack)) return null;
        try {
            PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
            if (pack == null) return null;
            String uuid = uuidForBackground(readIndex(pack), background);
            return uuid == null ? null : readDocument(pack, uuid);
        } catch (Throwable t) {
            Logger.err("CustomMap: map lookup failed for Background " + background, t);
            return null;
        }
    }

    private static String uuidForBackground(MapIndex index,
                                            Identifier<Background> background) {
        if (background == null || !PACK_ID.equals(background.pack)
                || index == null || index.maps == null) return null;
        for (MapRecord record : index.maps)
            if (record != null && record.backgroundId == background.id)
                return record.uuid;
        return null;
    }

    public static InputStream stream(String path) throws Exception {
        PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
        if (pack != null && pack.source != null) return pack.source.streamFile(path);
        return new FileInputStream(new File(workspaceRoot(), path.replace('/', File.separatorChar)));
    }

    public static File workspaceRoot() {
        return new File(new File(TileCatalog.bcuRoot(), "workspace"), PACK_ID);
    }

    public static synchronized int synchronizeBackgroundCatalog() throws Exception {
        PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
        if (pack == null) return 0;
        if (!(pack.source instanceof Source.Workspace)) {

            return pack.bgs == null ? 0 : pack.bgs.size();
        }

        MapIndex index = readIndexIfPresent(pack);
        if (index.maps == null || index.maps.isEmpty()) return 0;
        Set<Integer> claimed = new HashSet<Integer>();
        boolean changed = false;
        int available = 0;

        for (MapRecord record : index.maps) {
            if (record == null || record.uuid == null) continue;

            if (!isLive(pack, index, record)) continue;

            int id = record.backgroundId;
            if (id < 0 || claimed.contains(id)) {
                id = nextBackgroundId(pack, claimed,
                        Math.max(0, record.stageId));
                record.backgroundId = id;
                changed = true;
            }
            claimed.add(id);

            File image = new File(new File(workspaceRoot(), "backgrounds"),
                    trio(id) + ".png");
            Background background = pack.bgs.getRaw(id);
            if (background == null || !image.isFile()) {
                CustomMapDocument doc = readDocument(pack, record.uuid);
                if (doc == null) {
                    Logger.log("CustomMap: cannot register Background for missing map "
                            + record.uuid);
                    continue;
                }
                background = ensureBackground(pack, record, doc,
                        new File(workspaceRoot(), "custom_maps/" + record.uuid));
                changed = true;
            }

            StageMap stageMap = pack.mc.maps.getRaw(record.stageMapId);
            Stage stage = stageMap == null ? null : stageMap.list.getRaw(record.stageId);
            if (stage != null && !sameIdentifier(stage.bg, background.id)) {
                stage.bg = background.id;
                changed = true;
            }
            available++;
        }

        if (changed) {
            writeJson(new File(workspaceRoot(),
                    INDEX_PATH.replace('/', File.separatorChar)), index);
            Source.Workspace.saveWorkspace();
            Logger.log("CustomMap: synchronized " + available
                    + " Background selector entr" + (available == 1 ? "y" : "ies"));
        }
        return available;
    }

    private static int nextBackgroundId(PackData.UserPack pack,
                                        Set<Integer> claimed, int preferred) {
        int id = Math.max(0, preferred);
        while (claimed.contains(id) || pack.bgs.getRaw(id) != null) id++;
        return id;
    }

    private static boolean sameIdentifier(Identifier<Background> a,
                                          Identifier<Background> b) {
        return a == b || (a != null && b != null && a.id == b.id
                && (a.pack == null ? b.pack == null : a.pack.equals(b.pack)));
    }

    private static PackData.UserPack getOrCreateWorkspacePack() throws IOException {
        PackData.UserPack pack = UserProfile.getUserPack(PACK_ID);
        if (pack != null) {
            boolean marked = ownsMarker(pack.desc.desc)
                    || new File(workspaceRoot(), INDEX_PATH.replace('/', File.separatorChar)).isFile();
            if (!marked) throw new IOException("Pack id '" + PACK_ID
                    + "' is already used by a non-Studio pack. Rename that pack first.");
            if (!(pack.source instanceof Source.Workspace))
                throw new IOException("The installed Custom Map pack is read-only. Import it as an editable workspace first.");
            return pack;
        }
        pack = new PackData.UserPack(PACK_ID);
        pack.desc.name = PACK_NAME;
        pack.desc.desc = MARKER;
        try { pack.desc.names.put(PACK_NAME); } catch (Throwable ignored) {}
        UserProfile.profile().packmap.put(PACK_ID, pack);
        UserProfile.profile().packlist.add(pack);
        ensureStageMap(pack);
        Logger.log("CustomMap: created reserved pack '" + PACK_ID + "'");
        return pack;
    }

    private static Stage ensureStage(PackData.UserPack pack, MapIndex index,
                                     MapRecord record, CustomMapDocument doc) {
        StageMap sm = ensureStageMap(pack);
        Stage stage = record.stageId >= 0 ? sm.list.getRaw(record.stageId) : null;

        if (stage != null && !ownsStage(index, record, stage)) stage = null;
        if (stage == null) {
            stage = new Stage(sm);
            sm.add(stage);
        }
        stage.name = doc.name;
        try { stage.names.put(doc.name); } catch (Throwable ignored) {}
        CustomMapDocument.ModeVariant battle = doc.battleTerrain;
        if (battle != null && battle.destination != null) {

            stage.len = Math.max(3000,
                    Math.round(battle.worldX(battle.destination.x)) + 800);
        } else {
            int worldPerTile = CustomMapDocument.WORLD_PER_TILE;
            stage.len = Math.max(3000, doc.spec.width * worldPerTile);
        }
        stage.health = 60000;
        stage.maxSpawn = 8;
        if (record.backgroundId >= 0) {
            Background bg = pack.bgs.getRaw(record.backgroundId);
            if (bg != null) stage.bg = bg.id;
        }
        return stage;
    }

    private static Background ensureBackground(PackData.UserPack pack, MapRecord record,
                                               CustomMapDocument doc, File mapRoot)
            throws IOException {
        if (record.backgroundId < 0) {
            int preferred = Math.max(0, record.stageId);
            while (pack.bgs.getRaw(preferred) != null) preferred++;
            record.backgroundId = preferred;
        }
        Identifier<Background> id = new Identifier<Background>(
                PACK_ID, Background.class, record.backgroundId);
        File directory = new File(workspaceRoot(), "backgrounds");
        if (!directory.exists() && !directory.mkdirs())
            throw new IOException("Could not create " + directory);
        File image = new File(directory, trio(record.backgroundId) + ".png");
        writeBackgroundPreview(doc, mapRoot, image);

        Background bg = new Background(id,
                pack.source.readImage(Source.BasePath.BG.toString(), record.backgroundId));
        bg.ic = 1;
        bg.top = true;
        bg.effect = -1;
        applyBackgroundColors(bg, doc);
        pack.bgs.set(record.backgroundId, bg);
        if (pack.bgs.getRaw(record.backgroundId) != bg)
            throw new IOException("BCU rejected Custom Stage Background " + id);
        Logger.log("CustomMap: registered Background " + id + " for '"
                + doc.name + "'");
        return bg;
    }

    private static void applyBackgroundColors(Background bg, CustomMapDocument doc) {
        int top = doc.backgroundManifest == null
                ? 0xff53d7ef : doc.backgroundManifest.skyTopArgb;
        int bottom = doc.backgroundManifest == null
                ? 0xffc8f4f7 : doc.backgroundManifest.skyBottomArgb;
        int[] a = rgb(top), b = rgb(bottom);
        bg.cs[0] = a.clone();
        bg.cs[1] = b.clone();
        bg.cs[2] = b.clone();
        bg.cs[3] = b.clone();
    }

    private static int[] rgb(int argb) {
        return new int[]{(argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255};
    }

    static void writeBackgroundPreview(CustomMapDocument doc, File mapRoot,
                                       File output) throws IOException {
        File outputParent = output.getParentFile();
        if (outputParent != null && !outputParent.exists() && !outputParent.mkdirs())
            throw new IOException("Could not create " + outputParent);
        final int width = 1024, height = 1024;
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            int top = doc.backgroundManifest == null
                    ? 0xff53d7ef : doc.backgroundManifest.skyTopArgb;
            int bottom = doc.backgroundManifest == null
                    ? 0xffc8f4f7 : doc.backgroundManifest.skyBottomArgb;
            g.setPaint(new GradientPaint(0, 0, new Color(top, true),
                    0, height, new Color(bottom, true)));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (doc.backgroundManifest != null) {
                for (BackgroundLayoutEngine.DrawCommand command :
                        BackgroundLayoutEngine.layout(doc.backgroundManifest,
                                width, height, 0f)) {
                    if (command.asset == null || command.asset.asset == null
                            || command.asset.asset.isEmpty()) continue;
                    File source = new File(mapRoot,
                            command.asset.asset.replace('/', File.separatorChar));
                    BufferedImage image = source.isFile() ? ImageIO.read(source) : null;
                    if (image == null) continue;
                    java.awt.Composite old = g.getComposite();
                    if (command.alpha < 255)
                        g.setComposite(java.awt.AlphaComposite.SrcOver.derive(
                                command.alpha / 255f));
                    g.drawImage(image, Math.round(command.x), Math.round(command.y),
                            Math.round(command.width), Math.round(command.height), null);
                    g.setComposite(old);
                }
            }
        } finally {
            g.dispose();
        }
        File temp = new File(output.getParentFile(), output.getName() + ".tmp");
        if (!ImageIO.write(canvas, "png", temp))
            throw new IOException("No PNG writer is available.");
        Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String trio(int value) {
        String raw = Integer.toString(Math.max(0, value));
        while (raw.length() < 3) raw = "0" + raw;
        return raw;
    }

    private static void addEnemyDependencies(PackData.UserPack pack, CustomMapDocument doc) {
        if (pack == null || pack.desc == null || doc == null || doc.spec == null
                || doc.spec.enemyPool == null) return;
        for (String id : doc.spec.enemyPool) {
            if (id == null) continue;
            int split = id.lastIndexOf(':');
            if (split <= 0) continue;
            String sourcePack = id.substring(0, split).trim();
            if (!sourcePack.isEmpty() && !PACK_ID.equals(sourcePack)
                    && !pack.desc.dependency.contains(sourcePack)) pack.desc.dependency.add(sourcePack);
        }
    }

    private static void validateEnemyPool(CustomMapDocument doc) {
        if (doc == null || doc.spec == null || doc.spec.enemyPool == null
                || doc.spec.enemyPool.isEmpty()) return;
        java.util.HashSet<String> available = new java.util.HashSet<String>();
        try {
            for (PackData source : UserProfile.getAllPacks()) {
                if (source == null || source.enemies == null) continue;
                List<Enemy> enemies = source.enemies.getList();
                if (enemies == null) continue;
                for (Enemy enemy : enemies) if (enemy != null && enemy.id != null)
                    available.add(enemy.id.pack + ":" + enemy.id.id);
            }
        } catch (Throwable t) {
            throw new IllegalArgumentException("Could not validate the enemy pool: " + t.getMessage());
        }
        for (String id : doc.spec.enemyPool)
            if (id != null && !available.contains(id.trim()))
                throw new IllegalArgumentException("Enemy not found: " + id + " (expected pack:id).");
    }

    private static boolean sameTerrainGeometry(CustomMapDocument oldDoc,
                                               CustomMapDocument newDoc) {
        if (oldDoc == null || newDoc == null
                || !sameVariantGeometry(oldDoc.battleTerrain, newDoc.battleTerrain))
            return false;
        int oldCount = oldDoc.variants == null ? 0 : oldDoc.variants.size();
        int newCount = newDoc.variants == null ? 0 : newDoc.variants.size();
        if (oldCount != newCount) return false;
        if (newDoc.variants != null)
            for (java.util.Map.Entry<String, CustomMapDocument.ModeVariant> entry
                    : newDoc.variants.entrySet()) {
                CustomMapDocument.ModeVariant old = oldDoc.variants == null
                        ? null : oldDoc.variants.get(entry.getKey());
                if (!sameVariantGeometry(old, entry.getValue())) return false;
            }
        return true;
    }

    private static boolean sameBiome(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean sameVariantGeometry(CustomMapDocument.ModeVariant a,
                                               CustomMapDocument.ModeVariant b) {
        if (a == null || b == null || a.width != b.width || a.height != b.height
                || !Arrays.equals(a.cells, b.cells)
                || !Arrays.equals(a.surface, b.surface)
                || !Arrays.equals(a.surfaceMaterials, b.surfaceMaterials)
                || !Arrays.equals(a.iceSurfaceKeys, b.iceSurfaceKeys)
                || !Arrays.equals(a.walkSurfaceLayers, b.walkSurfaceLayers)
                || !Arrays.equals(a.slopeDirection, b.slopeDirection)
                || !Arrays.equals(a.slopePhase, b.slopePhase)
                || !Arrays.equals(a.slopeRunId, b.slopeRunId)
                || !Arrays.equals(a.water, b.water)) return false;
        int ac = a.secondaryPlatforms == null ? 0 : a.secondaryPlatforms.size();
        int bc = b.secondaryPlatforms == null ? 0 : b.secondaryPlatforms.size();
        if (ac != bc) return false;
        if (b.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform right : b.secondaryPlatforms) {
                if (right == null) return false;
                CustomMapDocument.SecondaryPlatform left = a.secondaryPlatform(right.id);
                if (left == null || left.startX != right.startX
                        || left.endX != right.endX
                        || left.surfaceMaterial != right.surfaceMaterial
                        || !Arrays.equals(left.iceSurfaceKeys,
                        right.iceSurfaceKeys)
                        || Float.floatToIntBits(left.supportLayer)
                        != Float.floatToIntBits(right.supportLayer)) return false;
            }
        return true;
    }

    private static void verifyStagedMap(File mapRoot, CustomMapDocument expected)
            throws IOException {
        File metadata = new File(mapRoot, "map.json");
        CustomMapDocument staged = readJson(metadata, CustomMapDocument.class);
        if (staged == null || staged.uuid == null || expected == null
                || !staged.uuid.equals(expected.uuid)
                || staged.terrainRevision != expected.terrainRevision)
            throw new IOException("The staged map metadata did not round-trip correctly.");
        requireStagedFile(mapRoot, "assets/ground/000.png", "ground tile");
        requireStagedFile(mapRoot, "assets/background/000.png", "opaque background");
        if (usesWater(staged)) {
            requireStagedFile(mapRoot, "assets/water/000.png", "water tile");
            requireStagedFile(mapRoot, "assets/water_surface/000.png",
                    "water-surface animation frame");
            requireStagedFile(mapRoot, "assets/water_fill/000.png", "water-body tile");
        }
        if (usesTrees(staged))
            requireStagedFile(mapRoot, "assets/tree/000.png", "tree tile");
        verifyIceSurfaceAssets(mapRoot, staged.iceSurfaceManifest);
        if (staged.backgroundManifest == null || staged.backgroundManifest.assets == null)
            throw new IOException("The staged background manifest is missing.");
        for (CustomMapDocument.BackgroundAssetRef asset : staged.backgroundManifest.assets)
            if (asset == null || asset.asset == null || asset.asset.isEmpty()
                    || !new File(mapRoot, asset.asset.replace('/', File.separatorChar)).isFile())
                throw new IOException("A staged background asset is missing.");
        verifyStagedVariant(mapRoot, "battle", staged.battleTerrain);
        if (staged.variants != null)
            for (CustomMapDocument.ModeVariant variant : staged.variants.values())
                if (variant != null) verifyStagedVariant(mapRoot, variant.mode, variant);
    }

    private static void verifyStagedVariant(File mapRoot, String id,
                                            CustomMapDocument.ModeVariant variant)
            throws IOException {
        if (variant == null) throw new IOException("A staged terrain variant is missing.");
        File chunks = new File(mapRoot, id + "/chunks/under");
        File[] terrain = chunks.listFiles();
        if (terrain == null || terrain.length == 0)
            throw new IOException("The staged " + id + " terrain chunks are missing.");
        if (variant.secondaryPlatforms == null) return;
        for (CustomMapDocument.SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || !platform.isPatrolling()) continue;
            String root = id + "/platforms/"
                    + CustomMapChunkWriter.safePlatformId(platform, variant) + "/";
            requireStagedFile(mapRoot, root + "under.png", "moving platform under sprite");
            requireStagedFile(mapRoot, root + "over.png", "moving platform over sprite");
        }
    }

    private static void requireStagedFile(File mapRoot, String relative, String label)
            throws IOException {
        File file = new File(mapRoot, relative.replace('/', File.separatorChar));
        if (!file.isFile() || file.length() <= 0L)
            throw new IOException("Missing staged " + label + ": " + relative);
    }

    private static void verifyIceSurfaceAssets(
            File mapRoot, CustomMapDocument.IceSurfaceManifest manifest)
            throws IOException {
        if (manifest == null || (manifest.tiles == null || manifest.tiles.isEmpty())
                && (manifest.breakFrames == null || manifest.breakFrames.isEmpty())) return;
        if (!manifest.isReady())
            throw new IOException("The staged breakable-ice asset manifest is incomplete.");
        for (CustomMapDocument.IceSurfaceAssetRef ref : manifest.tiles.values()) {
            if (ref == null || !ref.isComplete())
                throw new IOException(
                        "A staged breakable-ice tile record is incomplete.");
            requireStagedFile(mapRoot, ref.base, "ice base overlay");
            requireStagedFile(mapRoot, ref.crack1, "ice crack-01 overlay");
            requireStagedFile(mapRoot, ref.crack2, "ice crack-02 overlay");
            requireStagedFile(mapRoot, ref.crack3, "ice crack-03 overlay");
        }
        for (String frame : manifest.breakFrames)
            requireStagedFile(mapRoot, frame, "ice-break VFX frame");
    }

    private static boolean usesWater(CustomMapDocument doc) {
        if (doc == null) return false;
        if (variantUsesWater(doc.battleTerrain)) return true;
        if (doc.variants != null)
            for (CustomMapDocument.ModeVariant variant : doc.variants.values())
                if (variantUsesWater(variant)) return true;
        return false;
    }

    private static boolean variantUsesWater(CustomMapDocument.ModeVariant variant) {
        if (variant == null || variant.water == null) return false;
        for (boolean value : variant.water) if (value) return true;
        return false;
    }

    private static boolean usesTrees(CustomMapDocument doc) {
        if (doc == null) return false;
        if (doc.battleTerrain != null && doc.battleTerrain.trees != null
                && !doc.battleTerrain.trees.isEmpty()) return true;
        if (doc.variants != null)
            for (CustomMapDocument.ModeVariant variant : doc.variants.values())
                if (variant != null && variant.trees != null
                        && !variant.trees.isEmpty()) return true;
        return false;
    }

    private static StageMap ensureStageMap(PackData.UserPack pack) {
        StageMap sm = pack.mc.maps.getRaw(0);
        if (sm == null) {
            sm = new StageMap(new Identifier<StageMap>(PACK_ID, StageMap.class, 0));
            sm.name = PACK_NAME;
            try { sm.names.put(PACK_NAME); } catch (Throwable ignored) {}
            pack.mc.maps.set(0, sm);
        }
        return sm;
    }

    private static MapIndex readIndexIfPresent(PackData.UserPack pack) {
        try { return readIndex(pack); } catch (Throwable ignored) { return new MapIndex(); }
    }

    private static MapIndex readIndex(PackData.UserPack pack) throws IOException {
        InputStream in = null;
        try {
            in = pack.source.streamFile(INDEX_PATH);
            if (in == null) return new MapIndex();
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            MapIndex index = GSON.fromJson(reader, MapIndex.class);
            return index == null ? new MapIndex() : index;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static MapIndex readIndex(File file) throws IOException {
        if (!file.isFile()) return new MapIndex();
        MapIndex index = readJson(file, MapIndex.class);
        return index == null ? new MapIndex() : index;
    }

    private static CustomMapDocument readDocument(PackData.UserPack pack, String uuid) throws IOException {
        InputStream in = null;
        try {
            in = pack.source.streamFile("custom_maps/" + uuid + "/map.json");
            if (in == null) return null;
            CustomMapDocument doc = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    CustomMapDocument.class);
            if (doc != null && doc.schemaVersion > CustomMapDocument.SCHEMA_VERSION)
                throw new IOException("Map schema " + doc.schemaVersion + " is newer than this patch supports.");
            normalizeLoadedRevisions(doc);
            return doc;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static MapRecord find(MapIndex index, String uuid) {
        if (index != null && index.maps != null)
            for (MapRecord record : index.maps) if (record != null && uuid.equals(record.uuid)) return record;
        return null;
    }

    private static void normalizeLoadedRevisions(CustomMapDocument doc) {
        if (doc == null) return;
        doc.themeProfile = CustomMapDocument.ThemeProfile.normalized(doc.themeProfile);
        if (doc.iceSurfaceManifest == null)
            doc.iceSurfaceManifest = new CustomMapDocument.IceSurfaceManifest();
        normalizeLoadedPlatforms(doc.battleTerrain);
        if (doc.variants != null)
            for (CustomMapDocument.ModeVariant variant : doc.variants.values())
                normalizeLoadedPlatforms(variant);
        if (doc.backgroundManifest == null || doc.backgroundManifest.bands == null
                || doc.backgroundManifest.bands.isEmpty())
            doc.backgroundRevision = 0;
        if (CustomMapDocument.isSupportedTerrainRevision(doc.terrainRevision)) {
            boolean valid = revision11Variant(doc.battleTerrain);
            if (doc.variants != null)
                for (CustomMapDocument.ModeVariant variant : doc.variants.values())
                    valid &= revision11Variant(variant);
            if (!valid) doc.terrainRevision = 0;
            else if (doc.hasEnabledPlatformPatrols()
                    && doc.terrainRevision != CustomMapDocument.PATROL_TERRAIN_REVISION)
                doc.terrainRevision = 0;
        }
    }

    private static boolean ownsMarker(String marker) {
        return MARKER.equals(marker) || LEGACY_MARKER.equals(marker);
    }

    private static void normalizeLoadedPlatforms(CustomMapDocument.ModeVariant variant) {
        if (variant == null) return;
        if (variant.manualTiles == null)
            variant.manualTiles = new ArrayList<CustomMapDocument.ManualTile>();
        if (variant.manualSlopes == null)
            variant.manualSlopes = new ArrayList<CustomMapDocument.ManualSlopeRun>();
        if (variant.manualIslands == null)
            variant.manualIslands = new ArrayList<CustomMapDocument.ManualIslandRun>();
        if (variant.manualIceBridges == null)
            variant.manualIceBridges = new ArrayList<CustomMapDocument.ManualIceBridge>();
        if (variant.manualDecorations == null)
            variant.manualDecorations =
                    new ArrayList<CustomMapDocument.ManualDecoration>();
        if (variant.manualBackground == null)
            variant.manualBackground =
                    new ArrayList<CustomMapDocument.BackgroundLayer>();
        if (variant.manualEffects == null)
            variant.manualEffects =
                    new ArrayList<CustomMapDocument.ManualEffect>();
        MovingPlatformEngine.normalize(variant);
    }

    private static boolean revision11Variant(CustomMapDocument.ModeVariant variant) {
        return variant != null && variant.slopeRunId != null
                && variant.slopeRunId.length == variant.width
                && variant.motifs != null && variant.profile != null
                && variant.profile.complexityProfile != null;
    }

    private static void normalizeJsonTerrain(CustomMapDocument doc) throws IOException {
        if (doc == null) return;
        if (doc.spec == null || !finite(doc.spec.groundDensity)
                || !finite(doc.spec.waterDensity) || !finite(doc.spec.treeDensity)
                || !finite(doc.spec.propDensity)
                || !finite(doc.spec.iceSurfaceDensity)
                || !finite(doc.spec.floatingIslandDensity))
            throw new IOException("Map density contains a non-finite number. Regenerate the map.");
        if (doc.spec.groundDensity < 8.0 || doc.spec.groundDensity > 85.0)
            throw new IOException("Ground density is outside the playable 8-85% range. Regenerate the map.");
        if (doc.spec.waterDensity < 0.0 || doc.spec.waterDensity > 60.0)
            throw new IOException("Water density is outside the playable 0-60% range. Regenerate the map.");
        if (doc.spec.treeDensity < 0.0 || doc.spec.treeDensity > 100.0
                || doc.spec.propDensity < 0.0 || doc.spec.propDensity > 100.0)
            throw new IOException("Tree or prop density is outside 0-100%. Regenerate the map.");
        if (doc.spec.iceSurfaceDensity < 0.0
                || doc.spec.iceSurfaceDensity > 70.0)
            throw new IOException("Ice surface density is outside 0-70%. Regenerate the map.");
        if (!finite(doc.spec.iceBridgeDensity)
                || doc.spec.iceBridgeDensity < 0.0
                || doc.spec.iceBridgeDensity > 100.0)
            throw new IOException("Ice bridge density is outside 0-100%. Regenerate the map.");
        if (!finite(doc.spec.slopeCoverage)
                || doc.spec.slopeCoverage < 0.0
                || doc.spec.slopeCoverage > 80.0)
            throw new IOException("Slope coverage is outside 0-80%. Regenerate the map.");
        if (doc.spec.height < CustomMapGenerator.MIN_MAP_HEIGHT
                || doc.spec.height > CustomMapGenerator.MAX_MAP_HEIGHT)
            throw new IOException("Map height is outside "
                    + CustomMapGenerator.MIN_MAP_HEIGHT + "-"
                    + CustomMapGenerator.MAX_MAP_HEIGHT + ". Regenerate the map.");
        int maximumTerrainY = Math.min(CustomMapGenerator.MAX_GROUND_HEIGHT,
                doc.spec.height);
        if (doc.spec.slopeMinY < 2 || doc.spec.slopeMaxY > maximumTerrainY
                || doc.spec.slopeMinY > doc.spec.slopeMaxY
                || doc.spec.slopeCount < 0
                || doc.spec.slopeMinRise < 1 || doc.spec.slopeMaxRise > 10
                || doc.spec.slopeMinRise > doc.spec.slopeMaxRise
                || doc.spec.slopeMinLength < 1 || doc.spec.slopeMaxLength > 60
                || doc.spec.slopeMinLength > doc.spec.slopeMaxLength)
            throw new IOException("Slope count, terrain Y, rise or length settings are invalid. Regenerate the map.");
        if (doc.spec.floatingIslandCount < -1
                || doc.spec.floatingIslandCount
                > CustomMapGenerator.MAX_FLOATING_ISLAND_COUNT
                || doc.spec.floatingIslandLayers < -1
                || doc.spec.floatingIslandLayers
                > CustomMapGenerator.maxFloatingIslandLayers(doc.spec.height))
            throw new IOException("Floating-island count/layer settings are invalid. Regenerate the map.");
        normalizeVariant(doc.battleTerrain);
        if (doc.variants != null) for (CustomMapDocument.ModeVariant variant : doc.variants.values()) {
            normalizeVariant(variant);
        }
        if (doc.backgroundManifest == null || doc.backgroundManifest.assets == null
                || doc.backgroundManifest.bands == null || doc.backgroundManifest.bands.isEmpty())
            throw new IOException("Background composition is missing. Regenerate the map.");
        for (CustomMapDocument.BackgroundBand band : doc.backgroundManifest.bands) {
            if (band == null || !finite(band.minSize) || !finite(band.maxSize)
                    || !finite(band.minY) || !finite(band.maxY)
                    || !finite(band.minGap) || !finite(band.maxGap))
                throw new IOException("Background composition contains a non-finite number. Regenerate the map.");
        }
    }

    private static void normalizeVariant(CustomMapDocument.ModeVariant variant) throws IOException {
        if (variant == null)
            throw new IOException("Normal BCU stage terrain is missing. Regenerate the map.");
        if (variant.surface == null || variant.surface.length != variant.width)
            throw new IOException("Invalid terrain surface data. Regenerate the map.");
        if (variant.manualTiles == null)
            variant.manualTiles = new ArrayList<CustomMapDocument.ManualTile>();
        for (CustomMapDocument.ManualTile tile : variant.manualTiles) {
            if (tile == null || tile.x < 0 || tile.y < 0
                    || tile.x >= variant.width || tile.y >= variant.height)
                throw new IOException("A manual palette tile is outside the map grid.");
            String material = tile.material == null ? "" : tile.material;
            if (!CustomMapDocument.MATERIAL_NORMAL.equals(material)
                    && !CustomMapDocument.MATERIAL_ICE.equals(material)
                    && !CustomMapDocument.MATERIAL_WATER.equals(material)
                    && !CustomMapDocument.MATERIAL_LAVA.equals(material))
                throw new IOException("A manual palette tile has an unknown gameplay material.");
            if (unsafeAssetId(tile.preferredAsset))
                throw new IOException("A manual palette tile contains an unsafe asset path.");
            if (unsafeAssetId(tile.materialAsset))
                throw new IOException("A manual material contains an unsafe asset path.");
        }
        if (variant.manualSlopes == null)
            variant.manualSlopes = new ArrayList<CustomMapDocument.ManualSlopeRun>();
        if (variant.manualIslands == null)
            variant.manualIslands = new ArrayList<CustomMapDocument.ManualIslandRun>();
        if (variant.manualIceBridges == null)
            variant.manualIceBridges = new ArrayList<CustomMapDocument.ManualIceBridge>();
        for (CustomMapDocument.ManualSlopeRun run : variant.manualSlopes) {
            if (run == null || run.startX < 0 || run.endX < 0
                    || run.startX >= variant.width || run.endX >= variant.width
                    || run.startRow < 0 || run.endRow < 0
                    || run.startRow >= variant.height || run.endRow >= variant.height)
                throw new IOException("A manual slope is outside the map grid.");
            if (!"steep".equals(run.style)) run.style = "gentle";
        }
        for (CustomMapDocument.ManualIslandRun run : variant.manualIslands) {
            if (run == null || run.row < 0 || run.row >= variant.height
                    || run.startX < 0 || run.endX < run.startX
                    || run.endX >= variant.width || unsafeAssetId(run.asset))
                throw new IOException("A manual floating island is invalid.");
        }
        for (CustomMapDocument.ManualIceBridge bridge : variant.manualIceBridges) {
            if (bridge == null || bridge.row < 0 || bridge.row >= variant.height
                    || bridge.startX < 0 || bridge.endX < bridge.startX
                    || bridge.endX >= variant.width)
                throw new IOException("A manual ice bridge is invalid.");
        }
        boolean missingSurfaceMaterials = variant.surfaceMaterials == null;
        byte defaultMaterial = variant.profile != null
                && variant.profile.surfaceMaterial == CustomMapDocument.SURFACE_ICE
                ? CustomMapDocument.SURFACE_ICE : CustomMapDocument.SURFACE_NORMAL;
        if (missingSurfaceMaterials) {
            variant.surfaceMaterials = new byte[variant.width];
            Arrays.fill(variant.surfaceMaterials, defaultMaterial);
        } else if (variant.surfaceMaterials.length != variant.width) {
            throw new IOException("Invalid terrain surface-material data. Regenerate the map.");
        }
        for (int x = 0; x < variant.surfaceMaterials.length; x++)
            if (variant.surface[x] < 0
                    || (variant.water != null && x < variant.water.length
                    && variant.water[x])
                    || variant.surfaceMaterials[x] != CustomMapDocument.SURFACE_ICE)
                variant.surfaceMaterials[x] = CustomMapDocument.SURFACE_NORMAL;
        if (variant.walkSurfaceLayers == null
                || variant.walkSurfaceLayers.length != variant.width)
            variant.walkSurfaceLayers = new float[variant.width];
        for (int x = 0; x < variant.width; x++) {
            if (variant.surface[x] < 0) {
                variant.walkSurfaceLayers[x] = 0f;
            } else if (!finite(variant.walkSurfaceLayers[x])) {
                variant.walkSurfaceLayers[x] = -(variant.height - 1 - variant.surface[x])
                        * variant.layerUnitsPerTile();
            }
        }
        if (variant.navigationLinks == null || variant.navigationLinks.isEmpty())
            throw new IOException("Typed terrain navigation is missing. Regenerate the map.");
        for (CustomMapDocument.NavigationLink link : variant.navigationLinks)
            if (link == null || link.type == null || !finite(link.fromLayer)
                    || !finite(link.toLayer))
                throw new IOException("Terrain navigation contains invalid height data.");
        if (variant.secondaryPlatforms == null)
            variant.secondaryPlatforms =
                    new ArrayList<CustomMapDocument.SecondaryPlatform>();
        if (variant.slopeRunId == null || variant.slopeRunId.length != variant.width)
            variant.slopeRunId = new int[variant.width];
        if (variant.motifs == null)
            variant.motifs = new ArrayList<CustomMapDocument.TerrainMotif>();
        if (variant.profile != null && variant.profile.complexityProfile == null)
            variant.profile.complexityProfile =
                    new CustomMapDocument.ComplexityProfile();
        if (variant.profile == null || variant.profile.complexityProfile == null
                || !finite(variant.profile.complexityProfile.structuralScore)
                || !finite(variant.profile.complexityProfile.targetScore))
            throw new IOException("Terrain complexity metadata is invalid. Regenerate the map.");
        Set<String> platformIds = new HashSet<String>();
        Set<String> platformAssetIds = new HashSet<String>();
        Set<Integer> platformRows = new HashSet<Integer>();
        for (CustomMapDocument.SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || !finite(platform.supportLayer))
                throw new IOException("A secondary platform contains invalid height data.");
            if (missingSurfaceMaterials)
                platform.surfaceMaterial = defaultMaterial;
            else if (platform.surfaceMaterial != CustomMapDocument.SURFACE_ICE)
                platform.surfaceMaterial = CustomMapDocument.SURFACE_NORMAL;
            int row = Math.max(0, Math.min(variant.height - 1, Math.round(
                    variant.height + platform.supportLayer
                            / Math.max(1f, variant.layerUnitsPerTile()))));
            platformRows.add(row);
            if (platform.id == null || platform.id.trim().isEmpty())
                platform.id = CustomMapGenerator.stablePlatformId(
                        variant, row, platform.startX, platform.endX);
            if (!platformIds.add(platform.id))
                throw new IOException("Duplicate secondary platform id: " + platform.id);
            if (!platformAssetIds.add(CustomMapChunkWriter.safePlatformId(platform, variant)))
                throw new IOException("Two secondary platform ids resolve to the same asset path.");
            if (platform.patrol == null)
                MovingPlatformEngine.initializeAtOrigin(variant, platform);

            MovingPlatformEngine.normalize(variant, platform);
            if (platform.isPatrolling()) {
                CustomMapDocument.PlatformPatrol patrol = platform.patrol;
                if ("duel".equalsIgnoreCase(variant.mode))
                    throw new IOException("Duel terrain cannot contain moving platforms.");
                if (!finite(patrol.ax) || !finite(patrol.ay)
                        || !finite(patrol.bx) || !finite(patrol.by)
                        || !finite(patrol.speedTilesPerSecond)
                        || !finite(patrol.durationSeconds)
                        || !finite(patrol.dwellSeconds))
                    throw new IOException("Moving platform " + platform.id
                            + " contains a non-finite patrol value.");
                float patrolDx = patrol.bx - patrol.ax;
                float patrolDy = patrol.by - patrol.ay;
                float patrolPositionEpsilon =
                        MovingPlatformEngine.POSITION_EPSILON_TILES;
                if (patrolDx * patrolDx + patrolDy * patrolDy
                        <= patrolPositionEpsilon * patrolPositionEpsilon)
                    throw new IOException("Moving platform " + platform.id
                            + " needs two different patrol endpoints.");
                if (variant.trees != null)
                    for (CustomMapDocument.TreePlacement tree : variant.trees)
                        if (tree != null && tree.x >= platform.startX
                                && tree.x <= platform.endX && tree.y == row)
                            throw new IOException("Moving platform " + platform.id
                                    + " must be empty. Move its tree/object to static terrain.");
            }
        }
        variant.floatingIslandCount = variant.secondaryPlatforms.size();
        variant.floatingIslandLayerCount = platformRows.size();
        if ("battle".equalsIgnoreCase(variant.mode)) {
            if (variant.baseSafeZones == null || variant.baseSafeZones.size() < 2)
                throw new IOException("Normal-stage base safe zones are missing. Regenerate the map.");
            for (CustomMapDocument.BaseSafeZone zone : variant.baseSafeZones)
                if (zone == null || !finite(zone.supportLayer))
                    throw new IOException("A base safe zone contains invalid height data.");
        }
        if (!finite(variant.achievedGroundDensity)
                || !finite(variant.achievedWaterDensity)
                || !finite(variant.achievedTreeDensity)
                || !finite(variant.achievedFloatingIslandDensity))
            throw new IOException("Terrain metrics contain a non-finite number. Regenerate the map.");
        String patrolIssue = MovingPlatformValidator.firstBlockingMessage(variant);
        if (patrolIssue != null && !patrolIssue.isEmpty())
            throw new IOException(patrolIssue);
    }

    private static boolean unsafeAssetId(String value) {
        if (value == null || value.isEmpty()) return false;
        String path = value.replace('\\', '/');
        return path.startsWith("/") || path.contains("../")
                || path.contains(":") || path.indexOf('\0') >= 0;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static <T> T readJson(File file, Class<T> cls) throws IOException {
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        try { return GSON.fromJson(reader, cls); }
        finally { reader.close(); }
    }

    private static void writeJson(File file, Object value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8);
        try { GSON.toJson(value, writer); }
        finally { writer.close(); }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable ignored) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Could not remove " + file);
    }

    private static void deleteTreeQuietly(File file) {
        try { deleteTree(file); }
        catch (Throwable t) {
            Logger.err("CustomMap: could not remove transaction scratch " + file, t);
        }
    }

    private static final class StageSnapshot {
        final String name;
        final int len;
        final int health;
        final int maxSpawn;
        final Identifier<Background> background;
        final MultiLangData names;

        StageSnapshot(Stage stage) {
            name = stage.name;
            len = stage.len;
            health = stage.health;
            maxSpawn = stage.maxSpawn;
            background = stage.bg;
            names = stage.names == null ? null : stage.names.copy();
        }

        void restore(Stage stage) {
            if (stage == null) return;
            stage.name = name;
            stage.len = len;
            stage.health = health;
            stage.maxSpawn = maxSpawn;
            stage.bg = background;
            stage.names = names == null ? null : names.copy();
        }
    }

    private static final class MapIndex {
        int schemaVersion = CustomMapDocument.SCHEMA_VERSION;
        List<MapRecord> maps = new ArrayList<MapRecord>();
    }

    public static final class MapRecord {
        public String uuid;
        public String name = "Custom Map";
        public int stageMapId = 0;
        public int stageId = -1;

        public int backgroundId = -1;
        public long updatedAt;

        @Override public String toString() { return name; }
    }
}
