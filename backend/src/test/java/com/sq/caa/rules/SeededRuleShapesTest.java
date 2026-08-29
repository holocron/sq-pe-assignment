package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.batch;
import static com.sq.caa.rules.RuleTestFixtures.card;
import static com.sq.caa.rules.RuleTestFixtures.crypto;
import static com.sq.caa.rules.RuleTestFixtures.customer;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static com.sq.caa.rules.RuleTestFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every rule shape the seed data ships with, evaluated against planted activity and against a clean
 * control.
 *
 * <p>This is the regression net for the demo itself: if one of these stops firing, the analysis
 * screen goes quiet and the application looks like it works when it does not. Each shape is also
 * pushed through the strict validator, so the seeded {@code threshold_logic} can never drift away
 * from what the write API would accept.
 */
class SeededRuleShapesTest {

    private static final Instant T = Instant.parse("2026-08-20T12:00:00Z");

    private final RuleEvaluator evaluator = new RuleEvaluator();

    // ------------------------------------------------------------------
    // The catalogue of seeded shapes
    // ------------------------------------------------------------------

    private static final String SANCTIONED_WIRE = """
            {"op":"AND","conditions":[
              {"field":"amount","operator":"GT","value":10000},
              {"field":"payment.receiver_bank_country","operator":"IN",
               "value":["IR","KP","SY","RU","AF","CU","VE","BY","MM"]}]}""";

    private static final String STRUCTURING = """
            {"op":"AND","conditions":[
              {"field":"amount","operator":"BETWEEN","value":[8000,9999.99]},
              {"field":"agg.tx_count_24h","operator":"GTE","value":3}]}""";

    private static final String HIGH_VALUE_SWIFT = """
            {"op":"AND","conditions":[
              {"field":"payment.payment_method","operator":"EQ","value":"SWIFT"},
              {"field":"amount","operator":"GTE","value":50000}]}""";

    private static final String PRIVACY_CHAIN = """
            {"op":"AND","conditions":[
              {"field":"crypto.blockchain","operator":"IN","value":["XMR","ZEC","DASH"]},
              {"field":"crypto.exchange_name","operator":"IS_NULL"}]}""";

    private static final String CRYPTO_CONCENTRATION = """
            {"op":"AND","conditions":[
              {"field":"agg.crypto_ratio_30d","operator":"GTE","value":0.6},
              {"field":"agg.max_amount_30d","operator":"GT","value":5000}]}""";

    private static final String CNP_AFTER_DECLINES = """
            {"op":"AND","conditions":[
              {"field":"card.card_present","operator":"EQ","value":false},
              {"field":"status","operator":"EQ","value":"Completed"},
              {"field":"amount","operator":"GT","value":2000},
              {"field":"agg.failed_count_24h","operator":"GTE","value":3}]}""";

    private static final String HIGH_RISK_MCC = """
            {"op":"AND","conditions":[
              {"field":"card.mcc_code","operator":"IN","value":["7995","7801","7802","6051"]},
              {"field":"amount","operator":"GT","value":500}]}""";

    private static final String HIGH_RISK_MERCHANT = """
            {"field":"card.merchant_name","operator":"MATCHES","value":"(casino|betting|forex|mixer)"}""";

    private static final String VELOCITY_BURST = """
            {"op":"OR","conditions":[
              {"field":"agg.tx_count_24h","operator":"GTE","value":8},
              {"field":"agg.amount_sum_24h","operator":"GT","value":50000}]}""";

    private static final String OFF_HOURS_FOREIGN = """
            {"op":"AND","conditions":[
              {"op":"OR","conditions":[
                {"field":"hour_of_day","operator":"LT","value":5},
                {"field":"hour_of_day","operator":"GTE","value":23}]},
              {"op":"NOT","conditions":[
                {"field":"customer.country","operator":"EQ","value":"US"}]}]}""";

    private static final String COUNTRY_FAN_OUT = """
            {"op":"AND","conditions":[
              {"field":"agg.distinct_countries_30d","operator":"GTE","value":4},
              {"field":"payment.receiver_bank_country","operator":"NEQ","value":"US"}]}""";

    private static final String DECLINE_STORM = """
            {"op":"AND","conditions":[
              {"field":"card.decline_reason","operator":"NOT_NULL"},
              {"field":"agg.failed_count_24h","operator":"GTE","value":4}]}""";

