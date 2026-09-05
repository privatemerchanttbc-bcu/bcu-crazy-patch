package manualcontrol.reflect;

import java.util.List;

public final class BBPainterAccess {

    private BBPainterAccess() {}

    public static Object getStageBasis(Object bbpainter) {
        return BCUFields.get(bbpainter, "sb");
    }

    public static Object getBattleField(Object bbpainter) {
        return BCUFields.get(bbpainter, "bf");
    }

    public static int getMidh(Object bbpainter) {
        return BCUFields.getInt(bbpainter, "midh");
    }

    public static void setMidh(Object bbpainter, int value) {
        BCUFields.setInt(bbpainter, "midh", value);
    }

    public static float getSiz(Object bbpainter) {
        Object bf = getBattleField(bbpainter);
        Object sb = BCUFields.get(bf, "sb");
        return BCUFields.getFloat(sb, "siz");
    }

    public static void setSiz(Object bbpainter, float value) {
        Object bf = getBattleField(bbpainter);
        Object sb = BCUFields.get(bf, "sb");
        try {
            BCUFields.field(sb.getClass(), "siz").setFloat(sb, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set battle zoom", e);
        }
    }

    public static int getStagePos(Object bbpainter) {
        Object sb = getStageBasis(bbpainter);
        return BCUFields.getInt(sb, "pos");
    }

    public static int getMaxW(Object bbpainter) {
        return BCUFields.getInt(bbpainter, "maxW");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getEntityList(Object bbpainter) {
        Object sb = getStageBasis(bbpainter);
        return (List<Object>) BCUFields.get(sb, "le");
    }

    public static Object getPlayerBase(Object bbpainter) {
        Object sb = getStageBasis(bbpainter);
        return BCUFields.get(sb, "ubase");
    }

    public static Object getEnemyBase(Object bbpainter) {
        Object sb = getStageBasis(bbpainter);
        return BCUFields.get(sb, "ebase");
    }

    public static int getWidth(Object bbpainter) {
        Object box = BCUFields.get(bbpainter, "box");
        return (int) BCUFields.invoke(box, "getWidth");
    }

    public static int getHeight(Object bbpainter) {
        Object box = BCUFields.get(bbpainter, "box");
        return (int) BCUFields.invoke(box, "getHeight");
    }
}
