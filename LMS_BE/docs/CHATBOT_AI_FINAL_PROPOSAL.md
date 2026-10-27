# Đề xuất cuối cùng: Trợ lý tra cứu Library74

Ngày cập nhật: **28/10/2026**. Bổ sung hội thoại nhiều tool, phân biệt lỗi, Redis context và cache recommendation 8 tiếng theo [REDIS_SHARED_STATE.md](REDIS_SHARED_STATE.md).

Đây là phạm vi đã thống nhất và dùng để hiện thực trợ lý tra cứu. Mã nguồn BE/AI/FE đã được bổ sung theo phạm vi này; hướng dẫn cấu hình, giới hạn và kiểm thử nằm tại [FAQ_CHATBOT.md](FAQ_CHATBOT.md). Hai tài liệu thảo luận/kế hoạch sơ bộ đã được loại bỏ để tránh dùng nhầm thiết kế cũ. Chưa triển khai lên production.

## 1. Mục tiêu

Nâng chatbot FAQ hiện tại thành trợ lý tra cứu Library74 kết hợp **RAG và tool calling chỉ đọc**. Chatbot có thể hướng dẫn sử dụng, trả chính sách hiện hành, tìm/xem thông tin sách và tra cứu dữ liệu của chính bạn đọc đã đăng nhập.

Tiếp tục hỗ trợ Việt/Anh, light/dark, câu trả lời có bố cục, thẻ thông tin và liên kết nguồn. Không trả lời kiến thức bên ngoài nguồn được cung cấp, không mở Internet/source code, không thực hiện nghiệp vụ thay người dùng. Khi thiếu dữ liệu, hỏi lại hoặc nói chưa xác nhận được, không điền bằng suy đoán.

## 2. Ba nguồn thông tin

| Tầng | Nội dung | Cơ chế |
|---|---|---|
| Hướng dẫn chung | Giới thiệu, tác giả website, tài khoản, tìm sách, mượn/đặt trước/nhận/gia hạn/trả, cọc/phạt ở mức hướng dẫn, liên hệ | Nguồn nội dung chuẩn dùng chung với website; RAG qua pgvector |
| Dữ liệu công khai hiện hành | Chính sách, metadata sách, bản sao còn khả dụng, cơ sở/vị trí kệ, điểm đánh giá tổng hợp, sách mới/mượn nhiều | Tool BE chỉ đọc, lấy dữ liệu tại thời điểm tra cứu |
| Dữ liệu của bạn đọc | Sách đang mượn, lịch sử, khoản phạt, đặt trước, yêu thích và thông báo của chính tài khoản | Tool BE chỉ đọc, bắt buộc xác thực và giới hạn chủ thể từ JWT |

Không embedding tồn kho, chính sách đang thay đổi hoặc dữ liệu cá nhân vào kho hướng dẫn chung. Không dùng toàn bộ database làm nguồn cho LLM.

## 3. Danh sách tool trong phạm vi

Các tên dưới đây tương ứng với allowlist trong `ChatbotTool`. Dispatcher gọi adapter truy vấn chỉ đọc, không gọi lại HTTP controller và không bắt buộc tương ứng một endpoint.

### Tool công khai

