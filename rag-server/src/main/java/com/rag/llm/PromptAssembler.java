package com.rag.llm;

import java.util.ArrayList;
import java.util.List;

import com.rag.config.RagProperties;
import com.rag.domain.enums.SessionRole;
import com.rag.retrieval.model.Context;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

/**
 * Prompt 组装（docs/03-技术路线.md §3.2 步骤 6，QA-3/QA-6）：
 *
 * <p>双区结构：SystemMessage 携带答题纪律；对话历史以独立 USER/ASSISTANT 消息
 * 注入（仅连贯性，非证据）；最后一条 UserMessage 只含文档证据区与本次问题。
 * 历史内容绝不进入证据区标记之下。</p>
 */
@Component
public class PromptAssembler {

    /**
     * 系统指令（QA-3/QA-6 三句核心约束必须逐字保留，测试有断言）。
     *
     * <p>R6-B.1 一致性重写（取代 R6-B 的"已由前置判定确认"措辞）：该前提在
     * Judge fail-open（TIMEOUT/OVERLOADED/MODEL_ERROR/INVALID_RESPONSE →
     * 降级放行进生成）时不成立，生成模型必须始终保留证据充分性自检能力。
     * 新规则自洽于任何进入生成的前提：判定层放行或判定层故障降级都适用。</p>
     */
    static final String SYSTEM_INSTRUCTION = """
            你是企业知识库问答助手，请严格遵守以下规则：
            1. 仅依据文档证据回答用户问题；
            2. 若文档证据不足以回答，必须明确说明无法依据当前资料回答，不要凭记忆或猜测作答；
            3. 不得编造引用来源，回答依据只能来自文档证据区中的内容。
            如果回答所需的事实能够从文档证据区直接获得，即使这些事实分散在多个证据片段中，也应组合这些明确陈述的事实回答，不要仅因为证据简短、分散，或没有使用与问题完全相同的措辞而拒绝回答。
            只允许组合证据中明确陈述的事实；不得通过额外算术运算、跨行聚合、比例计算、缺失值补全、外部知识、未陈述的因果关系或其他推断生成证据中没有的新事实。
            用"因此""所以"等连接词组织证据中已明确陈述的事实（包括事实之间的对照关系），只要没有引入证据之外的新数值或新事实，就不属于上述禁止的推断。
            如果回答所需的关键事实确实缺失，则明确说明当前证据不足。
            对话历史仅用于理解上下文、保持对话连贯，不构成回答依据。""";

    private final int maxHistoryMessages;

    @org.springframework.beans.factory.annotation.Autowired
    public PromptAssembler(RagProperties ragProperties) {
        this.maxHistoryMessages = ragProperties.getModels().getChat().getMaxHistoryMessages();
    }

    /** 测试用构造。 */
    PromptAssembler(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }

    /**
     * @param question 本次用户问题
     * @param history  会话历史（时间正序；仅连贯性，非证据）
     * @param context  检索上下文（ContextAssembler 产物）
     * @return [SystemMessage, 历史消息(≤maxHistoryMessages), UserMessage(证据区+问题)]
     */
    public List<ChatMessage> build(String question, List<HistoryTurn> history, Context context) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_INSTRUCTION));

        List<HistoryTurn> turns = history == null ? List.of() : history;
        int from = Math.max(0, turns.size() - maxHistoryMessages);
        for (HistoryTurn turn : turns.subList(from, turns.size())) {
            if (turn.role() == SessionRole.USER) {
                messages.add(UserMessage.from(turn.content()));
            } else {
                messages.add(AiMessage.from(turn.content()));
            }
        }

        messages.add(UserMessage.from(evidenceSection(context) + "\n\n【问题】\n" + question));
        return messages;
    }

    private static String evidenceSection(Context context) {
        String text = context == null ? Context.empty().text() : context.text();
        return "【文档证据区】（回答仅可依据以下内容）\n" + text;
    }

    /** 会话历史轻量结构（与 JPA 实体解耦）。 */
    public record HistoryTurn(SessionRole role, String content) {
    }
}
