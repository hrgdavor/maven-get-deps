package hr.hrg.maven.getdeps.maven;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.DependencyResolver;
import hr.hrg.maven.getdeps.api.ResolutionResult;
import org.apache.maven.model.Model;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class MavenDependencyResolver implements DependencyResolver {

    private final RepositorySystem system;
    private final String localRepoPath;
    private RepositorySystemSession session;
    private final List<RemoteRepository> repos;
    private final List<File> reactorPaths = new ArrayList<>();

    public MavenDependencyResolver(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repos) {
        this.system = system;
        this.session = session;
        this.repos = repos;
        this.localRepoPath = null;
    }

    public MavenDependencyResolver(String localRepoPath) {
        this.system = MavenBootstrapper.newRepositorySystem();
        this.localRepoPath = localRepoPath;
        this.session = MavenBootstrapper.newRepositorySystemSession(system, localRepoPath, false);
        this.repos = MavenBootstrapper.newRepositories();
    }

    public void addReactorPath(File path) {
        this.reactorPaths.add(path);
        // Refresh session with WorkspaceReader
        this.session = MavenBootstrapper.newRepositorySystemSession(system, localRepoPath, false, new ReactorWorkspaceReader(reactorPaths));
    }

    @Override
    public ResolutionResult resolve(List<ArtifactDescriptor> artifacts) {
        return resolve(artifacts, Collections.emptyList());
    }

    public ResolutionResult resolve(List<ArtifactDescriptor> artifacts, List<ArtifactDescriptor> managedDependencies) {
        List<String> errors = new ArrayList<>();
        List<ArtifactDescriptor> dependencies = new ArrayList<>();
        Map<ArtifactDescriptor, File> artifactFiles = new HashMap<>();

        try {
            CollectRequest collectRequest = new CollectRequest();
            for (ArtifactDescriptor ad : artifacts) {
                collectRequest.addDependency(new Dependency(toAetherArtifact(ad), ad.scope()));
            }
            
            if (managedDependencies != null && !managedDependencies.isEmpty()) {
                collectRequest.setManagedDependencies(managedDependencies.stream()
                        .map(ad -> new Dependency(toAetherArtifact(ad), ad.scope()))
                        .collect(Collectors.toList()));
            }
            
            collectRequest.setRepositories(repos);

            org.eclipse.aether.graph.DependencyFilter filter = DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME, JavaScopes.COMPILE);
            org.eclipse.aether.collection.CollectResult collectResult = system.collectDependencies(session, collectRequest);
            
            List<DependencyNode> nodes = new ArrayList<>();
            collectNodes(collectResult.getRoot(), new ArrayList<>(), filter, nodes, new HashSet<>());

            List<ArtifactRequest> requests = nodes.stream()
                .filter(n -> n.getArtifact() != null)
                .map(n -> new ArtifactRequest(n.getArtifact(), repos, null))
                .collect(Collectors.toList());

            List<ArtifactResult> results;
            try {
                results = system.resolveArtifacts(session, requests);
            } catch (ArtifactResolutionException e) {
                results = e.getResults();
            }

            for (ArtifactResult ar : results) {
                Artifact artifact = ar.getArtifact() != null ? ar.getArtifact() : ar.getRequest().getArtifact();
                if (artifact == null) continue;
                ArtifactDescriptor ad = fromAetherArtifact(artifact);
                dependencies.add(ad);
                if (ar.isResolved()) {
                    artifactFiles.put(ad, ar.getArtifact().getFile());
                } else if (!ar.getExceptions().isEmpty()){
                    errors.add("Failed to resolve " + ar.getRequest().getArtifact() + ": " + ar.getExceptions().get(0).getMessage());
                }
            }

        } catch (DependencyCollectionException e) {
            errors.add(e.getMessage());
        }

        return new ResolutionResult(dependencies, artifactFiles, errors);
    }

    @Override
    public ResolutionResult resolve(Path pomPath) {
        return resolve(pomPath, List.of(JavaScopes.RUNTIME, JavaScopes.COMPILE));
    }

    @Override
    public ResolutionResult resolve(Path pomPath, List<String> scopes) {
        try {
            Model model = MavenBootstrapper.resolveModel(pomPath.toFile(), system, session, repos);
            
            List<Dependency> rootDeps = model.getDependencies().stream()
                .map(this::toAetherDependency)
                .collect(Collectors.toList());
            
            List<Dependency> managedDeps = Collections.emptyList();
            if (model.getDependencyManagement() != null) {
                managedDeps = model.getDependencyManagement().getDependencies().stream()
                        .map(this::toAetherDependency)
                        .collect(Collectors.toList());
            }

            CollectRequest collectRequest = new CollectRequest();
            for (Dependency dep : rootDeps) {
                collectRequest.addDependency(dep);
            }
            collectRequest.setManagedDependencies(managedDeps);
            collectRequest.setRepositories(repos);

            org.eclipse.aether.graph.DependencyFilter filter = DependencyFilterUtils.classpathFilter(scopes);
            org.eclipse.aether.collection.CollectResult collectResult = system.collectDependencies(session, collectRequest);
            
            List<DependencyNode> nodes = new ArrayList<>();
            collectNodes(collectResult.getRoot(), new ArrayList<>(), filter, nodes, new HashSet<>());

            List<ArtifactRequest> requests = nodes.stream()
                .filter(n -> n.getArtifact() != null)
                .map(n -> new ArtifactRequest(n.getArtifact(), repos, null))
                .collect(Collectors.toList());

            List<ArtifactResult> results;
            try {
                results = system.resolveArtifacts(session, requests);
            } catch (ArtifactResolutionException e) {
                results = e.getResults();
            }

            List<ArtifactDescriptor> dependencies = new ArrayList<>();
            Map<ArtifactDescriptor, File> artifactFiles = new HashMap<>();
            List<String> errors = new ArrayList<>();

            Map<Artifact, File> resolvedFiles = new HashMap<>();
            for (ArtifactResult ar : results) {
                if (ar.isResolved()) {
                    resolvedFiles.put(ar.getArtifact(), ar.getArtifact().getFile());
                } else if (!ar.getExceptions().isEmpty()){
                    errors.add("Failed to resolve " + ar.getRequest().getArtifact() + ": " + ar.getExceptions().get(0).getMessage());
                }
            }

            for (DependencyNode node : nodes) {
                Artifact artifact = node.getArtifact();
                if (artifact == null) continue;
                
                String scope = (node.getDependency() != null) ? node.getDependency().getScope() : null;
                
                // Explicitly filter by requested scopes if provided
                if (scopes != null && !scopes.isEmpty() && scope != null) {
                    if (!scopes.contains(scope)) continue;
                }

                ArtifactDescriptor ad = fromAetherArtifact(artifact, scope);
                dependencies.add(ad);
                
                File file = resolvedFiles.get(artifact);
                if (file != null) {
                    artifactFiles.put(ad, file);
                }
            }

            return new ResolutionResult(dependencies, artifactFiles, errors);

        } catch (Exception e) {
            e.printStackTrace();
            return new ResolutionResult(Collections.emptyList(), Collections.emptyMap(), List.of(e.getMessage()));
        }
    }

    private Dependency toAetherDependency(org.apache.maven.model.Dependency d) {
        Artifact artifact = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType(), d.getVersion());
        List<Exclusion> exclusions = d.getExclusions().stream()
                .map(ex -> new Exclusion(ex.getGroupId(), ex.getArtifactId(), "*", "*"))
                .collect(Collectors.toList());
        return new Dependency(artifact, d.getScope(), d.isOptional(), exclusions);
    }

    private Artifact toAetherArtifact(ArtifactDescriptor ad) {
        String type = ad.type() == null ? "jar" : ad.type();
        return new DefaultArtifact(ad.groupId(), ad.artifactId(), ad.classifier(), type, ad.version());
    }

    private ArtifactDescriptor fromAetherArtifact(Artifact a) {
        return fromAetherArtifact(a, null);
    }

    private ArtifactDescriptor fromAetherArtifact(Artifact a, String scope) {
        return new ArtifactDescriptor(a.getGroupId(), a.getArtifactId(), a.getVersion(), scope, a.getClassifier(), a.getExtension());
    }

    private void collectNodes(DependencyNode node, List<DependencyNode> parents, org.eclipse.aether.graph.DependencyFilter filter, List<DependencyNode> nodes, Set<String> seen) {
        if (node.getDependency() != null && (filter == null || filter.accept(node, parents))) {
            Artifact a = node.getArtifact();
            if (a != null) {
                String key = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + ":" + a.getClassifier() + ":" + a.getExtension();
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

    private static class ReactorWorkspaceReader implements WorkspaceReader {
        private final Map<String, File> gavToPom = new HashMap<>();
        private final WorkspaceRepository repository = new WorkspaceRepository("reactor");

        ReactorWorkspaceReader(List<File> reactorPaths) {
            for (File reactor : reactorPaths) {
                scan(reactor);
            }
        }

        private void scan(File dir) {
            if (dir == null || !dir.isDirectory()) return;
            File pom = new File(dir, "pom.xml");
            if (pom.exists()) {
                try {
                    String content = Files.readString(pom.toPath());
                    // Find actual groupId and artifactId by ignoring the ones in <parent>
                    String gid = null;
                    String aid = null;
                    
                    int parentEnd = content.indexOf("</parent>");
                    int searchStart = Math.max(0, parentEnd);
                    
                    int gStart = content.indexOf("<groupId>", searchStart);
                    if (gStart != -1) {
                        int gEnd = content.indexOf("</groupId>", gStart);
                        if (gEnd != -1) gid = content.substring(gStart + 9, gEnd).trim();
                    }
                    
                    int aStart = content.indexOf("<artifactId>", searchStart);
                    if (aStart != -1) {
                        int aEnd = content.indexOf("</artifactId>", aStart);
                        if (aEnd != -1) aid = content.substring(aStart + 12, aEnd).trim();
                    }
                    
                    // If not found after </parent>, try to find if there is NO <parent>
                    if (gid == null && parentEnd == -1) {
                        gStart = content.indexOf("<groupId>");
                        if (gStart != -1) {
                            int gEnd = content.indexOf("</groupId>", gStart);
                            if (gEnd != -1) gid = content.substring(gStart + 9, gEnd).trim();
                        }
                    }
                    
                    if (gid != null && aid != null) {
                        gavToPom.put(gid + ":" + aid, pom);
                    } else if (aid != null) {
                        // Fallback to searching for parent's groupId if child doesn't have it
                        int pStart = content.indexOf("<parent>");
                        if (pStart != -1) {
                            int pgStart = content.indexOf("<groupId>", pStart);
                            int pgEnd = content.indexOf("</groupId>", pgStart);
                            if (pgStart != -1 && pgEnd != -1 && pgEnd < parentEnd) {
                                gid = content.substring(pgStart + 9, pgEnd).trim();
                                gavToPom.put(gid + ":" + aid, pom);
                            }
                        }
                    }
                } catch (Exception e) {}
                
                // Recurse into subdirectories to find module POMs
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("target")) {
                            scan(f);
                        }
                    }
                }
            }
        }

        @Override
        public WorkspaceRepository getRepository() {
            return repository;
        }

        @Override
        public File findArtifact(Artifact artifact) {
            String fileName = artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getExtension();
            String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
            
            if ("pom".equals(artifact.getExtension())) {
                File pom = gavToPom.get(key);
                if (pom != null) return pom;
            } else {
                File pom = gavToPom.get(key);
                if (pom != null) {
                    File jar = new File(pom.getParentFile(), "target" + File.separator + fileName);
                    if (jar.exists()) return jar;
                }
            }
            return null;
        }

        @Override
        public List<String> findVersions(Artifact artifact) {
            String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
            if (gavToPom.containsKey(key)) return Collections.singletonList(artifact.getVersion());
            return Collections.emptyList();
        }
    }
}
