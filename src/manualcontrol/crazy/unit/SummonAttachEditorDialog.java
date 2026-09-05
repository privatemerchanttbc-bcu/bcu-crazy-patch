package manualcontrol.crazy.unit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import common.pack.Identifier;
import common.util.Data;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.anim.MaModel;
import common.util.unit.Form;
import common.util.unit.Unit;

import manualcontrol.Logger;
import manualcontrol.crazy.unit.SummonAttachFeature.Cfg;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SummonAttachEditorDialog {

    private SummonAttachEditorDialog() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PREVIEW_W = 460;
    private static final int PREVIEW_H = 380;

    public static void open(Object formObj) {
        try {
            if (!(formObj instanceof Form)) return;
            new Editor((Form) formObj).show();
        } catch (Throwable t) {
            Logger.err("summon-attach: editor dialog failed", t);
            JOptionPane.showMessageDialog(null, "Summon Attach editor error: " + t,
                    "Summon Attach", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class Editor {

        private final Form form;
        private final String base;
        private final int atkCount;
        private final Map<Integer, Cfg> edits = new HashMap<Integer, Cfg>();

        private final JComboBox<String> atkBox;
        private final JCheckBox enabled = new JCheckBox("Enable attach mode for this attack");
        private final JComboBox<String> animBox = new JComboBox<String>();
        private final JComboBox<String> partBox = new JComboBox<String>();
        private final JSpinner summonFrame = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
        private final JSpinner releaseFrame = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
        private final JSpinner offsetX = new JSpinner(new SpinnerNumberModel(0.0, -999.0, 999.0, 1.0));
        private final JSpinner offsetY = new JSpinner(new SpinnerNumberModel(0.0, -999.0, 999.0, 1.0));
        private final JSpinner scale = new JSpinner(new SpinnerNumberModel(1.0, 0.05, 10.0, 0.05));
        private final JSpinner gravity = new JSpinner(new SpinnerNumberModel(1.4, 0.05, 20.0, 0.1));
        private final JComboBox<String> ghostBox = new JComboBox<String>();
        private final JSlider frameSlider = new JSlider(0, 1, 0);
        private final JLabel preview = new JLabel();
        private final JLabel info = new JLabel(" ");

        private int current;
        private boolean loading;

        Editor(Form f) {
            form = f;
            base = f.uid.pack + "/" + f.uid.id + "/" + f.fid;
            int n = 1;
            try { n = Math.max(1, f.du.getAtkCount()); } catch (Throwable ignored) {}
            atkCount = n;

            String[] atks = new String[atkCount];
            for (int i = 0; i < atkCount; i++) atks[i] = "Attack " + (i + 1);
            atkBox = new JComboBox<String>(atks);

            for (AnimU.UType t : AnimU.UType.values()) {
                animBox.addItem(t.name());
                ghostBox.addItem(t.name());
            }

            Map<String, Cfg> all = readAll();
            for (int i = 0; i < atkCount; i++) {
                Cfg c = all.get(base + "/" + i);
                edits.put(Integer.valueOf(i), c != null ? c : new Cfg());
            }
            current = 0;
        }

        void show() {
            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            left.add(row("Attack", atkBox));
            left.add(enabled);
            left.add(row("Animation", animBox));
            left.add(row("Part", partBox));
            left.add(row("Summon frame", summonFrame));
            left.add(row("Release frame", releaseFrame));
            left.add(row("Offset X", offsetX));
            left.add(row("Offset Y", offsetY));
            left.add(row("Scale", scale));
            left.add(row("Fall gravity", gravity));
            left.add(row("Carried animation", ghostBox));
            left.add(Box.createVerticalGlue());

            JPanel right = new JPanel(new BorderLayout());
            preview.setPreferredSize(new Dimension(PREVIEW_W, PREVIEW_H));
            preview.setBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY));
            right.add(preview, BorderLayout.CENTER);
            JPanel south = new JPanel(new BorderLayout());
            south.add(frameSlider, BorderLayout.CENTER);
            south.add(info, BorderLayout.SOUTH);
            right.add(south, BorderLayout.SOUTH);

            JPanel root = new JPanel(new BorderLayout(10, 0));
            root.add(left, BorderLayout.WEST);
            root.add(right, BorderLayout.CENTER);
            root.add(new JLabel("Form " + base
                    + "   (attach mode overrides the Summon proc distance)"), BorderLayout.NORTH);

            attachListeners();
            loadInto(current);

            int res = JOptionPane.showConfirmDialog(null, root,
                    "Summon Attach to Part - " + base,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;
            captureFrom(current);
            save();
        }

        private Component row(String label, Component c) {
            JPanel p = new JPanel(new GridLayout(1, 2, 6, 0));
            p.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            p.setMaximumSize(new Dimension(320, 30));
            p.add(new JLabel(label));
            p.add(c);
            return p;
        }

        private void attachListeners() {
            atkBox.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (loading) return;
                    captureFrom(current);
                    current = Math.max(0, atkBox.getSelectedIndex());
                    loadInto(current);
                }
            });
            ActionListener redraw = new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (loading) return;
                    if (e.getSource() == animBox) rebuildParts();
                    refresh();
                }
            };
            animBox.addActionListener(redraw);
            partBox.addActionListener(redraw);
            ghostBox.addActionListener(redraw);
            ChangeListener ch = new ChangeListener() {
                @Override
                public void stateChanged(ChangeEvent e) {
                    if (loading) return;
                    refresh();
                }
            };
            summonFrame.addChangeListener(ch);
            releaseFrame.addChangeListener(ch);
            offsetX.addChangeListener(ch);
            offsetY.addChangeListener(ch);
            scale.addChangeListener(ch);
            frameSlider.addChangeListener(ch);
        }

        private void loadInto(int ind) {
            loading = true;
            try {
                Cfg c = edits.get(Integer.valueOf(ind));
                if (c == null) c = new Cfg();
                atkBox.setSelectedIndex(ind);
                enabled.setSelected(c.enabled && hasAnyValue(c));
                animBox.setSelectedItem(c.anim);
                if (animBox.getSelectedIndex() < 0) animBox.setSelectedItem("ATK");
                rebuildParts();
                if (c.part >= 0 && c.part < partBox.getItemCount()) partBox.setSelectedIndex(c.part);
                summonFrame.setValue(Integer.valueOf(c.summonFrame));
                releaseFrame.setValue(Integer.valueOf(c.releaseFrame));
                offsetX.setValue(Double.valueOf(c.offsetX));
                offsetY.setValue(Double.valueOf(c.offsetY));
                scale.setValue(Double.valueOf(c.scale));
                gravity.setValue(Double.valueOf(c.gravity));
                ghostBox.setSelectedItem(c.ghostAnim);
                if (ghostBox.getSelectedIndex() < 0) ghostBox.setSelectedItem("WALK");
            } finally {
                loading = false;
            }
            refresh();
        }

        private void captureFrom(int ind) {
            Cfg c = new Cfg();
            c.enabled = enabled.isSelected();
            c.anim = String.valueOf(animBox.getSelectedItem());
            c.part = Math.max(0, partBox.getSelectedIndex());
            c.summonFrame = intOf(summonFrame);
            c.releaseFrame = intOf(releaseFrame);
            c.offsetX = (float) doubleOf(offsetX);
            c.offsetY = (float) doubleOf(offsetY);
            c.scale = (float) doubleOf(scale);
            c.gravity = (float) doubleOf(gravity);
            c.ghostAnim = String.valueOf(ghostBox.getSelectedItem());
            edits.put(Integer.valueOf(ind), c);
        }

        private void rebuildParts() {
            int keep = partBox.getSelectedIndex();
            partBox.removeAllItems();
            EAnimU anim = animOf();
            if (anim == null || anim.ent == null) {
                partBox.addItem("0: part 0");
                frameSlider.setMaximum(1);
                return;
            }
            MaModel model = null;
            try {
                if (anim.ent.length > 0 && anim.ent[0] != null) model = anim.ent[0].getModel();
            } catch (Throwable ignored) {}
            for (int i = 0; i < anim.ent.length; i++) {
                String name = "";
                try {
                    if (model != null && model.strs0 != null && i < model.strs0.length) name = model.strs0[i];
                } catch (Throwable ignored) {}
                partBox.addItem(i + ": " + (name == null || name.isEmpty() ? "part" : name));
            }
            if (keep >= 0 && keep < partBox.getItemCount()) partBox.setSelectedIndex(keep);
            int len = Math.max(1, anim.len() - 1);
            frameSlider.setMaximum(len);
            ((SpinnerNumberModel) summonFrame.getModel()).setMaximum(Integer.valueOf(len));
            ((SpinnerNumberModel) releaseFrame.getModel()).setMaximum(Integer.valueOf(len));
        }

        private EAnimU animOf() {
            try {
                AnimU.UType t = AnimU.UType.valueOf(String.valueOf(animBox.getSelectedItem()));
                return form.getEAnim(t);
            } catch (Throwable t) {
                return null;
            }
        }

        private Form ghostForm() {
            try {
                Data.Proc.SUMMON s = form.du.getAtkModel(current).getProc().SUMMON;
                if (s == null || s.id == null || s.id.cls != Unit.class) return null;
                Unit u = Identifier.getOr(s.id, Unit.class);
                if (u == null || u.forms == null || u.forms.length == 0) return null;
                int fi = Math.max(0, Math.min(u.forms.length - 1, s.form - 1));
                return u.forms[fi];
            } catch (Throwable t) {
                return null;
            }
        }

        private void refresh() {
            try {
                AnimU.UType type;
                AnimU.UType gt;
                try {
                    type = AnimU.UType.valueOf(String.valueOf(animBox.getSelectedItem()));
                } catch (Throwable t) {
                    type = AnimU.UType.ATK;
                }
                try {
                    gt = AnimU.UType.valueOf(String.valueOf(ghostBox.getSelectedItem()));
                } catch (Throwable t) {
                    gt = AnimU.UType.WALK;
                }
                Form ghost = ghostForm();
                int frame = frameSlider.getValue();
                BufferedImage img = SummonAttachPreview.render(form, type, frame,
                        Math.max(0, partBox.getSelectedIndex()), ghost, gt,
                        (float) doubleOf(scale), (float) doubleOf(offsetX), (float) doubleOf(offsetY),
                        PREVIEW_W, PREVIEW_H);
                preview.setIcon(img == null ? null : new ImageIcon(img));
                String gname = ghost == null ? "none (attack has no unit Summon proc)" : ghost.uid.pack
                        + "/" + ghost.uid.id + " form " + ghost.fid;
                info.setText("frame " + frame + "   summoned: " + gname);
            } catch (Throwable t) {
                Logger.err("summon-attach: preview failed", t);
            }
        }

        private void save() {
            try {
                Map<String, Cfg> all = readAll();
                for (int i = 0; i < atkCount; i++) {
                    Cfg c = edits.get(Integer.valueOf(i));
                    String key = base + "/" + i;
                    if (c != null && c.enabled) all.put(key, c);
                    else all.remove(key);
                }
                writeAll(all);
                SummonAttachFeature.invalidate();
                JOptionPane.showMessageDialog(null,
                        "Saved to " + SummonAttachFeature.configFile()
                                + "\nApplies from the next battle.",
                        "Summon Attach", JOptionPane.INFORMATION_MESSAGE);
            } catch (Throwable t) {
                Logger.err("summon-attach: save failed", t);
                JOptionPane.showMessageDialog(null, "Summon Attach save error: " + t,
                        "Summon Attach", JOptionPane.ERROR_MESSAGE);
            }
        }

        private boolean hasAnyValue(Cfg c) {
            return c.releaseFrame > 0 || c.summonFrame > 0 || c.part > 0;
        }

        private int intOf(JSpinner s) {
            Object v = s.getValue();
            return (v instanceof Number) ? ((Number) v).intValue() : 0;
        }

        private double doubleOf(JSpinner s) {
            Object v = s.getValue();
            return (v instanceof Number) ? ((Number) v).doubleValue() : 0.0;
        }
    }

    static Map<String, Cfg> readAll() {
        File file = SummonAttachFeature.configFile();
        if (!file.isFile()) return new LinkedHashMap<String, Cfg>();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type type = new TypeToken<LinkedHashMap<String, Cfg>>() {}.getType();
            Map<String, Cfg> map = GSON.fromJson(reader, type);
            return map == null ? new LinkedHashMap<String, Cfg>() : map;
        } catch (Throwable t) {
            Logger.err("summon-attach: failed reading " + file, t);
            return new LinkedHashMap<String, Cfg>();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    static void writeAll(Map<String, Cfg> all) {
        File file = SummonAttachFeature.configFile();
        Writer w = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            GSON.toJson(all, w);
        } catch (Throwable t) {
            Logger.err("summon-attach: failed writing " + file, t);
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }
}
