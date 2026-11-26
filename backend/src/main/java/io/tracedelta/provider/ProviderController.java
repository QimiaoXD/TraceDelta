package io.tracedelta.provider;

import io.tracedelta.common.ApiResponse;
import io.tracedelta.provider.llm.LlmResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderService providerService;
    private final SmokeTestService smokeTestService;

    public ProviderController(ProviderService providerService, SmokeTestService smokeTestService) {
        this.providerService = providerService;
        this.smokeTestService = smokeTestService;
    }

    @PostMapping
    public ApiResponse<ProviderView> create(@Valid @RequestBody ProviderUpsertRequest request) {
        return ApiResponse.ok(providerService.create(request));
    }

    @GetMapping
    public ApiResponse<List<ProviderView>> list() {
        return ApiResponse.ok(providerService.list());
    }

    @PutMapping("/{id}")
    public ApiResponse<ProviderView> update(
            @PathVariable Long id, @Valid @RequestBody ProviderUpsertRequest request) {
        return ApiResponse.ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/smoke-test")
    public ApiResponse<LlmResponse> smokeTest(
            @PathVariable Long id, @Valid @RequestBody SmokeTestRequest request) {
        return ApiResponse.ok(smokeTestService.run(id, request.prompt(), request.model()));
    }

    public record SmokeTestRequest(@NotBlank String prompt, String model) {}
}
