package com.sq.caa.web;

import com.sq.caa.domain.RiskRule;
import com.sq.caa.rules.DuplicateRuleNameException;
import com.sq.caa.rules.FieldDefinition;
import com.sq.caa.rules.RuleDraft;
import com.sq.caa.rules.RuleInUseException;
import com.sq.caa.rules.RuleJudgementException;
import com.sq.caa.rules.RuleNotFoundException;
import com.sq.caa.rules.RuleValidationException;
import com.sq.caa.rules.UnknownCustomerException;
import com.sq.caa.security.SecurityRoles;
import com.sq.caa.service.RiskRuleService;
import com.sq.caa.web.dto.RuleDtos.FieldCatalogEntry;
import com.sq.caa.web.dto.RuleDtos.RiskRuleDto;
import com.sq.caa.web.dto.RuleDtos.RuleTestRequest;
import com.sq.caa.web.dto.RuleDtos.RuleTestResponse;
import com.sq.caa.web.dto.RuleDtos.RuleUpsertRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk rule administration.
 *
 * <p>Reading rules is open to any authenticated user because the analysis screens show them;
 * everything that changes a rule, reads the field catalog or spends a model call is restricted to
 * administrators and enforced here as well as in the URL security rules.
 *
 * <p>{@code threshold_logic} is natural language - the sentence the ReAct agent reads before it goes
 * and looks at the customer's data - so this controller only ever moves text. It parses nothing. The
 * two things it still owns are validation of that text (via the service) and turning the failure
 * modes of a model call into RFC-7807 responses an admin can act on.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private static final Logger log = LoggerFactory.getLogger(RuleController.class);

    private final RiskRuleService riskRuleService;

    public RuleController(RiskRuleService riskRuleService) {
        this.riskRuleService = riskRuleService;
    }

    /** Every rule, name ascending, with the latest judgement and firing times of each. */
    @GetMapping
    @PreAuthorize(SecurityRoles.IS_OPERATOR_OR_ADMIN)
    public List<RiskRuleDto> list() {
        var statsByRule = riskRuleService.activityStatsByRule();
        return riskRuleService.findAll().stream()
                .map(rule -> RiskRuleDto.from(rule, statsByRule.get(rule.getRuleId())))
                .toList();
    }

    /** Creates a rule. */
    @PostMapping
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public RiskRuleDto create(@Valid @RequestBody RuleUpsertRequest request) {
        RiskRule rule = riskRuleService.create(request.ruleName(), request.appliesTo(),
                request.thresholdLogic(), request.weight());
        return RiskRuleDto.from(rule);
    }

    /** Replaces a rule. */
    @PutMapping("/{ruleId}")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    public RiskRuleDto update(@PathVariable UUID ruleId, @Valid @RequestBody RuleUpsertRequest request) {
        RiskRule rule = riskRuleService.update(ruleId, request.ruleName(), request.appliesTo(),
                request.thresholdLogic(), request.weight());
        return RiskRuleDto.from(rule);
    }

    /**
     * Deletes a rule. A rule that historical {@code risk_assessments} rows still reference is not
     * deleted - the service refuses with {@code 409} so past analyses keep their evidence.
     */
    @DeleteMapping("/{ruleId}")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ruleId) {
        riskRuleService.delete(ruleId);
    }

    /**
     * The data a rule condition may talk about.
     *
     * <p>Not a grammar - conditions are prose - but the reference an author needs: these are exactly
     * the values the agent can fetch, so a condition written against them is one it can settle from
     * evidence instead of from memory.
     */
    @GetMapping("/field-catalog")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    public List<FieldCatalogEntry> fieldCatalog() {
        List<FieldDefinition> definitions = riskRuleService.fieldCatalog();
        return definitions.stream().map(FieldCatalogEntry::from).toList();
    }

    /**
     * Judges a draft rule against one customer, for real, with the agent's model.
     *
     * <p>Synchronous and slow by nature: it is one model call, bounded by
     * {@code caa.rules.judge.timeout-seconds}. Two runs of the same draft may differ - that is what
     * it means for the agent to be the scoring authority - so the response carries the model id, the
     * elapsed time and every correction that had to be applied to the answer.
     */
    @PostMapping("/test")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    public RuleTestResponse test(@Valid @RequestBody RuleTestRequest request) {
        RuleDraft draft = new RuleDraft(
                request.ruleName() == null || request.ruleName().isBlank()
                        ? "Draft rule" : request.ruleName().trim(),
                request.appliesTo(), request.thresholdLogic(), request.weight());
        return RuleTestResponse.from(riskRuleService.judgeRule(draft, request.customerId()));
    }

    // ------------------------------------------------------------------
    // Problem responses
    // ------------------------------------------------------------------

    /** A rule that cannot be saved as written. {@code field} names the input to highlight. */
    @ExceptionHandler(RuleValidationException.class)
    public ResponseEntity<ProblemDetail> onInvalidRule(RuleValidationException e, HttpServletRequest request) {
        log.debug("Rejected rule: {} {}", e.field(), e.getMessage());
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid rule definition",
                e.describe(), request);
        problem.setProperty("field", e.field());
        return respond(problem);
    }

    /**
     * A judgement that could not be obtained.
     *
     * <p>Mapped by cause, because the four cases need different answers from the caller: wait
     * (504/503), fix the configuration (503), or look at what the model actually said (502).
     */
    @ExceptionHandler(RuleJudgementException.class)
    public ResponseEntity<ProblemDetail> onFailedJudgement(RuleJudgementException e,
            HttpServletRequest request) {
        HttpStatus status = switch (e.reason()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BUSY, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case MODEL_ERROR, UNREADABLE_ANSWER -> HttpStatus.BAD_GATEWAY;
        };
        log.warn("Rule judgement failed ({}): {}", e.reason(), e.getMessage());
        ProblemDetail problem = problem(status, "Rule could not be judged", e.getMessage(), request);
        problem.setProperty("reason", e.reason().name());
        return respond(problem);
    }

    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ProblemDetail> onMissingRule(RuleNotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Rule not found", e.getMessage(), request);
        problem.setProperty("ruleId", String.valueOf(e.ruleId()));
        return respond(problem);
    }

    @ExceptionHandler(UnknownCustomerException.class)
    public ResponseEntity<ProblemDetail> onMissingCustomer(UnknownCustomerException e,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Customer not found", e.getMessage(), request);
        problem.setProperty("customerId", String.valueOf(e.customerId()));
        return respond(problem);
    }

    @ExceptionHandler(RuleInUseException.class)
    public ResponseEntity<ProblemDetail> onRuleInUse(RuleInUseException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Rule is referenced by past analyses",
                e.getMessage(), request);
        problem.setProperty("ruleId", String.valueOf(e.ruleId()));
        return respond(problem);
    }

    @ExceptionHandler(DuplicateRuleNameException.class)
    public ResponseEntity<ProblemDetail> onDuplicateName(DuplicateRuleNameException e,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Duplicate rule name", e.getMessage(), request);
        problem.setProperty("ruleName", e.ruleName());
        return respond(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        if (request != null && request.getRequestURI() != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
