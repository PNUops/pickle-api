package kr.ac.pusan.pickle.ipam;

/**
 * No allocatable address is left in the pool (all taken, reserved, or still
 * in quarantine). The provisioning pipeline parks the task as NEEDS_ADMIN on
 * this — it is an operator problem, not a retryable fault.
 */
public class IpPoolExhaustedException extends RuntimeException {

    public IpPoolExhaustedException(long poolId, String poolName) {
        super("IP pool exhausted: %s (id %d)".formatted(poolName, poolId));
    }
}
