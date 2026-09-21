package com.xhs.service;

import com.xhs.entity.Note;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Day8：Elasticsearch 搜索服务
 *
 * 不引入 ES 官方客户端，直接用 RestTemplate 调 ES 的 REST API（9200 端口），
 * 方便学生用 curl / Postman 对照理解每一条请求。
 *
 * 索引：xhs_notes
 *   title / content / tags 三个 text 字段（默认 standard 分词）
 */
@Slf4j
@Service
public class EsService {

    private static final String ES_BASE = "http://localhost:9200";
    private static final String INDEX = "xhs_notes";

    @Autowired
    private RestTemplate restTemplate;

    /** 启动时调用：索引不存在则创建 */
    public void ensureIndex() {
        try {
            restTemplate.getForEntity(ES_BASE + "/" + INDEX, String.class);
            // 200 说明索引已存在
        } catch (HttpClientErrorException.NotFound e) {
            String mapping = "{\"mappings\":{\"properties\":{" +
                    "\"title\":{\"type\":\"text\"}," +
                    "\"content\":{\"type\":\"text\"}," +
                    "\"tags\":{\"type\":\"text\"}}}}";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(ES_BASE + "/" + INDEX, HttpMethod.PUT,
                    new HttpEntity<>(mapping, headers), String.class);
            log.info("ES 索引 {} 创建成功", INDEX);
        } catch (Exception e) {
            log.warn("ES 连接失败，搜索将回退到 MySQL：{}", e.getMessage());
        }
    }

    /** 写入/更新一篇笔记到索引 */
    public void indexNote(Note note) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("title", note.getTitle());
        doc.put("content", note.getContent());
        doc.put("tags", note.getTags());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(ES_BASE + "/" + INDEX + "/_doc/" + note.getId(),
                HttpMethod.PUT, new HttpEntity<>(doc, headers), String.class);
    }

    /** 关键词搜索，返回命中的笔记ID列表（按相关性排序） */
    @SuppressWarnings("unchecked")
    public List<Long> searchIds(String keyword, int page, int size) {
        Map<String, Object> match = new HashMap<>();
        match.put("query", keyword);
        match.put("fields", new String[]{"title", "content", "tags"});
        Map<String, Object> query = new HashMap<>();
        query.put("multi_match", match);

        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("from", (page - 1) * size);
        body.put("size", size);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                ES_BASE + "/" + INDEX + "/_search", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        Map<String, Object> respBody = response.getBody();
        if (respBody == null) {
            return Collections.emptyList();
        }
        Map<String, Object> hits = (Map<String, Object>) respBody.get("hits");
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hits.get("hits");
        List<Long> ids = new ArrayList<>(hitList.size());
        for (Map<String, Object> hit : hitList) {
            ids.add(Long.parseLong(hit.get("_id").toString()));
        }
        return ids;
    }
}
