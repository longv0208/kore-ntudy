-- Replace the legacy learner catalog with a small independent demo catalog
-- catalog. Historical sets and immutable snapshots are retained for existing
-- attempts; only their live catalog entries are archived.
--
-- This migration intentionally adds no table and makes no production scoring
-- claim. The content is original KSH demo material and every published
-- question has an immutable group owner.

SET @premium_seed_bundle = 'practice-demo-canonical-v1';
SET @premium_seed_author = COALESCE(
    (SELECT id FROM users WHERE email = 'admin@ksh.edu.vn' ORDER BY id LIMIT 1),
    (SELECT id FROM users ORDER BY id LIMIT 1)
);

UPDATE practice_sets
SET status = 'ARCHIVED',
    archived_at = COALESCE(archived_at, CURRENT_TIMESTAMP)
WHERE id IN (1, 2, 3, 4, 5, 6)
  AND status = 'PUBLISHED'
  AND creation_method = 'MANUAL';

INSERT INTO practice_sets (
    title, description, skill, scope, metadata_json, status, created_by,
    creation_method, cover_image_url
) VALUES
    ('KSH Demo · Đọc theo ngữ cảnh',
     'Bộ đọc ngắn do KSH biên soạn cho bản trình diễn luyện tập.',
     'READING', 'GLOBAL',
     JSON_OBJECT('seedBundle', @premium_seed_bundle, 'seedKey', 'demo-reading-v1',
                 'premium', FALSE, 'demo', TRUE, 'releaseScope', 'EXPERIMENTAL_DEMO',
                 'contentAuthority', 'KSH_ORIGINAL_DEMO'),
     'PUBLISHED', @premium_seed_author, 'MANUAL', NULL),
    ('KSH Demo · Nghe tình huống',
     'Bộ nghe ngắn dùng audio kiểm tra loa đi kèm ứng dụng.',
     'LISTENING', 'GLOBAL',
     JSON_OBJECT('seedBundle', @premium_seed_bundle, 'seedKey', 'demo-listening-v1',
                 'premium', FALSE, 'demo', TRUE, 'releaseScope', 'EXPERIMENTAL_DEMO',
                 'contentAuthority', 'KSH_ORIGINAL_DEMO'),
     'PUBLISHED', @premium_seed_author, 'MANUAL', NULL),
    ('KSH Demo · Viết Q51–Q54',
     'Bộ viết minh họa gồm đủ bốn dạng Q51, Q52, Q53 và Q54.',
     'WRITING', 'GLOBAL',
     JSON_OBJECT('seedBundle', @premium_seed_bundle, 'seedKey', 'demo-writing-v1',
                 'premium', FALSE, 'demo', TRUE, 'releaseScope', 'EXPERIMENTAL_DEMO',
                 'contentAuthority', 'KSH_ORIGINAL_DEMO'),
     'PUBLISHED', @premium_seed_author, 'MANUAL', NULL),
    ('KSH Demo · Nói theo chủ đề',
     'Bộ nói ngắn cho luồng direct-audio experimental demo.',
     'SPEAKING', 'GLOBAL',
     JSON_OBJECT('seedBundle', @premium_seed_bundle, 'seedKey', 'demo-speaking-v1',
                 'premium', FALSE, 'demo', TRUE, 'releaseScope', 'EXPERIMENTAL_DEMO',
                 'experimentalFeedback', TRUE,
                 'contentAuthority', 'KSH_ORIGINAL_DEMO'),
     'PUBLISHED', @premium_seed_author, 'MANUAL', NULL);

