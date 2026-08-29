import { RotateCw, ShieldCheck, UserRound, Users as UsersIcon } from 'lucide-react'
import { useUsers } from '../../api/users'
import type { AppUser } from '../../api/types'
import {
  Badge,
  Button,
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
  EmptyState,
  PageHeader,
  RoleBadge,
  StatCard,
  Table,
  type Column,
} from '../../components'
import {
  EM_DASH,
  formatDateTime,
  formatNumber,
  formatRelativeTime,
  shortId,
} from '../../lib/format'

/** Two-letter monogram, so the directory scans like a real address book. */
function initials(name: string, fallback: string): string {
  const parts = name
    .trim()
    .split(/\s+/)
    .filter((part) => part.length > 0)
  const letters = parts.length > 0 ? parts : [fallback]
  const first = letters[0]?.[0] ?? ''
  const last = letters.length > 1 ? (letters[letters.length - 1]?.[0] ?? '') : ''
  return `${first}${last}`.toUpperCase() || '?'
}

const columns: Column<AppUser>[] = [
  {
    key: 'fullName',
    header: 'Name',
    className: 'min-w-56',
    cell: (user) => (
      <div className="flex min-w-0 items-center gap-2.5">
        <span
          aria-hidden="true"
          className="flex size-7 shrink-0 items-center justify-center rounded-full border border-border bg-surface-2 text-2xs font-semibold text-muted"
        >
          {initials(user.fullName, user.username)}
        </span>
        <span className="min-w-0">
          <span className="block truncate font-medium text-fg">{user.fullName}</span>
          <span className="block truncate font-mono text-2xs text-subtle">{user.username}</span>
        </span>
      </div>
    ),
  },
  {
    key: 'role',
    header: 'Role',
    className: 'w-32',
    cell: (user) => <RoleBadge role={user.role} />,
  },
  {
    key: 'enabled',
    header: 'Account',
    className: 'w-32',
    cell: (user) => (
      <Badge
        tone={user.enabled ? 'success' : 'neutral'}
        dot
        title={user.enabled ? 'Can sign in' : 'Sign-in is blocked'}
      >
        {user.enabled ? 'Enabled' : 'Disabled'}
      </Badge>
    ),
  },
  {
    key: 'userId',
    header: 'User id',
    className: 'w-32',
    cell: (user) => (
      <span className="font-mono text-2xs text-subtle" title={user.userId}>
        {shortId(user.userId)}
      </span>
    ),
  },
  {
    key: 'createdAt',
    header: 'Created',
    className: 'w-44',
    cell: (user) => (
      <span
        className="numeric whitespace-nowrap text-muted"
        title={formatRelativeTime(user.createdAt)}
      >
        {formatDateTime(user.createdAt)}
      </span>
    ),
  },
]

/**
 * Read-only directory of `app_users`. The API exposes no user mutations
 * (BUILD_SPEC section 5 lists `GET /api/users` only), so this view never
 * pretends to offer editing.
 */
export function UsersPage() {
  const users = useUsers()
  const rows = users.data ?? []
  const admins = rows.filter((user) => user.role === 'ADMIN').length
  const operators = rows.filter((user) => user.role === 'OPERATOR').length
  const disabled = rows.filter((user) => !user.enabled).length

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Users"
        description="Accounts that can sign in to Customer Activity Analytics. Roles are enforced server-side on every request as well as in the interface."
        actions={
          <Button
            variant="secondary"
            onClick={() => void users.refetch()}
            loading={users.isFetching}
            iconLeft={<RotateCw className="size-3.5" />}
          >
            Refresh
          </Button>
        }
      />

      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard
          label="Users"
          value={users.isError ? EM_DASH : formatNumber(rows.length)}
          loading={users.isLoading}
          hint={
            users.isError
              ? 'Directory unavailable'
              : disabled > 0
                ? `${formatNumber(disabled)} disabled`
                : 'All accounts enabled'
          }
          icon={<UsersIcon className="size-4" />}
          numeric
        />
        <StatCard
          label="Admins"
          value={users.isError ? EM_DASH : formatNumber(admins)}
          loading={users.isLoading}
          hint="Rules, knowledge base, users"
          icon={<ShieldCheck className="size-4" />}
          numeric
        />
        <StatCard
          label="Operators"
          value={users.isError ? EM_DASH : formatNumber(operators)}
          loading={users.isLoading}
          hint="Customer review and analyses"
          icon={<UserRound className="size-4" />}
          numeric
        />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Directory</CardTitle>
          <CardDescription>
            Read-only — the API exposes no user mutations, so accounts are managed by the database
            seed and by operations.
          </CardDescription>
        </CardHeader>
        <Table
          dense
          columns={columns}
          rows={rows}
          rowKey={(user) => user.userId}
          loading={users.isLoading}
          error={users.error}
          onRetry={() => void users.refetch()}
          caption="Application users"
          stickyHeader
          empty={
            <EmptyState
              icon={<UsersIcon className="size-5" />}
              title="No users returned"
              description="The backend reported an empty user directory. Check that the seed migration ran."
            />
          }
        />
      </Card>
    </div>
  )
}
