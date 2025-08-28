// homework.js
document.addEventListener('DOMContentLoaded', () => {
    // --- 状态管理 ---
    let currentView = 'list'; // 'list' | 'detail'
    let currentHomeworkId = null;
    let currentTab = 'all-homework';
    let roles = getRoles();
    let userName = getUserName();

    const isTeacher = roles.includes("ROLE_TEACHER");
    const isStudent = roles.includes("ROLE_STUDENT");

    // --- 配置 ---
    const API_BASE_URL = 'http://localhost:8080/api/v1';
    const HOMEWORK_API = axiosCreate(API_BASE_URL + '/homework');
    const SUBMISSION_API = axiosCreate(API_BASE_URL + '/submission');
    const UPLOAD_API = axiosCreate(API_BASE_URL + '/attach/upload');
    const DOWNLOAD_API = axiosCreate(API_BASE_URL + '/attach/download');

    const CHUNK_SIZE = 10 * 1024 * 1024; // 10MB 分块上传阈值

    // --- DOM 元素 ---
    const userRoleBadge = document.getElementById('user-role-badge');
    const userNameSpan = document.getElementById('user-name');
    const teacherView = document.getElementById('teacher-view');
    const studentView = document.getElementById('student-view');
    const homeworkDetailView = document.getElementById('homework-detail-view');
    const loadingSpinner = document.getElementById('loading-spinner');

    // 教师相关元素
    const publishHomeworkBtn = document.getElementById('publish-homework-btn');
    const teacherHomeworkList = document.getElementById('teacher-homework-list');
    const homeworkCountSpan = document.getElementById('homework-count');

    // 学生相关元素
    const studentHomeworkList = document.getElementById('student-homework-list');
    const studentSubmissionList = document.getElementById('student-submission-list');
    const tabBtns = document.querySelectorAll('.tab-btn');
    const tabPanes = document.querySelectorAll('.tab-pane');

    // 详情页面元素
    const backBtn = document.getElementById('back-btn');
    const detailTitle = document.getElementById('detail-title');
    const homeworkDetailContent = document.getElementById('homework-detail-content');
    const submissionsSection = document.getElementById('submissions-section');
    const submissionsList = document.getElementById('submissions-list');
    const studentSubmissionSection = document.getElementById('student-submission-section');
    const existingSubmission = document.getElementById('existing-submission');
    const mySubmissionContent = document.getElementById('my-submission-content');
    const submitFormSection = document.getElementById('submit-form-section');

    // 模态框相关元素
    const publishModal = document.getElementById('publish-modal');
    const publishForm = document.getElementById('publish-homework-form');
    const cancelPublishBtn = document.getElementById('cancel-publish');
    const closeModalBtns = document.querySelectorAll('.close-btn');

    // 文件上传相关元素
    const homeworkFileArea = document.getElementById('homework-file-area');
    const homeworkUploadZone = document.getElementById('homework-upload-zone');
    const homeworkFileInput = document.getElementById('homework-file-input');
    const homeworkFileList = document.getElementById('homework-file-list');

    const submissionFileArea = document.getElementById('submission-file-area');
    const submissionUploadZone = document.getElementById('submission-upload-zone');
    const submissionFileInput = document.getElementById('submission-file-input');
    const submissionFileList = document.getElementById('submission-file-list');

    const uploadProgress = document.getElementById('upload-progress');
    const progressBarFill = document.getElementById('progress-bar-fill');
    const uploadStatus = document.getElementById('upload-status');

    // 提交表单
    const submitHomeworkForm = document.getElementById('submit-homework-form');

    // --- 工具函数 ---
    const showLoading = (show) => {
        loadingSpinner.style.display = show ? 'flex' : 'none';
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

    const formatDate = (dateString) => {
        return new Date(dateString).toLocaleString('zh-CN');
    };

    const formatFileSize = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const getFileIcon = (mimeType) => {
        if (!mimeType) return 'fas fa-file';

        if (mimeType.startsWith('image/')) return 'fas fa-file-image';
        if (mimeType.startsWith('video/')) return 'fas fa-file-video';
        if (mimeType.startsWith('audio/')) return 'fas fa-file-audio';
        if (mimeType.includes('pdf')) return 'fas fa-file-pdf';
        if (mimeType.includes('word')) return 'fas fa-file-word';
        if (mimeType.includes('excel') || mimeType.includes('spreadsheet')) return 'fas fa-file-excel';
        if (mimeType.includes('powerpoint') || mimeType.includes('presentation')) return 'fas fa-file-powerpoint';
        if (mimeType.includes('zip') || mimeType.includes('rar')) return 'fas fa-file-archive';

        return 'fas fa-file';
    };

    // --- 初始化 ---
    const initializeApp = () => {
        // 设置用户信息
        if (isTeacher) {
            userRoleBadge.textContent = '教师';
            userRoleBadge.className = 'role-badge teacher';
            teacherView.style.display = 'block';
            loadTeacherHomeworks();
        } else if (isStudent) {
            userRoleBadge.textContent = '学生';
            userRoleBadge.className = 'role-badge student';
            studentView.style.display = 'block';
            loadAllHomeworks();
        }

        userNameSpan.textContent = userName;
    };

    // --- 视图切换 ---
    const showView = (viewName) => {
        teacherView.style.display = 'none';
        studentView.style.display = 'none';
        homeworkDetailView.style.display = 'none';

        currentView = viewName;

        if (viewName === 'list') {
            if (isTeacher) {
                teacherView.style.display = 'block';
            } else if (isStudent) {
                studentView.style.display = 'block';
            }
        } else if (viewName === 'detail') {
            homeworkDetailView.style.display = 'block';
        }
    };

    // --- 标签页切换 ---
    const switchTab = (tabName) => {
        tabBtns.forEach(btn => btn.classList.remove('active'));
        tabPanes.forEach(pane => pane.classList.remove('active'));

        document.querySelector(`[data-tab="${tabName}"]`).classList.add('active');
        document.getElementById(`${tabName}-tab`).classList.add('active');

        currentTab = tabName;

        if (tabName === 'all-homework') {
            loadAllHomeworks();
        } else if (tabName === 'my-submissions') {
            loadStudentSubmissions();
        }
    };

    // --- API 调用函数 ---
    const loadTeacherHomeworks = async () => {
        showLoading(true);
        try {
            const response = await HOMEWORK_API.get('/teacher/all');
            const homeworks = response.data;
            renderTeacherHomeworks(homeworks);
            homeworkCountSpan.textContent = `共 ${homeworks.length} 个作业`;
        } catch (error) {
            console.error('Failed to load teacher homeworks:', error);
            toast('error', '加载作业列表失败');
        } finally {
            showLoading(false);
        }
    };

    const loadAllHomeworks = async () => {
        showLoading(true);
        try {
            const response = await HOMEWORK_API.get('/all');
            const homeworks = response.data;
            renderStudentHomeworks(homeworks);
        } catch (error) {
            console.error('Failed to load all homeworks:', error);
            toast('error', '加载作业列表失败');
        } finally {
            showLoading(false);
        }
    };

    const loadStudentSubmissions = async () => {
        showLoading(true);
        try {
            const response = await SUBMISSION_API.get('/student/all');
            const submissions = response.data.data;
            renderStudentSubmissions(submissions);
        } catch (error) {
            console.error('Failed to load student submissions:', error);
            toast('error', '加载提交记录失败');
        } finally {
            showLoading(false);
        }
    };

    const loadHomeworkSubmissions = async (homeworkId) => {
        showLoading(true);
        try {
            const response = await SUBMISSION_API.get(`/${homeworkId}/submissions`);
            const submissions = response.data.data;
            renderHomeworkSubmissions(submissions);
        } catch (error) {
            console.error('Failed to load homework submissions:', error);
            toast('error', '加载提交列表失败');
        } finally {
            showLoading(false);
        }
    };

    const loadStudentSubmission = async (homeworkId) => {
        try {
            const response = await SUBMISSION_API.get(`/student/${homeworkId}/submission`);
            const submission = response.data.data;
            if (submission) {
                renderStudentSubmission(submission);
                existingSubmission.style.display = 'block';
                submitFormSection.style.display = 'none';
            } else {
                existingSubmission.style.display = 'none';
                submitFormSection.style.display = 'block';
            }
        } catch (error) {
            if (error.response?.status === 404) {
                // 没有提交记录
                existingSubmission.style.display = 'none';
                submitFormSection.style.display = 'block';
            } else {
                console.error('Failed to load student submission:', error);
                toast('error', '加载提交记录失败');
            }
        }
    };

    const deleteHomework = async (homeworkId) => {
        const result = await Swal.fire({
            title: '确认删除',
            text: '删除后无法恢复，确定要删除这个作业吗？',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonColor: '#ef4444',
            cancelButtonColor: '#6b7280',
            confirmButtonText: '删除',
            cancelButtonText: '取消'
        });

        if (result.isConfirmed) {
            try {
                await HOMEWORK_API.delete(`/${homeworkId}`);
                toast('success', '作业删除成功');
                loadTeacherHomeworks();
            } catch (error) {
                console.error('Failed to delete homework:', error);
                toast('error', '删除作业失败');
            }
        }
    };

    // --- 渲染函数 ---
    const renderTeacherHomeworks = (homeworks) => {
        if (homeworks.length === 0) {
            teacherHomeworkList.innerHTML = `
                <div class="empty-state">
                    <i class="fas fa-clipboard-list"></i>
                    <h3>还没有发布作业</h3>
                    <p>点击"发布作业"按钮来创建第一个作业</p>
                </div>
            `;
            return;
        }

        teacherHomeworkList.innerHTML = homeworks.map(homework => `
            <div class="homework-item" data-id="${homework.id}">
                <div class="homework-header-info">
                    <div>
                        <div class="homework-title">${homework.title}</div>
                        <div class="homework-meta">
                            <span><i class="fas fa-calendar"></i> ${formatDate(homework.createTime)}</span>
                        </div>
                    </div>
                    <div class="homework-actions">
                        <button class="action-btn view-submissions" data-id="${homework.id}">
                            <i class="fas fa-users"></i> 查看提交
                        </button>
                        <button class="action-btn delete" data-id="${homework.id}">
                            <i class="fas fa-trash"></i> 删除
                        </button>
                    </div>
                </div>
                ${homework.content ? `<div class="homework-content">${homework.content}</div>` : ''}
                ${renderAttachments(homework.attachments)}
            </div>
        `).join('');
    };

    const renderStudentHomeworks = (homeworks) => {
        if (homeworks.length === 0) {
            studentHomeworkList.innerHTML = `
                <div class="empty-state">
                    <i class="fas fa-clipboard-list"></i>
                    <h3>暂无作业</h3>
                    <p>老师还没有发布作业</p>
                </div>
            `;
            return;
        }

        studentHomeworkList.innerHTML = homeworks.map(homework => `
            <div class="homework-item" data-id="${homework.id}">
                <div class="homework-header-info">
                    <div>
                        <div class="homework-title">${homework.title}</div>
                        <div class="homework-meta">
                            <span><i class="fas fa-calendar"></i> ${formatDate(homework.createTime)}</span>
                        </div>
                    </div>
                    <div class="homework-actions">
                        <button class="action-btn view-homework" data-id="${homework.id}">
                            <i class="fas fa-eye"></i> 查看详情
                        </button>
                    </div>
                </div>
                ${homework.content ? `<div class="homework-content">${homework.content}</div>` : ''}
                ${renderAttachments(homework.attachments)}
            </div>
        `).join('');
    };

    const renderStudentSubmissions = (submissions) => {
        if (submissions.length === 0) {
            studentSubmissionList.innerHTML = `
                <div class="empty-state">
                    <i class="fas fa-file-alt"></i>
                    <h3>暂无提交记录</h3>
                    <p>您还没有提交任何作业</p>
                </div>
            `;
            return;
        }

        studentSubmissionList.innerHTML = submissions.map(submission => `
            <div class="submission-item">
                <div class="homework-header-info">
                    <div>
                        <div class="homework-title">${submission.homework.title}</div>
                        <div class="homework-meta">
                            <span><i class="fas fa-calendar"></i> 提交时间: ${formatDate(submission.createTime)}</span>
                        </div>
                    </div>
                </div>
                ${submission.content ? `<div class="homework-content">${submission.content}</div>` : ''}
                ${renderAttachments(submission.attachments)}
            </div>
        `).join('');
    };

    const renderHomeworkSubmissions = (submissions) => {
        if (submissions.length === 0) {
            submissionsList.innerHTML = `
                <div class="empty-state">
                    <i class="fas fa-user-times"></i>
                    <h3>暂无学生提交</h3>
                    <p>还没有学生提交这个作业</p>
                </div>
            `;
            return;
        }

        submissionsList.innerHTML = submissions.map(submission => `
            <div class="submission-item">
                <div class="homework-header-info">
                    <div>
                        <div class="homework-title">学生ID: ${submission.studentId}</div>
                        <div class="homework-meta">
                            <span><i class="fas fa-calendar"></i> 提交时间: ${formatDate(submission.createTime)}</span>
                        </div>
                    </div>
                </div>
                ${submission.content ? `<div class="homework-content">${submission.content}</div>` : ''}
                ${renderAttachments(submission.attachments)}
            </div>
        `).join('');
    };

    const renderStudentSubmission = (submission) => {
        mySubmissionContent.innerHTML = `
            <div class="submission-item">
                <div class="homework-meta">
                    <span><i class="fas fa-calendar"></i> 提交时间: ${formatDate(submission.createTime)}</span>
                </div>
                ${submission.content ? `<div class="homework-content">${submission.content}</div>` : ''}
                ${renderAttachments(submission.attachments)}
            </div>
        `;
    };

    const renderAttachments = (attachments) => {
        if (!attachments || attachments.length === 0) return '';

        return `
            <div class="attachments">
                <div class="attachments-title">
                    <i class="fas fa-paperclip"></i> 附件 (${attachments.length})
                </div>
                <div class="attachment-list">
                    ${attachments.map(attachment => `
                        <a href="#" class="attachment-item" data-path="${attachment.filePath}">
                            <i class="${getFileIcon(attachment.mimeTypeName)}"></i>
                            <span>${attachment.fileName}</span>
                            <small>(${formatFileSize(attachment.fileSize)})</small>
                        </a>
                    `).join('')}
                </div>
            </div>
        `;
    };

    // --- 文件上传处理 ---
    let selectedFiles = [];
    let uploadedFiles = [];

    const setupFileUpload = (uploadZone, fileInput, fileList, filesArray) => {
        // 点击上传区域
        uploadZone.addEventListener('click', () => {
            fileInput.click();
        });

        // 文件选择
        fileInput.addEventListener('change', (e) => {
            handleFileSelection(e.target.files, filesArray, fileList);
        });

        // 拖拽上传
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
            uploadZone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
            });
        });

        ['dragenter', 'dragover'].forEach(eventName => {
            uploadZone.addEventListener(eventName, () => {
                uploadZone.parentElement.classList.add('dragover');
            });
        });

        ['dragleave', 'drop'].forEach(eventName => {
            uploadZone.addEventListener(eventName, () => {
                uploadZone.parentElement.classList.remove('dragover');
            });
        });

        uploadZone.addEventListener('drop', (e) => {
            handleFileSelection(e.dataTransfer.files, filesArray, fileList);
        });
    };

    const handleFileSelection = (files, filesArray, fileList) => {
        Array.from(files).forEach(file => {
            filesArray.push(file);
        });
        renderFileList(filesArray, fileList);
    };

    const renderFileList = (filesArray, fileList) => {
        fileList.innerHTML = filesArray.map((file, index) => `
            <div class="file-item">
                <div class="file-info">
                    <i class="file-icon ${getFileIcon(file.type)}"></i>
                    <span class="file-name">${file.name}</span>
                    <span class="file-size">${formatFileSize(file.size)}</span>
                </div>
                <button type="button" class="remove-file" data-index="${index}">
                    <i class="fas fa-times"></i>
                </button>
            </div>
        `).join('');
    };

    const removeFile = (index, filesArray, fileList) => {
        filesArray.splice(index, 1);
        renderFileList(filesArray, fileList);
    };

    // 设置文件上传
    setupFileUpload(homeworkUploadZone, homeworkFileInput, homeworkFileList, selectedFiles);
    setupFileUpload(submissionUploadZone, submissionFileInput, submissionFileList, uploadedFiles);

    // 文件移除事件
    document.addEventListener('click', (e) => {
        if (e.target.closest('.remove-file')) {
            const index = parseInt(e.target.closest('.remove-file').dataset.index);
            const fileList = e.target.closest('.file-list');

            if (fileList.id === 'homework-file-list') {
                removeFile(index, selectedFiles, homeworkFileList);
            } else if (fileList.id === 'submission-file-list') {
                removeFile(index, uploadedFiles, submissionFileList);
            }
        }
    });

    // --- 文件上传逻辑 ---
    const uploadFiles = async (files) => {
        if (files.length === 0) return [];

        const smallFiles = files.filter(file => file.size <= CHUNK_SIZE);
        const largeFiles = files.filter(file => file.size > CHUNK_SIZE);

        const uploadIds = [];

        // 处理大文件分块上传
        if (largeFiles.length > 0) {
            try {
                const initResponse = await UPLOAD_API.post('/batch-init', {
                    files: largeFiles.map(file => ({
                        fileName: file.name,
                        fileSize: file.size
                    }))
                });

                const initResults = initResponse.data.data;

                // 并发上传大文件
                await Promise.all(largeFiles.map(async (file, index) => {
                    const uploadId = initResults[index].uploadId;
                    await uploadFileInChunks(file, uploadId);
                    uploadIds.push(uploadId);
                }));
            } catch (error) {
                console.error('Large file upload failed:', error);
                throw error;
            }
        }

        return { smallFiles, uploadIds };
    };

    const uploadFileInChunks = async (file, uploadId) => {
        const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

        for (let i = 0; i < totalChunks; i++) {
            const start = i * CHUNK_SIZE;
            const end = Math.min(start + CHUNK_SIZE, file.size);
            const chunk = file.slice(start, end);

            const formData = new FormData();
            formData.append('uploadId', uploadId);
            formData.append('chunkIndex', i);
            formData.append('totalChunks', totalChunks);
            formData.append('chunk', chunk);

            await UPLOAD_API.post('/chunk', formData, {
                onUploadProgress: (progressEvent) => {
                    const chunkProgress = (progressEvent.loaded / progressEvent.total) * 100;
                    const totalProgress = ((i + chunkProgress / 100) / totalChunks) * 100;
                    updateUploadProgress(totalProgress);
                }
            });
        }
    };

    const updateUploadProgress = (progress) => {
        progressBarFill.style.width = `${progress}%`;
        uploadStatus.textContent = `上传进度: ${Math.round(progress)}%`;
    };

    // --- 下载处理 ---
    const downloadFile = async (filePath, fileName) => {
        try {
            const response = await DOWNLOAD_API.get('/get/downloadId', {
                params: { path: filePath }
            });

            const token = response.data.data;
            const downloadUrl = `${API_BASE_URL}/attach/download/download?path=${encodeURIComponent(filePath)}&token=${token}`;

            const link = document.createElement('a');
            link.href = downloadUrl;
            link.download = fileName;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        } catch (error) {
            console.error('Download failed:', error);
            toast('error', '下载失败');
        }
    };

    // --- 事件监听器 ---

    // 发布作业按钮
    if (publishHomeworkBtn) {
        publishHomeworkBtn.addEventListener('click', () => {
            publishModal.style.display = 'block';
        });
    }

    // 关闭模态框
    closeModalBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            btn.closest('.modal').style.display = 'none';
        });
    });

    if (cancelPublishBtn) {
        cancelPublishBtn.addEventListener('click', () => {
            publishModal.style.display = 'none';
        });
    }

    // 点击模态框外部关闭
    window.addEventListener('click', (e) => {
        if (e.target.classList.contains('modal')) {
            e.target.style.display = 'none';
        }
    });

    // 标签页切换
    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabName = btn.dataset.tab;
            switchTab(tabName);
        });
    });

    // 返回按钮
    backBtn.addEventListener('click', () => {
        showView('list');
    });

    // 发布作业表单提交
    publishForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        const formData = new FormData();
        const title = document.getElementById('homework-title').value;
        const content = document.getElementById('homework-content').value;

        try {
            uploadProgress.style.display = 'block';
            updateUploadProgress(0);

            // 处理文件上传
            const { smallFiles, uploadIds } = await uploadFiles(selectedFiles);

            // 构建DTO
            const dto = {
                title,
                content,
                attachmentUploadIds: uploadIds
            };

            formData.append('dto', new Blob([JSON.stringify(dto)], { type: 'application/json' }));

            // 添加小文件
            smallFiles.forEach(file => {
                formData.append('files', file);
            });

            updateUploadProgress(80);

            await HOMEWORK_API.post('/publish', formData, {
                onUploadProgress: (progressEvent) => {
                    const progress = 80 + (progressEvent.loaded / progressEvent.total) * 20;
                    updateUploadProgress(progress);
                }
            });

            updateUploadProgress(100);
            toast('success', '作业发布成功');

            // 重置表单
            publishForm.reset();
            selectedFiles.length = 0;
            renderFileList(selectedFiles, homeworkFileList);
            publishModal.style.display = 'none';
            uploadProgress.style.display = 'none';

            // 刷新列表
            loadTeacherHomeworks();

        } catch (error) {
            console.error('Publish homework failed:', error);
            toast('error', '发布作业失败');
            uploadProgress.style.display = 'none';
        }
    });

    // 提交作业表单
    submitHomeworkForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        const content = document.getElementById('submission-content').value;

        try {
            uploadProgress.style.display = 'block';
            updateUploadProgress(0);

            // 处理文件上传
            const { smallFiles, uploadIds } = await uploadFiles(uploadedFiles);

            const formData = new FormData();
            const dto = {
                homeworkId: currentHomeworkId,
                content,
                attachmentUploadIds: uploadIds
            };

            formData.append('dto', new Blob([JSON.stringify(dto)], { type: 'application/json' }));

            // 添加小文件
            smallFiles.forEach(file => {
                formData.append('files', file);
            });

            updateUploadProgress(80);

            await SUBMISSION_API.post('/submit', formData, {
                onUploadProgress: (progressEvent) => {
                    const progress = 80 + (progressEvent.loaded / progressEvent.total) * 20;
                    updateUploadProgress(progress);
                }
            });

            updateUploadProgress(100);
            toast('success', '作业提交成功');

            // 重置表单
            submitHomeworkForm.reset();
            uploadedFiles.length = 0;
            renderFileList(uploadedFiles, submissionFileList);
            uploadProgress.style.display = 'none';

            // 重新加载提交状态
            loadStudentSubmission(currentHomeworkId);

        } catch (error) {
            console.error('Submit homework failed:', error);
            toast('error', '提交作业失败');
            uploadProgress.style.display = 'none';
        }
    });

    // 作业列表点击事件
    document.addEventListener('click', async (e) => {
        const target = e.target;

        // 查看提交按钮（教师）
        if (target.closest('.view-submissions')) {
            const homeworkId = target.closest('.view-submissions').dataset.id;
            const homeworkItem = target.closest('.homework-item');
            const title = homeworkItem.querySelector('.homework-title').textContent;

            currentHomeworkId = homeworkId;
            detailTitle.textContent = title;

            // 显示作业信息
            const content = homeworkItem.querySelector('.homework-content')?.textContent || '无内容';
            const attachments = homeworkItem.querySelector('.attachments')?.outerHTML || '';
            homeworkDetailContent.innerHTML = `
                <p>${content}</p>
                ${attachments}
            `;

            // 显示提交列表
            submissionsSection.style.display = 'block';
            studentSubmissionSection.style.display = 'none';

            showView('detail');
            await loadHomeworkSubmissions(homeworkId);
        }

        // 查看详情按钮（学生）
        if (target.closest('.view-homework')) {
            const homeworkId = target.closest('.view-homework').dataset.id;
            const homeworkItem = target.closest('.homework-item');
            const title = homeworkItem.querySelector('.homework-title').textContent;

            currentHomeworkId = homeworkId;
            detailTitle.textContent = title;

            // 显示作业信息
            const content = homeworkItem.querySelector('.homework-content')?.textContent || '无内容';
            const attachments = homeworkItem.querySelector('.attachments')?.outerHTML || '';
            homeworkDetailContent.innerHTML = `
                <p>${content}</p>
                ${attachments}
            `;

            // 显示学生提交区域
            submissionsSection.style.display = 'none';
            studentSubmissionSection.style.display = 'block';

            showView('detail');
            await loadStudentSubmission(homeworkId);
        }

        // 删除作业按钮
        if (target.closest('.delete')) {
            const homeworkId = target.closest('.delete').dataset.id;
            await deleteHomework(homeworkId);
        }

        // 附件下载
        if (target.closest('.attachment-item')) {
            e.preventDefault();
            const attachmentItem = target.closest('.attachment-item');
            const filePath = attachmentItem.dataset.path;
            const fileName = attachmentItem.querySelector('span').textContent;
            await downloadFile(filePath, fileName);
        }
    });

    // 初始化应用
    initializeApp();
});
