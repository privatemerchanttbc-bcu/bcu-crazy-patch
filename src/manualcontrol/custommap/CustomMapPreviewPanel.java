package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.MapAnchor;
import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.PlatformPatrol;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;
import manualcontrol.custommap.CustomMapDocument.TreePlacement;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class CustomMapPreviewPanel extends JPanel {

    private static final Map<TileCatalog.TileSet, PreviewAssets> CACHE =
            new WeakHashMap<TileCatalog.TileSet, PreviewAssets>();

    interface AnchorListener { void changed(ModeVariant variant); }

    interface StrokeListener {
        void finished(ModeVariant variant, List<java.awt.Point> cells);
        void decorationMoved(ModeVariant variant, int index, int tileX);
    }

    interface PatrolListener {
        void selectionChanged(ModeVariant variant, SecondaryPlatform platform);
        void patrolChanged(ModeVariant variant, SecondaryPlatform platform);
    }

    enum Overlay {
        GRID, HEIGHTFIELD, NAVIGATION, SLOPES, ZONES, TILE_ROLES, UNIT_SCALE,
        PATROL_ISLANDS
    }

    private ModeVariant variant;
    private CustomMapDocument document;
    private TileCatalog.TileSet tiles;
    private boolean placingSpawn = true;
    private boolean battleAnchors;
    private boolean anchorPlacementEnabled;
    private float zoom = 1f;
    private float pan = 0f;
    private float verticalPan = 1f;
    private boolean panningPreview;
    private int panDragStartX;
    private int panDragStartY;
    private float panDragStartHorizontal;
    private float panDragStartVertical;
    private AnchorListener listener;
    private PatrolListener patrolListener;
    private StrokeListener strokeListener;
    private CustomMapTerrainEditor.Tool editTool = CustomMapTerrainEditor.Tool.SELECT;
    private CustomMapPalette.Asset paletteAsset;
    private int brushSize = 1;
    private CustomMapTerrainEditor.SlopeMode slopeMode =
            CustomMapTerrainEditor.SlopeMode.AUTO;
    private CustomMapTerrainEditor.IceMode iceMode =
            CustomMapTerrainEditor.IceMode.APPLY;
    private final List<java.awt.Point> editStroke = new ArrayList<java.awt.Point>();
    private java.awt.Point hoverCell;
    private boolean editingStroke;
    private int draggingDecoration = -1;
    private int decorationDragX = -1;
    private SecondaryPlatform selectedPlatform;
    private boolean patrolEditingAllowed = true;

    private int draggingPatrolHandle;
    private long patrolAnimationEpochNanos = System.nanoTime();
    private final Timer patrolTimer;
    private PreviewAssets images = new PreviewAssets();
    private List<BufferedImage> trees = new ArrayList<BufferedImage>();
    private final Map<String, BufferedImage> embeddedBackgrounds =
            new HashMap<String, BufferedImage>();
    private final Map<String, BufferedImage> embeddedProps =
            new HashMap<String, BufferedImage>();
    private final Map<String, BufferedImage> paletteImages =
            new HashMap<String, BufferedImage>();
    private BufferedImage cachedTerrain;
    private ModeVariant cachedTerrainVariant;
    private TileCatalog.TileSet cachedTerrainTiles;
    private int cachedTerrainTilePx;

    private BufferedImage cachedStaticScene;
    private ModeVariant cachedStaticVariant;
    private TileCatalog.TileSet cachedStaticTiles;
    private int cachedStaticWidth;
    private int cachedStaticHeight;
    private float cachedStaticLayoutX;
    private float cachedStaticLayoutY;
    private float cachedStaticCell;
    private final Map<SecondaryPlatform, BufferedImage> patrolSprites =
            new IdentityHashMap<SecondaryPlatform, BufferedImage>();
    private final Map<BufferedImage, Path2D.Float> patrolSpriteOutlines =
            new WeakHashMap<BufferedImage, Path2D.Float>();
    private final Map<SecondaryPlatform, String> patrolValidationCache =
            new IdentityHashMap<SecondaryPlatform, String>();
    private long patrolValidationSignature = Long.MIN_VALUE;
    private final Map<Overlay, Boolean> overlays =
            new java.util.EnumMap<Overlay, Boolean>(Overlay.class);

    CustomMapPreviewPanel() {
        for (Overlay overlay : Overlay.values()) overlays.put(overlay, false);
        overlays.put(Overlay.UNIT_SCALE, true);
        setBackground(new Color(36, 45, 58));
        setToolTipText("Drag empty Preview space to pan horizontally and vertically. Select placement mode before changing an anchor.");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (variant == null || getWidth() <= 0) return;
                Layout layout = previewLayout();
                boolean insideLayout = e.getX() >= layout.x
                        && e.getX() < layout.x + layout.width
                        && e.getY() >= layout.y
                        && e.getY() < layout.y + layout.height;
                if (e.getButton() == MouseEvent.BUTTON3) {
                    beginPreviewPan(e);
                    return;
                }
                if (editTool != CustomMapTerrainEditor.Tool.SELECT) {
                    if (!insideLayout) return;
                    java.awt.Point cell = editCellAt(e, layout);
                    if (cell == null) return;
                    editStroke.clear();
                    editStroke.add(cell);
                    editingStroke = true;
                    hoverCell = cell;
                    repaint();
                    return;
                }
                if (anchorPlacementEnabled) {
                    if (!insideLayout) return;
                    int x = Math.max(0, Math.min(variant.width - 1,
                            (int) ((e.getX() - layout.x) / layout.cell)));
                    if (battleAnchors)
                        CustomMapGenerator.moveBattleAnchor(variant, placingSpawn, x);
                    else
                        CustomMapGenerator.moveAnchor(variant, placingSpawn, x);
                    if (listener != null) listener.changed(variant);
                    repaint();
                    return;
                }
                int decoration = insideLayout ? manualDecorationAt(e, layout) : -1;
                if (decoration >= 0) {
                    draggingDecoration = decoration;
                    decorationDragX = Math.max(0, Math.min(variant.width - 1,
                            (int) ((e.getX() - layout.x) / layout.cell)));
                    return;
                }
                if (insideLayout && patrolEditingAllowed) {
                    if (overlayEnabled(Overlay.PATROL_ISLANDS)) {
                        int handle = patrolHandleAt(e.getX(), e.getY(), layout);
                        if (handle != 0) {
                            draggingPatrolHandle = handle;
                            return;
                        }
                    }
                    SecondaryPlatform hit = patrolPlatformAt(e.getX(), e.getY(), layout);
                    if (hit != null) {
                        selectPatrolPlatform(hit, true);
                        return;
                    }
                    if (overlayEnabled(Overlay.PATROL_ISLANDS)
                            && patrolEndpointGhostAt(e.getX(), e.getY(), layout)) {
                        selectPatrolPlatform(selectedPlatform, true);
                        return;
                    }
                }
                if (e.getButton() != MouseEvent.BUTTON1) return;
                beginPreviewPan(e);
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (panningPreview) {
                    Layout layout = previewLayout();
                    float availableW = Math.max(1f, getWidth() - 16f);
                    float availableH = Math.max(1f, getHeight() - 50f);
                    float overflowX = Math.max(0f, layout.width - availableW);
                    float overflowY = Math.max(0f, layout.height - availableH);
                    if (overflowX > 0f)
                        pan = clampPan(panDragStartHorizontal
                                - (e.getX() - panDragStartX) / overflowX);
                    if (overflowY > 0f)
                        verticalPan = clampPan(panDragStartVertical
                                - (e.getY() - panDragStartY) / overflowY);
                    repaint();
                    return;
                }
                if (draggingDecoration >= 0) {
                    Layout layout = previewLayout();
                    decorationDragX = Math.max(0, Math.min(variant.width - 1,
                            (int) ((e.getX() - layout.x) / layout.cell)));
                    repaint();
                    return;
                }
                if (editingStroke) {
                    java.awt.Point cell = editCellAt(e, previewLayout());
                    if (cell != null && (editStroke.isEmpty()
                            || !cell.equals(editStroke.get(editStroke.size() - 1))))
                        editStroke.add(cell);
                    hoverCell = cell;
                    repaint();
                    return;
                }
                if (draggingPatrolHandle == 0 || selectedPlatform == null
                        || variant == null || !patrolEditingAllowed) return;
                PlatformPatrol patrol = ensurePatrol(selectedPlatform);
                Layout layout = previewLayout();
                float x = (e.getX() - layout.x) / layout.cell;
                float y = (layout.y + layout.height - e.getY()) / layout.cell;
                float halfWidth = platformWidth(selectedPlatform) * .5f;
                x = Math.max(halfWidth, Math.min(variant.width - halfWidth, x));
                y = Math.max(1f, Math.min(variant.height, y));
                if (draggingPatrolHandle == 1) {
                    patrol.ax = x;
                    patrol.ay = y;
                } else {
                    patrol.bx = x;
                    patrol.by = y;
                }
                patrol.coordinatesInitialized = true;
                MovingPlatformEngine.normalize(variant, selectedPlatform);
                patrolAnimationEpochNanos = System.nanoTime();
                if (patrolListener != null)
                    patrolListener.patrolChanged(variant, selectedPlatform);
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (panningPreview) {
                    panningPreview = false;
                    return;
                }
                if (draggingDecoration >= 0) {
                    int index = draggingDecoration;
                    int x = decorationDragX;
                    draggingDecoration = -1;
                    decorationDragX = -1;
                    if (strokeListener != null)
                        strokeListener.decorationMoved(variant, index, x);
                    repaint();
                    return;
                }
                if (editingStroke) {
                    editingStroke = false;
                    List<java.awt.Point> completed =
                            new ArrayList<java.awt.Point>(editStroke);
                    editStroke.clear();
                    if (strokeListener != null && !completed.isEmpty())
                        strokeListener.finished(variant, completed);
                    repaint();
                    return;
                }
                draggingPatrolHandle = 0;
            }

            @Override public void mouseMoved(MouseEvent e) {
                hoverCell = variant == null ? null : editCellAt(e, previewLayout());
                if (editTool != CustomMapTerrainEditor.Tool.SELECT) repaint();
            }

            @Override public void mouseExited(MouseEvent e) {
                if (!editingStroke) { hoverCell = null; repaint(); }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(e -> {
            if (zoom <= 1f) return;
            if (e.isShiftDown())
                verticalPan = clampPan(verticalPan + e.getWheelRotation() * 0.07f);
            else
                pan = clampPan(pan + e.getWheelRotation() * 0.07f);
            repaint();
        });
        patrolTimer = new Timer(33, e -> {

            if (isShowing() && hasEnabledPatrol()) repaint();
        });
        patrolTimer.setCoalesce(true);
    }

    @Override public void addNotify() {
        super.addNotify();
        patrolTimer.start();
    }

    @Override public void removeNotify() {
        patrolTimer.stop();
        super.removeNotify();
    }

    void setVariant(ModeVariant value, TileCatalog.TileSet set) {
        setDocument(null, value, set);
    }

    void setDocument(CustomMapDocument doc, ModeVariant value, TileCatalog.TileSet set) {
        if (document != doc) {
            embeddedBackgrounds.clear();
            embeddedProps.clear();
            paletteImages.clear();
        }
        document = doc;
        variant = value;
        long familySeed = doc != null && doc.spec != null
                ? doc.spec.seed : value == null ? 0L : value.seed;
        tiles = set == null ? null : set.resolveBaseGroundFamily(familySeed);
        PreviewAssets assets = assets(tiles);
        images = assets;
        loadManualThemeAssets(doc, value, assets, familySeed);
        trees = assets.trees;
        invalidatePatrolTerrain();
        if (selectedPlatform == null || value == null
                || value.secondaryPlatforms == null
                || !value.secondaryPlatforms.contains(selectedPlatform))
            selectedPlatform = firstPlatform(value);
        patrolAnimationEpochNanos = System.nanoTime();
        repaint();
    }

    ModeVariant variant() { return variant; }

    private static void loadManualThemeAssets(CustomMapDocument doc,
                                              ModeVariant value,
                                              PreviewAssets root,
                                              long familySeed) {
        if (value == null || value.manualTiles == null) return;
        Set<String> themes = new HashSet<String>();
        for (CustomMapDocument.ManualTile tile : value.manualTiles) {
            if (tile == null) continue;
            if (tile.sourceTheme != null && !tile.sourceTheme.trim().isEmpty())
                themes.add(TerrainTileRenderer.themeKey(
                        tile.sourceTheme, tile.family));
            if (tile.materialTheme != null && !tile.materialTheme.trim().isEmpty())
                themes.add(TerrainTileRenderer.themeKey(
                        tile.materialTheme, tile.materialFamily));
            loadPreferredPaletteAsset(doc, root, tile.preferredAsset);
            loadPreferredPaletteAsset(doc, root, tile.materialAsset);
            }
        for (String key : themes) {
            try {
                int split = key.indexOf('\n');
                String theme = split < 0 ? key : key.substring(0, split);
                String family = split < 0 ? "" : key.substring(split + 1);
                TileCatalog.TileSet set = TileCatalog.find(theme);
                PreviewAssets selected;
                if (set == null) selected = embeddedPaletteAssets(doc, theme, family);
                else {
                    TileCatalog.TileSet source = paletteFamily(set, family, familySeed);
                    selected = assets(source);
                }
                if (selected != root) root.themeAssets.put(key, selected);
            } catch (Throwable ignored) {}
        }
    }

    private static void loadPreferredPaletteAsset(CustomMapDocument doc,
                                                  PreviewAssets root,
                                                  String assetId) {
        if (assetId == null || assetId.isEmpty()
                || root.preferredAssets.containsKey(assetId)) return;
        BufferedImage preferred = readPaletteAsset(doc, assetId);
        if (preferred != null) root.preferredAssets.put(assetId, preferred);
    }

    private static BufferedImage readPaletteAsset(CustomMapDocument doc, String id) {
        if (id == null) return null;
        int slash = id.indexOf('/');
        if (slash <= 0 || slash + 1 >= id.length()) return null;
        String theme = id.substring(0, slash);
        String relative = id.substring(slash + 1);
        try {
            TileCatalog.TileSet set = TileCatalog.find(theme);
            if (set != null && set.root != null) {
                File file = new File(set.root,
                        relative.replace('/', File.separatorChar)).getCanonicalFile();
                if (file.toPath().startsWith(set.root.getCanonicalFile().toPath()))
                    return ImageIO.read(file);
            }
        } catch (Throwable ignored) {}
        if (doc == null || doc.uuid == null) return null;
        InputStream in = null;
        try {
            in = CustomMapRepository.stream("custom_maps/" + doc.uuid
                    + "/assets/palette/" + safePaletteName(theme)
                    + "/selected/" + relative);
            return in == null ? null : ImageIO.read(in);
        } catch (Throwable ignored) { return null; }
        finally { if (in != null) try { in.close(); } catch (Throwable ignored) {} }
    }

    private static PreviewAssets embeddedPaletteAssets(CustomMapDocument doc,
                                                        String theme,
                                                        String family) {
        PreviewAssets out = new PreviewAssets();
        if (doc == null || doc.uuid == null) return out;
        String base = "custom_maps/" + doc.uuid + "/assets/palette/"
                + safePaletteName(theme) + "/connectors/"
                + safePaletteName(family) + "/";
        out.ground = readEmbeddedSequence(base + "ground/");
        out.groundSurface = readEmbeddedSequence(base + "surface/");
        out.groundFill = readEmbeddedSequence(base + "fill/");
        out.groundLeft = readEmbeddedSequence(base + "left/");
        out.groundRight = readEmbeddedSequence(base + "right/");
        out.groundBottom = readEmbeddedSequence(base + "bottom/");
        out.groundTopLeft = readEmbeddedSequence(base + "top_left/");
        out.groundTopRight = readEmbeddedSequence(base + "top_right/");
        out.groundBottomLeft = readEmbeddedSequence(base + "bottom_left/");
        out.groundBottomRight = readEmbeddedSequence(base + "bottom_right/");
        out.groundInnerTopLeft = readEmbeddedSequence(base + "inner_top_left/");
        out.groundInnerTopRight = readEmbeddedSequence(base + "inner_top_right/");
        out.groundInnerBottomLeft = readEmbeddedSequence(base + "inner_bottom_left/");
        out.groundInnerBottomRight = readEmbeddedSequence(base + "inner_bottom_right/");
        out.groundPlatformCenter = readEmbeddedSequence(base + "platform_center/");
        out.groundPlatformLeft = readEmbeddedSequence(base + "platform_left/");
        out.groundPlatformRight = readEmbeddedSequence(base + "platform_right/");
        out.groundPlatformSingle = readEmbeddedSequence(base + "platform_single/");
        out.groundSlopeUp = readEmbeddedSequence(base + "slope_up/");
        out.groundSlopeDown = readEmbeddedSequence(base + "slope_down/");
        out.groundSteepSlopeUp = readEmbeddedSequence(base + "slope_steep_up/");
        out.groundSteepSlopeDown = readEmbeddedSequence(base + "slope_steep_down/");
        out.groundSlopeUpSupport = readEmbeddedSequence(base + "slope_up_support/");
        out.groundSlopeDownSupport = readEmbeddedSequence(base + "slope_down_support/");
        out.groundSteepSlopeUpSupport =
                readEmbeddedSequence(base + "slope_steep_up_support/");
        out.groundSteepSlopeDownSupport =
                readEmbeddedSequence(base + "slope_steep_down_support/");
        out.groundSlopeUpEndpointSupport =
                readEmbeddedSequence(base + "slope_up_endpoint_support/");
        out.groundSlopeDownEndpointSupport =
                readEmbeddedSequence(base + "slope_down_endpoint_support/");
        out.groundSteepSlopeUpEndpointSupport =
                readEmbeddedSequence(base + "slope_steep_up_endpoint_support/");
        out.groundSteepSlopeDownEndpointSupport =
                readEmbeddedSequence(base + "slope_steep_down_endpoint_support/");
        out.iceSurfaceBase = readEmbeddedSequence(base + "ice_surface/");
        out.iceSurfaceTopLeft = readEmbeddedSequence(base + "ice_top_left/");
        out.iceSurfaceTopRight = readEmbeddedSequence(base + "ice_top_right/");
        out.iceSurfacePlatformCenter =
                readEmbeddedSequence(base + "ice_platform_center/");
        out.iceSurfacePlatformLeft =
                readEmbeddedSequence(base + "ice_platform_left/");
        out.iceSurfacePlatformRight =
                readEmbeddedSequence(base + "ice_platform_right/");
        out.iceSurfacePlatformSingle =
                readEmbeddedSequence(base + "ice_platform_single/");
        out.iceSurfaceSlopeUp = readEmbeddedSequence(base + "ice_slope_up/");
        out.iceSurfaceSlopeDown = readEmbeddedSequence(base + "ice_slope_down/");
        for (int width = 1; width <= 12; width++) {
            BufferedImage span = readEmbeddedImage(base + "floating_span/"
                    + String.format("%03d.png", width));
            if (span != null) out.floatingIslandSpans.put(width, span);
        }
        out.widthSpecificFloatingIslands = !out.floatingIslandSpans.isEmpty();
        out.water = readEmbeddedSequence(base + "liquid/");
        out.waterSurface = readEmbeddedSequence(base + "liquid_surface/");
        out.waterFill = readEmbeddedSequence(base + "liquid_fill/");
        out.omitExposedWaterSurface = true;
        return out;
    }

    private static BufferedImage readEmbeddedImage(String path) {
        InputStream in = null;
        try {
            in = CustomMapRepository.stream(path);
            return in == null ? null : ImageIO.read(in);
        } catch (Throwable ignored) { return null; }
        finally { if (in != null) try { in.close(); } catch (Throwable ignored) {} }
    }

    private static List<BufferedImage> readEmbeddedSequence(String directory) {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        for (int index = 0; index < 1000; index++) {
            InputStream in = null;
            try {
                in = CustomMapRepository.stream(directory
                        + String.format("%03d.png", index));
                if (in == null) break;
                BufferedImage image = ImageIO.read(in);
                if (image == null) break;
                out.add(image);
            } catch (Throwable ignored) { break; }
            finally { if (in != null) try { in.close(); } catch (Throwable ignored) {} }
        }
        return out;
    }

    private static TileCatalog.TileSet paletteFamily(TileCatalog.TileSet set,
                                                     String family,
                                                     long seed) {
        if (set != null && family != null && !family.isEmpty())
            for (TileCatalog.TileSet candidate : set.groundFamilies)
                if (candidate != null && family.equalsIgnoreCase(candidate.groundFamily))
                    return candidate;
        return set == null ? null : set.resolveBaseGroundFamily(seed);
    }

    void setPlacingSpawn(boolean value) { placingSpawn = value; }

    void setBattleAnchors(boolean value) {
        battleAnchors = value;
        updateInteractionToolTip();
    }

    void setAnchorPlacementEnabled(boolean value) {
        anchorPlacementEnabled = value;
        panningPreview = false;
        updateInteractionToolTip();
    }

    boolean anchorPlacementEnabled() { return anchorPlacementEnabled; }

    float horizontalPanLevel() { return pan; }

    float verticalPanLevel() { return verticalPan; }

    private void updateInteractionToolTip() {
        if (editTool != CustomMapTerrainEditor.Tool.SELECT) {
            setToolTipText("Left-drag edits with the selected palette tool; right-drag pans Preview horizontally and vertically.");
            return;
        }
        if (!anchorPlacementEnabled) {
            setToolTipText("Drag empty Preview space to pan horizontally and vertically. Select placement mode before changing an anchor.");
            return;
        }
        setToolTipText(battleAnchors
                ? "Click snaps the selected S/G base to an existing 9-column flat, dry plateau; terrain is unchanged."
                : "Click snaps the selected anchor to the nearest eligible flat, dry site; terrain is unchanged.");
    }

    private void beginPreviewPan(MouseEvent event) {
        panningPreview = true;
        panDragStartX = event.getX();
        panDragStartY = event.getY();
        panDragStartHorizontal = pan;
        panDragStartVertical = verticalPan;
    }

    void setZoom(float value) {
        zoom = Math.max(1f, Math.min(16f, value));
        pan = 0f;
        verticalPan = 1f;
        repaint();
    }

    float zoomLevel() { return zoom; }

    float previewCellSize() {
        return variant == null ? 0f : previewLayout().cell;
    }

    private static float clampPan(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    void setAnchorListener(AnchorListener value) { listener = value; }

    void setPatrolListener(PatrolListener value) { patrolListener = value; }

    void setStrokeListener(StrokeListener value) { strokeListener = value; }

    void setEditingTool(CustomMapTerrainEditor.Tool tool,
                        CustomMapPalette.Asset asset, int size) {
        setEditingTool(tool, asset, size, CustomMapTerrainEditor.SlopeMode.AUTO,
                CustomMapTerrainEditor.IceMode.APPLY);
    }

    void setEditingTool(CustomMapTerrainEditor.Tool tool,
                        CustomMapPalette.Asset asset, int size,
                        CustomMapTerrainEditor.SlopeMode selectedSlopeMode,
                        CustomMapTerrainEditor.IceMode selectedIceMode) {
        editTool = tool == null ? CustomMapTerrainEditor.Tool.SELECT : tool;
        paletteAsset = asset;
        brushSize = Math.max(1, Math.min(2, size));
        slopeMode = selectedSlopeMode == null
                ? CustomMapTerrainEditor.SlopeMode.AUTO : selectedSlopeMode;
        iceMode = selectedIceMode == null
                ? CustomMapTerrainEditor.IceMode.APPLY : selectedIceMode;
        editingStroke = false;
        panningPreview = false;
        draggingDecoration = -1;
        decorationDragX = -1;
        editStroke.clear();
        setCursor(editTool == CustomMapTerrainEditor.Tool.SELECT
                ? java.awt.Cursor.getDefaultCursor()
                : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
        updateInteractionToolTip();
        repaint();
    }

    void setPatrolEditingAllowed(boolean value) {
        patrolEditingAllowed = value;
        if (!value) draggingPatrolHandle = 0;
        repaint();
    }

    boolean patrolEditingAllowed() { return patrolEditingAllowed; }

    SecondaryPlatform selectedPatrolPlatform() { return selectedPlatform; }

    void selectPatrolPlatform(SecondaryPlatform platform) {
        selectPatrolPlatform(platform, false);
    }

    void restartPatrolPreview() {
        patrolAnimationEpochNanos = System.nanoTime();
        repaint();
    }

    void invalidatePatrolTerrain() {
        cachedTerrain = null;
        cachedTerrainVariant = null;
        cachedTerrainTiles = null;
        cachedTerrainTilePx = 0;
        cachedStaticScene = null;
        cachedStaticVariant = null;
        cachedStaticTiles = null;
        cachedStaticWidth = cachedStaticHeight = 0;
        patrolSprites.clear();
        patrolValidationCache.clear();
        patrolValidationSignature = Long.MIN_VALUE;
        repaint();
    }

    void setOverlay(Overlay overlay, boolean enabled) {
        if (overlay == null) return;
        overlays.put(overlay, enabled);
        if (overlay == Overlay.PATROL_ISLANDS && enabled)
            patrolAnimationEpochNanos = System.nanoTime();
        repaint();
    }

    boolean overlayEnabled(Overlay overlay) {
        return Boolean.TRUE.equals(overlays.get(overlay));
    }

    @Override protected void paintComponent(Graphics raw) {
        super.paintComponent(raw);
        Graphics2D g = (Graphics2D) raw.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (variant == null) {
                g.setColor(new Color(205, 215, 226));
                g.drawString("Generate or load a map to preview this mode.", 22, 32);
                return;
            }
            Layout layout = previewLayout();
            if (hasEnabledPatrol()) {
                BufferedImage scene = staticPatrolScene(layout);
                if (scene != null) g.drawImage(scene, 0, 0, null);
            } else {
                g.setColor(new Color(91, 151, 195));
                g.fillRect(0, 0, getWidth(), getHeight());
                drawBackground(g);
                drawTrees(g, layout);
                drawProps(g, layout);
                drawTerrainLayer(g, layout);
                drawManualDecorations(g, layout);
            }
            drawMovingPatrolPlatforms(g, layout);
            if (overlayEnabled(Overlay.PATROL_ISLANDS)) drawPatrolIslands(g, layout);
            if (overlayEnabled(Overlay.UNIT_SCALE)) drawUnitScale(g, layout);
            drawAnchor(g, variant.spawn, layout, new Color(80, 245, 155), "S");
            drawAnchor(g, variant.destination, layout, new Color(255, 205, 70), "G");
            if (overlayEnabled(Overlay.ZONES)) drawZones(g, layout);
            if (overlayEnabled(Overlay.HEIGHTFIELD)) drawHeightfield(g, layout);
            if (overlayEnabled(Overlay.NAVIGATION)) drawNavigation(g, layout);
            if (overlayEnabled(Overlay.SLOPES)) drawSlopePhases(g, layout);
            if (overlayEnabled(Overlay.TILE_ROLES)) drawTileRoles(g, layout);
            if (overlayEnabled(Overlay.GRID)) drawGrid(g, layout);
            drawEditPreview(g, layout);
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(8, 8, 260, 26, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(variant.mode + "  |  " + variant.validation, 16, 26);
        } finally {
            g.dispose();
        }
    }

    private java.awt.Point editCellAt(MouseEvent event, Layout layout) {
        if (layout == null || event.getX() < layout.x || event.getY() < layout.y
                || event.getX() >= layout.x + layout.width
                || event.getY() >= layout.y + layout.height) return null;
        int x = (int) ((event.getX() - layout.x) / layout.cell);
        int y = (int) ((event.getY() - layout.y) / layout.cell);
        if (x < 0 || y < 0 || x >= variant.width || y >= variant.height) return null;
        return new java.awt.Point(x, y);
    }

    private void drawEditPreview(Graphics2D g, Layout layout) {
        if (editTool == CustomMapTerrainEditor.Tool.SELECT) return;
        List<java.awt.Point> input = editingStroke && !editStroke.isEmpty()
                ? editStroke : hoverCell == null
                ? java.util.Collections.<java.awt.Point>emptyList()
                : java.util.Collections.singletonList(hoverCell);
        if (input.isEmpty()) return;
        CustomMapTerrainEditor.StrokeProposal proposal =
                CustomMapTerrainEditor.propose(variant, input, paletteAsset,
                        editTool, brushSize, slopeMode, iceMode);
        Composite old = g.getComposite();
        Stroke stroke = g.getStroke();
        Color color = proposal.valid
                ? editTool == CustomMapTerrainEditor.Tool.ERASER
                ? new Color(245, 90, 80) : new Color(55, 230, 145)
                : new Color(255, 65, 65);
        g.setComposite(AlphaComposite.SrcOver.derive(.34f));
        g.setColor(color);
        List<java.awt.Point> cells = proposal.valid ? proposal.cells : input;
        for (java.awt.Point point : cells) {
            if (point == null || point.x < 0 || point.y < 0
                    || point.x >= variant.width || point.y >= variant.height) continue;
            int left = Math.round(layout.x + point.x * layout.cell);
            int top = Math.round(layout.y + point.y * layout.cell);
            int size = Math.max(1, Math.round(layout.cell));
            g.fillRect(left, top, size, size);
        }
        g.setComposite(old);
        g.setStroke(new BasicStroke(2f));
        g.setColor(color.brighter());
        java.awt.Point focus = proposal.end == null
                ? input.get(input.size() - 1) : proposal.end;
        int focusSize = editTool == CustomMapTerrainEditor.Tool.SLOPE
                || editTool == CustomMapTerrainEditor.Tool.ICE
                || editTool == CustomMapTerrainEditor.Tool.ISLAND ? 1 : brushSize;
        g.drawRect(Math.round(layout.x + focus.x * layout.cell),
                Math.round(layout.y + focus.y * layout.cell),
                Math.max(1, Math.round(layout.cell * focusSize)),
                Math.max(1, Math.round(layout.cell * focusSize)));
        if (proposal.start != null && proposal.end != null
                && (editTool == CustomMapTerrainEditor.Tool.SLOPE
                || editTool == CustomMapTerrainEditor.Tool.ICE
                || editTool == CustomMapTerrainEditor.Tool.ISLAND)) {
            drawProposalMarker(g, layout, proposal.start, "A", color);
            drawProposalMarker(g, layout, proposal.end, "B", color);
        }
        if (!proposal.valid && proposal.message != null
                && !proposal.message.isEmpty()) {
            String message = proposal.message.length() > 92
                    ? proposal.message.substring(0, 89) + "..." : proposal.message;
            int boxY = Math.max(38, Math.round(layout.y + focus.y * layout.cell) - 26);
            g.setColor(new Color(0, 0, 0, 190));
            g.fillRoundRect(12, boxY - 16,
                    Math.min(getWidth() - 24, g.getFontMetrics().stringWidth(message) + 16),
                    23, 7, 7);
            g.setColor(new Color(255, 220, 220));
            g.drawString(message, 20, boxY);
        }
        g.setStroke(stroke);
    }

    private static void drawProposalMarker(Graphics2D g, Layout layout,
                                           java.awt.Point point, String text,
                                           Color color) {
        int x = Math.round(layout.x + (point.x + .5f) * layout.cell);
        int y = Math.round(layout.y + (point.y + .5f) * layout.cell);
        int radius = Math.max(5, Math.round(layout.cell * .22f));
        g.setColor(new Color(0, 0, 0, 180));
        g.fillOval(x - radius - 1, y - radius - 1,
                radius * 2 + 2, radius * 2 + 2);
        g.setColor(color.brighter());
        g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        g.setColor(Color.BLACK);
        g.drawString(text, x - 4, y + 4);
    }

    private void drawTerrainLayer(Graphics2D g, Layout layout) {

        if (!hasEnabledPatrol()) {
            TerrainTileRenderer.draw(g, variant, images, layout.cell, layout.x, layout.y);
            return;
        }
        BufferedImage image = terrainLayer();
        if (image == null) {
            TerrainTileRenderer.draw(g, variant, images, layout.cell, layout.x, layout.y);
            return;
        }
        g.drawImage(image, Math.round(layout.x), Math.round(layout.y),
                Math.max(1, Math.round(layout.width)),
                Math.max(1, Math.round(layout.height)), null);
    }

    private BufferedImage staticPatrolScene(Layout layout) {
        if (variant == null || layout == null || getWidth() <= 0 || getHeight() <= 0)
            return null;
        if (cachedStaticScene != null
                && cachedStaticVariant == variant
                && cachedStaticTiles == tiles
                && cachedStaticWidth == getWidth()
                && cachedStaticHeight == getHeight()
                && close(cachedStaticLayoutX, layout.x)
                && close(cachedStaticLayoutY, layout.y)
                && close(cachedStaticCell, layout.cell))
            return cachedStaticScene;
        BufferedImage scene = new BufferedImage(getWidth(), getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D layer = scene.createGraphics();
        try {
            layer.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            layer.setColor(new Color(91, 151, 195));
            layer.fillRect(0, 0, getWidth(), getHeight());
            drawBackground(layer);
            drawTrees(layer, layout);
            drawProps(layer, layout);
            drawTerrainLayer(layer, layout);
            drawManualDecorations(layer, layout);
        } finally {
            layer.dispose();
        }
        cachedStaticScene = scene;
        cachedStaticVariant = variant;
        cachedStaticTiles = tiles;
        cachedStaticWidth = getWidth();
        cachedStaticHeight = getHeight();
        cachedStaticLayoutX = layout.x;
        cachedStaticLayoutY = layout.y;
        cachedStaticCell = layout.cell;
        return scene;
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= .01f;
    }

    private BufferedImage terrainLayer() {
        if (variant == null || variant.width <= 0 || variant.height <= 0) return null;
        int sourcePx = previewSourceTilePx();
        if (cachedTerrain != null && cachedTerrainVariant == variant
                && cachedTerrainTiles == tiles && cachedTerrainTilePx == sourcePx)
            return cachedTerrain;
        BufferedImage image = new BufferedImage(
                Math.max(1, variant.width * sourcePx),
                Math.max(1, variant.height * sourcePx), BufferedImage.TYPE_INT_ARGB);
        Graphics2D layer = image.createGraphics();
        try {
            layer.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            TerrainTileRenderer.draw(layer, variant, images, sourcePx, 0f, 0f);
        } finally {
            layer.dispose();
        }
        CustomMapChunkWriter.maskPatrolPlatforms(image, variant, sourcePx, 0f, 0f);
        cachedTerrain = image;
        cachedTerrainVariant = variant;
        cachedTerrainTiles = tiles;
        cachedTerrainTilePx = sourcePx;
        patrolSprites.clear();
        return image;
    }

    private void drawMovingPatrolPlatforms(Graphics2D g, Layout layout) {
        if (variant == null || variant.secondaryPlatforms == null
                || !hasEnabledPatrol()) return;
        long tick = patrolPreviewTick();
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || platform.patrol == null || !platform.patrol.enabled) continue;
            BufferedImage sprite = patrolPlatformSprite(platform);
            if (sprite == null) continue;
            MovingPlatformEngine.Pose pose =
                    MovingPlatformEngine.poseAtTick(variant, platform, tick);
            int left = Math.round(layout.x
                    + (pose.centerTileX - platformWidth(platform) * .5f) * layout.cell);
            int top = screenSupportTileY(layout, pose.supportTileY);
            int width = Math.max(1, Math.round(platformWidth(platform) * layout.cell));
            int height = Math.max(1, Math.round(layout.cell));
            g.drawImage(sprite, left, top, width, height, null);
        }
    }

    private void drawPatrolIslands(Graphics2D g, Layout layout) {
        if (variant.secondaryPlatforms == null || variant.secondaryPlatforms.isEmpty()) {
            g.setColor(new Color(0, 0, 0, 155));
            g.fillRoundRect(278, 8, 250, 26, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString("No floating island in this variant", 288, 26);
            return;
        }
        long tick = patrolPreviewTick();
        Stroke oldStroke = g.getStroke();
        Composite oldComposite = g.getComposite();
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null) continue;
            PlatformPatrol patrol = ensurePatrol(platform);
            boolean selected = platform == selectedPlatform;
            String problem = patrolValidationMessage(platform);
            Color color = !problem.isEmpty() ? new Color(245, 70, 70)
                    : patrol.enabled ? new Color(55, 230, 180)
                    : new Color(180, 120, 255);

            if (patrol.enabled || selected) {
                int ax = screenTileX(layout, patrol.ax);
                int ay = screenSupportTileY(layout, patrol.ay);
                int bx = screenTileX(layout, patrol.bx);
                int by = screenSupportTileY(layout, patrol.by);
                BufferedImage pathSprite = patrolPlatformSprite(platform);
                AlphaBounds pathBounds = alphaBounds(pathSprite);
                float bodyCenter = .5f;
                float bodyHeight = 1f;
                if (pathBounds != null) {
                    float sourceHeight = Math.max(1f, pathSprite.getHeight());
                    bodyCenter = (pathBounds.top + pathBounds.bottom + 1f)
                            * .5f / sourceHeight;
                    bodyHeight = (pathBounds.bottom - pathBounds.top + 1f)
                            / sourceHeight;
                }
                float corridor = Math.max(3f, bodyHeight * layout.cell);
                int bodyCenterOffset = Math.round(bodyCenter * layout.cell);
                g.setComposite(AlphaComposite.SrcOver.derive(selected ? .18f : .09f));
                g.setStroke(new BasicStroke(corridor, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_ROUND));
                g.setColor(color);
                g.drawLine(ax, ay + bodyCenterOffset,
                        bx, by + bodyCenterOffset);
                g.setComposite(oldComposite);
                g.setStroke(new BasicStroke(selected ? 2.5f : 1.4f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(ax, ay, bx, by);
                drawArrowHead(g, ax, ay, bx, by);
            }
            if (selected) drawPatrolEndpointGhosts(g, layout, platform, patrol);

            float centerX;
            float supportY;
            if (patrol.enabled) {
                MovingPlatformEngine.Pose pose =
                        MovingPlatformEngine.poseAtTick(variant, platform, tick);
                centerX = pose.centerTileX;
                supportY = pose.supportTileY;
            } else {
                centerX = originalCenterTileX(platform);
                supportY = originalSupportTileY(platform);
            }
            int left = Math.round(layout.x
                    + (centerX - platformWidth(platform) * .5f) * layout.cell);
            int top = screenSupportTileY(layout, supportY);
            int width = Math.max(2, Math.round(platformWidth(platform) * layout.cell));
            int height = Math.max(2, Math.round(layout.cell));
            BufferedImage sprite = patrolPlatformSprite(platform);
            Shape outline = islandOutline(sprite, left, top, width, height);
            g.setComposite(AlphaComposite.SrcOver.derive(patrol.enabled ? .38f : .15f));
            g.setColor(color);
            if (outline == null) g.fillRoundRect(left, top, width, height, 5, 5);
            else g.fill(outline);
            g.setComposite(oldComposite);
            g.setStroke(new BasicStroke(selected ? 3f : 1.2f));
            if (outline == null) g.drawRoundRect(left, top, width, height, 5, 5);
            else {
                g.draw(outline);
                drawAlphaTopSupport(g, sprite, left, top, width, height, color);
            }
            if (selected) {
                drawPatrolHandle(g, layout, patrol.ax, patrol.ay,
                        "A", draggingPatrolHandle == 1, color);
                drawPatrolHandle(g, layout, patrol.bx, patrol.by,
                        "B", draggingPatrolHandle == 2, color);
                g.setColor(new Color(0, 0, 0, 175));
                String label = patrol.enabled ? "Patrol " + safePlatformId(platform)
                        : "Static " + safePlatformId(platform);
                int labelWidth = g.getFontMetrics().stringWidth(label) + 10;
                g.fillRoundRect(left + Math.max(0, (width - labelWidth) / 2),
                        top - 20, labelWidth, 17, 6, 6);
                g.setColor(Color.WHITE);
                g.drawString(label, left + Math.max(0, (width - labelWidth) / 2) + 5,
                        top - 7);
            }
        }
        g.setStroke(oldStroke);
        g.setComposite(oldComposite);
    }

    private void drawPatrolEndpointGhosts(Graphics2D g, Layout layout,
                                          SecondaryPlatform platform,
                                          PlatformPatrol patrol) {
        BufferedImage sprite = patrolPlatformSprite(platform);
        if (sprite == null) return;
        boolean same = Math.abs(patrol.ax - patrol.bx)
                <= MovingPlatformEngine.POSITION_EPSILON_TILES
                && Math.abs(patrol.ay - patrol.by)
                <= MovingPlatformEngine.POSITION_EPSILON_TILES;
        float currentX;
        float currentY;
        if (patrol.enabled) {
            MovingPlatformEngine.Pose pose = MovingPlatformEngine.poseAtTick(
                    variant, platform, patrolPreviewTick());
            currentX = pose.centerTileX;
            currentY = pose.supportTileY;
        } else {
            currentX = originalCenterTileX(platform);
            currentY = originalSupportTileY(platform);
        }
        drawPatrolEndpointGhost(g, layout, platform, sprite,
                patrol.ax, patrol.ay, same ? "A/B" : "A", new Color(80, 245, 155),
                !samePoint(patrol.ax, patrol.ay, currentX, currentY));
        if (!same) drawPatrolEndpointGhost(g, layout, platform, sprite,
                patrol.bx, patrol.by, "B", new Color(255, 190, 65),
                !samePoint(patrol.bx, patrol.by, currentX, currentY));
    }

    private void drawPatrolEndpointGhost(Graphics2D g, Layout layout,
                                         SecondaryPlatform platform,
                                         BufferedImage sprite, float centerX,
                                         float supportY, String label, Color color,
                                         boolean drawSprite) {
        int width = Math.max(2, Math.round(platformWidth(platform) * layout.cell));
        int height = Math.max(2, Math.round(width
                * sprite.getHeight() / (float) Math.max(1, sprite.getWidth())));
        int left = Math.round(layout.x
                + (centerX - platformWidth(platform) * .5f) * layout.cell);
        int top = screenSupportTileY(layout, supportY);
        boolean active = (label.equals("A") && draggingPatrolHandle == 1)
                || (label.equals("B") && draggingPatrolHandle == 2)
                || (label.equals("A/B") && draggingPatrolHandle != 0);
        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        if (drawSprite) {
            g.setComposite(AlphaComposite.SrcOver.derive(active ? .72f : .46f));
            g.drawImage(sprite, left, top, width, height, null);
        }
        g.setComposite(oldComposite);
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        Shape outline = islandOutline(sprite, left, top, width, height);
        if (outline == null) g.drawRoundRect(left, top, width, height, 5, 5);
        else {
            g.draw(outline);
            drawAlphaTopSupport(g, sprite, left, top, width, height, color);
        }
        String coordinates = String.format(java.util.Locale.ROOT,
                "%s (%.3f, %.3f)", label, centerX, supportY);
        int labelWidth = g.getFontMetrics().stringWidth(coordinates) + 10;
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRoundRect(left + 3, top + 3, labelWidth, 18, 6, 6);
        g.setColor(Color.WHITE);
        g.drawString(coordinates, left + 8, top + 17);
        g.setStroke(oldStroke);
    }

    private boolean patrolEndpointGhostAt(int mouseX, int mouseY, Layout layout) {
        if (selectedPlatform == null || variant == null) return false;
        PlatformPatrol patrol = ensurePatrol(selectedPlatform);
        return endpointGhostContains(mouseX, mouseY, layout, selectedPlatform,
                patrol.ax, patrol.ay)
                || endpointGhostContains(mouseX, mouseY, layout, selectedPlatform,
                patrol.bx, patrol.by);
    }

    private boolean endpointGhostContains(int mouseX, int mouseY,
                                          Layout layout,
                                          SecondaryPlatform platform,
                                          float centerX, float supportY) {
        BufferedImage sprite = patrolPlatformSprite(platform);
        int left = Math.round(layout.x
                + (centerX - platformWidth(platform) * .5f) * layout.cell);
        int top = screenSupportTileY(layout, supportY);
        int width = Math.max(2, Math.round(platformWidth(platform) * layout.cell));
        int height = Math.max(2, Math.round(layout.cell));
        return alphaContains(sprite, mouseX, mouseY,
                left, top, width, height, 5);
    }

    private static boolean samePoint(float firstX, float firstY,
                                     float secondX, float secondY) {
        return Math.abs(firstX - secondX)
                <= MovingPlatformEngine.POSITION_EPSILON_TILES
                && Math.abs(firstY - secondY)
                <= MovingPlatformEngine.POSITION_EPSILON_TILES;
    }

    private BufferedImage patrolPlatformSprite(SecondaryPlatform platform) {
        if (platform == null || variant == null) return null;
        BufferedImage sprite = patrolSprites.get(platform);
        if (sprite == null) {
            sprite = CustomMapChunkWriter.renderPatrolPlatform(variant, platform,
                    previewSourceTilePx(), images);
            if (sprite != null) patrolSprites.put(platform, sprite);
        }
        return sprite;
    }

    private int previewSourceTilePx() {
        int sourcePx = tiles == null || tiles.tilePixels <= 0 ? 64 : tiles.tilePixels;
        sourcePx = Math.max(8, Math.min(192, sourcePx));
        return Math.max(4, Math.min(sourcePx,
                8192 / Math.max(1, variant == null ? 1 : variant.width)));
    }

    private static void drawPatrolHandle(Graphics2D g, Layout layout, float tileX,
                                         float supportTileY, String label,
                                         boolean active, Color color) {
        int x = screenTileX(layout, tileX);
        int y = screenSupportTileY(layout, supportTileY);
        int radius = active ? 9 : 7;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillOval(x - radius - 2, y - radius - 2,
                (radius + 2) * 2, (radius + 2) * 2);
        g.setColor(color);
        g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        g.setColor(Color.BLACK);
        g.drawString(label, x - 4, y + 5);
    }

    private SecondaryPlatform patrolPlatformAt(int mouseX, int mouseY, Layout layout) {
        if (variant == null || variant.secondaryPlatforms == null) return null;
        long tick = patrolPreviewTick();
        for (int i = variant.secondaryPlatforms.size() - 1; i >= 0; i--) {
            SecondaryPlatform platform = variant.secondaryPlatforms.get(i);
            if (platform == null) continue;
            PlatformPatrol patrol = ensurePatrol(platform);
            float centerX;
            float supportY;
            if (patrol.enabled) {
                MovingPlatformEngine.Pose pose =
                        MovingPlatformEngine.poseAtTick(variant, platform, tick);
                centerX = pose.centerTileX;
                supportY = pose.supportTileY;
            } else {
                centerX = originalCenterTileX(platform);
                supportY = originalSupportTileY(platform);
            }
            int left = Math.round(layout.x
                    + (centerX - platformWidth(platform) * .5f) * layout.cell);
            int top = screenSupportTileY(layout, supportY);
            int width = Math.max(2,
                    Math.round(platformWidth(platform) * layout.cell));
            int height = Math.max(2, Math.round(layout.cell));
            if (alphaContains(patrolPlatformSprite(platform), mouseX, mouseY,
                    left, top, width, height, 5)) return platform;
        }
        return null;
    }

    private Shape islandOutline(BufferedImage sprite, int left, int top,
                                int width, int height) {
        if (sprite == null || width <= 0 || height <= 0) return null;
        Path2D.Float source = patrolSpriteOutlines.get(sprite);
        if (source == null) {
            source = alphaEnvelope(sprite);
            patrolSpriteOutlines.put(sprite, source);
        }
        AffineTransform transform = new AffineTransform();
        transform.translate(left, top);
        transform.scale(width / (double) Math.max(1, sprite.getWidth()),
                height / (double) Math.max(1, sprite.getHeight()));
        return transform.createTransformedShape(source);
    }

    static Path2D.Float alphaEnvelope(BufferedImage sprite) {
        Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
        if (sprite == null) return path;
        int width = sprite.getWidth();
        int[] top = new int[width];
        int[] bottom = new int[width];
        java.util.Arrays.fill(top, -1);
        java.util.Arrays.fill(bottom, -1);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < sprite.getHeight(); y++) {
                if (((sprite.getRGB(x, y) >>> 24) & 255) < 24) continue;
                if (top[x] < 0) top[x] = y;
                bottom[x] = y;
            }
        }
        int first = 0;
        while (first < width && top[first] < 0) first++;
        if (first >= width) return path;
        int last = width - 1;
        while (last >= first && top[last] < 0) last--;
        path.moveTo(first, top[first]);
        for (int x = first + 1; x <= last; x++)
            if (top[x] >= 0) path.lineTo(x, top[x]);
        for (int x = last; x >= first; x--)
            if (bottom[x] >= 0) path.lineTo(x, bottom[x] + 1f);
        path.closePath();
        return path;
    }

    private static boolean alphaContains(BufferedImage sprite,
                                         int mouseX, int mouseY,
                                         int left, int top,
                                         int width, int height,
                                         int tolerancePixels) {
        if (sprite == null || width <= 0 || height <= 0) return false;
        float sx = (mouseX - left) * sprite.getWidth() / (float) width;
        float sy = (mouseY - top) * sprite.getHeight() / (float) height;
        int radiusX = Math.max(1, Math.round(tolerancePixels
                * sprite.getWidth() / (float) width));
        int radiusY = Math.max(1, Math.round(tolerancePixels
                * sprite.getHeight() / (float) height));
        int cx = Math.round(sx), cy = Math.round(sy);
        for (int y = Math.max(0, cy - radiusY);
             y <= Math.min(sprite.getHeight() - 1, cy + radiusY); y++)
            for (int x = Math.max(0, cx - radiusX);
                 x <= Math.min(sprite.getWidth() - 1, cx + radiusX); x++)
                if (((sprite.getRGB(x, y) >>> 24) & 255) >= 24) return true;
        return false;
    }

    private static void drawAlphaTopSupport(Graphics2D g, BufferedImage sprite,
                                            int left, int top,
                                            int width, int height, Color color) {
        AlphaBounds bounds = alphaBounds(sprite);
        if (bounds == null) return;
        int x1 = left + Math.round(bounds.left * width
                / (float) Math.max(1, sprite.getWidth()));
        int x2 = left + Math.round((bounds.right + 1) * width
                / (float) Math.max(1, sprite.getWidth()));
        int y = top + Math.round(bounds.top * height
                / (float) Math.max(1, sprite.getHeight()));
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.setColor(color.brighter());
        g.drawLine(x1, y, x2, y);
        g.setStroke(old);
    }

    private static AlphaBounds alphaBounds(BufferedImage sprite) {
        if (sprite == null) return null;
        int left = sprite.getWidth(), right = -1, top = sprite.getHeight();
        int bottom = -1;
        for (int y = 0; y < sprite.getHeight(); y++)
            for (int x = 0; x < sprite.getWidth(); x++)
                if (((sprite.getRGB(x, y) >>> 24) & 255) >= 24) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
        return right < left ? null : new AlphaBounds(left, right, top, bottom);
    }

    private static final class AlphaBounds {
        final int left;
        final int right;
        final int top;
        final int bottom;

        AlphaBounds(int left, int right, int top, int bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private int patrolHandleAt(int mouseX, int mouseY, Layout layout) {
        if (selectedPlatform == null) return 0;
        PlatformPatrol patrol = ensurePatrol(selectedPlatform);

        if (Math.abs(patrol.ax - patrol.bx)
                <= MovingPlatformEngine.POSITION_EPSILON_TILES
                && Math.abs(patrol.ay - patrol.by)
                <= MovingPlatformEngine.POSITION_EPSILON_TILES
                && near(mouseX, mouseY, screenTileX(layout, patrol.bx),
                screenSupportTileY(layout, patrol.by), 13)) return 2;
        if (near(mouseX, mouseY, screenTileX(layout, patrol.ax),
                screenSupportTileY(layout, patrol.ay), 13)) return 1;
        if (near(mouseX, mouseY, screenTileX(layout, patrol.bx),
                screenSupportTileY(layout, patrol.by), 13)) return 2;
        return 0;
    }

    private static boolean near(int x, int y, int targetX, int targetY, int radius) {
        long dx = x - targetX, dy = y - targetY;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void selectPatrolPlatform(SecondaryPlatform platform, boolean notify) {
        if (platform != null && (variant == null || variant.secondaryPlatforms == null
                || !variant.secondaryPlatforms.contains(platform))) return;
        selectedPlatform = platform;
        draggingPatrolHandle = 0;
        if (platform != null) ensurePatrol(platform);
        if (notify && patrolListener != null)
            patrolListener.selectionChanged(variant, platform);
        repaint();
    }

    private PlatformPatrol ensurePatrol(SecondaryPlatform platform) {
        if (platform.patrol == null) platform.patrol = new PlatformPatrol();
        MovingPlatformEngine.normalize(variant, platform);
        return platform.patrol;
    }

    String patrolValidationMessage(SecondaryPlatform platform) {
        if (variant == null || platform == null || platform.patrol == null
                || !platform.patrol.enabled) return "";
        refreshPatrolValidationCache();
        String message = patrolValidationCache.get(platform);
        return message == null ? "" : message;
    }

    private void refreshPatrolValidationCache() {
        long signature = patrolValidationSignature();
        if (signature == patrolValidationSignature) return;
        patrolValidationSignature = signature;
        patrolValidationCache.clear();
        if (variant == null || variant.secondaryPlatforms == null) return;
        for (SecondaryPlatform candidate : variant.secondaryPlatforms) {
            if (candidate == null || candidate.patrol == null
                    || !candidate.patrol.enabled) continue;
            patrolValidationCache.put(candidate,
                    MovingPlatformValidator.firstBlockingMessage(variant, candidate));
        }
    }

    private long patrolValidationSignature() {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, System.identityHashCode(variant));
        if (variant == null) return hash;
        hash = mix(hash, anchorSignature(variant.spawn));
        hash = mix(hash, anchorSignature(variant.destination));
        if (variant.secondaryPlatforms != null)
            for (SecondaryPlatform candidate : variant.secondaryPlatforms) {
                if (candidate == null) { hash = mix(hash, 0); continue; }
                hash = mix(hash, candidate.startX);
                hash = mix(hash, candidate.endX);
                hash = mix(hash, Float.floatToIntBits(candidate.supportLayer));
                hash = mix(hash, candidate.collisionMode == null
                        ? 0 : candidate.collisionMode.hashCode());
                hash = mix(hash, candidate.collisionLeftInsetPermille);
                hash = mix(hash, candidate.collisionRightInsetPermille);
                hash = mix(hash, candidate.collisionTopOffsetPermille);
                hash = mix(hash, candidate.collisionBottomInsetPermille);
                PlatformPatrol patrol = candidate.patrol;
                if (patrol == null) { hash = mix(hash, 0); continue; }
                hash = mix(hash, patrol.enabled ? 1 : 0);
                hash = mix(hash, Float.floatToIntBits(patrol.ax));
                hash = mix(hash, Float.floatToIntBits(patrol.ay));
                hash = mix(hash, Float.floatToIntBits(patrol.bx));
                hash = mix(hash, Float.floatToIntBits(patrol.by));
                hash = mix(hash, Float.floatToIntBits(patrol.speedTilesPerSecond));
                hash = mix(hash, Float.floatToIntBits(patrol.durationSeconds));
                hash = mix(hash, Float.floatToIntBits(patrol.dwellSeconds));
            }
        return hash;
    }

    private static int anchorSignature(MapAnchor anchor) {
        return anchor == null ? 0 : 31 * anchor.x + anchor.y;
    }

    private static long mix(long hash, int value) {
        return (hash ^ (value & 0xffffffffL)) * 0x100000001b3L;
    }

    private String sweptCollisionMessage(SecondaryPlatform platform) {
        PlatformPatrol patrol = platform.patrol;
        float dx = patrol.bx - patrol.ax;
        float dy = patrol.by - patrol.ay;
        int samples = Math.max(1, (int) Math.ceil(
                Math.max(Math.abs(dx), Math.abs(dy)) * 8f));
        for (int sample = 0; sample <= samples; sample++) {
            float t = sample / (float) samples;
            float centerX = patrol.ax + dx * t;
            float supportY = patrol.ay + dy * t;
            String issue = collisionAt(platform, centerX, supportY);
            if (!issue.isEmpty()) return issue;
        }
        if (variant.secondaryPlatforms != null)
            for (SecondaryPlatform other : variant.secondaryPlatforms) {
                if (other == null || other == platform || other.patrol == null
                        || !other.patrol.enabled) continue;
                if (sweptBoundsOverlap(platform, other))
                    return "Its swept path overlaps moving island "
                            + safePlatformId(other) + ".";
            }
        return "";
    }

    private String collisionAt(SecondaryPlatform platform, float centerX,
                               float supportY) {
        float half = platformWidth(platform) * .5f;
        float left = centerX - half;
        float right = centerX + half;
        float bodyBottom = supportY - 1f;
        float bodyTop = supportY;
        int ownRow = Math.max(0, Math.min(variant.height - 1, Math.round(
                variant.height + platform.supportLayer
                        / Math.max(1f, variant.layerUnitsPerTile()))));
        int startX = Math.max(0, (int) Math.floor(left + .001f));
        int endX = Math.min(variant.width - 1,
                (int) Math.ceil(right - .001f) - 1);
        for (int x = startX; x <= endX; x++) {
            for (int row = 0; row < variant.height; row++) {
                int cell = variant.cell(x, row);
                float cellTop = variant.height - row;
                float cellBottom = cellTop - 1f;
                if (cell != CustomMapDocument.CELL_GROUND) continue;
                if (row == ownRow && x >= platform.startX && x <= platform.endX)
                    continue;
                boolean cutsBody = intervalsOverlap(bodyBottom, bodyTop,
                        cellBottom, cellTop, .02f);
                boolean lacksHeadroom = intervalsOverlap(bodyTop, bodyTop + 2f,
                        cellBottom, cellTop, .02f);
                if (cutsBody) return "The island body crosses terrain or another island.";
                if (lacksHeadroom) return "The route needs two clear tiles above the island.";
            }
        }
        if (variant.baseSafeZones != null)
            for (CustomMapDocument.BaseSafeZone zone : variant.baseSafeZones) {
                if (zone == null || !("player".equals(zone.role)
                        || "enemy".equals(zone.role))
                        || right <= zone.centerX - .5f
                        || left >= zone.centerX + 1.5f)
                    continue;
                float groundY = -zone.supportLayer
                        / Math.max(1f, variant.layerUnitsPerTile());
                if (intervalsOverlap(bodyBottom, bodyTop + 2f,
                        groundY, groundY + 2f, .02f))
                    return "The patrol crosses the " + zone.role + " base position.";
            }
        return "";
    }

    private static boolean sweptBoundsOverlap(SecondaryPlatform first,
                                              SecondaryPlatform second) {
        PlatformPatrol a = first.patrol, b = second.patrol;
        float aHalf = platformWidth(first) * .5f;
        float bHalf = platformWidth(second) * .5f;
        float aLeft = Math.min(a.ax, a.bx) - aHalf;
        float aRight = Math.max(a.ax, a.bx) + aHalf;
        float bLeft = Math.min(b.ax, b.bx) - bHalf;
        float bRight = Math.max(b.ax, b.bx) + bHalf;
        float aBottom = Math.min(a.ay, a.by) - 1f;
        float aTop = Math.max(a.ay, a.by) + 2f;
        float bBottom = Math.min(b.ay, b.by) - 1f;
        float bTop = Math.max(b.ay, b.by) + 2f;
        return intervalsOverlap(aLeft, aRight, bLeft, bRight, .02f)
                && intervalsOverlap(aBottom, aTop, bBottom, bTop, .02f);
    }

    private static boolean intervalsOverlap(float a0, float a1,
                                            float b0, float b1, float gap) {
        return Math.min(a1, b1) - Math.max(a0, b0) > gap;
    }

    private boolean hasEnabledPatrol() {
        if (variant == null || variant.secondaryPlatforms == null) return false;
        for (SecondaryPlatform platform : variant.secondaryPlatforms)
            if (platform != null && platform.patrol != null && platform.patrol.enabled)
                return true;
        return false;
    }

    private long patrolPreviewTick() {
        long elapsed = Math.max(0L, System.nanoTime() - patrolAnimationEpochNanos);
        return elapsed / 33333333L;
    }

    private static SecondaryPlatform firstPlatform(ModeVariant variant) {
        return variant == null || variant.secondaryPlatforms == null
                || variant.secondaryPlatforms.isEmpty()
                ? null : variant.secondaryPlatforms.get(0);
    }

    private static float originalCenterTileX(SecondaryPlatform platform) {
        return (platform.startX + platform.endX + 1f) * .5f;
    }

    private float originalSupportTileY(SecondaryPlatform platform) {
        return -platform.supportLayer / Math.max(1f, variant.layerUnitsPerTile());
    }

    private static float platformWidth(SecondaryPlatform platform) {
        return Math.max(1f, platform.endX - platform.startX + 1f);
    }

    private static String safePlatformId(SecondaryPlatform platform) {
        return platform.id == null || platform.id.trim().isEmpty()
                ? (platform.startX + "-" + platform.endX) : platform.id;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static int screenTileX(Layout layout, float tileX) {
        return Math.round(layout.x + tileX * layout.cell);
    }

    private static int screenSupportTileY(Layout layout, float supportTileY) {
        return Math.round(layout.y + (layout.height / layout.cell - supportTileY)
                * layout.cell);
    }

    private void drawBackground(Graphics2D g) {
        if (document != null
                && document.backgroundRevision == CustomMapDocument.BACKGROUND_REVISION
                && document.backgroundManifest != null) {
            drawComposedBackground(g);
        } else drawLegacyBackground(g);
        drawManualBackground(g);
    }

    private void drawManualBackground(Graphics2D g) {
        if (variant == null) return;
        if (variant.manualBackground != null)
            for (CustomMapDocument.BackgroundLayer layer : variant.manualBackground) {
                if (layer == null) continue;
                BufferedImage image = paletteImage(layer.asset);
                if (image == null) continue;
                float scale = Math.max(getWidth() / (float) Math.max(1, image.getWidth()),
                        getHeight() / (float) Math.max(1, image.getHeight()));
                int width = Math.max(1, Math.round(image.getWidth() * scale));
                int height = Math.max(1, Math.round(image.getHeight() * scale));
                g.drawImage(image, (getWidth() - width) / 2, getHeight() - height,
                        width, height, null);
            }
        if (variant.manualEffects != null) {
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive(.55f));
            for (CustomMapDocument.ManualEffect effect : variant.manualEffects) {
                if (effect == null) continue;
                BufferedImage image = paletteImage(effect.asset);
                if (image == null) continue;
                int width = Math.min(getWidth(), Math.max(1, image.getWidth()));
                int height = Math.min(getHeight(), Math.max(1, image.getHeight()));
                g.drawImage(image, (getWidth() - width) / 2,
                        (getHeight() - height) / 2, width, height, null);
            }
            g.setComposite(old);
        }
    }

    private void drawManualDecorations(Graphics2D g, Layout layout) {
        if (variant == null || variant.manualDecorations == null) return;
        for (int index = 0; index < variant.manualDecorations.size(); index++) {
            CustomMapDocument.ManualDecoration decoration =
                    variant.manualDecorations.get(index);
            if (decoration == null) continue;
            BufferedImage image = paletteImage(decoration.asset);
            if (image == null) continue;
            float maxHeight = layout.cell * 3.2f * Math.max(.2f, decoration.scale);
            float maxWidth = layout.cell * 2.2f * Math.max(.2f, decoration.scale);
            float scale = Math.min(maxWidth / Math.max(1f, image.getWidth()),
                    maxHeight / Math.max(1f, image.getHeight()));
            int width = Math.max(1, Math.round(image.getWidth() * scale));
            int height = Math.max(1, Math.round(image.getHeight() * scale));
            float drawX = index == draggingDecoration && decorationDragX >= 0
                    ? decorationDragX + .5f : decoration.x;
            float layer = index == draggingDecoration && decorationDragX >= 0
                    && variant.surface != null && variant.surface[decorationDragX] >= 0
                    ? variant.walkLayerAtTile(decorationDragX) : decoration.anchorLayer;
            int center = Math.round(layout.x + drawX * layout.cell);
            int feet = screenLayerY(layout, layer);
            g.drawImage(image, center - width / 2, feet - height,
                    width, height, null);
        }
    }

    private int manualDecorationAt(MouseEvent event, Layout layout) {
        if (variant == null || variant.manualDecorations == null) return -1;
        for (int index = variant.manualDecorations.size() - 1; index >= 0; index--) {
            CustomMapDocument.ManualDecoration decoration =
                    variant.manualDecorations.get(index);
            if (decoration == null) continue;
            int center = Math.round(layout.x + decoration.x * layout.cell);
            int feet = screenLayerY(layout, decoration.anchorLayer);
            int halfWidth = Math.max(6, Math.round(layout.cell * 1.1f));
            int height = Math.max(10, Math.round(layout.cell * 3.2f));
            if (event.getX() >= center - halfWidth
                    && event.getX() <= center + halfWidth
                    && event.getY() >= feet - height && event.getY() <= feet + 4)
                return index;
        }
        return -1;
    }

    private BufferedImage paletteImage(String id) {
        if (id == null || id.isEmpty()) return null;
        if (paletteImages.containsKey(id)) return paletteImages.get(id);
        BufferedImage image = null;
        int slash = id.indexOf('/');
        if (slash > 0 && slash + 1 < id.length()) {
            String theme = id.substring(0, slash);
            String relative = id.substring(slash + 1);
            try {
                TileCatalog.TileSet set = TileCatalog.find(theme);
                if (set != null && set.root != null) {
                    File file = new File(set.root,
                            relative.replace('/', File.separatorChar)).getCanonicalFile();
                    if (file.toPath().startsWith(set.root.getCanonicalFile().toPath()))
                        image = ImageIO.read(file);
                }
            } catch (Throwable ignored) {}
            if (image == null && document != null && document.uuid != null) {
                InputStream in = null;
                try {
                    in = CustomMapRepository.stream("custom_maps/" + document.uuid
                            + "/assets/palette/" + safePaletteName(theme)
                            + "/selected/" + relative);
                    if (in != null) image = ImageIO.read(in);
                } catch (Throwable ignored) {
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignored) {}
                }
            }
        }
        paletteImages.put(id, image);
        return image;
    }

    private static String safePaletteName(String value) {
        if (value == null || value.trim().isEmpty()) return "default";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void drawComposedBackground(Graphics2D g) {
        List<BackgroundLayoutEngine.DrawCommand> commands = BackgroundLayoutEngine.layout(
                document.backgroundManifest, getWidth(), getHeight(), 0f);
        Composite old = g.getComposite();
        for (BackgroundLayoutEngine.DrawCommand command : commands) {
            BufferedImage image = backgroundImage(command.asset);
            if (image == null && "base".equals(command.asset.role)) {
                GradientPaint gradient = new GradientPaint(0f, 0f,
                        new Color(document.backgroundManifest.skyTopArgb, true),
                        0f, getHeight(),
                        new Color(document.backgroundManifest.skyBottomArgb, true));
                g.setPaint(gradient);
                g.fillRect(0, 0, getWidth(), getHeight());
                continue;
            }
            if (image == null) continue;
            if (command.alpha < 255)
                g.setComposite(AlphaComposite.SrcOver.derive(command.alpha / 255f));
            g.drawImage(image, Math.round(command.x), Math.round(command.y),
                    Math.max(1, Math.round(command.width)),
                    Math.max(1, Math.round(command.height)), null);
            g.setComposite(old);
        }
        g.setComposite(old);
    }

    private BufferedImage backgroundImage(CustomMapDocument.BackgroundAssetRef ref) {
        if (ref == null) return null;

        if (ref.asset != null && !ref.asset.isEmpty() && document != null
                && document.uuid != null && !document.uuid.isEmpty()) {
            String key = document.uuid + "/" + ref.asset;
            if (embeddedBackgrounds.containsKey(key))
                return embeddedBackgrounds.get(key);
            InputStream in = null;
            try {
                in = CustomMapRepository.stream(
                        "custom_maps/" + document.uuid + "/" + ref.asset);
                BufferedImage loaded = in == null ? null : ImageIO.read(in);
                if (loaded != null) {
                    embeddedBackgrounds.put(key, loaded);
                    return loaded;
                }
            } catch (Throwable ignored) {

            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) {}
            }
        }
        BufferedImage source = images.backgrounds.get(ref.sourceKey);
        if (source == null || !"unpack-595x239".equals(ref.sourceTransform)) return source;
        String key = "source-transform/" + ref.sourceKey;
        if (embeddedBackgrounds.containsKey(key)) return embeddedBackgrounds.get(key);
        if ((long) ref.width * ref.height != (long) source.getWidth() * source.getHeight())
            return source;
        BufferedImage rotated = new BufferedImage(ref.width, ref.height,
                BufferedImage.TYPE_INT_ARGB);
        int packedWidth = source.getWidth();
        for (int y = 0; y < ref.height; y++) for (int x = 0; x < ref.width; x++) {
            int index = y * ref.width + x;
            rotated.setRGB(x, y, source.getRGB(index % packedWidth, index / packedWidth));
        }
        embeddedBackgrounds.put(key, rotated);
        return rotated;
    }

    private void drawLegacyBackground(Graphics2D g) {
        if (images.backgrounds.isEmpty()) return;
        int i = 0;
        for (BufferedImage image : images.backgrounds.values()) {
            if (i == 0) {
                float scale = Math.max(getWidth() / (float) image.getWidth(),
                        getHeight() / (float) image.getHeight());
                int w = Math.round(image.getWidth() * scale), h = Math.round(image.getHeight() * scale);
                g.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } else {
                float scale = Math.min(getWidth() / (float) image.getWidth(),
                        getHeight() * 0.55f / image.getHeight());
                int w = Math.max(1, Math.round(image.getWidth() * scale));
                int h = Math.max(1, Math.round(image.getHeight() * scale));
                for (int x = 0; x < getWidth(); x += w) g.drawImage(image, x, getHeight() - h, w, h, null);
            }
            i++;
        }
    }

    private void drawTrees(Graphics2D g, Layout layout) {
        for (TreePlacement placement : variant.trees) {
            float centerX = layout.x + (placement.x + 0.5f
                    + placement.xOffsetPercent / 100f) * layout.cell;

            float bottom = layout.y + (placement.y + treeRootContactRatio(variant)) * layout.cell;
            if (!trees.isEmpty()) {
                BufferedImage image = trees.get(Math.floorMod(placement.asset, trees.size()));
                float unit = Math.max(1f, tiles == null ? image.getWidth() : tiles.tilePixels);
                float scale = treeAssetScale(unit, image.getWidth(), image.getHeight(),
                        placement.scalePercent);
                scale *= treeFitScale(variant, placement, layout.cell,
                        image.getWidth() * scale * layout.cell / unit);
                float w = image.getWidth() * scale * layout.cell / unit;
                float h = image.getHeight() * scale * layout.cell / unit;
                centerX = layout.x + treeCenterX(variant, placement, layout.cell, w);
                float opaqueBottom = alphaContentBottom(image) * scale * layout.cell / unit;
                g.drawImage(image, Math.round(centerX - w / 2f), Math.round(bottom - opaqueBottom),
                        Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), null);
            } else {
                g.setColor(new Color(45, 105, 56));
                g.fillOval(Math.round(centerX - layout.cell / 2f), Math.round(bottom - layout.cell * 2f),
                        Math.max(2, Math.round(layout.cell)), Math.max(3, Math.round(layout.cell * 2f)));
            }
        }
    }

    private void drawProps(Graphics2D g, Layout layout) {
        if (variant == null || variant.props == null || variant.props.isEmpty()
                || document == null || document.propManifest == null) return;
        for (CustomMapDocument.PropPlacement placement : variant.props) {
            if (placement == null || placement.assetId == null) continue;
            CustomMapDocument.PropAssetRef ref = document.propManifest.find(placement.assetId);
            if (ref == null || !ref.decorative || !"NONE".equals(ref.collision)
                    || !"NONE".equals(ref.interaction)) continue;
            BufferedImage image = propImage(ref);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) continue;
            float unit = Math.max(1f, tiles == null || tiles.tilePixels <= 0
                    ? image.getWidth() : tiles.tilePixels);
            float fit = Math.min(Math.max(.25f, ref.maxWidthTiles) * unit / image.getWidth(),
                    Math.max(.25f, ref.maxHeightTiles) * unit / image.getHeight());
            float variation = placement.scalePercent <= 0 ? 1f : placement.scalePercent / 100f;
            float scale = fit * variation;
            float w = image.getWidth() * scale * layout.cell / unit;
            float h = image.getHeight() * scale * layout.cell / unit;
            float centerX = layout.x + (placement.x + .5f
                    + placement.xOffsetPercent / 100f) * layout.cell;
            float bottom = layout.y + (placement.y + treeRootContactRatio(variant)) * layout.cell;
            float opaqueBottom = alphaContentBottom(image) * scale * layout.cell / unit;
            g.drawImage(image, Math.round(centerX - w * .5f), Math.round(bottom - opaqueBottom),
                    Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), null);
        }
    }

    private BufferedImage propImage(CustomMapDocument.PropAssetRef ref) {
        if (ref == null) return null;
        if (ref.asset != null && !ref.asset.isEmpty() && document != null
                && document.uuid != null && !document.uuid.isEmpty()) {
            String key = document.uuid + "/" + ref.asset;
            if (embeddedProps.containsKey(key)) return embeddedProps.get(key);
            InputStream in = null;
            try {
                in = CustomMapRepository.stream("custom_maps/" + document.uuid + "/" + ref.asset);
                BufferedImage loaded = in == null ? null : ImageIO.read(in);
                embeddedProps.put(key, loaded);
                return loaded;
            } catch (Throwable ignored) {
                embeddedProps.put(key, null);
                return null;
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) {}
            }
        }
        return images.props.get(ref.id);
    }

    private static int alphaContentBottom(BufferedImage image) {
        for (int y = image.getHeight() - 1; y >= 0; y--)
            for (int x = 0; x < image.getWidth(); x++)
                if (((image.getRGB(x, y) >>> 24) & 0xff) >= 16) return y + 1;
        return image.getHeight();
    }

    static final float TREE_ROOT_HALF_RATIO = .45f;

    static float treeCenterX(ModeVariant variant, TreePlacement placement,
                             float tilePixels, float drawnWidth) {
        float raw = (placement.x + .5f + placement.xOffsetPercent / 100f) * tilePixels;
        if (variant == null || placement == null) return raw;
        float half = Math.max(0f, drawnWidth) * TREE_ROOT_HALF_RATIO;
        int left = treeShelfStart(variant, placement);
        int right = treeShelfEnd(variant, placement);
        float minimum = left * tilePixels + half;
        float maximum = (right + 1) * tilePixels - half;
        if (minimum > maximum) return (left + right + 1) * .5f * tilePixels;
        return Math.max(minimum, Math.min(maximum, raw));
    }

    static float treeFitScale(ModeVariant variant, TreePlacement placement,
                              float tilePixels, float drawnWidth) {
        if (variant == null || placement == null || drawnWidth <= 0f) return 1f;
        float span = (treeShelfEnd(variant, placement)
                - treeShelfStart(variant, placement) + 1) * tilePixels;
        float root = drawnWidth * TREE_ROOT_HALF_RATIO * 2f;
        if (root <= span || root <= 0f) return 1f;
        return Math.max(.55f, span / root);
    }

    private static int treeShelfStart(ModeVariant variant, TreePlacement placement) {
        int left = placement.x;
        while (left - 1 >= 0 && treeGroundAt(variant, left - 1, placement.y)) left--;
        return left;
    }

    private static int treeShelfEnd(ModeVariant variant, TreePlacement placement) {
        int right = placement.x;
        while (right + 1 < variant.width
                && treeGroundAt(variant, right + 1, placement.y)) right++;
        return right;
    }

    static boolean treeGroundAt(ModeVariant variant, int x, int row) {
        if (variant == null || x < 0 || x >= variant.width) return false;
        if (variant.surface != null && x < variant.surface.length
                && variant.surface[x] >= 0
                && (variant.water == null || x >= variant.water.length
                || !variant.water[x])
                && variant.surface[x] <= row) return true;
        return variant.cell(x, row) == CustomMapDocument.CELL_GROUND;
    }

    static float treeRootContactRatio(ModeVariant variant) {
        float inset = variant == null || variant.profile == null
                ? 0f : variant.profile.surfaceInsetRatio;
        if (Float.isNaN(inset) || Float.isInfinite(inset)) inset = 0f;

        return Math.max(0f, Math.min(.25f, inset)) + .015f;
    }

    static float treeAssetScale(float tilePixels, int assetWidth, int assetHeight,
                                int scalePercent) {
        float unit = Math.max(1f, tilePixels);
        float scale = Math.min(1f, Math.min(
                unit * 2f / Math.max(1, assetWidth),
                unit * 3.5f / Math.max(1, assetHeight)));
        float variation = scalePercent <= 0 ? 1f : scalePercent / 100f;
        return Math.min(1.15f, scale * variation);
    }

    private static void drawAnchor(Graphics2D g, MapAnchor anchor, Layout layout,
                                   Color color, String label) {
        if (anchor == null) return;
        int x = Math.round(layout.x + (anchor.x + 0.5f) * layout.cell);
        int y = Math.round(layout.y + (anchor.y + 1f) * layout.cell);
        int r = Math.max(5, Math.round(layout.cell * 0.7f));
        g.setColor(new Color(0, 0, 0, 170));
        g.fillOval(x - r - 2, y - r - 2, r * 2 + 4, r * 2 + 4);
        g.setColor(color);
        g.fillOval(x - r, y - r, r * 2, r * 2);
        g.setColor(Color.BLACK);
        g.drawString(label, x - 4, y + 5);
    }

    private void drawGrid(Graphics2D g, Layout layout) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 255, 255, 62));
        for (int x = 0; x <= variant.width; x++) {
            int px = Math.round(layout.x + x * layout.cell);
            g.drawLine(px, Math.round(layout.y), px,
                    Math.round(layout.y + layout.height));
        }
        for (int y = 0; y <= variant.height; y++) {
            int py = Math.round(layout.y + y * layout.cell);
            g.drawLine(Math.round(layout.x), py,
                    Math.round(layout.x + layout.width), py);
        }
        g.setStroke(old);
    }

    private void drawHeightfield(Graphics2D g, Layout layout) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(Math.max(2f, layout.cell * .10f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 45, 205, 225));
        for (int x = 0; x + 1 < variant.width; x++) {
            if (variant.surface[x] < 0 || variant.surface[x + 1] < 0
                    || variant.water[x] || variant.water[x + 1]) continue;
            int x1 = screenX(layout, x);
            int x2 = screenX(layout, x + 1);
            int y1 = screenLayerY(layout, variant.walkLayerAtTile(x));
            int y2 = screenLayerY(layout, variant.walkLayerAtTile(x + 1));
            if (variant.isContinuousSurfaceBetween(x, x + 1))
                g.drawLine(x1, y1, x2, y2);
            else {
                g.fillOval(x1 - 3, y1 - 3, 6, 6);
                g.fillOval(x2 - 3, y2 - 3, 6, 6);
            }
        }
        g.setStroke(old);
    }

    private void drawNavigation(Graphics2D g, Layout layout) {
        if (variant.navigationLinks == null) return;
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(Math.max(1.5f, layout.cell * .07f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (CustomMapDocument.NavigationLink link : variant.navigationLinks) {
            if (link == null || link.type == null) continue;
            int x1 = screenX(layout, link.fromX);
            int y1 = screenLayerY(layout, link.fromLayer) - 3;
            int x2 = screenX(layout, link.toX);
            int y2 = screenLayerY(layout, link.toLayer) - 3;
            g.setColor(navigationColor(link.type));
            if (link.type == CustomMapDocument.NavigationType.JUMP
                    || link.type == CustomMapDocument.NavigationType.SWIM) {
                int lift = Math.max(8, Math.round(Math.abs(x2 - x1) * .22f));
                java.awt.geom.QuadCurve2D.Float curve =
                        new java.awt.geom.QuadCurve2D.Float(
                                x1, y1, (x1 + x2) / 2f,
                                Math.min(y1, y2) - lift, x2, y2);
                g.draw(curve);
            } else g.drawLine(x1, y1, x2, y2);
            drawArrowHead(g, x1, y1, x2, y2);
        }
        g.setStroke(old);
    }

    private static Color navigationColor(CustomMapDocument.NavigationType type) {
        if (type == CustomMapDocument.NavigationType.JUMP)
            return new Color(255, 190, 50, 220);
        if (type == CustomMapDocument.NavigationType.SWIM)
            return new Color(45, 215, 255, 220);
        if (type == CustomMapDocument.NavigationType.STEP_UP)
            return new Color(245, 80, 150, 225);
        if (type == CustomMapDocument.NavigationType.DROP_DOWN)
            return new Color(185, 100, 255, 225);
        return new Color(80, 255, 125, 190);
    }

    private static void drawArrowHead(Graphics2D g, int x1, int y1, int x2, int y2) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int size = 5;
        int ax = x2 - (int) Math.round(Math.cos(angle - .55) * size);
        int ay = y2 - (int) Math.round(Math.sin(angle - .55) * size);
        int bx = x2 - (int) Math.round(Math.cos(angle + .55) * size);
        int by = y2 - (int) Math.round(Math.sin(angle + .55) * size);
        g.drawLine(x2, y2, ax, ay);
        g.drawLine(x2, y2, bx, by);
    }

    private void drawSlopePhases(Graphics2D g, Layout layout) {
        for (int x = 0; x < variant.width; x++) {
            if (variant.slopeDirection == null || variant.slopeDirection[x] == 0
                    || variant.surface[x] < 0) continue;
            int y = variant.surface[x];
            int left = Math.round(layout.x + x * layout.cell);
            int top = Math.round(layout.y + y * layout.cell);
            int size = Math.max(1, Math.round(layout.cell));
            g.setColor(variant.slopeDirection[x] < 0
                    ? new Color(90, 110, 255, 105)
                    : new Color(255, 115, 65, 105));
            g.fillRect(left, top, size, size);
            if (layout.cell >= 18f) {
                g.setColor(Color.WHITE);
                String value = (variant.slopeDirection[x] < 0 ? "U" : "D")
                        + variant.slopePhase[x];
                g.drawString(value, left + 2, top + Math.min(size - 2, 13));
            }
        }
    }

    private void drawZones(Graphics2D g, Layout layout) {
        for (int x = 0; x < variant.width; x++) {
            if (!variant.water[x]) continue;
            int left = Math.round(layout.x + x * layout.cell);
            int width = Math.max(1, Math.round(layout.cell));
            g.setColor(new Color(40, 205, 255, 58));
            g.fillRect(left, Math.round(layout.y), width, Math.round(layout.height));
        }
        if (variant.baseSafeZones != null)
            for (CustomMapDocument.BaseSafeZone zone : variant.baseSafeZones) {
                if (zone == null) continue;
                int left = Math.round(layout.x + zone.startX * layout.cell);
                int right = Math.round(layout.x + (zone.endX + 1) * layout.cell);
                int y = screenLayerY(layout, zone.supportLayer);
                g.setColor("enemy".equals(zone.role)
                        ? new Color(255, 110, 90, 165)
                        : new Color(80, 245, 155, 165));
                g.fillRect(left, y - 5, Math.max(1, right - left), 5);
                g.drawRect(left, y - Math.max(8, Math.round(layout.cell)),
                        Math.max(1, right - left), Math.max(8, Math.round(layout.cell)));
            }
        if (variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform : variant.secondaryPlatforms) {
                if (platform == null) continue;
                int left = Math.round(layout.x + platform.startX * layout.cell);
                int right = Math.round(layout.x + (platform.endX + 1) * layout.cell);
                int y = screenLayerY(layout, platform.supportLayer);
                g.setColor(new Color(185, 95, 255, 210));
                g.drawLine(left, y, right, y);
            }
        if (variant.enemies != null)
            for (CustomMapDocument.EnemyPlacement enemy : variant.enemies) {
                if (enemy == null) continue;
                int x = screenX(layout, enemy.x);
                int y = Math.round(layout.y + (enemy.y + 1f) * layout.cell);
                int radius = Math.max(3, Math.round(layout.cell * .22f));
                g.setColor(enemy.boss
                        ? new Color(255, 60, 60, 230)
                        : new Color(255, 170, 55, 220));
                g.fillOval(x - radius, y - radius * 2, radius * 2, radius * 2);
            }
        for (TreePlacement tree : variant.trees) {
            int left = Math.round(layout.x + (tree.x - .45f
                    + tree.xOffsetPercent / 100f) * layout.cell);
            int bottom = Math.round(layout.y + tree.y * layout.cell);
            int width = Math.max(3, Math.round(layout.cell * .9f));
            int height = Math.max(5, Math.round(layout.cell * 2.5f));
            g.setColor(new Color(255, 150, 40, 175));
            g.drawRect(left, bottom - height, width, height);
        }
    }

    private void drawTileRoles(Graphics2D g, Layout layout) {
        for (int y = 0; y < variant.height; y++) for (int x = 0; x < variant.width; x++) {
            int cell = variant.cell(x, y);
            if (cell == CustomMapDocument.CELL_AIR) continue;
            String role = tileRole(x, y, cell);
            int left = Math.round(layout.x + x * layout.cell);
            int top = Math.round(layout.y + y * layout.cell);
            int size = Math.max(1, Math.round(layout.cell));
            g.setColor(roleColor(role));
            if (layout.cell < 16f) {
                g.fillRect(left + Math.max(0, size / 3), top + Math.max(0, size / 3),
                        Math.max(1, size / 3), Math.max(1, size / 3));
            } else {
                g.fillRoundRect(left + 1, top + 1, Math.max(2, size - 2),
                        Math.max(2, Math.min(size - 2, 16)), 4, 4);
                g.setColor(Color.WHITE);
                g.drawString(role, left + 3, top + Math.min(size - 2, 13));
            }
        }
    }

    private String tileRole(int x, int y, int cell) {
        if (cell == CustomMapDocument.CELL_WATER)
            return variant.cell(x, y - 1) == CustomMapDocument.CELL_WATER ? "WB" : "WT";
        if (variant.slopeDirection != null && variant.slopeDirection[x] != 0
                && variant.surface[x] == y) return "SL";
        boolean floating = variant.surface[x] < 0 || y < variant.surface[x];
        if (floating && variant.cell(x, y - 1) != CustomMapDocument.CELL_GROUND)
            return "PL";
        boolean up = variant.cell(x, y - 1) == CustomMapDocument.CELL_GROUND;
        boolean down = variant.cell(x, y + 1) == CustomMapDocument.CELL_GROUND;
        boolean left = variant.cell(x - 1, y) == CustomMapDocument.CELL_GROUND;
        boolean right = variant.cell(x + 1, y) == CustomMapDocument.CELL_GROUND;
        if (!up && !left) return "TL";
        if (!up && !right) return "TR";
        if (!up) return "TOP";
        if (!left) return "L";
        if (!right) return "R";
        if (!down) return "B";
        return "F";
    }

    private static Color roleColor(String role) {
        if (role.startsWith("W")) return new Color(0, 135, 220, 175);
        if ("SL".equals(role)) return new Color(235, 75, 175, 180);
        if ("PL".equals(role)) return new Color(160, 85, 225, 180);
        if ("F".equals(role)) return new Color(70, 70, 70, 145);
        return new Color(30, 155, 70, 170);
    }

    private void drawUnitScale(Graphics2D g, Layout layout) {
        List<ReferenceSpot> spots = referenceSpots();
        if (spots.isEmpty()) return;
        List<BufferedImage> sprites = UnitReferenceCatalog.images();
        float[] heights = {0.85f, 1.35f, 2.10f};
        for (int index = 0; index < spots.size(); index++) {
            ReferenceSpot spot = spots.get(index);
            BufferedImage image = sprites.get(index % sprites.size());
            float targetHeight = layout.cell * heights[index % heights.length];
            float scale = Math.min(targetHeight / Math.max(1f, image.getHeight()),
                    targetHeight * 1.55f / Math.max(1f, image.getWidth()));
            int width = Math.max(2, Math.round(image.getWidth() * scale));
            int height = Math.max(3, Math.round(image.getHeight() * scale));
            int centerX = screenX(layout, spot.x);
            int feetY = screenLayerY(layout, spot.supportLayer);
            g.drawImage(image, centerX - width / 2, feetY - height, width, height, null);
            g.setColor(new Color(0, 0, 0, 175));
            int labelWidth = g.getFontMetrics().stringWidth(spot.label) + 6;
            g.fillRoundRect(centerX - labelWidth / 2, feetY - height - 16,
                    labelWidth, 14, 5, 5);
            g.setColor(Color.WHITE);
            g.drawString(spot.label, centerX - labelWidth / 2 + 3,
                    feetY - height - 5);
            g.setColor(new Color(255, 255, 255, 180));
            g.drawLine(centerX + width / 2 + 3, feetY - height,
                    centerX + width / 2 + 3, feetY);
        }
    }

    private List<ReferenceSpot> referenceSpots() {
        ArrayList<ReferenceSpot> spots = new ArrayList<ReferenceSpot>();
        for (int x = 0; x < variant.width && spots.size() < 5; x++) {
            if (variant.slopeDirection != null && variant.slopeDirection[x] != 0) {
                addReferenceSpot(spots, x, variant.walkLayerAtTile(x), "Slope");
                while (x + 1 < variant.width && variant.slopeDirection[x + 1] != 0) x++;
            }
        }
        for (int x = 0; x + 1 < variant.width && spots.size() < 5; x++) {
            if (variant.surface[x] >= 0 && variant.surface[x + 1] >= 0
                    && !variant.water[x] && !variant.water[x + 1]
                    && !variant.isContinuousSurfaceBetween(x, x + 1)) {
                int high = variant.walkLayerAtTile(x) < variant.walkLayerAtTile(x + 1)
                        ? x : x + 1;
                addReferenceSpot(spots, high, variant.walkLayerAtTile(high), "Step");
            }
        }
        for (int x = 0; x < variant.width && spots.size() < 5; x++) {
            if (!variant.water[x]) continue;
            int bank = x > 0 && variant.surface[x - 1] >= 0 ? x - 1 : -1;
            if (bank >= 0)
                addReferenceSpot(spots, bank, variant.walkLayerAtTile(bank), "River bank");
            while (x + 1 < variant.width && variant.water[x + 1]) x++;
        }
        if (variant.secondaryPlatforms != null)
            for (CustomMapDocument.SecondaryPlatform platform : variant.secondaryPlatforms) {
                if (spots.size() >= 5) break;
                addReferenceSpot(spots, (platform.startX + platform.endX) / 2,
                        platform.supportLayer, "Island");
            }
        if (variant.spawn != null)
            addReferenceSpot(spots, variant.spawn.x,
                    variant.walkLayerAtTile(variant.spawn.x), "Flat");
        return spots;
    }

    private static void addReferenceSpot(List<ReferenceSpot> spots, int x,
                                         float supportLayer, String label) {
        if (Float.isNaN(supportLayer)) return;
        for (ReferenceSpot spot : spots) if (Math.abs(spot.x - x) < 4) return;
        spots.add(new ReferenceSpot(x, supportLayer, label));
    }

    private int screenX(Layout layout, int tileX) {
        return Math.round(layout.x + (tileX + .5f) * layout.cell);
    }

    private int screenLayerY(Layout layout, float layer) {
        float row = variant.height + layer / Math.max(1f, variant.layerUnitsPerTile());
        return Math.round(layout.y + row * layout.cell);
    }

    private static List<BufferedImage> read(List<File> files) {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        if (files != null) for (File file : files) {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image != null) out.add(image);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static List<BufferedImage> readIce(
            TileCatalog.TileSet set, List<File> topologySources) {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        if (set == null || topologySources == null) return out;
        for (File source : topologySources) {
            String key = CustomMapDocument.IceSurfaceManifest.tileKey(
                    source == null ? "" : source.getName());
            TileCatalog.IceSurfaceAsset asset = set.iceSurfaceAssets.get(key);
            if (asset == null || asset.base == null) continue;
            try {
                BufferedImage image = ImageIO.read(asset.base);
                if (image != null) out.add(image);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static PreviewAssets assets(TileCatalog.TileSet set) {
        if (set == null) return new PreviewAssets();
        synchronized (CACHE) {
            PreviewAssets found = CACHE.get(set);
            if (found != null) return found;
            found = new PreviewAssets();
            found.assetRevision = assetRevision(set);
            found.ground = read(set.ground);
            found.groundSurface = read(set.groundSurface);
            found.groundFill = read(set.groundFill);
            found.groundLeft = read(set.groundLeft);
            found.groundRight = read(set.groundRight);
            found.groundBottom = read(set.groundBottom);
            found.groundTopLeft = read(set.groundTopLeft);
            found.groundTopRight = read(set.groundTopRight);
            found.groundBottomLeft = read(set.groundBottomLeft);
            found.groundBottomRight = read(set.groundBottomRight);
            found.groundInnerTopLeft = read(set.groundInnerTopLeft);
            found.groundInnerTopRight = read(set.groundInnerTopRight);
            found.groundInnerBottomLeft = read(set.groundInnerBottomLeft);
            found.groundInnerBottomRight = read(set.groundInnerBottomRight);
            found.groundPlatformCenter = read(set.groundPlatformCenter);
            found.groundPlatformLeft = read(set.groundPlatformLeft);
            found.groundPlatformRight = read(set.groundPlatformRight);
            found.groundPlatformSingle = read(set.groundPlatformSingle);
            found.groundSlopeUp = read(set.groundSlopeUp);
            found.groundSlopeDown = read(set.groundSlopeDown);
            found.groundSteepSlopeUp = read(set.groundSteepSlopeUp);
            found.groundSteepSlopeDown = read(set.groundSteepSlopeDown);
            found.groundSlopeUpSupport = read(set.groundSlopeUpSupport);
            found.groundSlopeDownSupport = read(set.groundSlopeDownSupport);
            found.groundSteepSlopeUpSupport = read(set.groundSteepSlopeUpSupport);
            found.groundSteepSlopeDownSupport = read(set.groundSteepSlopeDownSupport);
            found.groundSlopeUpEndpointSupport = read(set.groundSlopeUpEndpointSupport);
            found.groundSlopeDownEndpointSupport = read(set.groundSlopeDownEndpointSupport);
            found.groundSteepSlopeUpEndpointSupport =
                    read(set.groundSteepSlopeUpEndpointSupport);
            found.groundSteepSlopeDownEndpointSupport =
                    read(set.groundSteepSlopeDownEndpointSupport);
            found.groundStepJunctionLeft = read(set.groundStepJunctionLeft);
            found.groundStepJunctionRight = read(set.groundStepJunctionRight);
            found.iceSurfaceBase = readIce(set, set.groundSurface);
            found.iceSurfaceTopLeft = readIce(set, set.groundTopLeft);
            found.iceSurfaceTopRight = readIce(set, set.groundTopRight);
            found.iceSurfacePlatformCenter = readIce(set, set.groundPlatformCenter);
            found.iceSurfacePlatformLeft = readIce(set, set.groundPlatformLeft);
            found.iceSurfacePlatformRight = readIce(set, set.groundPlatformRight);
            found.iceSurfacePlatformSingle = readIce(set, set.groundPlatformSingle);
            found.iceSurfaceSlopeUp = readIce(set, set.groundSlopeUp);
            found.iceSurfaceSlopeDown = readIce(set, set.groundSlopeDown);
            found.iceSurfaceStepJunctionLeft = readIceByKey(
                    set, IceSurfaceTopologyResolver.STEP_JUNCTION_LEFT_KEY);
            found.iceSurfaceStepJunctionRight = readIceByKey(
                    set, IceSurfaceTopologyResolver.STEP_JUNCTION_RIGHT_KEY);
            found.blankMissingGroundInterior = set.strictGroundRoles;
            found.sealSlopeUnderlay = set.sealSlopeUnderlay;
            found.stackedSafeBandSlopes = set.stackedSafeBandSlopes;
            found.missingDiagonalInnerCorners = set.missingDiagonalInnerCorners;
            found.pixelLockedInnerCornerOverlays = set.pixelLockedInnerCornerOverlays;
            found.embeddedBankIceBridge = set.embeddedBankIceBridge;
            found.iceBridgeSocketInsetPixels = set.iceBridgeSocketInsetPixels;
            found.widthSpecificFloatingIslands = set.widthSpecificFloatingIslands;
            found.snowOnlyFloatingIslands = set.snowOnlyFloatingIslands;
            for (Map.Entry<Integer, File> entry : set.floatingIslandSpans.entrySet()) {
                try {
                    BufferedImage image = ImageIO.read(entry.getValue());
                    if (image != null) found.floatingIslandSpans.put(entry.getKey(), image);
                } catch (Throwable ignored) {}
            }
            for (Map.Entry<String, File> entry : set.innerCornerJunctions.entrySet()) {
                try {
                    BufferedImage image = ImageIO.read(entry.getValue());
                    if (image != null) found.innerCornerJunctions.put(entry.getKey(), image);
                } catch (Throwable ignored) {}
            }
            found.water = read(set.water);
            found.waterSurface = read(set.waterSurface);
            found.waterFill = read(set.waterFill);
            found.trees = read(set.trees);
            for (TileCatalog.PropAsset prop : set.props) {
                try {
                    BufferedImage image = ImageIO.read(prop.file);
                    if (image != null) found.props.put(prop.id, image);
                } catch (Throwable ignored) {}
            }
            for (TileCatalog.BackgroundAsset background : set.backgrounds) {
                try {
                    BufferedImage image = ImageIO.read(background.file);
                    if (image != null) found.backgrounds.put(background.sourceKey, image);
                } catch (Throwable ignored) {}
            }
            CACHE.put(set, found);
            return found;
        }
    }

    private static List<BufferedImage> readIceByKey(
            TileCatalog.TileSet set, String sourceKey) {
        ArrayList<BufferedImage> out = new ArrayList<BufferedImage>();
        if (set == null || sourceKey == null) return out;
        TileCatalog.IceSurfaceAsset asset = set.iceSurfaceAssets.get(
                CustomMapDocument.IceSurfaceManifest.tileKey(sourceKey));
        if (asset == null || !asset.isComplete()) return out;
        try {
            BufferedImage image = ImageIO.read(asset.base);
            if (image != null) out.add(image);
        } catch (Throwable ignored) {}
        return out;
    }

    private static long assetRevision(TileCatalog.TileSet set) {
        long revision = 0xcbf29ce484222325L;
        for (File file : runtimeAssetFiles(set)) {
            if (file == null) continue;
            revision ^= file.getAbsolutePath().hashCode();
            revision *= 0x100000001b3L;
            revision ^= file.lastModified();
            revision *= 0x100000001b3L;
            revision ^= file.length();
            revision *= 0x100000001b3L;
        }
        return revision;
    }

    private static List<File> runtimeAssetFiles(TileCatalog.TileSet set) {
        ArrayList<File> out = new ArrayList<File>();
        if (set == null) return out;
        out.addAll(set.ground);
        out.addAll(set.water);
        out.addAll(set.trees);
        out.addAll(set.innerCornerJunctions.values());
        out.addAll(set.floatingIslandSpans.values());
        for (TileCatalog.PropAsset prop : set.props)
            if (prop != null) out.add(prop.file);
        for (TileCatalog.BackgroundAsset background : set.backgrounds)
            if (background != null) out.add(background.file);
        for (TileCatalog.IceSurfaceAsset ice : set.iceSurfaceAssets.values()) {
            if (ice == null) continue;
            out.add(ice.base);
            out.add(ice.crack1);
            out.add(ice.crack2);
            out.add(ice.crack3);
        }
        for (List<File> files : set.vfxAssets.values())
            if (files != null) out.addAll(files);
        return out;
    }

    private Layout previewLayout() {
        float pad = 8f;
        float top = 42f;
        float availableW = Math.max(1f, getWidth() - pad * 2f);
        float availableH = Math.max(1f, getHeight() - top - pad);
        float heightLimit = availableH / Math.max(1, variant.height);
        float fit = Math.min(availableW / Math.max(1, variant.width), heightLimit);
        float cell = Math.max(1f, fit * zoom);
        float width = cell * variant.width;
        float height = cell * variant.height;
        float x = width <= availableW ? (getWidth() - width) * 0.5f
                : pad - (width - availableW) * pan;
        float y = height <= availableH ? getHeight() - pad - height
                : top - (height - availableH) * verticalPan;
        return new Layout(x, y,
                width, height, cell);
    }

    private static final class PreviewAssets extends TerrainTileRenderer.Assets {
        long assetRevision;
        Map<String, BufferedImage> backgrounds = new java.util.LinkedHashMap<String, BufferedImage>();
        Map<String, BufferedImage> props = new java.util.LinkedHashMap<String, BufferedImage>();
    }

    private static final class ReferenceSpot {
        final int x;
        final float supportLayer;
        final String label;

        ReferenceSpot(int x, float supportLayer, String label) {
            this.x = x;
            this.supportLayer = supportLayer;
            this.label = label;
        }
    }

    private static final class Layout {
        final float x, y, width, height, cell;
        Layout(float x, float y, float width, float height, float cell) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.cell = cell;
        }
    }
}
