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
        List<String> errors = new ArrayList<>();
        List<ArtifactDescriptor> dependencies = new ArrayList<>();
        Map<ArtifactDescriptor, File> artifactFiles = new HashMap<>();

        try {
            CollectRequest collectRequest = new CollectRequest();
            for (ArtifactDescriptor ad : artifacts) {
                collectRequest.addDependency(new Dependency(toAetherArtifact(ad), ad.scope()));
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
            List<ArtifactDescriptor> rootDeps = model.getDependencies().stream()
                .map(d -> new ArtifactDescriptor(
                    d.getGroupId(),
                    d.getArtifactId(),
                    d.getVersion(),
                    d.getScope(),
                    d.getClassifier(),
                    d.getType()))
                .collect(Collectors.toList());
            return resolve(rootDeps);
        } catch (Exception e) {
            return new ResolutionResult(Collections.emptyList(), Collections.emptyMap(), List.of(e.getMessage()));
        }
    }

    private Artifact toAetherArtifact(ArtifactDescriptor ad) {
        String type = ad.type() == null ? "jar" : ad.type();
        return new DefaultArtifact(ad.groupId(), ad.artifactId(), ad.classifier(), type, ad.version());
    }

    private ArtifactDescriptor fromAetherArtifact(Artifact a) {
        return new ArtifactDescriptor(a.getGroupId(), a.getArtifactId(), a.getVersion(), null, a.getClassifier(), a.getExtension());
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
        private final Map<String, File> artifactIdToPom = new HashMap<>();
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
                    // Find actual artifactId by ignoring the one in <parent>
                    String aid = null;
                    int parentEnd = content.indexOf("</parent>");
                    int start = content.indexOf("<artifactId>", Math.max(0, parentEnd));
                    if (start != -1) {
                        int end = content.indexOf("</artifactId>", start);
                        if (end != -1) {
                            aid = content.substring(start + 12, end).trim();
                        }
                    }
                    if (aid != null) {
                        artifactIdToPom.put(aid, pom);
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
            
            if ("pom".equals(artifact.getExtension())) {
                File pom = artifactIdToPom.get(artifact.getArtifactId());
                if (pom != null) return pom;
            } else {
                File pom = artifactIdToPom.get(artifact.getArtifactId());
                if (pom != null) {
                    File jar = new File(pom.getParentFile(), "target" + File.separator + fileName);
                    if (jar.exists()) return jar;
                }
            }
            return null;
        }

        @Override
        public List<String> findVersions(Artifact artifact) {
            if (artifactIdToPom.containsKey(artifact.getArtifactId())) return Collections.singletonList(artifact.getVersion());
            return Collections.emptyList();
        }
    }
}
