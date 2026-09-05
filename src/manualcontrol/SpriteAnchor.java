package manualcontrol;

public final class SpriteAnchor {

    private static volatile Object lastEntity;
    private static volatile float lastX;
    private static volatile float lastY;
    private static volatile float lastSiz;
    private static volatile long lastUpdateMs;

    private static volatile Object boxEntity;
    private static volatile float boxMinX, boxMinY, boxMaxX, boxMaxY;
    private static volatile float boxBodyCX, boxBodyCY;
    private static volatile long boxUpdateMs;

    private static volatile Object liveEntity;
    private static volatile float liveMinX, liveMinY, liveMaxX, liveMaxY;
    private static volatile float liveBodyCX, liveBodyCY;
    private static volatile long liveUpdateMs;

    private static final long MAX_AGE_MS = 80L;

    private SpriteAnchor() {}

    public static void recordBox(Object entity, float minX, float minY, float maxX, float maxY,
                                 float bodyCX, float bodyCY) {
        if (entity == null) return;
        boxEntity = entity;
        boxMinX = minX;
        boxMinY = minY;
        boxMaxX = maxX;
        boxMaxY = maxY;
        boxBodyCX = bodyCX;
        boxBodyCY = bodyCY;
        boxUpdateMs = System.currentTimeMillis();
    }

    public static boolean hasFreshBox(Object entity) {
        if (entity == null || entity != boxEntity) return false;
        if (System.currentTimeMillis() - boxUpdateMs > MAX_AGE_MS) return false;
        float w = boxMaxX - boxMinX;
        float h = boxMaxY - boxMinY;
        return w > 2f && h > 2f && w < 6000f && h < 6000f;
    }

    public static float getBoxMinX() { return boxMinX; }
    public static float getBoxMinY() { return boxMinY; }
    public static float getBoxMaxX() { return boxMaxX; }
    public static float getBoxMaxY() { return boxMaxY; }
    public static float getBoxBodyCX() { return boxBodyCX; }
    public static float getBoxBodyCY() { return boxBodyCY; }

    public static void recordLiveBox(Object entity, float minX, float minY, float maxX, float maxY,
                                     float bodyCX, float bodyCY) {
        if (entity == null) return;
        liveEntity = entity;
        liveMinX = minX;
        liveMinY = minY;
        liveMaxX = maxX;
        liveMaxY = maxY;
        liveBodyCX = bodyCX;
        liveBodyCY = bodyCY;
        liveUpdateMs = System.currentTimeMillis();
    }

    public static boolean hasFreshLiveBox(Object entity) {
        if (entity == null || entity != liveEntity) return false;
        if (System.currentTimeMillis() - liveUpdateMs > MAX_AGE_MS) return false;
        float w = liveMaxX - liveMinX;
        float h = liveMaxY - liveMinY;
        return w > 2f && h > 2f && w < 6000f && h < 6000f;
    }

    public static float getLiveMinY() { return liveMinY; }
    public static float getLiveMaxY() { return liveMaxY; }
    public static float getLiveBodyCX() { return liveBodyCX; }
    public static float getLiveBodyCY() { return liveBodyCY; }

    public static void record(Object entity, float screenX, float screenY, float siz) {
        if (entity == null) return;
        lastEntity = entity;
        lastX = screenX;
        lastY = screenY;
        lastSiz = siz;
        lastUpdateMs = System.currentTimeMillis();
    }

    public static boolean hasFreshAnchor(Object entity) {
        if (entity == null || entity != lastEntity) return false;
        return (System.currentTimeMillis() - lastUpdateMs) <= MAX_AGE_MS;
    }

    public static float getX() { return lastX; }
    public static float getY() { return lastY; }
    public static float getSiz() { return lastSiz; }

    public static void clear() {
        lastEntity = null;
        lastUpdateMs = 0L;
        boxEntity = null;
        boxUpdateMs = 0L;
    }
}