| Tool | Nhiệm vụ | Giới hạn |
|---|---|---|
| `search_library_help` | Tìm hướng dẫn phù hợp trong kho tri thức | Chỉ nguồn hướng dẫn đã duyệt và còn hiệu lực |
| `get_current_circulation_policy` | Đọc số ngày/lượt, cửa sổ gia hạn, thời hạn nhận, mức cọc/phí và điều kiện chính sách công khai | Không trả thông tin Admin chỉnh sửa hoặc dữ liệu cá nhân |
| `search_catalog` | Tìm sách theo tên, tác giả, ISBN, từ khóa và bộ lọc được hỗ trợ | Chỉ danh mục công khai; không tìm nội dung chương/PDF |
| `get_publication_details` | Xem tác giả, ISBN, phiên bản, năm, nhà xuất bản, mô tả/tóm tắt đã lưu và thông tin danh mục | Không tạo tóm tắt từ kiến thức ngoài; không trả URL upload, tài liệu nội bộ hoặc trường kỹ thuật không cần thiết |
| `get_publication_availability` | Xem bản sao khả dụng, cơ sở, barcode và vị trí kệ được phép công khai | Không lộ ai đang giữ bản sao; không giữ chỗ hoặc thay đổi trạng thái |
| `list_publications` | Liệt kê sách mới hoặc mượn nhiều theo bộ lọc có sẵn | Dùng dữ liệu xếp hạng hiện có, không chạy job cập nhật |
| `get_publication_rating_summary` | Xem điểm trung bình, số lượng và phân bố đánh giá nếu có | Chỉ thống kê; không trích dữ liệu cá nhân của người đánh giá |

### Tool của bạn đọc đã đăng nhập

| Tool | Nhiệm vụ |
|---|---|
| `get_my_loans` | Tra cứu sách đang mượn, hạn trả và trạng thái của mình |
| `get_my_loan_history` | Tra cứu lịch sử mượn–trả của mình theo khoảng thời gian và phân trang |
| `get_my_fines` | Tra cứu khoản phạt, trạng thái thanh toán và tổng hợp của mình |
| `get_my_reservations` | Tra cứu yêu cầu đặt trước, hàng đợi/hạn nhận nếu dữ liệu nguồn có cung cấp |
| `get_my_wishlist` | Xem danh sách yêu thích, không thêm/xóa |
| `get_my_notifications` | Xem thông báo liên quan, không đánh dấu đã đọc |

Những tool này không nhận userId/MSSV từ LLM để chọn chủ thể. BE lấy danh tính từ phiên đã xác thực, kiểm tra vai trò bạn đọc và quyền sở hữu dữ liệu trước khi thực thi. Người chưa đăng nhập chỉ được dùng tool công khai.

Không đưa API tra cứu người khác của thủ thư hoặc API Admin vào bộ tool bạn đọc. Trong phạm vi này, thủ thư/Admin có thể dùng hướng dẫn và tra cứu sách công khai nhưng không có trợ lý quản trị hoặc tra cứu hồ sơ người khác.

## 4. Nguyên tắc chỉ đọc bắt buộc

- Dùng danh sách cho phép cụ thể; không tự xuất toàn bộ Swagger hoặc mọi endpoint GET thành tool.
- Kiểm tra cả chuỗi gọi bên dưới: controller, use case, repository, AI service và tác vụ liên quan. HTTP method không đủ để xác định chỉ đọc.
- Không có tool gọi URL/endpoint/SQL tùy ý. Tham số được khai báo và kiểm tra; người dùng hoặc LLM không quyết định quyền truy cập.
- Không tự ghi lịch sử tìm kiếm/lượt xem, tương tác, cache gợi ý hoặc thay đổi trạng thái khi gọi tool đọc. Nếu hàm hiện có kèm ghi, dùng nhánh đọc riêng hoặc loại khỏi danh sách.
- Không cấp URL upload hoặc khả năng ghi gián tiếp, dù endpoint mang tên GET.
- Log vận hành được xử lý riêng, không được dùng làm lý do tạo bản ghi nghiệp vụ hoặc lưu hội thoại/dữ liệu cá nhân không cần thiết.

Các trường hợp đã thấy trong source cần loại trừ/điều chỉnh trước khi tái sử dụng:

| Trường hợp | Cách xử lý |
|---|---|
| `GET /publications/{id}/document-upload-url` | Loại trừ, vì cấp khả năng upload |
| `GET /recommendations` gọi AI và luồng AI có persist vào `ai_recommendations` | Không dùng nguyên trạng làm tool chỉ đọc; cá nhân hóa từ recommender không thuộc bộ tool lần này |
| `POST /publications/{id}/view` | Không tự gọi kèm tool xem sách |
| Đồng bộ đơn thanh toán, đánh dấu thông báo đã đọc, refresh/reprocess/vectorize | Loại trừ vì có thể cập nhật dữ liệu |

