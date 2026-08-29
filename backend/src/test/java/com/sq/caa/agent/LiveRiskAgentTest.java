package com.sq.caa.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.repository.CustomerRepository;
import com.sq.caa.service.RiskAnalysisService;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisAccepted;
import com.sq.caa.web.dto.AnalysisDtos.AnalysisResult;
import com.sq.caa.web.dto.AnalysisDtos.RuleEvaluationView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end analysis against the real chat model and the real database.
 *
 * <p>Tagged {@code live} and therefore excluded from the default build: it needs the lemonade router
 * on {@code localhost:13305} and takes minutes. Run it with
 * {@code mvn test -Dtest.excludedGroups= -Dtest=LiveRiskAgentTest}.
 *
 * <p>What it proves that the unit tests cannot: that the real model can drive the real tool loop to
 * a conclusion, and that the invariant the whole design rests on - a COMPLETED run has an agent
 * verdict for every applicable rule - survives contact with it. The scores it produces are the
 * model's estimates and will differ between runs; the coverage claim is what must not.
 */
@SpringBootTest
@Tag("live")
class LiveRiskAgentTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL = Duration.ofSeconds(5);

    @Autowired
    private RiskAnalysisService analysisService;

    @Autowired
    private CustomerRepository customers;

    @Test
    @DisplayName("a real run reaches a conclusion with 100% rule coverage")
    void realRunCoversEveryRule() throws Exception {
        List<Customer> all = customers.findAll();
        assumeTrue(!all.isEmpty(), "no seeded customers; run V3__seed.sql first");
        Customer customer = all.stream()
                .max((left, right) -> Integer.compare(left.getFullName().length(),
                        right.getFullName().length()))
                .orElseThrow();

        AnalysisAccepted accepted = analysisService.start(customer.getCustomerId(), "live-test");
        assertEquals(AnalysisStatus.RUNNING, accepted.status());

        AnalysisResult result = awaitCompletion(accepted.assessmentId());

        System.out.println(">>> LIVE RUN " + result.assessmentId() + " status=" + result.status()
                + " level=" + result.riskLevel() + " score=" + result.totalScore()
                + " steps=" + result.steps() + " coverage=" + result.coveragePercent() + "%"
                + " judged=" + result.rulesEvaluated() + "/" + result.rulesTotal());
        System.out.println(">>> SUMMARY: " + result.summary());

        assertEquals(AnalysisStatus.COMPLETED, result.status(),
                "the run failed: " + result.error());
        assertEquals(result.rulesTotal(), result.ruleEvaluations().size(),
                "every applicable rule must end with an agent verdict");
        assertEquals(result.rulesTotal(), result.rulesEvaluated());
        assertTrue(result.coverageComplete(),
                "a COMPLETED run must have judged every applicable rule");
        assertEquals(100.0, result.coveragePercent(), 0.001);
        assertNotNull(result.riskLevel());
        assertNotNull(result.summary());
        assertTrue(result.steps() > 0);
        assertTrue(result.trace().get("steps").size() > 0, "the ReAct transcript must be persisted");
        for (RuleEvaluationView view : result.ruleEvaluations()) {
            assertNotNull(view.source(), "every rule verdict must name its source");
        }
    }

    private AnalysisResult awaitCompletion(UUID assessmentId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        AnalysisResult result = analysisService.get(assessmentId);
        while (result.status() == AnalysisStatus.RUNNING && Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL.toMillis());
            result = analysisService.get(assessmentId);
        }
        return result;
    }
}
