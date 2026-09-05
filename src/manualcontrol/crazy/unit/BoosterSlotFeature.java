package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.battle.data.MaskEntity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.unit.EForm;
import common.util.unit.Form;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class BoosterSlotFeature {

    public static final int S_PRICE = 0;
    public static final int S_SCALE = 1;
    public static final int S_DAMAGE = 2;
    public static final int S_HP = 3;
    public static final int S_SPEED = 4;
    public static final int S_ATK_SPEED = 5;
    public static final int S_TBA = 6;
    public static final int S_COUNT = 7;

    private static final String[] STAT_NAMES = new String[]{
            "Price", "Scale", "Damage", "HP", "Speed", "Attack Speed", "TBA"
    };

    private static final int RETURN_FRAMES = 16;
    private static final int FLASH_FRAMES = 60;

    private static final int INTERNAL_MONEY_MULT = 100;
    private static volatile Field transformDataField;

    private BoosterSlotFeature() {}

    public static final class State {
        public final Object lock = new Object();

        public final int[][][] boosts = new int[2][5][S_COUNT];
        public int totalClicks;

        public final int[][] basePrice = new int[2][5];
        public final boolean[][] basePriceCaptured = new boolean[2][5];
        public final int[][] lastWrittenPrice = new int[2][5];
        public final Form[][] lastForm = new Form[2][5];

        public final Map<Object, Tracked> tracked = new WeakHashMap<Object, Tracked>();
        public final Map<Object, Float> beforePos = new WeakHashMap<Object, Float>();
        public final Map<Object, MotionState> motion = new WeakHashMap<Object, MotionState>();
        public final Set<Object> damageScaled = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

        public int dockedRow = -1;
        public int dockedCol = -1;

        public boolean dragging;
        public boolean dragMoved;
        public int dragRow = -1;
        public int dragCol = -1;
        public int dragStartX;
        public int dragStartY;
        public int dragX;
        public int dragY;

        public boolean returning;
        public int returnFrame;
        public int returnFromX;
        public int returnFromY;

        public int flashStat = -1;
        public int flashFrame;

        public float dockExpand;

        public String statCacheKey;
        public StatBlock statCache;
        public boolean dockLogged;
    }

    public static final class Tracked {
        int row;
        int col;
        long baseMaxH;
        int appliedHpClicks;
    }

    public static final class MotionState {
        int lastWait;
        int lastPre;
        int lastAtk;
        boolean attackAnimSpeedLogged;
    }

    public static final class StatBlock {
        int price;
        long hp = 1;
        long damage = 1;
        int speed = 1;
        int tba = 1;
        int atkFrames = 1;
        int scalePct = 100;
    }

    private static final class Rect {
        final int x;
        final int y;
        final int w;
        final int h;

        Rect(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int px, int py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        int cx() { return x + w / 2; }
        int cy() { return y + h / 2; }
    }

    private static final class SlotGrid {
        int w;
        int h;
        int iw;
        int ih;
        float term;
        float termh;
        boolean twoRow;
        int frontRow;
    }

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (page == null || e == null || e.getButton() != MouseEvent.BUTTON1) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.boosterSlot;
            StageBasis sb = (StageBasis) rt.stage;
            SlotGrid grid = slotGrid(painterFromPage(page), sb);
            int mx = e.getX();
            int my = e.getY();

            if (st.dockedRow >= 0) {
                handleOverlayClick(rt, sb, page, grid, mx, my);
                return true;
            }
            if (grid == null) return false;

            int[] slot = slotAt(grid, mx, my);
            if (slot != null && formAt(sb, slot[0], slot[1]) != null) {
                beginDrag(st, slot[0], slot[1], mx, my);
                return true;
            }
            if (dockConsumesPress(dockRect(grid), st, mx, my)) {

                return true;
            }
            return false;
        } catch (Throwable t) {
            Logger.err("Booster Slot mouse press failed", t);
            return false;
        }
    }

    public static boolean onMouseDragged(Object page, MouseEvent e) {
        if (page == null || e == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.boosterSlot;
            if (st.dockedRow >= 0) return true;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            int dx = st.dragX - st.dragStartX;
            int dy = st.dragY - st.dragStartY;
            if (dx * dx + dy * dy > 64) st.dragMoved = true;
            return true;
        } catch (Throwable t) {
            Logger.err("Booster Slot mouse drag failed", t);
            return false;
        }
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        if (page == null || e == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.boosterSlot;
            if (st.dockedRow >= 0 && !st.dragging) return true;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            st.dragging = false;

            StageBasis sb = (StageBasis) rt.stage;
            SlotGrid grid = slotGrid(painterFromPage(page), sb);
            if (st.dragMoved && grid != null && dockRect(grid).contains(e.getX(), e.getY())) {
                dock(rt, st.dragRow, st.dragCol);
                return true;
            }
            if (st.dragMoved) {
                beginReturn(st, e.getX(), e.getY());
                return true;
            }

            return true;
        } catch (Throwable t) {
            Logger.err("Booster Slot mouse release failed", t);
            return false;
        }
    }

    private static void handleOverlayClick(CrazyRuntime.StageRuntime rt, StageBasis sb, Object page,
                                           SlotGrid grid, int mx, int my) {
        State st = rt.boosterSlot;
        int w = pageWidth(page);
        int h = pageHeight(page);
        for (int i = 0; i < S_COUNT; i++) {
            if (!plusButton(w, h, i).contains(mx, my)) continue;
            int cost = upgradeCost(st.boosts[st.dockedRow][st.dockedCol][i]);
            if (sb.money < cost * INTERNAL_MONEY_MULT) {
                reject();
                return;
            }
            sb.money = Math.max(0, sb.money - cost * INTERNAL_MONEY_MULT);
            synchronized (st.lock) {
                st.boosts[st.dockedRow][st.dockedCol][i]++;
                st.totalClicks++;
            }
            st.flashStat = i;
            st.flashFrame = FLASH_FRAMES;
            st.statCacheKey = null;
            play(19);
            Logger.log("Booster Slot +1% " + STAT_NAMES[i] + " slot=" + st.dockedRow + "-" + st.dockedCol
                    + " clicks=" + st.boosts[st.dockedRow][st.dockedCol][i] + " cost=" + cost);
            return;
        }
        for (int i = 0; i < S_COUNT; i++) {
            if (!maxButton(w, h, i).contains(mx, my)) continue;
            int bought = 0;
            long spent = 0L;
            synchronized (st.lock) {
                int clicks = st.boosts[st.dockedRow][st.dockedCol][i];
                while (true) {
                    long need = (long) upgradeCost(clicks + bought) * INTERNAL_MONEY_MULT;
                    if (sb.money < need) break;
                    sb.money -= (int) need;
                    spent += need / INTERNAL_MONEY_MULT;
                    bought++;
                }
                if (bought > 0) {
                    st.boosts[st.dockedRow][st.dockedCol][i] += bought;
                    st.totalClicks += bought;
                }
            }
            if (bought <= 0) {
                reject();
                return;
            }
            st.flashStat = i;
            st.flashFrame = FLASH_FRAMES;
            st.statCacheKey = null;
            play(19);
            Logger.log("Booster Slot MAX " + STAT_NAMES[i] + " slot=" + st.dockedRow + "-" + st.dockedCol
                    + " bought=" + bought + " spent=" + spent
                    + " clicks=" + st.boosts[st.dockedRow][st.dockedCol][i]);
            return;
        }
        if (removeButton(w, h).contains(mx, my)) {
            Logger.log("Booster Slot removed slot=" + st.dockedRow + "-" + st.dockedCol
                    + " (boosts kept)");
            st.dockedRow = -1;
            st.dockedCol = -1;
            play(19);
            return;
        }

        if (grid != null) {
            int[] slot = slotAt(grid, mx, my);
            if (slot != null && formAt(sb, slot[0], slot[1]) != null
                    && (slot[0] != st.dockedRow || slot[1] != st.dockedCol)) {
                dock(rt, slot[0], slot[1]);
                return;
            }
        }

    }

    private static void beginDrag(State st, int row, int col, int x, int y) {
        st.dragging = true;
        st.dragMoved = false;
        st.returning = false;
        st.dragRow = row;
        st.dragCol = col;
        st.dragStartX = x;
        st.dragStartY = y;
        st.dragX = x;
        st.dragY = y;
    }

    private static void beginReturn(State st, int x, int y) {
        st.dragging = false;
        st.returning = true;
        st.returnFrame = 0;
        st.returnFromX = x;
        st.returnFromY = y;
    }

    private static void dock(CrazyRuntime.StageRuntime rt, int row, int col) {
        State st = rt.boosterSlot;
        StageBasis sb = (StageBasis) rt.stage;
        if (formAt(sb, row, col) == null) {
            reject();
            return;
        }
        st.dockedRow = row;
        st.dockedCol = col;
        st.statCacheKey = null;
        play(19);
        Logger.log("Booster Slot docked slot=" + row + "-" + col
                + " totalClicks=" + slotTotal(st, row, col));
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null || !rt.config.boosterSlot) return;
        State st = rt.boosterSlot;
        if (st.flashFrame > 0) st.flashFrame--;
        if (st.returning) {
            st.returnFrame++;
            if (st.returnFrame >= RETURN_FRAMES) {
                st.returning = false;
                st.returnFrame = 0;
            }
        }
        StageBasis sb = (StageBasis) rt.stage;
        if (st.dockedRow >= 0 && (!battleRunning(sb) || formAt(sb, st.dockedRow, st.dockedCol) == null)) {

            Logger.log("Booster Slot auto-closed (battle ended or slot emptied)");
            st.dockedRow = -1;
            st.dockedCol = -1;
        }
        detectFormChanges(st, sb);
        if (st.totalClicks <= 0) return;
        enforcePrices(st, sb);
        trackUnits(st, sb);
        capturePositions(st);
    }

    public static void afterNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        if (rt == null || !rt.config.boosterSlot) return;
        State st = rt.boosterSlot;
        if (st.totalClicks <= 0) return;
        for (Iterator<Map.Entry<Object, Tracked>> it = st.tracked.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Object, Tracked> entry = it.next();
            if (!(entry.getKey() instanceof Entity)) {
                it.remove();
                continue;
            }
            Entity en = (Entity) entry.getKey();
            Tracked tr = entry.getValue();
            if (en.dead || en.health <= 0L) {
                it.remove();
                continue;
            }
            int speed = st.boosts[tr.row][tr.col][S_SPEED];
            Float before = st.beforePos.get(en);
            if (before != null && speed > 0) {
                float delta = en.pos - before.floatValue();
                if (finite(delta) && Math.abs(delta) > 0.0001f && Math.abs(delta) < 2000f) {
                    en.pos += delta * speed * 0.01f;
                    try { BCUFields.field(en.getClass(), "lastPosition").setFloat(en, en.pos); } catch (Throwable ignored) {}
                }
            }
            accelerateTimers(st, en, st.boosts[tr.row][tr.col][S_TBA], st.boosts[tr.row][tr.col][S_ATK_SPEED]);
        }
        st.beforePos.clear();
    }

    public static boolean shouldSkipNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        return rt != null && rt.boosterSlot != null && rt.boosterSlot.dockedRow >= 0;
    }

    public static boolean shouldBlockSpawn(CrazyRuntime.StageRuntime rt) {
        return shouldSkipNativeStageUpdate(rt);
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        if (rt == null || rt.boosterSlot == null) return false;
        State st = rt.boosterSlot;
        return st.dockedRow >= 0 || st.dragging || st.totalClicks > 0;
    }

    private static void detectFormChanges(State st, StageBasis sb) {
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 5; col++) {
                Form f = formAt(sb, row, col);
                if (f == st.lastForm[row][col]) continue;
                st.lastForm[row][col] = f;

                st.basePriceCaptured[row][col] = false;
                if (st.dockedRow == row && st.dockedCol == col) st.statCacheKey = null;
            }
        }
    }

    private static void enforcePrices(State st, StageBasis sb) {
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 5; col++) {
                int n = st.boosts[row][col][S_PRICE];
                if (n <= 0) continue;
                int cur = priceAt(sb, row, col);
                if (cur < 0) {
                    st.basePriceCaptured[row][col] = false;
                    continue;
                }
                if (!st.basePriceCaptured[row][col]) {
                    st.basePrice[row][col] = cur;
                    st.basePriceCaptured[row][col] = true;
                } else if (cur != st.lastWrittenPrice[row][col]) {

                    st.basePrice[row][col] = cur;
                }
                int target = (int) Math.max(0L,
                        Math.round((double) st.basePrice[row][col] * (1.0 - 0.01 * n)));
                if (cur != target) {
                    try { sb.elu.price[row][col] = target; } catch (Throwable ignored) {}
                }
                st.lastWrittenPrice[row][col] = target;
                if (st.dockedRow == row && st.dockedCol == col && st.statCache != null
                        && st.statCache.price != target / 100) {
                    st.statCacheKey = null;
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static void trackUnits(State st, StageBasis sb) {
        java.util.List list;
        try {
            list = sb.le;
        } catch (Throwable ignored) {
            return;
        }
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            Object obj;
            try { obj = list.get(i); } catch (Throwable ignored) { break; }
            if (!(obj instanceof EUnit)) continue;
            EUnit u = (EUnit) obj;
            if (u.dead || u.health <= 0L) {
                st.tracked.remove(u);
                continue;
            }
            int[] idx = u.index;
            if (idx == null || idx.length < 2 || !validSlot(idx[0], idx[1])) continue;
            if (slotTotal(st, idx[0], idx[1]) <= 0) continue;
            Tracked tr = st.tracked.get(u);
            if (tr == null) {
                tr = new Tracked();
                tr.row = idx[0];
                tr.col = idx[1];
                tr.baseMaxH = Math.max(1L, u.maxH);
                st.tracked.put(u, tr);
            }
            int hpClicks = st.boosts[tr.row][tr.col][S_HP];
            if (tr.appliedHpClicks != hpClicks) {
                long newMax = scaleLong(tr.baseMaxH, hpClicks);
                long delta = newMax - u.maxH;
                u.maxH = Math.max(1L, newMax);
                if (u.health > 0L) u.health = Math.max(1L, u.health + delta);
                tr.appliedHpClicks = hpClicks;
            }
        }
    }

    private static void capturePositions(State st) {
        st.beforePos.clear();
        for (Object obj : new ArrayList<Object>(st.tracked.keySet())) {
            if (obj instanceof Entity) {
                Entity en = (Entity) obj;
                if (!en.dead && en.health > 0L) st.beforePos.put(en, en.pos);
            }
        }
    }

    private static void accelerateTimers(State st, Entity e, int tbaLevel, int attackSpeedLevel) {
        if (tbaLevel <= 0 && attackSpeedLevel <= 0) return;
        MotionState ms = st.motion.get(e);
        if (ms == null) {
            ms = new MotionState();
            st.motion.put(e, ms);
        }
        if (tbaLevel > 0) {
            try {
                int wait = BCUFields.getInt(e, "waitTime");
                if (wait > 1 && (ms.lastWait <= 1 || wait > ms.lastWait + 1)) {
                    wait = reduceFrames(wait, tbaLevel);
                    BCUFields.setInt(e, "waitTime", wait);
                }
                ms.lastWait = wait;
            } catch (Throwable ignored) {}
        }
        if (attackSpeedLevel > 0) {
            try {
                Object atkm = BCUFields.get(e, "atkm");
                int pre = BCUFields.getInt(atkm, "preTime");
                int atk = BCUFields.getInt(atkm, "atkTime");
                if (pre > 1 && (ms.lastPre <= 1 || pre > ms.lastPre + 1)) {
                    pre = reduceFrames(pre, attackSpeedLevel);
                    BCUFields.setInt(atkm, "preTime", pre);
                }
                if (atk > 1 && (ms.lastAtk <= 1 || atk > ms.lastAtk + 1)) {
                    atk = reduceFrames(atk, attackSpeedLevel);
                    BCUFields.setInt(atkm, "atkTime", atk);
                }
                ms.lastPre = pre;
                ms.lastAtk = atk;
                fastForwardAttackAnimation(ms, e, attackSpeedLevel, atk);
            } catch (Throwable ignored) {}
        }
    }

    private static void fastForwardAttackAnimation(MotionState ms, Entity e, int attackSpeedLevel, int atkTime) {
        if (ms == null || e == null || attackSpeedLevel <= 0 || atkTime <= 0) return;
        try {
            Object animMgr = BCUFields.get(e, "anim");
            Object animObj = BCUFields.get(animMgr, "anim");
            if (!(animObj instanceof EAnimU)) return;
            EAnimU anim = (EAnimU) animObj;
            Object type = BCUFields.get(anim, "type");
            if (!(type instanceof Enum) || !"ATK".equals(((Enum<?>) type).name())) return;

            int len = Math.max(1, anim.len());
            int reducedLen = reduceFrames(len, attackSpeedLevel);
            if (reducedLen >= len) return;

            float cur = anim.ind();
            if (!finite(cur) || cur < 0f) return;
            float speed = len / (float) Math.max(1, reducedLen);
            float extra = nativeAnimationStep(anim) * Math.max(0f, speed - 1f);
            float next = Math.min(Math.max(0f, len - 1f), cur + extra);
            if (next > cur + 0.001f) {
                anim.setTime(next);
                if (!ms.attackAnimSpeedLogged) {
                    ms.attackAnimSpeedLogged = true;
                    Logger.log("Booster Slot attack animation speed active level=" + attackSpeedLevel
                            + " len=" + len + " reduced=" + reducedLen);
                }
            }
        } catch (Throwable t) {
            Logger.err("Booster Slot attack animation speed failed", t);
        }
    }

    private static float nativeAnimationStep(EAnimU anim) {
        try {
            if (CommonStatic.getConfig().performanceModeAnimation && anim != null && anim.ind() >= 0f) {
                return 0.5f;
            }
        } catch (Throwable ignored) {}
        return 1f;
    }

    public static float drawScaleFor(Object entity) {
        if (!(entity instanceof EUnit)) return 1f;
        try {
            EUnit u = (EUnit) entity;
            int[] idx = u.index;
            if (idx == null || idx.length < 2 || !validSlot(idx[0], idx[1])) return 1f;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(u.basis);
            if (rt == null || !rt.config.boosterSlot) return 1f;
            int n = rt.boosterSlot.boosts[idx[0]][idx[1]][S_SCALE];
            if (n <= 0) return 1f;
            float s = 1f + n * 0.01f;
            return finite(s) ? Math.max(1f, s) : 1f;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    public static void applyDamageMultiplier(CrazyRuntime.StageRuntime rt, AttackAb attack) {
        if (rt == null || attack == null || !(attack.attacker instanceof EUnit)) return;
        EUnit u = (EUnit) attack.attacker;
        int[] idx = u.index;
        if (idx == null || idx.length < 2 || !validSlot(idx[0], idx[1])) return;
        int n = rt.boosterSlot.boosts[idx[0]][idx[1]][S_DAMAGE];
        if (n <= 0) return;
        if (!rt.boosterSlot.damageScaled.add(attack)) return;
        long next = Math.round((double) attack.atk * (1.0 + 0.01 * n));
        attack.atk = clampInt(next);
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || bbpainter == null || gra == null || !enabled(rt)) return;
        State st = rt.boosterSlot;
        StageBasis sb = (StageBasis) rt.stage;
        SlotGrid grid = slotGrid(bbpainter, sb);
        if (grid == null) return;
        Rect dockR = dockRect(grid);
        drawDock(sb, gra, dockR, st);
        if (st.dragging && st.dragMoved) {
            boolean hot = dockR.contains(st.dragX, st.dragY);
            drawGhostAt(sb, gra, st.dragRow, st.dragCol,
                    hot ? dockR.cx() : st.dragX, hot ? dockR.cy() : st.dragY, dockR.w);
        } else if (st.returning) {
            Rect home = slotRect(grid, st.dragRow, st.dragCol);
            if (home != null) {
                float p = clamp01(st.returnFrame / (float) RETURN_FRAMES);
                float ease = 1f - (1f - p) * (1f - p);
                int x = Math.round(st.returnFromX + (home.cx() - st.returnFromX) * ease);
                int y = Math.round(st.returnFromY + (home.cy() - st.returnFromY) * ease);
                drawGhostAt(sb, gra, st.dragRow, st.dragCol, x, y, dockR.w);
            } else {
                st.returning = false;
            }
        }
        if (st.dockedRow >= 0) {
            drawOverlay(rt, sb, bbpainter, gra);
        }
    }

    private static void drawDock(StageBasis sb, FakeGraphics gra, Rect dock, State st) {
        boolean docked = st.dockedRow >= 0;
        boolean hot = st.dragging && st.dragMoved && dock.contains(st.dragX, st.dragY);
        boolean near = st.dragging && st.dragMoved && nearDock(dock, st.dragX, st.dragY);

        float target = (docked || near) ? 1f : 0f;
        st.dockExpand += (target - st.dockExpand) * 0.30f;
        if (Math.abs(st.dockExpand - target) < 0.02f) st.dockExpand = target;
        float e = clamp01(st.dockExpand);
        int minD = collapsedDockDiameter(dock);
        int cw = Math.round(minD + (dock.w - minD) * e);
        int ch = Math.round(minD + (dock.h - minD) * e);
        int rx = dock.cx() - cw / 2;
        int ry = dock.cy() - ch / 2;
        int arc = Math.max(10, Math.round(Math.min(cw, ch) * (1f - e)));
        if (!st.dockLogged) {
            st.dockLogged = true;
            Logger.log("Booster Slot dock visible rect=(" + dock.x + "," + dock.y + ","
                    + dock.w + "," + dock.h + ")");
        }
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g != null) {
            DrawState ds = pushOverlay(g);
            try {
                g.setComposite(AlphaComposite.SrcOver.derive(docked || hot ? 0.85f : 0.55f));
                g.setColor(new Color(20, 22, 28));
                g.fillRoundRect(rx, ry, cw, ch, arc, arc);
                g.setComposite(AlphaComposite.SrcOver);
                if (docked) {
                    g.setStroke(new BasicStroke(3f));
                } else {
                    g.setStroke(new BasicStroke(hot ? 4f : 3f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND, 1f, new float[]{7f, 6f}, 0f));
                }
                g.setColor(hot ? new Color(98, 255, 142) : new Color(255, 226, 142, docked ? 235 : 200));
                g.drawRoundRect(rx, ry, cw, ch, arc, arc);
                if (!docked && e > 0.75f) {
                    int a = clampInt(Math.round(((e - 0.75f) / 0.25f) * (hot ? 255 : 190)), 0, 255);
                    drawCenteredText(g, "BOOST", dock.cx(), dock.cy(),
                            Math.max(11, dock.h / 5), new Color(255, 226, 142, a));
                }
            } finally {
                popOverlay(g, ds);
            }
        } else if (e < 0.5f) {

            int d = Math.min(cw, ch);
            drawPrimitiveEllipse(gra, dock.cx(), dock.cy(), d + 8, d + 8,
                    hot ? 98 : 255, hot ? 255 : 226, 142, 235);
            drawPrimitiveEllipse(gra, dock.cx(), dock.cy(), d, d,
                    20, 22, 28, docked || hot ? 230 : 175);
        } else {
            gra.colRect(rx, ry, cw, ch, 20, 22, 28, docked || hot ? 220 : 140);
            drawPrimitiveRectOutline(gra, rx, ry, cw, ch, hot ? 4 : 3,
                    hot ? 98 : 255, hot ? 255 : 226, hot ? 142 : 142, 230);
        }
        if (docked) {
            FakeImage icon = playerIcon(formAt(sb, st.dockedRow, st.dockedCol));
            if (icon != null) {
                drawImageAt(gra, icon, dock.cx(), dock.cy(), Math.round(dock.w * 0.92f), 255);
            }
            Graphics2D g2 = CrazyRender.unwrap(gra);
            if (g2 != null) {
                DrawState ds = pushOverlay(g2);
                try {
                    int total = slotTotal(st, st.dockedRow, st.dockedCol);
                    drawCenteredText(g2, "+" + total, dock.x + dock.w - 14, dock.y + 10,
                            12, new Color(98, 255, 142));
                } finally {
                    popOverlay(g2, ds);
                }
            }
        }
    }

    private static void drawGhostAt(StageBasis sb, FakeGraphics gra, int row, int col,
                                    int cx, int cy, int size) {
        FakeImage icon = playerIcon(formAt(sb, row, col));
        if (icon == null) return;
        drawImageAt(gra, icon, cx, cy, size, 190);
    }

    private static void drawImageAt(FakeGraphics gra, FakeImage icon, int cx, int cy, int size, int alpha) {
        FakeTransform old = pushIdentityTransform(gra);
        try {
            try { gra.setComposite(FakeGraphics.TRANS, clampInt(alpha, 0, 255), 0); } catch (Throwable ignored) {}
            float iw = size;
            float ih = size * icon.getHeight() / (float) Math.max(1, icon.getWidth());
            gra.drawImage(icon, cx - iw / 2f, cy - ih / 2f, iw, ih);
        } catch (Throwable ignored) {
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void drawOverlay(CrazyRuntime.StageRuntime rt, StageBasis sb, Object bbpainter,
                                    FakeGraphics gra) {
        State st = rt.boosterSlot;
        StatBlock stats = currentStats(rt, sb);
        if (stats == null) return;
        int w = BBPainterAccess.getWidth(bbpainter);
        int h = BBPainterAccess.getHeight(bbpainter);
        Rect panel = overlayPanel(w, h);
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) {
            drawOverlayPrimitive(st, sb, stats, w, h, panel, gra);
            return;
        }
        DrawState ds = pushOverlay(g);
        try {

            g.setComposite(AlphaComposite.SrcOver.derive(0.62f));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            drawPanel(g, panel);
            drawTitle(g, "Booster Slot", panel.x + 28, panel.y + 42);
            drawSmallText(g, "Slot " + (st.dockedRow + 1) + "-" + (st.dockedCol + 1),
                    panel.x + 28, panel.y + 68, 16, new Color(218, 220, 226));

            int x = panel.x + Math.round(panel.w * 0.26f);
            drawSmallText(g, "Stat", x, rowY(panel, 0) - 30, 14, new Color(166, 174, 188));
            drawSmallText(g, "Value", x + 135, rowY(panel, 0) - 30, 14, new Color(166, 174, 188));
            drawSmallText(g, "Boost", x + 285, rowY(panel, 0) - 30, 14, new Color(166, 174, 188));
            drawSmallText(g, "Cost", x + 360, rowY(panel, 0) - 30, 14, new Color(166, 174, 188));
            for (int i = 0; i < S_COUNT; i++) {
                drawStatRow(g, st, sb, stats, w, h, panel, x, i);
            }
            drawButton(g, removeButton(w, h), "Remove", true, false);
            drawSmallText(g, "Boosts are kept after removing; effects last this battle only.",
                    panel.x + 28, panel.y + panel.h - 26, 14, new Color(176, 184, 198));
        } finally {
            popOverlay(g, ds);
        }
        FakeImage icon = playerIcon(formAt(sb, st.dockedRow, st.dockedCol));
        if (icon != null) {
            drawImageAt(gra, icon, panel.x + Math.round(panel.w * 0.14f),
                    panel.y + Math.round(panel.h * 0.42f), Math.round(panel.w * 0.15f), 255);
        }
    }

    private static void drawStatRow(Graphics2D g, State st, StageBasis sb, StatBlock stats, int w, int h,
                                    Rect panel, int x, int stat) {
        int y = rowY(panel, stat);
        boolean flash = st.flashStat == stat && st.flashFrame > 0;
        if (flash) {
            float p = st.flashFrame / (float) FLASH_FRAMES;
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(new Color(54, 210, 116, Math.round(65f * p)));
            g.fillRoundRect(x - 10, y - 22, Math.round(panel.w * 0.66f), 36, 8, 8);
        }
        int n = st.boosts[st.dockedRow][st.dockedCol][stat];
        int cost = upgradeCost(n);
        boolean affordable = sb != null && sb.money >= cost * INTERNAL_MONEY_MULT;
        drawSmallText(g, STAT_NAMES[stat], x, y, 17, new Color(245, 246, 248));
        drawSmallText(g, statValue(stats, stat), x + 135, y, 17, new Color(235, 238, 243));
        drawSmallText(g, boostBadge(stat, n), x + 285, y, 16,
                n > 0 ? new Color(58, 224, 118) : new Color(140, 146, 158));
        drawSmallText(g, format(cost) + "$", x + 360, y, 16,
                affordable ? new Color(255, 238, 140) : new Color(140, 140, 140));
        drawButton(g, plusButton(w, h, stat), "+", affordable, true);
        drawMaxButton(g, maxButton(w, h, stat), affordable);
    }

    private static void drawMaxButton(Graphics2D g, Rect r, boolean enabled) {
        Color bg = enabled ? new Color(112, 52, 52) : new Color(58, 58, 62);
        Color rim = enabled ? new Color(240, 96, 96) : new Color(120, 120, 124);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(bg);
        g.fillRoundRect(r.x, r.y, r.w, r.h, 7, 7);
        g.setStroke(new BasicStroke(2f));
        g.setColor(rim);
        g.drawRoundRect(r.x, r.y, r.w, r.h, 7, 7);
        Font old = g.getFont();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            FontMetrics fm = g.getFontMetrics();
            int tx = r.cx() - fm.stringWidth("+") / 2;
            int ty = r.cy() + (fm.getAscent() - fm.getDescent()) / 2;
            g.setColor(enabled ? new Color(255, 224, 224) : new Color(170, 170, 170));
            g.drawString("+", tx, ty);
        } finally {
            g.setFont(old);
        }
    }

    private static void drawOverlayPrimitive(State st, StageBasis sb, StatBlock stats, int w, int h,
                                             Rect panel, FakeGraphics gra) {
        FakeTransform old = pushIdentityTransform(gra);
        try {
            gra.colRect(0, 0, w, h, 0, 0, 0, 150);
            gra.colRect(panel.x, panel.y, panel.w, panel.h, 24, 27, 34, 245);
            drawPrimitiveRectOutline(gra, panel.x, panel.y, panel.w, panel.h, 4, 255, 226, 142, 210);
            drawPrimitiveText(gra, "BOOSTER SLOT", panel.x + 28, panel.y + 22, 4, 255, 240, 178, 255);
            drawPrimitiveText(gra, "SLOT " + (st.dockedRow + 1) + "-" + (st.dockedCol + 1),
                    panel.x + 28, panel.y + 58, 2, 218, 220, 226, 255);
            int x = panel.x + Math.round(panel.w * 0.24f);
            for (int i = 0; i < S_COUNT; i++) {
                int y = rowY(panel, i) - 12;
                int n = st.boosts[st.dockedRow][st.dockedCol][i];
                int cost = upgradeCost(n);
                boolean affordable = sb != null && sb.money >= cost * INTERNAL_MONEY_MULT;
                drawPrimitiveText(gra, STAT_NAMES[i], x, y, 2, 245, 246, 248, 255);
                drawPrimitiveText(gra, statValue(stats, i), x + 170, y, 2, 235, 238, 243, 255);
                drawPrimitiveText(gra, boostBadge(i, n), x + 330, y, 2, 58, 224, 118, 255);
                drawPrimitiveText(gra, format(cost) + "$", x + 380, y, 2,
                        affordable ? 255 : 140, affordable ? 238 : 140, 140, 255);
                drawPrimitiveButton(gra, plusButton(w, h, i), "+", affordable, true);
                drawPrimitiveMaxButton(gra, maxButton(w, h, i), affordable);
            }
            drawPrimitiveButton(gra, removeButton(w, h), "REMOVE", true, false);
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static StatBlock currentStats(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        State st = rt.boosterSlot;
        if (st.dockedRow < 0) return null;
        Form f = formAt(sb, st.dockedRow, st.dockedCol);
        if (f == null) return null;
        int price = priceAt(sb, st.dockedRow, st.dockedCol);
        String key = statKey(st, f, price);
        if (key.equals(st.statCacheKey) && st.statCache != null) return st.statCache;
        StatBlock out = computeStats(st, sb, st.dockedRow, st.dockedCol, price);
        st.statCacheKey = key;
        st.statCache = out;
        return out;
    }

    private static String statKey(State st, Form f, int price) {
        StringBuilder k = new StringBuilder(64);
        k.append(System.identityHashCode(f)).append(':').append(price)
                .append(':').append(st.dockedRow).append(':').append(st.dockedCol);
        int[] b = st.boosts[st.dockedRow][st.dockedCol];
        for (int i = 0; i < S_COUNT; i++) k.append(':').append(b[i]);
        return k.toString();
    }

    private static StatBlock computeStats(State st, StageBasis sb, int row, int col, int price) {
        StatBlock out = new StatBlock();
        int[] b = st.boosts[row][col];
        out.scalePct = 100 + Math.max(0, b[S_SCALE]);
        out.price = Math.max(0, price) / 100;
        try {
            EForm ef = sb.b.lu.efs[row][col];
            if (ef != null) {
                EUnit sample = ef.getEntity(sb, null, false, false);
                if (sample != null) {
                    out.hp = scaleLong(Math.max(1L, sample.maxH), b[S_HP]);
                    out.damage = scaleLong(Math.max(1L, totalAttack(sample)), b[S_DAMAGE]);
                    int baseSpeed = Math.max(1, sample.data.getSpeed());
                    out.speed = Math.max(1, Math.round(baseSpeed * (1f + Math.max(0, b[S_SPEED]) * 0.01f)));
                    out.tba = reduceFrames(Math.max(1, sample.data.getTBA()), b[S_TBA]);
                    out.atkFrames = reduceFrames(Math.max(1, animFrames(sample.data)), b[S_ATK_SPEED]);
                }
            }
        } catch (Throwable t) {
            Logger.err("Booster Slot stat preview failed", t);
        }
        return out;
    }

    private static String statValue(StatBlock s, int stat) {
        switch (stat) {
            case S_PRICE: return s.price + "$";
            case S_SCALE: return s.scalePct + "%";
            case S_DAMAGE: return format(s.damage);
            case S_HP: return format(s.hp);
            case S_SPEED: return Integer.toString(s.speed);
            case S_ATK_SPEED: return s.atkFrames + "f";
            case S_TBA: return s.tba + "f";
            default: return "";
        }
    }

    private static String boostBadge(int stat, int n) {
        if (n <= 0) return "+0%";

        if (stat == S_PRICE || stat == S_ATK_SPEED || stat == S_TBA) return "-" + n + "%";
        return "+" + n + "%";
    }

    private static SlotGrid slotGrid(Object bbpainter, StageBasis sb) {
        if (bbpainter == null || sb == null) return null;
        try {
            float hr = BCUFields.getFloat(bbpainter, "unir");
            if (hr <= 0f || !finite(hr)) return null;
            FakeImage slot = nativeSlotImage(bbpainter);
            if (slot == null || !slot.isValid()) return null;
            SlotGrid g = new SlotGrid();
            g.w = BBPainterAccess.getWidth(bbpainter);
            g.h = BBPainterAccess.getHeight(bbpainter);
            g.iw = (int) (hr * slot.getWidth());
            g.ih = (int) (hr * slot.getHeight());
            if (g.iw <= 0 || g.ih <= 0) return null;
            g.term = hr * slot.getWidth() * 0.2f;
            g.termh = hr * slot.getHeight() * 0.1f;
            g.twoRow = CommonStatic.getConfig().twoRow;
            g.frontRow = clampInt(sb.frontLineup, 0, 1);
            return g;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Rect slotRect(SlotGrid g, int row, int col) {
        if (g == null || !validSlot(row, col)) return null;
        if (g.twoRow) {
            int x = (g.w - g.iw * 5) / 2 + g.iw * col + (int) (g.term * (col - 2));
            int y = (int) (g.h - (2 - row) * (g.ih + g.termh));
            return new Rect(x, y, g.iw, g.ih);
        }
        if (row != g.frontRow) return null;
        int x = (g.w - g.iw * 5) / 2 + g.iw * col
                + (int) (g.term * (col - 2) + (g.frontRow == 0 ? 0f : g.term / 2f));
        int y = g.h - (int) (g.ih * 1.1f);
        return new Rect(x, y, g.iw, g.ih);
    }

    private static int[] slotAt(SlotGrid g, int x, int y) {
        if (g == null) return null;
        if (g.twoRow) {
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 5; j++) {
                    Rect r = slotRect(g, i, j);
                    if (r != null && r.contains(x, y)) return new int[]{i, j};
                }
            }
            return null;
        }
        for (int j = 0; j < 5; j++) {
            Rect r = slotRect(g, g.frontRow, j);
            if (r != null && r.contains(x, y)) return new int[]{g.frontRow, j};
        }
        return null;
    }

    private static int collapsedDockDiameter(Rect dock) {
        return Math.max(18, Math.round(Math.min(dock.w, dock.h) * 0.42f));
    }

    private static boolean nearDock(Rect dock, int x, int y) {
        long dx = x - dock.cx();
        long dy = y - dock.cy();
        long r = Math.round(Math.max(dock.w, dock.h) * 2.2f);
        return dx * dx + dy * dy <= r * r;
    }

    private static boolean dockConsumesPress(Rect dock, State st, int x, int y) {
        if (st.dockExpand > 0.5f) return dock.contains(x, y);
        long dx = x - dock.cx();
        long dy = y - dock.cy();
        long r = collapsedDockDiameter(dock) / 2 + 6;
        return dx * dx + dy * dy <= r * r;
    }

    private static Rect dockRect(SlotGrid g) {
        int cx = Math.round(g.w * 0.05f);
        int cy = Math.round(g.h * 0.74f);
        int x = Math.max(8, Math.min(cx - g.iw / 2, g.w - g.iw - 8));
        int y = Math.max(8, Math.min(cy - g.ih / 2, g.h - g.ih - 8));
        return new Rect(x, y, g.iw, g.ih);
    }

    private static Rect overlayPanel(int w, int h) {
        int pw = clampInt(Math.round(w * 0.62f), 800, Math.max(800, w - 80));
        int ph = clampInt(Math.round(h * 0.66f), 500, Math.max(500, h - 90));
        int y = Math.max(14, (h - ph) / 2 - Math.round(h * 0.05f));
        return new Rect((w - pw) / 2, y, pw, ph);
    }

    private static int rowY(Rect panel, int stat) {
        return panel.y + 118 + stat * 44;
    }

    private static Rect plusButton(int w, int h, int stat) {
        Rect p = overlayPanel(w, h);
        return new Rect(p.x + p.w - 152, rowY(p, stat) - 20, 44, 32);
    }

    private static Rect maxButton(int w, int h, int stat) {
        Rect p = overlayPanel(w, h);
        return new Rect(p.x + p.w - 100, rowY(p, stat) - 20, 44, 32);
    }

    private static Rect removeButton(int w, int h) {
        Rect p = overlayPanel(w, h);
        return new Rect(p.x + p.w - 132, p.y + p.h - 64, 104, 38);
    }

    private static boolean enabled(CrazyRuntime.StageRuntime rt) {
        return rt != null && rt.config.boosterSlot;
    }

    private static boolean isBattleCanvasEvent(Object page, MouseEvent e) {
        try {
            Object bb = BCUFields.get(page, "bb");
            return e.getSource() == bb;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object painterFromPage(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            return BCUFields.get(bb, "bbp");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int pageWidth(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            return ((Number) BCUFields.invoke(bb, "getWidth")).intValue();
        } catch (Throwable ignored) {
            return 1200;
        }
    }

    private static int pageHeight(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            return ((Number) BCUFields.invoke(bb, "getHeight")).intValue();
        } catch (Throwable ignored) {
            return 800;
        }
    }

    private static Form formAt(StageBasis sb, int row, int col) {
        try {
            return sb.b.lu.fs[row][col];
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int priceAt(StageBasis sb, int row, int col) {
        try {
            return sb.elu.price[row][col];
        } catch (Throwable ignored) {
            return -1;
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

    private static FakeImage playerIcon(Form form) {
        try {
            if (form == null || !(form.anim instanceof AnimU)) return null;
            return ((AnimU<?>) form.anim).getUni().getImg();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean battleRunning(StageBasis sb) {
        try {
            return sb != null && sb.ubase != null && sb.ebase != null
                    && sb.ubase.health > 0L && sb.ebase.health > 0L && sb.s_stop == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long totalAttack(Entity e) {
        long sum = 0L;
        try {
            Object aam = BCUFields.get(e, "aam");
            int[] atks = (int[]) BCUFields.get(aam, "atks");
            for (int i = 0; i < atks.length; i++) if (atks[i] > 0) sum += atks[i];
        } catch (Throwable ignored) {
            try { sum = Math.max(1, e.getAtk()); } catch (Throwable ignored2) {}
        }
        return Math.max(1L, sum);
    }

    private static int animFrames(MaskEntity data) {
        try {
            return Math.max(1, data.getAnimLen());
        } catch (Throwable ignored) {
            try { return Math.max(1, ((Number) BCUFields.invoke(data, "getAnimLen")).intValue()); }
            catch (Throwable ignored2) { return 1; }
        }
    }

    private static int slotTotal(State st, int row, int col) {
        if (!validSlot(row, col)) return 0;
        int sum = 0;
        for (int i = 0; i < S_COUNT; i++) sum += Math.max(0, st.boosts[row][col][i]);
        return sum;
    }

    private static boolean validSlot(int row, int col) {
        return row >= 0 && row < 2 && col >= 0 && col < 5;
    }

    private static final class DrawState {
        AffineTransform transform;
        java.awt.Composite composite;
        Color color;
        Stroke stroke;
        Font font;
        Object aa;
    }

    private static DrawState pushOverlay(Graphics2D g) {
        DrawState ds = new DrawState();
        ds.transform = g.getTransform();
        ds.composite = g.getComposite();
        ds.color = g.getColor();
        ds.stroke = g.getStroke();
        ds.font = g.getFont();
        ds.aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setTransform(new AffineTransform());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return ds;
    }

    private static void popOverlay(Graphics2D g, DrawState ds) {
        g.setTransform(ds.transform);
        g.setComposite(ds.composite);
        g.setColor(ds.color);
        g.setStroke(ds.stroke);
        g.setFont(ds.font);
        if (ds.aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, ds.aa);
    }

    private static FakeTransform pushIdentityTransform(FakeGraphics gra) {
        try {
            FakeTransform oldTransform = gra.getTransform();
            FakeTransform identity = gra.getTransform();
            Field f = transformDataField;
            if (f == null || f.getDeclaringClass() != identity.getClass()) {
                f = identity.getClass().getDeclaredField("data");
                f.setAccessible(true);
                transformDataField = f;
            }
            f.set(identity, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            gra.setTransform(identity);
            try { gra.delete(identity); } catch (Throwable ignored) {}
            return oldTransform;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void popTransform(FakeGraphics gra, FakeTransform oldTransform) {
        if (oldTransform == null) return;
        try {
            gra.setTransform(oldTransform);
        } catch (Throwable ignored) {
        } finally {
            try { gra.delete(oldTransform); } catch (Throwable ignored) {}
        }
    }

    private static void resetComposite(FakeGraphics gra) {
        try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private static void drawPanel(Graphics2D g, Rect r) {
        g.setComposite(AlphaComposite.SrcOver.derive(0.96f));
        g.setColor(new Color(24, 27, 34));
        g.fillRoundRect(r.x, r.y, r.w, r.h, 8, 8);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(255, 226, 142, 180));
        g.drawRoundRect(r.x, r.y, r.w, r.h, 8, 8);
    }

    private static void drawTitle(Graphics2D g, String text, int x, int y) {
        drawSmallText(g, text, x, y, 28, new Color(255, 240, 178));
    }

    private static void drawSmallText(Graphics2D g, String text, int x, int y, int size, Color color) {
        Font old = g.getFont();
        Color oc = g.getColor();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
            g.setColor(new Color(0, 0, 0, Math.min(170, color.getAlpha())));
            g.drawString(text, x + 1, y + 1);
            g.setColor(color);
            g.drawString(text, x, y);
        } finally {
            g.setFont(old);
            g.setColor(oc);
        }
    }

    private static void drawCenteredText(Graphics2D g, String text, int cx, int cy, int size, Color color) {
        Font old = g.getFont();
        Color oc = g.getColor();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
            FontMetrics fm = g.getFontMetrics();
            int tx = cx - fm.stringWidth(text) / 2;
            int ty = cy + (fm.getAscent() - fm.getDescent()) / 2;
            g.setColor(new Color(0, 0, 0, Math.min(170, color.getAlpha())));
            g.drawString(text, tx + 1, ty + 1);
            g.setColor(color);
            g.drawString(text, tx, ty);
        } finally {
            g.setFont(old);
            g.setColor(oc);
        }
    }

    private static void drawButton(Graphics2D g, Rect r, String text, boolean enabled, boolean compact) {
        Color bg = enabled ? new Color(64, 94, 72) : new Color(58, 58, 62);
        Color rim = enabled ? new Color(95, 232, 122) : new Color(120, 120, 124);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(bg);
        g.fillRoundRect(r.x, r.y, r.w, r.h, 7, 7);
        g.setStroke(new BasicStroke(2f));
        g.setColor(rim);
        g.drawRoundRect(r.x, r.y, r.w, r.h, 7, 7);
        Font old = g.getFont();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, compact ? 20 : 16));
            FontMetrics fm = g.getFontMetrics();
            int tx = r.cx() - fm.stringWidth(text) / 2;
            int ty = r.cy() + (fm.getAscent() - fm.getDescent()) / 2;
            g.setColor(enabled ? new Color(245, 255, 242) : new Color(170, 170, 170));
            g.drawString(text, tx, ty);
        } finally {
            g.setFont(old);
        }
    }

    private static void drawPrimitiveRectOutline(FakeGraphics gra, int x, int y, int w, int h, int t,
                                                 int r, int g, int b, int alpha) {
        if (gra == null || w <= 0 || h <= 0 || t <= 0 || alpha <= 0) return;
        gra.colRect(x, y, w, t, r, g, b, alpha);
        gra.colRect(x, y + h - t, w, t, r, g, b, alpha);
        gra.colRect(x, y, t, h, r, g, b, alpha);
        gra.colRect(x + w - t, y, t, h, r, g, b, alpha);
    }

    private static void drawPrimitiveButton(FakeGraphics gra, Rect r, String text, boolean enabled,
                                            boolean compact) {
        gra.colRect(r.x, r.y, r.w, r.h, enabled ? 64 : 58, enabled ? 94 : 58, enabled ? 72 : 62, 238);
        drawPrimitiveRectOutline(gra, r.x, r.y, r.w, r.h, 3,
                enabled ? 95 : 120, enabled ? 232 : 120, enabled ? 122 : 124, 255);
        int scale = compact ? 4 : 2;
        while (scale > 1 && primitiveTextWidth(text, scale) > r.w - 14) scale--;
        int tx = r.cx() - primitiveTextWidth(text, scale) / 2;
        int ty = r.cy() - (7 * scale) / 2;
        drawPrimitiveText(gra, text, tx, ty, scale,
                enabled ? 245 : 170, enabled ? 255 : 170, enabled ? 242 : 170, 255);
    }

    private static void drawPrimitiveEllipse(FakeGraphics gra, int cx, int cy, int w, int h,
                                             int r, int g, int b, int alpha) {
        if (gra == null || w <= 0 || h <= 0 || alpha <= 0) return;
        int rx = Math.max(1, w / 2);
        int ry = Math.max(1, h / 2);
        int step = Math.max(2, Math.min(rx, ry) / 18);
        for (int dy = -ry; dy <= ry; dy += step) {
            float yy = dy / (float) ry;
            int span = Math.round(rx * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
            gra.colRect(cx - span, cy + dy, span * 2 + 1, step + 1, r, g, b, alpha);
        }
    }

    private static void drawPrimitiveMaxButton(FakeGraphics gra, Rect r, boolean enabled) {
        gra.colRect(r.x, r.y, r.w, r.h, enabled ? 112 : 58, enabled ? 52 : 58, enabled ? 52 : 62, 238);
        drawPrimitiveRectOutline(gra, r.x, r.y, r.w, r.h, 3,
                enabled ? 240 : 120, enabled ? 96 : 120, enabled ? 96 : 124, 255);
        int scale = 4;
        int tx = r.cx() - primitiveTextWidth("+", scale) / 2;
        int ty = r.cy() - (7 * scale) / 2;
        drawPrimitiveText(gra, "+", tx, ty, scale,
                enabled ? 255 : 170, enabled ? 224 : 170, enabled ? 224 : 170, 255);
    }

    private static void drawPrimitiveText(FakeGraphics gra, String text, int x, int y, int scale,
                                          int r, int g, int b, int alpha) {
        if (gra == null || text == null || scale <= 0 || alpha <= 0) return;
        int cx = x;
        String s = text.toUpperCase();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            drawPrimitiveGlyph(gra, ch, cx, y, scale, r, g, b, alpha);
            cx += 6 * scale;
        }
    }

    private static int primitiveTextWidth(String text, int scale) {
        if (text == null || text.length() == 0) return 0;
        return Math.max(0, text.length() * 6 * scale - scale);
    }

    private static void drawPrimitiveGlyph(FakeGraphics gra, char ch, int x, int y, int scale,
                                           int r, int g, int b, int alpha) {
        if (ch == ' ') return;
        String[] rows = primitiveGlyphRows(ch);
        for (int yy = 0; yy < rows.length; yy++) {
            String row = rows[yy];
            for (int xx = 0; xx < row.length(); xx++) {
                if (row.charAt(xx) != '1') continue;
                gra.colRect(x + xx * scale, y + yy * scale, scale, scale, r, g, b, alpha);
            }
        }
    }

    private static String[] primitiveGlyphRows(char ch) {
        switch (ch) {
            case 'A': return new String[]{"01110","10001","10001","11111","10001","10001","10001"};
            case 'B': return new String[]{"11110","10001","10001","11110","10001","10001","11110"};
            case 'C': return new String[]{"01111","10000","10000","10000","10000","10000","01111"};
            case 'D': return new String[]{"11110","10001","10001","10001","10001","10001","11110"};
            case 'E': return new String[]{"11111","10000","10000","11110","10000","10000","11111"};
            case 'F': return new String[]{"11111","10000","10000","11110","10000","10000","10000"};
            case 'G': return new String[]{"01111","10000","10000","10111","10001","10001","01111"};
            case 'H': return new String[]{"10001","10001","10001","11111","10001","10001","10001"};
            case 'I': return new String[]{"11111","00100","00100","00100","00100","00100","11111"};
            case 'K': return new String[]{"10001","10010","10100","11000","10100","10010","10001"};
            case 'L': return new String[]{"10000","10000","10000","10000","10000","10000","11111"};
            case 'M': return new String[]{"10001","11011","10101","10101","10001","10001","10001"};
            case 'N': return new String[]{"10001","11001","10101","10011","10001","10001","10001"};
            case 'O': return new String[]{"01110","10001","10001","10001","10001","10001","01110"};
            case 'P': return new String[]{"11110","10001","10001","11110","10000","10000","10000"};
            case 'R': return new String[]{"11110","10001","10001","11110","10100","10010","10001"};
            case 'S': return new String[]{"01111","10000","10000","01110","00001","00001","11110"};
            case 'T': return new String[]{"11111","00100","00100","00100","00100","00100","00100"};
            case 'U': return new String[]{"10001","10001","10001","10001","10001","10001","01110"};
            case 'V': return new String[]{"10001","10001","10001","10001","10001","01010","00100"};
            case '0': return new String[]{"01110","10001","10011","10101","11001","10001","01110"};
            case '1': return new String[]{"00100","01100","00100","00100","00100","00100","01110"};
            case '2': return new String[]{"01110","10001","00001","00010","00100","01000","11111"};
            case '3': return new String[]{"11110","00001","00001","01110","00001","00001","11110"};
            case '4': return new String[]{"00010","00110","01010","10010","11111","00010","00010"};
            case '5': return new String[]{"11111","10000","10000","11110","00001","00001","11110"};
            case '6': return new String[]{"01110","10000","10000","11110","10001","10001","01110"};
            case '7': return new String[]{"11111","00001","00010","00100","01000","01000","01000"};
            case '8': return new String[]{"01110","10001","10001","01110","10001","10001","01110"};
            case '9': return new String[]{"01110","10001","10001","01111","00001","00001","01110"};
            case '+': return new String[]{"00000","00100","00100","11111","00100","00100","00000"};
            case '-': return new String[]{"00000","00000","00000","11111","00000","00000","00000"};
            case '%': return new String[]{"11001","11010","00010","00100","01000","01011","10011"};
            case '$': return new String[]{"00100","01111","10100","01110","00101","11110","00100"};
            case ',': return new String[]{"00000","00000","00000","00000","00000","00100","01000"};
            case '.': return new String[]{"00000","00000","00000","00000","00000","01100","01100"};
            default: return new String[]{"11111","00001","00010","00100","00100","00000","00100"};
        }
    }

    private static int upgradeCost(int clicks) {
        long cost = (long) (Math.max(0, clicks) + 1) * 100L;
        return cost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cost;
    }

    private static int reduceFrames(int base, int level) {
        if (base <= 1) return 1;
        float factor = Math.max(0f, 1f - Math.max(0, level) * 0.01f);
        return Math.max(1, Math.round(base * factor));
    }

    private static long scaleLong(long base, int percent) {
        double v = (double) Math.max(1L, base) * (1.0d + Math.max(0, percent) * 0.01d);
        if (v > Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, Math.round(v));
    }

    private static void play(int id) {
        try { CommonStatic.setSE(id); } catch (Throwable ignored) {}
    }

    private static void reject() {
        play(15);
    }

    private static String format(long v) {
        return String.format(java.util.Locale.US, "%,d", v);
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clampInt(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
