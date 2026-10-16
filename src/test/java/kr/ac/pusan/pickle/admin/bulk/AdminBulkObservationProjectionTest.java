package kr.ac.pusan.pickle.admin.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.publishing.Certificate;
import kr.ac.pusan.pickle.publishing.CertificateRepository;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainKind;
import kr.ac.pusan.pickle.publishing.DomainRecord;
import kr.ac.pusan.pickle.publishing.DomainRecordRepository;
import kr.ac.pusan.pickle.publishing.DomainRecordStatus;
import kr.ac.pusan.pickle.publishing.DomainRepository;
import kr.ac.pusan.pickle.publishing.DomainStatus;
import kr.ac.pusan.pickle.publishing.Route;
import kr.ac.pusan.pickle.publishing.RouteRepository;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Internal identity inputs to a fingerprint are absent from public observations. */
class AdminBulkObservationProjectionTest {
    @Test
    void domainReleaseObservationsExposeCountsAndStatusesInsteadOfInternalJoinKeys() {
        Domain domain = mock(Domain.class);
        when(domain.getId()).thenReturn(987654321L);
        when(domain.getVmId()).thenReturn(123456789L);
        when(domain.getKind()).thenReturn(DomainKind.CUSTOM);
        when(domain.getStatus()).thenReturn(DomainStatus.ACTIVE);
        DomainRecordRepository records = mock(DomainRecordRepository.class);
        DomainRecord record = mock(DomainRecord.class);
        when(record.getId()).thenReturn(222222222L);
        when(record.getStatus()).thenReturn(DomainRecordStatus.APPLIED);
        when(records.findByDomainIdAndStatusNotOrderByIdAsc(domain.getId(), DomainRecordStatus.REMOVED)).thenReturn(List.of(record));
        RouteRepository routes = mock(RouteRepository.class);
        Route route = mock(Route.class);
        when(route.getId()).thenReturn(333333333L);
        when(route.getStatus()).thenReturn(RouteStatus.APPLIED);
        when(routes.findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)).thenReturn(Optional.of(route));
        CertificateRepository certificates = mock(CertificateRepository.class);
        Certificate certificate = mock(Certificate.class);
        when(certificate.getId()).thenReturn(444444444L);
        when(certificates.findByDomainId(domain.getId())).thenReturn(List.of(certificate));
        var handler = new DomainBulkChanges(mock(DomainRepository.class), routes, certificates, records,
                mock(kr.ac.pusan.pickle.publishing.AdminPublishingService.class), mock(EntityManager.class), new ObjectMapper());
        var change = new AdminBulkChangeSpec(AdminBulkChangeKind.DOMAIN_FORCE_RELEASE, null, null, null,
                null, null, null, null, null, null, null);
        var values = handler.observationValues(domain, change);
        assertThat(values).containsOnlyKeys("kind", "status", "releasedAt", "dnsStatus", "recordCount", "liveRouteStatus", "certificateCount");
        assertThat(values.get("recordCount")).isEqualTo(1);
        assertThat(values.get("certificateCount")).isEqualTo(1);
        assertThat(values.get("liveRouteStatus")).isEqualTo("APPLIED");
        assertThat(new ObjectMapper().writeValueAsString(values)).doesNotContain("987654321", "123456789", "222222222", "333333333", "444444444", "vmId", "recordsGeneration");
    }
}
