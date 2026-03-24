package hr.hrg.maven.getdeps.mimic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Utility to compare two dependency lists.
 * Replaces PS1 comparison scripts.
 */
public class DependencyComparator {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: java DependencyComparator <file1> <file2>");
            System.exit(1);
        }

        Path p1 = Paths.get(args[0]);
        Path p2 = Paths.get(args[1]);

        List<String> lines1 = readLines(p1);
        List<String> lines2 = readLines(p2);

        compare(lines1, lines2, p1.getFileName().toString(), p2.getFileName().toString());
    }

    public static List<String> readLines(Path path) throws IOException {
        if (!Files.exists(path)) return Collections.emptyList();
        byte[] bytes = Files.readAllBytes(path);
        // Handle UTF-8 BOM
        if (bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(line -> line.startsWith("\uFEFF") ? line.substring(1) : line)
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .collect(Collectors.toList());
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> l.split(":").length >= 5)
                .collect(Collectors.toList());
    }

    public static void compare(List<String> expected, List<String> actual, String name1, String name2) {
        Set<String> set1 = new TreeSet<>(expected);
        Set<String> set2 = new TreeSet<>(actual);

        Set<String> unexpected = new TreeSet<>(set2);
        unexpected.removeAll(set1);

        Set<String> missing = new TreeSet<>(set1);
        missing.removeAll(set2);

        if (unexpected.isEmpty() && missing.isEmpty() && set1.size() == set2.size()) {
            System.out.println("SUCCESS: " + name1 + " and " + name2 + " align 100% (" + set1.size() + " items)");
            return;
        }

        System.out.println("DISCREPANCY FOUND between " + name1 + " (" + set1.size() + ") and " + name2 + " (" + set2.size() + ")");
        
        if (!unexpected.isEmpty()) {
            System.out.println("\nUnexpected in " + name2 + ":");
            unexpected.forEach(s -> System.out.println("  + " + s));
        }
        if (!missing.isEmpty()) {
            System.out.println("\nMissing in " + name2 + ":");
            missing.forEach(s -> System.out.println("  - " + s));
        }
        
        System.exit(1);
    }
}
