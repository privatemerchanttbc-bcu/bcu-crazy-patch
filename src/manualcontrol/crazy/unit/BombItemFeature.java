package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.entity.AbEntity;
import common.battle.entity.EAnimCont;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.anim.EAnimD;
import common.util.pack.EffAnim;
import manualcontrol.ConvertedRegistry;
import manualcontrol.FallingRegistry;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.fall.ImpactFallFeature;
import manualcontrol.hooks.BoundsRecorder;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.imageio.ImageIO;

public final class BombItemFeature {

    private static final int FPS = 60;
    private static final int COUNTDOWN_FRAMES = 3 * FPS;
    private static final int ZERO_HOLD_FRAMES = FPS;
    private static final int FUSE_FRAMES = COUNTDOWN_FRAMES + ZERO_HOLD_FRAMES;
    private static final int RETURN_FRAMES = 18;
    private static final float MAX_CARRIER_SCALE = 1.7f;
    private static final int CENTER_DAMAGE = 1000000;
    private static final int OUTER_DAMAGE = 100000;
    private static final int BASE_DAMAGE = 500000;
    private static final int MIN_RADIUS_PX = 240;
    private static final int MAX_RADIUS_PX = 760;
    private static final float RADIUS_SCALE = 4.0f;
    private static final float EXPLOSION_VISUAL_WIDTH_SCALE = 1.30f;
    private static final float OUTER_DAMAGE_RADIUS_SCALE = 1.50f;
    private static final int ALPHA_THRESHOLD = 18;
    private static final long NO_POINT = Long.MIN_VALUE;
    private static final int EXPLOSION_SE = 167;
    private static final int VISUAL_FRAMES = 46;
    private static final int LAUNCH_FRAMES = 42;
    private static final int CARRIER_FALL_MAX_FRAMES = 180;
    private static final float CARRIER_FALL_INITIAL_VY = -48f;
    private static final float CARRIER_FALL_GRAVITY = 1.20f;
    private static final int CRACK_BRANCHES = 7;
    private static final int CRACK_CLUSTERS = 3;
    private static final float CRACK_START_PROGRESS = 0.04f;
    private static final float CRACK_GLOW_START_PROGRESS = 0.06f;
    private static final int CRACK_GLOW_SPRITE_SIZE = 96;
    private static final String BOMB_ICON_RESOURCE = "/manualcontrol/crazy/unit/suicide_bomb_icon.png";
    private static final String EXPLOSION_SPRITE_RESOURCE = "/manualcontrol/crazy/unit/bomb_explosion_sprite.png";
    private static final int[] EXPLOSION_KEYFRAMES = new int[] {
            0, 1, 3, 4, 6, 7, 9, 10, 12, 13, 15, 16,
            18, 19, 21, 22, 24, 25, 27, 28, 30, 31, 33, 34
    };
    private static final int[][] EXPLOSION_CUTS = new int[][] {
            {15, 763, 75, 61}, {92, 747, 80, 77}, {174, 742, 86, 82},
            {262, 736, 85, 88}, {350, 716, 88, 108}, {440, 716, 94, 109},
            {15, 830, 102, 114}, {119, 830, 105, 114}, {225, 829, 109, 116},
            {336, 827, 108, 116}, {445, 826, 109, 119}, {15, 951, 111, 124},
            {126, 950, 113, 123}, {240, 951, 114, 123}, {355, 950, 108, 114},
            {464, 948, 107, 112}, {15, 1080, 108, 111}, {125, 1079, 108, 111},
            {234, 1077, 106, 113}, {343, 1076, 102, 110}, {446, 1075, 108, 115},
            {15, 1194, 69, 66}, {85, 1194, 68, 51}, {154, 1195, 73, 47}
    };
    private static final float COUNTDOWN_SCREEN_OFFSET_X = -0.040f;
    private static final float COUNTDOWN_SCREEN_OFFSET_Y = -0.242f;
    private static final float COUNTDOWN_SCREEN_WIDTH = 0.34f;
    private static final float COUNTDOWN_SCREEN_HEIGHT = 0.18f;
    private static final float COUNTDOWN_SCREEN_ROTATION = (float) Math.toRadians(-9.0);
    private static final int COUNTDOWN_SPRITE_W = 192;
    private static final int COUNTDOWN_SPRITE_H = 104;
    private static final int CLOCK_ASSET_W = 894;
    private static final int CLOCK_ASSET_H = 950;
    private static final int CLOCK_PATCH_W = 360;
    private static final int CLOCK_PATCH_H = 220;
    private static final float CLOCK_TL_X = 324f;
    private static final float CLOCK_TL_Y = 222f;
    private static final float CLOCK_TR_X = 465f;
    private static final float CLOCK_TR_Y = 183f;
    private static final float CLOCK_BR_X = 521f;
    private static final float CLOCK_BR_Y = 245f;
    private static final float CLOCK_BL_X = 376f;
    private static final float CLOCK_BL_Y = 313f;
    private static final int DIGIT_PATCH_W = 220;
    private static final int DIGIT_PATCH_H = 150;
    private static final float DIGIT_TL_X = 338f;
    private static final float DIGIT_TL_Y = 240f;
    private static final float DIGIT_TR_X = 435f;
    private static final float DIGIT_TR_Y = 194f;
    private static final float DIGIT_BR_X = 480f;
    private static final float DIGIT_BR_Y = 246f;
    private static final float DIGIT_BL_X = 381f;
    private static final float DIGIT_BL_Y = 295f;
    private static final float DIGIT_WARP_SCALE = 1.34f;
    private static volatile BufferedImage bombIconImage;
    private static volatile boolean bombIconLoadAttempted;
    private static volatile FakeImage bombIconFakeImage;
    private static volatile boolean bombIconFakeLoadAttempted;
    private static volatile BufferedImage explosionSpriteImage;
    private static volatile boolean explosionSpriteLoadAttempted;
    private static volatile FakeImage explosionSpriteFakeImage;
    private static volatile boolean explosionSpriteFakeLoadAttempted;
    private static final Object EXPLOSION_FRAME_CACHE_LOCK = new Object();
    private static final FakeImage[] explosionFrameFakeImages = new FakeImage[EXPLOSION_CUTS.length];
    private static final WeakHashMap<FakeImage, AlphaBounds> alphaBoundsCache =
            new WeakHashMap<FakeImage, AlphaBounds>();
    private static final Object COUNTDOWN_CACHE_LOCK = new Object();
    private static final Object CRACK_GLOW_CACHE_LOCK = new Object();
    private static final BufferedImage[] countdownScreenImages = new BufferedImage[10];
    private static final FakeImage[] countdownScreenFakeImages = new FakeImage[10];
    private static final BufferedImage[] bombCountdownImages = new BufferedImage[10];
    private static final FakeImage[] bombCountdownFakeImages = new FakeImage[10];
    private static volatile FakeImage crackGlowFakeImage;

    private BombItemFeature() {}

    public static final class State {
        public boolean initialized;
        public int remaining;

        public boolean dragging;
        public int dragX;
        public int dragY;

        public boolean returning;
        public int returnFrame;
        public int returnFromX;
        public int returnFromY;

        public float lastSiz = 1f;
        public int lastStagePos;
        public int lastMidh = 500;
        public boolean haveTransform;

        public final List<ArmedBomb> armed = new ArrayList<ArmedBomb>();
        public final List<ExplosionVisual> visuals = new ArrayList<ExplosionVisual>();
        public final List<LaunchJob> launches = new ArrayList<LaunchJob>();
        public final List<CarrierFallJob> carrierFalls = new ArrayList<CarrierFallJob>();
        public final Map<Object, SpriteBox> spriteBoxes = new WeakHashMap<Object, SpriteBox>();
    }

    private static final class ArmedBomb {
        final Entity entity;
        final int seed;
        final float fixedPos;
        final int fixedLayer;
        float lastPos;
        int lastLayer;
        float lastBodyX;
        float lastBodyY;
        float lastSpriteRadiusPx = 90f;
        final float attachOffsetX;
        final float attachOffsetY;
        final float attachRotation;
        final float attachSizeScale;
        int frame;

        ArmedBomb(Entity entity, int seed) {
            this.entity = entity;
            this.seed = seed;
            this.fixedPos = entity.pos;
            this.fixedLayer = safeLayer(entity);
            this.lastPos = this.fixedPos;
            this.lastLayer = this.fixedLayer;
            this.attachOffsetX = (rand01(seed + 17) - 0.5f) * 0.54f;
            this.attachOffsetY = (rand01(seed + 29) - 0.5f) * 0.42f - 0.04f;
            this.attachRotation = (rand01(seed + 43) - 0.5f) * (float) Math.toRadians(58.0);
            this.attachSizeScale = 0.92f + rand01(seed + 61) * 0.18f;
        }
    }

    private static final class ExplosionVisual {
        final float pos;
        final int layer;
        final float radiusWorld;
        final int seed;
        int age;

        ExplosionVisual(float pos, int layer, float radiusWorld, int seed) {
            this.pos = pos;
            this.layer = layer;
            this.radiusWorld = radiusWorld;
            this.seed = seed;
        }

        boolean done() {
            return age > VISUAL_FRAMES;
        }
    }

    private static final class LaunchJob {
        final Entity entity;
        final int origLayer;
        final float groundY;
        final float siz;
        final int stagePos;
        final int midh;
        float screenX;
        float screenY;
        float vx;
        float vy;
        int age;

        LaunchJob(Entity entity, int origLayer, float screenX, float screenY,
                  float vx, float vy, float groundY, float siz, int stagePos, int midh) {
            this.entity = entity;
            this.origLayer = origLayer;
            this.screenX = screenX;
            this.screenY = screenY;
            this.vx = vx;
            this.vy = vy;
            this.groundY = groundY;
            this.siz = siz;
            this.stagePos = stagePos;
            this.midh = midh;
        }
    }

    private static final class CarrierFallJob {
        final Entity entity;
        final int origLayer;
        final float groundY;
        final float siz;
        final int stagePos;
        final int midh;
        float screenX;
        float screenY;
        float vy;
        float highestY;
        int age;

        CarrierFallJob(Entity entity, int origLayer, float screenX, float screenY,
                       float groundY, float siz, int stagePos, int midh) {
            this.entity = entity;
            this.origLayer = origLayer;
            this.screenX = screenX;
            this.screenY = screenY;
            this.groundY = groundY;
            this.siz = siz;
            this.stagePos = stagePos;
            this.midh = midh;
            this.vy = CARRIER_FALL_INITIAL_VY;
            this.highestY = screenY;
        }
    }

    private static final class SpriteBox {
        float minX;
        float minY;
        float maxX;
        float maxY;
        float bodyCX;
        float bodyCY;
        long timeMs;
        final List<SpriteAlphaPart> parts = new ArrayList<SpriteAlphaPart>();

        void set(float minX, float minY, float maxX, float maxY, float bodyCX, float bodyCY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.bodyCX = bodyCX;
            this.bodyCY = bodyCY;
            this.timeMs = System.currentTimeMillis();
        }

        boolean fresh() {
            float w = maxX - minX;
            float h = maxY - minY;
            return System.currentTimeMillis() - timeMs <= 160L
                    && w > 2f && h > 2f && w < 6000f && h < 6000f;
        }

        float radius() {
            return Math.max(30f, Math.max(maxX - minX, maxY - minY) * 0.5f);
        }
    }

    private static final class AlphaBounds {
        final int minX;
        final int minY;
        final int maxX;
        final int maxY;
        final boolean valid;