SET @reading_set = (SELECT id FROM practice_sets
    WHERE JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.seedKey')) = 'demo-reading-v1' LIMIT 1);
SET @listening_set = (SELECT id FROM practice_sets
    WHERE JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.seedKey')) = 'demo-listening-v1' LIMIT 1);
SET @writing_set = (SELECT id FROM practice_sets
    WHERE JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.seedKey')) = 'demo-writing-v1' LIMIT 1);
SET @speaking_set = (SELECT id FROM practice_sets
    WHERE JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.seedKey')) = 'demo-speaking-v1' LIMIT 1);

INSERT INTO practice_tests (set_id, title, description, display_order, estimated_minutes) VALUES
    (@reading_set, 'Bài đọc demo', 'Đọc hai văn bản ngắn và trả lời câu hỏi.', 0, 20),
    (@listening_set, 'Bài nghe demo', 'Kiểm tra loa và nhận biết nội dung nghe cơ bản.', 0, 10),
    (@writing_set, 'Bài viết demo Q51–Q54', 'Luyện đủ bốn dạng viết canonical.', 0, 50),
    (@speaking_set, 'Bài nói demo', 'Ba câu trả lời direct-audio theo chủ đề.', 0, 15);

SET @reading_test = (SELECT id FROM practice_tests WHERE set_id = @reading_set LIMIT 1);
SET @listening_test = (SELECT id FROM practice_tests WHERE set_id = @listening_set LIMIT 1);
SET @writing_test = (SELECT id FROM practice_tests WHERE set_id = @writing_set LIMIT 1);
SET @speaking_test = (SELECT id FROM practice_tests WHERE set_id = @speaking_set LIMIT 1);

INSERT INTO practice_sections (
    set_id, test_id, title, skill, section_type, instructions, delivery_json,
    duration_minutes, total_points, display_order
) VALUES
    (@reading_set, @reading_test, 'Phần Đọc', 'READING', 'MAIN',
     'Đọc kỹ văn bản tiếng Hàn rồi chọn hoặc nhập đáp án.', NULL, 20, 40.00, 0),
    (@listening_set, @listening_test, 'Phần Nghe', 'LISTENING', 'MAIN',
     'Dùng audio kiểm tra loa của ứng dụng rồi trả lời câu hỏi.',
     JSON_OBJECT('schemaVersion', 'practice-section-delivery-v1',
                 'listeningDelivery', JSON_OBJECT(
                     'checkAudioReference', '/audio/practice/listening-speaker-check.wav')),
     10, 20.00, 0),
    (@writing_set, @writing_test, 'Phần Viết', 'WRITING', 'MAIN',
     'Hoàn thành Q51–Q54. Feedback AI nếu có chỉ mang tính thử nghiệm.', NULL, 50, 100.00, 0),
    (@speaking_set, @speaking_test, 'Phần Nói', 'SPEAKING', 'MAIN',
     'Thu âm câu trả lời. Feedback AI là thử nghiệm, không phải đánh giá chuẩn hóa.', NULL, 15, 100.00, 0);

SET @reading_section = (SELECT id FROM practice_sections WHERE set_id = @reading_set LIMIT 1);
SET @listening_section = (SELECT id FROM practice_sections WHERE set_id = @listening_set LIMIT 1);
SET @writing_section = (SELECT id FROM practice_sections WHERE set_id = @writing_set LIMIT 1);
SET @speaking_section = (SELECT id FROM practice_sections WHERE set_id = @speaking_set LIMIT 1);

INSERT INTO practice_question_groups (
    set_id, section_id, group_label, question_from, question_to, instruction,
    stimulus_type, instruction_language_tag, stimulus_language_tag,
    passage_text, transcript_text, image_url, stimulus_provenance_json,
    audio_url, example_json, display_order
) VALUES
    (@reading_set, @reading_section, 'Đọc 1', 1, 2,
     'Đọc đoạn văn và trả lời câu 1–2.', 'READING_PASSAGE', 'vi', 'ko',
     '지수 씨는 아침마다 자전거를 타고 회사에 갑니다. 비가 오는 날에는 버스를 탑니다. 회사에 도착하면 동료와 커피를 마시고 일을 시작합니다.',
     NULL, NULL, JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE),
     NULL, NULL, 0),
    (@reading_set, @reading_section, 'Đọc 2', 3, 4,
     'Đọc thông báo và trả lời câu 3–4.', 'READING_PASSAGE', 'vi', 'ko',
     '도서관 이용 안내: 월요일부터 토요일까지 오전 9시에 문을 엽니다. 일요일은 쉽니다. 책은 한 번에 세 권까지 빌릴 수 있습니다.',
     NULL, NULL, JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE),
     NULL, NULL, 1),
    (@listening_set, @listening_section, 'Kiểm tra nghe', 1, 2,
     'Nghe audio kiểm tra loa đi kèm ứng dụng.', 'LISTENING_AUDIO', 'vi', 'ko',
     NULL, NULL, NULL, JSON_OBJECT('source', 'KSH_BUNDLED_SPEAKER_CHECK', 'approvedForDemo', TRUE),
     '/audio/practice/listening-speaker-check.wav', NULL, 0),
    (@writing_set, @writing_section, 'Viết Q51', 51, 51,
     'Điền hai chỗ trống để hoàn thành thư nhắn.', 'NONE', 'vi', 'ko',
     NULL, NULL, NULL, JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE),
     NULL, NULL, 0),
    (@writing_set, @writing_section, 'Viết Q52', 52, 52,
     'Điền hai chỗ trống để hoàn thành đoạn văn.', 'NONE', 'vi', 'ko',
     NULL, NULL, NULL, JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE),
     NULL, NULL, 1),
    (@writing_set, @writing_section, 'Viết Q53', 53, 53,
     'Viết đoạn mô tả ngắn theo dữ kiện.', 'NONE', 'vi', 'ko',
     NULL, NULL, NULL, JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE),
     NULL, NULL, 2),
    (@writing_set, @writing_section, 'Viết Q54', 54, 54,
     'Viết bài nghị luận.', 'NONE', 'vi', 'ko',
     NULL, NULL, NULL, JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE),
     NULL, NULL, 3),
    (@speaking_set, @speaking_section, 'Nói 1', 1, 1,
     'Giới thiệu bản thân.', 'NONE', 'vi', 'ko', NULL, NULL, NULL,
     JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE), NULL, NULL, 0),
    (@speaking_set, @speaking_section, 'Nói 2', 2, 2,
     'Mô tả thói quen học tập.', 'NONE', 'vi', 'ko', NULL, NULL, NULL,
     JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE), NULL, NULL, 1),
    (@speaking_set, @speaking_section, 'Nói 3', 3, 3,
     'Trình bày ý kiến.', 'NONE', 'vi', 'ko', NULL, NULL, NULL,
     JSON_OBJECT('source', 'KSH_ORIGINAL_DEMO', 'approvedForDemo', TRUE), NULL, NULL, 2);

