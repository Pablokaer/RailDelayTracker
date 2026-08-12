package com.irishrail.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the Irish Rail {@code PublicMessage} blob into structured fields.
 *
 * <p>The API packs three lines into one string and separates them with a <em>literal</em>
 * backslash-n (two characters), not a newline — which is why naive whitespace cleaning leaves
 * {@code \n} visible in the UI. Observed shapes:
 *
 * <pre>
 * P524\nCobh to Cork\nExpected Departure 16:00
 * A220\n16:00 - Dublin Heuston to Cork (2 mins late)\nDeparted Inchicore next stop Thurles
 * A221\n15:25 - Cork to Dublin Heuston (-6 mins late)\nArrived LJ895 next stop Limerick Junction
 * </pre>
 */
public final class PublicMessageParser {

    private static final Pattern HTML_TAG    = Pattern.compile("<[^>]+>");
    private static final Pattern LATE        = Pattern.compile("\\((-?\\d+)\\s+mins?\\s+late\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHED_PREFIX = Pattern.compile("^(\\d{1,2}:\\d{2})\\s*-\\s*");
    private static final Pattern NEXT_STOP   = Pattern.compile("next\\s+stop\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVEMENT    = Pattern.compile("^(Departed|Arrived)\\s+(.+?)(?:\\s+next\\s+stop\\s+.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPECTED    = Pattern.compile("Expected\\s+Departure\\s+(\\d{1,2}:\\d{2})", Pattern.CASE_INSENSITIVE);

    private PublicMessageParser() {}

    /**
     * @param trainCode          code as echoed on the first line
     * @param origin             journey origin, or null when the message has no "X to Y" part
     * @param destination        journey destination, or null
     * @param lateMinutes        minutes late; negative means early; null when not reported
     * @param scheduledDeparture "HH:MM" scheduled departure, or null
     * @param expectedDeparture  "HH:MM" expected departure for services not yet running, or null
     * @param lastLocation       where the train was last seen, or null
     * @param nextStop           next scheduled stop, or null
     * @param text               the whole message, readable on one line
     */
    public record ParsedMessage(
            String trainCode,
            String origin,
            String destination,
            Integer lateMinutes,
            String scheduledDeparture,
            String expectedDeparture,
            String lastLocation,
            String nextStop,
            String text) {}

    private static final ParsedMessage EMPTY =
            new ParsedMessage(null, null, null, null, null, null, null, null, "");

    public static ParsedMessage parse(String publicMessage) {
        String[] lines = splitLines(publicMessage);
        if (lines.length == 0) return EMPTY;

        String trainCode = blankToNull(lines[0]);
        String journeyLine  = lines.length > 1 ? lines[1] : "";
        String movementLine = lines.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(lines, 2, lines.length)) : "";

        Integer lateMinutes = extractLateMinutes(journeyLine);

        String journey = LATE.matcher(journeyLine).replaceAll(" ").trim();
        String scheduledDeparture = null;
        Matcher sched = SCHED_PREFIX.matcher(journey);
        if (sched.find()) {
            scheduledDeparture = sched.group(1);
            journey = journey.substring(sched.end()).trim();
        }

        String origin = null;
        String destination = null;
        int toIndex = journey.toLowerCase().lastIndexOf(" to ");
        if (toIndex > 0) {
            origin = blankToNull(journey.substring(0, toIndex));
            destination = blankToNull(journey.substring(toIndex + 4));
        }

        String nextStop = null;
        Matcher next = NEXT_STOP.matcher(movementLine);
        if (next.find()) nextStop = blankToNull(next.group(1));

        String lastLocation = null;
        Matcher movement = MOVEMENT.matcher(movementLine);
        if (movement.matches()) lastLocation = blankToNull(movement.group(2));

        String expectedDeparture = null;
        Matcher expected = EXPECTED.matcher(movementLine);
        if (expected.find()) expectedDeparture = expected.group(1);

        return new ParsedMessage(
                trainCode, origin, destination, lateMinutes,
                scheduledDeparture, expectedDeparture, lastLocation, nextStop,
                flatten(publicMessage));
    }

    /** The message as a single readable line, with the literal {@code \n} markers resolved. */
    public static String flatten(String publicMessage) {
        return String.join(" · ", splitLines(publicMessage));
    }

    /** Strips markup and entities without touching the line structure. */
    public static String clean(String value) {
        if (value == null) return "";
        return collapse(HTML_TAG.matcher(value).replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">"));
    }

    private static Integer extractLateMinutes(String line) {
        Matcher matcher = LATE.matcher(line);
        if (!matcher.find()) return null;
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String[] splitLines(String publicMessage) {
        if (publicMessage == null || publicMessage.isBlank()) return new String[0];
        String cleaned = clean(publicMessage);
        // Literal "\n" first, then any real line break the API may use instead.
        return java.util.Arrays.stream(cleaned.split("\\\\n|\\R"))
                .map(PublicMessageParser::collapse)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static String collapse(String value) {
        return value.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
