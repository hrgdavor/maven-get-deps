import { Loop } from '@jsx6/jsx6'
import { $F, $If, $S } from '@jsx6/signal'

export const icons = {
  "Scroll of Arcane Forging": "📜",
  "Scroll of Systemd Binding": "🕯️",
  "Tome of Temporal Shifting": "🌀",
  "Architect's Hammer": "🛠️",
  "Crystalline Sentinel": "💎",
  "Chrono-Key": "🗡️",
  "Seal of the Architect": "🏛️",
  "Seal of the Warden": "🛡️",
  "Seal of the Gatekeeper": "🌀"
}

export function Inventory({ $inventory, $seals }) {
  const Slot = ({ $v: $item }) => (
    <div class={() => $S`slot ${$If($item, 'filled', '')}`} title={$F((item) => item || 'Empty Slot', $item)}>
      {$F((item) => item ? (icons[item] || item) : '', $item)}
    </div>
  )

  return (
    <div class="inventory-display">
      <section>
        <h3>Inventory</h3>
        <div id="inventory-slots" class="slots">
          <Loop
            primitive
            value={$S(() => {
              const items = $inventory()
              return Array(8).fill(0).map((_, i) => items[i])
            }, $inventory)}
            item={Slot}
          />
        </div>
      </section>

      <section>
        <h3>Seals</h3>
        <div id="seals-display" class="seals">
          <Loop
            value={$seals}
            primitive={true}
            item={({ $v: seal }) => <span class="seal-icon" title={seal}>{icons[seal] || '📜'}</span>}
          />
        </div>
      </section>
    </div>
  )
}