    private static List<RiskRule> seededRules() {
        List<RiskRule> rules = new ArrayList<>();
        rules.add(rule("Large payment to sanctioned jurisdiction", RuleScope.PAYMENT, SANCTIONED_WIRE, "30.00"));
        rules.add(rule("Structuring below the reporting threshold", RuleScope.PAYMENT, STRUCTURING, "25.00"));
        rules.add(rule("High value SWIFT wire", RuleScope.PAYMENT, HIGH_VALUE_SWIFT, "20.00"));
        rules.add(rule("Privacy chain transfer without exchange", RuleScope.CRYPTO, PRIVACY_CHAIN, "35.00"));
        rules.add(rule("Concentrated crypto exposure", RuleScope.CRYPTO, CRYPTO_CONCENTRATION, "15.00"));
        rules.add(rule("Card not present success after declines", RuleScope.CARD, CNP_AFTER_DECLINES, "30.00"));
        rules.add(rule("High risk merchant category", RuleScope.CARD, HIGH_RISK_MCC, "15.00"));
        rules.add(rule("High risk merchant name", RuleScope.CARD, HIGH_RISK_MERCHANT, "12.00"));
        rules.add(rule("Transaction velocity burst", RuleScope.ALL, VELOCITY_BURST, "20.00"));
        rules.add(rule("Off hours activity by a foreign customer", RuleScope.ALL, OFF_HOURS_FOREIGN, "10.00"));
        rules.add(rule("Cross border payment fan out", RuleScope.PAYMENT, COUNTRY_FAN_OUT, "20.00"));
        rules.add(rule("Declined authorisation storm", RuleScope.CARD, DECLINE_STORM, "10.00"));
        return rules;
    }

    private boolean triggers(String logic, RuleScope scope, EvaluationBatch fixture) {
        return evaluator.evaluate(rule("Rule under test", scope, logic, "10.00"), fixture).triggered();
    }

    private RuleEvaluationResult evaluate(String logic, RuleScope scope, EvaluationBatch fixture) {
        return evaluator.evaluate(rule("Rule under test", scope, logic, "10.00"), fixture);
    }

    // ------------------------------------------------------------------
    // Contract of the whole catalogue
    // ------------------------------------------------------------------

