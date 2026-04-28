package io.quarkus.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test suite that runs migration skills against test projects and verifies the results.
 *
 * <p>Configuration via system properties:
 * <ul>
 *   <li>{@code pi.model} — model to use (default: vertex-anthropic/claude-sonnet-4-5@20250929)</li>
 *   <li>{@code pi.strategy} — migration strategy: full or compatibility (default: full)</li>
 *   <li>{@code pi.timeout} — timeout in seconds per project (default: 300)</li>
 *   <li>{@code pi.cmd} — path to pi binary (default: pi)</li>
 *   <li>{@code pi.project} — run only this project (default: all)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * # Run all projects with defaults
 * mvn test
 *
 * # Run specific project
 * mvn test -Dpi.project=spring-rest-api
 *
 * # Compare models
 * mvn test -Dpi.model=vertex-anthropic/claude-sonnet-4-5@20250929
 * mvn test -Dpi.model=google/gemini-2.5-pro
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ResultsTracker tracker = ResultsTracker.defaultTracker();
    private static final SkillResolver skillResolver = new SkillResolver(
            skillsDir(), Path.of("target", "skills").toAbsolutePath());

    // -- config from system properties --

    static String piProvider() {
        return System.getProperty("pi.provider", "");
    }

    static String piModel() {
        return System.getProperty("pi.model", "");
    }

    /** Display string for the provider/model combination. */
    static String piModelDisplay() {
        String p = piProvider();
        String m = piModel();
        if (!p.isEmpty() && !m.isEmpty()) return p + "/" + m;
        if (!p.isEmpty()) return p + "/(default)";
        if (!m.isEmpty()) return m;
        return "(pi default)";
    }

    static String piStrategy() {
        return System.getProperty("pi.strategy", "full");
    }

    static int piTimeout() {
        return Integer.parseInt(System.getProperty("pi.timeout", "300"));
    }

    static String piCmd() {
        return System.getProperty("pi.cmd", "pi");
    }

    static String piProject() {
        return System.getProperty("pi.project", "");
    }

    /** Override the skill from project.yaml. Accepts a name, absolute path, or URL. */
    static String piSkill() {
        return System.getProperty("pi.skill", "");
    }

    /** Explicit branch when pi.skill is a URL — avoids ambiguity with slash-containing branch names. */
    static String piSkillBranch() {
        return System.getProperty("pi.skill.branch", "");
    }

    /** Explicit subpath within the cloned repo when pi.skill is a URL. */
    static String piSkillPath() {
        return System.getProperty("pi.skill.path", "");
    }

    // -- discover test projects --

    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        // If we're in tests/, go up one level
        if (dir.getFileName().toString().equals("tests") && Files.isDirectory(dir.resolve("projects"))) {
            return dir.getParent();
        }
        // If we're at repo root
        if (Files.isDirectory(dir.resolve("skills"))) {
            return dir;
        }
        // Otherwise try parent
        if (dir.getParent() != null && Files.isDirectory(dir.getParent().resolve("skills"))) {
            return dir.getParent();
        }
        return dir;
    }

    static Path projectsDir() {
        // First check if we're running from tests/ dir
        Path testsDir = Path.of("").toAbsolutePath();
        if (Files.isDirectory(testsDir.resolve("projects"))) {
            return testsDir.resolve("projects");
        }
        return repoRoot().resolve("tests").resolve("projects");
    }

    static Path skillsDir() {
        return repoRoot().resolve("skills");
    }

    static Stream<Arguments> migrationProjects() throws IOException {
        Path projects = projectsDir();
        String filter = piProject();

        try (var dirs = Files.list(projects)) {
            return dirs
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("project.yaml")))
                    .filter(p -> filter.isEmpty() || p.getFileName().toString().equals(filter))
                    .sorted()
                    .map(p -> {
                        try {
                            ProjectConfig config = YAML.readValue(
                                    p.resolve("project.yaml").toFile(),
                                    ProjectConfig.class);
                            return Arguments.of(config, p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList()  // materialize before stream closes
                    .stream();
        }
    }

    // -- the actual test --

    @ParameterizedTest(name = "{0}")
    @MethodSource("migrationProjects")
    @Order(1)
    void migrate(ProjectConfig config, Path projectDir) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PROJECT: " + config.name());
        System.out.println("  provider: " + (piProvider().isEmpty() ? "(default)" : piProvider()));
        System.out.println("  model:    " + (piModel().isEmpty() ? "(default)" : piModel()));
        System.out.println("  strategy: " + piStrategy());
        System.out.println("  timeout:  " + piTimeout() + "s");
        System.out.println("  checks:   " + config.checks());
        System.out.println("=".repeat(60));

        // 1. Prepare working directory and output directory
        Path workDir = prepareWorkDir(config, projectDir);

        // Build a run name: project_provider_model_strategy
        String providerShort = piProvider().isEmpty() ? "default" : piProvider().replaceAll("[^a-zA-Z0-9-]", "-");
        String modelShort = piModel().isEmpty() ? "default" : piModel().replaceAll("[^a-zA-Z0-9-]", "-");
        String runName = config.name() + "_" + providerShort + "_" + modelShort + "_" + piStrategy();
        Path outputDir = Path.of("target", "runs").toAbsolutePath();

        System.out.println("  workdir:  " + workDir);
        System.out.println("  outputs:  " + outputDir.resolve(runName + ".*"));

        MigrationResult result = new MigrationResult(
                config.name(), piModelDisplay(), piStrategy(), config.skill());
        result.setWorkDir(workDir.toString());
        result.setRunName(runName);

        // 2. Run pi migration agent
        String skillRef = piSkill().isEmpty() ? config.skill() : piSkill();
        Path skillPath = skillResolver.resolve(skillRef, piSkillBranch(), piSkillPath());
        assertTrue(Files.isDirectory(skillPath),
                "Skill directory not found: " + skillPath);

        int timeout = config.timeout() > 0 ? config.timeout() : piTimeout();
        PiRunner runner = new PiRunner(piCmd(), piProvider(), piModel(), skillPath, piStrategy(), timeout);

        System.out.println("  Running migration agent...");
        PiRunner.RunOutput output = runner.run(workDir, outputDir, runName);

        result.setPiExitCode(output.exitCode());
        result.setDuration(output.duration());
        result.setSessionFile(output.sessionFile());

        System.out.println("  Migration completed in " + output.duration().toSeconds() + "s (exit=" + output.exitCode() + ")");

        // 3. Extract usage stats from session
        PiRunner.UsageStats usage = PiRunner.extractUsage(output.sessionFile());
        result.setTotalTokens(usage.totalTokens());
        result.setTotalCost(usage.totalCost());
        result.setApiCalls(usage.apiCalls());

        System.out.println("  Tokens: " + usage.totalTokens() +
                "  Cost: $" + String.format("%.4f", usage.totalCost()) +
                "  API calls: " + usage.apiCalls());

        // 4. Run checks
        MigrationChecks checks = new MigrationChecks(workDir);
        System.out.println("  Running checks...");

        List<String> failures = new ArrayList<>();
        for (String check : config.checks()) {
            System.out.print("    " + check + " ... ");
            boolean passed = checks.runCheck(check);
            result.addCheck(check, passed);
            System.out.println(passed ? "✅" : "❌");
            if (!passed) {
                failures.add(check);
            }
        }

        // 5. Run skill review (separate pi session)
        PiRunner.ReviewOutput reviewOutput = runner.review(
                output.sessionFile(), workDir, outputDir, runName, skillPath, result.getChecks());
        result.setReview(reviewOutput.review());
        result.setReviewTokens(reviewOutput.usage().totalTokens());
        result.setReviewCost(reviewOutput.usage().totalCost());

        // 6. Record result
        tracker.record(result);
        System.out.println("\n" + result);

        // 7. Assert all checks passed
        if (!failures.isEmpty()) {
            fail("Migration checks failed: " + failures + "\n" +
                    "Work dir preserved at: " + workDir + "\n" +
                    "Score: " + result.score());
        }
    }

    // -- helpers --

    private Path prepareWorkDir(ProjectConfig config, Path projectDir) throws IOException, InterruptedException {
        // Put work dirs under target/workdirs/ so they survive JVM exit but get cleaned on mvn clean
        Path workdirsBase = Path.of("").toAbsolutePath().resolve("target").resolve("workdirs");
        Path workDir = workdirsBase.resolve(config.name());
        // Clean any previous run
        if (Files.exists(workDir)) {
            try (var walk = Files.walk(workDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
        Files.createDirectories(workDir);

        if (config.isLocal()) {
            Path source = projectDir.resolve("source");
            assertTrue(Files.isDirectory(source),
                    "Local source directory not found: " + source);
            copyDirectory(source, workDir);
        } else {
            // Clone from git
            List<String> cmd = new ArrayList<>(List.of(
                    "git", "clone", "--depth", "1"));
            if (config.ref() != null && !config.ref().isBlank()) {
                cmd.addAll(List.of("--branch", config.ref()));
            }
            cmd.add(config.source());
            cmd.add(workDir.toString());

            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();

            boolean done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(done && p.exitValue() == 0,
                    "Failed to clone " + config.source());
        }

        return workDir;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                Path dest = target.resolve(source.relativize(src));
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
