import { CircleCheckBig, CircleX, Info, TriangleAlert, X } from 'lucide-react'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'
import { cn } from '../../lib/cn'

export type ToastTone = 'default' | 'success' | 'error' | 'warning' | 'info'

export interface ToastOptions {
  title: string
  description?: ReactNode
  tone?: ToastTone
  /** Milliseconds before auto-dismiss; 0 keeps it until dismissed. */
  duration?: number
}

export interface ToastRecord {
  id: string
  title: string
  description?: ReactNode
  tone: ToastTone
  duration: number
}

export interface ToastApi {
  toast: (options: ToastOptions) => string
  success: (title: string, description?: ReactNode) => string
  error: (title: string, description?: ReactNode) => string
  info: (title: string, description?: ReactNode) => string
  warning: (title: string, description?: ReactNode) => string
  dismiss: (id: string) => void
  dismissAll: () => void
}

const ToastContext = createContext<ToastApi | null>(null)

const TONE_STYLES: Record<ToastTone, string> = {
  default: 'border-border bg-surface text-fg',
  success: 'border-success/40 bg-surface text-fg',
  error: 'border-danger/40 bg-surface text-fg',
  warning: 'border-warning/40 bg-surface text-fg',
  info: 'border-info/40 bg-surface text-fg',
}

const TONE_ICONS: Record<ToastTone, ReactNode> = {
  default: <Info className="size-4 text-muted" />,
  success: <CircleCheckBig className="size-4 text-success" />,
  error: <CircleX className="size-4 text-danger" />,
  warning: <TriangleAlert className="size-4 text-warning" />,
  info: <Info className="size-4 text-info" />,
}

let toastCounter = 0

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastRecord[]>([])
  const timers = useRef(new Map<string, number>())

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((item) => item.id !== id))
    const timer = timers.current.get(id)
    if (timer) {
      window.clearTimeout(timer)
      timers.current.delete(id)
    }
  }, [])

  const dismissAll = useCallback(() => {
    for (const timer of timers.current.values()) window.clearTimeout(timer)
    timers.current.clear()
    setToasts([])
  }, [])

  const toast = useCallback(
    (options: ToastOptions): string => {
      toastCounter += 1
      const id = `toast-${toastCounter}`
      const record: ToastRecord = {
        id,
        title: options.title,
        description: options.description,
        tone: options.tone ?? 'default',
        duration: options.duration ?? 5000,
      }
      setToasts((current) => [...current, record])
      if (record.duration > 0) {
        const timer = window.setTimeout(() => dismiss(id), record.duration)
        timers.current.set(id, timer)
      }
      return id
    },
    [dismiss],
  )

  useEffect(() => {
    const pending = timers.current
    return () => {
      for (const timer of pending.values()) window.clearTimeout(timer)
      pending.clear()
    }
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      toast,
      success: (title, description) => toast({ title, description, tone: 'success' }),
      error: (title, description) => toast({ title, description, tone: 'error', duration: 8000 }),
      info: (title, description) => toast({ title, description, tone: 'info' }),
      warning: (title, description) => toast({ title, description, tone: 'warning' }),
      dismiss,
      dismissAll,
    }),
    [toast, dismiss, dismissAll],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      {typeof document !== 'undefined'
        ? createPortal(
            <div
              aria-live="polite"
              aria-atomic="false"
              className="pointer-events-none fixed right-4 bottom-4 z-[60] flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2"
            >
              {toasts.map((item) => (
                <div
                  key={item.id}
                  role="status"
                  className={cn(
                    'pointer-events-auto flex items-start gap-2.5 rounded-md border p-3 shadow-popover animate-rise-in',
                    TONE_STYLES[item.tone],
                  )}
                >
                  <span aria-hidden="true" className="mt-px shrink-0">
                    {TONE_ICONS[item.tone]}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">{item.title}</p>
                    {item.description ? (
                      <p className="mt-0.5 text-xs break-words text-muted">{item.description}</p>
                    ) : null}
                  </div>
                  <button
                    type="button"
                    onClick={() => dismiss(item.id)}
                    aria-label="Dismiss notification"
                    className="-mt-0.5 -mr-0.5 shrink-0 rounded-xs p-1 text-subtle transition-colors hover:bg-surface-2 hover:text-fg focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                  >
                    <X className="size-3.5" />
                  </button>
                </div>
              ))}
            </div>,
            document.body,
          )
        : null}
    </ToastContext.Provider>
  )
}

export function useToast(): ToastApi {
  const context = useContext(ToastContext)
  if (!context) throw new Error('useToast must be used inside a <ToastProvider>')
  return context
}
