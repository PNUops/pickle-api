package kr.ac.pusan.pickle.publishing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.publishing.DomainRecordPolicy.DesiredSet;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The record sets a domain holds, edited as a whole.
 *
 * <p>The edit is a desired state, not a list of operations: the caller sends
 * every set the domain should have, and the difference against what it has is
 * computed here. That is the same discipline the vhost side uses, and it is
 * what makes a retry safe — sending the same desired state twice is one
 * change, not two.</p>
 *
 * <p>Authorization is not here. A domain reaches these methods already
 * decided, because who may edit a name is a question about the resource's
 * access list rather than about its records, and answering it twice in two
 * places is how the two answers drift apart.</p>
 */
@Service
public class DomainRecordsService {

    private final DomainRepository domainRepository;
    private final DomainRecordRepository recordRepository;
    private final DomainRecordPolicy policy;
    private final DomainRecordApplyJob applyJob;
    private final JobScheduler jobScheduler;

    public DomainRecordsService(DomainRepository domainRepository,
            DomainRecordRepository recordRepository, DomainRecordPolicy policy,
            DomainRecordApplyJob applyJob, JobScheduler jobScheduler) {
        this.domainRepository = domainRepository;
        this.recordRepository = recordRepository;
        this.policy = policy;
        this.applyJob = applyJob;
        this.jobScheduler = jobScheduler;
    }

    /** The sets a domain currently claims, in the order they were created. */
    @Transactional(readOnly = true)
    public List<DomainRecord> list(long domainId) {
        return recordRepository.findByDomainIdAndStatusNotOrderByIdAsc(domainId, DomainRecordStatus.REMOVED);
    }

