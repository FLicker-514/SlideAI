# 大纲生成：从用户输入文字生成 PPT 大纲（抽离自原项目）

from .prompts import build_outline_prompt, get_language_instruction, OUTLINE_JSON_FORMAT
from .generator import generate_outline, flatten_outline

__all__ = [
    "build_outline_prompt",
    "get_language_instruction",
    "OUTLINE_JSON_FORMAT",
    "generate_outline",
    "flatten_outline",
]
