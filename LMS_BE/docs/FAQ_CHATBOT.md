# Trợ lý Library74 — Gemini, tool JSON và hội thoại Redis

Cập nhật 28/10/2026. Đây là thiết kế mã nguồn, không xác nhận đã triển khai production. Thay thế luồng phân loại FAQ/trả thẻ dữ liệu trước đây. Xem [Redis và recommendation](REDIS_SHARED_STATE.md).

## 1. Phân chia trách nhiệm

**Gemini quyết định câu trả lời cuối bằng Markdown.** BE cung cấp nguồn, thực thi công cụ chỉ đọc và chuyển nguyên văn phần trả lời của model. FE render Markdown; không tự thêm thẻ sách, tiêu đề dữ liệu, log từng tool hoặc footer nguồn.

| Thành phần | Trách nhiệm |
|---|---|
| `chatbot/system-prompt.txt` | Phạm vi Library74, nguồn được phép, cách dùng tool, diễn đạt tự nhiên |
| `GeminiChatClient` | Native function calling AUTO, HTTP, provider fallback/cooldown |
| `ChatbotService` | Lịch sử user/model, catalog FAQ, điều phối tool và JSON |
| `ChatbotReadQueries` | SELECT có bind parameters, kiểm tra tham số và chủ thể JWT |
| `ChatConversationStore` | Lưu các cặp câu hỏi/câu trả lời đã hoàn tất trong Redis |
| `ChatbotMarkdown` | Hiển thị Markdown và liên kết, không thực thi HTML |

BE không ép gọi tool cho mọi câu hỏi, không kiểm tra trích dẫn nguyên văn/số liệu trong câu trả lời, không giới hạn thành một câu 400 ký tự, không thay văn phong Gemini bằng mẫu có sẵn. Không có exact-match trả lời thay model. Lời chào, giới thiệu khả năng, hỏi lại, từ chối ngoài phạm vi đều do model viết. Khi không gọi được provider, BE vẫn trả thông báo lỗi kỹ thuật rõ ràng; không tạo câu trả lời nghiệp vụ giả định.

Phạm vi nguồn nằm trong system prompt: chỉ Library74 và dữ liệu được cấp; không lấy thông tin Internet/source code, kiến thức bên ngoài để tạo sự thật về thư viện hoặc nội dung chương/PDF. Prompt không bảo đảm tuyệt đối không có hallucination; cần nghiệm thu Gemini thật.

## 2. Luồng một lượt hỏi

1. FE gửi câu hỏi hiện tại (tối đa 500 ký tự), ngôn ngữ và UUID. BE xác định public/bạn đọc từ endpoint và JWT.
2. BE lấy Redis context, gửi các message user/model xen kẽ, rồi câu hỏi mới. System prompt có toàn bộ FAQ đã duyệt của ngôn ngữ hiện tại, ngày Việt Nam và trạng thái có quyền tra cứu cá nhân; không gửi JWT/mật khẩu.
3. Gemini trả lời trực tiếp hoặc gọi một/nhiều native tool bằng AUTO. Không có tool giả để ép schema câu trả lời.
4. BE kiểm tra allowlist, tham số, phân trang và ownership. SELECT trả JSON gồm bản ghi, tổng số, phân trang, tổng hợp, URL, thời điểm tra cứu; không chuyển thành thẻ hoặc cắt mô tả sách. **JSON cá nhân của bạn đọc đang xác thực được phép gửi Gemini** theo phạm vi đã chốt.
5. Trong lượt đang xử lý, giữ nguyên native model content, call ID, thought signature và function response. Tool lỗi trả JSON `error.code`/`retryable`; model phân biệt lỗi tra cứu với kết quả rỗng và tự giải thích.
6. Gemini trả Markdown cuối. BE chuyển nguyên văn; Redis chỉ lưu câu hỏi và Markdown này. Không lưu các bước tool/JSON vào lịch sử.

Ví dụ: “Có sách cần trả gấp không?” → get_my_loans DUE_SOON → JSON → Gemini trả Có/Không trước, rồi tên sách/hạn trả cần thiết. “Có sách Java không?” → kết quả tìm kiếm → Markdown tên/link sách. Lượt sau “cuốn thứ hai ở CS2 còn không?”: Gemini đọc tên/ID trong câu trả lời trước và gọi tool khả dụng mới; lịch sử không phải tồn kho hiện tại.

## 3. Allowlist và JSON

