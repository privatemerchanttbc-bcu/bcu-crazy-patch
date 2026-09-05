package manualcontrol.adventure;

import common.battle.data.MaskEntity;
import common.pack.PackData;
import common.pack.UserProfile;
import common.util.Data;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.Logger;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapRepository;
import manualcontrol.custommap.CustomMapRuntime;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AdventureSetupDialog {

    private static final String[] FORM_TAGS = {"f", "c", "s", "u"};

    private AdventureSetupDialog() {}

    public static void show(Object mainPage) {
        show(mainPage, -1);
    }

    public static void show(Object mainPage, int slot) {
        final List<Form> all = collectAllForms();
        if (all.isEmpty()) {
            JOptionPane.showMessageDialog(mainPage instanceof Component ? (Component) mainPage : null,
                    "No player units found. If packs are still loading, try again in a moment.",
                    "Adventure Mode", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final boolean[] conjure = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) conjure[i] = hasConjure(all.get(i));

        final ArrayList<Form> visible = new ArrayList<Form>();
        final DefaultTableModel model = new DefaultTableModel(new Object[]{"Unit", "Conjure"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        final JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        java.awt.Font cellFont = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 15);
        table.setFont(cellFont);
        table.setRowHeight(30);
        table.setIntercellSpacing(new Dimension(8, 5));
        table.setShowGrid(true);
        table.getTableHeader().setFont(cellFont.deriveFont(java.awt.Font.BOLD, 16f));
        table.getTableHeader().setPreferredSize(new Dimension(10, 30));
        table.getColumnModel().getColumn(0).setPreferredWidth(440);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);

        final JTextField search = new JTextField();
        search.setFont(cellFont);
        search.setToolTipText("Type to filter by name or pack id");
        final Runnable refilter = new Runnable() {
            @Override public void run() {
                String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
                model.setRowCount(0);
                visible.clear();
                for (int i = 0; i < all.size(); i++) {
                    Form f = all.get(i);
                    if (!q.isEmpty() && !label(f).toLowerCase(Locale.ROOT).contains(q)) continue;
                    model.addRow(new Object[]{label(f), conjure[i] ? "✔ Conjure" : ""});
                    visible.add(f);
                }
                if (model.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
            }
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter.run(); }
        });

        final AdventureAnimPreview preview = new AdventureAnimPreview();
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = table.getSelectedRow();
            preview.setForm(r >= 0 && r < visible.size() ? visible.get(r) : null);
        });

        refilter.run();
        if (!visible.isEmpty()) preview.setForm(visible.get(0));

        final JSpinner lives = new JSpinner(
                new SpinnerNumberModel(AdventureRuntime.DEFAULT_LIVES, 1, 9, 1));
        final JComboBox<MapChoice> mapChoice = new JComboBox<MapChoice>(mapChoices().toArray(new MapChoice[0]));
        final javax.swing.JRadioButton casual = new javax.swing.JRadioButton("Casual", true);
        final javax.swing.JRadioButton ironman = new javax.swing.JRadioButton("Ironman");
        casual.setToolTipText("On death: keep a checkpoint - reload and retry the stage.");
        ironman.setToolTipText("Permadeath: on death the save slot is deleted.");
        javax.swing.ButtonGroup modeGroup = new javax.swing.ButtonGroup();
        modeGroup.add(casual);
        modeGroup.add(ironman);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(new JLabel("Units: " + all.size() + "    Lives:"));
        bottom.add(lives);
        bottom.add(new JLabel("   Mode:"));
        bottom.add(casual);
        bottom.add(ironman);
        bottom.add(new JLabel("   Map:"));
        bottom.add(mapChoice);

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(search, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(600, 620));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(preview, BorderLayout.EAST);
        panel.add(bottom, BorderLayout.SOUTH);

        Component parent = mainPage instanceof Component ? (Component) mainPage : null;
        preview.start();
        int ok;
        try {
            ok = JOptionPane.showConfirmDialog(parent, panel,
                    "Adventure Mode - pick your unit", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
        } finally {
            preview.stop();
        }
        if (ok != JOptionPane.OK_OPTION) return;

        int row = table.getSelectedRow();
        if (row < 0 || row >= visible.size()) return;
        Form sel = visible.get(row);
        if (sel == null) return;
        int livesVal = ((Number) lives.getValue()).intValue();
        String mode = ironman.isSelected()
                ? AdventureSaveData.MODE_IRONMAN : AdventureSaveData.MODE_CASUAL;
        Logger.log("Adventure: setup chose " + label(sel) + " lives=" + livesVal
                + " slot=" + slot + " mode=" + mode);
        try {
            MapChoice choice = (MapChoice) mapChoice.getSelectedItem();
            if (choice == null || choice.uuid == null) {
                CustomMapRuntime.clearPending();
            } else {
                if (choice.error != null) throw new IllegalArgumentException(choice.error);
                CustomMapRuntime.prepare(choice.uuid, CustomMapDocument.MapMode.ADVENTURE);
            }
            AdventureRuntime.begin(sel, levelFor(sel), livesVal, mainPage, slot, mode);
        } catch (Throwable problem) {
            JOptionPane.showMessageDialog(parent, problem.getMessage(), "Custom Map",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static List<MapChoice> mapChoices() {
        ArrayList<MapChoice> choices = new ArrayList<MapChoice>();
        choices.add(new MapChoice(null, "Normal campaign", null));
        for (CustomMapRepository.MapRecord record : CustomMapRepository.list()) {
            try {
                CustomMapDocument doc = CustomMapRepository.load(record.uuid);
                if (doc != null && doc.spec != null
                        && doc.spec.supports(CustomMapDocument.MapMode.ADVENTURE))
                    choices.add(new MapChoice(record.uuid, record.name, null));
            } catch (Throwable t) {
                choices.add(new MapChoice(record.uuid, record.name + " (invalid metadata)",
                        t.getMessage() == null ? "This map has invalid metadata." : t.getMessage()));
            }
        }
        return choices;
    }

    private static final class MapChoice {
        final String uuid;
        final String label;
        final String error;
        MapChoice(String uuid, String label, String error) {
            this.uuid = uuid;
            this.label = label == null ? "Custom Map" : label;
            this.error = error;
        }
        @Override public String toString() { return label; }
    }

    static boolean hasConjure(Form f) {
        try {
            if (f == null) return false;
            MaskEntity du = f.du;
            if (du == null) return false;
            Data.Proc p = du.getProc();
            return p != null && p.SPIRIT != null && p.SPIRIT.exists();
        } catch (Throwable ignored) {}
        return false;
    }

    static List<Form> collectAllForms() {
        ArrayList<Form> out = new ArrayList<Form>();
        Set<String> seenUnits = new HashSet<String>();
        try {
            for (PackData pack : UserProfile.getAllPacks()) {
                if (pack == null || pack.units == null) continue;
                List<Unit> units;
                try {
                    units = pack.units.getList();
                } catch (Throwable t) {
                    continue;
                }
                if (units == null) continue;
                for (Unit u : units) {
                    if (u == null || u.id == null || u.forms == null) continue;
                    String uk = u.id.pack + ":" + u.id.id;
                    if (!seenUnits.add(uk)) continue;
                    for (Form f : u.forms) {
                        if (f != null && f.du != null && f.unit != null) out.add(f);
                    }
                }
            }
        } catch (Throwable t) {
            Logger.err("Adventure: unit enumeration failed", t);
        }
        Logger.log("Adventure: collected " + out.size() + " forms from all packs");
        return out;
    }

    static Level levelFor(Form form) {
        Level level;
        try {
            level = form.unit.getPrefLvs().clone();
        } catch (Throwable ignored) {
            level = new Level(30);
        }
        try {
            if (level.getOrbs() == null) level.setOrbs(new int[0][]);
        } catch (Throwable ignored) {}
        return level;
    }

    static String label(Form f) {
        String name = null;
        try {
            if (f.names != null) name = f.names.toString();
            if (name == null || name.trim().isEmpty()) name = f.name;
        } catch (Throwable ignored) {}
        if (name == null || name.trim().isEmpty()) name = String.valueOf(f);
        String tag = f.fid >= 0 && f.fid < FORM_TAGS.length ? FORM_TAGS[f.fid] : "?";
        String pack = "";
        try { pack = f.unit.id.pack + "-" + f.unit.id.id; } catch (Throwable ignored) {}
        return "[" + pack + " " + tag + "] " + name;
    }
}
