package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.ScriptParameter;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.repository.ScriptParameterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bulk-imports the DevOps-in-a-Box seed script library (Task 4.6) into PostgreSQL.
 *
 * <p>Loading strategy (idempotent, safe to run on every startup):</p>
 * <ol>
 *   <li><b>Terraform</b> — the {@code DevOpsScript} rows (name, code, description,
 *       tags, cloud providers) are inserted by {@code terraform_scripts_seed.sql}.
 *       If those rows are absent, the SQL seed is executed once. The Terraform
 *       <i>parameter-only</i> manifest ({@code parametersOnly: true},
 *       {@code matchBy: "name"}) then supplies {@code ScriptParameter} rows, matched
 *       to the existing scripts by unique name and upserted.</li>
 *   <li><b>Bash / Python / Ansible</b> — "full" manifests carry the complete script
 *       metadata plus a {@code codeFile} (under {@code codeBaseDir}). For each entry
 *       whose name does not already exist, the code file is read from the classpath,
 *       a {@code DevOpsScript} is created (version/status/author default from the
 *       manifest), and its {@code ScriptParameter} rows are persisted.</li>
 * </ol>
 *
 * Idempotency is guarded by script {@code name}: existing scripts are skipped, so
 * duplicates are never created. Search is served directly from PostgreSQL, so no
 * separate indexing step is required.
 *
 * Requirements: REQ-2.1.1, REQ-2.1.2, REQ-2.1.3, REQ-2.3.1
 */
