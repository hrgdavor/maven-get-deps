# Systemd Deployment Guide (Multi-Module Daemon)

This guide demonstrates how to deploy a Java daemon using `systemd` and `maven-get-deps` for lean, zero-downtime-ready management. We'll use the [examples/multi](../examples/multi) project as our reference.

## 1. Project Structure
The example project consists of:
- `odt-lib`: A library for ODT generation.
- `md-to-odt`: The daemon watching a folder for Markdown files.

## 2. Deployment Structure
A production-ready version folder (e.g., `/opt/md-to-odt/v1.0.0`) must be self-contained and portable. It should contain:

| File | Description |
| :--- | :--- |
| `md-to-odt.jar` | The main application JAR. |
| `odt-lib.jar` | The local library JAR (not yet in Central/Nexus). |
| `cp.txt` | Full list of dependencies produced by `maven-get-deps`. |

> [!NOTE]
> `logback.xml` and other shared assets (e.g., templates, global configs) should be kept in the deployment root (e.g., `/opt/md-to-odt/`) rather than inside specific version folders. This allows configuration to be decoupled from code and shared across upgrades.

#### Why `extra.cp.txt`?
When deploying a project with multiple local modules, some JARs might not be available in a shared Maven repository yet. We maintain `extra.cp.txt` in the project folder to define these "extra" classpath entries. The Maven plugin then merges them with the resolved remote dependencies when generating the final `cp.txt`.

 > [!NOTE] **java will ignore non-existing classpath entries**, so you can add both your jar and classes folder to classpath, then you can switch them when testing code on remote server, just make sure both are not available at the same time. It is faster to sync just classes than wait for jar to be built, if you for some reason need to iterate on something that is easier to do on a remote server than locally (some resources may be hard to get to in local env).


## 3. Generating the Classpath (Manual)
If you are assembling the folder manually, you can use the `maven_get_deps` tool to generate a stable `cp.txt`.

```bash
# Example: Generate the classpath for the md-to-odt module
./maven_get_deps deps -i md-to-odt/pom.xml --extra-classpath md-to-odt/extra.cp.txt --classpath > /opt/md-to-odt/v1.0.0/cp.txt
```

## 4. Automated Staging
Instead of manually assembling the version folder, we use the `stage-deploy` Maven profile in `md-to-odt/pom.xml`.

```bash
# From examples/multi
mvn clean package -Pstage-deploy -DskipTests
```

This profile automates:
1. **Packaging**: Builds `odt-lib` and `md-to-odt`.
2. **Assembly**: Creates `target/v1.0.0/` and copies all local artifacts and `logback.xml`.
3. **Classpath Generation**: Uses the `maven-get-deps-maven-plugin` with the `extra-cp` parameter to resolve remote dependencies and combine them with local JARs into a portable `cp.txt`.

## 5. Security: Dedicated Service User

Running a daemon as `root` is a significant security risk. Always create a dedicated, non-privileged user for your application.

### Create the Service User
Use `useradd` to create a system user with no login shell and no home directory:
```bash
# Create a system user 'deploy'
sudo useradd --system --shell /usr/sbin/nologin --comment "Deployment User" deploy
```

### Filesystem Permissions
The `deploy` user needs to read the application files and have write access to the specific folders where it generates output.

1.  **Application Root**: Make sure the `deploy` user can read everything in `/opt/md-to-odt`.
    ```bash
    sudo chown -R deploy:deploy /opt/md-to-odt
    sudo chmod -R 755 /opt/md-to-odt
    ```
2.  **Shared Cache**: The user needs read access to the shared Maven repository.
    ```bash
    sudo chown -R deploy:deploy /opt/shared/m2
    ```
3.  **Data Directories**: The user *must* have write access to the output folder.
    ```bash
    sudo mkdir -p /srv/md/input /srv/md/output
    sudo chown deploy:deploy /srv/md/input /srv/md/output
    ```

## 6. The Systemd Service File
The service points to the `current` symlink. It dynamically loads the classpath on every start, ensuring it always uses the correct dependencies for that specific version.

### Stay Portable: Service File Symlinking
Instead of creating the service file directly in `/etc/systemd/system/`, keep it inside your deployment directory (e.g., `/opt/md-to-odt/md-to-odt.service`) and symlink it. This makes your deployment directory a **self-contained unit** that can be easily moved or recovered.

