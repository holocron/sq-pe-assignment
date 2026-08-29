package com.sq.caa.rules;

import com.sq.caa.domain.RuleScope;
import java.util.List;

/**
 * One entry of the field catalog: a single piece of data the agent can actually see about a
 * transaction, described well enough for a rule author to write a condition about it.
 *
 * <p>Rule conditions are prose now, so nothing here constrains what may be written - this is
 * reference material, not a grammar. It matters all the same: a condition that talks about data the
 * agent cannot fetch is a condition the agent can only guess at, and a guess is exactly what an
 * audit trail must not contain.
 *
 * @param field       name the value is presented under, e.g. {@code payment.receiver_bank_country}
 * @param label       human label for the reference panel
 * @param type        what kind of value it holds
 * @param category    how the reference panel groups it
 * @param appliesTo   activity scope the field exists on ({@code ALL} for base, derived and
 *                    aggregate values); on any other activity type it is simply absent
 * @param options     known values for enumerated fields, empty otherwise
 * @param nullable    {@code true} when the underlying column is nullable, so a condition should say
 *                    what an absent value means
 * @param example     a short sample value, shown next to the field in the reference panel
 * @param description short operator-readable explanation, including the unit or window where one
 *                    applies
 */
public record FieldDefinition(
        String field,
        String label,
        FieldType type,
        FieldCategory category,
        RuleScope appliesTo,
        List<String> options,
        boolean nullable,
        String example,
        String description) {

    public FieldDefinition {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
