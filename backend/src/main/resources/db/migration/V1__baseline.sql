-- =============================================================================
-- V1 - Baseline schema: the seven assignment tables.
--
-- Column names and types are EXACTLY as specified in the assignment. Nothing is
-- renamed, nothing is added. Only constraints, indexes and comments are layered
-- on top.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

-- -----------------------------------------------------------------------------
-- Native PostgreSQL enum types (mapped in JPA with @JdbcTypeCode(NAMED_ENUM)).
-- CREATE TYPE has no IF NOT EXISTS, so guard it explicitly.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'activity_type') THEN
        CREATE TYPE activity_type AS ENUM ('CARD', 'PAYMENT', 'CRYPTO');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'rule_scope') THEN
        CREATE TYPE rule_scope AS ENUM ('CARD', 'PAYMENT', 'CRYPTO', 'ALL');
    END IF;
END
$$;

-- -----------------------------------------------------------------------------
-- customers
-- -----------------------------------------------------------------------------
CREATE TABLE customers (
    customer_id UUID         NOT NULL,
    last_name   VARCHAR(120) NOT NULL,
    first_name  VARCHAR(120) NOT NULL,
    dob         DATE         NOT NULL,
    country     CHAR(2)      NOT NULL,
    CONSTRAINT pk_customers PRIMARY KEY (customer_id)
);

COMMENT ON TABLE  customers            IS 'Bank customers whose activity is analysed.';
COMMENT ON COLUMN customers.country    IS 'ISO 3166-1 alpha-2 country of birth.';

CREATE INDEX idx_customers_last_name  ON customers (lower(last_name));
CREATE INDEX idx_customers_first_name ON customers (lower(first_name));

-- -----------------------------------------------------------------------------
-- transactions - the base activity record, one row per customer activity
-- -----------------------------------------------------------------------------
CREATE TABLE transactions (
    transaction_id UUID          NOT NULL,
    customer_id    UUID          NOT NULL,
    activity_type  activity_type NOT NULL,
    amount         DECIMAL(18,2) NOT NULL,
    currency       VARCHAR(10)   NOT NULL,
    status         VARCHAR(30)   NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id),
    CONSTRAINT fk_transactions_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id) ON DELETE CASCADE
);

COMMENT ON COLUMN transactions.currency   IS 'ISO currency code or crypto ticker.';
COMMENT ON COLUMN transactions.status     IS 'Completed / Pending / Failed / Reversed.';
COMMENT ON COLUMN transactions.created_at IS 'UTC instant the transaction occurred.';

CREATE INDEX idx_transactions_customer_id       ON transactions (customer_id);
CREATE INDEX idx_transactions_created_at        ON transactions (created_at DESC);
CREATE INDEX idx_transactions_customer_created  ON transactions (customer_id, created_at DESC);
CREATE INDEX idx_transactions_customer_type     ON transactions (customer_id, activity_type);
CREATE INDEX idx_transactions_status            ON transactions (status);
CREATE INDEX idx_transactions_amount            ON transactions (amount);

-- -----------------------------------------------------------------------------
-- card_activity / payment_activity / crypto_activity
-- Detail tables sharing their primary key with transactions (1:0..1).
-- -----------------------------------------------------------------------------
CREATE TABLE card_activity (
    transaction_id     UUID         NOT NULL,
    card_pan           VARCHAR(25)  NOT NULL,
    card_type          VARCHAR(20)  NOT NULL,
    merchant_name      VARCHAR(160) NOT NULL,
    mcc_code           VARCHAR(4)   NOT NULL,
    card_present       BOOLEAN      NOT NULL,
    authorization_code VARCHAR(20)  NOT NULL,
    decline_reason     VARCHAR(120),
    CONSTRAINT pk_card_activity PRIMARY KEY (transaction_id),
    CONSTRAINT fk_card_activity_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id) ON DELETE CASCADE
);

COMMENT ON COLUMN card_activity.card_pan       IS 'Masked PAN, e.g. ****1234.';
COMMENT ON COLUMN card_activity.card_type      IS 'Debit / Credit / Prepaid.';
COMMENT ON COLUMN card_activity.card_present   IS 'FALSE means card-not-present.';
COMMENT ON COLUMN card_activity.decline_reason IS 'Populated only for declined authorisations.';

CREATE INDEX idx_card_activity_merchant ON card_activity (lower(merchant_name));
CREATE INDEX idx_card_activity_mcc      ON card_activity (mcc_code);

CREATE TABLE payment_activity (
    transaction_id        UUID        NOT NULL,
    payment_method        VARCHAR(20) NOT NULL,
    sender_account        VARCHAR(40) NOT NULL,
    receiver_account      VARCHAR(40) NOT NULL,
    receiver_bank_country CHAR(2)     NOT NULL,
    CONSTRAINT pk_payment_activity PRIMARY KEY (transaction_id),
    CONSTRAINT fk_payment_activity_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id) ON DELETE CASCADE
);

