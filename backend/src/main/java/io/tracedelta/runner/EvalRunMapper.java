package io.tracedelta.runner;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRunEntity> {

    @Update("""
            UPDATE eval_run
            SET status = 'RUNNING',
                started_at = COALESCE(started_at, #{now}),
                updated_at = #{now}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE eval_run
            SET cancel_requested = TRUE,
                updated_at = #{now}
            WHERE id = #{id} AND status IN ('PENDING', 'RUNNING')
            """)
    int markCancelRequested(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE eval_run
            SET trace_incomplete = TRUE,
                trace_dropped_count = trace_dropped_count + #{delta},
                updated_at = #{now}
            WHERE id = #{runId}
            """)
    int incrementTraceDropped(
            @Param("runId") Long runId,
            @Param("delta") int delta,
            @Param("now") LocalDateTime now);
}
