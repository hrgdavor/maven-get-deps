package hr.hrg.maven.getdeps.mimic;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.DependencyResolver;
import hr.hrg.maven.getdeps.api.ResolutionResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

public class MimicDependencyResolver implements DependencyResolver {

    private final File localRepo;
    private final List<File> reactorPaths = new ArrayList<>();
    private final Map<String, File> reactorPomMap = new HashMap<>();
    private final Map<File, PomModel> pomCache = new HashMap<>();
    private final Map<File, File> canonicalCache = new HashMap<>();
    private static final String CENTRAL_URL = "https://repo1.maven.org/maven2/";
    private final Set<String> repoUrls = new LinkedHashSet<>(Collections.singletonList(CENTRAL_URL));

    public MimicDependencyResolver(String localRepoPath) {
        this.localRepo = new File(localRepoPath);
    }

    public void addReactorPath(File path) {
        this.reactorPaths.add(path);
    }

    public void addRepository(String url) {
        if (!url.endsWith("/")) url += "/";
        repoUrls.add(url);
    }

    private static class Node {
        ArtifactDescriptor ad;
        Set<String> exclusions;
        int depth;
        Node parentNode;

        Node(ArtifactDescriptor ad, Set<String> exclusions, int depth, Node parentNode) {
            this.ad = ad;
            this.exclusions = exclusions;
            this.depth = depth;
            this.parentNode = parentNode;
        }
        
        String getPath() {
            if (parentNode == null) return ad.artifactId();
            return parentNode.getPath() + " -> " + ad.artifactId();
        }
    }

    public void clearCache() {
        pomCache.clear();
        canonicalCache.clear();
        reactorPomMap.clear();
        contexts.clear();
    }

    @Override
    public ResolutionResult resolve(List<ArtifactDescriptor> artifacts) {
        // Default call for backward compatibility, assumes all scopes are relevant and no project-level overrides
        return resolve(artifacts, List.of("compile", "runtime", "provided", "system", "test"), Collections.emptyMap(), Collections.emptyMap(), null);
    }

