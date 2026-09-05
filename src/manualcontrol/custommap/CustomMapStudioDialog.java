package manualcontrol.custommap;

import manualcontrol.Logger;
import manualcontrol.custommap.CustomMapDocument.MapMode;
import manualcontrol.custommap.CustomMapDocument.MapSpec;
import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.PlatformPatrol;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;
import manualcontrol.custommap.CustomMapDocument.TreePlacement;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

public final class CustomMapStudioDialog {

    private static final MapMode[] STUDIO_MODES = {
            MapMode.ADVENTURE, MapMode.HEIST, MapMode.DUEL
    };

    private final Object mainPage;
    private final JDialog dialog;
    private final JComboBox<CustomMapRepository.MapRecord> library = new JComboBox<CustomMapRepository.MapRecord>();
    private final JComboBox<TileCatalog.TileSet> biome = new JComboBox<TileCatalog.TileSet>();
    private final JTextField name = new JTextField("New Custom Map", 18);
    private final JSpinner seed = new JSpinner(new SpinnerNumberModel(1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L));
    private final JSpinner width = new JSpinner(new SpinnerNumberModel(75, 30, 120, 1));
    private final JSpinner height = new JSpinner(new SpinnerNumberModel(
            CustomMapGenerator.DEFAULT_MAP_HEIGHT,
            CustomMapGenerator.MIN_MAP_HEIGHT,
            CustomMapGenerator.MAX_MAP_HEIGHT, 1));
    private final JSpinner ground = new JSpinner(new SpinnerNumberModel(45.0, 8.0, 85.0, 1.0));
    private final JSpinner iceSurface = new JSpinner(new SpinnerNumberModel(20.0, 0.0, 70.0, 1.0));
    private final JSpinner iceBridge = new JSpinner(new SpinnerNumberModel(35.0, 0.0, 100.0, 1.0));
    private final JLabel liquidDensityLabel = new JLabel("Water %");
    private final JSpinner water = new JSpinner(new SpinnerNumberModel(12.0, 0.0, 100.0, 1.0));
    private final JSpinner trees = new JSpinner(new SpinnerNumberModel(20.0, 0.0, 100.0, 1.0));
    private final JSpinner props = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100.0, 1.0));
    private final JSpinner slopeMinY = new JSpinner(new SpinnerNumberModel(2, 2, 12, 1));
    private final JSpinner slopeMaxY = new JSpinner(new SpinnerNumberModel(10, 2, 12, 1));
    private final JSpinner slopeCount = new JSpinner(new SpinnerNumberModel(8, 0, 120, 1));
    private final JSpinner slopeCoverage = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 80.0, 1.0));
    private final JSpinner slopeMinRise = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));
    private final JSpinner slopeMaxRise = new JSpinner(new SpinnerNumberModel(4, 1, 10, 1));
    private final JSpinner slopeMinLength = new JSpinner(new SpinnerNumberModel(1, 1, 60, 1));
    private final JSpinner slopeMaxLength = new JSpinner(new SpinnerNumberModel(48, 1, 60, 1));
    private final JSpinner floatingIslands = new JSpinner(new SpinnerNumberModel(
            3, 0, CustomMapGenerator.MAX_FLOATING_ISLAND_COUNT, 1));
    private final JSpinner floatingIslandLayers = new JSpinner(new SpinnerNumberModel(
            2, 0, CustomMapGenerator.maxFloatingIslandLayers(
            CustomMapGenerator.DEFAULT_MAP_HEIGHT), 1));
    private final JSpinner complexity = new JSpinner(new SpinnerNumberModel(50, 0, 100, 5));
    private final JLabel complexityTier = new JLabel();
    private final JComboBox<String> difficulty = new JComboBox<String>(new String[]{"Easy", "Normal", "Hard"});
    private final JComboBox<CustomMapRandomizer.Preset> randomPreset =
            new JComboBox<CustomMapRandomizer.Preset>(CustomMapRandomizer.Preset.values());
    private final JSpinner adventureEnemies = new JSpinner(new SpinnerNumberModel(-1, -1, 30, 1));
    private final JSpinner heistEnemies = new JSpinner(new SpinnerNumberModel(-1, -1, 16, 1));
    private final JTextField enemyPool = new JTextField(18);
    private final Map<MapMode, JCheckBox> modeChecks = new EnumMap<MapMode, JCheckBox>(MapMode.class);
    private final Map<MapMode, CustomMapPreviewPanel> previews = new EnumMap<MapMode, CustomMapPreviewPanel>(MapMode.class);
    private final CustomMapPalettePanel palettePanel = new CustomMapPalettePanel();
    private final List<TileCatalog.TileSet> availableTiles =
            new ArrayList<TileCatalog.TileSet>();
    private final Map<String, EditHistory> editHistory =
            new LinkedHashMap<String, EditHistory>();
    private final JButton undoEdit = new JButton("Undo");
    private final JButton redoEdit = new JButton("Redo");
    private final JComboBox<String> brushSize =
            new JComboBox<String>(new String[]{"1x1", "2x2"});
    private final JComboBox<CustomMapTerrainEditor.SlopeMode> slopeMode =
            new JComboBox<CustomMapTerrainEditor.SlopeMode>(
                    CustomMapTerrainEditor.SlopeMode.values());
    private final JComboBox<CustomMapTerrainEditor.IceMode> iceMode =
            new JComboBox<CustomMapTerrainEditor.IceMode>(
                    CustomMapTerrainEditor.IceMode.values());
    private final JLabel slopeModeLabel = new JLabel("Slope style:");
    private final JLabel iceModeLabel = new JLabel("Ice action:");
    private final Map<CustomMapTerrainEditor.Tool, JToggleButton> toolButtons =
            new EnumMap<CustomMapTerrainEditor.Tool, JToggleButton>(
                    CustomMapTerrainEditor.Tool.class);
    private CustomMapTerrainEditor.Tool activeTool =
            CustomMapTerrainEditor.Tool.SELECT;
    private CustomMapPalette.Asset selectedPaletteAsset;

    private final CustomMapPreviewPanel battlePreview = new CustomMapPreviewPanel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JToggleButton placeAnchorMode = new JToggleButton("Place on Preview");
    private final JRadioButton placeSpawn = new JRadioButton("Place Spawn", true);
    private final JRadioButton placeGoal = new JRadioButton("Place Destination");
    private final JSpinner anchorX = new JSpinner(new SpinnerNumberModel(0, 0, 299, 1));
    private final JSpinner anchorY = new JSpinner(new SpinnerNumberModel(0, 0, 31, 1));
    private final JButton applyAnchor = new JButton("Apply + Snap");
    private final JComboBox<IslandChoice> patrolIsland = new JComboBox<IslandChoice>();
    private final JCheckBox patrolEnabled = new JCheckBox("Moving patrol");
    private final JSpinner patrolAX = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 120.0, 0.01));
    private final JSpinner patrolAY = new JSpinner(new SpinnerNumberModel(1.0, 1.0, 32.0, 0.01));
    private final JSpinner patrolBX = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 120.0, 0.01));
    private final JSpinner patrolBY = new JSpinner(new SpinnerNumberModel(1.0, 1.0, 32.0, 0.01));
    private final JSpinner patrolSpeed = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 4.0, 0.1));
    private final JSpinner patrolDuration = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 600.0, 0.1));
    private final JSpinner patrolDwell = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 30.0, 0.1));
    private final JLabel patrolIslandDetails = new JLabel(
            "Click a floating island in Preview, or choose one from the list.");
    private final JLabel patrolValidation = new JLabel("Select a floating island on the preview.");
    private final JTextArea status = new JTextArea(5, 42);
    private final Random random = new Random();

    private CustomMapDocument document;
    private boolean adjustingHeightControls;
    private boolean syncingPatrolControls;

    private SecondaryPlatform inspectedPatrolPlatform;

    private String unavailableSourceBiome;

    private CustomMapStudioDialog(Object mainPage) {
        this.mainPage = mainPage;
        Component parent = mainPage instanceof Component ? (Component) mainPage : null;
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        dialog = new JDialog(owner, "Custom Map Studio", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        buildUi();
        refreshTiles();
        refreshLibrary();
        dialog.setMinimumSize(new Dimension(760, 560));
        sizeToOwner(owner, parent);
    }

    public static void show(Object mainPage) {
        new CustomMapStudioDialog(mainPage).dialog.setVisible(true);
    }

    private void buildUi() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        top.add(new JLabel("Saved map:"));
        library.setPreferredSize(new Dimension(260, 28));
        top.add(library);
        JButton load = new JButton("Open");
        JButton fresh = new JButton("New");
        JButton delete = new JButton("Delete permanently");
        delete.setToolTipText("Permanently delete the selected map, stage, Background and embedded assets.");
        JButton refresh = new JButton("Refresh Tiles");
        top.add(load);
        top.add(fresh);
        top.add(delete);
        top.add(Box.createHorizontalStrut(12));
        top.add(refresh);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Generation settings"));
        int row = 0;
        add(form, row++, "Name", name);
        add(form, row++, "Biome", biome);
        add(form, row++, "Seed", seed);
        height.setToolTipText("Vertical grid size. Ground remains 2-12 tiles thick; extra rows provide airspace for terrain and floating islands.");
        addPair(form, row++, "Width (30-120)", width, "Height (8-32)", height);
        ground.setToolTipText("Ground fill inside the playable terrain band; additional sky rows do not dilute this percentage.");
        add(form, row++, "Ground %", ground);
        iceSurface.setToolTipText("Requested Ice coverage. Direct coating on Snow is capped at 20%; extra Ice is authored as eligible bridges.");
        add(form, row++, "Ice surface %", iceSurface);
        iceBridge.setToolTipText("Percentage of eligible chasm and water spans decked with a one-tile ice bridge.");
        add(form, row++, "Ice bridge %", iceBridge);
        add(form, row++, liquidDensityLabel, water);
        add(form, row++, "Trees %", trees);
        add(form, row++, "Props %", props);
        slopeMinY.setToolTipText("Lowest terrain elevation at which a generated slope may be placed.");
        slopeMaxY.setToolTipText("Highest terrain elevation at which a generated slope may be placed.");
        slopeCount.setToolTipText("Requested number of complete slopes. The generator returns the closest legal count when the map is too short or constrained.");
        slopeCoverage.setToolTipText("Requested share of dry main-route columns occupied by slopes (0-80%). 0% keeps legacy count-based generation; 30-80% is recommended for slope-heavy maps.");
        slopeMinRise.setToolTipText("Minimum vertical rise of one slope motif, in terrain tiles (1-10).");
        slopeMaxRise.setToolTipText("Maximum vertical rise of one slope motif, in terrain tiles (1-10).");
        String slopeLengthTip = "Horizontal slope length in terrain tiles (1-60). The generator snaps the requested length to the nearest complete phase supported by the theme tiles.";
        slopeMinLength.setToolTipText(slopeLengthTip);
        slopeMaxLength.setToolTipText(slopeLengthTip);
        floatingIslands.setToolTipText("Requested absolute island count. The generator reports the closest legal count if the map has insufficient width or airspace.");
        updateHeightControls();
        addPair(form, row++, "Terrain lowest Y", slopeMinY, "Highest Y", slopeMaxY);
        add(form, row++, "Slope count", slopeCount);
        add(form, row++, "Slope coverage %", slopeCoverage);
        addPair(form, row++, "Slope rise min", slopeMinRise, "Max", slopeMaxRise);
        addPair(form, row++, "Slope length min", slopeMinLength, "Max", slopeMaxLength);
        addPair(form, row++, "Floating island count", floatingIslands,
                "Island layers", floatingIslandLayers);
        JPanel complexityRow = new JPanel(new BorderLayout(8, 0));
        complexityRow.add(complexity, BorderLayout.WEST);
        complexityRow.add(complexityTier, BorderLayout.CENTER);
        add(form, row++, "Complexity (0-100)", complexityRow);
        complexity.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) { updateComplexityTierLabel(); }
        });
        seed.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) { updateBiomeControls(); }
        });
        height.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) { updateHeightControls(); }
        });
        slopeMinY.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int low = ((Number) slopeMinY.getValue()).intValue();
                if (((Number) slopeMaxY.getValue()).intValue() < low) slopeMaxY.setValue(low);
            }
        });
        slopeMaxY.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int high = ((Number) slopeMaxY.getValue()).intValue();
                if (((Number) slopeMinY.getValue()).intValue() > high) slopeMinY.setValue(high);
            }
        });
        slopeMinRise.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int low = ((Number) slopeMinRise.getValue()).intValue();
                if (((Number) slopeMaxRise.getValue()).intValue() < low)
                    slopeMaxRise.setValue(low);
            }
        });
        slopeMaxRise.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int high = ((Number) slopeMaxRise.getValue()).intValue();
                if (((Number) slopeMinRise.getValue()).intValue() > high)
                    slopeMinRise.setValue(high);
            }
        });
        slopeMinLength.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int low = ((Number) slopeMinLength.getValue()).intValue();
                if (((Number) slopeMaxLength.getValue()).intValue() < low)
                    slopeMaxLength.setValue(low);
            }
        });
        slopeMaxLength.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int high = ((Number) slopeMaxLength.getValue()).intValue();
                if (((Number) slopeMinLength.getValue()).intValue() > high)
                    slopeMinLength.setValue(high);
            }
        });
        floatingIslandLayers.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int layers = ((Number) floatingIslandLayers.getValue()).intValue();
                if (layers == 0) floatingIslands.setValue(0);
                else if (((Number) floatingIslands.getValue()).intValue() == 0)
                    floatingIslands.setValue(1);
                floatingIslands.setEnabled(layers > 0);
            }
        });
        floatingIslands.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int count = ((Number) floatingIslands.getValue()).intValue();
                int layers = ((Number) floatingIslandLayers.getValue()).intValue();
                if (count == 0 && layers != 0) floatingIslandLayers.setValue(0);
                else if (count > 0 && layers > count)
                    floatingIslandLayers.setValue(count);
            }
        });
        updateComplexityTierLabel();
        add(form, row++, "Difficulty", difficulty);
        add(form, row++, "Random preset", randomPreset);

        JPanel modes = new JPanel(new GridLayout(1, STUDIO_MODES.length, 8, 2));
        for (MapMode mode : STUDIO_MODES) {
            JCheckBox check = new JCheckBox(mode.title, false);
            check.setToolTipText("Bakes a separate terrain chunk set for "
                    + mode.title + ". Leave off unless you play this map in that mode.");
            modeChecks.put(mode, check);
            modes.add(check);
        }
        add(form, row++, "Variants", modes);

        JPanel advanced = new JPanel(new GridBagLayout());
        advanced.setBorder(BorderFactory.createTitledBorder("Advanced enemy (-1 = automatic)"));
        add(advanced, 0, "Adventure", adventureEnemies);
        add(advanced, 1, "Heist", heistEnemies);
        add(advanced, 2, "Enemy pool (pack:id, ...)", enemyPool);
        GridBagConstraints fill = new GridBagConstraints();
        fill.gridx = 0; fill.gridy = row++; fill.gridwidth = 2; fill.weightx = 1; fill.fill = GridBagConstraints.HORIZONTAL;
        fill.insets = new Insets(5, 3, 5, 3);
        form.add(advanced, fill);

        JButton generate = new JButton("Generate");
        JButton randomize = new JButton("Random");
        JButton regenerate = new JButton("Regenerate (seed + 1)");
        randomize.setToolTipText("Randomize preset parameters; keep the selected theme and Variants unchanged.");
        JPanel generationButtons = new JPanel(new GridLayout(1, 3, 7, 0));
        generationButtons.add(generate);
        generationButtons.add(randomize);
        generationButtons.add(regenerate);
        fill.gridy = row++; form.add(generationButtons, fill);

        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane statusScroll = new JScrollPane(status);
        statusScroll.setBorder(BorderFactory.createTitledBorder("Validation"));
        fill.gridy = row++; fill.weighty = 1; fill.fill = GridBagConstraints.BOTH;
        form.add(statusScroll, fill);

        battlePreview.setBattleAnchors(true);
        battlePreview.setAnchorListener(new CustomMapPreviewPanel.AnchorListener() {
            @Override public void changed(ModeVariant variant) { syncAnchorFields(variant); updateStatus(); }
        });
        installStrokeListener(battlePreview);
        installPatrolListener(battlePreview);
        tabs.addTab("BCU Stage", battlePreview);
        for (final MapMode mode : STUDIO_MODES) {
            CustomMapPreviewPanel preview = new CustomMapPreviewPanel();
            preview.setAnchorListener(new CustomMapPreviewPanel.AnchorListener() {
                @Override public void changed(ModeVariant variant) { syncAnchorFields(variant); updateStatus(); }
            });
            preview.setPatrolEditingAllowed(mode != MapMode.DUEL);
            installStrokeListener(preview);
            installPatrolListener(preview);
            previews.put(mode, preview);
            tabs.addTab(mode.title, preview);
        }

        ButtonGroup group = new ButtonGroup();
        group.add(placeSpawn); group.add(placeGoal);
        final JComboBox<String> zoom = new JComboBox<String>(
                new String[]{"1x (Fit)", "2x", "4x", "8x", "16x"});

        JPanel placementRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        placementRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        placeAnchorMode.setToolTipText("Enable this before changing a base or anchor. When disabled, drag empty Preview space to pan in both directions.");
        placementRow.add(placeAnchorMode);
        placementRow.add(placeSpawn);
        placementRow.add(placeGoal);
        placementRow.add(Box.createHorizontalStrut(8));
        placementRow.add(new JLabel("Zoom:"));
        placementRow.add(zoom);

        JPanel coordinateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        coordinateRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        coordinateRow.add(new JLabel("Tile X:"));
        coordinateRow.add(anchorX);
        coordinateRow.add(new JLabel("Tile Y:"));
        coordinateRow.add(anchorY);
        coordinateRow.add(applyAnchor);
        anchorX.setEnabled(false);
        anchorY.setEnabled(false);
        anchorY.setToolTipText("Y is derived from the eligible terrain surface and cannot be forced.");
        applyAnchor.setEnabled(false);

        JLabel hint = new JLabel("Placement OFF: left-drag empty space to pan. Palette tools: right-drag to pan. Placement ON: click to snap the selected base/anchor.");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(1, 7, 2, 4));

        JPanel overlayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        overlayRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        overlayRow.add(new JLabel("Overlays:"));
        addOverlayToggle(overlayRow, "Grid", CustomMapPreviewPanel.Overlay.GRID, false);
        addOverlayToggle(overlayRow, "Height", CustomMapPreviewPanel.Overlay.HEIGHTFIELD, false);
        addOverlayToggle(overlayRow, "Navigation", CustomMapPreviewPanel.Overlay.NAVIGATION, false);
        addOverlayToggle(overlayRow, "Slopes", CustomMapPreviewPanel.Overlay.SLOPES, false);
        addOverlayToggle(overlayRow, "Zones", CustomMapPreviewPanel.Overlay.ZONES, false);
        addOverlayToggle(overlayRow, "Tile roles", CustomMapPreviewPanel.Overlay.TILE_ROLES, false);
        addOverlayToggle(overlayRow, "Unit scale", CustomMapPreviewPanel.Overlay.UNIT_SCALE, true);
        addOverlayToggle(overlayRow, "Patrol islands",
                CustomMapPreviewPanel.Overlay.PATROL_ISLANDS, true);

        JPanel patrolPanel = buildPatrolPanel();

        JPanel anchorBar = new JPanel();
        anchorBar.setLayout(new BoxLayout(anchorBar, BoxLayout.Y_AXIS));
        anchorBar.add(placementRow);
        anchorBar.add(coordinateRow);
        anchorBar.add(overlayRow);
        anchorBar.add(patrolPanel);
        anchorBar.add(hint);

        JPanel previewArea = new JPanel(new BorderLayout(4, 4));
        previewArea.setBorder(BorderFactory.createTitledBorder(
                "Authoring preview (patrol timeline only; not playable)"));
        previewArea.add(tabs, BorderLayout.CENTER);
        previewArea.add(buildEditToolbar(), BorderLayout.NORTH);
        previewArea.add(anchorBar, BorderLayout.SOUTH);

        palettePanel.setListener(new CustomMapPalettePanel.Listener() {
            @Override public void selected(CustomMapPalette.Asset asset) {
                selectedPaletteAsset = asset;
                if (asset != null) selectToolFor(asset.category);
                applyEditorState();
            }
        });

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        int settingsWidth = Math.max(520, form.getPreferredSize().width + 32);
        int settingsMinimumWidth = Math.max(460, form.getMinimumSize().width + 24);
        formScroll.setPreferredSize(new Dimension(settingsWidth, 700));
        formScroll.setMinimumSize(new Dimension(settingsMinimumWidth, 420));
        JSplitPane previewAndPalette = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, previewArea, palettePanel);
        previewAndPalette.setBorder(BorderFactory.createEmptyBorder());
        previewAndPalette.setContinuousLayout(true);
        previewAndPalette.setOneTouchExpandable(true);
        previewAndPalette.setResizeWeight(1.0);
        previewAndPalette.setDividerLocation(760);
        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                formScroll, previewAndPalette);
        center.setBorder(BorderFactory.createEmptyBorder());
        center.setContinuousLayout(true);
        center.setOneTouchExpandable(true);
        center.setResizeWeight(0.38);
        center.setDividerLocation(settingsWidth);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 5));
        JButton save = new JButton("Save to Custom Map pack");
        JButton export = new JButton("Save + Export .bcuzip");
        JButton close = new JButton("Close");
        footer.add(save); footer.add(export); footer.add(close);

        dialog.getContentPane().setLayout(new BorderLayout(6, 6));
        dialog.getContentPane().add(top, BorderLayout.NORTH);
        dialog.getContentPane().add(center, BorderLayout.CENTER);
        dialog.getContentPane().add(footer, BorderLayout.SOUTH);

        load.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { loadSelected(); }});
        fresh.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { newDocument(); }});
        delete.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { deleteSelected(); }});
        refresh.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { refreshTiles(); }});
        biome.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateBiomeControls();
                updateStatus();
            }
        });
        generate.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { generate(false); }});
        randomize.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { randomizeAndGenerate(); }});
        regenerate.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { generate(true); }});
        save.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { save(false); }});
        export.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { save(true); }});
        close.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { dialog.dispose(); }});
        placeAnchorMode.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { updatePlacementMode(); }});
        placeSpawn.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { updatePlacementMode(); }});
        placeGoal.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { updatePlacementMode(); }});
        applyAnchor.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { applyAnchor(); }});
        zoom.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                int index = Math.max(0, Math.min(4, zoom.getSelectedIndex()));
                float scale = (float) (1 << index);
                battlePreview.setZoom(scale);
                for (CustomMapPreviewPanel preview : previews.values()) preview.setZoom(scale);
            }
        });
        tabs.addChangeListener(e -> {
            updateAnchorLabels();
            syncAnchorFields(currentVariant());
            refreshPatrolInspector();
            applyEditorState();
            updateHistoryButtons();
        });
        updateAnchorLabels();
        refreshPatrolInspector();
    }

    private JPanel buildEditToolbar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBorder(BorderFactory.createTitledBorder("Edit current tab"));
        JPanel tools = new JPanel(new GridLayout(2, 4, 4, 2));
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        tools.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.setAlignmentX(Component.LEFT_ALIGNMENT);
        ButtonGroup group = new ButtonGroup();
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.SELECT, "Select");
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.TERRAIN, "Terrain");
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.SLOPE, "Slope");
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.ICE, "Ice surface");
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.ISLAND, "Floating island");
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.DECORATION, "Decoration");
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.ENVIRONMENT, "Background/FX");
        addToolButton(tools, group, CustomMapTerrainEditor.Tool.ERASER, "Eraser");
        JToggleButton select = toolButtons.get(CustomMapTerrainEditor.Tool.SELECT);
        if (select != null) select.setSelected(true);
        options.add(new JLabel("Brush:"));
        brushSize.setToolTipText("2x2 uses the hovered tile as its top-left corner.");
        options.add(brushSize);
        options.add(slopeModeLabel);
        options.add(slopeMode);
        options.add(iceModeLabel);
        options.add(iceMode);
        undoEdit.setToolTipText("Undo one complete click/drag stroke.");
        redoEdit.setToolTipText("Redo one complete click/drag stroke.");
        options.add(undoEdit);
        options.add(redoEdit);
        bar.add(tools);
        bar.add(options);
        brushSize.addActionListener(e -> applyEditorState());
        slopeMode.addActionListener(e -> applyEditorState());
        iceMode.addActionListener(e -> applyEditorState());
        undoEdit.addActionListener(e -> undoEdit());
        redoEdit.addActionListener(e -> redoEdit());
        updateHistoryButtons();
        return bar;
    }

    private void addToolButton(JPanel bar, ButtonGroup group,
                               final CustomMapTerrainEditor.Tool tool,
                               String label) {
        final JToggleButton button = new JToggleButton(label);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 6, 2, 6));
        button.addActionListener(e -> {
            activeTool = tool;
            applyEditorState();
        });
        toolButtons.put(tool, button);
        group.add(button);
        bar.add(button);
    }

    private void selectToolFor(CustomMapPalette.Category category) {
        CustomMapTerrainEditor.Tool tool = category == CustomMapPalette.Category.SLOPE
                ? CustomMapTerrainEditor.Tool.SLOPE
                : category == CustomMapPalette.Category.ICE
                ? CustomMapTerrainEditor.Tool.ICE
                : category == CustomMapPalette.Category.ISLAND
                ? CustomMapTerrainEditor.Tool.ISLAND
                : category == CustomMapPalette.Category.TREE
                || category == CustomMapPalette.Category.PROP
                ? CustomMapTerrainEditor.Tool.DECORATION
                : category == CustomMapPalette.Category.BACKGROUND
                ? CustomMapTerrainEditor.Tool.ENVIRONMENT
                : CustomMapTerrainEditor.Tool.TERRAIN;
        activeTool = tool;
        JToggleButton button = toolButtons.get(tool);
        if (button != null) button.setSelected(true);
    }

    private void applyEditorState() {
        int size = brushSize.getSelectedIndex() == 1 ? 2 : 1;
        boolean steepAvailable = selectedSlopeSupportsSteep();
        if (!steepAvailable && slopeMode.getSelectedItem()
                == CustomMapTerrainEditor.SlopeMode.STEEP)
            slopeMode.setSelectedItem(CustomMapTerrainEditor.SlopeMode.AUTO);
        slopeMode.setToolTipText(steepAvailable
                ? "Auto prefers Gentle when both complete slope sets fit equally well."
                : "Steep is unavailable: this palette theme lacks a verified complete two-direction set.");
        CustomMapTerrainEditor.SlopeMode selectedSlope =
                (CustomMapTerrainEditor.SlopeMode) slopeMode.getSelectedItem();
        CustomMapTerrainEditor.IceMode selectedIce =
                (CustomMapTerrainEditor.IceMode) iceMode.getSelectedItem();
        battlePreview.setEditingTool(activeTool, selectedPaletteAsset, size,
                selectedSlope, selectedIce);
        for (CustomMapPreviewPanel preview : previews.values())
            preview.setEditingTool(activeTool, selectedPaletteAsset, size,
                    selectedSlope, selectedIce);
        boolean generated = currentVariant() != null;
        for (Map.Entry<CustomMapTerrainEditor.Tool, JToggleButton> entry
                : toolButtons.entrySet())
            entry.getValue().setEnabled(entry.getKey()
                    == CustomMapTerrainEditor.Tool.SELECT || generated);
        boolean endpointTool = activeTool == CustomMapTerrainEditor.Tool.SLOPE
                || activeTool == CustomMapTerrainEditor.Tool.ICE
                || activeTool == CustomMapTerrainEditor.Tool.ISLAND;
        brushSize.setEnabled(generated && activeTool != CustomMapTerrainEditor.Tool.SELECT
                && !endpointTool);
        slopeModeLabel.setVisible(activeTool == CustomMapTerrainEditor.Tool.SLOPE);
        slopeMode.setVisible(activeTool == CustomMapTerrainEditor.Tool.SLOPE);
        slopeMode.setEnabled(generated && activeTool == CustomMapTerrainEditor.Tool.SLOPE);
        iceModeLabel.setVisible(activeTool == CustomMapTerrainEditor.Tool.ICE);
        iceMode.setVisible(activeTool == CustomMapTerrainEditor.Tool.ICE);
        iceMode.setEnabled(generated && activeTool == CustomMapTerrainEditor.Tool.ICE);
        updateHistoryButtons();
        if (!generated && activeTool != CustomMapTerrainEditor.Tool.SELECT)
            status.setText("This tab has not been generated. Generate it before using editing tools.");
        updatePlacementMode();
    }

    private boolean selectedSlopeSupportsSteep() {
        if (selectedPaletteAsset == null
                || selectedPaletteAsset.category != CustomMapPalette.Category.SLOPE)
            return false;
        try {
            TileCatalog.TileSet root = TileCatalog.find(selectedPaletteAsset.theme);
            if (root == null) return false;
            if (selectedPaletteAsset.family != null
                    && !selectedPaletteAsset.family.isEmpty())
                for (TileCatalog.TileSet family : root.groundFamilies)
                    if (family != null && selectedPaletteAsset.family.equalsIgnoreCase(
                            family.groundFamily)) return family.supportsSteepSlopes();
            ModeVariant variant = currentVariant();
            return root.resolveBaseGroundFamily(variant == null ? 0L : variant.seed)
                    .supportsSteepSlopes();
        } catch (IOException ignored) {
            return false;
        }
    }

    private void installStrokeListener(final CustomMapPreviewPanel preview) {
        preview.setStrokeListener(new CustomMapPreviewPanel.StrokeListener() {
            @Override public void finished(ModeVariant original,
                                           List<java.awt.Point> cells) {
                if (preview != currentPreview() || original == null) return;
                String before = CustomMapTerrainEditor.snapshot(original);
                CustomMapTerrainEditor.Result result = CustomMapTerrainEditor.apply(
                        original, cells, selectedPaletteAsset, activeTool,
                        brushSize.getSelectedIndex() == 1 ? 2 : 1,
                        original == (document == null ? null : document.battleTerrain),
                        (CustomMapTerrainEditor.SlopeMode) slopeMode.getSelectedItem(),
                        (CustomMapTerrainEditor.IceMode) iceMode.getSelectedItem());
                if (!result.changed) {
                    status.setText(result.message);
                    java.awt.Toolkit.getDefaultToolkit().beep();
                    return;
                }
                EditHistory history = historyFor(original);
                history.undo.addLast(before);
                while (history.undo.size() > 100) history.undo.removeFirst();
                history.redo.clear();
                replaceVariant(original, result.variant);
                updateCurrentPreview();
                updateStatus();
                updateHistoryButtons();
            }

            @Override public void decorationMoved(ModeVariant original,
                                                   int index, int tileX) {
                if (preview != currentPreview() || original == null) return;
                String before = CustomMapTerrainEditor.snapshot(original);
                CustomMapTerrainEditor.Result result =
                        CustomMapTerrainEditor.moveDecoration(original, index, tileX);
                if (!result.changed) {
                    status.setText(result.message);
                    java.awt.Toolkit.getDefaultToolkit().beep();
                    return;
                }
                EditHistory history = historyFor(original);
                history.undo.addLast(before);
                while (history.undo.size() > 100) history.undo.removeFirst();
                history.redo.clear();
                replaceVariant(original, result.variant);
                updateCurrentPreview();
                updateStatus();
                updateHistoryButtons();
            }
        });
    }

    private EditHistory historyFor(ModeVariant variant) {
        String key = historyKey(variant);
        EditHistory found = editHistory.get(key);
        if (found == null) {
            found = new EditHistory();
            editHistory.put(key, found);
        }
        return found;
    }

    private String historyKey(ModeVariant variant) {
        if (document != null && variant == document.battleTerrain) return "battle";
        return variant == null || variant.mode == null ? "none" : variant.mode;
    }

    private void replaceVariant(ModeVariant original, ModeVariant replacement) {
        if (document == null || replacement == null) return;
        if (original == document.battleTerrain || "battle".equals(replacement.mode))
            document.battleTerrain = replacement;
        else document.variants.put(replacement.mode, replacement);
    }

    private void undoEdit() {
        ModeVariant current = currentVariant();
        if (current == null) return;
        EditHistory history = historyFor(current);
        if (history.undo.isEmpty()) return;
        history.redo.addLast(CustomMapTerrainEditor.snapshot(current));
        ModeVariant restored = CustomMapTerrainEditor.restore(history.undo.removeLast());
        replaceVariant(current, restored);
        updateCurrentPreview();
        updateStatus();
        updateHistoryButtons();
    }

    private void redoEdit() {
        ModeVariant current = currentVariant();
        if (current == null) return;
        EditHistory history = historyFor(current);
        if (history.redo.isEmpty()) return;
        history.undo.addLast(CustomMapTerrainEditor.snapshot(current));
        ModeVariant restored = CustomMapTerrainEditor.restore(history.redo.removeLast());
        replaceVariant(current, restored);
        updateCurrentPreview();
        updateStatus();
        updateHistoryButtons();
    }

    private void updateHistoryButtons() {
        ModeVariant current = currentVariant();
        EditHistory history = current == null ? null : editHistory.get(historyKey(current));
        undoEdit.setEnabled(history != null && !history.undo.isEmpty());
        redoEdit.setEnabled(history != null && !history.redo.isEmpty());
    }

    private static final class EditHistory {
        final Deque<String> undo = new ArrayDeque<String>();
        final Deque<String> redo = new ArrayDeque<String>();
    }

    private JPanel buildPatrolPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                "Selected floating island (each island has independent settings)"));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        patrolIsland.setPreferredSize(new Dimension(300, 25));
        patrolAX.setToolTipText("Horizontal centre of endpoint A, measured in tiles from the map's left edge.");
        patrolBX.setToolTipText("Horizontal centre of endpoint B, measured in tiles from the map's left edge.");
        patrolAY.setToolTipText("Surface height at A, measured upward in tiles from the grid bottom.");
        patrolBY.setToolTipText("Surface height at B, measured upward in tiles from the grid bottom.");
        patrolSpeed.setToolTipText("Editing Speed recalculates A-to-B time.");
        patrolDuration.setToolTipText("Editing A-to-B time recalculates Speed; the last edited field wins.");
        patrolDwell.setToolTipText("How long the island waits at A and again at B.");
        patrolAX.setEditor(new JSpinner.NumberEditor(patrolAX, "0.000"));
        patrolAY.setEditor(new JSpinner.NumberEditor(patrolAY, "0.000"));
        patrolBX.setEditor(new JSpinner.NumberEditor(patrolBX, "0.000"));
        patrolBY.setEditor(new JSpinner.NumberEditor(patrolBY, "0.000"));
        patrolSpeed.setEditor(new JSpinner.NumberEditor(patrolSpeed, "0.00"));
        patrolDuration.setEditor(new JSpinner.NumberEditor(patrolDuration, "0.00"));
        patrolDwell.setEditor(new JSpinner.NumberEditor(patrolDwell, "0.0"));
        JButton restart = new JButton("Restart preview at A");
        restart.setToolTipText("Restart only the Studio timeline animation; this does not launch gameplay.");

        JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        selection.add(new JLabel("Island:"));
        selection.add(patrolIsland);
        selection.add(patrolEnabled);
        selection.add(restart);

        patrolIslandDetails.setFont(patrolIslandDetails.getFont()
                .deriveFont(Font.BOLD, 11f));
        patrolIslandDetails.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));

        JPanel endpoints = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        endpoints.add(new JLabel("A X:")); endpoints.add(patrolAX);
        endpoints.add(new JLabel("Y:")); endpoints.add(patrolAY);
        endpoints.add(Box.createHorizontalStrut(8));
        endpoints.add(new JLabel("B X:")); endpoints.add(patrolBX);
        endpoints.add(new JLabel("Y:")); endpoints.add(patrolBY);

        JPanel timing = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        timing.add(new JLabel("Speed (tiles/s):")); timing.add(patrolSpeed);
        timing.add(new JLabel("A->B time (s):")); timing.add(patrolDuration);
        timing.add(new JLabel("Dwell at each end (s):")); timing.add(patrolDwell);

        patrolValidation.setFont(patrolValidation.getFont().deriveFont(Font.ITALIC, 11f));
        patrolValidation.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));

        GridBagConstraints row = new GridBagConstraints();
        row.gridx = 0; row.gridy = 0; row.weightx = 1;
        row.fill = GridBagConstraints.HORIZONTAL; row.anchor = GridBagConstraints.WEST;
        panel.add(patrolIslandDetails, row);
        row.gridy = 1; panel.add(selection, row);
        row.gridy = 2; panel.add(endpoints, row);
        row.gridy = 3; panel.add(timing, row);
        row.gridy = 4; panel.add(patrolValidation, row);

        patrolIsland.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (syncingPatrolControls) return;
                IslandChoice choice = (IslandChoice) patrolIsland.getSelectedItem();
                CustomMapPreviewPanel preview = currentPreview();
                if (preview != null) preview.selectPatrolPlatform(
                        choice == null ? null : choice.platform);
                syncPatrolControls(choice == null ? null : choice.platform);
            }
        });
        patrolEnabled.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!syncingPatrolControls) applyPatrolEnabled();
            }
        });
        ChangeListener endpointListener = new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (!syncingPatrolControls) applyPatrolEndpoints();
            }
        };
        patrolAX.addChangeListener(endpointListener);
        patrolAY.addChangeListener(endpointListener);
        patrolBX.addChangeListener(endpointListener);
        patrolBY.addChangeListener(endpointListener);
        patrolSpeed.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (!syncingPatrolControls) applyPatrolSpeed();
            }
        });
        patrolDuration.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (!syncingPatrolControls) applyPatrolDuration();
            }
        });
        patrolDwell.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (!syncingPatrolControls) applyPatrolDwell();
            }
        });
        restart.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                CustomMapPreviewPanel preview = currentPreview();
                if (preview != null) preview.restartPatrolPreview();
            }
        });
        return panel;
    }

    private void installPatrolListener(final CustomMapPreviewPanel preview) {
        preview.setPatrolListener(new CustomMapPreviewPanel.PatrolListener() {
            @Override public void selectionChanged(ModeVariant variant,
                                                   SecondaryPlatform platform) {
                if (preview == currentPreview()) {
                    selectPatrolChoice(platform);
                    syncPatrolControls(platform);
                }
            }

            @Override public void patrolChanged(ModeVariant variant,
                                                SecondaryPlatform platform) {
                if (preview == currentPreview()) syncPatrolControls(platform);
                if (document != null) document.markPatrolRevisionIfNeeded();
                updateStatus();
            }
        });
    }

    private CustomMapPreviewPanel currentPreview() {
        if (tabs.getSelectedComponent() == battlePreview) return battlePreview;
        int index = tabs.getSelectedIndex() - 1;
        if (index < 0 || index >= STUDIO_MODES.length) return null;
        return previews.get(STUDIO_MODES[index]);
    }

    private void refreshPatrolInspector() {
        ModeVariant variant = currentVariant();
        CustomMapPreviewPanel preview = currentPreview();
        boolean allowed = preview != null && preview.patrolEditingAllowed()
                && variant != null;
        SecondaryPlatform selected = preview == null
                ? null : preview.selectedPatrolPlatform();
        syncingPatrolControls = true;
        try {
            patrolIsland.removeAllItems();
            if (allowed && variant.secondaryPlatforms != null) {

                MovingPlatformEngine.normalize(variant);
                int index = 1;
                for (SecondaryPlatform platform : variant.secondaryPlatforms) {
                    if (platform == null) continue;
                    MovingPlatformEngine.normalize(variant, platform);
                    IslandChoice choice = new IslandChoice(index++, platform);
                    patrolIsland.addItem(choice);
                    if (platform == selected) patrolIsland.setSelectedItem(choice);
                }
                if (selected == null && patrolIsland.getItemCount() > 0) {
                    IslandChoice first = patrolIsland.getItemAt(0);
                    selected = first.platform;
                    patrolIsland.setSelectedIndex(0);
                    preview.selectPatrolPlatform(selected);
                }
            }
        } finally {
            syncingPatrolControls = false;
        }
        syncPatrolControls(selected);
    }

    private void selectPatrolChoice(SecondaryPlatform platform) {
        syncingPatrolControls = true;
        try {
            for (int i = 0; i < patrolIsland.getItemCount(); i++) {
                IslandChoice choice = patrolIsland.getItemAt(i);
                if (choice.platform == platform) {
                    patrolIsland.setSelectedIndex(i);
                    return;
                }
            }
        } finally {
            syncingPatrolControls = false;
        }
    }

    private void syncPatrolControls(SecondaryPlatform platform) {
        ModeVariant variant = currentVariant();
        CustomMapPreviewPanel preview = currentPreview();
        boolean allowed = preview != null && preview.patrolEditingAllowed()
                && variant != null;
        boolean available = allowed && platform != null;
        syncingPatrolControls = true;
        try {
            inspectedPatrolPlatform = available ? platform : null;
            patrolIsland.setEnabled(allowed && patrolIsland.getItemCount() > 0);
            patrolEnabled.setEnabled(available);
            patrolAX.setEnabled(available);
            patrolAY.setEnabled(available);
            patrolBX.setEnabled(available);
            patrolBY.setEnabled(available);
            patrolSpeed.setEnabled(available);
            patrolDuration.setEnabled(available);
            patrolDwell.setEnabled(available);
            if (available) {
                MovingPlatformEngine.normalize(variant, platform);
                PlatformPatrol patrol = platform.patrol;
                patrolIslandDetails.setForeground(new Color(35, 80, 135));
                patrolIslandDetails.setText(selectedIslandDescription(variant, platform));
                patrolEnabled.setSelected(patrol.enabled);
                setPatrolSpinnerModels(variant, patrol);
                String issue = preview.patrolValidationMessage(platform);
                if (!patrol.enabled) {
                    patrolValidation.setForeground(new Color(90, 90, 90));
                    patrolValidation.setText("Static island: drag the overlapping A/B marker to set B, then enable Moving patrol.");
                } else {
                    patrolValidation.setForeground(issue.isEmpty()
                            ? new Color(35, 125, 65) : new Color(190, 45, 45));
                    patrolValidation.setText(issue.isEmpty()
                            ? "Valid swept path. The last edited Speed/Time field wins."
                            : issue);
                }
            } else {
                patrolIslandDetails.setForeground(new Color(90, 90, 90));
                patrolIslandDetails.setText(variant == null
                        ? "Generate or load a map first."
                        : !allowed ? "This mode does not support moving islands."
                        : "Click a floating island in Preview, or choose one from the list.");
                patrolEnabled.setSelected(false);
                patrolValidation.setForeground(new Color(90, 90, 90));
                patrolValidation.setText(variant == null
                        ? "Generate or load a map first."
                        : !allowed ? "Moving islands are disabled for Duel."
                        : "This variant has no floating island to configure.");
            }
        } finally {
            syncingPatrolControls = false;
        }
    }

    private String selectedIslandDescription(ModeVariant variant,
                                             SecondaryPlatform platform) {
        int index = 1;
        if (variant.secondaryPlatforms != null) {
            for (SecondaryPlatform candidate : variant.secondaryPlatforms) {
                if (candidate == platform) break;
                if (candidate != null) index++;
            }
        }
        float surfaceY = -platform.supportLayer
                / Math.max(1f, variant.layerUnitsPerTile());
        int widthTiles = Math.max(1, platform.endX - platform.startX + 1);
        String id = platform.id == null || platform.id.trim().isEmpty()
                ? platform.startX + "-" + platform.endX : platform.id;
        String state = platform.patrol != null && platform.patrol.enabled
                ? "MOVING" : "STATIC";
        return "Editing Island " + index + " only | ID " + id
                + " | tier " + islandTier(variant, platform)
                + " | surface Y " + String.format(java.util.Locale.ROOT, "%.2f", surfaceY)
                + " | X " + platform.startX + "-" + platform.endX
                + " | width " + widthTiles + " tiles | " + state;
    }

    private static int islandTier(ModeVariant variant, SecondaryPlatform selected) {
        TreeSet<Integer> levels = new TreeSet<Integer>();
        if (variant != null && variant.secondaryPlatforms != null) {
            for (SecondaryPlatform platform : variant.secondaryPlatforms) {
                if (platform == null) continue;
                float y = -platform.supportLayer
                        / Math.max(1f, variant.layerUnitsPerTile());
                levels.add(Math.round(y * 100f));
            }
        }
        float selectedY = -selected.supportLayer
                / Math.max(1f, variant.layerUnitsPerTile());
        int target = Math.round(selectedY * 100f);
        int tier = 1;
        for (Integer level : levels) {
            if (level.intValue() == target) return tier;
            tier++;
        }
        return 1;
    }

    private void setPatrolSpinnerModels(ModeVariant variant, PlatformPatrol patrol) {
        patrolAX.setModel(new SpinnerNumberModel((double) patrol.ax,
                0.0, (double) variant.width, 0.01));
        patrolAY.setModel(new SpinnerNumberModel((double) patrol.ay,
                1.0, (double) variant.height, 0.01));
        patrolBX.setModel(new SpinnerNumberModel((double) patrol.bx,
                0.0, (double) variant.width, 0.01));
        patrolBY.setModel(new SpinnerNumberModel((double) patrol.by,
                1.0, (double) variant.height, 0.01));
        patrolSpeed.setModel(new SpinnerNumberModel((double) patrol.speedTilesPerSecond,
                0.1, 4.0, 0.1));
        patrolDuration.setModel(new SpinnerNumberModel(
                (double) Math.max(MovingPlatformEngine.MIN_TRAVEL_SECONDS,
                        patrol.durationSeconds),
                (double) MovingPlatformEngine.MIN_TRAVEL_SECONDS, 3600.0, 0.1));
        patrolDwell.setModel(new SpinnerNumberModel((double) patrol.dwellSeconds,
                0.0, 30.0, 0.1));
    }

    private SecondaryPlatform selectedPatrolPlatform() {
        ModeVariant variant = currentVariant();
        if (inspectedPatrolPlatform != null && variant != null
                && variant.secondaryPlatforms != null
                && variant.secondaryPlatforms.contains(inspectedPatrolPlatform))
            return inspectedPatrolPlatform;
        IslandChoice choice = (IslandChoice) patrolIsland.getSelectedItem();
        return choice == null ? null : choice.platform;
    }

    private void applyPatrolEnabled() {
        ModeVariant variant = currentVariant();
        SecondaryPlatform platform = selectedPatrolPlatform();
        if (variant == null || platform == null) return;
        MovingPlatformEngine.normalize(variant, platform);
        platform.patrol.enabled = patrolEnabled.isSelected();

        MovingPlatformEngine.normalize(variant, platform);
        boolean enabled = platform.patrol.enabled;
        if (enabled && !relocatePlatformTrees(variant, platform)) {
            syncingPatrolControls = true;
            patrolEnabled.setSelected(false);
            syncingPatrolControls = false;
            platform.patrol.enabled = false;
            patrolValidation.setForeground(new Color(190, 45, 45));
            patrolValidation.setText("No valid static ground was available for this island's trees.");
            return;
        }
        if (document != null) document.markPatrolRevisionIfNeeded();
        CustomMapPreviewPanel preview = currentPreview();
        if (preview != null) preview.invalidatePatrolTerrain();
        patrolChanged(platform, true);
    }

    private void applyPatrolEndpoints() {
        ModeVariant variant = currentVariant();
        SecondaryPlatform platform = selectedPatrolPlatform();
        if (variant == null || platform == null) return;
        MovingPlatformEngine.setEndpoints(variant, platform,
                ((Number) patrolAX.getValue()).floatValue(),
                ((Number) patrolAY.getValue()).floatValue(),
                ((Number) patrolBX.getValue()).floatValue(),
                ((Number) patrolBY.getValue()).floatValue());
        patrolChanged(platform, false);
    }

    private void applyPatrolSpeed() {
        ModeVariant variant = currentVariant();
        SecondaryPlatform platform = selectedPatrolPlatform();
        if (variant == null || platform == null) return;
        MovingPlatformEngine.setSpeed(variant, platform,
                ((Number) patrolSpeed.getValue()).floatValue());
        patrolChanged(platform, false);
    }

    private void applyPatrolDuration() {
        ModeVariant variant = currentVariant();
        SecondaryPlatform platform = selectedPatrolPlatform();
        if (variant == null || platform == null) return;
        MovingPlatformEngine.setDuration(variant, platform,
                ((Number) patrolDuration.getValue()).floatValue());
        patrolChanged(platform, false);
    }

    private void applyPatrolDwell() {
        ModeVariant variant = currentVariant();
        SecondaryPlatform platform = selectedPatrolPlatform();
        if (variant == null || platform == null) return;
        MovingPlatformEngine.setDwell(variant, platform,
                ((Number) patrolDwell.getValue()).floatValue());
        patrolChanged(platform, false);
    }

    private void patrolChanged(SecondaryPlatform platform, boolean rebuildChoices) {
        CustomMapPreviewPanel preview = currentPreview();
        if (preview != null) {
            preview.selectPatrolPlatform(platform);
            preview.restartPatrolPreview();
        }
        if (rebuildChoices) refreshPatrolInspector();
        else syncPatrolControls(platform);
        updateStatus();
    }

    private boolean relocatePlatformTrees(ModeVariant variant, SecondaryPlatform platform) {
        if (variant.trees == null || variant.trees.isEmpty()) return true;
        int sourceRow = Math.round(variant.height
                - MovingPlatformEngine.originSupportTileY(variant, platform));
        ArrayList<TreePlacement> moving = new ArrayList<TreePlacement>();
        for (TreePlacement tree : variant.trees)
            if (tree != null && tree.x >= platform.startX && tree.x <= platform.endX
                    && tree.y == sourceRow) moving.add(tree);
        if (moving.isEmpty()) return true;

        ArrayList<Integer> candidates = new ArrayList<Integer>();
        for (int x = 1; x + 1 < variant.width; x++) {
            if (variant.surface == null || variant.surface[x] < 0
                    || variant.water != null && variant.water[x]
                    || variant.slopeDirection != null && variant.slopeDirection[x] != 0)
                continue;
            boolean occupied = false;
            for (TreePlacement tree : variant.trees)
                if (tree != null && !moving.contains(tree) && Math.abs(tree.x - x) < 2) {
                    occupied = true;
                    break;
                }
            if (!occupied) candidates.add(x);
        }
        if (candidates.size() < moving.size()) return false;
        long salt = document == null || document.spec == null ? variant.seed
                : document.spec.seed;
        java.util.Collections.shuffle(candidates,
                new Random(salt ^ safePlatformId(platform).hashCode()));
        ArrayList<Integer> destinations = new ArrayList<Integer>();
        for (Integer candidate : candidates) {
            boolean separated = true;
            for (Integer chosen : destinations)
                if (Math.abs(chosen - candidate) < 2) {
                    separated = false;
                    break;
                }
            if (separated) destinations.add(candidate);
            if (destinations.size() >= moving.size()) break;
        }
        if (destinations.size() < moving.size()) return false;
        for (int i = 0; i < moving.size(); i++) {
            TreePlacement tree = moving.get(i);
            int x = destinations.get(i);
            tree.x = x;
            tree.y = variant.surface[x];
        }
        variant.objectCount = variant.trees.size();
        return true;
    }

    private static String safePlatformId(SecondaryPlatform platform) {
        if (platform == null) return "island";
        if (platform.id != null && !platform.id.trim().isEmpty()) return platform.id;
        return platform.startX + "-" + platform.endX;
    }

    private void sizeToOwner(Window owner, Component parent) {
        if (owner != null && owner.getWidth() >= 640 && owner.getHeight() >= 480) {
            Rectangle bounds = owner.getBounds();
            dialog.setMaximumSize(new Dimension(bounds.width, bounds.height));
            dialog.setBounds(bounds);
            return;
        }
        GraphicsConfiguration config = parent == null ? null : parent.getGraphicsConfiguration();
        Rectangle usable = config == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
                : config.getBounds();
        int width = Math.min(1440, Math.max(760, usable.width));
        int height = Math.min(960, Math.max(560, usable.height));
        dialog.setSize(width, height);
        dialog.setLocation(usable.x + Math.max(0, (usable.width - width) / 2),
                usable.y + Math.max(0, (usable.height - height) / 2));
    }

    private void refreshTiles() {
        try {
            String selected = selectedTiles() == null ? null : selectedTiles().biome;
            String desired = unavailableSourceBiome == null
                    ? selected : unavailableSourceBiome;
            List<TileCatalog.TileSet> sets = TileCatalog.scan();
            availableTiles.clear();
            biome.removeAllItems();
            int hidden = 0;
            for (TileCatalog.TileSet set : sets) {
                if (isSelectableTheme(set)) {
                    biome.addItem(set);
                    availableTiles.add(set);
                }
                else hidden++;
            }
            boolean restored = desired != null && selectBiome(desired);
            if (unavailableSourceBiome != null) {
                if (restored) unavailableSourceBiome = null;
                else biome.setSelectedItem(null);
            }
            updateBiomeControls();
            palettePanel.setThemes(availableTiles, selectedTiles());
            if (biome.getItemCount() == 0) status.setText("Tiles folder is ready at:\n"
                    + TileCatalog.tilesRoot()
                    + "\nNo valid theme was found. A visible theme needs a usable ground set; "
                    + hidden + " invalid theme(s) were hidden.");
            else {
                updateStatus();
                if (hidden > 0) status.append("\n" + hidden
                        + " invalid theme(s) without a usable ground set were hidden.");
            }
        } catch (Throwable t) {
            showError("Tile scan failed", t);
        }
    }

    static boolean isSelectableTheme(TileCatalog.TileSet set) {

        return set != null && set.isUsable(0.0, 0.0);
    }

    private void updateBiomeControls() {
        TileCatalog.TileSet set = selectedTiles();
        boolean preservePortableSpec = document != null
                && unavailableSourceBiome != null;
        boolean hasWater = set != null && !set.water.isEmpty();
        boolean hadIceSurface = iceSurface.isEnabled();
        boolean hasIceSurface = set != null && set.supportsIceSurfaceDensity();
        if (!hasIceSurface && !preservePortableSpec) iceSurface.setValue(0.0);
        else if (hasIceSurface && !hadIceSurface && document == null)
            iceSurface.setValue(20.0);
        iceSurface.setEnabled(hasIceSurface);
        iceSurface.setToolTipText(hasIceSurface
                ? "Requested coating density (0-70%); direct Snow coating is capped at 20%. Runs are 3-9 tiles."
                : "This theme has no complete Ice surface overlay set; fixed at 0%.");
        boolean hasIceBridge = CustomMapGenerator.iceBridgesAllowed(hasIceSurface, set);
        if (!hasIceBridge && !preservePortableSpec) iceBridge.setValue(0.0);
        iceBridge.setEnabled(hasIceBridge);
        iceBridge.setToolTipText(hasIceBridge
                ? "Ice bridge density (0-100%). Decks 2-9 tile chasm or water spans between banks of equal height; water is pushed below the deck."
                : "Ice bridges need an Ice or Snow Ice theme; fixed at 0%.");
        liquidDensityLabel.setText(liquidDensityLabelText(set));
        if (!hasWater && !preservePortableSpec) water.setValue(0.0);
        water.setEnabled(hasWater);
        water.setToolTipText(liquidDensityTooltip(set));
        boolean hasTrees = set != null && !set.trees.isEmpty();
        if (!hasTrees && !preservePortableSpec) trees.setValue(0.0);
        trees.setEnabled(hasTrees);
        trees.setToolTipText(hasTrees
                ? "Tree/object density for this theme."
                : "This theme has no detected tree/object tile; Trees % is fixed at 0.");
        boolean hasProps = set != null && set.supportsProps();
        if (!hasProps && !preservePortableSpec) props.setValue(0.0);
        props.setEnabled(hasProps);
        props.setToolTipText(hasProps
                ? "Decorative-only prop density; props never add collision or interactions."
                : "This theme has no complete random-safe prop; Props % is fixed at 0.");
        TileCatalog.TileSet slopeTiles = set == null ? null
                : set.resolveBaseGroundFamily(((Number) seed.getValue()).longValue());
        boolean hasSlopes = slopeTiles != null && slopeTiles.supportsSlopes();
        if (!hasSlopes && !preservePortableSpec) {
            slopeCount.setValue(0);
            slopeCoverage.setValue(0.0);
        }
        slopeMinY.setEnabled(hasSlopes);
        slopeMaxY.setEnabled(hasSlopes);
        slopeCount.setEnabled(hasSlopes);
        slopeCoverage.setEnabled(hasSlopes);
        slopeMinRise.setEnabled(hasSlopes);
        slopeMaxRise.setEnabled(hasSlopes);
        slopeMinLength.setEnabled(hasSlopes);
        slopeMaxLength.setEnabled(hasSlopes);
        slopeCount.setToolTipText(slopeCountTooltip(slopeTiles, hasSlopes));
        slopeCoverage.setToolTipText(hasSlopes
                ? "Requested share of dry main-route columns occupied by slopes (0-80%). 0% keeps legacy count-based generation; 30-80% is recommended for slope-heavy maps."
                : "The selected ground palette has no valid bidirectional slope contour; Slope coverage is fixed at 0%.");
    }

    private void refreshLibrary() {
        library.removeAllItems();
        for (CustomMapRepository.MapRecord record : CustomMapRepository.list()) library.addItem(record);
    }

    private void newDocument() {
        document = null;
        editHistory.clear();
        unavailableSourceBiome = null;
        name.setText("New Custom Map");
        seed.setValue(1L);
        width.setValue(75);
        height.setValue(CustomMapGenerator.DEFAULT_MAP_HEIGHT);
        slopeMinY.setValue(2);
        slopeMaxY.setValue(10);
        slopeCount.setValue(8);
        slopeCoverage.setValue(0.0);
        slopeMinRise.setValue(1);
        slopeMaxRise.setValue(4);
        slopeMinLength.setValue(1);
        slopeMaxLength.setValue(48);
        floatingIslandLayers.setValue(2);
        floatingIslands.setValue(3);
        complexity.setValue(50);
        updateBiomeControls();
        for (CustomMapPreviewPanel preview : previews.values())
            preview.setDocument(null, null, selectedTiles());
        battlePreview.setDocument(null, null, selectedTiles());
        refreshPatrolInspector();
        applyEditorState();
        updateStatus();
    }

    private void loadSelected() {
        CustomMapRepository.MapRecord record = (CustomMapRepository.MapRecord) library.getSelectedItem();
        if (record == null) return;
        try {
            document = CustomMapRepository.load(record.uuid);
            if (document == null) throw new IllegalStateException("Map metadata is missing.");
            unavailableSourceBiome = null;
            applySpec(document.spec);
            if (!selectedBiomeMatches(document.spec == null ? null
                    : document.spec.biome)) {
                unavailableSourceBiome = document.spec == null
                        ? "" : document.spec.biome;
                biome.setSelectedItem(null);

                updateBiomeControls();
            }
            TileCatalog.TileSet collisionTiles = selectedTiles();
            if (collisionTiles != null)
                CustomMapChunkWriter.assignFloatingIslandCollisionProfiles(
                        document, collisionTiles);
            updatePreviews();
            editHistory.clear();
            updateStatus();
        } catch (Throwable t) {
            showError("Could not open map", t);
        }
    }

    private void deleteSelected() {
        CustomMapRepository.MapRecord record =
                (CustomMapRepository.MapRecord) library.getSelectedItem();
        if (record == null) {
            JOptionPane.showMessageDialog(dialog, "Select a saved map first.",
                    "Delete permanently", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String label = record.name == null || record.name.trim().isEmpty()
                ? record.uuid : record.name.trim();
        Object[] options = {"Delete permanently", "Cancel"};
        int choice = JOptionPane.showOptionDialog(dialog,
                "Permanently delete '" + label + "'?\n\n"
                        + "This removes its BCU stage, Background entry, map metadata, "
                        + "chunks and embedded assets.\nThis action cannot be undone.",
                "Delete Custom Map", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) return;
        try {
            boolean removed = CustomMapRepository.deletePermanently(record.uuid);
            if (!removed) throw new IllegalStateException(
                    "The selected map no longer exists in the Custom Map index.");
            boolean deletedOpenDocument = document != null
                    && record.uuid != null && record.uuid.equals(document.uuid);
            refreshLibrary();
            if (deletedOpenDocument) newDocument();
            status.setText("Permanently deleted '" + label
                    + "': stage, Background, metadata, chunks and embedded assets removed.");
        } catch (Throwable t) {
            showError("Permanent deletion failed", t);
        }
    }

    private void generate(boolean nextSeed) {
        try {
            if (!confirmGenerateClearsEdits()) return;
            if (nextSeed) seed.setValue(((Number) seed.getValue()).longValue() + 1L);
            MapSpec spec = readSpec();
            TileCatalog.TileSet set = selectedTiles();
            if (set == null) throw new IllegalArgumentException("Add and select a biome first.");
            if (!set.isUsable(spec.waterDensity, spec.treeDensity, spec.propDensity))
                throw new IllegalArgumentException(set.validationMessage(
                        spec.waterDensity, spec.treeDensity, spec.propDensity));
            String uuid = document == null ? null : document.uuid;
            long created = document == null ? 0L : document.createdAt;
            document = CustomMapGenerator.generate(spec, uuid, set);
            if (created > 0) document.createdAt = created;
            unavailableSourceBiome = null;
            editHistory.clear();
            updatePreviews();
            updateStatus();
        } catch (Throwable t) {
            showError("Generation failed", t);
        }
    }

    private void randomizeAndGenerate() {
        try {
            if (!confirmGenerateClearsEdits()) return;
            TileCatalog.TileSet retainedTheme = selectedTiles();
            if (retainedTheme == null)
                throw new IllegalArgumentException("Select a biome before using Random.");
            CustomMapRandomizer.Preset preset =
                    (CustomMapRandomizer.Preset) randomPreset.getSelectedItem();
            List<String> retainedModes = selectedVariantIds();
            MapSpec spec = randomizedSpecForSelection(
                    retainedTheme, random, preset, retainedModes);

            document = null;
            unavailableSourceBiome = null;
            applySpec(spec);
            generate(false);
        } catch (Throwable t) {
            showError("Random generation failed", t);
        }
    }

    private boolean confirmGenerateClearsEdits() {
        if (document == null) return true;
        int terrain = 0, decorations = 0, backgrounds = 0, effects = 0;
        ArrayList<ModeVariant> variants = new ArrayList<ModeVariant>();
        if (document.battleTerrain != null) variants.add(document.battleTerrain);
        if (document.variants != null) variants.addAll(document.variants.values());
        for (ModeVariant variant : variants) {
            if (variant == null) continue;
            terrain += variant.manualTiles == null ? 0 : variant.manualTiles.size();
            decorations += variant.manualDecorations == null
                    ? 0 : variant.manualDecorations.size();
            backgrounds += variant.manualBackground == null
                    ? 0 : variant.manualBackground.size();
            effects += variant.manualEffects == null ? 0 : variant.manualEffects.size();
        }
        boolean patrols = hasAuthoredPatrolSettings(document);
        if (terrain + decorations + backgrounds + effects == 0 && !patrols) return true;
        StringBuilder message = new StringBuilder(
                "Generate/Regenerate/Random will remove these manual changes:\n");
        if (terrain > 0) message.append("- Terrain/liquid/slope cells: ").append(terrain).append('\n');
        if (decorations > 0) message.append("- Trees/decorations: ").append(decorations).append('\n');
        if (backgrounds > 0) message.append("- Background layers: ").append(backgrounds).append('\n');
        if (effects > 0) message.append("- Effects: ").append(effects).append('\n');
        if (patrols) message.append("- Floating-island patrol settings\n");
        message.append("Undo/Redo history will also be cleared. Continue?");
        return JOptionPane.showConfirmDialog(dialog, message.toString(),
                "Replace manual edits", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static boolean hasAuthoredPatrolSettings(CustomMapDocument doc) {
        if (doc == null) return false;
        if (hasAuthoredPatrolSettings(doc.battleTerrain)) return true;
        if (doc.variants != null)
            for (ModeVariant variant : doc.variants.values())
                if (hasAuthoredPatrolSettings(variant)) return true;
        return false;
    }

    private static boolean hasAuthoredPatrolSettings(ModeVariant variant) {
        if (variant == null || variant.secondaryPlatforms == null) return false;
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || platform.patrol == null) continue;
            MovingPlatformEngine.normalize(variant, platform);
            PlatformPatrol patrol = platform.patrol;
            float originX = MovingPlatformEngine.originCenterTileX(platform);
            float originY = MovingPlatformEngine.originSupportTileY(variant, platform);
            if (patrol.enabled || Math.abs(patrol.ax - originX)
                    > MovingPlatformEngine.POSITION_EPSILON_TILES
                    || Math.abs(patrol.ay - originY)
                    > MovingPlatformEngine.POSITION_EPSILON_TILES
                    || Math.abs(patrol.bx - originX)
                    > MovingPlatformEngine.POSITION_EPSILON_TILES
                    || Math.abs(patrol.by - originY)
                    > MovingPlatformEngine.POSITION_EPSILON_TILES
                    || Math.abs(patrol.speedTilesPerSecond - 1f) > .01f
                    || Math.abs(patrol.dwellSeconds - 1f) > .01f
                    || MovingPlatformEngine.AUTHORITY_DURATION.equals(
                    patrol.timingAuthority)) return true;
        }
        return false;
    }

    private void save(boolean exportAfter) {
        try {
            if (document == null) throw new IllegalStateException("Generate a map before saving.");
            String patrolIssue = firstPatrolValidationIssue();
            if (patrolIssue != null)
                throw new IllegalStateException("Moving-island validation failed:\n"
                        + patrolIssue);

            String editedName = name.getText();
            document.name = editedName;
            if (document.spec != null) document.spec.name = editedName;
            TileCatalog.TileSet set = selectedTiles();
            if (set != null && !sameBiome(set.biome,
                    document.spec == null ? null : document.spec.biome))
                set = null;
            CustomMapRepository.save(document, set);
            refreshLibrary();
            if (exportAfter) {
                File output = CustomMapRepository.exportPack();
                JOptionPane.showMessageDialog(dialog, "Exported:\n" + output,
                        "Custom Map Studio", JOptionPane.INFORMATION_MESSAGE);
            } else {
                status.setText("Saved '" + document.name + "' to pack " + CustomMapRepository.PACK_NAME + ".");
            }
        } catch (Throwable t) {
            showError(exportAfter ? "Save/export failed" : "Save failed", t);
        }
    }

    private String firstPatrolValidationIssue() {
        if (document == null) return null;
        String issue = firstPatrolValidationIssue(document.battleTerrain, battlePreview);
        if (issue != null) return "BCU stage: " + issue;
        for (MapMode mode : STUDIO_MODES) {
            ModeVariant variant = document.variant(mode);
            issue = firstPatrolValidationIssue(variant, previews.get(mode));
            if (issue != null) return mode.title + ": " + issue;
        }
        return null;
    }

    private static String firstPatrolValidationIssue(ModeVariant variant,
                                                      CustomMapPreviewPanel preview) {
        if (variant == null || preview == null || variant.secondaryPlatforms == null)
            return null;
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || platform.patrol == null || !platform.patrol.enabled)
                continue;
            String issue = preview.patrolValidationMessage(platform);
            if (!issue.isEmpty()) return safePlatformId(platform) + ": " + issue;
        }
        return null;
    }

    private MapSpec readSpec() {
        MapSpec spec = new MapSpec();
        spec.name = name.getText();
        TileCatalog.TileSet set = selectedTiles();
        spec.biome = set == null ? "" : set.biome;
        spec.seed = ((Number) seed.getValue()).longValue();
        spec.width = ((Number) width.getValue()).intValue();
        spec.height = ((Number) height.getValue()).intValue();
        spec.groundDensity = ((Number) ground.getValue()).doubleValue();
        spec.iceSurfaceDensity = ((Number) iceSurface.getValue()).doubleValue();
        spec.iceBridgeDensity = ((Number) iceBridge.getValue()).doubleValue();
        spec.waterDensity = ((Number) water.getValue()).doubleValue();
        spec.treeDensity = ((Number) trees.getValue()).doubleValue();
        spec.propDensity = ((Number) props.getValue()).doubleValue();
        spec.slopeMinY = ((Number) slopeMinY.getValue()).intValue();
        spec.slopeMaxY = ((Number) slopeMaxY.getValue()).intValue();
        spec.slopeCount = ((Number) slopeCount.getValue()).intValue();
        spec.slopeCoverage = ((Number) slopeCoverage.getValue()).doubleValue();
        spec.slopeMinRise = ((Number) slopeMinRise.getValue()).intValue();
        spec.slopeMaxRise = ((Number) slopeMaxRise.getValue()).intValue();
        spec.slopeMinLength = ((Number) slopeMinLength.getValue()).intValue();
        spec.slopeMaxLength = ((Number) slopeMaxLength.getValue()).intValue();
        spec.floatingIslandDensity = 0.0;
        spec.floatingIslandCount = ((Number) floatingIslands.getValue()).intValue();
        spec.floatingIslandLayers = ((Number) floatingIslandLayers.getValue()).intValue();
        spec.complexity = ((Number) complexity.getValue()).intValue();
        spec.difficulty = String.valueOf(difficulty.getSelectedItem());
        spec.adventureEnemyOverride = ((Number) adventureEnemies.getValue()).intValue();
        spec.heistEnemyOverride = ((Number) heistEnemies.getValue()).intValue();
        spec.enemyPool.clear();
        for (String token : enemyPool.getText().split(",")) {
            String id = token.trim();
            if (!id.isEmpty()) spec.enemyPool.add(id);
        }
        spec.modes.clear();
        spec.modes.addAll(selectedVariantIds());
        return spec;
    }

    private void applySpec(MapSpec spec) {
        if (spec == null) return;
        name.setText(spec.name);
        selectBiome(spec.biome);
        seed.setValue(spec.seed);
        width.setValue(Math.max(30, Math.min(120, spec.width)));
        int loadedHeight = Math.max(CustomMapGenerator.MIN_MAP_HEIGHT,
                Math.min(CustomMapGenerator.MAX_MAP_HEIGHT, spec.height));
        height.setValue(loadedHeight);
        updateHeightControls();
        ground.setValue(spec.groundDensity);
        iceSurface.setValue(Math.max(0.0, Math.min(70.0, spec.iceSurfaceDensity)));
        iceBridge.setValue(Math.max(0.0, Math.min(100.0, spec.iceBridgeDensity)));
        water.setValue(spec.waterDensity); trees.setValue(spec.treeDensity);
        props.setValue(spec.propDensity);
        int maximumTerrainY = Math.min(CustomMapGenerator.MAX_GROUND_HEIGHT,
                loadedHeight);
        slopeMinY.setValue(Math.max(2, Math.min(maximumTerrainY, spec.slopeMinY)));
        slopeMaxY.setValue(Math.max(2, Math.min(maximumTerrainY, spec.slopeMaxY)));
        slopeCount.setValue(spec.slopeCount);
        slopeCoverage.setValue(Math.max(0.0, Math.min(80.0, spec.slopeCoverage)));
        slopeMinRise.setValue(Math.max(1, Math.min(10, spec.slopeMinRise)));
        slopeMaxRise.setValue(Math.max(1, Math.min(10, spec.slopeMaxRise)));
        slopeMinLength.setValue(Math.max(1, Math.min(60, spec.slopeMinLength)));
        slopeMaxLength.setValue(Math.max(1, Math.min(60, spec.slopeMaxLength)));
        int islandCount = CustomMapGenerator.requestedFloatingIslandCount(spec);
        int islandLayers = CustomMapGenerator.requestedFloatingIslandLayers(spec);
        floatingIslandLayers.setValue(Math.max(0, Math.min(
                CustomMapGenerator.maxFloatingIslandLayers(loadedHeight), islandLayers)));
        floatingIslands.setValue(Math.max(0, Math.min(
                CustomMapGenerator.MAX_FLOATING_ISLAND_COUNT, islandCount)));
        complexity.setValue(spec.complexity);
        updateComplexityTierLabel();
        difficulty.setSelectedItem(spec.difficulty);
        adventureEnemies.setValue(spec.adventureEnemyOverride);
        heistEnemies.setValue(spec.heistEnemyOverride);
        StringBuilder pool = new StringBuilder();
        if (spec.enemyPool != null) for (String id : spec.enemyPool) {
            if (pool.length() > 0) pool.append(", ");
            pool.append(id);
        }
        enemyPool.setText(pool.toString());
        for (MapMode mode : STUDIO_MODES)
            modeChecks.get(mode).setSelected(spec.supports(mode));
        updateBiomeControls();
    }

    static MapSpec randomizedSpecForSelection(TileCatalog.TileSet retainedTheme,
                                              Random random,
                                              CustomMapRandomizer.Preset preset,
                                              List<String> retainedModes) {
        if (retainedTheme == null)
            throw new IllegalArgumentException("Select a biome before using Random.");
        ArrayList<TileCatalog.TileSet> singleton = new ArrayList<TileCatalog.TileSet>();
        singleton.add(retainedTheme);
        MapSpec spec = CustomMapRandomizer.create(singleton, random, preset);

        if (retainedTheme.water.isEmpty()) spec.waterDensity = 0.0;
        if (!retainedTheme.supportsIceSurfaceDensity()) spec.iceSurfaceDensity = 0.0;
        if (!CustomMapGenerator.iceBridgesAllowed(
                retainedTheme.supportsIceSurfaceDensity(), retainedTheme))
            spec.iceBridgeDensity = 0.0;
        if (retainedTheme.trees.isEmpty()) spec.treeDensity = 0.0;
        if (retainedTheme.randomPropCount() <= 0) spec.propDensity = 0.0;
        spec.biome = retainedTheme.biome;
        spec.modes.clear();
        if (retainedModes != null) for (MapMode mode : STUDIO_MODES)
            if (retainedModes.contains(mode.id)) spec.modes.add(mode.id);
        return spec;
    }

    private List<String> selectedVariantIds() {
        ArrayList<String> selected = new ArrayList<String>();
        for (MapMode mode : STUDIO_MODES) {
            JCheckBox check = modeChecks.get(mode);
            if (check != null && check.isSelected()) selected.add(mode.id);
        }
        return selected;
    }

    private void updatePreviews() {
        TileCatalog.TileSet set = selectedTiles();
        battlePreview.setDocument(document,
                document == null ? null : document.battleTerrain, set);
        for (MapMode mode : STUDIO_MODES)
            previews.get(mode).setDocument(document,
                    document == null ? null : document.variant(mode), set);
        updatePlacementMode();
        syncAnchorFields(currentVariant());
        refreshPatrolInspector();
        applyEditorState();
    }

    private void updateCurrentPreview() {
        CustomMapPreviewPanel preview = currentPreview();
        if (preview == null) return;
        ModeVariant value;
        if (preview == battlePreview) value = document == null
                ? null : document.battleTerrain;
        else {
            int index = tabs.getSelectedIndex() - 1;
            value = document == null || index < 0 || index >= STUDIO_MODES.length
                    ? null : document.variant(STUDIO_MODES[index]);
        }
        preview.setDocument(document, value, selectedTiles());
        updatePlacementMode();
        syncAnchorFields(value);
        refreshPatrolInspector();
        applyEditorState();
    }

    private void updatePlacementMode() {
        boolean canPlace = currentVariant() != null
                && activeTool == CustomMapTerrainEditor.Tool.SELECT;
        if (!canPlace) placeAnchorMode.setSelected(false);
        placeAnchorMode.setEnabled(canPlace);
        boolean enabled = canPlace && placeAnchorMode.isSelected();
        anchorX.setEnabled(enabled);
        applyAnchor.setEnabled(enabled);
        battlePreview.setPlacingSpawn(placeSpawn.isSelected());
        battlePreview.setAnchorPlacementEnabled(enabled);
        for (CustomMapPreviewPanel preview : previews.values()) {
            preview.setPlacingSpawn(placeSpawn.isSelected());
            preview.setAnchorPlacementEnabled(enabled);
        }
    }

    private void addOverlayToggle(JPanel row, String label,
                                  final CustomMapPreviewPanel.Overlay overlay,
                                  boolean selected) {
        final JToggleButton toggle = new JToggleButton(label, selected);
        toggle.setFocusable(false);
        toggle.setMargin(new Insets(2, 6, 2, 6));
        toggle.setToolTipText("Show/hide the " + label.toLowerCase()
                + " authoring overlay. This does not change the saved map.");
        setOverlay(overlay, selected);
        toggle.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setOverlay(overlay, toggle.isSelected());
            }
        });
        row.add(toggle);
    }

    private void setOverlay(CustomMapPreviewPanel.Overlay overlay, boolean enabled) {
        battlePreview.setOverlay(overlay, enabled);
        for (CustomMapPreviewPanel preview : previews.values())
            preview.setOverlay(overlay, enabled);
    }

    private ModeVariant currentVariant() {
        if (tabs.getSelectedComponent() == battlePreview) return battlePreview.variant();
        int index = tabs.getSelectedIndex() - 1;
        if (index < 0 || index >= STUDIO_MODES.length) return null;
        return previews.get(STUDIO_MODES[index]).variant();
    }

    private boolean editingBattleBases() {
        return tabs.getSelectedComponent() == battlePreview;
    }

    private void updateAnchorLabels() {
        if (editingBattleBases()) {
            placeAnchorMode.setText("Place bases on Preview");
            placeSpawn.setText("Enemy Base (S)");
            placeGoal.setText("Player Base (G)");
        } else {
            placeAnchorMode.setText("Place anchors on Preview");
            placeSpawn.setText("Place Spawn");
            placeGoal.setText("Place Destination");
        }
    }

    private void syncAnchorFields(ModeVariant variant) {
        if (variant == null) return;
        CustomMapDocument.MapAnchor anchor = placeSpawn.isSelected() ? variant.spawn : variant.destination;
        if (anchor == null) return;
        anchorX.setModel(new SpinnerNumberModel(anchor.x, 0, Math.max(0, variant.width - 1), 1));
        anchorY.setModel(new SpinnerNumberModel(anchor.y, 0, Math.max(0, variant.height - 1), 1));
    }

    private void applyAnchor() {
        if (!placeAnchorMode.isSelected()) return;
        ModeVariant variant = currentVariant();
        if (variant == null) return;
        int x = ((Number) anchorX.getValue()).intValue();
        if (editingBattleBases())
            CustomMapGenerator.moveBattleAnchor(variant, placeSpawn.isSelected(), x);
        else
            CustomMapGenerator.moveAnchor(variant, placeSpawn.isSelected(), x);
        if (editingBattleBases()) battlePreview.repaint();
        else {
            CustomMapPreviewPanel preview = previews.get(MapMode.fromId(variant.mode));
            if (preview != null) preview.repaint();
        }
        syncAnchorFields(variant);
        updateStatus();
    }

    private void updateStatus() {
        TileCatalog.TileSet set = selectedTiles();
        if (set == null) {
            if (document != null && unavailableSourceBiome != null) {
                status.setText("Source theme '" + unavailableSourceBiome
                        + "' is unavailable. The saved embedded terrain remains intact; "
                        + "patrol/anchor edits can still be saved. Select another theme "
                        + "and Regenerate to replace the terrain.");
            } else {
                status.setText("No biome found. Add PNG files under "
                        + TileCatalog.tilesRoot());
            }
            return;
        }
        double waterPct = ((Number) water.getValue()).doubleValue();
        double treePct = ((Number) trees.getValue()).doubleValue();
        double propPct = ((Number) props.getValue()).doubleValue();
        boolean replacementDraft = document != null
                && unavailableSourceBiome != null;
        if (!replacementDraft && !set.isUsable(waterPct, treePct, propPct)) {
            status.setText(set.validationMessage(((Number) water.getValue()).doubleValue(),
                    ((Number) trees.getValue()).doubleValue(), propPct));
            return;
        }
        if (document == null) {
            status.setText("Theme '" + set.biome + "' is ready: " + set.detectionSummary()
                    + "\n" + set.validationMessage(waterPct, treePct, propPct)
                    + slopeAvailabilityNote(set));
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (replacementDraft) {
            sb.append("SOURCE THEME '").append(unavailableSourceBiome)
                    .append("' IS UNAVAILABLE: Save keeps the embedded terrain. ")
                    .append("The selected theme is used only after Regenerate.\n");
        }
        if (!CustomMapDocument.isSupportedTerrainRevision(document.terrainRevision)) {
            sb.append("LEGACY TERRAIN r").append(document.terrainRevision)
                    .append(": preview only. Regenerate before Save, Export or Launch.\n");
        } else if (document.terrainRevision >= CustomMapDocument.PATROL_TERRAIN_REVISION) {
            sb.append("MOVING-ISLAND TERRAIN r")
                    .append(document.terrainRevision).append(".\n");
        }
        if (document.backgroundRevision != CustomMapDocument.BACKGROUND_REVISION) {
            sb.append("LEGACY BACKGROUND r").append(document.backgroundRevision)
                    .append(": Regenerate before Save, Export or Launch.\n");
        }
        double targetWater = CustomMapGenerator.effectiveWaterDensity(document.spec);
        double targetIce = Math.max(0.0, Math.min(20.0,
                document.spec == null ? 0.0 : document.spec.iceSurfaceDensity));
        double targetTrees = CustomMapGenerator.effectiveTreeDensity(document.spec);
        double targetProps = CustomMapGenerator.effectivePropDensity(document.spec);
        int targetIslands = CustomMapGenerator.requestedFloatingIslandCount(document.spec);
        int targetIslandLayers = CustomMapGenerator.requestedFloatingIslandLayers(document.spec);
        String liquidName = liquidStatusName(set);
        String poolName = liquidPoolStatusName(set);
        if (document.battleTerrain != null) {
            ModeVariant battle = document.battleTerrain;
            sb.append("BCU stage: ").append(battle.validation)
                    .append(" | S enemy base @ ").append(battle.spawn == null ? "?" : battle.spawn.x)
                    .append(" | G player base @ ").append(battle.destination == null ? "?" : battle.destination.x)
                    .append(liquidTraversalStatus(set));
            if (set.supportsIceSurfaceDensity())
                sb.append(" | ice ").append(round(battle.achievedIceSurfaceDensity))
                        .append('%').append('/').append(round(targetIce))
                        .append("% target");
            if (CustomMapGenerator.iceBridgesAllowed(
                    set.supportsIceSurfaceDensity(), set))
                sb.append(" | ice bridges ").append(battle.achievedIceBridgeCount)
                        .append(" @ ").append(round(CustomMapGenerator
                                .effectiveIceBridgeDensity(document.spec)))
                        .append("% target");
            if (battle.profile != null && battle.profile.complexityProfile != null) {
                CustomMapDocument.ComplexityProfile profile =
                        battle.profile.complexityProfile;
                sb.append(" | Tier ").append(profile.achievedTier)
                        .append('/').append(profile.requestedTier)
                        .append(" score ").append(round(profile.structuralScore))
                        .append('/').append(round(profile.targetScore))
                        .append(" slopes ").append(battle.achievedSlopeCount)
                        .append('/').append(document.spec.slopeCount).append(" requested")
                        .append(" coverage ").append(round(battle.achievedSlopeCoverage))
                        .append('%').append('/').append(round(document.spec.slopeCoverage))
                        .append("% target")
                        .append(" Y=").append(battle.achievedSlopeMinY)
                        .append("..").append(battle.achievedSlopeMaxY)
                        .append(" (requested ").append(document.spec.slopeMinY)
                        .append("..").append(document.spec.slopeMaxY).append(')')
                        .append(" rise ").append(battle.achievedSlopeMinRise)
                        .append("..").append(battle.achievedSlopeMaxRise)
                        .append("/").append(document.spec.slopeMinRise)
                        .append("..").append(document.spec.slopeMaxRise)
                        .append(" length ").append(battle.achievedSlopeMinLength)
                        .append("..").append(battle.achievedSlopeMaxLength)
                        .append("/").append(document.spec.slopeMinLength)
                        .append("..").append(document.spec.slopeMaxLength)
                        .append(" motifs ").append(battle.achievedSlopeMotifCount)
                        .append(" span ").append(profile.elevationSpanRows)
                        .append(' ').append(poolName).append(' ').append(battle.waterZoneCount)
                        .append(" islands ").append(battle.floatingIslandCount)
                        .append('/').append(targetIslands).append(" requested")
                        .append(" layers ").append(battle.floatingIslandLayerCount)
                        .append('/').append(targetIslandLayers)
                        .append(" gaps/steps ").append(profile.chasmStepGroupCount);
                if (profile.capReason != null && !profile.capReason.trim().isEmpty())
                    sb.append(" | ").append(profile.capReason);
            }
        }
        for (ModeVariant variant : document.variants.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(variant.mode).append(": ").append(variant.validation)
                    .append(" | ground ").append(round(variant.achievedGroundDensity)).append('%')
                    .append(set.supportsIceSurfaceDensity()
                            ? " ice " + round(variant.achievedIceSurfaceDensity)
                            + "%/" + round(targetIce) + "% target" : "")
                    .append(' ').append(liquidName).append(' ')
                    .append(round(variant.achievedWaterDensity)).append('%')
                    .append('/').append(round(targetWater)).append("% target")
                    .append(" trees ").append(round(variant.achievedTreeDensity)).append('%')
                    .append('/').append(round(targetTrees)).append("% target")
                    .append(" props P").append(variant.props == null ? 0 : variant.props.size())
                    .append('/').append(round(targetProps)).append("%")
                    .append(" slopes ").append(variant.achievedSlopeCount)
                    .append('/').append(document.spec.slopeCount).append(" requested")
                    .append(" coverage ").append(round(variant.achievedSlopeCoverage))
                    .append('%').append('/').append(round(document.spec.slopeCoverage))
                    .append("% target")
                    .append(" Y=").append(variant.achievedSlopeMinY)
                    .append("..").append(variant.achievedSlopeMaxY)
                    .append(" (requested ").append(document.spec.slopeMinY)
                    .append("..").append(document.spec.slopeMaxY).append(')')
                    .append(" rise ").append(variant.achievedSlopeMinRise)
                    .append("..").append(variant.achievedSlopeMaxRise)
                    .append("/").append(document.spec.slopeMinRise)
                    .append("..").append(document.spec.slopeMaxRise)
                    .append(" length ").append(variant.achievedSlopeMinLength)
                    .append("..").append(variant.achievedSlopeMaxLength)
                    .append("/").append(document.spec.slopeMinLength)
                    .append("..").append(document.spec.slopeMaxLength)
                    .append(" motifs ").append(variant.achievedSlopeMotifCount)
                    .append(" | complexity ").append(variant.profile.complexity)
                    .append(" Tier ").append(variant.profile.complexityProfile == null
                            ? CustomMapGenerator.complexityTier(variant.profile.complexity)
                            : variant.profile.complexityProfile.achievedTier)
                    .append('/').append(CustomMapGenerator.complexityTier(
                            variant.profile.complexity))
                    .append(' ').append(CustomMapGenerator.complexityTierName(
                            variant.profile.complexity))
                    .append(' ').append(poolName).append(' ').append(variant.waterZoneCount)
                    .append(" islands ").append(variant.floatingIslandCount)
                    .append('/').append(targetIslands).append(" requested")
                    .append(" layers ").append(variant.floatingIslandLayerCount)
                    .append('/').append(targetIslandLayers)
                    .append(" objects ").append(variant.objectCount)
                    .append(" enemies ").append(variant.enemies.size());
            if (variant.profile.complexityProfile != null) {
                CustomMapDocument.ComplexityProfile profile =
                        variant.profile.complexityProfile;
                sb.append(" | score ").append(round(profile.structuralScore))
                        .append('/').append(round(profile.targetScore))
                        .append(" span ").append(profile.elevationSpanRows)
                        .append(" object clusters ").append(profile.objectClusterCount)
                        .append(" gaps/steps ").append(profile.chasmStepGroupCount);
                if (profile.capReason != null && !profile.capReason.trim().isEmpty())
                    sb.append(" | ").append(profile.capReason);
            }
        }
        appendPatrolStatus(sb, "BCU stage", document.battleTerrain);
        if (document.variants != null)
            for (ModeVariant variant : document.variants.values())
                appendPatrolStatus(sb, variant == null ? "variant" : variant.mode, variant);
        sb.append("\nAssets: ").append(set.detectionSummary());
        sb.append(slopeAvailabilityNote(set));
        status.setText(sb.toString());
    }

    private String slopeAvailabilityNote(TileCatalog.TileSet set) {
        int value = ((Number) slopeCount.getValue()).intValue();
        double coverage = ((Number) slopeCoverage.getValue()).doubleValue();
        TileCatalog.TileSet slopeTiles = set == null ? null
                : set.resolveBaseGroundFamily(((Number) seed.getValue()).longValue());
        return (value > 0 || coverage > 0.0)
                && slopeTiles != null && !slopeTiles.supportsSlopes()
                ? "\nNo bidirectional slope PNG contour was detected; the main enemy route will stay flat."
                : "";
    }

    private void appendPatrolStatus(StringBuilder sb, String label, ModeVariant variant) {
        if (variant == null || variant.secondaryPlatforms == null) return;
        int enabled = 0;
        ArrayList<String> errors = new ArrayList<String>();
        CustomMapPreviewPanel preview = variant == document.battleTerrain
                ? battlePreview : previews.get(MapMode.fromId(variant.mode));
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null || platform.patrol == null || !platform.patrol.enabled) continue;
            enabled++;
            String issue = preview == null ? "" : preview.patrolValidationMessage(platform);
            if (!issue.isEmpty()) errors.add(safePlatformId(platform) + ": " + issue);
        }
        if (enabled == 0) return;
        sb.append('\n').append(label).append(" moving islands: ").append(enabled);
        if (errors.isEmpty()) sb.append(" | swept path validation OK");
        else for (String error : errors) sb.append("\n  ERROR ").append(error);
    }

    private void updateHeightControls() {
        if (adjustingHeightControls) return;
        adjustingHeightControls = true;
        try {
            int mapHeight = ((Number) height.getValue()).intValue();
            int maximumTerrainY = Math.min(CustomMapGenerator.MAX_GROUND_HEIGHT,
                    mapHeight);
            int low = Math.max(2, Math.min(maximumTerrainY,
                    ((Number) slopeMinY.getValue()).intValue()));
            int high = Math.max(low, Math.min(maximumTerrainY,
                    ((Number) slopeMaxY.getValue()).intValue()));
            slopeMinY.setModel(new SpinnerNumberModel(low, 2, maximumTerrainY, 1));
            slopeMaxY.setModel(new SpinnerNumberModel(high, 2, maximumTerrainY, 1));

            int maximumLayers = CustomMapGenerator.maxFloatingIslandLayers(mapHeight);
            int islandCount = ((Number) floatingIslands.getValue()).intValue();
            int layers = Math.max(0, Math.min(maximumLayers,
                    ((Number) floatingIslandLayers.getValue()).intValue()));
            if (islandCount == 0) layers = 0;
            else layers = Math.min(layers, islandCount);
            floatingIslandLayers.setModel(new SpinnerNumberModel(
                    layers, 0, maximumLayers, 1));
            floatingIslandLayers.setToolTipText("Number of distinct island height tiers. A "
                    + mapHeight + "-tile map supports at most " + maximumLayers
                    + " tier(s); 0 disables all floating islands.");
        } finally {
            adjustingHeightControls = false;
        }
    }

    private void updateComplexityTierLabel() {
        int value = ((Number) complexity.getValue()).intValue();
        complexityTier.setText("Tier " + CustomMapGenerator.complexityTier(value)
                + " - " + CustomMapGenerator.complexityTierName(value));
    }

    private TileCatalog.TileSet selectedTiles() {
        return (TileCatalog.TileSet) biome.getSelectedItem();
    }

    private boolean selectBiome(String value) {
        if (value == null) return false;
        for (int i = 0; i < biome.getItemCount(); i++) {
            TileCatalog.TileSet set = biome.getItemAt(i);
            if (sameBiome(set.biome, value)) {
                biome.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    private boolean selectedBiomeMatches(String value) {
        TileCatalog.TileSet set = selectedTiles();
        return set != null && sameBiome(set.biome, value);
    }

    private static boolean sameBiome(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    static boolean isLavaTheme(TileCatalog.TileSet set) {
        return set != null && set.isLavaTheme();
    }

    static String liquidDensityLabelText(TileCatalog.TileSet set) {
        return isLavaTheme(set) ? "Lava %" : "Water %";
    }

    static String liquidDensityTooltip(TileCatalog.TileSet set) {
        boolean lava = isLavaTheme(set);
        boolean available = set != null && !set.water.isEmpty();
        if (available) return lava
                ? "Lava density for this theme. Lava remains traversable through SWIM routes and applies the theme damage profile."
                : "Water density for this theme.";
        return lava
                ? "This theme has no detected lava tile; Lava % is fixed at 0."
                : "This theme has no detected water tile; Water % is fixed at 0.";
    }

    static String liquidStatusName(TileCatalog.TileSet set) {
        return isLavaTheme(set) ? "lava" : "water";
    }

    static String liquidPoolStatusName(TileCatalog.TileSet set) {
        return isLavaTheme(set) ? "lava pools" : "pools";
    }

    static String liquidTraversalStatus(TileCatalog.TileSet set) {
        return isLavaTheme(set)
                ? " | lava pools use SWIM traversal; dry gaps use unit jumping"
                : " | rivers are swimmable; dry gaps use unit jumping";
    }

    static String slopeCountTooltip(TileCatalog.TileSet set, boolean available) {
        return available
                ? "Requested complete slope runs; width, " + liquidStatusName(set)
                + ", bases and the Y envelope determine the closest legal result."
                : "This theme has no valid bidirectional slope contour; Slope count is fixed at 0.";
    }

    private static long round(double value) { return Math.round(value); }

    private void showError(String title, Throwable t) {
        Logger.err("CustomMap: " + title, t);
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) message = t.toString();
        status.setText(title + ":\n" + message);
        JOptionPane.showMessageDialog(dialog, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private static void add(JPanel panel, int row, String label, Component component) {
        add(panel, row, new JLabel(label), component);
    }

    private static void add(JPanel panel, int row, Component label, Component component) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0; left.gridy = row; left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(3, 4, 3, 7);
        panel.add(label, left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1; right.gridy = row; right.weightx = 1; right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(3, 2, 3, 4);
        panel.add(component, right);
    }

    private static void addPair(JPanel panel, int row, String aLabel, Component a,
                                String bLabel, Component b) {
        JPanel pair = new JPanel(new GridBagLayout());
        GridBagConstraints first = new GridBagConstraints();
        first.gridx = 0; first.gridy = 0; first.weightx = 1; first.fill = GridBagConstraints.HORIZONTAL;
        pair.add(a, first);
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 1; label.gridy = 0; label.insets = new Insets(0, 8, 0, 5);
        pair.add(new JLabel(bLabel), label);
        GridBagConstraints second = new GridBagConstraints();
        second.gridx = 2; second.gridy = 0; second.weightx = 1; second.fill = GridBagConstraints.HORIZONTAL;
        pair.add(b, second);
        add(panel, row, aLabel, pair);
    }

    private static final class IslandChoice {
        final int index;
        final SecondaryPlatform platform;

        IslandChoice(int index, SecondaryPlatform platform) {
            this.index = index;
            this.platform = platform;
        }

        @Override public String toString() {
            if (platform == null) return "No island";
            String state = platform.patrol != null && platform.patrol.enabled
                    ? "moving" : "static";
            String id = platform.id == null || platform.id.trim().isEmpty()
                    ? platform.startX + "-" + platform.endX : platform.id;
            return "Island " + index + " | " + id + " | X " + platform.startX + "-"
                    + platform.endX + " | " + state;
        }
    }
}
