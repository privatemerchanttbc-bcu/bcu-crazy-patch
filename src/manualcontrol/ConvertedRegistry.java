package manualcontrol;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class ConvertedRegistry {

    private static final Set<Object> CONVERTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private ConvertedRegistry() {}

    public static void mark(Object entity) {
        if (entity == null) return;
        CONVERTED.add(entity);
    }

    public static boolean isConverted(Object entity) {
        if (entity == null) return false;
        return CONVERTED.contains(entity);
    }

    public static void clear() {
        CONVERTED.clear();
    }
}
