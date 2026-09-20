package com.dusk4d.interview.agent;

import com.dusk4d.interview.parse.CleanIssue;
import com.dusk4d.interview.domain.SessionStatus;
import com.dusk4d.interview.error.InvalidSessionStateException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态机（对应方案书 6.3）。
 *
 * <pre>
 * CREATED -> RESUME_READY -> INTERVIEW_STARTED -> WAITING_ANSWER
 * WAITING_ANSWER -> EVALUATING -> FOLLOW_UP | NEXT_QUESTION
 * NEXT_QUESTION -> WAITING_ANSWER
 * EVALUATING -> FINISHED -> REPORT_READY
 * 任意状态 -> FAILED | CANCELLED
 * </pre>
 *
 * <p>关键设计：<b>状态只能由后端根据结构化结果推进</b>，模型无权直接修改。
 * 所有非法转移都会抛出带明确原因的业务异常（HTTP 409），而不是静默修正，
 * 这样既能防住并发/重复请求，也能在测试中精确断言。
 */
public final class InterviewStateMachine {

    private static final Map<SessionStatus, Set<SessionStatus>> ALLOWED = new java.util.EnumMap<>(Map.ofEntries(
            Map.entry(SessionStatus.CREATED, EnumSet.of(SessionStatus.RESUME_READY, SessionStatus.CANCELLED,
                    SessionStatus.FAILED)),
            Map.entry(SessionStatus.RESUME_READY, EnumSet.of(SessionStatus.INTERVIEW_STARTED,
                    SessionStatus.CANCELLED, SessionStatus.FAILED)),
            Map.entry(SessionStatus.INTERVIEW_STARTED, EnumSet.of(SessionStatus.WAITING_ANSWER,
                    SessionStatus.FINISHED, SessionStatus.CANCELLED, SessionStatus.FAILED)),
            Map.entry(SessionStatus.WAITING_ANSWER, EnumSet.of(SessionStatus.EVALUATING, SessionStatus.FINISHED,
                    SessionStatus.CANCELLED, SessionStatus.FAILED)),
            Map.entry(SessionStatus.EVALUATING, EnumSet.of(SessionStatus.FOLLOW_UP, SessionStatus.NEXT_QUESTION,
                    SessionStatus.FINISHED, SessionStatus.FAILED)),
            Map.entry(SessionStatus.FOLLOW_UP, EnumSet.of(SessionStatus.WAITING_ANSWER, SessionStatus.EVALUATING,
                    SessionStatus.FINISHED, SessionStatus.CANCELLED, SessionStatus.FAILED)),
            Map.entry(SessionStatus.NEXT_QUESTION, EnumSet.of(SessionStatus.WAITING_ANSWER, SessionStatus.FINISHED,
                    SessionStatus.CANCELLED, SessionStatus.FAILED)),
            Map.entry(SessionStatus.FINISHED, EnumSet.of(SessionStatus.REPORT_READY, SessionStatus.FAILED)),
            Map.entry(SessionStatus.REPORT_READY, EnumSet.noneOf(SessionStatus.class)),
            Map.entry(SessionStatus.FAILED, EnumSet.noneOf(SessionStatus.class)),
            Map.entry(SessionStatus.CANCELLED, EnumSet.noneOf(SessionStatus.class))));

    private InterviewStateMachine() {
    }

    /** 判断转移是否合法。 */
    public static boolean canTransition(SessionStatus from, SessionStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 执行转移校验。
     *
     * @throws InvalidSessionStateException 当转移非法时
     */
    public static void requireTransition(SessionStatus from, SessionStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidSessionStateException(
                    "非法状态转移：" + from + " -> " + to + "。"
                            + (from.isTerminal() ? "当前会话已结束，请新建一场面试。" : "请按正常流程继续。"));
        }
    }

    /** 是否允许提交回答。 */
    public static boolean canAcceptAnswer(SessionStatus status) {
        return status == SessionStatus.WAITING_ANSWER || status == SessionStatus.FOLLOW_UP;
    }

    /** 校验可以提交回答，否则抛出明确原因。 */
    public static void requireAcceptAnswer(SessionStatus status) {
        if (canAcceptAnswer(status)) {
            return;
        }
        String reason = switch (status) {
            case CREATED, RESUME_READY -> "还没有开始面试，请先获取第一道题。";
            case INTERVIEW_STARTED -> "还没有下发问题，请先获取题目。";
            case EVALUATING -> "上一份回答还在评估中，请稍候再提交。";
            case NEXT_QUESTION -> "请先获取下一道题，再提交回答。";
            case FINISHED, REPORT_READY -> "本场面试已结束，无法继续提交回答。";
            case CANCELLED -> "本场面试已取消，无法继续提交回答。";
            case FAILED -> "本场会话已失败，请新建一场面试。";
            default -> "当前状态不允许提交回答：" + status;
        };
        throw new InvalidSessionStateException(reason);
    }

