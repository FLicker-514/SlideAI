"""
大纲生成模块的配置：优先环境变量 QWEN_*，否则尝试从上层 outline-service/config.py 读取。
"""
import os
from pathlib import Path

# 配置后大纲生成将调用 llm-service 的 /llm/chat，不再直连 Qwen。如 http://127.0.0.1:8082
LLM_SERVICE_URL = (os.environ.get("LLM_SERVICE_URL") or "").strip()


def _load_parent_config():
    try:
        parent = Path(__file__).resolve().parent.parent
        cfg = parent / "config.py"
        if cfg.is_file():
            with open(cfg, "r", encoding="utf-8") as f:
                code = f.read()
            ns = {}
            exec(compile(code, str(cfg), "exec"), ns)
            return ns.get("API_KEY", ""), ns.get("API_BASE", ""), ns.get("MODEL", ""), ns.get("TIMEOUT", 120)
    except Exception:
        pass
    return "", "", "", 120

_pkey, _pbase, _pmodel, _ptimeout = _load_parent_config()

API_KEY = os.environ.get("QWEN_API_KEY") or _pkey or ""
API_BASE = os.environ.get("QWEN_API_BASE") or _pbase or "https://dashscope.aliyuncs.com/compatible-mode/v1"
MODEL = os.environ.get("QWEN_MODEL") or _pmodel or "qwen-turbo"
TIMEOUT = int(os.environ.get("QWEN_TIMEOUT", _ptimeout))


def get_api_key() -> str:
    return API_KEY or ""


def get_api_base() -> str:
    return (API_BASE or "").rstrip("/")


def get_model() -> str:
    return MODEL or "qwen-turbo"


def get_timeout() -> int:
    return TIMEOUT
