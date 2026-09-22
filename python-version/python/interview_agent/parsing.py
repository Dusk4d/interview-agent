from __future__ import annotations

import io
import re
from collections import Counter
from pathlib import Path

from docx import Document
from pypdf import PdfReader

from .errors import parse_error
from .models import FactType, ResumeFact
from .privacy import mask

SECTION_TYPES: list[tuple[FactType, tuple[str, ...]]] = [
    (FactType.EDUCATION, ("教育经历", "教育背景", "教育", "学历")),
    (FactType.INTERNSHIP, ("实习经历", "工作经历", "实践经历", "工作经验", "实习")),
    (FactType.PROJECT, ("项目经历", "项目经验", "个人项目", "项目")),
    (FactType.SKILL, ("专业技能", "技能清单", "技能", "技术栈")),
    (FactType.AWARD, ("获奖经历", "获奖情况", "荣誉奖励", "获奖荣誉", "荣誉", "获奖", "奖项", "竞赛")),
    (FactType.SUMMARY, ("个人总结", "自我评价", "个人简介", "基本信息")),
]

TECH_RE = re.compile(
    r"(?<![A-Za-z0-9])(?:Spring\s+Boot|Spring\s+Cloud|Spring\s+MVC|MyBatis-Plus|"
    r"JavaScript|TypeScript|PostgreSQL|Elasticsearch|RabbitMQ|RocketMQ|Kubernetes|TensorFlow|"
    r"LangChain4j|FastAPI|MongoDB|PyTorch|pgvector|Embedding|Docker|Redis|MySQL|Kafka|"
    r"Python|Java|C\+\+|React|Vue3?|Spring|Flask|Django|Linux|Git|JVM|Netty|RAG|Go|SQL|Ollama)"
    r"(?![A-Za-z0-9])", re.I
)
DATE_RE = re.compile(r"(?:19|20)\d{2}[./年-]\d{1,2}(?:月)?\s*[-—~至]\s*(?:(?:19|20)\d{2}[./年-]\d{1,2}(?:月)?|至今)")


