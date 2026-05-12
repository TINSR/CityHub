package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;



    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期
//        Shop shop = queryWithLogicalExpire(id);
//        if(shop==null){
//            return Result.fail("店铺不存在");
//        }
        //互斥锁解决缓存击穿
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY , id , Shop.class , this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY , id , Shop.class , this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return Result.ok(shop);
    }
    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    private void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));//永久有效
    }

    public Shop queryWithMutex(Long id){
        //从redis查缓存
        String shopJson=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY +id);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop= JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }if(shopJson!=null){
            return null;//说明此时shopjson是“”
        }

        //实现缓存重建
        //获取互斥锁
        Shop shop=null;
        try {
            boolean lockey = trylock(LOCK_SHOP_KEY + id);
            //判断是否获取成功
            if(!lockey){
                //失败则继续休眠
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取成功后判断缓存是否建立
            shopJson=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY +id);

            //判断是否存在
            if(StrUtil.isNotBlank(shopJson)){
                shop= JSONUtil.toBean(shopJson,Shop.class);
                return shop;
            }
            if(shopJson!=null){
                return null;//说明此时shopjson是“”
            }
            //不存在根据id查数据库
            shop = getById(id);
            //不存在直接返回
            if(shop ==null){
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在则写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY +id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(LOCK_SHOP_KEY + id);
        }return shop;

    }

    public Shop queryWithPassThrough(Long id){
        //从redis查缓存
        String shopJson=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY +id);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop= JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }if(shopJson!=null){
            return null;//说明此时shopjson是“”
        }

        //存在则直接返回

        //不存在根据id查数据库
        Shop shop =getById(id);
        //不存在直接返回
        if(shop ==null){
            return null;
        }
        //存在则写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY +id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return shop;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        //从redis查缓存
        String shopJson=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY +id);
        //判断是否存在
        if(StrUtil.isBlank(shopJson)){

            return null;
        }//命中则判断过期时间
        //先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = (LocalDateTime) redisData.getExpireTime();
        //未过期则直接返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //已过期就要缓存重建
        String lockKey = LOCK_SHOP_KEY + id ;
        //获取互斥锁
        boolean isLock = trylock(lockKey);
        //成功则开启独立线程
        if(isLock){
            //再次检测是否过期
            shopJson=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY +id);
            if(StrUtil.isBlank(shopJson)){

                return null;
            }//命中则判断过期时间
            //先把json反序列化为对象
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            data = (JSONObject) redisData.getData();
            shop = JSONUtil.toBean(data, Shop.class);
            expireTime = (LocalDateTime) redisData.getExpireTime();
            //未过期则直接返回
            if(expireTime.isAfter(LocalDateTime.now())){
                return shop;
            }//已过期则开启线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,1800L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(LOCK_SHOP_KEY + id);
                }
                //释放锁

            });


        }
        //返回商品信息
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id= shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();


    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
