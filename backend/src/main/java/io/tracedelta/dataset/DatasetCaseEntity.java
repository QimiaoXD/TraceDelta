package io.tracedelta.dataset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("dataset_case")
public class DatasetCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetVersionId;
    private String caseKey;
    private String inputJson;
    private String messagesJson;
    private String referenceAnswer;
    private String expectedDocIdsJson;
    private String assertionsJson;
    private String tagsJson;
    private String caseHash;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDatasetVersionId() {
        return datasetVersionId;
    }

    public void setDatasetVersionId(Long datasetVersionId) {
        this.datasetVersionId = datasetVersionId;
    }

    public String getCaseKey() {
        return caseKey;
    }

    public void setCaseKey(String caseKey) {
        this.caseKey = caseKey;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public void setMessagesJson(String messagesJson) {
        this.messagesJson = messagesJson;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public String getExpectedDocIdsJson() {
        return expectedDocIdsJson;
    }

    public void setExpectedDocIdsJson(String expectedDocIdsJson) {
        this.expectedDocIdsJson = expectedDocIdsJson;
    }

    public String getAssertionsJson() {
        return assertionsJson;
    }

    public void setAssertionsJson(String assertionsJson) {
        this.assertionsJson = assertionsJson;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public String getCaseHash() {
        return caseHash;
    }

    public void setCaseHash(String caseHash) {
        this.caseHash = caseHash;
    }
}
