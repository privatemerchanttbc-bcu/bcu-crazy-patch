package manualcontrol.custommap;

import manualcontrol.custommap.CustomMapDocument.ModeVariant;
import manualcontrol.custommap.CustomMapDocument.PlatformPatrol;
import manualcontrol.custommap.CustomMapDocument.SecondaryPlatform;

public final class MovingPlatformEngine {

    public static final int TICKS_PER_SECOND = 30;
    public static final float POSITION_EPSILON_TILES = 0.0001f;
    public static final float DEFAULT_SPEED_TILES_PER_SECOND = 1f;
    public static final float MIN_SPEED_TILES_PER_SECOND = 0.1f;
    public static final float MAX_SPEED_TILES_PER_SECOND = 4f;
    public static final float DEFAULT_DWELL_SECONDS = 1f;
    public static final float MIN_TRAVEL_SECONDS = 1f / TICKS_PER_SECOND;
    public static final float MAX_DWELL_SECONDS = 30f;
    public static final String AUTHORITY_SPEED = "speed";
    public static final String AUTHORITY_DURATION = "duration";
    public static final String EASING_EASE_IN_OUT = "ease-in-out";

    private static final float EPSILON = POSITION_EPSILON_TILES;

    private MovingPlatformEngine() {}

    public enum Leg {
        STATIC, DWELL_A, OUTBOUND, DWELL_B, RETURN
    }

    public static final class Pose {
        public final float centerTileX;
        public final float supportTileY;

        public final float offsetTileX;
        public final float offsetTileY;
        public final float deltaTileX;
        public final float deltaTileY;
        public final float velocityTilesPerSecondX;
        public final float velocityTilesPerSecondY;

        public final float progress;

        public final float easedProgress;
        public final Leg leg;
        public final boolean atEndpoint;

        private Pose(float centerTileX, float supportTileY,
                     float offsetTileX, float offsetTileY,
                     float deltaTileX, float deltaTileY,
                     float velocityTilesPerSecondX,
                     float velocityTilesPerSecondY,
                     float progress, float easedProgress,
                     Leg leg, boolean atEndpoint) {
            this.centerTileX = centerTileX;
            this.supportTileY = supportTileY;
            this.offsetTileX = offsetTileX;
            this.offsetTileY = offsetTileY;
            this.deltaTileX = deltaTileX;
            this.deltaTileY = deltaTileY;
            this.velocityTilesPerSecondX = velocityTilesPerSecondX;
            this.velocityTilesPerSecondY = velocityTilesPerSecondY;
            this.progress = progress;
            this.easedProgress = easedProgress;
            this.leg = leg;
            this.atEndpoint = atEndpoint;
        }

        public float centerWorldX(ModeVariant variant) {
            return centerTileX * worldUnitsPerTile(variant);
        }

        public float supportLayer(ModeVariant variant) {
            return -supportTileY * layerUnitsPerTile(variant);
        }

        public float deltaWorldX(ModeVariant variant) {
            return deltaTileX * worldUnitsPerTile(variant);
        }

        public float deltaLayer(ModeVariant variant) {
            return -deltaTileY * layerUnitsPerTile(variant);
        }

        public float offsetWorldX(ModeVariant variant) {
            return offsetTileX * worldUnitsPerTile(variant);
        }

        public float offsetLayer(ModeVariant variant) {
            return -offsetTileY * layerUnitsPerTile(variant);
        }

        public float leftTileX(SecondaryPlatform platform) {
            return centerTileX - (platform == null ? 0f : platform.widthTiles() * 0.5f);
        }

        public float rightTileX(SecondaryPlatform platform) {
            return centerTileX + (platform == null ? 0f : platform.widthTiles() * 0.5f);
        }

        public float collisionLeftTileX(SecondaryPlatform platform) {
            return platform == null ? centerTileX
                    : platform.collisionLeftTileX(centerTileX);
        }

        public float collisionRightTileX(SecondaryPlatform platform) {
            return platform == null ? centerTileX
                    : platform.collisionRightTileX(centerTileX);
        }

