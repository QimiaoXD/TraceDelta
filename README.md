# TraceDelta

> Automated regression testing, quality gate and execution-trace diagnosis for LLM / RAG applications.
> 面向 Java 企业级 AI 应用的轻量化、可恢复、可复现、可追溯的自动化质量保障平台。

**当前状态：设计已定稿，M1 开发启动中。** 目前仓库只有设计文档、实现计划和基础设施配置，功能尚未实现，也没有任何性能或测试数据。

## 它解决什么问题

大模型应用的输出每次都不一样。改一句提示词、换一个模型、调整 RAG 的切分方式之后，质量是变好还是变差，很难靠人工聊几句判断。TraceDelta 把这件事做成工程闭环：

建数据集（带版本快照）→ 并发批量评测（可中断恢复）→ 记录每一步执行链路 → 自动多维打分 → 两个版本五维对比、找出退化用例 → 质量门禁判定能否放行 → 回放定位问题。

## 核心设计

- **可靠评测调度引擎**：Java 21 虚拟线程、三级并发控制、任务状态机、尝试记录与重启恢复、预算熔断。
- **版本回归分析与链路诊断**：Trace/Span 追踪、不可变数据集版本与运行快照、五维 Regression Diff、质量门禁。
- **Mock 优先**：内置可编程、结果固定的 Mock 渠道，不需要任何 API Key 即可完整演示。
- **纯 Java 模块化单体**，不拆微服务。

详见 [设计文档](docs/superpowers/specs/2026-11-26-tracedelta-design.md)。

## 技术栈

Java 21 · Spring Boot 3 · MyBatis-Plus · Flyway · MySQL 8 · Redis 7 · Vue 3 + TypeScript + Element Plus + ECharts · Docker Compose · JUnit 5

## 里程碑

- [ ] M1 骨架、登录、数据集版本、渠道与 Mock（[实现计划](docs/superpowers/plans/2026-11-26-m1-foundation-dataset-mock.md)）
- [ ] M2 并发评测引擎、状态机、重试、限流、断点恢复
- [ ] M3 Trace/Span 追踪与瀑布回放
- [ ] M4 规则评分、五维 Regression Diff、HttpTarget
- [ ] M5 质量门禁、重新评分、RAG 靶场、压测与文档

## 本地启动（基础设施）

前置条件：JDK 21、Maven、Docker（或 OrbStack）、Node 20+。

```bash
cp .env.example .env      # 然后按注释填写密钥
docker compose up -d      # 启动 MySQL 8 与 Redis 7
```

后端与前端的启动方式会随 M1 完成后补充。
