"""诊断 Phase3 失败原因：
1. 加载全 0xFFFF 预期值 → 检查是否仍 PASS（说明 load_memory_by_id 未生效）
2. 打印所有探针值（包括 停机控制 和 result），帮助定位问题
"""

import asyncio
import json
import os
import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"
CIRCUIT_FILE = os.path.abspath("test_circuits/TEA_MIPS RAM 存储器验证实验.circ")
SUBCIRCUIT = "复杂的MIPS RAM存储器测试电路"
ALL_FF_TXT = os.path.abspath("test_circuits/MIPS_all_ff_expected.txt")
HALT_TARGET = "停机控制"
RESULT_TARGET = "output测试结束时，如果显示\u201cPASS\u201d，则表示测试通过；如果显示\u201cFAIL\u201d，则表示测试失败"
MAX_TICKS = 5_000_000


async def send(ws, action, **kw):
    await ws.send(json.dumps({"action": action, **kw}, ensure_ascii=False))
    resp = json.loads(await ws.recv())
    if resp.get("status") == "error":
        raise RuntimeError(f"{action} error: {resp.get('message')}")
    return resp


async def main():
    async with websockets.connect(URI) as ws:
        # ── 加载电路 ───────────────────────────────────────────────────────
        print("=== Loading circuit ===")
        await send(ws, "load_circuit", path=CIRCUIT_FILE)
        await send(ws, "switch_circuit", name=SUBCIRCUIT)

        # ── 解析 comp_id ────────────────────────────────────────────────────
        r = await send(
            ws,
            "resolve_component",
            factory_name="ROM",
            is_memory=True,
            addr_bits=9,
            data_bits=32,
        )
        payload = r.get("payload", {})
        if not payload.get("resolved"):
            print(f"FAIL: could not resolve ROM: {payload}")
            return
        comp_id = payload["comp_id"]
        print(f"comp_id = {comp_id!r}")

        # ── 读取 停机控制 and RESULT 前置值 ──────────────────────────────────
        halt_val_before = (await send(ws, "get_value", target=HALT_TARGET)).get(
            "payload"
        )
        print(f"Before loading: 停机控制 = {halt_val_before}")

        # ── 加载全 0xFFFF 期望值 ─────────────────────────────────────────────
        print(f"\nLoading all-0xFFFF expected values from: {ALL_FF_TXT}")
        await send(ws, "load_memory_by_id", comp_id=comp_id, txt_path=ALL_FF_TXT)
        print("load_memory_by_id succeeded")

        halt_val_after = (await send(ws, "get_value", target=HALT_TARGET)).get(
            "payload"
        )
        print(f"After loading (before tick): 停机控制 = {halt_val_after}")

        # ── tick 1 步检查变化 ─────────────────────────────────────────────────
        r1 = await send(
            ws, "tick_until", target=HALT_TARGET, expected="1", clock="CLK", max=15000
        )
        ticks = r1.get("ticks", -1)
        print(f"\ntick_until(max=15000) returned ticks={ticks}")

        halt_val = (await send(ws, "get_value", target=HALT_TARGET)).get("payload")
        result_val = (await send(ws, "get_value", target=RESULT_TARGET)).get("payload")
        print(f"停机控制 = {halt_val!r}")
        print(f"RESULT   = {result_val!r}")

        if ticks == -1:
            print(
                "\n[NOTE] tick_until did NOT halt within 15000 ticks - reloading and running full test"
            )
            # Fresh run to find out how many ticks it actually takes
            await send(ws, "load_circuit", path=CIRCUIT_FILE)
            await send(ws, "switch_circuit", name=SUBCIRCUIT)
            r2 = await send(
                ws,
                "resolve_component",
                factory_name="ROM",
                is_memory=True,
                addr_bits=9,
                data_bits=32,
            )
            comp_id2 = r2["payload"]["comp_id"]
            await send(ws, "load_memory_by_id", comp_id=comp_id2, txt_path=ALL_FF_TXT)
            r3 = await send(
                ws,
                "tick_until",
                target=HALT_TARGET,
                expected="1",
                clock="CLK",
                max=MAX_TICKS,
            )
            ticks2 = r3.get("ticks", -1)
            halt2 = (await send(ws, "get_value", target=HALT_TARGET)).get("payload")
            result2 = (await send(ws, "get_value", target=RESULT_TARGET)).get("payload")
            print(f"Full run: ticks={ticks2}, 停机控制={halt2!r}, RESULT={result2!r}")
        else:
            print(f"\n[SUMMARY] halted at {ticks} ticks, RESULT={result_val!r}")
            if result_val == "0x00000000":
                print(
                    "[PROBLEM] Circuit shows PASS even with all-0xFFFF expected values!"
                )
                print(
                    "          This means load_memory_by_id is NOT affecting the ROM."
                )
            else:
                print(
                    "[OK] Circuit shows FAIL as expected - load_memory_by_id works correctly."
                )
                print(
                    "     The original '修改后的期望值.txt' may not actually cause FAIL for this circuit."
                )

        # ── 打印所有输出 probe ───────────────────────────────────────────────
        print("\n=== All output probe values (after halt) ===")
        r_list = await send(ws, "list_components")
        probes = [
            c for c in r_list.get("payload", []) if c.get("factory_name") == "Probe"
        ]
        for p in probes:
            label = p.get("label", "")
            v = (
                (await send(ws, "get_value", target=label)).get("payload")
                if label
                else "(no label)"
            )
            print(f"  Probe {label!r}: {v}")


if __name__ == "__main__":
    asyncio.run(main())
