# lessons

按模块归类的避坑规则正文（正文行禁用 `- [` 开头——该前缀专属底部追加区）

## 源级断言

写源级字符串断言（读 `.kt` / `.xml` 做 `contains` 检查）时必须先剥离注释块（`/*...*/`、`<!--...-->`），只扫代码与标记：注释里描述「禁用某写法」时必然包含该字面量，不剥离会把说明文字误判为实现命中。修断言的扫描范围，不放宽断言本身。回归钉子：`goodhub-ai-client/src/test/java/com/zzh/goodhub/ai/GoodHubAiVisibilityTest.kt` 的 `withoutComments` helper 与 5 条断言。

## 待归并（inbox）