Giới hạn chỉ đọc này áp dụng cho **tool phục vụ hội thoại**. Job đồng bộ tri thức riêng vẫn được tạo/cập nhật vector trong kho tri thức theo quy trình được kiểm soát; không vì thế mà cho LLM quyền gọi job đó.

## 5. Nguồn hướng dẫn dùng chung và cập nhật

- Quản lý nội dung có cấu trúc tại BE, Việt/Anh, có ID ổn định, tiêu đề, câu hỏi/alias, các bước/lưu ý, đường dẫn nguồn và phiên bản.
- Website và chatbot đọc cùng nguồn; ưu tiên chuyển nội dung Hướng dẫn/FAQ tĩnh và các thông tin giới thiệu/liên hệ được chatbot sử dụng. Không tự thiết kế lại toàn bộ giao diện website.
- Các thông số chính sách trên website tiếp tục lấy nguồn chính sách hiện hành; không lấy giá trị fallback của FE làm dữ liệu embedding.
- Nội dung thay đổi qua code review và deploy BE; chưa làm CMS, chưa theo dõi GitHub hoặc file source trên máy đang chạy.
- Chưa xây crawler. Nguồn dùng chung đáp ứng phạm vi hiện tại; crawl chỉ là khả năng bổ sung sau khi có nhu cầu và phạm vi riêng.

### Chia chunk

- Chia theo chủ đề/section, ranh giới câu và nhóm bước nghiệp vụ. Không ghép hai chủ đề không liên quan để đủ độ dài.
- FAQ ngắn giữ một chunk; đoạn dài mới tách. Tính số token bằng tokenizer của model embedding sau cùng cách tiền xử lý dùng khi encode.
- Khởi đầu với mục tiêu tối đa **224 token tính cả tiêu đề/ngữ cảnh**, overlap mục tiêu **32 token** cho các chunk liền nhau cùng chủ đề. Giới hạn thực tế luôn phải chừa chỗ cho special tokens và không vượt model đang load; không coi 224 là mức áp dụng được cho mọi model.
- Ưu tiên overlap câu hoàn chỉnh trong ngân sách; không đánh đổi giới hạn đầu vào để giữ một câu quá dài. Giữ hoặc nhắc lại điều kiện nghiệp vụ cần thiết khi tách.
- Với model mặc định `vietnamese-bi-encoder`, thực hiện tách từ tiếng Việt nhất quán cho tài liệu và câu hỏi; kiểm tra token limit/số chiều runtime. Đánh giá tiếng Anh riêng, không mặc định chất lượng ngang tiếng Việt.
- Chưa sửa pipeline PDF/embedding sách trong đợt này. Việc kiểm tra hoặc chuyển pipeline sách sang giới hạn token là task độc lập.

### Phiên bản và embedding lại

- Giữ bản chỉ mục đang hoạt động và một bản trước; chỉ bản hợp lệ, phù hợp nội dung/model hiện tại được dùng để trả lời. Dọn các bản cũ hơn sau khi công bố thành công; Git lưu lịch sử nội dung lâu dài.
- Chuẩn hóa dữ liệu rồi tính hash cho toàn kho và từng mục. Có fingerprint cho model/revision, tiền xử lý và cách chia chunk.
- Backend tự kiểm tra nền khi ứng dụng sẵn sàng, sau đó mỗi **60 giây** kiểm tra trạng thái đồng bộ để phục hồi khi AI service chưa sẵn sàng/lần trước lỗi. Không embedding lại nếu fingerprint/nội dung không đổi.
- Đổi/thêm nội dung: chỉ tính lại vector cho phần đầu vào thay đổi, tái sử dụng vector còn hợp lệ. Xóa nội dung: loại khỏi bản mới. Đổi model/chunking/tiền xử lý: tính lại phần bị ảnh hưởng.
- Đổi model Gemini diễn đạt, đổi chính sách hoặc restart BE với dữ liệu không đổi không tự buộc embedding lại hướng dẫn.
- Chuẩn bị/kiểm tra bản mới trước, rồi công bố nguyên tử; không xóa bản đang dùng trước khi tính vector. Có chốt chống job trùng giữa worker/instance và chống job cũ ghi đè phiên bản mới.
- Nếu chỉ mục lỗi thời hoặc đồng bộ lỗi, không âm thầm dùng bản hướng dẫn cũ đã biết là không còn phù hợp. FAQ chuẩn, chính sách và tool đọc độc lập vẫn hoạt động trong khả năng của từng nhánh.

