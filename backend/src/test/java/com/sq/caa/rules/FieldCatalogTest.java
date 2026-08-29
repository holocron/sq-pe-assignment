package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.RuleScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The catalog is the promise the rule editor makes to a rule author: "these are the values the agent
 * can see, so a condition about them is one it can settle from evidence".
 *
 * <p>Two things therefore have to hold, and both are pinned here. The list of names is stable API -
 * the editor's reference panel and the evidence the model is shown are generated from it. And every
 * entry has to be usable as reference material: a label, a type, a category, a description and an
 * example, because an entry with a blank description tells an author nothing.
 */
class FieldCatalogTest {

    @Test
    void containsExactlyTheFieldsTheAgentCanSee() {
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
    void everyEntryIsUsableAsReferenceMaterial() {
        for (FieldDefinition definition : FieldCatalog.entries()) {
            assertThat(definition.label()).as("label of %s", definition.field()).isNotBlank();
            assertThat(definition.description()).as("description of %s", definition.field())
                    .isNotBlank();
            assertThat(definition.example()).as("example of %s", definition.field()).isNotBlank();
            assertThat(definition.type()).as("type of %s", definition.field()).isNotNull();
            assertThat(definition.category()).as("category of %s", definition.field()).isNotNull();
            assertThat(definition.appliesTo()).as("scope of %s", definition.field()).isNotNull();
        }
    }

    @Test
    void categoriesGroupTheFieldsTheWayTheEditorRendersThem() {
        assertThat(categoryOf("amount")).isEqualTo(FieldCategory.TRANSACTION);
        assertThat(categoryOf("customer.country")).isEqualTo(FieldCategory.CUSTOMER);
        assertThat(categoryOf("card.mcc_code")).isEqualTo(FieldCategory.CARD);
        assertThat(categoryOf("payment.payment_method")).isEqualTo(FieldCategory.PAYMENT);
        assertThat(categoryOf("crypto.blockchain")).isEqualTo(FieldCategory.CRYPTO);
        assertThat(categoryOf("agg.tx_count_24h")).isEqualTo(FieldCategory.AGGREGATE);
    }

    @Test
    void categoryNamesAreServedInTheLowerCaseTheEditorGroupsBy() {
        assertThat(FieldCategory.AGGREGATE.wireName()).isEqualTo("aggregate");
        assertThat(FieldCategory.values()).extracting(FieldCategory::wireName)
                .containsExactly("transaction", "customer", "card", "payment", "crypto", "aggregate");
    }

    @Test
    void enumeratedFieldsListTheirKnownValues() {
        assertThat(options("status")).containsExactly("Completed", "Pending", "Failed", "Reversed");
        assertThat(options("activity_type")).containsExactly("CARD", "PAYMENT", "CRYPTO");
        assertThat(options("card.card_type")).containsExactly("Debit", "Credit", "Prepaid");
        assertThat(options("payment.payment_method")).containsExactly("ACH", "Wire", "SWIFT", "P2P");
        assertThat(options("card.merchant_name")).isEmpty();
    }

    @Test
    void activitySpecificFieldsDeclareTheActivityTheyExistOn() {
        assertThat(FieldCatalog.find("card.mcc_code").orElseThrow().appliesTo())
                .isEqualTo(RuleScope.CARD);
        assertThat(FieldCatalog.find("payment.payment_method").orElseThrow().appliesTo())
                .isEqualTo(RuleScope.PAYMENT);
        assertThat(FieldCatalog.find("crypto.blockchain").orElseThrow().appliesTo())
                .isEqualTo(RuleScope.CRYPTO);
        assertThat(FieldCatalog.find("agg.tx_count_24h").orElseThrow().appliesTo())
                .isEqualTo(RuleScope.ALL);
    }

    @Test
    void nullableFieldsAreFlaggedSoARuleCanSayWhatAnAbsentValueMeans() {
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

    private static FieldCategory categoryOf(String field) {
        return FieldCatalog.find(field).orElseThrow().category();
    }

    private static List<String> options(String field) {
        return FieldCatalog.find(field).orElseThrow().options();
    }
}
