SELECT COUNT(*) AS notes_total FROM xhs_bench.t_note;
SELECT COUNT(*) AS notes_with_sanya FROM xhs_bench.t_note WHERE content LIKE '%三亚%';
SELECT MIN(id) AS min_id, MAX(id) AS max_id FROM xhs_bench.t_note;
SELECT COUNT(*) AS user5_follows FROM xhs_bench.t_follow WHERE user_id = 5;
