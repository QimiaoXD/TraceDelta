package io.tracedelta.dataset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetService {

    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper versionMapper;
    private final DatasetCaseMapper caseMapper;
    private final CaseHashCalculator caseHashCalculator;
    private final ObjectMapper objectMapper;

    public DatasetService(
            DatasetMapper datasetMapper,
            DatasetVersionMapper versionMapper,
            DatasetCaseMapper caseMapper,
            CaseHashCalculator caseHashCalculator,
            ObjectMapper objectMapper) {
        this.datasetMapper = datasetMapper;
        this.versionMapper = versionMapper;
        this.caseMapper = caseMapper;
        this.caseHashCalculator = caseHashCalculator;
        this.objectMapper = objectMapper;
    }

    public DatasetEntity create(String name, String description) {
        DatasetEntity entity = new DatasetEntity();
        entity.setName(name.trim());
        entity.setDescription(description);
        entity.setCreatedAt(LocalDateTime.now());
        datasetMapper.insert(entity);
        return entity;
    }

    public List<DatasetEntity> list() {
        return datasetMapper.selectList(
                new LambdaQueryWrapper<DatasetEntity>().orderByDesc(DatasetEntity::getId));
    }

    @Transactional
    public DatasetVersionEntity importVersion(Long datasetId, List<DatasetCaseInput> cases, String note) {
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在: " + datasetId);
        }
        if (cases == null || cases.isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "导入用例列表不能为空");
        }

        Set<String> seenNormalizedKeys = new HashSet<>();
        for (DatasetCaseInput item : cases) {
            String normalizedKey = item.caseKey().trim().toLowerCase(Locale.ROOT);
            if (!seenNormalizedKeys.add(normalizedKey)) {
                throw new BizException(
                        ErrorCode.DUPLICATE_CASE_KEY, "导入批次内存在重复的 case_key: " + item.caseKey());
            }
        }

        DatasetVersionEntity latest = versionMapper.selectOne(
                new LambdaQueryWrapper<DatasetVersionEntity>()
                        .eq(DatasetVersionEntity::getDatasetId, datasetId)
                        .orderByDesc(DatasetVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        int nextVersionNo = (latest == null) ? 1 : latest.getVersionNo() + 1;

        DatasetVersionEntity version = new DatasetVersionEntity();
        version.setDatasetId(datasetId);
        version.setVersionNo(nextVersionNo);
        version.setNote(note);
        version.setCaseCount(cases.size());
        version.setCreatedAt(LocalDateTime.now());
        try {
            versionMapper.insert(version);
        } catch (DataIntegrityViolationException ex) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "版本号冲突，请重试");
        }

        for (DatasetCaseInput item : cases) {
            DatasetCaseEntity entity = new DatasetCaseEntity();
            entity.setDatasetVersionId(version.getId());
            entity.setCaseKey(item.caseKey().trim());
            entity.setInputJson(writeJson(item.input()));
            entity.setMessagesJson(item.messages() == null ? null : writeJson(item.messages()));
            entity.setReferenceAnswer(item.referenceAnswer());
            entity.setExpectedDocIdsJson(item.expectedDocIds() == null ? null : writeJson(item.expectedDocIds()));
            entity.setAssertionsJson(writeJson(item.assertions()));
            entity.setTagsJson(item.tags() == null ? null : writeJson(item.tags()));
            entity.setCaseHash(caseHashCalculator.compute(item));
            try {
                caseMapper.insert(entity);
            } catch (DataIntegrityViolationException ex) {
                throw new BizException(
                        ErrorCode.DUPLICATE_CASE_KEY, "导入批次内存在重复的 case_key: " + item.caseKey());
            }
        }

        return version;
    }

    public List<DatasetVersionEntity> listVersions(Long datasetId) {
        ensureDatasetExists(datasetId);
        return versionMapper.selectList(
                new LambdaQueryWrapper<DatasetVersionEntity>()
                        .eq(DatasetVersionEntity::getDatasetId, datasetId)
                        .orderByAsc(DatasetVersionEntity::getVersionNo));
    }

    public List<DatasetCaseEntity> listCases(Long datasetId, int versionNo) {
        ensureDatasetExists(datasetId);
        DatasetVersionEntity version = versionMapper.selectOne(
                new LambdaQueryWrapper<DatasetVersionEntity>()
                        .eq(DatasetVersionEntity::getDatasetId, datasetId)
                        .eq(DatasetVersionEntity::getVersionNo, versionNo));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集版本不存在: v" + versionNo);
        }
        return caseMapper.selectList(
                new LambdaQueryWrapper<DatasetCaseEntity>()
                        .eq(DatasetCaseEntity::getDatasetVersionId, version.getId())
                        .orderByAsc(DatasetCaseEntity::getId));
    }

    private void ensureDatasetExists(Long datasetId) {
        if (datasetMapper.selectById(datasetId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据集不存在: " + datasetId);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize case JSON", ex);
        }
    }
}
