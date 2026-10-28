# Redis: ngữ cảnh chatbot và gợi ý cá nhân hóa

Cập nhật 28/10/2026. Đây là cấu hình mã nguồn; triển khai production thực hiện qua CI/CD.

## 1. Redis dùng cho gì?

Một service Redis dùng chung, với namespace tách biệt:

| Dữ liệu | Namespace | Thời hạn |
|---|---|---|
| Xếp hạng gợi ý theo tài khoản/khoa | `lms:reco:v1:*` | **8 tiếng từ lúc tính**, đọc lại không kéo dài |
| Khóa tính gợi ý theo tài khoản | `lms:reco:lock:*` | 30 giây, giải phóng theo token |
| Ngữ cảnh hội thoại theo chủ thể/UUID | `lms:chat:v2:*` | **30 phút không hoạt động**, tối đa 6 lượt hỏi đáp, 64 KiB UTF-8 |
| Khóa xử lý hội thoại | khóa hội thoại + `:lock` | 100 giây |
| Phiên bản ngữ cảnh của tài khoản | `lms:chat:owner:*` | 60 phút, gia hạn khi dùng; đổi khi đăng xuất |

Không thêm rate limiter request/phút/ngày, không thêm Flyway migration. Context có thể chứa dữ liệu cá nhân đã được Gemini trình bày trong câu trả lời; không lưu nguyên JSON tool hay snapshot database.

## 2. Gợi ý sách

`GET /api/v1/recommendations?limit=10` lấy danh tính từ JWT:

1. Đọc xếp hạng còn hạn trong Redis.
2. Nếu cache trống, tái sử dụng `ai_recommendations` PostgreSQL còn mới, có xét thời điểm cập nhật hồ sơ. Chỉ ghi Redis với thời hạn **còn lại** của 8 tiếng.
3. Khi cần tính mới, lấy khóa Redis theo tài khoản và khóa cục bộ có số lượng cố định, gọi AI Gateway, lưu xếp hạng mới. Request cạnh tranh đọc snapshot cũ/trending thay vì cùng tính lại. Refresh chủ động cạnh tranh trả HTTP 409.
4. Mỗi lần trả response đều truy vấn metadata và số bản sẵn sàng từ PostgreSQL. Loại sách không còn bản khả dụng; bổ sung trending nếu thiếu. Bản `AVAILABLE` nhưng đang chờ xếp giá không được tính là có sẵn.

Cache chỉ chứa ID/thứ tự, khoa và thời điểm tính; không giữ ảnh, tên sách, số lượng tồn kho hoặc hồ sơ cá nhân. Có thể thay đổi thứ tự hiển thị khi sách hết bản, dù xếp hạng cache còn hạn.

Nút **Cập nhật cá nhân hóa / Refresh personalization** nằm cạnh tiêu đề Gợi ý cho bạn ở trang chủ của bạn đọc. Gọi `POST /api/v1/recommendations/refresh?limit=10`, không nhận userId từ trình duyệt, bỏ qua snapshot còn hạn và bắt đầu kỳ 8 tiếng mới khi thành công. Danh sách vẫn hiển thị khi đang chờ; lỗi không xóa cache/danh sách hoặc báo thành công. Response cũ sau đổi tài khoản bị bỏ qua.

WATCH/WISHLIST/BORROWED vẫn được ghi nhận (WATCH vẫn chống trùng 24 giờ), nhưng không xóa gợi ý hoặc gọi AI tính lại mỗi tương tác. Gợi ý tính lần tiếp theo sẽ đọc các tương tác mới. Hết hạn được xử lý khi có request tiếp theo, **không phải job tự chạy mỗi 8 giờ**. Nút refresh yêu cầu tính lại; thuật toán vẫn có thể cho cùng danh sách nếu sở thích/dữ liệu chưa đổi.

## 3. Hội thoại chatbot

FE tạo UUID ngẫu nhiên cho hội thoại, gửi `message`, `language`, `conversationId`. BE tự xác định public hoặc userId từ JWT; không dùng userId do người dùng/AI truyền vào. Khóa public và khóa từng bạn đọc tách biệt.

Chỉ lưu `{language, turns:[{question,answer}]}`: tối đa sáu câu hỏi và **Markdown nguyên văn** do Gemini trả lời, kể cả câu trả lời chứa dữ liệu cá nhân của chính bạn đọc. Không có bản rút gọn 1.200 ký tự, registry sách, JSON tool hoặc thought signature. Lượt sau gửi các message user/model xen kẽ tới Gemini. Dữ liệu lịch sử chỉ là ngữ cảnh; số lượng tồn kho/công nợ/hạn trả phải truy vấn lại khi cần số liệu hiện tại.

Giới hạn toàn bộ JSON context **64 KiB UTF-8** (không phải đếm token chính xác). Vượt giới hạn thì bỏ toàn bộ cặp hỏi–đáp cũ nhất, không cắt ngang câu trả lời. Một lượt riêng quá lớn vẫn trả đầy đủ cho FE nhưng không giữ trong Redis. Namespace v2 không đọc định dạng context v1 cũ; key cũ tự hết hạn.

