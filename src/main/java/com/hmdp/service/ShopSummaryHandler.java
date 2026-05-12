package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.Blog;
import com.hmdp.entity.ShopPortrait;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopPortraitMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ShopSummaryHandler {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private ShopPortraitMapper shopPortraitMapper;
    @Autowired
    private ChatLanguageModel chatLanguageModel;

    private static final String QUEUE_NAME = "stream.shop.summary";
    private static final String GROUP_NAME = "g1";
    private static final int UPDATE_THRESHOLD = 5; // 满 5 篇笔记触发一次提纯

    // 创建单线程池，专门用来在后台处理大模型摘要任务
    private static final ExecutorService SUMMARY_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        // 1. 初始化消费者组 (如果不存在则创建)
        try {
            stringRedisTemplate.opsForStream().createGroup(QUEUE_NAME, GROUP_NAME);
        } catch (Exception e) {
            log.info("消息队列和消费组已存在");
        }
        // 2. 启动后台线程监听
        SUMMARY_EXECUTOR.submit(new SummaryTask());
    }
    // ... 原来的 @PostConstruct init() 方法 ...

    // 💡 新增：Spring 容器销毁前，优雅关闭大模型摘要线程池
    @PreDestroy
    public void destroy() {
        if (SUMMARY_EXECUTOR != null) {
            log.info("准备关闭 AI 店铺摘要后台线程...");
            SUMMARY_EXECUTOR.shutdownNow();
        }
    }


    private class SummaryTask implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 1. 获取消息队列中的消息：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.shop.summary >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );

                    // 2. 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        continue; // 没消息，继续循环等待
                    }

                    // 3. 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    Long shopId = Long.valueOf(values.get("shopId").toString());

                    // 4. 执行核心提纯逻辑 (带计步器限制)
                    processSummary(shopId);

                    // 5. 极其重要：处理完成后，必须 ACK 确认消息，从 Pending List 中移除！
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());

                } catch (Exception e) {
                    // 💡 修改：捕获到异常时，判断是不是因为系统强行关机导致的中断
                    if (e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                        log.info("AI 店铺摘要线程被安全中断");
                        break;
                    }
                    log.error("处理店铺摘要异常", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
        }
    }

    private void processSummary(Long shopId) {
        String redisKey = "shop:summary:count:" + shopId;
        Long newCount = stringRedisTemplate.opsForValue().increment(redisKey);

        if (newCount != null && newCount < UPDATE_THRESHOLD) {
            log.info("店铺 {} 收到新笔记，当前计数 {}，未达阈值，跳过大模型调用", shopId, newCount);
            return;
        }

        log.info("店铺 {} 新增笔记达到 {} 篇，触发 AI 知识蒸馏！", shopId, UPDATE_THRESHOLD);
        stringRedisTemplate.delete(redisKey); // 清零计数器

        // 查出该店最近的 10 篇原文
        LambdaQueryWrapper<Blog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Blog::getShopId, shopId)
                .orderByDesc(Blog::getCreateTime)
                .last("LIMIT 10");
        List<Blog> recentBlogs = blogMapper.selectList(queryWrapper);
        if (recentBlogs.isEmpty()) return;

        String rawComments = recentBlogs.stream()
                .map(Blog::getContent)
                .filter(c -> c != null && !c.isEmpty())
                .collect(Collectors.joining("\n---\n"));

        String prompt = """
                你是一个严苛的餐厅质检员。请阅读以下用户的真实探店笔记，提取出该店的核心情报。
                必须严格按照以下格式返回（不要说废话）：
                【优点】：标签1, 标签2
                【避雷】：标签1, 标签2
                【摘要】：一段100字以内的客观总结，突出核心特色和致命缺点。
                以下是评论原文：
                """ + rawComments;

        // 调用大模型
        String aiResponse = chatLanguageModel.generate(prompt);

        String positiveTags = extractBetween(aiResponse, "【优点】：", "【避雷】：");
        String negativeTags = extractBetween(aiResponse, "【避雷】：", "【摘要】：");
        String summary = extractAfter(aiResponse, "【摘要】：");

        // 存入画像表
        ShopPortrait portrait = new ShopPortrait();
        portrait.setShopId(shopId);
        portrait.setPositiveTags(positiveTags);
        portrait.setNegativeTags(negativeTags);
        portrait.setAiSummary(summary);
        portrait.setUpdateTime(LocalDateTime.now());

        ShopPortrait existPortrait = shopPortraitMapper.selectById(shopId);
        if (existPortrait == null) {
            shopPortraitMapper.insert(portrait);
        } else {
            shopPortraitMapper.updateById(portrait);
        }
        log.info("店铺 {} 的 AI 画像已成功提纯并入库！", shopId);
    }

    private String extractBetween(String text, String start, String end) {
        try {
            return text.substring(text.indexOf(start) + start.length(), text.indexOf(end)).trim();
        } catch (Exception e) { return ""; }
    }

    private String extractAfter(String text, String start) {
        try {
            return text.substring(text.indexOf(start) + start.length()).trim();
        } catch (Exception e) { return ""; }
    }

}
