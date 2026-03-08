package hr.hrg.maven.getdeps;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DependencyResolverService {

    public static class ResolutionResult {
        public List<String> relativePaths;
        public long totalSize;

        public ResolutionResult(List<String> relativePaths, long totalSize) {
            this.relativePaths = relativePaths;
            this.totalSize = totalSize;
        }
    }

    public static class ReportEntry {
        public String dependency;
        public long sizeBytes;

        public ReportEntry(String dependency, long sizeBytes) {
            this.dependency = dependency;
            this.sizeBytes = sizeBytes;
        }
    }

    public static class ReportResult {
        public List<ReportEntry> entries;
        public long totalSize;

        public ReportResult(List<ReportEntry> entries, long totalSize) {
            this.entries = entries;
            this.totalSize = totalSize;
        }

        public String formatMarkdownTable() {
            String col1Head = "Size (KB)";
            String col2Head = "Dependency";

            int maxCol1 = col1Head.length();
            int maxCol2 = col2Head.length();

            for (ReportEntry entry : entries) {
                maxCol1 = Math.max(maxCol1, String.valueOf(entry.sizeBytes / 1024).length());
                maxCol2 = Math.max(maxCol2, entry.dependency.length());
            }

            StringBuilder sb = new StringBuilder();
            // Header
            sb.append(String.format("| %" + maxCol1 + "s | %-" + maxCol2 + "s |%n", col1Head, col2Head));
            // Separator
            sb.append("|");
            for (int i = 0; i < maxCol1 + 1; i++)
                sb.append("-");
            sb.append(":|:");
            for (int i = 0; i < maxCol2 + 1; i++)
                sb.append("-");
            sb.append("|%n".formatted());

            // Rows
            for (ReportEntry entry : entries) {
                sb.append(String.format("| %" + maxCol1 + "d | %-" + maxCol2 + "s |%n", entry.sizeBytes / 1024,
                        entry.dependency));
            }

            sb.append(String.format("> Total size: %d bytes (%.2f MB)%n", totalSize, totalSize / (1024.0 * 1024.0)));

            return sb.toString();
        }
    }

    public static ResolutionResult resolve(
            RepositorySystem system,
            RepositorySystemSession session,
            List<RemoteRepository> repos,
            List<org.apache.maven.model.Dependency> dependencies,
            java.util.function.BiFunction<String, org.apache.maven.model.Model, String> propertyResolver,
            org.apache.maven.model.Model model,
            Set<String> scopes) throws Exception {

        CollectRequest collectRequest = new CollectRequest();
        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope();
            if (scope == null)
                scope = JavaScopes.COMPILE;

            if (scopes.contains(scope)) {
                String version = propertyResolver.apply(dep.getVersion(), model);
                String groupId = propertyResolver.apply(dep.getGroupId(), model);
                String artifactId = propertyResolver.apply(dep.getArtifactId(), model);

                collectRequest.addDependency(new Dependency(
                        new DefaultArtifact(groupId, artifactId, dep.getClassifier(), dep.getType(), version),
                        scope));
            }
        }
        collectRequest.setRepositories(repos);

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest,
                DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0])));

        DependencyResult result = system.resolveDependencies(session, dependencyRequest);

        List<String> relativePaths = new ArrayList<>();
        long totalSize = 0;

        for (ArtifactResult artifactResult : result.getArtifactResults()) {
            Artifact artifact = artifactResult.getArtifact();
            File file = artifact.getFile();
            if (file != null) {
                totalSize += file.length();

                // Use LocalRepositoryManager to get the relative path within the repository
                String relativePath = session.getLocalRepositoryManager().getPathForLocalArtifact(artifact);
                relativePaths.add(relativePath.replace('\\', '/'));

                // Also ensure POM is resolved/present
                Artifact pomArtifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getClassifier(), "pom", artifact.getVersion());
                ArtifactResult pomResult = system.resolveArtifact(session,
                        new org.eclipse.aether.resolution.ArtifactRequest(pomArtifact, repos, null));
                if (pomResult.getArtifact().getFile() != null) {
                    totalSize += pomResult.getArtifact().getFile().length();
                }
            }
        }

        return new ResolutionResult(relativePaths, totalSize);
    }

    public static ReportResult resolveReport(
            RepositorySystem system,
            RepositorySystemSession session,
            List<RemoteRepository> repos,
            List<org.apache.maven.model.Dependency> dependencies,
            java.util.function.BiFunction<String, org.apache.maven.model.Model, String> propertyResolver,
            org.apache.maven.model.Model model,
            Set<String> scopes) throws Exception {

        List<ReportEntry> entries = new ArrayList<>();
        java.util.Set<String> seenArtifacts = new java.util.HashSet<>();
        long totalSize = 0;

        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope();
            if (scope == null)
                scope = JavaScopes.COMPILE;

            if (scopes.contains(scope)) {
                String version = propertyResolver.apply(dep.getVersion(), model);
                String groupId = propertyResolver.apply(dep.getGroupId(), model);
                String artifactId = propertyResolver.apply(dep.getArtifactId(), model);
                String depId = groupId + ":" + artifactId + ":" + version;

                CollectRequest collectRequest = new CollectRequest();
                collectRequest.addDependency(new Dependency(
                        new DefaultArtifact(groupId, artifactId, dep.getClassifier(), dep.getType(), version),
                        scope));
                collectRequest.setRepositories(repos);

                DependencyRequest dependencyRequest = new DependencyRequest(collectRequest,
                        DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0])));

                DependencyResult result = system.resolveDependencies(session, dependencyRequest);
                long currentDepSize = 0;

                for (ArtifactResult artifactResult : result.getArtifactResults()) {
                    Artifact artifact = artifactResult.getArtifact();
                    String artifactIdStr = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                            + artifact.getVersion() + ":" + artifact.getClassifier() + ":" + artifact.getExtension();

                    if (!seenArtifacts.contains(artifactIdStr)) {
                        File file = artifact.getFile();
                        if (file != null) {
                            currentDepSize += file.length();
                        }

                        // Also ensure POM is resolved/present and count its size
                        Artifact pomArtifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(),
                                artifact.getClassifier(), "pom", artifact.getVersion());
                        ArtifactResult pomResult = system.resolveArtifact(session,
                                new org.eclipse.aether.resolution.ArtifactRequest(pomArtifact, repos, null));
                        if (pomResult.getArtifact().getFile() != null) {
                            currentDepSize += pomResult.getArtifact().getFile().length();
                        }

                        seenArtifacts.add(artifactIdStr);
                    }
                }

                entries.add(new ReportEntry(depId, currentDepSize));
                totalSize += currentDepSize;
            }
        }

        return new ReportResult(entries, totalSize);
    }

    public java.util.LinkedHashMap<String, List<String>> resolvePerDepForArtifact(String artifactCoords,
            String scopesStr, String cachePath) throws Exception {
        RepositorySystem system = Bootstrapper.newRepositorySystem();
        String defaultM2 = System.getProperty("user.home") + "/.m2/repository";
        String repoPath = (cachePath != null) ? cachePath : defaultM2;
        RepositorySystemSession session = Bootstrapper.newRepositorySystemSession(system, repoPath);
        List<org.eclipse.aether.repository.RemoteRepository> repos = Bootstrapper.newRepositories(system, session);

        DependencyFormatInfo info = FormatConverter.parse(artifactCoords);
        if (info == null || info.isLocal()) {
            throw new IllegalArgumentException("Invalid artifact coordinates: " + artifactCoords);
        }

        org.apache.maven.model.Model model = new org.apache.maven.model.Model();
        model.setGroupId("hr.hrg.maven.getdeps");
        model.setArtifactId("adhoc");
        model.setVersion("1.0.0");
        org.apache.maven.model.Dependency dep = new org.apache.maven.model.Dependency();
        dep.setGroupId(info.groupId());
        dep.setArtifactId(info.artifactId());
        dep.setVersion(info.version());
        dep.setClassifier(info.classifier());
        dep.setType(info.extension());
        model.addDependency(dep);

        Set<String> scopes = java.util.stream.Stream.of(scopesStr.split(","))
                .map(String::trim)
                .collect(java.util.stream.Collectors.toSet());

        return resolvePerDep(system, session, repos, model.getDependencies(),
                (v, m) -> Bootstrapper.resolveProperty(v, m), model, scopes);
    }

    /**
     * Resolves each direct dependency individually and returns, for each, the
     * ordered list of all artifacts in its transitive closure (including itself)
     * as {@code "groupId:artifactId:version"} strings.
     *
     * <p>
     * This data structure is used by {@code CveReportService} to map each
     * direct dependency to its full dependency tree for CVE reporting.
     * </p>
     */
    public java.util.LinkedHashMap<String, List<String>> resolvePerDep(File pomFile, String scopesStr, String cachePath)
            throws Exception {
        RepositorySystem system = Bootstrapper.newRepositorySystem();
        String defaultM2 = System.getProperty("user.home") + "/.m2/repository";
        String repoPath = (cachePath != null) ? cachePath : defaultM2;
        RepositorySystemSession session = Bootstrapper.newRepositorySystemSession(system, repoPath);
        List<org.eclipse.aether.repository.RemoteRepository> repos = Bootstrapper.newRepositories(system, session);
        org.apache.maven.model.Model model = Bootstrapper.resolveModel(pomFile, system, session, repos);

        Set<String> scopes = java.util.stream.Stream.of(scopesStr.split(","))
                .map(String::trim)
                .collect(java.util.stream.Collectors.toSet());

        return resolvePerDep(system, session, repos, model.getDependencies(),
                (v, m) -> Bootstrapper.resolveProperty(v, m), model, scopes);
    }

    public static java.util.LinkedHashMap<String, List<String>> resolvePerDep(
            RepositorySystem system,
            RepositorySystemSession session,
            List<RemoteRepository> repos,
            List<org.apache.maven.model.Dependency> dependencies,
            java.util.function.BiFunction<String, org.apache.maven.model.Model, String> propertyResolver,
            org.apache.maven.model.Model model,
            Set<String> scopes) throws Exception {

        java.util.LinkedHashMap<String, List<String>> result = new java.util.LinkedHashMap<>();

        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope();
            if (scope == null)
                scope = JavaScopes.COMPILE;
            if (!scopes.contains(scope))
                continue;

            String version = propertyResolver.apply(dep.getVersion(), model);
            String groupId = propertyResolver.apply(dep.getGroupId(), model);
            String artifactId = propertyResolver.apply(dep.getArtifactId(), model);
            String directKey = groupId + ":" + artifactId + ":" + version;

            CollectRequest collectRequest = new CollectRequest();
            collectRequest.addDependency(new Dependency(
                    new DefaultArtifact(groupId, artifactId, dep.getClassifier(), dep.getType(), version),
                    scope));
            collectRequest.setRepositories(repos);

            DependencyRequest dependencyRequest = new DependencyRequest(collectRequest,
                    DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0])));

            DependencyResult depResult = system.resolveDependencies(session, dependencyRequest);

            List<String> coords = new ArrayList<>();
            for (ArtifactResult artifactResult : depResult.getArtifactResults()) {
                Artifact artifact = artifactResult.getArtifact();
                coords.add(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion());
            }
            result.put(directKey, coords);
        }
        return result;
    }

    /**
     * Reads a list of G:A:V coordinates from a file (one per line) and
     * returns a map where each is its own "direct" dependency.
     */
    public static java.util.LinkedHashMap<String, List<String>> readPerDepFromFile(String path)
            throws java.io.IOException {
        java.util.LinkedHashMap<String, List<String>> result = new java.util.LinkedHashMap<>();
        List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(path));
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            // Handle both flat GAV lists and potential G:A:V|T1,T2 format
            String[] parts = line.split("\\|");
            String direct = parts[0].trim();
            List<String> closure = new java.util.ArrayList<>();
            closure.add(direct);
            if (parts.length > 1) {
                for (String t : parts[1].split(",")) {
                    if (!t.trim().isEmpty())
                        closure.add(t.trim());
                }
            }
            result.put(direct, closure);
        }
        return result;
    }
}
