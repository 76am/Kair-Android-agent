# 贡献指南

感谢参与 Kair。提交前请先读 [AGENTS.md](AGENTS.md) 里的工程硬约束。

## 环境

- JDK 17
- Android SDK：`compileSdk = 35`、`build-tools 35.0.0`
- 仓库自带 Gradle wrapper（8.14.5）

## 构建与测试

```bash
./gradlew :app:assembleDebug :plugin-api:test :app:testDebugUnitTest ktlintCheck
```

内存有限的机器加 `--no-parallel` 并下调 `-Dorg.gradle.jvmargs`。产物在
`app/build/outputs/apk/debug/app-debug.apk`。

- 新行为先写一条会失败的用例；不要用 `@Ignore` 或 `assumeTrue` 掩盖失败。
- 涉及插件 ABI 的改动必须跑 `:plugin-api:test`（`JvmDefaultCompatibilityTest` 会守编译选项）。
- 纯文档改动不必跑测试。

## 代码约定

- 面向用户的文案进 `res/values/strings.xml`；面向模型的字符串（工具描述、JSON Schema、
  system prompt）用英文；代码注释用中文。
- 单文件软上限约 500 行；普通方法 ≤ 100 行，Composable ≤ 200 行。
- 插件契约新增成员必须带**能独立工作的默认实现**；破坏性变更才提升 `KairApi.VERSION`，
  并同步更新 `docs/插件接口契约.md`。
- 不要往内核加 provider 分支；新协议应注册传输层插件。

## 提交与 PR

- 一个 PR 只做一件事；换实现时在同一提交里删除旧路径、开关与测试。
- 描述里写清：守住了什么不变量、触发路径、验收行为；以及「删掉了什么」。
- 大改 UI 前先在 Issue 里给出方案或草图，确认方向后再写 Compose。
- 不要 force-push 到 `main`；不要提交构建产物、本机路径或任何密钥。

## 许可证

贡献将以 [Apache License 2.0](LICENSE) 授权。提交即表示你同意这一点。
