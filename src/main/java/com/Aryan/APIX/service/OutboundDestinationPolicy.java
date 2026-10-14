package com.Aryan.APIX.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** Destination policy shared by URL preflight and the actual Reactor Netty DNS resolver. */
@Component
public class OutboundDestinationPolicy {
    private final Set<String> allowedLocalHosts;

    public OutboundDestinationPolicy(@Value("${apix.outbound.allowed-local-hosts:}") String configuredHosts) {
        this.allowedLocalHosts = Arrays.stream(configuredHosts.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(OutboundDestinationPolicy::normalizeHost).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Checks URL syntax and any literal IP before the HTTP client is invoked. DNS is checked by the resolver. */
    public URI validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getRawUserInfo() != null || uri.getHost() == null)
                throw blocked();
            if (uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1 || uri.getRawAuthority() == null
                    || uri.getRawAuthority().contains("\\") || uri.getRawAuthority().contains("%")) throw blocked();
            String authority = uri.getRawAuthority();
            if ((!authority.startsWith("[") && authority.endsWith(":")) || authority.startsWith("[") && authority.endsWith("]:")) throw blocked();
            String host = normalizeHost(uri.getHost());
            if (host.isBlank() || host.length() > 253 || host.startsWith(".") || host.contains("..")) throw blocked();
            if (isMetadataHost(host)) throw blocked();
            rejectAmbiguousNumericHost(host);
            InetAddress literal = parseLiteral(host);
            if (literal != null) validateResolved(host, java.util.List.of(literal));
            else if (isExplicitlyNonPublicHost(host)) validateResolved(host, java.util.List.of(loopbackAddress()));
            return uri;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw blocked();
        }
    }

    /** Validates WebSocket schemes, then applies the exact HTTP(S) destination policy to the handshake target. */
    public URI validateWebSocketUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("ws") || scheme.equals("wss")) || uri.getRawFragment() != null) throw blocked();
            String httpScheme = scheme.equals("ws") ? "http" : "https";
            validateUrl(httpScheme + value.substring(value.indexOf(':')));
            return uri;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw blocked();
        }
    }

    public void validateResolved(String originalHost, Collection<InetAddress> addresses) {
        String host = normalizeHost(originalHost);
        if (isMetadataHost(host)) throw blocked();
        if (addresses == null || addresses.isEmpty()) throw blocked();
        boolean trustedLocal = allowedLocalHosts.contains(host);
        for (InetAddress address : addresses) {
            if (address == null || isMetadataAddress(address)) throw blocked();
            if (trustedLocal ? !isLocalAddress(address) : !isPublicAddress(address)) throw blocked();
        }
    }

    public boolean isAllowedLocalHost(String host) { return allowedLocalHosts.contains(normalizeHost(host)); }

    private static boolean isExplicitlyNonPublicHost(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.equals("local") || host.endsWith(".local");
    }

    private static boolean isMetadataHost(String host) {
        return host.equals("metadata") || host.equals("metadata.google.internal") || host.equals("metadata.azure.internal");
    }

    private static boolean isMetadataAddress(InetAddress address) {
        byte[] b = address.getAddress();
        boolean metadataV4 = b.length == 4 && ((b[0] & 255) == 169 && (b[1] & 255) == 254 && (b[2] & 255) == 169 && (b[3] & 255) == 254
                || (b[0] & 255) == 168 && (b[1] & 255) == 63 && (b[2] & 255) == 129 && (b[3] & 255) == 16);
        if (b.length == 16 && isIpv4Mapped(b)) {
            try { return metadataV4 || isMetadataAddress(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16))); }
            catch (Exception ignored) { return true; }
        }
        return metadataV4;
    }

    private static InetAddress loopbackAddress() {
        try { return InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    static boolean isPublicAddress(InetAddress address) { return !isSpecialAddress(address); }

    private static boolean isLocalAddress(InetAddress address) {
        return address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || isUniqueLocalIpv6(address);
    }

    private static boolean isSpecialAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return in4(b, 8, 0) || in4(b, 8, 10) || in4(b, 10, 100, 64) || in4(b, 8, 127)
                    || in4(b, 16, 169, 254) || in4(b, 12, 172, 16) || in4(b, 24, 192, 0, 0)
                    || in4(b, 24, 192, 0, 2) || in4(b, 24, 192, 88, 99) || in4(b, 16, 192, 168)
                    || in4(b, 15, 198, 18) || in4(b, 24, 198, 51, 100) || in4(b, 24, 203, 0, 113)
                    || in4(b, 4, 224) || in4(b, 4, 240);
        }
        if (address instanceof Inet6Address) {
            if (isUniqueLocalIpv6(address) || in6(b, 0x20010db800000000L, 32) || in6(b, 0x2001000000000000L, 23)
                    || in6(b, 0x2002000000000000L, 16)) return true;
            if (isIpv4Mapped(b)) {
                try { return isSpecialAddress(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16))); }
                catch (Exception ignored) { return true; }
            }
            // IPv6 global unicast is 2000::/3. Other ranges are special-use or not globally routable.
            return (b[0] & 0xe0) != 0x20;
        }
        return true;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] b = address.getAddress(); return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        if (b.length != 16 || b[10] != (byte) 0xff || b[11] != (byte) 0xff) return false;
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return true;
    }

    private static boolean in4(byte[] bytes, int prefix, int... network) {
        if (bytes.length != 4) return false;
        int full = prefix / 8, rem = prefix % 8;
        for (int i = 0; i < full; i++) if ((bytes[i] & 255) != network[i]) return false;
        return rem == 0 || ((bytes[full] & 255) >>> (8 - rem)) == (network[full] >>> (8 - rem));
    }

    private static boolean in6(byte[] bytes, long prefixHigh, int prefix) {
        if (bytes.length != 16) return false;
        for (int bit = 0; bit < prefix; bit++) {
            int actual = (bytes[bit / 8] >> (7 - bit % 8)) & 1;
            int expected = (int) ((prefixHigh >>> (63 - bit)) & 1);
            if (actual != expected) return false;
        }
        return true;
    }

    private static InetAddress parseLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            try { return InetAddress.getByName(host); } catch (Exception e) { throw blocked(); }
        }
        if (host.matches("[0-9.]+")) {
            String[] pieces = host.split("\\.", -1);
            if (pieces.length != 4) throw blocked();
            byte[] bytes = new byte[4];
            try {
                for (int i = 0; i < 4; i++) {
                    if (pieces[i].isEmpty() || (pieces[i].length() > 1 && pieces[i].startsWith("0"))) throw blocked();
                    int part = Integer.parseInt(pieces[i]); if (part > 255) throw blocked(); bytes[i] = (byte) part;
                }
                return InetAddress.getByAddress(bytes);
            } catch (ResponseStatusException e) { throw e; }
            catch (Exception e) { throw blocked(); }
        }
        return null;
    }

    private static void rejectAmbiguousNumericHost(String host) {
        if (host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}")) {
            if (!host.matches("[0-9.]+")) throw blocked();
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) normalized = normalized.substring(1, normalized.length() - 1);
        if (normalized.contains(":")) return normalized;
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith(".")) throw blocked();
        try { return IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (Exception e) { throw blocked(); }
    }

    private static ResponseStatusException blocked() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Outbound target is invalid or blocked by APiX network policy");
    }
}
