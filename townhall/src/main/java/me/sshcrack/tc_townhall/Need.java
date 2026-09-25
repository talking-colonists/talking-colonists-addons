package me.sshcrack.tc_townhall;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A problem of the whole colony that a mayor can promise to tackle, measured by how many citizens
 * MineColonies counts as affected (their happiness modifier is below neutral).
 */
public enum Need {
    // What helps, from MineColonies' happiness rules (docs/minecolonies-mechanics.md in the main repo):
    // a home counts fully from level 3; "security" only counts guards, and a guard tower always holds
    // one guard whatever its level, so only barracks and barracks towers gain guards by upgrading.
    HOUSING("housing", "homelessness", "homes", "homeless or in a home below level 3", List.of("residence"), List.of("residence")),
    WORK("work", "unemployment", "jobs", "without work", List.of(), List.of()),
    FOOD("food", "food", "food", "tired of plain, samey meals", List.of("cook", "kitchen", "farmer"), List.of("cook", "kitchen", "farmer")),
    HEALTH("health", "health", "health care", "sick", List.of("hospital"), List.of("hospital")),
    SAFETY("safety", "security", "safety", "feeling unsafe with too few guards", List.of("guardtower", "barracks"), List.of("barracks", "barrackstower")),
    SUPPLIES("supplies", "idleatjob", "work supplies", "stuck at work without what they need", List.of("warehouse", "deliveryman"),
            List.of("warehouse", "deliveryman"));

    private final String id;
    private final String modifier;
    private final String label;
    private final String affected;
    private final List<String> buildings;
    private final List<String> upgrades;

    Need(String id, String modifier, String label, String affected, List<String> buildings, List<String> upgrades) {
        this.id = id;
        this.modifier = modifier;
        this.label = label;
        this.affected = affected;
        this.buildings = buildings;
        this.upgrades = upgrades;
    }

    /** Saved and shown to the model, e.g. "housing". */
    public String id() {
        return id;
    }

    /** MineColonies' happiness modifier that measures it. */
    public String modifier() {
        return modifier;
    }

    /** What the colony needs, e.g. "homes". */
    public String label() {
        return label;
    }

    /** How the affected citizens are, e.g. "homeless or in a home below level 3". */
    public String affected() {
        return affected;
    }

    /** MineColonies building types that help when a new one is built, the one to build first. Empty when no building does. */
    public List<String> buildings() {
        return buildings;
    }

    /** MineColonies building types that help more with every level. */
    public List<String> upgrades() {
        return upgrades;
    }

    /** Whether building work on a hut of {@code type}, at {@code level} before the work, helps: a new hut or a useful upgrade. */
    public boolean helps(String type, int level) {
        return upgrades.contains(type) || level == 0 && buildings.contains(type);
    }

    /** Every building type the need cares about. */
    public boolean concerns(String type) {
        return buildings.contains(type) || upgrades.contains(type);
    }

    public static @Nullable Need byId(@Nullable String id) {
        for (Need need : values()) {
            if (need.id.equals(id)) return need;
        }
        return null;
    }

    /** "3 citizens are homeless or in a home below level 3", "one citizen is sick". */
    public String describe(int count) {
        return (count == 1 ? "one citizen is " : count + " citizens are ") + affected;
    }
}
