package manualcontrol.crazy;

import common.battle.entity.Cannon;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import manualcontrol.crazy.base.CatCanonFeature;
import manualcontrol.crazy.base.SlingshotBaseFeature;
import manualcontrol.crazy.beam.ArmyCanonFeature;
import manualcontrol.crazy.beam.BeamFeature;
import manualcontrol.crazy.beam.CopyCatUfoFeature;
import manualcontrol.crazy.unit.BossItemFeature;

public final class CrazyCannonHooks {
    private CrazyCannonHooks() {}

    public static boolean onActivate(Object cannonObj) {
        if (!(cannonObj instanceof Cannon)) return false;
        Cannon cannon = (Cannon) cannonObj;
        if (BossItemFeature.isBossNativeCannon(cannon)) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(cannon.b);
        if (rt == null || rt.config.beamMode == null || rt.config.beamMode == CrazyConfig.BeamMode.NONE) {
            return false;
        }
        if (rt.config.beamMode == CrazyConfig.BeamMode.ARMY_CANON) {
            CrazyRuntime.safe("army-canon-activate", rt, new Runnable() {
                @Override
                public void run() {
                    ArmyCanonFeature.activate(rt, cannon);
                }
            });
            return !rt.isDisabled("army-canon-activate");
        }
        if (rt.config.beamMode == CrazyConfig.BeamMode.COPY_CAT_UFO) {
            CrazyRuntime.safe("copycat-ufo-activate", rt, new Runnable() {
                @Override
                public void run() {
                    CopyCatUfoFeature.activate(rt, cannon);
                }
            });
            return !rt.isDisabled("copycat-ufo-activate");
        }
        CrazyRuntime.safe("beam-activate", rt, new Runnable() {
            @Override
            public void run() {
                    BeamFeature.activate(rt, cannon);
            }
        });
        return !rt.isDisabled("beam-activate");
    }

    public static boolean onUpdate(Object cannonObj) {
        if (!(cannonObj instanceof Cannon)) return false;
        Cannon cannon = (Cannon) cannonObj;
        if (BossItemFeature.isBossNativeCannon(cannon)) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(cannon.b);
        if (rt == null) return false;
        if (rt.armyCanon.active || rt.armyCanon.locked) {
            CrazyRuntime.safe("army-canon-update", rt, new Runnable() {
                @Override
                public void run() {
                    ArmyCanonFeature.updateActive(rt, cannon);
                }
            });
            return true;
        }
        if (!rt.beam.active) return false;
        CrazyRuntime.safe("beam-update", rt, new Runnable() {
            @Override
            public void run() {
                BeamFeature.updateActive(rt, cannon);
            }
        });
        return true;
    }

    public static boolean onDrawAtk(Object cannonObj) {
        if (!(cannonObj instanceof Cannon)) return false;
        Cannon cannon = (Cannon) cannonObj;
        if (BossItemFeature.isBossNativeCannon(cannon)) return false;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(cannon.b);
        if (rt != null && rt.config.beamMode == CrazyConfig.BeamMode.ARMY_CANON) return true;
        return rt != null && rt.config.beamMode != null && rt.config.beamMode != CrazyConfig.BeamMode.NONE;
    }

    public static FakeTransform beforeDrawBase(Object cannonObj, Object graphicsObj, Object pointObj, float siz) {
        if (!(cannonObj instanceof Cannon) || !(graphicsObj instanceof FakeGraphics) || !(pointObj instanceof P)) return null;
        Cannon cannon = (Cannon) cannonObj;
        if (BossItemFeature.isBossNativeCannon(cannon)) return null;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(cannon.b);
        if (rt == null) return null;
        FakeGraphics g = (FakeGraphics) graphicsObj;
        P p = (P) pointObj;
        FakeTransform old = g.getTransform();
        boolean changed = false;
        try {
            if (rt.config.beamMode == CrazyConfig.BeamMode.ARMY_CANON) {
                float[] t = ArmyCanonFeature.baseTransform(rt);
                if (t != null) {

                    float px = ArmyCanonFeature.pivotXOr(rt, p.x);
                    float py = ArmyCanonFeature.pivotYOr(rt, p.y);
                    g.translate(px + t[2], py + t[3]);
                    if (Math.abs(t[4]) > 0.001f) g.rotate(t[4]);
                    g.scale(t[0], t[1]);
                    g.translate(-px, -py);
                    changed = true;
                }
            }
            if (!changed && rt.config.catCanonBase) {
                float[] t = CatCanonFeature.baseTransform(rt);
                if (t != null) {

                    float px = CatCanonFeature.pivotXOr(rt, p.x);
                    float py = CatCanonFeature.pivotYOr(rt, p.y);
                    g.translate(px + t[2], py + t[3]);
                    if (Math.abs(t[4]) > 0.001f) g.rotate(t[4]);
                    g.scale(t[0], t[1]);
                    g.translate(-px, -py);
                    changed = true;
                }
            }
            if (!changed) {
                try { g.delete(old); } catch (Throwable ignored) {}
                return null;
            }
            return old;
        } catch (Throwable ignored) {
            try {
                g.setTransform(old);
                g.delete(old);
            } catch (Throwable ignored2) {}
            return null;
        }
    }

    public static void afterDrawBase(Object graphicsObj, Object transformObj) {
        if (!(graphicsObj instanceof FakeGraphics) || !(transformObj instanceof FakeTransform)) return;
        FakeGraphics g = (FakeGraphics) graphicsObj;
        FakeTransform t = (FakeTransform) transformObj;
        try {
            g.setTransform(t);
        } finally {
            try { g.delete(t); } catch (Throwable ignored) {}
        }
    }

    public static void afterNativeDrawBase(Object cannonObj, Object graphicsObj, Object pointObj, float siz) {
        if (!(cannonObj instanceof Cannon) || !(graphicsObj instanceof FakeGraphics) || !(pointObj instanceof P)) return;
        Cannon cannon = (Cannon) cannonObj;
        if (BossItemFeature.isBossNativeCannon(cannon)) return;
        CrazyRuntime.StageRuntime rt = CrazyRuntime.get(cannon.b);
        if (rt == null) return;
        P p = (P) pointObj;
        if (rt.config.slingshotBase) {
            SlingshotBaseFeature.drawBaseAttachments(rt, (FakeGraphics) graphicsObj, p.x, p.y, siz);
        }
        if (rt.config.catCanonBase) {
            CatCanonFeature.recordBaseAnchor(rt, p.x, p.y, siz);
        }
    }
}