## 6. Chính sách hiện hành

- Đọc cùng nguồn nghiệp vụ cung cấp API `/api/v1/circulation-policies`. Trong BE có thể tái sử dụng service/port thay vì HTTP gọi vòng lại chính mình; không để LLM tự gọi endpoint tùy ý.
- Lấy một snapshot hiện hành cho câu hỏi cần thông số, chỉ gồm các trường chính sách công khai cần thiết và thời điểm liên quan; không chuyển nguyên DTO có thông tin người chỉnh sửa sang model.
- Nếu Admin đổi chính sách, lần tra cứu sau lấy giá trị mới mà không cần embedding lại. Không dùng số cũ hoặc mặc định khi nguồn chính sách lỗi.
- Phân biệt quy định chung với khả năng thực hiện một giao dịch cụ thể. Ví dụ số lượt gia hạn tối đa không tự chứng minh cuốn sách của bạn đọc hiện đủ điều kiện gia hạn.

## 7. Luồng hội thoại và trách nhiệm các thành phần

```text
FE nhận câu hỏi
    ↓
Java BE xác định ngữ cảnh xác thực và bộ tool được phép
    ├── FAQ/alias chính xác → nội dung chuẩn; bổ sung chính sách nếu cần
    └── Gemini chọn tool hợp lệ → BE kiểm tra và thực thi
           ├── Hướng dẫn → Python embedding/truy xuất pgvector
           ├── Sách/chính sách → query/use case chỉ đọc trong BE
           └── Dữ liệu cá nhân → query/use case theo chủ thể từ JWT
                ↓
       BE chuẩn hóa kết quả → thẻ dữ liệu/câu trả lời từ nguồn
                ↓
       Gemini diễn đạt khi cần → BE kiểm tra → FE hiển thị
```

- Java BE giữ điều phối, xác thực/phân quyền, gọi Gemini, danh sách tool, fallback và kiểm tra kết quả.
- Python AI service phụ trách chia chunk, embedding, đồng bộ/truy xuất kho hướng dẫn; không cần nhận hồ sơ, khoản phạt hoặc danh sách sách cá nhân để làm các bước này.
- Gemini chọn chức năng và diễn đạt; không có kết nối DB, không tự thực thi Java hoặc sở hữu quyền truy cập dữ liệu.
- Tái sử dụng use case qua giao diện phù hợp, không tự gọi vòng HTTP nếu cùng tiến trình. Các kiểm tra chỉ đặt ở controller cần được bảo đảm lại ở biên thực thi tool.
- Cho phép chuỗi đọc có mục đích, ví dụ tìm tên sách → lấy chi tiết/khả dụng. Có giới hạn kỹ thuật số vòng gọi, kết quả và timeout để tránh vòng lặp vô hạn; không thêm lại hạn mức request/phút/ngày tự đặt đã bị yêu cầu bỏ.
- Không xây bộ nhớ hội thoại dài hạn. Redis giữ tối đa 6 lượt trong 30 phút không hoạt động, tách theo JWT/UUID; chỉ lưu phản hồi công khai và tham chiếu đầu sách/cơ sở, không gửi kết quả cá nhân sang model. Xóa hội thoại/đăng xuất làm mất hiệu lực context. Không xác định được sách thì hỏi lại. Tối đa 3 vòng lập kế hoạch và 4 lần thực thi tool, ngân sách dịch vụ AI khoảng 25 giây.

