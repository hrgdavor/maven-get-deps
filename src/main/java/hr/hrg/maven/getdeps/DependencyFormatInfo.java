package hr.hrg.maven.getdeps;

public record DependencyFormatInfo(
        String groupId,
        String artifactId,
        String version,
        String classifier,
        String extension,
        String localPath) {
    public static DependencyFormatInfo local(String path) {
        return new DependencyFormatInfo(null, null, null, null, null, path);
    }

    public static DependencyFormatInfo remote(String groupId, String artifactId, String version, String classifier,
            String extension) {
        String ext = (extension == null || extension.isBlank()) ? "jar" : extension;
        return new DependencyFormatInfo(groupId, artifactId, version, classifier, ext, null);
    }

    public boolean isLocal() {
        return localPath != null;
    }
}
