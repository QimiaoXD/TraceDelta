package io.tracedelta.dataset;

import io.tracedelta.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetService datasetService;
    private final DatasetImportParser importParser;

    public DatasetController(DatasetService datasetService, DatasetImportParser importParser) {
        this.datasetService = datasetService;
        this.importParser = importParser;
    }

    @PostMapping
    public ApiResponse<DatasetEntity> create(@Valid @RequestBody CreateDatasetRequest request) {
        return ApiResponse.ok(datasetService.create(request.name(), request.description()));
    }

    @GetMapping
    public ApiResponse<List<DatasetEntity>> list() {
        return ApiResponse.ok(datasetService.list());
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<DatasetVersionEntity>> listVersions(@PathVariable Long id) {
        return ApiResponse.ok(datasetService.listVersions(id));
    }

    @PostMapping("/{id}/versions/import")
    public ApiResponse<DatasetVersionEntity> importVersion(
            @PathVariable Long id, @Valid @RequestBody ImportVersionRequest request) {
        List<DatasetCaseInput> cases = importParser.parse(request.format(), request.content());
        return ApiResponse.ok(datasetService.importVersion(id, cases, request.note()));
    }

    @GetMapping("/{id}/versions/{versionNo}/cases")
    public ApiResponse<List<DatasetCaseEntity>> listCases(
            @PathVariable Long id, @PathVariable int versionNo) {
        return ApiResponse.ok(datasetService.listCases(id, versionNo));
    }

    public record CreateDatasetRequest(@NotBlank String name, String description) {}

    public record ImportVersionRequest(
            @NotBlank String format, @NotBlank String content, String note) {}
}
