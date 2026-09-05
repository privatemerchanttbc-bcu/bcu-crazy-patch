package manualcontrol.adventure;

public enum AdventureLandingVfx {

    CRYSTAL(
            "crystal",
            "Crystal Seismic Crown",
            "Cyan crystal crown, fractured ground and clean seismic rings."),
    SOLAR(
            "solar",
            "Solar Meteor Impact",
            "White-hot impact beam, golden pressure rings and blazing debris."),
    VOID(
            "void",
            "Void Thunderquake",
            "Violet shock rings, cyan lightning and dark energy blades.");

    public final String id;
    public final String displayName;
    public final String description;

    AdventureLandingVfx(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public static AdventureLandingVfx fromId(String id) {
        if (id != null) {
            for (AdventureLandingVfx style : values()) {
                if (style.id.equalsIgnoreCase(id.trim())) return style;
            }
        }
        return CRYSTAL;
    }
}
