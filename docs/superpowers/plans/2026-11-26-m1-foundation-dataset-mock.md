# TraceDelta M1（骨架、鉴权、数据集版本与 Mock 渠道）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 TraceDelta 的单机开发基础设施、Spring Boot 3 / Java 21 模块化后端骨架、JWT 登录、不可变版本数据集、加密的模型渠道与可编程确定性 Mock 渠道、轻量 OpenAI 兼容客户端，以及 Vue 3 基础控制台，跑通「建数据集 → 配渠道/Mock → 单次同步调用」闭环。

**Architecture:** 纯 Java 模块化单体（`io.tracedelta.{common,auth,dataset,provider}`）。MyBatis-Plus 访问 MySQL 8，Flyway 管理表结构（SQL 写成 MySQL 与 H2 兼容，测试用 H2 的 MySQL 模式，无需 Docker 即可全绿）。LLM 调用走自封装的 `LlmClient`（Mock 与 OpenAI 兼容两种实现）。前端 Vue 3 + Element Plus 通过 `/api` 代理访问后端。每个后端任务同步写 JUnit 5 测试。

**Tech Stack:** Java 21、Spring Boot 3.4.x、Maven、Spring Security（仅用于过滤链与 BCrypt）、jjwt 0.12.x、MyBatis-Plus（`mybatis-plus-spring-boot3-starter` 3.5.x）、Flyway、MySQL 8、H2（仅测试）、JUnit 5 + Mockito + OkHttp MockWebServer、Vue 3 + TypeScript + Vite + Element Plus + axios + pinia + vue-router、Docker Compose（MySQL 8 + Redis 7）。

**Spec:** `docs/superpowers/specs/2026-11-26-tracedelta-design.md`（执行者需同时阅读，尤其第 4.5、4.6、4.9、4.10、4.11 节）

> 说明：M1 只覆盖 spec 第 7 节「第 0–2 周」范围。并发引擎（M2）、Trace（M3）、评分与 Diff（M4）、靶场与收尾（M5）各有独立计划，Redis 在 M1 仅在 Docker Compose 中启动，后端暂不依赖 Redis（YAGNI）。

## Global Constraints

- Java 21，Spring Boot 3，纯模块化单体；不拆微服务，不引入 ClickHouse / ES / 独立向量库。
- 所有表结构变更只走 Flyway：`backend/src/main/resources/db/migration/V*.sql`，SQL 必须同时兼容 MySQL 8 与 H2（MySQL 模式）：不使用 MySQL 专有的 `JSON` 列类型，JSON 一律存 `LONGTEXT`。
- 上游 API Key 用 AES-GCM 加密落库，主密钥只来自环境变量 `TRACEDELTA_MASTER_KEY`（Base64，解码后正好 32 字节），不进仓库。
- 数据集版本不可变：编辑即生成新版本；用例字段：`case_key`、`input`（JSON 对象，默认变量名 `question`）、可选 `messages`、可选 `reference_answer`、可选 `expected_doc_ids`、`assertions`（`{type, expected, params}` 列表）、`tags`。
- `case_hash` = 对 `input`、`reference_answer`、`expected_doc_ids`、`assertions` 做规范化 JSON（键排序）后的 SHA-256 十六进制。（spec 未列出 `messages`，但它是 input 的一部分，本计划将其一并计入哈希。）
- 导入格式：JSON（数组）、JSONL；CSV 仅支持两列「问题 + 参考答案」。
- Mock 渠道零费用、确定性；`mock-flaky` 的故障由 `(seed, case_key, attempt_no)` 决定；档位 `mock-good` / `mock-sloppy` / `mock-slow` / `mock-flaky`。
- 鉴权：基础 JWT + 种子管理员，不做 RBAC。
- 单元测试随每个任务同步编写。项目名 `TraceDelta`，Java 包根 `io.tracedelta`。
- 提交信息用英文 conventional commits；每个任务结束提交一次。

## Review Focus

1. 主密钥 `TRACEDELTA_MASTER_KEY` 缺失、为空、不是合法 Base64 或不是 32 字节：必须启动即失败，不得回退默认密钥。→ Task 2 测试。
2. 一次导入里出现重复 `case_key`：整批回滚，返回 HTTP 400 并指出重复的 `case_key`，库里不留半截数据。→ Task 4 测试。
3. JSON 键顺序不同的两个等价 `input`：`case_hash` 必须相同；改动 `reference_answer` / `expected_doc_ids` / `assertions` 任一项必须使哈希改变。→ Task 3 测试。
4. 渠道列表/详情绝不返回明文 Key 或密文；更新渠道时不传 `apiKey` 必须保留原密文，不能被清空或被掩码覆盖。→ Task 5 测试。
5. `mock-flaky`：相同 `(seed, case_key, attempt_no)` 必须得到完全相同的结果，且 `attempt_no` 递增到 3 时必然成功，保证重试可验证。→ Task 6 测试。

---

## File Structure

路径简写：`MAIN` = `backend/src/main/java/io/tracedelta`，`TEST` = `backend/src/test/java/io/tracedelta`，`RES` = `backend/src/main/resources`。

**根目录**
- `docker-compose.yml`：MySQL 8.0 + Redis 7-alpine，带健康检查。
- `.env.example`：所有环境变量示例（数据库、`TRACEDELTA_MASTER_KEY`、`TRACEDELTA_JWT_SECRET`、`TRACEDELTA_ADMIN_PASSWORD`）。

