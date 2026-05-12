package com.hmdp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class BlogVectorSyncJob {
    @Autowired
    private BlogMapper blogMapper;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    @Qualifier("reviewEmbeddingStore")
    private EmbeddingStore<TextSegment> reviewEmbeddingStore;

    public void syncBlogData(){
        log.info("开始执行【探店笔记/评论】向量化同步任务...");
        int current = 1;
        int size = 500;
        long totalProcessed = 0;
        while (true) {
            // 1. 分页查出探店笔记
            Page<Blog> page = new Page<>(current, size);
            Page<Blog> blogPage = blogMapper.selectPage(page, null);
            List<Blog> blogs = blogPage.getRecords();

            if (blogs == null || blogs.isEmpty()) {
                break;
            }

            log.info("正在处理第 {} 批探店笔记，共 {} 条...", current, blogs.size());

            List<Document> rawDocuments = new ArrayList<>();
            for (Blog blog : blogs) {
                // 如果没有关联商铺，直接跳过（因为后面要拿 shopId 去反查商铺）
                if (blog.getShopId() == null) {
                    continue;
                }

                //拼接评论文本
                String semanticContent = """
                        【探店笔记/用户评价】
                        标题：%s
                        真实评价内容：%s
                        """.formatted(
                        blog.getTitle() != null ? blog.getTitle() : "无标题",
                        blog.getContent() != null ? blog.getContent() : ""
                );

                Metadata metadata = new Metadata()
                        .put("blogId", blog.getId())
                        .put("shopId", blog.getShopId());

                rawDocuments.add(new Document(semanticContent, metadata));

                var splitter = DocumentSplitters.recursive(300, 30);
                List<TextSegment> allSegments = new ArrayList<>();
                for (Document doc : rawDocuments) {
                    allSegments.addAll(splitter.split(doc));
                }

                int batchSize = 100;
                for (int i = 0; i < allSegments.size(); i += batchSize) {
                    int end = Math.min(allSegments.size(), i + batchSize);
                    List<TextSegment> batchSegments = allSegments.subList(i, end);

                    var embeddings = embeddingModel.embedAll(batchSegments).content();
                    reviewEmbeddingStore.addAll(embeddings, batchSegments);
                }
                totalProcessed += blogs.size();
                current++;
            }
            totalProcessed += blogs.size();
            current++;
        }log.info("探店笔记全量同步完成,共处理了 {} 条评价", totalProcessed);
    }
}
