export type ImportFormat = 'JSONL' | 'JSON' | 'CSV'

export interface ImportIssue {
  line: number
  column?: number
  message: string
}

export interface ParsedCasePreview {
  line: number
  caseKey: string
  assertionCount: number
}

export interface ImportValidationResult {
  valid: boolean
  issues: ImportIssue[]
  cases: ParsedCasePreview[]
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function indexToLineCol(content: string, index: number): { line: number; column: number } {
  const safeIdx = Math.max(0, Math.min(index, content.length))
  let line = 1
  let lastNewline = -1
  for (let i = 0; i < safeIdx; i++) {
    if (content.charCodeAt(i) === 10) {
      line++
      lastNewline = i
    }
  }
  return { line, column: safeIdx - lastNewline }
}

function extractJsonErrorLocation(
  err: unknown,
  content: string,
  fallbackLine: number,
): { line: number; column?: number } {
  const msg = err instanceof Error ? err.message : ''
  const lineColMatch = msg.match(/line\s+(\d+)\s+column\s+(\d+)/i)
  if (lineColMatch) {
    return { line: Number(lineColMatch[1]), column: Number(lineColMatch[2]) }
  }
  const posMatch = msg.match(/position\s+(\d+)/i)
  if (posMatch) {
    return indexToLineCol(content, Number(posMatch[1]))
  }
  return { line: fallbackLine }
}

/**
 * Find physical line numbers for each top-level element of a JSON array.
 * Used purely to map schema errors on element i back to a line in the editor.
 */
function locateJsonArrayElementLines(content: string): number[] {
  const lines: number[] = []
  let depth = 0
  let inString = false
  let escaped = false
  let line = 1
  let expectingElement = false

  for (let i = 0; i < content.length; i++) {
    const ch = content[i]
    if (ch === '\n') {
      line++
      continue
    }
    if (inString) {
      if (escaped) {
        escaped = false
      } else if (ch === '\\') {
        escaped = true
      } else if (ch === '"') {
        inString = false
      }
      continue
    }
    if (ch === ' ' || ch === '\t' || ch === '\r') continue

    if (depth === 0) {
      if (ch === '[') {
        depth = 1
        expectingElement = true
      }
      continue
    }

    if (depth === 1 && expectingElement && ch !== ']') {
      lines.push(line)
      expectingElement = false
    }

    if (ch === '"') {
      inString = true
    } else if (ch === '{' || ch === '[') {
      depth++
    } else if (ch === '}' || ch === ']') {
      depth--
    } else if (ch === ',' && depth === 1) {
      expectingElement = true
    }
  }
  return lines
}

function validateCaseObject(
  node: unknown,
  line: number,
  seenKeys: Map<string, number>,
  issues: ImportIssue[],
  cases: ParsedCasePreview[],
) {
  if (!isPlainObject(node)) {
    issues.push({ line, message: '每条用例必须是 JSON 对象' })
    return
  }

  let hasError = false

  const rawKey = node.case_key
  const caseKey = typeof rawKey === 'string' ? rawKey.trim() : ''
  if (!caseKey) {
    issues.push({ line, message: '缺少必填字段 case_key，或 case_key 为空字符串' })
    hasError = true
  } else {
    const normalized = caseKey.toLowerCase()
    const firstLine = seenKeys.get(normalized)
    if (firstLine !== undefined) {
      issues.push({
        line,
        message: `case_key「${caseKey}」与第 ${firstLine} 行重复（忽略大小写）`,
      })
      hasError = true
    } else {
      seenKeys.set(normalized, line)
    }
  }

  if (!isPlainObject(node.input)) {
    issues.push({ line, message: '缺少必填字段 input，或 input 不是 JSON 对象' })
    hasError = true
  }

  if (node.expected_doc_ids !== undefined && node.expected_doc_ids !== null && !Array.isArray(node.expected_doc_ids)) {
    issues.push({ line, message: 'expected_doc_ids 必须是数组' })
    hasError = true
  }

  if (node.tags !== undefined && node.tags !== null && !Array.isArray(node.tags)) {
    issues.push({ line, message: 'tags 必须是数组' })
    hasError = true
  }

  let assertionCount = 0
  if (node.assertions !== undefined && node.assertions !== null) {
    if (!Array.isArray(node.assertions)) {
      issues.push({ line, message: 'assertions 必须是数组' })
      hasError = true
    } else {
      assertionCount = node.assertions.length
      node.assertions.forEach((item, idx) => {
        if (!isPlainObject(item)) {
          issues.push({ line, message: `assertions[${idx}] 必须是 JSON 对象` })
          hasError = true
        } else if (typeof item.type !== 'string' || !item.type.trim()) {
          issues.push({ line, message: `assertions[${idx}] 缺少非空的 type 字段` })
          hasError = true
        }
      })
    }
  }

  if (!hasError) {
    cases.push({ line, caseKey, assertionCount })
  }
}

function validateJsonl(content: string): ImportValidationResult {
  const lines = content.split(/\r?\n/)
  const issues: ImportIssue[] = []
  const cases: ParsedCasePreview[] = []
  const seenKeys = new Map<string, number>()

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i]
    const trimmed = raw.trim()
    if (!trimmed) continue
    const lineNo = i + 1
    try {
      const parsed = JSON.parse(trimmed)
      validateCaseObject(parsed, lineNo, seenKeys, issues, cases)
    } catch (err) {
      const loc = extractJsonErrorLocation(err, trimmed, 1)
      issues.push({
        line: lineNo,
        column: loc.column,
        message: 'JSON 语法非法，请检查引号、逗号或括号闭合',
      })
    }
  }

  if (issues.length === 0 && cases.length === 0) {
    issues.push({ line: 1, message: '未解析到任何有效用例' })
  }

  return { valid: issues.length === 0 && cases.length > 0, issues, cases }
}

