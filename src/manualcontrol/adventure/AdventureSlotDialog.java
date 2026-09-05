package manualcontrol.adventure;

import common.util.unit.Form;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class AdventureSlotDialog {

    private AdventureSlotDialog() {}

    public static void show(final Object mainPage) {
        final Component parent = mainPage instanceof Component ? (Component) mainPage : null;
        final Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        final JDialog dialog = new JDialog(owner, "Adventure Mode - Save Slots",
                Dialog.ModalityType.APPLICATION_MODAL);

        final JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                content.removeAll();
                JLabel head = new JLabel("Choose a save slot");
                head.setFont(head.getFont().deriveFont(Font.BOLD, 17f));
                head.setAlignmentX(Component.LEFT_ALIGNMENT);
                content.add(head);
                content.add(Box.createVerticalStrut(8));

                final int last = AdventureRuntime.lastSlot();
                if (last >= 1 && AdventureSave.hasSlot(last)) {
                    JButton contLast = new JButton("Continue Last Run (Slot " + last + ")");
                    contLast.setAlignmentX(Component.LEFT_ALIGNMENT);
                    contLast.addActionListener(new ActionListener() {
                        @Override public void actionPerformed(ActionEvent e) {
                            continueSlot(mainPage, dialog, last);
                        }
                    });
                    content.add(contLast);
                    content.add(Box.createVerticalStrut(10));
                }

                for (int s = 1; s <= AdventureSave.slotCount(); s++) {
                    content.add(buildSlotRow(mainPage, dialog, s, rebuild[0]));
                    content.add(Box.createVerticalStrut(8));
                }
                JButton close = new JButton("Close");
                close.setAlignmentX(Component.LEFT_ALIGNMENT);
                close.addActionListener(new ActionListener() {
                    @Override public void actionPerformed(ActionEvent e) { dialog.dispose(); }
                });
                content.add(Box.createVerticalStrut(4));
                content.add(close);
                content.revalidate();
                content.repaint();
                dialog.pack();
            }
        };
        rebuild[0].run();

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static JPanel buildSlotRow(final Object mainPage, final JDialog dialog,
                                       final int slot, final Runnable rebuild) {
        final AdventureSaveData d = AdventureSave.readSlot(slot);
        final Form form = d == null ? null : d.resolveForm();
        JPanel row = new JPanel(new BorderLayout(10, 4));
        row.setBorder(BorderFactory.createTitledBorder("Slot " + slot));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(760, 130));

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(70, 70));
        icon.setHorizontalAlignment(JLabel.CENTER);
        if (form != null) {
            javax.swing.ImageIcon ic = AdventureUnitIcon.render(form, 64);
            if (ic != null) icon.setIcon(ic);
        }
        row.add(icon, BorderLayout.WEST);

        JLabel info = new JLabel();
        info.setFont(info.getFont().deriveFont(Font.PLAIN, 14f));
        if (d == null) {
            info.setText("<html><i>Empty</i></html>");
        } else {
            String modeTag = d.isIronman()
                    ? "<font color='#ff5555'><b>&#9760; IRONMAN</b></font>"
                    : "<font color='#7cc88a'>CASUAL</font>";
            String missing = form == null
                    ? " <font color='#ff8888'>(unit missing)</font>" : "";
            info.setText("<html>" + escape(d.unitLabel) + missing + "<br>"
                    + escape(stageText(d)) + " &nbsp;|&nbsp; " + d.lives + " lives &nbsp;|&nbsp; "
                    + d.coreTokens.size() + " cores &nbsp;|&nbsp; " + modeTag
                    + "<br><font color='#888888'>Saved " + timeText(d.savedAt) + "</font></html>");
        }
        row.add(info, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        if (d == null) {
            JButton newGame = new JButton("New Game");
            newGame.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    dialog.dispose();
                    AdventureSetupDialog.show(mainPage, slot);
                }
            });
            btns.add(newGame);
        } else {
            JButton cont = new JButton("Continue");
            cont.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    if (form == null) {
                        JOptionPane.showMessageDialog(dialog,
                                "The unit for this save could not be found.\n"
                                        + "Its pack may have been removed. (" + d.unitLabel + ")",
                                "Adventure Mode", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    if (!chooseLandingVfx(dialog, d)) return;
                    dialog.dispose();
                    AdventureRuntime.beginFromSave(d, form, mainPage);
                }
            });
            JButton del = new JButton("Delete");
            del.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    int ok = JOptionPane.showConfirmDialog(dialog,
                            "Delete save Slot " + slot + "?\n"
                                    + d.unitLabel + " - " + stageText(d),
                            "Delete Save", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (ok == JOptionPane.YES_OPTION) {
                        AdventureSave.deleteSlot(slot);
                        rebuild.run();
                    }
                }
            });
            btns.add(cont);
            btns.add(del);
        }
        row.add(btns, BorderLayout.SOUTH);
        return row;
    }

    private static void continueSlot(Object mainPage, JDialog dialog, int slot) {
        AdventureSaveData d = AdventureSave.readSlot(slot);
        if (d == null) {
            JOptionPane.showMessageDialog(dialog,
                    "No recent Adventure run to continue.",
                    "Adventure Mode", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Form form = d.resolveForm();
        if (form == null) {
            JOptionPane.showMessageDialog(dialog,
                    "The unit for this save could not be found (pack removed?).",
                    "Adventure Mode", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!chooseLandingVfx(dialog, d)) return;
        dialog.dispose();
        AdventureRuntime.beginFromSave(d, form, mainPage);
    }

    private static boolean chooseLandingVfx(JDialog dialog, AdventureSaveData data) {
        if (data == null || !data.hasCore("S2")) return true;
        AdventureLandingVfx selected = AdventureLandingVfxDialog.choose(
                dialog, AdventureLandingVfx.fromId(data.landingVfx));
        if (selected == null) return false;
        data.landingVfx = selected.id;
        return true;
    }

    private static String stageText(AdventureSaveData d) {
        String s = d.stageName;
        if (s == null || s.trim().isEmpty()) s = "Stage " + d.stageNumber();
        return s;
    }

    private static String timeText(long ms) {
        if (ms <= 0) return "-";
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
        } catch (Throwable t) {
            return "-";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
