package hr.hrg.maven.getdeps.maven;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorPolicy;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.apache.maven.model.building.ModelCache;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MavenBootstrapper {

    public static RepositorySystem newRepositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    public static RepositorySystemSession newRepositorySystemSession(RepositorySystem system, String localRepoPath, boolean offline) {
        return newRepositorySystemSession(system, localRepoPath, offline, null);
    }

    public static RepositorySystemSession newRepositorySystemSession(RepositorySystem system, String localRepoPath, boolean offline, org.eclipse.aether.repository.WorkspaceReader workspaceReader) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        LocalRepository localRepo = new LocalRepository(localRepoPath);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

        if (workspaceReader != null) {
            session.setWorkspaceReader(workspaceReader);
        }

        session.setSystemProperties(System.getProperties());
        session.setCache(new DefaultRepositoryCache());
        session.setIgnoreArtifactDescriptorRepositories(true);
        session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(ArtifactDescriptorPolicy.IGNORE_MISSING | ArtifactDescriptorPolicy.IGNORE_ERRORS));
        session.setOffline(offline);

        session.getData().set(ModelCache.class, new DefaultModelCache());

        return session;
    }

    private static class DefaultModelCache implements ModelCache {
        private final Map<Object, Object> cache = new HashMap<>();
        @Override
        public void put(String groupId, String artifactId, String version, String tag, Object data) {
            cache.put(groupId + ":" + artifactId + ":" + version + ":" + tag, data);
        }
        @Override
        public Object get(String groupId, String artifactId, String version, String tag) {
            return cache.get(groupId + ":" + artifactId + ":" + version + ":" + tag);
        }
    }

    public static List<RemoteRepository> newRepositories() {
        List<RemoteRepository> repose = new ArrayList<>();
        RepositoryPolicy policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        RepositoryPolicy snapshotPolicy = new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        repose.add(new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/")
                .setReleasePolicy(policy)
                .setSnapshotPolicy(snapshotPolicy)
                .build());
        return repose;
    }

    public static org.apache.maven.model.Model resolveModel(File pomFile, RepositorySystem system,
            RepositorySystemSession session, List<RemoteRepository> repos) {
        org.apache.maven.model.building.ModelBuildingRequest request = new org.apache.maven.model.building.DefaultModelBuildingRequest();
        request.setPomFile(pomFile);
        request.setProcessPlugins(false);
        request.setValidationLevel(org.apache.maven.model.building.ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setSystemProperties(System.getProperties());
        request.setModelResolver(new AetherModelResolver(system, session, repos));

        org.apache.maven.model.building.ModelBuilder builder = new org.apache.maven.model.building.DefaultModelBuilderFactory()
                .newInstance();
        try {
            return builder.build(request).getEffectiveModel();
        } catch (org.apache.maven.model.building.ModelBuildingException e) {
            throw new RuntimeException("Error building effective model for " + pomFile, e);
        }
    }

    private static class AetherModelResolver implements org.apache.maven.model.resolution.ModelResolver {
        private final RepositorySystem system;
        private final RepositorySystemSession session;
        private final List<RemoteRepository> repositories;
        private final Map<String, org.apache.maven.model.building.ModelSource> cache = new HashMap<>();

        public AetherModelResolver(RepositorySystem system, RepositorySystemSession session,
                List<RemoteRepository> repositories) {
            this.system = system;
            this.session = session;
            this.repositories = repositories;
        }

        private AetherModelResolver(AetherModelResolver copy) {
            this.system = copy.system;
            this.session = copy.session;
            this.repositories = copy.repositories;
        }

        @Override
        public org.apache.maven.model.building.ModelSource resolveModel(String groupId, String artifactId,
                String version)
                throws org.apache.maven.model.resolution.UnresolvableModelException {
            String key = groupId + ":" + artifactId + ":" + version;
            if (cache.containsKey(key)) return cache.get(key);

            org.eclipse.aether.artifact.Artifact pomArtifact = new org.eclipse.aether.artifact.DefaultArtifact(groupId,
                    artifactId, "", "pom", version);
            try {
                if (session.getWorkspaceReader() != null) {
                    File file = session.getWorkspaceReader().findArtifact(pomArtifact);
                    if (file != null) {
                        org.apache.maven.model.building.ModelSource source = new org.apache.maven.model.building.FileModelSource(file);
                        cache.put(key, source);
                        return source;
                    }
                }
                org.eclipse.aether.resolution.ArtifactRequest request = new org.eclipse.aether.resolution.ArtifactRequest(
                        pomArtifact, repositories, null);
                org.eclipse.aether.resolution.ArtifactResult result = system.resolveArtifact(session, request);
                org.apache.maven.model.building.ModelSource source = new org.apache.maven.model.building.FileModelSource(result.getArtifact().getFile());
                cache.put(key, source);
                return source;
            } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
                throw new org.apache.maven.model.resolution.UnresolvableModelException(e.getMessage(), groupId,
                        artifactId, version, e);
            }
        }

        @Override
        public org.apache.maven.model.building.ModelSource resolveModel(org.apache.maven.model.Parent parent)
                throws org.apache.maven.model.resolution.UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public org.apache.maven.model.building.ModelSource resolveModel(org.apache.maven.model.Dependency dependency)
                throws org.apache.maven.model.resolution.UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(org.apache.maven.model.Repository repository)
                throws org.apache.maven.model.resolution.InvalidRepositoryException {
        }

        @Override
        public void addRepository(org.apache.maven.model.Repository repository, boolean replace)
                throws org.apache.maven.model.resolution.InvalidRepositoryException {
        }

        @Override
        public org.apache.maven.model.resolution.ModelResolver newCopy() {
            return new AetherModelResolver(this);
        }
    }
}
