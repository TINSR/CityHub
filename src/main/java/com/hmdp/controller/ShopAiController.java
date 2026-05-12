package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.BlogVectorSyncJob;
import com.hmdp.service.SemanticSearchService;
import com.hmdp.service.ShopVectorSyncJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/shop/ai")
@RequiredArgsConstructor
public class ShopAiController {
    private final SemanticSearchService semanticSearchService;
    private final ShopVectorSyncJob shopVectorSyncJob;
    private  final BlogVectorSyncJob  blogVectorSyncJob;

    @PostMapping("/sync")
    public Result syncDataManually() {
        log.info("接收到手动触发数据同步请求");
        try {
            shopVectorSyncJob.syncShopData();
            return Result.ok("数据同步指令已下发，请确认同步进度");
        } catch (Exception e) {
            log.error("手动触发同步失败", e);
            return Result.fail("数据同步失败：" + e.getMessage());
        }
    }
    @GetMapping("/search")
    public Result searchShopsByAi(@RequestParam("query") String query) {
        log.info("接收到 AI 搜店请求，搜索词: {}", query);
        try {
            // 调用 Service 层，拿到大模型匹配出的店铺列表
            List<Shop> shopList = semanticSearchService.searchShops(query);

            // 使用 CityHub 原生的 Result 进行包装并返回给前端
            return Result.ok(shopList, (long) shopList.size());
        } catch (Exception e) {
            log.error("AI 搜店接口异常", e);
            return Result.fail("AI 搜店暂时不可用：" + e.getMessage());
        }
    }
    
    @PostMapping("/sync-blog")
    public Result syncBlogDataManually() {
        log.info("接收到同步探店笔记的请求");
        try {
            blogVectorSyncJob.syncBlogData();
            return Result.ok("探店笔记同步指令已下发");
        } catch (Exception e) {
            log.error("同步笔记失败", e);
            return Result.fail("同步笔记失败：" + e.getMessage());
        }
    }

}
