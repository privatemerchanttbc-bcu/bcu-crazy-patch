package manualcontrol.adventure;

import java.awt.event.KeyEvent;

public final class AdventureInput {

    private static volatile boolean keyA;
    private static volatile boolean keyD;
    private static volatile boolean keyW;
    private static volatile boolean keyQ;
    private static volatile boolean keyE;
    private static volatile boolean keyS;
    private static volatile boolean keyK;
    private static volatile boolean jumpQueued;
    private static volatile boolean attackQueued;
    private static volatile boolean conjureQueued;
    private static volatile boolean teleportQueued;
    private static volatile int architectCycleQueued;
    private static volatile boolean architectBuildQueued;

    private AdventureInput() {}

    public static boolean left() { return keyA; }
    public static boolean right() { return keyD; }
    public static boolean jumpHeld() { return keyK; }

    public static synchronized boolean consumeJump() {
        boolean v = jumpQueued;
        jumpQueued = false;
        return v;
    }

    public static synchronized boolean consumeAttack() {
        boolean v = attackQueued;
        attackQueued = false;
        return v;
    }

    public static synchronized boolean consumeConjure() {
        boolean v = conjureQueued;
        conjureQueued = false;
        return v;
    }

    public static synchronized boolean hasTeleportQueued() {
        return teleportQueued;
    }

    public static synchronized boolean consumeTeleport() {
        boolean v = teleportQueued;
        teleportQueued = false;
        return v;
    }

    public static synchronized void clearTeleport() {
        teleportQueued = false;
    }

    public static synchronized int consumeArchitectCycle() {
        int v = architectCycleQueued;
        architectCycleQueued = 0;
        return v;
    }

    public static synchronized boolean consumeArchitectBuild() {
        boolean v = architectBuildQueued;
        architectBuildQueued = false;
        return v;
    }

    public static synchronized void clearQueuedActions() {
        jumpQueued = false;
        attackQueued = false;
        conjureQueued = false;
        teleportQueued = false;
        architectCycleQueued = 0;
        architectBuildQueued = false;
    }

    public static synchronized boolean onKey(int code, boolean down) {
        switch (code) {
            case KeyEvent.VK_A: keyA = down; return true;
            case KeyEvent.VK_D: keyD = down; return true;
            case KeyEvent.VK_J: if (down) attackQueued = true; return true;
            case KeyEvent.VK_K:
                if (down && !keyK) jumpQueued = true;
                keyK = down;
                return true;
            case KeyEvent.VK_L: if (down) conjureQueued = true; return true;
            case KeyEvent.VK_W:
                if (down && !keyW) teleportQueued = true;
                keyW = down;
                return true;
            case KeyEvent.VK_Q:
                if (down && !keyQ) architectCycleQueued = -1;
                keyQ = down;
                return true;
            case KeyEvent.VK_E:
                if (down && !keyE) architectCycleQueued = 1;
                keyE = down;
                return true;
            case KeyEvent.VK_S:
                if (down && !keyS) architectBuildQueued = true;
                keyS = down;
                return true;
            default:
                return false;
        }
    }

    public static synchronized void reset() {
        keyA = false;
        keyD = false;
        keyW = false;
        keyQ = false;
        keyE = false;
        keyS = false;
        keyK = false;
        clearQueuedActions();
    }
}
