package hr.hrg.maven.getdeps.cli;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.DependencyResolver;
import hr.hrg.maven.getdeps.api.DependencySizeReport;
import hr.hrg.maven.getdeps.api.ResolutionResult;
import hr.hrg.maven.getdeps.maven.MavenDependencyResolver;
import hr.hrg.maven.getdeps.mimic.MimicDependencyResolver;
import org.apache.commons.cli.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("c", "cache", true, "Local repository cache path");
        options.addOption("m", "mimic", false, "Use optimized mimic implementation");
        options.addOption("p", "pom", true, "Path to pom.xml");
        options.addOption("s", "scopes", true, "Comma-separated list of scopes");
        options.addOption("R", "reactor", true, "Reactor path for sibling modules (Mimic only)");
        options.addOption("r", "report", true, "Path to save the dependency size report (Markdown).");
        options.addOption("C", "use-cache", false, "Enable internal caching for Mimic (opt-in)");
        options.addOption("E", "extended", false, "Extended output format (matches Maven dependency:list)");
        options.addOption("dm", "debug-match", true, "Filter for mimic debug trace logs (e.g. jaxb-runtime)");
        options.addOption("ss", "skip-siblings", false, "Skip sibling modules in the final output (Mimic only)");
        options.addOption("h", "help", false, "Print this help message");

        CommandLineParser parser = new DefaultParser();
        try {
            if (args.length > 0 && (args[0].equals("scan-main") || args[0].equals("find-main"))) {
                String scanPath = args.length > 1 ? args[1] : ".";
                JavaMainFinder.scanAndPrint(Paths.get(scanPath));
                return;
            }

            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                System.out.println("Usage: java -jar maven-get-deps.jar <command> [options]");
                System.out.println("");
                System.out.println("Commands:");
                System.out.println("  scan-main [path]    Search for Java files with a main method");
                System.out.println("  find-main [path]    Alias for scan-main");
                System.out.println("");
                System.out.println("Options:");
                new HelpFormatter().printHelp(" ", options);
                return;
            }

            String pomPath = cmd.getOptionValue("p");
            String scopesStr = cmd.getOptionValue("s", "compile,runtime");
            String cachePath = cmd.getOptionValue("c",
                    System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository");
            String reactorPath = cmd.getOptionValue("R");
            String reportPath = cmd.getOptionValue("r");
            boolean useMimic = cmd.hasOption("mimic");
            boolean useCache = cmd.hasOption("use-cache");
            boolean extended = cmd.hasOption("extended");
            String debugMatch = cmd.getOptionValue("dm");

            if (pomPath == null) {
                System.err.println("POM path is required (-p)");
                new HelpFormatter().printHelp("maven-get-deps-cli", options);
                return;
            }

            List<String> scopes = List.of(scopesStr.split(","));
            List<String> mutableScopes = new ArrayList<>(scopes);

            ResolutionResult result = null;
            long start = System.currentTimeMillis();
            if (useMimic) {
                MimicDependencyResolver mimic = new MimicDependencyResolver(new File(cachePath), new ArrayList<>());
                if (reactorPath != null)
                    mimic.addReactorPath(new File(reactorPath));
                if (debugMatch != null)
                    mimic.setDebugFilter(debugMatch);
                if (cmd.hasOption("ss"))
                    mimic.setSkipSiblings(true);

                mimic.setNoCache(!useCache);
                result = mimic.resolve(Path.of(pomPath), mutableScopes);
            } else {
                MavenDependencyResolver maven = new MavenDependencyResolver(cachePath);
                maven.setUseCache(useCache);
                if (reactorPath != null)
                    maven.addReactorPath(new File(reactorPath));
                result = maven.resolve(Path.of(pomPath), mutableScopes);
            }
            long end = System.currentTimeMillis();
            System.out.println("Resolution completed in " + (end - start) + "ms (mimic=" + useMimic + ", useCache="
                    + useCache + ")");

            if (result != null) {
                final ResolutionResult finalResult = result;
                if (finalResult.hasErrors()) {
                    System.err.println("Errors during resolution:");
                    finalResult.errors().forEach(System.err::println);
                }

                System.out.println("Resolved Dependencies (" + finalResult.dependencies().size() + "):");
                finalResult.dependencies().stream()
                        .sorted((d1, d2) -> d1.toString().compareTo(d2.toString()))
                        .forEach(dep -> {
                            if (extended) {
                                System.out.println("   " + dep.groupId() + ":" + dep.artifactId() + ":" + dep.type()
                                        + ":" + dep.version() + ":" + dep.scope() + " (" + dep.path() + ")");
                            } else {
                                File file = finalResult.artifactFiles().get(dep);
                                System.out.println("   " + dep.toGAV() + " -> "
                                        + (file != null ? file.getAbsolutePath() : "NOT FOUND"));
                            }
                        });

                if (reportPath != null) {
                    String report = DependencySizeReport.formatMarkdownReport(finalResult);
                    Path reportFilePath = Path.of(reportPath);
                    if (reportFilePath.getParent() != null) {
                        Files.createDirectories(reportFilePath.getParent());
                    }
                    Files.writeString(reportFilePath, report);
                    System.out.println("Dependency size report written to: " + reportPath);
                }
            }

        } catch (ParseException e) {
            new HelpFormatter().printHelp("maven-get-deps-cli", options);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
