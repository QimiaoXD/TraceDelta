package io.tracedelta.target;

import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TargetRegistry {

    private final Map<TargetType, EvalTarget> targets = new EnumMap<>(TargetType.class);

    public TargetRegistry(List<EvalTarget> targetList) {
        for (EvalTarget target : targetList) {
            targets.put(target.type(), target);
        }
    }

    public EvalTarget get(TargetType type) {
        EvalTarget target = targets.get(type);
        if (target == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "不支持的被测目标类型: " + type);
        }
        return target;
    }
}
