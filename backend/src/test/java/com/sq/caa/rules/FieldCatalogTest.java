package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.RuleScope;
import org.junit.jupiter.api.Test;

/**
 * The catalog is a shared contract: the evaluator resolves these names, the validator rejects
 * anything else, and the frontend editor is generated from it. Renaming an entry breaks stored
 * rules, so the exact list is pinned here.
 */
class FieldCatalogTest {

    @Test
    void containsExactlyTheFieldsOfTheSpecification() {
        assertThat(FieldCatalog.fieldNames()).containsExactly(
                "amount",
                "currency",
                "status",
                "activity_type",
                "created_at",
                "hour_of_day",
                "customer.country",
                "customer.age",
                "card.mcc_code",
                "card.card_type",
                "card.card_present",
                "card.merchant_name",
                "card.decline_reason",
                "payment.payment_method",
                "payment.receiver_bank_country",
                "payment.sender_account",
                "payment.receiver_account",
                "crypto.blockchain",
                "crypto.exchange_name",
                "crypto.wallet_address_to",
                "agg.tx_count_24h",
                "agg.amount_sum_24h",
                "agg.failed_count_24h",
                "agg.distinct_countries_30d",
                "agg.crypto_ratio_30d",
                "agg.max_amount_30d");
    }

    @Test
    void everyEntryIsUsableByAnEditor() {
        for (FieldDefinition definition : FieldCatalog.entries()) {
            assertThat(definition.label()).as("label of %s", definition.field()).isNotBlank();
            assertThat(definition.description()).as("description of %s", definition.field()).isNotBlank();
            assertThat(definition.type()).as("type of %s", definition.field()).isNotNull();
            assertThat(definition.appliesTo()).as("scope of %s", definition.field()).isNotNull();
            assertThat(definition.allowedOperators()).as("operators of %s", definition.field()).isNotEmpty();
            if (definition.optionsClosed()) {
                assertThat(definition.options()).as("options of %s", definition.field()).isNotEmpty();
            }
        }
    }

    @Test
    void enumeratedFieldsCarryTheirOptions() {
        assertThat(FieldCatalog.find("status").orElseThrow().options())
                .containsExactly("Completed", "Pending", "Failed", "Reversed");
        assertThat(FieldCatalog.find("activity_type").orElseThrow().options())
                .containsExactly("CARD", "PAYMENT", "CRYPTO");
        assertThat(FieldCatalog.find("card.card_type").orElseThrow().options())
                .containsExactly("Debit", "Credit", "Prepaid");
        assertThat(FieldCatalog.find("payment.payment_method").orElseThrow().options())
                .containsExactly("ACH", "Wire", "SWIFT", "P2P");
        assertThat(FieldCatalog.find("crypto.blockchain").orElseThrow().optionsClosed()).isFalse();
    }

    @Test
    void operatorsFollowTheValueType() {
        assertThat(FieldCatalog.find("amount").orElseThrow().allowedOperators())
                .containsExactly(RuleOperator.GT, RuleOperator.GTE, RuleOperator.LT, RuleOperator.LTE,
                        RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.BETWEEN, RuleOperator.IN,
                        RuleOperator.NOT_IN, RuleOperator.IS_NULL, RuleOperator.NOT_NULL);
        assertThat(FieldCatalog.find("card.merchant_name").orElseThrow().allowedOperators())
                .contains(RuleOperator.CONTAINS, RuleOperator.NOT_CONTAINS, RuleOperator.MATCHES)
                .doesNotContain(RuleOperator.GT, RuleOperator.BETWEEN);
        assertThat(FieldCatalog.find("card.card_present").orElseThrow().allowedOperators())
                .containsExactly(RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.IS_NULL,
                        RuleOperator.NOT_NULL);
        assertThat(FieldCatalog.find("created_at").orElseThrow().allowedOperators())
                .contains(RuleOperator.BETWEEN, RuleOperator.GTE);
    }

    @Test
    void scopingHidesFieldsThatDoNotExistOnAnActivityType() {
        assertThat(FieldCatalog.entriesFor(RuleScope.CARD))
                .extracting(FieldDefinition::field)
                .contains("amount", "agg.tx_count_24h", "card.mcc_code")
                .doesNotContain("payment.payment_method", "crypto.blockchain");
        assertThat(FieldCatalog.entriesFor(RuleScope.ALL)).hasSameSizeAs(FieldCatalog.entries());
    }

    @Test
    void nullableFieldsAreFlagged() {
        assertThat(FieldCatalog.find("card.decline_reason").orElseThrow().nullable()).isTrue();
        assertThat(FieldCatalog.find("crypto.exchange_name").orElseThrow().nullable()).isTrue();
        assertThat(FieldCatalog.find("amount").orElseThrow().nullable()).isFalse();
    }

    @Test
    void lookupIsExactAndForgivingOfWhitespaceOnly() {
        assertThat(FieldCatalog.find(" amount ")).isPresent();
        assertThat(FieldCatalog.find("Amount")).isEmpty();
        assertThat(FieldCatalog.contains("nope")).isFalse();
        assertThat(FieldCatalog.find(null)).isEmpty();
    }
}
