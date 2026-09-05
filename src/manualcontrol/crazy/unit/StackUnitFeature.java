package manualcontrol.crazy.unit;

import common.CommonStatic;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRender;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.BCUFields;
import manualcontrol.reflect.EntityAccess;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class StackUnitFeature {

    private static final float STACK_BONUS   = 0.10f;
    private static final float CD_BONUS      = 0.01f;
    private static final int   FLIGHT_FRAMES = 45;
    private static final int   FLASH_FRAMES  = 20;

    private static final float MUZZLE_DX     = 22.4f;
    private static final float MUZZLE_UP     = 290f;

    private StackUnitFeature() {}

    public static final class State {

        public final WeakHashMap<Object, Integer> stackCounts = new WeakHashMap<Object, Integer>();

        public final List<StackProjectile> projectiles = new ArrayList<StackProjectile>();

        public final WeakHashMap<Object, Integer> flashFrames = new WeakHashMap<Object, Integer>();

        final int[][] baseMaxC = new int[2][5];
        boolean baseMaxCCaptured = false;
    }

    static final class StackProjectile {
        final Object target;
        int framesLeft;
        final int newStackCount;
        boolean done;

        StackProjectile(Object target, int framesLeft, int newStackCount) {
            this.target = target;
            this.framesLeft = framesLeft;
            this.newStackCount = newStackCount;
        }
    }

    public static boolean hasActive(CrazyRuntime.StageRuntime rt) {
        return rt != null && !rt.stackUnit.projectiles.isEmpty();
    }

    public static int beforeSpawn(CrazyRuntime.StageRuntime rt, int row, int col, boolean manual) {
        State state = rt.stackUnit;

        if (!state.baseMaxCCaptured) captureBaseMaxC(rt, state);

        Object eform = getEForm(rt.stage, row, col);
        if (eform == null) return -1;

        Object formData = getFormDu(eform);
        if (formData == null) return -1;

        Object existing = findOnField(rt, formData);
        if (existing == null) return -1;

        if (!manual && !isLocked(rt, row, col)) return -1;

        int price = getPrice(rt, row, col);
        if (price < 0) return -1;
        int money = getMoney(rt.stage);
        if (money < price) {
            if (manual) CommonStatic.setSE(15);
            return 0;
        }

        if (getCool(rt, row, col) > 0) {
            if (manual) CommonStatic.setSE(15);
            return 0;
        }

        int newCount = stackCountOf(state, existing) + inFlightCountFor(state, existing) + 1;

        BCUFields.setInt(rt.stage, "money", money - price);

        int baseCD = state.baseMaxC[row][col];
        int newCD = Math.round(baseCD * (1f + newCount * CD_BONUS));
        setCool(rt, row, col, newCD);

        CommonStatic.setSE(19);

        state.projectiles.add(new StackProjectile(existing, FLIGHT_FRAMES, newCount));
        return 1;
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        State state = rt.stackUnit;

        Iterator<StackProjectile> pit = state.projectiles.iterator();
        while (pit.hasNext()) {
            StackProjectile p = pit.next();
            if (p.done || EntityAccess.isDead(p.target)) {
                pit.remove();
                continue;
            }
            p.framesLeft--;
            if (p.framesLeft <= 0) {
                applyStackBuff(state, p.target, p.newStackCount);
                p.done = true;
                pit.remove();
            }
        }

        Iterator<Map.Entry<Object, Integer>> fit = state.flashFrames.entrySet().iterator();
        while (fit.hasNext()) {
            Map.Entry<Object, Integer> e = fit.next();
            int rem = e.getValue() - 1;
            if (rem <= 0) fit.remove();
            else e.setValue(rem);
        }

        Iterator<Object> sit = state.stackCounts.keySet().iterator();
        while (sit.hasNext()) {
            Object entity = sit.next();
            if (entity == null || EntityAccess.isDead(entity)) sit.remove();
        }
    }

    public static void applyDamageMultiplier(CrazyRuntime.StageRuntime rt, Object attackObj) {
        if (attackObj == null) return;
        Entity attacker;
        try {
            attacker = (Entity) BCUFields.get(attackObj, "attacker");
        } catch (Throwable t) {
            return;
        }
        if (!EntityAccess.isPlayerUnit(attacker)) return;
        float mult = multiplierFor(rt, attacker);
        if (mult <= 1.001f) return;
        try {
            int rawAtk = BCUFields.getInt(attackObj, "rawAtk");
            long next = Math.round((double) rawAtk * (double) mult);
            if (next > Integer.MAX_VALUE) next = Integer.MAX_VALUE;
            if (next < Integer.MIN_VALUE) next = Integer.MIN_VALUE;
            BCUFields.field(attackObj.getClass(), "atk").setInt(attackObj, (int) next);
        } catch (Throwable t) {
            Logger.err("StackUnit: applyDamageMultiplier failed", t);
        }
    }

    public static float scaleFor(CrazyRuntime.StageRuntime rt, Object entity) {
        if (rt == null || entity == null) return 1.0f;
        int count = stackCountOf(rt.stackUnit, entity);
        return count <= 0 ? 1.0f : 1.0f + count * STACK_BONUS;
    }

    public static float scaleFor(Object entity) {
        if (!(entity instanceof Entity)) return 1.0f;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(((Entity) entity).basis);
        if (rt == null) return 1.0f;
        float scale = scaleFor(rt, entity);
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return 1.0f;
        return Math.max(1.0f, scale);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void appendScaledHitboxes(CrazyRuntime.StageRuntime rt, List result, int touch, int dire,
                                             float d0, float d1, boolean excludeRightEdge) {
        if (rt == null || result == null || dire != -1) return;
        List<Object> entities;
        try {
            entities = (List<Object>) BCUFields.get(rt.stage, "le");
        } catch (Throwable t) {
            return;
        }
        float left  = Math.min(d0, d1);
        float right = Math.max(d0, d1);
        for (Object obj : entities) {
            if (!(obj instanceof EUnit)) continue;
            Entity e = (Entity) obj;
            if (e.dead || e.health <= 0L) continue;
            if (!touchable(e, touch)) continue;
            float scale = scaleFor(rt, e);
            if (scale <= 1.001f) continue;
            float extra = hitboxExtra(e, scale);
            if (extra <= 0.5f) continue;
            boolean inside = excludeRightEdge
                    ? e.pos >= left - extra && e.pos < right + extra
                    : e.pos >= left - extra && e.pos <= right + extra;
            if (inside && !result.contains(e)) result.add(e);
        }
    }

    public static float flashAlphaFor(CrazyRuntime.StageRuntime rt, Object entity) {
        if (rt == null || entity == null) return 0f;
        Integer frames = rt.stackUnit.flashFrames.get(entity);
        if (frames == null || frames <= 0) return 0f;
        return Math.min(1f, frames / (float) FLASH_FRAMES);
    }

    private static void captureBaseMaxC(CrazyRuntime.StageRuntime rt, State state) {
        try {
            Object elu = BCUFields.get(rt.stage, "elu");
            int[][] maxC = (int[][]) BCUFields.get(elu, "maxC");
            for (int r = 0; r < 2; r++)
                for (int c = 0; c < 5; c++)
                    state.baseMaxC[r][c] = maxC[r][c];
            state.baseMaxCCaptured = true;
        } catch (Throwable t) {
            Logger.err("StackUnit: captureBaseMaxC failed", t);
        }
    }

    private static Object getEForm(Object stageBasis, int row, int col) {
        try {
            Object b   = BCUFields.get(stageBasis, "b");
            Object lu  = BCUFields.get(b, "lu");
            Object[][] efs = (Object[][]) BCUFields.get(lu, "efs");
            if (efs == null || row >= efs.length || col >= efs[row].length) return null;
            return efs[row][col];
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getFormDu(Object eform) {
        try {
            return BCUFields.get(eform, "du");
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object findOnField(CrazyRuntime.StageRuntime rt, Object formData) {
        try {
            List<Object> le = (List<Object>) BCUFields.get(rt.stage, "le");
            for (Object obj : le) {
                if (!(obj instanceof EUnit)) continue;
                Entity e = (Entity) obj;
                if (e.dead || e.health <= 0L) continue;
                Object data = BCUFields.get(e, "data");
                if (data == formData) return e;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int getPrice(CrazyRuntime.StageRuntime rt, int row, int col) {
        try {
            Object elu = BCUFields.get(rt.stage, "elu");
            int[][] price = (int[][]) BCUFields.get(elu, "price");
            return price[row][col];
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int getMoney(Object stageBasis) {
        try {
            return BCUFields.getInt(stageBasis, "money");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isLocked(CrazyRuntime.StageRuntime rt, int row, int col) {
        try {
            boolean[][] locks = (boolean[][]) BCUFields.get(rt.stage, "locks");
            return locks != null && locks[row][col];
        } catch (Throwable t) {
            return false;
        }
    }

    private static int getCool(CrazyRuntime.StageRuntime rt, int row, int col) {
        try {
            Object elu = BCUFields.get(rt.stage, "elu");
            int[][] cool = (int[][]) BCUFields.get(elu, "cool");
            return cool[row][col];
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void setCool(CrazyRuntime.StageRuntime rt, int row, int col, int value) {
        try {
            Object elu = BCUFields.get(rt.stage, "elu");
            int[][] cool = (int[][]) BCUFields.get(elu, "cool");
            cool[row][col] = value;
        } catch (Throwable t) {
            Logger.err("StackUnit: setCool failed", t);
        }
    }

    private static void applyStackBuff(State state, Object entity, int newStackCount) {
        try {
            Entity e = (Entity) entity;
            float newMult  = 1f + newStackCount * STACK_BONUS;
            float prevMult = 1f + (newStackCount - 1) * STACK_BONUS;
            float ratio    = newMult / prevMult;

            long newMaxH   = Math.round((double) e.maxH * ratio);
            long healthAdd = newMaxH - e.maxH;
            e.maxH   = newMaxH;
            e.health = Math.min(e.maxH, e.health + healthAdd);

            state.stackCounts.put(entity, newStackCount);

            state.flashFrames.put(entity, FLASH_FRAMES);

            CommonStatic.setSE(27);

            Logger.log("StackUnit: buffed " + e.getClass().getSimpleName()
                    + " to stack " + newStackCount + " (mult=" + newMult + ")");
        } catch (Throwable t) {
            Logger.err("StackUnit: applyStackBuff failed", t);
        }
    }

    private static int stackCountOf(State state, Object entity) {
        Integer n = state.stackCounts.get(entity);
        return n == null ? 0 : n;
    }

    private static int inFlightCountFor(State state, Object target) {
        int n = 0;
        for (int i = 0; i < state.projectiles.size(); i++) {
            StackProjectile p = state.projectiles.get(i);
            if (!p.done && p.target == target) n++;
        }
        return n;
    }

    private static float multiplierFor(CrazyRuntime.StageRuntime rt, Object entity) {
        int count = stackCountOf(rt.stackUnit, entity);
        return count <= 0 ? 1.0f : 1.0f + count * STACK_BONUS;
    }

    private static float hitboxExtra(Entity e, float scale) {
        float width = 120f;
        try {
            Object data = BCUFields.get(e, "data");
            Object w = BCUFields.invoke(data, "getWidth");
            if (w instanceof Number) width = Math.max(40f, ((Number) w).floatValue());
        } catch (Throwable ignored) {}
        return width * 0.5f * (scale - 1.0f);
    }

    private static boolean touchable(Entity e, int touch) {
        try {
            return ((Integer) BCUFields.invoke(e, "touchable") & touch) > 0;
        } catch (Throwable ignored) {
            return (touch & 1) != 0;
        }
    }

    public static void draw(CrazyRuntime.StageRuntime rt, Object bbpainter, FakeGraphics gra) {
        State state = rt.stackUnit;
        if (state.projectiles.isEmpty() && state.flashFrames.isEmpty()) return;
        boolean gl = isGl(gra);
        float siz = 1f;
        try { siz = BBPainterAccess.getSiz(bbpainter); } catch (Throwable ignored) {}
        siz = Math.max(0.45f, siz);
        FakeTransform old = pushIdentityTransform(gra);
        try {
            for (int i = 0; i < state.projectiles.size(); i++) {
                drawProjectile(state.projectiles.get(i), bbpainter, gra, gl, siz);
            }
            for (Map.Entry<Object, Integer> entry :
                    new ArrayList<Map.Entry<Object, Integer>>(state.flashFrames.entrySet())) {
                drawFlash(entry.getKey(), entry.getValue(), bbpainter, gra, gl, siz);
            }
        } finally {
            resetComposite(gra);
            popTransform(gra, old);
        }
    }

    private static void drawProjectile(StackProjectile p, Object bbpainter,
                                        FakeGraphics gra, boolean gl, float siz) {
        if (p.done || EntityAccess.isDead(p.target)) return;
        float progress = 1f - (p.framesLeft / (float) FLIGHT_FRAMES);

        float srcX, srcY;
        try {
            Object base = BBPainterAccess.getPlayerBase(bbpainter);
            float basePos = EntityAccess.getPos(base);
            float sizR = BBPainterAccess.getSiz(bbpainter);
            int midh = BBPainterAccess.getMidh(bbpainter);

            srcX = CrazyRender.screenX(bbpainter, basePos) + MUZZLE_DX * sizR;
            srcY = midh - MUZZLE_UP * sizR;
        } catch (Throwable t) { return; }

        float dstX, dstY;
        try {
            Entity e = (Entity) p.target;
            dstX = CrazyRender.screenX(bbpainter, e.pos);
            dstY = CrazyRender.groundY(bbpainter, safeLayer(e)) - 60f * siz;
        } catch (Throwable t) { return; }

        for (int k = 4; k >= 0; k--) {
            float tp = Math.max(0f, Math.min(1f, progress - k * 0.05f));
            if (progress - k * 0.05f < 0f) continue;
            float tx = lerp(srcX, dstX, tp);
            float ty = lerp(srcY, dstY, tp) - (float) Math.sin(tp * Math.PI) * 40f * siz;
            if (k == 0) {

                fillCircle(gra, gl, tx, ty, 20f * siz, 255, 200, 80, 130);
                fillCircle(gra, gl, tx, ty, 13f * siz, 255, 255, 220, 220);
            } else {
                float r = (8f - k * 1.2f) * siz;
                int a = Math.round(140f - k * 28f);
                fillCircle(gra, gl, tx, ty, r, 255, 210, 100, a);
            }
        }
    }

    private static void drawFlash(Object entity, int framesLeft, Object bbpainter,
                                   FakeGraphics gra, boolean gl, float siz) {
        if (framesLeft <= 0 || EntityAccess.isDead(entity)) return;
        float fade = framesLeft / (float) FLASH_FRAMES;
        float t    = 1f - fade;
        try {
            Entity e = (Entity) entity;
            float cx = CrazyRender.screenX(bbpainter, e.pos);
            float groundY = CrazyRender.groundY(bbpainter, safeLayer(e));
            float cy = groundY - 60f * siz;

            float ringR = (24f + 72f * t) * siz;
            drawRing(gra, gl, cx, cy, ringR, 3.2f * siz, 255, 210, 90, Math.round(200f * fade));

            fillCircle(gra, gl, cx, groundY - 8f * siz, (38f + 26f * t) * siz,
                    255, 200, 80, Math.round(85f * fade));

            float coreR = (34f * fade + 8f) * siz;
            fillCircle(gra, gl, cx, cy, coreR * 1.5f, 255, 200, 80, Math.round(150f * fade));
            fillCircle(gra, gl, cx, cy, coreR, 255, 255, 230, Math.round(230f * fade));

            int sparks = 8;
            for (int k = 0; k < sparks; k++) {
                float ang = (float) (Math.PI * 2.0 * k / sparks);
                float spread = (float) Math.cos(ang) * (16f + 10f * t) * siz;
                float rise   = (28f + 92f * t) * siz + (k % 3) * 6f * siz;
                float sr = (3.6f - 1.6f * t) * siz;
                if (sr < 0.5f) continue;
                fillCircle(gra, gl, cx + spread, cy - rise, sr, 255, 235, 150, Math.round(220f * fade));
            }

            float beamA = fade * (1f - t * 0.4f);
            float beamH = (60f + 50f * t) * siz;
            int bands = 6;
            for (int i = 0; i < bands; i++) {
                float f0 = i / (float) bands;
                float bw = 6f * siz * fade * (1f - 0.7f * f0);
                if (bw < 0.5f) continue;
                fillCircle(gra, gl, cx, cy - beamH * f0, bw,
                        255, 245, 200, Math.round(120f * beamA * (1f - f0)));
            }
        } catch (Throwable ignored) {}
    }

    private static void drawRing(FakeGraphics g, boolean gl, float cx, float cy, float r,
                                  float dotR, int red, int green, int blue, int alpha) {
        if (alpha <= 0 || r <= 1f || dotR < 0.5f) return;
        int n = Math.max(14, Math.round(r * 0.5f));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0 * i / n;
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r * 0.82f;
            fillCircle(g, gl, x, y, dotR, red, green, blue, alpha);
        }
    }

    private static void fillCircle(FakeGraphics g, boolean gl, float cx, float cy, float r,
                                    int red, int green, int blue, int alpha) {
        if (alpha <= 0 || r <= 0f) return;
        int a = Math.max(0, Math.min(255, alpha));
        int iy = Math.max(1, Math.round(r));
        if (gl) {
            for (int dy = -iy; dy <= iy; dy++) {
                double dxf = r * Math.sqrt(Math.max(0.0, 1.0 - ((double) dy * dy) / ((double) r * r)));
                if (dxf < 0.5) continue;
                g.colRect((float) (cx - dxf), cy + dy - 0.5f, (float) (dxf * 2.0), 1f, red, green, blue, a);
            }
        } else {
            try {
                g.setComposite(FakeGraphics.TRANS, a, 0);
                g.setColor(red, green, blue);
                int d = Math.max(2, Math.round(r * 2f));
                g.fillOval(cx - r, cy - r, d, d);
            } finally {
                g.setComposite(FakeGraphics.DEF, 0, 0);
            }
        }
    }

    private static boolean isGl(FakeGraphics gra) {
        return gra != null && gra.getClass().getName().contains("GLGraphics");
    }

    private static volatile Field transformDataField = null;

    private static FakeTransform pushIdentityTransform(FakeGraphics gra) {
        if (gra == null) return null;
        try {
            FakeTransform old = gra.getTransform();
            FakeTransform id = gra.getTransform();
            Field f = transformDataField;
            if (f == null || f.getDeclaringClass() != id.getClass()) {
                f = id.getClass().getDeclaredField("data");
                f.setAccessible(true);
                transformDataField = f;
            }
            f.set(id, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            gra.setTransform(id);
            try { gra.delete(id); } catch (Throwable ignored) {}
            return old;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void popTransform(FakeGraphics gra, FakeTransform old) {
        if (gra == null || old == null) return;
        try { gra.setTransform(old); gra.delete(old); } catch (Throwable ignored) {}
    }

    private static void resetComposite(FakeGraphics gra) {
        try { if (gra != null) gra.setComposite(FakeGraphics.DEF, 0, 0); } catch (Throwable ignored) {}
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int safeLayer(Entity e) {
        try { return Math.max(0, Math.min(9, e.currentLayer)); } catch (Throwable ignored) { return 0; }
    }
}
