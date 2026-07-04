package com.costlink.ocr.controller;

import com.costlink.common.dto.Result;
import com.costlink.common.feign.OcrClient;
import com.costlink.ocr.service.OcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/internal/ocr")
@RequiredArgsConstructor
public class OcrInternalController {

    private final OcrService ocrService;

    /** 同步识别 — 等待百度返回结果 */
    @PostMapping("/recognize")
    public Result<OcrClient.OcrResultDTO> recognize(@RequestBody Map<String, Object> body) {
        Long attachmentId = Long.valueOf(body.get("attachmentId").toString());
        String fileHash = (String) body.get("fileHash");
        String fileUrl = (String) body.get("fileUrl");

        // 从 Base64 数据 URL 中提取图片字节
        byte[] imageBytes = decodeBase64Image(fileUrl);
        if (imageBytes == null) {
            return Result.fail(10401, "无法解码图片数据");
        }

        OcrClient.OcrResultDTO result = ocrService.recognize(attachmentId, fileHash, imageBytes);
        if ("SUCCESS".equals(result.getStatus())) {
            return Result.ok(result);
        } else {
            return Result.fail(10401, result.getErrorMessage());
        }
    }

    /** 异步识别 — 立即返回，结果通过 MQ 回写 */
    @PostMapping("/recognize-async")
    public Result<Void> recognizeAsync(@RequestBody Map<String, Object> body) {
        Long attachmentId = Long.valueOf(body.get("attachmentId").toString());
        Long reimbursementId = body.get("reimbursementId") != null
                ? Long.valueOf(body.get("reimbursementId").toString()) : null;
        String fileHash = (String) body.get("fileHash");
        String fileUrl = (String) body.get("fileUrl");

        byte[] imageBytes = decodeBase64Image(fileUrl);
        if (imageBytes == null) {
            return Result.fail(10401, "无法解码图片数据");
        }

        ocrService.recognizeAsync(attachmentId, reimbursementId, fileHash, imageBytes);
        return Result.ok();
    }

    /** 查询识别结果 */
    @PostMapping("/result")
    public Result<OcrClient.OcrResultDTO> getResult(@RequestBody Map<String, Object> body) {
        Long attachmentId = Long.valueOf(body.get("attachmentId").toString());
        String fileHash = (String) body.get("fileHash");
        OcrClient.OcrResultDTO result = ocrService.getResult(attachmentId, fileHash);
        if (result == null) {
            return Result.fail(10401, "识别结果不存在");
        }
        return Result.ok(result);
    }

    private byte[] decodeBase64Image(String fileUrl) {
        if (fileUrl == null) return null;
        try {
            // 支持 data:image/xxx;base64,xxxx 格式
            if (fileUrl.startsWith("data:")) {
                String base64 = fileUrl.substring(fileUrl.indexOf(",") + 1);
                return Base64.getDecoder().decode(base64);
            }
            return Base64.getDecoder().decode(fileUrl);
        } catch (Exception e) {
            return null;
        }
    }
}
