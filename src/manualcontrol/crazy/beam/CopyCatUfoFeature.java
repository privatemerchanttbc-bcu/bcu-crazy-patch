package manualcontrol.crazy.beam;

import common.battle.StageBasis;
import common.battle.data.MaskEnemy;
import common.battle.entity.EEnemy;
import common.battle.entity.Entity;
import common.pack.UserProfile;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.anim.EPart;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Unit;
import manualcontrol.ConvertedRegistry;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyConfig;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.hooks.BoundsRecorder;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.EntityAccess;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class CopyCatUfoFeature {

    private static final int PHASE_OUT = 0;
    private static final int PHASE_TURN = 1;
    private static final int PHASE_RETURN = 2;
    private static final int PHASE_SPAWN = 3;
    private static final int PHASE_DIVE = 4;

    private static final int UFO_UNIT_ID = 5;
    private static final int UFO_FORM_ID = 1;

    private static final int MAX_UFOS = 16;
    private static final int TURN_FRAMES = 14;

    private static final float UFO_SCALE = 0.85f;

    private static final float ENEMY_HALF_FALLBACK = 60f;

    private static final float SAUCER_W = 84f;
    private static final float SAUCER_H = 78f;

    private static final int SCAN_HIT_LIFE = 18;

    private static final int SHADOW_IMG_CUT = 8;

    private static final int DUMMY_IMG_CUT = 18;

    private static final float RETURN_TILT = 0.22f;

    private static final int TRAIL_LEN = 18;
    private static final float TRAIL_SIZE = 1.5f;
    private static final float TRAIL_ALPHA = 120f;
    private static final int SPARKLE_COUNT = 8;
    private static final int SPEED_LINE_COUNT = 5;
    private static final int RIM_NODES = 4;

    private static final float SAUCER_INSET_L = 0.00f;
    private static final float SAUCER_INSET_R = 0.071f;
    private static final float SAUCER_INSET_T = 0.038f;
    private static final float SAUCER_INSET_B = 0.218f;

    private static final boolean DEBUG_BOX =
            "true".equalsIgnoreCase(System.getProperty("mc.copycat.debugbox", "false"));

    private static volatile Field transformDataField;

    private static final Map<Object, Float> CLONE_SCALE =
            Collections.synchronizedMap(new WeakHashMap<Object, Float>());

    private CopyCatUfoFeature() {}

    public static final class State {
        public final List<Ufo> ufos = new ArrayList<Ufo>();
        Form ufoForm;
        EAnimU ufoAnim;
        boolean spriteResolved;
        boolean spriteFailed;
        boolean loggedDraw;

        FakeImage glowTex;
        FakeImage ringTex;
        FakeImage sparkTex;
        int bakedHue = Integer.MIN_VALUE;

        boolean saucerMeasured;
        float rCenterX, rCenterY, rHalfW, rHalfH, rBottom;

        final List<ScanHit> scanHits = new ArrayList<ScanHit>();
    }

    static final class Snapshot {
        final Enemy def;
        final float mult;
        final float mula;
        final long health;
        final int layer;
        final float statScale;

        Snapshot(Enemy def, float mult, float mula, long health, int layer, float statScale) {
            this.def = def;
            this.mult = mult;
            this.mula = mula;
            this.health = health;
            this.layer = layer;
            this.statScale = statScale;
        }
    }

    static final class ScanHit {
        final Entity enemy;
        float pos;
        int layer;
        int age;

        ScanHit(Entity e) {
            this.enemy = e;
            this.pos = e.pos;
            this.layer = e.currentLayer;
        }
    }

    static final class Ufo {
        int phase = PHASE_OUT;
        float pos;
        float startPos;
        float endPos;
        int animFrame;
        int turnTimer;
        int spawnTimer;
        int diveTimer;
        float signalFlash;
        float tilt;
        float[] histX, histY;
        int histCount;
        final Map<Object, Boolean> scanned =
                Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
        final List<Snapshot> queue = new ArrayList<Snapshot>();
    }

    public static void activate(CrazyRuntime.StageRuntime rt, Object cannon) {
        StageBasis sb = (StageBasis) rt.stage;
        State st = rt.copyCatUfo;
        if (st.ufos.size() >= MAX_UFOS) {
            Logger.log("Copy Cat UFO: max concurrent UFOs reached, ignoring fire");
            return;
        }
        Ufo u = new Ufo();
        u.startPos = playerBasePos(sb);
        u.endPos = enemyBasePos(sb);
        u.pos = u.startPos;
        u.phase = PHASE_OUT;
        synchronized (st.ufos) {
            st.ufos.add(u);
        }
        Logger.log("Copy Cat UFO launched: active=" + st.ufos.size()
                + " start=" + u.startPos + " end=" + u.endPos);
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        State st = rt.copyCatUfo;
        ageScanHits(st);
        if (st.ufos.isEmpty()) return;
        StageBasis sb = (StageBasis) rt.stage;
        if (sb.ubase == null || sb.ubase.health <= 0L) {
            synchronized (st.ufos) {
                st.ufos.clear();
            }
            return;
        }
        float speedOut = clampSpeed(rt.config.ufoSpeedOut);
        float speedRet = speedOut * (float) Math.max(1.0, rt.config.ufoReturnSpeedMul);
        int spawnInterval = Math.max(1, rt.config.ufoSpawnInterval);
        int diveFrames = Math.max(1, (int) Math.round(rt.config.ufoDiveDuration));
        float coneHalf = coneHalfWorld(rt);

        synchronized (st.ufos) {
        for (Iterator<Ufo> it = st.ufos.iterator(); it.hasNext(); ) {
            Ufo u = it.next();
            u.animFrame++;
            if (u.signalFlash > 0f) u.signalFlash = Math.max(0f, u.signalFlash - 0.06f);
            float tiltTarget = (u.phase == PHASE_RETURN) ? 1f : 0f;
            u.tilt += (tiltTarget - u.tilt) * 0.2f;

            if (u.phase == PHASE_OUT) {
                u.pos -= speedOut;
                scan(st, sb, u, coneHalf);
                if (u.pos <= u.endPos) {
                    u.pos = u.endPos;
                    u.phase = PHASE_TURN;
                    u.turnTimer = TURN_FRAMES;
                }
            } else if (u.phase == PHASE_TURN) {
                if (--u.turnTimer <= 0) {
                    u.phase = PHASE_RETURN;
                    u.spawnTimer = 0;
                }
            } else if (u.phase == PHASE_RETURN) {

                u.pos += speedRet;
                if (u.pos >= u.startPos) {
                    u.pos = u.startPos;
                    u.phase = PHASE_SPAWN;
                    u.spawnTimer = 0;
                }
            } else if (u.phase == PHASE_SPAWN) {

                if (!u.queue.isEmpty()) {
                    if (u.spawnTimer <= 0) {
                        spawnClone(rt, sb, u.queue.remove(0));
                        u.spawnTimer = spawnInterval;
                        u.signalFlash = 1f;
                    } else {
                        u.spawnTimer--;
                    }
                } else {
                    u.phase = PHASE_DIVE;
                    u.diveTimer = diveFrames;
                }
            } else {
                if (--u.diveTimer <= 0) it.remove();
            }
        }
        }
    }

    private static void scan(State st, StageBasis sb, Ufo u, float coneHalf) {
        List<Entity> le = sb.le;
        for (int i = 0; i < le.size(); i++) {
            Entity e = le.get(i);
            if (!isScanTarget(e)) continue;
            if (u.scanned.containsKey(e)) continue;
            float halfW = enemyHalfWidth(e);
            if (Math.abs(e.pos - u.pos) <= coneHalf + halfW) {
                u.scanned.put(e, Boolean.TRUE);
                Snapshot snap = snapshot((EEnemy) e);
                if (snap != null) u.queue.add(snap);
                synchronized (st.scanHits) {
                    st.scanHits.add(new ScanHit(e));
                }
                trackBounds(e);
            }
        }
    }

    private static void ageScanHits(State st) {
        synchronized (st.scanHits) {
            if (st.scanHits.isEmpty()) return;
            for (Iterator<ScanHit> it = st.scanHits.iterator(); it.hasNext(); ) {
                ScanHit h = it.next();
                if (h.enemy != null && !h.enemy.dead && h.enemy.health > 0L) {
                    h.pos = h.enemy.pos;
                    h.layer = h.enemy.currentLayer;
                }
                h.age++;
                if (h.age >= SCAN_HIT_LIFE) {
                    untrackBounds(h.enemy);
                    it.remove();
                }
            }
        }
    }

    private static boolean isScanTarget(Entity e) {
        if (e == null || e.dead || e.health <= 0L) return false;
        if (e.dire != 1) return false;
        if (!(e instanceof EEnemy)) return false;
        try {
            if (e.isBase()) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    private static Snapshot snapshot(EEnemy e) {
        try {
            Enemy def = ((MaskEnemy) e.data).getPack();
            if (def == null) return null;

            float statScale = EntityAccess.isBoss(e) ? 0.5f : 1f;
            return new Snapshot(def, (float) e.mult, (float) e.mula, e.health, e.currentLayer, statScale);
        } catch (Throwable t) {
            Logger.err("Copy Cat UFO snapshot failed", t);
            return null;
        }
    }

    private static void spawnClone(CrazyRuntime.StageRuntime rt, StageBasis sb, Snapshot snap) {
        try {
            float pos = spawnPos(sb);
            int layer = Math.max(0, Math.min(9, snap.layer));
            float magnif = snap.mult * snap.statScale;
            float atkMagnif = snap.mula * snap.statScale;
            EEnemy clone = snap.def.getEntity(sb, null, magnif, atkMagnif, layer, layer, 0, 0);
            clone.added(-1, pos);
            try { clone.dire = -1; } catch (Throwable ignored) {}
            ConvertedRegistry.mark(clone);
            addSorted(sb, clone);
            try {
                long hp = Math.max(1L, Math.min(clone.maxH, (long) (snap.health * snap.statScale)));
                clone.health = hp;
            } catch (Throwable ignored) {}

            if (snap.statScale < 0.999f) CLONE_SCALE.put(clone, snap.statScale);
        } catch (Throwable t) {
            Logger.err("Copy Cat UFO clone spawn failed", t);
        }
    }

    public static float drawScaleFor(Object entity) {
        if (entity == null) return 1f;
        Float s = CLONE_SCALE.get(entity);
        return s == null ? 1f : s;
    }

    private static void addSorted(StageBasis sb, Entity e) {
        sb.le.add(e);
        Collections.sort(sb.le, new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                return Integer.compare(a.currentLayer, b.currentLayer);
            }
        });
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || gra == null) return;
        State st = rt.copyCatUfo;
        if (st.ufos.isEmpty()) return;

        resolveSprite(st);
        bakeTextures(st, rt.config.ufoTintHue);
        if (st.ufoAnim != null && !st.spriteFailed) {
            try {
                if (st.ufoAnim.done()) st.ufoAnim.setTime(0f);
                else st.ufoAnim.update(true);
                suppressDecorParts(st);
            } catch (Throwable t) {
                st.spriteFailed = true;
            }
        }
        if (!st.loggedDraw) {
            st.loggedDraw = true;
            Logger.log("Copy Cat UFO draw: graphics=" + gra.getClass().getName()
                    + " sprite=" + (st.spriteFailed ? "primitive" : "ufo-cat-5-1"));
        }

        boolean gl = gra.getClass().getName().contains("GLGraphics");
        float siz = BBPainterAccess.getSiz(bbpainter);

        measureSaucer(st, gra, siz * UFO_SCALE);
        float groundY = CrazyRender.groundY(bbpainter, 0);
        int hue = ((rt.config.ufoTintHue % 360) + 360) % 360;
        Color base = Color.getHSBColor(hue / 360f, 0.85f, 1.0f);
        Color hot = lerp(Color.WHITE, base, 0.5f);

        Ufo[] arr;
        synchronized (st.ufos) {
            arr = st.ufos.toArray(new Ufo[0]);
        }
        for (int i = 0; i < arr.length; i++) {
            Ufo u = arr[i];
            if (u == null) continue;
            try {
                drawUfo(rt, st, gra, gl, bbpainter, u, siz, groundY, base, hot);
            } catch (Throwable t) {
                Logger.err("Copy Cat UFO draw failed", t);
            }
        }
        drawScanHits(st, gra, gl, bbpainter, siz, hot);
    }

    private static void drawScanHits(State st, FakeGraphics g, boolean gl, Object bbpainter, float siz, Color hot) {
        ScanHit[] hits;
        synchronized (st.scanHits) {
            if (st.scanHits.isEmpty()) return;
            hits = st.scanHits.toArray(new ScanHit[0]);
        }
        for (int i = 0; i < hits.length; i++) {
            ScanHit h = hits[i];
            if (h == null) continue;
            try {
                drawScanHit(g, gl, st, h, bbpainter, siz, hot);
            } catch (Throwable t) {
                Logger.err("Copy Cat UFO scan-hit draw failed", t);
            }
        }
    }

    private static void drawScanHit(FakeGraphics g, boolean gl, State st, ScanHit h, Object bbpainter,
                                    float siz, Color hot) {
        float t = clamp01(h.age / (float) SCAN_HIT_LIFE);
        float fade = 1f - t;
        if (fade <= 0f) return;

        float cx, cy, rad;
        float[] box = freshSpriteBox(h.enemy);
        if (box != null) {
            cx = box[0];
            cy = box[1];
            rad = Math.max(box[2], box[3]);
        } else {
            float rootX = CrazyRender.screenX(bbpainter, h.pos);
            float rootY = CrazyRender.groundY(bbpainter, h.layer);
            cx = rootX; cy = rootY - 34f * siz; rad = 40f * siz;
            if (h.enemy != null) {
                try {
                    EntityAccess.SpriteBounds b = EntityAccess.estimateSpriteBounds(h.enemy, siz, rootX, rootY);
                    if (b != null) {
                        cx = b.centerX;
                        cy = b.centerY;
                        rad = Math.max(b.right - b.left, b.bottom - b.top) * 0.5f;
                    }
                } catch (Throwable ignored) {}
            }
        }
        rad = Math.max(16f * siz, rad);

        float rg = rad * 2f * (0.7f + 1.1f * t);
        glowAdd(g, st.ringTex, cx, cy, rg, rg, Math.round(170f * fade));
        float fl = rad * 2.4f * (1f - 0.5f * t);
        glowAdd(g, st.glowTex, cx, cy, fl, fl, Math.round(150f * fade * fade));

        float ex = rad * (1.35f - 0.30f * smooth(t));
        float arm = rad * 0.45f;
        float th = Math.max(1.5f, rad * 0.09f);
        int a = Math.round(220f * fade);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float bx = cx + sx * ex;
                float by = cy + sy * ex;
                colFill(g, gl, Math.min(bx, bx - sx * arm), by - th * 0.5f, arm, th, hot, a);
                colFill(g, gl, bx - th * 0.5f, Math.min(by, by - sy * arm), th, arm, hot, a);
            }
        }
    }

    private static final class BoxRec {
        float minX, minY, maxX, maxY, cx, cy;
        long timeMs;
    }

    private static final Map<Object, BoxRec> SPRITE_BOXES =
            Collections.synchronizedMap(new WeakHashMap<Object, BoxRec>());
    private static final Map<Object, Boolean> BOUNDS_TRACKED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<FakeImage, float[]> ALPHA_BOUNDS_CACHE =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, float[]>());
    private static final long BOX_MAX_AGE_MS = 150L;
    private static final int ALPHA_THRESHOLD = 40;

    public static boolean wantsSpriteBounds(Object entity) {
        return entity != null && !BOUNDS_TRACKED.isEmpty() && BOUNDS_TRACKED.containsKey(entity);
    }

    static void trackBounds(Object entity) {
        if (entity != null) BOUNDS_TRACKED.put(entity, Boolean.TRUE);
    }

    static void untrackBounds(Object entity) {
        if (entity != null) {
            BOUNDS_TRACKED.remove(entity);
            SPRITE_BOXES.remove(entity);
        }
    }

    public static void recordSpriteBounds(Object entity, float minX, float minY, float maxX, float maxY,
                                          float bodyCX, float bodyCY) {
        if (entity == null) return;
        float w = maxX - minX, h = maxY - minY;
        if (!(w > 2f) || !(h > 2f) || w >= 6000f || h >= 6000f) return;
        if (!(bodyCX >= minX && bodyCX <= maxX)) bodyCX = (minX + maxX) * 0.5f;
        if (!(bodyCY >= minY && bodyCY <= maxY)) bodyCY = (minY + maxY) * 0.5f;
        BoxRec r = SPRITE_BOXES.get(entity);
        if (r == null) {
            r = new BoxRec();
            SPRITE_BOXES.put(entity, r);
        }
        r.minX = minX; r.minY = minY; r.maxX = maxX; r.maxY = maxY;
        r.cx = bodyCX; r.cy = bodyCY; r.timeMs = System.currentTimeMillis();
    }

    public static void recordSpriteParts(Object entity, List<BoundsRecorder.SpritePart> parts) {
        if (entity == null || parts == null || parts.isEmpty()) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float sumA = 0f, sumAX = 0f, sumAY = 0f;
        boolean any = false;
        int limit = Math.min(64, parts.size());
        for (int i = 0; i < limit; i++) {
            BoundsRecorder.SpritePart p = parts.get(i);
            if (p == null || p.image == null || p.matrix == null || p.w == 0f || p.h == 0f) continue;
            float[] ab = alphaBoundsUV(p.image);
            if (ab == null) continue;
            int iw, ih;
            try {
                iw = p.image.getWidth();
                ih = p.image.getHeight();
            } catch (Throwable ignored) {
                continue;
            }
            if (iw <= 0 || ih <= 0) continue;
            float lx0 = p.x + ab[0] / iw * p.w;
            float ly0 = p.y + ab[1] / ih * p.h;
            float lx1 = p.x + (ab[2] + 1f) / iw * p.w;
            float ly1 = p.y + (ab[3] + 1f) / ih * p.h;
            float[] m = p.matrix;
            float pMinX = Float.MAX_VALUE, pMinY = Float.MAX_VALUE, pMaxX = -Float.MAX_VALUE, pMaxY = -Float.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                float lx = (c == 0 || c == 3) ? lx0 : lx1;
                float ly = (c < 2) ? ly0 : ly1;
                float sx = m[0] * lx + m[1] * ly + m[2];
                float sy = m[3] * lx + m[4] * ly + m[5];
                if (sx < pMinX) pMinX = sx;
                if (sx > pMaxX) pMaxX = sx;
                if (sy < pMinY) pMinY = sy;
                if (sy > pMaxY) pMaxY = sy;
            }
            if (pMinX < minX) minX = pMinX;
            if (pMinY < minY) minY = pMinY;
            if (pMaxX > maxX) maxX = pMaxX;
            if (pMaxY > maxY) maxY = pMaxY;
            float area = (pMaxX - pMinX) * (pMaxY - pMinY);
            if (area > 0f) {
                sumA += area;
                sumAX += area * (pMinX + pMaxX) * 0.5f;
                sumAY += area * (pMinY + pMaxY) * 0.5f;
            }
            any = true;
        }
        if (!any || sumA <= 0f) return;
        recordSpriteBounds(entity, minX, minY, maxX, maxY, sumAX / sumA, sumAY / sumA);
    }

    private static float[] freshSpriteBox(Object entity) {
        if (entity == null) return null;
        BoxRec r = SPRITE_BOXES.get(entity);
        if (r == null) return null;
        if (System.currentTimeMillis() - r.timeMs > BOX_MAX_AGE_MS) return null;
        return new float[]{r.cx, r.cy, (r.maxX - r.minX) * 0.5f, (r.maxY - r.minY) * 0.5f};
    }

    private static float[] alphaBoundsUV(FakeImage img) {
        if (img == null) return null;
        float[] cached = ALPHA_BOUNDS_CACHE.get(img);
        if (cached != null) return cached.length == 0 ? null : cached;
        float[] result;
        try {
            int w = Math.max(0, img.getWidth());
            int h = Math.max(0, img.getHeight());
            int minX = w, minY = h, maxX = -1, maxY = -1;
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
            result = (maxX >= minX && maxY >= minY) ? new float[]{minX, minY, maxX, maxY} : new float[0];
        } catch (Throwable ignored) {
            result = new float[0];
        }
        ALPHA_BOUNDS_CACHE.put(img, result);
        return result.length == 0 ? null : result;
    }

    private static void drawUfo(CrazyRuntime.StageRuntime rt, State st, FakeGraphics g, boolean gl, Object bbpainter,
                                Ufo u, float siz, float groundY, Color base, Color hot) {
        float scale = siz * UFO_SCALE;
        float altitude = (float) rt.config.ufoAltitude * siz;
        float halfW = SAUCER_W * 0.5f * scale;
        float halfH = SAUCER_H * 0.5f * scale;

        float bob = 0f;
        if (u.phase == PHASE_OUT) {
            bob = (float) Math.sin(u.animFrame * (float) rt.config.ufoBobFrequency)
                    * (float) rt.config.ufoBobAmplitude * siz;
        }
        float shake = 0f;
        if (u.phase == PHASE_SPAWN) {
            float amp = (!u.queue.isEmpty() ? 3.0f : 1.2f) + 4.0f * u.signalFlash;
            shake = (float) Math.sin(u.animFrame * 1.9f) * amp * siz;
        }

        float feetX = CrazyRender.screenX(bbpainter, u.pos) + shake;
        float feetY = groundY - altitude + bob;

        float bodyAlpha = 1f;
        if (u.phase == PHASE_DIVE) {
            float t = 1f - u.diveTimer / (float) Math.max(1, (int) Math.round(rt.config.ufoDiveDuration));

            float backX = baseBackScreenX(bbpainter, (StageBasis) rt.stage, siz, feetX);
            feetX = feetX + (backX - feetX) * smooth(t);
            feetY += t * (altitude + 90f * siz);
            bodyAlpha = Math.max(0f, 1f - t);
        }

        float cx, cy, hw, hh, bottomY;
        if (st.saucerMeasured) {
            float rcx = feetX + st.rCenterX * scale;
            float rcy = feetY + st.rCenterY * scale;
            float rhw = st.rHalfW * scale;
            float rhh = st.rHalfH * scale;

            float left = rcx - rhw + SAUCER_INSET_L * (2f * rhw);
            float right = rcx + rhw - SAUCER_INSET_R * (2f * rhw);
            float top = rcy - rhh + SAUCER_INSET_T * (2f * rhh);
            float bottom = rcy + rhh - SAUCER_INSET_B * (2f * rhh);
            cx = (left + right) * 0.5f;
            cy = (top + bottom) * 0.5f;
            hw = (right - left) * 0.5f;
            hh = (bottom - top) * 0.5f;
            bottomY = bottom;
        } else {
            cx = feetX;
            cy = feetY - halfH;
            hw = halfW;
            hh = halfH;
            bottomY = feetY;
        }
        float coneW = Math.max(8f, hw * 2f * (float) Math.max(0.1, rt.config.ufoConeWidthMul));
        float intensity = bodyAlpha * (0.85f + 0.15f * (float) Math.sin(u.animFrame * 0.2f));

        drawTrail(g, st, u, cx, cy, hw, u.phase == PHASE_RETURN ? 1.6f : 1.0f, bodyAlpha);

        boolean beam = (u.phase == PHASE_OUT || u.phase == PHASE_TURN);
        if (beam) {

            drawGroundPool(g, st, u, cx, groundY, coneW, base, hot, intensity);
            drawCylinder(g, gl, st, cx, bottomY, groundY, coneW, base, hot, intensity);
            drawScanRings(g, st, u, cx, bottomY, groundY, coneW, intensity);
            drawMotes(g, st, u, cx, bottomY, groundY, coneW, intensity);
        }

        drawThrusters(g, st, u, cx, cy, hw, hh, base, hot, bodyAlpha);

        float angle = u.tilt * RETURN_TILT;

        drawAura(g, st, u, cx, cy, hw, hh, bodyAlpha);
        drawBody(st, g, gl, feetX, feetY, scale, Math.round(255f * bodyAlpha), base, hot, hw, hh, angle, cx, cy);
        drawNeonRim(g, st, u, cx, cy, hw, hh, bodyAlpha);

        drawSparkles(g, gl, st, u, cx, cy, hw, hh, hot, bodyAlpha);
        if (u.phase == PHASE_RETURN) drawSpeedLines(g, gl, u, cx, cy, hw, hh, hot, bodyAlpha);

        if (u.phase == PHASE_SPAWN && u.signalFlash > 0.02f) {
            float py = bottomY + (groundY - bottomY) * (1f - u.signalFlash);
            glowAdd(g, st.glowTex, cx, py, coneW * 0.5f, coneW * 0.5f, Math.round(200f * u.signalFlash));
            glowAdd(g, st.glowTex, cx, groundY, coneW * 2.2f * u.signalFlash, coneW * 0.6f,
                    Math.round(170f * u.signalFlash));
        }

        if (DEBUG_BOX) drawDebugBox(g, gl, cx, cy, hw, hh, bottomY, feetX, feetY);
    }

    private static void drawDebugBox(FakeGraphics g, boolean gl, float cx, float cy, float hw, float hh,
                                     float bottomY, float feetX, float feetY) {
        Color red = Color.RED, yellow = Color.YELLOW, cyan = Color.CYAN;

        colFill(g, gl, cx - hw, cy - hh, hw * 2f, 2f, red, 255);
        colFill(g, gl, cx - hw, cy + hh, hw * 2f, 2f, red, 255);
        colFill(g, gl, cx - hw, cy - hh, 2f, hh * 2f, red, 255);
        colFill(g, gl, cx + hw, cy - hh, 2f, hh * 2f, red, 255);

        colFill(g, gl, cx - 8f, cy - 1f, 16f, 2f, red, 255);
        colFill(g, gl, cx - 1f, cy - 8f, 2f, 16f, red, 255);

        colFill(g, gl, cx - hw, bottomY - 1f, hw * 2f, 2f, yellow, 255);

        colFill(g, gl, feetX - 5f, feetY - 5f, 10f, 10f, cyan, 255);
    }

    private static void drawNeonRim(FakeGraphics g, State st, Ufo u, float cx, float cy,
                                    float hw, float hh, float bodyAlpha) {
        float breath = 0.9f + 0.1f * (float) Math.sin(u.animFrame * 0.18f);
        glowAdd(g, st.ringTex, cx, cy, hw * 2.8f * breath, hh * 2.8f * breath, Math.round(54f * bodyAlpha));
        glowAdd(g, st.ringTex, cx, cy, hw * 2.5f * breath, hh * 2.5f * breath,
                Math.round((85f + 20f * (float) Math.sin(u.animFrame * 0.3f)) * bodyAlpha));
        FakeImage node = st.sparkTex != null ? st.sparkTex : st.glowTex;
        for (int k = 0; k < RIM_NODES; k++) {
            float ang = u.animFrame * 0.05f + k * (6.2832f / RIM_NODES);
            float nx = cx + (float) Math.cos(ang) * hw * 1.05f;
            float ny = cy + (float) Math.sin(ang) * hh * 1.0f;
            glowAdd(g, node, nx, ny, hw * 0.24f, hh * 0.24f, Math.round(170f * bodyAlpha));
        }
    }

    private static void drawAura(FakeGraphics g, State st, Ufo u, float cx, float cy,
                                 float hw, float hh, float bodyAlpha) {
        float aura = 0.85f + 0.15f * (float) Math.sin(u.animFrame * 0.1f);
        glowAdd(g, st.glowTex, cx, cy, hw * 1.8f * aura, hh * 1.8f * aura, Math.round(70f * bodyAlpha));
    }

    private static void drawTrail(FakeGraphics g, State st, Ufo u, float cx, float cy, float hw,
                                  float boost, float bodyAlpha) {
        if (st.glowTex == null) return;
        if (u.histX == null) { u.histX = new float[TRAIL_LEN]; u.histY = new float[TRAIL_LEN]; }
        for (int i = TRAIL_LEN - 1; i > 0; i--) { u.histX[i] = u.histX[i - 1]; u.histY[i] = u.histY[i - 1]; }
        u.histX[0] = cx; u.histY[0] = cy;
        if (u.histCount < TRAIL_LEN) u.histCount++;
        for (int i = u.histCount - 1; i >= 1; i--) {
            for (int s = 0; s < 2; s++) {
                float t = s * 0.5f;
                float px = u.histX[i] + (u.histX[i - 1] - u.histX[i]) * t;
                float py = u.histY[i] + (u.histY[i - 1] - u.histY[i]) * t;
                float f = 1f - (i - t) / (float) TRAIL_LEN;
                float e = smooth(clamp01(f));
                float sz = Math.max(2f, hw * TRAIL_SIZE * e);
                int aOut = Math.round(TRAIL_ALPHA * e * e * boost * bodyAlpha);
                glowAdd(g, st.glowTex, px, py, sz, sz, aOut);
                glowAdd(g, st.glowTex, px, py, sz * 0.4f, sz * 0.4f, Math.round(aOut * 1.1f));
            }
        }
    }

    private static void drawThrusters(FakeGraphics g, State st, Ufo u, float cx, float cy, float hw, float hh,
                                      Color base, Color hot, float bodyAlpha) {
        if (st.glowTex == null) return;
        boolean ret = u.phase == PHASE_RETURN;
        float backDir = ret ? -1f : 1f;
        float pulse = 0.7f + 0.3f * (float) Math.sin(u.animFrame * 0.7f);
        float jetLen = hh * (ret ? 1.8f : 0.9f) * pulse;
        float baseY = cy + hh * 0.45f;
        float[] off = {-0.38f, 0f, 0.38f};
        for (int j = 0; j < off.length; j++) {
            float flick = 0.8f + 0.2f * (float) Math.sin(u.animFrame * (1.3f + j * 0.4f) + j);
            float jx = cx + off[j] * hw + (ret ? backDir * hw * 0.18f : 0f);
            for (int k = 0; k < 5; k++) {
                float f = k / 5f;
                float fxp = jx + backDir * jetLen * 0.45f * f;
                float fyp = baseY + jetLen * f * flick;
                float sz = Math.max(2f, hw * 0.52f * (1f - f * 0.62f));
                int a = Math.round(160f * (1f - f) * pulse * flick * bodyAlpha);
                glowAdd(g, st.glowTex, fxp, fyp, sz, sz * 1.5f, a);
                if (k <= 1) glowAdd(g, st.glowTex, fxp, fyp, sz * 0.45f, sz * 0.7f, Math.round(a * 1.2f));
            }
        }
        if (ret) {
            glowAdd(g, st.ringTex, cx + backDir * hw * 1.2f, cy, hw * (1.4f + 0.6f * pulse), hh * 1.2f,
                    Math.round(60f * bodyAlpha));
        }
    }

    private static void drawSparkles(FakeGraphics g, boolean gl, State st, Ufo u, float cx, float cy,
                                     float hw, float hh, Color hot, float bodyAlpha) {
        FakeImage tex = st.sparkTex != null ? st.sparkTex : st.glowTex;
        if (tex == null) return;
        int glint = (u.animFrame / 18) % SPARKLE_COUNT;
        for (int k = 0; k < SPARKLE_COUNT; k++) {
            float ang = u.animFrame * 0.04f + k * (6.2832f / SPARKLE_COUNT);
            float ox = (float) Math.cos(ang) * hw * 1.18f;
            float oy = (float) Math.sin(ang) * hh * 1.0f;
            float tw = Math.abs((float) Math.sin(u.animFrame * 0.15f + k * 1.7f));
            int a = Math.round(150f * tw * bodyAlpha);
            float sz = Math.max(3f, hw * 0.20f * (0.6f + 0.4f * tw));
            if (k == glint) {
                sz *= 1.8f;
                a = Math.round(210f * bodyAlpha);
            }
            glowAdd(g, tex, cx + ox, cy + oy, sz, sz, a);
        }
    }

    private static void drawSpeedLines(FakeGraphics g, boolean gl, Ufo u, float cx, float cy, float hw, float hh,
                                       Color hot, float bodyAlpha) {
        float dir = -1f;
        float th = Math.max(1.5f, hh * 0.07f);
        for (int k = 0; k < SPEED_LINE_COUNT; k++) {
            float ly = cy + (k - (SPEED_LINE_COUNT - 1) * 0.5f) * hh * 0.45f;
            float len = hw * (1.0f + 0.6f * (float) Math.sin(u.animFrame * 0.5f + k));
            float lx = cx + dir * hw * (1.4f + (k % 2) * 0.5f);
            int a = Math.round(170f * (0.45f + 0.55f
                    * Math.abs((float) Math.sin(u.animFrame * 0.8f + k * 2f))) * bodyAlpha);
            float x0 = Math.min(lx, lx + dir * len);
            colFill(g, gl, x0, ly - th * 0.5f, len, th, hot, a);
            colFill(g, gl, x0, ly - th * 0.25f, len, Math.max(1f, th * 0.5f), Color.WHITE, Math.round(a * 0.7f));
        }
    }

    private static void drawCylinder(FakeGraphics g, boolean gl, State st, float cx, float topY, float groundY,
                                     float coneW, Color base, Color hot, float intensity) {
        float h = groundY - topY;
        if (h < 2f) return;
        float halfW = coneW * 0.5f;

        colFill(g, gl, cx - halfW, topY, coneW, h, base, Math.round(16f * intensity));
        colFill(g, gl, cx - halfW * 0.62f, topY, halfW * 1.24f, h, base, Math.round(22f * intensity));
        colFill(g, gl, cx - halfW * 0.26f, topY, halfW * 0.52f, h, hot, Math.round(30f * intensity));

        FakeImage tex = st.glowTex;
        if (tex != null) {
            float gd = coneW * 1.2f;
            float step = Math.max(8f, coneW * 0.42f);
            int n = Math.max(1, Math.round(h / step));
            try {
                g.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, Math.round(72f * intensity))), 1);
                for (int i = 0; i <= n; i++) {
                    float y = topY + h * i / n;
                    g.drawImage(tex, cx - gd / 2f, y - gd / 2f, gd, gd);
                }
            } finally {
                g.setComposite(FakeGraphics.DEF, 0, 0);
            }
        }
    }

    private static void drawScanRings(FakeGraphics g, State st, Ufo u, float cx, float topY, float groundY,
                                      float coneW, float intensity) {
        if (st.ringTex == null) return;
        float h = groundY - topY;
        for (int k = 0; k < 3; k++) {
            float p = ((u.animFrame * 0.018f + k / 3f) % 1f);
            float ry = topY + p * h;
            float fade = (float) Math.sin(p * Math.PI);
            glowAdd(g, st.ringTex, cx, ry, coneW * 1.1f, coneW * 0.34f, Math.round(120f * fade * intensity));
        }
    }

    private static void drawMotes(FakeGraphics g, State st, Ufo u, float cx, float topY, float groundY,
                                  float coneW, float intensity) {
        if (st.glowTex == null) return;
        float h = groundY - topY;
        float halfW = coneW * 0.5f;
        float dot = Math.max(3f, coneW * 0.13f);
        for (int k = 0; k < 9; k++) {
            float seed = (k * 0.1397f);
            float p = 1f - ((u.animFrame * 0.012f + seed) % 1f);
            float my = topY + p * h;
            float mx = cx + (float) Math.sin(k * 1.7f + u.animFrame * 0.05f) * halfW * 0.72f;
            float fade = (float) Math.sin((1f - p) * Math.PI);
            glowAdd(g, st.glowTex, mx, my, dot, dot, Math.round(150f * fade * intensity));
        }
    }

    private static void drawGroundPool(FakeGraphics g, State st, Ufo u, float cx, float groundY,
                                       float coneW, Color base, Color hot, float intensity) {
        glowAdd(g, st.glowTex, cx, groundY, coneW * 2.0f, coneW * 0.55f, Math.round(140f * intensity));
        glowAdd(g, st.glowTex, cx, groundY, coneW * 0.9f, coneW * 0.3f, Math.round(150f * intensity));
        if (st.ringTex != null) {
            float rp = (u.animFrame * 0.03f) % 1f;
            glowAdd(g, st.ringTex, cx, groundY, coneW * (0.6f + 1.7f * rp), coneW * (0.16f + 0.5f * rp),
                    Math.round(120f * (1f - rp) * intensity));
        }
    }

    private static void measureSaucer(State st, FakeGraphics g, float scale) {
        if (st.ufoAnim == null || st.spriteFailed || scale <= 0.001f) return;
        FakeTransform old = pushIdentity(g);
        P p = null;
        BoundsRec rec = new BoundsRec(g, true);
        try {
            p = P.newP(0f, 0f);
            st.ufoAnim.draw(rec, p, scale);
            if (rec.any) {
                st.rCenterX = (rec.minX + rec.maxX) * 0.5f / scale;
                st.rCenterY = (rec.minY + rec.maxY) * 0.5f / scale;
                st.rHalfW = (rec.maxX - rec.minX) * 0.5f / scale;
                st.rHalfH = (rec.maxY - rec.minY) * 0.5f / scale;
                st.rBottom = rec.maxY / scale;
                st.saucerMeasured = true;
            }
        } catch (Throwable t) {
            st.spriteFailed = true;
        } finally {
            if (p != null) {
                try { P.delete(p); } catch (Throwable ignored) {}
            }
            resetComposite(g);
            popTransform(g, old);
        }
    }

    private static void drawBody(State st, FakeGraphics g, boolean gl, float px, float py, float scale,
                                 int alpha, Color base, Color hot, float halfW, float halfH,
                                 float angle, float pivotX, float pivotY) {
        boolean drew = false;
        if (st.ufoAnim != null && !st.spriteFailed) {
            FakeTransform old = pushIdentity(g);
            boolean rot = Math.abs(angle) > 0.001f;
            if (rot) {
                g.translate(pivotX, pivotY);
                g.rotate(angle);
                g.translate(-pivotX, -pivotY);
            }
            P p = null;
            try {
                g.setComposite(FakeGraphics.TRANS, Math.max(0, Math.min(255, alpha)), 0);
                p = P.newP(px, py);
                st.ufoAnim.draw(g, p, scale);
                drew = true;
            } catch (Throwable t) {
                st.spriteFailed = true;
            } finally {
                if (p != null) {
                    try { P.delete(p); } catch (Throwable ignored) {}
                }
                resetComposite(g);
                popTransform(g, old);
            }
        }
        if (!drew) {

            float cx = px;
            float cy = py - halfH;
            fillEllipse(g, gl, cx, cy, halfW, halfH * 0.55f, base.darker(), alpha);
            fillEllipse(g, gl, cx, cy - halfH * 0.45f, halfW * 0.5f, halfH * 0.55f, hot, alpha);
        }
    }

    private static void glowAdd(FakeGraphics g, FakeImage tex, float cx, float cy, float w, float h, int alpha) {
        if (tex == null || alpha <= 1 || w < 2f || h < 2f) return;
        try {
            g.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, alpha)), 1);
            g.drawImage(tex, cx - w / 2f, cy - h / 2f, w, h);
        } catch (Throwable ignored) {
        } finally {
            g.setComposite(FakeGraphics.DEF, 0, 0);
        }
    }

    private static void colFill(FakeGraphics g, boolean gl, float x, float y, float w, float h, Color c, int alpha) {
        if (alpha <= 0 || w < 1f || h < 1f) return;
        int a = Math.max(0, Math.min(255, alpha));
        if (gl) {
            g.colRect(x, y, w, h, c.getRed(), c.getGreen(), c.getBlue(), a);
        } else {
            try {
                g.setComposite(FakeGraphics.TRANS, a, 0);
                g.setColor(c.getRed(), c.getGreen(), c.getBlue());
                g.fillRect(Math.round(x), Math.round(y), Math.max(1, Math.round(w)), Math.max(1, Math.round(h)));
            } finally {
                g.setComposite(FakeGraphics.DEF, 0, 0);
            }
        }
    }

    private static void fillEllipse(FakeGraphics g, boolean gl, float cx, float cy, float rx, float ry, Color c, int alpha) {
        if (alpha <= 0 || rx <= 0f || ry <= 0f) return;
        int a = Math.max(0, Math.min(255, alpha));
        int iy = Math.max(1, Math.round(ry));
        int ix = Math.max(1, Math.round(rx));
        if (!gl) {
            g.setComposite(FakeGraphics.TRANS, a, 0);
            g.setColor(c.getRed(), c.getGreen(), c.getBlue());
        }
        try {
            for (int dy = -iy; dy <= iy; dy++) {
                float yy = dy / (float) iy;
                int span = Math.round(ix * (float) Math.sqrt(Math.max(0f, 1f - yy * yy)));
                if (span <= 0) continue;
                if (gl) g.colRect(cx - span, cy + dy, span * 2 + 1, 1, c.getRed(), c.getGreen(), c.getBlue(), a);
                else g.fillRect(Math.round(cx) - span, Math.round(cy) + dy, span * 2 + 1, 1);
            }
        } finally {
            if (!gl) g.setComposite(FakeGraphics.DEF, 0, 0);
        }
    }

    private static void resolveSprite(State st) {
        if (st.spriteResolved) return;
        st.spriteResolved = true;
        try {
            List<Unit> units = UserProfile.getBCData().units.getList();
            if (units != null && UFO_UNIT_ID < units.size()) {
                Unit u = units.get(UFO_UNIT_ID);
                if (u != null && u.forms != null) {
                    int fid = Math.min(UFO_FORM_ID, u.forms.length - 1);
                    Form form = u.forms[fid];
                    if (form != null) {
                        st.ufoForm = form;
                        EAnimU anim = form.getEAnim(AnimU.UType.WALK);
                        if (anim == null) anim = form.getEAnim(AnimU.UType.IDLE);
                        if (anim != null) {
                            anim.setTime(0f);
                            st.ufoAnim = anim;
                            Logger.log("Copy Cat UFO sprite loaded: unit " + UFO_UNIT_ID + " form " + fid);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.err("Copy Cat UFO sprite load failed (using primitive saucer)", t);
        }
        st.spriteFailed = true;
    }

    private static void suppressDecorParts(State st) {
        EAnimU anim = st.ufoAnim;
        if (anim == null) return;
        try {
            EPart[] parts = anim.ent;
            if (parts == null) return;
            for (int i = 0; i < parts.length; i++) {
                EPart p = parts[i];
                if (p == null) continue;
                int img = Math.round(p.getValRaw(2));
                if (img == SHADOW_IMG_CUT || img == DUMMY_IMG_CUT) {
                    p.alter(12, 0f);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void bakeTextures(State st, int hueRaw) {
        int hue = ((hueRaw % 360) + 360) % 360;
        if (st.sparkTex == null && ImageBuilder.builder != null) {
            try { st.sparkTex = bakeSpark(); } catch (Throwable ignored) {}
        }
        if (st.bakedHue == hue && st.glowTex != null && st.ringTex != null) return;
        try {
            if (ImageBuilder.builder == null) return;
            Color base = Color.getHSBColor(hue / 360f, 0.85f, 1.0f);
            Color hot = lerp(Color.WHITE, base, 0.5f);
            Color deep = base.darker();
            FakeImage glow = bakeRadialGlow(Color.WHITE, hot, deep);
            FakeImage ring = bakeRing(base);
            if (glow != null && ring != null) {
                st.glowTex = glow;
                st.ringTex = ring;
                st.bakedHue = hue;
                Logger.log("Copy Cat UFO textures baked hue=" + hue);
            }
        } catch (Throwable t) {
            Logger.err("Copy Cat UFO texture bake failed", t);
        }
    }

    private static FakeImage bakeRadialGlow(Color c0, Color c1, Color c2) {
        final int N = 64;
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float dx = (x - c) / (N / 2f), dy = (y - c) / (N / 2f);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                Color bse = (d < 0.45f)
                        ? lerp(c0, c1, smooth(d / 0.45f))
                        : lerp(c1, c2, smooth(clamp01((d - 0.45f) / 0.55f)));
                float env = clamp01(1f - d);
                env = env * env;
                int r = Math.round(bse.getRed() * env);
                int gg = Math.round(bse.getGreen() * env);
                int b = Math.round(bse.getBlue() * env);

                int a = clamp255(255f * env);
                img.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return img;
    }

    private static FakeImage bakeRing(Color base) {
        final int N = 64;
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float d = (float) Math.sqrt((x - c) * (x - c) + (y - c) * (y - c)) - 23f;
                float env = (float) Math.exp(-(d * d) / (2f * 3.1f * 3.1f));
                float core = (float) Math.exp(-(d * d) / (2f * 1.1f * 1.1f));
                int r = clamp255(base.getRed() * env + 255f * core * 0.85f);
                int g = clamp255(base.getGreen() * env + 255f * core * 0.85f);
                int b = clamp255(base.getBlue() * env + 255f * core * 0.85f);
                int a = clamp255(255f * clamp01(env + core));
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static FakeImage bakeSpark() {
        final int N = 32;
        FakeImage img = ImageBuilder.builder.build(N, N);
        if (img == null) return null;
        float c = (N - 1) / 2f;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float fx = x - c, fy = y - c;
                float armH = (float) (Math.pow(Math.max(0f, 1f - Math.abs(fx) / 14f), 2.6)
                        * Math.pow(Math.max(0f, 1f - Math.abs(fy) / 1.9f), 2.0));
                float armV = (float) (Math.pow(Math.max(0f, 1f - Math.abs(fy) / 11f), 2.6)
                        * Math.pow(Math.max(0f, 1f - Math.abs(fx) / 1.9f), 2.0));
                float rr = (float) Math.sqrt(fx * fx + fy * fy);
                float glow = (float) Math.pow(Math.max(0f, 1f - rr / 5.5f), 2.0);
                float i = clamp01(Math.max(armH, Math.max(armV, glow)));
                int v = clamp255(255f * i);
                int a = clamp255(255f * i);
                img.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static float coneHalfWorld(CrazyRuntime.StageRuntime rt) {
        float mul = (float) Math.max(0.1, rt.config.ufoConeWidthMul);

        return SAUCER_W * 0.5f * UFO_SCALE / 0.32f * mul;
    }

    private static float playerBasePos(StageBasis sb) {
        try {
            if (sb.ubase != null) return sb.ubase.pos;
        } catch (Throwable ignored) {}
        try { return sb.st.len - 800f; } catch (Throwable ignored) {}
        return 0f;
    }

    private static float enemyBasePos(StageBasis sb) {
        try {
            if (sb.ebase != null) return Math.max(800f, sb.ebase.pos);
        } catch (Throwable ignored) {}
        return 800f;
    }

    private static float spawnPos(StageBasis sb) {
        try { return sb.st.len - 700f; } catch (Throwable ignored) {}
        try { return sb.ubase.pos - 160f; } catch (Throwable ignored) {}
        return 0f;
    }

    private static float baseBackScreenX(Object bbpainter, StageBasis sb, float siz, float fallback) {
        try {
            Object ubase = sb.ubase;
            if (ubase == null) return fallback;
            float rootX = CrazyRender.screenX(bbpainter, sb.ubase.pos);
            float rootY = CrazyRender.groundY(bbpainter, 0);
            EntityAccess.SpriteBounds b = EntityAccess.estimateSpriteBounds(ubase, siz, rootX, rootY);
            if (b != null) return b.right;
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static float enemyHalfWidth(Entity e) {
        try {
            int w = e.data.getWidth();
            if (w > 0) return w * 0.5f;
        } catch (Throwable ignored) {}
        return ENEMY_HALF_FALLBACK;
    }

    private static float clampSpeed(double s) {
        if (Double.isNaN(s) || s < 1.0) return 1f;
        return (float) Math.min(400.0, s);
    }

    private static Color lerp(Color a, Color b, float t) {
        t = clamp01(t);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int clamp255(float v) {
        return v < 0f ? 0 : (v > 255f ? 255 : Math.round(v));
    }

    private static FakeTransform pushIdentity(FakeGraphics gra) {
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

    private static float[] transformData(FakeTransform t) {
        if (t == null) return null;
        try {
            Field f = transformDataField;
            if (f == null || f.getDeclaringClass() != t.getClass()) {
                f = t.getClass().getDeclaredField("data");
                f.setAccessible(true);
                transformDataField = f;
            }
            Object v = f.get(t);
            if (v instanceof float[] && ((float[]) v).length >= 6) return (float[]) v;
        } catch (Throwable ignored) {}

        try {
            Object at = t.getClass().getMethod("getAT").invoke(t);
            if (at instanceof java.awt.geom.AffineTransform) {
                java.awt.geom.AffineTransform a = (java.awt.geom.AffineTransform) at;
                return new float[]{
                        (float) a.getScaleX(), (float) a.getShearX(), (float) a.getTranslateX(),
                        (float) a.getShearY(), (float) a.getScaleY(), (float) a.getTranslateY()};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final class BoundsRec implements FakeGraphics {
        private final FakeGraphics g;
        private final boolean measureOnly;
        boolean any;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        BoundsRec(FakeGraphics g) { this(g, false); }
        BoundsRec(FakeGraphics g, boolean measureOnly) { this.g = g; this.measureOnly = measureOnly; }

        private void mark(float x, float y, float w, float h) {
            FakeTransform t = g.getTransform();
            float[] m = transformData(t);
            try { g.delete(t); } catch (Throwable ignored) {}
            if (m == null) return;
            for (int c = 0; c < 4; c++) {
                float lx = (c == 0 || c == 3) ? x : x + w;
                float ly = (c < 2) ? y : y + h;
                float sx = m[0] * lx + m[1] * ly + m[2];
                float sy = m[3] * lx + m[4] * ly + m[5];
                if (sx < minX) minX = sx;
                if (sx > maxX) maxX = sx;
                if (sy < minY) minY = sy;
                if (sy > maxY) maxY = sy;
                any = true;
            }
        }

        @Override public void drawImage(FakeImage i, float x, float y, float w, float h) {
            mark(x, y, w, h);
            if (!measureOnly) g.drawImage(i, x, y, w, h);
        }
        @Override public void drawImage(FakeImage i, float x, float y) {
            float w = 0f, h = 0f;
            if (i != null) { try { w = i.getWidth(); h = i.getHeight(); } catch (Throwable ignored) {} }
            mark(x, y, w, h);
            if (!measureOnly) g.drawImage(i, x, y);
        }
        @Override public void colRect(float a, float b, float c, float d, int e, int f, int gg, int h) { g.colRect(a, b, c, d, e, f, gg, h); }
        @Override public void delete(FakeTransform t) { g.delete(t); }
        @Override public void drawLine(float a, float b, float c, float d) { g.drawLine(a, b, c, d); }
        @Override public void drawOval(float a, float b, float c, float d) { g.drawOval(a, b, c, d); }
        @Override public void drawRect(float a, float b, float c, float d) { g.drawRect(a, b, c, d); }
        @Override public void fillOval(float a, float b, float c, float d) { g.fillOval(a, b, c, d); }
        @Override public void fillRect(float a, float b, float c, float d) { g.fillRect(a, b, c, d); }
        @Override public FakeTransform getTransform() { return g.getTransform(); }
        @Override public void gradRect(float a, float b, float c, float d, float e, float f, int[] gg, float h, float i, int[] j) { g.gradRect(a, b, c, d, e, f, gg, h, i, j); }
        @Override public void gradRectAlpha(float a, float b, float c, float d, float e, float f, int gg, int[] h, float i, float j, int k, int[] l) { g.gradRectAlpha(a, b, c, d, e, f, gg, h, i, j, k, l); }
        @Override public void rotate(float a) { g.rotate(a); }
        @Override public void scale(float a, float b) { g.scale(a, b); }
        @Override public void setColor(int a) { g.setColor(a); }
        @Override public void setColor(int a, int b, int c) { g.setColor(a, b, c); }
        @Override public void setComposite(int a, int b, int c) { g.setComposite(a, b, c); }
        @Override public void setRenderingHint(int a, int b) { g.setRenderingHint(a, b); }
        @Override public void setTransform(FakeTransform t) { g.setTransform(t); }
        @Override public void translate(float a, float b) { g.translate(a, b); }
    }
}
