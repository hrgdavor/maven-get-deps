package hr.hrg.maven.getdeps.mimic;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DependencyListParserTest {

    @Test
    public void testParseSimple() {
        ArtifactDescriptor dep = DependencyListParser.parse("org.example:my-lib:1.2.3");
        assertNotNull(dep);
        assertEquals("org.example", dep.groupId());
        assertEquals("my-lib", dep.artifactId());
        assertEquals("1.2.3", dep.version());
        assertEquals("jar", dep.type());
        assertEquals("compile", dep.scope());
    }

    @Test
    public void testParseWithClassifier() {
        ArtifactDescriptor dep = DependencyListParser.parse("org.example:my-lib:1.2.3:jdk8");
        assertNotNull(dep);
        assertEquals("org.example", dep.groupId());
        assertEquals("my-lib", dep.artifactId());
        assertEquals("1.2.3", dep.version());
        assertEquals("jdk8", dep.classifier());
    }

    @Test
    public void testParseMavenListFormat() {
        // [INFO]    org.apache.commons:commons-lang3:jar:3.12.0:compile
        ArtifactDescriptor dep = DependencyListParser.parse("[INFO]    org.apache.commons:commons-lang3:jar:3.12.0:compile");
        assertNotNull(dep);
        assertEquals("org.apache.commons", dep.groupId());
        assertEquals("commons-lang3", dep.artifactId());
        assertEquals("jar", dep.type());
        assertEquals("3.12.0", dep.version());
        assertEquals("compile", dep.scope());
    }

    @Test
    public void testParseMavenListWithClassifier() {
        // org.example:my-lib:jar:jdk8:1.2.3:compile
        ArtifactDescriptor dep = DependencyListParser.parse("org.example:my-lib:jar:jdk8:1.2.3:compile");
        assertNotNull(dep);
        assertEquals("org.example", dep.groupId());
        assertEquals("my-lib", dep.artifactId());
        assertEquals("jar", dep.type());
        assertEquals("jdk8", dep.classifier());
        assertEquals("1.2.3", dep.version());
        assertEquals("compile", dep.scope());
    }

    @Test
    public void testParseDescriptiveLine() {
        assertNull(DependencyListParser.parse("[INFO] --- dependency:3.6.0:list (default-cli) @ core ---"));
        assertNull(DependencyListParser.parse("The following files have been resolved:"));
        assertNull(DependencyListParser.parse(""));
    }

    @Test
    public void testLoadBaselineFile() throws java.io.IOException {
        java.nio.file.Path path = java.nio.file.Paths.get("test/deps/complex1/core/dependency-list.txt");
        // Check if we are running from the target/test-classes or project root
        if (!java.nio.file.Files.exists(path)) {
            // Try relative to workspace root if not found (for some IDE runners)
            path = java.nio.file.Paths.get("d:/wrk/maven-get-deps/test/deps/complex1/core/dependency-list.txt");
        }
        
        long count = java.nio.file.Files.lines(path)
                .map(DependencyListParser::parse)
                .filter(java.util.Objects::nonNull)
                .count();
        assertEquals(183, count);
    }

    @Test
    public void testLoadRawBaselineFile() throws java.io.IOException {
        java.nio.file.Path path = java.nio.file.Paths.get("test/deps/complex1/core/dependency-list-raw.txt");
        if (!java.nio.file.Files.exists(path)) {
            path = java.nio.file.Paths.get("d:/wrk/maven-get-deps/test/deps/complex1/core/dependency-list-raw.txt");
        }
        
        long count = java.nio.file.Files.lines(path)
                .map(DependencyListParser::parse)
                .filter(java.util.Objects::nonNull)
                .count();
        assertEquals(212, count);
    }
}
