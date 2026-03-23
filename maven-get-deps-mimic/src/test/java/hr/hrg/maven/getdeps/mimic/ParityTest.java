package hr.hrg.maven.getdeps.mimic;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.ResolutionResult;
import hr.hrg.maven.getdeps.maven.MavenDependencyResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class ParityTest {

    private static final Path ROOT = Paths.get("d:/wrk/maven-get-deps");
    private static final Path POM = ROOT.resolve("test/deps/complex1/core/pom.xml");
    private static final Path REACTOR = ROOT.resolve("test/deps/complex1");
    private static final Path BASELINE = ROOT.resolve("test/deps/complex1/core/baseline-clean.txt");
    private static final File LOCAL_REPO = new File(System.getProperty("user.home"), ".m2/repository");

    @Test
    public void testMimicParity() throws IOException {
        MimicDependencyResolver mimic = new MimicDependencyResolver(LOCAL_REPO, List.of(REACTOR.toFile()));
        ResolutionResult result = mimic.resolve(POM, List.of("compile", "runtime"));
        
        Set<String> actual = convertToBaselineFormat(result);
        List<String> expected = Files.readAllLines(BASELINE);
        
        compareContent(expected, actual, "Mimic");
    }

    @Test
    public void testMavenParity() throws IOException {
        MavenDependencyResolver maven = new MavenDependencyResolver(LOCAL_REPO.getAbsolutePath());
        maven.addReactorPath(REACTOR.toFile());
        ResolutionResult result = maven.resolve(POM, List.of("compile", "runtime"));
        
        Set<String> actual = convertToBaselineFormat(result);
        List<String> expected = Files.readAllLines(BASELINE);
        
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
}
