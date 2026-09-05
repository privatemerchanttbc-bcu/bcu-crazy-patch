package manualcontrol.adventure;

import common.util.stage.Stage;
import common.util.unit.Form;
import common.util.unit.Level;
import manualcontrol.Logger;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapRepository;
import manualcontrol.custommap.CustomMapRuntime;

import java.util.List;
import java.util.prefs.Preferences;

public final class AdventureRuntime {

    public enum State { IDLE, LAUNCHING, PLAYING }

    public static final int DEFAULT_LIVES = 3;

    private static volatile State state = State.IDLE;
    private static volatile Form form;
    private static volatile Level level;
    private static volatile int livesLeft = DEFAULT_LIVES;
    private static volatile int levelIndex;

    private static volatile Object originPage;

    private static final AdventureCoreState cores = new AdventureCoreState();

    private static volatile AdventureCore lastPicked;

    private static volatile int activeSlot = -1;

    private static volatile String runMode = AdventureSaveData.MODE_CASUAL;

    private static volatile AdventureLandingVfx landingVfx = AdventureLandingVfx.CRYSTAL;

    private static volatile String customMapId;

    private static final Preferences PREFS =
            Preferences.userRoot().node("manualcontrol/bcu-crazy-patch");

    public static AdventureCoreState cores() { return cores; }

    public static AdventureCore lastPicked() { return lastPicked; }

    static void setLastPicked(AdventureCore core) { lastPicked = core; }

    private AdventureRuntime() {}

    public static State state() { return state; }
    public static boolean isLaunchPending() { return state == State.LAUNCHING; }
    public static boolean isPlaying() { return state == State.PLAYING; }
    public static Form form() { return form; }
    public static Level level() { return level; }
    public static int livesLeft() { return livesLeft; }
    public static int levelIndex() { return levelIndex; }
    public static Object originPage() { return originPage; }
    public static int activeSlot() { return activeSlot; }
    public static String runMode() { return runMode; }
    public static AdventureLandingVfx landingVfx() { return landingVfx; }
    public static boolean isIronman() { return AdventureSaveData.MODE_IRONMAN.equals(runMode); }
    public static String customMapId() { return customMapId; }
    public static boolean isCustomMapRun() { return customMapId != null && !customMapId.isEmpty(); }

    public static int lastSlot() {
        try { return PREFS.getInt("advLastSlot", -1); } catch (Throwable t) { return -1; }
    }

    public static synchronized void begin(Form selectedForm, Level selectedLevel, int lives, Object mainPage) {
        begin(selectedForm, selectedLevel, lives, mainPage, -1, AdventureSaveData.MODE_CASUAL);
    }

    public static synchronized void begin(Form selectedForm, Level selectedLevel, int lives,
                                          Object mainPage, int slot, String mode) {
        if (selectedForm == null) return;
        form = selectedForm;
        level = selectedLevel;
        livesLeft = Math.max(1, lives);
        levelIndex = 0;
        originPage = mainPage;
        activeSlot = slot;
        runMode = AdventureSaveData.MODE_IRONMAN.equals(mode)
                ? AdventureSaveData.MODE_IRONMAN : AdventureSaveData.MODE_CASUAL;
        landingVfx = AdventureLandingVfx.CRYSTAL;
        customMapId = CustomMapRuntime.pendingMapId();
        cores.clear();
        state = State.LAUNCHING;
        Logger.log("Adventure: run started form=" + selectedForm + " lives=" + livesLeft
                + " slot=" + slot + " mode=" + runMode);
        AdventureLauncher.launchLevel(mainPage, 0);
    }

