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
    
    // 1. Clear Caches
    await clearCache();
    
    // 2. Cold Runs
    console.log("\n--- COLD RUNS (No Cache) ---");
    const b1 = await run("Baseline 1", ["mvnd", "dependency:list", "-f", POM, "-DskipTests"]);
    if (b1) results["Baseline 1 (mvnd dependency:list)"] = `${b1.toFixed(2)}ms`;

    const b2 = await run("Baseline 2", ["mvnd", "dependency:copy-dependencies", "-f", POM, "-DskipTests"]);
    if (b2) results["Baseline 2 (mvnd dependency:copy-dep)"] = `${b2.toFixed(2)}ms`;
    
    const jc = await run("Java Classic (Cold)", [JAVA, "-jar", JAVA_JAR, "-p", POM, "-r", REACTOR]);
    if (jc) results["Java Classic (Cold)"] = `${jc.toFixed(2)}ms`;

    const jm = await run("Java Mimic (Cold)", [JAVA, "-jar", JAVA_JAR, "-m", "-p", POM, "-r", REACTOR]);
    if (jm) results["Java Mimic (Cold)"] = `${jm.toFixed(2)}ms`;

    const zc = await run("Zig Mimic (Cold)", [ZIG_EXE, "mimic", "-p", POM, "-r", REACTOR]);
    if (zc) results["Zig Mimic (Cold)"] = `${zc.toFixed(2)}ms`;
    
    // 3. Populate Cache
    console.log("\n--- POPULATING CACHE ---");
    const jm_cold = await run("Java Mimic (Cold)", [JAVA, "-jar", JAVA_JAR, "-p", POM, "-r", REACTOR, "-m"]);

    console.log("\n--- WARM RUNS (With Repository Cache) ---");
    const jc_warm = await run("Java Classic (Warm)", [JAVA, "-jar", JAVA_JAR, "-p", POM, "-r", REACTOR, "-C"]);
    if (jc_warm) results["Java Classic (Warm)"] = `${jc_warm.toFixed(2)}ms`;

    const jm_warm = await run("Java Mimic (Warm)", [JAVA, "-jar", JAVA_JAR, "-p", POM, "-r", REACTOR, "-m", "-C"]);
    if (jm_warm) results["Java Mimic (Warm)"] = `${jm_warm.toFixed(2)}ms`;

    const zm_warm = await run("Zig Mimic (Warm)", [ZIG_EXE, "mimic", "-p", POM, "-r", REACTOR, "--cache-tree"]);
    if (zm_warm) results["Zig Mimic (Warm)"] = `${zm_warm.toFixed(2)}ms`;
    
    console.log("\n--- Final Results ---");
    console.table(results);
}

main().catch(console.error);