def extract_document(file_name: str, content: bytes) -> tuple[str, str, list[str]]:
    if not content:
        raise parse_error("EMPTY_RESUME_TEXT", "上传的文件内容为空，请重新选择文件。")
    suffix = Path(file_name or "").suffix.lower()
    warnings: list[str] = []
    try:
        if suffix == ".pdf" or content.startswith(b"%PDF"):
            reader = PdfReader(io.BytesIO(content))
            if reader.is_encrypted:
                try:
                    if not reader.decrypt(""):
                        raise ValueError("password required")
                except Exception as exc:
                    raise parse_error("UNSUPPORTED_FILE", "该 PDF 需要打开密码，无法解析。") from exc
            text = "\n".join((page.extract_text() or "") for page in reader.pages)
            if not text.strip():
                raise parse_error("UNSUPPORTED_FILE", "检测到图片型/扫描版 PDF；Python 版不包含 OCR，请上传可选中文字的文件。")
            return text, "pdf", warnings
        if suffix == ".docx" or content.startswith(b"PK"):
            doc = Document(io.BytesIO(content))
            parts = [p.text for p in doc.paragraphs if p.text.strip()]
            for table in doc.tables:
                parts.extend(" | ".join(cell.text.strip() for cell in row.cells) for row in table.rows)
            return "\n".join(parts), "docx", warnings
        if suffix not in {".txt", ".md", ""}:
            raise parse_error("UNSUPPORTED_FILE", "不支持该文件类型；请选择 PDF、DOCX 或 TXT。")
        controls = sum(byte == 0 or (byte < 9) or (13 < byte < 32) for byte in content)
        if controls > max(1, len(content) // 20):
            raise parse_error("UNSUPPORTED_FILE", "文件包含大量二进制控制字符，无法按纯文本解析。")
        for encoding in ("utf-8-sig", "gb18030"):
            try:
                return content.decode(encoding), "txt", warnings
            except UnicodeDecodeError:
                pass
        warnings.append("文件编码无法准确识别，已替换无法解码的字符。")
        return content.decode("utf-8", errors="replace"), "txt", warnings
    except ApiError:
        raise
    except Exception as exc:
        raise parse_error("UNSUPPORTED_FILE", f"文档解析失败：{exc}") from exc


def clean_text(text: str) -> tuple[str, list[str]]:
    text = (text or "").replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
    text = re.sub(r"[\u200b\u200c\u200d\ufeff]", "", text)
    lines = [re.sub(r"[\t\u00a0]+", " ", line).strip() for line in text.splitlines()]
    nonempty = [line for line in lines if line]
    if not nonempty:
        raise parse_error("EMPTY_RESUME_TEXT", "简历解析后没有可用文本。")
    counts = Counter(nonempty)
    repeated = {line for line, count in counts.items() if count >= 3 and len(line) < 80}
    cleaned: list[str] = []
    for line in lines:
        if (not line or line in repeated
                or re.fullmatch(r"[-—_ ]*(?:第?\s*\d+\s*页?|\d+\s*/\s*\d+)[-—_ ]*", line)
                or re.fullmatch(r"[-—_=·•*#]{3,}", line)):
            continue
        cleaned.append(line)
    result = "\n".join(cleaned)
    warnings: list[str] = []
    if len(result) < 80:
        warnings.append("文本过短，建议核对解析结果。")
    replacement_count = result.count("�")
    mojibake_count = len(re.findall(r"(?:锟斤拷|烫烫|屯屯|鏂囦欢|绠€鍘†)", result))
    if replacement_count > max(2, len(result) // 100) or mojibake_count >= 2:
        warnings.append("检测到乱码，解析结果可能不准确。")
    compact = re.sub(r"\s", "", result)
    meaningful = re.findall(r"[A-Za-z0-9\u4e00-\u9fff]", compact)
    if len(meaningful) < max(2, int(len(compact) * 0.15)):
        raise parse_error("EMPTY_RESUME_TEXT", "简历内容几乎都是符号，无法提取有效文本。")
    return result, warnings


def _heading(line: str) -> FactType | None:
    compact = re.sub(r"[\s:：|/\\\-【】\[\]（）()]", "", line)
    compact = re.sub(r"^(?:第?[一二三四五六七八九十]+|\d+)[、.．)]?", "", compact)
    if len(compact) > 16:
        return None
    for kind, names in SECTION_TYPES:
        if compact in names:
            return kind
    return None


def extract_facts(text: str, resume_id: str) -> tuple[list[ResumeFact], float, list[str]]:
    sections: list[tuple[FactType, str, list[str]]] = []
    current_type = FactType.SUMMARY
    current_label = "个人概况"
    body: list[str] = []
    for line in text.splitlines():
        kind = _heading(line)
        if kind:
            if body:
                sections.append((current_type, current_label, body))
            current_type, current_label, body = kind, line.strip("：: "), []
        else:
            body.append(line)
    if body:
        sections.append((current_type, current_label, body))

    facts: list[ResumeFact] = []
    order = 0
    for kind, heading, lines in sections:
        chunks: list[list[str]] = []
        if kind in {FactType.PROJECT, FactType.INTERNSHIP, FactType.EDUCATION}:
            active: list[str] = []
            for line in lines:
                # Only an actual dated/numbered heading starts a new item.  Lines such
                # as “项目背景” and “项目描述” are body fields, not new projects.
                numbered = bool(re.match(r"^\s*\d+\s*[、.)．]", line))
                dated = bool(DATE_RE.search(line))
                begins = numbered or dated
                if begins and active:
                    chunks.append(active)
                    active = []
                active.append(line)
            if active:
                chunks.append(active)
        else:
            chunks = [lines]
        for chunk in chunks:
            content = mask("\n".join(chunk).strip())
            if not content:
                continue
            order += 1
            first = chunk[0].strip("：: |-—") if chunk else heading
            label = mask(first[:60] if kind in {FactType.PROJECT, FactType.INTERNSHIP, FactType.EDUCATION} else heading)
            tech = list(dict.fromkeys(match.group(0) for match in TECH_RE.finditer(content)))
            dates = DATE_RE.search(content)
            metadata = tech + ([f"时间：{dates.group(0)}"] if dates else [])
            facts.append(ResumeFact(
                id=f"resume-{kind.value.lower()}-{order:03d}", resumeId=resume_id, type=kind,
                label=label or kind.value, content=content, sourceOrder=order,
                confidence=0.9 if kind != FactType.SUMMARY else 0.7, metadata=metadata,
            ))
    all_tech = list(dict.fromkeys(match.group(0) for match in TECH_RE.finditer(text)))
    if all_tech and not any(f.type == FactType.SKILL for f in facts):
        order += 1
        facts.append(ResumeFact(id=f"resume-skill-{order:03d}", resumeId=resume_id, type=FactType.SKILL,
                                label="技能总览", content="、".join(all_tech), sourceOrder=order,
                                confidence=0.85, metadata=all_tech))
    if not facts:
        facts.append(ResumeFact(id="resume-other-001", resumeId=resume_id, type=FactType.OTHER,
                                label="简历内容", content=mask(text), sourceOrder=1, confidence=0.4))
    confidence = round(sum(f.confidence for f in facts) / len(facts), 2)
    warnings = [] if confidence >= 0.5 else ["结构化置信度较低，请人工核对事实。"]
    if not any(f.type == FactType.PROJECT for f in facts):
        warnings.append("未识别到项目经历；不会根据缺失内容编造项目，请人工确认。")
    return facts, confidence, warnings


# Avoid a circular import in the exception passthrough above.
from .errors import ApiError  # noqa: E402
