package manualcontrol.adventure;

import common.battle.attack.AttackAb;
import common.battle.entity.EEnemy;
import common.battle.entity.EUnit;
import common.battle.entity.Entity;
import manualcontrol.crazy.collision.SpriteBounds;
import manualcontrol.reflect.BCUFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

final class AdventureAdvancedCores {

    private static final int STATUS_TICKS = 90;
    private static final int BURN_PULSES = 10;
    private static final int RELAY_MARK_TICKS = 120;

    private static final class Burn {
        long remaining;
        int ticks = STATUS_TICKS;
        int pulses = BURN_PULSES;
    }

    private static final class Poise {
        float value;
        int idle;
    }

    private static final class TimedStatus {
        int ticks;
    }

    private static final class Debt {
        long amount;
        int ticks;

        Debt(long amount) {
            this.amount = amount;
            this.ticks = STATUS_TICKS;
        }
    }

    private static final class RelayMark {
        long sourceDamage;
        int ticks = RELAY_MARK_TICKS;
        final Set<EEnemy> collided = Collections.newSetFromMap(
                new WeakHashMap<EEnemy, Boolean>());
    }

    private static final class AttackSnapshot {
        int atkTime;
        int preTime;
        int preID;
        int attacksLeft;
        int elapsed;
        Object tempAtk;
        Object pres;
    }

    private final AdventureBattle battle;
    private final java.util.Random rng = new java.util.Random();
    private final WeakHashMap<EEnemy, Burn> burns = new WeakHashMap<EEnemy, Burn>();
    private final WeakHashMap<EEnemy, Poise> poise = new WeakHashMap<EEnemy, Poise>();
    private final WeakHashMap<EEnemy, TimedStatus> suppression =
            new WeakHashMap<EEnemy, TimedStatus>();
    private final WeakHashMap<EEnemy, TimedStatus> conduit =
            new WeakHashMap<EEnemy, TimedStatus>();
    private final WeakHashMap<EEnemy, RelayMark> relay =
            new WeakHashMap<EEnemy, RelayMark>();
    private final WeakHashMap<EEnemy, Float> positions = new WeakHashMap<EEnemy, Float>();
    private final List<Debt> debt = new ArrayList<Debt>();
    private final Set<Object> scaledAttacks = Collections.newSetFromMap(
            new WeakHashMap<Object, Boolean>());

    private Object lastConduitAttack;
    private AttackSnapshot relentlessCandidate;
    private AttackSnapshot relentlessPending;
    private int lastPlayerKb;
    private int attackPeak;

    AdventureAdvancedCores(AdventureBattle battle) {
        this.battle = battle;
    }

    void onLevelStart() {
        burns.clear();
        poise.clear();
        suppression.clear();
        conduit.clear();
        relay.clear();
        positions.clear();
        debt.clear();
        scaledAttacks.clear();
        lastConduitAttack = null;
        relentlessCandidate = null;
        relentlessPending = null;
        lastPlayerKb = 0;
        attackPeak = 0;
    }

    void beforeTick(EUnit player) {
        positions.clear();
        for (EEnemy e : battle.liveEnemies()) {
            if (isTargetable(e)) positions.put(e, e.pos);
        }
        tickRelentless(player);
    }

    void tick(EUnit player) {
        tickBurns();
        tickPoise();
        tickStatusMap(suppression);
        tickStatusMap(conduit);
        tickRelayMarks();
        tickDebt(player);
        tickRelentless(player);
    }

    void afterTick(EUnit player) {
        processRelayCollisions();
        tickRelentless(player);
    }