Ví dụ: “Clean Code ở CS2 còn không?” → tool JSON → Gemini trả Có/Không và tên/link sách → “Còn CS1?” đọc tham chiếu trong hỏi–đáp, gọi tool mới cho CS1. Nếu lịch sử không xác định rõ đầu sách thì cần hỏi lại hoặc tìm lại. Call ID/function response/thought signature chỉ được giữ trong request hiện tại, không đưa vào lịch sử Redis.

Hội thoại được khóa khi đang xử lý; lưu bằng compare-and-set để request cũ không ghi đè context mới. Xóa hội thoại tạo tombstone có TTL để request đang chạy không khôi phục context đã xóa. FE xóa/đổi UUID khi người dùng xóa hội thoại, đổi tài khoản/ngôn ngữ; hủy response đang chờ. Đăng xuất hợp lệ làm đổi phiên bản namespace của tài khoản, khiến toàn bộ context cũ không còn được truy cập; dữ liệu cũ tự hết hạn trong tối đa 30 phút. Đóng khung chat chỉ ẩn, không xóa ngữ cảnh.

Đây là bộ nhớ ngắn hạn, không phải lịch sử chat dài hạn hoặc vector tri thức. UUID không thay thế JWT cho dữ liệu cá nhân. Câu hỏi, hỏi–đáp cũ và JSON tra cứu cá nhân được phép trong lượt hiện tại có thể được gửi sang Gemini theo thiết kế đã chốt. Không gửi mật khẩu/OTP/token. Chi tiết luồng và thông báo quyền riêng tư nguồn revision 3 nằm tại [FAQ_CHATBOT.md](FAQ_CHATBOT.md).

## 4. Vận hành và dự phòng

Compose production dùng Redis 7.4 Alpine, `maxmemory 128mb`, giới hạn container `256m`, `allkeys-lru`, không công khai port ra VPS. 128 MB là trần dataset, không phải RAM cấp phát ngay. Giới hạn container còn bao gồm bộ nhớ ngoài dataset; cần theo dõi RSS/eviction thực tế.

Redis tắt snapshot/AOF vì đây là cache và bộ nhớ tạm. Restart BE vẫn giữ context khi Redis còn chạy; restart Redis làm mất context/cache. Redis có thể đẩy key ra sớm khi chạm giới hạn RAM: TTL là thời gian tối đa, không bảo đảm lưu đủ thời gian khi thiếu bộ nhớ.

Redis lỗi: recommendation ưu tiên snapshot PostgreSQL còn mới, sau đó tính mới/fallback như bình thường; khóa cục bộ còn hoạt động nhưng không chống trùng giữa nhiều replica khi Redis mất kết nối. Chatbot trả lời từng câu độc lập, FE nhắc khi context cũ hết hạn/chưa lưu được. Không làm API health chung báo DOWN chỉ vì Redis mất kết nối. Không xóa volume database để xử lý lỗi Redis.

Production chỉ dùng `.env.prod` tại **LMS gốc**:

```dotenv
# Tùy chọn; Redis và Backend nhận cùng giá trị qua Compose.
REDIS_PASSWORD=
```

Compose gán `REDIS_HOST=redis`. Khi BE chạy ngoài Docker, dùng `REDIS_HOST=localhost`, `REDIS_PORT=6379`, cùng mật khẩu nếu có. Dev Compose chỉ bind Redis vào `127.0.0.1:6379`. Không đưa các biến này vào Vite/frontend.

Backend CD hiện có `docker compose ... up -d --build backend`: dependency Redis sẽ được khởi động và chờ healthcheck. Không cần pip install hoặc thay model AI. Nếu dùng lệnh `up --no-deps` thủ công, phải khởi động Redis riêng trước. Để kiểm tra trạng thái, dùng `docker compose --env-file .env.prod -f docker-compose.prod.yml ps redis backend` và `exec -T redis redis-cli INFO memory` (healthcheck dùng `REDISCLI_AUTH` khi có mật khẩu).

## 5. Kiểm thử

- Unit test: TTL 8 tiếng không trượt theo lượt đọc; cache-first; ranking; PostgreSQL/trending fallback; refresh thành công/thất bại; khóa cạnh tranh; Redis mất kết nối; giới hạn input.
- Integration Testcontainers: Flyway/PostgreSQL thật + Redis thật; TTL, ownership, sáu lượt, compare-and-set, tombstone, đăng xuất, hết hạn; recommendation hydrate bằng SQL thật.
- FE: refresh không làm mất danh sách, lỗi/thành công, response muộn sau đổi tài khoản; conversation ID, xóa, Markdown an toàn, đóng panel vẫn nhận kết quả, không còn section/thẻ tool.
- Gemini dùng HTTP mock để kiểm tra hợp đồng, không tuyên bố độ chính xác model từ mock. Cần nghiệm thu thêm các câu Việt/Anh, không dấu, câu nhiều ý và tham chiếu qua nhiều lượt trên provider thật trước khi đánh giá chất lượng production.
