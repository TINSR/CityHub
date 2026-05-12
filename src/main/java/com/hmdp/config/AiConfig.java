package com.hmdp.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Collections;

@Configuration
public class AiConfig {

    @Value("${spring.langchain4j.zhipu-ai.chat-model.api-key}")
    private String zhipuApiKey;

    @Bean
    public EmbeddingModel embeddingModel() {
        return new InfinityEmbeddingModel(
                "http://localhost:7997",
                "BAAI/bge-m3"
        );
    }

    // 2. 专门给【商铺基本信息】用的向量库
    @Bean(name = "shopEmbeddingStore")
    @Primary
    public EmbeddingStore<TextSegment> shopEmbeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host("localhost")
                .port(6334)
                .collectionName("heima_shop_collection") // 指向商铺集合
                .build();
    }

    // 3. 专门给【用户评论/探店笔记】用的向量库
    @Bean(name = "reviewEmbeddingStore")
    public EmbeddingStore<TextSegment> reviewEmbeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host("localhost")
                .port(6334)
                .collectionName("heima_review_collection") // 指向刚刚新建的评价集合
                .build();
    }



    @Bean
    public ChatLanguageModel zhipuChatModel() {
        return ZhipuAiChatModel.builder()
                .apiKey(zhipuApiKey)
                .model("glm-4")
                .temperature(0.3)
                .maxToken(4096)
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(60))
                .build();
    }
    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        // 记住最近的 10 条消息
        return memoryId -> MessageWindowChatMemory.withMaxMessages(10);
    }

    @Bean
    public RetrievalAugmentor emptyRetrievalAugmentor() {
        return DefaultRetrievalAugmentor.builder()
                // 塞给它一个“空”的检索器，这样它就不会去瞎截胡了
                .contentRetriever(query -> Collections.emptyList())
                .build();
    }

}
