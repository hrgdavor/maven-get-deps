const fs = require('fs');
const path = require('path');

const rootDir = path.join(__dirname, '..');
const srcDir = __dirname;
const destDir = path.join(rootDir, 'docs');

// Create destination if it doesn't exist
if (!fs.existsSync(destDir)) {
    fs.mkdirSync(destDir, { recursive: true });
}

/**
 * Helper to copy a file to the destination
 */
function copyFile(srcFolder, srcName, destSubDir = '') {
    const srcPath = path.join(srcFolder, srcName);
    const destPath = path.join(destDir, destSubDir, path.basename(srcName));

    const targetDir = path.dirname(destPath);
    if (!fs.existsSync(targetDir)) {
        fs.mkdirSync(targetDir, { recursive: true });
    }

    fs.copyFileSync(srcPath, destPath);
    console.log(`  📎 Copied ${srcName} -> ${path.relative(destDir, destPath)}`);
}

/**
 * Helper to build a portal (copies HTML and optionally injects data)
 */
function buildPortal(templatePath, outputName, data = null) {
    let html = fs.readFileSync(templatePath, 'utf8');

    if (data) {
        const dataJson = JSON.stringify(data);
        // We still inject the data bundle directly as it's portal-specific
        html = html.replace('<script id="nexus-data" type="application/json">{}</script>',
            () => `<script id="nexus-data" type="application/json">${dataJson}</script>`);
    }

    fs.writeFileSync(path.join(destDir, outputName), html);
    console.log(`✅ Processed ${outputName}`);
}

function createJsx6Plugin(externals = []) {
    return {
        name: 'jsx6-plugin',
        setup(build) {
            build.onResolve({ filter: /^(react\/|@jsx6\/)/ }, args => {
                let pathStr = args.path;
                if (pathStr.startsWith('react/')) {
                    pathStr = pathStr.replace('react/', '@jsx6/');
                }

                if (externals.some(ext => pathStr.startsWith(ext))) {
                    return { external: true };
                }

                const parts = pathStr.split('/');
                const pkg = parts[1];
                const subpath = parts.slice(2).join('/');

                // Force resolution from the local project's node_modules/@jsx6
                const base = path.resolve(__dirname, 'node_modules', '@jsx6', pkg);
                let resolvedPath = subpath ? path.resolve(base, subpath) : path.resolve(base, 'index.js');

                // Handle missing extensions
                if (!resolvedPath.endsWith('.js') && !fs.existsSync(resolvedPath)) {
                    if (fs.existsSync(resolvedPath + '.js')) {
                        resolvedPath += '.js';
                    } else if (fs.existsSync(path.join(resolvedPath, 'index.js'))) {
                        resolvedPath = path.join(resolvedPath, 'index.js');
                    }
                }

                return { path: resolvedPath };
            });
        },
    };
}

/**
 * Fixes Bun's external sourcemaps by appending the sourceMappingURL and ensuring correct filenames.
 * This is necessary because Bun with 'naming' doesn't always append the comment or use the exact name for maps.
 */
function fixSourcemaps(result, naming) {
    if (!result.success || !result.outputs) return;

    const baseName = path.basename(naming, '.js');
    const mapName = baseName + '.js.map';

    for (const output of result.outputs) {
        if (output.path.endsWith('.js')) {
            const jsPath = output.path;
            const jsContent = fs.readFileSync(jsPath, 'utf8');
            if (!jsContent.includes('sourceMappingURL=')) {
                fs.appendFileSync(jsPath, `\n//# sourceMappingURL=${mapName}`);
            }
        } else if (output.path.endsWith('.map')) {
            const destMapPath = path.join(destDir, mapName);
            if (output.path !== destMapPath) {
                if (fs.existsSync(destMapPath)) fs.unlinkSync(destMapPath);
                fs.renameSync(output.path, destMapPath);
            }
        }
    }
}

