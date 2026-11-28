package io.tracedelta.target.http;

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
@RequestMapping("/api/http-targets")
public class HttpTargetController {

    private final HttpTargetService httpTargetService;

    public HttpTargetController(HttpTargetService httpTargetService) {
        this.httpTargetService = httpTargetService;
    }

    @GetMapping
    public ApiResponse<List<HttpTargetView>> list() {
        return ApiResponse.ok(httpTargetService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<HttpTargetView> get(@PathVariable Long id) {
        return ApiResponse.ok(httpTargetService.getView(id));
    }

    @PostMapping
    public ApiResponse<HttpTargetView> create(@Valid @RequestBody HttpTargetUpsertRequest request) {
        return ApiResponse.ok(httpTargetService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<HttpTargetView> update(
            @PathVariable Long id,
            @Valid @RequestBody HttpTargetUpsertRequest request) {
        return ApiResponse.ok(httpTargetService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        httpTargetService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<HttpTargetSmokeTestView> smokeTest(
            @PathVariable Long id,
            @RequestBody(required = false) HttpTargetSmokeTestRequest request) {
        return ApiResponse.ok(httpTargetService.smokeTest(id, request));
    }
}
