package io.tracedelta.provider;

import io.tracedelta.common.ApiResponse;
import jakarta.validation.Valid;
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

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
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
}
