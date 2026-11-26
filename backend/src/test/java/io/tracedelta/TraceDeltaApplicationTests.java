package io.tracedelta;

import static org.assertj.core.api.Assertions.assertThat;

import io.tracedelta.provider.llm.LlmClient;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TraceDeltaApplicationTests {

    @Autowired
    @Qualifier("virtualThreadExecutor")
    private ExecutorService virtualThreadExecutor;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsAndVirtualThreadExecutorUsesVirtualThreads() throws Exception {
        Boolean isVirtual = virtualThreadExecutor.submit(() -> Thread.currentThread().isVirtual()).get();
        assertThat(isVirtual).isTrue();
    }

    @Test
    void mapperScanDoesNotRegisterNonMapperInterfacesAsBeans() {
        Map<String, LlmClient> clients = applicationContext.getBeansOfType(LlmClient.class);
        assertThat(clients).hasSize(2);
        assertThat(clients).doesNotContainKey("llmClient");
    }
}
