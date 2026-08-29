/**
 * Recursive renderer for a `{ op, conditions[] }` group node.
 *
 * Structure is carried by four cues so a deep boolean expression stays
 * readable: a quiet tint that alternates per nesting depth, a hairline box, a
 * 2px left rail that fades as the tree gets deeper, and an indented connector
 * line joining the children of a group. Every group can switch between
 * AND / OR / NOT, add a condition, add a nested group and — unless it is the
 * root — remove itself.
 */
import { FolderPlus, Plus, Trash2 } from 'lucide-react'
import { RULE_GROUP_OPS, type RuleGroup, type RuleGroupOp, type RuleNode } from '../../../api/types'
import { Button } from '../../../components/ui/Button'
import { cn } from '../../../lib/cn'
import { RULE_GROUP_OP_LABELS, isRuleGroup, type RulePath } from '../../../lib/rules'
import { ConditionRow } from './ConditionRow'
import { groupLabel, issuesAt, type Catalog, type RuleIssues } from './ruleModel'

/** Deeper nesting than this is unreadable; the DSL itself has no limit. */
export const MAX_GROUP_DEPTH = 4

/**
 * One quiet tint per nesting level. They alternate rather than getting
 * progressively darker, so depth 4 is still a legible reading surface.
 */
const DEPTH_SURFACE = [
  'bg-surface-2/55',
  'bg-surface',
  'bg-surface-2/70',
  'bg-surface',
  'bg-surface-2/70',
] as const

/** The left rail loses weight with depth: the root reads as the trunk. */
const DEPTH_RAIL = [
  'border-l-accent',
  'border-l-accent/50',
  'border-l-border-strong',
  'border-l-border-strong',
  'border-l-border-strong',
] as const

function depthClass(list: readonly string[], depth: number): string {
  return list[Math.min(depth, list.length - 1)] ?? list[0] ?? ''
}

export interface BuilderActions {
  replaceNode: (path: RulePath, node: RuleNode) => void
  removeNode: (path: RulePath) => void
  duplicateNode: (path: RulePath) => void
  addCondition: (path: RulePath) => void
  addGroup: (path: RulePath) => void
}

export interface ConditionGroupProps {
  group: RuleGroup
  path: RulePath
  catalog: Catalog
  issues: RuleIssues
  actions: BuilderActions
  depth?: number
  disabled?: boolean
}

/**
 * Segmented control for the group operator.
 *
 * The segments show the DSL token (AND / OR / NOT) because that is what gets
 * persisted; the plain-English reading sits beside the control and in each
 * segment's accessible name, so the meaning is never hidden behind jargon.
 */
function GroupOpToggle({
  op,
  title,
  disabled,
  onChange,
}: {
  op: RuleGroupOp
  title: string
  disabled: boolean
  onChange: (op: RuleGroupOp) => void
}) {
  return (
    <div
      role="group"
      aria-label={`Combine conditions in ${title}`}
      className="inline-flex items-center gap-px rounded-xs border border-border bg-surface-2 p-0.5"
    >
      {RULE_GROUP_OPS.map((option) => {
        const active = option === op
        return (
          <button
            key={option}
            type="button"
            disabled={disabled}
            aria-pressed={active}
            aria-label={`Set ${title} to ${RULE_GROUP_OP_LABELS[option]} (${option})`}
            title={RULE_GROUP_OP_LABELS[option]}
            onClick={() => onChange(option)}
            className={cn(
              'h-6 min-w-11 rounded-xxs border-b-2 px-2 text-2xs font-semibold tracking-caption uppercase',
              'transition-colors outline-none',
              'focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1',
              'focus-visible:ring-offset-surface-2',
              'disabled:cursor-not-allowed disabled:opacity-60',
              // The selected segment is a raised surface under a brand-orange
              // rail: the brand still marks the choice, but the label keeps
              // full text contrast at 11px (white on the brand orange is 3.2:1).
              active
                ? 'border-b-accent bg-surface text-fg shadow-panel'
                : 'border-b-transparent text-muted hover:bg-surface-3 hover:text-fg',
            )}
          >
            {option}
          </button>
        )
      })}
    </div>
  )
}

