# Trợ lý tra cứu Library74 — RAG và công cụ chỉ đọc

Cập nhật: 28/10/2026. Mô tả mã nguồn hiện tại, chưa xác nhận triển khai production. Phạm vi được chốt tại [CHATBOT_AI_FINAL_PROPOSAL.md](CHATBOT_AI_FINAL_PROPOSAL.md). Ngữ cảnh Redis và cache gợi ý 8 tiếng được mô tả tại [REDIS_SHARED_STATE.md](REDIS_SHARED_STATE.md).

## 1. Phạm vi và luồng xử lý

| Nguồn | Cách lấy | Ví dụ |
|---|---|---|
| Hướng dẫn Library74 | JSON chuẩn dùng chung website/chatbot; RAG pgvector | Ai tạo website? Làm sao mượn sách? |
| Công khai hiện hành | Adapter SELECT ở BE | Chính sách; tác giả/ISBN; bản sao còn tại cơ sở/kệ nào |
| Dữ liệu của chính bạn đọc | JWT → userId ở BE → SELECT có ownership | Sách tôi đang mượn; tổng nợ phạt của tôi |

Không crawl, không mở Internet/source code, không hỏi đáp nội dung chương/PDF. Không có công cụ ghi dữ liệu, giao dịch/thanh toán, tra cứu tài khoản khác hoặc nghiệp vụ Admin/thủ thư.

1. FE gửi câu hỏi hiện tại (tối đa 500 ký tự), ngôn ngữ và UUID hội thoại; BE lấy ngữ cảnh ngắn hạn từ Redis theo chủ thể.
2. Khớp câu hỏi/alias chuẩn: trả nội dung có cấu trúc, không gọi LLM. Chủ đề quy định/cọc/phạt/nhận/gia hạn được kèm snapshot chính sách hiện hành.
3. Chưa khớp: Gemini đề xuất một hoặc nhiều công cụ từ schema. BE kiểm tra allowlist, tham số và quyền trước từng lần thực thi. Giao tiếp xã hội (chào hỏi, giới thiệu khả năng, cảm ơn) được phân loại linh hoạt rồi trả nội dung công khai do BE kiểm soát, không cần alias câu hỏi chính xác.
4. Nhánh hướng dẫn: vector search đúng version/ngôn ngữ → BE kiểm tra chunk thuộc nguồn → Gemini diễn đạt chỉ từ nguồn → kiểm tra đầu ra.
5. Nhánh dữ liệu: BE SELECT, dựng lời đáp và thẻ trực tiếp. **Kết quả cá nhân không gửi sang Gemini**, kể cả trong vòng tool tiếp theo/lịch sử Redis. Model nhận câu hỏi và ngữ cảnh công khai ngắn để chọn công cụ; không nhận JWT hoặc toàn bộ hồ sơ. Hỏi khả dụng được trả Có/Không kèm đầu sách, cơ sở, số lượng; phân biệt không có đầu sách với có đầu sách nhưng hết bản sao.
6. Ngoài phạm vi/thiếu dữ liệu/lỗi: từ chối, hỏi lại hoặc báo chưa tra cứu được; không tạo số liệu hay câu trả lời ngoài nguồn.

Vòng điều phối giới hạn tối đa 3 lượt lập kế hoạch, 4 lần thực thi tool, ngân sách xử lý dịch vụ AI khoảng 25 giây. Khóa hội thoại chống xử lý trùng. Không gọi thêm model chỉ để nói DONE sau một truy vấn độc lập đã đủ. Hướng dẫn nhiều chủ đề được tổng hợp từ nhiều nguồn (tối đa 4 phần), ví dụ quên mật khẩu và đăng nhập. Fallback provider có thể thêm HTTP request trong ngân sách còn lại.

Câu trả lời RAG gồm summary, steps, bullets, note. BE kiểm tra ID nguồn, trích dẫn nguyên văn, kiểu/độ dài, số liệu có trong nguồn và không nhận URL/HTML tự sinh. Nếu chọn nguồn hợp lệ nhưng phần diễn đạt không đạt kiểm tra, dùng nội dung nguồn của chunk đó. Các kiểm tra giảm sai sót, không chứng minh mọi câu diễn đạt đều đúng ngữ nghĩa.

## 2. Nguồn hướng dẫn dùng chung

File chuẩn: [faq.json](../library-recommendation-module/src/main/resources/chatbot/faq.json).