**后端 `backend/`**
- `pom.xml`：依赖与 Java 21 配置。
- `RES/application.yml` / `backend/src/test/resources/application-test.yml`：运行配置 / 测试配置（H2 + 测试密钥）。
- `RES/db/migration/V1__init_auth.sql`、`V2__init_dataset.sql`、`V3__init_provider.sql`：建表。
- `MAIN/TraceDeltaApplication.java`：入口。
- `MAIN/common/`：`ApiResponse`、`ErrorCode`、`BizException`、`GlobalExceptionHandler`、`VirtualThreadConfig`（虚拟线程执行器 Bean）、`JacksonConfig`（统一 ObjectMapper）。
- `MAIN/auth/`：`AesGcmEncryptor`、`EncryptionConfig`、`UserEntity`、`UserMapper`、`JwtTokenService`、`JwtAuthFilter`、`SecurityConfig`、`AuthService`、`AdminSeedInitializer`、`AuthController`。
- `MAIN/dataset/`：`DatasetEntity`、`DatasetVersionEntity`、`DatasetCaseEntity`、三个 `*Mapper`、`CaseAssertion`、`DatasetCaseInput`、`CaseHashCalculator`、`DatasetImportParser`、`DatasetService`、`DatasetController`。
- `MAIN/provider/`：`ProviderType`、`ProviderConfigEntity`、`ProviderConfigMapper`、`ProviderUpsertRequest`、`ProviderView`、`ProviderService`、`ProviderController`、`llm/{LlmMessage,LlmRequest,LlmResponse,LlmErrorType,LlmClient,MockProfile,MockLlmClient,OpenAiCompatibleLlmClient,LlmClientRouter}`、`SmokeTestService`。
- `TEST/` 下按同包结构放对应 `*Test`。

**前端 `frontend/`**
- `package.json`、`vite.config.ts`（`/api` 代理到 `http://localhost:8080`）、`tsconfig.json`、`index.html`、`src/main.ts`。
- `src/api/{http,auth,dataset,provider}.ts`：axios 封装与接口函数。
- `src/stores/auth.ts`：登录态（token 存 localStorage）。
- `src/router/index.ts`：路由与登录守卫。
- `src/layouts/MainLayout.vue`：侧边栏布局。
- `src/views/{LoginView,DatasetsView,DatasetDetailView,ProvidersView}.vue`。

---

### Task 0: 本机环境验证与基础设施（MySQL + Redis）

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`

**Interfaces:**
- Produces: 本机 `localhost:3306` 的 MySQL（库名 `tracedelta`，用户 `tracedelta`，密码取自 `.env`）与 `localhost:6379` 的 Redis；`.env.example` 中的变量名供后续任务引用：`TRACEDELTA_DB_URL`、`TRACEDELTA_DB_USER`、`TRACEDELTA_DB_PASSWORD`、`TRACEDELTA_MASTER_KEY`、`TRACEDELTA_JWT_SECRET`、`TRACEDELTA_ADMIN_PASSWORD`。

- [ ] **Step 1: 检查本机工具**

Run: `java -version; mvn -v; docker compose version; node -v`
Expected: Java 版本主号为 21，Maven ≥ 3.9，Docker Compose v2 可用，Node ≥ 20。缺什么装什么：`brew install openjdk@21 maven`（装完按 brew 提示把 JDK 21 加入 `JAVA_HOME`），Docker 装 Docker Desktop 并启动。全部通过再往下。

- [ ] **Step 2: 编写 `docker-compose.yml` 与 `.env.example`**

`docker-compose.yml` 含两个服务：`mysql`（镜像 `mysql:8.0`，字符集 `utf8mb4`，环境变量取自 `.env`，命名卷持久化，`healthcheck` 用 `mysqladmin ping`）与 `redis`（`redis:7-alpine`，`healthcheck` 用 `redis-cli ping`）。`.env.example` 给出各变量示例值，并在注释里说明 `TRACEDELTA_MASTER_KEY` 用 `openssl rand -base64 32` 生成、`TRACEDELTA_JWT_SECRET` 至少 32 字符。

- [ ] **Step 3: 启动并验证**

Run: `cp .env.example .env && docker compose up -d && docker compose ps`
Expected: `mysql` 与 `redis` 两个服务状态均为 `healthy`（等待约 30 秒）。

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml .env.example
git commit -m "chore: add docker compose for mysql and redis"
```

---

### Task 1: 后端骨架与统一响应/异常处理

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application-test.yml`
- Create: `MAIN/TraceDeltaApplication.java`
- Create: `MAIN/common/{ApiResponse,ErrorCode,BizException,GlobalExceptionHandler,VirtualThreadConfig,JacksonConfig}.java`
- Test: `TEST/TraceDeltaApplicationTests.java`、`TEST/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces:
  - `record ApiResponse<T>(boolean success, String code, String message, T data)`，静态方法 `ok(T)`、`ok()`、`error(String code, String message)`。
  - `enum ErrorCode`：`VALIDATION_ERROR`、`UNAUTHORIZED`、`NOT_FOUND`、`DUPLICATE_CASE_KEY`、`IMPORT_INVALID`、`PROVIDER_ERROR`，各带 `httpStatus`（400/401/404/400/400/400）。
  - `class BizException extends RuntimeException`：构造器 `BizException(ErrorCode code, String message)`，`ErrorCode getCode()`。
  - `GlobalExceptionHandler`：`BizException` → 其 `httpStatus` + `ApiResponse.error`；`MethodArgumentNotValidException` → 400 `VALIDATION_ERROR`；其他未捕获异常 → 500 `INTERNAL_ERROR`（不泄露堆栈）。
  - Bean `ExecutorService virtualThreadExecutor`（`Executors.newVirtualThreadPerTaskExecutor()`）。
  - `application.yml` 中配置项：`tracedelta.security.master-key=${TRACEDELTA_MASTER_KEY}`、`tracedelta.security.jwt-secret=${TRACEDELTA_JWT_SECRET}`、`tracedelta.security.jwt-ttl-minutes=720`、`tracedelta.admin.password=${TRACEDELTA_ADMIN_PASSWORD}`，数据源三项取自 `TRACEDELTA_DB_*`，`spring.threads.virtual.enabled=true`，Flyway 启用。
  - `application-test.yml`：H2 `jdbc:h2:mem:td;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1`，固定的测试用 master-key（32 字节 Base64）与 jwt-secret、admin 密码 `test-admin-pass`。

