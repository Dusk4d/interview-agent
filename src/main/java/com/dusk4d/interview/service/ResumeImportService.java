package com.dusk4d.interview.service;

import com.dusk4d.interview.config.AppProperties;
import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.Resume;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.domain.ResumeStatus;
import com.dusk4d.interview.error.NotFoundException;
import com.dusk4d.interview.error.ResumeParseException;
import com.dusk4d.interview.error.ValidationException;
import com.dusk4d.interview.parse.CleanIssue;
import com.dusk4d.interview.parse.CleanResult;
import com.dusk4d.interview.parse.DocumentText;
import com.dusk4d.interview.parse.ResumeFactExtractor;
import com.dusk4d.interview.parse.TextCleaner;
import com.dusk4d.interview.parse.extract.DocumentExtractorRouter;
import com.dusk4d.interview.privacy.PrivacyMasker;
import com.dusk4d.interview.rag.TextChunk;
import com.dusk4d.interview.rag.VectorStore;
import com.dusk4d.interview.storage.InterviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 简历导入与解析服务（对应方案书 6.1）。
 *
 * <p>处理链路：校验 → 提取文本 → 清洗 → 事实结构化 → 脱敏 → 落库 → 写入向量库。
 * 每一步失败都会返回明确原因（错误码 + 中文提示），不会静默产出「看起来合理」的结果；
 * 图片型 PDF、旧版 doc、空文本等都会在入口处被识别。
 */
@Service
public class ResumeImportService {

    private static final Logger log = LoggerFactory.getLogger(ResumeImportService.class);
    private static final int MAX_TEXT_CHARS = 200_000;

    private final DocumentExtractorRouter extractorRouter;
    private final TextCleaner textCleaner;
    private final ResumeFactExtractor factExtractor;
    private final PrivacyMasker privacyMasker;
    private final InterviewRepository repository;
    private final VectorStore vectorStore;
    private final AppProperties properties;
    private final Clock clock;

    public ResumeImportService(DocumentExtractorRouter extractorRouter,
                               TextCleaner textCleaner,
                               ResumeFactExtractor factExtractor,
                               PrivacyMasker privacyMasker,
                               InterviewRepository repository,
                               VectorStore vectorStore,
                               AppProperties properties,
                               Clock clock) {
        this.extractorRouter = extractorRouter;
        this.textCleaner = textCleaner;
        this.factExtractor = factExtractor;
        this.privacyMasker = privacyMasker;
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.clock = clock;
    }

    /** 导入结果。 */
    public record ImportResult(Resume resume, List<String> warnings, double confidence) {
    }