Root gồm revision (số nguyên dương) và entries. Mỗi entry có id, sourcePath, vi/en; mỗi bản dịch gồm question, answer, aliases. Catalog hiện có 20 chủ đề song ngữ. BE kiểm tra metadata/câu hỏi trùng khi khởi động; exact match chuẩn hóa cả câu có/không dấu.

Website lấy cùng nguồn qua GET /api/v1/help/content?language=vi|en:
- Trang Hướng dẫn và FAQ render nguồn chuẩn này.
- Giới thiệu/Liên hệ dùng snippet theo chủ đề tương ứng.
- FAQ đọc thêm API chính sách hiện hành. Lỗi tải có nút thử lại, không dùng giá trị mặc định giả định.

**Cập nhật nội dung:** sửa JSON, tăng revision theo số nguyên tăng dần, kiểm thử và build/deploy BE. Không cần migration mới hoặc chèn embedding bằng tay. Catalog nạp khi BE khởi động; không có CMS/hot reload trong bản này.

## 3. Embedding, chunk và phiên bản

AI Gateway tái sử dụng model embedding đã nạp (AI_ETL_EMBEDDING_MODEL, mặc định bkai-foundation-models/vietnamese-bi-encoder). Vector chatbot tách khỏi vector sách, không dùng embedding API trả phí.

- Tiếng Việt tách từ bằng PyVi; query và tài liệu dùng cùng tiền xử lý.
- Ưu tiên đoạn/câu/bước; câu dài mới tách theo từ. FAQ ngắn có thể giữ một chunk.
- Tối đa min(224, model.max_seq_length - 8) token, tính cả tiêu đề và special tokens bằng tokenizer thực tế; không cắt cụt âm thầm.
- Overlap các đơn vị hoàn chỉnh ở cuối, tối đa 32 token; giảm/bỏ overlap nếu không vừa ngân sách.
- Vector chuẩn hóa, 768 chiều. Model khác chiều bị từ chối.
- Truy xuất kết hợp cosine pgvector và từ khóa TF-IDF char n-gram (chuẩn hóa dấu), ghép thứ hạng RRF rồi lấy top 5 cùng ngôn ngữ. Model Việt đơn lẻ bỏ sót nhiều cách diễn đạt tiếng Anh nên cần nhánh từ khóa bổ sung.
- Candidate cần cosine ≥ 0,35 hoặc lexical ≥ 0,15; hai điểm giữ riêng, không coi là xác suất đúng. Đây là ngưỡng ban đầu cho kho hướng dẫn nhỏ; BE tiếp tục kiểm tra nguồn và model phải từ chối khi nguồn không đủ. Tối đa 2.000 candidate trong một lượt truy xuất; cần thiết kế chỉ mục lexical riêng nếu mở rộng kho lớn.

BE kiểm tra index khi ApplicationReady và mỗi 60 giây, bất đồng bộ. Khóa cục bộ và PostgreSQL advisory lock tránh hai job xuất bản cùng lúc. Input chunk không đổi được tái sử dụng vector; model/weight revision/context hoặc pipeline đổi sẽ lập lại vector tương ứng.

Version là SHA-256 catalog. Revision tăng dần ngăn deployment cũ ghi đè mới. Quên tăng revision khi sửa nội dung bị từ chối 409. Khi đổi tiền xử lý, tăng PIPELINE trong chatbot_knowledge.py. HF commit hash được ghi vào model identity nếu model cung cấp; model local không có metadata phải đổi model ID/pipeline thủ công khi đổi weights.

Generation mới chỉ thành active sau khi toàn bộ chunk/vector hợp lệ và transaction commit. Giữ active + một bản trước; encode/DB lỗi không làm trống active. BE không dùng vector khác version. Rollback về BE revision cũ vẫn có FAQ/tool đọc nhưng RAG không dùng index mới hơn; muốn khôi phục nội dung cũ phải phát hành lại với revision cao hơn.

BE kiểm tra lại topic, sourcePath, điểm hữu hạn và chunk thực sự thuộc nguồn hiện hành trước khi diễn đạt.

## 4. Allowlist công cụ

