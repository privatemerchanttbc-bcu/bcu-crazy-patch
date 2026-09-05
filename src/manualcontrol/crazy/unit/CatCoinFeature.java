package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.entity.AbEntity;
import common.battle.entity.Entity;
import common.battle.entity.EUnit;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.stage.Limit;
import common.util.stage.StageMap;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyPreloadService;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
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
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.imageio.ImageIO;

public final class CatCoinFeature {

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_CHOOSING = 1;
    private static final int PHASE_FLIPPING = 2;
    private static final int PHASE_RESULT = 3;
    private static final int PHASE_SPAWNING = 4;
    private static final int PHASE_COOLDOWN = 5;

    private static final int MIN_MONEY = 50 * 100;
    private static final int COOLDOWN_FRAMES = 150;
    private static final int FLIP_FRAMES = 90;
    private static final int RESULT_FRAMES = 30;
    private static final int EMERGE_FRAMES = 45;
    private static final int SHAKE_FRAMES = 18;
    private static final int RANDOM_SAMPLE_TRIES = 360;
    private static final float SPEND_THRESHOLD = 0.85f;

    private static final String HEAD_RESOURCE = "/manualcontrol/crazy/unit/cat_coin_head.png";
    private static final String TAIL_RESOURCE = "/manualcontrol/crazy/unit/cat_coin_tail.png";

    private static volatile FakeImage headImage;
    private static volatile FakeImage tailImage;
    private static volatile boolean headLoadAttempted;
    private static volatile boolean tailLoadAttempted;
    private static volatile Field transformDataField;

    private CatCoinFeature() {}

    public static int preloadFlags() {
        return CrazyPreloadService.PRELOAD_ALL_PLAYER_FORMS;
    }

    public static final class State {
        public final Object lock = new Object();
        public final ArrayList<Candidate> pool = new ArrayList<Candidate>();
        public final ArrayList<SpawnJob> spawns = new ArrayList<SpawnJob>();
        public final Map<Object, Boolean> enemyOwned = new WeakHashMap<Object, Boolean>();
        public boolean initialized;
        public boolean poolWarningLogged;
        public int minCost = Integer.MAX_VALUE;
        public int phase = PHASE_IDLE;
        public int frame;
        public int cooldown;
        public int shakeFrame;
        public boolean choiceHead;
        public boolean resultHead;
        public boolean win;
        public int stake;
        public int spent;
        public Selection selection;
    }

    private static final class Candidate {
        final Form form;
        final int cost;

        Candidate(Form form, int cost) {
            this.form = form;
            this.cost = cost;
        }
    }

    private static final class Selection {
        final Candidate[] candidates;
        final int cost;

        Selection(Candidate[] candidates, int cost) {
            this.candidates = candidates;
            this.cost = cost;
        }

        int count() {
            return candidates == null ? 0 : candidates.length;
        }
    }

    private static final class SpawnJob {
        final EUnit unit;
        final boolean enemyOwned;
        int age;

        SpawnJob(EUnit unit, boolean enemyOwned) {
            this.unit = unit;
            this.enemyOwned = enemyOwned;
        }
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

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (page == null || e == null || e.getButton() != MouseEvent.BUTTON1) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            ensureInitialized(rt);
            State st = rt.catCoin;

            if (st.phase == PHASE_CHOOSING) {
                Rect head = choiceRect(page, true);
                Rect tail = choiceRect(page, false);
                boolean chooseHead;
                if (head.contains(e.getX(), e.getY())) {
                    chooseHead = true;
                } else if (tail.contains(e.getX(), e.getY())) {
                    chooseHead = false;
                } else {
                    chooseHead = e.getX() < choiceSplitX(page);
                }
                confirmChoice(rt, chooseHead);
                return true;
            }

            Rect icon = iconRectFromPage(page);
            if (!icon.contains(e.getX(), e.getY())) {
                return st.phase != PHASE_IDLE && st.phase != PHASE_COOLDOWN;
            }
            if (st.phase != PHASE_IDLE) {
                beginShake(st);
                return true;
            }
            if (!usable(rt)) {
                beginShake(st);
                try { CommonStatic.setSE(15); } catch (Throwable ignored) {}
                return true;
            }

