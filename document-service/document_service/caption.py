"""
图片描述：对本地图片调用通义千问视觉模型生成一句中文描述。
"""
import base64
import io
import json
import logging
import urllib.request
import urllib.error
from pathlib import Path

from PIL import Image

from .config import get_caption_api_key, get_caption_api_base, get_caption_model

logger = logging.getLogger(__name__)

PROMPT = "请用一句简短的中文描述这张图片的主要内容。只返回描述文字，不要其他解释。"


def generate_caption(image_path: str | Path) -> str:
    path = Path(image_path)
    if not path.is_file():
        logger.warning("图片不存在: %s", path)
        return ""

    api_key = get_caption_api_key()
    if not api_key or not api_key.strip():
        logger.warning("未配置 CAPTION_API_KEY，跳过图片描述")
        return ""

    try:
        img = Image.open(path)
        if img.mode in ("RGBA", "LA", "P"):
            img = img.convert("RGB")
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=95)
        b64 = base64.b64encode(buf.getvalue()).decode("utf-8")
    except Exception as e:
        logger.warning("读取图片失败 %s: %s", path, e)
        return ""

    base = get_caption_api_base()
    model = get_caption_model()
    url = f"{base}/chat/completions" if base else "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

    body = json.dumps({
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                    {"type": "text", "text": PROMPT},
                ],
            }
        ],
        "temperature": 0.3,
    }).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body_str = e.read().decode("utf-8") if e.fp else ""
        logger.warning("描述 API 失败 %s: %s", e.code, body_str[:200])
        return ""

    choice = (data.get("choices") or [None])[0]
    if not choice:
        return ""
    content = (choice.get("message") or {}).get("content")
    if not content:
        return ""
    return content.strip()