| Công cụ | Phạm vi |
|---|---|
| search_library_help | Hướng dẫn công khai đã duyệt |
| get_current_circulation_policy | Quy định hiện tại; không lộ Admin sửa chính sách |
| search_catalog | Tên/tác giả/ISBN, có/không dấu, cơ sở |
| get_publication_details | Metadata, mô tả/tóm tắt đã lưu; không đọc PDF/URL upload |
| get_publication_availability | AVAILABLE chưa chờ cất kệ; barcode/cơ sở/kệ; không lộ người giữ |
| list_publications | Mới nhất/mượn nhiều; không chạy job cập nhật |
| get_publication_rating_summary | Trung bình, tổng, phân bố sao; không đọc danh tính người đánh giá |
| get_my_loans | Đang mượn/quá hạn; có thể hỏi riêng phiếu chờ nhận |
| get_my_loan_history | Đã trả, lọc ngày mượn/trả theo giờ Việt Nam |
| get_my_fines | Của mình; UNPAID mặc định, PAID/ALL khi yêu cầu |
| get_my_reservations | Trạng thái/hàng đợi/hạn nhận/cơ sở của mình |
| get_my_wishlist | Yêu thích của mình, không tăng lượt xem |
| get_my_notifications | Thông báo của mình, không đánh dấu đã đọc |

Schema không có userId/MSSV, URL hoặc SQL. BE từ chối tham số thừa/sai kiểu, ID vượt Long, ngày/trạng thái sai, page ngoài 0..1000. SELECT dùng bind parameters; các đoạn SQL ghép chỉ từ whitelist cố định. Adapter dùng transaction readOnly, không gọi controller/use case có tracking/ghi dữ liệu.

Mỗi trang/tool tối đa 5 thẻ; page bắt đầu 0. Tổng count/tiền tính trên toàn bộ bộ lọc. Redis giữ tối đa 6 lượt trong 30 phút không hoạt động và tham chiếu đầu sách/cơ sở công khai; không coi số liệu trong lịch sử là tồn kho hiện tại. Nhiều đầu sách phù hợp được trả danh sách để người dùng chỉ rõ tên/ISBN/mã. Khả dụng là snapshot, không giữ chỗ.

## 5. API và quyền

| Endpoint | Quyền |
|---|---|
| GET /api/v1/help/content?language=vi | Public; nguồn chuẩn, revision, version |
| GET /api/v1/chatbot/faqs?language=vi | Public; câu hỏi gợi ý |
| POST /api/v1/chatbot/messages | Public; luôn không có identity kể cả body cố chèn userId |
| POST /api/v1/chatbot/my/messages | JWT bắt buộc; tool cá nhân chỉ dành STUDENT/FACULTY |
| DELETE /api/v1/chatbot/conversations/{uuid} | Xóa ngữ cảnh public có UUID tương ứng |
| DELETE /api/v1/chatbot/my/conversations/{uuid} | JWT bắt buộc; chỉ làm mất hiệu lực ngữ cảnh của mình |

Body chat gồm message, language (vi/en), conversationId (UUID tùy chọn để tương thích client cũ). Không gửi lịch sử, identity hoặc token trong body. FE dùng Authorization hiện có của Axios cho endpoint riêng; public endpoint vẫn tokenless.

Hai POST chat là truy vấn: được loại khỏi audit filter dành cho thao tác ghi, không tạo audit_logs cho từng câu hỏi. Lỗi validation trả 400 chung, tránh logger MVC ghi rejected field chứa nội dung chat. Audit các endpoint thay đổi nghiệp vụ khác giữ nguyên.

Response giữ các trường cũ, bổ sung sections cho nhiều kết quả, conversationId, contextReset và reasonCode. Trạng thái gồm ANSWERED, CLARIFY, AUTH_REQUIRED, UNSUPPORTED, UNAVAILABLE, NO_MATCH. NO_MATCH chỉ là không tìm thấy thông tin phù hợp; lỗi index/version, timeout, hết quota AI có reasonCode riêng và thông báo lỗi rõ ràng. Khi có nguồn nhưng model diễn đạt lỗi, trả nội dung nguồn kèm thông báo. FE hiển thị “Đang suy nghĩ…”. Private endpoint chưa đăng nhập trả HTTP 401. Tất cả response chat có Cache-Control: no-store.

Các đường BE → AI nội bộ:
- GET /internal/chatbot/knowledge/status
- POST /internal/chatbot/knowledge/sync (revision, version, documents công khai)
- POST /internal/chatbot/knowledge/search (query, language, version, không identity)

