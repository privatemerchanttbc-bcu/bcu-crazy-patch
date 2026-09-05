package manualcontrol.adventure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static manualcontrol.adventure.AdventureCoreCatalog.*;

public final class AdventureCoreState {

    private final List<AdventureCore> owned = new ArrayList<AdventureCore>();
    private final Set<String> uniques = new HashSet<String>();

    private final int[] seenFamilyTierMask = new int[AdventureCoreCatalog.familyCount()];

    private final Map<String, Integer> seenUniqueTier = new HashMap<String, Integer>();
    private final Random rng = new Random();

    private float coinDmgPct = 0f;
    private float coinHpPct = 0f;

    private float hpMult = 1f;
    private float hpFlatPct = 0f;
    private float dmgMult = 1f;
    private float critChance = 0f;
    private float execBonus = 0f;
    private float giantBonus = 0f;
    private float berserkBonus = 0f;
    private float momentumPer = 0f;
    private float moveMult = 1f;
    private float atkSpeedBonus = 0f;
    private float jumpMult = 1f;
    private float gravMult = 1f;
    private float reachMult = 1f;
    private float guardPct = 0f;
    private float thornsPct = 0f;
    private float lifestealPct = 0f;
    private int aegisTicks = 0;
    private float knuckleDist = 0f;
    private int warcryTicks = 0;
    private float regenPctPerSec = 0f;
    private float adrenalinePct = 0f;
    private float secondWindPct = 0f;
    private float bulwarkPct = 0f;
    private float conjureCdMult = 1f;
    private float twinChance = 0f;
    private float tranceBonus = 0f;
    private float slipBonus = 0f;
    private float waveChance = 0f;
    private int waveMaxLv = 0;
    private float surgeChance = 0f;
    private int surgeMaxLv = 0;
    private float offerUpChance = 0f;
    private int teleportRank = 0;
    private float scorchPct = 0f;
    private float breakerPoise = 0f;
    private float suppressionPct = 0f;
    private float skirmisherPct = 0f;
    private float impactRelayPct = 0f;
    private float acrobatPct = 0f;
    private float conduitPct = 0f;
    private float relentlessChance = 0f;
    private float temporalDebtPct = 0f;
    private int architectRank = 0;

    private int momentumStacks;
    private int momentumIdle;
    private int adrenalineTicks;
    private boolean secondWindUsed;
    private int rouletteBuffTicks;
    private boolean panicUsedStage;
    private int staticTimer;
    private boolean staticCharged;
    private int rewindCooldown;
    private boolean healthy;

    public AdventureCoreState() {
        resetSeen();
        recompute();
    }

    public synchronized void clear() {
        owned.clear();
        uniques.clear();
        resetSeen();
        coinDmgPct = 0f;
        coinHpPct = 0f;
        momentumStacks = 0;
        momentumIdle = 0;
        adrenalineTicks = 0;
        secondWindUsed = false;
        rouletteBuffTicks = 0;
        panicUsedStage = false;
        staticTimer = 0;
        staticCharged = false;
        rewindCooldown = 0;
        recompute();
    }

    public float coinDmgPct() { return coinDmgPct; }
    public float coinHpPct() { return coinHpPct; }

    public synchronized void restore(List<AdventureCore> cores, float coinDmg, float coinHp) {
        restore(cores, coinDmg, coinHp, null);
    }

    public synchronized void restore(List<AdventureCore> cores, float coinDmg, float coinHp,
                                     List<AdventureCore> seenCores) {
        owned.clear();
        uniques.clear();
        resetSeen();
        coinDmgPct = coinDmg;
        coinHpPct = coinHp;
        momentumStacks = 0;
        momentumIdle = 0;
        adrenalineTicks = 0;
        secondWindUsed = false;
        rouletteBuffTicks = 0;
        panicUsedStage = false;
        staticTimer = 0;
        staticCharged = false;
        rewindCooldown = 0;
        if (cores != null) {
            for (AdventureCore c : cores) {
                if (c == null) continue;
                owned.add(c);
                if (c.unique) uniques.add(c.uid);

                if (seenCores == null) markSeenLocked(c);
            }
        }
        if (seenCores != null) {
            for (AdventureCore c : seenCores) markSeenLocked(c);
        }
        recompute();
    }

    public synchronized void add(AdventureCore core) {
        if (core == null) return;
        owned.add(core);
        if (core.unique) uniques.add(core.uid);
        markSeenLocked(core);

        if ("B1".equals(core.uid) && coinDmgPct == 0f && coinHpPct == 0f) {
            if (rng.nextBoolean()) coinDmgPct = 25f; else coinHpPct = 25f;
        }

        if ("L6".equals(core.uid)) upgradeAllTiers();

        if (core.family == F_REFINE) refineUpgrade((int) core.value, core);
        recompute();
    }

