package hr.hrg.maven.getdeps.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for Maven dependency resolution.
 */
public interface DependencyResolver {

    /**
     * Resolves transitive dependencies for a list of artifacts.
     *
     * @param artifacts list of root artifacts to resolve
     * @return result containing all transitive dependencies
     */
    ResolutionResult resolve(List<ArtifactDescriptor> artifacts);

    /**
     * Resolves transitive dependencies for a POM file.
     *
     * @param pomPath path to the pom.xml file
     * @return result containing all transitive dependencies
     */
    ResolutionResult resolve(Path pomPath);
    
    /**
     * Resolves transitive dependencies for a POM file with additional scope filtering.
     *
     * @param pomPath path to the pom.xml file
     * @param scopes  list of scopes to include (e.g., "runtime", "compile")
     * @return result containing all transitive dependencies
     */
    ResolutionResult resolve(Path pomPath, List<String> scopes);
}
