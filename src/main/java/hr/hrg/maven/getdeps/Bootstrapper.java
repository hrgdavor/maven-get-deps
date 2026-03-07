package hr.hrg.maven.getdeps;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Bootstrapper {

    public static RepositorySystem newRepositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    public static RepositorySystemSession newRepositorySystemSession(RepositorySystem system, String localRepoPath) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        LocalRepository localRepo = new LocalRepository(localRepoPath);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

        return session;
    }

    public static class LoggingRepositoryListener extends org.eclipse.aether.AbstractRepositoryListener {
        @Override
        public void artifactDownloaded(org.eclipse.aether.RepositoryEvent event) {
            System.out.println("Downloaded: " + event.getArtifact() + " from " + event.getRepository());
        }

        @Override
        public void metadataDownloaded(org.eclipse.aether.RepositoryEvent event) {
            System.out.println("Downloaded metadata: " + event.getMetadata() + " from " + event.getRepository());
        }
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

        public AetherModelResolver(RepositorySystem system, RepositorySystemSession session,
                List<RemoteRepository> repositories) {
            this.system = system;
            this.session = session;
            this.repositories = repositories;
        }

        @Override
        public org.apache.maven.model.building.ModelSource resolveModel(String groupId, String artifactId,
                String version)
                throws org.apache.maven.model.resolution.UnresolvableModelException {
            org.eclipse.aether.artifact.Artifact pomArtifact = new org.eclipse.aether.artifact.DefaultArtifact(groupId,
                    artifactId, "", "pom", version);
            try {
                org.eclipse.aether.resolution.ArtifactRequest request = new org.eclipse.aether.resolution.ArtifactRequest(
                        pomArtifact, repositories, null);
                org.eclipse.aether.resolution.ArtifactResult result = system.resolveArtifact(session, request);
                return new org.apache.maven.model.building.FileModelSource(result.getArtifact().getFile());
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
            // Not implemented for this tool
        }

        @Override
        public void addRepository(org.apache.maven.model.Repository repository, boolean replace)
                throws org.apache.maven.model.resolution.InvalidRepositoryException {
            // Not implemented for this tool
        }

        @Override
        public org.apache.maven.model.resolution.ModelResolver newCopy() {
            return new AetherModelResolver(system, session, repositories);
        }
    }

    public static String resolveProperty(String value, org.apache.maven.model.Model model) {
        if (value == null || !value.contains("${"))
            return value;
        if ("${project.version}".equals(value) || "${version}".equals(value))
            return model.getVersion();
        if ("${project.groupId}".equals(value) || "${groupId}".equals(value))
            return model.getGroupId();
        if ("${project.artifactId}".equals(value) || "${artifactId}".equals(value))
            return model.getArtifactId();

        // Simple property lookup
        if (value.startsWith("${") && value.endsWith("}")) {
            String propName = value.substring(2, value.length() - 1);
            String propValue = model.getProperties().getProperty(propName);
            if (propValue != null)
                return propValue;
        }

        return value;
    }

    public static List<RemoteRepository> newRepositories(RepositorySystem system, RepositorySystemSession session) {
        return newRepositories(system, session, null);
    }

    public static List<RemoteRepository> newRepositories(RepositorySystem system, RepositorySystemSession session,
            String cachePath) {
        List<RemoteRepository> repose = new ArrayList<>();
        repose.add(new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build());
        return repose;
    }

    public static List<RemoteRepository> convertRepositories(List<org.apache.maven.model.Repository> repositories) {
        List<RemoteRepository> result = new ArrayList<>();
        for (org.apache.maven.model.Repository repo : repositories) {
            result.add(new RemoteRepository.Builder(repo.getId(), repo.getLayout(), repo.getUrl()).build());
        }
        return result;
    }
}
