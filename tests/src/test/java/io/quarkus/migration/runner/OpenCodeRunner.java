package io.quarkus.migration.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OpenCodeRunner extends AbstractRunner implements AgentRunner {

    public OpenCodeRunner(String aiCmd, String provider, String model, Path skillPath, String strategy, int timeoutSeconds,
            String prompt) {
        super(aiCmd, provider, model, skillPath, strategy, timeoutSeconds, prompt);
    }

    /**
     * Run the migration opencode agent against the given project directory. Streams structured JSON output to console in
     * real-time.
     *
     * @param projectDir the project to migrate
     * @param outputDir where to store run artifacts (logs, session, etc.)
     * @param runName prefix for output files (e.g. "spring-rest-api_anthropic_full")
     */
    @Override
    public RunOutput run(Path projectDir, Path outputDir, String runName) throws IOException, InterruptedException {

        // Copy the SKILL from the skillPath resolver to the local opencode skill directory
        Path projectSkillsPath = Path.of(".opencode", "skills").toAbsolutePath();
        Files.createDirectories(projectSkillsPath);
        copySkills(skillPath, projectSkillsPath);

        // Use the user's prompt or the one to be used for the migration test
        var userPrompt = prompt.isEmpty() ? generateMigrationPrompt() : prompt;

        List<String> cmd = new ArrayList<>();
        cmd.add(aiCmd);
        cmd.add("run");
        addModelArgs(cmd);

        cmd.add(userPrompt);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(projectDir.toFile())
                .redirectErrorStream(true);

        Instant start = Instant.now();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            System.err.println("  ERROR: Failed to start opencode: " + e.getMessage());
            // TODO: Do we have an opencode log file and/or session file
            return new RunOutput(-1, Duration.ZERO, null, null);
        }

        System.out.println("  opencode pid:  " + process.pid());
        System.out.println("─".repeat(60));

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        Duration duration = Duration.between(start, Instant.now());

        int exitCode;
        if (!finished) {
            System.out.println("\n  ⏰ TIMEOUT after " + timeoutSeconds + "s — killing opencode");
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            exitCode = -1;
        } else {
            exitCode = process.exitValue();
        }

        String summary = "\n" + "─".repeat(60) + "\n" +
                "  opencode exit: " + exitCode + "  duration: " + duration.toSeconds() + "s";

        return new RunOutput(exitCode,duration,null,null);
    }

    @Override
    public ReviewOutput review(String sessionFile, Path projectDir, Path outputDir, String runName, Path skillPath,
            Map<String, Boolean> checkResults) {
        return null;
    }
}
