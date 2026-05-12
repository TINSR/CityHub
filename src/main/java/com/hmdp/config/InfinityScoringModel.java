package com.hmdp.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class InfinityScoringModel implements ScoringModel {

    private final String baseUrl;
    private final String modelName;
    private final RestTemplate restTemplate = new RestTemplate();

    public InfinityScoringModel(String baseUrl, String modelName) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    @Override
    public Response<Double> score(String text, String query) {
        // 单文本打分，直接复用多文本打分的逻辑
        return Response.from(scoreAll(List.of(TextSegment.from(text)), query).content().get(0));
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        // 拼接 Infinity 的真实重排接口地址
        String url = baseUrl + "/rerank";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 按照 Infinity 的接口要求构建 JSON 请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("query", query);
        requestBody.put("model", modelName);
        // 提取所有切片的纯文本内容
        requestBody.put("documents", segments.stream().map(TextSegment::text).collect(Collectors.toList()));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            // 发送 POST 请求并接收响应
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");

            // 注意：Infinity 返回的结果是按相关度从高到低排序的，并带有一个 "index" 字段
            // 但 LangChain4j 要求返回的分数列表必须与传入的 segments 顺序完全一一对应
            Double[] scores = new Double[segments.size()];
            for (Map<String, Object> result : results) {
                int index = (Integer) result.get("index");
                double score = ((Number) result.get("relevance_score")).doubleValue();
                scores[index] = score; // 利用 index 将分数还原回原本的文档顺序
            }

            return Response.from(List.of(scores));
        } catch (Exception e) {
            throw new RuntimeException("Infinity Reranker 调用失败: " + e.getMessage(), e);
        }
    }
}