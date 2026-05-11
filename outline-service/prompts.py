"""
大纲生成 Prompt 模块
从原项目 backend/services/prompts.py 抽离，仅保留「从用户输入文字生成 PPT 大纲」的 prompt。
"""

from typing import Optional

# 与原项目一致的 JSON 输出格式说明（供模型遵循）
OUTLINE_JSON_FORMAT = """\
Part-based format (for longer PPTs with major sections):
[
    {
    "part": "Part 1: Introduction",
    "pages": [
        {"title": "Welcome", "points": ["point1", "point2"]},
        {"title": "Overview", "points": ["point1", "point2"]}
    ]
    },
    {
    "part": "Part 2: Main Content",
    "pages": [
        {"title": "Topic 1", "points": ["point1", "point2"]},
        {"title": "Topic 2", "points": ["point1", "point2"]},
        …………………………………………
    ]
    }
    "part": "Part 3: Conclusion",
    "pages": [
        {"title": "Conclusion", "points": ["point1", "point2"]},
        {"title": "Thanks", "points": ["point1", "point2"]}
    ]
    }
]"""


LANGUAGE_CONFIG = {
    'zh': {'instruction': '请使用全中文输出。'},
    'ja': {'instruction': 'すべて日本語で出力してください。'},
    'en': {'instruction': 'Please output all in English.'},
    'auto': {'instruction': ''},
}


def _format_requirements(requirements: Optional[str], context: str = "outline") -> str:
    """格式化用户提供的生成要求（可选）。"""
    if not requirements or not requirements.strip():
        return ""
    marker_example = (
        "For example, if the user asks to avoid '#' symbols, "
        "do NOT use '#' in the page content, but still use '## Title' as "
        "the structural heading delimiter between pages."
    )
    return (
        "<user_requirements>\n"
        f"{requirements.strip()}\n"
        "</user_requirements>\n"
        "Note: The requirements above apply to the generated content of each page and "
        "take precedence over other content-related instructions. The required output format "
        f"and structural markers must still be used as-is. {marker_example}\n\n"
    )


def get_language_instruction(language: Optional[str] = None) -> str:
    """返回语言限制指令，默认中文。"""
    lang = (language or 'zh').lower()
    config = LANGUAGE_CONFIG.get(lang, LANGUAGE_CONFIG['zh'])
    return config['instruction']


def build_outline_prompt(
    user_input: str,
    language: str = 'zh',
    extra_requirements: Optional[str] = None,
    reference_files_content: Optional[list] = None,
) -> str:
    """
    构建「从用户输入生成 PPT 大纲」的完整 prompt（与原项目 get_outline_generation_prompt 逻辑一致）。

    Args:
        user_input: 用户的一段文字（PPT 构想/主题描述）。
        language: 输出语言，'zh' | 'en' | 'ja' | 'auto'。
        extra_requirements: 可选的额外要求（如页数、风格等）。
        reference_files_content: 可选的参考文件列表 [{"filename": "...", "content": "..."}]。

    Returns:
        可直接发给大模型的 prompt 字符串。
    """
    lang_instruction = get_language_instruction(language)
    requirements_block = _format_requirements(extra_requirements or "", context="outline")

    prompt = f"""\
You are a helpful assistant that generates an outline for a ppt.

You can organize the content in two ways:

{OUTLINE_JSON_FORMAT}

Choose the format that best fits the content. Use parts when the PPT has clear major sections.
Unless otherwise specified, the first page should be kept simplest, containing only the title, subtitle, and presenter information.

The user's request: {user_input}.
{requirements_block}Now generate the outline, don't include any other text.
{lang_instruction}
"""
    if reference_files_content:
        prompt = _prepend_reference_files(reference_files_content) + prompt
    return prompt


def _prepend_reference_files(files: list) -> str:
    """在 prompt 前追加参考文件 XML。"""
    parts = ["<uploaded_files>"]
    for info in files:
        name = info.get('filename', 'unknown')
        content = info.get('content', '')
        parts.append(f'  <file name="{name}">')
        parts.append('    <content>')
        parts.append(content)
        parts.append('    </content>')
        parts.append('  </file>')
    parts.append("</uploaded_files>")
    parts.append("")
    return '\n'.join(parts) + '\n'
