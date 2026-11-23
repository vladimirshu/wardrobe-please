package com.ecomassistant.search;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import tools.jackson.databind.json.JsonMapper;

/** Same one-repeat schema policy, charged to the request-wide retry allowance. No payload logging. */
public final class BudgetSchemaAdvisor implements CallAdvisor {
    private final Schema schema;
    private final int order;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public BudgetSchemaAdvisor(String jsonSchema) { this(jsonSchema, BaseAdvisor.LOWEST_PRECEDENCE - 2000); }
    public BudgetSchemaAdvisor(String jsonSchema, int order) {
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(JSON.readTree(jsonSchema));
        this.order = order;
    }
    @Override public String getName() { return "Budgeted schema validation"; }
    @Override public int getOrder() { return order; }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.copy(this).nextCall(request);
        if (valid(response)) return response;
        var budget = SearchBudget.current();
        if (budget != null && !budget.tryAdditionalAttempt()) return response;
        var corrected = request.prompt().augmentUserMessage(message -> message.mutate()
                .text(message.getText() + "\nOutput JSON validation failed. Return JSON matching the required schema.")
                .build());
        return chain.copy(this).nextCall(request.mutate().prompt(corrected).build());
    }
    private boolean valid(ChatClientResponse response) {
        var chat = response.chatResponse();
        if (chat != null && chat.hasToolCalls()) return true;
        if (chat == null || chat.getResult() == null || chat.getResult().getOutput() == null) return false;
        String text = chat.getResult().getOutput().getText();
        if (text == null || text.isBlank()) return false;
        try { return schema.validate(JSON.readTree(text)).isEmpty(); }
        catch (RuntimeException ignored) { return false; }
    }
}
