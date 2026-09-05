package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.data.MaskEnemy;
import common.battle.data.MaskUnit;
import common.battle.entity.AbEntity;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.pack.Identifier;
import common.system.VImg;
import common.system.SymCoord;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.util.anim.AnimU;
import common.util.Res;
import common.util.stage.Limit;
import common.util.stage.StageMap;
import common.util.unit.AbEnemy;
import common.util.unit.EForm;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.ConvertedRegistry;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyPreloadService;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.base.CatCanonFeature;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class DiceSlotFeature {

    private static final int SLOT_ROWS = 2;
    private static final int SLOT_COLS = 5;
    private static final int SLOT_COUNT = SLOT_ROWS * SLOT_COLS;
    private static final int ROLL_INTERVAL_FRAMES = 5 * 60;
    private static final int BEFORE_SPAWN_CONTINUE = -1;
    private static final int BEFORE_SPAWN_FAIL = 0;
    private static final int BEFORE_SPAWN_SUCCESS = 1;
    private static final int ENEMY_LAYER_MIN = 0;
    private static final int ENEMY_LAYER_MAX = 9;
    private static final int ENEMY_MARK = 0;
    private static final int ENEMY_GROUP = 0;
    private static final int SLOT_CHANGE_FX_FRAMES = 24;
    private static final int ENEMY_MIN_COOLDOWN_FRAMES = 5 * 60;
    private static final int ENEMY_MAX_COOLDOWN_FRAMES = 50 * 60;
    private static final int ENEMY_MAX_COOLDOWN_VALUE = 1500;
    private static final int NO_KILL_MONEY_SNAPSHOT = Integer.MIN_VALUE;

    private DiceSlotFeature() {}

    public static int preloadFlags() {
        return CrazyPreloadService.PRELOAD_OFFICIAL_PLAYER_FORMS
                | CrazyPreloadService.PRELOAD_OFFICIAL_ENEMIES;
    }

    public static final class State {
        public final Object lock = new Object();
        public final ArrayList<PoolEntry> pool = new ArrayList<PoolEntry>();
        public final Candidate[][] slots = new Candidate[SLOT_ROWS][SLOT_COLS];
        public final Candidate[][] selectedSnapshots = new Candidate[SLOT_ROWS][SLOT_COLS];
        public final int[][] changeFx = new int[SLOT_ROWS][SLOT_COLS];
        public final Map<Object, Reward> enemyRewards = new WeakHashMap<Object, Reward>();
        public Candidate placeholderPlayer;
        public boolean initialized;
        public boolean poolWarningLogged;
        public boolean syntheticSpawnArmed;
        public int syntheticRow = -1;
        public int syntheticCol = -1;
        public int timer;
    }

    private static abstract class PoolEntry {
        final boolean enemy;
        final String key;
        final int will;
        final int rarity;

        PoolEntry(boolean enemy, String key, int will, int rarity) {
            this.enemy = enemy;
            this.key = key;
            this.will = will;
            this.rarity = rarity;
        }

        abstract Candidate candidate(StageBasis sb);
    }

    private static final class PlayerEntry extends PoolEntry {
        final CrazyPreloadService.PlayerFormEntry source;

        PlayerEntry(CrazyPreloadService.PlayerFormEntry source) {
            super(false, source.key, source.will, source.rarity);
            this.source = source;
        }

        @Override
        Candidate candidate(StageBasis sb) {
            Form form = source == null ? null : source.form;
            if (form == null || form.du == null || form.unit == null) return null;
            try {
                Level lv = source.levelCloneOrDefault();
                EForm ef = new EForm(form, lv);
                int cost = playerCost(sb, form, ef);
                int cd = playerCooldown(sb, form);
                return new Candidate(form, ef, playerIcon(form), key, cost, cd, will, rarity);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static final class EnemyEntry extends PoolEntry {
        final CrazyPreloadService.EnemyEntry source;

        EnemyEntry(CrazyPreloadService.EnemyEntry source) {
            super(true, source.key, source.will, -1);
            this.source = source;
        }

        @Override
        Candidate candidate(StageBasis sb) {
            Enemy enemyUnit = source == null ? null : source.enemy;
            if (enemyUnit == null) return null;
            try {
                return new Candidate(enemyUnit, enemyIcon(enemyUnit), key, source.rawDrop, will);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static final class Candidate {
        final boolean enemy;
        final Form form;
        final EForm eform;
        final Enemy enemyUnit;
        final FakeImage icon;
        final String key;
        final int cost;
        final int maxCooldown;
        final int will;
        final int rarity;

        Candidate(Form form, EForm eform, FakeImage icon, String key,
                  int cost, int maxCooldown, int will, int rarity) {
            this.enemy = false;
            this.form = form;
            this.eform = eform;
            this.enemyUnit = null;
            this.icon = icon;
            this.key = key;
            this.cost = cost;
            this.maxCooldown = maxCooldown;
            this.will = will;
            this.rarity = rarity;
        }

        Candidate(Enemy enemyUnit, FakeImage icon, String key, int cost, int will) {
            this.enemy = true;
            this.form = null;
            this.eform = null;
            this.enemyUnit = enemyUnit;
            this.icon = icon;
            this.key = key;
            this.cost = cost;
            this.maxCooldown = enemyCooldownFrames(cost);
            this.will = will;
            this.rarity = -1;
        }
    }

    private static final class Reward {
        final int value;
        int moneyBefore = NO_KILL_MONEY_SNAPSHOT;
        boolean paid;

        Reward(int value) {
            this.value = value;
        }
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null || !rt.config.diceSlot) return;
        ensureInitialized(rt);
        State st = rt.diceSlot;
        synchronized (st.lock) {
            st.syntheticSpawnArmed = false;
        }
        StageBasis sb = (StageBasis) rt.stage;
        if (!battleRunning(sb)) return;
        if (BossItemFeature.shouldSkipNativeStageUpdate(rt)
                || CatCoinFeature.shouldSkipNativeStageUpdate(rt)
                || EggPetFeature.shouldSkipNativeStageUpdate(rt)
                || BoosterSlotFeature.shouldSkipNativeStageUpdate(rt)) return;
        tickChangeFx(st);
        st.timer++;
        if (st.timer >= ROLL_INTERVAL_FRAMES) {
            st.timer = 0;
            roll(rt);
        }
    }

    private static void tickChangeFx(State st) {
        synchronized (st.lock) {
            for (int row = 0; row < SLOT_ROWS; row++) {
                for (int col = 0; col < SLOT_COLS; col++) {
                    if (st.changeFx[row][col] > 0) st.changeFx[row][col]--;
                }
            }
        }
    }

    public static int beforeSpawn(CrazyRuntime.StageRuntime rt, int row, int col, boolean manual) {
        if (rt == null || !rt.config.diceSlot || !validSlot(row, col)) return BEFORE_SPAWN_CONTINUE;
        ensureInitialized(rt);
        StageBasis sb = (StageBasis) rt.stage;
        State st = rt.diceSlot;
        Candidate current;
        Candidate snapshot;
        synchronized (st.lock) {
            current = st.slots[row][col];
            snapshot = st.selectedSnapshots[row][col];
        }
        if (current == null && snapshot == null) return BEFORE_SPAWN_CONTINUE;

        if (manual && sb.buttonDelayOn && sb.selectedUnit[0] == -1) {
            if (sb.lineupChanging || sb.ubase.health == 0L) return BEFORE_SPAWN_FAIL;
            synchronized (st.lock) {
                st.selectedSnapshots[row][col] = current;
            }
            sb.buttonDelay = 6;
            sb.selectedUnit[0] = row;
            sb.selectedUnit[1] = col;
            return BEFORE_SPAWN_SUCCESS;
        }

        Candidate selected = snapshot != null ? snapshot : current;
        if (selected == null) return BEFORE_SPAWN_CONTINUE;
        if (selected.enemy || snapshot != null && snapshot != current) {
            if (!manual && !safeLock(sb, row, col)) return BEFORE_SPAWN_CONTINUE;
            return customSpawn(rt, selected, row, col, manual);
        }
        return BEFORE_SPAWN_CONTINUE;
    }

    public static boolean afterNativeSpawnAttempt(CrazyRuntime.StageRuntime rt, int row, int col,
                                                  boolean manual, boolean success) {
        if (rt == null || !rt.config.diceSlot || !validSlot(row, col)) return false;
        StageBasis sb = (StageBasis) rt.stage;
        State st = rt.diceSlot;
        synchronized (st.lock) {
            if (manual && success && sb.buttonDelay > 0
                    && sb.selectedUnit[0] == row && sb.selectedUnit[1] == col) {
                return true;
            }
            if (st.syntheticSpawnArmed && st.syntheticRow == row && st.syntheticCol == col) {
                st.syntheticSpawnArmed = false;
                st.syntheticRow = -1;
                st.syntheticCol = -1;
                return true;
            }
            st.selectedSnapshots[row][col] = null;
        }
        return false;
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics g) {

    }

    public static void drawNativeLineupOverlay(Object bbpainter, FakeGraphics g, int width, int height,
                                               float scale, float gap, boolean upper, int row) {
        CrazyRuntime.StageRuntime rt = runtimeForPainter(bbpainter);
        if (rt == null || !rt.config.diceSlot || g == null || !validRow(row)) return;
        ensureInitialized(rt);
        FakeImage slot = nativeSlotImage(bbpainter);
        if (slot == null || !slot.isValid()) return;
        int slotW = Math.round(scale * Math.max(1, slot.getWidth()));
        int slotH = Math.round(scale * Math.max(1, slot.getHeight()));
        for (int col = 0; col < SLOT_COLS; col++) {
            float x = (width - slotW * 5f) * 0.5f + slotW * col + gap * (col - 2f)
                    + (row == 0 ? 0f : gap * 0.5f);
            float y = height - slotH - (upper ? 0f : slotH * 0.1f);
            drawSlotOverlay(rt, bbpainter, g, row, col, x, y, slotW, slotH, scale, !upper);
        }
    }

    public static void drawNativeTwoRowLineupOverlay(Object bbpainter, FakeGraphics g, int width, int height,
                                                     float scale, float gap, float rowGap) {
        CrazyRuntime.StageRuntime rt = runtimeForPainter(bbpainter);
        if (rt == null || !rt.config.diceSlot || g == null) return;
        ensureInitialized(rt);
        FakeImage slot = nativeSlotImage(bbpainter);
        if (slot == null || !slot.isValid()) return;
        int slotW = Math.round(scale * Math.max(1, slot.getWidth()));
        int slotH = Math.round(scale * Math.max(1, slot.getHeight()));
        for (int row = 0; row < SLOT_ROWS; row++) {
            for (int col = 0; col < SLOT_COLS; col++) {
                float x = (width - slotW * 5f) * 0.5f + slotW * col + gap * (col - 2f);
                float y = height - (2f - row) * (slotH + rowGap);
                drawSlotOverlay(rt, bbpainter, g, row, col, x, y, slotW, slotH, scale, true);
            }
        }
    }

    public static boolean isManagedEnemy(Object entity) {
        if (!(entity instanceof Entity)) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return false;
        synchronized (rt.diceSlot.lock) {
            return rt.diceSlot.enemyRewards.containsKey(entity);
        }
    }

    public static void beforeEnemyKill(Object entity) {
        if (!(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(e.basis);
        if (rt == null) return;
        try {
            synchronized (rt.diceSlot.lock) {
                Reward reward = rt.diceSlot.enemyRewards.get(entity);
                if (reward != null) reward.moneyBefore = e.basis.money;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void afterEnemyKill(Object entity, Object killMode) {
        if (!(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(e.basis);
        if (rt == null) return;
        synchronized (rt.diceSlot.lock) {
            Reward reward = rt.diceSlot.enemyRewards.get(entity);
            if (reward == null) return;
            int moneyBefore = reward.moneyBefore;
            reward.moneyBefore = NO_KILL_MONEY_SNAPSHOT;
            if (moneyBefore == NO_KILL_MONEY_SNAPSHOT) return;
            if (reward.paid) {
                e.basis.money = moneyBefore;
                return;
            }
            e.basis.money = moneyBefore + reward.value;
            reward.paid = true;
            Logger.log("Dice Slot enemy reward paid=" + reward.value
                    + " killMode=" + (killMode == null ? "null" : killMode.toString()));
        }
    }

    public static int entityCount(Object stageBasis, int dire) {
        if (!(stageBasis instanceof StageBasis)) return 0;
        StageBasis sb = (StageBasis) stageBasis;
        int ans = 0;
        try {
            if (sb.ebase instanceof EEnemy && dire == 1) {
                ans += ((EEnemy) sb.ebase).data.getWill() + 1;
            }
        } catch (Throwable ignored) {}
        try {
            for (int i = 0; i < sb.le.size(); i++) {
                Entity ent = sb.le.get(i);
                if (ent == null || ent.dire != dire || ent.dead) continue;
                if (dire == -1 && isManagedEnemy(ent)) continue;
                if (dire == -1 && CatCanonFeature.isManaged(ent)) continue;
                ans += ent.data.getWill() + 1;
            }
        } catch (Throwable ignored) {}
        return ans;
    }

    public static int entityCount(Object stageBasis, int dire, int group) {
        if (!(stageBasis instanceof StageBasis)) return 0;
        StageBasis sb = (StageBasis) stageBasis;
        int ans = 0;
        try {
            for (int i = 0; i < sb.le.size(); i++) {
                Entity ent = sb.le.get(i);
                if (ent == null || ent.dire != dire || ent.group != group || ent.dead) continue;
                if (dire == -1 && isManagedEnemy(ent)) continue;
                if (dire == -1 && CatCanonFeature.isManaged(ent)) continue;
                ans += ent.data.getWill() + 1;
            }
        } catch (Throwable ignored) {}
        return ans;
    }

    public static int entityCountRar(Object stageBasis, int rarity) {
        if (!(stageBasis instanceof StageBasis)) return 0;
        StageBasis sb = (StageBasis) stageBasis;
        int ans = 0;
        try {
            for (int i = 0; i < sb.le.size(); i++) {
                Entity ent = sb.le.get(i);
                if (ent == null || ent.dire != -1 || ent.dead) continue;
                if (isManagedEnemy(ent)) continue;
                if (CatCanonFeature.isManaged(ent)) continue;
                if (entityRarity(ent) != rarity) continue;
                ans += ent.data.getWill() + 1;
            }
        } catch (Throwable ignored) {}
        return ans;
    }

    private static int entityRarity(Entity ent) {
        try {
            if (ent == null || !(ent.data instanceof MaskUnit)) return -1;
            Form form = ((MaskUnit) ent.data).getPack();
            if (form == null || form.unit == null) return -1;
            return form.unit.rarity;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void roll(CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        State st = rt.diceSlot;
        ensureInitialized(rt);
        if (st.pool.isEmpty()) {
            if (!st.poolWarningLogged) {
                st.poolWarningLogged = true;
                Logger.log("Dice Slot has no eligible official candidates");
            }
            return;
        }
        int face = randInt(sb, 6) + 1;
        int[] targets = targetsForFace(face);
        HashSet<String> used = usedKeysExcept(rt, targets);
        synchronized (st.lock) {
            for (int i = 0; i < targets.length; i++) {
                int slot = targets[i];
                int row = slot / SLOT_COLS;
                int col = slot % SLOT_COLS;
                Candidate c = randomCandidate(st, sb, used);
                if (c == null) continue;
                st.slots[row][col] = c;
                st.changeFx[row][col] = SLOT_CHANGE_FX_FRAMES;
                used.add(c.key);
                applySlotCandidate(rt, row, col, c);
                resetSlotBattleState(sb, row, col);
            }
        }
        try { CommonStatic.setSE(27); } catch (Throwable ignored) {}
        Logger.log("Dice Slot roll face=" + face + " targets=" + targets.length);
    }

    private static int[] targetsForFace(int face) {
        if (face >= 1 && face <= 5) {
            int col = face - 1;
            return new int[]{col, SLOT_COLS + col};
        }
        int[] all = new int[SLOT_COUNT];
        for (int i = 0; i < all.length; i++) all[i] = i;
        return all;
    }

    private static HashSet<String> usedKeysExcept(CrazyRuntime.StageRuntime rt, int[] targets) {
        HashSet<Integer> targetSet = new HashSet<Integer>();
        for (int i = 0; i < targets.length; i++) targetSet.add(targets[i]);
        HashSet<String> used = new HashSet<String>();
        StageBasis sb = (StageBasis) rt.stage;
        synchronized (rt.diceSlot.lock) {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                if (targetSet.contains(slot)) continue;
                int row = slot / SLOT_COLS;
                int col = slot % SLOT_COLS;
                Candidate c = rt.diceSlot.slots[row][col];
                if (c != null) {
                    used.add(c.key);
                    continue;
                }
                String nativeKey = nativeSlotKey(sb, row, col);
                if (nativeKey != null) used.add(nativeKey);
            }
        }
        return used;
    }

    private static Candidate randomCandidate(State st, StageBasis sb, HashSet<String> used) {
        if (st.pool.isEmpty()) return null;
        int start = randInt(sb, st.pool.size());
        for (int i = 0; i < st.pool.size(); i++) {
            PoolEntry e = st.pool.get((start + i) % st.pool.size());
            if (e == null || used.contains(e.key)) continue;
            Candidate c = e.candidate(sb);
            if (c != null) return c;
        }
        return null;
    }

    private static int customSpawn(CrazyRuntime.StageRuntime rt, Candidate c, int row, int col, boolean manual) {
        StageBasis sb = (StageBasis) rt.stage;
        if (sb.ubase.health == 0L || sb.unitRespawnTime > 0) {
            reject();
            clearSnapshot(rt, row, col);
            return BEFORE_SPAWN_FAIL;
        }
        if (sb.elu.cool[row][col] > 0) {
            reject();
            clearSnapshot(rt, row, col);
            return BEFORE_SPAWN_FAIL;
        }
        if (c.cost < 0 || c.cost > sb.money) {
            reject();
            clearSnapshot(rt, row, col);
            return BEFORE_SPAWN_FAIL;
        }
        if (!c.enemy && !canSpawnPlayer(sb, c)) {
            reject();
            clearSnapshot(rt, row, col);
            return BEFORE_SPAWN_FAIL;
        }
        try {
            if (c.enemy) {
                spawnEnemyCandidate(rt, c, row, col, mainSpawnPos(sb), true);
            } else {
                spawnPlayerCandidate(rt, c, row, col, mainSpawnPos(sb), true);
            }
            sb.money -= c.cost;
            sb.unitRespawnTime = 1;
            sb.elu.cool[row][col] = Math.max(0, c.maxCooldown);
            if (!c.enemy && sb.maxCatSpawns > 0) sb.maxCatSpawns--;
            markSyntheticSpawn(rt, row, col);
            clearSnapshot(rt, row, col);
            if (manual && rt.config.extraPlayerBases) spawnExtraCopies(rt, c, row, col);
            try { CommonStatic.setSE(19); } catch (Throwable ignored) {}
            return BEFORE_SPAWN_SUCCESS;
        } catch (Throwable t) {
            Logger.err("Dice Slot custom spawn failed", t);
            reject();
            clearSnapshot(rt, row, col);
            return BEFORE_SPAWN_FAIL;
        }
    }

    private static boolean canSpawnPlayer(StageBasis sb, Candidate c) {
        if (c == null || c.eform == null || c.form == null || c.form.du == null) return false;
        try {
            int will = Math.max(0, c.will);
            if (sb.maxCatSpawns == 0) return false;
            if (sb.entityCount(-1) >= sb.maxNum - will) return false;
            int rarity = c.rarity;
            if (rarity >= 0 && rarity < sb.maxRarityNum.length
                    && sb.maxRarityNum[rarity] != 0
                    && sb.entityCountRar(rarity) >= sb.maxRarityNum[rarity] - will) {
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private static void spawnPlayerCandidate(CrazyRuntime.StageRuntime rt, Candidate c, int row, int col,
                                             float pos, boolean main) {
        StageBasis sb = (StageBasis) rt.stage;
        EUnit unit = c.eform.getEntity(sb, new int[]{row, col}, false, false);
        unit.added(-1, pos);
        if (c.form.du.getProc().SPIRIT.exists()) {
            sb.summonerSummoned[row][col] = true;
            sb.spiritCooldown[row][col] = 15;
        }
        try {
            BCUFields.setInt(unit, "price", c.cost);
        } catch (Throwable ignored) {}
        if (!main) {
            try {
                unit.currentLayer = Math.max(0, Math.min(9, unit.currentLayer + 1));
            } catch (Throwable ignored) {}
        }
        addEntitySorted(sb, unit);
    }

    private static void spawnEnemyCandidate(CrazyRuntime.StageRuntime rt, Candidate c, int row, int col,
                                            float pos, boolean main) {
        StageBasis sb = (StageBasis) rt.stage;
        EEnemy enemy = c.enemyUnit.getEntity(sb, null, 1f, 1f,
                ENEMY_LAYER_MIN, ENEMY_LAYER_MAX, ENEMY_MARK, ENEMY_GROUP);
        enemy.added(-1, pos);
        try { enemy.dire = -1; } catch (Throwable ignored) {}
        try {
            if (!main) enemy.currentLayer = Math.max(0, Math.min(9, enemy.currentLayer + 1));
        } catch (Throwable ignored) {}
        ConvertedRegistry.mark(enemy);
        synchronized (rt.diceSlot.lock) {
            rt.diceSlot.enemyRewards.put(enemy, new Reward(Math.max(0, c.cost * 2)));
        }
        addEntitySorted(sb, enemy);
    }

    private static void spawnExtraCopies(CrazyRuntime.StageRuntime rt, Candidate c, int row, int col) {
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = 0; i < 2; i++) {
            float p = extraBaseSpawnPos(sb, i);
            try {
                if (c.enemy) {
                    spawnEnemyCandidate(rt, c, row, col, p, false);
                } else {
                    spawnPlayerCandidate(rt, c, row, col, p, false);
                }
            } catch (Throwable t) {
                Logger.err("Dice Slot extra-base copy failed", t);
            }
        }
    }

    private static void addEntitySorted(StageBasis sb, Entity e) {
        sb.le.add(e);
        Collections.sort(sb.le, new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                return Integer.compare(a.currentLayer, b.currentLayer);
            }
        });
    }

    private static void markSyntheticSpawn(CrazyRuntime.StageRuntime rt, int row, int col) {
        synchronized (rt.diceSlot.lock) {
            rt.diceSlot.syntheticSpawnArmed = true;
            rt.diceSlot.syntheticRow = row;
            rt.diceSlot.syntheticCol = col;
        }
    }

    private static void clearSnapshot(CrazyRuntime.StageRuntime rt, int row, int col) {
        synchronized (rt.diceSlot.lock) {
            rt.diceSlot.selectedSnapshots[row][col] = null;
        }
    }

    private static boolean safeLock(StageBasis sb, int row, int col) {
        try {
            return sb.locks[row][col];
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int enemyCooldownFrames(int cost) {
        int value = Math.max(0, cost / 100);
        float t = Math.max(0f, Math.min(1f, value / (float) ENEMY_MAX_COOLDOWN_VALUE));
        return Math.round(ENEMY_MIN_COOLDOWN_FRAMES
                + (ENEMY_MAX_COOLDOWN_FRAMES - ENEMY_MIN_COOLDOWN_FRAMES) * t);
    }

    private static float mainSpawnPos(StageBasis sb) {
        try {
            return sb.st.len - 700f;
        } catch (Throwable ignored) {
            return sb.ubase.pos - 160f;
        }
    }

    private static float playerBasePos(StageBasis sb) {
        try {
            return sb.st.len - 800f;
        } catch (Throwable ignored) {
            return sb.ubase.pos;
        }
    }

    private static float extraBaseSpawnPos(StageBasis sb, int index) {
        try {
            return manualcontrol.crazy.base.ExtraBasesFeature.doorPos(sb, index);
        } catch (Throwable ignored) {
            return playerBasePos(sb) - 80f;
        }
    }

    private static void reject() {
        try { CommonStatic.setSE(15); } catch (Throwable ignored) {}
    }

    private static void ensureInitialized(CrazyRuntime.StageRuntime rt) {
        State st = rt.diceSlot;
        if (st.initialized) return;
        synchronized (st.lock) {
            if (st.initialized) return;
            long start = System.currentTimeMillis();
            StageBasis sb = (StageBasis) rt.stage;
            CrazyPreloadService.Snapshot snap = CrazyPreloadService.snapshotOrBuildSync("dice-slot-stage");
            st.pool.clear();
            if (snap != null) {
                for (int i = 0; i < snap.officialPlayerForms.size(); i++) {
                    st.pool.add(new PlayerEntry(snap.officialPlayerForms.get(i)));
                }
                for (int i = 0; i < snap.officialEnemies.size(); i++) {
                    st.pool.add(new EnemyEntry(snap.officialEnemies.get(i)));
                }
            }
            st.placeholderPlayer = createPlaceholderPlayer(st, sb);
            st.initialized = true;
            Logger.log("Dice Slot stage init attached cache time="
                    + (System.currentTimeMillis() - start)
                    + "ms candidates=" + st.pool.size());
        }
    }

    private static Candidate createPlaceholderPlayer(State st, StageBasis sb) {
        for (int i = 0; i < st.pool.size(); i++) {
            PoolEntry e = st.pool.get(i);
            if (e != null && !e.enemy) {
                Candidate c = e.candidate(sb);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static Level diceLevel(Form form) {
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

    private static int playerCost(StageBasis sb, Form form, EForm eform) {
        int cost;
        try {
            int priceType = priceType(sb);
            Limit lim = sb.est == null ? null : sb.est.lim;
            if (lim != null && lim.unusable(eform.du, priceType)) return -1;
            cost = (int) (eform.getPrice(priceType) * 100.0f);
        } catch (Throwable t) {
            try { cost = form.du.getPrice() * 100; } catch (Throwable ignored) { cost = 0; }
        }
        try {
            Limit lim = sb.est == null ? null : sb.est.lim;
            if (lim != null && lim.stageLimit != null) {
                int global = sb.globalCost();
                if (global > 0) cost = global * 100;
                if (cost != -1 && form.unit != null) {
                    int rarity = form.unit.rarity;
                    int[] mult = lim.stageLimit.costMultiplier;
                    if (mult != null && rarity >= 0 && rarity < mult.length) {
                        cost = cost * mult[rarity] / 100;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return cost;
    }

    private static int playerCooldown(StageBasis sb, Form form) {
        int cd = 0;
        try {
            cd = sb.globalCdLimit() > 0
                    ? sb.b.t().getFinResGlobal(sb.globalCdLimit(), 0)
                    : sb.b.t().getFinRes(form.du.getRespawn(), 0);
        } catch (Throwable ignored) {}
        try {
            Limit lim = sb.est == null ? null : sb.est.lim;
            if (lim != null && lim.stageLimit != null && form.unit != null) {
                int rarity = form.unit.rarity;
                int[] mult = lim.stageLimit.cooldownMultiplier;
                if (mult != null && rarity >= 0 && rarity < mult.length) {
                    cd = cd * mult[rarity] / 100;
                }
            }
        } catch (Throwable ignored) {}
        return Math.max(0, cd);
    }

    private static int priceType(StageBasis sb) {
        try {
            Object cont = sb.st == null ? null : sb.st.getCont();
            if (cont instanceof StageMap) return ((StageMap) cont).price;
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void applySlotCandidate(CrazyRuntime.StageRuntime rt, int row, int col, Candidate c) {
        StageBasis sb = (StageBasis) rt.stage;
        try {
            if (c.enemy) {
                Candidate placeholder = rt.diceSlot.placeholderPlayer;
                if (placeholder != null && placeholder.form != null && placeholder.eform != null) {
                    sb.b.lu.fs[row][col] = placeholder.form;
                    sb.b.lu.efs[row][col] = placeholder.eform;
                    sb.b.lu.spirits[row][col] = spiritForm(sb, placeholder.form, placeholder.eform);
                    ensureLineupLevel(sb, placeholder.form, placeholder.eform);
                } else {
                    sb.b.lu.fs[row][col] = null;
                    sb.b.lu.efs[row][col] = null;
                    sb.b.lu.spirits[row][col] = null;
                }
            } else {
                sb.b.lu.fs[row][col] = c.form;
                sb.b.lu.efs[row][col] = c.eform;
                sb.b.lu.spirits[row][col] = spiritForm(sb, c.form, c.eform);
                ensureLineupLevel(sb, c.form, c.eform);
            }
            sb.elu.price[row][col] = c.enemy ? -1 : c.cost;
            sb.elu.maxC[row][col] = Math.max(1, c.maxCooldown);
        } catch (Throwable t) {
            Logger.err("Dice Slot native slot sync failed", t);
        }
    }

    private static void ensureLineupLevel(StageBasis sb, Form form, EForm eform) {
        try {
            if (sb == null || sb.b == null || sb.b.lu == null || form == null
                    || form.unit == null || form.unit.id == null || eform == null) return;
            Level level = eform.getLevel();
            if (level == null) return;
            Level copy = level.clone();
            if (copy.getOrbs() == null) copy.setOrbs(new int[0][]);
            sb.b.lu.map.put(form.unit.id, copy);
        } catch (Throwable t) {
            Logger.err("Dice Slot lineup level sync failed", t);
        }
    }

    private static EForm spiritForm(StageBasis sb, Form form, EForm base) {
        try {
            if (form == null || form.du == null || !form.du.getProc().SPIRIT.exists()) return null;
            Unit u = Identifier.getOr(form.du.getProc().SPIRIT.id, Unit.class);
            if (u == null || u.forms == null || u.forms.length == 0 || u.forms[0] == null) return null;
            Level spiritLevel = base.getLevel().clone();
            spiritLevel.setLevel(Math.min(u.max, spiritLevel.getLv() + spiritLevel.getPlusLv()));
            spiritLevel.setPlusLevel(0);
            spiritLevel.setOrbs(null);
            java.util.Arrays.fill(spiritLevel.getTalents(), 0);
            return new EForm(u.forms[0], spiritLevel);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void resetSlotBattleState(StageBasis sb, int row, int col) {
        try { sb.elu.cool[row][col] = 0; } catch (Throwable ignored) {}
        try {
            Object tick = BCUFields.get(sb.elu, "tick");
            if (tick instanceof int[][]) ((int[][]) tick)[row][col] = 0;
        } catch (Throwable ignored) {}
        try { sb.summonerSummoned[row][col] = false; } catch (Throwable ignored) {}
        try { sb.spiritSummoned[row][col] = false; } catch (Throwable ignored) {}
        try { sb.spiritCooldown[row][col] = 0; } catch (Throwable ignored) {}
        try { sb.spiritEmphasizeCount[row][col] = 0; } catch (Throwable ignored) {}
    }

    private static void drawSlotOverlay(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics g,
                                        int row, int col, float x, float y, float w, float h,
                                        float scale, boolean showStatus) {
        Candidate c;
        int fx;
        StageBasis sb = (StageBasis) rt.stage;
        synchronized (rt.diceSlot.lock) {
            c = rt.diceSlot.slots[row][col];
            fx = rt.diceSlot.changeFx[row][col];
        }
        if (c == null) return;
        if (!c.enemy) {
            drawSlotChangeFx(g, x, y, w, h, fx);
            return;
        }

        g.colRect(x, y, w, h, 5, 5, 7, 255);
        drawImageInBox(g, c.icon, x, y, w, h, 1f);
        g.colRect(x + w * 0.06f, y + h * 0.06f, w * 0.20f, h * 0.11f, 220, 40, 40, 230);
        g.colRect(x, y, w, h, 90, 0, 0, 46);
        int cool = safeCool(sb, row, col);
        int maxC = Math.max(1, safeMaxC(sb, row, col));
        boolean unavailable = !showStatus || c.cost > sb.money || cool > 0;
        if (unavailable) {
            g.colRect(x, y, w, h, 0, 0, 0, 100);
        }
        if (showStatus) {
            if (cool > 0) drawCooldownBar(g, x, y, w, h, scale, cool, maxC);
            drawNativeCost(bbpainter, g, scale, x + w, y + h, c.cost, !unavailable);
        }
        drawSlotChangeFx(g, x, y, w, h, fx);
    }

    private static void drawCooldownBar(FakeGraphics g, float x, float y, float w, float h,
                                        float scale, int cool, int maxC) {
        try {
            int dw = Math.max(1, Math.round(scale * 10.0f));
            int dh = Math.max(1, Math.round(scale * 12.0f));
            float cd = Math.max(0f, Math.min(1f, cool / (float) Math.max(1, maxC)));
            float xw2 = Math.max(0f, w - dw * 2f);
            float xw = cd * xw2;
            g.colRect(x + w - dw - xw2, y + h - dh * 2f, xw2, dh, 0, 0, 0, -1);
            g.colRect(x + dw + 2f, y + h - dh * 2f + 2f,
                    Math.max(0f, w - dw * 2f - xw - 4f),
                    Math.max(0f, dh - 4f), 0, 255, 255, -1);
        } catch (Throwable ignored) {}
    }

    private static void drawNativeCost(Object bbpainter, FakeGraphics g, float scale,
                                       float x, float y, int cost, boolean enabled) {
        if (bbpainter == null || g == null || cost < 0) return;
        try {
            Method m = BCUFields.method(bbpainter.getClass(), "setSym",
                    FakeGraphics.class, float.class, float.class, float.class, int.class);
            Object sym = m.invoke(bbpainter, g, scale, x, y, 3);
            if (sym instanceof SymCoord) {
                Res.getCost(cost / 100, enabled, (SymCoord) sym);
            }
        } catch (Throwable t) {
            drawPrice(g, cost, x - scale * 120f, y - scale * 58f, scale * 120f, scale * 58f, enabled);
        }
    }

    private static void drawSlotChangeFx(FakeGraphics gra, float x, float y, float w, float h, int frame) {
        if (gra == null || frame <= 0 || w <= 0f || h <= 0f) return;
        float p = Math.max(0f, Math.min(1f, frame / (float) SLOT_CHANGE_FX_FRAMES));
        int flashAlpha = clamp255(Math.round(108f * p));
        int borderAlpha = clamp255(Math.round(235f * p));
        if (flashAlpha > 0) {
            gra.colRect(x, y, w, h, 255, 255, 255, flashAlpha);
        }

        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) return;
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color oldColor = g.getColor();
        java.awt.Stroke oldStroke = g.getStroke();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(borderAlpha / 255f));
            float inset = Math.max(2f, Math.min(w, h) * (0.03f + 0.06f * (1f - p)));
            g.setStroke(new BasicStroke(Math.max(2f, Math.min(w, h) * 0.035f)));
            g.setColor(new Color(255, 232, 96));
            g.drawRoundRect(Math.round(x + inset), Math.round(y + inset),
                    Math.max(1, Math.round(w - inset * 2f)),
                    Math.max(1, Math.round(h - inset * 2f)),
                    Math.round(Math.min(w, h) * 0.08f),
                    Math.round(Math.min(w, h) * 0.08f));
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(oldColor);
            g.setStroke(oldStroke);
            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }
    }

    private static int safeCool(StageBasis sb, int row, int col) {
        try { return Math.max(0, sb.elu.cool[row][col]); } catch (Throwable ignored) { return 0; }
    }

    private static int safeMaxC(StageBasis sb, int row, int col) {
        try { return Math.max(1, sb.elu.maxC[row][col]); } catch (Throwable ignored) { return 1; }
    }

    private static void drawPrice(FakeGraphics gra, int cost, float x, float y, float w, float h, boolean enabled) {
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null || cost < 0) return;
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color oldColor = g.getColor();
        Font oldFont = g.getFont();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver);
            int size = Math.max(10, Math.round(h * 0.18f));
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            g.setFont(font);
            String text = Integer.toString(cost / 100);
            FontMetrics fm = g.getFontMetrics();
            int tx = Math.round(x + w - fm.stringWidth(text) - w * 0.08f);
            int ty = Math.round(y + h - h * 0.08f);
            g.setColor(new Color(0, 0, 0, 190));
            g.drawString(text, tx + 1, ty + 1);
            g.setColor(enabled ? new Color(255, 246, 120) : new Color(150, 150, 150));
            g.drawString(text, tx, ty);
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(oldColor);
            g.setFont(oldFont);
            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }
    }

    private static void drawImageInBox(FakeGraphics g, FakeImage img, float x, float y,
                                       float boxW, float boxH, float alpha) {
        if (img == null || boxW <= 0f || boxH <= 0f) return;
        int a = clamp255(Math.round(255f * clamp01(alpha)));
        if (a <= 0) return;
        try {
            g.setComposite(FakeGraphics.TRANS, a, 0);
            float ratio = Math.min(boxW / Math.max(1, img.getWidth()),
                    boxH / Math.max(1, img.getHeight()));
            float iw = img.getWidth() * ratio;
            float ih = img.getHeight() * ratio;
            g.drawImage(img, x + (boxW - iw) * 0.5f, y + (boxH - ih) * 0.5f, iw, ih);
        } finally {
            resetComposite(g);
        }
    }

    private static FakeImage playerIcon(Form form) {
        try {
            if (form == null || !(form.anim instanceof AnimU)) return null;
            return ((AnimU<?>) form.anim).getUni().getImg();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static FakeImage enemyIcon(Enemy enemy) {
        try {
            VImg icon = enemy.getIcon();
            return icon == null ? null : icon.getImg();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static FakeImage nativeSlotImage(Object bbpainter) {
        try {
            Object aux = BCUFields.get(bbpainter, "aux");
            Object slots = BCUFields.get(aux, "slot");
            Object slot0 = ((Object[]) slots)[0];
            Object img = BCUFields.invoke(slot0, "getImg");
            return img instanceof FakeImage ? (FakeImage) img : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CrazyRuntime.StageRuntime runtimeForPainter(Object bbpainter) {
        try {
            Object stage = BBPainterAccess.getStageBasis(bbpainter);
            return CrazyRuntime.get(stage);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String nativeSlotKey(StageBasis sb, int row, int col) {
        try {
            Form f = sb.b.lu.fs[row][col];
            return f == null ? null : keyForForm(f);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String keyForForm(Form form) {
        if (form == null || form.unit == null || form.unit.id == null) return null;
        return "U:" + form.unit.id.pack + ":" + form.unit.id.id + ":" + form.fid;
    }

    private static String keyForEnemy(Enemy enemy) {
        if (enemy == null || enemy.id == null) return null;
        return "E:" + enemy.id.pack + ":" + enemy.id.id;
    }

    private static boolean battleRunning(StageBasis sb) {
        try {
            return sb != null && sb.ubase != null && sb.ebase != null
                    && sb.ubase.health > 0L && sb.ebase.health > 0L && sb.s_stop == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean validSlot(int row, int col) {
        return validRow(row) && col >= 0 && col < SLOT_COLS;
    }

    private static boolean validRow(int row) {
        return row >= 0 && row < SLOT_ROWS;
    }

    private static int randInt(StageBasis sb, int bound) {
        if (bound <= 1) return 0;
        try {
            int v = (int) (sb.r.nextFloat() * bound);
            return Math.max(0, Math.min(bound - 1, v));
        } catch (Throwable ignored) {
            return (int) (Math.random() * bound);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static void resetComposite(FakeGraphics g) {
        try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }
}
