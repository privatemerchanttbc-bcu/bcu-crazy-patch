package manualcontrol.custommap;

public final class PlatformFallRules {

    public static final class Penalty {
        public final float dropTiles;
        public final float selfDamageRatio;
        public final int stunTicks;
        public final int slowTicks;
        public final float movementMultiplier;

        Penalty(float dropTiles, float selfDamageRatio, int stunTicks,
                int slowTicks, float movementMultiplier) {
            this.dropTiles = dropTiles;
            this.selfDamageRatio = selfDamageRatio;
            this.stunTicks = stunTicks;
            this.slowTicks = slowTicks;
            this.movementMultiplier = movementMultiplier;
        }

        public boolean active() {
            return dropTiles >= 1f;
        }

        public long selfDamage(long maxHp) {
            if (!active() || maxHp <= 0L || selfDamageRatio <= 0f) return 0L;
            double value = Math.ceil(maxHp * (double) selfDamageRatio);
            return Math.max(1L, Math.min(maxHp, (long) value));
        }

        public int effectiveSlowTicks(boolean slowImmune) {
            return slowImmune ? 0 : slowTicks;
        }

        public int effectiveStunTicks(boolean stopImmune) {
            return stopImmune ? 0 : stunTicks;
        }
    }

    private static final Penalty NONE = new Penalty(0f, 0f, 0, 0, 1f);

    private PlatformFallRules() {}

    public static Penalty forDrop(float dropTiles) {
        if (Float.isNaN(dropTiles) || Float.isInfinite(dropTiles)
                || dropTiles < 1f) return NONE;
        if (dropTiles < 3f)
            return new Penalty(dropTiles, .02f, 0, 30, .70f);
        if (dropTiles < 6f)
            return new Penalty(dropTiles, .07f, 15, 60, .55f);
        return new Penalty(dropTiles, .15f, 30, 90, .40f);
    }

    public static final double MAX_IMPACT_RATIO = .20d;
    public static final double IMPACT_REFERENCE_DROP_TILES = 8d;

    public static long impactDamage(float dropTiles, long referenceMaxHp) {
        if (dropTiles < 1f || referenceMaxHp <= 0L
                || Float.isNaN(dropTiles) || Float.isInfinite(dropTiles)) return 0L;
        double ratio = Math.min(MAX_IMPACT_RATIO, dropTiles * .025d);
        return Math.max(1L, Math.min(referenceMaxHp,
                (long) Math.ceil(referenceMaxHp * ratio)));
    }

    public static long impactDamage(float dropTiles, long fallerMaxHp,
                                    long targetMaxHp) {
        if (dropTiles < 1f || fallerMaxHp <= 0L || targetMaxHp <= 0L
                || Float.isNaN(dropTiles) || Float.isInfinite(dropTiles)) return 0L;
        double energy = (double) fallerMaxHp * dropTiles;
        double reference = (double) targetMaxHp * IMPACT_REFERENCE_DROP_TILES;
        double share = Math.max(0d, Math.min(1d, energy / reference));
        double ratio = share * MAX_IMPACT_RATIO;
        if (ratio <= 0d) return 0L;
        return Math.max(1L, Math.min(targetMaxHp,
                (long) Math.ceil(targetMaxHp * ratio)));
    }

    public static int heistHeartDamage(float dropTiles) {
        return dropTiles >= 1f && !Float.isInfinite(dropTiles)
                && !Float.isNaN(dropTiles) ? 1 : 0;
    }
}