            synchronized (st.lock) {
                st.phase = PHASE_CHOOSING;
                st.frame = 0;
                st.selection = null;
                st.stake = 0;
                st.spent = 0;
            }
            Logger.log("Cat Coin choosing opened");
            return true;
        } catch (Throwable t) {
            Logger.err("Cat Coin mouse press failed", t);
            return false;
        }
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        State st = rt.catCoin;
        if (st.shakeFrame > 0) st.shakeFrame--;
        if (!rt.config.catCoin && !hasActive(rt)) return;
        ensureInitialized(rt);
        tickSpawns(rt);
        switch (st.phase) {
            case PHASE_CHOOSING:
                st.frame++;
                break;
            case PHASE_FLIPPING:
                st.frame++;
                if (st.frame >= FLIP_FRAMES) {
                    st.phase = PHASE_RESULT;
                    st.frame = 0;
                }
                break;
            case PHASE_RESULT:
                st.frame++;
                if (st.frame >= RESULT_FRAMES) {
                    spawnSelection(rt);
                    st.phase = st.spawns.isEmpty() ? PHASE_COOLDOWN : PHASE_SPAWNING;
                    st.frame = 0;
                    if (st.phase == PHASE_COOLDOWN) st.cooldown = COOLDOWN_FRAMES;
                }
                break;
            case PHASE_SPAWNING:
                st.frame++;
                if (st.spawns.isEmpty()) {
                    st.phase = PHASE_COOLDOWN;
                    st.frame = 0;
                    st.cooldown = COOLDOWN_FRAMES;
                }
                break;
            case PHASE_COOLDOWN:
                if (st.cooldown > 0) st.cooldown--;
                if (st.cooldown <= 0) {
                    st.phase = PHASE_IDLE;
                    st.frame = 0;
                }
                break;
            default:
                break;
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (!enabled(rt) || bbpainter == null || gra == null) return;
        ensureInitialized(rt);
        State st = rt.catCoin;
        drawHudIcon(rt, bbpainter, gra);
        if (st.phase == PHASE_CHOOSING) {
            drawChoiceOverlay(rt, bbpainter, gra);
        } else if (st.phase == PHASE_FLIPPING || st.phase == PHASE_RESULT) {
            drawFlipOverlay(rt, bbpainter, gra);
        }
    }