    /** 是否允许生成下一题。 */
    public static void requireNextQuestion(SessionStatus status) {
        Set<SessionStatus> allowed = EnumSet.of(SessionStatus.CREATED, SessionStatus.INTERVIEW_STARTED,
                SessionStatus.RESUME_READY, SessionStatus.NEXT_QUESTION, SessionStatus.FOLLOW_UP,
                SessionStatus.WAITING_ANSWER, SessionStatus.EVALUATING);
        if (!allowed.contains(status)) {
            throw new InvalidSessionStateException(
                    status.isTerminal() ? "本场面试已结束，无法继续出题。" : "当前状态不允许出题：" + status);
        }
    }

    /** 是否允许结束面试。 */
    public static void requireFinish(SessionStatus status) {
        if (status.isTerminal() && status != SessionStatus.FINISHED) {
            throw new InvalidSessionStateException("本场面试已结束，无法重复结束。");
        }
    }

    /**
     * 是否允许「重新回答当前题」。
     *
     * <p>只有在「刚答完这道题、还没下发下一题」时才允许：状态是 {@code NEXT_QUESTION} 或
     * {@code FOLLOW_UP}。一旦进入下一题，当前题就换人了，此时重答会被拒绝并提示清楚。
     */
    public static void requireRetryAnswer(SessionStatus status) {
        if (status == SessionStatus.NEXT_QUESTION || status == SessionStatus.FOLLOW_UP) {
            return;
        }
        String reason = switch (status) {
            case WAITING_ANSWER -> "这道题还没有提交过回答，直接提交即可，不需要重答。";
            case FINISHED, REPORT_READY -> "本场面试已结束，无法重答。请新建一场面试。";
            case CANCELLED -> "本场面试已取消，无法重答。";
            case FAILED -> "本场会话已失败，请新建一场面试。";
            default -> "当前状态不支持重答：" + describe(status);
        };
        throw new InvalidSessionStateException(reason);
    }

    /** 是否允许「换一道题」（仅在已下发、尚未回答时）。 */
    public static void requireReplaceQuestion(SessionStatus status) {
        if (status == SessionStatus.WAITING_ANSWER) {
            return;
        }
        String reason = switch (status) {
            case NEXT_QUESTION, FOLLOW_UP -> "当前题已经回答过了，请先「重新回答」或直接进入下一题。";
            case FINISHED, REPORT_READY -> "本场面试已结束，无法换题。";
            case CANCELLED -> "本场面试已取消，无法换题。";
            case CREATED, RESUME_READY -> "还没有开始面试，请先获取第一道题。";
            default -> "当前状态不支持换题：" + describe(status);
        };
        throw new InvalidSessionStateException(reason);
    }

    /** 供前端展示的状态说明。 */
    public static String describe(SessionStatus status) {
        return switch (status) {
            case CREATED -> "面试已创建，尚未绑定简历";
            case RESUME_READY -> "简历已就绪，可以开始面试";
            case INTERVIEW_STARTED -> "面试已开始，等待出题";
            case WAITING_ANSWER -> "已下发问题，等待你的回答";
            case EVALUATING -> "正在评估回答";
            case FOLLOW_UP -> "已生成追问，等待你的回答";
            case NEXT_QUESTION -> "本题已完成，可以进入下一题";
            case FINISHED -> "面试已结束，可以生成报告";
            case REPORT_READY -> "报告已生成";
            case FAILED -> "会话失败，请新建面试";
            case CANCELLED -> "会话已取消";
        };
    }

    /** 清洗问题到用户可读提示（供服务层复用）。 */
    public static String describeCleanIssue(CleanIssue issue) {
        return switch (issue) {
            case EMPTY_TEXT -> "简历文本为空";
            case MOSTLY_NOISE -> "简历内容几乎都是符号，无法解析";
            case IMAGE_ONLY_PDF -> "PDF 没有文字层（扫描件/图片简历暂不支持）";
            case ENCODING_GARBLED -> "检测到乱码，解析结果可能不准确";
            case TOO_SHORT -> "简历文本过短，可能解析不完整";
        };
    }
}
