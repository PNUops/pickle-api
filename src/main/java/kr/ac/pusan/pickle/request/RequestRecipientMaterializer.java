package kr.ac.pusan.pickle.request;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.provisioning.VmCloneReservationService;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes the resources of approved many-person requests, one recipient at a
 * time.
 *
 * <p>Each recipient gets its own transaction, so one that fails rolls back
 * alone and is marked FAILED in a second one, while the rest carry on. Every
 * such transaction first takes one transaction-scoped advisory lock: two runs
 * (the minute schedule and a trigger after an approval) would otherwise both
 * count the VMs in creation, both see room, and together start more than the
 * setting allows. The lock is always the first thing taken and nothing else
 * takes it, so it adds no ordering between the rest.</p>
 *
 * <p>After it: the workspace row, then the recipient row, then whatever the
 * resource creation locks (the gateway generation row for a key, the image
 * revision and node rows for a VM). Workspace before recipient is the order a
 * workspace deletion uses; recipient before generation is the order an
 * approval uses for its own recipients, and the two never meet on the same
 * recipient rows anyway, because this only touches requests already committed
 * as APPROVED.</p>
 *
 * <p>VMs are limited per node: a VM is placed only on a node where fewer of
 * this path's VMs are still CREATING than {@value #CONCURRENCY_SETTING}
 * allows (default {@value #DEFAULT_CONCURRENCY}), because each one is a full
 * clone and the node's disk is the bottleneck. Nodes at the limit are handed
 * to placement as exclusions, so a recipient that some other node can take
 * goes there, and one that only a node at the limit could take stays QUEUED
 * for a later run rather than failing. GPU nodes are still placement's last
 * choice, but once the nodes ahead of them are at the limit a VM goes to a GPU
 * node rather than waiting. Within one run, a request whose recipient had to
 * wait has its other recipients skipped, since they would wait on the same
 * nodes. What remains is head-of-line blocking at the scan cap: a run looks at
 * the oldest {@code SCAN_LIMIT} queued recipients only, so while that many
 * are waiting on busy nodes, a newer request that another node could take
 * waits for them to drain. Only this path's VMs are counted: a
 * single-person approval clones on its own and neither counts nor waits.
 * Keys cost only a database write each, so they are capped per run
 * instead.</p>
 */
@Component
public class RequestRecipientMaterializer {

    private static final Logger log = LoggerFactory.getLogger(RequestRecipientMaterializer.class);

    public static final String JOB_ID = "request-recipient-materializer";
    static final String CONCURRENCY_SETTING = SettingsService.BULK_PROVISION_CONCURRENCY;
    static final int DEFAULT_CONCURRENCY = 4;
    static final int LLM_KEYS_PER_RUN = 20;
    /** How many queued rows one run looks at. */
    private static final int SCAN_LIMIT = 500;
    /** Arbitrary but fixed key for the run-wide advisory lock. */
    private static final long ADVISORY_LOCK_KEY = 0x5049434b52435054L;

    static final String REASON_WORKSPACE_GONE = "워크스페이스가 삭제되어 만들지 않았습니다.";
    static final String REASON_EXPIRED = "사용 기간이 이미 끝나 만들지 않았습니다.";
    static final String REASON_FAILED = "리소스를 만들지 못했습니다. 관리자가 다시 시도할 수 있습니다.";

    /**
     * AT_CAPACITY: every active node is at the VM limit, so no VM recipient
     * can start this run. WAITING: this recipient's nodes are at the limit,
     * but another recipient's may not be.
     */
    enum Outcome { CREATED, SKIPPED, AT_CAPACITY, WAITING, FAILED, NOT_APPLICABLE }

    private final RequestRecipientRepository recipientRepository;
    private final RequestRepository requestRepository;
    private final RequestReviewRepository reviewRepository;
    private final Map<ResourceType, RequestTypeHandler> handlers;
    private final ResourceAccessGrantRepository grantRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final JobScheduler jobScheduler;
    private final Clock clock;

    public RequestRecipientMaterializer(RequestRecipientRepository recipientRepository,
            RequestRepository requestRepository, RequestReviewRepository reviewRepository,
            List<RequestTypeHandler> handlers, ResourceAccessGrantRepository grantRepository,
            WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository,
            UserRepository userRepository, SettingsService settingsService,
            NotificationService notificationService, AuditService auditService,
            JdbcTemplate jdbcTemplate, TransactionTemplate tx, JobScheduler jobScheduler, Clock clock) {
        this.recipientRepository = recipientRepository;
        this.requestRepository = requestRepository;
        this.reviewRepository = reviewRepository;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(RequestTypeHandler::type, Function.identity()));
        this.grantRepository = grantRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.jobScheduler = jobScheduler;
        this.clock = clock;
    }

    private RequestTypeHandler handler(ResourceType type) {
        return handlers.get(type);
    }

    /**
     * Asks for a run once the current transaction commits. Enqueue failures
     * are swallowed: the minute schedule is the retry, and an approval that
     * already committed must not answer 500 because of it.
     */
    public void triggerAfterCommit() {
        Runnable enqueue = () -> {
            try {
                jobScheduler.<RequestRecipientMaterializer>enqueue(materializer -> materializer.run());
            } catch (RuntimeException e) {
                log.warn("could not enqueue a recipient materializer run; the schedule will pick it up", e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueue.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueue.run();
            }
        });
    }

    @Recurring(id = JOB_ID, interval = "PT1M")
    @Job(name = JOB_ID, retries = 0)
    public void run() {
        List<Map<String, Object>> queued = jdbcTemplate.queryForList("""
                select rr.id, rr.request_id, r.resource_type
                  from request_recipients rr
                  join requests r on r.id = rr.request_id
                 where rr.status = 'QUEUED' and r.status = 'APPROVED'
                 order by rr.id
                 limit ?
                """, SCAN_LIMIT);
        boolean vmFull = false;
        // Requests whose recipient had to wait for a node. Every recipient of a
        // request asks for the same image, size and forced node, and the nodes
        // at the limit only grow within a run, so the rest of that request
        // would wait too: they are not tried again until the next run.
        Set<Long> waitingRequests = new HashSet<>();
        int keys = 0;
        for (Map<String, Object> row : queued) {
            long id = ((Number) row.get("id")).longValue();
            long requestId = ((Number) row.get("request_id")).longValue();
            ResourceType type = ResourceType.valueOf(String.valueOf(row.get("resource_type")));
            if (type == ResourceType.VM) {
                if (vmFull || waitingRequests.contains(requestId)) {
                    continue;
                }
                Outcome outcome = processOne(id);
                if (outcome == Outcome.AT_CAPACITY) {
                    vmFull = true;
                } else if (outcome == Outcome.WAITING) {
                    waitingRequests.add(requestId);
                }
            } else if (type == ResourceType.LLM_API_KEY) {
                if (keys >= LLM_KEYS_PER_RUN) {
                    continue;
                }
                Outcome outcome = processOne(id);
                if (outcome == Outcome.CREATED || outcome == Outcome.FAILED) {
                    keys++;
                }
            }
            if (vmFull && keys >= LLM_KEYS_PER_RUN) {
                break;
            }
        }
    }

    /** One recipient: create in one transaction, or record the failure in another. */
    Outcome processOne(long recipientId) {
        try {
            Outcome outcome = tx.execute(status -> createOne(recipientId));
            return outcome == null ? Outcome.NOT_APPLICABLE : outcome;
        } catch (VmCloneReservationService.ExcludedNodesOnlyException atLimit) {
            // Only the per-node limit stood in the way: nothing was written,
            // and the recipient stays QUEUED for the next run.
            return Outcome.WAITING;
        } catch (RuntimeException e) {
            log.warn("request recipient {}: creation failed", recipientId, e);
            String reason = e instanceof VmCloneReservationService.NoCapacityException
                    && e.getMessage() != null ? e.getMessage() : REASON_FAILED;
            tx.executeWithoutResult(status -> {
                lock();
                recipientRepository.findWithLockById(recipientId)
                        .filter(recipient -> recipient.getStatus() == RequestRecipientStatus.QUEUED)
                        .ifPresent(recipient -> recipient.failed(reason));
            });
            return Outcome.FAILED;
        }
    }

    private Outcome createOne(long recipientId) {
        lock();
        // The workspace row before the recipient row, the order a workspace
        // deletion takes them in (workspace, then that workspace's recipients).
        // Holding it until commit is what lets the deletion's count of live
        // resources see this one, or this one see the deletion: without it the
        // count runs under read committed while this insert is still invisible.
        Long workspaceId = jdbcTemplate.query("""
                select r.workspace_id from request_recipients rr join requests r on r.id = rr.request_id
                 where rr.id = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, recipientId);
        if (workspaceId == null) {
            return Outcome.NOT_APPLICABLE;
        }
        boolean workspaceLive = workspaceRepository.findByIdForUpdate(workspaceId)
                .map(workspace -> workspace.getDeletedAt() == null).orElse(false);
        RequestRecipient recipient = recipientRepository.findWithLockById(recipientId).orElse(null);
        if (recipient == null || recipient.getStatus() != RequestRecipientStatus.QUEUED) {
            return Outcome.NOT_APPLICABLE;
        }
        Request request = requestRepository.findById(recipient.getRequestId()).orElseThrow();
        if (request.getStatus() != RequestStatus.APPROVED) {
            return Outcome.NOT_APPLICABLE;
        }
        RequestTypeHandler handler = handler(request.getResourceType());
        if (handler == null || !handler.supportsRecipients()) {
            throw new IllegalStateException("request " + request.getId() + " cannot make resources per recipient");
        }
        if (!workspaceLive) {
            recipient.mark(RequestRecipientStatus.SKIPPED_INELIGIBLE, REASON_WORKSPACE_GONE);
            return Outcome.SKIPPED;
        }
        Long userId = recipient.getUserId();
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE
                || workspaceMemberRepository.findByWorkspaceIdAndUserId(request.getWorkspaceId(), userId).isEmpty()) {
            recipient.mark(RequestRecipientStatus.SKIPPED_INELIGIBLE, RequestRecipientService.REASON_NOT_MEMBER);
            return Outcome.SKIPPED;
        }
        RequestReview review = reviewRepository.findByRequestId(request.getId()).orElseThrow();
        LocalDate end = review.getGrantedEndDate();
        if (end != null && end.isBefore(ClockConfig.todayKst(clock))) {
            recipient.mark(RequestRecipientStatus.SKIPPED_EXPIRED, REASON_EXPIRED);
            return Outcome.SKIPPED;
        }
        Set<Long> nodesAtLimit = Set.of();
        if (request.getResourceType() == ResourceType.VM) {
            nodesAtLimit = nodesAtLimit();
            // With no active node at all there is nothing to wait for: placement
            // fails with no capacity, as it does when nothing is in creation.
            Set<Long> active = activeNodeIds();
            if (!active.isEmpty() && nodesAtLimit.containsAll(active)) {
                return Outcome.AT_CAPACITY;
            }
        }

        RequestTypeHandler.Materialized created = handler.createFor(request, review, userId, nodesAtLimit);
        // The resource belongs to the recipient and to nobody else, the same
        // first grant a single approval gives its requester.
        grantRepository.save(ResourceAccessGrant.forUser(request.getResourceType(),
                created.resourceId(), userId, ResourceRole.OWNER));
        recipient.created(created.resourceId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                created.afterCommit().run();
            }
        });

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("type", request.getResourceType().name());
        auditArgs.put("recipientId", recipient.getPublicId());
        auditArgs.put("ownerId", user.getPublicId());
        auditArgs.putAll(created.auditArgs());
        auditService.recordAfterCommit(null, AuditService.ACTOR_ROLE_SYSTEM,
                AuditService.REQUEST_RECIPIENT_CREATE, "request", request.getPublicId(), auditArgs, null);
        // A VM tells its owner when provisioning finishes (the creation notice
        // goes to the VM's owners). A key has no such later moment: it exists
        // now and waits for its owner to issue the secret, which is what the
        // approval notice for a key says.
        if (request.getResourceType() == ResourceType.LLM_API_KEY) {
            Map<String, Object> notifyArgs = new LinkedHashMap<>();
            notifyArgs.put("requestId", request.getPublicId());
            notifyArgs.put("type", request.getResourceType().name());
            notifyArgs.put("resourceName", created.resourceName());
            notifyArgs.putAll(created.notificationArgs());
            notificationService.publish(userId, NotificationEvent.REQUEST_APPROVED, notifyArgs, null);
        }
        return Outcome.CREATED;
    }

    private void lock() {
        jdbcTemplate.queryForObject("select pg_advisory_xact_lock(?)::text", String.class, ADVISORY_LOCK_KEY);
    }

    /** Nodes where this path already has as many VMs CREATING as the limit allows. */
    private Set<Long> nodesAtLimit() {
        return Set.copyOf(jdbcTemplate.queryForList("""
                select v.node_id
                  from request_recipients rr
                  join requests r on r.id = rr.request_id
                  join vms v on v.id = rr.resource_id
                 where rr.status = 'CREATED' and r.resource_type = 'VM'
                   and v.status = 'CREATING' and v.deleted_at is null
                 group by v.node_id
                having count(*) >= ?
                """, Long.class, concurrency()));
    }

    private Set<Long> activeNodeIds() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select id from nodes where status = 'ACTIVE'", Long.class));
    }

    private int concurrency() {
        return Math.max(1, settingsService.integer(CONCURRENCY_SETTING, DEFAULT_CONCURRENCY));
    }
}