Tất cả yêu cầu header X-Chatbot-Service-Key. BE gửi khóa và AI kiểm tra khóa dùng chung này. Chưa đặt key hoặc sai key trả 403. Không đưa khóa ra trình duyệt hoặc mở reverse proxy public cho /internal/.

## 6. Cấu hình và release

**Với CI/CD và Docker Compose hiện tại, cấu hình trong file .env.prod ở thư mục LMS gốc.** Workflow dùng docker compose --env-file .env.prod và BE/AI đều có env_file: .env.prod. Không cần tạo thêm file .env trong LMS_BE hoặc LMS_AI khi triển khai theo cách này.

.env.prod.example là mẫu cho cách triển khai trên. LMS_BE/.env.example và LMS_AI/.env.example chỉ tham khảo khi chạy riêng từng service ngoài Compose. Không sửa secret thật trong tài liệu hoặc commit file .env.prod.

| Biến | Ý nghĩa |
|---|---|
| CHATBOT_GEMINI_API_KEY | Key server-side, có thể dùng key đã cấp quyền phù hợp |
| CHATBOT_GEMINI_MODEL | Giữ mặc định cũ gemini-3.5-flash-lite |
| CHATBOT_GEMINI_FALLBACK_MODEL | Giữ mặc định cũ gemini-3.1-flash-lite |
| CHATBOT_RAG_ENABLED | true mặc định; false tắt sync/retrieval |
| CHATBOT_INTERNAL_API_KEY | Tự tạo secret ngẫu nhiên, đặt một lần trong .env.prod; BE và AI cùng nhận giá trị này |
| BE_DATABASE_URL | Cấu hình BE đã có trong .env.prod; Compose chuyển thành DATABASE_URL của container backend |
| AI_DATABASE_URL | Cấu hình AI đã có trong .env.prod; Compose chuyển thành DATABASE_URL của container ai-api |

AI_GATEWAY_BASE_URL được Compose đặt sẵn cho backend là http://ai-api:8001, không cần thêm biến này vào .env.prod. DATABASE_URL là tên biến bên trong từng container, không phải yêu cầu thêm một biến database mới vào file gốc. BE dùng URL JDBC còn AI dùng URL PostgreSQL. Migration V63 chạy trên database LMS hiện có, chỉ thêm bảng knowledge cho chatbot; không tạo database riêng.

CHATBOT_INTERNAL_API_KEY là khóa xác thực giao tiếp BE → AI cho các API status/sync/search kho tri thức, không phải key Gemini, không lấy từ Google và không dùng để đăng nhập bạn đọc. Tự tạo 32 byte ngẫu nhiên bằng lệnh sau trên máy của bạn:

