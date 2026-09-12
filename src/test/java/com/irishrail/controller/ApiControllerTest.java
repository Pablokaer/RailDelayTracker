package com.irishrail.controller;

import com.irishrail.config.IrishRailProperties;
import com.irishrail.model.AnalyticsView;
import com.irishrail.model.DashboardSummary;
import com.irishrail.service.AnalyticsQueryService;
import com.irishrail.service.DelayTrackingService;
import com.irishrail.service.IrishRailService;
import com.irishrail.service.SnapshotEventService;
import com.irishrail.service.StationDirectory;
import com.irishrail.service.TrainPositionService;
import com.irishrail.service.TrainRouteService;
import com.irishrail.web.ApiExceptionHandler;
import com.irishrail.web.StationCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input handling at the HTTP edge.
 *
 * <p>Both behaviours checked here were silent before: an unknown station code was forwarded to
 * Irish Rail and cached under the caller's string, and an unparseable date became "no lower
 * bound", which the aggregate layer widens to the year 2000 — the most expensive query in the
 * application, returned as though it were the answer to what was asked.
 */
@WebMvcTest(controllers = ApiController.class)
@Import({StationCodes.class, ApiExceptionHandler.class, ApiControllerTest.SliceBeans.class})
@EnableConfigurationProperties(IrishRailProperties.class)
class ApiControllerTest {

    /**
     * {@code @WebMvcTest} includes {@code Filter} beans, so the security-header and rate-limit
     * filters are in this chain for real. They need a meter registry, which the web slice does not
     * auto-configure.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SliceBeans {
        @org.springframework.context.annotation.Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IrishRailService irishRailService;
    @MockitoBean
    private DelayTrackingService delayTrackingService;
    @MockitoBean
    private AnalyticsQueryService analytics;
    @MockitoBean
    private SnapshotEventService snapshotEventService;
    @MockitoBean
    private TrainPositionService trainPositionService;
    @MockitoBean
    private TrainRouteService trainRouteService;
    @MockitoBean
    private StationDirectory stationDirectory;

    @BeforeEach
    void setUp() {
        given(stationDirectory.isKnownCode(anyString()))
                .willAnswer(call -> List.of("CNLLY", "HSTON").contains(call.getArgument(0)));
        given(analytics.get(any())).willReturn(emptyView());
    }

    @Test
    void anUnknownStationCodeIsRejectedWithoutCallingUpstream() throws Exception {
        mvc.perform(get("/api/trains").param("stationCode", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(
                        "'stationCode' is not a known Irish Rail station code"));

        verify(irishRailService, never()).getTrainsByStation(anyString());
        verify(irishRailService, never()).getTrainsByStation(anyString(), anyBoolean());
    }

    @Test
    void aStationCodeCarryingQuerySyntaxIsRejected() throws Exception {
        mvc.perform(get("/api/trains").param("stationCode", "CNLLY&NumMins=9999"))
                .andExpect(status().isBadRequest());

        verify(irishRailService, never()).getTrainsByStation(anyString());
    }

    @Test
    void aKnownStationCodeIsServed() throws Exception {
        given(irishRailService.getTrainsByStation("CNLLY")).willReturn(List.of());

        mvc.perform(get("/api/trains").param("stationCode", "cnlly"))
                .andExpect(status().isOk());

        verify(irishRailService).getTrainsByStation("CNLLY");
    }

    @Test
    void anUnparseableDateIsA400RatherThanASilentAllTimeQuery() throws Exception {
        mvc.perform(get("/api/analytics/overview").param("from", "nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("YYYY-MM-DD")));

        verify(analytics, never()).get(any());
    }

    @Test
    void aValidDateRangeReachesTheQueryService() throws Exception {
        mvc.perform(get("/api/analytics/overview")
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboard").exists())
                .andExpect(jsonPath("$.catOnTime").exists());

        verify(analytics).get(new AnalyticsQueryService.Request(
                java.time.LocalDate.of(2026, 3, 1),
                java.time.LocalDate.of(2026, 3, 31),
                null, false, true));
    }

    /** The JSON shape is a contract with overview.js, not an implementation detail. */
    @Test
    void theOverviewPayloadKeepsTheKeysTheBrowserReads() throws Exception {
        mvc.perform(get("/api/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationRank").exists())
                .andExpect(jsonPath("$.hourlyLabels").exists())
                .andExpect(jsonPath("$.hourlyDelayPcts").exists())
                .andExpect(jsonPath("$.hourlyAvgDelays").exists())
                .andExpect(jsonPath("$.top10Delays").exists())
                .andExpect(jsonPath("$.routeRanking").exists())
                .andExpect(jsonPath("$.recentDelays").exists())
                .andExpect(jsonPath("$.destLabels").exists())
                .andExpect(jsonPath("$.destAvgDelays").exists())
                .andExpect(jsonPath("$.destDelayCounts").exists())
                .andExpect(jsonPath("$.maxCatCount").exists())
                .andExpect(jsonPath("$.catExtreme").exists())
                // Only ever used server-side; it was never part of the payload.
                .andExpect(jsonPath("$.hourly").doesNotExist());
    }

    @Test
    void everyResponseCarriesTheSecurityHeaders() throws Exception {
        mvc.perform(get("/api/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Frame-Options", "DENY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("Content-Security-Policy"));
    }

    @Test
    void aJourneyIsRejectedWhenEitherEndIsUnknown() throws Exception {
        mvc.perform(get("/api/journey-options")
                        .param("fromStationCode", "CNLLY")
                        .param("toStationCode", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("toStationCode")));
    }

    @Test
    void theEventStreamAnswers503OnceTheSubscriberCapIsReached() throws Exception {
        given(snapshotEventService.subscribe()).willReturn(null);

        mvc.perform(get("/api/events"))
                .andExpect(status().isServiceUnavailable());
    }

    private static AnalyticsView emptyView() {
        return new AnalyticsView(
                new DashboardSummary(0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                Map.of(), 0, 0, 0, 0, 0, 0, List.of());
    }
}
