package io.tracedelta.target.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    private SsrfGuard defaultGuard(HostResolver resolver) {
        SsrfProperties props = new SsrfProperties();
        props.setBlockPrivateAddresses(true);
        props.setAllowedHosts(List.of());
        return new SsrfGuard(props, resolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://example.com/data",
            "gopher://127.0.0.1:6379/_INFO",
            "https://user:pass@example.com/api",
            "http:///missing-host"
    })
    void rejectsDisallowedSchemesUserInfoAndMalformedUrls(String url) {
        SsrfGuard guard = defaultGuard(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        assertThatThrownBy(() -> guard.validateAndResolve(url))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/actuator",
            "http://localhost:8080/api",
            "http://[::1]:8080/api",
            "http://10.0.0.1/internal",
            "http://172.16.0.5/internal",
            "http://192.168.1.10/router",
            "http://169.254.169.254/latest/meta-data/",
            "http://[fe80::1]/meta",
            "http://0.0.0.0/any",
            "http://224.0.0.1/multicast",
            "http://100.64.0.1/cgnat",
            "http://[fc00::1]/ula",
            "http://[fd12:3456::1]/ula",
            "http://[::ffff:127.0.0.1]/mapped-loopback",
            "http://[::ffff:169.254.169.254]/mapped-metadata"
    })
    void blocksAllLoopbackPrivateLinkLocalMetadataCgnatUlaAndMappedIpv6(String url) {
        SsrfGuard guard = defaultGuard(host -> {
            if ("localhost".equalsIgnoreCase(host)) {
                return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})};
            }
            return InetAddress.getAllByName(host);
        });
        assertThatThrownBy(() -> guard.validateAndResolve(url))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void blocksHostIfAnyResolvedIpIsPrivate() {
        SsrfGuard guard = defaultGuard(host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("10.0.0.8")
        });
        assertThatThrownBy(() -> guard.validateAndResolve("https://dual-dns.example.com/v1/chat"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void allowsPublicHttpsUrl() {
        SsrfGuard guard = defaultGuard(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        URI uri = guard.validateAndResolve("https://api.example.com/v1/qa");
        assertThat(uri.getHost()).isEqualTo("api.example.com");
    }

    @Test
    void allowsLoopbackOnlyWhenExplicitlyWhitelisted() {
        SsrfProperties props = new SsrfProperties();
        props.setBlockPrivateAddresses(true);
        props.setAllowedHosts(List.of("127.0.0.1", "localhost"));
        SsrfGuard guard = new SsrfGuard(props, host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        URI uri = guard.validateAndResolve("http://127.0.0.1:9000/test");
        assertThat(uri.getPort()).isEqualTo(9000);

        // Non-whitelisted private IP must still be blocked
        SsrfGuard otherPrivate = new SsrfGuard(props, host -> new InetAddress[]{InetAddress.getByName("169.254.169.254")});
        assertThatThrownBy(() -> otherPrivate.validateAndResolve("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class);
    }
}
