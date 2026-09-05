package manualcontrol;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

public final class FeatureSelectDialog {

    public static final String PROPERTY = "manualcontrol.features";
    private static final String FILE_NAME = "crazy-features.properties";

    private static final boolean HAS_ADVENTURE = OptionalFeatures.present("manualcontrol.adventure.AdventureHooks");
    private static final boolean HAS_HYBRID = OptionalFeatures.present("unitcreator.UnitCreatorHooks");
    private static final boolean HAS_DIRECT_EDIT = OptionalFeatures.present("manualcontrol.animedit.DirectEditHooks");
    private static final boolean HAS_MINI_MODES = OptionalFeatures.present("manualcontrol.modes.core.ModesHooks");
    private static final boolean HAS_CUSTOM_MAPS = OptionalFeatures.present("manualcontrol.custommap.CustomMapHooks");
    private static final boolean HAS_ARENA = OptionalFeatures.present("manualcontrol.arena.ArenaHooks");
    private static final boolean HAS_SPEED_SCALE = OptionalFeatures.present("manualcontrol.speedscale.SpeedScaleHooks");
    private static final boolean HAS_NEW_PROC = OptionalFeatures.present("manualcontrol.newproc.NewProcs");
    private static final boolean HAS_ATTACK_FORM = OptionalFeatures.present("manualcontrol.attackform.AttackFormFeature");

    private FeatureSelectDialog() {}