```bash
sudo ln -s /opt/md-to-odt/md-to-odt.service /etc/systemd/system/md-to-odt.service
sudo systemctl daemon-reload
```

**`/opt/md-to-odt/md-to-odt.service`**:
```ini
[Unit]
Description=Markdown to ODT Converter Daemon
After=network.target

[Service]
Type=simple
User=deploy
Group=deploy
WorkingDirectory=/opt/md-to-odt

# 1. Dynamically generate the full CLASSPATH from the version's cp.txt
#    Note: 'current' is our atomic symlink. We include the app jar itself.
ExecStartPre=/bin/sh -c 'echo "CLASSPATH=current/md-to-odt.jar:$(./maven_get_deps -i current/cp.txt --classpath --cache /opt/shared/m2)" > /run/md-to-odt.env'

# 2. Load the environment variable and start the JVM
#    - CLASSPATH: automatically picked up, keeps 'ps' output clean.
#    - LOG_DIR: used by Logback to find where to store/rotate logs.
#    - logback.configurationFile: points to the external config for live scanning.
EnvironmentFile=/run/md-to-odt.env
Environment=LOG_DIR=/var/log/md-to-odt
# Note: Java automatically picks up the CLASSPATH variable, keeping 'ps' output clean.
ExecStart=/usr/bin/java -Dlogback.configurationFile=logback.xml hr.hrg.md2odt.Main /srv/md/input /srv/md/output

# Security Hardening (Systemd features)
ProtectSystem=full
ProtectHome=true
PrivateTmp=true
NoNewPrivileges=true

Restart=always

[Install]
WantedBy=multi-user.target
```

> [!TIP]
> **Log Rotation**: In this example, we use **Logback** with a `RollingFileAppender` inside the application. This is often preferred over `StandardOutput` redirection because:
> 1. **Granularity**: You can have different log files for different components.
> 2. **Native Rotation**: The application handles its own rotation logic (size/time based) and compression (`.gz`).
> 3. **Context**: Logs can include thread names, MDC context, and structured data (JSON) more easily.
> 4. **Live Configuration (`scan="true"`)**: By pointing to an external `logback.xml` via `-Dlogback.configurationFile=logback.xml`, you can change log levels (e.g., from `INFO` to `DEBUG`) by simply editing the file on the server. The application will detect the change and apply it within seconds without needing a restart—perfect for troubleshooting live issues. This works because the file is on the filesystem and not "hidden" inside the JAR.
>
> Ensure the `deploy` user has permission to write to the log directory:
> ```bash
> sudo mkdir -p /var/log/md-to-odt
> sudo chown deploy:deploy /var/log/md-to-odt
> ```

## 7. Deep Dive: The `/run/*.env` Mechanism

The use of an environment file in `/run` is a best-practice for managing dynamic classpaths in `systemd`. Here’s how it works in detail:

### Efficiency and Cleanliness (`CLASSPATH` vs `-cp`)
Instead of passing `-cp` as a command-line argument, we set the `CLASSPATH` environment variable.
- **Clean `ps` output**: Long classpaths (sometimes several kilobytes) make the output of `ps`, `top`, and `htop` unreadable. By using an environment variable, the process list remains concise: `/usr/bin/java hr.hrg.md2odt.Main ...`.
- **Security**: In some environments, command-line arguments might be visible to other users, whereas environment variables are better isolated.
- **JVM Behavior**: When no `-cp` or `-jar` is provided, the JVM automatically uses the `CLASSPATH` environment variable.

### Why `/run`?
In modern Linux distributions, `/run` is a **`tmpfs`** (a virtual filesystem in RAM). 
- **Performance**: Reading and writing is extremely fast.
- **Volatility**: It is automatically cleared on reboot. This ensures that no "stale" classpath from a previous system state is ever used.

### How it is generated (`ExecStartPre`)
`ExecStartPre` runs *before* the main service starts. We use a short shell command to capture the output of `maven_get_deps`:
```ini
ExecStartPre=/bin/sh -c 'echo "CLASSPATH=current/md-to-odt.jar:$(./maven_get_deps -i current/cp.txt --classpath --cache /opt/shared/m2)" > /run/md-to-odt.env'
```
- The Zig version of `maven_get_deps` resolves the classpath from `current/cp.txt` in milliseconds.
- The shell (`/bin/sh`) performs the command substitution `$(...)`.
- The result is written as a standard `KEY=VALUE` pair into the file.

