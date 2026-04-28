package io.quarkus.migration;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a skill reference to a local directory path.
 *
 * Supported formats for the skill reference:
 *   - Skill name       → looks up in the local skills/ directory
 *   - Absolute path    → used as-is
 *   - URL              → clones the repo; branch and subpath can come from either
 *                        the URL (/tree/branch/subpath) or explicit parameters
 *
 * Explicit pi.skill.branch / pi.skill.path always win over URL-parsed values,
 * which avoids the ambiguity when branch names contain slashes.
 *
 * Remote clones are cached in downloadDir for the duration of the test run.
 */
public class SkillResolver {

    private final Path skillsBaseDir;
    private final Path downloadDir;
    private final Map<String, Path> cache = new ConcurrentHashMap<>();

    public SkillResolver(Path skillsBaseDir, Path downloadDir) {
        this.skillsBaseDir = skillsBaseDir;
        this.downloadDir = downloadDir;
    }

    /**
     * Resolve with optional explicit branch and subpath overrides.
     * For URLs: explicit values take precedence over anything parsed from the URL.
     */
    public Path resolve(String skillRef, String explicitBranch, String explicitPath)
            throws IOException, InterruptedException {
        if (skillRef == null || skillRef.isBlank()) {
            throw new IllegalArgumentException("Skill reference cannot be empty");
        }

        if (isUrl(skillRef)) {
            return resolveFromUrl(skillRef, blankToNull(explicitBranch), blankToNull(explicitPath));
        }

        Path p = Path.of(skillRef);
        if (p.isAbsolute()) {
            return p;
        }

        return skillsBaseDir.resolve(skillRef);
    }

    private Path resolveFromUrl(String url, String explicitBranch, String explicitPath)
            throws IOException, InterruptedException {

        String cacheKey = url + "|" + nullToEmpty(explicitBranch) + "|" + nullToEmpty(explicitPath);
        if (cache.containsKey(cacheKey)) {
            System.out.println("  Using cached skill: " + cache.get(cacheKey));
            return cache.get(cacheKey);
        }

        // Parse /tree/branch[/subpath] from GitHub-style URLs
        String cloneUrl = url;
        String parsedBranch = null;
        String parsedSubPath = "";

        if (url.contains("/tree/")) {
            int treeIdx = url.indexOf("/tree/");
            cloneUrl = url.substring(0, treeIdx);
            String rest = url.substring(treeIdx + "/tree/".length()); // "branch[/subpath]"
            int slashIdx = rest.indexOf('/');
            if (slashIdx >= 0) {
                parsedBranch = rest.substring(0, slashIdx);
                parsedSubPath = rest.substring(slashIdx + 1);
            } else {
                parsedBranch = rest;
            }
        }

        // Explicit params win over URL-parsed values
        String branch = explicitBranch != null ? explicitBranch : parsedBranch;
        String subPath = explicitPath  != null ? explicitPath  : parsedSubPath;

        // Include branch in dir name to avoid clashes between branches of the same repo
        String dirKey = cloneUrl + (branch != null ? "@" + branch : "");
        String dirName = dirKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path cloneDir = downloadDir.resolve(dirName);

        if (!Files.exists(cloneDir)) {
            System.out.println("  Fetching skill from: " + cloneUrl +
                    (branch != null ? " (branch: " + branch + ")" : ""));
            Files.createDirectories(downloadDir);

            List<String> cloneCmd = new ArrayList<>(List.of("git", "clone", "--depth", "1"));
            if (branch != null) cloneCmd.addAll(List.of("--branch", branch));
            cloneCmd.addAll(List.of(cloneUrl, cloneDir.toString()));

            Process p = new ProcessBuilder(cloneCmd)
                    .redirectErrorStream(true)
                    .start();

            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done || p.exitValue() != 0) {
                throw new IOException("Failed to clone skill from: " + cloneUrl);
            }
            System.out.println("  Cloned to: " + cloneDir);
        } else {
            System.out.println("  Reusing cached clone: " + cloneDir);
        }

        Path skillPath = subPath.isBlank() ? cloneDir : cloneDir.resolve(subPath);
        if (!Files.isDirectory(skillPath)) {
            String available = availableSkillDirs(cloneDir);
            throw new IOException(
                    "Skill path not found: " + skillPath + "\n" +
                    "  The repo was cloned to: " + cloneDir + "\n" +
                    "  Available directories with SKILL.md: " + available + "\n" +
                    "  Tip: use -Dpi.skill.branch and -Dpi.skill.path to set these explicitly.");
        }

        cache.put(cacheKey, skillPath);
        return skillPath;
    }

    private String availableSkillDirs(Path cloneDir) {
        try (var stream = Files.walk(cloneDir, 2)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("SKILL.md")))
                    .map(p -> cloneDir.relativize(p).toString())
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .map(s -> "[" + s + "]")
                    .orElse("(no directories with SKILL.md found — check repo structure)");
        } catch (IOException e) {
            return "(could not list: " + e.getMessage() + ")";
        }
    }

    private boolean isUrl(String ref) {
        return ref.startsWith("https://") || ref.startsWith("http://") || ref.startsWith("git@");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
