package hr.hrg.maven.getdeps;

import org.apache.commons.cli.*;
import org.apache.maven.model.Model;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import hr.hrg.maven.getdeps.FormatConverter;
import hr.hrg.maven.getdeps.DependencyFormatInfo;
import java.util.Map;
import java.util.HashMap;

public class Main {

    public static final String VERSION;

    static {
        String version = "unknown";
        try (java.io.InputStream is = Main.class.getResourceAsStream("/app.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                version = props.getProperty("version", "unknown");
            }
        } catch (java.io.IOException e) {
            // ignore
        }
        VERSION = version;

        // Silence OWASP console output by default
        if (System.getProperty("org.slf4j.simpleLogger.log.org.owasp") == null) {
            System.setProperty("org.slf4j.simpleLogger.log.org.owasp", "warn");
        }
    }

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("v", "version", false, "Show version");

        Option destDirOpt = new Option("d", "dest-dir", true,
                "Destination directory for copies (relative paths in output will be relative to this)");
        options.addOption(destDirOpt);

        Option pomOpt = new Option("p", "pom", true, "Path to the pom.xml file");
        options.addOption(pomOpt);

        Option outputOpt = new Option("o", "output", true, "Output file path (optional)");
        options.addOption(outputOpt);

        Option reportOpt = new Option("r", "report", true, "Dependency size report output file path (optional)");
        options.addOption(reportOpt);

        Option cacheOpt = new Option("c", "cache", true,
                "Local repository source/cache (defaults to ~/.m2/repository)");
        options.addOption(cacheOpt);

        options.addOption(new Option("s", "scopes", true,
                "Comma-separated list of scopes to include (default: compile,runtime)"));
        options.addOption(new Option("n", "no-copy", false, "Disable copying to dest-dir (even if provided)"));

        Option inputOpt = new Option("i", "input", true, "Input text file with list of dependencies to convert");
        options.addOption(inputOpt);

        Option convertOpt = new Option("cf", "convert-format", true,
                "Convert format (colon|path). Requires -i/--input");
        options.addOption(convertOpt);

        options.addOption(new Option("cp", "classpath", false,
                "Output as a valid CLASSPATH string joined by the OS-specific path separator"));

