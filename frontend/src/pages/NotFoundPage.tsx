import { FileQuestionMark } from 'lucide-react'
import { EmptyState } from '../components/ui/EmptyState'
import { LinkButton } from '../components/ui/LinkButton'

export function NotFoundPage() {
  return (
    <EmptyState
      icon={<FileQuestionMark className="size-5" />}
      title="Page not found"
      description="The page you requested does not exist or has been moved."
      action={
        <LinkButton to="/dashboard" size="sm">
          Back to dashboard
        </LinkButton>
      }
    />
  )
}