    void onDirectPlayerHit(EEnemy enemy, AttackAb attack, long actualDamage) {
        if (!isTargetable(enemy) || attack == null || actualDamage <= 0L) return;
        AdventureCoreState cores = AdventureRuntime.cores();

        eraseDebt(Math.max(1L, Math.round(actualDamage * 0.25f)));

        if (cores.scorchPct() > 0f) {
            long total = Math.max(1L, Math.round(actualDamage * cores.scorchPct()));
            Burn b = burns.get(enemy);
            if (b == null) {
                b = new Burn();
                burns.put(enemy, b);
            }
            b.remaining = Math.max(b.remaining, total);
            b.ticks = STATUS_TICKS;
            b.pulses = BURN_PULSES;
            battle.coreVfx.spawnScorch(enemy);
        }

        if (cores.breakerPoise() > 0f) {
            Poise p = poise.get(enemy);
            if (p == null) {
                p = new Poise();
                poise.put(enemy, p);
            }
            float gain = cores.breakerPoise() * (enemy.mark != 0 ? 0.5f : 1f);
            p.value = Math.min(100f, p.value + gain);
            p.idle = 0;
            battle.coreVfx.spawnBreaker(enemy, p.value / 100f);
            if (p.value >= 100f) {
                p.value = 0f;
                AdventureController.interruptAction(enemy, enemy.mark != 0 ? 15 : 30);
                battle.camera.shake(4, 3);
                battle.coreVfx.spawnPoiseBreak(enemy);
            }
        }

        if (cores.suppressionPct() > 0f) {
            refresh(suppression, enemy, STATUS_TICKS);
            battle.coreVfx.spawnSuppress(enemy);
        }

        if (cores.impactRelayPct() > 0f && !cores.hasUnique("P5")) {
            RelayMark mark = new RelayMark();
            mark.sourceDamage = actualDamage;
            relay.put(enemy, mark);
        }

        if (cores.conduitPct() > 0f) {
            link(enemy);
            battle.coreVfx.spawnConduitNode(enemy);
            if (attack != lastConduitAttack) {
                lastConduitAttack = attack;
                conduct(enemy, actualDamage, cores.conduitPct());
            }
        }

        markFinalDamageFrame(enemy);
    }

    long deferIncomingDamage(EUnit player, long actualDamage,
                             long pendingBefore, long pendingAfter) {
        float pct = AdventureRuntime.cores().temporalDebtPct();
        if (player == null || actualDamage <= 0L || pct <= 0f) return actualDamage;
        long deferred = Math.min(actualDamage, Math.max(1L, Math.round(actualDamage * pct)));
        long immediate = actualDamage - deferred;
        try {
            long adjusted = Math.max(0L, pendingAfter - deferred);
            BCUFields.field(player.getClass(), "damage").setLong(player, adjusted);
            debt.add(new Debt(deferred));
            battle.coreVfx.spawnDebt(player);
            return immediate;
        } catch (Throwable ignored) {
            return actualDamage;
        }
    }

    void clearDebt() {
        debt.clear();
    }

    long debtTotal() {
        long total = 0L;
        for (Debt d : debt) total = saturatingAdd(total, d.amount);
        return total;
    }

    boolean debtDueSoon() {
        for (Debt d : debt) if (d.ticks <= 15) return true;
        return false;
    }

    float poiseFraction(EEnemy enemy) {
        Poise p = poise.get(enemy);
        return p == null ? 0f : Math.max(0f, Math.min(1f, p.value / 100f));
    }

    boolean isSuppressed(EEnemy enemy) {
        TimedStatus s = suppression.get(enemy);
        return s != null && s.ticks > 0;
    }

    boolean isBurning(EEnemy enemy) {
        Burn b = burns.get(enemy);
        return b != null && b.ticks > 0 && b.remaining > 0L;
    }

    boolean isLinked(EEnemy enemy) {
        TimedStatus s = conduit.get(enemy);
        return s != null && s.ticks > 0;
    }

    void modifyEnemyAttack(AttackAb attack) {
        if (attack == null || !(attack.attacker instanceof EEnemy)
                || !scaledAttacks.add(attack)) return;
        TimedStatus status = suppression.get((EEnemy) attack.attacker);
        if (status == null || status.ticks <= 0) return;
        float pct = AdventureRuntime.cores().suppressionPct();
        if (pct <= 0f) return;
        attack.atk = Math.max(0, Math.round(attack.atk * (1f - pct)));
    }

