---
name: goodhub-mcp-sdk-workflow
description: goodhub-mcp-sdk（Android/Kotlin · Gradle 多模块 SDK）工作流事实源：构建/测试/发布命令唯一出处、环境硬约束。构建报错、跑测试、换机器前先读；写码规则查 docs/project/coding-standards.md，功能落点查 docs/project/index.md，工具链踩坑查 context/lessons.md。
---

# goodhub-mcp-sdk · 工作流事实源

> 项目一句话：**GoodHub 本地插件接入 SDK**——Android/Kotlin，Gradle 多模块，产出四个 AAR 与一个可安装示例 APK。契约 SSOT = 本仓 `contract/src/main/aidl/` 与 `contract/src/main/java/com/zzh/goodhub/mcp/McpContract.kt`（线级契约文档 = 根 `api.md`）；上游宿主 goodhub 仓为跨仓只读引用，见根 `README.md` §7。

## 定位边界（三件套分工，防双源）

| 需要什么 | 查哪里 |
|---|---|
| **写码规则**：代码放哪个域 / 分层依赖方向 / 新功能落点决策 / 质量门禁 | `docs/project/coding-standards.md`（§1 分层矩阵 + 落点决策表；规约 = `goodhub-mcp-sdk-standards` skill） |
| **改前定位**：某功能的代码落点 / 文档落点 / 架构债与违例登记 | `docs/project/index.md`（数据；登记规约 = `goodhub-mcp-sdk-index` skill） |
| **命令与环境**：本 skill | 构建/测试/运行/发布命令、环境硬约束（工具链踩坑数据查 `context/lessons.md`，本 skill 不复制） |

一级目录结构与域清单不在此登记——见 `docs/project/coding-standards.md`（矩阵与护栏）与 `docs/project/index.md`（域内文件与功能落点）。现行 epic 与过程态查 `context/CURRENT` / `context/epics/`，本 skill 不登记。

## 读码纪律（省 token 与正确性兼得）

文档比代码省 token，但文档**有损且可能失真**——照文档下手可能建在错误前提上。三段式：

| 阶段 | 动作 | 成本量级 |
|---|---|---|
| ① 文档建图 | 先读索引落点 + memory 决策 + devlog 变更，拿「为什么 / 边界 / 落点」 | 几十~几百 token |
| ② 定向读码 | LSP / grep 只读相关符号与区域，**禁整文件通读** | 几百 token/次 |
| ③ 关键断言实证 | 文档「已实现」结论动手前抽查一处代码确认；行号引用须实测（会漂移） | 一次性 |

## 环境硬约束（现场实证）

- **JDK**：CI `.github/workflows/ci.yml` 的 `setup-java` 步骤声明 17；本机实测 `java -version` = OpenJDK 21.0.12.1，且 `./gradlew --console=plain test` 在该 JDK 下跑通。JDK 17 本机未实测。
- **Gradle**：wrapper 自带发行版 9.3.1（`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl`），无需独立安装。首次执行会下载发行版与依赖、写 `~/.gradle` 与各模块 `build/`；产物目录均被 `.gitignore` 忽略。
- **Android SDK**：`ANDROID_HOME=/home/zzh/android-sdk`（环境变量已设），其 `platforms` 下 android-35 实测在位（`compileSdk 35 / minSdk 34`）。`local.properties` 是机器本地配置、不入仓；CI 另硬校验 `platforms;android-35` 与 `build-tools;36.0.0`，本机 build-tools 版本未逐一实测。
- **单测是 JVM 单测**：`./gradlew test` 不依赖设备/模拟器；真机联调另需安装 GoodHub 宿主 App 与 sample APK（本轮未实测）。
- **离线分发**：`dist/` 是构建产物（`.gitignore` 忽略）。`toolbox check scripts/dist.sh` 实测 **FAIL**（缺 `--json` 契约输出），且该 check 会执行脚本本体触发真实发布；故 toolbox 入口改由项目池 `sdk-dist` 包装器承担，原址 `scripts/dist.sh` 保留（被 README/api.md/CHANGELOG/AI_ONBOARDING 四处以 `./scripts/dist.sh` 引用）。
- **副作用提示**：Gradle 命令写缓存与 `build/`；`./scripts/dist.sh` 会跑四个 `publishReleasePublicationToDistRepository` 并写 `dist/`。实测取证前应评估这两类副作用。

## 研发闭环命令（唯一出处，禁凭记忆写）

命令统一入口：项目工具池 `toolbox run <工具>`；无 toolbox 环境用裸脚本。离线分发件优先 `toolbox run sdk-dist`（等价 `./scripts/dist.sh`）。

```bash
# 依赖安装 / 拉取：无独立 install 命令，Gradle wrapper 自举并按需解析依赖
./gradlew --console=plain test
```

### 1. 提交前质检流水线（PR 交付红线，按耗时从小到大）

任一步失败**禁止提交**；禁止 `--no-verify` 绕过门禁：

```bash
toolbox run docs-lint
./gradlew --console=plain test
./gradlew --console=plain :contract:assembleRelease :plugin-core:assembleRelease \
          :goodhub-ai-client:assembleRelease :goodhub-ai-runtime:assembleRelease \
          :sample-dummy-plugin:assembleDebug
```

- `./gradlew --console=plain test`：**实测跑通**（2026-11-09，BUILD SUCCESSFUL，缓存命中态）。
- 五模块 assemble 是 CI build job 原样命令：本轮**未实测**（避免重复产物写入），命令出处 `.github/workflows/ci.yml`。
- 无独立静态 lint 配置；架构/契约红线由单测源级断言镜像（`GoodHubAiLayerTest` / `McpContractTest`），条款见 `docs/project/coding-standards.md` §8。

### 2. 本地运行与联调

```bash
./gradlew :sample-dummy-plugin:assembleDebug   # 产物 sample-dummy-plugin/build/outputs/apk/debug/
adb logcat | grep AI-CAP                        # 宿主侧 AI 算力审计锚
```

两条均**未实测**（需真机 + 宿主 App）；`adb` 锚点出处 `AI_ONBOARDING.md` §2。

### 3. 构建异常自救（急救包）

```bash
./gradlew --stop
./gradlew clean
./gradlew --console=plain test --rerun-tasks
```

本轮**未实测**（缓存命中态无需触发）；`test` 全 UP-TO-DATE 属预期，需强制重跑用 `--rerun-tasks`。

## 规范数据维护触发器（发现即维护，不当旁观者）

**判据三问**：① 是架构分流/准入规则？② 变更频率是月/季度或重大架构变更级？③ 缺了它 CI 扫描或 Review 是否失去判定依据？——三问命中才维护 `docs/project/coding-standards.md`；日常需求流水账禁写。维护收尾两件：① 刷新 `updated` 字段；② 检查机械镜像（护栏测试/CI 脚本）是否需同步——规则正文在文档，代码只镜像不立法。
