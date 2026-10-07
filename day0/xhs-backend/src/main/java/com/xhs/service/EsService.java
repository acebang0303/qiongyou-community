package com.xhs.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhs.entity.Note;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String INDEX = "xhs_notes";

    /** ES 地址可配：测试时指向不可达端口即可完全隔离，且顺带验证降级路径 */
    @Value("${xhs.es.base-url:http://localhost:9200}")
    private String esBase;

    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    /** 启动时调用：索引不存在则创建（★ P1-14：改用 IK 分词，建索引用 ik_max_word、搜索用 ik_smart） */
    public void ensureIndex() {
        try {
            restTemplate.getForEntity(esBase + "/" + INDEX, String.class);
            // 200 说明索引已存在
        } catch (HttpClientErrorException.NotFound e) {
            String field = "{\"type\":\"text\",\"analyzer\":\"ik_max_word\",\"search_analyzer\":\"ik_smart\"}";
            // note_id：仅供排序/游标使用的数值字段（ES 8 默认禁止对 _id 排序）
            String mapping = "{\"mappings\":{\"properties\":{" +
                    "\"note_id\":{\"type\":\"long\"}," +
                    "\"title\":" + field + "," +
                    "\"content\":" + field + "," +
                    "\"tags\":" + field + "}}}";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(esBase + "/" + INDEX, HttpMethod.PUT,
                    new HttpEntity<>(mapping, headers), String.class);
            log.info("ES 索引 {} 创建成功（IK 分词）", INDEX);
        } catch (Exception e) {
            log.warn("ES 连接失败，搜索将回退到 MySQL：{}", e.getMessage());
        }
    }

    /** ★ P1-15：索引内文档数（回填前判断是否为空） */
    public long count() throws Exception {
        ResponseEntity<Map> resp = restTemplate.getForEntity(esBase + "/" + INDEX + "/_count", Map.class);
        Map body = resp.getBody();
        if (body == null || body.get("count") == null) {
            return 0;
        }
        return ((Number) body.get("count")).longValue();
    }

    /** ★ P1-15：用 _bulk 批量写入（索引重建后程序化回填用） */
    public void bulkIndex(List<Note> notes) throws Exception {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        for (Note note : notes) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("_index", INDEX);
            meta.put("_id", String.valueOf(note.getId()));
            Map<String, Object> action = new HashMap<>();
            action.put("index", meta);
            ndjson.append(objectMapper.writeValueAsString(action)).append('\n');

            Map<String, Object> doc = new HashMap<>();
            doc.put("note_id", note.getId());
            doc.put("title", note.getTitle());
            doc.put("content", note.getContent());
            doc.put("tags", note.getTags());
            ndjson.append(objectMapper.writeValueAsString(doc)).append('\n');
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(esBase + "/_bulk", HttpMethod.POST,
                new HttpEntity<>(ndjson.toString(), headers), String.class);
        log.info("ES 批量回填 {} 篇笔记", notes.size());
    }

    /** 写入/更新一篇笔记到索引 */
    public void indexNote(Note note) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("note_id", note.getId());
        doc.put("title", note.getTitle());
        doc.put("content", note.getContent());
        doc.put("tags", note.getTags());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(esBase + "/" + INDEX + "/_doc/" + note.getId(),
                HttpMethod.PUT, new HttpEntity<>(doc, headers), String.class);
    }

    /**
     * 关键词搜索（游标分页，★ P1-16 用 search_after 取代 from/size，根除深分页）
     *
     * ★ P1-14 修正：multi_match 加 operator=and —— IK 可能把词切成单字，
     *   默认 or 会让「只命中其中一个字」的文档也算中（如搜"清补凉"命中只含"凉"的笔记）
     *
     * 排序：相关度降序 + _id 升序兜底，保证游标唯一、翻页不重不漏
     * @param cursor 上一页最后一条的排序值（"score:id"）；null 表示第一页
     */
    @SuppressWarnings("unchecked")
    public Page searchPage(String keyword, int size, String cursor) {
        Map<String, Object> match = new HashMap<>();
        match.put("query", keyword);
        match.put("fields", new String[]{"title", "content", "tags"});
        match.put("operator", "and");

        Map<String, Object> body = new HashMap<>();
        body.put("query", Collections.singletonMap("multi_match", match));
        body.put("size", size);
        body.put("sort", Arrays.asList(
                Collections.singletonMap("_score", "desc"),
                Collections.singletonMap("note_id", "asc")));
        if (cursor != null && !cursor.isEmpty()) {
            body.put("search_after", parseCursor(cursor));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                esBase + "/" + INDEX + "/_search", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        Map<String, Object> respBody = response.getBody();
        if (respBody == null) {
            return new Page(Collections.emptyList(), null);
        }
        Map<String, Object> hits = (Map<String, Object>) respBody.get("hits");
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hits.get("hits");

        List<Long> ids = new ArrayList<>(hitList.size());
        List<Object> lastSort = null;
        for (Map<String, Object> hit : hitList) {
            ids.add(Long.parseLong(hit.get("_id").toString()));
            lastSort = (List<Object>) hit.get("sort");
        }
        // 只有取满一页才可能有下一页；游标 = 本页最后一条的排序值
        String nextCursor = (hitList.size() == size && lastSort != null)
                ? (lastSort.get(0) + ":" + lastSort.get(1)) : null;
        return new Page(ids, nextCursor);
    }

    /** "1.23:15" → [1.23, 15]（score 为 double，note_id 为 long，类型需与 sort 字段一致） */
    private List<Object> parseCursor(String cursor) {
        int idx = cursor.indexOf(':');
        if (idx <= 0) {
            throw new IllegalArgumentException("非法游标：" + cursor);
        }
        return Arrays.asList(Double.parseDouble(cursor.substring(0, idx)),
                Long.parseLong(cursor.substring(idx + 1)));
    }

    /** 搜索结果：id 列表 + 下一页游标（null 表示没有下一页） */
    public static class Page {
        private final List<Long> ids;
        private final String nextCursor;

        public Page(List<Long> ids, String nextCursor) {
            this.ids = ids;
            this.nextCursor = nextCursor;
        }

        public List<Long> getIds() {
            return ids;
        }

        public String getNextCursor() {
            return nextCursor;
        }
    }
}
