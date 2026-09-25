package me.sshcrack.tc_townhall;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A problem of the whole colony that a mayor can promise to tackle, measured by how many citizens
 * MineColonies counts as affected (their happiness modifier is below neutral).
 */
public enum Need {
    HOUSING("housing", "homelessness", "homes", "without a proper home", List.of("residence")),
    WORK("work", "unemployment", "jobs", "without work", List.of()),
    FOOD("food", "food", "food", "poorly fed", List.of("cook", "kitchen", "farmer")),
    HEALTH("health", "health", "health care", "sick", List.of("hospital")),
    SAFETY("safety", "security", "safety", "feeling unsafe", List.of("guardtower", "barracks")),
    SUPPLIES("supplies", "idleatjob", "work supplies", "stuck at work without what they need", List.of("warehouse", "deliveryman"));

    private final String id;
    private final String modifier;
    private final String label;
    private final String affected;
    private final List<String> buildings;

    Need(String id, String modifier, String label, String affected, List<String> buildings) {
        this.id = id;
        this.modifier = modifier;
        this.label = label;
        this.affected = affected;
        this.buildings = buildings;
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

    /** How the affected citizens are, e.g. "without a proper home". */
    public String affected() {
        return affected;
    }

    /** MineColonies building types that help, the one to build first. Empty when no building does. */
    public List<String> buildings() {
        return buildings;
    }

    public static @Nullable Need byId(@Nullable String id) {
        for (Need need : values()) {
            if (need.id.equals(id)) return need;
        }
        return null;
    }

    /** "3 citizens are without a proper home", "one citizen is sick". */
    public String describe(int count) {
        return (count == 1 ? "one citizen is " : count + " citizens are ") + affected;
    }
}
