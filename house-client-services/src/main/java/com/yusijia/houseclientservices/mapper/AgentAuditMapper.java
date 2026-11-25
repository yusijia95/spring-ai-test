package com.yusijia.houseclientservices.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yusijia.houseclientservices.domain.entity.AgentAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentAuditMapper extends BaseMapper<AgentAudit> {

    /**
     * 该会话当前最大轮次序号（无记录返回 0）。
     * 审计表只追加、无并发删除，MAX+1 即下一轮 turn。
     */
    @Select("SELECT COALESCE(MAX(turn), 0) FROM agent_audit WHERE cid = #{cid}")
    int maxTurn(@Param("cid") String cid);
}
