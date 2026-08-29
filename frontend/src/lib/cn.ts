import { clsx, type ClassValue } from 'clsx'

export type { ClassValue }

/**
 * Joins class names conditionally.
 *
 * There is no tailwind-merge in this project, so later arguments do NOT
 * automatically win over earlier ones for conflicting Tailwind utilities.
 * Component authors put the caller-supplied `className` last and avoid
 * emitting a conflicting utility from the base styles when it is meant to be
 * overridable.
 */
export function cn(...inputs: ClassValue[]): string {
  return clsx(inputs)
}
