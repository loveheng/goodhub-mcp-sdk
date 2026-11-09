#!/usr/bin/env python3
"""GoodHub 接入方 conformance 套件（PC 侧驱动）。

对任意接入方 App 验证四项契约断言（设计 SSOT = goodhub 仓
docs/design/integration-test-refactor.md §R3）：
  A1 initialize 握手
  A2 聚合目录 SHARED 工具可见（default-deny 海关正向）
  A3 目录 PRIVATE 工具不可见（default-deny 负向钉）
  A4 SHARED 工具 tools/call 真调通

网关就绪两级模式：
  probe  — 自动拉起 goodhub 仓 gateway_external_probe（设备绑 0.0.0.0:8766）
           + adb forward，跑完自动收探针；
  direct — 外接已起网关（局域网直连 http://<手机IP>:<port>/mcp 等）。

薄配置 = configs/<app>.json（每接入方一份，零测试代码改动）：
  {
    "app": "dummy",
    "package": "com.zzh.goodhub.sample.dummy",
    "server_id": "dummy",
    "expect_shared_tools": ["echo"],
    "forbid_tools": ["add", "slowEcho", "reverseDemo"],
    "call_probe": {"tool": "echo", "arguments": {"text": "conformance"}}
  }
  expect_shared_tools 与 forbid_tools 同时为空 = 纯目录无关样本（仅 A1/A4 可跑）。

用法：
  python3 conformance/run.py --config conformance/configs/dummy.json \
      [--mode probe --device <ID> --goodhub-repo ../goodhub | --mode direct --host <IP> --port 8765]

退出码：0 = 全过；1 = 有断言失败；2 = 环境错误。
"""

import argparse
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request
import urllib.error

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((name, ok, detail))
    tag = "PASS" if ok else "FAIL"
    print(f"[{tag}] {name}" + (f" — {detail}" if detail else ""))
    return ok


def die(msg: str) -> "None":
    print(f"[ENV-ERROR] {msg}", file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------- 前置校验


def adb_devices() -> list[str]:
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    return [
        line.split("\t")[0]
        for line in out.splitlines()
        if "\tdevice" in line
    ]


def preflight(cfg: dict, device: str | None) -> None:
    devices = adb_devices()
    if not devices:
        die("无在线 adb 设备（真机连接后再跑）")
    if device:
        if device not in devices:
            die(f"指定设备 {device} 不在线；在线：{devices}")
        cfg["_device"] = device
        return
    if len(devices) > 1:
        die(f"多设备在线 {devices}，请 --device 指定")
    cfg["_device"] = devices[0]


def package_installed(pkg: str, device: str) -> bool:
    out = subprocess.run(
        ["adb", "-s", device, "shell", "pm", "list", "packages", pkg],
        capture_output=True, text=True,
    ).stdout
    return f"package:{pkg}" in out.split()


# ---------------------------------------------------------------- 网关就绪


def start_probe(goodhub_repo: str, device: str) -> subprocess.Popen:
    """拉起 goodhub 仓 gateway_external_probe（保活 120s），forward 8766。"""
    probe = os.path.join(goodhub_repo, "integration_test", "gateway_external_probe_test.dart")
    if not os.path.isfile(probe):
        die(f"探针不存在：{probe}（--goodhub-repo 指向 goodhub 仓根）")
    env = dict(os.environ)
    env.setdefault("ANDROID_HOME", os.path.expanduser("~/android-sdk"))
    env["PATH"] = os.path.expanduser("~/flutter/bin") + ":" + env["PATH"]
    proc = subprocess.Popen(
        ["flutter", "test", "integration_test/gateway_external_probe_test.dart",
         "-d", device],
        cwd=goodhub_repo, env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
        preexec_fn=os.setsid,
    )
    time.sleep(3)
    subprocess.run(["adb", "-s", device, "forward", "tcp:8766", "tcp:8766"],
                   capture_output=True)
    return proc


def stop_probe(proc: subprocess.Popen) -> None:
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except ProcessLookupError:
        pass


def wait_ready(endpoint: str, timeout_s: int = 90) -> None:
    """轮询 initialize 直到网关应答（探针装配需要扫描 + bind 时间）。"""
    deadline = time.time() + timeout_s
    last = ""
    while time.time() < deadline:
        try:
            _, resp = rpc(endpoint, "", {"jsonrpc": "2.0", "id": 0, "method": "initialize",
                                         "params": _init_params()})
            if resp.get("result") is not None:
                return
        except OSError as e:
            last = str(e)
        time.sleep(2)
    die(f"网关 {endpoint} 在 {timeout_s}s 内未就绪（最后错误：{last}）")


# ---------------------------------------------------------------- JSON-RPC 客户端


def _init_params() -> dict:
    return {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "goodhub-conformance", "version": "0.1"},
    }


