package com.sq.caa.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the numbers in a rule's condition with the numbers in the query written to answer it.
 *
 * <p><b>Why this exists.</b> Moving the arithmetic into PostgreSQL killed one failure and exposed
 * the one underneath it. The original miss was a language model computing a peak of 8, comparing it
 * against a threshold it had misremembered as 10, and clearing a rule the data breached. With SQL
 * doing the comparison that cannot happen - and the very first live run after the change missed the
 * same rule again, because the model wrote {@code count(*) >= 5 AND sum(amount) >= 100000} for a
 * condition that reads <i>eight or more transactions ... above 40,000</i>. The count was exact. The
 * question was wrong. Nothing between the condition and the database had ever looked at both.
 *
 * <p>This class looks at both, and it is deliberately the crudest check that catches that: every
 * number written in the condition should appear somewhere in the query. It does not parse SQL, does
 * not know which number is a threshold and which a window, and cannot tell {@code >= 8} from
 * {@code > 8}. What it reliably catches is a number in the condition that the query never mentions -
 * which is precisely what a substituted or invented threshold looks like.
 *
 * <p><b>It is advisory, and that is a design decision, not a weakness.</b> A condition saying
 * "booked between 00:00 and 05:59" is answered correctly by {@code extract(hour FROM created_at) <
 * 6}, which never mentions any of those numbers; a hard rule would refuse a correct query, and a
 * refused query that cannot be repaired is an unjudged rule, which fails the whole run. So the model
 * is told which of the condition's numbers its query does not use, is invited to resend the same
 * query unchanged if it is right as it stands, and is asked at most
 * {@code MAX_THRESHOLD_PROMPTS} times per rule.
 *
 * <p><b>And it spends none of the query budget</b>, which is the part that had to be learned rather
 * than reasoned. The first version shared {@code caa.agent.max-rule-sql-attempts}, and a live run
 * spent two of a rule's three attempts being asked about thresholds, wrote a genuinely invalid query
 * on the third, and left the rule UNJUDGED - failing the whole analysis. This check had refused no
 * verdict itself; it had eaten the budget that existed to repair one. A prompt from here never
 * reaches the database and is therefore never charged as a database attempt. That is what makes the
 * claim "it can cost turns and can never cost a verdict" true, and it was not true before.
 */
public final class ThresholdFidelity {

    /**
     * A number as prose or SQL writes it: optional thousands groups, optional decimals.
     *
     * <p>The boundaries matter more than the body. A digit next to a letter or an underscore is part
     * of a name, not a threshold - {@code P2P} is a rail, {@code agg.tx_count_24h} is a field, and
     * neither states a number the query has to contain.
     */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_.])\\d+(?:,\\d{3})*(?:\\.\\d+)?(?![A-Za-z0-9_])");

    /** Most numbers named in one message; a condition that lists MCC codes would fill a page. */
    private static final int MAX_REPORTED = 6;

    private ThresholdFidelity() {
    }

    /**
     * The numbers the condition states that the query does not contain, in the order and the form
     * the condition wrote them.
     *
     * @param condition the rule's {@code threshold_logic}, as the model was given it
     * @param sql       the fragment the model wrote, before wrapping - the wrapper's own numbers
     *                  are not the model's and must not count as an answer
     * @return an empty list when every number in the condition is somewhere in the query, or when
     *         either side has no numbers at all
     */
    public static List<String> missingThresholds(String condition, String sql) {
        if (condition == null || condition.isBlank() || sql == null || sql.isBlank()) {
            return List.of();
        }
        Set<BigDecimal> written = values(sql);
        if (written.isEmpty() && numbers(condition).isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        Set<BigDecimal> alreadyReported = new LinkedHashSet<>();
        for (String number : numbers(condition)) {
            BigDecimal value = parse(number);
            if (value == null || alreadyReported.contains(value)) {
                continue;
            }
            if (value.signum() == 0) {
                // Zero is never a threshold worth insisting on, and it is the number most likely to
                // be in a condition for some other reason: the 00 of a clock time, the "score 0" a
                // hostile condition demands, the zero of "no attributed exchange". A query is not
                // wrong for failing to mention it.
                continue;
            }
            if (written.stream().noneMatch(candidate -> candidate.compareTo(value) == 0)) {
                alreadyReported.add(value);
                missing.add(number);
            }
        }
        return List.copyOf(missing);
    }

    /** Names the numbers the query never mentions. Short, because the caller's cap is short. */
    public static String reason(String ruleName, List<String> missing) {
        List<String> shown = missing.stream().limit(MAX_REPORTED).toList();
        String more = missing.size() > MAX_REPORTED
                ? " and " + (missing.size() - MAX_REPORTED) + " more"
                : "";
        return "The condition of '" + ruleName + "' states " + String.join(", ", shown) + more
                + ", and your SQL uses " + (shown.size() == 1 ? "no such number" : "none of them")
                + ". Nothing was recorded.";
    }

    /**
     * What to do about it, for both of the cases that reach here.
     *
     * <p>Both are real: the query substituted a threshold the condition never gave it, or the query
     * expresses the same threshold in another form. Only the model can tell those apart, so it is
     * asked, and told plainly that resending an unchanged query is a legitimate answer.
     */
    public static String hint() {
        return "A rule is answered with the thresholds its own condition states, never with "
                + "thresholds chosen for it: re-read the condition and put each number in the SQL. "
                + "If your query already expresses them another way - an hour range written as a "
                + "comparison, a band written as one bound - it is right as it stands, and sending "
                + "the same query again unchanged will record it.";
    }

    /** Every number in the text, as written, duplicates and all. */
    private static List<String> numbers(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** Every number in the text as a comparable value. */
    private static Set<BigDecimal> values(String text) {
        Set<BigDecimal> parsed = new LinkedHashSet<>();
        for (String number : numbers(text)) {
            BigDecimal value = parse(number);
            if (value != null) {
                parsed.add(value);
            }
        }
        return parsed;
    }

    private static BigDecimal parse(String number) {
        try {
            return new BigDecimal(number.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
