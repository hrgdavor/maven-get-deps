import { $NOT, $S } from '@jsx6/signal'

export function ContentViewer({ $currentPath, $docs, $adventures, $inventory, $seals, onClaim }) {
  const $content = $S(() => {
    const path = $currentPath()
    if (!path) return null
    const [type, id] = path.split('/')
    const data = type === 'doc' ? $docs()[id] : $adventures()[id]
    return data ? { ...data, type } : null
  }, $currentPath, $docs, $adventures)
  const $renderedContent = $S(() => {
    const data = $content()
    if (!data || !data.content) return ''

    let content = String(data.content)

    // Custom pre-processing for alerts
    content = content.replace(/^> \[!IMPORTANT\]\n> \*\*Requirements\*\*: (.*)/gm, (_, reqs) => {
      const currentInv = $inventory()
      const reqList = reqs.split(', ').map(r => r.trim())
      const hasAll = reqList.every(r => currentInv.includes(r))
      return `<div class="callout callout-important"><strong>Requirements</strong>: ${reqs} ${hasAll ? '✅' : '❌'}</div>`
    })
    content = content.replace(/^> \[!NOTE\]\s*(.*)/gm, '<div class="callout callout-note">$1</div>')
    content = content.replace(/^> \[!IMPORTANT\]\s*(.*)/gm, '<div class="callout callout-important">$1</div>')

    if (window.marked) {
      return marked.parse(content)
    }
    return `<pre>${content}</pre>`
  }, $content, $inventory)

  const isAdventure = () => $content()?.type === 'adv'
  const rewards = () => $content()?.rewards

  const allClaimed = () => {
    const r = rewards()
    if (!r) return true
    const currentInv = $inventory()
    const currentSeals = $seals()
    return r.every(item => currentInv.includes(item) || currentSeals.includes(item))
  }

  return (
    <main id="content-area">
      <div class="markdown-body" innerHTML={$renderedContent}></div>

      <div id="stage-actions" class={() => (isAdventure() && rewards() ? '' : 'hidden')}>
        <button
          id="complete-stage-btn"
          class="premium-btn"
          disabled={allClaimed}
          onclick={() => onClaim($content())}
        >
          {() => allClaimed() ? 'Goal Achieved' : 'Claim Rewards'}
        </button>
      </div>

      <div id="welcome-screen" hidden={$content}>
        <h2>Select a Path</h2>
        <p>Choose a Tome or Trial from the sidebar to begin.</p>
      </div>
    </main>
  )
}