SET @reading_group_1 = (SELECT id FROM practice_question_groups WHERE set_id=@reading_set AND display_order=0 LIMIT 1);
SET @reading_group_2 = (SELECT id FROM practice_question_groups WHERE set_id=@reading_set AND display_order=1 LIMIT 1);
SET @listening_group = (SELECT id FROM practice_question_groups WHERE set_id=@listening_set LIMIT 1);
SET @writing_group_51 = (SELECT id FROM practice_question_groups WHERE set_id=@writing_set AND question_from=51 LIMIT 1);
SET @writing_group_52 = (SELECT id FROM practice_question_groups WHERE set_id=@writing_set AND question_from=52 LIMIT 1);
SET @writing_group_53 = (SELECT id FROM practice_question_groups WHERE set_id=@writing_set AND question_from=53 LIMIT 1);
SET @writing_group_54 = (SELECT id FROM practice_question_groups WHERE set_id=@writing_set AND question_from=54 LIMIT 1);
SET @speaking_group_1 = (SELECT id FROM practice_question_groups WHERE set_id=@speaking_set AND question_from=1 LIMIT 1);
SET @speaking_group_2 = (SELECT id FROM practice_question_groups WHERE set_id=@speaking_set AND question_from=2 LIMIT 1);
SET @speaking_group_3 = (SELECT id FROM practice_question_groups WHERE set_id=@speaking_set AND question_from=3 LIMIT 1);

