package com.irishrail.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real PostgreSQL.
 *
 * <p>They need a real one because the analytics layer is not portable SQL: {@code DISTINCT ON},
 * {@code ON CONFLICT}, BRIN and partial indexes, {@code pg_advisory_xact_lock}. H2 was on the
 * classpath as the test database and was never used by anything — which is just as well, since it
 * would have rejected or silently mis-answered most of the queries that matter.
 *
 * <p>Two ways to get one:
 * <ul>
 *   <li>Docker present — a throwaway container, which is what CI uses.</li>
 *   <li>{@code -Dirishrail.test.datasource.url=jdbc:postgresql://localhost:5432/some_scratch_db} —
 *       an existing server, for machines without Docker. The named database is written to and must
 *       not be one you care about.</li>
 * </ul>
 * With neither, these tests skip rather than fail, so {@code mvn test} stays green anywhere.
 */
@SpringBootTest(properties = {
        // No timers: the collector would call the live Irish Rail API and write to the test schema.
        "irishrail.scheduling.enabled=false",
        // No startup roll-up either; each test decides what to aggregate and when.
        "irishrail.analytics.aggregates.backfill-on-startup=false",
        "spring.flyway.clean-disabled=false"
})
@RequiresPostgres
public abstract class PostgresIntegrationTest {

    private static final String URL_PROPERTY = "irishrail.test.datasource.url";
    private static PostgreSQLContainer<?> container;

    /**
     * Blank counts as absent: Maven passes an undefined property through as an empty string, so a
     * plain null check would read "" as a JDBC URL and fail instead of falling back to Docker.
     */
    private static String overrideUrl() {
        String value = System.getProperty(URL_PROPERTY);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final boolean AVAILABLE = resolveAvailability();

    public static boolean databaseAvailable() {
        return AVAILABLE;
    }

    /**
     * Probed once, by actually building the Docker client. {@code isDockerAvailable()} was not
     * decisive here — it reported a usable environment on a machine where starting a container
     * then failed — whereas {@code client()} throws exactly when no strategy can be established.
     */
    private static boolean resolveAvailability() {
        if (overrideUrl() != null) return true;
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String override = overrideUrl();
        if (override != null) {
            registry.add("spring.datasource.url", () -> override);
            registry.add("spring.datasource.username",
                    () -> System.getProperty("irishrail.test.datasource.username", "postgres"));
            registry.add("spring.datasource.password",
                    () -> System.getProperty("irishrail.test.datasource.password", "postgres"));
            return;
        }
        startContainer();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static synchronized void startContainer() {
        if (container != null) return;
        container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        container.start();
        // Shared by every subclass for the whole run; Ryuk reaps it when the JVM exits.
    }
}
