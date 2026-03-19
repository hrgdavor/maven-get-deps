package hr.hrg.maven.getdeps;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.supplier.RepositorySystemSupplier;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.aether.resolution.ArtifactDescriptorPolicy;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import java.util.HashMap;
import java.util.Map;

public class Bootstrapper {

    public static RepositorySystem newRepositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    public static RepositorySystemSession newRepositorySystemSession(RepositorySystem system, String localRepoPath) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        LocalRepository localRepo = new LocalRepository(localRepoPath);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

        session.setSystemProperties(System.getProperties());
        session.setCache(new DefaultRepositoryCache());
        session.setIgnoreArtifactDescriptorRepositories(true);
        session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(ArtifactDescriptorPolicy.IGNORE_MISSING | ArtifactDescriptorPolicy.IGNORE_ERRORS));

        return session;
    }

    public static class ReactorWorkspaceReader implements WorkspaceReader {
        private final WorkspaceRepository repository = new WorkspaceRepository("reactor");
        private final Map<String, File> pomMap = new HashMap<>();
        private final Map<String, File> gaMap = new HashMap<>();
        private final java.util.Set<String> gaSet = new java.util.HashSet<>();

        public void registerPom(File pomFile) {
            try {
                javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
                org.w3c.dom.Document doc = builder.parse(pomFile);
                org.w3c.dom.Element root = doc.getDocumentElement();

                String groupId = getChild(root, "groupId");
                String artifactId = getChild(root, "artifactId");
                String version = getChild(root, "version");

                if (groupId == null || version == null) {
                    org.w3c.dom.Node parentNode = getChildNode(root, "parent");
                    if (parentNode != null) {
                        if (groupId == null) groupId = getChild(parentNode, "groupId");
                        if (version == null) version = getChild(parentNode, "version");
                    }
                }

                if (groupId != null && artifactId != null) {
                    String ga = groupId + ":" + artifactId;
                    gaMap.put(ga, pomFile);
                    gaSet.add(ga);
                    if (version != null) {
                        String key = ga + ":" + version;
                        pomMap.put(key, pomFile);
                        // System.out.println("Registered reactor module: " + key + " -> " + pomFile);
                    } else {
                        // System.out.println("Registered reactor module (no version): " + ga + " -> " + pomFile);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }

        private String getChild(org.w3c.dom.Node node, String localName) {
            org.w3c.dom.Node child = getChildNode(node, localName);
            return child == null ? null : child.getTextContent().trim();
        }

        private org.w3c.dom.Node getChildNode(org.w3c.dom.Node node, String localName) {
            org.w3c.dom.NodeList nl = node.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                org.w3c.dom.Node n = nl.item(i);
                if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    String name = n.getNodeName();
                    int idx = name.indexOf(':');
                    if (idx != -1) name = name.substring(idx + 1);
                    if (localName.equals(name)) return n;
                }
            }
            return null;
        }

        public java.util.Set<String> getRegisteredGAs() {
            return gaSet;
        }

        @Override
        public WorkspaceRepository getRepository() {
            return repository;
        }

        @Override
        public File findArtifact(org.eclipse.aether.artifact.Artifact artifact) {
            String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            File pomFile = pomMap.get(key);
            if (pomFile == null) {
                // Fallback to GA only (useful if version is a property in the POM)
                pomFile = gaMap.get(artifact.getGroupId() + ":" + artifact.getArtifactId());
            }

            if (pomFile == null) {
                // System.out.println("  Reactor NOT found: " + artifact);
                return null;
            }
            // System.out.println("  Reactor found artifact: " + artifact + " -> " + pomFile);

            if ("pom".equals(artifact.getExtension())) return pomFile;

            // For JARs and other types, look in target/
            String classifier = artifact.getClassifier();
            String filename = artifact.getArtifactId() + "-" + artifact.getVersion() +
                (classifier != null && !classifier.isEmpty() ? "-" + classifier : "") +
                "." + artifact.getExtension();
            File targetFile = new File(new File(pomFile.getParentFile(), "target"), filename);
            if (targetFile.exists()) return targetFile;

            // Fallback to target/classes if JAR not found (useful for siblings not yet packaged)
            File classesDir = new File(new File(pomFile.getParentFile(), "target"), "classes");
            if (classesDir.exists() && classesDir.isDirectory()) return classesDir;

            return null;
        }

        @Override
        public List<String> findVersions(org.eclipse.aether.artifact.Artifact artifact) {
            String key = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            if (pomMap.containsKey(key)) {
                return java.util.Collections.singletonList(artifact.getVersion());
            }
            // Fallback to GA only
            if (gaMap.containsKey(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                return java.util.Collections.singletonList(artifact.getVersion());
            }
            // System.out.println("  Reactor NOT found version: " + artifact);
            return java.util.Collections.emptyList();
        }
    }

    public static class LoggingRepositoryListener extends org.eclipse.aether.AbstractRepositoryListener {
        @Override
        public void artifactDownloaded(org.eclipse.aether.RepositoryEvent event) {
            System.out.println("Downloaded: " + event.getArtifact() + " from " + event.getRepository());
        }

        @Override
        public void metadataDownloaded(org.eclipse.aether.RepositoryEvent event) {
            System.out.println("Downloaded metadata: " + event.getMetadata() + " from " + event.getRepository());
        }
    }

    public static org.apache.maven.model.Model resolveModel(File pomFile, RepositorySystem system,
            RepositorySystemSession session, List<RemoteRepository> repos) {
        org.apache.maven.model.building.ModelBuildingRequest request = new org.apache.maven.model.building.DefaultModelBuildingRequest();
        request.setPomFile(pomFile);
        request.setProcessPlugins(false);
        request.setValidationLevel(org.apache.maven.model.building.ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setSystemProperties(System.getProperties());
        request.setModelResolver(new AetherModelResolver(system, session, repos));

        org.apache.maven.model.building.ModelBuilder builder = new org.apache.maven.model.building.DefaultModelBuilderFactory()
                .newInstance();
        try {
            return builder.build(request).getEffectiveModel();
        } catch (org.apache.maven.model.building.ModelBuildingException e) {
            throw new RuntimeException("Error building effective model for " + pomFile, e);
        }
    }

    private static class AetherModelResolver implements org.apache.maven.model.resolution.ModelResolver {
        private final RepositorySystem system;
        private final RepositorySystemSession session;
        private final List<RemoteRepository> repositories;

        public AetherModelResolver(RepositorySystem system, RepositorySystemSession session,
                List<RemoteRepository> repositories) {
            this.system = system;
            this.session = session;
            this.repositories = repositories;
        }

        @Override
        public org.apache.maven.model.building.ModelSource resolveModel(String groupId, String artifactId,
                String version)
                throws org.apache.maven.model.resolution.UnresolvableModelException {
            org.eclipse.aether.artifact.Artifact pomArtifact = new org.eclipse.aether.artifact.DefaultArtifact(groupId,
                    artifactId, "", "pom", version);
            try {
                org.eclipse.aether.resolution.ArtifactRequest request = new org.eclipse.aether.resolution.ArtifactRequest(
                        pomArtifact, repositories, null);
                org.eclipse.aether.resolution.ArtifactResult result = system.resolveArtifact(session, request);
                return new org.apache.maven.model.building.FileModelSource(result.getArtifact().getFile());
            } catch (org.eclipse.aether.resolution.ArtifactResolutionException e) {
                throw new org.apache.maven.model.resolution.UnresolvableModelException(e.getMessage(), groupId,
                        artifactId, version, e);
            }
        }

        @Override
        public org.apache.maven.model.building.ModelSource resolveModel(org.apache.maven.model.Parent parent)
                throws org.apache.maven.model.resolution.UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public org.apache.maven.model.building.ModelSource resolveModel(org.apache.maven.model.Dependency dependency)
                throws org.apache.maven.model.resolution.UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(org.apache.maven.model.Repository repository)
                throws org.apache.maven.model.resolution.InvalidRepositoryException {
            // Not implemented for this tool
        }

        @Override
        public void addRepository(org.apache.maven.model.Repository repository, boolean replace)
                throws org.apache.maven.model.resolution.InvalidRepositoryException {
            // Not implemented for this tool
        }

        @Override
        public org.apache.maven.model.resolution.ModelResolver newCopy() {
            return new AetherModelResolver(system, session, repositories);
        }
    }

    public static String resolveProperty(String value, org.apache.maven.model.Model model) {
        if (value == null || !value.contains("${"))
            return value;
        if ("${project.version}".equals(value) || "${version}".equals(value))
            return model.getVersion();
        if ("${project.groupId}".equals(value) || "${groupId}".equals(value)) {
            String gid = model.getGroupId();
            if (gid == null && model.getParent() != null) gid = model.getParent().getGroupId();
            return gid;
        }
        if ("${project.artifactId}".equals(value) || "${artifactId}".equals(value))
            return model.getArtifactId();

        // Simple property lookup
        if (value.startsWith("${") && value.endsWith("}")) {
            String propName = value.substring(2, value.length() - 1);
            String propValue = model.getProperties().getProperty(propName);
            if (propValue != null)
                return propValue;
        }

        return value;
    }

    public static File findReactorRoot(File pomFile) {
        File currentPom = pomFile.getAbsoluteFile();
        File lastValidPom = currentPom;
        while (currentPom != null && currentPom.exists()) {
            lastValidPom = currentPom;
            File parentDir = currentPom.getParentFile();
            if (parentDir == null) break;
            File grandDir = parentDir.getParentFile();
            if (grandDir == null) break;
            currentPom = new File(grandDir, "pom.xml");
        }
        return lastValidPom;
    }

    public static void registerReactor(File rootPom, ReactorWorkspaceReader reactor) {
        reactor.registerPom(rootPom);
        File rootDir = rootPom.getParentFile();
        if (rootDir != null) {
            registerModules(rootDir, reactor);
        }
    }

    private static void registerModules(File dir, ReactorWorkspaceReader reactor) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                File pom = new File(child, "pom.xml");
                if (pom.exists()) {
                    reactor.registerPom(pom);
                    registerModules(child, reactor);
                }
            }
        }
    }

    public static String peekGroupId(File pomFile) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(pomFile);
            org.w3c.dom.Element root = doc.getDocumentElement();
            
            // Try direct groupId
            org.w3c.dom.NodeList nl = root.getChildNodes();
            for(int i=0; i<nl.getLength(); i++) {
                org.w3c.dom.Node n = nl.item(i);
                if("groupId".equals(n.getNodeName())) return n.getTextContent().trim();
            }
            // Try parent groupId
            for(int i=0; i<nl.getLength(); i++) {
                org.w3c.dom.Node n = nl.item(i);
                if("parent".equals(n.getNodeName())) {
                    org.w3c.dom.NodeList pnl = n.getChildNodes();
                    for(int j=0; j<pnl.getLength(); j++) {
                        org.w3c.dom.Node pn = pnl.item(j);
                        if("groupId".equals(pn.getNodeName())) return pn.getTextContent().trim();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public static List<RemoteRepository> newRepositories(RepositorySystem system, RepositorySystemSession session) {
        return newRepositories(system, session, null);
    }

    public static List<RemoteRepository> newRepositories(RepositorySystem system, RepositorySystemSession session,
            String cachePath) {
        List<RemoteRepository> repose = new ArrayList<>();
        RepositoryPolicy policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        RepositoryPolicy snapshotPolicy = new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        repose.add(new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/")
                .setReleasePolicy(policy)
                .setSnapshotPolicy(snapshotPolicy)
                .build());
        return repose;
    }

    public static class SiblingBlockerRepositoryListener extends org.eclipse.aether.AbstractRepositoryListener {
        private final Set<String> reactorGAs;
        private final Set<String> allowedRepoIds;

        public SiblingBlockerRepositoryListener(Set<String> reactorGAs, Set<String> allowedRepoIds) {
            this.reactorGAs = reactorGAs;
            this.allowedRepoIds = allowedRepoIds;
        }

        @Override
        public void artifactDownloading(org.eclipse.aether.RepositoryEvent event) {
            String ga = event.getArtifact().getGroupId() + ":" + event.getArtifact().getArtifactId();
            if (reactorGAs != null && reactorGAs.contains(ga)) {
                if (event.getRepository() != null && !allowedRepoIds.contains(event.getRepository().getId())) {
                    System.err.println("BLOCKING download of sibling from " + event.getRepository().getId() + ": " + event.getArtifact());
                }
            }
        }
    }

    public static List<RemoteRepository> convertRepositories(List<org.apache.maven.model.Repository> repositories) {
        List<RemoteRepository> result = new ArrayList<>();
        RepositoryPolicy policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        RepositoryPolicy snapshotPolicy = new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        for (org.apache.maven.model.Repository repo : repositories) {
            result.add(new RemoteRepository.Builder(repo.getId(), repo.getLayout(), repo.getUrl())
                    .setReleasePolicy(policy)
                    .setSnapshotPolicy(snapshotPolicy)
                    .build());
        }
        return result;
    }
}
