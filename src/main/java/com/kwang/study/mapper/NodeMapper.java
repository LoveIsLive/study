package com.kwang.study.mapper;

import com.kwang.study.pojo.Node;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NodeMapper {

    int insertNode(Node node);

    Node selectNodeById(Long id);

    List<Node> selectChildrenByParentId(Long parentId);

    List<Node> selectRootChildren();

    Node selectNodeByParentIdAndName(@Param("parentId") Long parentId, @Param("name") String name);

    int deleteNodeById(Long id);

    int deleteNodeByIds(@Param("ids") List<Long> ids);

    int updateNodeForFile(@Param("id") Long id,
                          @Param("refPath") String refPath,
                          @Param("hash") String hash,
                          @Param("size") int size);
}
