package manualcontrol.hooks;

import common.system.fake.FakeImage;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;

public final class BoundsRecorder implements InvocationHandler {

    private final FakeGraphics target;
    private float minX, minY, maxX, maxY;
    private boolean found;

    private float largestArea;
    private float largestCX, largestCY;
    private boolean hasLargest;
    private final List<SpritePart> parts = new ArrayList<SpritePart>();

    private static volatile Field gltDataField;

    private BoundsRecorder(FakeGraphics target) {
        this.target = target;
    }

    public static BoundsRecorder begin(FakeGraphics target) {
        return new BoundsRecorder(target);
    }

    public FakeGraphics proxy() {
        return (FakeGraphics) Proxy.newProxyInstance(
                FakeGraphics.class.getClassLoader(),
                new Class<?>[]{FakeGraphics.class},
                this);
    }

    public boolean hasBox() { return found; }
    public float minX() { return minX; }
    public float minY() { return minY; }
    public float maxX() { return maxX; }
    public float maxY() { return maxY; }

    public boolean hasLargest() { return hasLargest; }

    public float bodyCX() { return hasLargest ? largestCX : (minX + maxX) * 0.5f; }
    public float bodyCY() { return hasLargest ? largestCY : (minY + maxY) * 0.5f; }
    public List<SpritePart> parts() { return new ArrayList<SpritePart>(parts); }

    public static final class SpritePart {
        public final FakeImage image;
        public final float x;
        public final float y;
        public final float w;
        public final float h;
        public final float[] matrix;

        SpritePart(FakeImage image, float x, float y, float w, float h, float[] matrix) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.matrix = matrix == null ? null : matrix.clone();
        }
    }

    @Override
    public Object invoke(Object proxyObj, Method method, Object[] args) throws Throwable {
        if ("drawImage".equals(method.getName()) && args != null) {
            try {
                recordDrawImage(args);
            } catch (Throwable ignored) {

            }
        }
        return method.invoke(target, args);
    }

    private void recordDrawImage(Object[] args) throws Throwable {
        float x, y, w, h;
        FakeImage image = args[0] instanceof FakeImage ? (FakeImage) args[0] : null;
        if (args.length >= 5) {
            x = ((Number) args[1]).floatValue();
            y = ((Number) args[2]).floatValue();
            w = ((Number) args[3]).floatValue();
            h = ((Number) args[4]).floatValue();
        } else if (args.length >= 3) {
            x = ((Number) args[1]).floatValue();
            y = ((Number) args[2]).floatValue();
            float[] wh = imageSize(args[0]);
            w = wh[0];
            h = wh[1];
        } else {
            return;
        }

        float[] m = currentMatrix();
        if (m == null) return;
        if (image != null && w > 0f && h > 0f) {
            parts.add(new SpritePart(image, x, y, w, h, m));
        }

        float[] cx = new float[4];
        float[] cy = new float[4];
        cx[0] = projectX(m, x, y);            cy[0] = projectY(m, x, y);
        cx[1] = projectX(m, x + w, y);        cy[1] = projectY(m, x + w, y);
        cx[2] = projectX(m, x + w, y + h);    cy[2] = projectY(m, x + w, y + h);
        cx[3] = projectX(m, x, y + h);        cy[3] = projectY(m, x, y + h);

        float pMinX = cx[0], pMaxX = cx[0], pMinY = cy[0], pMaxY = cy[0];
        for (int i = 1; i < 4; i++) {
            if (cx[i] < pMinX) pMinX = cx[i];
            if (cx[i] > pMaxX) pMaxX = cx[i];
            if (cy[i] < pMinY) pMinY = cy[i];
            if (cy[i] > pMaxY) pMaxY = cy[i];
        }

        if (!found) {
            minX = pMinX; maxX = pMaxX; minY = pMinY; maxY = pMaxY;
            found = true;
        } else {
            if (pMinX < minX) minX = pMinX;
            if (pMaxX > maxX) maxX = pMaxX;
            if (pMinY < minY) minY = pMinY;
            if (pMaxY > maxY) maxY = pMaxY;
        }

        float area = (pMaxX - pMinX) * (pMaxY - pMinY);
        if (area > largestArea) {
            largestArea = area;
            largestCX = (pMinX + pMaxX) * 0.5f;
            largestCY = (pMinY + pMaxY) * 0.5f;
            hasLargest = true;
        }
    }

    private static float projectX(float[] m, float lx, float ly) {
        return m[0] * lx + m[1] * ly + m[2];
    }

    private static float projectY(float[] m, float lx, float ly) {
        return m[3] * lx + m[4] * ly + m[5];
    }

    private float[] currentMatrix() {
        FakeTransform ft = null;
        try {
            ft = target.getTransform();
            if (ft == null) return null;
            Field f = gltDataField;
            if (f == null || f.getDeclaringClass() != ft.getClass()) {
                f = ft.getClass().getDeclaredField("data");
                f.setAccessible(true);
                gltDataField = f;
            }
            Object data = f.get(ft);
            if (data instanceof float[]) {
                float[] src = (float[]) data;
                if (src.length >= 6) {
                    return new float[]{src[0], src[1], src[2], src[3], src[4], src[5]};
                }
            }
            return null;
        } catch (Throwable t) {

            try {
                if (ft != null) {
                    Object at = ft.getClass().getMethod("getAT").invoke(ft);
                    if (at instanceof java.awt.geom.AffineTransform) {
                        java.awt.geom.AffineTransform a = (java.awt.geom.AffineTransform) at;
                        return new float[]{
                                (float) a.getScaleX(), (float) a.getShearX(), (float) a.getTranslateX(),
                                (float) a.getShearY(), (float) a.getScaleY(), (float) a.getTranslateY()};
                    }
                }
            } catch (Throwable ignored) {}
            return null;
        } finally {
            if (ft != null) {
                try { target.delete(ft); } catch (Throwable ignored) {}
            }
        }
    }

    private static float[] imageSize(Object img) {
        try {
            float w = ((Number) img.getClass().getMethod("getWidth").invoke(img)).floatValue();
            float h = ((Number) img.getClass().getMethod("getHeight").invoke(img)).floatValue();
            return new float[]{w, h};
        } catch (Throwable t) {
            return new float[]{0f, 0f};
        }
    }
}