async function runBuild() {
    console.log("🛠️ Starting modular project site build...");

    const pkg = JSON.parse(fs.readFileSync(path.join(srcDir, 'package.json'), 'utf8'));
    const version = pkg.version || '0.0.0';
    console.log(`📌 Site Version: ${version}`);

    const isProd = process.argv.includes('--prod');
    if (isProd) console.log("🚀 Production build enabled (DevTools excluded)");

    // Read version from @jsx6/jsx6 package
    const jsxPkg = JSON.parse(fs.readFileSync(path.resolve(srcDir, 'node_modules', '@jsx6', 'jsx6', 'package.json'), 'utf8'));
    const jsxVersion = jsxPkg.version || '0.0.0';
    const jsxBundleName = ['signal', 'jsx6'].sort().join('-') + `-v${jsxVersion}.js`;
    console.log(`📌 JSX6 Version: ${jsxVersion} -> ${jsxBundleName}`);

    const baseBuildOptions = {
        minify: true,
        sourcemap: 'external',
        define: {
            IS_DEV: JSON.stringify(!isProd)
        }
    };

    // 1. Build Shared Libs
    console.log(`📦 Building Shared JSX6 Lib: ${jsxBundleName}...`);
    const jsx6Result = await Bun.build({
        ...baseBuildOptions,
        plugins: [createJsx6Plugin()],
        entrypoints: [path.join(srcDir, 'jsx6-lib.js')],
        outdir: destDir,
        naming: jsxBundleName
    });
    fixSourcemaps(jsx6Result, jsxBundleName);

    console.log("📦 Building Shared UI Lib...");
    const uiBundleName = `ui-v${version}.js`;
    const uiResult = await Bun.build({
        ...baseBuildOptions,
        plugins: [createJsx6Plugin(['@jsx6/jsx6', '@jsx6/signal'])],
        entrypoints: [path.join(srcDir, 'ui-lib.jsx')],
        outdir: destDir,
        naming: uiBundleName,
        external: ['@jsx6/jsx6', '@jsx6/signal']
    });
    fixSourcemaps(uiResult, uiBundleName);

    // 2. Build CSS files (with manual 1:1 sourcemaps for DevTools mapping)
    console.log("🎨 Processing CSS files with sourcemaps...");
    const cssFiles = [
        { src: path.join(srcDir, 'style.css'), dest: 'style.css' },
        { src: path.join(srcDir, 'nexus', 'nexus.css'), dest: 'nexus.css' }
    ];

    for (const css of cssFiles) {
        let content = fs.readFileSync(css.src, 'utf8');
        const mapName = `${css.dest}.map`;
        content += `\n/*# sourceMappingURL=${mapName} */`;
        fs.writeFileSync(path.join(destDir, css.dest), content);

        const relPath = path.relative(destDir, css.src).replace(/\\/g, '/');
        const map = {
            version: 3,
            file: css.dest,
            sources: [relPath],
            names: [],
            mappings: "AAAA" // Start-to-start mapping
        };
        fs.writeFileSync(path.join(destDir, mapName), JSON.stringify(map, null, 2));
    }

    // 3. Bundle Nexus App (with externals)
    console.log("📦 Bundling Nexus App...");
    const nexusBundleName = 'nexus.bundle.js';
    const result = await Bun.build({
        ...baseBuildOptions,
        plugins: [createJsx6Plugin(['@jsx6/jsx6', '@jsx6/signal'])],
        entrypoints: [path.join(srcDir, 'nexus/index.jsx')],
        outdir: destDir,
        naming: nexusBundleName,
        external: ['@jsx6/jsx6', '@jsx6/signal', '../ui-lib.jsx']
    });

    if (!result.success) {
        console.error("Build failed");
        for (const message of result.logs) {
            console.error(message);
        }
        process.exit(1);
    }
    fixSourcemaps(result, nexusBundleName);
    console.log("✅ Nexus App bundled.");

    // 3. Copy Shared Assets
    console.log("🚚 Copying shared assets...");
    copyFile(srcDir, 'app.js');
    copyFile(srcDir, 'lib/marked.min.js', 'lib');
    copyFile(srcDir, 'lib/highlight.min.js', 'lib');
    copyFile(srcDir, 'lib/github.min.css', 'lib');

    // 4. Build Portals
    buildPortal(path.join(srcDir, 'download.html'), 'download.html');

    console.log("📦 Aggregating documentation for Nexus...");
    const bundle = { docs: {}, adventures: {} };
    const extractTitle = (c) => (c.match(/^# (.*)/) || [, 'Untitled'])[1];
    const extractRewards = (c) => {
        const rewards = [];
        const sm = c.match(/- \*\*Seal of Mastery\*\*: (.*)/);
        if (sm) rewards.push(sm[1].replace(/.*(Seal of the [^.]+).*/, '$1').trim());
        const tm = c.match(/- \*\*(Tools|Loot|Weapons) Gained\*\*: (.*)/);
        if (tm) rewards.push(tm[2].replace(/[.!?]/g, '').replace(/[\u2700-\u27BF]|[\uE000-\uF8FF]|\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDFFF]|[\u2011-\u26FF]|\uD83E[\uDD10-\uDDFF]/g, '').split('(')[0].trim());
        return rewards;
    };

    // Map Docs
    fs.readdirSync(path.join(rootDir, 'doc'))
        .filter(f => f.endsWith('.md'))
        .forEach(file => {
            const content = fs.readFileSync(path.join(rootDir, 'doc', file), 'utf8');
            bundle.docs[file.replace('.md', '')] = { title: extractTitle(content), content };
        });

    // Map Adventures
    fs.readdirSync(path.join(rootDir, 'adventures'))
        .filter(f => f.endsWith('.md') && f !== 'README.md')
        .forEach(file => {
            const content = fs.readFileSync(path.join(rootDir, 'adventures', file), 'utf8');
            bundle.adventures[file.replace('.md', '')] = {
                title: extractTitle(content).replace('Adventure: ', ''),
                content,
                rewards: extractRewards(content)
            };
        });

    const nexusHtml = path.join(srcDir, 'nexus/nexus.html');
    let html = fs.readFileSync(nexusHtml, 'utf8');

    // Inject dynamic version into HTML template before buildPortal
    html = html.replace(/jsx6-signal-v\d+\.\d+\.\d+\.js/g, jsxBundleName)
        .replace(/ui-v\d+\.\d+\.\d+\.js/g, `ui-v${version}.js`);

    const dataJson = JSON.stringify(bundle);
    html = html.replace('<script id="nexus-data" type="application/json">{}</script>',
        () => `<script id="nexus-data" type="application/json">${dataJson}</script>`);

    fs.writeFileSync(path.join(destDir, 'nexus.html'), html);
    console.log(`✅ Processed nexus.html`);

    // Add .well-known for chrome dev tools
    const wellKnownDir = path.join(destDir, '.well-known', 'appspecific');
    if (!fs.existsSync(wellKnownDir)) fs.mkdirSync(wellKnownDir, { recursive: true });
    
    const configPath = path.join(wellKnownDir, 'com.chrome.devtools.json');
    const config = {
        workspace: {
            root: path.resolve(srcDir, '..').replace(/\\/g, '/'),
            uuid: 'maven-get-deps-workspace'
        }
    };
    
    fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
    console.log(`✅ Processed .well-known/appspecific/com.chrome.devtools.json`);

    console.log(`🚀 Build complete. Assets available in ${destDir}`);
}

runBuild().catch(err => {
    console.error(err);
    process.exit(1);
});
