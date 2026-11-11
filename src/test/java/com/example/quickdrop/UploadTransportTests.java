package com.example.quickdrop;

import com.example.quickdrop.network.PublicUrlService;
import com.example.quickdrop.share.ShareLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:upload-transport",
        "spring.servlet.multipart.max-request-size=1KB",
        "spring.servlet.multipart.max-file-size=900B",
        "spring.servlet.multipart.resolve-lazily=true"})
class UploadTransportTests {
    @Autowired PublicUrlService urls;
    @Autowired ShareLinkService storage;
    HttpClient client;
    CookieManager cookies;
    String token;

    @BeforeEach void setup() throws Exception {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        var page = client.send(HttpRequest.newBuilder(URI.create(urls.localBaseUrl() + "/owner/setup")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var match = Pattern.compile("id=\"csrf-token\"[^>]*content=\"([^\"]+)\"").matcher(page.body());
        assertThat(match.find()).isTrue();
        token = match.group(1);
    }

    @Test void rejectsHeadersWithoutWaitingForBody() throws Exception {
        assertThat(headersOnly("/receive/missing/upload", 1000)).contains("410");
        assertThat(headersOnly("/send/upload", 1000)).contains("410");
        assertThat(headersOnly("/upload", 2000)).contains("413");
        assertThat(headersOnly("/qr", 1000)).contains("415");
        assertSpoolEmpty();
    }

    String headersOnly(String path, int length) throws Exception {
        URI base = URI.create(urls.localBaseUrl());
        try (Socket socket = new Socket(base.getHost(), base.getPort())) {
            socket.setSoTimeout(3000);
            String cookie = cookies.getCookieStore().getCookies().stream()
                    .map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
            String headers = "POST " + path + " HTTP/1.1\r\nHost: localhost:" + base.getPort()
                    + "\r\nCookie: " + cookie + "\r\nX-CSRF-Token: " + token
                    + "\r\nContent-Type: multipart/form-data; boundary=test\r\nContent-Length: " + length
                    + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
        }
    }

    @Test void chunkedOverLimitIsRejectedAndNextUploadSucceeds() throws Exception {
        // ofInputStream has unknown length, so HttpClient uses chunked transfer encoding.
        byte[] large = body("large.txt", "x".repeat(2000));
        var result = client.send(upload(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(large))),
                HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(413);
        assertSpoolEmpty();
        byte[] small = body(UUID.randomUUID() + ".txt", "hello");
        var accepted = client.send(upload(HttpRequest.BodyPublishers.ofByteArray(small)), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertSpoolEmpty();
    }

    HttpRequest upload(HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(URI.create(urls.localBaseUrl() + "/upload"))
                .timeout(Duration.ofSeconds(5)).header("X-CSRF-Token", token)
                .header("Content-Type", "multipart/form-data; boundary=test").POST(body).build();
    }

    byte[] body(String name, String content) {
        return ("--test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain\r\n\r\n" + content + "\r\n--test--\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    void assertSpoolEmpty() throws Exception {
        try (var files = Files.list(storage.storageDirectory().resolve(".incoming"))) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