        public float collisionSupportLayer(ModeVariant variant,
                                           SecondaryPlatform platform) {
            float base = supportLayer(variant);
            return platform == null ? base : platform.collisionSupportLayer(
                    base, layerUnitsPerTile(variant));
        }

        public float collisionSupportTileY(SecondaryPlatform platform) {
            return platform == null ? supportTileY
                    : platform.collisionSupportTileY(supportTileY);
        }

        public float velocityWorldPerSecondX(ModeVariant variant) {
            return velocityTilesPerSecondX * worldUnitsPerTile(variant);
        }

        public float velocityLayerPerSecond(ModeVariant variant) {
            return -velocityTilesPerSecondY * layerUnitsPerTile(variant);
        }
    }

    public static void normalize(ModeVariant variant) {
        if (variant == null || variant.secondaryPlatforms == null) return;

        java.util.IdentityHashMap<PlatformPatrol, Boolean> owners =
                new java.util.IdentityHashMap<PlatformPatrol, Boolean>();
        for (SecondaryPlatform platform : variant.secondaryPlatforms) {
            if (platform == null) continue;
            if (platform.patrol != null
                    && owners.put(platform.patrol, Boolean.TRUE) != null) {
                platform.patrol = copyPatrol(platform.patrol);
                owners.put(platform.patrol, Boolean.TRUE);
            }
            normalize(variant, platform);
        }
    }

    private static PlatformPatrol copyPatrol(PlatformPatrol source) {
        PlatformPatrol copy = new PlatformPatrol();
        copy.enabled = source.enabled;
        copy.coordinatesInitialized = source.coordinatesInitialized;
        copy.ax = source.ax;
        copy.ay = source.ay;
        copy.bx = source.bx;
        copy.by = source.by;
        copy.speedTilesPerSecond = source.speedTilesPerSecond;
        copy.durationSeconds = source.durationSeconds;
        copy.dwellSeconds = source.dwellSeconds;
        copy.timingAuthority = source.timingAuthority;
        copy.easing = source.easing;
        return copy;
    }

    public static void normalize(ModeVariant variant, SecondaryPlatform platform) {
        if (platform == null) return;
        if (platform.id == null || platform.id.trim().isEmpty()) {
            int row = Math.round((variant == null ? 0 : variant.height)
                    + platform.supportLayer / layerUnitsPerTile(variant));
            platform.id = CustomMapGenerator.stablePlatformId(
                    variant, row, platform.startX, platform.endX);
        }
        if (platform.patrol == null) platform.patrol = new PlatformPatrol();
        PlatformPatrol patrol = platform.patrol;
        float originX = originCenterTileX(platform);
        float originY = originSupportTileY(variant, platform);
        if (!patrol.coordinatesInitialized
                || !finite(patrol.ax) || !finite(patrol.ay)
                || !finite(patrol.bx) || !finite(patrol.by)) {
            patrol.ax = originX;
            patrol.ay = originY;
            patrol.bx = originX;
            patrol.by = originY;
            patrol.coordinatesInitialized = true;
        }
        patrol.dwellSeconds = clamp(finite(patrol.dwellSeconds)
                        ? patrol.dwellSeconds : DEFAULT_DWELL_SECONDS,
                0f, MAX_DWELL_SECONDS);
        patrol.easing = EASING_EASE_IN_OUT;

        float distance = distance(patrol.ax, patrol.ay, patrol.bx, patrol.by);
        if (distance <= EPSILON) {

            patrol.enabled = false;
            patrol.speedTilesPerSecond = clamp(finite(patrol.speedTilesPerSecond)
                            ? patrol.speedTilesPerSecond
                            : DEFAULT_SPEED_TILES_PER_SECOND,
                    MIN_SPEED_TILES_PER_SECOND, MAX_SPEED_TILES_PER_SECOND);

            if (AUTHORITY_DURATION.equals(patrol.timingAuthority)
                    && finite(patrol.durationSeconds)
                    && patrol.durationSeconds >= MIN_TRAVEL_SECONDS) {
                return;
            }
            patrol.durationSeconds = 0f;
            if (!AUTHORITY_SPEED.equals(patrol.timingAuthority))
                patrol.timingAuthority = AUTHORITY_SPEED;
            return;
        }

        if (AUTHORITY_DURATION.equals(patrol.timingAuthority)
                && finite(patrol.durationSeconds)
                && patrol.durationSeconds >= MIN_TRAVEL_SECONDS) {
            float derivedSpeed = distance / patrol.durationSeconds;
            patrol.speedTilesPerSecond = clamp(derivedSpeed,
                    MIN_SPEED_TILES_PER_SECOND, MAX_SPEED_TILES_PER_SECOND);

            patrol.durationSeconds = distance / patrol.speedTilesPerSecond;
        } else {
            patrol.timingAuthority = AUTHORITY_SPEED;
            patrol.speedTilesPerSecond = clamp(finite(patrol.speedTilesPerSecond)
                            ? patrol.speedTilesPerSecond
                            : DEFAULT_SPEED_TILES_PER_SECOND,
                    MIN_SPEED_TILES_PER_SECOND, MAX_SPEED_TILES_PER_SECOND);
            patrol.durationSeconds = distance / patrol.speedTilesPerSecond;
        }
    }

