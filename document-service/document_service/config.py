"""
文件解析模块的配置：仅解析 PDF，MinerU API + 图片描述（通义千问视觉）。
"""
import os
from pathlib import Path

# ------------------------------
# MinerU 解析服务（PDF）。Token 见 https://mineru.net/apiManage/token
# ------------------------------
def _get_mineru_token() -> str:
    t = os.environ.get("MINERU_TOKEN", "")
    if t:
        return t
    try:
        parent = Path(__file__).resolve().parent.parent
        cfg = parent / "config.py"
        if cfg.is_file():
            with open(cfg, encoding="utf-8") as f:
                for line in f:
                    if line.strip().startswith("MINERU_TOKEN") and "=" in line:
                        # 取引号内的值
                        import re
                        m = re.search(r'["\']([^"\']+)["\']', line)
                        if m:
                            return m.group(1).strip()
    except Exception:
        pass
    return ""

MINERU_TOKEN = _get_mineru_token()
MINERU_API_BASE = "https://mineru.net"
MINERU_MODEL_VERSION = "vlm"

# ------------------------------
# 图片描述（通义千问视觉）：未配置 CAPTION_API_KEY 时解析出的 content.md 中图片无描述
# 可通过环境变量 CAPTION_API_KEY 配置，或在此处直接赋值
# ------------------------------
ENABLE_IMAGE_CAPTION = True


def _get_caption_api_key() -> str:
    key = os.environ.get("CAPTION_API_KEY", "").strip()
    if key:
        return key
    try:
        parent = Path(__file__).resolve().parent.parent
        cfg = parent / "config.py"
        if cfg.is_file():
            with open(cfg, encoding="utf-8") as f:
                for line in f:
                    if line.strip().startswith("CAPTION_API_KEY") and "=" in line:
                        import re
                        m = re.search(r'["\']([^"\']*)["\']', line)
                        if m:
                            return (m.group(1) or "").strip()
    except Exception:
        pass
    return ""


CAPTION_API_KEY = _get_caption_api_key()
CAPTION_API_BASE = os.environ.get("CAPTION_API_BASE") or "https://dashscope.aliyuncs.com/compatible-mode/v1"
CAPTION_MODEL = os.environ.get("CAPTION_MODEL") or "qwen-vl-plus"

# ------------------------------
# 存储（parse_to_dir 调用时由调用方传入 storage_root，此处仅作默认）
# ------------------------------
STORAGE_ROOT = None


def get_mineru_token() -> str:
    return MINERU_TOKEN or ""


def get_mineru_api_base() -> str:
    return (MINERU_API_BASE or "https://mineru.net").rstrip("/")


def get_mineru_model_version() -> str:
    return MINERU_MODEL_VERSION or "vlm"


def get_storage_root() -> Path:
    if STORAGE_ROOT:
        return Path(STORAGE_ROOT)
    return Path(__file__).resolve().parent / "parsed_output"


def get_caption_api_key() -> str:
    return CAPTION_API_KEY or ""


def get_caption_api_base() -> str:
    return (CAPTION_API_BASE or "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/")


def get_caption_model() -> str:
    return CAPTION_MODEL or "qwen-vl-plus"


def get_enable_image_caption() -> bool:
    return ENABLE_IMAGE_CAPTION
