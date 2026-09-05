package manualcontrol.adventure;

import common.pack.PackData;
import common.pack.UserProfile;
import common.util.unit.Form;
import common.util.unit.Unit;
import manualcontrol.Logger;

import java.util.ArrayList;
import java.util.List;

public final class AdventureSaveData {

    public static final String MODE_CASUAL = "CASUAL";
    public static final String MODE_IRONMAN = "IRONMAN";

    public int slot = -1;
    public long savedAt;
    public String mode = MODE_CASUAL;

    public String unitPack = "";
    public int unitId = -1;
    public int unitForm = 0;
    public String unitLabel = "";

    public int levelIndex;
    public String stageName = "";

    public int lives = 1;
    public float coinDmg;
    public float coinHp;
    public String landingVfx = AdventureLandingVfx.CRYSTAL.id;
    public String customMapId = "";

    public final List<String> coreTokens = new ArrayList<String>();
    public final List<String> seenCoreTokens = new ArrayList<String>();

    public boolean isIronman() { return MODE_IRONMAN.equals(mode); }

    public int stageNumber() { return levelIndex + 1; }

    public boolean hasCore(String uid) {
        if (uid == null || uid.isEmpty()) return false;
        for (String token : coreTokens) {
            if (token == null) continue;
            int at = token.indexOf('@');
            String tokenUid = at < 0 ? token : token.substring(0, at);
            if (uid.equals(tokenUid)) return true;
        }
        return false;
    }

    public Form resolveForm() {
        try {
            for (PackData pack : UserProfile.getAllPacks()) {
                if (pack == null || pack.units == null) continue;
                List<Unit> units;
                try {
                    units = pack.units.getList();
                } catch (Throwable t) {
                    continue;
                }
                if (units == null) continue;
                for (Unit u : units) {
                    if (u == null || u.id == null || u.forms == null) continue;
                    if (u.id.id != unitId) continue;
                    if (unitPack != null && !unitPack.equals(u.id.pack)) continue;
                    if (unitForm >= 0 && unitForm < u.forms.length) {
                        Form f = u.forms[unitForm];
                        if (f != null && f.du != null && f.unit != null) return f;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.err("Adventure: resolveForm failed for " + unitPack + ":" + unitId, t);
        }
        return null;
    }

    public List<AdventureCore> toCores() {
        return parseCoreTokens(coreTokens);
    }

    public List<AdventureCore> toSeenCores() {
        return parseCoreTokens(seenCoreTokens);
    }

    private static List<AdventureCore> parseCoreTokens(List<String> tokens) {
        List<AdventureCore> out = new ArrayList<AdventureCore>();
        AdventureCore.Tier[] tiers = AdventureCore.Tier.values();
        for (String token : tokens) {
            if (token == null) continue;
            int at = token.indexOf('@');
            if (at <= 0 || at >= token.length() - 1) continue;
            String uid = token.substring(0, at);
            int ord;
            try {
                ord = Integer.parseInt(token.substring(at + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (ord < 0 || ord >= tiers.length) continue;
            AdventureCore c = AdventureCoreCatalog.byId(uid, tiers[ord]);
            if (c != null) out.add(c);
        }
        return out;
    }

    public String displayLine() {
        String stage = stageName == null || stageName.trim().isEmpty()
                ? "Stage " + stageNumber() : stageName;
        return unitLabel + "  |  " + stage + "  |  " + lives + " lives  |  "
                + coreTokens.size() + " cores  |  " + (isIronman() ? "IRONMAN" : "CASUAL");
    }
}