### How it is loaded (`EnvironmentFile`)
`systemd` reads the file specified in `EnvironmentFile=` and injects the variables into the service's environment.
- This allows us to use the `CLASSPATH` variable without needing it in the `ExecStart` line at all.
- **Limitation**: `systemd` does *not* support command substitution directly in `ExecStart`. You cannot do `ExecStart=/usr/bin/java -cp $(tool ...)`. The `/run/*.env` pattern is the standard workaround.

### Why not use a wrapper script?
You *could* put the `java` command in a `start.sh` script, but that has downsides:
1. **Signal Handling**: If you use a script, `systemd` sends signals (like `SIGTERM`) to the script, not the JVM (unless you use `exec java ...`).
2. **Process Tracking**: `systemd` loses direct visibility into the JVM's status if it's hidden behind a shell wrapper.
3. **Transparency**: Keeping the `java` command in the `.service` file makes it clear exactly how the application is running when you check `systemctl status`.

## 8. Deployment Workflow
With this setup, upgrading to a new version is safe and atomic:

1.  **Prepare**: Use `stage-deploy` (see section 4) to generate a new version folder (e.g., `v1.1.0/`).
2.  **Config**: Ensure `logback.xml` is present in `/opt/md-to-odt/`.
3.  **Upload**: Copy the version folder to `/opt/md-to-odt/v1.1.0/`.
4.  **Index**: Run `./maven_get_deps gen-index --folders /opt/md-to-odt --output versions.json`.
5.  **Deploy**: Run `./maven_get_deps deploy --version v1.1.0`.

The `deploy` command updates `manifest.json`, which updates the `current` symlink atomically and triggers the service restart.

## 9. Running Locally

The project is designed to be easily runnable locally for development and testing.

### Log Directory Resolution
In `logback.xml`, the log directory is defined as:
```xml
<property name="LOG_DIR" value="${LOG_DIR:-logs}" />
```
This syntax tells Logback:
1.  **Check for `LOG_DIR`**: Look for an environment variable or a system property named `LOG_DIR`.
2.  **Default to `logs`**: If not found, use a local folder named `logs`.

### Running with Maven
You can run the daemon directly from the `md-to-odt` folder:
```bash
mvn exec:java -Dexec.mainClass="hr.hrg.md2odt.Main" -Dexec.args="input output"
```

### Running with Java CLI
After building the project and generating `cp.txt`:

**Bash (Linux/macOS)**:
```bash
export CLASSPATH="md-to-odt/target/md-to-odt-1.0.0.jar:$(cat md-to-odt/target/cp.txt)"
java -DLOG_DIR=./my-logs hr.hrg.md2odt.Main input output
```

**PowerShell (Windows)**:
```powershell
$CP = Get-Content md-to-odt/target/cp.txt
$env:CLASSPATH = "md-to-odt\target\md-to-odt-1.0.0.jar;" + $CP
java -DLOG_DIR=./my-logs hr.hrg.md2odt.Main input output
```

In these examples, `-DLOG_DIR=./my-logs` overrides the default `logs` folder, demonstrating how the same configuration works across local and production environments.

## 10. Benefits
- **Lean JARs**: No need to build fat JARs with all dependencies embedded.
- **Fast Startup**: `ExecStartPre` generates the classpath instantly without a JVM.
- **Atomic Swaps**: The app always sees a consistent set of dependencies and code.
- **Failure Recovery**: Use `upgrade-failed` if the new version crashes to instantly revert.

## 11. Service and Upload Access (ACLs)

When running the app as a restricted user, it must have access to the versions folder and library folders. Managing permissions for multiple users (the service user and the upload user) can be complex.

A clean approach is to use a `deploy` group and Linux ACLs.

### Create the Group and Add Users
```bash
sudo groupadd deploy
sudo usermod -aG deploy java-app     # The service user
sudo usermod -aG deploy upload-user # The user uploading new versions
```

### Apply ACLs
ACLs ensure that any new file created within the deployment directory is immediately writable by the `deploy` group.

```bash
# Install ACL tools if not present
sudo apt install acl

# Apply recursive ACLs (-R) and set default ACLs (-d)
sudo setfacl -R -m g:deploy:rwx,d:g:deploy:rwx /opt/md-to-odt
```

