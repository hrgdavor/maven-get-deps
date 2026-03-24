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

    private static final Path ROOT = Paths.get("d:/wrk/maven-get-deps");
    private static final Path POM = ROOT.resolve("test/deps/complex1/core/pom.xml");
    private static final Path REACTOR = ROOT.resolve("test/deps/complex1");
    private static final Path BASELINE_LIST = ROOT.resolve("test/deps/complex1/core/baseline-clean.txt");
    private static final Path BASELINE_COPY = ROOT.resolve("test/deps/complex1/core/dependency-copy.txt");
    private static final File LOCAL_REPO = new File(System.getProperty("user.home"), ".m2/repository");

    @Test
    public void validateBaselines() throws IOException {
        List<String> listLines = readLinesHandlingBOM(BASELINE_LIST);
        List<String> copyLines = readLinesHandlingBOM(BASELINE_COPY);

        // dependency-list has GAV: com.fasterxml.jackson.core:jackson-core:jar:2.19.4:compile
        // dependency-copy has filenames: jackson-core-2.19.4.jar
        
        Set<String> listArtifacts = listLines.stream()
                .map(line -> {
                    String[] parts = line.split(":");
                    if (parts.length < 4) return null;
                    String aid = parts[1];
                    String type = parts[2];
                    String ver = parts[3];
                    return aid + "-" + ver + "." + type;
                })
                .filter(s -> s != null)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> copyArtifacts = copyLines.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        compareContent(new java.util.ArrayList<>(listArtifacts), copyArtifacts, "Baseline-Copy vs Baseline-List");
        Assertions.assertEquals(183, listArtifacts.size(), "Baseline should have 183 unique artifacts");
    }

    @Test
    public void testMimicParity() throws IOException {
        MimicDependencyResolver mimic = new MimicDependencyResolver(LOCAL_REPO, List.of(REACTOR.toFile()));
        ResolutionResult result = mimic.resolve(POM, List.of("compile", "runtime"));
        
        Set<String> actual = convertToBaselineFormat(result);
        List<String> expected = readLinesHandlingBOM(BASELINE_LIST);
        
        compareContent(expected, actual, "Mimic");
    }

    @Test
    public void testMavenParity() throws IOException {
        MavenDependencyResolver maven = new MavenDependencyResolver(LOCAL_REPO.getAbsolutePath());
        maven.addReactorPath(REACTOR.toFile());
        ResolutionResult result = maven.resolve(POM, List.of("compile", "runtime"));
        
        Set<String> actual = convertToBaselineFormat(result);
        List<String> expected = readLinesHandlingBOM(BASELINE_LIST);
        
        compareContent(expected, actual, "Maven");
    }

    private Set<String> convertToBaselineFormat(ResolutionResult result) {
        return result.dependencies().stream()
                .map(ad -> ad.groupId() + ":" + ad.artifactId() + ":" + ad.type() + ":" + ad.version() + ":" + ad.scope())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private void compareContent(List<String> expected, Set<String> actual, String implName) {
        Set<String> expectedSet = new TreeSet<>(expected);
        
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expectedSet);
        
        Set<String> missing = new TreeSet<>(expectedSet);
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
