package io.tracedelta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TraceDeltaApplicationTests {

    @Autowired
    @Qualifier("virtualThreadExecutor")
    private ExecutorService virtualThreadExecutor;

    @Test
    void contextLoadsAndVirtualThreadExecutorUsesVirtualThreads() throws Exception {
        Boolean isVirtual = virtualThreadExecutor.submit(() -> Thread.currentThread().isVirtual()).get();
        assertThat(isVirtual).isTrue();
    }
}