| Tool | Phạm vi |
|---|---|
| search_library_help | Hướng dẫn đã duyệt; catalog dự phòng khi retrieval lỗi/rỗng |
| get_current_circulation_policy | Quy định hiện tại; không trả danh tính Admin sửa |
| search_catalog | Tên/tác giả/ISBN/mô tả/tóm tắt đã lưu, có/không dấu, cơ sở |
| get_publication_details | Metadata và mô tả/tóm tắt; không đọc PDF/chương sách |
| get_publication_availability | AVAILABLE chưa chờ xếp giá; barcode/cơ sở/kệ, không lộ người giữ |
| list_publications | Mới/mượn nhiều, không chạy job cập nhật |
| get_publication_rating_summary | Thống kê điểm, không trả danh tính người đánh giá |
| get_my_loans | Đang mượn/quá hạn/chờ nhận của mình theo bộ lọc |
| get_my_loan_history | Lịch sử đã trả, lọc ngày mượn/trả theo giờ Việt Nam |
| get_my_fines | Phạt của mình, UNPAID mặc định, PAID/ALL khi yêu cầu |
| get_my_reservations | Đặt trước/hạn nhận/cơ sở của mình |
| get_my_wishlist | Yêu thích của mình, không tăng lượt xem |
| get_my_notifications | Thông báo của mình, không đánh dấu đã đọc |

Không nhận userId/MSSV, SQL, URL tùy ý từ model. Khách không có tool cá nhân. Không có tool ghi dữ liệu, giữ chỗ, gia hạn, thanh toán, tracking hay quản trị. Chỉ đọc vẫn cần phân quyền, không có nghĩa là trả mọi dữ liệu database.

Kết quả giữ số tiền dạng số, ngày ISO, ID dạng chuỗi để tránh mất chính xác. Phân trang mặc định 10 bản ghi, pageSize 1–50, page 0–1000, có total/hasNext để lấy tiếp. Tổng count/phí tính trên toàn bộ bộ lọc; không chỉ trang hiện tại. Không âm thầm cắt chuỗi trường để vừa UI.

Tìm kiếm theo từ đầy đủ để Java không tự khớp JavaScript. Khả dụng là snapshot, không giữ chỗ; bản chờ xếp giá không tính có sẵn. DUE_SOON gồm quá hạn hoặc đến hạn trong 3 ngày theo lịch Việt Nam; trạng thái đối chiếu hạn trả kể cả scheduler chưa cập nhật OVERDUE.

## 4. Redis context

- Namespace `lms:chat:v2:*`, tách public và từng bạn đọc/owner generation. Không đọc định dạng v1 cũ; key cũ tự hết hạn.
- Chỉ lưu `{language,turns:[{question,answer}]}`. Answer là Markdown nguyên văn, kể cả câu trả lời cá nhân. Không lưu tool JSON, call ID, thought signature, registry sách riêng hoặc tóm tắt câu trả lời.
- Tối đa **6 lượt hoàn chỉnh**, TTL **30 phút không hoạt động**, gia hạn sau khi lưu thành công.
- Toàn bộ JSON context tối đa **64 KiB UTF-8**. Vượt thì bỏ cặp hỏi–đáp cũ nhất; không cắt ngang câu trả lời. Đây là giới hạn byte, không phải bộ đếm token chính xác. Một lượt riêng quá lớn vẫn trả đầy đủ cho FE nhưng không giữ trong Redis.
- Khóa xử lý 100 giây, compare-and-set, tombstone khi xóa; logout đổi owner generation. Đổi tài khoản/ngôn ngữ/xóa chat bỏ phiên cũ và response đang chờ ở FE.
- Redis lỗi: trả lời từng câu độc lập và nhắc mất context; không ghi dữ liệu riêng vào namespace không kiểm soát. Không lưu transcript vào localStorage. Đóng widget chỉ ẩn, cho request hiện tại hoàn tất.

Nếu Markdown trước không đủ rõ để xác định “cuốn thứ hai”, model cần hỏi lại hoặc tìm lại. Tồn kho/công nợ/hạn trả phải truy vấn mới khi cần số liệu hiện tại.

Recommendation giữ cache xếp hạng **8 tiếng** và nút **Cập nhật cá nhân hóa** tại trang chủ; metadata/tồn kho vẫn hydrate từ SQL hiện tại. Không dùng context chatbot làm cache nghiệp vụ.

## 5. Nguồn hướng dẫn, embedding và phiên bản

Nguồn chuẩn `library-recommendation-module/src/main/resources/chatbot/faq.json`, 20 chủ đề song ngữ. Revision **3** cập nhật thông báo riêng tư theo luồng đã chốt. Website dùng cùng nguồn qua GET /api/v1/help/content?language=vi|en. Link có anchor như #password, #borrow, #creator. Sửa nội dung phải tăng revision và deploy BE, không hot reload/CMS.

