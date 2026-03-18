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
import java.util.List;
import java.util.Set;

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

    @Parameter(property = "scopes", defaultValue = "runtime")
    private String scopes;

    @Parameter(property = "reportFile")
    private File reportFile;

    @Parameter(property = "copyJars", defaultValue = "false")
    private boolean copyJars;

    @Parameter(property = "classpath", defaultValue = "false")
    private boolean classpath;

    @Parameter(property = "cache")
    private String cache;

    @Parameter(property = "exclude-cp")
    private String excludeClasspath;

    @Parameter(property = "extra-cp")
    private File extraClasspath;

    @Parameter(property = "excludeSiblings", defaultValue = "false")
    private boolean excludeSiblings;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            boolean performCopy = copyJars && destDir != null;
            File defaultM2 = new File(System.getProperty("user.home"), ".m2/repository");
            File sourceRepoBase = (cache != null) ? new File(cache)
                    : (repoSession.getLocalRepository() != null && repoSession.getLocalRepository().getBasedir() != null
                            ? repoSession.getLocalRepository().getBasedir()
                            : defaultM2);
            String sourceRepoPath = sourceRepoBase.getAbsolutePath();

            if (performCopy && !destDir.exists()) {
                destDir.mkdirs();
            }

            // If we are copying, we use a copy of the session pointing to destDir as the
            // "local repo"
            // Otherwise, we use the standard session (source)
            DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(repoSession);
            if (performCopy) {
                session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session,
                        new LocalRepository(destDir.getAbsolutePath())));
            }

            String projectGroupId = project.getModel().getGroupId();
            if (projectGroupId == null && project.getModel().getParent() != null) {
                projectGroupId = project.getModel().getParent().getGroupId();
            }

            java.util.Set<String> reactorGAs = new java.util.HashSet<>();
            if (project.getCollectedProjects() != null) {
                for (MavenProject p : project.getCollectedProjects()) {
                    reactorGAs.add(p.getGroupId() + ":" + p.getArtifactId());
                }
            }
            reactorGAs.add(project.getGroupId() + ":" + project.getArtifactId());

            if (projectGroupId != null) {
                java.util.Set<String> allowedRepos = new java.util.HashSet<>();
                allowedRepos.add("local-cache");
                session.setRepositoryListener(new Bootstrapper.SiblingBlockerRepositoryListener(reactorGAs, allowedRepos));
            }

            // Always add the original local repository as a "remote" cache source
            // to ensure Aether's collectDependencies can find local POMs for siblings
            List<RemoteRepository> effectiveRepos = new ArrayList<>(remoteRepos);
            effectiveRepos.add(0,
                    new RemoteRepository.Builder("local-cache", "default", sourceRepoBase.toURI().toString())
                            .build());

            Set<String> scopeSet = StreamUtil.splitToSet(scopes, ",");

            // The projectGroupId is already defined and used above for WorkspaceReader.
            // This redeclaration is redundant and can be removed.
            // String projectGroupId = project.getModel().getGroupId();
            // if (projectGroupId == null && project.getModel().getParent() != null) {
            //     projectGroupId = project.getModel().getParent().getGroupId();
            // }

            Set<String> excludeSet = DependencyResolverService.normalizeExcludes(excludeClasspath);

            DependencyResolverService.ResolutionResult result = DependencyResolverService.resolve(
                    repoSystem,
                    session,
                    effectiveRepos,
                    project.getDependencies(),
                    this::resolveProperty,
                    project.getModel(),
                    scopeSet,
                    excludeSet,
                    projectGroupId,
                    reactorGAs,
                    excludeSiblings);

            if (outputFile != null) {
                try (PrintWriter writer = new PrintWriter(outputFile)) {
                    if (classpath) {
                        List<String> paths = StreamUtil.map(result.relativePaths, p -> new File(sourceRepoPath, p).getAbsolutePath());
                        if (extraClasspath != null && extraClasspath.exists()) {
                            paths.addAll(java.nio.file.Files.readAllLines(extraClasspath.toPath()));
                        }
                        writer.println(String.join(File.pathSeparator, paths));
                    } else {
                        for (String path : result.relativePaths) {
                            writer.println(path);
                        }
                        if (extraClasspath != null && extraClasspath.exists()) {
                            for (String extra : java.nio.file.Files.readAllLines(extraClasspath.toPath())) {
                                writer.println(extra);
                            }
                        }
                    }
                }
                getLog().info("Output written to: " + outputFile.getAbsolutePath());
            } else {
                if (classpath) {
                    List<String> paths = StreamUtil.map(result.relativePaths, p -> new File(sourceRepoPath, p).getAbsolutePath());
                    if (extraClasspath != null && extraClasspath.exists()) {
                        paths.addAll(java.nio.file.Files.readAllLines(extraClasspath.toPath()));
                    }
                    getLog().info(String.join(File.pathSeparator, paths));
                } else {
                    for (String path : result.relativePaths) {
                        getLog().info(path);
                    }
                    if (extraClasspath != null && extraClasspath.exists()) {
                        for (String extra : java.nio.file.Files.readAllLines(extraClasspath.toPath())) {
                            getLog().info(extra);
                        }
                    }
                }
            }

            if (reportFile != null) {
                DependencyResolverService.ReportResult report = DependencyResolverService.resolveReport(
                        repoSystem, session, effectiveRepos, project.getDependencies(), this::resolveProperty,
                        project.getModel(), scopeSet, excludeSet, projectGroupId, reactorGAs, excludeSiblings);

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
