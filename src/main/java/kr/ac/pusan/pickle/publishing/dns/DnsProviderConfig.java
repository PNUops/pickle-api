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
    public DnsRecordProvider dnsRecordProvider(DnsProperties properties) {
        return switch (properties.provider()) {
            case DnsProperties.PROVIDER_NOOP -> {
                log.info("dns provider: noop (records are accepted and never written)");
                yield new NoopDnsRecordProvider();
            }
            case DnsProperties.PROVIDER_GOOGLE -> google(properties);
            case DnsProperties.PROVIDER_NONE -> unconfigured(
                    "DNS 레코드 제공자가 설정되어 있지 않습니다 (PICKLE_DNS_PROVIDER=none)");
            default -> unconfigured("알 수 없는 DNS 레코드 제공자입니다: "
                    + properties.provider());
        };
    }

    private DnsRecordProvider google(DnsProperties properties) {
        DnsProperties.Google google = properties.google();
        if (!google.complete()) {
            return unconfigured("Google Cloud DNS 설정이 비어 있습니다 "
                    + "(PICKLE_DNS_GOOGLE_PROJECT, PICKLE_DNS_GOOGLE_ZONE)");
        }
        RestClient restClient = restClient(properties);
        GoogleServiceAccountTokens tokens;
        try {
            tokens = GoogleServiceAccountTokens.load(Path.of(google.credentials()), restClient);
        } catch (DnsProviderException e) {
            return unconfigured("Google Cloud DNS 서비스 계정 키를 읽을 수 없습니다: " + e.getMessage());
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