COMMENT ON COLUMN payment_activity.payment_method        IS 'ACH / Wire / SWIFT / P2P.';
COMMENT ON COLUMN payment_activity.receiver_bank_country IS 'ISO 3166-1 alpha-2 beneficiary bank country.';

CREATE INDEX idx_payment_activity_country ON payment_activity (receiver_bank_country);
CREATE INDEX idx_payment_activity_method  ON payment_activity (payment_method);

CREATE TABLE crypto_activity (
    transaction_id      UUID         NOT NULL,
    blockchain          VARCHAR(30)  NOT NULL,
    wallet_address_from VARCHAR(120) NOT NULL,
    wallet_address_to   VARCHAR(120) NOT NULL,
    tx_hash             VARCHAR(120) NOT NULL,
    exchange_name       VARCHAR(80),
    CONSTRAINT pk_crypto_activity PRIMARY KEY (transaction_id),
    CONSTRAINT fk_crypto_activity_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id) ON DELETE CASCADE
);

COMMENT ON COLUMN crypto_activity.blockchain    IS 'BTC / ETH / USDT / XMR / ...';
COMMENT ON COLUMN crypto_activity.exchange_name IS 'Counterparty exchange, NULL when unattributed.';

CREATE INDEX idx_crypto_activity_blockchain ON crypto_activity (blockchain);
CREATE INDEX idx_crypto_activity_wallet_to  ON crypto_activity (wallet_address_to);

-- -----------------------------------------------------------------------------
-- risk_rules
-- -----------------------------------------------------------------------------
CREATE TABLE risk_rules (
    rule_id         UUID         NOT NULL,
    rule_name       VARCHAR(160) NOT NULL,
    applies_to      rule_scope   NOT NULL,
    threshold_logic TEXT         NOT NULL,
    weight          DECIMAL(5,2) NOT NULL,
    CONSTRAINT pk_risk_rules PRIMARY KEY (rule_id)
);

COMMENT ON COLUMN risk_rules.threshold_logic IS 'Rule condition in natural language: the sentence the ReAct agent reads, gathers evidence for and judges.';
COMMENT ON COLUMN risk_rules.weight          IS 'Score added when the rule matches; the per-rule score is capped at this weight.';

CREATE UNIQUE INDEX uq_risk_rules_rule_name ON risk_rules (lower(rule_name));
CREATE INDEX idx_risk_rules_applies_to      ON risk_rules (applies_to);

-- -----------------------------------------------------------------------------
-- risk_assessments
--
-- DOCUMENTED DEVIATION: the assignment lists assessment_id as the primary key but
-- also requires that a single analysis produces many rows sharing one common
-- assessment_id. Those two statements are mutually exclusive. Resolution:
--   * the columns stay exactly as specified,
--   * assessment_id is the shared identifier of one analysis run,
--   * the PRIMARY KEY is the composite (assessment_id, transaction_id, rule_id).
-- One row is written per (transaction, rule) pair evaluated - including rules that
-- did NOT trigger, which get score_contribution = 0.00. Coverage of every rule that
-- had at least one transaction in scope is therefore auditable from this table
-- alone.
--
-- The one exception is structural, not an omission: transaction_id is NOT NULL, so
-- a rule whose scope contains ZERO transactions (an ALL-scoped rule for a customer
-- with no activity at all) has nothing to key a row on and writes none. That rule
-- is still evaluated, and analysis_runs.rules_evaluated / rules_total /
-- coverage_complete, together with the run's trace, are the authoritative record
-- that it was checked.
-- -----------------------------------------------------------------------------
CREATE TABLE risk_assessments (
    assessment_id      UUID         NOT NULL,
    transaction_id     UUID         NOT NULL,
    rule_id            UUID         NOT NULL,
    triggered_at       TIMESTAMP    NOT NULL,
    score_contribution DECIMAL(5,2) NOT NULL,
    CONSTRAINT pk_risk_assessments PRIMARY KEY (assessment_id, transaction_id, rule_id),
    CONSTRAINT fk_risk_assessments_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (transaction_id) ON DELETE CASCADE,
    CONSTRAINT fk_risk_assessments_rule FOREIGN KEY (rule_id)
        REFERENCES risk_rules (rule_id) ON DELETE CASCADE
);

COMMENT ON COLUMN risk_assessments.assessment_id      IS 'Shared identifier of one analysis run; not unique on its own.';
COMMENT ON COLUMN risk_assessments.score_contribution IS 'Points added to the risk score; 0.00 for an evaluated-but-not-triggered rule.';

CREATE INDEX idx_risk_assessments_transaction ON risk_assessments (transaction_id);
CREATE INDEX idx_risk_assessments_rule        ON risk_assessments (rule_id);
CREATE INDEX idx_risk_assessments_triggered   ON risk_assessments (triggered_at DESC);
CREATE INDEX idx_risk_assessments_positive    ON risk_assessments (assessment_id) WHERE score_contribution > 0;