    public static void initializeAtOrigin(ModeVariant variant, SecondaryPlatform platform) {
        if (platform == null) return;
        if (platform.patrol == null) platform.patrol = new PlatformPatrol();
        PlatformPatrol patrol = platform.patrol;
        patrol.enabled = false;
        patrol.coordinatesInitialized = true;
        patrol.ax = patrol.bx = originCenterTileX(platform);
        patrol.ay = patrol.by = originSupportTileY(variant, platform);
        patrol.speedTilesPerSecond = DEFAULT_SPEED_TILES_PER_SECOND;
        patrol.durationSeconds = 0f;
        patrol.dwellSeconds = DEFAULT_DWELL_SECONDS;
        patrol.timingAuthority = AUTHORITY_SPEED;
        patrol.easing = EASING_EASE_IN_OUT;
    }

    public static void setEndpoints(ModeVariant variant, SecondaryPlatform platform,
                                    float ax, float ay, float bx, float by) {
        ensurePatrol(platform);
        platform.patrol.ax = ax;
        platform.patrol.ay = ay;
        platform.patrol.bx = bx;
        platform.patrol.by = by;
        platform.patrol.coordinatesInitialized = true;
        normalize(variant, platform);
    }

    public static void setSpeed(ModeVariant variant, SecondaryPlatform platform,
                                float speedTilesPerSecond) {
        ensurePatrol(platform);
        platform.patrol.speedTilesPerSecond = speedTilesPerSecond;
        platform.patrol.timingAuthority = AUTHORITY_SPEED;
        normalize(variant, platform);
    }

    public static void setDuration(ModeVariant variant, SecondaryPlatform platform,
                                   float durationSeconds) {
        ensurePatrol(platform);
        platform.patrol.durationSeconds = durationSeconds;
        platform.patrol.timingAuthority = AUTHORITY_DURATION;
        normalize(variant, platform);
    }

    public static void setDwell(ModeVariant variant, SecondaryPlatform platform,
                                float dwellSeconds) {
        ensurePatrol(platform);
        platform.patrol.dwellSeconds = dwellSeconds;
        normalize(variant, platform);
    }

    public static Pose poseAtTick(ModeVariant variant, SecondaryPlatform platform,
                                  long gameplayTick) {
        Parameters parameters = parameters(variant, platform);
        RawPose current = rawPose(parameters, gameplayTick);
        RawPose previous = gameplayTick <= 0L
                ? current : rawPose(parameters, gameplayTick - 1L);
        return new Pose(current.x, current.y,
                current.x - parameters.originX,
                current.y - parameters.originY,
                current.x - previous.x, current.y - previous.y,
                current.vx, current.vy,
                current.progress, current.easedProgress,
                current.leg, current.atEndpoint);
    }

