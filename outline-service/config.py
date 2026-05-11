"""
大纲生成模块的配置：需要填写的内容集中在此，默认使用通义千问（Qwen）API。
仅本地使用，请直接在本文件中填写 API Key。
获取地址：https://dashscope.console.aliyun.com/
"""

# ------------------------------
# 通义千问（Qwen）API 配置
# ------------------------------

# 直接填写你的 API Key（仅本地跑，无需担心泄漏）
API_KEY = "sk-a2966f4e37134351904851679884cb67"

# API 地址（一般无需修改）
API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"

# 模型名称。可选：qwen-turbo（快）、qwen-plus（平衡）、qwen-max（效果更好）
MODEL = "qwen-turbo"

# 请求超时（秒）
TIMEOUT = 120


def get_api_key() -> str:
    """获取 API Key（以本文件中的 API_KEY 为准）。"""
    return API_KEY


def get_api_base() -> str:
    """获取 API Base URL。"""
    return API_BASE.rstrip("/")


def get_model() -> str:
    """获取模型名。"""
    return MODEL


def get_timeout() -> int:
    """获取请求超时（秒）。"""
    return TIMEOUT
