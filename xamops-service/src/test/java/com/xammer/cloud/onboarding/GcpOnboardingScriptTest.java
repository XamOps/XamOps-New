package com.xammer.cloud.onboarding;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Runs the real GCP onboarding script ({@code scripts/gcp/create_serviceaccount.sh}) against a stub
 * {@code gcloud} placed first on PATH. The stub logs every call and answers the few the script
 * reads; nothing reaches Google Cloud.
 *
 * <p>The stub project {@code my-proj} (number 123456789012) has four managed-instance-group
 * templates: tpl-a (global, runs as app-sa), tpl-b (regional, runs as the default compute service
 * account), tpl-gke (a GKE node template) and tpl-x (runs as a service account of another project),
 * plus a VM running as vm-sa.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class GcpOnboardingScriptTest {

    /** The module directory is the working directory of a surefire run. */
    private static final Path SCRIPT =
            Path.of("..", "scripts", "gcp", "create_serviceaccount.sh").toAbsolutePath().normalize();

    private static final String XAMOPS_SA = "xamops@my-proj.iam.gserviceaccount.com";
    private static final String APP_SA = "app-sa@my-proj.iam.gserviceaccount.com";
    private static final String VM_SA = "vm-sa@my-proj.iam.gserviceaccount.com";
    private static final String DEFAULT_SA = "123456789012-compute@developer.gserviceaccount.com";
    private static final String GKE_SA = "gke-node-sa@my-proj.iam.gserviceaccount.com";
    private static final String FOREIGN_SA = "other@other-proj.iam.gserviceaccount.com";

    /** The 27 required read roles of the script before this change, unchanged by it. */
    private static final List<String> READ_ROLES = List.of(
            "roles/viewer", "roles/recommender.viewer", "roles/securitycenter.adminViewer",
            "roles/iam.securityReviewer", "roles/monitoring.viewer", "roles/logging.viewer",
            "roles/cloudasset.viewer", "roles/compute.viewer", "roles/bigquery.dataViewer",
            "roles/bigquery.metadataViewer", "roles/bigquery.resourceViewer", "roles/cloudsql.viewer",
            "roles/storage.objectViewer", "roles/container.clusterViewer", "roles/aiplatform.viewer",
            "roles/pubsub.viewer", "roles/dataplex.viewer", "roles/cloudscheduler.viewer",
            "roles/osconfig.viewer", "roles/cloudfunctions.viewer", "roles/run.viewer",
            "roles/appengine.appViewer", "roles/cloudbuild.builds.viewer", "roles/artifactregistry.reader",
            "roles/cloudkms.viewer", "roles/secretmanager.viewer", "roles/dns.reader");

    private static final String BILLING_VIEWER = "roles/billing.viewer";

    private static final List<String> LEGACY_ROLES = List.of(
            "roles/compute.instanceAdmin.v1", "roles/iam.serviceAccountUser", "roles/container.admin",
            "roles/storage.objectAdmin", "roles/monitoring.alertPolicyEditor");

    private static final String AUTOSPOTTING_PERMISSIONS = "compute.disks.create,compute.images.useReadOnly,"
            + "compute.instanceGroupManagers.get,compute.instanceGroupManagers.update,"
            + "compute.instanceTemplates.create,compute.instanceTemplates.delete,compute.instanceTemplates.get,"
            + "compute.instanceTemplates.useReadOnly,compute.instances.create,compute.instances.setLabels,"
            + "compute.instances.setMetadata,compute.instances.setTags,compute.subnetworks.use,"
            + "compute.subnetworks.useExternalIp";

    private static final String COMPUTE_OPS_PERMISSIONS = "compute.disks.delete,compute.disks.resize,"
            + "compute.instances.setMachineType,compute.instances.start,compute.instances.stop";

    private static final String CUSTOM_ROLE_AUTOSPOTTING = "projects/my-proj/roles/xamopsAutoSpotting";
    private static final String CUSTOM_ROLE_COMPUTE_OPS = "projects/my-proj/roles/xamopsComputeOps";

    /**
     * Stand-in for gcloud. Every call is logged as one tab-joined line; state (service account and
     * custom roles created) lives in $STUB_STATE so a second run sees the first one's results.
     *
     * <p>Optional failure knobs, each a space-separated list:
     * <ul>
     *   <li>{@code STUB_FAIL}, {@code STUB_FAIL_ONCE}: calls that always fail, or fail the first time
     *       only: {@code instance-groups} and {@code instances} (the listings), {@code get-iam-policy},
     *       or a service account email (its {@code describe} answers PERMISSION_DENIED);</li>
     *   <li>{@code STUB_SA_NOT_FOUND}: service accounts whose {@code describe} answers NOT_FOUND;</li>
     *   <li>{@code STUB_DELETED_ROLES}: custom role IDs that exist in a deleted state;</li>
     *   <li>{@code STUB_ROLE_CREATE_ERROR}: the error {@code iam roles create} fails with;</li>
     *   <li>{@code STUB_UNGRANTABLE_ROLES}: project roles whose binding fails with INVALID_ARGUMENT.</li>
     * </ul>
     */
    private static final String STUB_GCLOUD = """
            #!/bin/bash
            ( IFS=$'\\t'; printf '%s\\n' "$*" ) >> "$STUB_LOG"

            has() {
                local want="$1" arg
                shift
                for arg in "$@"; do
                    if [ "$arg" = "$want" ]; then return 0; fi
                done
                return 1
            }

            # Whether the space-separated list $1 contains the word $2.
            listed() {
                case " $1 " in *" $2 "*) return 0 ;; esac
                return 1
            }

            # Calls whose key is in STUB_FAIL always fail; those in STUB_FAIL_ONCE fail the first time only.
            fails() {
                if listed "${STUB_FAIL:-}" "$1"; then return 0; fi
                if listed "${STUB_FAIL_ONCE:-}" "$1" && [ ! -e "$STUB_STATE/failed-once-$1" ]; then
                    touch "$STUB_STATE/failed-once-$1"
                    return 0
                fi
                return 1
            }

            case "$1 $2" in
                "projects add-iam-policy-binding")
                    if has --help "$@"; then echo "  --condition=CONDITION"; exit 0; fi
                    for role in ${STUB_UNGRANTABLE_ROLES:-}; do
                        if has "--role=$role" "$@"; then
                            echo "ERROR: (gcloud.projects.add-iam-policy-binding) INVALID_ARGUMENT: Role $role is not supported for this resource." >&2
                            exit 1
                        fi
                    done
                    exit 0 ;;
                "projects describe")
                    echo "123456789012"
                    exit 0 ;;
                "projects get-iam-policy")
                    if fails get-iam-policy; then
                        echo "ERROR: (gcloud.projects.get-iam-policy) PERMISSION_DENIED: The caller does not have permission" >&2
                        exit 1
                    fi
                    if [ -n "${STUB_PROJECT_ROLES:-}" ]; then printf '%s\\n' "$STUB_PROJECT_ROLES"; fi
                    exit 0 ;;
                "iam service-accounts")
                    case "$3" in
                        create)
                            if [ -e "$STUB_STATE/sa-exists" ]; then
                                echo "ERROR: (gcloud.iam.service-accounts.create) Service account $4 already exists within project projects/my-proj." >&2
                                exit 1
                            fi
                            touch "$STUB_STATE/sa-exists"
                            exit 0 ;;
                        describe)
                            if listed "${STUB_SA_NOT_FOUND:-}" "$4"; then
                                echo "ERROR: (gcloud.iam.service-accounts.describe) NOT_FOUND: Unknown service account. This command is authenticated as user@example.com which is the active account specified by the [core/account] property" >&2
                                exit 1
                            fi
                            if fails "$4"; then
                                echo "ERROR: (gcloud.iam.service-accounts.describe) PERMISSION_DENIED: Permission 'iam.serviceAccounts.get' denied on resource (or it may not exist)." >&2
                                exit 1
                            fi
                            echo "email: $4"
                            exit 0 ;;
                        keys)
                            if [ "$4" = "create" ]; then
                                printf '%s' '{"type":"service_account","private_key":"-----BEGIN PRIVATE KEY-----\\nSTUB-KEY-MARKER\\n-----END PRIVATE KEY-----\\n"}' > "$5"
                                echo "created key [0123abcd] of type [json] as [$5]" >&2
                            fi
                            exit 0 ;;
                    esac
                    exit 0 ;;
                "iam roles")
                    case "$3" in
                        describe)
                            if listed "${STUB_DELETED_ROLES:-}" "$4"; then echo "True"; exit 0; fi
                            if [ -e "$STUB_STATE/role-$4" ]; then echo ""; exit 0; fi
                            echo "ERROR: (gcloud.iam.roles.describe) NOT_FOUND: Role $4 not found." >&2
                            exit 1 ;;
                        create)
                            if [ -n "${STUB_ROLE_CREATE_ERROR:-}" ]; then
                                printf '%s\\n' "$STUB_ROLE_CREATE_ERROR" >&2
                                exit 1
                            fi
                            touch "$STUB_STATE/role-$4"
                            exit 0 ;;
                    esac
                    exit 0 ;;
                "compute instance-groups")
                    if fails instance-groups; then
                        echo "ERROR: (gcloud.compute.instance-groups.managed.list) Some requests did not succeed:" >&2
                        echo " - Required 'compute.instanceGroupManagers.list' permission for 'projects/my-proj'" >&2
                        exit 1
                    fi
                    if has '--flatten=versions[]' "$@"; then
                        echo "https://www.googleapis.com/compute/v1/projects/my-proj/regions/us-central1/instanceTemplates/tpl-b"
                        echo "https://www.googleapis.com/compute/v1/projects/my-proj/global/instanceTemplates/tpl-gke"
                        echo "https://www.googleapis.com/compute/v1/projects/my-proj/global/instanceTemplates/tpl-x"
                    else
                        echo "https://www.googleapis.com/compute/v1/projects/my-proj/global/instanceTemplates/tpl-a"
                        echo ""
                    fi
                    exit 0 ;;
                "compute instance-templates")
                    if has '--format=value(properties.labels)' "$@"; then
                        if [ "$4" = "tpl-gke" ]; then echo "goog-gke-node="; else echo ""; fi
                        exit 0
                    fi
                    case "$4" in
                        tpl-a) echo "app-sa@my-proj.iam.gserviceaccount.com" ;;
                        tpl-b)
                            if ! has --region=us-central1 "$@"; then echo "ERROR: tpl-b is regional" >&2; exit 1; fi
                            echo "default" ;;
                        tpl-gke) echo "gke-node-sa@my-proj.iam.gserviceaccount.com" ;;
                        tpl-x) echo "other@other-proj.iam.gserviceaccount.com" ;;
                    esac
                    exit 0 ;;
                "compute instances")
                    if fails instances; then
                        echo "ERROR: (gcloud.compute.instances.list) Some requests did not succeed:" >&2
                        echo " - Required 'compute.instances.list' permission for 'projects/my-proj'" >&2
                        exit 1
                    fi
                    printf '%s\\n' "${STUB_VM_SAS-vm-sa@my-proj.iam.gserviceaccount.com}"
                    exit 0 ;;
            esac
            exit 0
            """;

    @TempDir
    Path tmp;

    private Path bin;
    private Path work;
    private Path state;
    private Path log;

    /** One run of the script: exit code, stdout and stderr together, and the gcloud calls it made. */
    record Result(int exitCode, String output, List<List<String>> calls) {

        List<List<String>> calls(String... prefix) {
            List<String> start = List.of(prefix);
            return calls.stream()
                    .filter(c -> c.size() >= start.size() && c.subList(0, start.size()).equals(start))
                    .toList();
        }

        int lastIndexOf(String... prefix) {
            List<String> start = List.of(prefix);
            return IntStream.range(0, calls.size())
                    .filter(i -> calls.get(i).size() >= start.size() && calls.get(i).subList(0, start.size()).equals(start))
                    .max().orElse(-1);
        }

        /** Project-level roles granted, in order (the --help probe is not a grant). */
        List<String> projectBindings() {
            return calls("projects", "add-iam-policy-binding").stream()
                    .filter(c -> !c.contains("--help"))
                    .map(c -> flag(c, "--role").orElseThrow())
                    .toList();
        }

        /** Service accounts on which a service-account-level binding was added, in order. */
        List<String> serviceAccountBindings() {
            return calls("iam", "service-accounts", "add-iam-policy-binding").stream()
                    .map(c -> c.get(3))
                    .toList();
        }

        List<List<String>> mutatingCalls() {
            return calls.stream().filter(GcpOnboardingScriptTest::isMutating).toList();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        bin = Files.createDirectories(tmp.resolve("bin"));
        work = Files.createDirectories(tmp.resolve("work"));
        state = Files.createDirectories(tmp.resolve("state"));
        log = tmp.resolve("gcloud.log");
        executable(bin.resolve("gcloud"), STUB_GCLOUD);
        executable(bin.resolve("sleep"), "#!/bin/sh\nexit 0\n");
    }

    // ------------------------------------------------------------------ 1. read-only

    @Test
    void readOnlyGrantsExactlyTheReadRolesAndKeepsTheKeyPrivate() throws Exception {
        Result result = run("my-proj");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.projectBindings()).containsExactlyInAnyOrderElementsOf(withBilling(READ_ROLES));
        assertThat(result.calls("iam", "roles")).isEmpty();
        assertThat(result.calls()).noneMatch(c -> String.join(" ", c).contains("serviceAccountUser"));
        assertThat(result.calls()).allMatch(c -> !c.contains("add-iam-policy-binding") || c.contains("--help")
                || c.contains("--member=serviceAccount:" + XAMOPS_SA));
        assertThat(result.output()).contains("Billing & cost data (full access)", "Store key securely");
        assertKeyFileIsPrivateAndNeverPrinted(result);
    }

    // ------------------------------------------------------------------ 2. read-write

    @Test
    void readWriteGrantsTheCustomRolesAndActsOnlyAsTheServiceAccountsWorkloadsRunAs() throws Exception {
        Result result = run("my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isZero();
        List<String> expected = new ArrayList<>(withBilling(READ_ROLES));
        expected.add(CUSTOM_ROLE_AUTOSPOTTING);
        expected.add(CUSTOM_ROLE_COMPUTE_OPS);
        assertThat(result.projectBindings()).containsExactlyInAnyOrderElementsOf(expected)
                .doesNotContainAnyElementsOf(LEGACY_ROLES);

        assertCustomRole(result, "create", "xamopsAutoSpotting", AUTOSPOTTING_PERMISSIONS);
        assertCustomRole(result, "create", "xamopsComputeOps", COMPUTE_OPS_PERMISSIONS);

        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(APP_SA, DEFAULT_SA, VM_SA)
                .doesNotContain(GKE_SA);
        assertThat(result.calls("iam", "service-accounts", "add-iam-policy-binding")).allMatch(c ->
                c.contains("--role=roles/iam.serviceAccountUser") && c.contains("--member=serviceAccount:" + XAMOPS_SA));
        assertThat(result.calls()).noneMatch(c -> c.contains("add-iam-policy-binding") && c.contains(FOREIGN_SA));
        assertThat(result.output()).contains("gcloud iam service-accounts add-iam-policy-binding " + FOREIGN_SA);

        assertThat(result.calls("compute", "instance-groups", "managed", "list")).hasSize(2)
                .allMatch(c -> c.contains("--filter=NOT name ~ ^gke-"));
        assertThat(result.calls("compute", "instances", "list")).hasSize(1)
                .allMatch(c -> c.contains("--filter=NOT labels.goog-gke-node:*"));
        assertThat(result.calls("compute", "instance-templates", "describe", "tpl-b"))
                .isNotEmpty().allMatch(c -> c.contains("--region=us-central1"));
        assertThat(result.calls("compute", "instance-templates", "describe", "tpl-a"))
                .isNotEmpty().allMatch(c -> c.contains("--global"));

        assertKeyFileIsPrivateAndNeverPrinted(result);
    }

    // ------------------------------------------------------------------ 3. re-run converges

    @Test
    void reRunningReadWriteUpdatesTheExistingRolesToTheSameDefinition() throws Exception {
        Result first = run("my-proj", "read-write");
        assertThat(first.exitCode()).as(first.output()).isZero();

        Result second = run("my-proj", "read-write");

        assertThat(second.exitCode()).as(second.output()).isZero();
        assertThat(second.calls("iam", "roles", "create")).isEmpty();
        assertThat(second.calls("iam", "roles", "undelete")).isEmpty();
        assertCustomRole(second, "update", "xamopsAutoSpotting", AUTOSPOTTING_PERMISSIONS);
        assertCustomRole(second, "update", "xamopsComputeOps", COMPUTE_OPS_PERMISSIONS);
        assertThat(second.projectBindings()).containsExactlyInAnyOrderElementsOf(first.projectBindings());
        assertThat(second.serviceAccountBindings()).containsExactlyInAnyOrderElementsOf(first.serviceAccountBindings());

        Path firstKey = assertKeyFileIsPrivateAndNeverPrinted(first);
        Path secondKey = assertKeyFileIsPrivateAndNeverPrinted(second);
        assertThat(secondKey).as("a second run never overwrites the first key file").isNotEqualTo(firstKey);
        assertThat(firstKey).isRegularFile();
    }

    // ------------------------------------------------------------------ 4-6. modes

    @Test
    void noKeyCreatesNoKey() throws Exception {
        Result result = run("my-proj", "read-write", "--no-key");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("iam", "service-accounts", "keys", "create")).isEmpty();
        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(APP_SA, DEFAULT_SA, VM_SA);
        assertThat(result.output()).as("no key, so no reminder to store one").doesNotContain("Store key securely");
    }

    @Test
    void refreshReappliesGrantsWithoutEnablingApisOrCreatingAKey() throws Exception {
        Result result = run("my-proj", "read-write", "--refresh");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("services", "enable")).isEmpty();
        assertThat(result.calls("iam", "service-accounts", "keys", "create")).isEmpty();
        assertThat(result.calls("iam", "service-accounts", "create")).isEmpty();
        assertThat(result.projectBindings()).contains(CUSTOM_ROLE_AUTOSPOTTING, CUSTOM_ROLE_COMPUTE_OPS);
        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(APP_SA, DEFAULT_SA, VM_SA);
    }

    @Test
    void dryRunChangesNothingAndShowsThePlannedCommands() throws Exception {
        Result result = run("my-proj", "read-write", "--dry-run");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.mutatingCalls()).isEmpty();
        assertThat(result.output())
                .contains("[dry-run] gcloud iam roles create xamopsAutoSpotting")
                .contains("[dry-run] gcloud iam roles create xamopsComputeOps")
                .contains("[dry-run] gcloud iam service-accounts add-iam-policy-binding " + APP_SA)
                .contains("[dry-run] gcloud iam service-accounts add-iam-policy-binding " + DEFAULT_SA)
                .contains("[dry-run] gcloud iam service-accounts add-iam-policy-binding " + VM_SA);
        try (Stream<Path> files = Files.list(work)) {
            assertThat(files).as("no key file in a dry run").isEmpty();
        }
    }

    // ------------------------------------------------------------------ 7. legacy roles

    @Test
    void revokeLegacyRolesRemovesEachOneAfterTheReplacementGrants() throws Exception {
        List<String> projectRoles = new ArrayList<>(LEGACY_ROLES);
        projectRoles.add("roles/viewer");
        Result result = run(Map.of("STUB_PROJECT_ROLES", String.join("\n", projectRoles)),
                "my-proj", "read-write", "--revoke-legacy-roles");

        assertThat(result.exitCode()).as(result.output()).isZero();
        List<List<String>> removals = result.calls("projects", "remove-iam-policy-binding");
        assertThat(removals).extracting(c -> flag(c, "--role").orElseThrow())
                .containsExactlyInAnyOrderElementsOf(LEGACY_ROLES);
        int lastServiceAccountGrant = result.lastIndexOf("iam", "service-accounts", "add-iam-policy-binding");
        assertThat(lastServiceAccountGrant).isNotNegative();
        for (List<String> removal : removals) {
            assertThat(result.calls().indexOf(removal)).as(String.join(" ", removal))
                    .isGreaterThan(lastServiceAccountGrant);
        }
    }

    @Test
    void withoutTheFlagLegacyRolesAreOnlyReported() throws Exception {
        Result result = run(Map.of("STUB_PROJECT_ROLES", String.join("\n", LEGACY_ROLES)), "my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("projects", "remove-iam-policy-binding")).isEmpty();
        assertThat(result.output()).contains("--refresh --revoke-legacy-roles");
    }

    @Test
    void revokeLegacyRolesStopsWhenTheIamPolicyCannotBeRead() throws Exception {
        Result result = run(Map.of("STUB_FAIL", "get-iam-policy", "STUB_PROJECT_ROLES", String.join("\n", LEGACY_ROLES)),
                "my-proj", "read-write", "--revoke-legacy-roles");

        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("Could not read the IAM policy of 'my-proj'");
        assertThat(result.calls("projects", "remove-iam-policy-binding")).isEmpty();
        assertThat(result.calls("iam", "service-accounts", "keys", "create")).isEmpty();
    }

    // ------------------------------------------------------------------ 8. service account name

    @Test
    void aCustomServiceAccountNameIsTheOnlyMemberGranted() throws Exception {
        Result result = run("my-proj", "read-write", "--service-account-name", "onb-as-test");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("iam", "service-accounts", "create")).singleElement()
                .satisfies(c -> assertThat(c.get(3)).isEqualTo("onb-as-test"));
        List<String> members = result.calls().stream()
                .flatMap(c -> c.stream().filter(a -> a.startsWith("--member=")))
                .distinct()
                .toList();
        assertThat(members).containsExactly("--member=serviceAccount:onb-as-test@my-proj.iam.gserviceaccount.com");
    }

    // ------------------------------------------------------------------ 9. bad input

    static Stream<List<String>> badInput() {
        return Stream.of(
                List.of("my-proj", "read-only", "--revoke-legacy-roles"),
                List.of("my-proj", "--revoke-legacy-roles"),
                List.of("my-proj", "--unknown-flag"),
                List.of("BAD_ID"),
                List.of("my-proj", "read-sometimes"),
                List.of(),
                List.of("my-proj", "read-write", "--service-account-name", "No"));
    }

    @ParameterizedTest
    @MethodSource("badInput")
    void badInputStopsBeforeAnyGcloudCall(List<String> args) throws Exception {
        Result result = run(args.toArray(String[]::new));

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.mutatingCalls()).isEmpty();
        assertThat(result.calls()).as("validation runs before the first gcloud call").isEmpty();
    }

    // ------------------------------------------------------------------ 10. POSIX guard

    @Test
    void underDashTheScriptSaysToUseBash() throws Exception {
        Optional<Path> dash = onPath("dash");
        Assumptions.assumeTrue(dash.isPresent(), "dash is not installed");

        Result result = run(dash.get().toString(), Map.of(), "my-proj");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("This script needs bash");
        assertThat(result.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ 11. custom roles in a deleted state

    @Test
    void aDeletedCustomRoleIsUndeletedAndThenSetToTheExactDefinition() throws Exception {
        Result result = run(Map.of("STUB_DELETED_ROLES", "xamopsAutoSpotting xamopsComputeOps"), "my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("iam", "roles", "create")).isEmpty();
        for (String roleId : List.of("xamopsAutoSpotting", "xamopsComputeOps")) {
            assertThat(result.calls("iam", "roles", "undelete", roleId)).singleElement()
                    .satisfies(c -> assertThat(c).contains("--project=my-proj"));
            assertThat(result.lastIndexOf("iam", "roles", "undelete", roleId)).as(roleId + " is undeleted before the update")
                    .isLessThan(result.lastIndexOf("iam", "roles", "update", roleId));
        }
        assertCustomRole(result, "update", "xamopsAutoSpotting", AUTOSPOTTING_PERMISSIONS);
        assertCustomRole(result, "update", "xamopsComputeOps", COMPUTE_OPS_PERMISSIONS);
        assertThat(result.projectBindings()).contains(CUSTOM_ROLE_AUTOSPOTTING, CUSTOM_ROLE_COMPUTE_OPS);
        assertKeyFileIsPrivateAndNeverPrinted(result);
    }

    static Stream<Arguments> roleCreateErrors() {
        return Stream.of(
                // What gcloud users report for a role ID that is still reserved after deletion.
                Arguments.of("ERROR: (gcloud.iam.roles.create) FAILED_PRECONDITION: You can't create a role_id"
                        + " (xamopsAutoSpotting) which has been marked for deletion.", true),
                // The wording review finding 5 expected.
                Arguments.of("ERROR: (gcloud.iam.roles.create) FAILED_PRECONDITION: You can't create a role with role_id"
                        + " (xamopsAutoSpotting) where there is an existing role with that role_id in a deleted state.", true),
                Arguments.of("ERROR: (gcloud.iam.roles.create) PERMISSION_DENIED: Permission 'iam.roles.create' denied"
                        + " on resource (or it may not exist).", false));
    }

    @ParameterizedTest
    @MethodSource("roleCreateErrors")
    void aCustomRoleThatCannotBeCreatedStopsWithoutAKey(String error, boolean idStillReserved) throws Exception {
        Result result = run(Map.of("STUB_ROLE_CREATE_ERROR", error), "my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("Failed to create or update the custom role xamopsAutoSpotting", error);
        if (idStillReserved) {
            assertThat(result.output()).contains("Google Cloud reserves a deleted custom role ID");
        } else {
            assertThat(result.output()).doesNotContain("Google Cloud reserves a deleted custom role ID");
        }
        assertThat(result.calls("iam", "service-accounts", "add-iam-policy-binding")).isEmpty();
        assertThat(result.calls("iam", "service-accounts", "keys", "create")).isEmpty();
    }

    // ------------------------------------------------------------------ 12. discovery failures

    @Test
    void aListingThatFailsOnceIsRetried() throws Exception {
        Result result = run(Map.of("STUB_FAIL_ONCE", "instances"), "my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("compute", "instances", "list")).hasSize(2);
        assertThat(result.output()).doesNotContain("could not be listed");
        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(APP_SA, DEFAULT_SA, VM_SA);
    }

    @Test
    void aListingThatFailsTwiceIsReportedAndWhatWasFoundIsStillGranted() throws Exception {
        Result result = run(Map.of("STUB_FAIL", "instance-groups"), "my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("compute", "instance-groups", "managed", "list")).as("two listings, each tried twice")
                .hasSize(4);
        assertThat(result.output()).contains("WARNING: Some instance groups, templates or VMs could not be listed",
                "my-proj read-write --refresh");
        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(DEFAULT_SA, VM_SA);
        assertKeyFileIsPrivateAndNeverPrinted(result);
    }

    @Test
    void revokeLegacyRolesRefusesAfterAFailedDiscovery() throws Exception {
        Result result = run(Map.of("STUB_FAIL", "instance-groups", "STUB_PROJECT_ROLES", String.join("\n", LEGACY_ROLES)),
                "my-proj", "read-write", "--refresh", "--revoke-legacy-roles");

        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.calls("projects", "remove-iam-policy-binding")).isEmpty();
        assertThat(result.output()).contains("The legacy project-wide write roles were not removed",
                "my-proj read-write --refresh --revoke-legacy-roles");
        assertThat(result.serviceAccountBindings()).as("the grants that could be made are in place")
                .containsExactlyInAnyOrder(DEFAULT_SA, VM_SA);
    }

    @Test
    void afterAFailedDiscoveryRevokeLegacyRolesHasNothingToRefuseWhenNoneAreHeld() throws Exception {
        Result result = run(Map.of("STUB_FAIL", "instance-groups"), "my-proj", "read-write", "--refresh", "--revoke-legacy-roles");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("projects", "remove-iam-policy-binding")).isEmpty();
        assertThat(result.output()).contains("could not be listed").doesNotContain("were not removed");
    }

    // ------------------------------------------------------------------ 13. service accounts that cannot be checked

    @Test
    void aServiceAccountThatIsGoneIsSkipped() throws Exception {
        Result result = run(Map.of("STUB_SA_NOT_FOUND", APP_SA), "my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains(APP_SA + " does not exist (a template or VM still names it); skipped");
        assertThat(result.calls("iam", "service-accounts", "describe", APP_SA)).as("NOT_FOUND is not retried").hasSize(1);
        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(DEFAULT_SA, VM_SA);
        assertKeyFileIsPrivateAndNeverPrinted(result);
    }

    @Test
    void aServiceAccountThatCannotBeCheckedStopsTheRunBeforeAnyLegacyRoleIsRemoved() throws Exception {
        Result result = run(Map.of("STUB_FAIL", APP_SA, "STUB_PROJECT_ROLES", String.join("\n", LEGACY_ROLES)),
                "my-proj", "read-write", "--revoke-legacy-roles");

        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);
        assertThat(result.calls("iam", "service-accounts", "describe", APP_SA)).as("tried twice").hasSize(2);
        assertThat(result.output()).contains("Could not check that this service account exists", "PERMISSION_DENIED")
                .doesNotContain(APP_SA + " does not exist");
        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(DEFAULT_SA, VM_SA);
        assertThat(result.calls("projects", "remove-iam-policy-binding")).isEmpty();
        assertThat(result.calls("iam", "service-accounts", "keys", "create")).isEmpty();
    }

    @Test
    void aServiceAccountCheckThatFailsOnceIsRetried() throws Exception {
        Result result = run(Map.of("STUB_FAIL_ONCE", APP_SA), "my-proj", "read-write");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("iam", "service-accounts", "describe", APP_SA)).hasSize(2);
        assertThat(result.serviceAccountBindings()).containsExactlyInAnyOrder(APP_SA, DEFAULT_SA, VM_SA);
    }

    // ------------------------------------------------------------------ 14. summary

    @Test
    void theSummarySaysBillingDataIsLimitedWhenBillingViewerWasNotGranted() throws Exception {
        Result result = run(Map.of("STUB_UNGRANTABLE_ROLES", BILLING_VIEWER), "my-proj");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Billing & cost data: limited (not granted: " + BILLING_VIEWER)
                .doesNotContain("Billing & cost data (full access)");
        assertKeyFileIsPrivateAndNeverPrinted(result);
    }

    // ------------------------------------------------------------------ helpers

    private Result run(String... args) throws Exception {
        return run("bash", Map.of(), args);
    }

    private Result run(Map<String, String> env, String... args) throws Exception {
        return run("bash", env, args);
    }

    private Result run(String shell, Map<String, String> env, String... args) throws Exception {
        Files.write(log, new byte[0]);
        List<String> command = new ArrayList<>(List.of(shell, SCRIPT.toString()));
        command.addAll(Arrays.asList(args));

        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
        environment.put("STUB_LOG", log.toString());
        environment.put("STUB_STATE", state.toString());
        environment.putAll(env);

        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("script did not finish within 60 s");
        }
        String text = output.get(10, TimeUnit.SECONDS);
        List<List<String>> calls = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isEmpty())
                .map(line -> List.of(line.split("\t", -1)))
                .toList();
        return new Result(process.exitValue(), text, calls);
    }

    private Path assertKeyFileIsPrivateAndNeverPrinted(Result result) throws IOException {
        List<List<String>> creates = result.calls("iam", "service-accounts", "keys", "create");
        assertThat(creates).hasSize(1);
        Path key = work.resolve(creates.get(0).get(4));
        assertThat(key).isRegularFile();
        assertThat(Files.getPosixFilePermissions(key)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(result.output()).doesNotContain("STUB-KEY-MARKER").doesNotContain("private_key");
        return key;
    }

    private static void assertCustomRole(Result result, String verb, String roleId, String permissions) {
        assertThat(result.calls("iam", "roles", verb, roleId)).singleElement().satisfies(c -> assertThat(c)
                .contains("--project=my-proj", "--permissions=" + permissions, "--stage=GA"));
    }

    private static boolean isMutating(List<String> call) {
        String joined = String.join(" ", call);
        return joined.startsWith("services enable")
                || joined.startsWith("iam service-accounts create")
                || joined.startsWith("iam service-accounts keys create")
                || (call.contains("add-iam-policy-binding") && !call.contains("--help"))
                || call.contains("remove-iam-policy-binding")
                || joined.startsWith("iam roles create")
                || joined.startsWith("iam roles update")
                || joined.startsWith("iam roles undelete");
    }

    private static Optional<String> flag(List<String> call, String name) {
        return call.stream().filter(a -> a.startsWith(name + "=")).map(a -> a.substring(name.length() + 1)).findFirst();
    }

    private static List<String> withBilling(List<String> roles) {
        List<String> all = new ArrayList<>(roles);
        all.add(BILLING_VIEWER);
        return all;
    }

    private static Optional<Path> onPath(String program) {
        String path = System.getenv().getOrDefault("PATH", "");
        return Arrays.stream(path.split(File.pathSeparator))
                .filter(dir -> !dir.isEmpty())
                .map(dir -> Path.of(dir, program))
                .filter(Files::isExecutable)
                .findFirst();
    }

    private static void executable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
