import { readFileSync, writeFileSync, existsSync } from "node:fs";
import path from "node:path";

function parseArgs(argv) {
    const options = {
        root: "pom.xml",
        setVersion: null,
        write: false,
        verbose: false,
    };

    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === "--root") {
            i += 1;
            if (i >= argv.length) {
                throw new Error("Missing value for --root");
            }
            options.root = argv[i];
        } else if (arg === "--set-version") {
            i += 1;
            if (i >= argv.length) {
                throw new Error("Missing value for --set-version");
            }
            options.setVersion = argv[i];
        } else if (arg === "--write") {
            options.write = true;
        } else if (arg === "--verbose") {
            options.verbose = true;
        } else if (arg === "--help" || arg === "-h") {
            printUsage(0);
        } else {
            throw new Error(`Unknown argument: ${arg}`);
        }
    }

    return options;
}

function printUsage(exitCode = 0) {
    console.log(`Usage: bun scripts/version.js [options]

Options:
  --root <pom.xml>           Root reactor pom path (default: pom.xml)
  --set-version <version>    Override the resolved root version before propagation
  --write                    Persist changes instead of dry-run output
  --verbose                  Print unchanged module paths too
  -h, --help                 Show this help
`);
    process.exit(exitCode);
}

function readText(filePath) {
    return readFileSync(filePath, "utf8");
}

function getDirectChildTagValue(block, tagName) {
    const match = block.match(new RegExp(`^[\\t ]*<${tagName}>[\\t ]*([^<]+?)[\\t ]*<\\/${tagName}>`, "m"));
    return match ? match[1].trim() : null;
}

function getProjectBody(xml) {
    const projectMatch = xml.match(/<project\b[\s\S]*?>([\s\S]*?)<\/project>/);
    return projectMatch ? projectMatch[1] : xml;
}

function stripParentBlock(projectBody) {
    return projectBody.replace(/<parent>[\s\S]*?<\/parent>/, "");
}

function getProjectCoordinates(xml, fallback = {}) {
    const projectBody = getProjectBody(xml);
    const parentBlockMatch = projectBody.match(/<parent>([\s\S]*?)<\/parent>/);
    const parentBlock = parentBlockMatch ? parentBlockMatch[1] : null;
    const projectBodyWithoutParent = stripParentBlock(projectBody);

    const groupId = getDirectChildTagValue(projectBodyWithoutParent, "groupId")
        ?? (parentBlock ? getDirectChildTagValue(parentBlock, "groupId") : null)
        ?? fallback.groupId
        ?? null;
    const artifactId = getDirectChildTagValue(projectBodyWithoutParent, "artifactId")
        ?? fallback.artifactId
        ?? null;
    const version = getDirectChildTagValue(projectBodyWithoutParent, "version")
        ?? (parentBlock ? getDirectChildTagValue(parentBlock, "version") : null)
        ?? fallback.version
        ?? null;

    return { groupId, artifactId, version };
}

function resolveCanonicalVersion(xml) {
    const revisionMatch = xml.match(/<properties>[\s\S]*?<revision>\s*([^<]+?)\s*<\/revision>[\s\S]*?<\/properties>/);
    if (revisionMatch) {
        return revisionMatch[1].trim();
    }

    const version = getProjectCoordinates(xml).version;
    if (version && !version.startsWith("${")) {
        return version;
    }

    throw new Error("Could not resolve canonical version from root pom. Expected <properties><revision>...</revision></properties> or a literal <version>.");
}

function getModulePaths(xml) {
    const modulesBlock = xml.match(/<modules>([\s\S]*?)<\/modules>/);
    if (!modulesBlock) {
        return [];
    }

    return Array.from(modulesBlock[1].matchAll(/<module>\s*([^<]+?)\s*<\/module>/g), match => match[1].trim());
}

function updateRevisionProperty(xml, newVersion) {
    const revisionPattern = /(<revision>[\t ]*)([^<]+)([\t ]*<\/revision>)/;
    const match = xml.match(revisionPattern);
    if (!match) {
        return { xml, changed: false, from: null };
    }

    const currentVersion = match[2].trim();
    if (currentVersion === newVersion) {
        return { xml, changed: false, from: currentVersion };
    }

    return {
        xml: xml.replace(revisionPattern, `$1${newVersion}$3`),
        changed: true,
        from: currentVersion,
    };
}

