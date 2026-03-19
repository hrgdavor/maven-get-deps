const state = {
    currentPath: null,
    inventory: JSON.parse(localStorage.getItem('nexus_inventory') || '[]'),
    seals: JSON.parse(localStorage.getItem('nexus_seals') || '[]'),
    docs: {},
    adventures: {}
};

const icons = {
    "Scroll of Arcane Forging": "📜",
    "Scroll of Systemd Binding": "🕯️",
    "Tome of Temporal Shifting": "🌀",
    "Architect's Hammer": "🛠️",
    "Crystalline Sentinel": "💎",
    "Chrono-Key": "🗡️",
    "Seal of the Architect": "🏛️",
    "Seal of the Warden": "🛡️",
    "Seal of the Gatekeeper": "🌀"
};

function init() {
    console.log("Nexus App Initializing...");
    const dataEl = document.getElementById('nexus-data');
    if (!dataEl) {
        console.error("Critical Error: nexus-data element not found!");
        return;
    }
    
    let data = {};
    try {
        data = JSON.parse(dataEl.textContent || '{}');
        console.log("Data bundle loaded:", Object.keys(data.docs || {}).length, "docs,", Object.keys(data.adventures || {}).length, "adventures");
    } catch (e) {
        console.error("Failed to parse nexus-data", e);
    }

    state.docs = data.docs || {};
    state.adventures = data.adventures || {};

    // Configure marked with highlight.js if available
    if (window.marked) {
        const markedOptions = {
            renderer: {}
        };

        if (window.hljs) {
            markedOptions.renderer.code = function({ text, lang }) {
                const language = hljs.getLanguage(lang) ? lang : 'plaintext';
                const highlighted = hljs.highlight(text, { language }).value;
                return `<pre><code class="hljs language-${language}">${highlighted}</code></pre>`;
            };
        }

        marked.use(markedOptions);
    } else {
        console.error("Critical Error: marked library not found!");
    }

    renderNav();
    renderInventory();
    setupNavListeners();
    
    const hash = window.location.hash.substring(1);
    if (hash) {
        console.log("Loading page from hash:", hash);
        loadPage(hash);
    }
}

function renderNav() {
    const docsList = document.getElementById('docs-list');
    const advList = document.getElementById('adventures-list');

    docsList.innerHTML = Object.entries(state.docs).map(([id, doc]) => `
        <li class="nav-item" data-id="doc/${id}">${doc.title}</li>
    `).join('');

    advList.innerHTML = Object.entries(state.adventures).map(([id, adv]) => `
        <li class="nav-item" data-id="adv/${id}">${adv.title}</li>
    `).join('');
}

function setupNavListeners() {
    document.getElementById('nav-tree').addEventListener('click', (e) => {
        const item = e.target.closest('.nav-item');
        if (item && item.dataset.id) {
            console.log("Nav item clicked:", item.dataset.id);
            loadPage(item.dataset.id);
        }
    });
}

function loadPage(path) {
    console.log("Attempting to load page:", path);
    state.currentPath = path;
    const [type, id] = path.split('/');
    const content = type === 'doc' ? state.docs[id] : state.adventures[id];

    if (!content) {
        console.warn("Path not found in data bundle:", path);
        return;
    }

    window.location.hash = path;
    
    // Update active nav item
    document.querySelectorAll('.nav-item').forEach(el => {
        el.classList.toggle('active', el.dataset.id === path);
    });

    renderContent(content, type === 'adv');
}

function renderContent(item, isAdventure) {
    const viewer = document.getElementById('content-viewer');
    const actions = document.getElementById('stage-actions');
    
    if (!item || !item.content) {
        console.error("No content to render for item:", item);
        viewer.innerHTML = "<p>Error: Content not found for this path.</p>";
        return;
    }

    // Custom rendering for GitHub-style alerts (pre-processing)
    let content = String(item.content);
    
    content = content.replace(/^> \[!IMPORTANT\]\n> \*\*Requirements\*\*: (.*)/gm, (_, reqs) => {
        const reqList = reqs.split(', ').map(r => r.trim());
        const hasAll = reqList.every(r => state.inventory.includes(r));
        return `<div class="callout callout-important"><strong>Requirements</strong>: ${reqs} ${hasAll ? '✅' : '❌'}</div>`;
    });
    content = content.replace(/^> \[!NOTE\]\s*(.*)/gm, '<div class="callout callout-note">$1</div>');
    content = content.replace(/^> \[!IMPORTANT\]\s*(.*)/gm, '<div class="callout callout-important">$1</div>');

    if (window.marked && typeof marked.parse === 'function') {
        try {
            viewer.innerHTML = marked.parse(content);
        } catch (err) {
            console.error("Marked parsing error:", err);
            viewer.innerHTML = `<pre>${content}</pre>`;
        }
    } else {
        console.warn("Marked not available, falling back to pre.");
        viewer.innerHTML = `<pre>${content}</pre>`;
    }
    
    if (isAdventure && item.rewards) {
        actions.classList.remove('hidden');
        const btn = document.getElementById('complete-stage-btn');
        const allDone = item.rewards.every(r => state.inventory.includes(r) || state.seals.includes(r));
        btn.innerText = allDone ? 'Goal Achieved' : 'Claim Rewards';
        btn.disabled = allDone;
        btn.onclick = () => claimRewards(item);
    } else {
        actions.classList.add('hidden');
    }
}

function claimRewards(item) {
    if (!item.rewards) return;
    
    item.rewards.forEach(reward => {
        if (reward.startsWith('Seal')) {
            if (!state.seals.includes(reward)) state.seals.push(reward);
        } else {
            if (!state.inventory.includes(reward)) state.inventory.push(reward);
        }
    });

    localStorage.setItem('nexus_inventory', JSON.stringify(state.inventory));
    localStorage.setItem('nexus_seals', JSON.stringify(state.seals));
    
    renderInventory();
    loadPage(state.currentPath); // Re-render to show ✅
    
    // Achievement effect?
    alert(`Loot Acquired: ${item.rewards.join(', ')}`);
}

function renderInventory() {
    const invGrid = document.getElementById('inventory-slots');
    const sealsGrid = document.getElementById('seals-display');

    invGrid.innerHTML = Array(8).fill(0).map((_, i) => {
        const item = state.inventory[i];
        return `<div class="slot ${item ? 'filled' : ''}" title="${item || 'Empty Slot'}">${item ? (icons[item] || '📦') : ''}</div>`;
    }).join('');

    sealsGrid.innerHTML = state.seals.map(seal => `
        <span class="seal-icon" title="${seal}">${icons[seal] || '📜'}</span>
    `).join('');
}

document.addEventListener('DOMContentLoaded', init);
