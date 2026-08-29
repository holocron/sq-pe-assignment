import { ShieldOff } from 'lucide-react'
import { EmptyState } from './ui/EmptyState'
import { LinkButton } from './ui/LinkButton'

export interface AccessDeniedProps {
  /** What the operator tried to reach, for the explanation line. */
  resource?: string
}

/** Client-side 403 surface. The backend enforces roles independently. */
export function AccessDenied({ resource = 'this area' }: AccessDeniedProps) {
  return (
    <EmptyState
      icon={<ShieldOff className="size-5" />}
      title="Not permitted"
      description={`Your role does not grant access to ${resource}. Contact an administrator if you believe this is wrong.`}
      action={
        <LinkButton to="/dashboard" size="sm">
          Back to dashboard
        </LinkButton>
      }
    />
  )
}
