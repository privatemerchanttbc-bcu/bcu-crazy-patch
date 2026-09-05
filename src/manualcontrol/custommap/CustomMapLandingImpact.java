package manualcontrol.custommap;

import common.battle.StageBasis;
import common.battle.entity.Entity;
import manualcontrol.Logger;
import manualcontrol.crazy.collision.PhysicalCollision;
import manualcontrol.reflect.BCUFields;

public final class CustomMapLandingImpact {

    public static final class Result {
        public final PlatformFallRules.Penalty penalty;
        public final long selfDamage;
        public final long impactDamage;
        public final int targets;

        Result(PlatformFallRules.Penalty penalty, long selfDamage,
               long impactDamage, int targets) {
            this.penalty = penalty;
            this.selfDamage = selfDamage;
            this.impactDamage = impactDamage;
            this.targets = targets;
        }
    }

    private CustomMapLandingImpact() {}

    public static Result resolveSelf(Entity faller, float dropTiles) {
        PlatformFallRules.Penalty penalty = PlatformFallRules.forDrop(dropTiles);
        if (faller == null || !penalty.active())
            return new Result(penalty, 0L, 0L, 0);
        long self = penalty.selfDamage(faller.maxH);
        if (self > 0L) queueDeferredDamage(faller, self);
        return new Result(penalty, self,
                PlatformFallRules.impactDamage(dropTiles, faller.maxH), 0);
    }

    public static Result resolve(StageBasis stage, Entity faller, float dropTiles) {
        PlatformFallRules.Penalty penalty = PlatformFallRules.forDrop(dropTiles);
        if (stage == null || faller == null || !penalty.active())
            return new Result(penalty, 0L, 0L, 0);

        long self = penalty.selfDamage(faller.maxH);
        long impact = PlatformFallRules.impactDamage(dropTiles, faller.maxH);
        int targets = 0;
        if (stage.le != null) {
            for (Entity target : stage.le) {
                if (target == null || target == faller || target.dead
                        || target.health <= 0L || target.isBase()
                        || target.dire != -faller.dire) continue;
                if (!PhysicalCollision.strictSpriteContact(faller, target)) continue;
                long crush = PlatformFallRules.impactDamage(
                        dropTiles, faller.maxH, target.maxH);
                if (crush > 0L && queueDeferredDamage(target, crush)) targets++;
            }
        }
        if (self > 0L) queueDeferredDamage(faller, self);
        return new Result(penalty, self, impact, targets);
    }

    static boolean queueDeferredDamage(Entity target, long amount) {
        if (target == null || amount <= 0L || target.dead || target.health <= 0L)
            return false;
        try {
            long pending = Math.max(0L, BCUFields.getLong(target, "damage"));
            long next = pending > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE : pending + amount;
            if (next == target.health && next < Long.MAX_VALUE) next++;
            BCUFields.field(target.getClass(), "damage").setLong(target, next);
            return true;
        } catch (Throwable failure) {
            Logger.err("CustomMap: deferred landing damage unavailable", failure);
            return false;
        }
    }
}