export function ConditionGroup({
  group,
  path,
  catalog,
  issues,
  actions,
  depth = 0,
  disabled = false,
}: ConditionGroupProps) {
  const title = groupLabel(path)
  const groupIssues = issuesAt(issues, path)
  const invalid = groupIssues.length > 0
  const isRoot = path.length === 0
  const joiner = group.op === 'NOT' ? 'AND' : group.op
  const canNest = depth < MAX_GROUP_DEPTH

  return (
    <div
      role="group"
      aria-label={title}
      className={cn(
        'rounded-md border border-l-2 p-2',
        invalid
          ? 'border-danger/50 border-l-danger bg-danger-soft/30'
          : cn('border-border', depthClass(DEPTH_RAIL, depth), depthClass(DEPTH_SURFACE, depth)),
      )}
    >
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5">
        <span className="text-2xs font-semibold tracking-caption text-subtle uppercase">{title}</span>
        <GroupOpToggle
          op={group.op}
          title={title}
          disabled={disabled}
          onChange={(op) => actions.replaceNode(path, { ...group, op })}
        />
        <span className="text-2xs text-subtle">
          {group.op === 'NOT'
            ? 'matches when the condition below does not'
            : `matches when ${group.op === 'AND' ? 'every' : 'any'} condition below matches`}
        </span>

        {/* Add/remove stay quiet bordered controls — the primary save action in
            the dialog footer is the only brand-filled button on the screen. */}
        <div className="ml-auto flex shrink-0 items-center gap-1">
          <Button
            variant="secondary"
            size="sm"
            className="border-dashed"
            aria-label={`Add condition to ${title}`}
            disabled={disabled}
            onClick={() => actions.addCondition(path)}
            iconLeft={<Plus className="size-3.5" aria-hidden="true" />}
          >
            Condition
          </Button>
          <Button
            variant="secondary"
            size="sm"
            className="border-dashed"
            aria-label={`Add nested group to ${title}`}
            title={canNest ? undefined : 'Maximum nesting depth reached'}
            disabled={disabled || !canNest}
            onClick={() => actions.addGroup(path)}
            iconLeft={<FolderPlus className="size-3.5" aria-hidden="true" />}
          >
            Group
          </Button>
          {isRoot ? null : (
            <Button
              variant="ghost"
              size="icon"
              className="size-8"
              aria-label={`Remove ${title}`}
              title="Remove group"
              disabled={disabled}
              onClick={() => actions.removeNode(path)}
            >
              <Trash2 className="size-3.5" aria-hidden="true" />
            </Button>
          )}
        </div>
      </div>

      {invalid ? (
        <p role="alert" className="mt-1.5 text-2xs font-medium text-danger-fg">
          {groupIssues.join(' · ')}
        </p>
      ) : null}

      {group.conditions.length === 0 ? (
        <p className="mt-2 rounded-xs border border-dashed border-border-strong px-3 py-4 text-center text-xs text-subtle">
          This group is empty. Add a condition or a nested group.
        </p>
      ) : (
        /* The hairline on the left is the tree connector: it groups the
           children visually and gives every level the same indentation step. */
        <div className="mt-2 ml-1 flex flex-col gap-1 border-l border-border pl-3">
          {group.conditions.map((child, index) => {
            const childPath: RulePath = [...path, index]
            return (
              <div key={childPath.join('.')} className="flex flex-col gap-1">
                {index > 0 ? (
                  <div className="flex items-center gap-2">
                    <span className="rounded-xxs border border-border bg-surface px-1.5 py-px text-2xs font-semibold tracking-caption text-muted uppercase">
                      {joiner}
                    </span>
                    <span className="h-px flex-1 bg-border" aria-hidden="true" />
                  </div>
                ) : null}
                {isRuleGroup(child) ? (
                  <ConditionGroup
                    group={child}
                    path={childPath}
                    catalog={catalog}
                    issues={issues}
                    actions={actions}
                    depth={depth + 1}
                    disabled={disabled}
                  />
                ) : (
                  <ConditionRow
                    condition={child}
                    path={childPath}
                    catalog={catalog}
                    issues={issuesAt(issues, childPath)}
                    onChange={actions.replaceNode}
                    onRemove={actions.removeNode}
                    onDuplicate={actions.duplicateNode}
                    disabled={disabled}
                  />
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
