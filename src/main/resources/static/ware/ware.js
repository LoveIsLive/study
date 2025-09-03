document.addEventListener('DOMContentLoaded', () => {
    // --- STATE ---
    let currentPath = '/';
    let stompClient = null;
    let roles = getRoles();
    const isTeacher = roles.includes("ROLE_TEACHER");

    // --- CONFIG ---
    const WARE_PREFIX = "/ware/home";
    const WARE_SOCKET_ENDPOINT = "/app/ware/search";
    const CHUNK_SIZE = common_config.LARGE_FILE_THRESHOLD; // 分块上传的阈值

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
    const wareAPI = axiosCreate(WARE_PREFIX);

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

    const renderNodes = (nodes) => {
        fileListBody.innerHTML = '';
        if (nodes.length === 0) {
            fileListBody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding: 2rem;">此目录为空</td></tr>';
            return;
        }

        nodes.forEach(node => {
            const tr = document.createElement('tr');
            tr.dataset.name = node.name;
            tr.dataset.type = node.type;
            tr.dataset.mime = node.mimeTypeName; // Assuming backend provides this
            const destPath = buildNewPath(currentPath, node.name);

            const teacherActions = isTeacher ? `
            <i class="fas fa-edit action-icon rename-icon" title="重命名"></i>
            <i class="fas fa-trash-alt action-icon delete-icon" title="删除"></i>
        ` : '';

            const actions = `
                <div class="actions-container">
                    <i class="fas fa-copy action-icon copy-path-icon" title="复制路径"></i>
                    ${node.type === 1 && isPreviewable(node.mimeTypeName) ? '<i class="fas fa-eye action-icon preview-icon" title="预览"></i>' : ''}
                    ${node.type === 1 ? `<i class="fas fa-download action-icon download-icon" title="下载"></i>` : ''}
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
                    renderDirAndUpdateUrl(buildNewPath(currentPath, node.name));
                });
            }

            fileListBody.appendChild(tr);
        });
    };

    const updateBreadcrumb = () => {
        breadcrumb.innerHTML = '';
        const parts = currentPath.split('/').filter(p => p);

        const rootLink = document.createElement('a');
        rootLink.href = WARE_PREFIX;
        rootLink.textContent = '根目录';
        rootLink.addEventListener('click', (e) => {
            e.preventDefault();
            renderDirAndUpdateUrl('/')
        });
        breadcrumb.appendChild(rootLink);

        parts.forEach((part, index) => {
            const separator = document.createElement('span');
            separator.textContent = '>';
            breadcrumb.appendChild(separator);

            const fullPathForPart = '/' + parts.slice(0, index + 1).join('/');
            const partLink = document.createElement('a');
            partLink.href = fullPathForPart;
            partLink.textContent = part;

            partLink.addEventListener('click', (e) => {
                e.preventDefault();
                renderDirAndUpdateUrl(fullPathForPart);
            });
            breadcrumb.appendChild(partLink);
        });
    };

    // --- CORE LOGIC ---
    const renderDirAndUpdateUrl = async (path = '/') => {
        await fetchAndRenderNodes(path);
        history.pushState({ path: path }, '', WARE_PREFIX + path);
    }

    const fetchAndRenderNodes = async (path = '/') => {
        showLoading(true);
        try {
            const response = await wareAPI.get('/get/dir', {params: { path } });
            currentPath = path;
            renderNodes(response.data.data.fileObjectDescs);
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
            const response = await wareAPI.get('/get/mime');
            const mimeTypes = response.data.data.mimeTypeNames;
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
            let destPath = buildNewPath(currentPath, dirName);
            await wareAPI.post('/create/directories', null, { params: {
                path: destPath
            } });
            toast('success', '目录创建成功');
            newItemModal.style.display = 'none';
            newDirNameInput.value = '';
            fetchAndRenderNodes(currentPath);
        } catch (error) {
            console.error('Failed to create directory:', error);
            toast('error', error.response?.data?.message || '创建失败');
        }
    });

    // File Upload Logic
    function getMimeType(fileName) {
        const extension = fileName.split('.').pop().toLowerCase();
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
                // 先根据扩展名，如果识别不到，在根据file.type(如果在mimeTypesValues中的话)
                let mimeType = getMimeType(fileToUpload.name);
                if (!mimeType && fileToUpload.type && mimeTypesValues.includes(fileToUpload.type)) {
                    mimeType = fileToUpload.type;
                }
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
        let destPath = buildNewPath(currentPath, fileNameInput.value.trim());
        formData.append('path', destPath);
        formData.append('file', file);
        formData.append('mimeTypeName', mimeTypeSelect.value);

        try {
            uploadStatus.textContent = '正在上传...';
            progressBarInner.style.width = '50%'; // Indicate progress

            await wareAPI.post('/create/files', formData, {
                onUploadProgress: progressEvent => {
                    const percentCompleted = Math.round((progressEvent.loaded * 100) / progressEvent.total);
                    progressBarInner.style.width = percentCompleted + '%';
                }
            });

            toast('success', '文件上传成功');
            resetUploadForm();
            fetchAndRenderNodes(currentPath);
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
        let uploadId;
        let destPath = buildNewPath(currentPath, file.name);
        try {
            const initResponse = await wareAPI.post('/chunk/init', {
                    path: destPath,
                    mimeTypeName: mimeTypeSelect.value
                });
            uploadId = initResponse.data.data.uploadId;
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
                formData.append('uploadId', uploadId);
                formData.append('chunkIndex', i);
                formData.append('totalChunks', totalChunks);
                formData.append('chunk', chunk);

                return wareAPI.post('/chunk/upload', formData, {
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
                fetchAndRenderNodes(currentPath);
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
        const name = tr.dataset.name;
        const type = parseInt(tr.dataset.type, 10);
        let destPath = buildNewPath(currentPath, name);

        if (actionIcon.classList.contains('copy-path-icon')) {
            navigator.clipboard.writeText(destPath).then(() => {
                toast('success', '路径已复制');
            }).catch(err => {
                console.error('Failed to copy path:', err);
                toast('error', '复制失败');
            });
        }

        if (actionIcon.classList.contains('preview-icon')) {
            wareAPI.get('/get/downloadId', {
                params: {
                    path: destPath
                }
            }).then(response => {
                const token = response.data.data;
                window.open(`${wareAPI.defaults.baseURL}/download?mode=inline&path=${destPath}&token=${token}`, '_blank');
            }).catch(error => {
                console.log(error)
                toast('error', '预览失败');
            });
        }

        if (actionIcon.classList.contains('download-icon')) {
            wareAPI.get('/get/downloadId', {
                params: {
                    path: destPath
                }
            }).then(response => {
                const token = response.data.data;
                // 创建一个隐藏的 <a> 标签并模拟点击来触发下载
                const tempLink = document.createElement('a');
                tempLink.style.display = 'none';
                tempLink.href = `${wareAPI.defaults.baseURL}/download?path=${destPath}&token=${token}`;
                tempLink.setAttribute('download', name);
                document.body.appendChild(tempLink);
                tempLink.click();
            }).catch(error => {
                console.log(error);
                toast("error", "获取文件失败");
            });
        }

        if (actionIcon.classList.contains('details-icon')) {
            try {
                const response = await wareAPI.get(`/get/node`, {params: {path: destPath} });
                const details = response.data.data;
                Swal.fire({
                    title: `<strong>属性: ${details.name}</strong>`,
                    html: `
                        <div style="text-align: left; margin-left: 2rem;">
                        <p><strong>类型:</strong> ${details.type === 0 ? '目录' : '文件'}</p>
                        <p><strong>大小:</strong> ${formatSize(details.size)}</p>
                        <p><strong>MIME类型:</strong> ${details.mimeTypeName || 'N/A'}</p>
                        <p><strong>路径:</strong> ${destPath}</p>
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
                        if (type === 0) {
                            await wareAPI.post('/update/dir', null, { params: { path: destPath, newName } });
                        } else {
                            await wareAPI.post('/update/file', { path: destPath, newName } );
                        }
                        toast('success', '重命名成功');
                        fetchAndRenderNodes(currentPath);
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
                    let deleteURL = type === 0 ? '/delete/dir' : '/delete/file';
                    await wareAPI.delete(deleteURL, { params: {path: destPath} });
                    toast('success', `"${name}" 已删除`);
                    fetchAndRenderNodes(currentPath);
                } catch(error) {
                    toast('error', '删除失败');
                }
            }
        }
    });

    async function navigateToPath(path) {
        if (!path) return;

        // 对于根路径，特殊处理一下
        if (path === '/') {
            await fetchAndRenderNodes();
            return;
        }

        showLoading(true);
        try {
            const response = await wareAPI.get('/get/dir', { params: { path } });
            const cdResult = response.data.data;

            // --- 更新前端状态 ---
            currentPath = path;

            // --- 渲染UI ---
            renderNodes(cdResult.fileObjectDescs);
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

            // 处理相对路径：如果不是以'/'开头，则与当前路径拼接
            if (!userInput.startsWith('/')) {
                targetPath = buildNewPath(currentPath, userInput);
            }

            // 现在我们有了一个绝对路径，可以进行导航
            await navigateToPath(targetPath);

            // 导航后，使用 pushState 更新 URL，确保历史记录正确
            history.pushState({ path: targetPath }, '', WARE_PREFIX + targetPath);

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
                stompClient.send(`${WARE_SOCKET_ENDPOINT}`, {}, JSON.stringify({
                    path: '/', // Search from root
                    namePattern: query
                }));
            }
        }
    });


    // --- INITIALIZATION ---
    const initialPath = window.location.pathname;
    if (initialPath) {
        if (initialPath === WARE_PREFIX) {
            navigateToPath('/');
        } else if (initialPath.startsWith(WARE_PREFIX + '/')) {
            navigateToPath(initialPath.substring(WARE_PREFIX.length));
        } else {
            window.location = WARE_PREFIX;
        }
    }
    fetchMimeTypes();
    connectWebSocket();

    window.addEventListener('popstate', (event) => {
        console.log("popState", event.state)
        if (event.state && event.state.path) {
            fetchAndRenderNodes(event.state.path);
        } else {
            fetchAndRenderNodes();
        }
    });
});