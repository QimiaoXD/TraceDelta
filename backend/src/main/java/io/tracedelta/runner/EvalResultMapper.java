package io.tracedelta.runner;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EvalResultMapper extends BaseMapper<EvalResultEntity> {

    @Update("""
            UPDATE eval_result
            SET status = 'RUNNING',
                attempt_count = attempt_count + 1,
                version = version + 1,
                started_at = COALESCE(started_at, #{now}),
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'PENDING'
              AND attempt_count < #{maxAttempts}
            """)
    int claimPending(
            @Param("id") Long id,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE eval_result
            SET attempt_count = attempt_count + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'RUNNING'
              AND attempt_count < #{maxAttempts}
            """)
    int incrementAttemptCount(
            @Param("id") Long id,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE eval_result
            SET status = 'SKIPPED',
                error_type = #{errorType},
                error_message = #{errorMessage},
                version = version + 1,
                finished_at = #{now},
                updated_at = #{now}
            WHERE run_id = #{runId}
              AND status = 'PENDING'
            """)
    int skipPendingForRun(
            @Param("runId") Long runId,
            @Param("errorType") String errorType,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now);
}
