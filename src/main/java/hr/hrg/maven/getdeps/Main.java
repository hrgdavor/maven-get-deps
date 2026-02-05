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

public class Main {

    public static void main(String[] args) {
        Options options = new Options();

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

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            String destDir = cmd.getOptionValue("dest-dir");
            String pomPath = cmd.getOptionValue("pom", "pom.xml");
            String outputPath = cmd.getOptionValue("output");
            String reportPath = cmd.getOptionValue("report");
            String cachePath = cmd.getOptionValue("cache");
            String scopesStr = cmd.getOptionValue("scopes", "compile,runtime");
            boolean copyJars = !cmd.hasOption("no-copy");

            run(destDir, pomPath, outputPath, reportPath, cachePath, scopesStr, copyJars);

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
            String scopesStr, boolean copyJars) throws Exception {
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
                for (String path : result.relativePaths) {
                    writer.println(path);
                }
            }
            System.out.println("Output written to: " + outputPath);
        } else {
            for (String path : result.relativePaths) {
                System.out.println(path);
            }
        }

        if (reportPath != null) {
            DependencyResolverService.ReportResult report = DependencyResolverService.resolveReport(
                    system, session, repos, model.getDependencies(), Main::resolveProperty, model, scopes);

            try (PrintWriter writer = new PrintWriter(new File(reportPath))) {
                writer.print(report.formatMarkdownTable());
            }
            if (outputPath != null)
                System.out.println("Report written to: " + reportPath);
        }

        if (outputPath != null)
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
}