Không crawl. BE đưa catalog FAQ hiện hành vào prompt; tool help dùng RAG và kiểm tra chunk thuộc version/nguồn đã duyệt; retrieval lỗi/rỗng vẫn có catalog làm nguồn dự phòng. Pipeline hiện có giữ nguyên:

- Model AI_ETL_EMBEDDING_MODEL, mặc định bkai-foundation-models/vietnamese-bi-encoder; vector chuẩn hóa 768 chiều, tách vector sách.
- Tiếng Việt dùng PyVi; query/tài liệu cùng tiền xử lý. Chia đoạn/câu/bước, câu dài mới tách theo từ.
- Tối đa min(224, model.max_seq_length - 8) token, tính tiêu đề/special tokens bằng tokenizer thật. Overlap đơn vị hoàn chỉnh tối đa 32 token, giảm/bỏ nếu không vừa.
- Hybrid cosine pgvector + TF-IDF char n-gram, ghép hạng RRF, top 5 cùng ngôn ngữ. Candidate cosine ≥ 0,35 hoặc lexical ≥ 0,15, không phải xác suất đúng. Tối đa 2.000 candidate; mở rộng kho lớn cần chỉ mục phù hợp.
- BE kiểm tra ApplicationReady và mỗi 60 giây, bất đồng bộ. Khóa cục bộ/PostgreSQL advisory lock tránh sync cạnh tranh.
- Version SHA-256 catalog, revision tăng dần; quên tăng revision khi đổi nội dung trả 409. Chunk input không đổi tái dùng vector; đổi model/pipeline/context tính lại phần tương ứng. Đổi tiền xử lý tăng PIPELINE; weights local không có metadata phải đổi ID/pipeline thủ công.
- Generation mới chỉ active sau khi toàn bộ vector hợp lệ và transaction commit. Giữ active + một bản trước, lỗi không làm trống active. BE không lấy index khác version; rollback nội dung cần phát hành revision cao hơn.

Không đổi Python dependencies/model embedding trong lượt tái cấu trúc này. Không cần migration mới; bảng knowledge đã có từ V63.

## 6. API và giao diện

| Endpoint | Quyền |
|---|---|
| GET /api/v1/chatbot/faqs?language=vi | Gợi ý công khai, không phát sinh trả lời AI |
| POST /api/v1/chatbot/messages | Public, không chọn danh tính từ body |
| POST /api/v1/chatbot/my/messages | JWT, tool cá nhân chỉ cho vai trò bạn đọc được phép |
| DELETE /api/v1/chatbot/conversations/{uuid} | Xóa public context tương ứng |
| DELETE /api/v1/chatbot/my/conversations/{uuid} | JWT, chỉ context của mình |

Request `{message,language,conversationId?}`, không gửi lịch sử từ browser. Response `{status,answer,format:"markdown",reasonCode?,conversationId,contextReset}` trong ApiResponseApp hiện có. Status ANSWERED hoặc UNAVAILABLE; thiếu thông tin/ngoài phạm vi do model giải thích trong answer. Lỗi provider/quota/cấu hình có reasonCode riêng. Cache-Control no-store; không ghi audit nghiệp vụ cho từng câu hỏi.

FE dùng react-markdown/remark-gfm, hỗ trợ đoạn/đậm/danh sách/link/bảng/code. `skipHtml` bỏ raw HTML, giữ URL transformer an toàn mặc định; không dùng dangerouslySetInnerHTML. Link nội bộ dùng Router Link và giữ fragment; link ngoài có noopener/noreferrer. FE không sửa model facts hoặc văn phong.

Widget Việt/Anh, light/dark, “Đang suy nghĩ…”, Enter gửi, Shift+Enter xuống dòng, clear/Escape, reset khi đổi phiên/ngôn ngữ. Đóng panel không abort câu trả lời; xóa/unmount thì có. Không tạo lời chào assistant giả trước câu hỏi đầu tiên. Upload panel được ưu tiên góc màn hình.

## 7. Tài nguyên và fallback

Tối đa 6 vòng có thể gọi tool, 12 lần thực thi tool rồi một bước model kết luận không khai báo tools. Ngân sách mềm 60 giây trước request provider tiếp theo; connect timeout 2 giây/read timeout 15 giây mỗi request nên request đang chạy có thể vượt 60 giây. FE timeout 90 giây, khóa Redis 100 giây. Đây là giới hạn tài nguyên, không phải kiểm duyệt nội dung.

Gemini maxOutputTokens 3.072 mỗi request. MAX_TOKENS có text vẫn trả nguyên văn và reasonCode OUTPUT_TRUNCATED để FE nhắc riêng. Không cắt answer thành 1.200 ký tự khi lưu.

