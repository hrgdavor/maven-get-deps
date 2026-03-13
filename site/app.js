const tools = [
    { id: 'maven-get-deps', name: 'maven-get-deps', desc: 'Core Java Tool', langs: ['java', 'jar'] },
    { id: 'cve12', name: 'cve12', desc: 'CVE Scanner', langs: ['java', 'jar'] },
    { id: 'get_deps', name: 'get_deps', desc: 'Zig Resolver', langs: ['zig'] },
    { id: 'version_manager', name: 'version_manager', desc: 'Version Manager', langs: ['zig'] },
    { id: 'gen_index', name: 'gen_index', desc: 'Index Generator', langs: ['zig'] }
];

const platforms = [
    { id: 'linux-x64', name: 'Linux x64', os: 'linux', arch: 'x64' },
    { id: 'linux-arm64', name: 'Linux ARM', os: 'linux', arch: 'arm64' },
    { id: 'windows-x64', name: 'Windows x64', os: 'windows', arch: 'x64' },
    { id: 'macos-x64', name: 'macOS Intel', os: 'macos', arch: 'x64' },
    { id: 'macos-arm64', name: 'macOS Apple', os: 'macos', arch: 'arm64' }
];

const state = {
    tool: 'maven-get-deps',
    platform: 'linux-x64',
    lang: 'java'
};

const baseUrl = 'https://github.com/hrgdavor/maven-get-deps/releases/latest/download';

function detectPlatform() {
    const ua = navigator.userAgent.toLowerCase();
    const platform = navigator.platform.toLowerCase();
    let os = 'linux';
    let arch = 'x64';

    if (ua.includes('win')) os = 'windows';
    else if (ua.includes('mac')) os = 'macos';
    else if (ua.includes('linux')) os = 'linux';

    // Detection for ARM64
    if (ua.includes('arm64') || ua.includes('aarch64') || platform.includes('arm64') || platform.includes('aarch64')) {
        arch = 'arm64';
    } else if (os === 'macos' && !ua.includes('intel')) {
        // Most modern macs are ARM based if not explicitly Intel
        arch = 'arm64';
    }

    const detectedId = `${os}-${arch}`;
    if (platforms.some(p => p.id === detectedId)) {
        state.platform = detectedId;
    }
}

function init() {
    detectPlatform();
    renderPlatforms();
    renderToolTable();
    setupEventListeners();
    updateCommands();
}

function renderPlatforms() {
    const container = document.getElementById('platform-tabs');
    if (!container) return;
    container.innerHTML = platforms.map(p => `
        <button class="tab-btn ${state.platform === p.id ? 'active' : ''}" data-value="${p.id}">
            ${p.name}
        </button>
    `).join('');
}

function renderToolTable() {
    const tbody = document.getElementById('tool-table-body');
    if (!tbody) return;

    tbody.innerHTML = tools.map(t => {
        const isActiveTool = state.tool === t.id;
        return `
        <tr class="${isActiveTool ? 'active-row' : ''}">
            <td><strong>${t.name}</strong></td>
            <td>${t.desc}</td>
            <td class="actions-col">
                <div class="row-actions">
                    ${t.langs.map(l => {
                        const label = (l === 'java' || l === 'zig') ? 'Native' : 'JAR';
                        const isActiveLang = isActiveTool && state.lang === l;
                        return `
                        <button class="action-btn ${isActiveLang ? 'active' : ''}" 
                                data-tool="${t.id}" data-lang="${l}">
                            ${label}
                        </button>
                        `;
                    }).join('')}
                </div>
            </td>
        </tr>
        `;
    }).join('');
}

function setupEventListeners() {
    // Platform tabs
    document.getElementById('platform-tabs').addEventListener('click', (e) => {
        if (e.target.classList.contains('tab-btn')) {
            state.platform = e.target.dataset.value;
            renderPlatforms();
            updateCommands();
        }
    });

    // Tool table buttons
    document.getElementById('tool-table-body').addEventListener('click', (e) => {
        const btn = e.target.closest('.action-btn');
        if (btn) {
            state.tool = btn.dataset.tool;
            state.lang = btn.dataset.lang;
            renderToolTable();
            updateCommands();
        }
    });

    // Copy button
    document.getElementById('copy-btn').addEventListener('click', () => {
        const text = document.getElementById('command-output').innerText;
        navigator.clipboard.writeText(text).then(() => {
            const btn = document.getElementById('copy-btn');
            const originalText = btn.innerText;
            btn.innerText = 'Copied!';
            setTimeout(() => btn.innerText = originalText, 2000);
        });
    });
}

function updateCommands() {
    const output = document.getElementById('command-output');
    if (!output) return;

    const tool = tools.find(t => t.id === state.tool);
    const platform = platforms.find(p => p.id === state.platform);
    
    let artifact = '';
    let isZip = platform.os === 'windows';
    let ext = isZip ? 'zip' : 'tar.gz';
    let extractCmd = isZip ? `Expand-Archive -Path` : `tar -xzf`;
    
    if (state.lang === 'jar') {
        artifact = `${state.tool}-cli.jar`;
        output.innerHTML = `# Download CLI JAR\ncurl -L -O ${baseUrl}/${artifact}\n\n# Run usage\njava -jar ${artifact} --help`;
        return;
    }

    const toolName = state.lang === 'zig' ? state.tool.replace('_', '-') : state.tool;
    const langSuffix = state.lang === 'zig' ? '-zig' : '';
    artifact = `${toolName}${langSuffix}-${platform.os}-${platform.arch}.${ext}`;

    if (platform.os === 'windows') {
        output.innerHTML = `# Download and Extract (PowerShell)\nInvoke-WebRequest -Uri "${baseUrl}/${artifact}" -OutFile "${artifact}"\n${extractCmd} "${artifact}" -DestinationPath "."\n\n# Run tool\n.\\${state.tool}.exe --help`;
    } else {
        output.innerHTML = `# Download and Extract\ncurl -L -O ${baseUrl}/${artifact}\n${extractCmd} ${artifact}\n\n# Run tool\n./${state.tool} --help`;
    }

    const downloadLink = document.getElementById('download-link');
    if (downloadLink) {
        downloadLink.href = `${baseUrl}/${artifact}`;
        downloadLink.innerText = `Download ${artifact}`;
    }
}

init();
