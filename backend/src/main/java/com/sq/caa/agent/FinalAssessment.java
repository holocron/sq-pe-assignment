package com.sq.caa.agent;

import com.sq.caa.domain.RiskLevel;

/**
 * The narrative conclusion the agent submitted through {@code submit_final_assessment}.
 *
 * <p>{@code riskLevel} is the band the agent asked for. It is not the band that is persisted on its
 * own authority: the mechanical band is derived by banding the sum of the per-rule scores, every one
 * of which came out of a SQL result rather than out of the model. The agent may only move the band
 * <em>upwards</em> from there, and only with {@code escalationJustification} recorded alongside it;
 * a proposal below the mechanical band is refused by the tool and, on the prose path where there is
 * no tool to refuse it, ignored by {@link RiskAgentLoop#settle}.
 *
 * <p>All three narratives pass through {@link Narrative#clean} here rather than at the call sites,
 * so the tool path and the prose-parser path cannot diverge. A narrative that carries nothing
 * readable becomes null, which is the signal the loop uses to generate a summary from the verdicts
 * instead - and a null justification is precisely what makes an escalation inadmissible.
 */
public record FinalAssessment(RiskLevel riskLevel, String escalationJustification, String summary,
        String recommendations) {

    public FinalAssessment {
        escalationJustification = Narrative.clean(escalationJustification);
        summary = Narrative.clean(summary);
        recommendations = Narrative.clean(recommendations);
    }

    /**
     * The band this conclusion may actually be recorded at, given the mechanical one.
     *
     * @return {@code riskLevel} when it is above {@code mechanical} and a justification was given,
     *         otherwise {@code mechanical} - a lower band is never honoured
     */
    public RiskLevel bandOver(RiskLevel mechanical) {
        return escalates(mechanical) ? riskLevel : mechanical;
    }

    /** True when this conclusion moves the band above {@code mechanical} and says why. */
    public boolean escalates(RiskLevel mechanical) {
        return riskLevel != null && mechanical != null && riskLevel.compareTo(mechanical) > 0
                && escalationJustification != null;
    }
}