        options.addOption(new Option("cr", "cve-report", true,
                "CVE report output file (queries local OWASP H2 database, default path used if --cve-data omitted)"));
        options.addOption(new Option("cd", "cve-data", true,
                "Path to the OWASP Dependency-Check H2 database directory (default: ~/.m2/dependency-check-data)"));
        options.addOption(new Option("cu", "cve-update", false,
                "Download / update the OWASP CVE database into the --cve-data directory and exit"));
        options.addOption(new Option("nk", "nvd-api-key", true,
                "NVD API key for higher rate limits during --cve-update (see https://nvd.nist.gov/developers/request-an-api-key)"));

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("version")) {
                System.out.println("maven-get-deps version: " + VERSION);
                return;
            }

            String inputPath = cmd.getOptionValue("input");
            String convertFormat = cmd.getOptionValue("convert-format");
            String outputPath = cmd.getOptionValue("output");
            String cachePath = cmd.getOptionValue("cache");

            String scopesStr = cmd.getOptionValue("scopes", "compile,runtime");
            boolean copyJars = !cmd.hasOption("no-copy");
            boolean classpathMode = cmd.hasOption("classpath");

            if (inputPath != null && convertFormat != null) {
                runConvert(inputPath, outputPath, convertFormat, classpathMode, cachePath);
                return;
            }

            String destDir = cmd.getOptionValue("dest-dir");
            String reportPath = cmd.getOptionValue("report");
            String cveReportPath = cmd.getOptionValue("cve-report");

            String defaultCveData = System.getProperty("user.home") + "/.m2/dependency-check-data";
            String cveDataDir = cmd.hasOption("cve-data") ? cmd.getOptionValue("cve-data") : defaultCveData;

            String pomPath = cmd.getOptionValue("pom", "pom.xml");
            File pomFile = new File(pomPath);
            DependencyResolverService service = new DependencyResolverService();

            if (cmd.hasOption("cve-update")) {
                String nvdApiKey = cmd.getOptionValue("nvd-api-key");
                CveReportService.updateDatabase(cveDataDir, nvdApiKey);
                return;
            }

            if (cveReportPath != null) {
                // Generate CVE report
                Map<String, List<String>> deps;
                if (inputPath != null) {
                    System.out.println("[CVE] Reading dependencies from: " + inputPath);
                    deps = DependencyResolverService.readPerDepFromFile(inputPath);
                } else {
                    System.out.println("[CVE] Resolving dependencies from: " + pomPath);
                    deps = service.resolvePerDep(pomFile, scopesStr, cachePath);
                }

                String report = CveReportService.scan(cveDataDir, deps).formatMarkdownReport();
                java.nio.file.Files.writeString(java.nio.file.Path.of(cveReportPath), report);
                System.out.println("CVE report written to: " + cveReportPath);

                // If we ONLY wanted a CVE report, exit now.
                // We check if other output options are missing.
                boolean hasOtherOutputs = outputPath != null || reportPath != null
                        || (cmd.hasOption("dest-dir") && !cmd.hasOption("no-copy"));
                if (!hasOtherOutputs) {
                    return;
                }
            }

            run(destDir, pomPath, outputPath, reportPath, cachePath, scopesStr, copyJars, classpathMode,
                    cveReportPath, cveDataDir);

        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("maven-get-deps", options);
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String destDir, String pomPath, String outputPath, String reportPath, String cachePath,
            String scopesStr, boolean copyJars, boolean classpathMode,
            String cveReportPath, String cveDataDir) throws Exception {

        // Size report logic remains as is (still uses pomPath)
        File pomFile = new File(pomPath);
        if (!pomFile.exists()) {
            throw new IllegalArgumentException("POM file not found: " + pomPath);
        }

        RepositorySystem system = Bootstrapper.newRepositorySystem();

        String defaultM2 = System.getProperty("user.home") + "/.m2/repository";
        String sourceRepoPath = (cachePath != null) ? cachePath : defaultM2;

        // Resolution always happens FROM sourceRepoPath (via LocalRepositoryManager)
        // BUT if copyJars is true AND destDir is provided, we use destDir as the "local
        // repo"
        // for the session, so Aether will copy artifacts from "remote" repos (including
        // our cache)
        String sessionLocalRepoPath = (copyJars && destDir != null) ? destDir : sourceRepoPath;

        RepositorySystemSession session = Bootstrapper.newRepositorySystemSession(system, sessionLocalRepoPath);

        if (outputPath != null) {
            ((DefaultRepositorySystemSession) session)
                    .setRepositoryListener(new Bootstrapper.LoggingRepositoryListener());
        }

        // Always add Central
        List<RemoteRepository> repos = Bootstrapper.newRepositories(system, session, sourceRepoPath);

        // If we are copying, we MUST add the sourceRepoPath (local .m2) as a "remote"
        // cache
        // so it's a source for resolution while the session "local repo" is the
        // destination
        if (copyJars && destDir != null) {
            repos.add(0,
                    new RemoteRepository.Builder("local-cache", "default", new File(sourceRepoPath).toURI().toString())
                            .build());
        }

        Model model = Bootstrapper.resolveModel(pomFile, system, session, repos);

        // Add additional repositories from the POM model
        repos.addAll(Bootstrapper.convertRepositories(model.getRepositories()));

        Set<String> scopes = Arrays.stream(scopesStr.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());

        DependencyResolverService.ResolutionResult result = DependencyResolverService.resolve(
                system,
                session,
                repos,
                model.getDependencies(),
                Main::resolveProperty,
                model,
                scopes);

        if (outputPath != null) {
            try (PrintWriter writer = new PrintWriter(new File(outputPath))) {
                if (classpathMode) {
                    writer.println(result.relativePaths.stream()
                            .map(p -> new File(sourceRepoPath, p).getAbsolutePath())
                            .collect(Collectors.joining(File.pathSeparator)));
                } else {
                    for (String path : result.relativePaths) {
                        writer.println(path);
                    }
                }
            }
            System.out.println("Output written to: " + outputPath);
        } else {
            if (classpathMode) {
                System.out.println(result.relativePaths.stream()
                        .map(p -> new File(sourceRepoPath, p).getAbsolutePath())
                        .collect(Collectors.joining(File.pathSeparator)));
            } else {
                for (String path : result.relativePaths) {
                    System.out.println(path);
                }
            }
        }

        if (reportPath != null) {
            DependencyResolverService.ReportResult report = DependencyResolverService.resolveReport(
                    system, session, repos, model.getDependencies(), Main::resolveProperty, model, scopes);

            try (PrintWriter writer = new PrintWriter(new File(reportPath))) {
                writer.print(report.formatMarkdownTable());
            }
            System.out.println("Size report written to: " + reportPath);
        }

        if (cveReportPath != null) {
            if (cveDataDir == null) {
                System.err.println("[CVE] --cve-data is required when using --cve-report");
            } else {
                java.util.LinkedHashMap<String, List<String>> perDep = DependencyResolverService.resolvePerDep(
                        system, session, repos, model.getDependencies(),
                        Main::resolveProperty, model, scopes);
                CveReportService.CveReportResult cveResult = CveReportService.scan(cveDataDir, perDep);
                try (PrintWriter writer = new PrintWriter(new File(cveReportPath))) {
                    writer.print(cveResult.formatMarkdownReport());
                }
                System.out.println("CVE report written to: " + cveReportPath);
            }
        }

        System.out.println("Total size: " + result.totalSize + " bytes");
    }

    private static String resolveProperty(String value, Model model) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String propertyName = value.substring(2, value.length() - 1);
            String propertyValue = model.getProperties().getProperty(propertyName);
            if (propertyValue != null) {
                return propertyValue;
            }
        }
        return value;
    }

    private static void runConvert(String inputPath, String outputPath, String convertFormat, boolean classpathMode,
            String cachePath) throws Exception {
        boolean toColon = "colon".equalsIgnoreCase(convertFormat);
        boolean toPath = "path".equalsIgnoreCase(convertFormat);

        if (!toColon && !toPath) {
            throw new IllegalArgumentException("Invalid convert option. Must be 'colon' or 'path'.");
        }

        java.util.List<String> lines = java.nio.file.Files.readAllLines(new File(inputPath).toPath());
        java.util.List<String> collectedPaths = new java.util.ArrayList<>();

        String defaultM2 = System.getProperty("user.home") + "/.m2/repository";
        String sourceRepoPath = (cachePath != null) ? cachePath : defaultM2;

        PrintWriter writer = null;
        if (outputPath != null) {
            writer = new PrintWriter(new File(outputPath));
        }

        for (String line : lines) {
            if (line.trim().isEmpty())
                continue;
            DependencyFormatInfo info = FormatConverter.parse(line);
            if (info == null)
                continue;

            String outLine = toColon ? FormatConverter.formatColon(info) : FormatConverter.formatPath(info);

            if (classpathMode) {
                if (toPath && !info.isLocal()) {
                    outLine = new File(sourceRepoPath, outLine).getAbsolutePath();
                }
                collectedPaths.add(outLine);
            } else {
                if (writer != null) {
                    writer.println(outLine);
                } else {
                    System.out.println(outLine);
                }
            }
        }

        if (classpathMode) {
            String combined = String.join(File.pathSeparator, collectedPaths);
            if (writer != null) {
                writer.println(combined);
            } else {
                System.out.println(combined);
            }
        }

        if (writer != null) {
            writer.close();
            System.out.println("Output written to: " + outputPath);
        }
    }
}
