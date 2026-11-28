package io.tracedelta.target.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SsrfGuard {

    private final SsrfProperties properties;
    private final HostResolver hostResolver;

    @Autowired
    public SsrfGuard(SsrfProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    public SsrfGuard(SsrfProperties properties, HostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver;
    }

    public URI validateAndResolve(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfBlockedException("目标 URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new SsrfBlockedException("目标 URL 格式非法: " + ex.getMessage());
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new SsrfBlockedException("仅允许 http 或 https 协议，已拦截: " + scheme);
        }
        if (uri.getRawUserInfo() != null) {
            throw new SsrfBlockedException("不允许在目标 URL 中内嵌认证凭据（userinfo）");
        }

        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new SsrfBlockedException("目标 URL 缺少有效主机名");
        }
        String host = stripIpv6Brackets(rawHost).toLowerCase(Locale.ROOT);

        if (!properties.isBlockPrivateAddresses() || isExplicitlyAllowedHost(host)) {
            return uri;
        }

        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolveAll(host);
        } catch (UnknownHostException ex) {
            throw new SsrfBlockedException("无法解析目标主机名: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new SsrfBlockedException("目标主机名未解析出任何 IP 地址: " + host);
        }

        for (InetAddress address : addresses) {
            String hostAddr = address.getHostAddress();
            if (hostAddr != null && isExplicitlyAllowedHost(hostAddr.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (isForbiddenAddress(address)) {
                throw new SsrfBlockedException(
                        "SSRF 防护已拦截私网/回环/保留地址: " + host + " -> " + hostAddr);
            }
        }
        return uri;
    }

    private boolean isExplicitlyAllowedHost(String hostOrIp) {
        List<String> allowed = properties.getAllowedHosts();
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        for (String entry : allowed) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            for (String token : entry.split(",")) {
                String clean = stripIpv6Brackets(token.trim()).toLowerCase(Locale.ROOT);
                if (!clean.isEmpty() && clean.equals(hostOrIp)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isForbiddenIpv4(bytes[0] & 0xFF, bytes[1] & 0xFF);
        }
        if (bytes.length == 16) {
            // IPv6 Unique Local Address (fc00::/7)
            int firstByte = bytes[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) {
                return true;
            }
            // IPv4-mapped IPv6 (::ffff:a.b.c.d)
            if (isIpv4MappedIpv6(bytes)) {
                return isForbiddenIpv4(bytes[12] & 0xFF, bytes[13] & 0xFF);
            }
        }
        return false;
    }

    private static boolean isForbiddenIpv4(int b0, int b1) {
        if (b0 == 0) return true;                          // 0.0.0.0/8 unspecified
        if (b0 == 10) return true;                         // 10.0.0.0/8 RFC1918
        if (b0 == 100 && (b1 & 0xC0) == 64) return true;   // 100.64.0.0/10 CGNAT (RFC6598)
        if (b0 == 127) return true;                        // 127.0.0.0/8 loopback
        if (b0 == 169 && b1 == 254) return true;           // 169.254.0.0/16 link-local & cloud metadata
        if (b0 == 172 && (b1 & 0xF0) == 16) return true;   // 172.16.0.0/12 RFC1918
        if (b0 == 192 && b1 == 168) return true;           // 192.168.0.0/16 RFC1918
        if ((b0 & 0xF0) == 224) return true;               // 224.0.0.0/4 multicast
        if ((b0 & 0xF0) == 240) return true;               // 240.0.0.0/4 reserved / broadcast
        return false;
    }

    private static boolean isIpv4MappedIpv6(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
