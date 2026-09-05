package manualcontrol.adventure;

public final class AdventureCore {

    public enum Tier {
        BRONZE("BRONZE", 0xB8, 0x73, 0x33),
        SILVER("SILVER", 0xC7, 0xD0, 0xDA),
        GOLD("GOLD", 0xFF, 0xD2, 0x4A),
        PLATINUM("PLATINUM", 0x9F, 0xE8, 0xFF),
        LEGEND("LEGEND", 0xC3, 0x6B, 0xFF);

        public final String label;
        public final int r, g, b;

        Tier(String label, int r, int g, int b) {
            this.label = label;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    public final int family;

    public final String uid;
    public final Tier tier;
    public final String name;

    public final String magText;

    public final String desc1, desc2;

    public final String glyph;

    public final float value;
    public final boolean unique;

    AdventureCore(int family, String uid, Tier tier, String name, String magText,
                  String desc1, String desc2, String glyph, float value, boolean unique) {
        this.family = family;
        this.uid = uid;
        this.tier = tier;
        this.name = name;
        this.magText = magText;
        this.desc1 = desc1;
        this.desc2 = desc2;
        this.glyph = glyph;
        this.value = value;
        this.unique = unique;
    }

    @Override
    public String toString() {
        return tier.label + " " + name + " (" + magText + ")";
    }
}
