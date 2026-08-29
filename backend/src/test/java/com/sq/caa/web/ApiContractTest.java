package com.sq.caa.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sq.caa.domain.AppUser;
import com.sq.caa.domain.UserRole;
import com.sq.caa.repository.AppUserRepository;
import com.sq.caa.security.AppUserPrincipal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end check of the wiring between the three parallel workstreams: the
 * filter chain, the error contract and the controllers.
 *
 * <p>Deliberately independent of seed data - every test either creates the row
 * it needs inside a rolled-back transaction or asserts behaviour that holds for
 * an empty database. The suite is therefore green both before and after
 * {@code V3__seed.sql} lands.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    private static final String PASSWORD = "contract-test-password";
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JsonMapper jsonMapper;

    private AppUser persistUser(UserRole role) {
        AppUser user = AppUser.builder()
                .userId(UUID.randomUUID())
                .username("contract_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Contract Test " + role.name())
                .role(role)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        return userRepository.saveAndFlush(user);
    }

    private static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String signIn(AppUser user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getUsername(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body).get("token").asString();
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @Transactional
        @DisplayName("issues a token that the filter chain accepts")
        void signsIn() throws Exception {
            AppUser user = persistUser(UserRole.ADMIN);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(user.getUsername(), PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                    .andExpect(jsonPath("$.user.username").value(user.getUsername()))
                    .andExpect(jsonPath("$.user.fullName").value(user.getFullName()))
                    .andExpect(jsonPath("$.user.role").value("ADMIN"));

            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + signIn(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(user.getUsername()))
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        @Transactional
        @DisplayName("rejects a wrong password as 401 problem+json")
        void wrongPassword() throws Exception {
            AppUser user = persistUser(UserRole.OPERATOR);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(user.getUsername(), "not-the-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.detail").value("Invalid username or password."));
        }

        @Test
        @Transactional
        @DisplayName("refuses a disabled account")
        void disabledAccount() throws Exception {
            AppUser user = persistUser(UserRole.OPERATOR);
            user.setEnabled(false);
            userRepository.saveAndFlush(user);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(user.getUsername(), PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        }

        @Test
        @DisplayName("rejects an unknown user as 401, never 500")
        void unknownUser() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("nobody-" + UUID.randomUUID(), "whatever")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.title").value("Unauthorized"))
                    .andExpect(jsonPath("$.instance").value("/api/auth/login"));
        }

        @Test
        @DisplayName("reports bean-validation failures as field errors")
        void blankCredentials() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("", "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.errors.username").isArray())
                    .andExpect(jsonPath("$.errors.password").isArray());
        }
    }

    @Nested
    @DisplayName("Filter chain")
    class FilterChainRules {

        @Test
        @DisplayName("an anonymous call to a protected route is 401 problem+json")
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/customers"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("an unparseable bearer token is 401, not 500")
        void garbageTokenIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/customers").header("Authorization", "Bearer not-a-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("The authentication token is not valid."));
        }

        @Test
        @DisplayName("the health probe stays public")
        void healthIsPublic() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("CORS pre-flight from the Vite dev server needs no token")
        void preflightIsAllowed() throws Exception {
            mockMvc.perform(options("/api/customers")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }

        @Test
        @Transactional
        @DisplayName("the SSE stream accepts the token as a query parameter")
        void streamAcceptsQueryParameterToken() throws Exception {
            String token = signIn(persistUser(UserRole.OPERATOR));

            // The analyses endpoint belongs to the agent workstream; whatever it
            // answers, authentication must already have happened by then.
            int authorized = mockMvc.perform(get("/api/analyses/{id}/stream", UUID.randomUUID())
                            .param("token", token))
                    .andReturn().getResponse().getStatus();
            assertNotEquals(401, authorized, "a query-parameter token must authenticate the SSE endpoint");

            mockMvc.perform(get("/api/analyses/{id}/stream", UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Role enforcement")
    class RoleEnforcement {

        @Test
        @WithMockUser(roles = "OPERATOR")
        @DisplayName("operators cannot list users")
        void operatorCannotListUsers() throws Exception {
            mockMvc.perform(get("/api/users"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @Transactional
        @DisplayName("admins can list users, and hashes never leave the server")
        void adminCanListUsers() throws Exception {
            AppUser admin = persistUser(UserRole.ADMIN);

            mockMvc.perform(get("/api/users").with(user(AppUserPrincipal.from(admin))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.username=='" + admin.getUsername() + "')].role")
                            .value("ADMIN"))
                    .andExpect(jsonPath("$..passwordHash").isEmpty());
        }

        @Test
        @WithMockUser(roles = "OPERATOR")
        @DisplayName("operators may read the rule set")
        void operatorCanReadRules() throws Exception {
            mockMvc.perform(get("/api/rules"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        @WithMockUser(roles = "OPERATOR")
        @DisplayName("operators cannot author rules")
        void operatorCannotWriteRules() throws Exception {
            mockMvc.perform(post("/api/rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ruleName\":\"nope\",\"appliesTo\":\"ALL\",\"weight\":5,"
                                    + "\"thresholdLogic\":\"Any payment above 10,000 sent by wire.\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("the field catalog is the rule editor's reference, not a grammar")
        void adminCanReadFieldCatalog() throws Exception {
            mockMvc.perform(get("/api/rules/field-catalog"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].field").value("amount"))
                    .andExpect(jsonPath("$[0].label").isString())
                    .andExpect(jsonPath("$[0].type").isString())
                    .andExpect(jsonPath("$[0].category").isString())
                    .andExpect(jsonPath("$[0].description").isString())
                    .andExpect(jsonPath("$[0].options").isArray())
                    // A grammar is exactly what the catalog no longer is: conditions are prose, so
                    // no operator list is published for an author to build an expression from.
                    .andExpect(jsonPath("$[0].operators").doesNotExist());
        }

        @Test
        @WithMockUser(roles = "OPERATOR")
        @DisplayName("the field catalog is closed to operators")
        void operatorCannotReadFieldCatalog() throws Exception {
            mockMvc.perform(get("/api/rules/field-catalog"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Error contract")
    class ErrorContract {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("an unknown customer is 404 problem+json")
        void unknownCustomer() throws Exception {
            mockMvc.perform(get("/api/customers/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("a malformed UUID is 400 problem+json")
        void malformedUuid() throws Exception {
            mockMvc.perform(get("/api/customers/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("paging metadata is present even on an empty result")
        void pagedEnvelope() throws Exception {
            mockMvc.perform(get("/api/customers").param("query", "zzz-no-such-customer-zzz"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").isNumber())
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andExpect(jsonPath("$.totalPages").isNumber());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("a rule condition pasted as the old JSON DSL is 400 and names the field")
        void ruleConditionPastedAsJsonDsl() throws Exception {
            mockMvc.perform(post("/api/rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ruleName\":\"Pasted DSL\",\"appliesTo\":\"ALL\","
                                    + "\"weight\":5,\"thresholdLogic\":\"{\\\"op\\\":\\\"AND\\\","
                                    + "\\\"conditions\\\":[{\\\"field\\\":\\\"amount\\\",\\\"operator\\\":"
                                    + "\\\"GT\\\",\\\"value\\\":1000}]}\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.field").value("thresholdLogic"))
                    .andExpect(jsonPath("$.detail").value(containsString("plain English")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("a rule condition too short to judge is 400 and names the field")
        void ruleConditionTooShort() throws Exception {
            mockMvc.perform(post("/api/rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ruleName\":\"Too terse\",\"appliesTo\":\"ALL\","
                                    + "\"weight\":5,\"thresholdLogic\":\"big payments\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.status").value(400));
        }
    }
}
