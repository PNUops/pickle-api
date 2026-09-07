package kr.ac.pusan.pickle.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.LlmUsageRollupService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The administrative usage surfaces added beside the holder's: one key, one
 * business account, and the platform-wide breakdown.
 *
 * <p>Most of what is asserted here is the difference between a missing number
 * and a zero one. Every defect this area has produced took the same shape: a
 * self-hosted request has no price at all, and an amount that reports it as
 * zero reads as "this was free" while ranking the consumer beside one that
 * genuinely spent nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminLlmUsageBreakdownTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private LlmUsageRollupService rollupService;

    private Org orgA;
    private Org orgB;
    private User owner;
    private String sysToken;
    private String orgBAdminToken;
    private long accountA;
    private UUID accountAPublicId;
    private long accountB;
    private UUID accountBPublicId;
    private UUID workspaceAPublicId;
    private Key keyOne;
    private Key keyTwo;
    private Key keyOther;
    private String paidModel;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        jdbcTemplate.update("delete from llm_usage_events");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        paidModel = "openai/paid-" + suffix;
        orgA = orgRepository.save(new Org("분해 기관 A " + suffix, null));
        orgB = orgRepository.save(new Org("분해 기관 B " + suffix, null));
        owner = user("breakdown-owner-" + suffix + "@pusan.ac.kr", UserRole.USER, null);
        sysToken = jwtService.createAccessToken(
                user("breakdown-sys-" + suffix + "@pusan.ac.kr", UserRole.SYS_ADMIN, null));
        orgBAdminToken = jwtService.createAccessToken(
                user("breakdown-orgb-" + suffix + "@pusan.ac.kr", UserRole.ORG_ADMIN,
                        orgB.getId()));

        Workspace workspaceA = workspaceRepository.save(
                new Workspace(WorkspaceKind.TEAM, "분해 A " + suffix, null));
        Workspace workspaceB = workspaceRepository.save(
                new Workspace(WorkspaceKind.TEAM, "분해 B " + suffix, null));
        accountA = account(orgA, "분해 사업 A " + suffix);
        accountAPublicId = SeedFixtures.publicId(jdbcTemplate, "openrouter_accounts", accountA);
        accountB = account(orgB, "분해 사업 B " + suffix);
        accountBPublicId = SeedFixtures.publicId(jdbcTemplate, "openrouter_accounts", accountB);
        workspaceAPublicId = workspaceA.getPublicId();
        keyOne = key(orgA, workspaceA, "분해 키 하나 " + suffix, accountA, "[\"images\"]");
        keyTwo = key(orgA, workspaceA, "분해 키 둘 " + suffix, accountA, "[]");
        keyOther = key(orgB, workspaceB, "분해 남의 키 " + suffix, accountB, "[\"images\"]");
    }

    @Test
    void aSelfHostedOnlyWindowReportsNoAmountRatherThanZero() throws Exception {
        // The defect this exists for. A self-hosted request has no dollar
        // figure at all, so a zero here would say the window was free.
        event(keyOne.id(), "pickle-general", "chat", null, 10, 20, hoursAgo(2));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend.models[0].attributedCostUsd").doesNotExist())
                .andExpect(jsonPath("$.trend.models[0].pricedRequests").value(0))
                .andExpect(jsonPath("$.trend.models[0].requests").value(1))
                .andExpect(jsonPath("$.costPoints[-1:].attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void aPricedRequestCarriesItsAmountAndItsCount() throws Exception {
        // The paid one carries the axis, the other does not: the key surface
        // reads raw events rather than the rollup, so its own filter has to be
        // asserted or a constant would pass.
        axisEvent(keyOne.id(), paidModel, "images", "0.24200000", "CREDIT", 7, 2, 9, hoursAgo(2));
        event(keyOne.id(), paidModel, "images", null, 3, 1, hoursAgo(3));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                // The catalogue has no row for a passed-through model, so a
                // join to decorate the name would drop exactly the traffic
                // that has a price on it.
                .andExpect(jsonPath("$.trend.models[0].modelName").value(paidModel))
                .andExpect(jsonPath("$.trend.models[0].requests").value(2))
                .andExpect(jsonPath("$.trend.models[0].pricedRequests").value(1))
                .andExpect(jsonPath("$.trend.models[0].attributedCostUsd").value(0.242))
                // The key screen draws this model as two rows, so it needs the
                // same five the platform breakdown carries. A model must not
                // read differently depending on which screen you open.
                .andExpect(jsonPath("$.trend.models[0].inputTokens").value(10))
                .andExpect(jsonPath("$.trend.models[0].pricedInputTokens").value(7))
                .andExpect(jsonPath("$.trend.models[0].pricedOutputTokens").value(2))
                .andExpect(jsonPath("$.trend.models[0].pricedAvgLatencyMs").value(9))
                .andExpect(jsonPath("$.trend.models[0].unpricedAvgLatencyMs").value(1))
                .andExpect(jsonPath("$.trend.models[0].pricedFailed").value(0))
                .andExpect(jsonPath("$.endpointKinds[0].endpoint").value("images"))
                .andExpect(jsonPath("$.endpointKinds[0].requests").value(2))
                .andExpect(jsonPath("$.endpointKinds[0].pricedRequests").value(1))
                // One of the two, not zero and not both. This surface fills the
                // same schema as the platform one from a different source.
                .andExpect(jsonPath("$.endpointKinds[0].creditAxisRequests").value(1));
    }

    @Test
    void aFallbackAppearsAndAMatchingAnswerDoesNot() throws Exception {
        // The gateway writes the served name only when it differs from the
        // model it sent, so presence is the finding. Nothing on this side
        // compares it against the public name, which is deliberately not the
        // upstream one.
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        jdbcTemplate.update("""
                update llm_usage_events set served_model_name = 'other-upstream'
                 where key_id = ?
                """, keyOne.id());
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(3));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servedModels.length()").value(1))
                .andExpect(jsonPath("$.servedModels[0].servedModelName").value("other-upstream"))
                .andExpect(jsonPath("$.servedModels[0].requests").value(1));
    }

    @Test
    void aRouteRecordedBeforeTheColumnExistedIsItsOwnBucketRatherThanOther() throws Exception {
        event(keyOne.id(), "pickle-general", null, null, 1, 1, hoursAgo(2));
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(3));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpointKinds.length()").value(2))
                .andExpect(jsonPath("$.endpointKinds[?(@.endpoint == 'chat')].requests")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.endpointKinds[?(@.endpoint == null)].requests")
                        .value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void aKeyOutsideTheReadersInstitutionsIsAnsweredAsMissing() throws Exception {
        // The 404 alone proves nothing: a scope check that refuses everything
        // passes it too. The pair is the assertion — the same reader reaches
        // its own institution's key and not the other one.
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        event(keyOther.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        rollupService.refresh();

        adminKeyUsage(orgBAdminToken, keyOther.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend.points").isArray());
        adminKeyUsage(orgBAdminToken, keyOne.publicId()).andExpect(status().isNotFound());
    }

    @Test
    void anAccountOutsideTheReadersInstitutionsIsAnsweredAsMissing() throws Exception {
        accountUsage(orgBAdminToken, accountBPublicId, 7).andExpect(status().isOk());
        accountUsage(orgBAdminToken, accountAPublicId, 7).andExpect(status().isNotFound());
    }

    @Test
    void theBreakdownCountsOnlyWhatTheReadersInstitutionsCover() throws Exception {
        // Traffic in both institutions, read by someone who holds one of them.
        event(keyOne.id(), paidModel, "images", "0.10000000", 1, 1, hoursAgo(2));
        event(keyOther.id(), paidModel, "images", "9.00000000", 1, 1, hoursAgo(2));
        rollupService.refresh();

        platformUsage(orgBAdminToken, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quality.totalRequests").value(1))
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].grantedKeys")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].requests")
                        .value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void anAccountSeriesKeepsEveryDayEvenWhenAnotherAccountHasTrafficThatDay()
            throws Exception {
        // The join has to carry the account filter into it. Filtering after a
        // plain join to the rollup drops the whole day whenever some other
        // account is the only one with a bucket on it, and the gap then reads
        // as "no data reached us".
        event(keyOther.id(), paidModel, "images", "1.00000000", 1, 1, hoursAgo(2));
        rollupService.refresh();

        accountUsage(sysToken, accountAPublicId, 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(7))
                .andExpect(jsonPath("$.requests").value(0))
                .andExpect(jsonPath("$.attributedCostUsd").doesNotExist())
                .andExpect(jsonPath("$.keysUsed").value(0))
                .andExpect(jsonPath("$.keysLinked").value(2));
    }

    @Test
    void anAccountAttributesOnlyItsOwnKeysAndSaysHowManyWerePriced() throws Exception {
        event(keyOne.id(), paidModel, "images", "0.50000000", 2, 2, hoursAgo(2));
        event(keyTwo.id(), "pickle-general", "chat", null, 3, 3, hoursAgo(2));
        event(keyOther.id(), paidModel, "images", "9.00000000", 1, 1, hoursAgo(2));
        rollupService.refresh();

        accountUsage(sysToken, accountAPublicId, 7)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").value(2))
                .andExpect(jsonPath("$.pricedRequests").value(1))
                .andExpect(jsonPath("$.attributedCostUsd").value(0.5))
                .andExpect(jsonPath("$.keysUsed").value(2))
                .andExpect(jsonPath("$.keys.length()").value(2))
                // The unpriced key carries no amount rather than a zero one.
                .andExpect(jsonPath("$.keys[?(@.keyName =~ /.*둘.*/)].attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void theBreakdownCountsGrantedKeysApartFromKeysThatUsedTheCapability() throws Exception {
        // One capability opens two routes, and the count has to follow both.
        event(keyOne.id(), paidModel, "images", "0.10000000", 1, 1, hoursAgo(2));
        event(keyOne.id(), paidModel, "images_models", null, 0, 0, hoursAgo(3));
        rollupService.refresh();

        platformUsage(sysToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.passthroughGrants.length()").value(2))
                // orgB도 이 기능을 가진 키를 하나 갖고 있다. 스코프가 빠지면 이 수가
                // 곧바로 달라지므로, 이 단언이 스코프를 실제로 지킨다.
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].grantedKeys")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].usedKeys")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'images')].requests")
                        .value(org.hamcrest.Matchers.contains(2)))
                // Granted to nobody and used by nobody is a zero row, not an
                // absent one: an absent row reads as "no data".
                .andExpect(jsonPath(
                        "$.breakdown.passthroughGrants[?(@.capability == 'embeddings')]"
                        + ".grantedKeys")
                        .value(org.hamcrest.Matchers.contains(0)));
    }

    @Test
    void theConsumerRowsCarryAnAmountOnlyWhereSomethingWasPriced() throws Exception {
        event(keyOne.id(), paidModel, "images", "0.75000000", 1, 1, hoursAgo(2));
        event(keyTwo.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        rollupService.refresh();

        // Drill to the key level so each consumer row is one key. At the
        // institution level both keys fold into one row and the unpriced half
        // stops being visible, which is how this test previously asserted
        // nothing at all about the consumers it is named for.
        platformUsage(sysToken, "orgId=" + orgA.getPublicId()
                        + "&workspaceId=" + workspaceAPublicId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("KEY"))
                .andExpect(jsonPath("$.consumers.items.length()").value(2))
                .andExpect(jsonPath("$.consumers.items[?(@.keyName =~ /.*하나.*/)]"
                        + ".attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(0.75)))
                .andExpect(jsonPath("$.consumers.items[?(@.keyName =~ /.*둘.*/)]"
                        + ".attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.consumers.items[?(@.keyName =~ /.*둘.*/)].pricedRequests")
                        .value(org.hamcrest.Matchers.contains(0)));

        platformUsage(sysToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quality.pricedRequests").value(1))
                .andExpect(jsonPath("$.quality.endpointRecordedRequests").value(2))
                .andExpect(jsonPath("$.breakdown.models.length()").value(2))
                .andExpect(jsonPath(
                        "$.breakdown.models[?(@.modelName == '%s')].attributedCostUsd"
                                .formatted(paidModel))
                        .value(org.hamcrest.Matchers.contains(0.75)))
                .andExpect(jsonPath(
                        "$.breakdown.models[?(@.modelName == 'pickle-general')]"
                        + ".attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.breakdown.endpointKinds.length()").value(2))
                .andExpect(jsonPath("$.breakdown.endpointKinds[?(@.endpoint == 'images')]"
                        + ".attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(0.75)))
                .andExpect(jsonPath("$.breakdown.endpointKinds[?(@.endpoint == 'chat')]"
                        + ".attributedCostUsd")
                        .value(org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void theUnpricedCountIsMeasuredAgainstPaidTrafficRatherThanEveryRequest()
            throws Exception {
        // A screen that wants to say "the amount covers all but N of these"
        // cannot subtract from the request count: a self-hosted request has no
        // dollar figure to miss, and a refusal never reached the vendor. Only
        // the paid axis could have carried an amount, so only it is the
        // denominator, and it travels on the row so the screen never has to
        // guess.
        // The priced request is the slow one, so a latency that did not split
        // would show the same number on both rows. Input and output differ so a
        // swapped pair of token sums is visible rather than symmetric.
        axisEvent(keyOne.id(), paidModel, "chat", "0.75000000", "CREDIT", 7, 3, 5, hoursAgo(2));
        axisEvent(keyOne.id(), paidModel, "chat", null, "CREDIT", 2, 1, 1, hoursAgo(2));
        // A refusal on the same model, so the model's request count is larger
        // than its paid-axis count. Without it the two candidate denominators
        // for the unpriced side are the same number and no assertion can say
        // which one the code used.
        refusedEvent(keyOne.id(), paidModel, "chat", hoursAgo(2));
        axisEvent(keyOne.id(), "pickle-general", "chat", null, "TOKEN", 1, 1, 1, hoursAgo(2));
        axisEvent(keyOne.id(), "pickle-general", "chat", null, "TOKEN", 1, 1, 1, hoursAgo(2));
        axisEvent(keyOne.id(), "pickle-general", "chat", null, "TOKEN", 1, 1, 1, hoursAgo(2));
        rollupService.refresh();

        platformUsage(sysToken, "orgId=" + orgA.getPublicId()
                        + "&workspaceId=" + workspaceAPublicId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.level").value("KEY"))
                .andExpect(jsonPath("$.consumers.items[?(@.keyName =~ /.*하나.*/)].requests")
                        .value(org.hamcrest.Matchers.contains(6)))
                .andExpect(jsonPath("$.consumers.items[?(@.keyName =~ /.*하나.*/)].pricedRequests")
                        .value(org.hamcrest.Matchers.contains(1)))
                // Two, not six: three were self-hosted and one was refused
                // before it reached a provider.
                .andExpect(jsonPath("$.consumers.items[?(@.keyName =~ /.*하나.*/)]"
                        + ".creditAxisRequests")
                        .value(org.hamcrest.Matchers.contains(2)));

        platformUsage(sysToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".creditAxisRequests")
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == 'pickle-general')]"
                        + ".creditAxisRequests")
                        .value(org.hamcrest.Matchers.contains(0)))
                // The tokens split with the amount. Without this the screen can
                // say how many requests went unpriced but not what volume they
                // carried, and the known rate has nothing to be applied to.
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".inputTokens")
                        .value(org.hamcrest.Matchers.contains(9)))
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".outputTokens")
                        .value(org.hamcrest.Matchers.contains(4)))
                // Seven and three, not three and seven: a swapped pair of sums
                // is only visible because the fixture is asymmetric.
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".pricedInputTokens")
                        .value(org.hamcrest.Matchers.contains(7)))
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".pricedOutputTokens")
                        .value(org.hamcrest.Matchers.contains(3)))
                // A self-hosted model has no priced side at all, so both are
                // zero and the screen leaves the row whole.
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == 'pickle-general')]"
                        + ".pricedInputTokens")
                        .value(org.hamcrest.Matchers.contains(0)))
                // The screen draws the two parts as two whole rows, so the
                // response time has to come apart with them. One number
                // repeated would have two rows with different request counts
                // claiming the same latency.
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".pricedAvgLatencyMs")
                        .value(org.hamcrest.Matchers.contains(5)))
                // (7 - 5) / (3 - 1). Dividing by the paid-axis remainder
                // instead would give (7 - 5) / (2 - 1) = 2, so this number
                // says which partition the row describes.
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".unpricedAvgLatencyMs")
                        .value(org.hamcrest.Matchers.contains(1)))
                // Zero because it is counted, not because it is assumed.
                .andExpect(jsonPath("$.breakdown.models[?(@.modelName == '%s')]"
                                .formatted(paidModel) + ".pricedFailed")
                        .value(org.hamcrest.Matchers.contains(0)))
                // One route carried both axes, so its own count is the only
                // thing that separates them here.
                .andExpect(jsonPath("$.breakdown.endpointKinds[?(@.endpoint == 'chat')].requests")
                        .value(org.hamcrest.Matchers.contains(6)))
                .andExpect(jsonPath("$.breakdown.endpointKinds[?(@.endpoint == 'chat')]"
                        + ".creditAxisRequests")
                        .value(org.hamcrest.Matchers.contains(2)));
    }

    @Test
    void aRateLimitedRefusalIsNotCountedAsAFailureOnEitherSurface() throws Exception {
        // The two surfaces publish one schema, so one field name must not carry
        // two definitions. A refusal is neither succeeded nor failed, and the
        // three partition the requests.
        event(keyOne.id(), "pickle-general", "chat", null, 1, 1, hoursAgo(2));
        refusedEvent(keyOne.id(), "pickle-general", "chat", hoursAgo(3));
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpointKinds[0].requests").value(2))
                .andExpect(jsonPath("$.endpointKinds[0].succeeded").value(1))
                .andExpect(jsonPath("$.endpointKinds[0].rateLimited").value(1))
                .andExpect(jsonPath("$.endpointKinds[0].failed").value(0));

        platformUsage(sysToken, "orgId=" + orgA.getPublicId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.endpointKinds[0].requests").value(2))
                .andExpect(jsonPath("$.breakdown.endpointKinds[0].succeeded").value(1))
                .andExpect(jsonPath("$.breakdown.endpointKinds[0].rateLimited").value(1))
                .andExpect(jsonPath("$.breakdown.endpointKinds[0].failed").value(0));
    }

    @Test
    void theTrendCarriesTheNewPerDayMeasurements() throws Exception {
        event(keyOne.id(), paidModel, "images", "0.20000000", 10, 4, hoursAgo(2));
        jdbcTemplate.update("update llm_usage_events set cached_input_tokens = 3, "
                + "reasoning_tokens = 2, image_count = 2, streamed = true where key_id = ?",
                keyOne.id());
        rollupService.refresh();

        adminKeyUsage(sysToken, keyOne.publicId())
                .andExpect(status().isOk())
                // 부분집합이므로 입출력 합계가 이 값들을 이미 포함한다. 셋을 더한
                // 값이 나오면 어딘가에서 이중 계산을 하고 있다는 뜻이다.
                .andExpect(jsonPath("$.trend.points[-1:].cachedInputTokens")
                        .value(org.hamcrest.Matchers.contains(3)))
                .andExpect(jsonPath("$.trend.points[-1:].reasoningTokens")
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.trend.points[-1:].inputTokens")
                        .value(org.hamcrest.Matchers.contains(10)))
                .andExpect(jsonPath("$.trend.points[-1:].imageCount")
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$.trend.points[-1:].streamedRequests")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.trend.models[0].imageCount").value(2));
    }

    private ResultActions adminKeyUsage(String token, UUID keyId) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/llm/keys/" + keyId + "/usage")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions accountUsage(String token, UUID accountId, int days) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/llm/accounts/" + accountId
                        + "/usage?days=" + days)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions platformUsage(String token, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/llm/usage?" + query)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON));
    }

    private Instant hoursAgo(int hours) {
        LocalDate today = ClockConfig.todayKst(java.time.Clock.systemUTC());
        return today.atTime(12, 0).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()
                .minusSeconds(hours * 3600L);
    }

    private void refusedEvent(long keyId, String model, String endpoint, Instant requestedAt) {
        jdbcTemplate.update("""
                insert into llm_usage_events
                       (event_id, key_id, public_model_name, endpoint, status,
                        input_tokens, output_tokens, estimated, latency_ms, requested_at)
                values (?, ?, ?, ?, 'RATE_LIMITED', 0, 0, false, 1, ?)
                """, UUID.randomUUID().toString(), keyId, model, endpoint,
                Timestamp.from(requestedAt));
    }

    private void event(long keyId, String model, String endpoint, String costUsd,
            int inputTokens, int outputTokens, Instant requestedAt) {
        axisEvent(keyId, model, endpoint, costUsd, null, inputTokens, outputTokens, 1,
                requestedAt);
    }

    /**
     * The same row with the budget axis set. Every other helper here leaves it
     * null, which is what a request recorded before the axis existed looks
     * like, so a test that needs the axis has to say so.
     */
    private void axisEvent(long keyId, String model, String endpoint, String costUsd,
            String budgetAxis, int inputTokens, int outputTokens, int latencyMs,
            Instant requestedAt) {
        jdbcTemplate.update("""
                insert into llm_usage_events
                       (event_id, key_id, public_model_name, endpoint, cost_usd, budget_axis,
                        status, input_tokens, output_tokens, estimated, latency_ms, requested_at)
                values (?, ?, ?, ?, ?::numeric, ?, 'OK', ?, ?, false, ?, ?)
                """, UUID.randomUUID().toString(), keyId, model, endpoint, costUsd, budgetAxis,
                inputTokens, outputTokens, latencyMs, Timestamp.from(requestedAt));
    }

    private long account(Org org, String name) {
        UUID publicId = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?) returning public_id
                """, UUID.class, org.getId(), name, owner.getId());
        return SeedFixtures.internalId(jdbcTemplate, "openrouter_accounts", publicId);
    }

    private Key key(Org org, Workspace workspace, String name, long accountId,
            String passthrough) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '분해 시험', ?)
                returning id
                """, Long.class, workspace.getId(), org.getId(), owner.getId(), name);
        UUID publicId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, token_hash, token_prefix,
                        status, credit_limit, quota_exhausted, openrouter_account_id,
                        passthrough_endpoints, created_by)
                values (?, ?, ?, ?, ?, 'pickle-brk', 'ACTIVE', 10, false, ?, ?::jsonb, ?)
                returning public_id
                """, UUID.class, workspace.getId(), org.getId(), requestId, name,
                UUID.randomUUID().toString(), accountId, passthrough, owner.getId());
        return new Key(SeedFixtures.internalId(jdbcTemplate, "llm_api_keys", publicId),
                publicId);
    }

    private User user(String email, UserRole role, Long orgId) {
        User user = new User(email, "{test-no-login}", email.substring(0, 12));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRole(role);
        User saved = userRepository.save(user);
        if (orgId != null) {
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
        }
        return saved;
    }

    private record Key(long id, UUID publicId) {
    }
}
