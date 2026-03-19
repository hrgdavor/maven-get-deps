package hr.hrg.maven.getdeps.api;

import java.util.Objects;

/**
 * Represents a Maven artifact (GAVS).
 */
public record ArtifactDescriptor(
    String groupId,
    String artifactId,
    String version,
    String scope,
    String classifier,
    String type
) {
    public ArtifactDescriptor(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, "compile", null, "jar");
    }

    public ArtifactDescriptor(String groupId, String artifactId, String version, String scope) {
        this(groupId, artifactId, version, scope, null, "jar");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId).append(":").append(artifactId).append(":").append(type);
        if (classifier != null && !classifier.isEmpty()) {
            sb.append(":").append(classifier);
        }
        sb.append(":").append(version);
        if (scope != null && !scope.isEmpty()) {
            sb.append(":").append(scope);
        }
        return sb.toString();
    }

    public String toGAV() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
