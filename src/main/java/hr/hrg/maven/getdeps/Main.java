package hr.hrg.maven.getdeps;

import org.apache.commons.cli.*;
import org.apache.maven.model.Model;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

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
    }

    public static void main(String[] args) {

        Options options = new Options();
        options.addOption("v", "version", false, "Show version");

        Option destDirOpt = new Option("d", "dest-dir", true,
                "Destination directory for copies (relative paths in output will be relative to this)");
        options.addOption(destDirOpt);

        Option outputOpt = new Option("o", "output", true, "Output file path (optional)");
        options.addOption(outputOpt);

        Option reportOpt = new Option("r", "report", true, "Dependency size report output file path (optional)");
        options.addOption(reportOpt);

        Option cacheOpt = new Option("c", "cache", true,
                "Local repository source/cache (defaults to ~/.m2/repository)");
        options.addOption(cacheOpt);

        options.addOption(new Option("s", "scopes", true,
                "Comma-separated list of scopes to include (default: runtime)"));
        options.addOption(new Option("n", "no-copy", false, "Disable copying to dest-dir (even if provided)"));

        Option convertOpt = new Option("cf", "convert-format", true,
                "Convert format (colon|path). Requires <source> to be a file");
        options.addOption(convertOpt);

        options.addOption(new Option("cp", "classpath", false,
                "Output as a valid CLASSPATH string joined by the OS-specific path separator"));

        options.addOption(new Option("ecp", "extra-classpath", true,
                "Path to a file containing additional classpath entries (one per line) to append."));

        options.addOption(new Option("ex", "exclude-cp", true,
                "Comma-separated list of artifact IDs (G:A) or relative paths to exclude from the classpath."));

        options.addOption(new Option("es", "exclude-siblings", false,
                "Exclude artifacts from the same groupId as the project (default: false)"));
        options.addOption(new Option("off", "offline", false, "Work offline (no remote repository checks)"));
        options.addOption(new Option("nc", "no-cache", false, "Disable per-dependency caching"));
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("version")) {
                System.out.println("maven-get-deps version: " + VERSION);
                return;
            }

            String pomPath = null;
            String artifactCoords = null;
            String inputPath = null;

            java.util.List<String> argList = cmd.getArgList();
            if (!argList.isEmpty()) {
                String arg = argList.get(0);
                File f = new File(arg);
                if (f.isDirectory()) {
                    File pomFileInDir = new File(f, "pom.xml");
                    if (pomFileInDir.exists()) {
                        pomPath = pomFileInDir.getAbsolutePath();
                    } else {
                        System.err.println("Error: Directory '" + arg + "' does not contain a pom.xml.");
                        System.exit(1);
                    }
                } else if (f.isFile()) {
                    if (arg.endsWith(".xml")) {
                        pomPath = arg;
                    } else {
                        inputPath = arg;
                    }
                } else if (arg.contains(":")) {
                    artifactCoords = arg;
                } else {
                    // Fallback to pomPath if it doesn't look like anything else
                    pomPath = arg;
                }
            }

            if (pomPath == null && artifactCoords == null && inputPath == null) {
                pomPath = "pom.xml";
            }

            String convertFormat = cmd.getOptionValue("convert-format");
            String outputPath = cmd.getOptionValue("output");
            String cachePath = cmd.getOptionValue("cache");
            String extraClasspathFile = cmd.getOptionValue("extra-classpath");
            String excludes = cmd.getOptionValue("exclude-cp");

            String scopesStr = cmd.getOptionValue("scopes", "runtime");
            boolean copyJars = !cmd.hasOption("no-copy");
            boolean classpathMode = cmd.hasOption("classpath");

            if (inputPath != null && convertFormat != null) {
                runConvert(inputPath, outputPath, convertFormat, classpathMode, cachePath, extraClasspathFile);
                return;
            }

            String destDir = cmd.getOptionValue("dest-dir");
            String reportPath = cmd.getOptionValue("report");


            boolean excludeSiblings = cmd.hasOption("exclude-siblings");
            boolean offline = cmd.hasOption("offline");
            boolean noCache = cmd.hasOption("no-cache");
 
            run(destDir, pomPath, artifactCoords, inputPath, outputPath, reportPath, cachePath, scopesStr, copyJars,
                    classpathMode,
                    extraClasspathFile, excludes, excludeSiblings, offline, noCache);

        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("maven-get-deps", options);
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String destDir, String pomPath, String artifactCoords, String inputPath, String outputPath,
            String reportPath,
            String cachePath,
            String scopesStr, boolean copyJars, boolean classpathMode,
            String extraClasspathFile, String excludes, boolean excludeSiblings, boolean offline, boolean noCache) throws Exception {

        Model model = null;
        RepositorySystem system = Bootstrapper.newRepositorySystem();

        String defaultM2 = System.getProperty("user.home") + "/.m2/repository";
        String sourceRepoPath = (cachePath != null) ? cachePath : defaultM2;

        String projectGroupId = null;
        if (pomPath != null) {
            projectGroupId = Bootstrapper.peekGroupId(new File(pomPath));
        } else if (artifactCoords != null) {
            DependencyFormatInfo info = FormatConverter.parse(artifactCoords);
            if (info != null) projectGroupId = info.groupId();
        }

        String sessionLocalRepoPath = (copyJars && destDir != null) ? destDir : sourceRepoPath;
        DefaultRepositorySystemSession session = (DefaultRepositorySystemSession) Bootstrapper.newRepositorySystemSession(system, sessionLocalRepoPath, offline);

        Bootstrapper.ReactorWorkspaceReader reactor = new Bootstrapper.ReactorWorkspaceReader();
        if (pomPath != null) {
            File pomFile = new File(pomPath);
            File rootPom = Bootstrapper.findReactorRoot(pomFile);
            if (rootPom != null) {
                System.out.println("Reactor root found: " + rootPom);
                Bootstrapper.registerReactor(rootPom, reactor);
            } else {
                reactor.registerPom(pomFile);
            }
        }
        session.setWorkspaceReader(reactor);

        java.util.Set<String> reactorGAs = reactor.getRegisteredGAs();

        if (projectGroupId != null) {
            java.util.Set<String> allowedRepos = new java.util.HashSet<>();
            allowedRepos.add("local-cache");
            ((DefaultRepositorySystemSession) session).setRepositoryListener(
                    new Bootstrapper.SiblingBlockerRepositoryListener(reactorGAs, allowedRepos));
        }

        if (outputPath != null) {
            // If we have an output path, we might want both listeners? 
            // Better to wrap or just use the blocker if we have a projectGroupId.
            // Actually, for simplicity, I'll just use the blocker.
        }

        // Always add Central
        List<RemoteRepository> repos = Bootstrapper.newRepositories(system, session, sourceRepoPath);

        Set<String> scopes = StreamUtil.splitToSet(scopesStr, ",");
        Set<String> excludeSet = DependencyResolverService.normalizeExcludes(excludes);

        if (artifactCoords != null) {
            DependencyFormatInfo info = FormatConverter.parse(artifactCoords);
            if (info == null || info.isLocal()) {
                throw new IllegalArgumentException("Invalid artifact coordinates: " + artifactCoords);
            }
            model = new Model();
            model.setGroupId("hr.hrg.maven.getdeps");
            model.setArtifactId("adhoc");
            model.setVersion("1.0.0");
            org.apache.maven.model.Dependency dep = new org.apache.maven.model.Dependency();
            dep.setGroupId(info.groupId());
            dep.setArtifactId(info.artifactId());
            dep.setVersion(info.version());
            dep.setClassifier(info.classifier());
            dep.setType(info.extension());
            model.addDependency(dep);
        } else if (inputPath != null) {
            model = modelFromDepsFile(inputPath);
        } else {
            File pomFile = new File(pomPath);
            if (!pomFile.exists()) {
                throw new IllegalArgumentException("POM file not found: " + pomPath);
            }
            model = Bootstrapper.resolveModel(pomFile, system, session, repos);
        }

        // Add additional repositories from the POM model
        repos.addAll(Bootstrapper.convertRepositories(model.getRepositories()));

        DependencyResolverService.ResolutionResult result = DependencyResolverService.resolve(
                system,
                session,
                repos,
                model.getDependencies(),
                Main::resolveProperty,
                model,
                scopes,
                excludeSet,
                projectGroupId,
                reactorGAs,
                excludeSiblings,
                noCache);
        
        if (outputPath != null) {
            try (PrintWriter writer = new PrintWriter(new File(outputPath))) {
                if (classpathMode) {
                    List<String> paths = StreamUtil.map(result.relativePaths, p -> new File(sourceRepoPath, p).getAbsolutePath());
                    if (extraClasspathFile != null) {
                        paths.addAll(java.nio.file.Files.readAllLines(new File(extraClasspathFile).toPath()));
                    }
                    writer.println(String.join(File.pathSeparator, paths));
                } else {
                    for (String path : result.relativePaths) {
                        writer.println(path);
                    }
                }
            }
            System.out.println("Output written to: " + outputPath);
        } else {
            if (classpathMode) {
                List<String> paths = StreamUtil.map(result.relativePaths, p -> new File(sourceRepoPath, p).getAbsolutePath());
                if (extraClasspathFile != null) {
                    paths.addAll(java.nio.file.Files.readAllLines(new File(extraClasspathFile).toPath()));
                }
                System.out.println(String.join(File.pathSeparator, paths));
            } else {
                for (String path : result.relativePaths) {
                    System.out.println(path);
                }
            }
        }

        if (reportPath != null) {
            if (model == null) {
                System.err.println("Warning: Size report cannot be generated from cache (model missing). Recalculating...");
                File pomFile = new File(pomPath);
                model = Bootstrapper.resolveModel(pomFile, system, session, repos);
                repos.addAll(Bootstrapper.convertRepositories(model.getRepositories()));
            }
            DependencyResolverService.ReportResult report = DependencyResolverService.resolveReport(
                    system, session, repos, model.getDependencies(), Main::resolveProperty, model, scopes, excludeSet, projectGroupId, reactorGAs, excludeSiblings);

            try (PrintWriter writer = new PrintWriter(new File(reportPath))) {
                writer.print(report.formatMarkdownTable());
            }
            System.out.println("Size report written to: " + reportPath);
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
            String cachePath, String extraClasspathFile) throws Exception {
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
            if (extraClasspathFile != null) {
                java.util.List<String> extras = java.nio.file.Files.readAllLines(new File(extraClasspathFile).toPath());
                if (!extras.isEmpty()) {
                    combined += File.pathSeparator + String.join(File.pathSeparator, extras);
                }
            }
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

    private static Model modelFromDepsFile(String path) throws java.io.IOException {
        Model model = new Model();
        model.setGroupId("hr.hrg.maven.getdeps");
        model.setArtifactId("adhoc-file");
        model.setVersion("1.0.0");

        List<String> lines = java.nio.file.Files.readAllLines(new File(path).toPath());
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;

            DependencyFormatInfo info = FormatConverter.parse(line);
            if (info != null && !info.isLocal()) {
                org.apache.maven.model.Dependency dep = new org.apache.maven.model.Dependency();
                dep.setGroupId(info.groupId());
                dep.setArtifactId(info.artifactId());
                dep.setVersion(info.version());
                dep.setClassifier(info.classifier());
                dep.setType(info.extension());
                model.addDependency(dep);
            }
        }
        return model;
    }
}
