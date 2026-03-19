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
import org.eclipse.aether.repository.WorkspaceReader;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
            boolean excludeSiblings,
            boolean noCache) throws ArtifactResolutionException, DependencyCollectionException {

        List<List<Artifact>> results = dependencies.parallelStream().map(modelDep -> {
            try {
                String scope = modelDep.getScope();
                if (scope == null) scope = JavaScopes.COMPILE;
                
                String version = propertyResolver.apply(modelDep.getVersion(), model);
                String groupId = propertyResolver.apply(modelDep.getGroupId(), model);
                String artifactId = propertyResolver.apply(modelDep.getArtifactId(), model);
                
                String classifier = modelDep.getClassifier();
                String extension = modelDep.getType();
                if ("test-jar".equals(extension)) {
                    extension = "jar";
                    classifier = "tests";
                }
                
                Artifact rootArtifact = new DefaultArtifact(groupId, artifactId, classifier, extension, version);
                
                File cacheFile = getCacheFile(rootArtifact, session, scopes);
                List<Artifact> closure = null;
                
                boolean isSibling = isSibling(groupId, artifactId, projectGroupId, reactorGAs);
                String currentPomHash = null;
                if (!noCache && cacheFile.exists()) {
                    if (isSibling) {
                        // For siblings, check if the POM hash has changed
                        Artifact pomArtifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);
                        File pomFile = session.getWorkspaceReader() != null ? session.getWorkspaceReader().findArtifact(pomArtifact) : null;
                        if (pomFile != null && pomFile.exists()) {
                            currentPomHash = calculateHash(pomFile);
                            String storedHash = getStoredHash(cacheFile);
                            if (currentPomHash != null && currentPomHash.equals(storedHash)) {
                                closure = loadTransitiveCache(cacheFile);
                            }
                        }
                    } else {
                        closure = loadTransitiveCache(cacheFile);
                    }
                }
                
                if (closure == null) {
                    closure = resolveTransitiveIsolated(system, session, repos, rootArtifact, scope, scopes);
                    if (!noCache) {
                        saveTransitiveCache(cacheFile, rootArtifact, closure, scopes, currentPomHash);
                    }
                }
                return closure;
            } catch (Exception e) {
                throw new RuntimeException("Error resolving transitive dependencies for " + modelDep, e);
            }
        }).collect(java.util.stream.Collectors.toList());

        Set<Artifact> allTransitiveArtifacts = new LinkedHashSet<>();
        for (List<Artifact> closure : results) {
            allTransitiveArtifacts.addAll(closure);
        }

        return resolveArtifactsAndPaths(system, session, repos, allTransitiveArtifacts, excludeSet, projectGroupId, reactorGAs, excludeSiblings);
    }

    private static List<Artifact> resolveTransitiveIsolated(RepositorySystem system, RepositorySystemSession session,
            List<RemoteRepository> repos, Artifact rootArtifact, String scope, Set<String> scopes) throws DependencyCollectionException {
        
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.addDependency(new Dependency(rootArtifact, scope));
        collectRequest.setRepositories(repos);

        org.eclipse.aether.graph.DependencyFilter scopeFilter = DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0]));
        org.eclipse.aether.collection.CollectResult collectResult = system.collectDependencies(session, collectRequest);

        List<DependencyNode> nodes = new ArrayList<>();
        collectNodes(collectResult.getRoot(), new ArrayList<>(), scopeFilter, nodes, new HashSet<>());
        
        List<Artifact> result = new ArrayList<>();
        for (DependencyNode node : nodes) {
            if (node.getArtifact() != null) result.add(node.getArtifact());
        }
        return result;
    }

    private static ResolutionResult resolveArtifactsAndPaths(
            RepositorySystem system,
            RepositorySystemSession session,
            List<RemoteRepository> repos,
            Set<Artifact> artifacts,
            Set<String> excludeSet,
            String projectGroupId,
            Set<String> reactorGAs,
            boolean excludeSiblings) throws ArtifactResolutionException {

        List<ArtifactRequest> requests = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            requests.add(new ArtifactRequest(artifact, repos, null));
            requests.add(new ArtifactRequest(new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), "pom", artifact.getVersion()), repos, null));
        }

        List<ArtifactResult> allResults = new ArrayList<>();
        if (session.isOffline()) {
            WorkspaceReader workspaceReader = session.getWorkspaceReader();
            for (Artifact artifact : artifacts) {
                ArtifactRequest jarReq = new ArtifactRequest(artifact, repos, null);
                ArtifactResult jarRes = new ArtifactResult(jarReq);
                File jarFile = null;
                if (workspaceReader != null) jarFile = workspaceReader.findArtifact(artifact);
                if (jarFile == null) {
                    String path = session.getLocalRepositoryManager().getPathForLocalArtifact(artifact);
                    jarFile = new File(session.getLocalRepository().getBasedir(), path);
                }
                if (jarFile != null && jarFile.exists()) jarRes.setArtifact(artifact.setFile(jarFile));
                allResults.add(jarRes);

                Artifact pomArtifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), "pom", artifact.getVersion());
                ArtifactResult pomRes = new ArtifactResult(new ArtifactRequest(pomArtifact, repos, null));
                File pomFile = null;
                if (workspaceReader != null) pomFile = workspaceReader.findArtifact(pomArtifact);
                if (pomFile == null) {
                    String path = session.getLocalRepositoryManager().getPathForLocalArtifact(pomArtifact);
                    pomFile = new File(session.getLocalRepository().getBasedir(), path);
                }
                if (pomFile != null && pomFile.exists()) pomRes.setArtifact(pomArtifact.setFile(pomFile));
                allResults.add(pomRes);
            }
        } else {
            try {
                allResults = system.resolveArtifacts(session, requests);
            } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
                allResults = e.getResults();
            }
        }

        Set<String> relativePaths = new LinkedHashSet<>();
        long totalSize = 0;
        Map<String, Artifact> jars = new HashMap<>();
        Map<String, Artifact> poms = new HashMap<>();

        for (ArtifactResult ar : allResults) {
            Artifact a = ar.getArtifact();
            if (a == null) continue;
            String ga = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + ":" + a.getClassifier();
            if ("pom".equals(a.getExtension())) poms.put(ga, a);
            else jars.put(ga, a);
        }

        for (Artifact artifact : artifacts) {
            String ga = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion() + ":" + artifact.getClassifier();
            Artifact resolvedJar = jars.get(ga);
            File jarFile = resolvedJar != null ? resolvedJar.getFile() : null;

            if (jarFile != null) {
                String relativePath = session.getLocalRepositoryManager().getPathForLocalArtifact(resolvedJar);
                String normalizedPath = relativePath.replace('\\', '/');
                if (isExcluded(artifact.getGroupId(), artifact.getArtifactId(), normalizedPath, excludeSet)) continue;
                if (excludeSiblings && isSibling(artifact.getGroupId(), artifact.getArtifactId(), projectGroupId, reactorGAs)) continue;
                
                if (relativePaths.add(normalizedPath)) {
                    totalSize += jarFile.length();
                    Artifact resolvedPom = poms.get(ga);
                    if (resolvedPom != null && resolvedPom.getFile() != null) totalSize += resolvedPom.getFile().length();
                }
            }
        }
        return new ResolutionResult(new ArrayList<>(relativePaths), totalSize);
    }

    private static File getCacheFile(Artifact artifact, RepositorySystemSession session, Set<String> scopes) {
        String path = session.getLocalRepositoryManager().getPathForLocalArtifact(artifact);
        File jarFile = new File(session.getLocalRepository().getBasedir(), path);
        File dir = jarFile.getParentFile();
        List<String> sortedScopes = new ArrayList<>(scopes);
        Collections.sort(sortedScopes);
        String scopeKey = String.join(",", sortedScopes);
        if (scopeKey.isEmpty()) scopeKey = "all";
        return new File(dir, artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom." + scopeKey + ".get-deps.cache");
    }

    private static void saveTransitiveCache(File file, Artifact root, List<Artifact> closure, Set<String> scopes, String pomHash) {
        try (PrintWriter writer = new PrintWriter(file)) {
            writer.println("# root=" + root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion() + ":" + root.getClassifier() + ":" + root.getExtension());
            List<String> sortedScopes = new ArrayList<>(scopes);
            Collections.sort(sortedScopes);
            writer.println("# scopes=" + String.join(",", sortedScopes));
            if (pomHash != null) {
                writer.println("# pomHash=" + pomHash);
            }
            for (Artifact a : closure) {
                writer.println(a.getGroupId() + ":" + a.getArtifactId() + ":" + (a.getClassifier() == null ? "" : a.getClassifier()) + ":" + a.getExtension() + ":" + a.getVersion());
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not save cache: " + e.getMessage());
        }
    }

    private static List<Artifact> loadTransitiveCache(File file) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
            List<Artifact> artifacts = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("#")) continue;
                String[] p = line.split(":");
                if (p.length == 5) {
                    artifacts.add(new DefaultArtifact(p[0], p[1], p[2], p[3], p[4]));
                }
            }
            return artifacts;
        } catch (Exception e) {
            return null;
        }
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

        CollectRequest collectRequest = new CollectRequest();
        for (org.apache.maven.model.Dependency dep : dependencies) {
            String scope = dep.getScope() != null ? dep.getScope() : JavaScopes.COMPILE;
            String version = propertyResolver.apply(dep.getVersion(), model);
            String groupId = propertyResolver.apply(dep.getGroupId(), model);
            String artifactId = propertyResolver.apply(dep.getArtifactId(), model);
            collectRequest.addDependency(new Dependency(new DefaultArtifact(groupId, artifactId, dep.getClassifier(), dep.getType(), version), scope));
        }
        collectRequest.setRepositories(repos);

        org.eclipse.aether.collection.CollectResult collectResult = system.collectDependencies(session, collectRequest);
        
        List<DependencyNode> directNodes = collectResult.getRoot().getChildren();
        org.eclipse.aether.graph.DependencyFilter scopeFilter = DependencyFilterUtils.classpathFilter(scopes.toArray(new String[0]));

        List<ReportEntry> entries = new ArrayList<>();
        java.util.Set<String> seenArtifacts = new java.util.HashSet<>();
        long totalSize = 0;

        for (DependencyNode directNode : directNodes) {
            Dependency dep = directNode.getDependency();
            if (dep == null) continue;
            Artifact a = dep.getArtifact();
            String depId = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion();

            List<DependencyNode> subNodes = new ArrayList<>();
            collectNodes(directNode, new ArrayList<>(), scopeFilter, subNodes, new HashSet<>());

            List<ArtifactRequest> requests = new ArrayList<>();
            for (DependencyNode node : subNodes) {
                Artifact artifact = node.getArtifact();
                if (artifact == null) continue;
                String artifactIdStr = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                        + artifact.getVersion() + ":" + artifact.getClassifier() + ":" + artifact.getExtension();
                if (!seenArtifacts.contains(artifactIdStr)) {
                    requests.add(new ArtifactRequest(artifact, repos, null));
                    requests.add(new ArtifactRequest(new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), "pom", artifact.getVersion()), repos, null));
                }
            }

            List<ArtifactResult> results;
            try {
                results = system.resolveArtifacts(session, requests);
            } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
                results = e.getResults();
            }

            long currentDepSize = 0;
            for (ArtifactResult ar : results) {
                Artifact resolved = ar.getArtifact();
                if (resolved == null || resolved.getFile() == null) continue;

                if (isExcluded(resolved.getGroupId(), resolved.getArtifactId(), 
                        session.getLocalRepositoryManager().getPathForLocalArtifact(resolved), excludeSet)) {
                    continue;
                }
                if (excludeSiblings && isSibling(resolved.getGroupId(), resolved.getArtifactId(), projectGroupId, reactorGAs)) {
                    continue;
                }

                String artifactIdStr = resolved.getGroupId() + ":" + resolved.getArtifactId() + ":"
                        + resolved.getVersion() + ":" + resolved.getClassifier() + ":" + resolved.getExtension();
                
                if (seenArtifacts.add(artifactIdStr)) {
                    currentDepSize += resolved.getFile().length();
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


    private static String calculateHash(File file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream is = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String getStoredHash(File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#")) break;
                if (line.startsWith("# pomHash=")) return line.substring("# pomHash=".length());
            }
        } catch (Exception e) {}
        return null;
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
