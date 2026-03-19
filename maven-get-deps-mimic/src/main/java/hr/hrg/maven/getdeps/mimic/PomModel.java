package hr.hrg.maven.getdeps.mimic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PomModel {
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private String groupId;
    private String artifactId;
    private String version;
    private Parent parent;
    private Map<String, Object> properties = new HashMap<>();
    private DependencyManagement dependencyManagement;
    private DependencyContainer dependencies;
    private RepositoryContainer repositories;

    public static PomModel parse(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> map = XmlToJsonConverter.convert(fis);
            // The root is usually "project"
            Object project = map.get("project");
            if (project == null) project = map; // fallback if root is not "project"
            return MAPPER.convertValue(project, PomModel.class);
        }
    }

    @JsonProperty("groupId")
    public void setGroupId(Object obj) { this.groupId = extractText(obj); }
    public String getGroupId() { 
        if (groupId != null) return groupId;
        if (parent != null) return parent.getGroupId();
        return null; 
    }

    @JsonProperty("artifactId")
    public void setArtifactId(Object obj) { this.artifactId = extractText(obj); }
    public String getArtifactId() { return artifactId; }

    @JsonProperty("version")
    public void setVersion(Object obj) { this.version = extractText(obj); }
    public String getVersion() { 
        if (version != null) return version;
        if (parent != null) return parent.getVersion();
        return null; 
    }

    public Parent getParent() { return parent; }
    public void setParent(Parent parent) { this.parent = parent; }

    @JsonProperty("properties")
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
    public Map<String, Object> getProperties() { return properties; }

    public DependencyManagement getDependencyManagement() { return dependencyManagement; }
    public void setDependencyManagement(DependencyManagement dependencyManagement) { this.dependencyManagement = dependencyManagement; }

    public DependencyContainer getDependencies() { return dependencies; }
    public void setDependencies(DependencyContainer dependencies) { this.dependencies = dependencies; }

    public RepositoryContainer getRepositories() { return repositories; }
    public void setRepositories(RepositoryContainer repositories) { this.repositories = repositories; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DependencyManagement {
        private DependencyContainer dependencies;
        @JsonProperty("dependencies")
        public void setDependencies(DependencyContainer dependencies) { this.dependencies = dependencies; }
        public DependencyContainer getDependencies() { return dependencies; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parent {
        private String groupId;
        private String artifactId;
        private String version;
        private String relativePath;
        
        @JsonProperty("groupId")
        public void setGroupId(Object obj) { this.groupId = extractText(obj); }
        public String getGroupId() { return groupId; }

        @JsonProperty("artifactId")
        public void setArtifactId(Object obj) { this.artifactId = extractText(obj); }
        public String getArtifactId() { return artifactId; }

        @JsonProperty("version")
        public void setVersion(Object obj) { this.version = extractText(obj); }
        public String getVersion() { return version; }

        @JsonProperty("relativePath")
        public void setRelativePath(Object obj) { this.relativePath = extractText(obj); }
        public String getRelativePath() { return relativePath; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DependencyContainer {
        private List<Dependency> dependencyList = new ArrayList<>();

        @JsonProperty("dependency")
        public void setDependency(Object obj) {
            if (obj instanceof List) {
                for (Object item : (List<?>) obj) {
                    if (item instanceof Map) {
                        dependencyList.add(MAPPER.convertValue(item, Dependency.class));
                    }
                }
            } else if (obj instanceof Map) {
                dependencyList.add(MAPPER.convertValue(obj, Dependency.class));
            }
        }

        public List<Dependency> getDependencyList() {
            return dependencyList;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Dependency {
        private String groupId;
        private String artifactId;
        private String version;
        private String scope = "compile";
        private String type = "jar";
        private String classifier;
        private String optional;
        private ExclusionContainer exclusions;

        @JsonProperty("groupId")
        public void setGroupId(Object obj) { this.groupId = extractText(obj); }
        public String getGroupId() { return groupId; }

        @JsonProperty("artifactId")
        public void setArtifactId(Object obj) { this.artifactId = extractText(obj); }
        public String getArtifactId() { return artifactId; }

        @JsonProperty("version")
        public void setVersion(Object obj) { this.version = extractText(obj); }
        public String getVersion() { return version; }

        @JsonProperty("scope")
        public void setScope(Object obj) { 
            this.scope = extractText(obj); 
        }
        public String getScope() { return scope; }

        @JsonProperty("type")
        public void setType(Object obj) { this.type = extractText(obj); }
        public String getType() { return type; }

        @JsonProperty("classifier")
        public void setClassifier(Object obj) { this.classifier = extractText(obj); }
        public String getClassifier() { return classifier; }

        @JsonProperty("optional")
        public void setOptional(Object obj) { this.optional = extractText(obj); }
        public String getOptional() { return optional; }

        @JsonProperty("exclusions")
        public void setExclusions(ExclusionContainer exclusions) { this.exclusions = exclusions; }
        public ExclusionContainer getExclusions() { return exclusions; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExclusionContainer {
        private List<Exclusion> exclusionList = new ArrayList<>();

        @JsonProperty("exclusion")
        public void setExclusion(Object obj) {
            if (obj instanceof List) {
                for (Object item : (List<?>) obj) {
                    if (item instanceof Map) {
                        exclusionList.add(MAPPER.convertValue(item, Exclusion.class));
                    }
                }
            } else if (obj instanceof Map) {
                exclusionList.add(MAPPER.convertValue(obj, Exclusion.class));
            }
        }

        public List<Exclusion> getExclusionList() {
            return exclusionList;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Exclusion {
        private String groupId;
        private String artifactId;

        @JsonProperty("groupId")
        public void setGroupId(Object obj) { this.groupId = extractText(obj); }
        public String getGroupId() { return groupId; }

        @JsonProperty("artifactId")
        public void setArtifactId(Object obj) { this.artifactId = extractText(obj); }
        public String getArtifactId() { return artifactId; }
    }

    public static String extractText(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        if (obj instanceof Map) {
            Object text = ((Map<?, ?>) obj).get("_text");
            return text != null ? text.toString() : null;
        }
        return obj.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepositoryContainer {
        private List<Repository> repositoryList = new ArrayList<>();

        @JsonProperty("repository")
        public void setRepository(Object obj) {
            if (obj instanceof List) {
                for (Object item : (List<?>) obj) {
                    if (item instanceof Map) {
                        repositoryList.add(MAPPER.convertValue(item, Repository.class));
                    }
                }
            } else if (obj instanceof Map) {
                repositoryList.add(MAPPER.convertValue(obj, Repository.class));
            }
        }

        public List<Repository> getRepositoryList() {
            return repositoryList;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private String id;
        private String url;

        @JsonProperty("id")
        public void setId(Object obj) { this.id = extractText(obj); }
        public String getId() { return id; }

        @JsonProperty("url")
        public void setUrl(Object obj) { this.url = extractText(obj); }
        public String getUrl() { return url; }
    }
}
