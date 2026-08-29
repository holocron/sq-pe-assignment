package com.sq.caa.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns parsed sections into embeddable, overlapping windows.
 *
 * <p>The assignment asks for documents "chunked to its sections", and section boundaries are the
 * primary split: a chunk never spans two sections, so every hit can be attributed to one policy
 * section. Sections are not uniformly sized though - a policy will have a two-line definition next
 * to a four-page schedule - and a single vector over four pages is a poor summary of any of it.
 * Oversized sections are therefore cut into windows of about
 * {@link #targetTokens()} tokens with {@link #overlapTokens()} tokens of overlap, so a statement
 * that straddles a window boundary still appears whole in one of them.
 *
 * <p>Windows are cut at natural boundaries, in decreasing order of preference: paragraph, then
 * sentence, then word. A single word is never split. The repeated overlap follows the same
 * preference: whole paragraphs when they fit the overlap budget, otherwise the trailing sentences
 * or words of the last paragraph, so consecutive windows always share text.
 *
 * <p>Every chunk is prefixed with its section heading. That costs a handful of tokens and buys two
 * things: the embedding carries the topic even when the window starts mid-argument, and a retrieved
 * chunk reads as a self-contained quote in the agent's prompt.
 *
 * <p>Whitespace-only chunks are impossible by construction - blank units are dropped on the way in
 * and every emitted window is verified non-blank on the way out.
 *
 * <p>Deliberately not a Spring bean: it is a pure, immutable function object that
 * {@code RagService} builds from {@link RagProperties}, which keeps it trivially unit-testable at
 * any window size.
 */
public class SectionChunker {

    /** Target window size. Comfortably inside the embedding model's input limit. */
    public static final int DEFAULT_TARGET_TOKENS = 800;

    /** Tokens repeated from the end of one window at the start of the next. */
    public static final int DEFAULT_OVERLAP_TOKENS = 100;

    /**
     * Smallest window we will ever ask for. Guards the degenerate case of a section heading so long
     * that the heading prefix alone eats the whole budget.
     */
    private static final int MIN_WINDOW_TOKENS = 120;

    /** Sentence end followed by whitespace and a new sentence. */
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?])[\"')\\]]*\\s+(?=[\\p{Lu}\\p{N}\"'(\\[])");

    /** One or more blank lines separate paragraphs. */
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n\\s*\\n");

    private final int targetTokens;
    private final int overlapTokens;

    public SectionChunker() {
        this(DEFAULT_TARGET_TOKENS, DEFAULT_OVERLAP_TOKENS);
    }

    public SectionChunker(int targetTokens, int overlapTokens) {
        if (targetTokens < MIN_WINDOW_TOKENS) {
            throw new IllegalArgumentException(
                    "targetTokens must be at least " + MIN_WINDOW_TOKENS + ", was " + targetTokens);
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("overlapTokens must not be negative");
        }
        this.targetTokens = targetTokens;
        // An overlap of half a window or more would make windows advance too slowly, in the worst
        // case not at all, so it is capped rather than trusted.
        this.overlapTokens = Math.min(overlapTokens, targetTokens / 2);
    }

    public int targetTokens() {
        return targetTokens;
    }

    public int overlapTokens() {
        return overlapTokens;
    }

    /** Chunks a whole document. Chunk indexes run continuously across all its sections. */
    public List<TextChunk> chunk(ParsedDocument document) {
        List<TextChunk> chunks = new ArrayList<>();
        for (ParsedSection section : document.sections()) {
            chunks.addAll(chunkSection(section, chunks.size()));
        }
        return chunks;
    }

    /** Chunks one section, numbering its chunks from {@code startIndex}. */
    List<TextChunk> chunkSection(ParsedSection section, int startIndex) {
        if (!section.hasText()) {
            return List.of();
        }
        String heading = HeadingHeuristics.capHeadingLength(
                section.title() == null ? "" : section.title().strip());
        String prefix = heading.isEmpty() ? "" : heading + "\n\n";
        int budget = Math.max(targetTokens - TokenEstimator.estimate(prefix), MIN_WINDOW_TOKENS);

        List<Unit> units = split(section.text(), budget);

        List<String> contents = new ArrayList<>();
        for (String window : pack(units, budget)) {
            String content = (prefix + window).strip();
            if (!content.isBlank()) {
                contents.add(content);
            }
        }

        List<TextChunk> chunks = new ArrayList<>(contents.size());
        for (int window = 0; window < contents.size(); window++) {
            String content = contents.get(window);
            chunks.add(new TextChunk(startIndex + window, section.order(), heading, window,
                    contents.size(), content, TokenEstimator.estimate(content)));
        }
        return chunks;
    }

    /* ------------------------------------------------------------------ */
    /* Splitting                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Breaks section text into units that are each guaranteed to fit the budget, preferring
     * paragraph boundaries, then sentence boundaries, then word boundaries.
     */
    private List<Unit> split(String text, int budget) {
        List<Unit> units = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BOUNDARY.split(text)) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (TokenEstimator.estimate(trimmed) <= budget) {
                units.add(new Unit(trimmed, "\n\n"));
                continue;
            }
            boolean first = true;
            for (String sentence : SENTENCE_BOUNDARY.split(trimmed)) {
                String candidate = sentence.strip();
                if (candidate.isEmpty()) {
                    continue;
                }
                String separator = first ? "\n\n" : " ";
                if (TokenEstimator.estimate(candidate) <= budget) {
                    units.add(new Unit(candidate, separator));
                } else {
                    for (String piece : hardSplit(candidate, budget)) {
                        units.add(new Unit(piece, separator));
                        separator = " ";
                    }
                }
                first = false;
            }
        }
        return units;
    }

    /** Last resort for a sentence longer than a whole window: cut it on word boundaries. */
    private List<String> hardSplit(String text, int budget) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        int tokens = 0;
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int wordTokens = TokenEstimator.estimate(word);
            if (tokens + wordTokens > budget && piece.length() > 0) {
                pieces.add(piece.toString());
                piece.setLength(0);
                tokens = 0;
            }
            if (piece.length() > 0) {
                piece.append(' ');
            }
            piece.append(word);
            tokens += wordTokens;
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    /* ------------------------------------------------------------------ */
    /* Packing                                                             */
    /* ------------------------------------------------------------------ */

    /** Greedily fills windows up to the budget, seeding each new window with the tail of the last. */
    private List<String> pack(List<Unit> units, int budget) {
        List<String> windows = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        int tokens = 0;

        for (Unit unit : units) {
            int unitTokens = TokenEstimator.estimate(unit.text());
            if (!current.isEmpty() && tokens + unitTokens > budget) {
                windows.add(render(current));
                current = overlapTail(current, budget);
                tokens = totalTokens(current);
            }
            current.add(unit);
            tokens += unitTokens;
        }
        if (!current.isEmpty()) {
            String rendered = render(current);
            if (!rendered.isBlank()) {
                windows.add(rendered);
            }
        }
        return windows;
    }

    /**
     * The trailing text of a window that should be repeated at the start of the next one.
     *
     * <p>Whole units are preferred, but a policy paragraph routinely runs 400-700 characters -
     * comfortably more than the default 100-token overlap - so insisting on whole units would make
     * the overlap silently empty for exactly the documents this knowledge base is built from. When
     * the last unit alone does not fit the overlap budget, its own trailing sentences (or, failing
     * that, its trailing words) are repeated instead. The overlap is therefore never empty unless
     * it was configured to be.
     *
     * <p>The tail is capped at {@link #overlapTokens} and at half the window budget, so a window is
     * never re-emitted whole. Termination does not depend on this cap: {@link #pack} always appends
     * the unit that triggered the flush, so every window consumes at least one fresh unit.
     */
    private List<Unit> overlapTail(List<Unit> window, int budget) {
        List<Unit> tail = new ArrayList<>();
        int cap = Math.min(overlapTokens, budget / 2);
        if (cap <= 0 || window.isEmpty()) {
            return tail;
        }
        int tokens = 0;
        for (int i = window.size() - 1; i >= 0; i--) {
            Unit unit = window.get(i);
            int unitTokens = TokenEstimator.estimate(unit.text());
            if (tokens + unitTokens > cap) {
                if (tail.isEmpty()) {
                    String partial = trailingText(unit.text(), cap);
                    if (!partial.isBlank()) {
                        tail.add(new Unit(partial, unit.separator()));
                    }
                }
                break;
            }
            tail.add(0, unit);
            tokens += unitTokens;
        }
        return tail;
    }

    /**
     * The last {@code budget} tokens' worth of a single unit, cut at a sentence boundary where one
     * is available and at a word boundary otherwise. Used when one paragraph is larger than the
     * whole overlap budget, which is the normal case for policy prose.
     */
    private static String trailingText(String text, int budget) {
        String[] sentences = SENTENCE_BOUNDARY.split(text);
        String fromSentences = trailingJoin(sentences, budget, " ");
        if (!fromSentences.isBlank()) {
            return fromSentences;
        }
        return trailingJoin(text.split("\\s+"), budget, " ");
    }

    /** Joins as many trailing elements as fit the budget, in their original order. */
    private static String trailingJoin(String[] parts, int budget, String separator) {
        int tokens = 0;
        int from = parts.length;
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].strip();
            if (part.isEmpty()) {
                continue;
            }
            int partTokens = TokenEstimator.estimate(part);
            if (tokens + partTokens > budget) {
                break;
            }
            tokens += partTokens;
            from = i;
        }
        if (from >= parts.length) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(separator);
            }
            joined.append(part);
        }
        return joined.toString();
    }

    private static int totalTokens(List<Unit> units) {
        int tokens = 0;
        for (Unit unit : units) {
            tokens += TokenEstimator.estimate(unit.text());
        }
        return tokens;
    }

    private static String render(List<Unit> units) {
        StringBuilder rendered = new StringBuilder();
        for (Unit unit : units) {
            if (rendered.length() > 0) {
                rendered.append(unit.separator());
            }
            rendered.append(unit.text());
        }
        return rendered.toString().strip();
    }

    /**
     * An indivisible piece of text plus the separator to write in front of it when it follows
     * another piece, so paragraphs stay paragraphs and sentences stay on one line.
     */
    private record Unit(String text, String separator) {
    }
}
