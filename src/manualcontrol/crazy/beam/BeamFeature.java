package manualcontrol.crazy.beam;

import common.battle.StageBasis;
import common.battle.attack.AttackCanon;
import common.battle.entity.AbEntity;
import common.battle.entity.Cannon;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.battle.data.MaskUnit;
import common.pack.UserProfile;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.system.fake.ImageBuilder;
import common.util.Data;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Trait;
import common.util.unit.Unit;
import manualcontrol.ConvertedRegistry;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyConfig;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.unit.BossItemFeature;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.BBPainterAccess;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class BeamFeature {

    private static final int HYPNOSIS_FADE = 60;
    private static final int EVOLUTION_FADE = 60;
    private static final int HYPNOSIS_FRAMES = 115 + HYPNOSIS_FADE;
    private static final int EVOLUTION_FRAMES = 84 + EVOLUTION_FADE;
    private static final int KAME_FRAMES = 600;

    private static final int KAME_TAPER_FRAMES = 48;

    private static final float KAME_SPLASH_LIFE = 21f;
    private static final int KAME_TICK = 5;
    private static final int EVOLUTION_MORPH_FRAMES = 60;
    private static final int EVOLUTION_LIFT_LAYERS = 22;
    private static final int TARGETS_PER_TICK = 15;

    private static final int VOLLEY_CHARGE_FRAMES = 6;
    private static final int VOLLEY_MUZZLE_FLASH = 6;
    private static final int VOLLEY_SPAWN_GAP = 3;
    private static final int VOLLEY_MAX_INFLIGHT = 8;
    private static final int VOLLEY_FLIGHT_MIN = 12;
    private static final int VOLLEY_FLIGHT_MAX = 20;
    private static final int VOLLEY_IMPACT_FRAMES = 12;
    private static final int VOLLEY_TRAIL_LEN = 12;

    private static final int HYPNO_CHARGE_FRAMES = 5;
    private static final int HYPNO_MUZZLE_FLASH = 5;
    private static final int HYPNO_SPAWN_GAP = 4;
    private static final int HYPNO_MAX_INFLIGHT = 10;
    private static final int HYPNO_FLIGHT_MIN = 7;
    private static final int HYPNO_FLIGHT_MAX = 12;
    private static final int HYPNO_IMPACT_FRAMES = 10;
    private static final int HYPNO_TRAIL_LEN = 8;

    private static final int HYPNO_HOVER_MIN = 90;
    private static final int HYPNO_HOVER_MAX = 150;
    private static final int HYPNO_HOVER_GRACE = 90;
    private static final int HYPNO_TRANCE_FRAMES = 36;
    private static final int HYPNO_GLITCH_FRAMES = 12;
    private static final float HYPNO_WEAVE_AMP = 8f;

    private static final Color HYPNO_HOT = new Color(255, 214, 240);
    private static final Color HYPNO_MAGENTA = new Color(252, 64, 200);
    private static final Color HYPNO_VIOLET = new Color(124, 56, 232);
    private static final Color HYPNO_ACID = new Color(164, 255, 64);

    private static final int EVOLUTION_SWAP_FRAME = Math.round(EVOLUTION_MORPH_FRAMES * 0.85f);
    private static final int EVOLUTION_DESCEND_AT = 43;
    private static final int EVOLUTION_DESCEND_LEN = 7;

    private static final int EVOLUTION_DISSOLVE_AT = 36;

    private static final int EVOLUTION_GROW_FRAMES = 26;
    private static final float SPLASH_RADIUS = 250f;
    private static final int INTRO_FRAMES = 18;
    private static final int DROP_FRAMES = 16;
    private static final float KAME_EXTEND_SPEED = 115f;
    private static final float KAME_REACH_EPS = 35f;

    private static final float KAME_DMG_MULT = 2f;
    private static final float KAME_RAMP_PEAK = 29f;

    private static final int   KAME_HIT_GAP    = 15;
    private static final float KAME_KB_FIRST   = 190f;
    private static final int   KAME_FREEZE_AT  = 90;
    private static final int   KAME_FREEZE_LEN = 60;
    private static final int   KAME_PUSH_AT    = 150;
    private static final float KAME_PUSH_BASE  = 40f;
    private static final float KAME_PUSH_MAX   = 900f;
    private static final float KAME_PUSH_GROW  = 5.7f;

    private static final short[] CANNON_Y = new short[]{-134, -134, -134, -250, -250, -134, -134, -134};
    private static final byte[] CANNON_X = new byte[]{0, 0, 0, 64, 64, 0, 0, 0};

    private static final float[] MUZZLE_DX = new float[]{28f, 28f, 28f, 28f, 28f, 28f, 28f, 28f};
    private static final float[] MUZZLE_DY = new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    private static final float CANNON_SPRITE_SCALE = 0.8f;
    private static final int EVT_HYPNOSIS = 1;
    private static final int EVT_FORGE = 2;
    private static final int EVT_COMET_HIT = 3;
    private static final Map<Object, Boolean> EVOLUTION_FROZEN =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private static volatile Object KAME_AIM_TARGET;

    public static boolean isKameAimTarget(Object entity) {
        return entity != null && entity == KAME_AIM_TARGET;
    }

    private static final int EVOLUTION_VFX = 4;

    private static final class VolleyPalette {
        final String name;
        final Color hot;
        final Color mid;
        final Color rim;
        final Color deep;
        VolleyPalette(String name, Color hot, Color mid, Color rim, Color deep) {
            this.name = name;
            this.hot = hot;
            this.mid = mid;
            this.rim = rim;
            this.deep = deep;
        }
    }

    private static final VolleyPalette[] VOLLEY_PALETTES = {
            new VolleyPalette("AuroraGold", new Color(255, 230, 128), new Color(255, 179, 71), new Color(255, 122, 47), new Color(194, 84, 26)),
            new VolleyPalette("PrismCyan", new Color(205, 246, 255), new Color(70, 214, 255), new Color(31, 134, 255), new Color(20, 80, 200)),
            new VolleyPalette("NovaPink", new Color(255, 192, 232), new Color(255, 94, 200), new Color(212, 40, 160), new Color(140, 20, 112)),
            new VolleyPalette("EmeraldPulse", new Color(180, 255, 210), new Color(60, 232, 150), new Color(20, 180, 110), new Color(10, 120, 72)),
            new VolleyPalette("VioletArc", new Color(216, 192, 255), new Color(160, 100, 255), new Color(114, 50, 224), new Color(74, 30, 160)),
            new VolleyPalette("ScarletComet", new Color(255, 176, 160), new Color(255, 90, 60), new Color(212, 40, 20), new Color(140, 20, 8)),
    };

    private static final class BoxRec {
        float minX, minY, maxX, maxY, cx, cy;
        long timeMs;
    }

    private static final Map<Object, BoxRec> SPRITE_BOXES =
            Collections.synchronizedMap(new WeakHashMap<Object, BoxRec>());
    private static final Map<Object, Boolean> BOUNDS_TRACKED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final long BOX_MAX_AGE_MS = 150L;

    public static boolean wantsSpriteBounds(Object entity) {
        return entity != null && !BOUNDS_TRACKED.isEmpty() && BOUNDS_TRACKED.containsKey(entity);
    }

    public static void recordSpriteBounds(Object entity, float minX, float minY, float maxX, float maxY,
                                          float bodyCX, float bodyCY) {
        if (entity == null) return;
        float w = maxX - minX;
        float h = maxY - minY;
        if (!(w > 2f) || !(h > 2f) || w >= 6000f || h >= 6000f) return;
        if (!(bodyCX >= minX && bodyCX <= maxX)) bodyCX = (minX + maxX) * 0.5f;
        if (!(bodyCY >= minY && bodyCY <= maxY)) bodyCY = (minY + maxY) * 0.5f;
        BoxRec r = SPRITE_BOXES.get(entity);
        if (r == null) {
            r = new BoxRec();
            SPRITE_BOXES.put(entity, r);
        }
        r.minX = minX;
        r.minY = minY;
        r.maxX = maxX;
        r.maxY = maxY;
        r.cx = bodyCX;
        r.cy = bodyCY;
        r.timeMs = System.currentTimeMillis();
    }

    private static float[] freshSpriteBox(Object entity) {
        if (entity == null) return null;
        BoxRec r = SPRITE_BOXES.get(entity);
        if (r == null) return null;
        if (System.currentTimeMillis() - r.timeMs > BOX_MAX_AGE_MS) return null;
        return new float[]{r.cx, r.cy, (r.maxX - r.minX) * 0.5f, (r.maxY - r.minY) * 0.5f};
    }

    private static void trackBounds(Object entity) {
        if (entity != null) BOUNDS_TRACKED.put(entity, Boolean.TRUE);
    }

    private static void untrackBounds(Object entity) {
        if (entity != null) {
            BOUNDS_TRACKED.remove(entity);
            SPRITE_BOXES.remove(entity);
        }
    }

    private static final int ALPHA_THRESHOLD = 40;

    private static final Map<FakeImage, float[]> ALPHA_BOUNDS_CACHE =
            Collections.synchronizedMap(new WeakHashMap<FakeImage, float[]>());

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
            result = (maxX >= minX && maxY >= minY)
                    ? new float[]{minX, minY, maxX, maxY}
                    : new float[0];
        } catch (Throwable ignored) {
            result = new float[0];
        }
        ALPHA_BOUNDS_CACHE.put(img, result);
        return result.length == 0 ? null : result;
    }

    public static void recordSpriteParts(Object entity,
                                         List<manualcontrol.hooks.BoundsRecorder.SpritePart> parts) {
        if (entity == null || parts == null || parts.isEmpty()) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        float sumA = 0f, sumAX = 0f, sumAY = 0f;
        boolean any = false;
        int limit = Math.min(64, parts.size());
        for (int i = 0; i < limit; i++) {
            manualcontrol.hooks.BoundsRecorder.SpritePart p = parts.get(i);
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
            float pMinX = Float.MAX_VALUE, pMinY = Float.MAX_VALUE;
            float pMaxX = -Float.MAX_VALUE, pMaxY = -Float.MAX_VALUE;
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

    private static final Map<Object, EvolutionMorph> MORPH_BY_SOURCE =
            Collections.synchronizedMap(new WeakHashMap<Object, EvolutionMorph>());

    private static final class GrowState {
        final float oldHalfH;
        final int palette;
        float fromScale = Float.NaN;
        int framesTotal = EVOLUTION_GROW_FRAMES;
        int age;
        int waitTicks;

        GrowState(float oldHalfH, int palette) {
            this.oldHalfH = oldHalfH;
            this.palette = palette;
        }
    }

    private static final Map<Object, GrowState> GROWING =
            Collections.synchronizedMap(new WeakHashMap<Object, GrowState>());

    private static final class TranceState {
        int age;
        float pos;
        int layer;
        boolean boxSeen;
        float boxCX, boxCY, boxHW, boxHH;

        TranceState(float pos, int layer) {
            this.pos = pos;
            this.layer = layer;
        }
    }

    private static final Map<Object, TranceState> TRANCE =
            Collections.synchronizedMap(new WeakHashMap<Object, TranceState>());

    public static float[] evolutionDrawFx(Object entity) {
        if (entity == null) return null;
        EvolutionMorph m = MORPH_BY_SOURCE.get(entity);
        if (m != null && !m.swapped && m.age >= EVOLUTION_DISSOLVE_AT) {
            float d = clamp01((m.age - EVOLUTION_DISSOLVE_AT)
                    / (float) Math.max(1, EVOLUTION_SWAP_FRAME - 1 - EVOLUTION_DISSOLVE_AT));
            float alpha = 255f * (1f - smooth(d));
            float jitter = (float) Math.sin(m.age * 2.9) * 4f * d;
            float swell = 1f + 0.05f * d * (float) Math.sin(m.age * 1.7);
            return new float[]{alpha, swell, jitter};
        }

        TranceState ts = TRANCE.get(entity);
        if (ts != null && ts.age < HYPNO_GLITCH_FRAMES) {
            float d = 1f - ts.age / (float) HYPNO_GLITCH_FRAMES;
            float jitter = (float) Math.sin(ts.age * 2.4) * 5f * d;
            float alpha = 255f - 75f * d * (0.5f + 0.5f * (float) Math.sin(ts.age * 1.9));
            return new float[]{alpha, 1f, jitter};
        }
        GrowState g = GROWING.get(entity);
        if (g != null) {
            if (Float.isNaN(g.fromScale)) {

                return new float[]{0f, 1f, 0f};
            }

            float t = clamp01(g.age / (float) Math.max(1, g.framesTotal));
            float u = easeOutBack(t);
            float sc = (float) Math.exp(Math.log(g.fromScale) * (1.0 - u));
            if (Math.abs(sc - 1f) < 0.002f) return null;
            return new float[]{255f, sc, 0f};
        }
        return null;
    }

    private static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static void updateEvolutionGrowth() {
        if (GROWING.isEmpty()) return;
        Object[] keys;
        synchronized (GROWING) {
            keys = GROWING.keySet().toArray();
        }
        for (int i = 0; i < keys.length; i++) {
            Object ent = keys[i];
            GrowState g = GROWING.get(ent);
            if (g == null) continue;
            if (Float.isNaN(g.fromScale)) {
                float[] box = freshSpriteBox(ent);
                if (box != null && box[3] > 3f) {

                    g.fromScale = (Float.isNaN(g.oldHalfH) || g.oldHalfH < 3f) ? 0.75f
                            : Math.max(0.06f, Math.min(16f, g.oldHalfH / box[3]));

                    float mag = (float) Math.min(1.0, Math.abs(Math.log10(g.fromScale)));
                    g.framesTotal = Math.round(EVOLUTION_GROW_FRAMES * (1f + 0.6f * mag));
                    Logger.log("BCU Crazy evolution grow-in: fromScale=" + g.fromScale
                            + " oldHalfH=" + g.oldHalfH + " naturalHalfH=" + box[3]
                            + " frames=" + g.framesTotal);
                } else if (++g.waitTicks > 10) {
                    g.fromScale = 0.75f;
                }
                continue;
            }
            g.age++;
            if (g.age >= g.framesTotal) {
                GROWING.remove(ent);
                untrackBounds(ent);
            }
        }
    }

    private static void drawGrowthAuras(FakeGraphics gra) {
        if (GROWING.isEmpty()) return;
        Object[] keys;
        synchronized (GROWING) {
            keys = GROWING.keySet().toArray();
        }
        for (int i = 0; i < keys.length; i++) {
            GrowState g = GROWING.get(keys[i]);
            if (g == null || Float.isNaN(g.fromScale)) continue;
            float[] box = freshSpriteBox(keys[i]);
            if (box == null) continue;
            float t = clamp01(g.age / (float) Math.max(1, g.framesTotal));
            float fade = 1f - t;
            if (fade <= 0f) continue;
            int pi = Math.max(0, Math.min(VOLLEY_PALETTES.length - 1, g.palette));
            VolleyPalette pal = VOLLEY_PALETTES[pi];
            float R = Math.min(480f, Math.max(box[2], box[3]));

            boolean shrink = g.fromScale > 1f;
            drawGlowTexAdditive(gra, getVolleyGlow(pi), box[0], box[1], 1.7f * R, Math.round(55f * fade));
            float ringR = shrink ? R * (1.30f - 0.45f * t) : R * (0.85f + 0.35f * t);
            fakeRing(gra, box[0], box[1], ringR, Math.max(1.6f, R * 0.03f),
                    pal.hot, Math.round(120f * fade), g.age * 0.2f);
            for (int k = 0; k < 8; k++) {
                float h = ((k * 97 + 31) & 255) / 255f;
                if (shrink) {

                    float f = 1f - ((g.age * (0.05f + h * 0.04f) + h) % 1f);
                    double a = h * Math.PI * 2.0 + g.age * 0.05;
                    fakePixel(gra, box[0] + (float) Math.cos(a) * box[2] * 1.25f * f,
                            box[1] + (float) Math.sin(a) * box[3] * 1.15f * f,
                            Math.max(2f, R * 0.03f),
                            k % 2 == 0 ? pal.hot : Color.WHITE,
                            Math.round(160f * fade * (1f - f * 0.4f)));
                } else {
                    float rise = (g.age * (1.5f + h) * Math.max(2f, R * 0.04f)) % (box[3] * 2f);
                    fakePixel(gra, box[0] + (h - 0.5f) * 1.6f * box[2],
                            box[1] + box[3] - rise, Math.max(2f, R * 0.03f),
                            k % 2 == 0 ? pal.hot : Color.WHITE, Math.round(160f * fade));
                }
            }
        }
    }

    private static void updateTrance() {
        if (TRANCE.isEmpty()) return;
        Object[] keys;
        synchronized (TRANCE) {
            keys = TRANCE.keySet().toArray();
        }
        for (int i = 0; i < keys.length; i++) {
            Object ent = keys[i];
            TranceState ts = TRANCE.get(ent);
            if (ts == null) continue;
            if (ent instanceof Entity) {
                Entity e = (Entity) ent;
                if (e.dead || e.health <= 0L) {
                    TRANCE.remove(ent);
                    untrackBounds(ent);
                    continue;
                }
                ts.pos = e.pos;
                ts.layer = readLayer(e, "currentLayer", e.currentLayer);
            }
            ts.age++;
            if (ts.age >= HYPNO_TRANCE_FRAMES) {
                TRANCE.remove(ent);
                untrackBounds(ent);
            }
        }
    }

    private static void drawTranceBlooms(FakeGraphics gra, Object bbpainter) {
        if (TRANCE.isEmpty()) return;
        float siz = BBPainterAccess.getSiz(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);
        Object[] keys;
        synchronized (TRANCE) {
            keys = TRANCE.keySet().toArray();
        }
        for (int i = 0; i < keys.length; i++) {
            TranceState ts = TRANCE.get(keys[i]);
            if (ts == null) continue;
            float[] box = freshSpriteBox(keys[i]);
            if (box != null) {
                ts.boxCX = box[0];
                ts.boxCY = box[1];
                ts.boxHW = box[2];
                ts.boxHH = box[3];
                ts.boxSeen = true;
            }
            float rootX = CrazyRender.screenX(bbpainter, ts.pos);
            float rootY = midh - (156f - ts.layer * 4f) * siz;
            float cx = ts.boxSeen ? ts.boxCX : rootX;
            float cy = ts.boxSeen ? ts.boxCY : rootY - 40f * siz;
            float hw = ts.boxSeen ? ts.boxHW : 26f * siz;
            float hh = ts.boxSeen ? ts.boxHH : 36f * siz;
            drawTranceBloom(gra, ts, cx, cy, hw, hh, siz);
        }
    }

    private static void drawTranceBloom(FakeGraphics gra, TranceState ts, float cx, float cy,
                                        float hw, float hh, float siz) {
        int age = ts.age;
        float lifeT = clamp01(age / (float) HYPNO_TRANCE_FRAMES);
        float fade = 1f - smooth(clamp01((age - (HYPNO_TRANCE_FRAMES - 12)) / 12f));
        if (fade <= 0f) return;
        float R = Math.min(480f, Math.max(Math.max(hw, hh), 14f * siz));
        float dot = Math.max(2f * siz, R * 0.035f);

        drawGlowTexAdditive(gra, getHypnoGlow(), cx, cy, 1.7f * R, Math.round(42f * fade));

        float breath = (float) Math.sin(age * 0.3);
        float r1 = R * (0.80f + 0.15f * breath);
        drawRingTex(gra, getHypnoRing(2), cx, cy, r1, Math.round(140f * fade * (1f - lifeT)));
        drawRingTex(gra, getHypnoRing(1), cx, cy, r1, Math.round(140f * fade * lifeT));
        drawRingTex(gra, getHypnoRing(3), cx, cy, R * (1.06f - 0.12f * breath),
                Math.round(95f * fade));

        float headY = cy - hh * 0.55f;
        float orbR = Math.max(hw * 0.95f, 12f * siz);
        for (int k = 0; k < 10; k++) {
            double a = age * 0.2 + k * 0.628;
            FakeImage sp = k % 2 == 0 ? getHypnoGlowAcid() : getHypnoSpark();
            drawTexAdditiveRot(gra, sp, cx + (float) Math.cos(a) * orbR,
                    headY + (float) Math.sin(a) * orbR * 0.28f,
                    dot * 3.4f, dot * 3.4f, (float) a, Math.round(185f * fade));
        }

        float eyeW = Math.max(18f * siz, Math.min(76f * siz, hw * 0.9f));
        float open = smooth(clamp01(age / 4f)) * fade;
        float eyeH = eyeW * 0.5f * open;
        float ecx = cx;
        float ecy = cy - hh - Math.max(10f * siz, eyeH + 4f * siz);
        drawHypnoEye(gra, ecx, ecy, eyeW, eyeH, age, fade, siz);

        if (age >= 4 && age <= 6) {
            drawGlowTexAdditive(gra, getVolleyWhiteGlow(), ecx, ecy, eyeW * 1.4f, 200);
        }

        if (age >= HYPNO_TRANCE_FRAMES - 10) {
            float e = clamp01((age - (HYPNO_TRANCE_FRAMES - 10)) / 10f);
            drawRingTex(gra, getHypnoRing(0), cx, cy, R * (0.4f + 1.4f * e),
                    Math.round(170f * (1f - e)));
        }

        for (int k = 0; k < 8; k++) {
            float h = ((k * 97 + 31) & 255) / 255f;
            float drop = (age * (1.2f + h) * Math.max(2f, R * 0.03f)) % (hh * 2f);
            FakeImage sp = k % 2 == 0 ? getHypnoSpark() : getHypnoGlowAcid();
            drawTexAdditiveRot(gra, sp, cx + (h - 0.5f) * 1.8f * hw, cy - hh + drop,
                    Math.max(5f, R * 0.07f), Math.max(5f, R * 0.07f),
                    h * 6.28f + age * 0.1f, Math.round(130f * fade));
        }
    }

    private static void drawHypnoEye(FakeGraphics gra, float cx, float cy, float eyeW, float eyeH,
                                     int age, float fade, float siz) {
        if (eyeW <= 2f || eyeH <= 1f) return;

        drawGlowTexAdditive(gra, getHypnoGlow(), cx, cy, eyeW * 2.3f, Math.round(95f * fade));

        float pulse = (float) ((Math.sin(age * 0.32) + 1.0) * 0.5);
        for (int i = 0; i < 8; i++) {
            if (i == 0 || i == 4) continue;
            double a = i * Math.PI / 4.0;
            float rx0 = cx + (float) Math.cos(a) * eyeW * 0.92f;
            float ry0 = cy + (float) Math.sin(a) * eyeH * 1.05f;
            drawBlade(gra, rx0, ry0, a, 0f, (6f + 5f * pulse) * siz, 1.5f * siz,
                    HYPNO_MAGENTA, HYPNO_VIOLET, Math.round(170f * fade));
        }
        drawBlade(gra, cx - eyeW * 0.98f, cy, Math.PI, 0f, 8f * siz, 2f * siz,
                HYPNO_HOT, HYPNO_MAGENTA, Math.round(230f * fade));
        drawBlade(gra, cx + eyeW * 0.98f, cy, 0.0, 0f, 8f * siz, 2f * siz,
                HYPNO_HOT, HYPNO_MAGENTA, Math.round(230f * fade));

        drawTexSpriteRot(gra, getHypnoEyeTex(), cx, cy, eyeW * 2.2857f, eyeH * 2.4242f, 0f, 1f);
        if (eyeH > 0.16f * eyeW) {

            drawTexSpriteRot(gra, getHypnoPupilTex(), cx, cy, 0.84f * eyeW, 0.84f * eyeW,
                    -age * 0.45f, 2.13f * eyeH / eyeW);

            gOpaque = true;
            try {
                fakeDisc(gra, cx - 0.1286f * eyeW, cy - 0.303f * eyeH,
                        Math.max(1.2f * siz, 0.0643f * eyeW), Color.WHITE, 255);
                fakeDisc(gra, cx + 0.1143f * eyeW, cy + 0.2727f * eyeH,
                        Math.max(1f, 0.0357f * eyeW), HYPNO_HOT, 255);
            } finally {
                gOpaque = false;
            }
        }

        float hx0 = Float.NaN, hy0 = 0f;
        for (int s = 0; s <= 10; s++) {
            float p = s / 10f;
            float xx = cx - eyeW + 2f * eyeW * p;
            float bow = (float) Math.sin(Math.PI * p) * Math.max(0f, eyeH - 1.6f * siz);
            if (!Float.isNaN(hx0)) {
                fakeLine(gra, hx0, hy0, xx, cy - bow, HYPNO_HOT, 0.9f * siz, Math.round(200f * fade));
            }
            hx0 = xx;
            hy0 = cy - bow;
        }
    }

    private static float gScale = 1f;
    private static float gAlpha = 1f;

    private static boolean gOpaque = false;

    private static boolean gIsGL = false;

    private static boolean gAdditive = false;

    private static boolean gTrueAlpha = false;

    private BeamFeature() {}

    public static final class State {
        public boolean active;
        public CrazyConfig.BeamMode mode = CrazyConfig.BeamMode.NONE;
        public int frame;
        public int duration;
        public float startPos;
        public float endPos;
        public float impactPos;
        public float visualImpactPos;
        public float sweepPos;
        public boolean hit;
        public boolean kameReached;
        public boolean loggedDraw;
        public int impactLayer;
        public float lastKameTargetPos;
        public float kameAimPos;
        public Object kameTargetEntity;
        public final Set<Object> touched = new HashSet<Object>();

        public final Map<Object, int[]> kameHits = new WeakHashMap<Object, int[]>();
        public final List<BeamEvent> events = new ArrayList<BeamEvent>();
        public final List<EvolutionMorph> morphs = new ArrayList<EvolutionMorph>();

        public final List<TracerBullet> bullets = new ArrayList<TracerBullet>();
        public int volleyCooldown;

        public int bulletCounter;
    }

    private static final class BeamEvent {
        final int type;
        final float pos;
        int age;

        BeamEvent(int type, float pos) {
            this.type = type;
            this.pos = pos;
        }
    }

    private static final class TracerBullet {
        static final int PHASE_CHARGE = 0;
        static final int PHASE_FLY = 1;
        static final int PHASE_IMPACT = 2;
        static final int PHASE_HOVER = 3;

        Entity target;

        final boolean hypno;
        final int palette;
        final int seed;
        final int flight;
        final int hover;
        int hoverExit;
        boolean airFizzle;
        int phase = PHASE_CHARGE;
        int age;
        int impactAge;
        boolean targetAlive = true;
        float targetPos;
        int targetLayer;

        final float[] histX = new float[VOLLEY_TRAIL_LEN];
        final float[] histY = new float[VOLLEY_TRAIL_LEN];
        int histCount;

        boolean aimSeen;
        float aimOffX, aimOffY, aimHW, aimHH;

        TracerBullet(Entity target, int palette, int seed, int flight, boolean hypno) {
            this.target = target;
            this.palette = palette;
            this.seed = seed;
            this.flight = flight;
            this.hypno = hypno;

            this.hover = hypno
                    ? HYPNO_HOVER_MIN + ((seed * 911 + 271) & 1023) % (HYPNO_HOVER_MAX - HYPNO_HOVER_MIN + 1)
                    : 0;
            this.targetPos = target.pos;
            this.targetLayer = target.currentLayer;
        }
    }

    private static final class EvolutionMorph {
        final EUnit source;
        final float pos;
        final int layer;
        final int currentLayer;
        final int spawnLayer;
        final int palette;
        boolean swapped;
        int age;

        boolean boxSeen;
        float boxCX, boxCY, boxHW, boxHH;

        EvolutionMorph(EUnit source, int palette) {
            this.source = source;
            this.palette = palette;
            this.pos = source.pos;
            this.layer = readLayer(source, "layer", readLayer(source, "currentLayer", source.currentLayer));
            this.currentLayer = readLayer(source, "currentLayer", source.currentLayer);
            this.spawnLayer = readLayer(source, "spawnLayer", source.spawnLayer);
        }

        float progress() {
            return clamp01(age / (float) EVOLUTION_MORPH_FRAMES);
        }

        float lift() {
            float rise = smooth(clamp01(age / 28f));

            if (age > EVOLUTION_DESCEND_AT) {
                rise *= 1f - smooth(clamp01((age - EVOLUTION_DESCEND_AT) / (float) EVOLUTION_DESCEND_LEN));
            }
            return rise;
        }

        int visualLayer() {

            return currentLayer - Math.round(lift() * EVOLUTION_LIFT_LAYERS);
        }
    }

    public static void activate(CrazyRuntime.StageRuntime rt, Cannon cannon) {
        StageBasis sb = (StageBasis) rt.stage;
        rt.beam.active = true;
        rt.beam.mode = rt.config.beamMode;
        rt.beam.frame = 0;
        rt.beam.duration = duration(rt.config.beamMode);
        rt.beam.startPos = sb.ubase.pos;
        rt.beam.endPos = beamFarPos(sb);
        rt.beam.sweepPos = beamNearPos(sb);
        if (rt.beam.mode == CrazyConfig.BeamMode.KAMEHAMEHA) {

            AbEntity first = firstEnemyTarget(rt, sb);
            rt.beam.impactPos = beamNearPos(sb);
            rt.beam.kameAimPos = (first != null) ? first.pos : beamFarPos(sb);
            rt.beam.kameTargetEntity = (first instanceof Entity) ? first : null;
            KAME_AIM_TARGET = rt.beam.kameTargetEntity;
        } else {
            rt.beam.impactPos = rt.beam.sweepPos;
            rt.beam.kameTargetEntity = null;
        }
        rt.beam.visualImpactPos = rt.beam.impactPos;
        rt.beam.hit = false;
        rt.beam.kameReached = false;
        rt.beam.loggedDraw = false;
        rt.beam.impactLayer = 0;
        rt.beam.lastKameTargetPos = Float.NaN;
        rt.beam.touched.clear();
        rt.beam.kameHits.clear();
        rt.beam.events.clear();
        rt.beam.bullets.clear();
        rt.beam.volleyCooldown = 0;
        clearEvolutionMorphs(rt);
        clearNativeCannonState(cannon, rt.beam.impactPos);
        Logger.log("BCU Crazy beam activated: " + rt.beam.mode + " duration=" + rt.beam.duration
                + " vfx=blue-kamehameha-muzzle-v4 evolution-volley-v1 mesmer-needle-hypnosis-v1"
                + " nativeDrawAtk=blocked");
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        if (rt.config.beamMode != null && rt.config.beamMode != CrazyConfig.BeamMode.NONE) {
            applyBeamRecovery(rt);
        }
        updateEvolutionMorphs(rt);
        updateEvolutionGrowth();
        updateTrance();
    }

    private static void applyBeamRecovery(CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        int want = Math.max(1, (int) Math.round(rt.config.beamRecoverySeconds * 30.0));
        if (sb.maxCannon != want) {
            int old = sb.maxCannon;
            sb.maxCannon = want;

            if (old > 0) {
                sb.cannon = Math.min(want, Math.round(sb.cannon * (want / (float) old)));
            }
        }
        if (sb.cannon > sb.maxCannon) sb.cannon = sb.maxCannon;
    }

    public static void updateActive(CrazyRuntime.StageRuntime rt, Cannon cannon) {
        if (!rt.beam.active) return;
        StageBasis sb = (StageBasis) rt.stage;
        rt.beam.startPos = sb.ubase.pos;
        rt.beam.endPos = beamFarPos(sb);
        rt.beam.hit = false;

        if (rt.beam.mode == CrazyConfig.BeamMode.HYPNOSIS) {
            updateHypnoVolley(rt, sb);
        } else if (rt.beam.mode == CrazyConfig.BeamMode.EVOLUTION) {
            updateVolley(rt, sb);
        } else if (rt.beam.mode == CrazyConfig.BeamMode.KAMEHAMEHA) {
            updateKame(rt, sb, cannon);
        }

        updateEvents(rt);
        rt.beam.frame++;

        boolean volleyBusy = (rt.beam.mode == CrazyConfig.BeamMode.EVOLUTION
                || rt.beam.mode == CrazyConfig.BeamMode.HYPNOSIS) && !rt.beam.bullets.isEmpty();
        if (rt.beam.frame >= rt.beam.duration && !volleyBusy) {
            rt.beam.active = false;
            rt.beam.touched.clear();
            rt.beam.kameHits.clear();
            rt.beam.events.clear();
            rt.beam.bullets.clear();
            rt.beam.kameTargetEntity = null;
            KAME_AIM_TARGET = null;
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt == null || gra == null) return;
        if (!rt.beam.active && rt.beam.morphs.isEmpty() && GROWING.isEmpty() && TRANCE.isEmpty()) return;

        gIsGL = gra.getClass().getName().contains("GLGraphics");
        float[] muzzle = cannonMuzzle(bbpainter, rt);
        float groundPos = beamNearPos((StageBasis) rt.stage);
        float groundX = CrazyRender.screenX(bbpainter, groundPos);
        float groundY = BBPainterAccess.getMidh(bbpainter) - 156f * BBPainterAccess.getSiz(bbpainter);
        float endX = CrazyRender.screenX(bbpainter, rt.beam.impactPos);
        float combatY = BBPainterAccess.getMidh(bbpainter) - 205f * BBPainterAccess.getSiz(bbpainter);
        float kameImpactY = BBPainterAccess.getMidh(bbpainter)
                - (205f - rt.beam.impactLayer * 4f) * BBPainterAccess.getSiz(bbpainter);

        if (rt.beam.active) {
            if (!rt.beam.loggedDraw) {
                rt.beam.loggedDraw = true;
                Logger.log("BCU Crazy beam draw active: mode=" + rt.beam.mode
                        + " graphics=" + gra.getClass().getName()
                        + " vfx=blue-kamehameha-muzzle-v4 shadow-molt-evolution-v1"
                        + " mesmer-needle-hypnosis-v1");
            }
            if (rt.beam.mode == CrazyConfig.BeamMode.KAMEHAMEHA) {

                float aimX = CrazyRender.screenX(bbpainter, rt.beam.kameAimPos);
                float aimY = kameImpactY;
                Object aimE = rt.beam.kameTargetEntity;
                if (rt.beam.kameReached && aimE != null
                        && manualcontrol.SpriteAnchor.hasFreshLiveBox(aimE)) {
                    aimX = manualcontrol.SpriteAnchor.getLiveBodyCX();
                    aimY = manualcontrol.SpriteAnchor.getLiveBodyCY();
                }

                float denom = aimX - muzzle[0];
                float progress = Math.abs(denom) < 1f ? 1f
                        : clamp01((endX - muzzle[0]) / denom);
                float kameEndX = rt.beam.kameReached ? aimX : endX;
                float kameEndY = rt.beam.kameReached ? aimY
                        : muzzle[1] + (aimY - muzzle[1]) * progress;
                float taper = kameTaper(rt);
                gScale = taper;
                try {
                    drawSpiralCometKamehameha(gra, rt, muzzle[0], muzzle[1], kameEndX, kameEndY, taper);
                } finally {
                    gScale = 1f;
                }
            } else if (rt.beam.mode == CrazyConfig.BeamMode.HYPNOSIS) {

                drawVolley(gra, rt, bbpainter, muzzle[0], muzzle[1]);
            } else if (rt.beam.mode == CrazyConfig.BeamMode.EVOLUTION) {

                drawVolley(gra, rt, bbpainter, muzzle[0], muzzle[1]);
            }
            drawBeamEvents(rt, bbpainter, gra, groundY, combatY);
        }
        drawEvolutionMorphs(rt, bbpainter, gra);
        drawGrowthAuras(gra);
        drawTranceBlooms(gra, bbpainter);
    }

    private static void drawForgeEvolution(FakeGraphics gra, CrazyRuntime.StageRuntime rt,
                                           float muzzleX, float muzzleY, float groundX, float groundY, float sweepX) {
        Color emerald = new Color(32, 220, 142);
        Color gold = new Color(255, 226, 82);
        Color ember = new Color(255, 112, 42);
        float pulse = (float) ((Math.sin(rt.beam.frame * 0.28) + 1.0) * 0.5);
        if (rt.beam.frame < INTRO_FRAMES) {
            fakeDisc(gra, muzzleX, muzzleY, 13f + pulse * 10f, ember, 125);
            fakeDisc(gra, muzzleX, muzzleY, 8f + pulse * 5f, gold, 170);
            fakeRing(gra, muzzleX, muzzleY, 28f + pulse * 6f, 4f, emerald, 140, rt.beam.frame * 0.18f);
            drawForgeRunes(gra, muzzleX, muzzleY, 34f, rt.beam.frame, emerald, gold);
            return;
        }

        float drop = clamp01((rt.beam.frame - INTRO_FRAMES) / (float) Math.max(1, DROP_FRAMES));
        float dropY = muzzleY + (groundY - muzzleY) * drop;
        if (rt.beam.frame < INTRO_FRAMES + DROP_FRAMES) {
            fakeLine(gra, muzzleX, muzzleY, groundX, dropY, ember, 8f + pulse * 1.5f, 82);
            fakeLine(gra, muzzleX, muzzleY, groundX, dropY, emerald, 4.5f + pulse * 1.5f, 124);
            fakeLine(gra, muzzleX, muzzleY, groundX, dropY, gold, 2.5f + pulse * 1f, 184);
            drawForgeSparks(gra, muzzleX, muzzleY, groundX, dropY, rt.beam.frame, ember, gold);
            return;
        }

        fakeLine(gra, muzzleX, muzzleY, sweepX, groundY, ember, 8f + pulse * 2f, 70);
        fakeLine(gra, muzzleX, muzzleY, sweepX, groundY, emerald, 5f + pulse * 1.5f, 118);
        fakeLine(gra, muzzleX, muzzleY, sweepX, groundY, gold, 2.5f + pulse * 1f, 182);
        fakeLine(gra, muzzleX, muzzleY, sweepX, groundY, Color.WHITE, 1.5f, 172);
        drawForgePlates(gra, muzzleX, muzzleY, sweepX, groundY, rt.beam.frame, emerald, gold, ember);
        drawForgeSparks(gra, muzzleX, muzzleY, sweepX, groundY, rt.beam.frame, ember, gold);
        fakeRing(gra, sweepX, groundY, 26f + pulse * 9f, 4f, gold, 145, -rt.beam.frame * 0.22f);
        fakeDiamond(gra, sweepX, groundY, 11f + pulse * 4f, Color.WHITE, 170);
    }

    private static void drawVolley(FakeGraphics gra, CrazyRuntime.StageRuntime rt, Object bbpainter,
                                   float muzzleX, float muzzleY) {
        State st = rt.beam;
        if (st.bullets.isEmpty()) return;
        float siz = BBPainterAccess.getSiz(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);
        for (int i = 0; i < st.bullets.size(); i++) {
            TracerBullet b;
            try {
                b = st.bullets.get(i);
            } catch (IndexOutOfBoundsException ex) {
                break;
            }
            if (b == null) continue;

            float rootX = CrazyRender.screenX(bbpainter, b.targetPos);
            float rootY = midh - (156f - b.targetLayer * 4f) * siz;
            float[] box = freshSpriteBox(b.target);
            if (box != null) {
                b.aimOffX = box[0] - rootX;
                b.aimOffY = box[1] - rootY;
                b.aimHW = box[2];
                b.aimHH = box[3];
                b.aimSeen = true;
            }
            float tx = rootX + (b.aimSeen ? b.aimOffX : 0f);
            float ty = rootY + (b.aimSeen ? b.aimOffY : -40f * siz);
            if (b.phase == TracerBullet.PHASE_CHARGE) {
                if (b.hypno) drawHypnoCharge(gra, b, muzzleX, muzzleY, siz);
                else drawVolleyCharge(gra, b, muzzleX, muzzleY, siz);
            } else if (b.phase == TracerBullet.PHASE_HOVER) {
                drawHypnoHover(gra, b, muzzleX, muzzleY, tx, ty, siz);
            } else if (b.phase == TracerBullet.PHASE_FLY) {
                if (b.hypno) drawHypnoTracer(gra, b, muzzleX, muzzleY, tx, ty, siz);
                else drawVolleyTracer(gra, b, muzzleX, muzzleY, tx, ty, siz);
            } else {

                float is = siz;
                if (b.aimSeen) {
                    is = siz * Math.max(1f, Math.min(8f,
                            Math.max(b.aimHW, b.aimHH * 0.7f) / (45f * siz)));
                }
                if (b.hypno && b.airFizzle) {

                    float[] hp = hypnoHoverPos(b, b.hoverExit, muzzleX, muzzleY, siz);
                    drawHypnoImpact(gra, b, hp[0], hp[1], siz);
                } else if (b.hypno) {
                    drawHypnoImpact(gra, b, tx, ty, is);
                } else {
                    drawVolleyImpact(gra, b, tx, ty, is);
                }
            }
        }
    }

    private static void drawVolleyCharge(FakeGraphics gra, TracerBullet b,
                                         float mx, float my, float siz) {
        VolleyPalette pal = VOLLEY_PALETTES[b.palette];
        float c = clamp01(b.age / (float) VOLLEY_CHARGE_FRAMES);
        drawGlowTexAdditive(gra, getVolleyGlow(b.palette), mx, my, (10f + 26f * c) * siz, Math.round(150f * c));
        fakeRing(gra, mx, my, (26f * (1f - c) + 7f) * siz, 1.6f * siz, pal.hot, Math.round(170f * c), b.age * 0.3f);

        for (int k = 0; k < 6; k++) {
            float h = ((k * 97 + b.seed * 31 + 11) & 255) / 255f;
            double a = h * Math.PI * 2.0 + b.age * 0.12;
            float d = (24f * (1f - c) + 4f) * siz * (0.5f + h * 0.7f);
            fakePixel(gra, mx + (float) Math.cos(a) * d, my + (float) Math.sin(a) * d,
                    1.8f * siz, k % 2 == 0 ? pal.hot : Color.WHITE, Math.round(190f * c));
        }
    }

    private static void drawVolleyTracer(FakeGraphics gra, TracerBullet b, float mx, float my,
                                         float tx, float ty, float siz) {
        VolleyPalette pal = VOLLEY_PALETTES[b.palette];
        FakeImage tex = getVolleyGlow(b.palette);
        float t = clamp01(b.age / (float) Math.max(1, b.flight));
        float e = t * (0.35f + 0.65f * t);
        float h1 = ((b.seed * 131 + 17) & 255) / 255f;
        float cpx = mx + (tx - mx) * 0.45f;
        float cpy = Math.min(my, ty) - (55f + h1 * 40f) * siz;
        float u = 1f - e;
        float px = u * u * mx + 2f * u * e * cpx + e * e * tx;
        float py = u * u * my + 2f * u * e * cpy + e * e * ty;

        if (b.age <= VOLLEY_MUZZLE_FLASH) {
            float fl = 1f - b.age / (float) VOLLEY_MUZZLE_FLASH;
            double dir = Math.atan2(cpy - my, cpx - mx);
            drawGlowTexAdditive(gra, tex, mx, my, (24f + 26f * (1f - fl)) * siz, Math.round(210f * fl));
            drawSmoothRing(gra, mx, my, (8f + 26f * (1f - fl)) * siz, (2.5f * fl + 0.8f) * siz,
                    Color.WHITE, Math.round(190f * fl));
            for (int k = 0; k < 5; k++) {
                double a = dir + (k - 2) * 0.22;
                float l0 = 4f * siz;
                float l1 = (13f + 19f * (1f - fl)) * siz;
                fakeLine(gra, mx + (float) Math.cos(a) * l0, my + (float) Math.sin(a) * l0,
                        mx + (float) Math.cos(a) * l1, my + (float) Math.sin(a) * l1,
                        k % 2 == 0 ? pal.hot : Color.WHITE, 1.8f * siz, Math.round(200f * fl));
            }
        }

        if (b.histCount == 0
                || Math.abs(b.histX[0] - px) > 0.5f || Math.abs(b.histY[0] - py) > 0.5f) {
            System.arraycopy(b.histX, 0, b.histX, 1, VOLLEY_TRAIL_LEN - 1);
            System.arraycopy(b.histY, 0, b.histY, 1, VOLLEY_TRAIL_LEN - 1);
            b.histX[0] = px;
            b.histY[0] = py;
            if (b.histCount < VOLLEY_TRAIL_LEN) b.histCount++;
        }
        int n = b.histCount;

        for (int i = n - 1; i >= 0; i--) {
            float f = 1f - i / (float) VOLLEY_TRAIL_LEN;
            drawGlowTexAdditive(gra, tex, b.histX[i], b.histY[i], (7f + 18f * f) * siz, Math.round(46f * f));
        }

        for (int i = 0; i < n - 1; i++) {
            float f = 1f - i / (float) VOLLEY_TRAIL_LEN;
            Color c = i < 3 ? pal.hot : (i < 7 ? pal.rim : pal.deep);
            fakeLine(gra, b.histX[i], b.histY[i], b.histX[i + 1], b.histY[i + 1],
                    c, (3.4f * f + 0.6f) * siz, Math.round(150f * f));
        }

        for (int i = 0; i < Math.min(8, n - 1); i++) {
            float dxs = b.histX[i] - b.histX[i + 1];
            float dys = b.histY[i] - b.histY[i + 1];
            float len = (float) Math.sqrt(dxs * dxs + dys * dys);
            if (len < 0.5f) continue;
            float nx = -dys / len, ny = dxs / len;
            float ph = b.age * 0.9f - i * 0.85f + b.seed;
            float off = (float) Math.sin(ph) * (2.5f + i * 0.55f) * siz;
            float fade = 1f - i / 8f;
            fakePixel(gra, b.histX[i] + nx * off, b.histY[i] + ny * off,
                    2f * siz, i % 2 == 0 ? pal.hot : Color.WHITE, Math.round(200f * fade));
            fakePixel(gra, b.histX[i] - nx * off, b.histY[i] - ny * off,
                    1.6f * siz, pal.mid, Math.round(140f * fade));
        }

        for (int k = 3; k < n; k += 3) {
            float h = ((k * 67 + b.seed * 53 + 29) & 255) / 255f;
            float sag = (2f + h * 5f + (b.age % 6)) * 0.8f * siz;
            fakePixel(gra, b.histX[k] + (h - 0.5f) * 6f * siz, b.histY[k] + sag,
                    1.6f * siz, pal.hot, Math.round(130f * (1f - k / (float) VOLLEY_TRAIL_LEN)));
        }

        drawGlowTexAdditive(gra, tex, px, py, 30f * siz, 205);
        gOpaque = true;
        try {
            fakeDisc(gra, px, py, 5.5f * siz, pal.mid, 255);
            fakeDisc(gra, px, py, 3f * siz, Color.WHITE, 255);
        } finally {
            gOpaque = false;
        }
    }

    private static void drawVolleyImpact(FakeGraphics gra, TracerBullet b, float cx, float cy, float siz) {
        VolleyPalette pal = VOLLEY_PALETTES[b.palette];
        float f = 1f - clamp01(b.impactAge / (float) VOLLEY_IMPACT_FRAMES);
        if (f <= 0f) return;
        float grow = 1f - f;
        drawGlowTexAdditive(gra, getVolleyGlow(b.palette), cx, cy, (26f + 46f * grow) * siz, Math.round(225f * f));
        drawGlowTexAdditive(gra, getVolleyWhiteGlow(), cx, cy, (14f + 22f * grow) * siz, Math.round(235f * f));

        drawBandRing(gra, cx, cy, (8f + 40f * grow) * siz, (4.5f * f + 1f) * siz,
                Color.WHITE, Math.round(215f * f));
        fakeRing(gra, cx, cy, (14f + 52f * grow) * siz, 2f * siz, pal.mid, Math.round(160f * f), 0f);
        for (int i = 0; i < 9; i++) {
            float h = ((b.seed * 67 + i * 149 + 23) & 255) / 255f;
            double ang = i * Math.PI * 2.0 / 9.0 + (h - 0.5f) * 0.3 + b.seed * 0.7;
            drawBlade(gra, cx, cy, ang, 3f * siz, (14f + 26f * grow + h * 12f) * siz,
                    2.6f * siz * f, Color.WHITE, pal.mid, Math.round(220f * f));
        }
        for (int k = 0; k < 8; k++) {
            float g1 = ((k * 97 + b.seed * 31 + 11) & 255) / 255f;
            double ang = g1 * Math.PI * 2.0;
            float d = (10f + 44f * grow * (0.5f + g1)) * siz;
            fakePixel(gra, cx + (float) Math.cos(ang) * d, cy + (float) Math.sin(ang) * d,
                    2f * siz, k % 2 == 0 ? pal.hot : Color.WHITE, Math.round(200f * f));
        }
    }

    private static float hypnoBlinkOpen(TracerBullet b) {
        int period = 48 + ((b.seed * 53 + 7) & 63);
        int ph = (b.age + ((b.seed * 29) & 127)) % period;
        final int BL = 9;
        if (ph < BL) {
            float t = ph / (float) BL;
            float open = t < 0.35f ? 1f - t / 0.35f : (t - 0.35f) / 0.65f;
            return 0.06f + 0.94f * smooth(open);
        }
        return 0.92f + 0.08f * (float) Math.sin(b.age * 0.11f + b.seed);
    }

    private static void drawHypnoEyeSprite(FakeGraphics gra, float cx, float cy, float eyeW,
                                           float open, float rot, int spin) {
        float eyeH = eyeW * 0.5f * clamp01(open);
        if (eyeH < 0.4f) {
            drawTexSpriteRot(gra, getHypnoEyeTex(), cx, cy, eyeW * 2.2857f,
                    Math.max(1.2f, eyeW * 0.08f), rot, 1f);
            return;
        }
        drawTexSpriteRot(gra, getHypnoEyeTex(), cx, cy, eyeW * 2.2857f, eyeH * 2.4242f, rot, 1f);
        if (eyeH > 0.16f * eyeW) {
            drawTexSpriteRot(gra, getHypnoPupilTex(), cx, cy, 0.84f * eyeW, 0.84f * eyeW,
                    -spin * 0.45f, 2.13f * eyeH / eyeW);
            gOpaque = true;
            try {
                fakeDisc(gra, cx - 0.1286f * eyeW, cy - 0.303f * eyeH,
                        Math.max(1f, 0.0643f * eyeW), Color.WHITE, 255);
            } finally {
                gOpaque = false;
            }
        }
    }

    private static float[] hypnoHoverPos(TracerBullet b, int age, float mx, float my, float siz) {
        float h1 = ((b.seed * 131 + 17) & 255) / 255f;
        float h2 = ((b.seed * 197 + 59) & 255) / 255f;
        float h3 = ((b.seed * 89 + 133) & 255) / 255f;
        float ax = mx - (10f + h1 * 30f) * siz;
        float ay = my - (45f + h2 * 30f) * siz;
        float wx = (float) (Math.sin(age * (0.035 + 0.02 * h1) + h3 * 6.28)
                + 0.6 * Math.sin(age * (0.013 + 0.011 * h2) + h1 * 6.28)) * (16f + 12f * h2) * siz;
        float wy = (float) (Math.sin(age * (0.045 + 0.018 * h2) + h2 * 6.28)
                + 0.5 * Math.sin(age * (0.017 + 0.013 * h3) + h2 * 6.28)) * (9f + 8f * h1) * siz;
        float rise = smooth(clamp01(age / 14f));
        return new float[]{mx + (ax + wx - mx) * rise, my + (ay + wy - my) * rise};
    }

    private static void drawHypnoCharge(FakeGraphics gra, TracerBullet b, float mx, float my,
                                        float siz) {
        float c = clamp01(b.age / (float) HYPNO_CHARGE_FRAMES);
        drawGlowTexAdditive(gra, getHypnoGlow(), mx, my, (8f + 24f * c) * siz, Math.round(150f * c));
        drawRingTex(gra, getHypnoRing(1), mx, my, (24f * (1f - c) + 9f) * siz, Math.round(225f * c));
        drawRingTex(gra, getHypnoRing(2), mx, my, (15f * (1f - c) + 5.5f) * siz, Math.round(200f * c));

        drawHypnoEyeSprite(gra, mx, my, (5f + 5f * c) * siz, c, 0f, b.age);
        float[] anch = hypnoHoverPos(b, 14, mx, my, siz);
        float dx = anch[0] - mx, dy = anch[1] - my;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) {
            float ux = dx / len, uy = dy / len;
            for (int k = 1; k <= 2; k++) {
                float d = (10f + 11f * k) * siz;
                drawRingTex(gra, getHypnoRing(3), mx + ux * d, my + uy * d,
                        (3f + 2.4f * k) * siz, Math.round((120f - 35f * k) * c));
            }
        }
    }

    private static void drawHypnoHover(FakeGraphics gra, TracerBullet b, float mx, float my,
                                       float tx, float ty, float siz) {
        float[] p0 = hypnoHoverPos(b, b.age, mx, my, siz);
        float px = p0[0], py = p0[1];
        float[] p1 = hypnoHoverPos(b, Math.max(0, b.age - 2), mx, my, siz);
        float dx = px - p1[0], dy = py - p1[1];
        float dl = (float) Math.sqrt(dx * dx + dy * dy);
        float ux = dl > 0.15f ? dx / dl : -1f;
        float uy = dl > 0.15f ? dy / dl : 0f;
        boolean aiming = b.targetAlive && b.age >= b.hover - 6;
        if (aiming) {
            float adx = tx - px, ady = ty - py;
            float al = (float) Math.sqrt(adx * adx + ady * ady);
            if (al > 1f) {
                ux = adx / al;
                uy = ady / al;
            }
            px -= ux * 3f * siz;
            py -= uy * 3f * siz;
        }

        if (b.histCount == 0
                || Math.abs(b.histX[0] - px) > 0.5f || Math.abs(b.histY[0] - py) > 0.5f) {
            System.arraycopy(b.histX, 0, b.histX, 1, HYPNO_TRAIL_LEN - 1);
            System.arraycopy(b.histY, 0, b.histY, 1, HYPNO_TRAIL_LEN - 1);
            b.histX[0] = px;
            b.histY[0] = py;
            if (b.histCount < HYPNO_TRAIL_LEN) b.histCount++;
        }
        int n = Math.min(b.histCount, HYPNO_TRAIL_LEN);

        for (int i = n - 1; i >= 0; i--) {
            float f = 1f - i / (float) HYPNO_TRAIL_LEN;
            drawGlowTexAdditive(gra, getHypnoGlow(), b.histX[i], b.histY[i],
                    (3f + 6f * f) * siz, Math.round(42f * f));
        }

        drawGlowTexAdditive(gra, getHypnoGlow(), px, py, 18f * siz, 120);

        if (b.age > 8) {
            float pt = (b.age % 32) / 32f;
            drawRingTex(gra, getHypnoRing(3), px, py, (5f + 13f * pt) * siz,
                    Math.round(130f * (1f - pt)));
        }

        for (int k = 0; k < 3; k++) {
            double a = b.age * 0.07 + k * 2.094 + b.seed;
            float orr = (7f + k * 2.5f) * siz;
            FakeImage sp = k == 0 ? getHypnoGlowAcid() : getHypnoSpark();
            drawTexAdditiveRot(gra, sp, px + (float) Math.cos(a) * orr,
                    py + (float) Math.sin(a) * orr * 0.6f, 8f * siz, 8f * siz,
                    (float) a, 170);
        }

        float eyeW = 12f * siz;
        float open = hypnoBlinkOpen(b) * smooth(clamp01(b.age / 8f));
        if (aiming) open = Math.min(open, 0.45f);
        float sway = 0.14f * (float) Math.sin(b.age * 0.05 + b.seed);
        drawHypnoEyeSprite(gra, px, py, eyeW, open, sway, b.age);
        if (aiming) {
            float ang = (float) Math.atan2(uy, ux);
            drawTexAdditiveRot(gra, getHypnoSpark(), px + ux * 11f * siz, py + uy * 11f * siz,
                    11f * siz, 11f * siz, ang, 235);
        }
    }

    private static void drawHypnoTracer(FakeGraphics gra, TracerBullet b, float mx, float my,
                                        float tx, float ty, float siz) {
        FakeImage tex = getHypnoGlow();

        float[] s0 = hypnoHoverPos(b, b.hoverExit, mx, my, siz);
        float sx = s0[0], sy = s0[1];
        float t = clamp01(b.age / (float) Math.max(1, b.flight));
        float dx = tx - sx, dy = ty - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float ux = len > 1f ? dx / len : -1f, uy = len > 1f ? dy / len : 0f;
        float nx = -uy, ny = ux;
        float damp = 1f - smooth(clamp01((t - 0.55f) / 0.45f));
        float seedPh = ((b.seed * 73 + 11) & 255) / 255f * 6.2832f;
        float weave = (float) Math.sin(t * Math.PI * 3.0 + seedPh) * HYPNO_WEAVE_AMP * siz * damp;
        float px = sx + dx * t + nx * weave;
        float py = sy + dy * t + ny * weave;

        if (b.age <= HYPNO_MUZZLE_FLASH) {
            float fl = 1f - b.age / (float) HYPNO_MUZZLE_FLASH;
            float flD = (16f + 34f * (1f - fl)) * siz;
            drawGlowTexAdditive(gra, tex, sx, sy, (20f + 22f * (1f - fl)) * siz, Math.round(190f * fl));
            drawRingTex(gra, getHypnoRing(0), sx, sy, (7f + 20f * (1f - fl)) * siz,
                    Math.round(210f * fl));
            drawTexAdditiveRot(gra, getHypnoSpiralTex(), sx, sy, flD, flD,
                    b.age * 0.5f, Math.round(240f * fl));
        }

        if (b.histCount == 0
                || Math.abs(b.histX[0] - px) > 0.5f || Math.abs(b.histY[0] - py) > 0.5f) {
            System.arraycopy(b.histX, 0, b.histX, 1, HYPNO_TRAIL_LEN - 1);
            System.arraycopy(b.histY, 0, b.histY, 1, HYPNO_TRAIL_LEN - 1);
            b.histX[0] = px;
            b.histY[0] = py;
            if (b.histCount < HYPNO_TRAIL_LEN) b.histCount++;
        }
        int n = Math.min(b.histCount, HYPNO_TRAIL_LEN);

        for (int i = n - 1; i >= 0; i--) {
            float f = 1f - i / (float) HYPNO_TRAIL_LEN;
            drawGlowTexAdditive(gra, tex, b.histX[i], b.histY[i], (5f + 10f * f) * siz, Math.round(36f * f));
        }

        float pax = Float.NaN, pay = 0f, pbx = 0f, pby = 0f;
        for (int i = 0; i < n - 1; i++) {
            float sdx = b.histX[i] - b.histX[i + 1];
            float sdy = b.histY[i] - b.histY[i + 1];
            float slen = (float) Math.sqrt(sdx * sdx + sdy * sdy);
            if (slen < 0.5f) continue;
            float snx = -sdy / slen, sny = sdx / slen;
            float ph = b.age * 1.1f - i * 0.95f + b.seed;
            float amp = (3.2f + i * 0.5f) * siz;
            float off = (float) Math.sin(ph) * amp;
            float fade = 1f - i / (float) HYPNO_TRAIL_LEN;
            float ax = b.histX[i] + snx * off, ay = b.histY[i] + sny * off;
            float bx = b.histX[i] - snx * off, by = b.histY[i] - sny * off;
            if (!Float.isNaN(pax)) {
                fakeLine(gra, pax, pay, ax, ay, i < 2 ? HYPNO_HOT : HYPNO_MAGENTA,
                        1.7f * siz, Math.round(190f * fade));
                fakeLine(gra, pbx, pby, bx, by, HYPNO_ACID, 1.4f * siz, Math.round(150f * fade));
            }

            drawTexAdditiveRot(gra, getHypnoSpark(), ax, ay, 7f * siz, 7f * siz,
                    ph, Math.round(190f * fade));
            drawGlowTexAdditive(gra, getHypnoGlowAcid(), bx, by, 6f * siz, Math.round(120f * fade));
            pax = ax;
            pay = ay;
            pbx = bx;
            pby = by;
        }

        for (int k = 2; k < n; k += 3) {
            float fade = 1f - k / (float) HYPNO_TRAIL_LEN;
            drawRingTex(gra, getHypnoRing(3), b.histX[k], b.histY[k], (4f + k * 1.6f) * siz,
                    Math.round(130f * fade));
        }

        drawGlowTexAdditive(gra, tex, px, py, 26f * siz, 210);
        drawGlowTexAdditive(gra, getHypnoGlowAcid(), px - ux * 7f * siz, py - uy * 7f * siz, 12f * siz, 110);
        drawHypnoEyeSprite(gra, px, py, 12f * siz, 0.42f, 0f, b.age * 3);
        float angH = (float) Math.atan2(uy, ux);
        drawTexAdditiveRot(gra, getHypnoSpark(), px + ux * 14f * siz, py + uy * 14f * siz,
                12f * siz, 12f * siz, angH, 190);
    }

    private static void drawHypnoImpact(FakeGraphics gra, TracerBullet b, float cx, float cy, float is) {
        float f = 1f - clamp01(b.impactAge / (float) HYPNO_IMPACT_FRAMES);
        if (f <= 0f) return;
        float grow = 1f - f;

        drawGlowTexAdditive(gra, getHypnoGlow(), cx, cy, (22f + 30f * grow) * is, Math.round(210f * f));
        drawGlowTexAdditive(gra, getVolleyWhiteGlow(), cx, cy, (8f + 16f * grow) * is, Math.round(230f * f));
        drawRingTex(gra, getHypnoRing(0), cx, cy, (54f - 40f * grow) * is, Math.round(235f * f));
        drawRingTex(gra, getHypnoRing(1), cx, cy, (44f - 30f * grow) * is, Math.round(210f * f));

        for (int i = 0; i < 6; i++) {
            float h = ((b.seed * 67 + i * 149 + 23) & 255) / 255f;
            double a = i * Math.PI * 2.0 / 6.0 + (h - 0.5f) * 0.4 + b.seed * 0.9;
            float rOut = (46f - 18f * grow) * is;
            drawBlade(gra, cx + (float) Math.cos(a) * rOut, cy + (float) Math.sin(a) * rOut,
                    a + Math.PI, 0f, (26f - 12f * grow) * is, 2.4f * is * f,
                    Color.WHITE, HYPNO_MAGENTA, Math.round(220f * f));
        }

        float spD = (22f + 36f * grow) * is;
        drawTexAdditiveRot(gra, getHypnoSpiralTex(), cx, cy, spD, spD,
                b.impactAge * 0.55f + b.seed, Math.round(245f * f));

        for (int k = 0; k < 6; k++) {
            float g1 = ((k * 97 + b.seed * 31 + 11) & 255) / 255f;
            double ang = g1 * Math.PI * 2.0;
            float d = (44f * (1f - 0.8f * grow) * (0.5f + g1 * 0.5f)) * is;
            FakeImage sp = k % 2 == 0 ? getHypnoGlowAcid() : getHypnoSpark();
            drawTexAdditiveRot(gra, sp, cx + (float) Math.cos(ang) * d,
                    cy + (float) Math.sin(ang) * d, 8f * is, 8f * is,
                    (float) ang, Math.round(210f * f));
        }
    }

    private static Color lerpColor(Color a, Color b, float t) {
        t = clamp01(t);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static void drawBeamGradLayer(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                          Color base, float width, int alpha, int segs, float narrowToTip) {
        float dx = x1 - x0, dy = y1 - y0;
        for (int i = 0; i < segs; i++) {
            float ta = i / (float) segs, tb = (i + 1f) / (float) segs;
            float whiten = smooth(clamp01(((ta + tb) * 0.5f - 0.32f) / 0.68f));
            Color c = lerpColor(base, Color.WHITE, whiten);
            float w = width * (1f - narrowToTip * whiten);
            fakeLine(gra, x0 + dx * ta, y0 + dy * ta, x0 + dx * tb, y0 + dy * tb, c, w, alpha);
        }
    }

    private static void drawSpiralCometKamehameha(FakeGraphics gra, CrazyRuntime.StageRuntime rt,
                                                  float muzzleX, float muzzleY, float endX, float endY,
                                                  float intensity) {
        Color white = Color.WHITE;
        Color core = new Color(190, 248, 255);
        Color cyan = new Color(64, 218, 255);
        Color aura = new Color(28, 128, 255);
        Color deep = new Color(18, 74, 212);
        float pulse = (float) ((Math.sin(rt.beam.frame * 0.28) + 1.0) * 0.5);

        drawBeamGlowAdditive(gra, muzzleX, muzzleY, endX, endY, 64f + pulse * 12f, Math.round(60f * intensity));
        fakeLine(gra, muzzleX, muzzleY, endX, endY, new Color(80, 170, 255), 40f + pulse * 6f, 55);
        fakeLine(gra, muzzleX, muzzleY, endX, endY, deep, 27f + pulse * 5f, 120);
        gOpaque = true;
        try {
            fakeLine(gra, muzzleX, muzzleY, endX, endY, aura, 20f + pulse * 4f, 255);
            fakeLine(gra, muzzleX, muzzleY, endX, endY, cyan, 13f + pulse * 2f, 255);
            fakeLine(gra, muzzleX, muzzleY, endX, endY, core, 8f + pulse * 2f, 255);
            fakeLine(gra, muzzleX, muzzleY, endX, endY, white, 3.5f + pulse * 1.5f, 255);
        } finally {
            gOpaque = false;
        }

        drawBeamGlowAdditive(gra, muzzleX, muzzleY, endX, endY, 46f + pulse * 10f, Math.round(95f * intensity));
        drawBeamGlowAdditive(gra, muzzleX, muzzleY, endX, endY, 22f + pulse * 5f, Math.round(165f * intensity));
        drawKamePlasmaSleeve(gra, muzzleX, muzzleY, endX, endY, rt.beam.frame, cyan, aura, white);
        drawKameForwardStreaks(gra, muzzleX, muzzleY, endX, endY, rt.beam.frame, cyan, deep, white);

        drawGlowAdditive(gra, muzzleX, muzzleY, 60f + pulse * 24f, Math.round(160f * intensity));
        fakeDisc(gra, muzzleX, muzzleY, 23f + pulse * 7f, aura, 94);
        fakeDisc(gra, muzzleX, muzzleY, 15f + pulse * 5f, cyan, 150);
        gOpaque = true;
        try {
            fakeDisc(gra, muzzleX, muzzleY, 7f + pulse * 3f, white, 255);
        } finally {
            gOpaque = false;
        }
        fakeRing(gra, muzzleX, muzzleY, 28f + pulse * 8f, 3f, cyan, 150, rt.beam.frame * 0.22f);
        fakeRing(gra, muzzleX, muzzleY, 42f - pulse * 6f, 2f, aura, 88, -rt.beam.frame * 0.16f);

        float hdx = endX - muzzleX;
        float hdy = endY - muzzleY;
        float hlen = (float) Math.sqrt(hdx * hdx + hdy * hdy);
        float hux = hlen > 1f ? hdx / hlen : 1f;
        float huy = hlen > 1f ? hdy / hlen : 0f;
        if (rt.beam.kameReached) {

            drawKameRayBurst(gra, endX, endY, hux, huy, rt.beam.frame, 0.83f, intensity);
        } else {

            drawKameTravelHead(gra, endX, endY, hux, huy, rt.beam.frame);
        }
    }

    private static void drawKameTravelHead(FakeGraphics gra, float hx, float hy,
                                           float ux, float uy, int frame) {
        Color white = Color.WHITE;
        Color core = new Color(190, 248, 255);
        Color cyan = new Color(64, 218, 255);
        Color aura = new Color(28, 128, 255);
        Color deep = new Color(18, 74, 212);
        float pulse = (float) ((Math.sin(frame * 0.35) + 1.0) * 0.5);
        float bx = -ux, by = -uy;
        float px = -uy, py = ux;

        drawGlowAdditive(gra, hx, hy, 140f + pulse * 30f, 110);
        drawGlowAdditive(gra, hx, hy, 70f + pulse * 16f, 200);

        for (int i = 1; i <= 9; i++) {
            float d = i * (12f + pulse * 1.5f);
            float r = (52f - i * 5f) + pulse * 3f;
            float flick = (float) Math.sin(frame * 0.4 + i) * 3f;
            fakeDisc(gra, hx + bx * d + px * flick, hy + by * d + py * flick,
                    Math.max(5f, r), i % 2 == 0 ? aura : cyan, 128 - i * 11);
        }

        fakeDisc(gra, hx, hy, 64f + pulse * 14f, deep, 84);
        fakeDisc(gra, hx, hy, 50f + pulse * 11f, aura, 128);
        fakeDisc(gra, hx, hy, 36f + pulse * 9f, cyan, 178);
        fakeDisc(gra, hx, hy, 22f + pulse * 6f, core, 218);
        fakeDisc(gra, hx, hy, 11f + pulse * 4f, white, 252);

        for (int i = 1; i <= 5; i++) {
            float d = i * (12f + pulse * 1.5f);
            float r = (44f - i * 6f) + pulse * 3f;
            fakeDisc(gra, hx + ux * d, hy + uy * d, Math.max(4f, r), i % 2 == 0 ? white : cyan, 185 - i * 26);
        }

        fakeRing(gra, hx, hy, 70f + pulse * 14f, 3.5f, cyan, 150, frame * 0.2f);
        fakeRing(gra, hx, hy, 90f - pulse * 9f, 2.5f, aura, 92, -frame * 0.14f);

        for (int i = 0; i < 8; i++) {
            double a = frame * 0.13 + i * Math.PI * 2.0 / 8.0;
            float r0 = 30f + pulse * 6f;
            float r1 = 76f + (i % 3) * 14f + pulse * 12f;
            float sx = hx + (float) Math.cos(a) * r0;
            float sy = hy + (float) Math.sin(a) * r0;
            float mx = hx + (float) Math.cos(a + 0.3) * (r1 * 0.7f)
                    + (float) Math.sin(frame * 0.5 + i) * 7f;
            float my = hy + (float) Math.sin(a + 0.3) * (r1 * 0.7f)
                    + (float) Math.cos(frame * 0.5 + i) * 7f;
            float ex = hx + (float) Math.cos(a + 0.1) * r1;
            float ey = hy + (float) Math.sin(a + 0.1) * r1;
            fakeLine(gra, sx, sy, mx, my, white, 2f, 185);
            fakeLine(gra, mx, my, ex, ey, cyan, 2f, 150);
        }
    }

    private static void drawKamePlasmaSleeve(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                             int frame, Color cyan, Color aura, Color white) {
        drawKameWave(gra, x0, y0, x1, y1, frame, cyan, 0f, 10f, 3.0f, 152);
        drawKameWave(gra, x0, y0, x1, y1, frame, aura, (float) Math.PI, 8f, 2.0f, 118);
        drawKameWave(gra, x0, y0, x1, y1, frame, white, (float) Math.PI * 0.5f, 5f, 1.5f, 150);
    }

    private static void drawKameWave(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                     int frame, Color color, float phaseOffset, float radius,
                                     float width, int alpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 2f) return;
        float nx = -dy / len;
        float ny = dx / len;
        int samples = Math.max(10, Math.min(46, (int) (len / 24f)));
        float lastX = x0;
        float lastY = y0;
        for (int i = 1; i <= samples; i++) {
            float p = i / (float) samples;
            float phase = p * (float) Math.PI * 7.0f - frame * 0.26f + phaseOffset;
            float amp = (float) Math.sin(phase) * radius;
            float x = x0 + dx * p + nx * amp;
            float y = y0 + dy * p + ny * amp;
            fakeLine(gra, lastX, lastY, x, y, color, width, alpha);
            lastX = x;
            lastY = y;
        }
    }

    private static void drawKameForwardStreaks(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                               int frame, Color cyan, Color deep, Color white) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 2f) return;
        float ux = dx / len;
        float uy = dy / len;
        float nx = -uy;
        float ny = ux;
        for (int i = 0; i < 13; i++) {
            float p = ((frame * 0.045f) + i * 0.079f) % 1f;
            float side = (float) Math.sin(frame * 0.17f + i * 1.6f) * (6f + (i % 3) * 2f);
            float x = x0 + dx * p + nx * side;
            float y = y0 + dy * p + ny * side;
            float back = 42f + (i % 4) * 9f;
            float front = 13f + (i % 3) * 5f;
            Color c = i % 4 == 0 ? white : (i % 2 == 0 ? cyan : deep);
            fakeLine(gra, x - ux * back, y - uy * back, x + ux * front, y + uy * front,
                    c, i % 4 == 0 ? 1.5f : 2.5f, i % 4 == 0 ? 170 : 118);
        }
    }

    private static Color splashColor(float d01) {
        d01 = clamp01(d01);
        Color white = Color.WHITE;
        Color flash = new Color(226, 245, 255);
        Color cyan = new Color(120, 224, 255);
        Color azure = new Color(28, 128, 255);
        if (d01 < 0.42f) return lerpColor(white, flash, smooth(d01 / 0.42f));
        if (d01 < 0.74f) return lerpColor(flash, cyan, smooth((d01 - 0.42f) / 0.32f));
        return lerpColor(cyan, azure, smooth((d01 - 0.74f) / 0.26f));
    }

    private static volatile FakeImage kameGlow;

    private static FakeImage getKameGlow() {
        FakeImage g = kameGlow;
        if (g != null) return g;
        try {
            final int N = 128;
            FakeImage img = ImageBuilder.builder.build(N, N);
            float c = (N - 1) / 2f;
            Color white = Color.WHITE;
            Color cyan = new Color(120, 200, 255);
            Color ki = new Color(30, 130, 255);
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    float dx = (x - c) / (N / 2f), dy = (y - c) / (N / 2f);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    Color base = (d < 0.5f)
                            ? lerpColor(white, cyan, smooth(d / 0.5f))
                            : lerpColor(cyan, ki, smooth(clamp01((d - 0.5f) / 0.5f)));
                    float env = clamp01(1f - d);
                    env = env * env;
                    int r = Math.round(base.getRed() * env);
                    int gg = Math.round(base.getGreen() * env);
                    int b = Math.round(base.getBlue() * env);
                    img.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
                }
            }
            kameGlow = img;
            Logger.log("Kame glow texture baked: " + N + "x" + N);
            return img;
        } catch (Throwable t) {
            Logger.err("Kame glow texture bake failed", t);
            return null;
        }
    }

    private static void drawGlowAdditive(FakeGraphics gra, float cx, float cy, float diameter, int p0) {
        drawGlowTexAdditive(gra, getKameGlow(), cx, cy, diameter, p0);
    }

    private static void drawGlowTexAdditive(FakeGraphics gra, FakeImage tex, float cx, float cy,
                                            float diameter, int p0) {
        if (tex == null || diameter < 2f || p0 <= 1) return;
        float d = diameter * gScale;
        try {
            gra.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, p0)), 1);
            gra.drawImage(tex, cx - d / 2f, cy - d / 2f, d, d);
        } finally {
            resetComposite(gra);
        }
    }

    private static final FakeImage[] volleyGlowTex = new FakeImage[VOLLEY_PALETTES.length];
    private static volatile FakeImage volleyWhiteGlow;

    private static FakeImage getVolleyGlow(int pi) {
        if (pi < 0 || pi >= volleyGlowTex.length) return getKameGlow();
        FakeImage g = volleyGlowTex[pi];
        if (g != null) return g;
        VolleyPalette pal = VOLLEY_PALETTES[pi];
        FakeImage img = bakeRadialGlow(Color.WHITE, pal.hot, pal.mid);
        if (img != null) {
            volleyGlowTex[pi] = img;
            Logger.log("Volley glow texture baked: " + pal.name);
        }
        return img;
    }

    private static FakeImage getVolleyWhiteGlow() {
        FakeImage g = volleyWhiteGlow;
        if (g != null) return g;
        FakeImage img = bakeRadialGlow(Color.WHITE, Color.WHITE, new Color(230, 230, 230));
        if (img != null) {
            volleyWhiteGlow = img;
            Logger.log("Volley white glow texture baked");
        }
        return img;
    }

    private static volatile FakeImage hypnoGlowMagenta;
    private static volatile FakeImage hypnoGlowAcid;

    private static FakeImage getHypnoGlow() {
        FakeImage g = hypnoGlowMagenta;
        if (g != null) return g;
        FakeImage img = bakeRadialGlow(Color.WHITE, HYPNO_HOT, HYPNO_MAGENTA);
        if (img != null) {
            hypnoGlowMagenta = img;
            Logger.log("Hypno magenta glow texture baked");
        }
        return img;
    }

    private static FakeImage getHypnoGlowAcid() {
        FakeImage g = hypnoGlowAcid;
        if (g != null) return g;
        FakeImage img = bakeRadialGlow(Color.WHITE, new Color(225, 255, 180), HYPNO_ACID);
        if (img != null) {
            hypnoGlowAcid = img;
            Logger.log("Hypno acid glow texture baked");
        }
        return img;
    }

    private static volatile FakeImage hypnoEyeTex;
    private static volatile FakeImage hypnoPupilTex;
    private static volatile FakeImage hypnoSparkTex;
    private static volatile FakeImage hypnoSpiralTex;
    private static volatile FakeImage hypnoRingWhite;
    private static volatile FakeImage hypnoRingMagenta;
    private static volatile FakeImage hypnoRingAcid;
    private static volatile FakeImage hypnoRingViolet;

    private static FakeImage getHypnoEyeTex() {
        FakeImage g = hypnoEyeTex;
        if (g != null) return g;
        g = bakeHypnoEye();
        if (g != null) hypnoEyeTex = g;
        return g;
    }

    private static FakeImage getHypnoPupilTex() {
        FakeImage g = hypnoPupilTex;
        if (g != null) return g;
        g = bakeHypnoPupil();
        if (g != null) hypnoPupilTex = g;
        return g;
    }

    private static FakeImage getHypnoSpark() {
        FakeImage g = hypnoSparkTex;
        if (g != null) return g;
        g = bakeHypnoSpark();
        if (g != null) hypnoSparkTex = g;
        return g;
    }

    private static FakeImage getHypnoSpiralTex() {
        FakeImage g = hypnoSpiralTex;
        if (g != null) return g;
        g = bakeHypnoSpiral();
        if (g != null) hypnoSpiralTex = g;
        return g;
    }

    private static FakeImage getHypnoRing(int which) {
        FakeImage g = which == 0 ? hypnoRingWhite : which == 1 ? hypnoRingMagenta
                : which == 2 ? hypnoRingAcid : hypnoRingViolet;
        if (g != null) return g;
        Color base = which == 0 ? Color.WHITE : which == 1 ? HYPNO_MAGENTA
                : which == 2 ? HYPNO_ACID : HYPNO_VIOLET;
        g = bakeHypnoRing(base);
        if (g != null) {
            if (which == 0) hypnoRingWhite = g;
            else if (which == 1) hypnoRingMagenta = g;
            else if (which == 2) hypnoRingAcid = g;
            else hypnoRingViolet = g;
        }
        return g;
    }

    private static float cov(float d) {
        return clamp01(0.5f + d / 1.2f);
    }

    private static void splatOver(float[] pr, float[] pg, float[] pb, float[] pa, int n,
                                  float px, float py, float rad, Color c, float alpha) {
        int x0 = Math.max(0, (int) (px - rad - 2f));
        int x1 = Math.min(n - 1, (int) (px + rad + 2f));
        int y0 = Math.max(0, (int) (py - rad - 2f));
        int y1 = Math.min(n - 1, (int) (py + rad + 2f));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - px, dy = y - py;
                float a = cov(rad - (float) Math.sqrt(dx * dx + dy * dy)) * alpha;
                if (a <= 0f) continue;
                int i = y * n + x;
                float ia = 1f - a;
                pr[i] = c.getRed() * a + pr[i] * ia;
                pg[i] = c.getGreen() * a + pg[i] * ia;
                pb[i] = c.getBlue() * a + pb[i] * ia;
                pa[i] = a + pa[i] * ia;
            }
        }
    }

    private static void splatAdd(float[] ar, float[] ag, float[] ab, int n,
                                 float px, float py, float rad, Color c, float alpha) {
        int x0 = Math.max(0, (int) (px - rad - 2f));
        int x1 = Math.min(n - 1, (int) (px + rad + 2f));
        int y0 = Math.max(0, (int) (py - rad - 2f));
        int y1 = Math.min(n - 1, (int) (py + rad + 2f));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - px, dy = y - py;
                float a = cov(rad - (float) Math.sqrt(dx * dx + dy * dy)) * alpha;
                if (a <= 0f) continue;
                int i = y * n + x;
                ar[i] += c.getRed() * a;
                ag[i] += c.getGreen() * a;
                ab[i] += c.getBlue() * a;
            }
        }
    }

    private static int clamp255(float v) {
        return v < 0f ? 0 : (v > 255f ? 255 : Math.round(v));
    }

    private static FakeImage bakeHypnoRing(Color base) {
        try {
            final int N = 64;
            FakeImage img = ImageBuilder.builder.build(N, N);
            float c = (N - 1) / 2f;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    float d = (float) Math.sqrt((x - c) * (x - c) + (y - c) * (y - c)) - 23f;
                    float env = (float) Math.exp(-(d * d) / (2f * 3.1f * 3.1f));
                    float core = (float) Math.exp(-(d * d) / (2f * 1.1f * 1.1f));
                    int r = clamp255(base.getRed() * env + 255f * core * 0.85f);
                    int g = clamp255(base.getGreen() * env + 255f * core * 0.85f);
                    int b = clamp255(base.getBlue() * env + 255f * core * 0.85f);
                    img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Hypno ring texture bake failed", t);
            return null;
        }
    }

    private static FakeImage bakeHypnoSpark() {
        try {
            final int N = 32;
            FakeImage img = ImageBuilder.builder.build(N, N);
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
                    int r = clamp255(255f * i);
                    int g = clamp255(255f * i * (0.80f + 0.20f * i));
                    int b = clamp255(255f * i * (0.90f + 0.10f * i));
                    img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Hypno spark texture bake failed", t);
            return null;
        }
    }

    private static FakeImage bakeHypnoSpiral() {
        try {
            final int N = 96;
            float[] ar = new float[N * N];
            float[] ag = new float[N * N];
            float[] ab = new float[N * N];
            float c = (N - 1) / 2f;
            for (int arm = 0; arm < 2; arm++) {
                Color col = arm == 0 ? HYPNO_MAGENTA : HYPNO_ACID;
                for (int s = 0; s < 900; s++) {
                    float p = s / 899f;
                    double ang = p * Math.PI * 2.0 * 1.9 + arm * Math.PI;
                    float rr = 2f + 40f * p;
                    float w = 1.2f + 2.6f * p;
                    Color cc = lerpColor(Color.WHITE, col, clamp01(p * 1.4f));
                    splatAdd(ar, ag, ab, N, c + (float) Math.cos(ang) * rr,
                            c + (float) Math.sin(ang) * rr, w, cc, 0.10f + 0.10f * p);
                }
            }
            FakeImage img = ImageBuilder.builder.build(N, N);
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    int i = y * N + x;
                    img.setRGB(x, y, 0xFF000000
                            | (clamp255(ar[i]) << 16) | (clamp255(ag[i]) << 8) | clamp255(ab[i]));
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Hypno spiral texture bake failed", t);
            return null;
        }
    }

    private static FakeImage bakeHypnoEye() {
        try {
            final int W = 160, H = 80;
            final float LW = 70f, LH = 33f;
            FakeImage img = ImageBuilder.builder.build(W, H);
            Color ink = new Color(24, 8, 50);
            Color lidHot = lerpColor(HYPNO_MAGENTA, HYPNO_HOT, 0.45f);
            float cx = (W - 1) / 2f, cy = (H - 1) / 2f;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    float fx = x - cx, fy = y - cy;
                    float p = clamp01((fx + LW) / (2f * LW));
                    float bow = (float) Math.sin(Math.PI * p) * LH;
                    float dEdge = bow - Math.abs(fy);
                    float aLens = cov(dEdge);
                    if (aLens <= 0f) {
                        img.setRGB(x, y, 0);
                        continue;
                    }
                    float r = ink.getRed(), g = ink.getGreen(), b = ink.getBlue();
                    float rad = (float) Math.sqrt(fx * fx + fy * fy);

                    float aIris = cov(26f - rad) * cov(dEdge - 2.6f);
                    if (aIris > 0f) {
                        r += (HYPNO_VIOLET.getRed() - r) * aIris;
                        g += (HYPNO_VIOLET.getGreen() - g) * aIris;
                        b += (HYPNO_VIOLET.getBlue() - b) * aIris;
                        float aRing = cov(23f - rad) * cov(rad - 19f);
                        r += (HYPNO_MAGENTA.getRed() - r) * aRing;
                        g += (HYPNO_MAGENTA.getGreen() - g) * aRing;
                        b += (HYPNO_MAGENTA.getBlue() - b) * aRing;
                        float aPit = cov(14f - rad);
                        r += (ink.getRed() - r) * aPit;
                        g += (ink.getGreen() - g) * aPit;
                        b += (ink.getBlue() - b) * aPit;
                    }

                    float dS = 4.5f - (float) Math.sqrt((fx + 9f) * (fx + 9f) + (fy + 10f) * (fy + 10f));
                    float aS = cov(dS);
                    r += (255f - r) * aS;
                    g += (255f - g) * aS;
                    b += (255f - b) * aS;
                    float dG = 2.5f - (float) Math.sqrt((fx - 8f) * (fx - 8f) + (fy - 9f) * (fy - 9f));
                    float aG = cov(dG) * 0.8f;
                    r += (HYPNO_HOT.getRed() - r) * aG;
                    g += (HYPNO_HOT.getGreen() - g) * aG;
                    b += (HYPNO_HOT.getBlue() - b) * aG;

                    float aStroke = cov(dEdge) * cov(2.4f - dEdge);
                    Color sc = fy < 0f ? lidHot : HYPNO_MAGENTA;
                    r += (sc.getRed() - r) * aStroke;
                    g += (sc.getGreen() - g) * aStroke;
                    b += (sc.getBlue() - b) * aStroke;
                    img.setRGB(x, y, (clamp255(255f * aLens) << 24)
                            | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b));
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Hypno eye texture bake failed", t);
            return null;
        }
    }

    private static FakeImage bakeHypnoPupil() {
        try {
            final int N = 64;
            float[] pr = new float[N * N];
            float[] pg = new float[N * N];
            float[] pb = new float[N * N];
            float[] pa = new float[N * N];
            float c = (N - 1) / 2f;
            for (int s = 0; s < 760; s++) {
                float p = s / 759f;
                double ang = p * Math.PI * 2.0 * 2.2;
                float rr = 1.6f + 22.4f * p;
                float w = 1.15f + 1.85f * p;
                splatOver(pr, pg, pb, pa, N, c + (float) Math.cos(ang) * rr,
                        c + (float) Math.sin(ang) * rr, w,
                        lerpColor(Color.WHITE, HYPNO_HOT, p * 0.9f), 0.95f);
            }
            splatOver(pr, pg, pb, pa, N, c, c, 3f, Color.WHITE, 1f);
            FakeImage img = ImageBuilder.builder.build(N, N);
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    int i = y * N + x;
                    float a = pa[i];
                    if (a <= 0.004f) {
                        img.setRGB(x, y, 0);
                        continue;
                    }
                    img.setRGB(x, y, (clamp255(255f * a) << 24)
                            | (clamp255(pr[i] / a) << 16) | (clamp255(pg[i] / a) << 8)
                            | clamp255(pb[i] / a));
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Hypno pupil texture bake failed", t);
            return null;
        }
    }

    private static void drawTexAdditiveRot(FakeGraphics gra, FakeImage tex, float cx, float cy,
                                           float w, float h, float rot, int p0) {
        if (tex == null || w < 2f || h < 1f || p0 <= 1) return;
        w *= gScale;
        h *= gScale;
        FakeTransform at = gra.getTransform();
        try {
            gra.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, p0)), 1);
            gra.translate(cx, cy);
            if (rot != 0f) gra.rotate(rot);
            gra.drawImage(tex, -w / 2f, -h / 2f, w, h);
        } finally {
            resetComposite(gra);
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void drawTexSpriteRot(FakeGraphics gra, FakeImage tex, float cx, float cy,
                                         float w, float h, float rot, float squashY) {
        if (tex == null || w < 2f || h < 1f || squashY <= 0.01f) return;
        w *= gScale;
        h *= gScale;
        FakeTransform at = gra.getTransform();
        try {
            gra.setComposite(FakeGraphics.DEF, 0, 0);
            gra.translate(cx, cy);
            if (squashY != 1f) gra.scale(1f, squashY);
            if (rot != 0f) gra.rotate(rot);
            gra.drawImage(tex, -w / 2f, -h / 2f, w, h);
        } finally {
            resetComposite(gra);
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void drawRingTex(FakeGraphics gra, FakeImage tex, float cx, float cy,
                                    float radius, int p0) {
        drawTexAdditiveRot(gra, tex, cx, cy, radius * 2.783f, radius * 2.783f, 0f, p0);
    }

    private static FakeImage bakeRadialGlow(Color c0, Color c1, Color c2) {
        try {
            final int N = 64;
            FakeImage img = ImageBuilder.builder.build(N, N);
            float c = (N - 1) / 2f;
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    float dx = (x - c) / (N / 2f), dy = (y - c) / (N / 2f);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    Color base = (d < 0.45f)
                            ? lerpColor(c0, c1, smooth(d / 0.45f))
                            : lerpColor(c1, c2, smooth(clamp01((d - 0.45f) / 0.55f)));
                    float env = clamp01(1f - d);
                    env = env * env;
                    int r = Math.round(base.getRed() * env);
                    int gg = Math.round(base.getGreen() * env);
                    int b = Math.round(base.getBlue() * env);
                    img.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
                }
            }
            return img;
        } catch (Throwable t) {
            Logger.err("Volley glow texture bake failed", t);
            return null;
        }
    }

    private static void drawBeamGlowAdditive(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                             float glowW, int p0) {
        if (glowW < 2f || p0 <= 1) return;
        FakeImage g = getKameGlow();
        if (g == null) return;
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        float gd = glowW * gScale;
        float step = Math.max(6f, gd * 0.5f);
        int n = Math.max(1, Math.round(len / step));
        try {
            gra.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, p0)), 1);
            for (int i = 0; i <= n; i++) {
                float p = i / (float) n;
                float cx = x0 + dx * p, cy = y0 + dy * p;
                gra.drawImage(g, cx - gd / 2f, cy - gd / 2f, gd, gd);
            }
        } finally {
            resetComposite(gra);
        }
    }

    private static final float[] AURA_FR = {0.0f, 0.4f, 0.8f, 1.0f};
    private static final int[]   AURA_AL = {120, 75, 28, 0};
    private static final Color[] AURA_COL = {
            new Color(135, 206, 250), new Color(30, 144, 255), new Color(0, 0, 255), new Color(0, 0, 255)};

    private static final float[] CORE_FR = {0.0f, 1.0f};
    private static final int[]   CORE_AL = {200, 0};
    private static final Color[] CORE_COL = {Color.WHITE, new Color(200, 230, 255)};

    private static void drawBlade(FakeGraphics gra, float ox, float oy, double ang,
                                  float rootR, float len, float halfWBase,
                                  Color cRoot, Color cTip, int aBase) {
        len *= gScale; rootR *= gScale; halfWBase *= gScale;
        if (len < 1f || aBase <= 2 || halfWBase < 0.2f) return;
        FakeTransform at = gra.getTransform();
        try {
            gra.translate(ox, oy);
            gra.rotate((float) ang);
            float step = 1.5f;
            int n = Math.max(2, Math.round(len / step));
            boolean gl = gIsGL;
            for (int i = 0; i <= n; i++) {
                float fr = i / (float) n;
                float x = rootR + len * fr;
                float hw = halfWBase * (float) Math.pow(1f - fr, 1.4f);
                if (hw < 0.4f) hw = 0.4f;
                int a = Math.round(aBase * (1f - fr * fr));
                if (a <= 2) continue;
                Color c = lerpColor(cRoot, cTip, smooth(fr));
                if (gl) {
                    gra.colRect(x, -hw, step + 0.6f, 2f * hw,
                            c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
                } else {
                    setColor(gra, c, a);
                    gra.fillRect(x, -hw, step + 0.6f, 2f * hw);
                    resetComposite(gra);
                }
            }
        } finally {
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void drawKameRayBurst(FakeGraphics gra, float hx, float hy, float ux, float uy,
                                         int frame, float scale, float intensity) {

        Color white = Color.WHITE;
        Color neon = new Color(120, 200, 255);
        Color ki = new Color(30, 130, 255);
        Color light = new Color(200, 230, 255);
        float s = Math.max(0.25f, scale);
        float in = clamp01(intensity);
        if (in <= 0.01f) return;

        float blen = (float) Math.sqrt(ux * ux + uy * uy);
        float fx = blen > 0.001f ? ux / blen : 1f;
        float fy = blen > 0.001f ? uy / blen : 0f;
        double baseAng = Math.atan2(fy, fx);
        float bx = hx, by = hy;

        float pulse = 1f + 0.1f * (float) Math.sin(frame * 0.45);

        if (frame % 30 == 0) {
            Logger.log("Kame splash v18 fat-blades+smooth-thick-rings(x3): angDeg=" + Math.round(Math.toDegrees(baseAng))
                    + " dir=(" + Math.round(fx * 100) + "," + Math.round(fy * 100) + ") s=" + s);
        }

        float reach = 175f * s * (0.6f + 0.4f * in);
        float sphereR = 170f * s * pulse;
        float rootBase = 5f * s;
        float flick = 0.85f + 0.15f * (float) Math.sin(frame * 0.9f);
        float spin = frame * 0.004f;

        fakeRadial(gra, bx, by, sphereR, AURA_FR, AURA_AL, AURA_COL, in, 46, false);
        fakeRing(gra, bx, by, sphereR * 0.93f, 3f * s, new Color(150, 220, 255), Math.round(150f * in), 0f);
        fakeRing(gra, bx, by, sphereR * 1.00f, 2f * s, white, Math.round(110f * in), 0f);

        final int rings = 4;
        for (int r = 0; r < rings; r++) {
            float phase = frame * 0.020f + r / (float) rings;
            float rt = phase % 1f;
            float rr = rt * sphereR;
            int gen = (int) Math.floor(phase);
            float hw = ((gen * 131 + r * 89 + 7) & 255) / 255f;
            float startMult = 6f + hw * 30f;
            float w = Math.max(1.5f, 2f * s) * startMult * (1f - rt);
            if (w < 0.6f) continue;
            if (rr < Math.max(3f, w * 0.5f)) continue;
            float fade = rt < 0.5f ? 1f : (1f - (rt - 0.5f) / 0.5f);
            int a = Math.round(225f * in * fade);
            if (a <= 3) continue;
            Color rc = lerpColor(white, neon, rt);
            drawSmoothRing(gra, bx, by, rr, w, rc, a);
        }

        for (int w = 0; w < 2; w++) {
            float rt = (frame * 0.028f + w * 0.5f) % 1f;
            float rr = sphereR * 0.95f + rt * reach * 1.1f;
            int a = Math.round(150f * in * (1f - rt));
            if (a <= 3) continue;
            float ww = Math.max(2f, 3f * s * (1f - rt * 0.5f));
            fakeRing(gra, bx, by, rr, ww, lerpColor(white, neon, rt), a, 0f);
        }

        final int spikes = 16;
        for (int j = 0; j < spikes; j++) {
            float h1 = ((j * 149 + 23) & 255) / 255f;
            float h2 = ((j * 211 + 17) & 255) / 255f;
            float h3 = ((j * 97 + 41) & 255) / 255f;
            float h4 = ((j * 83 + 137) & 255) / 255f;
            float spd = 0.16f + ((j * 61) & 31) / 31f * 0.4f;
            float breathe = 0.78f + 0.22f * (float) Math.sin(frame * spd + j * 1.37f);
            double ang = (j / (double) spikes) * Math.PI * 2.0 + (h3 - 0.5f) * 0.18 + spin;

            float mult = (h1 > 0.9f) ? 2.0f : (h2 > 0.6f ? 1.7f : 1.0f);
            float len = (70f + h1 * 150f) * s * breathe * mult;

            float widthMult = 2f + h4 * 3f;
            float baseHalf = (4f + h2 * 4f) * s * 0.5f * widthMult;

            drawBlade(gra, bx, by, ang, rootBase, len, baseHalf, white, neon, Math.round(235f * in));
            drawBlade(gra, bx, by, ang, rootBase, len * 0.8f, baseHalf * 0.42f, white, white, Math.round(225f * in));
        }

        final int needles = 6;
        for (int j = 0; j < needles; j++) {
            float h = ((j * 173 + 53) & 255) / 255f;
            float spd = (0.1f + ((j * 53) & 31) / 31f * 0.3f) * 1.5f;
            float br = 0.75f + 0.25f * (float) Math.sin(frame * spd + j * 2.1f);
            double ang = ((j + 0.5) / (double) needles) * Math.PI * 2.0 + (h - 0.5f) * 0.3 + spin;
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            float nlen = (120f + h * 150f) * s * br;
            int a = Math.round(190f * in * br);
            fakeLine(gra, bx + cos * rootBase, by + sin * rootBase,
                    bx + cos * nlen, by + sin * nlen, white, Math.max(1f, 1.5f * s), a);
        }

        final int sparks = 28;
        for (int k = 0; k < sparks; k++) {
            float g1 = ((k * 97 + 11) & 255) / 255f;
            float g2 = ((k * 53 + 29) & 255) / 255f;
            float g3 = ((k * 131 + 7) & 255) / 255f;
            float g4 = ((k * 71 + 41) & 255) / 255f;
            float lifeSpd = 0.05f + g4 * 0.09f;
            float t = (frame * lifeSpd + g3) % 1f;
            double ang = g1 * Math.PI * 2.0;
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            float ease = 1f - (1f - t) * (1f - t);
            float d = sphereR * 0.3f + ease * reach * (0.6f + g2 * 1.0f);
            int a = Math.round(235f * (1f - t));
            if (a <= 3) continue;
            float px = bx + cos * d, py = by + sin * d;
            Color cc = lerpColor(white, light, t);
            if ((k & 1) == 0) {
                fakeDisc(gra, px, py, Math.max(1f, (1.2f + g4 * 1.8f) * s), cc, a);
            } else {
                float ln = (5f + g2 * 9f) * s;
                fakeLine(gra, px, py, px + cos * ln, py + sin * ln, cc, Math.max(1f, 1.4f * s), a);
            }
        }

        final int debris = 4;
        for (int k = 0; k < debris; k++) {
            float g1 = ((k * 89 + 17) & 255) / 255f, g2 = ((k * 61 + 7) & 255) / 255f, g3 = ((k * 113 + 31) & 255) / 255f;
            float t = (frame * (0.03f + g2 * 0.04f) + g3) % 1f;
            double ang = g1 * Math.PI * 2.0;
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            float ease = 1f - (1f - t) * (1f - t);
            float d = sphereR * 0.4f + ease * reach * 1.6f;
            int a = Math.round(220f * (1f - t));
            if (a <= 3) continue;
            fakeDisc(gra, bx + cos * d, by + sin * d, Math.max(1.5f, (2.5f + g2 * 1.5f) * s),
                    lerpColor(white, light, t), a);
        }

        drawGlowAdditive(gra, bx, by, 170f * s * pulse, Math.round(80f * in * flick));
        drawGlowAdditive(gra, bx, by, 110f * s * pulse, Math.round(130f * in * flick));
        drawGlowAdditive(gra, bx, by, 72f * s * pulse, Math.round(210f * in * flick));
        drawGlowAdditive(gra, bx, by, 30f * s * pulse, Math.round(255f * in));
    }

    private static void radialGlow(FakeGraphics gra, float cx, float cy, float rOuter,
                                   Color inner, Color outer, int innerAlpha, int steps) {
        if (rOuter < 1f || innerAlpha <= 1) return;
        for (int i = steps; i >= 1; i--) {
            float f = i / (float) steps;
            float r = rOuter * f;
            Color c = lerpColor(inner, outer, f);
            int a = Math.round(innerAlpha * (1f - f) * (1f - f));
            if (a > 1) fakeDisc(gra, cx, cy, r, c, a);
        }
    }

    private static void fakeRadial(FakeGraphics gra, float cx, float cy, float R,
                                   float[] fr, int[] al, Color[] col, float gain, int steps,
                                   boolean additive) {
        if (R < 1f || steps < 2) return;
        float pPrev = 1f;
        float tPrev = 0f;
        for (int i = steps; i >= 1; i--) {
            float f = i / (float) steps;
            int seg = fr.length - 2;
            for (int k = 0; k < fr.length - 1; k++) { if (f <= fr[k + 1]) { seg = k; break; } }
            float t = clamp01((f - fr[seg]) / Math.max(1e-4f, fr[seg + 1] - fr[seg]));
            float ta = al[seg] + (al[seg + 1] - al[seg]) * t;
            Color tc = lerpColor(col[seg], col[seg + 1], t);
            float target = clamp01(ta / 255f * gain);
            float src;
            if (additive) {
                src = target - tPrev;
                tPrev = target;
            } else {
                float pCur = 1f - target;
                src = 1f - pCur / pPrev;
                pPrev = pCur;
            }
            int a255 = Math.round(clamp01(src) * 255f);
            if (a255 > 0) fakeDisc(gra, cx, cy, R * f, tc, a255);
        }
    }

    private static void drawKameSpike(FakeGraphics gra, float ox, float oy, float cos, float sin,
                                      float rootR, float rTip, float halfW, Color color, int alpha) {
        if (rTip - rootR <= 0f || alpha <= 2) return;
        float nx = -sin, ny = cos;
        float r0x = ox + cos * rootR, r0y = oy + sin * rootR;
        float tx = ox + cos * rTip, ty = oy + sin * rTip;
        float lineW = Math.max(1.5f, halfW * 0.5f);
        for (int k = -2; k <= 2; k++) {
            float off = (k / 2f) * halfW;
            fakeLine(gra, r0x + nx * off, r0y + ny * off, tx, ty, color, lineW, alpha);
        }
    }

    private static float headLayerR(float p, float beamW, float ballR, float backLen) {
        if (p <= 0f) return beamW + (ballR - beamW) * smooth(clamp01((p + backLen) / backLen));
        if (p >= ballR) return 0f;
        return ballR * (float) Math.sqrt(Math.max(0f, 1f - (p / ballR) * (p / ballR)));
    }

    private static void drawKameHead(FakeGraphics gra, float cx, float cy, float fx, float fy,
                                     float s, float pulse, float backLen,
                                     Color white, Color core, Color cyan, Color aura, Color deep) {

        float rWhite = 26f * s;
        float rimW = 4.5f * s;

        final float beamWhiteR = 2f, beamAuraR = 10f;

        fakeDisc(gra, cx, cy, (rWhite + rimW) * 1.18f, deep, 48);
        gOpaque = true;
        try {

            float step = 2.4f;
            int n0 = Math.max(1, Math.round(backLen / step));
            for (int i = n0; i >= 1; i--) {
                float p = -i * step;
                float t = clamp01(1f + p / backLen);
                float st = smooth(t);
                float rAura = beamAuraR + (rWhite + rimW - beamAuraR) * st;
                float rCoreW = beamWhiteR + (rWhite - beamWhiteR) * st;
                float gx = cx + fx * p, gy = cy + fy * p;
                fakeDisc(gra, gx, gy, rAura, aura, 255);
                fakeDisc(gra, gx, gy, rCoreW, white, 255);
            }

            fakeDisc(gra, cx, cy, rWhite + rimW, aura, 255);
            fakeDisc(gra, cx, cy, rWhite, white, 255);
        } finally {
            gOpaque = false;
        }
    }

    private static void drawKameTriShard(FakeGraphics gra, float cx, float cy, float r, double rot,
                                         Color color, int alpha) {
        if (r < 1.5f || alpha <= 2) return;
        float x0 = cx + (float) Math.cos(rot) * r, y0 = cy + (float) Math.sin(rot) * r;
        float x1 = cx + (float) Math.cos(rot + 2.0944) * r, y1 = cy + (float) Math.sin(rot + 2.0944) * r;
        float x2 = cx + (float) Math.cos(rot + 4.1888) * r, y2 = cy + (float) Math.sin(rot + 4.1888) * r;

        int n = Math.max(3, Math.round(r));
        float lw = Math.max(1.6f, 2f * r / n + 1.2f);
        for (int q = 0; q <= n; q++) {
            float f = q / (float) n;
            float ex = x1 + (x2 - x1) * f, ey = y1 + (y2 - y1) * f;
            fakeLine(gra, x0, y0, ex, ey, color, lw, alpha);
        }
    }

    private static void drawKameShard(FakeGraphics gra, float ox, float oy, float cos, float sin,
                                      float rBase, float rTip, float halfW, Color color, int alpha) {
        float len = rTip - rBase;
        if (len <= 0f || halfW < 0.8f || alpha <= 2) return;
        int steps = Math.max(2, Math.round(len / Math.max(1.6f, halfW * 0.85f)));
        for (int k = 0; k <= steps; k++) {
            float f = k / (float) steps;
            float rad = halfW * (1f - f);
            if (rad < 0.7f) continue;
            float r = rBase + len * f;
            fakeDisc(gra, ox + cos * r, oy + sin * r, rad, color, alpha);
        }
    }

    private static void fakePixel(FakeGraphics gra, float cx, float cy, float size, Color color, int alpha) {
        size *= gScale;
        if (size < 0.6f || alpha <= 1) return;
        int sz = Math.max(1, Math.round(size));
        if (gIsGL) {
            gra.colRect(cx - sz * 0.5f, cy - sz * 0.5f, sz, sz,
                    color.getRed(), color.getGreen(), color.getBlue(), effAlpha(alpha));
        } else {
            setColor(gra, color, alpha);
            gra.fillRect(cx - sz * 0.5f, cy - sz * 0.5f, sz, sz);
            resetComposite(gra);
        }
    }

    private static void drawForgeRunes(FakeGraphics gra, float cx, float cy, float radius,
                                       int frame, Color emerald, Color gold) {
        for (int i = 0; i < 8; i++) {
            double ang = frame * 0.055 + i * Math.PI * 2.0 / 8.0;
            float x = cx + (float) Math.cos(ang) * radius;
            float y = cy + (float) Math.sin(ang) * radius;
            if (i % 2 == 0) {
                fakeRect(gra, x - 5f, y - 2f, 10f, 4f, emerald, 145);
                fakeRect(gra, x - 2f, y - 7f, 4f, 14f, gold, 120);
            } else {
                fakeDiamond(gra, x, y, 6f, gold, 145);
            }
        }
    }

    private static void drawForgeSparks(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                        int frame, Color ember, Color gold) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        for (int i = 0; i < 12; i++) {
            float p = ((frame * 0.025f) + i * 0.087f) % 1f;
            float rise = ((frame * 2 + i * 11) % 36);
            float x = x0 + dx * p + (float) Math.sin(frame * 0.21f + i) * 12f;
            float y = y0 + dy * p - rise;
            fakeDiamond(gra, x, y, 3f + (i % 3), i % 2 == 0 ? ember : gold, 145);
            if (i % 3 == 0) fakeLine(gra, x, y, x + 10f, y - 12f, gold, 2f, 110);
        }
    }

    private static void drawForgePlates(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                        int frame, Color emerald, Color gold, Color ember) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        int count = 10;
        for (int i = 0; i < count; i++) {
            float p = (i + 0.5f) / count;
            float x = x0 + dx * p;
            float y = y0 + dy * p;
            float lift = (float) Math.sin(frame * 0.18f + i) * 8f;
            Color c = i % 3 == 0 ? ember : (i % 3 == 1 ? emerald : gold);
            fakeRect(gra, x - 9f, y - 18f + lift, 18f, 5f, c, 115);
            fakeRect(gra, x - 5f, y + 14f - lift, 10f, 4f, c, 100);
            if (i % 2 == 0) fakeDiamond(gra, x, y - 2f, 6f, Color.WHITE, 90);
        }
    }

    private static void drawBeamEvents(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra,
                                       float groundY, float combatY) {
        for (int i = 0; i < rt.beam.events.size(); i++) {
            BeamEvent ev = rt.beam.events.get(i);
            float x = CrazyRender.screenX(bbpainter, ev.pos);
            float life = clamp01(1f - ev.age / 34f);
            if (ev.type == EVT_HYPNOSIS) {
                float y = groundY - 62f;
                Color lime = new Color(158, 255, 62);
                Color pink = new Color(255, 72, 192);
                fakeRing(gra, x, y, 18f + ev.age * 1.4f, 3f, lime, Math.round(170 * life), ev.age * 0.2f);
                fakeRing(gra, x, y, 34f - ev.age * 0.35f, 2f, pink, Math.round(150 * life), -ev.age * 0.16f);
                fakeSpiralDots(gra, x, y, 4f, 26f, ev.age, pink, lime, 8, 2.2f);
                fakeDiamond(gra, x, y, 7f + ev.age * 0.15f, Color.WHITE, Math.round(165 * life));
            } else if (ev.type == EVT_FORGE) {
                float y = groundY - 24f;
                Color emerald = new Color(32, 220, 142);
                Color gold = new Color(255, 226, 82);
                Color ember = new Color(255, 112, 42);
                fakeRing(gra, x, y, 22f + ev.age * 0.8f, 4f, gold, Math.round(160 * life), ev.age * 0.18f);
                drawForgeRunes(gra, x, y, 28f + ev.age * 0.6f, ev.age, emerald, gold);
                for (int s = 0; s < 7; s++) {
                    float sx = x + (s - 3) * 9f + (float) Math.sin(ev.age * 0.2f + s) * 5f;
                    float sy = y + 12f - ev.age * (1.5f + s * 0.06f);
                    fakeDiamond(gra, sx, sy, 4f + (s % 3), s % 2 == 0 ? ember : gold, Math.round(150 * life));
                }
                fakeDisc(gra, x, y, 8f + ev.age * 0.2f, Color.WHITE, Math.round(110 * life));
            } else if (ev.type == EVT_COMET_HIT) {
                float y = combatY;

                drawKameRayBurst(gra, x, y, -1f, 0f, ev.age, 0.6f, 1f);
            }
        }
    }

    private static void drawEvolutionMorphs(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        if (rt.beam.morphs.isEmpty()) return;
        float siz = BBPainterAccess.getSiz(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);
        for (int i = 0; i < rt.beam.morphs.size(); i++) {
            EvolutionMorph morph = rt.beam.morphs.get(i);

            float x;
            float y;
            boolean haveAnchor = manualcontrol.SpriteAnchor.hasFreshAnchor(morph.source);
            if (haveAnchor) {
                x = manualcontrol.SpriteAnchor.getX();
                y = manualcontrol.SpriteAnchor.getY();
            } else {
                x = CrazyRender.screenX(bbpainter, morph.pos);
                y = midh - (156f - morph.visualLayer() * 4f) * siz;
            }

            float baseY = midh - (156f - morph.currentLayer * 4f) * siz;

            float[] box = freshSpriteBox(morph.source);
            if (box != null) {
                morph.boxCX = box[0];
                morph.boxCY = box[1];
                morph.boxHW = box[2];
                morph.boxHH = box[3];
                morph.boxSeen = true;
            }
            float mcx = morph.boxSeen ? morph.boxCX : x;
            float mcy = morph.boxSeen ? morph.boxCY : y - 40f * siz;
            float mhw = morph.boxSeen ? morph.boxHW : 26f * siz;
            float mhh = morph.boxSeen ? morph.boxHH : 36f * siz;
            switch (EVOLUTION_VFX) {
                case 2:  drawCrystalChrysalis(gra, x, y, baseY, siz, morph.progress(), morph.age);  break;
                case 3:  drawThunderAscension(gra, x, y, baseY, siz, morph.progress(), morph.age);  break;
                case 4:  drawStellarGenesis(gra, mcx, mcy, baseY, mhw, mhh, siz, morph.age, morph.palette);  break;
                default: drawPhoenixRebirth(gra, x, y, baseY, siz, morph.progress(), morph.age);    break;
            }
        }
    }

    private static void drawPhoenixRebirth(FakeGraphics gra, float x, float y, float baseY,
                                           float siz, float progress, int age) {
        Color red = new Color(255, 60, 20);
        Color orange = new Color(255, 150, 0);
        Color gold = new Color(255, 230, 80);
        Color white = Color.WHITE;
        float pulse = (float) ((Math.sin(age * 0.38) + 1.0) * 0.5);
        float s = siz;

        fakeOval(gra, x, baseY, (50f + pulse * 10f) * s * (1f - progress * 0.3f), 12f * s,
                new Color(255, 80, 0), 65);

        float colH = 80f * s * progress;
        for (int i = 0; i < 12; i++) {
            float p = i / 12f;
            float cx = x + (float) Math.sin(age * 0.22f + i * 1.3f) * (18f - p * 10f) * s;
            float cy = y - p * colH;
            float r = (22f - p * 14f + pulse * 4f) * s;
            Color c = p < 0.3f ? red : (p < 0.6f ? orange : gold);
            fakeDisc(gra, cx, cy, r, c, Math.round(200f * (1f - p * 0.7f)));
        }

        for (int i = 0; i < 20; i++) {
            float p = ((age * 0.04f + i * 0.053f)) % 1f;
            double ang = age * 0.16 + i * 0.38 * Math.PI;
            float r = (18f + (1f - p) * 32f) * s;
            float ex = x + (float) Math.cos(ang) * r * (1f - p * 0.65f);
            float ey = y - p * 70f * s;
            float sz = (4.5f - p * 2.5f) * s;
            if (sz > 0.5f) {
                fakeDiamond(gra, ex, ey, sz,
                        i % 3 == 0 ? gold : (i % 2 == 0 ? orange : red),
                        Math.round(200f * (1f - p)));
            }
        }

        fakeRing(gra, x, y - 30f * s, (40f + pulse * 14f) * s, 5f * s, orange, 140, age * 0.18f);
        fakeRing(gra, x, y - 30f * s, (64f - pulse * 8f) * s, 3f * s, gold, 95, -age * 0.12f);

        fakeDisc(gra, x, y - 30f * s, (18f + pulse * 7f) * s, gold, 185);
        fakeDisc(gra, x, y - 30f * s, (9f + pulse * 4f) * s, white, 235);

        if (progress > 0.82f) {
            float burst = clamp01((progress - 0.82f) / 0.18f);
            fakeDisc(gra, x, y - 30f * s, (22f + burst * 90f) * s, gold, Math.round(185f * (1f - burst)));
            fakeDisc(gra, x, y - 30f * s, (12f + burst * 55f) * s, white, Math.round(245f * (1f - burst)));
            for (int i = 0; i < 18; i++) {
                double a = i * Math.PI * 2.0 / 18.0 + age * 0.09;
                float r0 = (16f + burst * 22f) * s;
                float r1 = (44f + burst * (100f + (i % 4) * 18f)) * s;
                fakeLine(gra,
                        x + (float) Math.cos(a) * r0, y - 30f * s + (float) Math.sin(a) * r0,
                        x + (float) Math.cos(a) * r1, y - 30f * s + (float) Math.sin(a) * r1,
                        i % 4 == 0 ? white : gold,
                        (i % 4 == 0 ? 3.5f : 2.5f) * s,
                        Math.round(225f * (1f - burst * 0.5f)));
            }
        }
    }

    private static void drawCrystalChrysalis(FakeGraphics gra, float x, float y, float baseY,
                                             float siz, float progress, int age) {
        Color iceBlue = new Color(140, 220, 255);
        Color deepBlue = new Color(60, 140, 220);
        Color crystal = new Color(200, 240, 255);
        Color white = Color.WHITE;
        float pulse = (float) ((Math.sin(age * 0.42) + 1.0) * 0.5);
        float s = siz;
        float shellProg = clamp01(progress / 0.65f);
        float shatterProg = clamp01((progress - 0.65f) / 0.35f);
        float cy0 = y - 40f * s;

        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI * 2.0 / 8.0;
            float r0 = 8f * s;
            float r1 = (20f + shellProg * 30f) * s;
            fakeLine(gra, x + (float) Math.cos(a) * r0, baseY + (float) Math.sin(a) * r0 * 0.28f,
                    x + (float) Math.cos(a) * r1, baseY + (float) Math.sin(a) * r1 * 0.28f,
                    iceBlue, 2f * s, Math.round(125 * shellProg));
        }

        if (shatterProg < 1f) {

            for (int i = 0; i < 14; i++) {
                double base = i * Math.PI * 2.0 / 14.0 + age * 0.06;
                float r = (30f + shellProg * 24f + (i % 3) * 7f) * s;
                float px = x + (float) Math.cos(base) * r * 0.55f;
                float py = cy0 + (float) Math.sin(base) * r;
                float plateSz = (9f + shellProg * 9f + (i % 3) * 3f) * s * (1f - shatterProg * 0.5f);
                fakeDiamond(gra, px, py, plateSz,
                        i % 3 == 0 ? white : (i % 2 == 0 ? crystal : iceBlue),
                        Math.round(185 * shellProg * (1f - shatterProg)));
            }

            fakeOval(gra, x, cy0, (26f + pulse * 8f) * s, (50f + pulse * 14f) * s, deepBlue,
                    Math.round(90 * shellProg * (1f - shatterProg)));
            fakeOval(gra, x, cy0, (15f + pulse * 5f) * s, (32f + pulse * 9f) * s, crystal,
                    Math.round(135 * shellProg * (1f - shatterProg)));
            fakeRing(gra, x, cy0, (46f + pulse * 12f) * s, 5f * s, iceBlue,
                    Math.round(155 * shellProg), age * 0.20f);
            fakeRing(gra, x, cy0, (66f - pulse * 7f) * s, 3f * s, crystal,
                    Math.round(110 * shellProg), -age * 0.14f);

            for (int i = 0; i < 16; i++) {
                float sp = ((age * 0.035f + i * 0.068f)) % 1f;
                double a = age * 0.12 + i * 0.42;
                float r = (38f + (i % 4) * 13f) * s;
                float sx2 = x + (float) Math.cos(a) * r * 0.6f + (float) Math.sin(age * 0.3f + i) * 6f * s;
                float sy2 = cy0 + (float) Math.sin(a) * r + (float) Math.cos(age * 0.3f + i) * 6f * s;
                fakeDiamond(gra, sx2, sy2, (4.5f - sp * 2.5f) * s, white,
                        Math.round(215 * (1f - sp) * shellProg));
            }
        }

        if (shatterProg > 0f) {
            fakeDisc(gra, x, cy0, (35f + shatterProg * 130f) * s, white, Math.round(225 * (1f - shatterProg)));
            fakeDisc(gra, x, cy0, (22f + shatterProg * 85f) * s, crystal, Math.round(185 * (1f - shatterProg * 0.8f)));
            for (int i = 0; i < 26; i++) {
                double a = i * Math.PI * 2.0 / 26.0 + 0.12;
                float r = (22f + shatterProg * (110f + (i % 5) * 22f)) * s;
                float sx2 = x + (float) Math.cos(a) * r;
                float sy2 = cy0 + (float) Math.sin(a) * r;
                float shardLen = (18f + (i % 4) * 9f) * s * (1f - shatterProg * 0.55f);
                float ex2 = sx2 + (float) Math.cos(a) * shardLen;
                float ey2 = sy2 + (float) Math.sin(a) * shardLen;
                fakeLine(gra, sx2, sy2, ex2, ey2,
                        i % 3 == 0 ? white : (i % 2 == 0 ? crystal : iceBlue),
                        2.5f * s, Math.round(225 * (1f - shatterProg)));
                fakeDiamond(gra, sx2, sy2, (5f + (i % 3) * 3f) * s * (1f - shatterProg * 0.7f),
                        crystal, Math.round(180 * (1f - shatterProg)));
            }
            fakeRing(gra, x, cy0, (44f + shatterProg * 110f) * s, 5f * s, iceBlue,
                    Math.round(185 * (1f - shatterProg)), age * 0.18f);
        }
    }

    private static void drawThunderAscension(FakeGraphics gra, float x, float y, float baseY,
                                             float siz, float progress, int age) {
        Color purple = new Color(160, 60, 255);
        Color violet = new Color(210, 130, 255);
        Color gold = new Color(255, 220, 60);
        Color white = Color.WHITE;
        float pulse = (float) ((Math.sin(age * 0.44) + 1.0) * 0.5);
        float s = siz;
        float cy0 = y - 40f * s;

        fakeOval(gra, x, baseY, (52f + pulse * 16f) * s, 11f * s, purple, 80);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3.0 + age * 0.12;
            float r = (32f + progress * 22f + (i % 3) * 9f) * s;
            fakeLine(gra, x, baseY, x + (float) Math.cos(a) * r,
                    baseY + (float) Math.sin(a) * r * 0.32f, purple, 2f * s, 110);
        }

        for (int i = 0; i < 10; i++) {
            float p = i / 10f;
            float pillarY = y - p * 82f * s * progress;
            float pr = (17f - p * 10f + pulse * 4f) * s;
            float ox = x + (float) Math.sin(age * 0.20f + i) * 6f * s;
            fakeDisc(gra, ox, pillarY, pr,
                    p < 0.4f ? purple : (p < 0.7f ? violet : gold),
                    Math.round(165 * (1f - p * 0.65f)));
        }

        for (int i = 0; i < 8; i++) {
            double ang = age * 0.14 + i * Math.PI * 2.0 / 8.0;
            float r = (38f + progress * 16f + (i % 3) * 9f) * s;
            float nx = x + (float) Math.cos(ang) * r * 0.6f;
            float ny = cy0 + (float) Math.sin(ang) * r;
            fakeDisc(gra, nx, ny, (8f + pulse * 3f) * s, i % 2 == 0 ? purple : violet, 165);
            fakeDisc(gra, nx, ny, (3.5f + pulse * 2f) * s, white, 230);

            float mx = (nx + x) * 0.5f + (float) (Math.sin(age * 0.62 + i) * 14f * s);
            float my = (ny + cy0) * 0.5f + (float) (Math.cos(age * 0.62 + i) * 14f * s);
            fakeLine(gra, x, cy0, mx, my, white, 2f * s, 195);
            fakeLine(gra, mx, my, nx, ny, i % 2 == 0 ? purple : gold, 2f * s, 165);

            if (age % 3 == i % 3) {
                double nextAng = age * 0.14 + (i + 1) * Math.PI * 2.0 / 8.0;
                float nr = (38f + progress * 16f + ((i + 1) % 3) * 9f) * s;
                fakeLine(gra, nx, ny,
                        x + (float) Math.cos(nextAng) * nr * 0.6f,
                        cy0 + (float) Math.sin(nextAng) * nr,
                        violet, 1.5f * s, 125);
            }
        }

        fakeDisc(gra, x, cy0, (22f + pulse * 9f) * s, purple, 105);
        fakeDisc(gra, x, cy0, (13f + pulse * 6f) * s, violet, 158);
        fakeDisc(gra, x, cy0, (6f + pulse * 3f) * s, white, 230);
        fakeRing(gra, x, cy0, (50f + pulse * 16f) * s, 5f * s, purple, 145, age * 0.22f);
        fakeRing(gra, x, cy0, (74f - pulse * 11f) * s, 3f * s, violet, 105, -age * 0.15f);
        fakeRing(gra, x, cy0, (34f + pulse * 9f) * s, 6f * s, gold, 135, age * 0.32f);

        if (progress > 0.80f) {
            float burst = clamp01((progress - 0.80f) / 0.20f);
            fakeDisc(gra, x, cy0, (26f + burst * 120f) * s, white, Math.round(225 * (1f - burst)));
            fakeDisc(gra, x, cy0, (18f + burst * 75f) * s, gold, Math.round(185 * (1f - burst * 0.7f)));
            fakeRing(gra, x, cy0, (38f + burst * 140f) * s, 7f * s, purple,
                    Math.round(185 * (1f - burst)), age * 0.28f);
            for (int i = 0; i < 22; i++) {
                double a = i * Math.PI * 2.0 / 22.0;
                float r0 = (20f + burst * 18f) * s;
                float r1 = (54f + burst * (130f + (i % 5) * 20f)) * s;
                fakeLine(gra,
                        x + (float) Math.cos(a) * r0, cy0 + (float) Math.sin(a) * r0,
                        x + (float) Math.cos(a) * r1, cy0 + (float) Math.sin(a) * r1,
                        i % 5 == 0 ? white : (i % 2 == 0 ? gold : purple),
                        (i % 5 == 0 ? 4f : 2.5f) * s,
                        Math.round(235 * (1f - burst * 0.55f)));
            }
        }
    }

    private static void drawStellarGenesis(FakeGraphics gra, float cx, float cy, float baseY,
                                           float halfW, float halfH, float siz, int age, int paletteIdx) {
        int pi = Math.max(0, Math.min(VOLLEY_PALETTES.length - 1, paletteIdx));
        VolleyPalette pal = VOLLEY_PALETTES[pi];
        FakeImage tex = getVolleyGlow(pi);
        float s = siz;

        float R = Math.min(480f, Math.max(Math.max(halfW, halfH), 14f * s));
        float dot = Math.max(2f * s, R * 0.035f);
        float ringW = Math.max(1.6f * s, R * 0.030f);
        float rx = halfW * 1.08f;
        float ry = halfH * 1.02f;

        if (age < 21) {
            float w = clamp01(age / 21f);

            float gr = halfW * (0.5f + 0.7f * w);
            for (int i = 0; i < 26; i++) {
                double a = i * Math.PI * 2.0 / 26.0 + age * 0.05;
                fakePixel(gra, cx + (float) Math.cos(a) * gr, baseY + (float) Math.sin(a) * gr * 0.28f,
                        dot * 0.8f, i % 2 == 0 ? pal.mid : pal.hot, Math.round(150f * w));
            }

            for (int i = 0; i < 10; i++) {
                double a = age * 0.16 + i * 0.628;
                fakePixel(gra, cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry,
                        dot, i % 2 == 0 ? pal.hot : Color.WHITE, Math.round(210f * w));
                double a2 = -a * 0.8 + 1.7;
                fakePixel(gra, cx + (float) Math.cos(a2) * rx * 1.15f, cy + (float) Math.sin(a2) * ry * 0.75f,
                        dot * 0.85f, pal.mid, Math.round(150f * w));
            }

            for (int i = 0; i < 12; i++) {
                float tt = 1f - ((age * 0.03f + i * 0.083f) % 1f);
                double a = i * 1.7 + age * 0.02;
                float f = 0.25f + tt * 1.5f;
                fakePixel(gra, cx + (float) Math.cos(a) * rx * f, cy + (float) Math.sin(a) * ry * f * 0.85f,
                        dot * 0.75f, pal.mid, Math.round(170f * (1f - tt) * w));
            }
            drawGlowTexAdditive(gra, tex, cx, cy, 2.2f * R * w, Math.round(60f * w));
            return;
        }

        if (age < 48) {
            float c = clamp01((age - 21) / 27f);
            float freq = 0.30f + 0.45f * c;
            float pulse = (float) ((Math.sin(age * freq) + 1.0) * 0.5);
            float squash = age > EVOLUTION_DESCEND_AT
                    ? 1f - clamp01((age - EVOLUTION_DESCEND_AT) / 6f) * 0.42f : 1f;

            float pillarH = Math.max(40f * s, baseY - (cy - halfH));
            for (int i = 0; i < 8; i++) {
                float p = i / 8f;
                drawGlowTexAdditive(gra, tex,
                        cx + (float) Math.sin(age * 0.2f + i) * halfW * 0.06f,
                        baseY - p * pillarH * (0.55f + 0.45f * c),
                        (halfW * 0.9f) * (1f - p * 0.4f) * squash,
                        Math.round((60f + 55f * pulse) * (1f - p * 0.5f) * c));
            }

            drawGlowTexAdditive(gra, tex, cx, cy, (2.3f + 0.3f * pulse) * R * squash, Math.round(70f + 50f * pulse));
            fakeRing(gra, cx, cy, (0.95f + 0.12f * pulse) * R * squash, ringW, pal.hot,
                    Math.round(130f + 70f * pulse), age * 0.2f);
            fakeRing(gra, cx, cy, (1.22f - 0.09f * pulse) * R * squash, ringW * 0.75f, pal.rim, 95, -age * 0.14f);

            for (int i = 0; i < 10; i++) {
                double a = age * 0.22 + i * 0.628;
                fakePixel(gra, cx + (float) Math.cos(a) * rx * (0.72f + 0.28f * (1f - c)) * squash,
                        cy + (float) Math.sin(a) * ry * (0.72f + 0.28f * (1f - c)) * squash,
                        dot * 0.85f, i % 2 == 0 ? pal.hot : Color.WHITE, 190);
            }
            gOpaque = true;
            try {
                fakeDisc(gra, cx, cy, (2.5f + 2f * pulse) * Math.max(s, R / 45f), Color.WHITE, 255);
            } finally {
                gOpaque = false;
            }
            return;
        }

        float bb = clamp01((age - 48) / 12f);
        float fade = 1f - bb;
        drawGlowTexAdditive(gra, tex, cx, cy, (1.3f + 3.4f * bb) * R, Math.round(255f * fade));
        drawGlowTexAdditive(gra, getVolleyWhiteGlow(), cx, cy, (0.9f + 2.4f * bb) * R, Math.round(250f * fade));
        drawBandRing(gra, cx, cy, (0.35f + 2.3f * bb) * R, (5f * fade + 1f) * Math.max(s, R / 60f),
                Color.WHITE, Math.round(230f * fade));
        fakeRing(gra, cx, cy, (0.28f + 1.8f * bb) * R, ringW, pal.mid, Math.round(200f * fade), 0f);
        for (int i = 0; i < 14; i++) {
            double a = i * Math.PI * 2.0 / 14.0 + 0.12;
            float r0 = (0.25f + 0.55f * bb) * R;
            float r1 = (0.7f + (1.9f + (i % 4) * 0.35f) * bb) * R;
            fakeLine(gra, cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                    cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    i % 4 == 0 ? Color.WHITE : pal.hot,
                    (i % 4 == 0 ? 3f : 1.8f) * Math.max(s, R / 55f) * (1f - bb * 0.5f),
                    Math.round(235f * (1f - bb * 0.6f)));
        }

        for (int k = 0; k < 16; k++) {
            float g1 = ((k * 97 + 13) & 255) / 255f;
            float g2 = ((k * 57 + 41) & 255) / 255f;
            double a = g1 * Math.PI * 2.0;
            float spd = (0.045f + g2 * 0.06f) * R;
            float tt = age - 48;
            float pxx = cx + (float) Math.cos(a) * spd * tt;
            float pyy = cy + (float) Math.sin(a) * spd * tt * 0.7f - 0.04f * R * tt + 0.004f * R * tt * tt;
            fakePixel(gra, pxx, pyy, (1.6f + g2 * 1.2f) * Math.max(s, R / 55f),
                    k % 3 == 0 ? Color.WHITE : pal.hot, Math.round(220f * fade));
        }
    }

    private static void fakeSpiralDots(FakeGraphics gra, float cx, float cy, float inner, float outer,
                                       int frame, Color a, Color b, int count, float turns) {
        for (int i = 0; i < count; i++) {
            float p = i / (float) Math.max(1, count - 1);
            double ang = frame * 0.14 + p * Math.PI * 2.0 * turns;
            float r = inner + (outer - inner) * p;
            float x = cx + (float) Math.cos(ang) * r;
            float y = cy + (float) Math.sin(ang) * r;
            fakeDiamond(gra, x, y, 4f + p * 4f, i % 2 == 0 ? a : b, 150);
        }
    }

    private static void drawBandRing(FakeGraphics gra, float cx, float cy, float radius,
                                     float thick, Color color, int alpha) {
        if (radius <= 0f || thick <= 0f || alpha <= 0) return;
        float w = Math.max(2f, thick * 0.55f);
        fakeRing(gra, cx, cy, radius - thick * 0.22f, w, color, alpha, 0f);
        fakeRing(gra, cx, cy, radius + thick * 0.22f, w, color, Math.round(alpha * 0.8f), 0.07f);
    }

    private static void drawSmoothRing(FakeGraphics gra, float cx, float cy, float radius,
                                       float thick, Color color, int alpha) {
        if (alpha <= 0 || thick <= 0f) return;
        float dot = 2.4f;
        float inner = Math.max(0.5f, radius - thick * 0.5f);
        float outer = radius + thick * 0.5f;
        int n = Math.max(1, Math.round((outer - inner) / (dot * 0.8f)));
        for (int k = 0; k <= n; k++) {
            float sr = inner + (outer - inner) * (k / (float) n);
            fakeRing(gra, cx, cy, sr, dot, color, alpha, 0f);
        }
    }

    private static void fakeRing(FakeGraphics gra, float cx, float cy, float radius, float width,
                                 Color color, int alpha, float phase) {
        radius *= gScale;
        width *= gScale;
        if (radius <= 0f || alpha <= 0) return;
        FakeTransform at = gra.getTransform();
        try {

            int samples = Math.max(24, Math.min(640, Math.round(6.4f * radius / Math.max(1f, width))));
            int size = Math.max(1, Math.round(width));
            boolean gl = gIsGL;
            int cr = color.getRed(), cg = color.getGreen(), cb = color.getBlue();
            int a = effAlpha(alpha);
            if (!gl) setColor(gra, color, alpha);
            for (int i = 0; i < samples; i++) {
                double ang = phase + i * Math.PI * 2.0 / samples;
                float x = cx + (float) Math.cos(ang) * radius;
                float y = cy + (float) Math.sin(ang) * radius;
                if (gl) gra.colRect(x - size * 0.5f, y - size * 0.5f, size, size, cr, cg, cb, a);
                else gra.fillRect(x - size * 0.5f, y - size * 0.5f, size, size);
            }
            if (!gl) resetComposite(gra);
        } finally {
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void fakeRect(FakeGraphics gra, float x, float y, float w, float h, Color color, int alpha) {
        if (w <= 0f || h <= 0f || alpha <= 0) return;
        FakeTransform at = gra.getTransform();
        try {
            if (gIsGL) {
                gra.colRect(x, y, w, h, color.getRed(), color.getGreen(), color.getBlue(), effAlpha(alpha));
            } else {
                setColor(gra, color, alpha);
                gra.fillRect(x, y, w, h);
                resetComposite(gra);
            }
        } finally {
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void fakeLine(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                 Color color, float width, int alpha) {
        width *= gScale;
        FakeTransform at = gra.getTransform();
        try {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) {
                fakeSquareRaw(gra, x0, y0, Math.max(1, Math.round(width)), color, alpha);
                return;
            }
            int size = Math.max(1, Math.round(width));
            boolean gl = gIsGL;
            int cr = color.getRed(), cg = color.getGreen(), cb = color.getBlue();
            int a = effAlpha(alpha);
            if (!gl) setColor(gra, color, alpha);
            if (Math.abs(dy) <= 1.25f) {
                float left = Math.min(x0, x1);
                float right = Math.max(x0, x1);
                float w = Math.max(1f, right - left);
                if (gl) gra.colRect(left, y0 - size * 0.5f, w, size, cr, cg, cb, a);
                else gra.fillRect(left, y0 - size * 0.5f, w, size);
            } else {
                float step = Math.max(2f, width * 0.34f);
                int samples = Math.max(2, (int) Math.ceil(len / step));
                for (int i = 0; i <= samples; i++) {
                    float p = i / (float) samples;
                    float x = x0 + dx * p;
                    float y = y0 + dy * p;
                    if (gl) gra.colRect(x - size * 0.5f, y - size * 0.5f, size, size, cr, cg, cb, a);
                    else gra.fillRect(x - size * 0.5f, y - size * 0.5f, size, size);
                }
            }
        } finally {
            if (!gIsGL) resetComposite(gra);
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void fakeWavyLine(FakeGraphics gra, float x0, float y0, float x1, float y1,
                                     int frame, Color color, float width, int alpha, float phase) {
        final int segments = 28;
        float lastX = x0;
        float lastY = y0;
        for (int i = 1; i <= segments; i++) {
            float p = i / (float) segments;
            float x = x0 + (x1 - x0) * p;
            float y = y0 + (y1 - y0) * p + (float) Math.sin(p * Math.PI * 6.0 + frame * 0.18f + phase) * 13f;
            fakeLine(gra, lastX, lastY, x, y, color, width, alpha);
            lastX = x;
            lastY = y;
        }
    }

    private static int effAlpha(int alpha) {
        if (gOpaque) return 255;
        int a = Math.round(alpha * gAlpha);
        return a < 0 ? 0 : (a > 255 ? 255 : a);
    }

    private static void fakeDisc(FakeGraphics gra, float cx, float cy, float radius, Color color, int alpha) {
        radius *= gScale;
        int a = effAlpha(alpha);
        if (a <= 0) return;
        FakeTransform at = gra.getTransform();
        try {
            int r = Math.max(1, Math.round(radius));

            if (gIsGL) {
                int cr = color.getRed(), cg = color.getGreen(), cb = color.getBlue();
                for (int dy = -r; dy <= r; dy++) {
                    int span = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
                    gra.colRect(cx - span, cy + dy, span * 2 + 1, 1, cr, cg, cb, a);
                }
            } else {
                setColor(gra, color, alpha);
                for (int dy = -r; dy <= r; dy++) {
                    int span = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
                    gra.fillRect(cx - span, cy + dy, span * 2 + 1, 1);
                }
                resetComposite(gra);
            }
        } finally {
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void fakeOval(FakeGraphics gra, float cx, float cy, float rx, float ry, Color color, int alpha) {
        rx *= gScale;
        ry *= gScale;
        if (rx <= 0f || ry <= 0f || alpha <= 0) return;
        FakeTransform at = gra.getTransform();
        try {
            setColor(gra, color, alpha);
            int ix = Math.max(1, Math.round(rx));
            int iy = Math.max(1, Math.round(ry));
            for (int dy = -iy; dy <= iy; dy++) {
                float yn = dy / (float) iy;
                int span = Math.round(ix * (float) Math.sqrt(Math.max(0f, 1f - yn * yn)));
                gra.fillRect(cx - span, cy + dy, span * 2 + 1, 1);
            }
        } finally {
            resetComposite(gra);
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void fakeDiamond(FakeGraphics gra, float cx, float cy, float radius, Color color, int alpha) {
        radius *= gScale;
        FakeTransform at = gra.getTransform();
        try {
            setColor(gra, color, alpha);
            int r = Math.max(1, Math.round(radius));
            for (int dy = -r; dy <= r; dy++) {
                int span = r - Math.abs(dy);
                gra.fillRect(cx - span, cy + dy, span * 2 + 1, 1);
            }
        } finally {
            resetComposite(gra);
            gra.setTransform(at);
            gra.delete(at);
        }
    }

    private static void fakeSquareRaw(FakeGraphics gra, float cx, float cy, int size, Color color, int alpha) {
        setColor(gra, color, alpha);
        gra.fillRect(cx - size * 0.5f, cy - size * 0.5f, size, size);
    }

    private static void setColor(FakeGraphics gra, Color color, int alpha) {
        if (gAdditive) {

            int a = Math.round(alpha * gAlpha);
            gra.setComposite(FakeGraphics.BLEND, Math.max(0, Math.min(256, a)), 1);
        } else if (gTrueAlpha) {

            int a = Math.max(0, Math.min(255, Math.round(alpha * gAlpha)));
            if (gIsGL) gra.setComposite(FakeGraphics.BLEND, a, -2);
            else gra.setComposite(FakeGraphics.TRANS, a, 0);
        } else if (gOpaque) {
            if (gIsGL) {

                gra.setComposite(FakeGraphics.BLEND, 255, -2);
            } else {

                gra.setComposite(FakeGraphics.DEF, 0, 0);
            }
        } else {
            int a = Math.round(alpha * gAlpha);
            gra.setComposite(FakeGraphics.TRANS, Math.max(0, Math.min(255, a)), 0);
        }
        gra.setColor(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static void resetComposite(FakeGraphics gra) {
        gra.setComposite(FakeGraphics.DEF, 0, 0);
    }

    private static void updateHypnoVolley(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        State st = rt.beam;

        if (st.frame < st.duration - HYPNOSIS_FADE) {
            if (st.volleyCooldown > 0) st.volleyCooldown--;
            if (st.volleyCooldown <= 0 && st.bullets.size() < HYPNO_MAX_INFLIGHT) {
                Entity tgt = nextHypnoTarget(rt, sb);
                if (tgt != null) {
                    int flight = Math.round(Math.max(HYPNO_FLIGHT_MIN, Math.min(HYPNO_FLIGHT_MAX,
                            HYPNO_FLIGHT_MIN + Math.abs(sb.ubase.pos - tgt.pos) / 450f)));
                    TracerBullet b = new TracerBullet(tgt,
                            st.bulletCounter % VOLLEY_PALETTES.length, st.bulletCounter, flight, true);
                    st.bulletCounter++;
                    st.bullets.add(b);
                    st.touched.add(tgt);
                    trackBounds(tgt);
                    st.volleyCooldown = HYPNO_SPAWN_GAP;
                    Logger.log("BCU Crazy hypno needle #" + b.seed + " flight=" + flight
                            + " targetPos=" + Math.round(tgt.pos));
                }
            }
        }
        for (int i = st.bullets.size() - 1; i >= 0; i--) {
            TracerBullet b = st.bullets.get(i);
            b.age++;
            if (b.phase == TracerBullet.PHASE_CHARGE) {
                if (b.age >= HYPNO_CHARGE_FRAMES) {
                    b.phase = TracerBullet.PHASE_HOVER;
                    b.age = 0;
                }
                continue;
            }
            if (b.phase == TracerBullet.PHASE_HOVER) {

                if (b.target != null && !b.target.dead && b.target.health > 0L
                        && b.target.dire == 1 && sb.le.contains(b.target)) {
                    b.targetPos = b.target.pos;
                    b.targetLayer = b.target.currentLayer;
                    b.targetAlive = true;
                } else {
                    b.targetAlive = false;
                }
                if (b.age >= b.hover) {
                    if (!b.targetAlive) {

                        Entity again = nextHypnoTarget(rt, sb);
                        if (again != null) {
                            untrackBounds(b.target);
                            b.target = again;
                            st.touched.add(again);
                            trackBounds(again);
                            b.targetPos = again.pos;
                            b.targetLayer = again.currentLayer;
                            b.targetAlive = true;
                            b.aimSeen = false;
                            Logger.log("BCU Crazy hypno needle #" + b.seed
                                    + " retargeted in the air -> pos=" + Math.round(again.pos));
                        } else if (b.age >= b.hover + HYPNO_HOVER_GRACE) {

                            b.airFizzle = true;
                            b.hoverExit = b.age;
                            b.phase = TracerBullet.PHASE_IMPACT;
                            b.impactAge = 0;
                            Logger.log("BCU Crazy hypno needle #" + b.seed + " dissipated (no target)");
                        }
                    }
                    if (b.targetAlive) {
                        b.hoverExit = b.age;
                        b.phase = TracerBullet.PHASE_FLY;
                        b.age = 0;
                    }
                }
                continue;
            }
            if (b.phase == TracerBullet.PHASE_FLY) {

                if (b.target != null && !b.target.dead && b.target.health > 0L
                        && b.target.dire == 1 && sb.le.contains(b.target)) {
                    b.targetPos = b.target.pos;
                    b.targetLayer = b.target.currentLayer;
                } else {
                    b.targetAlive = false;
                }
                if (b.age >= b.flight) {
                    b.phase = TracerBullet.PHASE_IMPACT;
                    b.impactAge = 0;
                    boolean locked = b.targetAlive && beginHypnosis(b.target);
                    if (!locked) {
                        Logger.log("BCU Crazy hypno needle #" + b.seed + " fizzled (target gone)");
                    }
                }
                continue;
            }
            b.impactAge++;
            if (b.impactAge >= HYPNO_IMPACT_FRAMES) {
                st.bullets.remove(i);

                if (!TRANCE.containsKey(b.target)) untrackBounds(b.target);
            }
        }
    }

    private static Entity nextHypnoTarget(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        Entity best = null;
        float bestPos = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < sb.le.size(); i++) {
            Entity e = sb.le.get(i);
            if (!isLiveEnemy(e)) continue;
            if (rt.beam.touched.contains(e) || TRANCE.containsKey(e)) continue;
            if (!between(e.pos, sb.ebase.pos, sb.ubase.pos)) continue;
            if (e.pos > bestPos) {
                bestPos = e.pos;
                best = e;
            }
        }
        return best;
    }

    private static boolean beginHypnosis(Entity e) {
        if (e == null || e.dead || e.health <= 0L || e.dire != 1) return false;
        e.dire = -1;
        ConvertedRegistry.mark(e);
        TRANCE.put(e, new TranceState(e.pos, readLayer(e, "currentLayer", e.currentLayer)));
        trackBounds(e);
        Logger.log("BCU Crazy hypno lock: " + e.getClass().getSimpleName()
                + " pos=" + Math.round(e.pos) + " trance=" + HYPNO_TRANCE_FRAMES + "f");
        return true;
    }

    private static void updateVolley(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        State st = rt.beam;

        if (st.frame < st.duration - EVOLUTION_FADE) {
            if (st.volleyCooldown > 0) st.volleyCooldown--;
            if (st.volleyCooldown <= 0 && st.bullets.size() < VOLLEY_MAX_INFLIGHT) {
                EUnit tgt = nextVolleyTarget(rt, sb);
                if (tgt != null) {
                    int flight = Math.round(Math.max(VOLLEY_FLIGHT_MIN, Math.min(VOLLEY_FLIGHT_MAX,
                            VOLLEY_FLIGHT_MIN + Math.abs(sb.ubase.pos - tgt.pos) / 260f)));
                    TracerBullet b = new TracerBullet(tgt,
                            st.bulletCounter % VOLLEY_PALETTES.length, st.bulletCounter, flight, false);
                    st.bulletCounter++;
                    st.bullets.add(b);
                    st.touched.add(tgt);
                    trackBounds(tgt);
                    st.volleyCooldown = VOLLEY_SPAWN_GAP;
                    Logger.log("BCU Crazy volley shot #" + b.seed + " palette="
                            + VOLLEY_PALETTES[b.palette].name + " flight=" + flight
                            + " targetPos=" + Math.round(tgt.pos));
                }
            }
        }
        for (int i = st.bullets.size() - 1; i >= 0; i--) {
            TracerBullet b = st.bullets.get(i);
            b.age++;
            if (b.phase == TracerBullet.PHASE_CHARGE) {
                if (b.age >= VOLLEY_CHARGE_FRAMES) {
                    b.phase = TracerBullet.PHASE_FLY;
                    b.age = 0;
                }
                continue;
            }
            if (b.phase == TracerBullet.PHASE_FLY) {

                if (b.target != null && !b.target.dead && b.target.health > 0L
                        && sb.le.contains(b.target)) {
                    b.targetPos = b.target.pos;
                    b.targetLayer = b.target.currentLayer;
                } else {
                    b.targetAlive = false;
                }
                if (b.age >= b.flight) {
                    b.phase = TracerBullet.PHASE_IMPACT;
                    b.impactAge = 0;
                    boolean morphed = b.targetAlive && b.target instanceof EUnit
                            && beginEvolutionMorph(rt, (EUnit) b.target, b.palette);
                    if (!morphed) {
                        Logger.log("BCU Crazy volley shot #" + b.seed + " fizzled (target gone)");
                    }
                }
                continue;
            }
            b.impactAge++;
            if (b.impactAge >= VOLLEY_IMPACT_FRAMES) {
                st.bullets.remove(i);

                if (!isMorphing(rt, b.target)) untrackBounds(b.target);
            }
        }
    }

    private static EUnit nextVolleyTarget(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        EUnit best = null;
        float bestPos = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < sb.le.size(); i++) {
            Entity e = sb.le.get(i);
            if (!(e instanceof EUnit) || e.dead || e.health <= 0L || e.dire != -1) continue;
            if (rt.beam.touched.contains(e)) continue;
            if (isMorphing(rt, e)) continue;
            if (!canEvolve((EUnit) e)) continue;
            if (e.pos > bestPos) {
                bestPos = e.pos;
                best = (EUnit) e;
            }
        }
        return best;
    }

    public static boolean isEvolutionFrozen(Object entity) {
        return entity != null && EVOLUTION_FROZEN.containsKey(entity);
    }

    private static boolean beginEvolutionMorph(CrazyRuntime.StageRuntime rt, EUnit unit, int palette) {
        if (rt == null || unit == null || !canEvolve(unit) || isMorphing(rt, unit)) return false;
        EvolutionMorph morph = new EvolutionMorph(unit, palette);
        rt.beam.morphs.add(morph);
        EVOLUTION_FROZEN.put(unit, Boolean.TRUE);
        MORPH_BY_SOURCE.put(unit, morph);
        trackBounds(unit);
        pinMorphSource(morph);
        Logger.log("BCU Crazy evolution morph started: pos=" + Math.round(morph.pos)
                + " layer=" + morph.currentLayer + " frames=" + EVOLUTION_MORPH_FRAMES
                + " palette=" + VOLLEY_PALETTES[Math.max(0, Math.min(VOLLEY_PALETTES.length - 1, palette))].name
                + " swapAt=" + EVOLUTION_SWAP_FRAME);
        return true;
    }

    private static boolean isMorphing(CrazyRuntime.StageRuntime rt, Object unit) {
        for (int i = 0; i < rt.beam.morphs.size(); i++) {
            if (rt.beam.morphs.get(i).source == unit) return true;
        }
        return false;
    }

    private static void updateEvolutionMorphs(CrazyRuntime.StageRuntime rt) {
        if (rt == null || rt.beam.morphs.isEmpty()) return;
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = rt.beam.morphs.size() - 1; i >= 0; i--) {
            EvolutionMorph morph = rt.beam.morphs.get(i);

            if (!morph.swapped) {
                if (morph.source == null || morph.source.dead || morph.source.health <= 0L || !sb.le.contains(morph.source)) {
                    restoreMorphSource(morph);
                    EVOLUTION_FROZEN.remove(morph.source);
                    MORPH_BY_SOURCE.remove(morph.source);
                    untrackBounds(morph.source);
                    rt.beam.morphs.remove(i);
                    continue;
                }
                pinMorphSource(morph);
            }
            morph.age++;

            if (!morph.swapped && morph.age >= EVOLUTION_SWAP_FRAME) {
                swapEvolutionMorph(rt, sb, morph);
            }
            if (morph.age >= EVOLUTION_MORPH_FRAMES) {
                rt.beam.morphs.remove(i);
            }
        }
        sb.le.sort(Comparator.comparingInt(en -> en.currentLayer));
    }

    private static void swapEvolutionMorph(CrazyRuntime.StageRuntime rt, StageBasis sb, EvolutionMorph morph) {
        morph.swapped = true;
        restoreMorphSource(morph);
        EVOLUTION_FROZEN.remove(morph.source);
        MORPH_BY_SOURCE.remove(morph.source);
        untrackBounds(morph.source);
        EUnit evolved = evolveUnit(sb, morph.source);
        if (evolved == null) return;
        int idx = sb.le.indexOf(morph.source);
        if (idx >= 0) {
            sb.le.set(idx, evolved);
            rt.beam.touched.add(evolved);

            GROWING.put(evolved, new GrowState(morph.boxSeen ? morph.boxHH : Float.NaN, morph.palette));
            trackBounds(evolved);
            Logger.log("BCU Crazy evolution swap (hidden in white-out): pos=" + Math.round(morph.pos)
                    + " at " + morph.age + "/" + EVOLUTION_MORPH_FRAMES
                    + " source=" + morph.source.getClass().getSimpleName()
                    + " oldHalfH=" + (morph.boxSeen ? morph.boxHH : Float.NaN));
        }
    }

    private static void clearEvolutionMorphs(CrazyRuntime.StageRuntime rt) {
        if (rt == null || rt.beam.morphs.isEmpty()) return;
        for (int i = 0; i < rt.beam.morphs.size(); i++) {
            EvolutionMorph morph = rt.beam.morphs.get(i);
            restoreMorphSource(morph);
            EVOLUTION_FROZEN.remove(morph.source);
            MORPH_BY_SOURCE.remove(morph.source);
            untrackBounds(morph.source);
        }
        rt.beam.morphs.clear();
    }

    private static void pinMorphSource(EvolutionMorph morph) {
        if (morph == null || morph.source == null) return;
        morph.source.pos = morph.pos;
        setFloatQuiet(morph.source, "lastPosition", morph.pos);
        writeLayer(morph.source, morph.visualLayer(), morph.visualLayer(), morph.spawnLayer);
    }

    private static void restoreMorphSource(EvolutionMorph morph) {
        if (morph == null || morph.source == null) return;
        morph.source.pos = morph.pos;
        setFloatQuiet(morph.source, "lastPosition", morph.pos);
        writeLayer(morph.source, morph.layer, morph.currentLayer, morph.spawnLayer);
    }

    private static boolean canEvolve(EUnit old) {
        try {
            Form form = ((MaskUnit) old.data).getPack();
            Unit unit = form.unit;
            return unit != null && unit.forms != null && unit.forms.length > 0 && form.fid < unit.forms.length - 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void updateKame(CrazyRuntime.StageRuntime rt, StageBasis sb, Cannon cannon) {
        AbEntity target = firstEnemyTarget(rt, sb);
        boolean targetIsEnemy = target instanceof Entity;
        if (target == null) {
            target = sb.ebase;
            rt.beam.impactLayer = 0;
            rt.beam.kameTargetEntity = null;
        } else {
            targetIsEnemy = true;
            rt.beam.impactLayer = target instanceof Entity
                    ? readLayer(target, "currentLayer", ((Entity) target).currentLayer)
                    : 0;
            rt.beam.kameTargetEntity = target;
        }

        KAME_AIM_TARGET = rt.beam.kameTargetEntity;
        rt.beam.kameAimPos = target.pos;
        if (Float.isNaN(rt.beam.lastKameTargetPos) || Math.abs(rt.beam.lastKameTargetPos - target.pos) > 1f) {
            rt.beam.lastKameTargetPos = target.pos;
            Logger.log("BCU Crazy Kame target: " + target.getClass().getSimpleName()
                    + " pos=" + Math.round(target.pos)
                    + " visual=" + Math.round(rt.beam.visualImpactPos)
                    + (target instanceof Entity ? " " + entityState((Entity) target) : " base"));
        }

        if (rt.beam.visualImpactPos == 0f) {
            rt.beam.visualImpactPos = beamNearPos(sb);
        }

        if (target.pos > rt.beam.visualImpactPos) {
            rt.beam.visualImpactPos = target.pos;
        } else {
            rt.beam.visualImpactPos = moveToward(rt.beam.visualImpactPos, target.pos, KAME_EXTEND_SPEED);
        }
        rt.beam.impactPos = rt.beam.visualImpactPos;
        boolean reached = Math.abs(rt.beam.visualImpactPos - target.pos) <= KAME_REACH_EPS;
        rt.beam.hit = targetIsEnemy && reached;

        rt.beam.kameReached = reached;
        if (rt.beam.frame % KAME_TICK != 0) return;
        if (!reached) return;

        float ramp = 1.0f + KAME_RAMP_PEAK * ((float) rt.beam.frame / (float) Math.max(1, KAME_FRAMES - 1));
        int direct = clampDamage(Math.round(baseCannonDamage(sb) * KAME_DMG_MULT * ramp));
        if (target == sb.ebase) {
            direct = Math.max(1, direct / 4);
        }
        if (targetIsEnemy && target instanceof Entity) {
            Entity e = (Entity) target;

            Data.Proc tp = applyKameStatus(rt.beam, e, rt.beam.frame);
            damage(cannon, target, direct, tp);

            if (e.health <= 0L || e.dead || isDeathVisual(e) || !isLiveEnemy(e)) {
                rt.beam.touched.add(e);
            }
        } else {
            damage(cannon, target, direct);
        }

        int splash = Math.max(1, Math.round(direct * 0.35f));
        int count = 0;
        for (Entity e : new ArrayList<Entity>(sb.le)) {
            if (count >= TARGETS_PER_TICK) break;
            if (!isLiveEnemy(e) || e == target) continue;
            if (Math.abs(e.pos - target.pos) <= SPLASH_RADIUS) {

                damage(cannon, e, splash, applyKameStatus(rt.beam, e, rt.beam.frame));
                count++;
            }
        }
    }

    private static AbEntity firstEnemyTarget(CrazyRuntime.StageRuntime rt, StageBasis sb) {
        Entity best = null;
        float bestPos = Float.NEGATIVE_INFINITY;
        for (Entity e : sb.le) {
            if (!isLiveEnemy(e)) continue;
            if (rt != null && rt.beam.touched.contains(e) && (e.health <= 0L || e.dead || isDeathVisual(e))) continue;
            if (!between(e.pos, sb.ebase.pos, sb.ubase.pos)) continue;
            if (e.pos > bestPos) {
                bestPos = e.pos;
                best = e;
            }
        }
        return best;
    }

    private static void damage(Cannon cannon, AbEntity target, int amount) {
        damage(cannon, target, amount, Data.Proc.blank());
    }

    private static void damage(Cannon cannon, AbEntity target, int amount, Data.Proc pro) {
        if (target == null || amount == 0) return;
        try {
            ArrayList<Trait> traits = new ArrayList<Trait>();
            traits.add((Trait) UserProfile.getBCData().traits.get(16));
            AttackCanon atk = new AttackCanon(cannon, amount, traits, 0,
                    pro != null ? pro : Data.Proc.blank(),
                    target.pos - 1f, target.pos + 1f, 1);
            target.damaged(atk);
        } catch (Throwable t) {
            Logger.err("BCU Crazy beam damage failed", t);
        }
    }

    private static Data.Proc applyKameStatus(State beam, Entity e, int frame) {
        try {
            int[] st = beam.kameHits.get(e);
            if (st == null || frame - st[1] > KAME_HIT_GAP) {
                st = new int[]{0, frame, 0};
                beam.kameHits.put(e, st);
                e.interrupt(0, KAME_KB_FIRST);
                Logger.log("Kame buff v19: FIRST-HIT knockback dis=" + KAME_KB_FIRST);
                return Data.Proc.blank();
            }
            st[0] += frame - st[1];
            st[1] = frame;
            int dur = st[0];
            if (dur >= KAME_FREEZE_AT && (st[2] & 1) == 0) {
                st[2] |= 1;
                Data.Proc p = Data.Proc.blank();
                p.STOP.time = KAME_FREEZE_LEN;
                Logger.log("Kame buff v19: 3s reached -> FREEZE " + KAME_FREEZE_LEN + "f");
                return p;
            }
            if (dur >= KAME_PUSH_AT) {
                float dis = Math.min(KAME_PUSH_MAX, KAME_PUSH_BASE + (dur - KAME_PUSH_AT) * KAME_PUSH_GROW);
                e.interrupt(0, dis);
                if (dur % 30 == 0) Logger.log("Kame buff v19: PUSHBACK dis=" + Math.round(dis) + " dur=" + dur);
            }
        } catch (Throwable t) {
            Logger.err("BCU Crazy Kame status failed", t);
        }
        return Data.Proc.blank();
    }

    private static EUnit evolveUnit(StageBasis sb, EUnit old) {
        try {
            Form form = ((MaskUnit) old.data).getPack();
            Unit unit = form.unit;
            if (unit == null || unit.forms == null || unit.forms.length == 0) return null;
            int next = Math.min(unit.forms.length - 1, form.fid + 1);
            if (next == form.fid) return null;
            Level level = null;
            try {
                Object lv = BCUFields.get(old, "level");
                if (lv instanceof Level) level = (Level) lv;
            } catch (Throwable ignored) {}
            EForm ef = level == null ? new EForm(unit.forms[next], Math.max(1, old.lvl)) : new EForm(unit.forms[next], level);
            EUnit eu = ef.getEntity(sb, old.index, false, false);
            long oldHealth = Math.max(0L, old.health);
            long oldMax = Math.max(1L, old.maxH);
            eu.added(-1, old.pos);
            eu.currentLayer = old.currentLayer;
            eu.spawnLayer = old.spawnLayer;
            copyOptionalField(old, eu, "canOrb");
            copyOptionalField(old, eu, "price");
            eu.health = Math.max(1L, Math.min(eu.maxH, (long) Math.ceil((double) eu.maxH * (double) oldHealth / (double) oldMax)));
            return eu;
        } catch (Throwable t) {
            Logger.err("BCU Crazy evolution beam failed", t);
            return null;
        }
    }

    private static int baseCannonDamage(StageBasis sb) {
        try {
            Object treasure = sb.b.t();
            java.lang.reflect.Method m = treasure.getClass().getMethod("getCanonAtk", boolean.class);
            Object val = m.invoke(treasure, false);
            int atk = val instanceof Number ? ((Number) val).intValue() : 3000;
            return Math.max(3000, atk * Math.max(1, sb.cannonMultiplier()) / 100);
        } catch (Throwable ignored) {
            return 3000;
        }
    }

    private static void addEvent(CrazyRuntime.StageRuntime rt, int type, float pos) {
        if (rt == null) return;
        if (rt.beam.events.size() >= 48) {
            rt.beam.events.remove(0);
        }
        rt.beam.events.add(new BeamEvent(type, pos));
    }

    private static void clearNativeCannonState(Cannon cannon, float pos) {
        if (cannon == null) return;
        try { BCUFields.set(cannon, "anim", null); } catch (Throwable ignored) {}
        try { BCUFields.set(cannon, "atka", null); } catch (Throwable ignored) {}
        try { BCUFields.set(cannon, "exta", null); } catch (Throwable ignored) {}
        try { BCUFields.setInt(cannon, "duration", 0); } catch (Throwable ignored) {}
        try { BCUFields.setInt(cannon, "preTime", 0); } catch (Throwable ignored) {}
        try { cannon.pos = pos; } catch (Throwable ignored) {}
    }

    private static void updateEvents(CrazyRuntime.StageRuntime rt) {
        for (int i = rt.beam.events.size() - 1; i >= 0; i--) {
            BeamEvent ev = rt.beam.events.get(i);
            ev.age++;
            if (ev.age > 34) {
                rt.beam.events.remove(i);
            }
        }
    }

    private static float kameTaper(CrazyRuntime.StageRuntime rt) {
        return clamp01((rt.beam.duration - rt.beam.frame) / (float) KAME_TAPER_FRAMES);
    }

    private static float beamNearPos(StageBasis sb) {
        return playerBasePos(sb);
    }

    private static float beamFarPos(StageBasis sb) {
        return sb.ebase.pos;
    }

    private static float playerBasePos(StageBasis sb) {
        try {
            return sb.st.len - 800f;
        } catch (Throwable ignored) {
            return sb.ubase.pos;
        }
    }

    private static float moveToward(float current, float target, float maxStep) {
        float d = target - current;
        if (Math.abs(d) <= maxStep) return target;
        return current + Math.signum(d) * maxStep;
    }

    private static boolean isLiveEnemy(Entity e) {
        if (e == null || e.dead || e.health <= 0L || e.dire != 1 || e.isBase()) return false;
        if (isDeathVisual(e)) return false;
        try {
            return (e.touchable() & 1) != 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isDeathVisual(Entity e) {
        try {
            if (BCUFields.getInt(e, "kbTime") == -1) return true;
        } catch (Throwable ignored) {}
        try {
            Object anim = BCUFields.get(e, "anim");
            if (anim != null && BCUFields.getInt(anim, "dead") > 0) return true;
        } catch (Throwable ignored) {}
        try {
            return (e.touchable() & 0x10) != 0 && (e.touchable() & 1) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String entityState(Entity e) {
        int touch = -1;
        int kb = 9999;
        int animDead = -1;
        try { touch = e.touchable(); } catch (Throwable ignored) {}
        try { kb = BCUFields.getInt(e, "kbTime"); } catch (Throwable ignored) {}
        try { animDead = BCUFields.getInt(BCUFields.get(e, "anim"), "dead"); } catch (Throwable ignored) {}
        return "hp=" + e.health + " dead=" + e.dead + " touch=" + touch
                + " kb=" + kb + " animDead=" + animDead;
    }

    private static float[] cannonMuzzle(Object bbpainter, CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        float[] bossMuzzle = BossItemFeature.playerBaseBossMuzzle(rt, bbpainter);
        if (bossMuzzle != null) return bossMuzzle;
        float siz = BBPainterAccess.getSiz(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);
        int id = 0;
        try {
            id = Math.max(0, Math.min(CANNON_Y.length - 1, sb.canon.id));
        } catch (Throwable ignored) {}

        float baseWorldPos = (sb.ubase != null) ? sb.ubase.pos : playerBasePos(sb);

        float psiz = siz * CANNON_SPRITE_SCALE;
        float mountX = CrazyRender.screenX(bbpainter, baseWorldPos) + CANNON_X[id] * siz;
        float mountY = midh + (CANNON_Y[id] - 156f) * siz;
        float x = mountX + MUZZLE_DX[id] * psiz;
        float y = mountY + MUZZLE_DY[id] * psiz;
        return new float[]{x, y};
    }

    private static int duration(CrazyConfig.BeamMode mode) {
        if (mode == CrazyConfig.BeamMode.HYPNOSIS) return HYPNOSIS_FRAMES;
        if (mode == CrazyConfig.BeamMode.EVOLUTION) return EVOLUTION_FRAMES;
        if (mode == CrazyConfig.BeamMode.KAMEHAMEHA) return KAME_FRAMES;
        return 0;
    }

    private static boolean between(float pos, float a, float b) {
        return pos >= Math.min(a, b) && pos <= Math.max(a, b);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth(float v) {
        float t = clamp01(v);
        return t * t * (3f - 2f * t);
    }

    private static int readLayer(Object entity, String field, int fallback) {
        try {
            return BCUFields.getInt(entity, field);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void writeLayer(Object entity, int layer, int currentLayer, int spawnLayer) {
        setIntQuiet(entity, "layer", layer);
        setIntQuiet(entity, "currentLayer", currentLayer);
        setIntQuiet(entity, "spawnLayer", spawnLayer);
    }

    private static void setIntQuiet(Object entity, String field, int value) {
        try {
            BCUFields.setInt(entity, field, value);
        } catch (Throwable ignored) {}
    }

    private static void setFloatQuiet(Object entity, String field, float value) {
        try {
            BCUFields.set(entity, field, Float.valueOf(value));
        } catch (Throwable ignored) {}
    }

    private static int clampDamage(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < 1L) return 1;
        return (int) v;
    }

    private static void copyOptionalField(Object from, Object to, String name) {
        try {
            Object value = BCUFields.get(from, name);
            BCUFields.set(to, name, value);
        } catch (Throwable ignored) {}
    }
}
