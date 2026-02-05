package hr.hrg.maven.getdeps;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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

    @Parameter(property = "destDir", required = false)
    private File destDir;

    @Parameter(property = "outputFile")
    private File outputFile;

    @Parameter(property = "scopes", defaultValue = "compile,runtime")
    private String scopes;

    @Parameter(property = "reportFile")
    private File reportFile;

    @Parameter(property = "copyJars", defaultValue = "false")
    private boolean copyJars;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            boolean performCopy = copyJars && destDir != null;
            File sourceRepoBase = repoSession.getLocalRepository().getBasedir();

            if (performCopy && !destDir.exists()) {
                destDir.mkdirs();
            }

            // If we are copying, we use a copy of the session pointing to destDir as the
            // "local repo"
            // Otherwise, we use the standard session (source)
            RepositorySystemSession session = repoSession;
            if (performCopy) {
                DefaultRepositorySystemSession newSession = new DefaultRepositorySystemSession(repoSession);
                newSession.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(newSession,
                        new LocalRepository(destDir.getAbsolutePath())));
                session = newSession;
            }

            // When copying, we add the original local repository as a "remote" cache source
            List<RemoteRepository> effectiveRepos = new ArrayList<>(remoteRepos);
            if (performCopy) {
                effectiveRepos.add(0,
                        new RemoteRepository.Builder("local-cache", "default", sourceRepoBase.toURI().toString())
                                .build());
            }

            Set<String> scopeSet = Arrays.stream(scopes.split(","))
                    .map(String::trim)
                    .collect(Collectors.toSet());

            DependencyResolverService.ResolutionResult result = DependencyResolverService.resolve(
                    repoSystem,
                    session,
                    effectiveRepos,
                    project.getDependencies(),
                    this::resolveProperty,
                    project.getModel(),
                    scopeSet);

            if (outputFile != null) {
                try (PrintWriter writer = new PrintWriter(outputFile)) {
                    for (String path : result.relativePaths) {
                        writer.println(path);
                    }
                }
                getLog().info("Output written to: " + outputFile.getAbsolutePath());
            }

            if (reportFile != null) {
                DependencyResolverService.ReportResult report = DependencyResolverService.resolveReport(
                        repoSystem, session, effectiveRepos, project.getDependencies(), this::resolveProperty,
                        project.getModel(), scopeSet);

                try (PrintWriter writer = new PrintWriter(reportFile)) {
                    writer.print(report.formatMarkdownTable());
                }
                getLog().info("Report written to: " + reportFile.getAbsolutePath());
            }

            getLog().info("Total size: " + result.totalSize + " bytes");

        } catch (Exception e) {
            throw new MojoExecutionException("Error resolving dependencies", e);
        }
    }

    private String resolveProperty(String value, org.apache.maven.model.Model model) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String propertyName = value.substring(2, value.length() - 1);
            Object propertyValue = project.getProperties().get(propertyName);
            if (propertyValue != null) {
                return propertyValue.toString();
            }
        }
        return value;
    }
}
