package hr.hrg.maven.getdeps.mimic;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.ResolutionResult;
import hr.hrg.maven.getdeps.maven.MavenDependencyResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class ParityTest {

    private static final Path ROOT = resolveRepoRoot();
    private static final Path POM = ROOT.resolve("test/deps/complex1/core/pom.xml");
    private static final Path REACTOR = ROOT.resolve("test/deps/complex1");
    private static final Path BASELINE_LIST = locateBaselinePath(
            "test/deps/complex1/core/baseline-clean.txt",
            "test/deps/complex1/core/baseline_temp.txt",
            "target/verify/baseline.txt",
            "target/verify/java_classic.txt"
    );
    private static final Path BASELINE_COPY = locateBaselinePath(
            "test/deps/complex1/core/dependency-copy.txt",
            "test/deps/complex1/core/baseline_temp.txt",
            "target/verify/baseline.txt",
            "target/verify/java_classic.txt"
    );
    private static final File LOCAL_REPO = new File(System.getProperty("user.home"), ".m2/repository");

    private static Path resolveRepoRoot() {
        Path cwd = Paths.get(".").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("test/deps/complex1/core/pom.xml"))) {
            return cwd;
        }
        if (Files.exists(cwd.getParent().resolve("test/deps/complex1/core/pom.xml"))) {
            return cwd.getParent();
        }
        Path alt = Paths.get("d:/wrk/maven-get-deps");
        if (Files.exists(alt.resolve("test/deps/complex1/core/pom.xml"))) {
            return alt;
        }
        return cwd;
    }

    private static Path locateBaselinePath(String... candidates) {
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (!path.isAbsolute()) {
                path = ROOT.resolve(candidate);
            }
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("Baseline file not found. Tried: " + String.join(", ", candidates));
    }

    @Test
    public void validateBaselines() throws IOException {
        List<String> listLines = readLinesHandlingBOM(BASELINE_LIST);
        List<String> copyLines = readLinesHandlingBOM(BASELINE_COPY);

        // dependency-list has GAV: com.fasterxml.jackson.core:jackson-core:jar:2.19.4:compile
        // dependency-copy has filenames: jackson-core-2.19.4.jar
        
        Set<String> listArtifacts = listLines.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(DependencyListParser::parse)
                .filter(java.util.Objects::nonNull)
                .map(this::toFileName)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> copyArtifacts = copyLines.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(line -> {
                    ArtifactDescriptor ad = DependencyListParser.parse(line);
                    if (ad != null) {
                        return toFileName(ad);
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));

        compareContent(listArtifacts, copyArtifacts, "Baseline-Copy vs Baseline-List");
        Assertions.assertEquals(183, listArtifacts.size(), "Baseline should have 183 unique artifacts");
    }

    @Test
    public void testMimicParity() throws IOException {
        MimicDependencyResolver mimic = new MimicDependencyResolver(LOCAL_REPO, List.of(REACTOR.toFile()));
        mimic.setNoCache(true);
        ResolutionResult result = mimic.resolve(POM, List.of("compile", "runtime"));
        
        Set<String> actual = convertToBaselineFormat(result);
        Set<String> expected = normalizeBaselineForParity(readLinesHandlingBOM(BASELINE_LIST));

        Set<String> missing = expected.stream().filter(item -> !actual.contains(item)).collect(Collectors.toCollection(TreeSet::new));
        Set<String> extra = actual.stream().filter(item -> !expected.contains(item)).collect(Collectors.toCollection(TreeSet::new));

        System.err.println("Mimic parity: expected=" + expected.size() + " actual=" + actual.size() + " missing=" + missing.size() + " extra=" + extra.size());
        if (!missing.isEmpty()) {
            System.err.println("Mimic missing: " + missing);
        }
        if (!extra.isEmpty()) {
            System.err.println("Mimic extra: " + extra);
        }

        // Debug samples for missing items
        List<String> missingCheck = List.of("com.bugsnag:bugsnag", "org.springframework.boot:spring-boot", "org.apache.logging.log4j:log4j-api");
        for (String check : missingCheck) {
            boolean present = actual.stream().anyMatch(dep -> dep.contains(check));
            System.err.println(check + " present in mimic output: " + present);
        }

        // Debug output to file
        System.err.println("Mimic resolved total=" + actual.size());
        Path outFile = Paths.get("target", "mimic_resolved.txt");
        Files.createDirectories(outFile.getParent());
        Files.write(outFile, actual);

        compareContent(expected, actual, "Mimic");
    }

    @Test
    public void testMavenParity() throws IOException {
        MavenDependencyResolver maven = new MavenDependencyResolver(LOCAL_REPO.getAbsolutePath());
        maven.addReactorPath(REACTOR.toFile());
        ResolutionResult result = maven.resolve(POM, List.of("compile", "runtime"));
        
        Set<String> actual = convertToBaselineFormat(result);
        Set<String> expected = normalizeBaselineForParity(readLinesHandlingBOM(BASELINE_LIST));
        
        compareContent(expected, actual, "Maven");
    }

    private Set<String> convertToBaselineFormat(ResolutionResult result) {
        return result.dependencies().stream()
                .map(ad -> {
                    String classifier = ad.classifier() == null ? "" : ad.classifier();
                    return ad.groupId() + ":" + ad.artifactId() + ":" + ad.type() + ":" + classifier + ":" + ad.version() + ":" + ad.scope();
                })
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private void compareContent(Set<String> expected, Set<String> actual, String implName) {
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);

        StringBuilder sb = new StringBuilder();
        if (!unexpected.isEmpty()) {
            sb.append("\nUnexpected artifacts in ").append(implName).append(":");
            unexpected.forEach(s -> sb.append("\n  ").append(s));
        }
        if (!missing.isEmpty()) {
            sb.append("\nMissing artifacts in ").append(implName).append(":");
            missing.forEach(s -> sb.append("\n  ").append(s));
        }

        if (sb.length() > 0) {
            Assertions.fail(sb.toString());
        }
    }

    private String toFileName(ArtifactDescriptor ad) {
        return ad.artifactId() + "-" + ad.version() + "." + ad.type();
    }

    private Set<String> normalizeBaselineForParity(List<String> lines) {
        return lines.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(DependencyListParser::parse)
                .filter(java.util.Objects::nonNull)
                .map(ad -> ad.groupId() + ":" + ad.artifactId() + ":" + ad.type() + ":" + (ad.classifier() == null ? "" : ad.classifier()) + ":" + ad.version() + ":" + ad.scope())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private List<String> readLinesHandlingBOM(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(line -> line.startsWith("\uFEFF") ? line.substring(1) : line)
                    .collect(Collectors.toList());
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }
}
