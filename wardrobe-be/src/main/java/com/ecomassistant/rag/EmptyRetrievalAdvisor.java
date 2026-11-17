package com.ecomassistant.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Skips the product-filtering LLM call when retrieval returned no candidates, returning a
 * synthetic {@code {"skus":[]}} response instead. This is placed inside (after) the
 * {@link RetrievalAugmentationAdvisor}, whose {@code before()} populates
 * {@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT} in the request context.
 * For the storefront's synchronous calls it also logs the refinement stage around
 * the schema validator, so the duration includes any retry.
 */
@Component
public class EmptyRetrievalAdvisor implements CallAdvisor, StreamAdvisor {

    private static final int ORDER = -200;
    private static final Logger log = LoggerFactory.getLogger(EmptyRetrievalAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        int candidates = candidateCount(chatClientRequest);
        if (candidates == 0) {
            log.info("Skipping LLM product refinement because retrieval returned no candidates");
            return emptyResponse(chatClientRequest);
        }

        log.info("LLM product refinement request started: candidates={}", candidates);
        long start = System.nanoTime();
        ChatClientResponse response;
        try {
            response = callAdvisorChain.nextCall(chatClientRequest);
        } catch (RuntimeException e) {
            log.warn("LLM product refinement failed after {} ms: {}",
                    NANOSECONDS.toMillis(System.nanoTime() - start), e.getClass().getSimpleName());
            throw e;
        }
        ChatResponse chatResponse = response.chatResponse();
        var metadata = chatResponse == null ? null : chatResponse.getMetadata();
        var result = chatResponse == null ? null : chatResponse.getResult();
        log.info("LLM product refinement response received in {} ms: model={}, finishReason={}",
                NANOSECONDS.toMillis(System.nanoTime() - start),
                metadata == null ? null : metadata.getModel(),
                result == null ? null : result.getMetadata().getFinishReason());
        if (metadata != null && metadata.getUsage() != null) {
            var usage = metadata.getUsage();
            log.debug("Product refinement token usage: input={}, output={}, total={}",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        if (candidateCount(chatClientRequest) == 0) {
            return Flux.just(emptyResponse(chatClientRequest));
        }
        return streamAdvisorChain.nextStream(chatClientRequest);
    }

    private static int candidateCount(ChatClientRequest chatClientRequest) {
        Object value = chatClientRequest.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static ChatClientResponse emptyResponse(ChatClientRequest chatClientRequest) {
        return ChatClientResponse.builder()
            .chatResponse(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("{\"skus\":[]}"))))
                .build())
            .context(Map.copyOf(chatClientRequest.context()))
            .build();
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
