package manualcontrol;

public final class ControlMarker {

    public static final long FADE_OUT_MS = 1000L;

    private static volatile Object entity;
    private static volatile long fadeOutStartMs;

    private ControlMarker() {}

    public static void setActive(Object e) {
        if (e == null) return;
        entity = e;
        fadeOutStartMs = 0L;
    }

    public static void beginFadeOut() {
        if (entity != null && fadeOutStartMs == 0L) {
            fadeOutStartMs = System.currentTimeMillis();
        }
    }

    public static void clear() {
        entity = null;
        fadeOutStartMs = 0L;
    }

    public static Object getEntity() {
        if (entity == null) return null;
        if (fadeOutStartMs != 0L
                && System.currentTimeMillis() - fadeOutStartMs >= FADE_OUT_MS) {
            clear();
            return null;
        }
        return entity;
    }

    public static boolean tracks(Object e) {
        return e != null && e == entity;
    }

    public static float opacity() {
        if (entity == null) return 0f;
        if (fadeOutStartMs == 0L) return 1f;
        float t = (System.currentTimeMillis() - fadeOutStartMs) / (float) FADE_OUT_MS;
        if (t >= 1f) { clear(); return 0f; }
        return 1f - t;
    }
}
