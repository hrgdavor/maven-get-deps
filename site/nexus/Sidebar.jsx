import { Loop } from '@jsx6/jsx6'
import { Inventory } from '../ui-lib.jsx'
import { $EQ, $F, $If, $S } from '@jsx6/signal'

/**
 * @param {Object} props
 * @param {import('@jsx6/signal').Signal} props.docs
 * @param {import('@jsx6/signal').Signal} props.adventures
 * @param {import('@jsx6/signal').Signal} props.currentPath
 * @param {import('@jsx6/signal').Signal} props.inventory
 * @param {import('@jsx6/signal').Signal} props.seals
 * @param {Function} props.onNavigate
 */
export function Sidebar({ $docs, $adventures, $currentPath, $inventory, $seals, onNavigate }) {
  const NavItem = ({ $v, attr }) => {
    const $active = $EQ($v.id, $currentPath)
    const $className = $S`nav-item ${$If($active, 'active', '')}`
    return (
      <li
        class={$className}
        onclick={() => onNavigate(`${attr.idPrefix}/${$v.id()}`)}
      >
        {$v.title}
      </li>
    )
  }

  const mapToArrayWithId = (obj) => Object.entries(obj).map(([id, d]) => ({ id, ...d }))
  return (
    <aside id="sidebar">
      <header class="nexus-header">
        <h1>NEXUS</h1>
        <div class="subtitle">Artifact Retrieval System</div>
      </header>

      <nav id="nav-tree">

        <section class="nav-section">
          <h3>⚔️ Trial Paths</h3>
          <ul id="adventures-list">
            <Loop
              value={$F(mapToArrayWithId, $adventures)}
              item={NavItem}
              idPrefix="adv"
            />
          </ul>
          <Inventory $inventory={$inventory} $seals={$seals} />
        </section>

        <section class="nav-section">
          <h3>📜 Technical Tome</h3>
          <ul id="docs-list">
            <Loop
              value={$F(mapToArrayWithId, $docs)}
              item={NavItem}
              idPrefix="doc"
            />
          </ul>
        </section>

      </nav>
    </aside>
  )
}
