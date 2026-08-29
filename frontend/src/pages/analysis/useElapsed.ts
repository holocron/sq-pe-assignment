import { useMemo, useSyncExternalStore } from 'react'

/**
 * A one-second wall clock, shared by every component that watches it.
 *
 * The clock is an external system, so it is read through
 * `useSyncExternalStore` rather than by calling `Date.now()` during render.
 * The interval only runs while at least one component is subscribed.
 */
let clockNow = Date.now()
const listeners = new Set<() => void>()
let intervalId: number | null = null

function tick(): void {
  clockNow = Date.now()
  for (const listener of listeners) listener()
}

function subscribeToClock(listener: () => void): () => void {
  // Refresh on subscribe so a newly mounted clock is never a tick behind.
  clockNow = Date.now()
  listeners.add(listener)
  if (intervalId === null) intervalId = window.setInterval(tick, 1000)
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0 && intervalId !== null) {
      window.clearInterval(intervalId)
      intervalId = null
    }
  }
}

function getClockSnapshot(): number {
  return clockNow
}

/** Subscribing to nothing keeps the hook order stable when it is idle. */
const idleSubscribe = () => () => undefined

/**
 * Milliseconds since `startedAt`, updating once a second while `active`.
 *
 * The analysis agent is slow (a run takes minutes, not seconds), so the page
 * must always be able to show that time is passing — otherwise a healthy run
 * looks frozen. Returns null when the timestamp cannot be parsed.
 */
export function useElapsedMs(
  startedAt: string | null | undefined,
  active: boolean,
): number | null {
  const startMs = useMemo(() => (startedAt ? Date.parse(startedAt) : Number.NaN), [startedAt])
  const now = useSyncExternalStore(active ? subscribeToClock : idleSubscribe, getClockSnapshot)

  if (Number.isNaN(startMs)) return null
  return Math.max(0, now - startMs)
}
