/* ActivityCharts is deliberately NOT re-exported here: CustomerPage lazy-loads
   it so recharts stays out of every chunk that never draws a chart. A static
   re-export through this barrel would defeat that split. */
export { ActivityPanel, type ActivityPanelProps } from './ActivityPanel'
export { ActivitySummaryCards, type ActivitySummaryCardsProps } from './ActivitySummaryCards'
export {
  ACTIVITY_TABS,
  activityColumns,
  activityTabLabel,
  type ActivityTab,
} from './activityColumns'
export { CopyButton, type CopyButtonProps } from './CopyButton'
export { CustomerAnalysesPanel, type CustomerAnalysesPanelProps } from './CustomerAnalysesPanel'
export { CustomerHeader, type CustomerHeaderProps } from './CustomerHeader'
export {
  TransactionDetailModal,
  type TransactionDetailModalProps,
} from './TransactionDetailModal'
export { useDebouncedValue } from './useDebouncedValue'
