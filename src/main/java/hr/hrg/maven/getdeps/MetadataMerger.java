package hr.hrg.maven.getdeps;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility to merge GraalVM reachability-metadata.json files.
 * Prefer entries from the new file in case of overlap.
 */
public class MetadataMerger {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java hr.hrg.maven.getdeps.MetadataMerger <old_file> <new_file> <output_file>");
            System.exit(1);
        }

        try {
            merge(new File(args[0]), new File(args[1]), new File(args[2]));
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void merge(File oldFile, File newFile, File outputFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        JsonNode oldRoot = (oldFile.exists() && oldFile.length() > 0) ? mapper.readTree(oldFile)
                : mapper.createObjectNode();
        JsonNode newRoot = mapper.readTree(newFile);

        ObjectNode mergedRoot = mapper.createObjectNode();

        // Merge reflection
        mergedRoot.set("reflection", mergeArrays(mapper, oldRoot.get("reflection"), newRoot.get("reflection"), "type"));

        // Merge resources
        mergedRoot.set("resources", mergeResources(mapper, oldRoot.get("resources"), newRoot.get("resources")));

        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        mapper.writeValue(outputFile, mergedRoot);
        System.out.println("Successfully merged metadata into " + outputFile.getAbsolutePath());
    }

    private static ArrayNode mergeArrays(ObjectMapper mapper, JsonNode oldArr, JsonNode newArr, String keyField) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (oldArr != null && oldArr.isArray()) {
            for (JsonNode node : oldArr) {
                if (node.has(keyField)) {
                    map.put(node.get(keyField).asText(), node);
                }
            }
        }
        if (newArr != null && newArr.isArray()) {
            for (JsonNode node : newArr) {
                if (node.has(keyField)) {
                    map.put(node.get(keyField).asText(), node);
                }
            }
        }
        ArrayNode result = mapper.createArrayNode();
        List<String> sortedKeys = new ArrayList<>(map.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) {
            result.add(map.get(key));
        }
        return result;
    }

    private static ArrayNode mergeResources(ObjectMapper mapper, JsonNode oldArr, JsonNode newArr) {
        Map<String, JsonNode> globMap = new LinkedHashMap<>();
        List<JsonNode> otherResources = new ArrayList<>();

        if (oldArr != null && oldArr.isArray()) {
            for (JsonNode node : oldArr) {
                if (node.has("glob")) {
                    globMap.put(node.get("glob").asText(), node);
                } else {
                    otherResources.add(node);
                }
            }
        }
        if (newArr != null && newArr.isArray()) {
            for (JsonNode node : newArr) {
                if (node.has("glob")) {
                    globMap.put(node.get("glob").asText(), node);
                } else {
                    otherResources.add(node);
                }
            }
        }

        ArrayNode result = mapper.createArrayNode();
        List<String> sortedGlobs = new ArrayList<>(globMap.keySet());
        Collections.sort(sortedGlobs);
        for (String glob : sortedGlobs) {
            result.add(globMap.get(glob));
        }
        for (JsonNode node : otherResources) {
            result.add(node);
        }
        return result;
    }
}
