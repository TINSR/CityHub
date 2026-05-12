package com.hmdp.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class InfinityEmbeddingModel implements EmbeddingModel {

    private final String baseUrl;
    private final String modelName;
    private final RestTemplate restTemplate = new RestTemplate();

    public InfinityEmbeddingModel(String baseUrl, String modelName) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        String url = baseUrl + "/embeddings";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        List<String> inputs = textSegments.stream().map(TextSegment::text).collect(Collectors.toList());
        requestBody.put("input", inputs);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            //先接收最原始的 String 格式 JSON，而不是直接转 Map
            String rawJson = restTemplate.postForObject(url, entity, String.class);

            // 手动把 JSON 转回 Map 继续处理
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> response = mapper.readValue(rawJson, Map.class);

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

            List<Embedding> embeddings = data.stream().map(item -> {
                List<Double> vector = (List<Double>) item.get("embedding");
                float[] floatVector = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    floatVector[i] = vector.get(i).floatValue();
                }
                return Embedding.from(floatVector);
            }).collect(Collectors.toList());

            return Response.from(embeddings);
        } catch (Exception e) {
            throw new RuntimeException("Infinity Embedding 调用失败: " + e.getMessage(), e);
        }
    }
}
