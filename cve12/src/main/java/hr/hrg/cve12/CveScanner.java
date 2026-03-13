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
            String nvdApiDelay) throws Exception {

        Settings settings = new Settings();
        settings.setString(Settings.KEYS.DATA_DIRECTORY, dataDirectory);
        settings.setBoolean(Settings.KEYS.AUTO_UPDATE, false);

        if (nvdApiDelay != null && !nvdApiDelay.isBlank()) {
            settings.setString(Settings.KEYS.NVD_API_DELAY, nvdApiDelay);
        }

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

    public static void updateDatabase(String dataDirectory, String nvdApiKey, String nvdApiDelay) throws Exception {
        System.out.println("[CVE] Updating database in: " + dataDirectory);
        Files.createDirectories(Path.of(dataDirectory));

        Settings settings = new Settings();
        settings.setString(Settings.KEYS.DATA_DIRECTORY, dataDirectory);
        settings.setBoolean(Settings.KEYS.AUTO_UPDATE, true);
        if (nvdApiKey != null && !nvdApiKey.isBlank()) {
            settings.setString(Settings.KEYS.NVD_API_KEY, nvdApiKey);
        }
        if (nvdApiDelay != null && !nvdApiDelay.isBlank()) {
            settings.setString(Settings.KEYS.NVD_API_DELAY, nvdApiDelay);
        }
        
        // Disable analyzers for download-only
        settings.setBoolean(Settings.KEYS.ANALYZER_ARCHIVE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_JAR_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_CENTRAL_ENABLED, false);

        try (Engine engine = new Engine(settings)) {
            engine.openDatabase(false, false);
            engine.doUpdates(true);
            System.out.println("[CVE] Database update complete.");
        }
    }
}
