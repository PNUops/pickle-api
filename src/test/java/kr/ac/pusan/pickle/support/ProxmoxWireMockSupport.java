package kr.ac.pusan.pickle.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * WireMock harness for Proxmox client tests: a dynamic-port server whose
 * {@link #apiHost()} is passed straight to the client (the api host is a
 * per-call argument, so no Spring configuration is involved), plus loaders
 * for the responses captured from the real pve1 (PVE 9.2.3, 2026-07-08)
 * under {@code src/test/resources/wiremock/proxmox/}.
 */
public final class ProxmoxWireMockSupport implements AutoCloseable {

    private final WireMockServer server;

    private ProxmoxWireMockSupport(WireMockServer server) {
        this.server = server;
    }

    public static ProxmoxWireMockSupport start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return new ProxmoxWireMockSupport(server);
    }

    /** Base URL to hand the client as {@code apiHost}, e.g. {@code http://localhost:39041}. */
    public String apiHost() {
        return "http://localhost:" + server.port();
    }

    public WireMockServer server() {
        return server;
    }

    public void reset() {
        server.resetAll();
    }

    @Override
    public void close() {
        server.stop();
    }

    /** Raw captured response body, e.g. {@code fixture("10-clone")}. */
    public static String fixture(String name) {
        String resource = "/wiremock/proxmox/" + name + ".json";
        try (InputStream in = ProxmoxWireMockSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture " + resource, e);
        }
    }

    /** 200 response with the given captured body. */
    public static ResponseDefinitionBuilder okFixture(String name) {
        return jsonFixture(200, name);
    }

    /** Arbitrary-status response with the given captured body (PVE errors are 500). */
    public static ResponseDefinitionBuilder jsonFixture(int status, String name) {
        return WireMock.aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json;charset=UTF-8")
                .withBody(fixture(name));
    }
}
