package com.sq.caa.rag;

/**
 * Cheap token-count approximation used to size chunks.
 *
 * <p>The embedding model is a GGUF build behind an OpenAI-compatible router, so its tokenizer is
 * not available in-process and a real BPE count would cost a network round trip per candidate
 * window. Chunk sizing only needs to be roughly right - a window of 780 or 830 tokens embeds
 * equally well - so the well-known "about four characters per token" rule of thumb is applied per
 * word, which keeps the estimate monotone in the text length and never returns zero for a
 * non-empty word.
 *
 * <p>The estimate is deliberately conservative (it rounds up), so the real token count of a window
 * sized against {@link SectionChunker#targetTokens()} stays under the model's input limit.
 */
public final class TokenEstimator {

    /** Characters per token for English prose; the usual OpenAI rule of thumb. */
    private static final double CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {
    }

    /** Estimated token length of an arbitrary string. */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int wordLength = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                tokens += tokensForWord(wordLength);
                wordLength = 0;
            } else {
                wordLength++;
            }
        }
        tokens += tokensForWord(wordLength);
        return tokens;
    }

    /**
     * Tokens contributed by a word of the given length, including the whitespace that separates it
     * from the next one.
     */
    private static int tokensForWord(int wordLength) {
        if (wordLength == 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil((wordLength + 1) / CHARS_PER_TOKEN));
    }
}