    @Test
    void everySeededShapeSurvivesStrictValidation() {
        for (RiskRule seeded : seededRules()) {
            assertThatCode(() -> RuleParser.parseStrict(seeded.getThresholdLogic()))
                    .as(seeded.getRuleName())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void noSeededShapeDegradesOnRepresentativeActivity() {
        EvaluationBatch fixture = batch(
                payment("9000.00", "Completed", T, "Wire", "DE"),
                card("300.00", "Completed", T.minus(Duration.ofHours(5)), "GROCER", "5411", "Debit", true, null),
                crypto("400.00", "Completed", T.minus(Duration.ofHours(9)), "BTC", "Kraken", "wallet-x"));

        for (RiskRule seeded : seededRules()) {
            RuleEvaluationResult result = evaluator.evaluate(seeded, fixture);
            assertThat(result.degraded()).as("%s: %s", seeded.getRuleName(), result.degradationNotes())
                    .isFalse();
        }
    }

    @Test
    void aCleanCustomerTriggersNothing() {
        EvaluationBatch clean = batch(
                card("42.00", "Completed", T.minus(Duration.ofDays(2)), "GROCER", "5411", "Debit", true, null),
                payment("250.00", "Completed", T.minus(Duration.ofDays(4)), "ACH", "US"),
                card("18.00", "Completed", T.minus(Duration.ofDays(9)), "COFFEE HOUSE", "5814", "Debit", true, null));

        for (RiskRule seeded : seededRules()) {
            RuleEvaluationResult result = evaluator.evaluate(seeded, clean);
            assertThat(result.triggered()).as(seeded.getRuleName()).isFalse();
            assertThat(result.score()).isEqualByComparingTo("0.00");
        }
    }

    // ------------------------------------------------------------------
    // One planted pattern per shape
    // ------------------------------------------------------------------

    @Test
    void sanctionedJurisdictionWire() {
        EvaluationBatch guilty = batch(payment("15000.00", "Completed", T, "SWIFT", "IR"));
        EvaluationBatch innocent = batch(payment("15000.00", "Completed", T, "SWIFT", "DE"));

        assertThat(triggers(SANCTIONED_WIRE, RuleScope.PAYMENT, guilty)).isTrue();
        assertThat(triggers(SANCTIONED_WIRE, RuleScope.PAYMENT, innocent)).isFalse();
    }

    @Test
    void structuringNeedsBothTheAmountBandAndTheVelocity() {
        EvaluationBatch guilty = batch(
                payment("9500.00", "Completed", T, "Wire", "US"),
                payment("9400.00", "Completed", T.minus(Duration.ofHours(4)), "Wire", "US"),
                payment("9200.00", "Completed", T.minus(Duration.ofHours(8)), "Wire", "US"));
        EvaluationBatch spreadOut = batch(
                payment("9500.00", "Completed", T, "Wire", "US"),
                payment("9400.00", "Completed", T.minus(Duration.ofDays(3)), "Wire", "US"),
                payment("9200.00", "Completed", T.minus(Duration.ofDays(6)), "Wire", "US"));

        RuleEvaluationResult result = evaluate(STRUCTURING, RuleScope.PAYMENT, guilty);
        assertThat(result.triggered()).isTrue();
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.sampleMatches().get(0).explanation()).contains("agg.tx_count_24h=3 GTE 3 [true]");

        assertThat(triggers(STRUCTURING, RuleScope.PAYMENT, spreadOut)).isFalse();
    }

    @Test
    void highValueSwiftWire() {
        assertThat(triggers(HIGH_VALUE_SWIFT, RuleScope.PAYMENT,
                batch(payment("50000.00", "Completed", T, "SWIFT", "AE")))).isTrue();
        assertThat(triggers(HIGH_VALUE_SWIFT, RuleScope.PAYMENT,
                batch(payment("50000.00", "Completed", T, "ACH", "US")))).isFalse();
    }

    @Test
    void privacyChainWithoutAnExchange() {
        assertThat(triggers(PRIVACY_CHAIN, RuleScope.CRYPTO,
                batch(crypto("2000.00", "Completed", T, "XMR", null, "wallet-x")))).isTrue();
        assertThat(triggers(PRIVACY_CHAIN, RuleScope.CRYPTO,
                batch(crypto("2000.00", "Completed", T, "XMR", "Kraken", "wallet-x")))).isFalse();
        assertThat(triggers(PRIVACY_CHAIN, RuleScope.CRYPTO,
                batch(crypto("2000.00", "Completed", T, "BTC", null, "wallet-x")))).isFalse();
    }

    @Test
    void concentratedCryptoExposureUsesAggregates() {
        EvaluationBatch cryptoHeavy = batch(
                crypto("9000.00", "Completed", T, "ETH", "Kraken", "wallet-a"),
                crypto("4000.00", "Completed", T.minus(Duration.ofDays(2)), "BTC", "Kraken", "wallet-b"),
                payment("100.00", "Completed", T.minus(Duration.ofDays(3)), "ACH", "US"));
        EvaluationBatch balanced = batch(
                crypto("9000.00", "Completed", T, "ETH", "Kraken", "wallet-a"),
                payment("100.00", "Completed", T.minus(Duration.ofDays(2)), "ACH", "US"),
                payment("120.00", "Completed", T.minus(Duration.ofDays(3)), "ACH", "US"),
                payment("140.00", "Completed", T.minus(Duration.ofDays(4)), "ACH", "US"));

        assertThat(triggers(CRYPTO_CONCENTRATION, RuleScope.CRYPTO, cryptoHeavy)).isTrue();
        assertThat(triggers(CRYPTO_CONCENTRATION, RuleScope.CRYPTO, balanced)).isFalse();
    }

    @Test
    void cardNotPresentSuccessAfterABurstOfDeclines() {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            transactions.add(card("120.00", "Failed", T.minus(Duration.ofHours(i)), "ONLINE SHOP", "5732",
                    "Credit", false, "Do not honour"));
        }
        transactions.add(card("4200.00", "Completed", T, "ONLINE SHOP", "5732", "Credit", false, null));
        EvaluationBatch guilty = batch(customer(), transactions.toArray(new Transaction[0]));

        RuleEvaluationResult result = evaluate(CNP_AFTER_DECLINES, RuleScope.CARD, guilty);
        assertThat(result.triggered()).isTrue();
        assertThat(result.matchedCount()).isEqualTo(1);

        EvaluationBatch withoutDeclines = batch(
                card("4200.00", "Completed", T, "ONLINE SHOP", "5732", "Credit", false, null));
        assertThat(triggers(CNP_AFTER_DECLINES, RuleScope.CARD, withoutDeclines)).isFalse();
    }

