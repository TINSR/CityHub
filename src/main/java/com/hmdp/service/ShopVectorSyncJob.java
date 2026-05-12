package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.ShopTypeMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.document.splitter.DocumentSplitters;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;


@Slf4j
@Service

public class ShopVectorSyncJob {
    @Autowired
    private  ShopMapper shopMapper;
    @Autowired
    private  EmbeddingModel embeddingModel;
    @Autowired
    @Qualifier("shopEmbeddingStore")
    private  EmbeddingStore<TextSegment> embeddingStore;
    @Autowired
    private  ShopTypeMapper  shopTypeMapper;

    /**
     * 同步商铺数据到 Qdrant 向量数据库
     * 配置了每天凌晨 3 点自动执行，也可以通过 Controller 手动触发
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Qualifier("shopEmbeddingStore")
    public void syncShopData() {
        log.info("开始执行商铺向量化同步任务...");
        LocalDateTime syncStartTime = LocalDateTime.now().minusHours(25);
        LambdaQueryWrapper<Shop> queryWrapper = new LambdaQueryWrapper<>();
        //queryWrapper.ge(Shop::getUpdateTime, syncStartTime);

        List<ShopType> shopTypes = shopTypeMapper.selectList(null);
        Map<Long, String> typeMap = shopTypes.stream()
                .collect(Collectors.toMap(ShopType::getId, ShopType::getName));

        int current = 1;
        int size = 500;
        long totalProcessed = 0;

        while (true) {
            Page<Shop> page = new Page<>(current, size);
            Page<Shop> shopPage = shopMapper.selectPage(page, queryWrapper);
            List<Shop> shops = shopPage.getRecords();

            if (shops == null || shops.isEmpty()) {
                break;
            }
            log.info("本批次发现 {} 条发生更新的商铺，准备进行向量重置...", shops.size());
            List<Long> updatedShopIds = shops.stream().map(Shop::getId).toList();

            Filter oldVectorFilter = metadataKey("shopId").isIn(updatedShopIds);

            try {
                // 物理删除旧向量
                embeddingStore.removeAll(oldVectorFilter);
                log.info("已清空这{}家商铺的历史旧向量数据", updatedShopIds.size());
            } catch (Exception e) {
                log.warn("清理旧向量时发生异常 (若是首次全量同步且集合为空则忽略): {}", e.getMessage());
            }
            List<Document> rawDocuments = new ArrayList<>();

            for (Shop shop : shops) {
                String realTypeName = typeMap.getOrDefault(shop.getTypeId(), "未知分类");

                // 拼接最新文本
                String semanticContent = """
                        商铺名称：%s
                        主打分类：%s
                        所在商圈：%s
                        商铺具体地址：%s
                        """.formatted(
                        shop.getName(),
                        realTypeName,
                        shop.getArea(),
                        shop.getAddress()
                );
                Metadata metadata = new Metadata()
                        .put("shopId", shop.getId())
                        .put("categoryId", shop.getTypeId());

                rawDocuments.add(new Document(semanticContent, metadata));

            }
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
                embeddingStore.addAll(embeddings, batchSegments);
            }
            totalProcessed += shops.size();
            current++;
        }log.info("商铺增量同步完成，本次共处理了 {} 家更新的商铺", totalProcessed);
    }
}
