package hr.hrg.maven.getdeps.api;

import java.util.Set;

public class CachedDependency {
    public final String groupId;
    public final String artifactId;
    public final String version;
    public final String scope;
    public final String classifier;
    public final String type;
    public final boolean isOptional;
    public final Set<String> exclusions;

    public CachedDependency(String groupId, String artifactId, String version, String scope, String classifier, String type, boolean isOptional, Set<String> exclusions) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.scope = scope;
        this.classifier = classifier;
        this.type = type;
        this.isOptional = isOptional;
        this.exclusions = exclusions;
    }

    public ArtifactDescriptor toArtifactDescriptor() {
        return new ArtifactDescriptor(groupId, artifactId, version, scope, classifier, type);
    }
}
