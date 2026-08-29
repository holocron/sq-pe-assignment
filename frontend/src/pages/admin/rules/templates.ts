/**
 * Starter conditions.
 *
 * Every template is a worked example of the three things the guidance asks for
 * — a concrete threshold, a time window, and facts the agent's tools can
 * actually fetch — plus an explicit hint about how to score, because the score
 * is now the agent's estimate rather than a table lookup.
 */
import type { RuleScope } from '../../../api/types'

export interface RuleTemplate {
  id: string
  /** Short name for the chip / card. */
  title: string
  /** Suggested `rule_name`, used when the author has not typed one. */
  ruleName: string
  appliesTo: RuleScope
  weight: number
  condition: string
}

export const RULE_TEMPLATES: RuleTemplate[] = [
  {
    id: 'structuring',
    title: 'Structuring',
    ruleName: 'Structuring — repeated payments below the reporting threshold',
    appliesTo: 'PAYMENT',
    weight: 30,
    condition:
      'Triggered when the customer makes three or more payments with an amount between 8 000 and 9 999.99 within any rolling 24 hours, while no single payment reaches the 10 000 reporting threshold. ' +
      'Repeated near-threshold amounts to the same receiver_account are a stronger signal than the count alone. ' +
      'Score near the full weight when there are five or more such payments, and around half when there are exactly three.',
  },
  {
    id: 'sanctioned',
    title: 'Sanctioned jurisdiction',
    ruleName: 'Payment to a sanctioned or high-risk jurisdiction',
    appliesTo: 'PAYMENT',
    weight: 35,
    condition:
      'Triggered when a payment is sent to a beneficiary bank in a sanctioned or high-risk jurisdiction — payment.receiver_bank_country in IR, KP, SY, RU, BY or AF. ' +
      'A single such payment triggers the rule regardless of amount. ' +
      'Score at the full weight when the amount exceeds 10 000 or the customer has no earlier activity involving that country, otherwise around two thirds.',
  },
  {
    id: 'decline-burst',
    title: 'Card decline burst',
    ruleName: 'Card-not-present success after a decline burst',
    appliesTo: 'CARD',
    weight: 30,
    condition:
      'Triggered when a card-not-present authorisation (card.card_present is false) succeeds within 24 hours of two or more declined authorisations on the same customer. ' +
      'Cite the successful transaction as the evidence, and treat a card.decline_reason of "Do not honour" or "Insufficient funds" as the stronger pattern. ' +
      'Score lower when the declines and the success are more than 12 hours apart.',
  },
  {
    id: 'privacy-chain',
    title: 'Privacy-chain transfer',
    ruleName: 'Privacy-chain or unattributed crypto transfer',
    appliesTo: 'CRYPTO',
    weight: 40,
    condition:
      'Triggered when the customer settles on a privacy chain (crypto.blockchain is XMR, ZEC or DASH) or transfers to a destination wallet with no attributed exchange (crypto.exchange_name is empty). ' +
      'Score at the full weight when both hold on the same transfer or the amount exceeds 20 000; score around a third when only the missing exchange attribution applies.',
  },
  {
    id: 'velocity',
    title: 'Velocity spike',
    ruleName: 'Transaction velocity and value spike within 24 hours',
    appliesTo: 'ALL',
    weight: 20,
    condition:
      'Triggered when the customer makes eight or more transactions within any rolling 24 hours and the total amount over the same window exceeds 50 000. ' +
      'Use the agg.tx_count_24h and agg.amount_sum_24h aggregates, and state in the rationale by how much the customer’s usual daily pattern is exceeded. ' +
      'Score proportionally: full weight at double the thresholds, half at the thresholds themselves.',
  },
  {
    id: 'out-of-hours',
    title: 'Out-of-hours value',
    ruleName: 'High-value activity outside normal business hours',
    appliesTo: 'ALL',
    weight: 10,
    condition:
      'Triggered when a transaction above 5 000 is created between 00:00 and 05:00 UTC (hour_of_day 0 to 5). ' +
      'A single out-of-hours transaction is a weak signal on its own, so score near the low end unless three or more occur in the same night or the amount exceeds 25 000.',
  },
]
