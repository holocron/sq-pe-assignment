package com.sq.caa.llm;

/**
 * A PUT that changes the embedding model without {@code confirmReembed: true}.
 *
 * <p>Changing the embedding model invalidates every stored vector, so the change is refused with
 * {@code 409} until the caller confirms it wants the whole knowledge base re-embedded.
 */
public class ReembedConfirmationRequiredException extends RuntimeException {

    public ReembedConfirmationRequiredException(String currentModel, String requestedModel) {
        super("Changing the embedding model from '" + currentModel + "' to '" + requestedModel
                + "' invalidates every stored embedding. Resend with \"confirmReembed\": true to "
                + "save the change and re-embed the whole knowledge base in the background.");
    }
}
