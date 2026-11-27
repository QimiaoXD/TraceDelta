package io.tracedelta.runner;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class EvalRecoveryBootstrap {

    private final RunnerProperties properties;
    private final EvalRecoveryService recoveryService;

    public EvalRecoveryBootstrap(RunnerProperties properties, EvalRecoveryService recoveryService) {
        this.properties = properties;
        this.recoveryService = recoveryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isRecoveryOnStartup()) {
            recoveryService.recoverInterruptedRuns();
        }
    }
}
