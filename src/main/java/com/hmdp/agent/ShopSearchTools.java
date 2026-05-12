package com.hmdp.agent;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopPortrait;
import com.hmdp.mapper.ShopPortraitMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.SemanticSearchService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ShopSearchTools {

    @Autowired
    private SemanticSearchService semanticSearchService;
    @Autowired
    private ShopPortraitMapper shopPortraitMapper;
    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private IShopService  shopService;

    @Tool("当你需要根据用户的【模糊需求、氛围、环境】寻找相关店铺时，调用此工具。它会返回一批候选的 shopId。")
    public List<Shop> vectorSearchShop(String keyword) {
        log.info("AI 正在调用工具搜店，关键词: {}", keyword);
        // 调用 Qdrant HTTP 搜索，返回相似度最高的 15 个 ID
        try {
            //把自然语言的 keyword 转换为 1024 维的 float[] 向量
            float[] vector = embeddingModel.embed(keyword).content().vector();

            // 把转换好的 float[] 交给底层 HTTP 方法
            List<Long> shopIds = semanticSearchService.searchFromQdrantHttp("heima_shop_collection", vector, 15)
                    .keySet()
                    .stream()
                    .map(Long::valueOf)
                    .toList();
            if (shopIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<Shop> shops = shopService.listByIds(shopIds);

            return shopIds.stream()
                    .map(id -> shops.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("AI 调取 Qdrant 工具失败", e);
            return Collections.emptyList();
        }
    }
    @Tool("根据上一步获取到的 shopId 列表，查询这些店铺的【详细 AI 画像、优点、避雷缺点摘要】")
    public List<ShopPortrait> getShopPortraits(List<Long> shopIds) {
        log.info(" AI 正在调取店铺画像进行排雷，涉及店铺数: {}", shopIds.size());
        if (shopIds == null || shopIds.isEmpty()) {
            return Collections.emptyList();
        }
        return shopPortraitMapper.selectBatchIds(shopIds);
    }

}