    public static boolean shouldSkipNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return false;
        int phase = rt.catCoin.phase;
        return phase == PHASE_CHOOSING || phase == PHASE_FLIPPING
                || phase == PHASE_RESULT || phase == PHASE_SPAWNING;
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return false;
        State st = rt.catCoin;
        return st.phase != PHASE_IDLE || !st.spawns.isEmpty();
    }

    @SuppressWarnings("rawtypes")
    public static void filterInRange(CrazyRuntime.StageRuntime rt, List list) {
        if (rt == null || list == null || rt.catCoin.spawns.isEmpty()) return;
        synchronized (rt.catCoin.lock) {
            if (rt.catCoin.spawns.isEmpty()) return;
            for (Iterator it = list.iterator(); it.hasNext();) {
                Object e = it.next();
                if (isEmergingLocked(rt, e)) it.remove();
            }
        }
    }

    public static boolean isManaged(Object entity) {
        return spawnJobFor(entity) != null;
    }

    public static boolean isEmerging(Object entity) {
        SpawnJob job = spawnJobFor(entity);
        return job != null && job.age < EMERGE_FRAMES;
    }

    public static boolean isEnemyOwnedUnit(Object entity) {
        if (!(entity instanceof Entity)) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return false;
        synchronized (rt.catCoin.lock) {
            return rt.catCoin.enemyOwned.containsKey(entity);
        }
    }

    public static float[] emergeDraw(Object entity) {
        SpawnJob job = spawnJobFor(entity);
        if (job == null || job.age >= EMERGE_FRAMES) return null;
        float p = clamp01(job.age / (float) EMERGE_FRAMES);
        float eased = easeOutCubic(p);
        float offsetY = (1f - eased) * 74f;
        float scaleY = 0.25f + 0.75f * eased;
        float alpha = 0.65f + 0.35f * eased;
        return new float[]{offsetY, scaleY, alpha};
    }

    public static void afterEntityDamaged(CrazyRuntime.StageRuntime rt, AbEntity entity, long healthBefore) {
        if (rt == null || entity == null || !isEmergingLocked(rt, entity)) return;
        try {
            if (entity.health < healthBefore) entity.health = healthBefore;
        } catch (Throwable ignored) {}
    }

    private static void confirmChoice(CrazyRuntime.StageRuntime rt, boolean head) {
        State st = rt.catCoin;
        StageBasis sb = (StageBasis) rt.stage;
        if (!hasEnoughStake(rt)) {
            beginShake(st);
            st.phase = PHASE_IDLE;
            Logger.log("Cat Coin confirm rejected: insufficient money after choosing");
            return;
        }

        int stake = Math.max(0, sb.money);
        sb.money = 0;
        Selection selection = selectCombo(rt, stake);
        if (selection == null || selection.cost <= 0) {
            sb.money = clampMoney(sb, stake);
            beginShake(st);
            st.phase = PHASE_COOLDOWN;
            st.cooldown = COOLDOWN_FRAMES;
            Logger.log("Cat Coin selection failed; stake refunded");
            return;
        }

        int refund = Math.max(0, stake - selection.cost);
        if (refund > 0) {
            sb.money = clampMoney(sb, sb.money + refund);
        }

        st.choiceHead = head;
        st.resultHead = randInt(sb, 2) == 0;
        st.win = st.choiceHead == st.resultHead;
        st.stake = stake;
        st.spent = selection.cost;
        st.selection = selection;
        st.phase = PHASE_FLIPPING;
        st.frame = 0;
        try { CommonStatic.setSE(19); } catch (Throwable ignored) {}
        Logger.log("Cat Coin flip: choice=" + (head ? "HEAD" : "TAIL")
                + " result=" + (st.resultHead ? "HEAD" : "TAIL")
                + " win=" + st.win
                + " stake=" + stake
                + " spent=" + selection.cost
                + " count=" + selection.count());
    }

    private static void spawnSelection(CrazyRuntime.StageRuntime rt) {
        State st = rt.catCoin;
        Selection sel = st.selection;
        if (sel == null || sel.candidates == null || sel.candidates.length == 0) return;
        StageBasis sb = (StageBasis) rt.stage;
        float left = Math.min(sb.ebase.pos, sb.ubase.pos);
        float right = Math.max(sb.ebase.pos, sb.ubase.pos);
        boolean enemyOwned = !st.win;
        synchronized (st.lock) {
            for (int i = 0; i < sel.candidates.length; i++) {
                Candidate c = sel.candidates[i];
                if (c == null || c.form == null) continue;
                try {
                    EUnit unit = eformFor(c.form).getEntity(sb, null, false, false);
                    prepareSpawnedUnit(unit);
                    float pos = left + (right - left) * randFloat(sb);
                    unit.added(enemyOwned ? 1 : -1, pos);
                    try { BCUFields.setInt(unit, "price", c.cost); } catch (Throwable ignored) {}
                    sb.le.add(unit);
                    if (enemyOwned) st.enemyOwned.put(unit, Boolean.TRUE);
                    st.spawns.add(new SpawnJob(unit, enemyOwned));
                } catch (Throwable t) {
                    Logger.err("Cat Coin spawn failed", t);
                }
            }
            Collections.sort(sb.le, new Comparator<Entity>() {
                @Override
                public int compare(Entity a, Entity b) {
                    return Integer.compare(a.currentLayer, b.currentLayer);
                }
            });
        }
        try { CommonStatic.setSE(st.win ? 29 : 15); } catch (Throwable ignored) {}
        Logger.log("Cat Coin spawned units=" + st.spawns.size() + " enemyOwned=" + enemyOwned);
    }

    private static void prepareSpawnedUnit(EUnit unit) {
        if (unit == null) return;
        try {
            unit.getProc().MONEYBACK.mult = 0;
            unit.getProc().CANONCHARGE.mult = 0;
        } catch (Throwable ignored) {}
        try {
            int[] delayedCooldown = unit.status[64];
            if (delayedCooldown != null) {
                for (int i = 0; i < delayedCooldown.length; i++) delayedCooldown[i] = 0;
            }
        } catch (Throwable ignored) {}
    }

    private static void tickSpawns(CrazyRuntime.StageRuntime rt) {
        State st = rt.catCoin;
        synchronized (st.lock) {
            for (Iterator<SpawnJob> it = st.spawns.iterator(); it.hasNext();) {
                SpawnJob job = it.next();
                if (job == null || job.unit == null || job.unit.dead || job.unit.health <= 0L) {
                    it.remove();
                    continue;
                }
                job.age++;
                if (job.age >= EMERGE_FRAMES) {
                    it.remove();
                }
            }
        }
    }

    private static Selection selectCombo(CrazyRuntime.StageRuntime rt, int stake) {
        State st = rt.catCoin;
        if (stake <= 0 || st.pool.isEmpty()) return null;
        ArrayList<Candidate> affordable = new ArrayList<Candidate>();
        for (int i = 0; i < st.pool.size(); i++) {
            Candidate c = st.pool.get(i);
            if (c != null && c.cost > 0 && c.cost <= stake) affordable.add(c);
        }
        if (affordable.isEmpty()) return null;
        Collections.sort(affordable, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Integer.compare(a.cost, b.cost);
            }
        });

        Selection[] best = new Selection[4];
        best[1] = bestOne(affordable, stake);
        best[2] = bestTwo(affordable, stake);
        best[3] = bestThree(affordable, stake);

        int globalBest = 0;
        for (int i = 1; i <= 3; i++) {
            if (best[i] != null && best[i].cost > globalBest) globalBest = best[i].cost;
        }
        if (globalBest <= 0) return null;

        int count = 0;
        int threshold = Math.round(globalBest * SPEND_THRESHOLD);
        for (int i = 3; i >= 1; i--) {
            if (best[i] != null && best[i].cost >= threshold) {
                count = i;
                break;
            }
        }
        if (count == 0) {
            for (int i = 1; i <= 3; i++) {
                if (best[i] != null && (count == 0 || best[i].cost > best[count].cost
                        || (best[i].cost == best[count].cost && i > count))) {
                    count = i;
                }
            }
        }
        if (count == 0 || best[count] == null) return null;
        return randomNearBest(rt, affordable, stake, count, best[count]);
    }

    private static Selection bestOne(ArrayList<Candidate> list, int stake) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Candidate c = list.get(i);
            if (c.cost <= stake) return new Selection(new Candidate[]{c}, c.cost);
        }
        return null;
    }

    private static Selection bestTwo(ArrayList<Candidate> list, int stake) {
        int l = 0;
        int r = list.size() - 1;
        Selection best = null;
        while (l < r) {
            Candidate a = list.get(l);
            Candidate b = list.get(r);
            int cost = a.cost + b.cost;
            if (cost <= stake) {
                if (best == null || cost > best.cost) best = new Selection(new Candidate[]{a, b}, cost);
                l++;
            } else {
                r--;
            }
        }
        return best;
    }

    private static Selection bestThree(ArrayList<Candidate> list, int stake) {
        Selection best = null;
        int n = list.size();
        for (int a = 0; a < n - 2; a++) {
            Candidate ca = list.get(a);
            int l = a + 1;
            int r = n - 1;
            int remaining = stake - ca.cost;
            while (l < r) {
                Candidate cb = list.get(l);
                Candidate cc = list.get(r);
                int pair = cb.cost + cc.cost;
                if (pair <= remaining) {
                    int cost = ca.cost + pair;
                    if (best == null || cost > best.cost) {
                        best = new Selection(new Candidate[]{ca, cb, cc}, cost);
                    }
                    l++;
                } else {
                    r--;
                }
            }
        }
        return best;
    }

    private static Selection randomNearBest(CrazyRuntime.StageRuntime rt, ArrayList<Candidate> list,
                                            int stake, int count, Selection best) {
        ArrayList<Selection> choices = new ArrayList<Selection>();
        choices.add(best);
        int threshold = Math.round(best.cost * SPEND_THRESHOLD);
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = 0; i < RANDOM_SAMPLE_TRIES; i++) {
            Selection s = randomSelection(sb, list, stake, count);
            if (s != null && s.cost >= threshold) choices.add(s);
        }
        long weight = 0L;
        for (int i = 0; i < choices.size(); i++) {
            weight += Math.max(1, choices.get(i).cost);
        }
        long roll = (long) (randFloat(sb) * Math.max(1L, weight));
        long acc = 0L;
        for (int i = 0; i < choices.size(); i++) {
            Selection s = choices.get(i);
            acc += Math.max(1, s.cost);
            if (roll < acc) return s;
        }
        return best;
    }

    private static Selection randomSelection(StageBasis sb, ArrayList<Candidate> list, int stake, int count) {
        if (list.size() < count) return null;
        Candidate[] out = new Candidate[count];
        int tries = 0;
        while (tries++ < 40) {
            int cost = 0;
            boolean ok = true;
            for (int i = 0; i < count; i++) {
                Candidate c = list.get(randInt(sb, list.size()));
                for (int j = 0; j < i; j++) {
                    if (out[j].form == c.form) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) break;
                out[i] = c;
                cost += c.cost;
            }
            if (ok && cost <= stake) {
                Candidate[] copy = new Candidate[count];
                System.arraycopy(out, 0, copy, 0, count);
                return new Selection(copy, cost);
            }
        }
        return null;
    }

    private static boolean usable(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return false;
        ensureInitialized(rt);
        State st = rt.catCoin;
        if (st.phase != PHASE_IDLE) return false;
        return hasEnoughStake(rt);
    }

    private static boolean hasEnoughStake(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return false;
        ensureInitialized(rt);
        State st = rt.catCoin;
        StageBasis sb = (StageBasis) rt.stage;
        int needed = Math.max(MIN_MONEY, st.minCost == Integer.MAX_VALUE ? Integer.MAX_VALUE : st.minCost);
        return sb.money >= needed;
    }

    private static void ensureInitialized(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        State st = rt.catCoin;
        if (st.initialized) return;
        synchronized (st.lock) {
            if (st.initialized) return;
            buildPool(rt);
            st.initialized = true;
        }
    }

    private static void buildPool(CrazyRuntime.StageRuntime rt) {
        State st = rt.catCoin;
        st.pool.clear();
        st.minCost = Integer.MAX_VALUE;
        StageBasis sb = (StageBasis) rt.stage;
        try {
            long start = System.currentTimeMillis();
            CrazyPreloadService.Snapshot snap = CrazyPreloadService.snapshotOrBuildSync("cat-coin-stage");
            if (snap != null) {
                for (int i = 0; i < snap.allPlayerForms.size(); i++) {
                    CrazyPreloadService.PlayerFormEntry entry = snap.allPlayerForms.get(i);
                    Candidate c = candidateFor(sb, entry == null ? null : entry.form);
                    if (c == null) continue;
                    st.pool.add(c);
                    if (c.cost < st.minCost) st.minCost = c.cost;
                }
            }
            Logger.log("Cat Coin stage pool attach time=" + (System.currentTimeMillis() - start) + "ms");
        } catch (Throwable t) {
            Logger.err("Cat Coin pool build failed", t);
        }
        if (st.pool.isEmpty() && !st.poolWarningLogged) {
            st.poolWarningLogged = true;
            Logger.log("Cat Coin has no eligible player-unit forms");
        }
        Logger.log("Cat Coin pool initialized: forms=" + st.pool.size()
                + " minCost=" + (st.minCost == Integer.MAX_VALUE ? -1 : st.minCost));
    }

    private static Candidate candidateFor(StageBasis sb, Form form) {
        if (form == null || form.du == null || form.unit == null) return null;
        try {
            if (form.du.getPrice() <= 0) return null;
            if (form.du.getAtkCount() <= 0 || form.du.getAtkModel(0) == null) return null;
            EForm ef = eformFor(form);
            EUnit probe = ef.getEntity(sb, null, false, false);
            if (probe == null) return null;
            int cost = moneyCost(sb, form, ef);
            if (cost <= 0) return null;
            return new Candidate(form, cost);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static EForm eformFor(Form form) {
        Level level = new Level(20);
        try { level.setPlusLevel(30); } catch (Throwable ignored) {}
        return new EForm(form, level);
    }

    private static int moneyCost(StageBasis sb, Form form, EForm eform) {
        int cost = 0;
        try {
            int priceType = 0;
            try {
                Object cont = sb.st == null ? null : sb.st.getCont();
                if (cont instanceof StageMap) priceType = ((StageMap) cont).price;
            } catch (Throwable ignored) {}
            cost = (int) (eform.getPrice(priceType) * 100.0f);
        } catch (Throwable ignored) {
            try { cost = form.du.getPrice() * 100; } catch (Throwable ignored2) { cost = 0; }
        }
        try {
            Limit lim = sb.est == null ? null : sb.est.lim;
            if (lim != null && lim.stageLimit != null) {
                int global = sb.globalCost();
                if (global > 0) cost = global * 100;
                if (cost > 0 && form.unit != null) {
                    int rarity = form.unit.rarity;
                    int[] mult = lim.stageLimit.costMultiplier;
                    if (mult != null && rarity >= 0 && rarity < mult.length) {
                        cost = cost * mult[rarity] / 100;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return Math.max(0, cost);
    }

    private static void drawHudIcon(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.catCoin;
        Rect r = iconRectFromPainter(bbpainter);
        int shake = shakeOffset(st);
        boolean on = usable(rt);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            drawImageCentered(gra, headImage(), r.cx() + shake, r.cy(), r.w, 0f, 1f, 1f, 1f);
            if (!on || st.phase == PHASE_COOLDOWN) {
                gra.colRect(r.x + shake, r.y, r.w, r.h, 70, 70, 70, 142);
            }
            if (st.phase == PHASE_COOLDOWN && st.cooldown > 0) {
                drawCooldownRing(gra, r.cx() + shake, r.cy(), r.w / 2 + 4,
                        1f - st.cooldown / (float) COOLDOWN_FRAMES);
            }
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void drawChoiceOverlay(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        int w = BBPainterAccess.getWidth(bbpainter);
        int h = BBPainterAccess.getHeight(bbpainter);
        Rect head = choiceRect(w, h, true);
        Rect tail = choiceRect(w, h, false);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            gra.colRect(0, 0, w, h, 0, 0, 0, 118);
            drawChoiceButton(gra, head, true);
            drawChoiceButton(gra, tail, false);
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g != null) {
            AffineTransform ot = g.getTransform();
            java.awt.Composite oc = g.getComposite();
            Font of = g.getFont();
            Color color = g.getColor();
            Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            try {
                g.setTransform(new AffineTransform());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setComposite(AlphaComposite.SrcOver);
                drawCenteredText(g, "HEAD", head.cx(), head.y + head.h + 34, 24, new Color(255, 242, 176));
                drawCenteredText(g, "TAIL", tail.cx(), tail.y + tail.h + 34, 24, new Color(255, 242, 176));
            } finally {
                g.setTransform(ot);
                g.setComposite(oc);
                g.setFont(of);
                g.setColor(color);
                if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
            }
        }
    }

    private static void drawChoiceButton(FakeGraphics gra, Rect r, boolean head) {
        gra.colRect(r.x - 8, r.y - 8, r.w + 16, r.h + 16, 24, 18, 8, 190);
        gra.colRect(r.x - 4, r.y - 4, r.w + 8, r.h + 8, 214, 162, 46, 210);
        drawImageCentered(gra, head ? headImage() : tailImage(), r.cx(), r.cy(), r.w, 0f, 1f, 1f, 1f);
    }

    private static void drawFlipOverlay(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.catCoin;
        int w = BBPainterAccess.getWidth(bbpainter);
        int h = BBPainterAccess.getHeight(bbpainter);
        float[] c = flipCoinPose(w, h, st);
        FakeImage img = flipImage(st, c[4]);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            gra.colRect(0, 0, w, h, 0, 0, 0, 86);
            drawTableShadow(gra, c[0], c[3], c[5], c[6], c[7]);
            drawImageCentered(gra, img, c[0], c[1], c[2], c[4], c[8], 1f, 1f);
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
        if (st.phase == PHASE_RESULT) {
            Graphics2D g = CrazyRender.unwrap(gra);
            if (g != null) {
                AffineTransform ot = g.getTransform();
                java.awt.Composite oc = g.getComposite();
                Font of = g.getFont();
                Color color = g.getColor();
                Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
                try {
                    g.setTransform(new AffineTransform());
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setComposite(AlphaComposite.SrcOver);
                    drawCenteredText(g, st.win ? "YOU WIN!" : "YOU LOSE!", w / 2, Math.round(h * 0.24f),
                            40, st.win ? new Color(255, 242, 128) : new Color(255, 126, 108));
                } finally {
                    g.setTransform(ot);
                    g.setComposite(oc);
                    g.setFont(of);
                    g.setColor(color);
                    if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
                }
            }
        }
    }

    private static float[] flipCoinPose(int w, int h, State st) {
        float cx = w * 0.5f;
        float tableY = h * 0.58f;
        float y;
        float size = clamp(Math.min(w, h) * 0.36f, 184f, 328f);
        float rot;
        float shadowW;
        float shadowH;
        float shadowA;
        float scaleX;

        if (st.phase == PHASE_RESULT) {
            float p = clamp01(st.frame / (float) RESULT_FRAMES);
            float settle = 1f + 0.035f * (float) Math.sin(p * Math.PI * 2f) * (1f - p);
            return new float[]{cx, tableY, size * settle, tableY + size * 0.47f,
                    0f, size * 0.90f, size * 0.20f, 0.56f, 1f};
        }

        int f = st.frame;
        if (f <= 58) {
            float p = clamp01(f / 58f);
            float arc = (float) Math.sin(Math.PI * p);
            float startY = tableY + size * 1.10f;
            y = startY + (tableY - startY) * p - arc * size * 2.25f;
            rot = (float) (p * Math.PI * 11.5f);
            scaleX = Math.max(0.18f, Math.abs((float) Math.cos(rot)));
            float height = 1f - arc;
            shadowW = size * (0.54f + 0.42f * height);
            shadowH = size * (0.12f + 0.10f * height);
            shadowA = 0.18f + 0.30f * height;
        } else if (f <= 76) {
            float p = (f - 59) / 17f;
            float e = easeOutCubic(p);
            y = tableY - size * 0.18f * (float) Math.sin(Math.PI * p) * (1f - p);
            rot = 0f;
            size *= 1f + 0.07f * (float) Math.sin(Math.PI * e);
            scaleX = 1f;
            shadowW = size * (1.05f + 0.12f * (1f - e));
            shadowH = size * 0.22f;
            shadowA = 0.55f;
        } else {
            float p = (f - 77) / 13f;
            y = tableY;
            rot = 0f;
            scaleX = 1f;
            shadowW = size * 0.90f;
            shadowH = size * 0.20f;
            shadowA = 0.56f * clamp01(p);
        }
        return new float[]{cx, y, size, tableY + size * 0.47f, rot, shadowW, shadowH, shadowA, scaleX};
    }

    private static FakeImage flipImage(State st, float rotation) {
        if (st.phase == PHASE_RESULT || st.frame >= 59) {
            return st.resultHead ? headImage() : tailImage();
        }
        int halfTurns = (int) Math.floor(Math.abs(rotation) / Math.PI);
        boolean showHead = (halfTurns & 1) == 0;
        return showHead ? headImage() : tailImage();
    }

    private static void drawTableShadow(FakeGraphics gra, float cx, float cy, float w, float h, float alpha) {
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) return;
        float a = clamp01(alpha) * 0.48f;
        if (a <= 0f) return;
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color color = g.getColor();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(a));
            g.setColor(Color.BLACK);
            g.fillOval(Math.round(cx - w * 0.5f), Math.round(cy - h * 0.5f),
                    Math.max(1, Math.round(w)), Math.max(1, Math.round(h)));
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(color);
            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }
    }

    private static void drawCooldownRing(FakeGraphics gra, int cx, int cy, int r, float progress) {
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) return;
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Stroke os = g.getStroke();
        Color color = g.getColor();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(0.86f));
            g.setStroke(new BasicStroke(Math.max(3f, r * 0.11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 236, 126));
            g.drawArc(cx - r, cy - r, r * 2, r * 2, 90, -Math.round(360f * clamp01(progress)));
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setStroke(os);
            g.setColor(color);
            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }
    }

    private static void drawImageCentered(FakeGraphics gra, FakeImage img, float cx, float cy, float size,
                                          float rotation, float scaleX, float scaleY, float alpha) {
        if (img == null || size <= 0f) return;
        int a = clamp255(Math.round(255f * clamp01(alpha)));
        if (a <= 0) return;
        FakeTransform old = null;
        try {
            gra.setComposite(FakeGraphics.TRANS, a, 0);
            old = gra.getTransform();
            gra.translate(cx, cy);
            if (Math.abs(rotation) > 0.001f) gra.rotate(rotation);
            gra.scale(scaleX, scaleY);
            float ratio = size / Math.max(1f, Math.max(img.getWidth(), img.getHeight()));
            float iw = img.getWidth() * ratio;
            float ih = img.getHeight() * ratio;
            gra.drawImage(img, -iw * 0.5f, -ih * 0.5f, iw, ih);
        } catch (Throwable t) {
            Logger.err("Cat Coin image draw failed", t);
        } finally {
            if (old != null) {
                try {
                    gra.setTransform(old);
                    gra.delete(old);
                } catch (Throwable ignored) {}
            }
            resetComposite(gra);
        }
    }

    private static void drawCenteredText(Graphics2D g, String text, int cx, int cy, int size, Color fill) {
        Font oldFont = g.getFont();
        Color oldColor = g.getColor();
        Stroke oldStroke = g.getStroke();
        try {
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int x = cx - fm.stringWidth(text) / 2;
            int y = cy + (fm.getAscent() - fm.getDescent()) / 2;
            g.setStroke(new BasicStroke(Math.max(3f, size * 0.11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(40, 28, 10, 210));
            g.drawString(text, x + 2, y + 2);
            g.setColor(fill);
            g.drawString(text, x, y);
        } finally {
            g.setFont(oldFont);
            g.setColor(oldColor);
            g.setStroke(oldStroke);
        }
    }

    private static FakeImage headImage() {
        FakeImage img = headImage;
        if (img != null) return img;
        synchronized (CatCoinFeature.class) {
            if (!headLoadAttempted) {
                headLoadAttempted = true;
                headImage = loadResource(HEAD_RESOURCE);
            }
            return headImage;
        }
    }

    private static FakeImage tailImage() {
        FakeImage img = tailImage;
        if (img != null) return img;
        synchronized (CatCoinFeature.class) {
            if (!tailLoadAttempted) {
                tailLoadAttempted = true;
                tailImage = loadResource(TAIL_RESOURCE);
            }
            return tailImage;
        }
    }

    private static FakeImage loadResource(final String resource) {
        InputStream in = null;
        try {
            in = CatCoinFeature.class.getResourceAsStream(resource);
            if (in == null) {
                Logger.log("Cat Coin resource missing: " + resource);
                return null;
            }
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                Logger.log("Cat Coin resource is not a readable image: " + resource);
                return null;
            }
            FakeImage img = buildFakeImage(stripBorderWhite(src));
            if (img != null && img.isValid()) return img;
            Logger.log("Cat Coin resource could not be converted: " + resource);
        } catch (Throwable t) {
            Logger.err("Cat Coin resource load failed: " + resource, t);
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static BufferedImage stripBorderWhite(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }

        boolean[] seen = new boolean[w * h];
        ArrayDeque<Integer> q = new ArrayDeque<Integer>();
        for (int x = 0; x < w; x++) {
            enqueueWhite(out, seen, q, x, 0);
            enqueueWhite(out, seen, q, x, h - 1);
        }
        for (int y = 1; y < h - 1; y++) {
            enqueueWhite(out, seen, q, 0, y);
            enqueueWhite(out, seen, q, w - 1, y);
        }

        while (!q.isEmpty()) {
            int idx = q.removeFirst();
            int x = idx % w;
            int y = idx / w;
            out.setRGB(x, y, out.getRGB(x, y) & 0x00FFFFFF);
            if (x > 0) enqueueWhite(out, seen, q, x - 1, y);
            if (x + 1 < w) enqueueWhite(out, seen, q, x + 1, y);
            if (y > 0) enqueueWhite(out, seen, q, x, y - 1);
            if (y + 1 < h) enqueueWhite(out, seen, q, x, y + 1);
        }
        return out;
    }

    private static void enqueueWhite(BufferedImage image, boolean[] seen, ArrayDeque<Integer> q, int x, int y) {
        int w = image.getWidth();
        int idx = y * w + x;
        if (seen[idx]) return;
        if (!isBorderWhite(image.getRGB(x, y))) return;
        seen[idx] = true;
        q.addLast(idx);
    }

    private static boolean isBorderWhite(int argb) {
        int a = (argb >>> 24) & 0xFF;
        if (a <= 8) return true;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 235 && g >= 235 && b >= 235;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FakeImage buildFakeImage(BufferedImage image) {
        try {
            if (ImageBuilder.builder == null || image == null) return null;
            return ((ImageBuilder) ImageBuilder.builder).build(image);
        } catch (Throwable t) {
            Logger.err("Cat Coin FakeImage conversion failed", t);
            return null;
        }
    }

    private static SpawnJob spawnJobFor(Object entity) {
        if (!(entity instanceof Entity)) return null;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null || entity == null) return null;
        synchronized (rt.catCoin.lock) {
            for (int i = 0; i < rt.catCoin.spawns.size(); i++) {
                SpawnJob job = rt.catCoin.spawns.get(i);
                if (job != null && job.unit == entity) return job;
            }
        }
        return null;
    }

    private static boolean isEmergingLocked(CrazyRuntime.StageRuntime rt, Object entity) {
        if (rt == null || entity == null) return false;
        for (int i = 0; i < rt.catCoin.spawns.size(); i++) {
            SpawnJob job = rt.catCoin.spawns.get(i);
            if (job != null && job.unit == entity && job.age < EMERGE_FRAMES) return true;
        }
        return false;
    }

    private static boolean enabled(CrazyRuntime.StageRuntime rt) {
        return rt != null && rt.config.catCoin;
    }

    private static boolean isBattleCanvasEvent(Object page, MouseEvent e) {
        try {
            Object bb = BCUFields.get(page, "bb");
            return e.getSource() == bb;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Rect iconRectFromPage(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            int w = ((Number) BCUFields.invoke(bb, "getWidth")).intValue();
            int h = ((Number) BCUFields.invoke(bb, "getHeight")).intValue();
            return iconRect(w, h);
        } catch (Throwable ignored) {
            return iconRect(1200, 800);
        }
    }

    private static Rect iconRectFromPainter(Object bbpainter) {
        try {
            return iconRect(BBPainterAccess.getWidth(bbpainter), BBPainterAccess.getHeight(bbpainter));
        } catch (Throwable ignored) {
            return iconRect(1200, 800);
        }
    }

    private static Rect iconRect(int w, int h) {
        int size = clampInt(Math.round(h * 0.075f), 44, 66);
        int centerX = Math.round(w * 0.493f);
        int centerY = Math.round(h * 0.071f);
        int x = centerX - size / 2;
        int y = centerY - size / 2;
        return new Rect(x, y, size, size);
    }

    private static Rect choiceRect(Object page, boolean head) {
        try {
            Object bb = BCUFields.get(page, "bb");
            int w = ((Number) BCUFields.invoke(bb, "getWidth")).intValue();
            int h = ((Number) BCUFields.invoke(bb, "getHeight")).intValue();
            return choiceRect(w, h, head);
        } catch (Throwable ignored) {
            return choiceRect(1200, 800, head);
        }
    }

    private static int choiceSplitX(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            return ((Number) BCUFields.invoke(bb, "getWidth")).intValue() / 2;
        } catch (Throwable ignored) {
            return 600;
        }
    }

    private static Rect choiceRect(int w, int h, boolean head) {
        int size = clampInt(Math.round(Math.min(w, h) * 0.38f), 184, 304);
        int gap = Math.max(42, Math.round(size * 0.55f));
        int cx = w / 2 + (head ? -(size + gap) / 2 : (size + gap) / 2);
        int cy = Math.round(h * 0.55f);
        return new Rect(cx - size / 2, cy - size / 2, size, size);
    }

    private static int shakeOffset(State st) {
        if (st.shakeFrame <= 0) return 0;
        float p = st.shakeFrame / (float) SHAKE_FRAMES;
        return Math.round((float) Math.sin(st.shakeFrame * 2.55f) * 12f * p * p);
    }

    private static void beginShake(State st) {
        st.shakeFrame = SHAKE_FRAMES;
    }

    private static int clampMoney(StageBasis sb, int value) {
        return Math.max(0, Math.min(sb.maxMoney, value));
    }

    private static int randInt(StageBasis sb, int bound) {
        if (bound <= 1) return 0;
        int v = (int) (randFloat(sb) * bound);
        return Math.max(0, Math.min(bound - 1, v));
    }

    private static float randFloat(StageBasis sb) {
        try {
            return sb.r.nextFloat();
        } catch (Throwable ignored) {
            return (float) Math.random();
        }
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

    private static void resetComposite(FakeGraphics g) {
        try { g.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float easeOutCubic(float p) {
        float t = clamp01(p);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }
}
