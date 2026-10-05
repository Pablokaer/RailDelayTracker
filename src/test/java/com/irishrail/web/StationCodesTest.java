package com.irishrail.web;

import com.irishrail.service.StationDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Station codes arrive from the query string and used to go straight into an upstream URL and into
 * an unbounded cache key.
 */
class StationCodesTest {

    private StationCodes codes;

    @BeforeEach
    void setUp() {
        codes = new StationCodes(directoryKnowing("CNLLY", "HSTON", "MHIDE"));
    }

    @Test
    void acceptsAKnownCodeAndNormalisesIt() {
        assertThat(codes.resolve("  cnlly ")).contains("CNLLY");
    }

    @Test
    void rejectsACodeThatNamesNoStation() {
        assertThat(codes.resolve("NOPE")).isEmpty();
    }

    @Test
    void rejectsAttemptsToSmuggleExtraQueryParametersUpstream() {
        // Previously concatenated onto "...?NumMins=90&StationCode=" unencoded.
        assertThat(codes.resolve("CNLLY&NumMins=9999")).isEmpty();
    }

    @Test
    void rejectsUriTemplateSyntax() {
        // RestTemplate reads braces as an unresolved placeholder and throws rather than fetching.
        assertThat(codes.resolve("{code}")).isEmpty();
    }

    @Test
    void rejectsOverlongAndEmptyInput() {
        assertThat(codes.resolve("")).isEmpty();
        assertThat(codes.resolve(null)).isEmpty();
        assertThat(codes.resolve("A")).isEmpty();
        assertThat(codes.resolve("ABCDEFGHIJKLMNOP")).isEmpty();
    }

    @Test
    void requireReportsTheOffendingParameterName() {
        assertThatThrownBy(() -> codes.require("fromStationCode", "NOPE"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("fromStationCode");
    }

    @Test
    void pagesFallBackInsteadOfFailing() {
        assertThat(codes.orDefault("NOPE", "CNLLY")).isEqualTo("CNLLY");
        assertThat(codes.orDefault("MHIDE", "CNLLY")).isEqualTo("MHIDE");
    }

    /**
     * If the API has never answered, the directory is empty and cannot tell a real code from a
     * made-up one. Rejecting everything would take the whole site down with the upstream, so the
     * syntactic check stands alone in that window.
     */
    @Test
    void fallsBackToTheSyntacticCheckWhileTheDirectoryIsEmpty() {
        StationCodes empty = new StationCodes(directoryKnowing());
        assertThat(empty.resolve("CNLLY")).contains("CNLLY");
        assertThat(empty.resolve("CNLLY&NumMins=1")).isEmpty();
    }

    private static StationDirectory directoryKnowing(String... knownCodes) {
        Set<String> known = Set.of(knownCodes);
        return new StationDirectory(null, null) {
            @Override
            public boolean isKnownCode(String stationCode) {
                return known.isEmpty() || known.contains(stationCode);
            }
        };
    }
}
