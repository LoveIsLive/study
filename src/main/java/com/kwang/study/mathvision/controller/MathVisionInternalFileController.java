package com.kwang.study.mathvision.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.mathvision.transfer.MathVisionTransferServer;
import com.kwang.study.mathvision.engine.MathVisionLeaseLostException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;

@RestController
@RequestMapping("/internal/mathvision")
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "false", matchIfMissing = true)
public class MathVisionInternalFileController {
    private final MathVisionTransferServer server;
    private final FileStorageService files;
    private final ObjectMapper json;
    public MathVisionInternalFileController(MathVisionTransferServer server, FileStorageService files, ObjectMapper json) {
        this.server = server; this.files = files; this.json = json;
    }
    @PostMapping("/artifacts/init") public ObjectNode init(@RequestBody ObjectNode request) throws Exception { return server.init(request); }
    @GetMapping("/artifacts/{id}") public ObjectNode status(@PathVariable String id) throws Exception { return server.status(id); }
    @PutMapping("/artifacts/{id}/chunks/{index}") public void chunk(@PathVariable String id, @PathVariable int index, HttpServletRequest req) throws Exception {
        server.chunk(id, index, req.getInputStream());
    }
    @PostMapping("/artifacts/{id}/complete") public ObjectNode complete(@PathVariable String id) throws Exception { return server.complete(id); }
    @GetMapping("/tasks/{taskId}/assets/{index}")
    public void asset(@PathVariable long taskId, @PathVariable int index, @RequestParam String token, HttpServletResponse response) throws Exception {
        ObjectNode request = json.createObjectNode().put("taskId", taskId).put("token", token);
        // Version is obtained from the task, never from an arbitrary file path supplied by the worker.
        var task = taskMapper.findById(taskId);
        if (task == null) throw new MathVisionLeaseLostException();
        request.put("version", task.getCurrentVersion()); server.validate(request);
        var assets = json.readTree(task.getInputAssetsJson());
        if (index < 0 || index >= assets.size()) throw new IllegalArgumentException("Unknown asset");
        var file = files.getFileObject(assets.get(index).path("filePath").asText());
        response.setContentType("application/octet-stream");
        if (file.getSize() != null) response.setContentLengthLong(file.getSize());
        try (var input = file.getContent()) { input.transferTo(response.getOutputStream()); }
    }
    @org.springframework.beans.factory.annotation.Autowired
    private com.kwang.study.mathvision.mapper.MathVisionTaskMapper taskMapper;

    @ExceptionHandler(MathVisionLeaseLostException.class)
    public ResponseEntity<String> stale() { return ResponseEntity.status(HttpStatus.CONFLICT).body("Execution lease expired"); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> invalid() { return ResponseEntity.badRequest().body("Invalid transfer request"); }

    @ExceptionHandler(java.nio.file.NoSuchFileException.class)
    public ResponseEntity<String> missing() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Upload session not found"); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unavailable() { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("File transfer temporarily unavailable"); }
}