    public static long cycleTicks(ModeVariant variant, SecondaryPlatform platform) {
        Parameters p = parameters(variant, platform);
        if (!p.enabled || p.distance <= EPSILON) return 0L;
        return 2L * p.dwellTicks + 2L * p.travelTicks;
    }

    public static boolean isBoardingStop(Pose pose) {
        return pose != null && (pose.leg == Leg.DWELL_A
                || pose.leg == Leg.DWELL_B);
    }

    public static long remainingDwellTicks(ModeVariant variant,
                                           SecondaryPlatform platform,
                                           long gameplayTick) {
        Parameters p = parameters(variant, platform);
        if (!p.enabled || p.distance <= EPSILON || p.dwellTicks <= 0L
                || p.travelTicks <= 0L) return 0L;
        long cycle = 2L * p.dwellTicks + 2L * p.travelTicks;
        if (cycle <= 0L) return 0L;
        long local = floorMod(gameplayTick, cycle);
        if (local < p.dwellTicks) return p.dwellTicks - local;
        local -= p.dwellTicks + p.travelTicks;
        if (local >= 0L && local < p.dwellTicks)
            return p.dwellTicks - local;
        return 0L;
    }

    public static long travelTicks(ModeVariant variant, SecondaryPlatform platform) {
        return parameters(variant, platform).travelTicks;
    }

    public static float originCenterTileX(SecondaryPlatform platform) {
        return platform == null ? 0f : platform.originCenterTileX();
    }

    public static float originSupportTileY(ModeVariant variant, SecondaryPlatform platform) {
        if (platform == null) return 0f;
        return -platform.supportLayer / layerUnitsPerTile(variant);
    }

    private static Parameters parameters(ModeVariant variant, SecondaryPlatform platform) {
        Parameters out = new Parameters();
        if (platform == null) return out;
        PlatformPatrol patrol = platform.patrol;
        float originX = originCenterTileX(platform);
        float originY = originSupportTileY(variant, platform);
        out.originX = originX;
        out.originY = originY;
        boolean initialized = patrol != null && patrol.coordinatesInitialized
                && finite(patrol.ax) && finite(patrol.ay)
                && finite(patrol.bx) && finite(patrol.by);
        out.ax = initialized ? patrol.ax : originX;
        out.ay = initialized ? patrol.ay : originY;
        out.bx = initialized ? patrol.bx : originX;
        out.by = initialized ? patrol.by : originY;
        out.distance = distance(out.ax, out.ay, out.bx, out.by);
        out.enabled = patrol != null && patrol.enabled;
        out.dwellSeconds = patrol == null || !finite(patrol.dwellSeconds)
                ? DEFAULT_DWELL_SECONDS : clamp(patrol.dwellSeconds, 0f, MAX_DWELL_SECONDS);
        out.dwellTicks = Math.max(0L, Math.round(out.dwellSeconds * TICKS_PER_SECOND));

        float speed = patrol == null || !finite(patrol.speedTilesPerSecond)
                ? DEFAULT_SPEED_TILES_PER_SECOND
                : clamp(patrol.speedTilesPerSecond,
                MIN_SPEED_TILES_PER_SECOND, MAX_SPEED_TILES_PER_SECOND);
        float duration;
        if (patrol != null && AUTHORITY_DURATION.equals(patrol.timingAuthority)
                && finite(patrol.durationSeconds)
                && patrol.durationSeconds >= MIN_TRAVEL_SECONDS) {
            duration = patrol.durationSeconds;
            float derivedSpeed = out.distance / duration;
            if (derivedSpeed < MIN_SPEED_TILES_PER_SECOND
                    || derivedSpeed > MAX_SPEED_TILES_PER_SECOND) {
                speed = clamp(derivedSpeed, MIN_SPEED_TILES_PER_SECOND,
                        MAX_SPEED_TILES_PER_SECOND);
                duration = out.distance / speed;
            } else speed = derivedSpeed;
        } else duration = out.distance <= EPSILON ? 0f : out.distance / speed;
        out.durationSeconds = duration;
        out.travelTicks = out.distance <= EPSILON ? 0L
                : Math.max(1L, Math.round(duration * TICKS_PER_SECOND));
        return out;
    }

