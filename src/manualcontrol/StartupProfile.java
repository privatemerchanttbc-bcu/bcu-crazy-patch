package manualcontrol;

public final class StartupProfile {

    private static volatile long agentMs;
    private static volatile long featureSelectMs;
    private static volatile long transformersMs;
    private static volatile boolean reported;

    private StartupProfile() {}

    public static void agentStarted() {
        agentMs = System.currentTimeMillis();
    }

    public static void featureSelectDone() {
        featureSelectMs = System.currentTimeMillis();
    }

    public static void transformersRegistered() {
        transformersMs = System.currentTimeMillis();
    }

    public static void mainPageBuilt() {
        if (reported) return;
        reported = true;
        long now = System.currentTimeMillis();
        if (agentMs <= 0L) return;
        long select = featureSelectMs > 0L ? featureSelectMs - agentMs : 0L;
        long transform = featureSelectMs > 0L && transformersMs > 0L
                ? transformersMs - featureSelectMs : 0L;
        long bcu = transformersMs > 0L ? now - transformersMs : now - agentMs;
        Logger.log("STARTUP SUMMARY: featureSelect=" + select
                + "ms transformers=" + transform
                + "ms bcuLoadToMainPage=" + bcu
                + "ms total=" + (now - agentMs) + "ms");
        try {
            manualcontrol.crazy.CrazyPreloadService.preloadAfterStartup("main-page");
        } catch (Throwable t) {
            Logger.err("Crazy preload could not start after the main page", t);
        }
    }
}
