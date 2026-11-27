package io.tracedelta.runner;

import io.tracedelta.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class EvalRunController {

    private final EvalRunService evalRunService;

    public EvalRunController(EvalRunService evalRunService) {
        this.evalRunService = evalRunService;
    }

    @PostMapping
    public ApiResponse<EvalRunDetailView> create(@Valid @RequestBody CreateEvalRunRequest request) {
        return ApiResponse.ok(evalRunService.createAndStart(request));
    }

    @GetMapping
    public ApiResponse<List<EvalRunSummaryView>> list(
            @RequestParam(required = false) EvalRunStatus status) {
        return ApiResponse.ok(evalRunService.listRuns(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<EvalRunDetailView> get(@PathVariable Long id) {
        return ApiResponse.ok(evalRunService.getRunDetail(id));
    }

    @GetMapping("/{id}/results")
    public ApiResponse<List<EvalResultView>> listResults(
            @PathVariable Long id,
            @RequestParam(required = false) EvalResultStatus status,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(evalRunService.listResults(id, status, q));
    }

    @GetMapping("/{id}/results/{resultId}/attempts")
    public ApiResponse<List<EvalAttemptView>> listAttempts(
            @PathVariable Long id,
            @PathVariable Long resultId) {
        return ApiResponse.ok(evalRunService.listAttempts(id, resultId));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<EvalRunDetailView> cancel(@PathVariable Long id) {
        return ApiResponse.ok(evalRunService.cancelRun(id));
    }
}
