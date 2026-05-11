"""
大纲生成器（outline_service 包内）：输入主题与可选参考文档，输出结构化 PPT 大纲。
"""
import json
import logging
from typing import List, Dict, Callable, Optional

from .prompts import build_outline_prompt

logger = logging.getLogger(__name__)


def flatten_outline(outline: List[Dict]) -> List[Dict]:
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
    cleaned = raw.strip().strip("```json").strip("```").strip()
    # LLM 可能返回 JSON 数组后带说明或第二段 JSON，导致 "Extra data"；只解析第一个完整 JSON
    decoder = json.JSONDecoder()
    obj, _ = decoder.raw_decode(cleaned)
    return obj


def generate_outline(
    user_input: str,
    language: str = "zh",
    extra_requirements: Optional[str] = None,
    reference_files_content: Optional[List[Dict[str, str]]] = None,
    generate_text: Optional[Callable[..., str]] = None,
    thinking_budget: int = 1000,
    max_retries: int = 3,
) -> List[Dict]:
    if not (user_input or "").strip():
        raise ValueError("user_input 不能为空")
    prompt = build_outline_prompt(
        user_input=user_input.strip(),
        language=language,
        extra_requirements=extra_requirements,
        reference_files_content=reference_files_content,
    )
    import sys
    has_ref = bool(reference_files_content)
    preview = prompt[:1200] + ("..." if len(prompt) > 1200 else "")
    sys.stderr.write(
        f"\n========== 发给 LLM 的 prompt (共 {len(prompt)} 字符, 含参考文档: {has_ref}) ==========\n"
        f"{preview}\n"
        f"========== prompt 结束 ==========\n\n"
    )
    sys.stderr.flush()
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
    try:
        return generate_text(prompt, thinking_budget=thinking_budget)
    except TypeError:
        return generate_text(prompt)


def _qwen_generate_text(prompt: str, thinking_budget: int = 0) -> str:
    import urllib.request
    import urllib.error
    from .config import get_api_key, get_api_base, get_model, get_timeout

    api_key = get_api_key()
    if not api_key or not api_key.strip():
        raise RuntimeError(
            "请设置 QWEN_API_KEY 或在本模块 config 中填写 API_KEY。"
            "获取：https://dashscope.console.aliyun.com/"
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
        url, data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
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


def _llm_service_generate_text(prompt: str, thinking_budget: int = 0) -> str:
    """通过 llm-service 的 /llm/chat 调用 LLM，仅在有 LLM 请求时调用 llm-service。"""
    from .config import LLM_SERVICE_URL
    import urllib.request
    import urllib.error

    url = (LLM_SERVICE_URL or "").rstrip("/")
    if not url:
        raise RuntimeError("LLM_SERVICE_URL 未配置")
    chat_url = f"{url}/llm/chat"
    body = json.dumps({
        "messages": [{"role": "user", "content": prompt}],
    }).encode("utf-8")
    req = urllib.request.Request(
        chat_url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    timeout = 120
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body_str = e.read().decode("utf-8") if e.fp else ""
        raise RuntimeError(f"llm-service 请求失败 {e.code}: {body_str[:500]}") from e
    # 兼容 Result<LlmChatResponse>: { code, message, data: { content } }
    if isinstance(data, dict):
        inner = data.get("data") if data.get("data") is not None else data
        if isinstance(inner, dict) and isinstance(inner.get("content"), str):
            return inner["content"]
    raise RuntimeError(f"llm-service 返回格式异常: {data}")


def _get_default_generate_text() -> Callable[..., str]:
    from .config import LLM_SERVICE_URL
    if LLM_SERVICE_URL:
        return _llm_service_generate_text
    return _qwen_generate_text