INSERT INTO practice_questions (
    set_id, group_id, question_no, question_type, prompt, options_json,
    question_content_json, answer_key, answer_spec_json, explanation,
    explanation_strategy_registry_version, explanation_strategy_code,
    explanation_strategy_version, points, display_order, writing_task_type
) VALUES
    (@reading_set, @reading_group_1, 1, 'SINGLE_CHOICE',
     '지수 씨는 비가 오는 날에 무엇을 탑니까?',
     JSON_ARRAY('자전거', '버스', '지하철', '택시'),
     JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(
         JSON_OBJECT('id','opt_1','text','자전거'), JSON_OBJECT('id','opt_2','text','버스'),
         JSON_OBJECT('id','opt_3','text','지하철'), JSON_OBJECT('id','opt_4','text','택시'))),
     '2', JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','SINGLE_CHOICE',
         'correctOptionIds',JSON_ARRAY('opt_2'),'scoringPolicyCode','ALL_OR_NOTHING'),
     '비가 오는 날에는 버스를 탄다고 했습니다.',
     'rl-explanation-strategy-registry-v2','MCQ_OPTION_ELIMINATION','v1',10.00,0,NULL),
    (@reading_set, @reading_group_1, 2, 'TRUE_FALSE_NOT_GIVEN',
     '지수 씨는 회사에 도착한 뒤 바로 혼자 일을 시작합니다.',
     JSON_ARRAY('TRUE','FALSE','NOT_GIVEN'),
     JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(
         JSON_OBJECT('id','opt_true','text','TRUE'), JSON_OBJECT('id','opt_false','text','FALSE'),
         JSON_OBJECT('id','opt_not_given','text','NOT GIVEN'))),
     'FALSE', JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','TRUE_FALSE_NOT_GIVEN',
         'correctOptionIds',JSON_ARRAY('opt_false'),'scoringPolicyCode','ALL_OR_NOTHING'),
     '지수 씨는 동료와 커피를 마신 후 일을 시작합니다.',
     'rl-explanation-strategy-registry-v2','TFNG_CONTRADICTION_TABLE','v1',10.00,1,NULL),
    (@reading_set, @reading_group_2, 3, 'SINGLE_CHOICE',
     '도서관이 쉬는 날은 언제입니까?',
     JSON_ARRAY('월요일','금요일','토요일','일요일'),
     JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(
         JSON_OBJECT('id','opt_1','text','월요일'), JSON_OBJECT('id','opt_2','text','금요일'),
         JSON_OBJECT('id','opt_3','text','토요일'), JSON_OBJECT('id','opt_4','text','일요일'))),
     '4', JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','SINGLE_CHOICE',
         'correctOptionIds',JSON_ARRAY('opt_4'),'scoringPolicyCode','ALL_OR_NOTHING'),
     '안내문에 일요일은 쉰다고 되어 있습니다.',
     'rl-explanation-strategy-registry-v2','MCQ_OPTION_ELIMINATION','v1',10.00,2,NULL),
    (@reading_set, @reading_group_2, 4, 'FILL_BLANK',
     '책은 한 번에 최대 ______ 권까지 빌릴 수 있습니다.', NULL,
     JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(),'blanks',JSON_ARRAY(
         JSON_OBJECT('id','blank_1','ordinal',1))),
     '세', JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','FILL_BLANK',
         'correctOptionIds',JSON_ARRAY(),'blanks',JSON_ARRAY(
             JSON_OBJECT('blankId','blank_1','acceptedValues',JSON_ARRAY('세','3'))),
         'scoringPolicyCode','NORMALIZED_EXACT'),
     '안내문에 세 권까지 빌릴 수 있다고 되어 있습니다.',
     'rl-explanation-strategy-registry-v2','FILL_SLOT_GRAMMAR_ANALYSIS','v1',10.00,3,NULL),
    (@listening_set, @listening_group, 1, 'SINGLE_CHOICE',
     'Audio kiểm tra loa sử dụng ngôn ngữ nào?',
     JSON_ARRAY('Tiếng Hàn','Tiếng Việt','Tiếng Anh','Không có giọng nói'),
     JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(
         JSON_OBJECT('id','opt_1','text','Tiếng Hàn'), JSON_OBJECT('id','opt_2','text','Tiếng Việt'),
         JSON_OBJECT('id','opt_3','text','Tiếng Anh'), JSON_OBJECT('id','opt_4','text','Không có giọng nói'))),
     '1', JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','SINGLE_CHOICE',
         'correctOptionIds',JSON_ARRAY('opt_1'),'scoringPolicyCode','ALL_OR_NOTHING'),
     'Đây là file kiểm tra loa tiếng Hàn đi kèm ứng dụng.',
     'rl-explanation-strategy-registry-v2','MCQ_OPTION_ELIMINATION','v1',10.00,0,NULL),
    (@listening_set, @listening_group, 2, 'SINGLE_CHOICE',
     'Mục tiêu chính của audio này là gì?',
     JSON_ARRAY('Kiểm tra loa','Thi chứng chỉ','Xếp hạng học viên','Ghi lại giọng người học'),
     JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(
         JSON_OBJECT('id','opt_1','text','Kiểm tra loa'), JSON_OBJECT('id','opt_2','text','Thi chứng chỉ'),
         JSON_OBJECT('id','opt_3','text','Xếp hạng học viên'), JSON_OBJECT('id','opt_4','text','Ghi lại giọng người học'))),
     '1', JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','SINGLE_CHOICE',
         'correctOptionIds',JSON_ARRAY('opt_1'),'scoringPolicyCode','ALL_OR_NOTHING'),
     'Audio này chỉ dùng để xác nhận thiết bị phát âm thanh hoạt động.',
     'rl-explanation-strategy-registry-v2','MCQ_OPTION_ELIMINATION','v1',10.00,1,NULL),
    (@writing_set, @writing_group_51, 51, 'ESSAY',
     '친구에게 보낼 메시지를 완성하십시오: 내일 같이 도서관에 갑시다. 저는 오전에 수업이 있어서 ( ㉠ ). 오후 두 시에 도서관 앞에서 ( ㉡ ).',
     NULL,
     JSON_OBJECT('schemaVersion','question-content-v3','options',JSON_ARRAY(),'blanks',JSON_ARRAY(),
       'writingResponse',JSON_OBJECT('responseSchemaVersion','writing-blanks.v1','responseMode','STRUCTURED_BLANKS',
         'taskType','Q51','blanks',JSON_ARRAY(
           JSON_OBJECT('blankId','q51-b1','ordinal',1,'context','오전 일정'),
           JSON_OBJECT('blankId','q51-b2','ordinal',2,'context','약속 장소'))), 'languageTag','ko'),
     NULL,
     JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','ESSAY','correctOptionIds',JSON_ARRAY(),
       'blanks',JSON_ARRAY(),'scoringPolicyCode','PROFILE_BASED','writingBlankAuthority',JSON_OBJECT(
         'contractVersion','writing-blank-authority.v1','taskType','Q51','normalization','NFC',
         'whitespacePolicy','TRIM_COLLAPSE','blanks',JSON_ARRAY(
           JSON_OBJECT('blankId','q51-b1','ordinal',1,'acceptedAnswers',JSON_ARRAY(
             JSON_OBJECT('text','오후에 갈 수 있습니다','equivalence','EXACT','evidenceIds',JSON_ARRAY()))),
           JSON_OBJECT('blankId','q51-b2','ordinal',2,'acceptedAnswers',JSON_ARRAY(
             JSON_OBJECT('text','만납시다','equivalence','EXACT','evidenceIds',JSON_ARRAY())))))),
     'Các phương án tương đương được dùng làm bằng chứng luyện tập, không phải đáp án chứng chỉ.',
     NULL,NULL,NULL,10.00,0,'Q51'),
    (@writing_set, @writing_group_52, 52, 'ESSAY',
     '안내문을 완성하십시오: 이번 주 토요일에 한국어 모임이 있습니다. 참여하고 싶은 분은 금요일까지 ( ㉠ ). 모임에서는 한국 영화를 보고 함께 ( ㉡ ).',
     NULL,
     JSON_OBJECT('schemaVersion','question-content-v3','options',JSON_ARRAY(),'blanks',JSON_ARRAY(),
       'writingResponse',JSON_OBJECT('responseSchemaVersion','writing-blanks.v1','responseMode','STRUCTURED_BLANKS',
         'taskType','Q52','blanks',JSON_ARRAY(
           JSON_OBJECT('blankId','q52-b1','ordinal',1,'context','신청 방법'),
           JSON_OBJECT('blankId','q52-b2','ordinal',2,'context','모임 활동'))), 'languageTag','ko'),
     NULL,
     JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','ESSAY','correctOptionIds',JSON_ARRAY(),
       'blanks',JSON_ARRAY(),'scoringPolicyCode','PROFILE_BASED','writingBlankAuthority',JSON_OBJECT(
         'contractVersion','writing-blank-authority.v1','taskType','Q52','normalization','NFC',
         'whitespacePolicy','TRIM_COLLAPSE','blanks',JSON_ARRAY(
           JSON_OBJECT('blankId','q52-b1','ordinal',1,'acceptedAnswers',JSON_ARRAY(
             JSON_OBJECT('text','신청해 주십시오','equivalence','EXACT','evidenceIds',JSON_ARRAY()))),
           JSON_OBJECT('blankId','q52-b2','ordinal',2,'acceptedAnswers',JSON_ARRAY(
             JSON_OBJECT('text','이야기할 것입니다','equivalence','EXACT','evidenceIds',JSON_ARRAY())))))),
     'Các phương án tương đương được dùng làm bằng chứng luyện tập, không phải đáp án chứng chỉ.',
     NULL,NULL,NULL,10.00,1,'Q52'),
    (@writing_set, @writing_group_53, 53, 'ESSAY',
     '온라인 한국어 학습 시간이 최근 3년 동안 주 2시간에서 주 5시간으로 tăng했다는 dữ kiện을 바탕으로 200~300자로 설명하십시오.',
     NULL, JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(),'languageTag','ko'), NULL,
     JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','ESSAY','correctOptionIds',JSON_ARRAY(),
                 'blanks',JSON_ARRAY(),'scoringPolicyCode','PROFILE_BASED'),
     'Đánh giá thử nghiệm theo nội dung, cấu trúc và ngôn ngữ.', NULL,NULL,NULL,30.00,2,'Q53'),
    (@writing_set, @writing_group_54, 54, 'ESSAY',
     '외국어 학습에서 꾸준한 습관이 중요한 이유에 대해 600~700자로 자신의 생각을 쓰십시오.',
     NULL, JSON_OBJECT('schemaVersion','question-content-v1','options',JSON_ARRAY(),'languageTag','ko'), NULL,
     JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','ESSAY','correctOptionIds',JSON_ARRAY(),
                 'blanks',JSON_ARRAY(),'scoringPolicyCode','PROFILE_BASED'),
     'Đánh giá thử nghiệm theo nội dung, cấu trúc và ngôn ngữ.', NULL,NULL,NULL,50.00,3,'Q54'),
    (@speaking_set, @speaking_group_1, 1, 'SPEAKING',
     '한국어로 자기소개를 하고 한국어를 배우는 이유를 말해 보세요.', NULL,
     JSON_OBJECT('schemaVersion','question-content-v2','options',JSON_ARRAY(),'blanks',JSON_ARRAY(),
       'speakingDelivery',JSON_OBJECT('inputType','manual_text','deliveryMode','text_only',
         'audioOrigin','none','preparationSeconds',30,'responseSeconds',60),'languageTag','ko'),
     NULL, JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','SPEAKING',
       'correctOptionIds',JSON_ARRAY(),'blanks',JSON_ARRAY(),'scoringPolicyCode','PROFILE_BASED'),
     'Experimental AI feedback; không phải đánh giá chuẩn hóa.', NULL,NULL,NULL,30.00,0,NULL),
    (@speaking_set, @speaking_group_2, 2, 'SPEAKING',
     '평소에 한국어를 어떻게 공부하는지 구체적으로 말해 보세요.', NULL,
     JSON_OBJECT('schemaVersion','question-content-v2','options',JSON_ARRAY(),'blanks',JSON_ARRAY(),
       'speakingDelivery',JSON_OBJECT('inputType','manual_text','deliveryMode','text_only',
         'audioOrigin','none','preparationSeconds',30,'responseSeconds',60),'languageTag','ko'),
     NULL, JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','SPEAKING',
       'correctOptionIds',JSON_ARRAY(),'blanks',JSON_ARRAY(),'scoringPolicyCode','PROFILE_BASED'),
     'Experimental AI feedback; không phải đánh giá chuẩn hóa.', NULL,NULL,NULL,30.00,1,NULL),
    (@speaking_set, @speaking_group_3, 3, 'SPEAKING',
     '온라인 수업과 교실 수업 중 어떤 방식을 더 좋아하는지 이유와 함께 말해 보세요.', NULL,
     JSON_OBJECT('schemaVersion','question-content-v2','options',JSON_ARRAY(),'blanks',JSON_ARRAY(),
       'speakingDelivery',JSON_OBJECT('inputType','manual_text','deliveryMode','text_only',
         'audioOrigin','none','preparationSeconds',30,'responseSeconds',60),'languageTag','ko'),
     NULL, JSON_OBJECT('schemaVersion','answer-spec-v1','questionType','SPEAKING',
       'correctOptionIds',JSON_ARRAY(),'blanks',JSON_ARRAY(),'scoringPolicyCode','PROFILE_BASED'),
     'Experimental AI feedback; không phải đánh giá chuẩn hóa.', NULL,NULL,NULL,40.00,2,NULL);

