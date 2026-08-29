package it.alessandropezzali.navguard.integrity;

/**
 * One component of the GNSS Integrity Score.
 * A component that cannot be measured is UNAVAILABLE, never zero: it is dropped from the
 * weighted mean and the remaining weights are renormalised.
 */
public final class SubScore {
    public final String name;
    public final boolean available;
    public final double value;

    private SubScore(String name, boolean available, double value) {
        this.name = name;
        this.available = available;
        this.value = value;
    }

    public static SubScore of(String name, double value) {
        return new SubScore(name, true, GeoMath.clamp(value, 0.0, 100.0));
    }

    public static SubScore unavailable(String name) {
        return new SubScore(name, false, Double.NaN);
    }

    public int rounded() {
        return available ? (int) Math.round(value) : -1;
    }
}
