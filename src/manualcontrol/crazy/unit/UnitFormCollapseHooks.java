package manualcontrol.crazy.unit;

import common.util.unit.Form;

import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.Vector;

public final class UnitFormCollapseHooks {

    private UnitFormCollapseHooks() {}

    private static final int IND_W = 30;

    public static void onUnitManagePageBuilt(final Object page) {
        try {
            Object jlfObj = BCUFields.get(page, "jlf");
            Object jluObj = BCUFields.get(page, "jlu");
            if (!(jlfObj instanceof JList)) return;
            @SuppressWarnings("unchecked")
            final JList<Form> jlf = (JList<Form>) jlfObj;

            final State st = new State();
            @SuppressWarnings("rawtypes")
            final ListCellRenderer orig = jlf.getCellRenderer();

            jlf.setCellRenderer(new ListCellRenderer<Form>() {
                @Override
                public Component getListCellRendererComponent(JList<? extends Form> list, Form value,
                        int index, boolean isSelected, boolean cellHasFocus) {
                    @SuppressWarnings("unchecked")
                    Component c = orig == null
                            ? new JLabel(String.valueOf(value))
                            : (Component) orig.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    boolean showInd = index == 0 && (st.collapsed || list.getModel().getSize() > 1);
                    if (!showInd) return c;
                    JPanel wrap = new JPanel(new BorderLayout());
                    wrap.setOpaque(true);
                    wrap.setBackground(c.getBackground());
                    wrap.add(c, BorderLayout.CENTER);
                    JLabel ind = new JLabel(st.collapsed ? "[+]" : "[-]", SwingConstants.CENTER);
                    ind.setOpaque(true);
                    ind.setBackground(new java.awt.Color(255, 235, 150));
                    ind.setForeground(new java.awt.Color(20, 20, 20));
                    ind.setFont(ind.getFont().deriveFont(Font.BOLD, 13f));
                    ind.setPreferredSize(new Dimension(IND_W, 1));
                    wrap.add(ind, BorderLayout.WEST);
                    return wrap;
                }
            });

            jlf.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (jlf.locationToIndex(e.getPoint()) != 0) return;
                    Rectangle r = jlf.getCellBounds(0, 0);
                    if (r == null) return;
                    if (e.getX() > r.x + IND_W) return;
                    toggle(jlf, st);
                    e.consume();
                }
            });

            if (jluObj instanceof JList) {
                ((JList<?>) jluObj).addListSelectionListener(new ListSelectionListener() {
                    @Override
                    public void valueChanged(ListSelectionEvent e) {
                        if (e.getValueIsAdjusting()) return;
                        st.collapsed = false;
                        st.full = null;
                    }
                });
            }
            Logger.log("form-collapse: installed on UnitManagePage");
        } catch (Throwable t) {
            Logger.err("form-collapse: install failed", t);
        }
    }

    private static void toggle(JList<Form> jlf, State st) {
        try {
            ListModel<Form> m = jlf.getModel();
            if (!st.collapsed) {
                int n = m.getSize();
                if (n <= 1) return;
                Vector<Form> full = new Vector<Form>();
                for (int i = 0; i < n; i++) full.add(m.getElementAt(i));
                st.full = full;
                jlf.setListData(new Vector<Form>(Collections.singletonList(full.get(0))));
                st.collapsed = true;
            } else {
                if (st.full != null) jlf.setListData(st.full);
                st.collapsed = false;
            }
            jlf.setSelectedIndex(0);
            jlf.repaint();
        } catch (Throwable t) {
            Logger.err("form-collapse: toggle failed", t);
        }
    }

    private static final class State {
        boolean collapsed = false;
        Vector<Form> full = null;
    }
}