    boolean tryAcrobatCancel(EUnit player) {
        float window = AdventureRuntime.cores().acrobatPct();
        if (player == null || window <= 0f || !AdventureController.isAttackActive(player)) return false;
        int now = AdventureController.readAttackTime(player);
        if (attackPeak <= 0 || now > Math.ceil(attackPeak * window)) return false;
        try {
            Object atkm = BCUFields.get(player, "atkm");
            int left = BCUFields.getInt(atkm, "attacksLeft");
            int preId = BCUFields.getInt(atkm, "preID");
            int multi = BCUFields.getInt(atkm, "multi");
            if (preId < multi || left > 0) return false;
        } catch (Throwable ignored) {}
        AdventureController.interruptAttackOnly(player);
        relentlessCandidate = null;
        attackPeak = 0;
        battle.coreVfx.spawnAcrobat(player);
        return true;
    }

    void noteAttackStarted(EUnit player) {
        attackPeak = Math.max(1, AdventureController.readAttackTime(player));
    }

    void onSkirmishMove(EUnit player) {
        battle.coreVfx.spawnSkirmisher(player);
    }

    private void markFinalDamageFrame(EEnemy ignored) {
        EUnit player = battle.player();
        if (player == null) return;
        attackPeak = Math.max(attackPeak, AdventureController.readAttackTime(player));
    }

