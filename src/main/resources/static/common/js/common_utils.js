

/**
 * 净化用户内容，对后端允许的前端值，进行前端净化显示
 */
const sanitizeHTML = (str) => {
    if (!str) return '';
    const temp = document.createElement('div');
    temp.textContent = str; // 利用浏览器的内置机制进行转义
    return temp.innerHTML;
};

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

const isPreviewable = (mimeTypeName) => {
    if (!mimeTypeName) return false;

    // 直接通过 startsWith 匹配大类
    if (mimeTypeName.startsWith('image/') ||
        mimeTypeName.startsWith('text/') ||
        mimeTypeName.startsWith('audio/') ||
        mimeTypeName.startsWith('video/')) {
        return true;
    }

    // 单独匹配其他可预览的具体类型
    return [
        'application/pdf',
        'application/json',    // 强烈建议：用于预览JSON数据
        'application/xml',     // 建议：用于预览XML数据
        'image/svg+xml'        // 强烈建议：用于预览SVG矢量图
    ].includes(mimeTypeName);
};

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
    'jsx': 'text/jsx',
    'ts': 'application/typescript',
    'tsx': 'text/tsx',
    'json': 'application/json',
    'xml': 'application/xml',
    'csv': 'text/csv',
    'py': 'text/x-python',
    'pyc': 'application/x-python-code',
    'java': 'text/x-java-source',
    'class': 'application/java-vm',
    'cpp': 'text/x-c++src',
    'cc': 'text/x-c++src',
    'cxx': 'text/x-c++src',
    'c': 'text/x-csrc',
    'h': 'text/x-chdr',
    'hpp': 'text/x-c++hdr',
    'cs': 'text/x-csharp',
    'php': 'application/x-httpd-php',
    'rb': 'text/x-ruby',
    'go': 'text/x-go',
    'rs': 'text/rust',
    'swift': 'text/x-swift',
    'kt': 'text/x-kotlin',
    'scala': 'text/x-scala',
    'pl': 'application/x-perl',
    'sh': 'application/x-sh',
    'bash': 'application/x-sh',
    'sql': 'application/sql',
    'r': 'text/x-r-source',
    'matlab': 'text/x-matlab',
    'm': 'text/x-matlab',
    'mp': 'application/mp',
    'lua': 'text/x-lua',
    'dart': 'application/dart',
    'vue': 'text/vue',
    'scss': 'text/x-scss',
    'sass': 'text/x-sass',
    'less': 'text/x-less',
    'coffee': 'text/coffeescript',

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

const mimeTypesValues = [...new Set(Object.values(mimeTypes))];


