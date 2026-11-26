package io.tracedelta.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DatasetImportParser {

    private final ObjectMapper objectMapper;

    public DatasetImportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<DatasetCaseInput> parse(String format, String content) {
        if (format == null || format.isBlank()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "导入格式不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "导入内容不能为空");
        }
        return switch (format.trim().toUpperCase(Locale.ROOT)) {
            case "JSON" -> parseJson(content);
            case "JSONL" -> parseJsonl(content);
            case "CSV" -> parseCsv(content);
            default -> throw new BizException(ErrorCode.IMPORT_INVALID, "不支持的导入格式: " + format);
        };
    }

    private List<DatasetCaseInput> parseJson(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "第 1 行：JSON 格式非法");
        }
        if (!root.isArray()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "第 1 行：JSON 顶层必须是数组");
        }
        List<DatasetCaseInput> result = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            result.add(parseCaseNode(root.get(i), i + 1));
        }
        return result;
    }

    private List<DatasetCaseInput> parseJsonl(String content) {
        String[] lines = content.split("\\r?\\n");
        List<DatasetCaseInput> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            JsonNode node;
            try {
                node = objectMapper.readTree(line);
            } catch (JsonProcessingException ex) {
                throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：JSON 非法");
            }
            result.add(parseCaseNode(node, lineNo));
        }
        return result;
    }

    private List<DatasetCaseInput> parseCsv(String content) {
        String[] lines = content.split("\\r?\\n");
        int headerIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "第 1 行：CSV 内容为空");
        }
        List<String> headerCols = splitCsvLine(lines[headerIndex], headerIndex + 1);
        if (headerCols.size() != 2
                || !"question".equals(headerCols.get(0).trim())
                || !"reference_answer".equals(headerCols.get(1).trim())) {
            throw new BizException(
                    ErrorCode.IMPORT_INVALID,
                    "第 " + (headerIndex + 1) + " 行：CSV 表头必须为 question,reference_answer");
        }
        List<DatasetCaseInput> result = new ArrayList<>();
        int seq = 0;
        for (int i = headerIndex + 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            List<String> cols = splitCsvLine(lines[i], lineNo);
            if (cols.size() != 2) {
                throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：CSV 列数必须为 2");
            }
            String question = cols.get(0);
            if (question.isBlank()) {
                throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：question 不能为空");
            }
            String refAnswer = cols.get(1);
            seq++;
            ObjectNode inputObj = objectMapper.createObjectNode();
            inputObj.put("question", question);
            result.add(new DatasetCaseInput(
                    String.format("case-%04d", seq),
                    inputObj,
                    null,
                    refAnswer.isEmpty() ? null : refAnswer,
                    null,
                    List.of(),
                    null));
        }
        return result;
    }

    private List<String> splitCsvLine(String line, int lineNo) {
        List<String> cols = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    cols.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
        }
        if (inQuotes) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：CSV 引号未闭合");
        }
        cols.add(current.toString());
        return cols;
    }

    private DatasetCaseInput parseCaseNode(JsonNode node, int lineNo) {
        if (node == null || !node.isObject()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：每条用例必须是 JSON 对象");
        }
        String caseKey = node.hasNonNull("case_key") ? node.get("case_key").asText() : null;
        JsonNode input = node.get("input");
        JsonNode messages = node.hasNonNull("messages") ? node.get("messages") : null;
        String referenceAnswer = node.hasNonNull("reference_answer")
                ? node.get("reference_answer").asText()
                : null;
        List<String> expectedDocIds = parseStringList(node.get("expected_doc_ids"), "expected_doc_ids", lineNo);
        List<String> tags = parseStringList(node.get("tags"), "tags", lineNo);
        List<CaseAssertion> assertions = parseAssertions(node.get("assertions"), lineNo);

        try {
            return new DatasetCaseInput(
                    caseKey, input, messages, referenceAnswer, expectedDocIds, assertions, tags);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：" + ex.getMessage());
        }
    }

    private List<String> parseStringList(JsonNode node, String fieldName, int lineNo) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new BizException(
                    ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：" + fieldName + " 必须是数组");
        }
        List<String> list = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            list.add(item.asText());
        }
        return list;
    }

    private List<CaseAssertion> parseAssertions(JsonNode node, int lineNo) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：assertions 必须是数组");
        }
        List<CaseAssertion> list = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：断言项必须是对象");
            }
            String type = item.hasNonNull("type") ? item.get("type").asText() : null;
            JsonNode expected = item.get("expected");
            JsonNode params = item.get("params");
            try {
                list.add(new CaseAssertion(type, expected, params));
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.IMPORT_INVALID, "第 " + lineNo + " 行：" + ex.getMessage());
            }
        }
        return list;
    }
}