def rpc(endpoint: str, session: str, payload: dict) -> tuple[str, dict]:
    """发一帧 JSON-RPC；返回 (session_id, response)。initialize 帧服务端会下发新会话。"""
    req = urllib.request.Request(
        endpoint,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                 **({"Mcp-Session-Id": session} if session else {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        sid = r.headers.get("Mcp-Session-Id", session)
        raw = r.read().decode()
    # 通知帧（如 notifications/initialized）= 202 空体。
    if not raw.strip():
        return sid, {}
    # 网关可能回 SSE 帧或多帧；取首个 data: 行的 JSON（无则整段当纯 JSON）。
    for line in raw.splitlines():
        if line.startswith("data:"):
            raw = line[5:].strip()
            break
    return sid, json.loads(raw)


# ---------------------------------------------------------------- 断言面


def run_assertions(cfg: dict, endpoint: str) -> None:
    # A1 initialize 握手
    sid, resp = rpc(endpoint, "", {"jsonrpc": "2.0", "id": 1,
                                   "method": "initialize", "params": _init_params()})
    ok = check("A1 initialize", resp.get("result") is not None,
               f"serverInfo={ (resp.get('result') or {}).get('serverInfo') }")

    _, resp = rpc(endpoint, sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    _, resp = rpc(endpoint, sid, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    tools = ((resp.get("result") or {}).get("tools")) or []
    names = [t.get("name", "") for t in tools]

    # A2 SHARED 可见 / A3 PRIVATE 不可见（default-deny 海关）
    shared = cfg.get("expect_shared_tools") or []
    forbid = cfg.get("forbid_tools") or []
    prefix = cfg["server_id"]
    for tool in shared:
        check(f"A2 {prefix}__{tool} 进目录", f"{prefix}__{tool}" in names,
              f"目录 {len(names)} 项")
    for tool in forbid:
        check(f"A3 {prefix}__{tool} 不进目录", f"{prefix}__{tool}" not in names,
              "default-deny：未标 SHARED 不对外暴露")

    # A4 SHARED tools/call 真调通
    probe = cfg.get("call_probe") or {}
    if probe:
        _, resp = rpc(endpoint, sid, {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                      "params": {"name": f"{prefix}__{probe['tool']}",
                                                 "arguments": probe.get("arguments", {})}})
        result = resp.get("result") or {}
        check(f"A4 tools/call {prefix}__{probe['tool']}",
              result.get("isError") is not True,
              f"isError={result.get('isError')}")


# ---------------------------------------------------------------- 入口


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", required=True, help="薄配置 JSON 路径")
    ap.add_argument("--mode", choices=["probe", "direct"], default="probe")
    ap.add_argument("--device", default=None, help="adb 设备 ID（probe 模式；缺省唯一在线设备）")
    ap.add_argument("--goodhub-repo", default=None,
                    help="goodhub 仓根（probe 模式；缺省 <SDK仓>/../goodhub）")
    ap.add_argument("--host", default="127.0.0.1", help="direct 模式网关地址")
    ap.add_argument("--port", type=int, default=8766, help="direct 模式网关端口")
    args = ap.parse_args()

    with open(args.config, encoding="utf-8") as f:
        cfg = json.load(f)
    for key in ("app", "package", "server_id"):
        if key not in cfg:
            die(f"配置缺字段 {key}：{args.config}")

    print(f"== conformance: {cfg['app']} (mode={args.mode}) ==")
    probe_proc = None
    try:
        if args.mode == "probe":
            preflight(cfg, args.device)
            device = cfg["_device"]
            # 宿主不查预装：探针（flutter test 测试壳）自带宿主代码覆盖安装。
            if not package_installed(cfg["package"], device):
                die(f"设备 {device} 未装接入方 {cfg['package']}")
            repo = args.goodhub_repo or os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "..", "goodhub")
            probe_proc = start_probe(repo, device)
            endpoint = "http://127.0.0.1:8766/mcp"
        else:
            endpoint = f"http://{args.host}:{args.port}/mcp"
        wait_ready(endpoint)
        run_assertions(cfg, endpoint)
    finally:
        if probe_proc:
            stop_probe(probe_proc)

    failed = [r for r in RESULTS if not r[1]]
    print(f"\n== 结论：{len(RESULTS) - len(failed)}/{len(RESULTS)} 过 ==")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
