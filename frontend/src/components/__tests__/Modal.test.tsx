/**
 * Focus management for the shared `<Modal />`.
 *
 * Every caller passes an inline arrow for `onClose`, so the dialog must not
 * key any of its focus work off that identity: a parent re-render while the
 * dialog is open (a pending delete, a refetch, a keystroke) used to tear the
 * effect down, hand focus back to the background trigger and then steal it
 * into the dialog again. These tests pin the invariants — trapped once on
 * open, untouched across re-renders, returned to the trigger on close.
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Modal } from '../ui/Modal'

/** Mirrors the real delete-confirmation flow: a pending flag re-renders the parent. */
function DeleteConfirmHarness({ onEscapeWhilePending }: { onEscapeWhilePending?: (pending: boolean) => void }) {
  const [open, setOpen] = useState(false)
  const [pending, setPending] = useState(false)

  return (
    <div>
      <button data-testid="trigger" onClick={() => setOpen(true)}>
        Open delete dialog
      </button>
      <Modal
        open={open}
        // Inline arrow on purpose — a new function identity on every render.
        onClose={() => {
          onEscapeWhilePending?.(pending)
          setOpen(false)
        }}
        title="Delete rule"
        closeOnOverlayClick={false}
        footer={
          <button data-testid="confirm" onClick={() => setPending((value) => !value)}>
            {pending ? 'Deleting…' : 'Confirm delete'}
          </button>
        }
      >
        <p>This cannot be undone.</p>
      </Modal>
    </div>
  )
}

/** Opens the dialog from the trigger and waits for the initial focus trap. */
async function openDialog() {
  const trigger = screen.getByTestId('trigger')
  act(() => trigger.focus())
  fireEvent.click(trigger)
  const dialog = await screen.findByRole('dialog')
  await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
  return { trigger, dialog }
}

/** Lets the effect's 0 ms focus timer fire, if one was (re-)scheduled. */
async function flushFocusTimer() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 20))
  })
}

describe('Modal focus management', () => {
  it('moves focus into the dialog when it opens', async () => {
    render(<DeleteConfirmHarness />)
    const { dialog } = await openDialog()
    expect(dialog).toContainElement(document.activeElement as HTMLElement)
    expect(document.body.style.overflow).toBe('hidden')
  })

  it('leaves focus alone when the parent re-renders while it is open', async () => {
    render(<DeleteConfirmHarness />)
    await openDialog()

    const confirm = screen.getByTestId('confirm')
    act(() => confirm.focus())
    expect(confirm).toHaveFocus()

    // Flips the parent's pending flag: a re-render with a brand new `onClose`.
    fireEvent.click(confirm)
    expect(confirm).toHaveFocus()

    await flushFocusTimer()
    expect(confirm).toHaveFocus()
    expect(screen.getByTestId('confirm')).toHaveTextContent('Deleting…')
  })

  it('installs the tab trap once, not once per parent render', async () => {
    const addEventListener = vi.spyOn(document, 'addEventListener')
    render(<DeleteConfirmHarness />)
    await openDialog()

    const confirm = screen.getByTestId('confirm')
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    await flushFocusTimer()

    const keydownListeners = addEventListener.mock.calls.filter(([type]) => type === 'keydown')
    expect(keydownListeners).toHaveLength(1)
  })

  it('still closes on Escape after a re-render, with the current handler', async () => {
    const onEscapeWhilePending = vi.fn()
    render(<DeleteConfirmHarness onEscapeWhilePending={onEscapeWhilePending} />)
    await openDialog()

    fireEvent.click(screen.getByTestId('confirm'))
    fireEvent.keyDown(document, { key: 'Escape' })

    // A stale handler captured at open time would report `false` here.
    expect(onEscapeWhilePending).toHaveBeenCalledWith(true)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('keeps the tab trap wrapping from the last control back to the first', async () => {
    render(<DeleteConfirmHarness />)
    const { dialog } = await openDialog()
    fireEvent.click(screen.getByTestId('confirm'))

    const focusable = [...dialog.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')]
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    act(() => last.focus())
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(first).toHaveFocus()
  })

  it('returns focus to the trigger and restores scrolling on close', async () => {
    render(<DeleteConfirmHarness />)
    const { trigger } = await openDialog()

    fireEvent.click(screen.getByLabelText('Close dialog'))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
    expect(document.body.style.overflow).toBe('')
  })
})
