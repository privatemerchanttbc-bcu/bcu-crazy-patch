package manualcontrol.crazy.collision;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;

import java.lang.reflect.Field;

public final class CollisionHud {

    public static volatile float X = 14f;
    public static volatile float Y = 70f;
    private static final float W = 64f;
    private static final float H = 40f;

    private static final long FLASH_MS = 1500L;

    private static volatile long lastToggleMs = 0L;

    private static volatile Field transformDataField;

    private CollisionHud() {}

    public static void noteToggle() {
        lastToggleMs = System.currentTimeMillis();
    }

    public static void draw(FakeGraphics g) {
        if (g == null) return;
        boolean on = PhysicalCollision.ENABLED;
        long now = System.currentTimeMillis();
        long since = lastToggleMs == 0L ? Long.MAX_VALUE : now - lastToggleMs;
        boolean flashing = since < FLASH_MS;
        if (!on && !flashing) return;

        FakeTransform old = pushIdentityTransform(g);
        try {
            float x = X, y = Y;

            g.colRect(x, y, W, H, 10, 12, 18, on ? 175 : 150);
            if (on) g.setColor(80, 255, 120);
            else g.setColor(150, 150, 150);
            g.drawRect(x, y, W, H);

            g.colRect(x + 8f, y + 9f, 24f, 20f, 0, 220, 255, on ? 120 : 55);
            g.setColor(0, 220, 255);
            g.drawRect(x + 8f, y + 9f, 24f, 20f);
            g.colRect(x + 24f, y + 15f, 24f, 18f, 255, 70, 70, on ? 120 : 55);
            g.setColor(255, 80, 80);
            g.drawRect(x + 24f, y + 15f, 24f, 18f);

            if (on) {

                g.colRect(x + W - 13f, y + 4f, 9f, 9f, 60, 255, 90, 255);
            } else {

                g.setColor(255, 70, 70);
                g.drawLine(x + 4f, y + H - 4f, x + W - 4f, y + 4f);
                g.drawLine(x + 5f, y + H - 3f, x + W - 3f, y + 5f);
            }

            if (flashing && ((since / 120L) & 1L) == 0L) {
                if (on) g.setColor(80, 255, 120);
                else g.setColor(255, 80, 80);
                g.drawRect(x - 3f, y - 3f, W + 6f, H + 6f);
                g.drawRect(x - 4f, y - 4f, W + 8f, H + 8f);
            }
        } catch (Throwable ignored) {

        } finally {
            popTransform(g, old);
        }
    }

    static FakeTransform pushIdentityTransform(FakeGraphics gra) {
        try {
            FakeTransform oldTransform = gra.getTransform();
            FakeTransform identity = gra.getTransform();
            Field f = transformDataField;
            if (f == null || f.getDeclaringClass() != identity.getClass()) {
                f = identity.getClass().getDeclaredField("data");
                f.setAccessible(true);
                transformDataField = f;
            }
            f.set(identity, new float[]{1f, 0f, 0f, 0f, 1f, 0f});
            gra.setTransform(identity);
            try { gra.delete(identity); } catch (Throwable ignored) {}
            return oldTransform;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void popTransform(FakeGraphics gra, FakeTransform oldTransform) {
        if (oldTransform == null) return;
        try {
            gra.setTransform(oldTransform);
        } catch (Throwable ignored) {
        } finally {
            try { gra.delete(oldTransform); } catch (Throwable ignored) {}
        }
    }
}
