package hr.hrg.maven.getdeps;

import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.dependency.Vulnerability;
import org.owasp.dependencycheck.dependency.naming.GenericIdentifier;
import org.owasp.dependencycheck.dependency.naming.PurlIdentifier;
import org.owasp.dependencycheck.dependency.naming.Identifier;
import org.owasp.dependencycheck.exception.ExceptionCollection;
import org.owasp.dependencycheck.utils.Settings;

import java.io.File;
import java.util.Map;
import java.util.Set;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;

/**
 * Service that queries a pre-populated OWASP Dependency-Check H2 database
 * for CVE information about Maven artifacts.
 *
 * <p>
 * The database must already exist and be up-to-date. This service never
 * triggers an auto-update or any network traffic.
 * </p>
 */
public class CveReportService {

    static {
        // Silence OWASP Dependency-Check console noise by defaulting to WARN level
        if (System.getProperty("org.slf4j.simpleLogger.log.org.owasp") == null) {
            System.setProperty("org.slf4j.simpleLogger.log.org.owasp", "warn");
        }
    }

    /** Represents the CVE scan results for a single artifact. */
    public static class ArtifactCveResult {
        public final String coordinate; // G:A:V
        public final List<CveInfo> vulnerabilities;
        public final boolean hasVulnerabilities;
        public String nearestCleanVersion;

        public ArtifactCveResult(String coordinate, List<CveInfo> vulnerabilities) {
            this.coordinate = coordinate;
            this.vulnerabilities = vulnerabilities;
            this.hasVulnerabilities = !vulnerabilities.isEmpty();
        }

        public boolean hasVulnerabilitiesAbove(float threshold) {
            return StreamUtil.any(vulnerabilities, v -> v.cvssScore >= threshold);
        }
    }

    /** Simple wrapper for CVE ID and its link. */
    public static class CveInfo {
        public final String id;
        public final String url;
        public final float cvssScore;

        public CveInfo(String id, String url, float cvssScore) {
            this.id = id;
            this.url = url;
            this.cvssScore = cvssScore;
        }

        @Override
        public String toString() {
            String label = id;
            if (cvssScore > 0) {
                label += " (CVSS: " + cvssScore + ")";
            }
            if (url != null && !url.isBlank()) {
                return "[" + label + "](" + url + ")";
            }
            return label;
        }
    }

    /**
     * Represents the full CVE report for one direct dependency and all its
     * transitives.
     */
    public static class DirectDepReport {
        public final String directCoordinate;
        public final List<ArtifactCveResult> allResults; // direct + transitives

        public DirectDepReport(String directCoordinate, List<ArtifactCveResult> allResults) {
            this.directCoordinate = directCoordinate;
            this.allResults = allResults;
        }

        public boolean anyVulnerable() {
            return StreamUtil.any(allResults, r -> r.hasVulnerabilities);
        }

        public long transitiveVulnCount() {
            return StreamUtil.count(allResults, r -> !r.coordinate.equals(directCoordinate) && r.hasVulnerabilities);
        }
    }

    /** Top-level result returned from {@link #scan}. */
    public static class CveReportResult {
        public final List<DirectDepReport> directReports;

        public CveReportResult(List<DirectDepReport> directReports) {
            this.directReports = directReports;
        }

        public boolean hasAnyVulnerabilitiesAbove(float threshold) {
            return StreamUtil.any(directReports, dr -> StreamUtil.any(dr.allResults, r -> r.hasVulnerabilitiesAbove(threshold)));
        }

