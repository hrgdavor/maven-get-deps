package hr.hrg.cve12;

import hr.hrg.cve.*;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.dependency.Vulnerability;
import org.owasp.dependencycheck.dependency.naming.GenericIdentifier;
import org.owasp.dependencycheck.exception.ExceptionCollection;
import org.owasp.dependencycheck.utils.Settings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CveScanner {

    public static CveReportResult scan(
            String dataDirectory,
            Map<String, List<String>> directDepsWithTransitives,
            String nvdApiDelay,
            String kevUrl) throws Exception {

        Settings settings = createSettings(dataDirectory, nvdApiDelay, kevUrl);
        settings.setBoolean(Settings.KEYS.AUTO_UPDATE, false);

        // Essential analyzers
        settings.setBoolean(Settings.KEYS.ANALYZER_CENTRAL_ENABLED, true);
        settings.setBoolean(Settings.KEYS.ANALYZER_NVD_CVE_ENABLED, true);
        settings.setBoolean(Settings.KEYS.ANALYZER_CPE_ENABLED, true);
        settings.setBoolean(Settings.KEYS.ANALYZER_HINT_ENABLED, true);

        // Disable others
        settings.setBoolean(Settings.KEYS.ANALYZER_NEXUS_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_OSSINDEX_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_ARCHIVE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_JAR_ENABLED, false);

        Set<String> allUniqueCoords = new HashSet<>();
        for (List<String> coords : directDepsWithTransitives.values()) {
            allUniqueCoords.addAll(coords);
        }

        Map<String, ArtifactCveResult> cveResultsByCoord = new HashMap<>();

        try (Engine engine = new Engine(settings)) {
            engine.openDatabase(false, false);

            for (String coord : allUniqueCoords) {
                String[] parts = coord.split(":");
                if (parts.length < 3) continue;
                String groupId = parts[0];
                String artifactId = parts[1];
                String version = parts[2];

                Dependency dep = new Dependency(new File(artifactId + "-" + version + ".jar"), true);
                dep.setPackagePath(coord);
                dep.setName(artifactId);
                dep.setVersion(version);
                dep.addSoftwareIdentifier(new GenericIdentifier(
                        "pkg:maven/" + groupId + "/" + artifactId + "@" + version,
                        org.owasp.dependencycheck.dependency.Confidence.HIGHEST));
                engine.addDependency(dep);
            }

            try {
                engine.analyzeDependencies();
            } catch (ExceptionCollection ex) {
                // Ignore collection errors
            }

            for (Dependency dep : engine.getDependencies()) {
                String coord = dep.getPackagePath();
                if (coord == null) continue;

                List<CveInfo> vulns = StreamUtil.mapToSorted(dep.getVulnerabilities(),
                        v -> {
                            String name = v.getName();
                            String url = name.startsWith("CVE-") ? "https://nvd.nist.gov/vuln/detail/" + name : null;
                            float score = 0;
                            if (v.getCvssV3() != null && v.getCvssV3().getCvssData() != null) {
                                score = v.getCvssV3().getCvssData().getBaseScore().floatValue();
                            } else if (v.getCvssV2() != null && v.getCvssV2().getCvssData() != null) {
                                score = v.getCvssV2().getCvssData().getBaseScore().floatValue();
                            }
                            return new CveInfo(name, url, score);
                        },
                        (a, b) -> a.id.compareTo(b.id));

                cveResultsByCoord.put(coord, new ArtifactCveResult(coord, vulns));
            }
        }

        List<DirectDepReport> directReports = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : directDepsWithTransitives.entrySet()) {
            String directCoord = entry.getKey();
            List<ArtifactCveResult> artifactResults = new ArrayList<>();
            for (String coord : entry.getValue()) {
                ArtifactCveResult res = cveResultsByCoord.get(coord);
                artifactResults.add(res != null ? res : new ArtifactCveResult(coord, new ArrayList<>()));
            }
            directReports.add(new DirectDepReport(directCoord, artifactResults));
        }

        return new CveReportResult(directReports);
    }

    public static void updateDatabase(String dataDirectory, String nvdApiKey, String nvdApiDelay, String kevUrl) throws Exception {
        System.out.println("[CVE] Updating database in: " + dataDirectory);
        Files.createDirectories(Path.of(dataDirectory));

        Settings settings = createSettings(dataDirectory, nvdApiDelay, kevUrl);
        settings.setBoolean(Settings.KEYS.AUTO_UPDATE, true);
        if (nvdApiKey != null && !nvdApiKey.isBlank()) {
            settings.setString(Settings.KEYS.NVD_API_KEY, nvdApiKey);
        }
        
        // Disable analyzers for download-only
        settings.setBoolean(Settings.KEYS.ANALYZER_ARCHIVE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_JAR_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_CENTRAL_ENABLED, false);

        try (Engine engine = new Engine(settings)) {
            try {
                engine.openDatabase(false, false);
            } catch (Exception e) {
                if (e.getMessage().contains("Unable to create the database structure") || e.toString().contains("JdbcSQLSyntaxErrorException")) {
                    System.err.println("[CVE] ERROR: Database corruption or schema incompatible at: " + dataDirectory);
                    System.err.println("[CVE] Attempting automatic reset of the data directory...");
                    
                    // Close engine to release locks
                    engine.close();
                    
                    Path dirPath = Path.of(dataDirectory);
                    if (Files.exists(dirPath)) {
                        try (java.util.stream.Stream<Path> walk = Files.walk(dirPath)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        if (!p.equals(dirPath)) {
                                            System.err.println("[CVE] Deleting stale path: " + p.getFileName());
                                            Files.delete(p);
                                        }
                                    } catch (Exception ex) {
                                        System.err.println("[CVE] FAILED to delete " + p + ": " + ex.getMessage());
                                    }
                                });
                        }
                    }
                    
                    // Retry once
                    System.err.println("[CVE] Retrying initialization after cleanup...");
                    engine.openDatabase(false, false);
                } else {
                    throw e;
                }
            }
            try {
                engine.doUpdates(true);
            } catch (Exception e) {
                System.err.println("[CVE] WARN: Database update had some failures: " + e.getMessage());
                // If it's just the CISA feed failing, the rest might be okay
            }
            System.out.println("[CVE] Database update complete (with potential warnings).");
        }
    }

    private static Settings createSettings(String dataDirectory, String nvdApiDelay, String kevUrl) {
        Settings settings = new Settings();
        settings.setString(Settings.KEYS.DATA_DIRECTORY, dataDirectory);
        
        // Disable CISA KEV by default because it often returns 403 Forbidden
        // unless a custom URL is provided
        if (kevUrl != null && !kevUrl.isBlank()) {
            settings.setString(Settings.KEYS.KEV_URL, kevUrl);
            settings.setString("data.kev.url", kevUrl);
            settings.setString("data.kev.source.url", kevUrl);
            settings.setString("cisa.kev.url", kevUrl);
            settings.setString("data.knownexploitedvulnerabilities.url", kevUrl);
            settings.setString("data.knownexploitedvulnerability.url", kevUrl);
            settings.setString("analyzer.knownexploitedvulnerability.url", kevUrl);
            settings.setBoolean("analyzer.knownexploitedvulnerability.enabled", true);
            System.out.println("[CVE] Using custom KEV URL: " + kevUrl);
        } else {
            settings.setBoolean("analyzer.knownexploitedvulnerability.enabled", false);
        }

        if (nvdApiDelay != null && !nvdApiDelay.isBlank()) {
            settings.setString(Settings.KEYS.NVD_API_DELAY, nvdApiDelay);
        }

        // FORCE clear and correct connection string to avoid conflicts from loaded properties
        // We use %s so ODC substitutes it with the dataDirectory
        settings.setString(Settings.KEYS.DB_CONNECTION_STRING, "jdbc:h2:file:%s/odc;AUTO_SERVER=TRUE;NON_KEYWORDS=KEY,VALUE;");
        settings.setString(Settings.KEYS.DB_DRIVER_NAME, "org.h2.Driver");

        // Ensure we load the properties (this will load from classpath)
        // If they already loaded, we just overrode the critical ones above.
        if (settings.getString(Settings.KEYS.DB_CONNECTION_STRING) == null) {
            System.err.println("[CVE] WARN: Settings failed to initialize correctly.");
        } else {
            System.out.println("[CVE] Settings initialized with data directory: " + dataDirectory);
        }
        
        // ensure the connection string uses the data directory
        String connStr = settings.getString(Settings.KEYS.DB_CONNECTION_STRING);
        if (connStr != null && connStr.contains("%s")) {
            connStr = String.format(connStr, dataDirectory);
            settings.setString(Settings.KEYS.DB_CONNECTION_STRING, connStr);
        }
        System.out.println("[CVE] Final Database Connection String: " + settings.getString(Settings.KEYS.DB_CONNECTION_STRING));

        return settings;
    }
}
