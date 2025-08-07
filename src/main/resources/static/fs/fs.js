document.addEventListener('DOMContentLoaded', () => {
    // --- STATE ---
    let currentParentId = null; // null for root directory
    let currentPath = '/';
    let pathMap = new Map([ [null, '/'] ]);
    let stompClient = null;
    let roles = getRoles();
    const isTeacher = roles.includes("ROLE_TEACHER");

    // --- CONFIG ---
    const API_BASE_URL = 'http://localhost:8080/api/v1';
    const FS_PREFIX = "/fs";
    const FS_BASE_URL = API_BASE_URL + FS_PREFIX;
    const CHUNK_SIZE = 10 * 1024 * 1024; // 分块上传的阈值

    // --- DOM ELEMENTS ---
    // 提前动态渲染一些元素
    const newDiv = document.getElementById('new-div');
    if (isTeacher) {
        newDiv.innerHTML = `<button id="new-btn" class="btn btn-primary"><i class="fas fa-plus"></i> 新建</button>`;
    }

    const fileListBody = document.getElementById('file-list');
    const breadcrumb = document.getElementById('breadcrumb');
    const loadingSpinner = document.getElementById('loading-spinner');

    const newItemModal = document.getElementById('new-item-modal');
    const closeModalBtns = document.querySelectorAll('.close-btn');

    const showNewDirBtn = document.getElementById('show-new-dir-form');
    const showUploadFileBtn = document.getElementById('show-upload-file-form');
    const newDirForm = document.getElementById('new-dir-form');
    const uploadFileForm = document.getElementById('upload-file-form');
    const newDirNameInput = document.getElementById('new-dir-name');

    const dragDropArea = document.getElementById('drag-drop-area');
    const fileInput = document.getElementById('file-input');
    const fileNameInput = document.getElementById('file-input-name');
    const fileNameDisplay = document.getElementById('file-name-display');
    const mimeTypeSelect = document.getElementById('mime-type-select');

    const uploadProgressContainer = document.getElementById('upload-progress-container');
    const uploadStatus = document.getElementById('upload-status');
    const progressBarInner = document.getElementById('progress-bar-inner');

    const pathInput = document.getElementById('path-input');
    const searchInput = document.getElementById('search-input');
    const searchResultsModal = document.getElementById('search-results-modal');
    const searchResultsList = document.getElementById('search-results-list');
    const searchStatusMessage = document.getElementById('search-status-message');


    // --- API HELPERS ---
    const fsApi = axiosCreate(FS_BASE_URL);

    const showLoading = (show) => {
        loadingSpinner.style.display = show ? 'flex' : 'none';
        fileListBody.style.display = show ? 'none' : 'table-row-group';
    };
    const toast = (icon, title) => Swal.fire({
        toast: true,
        position: 'top-end',
        icon,
        title,
        showConfirmButton: false,
        timer: 3000,
        timerProgressBar: true
    });

    // --- RENDER FUNCTIONS ---
    const getFileIcon = (type, mimeTypeName) => {
        // 1. Handle Folders
        if (type === 0) return 'fas fa-folder';

        // 2. Handle invalid or empty MIME type
        if (!mimeTypeName) return 'fas fa-file';

        // 3. Icon mapping for specific MIME types
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

        // 4. Check for a direct match in the map
        if (mimeIconMap[mimeTypeName]) {
            return mimeIconMap[mimeTypeName];
        }

        // 5. Check for broad category matches
        if (mimeTypeName.startsWith('image/')) return 'fas fa-file-image';
        if (mimeTypeName.startsWith('audio/')) return 'fas fa-file-audio';
        if (mimeTypeName.startsWith('video/')) return 'fas fa-file-video';

        // 6. Default fallback icon
        return 'fas fa-file';
    };

    const formatSize = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

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

    const renderNodes = (nodes) => {
        fileListBody.innerHTML = '';
        if (nodes.length === 0) {
            fileListBody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding: 2rem;">此目录为空</td></tr>';
            return;
        }

        nodes.forEach(node => {
            const tr = document.createElement('tr');
            tr.dataset.id = node.id;
            tr.dataset.name = node.name;
            tr.dataset.type = node.type;
            tr.dataset.mime = node.mimeTypeId; // Assuming backend provides this

            // Build full path for this node and store it
            const nodeFullPath = buildNewPath(currentPath, node.name);
            pathMap.set(node.id, nodeFullPath);

            const teacherActions = isTeacher ? `
            <i class="fas fa-edit action-icon rename-icon" title="重命名"></i>
            <i class="fas fa-trash-alt action-icon delete-icon" title="删除"></i>
        ` : '';

            const actions = `
                <div class="actions-container">
                    <i class="fas fa-copy action-icon copy-path-icon" title="复制路径"></i>
                    ${node.type === 1 && isPreviewable(node.mimeTypeName) ? '<i class="fas fa-eye action-icon preview-icon" title="预览"></i>' : ''}
                    ${node.type === 1 ? `
                        <a href="${FS_BASE_URL}/nodes/${node.id}/download" download="${node.name}" title="下载">
                          <i class="fas fa-download action-icon download-icon"></i>
                        </a>
                      ` : ''}
                    <i class="fas fa-info-circle action-icon details-icon" title="属性"></i>
                    ${teacherActions}
                </div>
            `;
            const displayName = node.type === 100 ? node.name + "（正在上传）" : node.name;
            tr.innerHTML = `
                <td class="col-icon"><i class="node-icon ${getFileIcon(node.type, node.mimeTypeName)}"></i></td>
                <td class="col-name"><div class="node-name"><span>${displayName}</span></div></td>
                <td class="col-size">${node.type === 1 ? formatSize(node.size) : '--'}</td>
                <td class="col-modified">${new Date(node.modifyTime).toLocaleString()}</td>
                <td class="col-actions">${actions}</td>
            `;

            if (node.type === 0) { // Is a directory
                tr.addEventListener('dblclick', () => {
                    renderDirAndUpdateUrl(node.id, buildNewPath(currentPath, node.name));
                });
            }

            fileListBody.appendChild(tr);
        });
    };

    const updateBreadcrumb = () => {
        breadcrumb.innerHTML = '';
        const parts = currentPath.split('/').filter(p => p);
        let path = '';

        const rootLink = document.createElement('a');
        rootLink.href = FS_PREFIX;
        rootLink.textContent = '根目录';
        rootLink.addEventListener('click', (e) => {
            e.preventDefault();
            renderDirAndUpdateUrl(null, '/')
        });
        breadcrumb.appendChild(rootLink);

        parts.forEach((part, index) => {
            const separator = document.createElement('span');
            separator.textContent = '>';
            breadcrumb.appendChild(separator);

            // Find the ID for this path segment
            const fullPathForPart = '/' + parts.slice(0, index + 1).join('/');
            const partId = [...pathMap.entries()].find(([id, path]) => path === fullPathForPart)?.[0];

            if (partId) {
                const partLink = document.createElement('a');
                partLink.href = fullPathForPart;
                partLink.textContent = part;

                partLink.addEventListener('click', (e) => {
                    e.preventDefault();
                    renderDirAndUpdateUrl(partId, fullPathForPart);
                });
                breadcrumb.appendChild(partLink);
            }
        });
    };

    // --- CORE LOGIC ---
    const renderDirAndUpdateUrl = async (parentId = null, fullPath = '/') => {
        await fetchAndRenderNodes(parentId);
        history.pushState({ id: parentId }, '', FS_PREFIX + fullPath);
    }

    const fetchAndRenderNodes = async (parentId = null) => {
        showLoading(true);
        try {
            const response = await fsApi.get('/nodes', { params: { parentId } });
            currentParentId = parentId;
            currentPath = pathMap.get(parentId) || '/';
            renderNodes(response.data.data);
            updateBreadcrumb();
        } catch (error) {
            console.error('Failed to fetch nodes:', error);
            toast('error', '加载失败');
        } finally {
            showLoading(false);
        }
    };

    const fetchMimeTypes = async () => {
        try {
            const response = await fsApi.get('/mime-types/all');
            const mimeTypes = response.data.data;
            mimeTypeSelect.innerHTML = '<option value="" disabled selected>请选择文件类型</option>';
            mimeTypes.forEach(name => {
                const option = document.createElement('option');
                option.value = name;
                option.textContent = name;
                mimeTypeSelect.appendChild(option);
            });
        } catch (error) {
            console.error('Failed to fetch mime types:', error);
        }
    };

    // --- EVENT HANDLERS ---

    // Modal Handling
    if (isTeacher) {
        document.getElementById('new-btn').onclick = () => {
            newDirForm.style.display = 'none';
            uploadFileForm.style.display = 'none';
            document.getElementById('new-item-options').style.display = 'flex';
            newItemModal.style.display = 'block';
        };
    }

    closeModalBtns.forEach(btn => btn.onclick = () => {
        btn.closest('.modal').style.display = 'none';
    });
    window.onclick = (event) => {
        if (event.target.classList.contains('modal')) {
            event.target.style.display = 'none';
        }
    };
    showNewDirBtn.onclick = () => {
        document.getElementById('new-item-options').style.display = 'none';
        newDirForm.style.display = 'flex';
        newDirNameInput.focus();
    };
    showUploadFileBtn.onclick = () => {
        document.getElementById('new-item-options').style.display = 'none';
        uploadFileForm.style.display = 'flex';
    };

    // Form Submissions
    newDirForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const dirName = newDirNameInput.value.trim();
        if (!dirName) return;

        try {
            await fsApi.post('/nodes/directories', {
                name: dirName,
                parentId: currentParentId,
                permissions: "rwxrwxrwx"
            });
            toast('success', '目录创建成功');
            newItemModal.style.display = 'none';
            newDirNameInput.value = '';
            fetchAndRenderNodes(currentParentId);
        } catch (error) {
            console.error('Failed to create directory:', error);
            toast('error', error.response?.data?.message || '创建失败');
        }
    });

    // File Upload Logic
    function getMimeType(fileName) {
        const extension = fileName.split('.').pop().toLowerCase();
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
        return mimeTypes[extension] || ''; // 如果找不到匹配项，则返回空字符串
    }
    let fileToUpload = null;
    dragDropArea.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => {
        if (e.target.files.length > 0) {
            fileToUpload = e.target.files[0];
            fileNameDisplay.textContent = `已选择文件: ${fileToUpload.name}`;
            dragDropArea.classList.add('has-file');
            if (!fileNameInput.value) {
                // 如果name输入框为空，将文件名（不含后缀）填入
                fileNameInput.value = fileToUpload.name.split('.').slice(0, -1).join('.');
            }
            if (!mimeTypeSelect.value) {
                const mimeType = getMimeType(fileToUpload.name);
                console.log('mimeType', mimeType)
                if (mimeType) {
                    mimeTypeSelect.value = mimeType;
                } else {
                    toast('warning', '无法识别的文件类型，请手动选择');
                }
            }
        }
    });
    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        dragDropArea.addEventListener(eventName, e => {
            e.preventDefault();
            e.stopPropagation();
        }, false);
    });
    ['dragenter', 'dragover'].forEach(eventName => {
        dragDropArea.addEventListener(eventName, () => dragDropArea.classList.add('dragover'), false);
    });
    ['dragleave', 'drop'].forEach(eventName => {
        dragDropArea.addEventListener(eventName, () => dragDropArea.classList.remove('dragover'), false);
    });
    dragDropArea.addEventListener('drop', e => {
        if (e.dataTransfer.files.length > 0) {
            fileToUpload = e.dataTransfer.files[0];
            fileNameDisplay.textContent = `已选择文件: ${fileToUpload.name}`;
            dragDropArea.classList.add('has-file');
        }
    });

    uploadFileForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!fileToUpload || !mimeTypeSelect.value) {
            toast('warning', '请选择文件和MIME类型');
            return;
        }

        uploadProgressContainer.style.display = 'block';

        if (fileToUpload.size > CHUNK_SIZE) {
            await uploadFileInChunks(fileToUpload);
        } else {
            await uploadSmallFile(fileToUpload);
        }
    });

    const uploadSmallFile = async (file) => {
        const formData = new FormData();
        formData.append('name', file.name);
        formData.append('parentId', currentParentId);
        formData.append('file', file);
        formData.append('mimeTypeName', mimeTypeSelect.value);
        formData.append('permissions', 'rwxrwxrwx');

        try {
            uploadStatus.textContent = '正在上传...';
            progressBarInner.style.width = '50%'; // Indicate progress

            await fsApi.post('/nodes/files', formData, {
                onUploadProgress: progressEvent => {
                    const percentCompleted = Math.round((progressEvent.loaded * 100) / progressEvent.total);
                    progressBarInner.style.width = percentCompleted + '%';
                }
            });

            toast('success', '文件上传成功');
            resetUploadForm();
            fetchAndRenderNodes(currentParentId);

        } catch (error) {
            console.error('Upload failed:', error);
            toast('error', '上传失败');
            uploadStatus.textContent = '上传失败';
        }
    };

    /**
     * 并发执行Promise任务池
     * @param {Array<Function>} tasks - 一个返回Promise的任务函数数组
     * @param {number} concurrencyLimit - 最大并发数
     * @returns {Promise<Array>} 所有任务成功后的结果数组
     */
    async function executeConcurrentPromises(tasks, concurrencyLimit) {
        const results = [];
        const executing = [];
        let taskIndex = 0;

        for (let i = 0; i < tasks.length; i++) {
            const p = Promise.resolve().then(() => {
                const task = tasks[taskIndex++];
                return task();
            });
            results.push(p);

            if (concurrencyLimit <= tasks.length) {
                const e = p.then(() => executing.splice(executing.indexOf(e), 1));
                executing.push(e);
                if (executing.length >= concurrencyLimit) {
                    await Promise.race(executing);
                }
            }
        }
        return Promise.all(results);
    }

    const uploadFileInChunks = async (file) => {
        uploadStatus.textContent = '正在初始化大文件上传...';
        progressBarInner.style.width = '0%';
        const CONCURRENCY_LIMIT = 6; // 设置并发上传数量

        // 1. 初始化上传，获取fileId
        let fileId;
        try {
            const initResponse = await fsApi.post('/chunk/init', null, { params: {
                    name: file.name,
                    parentId: currentParentId,
                    mimeTypeName: mimeTypeSelect.value,
                    permissions: 'rwxrwxrwx'
                }});
            fileId = initResponse.data.data.id;
        } catch(error) {
            console.error('Chunk init failed:', error);
            toast('error', '初始化失败');
            uploadStatus.textContent = '初始化失败';
            return;
        }

        // 2. 创建所有分块的上传任务
        const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
        const chunkTasks = [];
        const progressMap = new Array(totalChunks).fill(0); // 用于跟踪每个分块的上传进度
        let totalLoaded = 0;

        const updateTotalProgress = () => {
            totalLoaded = progressMap.reduce((acc, val) => acc + val, 0);
            const percentCompleted = Math.round((totalLoaded * 100) / file.size);
            progressBarInner.style.width = percentCompleted + '%';
            uploadStatus.textContent = `正在上传... ${percentCompleted}%`;
        };

        for (let i = 0; i < totalChunks; i++) {
            // 使用函数包装，延迟执行
            const task = () => {
                const start = i * CHUNK_SIZE;
                const end = Math.min(start + CHUNK_SIZE, file.size);
                const chunk = file.slice(start, end);

                const formData = new FormData();
                formData.append('fileId', fileId);
                formData.append('chunkIndex', i);
                formData.append('totalChunks', totalChunks);
                formData.append('chunk', chunk);

                return fsApi.post('/chunk/upload', formData, {
                    onUploadProgress: progressEvent => {
                        // 更新当前分片的进度
                        progressMap[i] = progressEvent.loaded;
                        // 更新总进度条
                        updateTotalProgress();
                    }
                }).catch(err => {
                    // 如果单个分片失败，让整个上传失败
                    console.error(`分片 ${i} 上传失败:`, err);
                    // 抛出错误，以便Promise.all可以捕获到
                    throw new Error(`分片 ${i} 上传失败`);
                });
            };
            chunkTasks.push(task);
        }

        // 3. 并发执行上传任务
        try {
            await executeConcurrentPromises(chunkTasks, CONCURRENCY_LIMIT);

            // 由于后端的合并是自动触发的，前端在这里只需要确认所有分块都已发送
            // 最终的合并成功与否以后端返回为准。
            // 为了确保进度条达到100%，即使在请求完成后也手动更新一下。
            progressBarInner.style.width = '100%';
            uploadStatus.textContent = '上传完成，等待后端合并...';

            // TODO: 需要改为websocket通知，文件合并需要后端线程执行
            // 这里可以轮询一个节点状态接口来确认合并成功，或者简单地认为上传已完成
            // 为简单起见，我们直接刷新列表
            setTimeout(() => {
                toast('success', '文件上传成功！');
                resetUploadForm();
                fetchAndRenderNodes(currentParentId);
            }, 1000); // 留出一点时间给后端合并

        } catch (error) {
            console.error('Upload failed during chunk upload:', error);
            toast('error', '上传过程中发生错误，请重试。');
            uploadStatus.textContent = '上传失败';
        }
    };

    const resetUploadForm = () => {
        newItemModal.style.display = 'none';
        uploadFileForm.reset();
        fileToUpload = null;
        fileNameDisplay.textContent = '';
        uploadProgressContainer.style.display = 'none';
        progressBarInner.style.width = '0%';
    }

    // Actions on file/dir rows
    fileListBody.addEventListener('click', async (e) => {
        const target = e.target;
        const actionIcon = target.closest('.action-icon');
        if (!actionIcon) return;

        const tr = target.closest('tr');
        const id = tr.dataset.id;
        const name = tr.dataset.name;
        const type = parseInt(tr.dataset.type, 10);
        const nodePath = pathMap.get(parseInt(id));

        if (actionIcon.classList.contains('copy-path-icon')) {
            navigator.clipboard.writeText(nodePath).then(() => {
                toast('success', '路径已复制');
            }).catch(err => {
                console.error('Failed to copy path:', err);
                toast('error', '复制失败');
            });
        }

        if (actionIcon.classList.contains('preview-icon')) {
            window.open(`${FS_BASE_URL}/nodes/${id}/download?mode=inline`, '_blank');
        }

        if (actionIcon.classList.contains('details-icon')) {
            try {
                const response = await fsApi.get(`/nodes/${id}/details`);
                const details = response.data.data;
                Swal.fire({
                    title: `<strong>属性: ${details.name}</strong>`,
                    html: `
                        <div style="text-align: left; margin-left: 2rem;">
                        <p><strong>类型:</strong> ${details.type === 0 ? '目录' : '文件'}</p>
                        <p><strong>大小:</strong> ${formatSize(details.size)}</p>
                        <p><strong>权限:</strong> ${details.permissions}</p>
                        <p><strong>MIME类型:</strong> ${details.mimeTypeName || 'N/A'}</p>
                        <p><strong>路径:</strong> ${nodePath}</p>
                        <p><strong>创建时间:</strong> ${new Date(details.createTime).toLocaleString()}</p>
                        <p><strong>修改时间:</strong> ${new Date(details.modifyTime).toLocaleString()}</p>
                        </div>
                    `,
                    showCloseButton: true,
                });
            } catch (error) {
                toast('error', '获取属性失败');
            }
        }

        if (actionIcon.classList.contains('rename-icon')) {
            const nameSpan = tr.querySelector('.node-name span');
            const currentName = nameSpan.textContent;
            const input = document.createElement('input');
            input.type = 'text';
            input.value = currentName;
            input.className = 'rename-input';

            nameSpan.replaceWith(input);
            input.focus();

            const finishRename = async () => {
                const newName = input.value.trim();
                if (newName && newName !== currentName) {
                    try {
                        await fsApi.patch(`/nodes/${id}/rename`, null, { params: { newName } });
                        toast('success', '重命名成功');
                        fetchAndRenderNodes(currentParentId);
                    } catch(error) {
                        toast('error', error.response?.data?.message || '重命名失败');
                        input.replaceWith(nameSpan); // Revert on failure
                    }
                } else {
                    input.replaceWith(nameSpan);
                }
            };

            input.addEventListener('blur', finishRename);
            input.addEventListener('keydown', e => {
                if (e.key === 'Enter') input.blur();
                if (e.key === 'Escape') {
                    input.value = currentName;
                    input.blur();
                }
            });
        }

        if (actionIcon.classList.contains('delete-icon')) {
            const confirmation = await Swal.fire({
                title: `确定要删除 "${name}" 吗?`,
                text: type === 0 ? "警告：删除目录将永久删除其所有内容！此操作无法撤销。" : "此操作无法撤销。",
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#d33',
                cancelButtonColor: '#3085d6',
                confirmButtonText: '是的，删除它!',
                cancelButtonText: '取消'
            });

            if (confirmation.isConfirmed) {
                try {
                    await fsApi.delete(`/nodes/${id}`);
                    toast('success', `"${name}" 已删除`);
                    fetchAndRenderNodes(currentParentId);
                } catch(error) {
                    toast('error', '删除失败');
                }
            }
        }
    });

    async function navigateToPath(path) {
        if (!path) return;

        // 对于根路径，我们特殊处理一下
        if (path === '/') {
            await fetchAndRenderNodes();
            return;
        }

        showLoading(true);
        try {
            const response = await fsApi.get('/nodes/list-by-path', { params: { path } });
            const cdResult = response.data.data;

            // --- 更新前端状态 ---
            currentParentId = cdResult.dirId;
            currentPath = cdResult.dirPath;
            pathMap.set(currentParentId, currentPath);

            // --- 渲染UI ---
            renderNodes(cdResult.nodeDetailDTOS);
            updateBreadcrumb();
            toast('success', `已加载路径: ${currentPath}`);
        } catch (error) {
            console.error(`Failed to navigate to path "${path}":`, error);
            toast('error', error.response?.data?.message || '路径无效或无权访问');
        } finally {
            showLoading(false);
        }
    }

    // "cd" command
    pathInput.addEventListener('keydown', async (e) => {
        if (e.key === 'Enter') {
            const userInput = pathInput.value.trim();
            let targetPath = userInput;
            // TODO: 需要注意用户输入的部分与文件路径部分的关系。用户输入的路径可能不是文件路径

            // 处理相对路径：如果不是以'/'开头，则与当前路径拼接
            if (!userInput.startsWith('/')) {
                targetPath = buildNewPath(currentPath, userInput);
            }

            // 现在我们有了一个绝对路径，可以进行导航
            await navigateToPath(targetPath);

            // 导航后，使用 pushState 更新 URL，确保历史记录正确
            history.pushState({ id: currentParentId }, '', FS_PREFIX + targetPath);

            pathInput.value = ''; // 清空输入框
        }
    });

    // --- WebSocket Search ---
    const connectWebSocket = () => {
        const socket = new SockJS('/ws/search');
        stompClient = Stomp.over(socket);
        const headers = {
            'Authorization': 'Bearer ' + token
        };
        stompClient.connect(headers, (frame) => {
            console.log('Connected: ' + frame);

            stompClient.subscribe('/user/queue/search-results', (message) => {
                const payload = message.body;
                console.log(payload);
                if (payload === "SEARCH_COMPLETE") {
                    searchStatusMessage.textContent = '搜索完成。';
                    return;
                }
                if(payload.startsWith("SEARCH_ERROR:")) {
                    searchStatusMessage.textContent = `搜索出错: ${payload}`;
                    return;
                }

                const foundNode = JSON.parse(payload);
                const item = document.createElement('div');
                item.className = 'search-result-item';
                item.innerHTML = `
                    <i class="node-icon ${getFileIcon(foundNode.type, foundNode.mimeTypeName)}"></i>
                    <div>
                        <strong>${foundNode.name}</strong>
                        <div class="search-result-path">${foundNode.fullPath}</div>
                    </div>
                `; // Assumes backend DTO has fullPath
                searchResultsList.appendChild(item);
            });
        }, (error) => {
            console.error('STOMP connection error:', error);
            // 如果连接错误是认证失败导致的，后端可能会主动关闭连接
            // 这时可以检查错误类型，如果是认证问题，就跳转到登录页
            if (error.headers && error.headers.message && error.headers.message.includes('AccessDenied')) {
                // 自定义错误处理
                toast('warning', '权限被拒绝');
            } else {
                setTimeout(connectWebSocket, 5000); // 尝试重连
            }
        });
    };

    searchInput.addEventListener('input', () => {
        const query = searchInput.value.trim();
        if (query.length < 1) {
            searchResultsModal.style.display = 'none';
        }
    });

    searchInput.addEventListener('keydown', async (e) => {
        if (e.key === 'Enter') {
            const query = searchInput.value.trim();
            if (query.length < 1) {
                toast('warning', '请输入搜索的文件名');
                return;
            }

            searchResultsList.innerHTML = '';
            searchStatusMessage.textContent = '正在搜索...';
            searchResultsModal.style.display = 'block';

            if (stompClient && stompClient.connected) {
                stompClient.send("/app/search", {}, JSON.stringify({
                    startNodeId: null, // Search from root
                    namePattern: query
                }));
            }
        }
    });


    // --- INITIALIZATION ---
    const initialPath = window.location.pathname;
    if (initialPath) {
        if (initialPath === '/fs' || !initialPath.startsWith('/fs/')) {
            navigateToPath('/');
        } else if (initialPath.startsWith('/fs/')) {
            navigateToPath(initialPath.substring(3));
        }
    }
    fetchMimeTypes();
    connectWebSocket();

    window.addEventListener('popstate', (event) => {
        console.log("popState", event.state)
        if (event.state && event.state.id) {
            fetchAndRenderNodes(event.state.id);
        } else {
            fetchAndRenderNodes();
        }
    });
});