        /**
         * Renders a two-section markdown report:
         * 1. Summary table — one row per direct dependency
         * 2. Detailed sections — one ### block per direct dep with transitive table
         */
        public String formatMarkdownReport() {
            StringBuilder sb = new StringBuilder();

            // ── Section 1: Summary ──────────────────────────────────────────
            sb.append("## CVE Report — Summary\n\n");
            sb.append("| Direct Dependency | Status | Transitive Issues |\n");
            sb.append("|---|:---:|:---:|\n");
            for (DirectDepReport rep : directReports) {
                String status = rep.allResults.isEmpty() ? "❓ UNKNOWN"
                        : (rep.allResults.get(0).hasVulnerabilities ? "⚠ CVE" : "✅ CLEAN");
                long transitiveVuln = rep.transitiveVulnCount();
                String transitiveStr = transitiveVuln == 0 ? "—" : (transitiveVuln + " with CVEs");
                sb.append(String.format("| `%s` | %s | %s |\n",
                        rep.directCoordinate, status, transitiveStr));
            }

            // ── Section 2: Details ──────────────────────────────────────────
            sb.append("\n## CVE Report — Details\n");
            for (DirectDepReport rep : directReports) {
                if (rep.anyVulnerable()) {
                    sb.append("| Artifact | Version | CVEs | Nearest Clean Version |\n");
                    sb.append("|---|---|---|---|\n");
                    for (ArtifactCveResult r : rep.allResults) {
                        if (r.hasVulnerabilities) {
                            List<String> links = new ArrayList<>();
                            for (CveInfo v : r.vulnerabilities) {
                                links.add(v.toString());
                            }

                            String cveStr = String.join(", ", links);
                            String[] parts = r.coordinate.split(":");
                            String artifact = parts.length >= 2 ? parts[0] + ":" + parts[1] : r.coordinate;
                            String version = parts.length >= 3 ? parts[2] : "?";
                            String cleanVer = r.nearestCleanVersion != null ? "`" + r.nearestCleanVersion + "`" : "—";
                            sb.append(String.format("| `%s` | %s | %s | %s |\n", artifact, version, cveStr, cleanVer));
                        }
                    }
                }
            }

            return sb.toString();
        }
    }

    /**
     * Runs an offline CVE scan against a pre-populated local H2 database.
     *
     * @param dataDirectory             path to the directory containing the H2
     *                                  database
     * @param directDepsWithTransitives map from direct dependency G:A:V to the list
     *                                  of
     *                                  all artifacts in its closure (direct first)
     * @return structured CVE report result
     */
    public static CveReportResult scan(
            String dataDirectory,
            Map<String, List<String>> directDepsWithTransitives) throws Exception {
        return scan(dataDirectory, directDepsWithTransitives, false);
    }

