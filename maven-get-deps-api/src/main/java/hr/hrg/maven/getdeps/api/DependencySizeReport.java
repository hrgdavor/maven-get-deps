package hr.hrg.maven.getdeps.api;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public final class DependencySizeReport {

    public static String formatMarkdownReport(ResolutionResult result) {
        Map<ArtifactDescriptor, File> artifactFiles = result.artifactFiles();
        Map<String, List<ArtifactDescriptor>> groups = new LinkedHashMap<>();

        for (ArtifactDescriptor dep : result.dependencies()) {
            String root = topLevelRoot(dep);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(dep);
        }

        Set<String> counted = new HashSet<>();
        long grandTotalBytes = 0;
        List<RootStats> rootStats = new ArrayList<>();

        for (Map.Entry<String, List<ArtifactDescriptor>> entry : groups.entrySet()) {
            String root = entry.getKey();
            List<ArtifactDescriptor> uniqueDeps = new ArrayList<>();
            long rootBytes = 0;
            for (ArtifactDescriptor dep : entry.getValue()) {
                String depKey = dep.toGAV();
                if (!counted.contains(depKey)) {
                    counted.add(depKey);
                    uniqueDeps.add(dep);
                    File file = artifactFiles.get(dep);
                    if (file != null && file.exists()) {
                        rootBytes += file.length();
                    }
                }
            }
            if (!uniqueDeps.isEmpty()) {
                rootStats.add(new RootStats(root, uniqueDeps, rootBytes));
                grandTotalBytes += rootBytes;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Dependency Size Report\n\n");
        sb.append("This report attributes disk size incrementally to each top-level dependency.\n\n");
        sb.append("| Size (KB) | Top-level dependency | New artifacts |\n");
        sb.append("|----------:|:---------------------|--------------:|\n");
        for (RootStats stats : rootStats) {
            sb.append("| ").append(stats.sizeKb()).append(" | ")
                    .append(stats.root()).append(" | ")
                    .append(stats.uniqueCount()).append(" |\n");
        }
        sb.append("\n> Total size: ").append(grandTotalBytes).append(" bytes\n\n");

        for (RootStats stats : rootStats) {
            sb.append("## ").append(stats.root()).append("\n\n");
            sb.append("Artifacts introduced by this dependency:\n\n");
            sb.append("| Size (KB) | Dependency | Path |\n");
            sb.append("|----------:|:-----------|:-----|\n");
            String rootPrefix = stats.root() + " -> ";
            for (ArtifactDescriptor dep : stats.deps()) {
                File file = artifactFiles.get(dep);
                long size = file != null && file.exists() ? file.length() : 0;
                String rawPath = dep.path() == null ? "" : dep.path();
                if (rawPath.equals(stats.root())) rawPath = "";
                else if (rawPath.startsWith(rootPrefix)) rawPath = rawPath.substring(rootPrefix.length());
                String path = rawPath.replace("|", "\\|");
                sb.append("| ").append(size / 1024).append(" | ")
                        .append(dep.toGAV()).append(" | ")
                        .append(path).append(" |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String topLevelRoot(ArtifactDescriptor dep) {
        String path = dep.path();
        if (path == null || path.isBlank()) {
            return dep.toGAV();
        }
        String[] parts = path.split(" -> ");
        return parts.length > 0 ? parts[0] : dep.toGAV();
    }

    private record RootStats(String root, List<ArtifactDescriptor> deps, long bytes) {
        int uniqueCount() {
            return deps.size();
        }
        long sizeKb() {
            return (bytes + 1023) / 1024;
        }
    }
}
