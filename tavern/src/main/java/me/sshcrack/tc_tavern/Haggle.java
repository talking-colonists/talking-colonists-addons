package me.sshcrack.tc_tavern;

/**
 * The limits of haggling over a visitor's recruit cost, as pure arithmetic. A visitor never goes
 * below half the original amount, drops at most a quarter of it per step, and changes the price at
 * most {@link #MAX_CHANGES_PER_DAY} times a day.
 */
public final class Haggle {
    public static final int MAX_CHANGES_PER_DAY = 3;

    private Haggle() {
    }

    /** The lowest amount a visitor ever accepts. */
    public static int floor(int original) {
        return Math.max(1, (original + 1) / 2);
    }

    /** The largest drop allowed in one step. */
    public static int maxStep(int original) {
        return Math.max(1, (original + 3) / 4);
    }

    /** Outcome of a request: the new amount and whether the request was cut to the limits. */
    public record Result(int amount, boolean limited, String note) {
    }

    /**
     * The amount after the model asked for {@code requested}, given the {@code original} and
     * {@code current} amounts. Never raises the price.
     */
    public static Result apply(int original, int current, int requested) {
        if (requested >= current) return new Result(current, false, "The price stays at " + current + ".");
        int lowest = Math.max(floor(original), current - maxStep(original));
        if (requested >= lowest) return new Result(requested, false, "The price drops to " + requested + ".");
        if (lowest >= current) {
            return new Result(current, true, "This is already the lowest price this visitor accepts.");
        }
        return new Result(lowest, true, "The visitor only goes down to " + lowest
                + (lowest == floor(original) ? ", the lowest they will ever accept." : " for now."));
    }
}
