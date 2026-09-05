package manualcontrol.adventure;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class AdventureCoreCatalog {

    public static final int F_VITALITY = 0;
    public static final int F_TITAN_BLOOD = 1;
    public static final int F_MIGHT = 2;
    public static final int F_CRIT = 3;
    public static final int F_EXECUTIONER = 4;
    public static final int F_GIANT_SLAYER = 5;
    public static final int F_BERSERKER = 6;
    public static final int F_MOMENTUM = 7;
    public static final int F_SWIFTNESS = 8;
    public static final int F_HASTE = 9;
    public static final int F_SKY_LEAP = 10;
    public static final int F_FEATHERFALL = 11;
    public static final int F_LONG_ARMS = 12;
    public static final int F_GUARD = 13;
    public static final int F_THORNS = 14;
    public static final int F_LIFESTEAL = 15;
    public static final int F_AEGIS = 16;
    public static final int F_IRON_KNUCKLE = 17;
    public static final int F_WARCRY = 18;
    public static final int F_REGEN = 19;
    public static final int F_ADRENALINE = 20;
    public static final int F_SECOND_WIND = 21;
    public static final int F_BULWARK = 22;
    public static final int F_SUMMONER = 23;
    public static final int F_TWIN_SOUL = 24;
    public static final int F_FRENZY = 25;
    public static final int F_TRANCE = 26;
    public static final int F_SPRINT = 27;
    public static final int F_SLIPSTREAM = 28;
    public static final int F_REFINE = 29;
    public static final int F_FORESIGHT = 30;
    public static final int F_WAVE = 31;
    public static final int F_SURGE = 32;
    public static final int F_TELEPORT = 33;
    public static final int F_SCORCH = 34;
    public static final int F_BREAKER = 35;
    public static final int F_SUPPRESSOR = 36;
    public static final int F_SKIRMISHER = 37;
    public static final int F_IMPACT_RELAY = 38;
    public static final int F_ACROBAT = 39;
    public static final int F_CONDUIT = 40;
    public static final int F_RELENTLESS = 41;
    public static final int F_TEMPORAL_DEBT = 42;
    public static final int F_ARCHITECT = 43;

    private static final class Family {
        final String name, glyph, d1, d2, magFmt;
        final float[] vals;
        boolean impl;

        Family(String name, String glyph, String magFmt, String d1, String d2, float[] vals, boolean impl) {
            this.name = name;
            this.glyph = glyph;
            this.magFmt = magFmt;
            this.d1 = d1;
            this.d2 = d2;
            this.vals = vals;
            this.impl = impl;
        }
    }

    private static final Family[] FAMILIES = {
        new Family("Vitality Core", "♥", "+%.0f%% Max HP",
                "Raises maximum HP.", "Bonus HP is healed on pickup.",
                new float[]{10, 20, 35, 55, 80}, true),
        new Family("Titan Blood", "♦", "+%.0f%% Base HP",
                "Adds HP scaled from the", "form's original health.",
                new float[]{15, 30, 50, 75, 110}, true),
        new Family("Might Core", "⚔", "+%.0f%% Damage",
                "All attacks deal", "more damage.",
                new float[]{10, 20, 35, 55, 80}, true),
        new Family("Crit Core", "✖", "%.0f%% Crit Chance",
                "Attacks may critically", "hit for double damage.",
                new float[]{5, 10, 15, 22, 30}, true),
        new Family("Executioner", "☠", "+%.0f%% vs Low HP",
                "Bonus damage to enemies", "below 25% health.",
                new float[]{15, 30, 50, 75, 110}, true),
        new Family("Giant Slayer", "♚", "+%.0f%% vs Bosses",
                "Bonus damage against", "boss enemies.",
                new float[]{10, 20, 35, 55, 80}, true),
        new Family("Berserker", "❗", "+%.0f%% Rage Damage",
                "Bonus damage while your", "HP is below 30%.",
                new float[]{20, 40, 65, 95, 140}, true),
        new Family("Momentum", "▲", "+%.0f%%/Hit Stacking",
                "Consecutive hits stack", "damage, up to 10 stacks.",
                new float[]{2, 4, 6, 9, 13}, true),
        new Family("Swiftness", "➤", "+%.0f%% Move Speed",
                "Run faster across", "the battlefield.",
                new float[]{8, 16, 26, 38, 52}, true),
        new Family("Haste Core", "⚙", "+%.0f%% Attack Speed",
                "Attack animation and", "damage resolve faster.",
                new float[]{10, 20, 32, 46, 62}, true),
        new Family("Sky Leap", "↑", "+%.0f%% Jump Power",
                "Jump higher and dodge", "longer in the air.",
                new float[]{10, 20, 32, 46, 62}, true),
        new Family("Featherfall", "☂", "-%.0f%% Gravity",
                "Fall slower - drift", "over enemy attacks.",
                new float[]{10, 18, 28, 40, 54}, true),
        new Family("Long Arms", "→", "+%.0f%% Reach",
                "Extends your attack", "reach on both sides.",
                new float[]{8, 15, 24, 35, 48}, true),
        new Family("Guard Core", "⛨", "-%.0f%% Damage Taken",
                "Reduces all incoming", "damage.",
                new float[]{8, 15, 24, 35, 48}, true),
        new Family("Thorns", "✧", "%.0f%% Reflect",
                "Reflects damage taken", "back to the attacker.",
                new float[]{10, 20, 35, 55, 80}, true),
        new Family("Lifesteal", "♡", "%.0f%% Lifesteal",
                "Heal for a portion of", "damage you deal.",
                new float[]{3, 6, 10, 15, 22}, true),
        new Family("Aegis", "⛓", "%.0ft Invulnerable",
                "Brief invulnerability", "after taking a hit.",
                new float[]{10, 16, 24, 34, 46}, true),
        new Family("Iron Knuckle", "✊", "+%.0f Knockback",
                "Hits shove enemies", "farther away.",
                new float[]{20, 40, 65, 95, 140}, true),
        new Family("Warcry", "♪", "%.0ft Slow on Hit",
                "Hits slow enemies", "for a short time.",
                new float[]{15, 25, 40, 60, 85}, true),
        new Family("Regeneration", "⚕", "+%.1f%% HP/sec",
                "Steadily regenerate", "health over time.",
                new float[]{0.5f, 1f, 1.7f, 2.6f, 3.8f}, true),
        new Family("Adrenaline", "⚡", "+%.0f%% After Kill",
                "Kills grant attack and", "move speed for 3s.",
                new float[]{10, 18, 28, 40, 55}, true),
        new Family("Second Wind", "♻", "Revive at %.0f%% HP",
                "Once per stage: survive", "death and revive in place.",
                new float[]{20, 30, 45, 60, 80}, true),
        new Family("Bulwark", "■", "-%.0f%% Knockback",
                "Resist knockback -", "hold your ground.",
                new float[]{20, 35, 50, 68, 85}, true),
        new Family("Summoner", "☆", "-%.0f%% Conjure CD",
                "Conjure (L) recharges", "faster.",
                new float[]{10, 20, 32, 46, 62}, true),
        new Family("Twin Soul", "∞", "%.0f%% Twin Conjure",
                "Conjure may summon an", "extra copy.",
                new float[]{15, 30, 50, 75, 100}, true),
        new Family("Frenzy Core", "♯", "+%.0f%% Attack Speed",
                "A second source of raw", "attack speed. Stacks.",
                new float[]{8, 16, 26, 38, 52}, true),
        new Family("Battle Trance", "♬", "+%.0f%% Atk Spd Healthy",
                "Attack much faster while", "your HP is above 70%.",
                new float[]{16, 30, 46, 66, 90}, true),
        new Family("Sprint Core", "»", "+%.0f%% Move Speed",
                "A second source of raw", "move speed. Stacks.",
                new float[]{10, 20, 32, 46, 62}, true),
        new Family("Slipstream", "≈", "+%.0f%% Speed Healthy",
                "Run much faster while", "your HP is above 70%.",
                new float[]{18, 34, 52, 74, 100}, true),
        new Family("Refine Core", "∆", "Upgrade %.0f Cores Now",
                "Instantly ascend random", "owned cores one tier.",
                new float[]{1, 1, 2, 2, 3}, true),
        new Family("Foresight Core", "◇", "%.0f%% Better Offers",
                "Future core cards may roll", "one tier above the stage.",
                new float[]{10, 18, 26, 34, 42}, true),
        new Family("Wave Core", "~", "%.0f%% Wave on Hit",
                "Hits may launch a piercing", "wave of a random level.",
                new float[]{15, 20, 25, 30, 35}, true),
        new Family("Surge Core", "^", "%.0f%% Surge on Hit",
                "Hits may erupt a surge near", "the target, random level.",
                new float[]{15, 20, 25, 30, 35}, true),
        new Family("Blink Core", "W", "Blink Rank %.0f",
                "Blink in your facing direction.", "The crossed path cuts foes.",
                new float[]{1, 2, 3, 4, 5}, true),
        new Family("Scorch Core", "*", "%.0f%% Burn Damage",
                "Direct hits ignite enemies.", "Burn pulses ten times over 3s.",
                new float[]{8, 14, 22, 34, 50}, true),
        new Family("Breaker Core", "#", "+%.0f Poise / Hit",
                "Direct hits break enemy poise.", "A full meter interrupts attacks.",
                new float[]{8, 13, 20, 30, 44}, true),
        new Family("Suppressor Core", "v", "-%.0f%% Enemy Damage",
                "Direct hits weaken enemy attacks.", "Further hits refresh the seal.",
                new float[]{4, 7, 11, 16, 23}, true),
        new Family("Skirmisher Core", ">", "%.0f%% Attack Move",
                "Move while an attack is active.", "Attack direction stays locked.",
                new float[]{10, 18, 28, 42, 60}, true),
        new Family("Impact Relay", "X", "%.0f%% Collision Damage",
                "Knocked enemies hurt each other.", "One relay hit per enemy pair.",
                new float[]{6, 10, 16, 24, 34}, true),
        new Family("Acrobat Core", "K", "%.0f%% Cancel Window",
                "Jump after the final damage frame", "to cancel attack recovery.",
                new float[]{10, 18, 28, 42, 60}, true),
        new Family("Conduit Core", "=", "%.0f%% Linked Damage",
                "Hits link up to five enemies.", "A swing conducts one extra hit.",
                new float[]{4, 7, 11, 16, 23}, true),
        new Family("Relentless Core", "R", "%.0f%% Preserve Attack",
                "Knockback may preserve your attack.", "Resume its exact timeline after impact.",
                new float[]{10, 18, 28, 42, 60}, true),
        new Family("Temporal Debt", "T", "%.0f%% Damage Deferred",
                "Delay part of incoming damage.", "Direct attacks erase oldest debt.",
                new float[]{8, 12, 18, 26, 36}, true),
        new Family("Architect Core", "S", "Architect Rank %.0f",
                "Deploy battlefield structures.", "Q/E select and S installs.",
                new float[]{1, 2, 3, 4, 5}, true),
    };

    private static final class Unique {
        final String uid, name, mag, d1, d2, glyph;
        boolean impl;

        Unique(String uid, String name, String mag, String d1, String d2, String glyph, boolean impl) {
            this.uid = uid;
            this.name = name;
            this.mag = mag;
            this.d1 = d1;
            this.d2 = d2;
            this.glyph = glyph;
            this.impl = impl;
        }
    }

    private static final Unique[] BRONZE_UNIQUES = {
        new Unique("B1", "Coin Flip", "50/50: +25% DMG or HP",
                "Fate decides on pickup:", "+25% damage OR +25% max HP.", "◐", true),
        new Unique("B2", "Panic Button", "Auto-Blast Under 20%",
                "First brush with death each", "stage blasts foes away.", "‼", true),
    };

    private static final Unique[] SILVER_UNIQUES = {
        new Unique("S1", "Static Charge", "4x Strike Every 5s",
                "A charge builds; your next", "attack strikes as lightning.", "↯", true),
        new Unique("S2", "Bouncy Castle", "Landings Shockwave",
                "Every jump landing damages", "enemies around you.", "◎", true),
    };

    private static final Unique[] GOLD_UNIQUES = {
        new Unique("G1", "Midas Touch", "Kills May Heal 5%",
                "20% of kills turn to gold,", "restoring your health.", "$", true),
        new Unique("G2", "Time Bubble", "Hits May Freeze Foes",
                "20% chance when struck to", "freeze nearby enemies.", "❄", true),
    };

    private static final Unique[] PLATINUM_UNIQUES = {
        new Unique("P1", "Double Jump", "Jump Again Mid-Air",
                "A second jump in the air,", "with fresh dodge frames.", "⇈", true),
        new Unique("P2", "Echo Strike", "Attacks Repeat Free",
                "Every attack echoes once,", "shortly after it ends.", "≋", true),
        new Unique("P3", "Death Nova", "Kills Explode",
                "Slain enemies detonate,", "chaining nearby foes.", "✹", true),
        new Unique("P4", "Time Dilation", "Enemies 30% Slower",
                "All enemies move and", "attack in slow motion.", "⌛", true),
        new Unique("P5", "Magnet Fist", "Hits Pull Enemies",
                "Strikes drag enemies", "toward you. Herd and sweep.", "⦿", true),
        new Unique("P6", "Glass Cannon", "3x DMG, 40% HP",
                "Overwhelming power in an", "eggshell body.", "✦", true),
        new Unique("P7", "Rewind", "30% Cheat Death",
                "On death: 30% chance to", "rewind five seconds.", "↺", true),
        new Unique("P8", "Afterimage", "Hits Leave Echo Clones",
                "Landed hits leave a blue ghost", "that repeats the strike.", "◈", true),
    };

    private static final Unique[] LEGEND_UNIQUES = {
        new Unique("L1", "Titan Core", "2x Size, HP & Power",
                "Become a giant: double", "size and HP, 1.5x damage.", "◆", true),
        new Unique("L2", "Overclock", "Act Twice As Fast",
                "You move, jump and attack", "at double time.", "≫", true),
        new Unique("L3", "Pandemonium", "25% Enemies Defect",
                "Some enemies spawn confused", "and fight for you.", "☯", true),
        new Unique("L4", "Chaos Quake", "Quake Every 10s",
                "Periodic quakes scatter", "every enemy on the field.", "☵", true),
        new Unique("L5", "Roulette", "Random Effect / 5s",
                "Heal, explode, self-harm,", "power surge... pure chaos.", "❈", true),
        new Unique("L6", "Golden God", "ALL Cores +1 Tier",
                "Every core you own ascends", "one tier, instantly.", "✪", true),
        new Unique("L7", "One Punch", "10% Instant Kill",
                "Each hit may delete a foe", "outright (bosses -10%).", "☄", true),
    };

    private AdventureCoreCatalog() {}

    private static final float UNIQUE_CHANCE = 0.30f;

    public static int familyCount() { return FAMILIES.length; }

    static AdventureCore of(int family, AdventureCore.Tier tier) {
        Family f = FAMILIES[family];
        float v = f.vals[tier.ordinal()];
        return new AdventureCore(family, "F" + family, tier, f.name,
                String.format(f.magFmt, v), f.d1, f.d2, f.glyph, v, false);
    }

    private static AdventureCore of(Unique u, AdventureCore.Tier tier) {
        return new AdventureCore(-1, u.uid, tier, u.name, u.mag, u.d1, u.d2, u.glyph, 0f, true);
    }

    public static AdventureCore[] rollOffer(AdventureCore.Tier tier, Random rnd, AdventureCoreState owned) {
        List<Integer> fams = new ArrayList<Integer>();
        for (int i = 0; i < FAMILIES.length; i++) {
            if (!FAMILIES[i].impl) continue;
            if (owned != null && !owned.canOfferFamily(i, tier)) continue;
            fams.add(i);
        }
        List<Unique> uniques = new ArrayList<Unique>();
        Unique[] pool;
        switch (tier) {
            case BRONZE:   pool = BRONZE_UNIQUES; break;
            case SILVER:   pool = SILVER_UNIQUES; break;
            case GOLD:     pool = GOLD_UNIQUES; break;
            case PLATINUM: pool = PLATINUM_UNIQUES; break;
            default:       pool = LEGEND_UNIQUES; break;
        }
        for (Unique u : pool) {
            if (u.impl && (owned == null || !owned.hasSeenUnique(u.uid))) uniques.add(u);
        }

        List<AdventureCore> offer = new ArrayList<AdventureCore>(3);
        List<Integer> famsLeft = new ArrayList<Integer>(fams);
        List<Unique> uniquesLeft = new ArrayList<Unique>(uniques);
        for (int i = 0; i < 3; i++) {
            if (!uniquesLeft.isEmpty() && rnd.nextFloat() < UNIQUE_CHANCE) {
                offer.add(of(uniquesLeft.remove(rnd.nextInt(uniquesLeft.size())), tier));
            } else if (!famsLeft.isEmpty()) {
                offer.add(of(famsLeft.remove(rnd.nextInt(famsLeft.size())), tier));
            } else if (!uniquesLeft.isEmpty()) {
                offer.add(of(uniquesLeft.remove(rnd.nextInt(uniquesLeft.size())), tier));
            }
        }
        return offer.isEmpty() ? null : offer.toArray(new AdventureCore[0]);
    }

    public static AdventureCore.Tier tierForStage(int completedStage) {
        int idx = Math.max(0, Math.min(4, (completedStage - 1) / 5));
        return AdventureCore.Tier.values()[idx];
    }

    public static AdventureCore byId(String uid, AdventureCore.Tier tier) {
        if (uid == null || tier == null) return null;
        if (uid.length() > 1 && uid.charAt(0) == 'F') {
            try {
                int fam = Integer.parseInt(uid.substring(1));
                if (fam >= 0 && fam < FAMILIES.length) return of(fam, tier);
            } catch (NumberFormatException ignored) {}
            return null;
        }
        for (Unique[] pool : new Unique[][]{
                BRONZE_UNIQUES, SILVER_UNIQUES, GOLD_UNIQUES, PLATINUM_UNIQUES, LEGEND_UNIQUES}) {
            for (Unique u : pool) {
                if (u.uid.equals(uid)) return of(u, tier);
            }
        }
        return null;
    }
}
