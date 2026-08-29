package com.sq.caa.web;

import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.rules.DuplicateRuleNameException;
import com.sq.caa.rules.FieldDefinition;
import com.sq.caa.rules.RuleNotFoundException;
import com.sq.caa.rules.RuleParser;
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
import tools.jackson.databind.JsonNode;

/**
 * Risk rule administration.
 *
 * <p>Reading rules is open to any authenticated user because the analysis screens show them;
 * everything that changes a rule, exposes the field catalog or runs an ad-hoc evaluation is
 * restricted to administrators and enforced here as well as in the URL security rules.
 *
 * <p>Rule-specific failures are translated locally into RFC-7807 responses that name the offending
 * node, which is more useful to a rule author than a generic 400.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private static final Logger log = LoggerFactory.getLogger(RuleController.class);

    private final RiskRuleService riskRuleService;

    public RuleController(RiskRuleService riskRuleService) {
        this.riskRuleService = riskRuleService;
    }

    /** Every rule, name ascending. */
    @GetMapping
    @PreAuthorize(SecurityRoles.IS_OPERATOR_OR_ADMIN)
    public List<RiskRuleDto> list() {
        return riskRuleService.findAll().stream().map(RiskRuleDto::from).toList();
    }

    /** Creates a rule. The logic is validated against the field catalog before it is stored. */
    @PostMapping
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public RiskRuleDto create(@Valid @RequestBody RuleUpsertRequest request) {
        validateAgainstScope(request.thresholdLogic(), request.appliesTo());
        RiskRule rule = riskRuleService.create(request.ruleName(), request.appliesTo(),
                request.thresholdLogic(), request.weight());
        return RiskRuleDto.from(rule);
    }

    /** Replaces a rule. */
    @PutMapping("/{ruleId}")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    public RiskRuleDto update(@PathVariable UUID ruleId, @Valid @RequestBody RuleUpsertRequest request) {
        validateAgainstScope(request.thresholdLogic(), request.appliesTo());
        RiskRule rule = riskRuleService.update(ruleId, request.ruleName(), request.appliesTo(),
                request.thresholdLogic(), request.weight());
        return RiskRuleDto.from(rule);
    }

    /** Deletes a rule and, by cascade, its recorded assessments. */
    @DeleteMapping("/{ruleId}")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ruleId) {
        riskRuleService.delete(ruleId);
    }

    /** The field catalog the visual editor is built from. */
    @GetMapping("/field-catalog")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    public List<FieldCatalogEntry> fieldCatalog() {
        List<FieldDefinition> definitions = riskRuleService.fieldCatalog();
        return definitions.stream().map(FieldCatalogEntry::from).toList();
    }

    /** Runs a draft rule over live activity without saving it. */
    @PostMapping("/test")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    public RuleTestResponse test(@Valid @RequestBody RuleTestRequest request) {
        validateAgainstScope(request.thresholdLogic(), request.appliesTo());
        return RuleTestResponse.from(riskRuleService.testRule(request.thresholdLogic(),
                request.appliesTo(), request.customerId()));
    }

    /**
     * Refuses logic that cannot fire under the scope it is being saved with - a CARD rule reading
     * {@code payment.*}, for instance, which would validate cleanly against the catalog and then
     * evaluate to false on every transaction for ever.
     *
     * <p>Applied on create, replace and "Test rule" so the three give the same verdict. The service
     * re-parses the same document when it canonicalises it for storage; this call is the scope-aware
     * half of that contract and runs before anything is written.
     */
    private static void validateAgainstScope(JsonNode thresholdLogic, RuleScope appliesTo) {
        if (thresholdLogic == null || thresholdLogic.isNull() || thresholdLogic.isMissingNode()) {
            throw new RuleValidationException("$", null, "thresholdLogic is required");
        }
        RuleParser.parseStrict(thresholdLogic, appliesTo == null ? RuleScope.ALL : appliesTo);
    }

    // ------------------------------------------------------------------
    // Problem responses
    // ------------------------------------------------------------------

    /**
     * Malformed rule logic. The response names the offending node in {@code detail} and repeats it
     * as the {@code path} and {@code node} properties so the editor can highlight it.
     */
    @ExceptionHandler(RuleValidationException.class)
    public ResponseEntity<ProblemDetail> onInvalidRule(RuleValidationException e, HttpServletRequest request) {
        log.debug("Rejected rule logic at {}: {}", e.path(), e.getMessage());
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid rule definition", e.describe(), request);
        problem.setProperty("path", e.path());
        if (e.node() != null) {
            problem.setProperty("node", e.node());
        }
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
