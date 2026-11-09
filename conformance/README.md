# conformance — 接入方联调套件

对任意接入方 App 的四项契约断言（PC 侧驱动，零测试代码改动——新接入方 = 复制一份薄配置）：

| 断言 | 覆盖 |
| --- | --- |
| A1 | initialize 握手（协议 2025-06-18） |
| A2 | 聚合目录 SHARED 工具可见（`visibility=shared` 正向） |
| A3 | 目录 PRIVATE 工具不可见（default-deny 海关负向钉） |
| A4 | SHARED 工具 `tools/call` 真调通 |

设计 SSOT = goodhub 仓 `docs/design/integration-test-refactor.md` §R3；线级契约 = [api.md](../api.md)。blob 分片（`mcp-blob://`）契约未落地，不进套件；sampling 反向二期。

## 用法

```bash
# probe 模式（推荐）：自动拉起 goodhub 网关探针 + adb forward，跑完自动收
python3 conformance/run.py --config conformance/configs/dummy.json --device <DEVICE_ID>

# direct 模式：外接已起网关（如宿主局域网直连）
python3 conformance/run.py --config conformance/configs/dummy.json \
    --mode direct --host <手机IP> --port 8765
```

前置：真机在线 + 已装接入方 App（同 debug keystore 同批安装，`check-same-signature` 门禁过）。

⚠️ **probe 模式互斥纪律**：探针 = flutter test 测试壳，会**覆盖安装设备上的正式宿主包**且探针进程被杀后宿主包可能被清掉——probe 模式跑完须重装正式宿主（`toolbox run build-apk --install`）。宿主正式包的网关是设置页手动启动的，direct 模式适合「宿主已开网关」的联调现场。

退出码：0 = 全过；1 = 有断言失败；2 = 环境错误。

## 薄配置（configs/<app>.json）

```json
{
  "app": "dummy",                      // 接入方显示名
  "package": "com.zzh.goodhub.sample.dummy",
  "server_id": "dummy",
  "expect_shared_tools": ["echo"],     // A2：应出现在对外聚合目录
  "forbid_tools": ["add"],             // A3：不应出现（default-deny）
  "call_probe": {"tool": "echo", "arguments": {"text": "conformance"}}  // A4
}
```

`expect_shared_tools: []` = 旧式全 PRIVATE 插件的负样本形态（只跑 A1/A4）。

## 样本

- `configs/dummy.json` — 标准参照插件（sample-dummy-plugin），正向全链；
- `configs/goodshare.json` — 旧式全 PRIVATE 插件负样本（目录不可见 + 知名直达仍可达，钉非 ACL 语义）。
