package com.irishrail.model;

public enum DelayCategory {

    ON_TIME      ("On Time",       0,  3,  "#3ecf73", "rgba(62,207,115,.18)",  "rgba(62,207,115,.35)"),
    SMALL_DELAY  ("Small Delay",   4,  9,  "#60a5fa", "rgba(96,165,250,.18)",  "rgba(96,165,250,.35)"),
    MEDIUM_DELAY ("Medium Delay", 10, 19,  "#f59e0b", "rgba(245,158,11,.18)",  "rgba(245,158,11,.35)"),
    BIG_DELAY    ("Big Delay",    20, 39,  "#f97316", "rgba(249,115,22,.18)",  "rgba(249,115,22,.35)"),
    EXTREME_DELAY("Extreme Delay",40, Integer.MAX_VALUE, "#f87171", "rgba(248,113,113,.18)", "rgba(248,113,113,.35)");

    private final String label;
    private final int    minMinutes;
    private final int    maxMinutes;
    private final String textColor;
    private final String bgColor;
    private final String borderColor;

    DelayCategory(String label, int minMinutes, int maxMinutes,
                  String textColor, String bgColor, String borderColor) {
        this.label       = label;
        this.minMinutes  = minMinutes;
        this.maxMinutes  = maxMinutes;
        this.textColor   = textColor;
        this.bgColor     = bgColor;
        this.borderColor = borderColor;
    }

    /** Minimum minutes to be counted as a delayed trip across the whole system. */
    public static int delayedThreshold() { return SMALL_DELAY.minMinutes; }

    public static DelayCategory of(int lateMinutes) {
        for (DelayCategory c : values()) {
            if (lateMinutes >= c.minMinutes && lateMinutes <= c.maxMinutes) return c;
        }
        return EXTREME_DELAY;
    }

    /** Human-readable label including the time range, e.g. "Small Delay (5–9 min)". */
    public String getDisplayLabel() {
        if (this == ON_TIME) return label;
        if (maxMinutes == Integer.MAX_VALUE) return label + " (" + minMinutes + "+ min)";
        return label + " (" + minMinutes + "–" + maxMinutes + " min)";
    }

    public boolean isOnTime()  { return this == ON_TIME; }
    public boolean isDelayed() { return this != ON_TIME; }

    public String getLabel()       { return label; }
    public String getTextColor()   { return textColor; }
    public String getBgColor()     { return bgColor; }
    public String getBorderColor() { return borderColor; }
    public int    getMinMinutes()  { return minMinutes; }
    public int    getMaxMinutes()  { return maxMinutes; }
}
