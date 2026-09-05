package manualcontrol.crazy.unit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import common.pack.Identifier;
import common.util.unit.Form;
import common.util.unit.Unit;

import manualcontrol.Logger;
import manualcontrol.crazy.unit.SpecialSummonFeature.EntryJson;
import manualcontrol.crazy.unit.SpecialSummonFeature.SummonJson;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import java.awt.BorderLayout;
import java.awt.Color;
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
import java.util.LinkedHashMap;
import java.util.Map;

public final class SpecialSummonEditorDialog {

    private SpecialSummonEditorDialog() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void open(Object formObj) {
        try {
            if (!(formObj instanceof Form)) return;
            Form form = (Form) formObj;
            String key = form.uid.pack + "/" + form.uid.id + "/" + form.fid;

            Map<String, EntryJson> all = readAll();
            EntryJson current = all.get(key);
            String copiedFrom = null;
            if (current == null) {
                String prefix = form.uid.pack + "/" + form.uid.id + "/";
                for (Map.Entry<String, EntryJson> en : all.entrySet()) {
                    if (en.getValue() != null && en.getKey().startsWith(prefix)) {
                        current = en.getValue();
                        copiedFrom = en.getKey();
                        break;
                    }
                }
            }
            Logger.log("special-summon editor: key=" + key + " entries=" + all.size()
                    + " keys=" + all.keySet() + " own=" + (all.get(key) != null)
                    + " copiedFrom=" + copiedFrom);

            SummonPanel hit = new SummonPanel("On HIT (non-lethal connect) - Unit A");
            SummonPanel kill = new SummonPanel("On KILL (killing blow) - Unit B");
            SummonPanel miss = new SummonPanel("On MISS (whiff, no target) - Unit C");
            if (current != null) {
                hit.load(current.hit);
                kill.load(current.kill);
                miss.load(current.miss);
            }

            JPanel root = new JPanel();
            root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
            String headText = "Form " + key + "   (chance 0 = that outcome is off)";
            if (copiedFrom != null) {
                headText = "Form " + key + "   (template copied from " + copiedFrom
                        + " - OK saves to THIS form only, does not touch " + copiedFrom + ")";
            }
            JLabel head = new JLabel(headText);
            head.setAlignmentX(Component.LEFT_ALIGNMENT);
            root.add(head);
            root.add(hit.panel);
            root.add(kill.panel);
            root.add(miss.panel);

            int res = JOptionPane.showConfirmDialog(null, root,
                    "Special Summon - " + key,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;

            EntryJson entry = new EntryJson();
            entry.hit = hit.toJson();
            entry.kill = kill.toJson();
            entry.miss = miss.toJson();

            if (entry.hit != null || entry.kill != null || entry.miss != null) {
                all.put(key, entry);
            } else {
                all.remove(key);
            }
            writeAll(all);
            SpecialSummonFeature.invalidate();
            JOptionPane.showMessageDialog(null,
                    "Saved to " + SpecialSummonFeature.configFile()
                            + "\nApplies from the next battle.",
                    "Special Summon", JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable t) {
            Logger.err("special-summon: editor dialog failed", t);
            JOptionPane.showMessageDialog(null, "Special Summon editor error: " + t,
                    "Special Summon", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Map<String, EntryJson> readAll() {
        File f = SpecialSummonFeature.configFile();
        if (!f.isFile()) return new LinkedHashMap<String, EntryJson>();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            Type type = new TypeToken<LinkedHashMap<String, EntryJson>>() {}.getType();
            Map<String, EntryJson> m = GSON.fromJson(r, type);
            return m == null ? new LinkedHashMap<String, EntryJson>() : m;
        } catch (Throwable t) {
            Logger.err("special-summon: editor read failed", t);
            return new LinkedHashMap<String, EntryJson>();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeAll(Map<String, EntryJson> all) {
        File f = SpecialSummonFeature.configFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            Type type = new TypeToken<LinkedHashMap<String, EntryJson>>() {}.getType();
            GSON.toJson(all, type, w);
            w.flush();
        } catch (Throwable t) {
            Logger.err("special-summon: editor write failed", t);
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }

    private static final class SummonPanel {
        final JPanel panel = new JPanel(new BorderLayout(8, 4));
        final JPanel grid = new JPanel(new GridLayout(0, 4, 6, 3));
        final JSpinner chance = spinner(0, 0, 100, 5);
        final JSpinner copies = spinner(0, -1, 50, 1);
        final JTextField id = new JTextField("000000/0");
        final JButton pick = new JButton("Pick...");
        final JLabel preview = new JLabel("", JLabel.CENTER);
        final JCheckBox enemy = new JCheckBox("enemy");
        final JSpinner form = spinner(0, 0, 9, 1);
        final JSpinner buff = spinner(0, 0, 100000, 1);
        final JSpinner minDist = spinner(0, -5000, 5000, 50);
        final JSpinner maxDist = spinner(0, -5000, 5000, 50);
        final JSpinner spawnDelay = spinner(0, 0, 6000, 5);
        final JSpinner summonAnim = spinner(0, 0, 3, 1);
        final JSpinner layerMin = spinner(0, -1, 100000, 1);
        final JSpinner layerMax = spinner(0, -1, 100000, 1);
        final JCheckBox ignoreLimit = new JCheckBox("ignore limit");
        final JCheckBox fixBuff = new JCheckBox("fix buff");
        final JCheckBox sameHealth = new JCheckBox("same health");
        final JCheckBox bondHealth = new JCheckBox("bond health");

        SummonPanel(String title) {
            panel.setBorder(BorderFactory.createTitledBorder(title));
            id.setPreferredSize(new Dimension(120, 24));
            add("chance %", chance);
            add("copies", copies);
            add("summon id", id);
            add("form", form);
            add("", enemy);
            add("buff", buff);
            add("min dist", minDist);
            add("max dist", maxDist);
            add("spawn delay", spawnDelay);
            add("summon anim", summonAnim);
            add("layer min", layerMin);
            add("layer max", layerMax);
            add("", new JLabel());
            grid.add(ignoreLimit);
            grid.add(fixBuff);
            grid.add(sameHealth);
            grid.add(bondHealth);

            preview.setPreferredSize(new Dimension(120, 130));
            preview.setBorder(BorderFactory.createLineBorder(new Color(90, 90, 90)));
            preview.setBackground(new Color(46, 52, 60));
            preview.setOpaque(true);
            JPanel side = new JPanel(new BorderLayout(0, 4));
            side.add(preview, BorderLayout.CENTER);
            side.add(pick, BorderLayout.SOUTH);

            panel.add(grid, BorderLayout.CENTER);
            panel.add(side, BorderLayout.EAST);

            pick.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    Form f = SpecialSummonUnitPicker.pick();
                    if (f == null) return;
                    id.setText(f.unit.id.pack + "/" + f.unit.id.id);
                    enemy.setSelected(false);
                    form.setValue(clip(f.fid, 0, 9));
                    refreshPreview();
                }
            });
            DocumentListener dl = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { refreshPreview(); }
                @Override public void removeUpdate(DocumentEvent e) { refreshPreview(); }
                @Override public void changedUpdate(DocumentEvent e) { refreshPreview(); }
            };
            id.getDocument().addDocumentListener(dl);
            ChangeListener cl = new ChangeListener() {
                @Override public void stateChanged(ChangeEvent e) { refreshPreview(); }
            };
            form.addChangeListener(cl);
            enemy.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { refreshPreview(); }
            });
            refreshPreview();
        }

        private void add(String label, JComponent field) {
            grid.add(new JLabel(label));
            grid.add(field);
        }

        void refreshPreview() {
            try {
                if (enemy.isSelected()) {
                    preview.setIcon(null);
                    preview.setText("(enemy)");
                    return;
                }
                String[] parts = id.getText().trim().split("/");
                if (parts.length < 2) { preview.setIcon(null); preview.setText(""); return; }
                String pack = parts[0].trim();
                int uid = Integer.parseInt(parts[1].trim());
                Unit u = Identifier.getOr(new Identifier<Unit>(pack, Unit.class, uid), Unit.class);
                if (u == null || u.forms == null) { preview.setIcon(null); preview.setText("(?)"); return; }
                int fi = clip((Integer) form.getValue(), 0, u.forms.length - 1);
                Form f = u.forms[fi];
                BufferedImage img = f == null ? null : SpecialSummonPreview.render(f, 120, 130);
                preview.setIcon(img == null ? null : new ImageIcon(img));
                preview.setText(img == null ? "(no preview)" : "");
            } catch (Throwable t) {
                preview.setIcon(null);
                preview.setText("");
            }
        }

        void load(SummonJson j) {
            if (j == null) { refreshPreview(); return; }
            chance.setValue(clip(j.chance, 0, 100));
            copies.setValue(clip(j.copies, -1, 50));
            if (j.id != null) id.setText(j.id);
            enemy.setSelected(j.enemy);
            form.setValue(clip(j.form, 0, 9));
            buff.setValue(Math.max(0, j.buff));
            minDist.setValue(j.minDist);
            maxDist.setValue(j.maxDist);
            spawnDelay.setValue(Math.max(0, j.spawnDelay));
            summonAnim.setValue(clip(j.summonAnim, 0, 3));
            layerMin.setValue(j.layerMin);
            layerMax.setValue(j.layerMax);
            ignoreLimit.setSelected(j.ignoreLimit);
            fixBuff.setSelected(j.fixBuff);
            sameHealth.setSelected(j.sameHealth);
            bondHealth.setSelected(j.bondHealth);
            refreshPreview();
        }

        SummonJson toJson() {
            int ch = (Integer) chance.getValue();
            if (ch < 1) return null;
            SummonJson j = new SummonJson();
            j.chance = ch;
            j.copies = (Integer) copies.getValue();
            j.id = id.getText().trim();
            j.enemy = enemy.isSelected();
            j.form = (Integer) form.getValue();
            j.buff = (Integer) buff.getValue();
            j.minDist = (Integer) minDist.getValue();
            j.maxDist = (Integer) maxDist.getValue();
            j.spawnDelay = (Integer) spawnDelay.getValue();
            j.summonAnim = (Integer) summonAnim.getValue();
            j.layerMin = (Integer) layerMin.getValue();
            j.layerMax = (Integer) layerMax.getValue();
            j.ignoreLimit = ignoreLimit.isSelected();
            j.fixBuff = fixBuff.isSelected();
            j.sameHealth = sameHealth.isSelected();
            j.bondHealth = bondHealth.isSelected();
            return j;
        }

        private static int clip(int v, int lo, int hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }

    private static JSpinner spinner(int val, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(val, min, max, step));
    }
}
