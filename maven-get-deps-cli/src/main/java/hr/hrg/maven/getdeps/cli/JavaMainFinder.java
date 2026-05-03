package hr.hrg.maven.getdeps.cli;

import java.io.*;
import java.nio.file.*;
import java.util.regex.*;
import java.util.stream.*;

public class JavaMainFinder {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([^;\\s]+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern MAIN_PATTERN = Pattern.compile(
            "public\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*(?:\\[\\s*\\]\\s*\\w+|\\.\\.\\.\\s*\\w+|\\w+\\s*\\[\\s*\\])\\s*\\)");

    public static void scanAndPrint(Path startPath) {
        try (Stream<Path> paths = Files.walk(startPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        // System.out.println("Processing: " + p);
                        processFile(p);
                    });
        } catch (IOException e) {
            System.err.println("Error scanning directory: " + e.getMessage());
        }
    }

    private static void processFile(Path path) {
        String packageName = "";
        String className = null;
        boolean hasMain = false;

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Simplified comment stripping (doesn't handle multi-line comments perfectly
                // but good enough for this)
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                    continue;
                }

                if (packageName.isEmpty()) {
                    Matcher m = PACKAGE_PATTERN.matcher(line);
                    if (m.find()) {
                        packageName = m.group(1);
                    }
                }

                if (className == null) {
                    Matcher m = CLASS_PATTERN.matcher(line);
                    if (m.find()) {
                        className = m.group(1);
                    }
                }

                if (MAIN_PATTERN.matcher(line).find()) {
                    hasMain = true;
                    break;
                }
            }

            if (hasMain && className != null) {
                if (!packageName.isEmpty()) {
                    System.out.println(packageName + "." + className);
                } else {
                    System.out.println(className);
                }
            }
        } catch (IOException e) {
            // ignore files that can't be read
        }
    }
}