```powershell
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

Dán kết quả vào CHATBOT_INTERNAL_API_KEY trong file LMS/.env.prod. Chỉ đặt một lần vì Compose nạp cùng file cho cả BE và AI. Sau khi đổi env, cần tạo lại container BE và AI để chúng nhận giá trị mới; restart tiến trình đơn thuần không cập nhật môi trường container.

Tên Gemini là cấu hình sẵn có, không phải cam kết Google luôn cung cấp model/quota đó. Chọn model có quyền truy cập thực tế trong project của bạn. Nếu chạy riêng ngoài Compose, cần tự cấp internal key trùng nhau cho hai service.

Thứ tự release:
1. Staging có pgvector và quyền Flyway tạo extension/schema. Kiểm tra quyền/schema production trước release.
   Nếu AI dùng DB role khác Flyway, cấp SELECT/INSERT/UPDATE/DELETE trên hai bảng ai_engine.chatbot_knowledge_* cho đúng role AI; không cấp quyền nghiệp vụ thêm cho chatbot.
2. BE chạy V63__chatbot_knowledge.sql: tạo hai bảng knowledge, không sửa bảng nghiệp vụ hoặc migration đã phát hành; không embedding trong migration.
3. AI có PyVi/router nội bộ. BE/AI lệch thứ tự tạm thời được xử lý bằng sync thử lại mỗi 60 giây, không chặn BE startup.
4. Cấu hình internal key, bật RAG; kiểm tra status đúng version/ready qua mạng nội bộ.
5. FE: kiểm tra nguồn website, exact FAQ, câu hỏi diễn đạt lại, tra sách và hai tài khoản bạn đọc khác nhau.

Tắt RAG hoặc bỏ internal key: FAQ chính xác và tool đọc vẫn còn; tool cần Gemini phân loại vẫn phụ thuộc Gemini. Để trống Gemini key sẽ chỉ còn FAQ khớp chính xác ở chat. Không đặt trần RPM/RPD riêng; giữ concurrency bốn request và cooldown provider.

429 tôn trọng Retry-After/quota metadata và có thể dùng model dự phòng. 404/network/5xx có cooldown. Sai key/payload hoặc output/safety lỗi không thử model khác để tìm câu trả lời dễ dãi hơn. Không dùng kiến thức ngoài khi hết quota.

## 7. FE và riêng tư

Widget Việt/Anh, light/dark; Enter gửi, Shift+Enter xuống dòng; loading/error, gợi ý, xóa hội thoại, Escape. Hiển thị tóm tắt/bước/bullet/lưu ý và thẻ sách/phiếu có nguồn/timestamp. Không render HTML từ model; link nội bộ được whitelist, chi tiết mở tab mới bằng Router Link.

Đổi ngôn ngữ, đăng nhập/đăng xuất/đổi phiên: unmount panel, hủy request, xóa nội dung riêng tư. Logout xóa state ngay, không chờ HTTP hoàn tất; storage event hỗ trợ đổi phiên giữa các tab. Không lưu transcript vào localStorage. Panel upload đang hoạt động được ưu tiên góc màn hình, chat tạm ẩn/hủy request.

## 8. Kiểm thử và giới hạn nghiệm thu

Chạy tại từng thư mục:
- LMS_BE: mvn --batch-mode --no-transfer-progress test (cần Docker).
- LMS_FE: npm test -- --runInBand; npx tsc --noEmit; npm run build.
- LMS_AI: python -m pytest -q -rs.

AI integration tests dùng riêng CHATBOT_TEST_DATABASE_URL với dbname bắt buộc chatbot_test, tuyệt đối không dùng DATABASE_URL/production. CI đã tạo pgvector service và database test. Thiếu biến này, bốn test PostgreSQL RAG skip có lý do. Ba test live API cũ vẫn opt-in.

Bao phủ: allowlist/identity injection/JWT/ownership; không gửi private results cho LLM; SQL thực tế của các adapter không làm tăng interaction/đánh dấu notification; tổng tiền toàn bộ bộ lọc; source/version không hợp lệ; token budget/overlap; sync no-op/vector reuse/giữ hai generation; encode lỗi giữ active; revision cũ bị từ chối; FE loading/error/session reset.

Hai test BorrowFlow và Reservation đã đổi image PostgreSQL test sang pgvector để chạy migration mới; không xóa hoặc nới assertion nghiệp vụ cũ.

Kết quả chạy local ngày 28/10/2026:

| Kiểm tra | Kết quả |
|---|---|
| Maven test, gồm PostgreSQL/Redis Testcontainers và Flyway V63 | 64 lớp/suite, 401 test đạt, 0 lỗi, 0 skip (toàn bộ suite và lượt kiểm tra bổ sung) |
| Jest FE | 36 suite, 229 test đạt |
| TypeScript và Vite production build | Đạt; còn cảnh báo bundle lớn có sẵn |
| Pytest AI, có database pgvector riêng | 92 đạt, 3 live API test opt-in skip |

Các con số trên là lượt xác minh mã nguồn, không phải báo cáo chạy trên production hoặc đo độ chính xác Gemini.

Bộ test tự động không thay thế nghiệm thu Gemini thật: thử Việt/Anh/không dấu, sách trùng tên, ngoài phạm vi/prompt injection, đúng nguồn/số liệu, độ trễ và quota/model trước công bố chất lượng. Không có giao dịch thật hoặc truy cập production trong bước phát triển này.

Đánh giá retrieval riêng với weights thật: tại LMS_AI chạy python -m scripts.evaluate_chatbot_retrieval. Script không gọi DB/Gemini, không đọc dữ liệu tài khoản. Lượt kiểm tra 27/10/2026: 40 tài liệu → 40 chunk, dài nhất 180 token trong context 256, 768 chiều; tìm được nguồn mong đợi trong top 5 ở 19/20 câu diễn đạt lại Việt/Anh. Một câu tiếng Anh về kéo dài thời hạn mượn còn bị bỏ sót. Bộ 20 câu dùng để chỉnh bước truy xuất, không phải tập nghiệm thu độc lập hoặc độ chính xác trả lời 95%. Cần mở rộng bộ đánh giá và kiểm tra routing/diễn đạt Gemini trên staging.
