const subscribeSymbol = Symbol.for('signalSubscribe');

/**
 * Initializes DevTools improvements for @jsx6/signal.
 * - Registers a Custom DevTools Formatter for better visual inspection.
 * - Adds global helper $v for proxy-based signal unwrapping.
 */
export function initDevTools() {
  if (typeof window === 'undefined') return;
  if (window.__jsx6_devtools_initialized) return;
  window.__jsx6_devtools_initialized = true;

  // 1. Register Custom DevTools Formatter
  window.devtoolsFormatters = window.devtoolsFormatters || [];

  const signalFormatter = {
    header: (obj) => {
      if (isSignal(obj)) {
        const val = obj();
        const label = obj.label || obj.name || 'anonymous';
        return ['span', { style: 'padding: 1px 5px; background: #eef8ff; border: 1px solid #cce5ff; border-radius: 3px; color: #0077cc; font-size: 11px; margin-right: 5px;' },
          ['span', { style: 'font-weight: bold;' }, 'Signal'],
          ['span', { style: 'color: #666; font-style: italic;' }, `[${label}]: `],
          ['object', { object: val }]
        ];
      }
      return null;
    },
    hasBody: (obj) => isSignal(obj),
    body: (obj) => {
      const val = obj();
      const children = [
        ['div', { style: 'margin-left: 15px;' },
          ['span', { style: 'color: #888;' }, 'current value: '],
          ['object', { object: val }]
        ]
      ];

      if (obj.label || obj.name) {
        children.unshift(['div', { style: 'margin-left: 15px;' },
          ['span', { style: 'color: #888;' }, 'label/name: '],
          ['span', { style: 'color: #333;' }, obj.label || obj.name]
        ]);
      }

      return ['div', {}, ...children];
    }
  };

  window.devtoolsFormatters.push(signalFormatter);

  // 2. Add global shorthand proxy $v
  // Usage in console: $v(app.$s).count instead of app.$s.count()
  window.$v = (obj) => {
    if (isSignal(obj)) return obj();
    if (obj !== null && typeof obj === 'object') {
      return new Proxy(obj, {
        get(target, prop) {
          const val = target[prop];
          return isSignal(val) ? val() : window.$v(val);
        }
      });
    }
    return obj;
  };

  console.log('✅ JSX6 Signal DevTools Initialized');
  console.log('💡 Tip: Enable "Custom formatters" in DevTools Settings > Preferences > Console to see signals beautifully.');
  console.log('💡 Tip: Use $dump(obj) or $v(obj) in console to unwrap signals recursively.');
}

/** 
 * Helper to check if an object is an @jsx6/signal 
 */
function isSignal(obj) {
  return typeof obj === 'function' && !!obj[subscribeSymbol];
}

function formatValue(val) {
  try {
    if (val === null) return 'null';
    if (val === undefined) return 'undefined';
    if (typeof val === 'string') return `"${val}"`;
    if (typeof val === 'number' || typeof val === 'boolean') return String(val);
    if (Array.isArray(val)) return `Array(${val.length})`;
    if (typeof val === 'object') return 'Object';
    return String(val);
  } catch (e) {
    return 'Error';
  }
}

/**
 * Recursively unwraps signals in an object or array.
 * Useful for logging complex state.
 */
export function $dump(obj) {
  if (isSignal(obj)) return $dump(obj());

  if (Array.isArray(obj)) {
    return obj.map($dump);
  }

  if (obj !== null && typeof obj === 'object') {
    const out = {};
    for (const key in obj) {
      out[key] = $dump(obj[key]);
    }
    return out;
  }

  return obj;
}

// Global access for convenience in console
if (typeof window !== 'undefined') {
  window.$dump = $dump;
}