-- Build immutable version 1 from the complete canonical live graph.
INSERT INTO practice_published_versions (
    set_id, version_number, status, content_hash, published_by, published_at
)
SELECT s.id, 1, 'PUBLISHED',
       SHA2(CONCAT(@premium_seed_bundle, ':', JSON_UNQUOTE(JSON_EXTRACT(s.metadata_json, '$.seedKey'))), 256),
       @premium_seed_author, CURRENT_TIMESTAMP
FROM practice_sets s
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.metadata_json, '$.seedBundle')) = @premium_seed_bundle;

INSERT INTO practice_set_versions (
    published_version_id, set_id, title, description, skill, scope, class_id,
    metadata_json, creation_method, cover_image_url
)
SELECT pv.id, s.id, s.title, s.description, s.skill, s.scope, s.class_id,
       s.metadata_json, s.creation_method, s.cover_image_url
FROM practice_published_versions pv
JOIN practice_sets s ON s.id = pv.set_id
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.metadata_json, '$.seedBundle')) = @premium_seed_bundle;

INSERT INTO practice_test_versions (
    published_version_id, set_version_id, test_id, title, description,
    display_order, estimated_minutes
)
SELECT pv.id, sv.id, t.id, t.title, t.description, t.display_order, t.estimated_minutes
FROM practice_published_versions pv
JOIN practice_set_versions sv ON sv.published_version_id = pv.id
JOIN practice_tests t ON t.set_id = pv.set_id
JOIN practice_sets s ON s.id = pv.set_id
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.metadata_json, '$.seedBundle')) = @premium_seed_bundle;