    private ResolutionResult resolve(List<ArtifactDescriptor> artifacts, List<String> effectiveScopes, Map<String, String> projectScopes, Map<String, String> projectVersions, PomContext rootCtx) {
        List<ArtifactDescriptor> result = new ArrayList<>();
        Map<ArtifactDescriptor, File> artifactFiles = new HashMap<>();
        List<String> errors = new ArrayList<>();
        
        // GA -> version (first one at shallowest depth wins)
        Map<String, String> resolvedVersions = new HashMap<>();
        // GA -> depth
        Map<String, Integer> resolvedDepths = new HashMap<>();

        for (Map.Entry<String, String> entry : projectVersions.entrySet()) {
            resolvedVersions.put(entry.getKey(), entry.getValue());
            resolvedDepths.put(entry.getKey(), 0);
        }
        
        Deque<Node> queue = new ArrayDeque<>();
        for (ArtifactDescriptor ad : artifacts) {
            String ga = ad.groupId() + ":" + ad.artifactId();
            resolvedVersions.put(ga, ad.version());
            resolvedDepths.put(ga, 0);
            queue.add(new Node(ad, Collections.emptySet(), 0, null));
        }


        while (!queue.isEmpty()) {
            Node current = queue.poll();
            ArtifactDescriptor ad = current.ad;
            String ga = ad.groupId() + ":" + ad.artifactId();
            
            if (resolvedVersions.containsKey(ga)) {
                if (current.depth > resolvedDepths.get(ga)) {
                    continue;
                }
            }
            

            resolvedVersions.put(ga, ad.version());
            resolvedDepths.put(ga, current.depth);

            try {
                File jarFile = getOrDownload(ad, ad.type(), null);
                if (jarFile != null && jarFile.exists()) {
                    artifactFiles.put(ad, jarFile);
                }

                File pomFile = getOrDownload(ad, "pom", null);
                if (pomFile != null && pomFile.exists()) {
                    PomContext ctx = loadPomContext(pomFile);
                    
                    // Collect all dependencies from the entire parent hierarchy with their contexts
                    class DepWithCtx { 
                        PomModel.Dependency dep; 
                        PomContext ctx; 
                        DepWithCtx(PomModel.Dependency d, PomContext c) { dep = d; ctx = c; } 
                    }
                    List<DepWithCtx> allDeps = new ArrayList<>();
                    PomContext currentCtx = ctx;
                    while (currentCtx != null) {
                        if (currentCtx.pom.getDependencies() != null && currentCtx.pom.getDependencies().getDependencyList() != null) {
                            for (PomModel.Dependency d : currentCtx.pom.getDependencies().getDependencyList()) {
                                allDeps.add(new DepWithCtx(d, currentCtx));
                            }
                        }
                        currentCtx = currentCtx.parent;
                    }

                    for (DepWithCtx dc : allDeps) {
                        PomModel.Dependency dep = dc.dep;
                        PomContext dCtx = dc.ctx;
                        String dGid = dCtx.resolveProperty(dep.getGroupId());
                        String dAid = dCtx.resolveProperty(dep.getArtifactId());
                        
                        // Check exclusions from parent node
                        if (current.exclusions.contains(dGid + ":" + dAid) || current.exclusions.contains(dGid + ":*")) {
                            continue;
                        }

                        String scope = dCtx.resolveProperty(dep.getScope());
                        if (scope == null) scope = "compile";
                        
                        // project level scope override (nearest wins)
                        String pScope = projectScopes.get(dGid + ":" + dAid);
                        if (pScope != null) {
                            if (!effectiveScopes.contains(pScope)) continue; // project scope test/provided -> ignore
                            scope = pScope;
                        }

                        // Transitive scope propagation (simplified)
                        if (effectiveScopes.contains(scope)) { // Use effectiveScopes to filter
                            if (!"true".equals(dep.getOptional())) {
                                String managedV = rootCtx == null ? null : rootCtx.getManagedVersion(dGid, dAid);
                                String managedS = rootCtx == null ? null : rootCtx.getManagedScope(dGid, dAid);
                                
                                String v = managedV != null ? managedV : dCtx.resolveProperty(dep.getVersion());
                                String s = managedS != null ? managedS : scope;
                                
                                if (v != null && effectiveScopes.contains(s)) {
                                    Set<String> nextExclusions = new HashSet<>(current.exclusions);
                                    if (dep.getExclusions() != null) {
                                        for (PomModel.Exclusion ex : dep.getExclusions().getExclusionList()) {
                                            nextExclusions.add(dCtx.resolveProperty(ex.getGroupId()) + ":" + dCtx.resolveProperty(ex.getArtifactId()));
                                        }
                                    }

                                    if (dAid.contains("junit") || dAid.contains("HikariCP") || dAid.contains("classmate")) {
                                        System.out.println("DEBUG: Adding " + dAid + " path=" + current.getPath() + " -> " + dAid + " scope=" + s + " (orig=" + scope + ") rootScope=" + (rootCtx != null ? rootCtx.getManagedScope(dGid, dAid) : "null"));
                                    }
                                    ArtifactDescriptor transitive = new ArtifactDescriptor(
                                        dGid, dAid, v, s, dep.getClassifier(), dep.getType()
                                    );
                                    queue.add(new Node(transitive, nextExclusions, current.depth + 1, current));
                                }
                            }
                        }
                    }
                }

            } catch (Exception e) {
                errors.add("Error resolving " + ad + ": " + e.getMessage());
            }
        }

        // Build final list based on resolvedVersions
        for (Map.Entry<String, String> entry : resolvedVersions.entrySet()) {
            String[] parts = entry.getKey().split(":");
            String gid = parts[0];
            String aid = parts[1];
            String ver = entry.getValue();
            
            // Try to find original descriptor to preserve classifier/type
            ArtifactDescriptor found = null;
            for (ArtifactDescriptor a : artifactFiles.keySet()) {
                if (a.groupId().equals(gid) && a.artifactId().equals(aid) && a.version().equals(ver)) {
                    found = a;
                    break;
                }
            }
            if (found == null) {
                // fallback if not in artifactFiles (e.g. if getOrDownload failed but we still want it in the list)
                found = new ArtifactDescriptor(gid, aid, ver, "compile", null, "jar");
            }
            result.add(found);
        }

        return new ResolutionResult(result, artifactFiles, errors);
    }

