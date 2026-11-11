package com.example.quickdrop.network;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;

/** Generates URLs from operator configuration, never from Host/Forwarded headers. */
@Service
public class PublicUrlService {
    private final NetworkAddressService network;
    private final boolean https;
    private final String publicUrl;
    private volatile int port;

    public PublicUrlService(NetworkAddressService network,
                            @Value("${quickdrop.public-base-url:}") String publicUrl,
                            @Value("${server.ssl.enabled:false}") boolean https,
                            @Value("${server.port:8080}") int port) {
        this.network = network;
        this.https = https;
        this.port = port;
        this.publicUrl = validate(publicUrl, https);
    }

    @EventListener
    public void serverStarted(WebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() == null) {
            port = event.getWebServer().getPort();
        }
    }

    public String currentBaseUrl() {
        if (publicUrl != null) return publicUrl;
        String address = network.findLocalIpv4Address();
        if (address == null || address.isBlank() || address.startsWith("127.") || address.equals("0.0.0.0")) {
            return null;
        }
        return origin(address);
    }

    public String localBaseUrl() { return origin("localhost"); }

    private String origin(String host) {
        int defaultPort = https ? 443 : 80;
        return (https ? "https://" : "http://") + host + (port == defaultPort ? "" : ":" + port);
    }

    private static String validate(String configured, boolean https) {
        if (configured == null || configured.isBlank()) return null;
        try {
            URI uri = URI.create(configured.trim());
            String scheme = uri.getScheme();
            String expectedScheme = https ? "https" : "http";
            if (!expectedScheme.equalsIgnoreCase(scheme) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException();
            }
            int configuredPort = uri.getPort();
            int defaultPort = https ? 443 : 80;
            return expectedScheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (configuredPort == -1 || configuredPort == defaultPort ? "" : ":" + configuredPort);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("quickdrop.public-base-url must be an absolute "
                    + (https ? "HTTPS" : "HTTP")
                    + " origin matching the server's TLS mode, with no credentials, path, query, or fragment.");
        }
    }
}
