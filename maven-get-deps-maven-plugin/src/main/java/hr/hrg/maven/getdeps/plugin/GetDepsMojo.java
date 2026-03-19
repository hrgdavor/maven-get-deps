package hr.hrg.maven.getdeps.plugin;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.ResolutionResult;
import hr.hrg.maven.getdeps.maven.MavenDependencyResolver;
import hr.hrg.maven.getdeps.maven.MavenBootstrapper;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

@Mojo(name = "get-deps", defaultPhase = LifecyclePhase.PACKAGE)
public class GetDepsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepos;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            MavenDependencyResolver resolver = new MavenDependencyResolver(repoSystem, repoSession, remoteRepos);
            
            List<ArtifactDescriptor> rootDeps = project.getDependencies().stream()
                .map(d -> new ArtifactDescriptor(d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getScope(), d.getClassifier(), d.getType()))
                .collect(Collectors.toList());

            ResolutionResult result = resolver.resolve(rootDeps);

            if (result.hasErrors()) {
                result.errors().forEach(getLog()::error);
            }

            getLog().info("Resolved " + result.dependencies().size() + " dependencies");
            for (ArtifactDescriptor dep : result.dependencies()) {
                File file = result.artifactFiles().get(dep);
                getLog().info(dep + " -> " + (file != null ? file.getName() : "MISSING"));
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Error resolving dependencies", e);
        }
    }
}