    @Override
    public ResolutionResult resolve(Path pomPath) {
        return resolve(pomPath, List.of("compile", "runtime"));
    }

    @Override
    public ResolutionResult resolve(Path pomPath, List<String> scopes) {

        List<String> effectiveScopes = new ArrayList<>(scopes);
        if (effectiveScopes.contains("runtime") && !effectiveScopes.contains("compile")) {
            effectiveScopes.add("compile");
        }
        try {
            contexts.clear();
            if (reactorPomMap.isEmpty()) {
                for (File reactor : reactorPaths) {
                    scanReactor(reactor);
                }
            }

            PomContext ctx = loadPomContext(pomPath.toFile());

            Map<String, String> projectScopes = new HashMap<>();
            Map<String, String> projectVersions = new HashMap<>();
            List<ArtifactDescriptor> rootDeps = new ArrayList<>();
            if (ctx.pom.getDependencies() != null && ctx.pom.getDependencies().getDependencyList() != null) {
                for (PomModel.Dependency dep : ctx.pom.getDependencies().getDependencyList()) {
                    String dGid = ctx.resolveProperty(dep.getGroupId());
                    String dAid = ctx.resolveProperty(dep.getArtifactId());
                    String scope = ctx.resolveProperty(dep.getScope());
                    if (scope == null) scope = "compile";
                    
                    String v = ctx.resolveProperty(dep.getVersion());
                    if (v == null) v = ctx.getManagedVersion(dGid, dAid);
                    
                    if (v != null) {
                        projectScopes.put(dGid + ":" + dAid, scope);
                        projectVersions.put(dGid + ":" + dAid, v);

                        if (effectiveScopes.contains(scope)) {
                            ArtifactDescriptor ad = new ArtifactDescriptor(
                                dGid, dAid, v, scope, dep.getClassifier(), dep.getType()
                            );
                            rootDeps.add(ad);
                        }
                    }
                }
            }

            return resolve(rootDeps, effectiveScopes, projectScopes, projectVersions, ctx);

        } catch (Exception e) {
            e.printStackTrace();
            return new ResolutionResult(Collections.emptyList(), Collections.emptyMap(), List.of(e.getMessage()));
        }
    }

    private void scanReactor(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File pom = new File(dir, "pom.xml");
        if (pom.exists()) {
            try {
                PomModel model = PomModel.parse(pom);
                String gid = model.getGroupId();
                if (gid == null && model.getParent() != null) gid = model.getParent().getGroupId();
                if (gid != null && model.getArtifactId() != null) {
                    reactorPomMap.put(gid + ":" + model.getArtifactId(), pom);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    String name = f.getName();
                    if (name.startsWith(".") || "target".equals(name) || "bin".equals(name) || "deploy".equals(name) || "node_modules".equals(name)) {
                        continue;
                    }
                    scanReactor(f);
                }
            }
        }
    }

    private final Map<File, PomContext> contexts = new HashMap<>();

