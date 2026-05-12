package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;
    public  LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        //基于token获取redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY +token);

        //判断用户是否存在
        if(userMap.isEmpty()){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return  false;
        }
        //不存在则拦截

        //转成userdto
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        System.out.println("=== 3. ThreadLocal里的User: " + userDTO);
        //存在就保存用户信息到threadlocal
        UserHolder.saveUser((userDTO));
        //刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY +token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return  true;
    }
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.saveUser(null);
    }
}
