package com.dusk4d.interview.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("隐私脱敏 PrivacyMasker")
class PrivacyMaskerTest {

    private final PrivacyMasker masker = new PrivacyMasker();

    @ParameterizedTest
    @ValueSource(strings = {
            "13812345678",
            "138-1234-5678",
            "138 1234 5678",
            "+8613812345678",
            "联系我 15900001111 谢谢"
    })
    @DisplayName("手机号在多种书写形式下都应被脱敏")
    void masksPhoneNumbers(String input) {
        String masked = masker.mask(input);
        assertThat(masked).contains(PrivacyMasker.PHONE_PLACEHOLDER);
        assertThat(masked).doesNotContain("13812345678").doesNotContain("15900001111");
        assertThat(masker.containsSensitive(masked)).isFalse();
    }

    @Test
    @DisplayName("座机号应被脱敏")
    void masksLandline() {
        String masked = masker.mask("电话：010-88886666");
        assertThat(masked).contains(PrivacyMasker.PHONE_PLACEHOLDER);
    }

    @Test
    @DisplayName("邮箱应被脱敏")
    void masksEmail() {
        String masked = masker.mask("邮箱 zhang.wei+dev@example.com 请勿外传");
        assertThat(masked).contains(PrivacyMasker.EMAIL_PLACEHOLDER);
        assertThat(masked).doesNotContain("example.com");
    }

    @Test
    @DisplayName("身份证号应被脱敏")
    void masksIdCard() {
        String masked = masker.mask("身份证 110101199003072316 已核验");
        assertThat(masked).contains(PrivacyMasker.ID_CARD_PLACEHOLDER);
        assertThat(masked).doesNotContain("110101199003072316");
    }

    @Test
    @DisplayName("链接应被脱敏（可能是个人主页或作品集）")
    void masksUrl() {
        String masked = masker.mask("作品集 https://github.com/example/repo 可查看");
        assertThat(masked).contains(PrivacyMasker.URL_PLACEHOLDER);
    }

    @Test
    @DisplayName("地址行应被脱敏")
    void masksAddress() {
        String masked = masker.mask("现居地址：北京市海淀区中关村大街 27 号");
        assertThat(masked).contains(PrivacyMasker.ADDRESS_PLACEHOLDER);
    }

    @Test
    @DisplayName("项目指标与时间不应被误伤")
    void keepsTechnicalNumbers() {
        String input = "把下单接口 P99 从 800ms 降到 210ms，QPS 提升到 3000，覆盖 12 类问题";
        assertThat(masker.mask(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("null 与空串安全")
    void handlesNullAndEmpty() {
        assertThat(masker.mask(null)).isEmpty();
        assertThat(masker.mask("")).isEmpty();
        assertThat(masker.containsSensitive(null)).isFalse();
    }

    @Test
    @DisplayName("scan 应报告命中类型与数量")
    void scanReportsHits() {
        Map<String, Integer> hits = masker.scan("手机 13812345678，备用 13900002222，邮箱 a@b.com");
        assertThat(hits).containsEntry("phone", 2).containsEntry("email", 1);
    }

    @Test
    @DisplayName("日志脱敏：先脱敏后截断，敏感内容绝不进入日志")
    void forLogTruncates() {
        String log = masker.forLog("第一行 13812345678 尾随内容", 20);
        assertThat(log).doesNotContain("\n").contains(PrivacyMasker.PHONE_PLACEHOLDER);
        String truncated = masker.forLog("很长的一段内容 13812345678 更多内容", 6);
        assertThat(truncated).endsWith("…").doesNotContain("1381");
    }
}
