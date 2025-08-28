/**
返回登陆用户的所有角色
 */
function getRoles() {
    try {
        if (!token) {
            return false;
        }
        const decodedToken = jwt_decode(token);
        return decodedToken.roles || [];
    } catch (error) {
        console.error("解码JWT失败或Token无效:", error);
        return [];
    }
}

/**
返回登陆的用户名
 */
function getUserName() {
    try {
        if (!token) {
            return false;
        }
        const decodedToken = jwt_decode(token);
        return decodedToken.sub || "";
    } catch (error) {
        console.error("解码JWT失败或Token无效:", error);
        return "";
    }
}

function buildNewPath(currentPath, name) {
    if (!currentPath) {
        return "/" + name;
    }
    const basePath = currentPath.endsWith('/') ? currentPath : currentPath + '/';
    return basePath + name;
}

const mimeIconMap = {
    // Documents
    'application/pdf': 'fas fa-file-pdf',
    'application/msword': 'fas fa-file-word',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'fas fa-file-word',
    'application/vnd.ms-excel': 'fas fa-file-excel',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'fas fa-file-excel',
    'application/vnd.ms-powerpoint': 'fas fa-file-powerpoint',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'fas fa-file-powerpoint',
    'text/plain': 'fas fa-file-alt',
    'text/csv': 'fas fa-file-csv',

    // Code
    'text/html': 'fas fa-file-code',
    'text/css': 'fas fa-file-code',
    'application/javascript': 'fas fa-file-code',
    'application/json': 'fas fa-file-code',
    'application/xml': 'fas fa-file-code',

    // Archives
    'application/zip': 'fas fa-file-archive',
    'application/vnd.rar': 'fas fa-file-archive',
    'application/x-7z-compressed': 'fas fa-file-archive',
    'application/x-tar': 'fas fa-file-archive',
    'application/gzip': 'fas fa-file-archive',

    // Fallback for unknown binary files
    'application/octet-stream': 'fas fa-file-binary',
    'application/x-msdownload': 'fas fa-hdd', // Icon for executables
};


const mimeTypes = {
    // Images
    'png': 'image/png',
    'jpg': 'image/jpeg',
    'jpeg': 'image/jpeg',
    'gif': 'image/gif',
    'bmp': 'image/bmp',
    'webp': 'image/webp',
    'svg': 'image/svg+xml',

    // Audio
    'mp3': 'audio/mpeg',
    'wav': 'audio/wav',
    'ogg': 'audio/ogg',
    'm4a': 'audio/mp4',

    // Video
    'mp4': 'video/mp4',
    'webm': 'video/webm',
    'mov': 'video/quicktime',
    'avi': 'video/x-msvideo',
    'mkv': 'video/x-matroska',

    // Documents
    'pdf': 'application/pdf',
    'txt': 'text/plain',
    'rtf': 'application/rtf',
    'doc': 'application/msword',
    'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'xls': 'application/vnd.ms-excel',
    'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'ppt': 'application/vnd.ms-powerpoint',
    'pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',

    // Data & Code
    'html': 'text/html',
    'htm': 'text/html',
    'css': 'text/css',
    'js': 'application/javascript',
    'json': 'application/json',
    'xml': 'application/xml',
    'csv': 'text/csv',

    // Archives
    'zip': 'application/zip',
    'rar': 'application/vnd.rar',
    '7z': 'application/x-7z-compressed',
    'tar': 'application/x-tar',
    'gz': 'application/gzip',
    'tgz': 'application/gzip',

    // Binary & Executables
    'bin': 'application/octet-stream',
    'exe': 'application/x-msdownload',
    'dll': 'application/x-msdownload',
};