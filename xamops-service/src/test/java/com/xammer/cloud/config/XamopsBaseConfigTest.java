package com.xammer.cloud.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design R2: the xamops base {@code application.properties} sets no default profile and no datasource,
 * so the active profile supplies it and anything else fails fast (R2-1 to R2-3).
 *
 * <p>The files are read by path, because the test {@code application.properties} shadows the base
 * file on the classpath. Resolvers hold file-backed property sources only, so system properties and
 * the environment are never consulted (flag F5). Assertions compare inside the test and report
 * booleans: no message carries a value or a hash of one.
 */
class XamopsBaseConfigTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path BASE = RESOURCES.resolve("application.properties");
    private static final Path DATASOURCE_CONFIG =
            Path.of("src/main/java/com/xammer/cloud/config/multitenancy/MultiTenantDataSourceConfig.java");

    private static final List<String> DATASOURCE_KEYS = List.of(
            "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
            "spring.datasource.driver-class-name");

    // ------------------------------------------------------------------ R2-1

    @Test
    void r2_1TheBaseFileSetsNoProfileAndNoDatasourceCredentials() throws IOException {
        Properties base = load(BASE);
        for (String key : List.of("spring.profiles.active", "spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password")) {
            assertThat(base.containsKey(key)).as("base application.properties defines %s", key).isFalse();
        }
        assertThat(Files.readString(BASE, StandardCharsets.UTF_8).contains("18.61.99.59"))
                .as("base application.properties names the production database host").isFalse();
    }

    @Test
    void r2_1TheDatasourceConfigHasNoHardCodedConnectionLiterals() throws IOException {
        String source = Files.readString(DATASOURCE_CONFIG, StandardCharsets.UTF_8);
        for (String setter : List.of("setJdbcUrl(\"", "setUsername(\"", "setPassword(\"")) {
            assertThat(source.contains(setter)).as("MultiTenantDataSourceConfig calls %s with a string literal", setter)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ R2-2

    @ParameterizedTest
    @ValueSource(strings = {"local", "uat", "prod", "preprod"})
    void r2_2EachProfileSuppliesTheWholeDatasourceAndTheBaseChangesNoneOfIt(String profile) throws IOException {
        Properties profileProperties = load(RESOURCES.resolve("application-" + profile + ".properties"));
        PropertySourcesPropertyResolver effective = resolver(
                new PropertiesPropertySource("application-" + profile, profileProperties),
                new PropertiesPropertySource("application", load(BASE)));
        effective.setIgnoreUnresolvableNestedPlaceholders(true);   // env-only placeholders stay as written

        for (String key : DATASOURCE_KEYS) {
            assertThat(profileProperties.containsKey(key)).as("profile %s defines %s", profile, key).isTrue();
            boolean same = Objects.equals(effective.getProperty(key), profileProperties.getProperty(key));
            assertThat(same).as("profile %s: the effective %s is the profile's own value", profile, key).isTrue();
        }
    }

    // ------------------------------------------------------------------ R2-3

    @Test
    void r2_3WithTheBaseFileAloneTheDatasourceUrlDoesNotResolve() throws IOException {
        PropertySourcesPropertyResolver baseOnly = resolver(new PropertiesPropertySource("application", load(BASE)));

        assertThat(baseOnly.containsProperty("spring.datasource.url")).isFalse();
        assertThatThrownBy(() -> baseOnly.resolveRequiredPlaceholders("${spring.datasource.url}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.datasource.url");
        assertThatThrownBy(() -> baseOnly.resolveRequiredPlaceholders("${spring.profiles.active}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static PropertySourcesPropertyResolver resolver(PropertiesPropertySource... sources) {
        MutablePropertySources propertySources = new MutablePropertySources();
        for (PropertiesPropertySource source : sources) {
            propertySources.addLast(source);
        }
        return new PropertySourcesPropertyResolver(propertySources);
    }

    private static Properties load(Path file) throws IOException {
        assertThat(file).as("run from the module directory").isRegularFile();
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        return properties;
    }
}
