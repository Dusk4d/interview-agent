package com.dusk4d.interview.parse;

import com.dusk4d.interview.domain.FactType;
import com.dusk4d.interview.domain.ResumeFact;
import com.dusk4d.interview.privacy.PrivacyMasker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历事实结构化（方案书 6.1 第 3~4 步）。
 *
 * <p>设计原则：
 * <ol>
 *   <li><b>不编造</b>：只重组原文行，不生成简历中不存在的内容；无法归类的部分落到 OTHER，仍然保留。</li>
 *   <li><b>可溯源</b>：每条事实带 sourceOrder（原文顺序）与 label（项目/公司/学校名）。</li>
 *   <li><b>保守分段</b>：项目按「标题行（含时间或项目标记）」切块；找不到标题时按空行段落合并，
 *       宁可分段粗一点，也不要丢掉上下文。</li>
 *   <li><b>带置信度</b>：低置信度会提示用户手工修正，而不是假装解析成功。</li>
 * </ol>
 */
@Component
public class ResumeFactExtractor {

    /** 区块类型（内部）。 */
    private enum Section { BASICS, EDUCATION, INTERNSHIP, PROJECT, SKILLS, AWARDS, OTHER }

    /** 抽取结果。 */
    public record Result(List<ResumeFact> facts, Set<String> techStack, String name,
                         List<String> warnings, double confidence) {
        public Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            techStack = techStack == null ? Set.of() : Set.copyOf(techStack);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    // ---------------------------------------------------------------- 标题识别
    private static final Map<Section, List<String>> HEADER_KEYWORDS = Map.of(
            Section.EDUCATION, List.of("教育经历", "教育背景", "教育信息", "学习经历", "学历",
                    "education", "academic"),
            Section.INTERNSHIP, List.of("实习经历", "工作经历", "实习经验", "工作经验", "职业经历",
                    "实习", "任职经历", "项目与实习", "experience", "employment", "internship"),
            Section.PROJECT, List.of("项目经历", "项目经验", "实践经历", "主要项目", "项目介绍", "科研经历",
                    "projects", "project experience"),
            Section.SKILLS, List.of("专业技能", "技术栈", "技能特长", "技能清单", "掌握技能", "技能",
                    "个人技能", "技术能力", "skills", "technical skills"),
            Section.AWARDS, List.of("获奖情况", "荣誉奖项", "奖项荣誉", "获奖经历", "荣誉",
                    "awards", "honors"),
            Section.BASICS, List.of("个人信息", "基本信息", "自我评价", "个人简介", "自我介绍", "联系方式",
                    "profile", "summary", "about me")
    );

    private static final List<String> PROJECT_TITLE_MARKERS =
            List.of("项目", "系统", "平台", "网站", "服务", "引擎", "app", "小程序", "工具");

    /**
     * 时间区间。月/日允许 1~2 位，分隔符允许 . - / 年 月 与 至 到 ～ ~。
     * 注意量词必须写成 {1,2} 而不是 {1,2}?，否则「2024.05」会被拆成「2024.0」。
     */
    private static final Pattern RANGE = Pattern.compile(
            "(\\d{4}\\s*[.\\-/年]\\s*\\d{1,2}\\s*月?)\\s*[-—~～至到]{1,2}\\s*"
                    + "(至今|现在|now|present|\\d{4}\\s*[.\\-/年]\\s*\\d{1,2}\\s*月?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_TOKEN = Pattern.compile(
            "\\d{4}\\s*[.\\-/年]\\s*\\d{1,2}\\s*月?|\\d{4}\\s*年?", Pattern.CASE_INSENSITIVE);
    /** 行首的「字段名：」结构，用于把「技术栈：Java、Spring」这类内容行与标题行区分开。 */
    private static final Pattern LABEL_PREFIX = Pattern.compile(
            "^(项目名称|项目名|项目背景|项目描述|项目简介|项目周期|项目规模|项目地址|项目职责|项目成果|项目收获"
                    + "|课题名称|题目|时间|周期|起止时间|岗位|职位|公司|学校|专业|学历|GPA|排名|成绩"
                    + "|技术栈|技术选型|开发环境|使用技术|核心技术|主要技术|技术方案"
                    + "|个人职责|本人职责|我的职责|负责内容|职责描述|工作内容|个人工作|承担工作"
                    + "|主要工作|负责模块|难点|技术难点|主要难点|项目难点|成果|结果|项目结果|业绩|产出|收益"
                    + "|技能|专业技能|获奖|奖项|荣誉|教育经历|实习经历|项目经历)[:：]");
    private static final Pattern DEGREE = Pattern.compile("(博士|硕士|本科|学士|专科|大专|高中|研究生)");
    private static final Pattern SCHOOL = Pattern.compile("([\\u4e00-\\u9fa5]{2,12}(?:大学|学院|学校|中学))");
    private static final Pattern COMPANY = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9（）()]{2,24}(?:有限公司|股份有限公司|集团|科技公司|研究院|研究所|银行|事业部|工作室))");
    private static final Pattern POSITION = Pattern.compile(
            "(?:实习生|工程师|开发|架构师|负责人|研究员|分析师|专员|经理|主管|组长|助教|讲师)");

    /** 技术栈词典（可扩展；按最长优先匹配，避免 "Java" 命中 "JavaScript" 的边界问题）。 */
    private static final List<String> TECH_TERMS = List.of(
            "Java", "JavaScript", "TypeScript", "Python", "Go", "Golang", "C++", "C#", "Rust", "Scala", "Kotlin", "PHP", "Ruby", "SQL",
            "Spring Boot", "Spring Cloud", "Spring MVC", "Spring AI", "Spring Security", "Spring Framework", "Spring",
            "MyBatis-Plus", "MyBatis", "Hibernate", "JPA", "Netty", "Dubbo", "Nacos", "Sentinel",
            "FastAPI", "Flask", "Django", "Tornado", "Celery", "SQLAlchemy", "Pydantic", "uvicorn", "gunicorn",
            "Gin", "Beego", "Gorm",
            "MySQL", "PostgreSQL", "Oracle", "SQL Server", "MongoDB", "SQLite", "ClickHouse", "Elasticsearch", "ElasticSearch",
            "Redis", "Kafka", "RabbitMQ", "RocketMQ", "ZooKeeper", "Nginx", "Tomcat",
            "Docker", "Kubernetes", "K8s", "Jenkins", "GitLab CI", "ArgoCD", "Prometheus", "Grafana", "SkyWalking", "ELK",
            "Vue", "Vue3", "React", "Angular", "Element Plus", "Vite", "Webpack", "Node.js", "HTML", "CSS", "Axios",
            "LangChain", "LangChain4j", "LlamaIndex", "RAG", "Rerank", "pgvector", "Milvus", "Faiss", "Chroma", "向量检索", "Embedding",
            "GPT", "Qwen", "DeepSeek", "Ollama", "LM Studio", "OpenAI", "DashScope", "通义千问", "大模型", "Prompt",
            "JVM", "GC", "JUC", "并发", "多线程", "分布式锁", "分布式事务", "微服务", "限流", "熔断", "幂等",
            "Linux", "Shell", "Git", "Maven", "Gradle", "RESTful", "gRPC", "WebSocket", "SSE",
            "JUnit", "Mockito", "JMeter", "Postman", "JWT", "OAuth2", "Knife4j", "Swagger",
            "OpenCV", "PyTorch", "TensorFlow", "Pandas", "NumPy", "Scrapy", "Selenium", "Playwright",
            "C语言", "数据结构", "算法", "计算机网络", "操作系统", "设计模式", "微服务架构", "领域驱动", "DDD"
    );

    private final TextCleaner cleaner;
    private final PrivacyMasker masker;
    /** 是否存在明确的「项目经历」标题；没有标题时项目抽取必须更保守，避免把段落当项目。 */
    private boolean projectSectionDeclared;

    public ResumeFactExtractor(TextCleaner cleaner, PrivacyMasker masker) {
        this.cleaner = cleaner;
        this.masker = masker;
    }

    /** 从清洗后的简历文本抽取结构化事实。 */
    public Result extract(String cleanedText) {
        List<String> warnings = new ArrayList<>();
        projectSectionDeclared = false;
        if (cleanedText == null || cleanedText.isBlank()) {
            return new Result(List.of(), Set.of(), null, List.of("文本为空，无法抽取事实。"), 0d);
        }
        String text = cleaner.normalize(cleanedText);
        List<String> lines = List.of(text.split("\n", -1));

        Map<Section, List<String>> sections = splitSections(lines);
        List<ResumeFact> facts = new ArrayList<>();

        int order = 1;
        Set<String> techStack = new LinkedHashSet<>(detectTechStack(text));

        order = appendSimpleSection(facts, FactType.SUMMARY, "个人概况", sections.get(Section.BASICS), order,
                labelFromLines(sections.get(Section.BASICS), SCHOOL, "个人概况"));
        order = appendSimpleSection(facts, FactType.EDUCATION, "教育经历", sections.get(Section.EDUCATION), order,
                educationLabel(sections.get(Section.EDUCATION)));
        order = appendSimpleSection(facts, FactType.INTERNSHIP, "实习/工作经历", sections.get(Section.INTERNSHIP), order,
                companyLabel(sections.get(Section.INTERNSHIP)));
        order = appendProjectSection(facts, sections.get(Section.PROJECT), order, warnings);
        order = appendSimpleSection(facts, FactType.SKILL, "技能", sections.get(Section.SKILLS), order, "技能");
        order = appendSimpleSection(facts, FactType.AWARD, "奖项", sections.get(Section.AWARDS), order, "奖项");
        order = appendSimpleSection(facts, FactType.OTHER, "其它", sections.get(Section.OTHER), order, "其它");

        if (techStack.isEmpty()) {
            warnings.add("未识别到明确的技术栈关键词。");
        }
        if (facts.stream().noneMatch(f -> f.type() == FactType.PROJECT)) {
            warnings.add("未识别到项目经历区块，项目面将缺少可检索事实，建议手动补充或调整简历分段。");
        }

        // 汇总式技术栈事实：用于「这个简历用了哪些技术」这类检索
        if (!techStack.isEmpty()) {
            String joined = String.join("、", techStack);
            facts.add(new ResumeFact("resume-techstack-index", null, FactType.SKILL,
                    "技术栈总览", joined, 9000, 0.9, List.copyOf(techStack)));
        }

        double confidence = confidenceOf(facts, techStack, warnings);
        if (confidence < 0.6) {
            warnings.add("结构化置信度较低（" + Math.round(confidence * 100) + "%），建议人工核对或修正解析结果。");
        }
        String name = detectName(lines);
        return new Result(facts, techStack, name, warnings, confidence);
    }

    // ---------------------------------------------------------------- 分区块

    private Map<Section, List<String>> splitSections(List<String> lines) {
        Map<Section, List<String>> sections = new LinkedHashMap<>();
        for (Section s : Section.values()) {
            sections.put(s, new ArrayList<>());
        }
        Section current = Section.BASICS;
        boolean headerSeen = false;
        for (String line : lines) {
            Section header = matchHeader(line);
            if (header != null) {
                current = header;
                headerSeen = true;
                if (header == Section.PROJECT) {
                    projectSectionDeclared = true;
                }
                continue;
            }
            sections.get(current).add(line);
        }
        if (!headerSeen) {
            // 完全没有标题：整体作为 OTHER，同时交给项目启发式尝试识别（要求更严格）
            sections.get(Section.OTHER).addAll(lines);
            sections.get(Section.PROJECT).addAll(lines);
        }
        return sections;
    }

    /**
     * 判断一行是否是区块标题；同时防误判：正文句子（含句号、问号、过长）不视为标题。
     *
     * <p>标题行去掉装饰后再去掉所有空白，因此「技 术 栈」这类排版（简历模板常用字间距）
     * 也能被识别。
     */
    Section matchHeader(String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.length() > 30) {
            return null;
        }
        if (line.endsWith("。") || line.endsWith("！") || line.endsWith("？")
                || line.endsWith(".") || line.endsWith(";") || line.endsWith("；")) {
            return null;
        }
        // 形如「项目经历：xxx」是内容行而不是标题
        if (line.matches("^[\\u4e00-\\u9fa5A-Za-z]{2,8}[:：].+")) {
            return null;
        }
        String bare = stripHeaderDecoration(line).replaceAll("\\s+", "");
        String lower = bare.toLowerCase(Locale.ROOT);
        Section matched = null;
        int matchedCount = 0;
        for (Map.Entry<Section, List<String>> entry : HEADER_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.equals(keyword.toLowerCase(Locale.ROOT))
                        || lower.startsWith(keyword.toLowerCase(Locale.ROOT))) {
                    matched = entry.getKey();
                    matchedCount++;
                    break;
                }
            }
        }
        if (matched == null) {
            return null;
        }
        // 命中多个区块关键词（如「项目与实习」）时取更具体者；过长的行直接放弃
        if (matchedCount > 1 && line.length() > 12) {
            return null;
        }
        return matched;
    }

    /** 去掉标题前后装饰：序号、冒号、方括号、dash。 */
    private String stripHeaderDecoration(String line) {
        String result = line.replaceAll("^[\\s\\d一二三四五六七八九十]+[、.．)）:：\\-—\\s]*", "");
        result = result.replaceAll("[:：]\\s*$", "");
        result = result.replaceAll("^[\\[【（(]+", "").replaceAll("[\\]】）)]+$", "");
        return result.strip();
    }

    private int appendSimpleSection(List<ResumeFact> facts, FactType type, String fallbackLabel,
                                    List<String> lines, int order, String label) {
        if (lines == null) {
            return order;
        }
        String content = sanitizeBlock(lines);
        if (content.isBlank()) {
            return order;
        }
        facts.add(new ResumeFact(factId(type.name(), order), null, type,
                label == null || label.isBlank() ? fallbackLabel : label, content, order,
                type == FactType.OTHER ? 0.4 : 0.8, List.of()));
        return order + 1;
    }

    /** 项目区块：按标题行切块，每块一条事实，并提取项目技术栈。 */
    private int appendProjectSection(List<ResumeFact> facts, List<String> lines, int order, List<String> warnings) {
        if (lines == null || lines.isEmpty()) {
            return order;
        }
        List<List<String>> blocks = splitProjectBlocks(lines);
        for (List<String> block : blocks) {
            String content = sanitizeBlock(block);
            String first = block.stream().filter(l -> !l.isBlank()).findFirst().orElse("项目经历");
            boolean titleLike = looksLikeTitle(first);
            // 没有「项目经历」标题时（例如极简简历），只保留同时具备时间区间与标题特征，
            // 且内容足够长的块——否则会把「做过一些后端开发的工作」这类句子当成项目。
            boolean credible = projectSectionDeclared
                    ? content.replaceAll("\\s", "").length() >= 8
                    : (titleLike && RANGE.matcher(content).find() && content.replaceAll("\\s", "").length() >= 20);
            if (!credible) {
                continue;
            }
            String name = projectName(first);
            double confidence = titleLike ? 0.9 : 0.55;
            if (confidence < 0.6) {
                warnings.add("项目标题识别置信度较低：「" + masker.forLog(first, 24) + "」，建议人工确认项目名。");
            }
            List<String> meta = new ArrayList<>();
            Matcher range = RANGE.matcher(content);
            if (range.find()) {
                meta.add("时间：" + range.group().replaceAll("\\s+", ""));
            }
            List<String> tech = detectTechStack(content);
            meta.addAll(tech);
            facts.add(new ResumeFact(factId("PROJECT", order), null,
                    FactType.PROJECT, name, content, order, confidence, meta));
            order++;
        }
        return order;
    }

    private List<List<String>> splitProjectBlocks(List<String> lines) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> currentBlock = new ArrayList<>();
        boolean currentHasTitle = false;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (!currentBlock.isEmpty()) {
                    currentBlock.add("");
                }
                continue;
            }
            boolean title = looksLikeTitle(stripped);
            if (title && currentHasTitle && !currentBlock.isEmpty()) {
                blocks.add(trimTrailingBlank(currentBlock));
                currentBlock = new ArrayList<>();
                currentHasTitle = false;
            }
            currentBlock.add(stripped);
            if (title) {
                currentHasTitle = true;
            }
        }
        if (!currentBlock.isEmpty()) {
            blocks.add(trimTrailingBlank(currentBlock));
        }
        if (blocks.size() == 1 && blocks.get(0).size() > 12) {
            // 没有任何标题：退化为按空行分段（合并过短段落），避免整段丢失
            return splitByParagraph(lines);
        }
        return blocks;
    }

    private List<List<String>> splitByParagraph(List<String> lines) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    blocks.add(trimTrailingBlank(current));
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(line.strip());
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        // 合并过短块（<20 字）到下一块，避免出现无意义的碎片
        List<List<String>> merged = new ArrayList<>();
        for (List<String> block : blocks) {
            if (!merged.isEmpty() && String.join("", merged.get(merged.size() - 1)).length() < 20) {
                merged.get(merged.size() - 1).addAll(block);
            } else {
                merged.add(new ArrayList<>(block));
            }
        }
        return merged;
    }

    private List<String> trimTrailingBlank(List<String> block) {
        List<String> copy = new ArrayList<>(block);
        while (!copy.isEmpty() && copy.get(copy.size() - 1).isBlank()) {
            copy.remove(copy.size() - 1);
        }
        return copy;
    }

    /**
     * 标题行判定：含时间区间；或「短行 + 项目标记词」；或「序号 + 项目名」。
     *
     * <p>排除内容行：以「字段名：」开头的行（项目描述：/技术栈：/个人职责：…）绝不当作标题，
     * 否则一个项目会被错误切成多块。
     */
    boolean looksLikeTitle(String line) {
        String stripped = stripOrdinal(line.strip());
        if (stripped.isEmpty() || stripped.length() > 60) {
            return false;
        }
        if (LABEL_PREFIX.matcher(stripped).find()) {
            return false;
        }
        if (RANGE.matcher(stripped).find()) {
            return true;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        boolean hasMarker = PROJECT_TITLE_MARKERS.stream().anyMatch(lower::contains);
        if (!hasMarker) {
            return false;
        }
        if (stripped.length() > 24 || stripped.endsWith("。") || stripped.endsWith("，")) {
            return false;
        }
        // 带日期短标题，或「序号 + 项目名」（序号行必须足够短，避免把正文条目当标题）
        return DATE_TOKEN.matcher(stripped).find()
                || (isNumberedLine(line.strip()) && stripped.length() <= TITLE_MAX_LENGTH);
    }

    /** 序号式项目标题的最大长度。 */
    private static final int TITLE_MAX_LENGTH = 20;

    /** 是否形如「1、xxx」「二、xxx」「(3) xxx」的编号行。 */
    private boolean isNumberedLine(String line) {
        return line.matches("^[（(]?[0-9一二三四五六七八九十]{1,2}[）)]?\\s*[、.．,，:：]?\\s*\\S{2,}.*");
    }

    /** 去掉行首序号（1、/ 二、/ (3) ），保留项目名。 */
    private String stripOrdinal(String line) {
        return line.replaceAll("^[（(]?[0-9一二三四五六七八九十]{1,2}[）)]?\\s*[、.．]\\s*", "").strip();
    }

    /** 项目名：去掉序号、时间区间与「项目名称：」等前缀。 */
    private String projectName(String firstLine) {
        String name = stripOrdinal(firstLine.strip());
        Matcher range = RANGE.matcher(name);
        if (range.find()) {
            name = name.replace(range.group(), " ");
        }
        name = name.replaceAll("^(项目名称|项目名|项目|课题|题目)[:：\\s]*", "");
        name = name.replaceAll("[、.．,，;；:：|]+$", "").replaceAll("\\s{2,}", " ").strip();
        if (name.isBlank()) {
            return "未命名项目";
        }
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    private String educationLabel(List<String> lines) {
        return labelFromLines(lines, SCHOOL, "教育经历");
    }

    private String companyLabel(List<String> lines) {
        return labelFromLines(lines, COMPANY, "实习/工作经历");
    }

    private String labelFromLines(List<String> lines, Pattern pattern, String fallback) {
        if (lines == null) {
            return fallback;
        }
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).strip();
            }
        }
        return fallback;
    }

    private String sanitizeBlock(List<String> lines) {
        List<String> cleaned = new ArrayList<>(lines.size());
        for (String line : lines) {
            cleaned.add(masker.mask(line).strip());
        }
        String joined = String.join("\n", cleaned).replaceAll("\n{3,}", "\n\n");
        return joined.strip();
    }

    // ---------------------------------------------------------------- 技术栈与个人信息

    /**
     * 技术栈识别：大小写不敏感、最长优先。
     *
     * <p>只做「子串抑制」：如果某个较短术语只出现在已命中的更长术语内部
     * （例如 "Spring" 只出现在 "Spring Boot" 内），则不再重复计入；
     * 但如果它同时也有独立出现（如 "JavaScript" 与单独的 "Java"），两者都保留。
     */
    public List<String> detectTechStack(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> sorted = new ArrayList<>(TECH_TERMS);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        Set<String> found = new LinkedHashSet<>();
        for (String term : sorted) {
            String needle = term.toLowerCase(Locale.ROOT);
            if (!lower.contains(needle)) {
                continue;
            }
            boolean shadowed = found.stream().anyMatch(existing ->
                    existing.length() > term.length()
                            && existing.toLowerCase(Locale.ROOT).contains(needle)
                            && !containsStandalone(lower, needle, existing.toLowerCase(Locale.ROOT)));
            if (!shadowed) {
                found.add(term);
            }
        }
        return new ArrayList<>(found);
    }

    /** 短术语是否在被更长术语覆盖之外还有独立出现。 */
    private boolean containsStandalone(String haystack, String needle, String longerTerm) {
        if (isAsciiWord(needle)) {
            return Pattern.compile("(?<![A-Za-z0-9+#])" + Pattern.quote(needle) + "(?![A-Za-z0-9+#])")
                    .matcher(haystack).find();
        }
        String withoutLonger = haystack.replace(longerTerm, "");
        return withoutLonger.contains(needle);
    }

    private boolean isAsciiWord(String term) {
        return term.chars().allMatch(c -> c < 128);
    }

    /** 姓名识别：取前 6 行中第一个「2~4 个汉字、不含数字与关键词」的短行。 */
    String detectName(List<String> lines) {
        int checked = 0;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            if (++checked > 6) {
                break;
            }
            if (stripped.matches("^[\\u4e00-\\u9fa5]{2,4}$")
                    && !stripped.matches(".*(简历|求职|应聘|个人|信息).*")) {
                return stripped;
            }
            if (stripped.matches("^[A-Za-z]{2,20}(\\s[A-Za-z]{2,20}){0,2}$") && stripped.length() <= 24) {
                return stripped;
            }
        }
        return null;
    }

    private double confidenceOf(List<ResumeFact> facts, Set<String> techStack, List<String> warnings) {
        if (facts.isEmpty()) {
            return 0d;
        }
        long projects = facts.stream().filter(f -> f.type() == FactType.PROJECT).count();
        double base = 0.25;
        if (projects > 0) {
            base += 0.3;
        }
        if (!techStack.isEmpty()) {
            base += 0.2;
        }
        if (facts.stream().anyMatch(f -> f.type() == FactType.EDUCATION)) {
            base += 0.1;
        }
        if (facts.stream().anyMatch(f -> f.type() == FactType.INTERNSHIP)) {
            base += 0.1;
        }
        base -= Math.min(0.2, warnings.size() * 0.03);
        return Math.max(0d, Math.min(1d, base));
    }

    private String factId(String type, int order) {
        return "resume-" + type.toLowerCase(Locale.ROOT) + "-" + String.format("%03d", Math.max(1, order));
    }
}