@Component
@Order(20) // after DataInitializer / schema initializers
public class DevOpsScriptSeedLoader implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DevOpsScriptSeedLoader.class);

    private static final String SEED_DIR = "data/seed";
    private static final String TERRAFORM_SEED_SQL = SEED_DIR + "/terraform_scripts_seed.sql";
    private static final String TERRAFORM_MANIFEST = SEED_DIR + "/terraform_scripts_manifest.json";
    private static final List<String> FULL_MANIFESTS = List.of(
            SEED_DIR + "/bash_scripts_manifest.json",
            SEED_DIR + "/python_scripts_manifest.json",
            SEED_DIR + "/ansible_scripts_manifest.json"
    );
    /** Sentinel Terraform script used to detect whether the SQL seed already ran. */
    private static final String TERRAFORM_SENTINEL = "AWS EC2 Instance with Best Practices";

    private static final String DEFAULT_AUTHOR = "XamOps Team";
    private static final String DEFAULT_VERSION = "1.0.0";
    private static final String DEFAULT_STATUS = "published";

    private final DevOpsScriptRepository scriptRepository;
    private final ScriptParameterRepository parameterRepository;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Value("${devops.seed.enabled:true}")
    private boolean seedEnabled;

    public DevOpsScriptSeedLoader(DevOpsScriptRepository scriptRepository,
                                  ScriptParameterRepository parameterRepository,
                                  DataSource dataSource,
                                  ObjectMapper objectMapper) {
        this.scriptRepository = scriptRepository;
        this.parameterRepository = parameterRepository;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            logger.info("DevOps script seed loader disabled (devops.seed.enabled=false); skipping.");
            return;
        }
        try {
            seedAll();
        } catch (Exception e) {
            // Never let seeding take down application startup.
            logger.error("DevOps script seed import failed; continuing startup.", e);
        }
    }

    /**
     * Run the full import pipeline. Exposed (package-private) for tests.
     */
    @Transactional
    public void seedAll() {
        int terraformParams = loadTerraform();
        int fullImported = 0;
        for (String manifest : FULL_MANIFESTS) {
            fullImported += loadFullManifest(manifest);
        }
        long total = scriptRepository.count();
        logger.info("DevOps seed import complete: {} new scripts imported, {} Terraform parameters upserted, {} scripts total in database.",
                fullImported, terraformParams, total);
    }

    // ------------------------------------------------------------------
    // Terraform: SQL seed (script rows) + parameter-only manifest
    // ------------------------------------------------------------------

    private int loadTerraform() {
        ensureTerraformScriptsSeeded();
        return applyParameterOnlyManifest(TERRAFORM_MANIFEST);
    }

    /**
     * Execute {@code terraform_scripts_seed.sql} exactly once. The file uses
     * PostgreSQL dollar-quoted bodies (so semicolon-based statement splitting would
     * corrupt it); it is therefore sent to the server as a single simple query on a
     * dedicated auto-commit connection.
     */
    private void ensureTerraformScriptsSeeded() {
        if (scriptRepository.existsByName(TERRAFORM_SENTINEL)) {
            logger.info("Terraform scripts already present; skipping SQL seed.");
            return;
        }
        ClassPathResource resource = new ClassPathResource(TERRAFORM_SEED_SQL);
        if (!resource.exists()) {
            logger.warn("Terraform seed SQL not found on classpath: {}", TERRAFORM_SEED_SQL);
            return;
        }
        String sql;
        try {
            sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Terraform seed SQL: " + TERRAFORM_SEED_SQL, e);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                logger.info("Executed Terraform scripts SQL seed (32 scripts + tag/provider associations).");
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute Terraform seed SQL", e);
        }
    }

    /**
     * Apply a parameter-only manifest: for each {@code { name, parameters[] }} entry,
     * look up the existing {@link DevOpsScript} by name and upsert its parameters.
     *
     * @return number of parameter rows created or updated
     */
    int applyParameterOnlyManifest(String manifestPath) {
        JsonNode root = readManifest(manifestPath);
        if (root == null) {
            return 0;
        }
        JsonNode scripts = root.path("scripts");
        int upserted = 0;
        int missing = 0;
        for (JsonNode entry : scripts) {
            String name = entry.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            Optional<DevOpsScript> existing = scriptRepository.findFirstByName(name);
            if (existing.isEmpty()) {
                missing++;
                logger.warn("Parameter-only manifest references unknown script '{}'; skipping its parameters.", name);
                continue;
            }
            upserted += upsertParameters(existing.get(), entry.path("parameters"));
        }
        if (missing > 0) {
            logger.warn("{} script(s) in {} had no matching DevOpsScript row.", missing, manifestPath);
        }
        logger.info("Applied parameter-only manifest {}: {} parameters upserted.", manifestPath, upserted);
        return upserted;
    }

    // ------------------------------------------------------------------
    // Full manifests: bash / python / ansible
    // ------------------------------------------------------------------

    /**
     * Load a "full" manifest (script metadata + code file + parameters).
     *
     * @return number of newly imported scripts
     */
    int loadFullManifest(String manifestPath) {
        JsonNode root = readManifest(manifestPath);
        if (root == null) {
            return 0;
        }
        String category = root.path("category").asText(null);
        String author = text(root, "author", DEFAULT_AUTHOR);
        String defaultStatus = text(root, "defaultStatus", DEFAULT_STATUS);
        String codeBaseDir = root.path("codeBaseDir").asText(null);

        JsonNode scripts = root.path("scripts");
        int imported = 0;
        int skipped = 0;
        for (JsonNode entry : scripts) {
            String name = entry.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            if (scriptRepository.existsByName(name)) {
                skipped++;
                continue; // idempotent: already imported
            }
            DevOpsScript script = buildScript(entry, category, author, defaultStatus, codeBaseDir);
            if (script == null) {
                continue;
            }
            DevOpsScript saved = scriptRepository.save(script);
            upsertParameters(saved, entry.path("parameters"));
            imported++;
        }
        logger.info("Loaded full manifest {}: {} imported, {} skipped (already present).",
                manifestPath, imported, skipped);
        return imported;
    }

    private DevOpsScript buildScript(JsonNode entry, String category, String author,
                                     String defaultStatus, String codeBaseDir) {
        String name = entry.path("name").asText();
        String codeFile = entry.path("codeFile").asText(null);
        if (codeFile == null || codeFile.isBlank()) {
            logger.warn("Manifest entry '{}' has no codeFile; skipping.", name);
            return null;
        }
        String code = readCodeFile(codeBaseDir, codeFile);
        if (code == null) {
            logger.warn("Could not read code file '{}/{}' for script '{}'; skipping.", codeBaseDir, codeFile, name);
            return null;
        }

        DevOpsScript script = new DevOpsScript();
        script.setName(name);
        script.setDescription(entry.path("description").asText(null));
        script.setCategory(category);
        script.setCode(code);
        script.setVersion(text(entry, "version", DEFAULT_VERSION));
        script.setStatus(defaultStatus != null ? defaultStatus : DEFAULT_STATUS);
        script.setAuthor(author);
        script.setDifficulty(entry.path("difficulty").asText(null));
        script.setPrerequisites(entry.path("prerequisites").asText(null));
        if (entry.hasNonNull("estimatedTimeMinutes")) {
            script.setEstimatedTimeMinutes(entry.path("estimatedTimeMinutes").asInt());
        }
        script.setTags(stringList(entry.path("tags")));
        script.setCloudProviders(stringList(entry.path("cloudProviders")));
        return script;
    }

    // ------------------------------------------------------------------
    // Parameter upsert (shared by both manifest flavors)
    // ------------------------------------------------------------------

    /**
     * Create or update the {@link ScriptParameter} rows for a script, matching
     * existing rows by parameter name.
     *
     * @return number of parameter rows created or updated
     */
    private int upsertParameters(DevOpsScript script, JsonNode parameters) {
        if (parameters == null || !parameters.isArray() || parameters.size() == 0) {
            return 0;
        }
        Map<String, ScriptParameter> existingByName = new LinkedHashMap<>();
        for (ScriptParameter p : parameterRepository.findByScriptIdOrderByDisplayOrderAsc(script.getId())) {
            existingByName.put(p.getName(), p);
        }

        List<ScriptParameter> toSave = new ArrayList<>();
        for (JsonNode node : parameters) {
            String paramName = node.path("name").asText(null);
            if (paramName == null || paramName.isBlank()) {
                continue;
            }
            ScriptParameter param = existingByName.getOrDefault(paramName, new ScriptParameter());
            param.setScript(script);
            param.setName(paramName);
            param.setLabel(text(node, "label", paramName));
            param.setDescription(node.path("description").asText(null));
            param.setParameterType(text(node, "parameterType", "text"));
            param.setRequired(node.path("required").asBoolean(true));
            param.setDefaultValue(node.path("defaultValue").asText(null));
            param.setValidationRules(serializeValidationRules(node.path("validationRules")));
            param.setCategory(node.path("category").asText(null));
            param.setDependsOn(node.path("dependsOn").asText(null));
            param.setPrefillFrom(node.path("prefillFrom").asText(null));
            param.setDisplayOrder(node.path("displayOrder").asInt(0));
            toSave.add(param);
        }
        parameterRepository.saveAll(toSave);
        return toSave.size();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private JsonNode readManifest(String manifestPath) {
        ClassPathResource resource = new ClassPathResource(manifestPath);
        if (!resource.exists()) {
            logger.warn("Seed manifest not found on classpath: {}", manifestPath);
            return null;
        }
        try {
            return objectMapper.readTree(resource.getInputStream());
        } catch (Exception e) {
            logger.error("Failed to parse seed manifest {}", manifestPath, e);
            return null;
        }
    }

    private String readCodeFile(String codeBaseDir, String codeFile) {
        String path = (codeBaseDir != null && !codeBaseDir.isBlank())
                ? codeBaseDir + "/" + codeFile
                : SEED_DIR + "/" + codeFile;
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Failed to read code file {}", path, e);
            return null;
        }
    }

    private String serializeValidationRules(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            logger.warn("Failed to serialize validationRules; storing null.", e);
            return null;
        }
    }

    private static List<String> stringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (Iterator<JsonNode> it = arrayNode.elements(); it.hasNext(); ) {
                values.add(it.next().asText());
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? defaultValue : text;
    }
}
