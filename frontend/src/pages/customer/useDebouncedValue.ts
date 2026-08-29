import { useEffect, useState } from 'react'

/**
 * Debounces a rapidly changing value (a search box, a filter field) so the
 * query layer only sees settled input. Shared by the dashboard search and the
 * customer activity filters.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    if (value === debounced) return
    const timer = window.setTimeout(() => setDebounced(value), delayMs)
    return () => window.clearTimeout(timer)
  }, [value, delayMs, debounced])

  return debounced
}
