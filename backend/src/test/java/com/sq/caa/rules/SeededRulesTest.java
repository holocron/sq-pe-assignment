package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The twelve seeded rules, read straight out of the migration that creates them.
 *
 * <p>These conditions are the showcase of the whole design: they are prompts, and a prompt that is
 * vague is a rule that gets a different verdict every run. Nothing here can check that the model
 * agrees with them - only a run against the live model can - but everything that <em>is</em>
 * checkable is checked, because the failure mode of a bad seeded rule is a demo that looks like it
 * works while quietly judging nothing.
 *
 * <p>So: each condition must survive the write validation the API would apply to it, must be prose
 * rather than a leftover DSL document, must name at least one field the agent can actually fetch,
 * must state at least one concrete number, and must say why the pattern is suspicious. The names,
 * scopes and weights are pinned as well, because the seeded set is what the planted customer
 * patterns were built against.
 */
class SeededRulesTest {

    private static final Pattern SEEDED_RULE = Pattern.compile(
            "INSERT INTO risk_rules \\(rule_id, rule_name, applies_to, threshold_logic, weight\\) "
                    + "VALUES\\n  \\('([0-9a-f-]+)', '([^']+)', '(\\w+)',\\n   "
                    + "'((?:[^']|'')*)',\\n   (\\d+\\.\\d\\d)\\);");

    private static List<SeededRule> seeded;

    @BeforeAll
    static void readMigration() throws IOException {
        String sql = migration();
        Matcher matcher = SEEDED_RULE.matcher(sql.substring(sql.indexOf("INSERT INTO risk_rules")));
        List<SeededRule> rules = new ArrayList<>();
        while (matcher.find()) {
            rules.add(new SeededRule(matcher.group(2), matcher.group(3),
                    matcher.group(4).replace("''", "'"), matcher.group(5)));
        }
        seeded = List.copyOf(rules);
    }

    @Test
    @DisplayName("the seeded set is the twelve rules the planted patterns were built against")
    void seedsTwelveRules() {
        assertThat(seeded).extracting(SeededRule::name).containsExactlyInAnyOrder(
                "Large payment at or above the 10,000 reporting threshold",
                "Structuring - repeated payments just below the reporting threshold",
                "Payment to a sanctioned or high-risk jurisdiction",
                "High-value cross-border SWIFT wire",
                "Cross-border payment fan-out across many jurisdictions",
                "Card-not-present success immediately after a decline burst",
                "Declined card authorisation velocity",
                "High-risk merchant category spend",
                "Privacy-chain or mixer transfer with no attributed exchange",
                "Concentrated high-value crypto exposure",
                "High-value activity outside normal business hours",
                "Transaction velocity and value spike within 24 hours");
    }

    @Test
    @DisplayName("every activity type has rules, so no customer is judged against an empty set")
    void coversEveryActivityType() {
        assertThat(seeded).filteredOn(rule -> rule.scope().equals("PAYMENT")).hasSize(5);
        assertThat(seeded).filteredOn(rule -> rule.scope().equals("CARD")).hasSize(3);
        assertThat(seeded).filteredOn(rule -> rule.scope().equals("CRYPTO")).hasSize(2);
        assertThat(seeded).filteredOn(rule -> rule.scope().equals("ALL")).hasSize(2);
    }

    @Test
    @DisplayName("the heaviest rule is the one that removes every means of tracing the money")
    void weightsRankTheFindings() {
        for (SeededRule rule : seeded) {
            assertThat(new java.math.BigDecimal(rule.weight()))
                    .as("weight of '%s'", rule.name())
                    .isBetween(RuleValidator.MIN_WEIGHT, RuleValidator.MAX_WEIGHT);
        }
        assertThat(heaviest().name())
                .isEqualTo("Privacy-chain or mixer transfer with no attributed exchange");
        assertThat(heaviest().weight()).isEqualTo("40.00");
    }

    @Test
    @DisplayName("every seeded condition would be accepted by the write API")
    void everyConditionPassesWriteValidation() {
        for (SeededRule rule : seeded) {
            assertThatCode(() -> RuleValidator.normaliseCondition(rule.condition()))
                    .as("condition of '%s'", rule.name())
                    .doesNotThrowAnyException();
            assertThatCode(() -> RuleValidator.normaliseName(rule.name()))
                    .as("name of '%s'", rule.name())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("no leftover JSON: threshold_logic is prose now")
    void noConditionIsADslDocument() {
        for (SeededRule rule : seeded) {
            assertThat(rule.condition()).as("condition of '%s'", rule.name())
                    .doesNotContain("\"operator\"")
                    .doesNotContain("\"conditions\"")
                    .doesNotStartWith("{");
        }
    }

    @Test
    @DisplayName("every condition names data the agent can actually fetch")
    void everyConditionReferencesTheFieldCatalog() {
        for (SeededRule rule : seeded) {
            assertThat(FieldCatalog.fieldNames())
                    .as("condition of '%s' names no catalog field: %s", rule.name(), rule.condition())
                    .anyMatch(field -> rule.condition().contains(field));
        }
    }

    @Test
    @DisplayName("every condition states a concrete threshold, so two runs read it the same way")
    void everyConditionStatesANumber() {
        for (SeededRule rule : seeded) {
            assertThat(rule.condition()).as("condition of '%s'", rule.name())
                    .matches(text -> text.chars().anyMatch(Character::isDigit));
        }
    }

    @Test
    @DisplayName("every condition explains why the pattern is suspicious, not only what it is")
    void everyConditionExplainsItself() {
        for (SeededRule rule : seeded) {
            assertThat(rule.condition()).as("condition of '%s'", rule.name())
                    .contains("Why it matters:");
        }
    }

    @Test
    @DisplayName("the planted patterns are each addressed by a rule that names their signature")
    void addressesThePlantedPatterns() {
        assertThat(conditionOf("Structuring - repeated payments just below the reporting threshold"))
                .contains("8,000 and 9,999.99")
                .contains("agg.tx_count_24h");
        assertThat(conditionOf("Payment to a sanctioned or high-risk jurisdiction"))
                .contains("payment.receiver_bank_country")
                .contains("RU")
                .contains("SY")
                .contains("IR");
        assertThat(conditionOf("Privacy-chain or mixer transfer with no attributed exchange"))
                .contains("XMR")
                .contains("crypto.exchange_name")
                .contains("0x8589427373D6D84E98730D7795D8f6f8731FDA16");
        assertThat(conditionOf("Card-not-present success immediately after a decline burst"))
                .contains("card.card_present")
                .contains("agg.failed_count_24h");
        assertThat(conditionOf("Concentrated high-value crypto exposure"))
                .contains("agg.crypto_ratio_30d");
    }

    private static SeededRule heaviest() {
        return seeded.stream()
                .max((left, right) -> new java.math.BigDecimal(left.weight())
                        .compareTo(new java.math.BigDecimal(right.weight())))
                .orElseThrow();
    }

    private static String conditionOf(String name) {
        return seeded.stream().filter(rule -> rule.name().equals(name)).findFirst().orElseThrow()
                .condition();
    }

    private static String migration() throws IOException {
        try (InputStream stream = SeededRulesTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V3__seed.sql")) {
            assertThat(stream).as("V3__seed.sql on the classpath").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SeededRule(String name, String scope, String condition, String weight) {
    }
}