- [ ] **Step 1: 写失败测试**

`TraceDeltaApplicationTests`：`@SpringBootTest @ActiveProfiles("test")`，`contextLoads()` 通过，并断言注入的 `ExecutorService virtualThreadExecutor` 提交的任务在虚拟线程中运行（`Thread.currentThread().isVirtual()` 为 `true`）。
`GlobalExceptionHandlerTest`：用 `@WebMvcTest` + 一个仅测试用的控制器，断言：抛 `BizException(NOT_FOUND, "x")` → HTTP 404 且 JSON `success=false, code="NOT_FOUND", message="x"`；校验失败的请求体 → HTTP 400 `code="VALIDATION_ERROR"`；抛 `RuntimeException("boom")` → HTTP 500 `code="INTERNAL_ERROR"` 且响应体不包含 `boom`。

- [ ] **Step 2: 确认失败**

Run: `cd backend && mvn -q test`
Expected: 失败（编译错误：类不存在，或尚无 `pom.xml`）。

- [ ] **Step 3: 创建 `pom.xml` 与上述类**

`pom.xml` 以 `spring-boot-starter-parent` 3.4.x 为父，`java.version=21`，依赖：web、validation、security、jdbc、mybatis-plus-spring-boot3-starter、flyway-core + flyway-mysql、mysql-connector-j、jjwt-api/impl/jackson、h2（test scope）、spring-boot-starter-test、spring-security-test、`com.squareup.okhttp3:mockwebserver`（test scope）。其余按 Interfaces 实现。

- [ ] **Step 4: 确认通过**

