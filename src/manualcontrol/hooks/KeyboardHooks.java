package manualcontrol.hooks;

import manualcontrol.OptionalModes;
import manualcontrol.HoldState;
import manualcontrol.Logger;
import manualcontrol.crazy.CrazyRuntime;
import manualcontrol.reflect.EntityAccess;

import java.awt.event.KeyEvent;

public final class KeyboardHooks {

    private KeyboardHooks() {}

    private static final int[] DEPLOY_KEYS = new int[]{
            KeyEvent.VK_Q, KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_T,
            KeyEvent.VK_S, KeyEvent.VK_F,
            KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_B
    };

    public static final int RELEASE_KEY = KeyEvent.VK_G;

    public static boolean onKeyPressed(Object keyHandler, KeyEvent e) {
        if (e == null) return false;
        try {
            HoldState state = HoldState.get();
            int code = e.getKeyCode();
            boolean slingshotActive = CrazyRuntime.isSlingshotActiveFor(keyHandler);

            if (code == KeyEvent.VK_F7) {
                if (manualcontrol.adventure.AdventureBridge.isPhysicalCollisionLocked()) {
                    manualcontrol.crazy.collision.PhysicalCollision.ENABLED = true;
                    Logger.log("Physical collision stays ON (locked by the current mode)");
                    return true;
                }
                boolean physicsOn = !manualcontrol.crazy.collision.PhysicalCollision.ENABLED;
                manualcontrol.crazy.collision.PhysicalCollision.ENABLED = physicsOn;
                manualcontrol.crazy.collision.CollisionHud.noteToggle();
                Logger.log("Physical collision " + (physicsOn ? "ON" : "off"));
                return true;
            }
            if (code == KeyEvent.VK_F8) {
                boolean on = !manualcontrol.crazy.collision.CollisionDebug.OVERLAY;
                manualcontrol.crazy.collision.CollisionDebug.OVERLAY = on;
                Logger.log("Collision debug overlay " + (on ? "ON" : "off"));
                return true;
            }

            if (manualcontrol.adventure.AdventureBridge.wantsKeys(keyHandler)) {
                return manualcontrol.adventure.AdventureBridge.handleKey(code, true);
            }

            if (OptionalModes.wantsKeys(keyHandler)) {
                return OptionalModes.handleKey(code, true);
            }

            if (code == RELEASE_KEY) {
                Logger.log("=== G key pressed === currentState=" + state.getPhase()
                        + " isInControl=" + state.isInControl()
                        + " handlerClass=" + (keyHandler == null ? "null" : keyHandler.getClass().getSimpleName()));
                if (state.isInControl()) {
                    state.release();
                    Logger.log("G handled: released. New state=" + state.getPhase());
                    return true;
                }

                return slingshotActive;
            }

            if (slingshotActive) {
                for (int dk : DEPLOY_KEYS) {
                    if (dk == code) return true;
                }
                return false;
            }

            if (!state.isInControl()) return false;

            if (code == KeyEvent.VK_A || code == KeyEvent.VK_D
                    || code == KeyEvent.VK_J || code == KeyEvent.VK_K) {
                state.setKey(code, true);
                return true;
            }

            for (int dk : DEPLOY_KEYS) {
                if (dk == code) return true;
            }
            return false;
        } catch (Throwable t) {
            Logger.err("onKeyPressed error", t);
            return false;
        }
    }

    public static boolean onKeyReleased(Object keyHandler, KeyEvent e) {
        if (e == null) return false;
        try {
            if (manualcontrol.adventure.AdventureBridge.wantsKeys(keyHandler)) {
                return manualcontrol.adventure.AdventureBridge.handleKey(e.getKeyCode(), false);
            }
            if (OptionalModes.wantsKeys(keyHandler)) {
                return OptionalModes.handleKey(e.getKeyCode(), false);
            }
            HoldState state = HoldState.get();
            boolean slingshotActive = CrazyRuntime.isSlingshotActiveFor(keyHandler);
            if (!state.isInControl() && slingshotActive) {
                int code = e.getKeyCode();
                if (code == RELEASE_KEY) return true;
                for (int dk : DEPLOY_KEYS) {
                    if (dk == code) return true;
                }
                return false;
            }
            if (!state.isInControl()) return false;

            int code = e.getKeyCode();

            if (code == KeyEvent.VK_A || code == KeyEvent.VK_D
                    || code == KeyEvent.VK_J || code == KeyEvent.VK_K) {
                state.setKey(code, false);
                return true;
            }

            for (int dk : DEPLOY_KEYS) {
                if (dk == code) return true;
            }
            return false;
        } catch (Throwable t) {
            Logger.err("onKeyReleased error", t);
            return false;
        }
    }
}
