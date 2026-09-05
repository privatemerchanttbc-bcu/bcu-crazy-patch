package manualcontrol;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class OptionalFeatures {

    public static final Class<?>[] NO_SIGNATURE = new Class<?>[0];
    public static final Object[] NO_ARGS = new Object[0];

    private OptionalFeatures() {}

    public static Class<?> load(String className) {
        try {
            return Class.forName(className, false, OptionalFeatures.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean present(String className) {
        return load(className) != null;
    }

    public static boolean register(Instrumentation inst, String className, boolean canRetransform) {
        Class<?> type = load(className);
        if (type == null) {
            Logger.log("optional transformer " + className + ": absent");
            return false;
        }
        try {
            inst.addTransformer((ClassFileTransformer) type.newInstance(), canRetransform);
            Logger.log("optional transformer " + className + ": ok");
            return true;
        } catch (Throwable error) {
            Logger.err("optional transformer " + className + ": failed", error);
            return false;
        }
    }

    public static Object call(String className, String method, Class<?>[] signature, Object[] args) {
        Class<?> type = load(className);
        if (type == null) return null;
        try {
            Method target = type.getMethod(method, signature);
            return target.invoke(null, args);
        } catch (Throwable error) {
            Logger.err("optional call " + className + "." + method + ": failed", error);
            return null;
        }
    }

    public static Call bind(String className, String methodName, Class<?>... signature) {
        return new Call(className, methodName, signature);
    }

    public static final class Call {

        private final String className;
        private final String methodName;
        private final Class<?>[] signature;
        private volatile Method method;
        private volatile boolean resolved;

        private Call(String className, String methodName, Class<?>[] signature) {
            this.className = className;
            this.methodName = methodName;
            this.signature = signature;
        }

        private Method target() {
            if (resolved) return method;
            synchronized (this) {
                if (!resolved) {
                    Class<?> type = load(className);
                    if (type != null) {
                        try {
                            method = type.getMethod(methodName, signature);
                        } catch (Throwable error) {
                            Logger.err("optional call " + className + "." + methodName + ": missing", error);
                        }
                    }
                    resolved = true;
                }
            }
            return method;
        }

        public boolean present() {
            return target() != null;
        }

        public Object invoke(Object... args) {
            Method found = target();
            if (found == null) return null;
            try {
                return found.invoke(null, args);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException(cause);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }

        public boolean invokeBoolean(boolean fallback, Object... args) {
            Object value = invoke(args);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        public int invokeInt(int fallback, Object... args) {
            Object value = invoke(args);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        public float invokeFloat(float fallback, Object... args) {
            Object value = invoke(args);
            return value instanceof Number ? ((Number) value).floatValue() : fallback;
        }
    }

    public static String describe(String className, String method) {
        Object value = call(className, method, NO_SIGNATURE, NO_ARGS);
        return value == null ? "ABSENT" : String.valueOf(value);
    }
}
