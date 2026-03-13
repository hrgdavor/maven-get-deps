# Static Classpath Deployment for Docker

For highly optimized production environments, you can generate a **static CLASSPATH string** during your build phase and bake it directly into your `Dockerfile` or a startup script. 

This approach is the most efficient because:
1.  **Zero Runtime Overhead**: No tools (Zig or Java) need to run at container startup to resolve or download dependencies.
2.  **Immutability**: The classpath is fixed at build time, ensuring the exact same environment across all deployments.
3.  **Security**: You don't need the `maven-get-deps` binary or Maven repository access inside your production container.

## Strategy: Build-Time Generation

In this model, your CI/CD pipeline (e.g., GitHub Actions, Jenkins) or local build script performs the resolution and generates a file containing the final classpath string.

### 1. Simple Project

If you have a single JAR and want to use dependencies from a mounted volume:

**Build Phase:**
```bash
# Generate dependencies.txt
mvn hr.hrg:maven-get-deps-plugin:get-deps -Doutput=dependencies.txt

# Generate the static classpath string
# (Assumes dependencies are in /libs inside the container)
get_deps -i dependencies.txt -cf path --classpath --cache /libs > static_cp.txt
```

**Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/my-app.jar .
COPY static_cp.txt .

# Start the app using the pre-baked classpath
CMD java -cp "my-app.jar:$(cat static_cp.txt)" com.example.Main
```

---

## Multi-Module Projects & Extra Classpath

In multi-module projects, you often have local JARs (from other modules) that aren't in a Maven repository yet. You can use the `--extra-classpath` flag to merge these local paths into your final classpath.

### Example: Multi-Module Setup

Imagine a project with:
- `core/target/core.jar`
- `web/target/web.jar`
- External dependencies from Maven.

**1. Create `local_modules.txt`:**
```text
/app/core.jar
/app/web.jar
```

**2. Generate combined classpath at build time:**
```bash
# Resolve external deps from one of the modules
get_deps -i web/dependencies.txt -cf path --classpath --cache /libs --extra-classpath local_modules.txt > full_cp.txt
```

**3. Dockerfile:**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy all local artifacts
COPY core/target/core.jar .
COPY web/target/web.jar .
# Copy the pre-generated full classpath
COPY full_cp.txt .

CMD java -cp "$(cat full_cp.txt)" com.example.MainWeb
```

## When to use this strategy?
- **Production**: When stability and startup speed are critical.
- **Security-Hardened Environments**: When you want minimize the tools installed in the image.
- **Large-Scale Deployments**: When thousands of pods starting at once would otherwise hammer a shared cache or network drive.

---

## Kubernetes Integration

When using a static classpath in Kubernetes, your Pod definition becomes extremely simple and highly secure. The application container runs entirely in read-only mode, mounting a pre-populated shared Maven cache (or using an InitContainer from a utility image to populate it).

**Kubernetes Deployment Snippet:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-static-app
spec:
  replicas: 3
  template:
    spec:
      volumes:
        - name: shared-maven-cache
          persistentVolumeClaim:
            claimName: maven-repo-pvc
      
      # Optional: Use a generic InitContainer to download dependencies if they aren't pre-populated 
      # initContainers:
      #   - name: dep-fetcher
      #     image: custom-zig-tool-image:latest
      #     volumeMounts:
      #       - name: shared-maven-cache
      #         mountPath: /libs
      #     command: ["get_deps", "-i", "/config/dependencies.txt", "--download", "--cache", "/libs"]
      
      containers:
        - name: my-java-app
          image: my-static-app-image:latest
          volumeMounts:
            - name: shared-maven-cache
              mountPath: /libs
              readOnly: true # The application cannot modify the dependencies
          securityContext:
            readOnlyRootFilesystem: true # Maximum security, since CP is static and deps are read-only
```

### Benefits for Kubernetes:
1. **Ultra-Fast Pod Startup**: No dynamic classpath generation or dependency downloads on the critical path of the application container.
2. **Maximum Security**: The application container (`my-static-app-image`) contains only the JRE, your thin JAR, and `static_cp.txt`. It does not contain `get_deps` and can run with a read-only root filesystem.
3. **Immutability**: Every replica starts with the exact same pre-computed classpath.
