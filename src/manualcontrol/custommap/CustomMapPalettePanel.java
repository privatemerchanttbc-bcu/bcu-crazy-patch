package manualcontrol.custommap;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CustomMapPalettePanel extends JPanel {

    interface Listener { void selected(CustomMapPalette.Asset asset); }

    private final JComboBox<TileCatalog.TileSet> themes =
            new JComboBox<TileCatalog.TileSet>();
    private final JComboBox<Object> groups = new JComboBox<Object>();
    private final JTextField search = new JTextField();
    private final JCheckBox advanced = new JCheckBox("Advanced");
    private final DefaultListModel<CustomMapPalette.Asset> model =
            new DefaultListModel<CustomMapPalette.Asset>();
    private final JList<CustomMapPalette.Asset> list =
            new JList<CustomMapPalette.Asset>(model);
    private final JLabel largePreview = new JLabel("Choose a tile", SwingConstants.CENTER);
    private final JTextArea details = new JTextArea(4, 22);
    private final Map<String, ImageIcon> thumbnailCache =
            new HashMap<String, ImageIcon>();
    private List<CustomMapPalette.Asset> catalog =
            new ArrayList<CustomMapPalette.Asset>();
    private Listener listener;
    private boolean updatingThemes;

    CustomMapPalettePanel() {
        super(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Tile Palette"));
        setMinimumSize(new Dimension(235, 300));
        setPreferredSize(new Dimension(315, 700));

        JPanel selectors = new JPanel(new GridLayout(0, 1, 3, 3));
        selectors.add(labelled("Palette theme", themes));
        groups.addItem("All groups");
        for (CustomMapPalette.Category category : CustomMapPalette.Category.values())
            if (category != CustomMapPalette.Category.ADVANCED) groups.addItem(category);
        selectors.add(labelled("Group", groups));
        selectors.add(labelled("Search", search));
        JPanel advancedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        advanced.setToolTipText("Shows review/technical images as locked items with a reason.");
        advancedRow.add(advanced);
        selectors.add(advancedRow);
        add(selectors, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(-1);
        list.setFixedCellWidth(94);
        list.setFixedCellHeight(92);
        list.setCellRenderer(new TileRenderer());
        add(new JScrollPane(list), BorderLayout.CENTER);

        largePreview.setOpaque(true);
        largePreview.setBackground(new Color(42, 49, 61));
        largePreview.setForeground(Color.WHITE);
        largePreview.setPreferredSize(new Dimension(250, 145));
        largePreview.setBorder(BorderFactory.createLineBorder(new Color(95, 105, 120)));
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setOpaque(false);
        details.setFont(details.getFont().deriveFont(Font.PLAIN, 11f));
        details.setText("Choose a supported thumbnail, then click or drag on the preview. "
                + "The chosen tile stays the priority; neighbouring edges and corners reconnect automatically.");
        JPanel south = new JPanel(new BorderLayout(3, 3));
        south.add(largePreview, BorderLayout.CENTER);
        south.add(details, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        themes.addActionListener(e -> { if (!updatingThemes) reloadCatalog(); });
        groups.addActionListener(e -> filter());
        advanced.addActionListener(e -> reloadCatalog());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });
        list.addListSelectionListener(new ListSelectionListener() {
            @Override public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) showSelection();
            }
        });
    }

    void setListener(Listener value) { listener = value; }

    void setThemes(List<TileCatalog.TileSet> values,
                   TileCatalog.TileSet selected) {
        Object previous = themes.getSelectedItem();
        updatingThemes = true;
        try {
            themes.removeAllItems();
            if (values != null) for (TileCatalog.TileSet value : values)
                if (value != null && value.errors.isEmpty()) themes.addItem(value);
            TileCatalog.TileSet target = previous instanceof TileCatalog.TileSet
                    ? (TileCatalog.TileSet) previous : selected;
            if (target != null)
                for (int i = 0; i < themes.getItemCount(); i++)
                    if (themes.getItemAt(i).biome.equalsIgnoreCase(target.biome)) {
                        themes.setSelectedIndex(i);
                        break;
                    }
        } finally {
            updatingThemes = false;
        }
        reloadCatalog();
    }

    TileCatalog.TileSet selectedTheme() {
        return (TileCatalog.TileSet) themes.getSelectedItem();
    }

    CustomMapPalette.Asset selectedAsset() { return list.getSelectedValue(); }

    private void reloadCatalog() {
        catalog = CustomMapPalette.scan(selectedTheme(), advanced.isSelected());
        filter();
    }

    private void filter() {
        CustomMapPalette.Asset selected = list.getSelectedValue();
        Object group = groups.getSelectedItem();
        String query = search.getText();
        model.clear();
        for (CustomMapPalette.Asset asset : catalog) {
            if (group instanceof CustomMapPalette.Category
                    && asset.category != group) continue;
            if (!asset.matches(query)) continue;
            model.addElement(asset);
        }
        if (selected != null)
            for (int i = 0; i < model.size(); i++)
                if (model.get(i).id.equals(selected.id)) {
                    list.setSelectedIndex(i);
                    return;
                }
        if (!model.isEmpty()) list.setSelectedIndex(0);
        else showSelection();
    }

    private void showSelection() {
        CustomMapPalette.Asset asset = list.getSelectedValue();
        if (asset == null) {
            largePreview.setIcon(null);
            largePreview.setText("No matching tile");
            if (listener != null) listener.selected(null);
            return;
        }
        largePreview.setText("");
        largePreview.setIcon(icon(asset, 250, 140));
        details.setForeground(asset.supported
                ? new Color(45, 105, 65) : new Color(175, 55, 55));
        details.setText(asset.supported
                ? asset.category + " - " + asset.role + "\nTheme: "
                + asset.theme + (asset.family.isEmpty() ? "" : " / " + asset.family)
                + "\nClick or drag on Preview; adjacent edge/corner images update automatically."
                : "Locked: " + asset.disabledReason);
        if (listener != null) listener.selected(asset.supported ? asset : null);
    }

    private ImageIcon icon(CustomMapPalette.Asset asset, int width, int height) {
        String key = asset.id + "@" + width + "x" + height;
        ImageIcon found = thumbnailCache.get(key);
        if (found != null) return found;
        try {
            BufferedImage source = ImageIO.read(asset.file);
            if (source != null) {
                double scale = Math.min(width / (double) source.getWidth(),
                        height / (double) source.getHeight());
                int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
                found = new ImageIcon(source.getScaledInstance(w, h, Image.SCALE_SMOOTH));
            }
        } catch (Throwable ignored) {}
        if (found == null) found = new ImageIcon(new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_ARGB));
        thumbnailCache.put(key, found);
        return found;
    }

    private static JPanel labelled(String label, Component component) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel title = new JLabel(label + ":");
        title.setPreferredSize(new Dimension(88, 24));
        row.add(title, BorderLayout.WEST);
        row.add(component, BorderLayout.CENTER);
        return row;
    }

    private final class TileRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> source,
                                                                 Object value,
                                                                 int index,
                                                                 boolean selected,
                                                                 boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    source, value, index, selected, focus);
            CustomMapPalette.Asset asset = (CustomMapPalette.Asset) value;
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setVerticalTextPosition(SwingConstants.BOTTOM);
            label.setHorizontalTextPosition(SwingConstants.CENTER);
            label.setIcon(icon(asset, 72, 62));
            label.setText((asset.supported ? "" : "[LOCKED] ") + asset.name);
            label.setToolTipText(asset.supported
                    ? asset.role + " - " + asset.id : asset.disabledReason);
            if (!asset.supported && !selected) label.setForeground(new Color(145, 80, 80));
            return label;
        }
    }
}