    public static synchronized void beginFromSave(AdventureSaveData data, Form resolvedForm, Object mainPage) {
        if (data == null || resolvedForm == null) return;
        form = resolvedForm;
        level = AdventureSetupDialog.levelFor(resolvedForm);
        livesLeft = Math.max(1, data.lives);
        levelIndex = Math.max(0, data.levelIndex);
        originPage = mainPage;
        activeSlot = data.slot;
        runMode = data.isIronman() ? AdventureSaveData.MODE_IRONMAN : AdventureSaveData.MODE_CASUAL;
        landingVfx = AdventureLandingVfx.fromId(data.landingVfx);
        customMapId = data.customMapId == null || data.customMapId.trim().isEmpty() ? null : data.customMapId;
        if (customMapId != null) {
            try {
                CustomMapRuntime.prepare(customMapId, CustomMapDocument.MapMode.ADVENTURE);
            } catch (Throwable t) {
                customMapId = null;
                state = State.IDLE;
                javax.swing.JOptionPane.showMessageDialog(mainPage instanceof java.awt.Component
                                ? (java.awt.Component) mainPage : null,
                        "Cannot resume this custom map: " + t.getMessage(), "Adventure Mode",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            CustomMapRuntime.clearPending();
        }
        cores.restore(data.toCores(), data.coinDmg, data.coinHp, data.toSeenCores());
        state = State.LAUNCHING;
        Logger.log("Adventure: loaded slot=" + data.slot + " level=" + levelIndex
                + " unit=" + data.unitLabel + " cores=" + data.coreTokens.size());
        AdventureLauncher.launchLevel(mainPage, levelIndex);
    }

    public static synchronized void saveCheckpoint() {
        if (activeSlot < 0 || form == null) return;
        try {
            AdventureSaveData d = new AdventureSaveData();
            d.slot = activeSlot;
            d.savedAt = System.currentTimeMillis();
            d.mode = runMode;
            try {
                d.unitPack = form.unit.id.pack;
                d.unitId = form.unit.id.id;
                d.unitForm = form.fid;
            } catch (Throwable ignored) {}
            d.unitLabel = AdventureSetupDialog.label(form);
            d.levelIndex = levelIndex;
            d.stageName = stageNameFor(levelIndex);
            d.lives = livesLeft;
            d.coinDmg = cores.coinDmgPct();
            d.coinHp = cores.coinHpPct();
            d.landingVfx = landingVfx.id;
            d.customMapId = customMapId == null ? "" : customMapId;
            for (AdventureCore c : cores.ownedList()) {
                d.coreTokens.add(c.uid + "@" + c.tier.ordinal());
            }
            d.seenCoreTokens.addAll(cores.seenTokens());
            if (AdventureSave.writeSlot(activeSlot, d)) {
                try { PREFS.putInt("advLastSlot", activeSlot); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.err("Adventure: saveCheckpoint failed", t);
        }
    }

    public static synchronized void deleteActiveSlotIfIronman() {
        if (activeSlot >= 0 && AdventureSaveData.MODE_IRONMAN.equals(runMode)) {
            AdventureSave.deleteSlot(activeSlot);
        }
    }

    private static String stageNameFor(int index) {
        if (isCustomMapRun()) {
            try {
                CustomMapDocument doc = CustomMapRepository.load(customMapId);
                if (doc != null) return doc.name;
            } catch (Throwable ignored) {}
        }
        try {
            List<Stage> ls = AdventureLauncher.levels();
            if (index >= 0 && index < ls.size()) return AdventureLauncher.levelName(index, ls.get(index));
        } catch (Throwable ignored) {}
        return "Stage " + (index + 1);
    }

    static synchronized void onLaunching(int index) {
        levelIndex = index;
        state = State.LAUNCHING;
    }

    static synchronized void onBattleAdopted() {
        state = State.PLAYING;
    }

    static int loseLife() {
        int left = livesLeft - 1;
        livesLeft = Math.max(0, left);
        return livesLeft;
    }

    public static synchronized void reset(String reason) {
        if (state != State.IDLE) Logger.log("Adventure: reset (" + reason + ")");
        state = State.IDLE;
        form = null;
        level = null;
        livesLeft = DEFAULT_LIVES;
        levelIndex = 0;
        activeSlot = -1;
        runMode = AdventureSaveData.MODE_CASUAL;
        landingVfx = AdventureLandingVfx.CRYSTAL;
        customMapId = null;
        CustomMapRuntime.release(null);
        CustomMapRuntime.clearPending();
        cores.clear();
    }
}
