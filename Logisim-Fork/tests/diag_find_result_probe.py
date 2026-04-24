"""诊断：运行原始电路（PASS）和修改后电路（FAIL），对比所有 probe 值，找到差异的探针。"""

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
MAX_TICKS = 5_000_000


async def send(ws, action, **kw):
    await ws.send(json.dumps({"action": action, **kw}, ensure_ascii=False))
    resp = json.loads(await ws.recv())
    if resp.get("status") == "error":
        raise RuntimeError(f"{action} error: {resp.get('message')}")
    return resp


async def get_all_probe_values(ws, probes):
    """读取所有探针的当前值，返回 {label: value} 字典。"""
    result = {}
    r_list = await send(ws, "list_components")
    for comp in r_list.get("payload", []):
        label = comp.get("label", "")
        factory = comp.get("factory_name", "")
        if factory == "Probe":
            if label:
                try:
                    v = (await send(ws, "get_value", target=label)).get("payload", "?")
                    result[label] = v
                except Exception as e:
                    result[label] = f"ERROR: {e}"
    return result


async def run_phase(ws, use_wrong_rom, comp_id_wrong=None):
    """加载电路、可选替换 ROM、运行到 halt，返回所有探针值。"""
    await send(ws, "load_circuit", path=CIRCUIT_FILE)
    await send(ws, "switch_circuit", name=SUBCIRCUIT)

    if use_wrong_rom:
        r = await send(
            ws,
            "resolve_component",
            factory_name="ROM",
            is_memory=True,
            addr_bits=9,
            data_bits=32,
        )
        cid = r["payload"]["comp_id"]
        await send(ws, "load_memory_by_id", comp_id=cid, txt_path=ALL_FF_TXT)
        print(f"  [WRONG ROM] Loaded all-FF into {cid!r}")

    r = await send(
        ws, "tick_until", target=HALT_TARGET, expected="1", clock="CLK", max=MAX_TICKS
    )
    ticks = r.get("ticks", -1)
    print(f"  halt at ticks={ticks}")

    # 读所有 labeled probe
    result = {}
    r_list = await send(ws, "list_components")
    for comp in r_list.get("payload", []):
        label = comp.get("label", "")
        factory = comp.get("factory_name", "")
        if factory == "Probe" and label:
            try:
                v = (await send(ws, "get_value", target=label)).get("payload", "?")
                result[label] = v
            except Exception as e:
                result[label] = f"ERROR: {e}"
    return result, ticks


async def main():
    async with websockets.connect(URI) as ws:
        print("=== Phase 1: Original ROM (should PASS) ===")
        vals_pass, ticks_pass = await run_phase(ws, use_wrong_rom=False)
        for k, v in sorted(vals_pass.items()):
            print(f"  {k!r}: {v}")

        print(f"\n=== Phase 3: All-FF ROM (should FAIL) ===")
        vals_fail, ticks_fail = await run_phase(ws, use_wrong_rom=True)
        for k, v in sorted(vals_fail.items()):
            print(f"  {k!r}: {v}")

        print(f"\n=== Diff (probes that CHANGED) ===")
        all_keys = set(vals_pass.keys()) | set(vals_fail.keys())
        for k in sorted(all_keys):
            vp = vals_pass.get(k, "MISSING")
            vf = vals_fail.get(k, "MISSING")
            if vp != vf:
                print(f"  {k!r}: PASS={vp!r}  →  FAIL={vf!r}")
        print("\n(unchanged probes omitted)")


if __name__ == "__main__":
    asyncio.run(main())
