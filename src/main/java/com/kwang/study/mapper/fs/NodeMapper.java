package com.kwang.study.mapper.fs;

import com.kwang.study.dto.fs.result.NodeDetailDTO;
import com.kwang.study.pojo.fs.Node;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface NodeMapper {

    /**
     * 插入一个新节点（文件或目录）
     * @param node 节点信息
     * @return 影响的行数
     */
    int insertNode(Node node);

    /**
     * 根据ID查询节点
     * @param id 节点ID
     * @return 节点信息
     */
    Node selectNodeById(Long id);

    /**
     * 批量根据ID查询节点
     * @param ids 节点ID列表
     * @return 节点列表
     */
    List<Node> selectNodesByIds(@Param("ids") List<Long> ids);

    /**
     * 根据父ID和名称查询节点，用于检查同级目录下的唯一性
     * @param parentId 父节点ID
     * @param name 名称
     * @return 节点信息
     */
    Node selectNodeByParentIdAndName(@Param("parentId") Long parentId, @Param("name") String name);

    /**
     * 查询指定目录下的子节点
     * @param parentId 父节点ID, 根目录为null
     * @param types 节点类型列表 (0: 目录, 1: 文件)，如果为null则查询所有类型
     * @return 子节点列表
     */
    List<Node> selectChildrenByParentId(@Param("parentId") Long parentId, @Param("types") List<Integer> types);

    /**
     * 更新节点信息（例如：重命名）
     * @param node 待更新的节点信息
     * @return 影响的行数
     */
    int updateNode(Node node);

    /**
     * 根据ID删除节点
     * @param id 节点ID
     * @return 影响的行数
     */
    int deleteNodeById(Long id);

    /**
     * 批量删除节点
     * @param ids 节点ID列表
     * @return 影响的行数
     */
    int batchDeleteNodeByIds(@Param("ids") List<Long> ids);

    /**
     * 获取指定节点的所有后代节点ID (用于递归删除)
     * @param id 节点ID
     * @return 所有后代节点的ID列表
     */
    List<Long> selectAllDescendantIds(Long id);

    /**
     * 根据ID查询节点详细信息（包含MIME类型名称）
     * @param id 节点ID
     * @return 节点详细信息DTO
     */
    NodeDetailDTO selectNodeDetailById(Long id);

    /**
     * 查询指定目录下的子节点详细信息（包含MIME类型名称）
     * @param parentId 父节点ID, 根目录为null
     * @param types 节点类型列表 (0: 目录, 1: 文件)，如果为null则查询所有类型
     * @return 子节点详细信息DTO列表
     */
    List<NodeDetailDTO> selectChildrenDetailByParentId(@Param("parentId") Long parentId, @Param("types") List<Integer> types);

    /**
     * 更新节点的父ID或名称（用于移动和重命名）
     * 在Service层调用前，必须检查目标路径下名称是否冲突
     * @param id 要操作的节点ID
     * @param newParentId 新的父节点ID (如果为null则不修改)
     * @param newName 新的名称 (如果为null则不修改)
     * @return 影响的行数
     */
    int updateNodeParentAndName(@Param("id") Long id,
                                @Param("newParentId") Long newParentId,
                                @Param("newName") String newName);

    /**
     * 根据节点ID，反向递归查询其完整路径
     * @param id 节点ID, id为null时，返回"/"
     * @return 节点的完整路径字符串, e.g., "/home/user/file.txt"
     */
    String selectFullPathById(Long id);

    /**
     * 根据名称模式搜索节点 (模糊查询)
     * @param namePattern 搜索模式, e.g., "%report%"
     * @param types 节点类型列表 (0: 目录, 1: 文件)，如果为null则查询所有类型
     * @return 匹配的节点列表
     */
    List<Node> searchNodesByName(@Param("namePattern") String namePattern, @Param("types") List<Integer> types);
}