function validateJson(content: string): ImportValidationResult {
  const issues: ImportIssue[] = []
  const cases: ParsedCasePreview[] = []
  const seenKeys = new Map<string, number>()

  let parsed: unknown
  try {
    parsed = JSON.parse(content)
  } catch (err) {
    const loc = extractJsonErrorLocation(err, content, 1)
    issues.push({
      line: loc.line,
      column: loc.column,
      message: 'JSON 语法非法，请检查引号、逗号或括号闭合',
    })
    return { valid: false, issues, cases }
  }

  if (!Array.isArray(parsed)) {
    issues.push({ line: 1, column: 1, message: 'JSON 顶层必须是数组（[...]）' })
    return { valid: false, issues, cases }
  }

  if (parsed.length === 0) {
    issues.push({ line: 1, message: 'JSON 数组不能为空，至少包含 1 条用例' })
    return { valid: false, issues, cases }
  }

  const elemLines = locateJsonArrayElementLines(content)
  parsed.forEach((item, idx) => {
    const lineNo = elemLines[idx] ?? idx + 1
    validateCaseObject(item, lineNo, seenKeys, issues, cases)
  })

  return { valid: issues.length === 0 && cases.length > 0, issues, cases }
}

interface CsvRecord {
  startLine: number
  endLine: number
  cols: string[]
  hasEmbeddedNewline: boolean
}

/**
 * RFC 4180 CSV parser with physical line tracking.
 * Also flags quoted embedded newlines because the M1 backend CSV parser splits on line breaks.
 */
