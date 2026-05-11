"""
大纲生成器：输入一段文字，输出结构化 PPT 大纲（与原项目 AIService.generate_outline + flatten_outline 一致）。
"""

import json
import logging
import os
from typing import List, Dict, Callable, Optional

from .prompts import build_outline_prompt

logger = logging.getLogger(__name__)


def flatten_outline(outline: List[Dict]) -> List[Dict]:
    """
    将大纲结构压平为页面列表（与原项目 AIService.flatten_outline 一致）。
    - 若项含 "part" 与 "pages"，展开为多页并每页带上 part。
    - 否则视为单页，直接加入列表。
    """
    pages = []
    for item in outline:
        if "part" in item and "pages" in item:
            for page in item["pages"]:
                page_with_part = page.copy()
                page_with_part["part"] = item["part"]
                pages.append(page_with_part)
        else:
            pages.append(item)
    return pages


def _parse_json_response(raw: str) -> List[Dict]:
    """清理模型返回（去 markdown 代码块等）并解析为 JSON 列表。"""
    cleaned = raw.strip().strip("```json").strip("```").strip()
    return json.loads(cleaned)


def generate_outline(
    user_input: str,
    language: str = 'zh',
    extra_requirements: Optional[str] = None,
    reference_files_content: Optional[List[Dict[str, str]]] = None,
    generate_text: Optional[Callable[[str], str]] = None,
    thinking_budget: int = 1000,
    max_retries: int = 3,
) -> List[Dict]:
    """
    根据用户输入的一段文字生成 PPT 大纲（与原项目优秀方法一致）。

    Args:
        user_input: 用户描述（PPT 主题/构想的一段文字）。
        language: 输出语言，'zh' | 'en' | 'ja' | 'auto'。
        extra_requirements: 可选的额外要求。
        reference_files_content: 可选的参考文件 [{"filename": "...", "content": "..."}]。
        generate_text: 调用大模型的方法 (prompt -> str)。若不传，则尝试使用项目 backend 的 TextProvider。
        thinking_budget: 思考预算（部分模型用于推理步数），传 0 表示不启用。
        max_retries: JSON 解析失败时的重试次数。

    Returns:
        压平后的页面列表，每项形如 {"title": "...", "points": [...], "part": "..."(可选)}。
    """
    if not (user_input or "").strip():
        raise ValueError("user_input 不能为空")

    prompt = build_outline_prompt(
        user_input=user_input.strip(),
        language=language,
        extra_requirements=extra_requirements,
        reference_files_content=reference_files_content,
    )

    if generate_text is None:
        generate_text = _get_default_generate_text()

    last_error = None
    for attempt in range(max_retries):
        try:
            response = _call_generate_text(generate_text, prompt, thinking_budget)
            outline = _parse_json_response(response)
            if not isinstance(outline, list):
                outline = [outline]
            return flatten_outline(outline)
        except (json.JSONDecodeError, ValueError, TypeError) as e:
            last_error = e
            logger.warning("大纲 JSON 解析失败 (尝试 %d/%d): %s", attempt + 1, max_retries, e)
    raise last_error or RuntimeError("大纲生成失败")


def _call_generate_text(generate_text: Callable, prompt: str, thinking_budget: int) -> str:
    """调用 generate_text：支持 (prompt) 或 (prompt, thinking_budget) 两种签名。"""
    try:
        return generate_text(prompt, thinking_budget=thinking_budget)
    except TypeError:
        return generate_text(prompt)


def _qwen_generate_text(prompt: str, thinking_budget: int = 0) -> str:
    """
    使用通义千问（Qwen）OpenAI 兼容接口调用，配置来自 大纲生成/config.py。
    仅依赖标准库。
    """
    import urllib.request
    import urllib.error

    from .config import get_api_key, get_api_base, get_model, get_timeout

    api_key = get_api_key()
    if not api_key or not api_key.strip():
        raise RuntimeError(
            "请在 大纲生成/config.py 中填写 API_KEY，或在环境变量中设置 QWEN_API_KEY。"
            "API Key 可从阿里云 DashScope 控制台获取：https://dashscope.console.aliyun.com/"
        )

    base = get_api_base()
    model = get_model()
    timeout = get_timeout()
    url = f"{base}/chat/completions" if base else "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.3,
    }).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body_str = e.read().decode("utf-8") if e.fp else ""
        raise RuntimeError(f"Qwen API 请求失败 {e.code}: {body_str[:500]}") from e

    choice = (data.get("choices") or [None])[0]
    if not choice:
        raise RuntimeError(f"API 返回无 choices: {data}")
    content = (choice.get("message") or {}).get("content")
    if content is None:
        raise RuntimeError(f"API 返回无 content: {choice}")
    return content


def _get_default_generate_text() -> Callable[[str], str]:
    """
    默认文本生成：使用 大纲生成/config.py 中的通义千问（Qwen）配置。
    """
    return _qwen_generate_text
