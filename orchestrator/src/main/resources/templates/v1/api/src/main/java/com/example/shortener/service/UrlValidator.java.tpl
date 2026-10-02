package com.example.shortener.service;

import com.example.shortener.error.ShortenerException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Input validation for destination URLs and custom aliases, including a URL-level SSRF guard.
 * No DNS resolution happens here, so DNS rebinding is out of scope (documented limitation).
 */
@Component
public class UrlValidator {

    static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    private static final Set<String> RESERVED =
            Set.of("api", "actuator", "health", "healthz", "admin", "static", "favicon", "error", "docs");
    private static final Pattern DOTTED_QUAD = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern LABEL = Pattern.compile("^[a-z][a-z0-9-]*$");

    public String validateUrl(String raw) {
        if (raw == null || raw.isBlank()) throw ShortenerException.invalid("longUrl must not be blank");
        String url = raw.trim();
        if (url.length() > MAX_URL_LENGTH) throw ShortenerException.invalid("longUrl exceeds " + MAX_URL_LENGTH + " characters");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw ShortenerException.invalid("longUrl is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)))
            throw ShortenerException.invalid("only http and https URLs are allowed");
        if (uri.getUserInfo() != null) throw ShortenerException.invalid("credentials in URL are not allowed");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw ShortenerException.invalid("longUrl must contain a host");
        assertPublicHost(host);
        return url;
    }

    public String validateAlias(String alias) {
        if (!ALIAS.matcher(alias).matches())
            throw ShortenerException.invalid("customAlias must be 3-32 characters of letters, digits, '-' or '_'");
        if (RESERVED.contains(alias))
            throw ShortenerException.invalid("customAlias is reserved");
        return alias;
    }

    private void assertPublicHost(String rawHost) {
        String h = rawHost.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        try {
            if (h.contains(":")) {
                assertPublicAddress(InetAddress.getByName(h)); // IPv6 literal: parsed, never resolved
                return;
            }
            Matcher q = DOTTED_QUAD.matcher(h);
            if (q.matches()) {
                byte[] b = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int v = Integer.parseInt(q.group(i + 1));
                    if (v > 255) throw ShortenerException.invalid("malformed IP address");
                    b[i] = (byte) v;
                }
                assertPublicAddress(InetAddress.getByAddress(b));
                return;
            }
        } catch (UnknownHostException e) {
            throw ShortenerException.invalid("malformed IP address");
        }
        if (!h.contains(".") || h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal"))
            throw blocked();
        // Real top-level domains never start with a digit; this rejects 2130706433, 0x7f.1, 127.1 and similar forms.
        String tld = h.substring(h.lastIndexOf('.') + 1);
        if (!LABEL.matcher(tld).matches()) throw blocked();
    }

    private void assertPublicAddress(InetAddress a) {
        byte[] b = a.getAddress();
        boolean bad = a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            bad |= b0 == 0 || b0 >= 224 || (b0 == 100 && (b1 & 0xC0) == 64);
        } else {
            bad |= (b[0] & 0xFE) == 0xFC; // unique local fc00::/7
        }
        if (bad) throw blocked();
    }

    private ShortenerException blocked() {
        return ShortenerException.invalid("destination host is not allowed");
    }
}
