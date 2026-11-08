package kr.ac.pusan.pickle.publishing.dns;

import java.net.http.HttpClient;
import java.nio.file.Path;
import kr.ac.pusan.pickle.config.DnsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the {@link DnsRecordProvider} from {@code pickle.dns.provider}.
 * Every path here ends in a bean and a log line, never an exception: a
 * misconfigured provider becomes the fail-closed one with its reason logged
 * at WARN, and the api boots.
 */
@Configuration
public class DnsProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(DnsProviderConfig.class);

    @Bean
    public DnsRecordProvider dnsRecordProvider(DnsProperties properties,
            org.springframework.core.env.Environment environment) {
        return switch (properties.provider()) {
            case DnsProperties.PROVIDER_NOOP -> {
                // Refused outside development. The no-op accepts every write
                // and performs none, so on a real deployment it would mark
                // every name APPLIED while not one of them resolves — the
                // failure is invisible until a user reports a dead site. The
                // default already avoids it; this stops an explicit setting
                // from reaching production, which the default cannot.
                if (environment.matchesProfiles("prod")) {
                    yield unconfigured("DNS 레코드 제공자 noop은 운영 프로파일에서 사용할 수 없어요 "
                            + "(레코드를 쓰지 않고 성공으로 기록해요)");
                }
                log.info("dns provider: noop (records are accepted and never written)");
                yield new NoopDnsRecordProvider();
            }
            case DnsProperties.PROVIDER_GOOGLE -> google(properties);
            case DnsProperties.PROVIDER_NONE -> unconfigured(
                    "DNS 레코드 제공자가 설정되지 않았어요 (PICKLE_DNS_PROVIDER=none)");
            default -> unconfigured("알 수 없는 DNS 레코드 제공자예요: "
                    + properties.provider());
        };
    }

    private DnsRecordProvider google(DnsProperties properties) {
        DnsProperties.Google google = properties.google();
        if (!google.complete()) {
            return unconfigured("Google Cloud DNS 설정이 비어 있어요 "
                    + "(PICKLE_DNS_GOOGLE_PROJECT, PICKLE_DNS_GOOGLE_ZONE)");
        }
        RestClient restClient = restClient(properties);
        GoogleServiceAccountTokens tokens;
        try {
            tokens = GoogleServiceAccountTokens.load(Path.of(google.credentials()), restClient);
        } catch (DnsProviderException e) {
            return unconfigured("Google Cloud DNS 서비스 계정 키를 읽을 수 없어요: " + e.getMessage());
        }
        log.info("dns provider: google (project {}, zone {}, account {})", google.project(),
                google.zone(), tokens.clientEmail());
        return new GoogleCloudDnsRecordProvider(restClient, tokens,
                GoogleCloudDnsRecordProvider.DEFAULT_API_BASE, google.project(), google.zone());
    }

    private static RestClient restClient(DnsProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    private static DnsRecordProvider unconfigured(String reason) {
        log.warn("dns provider unconfigured, platform-subdomain publishing is refused: {}", reason);
        return new UnconfiguredDnsRecordProvider(reason);
    }
}
