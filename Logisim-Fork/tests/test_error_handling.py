import asyncio
import websockets
import json
import os

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

async def send_json(ws, action, **kwargs):
    req = {"action": action, **kwargs}
    await ws.send(json.dumps(req))
    resp = await ws.recv()
    return json.loads(resp)

async def test_errors():
    print(f"Connecting to {URI}...")
    async with websockets.connect(URI) as ws:
        circ_path = os.path.abspath("test_circuits/先行进位加法器.circ")
        await send_json(ws, "load_circuit", path=circ_path)
        
        test_cases = [
            ("X", "abc"),      # Not a number
            ("X", "0xG"),      # Invalid Hex
            ("X", "0b12"),     # Invalid Binary
            ("X", ""),         # Empty
            ("X", "09"),       # Invalid Octal (9 is not in base 8)
        ]
        
        for pin, input_val in test_cases:
            print(f"Testing invalid input: '{input_val}' for pin '{pin}'...")
            resp = await send_json(ws, "set_value", target=pin, value=input_val)
            
            if resp.get("status") == "error":
                print(f"  ✅ Correctly failed: {resp.get('message')}")
            else:
                print(f"  ❌ Incorrectly succeeded: {resp}")

if __name__ == "__main__":
    asyncio.run(test_errors())
