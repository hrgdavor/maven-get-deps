# Nexus App & Download Portal Build System

This directory contains the source for the **Nexus Interactive App** and the **Download Portal**. 

The build system bundles these into the final `/docs` directory for deployment via GitHub Pages.

## 🏗️ Build Process

The build script ([build.js](./build.js)) performs the following rituals:
1. **Modular Distribution**: Copies shared CSS, JavaScript, and third-party libraries (`marked`, `highlight.js`) to the `/docs` directory to be shared across portals.
2. **Data Bundling**: Scans the project's `/doc` and `/adventures` directories, extracting titles and rewards to build the **Technical Tome** manifest.
3. **Portal Processing**: Generates optimized `nexus.html` and `download.html` that link to the shared technical resources.

## ⚔️ Commands

Run these from the `/site` directory (or root via `npm`):

```bash
# Execute the full build ritual
npm run build

# Consult the oracle (Serve the results)
npm run serve
```

## 📂 Source Structure
- `nexus.html / .css / .js`: The interactive documentation app.
- `download.html / style.css / app.js`: The release download utility.
- `build.js`: The architect's script for bundling.
