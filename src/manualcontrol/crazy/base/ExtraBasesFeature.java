package manualcontrol.crazy.base;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.entity.ECastle;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import common.util.unit.EForm;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.crazy.beam.ArmyCanonFeature;
import manualcontrol.crazy.unit.BossItemFeature;
import manualcontrol.reflect.BCUFields;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;

public final class ExtraBasesFeature {

    private static final int EXTRA_COUNT = 2;

    private static final float DEFAULT_CASTLE_IMG_W = 128f;
    private static final float BODY_FRACTION = 110f / 128f;
    private static volatile Method drawNyCast;

    private ExtraBasesFeature() {}

    public static final class State {
        public final ECastle[] bases = new ECastle[EXTRA_COUNT];
        public long sharedHealth = -1L;
    }

    public static void tick(CrazyRuntime.StageRuntime rt) {
        ensure(rt);
        StageBasis sb = (StageBasis) rt.stage;
        long shared = sb.ubase.health;
        for (int i = 0; i < EXTRA_COUNT; i++) {
            ECastle b = rt.extraBases.bases[i];
            if (b == null) continue;
            shared = Math.min(shared, b.health);
        }
        if (shared < sb.ubase.health) {
            sb.ubase.health = Math.max(0L, shared);
        }
        for (int i = 0; i < EXTRA_COUNT; i++) {
            ECastle b = rt.extraBases.bases[i];
            if (b == null) continue;
            b.maxH = sb.ubase.maxH;
            b.health = sb.ubase.health;
            b.update();
        }
        rt.extraBases.sharedHealth = sb.ubase.health;
    }

