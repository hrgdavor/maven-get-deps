package hr.hrg.maven.getdeps.mimic;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;

/**
 * Flexible parser for Maven dependency:list output.
 */
public class DependencyListParser {

    /**
     * Parses a line from Maven output (like dependency:list).
     * Handles [INFO] prefix, extra spaces, and 3-6 colon-separated parts.
     * 
     * @param line Raw line from Maven output
     * @return ArtifactDescriptor or null if the line doesn't match a dependency pattern
     */
    public static ArtifactDescriptor parse(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;

        // Handle [INFO] prefix
        if (trimmed.startsWith("[INFO]")) {
            trimmed = trimmed.substring(6).trim();
        }

        // A valid Maven dependency string has at least 2 colons (g:a:v)
        // and usually doesn't contain "---" or spaces within the GAV parts.
        if (trimmed.contains("---")) return null;
        
        String[] parts = trimmed.split(":");
        if (parts.length < 3) return null;

        // Basic heuristic: GID, AID and Version shouldn't have spaces
        // Trim each part and remove trailing fluff from the last part (e.g. "-- module ...")
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (i == parts.length - 1 && parts[i].contains(" ")) {
                parts[i] = parts[i].split(" ")[0];
            }
        }

        // Lenient space check: allow one space if it contains a dot (common in GID with typos/formatting)
        // but exclude if it looks like a descriptive line (e.g. "Finished at")
        if (parts[0].contains(" ") && !parts[0].contains(".")) return null;
        if (parts[1].contains(" ") || parts[2].contains(" ")) return null;

        String groupId = parts[0];
        String artifactId = parts[1];
        String type = "jar";
        String version = null;
        String classifier = null;
        String scope = "compile";

        // Maven dependency:list format: groupId:artifactId:type:[classifier:]version:scope
        if (parts.length == 3) {
            // g:a:v
            version = parts[2];
        } else if (parts.length == 4) {
            // g:a:v:c (Legacy/Short format)
            version = parts[2];
            classifier = parts[3];
        } else if (parts.length == 5) {
            // g:a:t:v:s (Standard dependency:list)
            type = parts[2];
            version = parts[3];
            scope = parts[4];
        } else if (parts.length >= 6) {
            // g:a:t:c:v:s (Standard dependency:list with classifier)
            type = parts[2];
            classifier = parts[3];
            version = parts[4];
            scope = parts[5];
        }

        if (version == null || version.isEmpty()) return null;

        return new ArtifactDescriptor(groupId, artifactId, version, scope, classifier, type);
    }
}
