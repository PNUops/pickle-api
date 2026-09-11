package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.publishing.DomainRecordStatus;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code DnsRecordSetView}: one record set as the platform
 * holds it, and how far it has got into the zone.
 *
 * <p>There is no id. The set is identified by the pair that identifies it in
 * DNS itself, and an edit sends the whole desired state rather than addressing
 * one row, so an identifier would name something the client never refers to.</p>
 */
public record DnsRecordSetView(
        String name,
        DnsRecordType type,
        List<String> values,
        int ttl,
        DomainRecordStatus status,
        @Nullable String lastError,
        @Nullable Instant appliedAt) {
}
