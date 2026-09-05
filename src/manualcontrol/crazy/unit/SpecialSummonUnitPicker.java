package manualcontrol.crazy.unit;

import common.pack.PackData;
import common.pack.UserProfile;
import common.util.unit.Form;
import common.util.unit.Unit;

import manualcontrol.Logger;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SpecialSummonUnitPicker {

    private static final String[] FORM_TAGS = {"f", "c", "s", "u", "z"};

    private SpecialSummonUnitPicker() {}

    static Form pick() {
        final List<Form> all = collectForms();

        final DefaultListModel<Form> model = new DefaultListModel<Form>();
        for (Form f : all) model.addElement(f);

        final JList<Form> list = new JList<Form>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, index, sel, focus);
                if (value instanceof Form) setText(label((Form) value));
                return this;
            }
        });

        final JTextField search = new JTextField();
        search.setToolTipText("Type to filter by name or pack-id");

        final JLabel preview = new JLabel("", JLabel.CENTER);
        preview.setPreferredSize(new Dimension(140, 160));
        preview.setBorder(BorderFactory.createLineBorder(new Color(90, 90, 90)));
        preview.setBackground(new Color(46, 52, 60));
        preview.setOpaque(true);

        list.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) return;
                Form f = list.getSelectedValue();
                BufferedImage img = f == null ? null : SpecialSummonPreview.render(f, 140, 160);
                preview.setIcon(img == null ? null : new ImageIcon(img));
                preview.setText(img == null && f != null ? "(no preview)" : "");
            }
        });

        search.getDocument().addDocumentListener(new DocumentListener() {
            private void refilter() {
                String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
                Form keep = list.getSelectedValue();
                model.clear();
                for (Form f : all) {
                    if (q.isEmpty() || label(f).toLowerCase(Locale.ROOT).contains(q)) model.addElement(f);
                }
                if (keep != null && model.contains(keep)) list.setSelectedValue(keep, true);
            }
            @Override public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter(); }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(360, 380));

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(search, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(preview, BorderLayout.EAST);

        int res = JOptionPane.showConfirmDialog(null, panel, "Pick a unit to summon",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return null;
        return list.getSelectedValue();
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
        try { pack = f.unit.id.pack + "/" + f.unit.id.id; } catch (Throwable ignored) {}
        return "[" + pack + " " + tag + "] " + name;
    }

    private static List<Form> collectForms() {
        ArrayList<Form> out = new ArrayList<Form>();
        Set<String> seen = new HashSet<String>();
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
                    if (!seen.add(uk)) continue;
                    for (Form f : u.forms) {
                        if (f != null && f.du != null && f.unit != null) out.add(f);
                    }
                }
            }
        } catch (Throwable t) {
            Logger.err("special-summon: unit enumeration failed", t);
        }
        return out;
    }
}
