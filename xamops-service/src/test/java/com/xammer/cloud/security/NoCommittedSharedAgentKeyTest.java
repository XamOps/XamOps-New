package com.xammer.cloud.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-6 CI guard: the committed shared agent key must not reappear in any tracked source file.
 *
 * <p>
 * The literal was removed from all 11 source sites (server profiles, the served + repo
 * installers, and the k8s manifest) and replaced with templated/env values. This test walks the
 * worktree root and fails if the key is found anywhere outside the excluded directories — so a
 * future paste that re-introduces it breaks the build. After the removal the string survives only
 * in the {@code .agents} audit/reference copies, which are excluded (and never shipped).
 *
 * <p>
 * The needle is assembled from fragments so this test file does not itself contain the literal.
 */
class NoCommittedSharedAgentKeyTest {

    private static final String NEEDLE = "xamops_" + "SecureAgentKey2024" + "XamOps";
    private static final Set<String> EXCLUDED_DIRS = Set.of(".git", "node_modules", "target", ".agents");
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    @Test
    void sharedKeyLiteralIsAbsentFromTrackedSource() throws IOException {
        Path root = worktreeRoot();
        byte[] needle = NEEDLE.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        List<String> offenders = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return EXCLUDED_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_BYTES) {
                        byte[] bytes = Files.readAllBytes(file);
                        if (indexOf(bytes, needle) >= 0) {
                            offenders.add(root.relativize(file).toString());
                        }
                    }
                } catch (IOException ignored) {
                    // Unreadable file (e.g. a transient lock) cannot carry a committed literal.
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(offenders)
                .as("committed shared agent key must not appear in tracked source; found in: %s", offenders)
                .isEmpty();
    }

    /** Walk up from the working directory to the worktree root (holds xamops-service + xamops-agent). */
    private static Path worktreeRoot() {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("xamops-service")) && Files.isDirectory(p.resolve("xamops-agent"))) {
                return p;
            }
        }
        // Fallback: the module's parent, matching "resolve .. from the module dir".
        return start.getParent() != null ? start.getParent() : start;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
