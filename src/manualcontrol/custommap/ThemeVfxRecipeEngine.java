package manualcontrol.custommap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class ThemeVfxRecipeEngine {

    public static final int ENGINE_VERSION = 1;

    private ThemeVfxRecipeEngine() {}

    public interface AssetResolver {
        boolean hasSprite(String assetKind);
    }

    public interface Output {
        void drawSprite(String layer, String assetKind, float x, float y,
                        float scale, float alpha);
        void drawRectangle(String layer, int argb, float x, float y,
                           float width, float height, float alpha);
        void playSound(int soundId);
    }

    public static final class StyleKit {
        public final boolean valid;
        public final String reason;
        public final String profileId;
        public final int revision;
        public final int totalCap;
        public final int eventCap;
        public final int ambientCap;
        public final String hash;
        private final Map<String, CustomMapDocument.ThemeVfxRecipe> recipes;

        private StyleKit(boolean valid, String reason, String profileId,
                         int revision, int totalCap, int eventCap,
                         int ambientCap, String hash,
                         Map<String, CustomMapDocument.ThemeVfxRecipe> recipes) {
            this.valid = valid;
            this.reason = reason;
            this.profileId = profileId;
            this.revision = revision;
            this.totalCap = totalCap;
            this.eventCap = eventCap;
            this.ambientCap = ambientCap;
            this.hash = hash;
            this.recipes = recipes;
        }

        public CustomMapDocument.ThemeVfxRecipe recipe(String eventKey) {
            if (eventKey == null) return null;
            return recipes.get(eventKey.trim().toLowerCase(Locale.ROOT));
        }

        public int recipeCount() {
            return recipes.size();
        }
    }

    public static StyleKit compile(CustomMapDocument.ThemeVfxProfile source) {
        CustomMapDocument.ThemeVfxProfile profile =
                CustomMapDocument.ThemeVfxProfile.normalized(source);
        if (source == null || profile.styleKitVersion == 0)
            return invalid("missing-style-kit", profile);
        String rawHash = source.styleKitHash == null ? ""
                : source.styleKitHash.trim().toLowerCase(Locale.ROOT);
        if (!rawHash.isEmpty() && !rawHash.matches("[0-9a-f]{64}"))
            return invalid("malformed-style-kit-hash", profile);
        if (profile.styleKitVersion != 1)
            return invalid("unsupported-style-kit-version", profile);
        if (profile.engineMinVersion > ENGINE_VERSION)
            return invalid("engine-incompatible", profile);
        if (profile.recipes.isEmpty())
            return invalid("missing-recipes", profile);
        String computed = canonicalHash(profile);
        if (!profile.styleKitHash.isEmpty()
                && !profile.styleKitHash.equals(computed))
            return invalid("style-kit-hash-mismatch", profile);
        return new StyleKit(true, "", profile.profileId,
                profile.recipeRevision, profile.totalCap, profile.eventCap,
                profile.ambientCap, computed,
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, CustomMapDocument.ThemeVfxRecipe>(
                                profile.recipes)));
    }

    private static StyleKit invalid(String reason,
                                    CustomMapDocument.ThemeVfxProfile profile) {
        return new StyleKit(false, reason,
                profile == null ? "" : profile.profileId,
                profile == null ? 0 : profile.recipeRevision,
                profile == null ? 96 : profile.totalCap,
                profile == null ? 96 : profile.eventCap,
                profile == null ? 0 : profile.ambientCap,
                "", Collections.<String, CustomMapDocument.ThemeVfxRecipe>emptyMap());
    }

    public static String cacheKey(String mapUuid,
                                  CustomMapDocument.ThemeVfxProfile profile) {
        StyleKit kit = compile(profile);
        String uuid = mapUuid == null ? "" : mapUuid.trim();
        return uuid + "|" + kit.profileId + "|" + kit.revision + "|"
                + (kit.valid ? kit.hash : "legacy");
    }

    public static String canonicalHash(CustomMapDocument.ThemeVfxProfile source) {
        CustomMapDocument.ThemeVfxProfile profile =
                CustomMapDocument.ThemeVfxProfile.normalized(source);
        StringBuilder out = new StringBuilder(1024);
        append(out, "styleKitVersion", profile.styleKitVersion);
        append(out, "engineMinVersion", profile.engineMinVersion);
        append(out, "recipeRevision", profile.recipeRevision);
        append(out, "profileId", profile.profileId);
        append(out, "totalCap", profile.totalCap);
        append(out, "eventCap", profile.eventCap);
        append(out, "ambientCap", profile.ambientCap);
        ArrayList<String> assetKinds = new ArrayList<String>(profile.assets.keySet());
        Collections.sort(assetKinds);
        for (String kind : assetKinds) {
            append(out, "assetKind", kind);
            ArrayList<String> paths = new ArrayList<String>(profile.assets.get(kind));
            Collections.sort(paths);
            for (String path : paths) append(out, "assetPath", path);
        }
        ArrayList<String> keys = new ArrayList<String>(profile.recipes.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            CustomMapDocument.ThemeVfxRecipe recipe = profile.recipes.get(key);
            append(out, "event", key);
            append(out, "layer", recipe.layer);
            append(out, "lifetime", recipe.lifetimeTicks);
            append(out, "burst", recipe.burst);
            append(out, "rate", recipe.rate);
            append(out, "velocityMinX", recipe.velocityMinX);
            append(out, "velocityMaxX", recipe.velocityMaxX);
            append(out, "velocityMinY", recipe.velocityMinY);
            append(out, "velocityMaxY", recipe.velocityMaxY);
            append(out, "gravity", recipe.gravity);
            appendFrames(out, "scale", recipe.scale);
            appendFrames(out, "alpha", recipe.alpha);
            append(out, "seedSalt", recipe.seedSalt);
            append(out, "assetKind", recipe.assetKind);
            append(out, "blend", recipe.blend);
            append(out, "primitive", recipe.primitive);
            append(out, "primitiveArgb", recipe.primitiveArgb);
            append(out, "missingAsset", recipe.missingAsset);
            append(out, "soundId", recipe.audio.soundId);
            append(out, "audioCooldown", recipe.audio.cooldownTicks);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(out.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes)
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void appendFrames(StringBuilder out, String name,
                                     List<CustomMapDocument.ThemeVfxKeyframe> frames) {
        for (CustomMapDocument.ThemeVfxKeyframe frame : frames) {
            append(out, name + ".tick", frame.tick);
            append(out, name + ".value", frame.value);
        }
    }

    private static void append(StringBuilder out, String key, Object value) {
        String text = String.valueOf(value);
        out.append(key.length()).append(':').append(key).append('=')
                .append(text.length()).append(':').append(text).append('\n');
    }

    public static final class Outcome {
        public final boolean legacyFallback;
        public final boolean skippedMissingAsset;
        public final int spawned;
        public final boolean soundPlayed;

        private Outcome(boolean legacyFallback, boolean skippedMissingAsset,
                        int spawned, boolean soundPlayed) {
            this.legacyFallback = legacyFallback;
            this.skippedMissingAsset = skippedMissingAsset;
            this.spawned = spawned;
            this.soundPlayed = soundPlayed;
        }
    }

    public static final class ParticleSnapshot {
        public final String eventKey;
        public final String renderKind;
        public final float x;
        public final float y;
        public final float velocityX;
        public final float velocityY;
        public final int lifetimeTicks;

        private ParticleSnapshot(Particle particle) {
            eventKey = particle.eventKey;
            renderKind = particle.sprite ? "sprite" : "rectangle";
            x = particle.x;
            y = particle.y;
            velocityX = particle.velocityX;
            velocityY = particle.velocityY;
            lifetimeTicks = particle.recipe.lifetimeTicks;
        }

        @Override public String toString() {
            return eventKey + '|' + renderKind + '|' + Float.floatToIntBits(x)
                    + '|' + Float.floatToIntBits(y) + '|'
                    + Float.floatToIntBits(velocityX) + '|'
                    + Float.floatToIntBits(velocityY) + '|' + lifetimeTicks;
        }
    }

    public static final class Session {
        private final StyleKit kit;
        private final ArrayList<Particle> particles = new ArrayList<Particle>();
        private final Map<String, Long> soundReadyAt = new HashMap<String, Long>();

        public Session(StyleKit kit) {
            this.kit = kit == null
                    ? invalid("missing-style-kit", new CustomMapDocument.ThemeVfxProfile())
                    : kit;
        }

        public StyleKit styleKit() {
            return kit;
        }

        public Outcome emit(String eventKey, long fixedSeed, float worldX,
                            float layer, int direction, long tick,
                            AssetResolver resolver, Output output) {
            CustomMapDocument.ThemeVfxRecipe recipe = kit.recipe(eventKey);
            if (!kit.valid || recipe == null)
                return new Outcome(true, false, 0, false);
            String normalizedKey = eventKey.trim().toLowerCase(Locale.ROOT);
            boolean ambient = normalizedKey.startsWith("ambient.");
            boolean sprite = resolver != null && !recipe.assetKind.isEmpty()
                    && resolver.hasSprite(recipe.assetKind);
            if (!sprite && "skip".equals(recipe.missingAsset))
                return new Outcome(false, true, 0, false);

            int totalSlots = Math.max(0, kit.totalCap - particles.size());
            int categoryCount = count(ambient);
            int categoryCap = ambient ? kit.ambientCap : kit.eventCap;
            int categorySlots = Math.max(0, categoryCap - categoryCount);
            int spawnCount = Math.min(recipe.burst,
                    Math.min(totalSlots, categorySlots));
            Random random = new Random(mixSeed(fixedSeed, recipe.seedSalt,
                    normalizedKey));
            int signedDirection = direction < 0 ? -1 : 1;
            for (int i = 0; i < spawnCount; i++) {
                float velocityX = (float) between(random, recipe.velocityMinX,
                        recipe.velocityMaxX) * signedDirection;
                float velocityY = (float) between(random, recipe.velocityMinY,
                        recipe.velocityMaxY);
                float jitterX = (random.nextFloat() - .5f) * 12f;
                float jitterY = (random.nextFloat() - .5f) * 8f;
                particles.add(new Particle(normalizedKey, recipe, ambient,
                        sprite, worldX + jitterX, layer + jitterY,
                        velocityX, velocityY));
            }
            boolean played = false;
            if (spawnCount > 0 && recipe.audio.soundId >= 0 && output != null) {
                Long ready = soundReadyAt.get(normalizedKey);
                if (ready == null || tick >= ready.longValue()) {
                    output.playSound(recipe.audio.soundId);
                    soundReadyAt.put(normalizedKey,
                            tick + recipe.audio.cooldownTicks);
                    played = true;
                }
            }
            return new Outcome(false, false, spawnCount, played);
        }

        public void renderAndAdvance(Output output) {
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle particle = particles.get(i);
                float scale = (float) sample(particle.recipe.scale, particle.age);
                float alpha = (float) sample(particle.recipe.alpha, particle.age);
                if (output != null && alpha > 0f && scale > 0f) {
                    if (particle.sprite)
                        output.drawSprite(particle.recipe.layer,
                                particle.recipe.assetKind, particle.x, particle.y,
                                scale, alpha);
                    else
                        output.drawRectangle(particle.recipe.layer,
                                parseArgb(particle.recipe.primitiveArgb),
                                particle.x, particle.y, 18f * scale,
                                8f * scale, alpha);
                }
                particle.x += particle.velocityX;
                particle.y += particle.velocityY;
                particle.velocityY += (float) particle.recipe.gravity;
                particle.age++;
                if (particle.age >= particle.recipe.lifetimeTicks)
                    particles.remove(i);
            }
        }

        public List<ParticleSnapshot> snapshots() {
            ArrayList<ParticleSnapshot> out = new ArrayList<ParticleSnapshot>();
            for (Particle particle : particles) out.add(new ParticleSnapshot(particle));
            return Collections.unmodifiableList(out);
        }

        public int activeCount() {
            return particles.size();
        }

        private int count(boolean ambient) {
            int count = 0;
            for (Particle particle : particles)
                if (particle.ambient == ambient) count++;
            return count;
        }
    }

    private static final class Particle {
        final String eventKey;
        final CustomMapDocument.ThemeVfxRecipe recipe;
        final boolean ambient;
        final boolean sprite;
        float x;
        float y;
        float velocityX;
        float velocityY;
        int age;

        Particle(String eventKey, CustomMapDocument.ThemeVfxRecipe recipe,
                 boolean ambient, boolean sprite, float x, float y,
                 float velocityX, float velocityY) {
            this.eventKey = eventKey;
            this.recipe = recipe;
            this.ambient = ambient;
            this.sprite = sprite;
            this.x = x;
            this.y = y;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
        }
    }

    private static long mixSeed(long seed, long salt, String eventKey) {
        long mixed = seed ^ (salt + 0x9E3779B97F4A7C15L
                + (seed << 6) + (seed >>> 2));
        for (int i = 0; i < eventKey.length(); i++) {
            mixed ^= eventKey.charAt(i);
            mixed *= 0x100000001B3L;
        }
        return mixed;
    }

    private static double between(Random random, double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    private static double sample(List<CustomMapDocument.ThemeVfxKeyframe> frames,
                                 int tick) {
        if (frames == null || frames.isEmpty()) return 1d;
        CustomMapDocument.ThemeVfxKeyframe before = frames.get(0);
        if (tick <= before.tick) return before.value;
        for (int i = 1; i < frames.size(); i++) {
            CustomMapDocument.ThemeVfxKeyframe after = frames.get(i);
            if (tick <= after.tick) {
                double span = Math.max(1d, after.tick - before.tick);
                double progress = (tick - before.tick) / span;
                return before.value + (after.value - before.value) * progress;
            }
            before = after;
        }
        return before.value;
    }

    private static int parseArgb(String value) {
        if (value == null || value.length() != 9 || value.charAt(0) != '#')
            return 0xffffffff;
        try {
            return (int) Long.parseLong(value.substring(1), 16);
        } catch (NumberFormatException ignored) {
            return 0xffffffff;
        }
    }
}
