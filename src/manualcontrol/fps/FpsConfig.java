package manualcontrol.fps;

import java.util.prefs.Preferences;

public final class FpsConfig {

    private static final Preferences PREFS =
            Preferences.userRoot().node("manualcontrol/bcu-crazy-patch");
    private static final String KEY = "battleFps";

    public static final int[] CHOICES = {30, 60, 120};

    private static volatile int targetFps = clampChoice(PREFS.getInt(KEY, 60));

    private FpsConfig() {}

    public static int targetFps() { return targetFps; }

    public static int subFrames() { return Math.max(1, targetFps / 30); }

    public static int frameIntervalMs() {
        switch (targetFps) {
            case 120: return 8;
            case 60:  return 16;
            default:  return 33;
        }
    }

    public static boolean active() { return targetFps > 30; }

    public static synchronized void setTargetFps(int fps) {
        targetFps = clampChoice(fps);
        PREFS.putInt(KEY, targetFps);
        FpsHooks.apply();
    }

    public static synchronized void setTargetFpsTransient(int fps) {
        targetFps = clampChoice(fps);
        FpsHooks.apply();
    }

    private static int clampChoice(int fps) {
        for (int c : CHOICES) if (c == fps) return fps;
        return 30;
    }
}
