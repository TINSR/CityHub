package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
@Data
@TableName("tb_shop_portrait")
public class ShopPortrait {
    // 主键，并且告诉 MP 这个 ID 是手动输入的（对应 tb_shop 的 ID）
    @TableId(type = IdType.INPUT)
    private Long shopId;
    private String positiveTags;
    private String negativeTags;
    private String aiSummary;
    private LocalDateTime updateTime;
}
