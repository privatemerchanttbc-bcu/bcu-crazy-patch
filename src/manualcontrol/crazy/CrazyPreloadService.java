package manualcontrol.crazy;

import common.pack.UserProfile;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.Logger;
import manualcontrol.crazy.unit.CatCoinFeature;
import manualcontrol.crazy.unit.DiceSlotFeature;
import manualcontrol.crazy.unit.EggPetFeature;
import manualcontrol.crazy.unit.TheRitualFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CrazyPreloadService {

    public static final int PRELOAD_OFFICIAL_PLAYER_FORMS = 1;
    public static final int PRELOAD_ALL_PLAYER_FORMS = 1 << 1;
    public static final int PRELOAD_OFFICIAL_ENEMIES = 1 << 2;

    private static final String OFFICIAL_PACK = "000000";
    private static final int PRELOAD_MAX_ATTEMPTS = 90;
    private static final int PRELOAD_RETRY_MS = 1000;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Integer> CONSUMERS = new LinkedHashMap<String, Integer>();
    private static boolean defaultConsumersRegistered;
    private static volatile Snapshot snapshot;
    private static volatile boolean running;

    private CrazyPreloadService() {}

    public static final class Snapshot {
        public final ArrayList<PlayerFormEntry> officialPlayerForms;
        public final ArrayList<EnemyEntry> officialEnemies;
        public final ArrayList<PlayerFormEntry> allPlayerForms;

        Snapshot(ArrayList<PlayerFormEntry> officialPlayerForms,
                 ArrayList<EnemyEntry> officialEnemies,
                 ArrayList<PlayerFormEntry> allPlayerForms) {
            this.officialPlayerForms = officialPlayerForms;
            this.officialEnemies = officialEnemies;
            this.allPlayerForms = allPlayerForms;
        }

        public int total() {
            return Math.max(officialPlayerForms.size(), allPlayerForms.size()) + officialEnemies.size();
        }
    }

    public static final class PlayerFormEntry {
        public final Form form;
        public final String key;
        public final int will;
        public final int rarity;
        public final Level baseLevel;

        PlayerFormEntry(Form form, String key, int will, int rarity, Level baseLevel) {
            this.form = form;
            this.key = key;
            this.will = will;
            this.rarity = rarity;
            this.baseLevel = baseLevel;
        }

        public Level levelCloneOrDefault() {
            try {
                Level lv = baseLevel == null ? null : baseLevel.clone();
                if (lv == null) lv = new Level(30);
                if (lv.getOrbs() == null) lv.setOrbs(new int[0][]);
                return lv;
            } catch (Throwable ignored) {
                Level lv = new Level(30);
                try { lv.setOrbs(new int[0][]); } catch (Throwable ignored2) {}
                return lv;
            }
        }
    }

    public static final class EnemyEntry {
        public final Enemy enemy;
        public final String key;
        public final int rawDrop;
        public final int will;

        EnemyEntry(Enemy enemy, String key, int rawDrop, int will) {
            this.enemy = enemy;
            this.key = key;
            this.rawDrop = rawDrop;
            this.will = will;
        }
    }

    public static void register(String featureName, int flags) {
        if (featureName == null || featureName.trim().isEmpty() || flags == 0) return;
        synchronized (LOCK) {
            Integer old = CONSUMERS.get(featureName);
            CONSUMERS.put(featureName, old == null ? flags : old | flags);
        }
    }

    public static void registerDefaultConsumers() {
        synchronized (LOCK) {
            if (defaultConsumersRegistered) return;
            defaultConsumersRegistered = true;
        }
        register("Dice Slot", DiceSlotFeature.preloadFlags());
        register("Cat Coin", CatCoinFeature.preloadFlags());
        register("Egg Pet", EggPetFeature.preloadFlags());
        register("The Ritual", TheRitualFeature.preloadFlags());
    }

    public static int combinedFlags() {
        synchronized (LOCK) {
            int flags = 0;
            for (Integer v : CONSUMERS.values()) {
                if (v != null) flags |= v;
            }
            return flags;
        }
    }

    public static String consumerSummary() {
        synchronized (LOCK) {
            if (CONSUMERS.isEmpty()) return "none";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Integer> e : CONSUMERS.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append("=").append(flagsToString(e.getValue() == null ? 0 : e.getValue()));
            }
            return sb.toString();
        }
    }

    public static void preloadAfterStartup(String reason) {
        preloadAsync(reason);
    }

    public static void preloadAsync(String reason) {
        registerDefaultConsumers();
        final int flags = combinedFlags();
        if (flags == 0) {
            Logger.log("Crazy preload skipped: no registered consumers");
            return;
        }
        if (snapshot != null) return;
        synchronized (LOCK) {
            if (snapshot != null || running) return;
            running = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                Logger.log("Crazy preload started: " + reason
                        + " flags=" + flagsToString(flags)
                        + " consumers=[" + consumerSummary() + "]");
                try {
                    for (int attempt = 1; attempt <= PRELOAD_MAX_ATTEMPTS; attempt++) {
                        Snapshot snap = buildSnapshot(flags, false);
                        if (snap != null && hasRequiredData(snap, flags)) {
                            snapshot = snap;
                            Logger.log("Crazy preload ready: officialPlayers=" + snap.officialPlayerForms.size()
                                    + " officialEnemies=" + snap.officialEnemies.size()
                                    + " allPlayers=" + snap.allPlayerForms.size()
                                    + " total=" + snap.total()
                                    + " time=" + (System.currentTimeMillis() - start) + "ms");
                            return;
                        }
                        try { Thread.sleep(PRELOAD_RETRY_MS); } catch (InterruptedException ignored) { return; }
                    }
                    Logger.log("Crazy preload gave up after " + PRELOAD_MAX_ATTEMPTS
                            + " attempts time=" + (System.currentTimeMillis() - start) + "ms");
                } catch (Throwable t) {
                    Logger.err("Crazy preload failed", t);
                } finally {
                    synchronized (LOCK) {
                        running = false;
                    }
                }
            }
        }, "CrazyPreload");
        t.setDaemon(true);
        t.start();
    }

    public static Snapshot snapshotOrBuildSync(String reason) {
        registerDefaultConsumers();
        int flags = combinedFlags();
        Snapshot snap = snapshot;
        if (snap != null && hasRequiredData(snap, flags)) return snap;
        long start = System.currentTimeMillis();
        snap = buildSnapshot(flags, true);
        if (snap != null && hasRequiredData(snap, flags) && snapshot == null) snapshot = snap;
        Logger.log("Crazy preload fallback sync: reason=" + reason
                + " flags=" + flagsToString(flags)
                + " time=" + (System.currentTimeMillis() - start) + "ms"
                + " total=" + (snap == null ? 0 : snap.total()));
        return snap;
    }

    public static Snapshot currentSnapshot() {
        return snapshot;
    }

    private static Snapshot buildSnapshot(int flags, boolean logErrors) {
        ArrayList<PlayerFormEntry> officialPlayers = new ArrayList<PlayerFormEntry>();
        ArrayList<PlayerFormEntry> allPlayers = new ArrayList<PlayerFormEntry>();
        ArrayList<EnemyEntry> enemies = new ArrayList<EnemyEntry>();

        if ((flags & (PRELOAD_OFFICIAL_PLAYER_FORMS | PRELOAD_ALL_PLAYER_FORMS)) != 0) try {
            List<Unit> units = UserProfile.getBCData().units.getList();
            if (units != null) {
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (u == null || u.id == null || u.forms == null) continue;
                    boolean official = OFFICIAL_PACK.equals(u.id.pack);
                    for (int f = 0; f < u.forms.length; f++) {
                        if (!official && (flags & PRELOAD_ALL_PLAYER_FORMS) == 0) continue;
                        PlayerFormEntry e = playerEntry(u.forms[f]);
                        if (e == null) continue;
                        if ((flags & PRELOAD_ALL_PLAYER_FORMS) != 0) allPlayers.add(e);
                        if (official && (flags & PRELOAD_OFFICIAL_PLAYER_FORMS) != 0) officialPlayers.add(e);
                    }
                }
            }
        } catch (Throwable t) {
            if (logErrors) Logger.err("Crazy preload player build failed", t);
        }

        if ((flags & PRELOAD_OFFICIAL_ENEMIES) != 0) try {
            List<Enemy> enemyList = UserProfile.getBCData().enemies.getList();
            if (enemyList != null) {
                for (int i = 0; i < enemyList.size(); i++) {
                    EnemyEntry e = enemyEntry(enemyList.get(i));
                    if (e != null) enemies.add(e);
                }
            }
        } catch (Throwable t) {
            if (logErrors) Logger.err("Crazy preload enemy build failed", t);
        }

        return new Snapshot(officialPlayers, enemies, allPlayers);
    }

    private static boolean hasRequiredData(Snapshot snap, int flags) {
        if (snap == null) return false;
        if ((flags & PRELOAD_OFFICIAL_PLAYER_FORMS) != 0 && snap.officialPlayerForms.isEmpty()) return false;
        if ((flags & PRELOAD_ALL_PLAYER_FORMS) != 0 && snap.allPlayerForms.isEmpty()) return false;
        if ((flags & PRELOAD_OFFICIAL_ENEMIES) != 0 && snap.officialEnemies.isEmpty()) return false;
        return flags != 0;
    }

    private static String flagsToString(int flags) {
        if (flags == 0) return "none";
        StringBuilder sb = new StringBuilder();
        appendFlag(sb, flags, PRELOAD_OFFICIAL_PLAYER_FORMS, "official-player-forms");
        appendFlag(sb, flags, PRELOAD_ALL_PLAYER_FORMS, "all-player-forms");
        appendFlag(sb, flags, PRELOAD_OFFICIAL_ENEMIES, "official-enemies");
        return sb.toString();
    }

    private static void appendFlag(StringBuilder sb, int flags, int bit, String label) {
        if ((flags & bit) == 0) return;
        if (sb.length() > 0) sb.append("|");
        sb.append(label);
    }

    private static PlayerFormEntry playerEntry(Form form) {
        if (form == null || form.du == null || form.unit == null || form.unit.id == null) return null;
        try {
            String key = "U:" + form.unit.id.pack + ":" + form.unit.id.id + ":" + form.fid;
            int will = Math.max(0, form.du.getWill());
            int rarity = form.unit.rarity;
            return new PlayerFormEntry(form, key, will, rarity, levelFor(form));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static EnemyEntry enemyEntry(Enemy enemy) {
        if (enemy == null || enemy.id == null || !OFFICIAL_PACK.equals(enemy.id.pack) || enemy.de == null) return null;
        try {
            String key = "E:" + enemy.id.pack + ":" + enemy.id.id;
            int drop = Math.max(0, enemy.de.getDrop());
            int will = Math.max(0, enemy.de.getWill());
            return new EnemyEntry(enemy, key, drop, will);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Level levelFor(Form form) {
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
}