    public static CveReportResult scan(
            String dataDirectory,
            Map<String, List<String>> directDepsWithTransitives,
            boolean checkCleanVersions) throws Exception {

        Settings settings = new Settings();
        settings.setString(Settings.KEYS.DATA_DIRECTORY, dataDirectory);
        settings.setBoolean(Settings.KEYS.AUTO_UPDATE, false);

        // Essential analyzers for coordinate-based CVE lookup
        settings.setBoolean(Settings.KEYS.ANALYZER_CENTRAL_ENABLED, true);
        settings.setBoolean(Settings.KEYS.ANALYZER_NVD_CVE_ENABLED, true);
        settings.setBoolean(Settings.KEYS.ANALYZER_CPE_ENABLED, true);
        settings.setBoolean(Settings.KEYS.ANALYZER_HINT_ENABLED, true);

        // Disable heavy/irrelevant analyzers
        settings.setBoolean(Settings.KEYS.ANALYZER_NEXUS_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_OSSINDEX_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_ARCHIVE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_JAR_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_ASSEMBLY_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_NODE_PACKAGE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_YARN_AUDIT_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_PNPM_AUDIT_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_COMPOSER_LOCK_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_PYTHON_DISTRIBUTION_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_PYTHON_PACKAGE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_GOLANG_DEP_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_GOLANG_MOD_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_NUSPEC_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_NUGETCONF_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_COCOAPODS_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_SWIFT_PACKAGE_MANAGER_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_AUTOCONF_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_CMAKE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_OPENSSL_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_RETIREJS_ENABLED, false);

        // Collect unique dependencies
        Set<String> allUniqueCoords = new HashSet<>();
        for (List<String> coords : directDepsWithTransitives.values()) {
            allUniqueCoords.addAll(coords);
        }

        Map<String, ArtifactCveResult> cveResultsByCoord = new HashMap<>();

        try (Engine engine = new Engine(settings)) {
            engine.openDatabase(false, false);

            for (String coord : allUniqueCoords) {
                String[] parts = coord.split(":");
                if (parts.length < 3)
                    continue;
                String groupId = parts[0];
                String artifactId = parts[1];
                String version = parts[2];

                // Virtual file to satisfy the engine, but we set explicit evidence
                Dependency dep = new Dependency(new File(artifactId + "-" + version + ".jar"), true);
                dep.setPackagePath(coord); // Map back key
                dep.setName(artifactId);
                dep.setVersion(version);
                // Identifier that reliably triggers lookup
                dep.addSoftwareIdentifier(new GenericIdentifier(
                        "pkg:maven/" + groupId + "/" + artifactId + "@" + version,
                        org.owasp.dependencycheck.dependency.Confidence.HIGHEST));
                engine.addDependency(dep);
            }

            try {
                engine.analyzeDependencies();
            } catch (ExceptionCollection ex) {
                // Ignore collection errors in fast mode
            }

            // Extract results and map them back by coordinate
            for (Dependency dep : engine.getDependencies()) {
                String coord = dep.getPackagePath();
                if (coord == null)
                    continue;

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

            if (checkCleanVersions) {
                findNearestCleanVersions(engine, cveResultsByCoord, dataDirectory);
            }
        }

        // Build report structure
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

    /**
     * For each vulnerable artifact, fetches available versions and finds the
     * nearest one that is CLEAN.
     */
    private static void findNearestCleanVersions(Engine engine, Map<String, ArtifactCveResult> results,
            String dataDirectory) {
        RepositorySystem system = Bootstrapper.newRepositorySystem();
        RepositorySystemSession session = Bootstrapper.newRepositorySystemSession(system,
                System.getProperty("user.home") + "/.m2/repository");
        List<RemoteRepository> repos = Bootstrapper.newRepositories(system, session);

        List<ArtifactCveResult> vulnerableOnes = StreamUtil.filter(results.values(), r -> r.hasVulnerabilities);

        if (vulnerableOnes.isEmpty())
            return;

        System.out.println("[CVE] Searching for clean versions for " + vulnerableOnes.size() + " artifacts...");

        for (ArtifactCveResult res : vulnerableOnes) {
            String[] parts = res.coordinate.split(":");
            if (parts.length < 3)
                continue;
            String g = parts[0];
            String a = parts[1];
            String currentV = parts[2];

            try {
                Artifact artifact = new DefaultArtifact(g, a, "jar", "[0,)");
                VersionRangeRequest rangeRequest = new VersionRangeRequest();
                rangeRequest.setArtifact(artifact);
                rangeRequest.setRepositories(repos);

                VersionRangeResult rangeResult = system.resolveVersionRange(session, rangeRequest);
                List<Version> versions = rangeResult.getVersions();

                // Sort versions: we want the nearest ones higher than current, then lower
                List<String> candidates = versions.stream()
                        .map(Version::toString)
                        .filter(v -> !v.equals(currentV))
                        .sorted((v1, v2) -> {
                            // Simple heuristic: higher versions first
                            return v2.compareTo(v1);
                        })
                        .limit(10) // Check up to 10 nearest versions
                        .collect(Collectors.toList());

                // Bulk scan these candidates
                for (String candV : candidates) {
                    Dependency candDep = new Dependency(new File(a + "-" + candV + ".jar"), true);
                    candDep.setName(a);
                    candDep.setVersion(candV);
                    candDep.addSoftwareIdentifier(new GenericIdentifier(
                            "pkg:maven/" + g + "/" + a + "@" + candV,
                            org.owasp.dependencycheck.dependency.Confidence.HIGHEST));

                    // Use a temporary mini-engine or just manually trigger analyzer on one dep?
                    // Engine.analyze(Dependency) is not public. We must use a full pass or a
                    // internal call.
                    // For simplicity and correctness, we'll use the existing engine to add one and
                    // analyze.
                    engine.addDependency(candDep);
                    try {
                        engine.analyzeDependencies();
                    } catch (ExceptionCollection e) {
                    }

                    boolean clean = candDep.getVulnerabilities().isEmpty();
                    if (clean) {
                        res.nearestCleanVersion = candV;
                        break; // Found one!
                    }
                }
            } catch (Exception e) {
                System.err.println("[CVE] Failed to resolve versions for " + res.coordinate + ": " + e.getMessage());
            }
        }
    }

    private static String coordFromDep(Dependency dep, List<String> allCoords) {
        for (String coord : allCoords) {
            String[] parts = coord.split(":");
            if (parts.length >= 3
                    && parts[1].equals(dep.getName())
                    && parts[2].equals(dep.getVersion())) {
                return coord;
            }
        }
        // Fall back to identifier string from PURL if present
        for (Identifier id : dep.getSoftwareIdentifiers()) {
            if (id instanceof PurlIdentifier purlId) {
                String value = purlId.getValue();
                if (value != null && value.startsWith("pkg:maven/")) {
                    // pkg:maven/group/artifact@version → group:artifact:version
                    String rest = value.substring("pkg:maven/".length());
                    int at = rest.lastIndexOf('@');
                    String version = at >= 0 ? rest.substring(at + 1) : "?";
                    String ga = at >= 0 ? rest.substring(0, at) : rest;
                    String[] gaParts = ga.split("/");
                    if (gaParts.length == 2) {
                        return gaParts[0] + "." + gaParts[1] + ":" + gaParts[1] + ":" + version;
                    }
                }
                return value;
            }
        }
        return dep.getName() + ":" + dep.getVersion();
    }

    /**
     * Downloads / updates the local OWASP Dependency-Check H2 database.
     * Suitable for scheduling as a cron job or Windows Task Scheduler entry.
     *
     * @param dataDirectory path to the directory where the H2 database is stored
     *                      (default: {@code ~/.m2/dependency-check-data})
     * @param nvdApiKey     optional NVD API key for higher rate limits; may be
     *                      {@code null}
     */
    public static void updateDatabase(String dataDirectory, String nvdApiKey) throws Exception {
        System.out.println("[CVE] Updating database in: " + dataDirectory);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(dataDirectory));

        Settings settings = new Settings();
        settings.setString(Settings.KEYS.DATA_DIRECTORY, dataDirectory);
        settings.setBoolean(Settings.KEYS.AUTO_UPDATE, true);
        if (nvdApiKey != null && !nvdApiKey.isBlank()) {
            settings.setString(Settings.KEYS.NVD_API_KEY, nvdApiKey);
        }
        // Disable all analyzers — we only want data download, not scanning
        settings.setBoolean(Settings.KEYS.ANALYZER_ARCHIVE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_JAR_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_ASSEMBLY_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_NODE_PACKAGE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_YARN_AUDIT_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_PNPM_AUDIT_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_COMPOSER_LOCK_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_PYTHON_DISTRIBUTION_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_PYTHON_PACKAGE_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_GOLANG_DEP_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_GOLANG_MOD_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_NUSPEC_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_NUGETCONF_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_NEXUS_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_OSSINDEX_ENABLED, false);
        settings.setBoolean(Settings.KEYS.ANALYZER_CENTRAL_ENABLED, false);

        try (Engine engine = new Engine(settings)) {
            engine.openDatabase(false, false);
            try {
                engine.doUpdates(true);
                System.out.println("[CVE] Database update complete.");
            } catch (org.owasp.dependencycheck.data.update.exception.UpdateException ex) {
                // Rate-limiting (HTTP 429) happens without an NVD API key.
                // The CISA feed still succeeds so the DB is partially useful.
                System.err.println("[CVE] Update partially failed: " + ex.getMessage());
                if (nvdApiKey == null || nvdApiKey.isBlank()) {
                    System.err.println("[CVE] TIP: The NVD API rate-limits unauthenticated requests.");
                    System.err
                            .println("[CVE]      Get a free key at https://nvd.nist.gov/developers/request-an-api-key");
                    System.err.println("[CVE]      Then retry with: --cve-update --nvd-api-key <YOUR_KEY>");
                }
                System.err.println("[CVE] Partial data was saved; CVE reports may be incomplete.");
            }
        }
    }
}
