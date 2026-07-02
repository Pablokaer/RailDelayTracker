package com.irishrail.model;

public final class ServiceScope {
    public static final String CONNOLLY = "CONNOLLY";
    public static final String HEUSTON = "HEUSTON";

    private ServiceScope() {}

    public static String fromOverviewCode(String code) {
        if ("HSTON".equalsIgnoreCase(code) || HEUSTON.equalsIgnoreCase(code)) return HEUSTON;
        if ("CNLLY".equalsIgnoreCase(code) || CONNOLLY.equalsIgnoreCase(code)) return CONNOLLY;
        return null;
    }

    public static String overviewCode(String scope) {
        if (HEUSTON.equalsIgnoreCase(scope)) return "HSTON";
        if (CONNOLLY.equalsIgnoreCase(scope)) return "CNLLY";
        return "OVERVIEW";
    }
}
