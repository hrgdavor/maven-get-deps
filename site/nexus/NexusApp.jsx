import { Jsx6 } from '@jsx6/jsx6'
import { $State } from '@jsx6/signal'
import { Sidebar } from './Sidebar.jsx'
import { ContentViewer } from './ContentViewer.jsx'
import { initDevTools } from '../devtools.js'

if (typeof IS_DEV !== 'undefined' && IS_DEV) {
  initDevTools();
}

export class NexusApp extends Jsx6 {

  initData() {
    const dataEl = document.getElementById('nexus-data')
    if (dataEl) {
      try {
        const data = JSON.parse(dataEl.textContent || '{}')
        this.$s.docs(data.docs || {})
        this.$s.adventures(data.adventures || {})
      } catch (e) {
        console.error("Failed to parse nexus-data", e)
      }
    }
  }

  setupNavigation() {
    const hash = window.location.hash.substring(1)
    if (hash) this.loadPage(hash)

    window.addEventListener('hashchange', () => {
      const h = window.location.hash.substring(1)
      if (h) this.loadPage(h)
    })
  }

  loadPage(path) {
    this.$s.currentPath(path)
    window.location.hash = path
  }

  claimRewards(item) {
    if (!item.rewards) return

    const inventory = [...this.$s.inventory()]
    const seals = [...this.$s.seals()]

    item.rewards.forEach(reward => {
      if (reward.startsWith('Seal')) {
        if (!seals.includes(reward)) seals.push(reward)
      } else {
        if (!inventory.includes(reward)) inventory.push(reward)
      }
    })

    this.$s.inventory(inventory)
    this.$s.seals(seals)

    localStorage.setItem('nexus_inventory', JSON.stringify(inventory))
    localStorage.setItem('nexus_seals', JSON.stringify(seals))

    alert(`Loot Acquired: ${item.rewards.join(', ')}`)
  }

  tpl() {
    const { $s } = this
    $s({
      currentPath: null,
      bla: 'bla',
      inventory: JSON.parse(localStorage.getItem('nexus_inventory') || '[]'),
      seals: JSON.parse(localStorage.getItem('nexus_seals') || '[]'),
      docs: {},
      adventures: {}
    })
    console.log('$s', $s)
    console.log('$s.bla', $s.bla)
    console.log(this)
    this.initData()
    this.setupNavigation()

    return (
      <div id="app">
        <Sidebar
          $docs={$s.docs}
          $adventures={$s.adventures}
          $currentPath={$s.currentPath}
          $inventory={$s.inventory}
          $seals={$s.seals}
          onNavigate={p => this.loadPage(p)}
        />
        <ContentViewer
          $currentPath={$s.currentPath}
          $docs={$s.docs}
          $adventures={$s.adventures}
          $inventory={$s.inventory}
          $seals={$s.seals}
          onClaim={item => this.claimRewards(item)}
        />
      </div>
    )
  }
}
