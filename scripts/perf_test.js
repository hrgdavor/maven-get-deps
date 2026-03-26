import { Glob, spawn } from "bun";
import { unlink } from "node:fs/promises";
import { join } from "node:path";

// Use current working directory as base
const WORKING_DIR = process.cwd();
const POM = "test/deps/complex1/core/pom.xml";
const REACTOR = "test/deps/complex1";
const JAVA_JAR = "maven-get-deps-cli/target/maven-get-deps-cli-1.0.2-cli.jar";
const ZIG_EXE = "zig-out/bin/get_deps.exe";
const JAVA = "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe";

async function run(name, cmd, cwd = WORKING_DIR) {
    console.log(`\nRunning ${name}...`);
    console.log(`CMD: ${cmd.join(" ")}`);
    const start = performance.now();
    const proc = spawn(cmd, { 
        cwd,
        stdout: "ignore", 
        stderr: "inherit" 
    });
    const exitCode = await proc.exited;
    const end = performance.now();
    
    if (exitCode !== 0) {
        console.error(`${name} failed with exit code ${exitCode}`);
        return null;
    }
    
    const duration = end - start;
    console.log(`${name}: ${duration.toFixed(2)}ms`);
    return duration;
}

async function clearCache() {
    console.log("Clearing internal caches...");
    const glob = new Glob("test/deps/complex1/core/pom.xml.*.get-deps.*cache");
    for await (const f of glob.scan(WORKING_DIR)) {
        try { 
            const fullPath = join(WORKING_DIR, f);
            await unlink(fullPath); 
            console.log(`Deleted ${fullPath}`);
        } catch(e) {}
    }
}

async function main() {
    const results = {};

    // 1. Ensure a clean state before collecting cache performance data
    await clearCache();

    // 2. Populate local caches once (all delivery paths)
    console.log("\n--- POPULATING CACHE ---");
    await run("Java Mimic (Populate cache)", [JAVA, "-jar", JAVA_JAR, "-m", "-p", POM, "-r", REACTOR]);
    await run("Zig Mimic (Populate cache)", [ZIG_EXE, "mimic", "-p", POM, "-r", REACTOR, "--cache-tree"]);

    // 3. Warm runs only (cache should already be populated)
    console.log("\n--- WARM RUNS (Cache key, local cache populated) ---");

    const jc_warm = await run("Java Classic (Warm)", [JAVA, "-jar", JAVA_JAR, "-p", POM, "-r", REACTOR, "-C"]);
    if (jc_warm) results["Java Classic (Warm)"] = `${jc_warm.toFixed(2)}ms`;

    const jm_warm = await run("Java Mimic (Warm)", [JAVA, "-jar", JAVA_JAR, "-m", "-p", POM, "-r", REACTOR, "-C"]);
    if (jm_warm) results["Java Mimic (Warm)"] = `${jm_warm.toFixed(2)}ms`;

    const zm_warm = await run("Zig Mimic (Warm)", [ZIG_EXE, "mimic", "-p", POM, "-r", REACTOR, "--cache-tree"]);
    if (zm_warm) results["Zig Mimic (Warm)"] = `${zm_warm.toFixed(2)}ms`;

    console.log("\n--- Final Results ---");
    console.table(results);
}

main().catch(console.error);