        AlphaBounds(int minX, int minY, int maxX, int maxY, boolean valid) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.valid = valid;
        }
    }

    private static final class SpriteAlphaPart {
        final FakeImage image;
        final AlphaBounds alpha;
        final float drawX;
        final float drawY;
        final float drawW;
        final float drawH;
        final float[] matrix;

        SpriteAlphaPart(FakeImage image, AlphaBounds alpha, float drawX, float drawY,
                        float drawW, float drawH, float[] matrix) {
            this.image = image;
            this.alpha = alpha;
            this.drawX = drawX;
            this.drawY = drawY;
            this.drawW = drawW;
            this.drawH = drawH;
            this.matrix = matrix == null ? null : matrix.clone();
        }
    }

    private static final class ScreenComposite implements Composite {
        private final float alpha;

        ScreenComposite(float alpha) {
            this.alpha = clamp01(alpha);
        }

        @Override
        public CompositeContext createContext(ColorModel srcColorModel, ColorModel dstColorModel, RenderingHints hints) {
            return new ScreenCompositeContext(alpha);
        }
    }

    private static final class ScreenCompositeContext implements CompositeContext {
        private final float alpha;

        ScreenCompositeContext(float alpha) {
            this.alpha = alpha;
        }

        @Override
        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            int w = Math.min(src.getWidth(), dstIn.getWidth());
            int h = Math.min(src.getHeight(), dstIn.getHeight());
            int srcBands = src.getNumBands();
            int dstBands = dstIn.getNumBands();
            int outBands = dstOut.getNumBands();
            int[] s = new int[Math.max(4, srcBands)];
            int[] d = new int[Math.max(4, dstBands)];
            int[] o = new int[Math.max(4, outBands)];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    src.getPixel(x, y, s);
                    dstIn.getPixel(x, y, d);
                    int sa = srcBands > 3 ? s[3] : 255;
                    sa = clampInt(Math.round(sa * alpha), 0, 255);
                    float a = sa / 255f;
                    for (int c = 0; c < Math.min(3, outBands); c++) {
                        int sc = c < srcBands ? s[c] : 0;
                        int dc = c < dstBands ? d[c] : 0;
                        int screened = 255 - (255 - dc) * (255 - sc) / 255;
                        o[c] = clampInt(Math.round(dc + (screened - dc) * a), 0, 255);
                    }
                    if (outBands > 3) {
                        int da = dstBands > 3 ? d[3] : 255;
                        o[3] = clampInt(da + sa * (255 - da) / 255, 0, 255);
                    }
                    dstOut.setPixel(x, y, o);
                }
            }
        }

        @Override
        public void dispose() {}
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
        if (page == null || e == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.bombItem;
            ensureInitialized(rt);
            if (st.remaining <= 0) return false;
            Rect icon = iconRectFromPage(page);
            if (!icon.contains(e.getX(), e.getY())) return false;
            st.dragging = true;
            st.returning = false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            Logger.log("Bomb Item drag started; remaining=" + st.remaining);
            return true;
        } catch (Throwable t) {
            Logger.err("Bomb Item mousePressed failed", t);
            return false;
        }
    }

    public static boolean onMouseDragged(Object page, MouseEvent e) {
        if (page == null || e == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.bombItem;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            return true;
        } catch (Throwable t) {
            Logger.err("Bomb Item mouseDragged failed", t);
            return false;
        }
    }

    public static boolean onMouseReleased(Object page, MouseEvent e) {
        if (page == null || e == null) return false;
        try {
            if (!isBattleCanvasEvent(page, e)) return false;
            CrazyRuntime.StageRuntime rt = CrazyRuntime.runtimeFromPage(page);
            if (!enabled(rt)) return false;
            State st = rt.bombItem;
            if (!st.dragging) return false;
            st.dragX = e.getX();
            st.dragY = e.getY();
            st.dragging = false;
            Entity target = findTargetUnderCursor(page, e.getX(), e.getY());
            if (target != null && arm(rt, target)) {
                return true;
            }
            beginReturn(st, e.getX(), e.getY());
            Logger.log("Bomb Item returned: no valid target");
            return true;
        } catch (Throwable t) {
            Logger.err("Bomb Item mouseReleased failed", t);
            return false;
        }
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (!enabled(rt)) return;
        ensureInitialized(rt);
        State st = rt.bombItem;
        if (st.returning) {
            st.returnFrame++;
            if (st.returnFrame >= RETURN_FRAMES) {
                st.returning = false;
                st.returnFrame = 0;
            }
        }
        tickLaunches(rt);
        tickCarrierFalls(rt);
        tickVisuals(st);
        if (st.armed.isEmpty()) return;
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = st.armed.size() - 1; i >= 0; i--) {
            ArmedBomb bomb = st.armed.get(i);
            if (bomb == null || bomb.entity == null || !sb.le.contains(bomb.entity)) {
                explode(rt, bomb);
                st.armed.remove(i);
                continue;
            }
            Entity e = bomb.entity;
            trackCarrier(bomb);
            if (e.dead || e.health <= 0L) {
                explode(rt, bomb);
                st.armed.remove(i);
                continue;
            }
            if (bomb.frame >= FUSE_FRAMES) {
                explode(rt, bomb);
                st.armed.remove(i);
                continue;
            }
            bomb.frame++;
        }
    }

    public static void drawUnder(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (!enabled(rt) || bbpainter == null || gra == null) return;
        State st = rt.bombItem;
        updateTransform(st, bbpainter);
        for (int i = 0; i < st.visuals.size(); i++) {
            ExplosionVisual v = st.visuals.get(i);
            if (v == null) continue;
            float x = CrazyRender.screenX(bbpainter, v.pos);
            float y = CrazyRender.groundY(bbpainter, v.layer);
            float r = Math.max(12f, v.radiusWorld * 0.32f * BBPainterAccess.getSiz(bbpainter));
            drawGroundShockwave(gra, x, y, r, v);
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (!enabled(rt) || bbpainter == null || gra == null) return;
        ensureInitialized(rt);
        State st = rt.bombItem;
        updateTransform(st, bbpainter);
        Rect icon = iconRectFromPainter(bbpainter);
        boolean usable = st.remaining > 0;
        drawIcon(gra, icon.cx(), icon.cy(), icon.w, usable, st.remaining, 1f);
        if (st.returning) {
            float p = clamp01(st.returnFrame / (float) RETURN_FRAMES);
            float eased = 1f - (1f - p) * (1f - p);
            int x = Math.round(lerp(st.returnFromX, icon.cx(), eased));
            int y = Math.round(lerp(st.returnFromY, icon.cy(), eased));
            drawIcon(gra, x, y, icon.w, true, st.remaining, 0.82f, false);
        }
        if (st.dragging) {
            drawIcon(gra, st.dragX, st.dragY, icon.w, true, st.remaining, 0.9f, false);
        }
        for (int i = 0; i < st.visuals.size(); i++) {
            ExplosionVisual v = st.visuals.get(i);
            if (v == null) continue;
            float x = CrazyRender.screenX(bbpainter, v.pos);
            float y = CrazyRender.groundY(bbpainter, v.layer);
            float r = Math.max(12f, v.radiusWorld * 0.32f * BBPainterAccess.getSiz(bbpainter));
            drawExplosionOverlay(gra, x, y, r, v);
        }
    }

    public static boolean wantsSpriteBounds(Object entity) {
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt)) return false;
        return armedBomb(rt, entity) != null;
    }

    public static void recordSpriteBounds(Object entity, float minX, float minY, float maxX, float maxY,
                                          float bodyCX, float bodyCY) {
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt)) return;
        State st = rt.bombItem;
        SpriteBox box = st.spriteBoxes.get(entity);
        if (box == null) {
            box = new SpriteBox();
            st.spriteBoxes.put(entity, box);
        }
        box.set(minX, minY, maxX, maxY, bodyCX, bodyCY);
        ArmedBomb bomb = armedBomb(rt, entity);
        if (bomb != null) {
            bomb.lastBodyX = bodyCX;
            bomb.lastBodyY = bodyCY;
            bomb.lastSpriteRadiusPx = box.radius();
        }
    }

    public static void recordSpriteParts(Object entity, List<BoundsRecorder.SpritePart> parts) {
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt) || entity == null) return;
        State st = rt.bombItem;
        SpriteBox box = st.spriteBoxes.get(entity);
        if (box == null) {
            box = new SpriteBox();
            st.spriteBoxes.put(entity, box);
        }
        box.parts.clear();
        if (parts == null || parts.isEmpty()) return;
        int limit = Math.min(64, parts.size());
        for (int i = 0; i < limit; i++) {
            BoundsRecorder.SpritePart p = parts.get(i);
            if (p == null || p.image == null || p.matrix == null || p.w == 0f || p.h == 0f) continue;
            AlphaBounds ab = alphaBounds(p.image);
            if (ab == null || !ab.valid) continue;
            box.parts.add(new SpriteAlphaPart(p.image, ab, p.x, p.y, p.w, p.h, p.matrix));
        }
        box.timeMs = System.currentTimeMillis();
    }

    public static void drawAttachedForEntity(Object entity, FakeGraphics gra) {
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt) || entity == null || gra == null) return;
        ArmedBomb bomb = armedBomb(rt, entity);
        if (bomb == null) return;
        drawAttachedBomb(bomb, gra);
    }

    public static float drawScaleFor(Object entity) {
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt)) return 1f;
        ArmedBomb bomb = armedBomb(rt, entity);
        if (bomb == null) return 1f;
        float p = swellProgress(bomb);
        if (p <= 0f) return 1f;
        float eased = p * p * (3f - 2f * p);
        float pulse = (float) Math.sin(bomb.frame * 0.48f) * 0.018f * p;
        return Math.max(1f, Math.min(MAX_CARRIER_SCALE, 1f + (MAX_CARRIER_SCALE - 1f) * eased + pulse));
    }

    private static float swellProgress(ArmedBomb bomb) {
        if (bomb == null) return 0f;
        int swellStart = Math.max(0, FUSE_FRAMES - ZERO_HOLD_FRAMES);
        if (bomb.frame < swellStart) return 0f;
        return clamp01((bomb.frame - swellStart) / (float) ZERO_HOLD_FRAMES);
    }

    private static void drawSwellCracks(ArmedBomb bomb, FakeGraphics gra) {
        float p = swellProgress(bomb);
        if (p <= CRACK_START_PROGRESS || bomb == null || bomb.entity == null || gra == null) return;
        SpriteBox box = runtimeBox(bomb.entity);
        if (box == null || !box.fresh() || box.parts.isEmpty()) return;
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g != null) {
            drawSwellCracks(g, box, bomb, p);
        } else {
            drawSwellCracksFallback(gra, box, bomb, p);
        }
    }

    private static void drawSwellCracks(Graphics2D g, SpriteBox box, ArmedBomb bomb,
                                        float p) {
        if (g == null || box == null || bomb == null) return;
        float reveal = clamp01((p - CRACK_START_PROGRESS) / (1f - CRACK_START_PROGRESS));
        if (reveal <= 0f) return;
        float glow = crackGlowProgress(p, reveal, bomb.frame);
        float radius = clamp(box.radius(), 28f, 360f);

        AffineTransform oldTx = g.getTransform();
        java.awt.Composite oldComp = g.getComposite();
        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int cluster = 0; cluster < CRACK_CLUSTERS; cluster++) {
                long origin = crackOriginInSpritePart(box, bomb, cluster);
                if (origin == NO_POINT) continue;
                float cx = unpackX(origin);
                float cy = unpackY(origin);
                int branchCount = cluster == 0 ? CRACK_BRANCHES : Math.max(4, CRACK_BRANCHES - 2);
                float clusterScale = cluster == 0 ? 1f : 0.70f;
                for (int i = 0; i < branchCount; i++) {
                    float base = (float) (Math.PI * 2.0 * i / branchCount);
                    int branchSeed = bomb.seed + cluster * 919 + i * 173;
                    float angle = base + (rand01(branchSeed + 97) - 0.5f) * 0.92f;
                    float len = radius * clusterScale * (0.20f + 0.34f * rand01(branchSeed + 131)) * reveal;
                    drawCrackBranch(g, box, branchSeed, cx, cy, angle, len, radius, reveal, glow);
                }
            }
        } finally {
            g.setTransform(oldTx);
            g.setComposite(oldComp);
            g.setStroke(oldStroke);
            g.setColor(oldColor);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    private static float crackGlowProgress(float p, float reveal, int frame) {
        float ramp = clamp01((p - CRACK_GLOW_START_PROGRESS) / (1f - CRACK_GLOW_START_PROGRESS));
        float pulse = 0.74f + 0.26f * (float) Math.sin(frame * 0.68f);
        float glow = Math.max(reveal * 0.58f, ramp);
        return clamp01(glow * (0.94f + 0.24f * pulse));
    }

    private static void drawCrackBranch(Graphics2D g, SpriteBox box, int seed, float cx, float cy,
                                        float angle, float length, float radius, float reveal, float glow) {
        if (length < 4f) return;
        int segments = 5;
        float x = cx;
        float y = cy;
        float a = angle;
        for (int s = 0; s < segments; s++) {
            float t = (s + 1f) / segments;
            a += (rand01(seed + s * 31) - 0.5f) * 0.70f;
            float step = length / segments * (0.78f + rand01(seed + s * 43) * 0.44f);
            float nx = x + (float) Math.cos(a) * step;
            float ny = y + (float) Math.sin(a) * step * 0.86f;
            float width = Math.max(1.0f, radius * 0.013f * (1.05f - t * 0.54f));
            float dark = clamp01(0.28f + 0.72f * reveal);
            drawMaskedCrackLine(g, box, x, y, nx, ny, width, dark, glow);

            if (glow > 0.06f && s >= 1 && s <= 3) {
                float side = rand01(seed + s * 71) > 0.5f ? 1f : -1f;
                float rayA = a + side * (0.82f + rand01(seed + s * 83) * 0.56f);
                float rayLen = radius * (0.08f + rand01(seed + s * 89) * 0.10f) * glow;
                drawMaskedCrackLine(g, box,
                        (x + nx) * 0.5f, (y + ny) * 0.5f,
                        (x + nx) * 0.5f + (float) Math.cos(rayA) * rayLen,
                        (y + ny) * 0.5f + (float) Math.sin(rayA) * rayLen * 0.86f,
                        Math.max(0.8f, width * 0.55f), 0f, glow * 0.78f);
            }

            if (reveal > 0.42f && s == 2) {
                float side = rand01(seed + 251) > 0.5f ? 1f : -1f;
                float ba = a + side * (0.70f + rand01(seed + 257) * 0.54f);
                float bl = length * (0.20f + rand01(seed + 263) * 0.20f) * reveal;
                drawMaskedCrackLine(g, box, nx, ny,
                        nx + (float) Math.cos(ba) * bl,
                        ny + (float) Math.sin(ba) * bl * 0.86f,
                        Math.max(0.8f, width * 0.72f),
                        clamp01(0.22f + 0.58f * reveal), glow * 0.72f);
            }
            x = nx;
            y = ny;
        }
    }

    private static void drawMaskedCrackLine(Graphics2D g, SpriteBox box,
                                            float x0, float y0, float x1, float y1,
                                            float width, float darkAlpha, float glowAlpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = Math.max(3, Math.min(44, Math.round(dist / 4.5f)));
        boolean open = false;
        float px = 0f;
        float py = 0f;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float x = x0 + dx * t;
            float y = y0 + dy * t;
            if (visiblePointOnSprite(box, x, y)) {
                if (open) drawCrackSegment(g, px, py, x, y, width, darkAlpha, glowAlpha);
                open = true;
                px = x;
                py = y;
            } else {
                open = false;
            }
        }
    }

    private static void drawCrackSegment(Graphics2D g, float x0, float y0, float x1, float y1,
                                         float width, float darkAlpha, float glowAlpha) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);

        if (darkAlpha > 0.001f) {
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(0.74f * darkAlpha)));
            g.setStroke(new BasicStroke(Math.max(1.1f, width * 2.2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(10, 8, 7));
            g.draw(path);
        }
        if (glowAlpha > 0.001f) {
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(glowAlpha * 0.34f)));
            g.setStroke(new BasicStroke(Math.max(2f, width * 11.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 98, 22));
            g.draw(path);
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(glowAlpha * 0.54f)));
            g.setStroke(new BasicStroke(Math.max(1.4f, width * 6.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(34, 236, 255));
            g.draw(path);
            g.setComposite(new ScreenComposite(clamp01(glowAlpha * 0.48f)));
            g.setStroke(new BasicStroke(Math.max(1f, width * 3.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(96, 255, 255));
            g.draw(path);
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(glowAlpha)));
            g.setStroke(new BasicStroke(Math.max(1f, width * 1.75f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(242, 255, 238));
            g.draw(path);
        }
    }

    private static void drawSwellCracksFallback(FakeGraphics gra, SpriteBox box, ArmedBomb bomb,
                                                float p) {
        float reveal = clamp01((p - CRACK_START_PROGRESS) / (1f - CRACK_START_PROGRESS));
        if (reveal <= 0f) return;
        float glow = crackGlowProgress(p, reveal, bomb.frame);
        float radius = clamp(box.radius(), 28f, 360f);
        try {
            for (int cluster = 0; cluster < CRACK_CLUSTERS; cluster++) {
                long origin = crackOriginInSpritePart(box, bomb, cluster);
                if (origin == NO_POINT) continue;
                float cx = unpackX(origin);
                float cy = unpackY(origin);
                int branchCount = cluster == 0 ? CRACK_BRANCHES : Math.max(4, CRACK_BRANCHES - 2);
                float clusterScale = cluster == 0 ? 1f : 0.70f;
                for (int i = 0; i < branchCount; i++) {
                    int branchSeed = bomb.seed + cluster * 919 + i * 173;
                    float base = (float) (Math.PI * 2.0 * i / branchCount);
                    float a = base + (rand01(branchSeed + 97) - 0.5f) * 0.92f;
                    float len = radius * clusterScale * (0.18f + 0.28f * rand01(branchSeed + 131)) * reveal;
                    float x = cx;
                    float y = cy;
                    int segments = 5;
                    for (int s = 0; s < segments; s++) {
                        float t = (s + 1f) / segments;
                        a += (rand01(branchSeed + s * 31) - 0.5f) * 0.70f;
                        float step = len / segments * (0.78f + rand01(branchSeed + s * 43) * 0.44f);
                        float nx = x + (float) Math.cos(a) * step;
                        float ny = y + (float) Math.sin(a) * step * 0.86f;
                        float width = Math.max(1.0f, radius * 0.013f * (1.05f - t * 0.54f));
                        float dark = clamp01(0.28f + 0.72f * reveal);
                        drawMaskedFallbackLine(gra, box, x, y, nx, ny, width, dark, glow);

                        if (glow > 0.06f && s >= 1 && s <= 3) {
                            float side = rand01(branchSeed + s * 71) > 0.5f ? 1f : -1f;
                            float rayA = a + side * (0.82f + rand01(branchSeed + s * 83) * 0.56f);
                            float rayLen = radius * (0.08f + rand01(branchSeed + s * 89) * 0.10f) * glow;
                            drawMaskedFallbackLine(gra, box,
                                    (x + nx) * 0.5f, (y + ny) * 0.5f,
                                    (x + nx) * 0.5f + (float) Math.cos(rayA) * rayLen,
                                    (y + ny) * 0.5f + (float) Math.sin(rayA) * rayLen * 0.86f,
                                    Math.max(0.8f, width * 0.55f), 0f, glow * 0.78f);
                        }

                        if (reveal > 0.42f && s == 2) {
                            float side = rand01(branchSeed + 251) > 0.5f ? 1f : -1f;
                            float ba = a + side * (0.70f + rand01(branchSeed + 257) * 0.54f);
                            float bl = len * (0.20f + rand01(branchSeed + 263) * 0.20f) * reveal;
                            drawMaskedFallbackLine(gra, box, nx, ny,
                                    nx + (float) Math.cos(ba) * bl,
                                    ny + (float) Math.sin(ba) * bl * 0.86f,
                                    Math.max(0.8f, width * 0.72f),
                                    clamp01(0.22f + 0.58f * reveal), glow * 0.72f);
                        }
                        x = nx;
                        y = ny;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.err("Bomb Item crack fallback draw failed", t);
        } finally {
            resetComposite(gra);
        }
    }

    private static void drawMaskedFallbackLine(FakeGraphics gra, SpriteBox box,
                                               float x0, float y0, float x1, float y1,
                                               float width, float darkAlpha, float glowAlpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = Math.max(3, Math.min(36, Math.round(dist / 4.5f)));
        boolean open = false;
        float px = 0f;
        float py = 0f;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float x = x0 + dx * t;
            float y = y0 + dy * t;
            if (visiblePointOnSprite(box, x, y)) {
                if (open) {
                    drawFallbackCrackSegment(gra, px, py, x, y, width, darkAlpha, glowAlpha);
                } else {
                    drawFallbackCrackPoint(gra, x, y, width, darkAlpha, glowAlpha);
                }
                open = true;
                px = x;
                py = y;
            } else {
                open = false;
            }
        }
    }

    private static void drawFallbackCrackSegment(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                                 float width, float darkAlpha, float glowAlpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = Math.max(1, Math.min(6, Math.round(dist / 3.5f)));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            drawFallbackCrackPoint(gra, x0 + dx * t, y0 + dy * t, width, darkAlpha, glowAlpha);
        }
    }

    private static void drawFallbackCrackPoint(FakeGraphics gra, float x, float y,
                                               float width, float darkAlpha, float glowAlpha) {
        int ix = Math.round(x);
        int iy = Math.round(y);
        if (glowAlpha > 0.001f) {
            int glowR = Math.max(5, Math.round(width * 10.0f));
            drawFallbackGlowSprite(gra, ix, iy, glowR, glowAlpha);
            int orangeA = clampInt(Math.round(118f * glowAlpha), 0, 220);
            int cyanA = clampInt(Math.round(168f * glowAlpha), 0, 235);
            int whiteA = clampInt(Math.round(255f * glowAlpha), 0, 255);
            fillDisc(gra, ix, iy, Math.max(3, Math.round(width * 7.6f)), 255, 98, 22, orangeA);
            fillDisc(gra, ix, iy, Math.max(2, Math.round(width * 4.4f)), 34, 236, 255, cyanA);
            fillDisc(gra, ix, iy, Math.max(1, Math.round(width * 1.55f)), 246, 255, 236, whiteA);
        }
        if (darkAlpha > 0.001f) {
            int darkA = clampInt(Math.round(220f * darkAlpha), 0, 255);
            fillDisc(gra, ix, iy, Math.max(1, Math.round(width * 1.65f)), 6, 4, 3, darkA);
            if (glowAlpha > 0.001f) {
                fillDisc(gra, ix, iy, Math.max(1, Math.round(width * 0.82f)),
                        246, 255, 236, clampInt(Math.round(255f * glowAlpha), 0, 255));
            }
        }
    }

    private static void drawFallbackGlowSprite(FakeGraphics gra, int cx, int cy, int radius,
                                               float glowAlpha) {
        FakeImage img = crackGlowFakeImage();
        int alpha = clampInt(Math.round(230f * glowAlpha), 0, 255);
        if (gra == null || img == null || radius <= 1 || alpha <= 0) return;
        try {
            gra.setComposite(FakeGraphics.TRANS, alpha, 0);
            gra.drawImage(img, cx - radius, cy - radius, radius * 2f, radius * 2f);
        } catch (Throwable ignored) {
        } finally {
            resetComposite(gra);
        }
    }

    public static boolean isManaged(Object entity) {
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt) || entity == null) return false;
        State st = rt.bombItem;
        for (int i = 0; i < st.launches.size(); i++) {
            LaunchJob job = st.launches.get(i);
            if (job != null && job.entity == entity) return true;
        }
        for (int i = 0; i < st.carrierFalls.size(); i++) {
            CarrierFallJob job = st.carrierFalls.get(i);
            if (job != null && job.entity == entity) return true;
        }
        return false;
    }

    private static boolean arm(CrazyRuntime.StageRuntime rt, Entity target) {
        if (!enabled(rt) || target == null) return false;
        ensureInitialized(rt);
        State st = rt.bombItem;
        if (st.remaining <= 0 || !validTarget(rt, target)) return false;
        ArmedBomb bomb = new ArmedBomb(target, stableSeed(target, st.remaining + st.armed.size() * 31));
        st.armed.add(bomb);
        st.remaining = Math.max(0, st.remaining - 1);
        Logger.log("Bomb armed target=" + target.getClass().getSimpleName()
                + " remaining=" + st.remaining);
        return true;
    }

    private static void explode(CrazyRuntime.StageRuntime rt, ArmedBomb bomb) {
        if (!enabled(rt) || bomb == null) return;
        State st = rt.bombItem;
        StageBasis sb = (StageBasis) rt.stage;
        float siz = st.haveTransform ? st.lastSiz : readStageSiz(sb);
        if (siz < 0.001f) siz = 1f;
        float spriteRadius = Math.max(60f, bomb.lastSpriteRadiusPx);
        float radiusPx = clamp(spriteRadius * RADIUS_SCALE, MIN_RADIUS_PX, MAX_RADIUS_PX);
        float coreDamageRadiusPx = explosionAnimationRadiusPx(radiusPx);
        float outerDamageRadiusPx = coreDamageRadiusPx * OUTER_DAMAGE_RADIUS_SCALE;
        float radiusWorld = radiusPx / Math.max(0.001f, 0.32f * siz);
        float pos = finite(bomb.lastPos) ? bomb.lastPos : bomb.fixedPos;
        int layer = bomb.lastLayer;
        st.spriteBoxes.remove(bomb.entity);
        st.visuals.add(new ExplosionVisual(pos, layer, radiusWorld, bomb.seed));
        spawnNativeShockwave(sb, pos, layer);
        applyCameraShake(sb, radiusPx);
        playExplosionSound();

        int unitHits = 0;
        int unitKills = 0;
        int unitLaunches = 0;

        boolean carrierFall = startCarrierFall(rt, bomb, pos, layer);
        if (!carrierFall && bomb.entity != null && !bomb.entity.dead && bomb.entity.health > 0L) {
            if (applyBombUnitDamage(rt, bomb.entity, CENTER_DAMAGE, pos, true)) unitKills++;
            unitHits++;
        }

        List<Entity> targets = new ArrayList<Entity>(sb.le);
        for (int i = 0; i < targets.size(); i++) {
            Entity e = targets.get(i);
            if (e == null || e.isBase() || e.dead || e.health <= 0L) continue;
            if (carrierFall && e == bomb.entity) continue;
            float distPx = Math.abs(e.pos - pos) * 0.32f * siz;
            if (distPx > outerDamageRadiusPx) continue;
            int damage = unitExplosionDamage(distPx, coreDamageRadiusPx, outerDamageRadiusPx);
            if (damage <= 0) continue;
            boolean killed = applyBombUnitDamage(rt, e, damage, pos, false);
            unitHits++;
            if (killed) unitKills++;
            if (e != bomb.entity && e.health > 0L) {
                launch(rt, e, pos, distPx, outerDamageRadiusPx);
                unitLaunches++;
            }
        }
        int baseHits = 0;
        if (damageBase(sb.ubase, pos, siz, coreDamageRadiusPx)) baseHits++;
        if (damageBase(sb.ebase, pos, siz, coreDamageRadiusPx)) baseHits++;
        Logger.log("Bomb exploded pos=" + Math.round(pos)
                + " radiusPx=" + Math.round(radiusPx)
                + " coreDamagePx=" + Math.round(coreDamageRadiusPx)
                + " outerDamagePx=" + Math.round(outerDamageRadiusPx)
                + " radiusWorld=" + Math.round(radiusWorld)
                + " unitHits=" + unitHits
                + " unitKills=" + unitKills
                + " unitLaunches=" + unitLaunches
                + " baseHits=" + baseHits);
    }

    private static void tickLaunches(CrazyRuntime.StageRuntime rt) {
        State st = rt.bombItem;
        Iterator<LaunchJob> it = st.launches.iterator();
        while (it.hasNext()) {
            LaunchJob job = it.next();
            if (job == null || job.entity == null || job.entity.dead || job.entity.health <= 0L) {
                it.remove();
                continue;
            }
            job.age++;
            job.vy += 1.15f;
            job.vx *= 0.965f;
            job.screenX += job.vx;
            job.screenY += job.vy;
            if (job.age > 7 && (job.screenY >= job.groundY || job.age >= LAUNCH_FRAMES)) {
                applyScreen(job.entity, job.screenX, job.groundY, job.siz, job.stagePos, job.midh, job.origLayer);
                cleanupMotion(job.entity);
                it.remove();
            } else {
                int layer = (int) (((job.screenY - job.midh) / job.siz + 156f) / 4f);
                if (layer > job.origLayer) layer = job.origLayer;
                applyScreen(job.entity, job.screenX, job.screenY, job.siz, job.stagePos, job.midh, layer);
                cleanupMotion(job.entity);
            }
        }
    }

    private static boolean startCarrierFall(CrazyRuntime.StageRuntime rt, ArmedBomb bomb, float pos, int layer) {
        if (rt == null || bomb == null || bomb.entity == null) return false;
        Entity e = bomb.entity;
        if (e.dead || e.health <= 0L) return false;
        State st = rt.bombItem;
        if (!st.haveTransform || st.lastSiz < 0.001f) return false;
        float sx = screenX(pos, st.lastSiz, st.lastStagePos);
        float sy = groundY(layer, st.lastSiz, st.lastMidh);
        try {
            e.pos = pos;
            e.lastPosition = pos;
            if (e.health <= 0L) e.health = 1L;
            BCUFields.field(e.getClass(), "kbTime").setInt(e, 0);
            EntityAccess.setLayer(e, layer);
        } catch (Throwable ignored) {}
        st.carrierFalls.add(new CarrierFallJob(e, layer, sx, sy, sy,
                st.lastSiz, st.lastStagePos, st.lastMidh));
        Logger.log("Bomb carrier launched upward for impact fall: "
                + e.getClass().getSimpleName());
        return true;
    }

    private static void tickCarrierFalls(CrazyRuntime.StageRuntime rt) {
        State st = rt.bombItem;
        Iterator<CarrierFallJob> it = st.carrierFalls.iterator();
        while (it.hasNext()) {
            CarrierFallJob job = it.next();
            if (job == null || job.entity == null || job.entity.dead || job.entity.health <= 0L) {
                it.remove();
                continue;
            }
            job.age++;
            job.vy += CARRIER_FALL_GRAVITY;
            job.screenY += job.vy;
            if (job.screenY < job.highestY) job.highestY = job.screenY;
            float maxHeight = Math.max(0f, job.groundY - job.highestY);
            if (job.age > 8 && (job.screenY >= job.groundY || job.age >= CARRIER_FALL_MAX_FRAMES)) {
                job.screenY = job.groundY;
                float impactVy = Math.max(Math.abs(job.vy), 44f);
                boolean triggered = ImpactFallFeature.triggerLandingFromBomb(job.entity,
                        job.screenX, job.groundY, impactVy, Math.max(maxHeight, 720f),
                        job.origLayer, job.siz, job.stagePos, job.midh);
                if (!triggered) {
                    applyScreen(job.entity, job.screenX, job.groundY, job.siz, job.stagePos, job.midh, job.origLayer);
                    cleanupMotion(job.entity);
                    forceBombUnitDeath(null, job.entity, job.entity.pos);
                }
                it.remove();
            } else {
                int layer = (int) (((job.screenY - job.midh) / job.siz + 156f) / 4f);
                if (layer > job.origLayer) layer = job.origLayer;
                applyScreen(job.entity, job.screenX, job.screenY, job.siz, job.stagePos, job.midh, layer);
                cleanupMotion(job.entity);
            }
        }
    }

    private static void tickVisuals(State st) {
        Iterator<ExplosionVisual> it = st.visuals.iterator();
        while (it.hasNext()) {
            ExplosionVisual v = it.next();
            if (v == null) {
                it.remove();
                continue;
            }
            v.age++;
            if (v.done()) it.remove();
        }
    }

    private static void launch(CrazyRuntime.StageRuntime rt, Entity e, float centerPos,
                               float distPx, float radiusPx) {
        State st = rt.bombItem;
        if (!st.haveTransform || st.lastSiz < 0.001f) return;
        if (armedBomb(rt, e) != null) return;
        float sx = screenX(e.pos, st.lastSiz, st.lastStagePos);
        int layer = safeLayer(e);
        float sy = groundY(layer, st.lastSiz, st.lastMidh);
        float ground = sy;
        float falloff = clamp01(1f - distPx / Math.max(1f, radiusPx));
        float sign = e.pos >= centerPos ? 1f : -1f;
        float vx = sign * (9f + 24f * falloff);
        float vy = -(14f + 36f * falloff);
        st.launches.add(new LaunchJob(e, layer, sx, sy, vx, vy, ground,
                st.lastSiz, st.lastStagePos, st.lastMidh));
    }

    private static boolean damageBase(AbEntity base, float pos, float siz, float coreDamageRadiusPx) {
        if (base == null || base.health <= 0L) return false;
        float distPx = Math.abs(base.pos - pos) * 0.32f * siz;
        if (distPx > coreDamageRadiusPx) return false;
        base.health = Math.max(0L, base.health - BASE_DAMAGE);
        return true;
    }

    private static int unitExplosionDamage(float distPx, float coreDamageRadiusPx, float outerDamageRadiusPx) {
        if (distPx <= coreDamageRadiusPx) return CENTER_DAMAGE;
        if (distPx <= outerDamageRadiusPx) return OUTER_DAMAGE;
        return 0;
    }

    private static boolean applyBombUnitDamage(CrazyRuntime.StageRuntime rt, Entity e,
                                               int damage, float blastPos,
                                               boolean carrierFallback) {
        if (e == null || damage <= 0 || e.dead || e.health <= 0L) return false;
        long before = e.health;
        e.damageTaken = safeAdd(e.damageTaken, damage);
        e.health = Math.max(0L, before - damage);
        showBombHitFeedback(e);
        if (e.health > 0L) return false;
        float deathPos = carrierFallback && finite(blastPos) ? blastPos : e.pos;
        forceBombUnitDeath(rt, e, deathPos);
        if (carrierFallback) {
            Logger.log("Bomb carrier forced death after fall launch failed: "
                    + e.getClass().getSimpleName());
        }
        return true;
    }

    private static void forceBombUnitDeath(CrazyRuntime.StageRuntime rt, Entity e, float pos) {
        if (e == null) return;
        try { e.health = 0L; } catch (Throwable ignored) {}
        try {
            e.pos = pos;
            e.lastPosition = pos;
        } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "lastPosition").setFloat(e, pos); } catch (Throwable ignored) {}
        try { cleanupMotion(e); } catch (Throwable ignored) {}
        removeBombLaunchJobs(rt, e);
        try {
            e.kill(Entity.KillMode.SELF_DESTRUCT);
        } catch (Throwable t) {
            try { BCUFields.field(e.getClass(), "kbTime").setInt(e, -1); } catch (Throwable ignored) {}
            Logger.err("Bomb Item forced unit death failed", t);
        }
    }

    private static void removeBombLaunchJobs(CrazyRuntime.StageRuntime rt, Entity e) {
        if (rt == null || rt.bombItem == null || e == null) return;
        Iterator<LaunchJob> it = rt.bombItem.launches.iterator();
        while (it.hasNext()) {
            LaunchJob job = it.next();
            if (job == null || job.entity == e) it.remove();
        }
    }

    private static void showBombHitFeedback(Entity e) {
        if (e == null) return;
        try { BCUFields.field(e.getClass(), "hit").setByte(e, (byte) 2); } catch (Throwable ignored) {}
    }

    private static long safeAdd(long base, long add) {
        if (add <= 0L) return base;
        if (base > Long.MAX_VALUE - add) return Long.MAX_VALUE;
        return base + add;
    }

    private static float explosionAnimationRadiusPx(float radiusPx) {
        return Math.max(1f, clamp(radiusPx * EXPLOSION_VISUAL_WIDTH_SCALE, 190f, 820f) * 0.5f);
    }

    private static void drawAttachedBomb(ArmedBomb bomb, FakeGraphics gra) {
        if (bomb == null || bomb.entity == null || gra == null) return;
        float cx;
        float cy;
        float radius;
        SpriteBox box = runtimeBox(bomb.entity);
        if (box != null && box.fresh()) {
            cx = box.bodyCX + (box.maxX - box.minX) * bomb.attachOffsetX;
            cy = box.bodyCY + (box.maxY - box.minY) * bomb.attachOffsetY;
            radius = box.radius();
        } else if (bomb.lastBodyX > 1f && bomb.lastBodyY > 1f) {
            cx = bomb.lastBodyX + bomb.lastSpriteRadiusPx * 1.45f * bomb.attachOffsetX;
            cy = bomb.lastBodyY + bomb.lastSpriteRadiusPx * 1.45f * bomb.attachOffsetY;
            radius = Math.max(45f, bomb.lastSpriteRadiusPx);
        } else {
            return;
        }
        int size = clampInt(Math.round(radius * 0.72f * bomb.attachSizeScale), 38, 92);
        drawAttachedDevice(gra, Math.round(cx), Math.round(cy), size, bomb.attachRotation, countdownText(bomb));
    }

    private static void drawGroundShockwave(FakeGraphics gra, float x, float y, float radius, ExplosionVisual v) {
        Graphics2D g = CrazyRender.unwrap(gra);
        float p = clamp01(v.age / (float) VISUAL_FRAMES);
        float ring = radius * (0.20f + 0.72f * p);
        float fade = 1f - p;
        if (g != null) {
            AffineTransform oldTx = g.getTransform();
            java.awt.Composite oldComp = g.getComposite();
            Stroke oldStroke = g.getStroke();
            Color oldColor = g.getColor();
            Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            try {
                g.setTransform(new AffineTransform());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setComposite(AlphaComposite.SrcOver.derive(clamp01(0.24f * fade)));
                g.setColor(new Color(48, 42, 36));
                g.fillOval(Math.round(x - ring), Math.round(y - ring * 0.23f),
                        Math.round(ring * 2f), Math.round(ring * 0.46f));
                g.setComposite(AlphaComposite.SrcOver.derive(clamp01(0.28f * fade)));
                g.setStroke(new BasicStroke(Math.max(2f, radius * 0.018f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(255, 207, 120));
                g.drawOval(Math.round(x - ring * 0.84f), Math.round(y - ring * 0.18f),
                        Math.round(ring * 1.68f), Math.round(ring * 0.36f));
            } finally {
                g.setTransform(oldTx);
                g.setComposite(oldComp);
                g.setStroke(oldStroke);
                g.setColor(oldColor);
                if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
            }
        } else {
            int alpha = Math.round(92f * fade);
            strokeEllipseFallback(gra, Math.round(x), Math.round(y), Math.round(ring),
                    Math.max(8, Math.round(ring * 0.18f)), 3, 255, 207, 120, alpha);
        }
    }

    private static void drawExplosionOverlay(FakeGraphics gra, float x, float y, float radius, ExplosionVisual v) {
        Graphics2D g = CrazyRender.unwrap(gra);
        boolean drawn = g != null
                ? drawExplosionSprite(g, x, y, radius, v)
                : drawExplosionSprite(gra, x, y, radius, v);
        if (drawn) return;

        float fade = 1f - clamp01(v.age / (float) VISUAL_FRAMES);
        float intro = clamp01(v.age / 10f);
        if (g != null) {
            AffineTransform oldTx = g.getTransform();
            java.awt.Composite oldComp = g.getComposite();
            Color oldColor = g.getColor();
            Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            try {
                g.setTransform(new AffineTransform());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float blast = radius * (0.16f + 0.44f * clamp01((v.age - 4f) / 20f));
                g.setComposite(AlphaComposite.SrcOver.derive(clamp01(0.82f * fade)));
                g.setColor(new Color(255, 116, 36));
                g.fillOval(Math.round(x - blast), Math.round(y - blast), Math.round(blast * 2f), Math.round(blast * 2f));
                g.setComposite(AlphaComposite.SrcOver.derive(clamp01(0.92f * fade)));
                g.setColor(new Color(255, 248, 210));
                g.fillOval(Math.round(x - blast * 0.44f), Math.round(y - blast * 0.46f),
                        Math.round(blast * 0.88f), Math.round(blast * 0.88f));
            } finally {
                g.setTransform(oldTx);
                g.setComposite(oldComp);
                g.setColor(oldColor);
                if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
            }
        } else {
            int alpha = Math.round(210f * fade);
            fillDisc(gra, Math.round(x), Math.round(y), Math.round(radius * (0.16f + 0.30f * intro)),
                    255, 116, 36, alpha);
        }
    }

    private static boolean drawExplosionSprite(Graphics2D g, float x, float y, float radius, ExplosionVisual v) {
        BufferedImage sprite = explosionSpriteImage();
        if (g == null || sprite == null) return false;
        int index = explosionFrameIndex(v.age);
        int[] cut = EXPLOSION_CUTS[index];
        float targetW = clamp(radius * EXPLOSION_VISUAL_WIDTH_SCALE, 190f, 820f);
        float scale = targetW / Math.max(1f, cut[2]);
        float targetH = cut[3] * scale;
        float dx = x - targetW * 0.50f;
        float dy = y - targetH + radius * 0.10f;
        float alpha = explosionFrameAlpha(v.age);

        AffineTransform oldTx = g.getTransform();
        java.awt.Composite oldComp = g.getComposite();
        Object oldBI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (v.age <= 4) {
                float flash = clamp01(1f - v.age / 5f);
                g.setComposite(AlphaComposite.SrcOver.derive(0.34f * flash));
                g.setColor(Color.WHITE);
                g.fillOval(Math.round(x - targetW * 0.34f), Math.round(y - targetH * 0.74f),
                        Math.round(targetW * 0.68f), Math.round(targetH * 0.52f));
            }
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            g.drawImage(sprite,
                    Math.round(dx), Math.round(dy),
                    Math.round(dx + targetW), Math.round(dy + targetH),
                    cut[0], cut[1], cut[0] + cut[2], cut[1] + cut[3],
                    null);
            return true;
        } finally {
            g.setTransform(oldTx);
            g.setComposite(oldComp);
            if (oldBI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldBI);
        }
    }

    private static boolean drawExplosionSprite(FakeGraphics gra, float x, float y, float radius, ExplosionVisual v) {
        if (gra == null) return false;
        FakeImage frame = explosionFrameFakeImage(explosionFrameIndex(v.age));
        if (frame == null) return false;
        float targetW = clamp(radius * EXPLOSION_VISUAL_WIDTH_SCALE, 190f, 820f);
        float scale = targetW / Math.max(1f, frame.getWidth());
        float targetH = frame.getHeight() * scale;
        int alpha = clampInt(Math.round(255f * explosionFrameAlpha(v.age)), 0, 255);
        try {
            gra.setComposite(FakeGraphics.TRANS, alpha, 0);
            gra.drawImage(frame, x - targetW * 0.5f, y - targetH + radius * 0.10f, targetW, targetH);
            return true;
        } catch (Throwable t) {
            Logger.err("Bomb Item explosion sprite draw failed", t);
            return false;
        } finally {
            resetComposite(gra);
        }
    }

    private static void drawIcon(FakeGraphics gra, int cx, int cy, int size,
                                 boolean enabled, int remaining, float opacity) {
        drawIcon(gra, cx, cy, size, enabled, remaining, opacity, true);
    }

    private static void drawIcon(FakeGraphics gra, int cx, int cy, int size,
                                 boolean enabled, int remaining, float opacity, boolean showCountBadge) {
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) {
            FakeImage asset = bombIconFakeImage();
            if (asset != null) {
                drawBombIconAsset(gra, asset, cx, cy, size, enabled, remaining, opacity, showCountBadge);
                return;
            }
            drawIconFallback(gra, cx, cy, size, enabled, remaining, opacity, showCountBadge);
            return;
        }
        BufferedImage asset = bombIconImage();
        if (asset != null) {
            drawBombIconAsset(g, asset, cx, cy, size, enabled, remaining, opacity, showCountBadge);
            return;
        }
        AffineTransform oldTx = g.getTransform();
        java.awt.Composite oldComp = g.getComposite();
        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        Font oldFont = g.getFont();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(clamp01(opacity)));
            float pulse = enabled ? 1f + (float) Math.sin(System.currentTimeMillis() * 0.007) * 0.04f : 1f;
            int r = Math.round(size * 0.58f * pulse);
            g.setColor(enabled ? new Color(0, 220, 255, 54) : new Color(90, 90, 90, 48));
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
            g.setColor(enabled ? new Color(255, 80, 34, 60) : new Color(60, 60, 60, 55));
            g.fillOval(cx - r + 8, cy - r + 8, r * 2 - 16, r * 2 - 16);
            drawModernDevice2D(g, cx, cy, size, enabled, showCountBadge ? String.valueOf(Math.max(0, remaining)) : "");
        } finally {
            g.setTransform(oldTx);
            g.setComposite(oldComp);
            g.setStroke(oldStroke);
            g.setColor(oldColor);
            g.setFont(oldFont);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    private static void drawAttachedDevice(FakeGraphics gra, int cx, int cy, int size,
                                           float rotation, String count) {
        Graphics2D g = CrazyRender.unwrap(gra);
        if (g == null) {
            FakeImage asset = bombIconFakeImage();
            if (asset != null) {
                drawAttachedBombAsset(gra, asset, cx, cy, size, rotation, count);
                return;
            }
            drawAttachedFallback(gra, cx, cy, size, rotation, count);
            return;
        }
        BufferedImage asset = bombIconImage();
        if (asset != null) {
            drawAttachedBombAsset(g, asset, cx, cy, size, rotation, count);
            return;
        }
        AffineTransform oldTx = g.getTransform();
        java.awt.Composite oldComp = g.getComposite();
        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        Font oldFont = g.getFont();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        try {
            g.setTransform(new AffineTransform());
            g.translate(cx, cy);
            g.rotate(rotation);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(0.78f));
            g.setColor(new Color(0, 230, 255, 70));
            g.fillOval(-size, -size, size * 2, size * 2);
            g.setComposite(AlphaComposite.SrcOver);
            drawModernDevice2D(g, 0, 0, size, true, count);
        } finally {
            g.setTransform(oldTx);
            g.setComposite(oldComp);
            g.setStroke(oldStroke);
            g.setColor(oldColor);
            g.setFont(oldFont);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    private static void drawBombIconAsset(FakeGraphics gra, FakeImage asset, int cx, int cy, int size,
                                          boolean enabled, int remaining, float opacity,
                                          boolean showCountBadge) {
        int alpha = clampInt(Math.round(255f * clamp01(opacity)), 0, 255);
        if (alpha <= 0) return;
        float pulse = enabled ? 1f + (float) Math.sin(System.currentTimeMillis() * 0.007) * 0.025f : 1f;
        float target = size * 1.12f * pulse;
        fillDisc(gra, cx, cy, Math.round(size * 0.52f),
                enabled ? 0 : 80, enabled ? 220 : 80, enabled ? 255 : 80,
                alphaScale(alpha, enabled ? 0.20f : 0.16f));
        drawScaledBombAsset(gra, asset, cx, cy, target, alpha);
        if (!enabled) {
            fillDisc(gra, cx, cy, Math.round(size * 0.50f), 38, 38, 38, alphaScale(alpha, 0.46f));
        }
        if (showCountBadge) {
            drawCountBadgeFallback(gra, String.valueOf(Math.max(0, remaining)),
                    Math.round(cx + size * 0.33f), Math.round(cy + size * 0.34f), size, enabled, alpha);
        }
    }

    private static void drawAttachedBombAsset(FakeGraphics gra, FakeImage asset, int cx, int cy,
                                              int size, float rotation, String count) {
        float target = size * 1.52f;
        FakeTransform old = null;
        try {
            old = gra.getTransform();
            gra.translate(cx, cy);
            gra.rotate(rotation);
            fillDisc(gra, 0, 0, Math.round(target * 0.42f), 0, 230, 255, 70);
            FakeImage display = bombCountdownFakeImage(count);
            if (display != null) {
                drawScaledBombAsset(gra, display, 0, 0, target, 255);
            } else {
                drawScaledBombAsset(gra, asset, 0, 0, target, 255);
                drawDigitalCountdownOverlayFallback(gra, count, 0, 0, target);
            }
        } catch (Throwable t) {
            Logger.err("Bomb Item attached FakeImage draw failed", t);
        } finally {
            if (old != null) {
                try { gra.setTransform(old); } catch (Throwable ignored) {}
                try { gra.delete(old); } catch (Throwable ignored) {}
            }
            resetComposite(gra);
        }
    }

    private static void drawScaledBombAsset(FakeGraphics gra, FakeImage asset, float cx, float cy,
                                            float maxSide, int alpha) {
        if (gra == null || asset == null || maxSide <= 0.5f || alpha <= 0) return;
        try {
            gra.setComposite(FakeGraphics.TRANS, clampInt(alpha, 0, 255), 0);
            float ratio = maxSide / Math.max(1f, Math.max(asset.getWidth(), asset.getHeight()));
            float w = asset.getWidth() * ratio;
            float h = asset.getHeight() * ratio;
            gra.drawImage(asset, cx - w * 0.5f, cy - h * 0.5f, w, h);
        } catch (Throwable t) {
            Logger.err("Bomb Item FakeImage draw failed", t);
        } finally {
            resetComposite(gra);
        }
    }

    private static void drawCountBadgeFallback(FakeGraphics gra, String text, int cx, int cy,
                                               int iconSize, boolean enabled, int alpha) {
        String label = text == null ? "" : text;
        int w = Math.max(Math.round(iconSize * 0.38f), label.length() * Math.max(8, iconSize / 5) + 10);
        int h = Math.max(Math.round(iconSize * 0.30f), Math.max(14, iconSize / 4));
        int x = cx - w / 2;
        int y = cy - h / 2;
        rect(gra, x - 3, y - 3, w + 6, h + 6, 0, 0, 0, alpha);
        rect(gra, x, y, w, h, enabled ? 255 : 150, enabled ? 226 : 150, enabled ? 68 : 150, alpha);
        strokeRect(gra, x, y, w, h, Math.max(1, iconSize / 28), 255, 255, 240, alphaScale(alpha, 0.62f));
        drawNumberFallback(gra, label, cx, cy + Math.max(1, iconSize / 26),
                Math.max(2, iconSize / 18), 0, 0, 0, alpha);
    }

    private static void drawDigitalCountdownOverlayFallback(FakeGraphics gra, String text,
                                                            float cx, float cy, float maxSide) {
        String label = text == null ? "" : text;
        FakeImage screen = countdownScreenFakeImage(label);
        if (screen != null) {
            drawCountdownScreen(gra, screen, cx, cy, maxSide);
            return;
        }
        float bx = countdownScreenX(cx, maxSide);
        float by = countdownScreenY(cy, maxSide);
        int w = Math.round(maxSide * COUNTDOWN_SCREEN_WIDTH);
        int h = Math.round(maxSide * COUNTDOWN_SCREEN_HEIGHT);
        int x = Math.round(bx - w * 0.5f);
        int y = Math.round(by - h * 0.5f);
        rect(gra, x, y, w, h, 1, 8, 10, 245);
        strokeRect(gra, x, y, w, h, Math.max(1, Math.round(maxSide * 0.018f)), 78, 238, 255, 245);
        drawNumberFallback(gra, label, Math.round(bx), Math.round(by),
                Math.max(2, Math.round(maxSide * 0.026f)), 122, 252, 255, 255);
    }

    private static void drawBombIconAsset(Graphics2D g, BufferedImage asset, int cx, int cy, int size,
                                          boolean enabled, int remaining, float opacity,
                                          boolean showCountBadge) {
        AffineTransform oldTx = g.getTransform();
        java.awt.Composite oldComp = g.getComposite();
        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        Font oldFont = g.getFont();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object oldBI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        try {
            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            float alpha = clamp01(opacity);
            float pulse = enabled ? 1f + (float) Math.sin(System.currentTimeMillis() * 0.007) * 0.025f : 1f;
            float target = size * 1.12f * pulse;
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            g.setColor(enabled ? new Color(0, 220, 255, 48) : new Color(80, 80, 80, 46));
            g.fillOval(Math.round(cx - size * 0.52f), Math.round(cy - size * 0.52f),
                    Math.round(size * 1.04f), Math.round(size * 1.04f));
            drawScaledBombAsset(g, asset, cx, cy, target);
            if (!enabled) {
                g.setComposite(AlphaComposite.SrcOver.derive(alpha * 0.48f));
                g.setColor(new Color(38, 38, 38));
                g.fillOval(Math.round(cx - size * 0.50f), Math.round(cy - size * 0.50f),
                        Math.round(size), Math.round(size));
            }
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            if (showCountBadge) {
                drawCountBadge(g, String.valueOf(Math.max(0, remaining)),
                        cx + size * 0.33f, cy + size * 0.34f, size, enabled);
            }
        } finally {
            g.setTransform(oldTx);
            g.setComposite(oldComp);
            g.setStroke(oldStroke);
            g.setColor(oldColor);
            g.setFont(oldFont);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
            if (oldBI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldBI);
        }
    }

    private static void drawAttachedBombAsset(Graphics2D g, BufferedImage asset, int cx, int cy,
                                              int size, float rotation, String count) {
        AffineTransform oldTx = g.getTransform();
        java.awt.Composite oldComp = g.getComposite();
        Stroke oldStroke = g.getStroke();
        Color oldColor = g.getColor();
        Font oldFont = g.getFont();
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object oldBI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        try {
            g.setTransform(new AffineTransform());
            g.translate(cx, cy);
            g.rotate(rotation);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            float target = size * 1.52f;
            g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
            g.setColor(new Color(0, 230, 255, 70));
            g.fillOval(Math.round(-target * 0.42f), Math.round(-target * 0.42f),
                    Math.round(target * 0.84f), Math.round(target * 0.84f));
            g.setComposite(AlphaComposite.SrcOver);
            BufferedImage display = bombCountdownImage(count);
            if (display != null) {
                drawScaledBombAsset(g, display, 0, 0, target);
            } else {
                drawScaledBombAsset(g, asset, 0, 0, target);
                drawDigitalCountdownOverlay(g, count, 0, 0, target);
            }
        } finally {
            g.setTransform(oldTx);
            g.setComposite(oldComp);
            g.setStroke(oldStroke);
            g.setColor(oldColor);
            g.setFont(oldFont);
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
            if (oldBI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldBI);
        }
    }

    private static void drawScaledBombAsset(Graphics2D g, BufferedImage asset, float cx, float cy, float maxSide) {
        if (asset == null || maxSide <= 0.5f) return;
        float ratio = maxSide / Math.max(1f, Math.max(asset.getWidth(), asset.getHeight()));
        float w = asset.getWidth() * ratio;
        float h = asset.getHeight() * ratio;
        g.drawImage(asset, Math.round(cx - w * 0.5f), Math.round(cy - h * 0.5f),
                Math.round(w), Math.round(h), null);
    }

    private static void drawCountBadge(Graphics2D g, String text, float cx, float cy,
                                       int iconSize, boolean enabled) {
        String label = text == null ? "" : text;
        int fontSize = clampInt(Math.round(iconSize * 0.26f), 11, 18);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int w = Math.max(Math.round(iconSize * 0.38f), fm.stringWidth(label) + 10);
        int h = Math.max(Math.round(iconSize * 0.30f), fm.getHeight() - 1);
        int x = Math.round(cx - w * 0.5f);
        int y = Math.round(cy - h * 0.5f);
        int arc = Math.max(8, h);
        g.setColor(Color.BLACK);
        g.fillRoundRect(x - 3, y - 3, w + 6, h + 6, arc + 6, arc + 6);
        g.setColor(enabled ? new Color(255, 226, 68) : new Color(150, 150, 150));
        g.fillRoundRect(x, y, w, h, arc, arc);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 240, enabled ? 150 : 80));
        g.drawRoundRect(x + 1, y + 1, Math.max(1, w - 2), Math.max(1, h - 2), arc - 2, arc - 2);
        g.setColor(Color.BLACK);
        int tx = Math.round(cx - fm.stringWidth(label) * 0.5f);
        int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(label, tx, ty);
    }

    private static void drawDigitalCountdownOverlay(Graphics2D g, String text, float cx, float cy, float maxSide) {
        String label = text == null ? "" : text;
        BufferedImage screen = countdownScreenImage(label);
        if (screen != null) {
            drawCountdownScreen(g, screen, cx, cy, maxSide);
            return;
        }
        float bx = countdownScreenX(cx, maxSide);
        float by = countdownScreenY(cy, maxSide);
        int w = Math.round(maxSide * COUNTDOWN_SCREEN_WIDTH);
        int h = Math.round(maxSide * COUNTDOWN_SCREEN_HEIGHT);
        int x = Math.round(bx - w * 0.5f);
        int y = Math.round(by - h * 0.5f);
        int arc = Math.max(6, Math.round(h * 0.35f));
        g.setComposite(AlphaComposite.SrcOver.derive(0.96f));
        g.setColor(new Color(1, 8, 10));
        g.fillRoundRect(x, y, w, h, arc, arc);
        g.setStroke(new BasicStroke(Math.max(1.2f, maxSide * 0.018f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(78, 238, 255));
        g.drawRoundRect(x, y, w, h, arc, arc);
        int fontSize = clampInt(Math.round(maxSide * 0.145f), 9, 24);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, fontSize));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int tx = Math.round(bx - fm.stringWidth(label) * 0.5f);
        int ty = Math.round(by + fm.getAscent() * 0.36f);
        g.setColor(new Color(0, 42, 48, 170));
        g.drawString(label, tx + 1, ty + 1);
        g.setColor(new Color(122, 252, 255));
        g.drawString(label, tx, ty);
    }

    private static float countdownScreenX(float cx, float maxSide) {
        return cx + maxSide * COUNTDOWN_SCREEN_OFFSET_X;
    }

    private static float countdownScreenY(float cy, float maxSide) {
        return cy + maxSide * COUNTDOWN_SCREEN_OFFSET_Y;
    }

    private static void drawCountdownScreen(FakeGraphics gra, FakeImage img, float cx, float cy, float maxSide) {
        if (gra == null || img == null || maxSide <= 0.5f) return;
        FakeTransform old = null;
        try {
            old = gra.getTransform();
            gra.translate(countdownScreenX(cx, maxSide), countdownScreenY(cy, maxSide));
            gra.rotate(COUNTDOWN_SCREEN_ROTATION);
            gra.setComposite(FakeGraphics.TRANS, 255, 0);
            float w = maxSide * COUNTDOWN_SCREEN_WIDTH;
            float h = maxSide * COUNTDOWN_SCREEN_HEIGHT;
            gra.drawImage(img, -w * 0.5f, -h * 0.5f, w, h);
        } catch (Throwable t) {
            Logger.err("Bomb Item countdown screen draw failed", t);
        } finally {
            if (old != null) {
                try { gra.setTransform(old); } catch (Throwable ignored) {}
                try { gra.delete(old); } catch (Throwable ignored) {}
            }
            resetComposite(gra);
        }
    }

    private static void drawCountdownScreen(Graphics2D g, BufferedImage img, float cx, float cy, float maxSide) {
        if (g == null || img == null || maxSide <= 0.5f) return;
        AffineTransform oldTx = g.getTransform();
        java.awt.Composite oldComp = g.getComposite();
        Object oldBI = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(AlphaComposite.SrcOver);
            g.translate(countdownScreenX(cx, maxSide), countdownScreenY(cy, maxSide));
            g.rotate(COUNTDOWN_SCREEN_ROTATION);
            float w = maxSide * COUNTDOWN_SCREEN_WIDTH;
            float h = maxSide * COUNTDOWN_SCREEN_HEIGHT;
            g.drawImage(img, Math.round(-w * 0.5f), Math.round(-h * 0.5f),
                    Math.round(w), Math.round(h), null);
        } finally {
            g.setTransform(oldTx);
            g.setComposite(oldComp);
            if (oldBI != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldBI);
        }
    }

    private static BufferedImage countdownScreenImage(String text) {
        int digit = countdownDigit(text);
        if (digit < 0) return null;
        BufferedImage cached = countdownScreenImages[digit];
        if (cached != null) return cached;
        synchronized (COUNTDOWN_CACHE_LOCK) {
            cached = countdownScreenImages[digit];
            if (cached == null) {
                cached = buildCountdownScreenImage(digit);
                countdownScreenImages[digit] = cached;
            }
        }
        return cached;
    }

    private static FakeImage countdownScreenFakeImage(String text) {
        int digit = countdownDigit(text);
        if (digit < 0) return null;
        FakeImage cached = countdownScreenFakeImages[digit];
        if (cached != null) return cached;
        synchronized (COUNTDOWN_CACHE_LOCK) {
            cached = countdownScreenFakeImages[digit];
            if (cached == null) {
                BufferedImage source = countdownScreenImage(text);
                cached = buildFakeImage(source);
                countdownScreenFakeImages[digit] = cached;
            }
        }
        return cached;
    }

    private static BufferedImage bombCountdownImage(String text) {
        int digit = countdownDigit(text);
        if (digit < 0) return null;
        BufferedImage cached = bombCountdownImages[digit];
        if (cached != null) return cached;
        synchronized (COUNTDOWN_CACHE_LOCK) {
            cached = bombCountdownImages[digit];
            if (cached == null) {
                cached = buildBombCountdownImage(digit);
                bombCountdownImages[digit] = cached;
            }
        }
        return cached;
    }

    private static FakeImage bombCountdownFakeImage(String text) {
        int digit = countdownDigit(text);
        if (digit < 0) return null;
        FakeImage cached = bombCountdownFakeImages[digit];
        if (cached != null) return cached;
        synchronized (COUNTDOWN_CACHE_LOCK) {
            cached = bombCountdownFakeImages[digit];
            if (cached == null) {
                BufferedImage source = bombCountdownImage(text);
                cached = buildFakeImage(source);
                bombCountdownFakeImages[digit] = cached;
            }
        }
        return cached;
    }

    private static BufferedImage buildBombCountdownImage(int digit) {
        BufferedImage base = bombIconImage();
        if (base == null) return null;
        BufferedImage image = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(base, 0, 0, null);
            float[][] quad = expandQuad(digitQuad(base.getWidth(), base.getHeight()), DIGIT_WARP_SCALE);
            fillQuad(g, expandQuad(quad, 1.06f), new Color(1, 8, 10, 238));
            BufferedImage screen = buildCountdownDigitPatch(digit);
            if (screen != null) warpImageToQuad(image, screen, quad);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int countdownDigit(String text) {
        if (text == null) return -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') return c - '0';
        }
        return -1;
    }

    private static BufferedImage buildCountdownScreenImage(int digit) {
        BufferedImage image = new BufferedImage(COUNTDOWN_SPRITE_W, COUNTDOWN_SPRITE_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(AlphaComposite.SrcOver);

            for (int i = 0; i < 4; i++) {
                int grow = 14 + i * 7;
                g.setColor(new Color(60, 238, 255, 18 - i * 3));
                g.fillRoundRect(18 - grow / 2, 18 - grow / 2,
                        COUNTDOWN_SPRITE_W - 36 + grow, COUNTDOWN_SPRITE_H - 36 + grow,
                        34 + grow, 34 + grow);
            }

            g.setColor(new Color(0, 0, 0, 168));
            g.fillRoundRect(10, 12, COUNTDOWN_SPRITE_W - 20, COUNTDOWN_SPRITE_H - 20, 30, 30);
            g.setColor(new Color(1, 8, 10, 250));
            g.fillRoundRect(16, 17, COUNTDOWN_SPRITE_W - 32, COUNTDOWN_SPRITE_H - 34, 24, 24);

            g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(15, 70, 78, 230));
            g.drawRoundRect(19, 20, COUNTDOWN_SPRITE_W - 38, COUNTDOWN_SPRITE_H - 40, 21, 21);
            g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(89, 246, 255, 245));
            g.drawRoundRect(19, 20, COUNTDOWN_SPRITE_W - 38, COUNTDOWN_SPRITE_H - 40, 21, 21);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(220, 255, 255, 130));
            g.drawRoundRect(25, 26, COUNTDOWN_SPRITE_W - 50, COUNTDOWN_SPRITE_H - 52, 16, 16);

            drawSevenSegmentDigit(g, 0, 42f, 26f, 48f, 58f, 17f,
                    new Color(35, 244, 255, 80));
            drawSevenSegmentDigit(g, digit, 104f, 26f, 48f, 58f, 17f,
                    new Color(35, 244, 255, 80));
            drawSevenSegmentDigit(g, 0, 42f, 26f, 48f, 58f, 11f,
                    new Color(94, 252, 255, 230));
            drawSevenSegmentDigit(g, digit, 104f, 26f, 48f, 58f, 11f,
                    new Color(94, 252, 255, 230));
            drawSevenSegmentDigit(g, 0, 42f, 26f, 48f, 58f, 5.2f,
                    new Color(226, 255, 255, 245));
            drawSevenSegmentDigit(g, digit, 104f, 26f, 48f, 58f, 5.2f,
                    new Color(226, 255, 255, 245));
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage buildCountdownClockPatch(int digit) {
        BufferedImage image = new BufferedImage(CLOCK_PATCH_W, CLOCK_PATCH_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(AlphaComposite.SrcOver);

            int padX = 18;
            int padY = 20;
            int w = CLOCK_PATCH_W - padX * 2;
            int h = CLOCK_PATCH_H - padY * 2;
            int arc = 42;
            for (int i = 0; i < 5; i++) {
                int grow = 12 + i * 8;
                g.setColor(new Color(50, 245, 255, 24 - i * 3));
                g.fillRoundRect(padX - grow / 2, padY - grow / 2,
                        w + grow, h + grow, arc + grow, arc + grow);
            }

            g.setColor(new Color(0, 0, 0, 174));
            g.fillRoundRect(padX - 8, padY - 8, w + 16, h + 16, arc + 16, arc + 16);
            g.setColor(new Color(1, 8, 10, 255));
            g.fillRoundRect(padX, padY, w, h, arc, arc);
            g.setStroke(new BasicStroke(9.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(25, 92, 102, 230));
            g.drawRoundRect(padX + 4, padY + 4, w - 8, h - 8, arc - 8, arc - 8);
            g.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(91, 246, 255, 240));
            g.drawRoundRect(padX + 5, padY + 5, w - 10, h - 10, arc - 10, arc - 10);

            int digitW = 72;
            int digitH = 118;
            int gap = 28;
            int startX = (CLOCK_PATCH_W - digitW * 2 - gap) / 2;
            int y = 52;
            drawSevenSegmentDigit(g, 8, startX, y, digitW, digitH, 10f,
                    new Color(28, 92, 100, 58));
            drawSevenSegmentDigit(g, 8, startX + digitW + gap, y, digitW, digitH, 10f,
                    new Color(28, 92, 100, 58));
            drawClockDigit(g, 0, startX, y, digitW, digitH);
            drawClockDigit(g, digit, startX + digitW + gap, y, digitW, digitH);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage buildCountdownDigitPatch(int digit) {
        BufferedImage image = new BufferedImage(DIGIT_PATCH_W, DIGIT_PATCH_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.SrcOver);

            int digitW = 68;
            int digitH = 112;
            int gap = 22;
            int startX = (DIGIT_PATCH_W - digitW * 2 - gap) / 2;
            int y = 18;
            drawSevenSegmentDigit(g, 8, startX, y, digitW, digitH, 9.5f,
                    new Color(28, 92, 100, 48));
            drawSevenSegmentDigit(g, 8, startX + digitW + gap, y, digitW, digitH, 9.5f,
                    new Color(28, 92, 100, 48));
            drawClockDigit(g, 0, startX, y, digitW, digitH);
            drawClockDigit(g, digit, startX + digitW + gap, y, digitW, digitH);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void drawClockDigit(Graphics2D g, int digit, float x, float y, float w, float h) {
        drawSevenSegmentDigit(g, digit, x, y, w, h, 27f, new Color(36, 242, 255, 58));
        drawSevenSegmentDigit(g, digit, x, y, w, h, 16f, new Color(91, 252, 255, 222));
        drawSevenSegmentDigit(g, digit, x, y, w, h, 6.4f, new Color(226, 255, 255, 245));
    }

    private static float[][] clockQuad(int assetW, int assetH) {
        float sx = assetW / (float) CLOCK_ASSET_W;
        float sy = assetH / (float) CLOCK_ASSET_H;
        return new float[][] {
                {CLOCK_TL_X * sx, CLOCK_TL_Y * sy},
                {CLOCK_TR_X * sx, CLOCK_TR_Y * sy},
                {CLOCK_BR_X * sx, CLOCK_BR_Y * sy},
                {CLOCK_BL_X * sx, CLOCK_BL_Y * sy}
        };
    }

    private static float[][] digitQuad(int assetW, int assetH) {
        float sx = assetW / (float) CLOCK_ASSET_W;
        float sy = assetH / (float) CLOCK_ASSET_H;
        return new float[][] {
                {DIGIT_TL_X * sx, DIGIT_TL_Y * sy},
                {DIGIT_TR_X * sx, DIGIT_TR_Y * sy},
                {DIGIT_BR_X * sx, DIGIT_BR_Y * sy},
                {DIGIT_BL_X * sx, DIGIT_BL_Y * sy}
        };
    }

    private static float[][] expandQuad(float[][] quad, float scale) {
        float cx = 0f;
        float cy = 0f;
        for (int i = 0; i < 4; i++) {
            cx += quad[i][0];
            cy += quad[i][1];
        }
        cx *= 0.25f;
        cy *= 0.25f;
        float[][] out = new float[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = cx + (quad[i][0] - cx) * scale;
            out[i][1] = cy + (quad[i][1] - cy) * scale;
        }
        return out;
    }

    private static void fillQuad(Graphics2D g, float[][] quad, Color color) {
        if (g == null || quad == null || quad.length < 4 || color == null) return;
        Path2D path = new Path2D.Float();
        path.moveTo(quad[0][0], quad[0][1]);
        for (int i = 1; i < 4; i++) {
            path.lineTo(quad[i][0], quad[i][1]);
        }
        path.closePath();
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(color);
        g.fill(path);
    }

    private static void warpImageToQuad(BufferedImage dest, BufferedImage src, float[][] quad) {
        if (dest == null || src == null || quad == null || quad.length < 4) return;
        double[][] from = new double[][] {
                {quad[0][0], quad[0][1]},
                {quad[1][0], quad[1][1]},
                {quad[2][0], quad[2][1]},
                {quad[3][0], quad[3][1]}
        };
        double[][] to = new double[][] {
                {0.0, 0.0},
                {src.getWidth() - 1.0, 0.0},
                {src.getWidth() - 1.0, src.getHeight() - 1.0},
                {0.0, src.getHeight() - 1.0}
        };
        double[] h = homography(from, to);
        if (h == null) return;
        int minX = clampInt(clampInt((long) Math.floor(minQuadX(quad)) - 2), 0, dest.getWidth() - 1);
        int maxX = clampInt(clampInt((long) Math.ceil(maxQuadX(quad)) + 2), 0, dest.getWidth() - 1);
        int minY = clampInt(clampInt((long) Math.floor(minQuadY(quad)) - 2), 0, dest.getHeight() - 1);
        int maxY = clampInt(clampInt((long) Math.ceil(maxQuadY(quad)) + 2), 0, dest.getHeight() - 1);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double den = h[6] * px + h[7] * py + 1.0;
                if (Math.abs(den) < 1.0e-9) continue;
                double sx = (h[0] * px + h[1] * py + h[2]) / den;
                double sy = (h[3] * px + h[4] * py + h[5]) / den;
                if (sx < -0.5 || sy < -0.5 || sx > src.getWidth() - 0.5 || sy > src.getHeight() - 0.5) {
                    continue;
                }
                int s = sampleBilinear(src, sx, sy);
                if (((s >>> 24) & 255) <= 0) continue;
                int d = dest.getRGB(x, y);
                dest.setRGB(x, y, blendOver(d, s));
            }
        }
    }

    private static double[] homography(double[][] from, double[][] to) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = from[i][0];
            double y = from[i][1];
            double u = to[i][0];
            double v = to[i][1];
            int r = i * 2;
            a[r][0] = x;
            a[r][1] = y;
            a[r][2] = 1.0;
            a[r][6] = -u * x;
            a[r][7] = -u * y;
            a[r][8] = u;
            a[r + 1][3] = x;
            a[r + 1][4] = y;
            a[r + 1][5] = 1.0;
            a[r + 1][6] = -v * x;
            a[r + 1][7] = -v * y;
            a[r + 1][8] = v;
        }
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            double best = Math.abs(a[col][col]);
            for (int row = col + 1; row < 8; row++) {
                double v = Math.abs(a[row][col]);
                if (v > best) {
                    best = v;
                    pivot = row;
                }
            }
            if (best < 1.0e-9) return null;
            if (pivot != col) {
                double[] tmp = a[col];
                a[col] = a[pivot];
                a[pivot] = tmp;
            }
            double div = a[col][col];
            for (int j = col; j < 9; j++) a[col][j] /= div;
            for (int row = 0; row < 8; row++) {
                if (row == col) continue;
                double f = a[row][col];
                if (Math.abs(f) < 1.0e-12) continue;
                for (int j = col; j < 9; j++) a[row][j] -= f * a[col][j];
            }
        }
        double[] h = new double[8];
        for (int i = 0; i < 8; i++) h[i] = a[i][8];
        return h;
    }

    private static int sampleBilinear(BufferedImage image, double x, double y) {
        int w = image.getWidth();
        int h = image.getHeight();
        x = Math.max(0.0, Math.min(w - 1.0, x));
        y = Math.max(0.0, Math.min(h - 1.0, y));
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        double fx = x - x0;
        double fy = y - y0;
        int c00 = image.getRGB(x0, y0);
        int c10 = image.getRGB(x1, y0);
        int c01 = image.getRGB(x0, y1);
        int c11 = image.getRGB(x1, y1);
        int a = bilerp((c00 >>> 24) & 255, (c10 >>> 24) & 255, (c01 >>> 24) & 255, (c11 >>> 24) & 255, fx, fy);
        int r = bilerp((c00 >>> 16) & 255, (c10 >>> 16) & 255, (c01 >>> 16) & 255, (c11 >>> 16) & 255, fx, fy);
        int g = bilerp((c00 >>> 8) & 255, (c10 >>> 8) & 255, (c01 >>> 8) & 255, (c11 >>> 8) & 255, fx, fy);
        int b = bilerp(c00 & 255, c10 & 255, c01 & 255, c11 & 255, fx, fy);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int bilerp(int c00, int c10, int c01, int c11, double fx, double fy) {
        double top = c00 + (c10 - c00) * fx;
        double bottom = c01 + (c11 - c01) * fx;
        return clampInt(clampInt(Math.round(top + (bottom - top) * fy)), 0, 255);
    }

    private static int blendOver(int dst, int src) {
        int sa = (src >>> 24) & 255;
        if (sa <= 0) return dst;
        if (sa >= 255) return src;
        int da = (dst >>> 24) & 255;
        int inv = 255 - sa;
        int outA = sa + da * inv / 255;
        if (outA <= 0) return 0;
        int sr = (src >>> 16) & 255;
        int sg = (src >>> 8) & 255;
        int sb = src & 255;
        int dr = (dst >>> 16) & 255;
        int dg = (dst >>> 8) & 255;
        int db = dst & 255;
        int outR = (sr * sa + dr * da * inv / 255) / outA;
        int outG = (sg * sa + dg * da * inv / 255) / outA;
        int outB = (sb * sa + db * da * inv / 255) / outA;
        return (outA << 24)
                | (clampInt(outR, 0, 255) << 16)
                | (clampInt(outG, 0, 255) << 8)
                | clampInt(outB, 0, 255);
    }

    private static float minQuadX(float[][] quad) {
        return Math.min(Math.min(quad[0][0], quad[1][0]), Math.min(quad[2][0], quad[3][0]));
    }

    private static float maxQuadX(float[][] quad) {
        return Math.max(Math.max(quad[0][0], quad[1][0]), Math.max(quad[2][0], quad[3][0]));
    }

    private static float minQuadY(float[][] quad) {
        return Math.min(Math.min(quad[0][1], quad[1][1]), Math.min(quad[2][1], quad[3][1]));
    }

    private static float maxQuadY(float[][] quad) {
        return Math.max(Math.max(quad[0][1], quad[1][1]), Math.max(quad[2][1], quad[3][1]));
    }

    private static void drawSevenSegmentDigit(Graphics2D g, int digit, float x, float y,
                                              float w, float h, float strokeWidth, Color color) {
        boolean[] seg = digitSegments((char) ('0' + clampInt(digit, 0, 9)));
        Stroke old = g.getStroke();
        Color oldColor = g.getColor();
        try {
            g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(color);
            float mid = y + h * 0.5f;
            float inset = strokeWidth * 0.55f;
            if (seg[0]) g.drawLine(Math.round(x + inset), Math.round(y), Math.round(x + w - inset), Math.round(y));
            if (seg[1]) g.drawLine(Math.round(x + w), Math.round(y + inset), Math.round(x + w), Math.round(mid - inset * 0.5f));
            if (seg[2]) g.drawLine(Math.round(x + w), Math.round(mid + inset * 0.5f), Math.round(x + w), Math.round(y + h - inset));
            if (seg[3]) g.drawLine(Math.round(x + inset), Math.round(y + h), Math.round(x + w - inset), Math.round(y + h));
            if (seg[4]) g.drawLine(Math.round(x), Math.round(mid + inset * 0.5f), Math.round(x), Math.round(y + h - inset));
            if (seg[5]) g.drawLine(Math.round(x), Math.round(y + inset), Math.round(x), Math.round(mid - inset * 0.5f));
            if (seg[6]) g.drawLine(Math.round(x + inset), Math.round(mid), Math.round(x + w - inset), Math.round(mid));
        } finally {
            g.setStroke(old);
            g.setColor(oldColor);
        }
    }

    private static FakeImage crackGlowFakeImage() {
        FakeImage cached = crackGlowFakeImage;
        if (cached != null) return cached;
        synchronized (CRACK_GLOW_CACHE_LOCK) {
            cached = crackGlowFakeImage;
            if (cached == null) {
                cached = buildFakeImage(buildCrackGlowImage());
                crackGlowFakeImage = cached;
            }
        }
        return cached;
    }

    private static BufferedImage buildCrackGlowImage() {
        int n = CRACK_GLOW_SPRITE_SIZE;
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        float center = (n - 1) * 0.5f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = (x - center) / center;
                float dy = (y - center) / center;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= 1f) continue;
                float falloff = 1f - d;
                float hot = clamp01((0.42f - d) / 0.42f);
                float rim = clamp01((d - 0.38f) / 0.62f) * falloff;
                int a = clampInt((int) Math.round(205f * Math.pow(falloff, 1.65f) + 75f * hot), 0, 255);
                int r = clampInt(Math.round(38f + 207f * hot + 118f * rim), 0, 255);
                int g = clampInt(Math.round(232f + 23f * hot - 112f * rim), 0, 255);
                int b = clampInt(Math.round(255f - 18f * rim), 0, 255);
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FakeImage buildFakeImage(BufferedImage image) {
        try {
            if (ImageBuilder.builder == null || image == null) return null;
            return ((ImageBuilder) ImageBuilder.builder).build(image);
        } catch (Throwable t) {
            Logger.err("Bomb Item FakeImage conversion failed", t);
            return null;
        }
    }

    private static long visiblePointNear(SpriteBox box, float sx, float sy, float radius) {
        if (visiblePointOnSprite(box, sx, sy)) return packPoint(Math.round(sx), Math.round(sy));
        float step = Math.max(5f, radius * 0.055f);
        for (int ring = 1; ring <= 8; ring++) {
            int samples = 10 + ring * 4;
            float r = step * ring;
            for (int i = 0; i < samples; i++) {
                float a = (float) (Math.PI * 2.0 * i / samples);
                float x = sx + (float) Math.cos(a) * r;
                float y = sy + (float) Math.sin(a) * r * 0.88f;
                if (visiblePointOnSprite(box, x, y)) return packPoint(Math.round(x), Math.round(y));
            }
        }
        return NO_POINT;
    }

    private static long crackOriginInSpritePart(SpriteBox box, ArmedBomb bomb, int cluster) {
        if (box == null || bomb == null || box.parts.isEmpty()) return NO_POINT;
        long avoid = attachedBombPoint(box, bomb);
        float avoidR = box.radius() * (cluster == 0 ? 0.42f : 0.30f);
        int seed = bomb.seed + cluster * 1009;
        for (int attempt = 0; attempt < 48; attempt++) {
            SpriteAlphaPart part = pickSpritePart(box, seed + attempt * 37);
            long p = randomVisiblePointInPart(part, seed + attempt * 53);
            if (p == NO_POINT) continue;
            float x = unpackX(p);
            float y = unpackY(p);
            if (avoid != NO_POINT) {
                float dx = x - unpackX(avoid);
                float dy = y - unpackY(avoid);
                if (dx * dx + dy * dy < avoidR * avoidR && attempt < 36) continue;
            }
            return p;
        }
        for (int i = 0; i < box.parts.size(); i++) {
            long p = randomVisiblePointInPart(box.parts.get(i), seed + 5000 + i * 67);
            if (p != NO_POINT) return p;
        }
        return NO_POINT;
    }

    private static SpriteAlphaPart pickSpritePart(SpriteBox box, int seed) {
        if (box == null || box.parts.isEmpty()) return null;
        float total = 0f;
        float[] weights = new float[Math.min(32, box.parts.size())];
        for (int i = 0; i < weights.length; i++) {
            SpriteAlphaPart part = box.parts.get(i);
            float w = Math.max(0f, screenArea(part));
            weights[i] = w;
            total += w;
        }
        if (total <= 0.001f) return box.parts.get(Math.min(box.parts.size() - 1, Math.abs(seed) % box.parts.size()));
        float pick = rand01(seed) * total;
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick <= 0f) return box.parts.get(i);
        }
        return box.parts.get(weights.length - 1);
    }

    private static float screenArea(SpriteAlphaPart part) {
        if (part == null || part.alpha == null || part.matrix == null || !part.alpha.valid) return 0f;
        try {
            int iw = Math.max(1, part.image.getWidth());
            int ih = Math.max(1, part.image.getHeight());
            float l = part.drawX + part.alpha.minX / (float) iw * part.drawW;
            float r = part.drawX + (part.alpha.maxX + 1f) / iw * part.drawW;
            float t = part.drawY + part.alpha.minY / (float) ih * part.drawH;
            float b = part.drawY + (part.alpha.maxY + 1f) / ih * part.drawH;
            long p0 = projectPoint(part.matrix, l, t);
            long p1 = projectPoint(part.matrix, r, t);
            long p2 = projectPoint(part.matrix, r, b);
            long p3 = projectPoint(part.matrix, l, b);
            float minX = Math.min(Math.min(unpackX(p0), unpackX(p1)), Math.min(unpackX(p2), unpackX(p3)));
            float maxX = Math.max(Math.max(unpackX(p0), unpackX(p1)), Math.max(unpackX(p2), unpackX(p3)));
            float minY = Math.min(Math.min(unpackY(p0), unpackY(p1)), Math.min(unpackY(p2), unpackY(p3)));
            float maxY = Math.max(Math.max(unpackY(p0), unpackY(p1)), Math.max(unpackY(p2), unpackY(p3)));
            return Math.max(0f, maxX - minX) * Math.max(0f, maxY - minY);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static long randomVisiblePointInPart(SpriteAlphaPart part, int seed) {
        if (part == null || part.image == null || part.alpha == null || !part.alpha.valid || part.matrix == null) {
            return NO_POINT;
        }
        try {
            int iw = Math.max(1, part.image.getWidth());
            int ih = Math.max(1, part.image.getHeight());
            for (int attempt = 0; attempt < 32; attempt++) {
                float rx = rand01(seed + attempt * 41);
                float ry = rand01(seed + attempt * 59);
                int u = clampInt(Math.round(lerp(part.alpha.minX, part.alpha.maxX, rx)), part.alpha.minX, part.alpha.maxX);
                int v = clampInt(Math.round(lerp(part.alpha.minY, part.alpha.maxY, ry)), part.alpha.minY, part.alpha.maxY);
                if (!visiblePixel(part, u, v)) continue;
                float lx = part.drawX + (u + 0.5f) / iw * part.drawW;
                float ly = part.drawY + (v + 0.5f) / ih * part.drawH;
                return projectPoint(part.matrix, lx, ly);
            }
        } catch (Throwable ignored) {}
        return NO_POINT;
    }

    private static long attachedBombPoint(SpriteBox box, ArmedBomb bomb) {
        if (box == null || bomb == null) return NO_POINT;
        float cx = box.bodyCX + (box.maxX - box.minX) * bomb.attachOffsetX;
        float cy = box.bodyCY + (box.maxY - box.minY) * bomb.attachOffsetY;
        return packPoint(Math.round(cx), Math.round(cy));
    }

    private static boolean visiblePointOnSprite(SpriteBox box, float sx, float sy) {
        if (box == null || box.parts.isEmpty()) return false;
        for (int i = 0; i < box.parts.size(); i++) {
            if (visiblePointOnPart(box.parts.get(i), sx, sy)) return true;
        }
        return false;
    }

    private static boolean visiblePointOnPart(SpriteAlphaPart part, float sx, float sy) {
        if (part == null || part.image == null || part.alpha == null || !part.alpha.valid || part.matrix == null) {
            return false;
        }
        if (part.drawW == 0f || part.drawH == 0f) return false;
        long local = inverseProject(part.matrix, sx, sy);
        if (local == NO_POINT) return false;
        float lx = Float.intBitsToFloat(unpackX(local));
        float ly = Float.intBitsToFloat(unpackY(local));
        float left = Math.min(part.drawX, part.drawX + part.drawW);
        float right = Math.max(part.drawX, part.drawX + part.drawW);
        float top = Math.min(part.drawY, part.drawY + part.drawH);
        float bottom = Math.max(part.drawY, part.drawY + part.drawH);
        if (lx < left || lx > right || ly < top || ly > bottom) return false;
        try {
            int iw = Math.max(1, part.image.getWidth());
            int ih = Math.max(1, part.image.getHeight());
            int u = (int) Math.floor((lx - part.drawX) / part.drawW * iw);
            int v = (int) Math.floor((ly - part.drawY) / part.drawH * ih);
            return visiblePixel(part, u, v);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean visiblePixel(SpriteAlphaPart part, int u, int v) {
        if (part == null || part.alpha == null || part.image == null) return false;
        if (u < part.alpha.minX || u > part.alpha.maxX || v < part.alpha.minY || v > part.alpha.maxY) return false;
        try {
            int a = (part.image.getRGB(u, v) >>> 24) & 255;
            return a > ALPHA_THRESHOLD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static AlphaBounds alphaBounds(FakeImage img) {
        if (img == null) return null;
        synchronized (alphaBoundsCache) {
            AlphaBounds cached = alphaBoundsCache.get(img);
            if (cached != null) return cached;
        }
        AlphaBounds result;
        try {
            int w = Math.max(0, img.getWidth());
            int h = Math.max(0, img.getHeight());
            int minX = w;
            int minY = h;
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = (img.getRGB(x, y) >>> 24) & 255;
                    if (a <= ALPHA_THRESHOLD) continue;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
            result = maxX >= minX && maxY >= minY
                    ? new AlphaBounds(minX, minY, maxX, maxY, true)
                    : new AlphaBounds(0, 0, Math.max(0, w - 1), Math.max(0, h - 1), false);
        } catch (Throwable ignored) {
            result = new AlphaBounds(0, 0, 0, 0, false);
        }
        synchronized (alphaBoundsCache) {
            alphaBoundsCache.put(img, result);
        }
        return result;
    }

    private static long inverseProject(float[] m, float sx, float sy) {
        if (m == null || m.length < 6) return NO_POINT;
        float det = m[0] * m[4] - m[1] * m[3];
        if (Math.abs(det) < 0.00001f) return NO_POINT;
        float dx = sx - m[2];
        float dy = sy - m[5];
        float lx = (m[4] * dx - m[1] * dy) / det;
        float ly = (-m[3] * dx + m[0] * dy) / det;
        return packPoint(Float.floatToIntBits(lx), Float.floatToIntBits(ly));
    }

    private static long projectPoint(float[] m, float lx, float ly) {
        if (m == null || m.length < 6) return NO_POINT;
        float x = m[0] * lx + m[1] * ly + m[2];
        float y = m[3] * lx + m[4] * ly + m[5];
        return packPoint(Math.round(x), Math.round(y));
    }

    private static int explosionFrameIndex(int age) {
        int index = 0;
        for (int i = 0; i < EXPLOSION_KEYFRAMES.length; i++) {
            if (age < EXPLOSION_KEYFRAMES[i]) break;
            index = i;
        }
        return clampInt(index, 0, EXPLOSION_CUTS.length - 1);
    }

    private static float explosionFrameAlpha(int age) {
        if (age <= 34) return 1f;
        return clamp01(1f - (age - 34f) / Math.max(1f, VISUAL_FRAMES - 34f));
    }

    private static BufferedImage explosionSpriteImage() {
        if (!explosionSpriteLoadAttempted) {
            synchronized (BombItemFeature.class) {
                if (!explosionSpriteLoadAttempted) {
                    explosionSpriteLoadAttempted = true;
                    InputStream in = null;
                    try {
                        in = BombItemFeature.class.getResourceAsStream(EXPLOSION_SPRITE_RESOURCE);
                        if (in != null) {
                            explosionSpriteImage = ImageIO.read(in);
                        } else {
                            Logger.log("Bomb Item explosion sprite resource missing: " + EXPLOSION_SPRITE_RESOURCE);
                        }
                    } catch (Throwable t) {
                        Logger.err("Bomb Item explosion sprite load failed", t);
                    } finally {
                        if (in != null) {
                            try { in.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
        return explosionSpriteImage;
    }

    private static FakeImage explosionSpriteFakeImage() {
        if (!explosionSpriteFakeLoadAttempted) {
            synchronized (BombItemFeature.class) {
                if (!explosionSpriteFakeLoadAttempted) {
                    explosionSpriteFakeLoadAttempted = true;
                    try {
                        explosionSpriteFakeImage = FakeImage.read(new java.util.function.Supplier<InputStream>() {
                            @Override
                            public InputStream get() {
                                return BombItemFeature.class.getResourceAsStream(EXPLOSION_SPRITE_RESOURCE);
                            }
                        });
                        if (explosionSpriteFakeImage == null) {
                            Logger.log("Bomb Item explosion FakeImage resource missing: " + EXPLOSION_SPRITE_RESOURCE);
                        }
                    } catch (Throwable t) {
                        Logger.err("Bomb Item explosion FakeImage load failed", t);
                    }
                }
            }
        }
        return explosionSpriteFakeImage;
    }

    private static FakeImage explosionFrameFakeImage(int index) {
        index = clampInt(index, 0, EXPLOSION_CUTS.length - 1);
        FakeImage cached = explosionFrameFakeImages[index];
        if (cached != null) return cached;
        synchronized (EXPLOSION_FRAME_CACHE_LOCK) {
            cached = explosionFrameFakeImages[index];
            if (cached == null) {
                FakeImage sprite = explosionSpriteFakeImage();
                if (sprite == null) return null;
                int[] cut = EXPLOSION_CUTS[index];
                cached = sprite.getSubimage(cut[0], cut[1], cut[2], cut[3]);
                explosionFrameFakeImages[index] = cached;
            }
        }
        return cached;
    }

    private static BufferedImage bombIconImage() {
        if (!bombIconLoadAttempted) {
            synchronized (BombItemFeature.class) {
                if (!bombIconLoadAttempted) {
                    bombIconLoadAttempted = true;
                    InputStream in = null;
                    try {
                        in = BombItemFeature.class.getResourceAsStream(BOMB_ICON_RESOURCE);
                        if (in != null) {
                            bombIconImage = ImageIO.read(in);
                        } else {
                            Logger.log("Bomb Item icon resource missing: " + BOMB_ICON_RESOURCE);
                        }
                    } catch (Throwable t) {
                        Logger.err("Bomb Item icon load failed", t);
                    } finally {
                        if (in != null) {
                            try { in.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
        return bombIconImage;
    }

    private static FakeImage bombIconFakeImage() {
        if (!bombIconFakeLoadAttempted) {
            synchronized (BombItemFeature.class) {
                if (!bombIconFakeLoadAttempted) {
                    bombIconFakeLoadAttempted = true;
                    try {
                        bombIconFakeImage = FakeImage.read(new java.util.function.Supplier<InputStream>() {
                            @Override
                            public InputStream get() {
                                return BombItemFeature.class.getResourceAsStream(BOMB_ICON_RESOURCE);
                            }
                        });
                        if (bombIconFakeImage == null) {
                            Logger.log("Bomb Item FakeImage resource missing: " + BOMB_ICON_RESOURCE);
                        }
                    } catch (Throwable t) {
                        Logger.err("Bomb Item FakeImage icon load failed", t);
                    }
                }
            }
        }
        return bombIconFakeImage;
    }

    private static void drawModernDevice2D(Graphics2D g, int cx, int cy, int size,
                                           boolean enabled, String text) {
        float s = size / 66f;
        Color body = enabled ? new Color(30, 36, 44) : new Color(58, 58, 58);
        Color rim = enabled ? new Color(112, 236, 255) : new Color(150, 150, 150);
        Color hot = enabled ? new Color(255, 82, 44) : new Color(120, 120, 120);
        Path2D bodyPath = new Path2D.Float();
        bodyPath.moveTo(cx - 20f * s, cy - 35f * s);
        bodyPath.lineTo(cx + 20f * s, cy - 35f * s);
        bodyPath.lineTo(cx + 35f * s, cy - 18f * s);
        bodyPath.lineTo(cx + 31f * s, cy + 27f * s);
        bodyPath.lineTo(cx + 18f * s, cy + 39f * s);
        bodyPath.lineTo(cx - 18f * s, cy + 39f * s);
        bodyPath.lineTo(cx - 31f * s, cy + 27f * s);
        bodyPath.lineTo(cx - 35f * s, cy - 18f * s);
        bodyPath.closePath();
        g.setColor(new Color(0, 0, 0, enabled ? 115 : 90));
        g.fillOval(Math.round(cx - 42f * s), Math.round(cy - 24f * s),
                Math.round(84f * s), Math.round(54f * s));
        g.setColor(new Color(16, 20, 25, enabled ? 190 : 150));
        g.setStroke(new BasicStroke(Math.max(4f, 7f * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(Math.round(cx - 43f * s), Math.round(cy - 5f * s),
                Math.round(cx + 43f * s), Math.round(cy - 5f * s));
        g.drawLine(cx, Math.round(cy - 44f * s), cx, Math.round(cy + 47f * s));
        g.setColor(body);
        g.fill(bodyPath);
        g.setStroke(new BasicStroke(Math.max(2.4f, 4.6f * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(rim);
        g.draw(bodyPath);
        g.setStroke(new BasicStroke(Math.max(1.3f, 2.3f * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(hot);
        g.draw(bodyPath);
        int sw = Math.round(50f * s);
        int sh = Math.round(31f * s);
        int sx = cx - sw / 2;
        int sy = cy - Math.round(13f * s);
        g.setColor(new Color(2, 6, 8));
        g.fillRoundRect(sx, sy, sw, sh, Math.round(8f * s), Math.round(8f * s));
        g.setStroke(new BasicStroke(Math.max(1f, 1.8f * s)));
        g.setColor(rim);
        g.drawRoundRect(sx, sy, sw, sh, Math.round(8f * s), Math.round(8f * s));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(10, Math.round(21f * s))));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String label = text == null ? "" : text;
        int tx = cx - fm.stringWidth(label) / 2;
        int ty = sy + sh / 2 + fm.getAscent() / 2 - 3;
        g.setColor(enabled ? new Color(105, 250, 255) : new Color(185, 185, 185));
        g.drawString(label, tx, ty);
        g.setColor(new Color(18, 22, 26));
        g.fillOval(Math.round(cx - 14f * s), Math.round(cy - 49f * s),
                Math.round(28f * s), Math.round(18f * s));
        g.setColor(rim);
        g.drawOval(Math.round(cx - 14f * s), Math.round(cy - 49f * s),
                Math.round(28f * s), Math.round(18f * s));
        g.setColor(hot);
        g.fillOval(Math.round(cx + 18f * s), Math.round(cy - 49f * s),
                Math.max(3, Math.round(8f * s)), Math.max(3, Math.round(8f * s)));
    }

    private static void drawIconFallback(FakeGraphics gra, int cx, int cy, int size,
                                         boolean enabled, int remaining, float opacity,
                                         boolean showCountBadge) {
        int alpha = Math.round(255f * clamp01(opacity));
        int r = Math.max(20, size / 2);
        fillDisc(gra, cx, cy, r + 8, enabled ? 0 : 80, enabled ? 220 : 80, enabled ? 255 : 80, alphaScale(alpha, 0.26f));
        rect(gra, cx - r / 2, cy - r / 2, r, r, enabled ? 30 : 70, enabled ? 36 : 70, enabled ? 44 : 70, alpha);
        rect(gra, cx - r / 2 + 4, cy - 6, r - 8, Math.max(12, size / 4), 2, 6, 8, alpha);
        if (showCountBadge) {
            drawNumberFallback(gra, String.valueOf(Math.max(0, remaining)), cx, cy + 7, Math.max(2, size / 16),
                    enabled ? 105 : 180, enabled ? 250 : 180, enabled ? 255 : 180, alpha);
        }
        strokeRect(gra, cx - r / 2, cy - r / 2, r, r, 3,
                enabled ? 112 : 150, enabled ? 236 : 150, enabled ? 255 : 150, alpha);
    }

    private static void drawAttachedFallback(FakeGraphics gra, int cx, int cy, int size,
                                             float rotation, String count) {
        FakeTransform old = null;
        try {
            old = gra.getTransform();
            gra.translate(cx, cy);
            gra.rotate(rotation);
            int r = Math.max(18, size / 2);
            fillDisc(gra, 0, 0, r + 7, 0, 230, 255, 72);
            rect(gra, -r / 2, -r / 2, r, r, 30, 36, 44, 235);
            rect(gra, -r / 2 + 3, -5, r - 6, Math.max(10, size / 4), 2, 6, 8, 245);
            drawNumberFallback(gra, count, 0, 6, Math.max(2, size / 16), 105, 250, 255, 255);
            strokeRect(gra, -r / 2, -r / 2, r, r, 2, 112, 236, 255, 240);
        } finally {
            if (old != null) {
                try { gra.setTransform(old); } catch (Throwable ignored) {}
                try { gra.delete(old); } catch (Throwable ignored) {}
            }
            resetComposite(gra);
        }
    }

    private static Entity findTargetUnderCursor(Object page, int mx, int my) {
        try {
            Object basis = BCUFields.get(page, "basis");
            StageBasis sb = (StageBasis) BCUFields.get(basis, "sb");
            Object bb = BCUFields.get(page, "bb");
            Object bbp = BCUFields.get(bb, "bbp");
            float siz = BCUFields.getFloat(sb, "siz");
            int sbPos = BCUFields.getInt(sb, "pos");
            int midh = BCUFields.getInt(bbp, "midh");
            CrazyRuntime.StageRuntime rt = CrazyRuntime.get(sb);
            Entity best = null;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < sb.le.size(); i++) {
                Entity e = sb.le.get(i);
                if (!validTarget(rt, e)) continue;
                float sx = screenX(e.pos, siz, sbPos);
                float sy = groundY(safeLayer(e), siz, midh);
                float dx = sx - mx;
                float dy = sy - my;
                if (Math.abs(dx) > 120f || Math.abs(dy) > 280f) continue;
                float d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
            return best;
        } catch (Throwable t) {
            Logger.err("Bomb target hit-test failed", t);
            return null;
        }
    }

    private static boolean validTarget(CrazyRuntime.StageRuntime rt, Entity e) {
        if (rt == null || e == null) return false;
        if (e.isBase() || e.dead || e.health <= 0L) return false;
        if (!isAllySide(e)) return false;
        if (armedBomb(rt, e) != null) return false;
        try {
            HoldState hs = HoldState.get();
            if (hs.getHeldEntity() == e && hs.getPhase() != HoldState.Phase.NONE) return false;
        } catch (Throwable ignored) {}
        try { if (FallingRegistry.isManaged(e)) return false; } catch (Throwable ignored) {}
        try { if (manualcontrol.crazy.beam.BeamFeature.isEvolutionFrozen(e)) return false; } catch (Throwable ignored) {}
        try { if (manualcontrol.crazy.beam.ArmyCanonFeature.isManaged(e)) return false; } catch (Throwable ignored) {}
        try { if (manualcontrol.crazy.base.SlingshotBaseFeature.isManaged(e)) return false; } catch (Throwable ignored) {}
        try { if (manualcontrol.crazy.fall.ImpactFallFeature.isManaged(e)) return false; } catch (Throwable ignored) {}
        return true;
    }

    private static boolean isAllySide(Entity e) {
        if (e == null || e.dire != -1) return false;
        String cls = e.getClass().getName();
        return EntityAccess.CLASS_EUNIT.equals(cls) || ConvertedRegistry.isConverted(e);
    }

    private static ArmedBomb armedBomb(CrazyRuntime.StageRuntime rt, Object entity) {
        if (rt == null || entity == null) return null;
        State st = rt.bombItem;
        for (int i = 0; i < st.armed.size(); i++) {
            ArmedBomb bomb = st.armed.get(i);
            if (bomb != null && bomb.entity == entity) return bomb;
        }
        return null;
    }

    private static SpriteBox runtimeBox(Object entity) {
        CrazyRuntime.StageRuntime rt = runtimeForEntity(entity);
        if (!enabled(rt)) return null;
        return rt.bombItem.spriteBoxes.get(entity);
    }

    private static void trackCarrier(ArmedBomb bomb) {
        if (bomb == null || bomb.entity == null) return;
        Entity e = bomb.entity;
        bomb.lastPos = e.pos;
        bomb.lastLayer = safeLayer(e);
    }

    private static void cleanupMotion(Entity e) {
        if (e == null) return;
        try { BCUFields.field(e.getClass(), "kbTime").setInt(e, 0); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "walking").setBoolean(e, false); } catch (Throwable ignored) {}
        try { BCUFields.field(e.getClass(), "lastPosition").setFloat(e, e.pos); } catch (Throwable ignored) {}
    }

    private static void spawnNativeShockwave(StageBasis sb, float pos, int layer) {
        if (sb == null) return;
        try {
            EAnimD<?> anim = EffAnim.effas().A_SHOCKWAVE.getEAnim(EffAnim.DefEff.DEF);
            sb.lea.add(new EAnimCont(pos, layer, anim));
            sb.leaSort = true;
        } catch (Throwable t) {
            Logger.err("Bomb Item native shockwave spawn failed", t);
        }
    }

    private static void applyScreen(Entity e, float sx, float sy, float siz,
                                    int stagePos, int midh, int layer) {
        if (e == null || siz < 0.001f) return;
        float gameX = ((sx - stagePos) / siz - 200f) / 0.32f;
        e.pos = gameX;
        e.lastPosition = gameX;
        try { EntityAccess.setLayer(e, layer); } catch (Throwable ignored) {}
    }

    private static void updateTransform(State st, Object bbpainter) {
        try {
            st.lastSiz = BBPainterAccess.getSiz(bbpainter);
            st.lastStagePos = BBPainterAccess.getStagePos(bbpainter);
            st.lastMidh = BBPainterAccess.getMidh(bbpainter);
            st.haveTransform = st.lastSiz > 0.001f;
        } catch (Throwable ignored) {}
    }

    private static void applyCameraShake(StageBasis sb, float radiusPx) {
        try {
            int frames = clampInt(Math.round(10f + radiusPx / 58f), 12, 24);
            int amp = clampInt(Math.round(7f + radiusPx / 25f), 12, 38);
            sb.shake = new int[] {frames, Math.max(2, amp / 4), amp};
            sb.shakeDuration = frames;
        } catch (Throwable ignored) {}
    }

    private static void playExplosionSound() {
        try {
            CommonStatic.setSE(EXPLOSION_SE);
        } catch (Throwable t) {
            Logger.err("Bomb explosion sound failed", t);
        }
    }

    private static String countdownText(ArmedBomb bomb) {
        int frame = Math.max(0, bomb == null ? 0 : bomb.frame);
        int value;
        if (frame < FPS) value = 3;
        else if (frame < 2 * FPS) value = 2;
        else if (frame < 3 * FPS) value = 1;
        else value = 0;
        return "0" + value;
    }

    private static void beginReturn(State st, int x, int y) {
        st.dragging = false;
        st.returning = true;
        st.returnFrame = 0;
        st.returnFromX = x;
        st.returnFromY = y;
    }

    private static void ensureInitialized(CrazyRuntime.StageRuntime rt) {
        State st = rt.bombItem;
        if (st.initialized) return;
        st.remaining = rt.config.bombCount;
        st.initialized = true;
    }

    private static boolean enabled(CrazyRuntime.StageRuntime rt) {
        return rt != null && rt.config.bombItem;
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
        int bossX = w - size - margin;
        int x = bossX - size - gap;
        int y = Math.max(58, Math.round(h * 0.095f));
        return new Rect(x, y, size, size);
    }

    private static CrazyRuntime.StageRuntime runtimeForEntity(Object entity) {
        if (entity instanceof Entity) {
            try {
                return CrazyRuntime.get(((Entity) entity).basis);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int safeLayer(Object entity) {
        try {
            return EntityAccess.getLayer(entity);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static float readStageSiz(StageBasis sb) {
        try {
            return BCUFields.getFloat(sb, "siz");
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private static float screenX(float pos, float siz, int stagePos) {
        return (pos * 0.32f + 200f) * siz + stagePos;
    }

    private static float groundY(int layer, float siz, int midh) {
        return midh - (156 - layer * 4) * siz;
    }

    private static void fillDisc(FakeGraphics gra, int cx, int cy, int radius,
                                 int r, int g, int b, int alpha) {
        int rr = Math.max(1, radius);
        for (int dy = -rr; dy <= rr; dy++) {
            int span = (int) Math.floor(Math.sqrt((double) rr * rr - (double) dy * dy));
            rect(gra, cx - span, cy + dy, span * 2 + 1, 1, r, g, b, alpha);
        }
    }

    private static void strokeEllipseFallback(FakeGraphics gra, int cx, int cy, int rx, int ry,
                                              int thickness, int r, int g, int b, int alpha) {
        int steps = 72;
        int px = cx + rx;
        int py = cy;
        for (int i = 1; i <= steps; i++) {
            double a = Math.PI * 2.0 * i / steps;
            int x = cx + (int) Math.round(Math.cos(a) * rx);
            int y = cy + (int) Math.round(Math.sin(a) * ry);
            drawLineFallback(gra, px, py, x, y, thickness, r, g, b, alpha);
            px = x;
            py = y;
        }
    }

    private static void drawLineFallback(FakeGraphics gra, int x0, int y0, int x1, int y1,
                                         int thickness, int r, int g, int b, int alpha) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(1, (int) (Math.sqrt((double) dx * dx + (double) dy * dy) / 3.0));
        int dot = Math.max(1, thickness);
        int half = dot / 2;
        for (int i = 0; i <= steps; i++) {
            float p = i / (float) steps;
            int x = Math.round(x0 + dx * p);
            int y = Math.round(y0 + dy * p);
            rect(gra, x - half, y - half, dot, dot, r, g, b, alpha);
        }
    }

    private static void strokeRect(FakeGraphics gra, int x, int y, int w, int h, int t,
                                   int r, int g, int b, int alpha) {
        rect(gra, x, y, w, t, r, g, b, alpha);
        rect(gra, x, y + h - t, w, t, r, g, b, alpha);
        rect(gra, x, y, t, h, r, g, b, alpha);
        rect(gra, x + w - t, y, t, h, r, g, b, alpha);
    }

    private static void drawNumberFallback(FakeGraphics gra, String text, int cx, int cy, int thick,
                                           int r, int g, int b, int alpha) {
        if (text == null) return;
        int charW = thick * 8;
        int start = cx - (text.length() * charW) / 2;
        for (int i = 0; i < text.length(); i++) {
            drawDigitFallback(gra, text.charAt(i), start + i * charW + charW / 2, cy, thick, r, g, b, alpha);
        }
    }

    private static void drawDigitFallback(FakeGraphics gra, char c, int cx, int cy, int t,
                                          int r, int g, int b, int alpha) {
        int w = t * 5;
        int h = t * 8;
        boolean[] seg = digitSegments(c);
        int x = cx - w / 2;
        int y = cy - h / 2;
        if (seg[0]) rect(gra, x + t, y, w - 2 * t, t, r, g, b, alpha);
        if (seg[1]) rect(gra, x + w - t, y + t, t, h / 2 - t, r, g, b, alpha);
        if (seg[2]) rect(gra, x + w - t, y + h / 2, t, h / 2 - t, r, g, b, alpha);
        if (seg[3]) rect(gra, x + t, y + h - t, w - 2 * t, t, r, g, b, alpha);
        if (seg[4]) rect(gra, x, y + h / 2, t, h / 2 - t, r, g, b, alpha);
        if (seg[5]) rect(gra, x, y + t, t, h / 2 - t, r, g, b, alpha);
        if (seg[6]) rect(gra, x + t, y + h / 2 - t / 2, w - 2 * t, t, r, g, b, alpha);
    }

    private static boolean[] digitSegments(char c) {
        switch (c) {
            case '0': return new boolean[] {true, true, true, true, true, true, false};
            case '1': return new boolean[] {false, true, true, false, false, false, false};
            case '2': return new boolean[] {true, true, false, true, true, false, true};
            case '3': return new boolean[] {true, true, true, true, false, false, true};
            case '4': return new boolean[] {false, true, true, false, false, true, true};
            case '5': return new boolean[] {true, false, true, true, false, true, true};
            case '6': return new boolean[] {true, false, true, true, true, true, true};
            case '7': return new boolean[] {true, true, true, false, false, false, false};
            case '8': return new boolean[] {true, true, true, true, true, true, true};
            case '9': return new boolean[] {true, true, true, true, false, true, true};
            default: return new boolean[] {false, false, false, false, false, false, true};
        }
    }

    private static void rect(FakeGraphics gra, int x, int y, int w, int h,
                             int r, int g, int b, int alpha) {
        if (gra == null || w <= 0 || h <= 0 || alpha <= 0) return;
        gra.colRect(x, y, w, h, clampInt(r, 0, 255), clampInt(g, 0, 255),
                clampInt(b, 0, 255), clampInt(alpha, 0, 255));
    }

    private static void resetComposite(FakeGraphics gra) {
        try { gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private static int alphaScale(int alpha, float scale) {
        return clampInt(Math.round(alpha * scale), 0, 255);
    }

    private static int stableSeed(Object entity, int salt) {
        int h = System.identityHashCode(entity) ^ (salt * 1103515245);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return h;
    }

    private static float rand01(int seed) {
        int x = seed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        return (x & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }

    private static float lerp(float a, float b, float p) {
        return a + (b - a) * p;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long packPoint(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static int unpackX(long p) {
        return (int) (p >> 32);
    }

    private static int unpackY(long p) {
        return (int) p;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