function parseCsvRecords(content: string): { records: CsvRecord[]; unclosedLine?: number } {
  const records: CsvRecord[] = []
  let cols: string[] = []
  let current = ''
  let inQuotes = false
  let line = 1
  let recordStartLine = 1
  let hasEmbeddedNewline = false
  let hasAnyCharInRecord = false

  for (let i = 0; i < content.length; i++) {
    const ch = content[i]
    if (!hasAnyCharInRecord && ch !== '\r' && ch !== '\n') {
      recordStartLine = line
      hasAnyCharInRecord = true
    }

    if (inQuotes) {
      if (ch === '"') {
        if (i + 1 < content.length && content[i + 1] === '"') {
          current += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        if (ch === '\n') {
          line++
          hasEmbeddedNewline = true
        }
        current += ch
      }
      continue
    }

    if (ch === '"') {
      inQuotes = true
    } else if (ch === ',') {
      cols.push(current)
      current = ''
    } else if (ch === '\r') {
      if (i + 1 < content.length && content[i + 1] === '\n') {
        i++
      }
      cols.push(current)
      if (cols.some((c) => c.trim() !== '')) {
        records.push({ startLine: recordStartLine, endLine: line, cols, hasEmbeddedNewline })
      }
      cols = []
      current = ''
      hasEmbeddedNewline = false
      hasAnyCharInRecord = false
      line++
    } else if (ch === '\n') {
      cols.push(current)
      if (cols.some((c) => c.trim() !== '')) {
        records.push({ startLine: recordStartLine, endLine: line, cols, hasEmbeddedNewline })
      }
      cols = []
      current = ''
      hasEmbeddedNewline = false
      hasAnyCharInRecord = false
      line++
    } else {
      current += ch
    }
  }

  if (inQuotes) {
    return { records, unclosedLine: recordStartLine }
  }

  if (hasAnyCharInRecord || cols.length > 0 || current.length > 0) {
    cols.push(current)
    if (cols.some((c) => c.trim() !== '')) {
      records.push({ startLine: recordStartLine, endLine: line, cols, hasEmbeddedNewline })
    }
  }

  return { records }
}

function validateCsv(content: string): ImportValidationResult {
  const issues: ImportIssue[] = []
  const cases: ParsedCasePreview[] = []
  const { records, unclosedLine } = parseCsvRecords(content)

  if (unclosedLine !== undefined) {
    issues.push({ line: unclosedLine, message: 'CSV 双引号未闭合' })
    return { valid: false, issues, cases }
  }

  if (records.length === 0) {
    issues.push({ line: 1, message: 'CSV 内容为空' })
    return { valid: false, issues, cases }
  }

  const header = records[0]
  if (
    header.cols.length !== 2 ||
    header.cols[0].trim() !== 'question' ||
    header.cols[1].trim() !== 'reference_answer'
  ) {
    issues.push({
      line: header.startLine,
      message: 'CSV 表头必须严格为两列：question,reference_answer',
    })
    return { valid: false, issues, cases }
  }

  let seq = 0
  for (let i = 1; i < records.length; i++) {
    const rec = records[i]
    if (rec.hasEmbeddedNewline) {
      issues.push({
        line: rec.startLine,
        message: '单元格包含换行符：当前服务端 CSV 解析按行拆分，建议移除换行或改用 JSONL/JSON',
      })
      continue
    }
    if (rec.cols.length !== 2) {
      issues.push({
        line: rec.startLine,
        message: `CSV 列数为 ${rec.cols.length}，必须为 2 列（含逗号的内容请用双引号包裹）`,
      })
      continue
    }
    if (!rec.cols[0].trim()) {
      issues.push({ line: rec.startLine, message: 'question 列不能为空' })
      continue
    }
    seq++
    cases.push({
      line: rec.startLine,
      caseKey: `case-${String(seq).padStart(4, '0')}`,
      assertionCount: 0,
    })
  }

  if (issues.length === 0 && cases.length === 0) {
    issues.push({ line: header.startLine, message: 'CSV 表头下方没有数据行' })
  }

  return { valid: issues.length === 0 && cases.length > 0, issues, cases }
}

export function validateDatasetImport(
  format: ImportFormat,
  content: string,
): ImportValidationResult {
  if (!content.trim()) {
    return {
      valid: false,
      issues: [{ line: 1, message: '请输入或粘贴待导入内容' }],
      cases: [],
    }
  }
  if (format === 'JSONL') return validateJsonl(content)
  if (format === 'JSON') return validateJson(content)
  return validateCsv(content)
}