    private void refineUpgrade(int count, AdventureCore self) {
        AdventureCore.Tier[] tiers = AdventureCore.Tier.values();
        List<Integer> candidates = new ArrayList<Integer>();
        for (int i = 0; i < owned.size(); i++) {
            AdventureCore c = owned.get(i);
            if (c == self || c.unique || c.family < 0 || c.family == F_REFINE) continue;
            if (c.tier.ordinal() < tiers.length - 1) candidates.add(i);
        }
        for (int n = 0; n < count && !candidates.isEmpty(); n++) {
            int idx = candidates.remove(rng.nextInt(candidates.size()));
            AdventureCore c = owned.get(idx);
            owned.set(idx, AdventureCoreCatalog.of(c.family, tiers[c.tier.ordinal() + 1]));
        }
    }

    private void upgradeAllTiers() {
        AdventureCore.Tier[] tiers = AdventureCore.Tier.values();
        for (int i = 0; i < owned.size(); i++) {
            AdventureCore c = owned.get(i);
            if (c.unique || c.family < 0) continue;
            int next = Math.min(tiers.length - 1, c.tier.ordinal() + 1);
            if (next != c.tier.ordinal()) {
                owned.set(i, AdventureCoreCatalog.of(c.family, tiers[next]));
            }
        }
    }

    public synchronized List<AdventureCore> ownedList() {
        return new ArrayList<AdventureCore>(owned);
    }

    public int count() { return owned.size(); }

    public boolean hasUnique(String uid) { return uniques.contains(uid); }

    public synchronized void markSeen(AdventureCore[] offer) {
        if (offer == null) return;
        for (AdventureCore c : offer) markSeenLocked(c);
    }

    public synchronized boolean hasSeenFamilyTier(int family, AdventureCore.Tier tier) {
        if (family < 0 || family >= seenFamilyTierMask.length || tier == null) return false;
        return (seenFamilyTierMask[family] & (1 << tier.ordinal())) != 0;
    }

    public synchronized boolean hasSelectedFamily(int family) {
        if (family < 0 || family >= seenFamilyTierMask.length) return false;
        for (AdventureCore c : owned) {
            if (c != null && !c.unique && c.family == family) return true;
        }
        return false;
    }

    public synchronized boolean canOfferFamily(int family, AdventureCore.Tier tier) {
        if (tier == null || hasSeenFamilyTier(family, tier)) return false;
        return tier == AdventureCore.Tier.BRONZE || hasSelectedFamily(family);
    }

    public synchronized boolean hasSeenUnique(String uid) {
        return uid != null && seenUniqueTier.containsKey(uid);
    }

