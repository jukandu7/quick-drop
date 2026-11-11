package com.example.quickdrop;

import com.example.quickdrop.controller.OwnerPinService;
import com.example.quickdrop.network.NetworkAddressService;
import com.example.quickdrop.network.PublicUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:https-transport")
@ActiveProfiles("https")
class HttpsTransportTests {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path KEYSTORE = createCertificate();
    @Autowired PublicUrlService urls;
    @MockitoBean OwnerPinService pins;
    @MockitoBean NetworkAddressService network;
    HttpClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("server.ssl.key-store", () -> KEYSTORE.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> PASSWORD);
    }

    @BeforeEach
    void setUp() throws Exception {
        when(pins.isConfigured()).thenReturn(true);
        when(pins.authenticationVersion()).thenReturn("test");
        when(network.findLocalIpv4Address()).thenReturn("192.0.2.10");
        KeyStore identity = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(KEYSTORE)) { identity.load(input, PASSWORD.toCharArray()); }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("test-server", identity.getCertificate("quickdrop-test"));
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, factory.getTrustManagers(), null);
        // Trust only this ephemeral test certificate; hostname validation remains enabled.
        client = HttpClient.newBuilder().sslContext(ssl)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void nativeHttpsServesPagesWithSecureCookiesAndSecureGeneratedLinks() throws Exception {
        var response = get("/owner/setup");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.sslSession()).isPresent();
        assertThat(response.headers().allValues("Set-Cookie").toString()).contains("Secure", "HttpOnly");
        assertThat(response.body()).doesNotContain("localhost:8080", "HTTP connection: PINs and files");
        var status = get("/api/status");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("https://192.0.2.10:");
        var spoofed = client.send(HttpRequest.newBuilder(URI.create(urls.localBaseUrl() + "/api/status"))
                .header("Forwarded", "proto=http;host=evil.example")
                .header("X-Forwarded-Proto", "http").header("X-Forwarded-Host", "evil.example")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(spoofed.body()).contains("https://192.0.2.10:").doesNotContain("evil.example");
    }

    @Test
    void httpsFormsAcceptMatchingOriginButRejectHttpOrigin() throws Exception {
        var page = get("/owner/setup");
        var matcher = Pattern.compile("id=\"csrf-token\"[^>]*content=\"([^\"]+)\"").matcher(page.body());
        assertThat(matcher.find()).isTrue();
        String body = "_csrf=" + matcher.group(1) + "&pin=9876&confirmPin=9876&currentPin=1234";
        assertThat(post(body, urls.localBaseUrl().replace("https:", "http:")).statusCode()).isEqualTo(403);
        verify(pins, never()).updatePin(anyString(), anyString());
        assertThat(post(body, urls.localBaseUrl()).statusCode()).isEqualTo(302);
        verify(pins).updatePin("9876", "1234");
    }

    @Test
    void plaintextCannotServeTheDashboardOnTheTlsPort() throws Exception {
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create(urls.localBaseUrl().replace("https:", "http:") + "/owner/setup"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(response.body()).doesNotContain("name=\"currentPin\"");
        } catch (java.io.IOException expected) {
            // A TLS connector may close the connection rather than send an HTTP error.
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(urls.localBaseUrl() + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, String origin) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(urls.localBaseUrl() + "/owner/setup"))
                .timeout(Duration.ofSeconds(5)).header("Origin", origin)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path createCertificate() {
        try {
            Path directory = Files.createTempDirectory("quickdrop-tls-test-");
            Path store = directory.resolve("server.p12");
            directory.toFile().deleteOnExit();
            store.toFile().deleteOnExit();
            String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-genkeypair", "-alias", "quickdrop-test", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-storetype", "PKCS12", "-keystore", store.toString(),
                    "-storepass:env", "QUICKDROP_TEST_CERT_PASSWORD", "-keypass:env", "QUICKDROP_TEST_CERT_PASSWORD");
            builder.environment().put("QUICKDROP_TEST_CERT_PASSWORD", PASSWORD);
            builder.redirectErrorStream(true);
            var process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("keytool timed out"); }
            if (process.exitValue() != 0) throw new IllegalStateException(new String(process.getInputStream().readAllBytes()));
            return store;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
