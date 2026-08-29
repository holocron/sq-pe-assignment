package com.sq.caa.rules;

import java.util.List;

/**
 * A node of a parsed {@code threshold_logic} document.
 *
 * <p>The DSL has exactly two node kinds, so the hierarchy is sealed: a {@link RuleGroup} combines
 * children with AND/OR/NOT, a {@link RuleCondition} is a leaf comparison. Everything downstream
 * (evaluator, validator, serialiser) switches exhaustively over these two.
 */
public sealed interface RuleNode permits RuleGroup, RuleCondition {

    /** Number of nodes in this subtree, this node included. Used to bound rule complexity. */
    int nodeCount();

    /** Nesting depth of this subtree, a leaf being 1. */
    int depth();

    /** Every catalog field referenced in this subtree, in traversal order and de-duplicated. */
    List<String> referencedFields();
}
