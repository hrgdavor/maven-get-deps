package hr.hrg.maven.getdeps;

public class FormatConverter {

    /**
     * Parses a string into a DependencyFormatInfo.
     * Handles local paths (starting with ./ or .\\), colon format, and relative
     * path format.
     */
    public static DependencyFormatInfo parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        line = line.trim();

        if (line.startsWith("./") || line.startsWith(".\\")) {
            return DependencyFormatInfo.local(line);
        }

        if (line.contains(":")) {
            return parseColonFormat(line);
        } else {
            return parsePathFormat(line);
        }
    }

    private static DependencyFormatInfo parseColonFormat(String line) {
        // groupId:artifactId:version[:classifier][@extension]
        String extension = "jar";
        int atIndex = line.indexOf('@');
        if (atIndex != -1) {
            extension = line.substring(atIndex + 1);
            line = line.substring(0, atIndex);
        }

        String[] parts = line.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid colon format: " + line);
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : null;

        return DependencyFormatInfo.remote(groupId, artifactId, version, classifier, extension);
    }

    private static DependencyFormatInfo parsePathFormat(String line) {
        // group/id/artifactId/version/artifactId-version[-classifier].ext
        line = line.replace('\\', '/');

        int lastSlash = line.lastIndexOf('/');
        if (lastSlash == -1) {
            throw new IllegalArgumentException("Invalid path format (no slash): " + line);
        }

        String dirPath = line.substring(0, lastSlash);
        String fileName = line.substring(lastSlash + 1);

        int extDot = fileName.lastIndexOf('.');
        String extension = "jar";
        String baseName = fileName;
        if (extDot != -1) {
            extension = fileName.substring(extDot + 1);
            baseName = fileName.substring(0, extDot);
        }

        // Parent dir is version
        int versionSlash = dirPath.lastIndexOf('/');
        if (versionSlash == -1) {
            throw new IllegalArgumentException("Invalid path format (missing version dir): " + line);
        }
        String version = dirPath.substring(versionSlash + 1);
        dirPath = dirPath.substring(0, versionSlash);

        // Parent dir is artifactId
        int artifactSlash = dirPath.lastIndexOf('/');
        if (artifactSlash == -1) {
            throw new IllegalArgumentException("Invalid path format (missing artifact dir): " + line);
        }
        String artifactId = dirPath.substring(artifactSlash + 1);
        String groupId = dirPath.substring(0, artifactSlash).replace('/', '.');

        // Extract classifier from baseName
        // baseName should be artifactId-version[-classifier]
        String expectedPrefix = artifactId + "-" + version;
        String classifier = null;
        if (baseName.startsWith(expectedPrefix + "-")) {
            classifier = baseName.substring(expectedPrefix.length() + 1);
        }

        return DependencyFormatInfo.remote(groupId, artifactId, version, classifier, extension);
    }

    public static String formatColon(DependencyFormatInfo info) {
        if (info.isLocal()) {
            return info.localPath();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(info.groupId()).append(":")
                .append(info.artifactId()).append(":")
                .append(info.version());

        if (info.classifier() != null && !info.classifier().isBlank()) {
            sb.append(":").append(info.classifier());
        }

        if (!"jar".equals(info.extension())) {
            sb.append("@").append(info.extension());
        }

        return sb.toString();
    }

    public static String formatPath(DependencyFormatInfo info) {
        if (info.isLocal()) {
            return info.localPath();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(info.groupId().replace('.', '/')).append("/")
                .append(info.artifactId()).append("/")
                .append(info.version()).append("/")
                .append(info.artifactId()).append("-").append(info.version());

        if (info.classifier() != null && !info.classifier().isBlank()) {
            sb.append("-").append(info.classifier());
        }

        sb.append(".").append(info.extension());

        return sb.toString();
    }
}
