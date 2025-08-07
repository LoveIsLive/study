/**
 * 检查当前登录的用户是否包含 'ROLE_TEACHER' 角色。
 * @returns {boolean} 如果是教师则返回 true，否则返回 false。
 */
function getRoles() {
    try {
        if (!token) {
            return false; // 没有 token，肯定不是教师
        }

        const decodedToken = jwt_decode(token);

        return decodedToken.roles || [];
    } catch (error) {
        console.error("解码JWT失败或Token无效:", error);
        return false;
    }
}

function buildNewPath(currentPath, name) {
    if (!currentPath) {
        return "/" + name;
    }
    const basePath = currentPath.endsWith('/') ? currentPath : currentPath + '/';
    return basePath + name;
}