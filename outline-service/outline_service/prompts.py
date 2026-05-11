"""Outline prompt builder (outline_service package)."""
from typing import Optional

OUTLINE_JSON_FORMAT = """\
Output a JSON array only. Each item: "part" (optional, section name like "一、概述"), "title" (slide title), "points" (array of 2-4 concrete points).
Numbering rule: set "part" ONLY on the first slide of each section; omit "part" on following slides in the same section, so the same section title (e.g. 四、功能展示) is not repeated on every slide.
Example: [{"part":"一、引言","title":"封面","points":["副标题","演讲人"]},{"part":"二、正文","title":"第一节标题","points":["要点一","要点二","要点三"]},{"title":"第二节标题","points":["要点一","要点二"]},{"part":"三、总结","title":"结论","points":["总结要点"]}]
Use real titles and points from the reference documents, not placeholders like p1 or Topic 1."""

LANGUAGE_CONFIG = {
    "zh": {"instruction": "请使用全中文输出。"},
    "ja": {"instruction": "すべて日本語で出力してください。"},
    "en": {"instruction": "Please output all in English."},
    "auto": {"instruction": ""},
}

def _format_requirements(requirements: Optional[str]) -> str:
    if not requirements or not requirements.strip():
        return ""
    return "<user_requirements>\n" + requirements.strip() + "\n</user_requirements>\n\n"

def get_language_instruction(language: Optional[str] = None) -> str:
    lang = (language or "zh").lower()
    return LANGUAGE_CONFIG.get(lang, LANGUAGE_CONFIG["zh"])["instruction"]

def build_outline_prompt(
    user_input: str,
    language: str = "zh",
    extra_requirements: Optional[str] = None,
    reference_files_content: Optional[list] = None,
) -> str:
    lang_instruction = get_language_instruction(language)
    requirements_block = _format_requirements(extra_requirements or "")

    if reference_files_content:
        parts = ["<uploaded_files>"]
        for info in reference_files_content:
            name = info.get("filename", "unknown")
            content = info.get("content", "")
            parts.append(f'  <file name="{name}">')
            parts.append("    <content>")
            parts.append(content)
            parts.append("    </content>")
            parts.append("  </file>")
        parts.append("</uploaded_files>")
        ref_instruction = """
You MUST use the above uploaded reference documents as the PRIMARY source for the outline.
- Extract part titles, slide titles and points FROM the document structure and content (headings, sections, key ideas).
- Do NOT output generic placeholders like "p1", "p2", "Topic 1"; use real titles and points from the documents.
- Section numbering: put "part" (e.g. 一、二、三、四、五) ONLY on the first slide of that section; for other slides in the same section, omit "part" so the section title is not repeated on every slide.
- The user's request below gives the theme; the outline content must come from the documents. You may reorder or merge for PPT length.
"""
        prompt = "\n".join(parts) + "\n" + ref_instruction + f"""
The user's request (theme): {user_input}.
{requirements_block}Generate the outline in the required JSON format only. No other text.
{lang_instruction}
{OUTLINE_JSON_FORMAT}
"""
    else:
        prompt = f"""\
You are a helpful assistant that generates an outline for a ppt.
{OUTLINE_JSON_FORMAT}
The user's request: {user_input}.
{requirements_block}Now generate the outline, don't include any other text.
{lang_instruction}
"""
    return prompt
