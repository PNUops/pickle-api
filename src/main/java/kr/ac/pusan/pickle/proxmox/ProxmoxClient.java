package kr.ac.pusan.pickle.proxmox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import kr.ac.pusan.pickle.proxmox.dto.AgentInterface;
import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.proxmox.dto.NodeStatusInfo;
import kr.ac.pusan.pickle.proxmox.dto.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thin client for the Proxmox VE REST API ({@code /api2/json}, docs/plan/03).
 * The API host is a per-call argument ({@code https://<node>:8006}) because it
 * can differ per node — and tests point it at WireMock.
 *
 * <p>Long operations (clone/resize/power/delete) return a UPID immediately;
 * {@link #awaitTask} polls the task status with jitter until {@code stopped}
 * and maps a non-OK {@code exitstatus} to {@link ProxmoxTaskFailedException}.
 * HTTP errors become {@link ProxmoxApiException} preserving the PVE
 * {@code message} field; the token never appears in exceptions or logs (the
 * logback masking additionally scrubs {@code PVEAPIToken=...}).</p>
 *
 * <p>TLS: when {@code pickle.proxmox.ca-cert-path} is set, only that PEM chain
 * is trusted (pinning). There is deliberately no "skip verification" option.</p>
 */
@Component
public class ProxmoxClient {

    private static final Logger log = LoggerFactory.getLogger(ProxmoxClient.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Envelope<String>> UPID_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<Envelope<Object>> VOID_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<Envelope<TaskStatus>> TASK_STATUS_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<Envelope<Map<String, Object>>> CONFIG_RESPONSE =
            new TypeReference<>() {
            };
    private static final TypeReference<Envelope<List<ClusterResource>>> CLUSTER_RESOURCES_RESPONSE =
            new TypeReference<>() {
            };
    private static final TypeReference<Envelope<NodeStatusInfo>> NODE_STATUS_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<Envelope<AgentResult<List<AgentInterface>>>> AGENT_NETIF_RESPONSE =
            new TypeReference<>() {
            };
    private static final TypeReference<Envelope<AgentFileRead>> AGENT_FILE_READ_RESPONSE =
            new TypeReference<>() {
            };

    private final ProxmoxProperties properties;
    private final RestClient restClient;

    public ProxmoxClient(ProxmoxProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(properties))
                .build();
    }

    // --- VMID / inventory ---------------------------------------------------

    /** {@code GET /cluster/nextid} — PVE returns the id as a JSON string. */
    public int nextId(String apiHost) {
        String data = call(HttpMethod.GET, uri(apiHost, "cluster", "nextid"), null, UPID_RESPONSE);
        return Integer.parseInt(data);
    }

    /** {@code GET /cluster/resources[?type=]} — {@code type} nullable (e.g. "vm"). */
    public List<ClusterResource> clusterResources(String apiHost, String type) {
        UriComponentsBuilder builder = baseBuilder(apiHost).pathSegment("cluster", "resources");
        if (type != null && !type.isBlank()) {
            builder.queryParam("type", type);
        }
        return call(HttpMethod.GET, builder.build().encode().toUri(), null, CLUSTER_RESOURCES_RESPONSE);
    }

    /** {@code GET /nodes/{n}/status} — capacity info for placement scoring. */
    public NodeStatusInfo nodeStatus(String apiHost, String node) {
        return call(HttpMethod.GET, uri(apiHost, "nodes", node, "status"), null, NODE_STATUS_RESPONSE);
    }

    // --- VM lifecycle (async: return UPID) -----------------------------------

    /** {@code POST /nodes/{n}/qemu/{tmpl}/clone} — always a full clone. */
    public String clone(String apiHost, String node, int templateVmid, int newVmid, String name) {
        Map<String, String> form = Map.of(
                "newid", String.valueOf(newVmid),
                "name", name,
                "full", "1");
        return call(HttpMethod.POST,
                uri(apiHost, "nodes", node, "qemu", String.valueOf(templateVmid), "clone"),
                form, UPID_RESPONSE);
    }

    /**
     * {@code PUT /nodes/{n}/qemu/{id}/config} (form-urlencoded; synchronous —
     * {@code data} is null). Params per docs/plan/03: cores, memory, ciuser,
     * cipassword, ipconfig0, nameserver, onboot…
     */
    public void config(String apiHost, String node, int vmid, Map<String, String> params) {
        call(HttpMethod.PUT, uri(apiHost, "nodes", node, "qemu", String.valueOf(vmid), "config"),
                params, VOID_RESPONSE);
    }

    /**
     * Sets or clears the PVE native {@code protection} flag on the VM config
     * (synchronous, form-urlencoded — mirrors {@link #config}). A protected VM
     * refuses destroy/disk-remove at the hypervisor level, backing the
     * {@code deletion_protection} VM setting (M6, docs/plan/03).
     */
    public void setProtection(String apiHost, String node, int vmid, boolean protect) {
        config(apiHost, node, vmid, Map.of("protection", protect ? "1" : "0"));
    }

    /**
     * {@code GET /nodes/{n}/qemu/{id}/config} → the live PVE {@code protection}
     * flag (absent/0 = false). Used by the reconciler to detect out-of-band
     * {@code qm set --protection} divergence from the {@code deletion_protection}
     * VM setting (M6).
     */
    public boolean isProtected(String apiHost, String node, int vmid) {
        Map<String, Object> config = call(HttpMethod.GET,
                uri(apiHost, "nodes", node, "qemu", String.valueOf(vmid), "config"), null,
                CONFIG_RESPONSE);
        return config != null && truthyFlag(config.get("protection"));
    }

    /** PVE renders boolean flags as 0/1 int or string across versions. */
    private static boolean truthyFlag(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        if (value instanceof String s) {
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
        return false;
    }

    /** {@code PUT /nodes/{n}/qemu/{id}/resize} — e.g. disk "scsi0", size "20G". */
    public String resize(String apiHost, String node, int vmid, String disk, String size) {
        return call(HttpMethod.PUT, uri(apiHost, "nodes", node, "qemu", String.valueOf(vmid), "resize"),
                Map.of("disk", disk, "size", size), UPID_RESPONSE);
    }

    public String start(String apiHost, String node, int vmid) {
        return power(apiHost, node, vmid, "start", null);
    }

    /** Graceful shutdown with PVE's default guest timeout. */
    public String shutdown(String apiHost, String node, int vmid) {
        return power(apiHost, node, vmid, "shutdown", null);
    }

    /** Graceful shutdown, giving the guest {@code timeoutSeconds} to power down. */
    public String shutdown(String apiHost, String node, int vmid, int timeoutSeconds) {
        return power(apiHost, node, vmid, "shutdown", Map.of("timeout", String.valueOf(timeoutSeconds)));
    }

    public String stop(String apiHost, String node, int vmid) {
        return power(apiHost, node, vmid, "stop", null);
    }

    public String reboot(String apiHost, String node, int vmid) {
        return power(apiHost, node, vmid, "reboot", null);
    }

    /**
     * {@code DELETE /nodes/{n}/qemu/{id}?purge=1&destroy-unreferenced-disks=1}:
     * also drop the VM from backup/HA configs and destroy stray disks so a
     * deleted user VM leaves nothing behind.
     */
    public String delete(String apiHost, String node, int vmid) {
        URI uri = baseBuilder(apiHost)
                .pathSegment("nodes", node, "qemu", String.valueOf(vmid))
                .queryParam("purge", "1")
                .queryParam("destroy-unreferenced-disks", "1")
                .build().encode().toUri();
        return call(HttpMethod.DELETE, uri, null, UPID_RESPONSE);
    }

    // --- Task polling ---------------------------------------------------------

    /** {@code GET /nodes/{n}/tasks/{upid}/status} — single poll. */
    public TaskStatus taskStatus(String apiHost, String node, String upid) {
        return call(HttpMethod.GET, uri(apiHost, "nodes", node, "tasks", upid, "status"),
                null, TASK_STATUS_RESPONSE);
    }

    /** {@link #awaitTask(String, String, String, Duration)} with the configured default timeout. */
    public TaskStatus awaitTask(String apiHost, String node, String upid) {
        return awaitTask(apiHost, node, upid, properties.taskPollTimeout());
    }

    /**
     * Polls the task until {@code stopped} (interval ±20% jitter so concurrent
     * jobs do not thundering-herd pvedaemon).
     *
     * @throws ProxmoxTaskFailedException when the task stopped with a non-OK exitstatus
     * @throws ProxmoxTimeoutException    when {@code timeout} elapses first
     */
    public TaskStatus awaitTask(String apiHost, String node, String upid, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            TaskStatus status = taskStatus(apiHost, node, upid);
            if (status.isStopped()) {
                if (!status.isOk()) {
                    throw new ProxmoxTaskFailedException(upid, status.exitstatus());
                }
                return status;
            }
            if (System.nanoTime() >= deadline) {
                throw new ProxmoxTimeoutException(
                        "Proxmox task did not finish within " + timeout + ": " + upid);
            }
            sleepWithJitter();
        }
    }

    // --- Guest agent ----------------------------------------------------------

    /**
     * {@code POST /nodes/{n}/qemu/{id}/agent/ping} — true when the guest agent
     * answers (2xx). An HTTP error ("guest agent is not running") is the
     * expected not-ready-yet answer → false; transport failures still throw.
     */
    public boolean agentPing(String apiHost, String node, int vmid) {
        try {
            call(HttpMethod.POST, uri(apiHost, "nodes", node, "qemu", String.valueOf(vmid), "agent", "ping"),
                    null, VOID_RESPONSE);
            return true;
        } catch (ProxmoxApiException e) {
            if (e.statusCode() > 0) {
                log.debug("proxmox agent ping negative for vmid {}: {}", vmid, e.getMessage());
                return false;
            }
            throw e;
        }
    }

    /**
     * {@code POST /nodes/{n}/qemu/{id}/agent/set-user-password} — sets the
     * guest account's password live (no reboot). Returns true on success; an
     * HTTP error ("guest agent is not running") is the expected agent-unavailable
     * answer → false (caller maps to 409). Transport failures still throw (5xx).
     */
    public boolean agentSetUserPassword(String apiHost, String node, int vmid, String username,
            String password) {
        try {
            call(HttpMethod.POST,
                    uri(apiHost, "nodes", node, "qemu", String.valueOf(vmid), "agent",
                            "set-user-password"),
                    Map.of("username", username, "password", password), VOID_RESPONSE);
            return true;
        } catch (ProxmoxApiException e) {
            if (e.statusCode() > 0) {
                log.debug("proxmox agent set-user-password negative for vmid {}: {}", vmid,
                        e.getMessage());
                return false;
            }
            throw e;
        }
    }

    /**
     * {@code GET /nodes/{n}/qemu/{id}/agent/file-read?file=<path>} — reads a
     * small guest file via the agent (used to collect the SSH host key). A
     * truncated read (file exceeded the agent's size cap) is an error rather
     * than a silently-clipped value.
     */
    public String agentFileRead(String apiHost, String node, int vmid, String path) {
        URI uri = baseBuilder(apiHost)
                .pathSegment("nodes", node, "qemu", String.valueOf(vmid), "agent", "file-read")
                .queryParam("file", path)
                .build().encode().toUri();
        AgentFileRead result = call(HttpMethod.GET, uri, null, AGENT_FILE_READ_RESPONSE);
        if (result == null || result.content() == null) {
            throw new ProxmoxApiException("agent file-read returned no content for " + path, null);
        }
        if (result.isTruncated()) {
            throw new ProxmoxApiException("agent file-read truncated for " + path, null);
        }
        return result.content();
    }

    /** {@code GET /nodes/{n}/qemu/{id}/agent/network-get-interfaces}. */
    public List<AgentInterface> agentNetworkInterfaces(String apiHost, String node, int vmid) {
        AgentResult<List<AgentInterface>> data = call(HttpMethod.GET,
                uri(apiHost, "nodes", node, "qemu", String.valueOf(vmid), "agent", "network-get-interfaces"),
                null, AGENT_NETIF_RESPONSE);
        return data.result() != null ? data.result() : List.of();
    }

    // --- internals --------------------------------------------------------------

    private String power(String apiHost, String node, int vmid, String action, Map<String, String> form) {
        return call(HttpMethod.POST,
                uri(apiHost, "nodes", node, "qemu", String.valueOf(vmid), "status", action),
                form, UPID_RESPONSE);
    }

    /**
     * Executes the request and unwraps the PVE {@code {"data": ...}} envelope.
     * Error responses ({@code {"message": ..., "data": null}}) and transport
     * failures both surface as {@link ProxmoxApiException}.
     */
    private <T> T call(HttpMethod method, URI uri, Map<String, String> form,
            TypeReference<Envelope<T>> responseType) {
        String description = method + " " + uri.getPath();
        log.debug("proxmox {}", description);
        RestClient.RequestBodySpec spec = restClient.method(method)
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader())
                .accept(MediaType.APPLICATION_JSON);
        if (form != null && !form.isEmpty()) {
            spec = spec.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(encodeForm(form));
        }
        try {
            return spec.exchange((request, response) -> {
                String body;
                try (InputStream in = response.getBody()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (response.getStatusCode().isError()) {
                    throw new ProxmoxApiException(response.getStatusCode().value(),
                            extractErrorMessage(body), description);
                }
                return JSON.readValue(body, responseType).data();
            });
        } catch (ResourceAccessException e) {
            // connect/read timeout, connection refused/reset … — no HTTP response.
            throw new ProxmoxApiException("Proxmox API transport failure on " + description
                    + ": " + e.getMessage(), e);
        }
    }

    private String authorizationHeader() {
        if (isBlank(properties.tokenId()) || isBlank(properties.tokenSecret())) {
            throw new IllegalStateException("Proxmox API token is not configured: set "
                    + "PICKLE_PROXMOX_TOKEN_ID and PICKLE_PROXMOX_TOKEN_SECRET (pickle.proxmox.*)");
        }
        return "PVEAPIToken=" + properties.tokenId() + "=" + properties.tokenSecret();
    }

    private static String extractErrorMessage(String body) {
        try {
            ErrorBody error = JSON.readValue(body, ErrorBody.class);
            return error.message() != null ? error.message() : body;
        } catch (RuntimeException e) {
            return body; // non-JSON error page (proxy, pveproxy hiccup)
        }
    }

    private void sleepWithJitter() {
        long base = properties.taskPollInterval().toMillis();
        double factor = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        try {
            Thread.sleep(Math.max(1L, (long) (base * factor)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxmoxTimeoutException("Interrupted while polling Proxmox task", e);
        }
    }

    private static URI uri(String apiHost, String... segments) {
        return baseBuilder(apiHost).pathSegment(segments).build().encode().toUri();
    }

    private static UriComponentsBuilder baseBuilder(String apiHost) {
        return UriComponentsBuilder.fromUriString(apiHost).pathSegment("api2", "json");
    }

    private static String encodeForm(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static org.springframework.http.client.JdkClientHttpRequestFactory requestFactory(
            ProxmoxProperties properties) {
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout());
        if (!isBlank(properties.caCertPath())) {
            builder.sslContext(pinnedSslContext(properties.caCertPath()));
        }
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    /**
     * SSLContext trusting only the certificate chain in the given PEM file
     * (the pve1 API cert/CA — docs/domains-tls.md). No verification-bypass
     * variant exists on purpose.
     */
    private static SSLContext pinnedSslContext(String pemPath) {
        try (InputStream in = Files.newInputStream(Path.of(pemPath))) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificateFactory.generateCertificates(in)) {
                trustStore.setCertificateEntry("proxmox-ca-" + index++, certificate);
            }
            if (index == 0) {
                throw new IllegalStateException("No certificates found in " + pemPath);
            }
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Failed to load Proxmox CA certificate from " + pemPath, e);
        }
    }

    /** PVE wraps every response in {@code {"data": ...}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope<T>(T data) {
    }

    /** Guest-agent responses nest the payload one level deeper: {@code data.result}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AgentResult<T>(T result) {
    }

    /**
     * {@code agent/file-read} payload ({@code {"content":..., "truncated":...}}).
     * PVE has returned {@code truncated} as both a boolean and a 0/1 int across
     * versions, so it is read leniently.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AgentFileRead(String content, Object truncated) {
        boolean isTruncated() {
            if (truncated instanceof Boolean b) {
                return b;
            }
            if (truncated instanceof Number n) {
                return n.intValue() != 0;
            }
            return false;
        }
    }

    /** PVE error responses: {@code {"message": "...", "data": null}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorBody(String message) {
    }
}
