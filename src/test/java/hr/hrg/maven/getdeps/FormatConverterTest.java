package hr.hrg.maven.getdeps;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FormatConverterTest {

    @Test
    public void testParseLocal() {
        DependencyFormatInfo info1 = FormatConverter.parse("./my-local-file.jar");
        assertTrue(info1.isLocal());
        assertEquals("./my-local-file.jar", info1.localPath());

        DependencyFormatInfo info2 = FormatConverter.parse(".\\my-local-file.jar");
        assertTrue(info2.isLocal());
        assertEquals(".\\my-local-file.jar", info2.localPath());
    }

    @Test
    public void testParseColonBasic() {
        DependencyFormatInfo info = FormatConverter.parse("org.example:my-lib:1.2.3");
        assertFalse(info.isLocal());
        assertEquals("org.example", info.groupId());
        assertEquals("my-lib", info.artifactId());
        assertEquals("1.2.3", info.version());
        assertNull(info.classifier());
        assertEquals("jar", info.extension());
    }

    @Test
    public void testParseColonWithClassifierAndExt() {
        DependencyFormatInfo info = FormatConverter.parse("org.example:my-lib:1.2.3:jdk8@zip");
        assertEquals("org.example", info.groupId());
        assertEquals("my-lib", info.artifactId());
        assertEquals("1.2.3", info.version());
        assertEquals("jdk8", info.classifier());
        assertEquals("zip", info.extension());
    }

    @Test
    public void testParsePathBasic() {
        DependencyFormatInfo info = FormatConverter.parse("org/example/my-lib/1.2.3/my-lib-1.2.3.jar");
        assertEquals("org.example", info.groupId());
        assertEquals("my-lib", info.artifactId());
        assertEquals("1.2.3", info.version());
        assertNull(info.classifier());
        assertEquals("jar", info.extension());
    }

    @Test
    public void testParsePathWithClassifierAndExt() {
        DependencyFormatInfo info = FormatConverter.parse("org/example/my-lib/1.2.3/my-lib-1.2.3-jdk8.zip");
        assertEquals("org.example", info.groupId());
        assertEquals("my-lib", info.artifactId());
        assertEquals("1.2.3", info.version());
        assertEquals("jdk8", info.classifier());
        assertEquals("zip", info.extension());
    }

    @Test
    public void testFormatColon() {
        DependencyFormatInfo info = FormatConverter.parse("org/example/my-lib/1.2.3/my-lib-1.2.3-jdk8.zip");
        String colon = FormatConverter.formatColon(info);
        assertEquals("org.example:my-lib:1.2.3:jdk8@zip", colon);

        DependencyFormatInfo info2 = FormatConverter.parse("org/example/my-lib/1.2.3/my-lib-1.2.3.jar");
        String colon2 = FormatConverter.formatColon(info2);
        assertEquals("org.example:my-lib:1.2.3", colon2);

        DependencyFormatInfo localInfo = FormatConverter.parse("./foo.jar");
        assertEquals("./foo.jar", FormatConverter.formatColon(localInfo));
    }

    @Test
    public void testFormatPath() {
        DependencyFormatInfo info = FormatConverter.parse("org.example:my-lib:1.2.3:jdk8@zip");
        String path = FormatConverter.formatPath(info);
        assertEquals("org/example/my-lib/1.2.3/my-lib-1.2.3-jdk8.zip", path);

        DependencyFormatInfo info2 = FormatConverter.parse("org.example:my-lib:1.2.3");
        String path2 = FormatConverter.formatPath(info2);
        assertEquals("org/example/my-lib/1.2.3/my-lib-1.2.3.jar", path2);

        DependencyFormatInfo localInfo = FormatConverter.parse("./foo.jar");
        assertEquals("./foo.jar", FormatConverter.formatPath(localInfo));
    }
}
