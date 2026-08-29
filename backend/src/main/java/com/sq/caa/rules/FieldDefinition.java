package com.sq.caa.rules;

import com.sq.caa.domain.RuleScope;
import java.util.List;

/**
 * One entry of the field catalog: everything a rule author (human or model) needs in order to write
 * a valid leaf condition against this field.
 *
 * @param field           DSL field name, e.g. {@code payment.receiver_bank_country}
 * @param label           human label for the editor
 * @param type            value type, which fixes the allowed operators
 * @param appliesTo       activity scope the field exists on ({@code ALL} for base/derived/aggregate
 *                        fields); on any other activity type the leaf resolves to false
 * @param options         enum options where applicable, empty otherwise
 * @param optionsClosed   {@code true} when {@link #options()} is exhaustive, so a value outside it
 *                        is rejected on write instead of silently never matching
 * @param nullable        {@code true} when the underlying column is nullable
 * @param description     short operator-readable explanation
 */
public record FieldDefinition(
        String field,
        String label,
        FieldType type,
        RuleScope appliesTo,
        List<String> options,
        boolean optionsClosed,
        boolean nullable,
        String description) {

    public FieldDefinition {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** Operators allowed on this field. */
    public List<RuleOperator> allowedOperators() {
        return type.allowedOperators();
    }

    /** {@code true} when the field exists for the given activity scope. */
    public boolean availableIn(RuleScope scope) {
        return appliesTo == RuleScope.ALL || scope == RuleScope.ALL || appliesTo == scope;
    }
}
