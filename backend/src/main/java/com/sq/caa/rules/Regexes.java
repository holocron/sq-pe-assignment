package com.sq.caa.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The single place where the {@code MATCHES} operator turns admin-supplied text into a running
 * regular expression, and therefore the single place where that risk is bounded.
 *
 * <p>Three guards, all of which apply to stored rules and to unsaved {@code POST /api/rules/test}
 * drafts alike:
 * <ol>
 *   <li><b>Length.</b> A pattern longer than {@link #MAX_PATTERN_LENGTH} characters is refused. The
 *       catalog's text fields are merchant names, account identifiers, wallet addresses and country
 *       codes; no legitimate rule needs more, and the cap removes the "one request pins an
 *       arbitrarily large string" amplification.
 *   <li><b>Backtracking.</b> Matching runs against a {@link BudgetedSequence} that counts character
 *       reads and aborts after {@link #MAX_MATCH_STEPS}. This bounds catastrophic backtracking
 *       (ReDoS) for <em>any</em> pattern, which a syntactic blacklist of nested quantifiers cannot,
 *       and costs one decrement per character read on the happy path.
 *   <li><b>Memory.</b> Compiled patterns are cached in a bounded LRU of {@link #MAX_CACHED_PATTERNS}
 *       entries rather than an unbounded map, so a loop of distinct ad-hoc regexes evicts instead of
 *       accumulating for the life of the JVM.
 * </ol>
 *
 * <p>Nothing here ever throws: a refused pattern comes back as an outcome the evaluator reports as a
 * degraded false, which is the engine's contract.
 */
final class Regexes {

    /** Longest {@code MATCHES} pattern accepted, on write and at evaluation time. */
    static final int MAX_PATTERN_LENGTH = 200;

    /** Compiled patterns kept in the LRU cache. */
    static final int MAX_CACHED_PATTERNS = 256;

    /**
     * Character reads one match may perform before it is abandoned as pathological.
     *
     * <p>Calibrated against the real engine: a nested-quantifier pattern such as
     * {@code ([a-z]+)*$} over a 40 character merchant name costs about 3 400 reads, while the
     * exponential case {@code (.*a){20}$} over the same input passes fifty million. Anything between
     * those two is a pattern no rule author needs.
     */
    static final int MAX_MATCH_STEPS = 100_000;

    private static final Map<String, Optional<Pattern>> CACHE =
            Collections.synchronizedMap(new LruCache(MAX_CACHED_PATTERNS));

    private Regexes() {
    }

    /** How a {@code MATCHES} evaluation ended. */
    enum Outcome {
        /** The pattern matched somewhere in the value. */
        MATCH,
        /** The pattern is usable and did not match. */
        NO_MATCH,
        /** The operand is null, empty or whitespace only; an empty pattern matches everything. */
        BLANK,
        /** The pattern is longer than {@link #MAX_PATTERN_LENGTH}. */
        TOO_LONG,
        /** The pattern does not compile. */
        INVALID,
        /** Matching exceeded {@link #MAX_MATCH_STEPS} and was abandoned. */
        BUDGET_EXCEEDED
    }

    /** Why a pattern is unusable, or empty when it is fine. Shared by write-time validation. */
    static Optional<Outcome> reject(String regex) {
        if (regex == null || regex.isBlank()) {
            return Optional.of(Outcome.BLANK);
        }
        if (regex.length() > MAX_PATTERN_LENGTH) {
            return Optional.of(Outcome.TOO_LONG);
        }
        return compile(regex).isPresent() ? Optional.empty() : Optional.of(Outcome.INVALID);
    }

    /** Runs {@code regex} over {@code input} under every guard above. Never throws. */
    static Outcome search(String regex, String input) {
        Optional<Outcome> rejected = reject(regex);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        Pattern pattern = compile(regex).orElse(null);
        if (pattern == null) {
            return Outcome.INVALID;
        }
        try {
            return pattern.matcher(new BudgetedSequence(input, MAX_MATCH_STEPS)).find()
                    ? Outcome.MATCH
                    : Outcome.NO_MATCH;
        } catch (BudgetExceededException e) {
            return Outcome.BUDGET_EXCEEDED;
        }
    }

    /** Compiles case-insensitively through the bounded cache; empty when the syntax is bad. */
    static Optional<Pattern> compile(String regex) {
        if (regex == null || regex.length() > MAX_PATTERN_LENGTH) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(regex, key -> {
            try {
                return Optional.of(Pattern.compile(key, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                return Optional.empty();
            }
        });
    }

    /** Cache size, for the test that pins the bound. */
    static int cachedPatternCount() {
        return CACHE.size();
    }

    /** Access-ordered LRU; the eldest entry is dropped once the cap is reached. */
    private static final class LruCache extends LinkedHashMap<String, Optional<Pattern>> {

        private static final long serialVersionUID = 1L;

        private final int capacity;

        private LruCache(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Pattern>> eldest) {
            return size() > capacity;
        }
    }

    /**
     * The input as seen by the matcher, with a budget on how many characters it may read.
     *
     * <p>Catastrophic backtracking shows up as an enormous number of character reads, so the budget
     * turns an exponential-time pattern into a bounded, reported failure instead of a stalled
     * request holding a worker thread.
     */
    private static final class BudgetedSequence implements CharSequence {

        private final CharSequence delegate;
        private int remaining;

        private BudgetedSequence(CharSequence delegate, int budget) {
            this.delegate = delegate;
            this.remaining = budget;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining < 0) {
                throw new BudgetExceededException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /** Control flow only; never escapes {@link #search(String, String)}. */
    private static final class BudgetExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private BudgetExceededException() {
            super(null, null, false, false);
        }
    }
}
