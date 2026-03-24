package hr.hrg.maven.getdeps.api;

import java.util.Objects;

/**
 * Represents a Maven artifact (GAVS).
 */
public class ArtifactDescriptor {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String scope;
    private final String classifier;
    private final String type;
    private final String path;
    private int depth = 1;

    public ArtifactDescriptor(String groupId, String artifactId, String version, String scope, String classifier, String type, String path) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.scope = scope;
        this.classifier = classifier;
        this.type = type;
        this.path = path;
    }

    public ArtifactDescriptor(String groupId, String artifactId, String version, String scope, String classifier, String type) {
        this(groupId, artifactId, version, scope, classifier, type, null);
    }
    public ArtifactDescriptor(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, "compile", null, "jar");
    }

    public ArtifactDescriptor(String groupId, String artifactId, String version, String scope) {
        this(groupId, artifactId, version, scope, null, "jar");
    }

    public String groupId() { return groupId; }
    public String artifactId() { return artifactId; }
    public String version() { return version; }
    public String scope() { return scope; }
    public String classifier() { return classifier; }
    public String type() { return type; }
    public String path() { return path; }
    public int depth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArtifactDescriptor)) return false;
        ArtifactDescriptor that = (ArtifactDescriptor) o;
        return Objects.equals(groupId, that.groupId) &&
               Objects.equals(artifactId, that.artifactId) &&
               Objects.equals(version, that.version) &&
               Objects.equals(classifier, that.classifier) &&
               Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, classifier, type);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId).append(":").append(artifactId).append(":").append(version);
        if (type != null && !"jar".equals(type)) {
            sb.append(":").append(type);
        }
        if (classifier != null && !classifier.isEmpty()) {
            sb.append(":").append(classifier);
        }
        if (scope != null && !"compile".equals(scope)) {
            sb.append(":").append(scope);
        }
        return sb.toString();
    }

    public String toGAV() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
