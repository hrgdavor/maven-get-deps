import { spawn } from "bun";
import { existsSync } from "node:fs";

async function run(name, cmd, cwd = process.cwd()) {
    console.log(`\n[${name}] Running: ${cmd.join(" ")}`);
    const proc = spawn(cmd, { 
        cwd,
        stdout: "inherit", 
        stderr: "inherit" 
    });
    const exitCode = await proc.exited;
    if (exitCode !== 0) {
        console.error(`❌ ${name} failed with exit code ${exitCode}`);
        process.exit(exitCode);
    }
    console.log(`✅ ${name} completed successfully.`);
}

async function main() {
    console.log("Building Maven Get Deps (Java & Zig)");
    console.log("====================================");

    // 1. Build Java
    // Using -Pexecutable to ensure the shaded CLI jar is produced
    await run("Java Build", ["mvnd", "clean", "install", "-Pexecutable", "-DskipTests"]);

    // 2. Build Zig
    await run("Zig Build", ["zig", "build", "-Doptimize=ReleaseSafe"]);

    console.log("\nBuild Results:");
    const javaJar = "maven-get-deps-cli/target/maven-get-deps-cli-1.0.2-cli.jar";
    const zigExe = "zig-out/bin/get_deps.exe";

    if (existsSync(javaJar)) {
        console.log(`  [Java]  ${javaJar}`);
    } else {
        console.error(`  [Java]  MISSING: ${javaJar}`);
    }

    if (existsSync(zigExe)) {
        console.log(`  [Zig]   ${zigExe}`);
    } else {
        console.error(`  [Zig]   MISSING: ${zigExe}`);
    }
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