Run: `cd backend && mvn -q test`
Expected: `BUILD SUCCESS`，两个测试类全部通过。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add spring boot skeleton with unified api response and error handling"
```

---

### Task 2: AES-GCM 加密器与 JWT 登录

**Files:**
- Create: `RES/db/migration/V1__init_auth.sql`
- Create: `MAIN/auth/{AesGcmEncryptor,EncryptionConfig,UserEntity,UserMapper,JwtTokenService,JwtAuthFilter,SecurityConfig,AuthService,AdminSeedInitializer,AuthController}.java`
- Test: `TEST/auth/{AesGcmEncryptorTest,JwtTokenServiceTest,AuthControllerTest}.java`

**Interfaces:**
- Consumes: Task 1 的 `ApiResponse`、`BizException`、`ErrorCode.UNAUTHORIZED`、`application.yml` 的 `tracedelta.security.*` 与 `tracedelta.admin.password`。
- Produces:
  - `V1__init_auth.sql`：表 `sys_user(id BIGINT AUTO_INCREMENT PK, username VARCHAR(64) UNIQUE NOT NULL, password_hash VARCHAR(100) NOT NULL, created_at DATETIME NOT NULL)`。
  - `class AesGcmEncryptor`：构造器 `AesGcmEncryptor(String base64Key)`（缺失/空白/非法 Base64/解码后不是 32 字节 → 抛 `IllegalStateException`，消息中不含密钥内容）；`String encrypt(String plaintext)`（每次随机 12 字节 IV，输出 Base64(IV‖密文‖tag)）；`String decrypt(String ciphertext)`（被篡改 → 抛 `IllegalArgumentException`）。
  - `EncryptionConfig`：把 `tracedelta.security.master-key` 构造成 `AesGcmEncryptor` Bean（构造失败即应用启动失败）。
  - `class JwtTokenService`：构造器 `JwtTokenService(String secret, long ttlMinutes)` 与可注入时钟的 `JwtTokenService(String secret, long ttlMinutes, java.time.Clock clock)`（secret 少于 32 字符 → `IllegalStateException`）；`String issue(String username)`；`Optional<String> verify(String token)`（返回 username；过期/篡改返回 empty）。
  - REST：`POST /api/auth/login` 请求 `{username, password}`（均 `@NotBlank`）→ `ApiResponse<{token, username}>`，错误凭据 → 401 `UNAUTHORIZED`；`GET /api/auth/me` → `ApiResponse<{username}>`。
  - `SecurityConfig`：无状态；放行 `/api/auth/login`、`/actuator/health`；其余 `/api/**` 需有效 `Authorization: Bearer <token>`，否则 401（JSON `ApiResponse`）。
  - `AdminSeedInitializer`：启动时若无用户 `admin`，则以 BCrypt 存入密码 `tracedelta.admin.password`。

- [ ] **Step 1: 写失败测试**

`AesGcmEncryptorTest`：
- `encryptThenDecryptRoundTrips`：`decrypt(encrypt("sk-abc123")).equals("sk-abc123")`。
- `sameInputProducesDifferentCiphertext`：同一明文两次 `encrypt` 结果不相等。
- `tamperedCiphertextIsRejected`：改动密文最后一个字符后 `decrypt` 抛 `IllegalArgumentException`。
- **Review Focus 1** `rejectsMissingOrInvalidMasterKey`：参数化覆盖 `null`、`""`、`"   "`、`"not-base64!!"`、合法 Base64 但仅 16 字节，均抛 `IllegalStateException`，且 `getMessage()` 不包含传入的密钥字符串。

`JwtTokenServiceTest`：`issueThenVerifyReturnsUsername`；`tamperedTokenReturnsEmpty`；`expiredTokenReturnsEmpty`（用固定时钟签发，再用快进 ttl+1 分钟的时钟验证）；`shortSecretIsRejected`（`IllegalStateException`）。

`AuthControllerTest`（`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`）：
- `loginWithSeedAdminReturnsToken`：`admin` / `test-admin-pass` → 200，`data.token` 非空。
- `loginWithWrongPasswordReturns401`。
- `meWithoutTokenReturns401`；`meWithTokenReturnsUsername`（`data.username == "admin"`）。
- `loginWithBlankFieldsReturns400`。

- [ ] **Step 2: 确认失败**

Run: `cd backend && mvn -q -Dtest='AesGcmEncryptorTest,JwtTokenServiceTest,AuthControllerTest' test`
Expected: 失败（类不存在）。

- [ ] **Step 3: 实现**

按 Interfaces 实现；`JwtAuthFilter` 解析 Bearer token，成功则写入 `SecurityContext`；认证入口返回 401 JSON。

- [ ] **Step 4: 确认通过**

Run: `cd backend && mvn -q test`
Expected: `BUILD SUCCESS`，含 Task 1 的测试一并通过。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add aes-gcm encryptor and jwt login"
```

---

### Task 3: 数据集模型、规范化 case_hash 与导入解析

**Files:**
- Create: `RES/db/migration/V2__init_dataset.sql`
- Create: `MAIN/dataset/{DatasetEntity,DatasetVersionEntity,DatasetCaseEntity,CaseAssertion,DatasetCaseInput,CaseHashCalculator,DatasetImportParser}.java`
- Test: `TEST/dataset/{CaseHashCalculatorTest,DatasetImportParserTest}.java`

**Interfaces:**
- Consumes: Task 1 的 `BizException`、`ErrorCode.IMPORT_INVALID`、`JacksonConfig` 的 `ObjectMapper`。
- Produces:
  - `V2__init_dataset.sql`：
    - `dataset(id, name VARCHAR(128) NOT NULL, description VARCHAR(500), created_at)`；
    - `dataset_version(id, dataset_id, version_no INT, note VARCHAR(255), case_count INT, created_at)`，唯一索引 `(dataset_id, version_no)`；
    - `dataset_case(id, dataset_version_id, case_key VARCHAR(128), input_json LONGTEXT NOT NULL, messages_json LONGTEXT NULL, reference_answer LONGTEXT NULL, expected_doc_ids_json LONGTEXT NULL, assertions_json LONGTEXT NOT NULL, tags_json LONGTEXT NULL, case_hash CHAR(64) NOT NULL)`，唯一索引 `(dataset_version_id, case_key)`。
  - `record CaseAssertion(String type, JsonNode expected, JsonNode params)`。
  - `record DatasetCaseInput(String caseKey, JsonNode input, JsonNode messages, String referenceAnswer, List<String> expectedDocIds, List<CaseAssertion> assertions, List<String> tags)`，规范构造器校验：`caseKey` 非空白，`input` 为 JSON 对象，`assertions` 为 null 时归一为空列表。
  - `class CaseHashCalculator`：`String compute(DatasetCaseInput c)`——把 `input`、`messages`、`referenceAnswer`、`expectedDocIds`、`assertions` 组成一个对象，递归按键排序（数组保持原顺序）序列化后取 SHA-256 十六进制小写；`tags` 与 `caseKey` 不计入。
  - `class DatasetImportParser`：`List<DatasetCaseInput> parse(String format, String content)`，`format` 取 `JSON`、`JSONL`、`CSV`（大小写不敏感，其他值抛 `BizException(IMPORT_INVALID, ...)`）。JSON 为对象数组，JSONL 每行一个对象，对象字段名与 `DatasetCaseInput` 一致（snake_case：`case_key`、`reference_answer`、`expected_doc_ids`）。CSV 首行必须是 `question,reference_answer`，`case_key` 自动生成 `case-0001`、`case-0002`…，`input` 为 `{"question": <第一列>}`。任何非法行抛 `BizException(IMPORT_INVALID, "第 N 行：原因")`。

- [ ] **Step 1: 写失败测试**

`CaseHashCalculatorTest`：
- **Review Focus 3** `keyOrderDoesNotAffectHash`：`input` 分别为 `{"a":1,"b":2}` 与 `{"b":2,"a":1}` 的两个用例哈希相等；嵌套对象同样成立。
- `changingReferenceAnswerChangesHash`、`changingExpectedDocIdsChangesHash`、`changingAssertionsChangesHash`、`changingInputChangesHash`、`changingMessagesChangesHash`：各自与基准哈希不相等。
- `tagsAndCaseKeyDoNotAffectHash`：只改 `tags` 或 `caseKey`，哈希不变。
- `hashIs64LowercaseHex`：匹配 `^[0-9a-f]{64}$`。

`DatasetImportParserTest`：
- `parsesJsonArray`、`parsesJsonlSkippingBlankLines`、`parsesTwoColumnCsvWithGeneratedKeys`（断言 `case-0001`、`input.question`、`referenceAnswer`）。
- `jsonlBadLineReportsLineNumber`：第 2 行不是合法 JSON → `BizException`，`message` 含 `"第 2 行"`。
- `missingCaseKeyInJsonIsRejected`、`inputNotObjectIsRejected`、`csvWrongHeaderIsRejected`、`unknownFormatIsRejected`。

- [ ] **Step 2: 确认失败**

Run: `cd backend && mvn -q -Dtest='CaseHashCalculatorTest,DatasetImportParserTest' test`
Expected: 失败（类不存在）。

- [ ] **Step 3: 实现**

按 Interfaces 实现。规范化用 `ObjectMapper` 读成 `JsonNode` 后递归构造 `TreeMap` 再序列化；CSV 用手写的简单解析即可（支持双引号包裹与引号内逗号），不引入新依赖。实体类使用 MyBatis-Plus 注解（`@TableName`、`@TableId(type = IdType.AUTO)`）。

- [ ] **Step 4: 确认通过**

Run: `cd backend && mvn -q test`
Expected: `BUILD SUCCESS`；`V2` 迁移在 H2 上能被 Flyway 执行（由 `contextLoads` 覆盖）。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add dataset schema, canonical case hash and import parser"
```

---

### Task 4: 不可变版本数据集服务与 REST API

**Files:**
- Create: `MAIN/dataset/{DatasetMapper,DatasetVersionMapper,DatasetCaseMapper,DatasetService,DatasetController}.java`
- Test: `TEST/dataset/{DatasetServiceTest,DatasetControllerTest}.java`

**Interfaces:**
- Consumes: Task 3 的实体、`DatasetCaseInput`、`CaseHashCalculator`、`DatasetImportParser`；Task 1 的 `BizException`、`ErrorCode.{NOT_FOUND,DUPLICATE_CASE_KEY}`。
- Produces:
  - `class DatasetService`：
    - `DatasetEntity create(String name, String description)`；
    - `List<DatasetEntity> list()`；
    - `@Transactional DatasetVersionEntity importVersion(Long datasetId, List<DatasetCaseInput> cases, String note)`：`versionNo` = 当前最大值 + 1（首次为 1），写入版本与全部用例（含 `case_hash`），回填 `case_count`；`cases` 为空 → `BizException(IMPORT_INVALID)`；批内 `case_key` 重复 → `BizException(DUPLICATE_CASE_KEY, 含重复的 key)`，事务回滚；数据集不存在 → `NOT_FOUND`；
    - `List<DatasetVersionEntity> listVersions(Long datasetId)`（按 `versionNo` 升序）；
    - `List<DatasetCaseEntity> listCases(Long datasetId, int versionNo)`。
  - REST（均需登录）：`POST /api/datasets`（`{name(@NotBlank), description}`）、`GET /api/datasets`、`GET /api/datasets/{id}/versions`、`POST /api/datasets/{id}/versions/import`（`{format, content, note}`，调用 `DatasetImportParser` 后 `importVersion`）、`GET /api/datasets/{id}/versions/{versionNo}/cases`。
  - 版本不可变：不提供任何修改或删除已有版本/用例的接口。

- [ ] **Step 1: 写失败测试**

`DatasetServiceTest`（`@SpringBootTest @ActiveProfiles("test")` 的集成测试，用 H2；**类上不加 `@Transactional`**，每个测试自己新建数据集，这样才能观察回滚）：
- `firstImportCreatesVersion1WithCaseCount`、`secondImportCreatesVersion2AndVersion1StaysUnchanged`（导入 v2 后再读 v1 用例，内容与 `case_hash` 不变，且 `listVersions` 返回 `[1,2]`）。
- **Review Focus 2** `duplicateCaseKeyRollsBackWholeBatch`：导入含两个相同 `case_key` 的批次 → 抛 `BizException`，`getCode()==DUPLICATE_CASE_KEY`，消息含该 key；随后该数据集的 `listVersions` 为空、`dataset_case` 表中不存在属于该批次的行。
- `emptyBatchIsRejected`、`unknownDatasetIsNotFound`、`caseHashIsPersistedAndMatchesCalculator`。

`DatasetControllerTest`（MockMvc，先登录拿 token）：
- `importJsonlThenListCases`：创建数据集、导入 JSONL 两条，`GET .../versions/1/cases` 返回两条。
- `importDuplicateKeyReturns400WithCode`：响应 HTTP 400，`code=="DUPLICATE_CASE_KEY"`。
- `endpointsRequireAuth`：无 token → 401。

- [ ] **Step 2: 确认失败**

Run: `cd backend && mvn -q -Dtest='DatasetServiceTest,DatasetControllerTest' test`
Expected: 失败（类不存在）。

- [ ] **Step 3: 实现**

`versionNo` 的计算与插入放在同一事务内；为避免并发导入产生相同版本号，依赖 `(dataset_id, version_no)` 唯一索引，冲突时抛 `BizException(IMPORT_INVALID, "版本号冲突，请重试")`。

- [ ] **Step 4: 确认通过**

Run: `cd backend && mvn -q test`
Expected: `BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add immutable dataset versioning service and api"
```

---

### Task 5: 模型渠道管理（加密 Key 与脱敏）

**Files:**
- Create: `RES/db/migration/V3__init_provider.sql`
- Create: `MAIN/provider/{ProviderType,ProviderConfigEntity,ProviderConfigMapper,ProviderUpsertRequest,ProviderView,ProviderService,ProviderController}.java`
- Test: `TEST/provider/{ProviderServiceTest,ProviderControllerTest}.java`

**Interfaces:**
- Consumes: Task 2 的 `AesGcmEncryptor`；Task 1 的 `BizException`、`ErrorCode.NOT_FOUND`。
- Produces:
  - `V3__init_provider.sql`：`provider_config(id, name VARCHAR(128) NOT NULL, provider_type VARCHAR(32) NOT NULL, base_url VARCHAR(255), api_key_ciphertext VARCHAR(1024), api_key_mask VARCHAR(64), default_model VARCHAR(128) NOT NULL, pricing_json LONGTEXT, mock_config_json LONGTEXT, created_at, updated_at)`。
  - `enum ProviderType { OPENAI_COMPATIBLE, MOCK }`。
  - `record ProviderUpsertRequest(@NotBlank String name, @NotNull ProviderType providerType, String baseUrl, String apiKey, @NotBlank String defaultModel, String pricingJson, String mockConfigJson)`。
  - `record ProviderView(Long id, String name, ProviderType providerType, String baseUrl, String apiKeyMask, String defaultModel, String pricingJson, String mockConfigJson)`——**不含**明文与密文字段。
  - `class ProviderService`：`ProviderView create(ProviderUpsertRequest)`、`ProviderView update(Long id, ProviderUpsertRequest)`、`List<ProviderView> list()`、`ProviderView get(Long id)`、`void delete(Long id)`、`ProviderConfigEntity getEntity(Long id)`（供 Task 6 使用，含密文）、`String decryptApiKey(ProviderConfigEntity)`。规则：
    - `OPENAI_COMPATIBLE` 创建时 `baseUrl` 与 `apiKey` 必填，否则 `VALIDATION_ERROR`；`MOCK` 类型两者可空；
    - 掩码规则：Key 长度 ≥ 8 时为 `前 3 位 + "****" + 后 4 位`，否则为 `"****"`；
    - 更新时 `apiKey` 为 null 或空白 → 保留原密文与原掩码。
  - REST（均需登录）：`POST /api/providers`、`GET /api/providers`、`PUT /api/providers/{id}`、`DELETE /api/providers/{id}`，返回 `ProviderView`。

- [ ] **Step 1: 写失败测试**

`ProviderServiceTest`：
- `createEncryptsKeyAndStoresMask`：创建 Key `sk-abcdef123456` 的渠道后，库中 `api_key_ciphertext` 不等于明文且不包含 `abcdef`，`decryptApiKey` 还原为明文，`apiKeyMask == "sk-****3456"`。
- **Review Focus 4** `viewNeverExposesKeyMaterial`：对 `ProviderView` 序列化为 JSON 字符串，断言不含明文 Key，也不含密文字符串。
- **Review Focus 4** `updateWithoutApiKeyKeepsOriginalCiphertext`：先创建，再 `update` 时 `apiKey=null`（以及 `apiKey="   "`），之后 `decryptApiKey` 仍为原明文，`apiKeyMask` 不变。
- `updateWithNewApiKeyReplacesCiphertext`。
- `openAiProviderRequiresBaseUrlAndKey`、`mockProviderAllowsMissingKeyAndBaseUrl`、`deleteUnknownIsNotFound`、`shortKeyMaskIsStars`。

`ProviderControllerTest`：`createListUpdateDeleteFlow`（响应里没有 `apiKey` 与 `apiKeyCiphertext` 字段）、`endpointsRequireAuth`。

- [ ] **Step 2: 确认失败**

Run: `cd backend && mvn -q -Dtest='ProviderServiceTest,ProviderControllerTest' test`
Expected: 失败（类不存在）。

- [ ] **Step 3: 实现**

按 Interfaces 实现；所有对外返回一律通过 `ProviderView` 转换，实体类不得直接作为 Controller 返回值。

- [ ] **Step 4: 确认通过**

Run: `cd backend && mvn -q test`
Expected: `BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add provider config management with encrypted api keys"
```

---

### Task 6: LLM 客户端、确定性 Mock 渠道与冒烟测试接口

**Files:**
- Create: `MAIN/provider/llm/{LlmMessage,LlmRequest,LlmResponse,LlmErrorType,LlmClient,MockProfile,MockLlmClient,OpenAiCompatibleLlmClient,LlmClientRouter}.java`
- Create: `MAIN/provider/SmokeTestService.java`
- Modify: `MAIN/provider/ProviderController.java`（增加冒烟测试端点）
- Test: `TEST/provider/llm/{MockLlmClientTest,OpenAiCompatibleLlmClientTest}.java`、`TEST/provider/SmokeTestServiceTest.java`

**Interfaces:**
- Consumes: Task 5 的 `ProviderConfigEntity`、`ProviderType`、`ProviderService.getEntity/decryptApiKey`；Task 1 的 `virtualThreadExecutor` 不是必须。
- Produces:
  - `record LlmMessage(String role, String content)`（`role` 取 `system|user|assistant`）。
  - `record LlmRequest(String model, List<LlmMessage> messages, String caseKey, int attemptNo, Map<String,String> metadata, Duration timeout)`；`metadata` 可含 `referenceAnswer`（M2 的评测引擎会填入，M1 冒烟测试可手填）。
  - `enum LlmErrorType { NONE, RATE_LIMITED, TIMEOUT, HTTP_ERROR, MALFORMED_RESPONSE }`。
  - `record LlmResponse(String content, long latencyMs, Long ttftMs, int inputTokens, int outputTokens, BigDecimal estimatedCost, LlmErrorType errorType, String errorMessage)`；M1 为非流式，`ttftMs` 恒为 `null`（流式与 TTFT 留到 M3）；上游失败**不抛异常**，以 `errorType != NONE` 返回。
  - `interface LlmClient { LlmResponse chat(ProviderConfigEntity provider, String apiKeyPlain, LlmRequest request); }`。
  - `enum MockProfile { MOCK_GOOD("mock-good"), MOCK_SLOPPY("mock-sloppy"), MOCK_SLOW("mock-slow"), MOCK_FLAKY("mock-flaky") }`，`static MockProfile fromName(String)`。
  - `MockLlmClient`：从 `provider.mockConfigJson` 读 `{"profile": "...", "seed": <long，默认 0>, "latencyMs": <默认 0>, "failRate": <默认 0.5>}`。行为：
    - 通用：答案基础文本 = `request.metadata["referenceAnswer"]`（存在时），否则 `"[mock] " + 最后一条 user 消息`；`inputTokens` = 所有消息字符数之和 ÷ 4 向上取整，`outputTokens` 同理按输出算；`estimatedCost` 由 `pricingJson` 的 `inputPer1k`/`outputPer1k` 计算，缺省为 0。
    - `mock-good`：返回基础文本。
    - `mock-sloppy`：用 `hash(seed, caseKey) mod 100 < 40` 判定「答错」；答错时返回被截断的坏 JSON `{"answer": "`，否则返回基础文本。与 `attemptNo` 无关。
    - `mock-slow`：同 `mock-good`，但先 `Thread.sleep(latencyMs)`（缺省 2000）。
    - `mock-flaky`：令 `h = hash(seed, caseKey, attemptNo)`；`attemptNo >= 3` 必成功；否则 `h mod 1000 < failRate*1000` 时失败，失败类型由 `h` 的奇偶决定（奇数 `RATE_LIMITED`，偶数 `TIMEOUT`），成功时同 `mock-good`。
    - `hash` 使用固定算法（SHA-256 取前 8 字节转 long），保证跨 JVM 一致。
  - `OpenAiCompatibleLlmClient`：用 `RestClient`（JDK `HttpClient` 请求工厂，连接与读超时取 `request.timeout`）向 `{baseUrl}/chat/completions` 发 POST（头 `Authorization: Bearer <key>`，体含 `model`、`messages`），映射：HTTP 429 → `RATE_LIMITED`；超时 → `TIMEOUT`；其他非 2xx → `HTTP_ERROR`；2xx 但缺少 `choices[0].message.content` → `MALFORMED_RESPONSE`；成功时 token 数取响应的 `usage.prompt_tokens/completion_tokens`（缺失则用字符数÷4 估算）。`errorMessage` 与日志中绝不出现 API Key。
  - `class LlmClientRouter { LlmClient forType(ProviderType type); }`。
  - `class SmokeTestService { LlmResponse run(Long providerId, String prompt, String modelOverride); }`：取渠道、解密 Key（Mock 渠道无 Key 时传 null）、构造 `caseKey="smoke"`、`attemptNo=1` 的请求并调用。
  - REST：`POST /api/providers/{id}/smoke-test`，请求 `{prompt(@NotBlank), model}`，返回 `ApiResponse<LlmResponse>`。

- [ ] **Step 1: 写失败测试**

`MockLlmClientTest`：
- `goodProfileEchoesReferenceAnswerWhenPresent`、`goodProfileFallsBackToPromptEcho`。
- `sloppyProfileIsDeterministicPerCaseKey`：同一 `(seed, caseKey)` 重复调用 20 次结果完全相同；对 200 个不同 `caseKey` 统计，答错占比落在 `[25%, 55%]` 内；答错的响应内容不是合法 JSON。
- `slowProfileHonorsLatency`：`latencyMs=50` 时 `latencyMs` 字段 ≥ 50。
- **Review Focus 5** `flakyIsDeterministicForSameSeedCaseAndAttempt`：对同一 `(seed, caseKey, attemptNo=1)` 调用 10 次，`errorType` 与 `content` 完全一致。
- **Review Focus 5** `flakyAlwaysSucceedsByAttempt3`：对 200 个不同 `caseKey`，`attemptNo=3` 全部 `errorType==NONE`。
- `flakyFailuresRecoverAcrossAttempts`：在 200 个 `caseKey` 中找到至少一个 `attemptNo=1` 失败的，断言它的 `attemptNo` 序列 `1→2→3` 最迟在第 3 次成功。
- `flakyProducesBothErrorKinds`：200 个 `caseKey` 的 `attemptNo=1` 中既出现 `RATE_LIMITED` 也出现 `TIMEOUT`。
- `costIsComputedFromPricing`、`tokensAreEstimatedFromCharacters`。

`OpenAiCompatibleLlmClientTest`（`MockWebServer`）：
- `successParsesContentAndUsage`（发送的请求路径为 `/chat/completions`，带 `Authorization: Bearer test-key`，体含 `model`）。
- `http429MapsToRateLimited`、`http500MapsToHttpError`、`readTimeoutMapsToTimeout`（服务端延迟大于 `request.timeout`）、`missingChoicesMapsToMalformedResponse`。
- `errorMessageNeverContainsApiKey`：以上各错误场景中，`errorMessage` 与捕获的日志输出都不含 `test-key`。

`SmokeTestServiceTest`：`mockProviderSmokeTestReturnsResponse`（建 `mock-good` 渠道，prompt `hello`，响应 `content` 含 `hello`）、`unknownProviderIsNotFound`。

- [ ] **Step 2: 确认失败**

Run: `cd backend && mvn -q -Dtest='MockLlmClientTest,OpenAiCompatibleLlmClientTest,SmokeTestServiceTest' test`
Expected: 失败（类不存在）。

- [ ] **Step 3: 实现**

按 Interfaces 实现；`LlmClientRouter` 通过 Spring 注入两个客户端并按 `ProviderType` 返回；`ProviderController` 新增端点调用 `SmokeTestService`。

- [ ] **Step 4: 确认通过**

Run: `cd backend && mvn -q test`
Expected: `BUILD SUCCESS`，全部后端测试通过。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add llm clients, deterministic mock provider and smoke test endpoint"
```

---

### Task 7: Vue 3 控制台（登录、数据集、渠道与冒烟调试）

**Files:**
- Create: `frontend/{package.json,vite.config.ts,tsconfig.json,index.html}`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/api/{http,auth,dataset,provider}.ts`
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/layouts/MainLayout.vue`
- Create: `frontend/src/views/{LoginView,DatasetsView,DatasetDetailView,ProvidersView}.vue`

**Interfaces:**
- Consumes: Task 2/4/5/6 的 REST 接口与 `ApiResponse` 结构 `{success, code, message, data}`。
- Produces（前端内部）：
  - `http.ts`：axios 实例，`baseURL=/api`，请求拦截器附 `Authorization: Bearer <token>`，响应拦截器在 `success=false` 时用 `ElMessage.error(message)` 提示并 reject，收到 HTTP 401 时清 token 并跳转 `/login`。
  - `auth.ts`（store）：`token`、`login(username, password)`、`logout()`、`isLoggedIn`。
  - 页面路由：`/login`、`/datasets`、`/datasets/:id`、`/providers`；除 `/login` 外均需登录。
  - `DatasetDetailView`：列出版本，提供「导入新版本」对话框（选择格式 JSON/JSONL/CSV、粘贴内容、备注），选中版本后展示用例表（`caseKey`、输入摘要、参考答案、`caseHash` 前 8 位）。
  - `ProvidersView`：渠道列表（只显示 `apiKeyMask`）、新建/编辑对话框（编辑时 Key 输入框留空表示不修改）、每行「冒烟测试」按钮：弹出输入 prompt，展示返回的 `content`、`latencyMs`、`inputTokens/outputTokens`、`errorType`。

- [ ] **Step 1: 搭建工程并写类型约束**

用 `npm create vite@latest frontend -- --template vue-ts` 初始化，安装 `element-plus axios pinia vue-router`；配置 `vite.config.ts` 代理 `/api` → `http://localhost:8080`；为 `ApiResponse<T>`、数据集、版本、用例、渠道、冒烟响应定义 TypeScript 接口（放在各自 `api/*.ts`）。

- [ ] **Step 2: 确认类型检查先失败**

Run: `cd frontend && npm run build`
Expected: 在页面文件尚未创建时失败（路由引用的视图不存在）。

- [ ] **Step 3: 实现 API 层、store、路由、布局与四个页面**

按 Interfaces 实现。

- [ ] **Step 4: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 类型检查零错误，Vite 构建成功并生成 `dist/`。

- [ ] **Step 5: 端到端手工验证 M1 闭环**

先在项目根目录 `cp .env.example .env` 并填好密钥，`docker compose up -d`，然后：
Run: `cd backend && set -a && source ../.env && set +a && mvn spring-boot:run`，另开终端 `cd frontend && npm run dev`，浏览器打开 `http://localhost:5173`。
Expected（逐项确认）：
1. 用 `admin` 和 `.env` 里的 `TRACEDELTA_ADMIN_PASSWORD` 登录成功；未登录访问 `/datasets` 会跳回登录页。
2. 新建数据集，用 JSONL 导入 2 条用例得到 v1；再导入 3 条得到 v2；v1 的用例仍是 2 条。
3. 导入含重复 `case_key` 的内容时，页面弹出错误提示，且版本列表没有新增版本。
4. 新建 `mock-good` 渠道（`mockConfigJson={"profile":"mock-good","seed":1}`）与 `mock-sloppy` 渠道，列表里没有任何 Key 明文；对 `mock-good` 点「冒烟测试」得到包含 prompt 回显的内容与 token 数。

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat: add vue console for login, datasets and providers"
```

---

## Self-Review 记录

- **Spec 覆盖（M1 范围：第 0–2 周）**：环境与 Docker（Task 0）、骨架（Task 1）、登录与密钥加密（Task 2，spec 4.10）、数据集版本/`case_hash`/导入格式（Task 3–4，spec 4.6、4.11A）、渠道与 Mock 四档（Task 5–6，spec 4.9、4.11G）、前端并行起步（Task 7，spec 4.11G）。「Run 快照」与 EvalAttempt 属于 M2，「快照」所需的版本不可变性已在 Task 4 打好基础。
- **类型一致性**：`DatasetCaseInput`、`CaseAssertion`、`ProviderConfigEntity`、`LlmRequest/LlmResponse`、`ErrorCode` 名称在各任务间一致；`ErrorCode` 的全部取值在 Task 1 一次定义，后续任务不新增。
- **Review Focus**：5 条均已挂在对应任务的具名测试上（Task 2/3/4/5/6）。
- **留给后续计划**：流式与 TTFT（M3）、Redis 限流与并发引擎（M2）、断言执行（M4）。
