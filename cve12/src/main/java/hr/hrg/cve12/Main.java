package hr.hrg.cve12;

import hr.hrg.cve.CveReportResult;
import org.apache.commons.cli.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        // Quick scan for kevUrl to set it as early as possible for ODC static initializers
        for (int i = 0; i < args.length - 1; i++) {
            if ("--kevUrl".equals(args[i]) || "-ku".equals(args[i])) {
                String kv = args[i + 1];
                System.setProperty("data.kev.url", kv);
                System.setProperty("cisa.kev.url", kv);
                System.setProperty("analyzer.knownexploitedvulnerability.url", kv);
                break;
            }
        }

        Options options = new Options();
        options.addOption(new Option("h", "help", false, "Show help"));
        options.addOption(new Option("r", "report", true, "Report output file (markdown)"));
        options.addOption(new Option("d", "data", true, "Path to the OWASP Dependency-Check H2 database directory"));
        options.addOption(new Option("u", "update", false, "Download/update the CVE database and exit"));
        options.addOption(new Option("k", "key", true, "NVD API key for higher rate limits during update"));
        options.addOption(new Option("nd", "nvd-api-delay", true, "Delay in milliseconds between NVD API requests"));
        options.addOption(new Option("i", "input", true, "Input file containing dependencies (newline delimited G:A:V)"));
        options.addOption(new Option("t", "threshold", true, "CVSS severity threshold (0.0 to 10.0, default: 8.0)"));
        options.addOption(new Option("ku", "kevUrl", true, "URL to the Known Exploited Vulnerabilities JSON feed"));

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help") || args.length == 0) {
                formatter.printHelp("cve12", options);
                return;
            }

            String dataDir = cmd.getOptionValue("data", System.getProperty("user.home") + "/.m2/dependency-check-data");
            String nvdApiDelay = cmd.getOptionValue("nvd-api-delay");
            String kevUrl = cmd.getOptionValue("kevUrl");
            
            if (kevUrl != null && !kevUrl.isBlank()) {
                System.setProperty("data.kev.url", kevUrl);
                System.setProperty("analyzer.knownexploitedvulnerability.url", kevUrl);
            }

            if (cmd.hasOption("update")) {
                CveScanner.updateDatabase(dataDir, cmd.getOptionValue("key"), nvdApiDelay, kevUrl);
                return;
            }

            String inputPath = cmd.getOptionValue("input");
            String reportPath = cmd.getOptionValue("report");

            if (inputPath == null || reportPath == null) {
                System.err.println("Error: --input and --report are required for scanning.");
                formatter.printHelp("cve12", options);
                System.exit(1);
            }

            Map<String, List<String>> deps = readDeps(inputPath);
            CveReportResult result = CveScanner.scan(dataDir, deps, nvdApiDelay, kevUrl);

            String report = result.formatMarkdownReport();
            Files.writeString(Path.of(reportPath), report);
            System.out.println("CVE report written to: " + reportPath);

            float threshold = Float.parseFloat(cmd.getOptionValue("threshold", "8.0"));
            if (result.hasAnyVulnerabilitiesAbove(threshold)) {
                System.err.println("High-severity vulnerabilities found! Exiting with error.");
                System.exit(1);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Map<String, List<String>> readDeps(String path) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(path));
        Map<String, List<String>> result = new HashMap<>();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // Simple mapping for now: assume each line is its own direct dep
            result.put(line, List.of(line));
        }
        return result;
    }
}