INSERT INTO practice_section_versions (
    published_version_id, test_version_id, section_id, title, skill,
    section_type, instructions, delivery_json, duration_minutes, total_points, display_order
)
SELECT pv.id, tv.id, sec.id, sec.title, sec.skill, sec.section_type,
       sec.instructions, sec.delivery_json, sec.duration_minutes, sec.total_points, sec.display_order
FROM practice_published_versions pv
JOIN practice_test_versions tv ON tv.published_version_id = pv.id
JOIN practice_sections sec ON sec.test_id = tv.test_id
JOIN practice_sets s ON s.id = pv.set_id
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.metadata_json, '$.seedBundle')) = @premium_seed_bundle;

INSERT INTO practice_question_group_versions (
    published_version_id, section_version_id, group_id, group_label,
    question_from, question_to, instruction, stimulus_type,
    instruction_language_tag, stimulus_language_tag, passage_text,
    transcript_text, image_url, stimulus_provenance_json, audio_url,
    example_json, display_order
)
SELECT pv.id, secv.id, g.id, g.group_label, g.question_from, g.question_to,
       g.instruction, g.stimulus_type, g.instruction_language_tag,
       g.stimulus_language_tag, g.passage_text, g.transcript_text, g.image_url,
       g.stimulus_provenance_json, g.audio_url, g.example_json, g.display_order
