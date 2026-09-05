package manualcontrol.crazy;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public final class CrazyDialog {

    private CrazyDialog() {}

    public static CrazyConfig show(Component parent) {
        CrazyConfig remembered = CrazyConfig.loadRemembered();

        JCheckBox growing = new JCheckBox("Growing Units", remembered.growingUnits);
        JCheckBox bossItem = new JCheckBox("Boss Item", remembered.bossItem);
        JCheckBox bombItem = new JCheckBox("Suicide Bomb", remembered.bombItem);
        JCheckBox catCoin = new JCheckBox("Cat Coin", remembered.catCoin);
        JCheckBox theRitual = new JCheckBox("The Ritual", remembered.theRitual);
        JCheckBox diceSlot = new JCheckBox("Dice Slot", remembered.diceSlot);
        JCheckBox eggPet = new JCheckBox("Egg Pet", remembered.eggPet);
        JCheckBox boosterSlot = new JCheckBox("Booster Slot", remembered.boosterSlot);
        final JLabel bombCountLabel = new JLabel("Bomb count");
        final JSpinner bombCount = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clampBombCount(remembered.bombCount),
                CrazyConfig.BOMB_COUNT_MIN, CrazyConfig.BOMB_COUNT_MAX, 1));
        Runnable syncBombEnabled = new Runnable() {
            @Override
            public void run() {
                boolean on = bombItem.isSelected();
                bombCount.setEnabled(on);
                bombCountLabel.setEnabled(on);
            }
        };
        bossItem.addActionListener(e -> {
            if (bossItem.isSelected()) bombItem.setSelected(false);
            syncBombEnabled.run();
        });
        bombItem.addActionListener(e -> {
            if (bombItem.isSelected()) bossItem.setSelected(false);
            syncBombEnabled.run();
        });
        JCheckBox impactFall = new JCheckBox("Impact Fall", remembered.impactFall);
        JCheckBox stackUnit = new JCheckBox("Stack Unit", remembered.stackUnit);
        JCheckBox reincarnation = new JCheckBox("Reincarnation", remembered.reincarnation);
        final JLabel reincThresholdLabel = new JLabel("Min price to reincarnate");
        final JSpinner reincThreshold = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clampReincarnationThreshold(remembered.reincarnationThreshold),
                CrazyConfig.REINCARNATION_THRESHOLD_MIN, CrazyConfig.REINCARNATION_THRESHOLD_MAX, 100));
        Runnable syncReincEnabled = new Runnable() {
            @Override
            public void run() {
                boolean on = reincarnation.isSelected();
                reincThreshold.setEnabled(on);
                reincThresholdLabel.setEnabled(on);
            }
        };
        reincarnation.addActionListener(e -> syncReincEnabled.run());

        JCheckBox extraBases = new JCheckBox("Extra Player Bases", remembered.extraPlayerBases);
        JCheckBox interactive = new JCheckBox("Interactive Player Base", remembered.interactiveBase);
        JCheckBox slingshot = new JCheckBox("Slingshot Base", remembered.slingshotBase && !remembered.interactiveBase && !remembered.catCanonBase);
        JCheckBox catCanon = new JCheckBox("Cat Canon", remembered.catCanonBase);
        JCheckBox slingshotMoneyCost = new JCheckBox("Money Cost", remembered.slingshotMoneyCost);
        slingshotMoneyCost.setVisible(slingshot.isSelected());

        JCheckBox baseHpOverride = new JCheckBox("Edit Player Base HP", remembered.playerBaseHpOverride);
        final JLabel baseHpLabel = new JLabel("Base HP");
        final JSpinner baseHp = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clampPlayerBaseHp(remembered.playerBaseHp),
                CrazyConfig.PLAYER_BASE_HP_MIN, CrazyConfig.PLAYER_BASE_HP_MAX, 1000));
        JCheckBox moneyLimitOverride = new JCheckBox("Set max money (start full)", remembered.moneyLimitOverride);
        final JLabel moneyLimitLabel = new JLabel("Max money");
        final JSpinner moneyLimit = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clampMoneyLimit(remembered.moneyLimit),
                CrazyConfig.MONEY_LIMIT_MIN, CrazyConfig.MONEY_LIMIT_MAX, 1000));
        Runnable syncBaseEditEnabled = new Runnable() {
            @Override
            public void run() {
                boolean hpOn = baseHpOverride.isSelected();
                baseHp.setEnabled(hpOn);
                baseHpLabel.setEnabled(hpOn);
                boolean monOn = moneyLimitOverride.isSelected();
                moneyLimit.setEnabled(monOn);
                moneyLimitLabel.setEnabled(monOn);
            }
        };
        baseHpOverride.addActionListener(e -> syncBaseEditEnabled.run());
        moneyLimitOverride.addActionListener(e -> syncBaseEditEnabled.run());
        interactive.addActionListener(e -> {
            if (interactive.isSelected()) slingshot.setSelected(false);
            if (interactive.isSelected()) catCanon.setSelected(false);
        });
        slingshot.addActionListener(e -> {
            if (slingshot.isSelected()) interactive.setSelected(false);
            if (slingshot.isSelected()) catCanon.setSelected(false);
        });
        catCanon.addActionListener(e -> {
            if (catCanon.isSelected()) {
                interactive.setSelected(false);
                slingshot.setSelected(false);
                slingshotMoneyCost.setSelected(false);
            }
        });
        JComboBox<String> spawnMode = new JComboBox<String>(new String[]{
                "Extra bases spawn the same unit",
                "Extra bases spawn higher forms"
        });
        spawnMode.setSelectedIndex(remembered.multiBaseHigherForms ? 1 : 0);

        JCheckBox manualControl = new JCheckBox("Manual Unit Control (grab & lift a unit to control it)", remembered.manualControl);

        JRadioButton beamNone = new JRadioButton("No custom beam", remembered.beamMode == CrazyConfig.BeamMode.NONE);
        JRadioButton beamKame = new JRadioButton("Kamehameha Beam", remembered.beamMode == CrazyConfig.BeamMode.KAMEHAMEHA);
        JRadioButton beamHypnosis = new JRadioButton("Hypnosis Beam", remembered.beamMode == CrazyConfig.BeamMode.HYPNOSIS);
        JRadioButton beamEvolution = new JRadioButton("Evolution Beam", remembered.beamMode == CrazyConfig.BeamMode.EVOLUTION);
        JRadioButton beamArmy = new JRadioButton("Army Canon", remembered.beamMode == CrazyConfig.BeamMode.ARMY_CANON);
        JRadioButton beamCopyCat = new JRadioButton("Copy Cat UFO", remembered.beamMode == CrazyConfig.BeamMode.COPY_CAT_UFO);
        ButtonGroup beamGroup = new ButtonGroup();
        beamGroup.add(beamNone);
        beamGroup.add(beamKame);
        beamGroup.add(beamHypnosis);
        beamGroup.add(beamEvolution);
        beamGroup.add(beamArmy);
        beamGroup.add(beamCopyCat);

        final JLabel ufoSpeedLabel = new JLabel("UFO speed (out)");
        final JSpinner ufoSpeed = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clamp(remembered.ufoSpeedOut, CrazyConfig.UFO_SPEED_MIN, CrazyConfig.UFO_SPEED_MAX, 26.0),
                CrazyConfig.UFO_SPEED_MIN, CrazyConfig.UFO_SPEED_MAX, 1.0));
        final JLabel ufoReturnLabel = new JLabel("Return speed x");
        final JSpinner ufoReturn = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clamp(remembered.ufoReturnSpeedMul, CrazyConfig.UFO_RETURN_MUL_MIN, CrazyConfig.UFO_RETURN_MUL_MAX, 1.6),
                CrazyConfig.UFO_RETURN_MUL_MIN, CrazyConfig.UFO_RETURN_MUL_MAX, 0.1));
        final JLabel ufoAltLabel = new JLabel("Altitude (px)");
        final JSpinner ufoAlt = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clamp(remembered.ufoAltitude, CrazyConfig.UFO_ALTITUDE_MIN, CrazyConfig.UFO_ALTITUDE_MAX, 150.0),
                CrazyConfig.UFO_ALTITUDE_MIN, CrazyConfig.UFO_ALTITUDE_MAX, 5.0));
        final JLabel ufoBobAmpLabel = new JLabel("Bob amplitude (px)");
        final JSpinner ufoBobAmp = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clamp(remembered.ufoBobAmplitude, CrazyConfig.UFO_BOB_AMP_MIN, CrazyConfig.UFO_BOB_AMP_MAX, 26.0),
                CrazyConfig.UFO_BOB_AMP_MIN, CrazyConfig.UFO_BOB_AMP_MAX, 2.0));
        final JLabel ufoBobFreqLabel = new JLabel("Bob frequency");
        final JSpinner ufoBobFreq = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clamp(remembered.ufoBobFrequency, CrazyConfig.UFO_BOB_FREQ_MIN, CrazyConfig.UFO_BOB_FREQ_MAX, 0.08),
                CrazyConfig.UFO_BOB_FREQ_MIN, CrazyConfig.UFO_BOB_FREQ_MAX, 0.01));
        final JLabel ufoConeLabel = new JLabel("Scan width x");
        final JSpinner ufoCone = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clamp(remembered.ufoConeWidthMul, CrazyConfig.UFO_CONE_MUL_MIN, CrazyConfig.UFO_CONE_MUL_MAX, 1.0),
                CrazyConfig.UFO_CONE_MUL_MIN, CrazyConfig.UFO_CONE_MUL_MAX, 0.05));
        final JLabel ufoSpawnLabel = new JLabel("Spawn interval (frames)");
        final JSpinner ufoSpawn = new JSpinner(new SpinnerNumberModel(
                Math.max(CrazyConfig.UFO_SPAWN_INTERVAL_MIN, Math.min(CrazyConfig.UFO_SPAWN_INTERVAL_MAX, remembered.ufoSpawnInterval)),
                CrazyConfig.UFO_SPAWN_INTERVAL_MIN, CrazyConfig.UFO_SPAWN_INTERVAL_MAX, 1));
        final JLabel ufoHueLabel = new JLabel("Tint hue (0-359)");
        final JSpinner ufoHue = new JSpinner(new SpinnerNumberModel(
                Math.max(CrazyConfig.UFO_HUE_MIN, Math.min(CrazyConfig.UFO_HUE_MAX, remembered.ufoTintHue)),
                CrazyConfig.UFO_HUE_MIN, CrazyConfig.UFO_HUE_MAX, 5));
        final JLabel ufoDiveLabel = new JLabel("Dive frames");
        final JSpinner ufoDive = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clamp(remembered.ufoDiveDuration, CrazyConfig.UFO_DIVE_MIN, CrazyConfig.UFO_DIVE_MAX, 24.0),
                CrazyConfig.UFO_DIVE_MIN, CrazyConfig.UFO_DIVE_MAX, 1.0));
        final JSpinner[] ufoSpinners = {ufoSpeed, ufoReturn, ufoAlt, ufoBobAmp, ufoBobFreq, ufoCone, ufoSpawn, ufoHue, ufoDive};
        final JLabel[] ufoLabels = {ufoSpeedLabel, ufoReturnLabel, ufoAltLabel, ufoBobAmpLabel, ufoBobFreqLabel, ufoConeLabel, ufoSpawnLabel, ufoHueLabel, ufoDiveLabel};
        final Runnable syncUfoEnabled = new Runnable() {
            @Override
            public void run() {
                boolean on = beamCopyCat.isSelected();
                for (int i = 0; i < ufoSpinners.length; i++) {
                    ufoSpinners[i].setEnabled(on);
                    ufoLabels[i].setEnabled(on);
                }
            }
        };

        final JLabel beamRecoveryLabel = new JLabel("Recovery time (seconds)");
        final JSpinner beamRecovery = new JSpinner(new SpinnerNumberModel(
                CrazyConfig.clampRecovery(remembered.beamRecoverySeconds),
                CrazyConfig.BEAM_RECOVERY_MIN, CrazyConfig.BEAM_RECOVERY_MAX, 0.5));
        Runnable syncRecoveryEnabled = new Runnable() {
            @Override
            public void run() {
                boolean on = !beamNone.isSelected();
                beamRecovery.setEnabled(on);
                beamRecoveryLabel.setEnabled(on);
            }
        };
        beamNone.addActionListener(e -> syncRecoveryEnabled.run());
        beamKame.addActionListener(e -> {
            if (beamKame.isSelected()) catCanon.setSelected(false);
            syncRecoveryEnabled.run();
        });
        beamHypnosis.addActionListener(e -> {
            if (beamHypnosis.isSelected()) catCanon.setSelected(false);
            syncRecoveryEnabled.run();
        });
        beamEvolution.addActionListener(e -> {
            if (beamEvolution.isSelected()) catCanon.setSelected(false);
            syncRecoveryEnabled.run();
        });
        beamArmy.addActionListener(e -> {
            if (beamArmy.isSelected()) catCanon.setSelected(false);
            syncRecoveryEnabled.run();
        });
        beamCopyCat.addActionListener(e -> {
            if (beamCopyCat.isSelected()) catCanon.setSelected(false);
            syncRecoveryEnabled.run();
        });

        beamCopyCat.addItemListener(e -> syncUfoEnabled.run());
        catCanon.addActionListener(e -> {
            if (catCanon.isSelected()) {
                beamNone.setSelected(true);
                syncRecoveryEnabled.run();
            }
        });

        JTabbedPane tabs = new JTabbedPane();
        JPanel unitPanel = grid();
        add(unitPanel, growing, 0, 0, 2);
        add(unitPanel, bossItem, 0, 1, 2);
        add(unitPanel, new JLabel("Boss Item: drag the battle icon onto a unit once per battle."), 0, 2, 2);
        add(unitPanel, bombItem, 0, 3, 2);
        add(unitPanel, bombCountLabel, 0, 4, 1);
        add(unitPanel, bombCount, 1, 4, 1);
        add(unitPanel, new JLabel("Suicide Bomb: drag a bomb onto an ally-side unit."), 0, 5, 2);
        syncBombEnabled.run();
        add(unitPanel, catCoin, 0, 6, 2);
        add(unitPanel, theRitual, 0, 7, 2);
        add(unitPanel, diceSlot, 0, 8, 2);
        add(unitPanel, eggPet, 0, 9, 2);
        add(unitPanel, impactFall, 0, 10, 2);
        add(unitPanel, boosterSlot, 0, 11, 2);
        add(unitPanel, new JLabel("Booster Slot: drag a lineup button onto the dock left of the lineup."), 0, 12, 2);
        add(unitPanel, stackUnit, 0, 13, 2);
        add(unitPanel, new JLabel("Stack Unit: re-deploying an existing unit stacks it (+10% HP/ATK/range/scale, +1% CD)."), 0, 14, 2);
        add(unitPanel, reincarnation, 0, 15, 2);
        add(unitPanel, reincThresholdLabel, 0, 16, 1);
        add(unitPanel, reincThreshold, 1, 16, 1);
        add(unitPanel, new JLabel("Reincarnation: a dying ally over the price hatches 2 random units summing to its price."), 0, 17, 2);
        syncReincEnabled.run();

        JPanel beamPanel = grid();
        add(beamPanel, manualControl, 0, 0, 2);
        add(beamPanel, new JLabel("--- Custom beam (mutually exclusive) ---"), 0, 1, 2);
        add(beamPanel, beamNone, 0, 2, 2);
        add(beamPanel, beamKame, 0, 3, 2);
        add(beamPanel, beamHypnosis, 0, 4, 2);
        add(beamPanel, beamEvolution, 0, 5, 2);
        add(beamPanel, beamArmy, 0, 6, 2);
        add(beamPanel, beamCopyCat, 0, 7, 2);
        add(beamPanel, beamRecoveryLabel, 0, 8, 1);
        add(beamPanel, beamRecovery, 1, 8, 1);
        add(beamPanel, new JLabel("--- Copy Cat UFO tuning ---"), 0, 9, 2);
        add(beamPanel, ufoSpeedLabel, 0, 10, 1);
        add(beamPanel, ufoSpeed, 1, 10, 1);
        add(beamPanel, ufoReturnLabel, 0, 11, 1);
        add(beamPanel, ufoReturn, 1, 11, 1);
        add(beamPanel, ufoAltLabel, 0, 12, 1);
        add(beamPanel, ufoAlt, 1, 12, 1);
        add(beamPanel, ufoBobAmpLabel, 0, 13, 1);
        add(beamPanel, ufoBobAmp, 1, 13, 1);
        add(beamPanel, ufoBobFreqLabel, 0, 14, 1);
        add(beamPanel, ufoBobFreq, 1, 14, 1);
        add(beamPanel, ufoConeLabel, 0, 15, 1);
        add(beamPanel, ufoCone, 1, 15, 1);
        add(beamPanel, ufoSpawnLabel, 0, 16, 1);
        add(beamPanel, ufoSpawn, 1, 16, 1);
        add(beamPanel, ufoHueLabel, 0, 17, 1);
        add(beamPanel, ufoHue, 1, 17, 1);
        add(beamPanel, ufoDiveLabel, 0, 18, 1);
        add(beamPanel, ufoDive, 1, 18, 1);
        syncRecoveryEnabled.run();
        syncUfoEnabled.run();

        JPanel basePanel = grid();
        add(basePanel, extraBases, 0, 0, 2);
        add(basePanel, new JLabel("Spawn mode"), 0, 1, 1);
        add(basePanel, spawnMode, 1, 1, 1);
        add(basePanel, interactive, 0, 2, 2);
        add(basePanel, slingshot, 0, 3, 1);
        add(basePanel, slingshotMoneyCost, 1, 3, 1);
        add(basePanel, catCanon, 0, 4, 2);
        add(basePanel, new JLabel("--- Base & money editor ---"), 0, 5, 2);
        add(basePanel, baseHpOverride, 0, 6, 2);
        add(basePanel, baseHpLabel, 0, 7, 1);
        add(basePanel, baseHp, 1, 7, 1);
        add(basePanel, moneyLimitOverride, 0, 8, 2);
        add(basePanel, moneyLimitLabel, 0, 9, 1);
        add(basePanel, moneyLimit, 1, 9, 1);
        syncBaseEditEnabled.run();
        Runnable syncSlingshotOptions = new Runnable() {
            @Override
            public void run() {
                boolean on = slingshot.isSelected();
                slingshotMoneyCost.setVisible(on);
                if (!on) slingshotMoneyCost.setSelected(false);
                basePanel.revalidate();
                basePanel.repaint();
            }
        };
        interactive.addActionListener(e -> syncSlingshotOptions.run());
        slingshot.addActionListener(e -> syncSlingshotOptions.run());
        catCanon.addActionListener(e -> syncSlingshotOptions.run());
        syncSlingshotOptions.run();

        tabs.addTab("Unit", unitPanel);
        tabs.addTab("Beam", beamPanel);
        tabs.addTab("Base", basePanel);

        int choice = JOptionPane.showConfirmDialog(parent, tabs, "BCU Crazy Patch",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            CrazyConfig off = CrazyConfig.defaults();
            off.remember();
            return off;
        }

        CrazyConfig c = new CrazyConfig();
        c.manualControl = manualControl.isSelected();
        c.growingUnits = growing.isSelected();
        c.bombItem = bombItem.isSelected();
        c.bombCount = CrazyConfig.clampBombCount(((Number) bombCount.getValue()).intValue());
        c.bossItem = bossItem.isSelected() && !c.bombItem;
        c.catCoin = catCoin.isSelected();
        c.theRitual = theRitual.isSelected();
        c.diceSlot = diceSlot.isSelected();
        c.eggPet = eggPet.isSelected();
        c.boosterSlot = boosterSlot.isSelected();
        c.impactFall = impactFall.isSelected();
        c.stackUnit = stackUnit.isSelected();
        c.reincarnation = reincarnation.isSelected();
        c.reincarnationThreshold = CrazyConfig.clampReincarnationThreshold(((Number) reincThreshold.getValue()).intValue());
        c.extraPlayerBases = extraBases.isSelected();
        c.multiBaseHigherForms = spawnMode.getSelectedIndex() == 1;
        c.catCanonBase = catCanon.isSelected();
        c.slingshotBase = slingshot.isSelected() && !c.catCanonBase;
        c.slingshotMoneyCost = c.slingshotBase && slingshotMoneyCost.isSelected();
        c.playerBaseHpOverride = baseHpOverride.isSelected();
        c.playerBaseHp = CrazyConfig.clampPlayerBaseHp(((Number) baseHp.getValue()).intValue());
        c.moneyLimitOverride = moneyLimitOverride.isSelected();
        c.moneyLimit = CrazyConfig.clampMoneyLimit(((Number) moneyLimit.getValue()).intValue());
        c.interactiveBase = interactive.isSelected() && !c.slingshotBase && !c.catCanonBase;
        if (c.catCanonBase) c.beamMode = CrazyConfig.BeamMode.NONE;
        else if (beamKame.isSelected()) c.beamMode = CrazyConfig.BeamMode.KAMEHAMEHA;
        else if (beamHypnosis.isSelected()) c.beamMode = CrazyConfig.BeamMode.HYPNOSIS;
        else if (beamEvolution.isSelected()) c.beamMode = CrazyConfig.BeamMode.EVOLUTION;
        else if (beamArmy.isSelected()) c.beamMode = CrazyConfig.BeamMode.ARMY_CANON;
        else if (beamCopyCat.isSelected()) c.beamMode = CrazyConfig.BeamMode.COPY_CAT_UFO;
        else c.beamMode = CrazyConfig.BeamMode.NONE;
        c.beamRecoverySeconds = CrazyConfig.clampRecovery(((Number) beamRecovery.getValue()).doubleValue());
        c.ufoSpeedOut = CrazyConfig.clamp(((Number) ufoSpeed.getValue()).doubleValue(), CrazyConfig.UFO_SPEED_MIN, CrazyConfig.UFO_SPEED_MAX, 26.0);
        c.ufoReturnSpeedMul = CrazyConfig.clamp(((Number) ufoReturn.getValue()).doubleValue(), CrazyConfig.UFO_RETURN_MUL_MIN, CrazyConfig.UFO_RETURN_MUL_MAX, 1.6);
        c.ufoAltitude = CrazyConfig.clamp(((Number) ufoAlt.getValue()).doubleValue(), CrazyConfig.UFO_ALTITUDE_MIN, CrazyConfig.UFO_ALTITUDE_MAX, 150.0);
        c.ufoBobAmplitude = CrazyConfig.clamp(((Number) ufoBobAmp.getValue()).doubleValue(), CrazyConfig.UFO_BOB_AMP_MIN, CrazyConfig.UFO_BOB_AMP_MAX, 26.0);
        c.ufoBobFrequency = CrazyConfig.clamp(((Number) ufoBobFreq.getValue()).doubleValue(), CrazyConfig.UFO_BOB_FREQ_MIN, CrazyConfig.UFO_BOB_FREQ_MAX, 0.08);
        c.ufoConeWidthMul = CrazyConfig.clamp(((Number) ufoCone.getValue()).doubleValue(), CrazyConfig.UFO_CONE_MUL_MIN, CrazyConfig.UFO_CONE_MUL_MAX, 1.0);
        c.ufoSpawnInterval = ((Number) ufoSpawn.getValue()).intValue();
        c.ufoTintHue = ((Number) ufoHue.getValue()).intValue();
        c.ufoDiveDuration = CrazyConfig.clamp(((Number) ufoDive.getValue()).doubleValue(), CrazyConfig.UFO_DIVE_MIN, CrazyConfig.UFO_DIVE_MAX, 24.0);
        c.applyImpactFallDefaults();
        c.remember();
        return c;
    }

    private static JPanel grid() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return p;
    }

    private static void add(JPanel panel, Component c, int x, int y, int width) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = width;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);
        panel.add(c, gbc);
    }
}
