package com.irishrail.support;

import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips a test unless a PostgreSQL is reachable — Docker for Testcontainers, or the server named
 * by {@code -Dirishrail.test.datasource.url}.
 *
 * <p>This exists because {@link EnabledIf} is not declared {@code @Inherited}: putting it on
 * {@link PostgresIntegrationTest} had no effect on the subclasses, which went on to fail on a
 * machine without Docker instead of skipping. Wrapping it in an {@code @Inherited} annotation of
 * our own makes it apply down the hierarchy, so a new integration test only has to extend the base
 * class to get the behaviour.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@EnabledIf("com.irishrail.support.PostgresIntegrationTest#databaseAvailable")
public @interface RequiresPostgres {
}
