"""诊断脚本：列出复杂MIPS RAM测试电路中的所有内存组件，确认哪个是期望值存储器。"""

import asyncio
import json
import os
import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"
CIRCUIT_FILE = os.path.abspath("test_circuits/TEA_MIPS RAM 存储器验证实验.circ")


async def main():
    async with websockets.connect(URI) as ws:

        async def send(action, **kw):
            await ws.send(json.dumps({"action": action, **kw}, ensure_ascii=False))
            resp = json.loads(await ws.recv())
            if resp.get("status") == "error":
                raise RuntimeError(f"{action} error: {resp.get('message')}")
            return resp

        r = await send("load_circuit", path=CIRCUIT_FILE)
        print("load:", r["status"])
        r = await send("switch_circuit", name="复杂的MIPS RAM存储器测试电路")
        print("switch:", r["status"])

        # 列出所有内存组件
        r = await send("list_components", is_memory=True)
        comps = r.get("payload", [])
        print(
            f"\n=== Memory components in 复杂的MIPS RAM存储器测试电路 ({len(comps)}) ==="
        )
        for c in comps:
            print(f"  comp_id = {c['comp_id']}")
            print(
                f"    type={c.get('factory_name')}, addr={c.get('addr_bits')}, data={c.get('data_bits')}"
            )
            print(f"    label={c.get('label')!r}, human={c.get('human_name')!r}")
            print(f"    neighbor_hints={c.get('neighbor_hints')}")
            print()

        # 列出所有组件（含非内存）找到 Probe "期望值" 的邻居
        r = await send("list_components")
        all_comps = r.get("payload", [])
        print(f"\n=== All components ({len(all_comps)}) ===")
        for c in all_comps:
            label = c.get("label", "")
            if "期望值" in label or "Dout" in label:
                print(
                    f"  comp_id={c['comp_id']}  type={c.get('factory_name')}  label={label!r}"
                )
                print(f"    neighbor_hints={c.get('neighbor_hints')}")


if __name__ == "__main__":
    asyncio.run(main())
