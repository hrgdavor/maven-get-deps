# Maven Artifact Layout & Regex Conversions

The Maven repository structure is designed for simple, predictable mapping between artifact coordinates and filesystem paths. Understanding this pattern is key for mastering the tool's `gen-index` and `deps` commands, and understanding value such convention brings in maintaining a maven central repository, local cache, or a shared library folder for multiple application versions( even different applications).

## The Directory Pattern
A Maven artifact follows this fixed hierarchy:
`[groupId (dots to slashes)] / [artifactId] / [version] / [artifactId]-[version][-classifier].[extension]`

**Example:**
- **Colon Format:** `org.slf4j:slf4j-api:2.0.7`
- **Path Format:** `org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar`

## Regex Visualisation
You can use Regex to convert between these formats in your own automation scripts.

### 1. Path to Colon (JavaScript)
```javascript
const path = "org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar";
// Pattern: group/id/artifact/version/artifact-version[-classifier].ext
const regex = /^(.+)\/([^\/]+)\/([^\/]+)\/\2-\3(?:-([^\.]+))?\.(.+)$/;

const colon = path.replace(regex, (m, g, a, v, c, e) => {
  const groupId = g.replace(/\//g, '.');
  const classifier = c ? `:${c}` : '';
  const extension = e !== 'jar' ? `@${e}` : '';
  return `${groupId}:${a}:${v}${classifier}${extension}`;
});
// Result: "org.slf4j:slf4j-api:2.0.7"
```

### 2. Path to Colon (Java)
```java
String path = "org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar";
Pattern p = Pattern.compile("^(.+)/([^/]+)/([^/]+)/\\2-\\3(?:-([^.]+))?\\.(.+)$");
Matcher m = p.matcher(path);

if (m.find()) {
    String groupId = m.group(1).replace('/', '.');
    String artifactId = m.group(2);
    String version = m.group(3);
    String classifier = m.group(4);
    String extension = m.group(5);
    
    String colon = String.format("%s:%s:%s%s%s", 
        groupId, artifactId, version,
        classifier != null ? ":" + classifier : "",
        !"jar".equals(extension) ? "@" + extension : ""
    );
}
```

### 3. Colon to Path (JavaScript)
```javascript
const colon = "org.slf4j:slf4j-api:2.0.7";
const regex = /^([^:]+):([^:]+):([^:]+)(?::([^@]+))?(?:@(.+))?$/;

const path = colon.replace(regex, (m, g, a, v, c, e) => {
  const groupId = g.replace(/\./g, '/');
  const classifier = c ? `-${c}` : '';
  const ext = e || 'jar';
  return `${groupId}/${a}/${v}/${a}-${v}${classifier}.${ext}`;
});
// Result: "org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar"
```

#### 4. Colon to Path (Java)
```java
String colon = "org.slf4j:slf4j-api:2.0.7";
Pattern p = Pattern.compile("^([^:]+):([^:]+):([^:]+)(?::([^@]+))?(?:@(.+))?$");
Matcher m = p.matcher(colon);

if (m.find()) {
    String groupId = m.group(1).replace('.', '/');
    String artifactId = m.group(2);
    String version = m.group(3);
    String classifier = m.group(4);
    String extension = m.group(5) != null ? m.group(5) : "jar";

    String path = String.format("%s/%s/%s/%s-%s%s.%s",
        groupId, artifactId, version,
        artifactId, version,
        classifier != null ? "-" + classifier : "",
        extension
    );
}
```