function updateProjectVersion(xml, newVersion) {
    const projectBody = getProjectBody(xml);
    const parentMatch = projectBody.match(/<parent>([\s\S]*?)<\/parent>/);
    const projectBodyWithoutParent = stripParentBlock(projectBody);
    const firstContainerMatch = projectBodyWithoutParent.match(/^[\t ]*<(properties|modules|dependencyManagement|dependencies|build|profiles|repositories|pluginRepositories|reporting|distributionManagement)\b/m);
    const projectHeader = firstContainerMatch
        ? projectBodyWithoutParent.slice(0, firstContainerMatch.index)
        : projectBodyWithoutParent;
    const versionPattern = /(^[\t ]*<version>[\t ]*)([^<]+)([\t ]*<\/version>)/m;
    const directVersionMatch = projectHeader.match(versionPattern);

    if (!directVersionMatch) {
        return { xml, changed: false, from: null };
    }

    const currentVersion = directVersionMatch[2].trim();
    if (currentVersion === newVersion) {
        return { xml, changed: false, from: currentVersion };
    }

    const updatedHeader = projectHeader.replace(versionPattern, `$1${newVersion}$3`);
    const updatedBodyWithoutParent = updatedHeader + projectBodyWithoutParent.slice(projectHeader.length);
    const updatedBody = parentMatch
        ? projectBody.replace(projectBodyWithoutParent, updatedBodyWithoutParent)
        : updatedBodyWithoutParent;

    return {
        xml: xml.replace(projectBody, updatedBody),
        changed: true,
        from: currentVersion,
    };
}

function updateParentVersion(xml, reactorArtifactKeys, newVersion) {
    const parentMatch = xml.match(/<parent>([\s\S]*?)<\/parent>/);
    if (!parentMatch) {
        return { xml, changed: false, from: null, reason: "no-parent" };
    }

    const parentBlock = parentMatch[1];
    const groupId = getDirectChildTagValue(parentBlock, "groupId");
    const artifactId = getDirectChildTagValue(parentBlock, "artifactId");
    const currentVersion = getDirectChildTagValue(parentBlock, "version");
    const parentKey = `${groupId}:${artifactId}`;

    if (!groupId || !artifactId || !currentVersion || !reactorArtifactKeys.has(parentKey)) {
        return { xml, changed: false, from: currentVersion, reason: "different-parent" };
    }

    if (currentVersion === newVersion) {
        return { xml, changed: false, from: currentVersion, reason: "already-current" };
    }

    const updatedParent = parentMatch[0].replace(/(<version>[\t ]*)([^<]+)([\t ]*<\/version>)/, `$1${newVersion}$3`);
    return {
        xml: xml.replace(parentMatch[0], updatedParent),
        changed: true,
        from: currentVersion,
        reason: "updated",
    };
}

function updateSiblingDependencyVersions(xml, reactorArtifactKeys, newVersion) {
    let changed = false;
    const updates = [];

    const updatedXml = xml.replace(/<dependency>([\s\S]*?)<\/dependency>/g, fullMatch => {
        const groupId = getDirectChildTagValue(fullMatch, "groupId");
        const artifactId = getDirectChildTagValue(fullMatch, "artifactId");
        const currentVersion = getDirectChildTagValue(fullMatch, "version");
        const dependencyKey = `${groupId}:${artifactId}`;

        if (!groupId || !artifactId || !currentVersion || !reactorArtifactKeys.has(dependencyKey) || currentVersion === newVersion) {
            return fullMatch;
        }

        changed = true;
        updates.push(`${dependencyKey} ${currentVersion} -> ${newVersion}`);
        return fullMatch.replace(/(<version>[\t ]*)([^<]+)([\t ]*<\/version>)/, `$1${newVersion}$3`);
    });

    return { xml: updatedXml, changed, updates };
}

function writeIfNeeded(filePath, original, updated, write) {
    if (original === updated) {
        return false;
    }

    if (write) {
        writeFileSync(filePath, updated, "utf8");
    }

    return true;
}

