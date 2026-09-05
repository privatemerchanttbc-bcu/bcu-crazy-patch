package manualcontrol.adventure;

import common.CommonStatic;
import common.battle.StageBasis;
import common.battle.data.MaskEntity;
import common.battle.entity.EUnit;
import common.pack.Identifier;
import common.util.Data;
import common.util.unit.EForm;
import common.util.unit.Level;
import common.util.unit.Unit;
import manualcontrol.Logger;
import manualcontrol.reflect.BCUFields;

import java.util.Arrays;
import java.util.Random;

final class AdventureConjure {

    static final int COOLDOWN_TICKS = 150;

    private final Random rnd = new Random();
    private int cooldown;

    void tick() {
        if (cooldown > 0) cooldown--;
    }

    int cooldownTicks() { return cooldown; }

    boolean tryConjure(StageBasis sb, EUnit player) {
        if (cooldown > 0 || sb == null || player == null) return false;
        try {
            Data.Proc.SPIRIT spirit = findSpirit(player);
            if (spirit == null || !spirit.exists()) return false;

            boolean facingRight = AdventureBridge.isAdventureFlipped(player);
            EUnit summoned = doConjure(sb, player, spirit, facingRight);
            if (summoned == null) return false;

            float twin = AdventureRuntime.cores().twinChance();
            if (twin > 0f && rnd.nextFloat() < twin) {
                doConjure(sb, player, spirit, facingRight);
            }

            cooldown = Math.max(15, Math.round(
                    COOLDOWN_TICKS * AdventureRuntime.cores().conjureCdMult()));
            try { CommonStatic.setSE(162); } catch (Throwable ignored) {}
            Logger.log("Adventure: native spirit conjured facing="
                    + (facingRight ? "right" : "left"));
            return true;
        } catch (Throwable t) {
            Logger.err("Adventure: conjure failed", t);
            return false;
        }
    }

    private static Data.Proc.SPIRIT findSpirit(EUnit player) {
        try {
            MaskEntity data = (MaskEntity) BCUFields.get(player, "data");
            if (data == null) return null;
            Data.Proc proc = data.getProc();
            if (proc != null && proc.SPIRIT != null && proc.SPIRIT.exists()) {
                return proc.SPIRIT;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private EUnit doConjure(StageBasis sb, EUnit player, Data.Proc.SPIRIT proc,
                            boolean facingRight) {
        Unit unit = Identifier.getOr(proc.id, Unit.class);
        if (unit == null || unit.forms == null || unit.forms.length == 0
                || unit.forms[0] == null) {
            return null;
        }

        Level source = AdventureRuntime.level();
        Level spiritLevel = source == null ? new Level(30) : source.clone();
        int totalLevel = source == null ? 30 : source.getLv() + source.getPlusLv();
        spiritLevel.setLevel(Math.min(unit.max, Math.max(1, totalLevel)));
        spiritLevel.setPlusLevel(0);
        spiritLevel.setOrbs(null);
        if (spiritLevel.getTalents() != null) {
            Arrays.fill(spiritLevel.getTalents(), 0);
        }

        EForm ef = new EForm(unit.forms[0], spiritLevel);
        EUnit summoned = ef.getEntity(sb, null, true, false);

        float behind = player.pos + (facingRight ? -150f : 150f);
        float minX = sb.ebase.pos + summoned.data.getRange();
        float spawnX = Math.max(minX, Math.min(behind, sb.ubase.pos));
        summoned.added(-1, spawnX);
        AdventureBridge.registerConjuredSpirit(summoned, facingRight);
        AdventureBattle.addEntitySorted(sb, summoned);
        return summoned;
    }
}
