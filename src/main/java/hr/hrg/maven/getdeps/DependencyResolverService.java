package hr.hrg.maven.getdeps;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
            Set<String> scopes,
            Set<String> excludeSet,
            String projectGroupId,
            Set<String> reactorGAs,
            boolean excludeSiblings) throws ArtifactResolutionException, DependencyCollectionException {

        CollectRequest collectRequest = new CollectRequest();
        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope();
            if (scope == null)
                scope = JavaScopes.COMPILE;

            String version = propertyResolver.apply(dep.getVersion(), model);
            String groupId = propertyResolver.apply(dep.getGroupId(), model);
            String artifactId = propertyResolver.apply(dep.getArtifactId(), model);

            String classifier = dep.getClassifier();
            String extension = dep.getType();
            if ("test-jar".equals(extension)) {
                extension = "jar";
                classifier = "tests";
            }

            collectRequest.addDependency(new Dependency(
                    new DefaultArtifact(groupId, artifactId, classifier, extension, version),
                    scope));
        }

        if (model.getDependencyManagement() != null) {
            List<Dependency> managed = new ArrayList<>();
            for (org.apache.maven.model.Dependency dep : model.getDependencyManagement().getDependencies()) {
                String version = propertyResolver.apply(dep.getVersion(), model);
                String groupId = propertyResolver.apply(dep.getGroupId(), model);
                String artifactId = propertyResolver.apply(dep.getArtifactId(), model);
                
                String classifier = dep.getClassifier();
                String extension = dep.getType();
                if ("test-jar".equals(extension)) {
                    extension = "jar";
                    classifier = "tests";
                }
                
                managed.add(new Dependency(
                        new DefaultArtifact(groupId, artifactId, classifier, extension, version),
                        dep.getScope(), dep.isOptional()));
            }
            collectRequest.setManagedDependencies(managed);
        }
        collectRequest.setRepositories(repos);

        org.eclipse.aether.graph.DependencyFilter scopeFilter = DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0]));
        org.eclipse.aether.collection.CollectResult collectResult = system.collectDependencies(session, collectRequest);

        if (!collectResult.getExceptions().isEmpty()) {
            for (Exception e : collectResult.getExceptions()) {
                System.err.println("Collection error: " + e.getMessage());
            }
        }

        List<DependencyNode> nodes = new java.util.ArrayList<>();
        collectNodes(collectResult.getRoot(), new java.util.ArrayList<>(), scopeFilter, nodes, new java.util.HashSet<>());

        List<ArtifactRequest> requests = new ArrayList<>();
        for (DependencyNode node : nodes) {
            Artifact artifact = node.getArtifact();
            if (artifact == null) continue;
            requests.add(new ArtifactRequest(artifact, repos, null));
            // Also add POM to requests to resolve in batch
            requests.add(new ArtifactRequest(new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), "pom", artifact.getVersion()), repos, null));
        }

        List<ArtifactResult> allResults;
        try {
            allResults = system.resolveArtifacts(session, requests);
        } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
            // Handle partially resolved results if possible, but for simplicity we'll just catch and filter later
            allResults = e.getResults();
        }

        List<String> relativePaths = new ArrayList<>();
        long totalSize = 0;

        // Group into JAR and POM for processing
        Map<String, Artifact> jars = new HashMap<>();
        Map<String, Artifact> poms = new HashMap<>();

        for(ArtifactResult ar : allResults) {
            Artifact a = ar.getArtifact();
            if(a == null) continue;
            String ga = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion();
            if("pom".equals(a.getExtension())) {
                poms.put(ga, a);
            } else {
                jars.put(ga, a);
            }
        }

        for (DependencyNode node : nodes) {
            Artifact artifact = node.getArtifact();
            if (artifact == null) continue;
            
            String ga = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            Artifact resolvedJar = jars.get(ga);
            File jarFile = resolvedJar != null ? resolvedJar.getFile() : null;

            if (jarFile != null) {
                totalSize += jarFile.length();
                String relativePath = session.getLocalRepositoryManager().getPathForLocalArtifact(resolvedJar);
                String normalizedPath = relativePath.replace('\\', '/');

                if (isExcluded(artifact.getGroupId(), artifact.getArtifactId(), normalizedPath, excludeSet)) {
                    totalSize -= jarFile.length();
                    continue;
                }
                if (excludeSiblings && isSibling(artifact.getGroupId(), artifact.getArtifactId(), projectGroupId, reactorGAs)) {
                    totalSize -= jarFile.length();
                    continue;
                }
                relativePaths.add(normalizedPath);

                Artifact resolvedPom = poms.get(ga);
                if (resolvedPom != null && resolvedPom.getFile() != null) {
                    totalSize += resolvedPom.getFile().length();
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
            Set<String> scopes,
            Set<String> excludeSet,
            String projectGroupId,
            java.util.Set<String> reactorGAs,
            boolean excludeSiblings) throws Exception {

        List<ReportEntry> entries = new ArrayList<>();
        java.util.Set<String> seenArtifacts = new java.util.HashSet<>();
        long totalSize = 0;

        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope();
            if (scope == null)
                scope = JavaScopes.COMPILE;

            String version = propertyResolver.apply(dep.getVersion(), model);
            String groupId = propertyResolver.apply(dep.getGroupId(), model);
            String artifactId = propertyResolver.apply(dep.getArtifactId(), model);
            String depId = groupId + ":" + artifactId + ":" + version;

            CollectRequest collectRequest = new CollectRequest();
            collectRequest.addDependency(new Dependency(
                    new DefaultArtifact(groupId, artifactId, dep.getClassifier(), dep.getType(), version),
                    scope));
            collectRequest.setRepositories(repos);

            org.eclipse.aether.graph.DependencyFilter scopeFilter = DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0]));
            org.eclipse.aether.collection.CollectResult collectResult = system.collectDependencies(session, collectRequest);
            if (!collectResult.getExceptions().isEmpty()) {
                for (Exception e : collectResult.getExceptions()) {
                    System.err.println("Collection error: " + e.getMessage());
                }
            }

            List<DependencyNode> nodes = new java.util.ArrayList<>();
            collectNodes(collectResult.getRoot(), new java.util.ArrayList<>(), scopeFilter, nodes, new java.util.HashSet<>());

            List<ArtifactResult> artifactResults = new ArrayList<>();
            for (DependencyNode node : nodes) {
                Artifact artifact = node.getArtifact();
                if (artifact == null) continue;
                try {
                    artifactResults.add(system.resolveArtifact(session, new ArtifactRequest(artifact, repos, null)));
                } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
                    if (excludeSiblings && isSibling(artifact.getGroupId(), artifact.getArtifactId(), projectGroupId, reactorGAs)) {
                        artifactResults.add(new ArtifactResult(new ArtifactRequest(artifact, repos, null)).setArtifact(artifact));
                    } else {
                        throw e;
                    }
                }
            }
            long currentDepSize = 0;

            for (ArtifactResult artifactResult : artifactResults) {
                Artifact artifact = artifactResult.getArtifact();
                String relativePath = session.getLocalRepositoryManager().getPathForLocalArtifact(artifact);
                if (isExcluded(artifact.getGroupId(), artifact.getArtifactId(), relativePath, excludeSet)) {
                    continue;
                }

                if (excludeSiblings && isSibling(artifact.getGroupId(), artifact.getArtifactId(), projectGroupId, reactorGAs)) {
                    continue;
                }

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
                (v, m) -> Bootstrapper.resolveProperty(v, m), model, scopes, new java.util.HashSet<>(), null, new java.util.HashSet<>(), false);
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
                (v, m) -> Bootstrapper.resolveProperty(v, m), model, scopes, new java.util.HashSet<>(), null, new java.util.HashSet<>(), false);
    }

    public static java.util.LinkedHashMap<String, List<String>> resolvePerDep(
            RepositorySystem system,
            RepositorySystemSession session,
            List<RemoteRepository> repos,
            List<org.apache.maven.model.Dependency> dependencies,
            java.util.function.BiFunction<String, org.apache.maven.model.Model, String> propertyResolver,
            org.apache.maven.model.Model model,
            Set<String> scopes,
            Set<String> excludeSet,
            String projectGroupId,
            java.util.Set<String> reactorGAs,
            boolean includeSiblings) throws Exception {

        java.util.LinkedHashMap<String, List<String>> result = new java.util.LinkedHashMap<>();

        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope();
            if (scope == null)
                scope = JavaScopes.COMPILE;

            String version = propertyResolver.apply(dep.getVersion(), model);
            String groupId = propertyResolver.apply(dep.getGroupId(), model);
            String artifactId = propertyResolver.apply(dep.getArtifactId(), model);
            String directKey = groupId + ":" + artifactId + ":" + version;

            CollectRequest collectRequest = new CollectRequest();
            collectRequest.addDependency(new Dependency(
                    new DefaultArtifact(groupId, artifactId, dep.getClassifier(), dep.getType(), version),
                    scope));
            collectRequest.setRepositories(repos);

            org.eclipse.aether.graph.DependencyFilter scopeFilter = DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0]));
            org.eclipse.aether.collection.CollectResult collectResult = system.collectDependencies(session, collectRequest);
            if (!collectResult.getExceptions().isEmpty()) {
                for (Exception e : collectResult.getExceptions()) {
                    System.err.println("Collection error: " + e.getMessage());
                }
            }

            List<DependencyNode> nodes = new java.util.ArrayList<>();
            collectNodes(collectResult.getRoot(), new java.util.ArrayList<>(), scopeFilter, nodes, new java.util.HashSet<>());

            List<ArtifactResult> artifactResults = new ArrayList<>();
            for (DependencyNode node : nodes) {
                Artifact artifact = node.getArtifact();
                if (artifact == null) continue;
                try {
                    artifactResults.add(system.resolveArtifact(session, new ArtifactRequest(artifact, repos, null)));
                } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
                    if (includeSiblings || !isSibling(artifact.getGroupId(), artifact.getArtifactId(), projectGroupId, reactorGAs)) {
                        throw e;
                    }
                    artifactResults.add(new ArtifactResult(new ArtifactRequest(artifact, repos, null)).setArtifact(artifact));
                }
            }

            List<String> coords = new ArrayList<>();
            for (ArtifactResult artifactResult : artifactResults) {
                Artifact artifact = artifactResult.getArtifact();
                String relativePath = session.getLocalRepositoryManager().getPathForLocalArtifact(artifact);
                if (isExcluded(artifact.getGroupId(), artifact.getArtifactId(), relativePath, excludeSet)) {
                    continue;
                }
                if (!includeSiblings && isSibling(artifact.getGroupId(), artifact.getArtifactId(), projectGroupId, reactorGAs)) {
                    continue;
                }
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

    public static Set<String> normalizeExcludes(String excludes) {
        Set<String> result = new HashSet<>();
        if (excludes == null || excludes.isEmpty())
            return result;
        for (String s : excludes.split(",")) {
            String trimmed = s.trim().replace('\\', '/');
            if (trimmed.isEmpty())
                continue;
            result.add(trimmed);

            // If it's a path, try to extract G:A variant
            if (trimmed.contains("/") && trimmed.endsWith(".jar")) {
                String ga = extractGAFromPath(trimmed);
                if (ga != null)
                    result.add(ga);
            }
        }
        return result;
    }

    private static String extractGAFromPath(String path) {
        // Expected: group/id/artifact/id/version/artifact-id-version.jar
        String[] parts = path.split("/");
        if (parts.length < 4)
            return null;
        String artifactId = parts[parts.length - 3];
        StringBuilder groupId = new StringBuilder();
        for (int i = 0; i < parts.length - 3; i++) {
            if (i > 0)
                groupId.append(".");
            groupId.append(parts[i]);
        }
        return groupId.toString() + ":" + artifactId;
    }

    public static boolean isExcluded(String groupId, String artifactId, String relativePath, Set<String> excludes) {
        if (excludes == null || excludes.isEmpty())
            return false;
        if (excludes.contains(groupId + ":" + artifactId))
            return true;
        String normalizedPath = relativePath.replace('\\', '/');
        if (excludes.contains(normalizedPath))
            return true;
        // Optionally check if path starts with or ends with any excluded prefix/suffix
        for (String ex : excludes) {
            if (normalizedPath.startsWith(ex) || normalizedPath.endsWith(ex))
                return true;
        }
        return false;
    }

    public static boolean isSibling(String groupId, String artifactId, String projectGroupId, Set<String> reactorGAs) {
        if (reactorGAs != null && reactorGAs.contains(groupId + ":" + artifactId))
            return true;
        if (projectGroupId == null || groupId == null)
            return false;
        return groupId.equals(projectGroupId);
    }

    private static void collectNodes(DependencyNode node,
            List<DependencyNode> parents,
            org.eclipse.aether.graph.DependencyFilter filter,
            List<DependencyNode> nodes,
            java.util.Set<String> seen) {
        
        if (filter == null || node.getDependency() == null || filter.accept(node, parents)) {
            Artifact a = node.getArtifact();
            if (a != null) {
                String key = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + ":" + a.getClassifier() + ":"
                        + a.getExtension();
                if (seen.add(key)) {
                    nodes.add(node);
                }
            }
        }

        List<DependencyNode> nextParents = new ArrayList<>(parents);
        nextParents.add(node);
        for (DependencyNode child : node.getChildren()) {
            collectNodes(child, nextParents, filter, nodes, seen);
        }
    }
}