    public static void onManualSpawn(CrazyRuntime.StageRuntime rt, int row, int col) {
        ensure(rt);
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = 0; i < EXTRA_COUNT; i++) {
            EForm form = formFor(rt, row, col, i + 1);
            if (form == null) continue;
            spawnAtExtraBase(sb, form, row, col, i);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void appendInRange(CrazyRuntime.StageRuntime rt, List result, int touch, int dire,
                                     float d0, float d1, boolean excludeRightEdge) {
        if (dire != -1 || (touch & 1) == 0) return;
        ensure(rt);
        float left = Math.min(d0, d1);
        float right = Math.max(d0, d1);
        for (int i = 0; i < EXTRA_COUNT; i++) {
            ECastle b = rt.extraBases.bases[i];
            if (b == null || b.health <= 0L) continue;
            boolean inside = excludeRightEdge ? b.pos >= left && b.pos < right : b.pos >= left && b.pos <= right;
            if (inside && !result.contains(b)) {
                result.add(b);
            }
        }
    }

    public static void drawNyCast(FakeGraphics gra, int y, int x, float siz, int[] nyc, Object bbpainter) {
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(manualcontrol.reflect.BBPainterAccess.getStageBasis(bbpainter));
        int dx = 0;
        int dy = 0;
        if (rt != null && rt.config.interactiveBase) {
            dx = Math.round(rt.interactiveBase.offsetX);
            dy = Math.round(rt.interactiveBase.offsetY);
        }
        drawMainPlayerBase(rt, gra, y + dy, x + dx, siz, nyc);
        if (rt == null || !rt.config.extraPlayerBases) return;
        ensure(rt);
        StageBasis sb = (StageBasis) rt.stage;

        for (int i = EXTRA_COUNT - 1; i >= 0; i--) {
            ECastle b = rt.extraBases.bases[i];
            if (b == null) continue;
            int bx = x + Math.round((b.pos - sb.ubase.pos) * 0.32f * siz);
            invokeDraw(gra, y, bx, siz, nyc);
        }
    }

    private static void drawMainPlayerBase(CrazyRuntime.StageRuntime rt, FakeGraphics gra,
                                           int y, int x, float siz, int[] nyc) {
        float scale = BossItemFeature.playerBaseDrawScale(rt);
        float[] army = ArmyCanonFeature.castleTransform(rt);
        float[] cat = (army == null && rt != null && rt.config != null && rt.config.catCanonBase)
                ? CatCanonFeature.baseTransform(rt) : null;
        if (scale <= 1.001f && army == null && cat == null) {
            invokeDraw(gra, y, x, siz, nyc);
            return;
        }
        FakeTransform old = gra.getTransform();
        try {
            float pivotX = BossItemFeature.playerBaseFootPivotX(rt, x, siz);
            float pivotY = BossItemFeature.playerBaseFootPivotY(rt, y, siz);
            if (army != null) {

                ArmyCanonFeature.notifyCastlePivot(rt, pivotX, pivotY);
                gra.translate(pivotX + army[2], pivotY + army[3]);
                if (Math.abs(army[4]) > 0.001f) gra.rotate(army[4]);
                gra.scale(army[0], army[1]);
                gra.translate(-pivotX, -pivotY);
            } else if (cat != null) {

                CatCanonFeature.notifyCastlePivot(rt, pivotX, pivotY);
                gra.translate(pivotX + cat[2], pivotY + cat[3]);
                if (Math.abs(cat[4]) > 0.001f) gra.rotate(cat[4]);
                gra.scale(cat[0], cat[1]);
                gra.translate(-pivotX, -pivotY);
            }
            if (scale > 1.001f) {
                gra.translate(pivotX, pivotY);
                gra.scale(scale, scale);
                gra.translate(-pivotX, -pivotY);
            }
            invokeDraw(gra, y, x, siz, nyc);
        } catch (Throwable t) {
            Logger.err("BCU Crazy base draw transform failed", t);
            try { gra.setTransform(old); } catch (Throwable ignored) {}
            invokeDraw(gra, y, x, siz, nyc);
        } finally {
            try { gra.setTransform(old); } catch (Throwable ignored) {}
            try { gra.delete(old); } catch (Throwable ignored) {}
        }
    }

    public static float virtualBasePos(CrazyRuntime.StageRuntime rt, int index) {
        ensure(rt);
        ECastle b = rt.extraBases.bases[index];
        return b == null ? ((StageBasis) rt.stage).ubase.pos : b.pos;
    }

    private static void ensure(CrazyRuntime.StageRuntime rt) {
        StageBasis sb = (StageBasis) rt.stage;
        for (int i = 0; i < EXTRA_COUNT; i++) {
            if (rt.extraBases.bases[i] == null) {
                ECastle b = new ECastle(sb, sb.b);
                b.added(-1, extraBasePos(sb, i));
                b.maxH = sb.ubase.maxH;
                b.health = sb.ubase.health;
                rt.extraBases.bases[i] = b;
            } else {
                rt.extraBases.bases[i].pos = extraBasePos(sb, i);
                rt.extraBases.bases[i].lastPosition = rt.extraBases.bases[i].pos;
            }
        }
    }

    private static EForm formFor(CrazyRuntime.StageRuntime rt, int row, int col, int offset) {
        try {
            StageBasis sb = (StageBasis) rt.stage;
            EForm base = sb.b.lu.efs[row][col];
            if (base == null) return null;
            if (!rt.config.multiBaseHigherForms) return base;
            Form form = base.du.getPack();
            Unit unit = form.unit;
            if (unit == null || unit.forms == null || unit.forms.length == 0) return base;
            int fid = Math.min(unit.forms.length - 1, form.fid + offset);
            if (fid == form.fid) return base;
            try {
                Level level = base.getLevel();
                return new EForm(unit.forms[fid], level);
            } catch (Throwable ignored) {
                return new EForm(unit.forms[fid], 50);
            }
        } catch (Throwable t) {
            Logger.err("BCU Crazy choose extra-base form failed", t);
            return null;
        }
    }

    private static void spawnAtExtraBase(StageBasis sb, EForm form, int row, int col, int baseIndex) {
        try {
            EUnit eu = form.getEntity(sb, new int[]{row, col}, false, false);
            float p = doorPos(sb, baseIndex);
            eu.added(-1, p);
            eu.currentLayer = Math.max(0, Math.min(9, eu.currentLayer + baseIndex + 1));
            try {
                BCUFields.setInt(eu, "price", sb.elu.price[row][col]);
            } catch (Throwable ignored) {}
            sb.le.add(eu);
            sb.le.sort(Comparator.comparingInt(e -> e.currentLayer));
            Logger.log("BCU Crazy extra base spawned row=" + row + " col=" + col
                    + " base=" + (baseIndex + 2) + " pos=" + Math.round(p));
        } catch (Throwable t) {
            Logger.err("BCU Crazy extra-base spawn failed", t);
        }
    }

    private static float playerBasePos(StageBasis sb) {
        try {
            return sb.st.len - 800f;
        } catch (Throwable ignored) {
            return sb.ubase.pos;
        }
    }

    public static float doorPos(StageBasis sb, int index) {
        return extraBasePos(sb, index) + 100f;
    }

    private static float extraBasePos(StageBasis sb, int index) {

        float step = (castleBodyWidthPx(sb) + 1f) / 0.32f;
        return playerBasePos(sb) + step * (index + 1);
    }

    private static float castleBodyWidthPx(StageBasis sb) {
        float imgW = DEFAULT_CASTLE_IMG_W;
        try {
            int[] nyc = (int[]) BCUFields.get(sb, "nyc");
            FakeImage img = CommonStatic.getBCAssets().main[2][nyc[2]].getImg();
            if (img != null && img.getWidth() > 0) imgW = img.getWidth();
        } catch (Throwable ignored) {}
        return imgW * BODY_FRACTION;
    }

    private static void invokeDraw(FakeGraphics gra, int y, int x, float siz, int[] nyc) {
        try {
            Method m = drawNyCast;
            if (m == null) {
                Class<?> cls = Class.forName("page.battle.BattleBox$BBPainter");
                m = cls.getDeclaredMethod("drawNyCast", FakeGraphics.class, int.class, int.class, float.class, int[].class);
                m.setAccessible(true);
                drawNyCast = m;
            }
            m.invoke(null, gra, y, x, siz, nyc);
        } catch (Throwable t) {
            Logger.err("BCU Crazy drawNyCast failed", t);
        }
    }
}