## 8. Tính đúng, quyền riêng tư và trải nghiệm

- Tên sách có nhiều phiên bản: cho chọn hoặc hỏi lại trước khi tra cứu bản sao; không tự coi kết quả đầu là đúng.
- Khả dụng là snapshot tại thời điểm hỏi, không phải cam kết giữ chỗ. Không tự thông báo đã mượn/đặt trước thành công.
- BE tính tổng phạt, số lượng, quá hạn và các kết luận có nghiệp vụ khi có chức năng tương ứng. Không để LLM cộng trang đầu rồi gọi là tổng; xử lý phân trang đầy đủ và giữ giới hạn trả kết quả.
- Chỉ kết luận điều nguồn dữ liệu cung cấp. Thiếu điều kiện gia hạn hoặc thông tin khác thì nói chưa xác nhận, không suy luận thành quyền thực hiện.
- Chỉ gửi dữ liệu cần cho câu hỏi sang Gemini. Không gửi JWT, mật khẩu, toàn bộ hồ sơ, email/điện thoại hoặc lịch sử đầy đủ khi không cần. Các con số/thẻ dữ liệu có thể render trực tiếp từ BE để giảm sai lệch và giảm dữ liệu gửi ra ngoài.
- Không lưu dữ liệu cá nhân vào vector, cache chung hoặc log hội thoại. Không dùng prompt làm cơ chế phân quyền; kiểm tra quyền ở BE cho từng lần thực thi.
- Câu trả lời hiển thị tóm tắt ngắn, bước/gạch đầu dòng/lưu ý khi phù hợp; sách hiển thị thẻ gồm bìa, tên, tác giả, thông tin cần thiết và link chi tiết.
- Nguồn/URL do BE kiểm tra và tạo từ dữ liệu hợp lệ. Nguồn cá nhân dẫn đến trang của chính người dùng, không có đường tắt sang tra cứu thủ thư/Admin.
- Nội dung không đủ hoặc ngoài phạm vi: hỏi lại/từ chối phù hợp. Mô tả sách và tài liệu tham khảo là dữ liệu, không được ghi đè chỉ dẫn hoặc mở quyền tool.
- RAG, schema và trích dẫn giúp giảm sai sót, không bảo đảm tuyệt đối LLM luôn hiểu và diễn đạt đúng.
- Giữ hai dòng thông báo UI đã bỏ theo yêu cầu ở trạng thái đã bỏ; không tự thêm lại chúng trong đợt nâng cấp.

## 9. Fallback và chi phí

- Giữ FAQ khớp chính xác không cần LLM, model dự phòng và cooldown theo lỗi provider đã có.
- AI service lỗi/chỉ mục chưa phù hợp: không dùng RAG lỗi thời; các tool BE chỉ đọc có thể tiếp tục nếu dịch vụ tương ứng sẵn sàng.
- Gemini lỗi: FAQ chuẩn vẫn hoạt động. Nếu đã có kết quả tool hợp lệ, có thể trả thẻ/số liệu từ BE; nếu chưa xác định được chức năng thì hướng về gợi ý/trang chức năng, không hứa xử lý mọi câu hỏi khi hết quota.
- Lỗi quyền truy cập không được đổi sang tool có quyền rộng hơn. Lỗi đầu ra/safety block không được gọi model khác chỉ để tìm đáp án dễ dãi hơn.
- Tool calling có thể cần nhiều lượt LLM hơn RAG thuần; tận dụng thẻ dữ liệu trực tiếp, giới hạn vòng gọi và tránh gửi dữ liệu thừa. Embedding local không loại bỏ quota/độ trễ Gemini.

## 10. Loại trừ rõ ràng

Không có tool mượn/gia hạn/hủy/đặt trước, nhận/trả, thu/sửa/miễn phạt, tạo đơn/QR hoặc sync thanh toán có cập nhật trạng thái, upload, ghi đánh giá/phản hồi, thay yêu thích, đánh dấu thông báo, sửa hồ sơ hoặc gửi ticket.

