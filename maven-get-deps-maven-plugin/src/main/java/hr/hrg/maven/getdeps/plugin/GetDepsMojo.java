package hr.hrg.maven.getdeps.plugin;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.DependencySizeReport;
import hr.hrg.maven.getdeps.api.ResolutionResult;
import hr.hrg.maven.getdeps.maven.MavenDependencyResolver;
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
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mojo(name = "get-deps", defaultPhase = LifecyclePhase.PACKAGE)
public class GetDepsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    @Parameter(property = "outputFile")
    private File outputFile;

    @Parameter(property = "classpath", defaultValue = "false")
    private boolean classpath;

    @Parameter(property = "scopes", defaultValue = "compile,runtime")
    private String scopes;

    @Parameter(property = "destDir")
    private File destDir;

    @Parameter(property = "copyJars", defaultValue = "false")
    private boolean copyJars;

    @Parameter(property = "reportFile")
    private File reportFile;

    /** Comma-separated list of groupIds to exclude (e.g. {@code hr.hrg,com.example}). */
    @Parameter(property = "excludeGroupIds", defaultValue = "")
    private String excludeGroupIds;

    /** Comma-separated list of artifactIds to exclude. */
    @Parameter(property = "excludeArtifactIds", defaultValue = "")
    private String excludeArtifactIds;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            List<String> scopeList = Arrays.asList(scopes.split(","));

            Set<String> excludedGroups = excludeGroupIds == null || excludeGroupIds.isBlank()
                    ? Collections.emptySet()
                    : Arrays.stream(excludeGroupIds.split(",")).map(String::trim).collect(Collectors.toSet());
            Set<String> excludedArtifacts = excludeArtifactIds == null || excludeArtifactIds.isBlank()
                    ? Collections.emptySet()
                    : Arrays.stream(excludeArtifactIds.split(",")).map(String::trim).collect(Collectors.toSet());

            getLog().info("Resolving dependencies for scopes: " + scopes);
            MavenDependencyResolver resolver = new MavenDependencyResolver(repoSystem, repoSession, remoteRepos);
            ResolutionResult result = resolver.resolve(project.getFile().toPath(), scopeList);
            getLog().info("Resolved " + result.dependencies().size() + " dependencies");

            if (result.hasErrors()) {
                result.errors().forEach(e -> getLog().warn(e));
            }

            List<String> entries = new ArrayList<>();
            for (ArtifactDescriptor dep : result.dependencies()) {
                if (excludedGroups.contains(dep.groupId())) continue;
                if (excludedArtifacts.contains(dep.artifactId())) continue;

                File file = result.artifactFiles().get(dep);
                File effectiveFile = file;
                if (file != null) {
                    if (copyJars && destDir != null) {
                        if (!destDir.exists()) destDir.mkdirs();
                        File targetFile = new File(destDir, file.getName());
                        if (!targetFile.exists()) {
                            getLog().info("Copy file  " + targetFile.toPath());
                            Files.copy(file.toPath(), targetFile.toPath());
                        }
                        effectiveFile = targetFile;
                    }
                } else {
                    getLog().warn("Missing file for " + dep.toGAV());
                }

                if (classpath) {
                    if (effectiveFile != null) {
                        entries.add(effectiveFile.getAbsolutePath());
                    }
                } else {
                    entries.add(dep.groupId() + ":" + dep.artifactId() + ":" + dep.version());
                }
            }

            String separator = classpath ? File.pathSeparator : "\n";
            String output = entries.stream().collect(Collectors.joining(separator));

            if (outputFile != null) {
                getLog().info("Writing dependencies to " + outputFile.getAbsolutePath());
                if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
                try (FileWriter writer = new FileWriter(outputFile)) {
                    writer.write(output);
                    if (!classpath && !output.isEmpty()) writer.write("\n");
                }
            } else {
                getLog().info("Resolved Dependencies:\n" + output);
            }

            if (reportFile != null) {
                getLog().info("Writing dependency size report to " + reportFile.getAbsolutePath());
                if (reportFile.getParentFile() != null) reportFile.getParentFile().mkdirs();
                String report = DependencySizeReport.formatMarkdownReport(result);
                Files.writeString(reportFile.toPath(), report);
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Error resolving dependencies", e);
        }
    }
}
