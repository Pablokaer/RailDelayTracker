package com.irishrail.model;

public final class DelayLimits {
    public static final int MAX_STAT_DELAY_MINUTES = 500;

    /**
     * Where "on time" ends. A compile-time constant (not a method) so native queries can inline it
     * and match the partial index in {@code DatabaseIndexInitializer}; {@link DelayCategory}
     * derives its first delayed band from the same value.
     */
    public static final int DELAYED_THRESHOLD_MINUTES = 5;

    private DelayLimits() {}
}