    /** 从上传文件导入。 */
    public ImportResult importFile(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw ResumeParseException.emptyText("上传的文件内容为空，请重新选择文件。");
        }
        long maxBytes = properties.parser().resolvedMaxFileBytes();
        if (content.length > maxBytes) {
            throw new ResumeParseException("FILE_TOO_LARGE",
                    "文件过大（" + humanSize(content.length) + "），上限为 " + humanSize(maxBytes) + "。");
        }
        DocumentText document = extractorRouter.extract(fileName, content);
        return buildResume(displayName(fileName), extractorRouter.detectType(fileName, content),
                content.length, document);
    }

    /** 从纯文本导入（兜底输入，例如用户直接粘贴简历）。 */
    public ImportResult importText(String text, String sourceName) {
        if (text == null || text.isBlank()) {
            throw ResumeParseException.emptyText("粘贴的简历内容为空，请补充后再提交。");
        }
        byte[] content = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DocumentText document = new DocumentText(text, "txt",
                (int) text.lines().filter(l -> !l.isBlank()).count(), false, false, List.of());
        return buildResume(sourceName == null || sourceName.isBlank() ? "手动粘贴的简历" : sourceName,
                "txt", content.length, document);
    }

    // ---------------------------------------------------------------- 核心流程

    private ImportResult buildResume(String fileName, String fileType, long fileSize, DocumentText document) {
        List<String> warnings = new ArrayList<>(document.warnings());

        if (document.passwordProtected()) {
            throw ResumeParseException.unsupported("该 PDF 需要打开密码，无法解析。请提供未加密的版本。");
        }
        if (document.imageOnly()) {
            throw ResumeParseException.unsupported(
                    "检测到图片型/扫描版文档，第一版不支持 OCR，因此无法解析内容。"
                            + "请上传可选中文字的 PDF，或改用 DOCX/TXT，也可以直接粘贴文本。");
        }

        String truncated = document.rawText() == null ? "" : document.rawText();
        if (truncated.length() > MAX_TEXT_CHARS) {
            truncated = truncated.substring(0, MAX_TEXT_CHARS);
            warnings.add("文本超过 " + MAX_TEXT_CHARS + " 字符，仅解析前 " + MAX_TEXT_CHARS + " 字符。");
        }

        CleanResult cleaned = textCleaner.clean(truncated);
        for (CleanIssue issue : cleaned.issues()) {
            warnings.add(describe(issue));
        }
        if (!cleaned.usable()) {
            throw ResumeParseException.emptyText("简历解析后没有可用文本："
                    + String.join("；", warnings) + "。请检查文件内容或直接粘贴文本。");
        }

        ResumeFactExtractor.Result extracted = factExtractor.extract(cleaned.text());
        warnings.addAll(extracted.warnings());

        String masked = privacyMasker.mask(cleaned.text());
        if (properties.privacy().maskName() && extracted.name() != null) {
            masked = privacyMasker.maskName(masked, extracted.name());
        }

        Instant now = clock.instant();
        String resumeId = UUID.randomUUID().toString();
        List<ResumeFact> facts = new ArrayList<>();
        for (ResumeFact fact : extracted.facts()) {
            facts.add(new ResumeFact(fact.id(), resumeId, fact.type(), fact.label(), fact.content(),
                    fact.sourceOrder(), fact.confidence(), fact.metadata()));
        }

        ResumeStatus status = extracted.confidence() < 0.5 ? ResumeStatus.NEEDS_REVIEW : ResumeStatus.PARSED;
        Resume resume = new Resume(resumeId, fileName, fileType, fileSize, masked, masked, status,
                null, warnings, facts, now, now);
        repository.saveResume(resume);
        indexFacts(resume);

        log.info("简历导入完成：id={} 类型={} 事实={} 技术栈={} 置信度={}",
                resumeId, fileType, facts.size(), extracted.techStack().size(),
                Math.round(extracted.confidence() * 100) / 100.0);
        return new ImportResult(resume, warnings, extracted.confidence());
    }

    /** 把事实写入向量库（元数据含简历 ID、区块类型、项目名与来源顺序）。 */
    private void indexFacts(Resume resume) {
        vectorStore.deleteByResume(resume.id());
        List<TextChunk> chunks = new ArrayList<>();
        for (ResumeFact fact : resume.facts()) {
            if (fact.content() == null || fact.content().isBlank()) {
                continue;
            }
            chunks.add(TextChunk.resumeFact(fact.id(), resume.id(), fact.type(), fact.label(),
                    fact.type() == FactType.PROJECT ? fact.label() : null,
                    fact.content(), fact.sourceOrder(), fact.metadata()));
        }
        vectorStore.upsert(chunks);
    }

    // ---------------------------------------------------------------- 查询与人工修正

    public Resume require(String resumeId) {
        return repository.findResume(resumeId)
                .orElseThrow(() -> new NotFoundException("简历", resumeId));
    }

    public Optional<Resume> find(String resumeId) {
        return repository.findResume(resumeId);
    }

    public List<Resume> list() {
        return repository.listResumes();
    }

    /** 人工修正解析结果：整体替换事实列表，并重建检索索引。 */
    public Resume updateFacts(String resumeId, List<ResumeFact> facts) {
        Resume resume = require(resumeId);
        if (facts == null || facts.isEmpty()) {
            throw new ValidationException("修正后的内容不能为空；如果确实要清空，请重新导入简历。");
        }
        List<ResumeFact> normalized = new ArrayList<>();
        int order = 0;
        for (ResumeFact fact : facts) {
            order++;
            String content = privacyMasker.mask(fact.content() == null ? "" : fact.content());
            if (content.isBlank()) {
                throw new ValidationException("第 " + order + " 条事实内容为空，请补充内容或删除该条。");
            }
            String label = fact.label() == null || fact.label().isBlank()
                    ? defaultLabel(fact.type()) : fact.label().strip();
            normalized.add(new ResumeFact(
                    fact.id() == null || fact.id().isBlank()
                            ? "resume-" + fact.type().name().toLowerCase(Locale.ROOT) + "-" + String.format("%03d", order)
                            : fact.id(),
                    resumeId,
                    fact.type(),
                    label,
                    content,
                    order,
                    1.0,
                    fact.metadata()));
        }
        Resume updated = resume.withFacts(normalized);
        repository.saveResume(updated);
        indexFacts(updated);
        log.info("简历 {} 的人工修正已保存，共 {} 条事实", resumeId, normalized.size());
        return updated;
    }

    /** 重新结构化（用户修正原始文本后重跑抽取）。 */
    public Resume restructure(String resumeId, String rawText) {
        Resume resume = require(resumeId);
        if (rawText == null || rawText.isBlank()) {
            throw new ValidationException("原始文本不能为空。");
        }
        CleanResult cleaned = textCleaner.clean(rawText);
        if (!cleaned.usable()) {
            throw ResumeParseException.emptyText("修改后的文本没有可用内容。");
        }
        ResumeFactExtractor.Result extracted = factExtractor.extract(cleaned.text());
        String masked = privacyMasker.mask(cleaned.text());
        List<ResumeFact> facts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ResumeFact fact : extracted.facts()) {
            if (!seen.add(fact.id())) {
                continue;
            }
            facts.add(new ResumeFact(fact.id(), resumeId, fact.type(), fact.label(), fact.content(),
                    fact.sourceOrder(), fact.confidence(), fact.metadata()));
        }
        Resume updated = resume.withFacts(facts);
        repository.saveResume(updated);
        indexFacts(updated);
        return updated;
    }

    public boolean delete(String resumeId) {
        boolean removed = repository.findResume(resumeId).isPresent();
        if (removed) {
            vectorStore.deleteByResume(resumeId);
        }
        return removed;
    }

    // ---------------------------------------------------------------- 工具

    private String defaultLabel(FactType type) {
        return switch (type) {
            case EDUCATION -> "教育经历";
            case INTERNSHIP -> "实习/工作经历";
            case PROJECT -> "未命名项目";
            case SKILL -> "技能";
            case AWARD -> "奖项";
            case SUMMARY -> "个人概况";
            case KNOWLEDGE -> "知识点";
            case OTHER -> "其它";
        };
    }

    private String displayName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文件";
        }
        String normalized = fileName.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String describe(CleanIssue issue) {
        return switch (issue) {
            case EMPTY_TEXT -> "清洗后没有可用文本";
            case MOSTLY_NOISE -> "文本几乎都是符号，可能是解析错误";
            case IMAGE_ONLY_PDF -> "PDF 没有文字层";
            case ENCODING_GARBLED -> "检测到乱码，解析结果可能不准确";
            case TOO_SHORT -> "文本过短，建议核对解析结果";
        };
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
