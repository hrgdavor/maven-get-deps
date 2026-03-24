package hr.hrg.maven.getdeps.mimic;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.CachedDependency;
import hr.hrg.maven.getdeps.api.CacheManager;
import hr.hrg.maven.getdeps.api.DependencyResolver;
import hr.hrg.maven.getdeps.api.ResolutionResult;

import hr.hrg.wyhash.Wyhash64;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class MimicDependencyResolver implements DependencyResolver {

    private final File localRepo;
    private final List<File> reactorPaths = new ArrayList<>();
    private final Map<String, File> reactorPomMap = new HashMap<>();
    private final Map<File, PomModel> pomCache = new HashMap<>();
    private static final String CENTRAL_URL = "https://repo1.maven.org/maven2/";
    private final Set<String> repoUrls = new LinkedHashSet<>(Collections.singletonList(CENTRAL_URL));
    private final Map<File, PomContext> contexts = new HashMap<>();

    private String debugFilter = null;
    private boolean skipSiblings = false;
    private boolean noCache = false;

    public void setDebugFilter(String debugFilter) {
        this.debugFilter = debugFilter;
    }

    public void setSkipSiblings(boolean skipSiblings) {
        this.skipSiblings = skipSiblings;
    }

    public boolean isDebugMatch(String gid, String aid) {
        if (debugFilter == null || debugFilter.isEmpty())
            return false;
        if (debugFilter.equals("ALL"))
            return true;
        return (gid != null && gid.contains(debugFilter)) || (aid != null && aid.contains(debugFilter));
    }

    public MimicDependencyResolver(File localRepo, List<File> reactorPaths) {
        this.localRepo = localRepo;
        this.reactorPaths.addAll(reactorPaths);
    }

    public void addReactorPath(File path) {
        this.reactorPaths.add(path);
    }

    public void addRepository(String url) {
        if (!url.endsWith("/"))
            url += "/";
        repoUrls.add(url);
    }

    public void setNoCache(boolean noCache) {
        this.noCache = noCache;
    }


    private static class Node {
        ArtifactDescriptor ad;
        Set<String> exclusions;
        int depth;
        Node parentNode;
        PomContext context;
        String path;
        boolean isReactor;

        Node(ArtifactDescriptor ad, Set<String> exclusions, int depth, Node parentNode) {
            this.ad = ad;
            this.exclusions = exclusions;
            this.depth = depth;
            this.parentNode = parentNode;
        }
    }

    public void clearCache() {
        pomCache.clear();
        reactorPomMap.clear();
        contexts.clear();
        noCache = true;
    }

    @Override
    public ResolutionResult resolve(List<ArtifactDescriptor> artifacts) {
        return resolve(artifacts, List.of("compile", "runtime", "provided", "system", "test"));
    }

    public ResolutionResult resolve(List<ArtifactDescriptor> artifacts, List<String> effectiveScopes) {
        List<Node> rootNodes = new ArrayList<>();
        Map<String, ArtifactDescriptor> resolved = new LinkedHashMap<>();
        Map<String, Integer> resolvedDepths = new HashMap<>();
        for (ArtifactDescriptor ad : artifacts) {
            String ga = ad.groupId() + ":" + ad.artifactId();
            resolvedDepths.put(ga, 0);
            resolved.put(ga, ad);
            rootNodes.add(new Node(ad, Collections.emptySet(), 0, null));
        }
        return resolve(rootNodes, effectiveScopes, Collections.emptyMap(), null, resolved, resolvedDepths,
                new HashSet<>());
    }

    public static String propagateScope(String parentScope, String childScope) {
        if (childScope == null || childScope.isEmpty())
            childScope = "compile";
        if (parentScope == null || parentScope.isEmpty())
            parentScope = "compile";

        if ("provided".equals(childScope) || "test".equals(childScope))
            return null;

        if ("compile".equals(parentScope))
            return childScope;
        if ("runtime".equals(parentScope)) {
            if ("compile".equals(childScope))
                return "runtime";
            return childScope;
        }
        if ("provided".equals(parentScope)) {
            if ("compile".equals(childScope) || "runtime".equals(childScope))
                return "provided";
            return null;
        }
        if ("test".equals(parentScope)) {
            if ("compile".equals(childScope) || "runtime".equals(childScope))
                return "test";
            return null;
        }
        return childScope;
    }

    public static int scopeStrength(String scope) {
        if (scope == null || scope.equals("compile"))
            return 3;
        if (scope.equals("runtime"))
            return 2;
        if (scope.equals("provided"))
            return 1;
        if (scope.equals("test"))
            return 0;
        return -1;
    }

    private ResolutionResult resolve(List<Node> rootNodes, List<String> effectiveScopes,
            Map<String, String> projectScopes, PomContext rootCtx, Map<String, ArtifactDescriptor> resolved,
            Map<String, Integer> resolvedDepths, Set<String> resolvedOptionals) {
        List<ArtifactDescriptor> resultList = new ArrayList<>();
        Map<ArtifactDescriptor, File> artifactFiles = new HashMap<>();
        List<String> errors = new ArrayList<>();

        Deque<Node> queue = new ArrayDeque<>(rootNodes);

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            ArtifactDescriptor ad = current.ad;
            String ga = ad.groupId() + ":" + ad.artifactId();

            if (isDebugMatch(ad.groupId(), ad.artifactId())) {
                System.err.println("MIMIC: [RESOLVE-STEP] " + ad.groupId() + ":" + ad.artifactId() + ":" + ad.version() + ":" + ad.scope());
            }

            if (resolvedDepths.containsKey(ga)) {
                int oldDepth = resolvedDepths.get(ga);
                if (current.depth > oldDepth)
                    continue;
                // If the currently registered descriptor for this depth has a stronger scope,
                // cancel
                ArtifactDescriptor oldAd = resolved.get(ga);
                if (current.depth == oldDepth && oldAd != ad
                        && scopeStrength(ad.scope()) < scopeStrength(oldAd.scope())) {
                    continue;
                }
            }

            resolvedDepths.put(ga, current.depth);
            resolved.put(ga, ad);
            ad.setDepth(current.depth);

            try {
                File jarFile = getOrDownload(ad, ad.type(), null);
                if (jarFile != null && jarFile.exists())
                    artifactFiles.put(ad, jarFile);

                File pomFile = getOrDownload(ad, "pom", null);
                if (pomFile != null && pomFile.exists()) {
                    File absPomFile = pomFile.getAbsoluteFile();
                    boolean isReactor = reactorPomMap.containsValue(absPomFile);
                    long currentHash = isReactor ? CacheManager.calculateWyhash64(absPomFile) : -1;
                    File cacheFile = CacheManager.getCacheFile(pomFile, effectiveScopes);
                    List<CachedDependency> directDeps = null;
                    if (!noCache && !isReactor) {
                        directDeps = CacheManager.loadCache(cacheFile, currentHash);
                        if (directDeps != null && isDebugMatch(ad.groupId(), ad.artifactId()))
                            System.err.println("MIMIC: [CACHE-HIT] " + ad + " (hash=" + currentHash + ")");
                    }

                    if (directDeps == null) {
                        directDeps = new ArrayList<>();
                        PomContext ctx = loadPomContext(pomFile, Collections.emptyMap());
                        for (PomModel.Dependency dep : ctx.getEffectiveDependencies()) {
                            String dGid = ctx.resolveProperty(dep.getGroupId());
                            String dAid = ctx.resolveProperty(dep.getArtifactId());
                            
                            // Apply HIS OWN dependency management and properties
                            String libManagedV = ctx.getManagedVersion(dGid, dAid);
                            String libManagedS = ctx.getManagedScope(dGid, dAid);
                            String rawV = dep.getVersion();
                            String rawS = dep.getScope();
                            String dV = ctx.resolveProperty(rawV != null ? rawV : libManagedV);
                            String dS = ctx.resolveProperty(rawS != null ? rawS : (libManagedS != null ? libManagedS : "compile"));
                            
                            if (dV == null) continue;

                            String v = resolveVersionRange(new ArtifactDescriptor(dGid, dAid, dV, null, null, null));
                            String dType = dep.getType() == null ? "jar" : dep.getType();
                            boolean isOpt = "true".equals(ctx.resolveProperty(dep.getOptional()));
                            
                            Set<String> depExclusions = new HashSet<>();
                            if (dep.getExclusions() != null) {
                                for (PomModel.Exclusion ex : dep.getExclusions().getExclusionList()) {
                                    depExclusions.add(ctx.resolveProperty(ex.getGroupId()) + ":" + ctx.resolveProperty(ex.getArtifactId()));
                                }
                            }
                            directDeps.add(new CachedDependency(dGid, dAid, v, dS, dep.getClassifier(), dType, isOpt, depExclusions));
                        }
                        if (!noCache && !isReactor) {
                            CacheManager.saveCache(cacheFile, directDeps, currentHash);
                        }
                    }

                    for (CachedDependency cd : directDeps) {
                        String gaTrans = cd.groupId + ":" + cd.artifactId;
                        if (current.exclusions.contains(gaTrans) || current.exclusions.contains(cd.groupId + ":*")) {
                            if (isDebugMatch(cd.groupId, cd.artifactId))
                                System.err.println("MIMIC: [EXCLUDE-SKIP] " + gaTrans + " in " + ad);
                            continue;
                        }

                        // 4. Apply Root project dependency management overrides
                        String rootManagedV = rootCtx == null ? null : rootCtx.getManagedVersion(cd.groupId, cd.artifactId);
                        String rootManagedS = rootCtx == null ? null : rootCtx.getManagedScope(cd.groupId, cd.artifactId);

                        String v = rootManagedV != null ? resolveVersionRange(new ArtifactDescriptor(cd.groupId, cd.artifactId, rootManagedV, null, null, null)) : cd.version;
                        String sRaw = rootManagedS != null ? rootManagedS : cd.scope;

                        // 5. Propagate scope from parent to child
                        String s = propagateScope(ad.scope(), sRaw);
                        if (s == null) continue;

                        int nextDepth = current.depth + 1;
                        boolean alreadyHaveBetter = false;
                        if (resolvedDepths.containsKey(gaTrans)) {
                            int oldDepth = resolvedDepths.get(gaTrans);
                            ArtifactDescriptor oldAd = resolved.get(gaTrans);
                            boolean oldIsOpt = resolvedOptionals.contains(gaTrans);

                            if (oldDepth <= nextDepth) {
                                boolean shouldPromote = scopeStrength(s) > scopeStrength(oldAd.scope());
                                if (oldDepth == 0 && "provided".equals(oldAd.scope()) && ("compile".equals(s) || "runtime".equals(s))) {
                                    shouldPromote = false;
                                }

                                if (oldAd != null && shouldPromote) {
                                    ArtifactDescriptor promoted = new ArtifactDescriptor(oldAd.groupId(), oldAd.artifactId(), oldAd.version(), s, oldAd.classifier(), oldAd.type(), oldAd.path());
                                    resolved.put(gaTrans, promoted);
                                    if (isDebugMatch(cd.groupId, cd.artifactId))
                                        System.err.println("MIMIC: [SCOPE-PROMOTE] " + gaTrans + " from " + oldAd.scope() + " to " + s + " at depth " + nextDepth + " via " + ad.groupId() + ":" + ad.artifactId());
                                }

                                if (oldDepth < nextDepth) {
                                    if (!oldIsOpt) alreadyHaveBetter = true;
                                } else if (oldDepth == nextDepth) {
                                    if (!(oldIsOpt && !cd.isOptional)) alreadyHaveBetter = true;
                                }
                            }
                        }
                        if (alreadyHaveBetter) continue;

                        String nextPath = current.path + " -> " + cd.groupId + ":" + cd.artifactId + ":" + v;
                        ArtifactDescriptor transitive = new ArtifactDescriptor(cd.groupId, cd.artifactId, v, s, cd.classifier, cd.type, nextPath);

                        resolvedDepths.put(gaTrans, nextDepth);
                        resolved.put(gaTrans, transitive);
                        if (cd.isOptional) {
                            resolvedOptionals.add(gaTrans);
                        } else {
                            resolvedOptionals.remove(gaTrans);
                        }

                        if (s != null && effectiveScopes.contains(s) && !cd.isOptional) {
                            if (isDebugMatch(cd.groupId, cd.artifactId)) {
                                System.err.println("MIMIC: [TRANS-ADD] " + cd.groupId + ":" + cd.artifactId + ":" + v + " (s=" + s + ") depth=" + nextDepth + " parent: " + ad);
                            }

                            Set<String> nextExclusions = new HashSet<>(current.exclusions);
                            nextExclusions.addAll(cd.exclusions);

                            Node nextNode = new Node(transitive, nextExclusions, nextDepth, current);
                            nextNode.path = nextPath;
                            nextNode.isReactor = reactorPomMap.containsKey(gaTrans);
                            queue.add(nextNode);
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("Error resolving " + ad + ": " + e.getMessage());
            }
        }

        for (ArtifactDescriptor adResolved : resolved.values()) {
            String ga = adResolved.groupId() + ":" + adResolved.artifactId();
            // Nuance 30: Reactor Module Inclusion (optional skipping)
            if (skipSiblings && reactorPomMap.containsKey(ga)) {
                if (isDebugMatch(adResolved.groupId(), adResolved.artifactId())) {
                    System.err.println("MIMIC: [REACTOR-SKIP] " + ga);
                }
                continue;
            }

            if (effectiveScopes.contains(adResolved.scope()) && !resolvedOptionals.contains(ga)) {
                resultList.add(adResolved);
            }
        }

        return new ResolutionResult(resultList, artifactFiles, errors);
    }

    @Override
    public ResolutionResult resolve(Path pomPath) {
        return resolve(pomPath, List.of("compile", "runtime"));
    }

    @Override
    public ResolutionResult resolve(Path pomPath, List<String> scopes) {
        List<String> effectiveScopes = new ArrayList<>(scopes);
        if (effectiveScopes.contains("runtime") && !effectiveScopes.contains("compile"))
            effectiveScopes.add("compile");
        try {
            File pomFile = pomPath.toFile();
            long currentPomHash = -1; // We'll calculate it only if needed (for reactor) or skip for cache check if not matching reactor
            
            populateReactorMap(); // Always scan reactor to identify siblings
            contexts.clear();

            boolean isReactorRoot = reactorPomMap.containsValue(pomFile.getAbsoluteFile());

            if (!noCache && !isReactorRoot) {
                File cacheFile = CacheManager.getCacheFile(pomFile, effectiveScopes);
                List<CachedDependency> cached = CacheManager.loadCache(cacheFile, currentPomHash);
                if (cached != null) {
                    List<ArtifactDescriptor> dependencies = new ArrayList<>();
                    Map<ArtifactDescriptor, File> artifactFiles = new HashMap<>();
                    for (CachedDependency cd : cached) {
                        ArtifactDescriptor ad = cd.toArtifactDescriptor();
                        dependencies.add(ad);
                        File jarFile = getArtifactFile(ad, ad.type());
                        if (jarFile != null && jarFile.exists())
                            artifactFiles.put(ad, jarFile);
                    }
                    return new ResolutionResult(dependencies, artifactFiles, Collections.emptyList());
                }
            }

            PomContext ctx = loadPomContext(pomFile, Collections.emptyMap());
            System.err.println("MIMIC: Root POM loaded. Managed entries: " + ctx.managedVersions.size());

            Map<String, String> projectScopes = new HashMap<>();
            List<Node> rootNodes = new ArrayList<>();
            Map<String, ArtifactDescriptor> resolved = new HashMap<>();
            Map<String, Integer> resolvedDepths = new HashMap<>();
            Set<String> resolvedOptionals = new HashSet<>();

            for (PomModel.Dependency dep : ctx.getEffectiveDependencies()) {
                String dGid = ctx.resolveProperty(dep.getGroupId());
                String dAid = ctx.resolveProperty(dep.getArtifactId());
                String dV = ctx.resolveProperty(dep.getVersion());
                String dS = ctx.resolveProperty(dep.getScope());

                String managedV = ctx.getManagedVersion(dGid, dAid);
                String vRaw = (dV != null) ? dV : managedV;
                String v = vRaw;
                if (v != null && (v.startsWith("[") || v.startsWith("("))) {
                    v = resolveVersionRange(new ArtifactDescriptor(dGid, dAid, v, null, null, null));
                }
                String managedS = ctx.getManagedScope(dGid, dAid);
                // Direct dependency scope ALWAYS wins over managed scope
                String s = (dS != null) ? dS : (managedS != null ? managedS : "compile");

                if (v != null) {
                    projectScopes.put(dGid + ":" + dAid, s);
                    String ga = dGid + ":" + dAid;
                    String dType = dep.getType();
                    if (dType == null)
                        dType = "jar";
                    ArtifactDescriptor ad = new ArtifactDescriptor(dGid, dAid, v, s, dep.getClassifier(), dType, ga);

                    // Register EVERYTHING at depth 0 to ensure "Nearest Wins" for test/provided
                    // scopes too!
                    resolvedDepths.put(ga, 0);
                    resolved.put(ga, ad);
                    boolean isOpt = "true".equals(dep.getOptional());
                    if (isOpt) {
                        resolvedOptionals.add(ga);
                        if (isDebugMatch(dGid, dAid))
                            System.err.println("MIMIC: [OPT-ROOT] " + ga);
                    }

                    if (effectiveScopes.contains(s) && !isOpt) {
                        Set<String> exclusions = new HashSet<>();
                        if (dep.getExclusions() != null) {
                            for (PomModel.Exclusion ex : dep.getExclusions().getExclusionList()) {
                                exclusions.add(ctx.resolveProperty(ex.getGroupId()) + ":"
                                        + ctx.resolveProperty(ex.getArtifactId()));
                            }
                        }

                        Node rn = new Node(ad, exclusions, 0, null);
                        rn.path = ga;
                        rn.isReactor = reactorPomMap.containsKey(ga);
                        rootNodes.add(rn);

                        if (isDebugMatch(dGid, dAid)) {
                            System.err.println("MIMIC: [ROOTADD ] " + dGid + ":" + dAid + ":" + v + " (s=" + s + ")");
                        }
                    }
                } else {
                    System.err.println("MIMIC: [ROOTSKIP] " + dGid + ":" + dAid + " (no version)");
                }
            }
            ResolutionResult finalResult = resolve(rootNodes, effectiveScopes, projectScopes, ctx, resolved, resolvedDepths, resolvedOptionals);
            if (!noCache && !isReactorRoot && finalResult.errors().isEmpty()) {
                File cacheFile = CacheManager.getCacheFile(pomFile, effectiveScopes);
                List<CachedDependency> toCache = finalResult.dependencies().stream()
                        .map(ad -> new CachedDependency(ad.groupId(), ad.artifactId(), ad.version(), ad.scope(), ad.classifier(), ad.type(), false, Collections.emptySet()))
                        .collect(Collectors.toList());
                CacheManager.saveCache(cacheFile, toCache, currentPomHash);
            }
            return finalResult;
        } catch (Exception e) {
            e.printStackTrace();
            return new ResolutionResult(Collections.emptyList(), Collections.emptyMap(), List.of(e.getMessage()));
        }
    }

    private void scanReactor(File dir) {
        if (dir == null || !dir.isDirectory())
            return;
        File pom = new File(dir, "pom.xml");
        if (pom.exists()) {
            try {
                PomModel model = PomModel.parse(pom);
                String gid = model.getGroupId();
                if (gid == null && model.getParent() != null)
                    gid = model.getParent().getGroupId();
                if (gid != null && model.getArtifactId() != null)
                    reactorPomMap.put(gid + ":" + model.getArtifactId(), pom);
            } catch (Exception e) {
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    String name = f.getName();
                    if (name.startsWith(".") || "target".equals(name) || "node_modules".equals(name))
                        continue;
                    scanReactor(f);
                }
            }
        }
    }

    private PomContext loadPomContext(File pomFileIn, Map<String, String> inheritedProperties) throws Exception {
        File pomFile = pomFileIn.getCanonicalFile();
        if (contexts.containsKey(pomFile))
            return contexts.get(pomFile);

        System.err.println("MIMIC: Loading context for: " + pomFile);
        PomModel pom = pomCache.get(pomFile);
        if (pom == null) {
            pom = PomModel.parse(pomFile);
            pomCache.put(pomFile, pom);
        }

        PomContext parentCtx = null;
        if (pom.getParent() != null) {
            String pgid = resolveProperty(pom.getParent().getGroupId(), inheritedProperties);
            String paid = resolveProperty(pom.getParent().getArtifactId(), inheritedProperties);
            String pv = resolveProperty(pom.getParent().getVersion(), inheritedProperties);

            System.err.println("MIMIC: Parent detected: " + pgid + ":" + paid + ":" + pv);
            ArtifactDescriptor parentAd = new ArtifactDescriptor(pgid, paid, pv, "pom", null, "pom");
            File parentFile = null;
            String relPath = pom.getParent().getRelativePath();
            if (relPath == null)
                relPath = "../pom.xml";

            if (!relPath.isEmpty()) {
                File relFile = new File(pomFile.getParentFile(), relPath);
                if (relFile.isDirectory())
                    relFile = new File(relFile, "pom.xml");
                if (relFile.exists() && relFile.isFile())
                    parentFile = relFile;
            }

            if (parentFile == null || !parentFile.exists())
                parentFile = getOrDownload(parentAd, "pom", null);
            if (parentFile != null && parentFile.exists()) {
                parentCtx = loadPomContext(parentFile, inheritedProperties);
            }
        }

        PomContext ctx = new PomContext(pom, this, parentCtx, inheritedProperties);
        contexts.put(pomFile, ctx);
        return ctx;
    }

    private String resolveProperty(String val, Map<String, String> props) {
        if (val == null || !val.contains("${"))
            return val;

        String lastVal = null;
        int depth = 0;
        // Loop for recursive resolution, up to a certain depth to prevent infinite
        // loops
        while (val.contains("${") && !val.equals(lastVal) && depth < 10) {
            lastVal = val; // Store current value to detect if no changes occurred
            boolean changedThisIteration = false;
            for (Map.Entry<String, String> entry : props.entrySet()) {
                if (entry.getValue() != null) {
                    String needle = "${" + entry.getKey() + "}";
                    if (val.contains(needle)) {
                        val = val.replace(needle, entry.getValue());
                        changedThisIteration = true;
                    }
                }
            }
            if (!changedThisIteration && val.contains("${")) {
                // If no properties from 'props' changed the value, but it still contains
                // placeholders,
                // it means they might be unresolved or refer to properties not in 'props'.
                // For this specific method, we only resolve against 'props'.
                break;
            }
            depth++;
        }
        return val;
    }

    private static class PomContext {
        final PomModel pom;
        final MimicDependencyResolver resolver;
        final PomContext parent;
        final Map<String, String> managedVersions = new HashMap<>();
        final Map<String, String> managedScopes = new HashMap<>();
        final Map<String, String> properties = new HashMap<>();

        public PomContext(PomModel pom, MimicDependencyResolver resolver, PomContext parent,
                Map<String, String> inheritedProperties) {
            this.pom = pom;
            this.resolver = resolver;
            this.parent = parent;

            // Priority: inherited > own > parent
            if (parent != null)
                this.properties.putAll(parent.properties);

            if (pom.getProperties() != null) {
                for (Map.Entry<String, Object> entry : pom.getProperties().entrySet()) {
                    String val = PomModel.extractText(entry.getValue());
                    if (val != null) {
                        if (resolver.isDebugMatch(entry.getKey(), "")) {
                            System.err.println(
                                    "MIMIC: [CTX-OWN  ] " + entry.getKey() + "=" + val + " in " + pom.getArtifactId());
                        }
                        this.properties.put(entry.getKey(), val);
                    }
                }
            }

            if (resolver.debugFilter != null) {
                for (Map.Entry<String, String> entry : inheritedProperties.entrySet()) {
                    if (entry.getKey().contains(resolver.debugFilter)) {
                        System.err.println("MIMIC: [CTX-INHER] " + entry.getKey() + "=" + entry.getValue()
                                + " overriding in " + pom.getArtifactId());
                    }
                }
            }
            this.properties.putAll(inheritedProperties);

            // Explicitly handle project properties for own resolution
            String projectVer = pom.getVersion();
            if (projectVer == null && pom.getParent() != null)
                projectVer = pom.getParent().getVersion();
            if (projectVer != null) {
                this.properties.put("project.version", projectVer);
                this.properties.put("version", projectVer);
            }
            String projectGid = pom.getGroupId();
            if (projectGid == null && pom.getParent() != null)
                projectGid = pom.getParent().getGroupId();
            if (projectGid != null) {
                this.properties.put("project.groupId", projectGid);
                this.properties.put("groupId", projectGid);
            }
            String projectAid = pom.getArtifactId();
            if (projectAid != null) {
                this.properties.put("project.artifactId", projectAid);
                this.properties.put("artifactId", projectAid);
            }
            if (pom.getParent() != null) {
                String pV = pom.getParent().getVersion();
                String pG = pom.getParent().getGroupId();
                String pA = pom.getParent().getArtifactId();
                if (pV != null) {
                    this.properties.put("project.parent.version", pV);
                    this.properties.put("parent.version", pV);
                }
                if (pG != null) {
                    this.properties.put("project.parent.groupId", pG);
                    this.properties.put("parent.groupId", pG);
                }
                if (pA != null) {
                    this.properties.put("project.parent.artifactId", pA);
                    this.properties.put("parent.artifactId", pA);
                }
            }

            if (parent != null) {
                managedVersions.putAll(parent.managedVersions);
                managedScopes.putAll(parent.managedScopes);
            }

            if (pom.getDependencyManagement() != null && pom.getDependencyManagement().getDependencies() != null) {
                for (PomModel.Dependency dep : pom.getDependencyManagement().getDependencies().getDependencyList()) {
                    String dGid = resolveProperty(dep.getGroupId());
                    String dAid = resolveProperty(dep.getArtifactId());
                    String dScope = dep.getScope(); // keep raw
                    String dV = dep.getVersion(); // keep raw

                    if (dGid != null && dAid != null) {
                        String key = dGid + ":" + dAid;
                        if ("import".equals(resolveProperty(dScope)) && "pom".equals(dep.getType())) {
                            try {
                                String iVer = resolveProperty(dV);
                                if (iVer == null)
                                    iVer = getManagedVersion(dGid, dAid);

                                if (iVer != null) {
                                    System.err.println("MIMIC: [IMPORT ] " + dGid + ":" + dAid + ":" + iVer + " in "
                                            + pom.getArtifactId());
                                    ArtifactDescriptor ad = new ArtifactDescriptor(dGid, dAid, iVer, "import", null,
                                            "pom");
                                    File pomFile = resolver.getOrDownload(ad, "pom", null);
                                    if (pomFile != null && pomFile.exists()) {
                                        PomContext importCtx = resolver.loadPomContext(pomFile, this.properties);
                                        mergeManaged(importCtx);
                                    }
                                }
                            } catch (Exception e) {
                            }
                        } else {
                            if (dV != null) {
                                if (resolver.isDebugMatch(dGid, dAid)) {
                                    System.err.println("MIMIC: [MGNTDEF] " + key + ":" + dV + " (scope=" + dScope
                                            + ") in " + pom.getArtifactId());
                                }
                                managedVersions.put(key, dV);
                                if (dScope != null) {
                                    if (resolver.isDebugMatch(dGid, dAid)) {
                                        System.err.println("MIMIC: [MGNTSET] " + key + " scope=" + dScope + " in "
                                                + pom.getArtifactId());
                                    }
                                    managedScopes.put(key, dScope);
                                }
                            }
                        }
                    }
                }
            }
        }

        List<PomModel.Dependency> getEffectiveDependencies() {
            List<PomModel.Dependency> allDeps = new ArrayList<>();
            // Maven behavior: dependencies are NOT inherited from parents in the same way
            // managed ones are?
            // Actually, they ARE inherited.
            if (parent != null)
                allDeps.addAll(parent.getEffectiveDependencies());
            if (pom.getDependencies() != null && pom.getDependencies().getDependencyList() != null) {
                allDeps.addAll(pom.getDependencies().getDependencyList());
            }
            return allDeps;
        }

        private void mergeManaged(PomContext other) {
            for (Map.Entry<String, String> entry : other.managedVersions.entrySet()) {
                String val = other.resolveProperty(entry.getValue());
                if (resolver.isDebugMatch(entry.getKey(), "")) {
                    System.err.println("MIMIC: [MGNT-MERGE] " + entry.getKey() + " " + entry.getValue() + " -> " + val
                            + " from " + other.pom.getArtifactId() + " into " + pom.getArtifactId());
                }
                managedVersions.putIfAbsent(entry.getKey(), val);
            }
            for (Map.Entry<String, String> entry : other.managedScopes.entrySet()) {
                String val = other.resolveProperty(entry.getValue());
                managedScopes.putIfAbsent(entry.getKey(), val);
            }
        }

        String getManagedVersion(String gid, String aid) {
            String key = gid + ":" + aid;
            String v = managedVersions.get(key);
            if (v == null && parent != null)
                v = parent.getManagedVersion(gid, aid);
            String resolved = resolveProperty(v);
            if (resolver.isDebugMatch(gid, aid)) {
                System.err.println("MIMIC: [MGNT-GET] " + key + " v=" + v + " resolved=" + resolved + " in "
                        + pom.getArtifactId());
            }
            return resolved;
        }

        String getManagedScope(String gid, String aid) {
            String key = gid + ":" + aid;
            String s = managedScopes.get(key);
            if (s == null && parent != null)
                s = parent.getManagedScope(gid, aid);
            String resolved = resolveProperty(s);
            if (resolver.isDebugMatch(gid, aid)) {
                System.err.println("MIMIC: [MGNT-GET] " + gid + ":" + aid + " s=" + s + " resolved=" + resolved + " in "
                        + pom.getArtifactId());
            }
            return resolved;
        }

        public String resolveProperty(String val) {
            if (val == null || !val.contains("${"))
                return val;
            String result = val;

            // Loop for recursive resolution
            for (int i = 0; i < 8; i++) {
                boolean changed = false;
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    if (entry.getValue() != null) {
                        String needle = "${" + entry.getKey() + "}";
                        if (result.contains(needle)) {
                            result = result.replace(needle, entry.getValue());
                            changed = true;
                        }
                    }
                }
                if (!changed || !result.contains("${"))
                    break;
            }
            if (result.contains("${")) {
                // System.err.println("MIMIC: [PROP-FAIL] " + val + " -> " + result + " in
                // context " + pom.getArtifactId());
            } else if (!val.equals(result)) {
                if (resolver.isDebugMatch(val, "")) {
                    System.err.println(
                            "MIMIC: [PROP-RESOLVE] " + val + " -> " + result + " in context " + pom.getArtifactId());
                }
            }
            return result;
        }
    }

    private String resolveVersionRange(ArtifactDescriptor ad) {
        String version = ad.version();
        if (version == null)
            return null;
        if (!version.startsWith("[") && !version.startsWith("("))
            return version;

        // Extract base version for matching (simplified range parsing)
        String v = version.substring(1);
        int comma = v.indexOf(',');
        boolean upperIncluded = version.endsWith("]");
        String upperV = null;
        if (comma != -1) {
            upperV = v.substring(comma + 1);
            if (upperV.endsWith(")") || upperV.endsWith("]"))
                upperV = upperV.substring(0, upperV.length() - 1).trim();
            v = v.substring(0, comma).trim();
        } else {
            if (v.endsWith(")") || v.endsWith("]"))
                v = v.substring(0, v.length() - 1).trim();
        }

        File versionDir = new File(localRepo, ad.groupId().replace('.', '/') + "/" + ad.artifactId());
        if (versionDir.exists()) {
            File[] versions = versionDir.listFiles(File::isDirectory);
            if (versions != null) {
                // Sort descending so the highest matching version is first
                // We should use a proper version comparator if possible, but alphabetically
                // works for most cases
                List<String> sortedVersions = new ArrayList<>();
                for (File vf : versions)
                    sortedVersions.add(vf.getName());
                sortedVersions.sort((s1, s2) -> compareVersions(s2, s1)); // Descending

                for (String vName : sortedVersions) {
                    if (isVersionInRange(vName, version))
                        return vName;
                }
            }
        }
        return v.isEmpty() ? version : v; // fallback to base version or original
    }

    private boolean isVersionInRange(String vName, String range) {
        // Very simplified range matching for common cases like [3.0, 4.0)
        String r = range.substring(1, range.length() - 1);
        String[] parts = r.split(",");
        if (parts.length == 1) {
            return vName.equals(parts[0].trim());
        }
        String lower = parts[0].trim();
        String upper = parts[1].trim();
        boolean lowerIncl = range.startsWith("[");
        boolean upperIncl = range.endsWith("]");

        boolean match = true;
        if (!lower.isEmpty()) {
            int cmp = compareVersions(vName, lower);
            if (lowerIncl)
                match = cmp >= 0;
            else
                match = cmp > 0;
        }
        if (match && !upper.isEmpty()) {
            int cmp = compareVersions(vName, upper);
            if (upperIncl)
                match = match && cmp <= 0;
            else
                match = match && cmp < 0;
        }
        return match;
    }

    private int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("[\\.\\-]");
        String[] p2 = v2.split("[\\.\\-]");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            String s1 = i < p1.length ? p1[i] : "0";
            String s2 = i < p2.length ? p2[i] : "0";
            try {
                int n1 = Integer.parseInt(s1);
                int n2 = Integer.parseInt(s2);
                if (n1 != n2)
                    return Integer.compare(n1, n2);
            } catch (NumberFormatException e) {
                int res = s1.compareTo(s2);
                if (res != 0)
                    return res;
            }
        }
        return 0;
    }

    private File getOrDownload(ArtifactDescriptor ad, String extension, File pomFile) throws IOException {
        String version = ad.version();
        if (version != null && (version.startsWith("[") || version.startsWith("("))) {
            String bestV = resolveVersionRange(ad);
            if (bestV != null) {
                ad = new ArtifactDescriptor(ad.groupId(), ad.artifactId(), bestV, ad.scope(), ad.classifier(),
                        ad.type(), ad.path());
            }
        }

        // Nuance: Map test-jar type to classifier=tests and extension=jar
        if ("test-jar".equals(ad.type())) {
            ad = new ArtifactDescriptor(ad.groupId(), ad.artifactId(), ad.version(), ad.scope(), "tests", "jar",
                    ad.path());
            extension = "jar";
        }

        if ("pom".equals(extension)) {
            File f = reactorPomMap.get(ad.groupId() + ":" + ad.artifactId());
            if (f != null)
                return f;
        }

        for (File reactor : reactorPaths) {
            File f = new File(reactor,
                    ad.artifactId() + "-" + ad.version() + "." + (extension == null ? "jar" : extension));
            if (f.exists())
                return f;
            f = new File(reactor, ad.artifactId() + "/target/" + ad.artifactId() + "-" + ad.version() + "."
                    + (extension == null ? "jar" : extension));
            if (f.exists())
                return f;
        }

        File file = getArtifactFile(ad, extension);
        if (!file.exists())
            downloadFromRepos(ad, extension, file);
        return file;
    }

    private File getArtifactFile(ArtifactDescriptor ad, String extension) {
        String path = ad.groupId().replace('.', File.separatorChar) + File.separator + ad.artifactId() + File.separator
                + ad.version() + File.separator + ad.artifactId() + "-" + ad.version()
                + (ad.classifier() != null && !ad.classifier().isEmpty() ? "-" + ad.classifier() : "") + "."
                + (extension == null ? "jar" : extension);
        return new File(localRepo, path);
    }

    private void downloadFromRepos(ArtifactDescriptor ad, String extension, File target) throws IOException {
        String relPath = ad.groupId().replace('.', '/') + "/" + ad.artifactId() + "/" + ad.version() + "/"
                + ad.artifactId() + "-" + ad.version()
                + (ad.classifier() != null && !ad.classifier().isEmpty() ? "-" + ad.classifier() : "") + "."
                + (extension == null ? "jar" : extension);
        for (String repoUrl : repoUrls) {
            String urlStr = repoUrl + relPath;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    target.getParentFile().mkdirs();
                    try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(target)) {
                        is.transferTo(fos);
                    }
                    return;
                }
            } catch (Exception e) {
            }
        }
    }


    private void populateReactorMap() {
        if (reactorPaths.isEmpty() || !reactorPomMap.isEmpty())
            return;
        for (File path : reactorPaths) {
            scanForPoms(path);
        }
    }

    private void scanForPoms(File dir) {
        File pomFile = new File(dir, "pom.xml");
        if (pomFile.exists()) {
            try {
                PomModel pom = PomModel.parse(pomFile);
                String gid = pom.getGroupId();
                String aid = pom.getArtifactId();
                if (gid == null || gid.contains("${")) {
                    // if groupId is missing or property, we might need a better parser or just skip
                    // but PomModel.parse usually handles simple cases.
                }
                if (gid != null && aid != null) {
                    File abs = pomFile.getAbsoluteFile();
                    reactorPomMap.put(gid + ":" + aid, abs);
                    if (debugFilter != null)
                        System.err.println("MIMIC: [REACTOR-ADD] " + gid + ":" + aid + " -> " + abs);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".") && !"target".equals(f.getName())) {
                    scanForPoms(f);
                }
            }
        }
    }
}
