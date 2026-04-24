"""集成测试：用 resolve_component + load_memory_by_id 精确操作无标签 ROM，
验证「复杂的MIPS RAM存储器测试电路」PASS → FAIL 完整流程。

电路设计：比较期望值 ROM 输出与实际 MIPS RAM 输出，匹配才推进，不匹配则无限循环。
- PASS：电路在限定 ticks 内停机（停机控制=1）
- FAIL：电路因比较不匹配而卡死，在限定 ticks 内不停机

问题背景：该电路含 6 个无标签 ROM，旧的 load_memory(target="ROM") 因歧义无法工作。
新方案：通过 addrBits=9、dataBits=32 唯一过滤出期望值 ROM 并直接用 comp_id 写入。
"""

import asyncio
import json
import os
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/TEA_MIPS RAM 存储器验证实验.circ")
SUBCIRCUIT = "复杂的MIPS RAM存储器测试电路"

# 全 0xFFFFFFFF 期望值 → 电路在第 0 步比较即失败，卡死不停机
WRONG_EXPECTED_TXT = os.path.abspath("test_circuits/MIPS_all_ff_expected.txt")

HALT_TARGET = "停机控制"
# 正常停机的最大 tick 数（Phase1 实测约 875 ticks）
PASS_MAX_TICKS = 100_000
# FAIL 检测用的超短 tick 数（远大于 875，确保正常停机能通过；错误时电路卡死，远小于 5M）
FAIL_DETECT_TICKS = 10_000


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------


async def send_json(ws: Any, action: str, **kwargs: Any) -> dict[str, Any]:
    req: dict[str, Any] = {"action": action, **kwargs}
    print(f"  >> {action}({', '.join(f'{k}={v!r}' for k, v in kwargs.items())})")
    await ws.send(json.dumps(req, ensure_ascii=False))
    resp = await ws.recv()
    if isinstance(resp, bytes):
        raise AssertionError(f"Unexpected binary response for action '{action}'")
    data = json.loads(resp)
    if data.get("status") == "error":
        raise AssertionError(
            f"Action '{action}' returned error: {data.get('message') or data}"
        )
    return data


async def load_and_switch(ws: Any) -> None:
    """加载电路并切换到目标子电路（每次给出干净初始状态）。"""
    await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
    await send_json(ws, "switch_circuit", name=SUBCIRCUIT)


async def find_expected_value_rom_id(ws: Any) -> str:
    """调用 resolve_component 唯一定位期望值 ROM（addrWidth=9, dataWidth=32）。"""
    resp = await send_json(
        ws,
        "resolve_component",
        factory_name="ROM",
        is_memory=True,
        addr_bits=9,
        data_bits=32,
    )
    payload = resp.get("payload", {})
    if not payload.get("resolved", False):
        # 未能唯一解析：打印候选列表辅助调试
        candidates = payload.get("candidates", [])
        print(f"  [Candidates] {json.dumps(candidates, ensure_ascii=False, indent=2)}")
        raise AssertionError(
            f"resolve_component did not uniquely resolve expected-value ROM: "
            f"{payload.get('reason')}, candidates={payload.get('candidate_count')}"
        )
    comp_id: str = payload["comp_id"]
    component = payload.get("component", {})
    print(f"  [Resolved] comp_id={comp_id!r}")
    print(f"  [Resolved] human_name={component.get('human_name')!r}")
    print(
        f"  [Resolved] addr_bits={component.get('addr_bits')}, data_bits={component.get('data_bits')}"
    )
    return comp_id


async def run_until_halt(
    ws: Any, phase_label: str, max_ticks: int = PASS_MAX_TICKS
) -> int:
    """推进时钟直到 停机控制=1，返回停机所需 ticks 数。"""
    resp = await send_json(
        ws,
        "tick_until",
        target=HALT_TARGET,
        expected="1",
        clock="CLK",
        max=max_ticks,
    )
    ticks: int = resp.get("ticks", -1)
    print(f"  [{phase_label}] ticks to halt: {ticks}")
    return ticks


async def try_tick_until_no_halt(ws: Any, phase_label: str) -> bool:
    """尝试 tick 限定步数，返回 True 表示「电路未在限定内停机」（FAIL 状态）。"""
    req: dict[str, Any] = {
        "action": "tick_until",
        "target": HALT_TARGET,
        "expected": "1",
        "clock": "CLK",
        "max": FAIL_DETECT_TICKS,
    }
    await ws.send(json.dumps(req, ensure_ascii=False))
    raw = await ws.recv()
    data = json.loads(raw)
    if data.get("status") == "error":
        # tick_until 超时 → 电路卡死未停机 → FAIL 确认
        msg = data.get("message", "")
        print(f"  [{phase_label}] tick_until timed out (expected): {msg[:80]}")
        return True  # FAIL confirmed
    # 如果意外停机了
    ticks = data.get("ticks", -1)
    print(f"  [{phase_label}] WARNING: circuit halted at {ticks} ticks (unexpected)")
    return False  # not FAIL


# ---------------------------------------------------------------------------
# 主测试
# ---------------------------------------------------------------------------


async def test_mips_ram_pass_then_fail() -> None:
    # 前置检查
    if not os.path.exists(CIRCUIT_FILE):
        raise FileNotFoundError(f"Circuit not found: {CIRCUIT_FILE}")
    if not os.path.exists(WRONG_EXPECTED_TXT):
        raise FileNotFoundError(
            f"Wrong expected value txt not found: {WRONG_EXPECTED_TXT}"
        )

    async with websockets.connect(URI) as ws:

        # ── Phase 1: 验证原始电路能跑出 PASS ───────────────────────────
        print("\n=== Phase 1: Verify original circuit → PASS ===")
        await load_and_switch(ws)
        ticks1 = await run_until_halt(ws, "Phase1")
        assert (
            ticks1 > 0
        ), f"[FAIL] Phase 1: circuit did not halt within {PASS_MAX_TICKS} ticks"
        print(f"[PASS] Phase 1: Circuit halted at {ticks1} ticks (PASS confirmed)")

        # ── Phase 2: 定位期望值 ROM 的 comp_id ─────────────────────────
        print("\n=== Phase 2: Locate expected-value ROM via resolve_component ===")
        # 重新加载以获取干净的组件快照
        await load_and_switch(ws)
        expected_rom_id = await find_expected_value_rom_id(ws)

        # ── Phase 3: 写入错误期望值，验证电路报 FAIL ───────────────────
        print("\n=== Phase 3: Load wrong expected values → FAIL ===")
        await send_json(
            ws,
            "load_memory_by_id",
            comp_id=expected_rom_id,
            txt_path=WRONG_EXPECTED_TXT,
        )
        print(f"  Loaded all-0xFFFFFFFF expected values into {expected_rom_id!r}")

        did_not_halt = await try_tick_until_no_halt(ws, "Phase3")
        assert did_not_halt, (
            f"[FAIL] Phase 3: Expected circuit to NOT halt with all-FF expected values, "
            f"but it halted (wrong expected values did not cause stall)"
        )
        print(
            "[PASS] Phase 3: Circuit correctly stalled with wrong expected values (FAIL confirmed)"
        )

        print("\n[ALL PASS] test_mips_ram_pass_then_fail completed successfully.")


if __name__ == "__main__":
    asyncio.run(test_mips_ram_pass_then_fail())
