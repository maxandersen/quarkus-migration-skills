package io.quarkus.migration.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SessionExporter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Export the list of the sessions using as key the title id
     *
     * @param targetId the Title id to search about
     * @return the List of the session id
     */
    public static List<String> getSessionIdsByTitle(String targetId) {
        List<String> cmd = new ArrayList<>();
        cmd.add("opencode");
        cmd.add("session");
        cmd.add("list");
        cmd.add("--format");
        cmd.add("json");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String fullJsonOutput = reader.lines().collect(Collectors.joining("\n"));
                process.waitFor();

                List<Map<String, Object>> sessions = objectMapper.readValue(
                        fullJsonOutput,
                        new TypeReference<List<Map<String, Object>>>() {}
                );

                return sessions.stream()
                        .filter(s -> targetId.equalsIgnoreCase((String) s.get("title")))
                        .map(s -> (String) s.get("id"))
                        .filter(id -> id != null)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.err.println("Error gathering session IDs: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Finds all sessions matching a title, exports them using the sanitize flag,
     * and writes the output stream directly into a target folder.
     *
     * @param targetTitle  The project title to filter by (e.g., "project_001")
     * @param outputDir   The directory path where the session files will be saved
     * @return the list of the session files
     */
    public static List<String> exportSessions(String targetTitle, Path outputDir) {
        List<String> sessionFiles = new ArrayList<>();
        try {
            System.out.println("Fetching the session list using the title id: " + targetTitle);
            List<String> sessionIds = getSessionIdsByTitle(targetTitle);

            if (sessionIds.isEmpty()) {
                System.out.println("No matching sessions found to export.");
                return sessionFiles;
            }

            System.out.println("Found " + sessionIds.size() + " sessions");
            sessionIds.forEach(s -> System.out.printf("Sessions id: %s.%n", s));

            System.out.println("Starting to export them ...");

            for (String sessionId : sessionIds) {
                sessionFiles.add(logSessionContent(sessionId, outputDir));
            }

            System.out.println("Successfully processed all exports.");
            return sessionFiles;

        } catch (Exception e) {
            System.err.println("Error initializing export strategy: " + e.getMessage());
        }
        return sessionFiles;
    }

    private static String logSessionContent(String sessionId, Path outputDir) {
        File sessionFile = outputDir.resolve(sessionId + ".session.jsonl").toFile();

        // Build: opencode export <session_ID> --sanitize
        List<String> cmd = new ArrayList<>();
        cmd.add("opencode");
        cmd.add("export");
        cmd.add(sessionId);
        cmd.add("--sanitize");
        ProcessBuilder exportBuilder = new ProcessBuilder(cmd);

        try {
            exportBuilder.redirectOutput(sessionFile);
            exportBuilder.redirectError(ProcessBuilder.Redirect.INHERIT); // Print errors to Java console

            Process process = exportBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println(" -> Exported: " + sessionFile.getName());
            } else {
                System.err.println(" -> Failed to export " + sessionId + " (Exit code: " + exitCode + ")");
            }

        } catch (Exception e) {
            System.err.println(" -> Exception running export for " + sessionId + ": " + e.getMessage());
        }

        return sessionFile.getAbsolutePath();
    }
}