    public synchronized List<String> seenTokens() {
        List<String> out = new ArrayList<String>();
        AdventureCore.Tier[] tiers = AdventureCore.Tier.values();
        for (int i = 0; i < seenFamilyTierMask.length; i++) {
            for (int ord = 0; ord < tiers.length; ord++) {
                if ((seenFamilyTierMask[i] & (1 << ord)) != 0) {
                    out.add("F" + i + "@" + ord);
                }
            }
        }
        for (Map.Entry<String, Integer> e : seenUniqueTier.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.add(e.getKey() + "@" + e.getValue().intValue());
            }
        }
        return out;
    }

    private void resetSeen() {
        java.util.Arrays.fill(seenFamilyTierMask, 0);
        seenUniqueTier.clear();
    }

    private void markSeenLocked(AdventureCore core) {
        if (core == null) return;
        int ord = core.tier == null ? 0 : core.tier.ordinal();
        if (core.unique) {
            Integer old = seenUniqueTier.get(core.uid);
            if (old == null || ord > old.intValue()) seenUniqueTier.put(core.uid, ord);
        } else if (core.family >= 0 && core.family < seenFamilyTierMask.length) {
            seenFamilyTierMask[core.family] |= 1 << ord;
        }
    }

    public float hpMult() {
        return hpMult
                * (hasUnique("L1") ? 2f : 1f)
                * (hasUnique("P6") ? 0.4f : 1f);
    }
    public float hpFlatPct() { return hpFlatPct; }

    public float dmgMult() {
        return dmgMult
                * (hasUnique("L1") ? 1.5f : 1f)
                * (hasUnique("P6") ? 3f : 1f)
                * (rouletteBuffTicks > 0 ? 2f : 1f);
    }
    public float critChance() { return critChance; }
    public float execBonus() { return execBonus; }
    public float giantBonus() { return giantBonus; }
    public float berserkBonus() { return berserkBonus; }
    public float reachMult() { return reachMult; }
    public float guardPct() { return guardPct; }
    public float thornsPct() { return thornsPct; }
    public float lifestealPct() { return lifestealPct; }
    public int aegisTicks() { return aegisTicks; }
    public float knuckleDist() { return knuckleDist; }
    public int warcryTicks() { return warcryTicks; }
    public float regenPctPerSec() { return regenPctPerSec; }
    public float secondWindPct() { return secondWindPct; }
    public float bulwarkPct() { return bulwarkPct; }
    public float conjureCdMult() { return conjureCdMult; }
    public float twinChance() { return twinChance; }

    public float moveMult() {
        return moveMult
                * (healthy && slipBonus > 0f ? 1f + slipBonus : 1f)
                * (adrenalineTicks > 0 ? 1f + adrenalinePct : 1f)
                * (hasUnique("L2") ? 2f : 1f);
    }

    public float atkSpeedBonus() {
        return atkSpeedBonus
                + (healthy ? tranceBonus : 0f)
                + (adrenalineTicks > 0 ? adrenalinePct : 0f)
                + (hasUnique("L2") ? 1f : 0f);
    }

    void setHealthy(boolean h) { healthy = h; }

    public float jumpMult() { return jumpMult; }
    public float gravMult() { return gravMult; }

    public float momentumMult() {
        return 1f + momentumPer * momentumStacks;
    }

    void onPlayerHitLanded() {
        if (momentumPer > 0f) {
            momentumStacks = Math.min(10, momentumStacks + 1);
            momentumIdle = 60;
        }
    }

    void onEnemyKilled() {
        if (adrenalinePct > 0f) adrenalineTicks = 90;
    }

    void tickTransients() {
        if (momentumIdle > 0 && --momentumIdle == 0) momentumStacks = 0;
        if (adrenalineTicks > 0) adrenalineTicks--;
        if (rouletteBuffTicks > 0) rouletteBuffTicks--;
        if (rewindCooldown > 0) rewindCooldown--;

        if (hasUnique("S1") && !staticCharged && ++staticTimer >= 150) {
            staticTimer = 0;
            staticCharged = true;
        }
    }

    boolean staticCharged() { return staticCharged; }

    void rouletteSurge(int ticks) { rouletteBuffTicks = ticks; }

    public void onStageStart() {
        secondWindUsed = false;
        momentumStacks = 0;
        momentumIdle = 0;
        adrenalineTicks = 0;
        rouletteBuffTicks = 0;
        panicUsedStage = false;
        staticTimer = 0;
        staticCharged = false;
        rewindCooldown = 0;
    }

    boolean trySecondWind() {
        if (secondWindPct <= 0f || secondWindUsed) return false;
        secondWindUsed = true;
        return true;
    }

    boolean tryPanicBlast() {
        if (!hasUnique("B2") || panicUsedStage) return false;
        panicUsedStage = true;
        return true;
    }

    boolean consumeStaticCharge() {
        if (!staticCharged) return false;
        staticCharged = false;
        return true;
    }

    boolean tryRewind() {
        if (!hasUnique("P7") || rewindCooldown > 0) return false;
        if (rng.nextFloat() >= 0.30f) return false;
        rewindCooldown = 150;
        return true;
    }

    private void recompute() {
        float hp = 0, hpf = 0, dmg = 0, crit = 0, exec = 0, giant = 0, bers = 0, mom = 0;
        float move = 0, atk = 0, jump = 0, grav = 0, reach = 0;
        float guard = 0, thorns = 0, steal = 0, knuckle = 0, regen = 0, adr = 0, sw = 0, bul = 0, cd = 0, twin = 0;
        float trance = 0, slip = 0, wave = 0, surge = 0, fore = 0, teleport = 0;
        float scorch = 0, breaker = 0, suppression = 0, skirmisher = 0, relay = 0;
        float acrobat = 0, conduit = 0, relentless = 0, debt = 0, architect = 0;
        int aegis = 0, warcry = 0, waveLv = 0, surgeLv = 0;
        for (AdventureCore c : owned) {
            float v = c.value;
            switch (c.family) {
                case F_VITALITY:     hp += v; break;
                case F_TITAN_BLOOD:  hpf += v; break;
                case F_MIGHT:        dmg += v; break;
                case F_CRIT:         crit += v; break;
                case F_EXECUTIONER:  exec += v; break;
                case F_GIANT_SLAYER: giant += v; break;
                case F_BERSERKER:    bers += v; break;
                case F_MOMENTUM:     mom += v; break;
                case F_SWIFTNESS:    move += v; break;
                case F_HASTE:        atk += v; break;
                case F_SKY_LEAP:     jump += v; break;
                case F_FEATHERFALL:  grav += v; break;
                case F_LONG_ARMS:    reach += v; break;
                case F_GUARD:        guard += v; break;
                case F_THORNS:       thorns += v; break;
                case F_LIFESTEAL:    steal += v; break;
                case F_AEGIS:        aegis += (int) v; break;
                case F_IRON_KNUCKLE: knuckle += v; break;
                case F_WARCRY:       warcry += (int) v; break;
                case F_REGEN:        regen += v; break;
                case F_ADRENALINE:   adr += v; break;
                case F_SECOND_WIND:  sw += v; break;
                case F_BULWARK:      bul += v; break;
                case F_SUMMONER:     cd += v; break;
                case F_TWIN_SOUL:    twin += v; break;
                case F_FRENZY:       atk += v; break;
                case F_TRANCE:       trance += v; break;
                case F_SPRINT:       move += v; break;
                case F_SLIPSTREAM:   slip += v; break;
                case F_WAVE:
                    wave += v;
                    waveLv = Math.max(waveLv, c.tier.ordinal() + 1);
                    break;
                case F_SURGE:
                    surge += v;
                    surgeLv = Math.max(surgeLv, c.tier.ordinal() + 1);
                    break;
                case F_TELEPORT:     teleport += v; break;
                case F_SCORCH:       scorch += v; break;
                case F_BREAKER:      breaker += v; break;
                case F_SUPPRESSOR:   suppression += v; break;
                case F_SKIRMISHER:   skirmisher += v; break;
                case F_IMPACT_RELAY: relay += v; break;
                case F_ACROBAT:      acrobat += v; break;
                case F_CONDUIT:      conduit += v; break;
                case F_RELENTLESS:   relentless += v; break;
                case F_TEMPORAL_DEBT: debt += v; break;
                case F_ARCHITECT:    architect += v; break;
                case F_FORESIGHT:    fore += v; break;
                case F_REFINE:       break;
                default: break;
            }
        }
        hpMult = 1f + (hp + coinHpPct) / 100f;
        hpFlatPct = hpf / 100f;
        dmgMult = 1f + (dmg + coinDmgPct) / 100f;
        critChance = Math.min(0.9f, crit / 100f);
        execBonus = exec / 100f;
        giantBonus = giant / 100f;
        berserkBonus = bers / 100f;
        momentumPer = mom / 100f;
        moveMult = 1f + move / 100f;
        atkSpeedBonus = atk / 100f;
        jumpMult = 1f + jump / 100f;
        gravMult = Math.max(0.2f, 1f - grav / 100f);
        reachMult = 1f + reach / 100f;
        guardPct = Math.min(0.85f, guard / 100f);
        thornsPct = thorns / 100f;
        lifestealPct = steal / 100f;
        aegisTicks = aegis;
        knuckleDist = knuckle;
        warcryTicks = warcry;
        regenPctPerSec = regen;
        adrenalinePct = adr / 100f;
        secondWindPct = Math.min(100f, sw);
        bulwarkPct = Math.min(0.9f, bul / 100f);
        conjureCdMult = Math.max(0.25f, 1f - cd / 100f);
        twinChance = Math.min(1f, twin / 100f);
        tranceBonus = trance / 100f;
        slipBonus = slip / 100f;
        waveChance = Math.min(0.8f, wave / 100f);
        waveMaxLv = waveLv;
        surgeChance = Math.min(0.8f, surge / 100f);
        surgeMaxLv = surgeLv;
        offerUpChance = Math.min(0.6f, fore / 100f);
        teleportRank = Math.max(0, Math.min(5, Math.round(teleport)));
        scorchPct = Math.min(1.5f, scorch / 100f);
        breakerPoise = Math.min(100f, breaker);
        suppressionPct = Math.min(0.6f, suppression / 100f);
        skirmisherPct = Math.min(1f, skirmisher / 100f);
        impactRelayPct = Math.min(0.9f, relay / 100f);
        acrobatPct = Math.min(1f, acrobat / 100f);
        conduitPct = Math.min(0.6f, conduit / 100f);
        relentlessChance = Math.min(1f, relentless / 100f);
        temporalDebtPct = Math.min(0.8f, debt / 100f);
        architectRank = Math.max(0, Math.min(5, Math.round(architect)));
    }

    public float waveChance() { return waveChance; }
    public int waveMaxLv() { return waveMaxLv; }
    public float surgeChance() { return surgeChance; }
    public int surgeMaxLv() { return surgeMaxLv; }
    public float offerUpChance() { return offerUpChance; }
    public int teleportRank() { return teleportRank; }
    public float scorchPct() { return scorchPct; }
    public float breakerPoise() { return breakerPoise; }
    public float suppressionPct() { return suppressionPct; }
    public float skirmisherPct() { return skirmisherPct; }
    public float impactRelayPct() { return impactRelayPct; }
    public float acrobatPct() { return acrobatPct; }
    public float conduitPct() { return conduitPct; }
    public float relentlessChance() { return relentlessChance; }
    public float temporalDebtPct() { return temporalDebtPct; }
    public int architectRank() { return architectRank; }
}
