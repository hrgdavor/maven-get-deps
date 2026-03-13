# Thin Docker Images with maven-get-deps

While the author generally prefers simpler deployment models (like shared network drives), combining the Zig version of `maven-get-deps` with Docker allows for extremely thin and efficient container images.

## The Problem with Fat JARs in Docker

Standard Docker practices often involve building a "fat JAR" (using Maven Shade or Spring Boot) and copying it into an image. This has several downsides:
1.  **Bloated Layers**: Every change to your code requires re-uploading the entire JAR (including all 50MB-100MB of static dependencies).
2.  **Memory Overhead**: Fat JARs can be slower to start and harder for GraalVM to optimize.
3.  **No Deduplication**: If you have 10 microservices sharing 90% of the same libraries, Docker will store those libraries 10 times.

## The "Thin Image" Strategy

Instead of building a fat JAR, we keep the dependencies in a separate volume/cache and use `maven-get-deps` to assemble the classpath at runtime.

### 1. Local Development (Bind-Mount)

In local development, you can mount your local `.m2` repository directly into the container.

**Dockerfile.local**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the Zig tool and dependency list
COPY target/get_deps /usr/local/bin/
COPY dependencies.txt .
COPY target/my-app.jar .

# Use a startup script to generate CLASSPATH on the fly
CMD CP_STRING=$(get_deps -i dependencies.txt -cf path --classpath --cache /root/.m2/repository) && \
    export CLASSPATH="my-app.jar:$CP_STRING" && \
    java com.example.Main
```

**Run command:**
```bash
docker run -v ~/.m2/repository:/root/.m2/repository my-app-local
```

### 2. Production Deployment (Permissive Environment)

In environments where the container has internet access, you can allow the Zig tool to fill a persistent volume cache from inside the container.

**Dockerfile.prod**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/get_deps /usr/local/bin/
COPY dependencies.txt .
COPY target/my-app.jar .

# Entrypoint script that ensures deps are present before starting
COPY entrypoint.sh .
RUN chmod +x entrypoint.sh
ENTRYPOINT ["./entrypoint.sh"]
```

**entrypoint.sh**
```sh
#!/bin/sh
# 1. Download missing jars into the mounted cache volume
get_deps -i dependencies.txt --download --cache /data/maven-cache

# 2. Generate classpath
CP_STRING=$(get_deps -i dependencies.txt -cf path --classpath --cache /data/maven-cache)

# 3. Exec java
export CLASSPATH="my-app.jar:$CP_STRING"
exec java com.example.Main
```

---

## Kubernetes Integration (The "Pre-fetch" Pattern)

In highly secure Kubernetes environments, application containers are often restricted from writing to disks or accessing the public internet.

### Suggestion: InitContainer for Dependency Resolution

Use a dedicated `InitContainer` to handle dependency fetching. This keeps your main application container strictly "Read-Only" and avoids including the downloader tool in the final runtime image.

**Kubernetes Deployment Snippet:**
```yaml
spec:
  volumes:
    - name: maven-cache-vol
      persistentVolumeClaim:
        claimName: shared-maven-cache
  
  initContainers:
    - name: dep-fetcher
      image: alpine:latest # Or a custom image with the Zig binary
      volumeMounts:
        - name: maven-cache-vol
          mountPath: /data/cache
      command: ["/bin/sh", "-c"]
      args:
        - |
          # The dependencies.txt would be provided via ConfigMap or inside the image
          /usr/local/bin/get_deps --input /app/dependencies.txt --download --cache /data/cache
  
  containers:
    - name: my-java-app
      image: my-thin-app-image:latest
      volumeMounts:
        - name: maven-cache-vol
          mountPath: /data/cache
          readOnly: true # App container cannot modify the cache
      env:
        - name: JAVA_OPTS
          value: "-Dskip.something=true"
      command: ["/bin/sh", "-c"]
      args:
        - |
          # Generate CP string using the Zig tool (also included in the app image)
          CP=$(/usr/local/bin/get_deps -i /app/dependencies.txt -cf path --classpath --cache /data/cache)
          export CLASSPATH="/app/my-app.jar:$CP"
          exec java $JAVA_OPTS com.example.Main
```

### Benefits of this K8s pattern:
1.  **Security**: Only the `InitContainer` needs internet access. The `AppContainer` can be fully isolated.
2.  **Immutability**: The shared cache ensures that all pods in a replica set use the exact same JAR files.
3.  **Efficiency**: JARs are downloaded once per node (if using `ReadWriteMany` or local node caches) and shared across all pods.
