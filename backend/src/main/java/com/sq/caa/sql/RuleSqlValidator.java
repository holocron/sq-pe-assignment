package com.sq.caa.sql;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a fragment written by a language model may be sent to PostgreSQL at all.
 *
 * <p>The stance is <b>allow-list first</b>. Three things are enumerated positively and everything
 * else is refused by default:
 *
 * <ul>
 *   <li><b>identifiers</b> - the five CTE names in {@link RuleSqlSchema}, their columns, the names
 *       the fragment itself introduces (aliases and its own CTEs), a fixed list of SQL keywords and
 *       a fixed list of type names;
 *   <li><b>functions</b> - a fixed list of aggregates, window functions, and string, maths and date
 *       functions. Any other name in call position is refused, so a function does not have to be
 *       known to be dangerous to be blocked - it only has to be absent;
 *   <li><b>lexical elements</b> - identifiers, numbers, single-quoted strings and a closed set of
 *       operators, all of them plain ASCII. Quoted identifiers, dollar quoting, string prefixes,
 *       array brackets, parameter markers, control characters and every byte above 0x7E have no
 *       accepted form - PostgreSQL's scanner treats those bytes as identifier characters and this
 *       one must not disagree with it about where a token ends.
 * </ul>
 *
 * <p><b>Names the fragment declares are tracked by kind, not pooled.</b> A penetration test showed
 * why: while every declared name went into one set, {@code SELECT tx.amount AS public, tx.currency
 * AS app_users FROM tx, public.app_users} type-checked, because both halves of {@code
 * public.app_users} were "declared". The table was still unreachable - the role holds no privilege
 * in schema {@code public} - but the guarantee had quietly moved one ring outwards. So a name is now
 * either a <i>range variable</i> (a relation, a CTE the fragment defines, or a {@code FROM}/{@code
 * JOIN} alias) or an <i>output column</i>, and the two are not interchangeable: only a range
 * variable may qualify a column, and {@link #checkRelationPositions} refuses a qualified name in
 * {@code FROM} or {@code JOIN} position outright, so no alias can make a schema-qualified table name
 * legal.
 *
 * <p>A deny-list runs <b>in front of</b> the allow-list, and it exists purely for the error message.
 * {@code INSERT}, {@code DROP}, {@code pg_sleep} and {@code information_schema} are all refused by
 * the allow-list already - they are simply not in it - but "INSERT is not allowed: the fragment must
 * be a single read-only SELECT" tells the model what to do next, whereas "unknown identifier
 * 'insert'" invites it to guess. Security never depends on the deny-list being complete.
 *
 * <p>What this class explicitly does <b>not</b> do is stand alone. It is one ring of four: the
 * fragment still runs inside {@link RuleSqlWrapper}'s parameter-bound CTEs, as a role with no
 * privilege outside five customer-scoped views, in a read-only transaction with a statement
 * timeout. Every attack test in {@code RuleSqlSecurityTest} is asserted twice - once here, and once
 * with the validator out of the way.
 */
public final class RuleSqlValidator {

    /**
     * The result of validating one fragment: a reason to refuse it, or the fragment ready to wrap.
     *
     * @param rejectionReason null when the fragment was accepted
     * @param fragment        the accepted fragment, trimmed and stripped of a trailing semicolon;
     *                        null when it was refused
     */
    public record Verdict(String rejectionReason, String fragment) {

        /** Whether the fragment may be sent to the database. */
        public boolean accepted() {
            return rejectionReason == null;
        }

        private static Verdict reject(String reason) {
            return new Verdict(reason, null);
        }

        private static Verdict accept(String fragment) {
            return new Verdict(null, fragment);
        }
    }

    /** Structural SQL words. Anything not here has no meaning to a rule query. */
    private static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "group", "by", "having", "order", "limit", "offset", "as",
            "and", "or", "not", "in", "is", "isnull", "notnull", "null", "true", "false", "unknown",
            "distinct", "on", "join", "inner", "left", "right", "full", "outer", "cross", "union",
            "all", "intersect", "except", "case", "when", "then", "else", "end", "between",
            "symmetric", "asymmetric", "like", "ilike", "similar", "escape", "asc", "desc", "nulls",
            "first", "last", "with", "materialized", "exists", "any", "some", "filter", "over",
            "partition", "rows", "range", "groups", "preceding", "following", "unbounded", "current",
            "row", "using", "natural", "within", "at", "zone", "to",
            // EXTRACT / date_part field names, which are bare words rather than literals
            "epoch", "year", "month", "day", "hour", "minute", "second", "dow", "isodow", "doy",
            "week", "quarter", "decade", "century", "millennium", "milliseconds", "microseconds",
            "timezone", "timezone_hour", "timezone_minute");

    /** Type names a cast may mention. */
    private static final Set<String> TYPES = Set.of(
            "int", "int2", "int4", "int8", "integer", "bigint", "smallint", "numeric", "decimal",
            "real", "double", "precision", "float", "float4", "float8", "text", "varchar", "char",
            "character", "varying", "boolean", "bool", "date", "timestamp", "timestamptz", "time",
            "interval", "uuid");

    /** Every function a rule query may call. Absence is refusal; nothing else is reachable. */
    private static final Set<String> FUNCTIONS = Set.of(
            // aggregates
            "count", "sum", "avg", "min", "max", "stddev", "stddev_pop", "stddev_samp", "variance",
            "var_pop", "var_samp", "bool_and", "bool_or", "every", "array_agg", "string_agg",
            "percentile_cont", "percentile_disc", "mode",
            // window
            "row_number", "rank", "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead",
            "first_value", "last_value", "nth_value",
            // maths
            "abs", "ceil", "ceiling", "floor", "round", "trunc", "mod", "div", "power", "sqrt",
            "exp", "ln", "log", "sign", "greatest", "least", "width_bucket",
            // conditional and casting
            "coalesce", "nullif", "cast", "extract",
            // strings
            "lower", "upper", "length", "char_length", "character_length", "trim", "btrim", "ltrim",
            "rtrim", "substr", "substring", "position", "strpos", "left", "right", "replace",
            "split_part", "concat", "concat_ws", "starts_with", "initcap", "translate", "reverse",
            "to_char", "to_number", "to_date", "to_timestamp",
            // date and time
            "now", "date_trunc", "date_part", "age", "make_date", "make_timestamp");

    /**
     * Words that mean the fragment is trying to be something other than a read-only SELECT. Refused
     * before the allow-list runs, only so the model is told which word ended the conversation.
     */
    private static final Set<String> FORBIDDEN_STATEMENTS = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "grant", "revoke", "truncate",
            "copy", "call", "do", "set", "vacuum", "analyze", "merge", "execute", "prepare",
            "deallocate", "listen", "unlisten", "notify", "lock", "reindex", "refresh", "cluster",
            "comment", "declare", "fetch", "move", "close", "begin", "start", "commit", "rollback",
            "savepoint", "release", "checkpoint", "discard", "explain", "reset", "into", "returning",
            "import", "load", "security", "definer", "for", "share", "nowait", "locked");

    /**
     * Name prefixes that reach the catalog, the filesystem or the network.
     *
     * <p>Deliberately not "crypt": the {@code crypto} relation starts with it, and a deny-list rule
     * that silently breaks a legitimate rule query is worse than useless. pgcrypto is refused by
     * exact name below instead - and by the allow-list regardless.
     */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "pg_", "lo_", "dblink", "xml", "txid_", "pgp_", "pgstat", "regclass", "regproc");

    /** Name fragments with the same problem, wherever they appear in the name. */
    private static final List<String> FORBIDDEN_INFIXES = List.of(
            "read_file", "ls_dir", "stat_file", "sleep", "exec", "shell", "terminate", "cancel");

    /** Names that leak the session, the server or its configuration. */
    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "information_schema", "current_setting", "set_config", "current_user", "session_user",
            "current_role", "current_catalog", "current_database", "current_schema",
            "current_schemas", "system_user", "user", "version", "inet_client_addr",
            "inet_server_addr", "program", "has_table_privilege", "has_schema_privilege",
            "to_regclass", "to_regproc", "query_to",
            "crypt", "gen_salt", "digest", "hmac", "encrypt", "decrypt");

    /**
     * Words that must never become legal, whatever the fragment declares.
     *
     * <p>Everything else in this class treats an unknown name as refused-unless-declared, which is
     * the right default for a column alias and the wrong one for these. They fall into three groups,
     * and each was reachable before this set existed:
     *
     * <ul>
     *   <li><b>schema names.</b> {@code public} and {@code caa_ro} are the only two schemas that
     *       exist here. {@link #checkRelationPositions} already refuses a qualified name where a
     *       table belongs, so this is the second lock on the same door, not the first;
     *   <li><b>clause words that change what a {@code FROM} item is.</b> {@code RECURSIVE} is the
     *       one that matters: a recursive CTE is the only construct reachable from this grammar that
     *       can allocate without bound, and {@code statement_timeout} is checked between executor
     *       steps, so it cannot interrupt a single doubling allocation. Measured on the shipped
     *       5-second timeout, a string-doubling recursion held roughly 0.7 GB in one backend before
     *       PostgreSQL stopped it on the 1 GB varlena limit. It is refused here rather than bounded;
     *   <li><b>the parenthesis-less session functions.</b> {@code current_role} and friends take no
     *       argument list, so they never reach the function allow-list. They cannot return a value -
     *       a rule query returns transaction ids - but they can steer a predicate, and a predicate
     *       that answers a question about the server is one bit per query.
     * </ul>
     *
     * <p>This runs on raw tokens in {@link #checkForbiddenWords}, before any declaration is
     * collected, which is what makes it unlaunderable.
     */
    private static final Set<String> NEVER_DECLARABLE = Set.of(
            "public", "caa_ro", "recursive", "lateral", "tablesample", "ordinality", "values",
            "current_role", "current_time", "current_date", "current_timestamp", "localtime",
            "localtimestamp");

    private static final Set<String> STRING_PREFIXES = Set.of("e", "u", "b", "x");

    private static final int MAX_TOKENS = 1500;
    private static final int MAX_PAREN_DEPTH = 30;
    private static final int MAX_LITERAL_CHARS = 256;

    private final int maxFragmentChars;

    /**
     * @param maxFragmentChars longest fragment accepted; a rule condition needs a few hundred
     *                         characters, and anything far past that is a payload, not a query
     */
    public RuleSqlValidator(int maxFragmentChars) {
        this.maxFragmentChars = Math.max(64, maxFragmentChars);
    }

    /** Validates one fragment. Never throws: every rejection is a reason the model can act on. */
    public Verdict validate(String agentSql) {
        if (agentSql == null || agentSql.isBlank()) {
            return Verdict.reject("the SQL fragment is empty: write a SELECT that returns one row "
                    + "per transaction matching the rule condition, projecting transaction_id.");
        }
        String fragment = stripTrailingSemicolon(agentSql.strip());
        if (fragment.isBlank()) {
            return Verdict.reject("the SQL fragment is empty once the trailing ';' is removed.");
        }
        if (fragment.length() > maxFragmentChars) {
            return Verdict.reject("the SQL fragment is " + fragment.length() + " characters, the "
                    + "limit is " + maxFragmentChars + ": express the rule condition directly "
                    + "instead of enumerating rows.");
        }

        List<Token> tokens = new ArrayList<>();
        String lexical = tokenize(fragment, tokens);
        if (lexical != null) {
            return Verdict.reject(lexical);
        }
        if (tokens.isEmpty()) {
            return Verdict.reject("the SQL fragment contains no SQL.");
        }

        String forbidden = checkForbiddenWords(tokens);
        if (forbidden != null) {
            return Verdict.reject(forbidden);
        }

        String shape = checkShape(tokens);
        if (shape != null) {
            return Verdict.reject(shape);
        }

        Declared declared = collectDeclaredNames(tokens);

        String names = checkNames(tokens, declared);
        if (names != null) {
            return Verdict.reject(names);
        }

        String relations = checkRelationPositions(tokens, declared.rangeVariables());
        if (relations != null) {
            return Verdict.reject(relations);
        }

        if (!projectsMatchColumn(tokens)) {
            return Verdict.reject("the SQL fragment never mentions " + RuleSqlSchema.MATCH_COLUMN
                    + ": a rule query has to return the transaction_id of every transaction that "
                    + "matches, because that is how the match is recorded.");
        }
        return Verdict.accept(fragment);
    }

    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql;
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    // -------------------------------------------------------------------------------------------
    // Lexer. Rejects on the character level everything that could hide a second meaning: comments,
    // extra statements, quoted identifiers, dollar quoting, string prefixes, parameter markers,
    // array syntax and unbalanced or over-nested parentheses.
    // -------------------------------------------------------------------------------------------

    private enum Kind { IDENTIFIER, NUMBER, STRING, PUNCT }

    private record Token(Kind kind, String text, List<String> parts) {

        static Token identifier(List<String> parts) {
            return new Token(Kind.IDENTIFIER, String.join(".", parts), parts);
        }

        static Token of(Kind kind, String text) {
            return new Token(kind, text, List.of());
        }

        boolean isPunct(String value) {
            return kind == Kind.PUNCT && text.equals(value);
        }

        boolean isWord(String value) {
            return kind == Kind.IDENTIFIER && parts.size() == 1 && text.equals(value);
        }
    }

    private static String tokenize(String sql, List<Token> out) {
        int i = 0;
        int length = sql.length();
        int depth = 0;
        while (i < length) {
            char ch = sql.charAt(i);
            if (isSeparator(ch)) {
                i++;
                continue;
            }
            if (ch < 0x20 || ch > 0x7E) {
                // Deliberately not Character.isWhitespace and deliberately not "unknown operator".
                // PostgreSQL's scanner treats every byte above 0x7F as an identifier character, so
                // U+2003 between SELECT and tx makes ONE identifier there while Java's definition
                // of whitespace makes two tokens here. Two lexers that disagree about where a token
                // ends is how a validator ends up approving a statement it did not read; the only
                // safe answer is to refuse every character the two could read differently.
                return "the SQL fragment contains the character U+%04X, which PostgreSQL and this "
                        .formatted((int) ch)
                        + "check would read differently: write plain ASCII SQL.";
            }
            if (out.size() > MAX_TOKENS) {
                return "the SQL fragment is longer than " + MAX_TOKENS + " tokens.";
            }
            if (ch == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                return "'--' comments are not allowed: everything the query does must be visible "
                        + "in the query itself.";
            }
            if (ch == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                return "'/*' comments are not allowed: everything the query does must be visible "
                        + "in the query itself.";
            }
            if (ch == '*' && i + 1 < length && sql.charAt(i + 1) == '/') {
                return "'*/' is not allowed: comments cannot be used in a rule query.";
            }
            if (ch == ';') {
                return "';' is not allowed: the fragment must be exactly one SELECT statement.";
            }
            if (ch == '"') {
                return "double-quoted identifiers are not allowed: write plain lower-case names "
                        + "such as tx.amount.";
            }
            if (ch == '$') {
                return "'$' is not allowed: neither dollar-quoted strings nor positional "
                        + "parameters have a place in a rule query.";
            }
            if (ch == '?') {
                return "'?' is not allowed: the customer is bound by the evaluator, the fragment "
                        + "takes no parameters.";
            }
            if (ch == '\\') {
                return "'\\' is not allowed.";
            }
            if (ch == '[' || ch == ']' || ch == '{' || ch == '}') {
                return "'" + ch + "' is not allowed: array and record syntax cannot be used in a "
                        + "rule query.";
            }
            if (ch == '\'') {
                int end = scanString(sql, i);
                if (end < 0) {
                    return "unterminated string literal: every ' must be closed, and a ' inside a "
                            + "literal is written ''.";
                }
                if (end - i - 1 > MAX_LITERAL_CHARS) {
                    return "a string literal is longer than " + MAX_LITERAL_CHARS + " characters.";
                }
                out.add(Token.of(Kind.STRING, sql.substring(i, end + 1)));
                i = end + 1;
                continue;
            }
            if (isIdentifierStart(ch)) {
                int end = i;
                while (end < length && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String word = sql.substring(i, end).toLowerCase(Locale.ROOT);
                if (end < length && sql.charAt(end) == '\'' && STRING_PREFIXES.contains(word)) {
                    return "prefixed string literals such as " + word.toUpperCase(Locale.ROOT)
                            + "'...' are not allowed: use a plain '...' literal.";
                }
                List<String> parts = new ArrayList<>(2);
                parts.add(word);
                i = end;
                while (i < length && sql.charAt(i) == '.') {
                    if (parts.size() == 2) {
                        return "'" + String.join(".", parts) + ".' is not allowed: names are "
                                + "either a column or relation.column, never schema-qualified.";
                    }
                    int next = i + 1;
                    if (next < length && sql.charAt(next) == '*') {
                        parts.add("*");
                        i = next + 1;
                        break;
                    }
                    if (next < length && isIdentifierStart(sql.charAt(next))) {
                        int partEnd = next;
                        while (partEnd < length && isIdentifierPart(sql.charAt(partEnd))) {
                            partEnd++;
                        }
                        parts.add(sql.substring(next, partEnd).toLowerCase(Locale.ROOT));
                        i = partEnd;
                        continue;
                    }
                    return "'" + word + ".' is followed by something that is not a column name.";
                }
                out.add(Token.identifier(parts));
                continue;
            }
            if (Character.isDigit(ch) || (ch == '.' && i + 1 < length
                    && Character.isDigit(sql.charAt(i + 1)))) {
                int end = i;
                while (end < length && (Character.isDigit(sql.charAt(end)) || sql.charAt(end) == '.')) {
                    end++;
                }
                if (end < length && (sql.charAt(end) == 'e' || sql.charAt(end) == 'E')) {
                    int exponent = end + 1;
                    if (exponent < length && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) {
                        exponent++;
                    }
                    if (exponent < length && Character.isDigit(sql.charAt(exponent))) {
                        end = exponent;
                        while (end < length && Character.isDigit(sql.charAt(end))) {
                            end++;
                        }
                    }
                }
                if (end < length && isIdentifierStart(sql.charAt(end))) {
                    return "'" + sql.substring(i, end + 1) + "' is not a number: numeric literals "
                            + "cannot be followed by a letter.";
                }
                out.add(Token.of(Kind.NUMBER, sql.substring(i, end)));
                i = end;
                continue;
            }
            String operator = scanOperator(sql, i);
            if (operator == null) {
                return "'" + ch + "' is not an operator a rule query may use. Allowed: "
                        + "+ - * / % = <> != < <= > >= || :: ( ) , and the SQL keywords.";
            }
            if (operator.equals("(")) {
                depth++;
                if (depth > MAX_PAREN_DEPTH) {
                    return "the SQL fragment nests parentheses more than " + MAX_PAREN_DEPTH
                            + " deep.";
                }
            } else if (operator.equals(")")) {
                depth--;
                if (depth < 0) {
                    return "unbalanced parentheses: a ')' has no matching '('.";
                }
            }
            out.add(Token.of(Kind.PUNCT, operator));
            i += operator.length();
        }
        if (depth != 0) {
            return "unbalanced parentheses: " + depth + " '(' were never closed.";
        }
        return null;
    }

    /** Index of the closing quote, or -1 when the literal never ends. Handles the '' escape. */
    private static int scanString(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return -1;
    }

    private static String scanOperator(String sql, int i) {
        char ch = sql.charAt(i);
        char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
        switch (ch) {
            case '<':
                if (next == '=' || next == '>') {
                    return sql.substring(i, i + 2);
                }
                return "<";
            case '>':
                return next == '=' ? ">=" : ">";
            case '!':
                return next == '=' ? "!=" : null;
            case '|':
                return next == '|' ? "||" : null;
            case ':':
                return next == ':' ? "::" : null;
            case '+', '-', '*', '/', '%', '=', '(', ')', ',':
                return String.valueOf(ch);
            default:
                return null;
        }
    }

    /**
     * The six characters PostgreSQL's own scanner treats as whitespace, and no others.
     *
     * <p>{@code Character.isWhitespace} is a Unicode predicate and answers true for U+2003 and the
     * rest of the Unicode space separators; PostgreSQL answers false and folds them into the
     * adjacent identifier. Using the Java predicate here made this class tokenise a statement
     * PostgreSQL would not.
     */
    private static boolean isSeparator(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\f' || ch == 0x0B;
    }

    private static boolean isIdentifierStart(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
    }

    private static boolean isIdentifierPart(char ch) {
        return isIdentifierStart(ch) || (ch >= '0' && ch <= '9');
    }

    // -------------------------------------------------------------------------------------------
    // Semantic passes.
    // -------------------------------------------------------------------------------------------

    private static String checkForbiddenWords(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.kind() != Kind.IDENTIFIER) {
                continue;
            }
            for (String part : token.parts()) {
                if (FORBIDDEN_STATEMENTS.contains(part)) {
                    return part.toUpperCase(Locale.ROOT) + " is not allowed: the fragment must be a "
                            + "single read-only SELECT that reads the customer's activity, nothing "
                            + "that writes, locks or changes anything.";
                }
                if (FORBIDDEN_NAMES.contains(part)) {
                    return "'" + part + "' is not allowed: a rule query may only read the "
                            + "customer's activity, never the server, the session or the catalog.";
                }
                if (NEVER_DECLARABLE.contains(part)) {
                    return "'" + part + "' cannot appear anywhere in a rule query - not as a "
                            + "relation, an alias or a column name. Name only the five relations "
                            + "and their columns: " + RuleSqlSchema.describe() + ".";
                }
                for (String prefix : FORBIDDEN_PREFIXES) {
                    if (part.startsWith(prefix)) {
                        return "'" + part + "' is not allowed: names starting with '" + prefix
                                + "' reach the catalog, the filesystem or the network.";
                    }
                }
                for (String infix : FORBIDDEN_INFIXES) {
                    if (part.contains(infix)) {
                        return "'" + part + "' is not allowed: it is not a function a rule query "
                                + "may call.";
                    }
                }
            }
        }
        return null;
    }

    private static String checkShape(List<Token> tokens) {
        Token first = tokens.get(0);
        if (first.isWord("select") || first.isWord("with")) {
            return null;
        }
        return "the fragment has to start with SELECT (or WITH ... SELECT); it starts with '"
                + first.text() + "'.";
    }

    /**
     * The names a fragment brings with it, separated by what they are allowed to mean.
     *
     * @param rangeVariables things that can stand to the left of a dot: the CTEs the fragment
     *                       defines and the aliases it gives to relations and sub-selects in
     *                       {@code FROM} and {@code JOIN}
     * @param outputColumns  things that can stand to the right of one: the aliases it gives to
     *                       computed columns
     */
    private record Declared(Set<String> rangeVariables, Set<String> outputColumns) {

        boolean names(String word) {
            return rangeVariables.contains(word) || outputColumns.contains(word);
        }
    }

    /**
     * Collects both kinds of declared name in one pass.
     *
     * <p>A fragment that cannot name its own alias cannot be written at all, so declarations have to
     * relax the allow-list. What matters is <b>how far</b>: an output-column alias now only makes a
     * name usable as a column, and the only way to introduce a range variable is to alias something
     * in {@code FROM} or {@code JOIN} - which {@link #checkRelationPositions} independently requires
     * to be one of the five relations or a CTE defined here. Neither kind can conjure a relation,
     * and neither can be used in place of the other.
     */
    private static Declared collectDeclaredNames(List<Token> tokens) {
        Set<String> rangeVariables = new LinkedHashSet<>();
        Set<String> outputColumns = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isWord("as")) {
                int aliasAt = skipMaterialized(tokens, i + 1);
                Token alias = at(tokens, aliasAt);
                Token opener = at(tokens, aliasAt + 1);
                Token cteName = at(tokens, i - 1);
                boolean cteDefinition = (alias != null && alias.isPunct("("))
                        || (opener != null && opener.isPunct("("));
                if (cteDefinition) {
                    // "name AS (" and "name AS MATERIALIZED (": a CTE, which is a relation.
                    if (cteName != null && isPlainName(cteName) && !isReserved(cteName.text())) {
                        rangeVariables.add(cteName.text());
                    }
                } else if (alias != null && isPlainName(alias) && !isReserved(alias.text())) {
                    // "expression AS name". Whether this is a column alias or a FROM alias is
                    // decided by checkRelationPositions, which adds the FROM ones itself.
                    outputColumns.add(alias.text());
                }
                continue;
            }
            if (!isPlainName(token) || isReserved(token.text())) {
                continue;
            }
            Token next = at(tokens, i + 1);
            if (next != null && next.isPunct("(")) {
                continue;
            }
            Token previous = at(tokens, i - 1);
            boolean followsValue = previous != null
                    && (previous.isPunct(")")
                        || (previous.kind() == Kind.IDENTIFIER && !isReserved(previous.text())));
            if (followsValue) {
                outputColumns.add(token.text());
            }
        }
        collectRangeVariables(tokens, rangeVariables);
        return new Declared(rangeVariables, outputColumns);
    }

    /** Walks every {@code FROM} and {@code JOIN} and records the aliases they introduce. */
    private static void collectRangeVariables(List<Token> tokens, Set<String> rangeVariables) {
        walkFromClauses(tokens, index -> {
            scanFromList(tokens, index, null, rangeVariables);
            return null;
        });
    }

    /**
     * Refuses anything in relation position that is not one of the five relations or a CTE the
     * fragment defines - and, above all, refuses a qualified name there at all.
     *
     * <p>This is the structural half of the fix for alias laundering. {@code public.app_users} is
     * one token with two parts; wherever a table belongs, a two-part name is rejected on its shape,
     * before anything has been declared and regardless of what has been. There is consequently no
     * fragment in which naming a schema is legal, and the guarantee is back in this class rather
     * than resting on the grants alone.
     */
    private static String checkRelationPositions(List<Token> tokens, Set<String> rangeVariables) {
        return walkFromClauses(tokens,
                index -> scanFromList(tokens, index, rangeVariables, null));
    }

    /** Handed the index of the first token after a {@code FROM} or {@code JOIN} keyword. */
    private interface FromClauseVisitor {
        String visit(int firstItem);
    }

    /**
     * Calls {@code visitor} once per real {@code FROM} or {@code JOIN} clause.
     *
     * <p>"Real" is the whole difficulty: {@code EXTRACT(EPOCH FROM b - a)}, {@code SUBSTRING(x FROM
     * 2)} and {@code TRIM(BOTH FROM x)} all spell {@code FROM} inside a function's argument list and
     * none of them starts a table list. A paren stack records whether each open parenthesis followed
     * a function name, and a {@code FROM} whose innermost parenthesis is a call is skipped. A
     * sub-select nested inside such a call opens its own parenthesis - which does not follow a
     * function name - so its {@code FROM} is still checked.
     */
    private static String walkFromClauses(List<Token> tokens, FromClauseVisitor visitor) {
        Deque<Boolean> callParentheses = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                Token previous = at(tokens, i - 1);
                callParentheses.push(previous != null && isPlainName(previous)
                        && !isReserved(previous.text()));
                continue;
            }
            if (token.isPunct(")")) {
                callParentheses.poll();
                continue;
            }
            boolean from = token.isWord("from");
            if (!from && !token.isWord("join")) {
                continue;
            }
            if (from && Boolean.TRUE.equals(callParentheses.peek())) {
                continue;
            }
            String problem = visitor.visit(i + 1);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /**
     * Reads one comma-separated table list.
     *
     * @param allowed        relation names that may appear here; null skips the check, which is how
     *                       the collecting pass runs before the checking one
     * @param rangeVariables collects the aliases found; null discards them
     * @return the reason to refuse the fragment, or null
     */
    private static String scanFromList(List<Token> tokens, int start, Set<String> allowed,
            Set<String> rangeVariables) {
        int i = start;
        while (i < tokens.size()) {
            Token item = tokens.get(i);
            if (item.isPunct("(")) {
                i = skipParenthesised(tokens, i);
            } else if (item.kind() == Kind.IDENTIFIER && item.parts().size() > 1) {
                return "'" + item.text() + "' is not a table this query may read: a relation is "
                        + "named by one plain word, never qualified by a schema. The fragment reads "
                        + RuleSqlSchema.describe() + ", and nothing else.";
            } else if (isPlainName(item) && !isReserved(item.text())) {
                Token next = at(tokens, i + 1);
                if (next != null && next.isPunct("(")) {
                    return "'" + item.text() + "' cannot appear in FROM: a rule query reads the "
                            + "customer's activity relations, not the result of a function.";
                }
                if (allowed != null && !RuleSqlSchema.isRelation(item.text())
                        && !allowed.contains(item.text())) {
                    return "'" + item.text() + "' is not a relation this query may read. Available: "
                            + RuleSqlSchema.describe() + ".";
                }
                i++;
            } else {
                return null;
            }
            Token alias = at(tokens, i);
            if (alias != null && alias.isWord("as")) {
                i++;
                alias = at(tokens, i);
            }
            if (alias != null && isPlainName(alias) && !isReserved(alias.text())) {
                if (rangeVariables != null) {
                    rangeVariables.add(alias.text());
                }
                i++;
            }
            Token separator = at(tokens, i);
            if (separator == null || !separator.isPunct(",")) {
                return null;
            }
            i++;
        }
        return null;
    }

    /** Index just past the parenthesis opened at {@code open}, or the end of the fragment. */
    private static int skipParenthesised(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).isPunct("(")) {
                depth++;
            } else if (tokens.get(i).isPunct(")") && --depth == 0) {
                return i + 1;
            }
        }
        return tokens.size();
    }

    private static int skipMaterialized(List<Token> tokens, int index) {
        int i = index;
        Token token = at(tokens, i);
        if (token != null && token.isWord("not")) {
            i++;
            token = at(tokens, i);
        }
        if (token != null && token.isWord("materialized")) {
            i++;
        }
        return i;
    }

    private static Token at(List<Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
    }

    private static boolean isPlainName(Token token) {
        return token.kind() == Kind.IDENTIFIER && token.parts().size() == 1;
    }

    private static boolean isReserved(String word) {
        return KEYWORDS.contains(word) || TYPES.contains(word);
    }

    private static String checkNames(List<Token> tokens, Declared declared) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() != Kind.IDENTIFIER) {
                continue;
            }
            Token next = at(tokens, i + 1);
            boolean callPosition = next != null && next.isPunct("(");
            if (callPosition && !isReserved(token.text())) {
                if (token.parts().size() != 1) {
                    return "'" + token.text() + "' is not allowed: functions cannot be called by a "
                            + "qualified name.";
                }
                if (!FUNCTIONS.contains(token.text())) {
                    return "'" + token.text() + "' is not a function a rule query may call. "
                            + "Available: " + sorted(FUNCTIONS) + ".";
                }
                continue;
            }
            String problem = token.parts().size() == 1
                    ? checkSimpleName(token.text(), declared)
                    : checkQualifiedName(token.parts(), declared);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String checkSimpleName(String name, Declared declared) {
        if (isReserved(name) || FUNCTIONS.contains(name) || declared.names(name)
                || RuleSqlSchema.isRelation(name) || RuleSqlSchema.isColumn(name)) {
            return null;
        }
        return "'" + name + "' is not something a rule query can name. The fragment may only use "
                + "these five relations, which already contain nothing but this customer's "
                + "activity: " + RuleSqlSchema.describe() + ".";
    }

    /**
     * Checks {@code qualifier.column}.
     *
     * <p>The qualifier half is the one that was laundered. It has to be a relation or a range
     * variable - a name the fragment put in {@code FROM} or {@code WITH} - and an output-column
     * alias is deliberately not enough, because {@code SELECT tx.amount AS public} must not make
     * {@code public.anything} a legal name.
     */
    private static String checkQualifiedName(List<String> parts, Declared declared) {
        String qualifier = parts.get(0);
        String column = parts.get(1);
        if (RuleSqlSchema.isRelation(qualifier)) {
            if (column.equals("*") || RuleSqlSchema.columnsOf(qualifier).contains(column)) {
                return null;
            }
            return "'" + qualifier + "." + column + "' does not exist: " + qualifier + " has "
                    + String.join(", ", RuleSqlSchema.columnsOf(qualifier)) + ".";
        }
        if (declared.rangeVariables().contains(qualifier)) {
            if (column.equals("*") || RuleSqlSchema.isColumn(column)
                    || declared.outputColumns().contains(column)) {
                return null;
            }
            return "'" + qualifier + "." + column + "' does not exist: " + column + " is not a "
                    + "column of any relation the fragment may read (" + RuleSqlSchema.describe()
                    + ") and it is not a column this query computes.";
        }
        if (declared.outputColumns().contains(qualifier)) {
            return "'" + qualifier + "' is a column this query computes, not a table: a name to the "
                    + "left of a dot has to be one of " + String.join(", ",
                    RuleSqlSchema.relationNames()) + " or an alias introduced in FROM or WITH.";
        }
        return "'" + qualifier + "' is not a relation the fragment may read, and it is not an alias "
                + "the fragment introduces in FROM or WITH. Available: " + RuleSqlSchema.describe()
                + ".";
    }

    /**
     * Whether the fragment can possibly return a transaction id.
     *
     * <p>Naming the column counts, and so does a star that is not the {@code count(*)} star: {@code
     * SELECT * FROM tx WHERE ...} does project it. This is a static check on a language that is not
     * statically decidable, so it is deliberately the weaker half of the guarantee - the wrapper's
     * join to {@code tx} is the half that cannot be talked around, and a fragment that gets past
     * here without the column fails on it with a PostgreSQL error naming exactly what is missing.
     */
    private static boolean projectsMatchColumn(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.IDENTIFIER
                    && (token.parts().contains(RuleSqlSchema.MATCH_COLUMN)
                        || token.parts().contains("*"))) {
                return true;
            }
            Token previous = at(tokens, i - 1);
            if (token.isPunct("*") && (previous == null || !previous.isPunct("("))) {
                return true;
            }
        }
        return false;
    }

    private static String sorted(Set<String> words) {
        return words.stream().sorted().reduce((a, b) -> a + ", " + b).orElse("");
    }
}
