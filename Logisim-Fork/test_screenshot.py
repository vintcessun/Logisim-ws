import asyncio
import websockets
import json
import os
from PIL import Image
import io

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

async def send_json(ws, action, **kwargs):
    req = {"action": action, **kwargs}
    await ws.send(json.dumps(req))
    resp = await ws.recv()
    return json.loads(resp)

async def test_screenshot():
    print(f"Connecting to {URI}...")
    async with websockets.connect(URI) as ws:
        # 1. 加载电路文件
        circ_path = os.path.abspath("test_circuits/先行进位加法器.circ")
        print(f"Loading circuit: {circ_path}")
        await send_json(ws, "load_circuit", path=circ_path)

        # 2. 截图（带参数，验证参数是否被忽略）
        print("Taking screenshot (sending width=1920, height=1080)...")
        await ws.send(json.dumps({"action": "get_screenshot", "width": 1920, "height": 1080}))
        binary_data = await ws.recv()
        
        if isinstance(binary_data, bytes):
            # 使用 PIL 加载图片以检查尺寸
            img = Image.open(io.BytesIO(binary_data))
            width, height = img.size
            print(f"Screenshot received. Dimensions: {width}x{height}")
            
            output_path = "test_auto_crop.png"
            img.save(output_path)
            print(f"Screenshot saved to: {os.path.abspath(output_path)}")
            
            # 验证尺寸是否明显小于 1920x1080 (通常电路不会撑满全屏)
            if width < 1920 and height < 1080:
                print("✅ Success: Parameters were ignored and image was cropped.")
            else:
                print("⚠️ Warning: Image dimensions are 1920x1080, check if parameters were really ignored.")
        else:
            print("Failed to receive binary image data.")

if __name__ == "__main__":
    asyncio.run(test_screenshot())
