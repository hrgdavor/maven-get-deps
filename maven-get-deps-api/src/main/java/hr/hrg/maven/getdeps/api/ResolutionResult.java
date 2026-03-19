package hr.hrg.maven.getdeps.api;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Result of a dependency resolution process.
 */
public record ResolutionResult(
    List<ArtifactDescriptor> dependencies,
    Map<ArtifactDescriptor, File> artifactFiles,
    List<String> errors
) {
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
