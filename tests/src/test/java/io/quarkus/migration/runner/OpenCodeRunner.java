package io.quarkus.migration.runner;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.quarkus.migration.runner.SessionExporter.exportSessions;

public class OpenCodeRunner extends AbstractRunner implements AgentRunner {

    public OpenCodeRunner(String aiCmd, String provider, String model, Path skillPath, String strategy, int timeoutSeconds,
            String prompt) {
        super(aiCmd, provider, model, skillPath, strategy, timeoutSeconds, prompt);
    }

    /**
     * Run the opencode agent against the given project directory. Streams structured JSON output to console in
     * real-time.
     *
     * @param projectDir the project to migrate
     * @param outputDir where to store run artifacts (logs, session, etc.)
     * @param runName prefix for output files (e.g. "spring-rest-api_anthropic_full")
     */
    @Override
    public RunOutput run(Path projectDir, Path outputDir, String runName) throws IOException, InterruptedException {

        // Create the output directories where files will be exported
        Files.createDirectories(outputDir);

        // Copy the SKILL from the skillPath resolver to the local opencode skill directory
        Path projectSkillsPath = Path.of(".opencode", "skills").toAbsolutePath();
        Files.createDirectories(projectSkillsPath);
        copySkills(skillPath, projectSkillsPath);

        // Use the user's prompt or the one to be used for the migration test
        var userPrompt = prompt.isEmpty() ? generateMigrationPrompt() : prompt;

        List<String> cmd = new ArrayList<>();
        // Wrap with script to provide a pseudo-TTY — without it, opencode suppresses stdout output
        cmd.addAll(List.of("script", "-q", "/dev/null"));
        cmd.add(aiCmd);
        cmd.add("run");
        cmd.add("--format");
        cmd.add("json");
        cmd.add("--title");
        cmd.add(runName); // We run as Title Id the name of the run session
        addModelArgs(cmd);

        cmd.add(userPrompt);

        System.out.println("  ai cwd:     " + projectDir);
        System.out.println("  output dir: " + outputDir);
        System.out.println("  run name:   " + runName);
        System.out.println("  ai cmd:   " + cmd);
        System.out.println();

        Path logFile = outputDir.resolve(runName + ".json.log");
        Path prettyFile = outputDir.resolve(runName + ".pretty.md");

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(projectDir.toFile())
                .redirectErrorStream(true);

        Instant start = Instant.now();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            System.err.println("  ERROR: Failed to start opencode: " + e.getMessage());
            return new RunOutput(-1, Duration.ZERO, null, logFile.toString());
        }

        System.out.println("  opencode pid:  " + process.pid());
        System.out.println("─".repeat(60));

        Thread readerThread;
        try (var logWriter = new BufferedWriter(new FileWriter(logFile.toFile()));
                var prettyWriter = new BufferedWriter(new FileWriter(prettyFile.toFile()))) {
            readerThread = Thread.startVirtualThread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Write raw JSONL to log
                        synchronized (logWriter) {
                            logWriter.write(line);
                            logWriter.newLine();
                            logWriter.flush();
                        }

                        // Parse and print human-readable summary
                        try {
                            JsonNode event = JSON.readTree(line);
                            printEvent(event, prettyWriter);
                        } catch (Exception e) {
                            // Not JSON (e.g. script command noise), print as-is
                            if (!line.isBlank()) {
                                printBoth("  │ " + line, prettyWriter);
                            }
                        }
                    }
                } catch (IOException e) {
                    // process closed stream
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            Duration duration = Duration.between(start, Instant.now());

            int exitCode;
            if (!finished) {
                System.out.println("\n  ⏰ TIMEOUT after " + timeoutSeconds + "s — killing opencode");
                // Kill the entire process tree (children first), not just the direct process,
                // otherwise child processes survive SIGKILL and keep running
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }

            readerThread.join(5000);

            String summary = "\n" + "─".repeat(60) + "\n" +
                    "  opencode exit: " + exitCode + "  duration: " + duration.toSeconds() + "s";
            printBoth(summary, prettyWriter);

            // Find the session files matching the title id and export them
            var sessionFiles = exportSessions(runName, outputDir);

            return new RunOutput(exitCode, duration, sessionFiles, logFile.toString());
        }
    }

    @Override
    public ReviewOutput review(String sessionFile, Path projectDir, Path outputDir, String runName, Path skillPath,
            Map<String, Boolean> checkResults) {
        return null;
    }
}
