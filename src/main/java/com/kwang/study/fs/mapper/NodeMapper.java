package com.kwang.study.fs.mapper;

import com.kwang.study.fs.pojo.Node;
import com.kwang.study.fs.pojo.NodeDetail;
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
     * @return 子节点列表
     */
    List<Node> selectChildrenByParentId(@Param("parentId") Long parentId);

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
    NodeDetail selectNodeDetailById(Long id);

    /**
     * 查询指定目录下的子节点详细信息（包含MIME类型名称）
     * @param parentId 父节点ID, 根目录为null
     * @return 子节点详细信息DTO列表
     */
    List<NodeDetail> selectChildrenDetailByParentId(@Param("parentId") Long parentId);

    /**
     * 根据完整的Unix路径查询节点信息。
     * 使用递归CTE实现，高效查询。
     * @param path 完整的Unix路径，例如 "/home/user/file.txt"
     * @return 节点详细信息，如果路径不存在则返回null
     */
    Node selectNodeByPath(@Param("path") String path);

    /**
     * 根据完整的Unix路径查询节点详细信息。
     * 使用递归CTE实现，高效查询。
     * @param path 完整的Unix路径，例如 "/home/user/file.txt"
     * @return 节点详细信息，如果路径不存在则返回null
     */
    NodeDetail selectNodeDetailByPath(@Param("path") String path);

}