package com.sq.caa.agent;

/**
 * Normalises the free text the model writes for a human reader.
 *
 * <p>Everything the agent narrates - the final summary, the recommended actions, the per-rule
 * rationale - is rendered verbatim to a compliance officer. A language model that is running out of
 * context or has started to degenerate does not stop producing tokens; it produces damaged ones. A
 * real run of this system concluded with
 * {@code "1. File SARS ( S" + 100 non-breaking spaces + ")"}, which reached the
 * screen as a single unbreakable line that blew the "Recommended actions" panel across the page.
 *
 * <p>So model prose is cleaned once, at the boundary where it enters the system:
 *
 * <ul>
 *   <li>Unicode space separators (U+00A0 no-break space, U+202F narrow no-break space, U+2007
 *       figure space, ...) become ordinary spaces. {@link String#isBlank()} does not treat them as
 *       whitespace, so without this step a "blank" answer is neither blank nor readable.</li>
 *   <li>Control characters other than a newline are dropped - they cannot render and they would
 *       break the trace JSON and the CSV a reviewer might export.</li>
 *   <li>Runs of horizontal whitespace collapse to one space; runs of blank lines collapse to one
 *       blank line. Line structure is kept, because recommendations are one action per line.</li>
 *   <li>Text carrying no letter or digit is treated as absent, so the caller's generated fallback
 *       narrative is used instead of a line of punctuation - and, for a rule rationale, so a
 *       verdict cannot be submitted with punctuation in place of a reason.</li>
 *   <li>Length is capped. A summary is specified as three to six sentences; anything past
 *       {@value #MAX_CHARS} characters is a runaway generation, not a narrative.</li>
 * </ul>
 *
 * <p>The text is never rewritten beyond this. What the model said is what is shown - a wrong or
 * unhelpful conclusion must stay visible to the reviewer, because hiding it would misrepresent the
 * run.
 */
public final class Narrative {

    /** Past this, the model is generating rather than concluding. */
    public static final int MAX_CHARS = 4_000;

    /** Appended when {@link #MAX_CHARS} truncates the text, so the cut is never silent. */
    public static final String TRUNCATION_MARKER = " [...truncated]";

    /** Neither of these is whitespace to {@code Character}, and neither renders. */
    private static final char ZERO_WIDTH_SPACE = '\u200B';
    private static final char ZERO_WIDTH_NO_BREAK_SPACE = '\uFEFF';

    private Narrative() {
    }

    /**
     * @param text raw model output, possibly null
     * @return readable text, or null when the model effectively said nothing
     */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String collapsed = collapse(text);
        if (!carriesContent(collapsed)) {
            return null;
        }
        return truncate(collapsed);
    }

    private static String collapse(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int pendingSpaces = 0;
        int pendingNewlines = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                continue; // \r\n is handled by the \n that follows.
            }
            if (c == '\n') {
                pendingNewlines++;
                pendingSpaces = 0;
                continue;
            }
            if (isSpace(c)) {
                pendingSpaces++;
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            if (started) {
                // A newline outranks a space: "a  \n  b" is a line break, not three spaces.
                if (pendingNewlines > 0) {
                    out.append("\n".repeat(Math.min(pendingNewlines, 2)));
                } else if (pendingSpaces > 0) {
                    out.append(' ');
                }
            }
            out.append(c);
            started = true;
            pendingSpaces = 0;
            pendingNewlines = 0;
        }
        return out.toString();
    }

    /** True for anything that renders as horizontal blank space, including the non-breaking kinds. */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c)
                || Character.isSpaceChar(c)
                || c == ZERO_WIDTH_SPACE
                || c == ZERO_WIDTH_NO_BREAK_SPACE;
    }

    /** A line of brackets, digits-free punctuation or stray quotes is not a narrative. */
    private static boolean carriesContent(String text) {
        return text.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        int cut = MAX_CHARS - TRUNCATION_MARKER.length();
        int lastSpace = text.lastIndexOf(' ', cut);
        if (lastSpace > cut / 2) {
            cut = lastSpace;
        }
        return text.substring(0, cut) + TRUNCATION_MARKER;
    }
}