    public static void prompt() {
        try {
            String override = trimmed(System.getProperty(PROPERTY));
            boolean forceDialog = "ask".equalsIgnoreCase(override);
            if (override != null && !forceDialog) {
                applyList(override);
                finish("-D" + PROPERTY + "=" + override);
                return;
            }
            if (GraphicsEnvironment.isHeadless()) {
                enableSafeDefaults();
                finish("headless");
                return;
            }
            Properties saved = load();
            if (!forceDialog && saved != null
                    && Boolean.parseBoolean(saved.getProperty("skipDialog", "false"))) {
                applySaved(saved);
                finish("remembered - delete " + FILE_NAME
                        + " or launch with -D" + PROPERTY + "=ask to choose again");
                return;
            }
            final Properties initial = saved;
            if (SwingUtilities.isEventDispatchThread()) show(initial);
            else SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { show(initial); }
            });
            finish("dialog");
        } catch (Throwable t) {
            Logger.err("FeatureSelect: dialog failed, enabling safe defaults", t);
            enableSafeDefaults();
            finish("fallback");
        }
    }

    private static void clampToAvailable() {
        if (!HAS_ADVENTURE) FeatureFlags.adventure = false;
        if (!HAS_HYBRID) FeatureFlags.hybrid = false;
        if (!HAS_DIRECT_EDIT) FeatureFlags.directEdit = false;
        if (!HAS_MINI_MODES) FeatureFlags.miniModes = false;
        if (!HAS_CUSTOM_MAPS) FeatureFlags.customMaps = false;
        if (!HAS_ARENA) FeatureFlags.arena = false;
        if (!HAS_SPEED_SCALE) FeatureFlags.speedScale = false;
        if (!HAS_NEW_PROC) FeatureFlags.newProc = false;
        if (!HAS_ATTACK_FORM) FeatureFlags.switchAttackForm = false;
    }

    private static void finish(String source) {
        clampToAvailable();
        System.setProperty("unitcreator.enabled", String.valueOf(FeatureFlags.hybrid));
        Logger.log("Features chosen (" + source + "): crazy=" + FeatureFlags.crazy
                + " adventure=" + FeatureFlags.adventure + " hybrid=" + FeatureFlags.hybrid
                + " directEdit=" + FeatureFlags.directEdit + " miniModes=" + FeatureFlags.miniModes
                + " customMaps=" + FeatureFlags.customMaps
                + " arena=" + FeatureFlags.arena
                + " speedScale=" + FeatureFlags.speedScale
                + " newProc=" + FeatureFlags.newProc
                + " switchAttackForm=" + FeatureFlags.switchAttackForm);
    }

    private static void enableAll() {
        FeatureFlags.crazy = FeatureFlags.adventure = FeatureFlags.hybrid
                = FeatureFlags.directEdit = FeatureFlags.miniModes = FeatureFlags.customMaps
                = FeatureFlags.arena = FeatureFlags.speedScale = FeatureFlags.newProc
                = FeatureFlags.switchAttackForm = true;
    }

    private static void enableSafeDefaults() {
        FeatureFlags.crazy = FeatureFlags.adventure = FeatureFlags.hybrid
                = FeatureFlags.directEdit = FeatureFlags.miniModes = FeatureFlags.customMaps
                = FeatureFlags.arena = FeatureFlags.speedScale = FeatureFlags.newProc = true;
        FeatureFlags.switchAttackForm = false;
    }

    private static void applyList(String list) {
        if ("all".equalsIgnoreCase(list)) { enableAll(); return; }
        FeatureFlags.crazy = FeatureFlags.adventure = FeatureFlags.hybrid
                = FeatureFlags.directEdit = FeatureFlags.miniModes
                = FeatureFlags.customMaps = FeatureFlags.arena = FeatureFlags.speedScale
                = FeatureFlags.newProc = FeatureFlags.switchAttackForm = false;
        for (String raw : list.split(",")) {
            String token = raw.trim().toLowerCase();
            if (token.isEmpty() || "none".equals(token)) continue;
            if ("crazy".equals(token)) FeatureFlags.crazy = true;
            else if ("adventure".equals(token)) FeatureFlags.adventure = true;
            else if ("hybrid".equals(token)) FeatureFlags.hybrid = true;
            else if ("directedit".equals(token)) FeatureFlags.directEdit = true;
            else if ("minimodes".equals(token)) FeatureFlags.miniModes = true;
            else if ("custommaps".equals(token)) FeatureFlags.customMaps = true;
            else if ("arena".equals(token)) FeatureFlags.arena = true;
            else if ("speedscale".equals(token)) FeatureFlags.speedScale = true;
            else if ("newproc".equals(token)) FeatureFlags.newProc = true;
            else if ("switch_attack_form".equals(token)
                    || "switchattackform".equals(token) || "attackforms".equals(token))
                FeatureFlags.switchAttackForm = true;
            else Logger.log("FeatureSelect: unknown feature '" + raw.trim() + "' ignored");
        }
        enforceDependencies();
    }

    private static void applySaved(Properties saved) {
        FeatureFlags.crazy = flag(saved, "crazy");
        FeatureFlags.adventure = flag(saved, "adventure");
        FeatureFlags.hybrid = flag(saved, "hybrid");
        FeatureFlags.directEdit = flag(saved, "directEdit");
        FeatureFlags.miniModes = flag(saved, "miniModes");
        FeatureFlags.customMaps = flag(saved, "customMaps");
        FeatureFlags.arena = flag(saved, "arena");
        FeatureFlags.speedScale = flag(saved, "speedScale");
        FeatureFlags.newProc = flag(saved, "newProc");
        FeatureFlags.switchAttackForm = attackFormFlag(saved);
        enforceDependencies();
    }

    private static void enforceDependencies() {
        if (FeatureFlags.crazy) return;
        FeatureFlags.adventure = false;
        FeatureFlags.miniModes = false;
        FeatureFlags.customMaps = false;
        FeatureFlags.arena = false;
    }

    private static boolean flag(Properties saved, String key) {
        return flag(saved, key, true);
    }

    private static boolean flag(Properties saved, String key, boolean defaultValue) {
        return Boolean.parseBoolean(saved.getProperty(key, String.valueOf(defaultValue)));
    }

    private static boolean attackFormFlag(Properties saved) {
        if (saved.containsKey("switch_attack_form"))
            return flag(saved, "switch_attack_form", false);
        return flag(saved, "switchAttackForm", false);
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }

    static File prefsFile() {
        File base = null;
        try {
            File log = Logger.getLogFile();
            if (log != null) base = log.getParentFile();
        } catch (Throwable ignored) {}
        if (base == null) base = new File(System.getProperty("user.dir", "."));
        return new File(base, FILE_NAME);
    }

    private static Properties load() {
        File file = prefsFile();
        if (!file.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Properties props = new Properties();
            props.load(in);
            return props;
        } catch (Throwable t) {
            Logger.err("FeatureSelect: could not read " + file, t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void save(boolean skipDialog) {
        File file = prefsFile();
        OutputStream out = null;
        try {
            Properties props = new Properties();
            props.setProperty("crazy", String.valueOf(FeatureFlags.crazy));
            props.setProperty("adventure", String.valueOf(FeatureFlags.adventure));
            props.setProperty("hybrid", String.valueOf(FeatureFlags.hybrid));
            props.setProperty("directEdit", String.valueOf(FeatureFlags.directEdit));
            props.setProperty("miniModes", String.valueOf(FeatureFlags.miniModes));
            props.setProperty("customMaps", String.valueOf(FeatureFlags.customMaps));
            props.setProperty("arena", String.valueOf(FeatureFlags.arena));
            props.setProperty("speedScale", String.valueOf(FeatureFlags.speedScale));
            props.setProperty("newProc", String.valueOf(FeatureFlags.newProc));
            props.setProperty("switch_attack_form", String.valueOf(FeatureFlags.switchAttackForm));
            props.setProperty("skipDialog", String.valueOf(skipDialog));
            out = new FileOutputStream(file);
            props.store(out, "BCU Crazy Patch feature selection");
        } catch (Throwable t) {
            Logger.err("FeatureSelect: could not write " + file, t);
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static void addRow(java.util.List<Component> rows, JCheckBox box, boolean available) {
        if (!available) return;
        rows.add(Box.createVerticalStrut(6));
        rows.add(box);
    }

    private static void show(Properties saved) {
        final JCheckBox crazy = new JCheckBox("Crazy Patch  -  battle engine, manual control & crazy features",
                saved == null || flag(saved, "crazy"));
        final JCheckBox adv = new JCheckBox("Adventure Mode  -  action-platformer (requires Crazy Patch)",
                saved == null || flag(saved, "adventure"));
        final JCheckBox hyb = new JCheckBox("Hybrid / Unit Creator  -  combine 2 units into 1",
                saved == null || flag(saved, "hybrid"));
        final JCheckBox de = new JCheckBox("Direct Edit  -  drag-edit animation parts in the Animation Editor",
                saved == null || flag(saved, "directEdit"));
        final JCheckBox mm = new JCheckBox("Game Modes  -  5 mini game modes hub (requires Crazy Patch)",
                saved == null || flag(saved, "miniModes"));
        final JCheckBox cm = new JCheckBox("Custom Map Studio  -  tile map authoring (requires Crazy Patch)",
                saved == null || flag(saved, "customMaps"));
        final JCheckBox ar = new JCheckBox("Arena  -  AI vs AI team battles with evolving AI (requires Crazy Patch)",
                saved == null || flag(saved, "arena"));
        final JCheckBox ss = new JCheckBox("Speed Scale  -  retime a range of animation keyframes in the Animation Editor",
                saved == null || flag(saved, "speedScale"));
        final JCheckBox np = new JCheckBox("New Procs  -  Pulse Attack and Damage Itself in the unit edit table",
                saved == null || flag(saved, "newProc"));
        final JCheckBox saf = new JCheckBox(
                "Switch Attack Form  -  optional multi-form attack plugin",
                saved != null && attackFormFlag(saved));
        final JCheckBox remember = new JCheckBox(
                "Start with this selection from now on (skip this dialog)", false);

        adv.addActionListener(e -> { if (adv.isSelected()) crazy.setSelected(true); });
        mm.addActionListener(e -> { if (mm.isSelected()) crazy.setSelected(true); });
        cm.addActionListener(e -> { if (cm.isSelected()) crazy.setSelected(true); });
        ar.addActionListener(e -> { if (ar.isSelected()) crazy.setSelected(true); });
        crazy.addActionListener(e -> { if (!crazy.isSelected()) { adv.setSelected(false); mm.setSelected(false); cm.setSelected(false); ar.setSelected(false); } });

        JLabel title = new JLabel("BCU Crazy Patch - select features to enable");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        JLabel sub = new JLabel("<html>Tick the features to load. Unticked = <b>not loaded</b> with BCU.</html>");
        JLabel note = new JLabel("<html><i>Adventure Mode and Game Modes share Crazy Patch's engine, so they auto-enable Crazy.</i></html>");
        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        JLabel skipNote = new JLabel("<html><i>To bring this dialog back, delete "
                + FILE_NAME + " next to the log, or launch BCU with -D"
                + PROPERTY + "=ask</i></html>");
        skipNote.setFont(skipNote.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
        java.util.List<Component> rows = new java.util.ArrayList<Component>();
        rows.add(title);
        rows.add(Box.createVerticalStrut(4));
        rows.add(sub);
        rows.add(Box.createVerticalStrut(14));
        rows.add(crazy);
        addRow(rows, adv, HAS_ADVENTURE);
        addRow(rows, hyb, HAS_HYBRID);
        addRow(rows, de, HAS_DIRECT_EDIT);
        addRow(rows, mm, HAS_MINI_MODES);
        addRow(rows, cm, HAS_CUSTOM_MAPS);
        addRow(rows, ar, HAS_ARENA);
        addRow(rows, ss, HAS_SPEED_SCALE);
        addRow(rows, np, HAS_NEW_PROC);
        addRow(rows, saf, HAS_ATTACK_FORM);
        rows.add(Box.createVerticalStrut(12));
        rows.add(note);
        rows.add(Box.createVerticalStrut(10));
        rows.add(remember);
        rows.add(Box.createVerticalStrut(4));
        rows.add(skipNote);
        for (Component c : rows) {
            ((JComponent) c).setAlignmentX(0f);
            body.add(c);
        }

        final JDialog dlg = new JDialog((Frame) null, "BCU - Feature Select", true);
        JButton start = new JButton("Start BCU");
        start.addActionListener(e -> dlg.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(start);

        dlg.getContentPane().setLayout(new BorderLayout());
        dlg.getContentPane().add(body, BorderLayout.CENTER);
        dlg.getContentPane().add(south, BorderLayout.SOUTH);
        dlg.getRootPane().setDefaultButton(start);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dlg.setAlwaysOnTop(true);
        dlg.pack();
        dlg.setLocationRelativeTo(null);
        dlg.setVisible(true);

        FeatureFlags.crazy = crazy.isSelected();
        FeatureFlags.adventure = adv.isSelected() && crazy.isSelected();
        FeatureFlags.hybrid = hyb.isSelected();
        FeatureFlags.directEdit = de.isSelected();
        FeatureFlags.miniModes = mm.isSelected() && crazy.isSelected();
        FeatureFlags.customMaps = cm.isSelected() && crazy.isSelected();
        FeatureFlags.arena = ar.isSelected() && crazy.isSelected();
        FeatureFlags.speedScale = ss.isSelected();
        FeatureFlags.newProc = np.isSelected();
        FeatureFlags.switchAttackForm = saf.isSelected();
        save(remember.isSelected());
    }
}
