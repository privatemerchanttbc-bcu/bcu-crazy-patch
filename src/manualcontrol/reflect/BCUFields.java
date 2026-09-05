package manualcontrol.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class BCUFields {

    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();

    private BCUFields() {}

    public static Object get(Object instance, String fieldName) {
        try {
            Field f = field(instance.getClass(), fieldName);
            return f.get(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read field " + fieldName + " on " + instance.getClass().getName(), e);
        }
    }

    public static int getInt(Object instance, String fieldName) {
        try {
            return field(instance.getClass(), fieldName).getInt(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read int field " + fieldName, e);
        }
    }

    public static long getLong(Object instance, String fieldName) {
        try {
            return field(instance.getClass(), fieldName).getLong(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read long field " + fieldName, e);
        }
    }

    public static float getFloat(Object instance, String fieldName) {
        try {
            return field(instance.getClass(), fieldName).getFloat(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read float field " + fieldName, e);
        }
    }

    public static double getDouble(Object instance, String fieldName) {
        try {
            return field(instance.getClass(), fieldName).getDouble(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read double field " + fieldName, e);
        }
    }

    public static boolean getBoolean(Object instance, String fieldName) {
        try {
            return field(instance.getClass(), fieldName).getBoolean(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read boolean field " + fieldName, e);
        }
    }

    public static void set(Object instance, String fieldName, Object value) {
        try {
            field(instance.getClass(), fieldName).set(instance, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName + " on " + instance.getClass().getName(), e);
        }
    }

    public static void setInt(Object instance, String fieldName, int value) {
        try {
            field(instance.getClass(), fieldName).setInt(instance, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set int field " + fieldName, e);
        }
    }

    public static void setDouble(Object instance, String fieldName, double value) {
        try {
            field(instance.getClass(), fieldName).setDouble(instance, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set double field " + fieldName, e);
        }
    }

    public static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        String key = cls.getName() + "#" + name;
        Field f = FIELD_CACHE.get(key);
        if (f != null) return f;

        Class<?> c = cls;
        while (c != null) {
            try {
                f = c.getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + cls.getName());
    }

    public static Object invoke(Object instance, String methodName) {
        try {
            Method m = method(instance.getClass(), methodName);
            return m.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke " + methodName, e);
        }
    }

    public static Method method(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        String key = cls.getName() + "#" + name + "(" + params.length + ")";
        Method m = METHOD_CACHE.get(key);
        if (m != null) return m;
        Class<?> c = cls;
        while (c != null) {
            try {
                m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                METHOD_CACHE.put(key, m);
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name + " on " + cls.getName());
    }
}
