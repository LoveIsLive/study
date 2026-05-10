package com.kwang.study.ware.mapper;

import com.kwang.study.ware.pojo.NodeMetadata;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 节点元数据 Mapper 接口
 */
@Mapper
public interface NodeMetadataMapper {

    /**
     * 根据nodeId查询元数据
     */
    NodeMetadata selectByNodeId(@Param("nodeId") Long nodeId);

    /**
     * 查询所有元数据
     */
    List<NodeMetadata> selectAll();

    /**
     * 新增元数据
     */
    int insert(NodeMetadata nodeMetadata);

    /**
     * 更新元数据（根据nodeId）
     */
    int updateOrCreateByNodeId(NodeMetadata nodeMetadata);

    /**
     * 根据nodeId删除元数据
     */
    int deleteByNodeId(@Param("nodeId") Long nodeId);
}