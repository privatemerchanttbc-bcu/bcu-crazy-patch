package manualcontrol;

import manualcontrol.reflect.BBPainterAccess;
import manualcontrol.reflect.EntityAccess;
import java.util.List;

public final class EntitySelector {

    private static final float HIT_PADDING_PX = 4.0f;

    private EntitySelector() {}

    public static float entityXtoScreenX(float entityX, float siz, int stagePos) {
        return (entityX * 0.32f + 200.0f) * siz + stagePos;
    }

    public static float entityYatLayer(int layer, int midh, float siz) {
        int dep = layer * 4;
        return midh - (156 - dep) * siz;
    }

    public static Object findUnderCursor(Object bbpainter, int mouseX, int mouseY) {
        List<Object> entities = BBPainterAccess.getEntityList(bbpainter);
        if (entities == null || entities.isEmpty()) return null;

        float siz = BBPainterAccess.getSiz(bbpainter);
        int stagePos = BBPainterAccess.getStagePos(bbpainter);
        int midh = BBPainterAccess.getMidh(bbpainter);

        Object best = null;
        float bestD2 = Float.MAX_VALUE;

        for (Object e : entities) {
            if (e == null) continue;
            if (EntityAccess.isDead(e)) continue;
            if (EntityAccess.isBase(e)) continue;

            if (EntityAccess.isBoss(e)) continue;
            if (FallingRegistry.isManaged(e)) continue;

            float ex = entityXtoScreenX(EntityAccess.getPos(e), siz, stagePos);
            float ey = entityYatLayer(EntityAccess.getLayer(e), midh, siz);

            EntityAccess.SpriteBounds b = EntityAccess.estimateSpriteBounds(e, siz, ex, ey);
            if (b == null || !b.contains(mouseX, mouseY, HIT_PADDING_PX)) continue;

            float dx = b.centerX - mouseX;
            float dy = b.centerY - mouseY;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = e;
            }
        }
        return best;
    }
}
