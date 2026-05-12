package com.hmdp.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.SemanticSearchService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticSearchServiceImpl implements SemanticSearchService {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    @Qualifier("shopEmbeddingStore")
    private EmbeddingStore<TextSegment> shopEmbeddingStore;

    @Autowired
    @Qualifier("reviewEmbeddingStore")
    private EmbeddingStore<TextSegment> reviewEmbeddingStore;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // CityHub 的缓存 Key
    private static final String CACHE_SHOP_KEY = "cache:shop:";
    private static final Long CACHE_SHOP_TTL = 30L;
    /**
     * 基于 RAG 的语义搜店
     */
    @Override
    public List<Shop> searchShops(String query) {
        try {
            //将用户自然语言转成向量
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            // 路 A：去商铺基础库搜 (匹配店名、商圈、分类等客观属性)
            float[] vector = queryEmbedding.vector();

            // 路 A：搜商铺
            Map<Long, Double> shopScores = searchFromQdrantHttp("heima_shop_collection", vector, 10);
            // 路 B：搜评论
            Map<Long, Double> reviewScores = searchFromQdrantHttp("heima_review_collection", vector, 15);

            //合并与重排
            Map<Long, Double> finalScoreMap = new HashMap<>(shopScores);
            reviewScores.forEach((shopId, score) -> {
                // 核心逻辑：同一家店，取两路召回的最高分
                finalScoreMap.put(shopId, Math.max(finalScoreMap.getOrDefault(shopId, 0.0), score));
            });

            // 按综合得分从高到低排序，截取前 10 名

            List<Long> orderedShopIds = finalScoreMap.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .limit(10)
                    .toList();

            if (orderedShopIds.isEmpty()) {
                return Collections.emptyList();
            }

            //查redis缓存
            List<String> cacheKeys = orderedShopIds.stream()
                    .map(id -> CACHE_SHOP_KEY + id)
                    .toList();
            List<String> cachedShopJsons = stringRedisTemplate.opsForValue().multiGet(cacheKeys);

            Map<Long, Shop> shopDataMap = new HashMap<>();
            List<Long> missCacheShopIds = new ArrayList<>();
            //挑出未命中的id
            for (int i = 0; i < orderedShopIds.size(); i++) {
                Long shopId = orderedShopIds.get(i);
                String json = cachedShopJsons != null ? cachedShopJsons.get(i) : null;

                if (json != null) {
                    if (!json.isEmpty()) {
                        shopDataMap.put(shopId, JSONUtil.toBean(json, Shop.class));
                    }
                } else {
                    missCacheShopIds.add(shopId);
                }
            }
            //查mysql并写回redis
            if (!missCacheShopIds.isEmpty()) {
                List<Shop> dbShops = shopMapper.selectBatchIds(missCacheShopIds);
                // 为了方便比对，把从 MySQL 真实查到的商铺按 ID 转成 Map
                Map<Long, Shop> dbShopMap = dbShops.stream()
                        .collect(Collectors.toMap(Shop::getId, shop -> shop));

                Map<String, String> redisWriteMap = new HashMap<>();

                // 遍历当初拿着去找 MySQL 的所有 ID
                for (Long shopId : missCacheShopIds) {
                    Shop shop = dbShopMap.get(shopId);
                    String cacheKey = CACHE_SHOP_KEY + shopId;

                    if (shop != null) {
                        // 真实存在的店，存入 Map 准备返回，并且存入 Redis 准备写入
                        shopDataMap.put(shopId, shop);
                        redisWriteMap.put(cacheKey, JSONUtil.toJsonStr(shop));
                    } else {
                        redisWriteMap.put(cacheKey, "");
                    }
                }

                if (!redisWriteMap.isEmpty()) {
                    stringRedisTemplate.opsForValue().multiSet(redisWriteMap);
                    redisWriteMap.forEach((key, value) -> {
                        if (value.isEmpty()) {
                            // 僵尸 ID 给一个超短的 TTL（比如 2 分钟），防止它以后重新开业了系统却一直拦截
                            stringRedisTemplate.expire(key, 2L, TimeUnit.MINUTES);
                        } else {
                            // 正常商铺数据给正常的 TTL（比如 30 分钟）
                            stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
                        }
                    });
                }
            }
            return orderedShopIds.stream()
                    .map(shopDataMap::get)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.error("AI 语义搜店异常，查询词: {}", query, e);
            throw new RuntimeException("AI搜索服务暂时不可用");
        }
    }

    public Map<Long, Double> searchFromQdrantHttp(String collectionName, float[] vector, int limit) {
        Map<Long, Double> scoreMap = new HashMap<>();
        try {
            // 构建 Qdrant 标准的查询 JSON 体
            Map<String, Object> param = new HashMap<>();
            param.put("vector", vector);
            param.put("limit", limit);
            param.put("with_payload", true);

            String url = "http://localhost:6333/collections/" + collectionName + "/points/search";
            String response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(param))
                    .timeout(5000)
                    .execute().body();

            // 解析返回的 JSON 结果
            JSONObject jsonObj = JSONUtil.parseObj(response);
            JSONArray results = jsonObj.getJSONArray("result");
            if (results != null) {
                for (int i = 0; i < results.size(); i++) {
                    JSONObject point = results.getJSONObject(i);
                    Double score = point.getDouble("score"); // 拿到相似度得分
                    JSONObject payload = point.getJSONObject("payload");
                    if (payload != null && payload.containsKey("shopId")) {
                        // Qdrant 存的 shopId 可能是 String 也可能是数字，保险起见用 getStr 转 Long
                        Long shopId = Long.valueOf(payload.getStr("shopId"));
                        scoreMap.put(shopId, score);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("通过 HTTP 请求 Qdrant 集合 {} 失败", collectionName, e);
        }
        return scoreMap;
    }

}