    /**
     * Makes the domain's record sets exactly {@code desired}.
     *
     * <p>Returns the rows as they stand afterwards. The apply is enqueued after
     * commit, so a caller that fails later leaves nothing half-pushed: the zone
     * is only ever told about a state that is already durable.</p>
     */
    @Transactional
    public List<DomainRecord> replace(Domain domain, List<DesiredSet> requested) {
        // Normalized before anything reads it, so the rules and the rows agree
        // about what the value is. They did not: the guards ran on a stripped
        // and folded copy while the raw string was stored and pushed.
        List<DesiredSet> desired = requested.stream().map(DesiredSet::normalized).toList();
        List<FieldValidationError> errors = new ArrayList<>();
        policy.validate(desired, "records", errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        // Locked, because the generation below orders pushes and two edits that
        // read the same value would each believe theirs is the newer intent.
        Domain locked = domainRepository.findByIdForUpdate(domain.getId()).orElseThrow();
        requireExternal(locked);
        requireStillHeld(locked);

        Map<String, DomainRecord> live = new LinkedHashMap<>();
        for (DomainRecord record : recordRepository.findByDomainIdOrderByIdAsc(locked.getId())) {
            if (record.getStatus() != DomainRecordStatus.REMOVED) {
                live.put(key(record.getName(), record.getType().name()), record);
            }
        }

        boolean changed = false;
        for (DesiredSet set : DomainRecordPolicy.ordered(desired)) {
            DomainRecord existing = live.remove(key(set.name(), set.type().name()));
            if (existing == null) {
                recordRepository.save(new DomainRecord(locked.getId(), set.name(), set.type(),
                        set.rrdatas(), set.ttl()));
                changed = true;
            } else if (!existing.getRrdatas().equals(set.rrdatas())
                    || existing.getTtl() != set.ttl()
                    || existing.getStatus() != DomainRecordStatus.APPLIED) {
                // A set already APPLIED and identical is left alone — resending
                // the same desired state must not spend a zone write. One that
                // is FAILED or still PENDING is owed a push even when its
                // values did not move, which is what makes a retry a retry.
                existing.reviseTo(set.rrdatas(), set.ttl());
                changed = true;
            }
        }
        for (DomainRecord gone : live.values()) {
            if (gone.getAppliedAt() == null) {
                // Never reached the zone, so there is nothing to take down. A
                // removal queued for it would retry forever against a set that
                // never existed.
                recordRepository.delete(gone);
            } else {
                gone.markRemovalOwed();
            }
            changed = true;
        }

        if (changed) {
            locked.setRecordsGeneration(locked.getRecordsGeneration() + 1);
            long domainId = locked.getId();
            enqueueAfterCommit(() -> applyJob.apply(domainId));
        }
        return recordRepository.findByDomainIdAndStatusNotOrderByIdAsc(locked.getId(),
                DomainRecordStatus.REMOVED);
    }

    /**
     * Marks every set of a domain for removal, the step a reclaimed name owes
     * the zone before its name is free. Rows that never reached the zone are
     * dropped rather than queued, for the reason {@link #replace} gives.
     */
    @Transactional
    public void removeAll(long domainId) {
        Domain locked = domainRepository.findByIdForUpdate(domainId).orElseThrow();
        requireExternal(locked);
        boolean changed = false;
        for (DomainRecord record : recordRepository.findByDomainIdOrderByIdAsc(domainId)) {
            if (record.getStatus() == DomainRecordStatus.REMOVED) {
                continue;
            }
            if (record.getAppliedAt() == null) {
                recordRepository.delete(record);
            } else {
                record.markRemovalOwed();
            }
            changed = true;
        }
        if (changed) {
            locked.setRecordsGeneration(locked.getRecordsGeneration() + 1);
            enqueueAfterCommit(() -> applyJob.apply(domainId));
        }
    }

    /**
     * Refuses a domain this platform serves itself.
     *
     * <p>Not authorization, which is the caller's: an invariant about the row.
     * The other kinds fail for two different reasons and both are silent
     * rather than loud. A served name's A record is written by the zone
     * reconciler from its route, so a hand-edited set on that name is two
     * writers with opposite intents taking turns, each undoing the other on
     * its next pass. A custom domain is not in a zone this platform operates
     * at all, so the push would go to the wrong zone or nowhere.</p>
     *
     * <p>Named rather than asked through {@link DomainKind}'s answers on
     * purpose: those answers are about serving and about holding a name, and
     * neither is the question here — a kind can be unserved and still not be
     * ours to write.</p>
     *
     * <p>This is the kind half only. Whether the row is still its owner's to
     * edit is a separate question with a separate answer, in
     * {@link #requireStillHeld}.</p>
     */
    /**
     * Refuses an edit to a name its owner has already let go of.
     *
     * <p>A released row is on its way out: its sets are marked for removal and
     * the reclaim waits for the zone to confirm they are gone. A set added
     * after that point is pushed into the zone for a name that is mid-reclaim,
     * and — being owed a write rather than a removal — it makes the reclaim
     * find work forever, so the name is never freed and the sweep says so once
     * an hour with nothing changing.</p>
     */
    private static void requireStillHeld(Domain domain) {
        if (domain.getStatus() == DomainStatus.REMOVED || domain.getReleasedAt() != null) {
            throw new IllegalStateException(
                    "records are not editable on a domain that has been released: "
                            + domain.getFqdn());
        }
    }

    private static void requireExternal(Domain domain) {
        if (domain.getKind() != DomainKind.EXTERNAL) {
            throw new IllegalStateException("record sets belong to an external domain, not "
                    + domain.getKind() + ": " + domain.getFqdn());
        }
    }

    private static String key(String name, String type) {
        return name + "/" + type;
    }

    /**
     * Queues the push instead of running it here. After commit is when the
     * state is durable, but the caller's thread is still the request's: a
     * provider call on it makes the edit wait on the zone, and a slow or
     * unreachable provider becomes a slow save rather than a record that is
     * owed a write.
     */
    private void enqueueAfterCommit(JobLambda job) {
        afterCommit(() -> jobScheduler.enqueue(job));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
