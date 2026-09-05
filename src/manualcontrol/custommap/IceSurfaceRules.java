package manualcontrol.custommap;

public final class IceSurfaceRules {

    public enum Phase { NONE, GLIDE, TUMBLE }

    public static final float MIN_GLIDE_SPEED = 0.018f;
    public static final float ACCELERATION_PER_TICK = 0.0015f;
    public static final float MAX_GLIDE_SPEED = 0.120f;
    public static final float CONTROL_SPEED_MULTIPLIER = 1.20f;
    public static final float TUMBLE_THRESHOLD = 0.065f;
    public static final float TUMBLE_FRICTION = 0.925f;
    public static final float DRY_TUMBLE_FRICTION = 0.86f;
    public static final float MIN_REBOUND_SPEED = 0.035f;
    public static final float REBOUND_RETENTION = 0.58f;
    public static final int IMPACT_COOLDOWN_TICKS = 8;
    public static final int MAX_TUMBLE_TICKS = 42;

    public static final float RESTITUTION = 0.45f;
    public static final float MAX_IMPACT_SPEED = MAX_GLIDE_SPEED * 2f;
    public static final float MAX_IMPACT_DAMAGE_RATIO = 0.18f;

    public static final float ENERGY_REFERENCE = 0.0144f;

    public static final float STAGGER_DELTA = MAX_GLIDE_SPEED * 0.2f;

    private IceSurfaceRules() {}

    public static final class Step {
        public final Phase phase;
        public final boolean forced;
        public final boolean lockAttack;
        public final float deltaTiles;

        Step(Phase phase, boolean forced, boolean lockAttack, float deltaTiles) {
            this.phase = phase;
            this.forced = forced;
            this.lockAttack = lockAttack;
            this.deltaTiles = finite(deltaTiles) ? deltaTiles : 0f;
        }
    }

    public static final class Impact {
        public final boolean active;
        public final float damageRatio;
        public final float reboundTilesPerTick;
        public final int cooldownTicks;

        Impact(boolean active, float damageRatio,
               float reboundTilesPerTick, int cooldownTicks) {
            this.active = active;
            this.damageRatio = damageRatio;
            this.reboundTilesPerTick = reboundTilesPerTick;
            this.cooldownTicks = cooldownTicks;
        }
    }

    public static final class Collision {
        public final boolean active;
        public final float velocityA;
        public final float velocityB;
        public final float damageRatioA;
        public final float damageRatioB;
        public final boolean staggerA;
        public final boolean staggerB;

        Collision(boolean active, float velocityA, float velocityB,
                  float damageRatioA, float damageRatioB,
                  boolean staggerA, boolean staggerB) {
            this.active = active;
            this.velocityA = velocityA;
            this.velocityB = velocityB;
            this.damageRatioA = damageRatioA;
            this.damageRatioB = damageRatioB;
            this.staggerA = staggerA;
            this.staggerB = staggerB;
        }
    }

    public static final float WATER_RESTITUTION = 0.2f;
    public static final float WATER_ENERGY_SCALE = 0.35f;

    public static Collision resolve(float massA, float velocityA,
                                    float massB, float velocityB) {
        return resolve(massA, velocityA, massB, velocityB, RESTITUTION, 1f);
    }

    public static Collision resolve(float massA, float velocityA,
                                    float massB, float velocityB,
                                    float restitution, float energyScale) {
        if (!finite(massA) || !finite(massB) || !finite(restitution)
                || !finite(energyScale)
                || !finite(velocityA) || !finite(velocityB)) return noCollision();
        float mA = Math.max(1f, massA);
        float mB = Math.max(1f, massB);
        float relative = Math.abs(velocityA - velocityB);
        if (relative < MIN_GLIDE_SPEED * .8f) return noCollision();

        float total = mA + mB;
        float e = clamp(restitution, 0f, 1f);
        float outA = clamp(((mA - e * mB) * velocityA
                + (1f + e) * mB * velocityB) / total,
                -MAX_IMPACT_SPEED, MAX_IMPACT_SPEED);
        float outB = clamp(((mB - e * mA) * velocityB
                + (1f + e) * mA * velocityA) / total,
                -MAX_IMPACT_SPEED, MAX_IMPACT_SPEED);

        float reduced = mA * mB / total;
        float energy = .5f * reduced * relative * relative
                * Math.max(0f, energyScale);
        float ratioA = impactRatio(energy, mA);
        float ratioB = impactRatio(energy, mB);
        return new Collision(true, outA, outB, ratioA, ratioB,
                Math.abs(outA - velocityA) >= STAGGER_DELTA,
                Math.abs(outB - velocityB) >= STAGGER_DELTA);
    }

