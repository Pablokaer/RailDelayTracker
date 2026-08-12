package com.irishrail.util;

import com.irishrail.util.PublicMessageParser.ParsedMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The samples below are verbatim {@code PublicMessage} values from
 * {@code getCurrentTrainsXML}. Note the {@code \n} in the Java strings is escaped twice: the API
 * sends the two characters backslash + n, not a line break.
 */
class PublicMessageParserTest {

    @Test
    void parsesRunningServiceWithDelay() {
        ParsedMessage parsed = PublicMessageParser.parse(
                "A220\\n16:00 - Dublin Heuston to Cork (2 mins late)\\nDeparted Inchicore Advance Starter next stop Thurles");

        assertEquals("A220", parsed.trainCode());
        assertEquals("16:00", parsed.scheduledDeparture());
        assertEquals("Dublin Heuston", parsed.origin());
        assertEquals("Cork", parsed.destination());
        assertEquals(2, parsed.lateMinutes());
        assertEquals("Inchicore Advance Starter", parsed.lastLocation());
        assertEquals("Thurles", parsed.nextStop());
    }

    @Test
    void parsesEarlyServiceAsNegativeMinutes() {
        ParsedMessage parsed = PublicMessageParser.parse(
                "A221\\n15:25 - Cork to Dublin Heuston (-6 mins late)\\nArrived LJ895 next stop Limerick Junction");

        assertEquals(-6, parsed.lateMinutes());
        assertEquals("Cork", parsed.origin());
        assertEquals("Dublin Heuston", parsed.destination());
        assertEquals("LJ895", parsed.lastLocation());
        assertEquals("Limerick Junction", parsed.nextStop());
    }

    /**
     * The regression that mattered: the old extractor left "\nExpected Departure 16:00" glued to
     * the destination, so it resolved to no station — or worse, to a wrong one by substring match.
     */
    @Test
    void parsesNotYetRunningServiceWithoutLeakingTheThirdLine() {
        ParsedMessage parsed = PublicMessageParser.parse("P524\\nCobh to Cork\\nExpected Departure 16:00");

        assertEquals("P524", parsed.trainCode());
        assertEquals("Cobh", parsed.origin());
        assertEquals("Cork", parsed.destination());
        assertEquals("16:00", parsed.expectedDeparture());
        assertNull(parsed.lateMinutes());
        assertNull(parsed.scheduledDeparture());
        assertNull(parsed.nextStop());
    }

    @Test
    void flattenedTextNeverContainsLiteralEscapes() {
        String text = PublicMessageParser.parse(
                "D923\\nDublin Pearse to Maynooth\\nExpected Departure 16:12").text();

        assertTrue(text.contains("Dublin Pearse to Maynooth"), text);
        assertTrue(text.contains("Expected Departure 16:12"), text);
        assertTrue(!text.contains("\\n"), "literal \\n leaked into the UI text: " + text);
    }

    @Test
    void handlesRealNewlinesToo() {
        ParsedMessage parsed = PublicMessageParser.parse("E109\nBray to Howth (1 mins late)\nDeparted Bray next stop Shankill");

        assertEquals("E109", parsed.trainCode());
        assertEquals("Bray", parsed.origin());
        assertEquals("Howth", parsed.destination());
        assertEquals(1, parsed.lateMinutes());
        assertEquals("Shankill", parsed.nextStop());
    }

    @Test
    void stripsMarkupAndEntities() {
        ParsedMessage parsed = PublicMessageParser.parse(
                "<b>A123</b>\\nCork &amp; Cobh to Midleton (0 mins late)\\nDeparted Cork next stop Midleton");

        assertEquals("A123", parsed.trainCode());
        assertEquals("Cork & Cobh", parsed.origin());
        assertEquals("Midleton", parsed.destination());
        assertEquals(0, parsed.lateMinutes());
    }

    @Test
    void toleratesEmptyAndMalformedInput() {
        assertNull(PublicMessageParser.parse(null).trainCode());
        assertEquals("", PublicMessageParser.parse(null).text());
        assertNull(PublicMessageParser.parse("   ").trainCode());

        ParsedMessage single = PublicMessageParser.parse("A999");
        assertEquals("A999", single.trainCode());
        assertNull(single.destination());
        assertNull(single.lateMinutes());
    }

    /** "Departed X" with no "next stop" clause must still yield the location. */
    @Test
    void parsesMovementLineWithoutNextStop() {
        ParsedMessage parsed = PublicMessageParser.parse(
                "A500\\n09:00 - Galway to Dublin Heuston (3 mins late)\\nDeparted Athenry");

        assertEquals("Athenry", parsed.lastLocation());
        assertNull(parsed.nextStop());
    }
}
