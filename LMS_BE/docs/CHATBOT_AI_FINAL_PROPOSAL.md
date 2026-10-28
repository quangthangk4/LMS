# Thiết kế đã chốt: Trợ lý Library74

Cập nhật 28/10/2026. Thay thế đề xuất trả lời có cấu trúc/thẻ dữ liệu và không gửi kết quả cá nhân cho Gemini trước đây. Tài liệu hiện hành: [FAQ_CHATBOT.md](FAQ_CHATBOT.md), [REDIS_SHARED_STATE.md](REDIS_SHARED_STATE.md).

## Phạm vi

Giao tiếp tự nhiên, hướng dẫn Library74, chính sách hiện hành, catalog/bản sao và dữ liệu của **chính bạn đọc đăng nhập**. Không hỏi đáp chương/PDF, thông tin Internet/source code, không thực hiện mượn/đặt trước/gia hạn/thanh toán hoặc tra cứu người khác.

## Luồng đã thống nhất

1. BE gửi system prompt, nguồn FAQ đã duyệt, lịch sử hỏi–đáp Redis và câu hỏi mới cho Gemini.
2. Gemini quyết định trả lời trực tiếp hay gọi một/nhiều native tool AUTO; lời chào/hỏi lại không phải khớp câu mẫu.
3. BE thực thi allowlist chỉ đọc, kiểm tra tham số và JWT ownership, trả JSON kết quả đầy đủ trong phạm vi tool/phân trang. **JSON cá nhân của bạn đọc đang xác thực được gửi Gemini** để diễn đạt theo câu hỏi.
4. Gemini viết câu trả lời cuối bằng **Markdown**. BE không chấm điểm/ép mẫu/sửa nội dung; lỗi transport/provider vẫn được báo rõ khi không có câu trả lời model.
5. FE render Markdown an toàn, không dựng thẻ sách hoặc lặp log tool/nguồn. Model dùng link sách/nguồn được cung cấp.
6. Redis giữ câu hỏi và Markdown cuối, tối đa 6 lượt/30 phút không hoạt động/64 KiB UTF-8. Tool call, thought signature và JSON chỉ tồn tại trong lượt đang xử lý. Lượt sau truy vấn lại dữ liệu hiện hành khi cần.

## Nguyên tắc giữ lại

- JWT quyết định chủ thể; không có userId tùy chọn từ Gemini, SQL/URL tùy ý hoặc truyền secret/password/token.
- Tool SELECT không tracking, đánh dấu thông báo, giữ chỗ hay ghi nghiệp vụ. Chỉ đọc vẫn cần phân quyền.
- RAG dùng nguồn chuẩn faq.json, không crawl; toàn bộ catalog FAQ cũng có trong prompt để hỗ trợ câu diễn đạt khác nhau và retrieval lỗi/rỗng.
- Context tách chủ thể/phiên đăng xuất, clear/đổi tài khoản/ngôn ngữ không khôi phục dữ liệu cũ. Vượt giới hạn lịch sử bỏ toàn bộ lượt cũ, không cắt Markdown.
- Redis lỗi thì chat độc lập; recommendation giữ fallback hiện có, cache **8 tiếng**, nút **Cập nhật cá nhân hóa**, hydrate tồn kho SQL mới.
- Giới hạn vòng/tool/phân trang/timeout bảo vệ tài nguyên; không đặt trần RPM/RPD chatbot riêng. Quota provider vẫn có thể làm chat tạm không trả lời.
- Prompt không bảo đảm tuyệt đối đúng nguồn; cần nghiệm thu Gemini thật ngoài unit/integration mock.

Chi tiết API, prompt, embedding/version, cấu hình .env.prod, bảo mật Markdown và kiểm thử nằm trong tài liệu kỹ thuật ở đầu.