    private static float impactRatio(float energy, float mass) {
        float reference = ENERGY_REFERENCE * mass;
        if (reference <= 0f) return 0f;
        return clamp(energy / reference, 0f, 1f) * MAX_IMPACT_DAMAGE_RATIO;
    }

    private static Collision noCollision() {
        return new Collision(false, 0f, 0f, 0f, 0f, false, false);
    }

    public static final class Motion {
        private Phase phase = Phase.NONE;
        private float velocityTilesPerTick;
        private float referenceSpeedTilesPerTick;
        private float nativeSpeedTilesPerTick;
        private int tumbleTicks;
        private int impactCooldown;

        public void setNativeSpeed(float tilesPerTick) {
            nativeSpeedTilesPerTick = finite(tilesPerTick) && tilesPerTick > 0f
                    ? Math.min(MAX_GLIDE_SPEED, tilesPerTick) : 0f;
        }

        public float nativeSpeedTilesPerTick() { return nativeSpeedTilesPerTick; }

        public float settleSpeed() {
            return Math.max(MIN_GLIDE_SPEED * .55f, nativeSpeedTilesPerTick);
        }

        public boolean settled() {
            return Math.abs(velocityTilesPerTick) <= settleSpeed() + .000001f;
        }

        public Phase phase() { return phase; }
        public float velocityTilesPerTick() { return velocityTilesPerTick; }
        public int direction() { return sign(velocityTilesPerTick); }
        public boolean active() { return phase != Phase.NONE; }
        public boolean gliding() { return phase == Phase.GLIDE; }
        public boolean tumbling() { return phase == Phase.TUMBLE; }
        public boolean canImpact() { return active() && impactCooldown <= 0; }
        public boolean canVoluntarilyStop() {
            return phase == Phase.GLIDE && Math.abs(velocityTilesPerTick)
                    <= controlSpeedLimit() + .000001f;
        }

        public void clear() {
            phase = Phase.NONE;
            velocityTilesPerTick = 0f;
            referenceSpeedTilesPerTick = 0f;
            tumbleTicks = 0;
            impactCooldown = 0;
        }

        public Step tick(boolean onIce, float nativeDeltaTiles,
                         int facingDirection, int downhillDirection) {
            if (impactCooldown > 0) impactCooldown--;
            nativeDeltaTiles = finite(nativeDeltaTiles) ? nativeDeltaTiles : 0f;

            if (phase == Phase.NONE) {
                if (!onIce) return idle();
                int direction = sign(nativeDeltaTiles);
                if (direction == 0) return idle();
                float entered = Math.min(MAX_GLIDE_SPEED,
                        Math.abs(nativeDeltaTiles));
                referenceSpeedTilesPerTick = entered;
                velocityTilesPerTick = direction * entered;
                phase = Phase.GLIDE;
            }

            if (phase == Phase.GLIDE) {
                if (!onIce) {
                    if (Math.abs(velocityTilesPerTick) < TUMBLE_THRESHOLD) {
                        clear();
                        return idle();
                    }
                    beginTumble();
                } else {
                    int direction = sign(velocityTilesPerTick);
                    if (direction == 0)
                        direction = sign(nativeDeltaTiles) != 0
                                ? sign(nativeDeltaTiles)
                                : facingDirection < 0 ? -1 : 1;
                    int requestedDirection = sign(nativeDeltaTiles);
                    boolean requestedStop = requestedDirection == 0
                            || requestedDirection == -direction;
                    if (requestedStop && canVoluntarilyStop()) {
                        clear();
                        return idle();
                    }
                    float magnitude = Math.max(Math.abs(velocityTilesPerTick),
                            sign(nativeDeltaTiles) == direction
                                    ? Math.abs(nativeDeltaTiles) : 0f);
                    float gravityFactor = downhillDirection == direction ? 1.28f
                            : downhillDirection == -direction ? .72f : 1f;
                    magnitude = Math.min(MAX_GLIDE_SPEED,
                            magnitude + ACCELERATION_PER_TICK * gravityFactor);
                    velocityTilesPerTick = direction * magnitude;
                }
            }

            if (phase == Phase.TUMBLE) {
                if (tumbleTicks-- <= 0 || settled()) {
                    clear();
                    return idle();
                }
                velocityTilesPerTick *= onIce
                        ? TUMBLE_FRICTION : DRY_TUMBLE_FRICTION;
            }
            return new Step(phase, true,
                    phase == Phase.TUMBLE
                            || (phase == Phase.GLIDE && !canVoluntarilyStop()),
                    velocityTilesPerTick);
        }