    private PomContext loadPomContext(File pomFileIn) throws Exception {
        File pomFile = canonicalCache.get(pomFileIn);
        if (pomFile == null) {
            pomFile = pomFileIn.getCanonicalFile();
            canonicalCache.put(pomFileIn, pomFile);
        }
        if (contexts.containsKey(pomFile)) return contexts.get(pomFile);
        
        PomModel pom = pomCache.get(pomFile);
        if (pom == null) {
            pom = PomModel.parse(pomFile);
            pomCache.put(pomFile, pom);
            if (pom.getRepositories() != null && pom.getRepositories().getRepositoryList() != null) {
                for (PomModel.Repository repo : pom.getRepositories().getRepositoryList()) {
                    if (repo.getUrl() != null) addRepository(repo.getUrl());
                }
            }
        }
        
        PomContext parentCtx = null;
        if (pom.getParent() != null) {
            String pgid = pom.getParent().getGroupId();
            String paid = pom.getParent().getArtifactId();
            String pv = pom.getParent().getVersion();
            
            ArtifactDescriptor parentAd = new ArtifactDescriptor(pgid, paid, pv, "pom", null, "pom");
            // Check relativePath
            File parentFile = null;
            String relPath = pom.getParent().getRelativePath();
            if (relPath == null) relPath = "../pom.xml";
            
            if (!relPath.isEmpty()) {
                File relFile = new File(pomFile.getParentFile(), relPath);
                if (relFile.exists() && relFile.isFile()) {
                    parentFile = relFile;
                } else if (relFile.exists() && relFile.isDirectory()) {
                    parentFile = new File(relFile, "pom.xml");
                }
            }

            if (parentFile == null || !parentFile.exists()) {
                parentFile = getOrDownload(parentAd, "pom", null);
            }

            if (parentFile != null && parentFile.exists()) {
                parentCtx = loadPomContext(parentFile);
            }
        }

        PomContext ctx = new PomContext(pom, this, parentCtx);
        contexts.put(pomFile, ctx); 
        return ctx;
    }

    private static class PomContext {
        final PomModel pom;
        final PomContext parent;
        final Map<String, String> managedVersions = new HashMap<>();
        final Map<String, String> managedScopes = new HashMap<>();

        PomContext(PomModel pom, MimicDependencyResolver resolver, PomContext parent) {
            this.pom = pom;
            this.parent = parent;
            if (pom.getDependencyManagement() != null && pom.getDependencyManagement().getDependencies() != null) {
                for (PomModel.Dependency dep : pom.getDependencyManagement().getDependencies().getDependencyList()) {
                    String dGid = resolveProperty(dep.getGroupId());
                    String dAid = resolveProperty(dep.getArtifactId());
                    String dScope = resolveProperty(dep.getScope());
                    
                    if ("import".equals(dScope) && "pom".equals(dep.getType())) {
                        try {
                            String iVer = resolveProperty(dep.getVersion());
                            ArtifactDescriptor ad = new ArtifactDescriptor(dGid, dAid, iVer, "pom", null, "pom");
                            File pomFile = resolver.getOrDownload(ad, "pom", null);
                            if (pomFile != null && pomFile.exists()) {
                                PomContext importCtx = resolver.loadPomContext(pomFile);
                                // Merge managed versions from the imported context (recursively handled)
                                mergeManagedVersions(importCtx);
                                // Also resolve GID/AID of the imported POM itself and add to managed versions
                                String rGid = importCtx.resolveProperty(importCtx.pom.getGroupId());
                                String rAid = importCtx.resolveProperty(importCtx.pom.getArtifactId());
                                String rVer = importCtx.resolveProperty(importCtx.pom.getVersion());
                                managedVersions.put(rGid + ":" + rAid, rVer);
                            }
                        } catch (Exception e) {
                            // ignore import errors
                        }
                    } else {
                        managedVersions.put(dGid + ":" + dAid, resolveProperty(dep.getVersion()));
                        if (dScope != null) {
                            managedScopes.put(dGid + ":" + dAid, dScope);
                        }
                    }
                }
            }
        }

        private void mergeManagedVersions(PomContext other) {
            if (other.parent != null) mergeManagedVersions(other.parent);
            managedVersions.putAll(other.managedVersions);
            managedScopes.putAll(other.managedScopes);
        }

        String getManagedVersion(String gid, String aid) {
            String v = managedVersions.get(resolveProperty(gid) + ":" + resolveProperty(aid));
            if (v == null && parent != null) return parent.getManagedVersion(gid, aid);
            return resolveProperty(v);
        }

