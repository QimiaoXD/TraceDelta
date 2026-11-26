# TraceDelta 协作与接续指南（AGENTS.md）

> 新开对话窗口时，按下面顺序读完即可零失忆接续开发。

## 1. 项目一句话
**TraceDelta**：面向 Java 企业级 AI 应用（RAG / Agent）的轻量化、可恢复、可复现、可追溯自动化质量保障平台（本科毕设 + 求职主项目 + GitHub 长期维护）。

## 2. 接续必读（按顺序）
1. 唯一权威设计文档：`docs/superpowers/specs/2026-11-26-tracedelta-design.md`
2. 当前里程碑实现计划：`docs/superpowers/plans/` 下最新计划（当前为 `2026-11-26-m1-foundation-dataset-mock.md`，查看 `- [x]` / `- [ ]` 进度）
3. 最近提交记录：`git log -n 10 --oneline` 与当前分支 `git branch`

## 3. 本机环境约定（macOS Apple Silicon）
- **JDK 21**（Homebrew keg-only）：
  - `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"`
  - `PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"`
- **Maven**：`/opt/homebrew/bin/mvn`（3.10.x）
- **Docker**：OrbStack（`docker compose up -d` 启动 MySQL 8 + Redis 7）
- **Node**：v25+（Vue 3 前端）

## 4. 铁律与协作规范
- **严禁提交 `.env` 或任何真实密钥**（API Key 主密钥 `TRACEDELTA_MASTER_KEY` 仅存本地 `.env`）。
- **纯 Java 模块化单体**（`io.tracedelta.*`），不拆微服务，不引入 ClickHouse/ES/独立向量库。
- **TDD + 同步单测**：每个任务先写失败测试（RED）→ 最小实现（GREEN）→ 跑通 `mvn test` → 提交并推送到 GitHub（用户要求「做完一块传一块」）。
- **用户水平对齐**：开发完成后用简明大白话说明该模块的设计思路与面试高频提问点，确保作者真正吃透。
- **严禁编造数据**：README、论文、简历中的吞吐/延迟/覆盖率数字必须来自真实实测。