FROM practice_published_versions pv
JOIN practice_section_versions secv ON secv.published_version_id = pv.id
JOIN practice_question_groups g ON g.section_id = secv.section_id
JOIN practice_sets s ON s.id = pv.set_id
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.metadata_json, '$.seedBundle')) = @premium_seed_bundle;

INSERT INTO practice_question_versions (
    published_version_id, section_version_id, group_version_id, question_id,
    question_no, question_type, prompt, options_json, question_content_json,
    answer_key, answer_spec_json, explanation,
    explanation_strategy_registry_version, explanation_strategy_code,
    explanation_strategy_version, points, display_order, writing_task_type
)
SELECT pv.id, gv.section_version_id, gv.id, q.id, q.question_no,
       q.question_type, q.prompt, q.options_json, q.question_content_json,
       q.answer_key, q.answer_spec_json, q.explanation,
       q.explanation_strategy_registry_version, q.explanation_strategy_code,
       q.explanation_strategy_version, q.points, q.display_order, q.writing_task_type
FROM practice_published_versions pv
JOIN practice_question_group_versions gv ON gv.published_version_id = pv.id
JOIN practice_questions q ON q.group_id = gv.group_id
JOIN practice_sets s ON s.id = pv.set_id
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.metadata_json, '$.seedBundle')) = @premium_seed_bundle;