        String getManagedScope(String gid, String aid) {
            String s = managedScopes.get(resolveProperty(gid) + ":" + resolveProperty(aid));
            if (s == null && parent != null) return parent.getManagedScope(gid, aid);
            return resolveProperty(s);
        }

        String resolveProperty(String value) {
            if (value == null || !value.contains("${")) return value;
            String result = value;
            int start;
            while ((start = result.indexOf("${")) != -1) {
                int end = result.indexOf("}", start);
                if (end == -1) break;
                String propName = result.substring(start + 2, end);
                String propValue = getPropertyValue(propName);
                if (propValue != null) {
                    result = result.substring(0, start) + propValue + result.substring(end + 1);
                } else {
                    break; // stop if can't resolve
                }
            }
            return result;
        }

        private String getPropertyValue(String name) {
            String valStr = null;
            if ("project.version".equals(name) || "version".equals(name)) valStr = pom.getVersion();
            else if ("project.groupId".equals(name) || "groupId".equals(name)) valStr = pom.getGroupId();
            else if ("project.artifactId".equals(name) || "artifactId".equals(name)) valStr = pom.getArtifactId();
            else if ("project.parent.version".equals(name)) valStr = pom.getParent() != null ? pom.getParent().getVersion() : null;
            else {
                Object val = pom.getProperties().get(name);
                if (val != null) valStr = PomModel.extractText(val);
                else {
                    if (parent != null) valStr = parent.getPropertyValue(name);
                }
            }
            
            if (valStr == null) {
                valStr = System.getProperty(name);
                if (valStr == null) valStr = System.getenv(name);
            }
            // if (valStr != null) System.out.println("Property " + name + " -> " + valStr + " (in " + pom.getArtifactId() + ")");
            return valStr;
        }
    }

    private File getOrDownload(ArtifactDescriptor ad, String extension, File pomFile) throws IOException {
        // try reactor first
        if ("pom".equals(extension)) {
            File f = reactorPomMap.get(ad.groupId() + ":" + ad.artifactId());
            if (f != null) return f;
        }

        for (File reactor : reactorPaths) {
            File f = new File(reactor, ad.artifactId() + "-" + ad.version() + "." + (extension == null ? "jar" : extension));
            if (f.exists()) return f;
            // check in target too (though scanReactor handles poms)
            f = new File(reactor, ad.artifactId() + "/target/" + ad.artifactId() + "-" + ad.version() + "." + (extension == null ? "jar" : extension));
            if (f.exists()) return f;
        }

        File file = getArtifactFile(ad, extension);
        if (!file.exists()) {
            downloadFromRepos(ad, extension, file);
        }
        return file;
    }

    private File getArtifactFile(ArtifactDescriptor ad, String extension) {
        String path = ad.groupId().replace('.', File.separatorChar) + File.separator +
                ad.artifactId() + File.separator +
                ad.version() + File.separator +
                ad.artifactId() + "-" + ad.version() + 
                (ad.classifier() != null && !ad.classifier().isEmpty() ? "-" + ad.classifier() : "") +
                "." + (extension == null ? "jar" : extension);
        return new File(localRepo, path);
    }

    private void downloadFromRepos(ArtifactDescriptor ad, String extension, File target) throws IOException {
        String relPath = ad.groupId().replace('.', '/') + "/" +
                ad.artifactId() + "/" +
                ad.version() + "/" +
                ad.artifactId() + "-" + ad.version() + 
                (ad.classifier() != null && !ad.classifier().isEmpty() ? "-" + ad.classifier() : "") +
                "." + (extension == null ? "jar" : extension);
        
        for (String repoUrl : repoUrls) {
            String urlStr = repoUrl + relPath;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    target.getParentFile().mkdirs();
                    try (InputStream is = conn.getInputStream();
                         FileOutputStream fos = new FileOutputStream(target)) {
                        is.transferTo(fos);
                    }
                    System.out.println("Downloaded: " + urlStr + " to " + target.getAbsolutePath());
                    return; // Success
                }
            } catch (Exception e) {
                // ignore and try next repo
            }
        }
    }

}
