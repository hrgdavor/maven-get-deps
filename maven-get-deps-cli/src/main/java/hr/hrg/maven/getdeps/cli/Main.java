package hr.hrg.maven.getdeps.cli;

import hr.hrg.maven.getdeps.api.ArtifactDescriptor;
import hr.hrg.maven.getdeps.api.DependencyResolver;
import hr.hrg.maven.getdeps.api.ResolutionResult;
import hr.hrg.maven.getdeps.maven.MavenDependencyResolver;
import hr.hrg.maven.getdeps.mimic.MimicDependencyResolver;
import org.apache.commons.cli.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("c", "cache", true, "Local repository cache path");
        options.addOption("m", "mimic", false, "Use optimized mimic implementation");
        options.addOption("p", "pom", true, "Path to pom.xml");
        options.addOption("s", "scopes", true, "Comma-separated list of scopes");
        options.addOption("r", "reactor", true, "Reactor path for sibling modules (Mimic only)");
        options.addOption("n", "no-cache", false, "Disable internal caching for Mimic");
        options.addOption("E", "extended", false, "Extended output format (matches Maven dependency:list)");
        options.addOption("dm", "debug-match", true, "Filter for mimic debug trace logs (e.g. jaxb-runtime)");
        options.addOption("h", "help", false, "Print this help message");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                new HelpFormatter().printHelp("maven-get-deps-cli", options);
                return;
            }

            String pomPath = cmd.getOptionValue("p");
            String scopesStr = cmd.getOptionValue("s", "compile,runtime");
            String cachePath = cmd.getOptionValue("c", System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository");
            String reactorPath = cmd.getOptionValue("r");
            boolean useMimic = cmd.hasOption("mimic");
            boolean noCache = cmd.hasOption("no-cache");
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
            if (useMimic) {
                System.out.println("Using Mimic implementation (noCache=" + noCache + ")...");
                MimicDependencyResolver mimic = new MimicDependencyResolver(new File(cachePath), new ArrayList<>());
                if (reactorPath != null) {
                    mimic.addReactorPath(new File(reactorPath));
                }
                if (debugMatch != null) mimic.setDebugFilter(debugMatch);

                // Cold run
                if (noCache) mimic.clearCache();
                long startCold = System.currentTimeMillis();
                result = mimic.resolve(Path.of(pomPath), scopes);
                long endCold = System.currentTimeMillis();

                // Hot run
                if (noCache) mimic.clearCache();
                long startHot = System.currentTimeMillis();
                mimic.resolve(Path.of(pomPath), scopes);
                long endHot = System.currentTimeMillis();

                System.out.println("Performance Breakdown:");
                System.out.println("  Cold Cache: " + (endCold - startCold) + "ms");
                System.out.println("  Hot Cache:  " + (endHot - startHot) + "ms");

            } else {
                System.out.println("Using Maven implementation (noCache=" + noCache + ")...");

                // Maven cold run (new instance)
                MavenDependencyResolver mavenCold = new MavenDependencyResolver(cachePath);
                if (reactorPath != null) mavenCold.addReactorPath(new File(reactorPath));

                long startCold = System.currentTimeMillis();
                result = mavenCold.resolve(Path.of(pomPath), scopes);
                long endCold = System.currentTimeMillis();

                // Maven hot run (reuse instance/session if possible, or new instance if noCache)
                MavenDependencyResolver mavenHot = noCache ? new MavenDependencyResolver(cachePath) : mavenCold;
                if (noCache && reactorPath != null) mavenHot.addReactorPath(new File(reactorPath));

                long startHot = System.currentTimeMillis();
                mavenHot.resolve(Path.of(pomPath), scopes);
                long endHot = System.currentTimeMillis();

                System.out.println("Performance Breakdown:");
                System.out.println("  Cold Cache: " + (endCold - startCold) + "ms");
                System.out.println("  Hot Cache:  " + (endHot - startHot) + "ms");
            }

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
                            System.out.println("   " + dep.groupId() + ":" + dep.artifactId() + ":" + dep.type() + ":" + dep.version() + ":" + dep.scope() + " (" + dep.path() + ")");
                        } else {
                            File file = finalResult.artifactFiles().get(dep);
                            System.out.println("   " + dep.toGAV() + " -> " + (file != null ? file.getAbsolutePath() : "NOT FOUND"));
                        }
                    });
            }

        } catch (ParseException e) {
            new HelpFormatter().printHelp("maven-get-deps-cli", options);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
