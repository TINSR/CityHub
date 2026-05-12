package com.hmdp.service;

import com.hmdp.entity.Shop;

import java.util.List;
import java.util.Map;

public interface SemanticSearchService {
     List<Shop> searchShops(String query);
    Map<Long, Double> searchFromQdrantHttp(String collectionName, float[] vector, int limit);
}
