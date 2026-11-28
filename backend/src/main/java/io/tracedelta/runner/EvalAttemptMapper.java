package io.tracedelta.runner;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EvalAttemptMapper extends BaseMapper<EvalAttemptEntity> {

    @Update("""
            UPDATE eval_attempt
            SET status = 'INTERRUPTED_UNKNOWN',
                error_type = 'INTERRUPTED_UNKNOWN',
                error_message = #{message},
                finished_at = #{now}
            WHERE status = 'RUNNING'
            """)
    int markRunningInterrupted(
            @Param("now") LocalDateTime now,
            @Param("message") String message);
}