429 có fallback/cooldown theo Retry-After/quota metadata, quota ngày chờ reset tương ứng. 404/network/408/5xx có fallback. Sai key/payload hoặc output/safety lỗi không thử model khác để tìm câu trả lời dễ dãi hơn. Không có trần RPM/RPD hoặc semaphore bốn request tự đặt tại chatbot; quota provider vẫn áp dụng.

## 8. Cấu hình và triển khai

Production dùng **.env.prod tại LMS gốc**, Compose nạp cho service; secret không đưa FE:

| Biến | Ý nghĩa |
|---|---|
| CHATBOT_GEMINI_API_KEY | Gemini key server-side có quyền model |
| CHATBOT_GEMINI_MODEL | Giữ mặc định cấu hình cũ gemini-3.5-flash-lite |
| CHATBOT_GEMINI_FALLBACK_MODEL | Giữ mặc định cấu hình cũ gemini-3.1-flash-lite |
| CHATBOT_RAG_ENABLED | Bật sync/retrieval; false vẫn có catalog FAQ/tool SQL trong luồng Gemini |
| CHATBOT_INTERNAL_API_KEY | Secret tự tạo dùng chung BE–AI, không phải key Google |
| REDIS_PASSWORD | Tùy chọn, BE/Redis nhận cùng giá trị qua Compose |

Tên model là cấu hình hiện có, không bảo đảm quyền/quota Google. Không có Gemini key thì chat không sinh câu trả lời; trang hướng dẫn/FAQ vẫn hoạt động, không còn exact-match fallback trong chat.

AI_GATEWAY_BASE_URL được Compose đặt http://ai-api:8001. BE_DATABASE_URL/AI_DATABASE_URL hiện có chuyển thành DATABASE_URL trong container tương ứng, không cần database mới. API /internal/chatbot/knowledge/{status,sync,search} kiểm tra X-Chatbot-Service-Key, không mở proxy public. Tạo internal key một lần bằng `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`, đặt trong file gốc cho cả BE/AI. Đổi env cần recreate container.

Deploy đồng bộ BE/FE vì response bỏ cards/sections. Redis chạy theo Compose hiện có. Không cần rebuild AI do thay đổi lần này không sửa Python. Sync revision 3 tự chạy khi BE lên, thử lại mỗi 60 giây. AI dùng role khác Flyway giữ quyền các bảng knowledge đã cấu hình. Context v1 tự hết hạn, recommendation v1 không bị xóa.

## 9. Kiểm thử và nghiệm thu

- LMS_BE: mvn --batch-mode --no-transfer-progress test, cần Docker cho PostgreSQL/Redis Testcontainers.
- LMS_FE: npm test; npx tsc --noEmit; npm run build.
- AI khi sửa pipeline: python -m pytest -q -rs; CHATBOT_TEST_DATABASE_URL phải là database riêng chatbot_test, không dùng production. Live API tests opt-in.

Bao phủ AUTO/call ID/signature, nhiều tool, JSON cá nhân đúng chủ thể gửi model, vòng kết luận, Markdown nguyên văn, lỗi tool/provider, không lưu QA lỗi giả, thứ tự user/model, ownership/logout/CAS/tombstone/TTL/6 lượt/64 KiB, SQL không ghi nghiệp vụ, aggregate/Java≠JavaScript, Markdown an toàn, đóng panel và reset phiên.

HTTP mock kiểm chứng giao thức/điều phối, **không chứng minh Gemini thật trả lời hay hoặc luôn đúng nguồn**. PostgreSQL/Redis integration dùng container thật, không truy cập production. Số liệu test phải lấy từ lượt chạy thực tế, không dùng số cũ.

Kết quả local ngày 28/10/2026: `mvn -q clean test` đạt **370 test/64 suite**, không lỗi hoặc skip, gồm 10 test integration chatbot với PostgreSQL/Redis thật. `npm test` đạt **224 test/37 suite**; `npx tsc --noEmit` và `npm run build` thành công. Build còn cảnh báo bundle lớn. Test classifier/schema câu trả lời và thẻ UI cũ được thay bằng test native AUTO/Markdown/lịch sử hỏi–đáp theo hợp đồng mới; không dùng số lượng test phiên bản cũ để mô tả lần chạy này. Chưa gọi Gemini thật trong lần xác minh này; không thay đổi hoặc chạy lại pipeline Python.

Nghiệm thu sau deploy bằng câu Việt/Anh/không dấu: người tạo website; quên mật khẩu + đăng nhập (đúng anchor); Java rồi cuốn thứ hai ở CS2 (đọc tồn kho mới); sách cần trả gấp (Có/Không trước, đúng tài khoản); hai tài khoản không chung context; phân biệt không có bản ghi với tool lỗi/hết quota; ngoài phạm vi/prompt injection không dẫn đến truy cập khác quyền/ghi dữ liệu.