        public void applyCollision(float signedVelocityTilesPerTick, boolean stagger) {
            float v = clamp(signedVelocityTilesPerTick,
                    -MAX_IMPACT_SPEED, MAX_IMPACT_SPEED);
            if (!finite(v) || Math.abs(v) < MIN_GLIDE_SPEED * .55f) {
                clear();
                return;
            }
            velocityTilesPerTick = v;
            if (referenceSpeedTilesPerTick <= 0f)
                referenceSpeedTilesPerTick = Math.max(MIN_GLIDE_SPEED,
                        nativeSpeedTilesPerTick);
            if (stagger || phase == Phase.TUMBLE) {
                phase = Phase.TUMBLE;
                float speedRatio = clamp(Math.abs(v) / MAX_GLIDE_SPEED, 0f, 1f);
                tumbleTicks = Math.max(16, Math.round(18f
                        + (MAX_TUMBLE_TICKS - 18f) * speedRatio));
            } else {
                phase = Phase.GLIDE;
                tumbleTicks = 0;
            }
            impactCooldown = IMPACT_COOLDOWN_TICKS;
        }

        public void beginTumble() {
            if (phase == Phase.NONE || !finite(velocityTilesPerTick)) return;
            phase = Phase.TUMBLE;
            float speedRatio = clamp(Math.abs(velocityTilesPerTick)
                    / MAX_GLIDE_SPEED, 0f, 1f);
            tumbleTicks = Math.max(16, Math.round(18f
                    + (MAX_TUMBLE_TICKS - 18f) * speedRatio));
        }

        public Impact hitEnemy(float otherVelocityTilesPerTick) {
            if (!canImpact()) return noImpact();
            float relative = Math.abs(velocityTilesPerTick
                    - (finite(otherVelocityTilesPerTick)
                    ? otherVelocityTilesPerTick : 0f));
            relative = Math.max(relative, Math.abs(velocityTilesPerTick));
            if (relative < MIN_GLIDE_SPEED * .8f) return noImpact();

            int direction = sign(velocityTilesPerTick);
            if (direction == 0) direction = 1;
            float reboundMagnitude = Math.min(MAX_GLIDE_SPEED,
                    Math.max(MIN_REBOUND_SPEED,
                            Math.abs(velocityTilesPerTick) * REBOUND_RETENTION));
            velocityTilesPerTick = -direction * reboundMagnitude;
            impactCooldown = IMPACT_COOLDOWN_TICKS;

            float normalized = clamp((relative - MIN_GLIDE_SPEED)
                    / Math.max(.0001f, MAX_GLIDE_SPEED - MIN_GLIDE_SPEED), 0f, 1f);
            float ratio = .025f + normalized * .155f;
            return new Impact(true, ratio, velocityTilesPerTick,
                    IMPACT_COOLDOWN_TICKS);
        }

        public void armImpactCooldown() {
            if (active())
                impactCooldown = Math.max(impactCooldown, IMPACT_COOLDOWN_TICKS);
        }

        public void start(float signedVelocityTilesPerTick, Phase requestedPhase) {
            if (!finite(signedVelocityTilesPerTick)
                    || Math.abs(signedVelocityTilesPerTick) < .0001f) {
                clear();
                return;
            }
            velocityTilesPerTick = clamp(signedVelocityTilesPerTick,
                    -MAX_GLIDE_SPEED, MAX_GLIDE_SPEED);
            referenceSpeedTilesPerTick = Math.max(MIN_GLIDE_SPEED,
                    Math.min(Math.abs(velocityTilesPerTick), TUMBLE_THRESHOLD));
            phase = requestedPhase == Phase.TUMBLE ? Phase.TUMBLE : Phase.GLIDE;
            if (phase == Phase.TUMBLE) beginTumble();
            else tumbleTicks = 0;
        }

        private float controlSpeedLimit() {
            return Math.min(MAX_GLIDE_SPEED, Math.max(.0001f,
                    referenceSpeedTilesPerTick) * CONTROL_SPEED_MULTIPLIER);
        }
    }

    public static long damage(long referenceMaxHealth, float ratio) {
        if (referenceMaxHealth <= 0L || !finite(ratio) || ratio <= 0f) return 0L;
        double value = Math.ceil(referenceMaxHealth
                * (double) clamp(ratio, 0f, .18f));
        return Math.max(1L, Math.min(referenceMaxHealth, (long) value));
    }

    private static Step idle() {
        return new Step(Phase.NONE, false, false, 0f);
    }

    private static Impact noImpact() {
        return new Impact(false, 0f, 0f, 0);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static int sign(float value) {
        return value > .000001f ? 1 : value < -.000001f ? -1 : 0;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
