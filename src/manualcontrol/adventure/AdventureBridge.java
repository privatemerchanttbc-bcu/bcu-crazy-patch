package manualcontrol.adventure;

import common.battle.StageBasis;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyConfig;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.custommap.CustomMapDocument;
import manualcontrol.custommap.CustomMapRuntime;

import java.util.List;

public final class AdventureBridge {

    private static volatile AdventureBattle active;

    private static boolean f7Locked;

    private AdventureBridge() {}

    public static boolean isActive() { return active != null; }

    static void lockPhysicalCollision() {
        f7Locked = true;
        manualcontrol.crazy.collision.PhysicalCollision.ENABLED = true;
    }

    static void unlockPhysicalCollision() {
        manualcontrol.crazy.collision.PhysicalCollision.ENABLED = true;
        f7Locked = false;
    }

    public static boolean isPhysicalCollisionLocked() { return f7Locked; }

    public static AdventureBattle activeBattle() { return active; }

    public static boolean isActiveStage(Object stageBasis) {
        AdventureBattle b = active;
        return b != null && b.sb == stageBasis;
    }

    public static boolean isAdventureEntity(Object entity) {
        AdventureBattle b = active;
        return b != null && b.ownsEntity(entity);
    }

    public static boolean drawSplitShadow(Object entity, Object am,
                                          common.system.fake.FakeGraphics g,
                                          common.system.P p, float siz) {
        AdventureBattle b = active;
        if (b == null || entity == null || entity != b.player()) return false;
        if (!b.controller.isAirborne()) return false;
        Integer idx = SHADOW_PART.get(entity);
        if (idx == null || idx < 0) return false;
        try {
            Object anim = manualcontrol.reflect.BCUFields.get(am, "anim");
            if (anim == null) return false;
            float f = manualcontrol.reflect.BCUFields.getFloat(anim, "f");
            if (f < 0f) return false;
            Object[] order = (Object[]) manualcontrol.reflect.BCUFields.get(anim, "order");
            if (order == null || idx >= order.length) return false;

            float basisSiz = 1f;
            try {
                Object basis = manualcontrol.reflect.BCUFields.get(entity, "basis");
                basisSiz = manualcontrol.reflect.BCUFields.getFloat(basis, "siz");
            } catch (Throwable ignored) {}
            if (basisSiz <= 0.0001f) return false;
            float lift = b.controller.jumpLiftLayers() * 4f * basisSiz / drawScaleFor(entity);
            if (lift <= 0.5f) return false;

            try {
                java.lang.reflect.Method set = manualcontrol.reflect.BCUFields.method(
                        anim.getClass(), "set", common.system.fake.FakeGraphics.class);
                set.invoke(null, g);
            } catch (Throwable ignored) {}

            common.system.fake.FakeTransform saved = g.getTransform();
            try {
                g.translate(p.x, p.y);
                for (int i = 0; i < order.length; i++) {
                    if (!(order[i] instanceof common.util.anim.EPart)) continue;
                    common.system.P pp = new common.system.P(siz, siz);
                    if (i == idx) {
                        g.translate(0f, lift);
                        ((common.util.anim.EPart) order[i]).drawPart(g, pp);
                        g.translate(0f, -lift);
                    } else {
                        ((common.util.anim.EPart) order[i]).drawPart(g, pp);
                    }
                }
            } finally {
                g.setTransform(saved);
                try { g.delete(saved); } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static float drawScaleFor(Object entity) {
        AdventureBattle b = active;
        if (b == null || b.player() != entity) return 1f;
        return AdventureRuntime.cores().hasUnique("L1") ? 2f : 1f;
    }

    public static CrazyConfig adventureConfig() {
        CrazyConfig c = CrazyConfig.defaults();
        c.manualControl = false;
        return c;
    }

    public static void adoptBattle(Object page) {
        try {
            Object sb = CrazyRuntime.stageFromPage(page);
            if (!(sb instanceof StageBasis)) {
                Logger.err("Adventure: could not resolve StageBasis from page - aborting run", null);
                active = null;
                unlockPhysicalCollision();
                AdventureRuntime.reset("adopt-failed");
                return;
            }
            CustomMapRuntime.adopt((StageBasis) sb, CustomMapDocument.MapMode.ADVENTURE);
            active = new AdventureBattle((StageBasis) sb, page);
            lockPhysicalCollision();
            AdventureRuntime.onBattleAdopted();
            try { AdventureRuntime.saveCheckpoint(); } catch (Throwable ignored) {}
            Logger.log("Adventure: battle adopted level=" + AdventureRuntime.levelIndex()
                    + " stage=" + ((StageBasis) sb).st);
        } catch (Throwable t) {
            Logger.err("Adventure: adoptBattle failed", t);
            active = null;
            CustomMapRuntime.release(null);
            unlockPhysicalCollision();
            AdventureRuntime.reset("adopt-error");
        }
    }

    public static void onForeignBattleConstructed() {
        if (active != null || AdventureRuntime.state() != AdventureRuntime.State.IDLE) {
            CustomMapRuntime.release(active == null ? null : active.sb);
            CustomMapRuntime.clearPending();
            active = null;
            unlockPhysicalCollision();
            AdventureRuntime.reset("normal battle started");
        }
    }

    public static void deactivate(String reason) {
        if (active != null || AdventureRuntime.state() != AdventureRuntime.State.IDLE) {
            CustomMapRuntime.release(active == null ? null : active.sb);
            active = null;
            unlockPhysicalCollision();
            AdventureRuntime.reset(reason);
        }
    }

    public static void beforeStageUpdate(Object stageBasis) {
        AdventureBattle b = active;
        if (b == null || b.sb != stageBasis) return;
        try {
            b.tick();
        } catch (Throwable t) {
            Logger.err("Adventure: tick failed - deactivating", t);
            deactivate("tick-error");
        }
    }

    public static void onEntityDamaged(Object victim, Object attack, long healthBefore,
                                       long pendingBefore) {
        AdventureBattle b = active;
        if (b == null) return;
        try {
            b.coreEffects.onEntityDamaged(victim, attack, healthBefore, pendingBefore);
            long pendingAfter = pendingBefore;
            try {
                pendingAfter = Math.max(0L,
                        manualcontrol.reflect.BCUFields.getLong(victim, "damage"));
            } catch (Throwable ignored) {}
            if (pendingAfter <= Math.max(0L, pendingBefore)) return;
            if (victim == b.player()) {
                b.controller.onDamagedWhileMoving(b.player());
            } else if (victim instanceof common.battle.entity.EEnemy
                    && b.ownsEntity(victim)) {
                b.enemyAI.onDamagedWhileMoving((common.battle.entity.EEnemy) victim);
            }
        } catch (Throwable ignored) {}
    }

    public static void afterStageUpdate(Object stageBasis) {
        AdventureBattle b = active;
        if (b == null || b.sb != stageBasis) return;
        try {
            b.afterTick();
        } catch (Throwable t) {
            Logger.err("Adventure: afterTick failed - deactivating", t);
            deactivate("after-tick-error");
        }
    }

    public static boolean blockAllSpawns(Object stageBasis) {
        AdventureBattle b = active;
        return b != null && b.sb == stageBasis;
    }

    public static boolean wantsKeys(Object keyHandler) {
        AdventureBattle b = active;
        return b != null && keyHandler != null && b.page() == keyHandler;
    }

    public static boolean handleKey(int code, boolean down) {
        AdventureBattle b = active;

        if (b != null && b.isPaused()) {
            AdventurePauseOverlay ov = b.pauseOverlay();
            if (down && ov != null) {
                switch (code) {
                    case java.awt.event.KeyEvent.VK_A:
                    case java.awt.event.KeyEvent.VK_W:
                        ov.move(-1); break;
                    case java.awt.event.KeyEvent.VK_D:
                    case java.awt.event.KeyEvent.VK_S:
                        ov.move(1); break;
                    case java.awt.event.KeyEvent.VK_J:
                        ov.confirm(); break;
                    case java.awt.event.KeyEvent.VK_P:
                    case java.awt.event.KeyEvent.VK_ESCAPE:
                        b.togglePause(); break;
                    default: break;
                }
            }
            return true;
        }

        if (b != null && down && code == java.awt.event.KeyEvent.VK_P) {
            b.togglePause();
            return true;
        }
        if (b != null && b.isChoosingCore()) {
            AdventureCoreOverlay ov = b.coreOverlay();
            if (down && ov != null) {
                if (code == java.awt.event.KeyEvent.VK_A) ov.move(-1);
                else if (code == java.awt.event.KeyEvent.VK_D) ov.move(1);
                else if (code == java.awt.event.KeyEvent.VK_J) ov.confirm();
            }

            switch (code) {
                case java.awt.event.KeyEvent.VK_A:
                case java.awt.event.KeyEvent.VK_D:
                case java.awt.event.KeyEvent.VK_J:
                case java.awt.event.KeyEvent.VK_K:
                case java.awt.event.KeyEvent.VK_L:
                case java.awt.event.KeyEvent.VK_W:
                case java.awt.event.KeyEvent.VK_Q:
                case java.awt.event.KeyEvent.VK_E:
                case java.awt.event.KeyEvent.VK_S:
                case java.awt.event.KeyEvent.VK_G:
                    return true;
                default:
                    return false;
            }
        }
        if (AdventureInput.onKey(code, down)) return true;
        switch (code) {
            case java.awt.event.KeyEvent.VK_Q:
            case java.awt.event.KeyEvent.VK_E:
            case java.awt.event.KeyEvent.VK_R:
            case java.awt.event.KeyEvent.VK_T:
            case java.awt.event.KeyEvent.VK_F:
            case java.awt.event.KeyEvent.VK_G:
            case java.awt.event.KeyEvent.VK_Z:
            case java.awt.event.KeyEvent.VK_X:
            case java.awt.event.KeyEvent.VK_C:
            case java.awt.event.KeyEvent.VK_V:
            case java.awt.event.KeyEvent.VK_B:
                return true;
            default:
                return false;
        }
    }

    public static boolean isPlayerEntity(Object entity) {
        AdventureBattle b = active;
        return b != null && b.player() == entity;
    }

    private static final java.util.Map<Object, Float> CENTERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Float>());
    private static final java.util.Map<Object, Boolean> FLIPPED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Boolean>());

    private static final java.util.Set<Object> CONJURED_SPIRITS =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Object, Boolean>()));

    private static final java.util.Map<Object, Integer> SHADOW_PART =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Integer>());

    static Integer shadowPart(Object entity) { return SHADOW_PART.get(entity); }

    static void setShadowPart(Object entity, int idx) {
        if (entity != null) SHADOW_PART.put(entity, idx);
    }

    private static volatile float playerSpriteTop = Float.NaN;

    public static boolean isAdventureFlipped(Object entity) {
        Boolean f = FLIPPED.get(entity);
        return f != null && f;
    }

    public static float mirrorCenter(Object entity) {
        Float c = CENTERS.get(entity);
        return c == null ? Float.NaN : c;
    }

    public static void setFlipped(Object entity, boolean flip) {
        if (entity != null) FLIPPED.put(entity, flip);
    }

    public static void registerConjuredSpirit(Object entity, boolean flip) {
        if (entity == null) return;
        CONJURED_SPIRITS.add(entity);
        FLIPPED.put(entity, flip);
    }

    public static boolean isConjuredSpirit(Object entity) {
        return entity != null && CONJURED_SPIRITS.contains(entity);
    }

    public static void setMirrorCenter(Object entity, float modelX) {
        if (entity != null && modelX == modelX && Math.abs(modelX) < 20000f) {
            CENTERS.put(entity, modelX);
        }
    }

    public static float playerSpriteTop() { return playerSpriteTop; }

    public static void setPlayerSpriteTop(float modelY) {
        if (modelY == modelY && Math.abs(modelY) < 20000f) playerSpriteTop = modelY;
    }

    public static boolean skipNativeUpdate(Object entity) {
        AdventureBattle b = active;
        if (b == null || !b.ownsEntity(entity)) return false;
        return AdventureController.canDrive(entity);
    }

    public static boolean skipNativeUpdateMove(Object entity) {
        AdventureBattle b = active;
        return b != null && b.ownsEntity(entity);
    }

    public static boolean onUpdateAnimation(Object entity) {
        AdventureBattle b = active;
        if (b == null || !b.ownsEntity(entity)) return false;
        if (!AdventureController.canDrive(entity)) return false;
        b.driveAnim(entity);
        return true;
    }

    public static void applyCamera(Object bbpainter, Object stageBasis) {
        AdventureBattle b = active;
        if (b == null || b.sb != stageBasis) return;
        try {
            b.camera.apply(bbpainter, b);
        } catch (Throwable ignored) {}
    }

    public static void drawWorld(Object bbpainter, common.system.fake.FakeGraphics g) {
        AdventureBattle b = active;
        if (b == null) return;
        try {
            if (bbpainter == null
                    || manualcontrol.reflect.BBPainterAccess.getStageBasis(bbpainter) != b.sb) return;
            b.architect.drawGround(bbpainter, g);
            b.coreVfx.drawGround(bbpainter, g);
            if (b.showDoor()) b.door().draw(bbpainter, g);
            b.afterimages.draw(bbpainter, g);
            b.teleport.draw(bbpainter, g);
            b.projectiles.draw(bbpainter, g);
            b.spawnFx.drawBursts(bbpainter, g);
        } catch (Throwable ignored) {}
    }

    public static float tauntXFor(Object enemy) {
        AdventureBattle b = active;
        if (b == null || !(enemy instanceof common.battle.entity.EEnemy)) return Float.NaN;
        try {
            return b.afterimages.tauntXFor((common.battle.entity.EEnemy) enemy);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    public static void drawWorldOverlay(Object bbpainter, common.system.fake.FakeGraphics g) {
        AdventureBattle b = active;
        if (b == null) return;
        try {
            if (bbpainter == null
                    || manualcontrol.reflect.BBPainterAccess.getStageBasis(bbpainter) != b.sb) return;
            b.coreVfx.drawWorldOverlay(bbpainter, g);
            b.spawnFx.drawWorldOverlay(bbpainter, g);
            b.spawnFx.drawLandingScreenOverlay(bbpainter, g);
        } catch (Throwable ignored) {}
    }

    public static void drawHud(Object bbpainter, common.system.fake.FakeGraphics g) {
        AdventureBattle b = active;
        if (b == null) return;
        try {
            if (bbpainter == null
                    || manualcontrol.reflect.BBPainterAccess.getStageBasis(bbpainter) != b.sb) return;
            b.hud.draw(g, bbpainter, b);
            b.spawnFx.drawOverlay(bbpainter, g);
            AdventureCoreOverlay ov = b.coreOverlay();
            if (ov != null) ov.draw(bbpainter, g);
            AdventurePauseOverlay pv = b.pauseOverlay();
            if (pv != null) pv.draw(bbpainter, g);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("rawtypes")
    public static void filterBases(List result, Object stageBasis) {
        AdventureBattle b = active;
        if (b == null || b.sb != stageBasis || result == null || result.isEmpty()) return;
        try {
            result.remove(b.sb.ubase);
            result.remove(b.sb.ebase);
        } catch (Throwable ignored) {}
    }
}
