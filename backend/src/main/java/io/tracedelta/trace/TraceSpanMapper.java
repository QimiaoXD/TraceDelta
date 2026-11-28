package io.tracedelta.trace;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TraceSpanMapper extends BaseMapper<TraceSpanEntity> {

    @Select("""
            SELECT * FROM trace_span
            WHERE trace_id = #{traceId}
            ORDER BY start_time_ms ASC, id ASC
            """)
    List<TraceSpanEntity> findByTraceIdOrdered(@Param("traceId") String traceId);

    @Delete("DELETE FROM trace_span WHERE run_id = #{runId}")
    int deleteByRunId(@Param("runId") Long runId);
}
