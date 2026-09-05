package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.entity.AbEntity;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.battle.data.MaskEntity;
import common.pack.UserProfile;
import common.system.P;
import common.system.VImg;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.unit.EForm;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import common.system.fake.ImageBuilder;
import manualcontrol.ConvertedRegistry;
import manualcontrol.Logger;
import manualcontrol.OptionalFeatures;
import manualcontrol.crazy.CrazyPreloadService;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.hooks.BoundsRecorder;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class EggPetFeature {

    private static final int LIFE_EMPTY = 0;
    private static final int LIFE_OFFERED = 1;
    private static final int LIFE_ACCEPTED = 2;
    private static final int LIFE_HATCHING = 3;
    private static final int LIFE_HATCHED = 4;
    private static final int LIFE_DEAD = 5;
    private static final int LIFE_EMERGING = 6;

    private static final int SCREEN_NONE = 0;
    private static final int SCREEN_OFFER = 1;
    private static final int SCREEN_UPGRADE = 2;

    private static final int U_LEVEL = 0;
    private static final int U_HP = 1;
    private static final int U_ATTACK = 2;
    private static final int U_SPEED = 3;
    private static final int U_TBA = 4;
    private static final int U_ATTACK_SPEED = 5;
    private static final int U_COUNT = 6;

    private static final int RETURN_FRAMES = 16;
    private static final int HATCH_FRAMES = 86;
    private static final int EMERGE_FRAMES = 30;
    private static final int FLASH_FRAMES = 60;
    private static final long HATCH_FAILSAFE_MS = 6500L;
    private static final int INTERNAL_MONEY_MULT = 100;
    private static final int EGG_ICON_UNIT_ID = 757;
    private static final int EGG_ICON_ALT_UNIT_ID = EGG_ICON_UNIT_ID - 1;
    private static final float EGG_ANIM_ANCHOR_X = -0.38f;
    private static final float EGG_ANIM_ANCHOR_Y = 0.78f;
    private static final String OFFICIAL_PACK = "000000";
    private static volatile FakeImage hatchShadowImage;
    private static volatile FakeImage hatchGlowImage;
    private static volatile FakeImage hatchShockwaveImage;
    private static volatile FakeImage hatchSparkImage;
    private static volatile FakeImage hatchCrackImage;
    private static final Map<FakeImage, AlphaBounds> hatchAlphaBoundsCache =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, AlphaBounds>());
    private static volatile Field transformDataField;

    private static final String[] STAT_NAMES = new String[]{
            "Level", "HP", "Attack", "Speed", "TBA", "Attack Speed"
    };

    private EggPetFeature() {}

    public static int preloadFlags() {
        return CrazyPreloadService.PRELOAD_OFFICIAL_PLAYER_FORMS
                | CrazyPreloadService.PRELOAD_OFFICIAL_ENEMIES;
    }

    public static final class State {
        public final Object lock = new Object();
        public final ArrayList<Candidate> pool = new ArrayList<Candidate>();
        public final int[] upgrades = new int[U_COUNT];
        public final Map<Object, PetStats> pets = new WeakHashMap<Object, PetStats>();
        public final Map<Object, Float> beforePos = new WeakHashMap<Object, Float>();
        public final Map<Object, MotionState> motion = new WeakHashMap<Object, MotionState>();
        public final Map<Object, Integer> enemyMoneyBeforeKill = new WeakHashMap<Object, Integer>();
        public final Set<Object> rangeScaled = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
        public final Set<Object> damageScaled = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

        public boolean initialized;
        public boolean poolWarningLogged;
        public int life = LIFE_EMPTY;
        public int screen = SCREEN_NONE;
        public Candidate current;
        public Candidate previous;
        public boolean iconRectLogged;
        public boolean eggIconWarningLogged;
        public boolean primitiveOverlayLogged;
        public Form eggIconForm;
        public EAnimU eggIconAnim;
        public FakeImage eggIconImage;
        public String eggIconSource;
        public FakeImage fallbackOverlayImage;
        public String fallbackOverlayKey;

        public boolean dragging;
        public boolean dragMoved;
        public int dragStartX;
        public int dragStartY;
        public int dragX;
        public int dragY;

        public boolean returning;
        public int returnFrame;
        public int returnFromX;
        public int returnFromY;

        public int hatchFrame;
        public int emergeFrame;
        public float hatchX;
        public float hatchY;
        public int hatchLayer;
        public boolean hatchDrawLogged;
        public boolean hatchTickLogged;
        public boolean hatchFailsafeLogged;
        public boolean hatchCrackBoundsLogged;
        public long hatchPhaseStartedAt;
        public Entity pet;
        public int flashStat = -1;
        public int flashFrame;
    }

    private static final class Candidate {
        final boolean enemy;
        final String key;
        final Form form;
        final Enemy enemyUnit;
        final Level baseLevel;
        final int will;
        final int tier;
        final int rerollCost;
        final int seed;
        final FakeImage icon;

        Candidate(Form form, Level baseLevel, String key, int will, int tier, FakeImage icon) {
            this.enemy = false;
            this.form = form;
            this.enemyUnit = null;
            this.baseLevel = baseLevel;
            this.key = key;
            this.will = will;
            this.tier = clampInt(tier, 0, 5);
            this.rerollCost = 500 + this.tier * 100;
            this.seed = key == null ? 0 : key.hashCode();
            this.icon = icon;
        }

        Candidate(Enemy enemy, String key, int will, int tier, FakeImage icon) {
            this.enemy = true;
            this.form = null;
            this.enemyUnit = enemy;
            this.baseLevel = null;
            this.key = key;
            this.will = will;
            this.tier = clampInt(tier, 0, 5);
            this.rerollCost = 500 + this.tier * 100;
            this.seed = key == null ? 0 : key.hashCode();
            this.icon = icon;
        }
    }

    private static final class EnemyScore {
        final CrazyPreloadService.EnemyEntry entry;
        final int score;

        EnemyScore(CrazyPreloadService.EnemyEntry entry) {
            this.entry = entry;
            this.score = Math.max(0, entry.rawDrop / INTERNAL_MONEY_MULT) + Math.max(0, entry.will) * 50;
        }
    }

    private static final class IconForm {
        final Form form;
        final String source;

        IconForm(Form form, String source) {
            this.form = form;
            this.source = source;
        }
    }

    private static final class PetStats {
        final Entity entity;
        final float scale;
        final int speedLevel;
        final int tbaLevel;
        final int attackSpeedLevel;
        final boolean enemyPet;

        PetStats(Entity entity, float scale, int speedLevel, int tbaLevel, int attackSpeedLevel, boolean enemyPet) {
            this.entity = entity;
            this.scale = scale;
            this.speedLevel = speedLevel;
            this.tbaLevel = tbaLevel;
            this.attackSpeedLevel = attackSpeedLevel;
            this.enemyPet = enemyPet;
        }
    }

    private static final class MotionState {
        int lastWait;
        int lastPre;
        int lastAtk;
        boolean attackAnimSpeedLogged;
        boolean attackTimerSpeedLogged;
    }

    private static final class StatBlock {
        long hp;
        long attack;
        float speed;
        int tba;
        int attackFrames;
        int scalePct;
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

    private static final class BaseDropArea {
        final Object base;
        final float rootX;
        final float rootY;
        final float siz;
        final int layer;
        final int left;
        final int top;
        final int right;
        final int bottom;
        final int doorX;
        final int doorY;
        final EntityAccess.SpriteBounds sprite;

        BaseDropArea(Object base, float rootX, float rootY, float siz, int layer,
                     int left, int top, int right, int bottom, int doorX, int doorY,
                     EntityAccess.SpriteBounds sprite) {
            this.base = base;
            this.rootX = rootX;
            this.rootY = rootY;
            this.siz = siz;
            this.layer = layer;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.doorX = doorX;
            this.doorY = doorY;
            this.sprite = sprite;
        }

        boolean spriteHit(int x, int y) {
            return false;
        }

        boolean broadHit(int x, int y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        boolean doorHit(int x, int y) {
            return broadHit(x, y);
        }

        boolean contains(int x, int y) {
            return broadHit(x, y);
        }
    }

    private static final class EggDrawBox {
        final float left;
        final float top;
        final float right;
        final float bottom;
        final String source;

        EggDrawBox(float left, float top, float right, float bottom, String source) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.source = source;
        }

        float width() { return Math.max(1f, right - left); }
        float height() { return Math.max(1f, bottom - top); }
        float cx() { return (left + right) * 0.5f; }
        float cy() { return (top + bottom) * 0.5f; }
    }

    private static final class AlphaBounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        AlphaBounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean valid() {
            return right > left && bottom > top;
        }
    }

    public static boolean onMousePressed(Object page, MouseEvent e) {
        if (page == null || e == null || e.getButton() != MouseEvent.BUTTON1) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            ensureInitialized(rt);
            State st = rt.eggPet;

            if (st.screen == SCREEN_OFFER) {
                handleOfferClick(rt, page, e.getX(), e.getY());
                return true;
            }
            if (st.screen == SCREEN_UPGRADE) {
                handleUpgradeClick(rt, page, e.getX(), e.getY());
                return true;
            }
            if (st.life == LIFE_HATCHING || st.life == LIFE_EMERGING || st.life == LIFE_HATCHED || st.life == LIFE_DEAD) return false;

            Rect icon = iconRectFromPage(page);
            if (!icon.contains(e.getX(), e.getY())) return false;
            Logger.log("Egg Pet icon clicked at (" + e.getX() + "," + e.getY() + ") rect=("
                    + icon.x + "," + icon.y + "," + icon.w + "," + icon.h + ") life=" + st.life
                    + " current=" + (st.current == null ? "null" : st.current.key));
            if (st.life == LIFE_ACCEPTED) {
                st.dragging = true;
                st.dragMoved = false;
                st.returning = false;
                st.dragStartX = e.getX();
                st.dragStartY = e.getY();
                st.dragX = e.getX();
                st.dragY = e.getY();
                play(19);
                return true;
            }
            if (st.current == null) {
                st.current = randomCandidate(rt, null);
                st.previous = st.current;
                st.life = st.current == null ? LIFE_EMPTY : LIFE_OFFERED;
            }
            if (st.current != null) {
                st.screen = SCREEN_OFFER;
                play(19);
                Logger.log("Egg Pet offer opened candidate=" + st.current.key
                        + " cost=" + st.current.rerollCost);
                return true;
            }
            reject();
        } catch (Throwable t) {
            Logger.err("Egg Pet mouse press failed", t);
        }
        return false;
    }

    public static boolean onMouseDragged(Object page, MouseEvent e) {
        if (page == null || e == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.eggPet;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            int dx = st.dragX - st.dragStartX;
            int dy = st.dragY - st.dragStartY;
            if (dx * dx + dy * dy > 64) st.dragMoved = true;
            return true;
        } catch (Throwable t) {
            Logger.err("Egg Pet mouse drag failed", t);
            return false;
        }
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        if (page == null || e == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.eggPet;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            st.dragging = false;

            BaseDropArea dropArea = baseDropAreaFromPage(page);
            boolean overBase = dropArea != null && dropArea.contains(e.getX(), e.getY());
            logBaseDropCheck(dropArea, e.getX(), e.getY());
            Logger.log("Egg Pet release at (" + e.getX() + "," + e.getY() + ") moved="
                    + st.dragMoved + " overBase=" + overBase + " life=" + st.life);
            if (overBase) {
                Logger.log("Egg Pet dropped on player base at (" + e.getX() + "," + e.getY()
                        + ") snap=(" + dropArea.doorX + "," + dropArea.doorY + ")");
                beginHatch(rt, page, dropArea.doorX, dropArea.doorY);
                return true;
            }

            if (!st.dragMoved) {
                st.screen = SCREEN_UPGRADE;
                play(19);
                return true;
            }
            Logger.log("Egg Pet drop missed player base at (" + e.getX() + "," + e.getY() + ")");
            beginReturn(st, e.getX(), e.getY());
            reject();
            return true;
        } catch (Throwable t) {
            Logger.err("Egg Pet mouse release failed", t);
            return false;
        }
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        State st = rt.eggPet;
        if (!rt.config.eggPet && !hasActive(rt)) return;
        ensureInitialized(rt);
        if (st.flashFrame > 0) st.flashFrame--;
        if (st.returning) {
            st.returnFrame++;
            if (st.returnFrame >= RETURN_FRAMES) {
                st.returning = false;
                st.returnFrame = 0;
            }
        }
        if (st.life == LIFE_HATCHING) {
            st.hatchFrame++;
            if (!st.hatchTickLogged) {
                st.hatchTickLogged = true;
                Logger.log("Egg Pet hatch tick active frame=" + st.hatchFrame);
            }
            if (st.hatchFrame == 28) play(167);
            if (st.hatchFrame >= HATCH_FRAMES) {
                hatchNow(rt);
            }
            return;
        }
        if (st.life == LIFE_EMERGING) {
            st.emergeFrame++;
            if (st.emergeFrame >= EMERGE_FRAMES) {
                completeEmergence(rt);
            }
            return;
        }
        capturePetPositions(rt);
        checkPetDeath(rt);
    }

    public static void afterNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return;
        applyPetMotion(rt);
        checkPetDeath(rt);
    }

    public static boolean shouldSkipNativeStageUpdate(CrazyRuntime.StageRuntime rt) {
        if (rt == null || rt.eggPet == null) return false;
        State st = rt.eggPet;
        recoverStuckHatch(rt, st);
        return st.screen != SCREEN_NONE || st.life == LIFE_HATCHING || st.life == LIFE_EMERGING;
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        if (rt == null) return false;
        State st = rt.eggPet;
        return st.screen != SCREEN_NONE || st.life == LIFE_OFFERED || st.life == LIFE_ACCEPTED
                || st.life == LIFE_HATCHING || st.life == LIFE_EMERGING || st.life == LIFE_HATCHED || !st.pets.isEmpty();
    }

    private static void recoverStuckHatch(CrazyRuntime.StageRuntime rt, State st) {
        if (rt == null || st == null) return;
        if (st.life != LIFE_HATCHING && st.life != LIFE_EMERGING) return;
        long started = st.hatchPhaseStartedAt;
        if (started <= 0L) {
            st.hatchPhaseStartedAt = System.currentTimeMillis();
            return;
        }
        long elapsed = System.currentTimeMillis() - started;
        if (elapsed < HATCH_FAILSAFE_MS) return;
        if (!st.hatchFailsafeLogged) {
            st.hatchFailsafeLogged = true;
            Logger.log("Egg Pet hatch failsafe elapsed=" + elapsed + "ms life=" + st.life
                    + " hatchFrame=" + st.hatchFrame + " emergeFrame=" + st.emergeFrame);
        }
        if (st.life == LIFE_HATCHING) {
            hatchNow(rt);
            if (st.life == LIFE_HATCHING) {
                st.life = LIFE_DEAD;
                st.screen = SCREEN_NONE;
                st.dragging = false;
                st.returning = false;
                st.hatchPhaseStartedAt = 0L;
            }
        } else if (st.life == LIFE_EMERGING) {
            completeEmergence(rt);
        }
    }

    public static boolean isAnchorTarget(Object entity) {
        return entity instanceof Entity && petStats(entity) != null;
    }

    public static float drawScaleFor(Object entity) {
        PetStats stats = petStats(entity);
        if (stats == null) return 1f;
        float scale = safeScale(stats.scale);
        try {
            if (entity instanceof Entity) {
                CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
                if (rt != null && rt.eggPet.life == LIFE_EMERGING && rt.eggPet.pet == entity) {
                    scale *= emergeScaleFactor(rt.eggPet);
                }
            }
        } catch (Throwable ignored) {}
        return safeVisualScale(scale);
    }

    public static void applyDamageMultiplier(CrazyRuntime.StageRuntime rt, AttackAb attack) {
        if (rt == null || attack == null || attack.attacker == null) return;
        PetStats stats = rt.eggPet.pets.get(attack.attacker);
        if (stats == null) return;
        if (!rt.eggPet.damageScaled.add(attack)) return;

    }

    public static void scaleAttackRange(Object attackObj) {
        if (!(attackObj instanceof AttackAb)) return;
        AttackAb attack = (AttackAb) attackObj;
        if (attack.attacker == null) return;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(attack.attacker.basis);
        if (rt == null) return;
        PetStats stats = rt.eggPet.pets.get(attack.attacker);
        if (stats == null || stats.scale <= 1.0001f) return;
        if (!rt.eggPet.rangeScaled.add(attack)) return;
        try {
            java.lang.reflect.Field sta = BCUFields.field(attack.getClass(), "sta");
            java.lang.reflect.Field end = BCUFields.field(attack.getClass(), "end");
            float pos = attack.attacker.pos;
            sta.setFloat(attack, pos + (sta.getFloat(attack) - pos) * stats.scale);
            end.setFloat(attack, pos + (end.getFloat(attack) - pos) * stats.scale);
        } catch (Throwable t) {
            Logger.err("Egg Pet range scale failed", t);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void appendScaledHitboxes(CrazyRuntime.StageRuntime rt, List result, int touch, int dire,
                                            float d0, float d1, boolean excludeRightEdge) {
        if (rt == null || result == null || dire == 0) return;
        float left = Math.min(d0, d1);
        float right = Math.max(d0, d1);
        List<Object> pets = new ArrayList<Object>(rt.eggPet.pets.keySet());
        for (int i = 0; i < pets.size(); i++) {
            Object obj = pets.get(i);
            if (!(obj instanceof Entity)) continue;
            Entity e = (Entity) obj;
            PetStats stats = rt.eggPet.pets.get(e);
            if (stats == null || stats.scale <= 1.0001f) continue;
            if (EntityAccess.getDire(e) != dire || e.dead || e.health <= 0L || e.isBase()) continue;
            if (!touchable(e, touch)) continue;
            float extra = scaledHitboxExtra(e, stats.scale);
            boolean inside = excludeRightEdge
                    ? e.pos >= left - extra && e.pos < right + extra
                    : e.pos >= left - extra && e.pos <= right + extra;
            if (inside && !result.contains(e)) result.add(e);
        }
    }

    public static void afterEntityDamaged(CrazyRuntime.StageRuntime rt, AbEntity entity, long healthBefore) {
        if (rt == null || !(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        if (!rt.eggPet.pets.containsKey(e)) return;
        if (healthBefore > 0L && (e.health <= 0L || e.dead)) markDead(rt, e);
    }

    public static void beforeEnemyKill(Object entity) {
        if (!(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(e.basis);
        if (rt == null || !rt.eggPet.pets.containsKey(entity)) return;
        try {
            rt.eggPet.enemyMoneyBeforeKill.put(entity, e.basis.money);
        } catch (Throwable ignored) {}
    }

    public static void afterEnemyKill(Object entity, Object killMode) {
        if (!(entity instanceof Entity)) return;
        Entity e = (Entity) entity;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(e.basis);
        if (rt == null || !rt.eggPet.pets.containsKey(entity)) return;
        Integer before = rt.eggPet.enemyMoneyBeforeKill.remove(entity);
        if (before != null) {
            try { e.basis.money = before.intValue(); } catch (Throwable ignored) {}
        }
        markDead(rt, e);
    }

    public static void drawUnder(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || gra == null || rt.eggPet.life != LIFE_HATCHING) return;
        State st = rt.eggPet;
        drawPrimitiveHatchShadow(st, gra);
    }

    private static void drawGraphicsHatchVfx(State st, Graphics2D g) {
        if (st == null || g == null) return;
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color old = g.getColor();
        Stroke os = g.getStroke();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float p = clamp01(st.hatchFrame / (float) HATCH_FRAMES);
            float shake = st.hatchFrame < 55 ? (float) Math.sin(st.hatchFrame * 0.9f) * (1f - p) * 10f : 0f;
            int cx = Math.round(st.hatchX + shake);
            int cy = Math.round(st.hatchY);
            int w = Math.round(62f + 20f * p);
            int h = Math.round(82f + 24f * p);
            int alpha = 235;
            if (p > 0.72f) alpha = Math.round(235f * (1f - (p - 0.72f) / 0.28f));
            alpha = clampInt(alpha, 0, 255);

            if (st.hatchFrame > 46) {
                float q = clamp01((st.hatchFrame - 46) / 28f);
                g.setComposite(AlphaComposite.SrcOver.derive(0.42f * (1f - q)));
                g.setColor(new Color(255, 244, 160));
                int rr = Math.round(50f + 150f * q);
                g.fillOval(cx - rr, Math.round(st.hatchY + h * 0.35f - rr * 0.35f),
                        rr * 2, Math.max(6, Math.round(rr * 0.70f)));
            }

            g.setComposite(AlphaComposite.SrcOver.derive(alpha / 255f));
            GradientPaint gp = new GradientPaint(cx - w / 2f, cy - h / 2f, new Color(255, 244, 197),
                    cx + w / 2f, cy + h / 2f, new Color(210, 128, 70));
            g.setPaint(gp);
            g.fillOval(cx - w / 2, cy - h / 2, w, h);
            g.setPaint(null);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(118, 67, 45, 220));
            g.drawOval(cx - w / 2, cy - h / 2, w, h);
            if (st.hatchFrame > 18) {
                Path2D crack = new Path2D.Float();
                crack.moveTo(cx - 4, cy - h / 2 + 8);
                crack.lineTo(cx + 5, cy - h / 5);
                crack.lineTo(cx - 8, cy + 2);
                crack.lineTo(cx + 9, cy + h / 4);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(52, 34, 28, 210));
                g.draw(crack);
            }
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(old);
            g.setStroke(os);
            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }
    }

    private static void drawHatchOverlay(CrazyRuntime.StageRuntime rt, FakeGraphics gra) {
        if (rt == null || gra == null || rt.eggPet.life != LIFE_HATCHING) return;
        State st = rt.eggPet;
        if (!st.hatchDrawLogged) {
            st.hatchDrawLogged = true;
            Logger.log("Egg Pet hatch draw active graphics=" + gra.getClass().getName()
                    + " frame=" + st.hatchFrame + " pos=(" + Math.round(st.hatchX)
                    + "," + Math.round(st.hatchY) + ")");
        }
        drawPrimitiveHatchVfx(rt, st, gra, true);
    }

    private static void drawPrimitiveHatchShadow(State st, FakeGraphics gra) {
        if (st == null || gra == null) return;
        float p = clamp01(st.hatchFrame / (float) HATCH_FRAMES);
        int cx = Math.round(st.hatchX);
        int cy = Math.round(st.hatchY + 44f);
        int w = Math.round(96f + 40f * p);
        int h = Math.round(22f + 8f * p);
        drawHatchImage(gra, hatchShadowImage(), cx, cy, w, h, 105);
    }

    private static void drawPrimitiveHatchVfx(CrazyRuntime.StageRuntime rt, State st, FakeGraphics gra,
                                              boolean topLayer) {
        if (st == null || gra == null) return;
        float p = clamp01(st.hatchFrame / (float) HATCH_FRAMES);
        float shake = st.hatchFrame < 55 ? (float) Math.sin(st.hatchFrame * 0.9f) * (1f - p) * 10f : 0f;
        int cx = Math.round(st.hatchX + shake);
        int cy = Math.round(st.hatchY);
        int eggW = Math.round(70f + 22f * p);
        int eggH = Math.round(88f + 28f * p);
        int alpha = 245;
        if (p > 0.72f) alpha = Math.round(245f * (1f - (p - 0.72f) / 0.28f));
        alpha = clampInt(alpha, 0, 255);

        drawPrimitiveHatchShadow(st, gra);
        drawHatchGlow(gra, st, cx, cy, eggW, eggH, p, topLayer);
        if (st.hatchFrame > 46) {
            float q = clamp01((st.hatchFrame - 46) / 28f);
            drawHatchShockwave(gra, st, cx, eggH, q);
        }
        drawHatchSparks(gra, st, cx, cy, eggW, eggH, p);

        if (alpha > 10) {
            int eggSize = Math.max(eggW, eggH);
            EggDrawBox eggBox = drawHatchEggAndMeasure(rt, gra, cx, cy, eggSize, alpha / 255f);
            if (eggBox == null) {
                drawPrimitiveEllipse(gra, cx, cy, eggW, eggH, 246, 207, 125, alpha);
                drawPrimitiveEllipse(gra, cx - eggW / 8, cy - eggH / 6, eggW / 3, eggH / 4,
                        255, 244, 190, Math.min(190, alpha));
                drawPrimitiveRectOutline(gra, cx - eggW / 2, cy - eggH / 2,
                        eggW, eggH, 3, 118, 67, 45, Math.min(230, alpha));
                eggBox = new EggDrawBox(cx - eggW * 0.5f, cy - eggH * 0.5f,
                        cx + eggW * 0.5f, cy + eggH * 0.5f, "primitive");
            }
            if (st.hatchFrame > 14) {
                if (!st.hatchCrackBoundsLogged) {
                    st.hatchCrackBoundsLogged = true;
                    Logger.log("Egg Pet hatch crack bounds source=" + eggBox.source
                            + " box=(" + Math.round(eggBox.left) + "," + Math.round(eggBox.top)
                            + ")-(" + Math.round(eggBox.right) + "," + Math.round(eggBox.bottom)
                            + ") center=(" + Math.round(eggBox.cx()) + "," + Math.round(eggBox.cy())
                            + ") hatch=(" + cx + "," + cy + ") size=" + eggSize);
                }
                drawHatchCrackImage(gra, eggBox, Math.min(255, alpha + 20));
            }
        }

        if (st.hatchFrame < 54) {
            int labelScale = 2 + (st.hatchFrame / 10) % 2;
            int tw = primitiveTextWidth("HATCH", labelScale);
            int tx = cx - tw / 2;
            int ty = cy - eggH / 2 - 26;
            gra.colRect(tx - 10, ty - 8, tw + 20, 7 * labelScale + 16, 8, 20, 12, 155);
            drawPrimitiveText(gra, "HATCH", tx, ty, labelScale, 98, 255, 142, 255);
        }
    }

    private static void drawHatchGlow(FakeGraphics gra, State st, int cx, int cy, int eggW, int eggH,
                                      float p, boolean topLayer) {
        if (gra == null || st == null) return;
        float pulse = (float) Math.sin(st.hatchFrame * 0.22f) * 0.06f;
        float s = 1.55f + 0.30f * p + pulse;
        int alpha = topLayer ? Math.round(82f * (1f - clamp01((p - 0.74f) / 0.26f))) : 42;
        alpha = clampInt(alpha, 0, 96);
        if (alpha <= 0) return;
        drawHatchImage(gra, hatchGlowImage(), cx, Math.round(cy + eggH * 0.02f),
                Math.round(eggW * s), Math.round(eggH * s), alpha);
    }

    private static void drawHatchShockwave(FakeGraphics gra, State st, int cx, int eggH, float q) {
        if (gra == null || st == null) return;
        float ease = 1f - (1f - q) * (1f - q);
        int w = Math.round(112f + 250f * ease);
        int h = Math.round(28f + 38f * ease);
        int alpha = clampInt(Math.round(180f * (1f - q)), 0, 180);
        if (alpha <= 0) return;
        int y = Math.round(st.hatchY + eggH * 0.42f);
        drawHatchImage(gra, hatchShockwaveImage(), cx, y, w, h, alpha);
    }

    private static void drawHatchSparks(FakeGraphics gra, State st, int cx, int cy, int eggW, int eggH, float p) {
        if (gra == null || st == null) return;
        FakeImage spark = hatchSparkImage();
        if (spark == null) return;
        int baseAlpha = st.hatchFrame < 18 ? 0 : Math.round(150f * (1f - clamp01((p - 0.72f) / 0.28f)));
        if (baseAlpha <= 0) return;
        for (int i = 0; i < 7; i++) {
            float seed = i * 1.731f + 0.31f;
            float drift = (st.hatchFrame + i * 9) / (float) HATCH_FRAMES;
            float side = (i % 2 == 0 ? -1f : 1f);
            float ox = side * (eggW * (0.35f + 0.12f * i) + 18f * (float) Math.sin(seed + drift * 6f));
            float oy = -eggH * (0.18f + 0.07f * i) - drift * (18f + i * 2f);
            int size = Math.round(10f + (i % 3) * 4f);
            int alpha = clampInt(Math.round(baseAlpha * (0.45f + 0.08f * i)), 0, 165);
            drawHatchImage(gra, spark, Math.round(cx + ox), Math.round(cy + oy), size, size, alpha);
        }
    }

    private static void drawHatchImage(FakeGraphics gra, FakeImage img, float cx, float cy,
                                       float w, float h, int alpha) {
        if (gra == null || img == null || w <= 0f || h <= 0f || alpha <= 0) return;
        try {
            try { gra.setComposite(FakeGraphics.TRANS, clampInt(alpha, 0, 255), 0); } catch (Throwable ignored) {}
            gra.drawImage(img, cx - w * 0.5f, cy - h * 0.5f, w, h);
        } catch (Throwable t) {
            Logger.err("Egg Pet hatch image draw failed", t);
        } finally {
            resetComposite(gra);
        }
    }

    private static FakeImage hatchShadowImage() {
        FakeImage img = hatchShadowImage;
        if (img != null) return img;
        synchronized (EggPetFeature.class) {
            img = hatchShadowImage;
            if (img == null) hatchShadowImage = img = buildSoftEllipseImage(256, 80,
                    new Color(0, 0, 0), 210, 1.0f, 0.50f);
        }
        return img;
    }

    private static FakeImage hatchGlowImage() {
        FakeImage img = hatchGlowImage;
        if (img != null) return img;
        synchronized (EggPetFeature.class) {
            img = hatchGlowImage;
            if (img == null) hatchGlowImage = img = buildRadialGlowImage(192, 192);
        }
        return img;
    }

    private static FakeImage hatchShockwaveImage() {
        FakeImage img = hatchShockwaveImage;
        if (img != null) return img;
        synchronized (EggPetFeature.class) {
            img = hatchShockwaveImage;
            if (img == null) hatchShockwaveImage = img = buildShockwaveImage();
        }
        return img;
    }

    private static FakeImage hatchSparkImage() {
        FakeImage img = hatchSparkImage;
        if (img != null) return img;
        synchronized (EggPetFeature.class) {
            img = hatchSparkImage;
            if (img == null) hatchSparkImage = img = buildSparkImage();
        }
        return img;
    }

    private static FakeImage buildSoftEllipseImage(int w, int h, Color color, int maxAlpha,
                                                   float rxScale, float ryScale) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int cx = w / 2;
        int cy = h / 2;
        float rx = Math.max(1f, w * 0.5f * Math.max(0.1f, rxScale));
        float ry = Math.max(1f, h * 0.5f * Math.max(0.1f, ryScale));
        int rgb = color.getRGB() & 0x00FFFFFF;
        for (int y = 0; y < h; y++) {
            float ny = (y + 0.5f - cy) / ry;
            for (int x = 0; x < w; x++) {
                float nx = (x + 0.5f - cx) / rx;
                float d = nx * nx + ny * ny;
                if (d >= 1f) continue;
                float a = (1f - d);
                a = a * a;
                int alpha = clampInt(Math.round(maxAlpha * a), 0, 255);
                image.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildRadialGlowImage(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int cx = w / 2;
        int cy = h / 2;
        float max = Math.max(1f, Math.min(w, h) * 0.5f);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x + 0.5f - cx;
                float dy = y + 0.5f - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy) / max;
                if (d >= 1f) continue;
                float core = Math.max(0f, 1f - d);
                int alpha = clampInt(Math.round(210f * core * core), 0, 255);
                int r = clampInt(Math.round(255f - 35f * d), 0, 255);
                int g = clampInt(Math.round(238f - 105f * d), 0, 255);
                int b = clampInt(Math.round(150f - 95f * d), 0, 255);
                image.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildShockwaveImage() {
        int w = 384;
        int h = 128;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int cx = w / 2;
            int cy = h / 2;
            g.setComposite(AlphaComposite.SrcOver.derive(0.25f));
            g.setColor(new Color(255, 230, 145));
            g.setStroke(new BasicStroke(16f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(34, 36, w - 68, h - 72);
            g.setComposite(AlphaComposite.SrcOver.derive(0.72f));
            g.setColor(new Color(255, 247, 188));
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(46, 43, w - 92, h - 86);
            g.setComposite(AlphaComposite.SrcOver.derive(0.32f));
            g.setColor(new Color(255, 188, 82));
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 10; i++) {
                float t = i / 9f;
                float x0 = cx + (t - 0.5f) * (w * 0.82f);
                float y0 = cy + (float) Math.sin(t * Math.PI * 2.0) * 10f;
                float x1 = x0 + (t - 0.5f) * 44f;
                float y1 = y0 + (i % 2 == 0 ? -7f : 7f);
                Path2D streak = new Path2D.Float();
                streak.moveTo(x0, y0);
                streak.curveTo((x0 + x1) * 0.5f, y0 - 10f, (x0 + x1) * 0.5f, y1 + 10f, x1, y1);
                g.draw(streak);
            }
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildSparkImage() {
        int n = 48;
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(0.45f));
            g.setColor(new Color(255, 189, 82));
            g.fillOval(10, 10, 28, 28);
            g.setComposite(AlphaComposite.SrcOver.derive(0.88f));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 250, 204));
            g.drawLine(24, 4, 24, 44);
            g.drawLine(4, 24, 44, 24);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(12, 12, 36, 36);
            g.drawLine(36, 12, 12, 36);
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static EggDrawBox drawHatchEggAndMeasure(CrazyRuntime.StageRuntime rt, FakeGraphics gra,
                                                     int cx, int cy, int size, float opacity) {
        if (rt == null || gra == null || size <= 0) return null;
        try {
            BoundsRecorder rec = BoundsRecorder.begin(gra);
            boolean drew = drawUnitEggAt(rt, rec.proxy(), cx, cy, size, opacity);
            if (!drew) return null;
            EggDrawBox alphaBox = alphaRecordedEggBox(rec);
            if (alphaBox != null) return alphaBox;
            if (rec.hasBox()) {
                return new EggDrawBox(rec.minX(), rec.minY(), rec.maxX(), rec.maxY(), "recorded");
            }
            return calibratedHatchEggBox(cx, cy, size, "calibrated");
        } catch (Throwable t) {
            Logger.err("Egg Pet hatch egg bounds recording failed", t);
            return null;
        }
    }

    private static EggDrawBox calibratedHatchEggBox(int cx, int cy, int size, String source) {
        float w = Math.max(1f, size * 0.76f);
        float h = Math.max(1f, size * 0.98f);
        return new EggDrawBox(cx - w * 0.5f, cy - h * 0.5f,
                cx + w * 0.5f, cy + h * 0.5f, source);
    }

    private static EggDrawBox alphaRecordedEggBox(BoundsRecorder rec) {
        if (rec == null) return null;
        float minX = 0f, minY = 0f, maxX = 0f, maxY = 0f;
        boolean found = false;
        try {
            List<BoundsRecorder.SpritePart> parts = rec.parts();
            for (int i = 0; i < parts.size(); i++) {
                BoundsRecorder.SpritePart part = parts.get(i);
                if (part == null || part.image == null || part.matrix == null || part.w <= 0f || part.h <= 0f) continue;
                AlphaBounds alpha = hatchAlphaBounds(part.image);
                if (alpha == null || !alpha.valid()) continue;
                float iw = Math.max(1f, part.image.getWidth());
                float ih = Math.max(1f, part.image.getHeight());
                float lx0 = part.x + part.w * alpha.left / iw;
                float ly0 = part.y + part.h * alpha.top / ih;
                float lx1 = part.x + part.w * alpha.right / iw;
                float ly1 = part.y + part.h * alpha.bottom / ih;
                float[] xs = new float[]{
                        projectPartX(part.matrix, lx0, ly0),
                        projectPartX(part.matrix, lx1, ly0),
                        projectPartX(part.matrix, lx1, ly1),
                        projectPartX(part.matrix, lx0, ly1)
                };
                float[] ys = new float[]{
                        projectPartY(part.matrix, lx0, ly0),
                        projectPartY(part.matrix, lx1, ly0),
                        projectPartY(part.matrix, lx1, ly1),
                        projectPartY(part.matrix, lx0, ly1)
                };
                for (int p = 0; p < 4; p++) {
                    if (!finite(xs[p]) || !finite(ys[p])) continue;
                    if (!found) {
                        minX = maxX = xs[p];
                        minY = maxY = ys[p];
                        found = true;
                    } else {
                        if (xs[p] < minX) minX = xs[p];
                        if (xs[p] > maxX) maxX = xs[p];
                        if (ys[p] < minY) minY = ys[p];
                        if (ys[p] > maxY) maxY = ys[p];
                    }
                }
            }
        } catch (Throwable t) {
            Logger.err("Egg Pet hatch alpha bounds failed", t);
            return null;
        }
        if (!found || maxX <= minX || maxY <= minY) return null;
        return new EggDrawBox(minX, minY, maxX, maxY, "alpha-recorded");
    }

    private static float projectPartX(float[] m, float x, float y) {
        return m[0] * x + m[1] * y + m[2];
    }

    private static float projectPartY(float[] m, float x, float y) {
        return m[3] * x + m[4] * y + m[5];
    }

    private static AlphaBounds hatchAlphaBounds(FakeImage img) {
        if (img == null) return null;
        AlphaBounds cached = hatchAlphaBoundsCache.get(img);
        if (cached != null) return cached;
        AlphaBounds fresh = scanAlphaBounds(img);
        if (fresh != null) hatchAlphaBoundsCache.put(img, fresh);
        return fresh;
    }

    private static AlphaBounds scanAlphaBounds(FakeImage img) {
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
            int left = w, top = h, right = -1, bottom = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = (img.getRGB(x, y) >>> 24) & 255;
                    if (a <= 8) continue;
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
            if (right < left || bottom < top) return null;
            return new AlphaBounds(left, top, right + 1, bottom + 1);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void drawHatchCrackImage(FakeGraphics gra, EggDrawBox eggBox, int alpha) {
        if (gra == null || alpha <= 0) return;
        FakeImage img = hatchCrackImage();
        if (img == null || eggBox == null) return;
        float w = Math.max(1f, eggBox.width());
        float h = Math.max(1f, eggBox.height());
        float cx = eggBox.cx();
        float cy = eggBox.cy();
        try {
            try { gra.setComposite(FakeGraphics.TRANS, clampInt(alpha, 0, 255), 0); } catch (Throwable ignored) {}
            gra.drawImage(img, cx - w * 0.5f, cy - h * 0.5f, w, h);
        } catch (Throwable t) {
            Logger.err("Egg Pet hatch crack image draw failed", t);
        } finally {
            resetComposite(gra);
        }
    }

    private static FakeImage hatchCrackImage() {
        FakeImage img = hatchCrackImage;
        if (img != null) return img;
        synchronized (EggPetFeature.class) {
            img = hatchCrackImage;
            if (img == null) hatchCrackImage = img = buildHatchCrackImage();
        }
        return img;
    }

    private static FakeImage buildHatchCrackImage() {
        int w = 128;
        int h = 160;
        float fw = w;
        float fh = h;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setClip(new java.awt.geom.Ellipse2D.Float(3f, 2f, fw - 6f, fh - 4f));

            Path2D main = new Path2D.Float();
            main.moveTo(fw * 0.51f, fh * 0.08f);
            main.curveTo(fw * 0.58f, fh * 0.20f, fw * 0.45f, fh * 0.30f, fw * 0.51f, fh * 0.42f);
            main.curveTo(fw * 0.58f, fh * 0.55f, fw * 0.40f, fh * 0.66f, fw * 0.47f, fh * 0.78f);
            main.curveTo(fw * 0.55f, fh * 0.91f, fw * 0.34f, fh * 0.98f, fw * 0.42f, fh * 1.08f);

            Path2D upper = new Path2D.Float();
            upper.moveTo(fw * 0.50f, fh * 0.41f);
            upper.curveTo(fw * 0.63f, fh * 0.37f, fw * 0.72f, fh * 0.29f, fw * 0.84f, fh * 0.25f);

            Path2D left = new Path2D.Float();
            left.moveTo(fw * 0.47f, fh * 0.77f);
            left.curveTo(fw * 0.36f, fh * 0.72f, fw * 0.26f, fh * 0.70f, fw * 0.16f, fh * 0.64f);

            Path2D right = new Path2D.Float();
            right.moveTo(fw * 0.42f, fh * 0.92f);
            right.curveTo(fw * 0.55f, fh * 0.95f, fw * 0.66f, fh * 1.02f, fw * 0.82f, fh * 1.03f);

            Path2D hair = new Path2D.Float();
            hair.moveTo(fw * 0.55f, fh * 0.22f);
            hair.curveTo(fw * 0.45f, fh * 0.17f, fw * 0.36f, fh * 0.11f, fw * 0.27f, fh * 0.09f);

            Path2D[] cracks = new Path2D[]{main, upper, left, right, hair};
            drawCrackStrokes(g, cracks, new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
                    new Color(34, 22, 17, 86));
            drawCrackStrokes(g, cracks, new BasicStroke(3.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
                    new Color(52, 32, 23, 210));
            g.translate(-0.8, -0.8);
            drawCrackStrokes(g, cracks, new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
                    new Color(154, 104, 73, 135));
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static void drawCrackStrokes(Graphics2D g, Path2D[] cracks, Stroke stroke, Color color) {
        g.setStroke(stroke);
        g.setColor(color);
        for (Path2D crack : cracks) g.draw(crack);
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || gra == null || !enabled(rt)) return;
        ensureInitialized(rt);
        State st = rt.eggPet;
        if (st.life != LIFE_HATCHING && st.life != LIFE_EMERGING && st.life != LIFE_HATCHED && st.life != LIFE_DEAD) {
            drawIcon(rt, bbpainter, gra);
        }
        if (st.dragging) {
            drawBaseDropTarget(rt, bbpainter, gra);
            int[] point = dragEggDrawPoint(st, bbpainter);
            drawEggAt(rt, gra, point[0], point[1], dragEggSize(bbpainter), true, 1f);
        } else if (st.returning) {
            Rect icon = iconRectFromPainter(bbpainter);
            float p = clamp01(st.returnFrame / (float) RETURN_FRAMES);
            float e = 1f - (1f - p) * (1f - p);
            int x = Math.round(st.returnFromX + (icon.cx() - st.returnFromX) * e);
            int y = Math.round(st.returnFromY + (icon.cy() - st.returnFromY) * e);
            drawEggAt(rt, gra, x, y, icon.w, true, 1f);
        }
        if (st.life == LIFE_HATCHING) {
            drawHatchOverlay(rt, gra);
        }
        if (st.screen == SCREEN_OFFER) {
            drawOfferOverlay(rt, bbpainter, gra);
        } else if (st.screen == SCREEN_UPGRADE) {
            drawUpgradeOverlay(rt, bbpainter, gra);
        }
    }

    private static void handleOfferClick(CrazyRuntime.StageRuntime rt, Object page, int mx, int my) {
        State st = rt.eggPet;
        Rect reroll = offerButton(page, 0);
        Rect accept = offerButton(page, 1);
        Rect skip = offerButton(page, 2);
        StageBasis sb = (StageBasis) rt.stage;
        if (reroll.contains(mx, my)) {
            int cost = st.current == null ? 500 : st.current.rerollCost;
            if (st.current == null || sb.money < cost * INTERNAL_MONEY_MULT) {
                reject();
                return;
            }
            sb.money = Math.max(0, sb.money - cost * INTERNAL_MONEY_MULT);
            Candidate old = st.current;
            st.previous = old;
            st.current = randomCandidate(rt, old == null ? null : old.key);
            if (st.current == null) st.current = old;
            play(27);
            Logger.log("Egg Pet reroll cost=" + cost + " candidate=" + (st.current == null ? "null" : st.current.key));
            return;
        }
        if (accept.contains(mx, my)) {
            if (st.current == null) {
                reject();
                return;
            }
            st.life = LIFE_ACCEPTED;
            st.screen = SCREEN_UPGRADE;
            play(19);
            Logger.log("Egg Pet accepted candidate=" + st.current.key);
            return;
        }
        if (skip.contains(mx, my)) {
            st.screen = SCREEN_NONE;
            if (st.current != null && st.life == LIFE_EMPTY) st.life = LIFE_OFFERED;
            play(19);
            return;
        }
    }

    private static void handleUpgradeClick(CrazyRuntime.StageRuntime rt, Object page, int mx, int my) {
        State st = rt.eggPet;
        Rect close = upgradeCloseButton(page);
        if (close.contains(mx, my)) {
            st.screen = SCREEN_NONE;
            play(19);
            return;
        }
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = 0; i < U_COUNT; i++) {
            Rect r = upgradeButton(page, i);
            if (!r.contains(mx, my)) continue;
            int cost = upgradeCost(st.upgrades[i]);
            if (sb.money < cost * INTERNAL_MONEY_MULT) {
                reject();
                return;
            }
            sb.money = Math.max(0, sb.money - cost * INTERNAL_MONEY_MULT);
            st.upgrades[i]++;
            st.flashStat = i;
            st.flashFrame = FLASH_FRAMES;
            play(19);
            Logger.log("Egg Pet upgrade " + STAT_NAMES[i] + " level=" + st.upgrades[i] + " cost=" + cost);
            return;
        }

    }

    private static void beginHatch(CrazyRuntime.StageRuntime rt, Object page, int hatchX, int hatchY) {
        State st = rt.eggPet;
        if (st.current == null || st.life != LIFE_ACCEPTED) {
            reject();
            return;
        }
        float[] p = baseDoorPoint(page);
        st.hatchX = finite(hatchX) ? hatchX : p[0];
        st.hatchY = finite(hatchY) ? hatchY : p[1];
        st.hatchLayer = Math.round(p[2]);
        st.hatchFrame = 0;
        st.emergeFrame = 0;
        st.hatchDrawLogged = false;
        st.hatchTickLogged = false;
        st.hatchFailsafeLogged = false;
        st.hatchCrackBoundsLogged = false;
        st.hatchPhaseStartedAt = System.currentTimeMillis();
        st.life = LIFE_HATCHING;
        st.screen = SCREEN_NONE;
        st.dragging = false;
        st.returning = false;
        play(19);
        Logger.log("Egg Pet hatch started at screen=(" + st.hatchX + "," + st.hatchY + ")");
    }

    private static void hatchNow(CrazyRuntime.StageRuntime rt) {
        State st = rt.eggPet;
        if (st.current == null) {
            st.life = LIFE_DEAD;
            return;
        }
        try {
            StageBasis sb = (StageBasis) rt.stage;
            Entity pet = createPetEntity(rt, st.current);
            if (pet == null) {
                st.life = LIFE_DEAD;
                reject();
                return;
            }
            float pos = hatchSpawnPos(sb);
            pet.added(-1, pos);
            try { EntityAccess.setLayer(pet, clampInt(st.hatchLayer, 0, 9)); } catch (Throwable ignored) {}
            applyHatchStats(rt, pet, st.current);
            addEntitySorted(sb, pet);
            st.pet = pet;
            st.life = LIFE_EMERGING;
            st.emergeFrame = 0;
            st.hatchPhaseStartedAt = System.currentTimeMillis();
            st.screen = SCREEN_NONE;
            st.pets.put(pet, new PetStats(pet, totalScale(st), st.upgrades[U_SPEED],
                    st.upgrades[U_TBA], st.upgrades[U_ATTACK_SPEED], st.current.enemy));
            if (st.current.enemy) ConvertedRegistry.mark(pet);
            Logger.log("Egg Pet pet emerging key=" + st.current.key + " entity="
                    + pet.getClass().getSimpleName() + " targetScale=" + totalScale(st));
        } catch (Throwable t) {
            Logger.err("Egg Pet hatch failed", t);
            st.life = LIFE_DEAD;
            reject();
        }
    }

    private static void completeEmergence(CrazyRuntime.StageRuntime rt) {
        State st = rt.eggPet;
        Candidate hatched = st.current;
        Entity activated = st.pet;
        st.emergeFrame = EMERGE_FRAMES;
        st.hatchPhaseStartedAt = 0L;
        play(29);
        Logger.log("Egg Pet activated after emergence entity="
                + (activated == null ? "null" : activated.getClass().getSimpleName()));
        st.pet = null;
        prepareNextEggAfterHatch(rt, hatched == null ? null : hatched.key);
    }

    private static void prepareNextEggAfterHatch(CrazyRuntime.StageRuntime rt, String avoidKey) {
        if (rt == null) return;
        State st = rt.eggPet;
        Candidate old = st.current;
        st.previous = old;
        resetUpgradeState(st);
        st.current = randomCandidate(rt, avoidKey);
        st.life = st.current == null ? LIFE_EMPTY : LIFE_OFFERED;
        st.screen = SCREEN_NONE;
        st.dragging = false;
        st.returning = false;
        st.hatchFrame = 0;
        st.emergeFrame = 0;
        st.hatchPhaseStartedAt = 0L;
        Logger.log("Egg Pet next egg rolled after hatch candidate="
                + (st.current == null ? "null" : st.current.key)
                + " avoid=" + (avoidKey == null ? "none" : avoidKey));
    }

    private static void resetUpgradeState(State st) {
        if (st == null) return;
        for (int i = 0; i < st.upgrades.length; i++) st.upgrades[i] = 0;
        st.flashStat = -1;
        st.flashFrame = 0;
    }

    private static Entity createPetEntity(CrazyRuntime.StageRuntime rt, Candidate c) {
        StageBasis sb = (StageBasis) rt.stage;
        if (c.enemy) {
            if (c.enemyUnit == null) return null;
            EEnemy enemy = c.enemyUnit.getEntity(sb, null, 1f, 1f, 0, 9, 0, 0);
            try { enemy.dire = -1; } catch (Throwable ignored) {}
            return enemy;
        }
        if (c.form == null) return null;
        Level lv = levelWithUpgrade(c.baseLevel, rt.eggPet.upgrades[U_LEVEL]);
        EForm ef = new EForm(c.form, lv);
        return ef.getEntity(sb, null, false, false);
    }

    private static void applyHatchStats(CrazyRuntime.StageRuntime rt, Entity pet, Candidate c) {
        State st = rt.eggPet;
        int extraHp = st.upgrades[U_HP] + (c.enemy ? st.upgrades[U_LEVEL] : 0);
        int extraAtk = st.upgrades[U_ATTACK] + (c.enemy ? st.upgrades[U_LEVEL] : 0);
        if (extraHp > 0) {
            long max = scaleLong(pet.maxH, extraHp);
            pet.maxH = Math.max(1L, max);
            pet.health = pet.maxH;
        }
        if (extraAtk > 0) {
            try {
                Object aam = BCUFields.get(pet, "aam");
                int[] atks = (int[]) BCUFields.get(aam, "atks");
                for (int i = 0; i < atks.length; i++) {
                    if (atks[i] > 0) atks[i] = clampInt(scaleLong(atks[i], extraAtk));
                }
            } catch (Throwable t) {
                Logger.err("Egg Pet attack stat apply failed", t);
            }
        }
        try { BCUFields.setInt(pet, "price", 0); } catch (Throwable ignored) {}
    }

    private static void capturePetPositions(CrazyRuntime.StageRuntime rt) {
        State st = rt.eggPet;
        st.beforePos.clear();
        for (Object obj : new ArrayList<Object>(st.pets.keySet())) {
            if (obj instanceof Entity) {
                Entity e = (Entity) obj;
                if (!e.dead && e.health > 0L) st.beforePos.put(e, e.pos);
            }
        }
    }

    private static void applyPetMotion(CrazyRuntime.StageRuntime rt) {
        State st = rt.eggPet;
        for (Map.Entry<Object, PetStats> entry
                : new ArrayList<Map.Entry<Object, PetStats>>(st.pets.entrySet())) {
            if (!(entry.getKey() instanceof Entity)) {
                st.pets.remove(entry.getKey());
                continue;
            }
            Entity e = (Entity) entry.getKey();
            PetStats stats = entry.getValue();
            if (e.dead || e.health <= 0L) {
                markDead(rt, e);
                continue;
            }
            Float before = st.beforePos.get(e);
            if (before != null && stats.speedLevel > 0) {
                float delta = e.pos - before.floatValue();
                if (finite(delta) && Math.abs(delta) > 0.0001f && Math.abs(delta) < 2000f) {
                    int baseSpeed = 1;
                    try { baseSpeed = Math.max(1, e.data.getSpeed()); } catch (Throwable ignored) {}
                    float extra = delta * (speedMultiplier(baseSpeed, stats.speedLevel) - 1f);
                    e.pos += extra;
                    try { BCUFields.field(e.getClass(), "lastPosition").setFloat(e, e.pos); } catch (Throwable ignored) {}
                }
            }
            accelerateTimers(st, e, stats);
        }
        st.beforePos.clear();
    }

    private static void accelerateTimers(State st, Entity e, PetStats stats) {
        if (stats.tbaLevel <= 0 && stats.attackSpeedLevel <= 0) return;
        MotionState ms = st.motion.get(e);
        if (ms == null) {
            ms = new MotionState();
            st.motion.put(e, ms);
        }
        if (stats.tbaLevel > 0) {
            try {
                int wait = BCUFields.getInt(e, "waitTime");
                if (wait > 1 && (ms.lastWait <= 1 || wait > ms.lastWait + 1)) {
                    wait = reduceFrames(wait, stats.tbaLevel);
                    BCUFields.setInt(e, "waitTime", wait);
                }
                ms.lastWait = wait;
            } catch (Throwable ignored) {}
        }
        if (stats.attackSpeedLevel > 0) {
            try {
                Object atkm = BCUFields.get(e, "atkm");
                normalizeAttackTimers(ms, e, atkm, stats.attackSpeedLevel, false);
                fastForwardAttackAnimation(ms, e, atkm, stats.attackSpeedLevel);
            } catch (Throwable ignored) {}
        }
    }

    public static void onAttackStarted(Object atkm) {
        handleAttackManagerHook(atkm, true);
    }

    public static void onAttackUpdateFinished(Object atkm) {
        handleAttackManagerHook(atkm, false);
    }

    private static void handleAttackManagerHook(Object atkm, boolean started) {
        if (atkm == null) return;
        try {
            Object obj = BCUFields.get(atkm, "e");
            if (!(obj instanceof Entity)) return;
            Entity e = (Entity) obj;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(e.basis);
            if (rt == null) return;
            PetStats stats = rt.eggPet.pets.get(e);
            if (stats == null || stats.attackSpeedLevel <= 0) return;
            MotionState ms = rt.eggPet.motion.get(e);
            if (ms == null) {
                ms = new MotionState();
                rt.eggPet.motion.put(e, ms);
            }
            normalizeAttackTimers(ms, e, atkm, stats.attackSpeedLevel, started);
        } catch (Throwable t) {
            Logger.err("Egg Pet attack manager hook failed", t);
        }
    }

    private static final OptionalFeatures.Call ATTACK_FORM_LENGTH = OptionalFeatures.bind(
            "manualcontrol.attackform.runtime.AttackFormRuntimeHooks",
            "effectiveAttackLength", Entity.class, int.class);

    private static void normalizeAttackTimers(MotionState ms, Entity e, Object atkm,
                                              int attackSpeedLevel, boolean forceStart) {
        if (ms == null || e == null || atkm == null || attackSpeedLevel <= 0) return;
        try {
            int stockLen = e.data.getAnimLen();
            int originalLen = ATTACK_FORM_LENGTH.invokeInt(stockLen, e, Integer.valueOf(stockLen));
            int reducedLen = reduceFrames(originalLen, attackSpeedLevel);
            int atk = BCUFields.getInt(atkm, "atkTime");
            int pre = BCUFields.getInt(atkm, "preTime");

            if (atk > 0 && reducedLen < originalLen) {
                int normalizedAtk = atk;
                if (forceStart) {
                    normalizedAtk = reducedLen;
                } else if (atk > reducedLen) {
                    int elapsedOriginal = clampInt(originalLen - atk, 0, originalLen);
                    int elapsedReduced = reduceElapsedFrames(elapsedOriginal, attackSpeedLevel);
                    normalizedAtk = Math.max(1, reducedLen - elapsedReduced);
                }
                if (normalizedAtk != atk) {
                    BCUFields.setInt(atkm, "atkTime", normalizedAtk);
                    atk = normalizedAtk;
                }
            }

            if (pre > 0 && reducedLen < originalLen
                    && (forceStart || ms.lastPre <= 1 || pre > ms.lastPre + 1)) {
                int normalizedPre = reduceAttackInterval(pre, attackSpeedLevel);
                if (normalizedPre != pre) {
                    BCUFields.setInt(atkm, "preTime", normalizedPre);
                    pre = normalizedPre;
                }
            }

            ms.lastPre = pre;
            ms.lastAtk = atk;
            if (!ms.attackTimerSpeedLogged && reducedLen < originalLen && atk > 0) {
                ms.attackTimerSpeedLogged = true;
                Logger.log("Egg Pet attack timers scaled level=" + attackSpeedLevel
                        + " anim=" + originalLen + "->" + reducedLen);
            }
        } catch (Throwable ignored) {}
    }

    private static void fastForwardAttackAnimation(MotionState ms, Entity e, Object atkm, int attackSpeedLevel) {
        if (ms == null || e == null || atkm == null || attackSpeedLevel <= 0) return;
        try {
            int atk = BCUFields.getInt(atkm, "atkTime");
            if (atk <= 0) return;
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
                    Logger.log("Egg Pet attack animation speed active level=" + attackSpeedLevel
                            + " len=" + len + " reduced=" + reducedLen);
                }
            }
        } catch (Throwable t) {
            Logger.err("Egg Pet attack animation speed failed", t);
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

    private static int reduceAttackInterval(int frames, int level) {
        if (frames <= 0) return frames;
        return reduceFrames(frames, level);
    }

    private static int reduceElapsedFrames(int elapsed, int level) {
        if (elapsed <= 0) return 0;
        return Math.max(1, reduceFrames(elapsed, level));
    }

    private static void checkPetDeath(CrazyRuntime.StageRuntime rt) {
        State st = rt.eggPet;
        if (st.pet != null && (st.pet.dead || st.pet.health <= 0L)) {
            markDead(rt, st.pet);
        }
    }

    private static void markDead(CrazyRuntime.StageRuntime rt, Entity e) {
        if (rt == null || e == null) return;
        State st = rt.eggPet;
        st.pets.remove(e);
        st.motion.remove(e);
        st.beforePos.remove(e);
        st.enemyMoneyBeforeKill.remove(e);
        if (st.pet == e) {
            boolean emerging = st.life == LIFE_EMERGING;
            st.pet = null;
            st.dragging = false;
            st.returning = false;
            if (emerging) {
                st.screen = SCREEN_NONE;
                prepareNextEggAfterHatch(rt, st.current == null ? null : st.current.key);
            }
        }
        Logger.log("Egg Pet pet died entity=" + e.getClass().getSimpleName());
    }

    private static void drawIcon(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        Rect icon = iconRectFromPainter(bbpainter);
        State st = rt.eggPet;
        if (!st.iconRectLogged) {
            st.iconRectLogged = true;
            Logger.log("Egg Pet icon visible rect=(" + icon.x + "," + icon.y + ","
                    + icon.w + "," + icon.h + ")");
        }
        boolean active = st.current != null || st.life == LIFE_EMPTY || st.life == LIFE_OFFERED || st.life == LIFE_ACCEPTED;
        if (!st.dragging && !st.returning) drawEggAt(rt, gra, icon.cx(), icon.cy(), icon.w, active, 1f);
    }

    private static void drawBaseDropTarget(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || bbpainter == null || gra == null) return;
        try {
            BaseDropArea area = baseDropAreaFromPainter(bbpainter);
            if (area == null) return;
            State st = rt.eggPet;
            boolean hot = st != null && area.contains(st.dragX, st.dragY);
            int fillAlpha = hot ? 82 : 32;
            int outlineAlpha = hot ? 255 : 205;
            int outlineR = hot ? 96 : 58;
            int outlineG = hot ? 255 : 224;
            int outlineB = hot ? 146 : 118;
            gra.colRect(area.left, area.top, area.right - area.left, area.bottom - area.top,
                    58, 224, 118, fillAlpha);
            drawPrimitiveRectOutline(gra, area.left, area.top, area.right - area.left,
                    area.bottom - area.top, hot ? 6 : 4, outlineR, outlineG, outlineB, outlineAlpha);

            float s = Math.max(0.5f, area.siz);
            int marker = Math.max(22, Math.round(42f * s));
            gra.colRect(area.doorX - marker / 2, area.doorY - marker / 2, marker, marker,
                    255, 241, 186, hot ? 120 : 70);
            drawPrimitiveRectOutline(gra, area.doorX - marker / 2, area.doorY - marker / 2,
                    marker, marker, hot ? 5 : 3, 255, 241, 186, hot ? 255 : 230);
            if (hot) {
                int pulse = (int) ((System.currentTimeMillis() / 110L) % 3L);
                int hatchScale = 4 + (pulse == 0 ? 1 : 0);
                int tw = primitiveTextWidth("HATCH", hatchScale);
                int tx = Math.round(area.doorX - tw / 2f);
                int ty = Math.max(10, Math.round(area.doorY - 72f * s));
                gra.colRect(tx - 12, ty - 10, tw + 24, 7 * hatchScale + 20,
                        8, 20, 12, 185);
                drawPrimitiveText(gra, "HATCH", tx, ty, hatchScale, 98, 255, 142, 255);
                if (st != null) {
                    int eggSize = dragEggSize(bbpainter);
                    drawPrimitiveRectOutline(gra, area.doorX - eggSize / 2 - 8,
                            area.doorY - eggSize / 2 - 8, eggSize + 16, eggSize + 16,
                            5, 98, 255, 142, 245);
                }
            } else {
                int tw = primitiveTextWidth("DROP", 2);
                drawPrimitiveText(gra, "DROP", area.doorX - tw / 2,
                        Math.max(10, area.top - 26), 2, 255, 241, 186, 255);
            }
        } catch (Throwable t) {
            Logger.err("Egg Pet draw base drop target failed", t);
        }
    }

    private static int dragEggSize(Object bbpainter) {
        try {
            Rect icon = iconRectFromPainter(bbpainter);
            return Math.max(icon.w, Math.round(icon.w * 1.12f));
        } catch (Throwable ignored) {
            return 72;
        }
    }

    private static int[] dragEggDrawPoint(State st, Object bbpainter) {
        if (st == null) return new int[]{0, 0};
        try {
            BaseDropArea area = baseDropAreaFromPainter(bbpainter);
            if (area != null && area.contains(st.dragX, st.dragY)) {
                return new int[]{area.doorX, area.doorY};
            }
        } catch (Throwable ignored) {}
        return new int[]{st.dragX, st.dragY};
    }

    private static void drawOfferOverlay(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.eggPet;
        if (st.current == null) return;
        int w = BBPainterAccess.getWidth(bbpainter);
        int h = BBPainterAccess.getHeight(bbpainter);
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) {
            drawOfferOverlayFallback(rt, w, h, gra);
            return;
        }
        DrawState ds = pushOverlay(g);
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(0.62f));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            Rect panel = overlayPanel(w, h);
            drawPanel(g, panel);
            drawTitle(g, "Egg Pet", panel.x + 28, panel.y + 42);
            drawSilhouette(g, panel.x + Math.round(panel.w * 0.23f), panel.y + Math.round(panel.h * 0.47f),
                    Math.round(panel.w * 0.28f), Math.round(panel.h * 0.48f), st.current.seed);

            StatBlock stats = computeStats(rt, st.current, st.upgrades);
            int sx = panel.x + Math.round(panel.w * 0.48f);
            int sy = panel.y + 86;
            drawSmallText(g, "Mystery unit", sx, sy, 22, new Color(255, 241, 186));
            sy += 34;
            drawMetric(g, "HP", format(stats.hp), sx, sy); sy += 28;
            drawMetric(g, "Attack", format(stats.attack), sx, sy); sy += 28;
            drawMetric(g, "Speed", formatSpeed(stats.speed), sx, sy); sy += 28;
            drawMetric(g, "TBA", stats.tba + "f", sx, sy); sy += 28;
            drawMetric(g, "Attack Anim", stats.attackFrames + "f", sx, sy); sy += 28;
            drawMetric(g, "Scale", stats.scalePct + "%", sx, sy); sy += 36;
            drawMetric(g, "Reroll", st.current.rerollCost + "$", sx, sy);

            StageBasis sb = (StageBasis) rt.stage;
            drawButton(g, offerButton(w, h, 0), "Reroll", sb.money >= st.current.rerollCost * INTERNAL_MONEY_MULT, false);
            drawButton(g, offerButton(w, h, 1), "Accept", true, false);
            drawButton(g, offerButton(w, h, 2), "Skip", true, false);
        } finally {
            popOverlay(g, ds);
        }
    }

    private static void drawUpgradeOverlay(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State st = rt.eggPet;
        if (st.current == null) return;
        int w = BBPainterAccess.getWidth(bbpainter);
        int h = BBPainterAccess.getHeight(bbpainter);
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) {
            drawUpgradeOverlayFallback(rt, w, h, gra);
            return;
        }
        DrawState ds = pushOverlay(g);
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(0.62f));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            Rect panel = overlayPanel(w, h);
            drawPanel(g, panel);
            drawTitle(g, "Upgrade Egg", panel.x + 28, panel.y + 42);
            drawSilhouette(g, panel.x + Math.round(panel.w * 0.16f), panel.y + Math.round(panel.h * 0.30f),
                    Math.round(panel.w * 0.17f), Math.round(panel.h * 0.28f), st.current.seed);
            drawSmallText(g, "Drag the egg icon onto your base to hatch.", panel.x + 28,
                    panel.y + panel.h - 30, 15, new Color(218, 220, 226));

            int x = panel.x + Math.round(panel.w * 0.33f);
            int y = panel.y + 82;
            drawHeader(g, x, y);
            y += 30;
            StageBasis sb = (StageBasis) rt.stage;
            for (int i = 0; i < U_COUNT; i++) {
                drawUpgradeRow(rt, g, pageSafeWidth(w), pageSafeHeight(h), x, y + i * 43, i,
                        sb.money >= upgradeCost(st.upgrades[i]) * INTERNAL_MONEY_MULT);
            }
            StatBlock stats = computeStats(rt, st.current, st.upgrades);
            drawSmallText(g, "Scale: " + stats.scalePct + "%", panel.x + 28, panel.y + Math.round(panel.h * 0.58f),
                    20, new Color(255, 241, 186));
            drawButton(g, upgradeCloseButton(w, h), "Close", true, false);
        } finally {
            popOverlay(g, ds);
        }
    }

    private static void drawOfferOverlayFallback(CrazyRuntime.StageRuntime rt, int w, int h, FakeGraphics gra) {
        State st = rt.eggPet;
        Rect panel = overlayPanel(w, h);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            gra.colRect(0, 0, w, h, 0, 0, 0, 150);
            logPrimitiveOverlay(st, gra);
            drawPrimitiveOfferOverlay(rt, w, h, panel, gra);
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void drawUpgradeOverlayFallback(CrazyRuntime.StageRuntime rt, int w, int h, FakeGraphics gra) {
        Rect panel = overlayPanel(w, h);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            gra.colRect(0, 0, w, h, 0, 0, 0, 150);
            logPrimitiveOverlay(rt.eggPet, gra);
            drawPrimitiveUpgradeOverlay(rt, w, h, panel, gra);
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void logPrimitiveOverlay(State st, FakeGraphics gra) {
        if (st == null || st.primitiveOverlayLogged) return;
        st.primitiveOverlayLogged = true;
        Logger.log("Egg Pet primitive overlay fallback active graphics="
                + (gra == null ? "null" : gra.getClass().getName()));
    }

    private static void drawPrimitiveOfferOverlay(CrazyRuntime.StageRuntime rt, int w, int h, Rect panel, FakeGraphics gra) {
        State st = rt.eggPet;
        if (st.current == null) return;
        drawPrimitivePanel(gra, panel);
        drawPrimitiveText(gra, "EGG PET", panel.x + 28, panel.y + 26, 4, 255, 240, 178, 255);
        drawPrimitiveSilhouette(gra, panel.x + Math.round(panel.w * 0.23f), panel.y + Math.round(panel.h * 0.48f),
                Math.round(panel.w * 0.20f), Math.round(panel.h * 0.38f), st.current.seed);

        StatBlock stats = computeStats(rt, st.current, st.upgrades);
        int sx = panel.x + Math.round(panel.w * 0.48f);
        int sy = panel.y + 82;
        drawPrimitiveText(gra, "MYSTERY UNIT", sx, sy, 3, 255, 241, 186, 255);
        sy += 42;
        drawPrimitiveMetric(gra, "HP", format(stats.hp), sx, sy); sy += 34;
        drawPrimitiveMetric(gra, "ATTACK", format(stats.attack), sx, sy); sy += 34;
        drawPrimitiveMetric(gra, "SPEED", formatSpeed(stats.speed), sx, sy); sy += 34;
        drawPrimitiveMetric(gra, "TBA", stats.tba + "F", sx, sy); sy += 34;
        drawPrimitiveMetric(gra, "ATK ANIM", stats.attackFrames + "F", sx, sy); sy += 34;
        drawPrimitiveMetric(gra, "SCALE", stats.scalePct + "%", sx, sy); sy += 42;
        drawPrimitiveMetric(gra, "REROLL", st.current.rerollCost + "$", sx, sy);

        StageBasis sb = (StageBasis) rt.stage;
        drawPrimitiveButton(gra, offerButton(w, h, 0), "REROLL",
                sb.money >= st.current.rerollCost * INTERNAL_MONEY_MULT, false);
        drawPrimitiveButton(gra, offerButton(w, h, 1), "ACCEPT", true, false);
        drawPrimitiveButton(gra, offerButton(w, h, 2), "SKIP", true, false);
    }

    private static void drawPrimitiveUpgradeOverlay(CrazyRuntime.StageRuntime rt, int w, int h, Rect panel, FakeGraphics gra) {
        State st = rt.eggPet;
        if (st.current == null) return;
        drawPrimitivePanel(gra, panel);
        drawPrimitiveText(gra, "UPGRADE EGG", panel.x + 28, panel.y + 26, 4, 255, 240, 178, 255);
        drawPrimitiveSilhouette(gra, panel.x + Math.round(panel.w * 0.16f), panel.y + Math.round(panel.h * 0.30f),
                Math.round(panel.w * 0.13f), Math.round(panel.h * 0.22f), st.current.seed);
        drawPrimitiveText(gra, "DRAG EGG TO BASE TO HATCH", panel.x + 28, panel.y + panel.h - 34,
                2, 218, 220, 226, 255);

        int x = panel.x + Math.round(panel.w * 0.30f);
        int y = panel.y + 78;
        drawPrimitiveText(gra, "STAT", x, y, 2, 166, 174, 188, 255);
        drawPrimitiveText(gra, "LV", x + 116, y, 2, 166, 174, 188, 255);
        drawPrimitiveText(gra, "COST", x + 170, y, 2, 166, 174, 188, 255);
        drawPrimitiveText(gra, "VALUE", x + 248, y, 2, 166, 174, 188, 255);
        drawPrimitiveText(gra, "GAIN", upgradeButton(w, h, 0).x - 128, y, 2, 166, 174, 188, 255);
        y += 32;

        StageBasis sb = (StageBasis) rt.stage;
        for (int i = 0; i < U_COUNT; i++) {
            drawPrimitiveUpgradeRow(rt, gra, w, h, panel, x, y + i * 58, i,
                    sb.money >= upgradeCost(st.upgrades[i]) * INTERNAL_MONEY_MULT);
        }
        StatBlock stats = computeStats(rt, st.current, st.upgrades);
        drawPrimitiveText(gra, "SCALE " + stats.scalePct + "%", panel.x + 28,
                panel.y + Math.round(panel.h * 0.58f), 3, 255, 241, 186, 255);
        drawPrimitiveButton(gra, upgradeCloseButton(w, h), "CLOSE", true, false);
    }

    private static void drawPrimitiveUpgradeRow(CrazyRuntime.StageRuntime rt, FakeGraphics gra, int w, int h,
                                                Rect panel, int x, int y, int stat, boolean enabled) {
        State st = rt.eggPet;
        int[] nextLevels = st.upgrades.clone();
        nextLevels[stat]++;
        StatBlock cur = computeStats(rt, st.current, st.upgrades);
        StatBlock next = computeStats(rt, st.current, nextLevels);
        boolean flash = st.flashStat == stat && st.flashFrame > 0;
        int valueX = x + 248;
        Rect button = upgradeButton(w, h, stat);
        int gainX = button.x - 128;
        if (flash) {
            int a = Math.round(72f * st.flashFrame / (float) FLASH_FRAMES);
            gra.colRect(x - 10, y - 13, button.x + button.w - x + 18, 45, 54, 210, 116, a);
        }
        drawPrimitiveText(gra, primitiveStatName(stat), x, y, 2, 245, 246, 248, 255);
        drawPrimitiveText(gra, Integer.toString(st.upgrades[stat]), x + 120, y, 2, 212, 216, 224, 255);
        drawPrimitiveText(gra, upgradeCost(st.upgrades[stat]) + "$", x + 170, y, 2,
                enabled ? 255 : 140, enabled ? 238 : 140, enabled ? 140 : 140, 255);
        if (stat == U_LEVEL) {
            drawPrimitiveText(gra, "HP " + compact(cur.hp) + " > " + compact(next.hp),
                    valueX, y, 2, 235, 238, 243, 255);
            drawPrimitiveText(gra, "+" + compact(next.hp - cur.hp),
                    gainX, y, 2, 58, 224, 118, 255);
            drawPrimitiveText(gra, "ATK " + compact(cur.attack) + " > " + compact(next.attack),
                    valueX, y + 22, 2, 210, 216, 226, 255);
            drawPrimitiveText(gra, "+" + compact(next.attack - cur.attack),
                    gainX, y + 22, 2, 58, 224, 118, 255);
        } else {
            drawPrimitiveText(gra, primitiveValue(cur, stat) + " > " + primitiveValue(next, stat),
                    valueX, y, 2, 235, 238, 243, 255);
            drawPrimitiveText(gra, primitiveDelta(cur, next, stat),
                    gainX, y, 2, 58, 224, 118, 255);
        }
        drawPrimitiveButton(gra, button, "+", enabled, true);
    }

    private static String primitiveStatName(int stat) {
        switch (stat) {
            case U_LEVEL: return "LEVEL";
            case U_HP: return "HP";
            case U_ATTACK: return "ATTACK";
            case U_SPEED: return "SPEED";
            case U_TBA: return "TBA";
            case U_ATTACK_SPEED: return "ATK SPD";
            default: return "STAT";
        }
    }

    private static String primitiveValue(StatBlock s, int stat) {
        switch (stat) {
            case U_HP:
                return compact(s.hp);
            case U_ATTACK:
                return compact(s.attack);
            case U_SPEED:
                return formatSpeed(s.speed);
            case U_TBA:
                return s.tba + "F";
            case U_ATTACK_SPEED:
                return s.attackFrames + "F";
            default:
                return "";
        }
    }

    private static String primitiveDelta(StatBlock cur, StatBlock next, int stat) {
        switch (stat) {
            case U_HP:
                return "+" + compact(next.hp - cur.hp);
            case U_ATTACK:
                return "+" + compact(next.attack - cur.attack);
            case U_SPEED:
                return formatSpeedDelta(next.speed - cur.speed);
            case U_TBA:
                return "-" + Math.max(0, cur.tba - next.tba) + "F";
            case U_ATTACK_SPEED:
                return "-" + Math.max(0, cur.attackFrames - next.attackFrames) + "F";
            default:
                return "";
        }
    }

    private static String compact(long v) {
        long abs = Math.abs(v);
        if (abs >= 1000000000L) return compactDecimal(v, 1000000000L, "B");
        if (abs >= 1000000L) return compactDecimal(v, 1000000L, "M");
        if (abs >= 10000L) return compactDecimal(v, 1000L, "K");
        return Long.toString(v);
    }

    private static String compactDecimal(long v, long div, String suffix) {
        long sign = v < 0 ? -1L : 1L;
        long abs = Math.abs(v);
        long tenths = (abs * 10L + div / 2L) / div;
        if (tenths >= 1000L || tenths % 10L == 0L) return Long.toString(sign * (tenths / 10L)) + suffix;
        return (sign < 0 ? "-" : "") + (tenths / 10L) + "." + (tenths % 10L) + suffix;
    }

    private static FakeImage fallbackOverlayImage(CrazyRuntime.StageRuntime rt, String key) {
        State st = rt.eggPet;
        if (key != null && key.equals(st.fallbackOverlayKey) && st.fallbackOverlayImage != null) {
            return st.fallbackOverlayImage;
        }
        return null;
    }

    private static FakeImage replaceFallbackOverlayImage(CrazyRuntime.StageRuntime rt, String key, FakeImage fresh) {
        State st = rt.eggPet;
        if (st.fallbackOverlayImage != null) {
            try { st.fallbackOverlayImage.unload(); } catch (Throwable ignored) {}
        }
        st.fallbackOverlayKey = key;
        st.fallbackOverlayImage = fresh;
        return fresh;
    }

    private static FakeImage buildOfferOverlayImage(CrazyRuntime.StageRuntime rt, int w, int h, Rect panel) {
        State st = rt.eggPet;
        if (st.current == null || panel.w <= 0 || panel.h <= 0) return null;
        BufferedImage image = new BufferedImage(panel.w, panel.h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(-panel.x, -panel.y);
            drawPanel(g, panel);
            drawTitle(g, "Egg Pet", panel.x + 28, panel.y + 42);
            drawSilhouette(g, panel.x + Math.round(panel.w * 0.23f), panel.y + Math.round(panel.h * 0.47f),
                    Math.round(panel.w * 0.28f), Math.round(panel.h * 0.48f), st.current.seed);

            StatBlock stats = computeStats(rt, st.current, st.upgrades);
            int sx = panel.x + Math.round(panel.w * 0.48f);
            int sy = panel.y + 86;
            drawSmallText(g, "Mystery unit", sx, sy, 22, new Color(255, 241, 186));
            sy += 34;
            drawMetric(g, "HP", format(stats.hp), sx, sy); sy += 28;
            drawMetric(g, "Attack", format(stats.attack), sx, sy); sy += 28;
            drawMetric(g, "Speed", formatSpeed(stats.speed), sx, sy); sy += 28;
            drawMetric(g, "TBA", stats.tba + "f", sx, sy); sy += 28;
            drawMetric(g, "Attack Anim", stats.attackFrames + "f", sx, sy); sy += 28;
            drawMetric(g, "Scale", stats.scalePct + "%", sx, sy); sy += 36;
            drawMetric(g, "Reroll", st.current.rerollCost + "$", sx, sy);

            StageBasis sb = (StageBasis) rt.stage;
            drawButton(g, offerButton(w, h, 0), "Reroll", sb.money >= st.current.rerollCost * INTERNAL_MONEY_MULT, false);
            drawButton(g, offerButton(w, h, 1), "Accept", true, false);
            drawButton(g, offerButton(w, h, 2), "Skip", true, false);
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static FakeImage buildUpgradeOverlayImage(CrazyRuntime.StageRuntime rt, int w, int h, Rect panel) {
        State st = rt.eggPet;
        if (st.current == null || panel.w <= 0 || panel.h <= 0) return null;
        BufferedImage image = new BufferedImage(panel.w, panel.h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(-panel.x, -panel.y);
            drawPanel(g, panel);
            drawTitle(g, "Upgrade Egg", panel.x + 28, panel.y + 42);
            drawSilhouette(g, panel.x + Math.round(panel.w * 0.16f), panel.y + Math.round(panel.h * 0.30f),
                    Math.round(panel.w * 0.17f), Math.round(panel.h * 0.28f), st.current.seed);
            drawSmallText(g, "Drag the egg icon onto your base to hatch.", panel.x + 28,
                    panel.y + panel.h - 30, 15, new Color(218, 220, 226));

            int x = panel.x + Math.round(panel.w * 0.33f);
            int y = panel.y + 82;
            drawHeader(g, x, y);
            y += 30;
            StageBasis sb = (StageBasis) rt.stage;
            for (int i = 0; i < U_COUNT; i++) {
                drawUpgradeRow(rt, g, pageSafeWidth(w), pageSafeHeight(h), x, y + i * 43, i,
                        sb.money >= upgradeCost(st.upgrades[i]) * INTERNAL_MONEY_MULT);
            }
            StatBlock stats = computeStats(rt, st.current, st.upgrades);
            drawSmallText(g, "Scale: " + stats.scalePct + "%", panel.x + 28, panel.y + Math.round(panel.h * 0.58f),
                    20, new Color(255, 241, 186));
            drawButton(g, upgradeCloseButton(w, h), "Close", true, false);
        } finally {
            g.dispose();
        }
        return buildFakeImage(image);
    }

    private static String fallbackOverlayKey(CrazyRuntime.StageRuntime rt, int screen, int w, int h, Rect panel) {
        State st = rt.eggPet;
        StringBuilder sb = new StringBuilder(96);
        sb.append(screen).append(':').append(w).append('x').append(h)
                .append(':').append(panel.w).append('x').append(panel.h)
                .append(':').append(st.current == null ? "null" : st.current.key);
        try { sb.append(":m").append(((StageBasis) rt.stage).money); } catch (Throwable ignored) {}
        for (int i = 0; i < U_COUNT; i++) sb.append(':').append(st.upgrades[i]);
        sb.append(":f").append(st.flashStat).append(':').append(st.flashFrame);
        return sb.toString();
    }

    private static void drawFallbackPanelBlocks(FakeGraphics gra, Rect panel) {
        gra.colRect(panel.x, panel.y, panel.w, panel.h, 24, 27, 34, 245);
        gra.colRect(panel.x, panel.y, panel.w, 4, 255, 226, 142, 210);
        gra.colRect(panel.x, panel.y + panel.h - 4, panel.w, 4, 255, 226, 142, 210);
        gra.colRect(panel.x, panel.y, 4, panel.h, 255, 226, 142, 210);
        gra.colRect(panel.x + panel.w - 4, panel.y, 4, panel.h, 255, 226, 142, 210);
    }

    private static void drawPrimitivePanel(FakeGraphics gra, Rect panel) {
        drawFallbackPanelBlocks(gra, panel);
        gra.colRect(panel.x + 8, panel.y + 8, panel.w - 16, 2, 72, 79, 94, 210);
        gra.colRect(panel.x + 8, panel.y + panel.h - 10, panel.w - 16, 2, 72, 79, 94, 210);
    }

    private static void drawPrimitiveMetric(FakeGraphics gra, String label, String value, int x, int y) {
        drawPrimitiveText(gra, label, x, y, 2, 176, 184, 198, 255);
        drawPrimitiveText(gra, value, x + 166, y, 2, 245, 247, 250, 255);
    }

    private static void drawPrimitiveButton(FakeGraphics gra, Rect r, String text, boolean enabled, boolean compact) {
        int bgR = enabled ? 64 : 58;
        int bgG = enabled ? 94 : 58;
        int bgB = enabled ? 72 : 62;
        int rimR = enabled ? 95 : 120;
        int rimG = enabled ? 232 : 120;
        int rimB = enabled ? 122 : 124;
        gra.colRect(r.x, r.y, r.w, r.h, bgR, bgG, bgB, 238);
        gra.colRect(r.x, r.y, r.w, 3, rimR, rimG, rimB, 255);
        gra.colRect(r.x, r.y + r.h - 3, r.w, 3, rimR, rimG, rimB, 255);
        gra.colRect(r.x, r.y, 3, r.h, rimR, rimG, rimB, 255);
        gra.colRect(r.x + r.w - 3, r.y, 3, r.h, rimR, rimG, rimB, 255);
        int scale = compact ? 4 : 2;
        while (scale > 1 && primitiveTextWidth(text, scale) > r.w - 14) scale--;
        int tx = r.cx() - primitiveTextWidth(text, scale) / 2;
        int ty = r.cy() - (7 * scale) / 2;
        drawPrimitiveText(gra, text, tx, ty, scale,
                enabled ? 245 : 170, enabled ? 255 : 170, enabled ? 242 : 170, 255);
    }

    private static void drawPrimitiveRectOutline(FakeGraphics gra, int x, int y, int w, int h, int t,
                                                 int r, int g, int b, int alpha) {
        if (gra == null || w <= 0 || h <= 0 || t <= 0 || alpha <= 0) return;
        gra.colRect(x, y, w, t, r, g, b, alpha);
        gra.colRect(x, y + h - t, w, t, r, g, b, alpha);
        gra.colRect(x, y, t, h, r, g, b, alpha);
        gra.colRect(x + w - t, y, t, h, r, g, b, alpha);
    }

    private static void drawPrimitiveSilhouette(FakeGraphics gra, int cx, int cy, int w, int h, int seed) {
        int bodyW = Math.max(40, w);
        int bodyH = Math.max(54, h);
        drawPrimitiveEllipse(gra, cx, cy, bodyW, bodyH, 2, 3, 6, 238);
        drawPrimitiveEllipse(gra, cx, cy - bodyH / 3, Math.round(bodyW * 0.58f),
                Math.round(bodyH * 0.42f), 2, 3, 6, 238);
        int v = Math.abs(seed);
        if ((v & 1) == 0) {
            int earW = Math.max(16, bodyW / 5);
            int earH = Math.max(22, bodyH / 5);
            gra.colRect(cx - bodyW / 3, cy - bodyH / 2 - earH / 2, earW, earH, 2, 3, 6, 238);
            gra.colRect(cx + bodyW / 3 - earW, cy - bodyH / 2 - earH / 2, earW, earH, 2, 3, 6, 238);
        }
        if ((v & 2) != 0) {
            drawPrimitiveEllipse(gra, cx + bodyW / 2, cy + bodyH / 7, bodyW / 2, bodyH / 5, 2, 3, 6, 238);
        }
        drawPrimitiveEllipse(gra, cx - bodyW / 5, cy - bodyH / 4, Math.max(8, bodyW / 10),
                Math.max(10, bodyH / 10), 255, 255, 255, 44);
    }

    private static void drawPrimitiveEllipse(FakeGraphics gra, int cx, int cy, int w, int h,
                                             int r, int g, int b, int alpha) {
        if (w <= 0 || h <= 0 || alpha <= 0) return;
        int rx = Math.max(1, w / 2);
        int ry = Math.max(1, h / 2);
        int step = Math.max(2, Math.min(rx, ry) / 18);
        for (int dy = -ry; dy <= ry; dy += step) {
            float yy = dy / (float) ry;
            int span = Math.round(rx * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
            gra.colRect(cx - span, cy + dy, span * 2 + 1, step + 1, r, g, b, alpha);
        }
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
            case 'J': return new String[]{"00111","00010","00010","00010","00010","10010","01100"};
            case 'K': return new String[]{"10001","10010","10100","11000","10100","10010","10001"};
            case 'L': return new String[]{"10000","10000","10000","10000","10000","10000","11111"};
            case 'M': return new String[]{"10001","11011","10101","10101","10001","10001","10001"};
            case 'N': return new String[]{"10001","11001","10101","10011","10001","10001","10001"};
            case 'O': return new String[]{"01110","10001","10001","10001","10001","10001","01110"};
            case 'P': return new String[]{"11110","10001","10001","11110","10000","10000","10000"};
            case 'Q': return new String[]{"01110","10001","10001","10001","10101","10010","01101"};
            case 'R': return new String[]{"11110","10001","10001","11110","10100","10010","10001"};
            case 'S': return new String[]{"01111","10000","10000","01110","00001","00001","11110"};
            case 'T': return new String[]{"11111","00100","00100","00100","00100","00100","00100"};
            case 'U': return new String[]{"10001","10001","10001","10001","10001","10001","01110"};
            case 'V': return new String[]{"10001","10001","10001","10001","10001","01010","00100"};
            case 'W': return new String[]{"10001","10001","10001","10101","10101","10101","01010"};
            case 'X': return new String[]{"10001","10001","01010","00100","01010","10001","10001"};
            case 'Y': return new String[]{"10001","10001","01010","00100","00100","00100","00100"};
            case 'Z': return new String[]{"11111","00001","00010","00100","01000","10000","11111"};
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
            case '>': return new String[]{"10000","01000","00100","00010","00100","01000","10000"};
            case '/': return new String[]{"00001","00010","00010","00100","01000","01000","10000"};
            case '%': return new String[]{"11001","11010","00010","00100","01000","01011","10011"};
            case '$': return new String[]{"00100","01111","10100","01110","00101","11110","00100"};
            case ',': return new String[]{"00000","00000","00000","00000","00000","00100","01000"};
            case '.': return new String[]{"00000","00000","00000","00000","00000","01100","01100"};
            case ':': return new String[]{"00000","01100","01100","00000","01100","01100","00000"};
            case '(': return new String[]{"00010","00100","01000","01000","01000","00100","00010"};
            case ')': return new String[]{"01000","00100","00010","00010","00010","00100","01000"};
            default: return new String[]{"11111","00001","00010","00100","00100","00000","00100"};
        }
    }

    private static void drawUpgradeRow(CrazyRuntime.StageRuntime rt, Graphics2D g, int w, int h, int x, int y,
                                       int stat, boolean enabled) {
        State st = rt.eggPet;
        int[] nextLevels = st.upgrades.clone();
        nextLevels[stat]++;
        StatBlock cur = computeStats(rt, st.current, st.upgrades);
        StatBlock next = computeStats(rt, st.current, nextLevels);
        String current = statValue(cur, stat);
        String nextValue = statValue(next, stat);
        String delta = statDelta(cur, next, stat);
        boolean flash = st.flashStat == stat && st.flashFrame > 0;

        if (flash) {
            float p = st.flashFrame / (float) FLASH_FRAMES;
            g.setColor(new Color(54, 210, 116, Math.round(65f * p)));
            g.fillRoundRect(x - 10, y - 22, Math.round(w * 0.47f), 36, 8, 8);
        }
        drawSmallText(g, STAT_NAMES[stat], x, y, 17, new Color(245, 246, 248));
        drawSmallText(g, "Lv " + st.upgrades[stat], x + 132, y, 16, new Color(212, 216, 224));
        drawSmallText(g, upgradeCost(st.upgrades[stat]) + "$", x + 204, y, 16, enabled ? new Color(255, 238, 140) : new Color(140, 140, 140));
        drawSmallText(g, current + " -> " + nextValue, x + 286, y, 16, new Color(235, 238, 243));
        drawSmallText(g, delta, x + 472, y, 16, new Color(58, 224, 118));
        drawButton(g, upgradeButton(w, h, stat), "+", enabled, true);
    }

    private static void drawHeader(Graphics2D g, int x, int y) {
        drawSmallText(g, "Stat", x, y, 14, new Color(166, 174, 188));
        drawSmallText(g, "Level", x + 132, y, 14, new Color(166, 174, 188));
        drawSmallText(g, "Cost", x + 204, y, 14, new Color(166, 174, 188));
        drawSmallText(g, "Current -> Next", x + 286, y, 14, new Color(166, 174, 188));
        drawSmallText(g, "Gain", x + 472, y, 14, new Color(166, 174, 188));
    }

    private static boolean drawUnitEggAt(CrazyRuntime.StageRuntime rt, FakeGraphics gra, int cx, int cy, int size, float opacity) {
        if (rt == null || gra == null || size <= 0) return false;
        State st = rt.eggPet;
        EAnimU anim = eggIconAnim(st);
        if (anim == null) return drawUnitEggImageAt(st, gra, cx, cy, size, opacity);
        FakeTransform old = pushIdentityTransform(gra);
        P p = null;
        try {
            int alpha = clampInt(Math.round(255f * clamp01(opacity)), 0, 255);
            try { gra.setComposite(FakeGraphics.TRANS, alpha, 0); } catch (Throwable ignored) {}
            p = P.newP(cx + size * EGG_ANIM_ANCHOR_X, cy + size * EGG_ANIM_ANCHOR_Y);
            anim.draw(gra, p, Math.max(0.18f, size / 128f));
            if (anim.done()) anim.setTime(0f);
            else anim.update(true);
            return true;
        } catch (Throwable t) {
            if (!st.eggIconWarningLogged) {
                st.eggIconWarningLogged = true;
                Logger.err("Egg Pet unit 757 icon draw failed", t);
            }
            return drawUnitEggImageAt(st, gra, cx, cy, size, opacity);
        } finally {
            if (p != null) {
                try { P.delete(p); } catch (Throwable ignored) {}
            }
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static boolean drawUnitEggImageAt(State st, FakeGraphics gra, int cx, int cy, int size, float opacity) {
        if (st == null || gra == null || size <= 0) return false;
        FakeImage img = st.eggIconImage;
        if (img == null && st.eggIconForm != null) {
            img = playerIcon(st.eggIconForm);
            st.eggIconImage = img;
        }
        if (img == null) return false;
        FakeTransform old = pushIdentityTransform(gra);
        try {
            int alpha = clampInt(Math.round(255f * clamp01(opacity)), 0, 255);
            try { gra.setComposite(FakeGraphics.TRANS, alpha, 0); } catch (Throwable ignored) {}
            float s = size * 1.08f;
            gra.drawImage(img, cx - s * 0.5f, cy - s * 0.5f, s, s);
            return true;
        } catch (Throwable t) {
            if (!st.eggIconWarningLogged) {
                st.eggIconWarningLogged = true;
                Logger.err("Egg Pet unit 757 icon image draw failed", t);
            }
            return false;
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static EAnimU eggIconAnim(State st) {
        if (st == null || st.eggIconForm == null) return null;
        if (st.eggIconAnim != null) return st.eggIconAnim;
        try {
            EAnimU anim = st.eggIconForm.getEAnim(AnimU.UType.IDLE);
            if (anim != null) {
                anim.setTime(0f);
                st.eggIconAnim = anim;
                Logger.log("Egg Pet using unit " + EGG_ICON_UNIT_ID + " idle animation for icon");
            }
        } catch (Throwable t) {
            if (!st.eggIconWarningLogged) {
                st.eggIconWarningLogged = true;
                Logger.err("Egg Pet unit 757 icon animation load failed", t);
            }
        }
        return st.eggIconAnim;
    }

    private static void drawEggAt(CrazyRuntime.StageRuntime rt, FakeGraphics gra, int cx, int cy, int size, boolean active, float opacity) {
        if (drawUnitEggAt(rt, gra, cx, cy, size, opacity)) return;
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) {
            int a = Math.round(255f * clamp01(opacity));
            gra.colRect(cx - size / 3, cy - size / 2, size * 2 / 3, size, active ? 255 : 120, active ? 235 : 120, active ? 170 : 120, a);
            return;
        }
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color old = g.getColor();
        Stroke os = g.getStroke();
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(opacity)));
            int w = Math.round(size * 0.74f);
            int h = Math.round(size * 0.98f);
            int x = cx - w / 2;
            int y = cy - h / 2;
            g.setColor(new Color(0, 0, 0, 70));
            g.fillOval(x + 4, y + 7, w, h);
            GradientPaint gp = new GradientPaint(x, y, active ? new Color(255, 248, 204) : new Color(150, 150, 150),
                    x + w, y + h, active ? new Color(214, 130, 73) : new Color(95, 95, 95));
            g.setPaint(gp);
            g.fillOval(x, y, w, h);
            g.setPaint(null);
            g.setStroke(new BasicStroke(Math.max(2f, size * 0.055f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(active ? new Color(112, 68, 44) : new Color(92, 92, 92));
            g.drawOval(x, y, w, h);
            g.setColor(new Color(255, 255, 255, active ? 95 : 45));
            g.fillOval(x + w / 4, y + h / 7, Math.max(5, w / 4), Math.max(8, h / 5));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(15, size / 3)));
            String s = "E";
            FontMetrics fm = g.getFontMetrics();
            g.setColor(new Color(50, 32, 22, active ? 210 : 130));
            g.drawString(s, cx - fm.stringWidth(s) / 2, cy + fm.getAscent() / 2 - 4);
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(old);
            g.setStroke(os);
            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }
    }

    private static void drawSilhouette(Graphics2D g, int cx, int cy, int w, int h, int seed) {
        AffineTransform ot = g.getTransform();
        java.awt.Composite oc = g.getComposite();
        Color old = g.getColor();
        try {
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(new Color(2, 3, 6, 235));
            int bodyW = Math.max(40, w);
            int bodyH = Math.max(54, h);
            g.fillOval(cx - bodyW / 2, cy - bodyH / 2, bodyW, bodyH);
            int headW = Math.round(bodyW * 0.58f);
            int headH = Math.round(bodyH * 0.42f);
            g.fillOval(cx - headW / 2, cy - bodyH / 2 - headH / 4, headW, headH);
            int v = Math.abs(seed);
            if ((v & 1) == 0) {
                Path2D ear = new Path2D.Float();
                ear.moveTo(cx - headW * 0.28f, cy - bodyH * 0.48f);
                ear.lineTo(cx - headW * 0.50f, cy - bodyH * 0.76f);
                ear.lineTo(cx - headW * 0.08f, cy - bodyH * 0.57f);
                ear.moveTo(cx + headW * 0.28f, cy - bodyH * 0.48f);
                ear.lineTo(cx + headW * 0.50f, cy - bodyH * 0.76f);
                ear.lineTo(cx + headW * 0.08f, cy - bodyH * 0.57f);
                g.fill(ear);
            }
            if ((v & 2) != 0) {
                g.fillOval(cx + bodyW / 4, cy + bodyH / 8, bodyW / 2, bodyH / 5);
            }
            g.setComposite(AlphaComposite.SrcOver.derive(0.22f));
            g.setColor(Color.WHITE);
            g.fillOval(cx - bodyW / 5, cy - bodyH / 3, bodyW / 8, bodyH / 9);
        } finally {
            g.setTransform(ot);
            g.setComposite(oc);
            g.setColor(old);
        }
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

    private static void drawMetric(Graphics2D g, String label, String value, int x, int y) {
        drawSmallText(g, label, x, y, 16, new Color(176, 184, 198));
        drawSmallText(g, value, x + 142, y, 17, new Color(245, 247, 250));
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

    private static StatBlock computeStats(CrazyRuntime.StageRuntime rt, Candidate c, int[] upgrades) {
        StatBlock out = new StatBlock();
        out.hp = 1;
        out.attack = 1;
        out.speed = 1;
        out.tba = 1;
        out.attackFrames = 1;
        out.scalePct = Math.round(totalScale(upgrades) * 100f);
        if (rt == null || c == null) return out;
        try {
            StageBasis sb = (StageBasis) rt.stage;
            Entity e = c.enemy ? sampleEnemy(sb, c) : samplePlayer(sb, c, upgrades[U_LEVEL]);
            if (e == null) return out;
            int hpExtra = upgrades[U_HP] + (c.enemy ? upgrades[U_LEVEL] : 0);
            int atkExtra = upgrades[U_ATTACK] + (c.enemy ? upgrades[U_LEVEL] : 0);
            out.hp = scaleLong(Math.max(1L, e.maxH), hpExtra);
            out.attack = scaleLong(Math.max(1L, totalAttack(e)), atkExtra);
            int baseSpeed = Math.max(1, e.data.getSpeed());
            int baseTba = Math.max(1, e.data.getTBA());
            int baseAnim = Math.max(1, animFrames(e.data));
            out.speed = scaledSpeed(baseSpeed, upgrades[U_SPEED]);
            out.tba = reduceFrames(baseTba, upgrades[U_TBA]);
            out.attackFrames = reduceFrames(baseAnim, upgrades[U_ATTACK_SPEED]);
        } catch (Throwable t) {
            Logger.err("Egg Pet stat preview failed", t);
        }
        return out;
    }

    private static EUnit samplePlayer(StageBasis sb, Candidate c, int levelUpgrade) {
        if (c.form == null) return null;
        EForm ef = new EForm(c.form, levelWithUpgrade(c.baseLevel, levelUpgrade));
        return ef.getEntity(sb, null, false, false);
    }

    private static EEnemy sampleEnemy(StageBasis sb, Candidate c) {
        if (c.enemyUnit == null) return null;
        return c.enemyUnit.getEntity(sb, null, 1f, 1f, 0, 9, 0, 0);
    }

    private static String statValue(StatBlock s, int stat) {
        switch (stat) {
            case U_LEVEL:
                return format(s.hp) + " / " + format(s.attack);
            case U_HP:
                return format(s.hp);
            case U_ATTACK:
                return format(s.attack);
            case U_SPEED:
                return formatSpeed(s.speed);
            case U_TBA:
                return s.tba + "f";
            case U_ATTACK_SPEED:
                return s.attackFrames + "f";
            default:
                return "";
        }
    }

    private static String statDelta(StatBlock cur, StatBlock next, int stat) {
        switch (stat) {
            case U_LEVEL:
                return "+" + format(next.hp - cur.hp) + " HP, +" + format(next.attack - cur.attack) + " ATK";
            case U_HP:
                return "+" + format(next.hp - cur.hp);
            case U_ATTACK:
                return "+" + format(next.attack - cur.attack);
            case U_SPEED:
                return formatSpeedDelta(next.speed - cur.speed);
            case U_TBA:
                return "-" + Math.max(0, cur.tba - next.tba) + "f";
            case U_ATTACK_SPEED:
                return "-" + Math.max(0, cur.attackFrames - next.attackFrames) + "f";
            default:
                return "";
        }
    }

    private static void ensureInitialized(CrazyRuntime.StageRuntime rt) {
        State st = rt.eggPet;
        if (st.initialized) return;
        synchronized (st.lock) {
            if (st.initialized) return;
            long start = System.currentTimeMillis();
            CrazyPreloadService.Snapshot snap = CrazyPreloadService.snapshotOrBuildSync("egg-pet-stage");
            st.pool.clear();
            if (snap != null) {
                IconForm preferredEggIcon = null;
                for (int i = 0; i < snap.officialPlayerForms.size(); i++) {
                    CrazyPreloadService.PlayerFormEntry e = snap.officialPlayerForms.get(i);
                    FakeImage icon = playerIcon(e.form);
                    st.pool.add(new Candidate(e.form, e.levelCloneOrDefault(), e.key, e.will, e.rarity, icon));
                    preferredEggIcon = betterEggIcon(preferredEggIcon, e.form, "snapshot:" + e.key);
                }
                IconForm profileEggIcon = findEggIconFromProfile();
                if (profileEggIcon != null && (preferredEggIcon == null
                        || eggIconMatchRank(profileEggIcon.form) <= eggIconMatchRank(preferredEggIcon.form))) {
                    preferredEggIcon = profileEggIcon;
                }
                if (preferredEggIcon != null) {
                    st.eggIconForm = preferredEggIcon.form;
                    st.eggIconSource = preferredEggIcon.source;
                    st.eggIconImage = playerIcon(preferredEggIcon.form);
                }
                ArrayList<EnemyScore> enemies = new ArrayList<EnemyScore>();
                for (int i = 0; i < snap.officialEnemies.size(); i++) enemies.add(new EnemyScore(snap.officialEnemies.get(i)));
                Collections.sort(enemies, new Comparator<EnemyScore>() {
                    @Override
                    public int compare(EnemyScore a, EnemyScore b) {
                        return Integer.compare(a.score, b.score);
                    }
                });
                int n = Math.max(1, enemies.size());
                for (int i = 0; i < enemies.size(); i++) {
                    CrazyPreloadService.EnemyEntry e = enemies.get(i).entry;
                    int tier = Math.min(5, i * 6 / n);
                    st.pool.add(new Candidate(e.enemy, e.key, e.will, tier, enemyIcon(e.enemy)));
                }
            }
            st.initialized = true;
            Logger.log("Egg Pet stage init time=" + (System.currentTimeMillis() - start)
                    + "ms candidates=" + st.pool.size()
                    + " eggIcon757=" + (st.eggIconForm == null ? "missing" : ("form" + st.eggIconForm.fid))
                    + " source=" + (st.eggIconSource == null ? "none" : st.eggIconSource));
        }
    }

    private static Candidate randomCandidate(CrazyRuntime.StageRuntime rt, String avoidKey) {
        State st = rt.eggPet;
        if (st.pool.isEmpty()) {
            if (!st.poolWarningLogged) {
                st.poolWarningLogged = true;
                Logger.log("Egg Pet has no official candidates");
            }
            return null;
        }
        StageBasis sb = (StageBasis) rt.stage;
        for (int tries = 0; tries < 16; tries++) {
            Candidate c = st.pool.get(randInt(sb, st.pool.size()));
            if (c != null && (avoidKey == null || st.pool.size() <= 1 || !avoidKey.equals(c.key))) return c;
        }
        return st.pool.get(randInt(sb, st.pool.size()));
    }

    private static IconForm betterEggIcon(IconForm current, Form form, String source) {
        int rank = eggIconMatchRank(form);
        if (rank < 0) return current;
        if (current == null) return new IconForm(form, source);
        int oldRank = eggIconMatchRank(current.form);
        if (rank < oldRank) return new IconForm(form, source);
        if (rank == oldRank && current.form != null && current.form.fid != 0 && form != null && form.fid == 0) {
            return new IconForm(form, source);
        }
        return current;
    }

    private static int eggIconMatchRank(Form form) {
        try {
            if (form == null || form.unit == null || form.unit.id == null
                    || !OFFICIAL_PACK.equals(form.unit.id.pack)) return -1;
            int id = form.unit.id.id;
            if (id == EGG_ICON_UNIT_ID) return 0;
            if (id == EGG_ICON_ALT_UNIT_ID) return 1;
            return -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static IconForm findEggIconFromProfile() {
        try {
            List<Unit> units = UserProfile.getBCData().units.getList();
            IconForm best = null;
            if (units != null) {
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    if (u == null || u.forms == null) continue;
                    for (int f = 0; f < u.forms.length; f++) {
                        Form form = u.forms[f];
                        best = betterEggIcon(best, form, "profile-id:" + unitKey(form));
                    }
                }
                best = betterEggIcon(best, formAtUnitIndex(units, EGG_ICON_UNIT_ID),
                        "profile-index:" + EGG_ICON_UNIT_ID);
                best = betterEggIcon(best, formAtUnitIndex(units, EGG_ICON_ALT_UNIT_ID),
                        "profile-index:" + EGG_ICON_ALT_UNIT_ID);
                if (best == null) {
                    Form byIndex = formAtUnitIndex(units, EGG_ICON_UNIT_ID);
                    if (byIndex == null) byIndex = formAtUnitIndex(units, EGG_ICON_ALT_UNIT_ID);
                    if (byIndex != null) best = new IconForm(byIndex, "profile-index-fallback:" + unitKey(byIndex));
                }
            }
            return best;
        } catch (Throwable t) {
            Logger.err("Egg Pet unit 757 profile lookup failed", t);
            return null;
        }
    }

    private static Form formAtUnitIndex(List<Unit> units, int index) {
        try {
            if (units == null || index < 0 || index >= units.size()) return null;
            Unit u = units.get(index);
            if (u == null || u.forms == null || u.forms.length == 0) return null;
            if (u.forms[0] != null) return u.forms[0];
            for (int i = 1; i < u.forms.length; i++) if (u.forms[i] != null) return u.forms[i];
        } catch (Throwable ignored) {}
        return null;
    }

    private static String unitKey(Form form) {
        try {
            if (form == null || form.unit == null || form.unit.id == null) return "null";
            return form.unit.id.pack + ":" + form.unit.id.id + ":" + form.fid;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Level levelWithUpgrade(Level base, int upgrade) {
        Level lv;
        try {
            lv = base == null ? new Level(30) : base.clone();
        } catch (Throwable ignored) {
            lv = new Level(30);
        }
        try {
            lv.setLevel(Math.max(1, lv.getLv() + Math.max(0, upgrade)));
            if (lv.getOrbs() == null) lv.setOrbs(new int[0][]);
        } catch (Throwable ignored) {}
        return lv;
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

    private static int reduceFrames(int base, int level) {
        if (base <= 1) return 1;
        float factor = Math.max(0f, 1f - Math.max(0, level) * 0.01f);
        return Math.max(1, Math.round(base * factor));
    }

    private static float scaledSpeed(float base, int level) {
        float safeBase = Math.max(1f, base);
        int safeLevel = Math.max(0, level);
        if (safeLevel == 0) return safeBase;
        float bonusPerLevel = Math.max(safeBase * 0.01f, 0.1f);
        return Math.max(1f, safeBase + bonusPerLevel * safeLevel);
    }

    private static float speedMultiplier(float base, int level) {
        float safeBase = Math.max(1f, base);
        return Math.max(1f, scaledSpeed(safeBase, level) / safeBase);
    }

    private static String formatSpeed(float value) {
        return formatDecimal(value);
    }

    private static String formatSpeedDelta(float value) {
        return (value >= 0f ? "+" : "-") + formatDecimal(Math.abs(value));
    }

    private static String formatDecimal(float value) {
        if (!finite(value)) return "0";
        long hundredths = Math.round(Math.abs(value) * 100f);
        String sign = value < 0f ? "-" : "";
        long whole = hundredths / 100L;
        long frac = hundredths % 100L;
        if (frac == 0L) return sign + whole;
        if (frac % 10L == 0L) return sign + whole + "." + (frac / 10L);
        return sign + whole + "." + (frac < 10L ? "0" : "") + frac;
    }

    private static long scaleLong(long base, int percent) {
        double v = (double) Math.max(1L, base) * (1.0d + Math.max(0, percent) * 0.01d);
        if (v > Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, Math.round(v));
    }

    private static float totalScale(State st) {
        return totalScale(st.upgrades);
    }

    private static float totalScale(int[] upgrades) {
        int sum = 0;
        for (int i = 0; i < upgrades.length; i++) sum += Math.max(0, upgrades[i]);
        return safeScale(1f + sum * 0.01f);
    }

    private static float emergeScaleFactor(State st) {
        if (st == null) return 1f;
        float p = clamp01(st.emergeFrame / (float) EMERGE_FRAMES);
        float eased = 1f - (1f - p) * (1f - p);
        return 0.22f + 0.78f * eased;
    }

    private static int upgradeCost(int level) {
        long cost = (long) (Math.max(0, level) + 1) * 100L;
        return cost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cost;
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

    private static float hatchSpawnPos(StageBasis sb) {
        try {
            return sb.st.len - 700f;
        } catch (Throwable ignored) {
            return sb.ubase == null ? 0f : sb.ubase.pos - 120f;
        }
    }

    private static BaseDropArea baseDropAreaFromPainter(Object bbpainter) {
        if (bbpainter == null) return null;
        try {
            StageBasis sb = (StageBasis) BBPainterAccess.getStageBasis(bbpainter);
            return baseDropArea(sb, BBPainterAccess.getSiz(bbpainter),
                    BBPainterAccess.getStagePos(bbpainter), BBPainterAccess.getMidh(bbpainter));
        } catch (Throwable t) {
            Logger.err("Egg Pet painter base area failed", t);
            return null;
        }
    }

    private static BaseDropArea baseDropAreaFromPage(Object page) {
        if (page == null) return null;
        try {
            Object basis = BCUFields.get(page, "basis");
            StageBasis sb = (StageBasis) BCUFields.get(basis, "sb");
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            return baseDropArea(sb, BCUFields.getFloat(sb, "siz"), BCUFields.getInt(sb, "pos"),
                    BCUFields.getInt(bbp, "midh"));
        } catch (Throwable first) {
            try {
                Object bb = BCUFields.get(page, "bb");
                Object bbp = BCUFields.get(bb, "bbp");
                Object bf = BCUFields.get(bbp, "bf");
                StageBasis sb = (StageBasis) BCUFields.get(bf, "sb");
                return baseDropArea(sb, BCUFields.getFloat(sb, "siz"), BCUFields.getInt(sb, "pos"),
                        BCUFields.getInt(bbp, "midh"));
            } catch (Throwable second) {
                Logger.err("Egg Pet page base area failed", second);
                return null;
            }
        }
    }

    private static BaseDropArea baseDropArea(StageBasis sb, float siz, int sbPos, int midh) {
        if (sb == null || sb.ubase == null) return null;
        Object base = sb.ubase;
        float s = Math.max(0.5f, siz);
        float rootX = (EntityAccess.getPos(base) * 0.32f + 200f) * siz + sbPos;
        int layer = 0;
        try { layer = EntityAccess.getLayer(base); } catch (Throwable ignored) {}
        float rootY = midh - (156 - layer * 4) * siz;
        if (!finite(rootX)) rootX = 0f;
        if (!finite(rootY)) rootY = midh - 120f * s;

        EntityAccess.SpriteBounds sprite = null;
        try { sprite = EntityAccess.estimateSpriteBounds(base, siz, rootX, rootY); }
        catch (Throwable ignored) {}

        int doorX = Math.round(rootX + 54f * s);
        int doorY = Math.round(rootY + 8f * s);
        int halfW = Math.max(24, Math.round(34f * s));
        int halfTop = Math.max(28, Math.round(38f * s));
        int halfBottom = Math.max(22, Math.round(30f * s));
        int left = doorX - halfW;
        int top = doorY - halfTop;
        int right = doorX + halfW;
        int bottom = doorY + halfBottom;
        return new BaseDropArea(base, rootX, rootY, siz, layer, Math.round(left), Math.round(top),
                Math.round(right), Math.round(bottom), doorX, doorY, sprite);
    }

    private static boolean validSpriteBounds(EntityAccess.SpriteBounds b) {
        return b != null && finite(b.left) && finite(b.top) && finite(b.right) && finite(b.bottom)
                && b.right > b.left && b.bottom > b.top
                && b.right - b.left < 5000f && b.bottom - b.top < 5000f;
    }

    private static float[] baseDoorPoint(Object page) {
        try {
            BaseDropArea area = baseDropAreaFromPage(page);
            if (area != null) return new float[]{area.doorX, area.doorY, area.layer};
        } catch (Throwable t) {
        }
        return new float[]{900f, 520f, 0f};
    }

    private static boolean isOverPlayerBase(Object page, int cursorX, int cursorY) {
        try {
            BaseDropArea area = baseDropAreaFromPage(page);
            if (area == null) return false;
            logBaseDropCheck(area, cursorX, cursorY);
            return area.contains(cursorX, cursorY);
        } catch (Throwable t) {
            Logger.err("Egg Pet base drop check failed", t);
            return false;
        }
    }

    private static void logBaseDropCheck(BaseDropArea area, int cursorX, int cursorY) {
        if (area == null) {
            Logger.log("Egg Pet base drop check cursor=(" + cursorX + "," + cursorY + ") area=null");
            return;
        }
        boolean broadHit = area.broadHit(cursorX, cursorY);
        Logger.log("Egg Pet base drop check cursor=(" + cursorX + "," + cursorY + ") root=("
                + Math.round(area.rootX) + "," + Math.round(area.rootY) + ") door=("
                + area.doorX + "," + area.doorY + ") siz=" + area.siz
                + " area=(" + area.left + "," + area.top + ")-(" + area.right + "," + area.bottom + ")"
                + " base=" + (area.base == null ? "null" : area.base.getClass().getSimpleName())
                + " doorHit=" + broadHit);
    }

    private static boolean enabled(CrazyRuntime.StageRuntime rt) {
        return rt != null && rt.config.eggPet;
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
        int margin = Math.max(12, Math.round(h * 0.018f));
        int gap = Math.max(8, Math.round(size * 0.18f));
        int bossSlotX = w - size - margin;
        int x = bossSlotX - (size + gap) * 2;
        if (x < margin) x = margin;
        int y = Math.max(58, Math.round(h * 0.095f));
        return new Rect(x, y, size, size);
    }

    private static Rect overlayPanel(int w, int h) {
        int pw = clampInt(Math.round(w * 0.70f), 720, Math.max(720, w - 80));
        int ph = clampInt(Math.round(h * 0.68f), 430, Math.max(430, h - 80));
        return new Rect((w - pw) / 2, (h - ph) / 2, pw, ph);
    }

    private static Rect offerButton(Object page, int index) {
        try {
            Object bb = BCUFields.get(page, "bb");
            int w = ((Number) BCUFields.invoke(bb, "getWidth")).intValue();
            int h = ((Number) BCUFields.invoke(bb, "getHeight")).intValue();
            return offerButton(w, h, index);
        } catch (Throwable ignored) {
            return offerButton(1200, 800, index);
        }
    }

    private static Rect offerButton(int w, int h, int index) {
        Rect p = overlayPanel(w, h);
        int bw = Math.max(112, p.w / 7);
        int bh = 42;
        int gap = 18;
        int total = bw * 3 + gap * 2;
        int x = p.x + (p.w - total) / 2 + index * (bw + gap);
        int y = p.y + p.h - 74;
        return new Rect(x, y, bw, bh);
    }

    private static Rect upgradeButton(Object page, int stat) {
        try {
            Object bb = BCUFields.get(page, "bb");
            int w = ((Number) BCUFields.invoke(bb, "getWidth")).intValue();
            int h = ((Number) BCUFields.invoke(bb, "getHeight")).intValue();
            return upgradeButton(w, h, stat);
        } catch (Throwable ignored) {
            return upgradeButton(1200, 800, stat);
        }
    }

    private static Rect upgradeButton(int w, int h, int stat) {
        Rect p = overlayPanel(w, h);
        int x = p.x + p.w - 220;
        int y = p.y + 82 + 30 + stat * 58 - 18;
        return new Rect(x, y, 38, 32);
    }

    private static Rect upgradeCloseButton(Object page) {
        try {
            Object bb = BCUFields.get(page, "bb");
            int w = ((Number) BCUFields.invoke(bb, "getWidth")).intValue();
            int h = ((Number) BCUFields.invoke(bb, "getHeight")).intValue();
            return upgradeCloseButton(w, h);
        } catch (Throwable ignored) {
            return upgradeCloseButton(1200, 800);
        }
    }

    private static Rect upgradeCloseButton(int w, int h) {
        Rect p = overlayPanel(w, h);
        return new Rect(p.x + p.w - 132, p.y + p.h - 64, 104, 38);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FakeImage buildFakeImage(BufferedImage image) {
        try {
            if (ImageBuilder.builder == null || image == null) return null;
            return ((ImageBuilder) ImageBuilder.builder).build(image);
        } catch (Throwable t) {
            Logger.err("Egg Pet BufferedImage conversion failed", t);
            return null;
        }
    }

    private static final class DrawState {
        AffineTransform transform;
        java.awt.Composite composite;
        Color color;
        Stroke stroke;
        Font font;
        Object aa;
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

    private static PetStats petStats(Object entity) {
        if (!(entity instanceof Entity)) return null;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return null;
        return rt.eggPet.pets.get(entity);
    }

    private static boolean touchable(Entity e, int touch) {
        try {
            return (e.touchable() & touch) > 0;
        } catch (Throwable ignored) {
            return (touch & 1) != 0;
        }
    }

    private static float scaledHitboxExtra(Entity e, float scale) {
        float width = 120f;
        try { width = Math.max(40f, e.data.getWidth()); } catch (Throwable ignored) {}
        return width * 0.5f * Math.max(0f, scale - 1f);
    }

    private static void beginReturn(State st, int x, int y) {
        st.dragging = false;
        st.returning = true;
        st.returnFrame = 0;
        st.returnFromX = x;
        st.returnFromY = y;
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

    private static void play(int id) {
        try { CommonStatic.setSE(id); } catch (Throwable ignored) {}
    }

    private static void reject() {
        play(15);
    }

    private static String format(long v) {
        return String.format(java.util.Locale.US, "%,d", v);
    }

    private static int pageSafeWidth(int w) { return w; }
    private static int pageSafeHeight(int h) { return h; }

    private static float safeScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return 1f;
        return Math.max(1f, Math.min(Float.MAX_VALUE / 4f, scale));
    }

    private static float safeVisualScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return 1f;
        return Math.max(0.05f, Math.min(Float.MAX_VALUE / 4f, scale));
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
