package kr.ac.pusan.pickle.terminal;

import java.net.http.HttpClient;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.TerminalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for the bridge control link (the internal web-terminal contract,
 * {@code POST 172.30.1.30:8083/control/terminate}). pickle-api is the client:
 * a SYS_ADMIN force-terminate calls here and the bridge closes the WS (4002) and
 * tears down SSH.
 *
 * <p>Fail-closed: the control bearer ({@code PICKLE_TERMINAL_CONTROL_TOKEN}) has
 * no default outside dev/test — an unset token makes the terminate call answer
 * <b>503</b> rather than sending an empty bearer (the app still boots; only the
 * terminate op is unavailable). A transport failure (bridge down) also surfaces
 * as 503.</p>
 */
@Component
public class BridgeControlClient {

    private static final Logger log = LoggerFactory.getLogger(BridgeControlClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TerminalProperties properties;
    private final RestClient restClient;

    public BridgeControlClient(TerminalProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.bridgeConnectTimeout()).build());
        factory.setReadTimeout(properties.bridgeReadTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.bridgeControlBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * {@code POST /control/terminate} — instructs the bridge to close the session.
     * Idempotent on the bridge (unknown/already-closed → 204). Throws 503 when the
     * control token is unset or the bridge is unreachable.
     */
    public void terminate(String sessionId) {
        if (properties.controlTokenUnset()) {
            throw serviceUnavailable("웹 터미널 제어 채널이 설정되지 않았습니다.");
        }
        String body = JSON.writeValueAsString(new TerminateBody(sessionId));
        try {
            restClient.method(HttpMethod.POST)
                    .uri("/control/terminate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.bridgeControlToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 200 && status < 300) {
                            return null;
                        }
                        log.warn("bridge control /terminate HTTP {} for session {}", status,
                                sessionId);
                        throw serviceUnavailable("웹 터미널 세션 종료 지시에 실패했습니다.");
                    });
        } catch (ResourceAccessException e) {
            log.warn("bridge control transport failure on /terminate: {}", e.getMessage());
            throw serviceUnavailable("웹 터미널 브리지에 연결할 수 없습니다.");
        }
    }

    private static ApiException serviceUnavailable(String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.INTERNAL_ERROR,
                "웹 터미널 세션을 종료할 수 없습니다", detail);
    }

    private record TerminateBody(String sessionId) {
    }
}