function collectModules(rootPomPath) {
    const queue = [path.resolve(rootPomPath)];
    const seen = new Set();
    const result = [];

    while (queue.length > 0) {
        const pomPath = queue.shift();
        if (!pomPath || seen.has(pomPath)) {
            continue;
        }

        seen.add(pomPath);
        result.push(pomPath);

        const xml = readText(pomPath);
        const pomDir = path.dirname(pomPath);
        for (const modulePath of getModulePaths(xml)) {
            const childPom = path.resolve(pomDir, modulePath, "pom.xml");
            if (!existsSync(childPom)) {
                throw new Error(`Declared module does not exist: ${childPom}`);
            }
            queue.push(childPom);
        }
    }

    return result;
}

function getReactorArtifacts(pomPaths) {
    const artifactKeys = new Set();
    const projectInfo = new Map();

    for (const pomPath of pomPaths) {
        const xml = readText(pomPath);
        const coords = getProjectCoordinates(xml);
        if (!coords.groupId || !coords.artifactId) {
            throw new Error(`Could not determine coordinates for ${pomPath}`);
        }

        artifactKeys.add(`${coords.groupId}:${coords.artifactId}`);
        projectInfo.set(pomPath, coords);
    }

    return { artifactKeys, projectInfo };
}

function main() {
    const options = parseArgs(process.argv.slice(2));
    const rootPomPath = path.resolve(options.root);
    if (!existsSync(rootPomPath)) {
        throw new Error(`Root pom not found: ${rootPomPath}`);
    }

    const rootXml = readText(rootPomPath);
    const targetVersion = options.setVersion ?? resolveCanonicalVersion(rootXml);
    const pomPaths = collectModules(rootPomPath);
    const { artifactKeys, projectInfo } = getReactorArtifacts(pomPaths);
    const changed = [];
    const unchanged = [];

    for (const pomPath of pomPaths) {
        const originalXml = readText(pomPath);
        const details = [];
        let updatedXml = originalXml;

        if (pomPath === rootPomPath) {
            const revisionUpdate = updateRevisionProperty(updatedXml, targetVersion);
            updatedXml = revisionUpdate.xml;
            if (revisionUpdate.changed) {
                details.push(`revision ${revisionUpdate.from} -> ${targetVersion}`);
            }
        }

        const projectVersionUpdate = updateProjectVersion(updatedXml, targetVersion);
        updatedXml = projectVersionUpdate.xml;
        if (projectVersionUpdate.changed) {
            details.push(`project-version ${projectVersionUpdate.from} -> ${targetVersion}`);
        }

        const parentVersionUpdate = updateParentVersion(updatedXml, artifactKeys, targetVersion);
        updatedXml = parentVersionUpdate.xml;
        if (parentVersionUpdate.changed) {
            details.push(`parent-version ${parentVersionUpdate.from} -> ${targetVersion}`);
        }

        const siblingDependencyUpdate = updateSiblingDependencyVersions(updatedXml, artifactKeys, targetVersion);
        updatedXml = siblingDependencyUpdate.xml;
        if (siblingDependencyUpdate.changed) {
            details.push(...siblingDependencyUpdate.updates.map(update => `dependency-version ${update}`));
        }

        if (writeIfNeeded(pomPath, originalXml, updatedXml, options.write)) {
            changed.push({ filePath: pomPath, details });
        } else if (options.verbose) {
            const coords = projectInfo.get(pomPath);
            unchanged.push({
                filePath: pomPath,
                reason: coords ? `${coords.groupId}:${coords.artifactId} already current` : "already current",
            });
        }
    }

    console.log(`${options.write ? "Updated" : "Planned"} ${changed.length} file(s) using version ${targetVersion}.`);
    for (const item of changed) {
        console.log(` - ${path.relative(process.cwd(), item.filePath)}: ${item.details.join(", ")}`);
    }

    if (options.verbose && unchanged.length > 0) {
        console.log(`Unchanged ${unchanged.length} file(s):`);
        for (const item of unchanged) {
            console.log(` - ${path.relative(process.cwd(), item.filePath)}: ${item.reason}`);
        }
    }

    if (!options.write) {
        console.log("Dry run only. Re-run with --write to persist changes.");
    }
}

try {
    main();
} catch (err) {
    if (err instanceof Error) {
        console.error(err.message);
    } else {
        console.error(err);
    }
    process.exit(1);
}