package com.bhu.runshistudioweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * studio_notification 表的数据访问层（站内通知）
 *
 * @author shaoshing
 */
@Mapper
public interface StudioNotificationMapper extends BaseMapper<StudioNotification> {

    /**
     * 点赞通知的原子聚合写入：有则更新，无则插入
     *
     * <p>撞的是唯一键 uk_receiver_biz(receiver_id, type, biz_type, biz_id, deleted_at)，
     * 因此同一作者 + 同一帖子只有一条 POST_LIKED 通知。用单条 SQL 而不是「先查再改」，
     * 避免多人同时点赞时的并发竞态与双写。
     *
     * <p>更新时重置 is_read/read_at 并刷新 created_at：新互动要重新出现在列表顶部、红点重新亮起。
     * 不走 MyBatis-Plus 的自动填充，故所有 NOT NULL 列都显式给值。
     *
     * @return 影响行数（插入为 1；命中唯一键更新通常为 2，不用于业务判断）
     */
    @Insert("""
            INSERT INTO studio_notification
                (id, receiver_id, type, biz_type, biz_id, title, content,
                 is_read, read_at, created_at, updated_at, created_by, updated_by, deleted_at)
            VALUES
                (#{id}, #{receiverId}, #{type}, #{bizType}, #{bizId}, #{title}, #{content},
                 0, NULL, #{now}, #{now}, 0, 0, 0)
            ON DUPLICATE KEY UPDATE
                title      = #{title},
                content    = #{content},
                is_read    = 0,
                read_at    = NULL,
                created_at = #{now},
                updated_at = #{now}
            """)
    int upsertLikeAggregate(@Param("id") Long id,
                            @Param("receiverId") Long receiverId,
                            @Param("type") String type,
                            @Param("bizType") String bizType,
                            @Param("bizId") Long bizId,
                            @Param("title") String title,
                            @Param("content") String content,
                            @Param("now") LocalDateTime now);
}