    private static RawPose rawPose(Parameters p, long tick) {
        if (!p.enabled)
            return new RawPose(p.originX, p.originY, 0f, 0f,
                    0f, 0f, Leg.STATIC, true);
        if (p.distance <= EPSILON || p.travelTicks <= 0L)
            return new RawPose(p.ax, p.ay, 0f, 0f,
                    0f, 0f, Leg.STATIC, true);
        long cycle = 2L * p.dwellTicks + 2L * p.travelTicks;
        if (cycle <= 0L) return new RawPose(p.ax, p.ay, 0f, 0f,
                0f, 0f, Leg.STATIC, true);
        long local = floorMod(tick, cycle);
        if (local < p.dwellTicks)
            return new RawPose(p.ax, p.ay, 0f, 0f,
                    0f, 0f, Leg.DWELL_A, true);
        local -= p.dwellTicks;
        if (local < p.travelTicks)
            return travelling(p, local / (float) p.travelTicks, false);
        local -= p.travelTicks;
        if (local < p.dwellTicks)
            return new RawPose(p.bx, p.by, 0f, 0f,
                    1f, 1f, Leg.DWELL_B, true);
        local -= p.dwellTicks;
        if (local < p.travelTicks)
            return travelling(p, local / (float) p.travelTicks, true);
        return new RawPose(p.ax, p.ay, 0f, 0f,
                0f, 0f, Leg.DWELL_A, true);
    }

    private static RawPose travelling(Parameters p, float progress, boolean returning) {
        float t = clamp(progress, 0f, 1f);
        float eased = smoothstep(t);
        float derivative = 6f * t * (1f - t);
        float direction = returning ? -1f : 1f;
        float pathProgress = returning ? 1f - eased : eased;
        float x = p.ax + (p.bx - p.ax) * pathProgress;
        float y = p.ay + (p.by - p.ay) * pathProgress;
        float seconds = Math.max(MIN_TRAVEL_SECONDS,
                p.travelTicks / (float) TICKS_PER_SECOND);
        float vx = (p.bx - p.ax) * derivative / seconds * direction;
        float vy = (p.by - p.ay) * derivative / seconds * direction;
        return new RawPose(x, y, vx, vy, t, pathProgress,
                returning ? Leg.RETURN : Leg.OUTBOUND,
                t <= EPSILON || t >= 1f - EPSILON);
    }

    private static float smoothstep(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static void ensurePatrol(SecondaryPlatform platform) {
        if (platform == null)
            throw new IllegalArgumentException("Platform is required.");
        if (platform.patrol == null) platform.patrol = new PlatformPatrol();
    }

    private static int worldUnitsPerTile(ModeVariant variant) {
        return variant == null ? CustomMapDocument.WORLD_PER_TILE
                : Math.max(1, variant.worldUnitsPerTile());
    }

    private static int layerUnitsPerTile(ModeVariant variant) {
        return variant == null ? CustomMapDocument.LAYERS_PER_TILE
                : Math.max(1, variant.layerUnitsPerTile());
    }

    private static float distance(float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long floorMod(long value, long modulus) {
        long result = value % modulus;
        return result < 0L ? result + modulus : result;
    }

    private static final class Parameters {
        boolean enabled;
        float originX;
        float originY;
        float ax;
        float ay;
        float bx;
        float by;
        float distance;
        float dwellSeconds;
        float durationSeconds;
        long dwellTicks;
        long travelTicks;
    }

    private static final class RawPose {
        final float x;
        final float y;
        final float vx;
        final float vy;
        final float progress;
        final float easedProgress;
        final Leg leg;
        final boolean atEndpoint;

        RawPose(float x, float y, float vx, float vy,
                float progress, float easedProgress,
                Leg leg, boolean atEndpoint) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.progress = progress;
            this.easedProgress = easedProgress;
            this.leg = leg;
            this.atEndpoint = atEndpoint;
        }
    }
}