    private void tickBurns() {
        Iterator<java.util.Map.Entry<EEnemy, Burn>> it = burns.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<EEnemy, Burn> en = it.next();
            EEnemy e = en.getKey();
            Burn b = en.getValue();
            if (!isTargetable(e) || b == null || b.remaining <= 0L || b.pulses <= 0) {
                it.remove();
                continue;
            }
            b.ticks--;
            int nextPulseAt = (b.pulses - 1) * (STATUS_TICKS / BURN_PULSES);
            if (b.ticks <= nextPulseAt) {
                long pulse = Math.max(1L, b.remaining / b.pulses);
                if (AdventureCombat.queueEffectDamage(e, pulse)) {
                    b.remaining -= pulse;
                    b.pulses--;
                    battle.coreVfx.spawnBurnPulse(e);
                }
            }
            if (b.ticks <= 0 || b.pulses <= 0 || b.remaining <= 0L) it.remove();
        }
    }

    private void tickPoise() {
        Iterator<java.util.Map.Entry<EEnemy, Poise>> it = poise.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<EEnemy, Poise> en = it.next();
            EEnemy e = en.getKey();
            Poise p = en.getValue();
            if (!isTargetable(e) || p == null) {
                it.remove();
                continue;
            }
            if (++p.idle > 60) p.value = Math.max(0f, p.value - 5f / 30f);
            if (p.value <= 0f && p.idle > 60) it.remove();
        }
    }

    private static void tickStatusMap(WeakHashMap<EEnemy, TimedStatus> map) {
        Iterator<java.util.Map.Entry<EEnemy, TimedStatus>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<EEnemy, TimedStatus> en = it.next();
            if (!isTargetable(en.getKey()) || en.getValue() == null || --en.getValue().ticks <= 0) {
                it.remove();
            }
        }
    }

    private void tickRelayMarks() {
        Iterator<java.util.Map.Entry<EEnemy, RelayMark>> it = relay.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<EEnemy, RelayMark> en = it.next();
            if (!isTargetable(en.getKey()) || en.getValue() == null || --en.getValue().ticks <= 0) {
                it.remove();
            }
        }
    }

    private void processRelayCollisions() {
        float pct = AdventureRuntime.cores().impactRelayPct();
        if (pct <= 0f) return;
        for (java.util.Map.Entry<EEnemy, RelayMark> en : relay.entrySet()) {
            EEnemy moving = en.getKey();
            RelayMark mark = en.getValue();
            Float old = positions.get(moving);
            if (!isTargetable(moving) || mark == null || old == null
                    || Math.abs(moving.pos - old.floatValue()) < 1f) continue;
            SpriteBounds.WorldBox mb = SpriteBounds.of(moving);
            for (EEnemy other : battle.liveEnemies()) {
                if (other == moving || !isTargetable(other) || mark.collided.contains(other)) continue;
                SpriteBounds.WorldBox ob = SpriteBounds.of(other);
                boolean overlaps = mb != null && ob != null
                        ? mb.overlaps(ob)
                        : Math.abs(moving.pos - other.pos) <= 140f;
                if (!overlaps) continue;
                long damage = Math.max(1L, Math.round(mark.sourceDamage * pct));
                AdventureCombat.queueEffectDamage(moving, damage);
                AdventureCombat.queueEffectDamage(other, damage);
                mark.collided.add(other);
                battle.coreVfx.spawnRelay(moving, other);
                battle.camera.shake(4, 3);
            }
        }
    }

    private void link(EEnemy enemy) {
        refresh(conduit, enemy, STATUS_TICKS);
        if (conduit.size() <= 5) return;
        EEnemy oldest = null;
        int least = Integer.MAX_VALUE;
        for (java.util.Map.Entry<EEnemy, TimedStatus> en : conduit.entrySet()) {
            if (en.getKey() == enemy) continue;
            if (en.getValue().ticks < least) {
                least = en.getValue().ticks;
                oldest = en.getKey();
            }
        }
        if (oldest != null) conduit.remove(oldest);
    }

    private void conduct(EEnemy source, long actualDamage, float pct) {
        long damage = Math.max(1L, Math.round(actualDamage * pct));
        for (EEnemy other : new ArrayList<EEnemy>(conduit.keySet())) {
            if (other == source || !isTargetable(other)) continue;
            AdventureCombat.queueEffectDamage(other, damage);
            battle.coreVfx.spawnConduit(source, other);
        }
    }

    private void tickDebt(EUnit player) {
        if (player == null || player.dead || player.health <= 0L) {
            debt.clear();
            return;
        }
        Iterator<Debt> it = debt.iterator();
        while (it.hasNext()) {
            Debt d = it.next();
            if (--d.ticks > 0) continue;
            long pending = 0L;
            try { pending = Math.max(0L, BCUFields.getLong(player, "damage")); }
            catch (Throwable ignored) {}
            if (d.amount + pending >= player.health && tryDebtCheatDeath(player)) {
                debt.clear();
                return;
            }
            AdventureCombat.queueEffectDamage(player, d.amount);
            battle.coreVfx.spawnDebtMature(player);
            it.remove();
        }
    }

    private void eraseDebt(long amount) {
        long left = amount;
        Iterator<Debt> it = debt.iterator();
        while (it.hasNext() && left > 0L) {
            Debt d = it.next();
            long paid = Math.min(left, d.amount);
            d.amount -= paid;
            left -= paid;
            if (d.amount <= 0L) it.remove();
        }
    }

    private boolean tryDebtCheatDeath(EUnit player) {
        AdventureCoreState cores = AdventureRuntime.cores();
        if (cores.trySecondWind()) {
            clearPlayerPending(player);
            player.health = Math.max(1L,
                    Math.round(player.maxH * cores.secondWindPct() / 100f));
            grantIFrames(player, 45);
            battle.spawnFx.spawn(player.pos, player.currentLayer, false);
            AdventureSfx.play(AdventureSfx.UNIQUE_STING);
            battle.hud.showMessage("SECOND WIND!", 235, 245, 255);
            return true;
        }
        if (cores.tryRewind()) {
            clearPlayerPending(player);
            player.health = Math.max(1L, Math.round(player.maxH * 0.5f));
            grantIFrames(player, 45);
            battle.spawnFx.spawnColored(player.pos, player.currentLayer,
                    160, 200, 255, 235, 250, 255);
            AdventureSfx.play(AdventureSfx.UNIQUE_STING);
            battle.hud.showMessage("REWIND!", 160, 200, 255);
            return true;
        }
        return false;
    }

    private static void clearPlayerPending(EUnit player) {
        try { BCUFields.field(player.getClass(), "damage").setLong(player, 0L); }
        catch (Throwable ignored) {}
    }

    private static void grantIFrames(EUnit player, int ticks) {
        try {
            int[] immunity = ((int[][]) BCUFields.get(player, "status"))[44];
            immunity[0] = Math.max(immunity[0], ticks);
        } catch (Throwable ignored) {}
    }

    private void tickRelentless(EUnit player) {
        if (player == null || player.dead || player.health <= 0L) {
            relentlessCandidate = null;
            relentlessPending = null;
            lastPlayerKb = 0;
            return;
        }
        float chance = AdventureRuntime.cores().relentlessChance();
        int kb = AdventureController.readKbTime(player);
        if (chance <= 0f) {
            relentlessCandidate = null;
            relentlessPending = null;
            lastPlayerKb = kb;
            return;
        }
        if (kb == 0 && AdventureController.isAttackActive(player)) {
            relentlessCandidate = captureAttack(player);
        }
        if (lastPlayerKb == 0 && kb > 0 && relentlessCandidate != null
                && rng.nextFloat() < chance) {
            relentlessPending = relentlessCandidate;
            relentlessCandidate = null;
            battle.coreVfx.spawnRelentless(player);
        }
        if (lastPlayerKb > 0 && kb == 0 && relentlessPending != null
                && AdventureController.canDrive(player)) {
            restoreAttack(player, relentlessPending);
            relentlessPending = null;
        }
        lastPlayerKb = kb;
    }

    private AttackSnapshot captureAttack(EUnit player) {
        try {
            Object atkm = BCUFields.get(player, "atkm");
            AttackSnapshot s = new AttackSnapshot();
            s.atkTime = BCUFields.getInt(atkm, "atkTime");
            s.preTime = BCUFields.getInt(atkm, "preTime");
            s.preID = BCUFields.getInt(atkm, "preID");
            s.attacksLeft = BCUFields.getInt(atkm, "attacksLeft");
            try { s.tempAtk = BCUFields.get(atkm, "tempAtk"); } catch (Throwable ignored) {}
            try { s.pres = BCUFields.get(atkm, "pres"); } catch (Throwable ignored) {}
            attackPeak = Math.max(attackPeak, s.atkTime);
            s.elapsed = Math.max(0, attackPeak - s.atkTime);
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void restoreAttack(EUnit player, AttackSnapshot s) {
        try {
            Object atkm = BCUFields.get(player, "atkm");
            BCUFields.setInt(atkm, "atkTime", Math.max(1, s.atkTime));
            BCUFields.setInt(atkm, "preTime", s.preTime);
            BCUFields.setInt(atkm, "preID", s.preID);
            BCUFields.setInt(atkm, "attacksLeft", s.attacksLeft);
            if (s.tempAtk != null) BCUFields.set(atkm, "tempAtk", s.tempAtk);
            if (s.pres != null) BCUFields.set(atkm, "pres", s.pres);
            AdventureController.setAnim(player, "ATK");
            for (int i = 0; i < Math.min(300, s.elapsed); i++) AdventureController.tickAnim(player);
            AdventureController.setWalking(player, false);
            AdventureController.syncLastPosition(player);
        } catch (Throwable ignored) {}
    }

    private static void refresh(WeakHashMap<EEnemy, TimedStatus> map,
                                EEnemy enemy, int ticks) {
        TimedStatus s = map.get(enemy);
        if (s == null) {
            s = new TimedStatus();
            map.put(enemy, s);
        }
        s.ticks = ticks;
    }

    private static boolean isTargetable(EEnemy e) {
        return e != null && !e.dead && e.health > 0L
                && !AdventureController.isRevivingCorpse(e);
    }

    private static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
