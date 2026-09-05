package manualcontrol.adventure;

public final class AdventureSfx {

    public static final int OVERLAY_OPEN = 22;
    public static final int CARD_LAND = 15;
    public static final int UNIQUE_STING = 90;
    public static final int SELECT_MOVE = 22;
    public static final int CONFIRM_BRONZE = 22;
    public static final int CONFIRM_SILVER = 25;
    public static final int CONFIRM_GOLD = 44;
    public static final int CONFIRM_PLATINUM = 70;
    public static final int CONFIRM_LEGEND = 90;
    public static final int CONFIRM_LEGEND_2 = 162;
    public static final int HUD_ICON_LAND = 15;

    private AdventureSfx() {}

    public static void play(int id) {
        try {
            common.CommonStatic.setSE(id);
        } catch (Throwable ignored) {}
    }

    public static void confirm(AdventureCore.Tier tier) {
        switch (tier) {
            case BRONZE:   play(CONFIRM_BRONZE); break;
            case SILVER:   play(CONFIRM_SILVER); break;
            case GOLD:     play(CONFIRM_GOLD); break;
            case PLATINUM: play(CONFIRM_PLATINUM); break;
            case LEGEND:   play(CONFIRM_LEGEND); play(CONFIRM_LEGEND_2); break;
        }
    }
}
