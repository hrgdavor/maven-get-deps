package hr.hrg.maven.getdeps.api;

import hr.hrg.wyhash.Wyhash64;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class CacheManager {

    public static long calculateWyhash64(File file) {
        try {
            byte[] content = Files.readAllBytes(file.toPath());
            return Wyhash64.hash(0, content);
        } catch (IOException e) {
            return 0;
        }
    }

    public static File getCacheFile(File pomFile, List<String> scopes) {
        List<String> sortedScopes = new ArrayList<>(scopes);
        Collections.sort(sortedScopes);
        String scopeKey = String.join(",", sortedScopes);
        if (scopeKey.isEmpty())
            scopeKey = "all";
        return new File(pomFile.getParentFile(), pomFile.getName() + "." + scopeKey + ".get-deps.v2.cache");
    }

    public static File getArtifactCacheFile(File localRepo, ArtifactDescriptor ad, List<String> scopes) {
        File artifactDir = new File(localRepo, ad.groupId().replace('.', '/') + "/" + ad.artifactId() + "/" + ad.version());
        List<String> sortedScopes = new ArrayList<>(scopes);
        Collections.sort(sortedScopes);
        String scopeKey = String.join(",", sortedScopes);
        if (scopeKey.isEmpty())
            scopeKey = "all";
        return new File(artifactDir, ad.artifactId() + "-" + ad.version() + "." + scopeKey + ".get-deps.v2.cache");
    }

    public static List<CachedDependency> loadCache(File cacheFile, long currentHash) {
        if (!cacheFile.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("# pomHash=")) {
                long storedHash = Long.parseLong(firstLine.substring(10));
                if (currentHash != -1 && storedHash != currentHash) {
                    return null;
                }
            } else if (currentHash != -1) {
                return null;
            }

            List<CachedDependency> dependencies = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line == null || line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(":", -1);
                if (p.length >= 8) {
                    Set<String> exclusions = new HashSet<>();
                    if (!p[7].isEmpty()) {
                        for (String ex : p[7].split(",")) {
                            exclusions.add(ex);
                        }
                    }
                    dependencies.add(new CachedDependency(p[0], p[1], p[2], p[5], p[3], p[4], "true".equals(p[6]), exclusions));
                }
            }
            return dependencies;
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveCache(File cacheFile, List<CachedDependency> dependencies, long pomHash) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(cacheFile))) {
            if (pomHash != -1) {
                writer.println("# pomHash=" + pomHash);
            }
            for (CachedDependency cd : dependencies) {
                writer.println(cd.groupId + ":" + cd.artifactId + ":" + cd.version + ":" +
                        (cd.classifier != null ? cd.classifier : "") + ":" +
                        (cd.type != null ? cd.type : "jar") + ":" +
                        (cd.scope != null ? cd.scope : "compile") + ":" +
                        cd.isOptional + ":" +
                        String.join(",", cd.exclusions));
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not save cache: " + e.getMessage());
        }
    }
}
