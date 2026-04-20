"""
文件解析模块的配置：仅解析 PDF，MinerU API + 图片描述（通义千问视觉）。
仅本地使用，请直接在本文件中填写 Token。
"""

from pathlib import Path

# ------------------------------
# MinerU 解析服务（PDF）
# ------------------------------
# 直接填写 MinerU API Token。获取：https://mineru.net/apiManage/token
MINERU_TOKEN = "eyJ0eXBlIjoiSldUIiwiYWxnIjoiSFM1MTIifQ.eyJqdGkiOiI3ODgwMDM1OSIsInJvbCI6IlJPTEVfUkVHSVNURVIiLCJpc3MiOiJPcGVuWExhYiIsImlhdCI6MTc3MzM4NDMxNiwiY2xpZW50SWQiOiJsa3pkeDU3bnZ5MjJqa3BxOXgydyIsInBob25lIjoiIiwib3BlbklkIjpudWxsLCJ1dWlkIjoiNGQ5YzA5ZTUtZDYzZS00NDc0LTg1ZGYtNDYyMDQ3ZWU1OTBmIiwiZW1haWwiOiIzNDc4NDQ4OTYyeXpsQGdtYWlsLmNvbSIsImV4cCI6MTc4MTE2MDMxNn0.gX88XYmx5FpLnyBb16vSwwUsn0MkXoOW6hcH0j5lESme0LypUs3W7n5YH33RkOkbZ3ghfaYJmTm5aiQ-sYr1kg"

MINERU_API_BASE = "https://mineru.net"
MINERU_MODEL_VERSION = "vlm"

# ------------------------------
# 图片描述（无描述时自动生成）
# ------------------------------
# 是否对没有 alt 的图片自动生成描述（需配置下方 API）
ENABLE_IMAGE_CAPTION = True

# 通义千问 API
CAPTION_API_KEY = "sk-a2966f4e37134351904851679884cb67"
CAPTION_API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
CAPTION_MODEL = "qwen-vl-plus"  # 视觉模型，如 qwen-vl-plus / qwen-vl-max

# ------------------------------
# 存储
# ------------------------------
# 解析结果根目录；每个 PDF 对应其下一個 id 文件夹，id 内为 content.md + 图片
STORAGE_ROOT = None


def get_mineru_token() -> str:
    return MINERU_TOKEN


def get_mineru_api_base() -> str:
    return (MINERU_API_BASE or "https://mineru.net").rstrip("/")


def get_mineru_model_version() -> str:
    return MINERU_MODEL_VERSION or "vlm"


def get_storage_root() -> Path:
    if STORAGE_ROOT:
        return Path(STORAGE_ROOT)
    return Path(__file__).resolve().parent / "parsed_output"


def get_caption_api_key() -> str:
    try:
        from 大纲生成.config import get_api_key
        return get_api_key()
    except ImportError:
        return CAPTION_API_KEY


def get_caption_api_base() -> str:
    try:
        from 大纲生成.config import get_api_base
        return get_api_base()
    except ImportError:
        return (CAPTION_API_BASE or "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/")


def get_caption_model() -> str:
    return CAPTION_MODEL or "qwen-vl-plus"


def get_enable_image_caption() -> bool:
    return ENABLE_IMAGE_CAPTION
