import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class BaselineExtractor {
    public static void main(String[] args) throws IOException {
        Path input = Paths.get("d:/wrk/maven-get-deps/complex1_core_baseline_utf8.txt");
        Path output = Paths.get("d:/wrk/maven-get-deps/complex1_core_baseline_clean.txt");
        Set<String> results = new TreeSet<>();
        Files.lines(input).forEach(line -> {
            String trimmed = line.trim();
            if (trimmed.startsWith("[INFO]")) {
                trimmed = trimmed.substring(6).trim();
            }
            String[] parts = trimmed.split(":");
            if (parts.length >= 5) {
                // groupId:artifactId:type:[classifier:]version:scope
                String g = parts[0];
                String a = parts[1];
                String t = parts[2];
                String v, s;
                if (parts.length == 5) {
                    v = parts[3];
                    s = parts[4];
                } else {
                    v = parts[4];
                    s = parts[5];
                }
                results.add(g + ":" + a + ":" + t + ":" + v + ":" + s);
            }
        });
        Files.write(output, results);
    }
}