Không hỏi đáp sâu nội dung sách/PDF như chương 5, giải bài tập, trích toàn văn hoặc suy luận từ các chương. Không có tool truy xuất passage sách trong phạm vi này. Tóm tắt/mô tả sách chỉ lấy phần đã lưu và được phép hiển thị.

Không có crawler, CMS, trợ lý Admin/thủ thư, tra cứu tài khoản khác, SQL/URL tùy ý, Internet search hoặc cập nhật pipeline embedding sách trong đợt này.

## 11. Kiểm thử và thứ tự thực hiện

1. Chuẩn hóa nguồn hướng dẫn và chính sách; website/chatbot đọc cùng nội dung, xử lý lỗi tải mà không dùng quy định giả định.
2. Xây kho tri thức, chia chunk theo token và đồng bộ tự động an toàn; migration do Flyway quản lý, không embedding trong migration. Kiểm tra lại version migration thực tế, không sửa migration đã phát hành.
3. Tạo bộ tool công khai, dispatcher chỉ đọc và hiển thị nguồn/thẻ sách; kiểm tra thực tế các hàm được tái sử dụng không ghi dữ liệu.
4. Bổ sung chat cá nhân có xác thực, bộ tool theo vai trò và kiểm tra ownership; chat công khai tiếp tục dùng được khi chưa đăng nhập.
5. Kiểm thử BE/AI/FE: câu hỏi đúng/ngoài phạm vi, nhiều phiên bản sách, sách không có bản sao, tổng/phân trang, token hết hạn, truy cập chéo tài khoản, tool không được phép, prompt injection, provider lỗi và phiên bản kho cũ.
6. Kiểm thử migration, truy xuất và đồng bộ trên PostgreSQL/pgvector thật; test provider giả lập không thay cho đánh giá retrieval/LLM thực tế.
7. Đánh giá Việt/Anh/không dấu, nguồn trả lời, số liệu, độ trễ và số lần gọi AI. Không công bố chất lượng chỉ dựa vào mock.
8. Triển khai tương thích qua test/staging trước; bảo vệ đường đồng bộ nội bộ, giữ công tắc quay về FAQ, theo dõi phiên bản/lỗi mà không log dữ liệu cá nhân. Không tự push/deploy hoặc ghi production chỉ từ bản đề xuất này.

## 12. Tiêu chí nghiệm thu

- Website và chatbot dùng cùng hướng dẫn; hướng dẫn thay đổi được đồng bộ mà không nhân dữ liệu hoặc làm trống kho đang dùng khi lỗi.
- Không gửi chunk vượt giới hạn model; nội dung/model/tiền xử lý thay đổi được nhận biết để lập chỉ mục đúng.
- Chính sách và khả dụng lấy hiện hành, không trả con số cũ từ vector hoặc giá trị mặc định FE.
- Các tool được phép chỉ đọc trong toàn chuỗi gọi; không có thay đổi nghiệp vụ do hội thoại gây ra.
- Bạn đọc chỉ đọc dữ liệu của mình; chưa đăng nhập không nhận được dữ liệu cá nhân, kể cả model yêu cầu tool đó.
- Metadata/tóm tắt sách được lấy đúng đầu sách, có nguồn/link, không tự sinh nội dung chương hoặc giữ chỗ.
- Tổng tiền, phân trang, trạng thái và ngày hạn được xử lý đúng từ BE; không tạo dữ liệu giả khi API/AI lỗi.
- Widget Việt/Anh, light/dark, fallback, TypeScript/build và các suite liên quan không hồi quy. Hạn chế kiểm thử thực tế còn lại phải được báo rõ.

## 13. Các tài liệu liên quan

- [FAQ_CHATBOT.md](FAQ_CHATBOT.md): kiến trúc đã hiện thực, cấu hình, cập nhật nguồn và kiểm thử.
- [BROWSER_ROUTER_MIGRATION.md](../../LMS_FE/docs/BROWSER_ROUTER_MIGRATION.md): thay đổi router độc lập đã push.