    @Test
    void highRiskMerchantCategory() {
        assertThat(triggers(HIGH_RISK_MCC, RuleScope.CARD,
                batch(card("900.00", "Completed", T, "LUCKY SLOTS", "7995", "Credit", true, null)))).isTrue();
        assertThat(triggers(HIGH_RISK_MCC, RuleScope.CARD,
                batch(card("100.00", "Completed", T, "LUCKY SLOTS", "7995", "Credit", true, null)))).isFalse();
        assertThat(triggers(HIGH_RISK_MCC, RuleScope.CARD,
                batch(card("900.00", "Completed", T, "GROCER", "5411", "Credit", true, null)))).isFalse();
    }

    @Test
    void highRiskMerchantNameMatchesByRegex() {
        assertThat(triggers(HIGH_RISK_MERCHANT, RuleScope.CARD,
                batch(card("50.00", "Completed", T, "Sunset Casino Resort", "7011", "Credit", true, null))))
                .isTrue();
        assertThat(triggers(HIGH_RISK_MERCHANT, RuleScope.CARD,
                batch(card("50.00", "Completed", T, "Sunset Family Resort", "7011", "Credit", true, null))))
                .isFalse();
    }

    @Test
    void velocityBurstFiresOnEitherCountOrValue() {
        List<Transaction> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(payment("50.00", "Completed", T.minus(Duration.ofHours(i)), "P2P", "US"));
        }
        assertThat(triggers(VELOCITY_BURST, RuleScope.ALL,
                batch(customer(), many.toArray(new Transaction[0])))).isTrue();

        assertThat(triggers(VELOCITY_BURST, RuleScope.ALL, batch(
                payment("30000.00", "Completed", T, "Wire", "US"),
                payment("25000.00", "Completed", T.minus(Duration.ofHours(2)), "Wire", "US")))).isTrue();

        assertThat(triggers(VELOCITY_BURST, RuleScope.ALL,
                batch(payment("100.00", "Completed", T, "ACH", "US")))).isFalse();
    }

    @Test
    void offHoursActivityByAForeignCustomerCombinesOrAndNot() {
        Instant night = Instant.parse("2026-08-20T02:30:00Z");
        EvaluationBatch foreignAtNight = batch(customer("RU", 35),
                payment("300.00", "Completed", night, "P2P", "RU"));
        EvaluationBatch domesticAtNight = batch(customer("US", 35),
                payment("300.00", "Completed", night, "P2P", "US"));
        EvaluationBatch foreignByDay = batch(customer("RU", 35),
                payment("300.00", "Completed", T, "P2P", "RU"));

        assertThat(triggers(OFF_HOURS_FOREIGN, RuleScope.ALL, foreignAtNight)).isTrue();
        assertThat(triggers(OFF_HOURS_FOREIGN, RuleScope.ALL, domesticAtNight)).isFalse();
        assertThat(triggers(OFF_HOURS_FOREIGN, RuleScope.ALL, foreignByDay)).isFalse();
    }

    @Test
    void crossBorderFanOutCountsDistinctBeneficiaryCountries() {
        EvaluationBatch fanOut = batch(
                payment("500.00", "Completed", T, "SWIFT", "TR"),
                payment("500.00", "Completed", T.minus(Duration.ofDays(1)), "SWIFT", "AE"),
                payment("500.00", "Completed", T.minus(Duration.ofDays(2)), "SWIFT", "CY"),
                payment("500.00", "Completed", T.minus(Duration.ofDays(3)), "SWIFT", "MT"));
        EvaluationBatch domestic = batch(
                payment("500.00", "Completed", T, "ACH", "US"),
                payment("500.00", "Completed", T.minus(Duration.ofDays(1)), "ACH", "US"));

        assertThat(triggers(COUNTRY_FAN_OUT, RuleScope.PAYMENT, fanOut)).isTrue();
        assertThat(triggers(COUNTRY_FAN_OUT, RuleScope.PAYMENT, domestic)).isFalse();
    }

    @Test
    void declineStormNeedsBothAReasonAndTheFailureCount() {
        List<Transaction> declines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            declines.add(card("75.00", "Failed", T.minus(Duration.ofHours(i)), "ONLINE SHOP", "5732",
                    "Credit", false, "Do not honour"));
        }
        EvaluationBatch storm = batch(customer(), declines.toArray(new Transaction[0]));
        assertThat(triggers(DECLINE_STORM, RuleScope.CARD, storm)).isTrue();

        EvaluationBatch singleDecline = batch(
                card("75.00", "Failed", T, "ONLINE SHOP", "5732", "Credit", false, "Do not honour"));
        assertThat(triggers(DECLINE_STORM, RuleScope.CARD, singleDecline)).isFalse();
    }
}
